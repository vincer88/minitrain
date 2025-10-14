package com.minitrain.app

import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.LightsSource
import com.minitrain.app.model.LightsState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TelemetrySource
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandChannelClient
import com.minitrain.app.network.CommandWebSocketFactory
import com.minitrain.app.network.CommandWebSocketSession
import androidx.media3.common.Player
import com.minitrain.app.network.UnconfiguredVideoStreamClient
import com.minitrain.app.network.VideoStreamClient
import com.minitrain.app.network.VideoStreamState
import com.minitrain.app.network.buildRealtimeHttpClient
import com.minitrain.app.repository.HttpTrainTransport
import com.minitrain.app.repository.TrainRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class TrainRepositoryTest {
    private class StubSession : CommandWebSocketSession {
        override val isOpen: Boolean = true
        override suspend fun send(frame: ByteArray): Boolean = true
        override suspend fun close(reason: io.ktor.websocket.CloseReason) {}
        override suspend fun receive(): ByteArray? = null
    }

    private class RecordingVideoStreamClient : VideoStreamClient {
        private val _state = kotlinx.coroutines.flow.MutableStateFlow<VideoStreamState>(VideoStreamState.Idle)
        override val state: kotlinx.coroutines.flow.StateFlow<VideoStreamState> = _state
        override val player: Player? = null
        val started = mutableListOf<String>()
        var stopCount = 0

        override fun start(url: String) {
            started.add(url)
            _state.value = VideoStreamState.Buffering
        }

        override fun stop() {
            stopCount += 1
            _state.value = VideoStreamState.Idle
        }

        override fun release() {}
    }

    private fun buildRepository(
        scope: TestScope,
        legacy: HttpTrainTransport,
        enableFallback: Boolean = true,
        videoClient: VideoStreamClient = UnconfiguredVideoStreamClient()
    ): TrainRepository {
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID(0, 0),
            client = buildRealtimeHttpClient(),
            scope = scope,
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        ) { _, _ -> CommandWebSocketFactory { StubSession() } }
        return TrainRepository(client, legacy, legacyFallbackEnabled = enableFallback, videoStreamClient = videoClient)
    }

    @Test
    fun `send command posts encoded payload`() = runTest(StandardTestDispatcher()) {
        lateinit var capturedRequest: HttpRequestData
        val engine = MockEngine { request ->
            capturedRequest = request
            respond(content = "OK", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val legacy = HttpTrainTransport("http://localhost", client)
        val repository = buildRepository(this, legacy)

        repository.sendCommand(TrainCommand("set_speed", "1.0"))

        assertEquals(HttpMethod.Post, capturedRequest.method)
        val body = capturedRequest.body as TextContent
        assertEquals("command=set_speed;value=1.0", body.text)
    }

    @Test
    fun `fetch telemetry parses json`() = runTest(StandardTestDispatcher()) {
        val telemetry = Telemetry(
            sessionId = "00000000-0000-0000-0000-000000000000",
            sequence = 1,
            commandTimestamp = 1234,
            speedMetersPerSecond = 1.0,
            motorCurrentAmps = 0.5,
            batteryVoltage = 11.1,
            temperatureCelsius = 30.0,
            appliedSpeedMetersPerSecond = 0.8,
            appliedDirection = Direction.FORWARD,
            headlights = true,
            horn = false,
            direction = Direction.FORWARD,
            emergencyStop = false,
            activeCab = ActiveCab.FRONT,
            lightsState = LightsState.FRONT_WHITE_REAR_RED,
            lightsSource = LightsSource.AUTOMATIC,
            source = TelemetrySource.INSTANTANEOUS,
            lightsOverrideMask = 0,
            lightsTelemetryOnly = false
        )
        val json = Json { encodeDefaults = true }
        val engine = MockEngine {
            respond(
                content = json.encodeToString(Telemetry.serializer(), telemetry),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val legacy = HttpTrainTransport("http://localhost", client)
        val repository = buildRepository(this, legacy)

        val parsed = repository.fetchTelemetry()
        assertEquals(telemetry, parsed)
    }

    @Test
    fun `push state posts json`() = runTest(StandardTestDispatcher()) {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond("OK", HttpStatusCode.OK, headersOf())
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val legacy = HttpTrainTransport("http://localhost", client)
        val repository = buildRepository(this, legacy)

        val state = ControlState(1.0, Direction.REVERSE, true, false, false)
        repository.pushState(state)

        assertEquals(HttpMethod.Post, captured.method)
        val contentType = captured.headers[HttpHeaders.ContentType]
        assertTrue(contentType == null || contentType.startsWith("application/json"))
        val body = captured.body as TextContent
        assertTrue(body.text.contains("\"direction\":\"REVERSE\""))
    }

    @Test
    fun `legacy transport disabled without flag`() = runTest(StandardTestDispatcher()) {
        val engine = MockEngine { respond("OK", HttpStatusCode.OK, headersOf()) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val legacy = HttpTrainTransport("http://localhost", client)
        val repository = buildRepository(this, legacy, enableFallback = false)

        assertFailsWith<IllegalStateException> { repository.fetchTelemetry() }
        assertFailsWith<IllegalStateException> { repository.sendCommand(TrainCommand("test")) }
        assertFailsWith<IllegalStateException> {
            repository.pushState(ControlState(0.0, Direction.FORWARD, false, false, false))
        }
    }

    @Test
    fun `start and stop video stream delegates to client`() = runTest(StandardTestDispatcher()) {
        val engine = MockEngine { respond("OK", HttpStatusCode.OK, headersOf()) }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val legacy = HttpTrainTransport("http://localhost", client)
        val videoClient = RecordingVideoStreamClient()
        val repository = buildRepository(this, legacy, videoClient = videoClient)

        repository.startVideoStream("https://example.com/stream.m3u8")

        assertEquals(listOf("https://example.com/stream.m3u8"), videoClient.started)
        assertEquals(VideoStreamState.Buffering, videoClient.state.value)

        repository.stopVideoStream()
        assertEquals(1, videoClient.stopCount)
    }
}
