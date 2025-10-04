package com.minitrain.app

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.repository.TrainRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainRepositoryTest {
    @Test
    fun `send command posts encoded payload`() = runBlocking {
        lateinit var capturedRequest: HttpRequestData
        val engine = MockEngine { request ->
            capturedRequest = request
            respond(content = "OK", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val repository = TrainRepository("http://localhost", client)

        repository.sendCommand(TrainCommand("set_speed", "1.0"))

        assertEquals(HttpMethod.Post, capturedRequest.method)
        val body = capturedRequest.body as TextContent
        assertEquals("command=set_speed;value=1.0", body.text)
    }

    @Test
    fun `fetch telemetry parses json`() = runBlocking {
        val telemetry = Telemetry(1.0, 0.5, 11.1, 30.0, true, false, Direction.FORWARD, false)
        val engine = MockEngine {
            respond(
                content = """{
                    "speedMetersPerSecond": ${telemetry.speedMetersPerSecond},
                    "motorCurrentAmps": ${telemetry.motorCurrentAmps},
                    "batteryVoltage": ${telemetry.batteryVoltage},
                    "temperatureCelsius": ${telemetry.temperatureCelsius},
                    "headlights": ${telemetry.headlights},
                    "horn": ${telemetry.horn},
                    "direction": "${telemetry.direction}",
                    "emergencyStop": ${telemetry.emergencyStop}
                }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val repository = TrainRepository("http://localhost", client)

        val parsed = repository.fetchTelemetry()
        assertEquals(telemetry, parsed)
    }

    @Test
    fun `push state posts json`() = runBlocking {
        lateinit var captured: HttpRequestData
        val engine = MockEngine { request ->
            captured = request
            respond("OK", HttpStatusCode.OK, headersOf())
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val repository = TrainRepository("http://localhost", client)

        val state = ControlState(1.0, Direction.REVERSE, true, false, false)
        repository.pushState(state)

        assertEquals(HttpMethod.Post, captured.method)
        val contentType = captured.headers[HttpHeaders.ContentType]
        assertTrue(contentType == null || contentType.startsWith("application/json"))
        val body = captured.body as TextContent
        assertTrue(body.text.contains("\"direction\":\"REVERSE\""))
    }
}
