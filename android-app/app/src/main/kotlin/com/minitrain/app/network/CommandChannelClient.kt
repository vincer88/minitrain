package com.minitrain.app.network

import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.LightsSource
import com.minitrain.app.model.LightsState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TelemetrySource
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

private data class RampState(
    val startInstant: Instant,
    val initialSpeed: Double,
    val direction: Direction
)

data class FailsafeRampStatus(
    val isActive: Boolean,
    val progress: Double,
    val direction: Direction?
) {
    init {
        require(progress in 0.0..1.0) { "Progress must be between 0.0 and 1.0" }
    }

    companion object {
        fun inactive(): FailsafeRampStatus = FailsafeRampStatus(false, 0.0, null)
    }
}

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
    companion object {
        private const val TELEMETRY_PAYLOAD_SIZE = 36
    }
    private val sequence = AtomicInteger(0)
    private val sessionBytes: ByteArray = uuidToLittleEndian(sessionId)
    private val queuedAuxiliaryPayloads = ArrayDeque<ByteArray>()
    private val mutex = Mutex()
    private var job: Job? = null
    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 16)
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()
    @Volatile
    private var lastTelemetryCommandTimestampMicros: Long? = null
    private val _failsafeRampStatus = MutableStateFlow(FailsafeRampStatus.inactive())
    val failsafeRampStatus: StateFlow<FailsafeRampStatus> = _failsafeRampStatus.asStateFlow()

    fun start(stateFlow: StateFlow<ControlState>): Job {
        lastTelemetryCommandTimestampMicros = null
        _failsafeRampStatus.value = FailsafeRampStatus.inactive()
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
        var rampCompleted = false
        var rampActiveLast = false
        while (scope.isActive && session.isOpen) {
            var congested = false
            val currentState = stateFlow.value
            val now = clock.instant()
            val telemetryTimestampMicros = lastTelemetryCommandTimestampMicros
            val telemetryInstant = telemetryTimestampMicros?.let { telemetryTimestampToInstant(it) }
            val stale = telemetryInstant?.let { Duration.between(it, now) > failsafeConfig.staleThreshold } ?: false
            rampState = when {
                stale && rampState == null && !rampCompleted && currentState.targetSpeed > 0.0 ->
                    RampState(now, currentState.targetSpeed, currentState.direction)
                !stale -> {
                    rampCompleted = false
                    null
                }
                else -> rampState
            }
            val activeRamp = rampState
            val rampComputation = if (activeRamp != null) {
                val elapsed = Duration.between(activeRamp.startInstant, now)
                val rampDurationNanos = failsafeConfig.rampDuration.toNanos()
                val elapsedNanos = elapsed.toNanos().coerceAtLeast(0L)
                val progress = (elapsedNanos.toDouble() / rampDurationNanos.toDouble()).coerceIn(0.0, 1.0)
                val complete = progress >= 1.0
                val rampSpeed = if (complete) 0.0 else (activeRamp.initialSpeed * (1.0 - progress)).coerceAtLeast(0.0)
                val direction = if (complete) Direction.NEUTRAL else activeRamp.direction
                if (complete) {
                    rampState = null
                    rampCompleted = true
                }
                Triple(
                    currentState.copy(
                        targetSpeed = rampSpeed,
                        direction = direction
                    ),
                    progress,
                    complete
                )
            } else {
                null
            }
            val effectiveState = rampComputation?.first ?: currentState
            val rampProgress = rampComputation?.second ?: 0.0
            val rampActive = rampComputation?.let { !it.third } ?: false
            val rampDirection = if (rampActive) activeRamp?.direction else null
            if (rampActive != rampActiveLast || (rampActive && _failsafeRampStatus.value.progress != rampProgress)) {
                _failsafeRampStatus.value = if (rampActive) {
                    FailsafeRampStatus(true, rampProgress, rampDirection)
                } else {
                    FailsafeRampStatus.inactive()
                }
            }
            rampActiveLast = rampActive
            val auxiliaryPayload = mutex.withLock {
                if (queuedAuxiliaryPayloads.isEmpty()) null else queuedAuxiliaryPayloads.removeFirst()
            }
            val frame = buildStateFrames(
                sessionBytes,
                { sequence.incrementAndGet() },
                { clock.instant() },
                effectiveState,
                auxiliaryPayload ?: byteArrayOf(),
                lightsOverride = null
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
                    val telemetry = decodeTelemetryFrame(frame) ?: continue
                    lastTelemetryCommandTimestampMicros = telemetry.commandTimestamp
                    if (_failsafeRampStatus.value.isActive) {
                        _failsafeRampStatus.value = FailsafeRampStatus.inactive()
                    }
                    _telemetry.emit(telemetry)
                }
            } catch (_: Exception) {
                // Ignore malformed frames
            }
        }
        session.close()
    }

    private fun decodeTelemetryFrame(frame: CommandFrame): Telemetry? {
        if (frame.payload.size < TELEMETRY_PAYLOAD_SIZE) {
            return null
        }
        val buffer = ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
        val speed = buffer.float.toDouble()
        val motorCurrent = buffer.float.toDouble()
        val batteryVoltage = buffer.float.toDouble()
        val temperature = buffer.float.toDouble()
        val appliedSpeed = buffer.float.toDouble()
        val failSafeProgress = buffer.float.toDouble().coerceIn(0.0, 1.0)
        val failSafeElapsed = buffer.int.toLong() and 0xFFFF_FFFFL
        val flags = buffer.get().toInt() and 0xFF
        val activeCab = decodeActiveCab(buffer.get().toInt() and 0xFF)
        val lightsState = decodeLightsState(buffer.get().toInt() and 0xFF)
        val lightsSource = decodeLightsSource(buffer.get().toInt() and 0xFF)
        val lightsOverrideMask = buffer.get().toInt() and 0xFF
        val telemetrySource = decodeTelemetrySource(buffer.get().toInt() and 0xFF)
        val appliedDirection = CommandFrameSerializer.protocolToDirection(buffer.get())
        buffer.get() // reserved

        val failSafeActive = flags and 0x01 != 0
        val lightsTelemetryOnly = flags and 0x02 != 0
        val sessionUuid = littleEndianBytesToUuid(frame.header.sessionId).toString()

        return Telemetry(
            sessionId = sessionUuid,
            sequence = frame.header.sequence.toLong() and 0xFFFF_FFFFL,
            commandTimestamp = frame.header.timestampMicros,
            speedMetersPerSecond = speed,
            motorCurrentAmps = motorCurrent,
            batteryVoltage = batteryVoltage,
            temperatureCelsius = temperature,
            appliedSpeedMetersPerSecond = appliedSpeed,
            appliedDirection = appliedDirection,
            headlights = (lightsOverrideMask and 0x03) != 0,
            horn = false,
            direction = frame.header.direction,
            emergencyStop = false,
            activeCab = activeCab,
            lightsState = lightsState,
            lightsSource = lightsSource,
            source = telemetrySource,
            lightsOverrideMask = lightsOverrideMask,
            lightsTelemetryOnly = lightsTelemetryOnly,
            failSafeProgress = failSafeProgress,
            failSafeElapsedMillis = failSafeElapsed,
            failSafeActive = failSafeActive
        )
    }

    private fun decodeActiveCab(code: Int): ActiveCab = when (code) {
        1 -> ActiveCab.FRONT
        2 -> ActiveCab.REAR
        else -> ActiveCab.NONE
    }

    private fun decodeLightsState(code: Int): LightsState = when (code) {
        1 -> LightsState.FRONT_WHITE_REAR_RED
        2 -> LightsState.FRONT_RED_REAR_WHITE
        3 -> LightsState.BOTH_OFF
        4 -> LightsState.BOTH_WHITE
        5 -> LightsState.BOTH_RED_FLASHING
        else -> LightsState.BOTH_RED
    }

    private fun decodeLightsSource(code: Int): LightsSource = when (code) {
        1 -> LightsSource.OVERRIDE
        2 -> LightsSource.FAIL_SAFE
        else -> LightsSource.AUTOMATIC
    }

    private fun decodeTelemetrySource(code: Int): TelemetrySource = when (code) {
        1 -> TelemetrySource.AGGREGATED
        else -> TelemetrySource.INSTANTANEOUS
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
