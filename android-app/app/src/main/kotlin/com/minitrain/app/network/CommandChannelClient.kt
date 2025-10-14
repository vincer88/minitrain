package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.receiveCatching
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayDeque

interface CommandWebSocketSession {
    val isOpen: Boolean
    suspend fun send(frame: ByteArray): Boolean
    suspend fun close(reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, ""))
    suspend fun receive(): ByteArray?
}

fun interface CommandWebSocketFactory {
    suspend fun open(): CommandWebSocketSession
}

private class KtorCommandWebSocketSession(private val delegate: DefaultClientWebSocketSession) : CommandWebSocketSession {
    override val isOpen: Boolean
        get() = delegate.coroutineContext.isActive

    override suspend fun send(frame: ByteArray): Boolean {
        delegate.send(Frame.Binary(fin = true, data = frame))
        return true
    }

    override suspend fun close(reason: CloseReason) {
        delegate.close(reason)
    }

    override suspend fun receive(): ByteArray? {
        val frame = delegate.incoming.receiveCatching().getOrNull() ?: return null
        return when (frame) {
            is Frame.Binary -> frame.data.copyOf()
            is Frame.Text -> frame.readBytes()
            is Frame.Close -> null
            else -> null
        }
    }
}

data class TelemetryFailsafeConfig(
    val staleThreshold: Duration = Duration.ofMillis(150),
    val rampDuration: Duration = Duration.ofMillis(1000)
) {
    init {
        require(staleThreshold > Duration.ZERO) { "Stale threshold must be positive" }
        require(rampDuration > Duration.ZERO) { "Ramp duration must be positive" }
    }
}

private val FAILSAFE_LIGHTS_MASK: Byte = 0x0C.toByte()

private data class RampState(
    val startInstant: Instant,
    val initialSpeed: Double,
    val direction: Direction
)

class CommandChannelClient(
    private val endpoint: String,
    private val sessionId: UUID,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val failsafeConfig: TelemetryFailsafeConfig = TelemetryFailsafeConfig(),
    private val factoryProvider: (HttpClient, String) -> CommandWebSocketFactory = { httpClient, url ->
        CommandWebSocketFactory {
            val builder = URLBuilder(url)
            if (builder.protocol.name !in listOf("ws", "wss")) {
                builder.protocol = URLProtocol.WSS
            }
            val session = httpClient.webSocketSession {
                url(builder.buildString())
            }
            KtorCommandWebSocketSession(session)
        }
    }
) {
    private val sequence = AtomicInteger(0)
    private val sessionBytes: ByteArray = uuidToLittleEndian(sessionId)
    private val queuedAuxiliaryPayloads = ArrayDeque<ByteArray>()
    private val mutex = Mutex()
    private var job: Job? = null
    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 16)
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()
    @Volatile
    private var lastTelemetryInstant: Instant? = null

    fun start(stateFlow: StateFlow<ControlState>): Job {
        lastTelemetryInstant = null
        val newJob = scope.launch { runLoop(stateFlow) }
        job = newJob
        return newJob
    }

    suspend fun sendCommand(payload: ByteArray) {
        if (payload.isEmpty()) {
            return
        }
        mutex.withLock { queuedAuxiliaryPayloads.addLast(payload.copyOf()) }
    }

    suspend fun stop() {
        mutex.withLock {
            job?.cancel()
            job = null
            queuedAuxiliaryPayloads.clear()
        }
    }

    private suspend fun runLoop(stateFlow: StateFlow<ControlState>) {
        var attempt = 0
        val factory = factoryProvider(client, endpoint)
        while (scope.isActive) {
            try {
                val session = factory.open()
                attempt = 0
                coroutineScope {
                    val receiver = launch { receiveLoop(session) }
                    try {
                        sendLoop(session, stateFlow)
                    } finally {
                        receiver.cancel()
                        receiver.join()
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                attempt += 1
                val backoff = Duration.ofMillis((500L * attempt).coerceAtMost(5_000L))
                delay(backoff.toMillis())
            }
        }
    }

    private suspend fun sendLoop(session: CommandWebSocketSession, stateFlow: StateFlow<ControlState>) {
        var interval = Duration.ofMillis(20)
        var rampState: RampState? = null
        while (scope.isActive && session.isOpen) {
            var congested = false
            val currentState = stateFlow.value
            val now = clock.instant()
            val telemetryInstant = lastTelemetryInstant
            val stale = telemetryInstant?.let { Duration.between(it, now) > failsafeConfig.staleThreshold } ?: false
            rampState = when {
                stale && rampState == null -> RampState(now, currentState.targetSpeed, currentState.direction)
                !stale -> null
                else -> rampState
            }
            val activeRamp = rampState
            val effectiveState = if (activeRamp != null) {
                val elapsed = Duration.between(activeRamp.startInstant, now)
                val rampDurationNanos = failsafeConfig.rampDuration.toNanos()
                val elapsedNanos = elapsed.toNanos().coerceAtLeast(0L)
                val progress = (elapsedNanos.toDouble() / rampDurationNanos.toDouble()).coerceIn(0.0, 1.0)
                val rampSpeed = (activeRamp.initialSpeed * (1.0 - progress)).coerceAtLeast(0.0)
                currentState.copy(
                    targetSpeed = rampSpeed,
                    direction = if (progress >= 1.0) Direction.NEUTRAL else activeRamp.direction
                )
            } else {
                currentState
            }
            val auxiliaryPayload = mutex.withLock {
                if (queuedAuxiliaryPayloads.isEmpty()) null else queuedAuxiliaryPayloads.removeFirst()
            }
            val frame = buildStateFrames(
                sessionBytes,
                { sequence.incrementAndGet() },
                { clock.instant() },
                effectiveState,
                auxiliaryPayload ?: byteArrayOf(),
                lightsOverride = if (activeRamp != null) FAILSAFE_LIGHTS_MASK else null
            )
            if (!session.send(CommandFrameSerializer.encode(frame))) {
                congested = true
            }

            val targetInterval = if (congested) Duration.ofMillis(100) else Duration.ofMillis(20)
            interval = targetInterval
            delay(interval.toMillis())
        }
        session.close()
    }

    private suspend fun receiveLoop(session: CommandWebSocketSession) {
        while (scope.isActive && session.isOpen) {
            val payload = session.receive() ?: break
            try {
                val frame = CommandFrameSerializer.decode(payload)
                if (frame.header.lightsOverride.toInt() and 0x80 != 0) {
                    val text = frame.payload.decodeToString()
                    val telemetry = TelemetryParser.parse(text)
                    lastTelemetryInstant = telemetryTimestampToInstant(telemetry.commandTimestamp)
                    _telemetry.emit(telemetry)
                }
            } catch (_: Exception) {
                // Ignore malformed frames
            }
        }
        session.close()
    }
}

fun buildRealtimeHttpClient(base: HttpClient = HttpClient()): HttpClient = base.config {
    install(WebSockets)
}

private fun telemetryTimestampToInstant(timestampMicros: Long): Instant {
    val seconds = Math.floorDiv(timestampMicros, 1_000_000L)
    val micros = Math.floorMod(timestampMicros, 1_000_000L)
    val nanos = micros * 1_000L
    return Instant.ofEpochSecond(seconds, nanos)
}
