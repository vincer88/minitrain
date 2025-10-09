package com.minitrain.app

import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.LightsSource
import com.minitrain.app.model.LightsState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandChannelClient
import com.minitrain.app.network.CommandWebSocketFactory
import com.minitrain.app.network.CommandWebSocketSession
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

@OptIn(ExperimentalCoroutinesApi::class)
class TrainRepositoryTest {
    private class StubSession : CommandWebSocketSession {
        override val isOpen: Boolean = true
        override suspend fun send(frame: ByteArray): Boolean = true
        override suspend fun close(reason: io.ktor.websocket.CloseReason) {}
    }

    private fun buildRepository(scope: TestScope, legacy: HttpTrainTransport): TrainRepository {
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID(0, 0),
            client = buildRealtimeHttpClient(),
            scope = scope,
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        ) { _, _ -> CommandWebSocketFactory { StubSession() } }
        return TrainRepository(client, legacy)
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
            1.0,
            0.5,
            11.1,
            30.0,
            true,
            false,
            Direction.FORWARD,
            false,
            ActiveCab.FRONT,
            LightsState.FRONT_WHITE_REAR_RED,
            LightsSource.AUTOMATIC,
            0,
            false
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
}
