package com.minitrain.app.repository

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandEncoder
import com.minitrain.app.network.TelemetryParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class TrainRepository(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json()
        }
    }
) {

    open suspend fun sendCommand(command: TrainCommand): Unit = withContext(Dispatchers.IO) {
        val payload = CommandEncoder.encode(command)
        client.post("$baseUrl/command") {
            contentType(ContentType.Text.Plain)
            setBody(payload)
        }
        Unit
    }

    open suspend fun pushState(state: ControlState): Unit = withContext(Dispatchers.IO) {
        val serialized = CommandEncoder.encodeState(state)
        client.post("$baseUrl/state") {
            contentType(ContentType.Application.Json)
            setBody(serialized)
        }
        Unit
    }

    open suspend fun fetchTelemetry(): Telemetry = withContext(Dispatchers.IO) {
        val response = client.get("$baseUrl/telemetry").body<String>()
        TelemetryParser.parse(response)
    }
}
