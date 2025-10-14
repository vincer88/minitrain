package com.minitrain.app.network

import com.minitrain.app.model.ControlState
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

class CommandChannelClient(
    private val endpoint: String,
    private val sessionId: UUID,
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
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
    private val queuedCommands = ArrayDeque<CommandFrame>()
    private val mutex = Mutex()
    private var job: Job? = null
    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 16)
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    fun start(stateFlow: StateFlow<ControlState>): Job {
        val newJob = scope.launch { runLoop(stateFlow) }
        job = newJob
        return newJob
    }

    suspend fun sendCommand(kind: CommandKind, payload: ByteArray = byteArrayOf()) {
        val framePayload = ByteArray(1 + payload.size)
        framePayload[0] = kind.code
        payload.copyInto(framePayload, 1)
        val frame = CommandFrame(
            buildHeader(sessionBytes, sequence.incrementAndGet(), clock.instant(), CommandPayloadType.Command, framePayload.size),
            framePayload
        )
        mutex.withLock { queuedCommands.addLast(frame) }
    }

    suspend fun stop() {
        mutex.withLock {
            job?.cancel()
            job = null
            queuedCommands.clear()
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
        var previousState: ControlState? = null
        var interval = Duration.ofMillis(20)
        while (scope.isActive && session.isOpen) {
            var congested = false
            while (true) {
                val pending = mutex.withLock {
                    if (queuedCommands.isEmpty()) null else queuedCommands.removeFirst()
                } ?: break
                if (!session.send(CommandFrameSerializer.encode(pending))) {
                    congested = true
                    break
                }
            }

            val currentState = stateFlow.value
            val frames = buildStateFrames(sessionBytes, { sequence.incrementAndGet() }, { clock.instant() }, currentState, previousState)
            previousState = currentState
            for (frame in frames) {
                if (!session.send(CommandFrameSerializer.encode(frame))) {
                    congested = true
                }
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
                if (frame.header.payloadType == CommandPayloadType.LegacyText) {
                    val text = frame.payload.decodeToString()
                    val telemetry = TelemetryParser.parse(text)
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
