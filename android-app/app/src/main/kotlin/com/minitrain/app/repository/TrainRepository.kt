package com.minitrain.app.repository

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandChannelClient
import com.minitrain.app.network.CommandKind
import com.minitrain.app.network.buildRealtimeHttpClient
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.UUID

@Deprecated("Migrating to binary WebSocket channel")
interface LegacyTrainTransport {
    suspend fun sendCommand(command: TrainCommand)
    suspend fun pushState(state: ControlState)
    suspend fun fetchTelemetry(): Telemetry
}

@Deprecated("Migrating to binary WebSocket channel")
open class HttpTrainTransport(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) { json() }
    },
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) : LegacyTrainTransport {
    override suspend fun sendCommand(command: TrainCommand) {
        withContext(Dispatchers.IO) {
            val builder = StringBuilder().apply {
                append("command=").append(command.command)
                command.value?.let { append(";value=").append(it) }
            }
            client.post("$baseUrl/command") {
                contentType(ContentType.Text.Plain)
                setBody(builder.toString())
            }
        }
    }

    override suspend fun pushState(state: ControlState) {
        withContext(Dispatchers.IO) {
            val encoded = json.encodeToString(state)
            client.post("$baseUrl/state") {
                contentType(ContentType.Application.Json)
                setBody(encoded)
            }
        }
    }

    override suspend fun fetchTelemetry(): Telemetry = withContext(Dispatchers.IO) {
        val response = client.get("$baseUrl/telemetry").body<String>()
        json.decodeFromString(response)
    }
}

open class TrainRepository(
    private val realtimeClient: CommandChannelClient,
    private val legacyTransport: LegacyTrainTransport? = null,
    private val legacyFallbackEnabled: Boolean = false
) {
    fun startRealtime(state: StateFlow<ControlState>): Job = realtimeClient.start(state)

    suspend fun sendRealtimeCommand(kind: CommandKind, payload: ByteArray = byteArrayOf()) {
        realtimeClient.sendCommand(kind, payload)
    }

    suspend fun stopRealtime() {
        realtimeClient.stop()
    }

    open val telemetry: Flow<Telemetry>
        get() = realtimeClient.telemetry

    @Deprecated("Legacy HTTP control path")
    open suspend fun sendCommand(command: TrainCommand) {
        if (!legacyFallbackEnabled) {
            error("Legacy transport disabled")
        }
        legacyTransport?.sendCommand(command)
    }

    @Deprecated("Legacy HTTP control path")
    open suspend fun pushState(state: ControlState) {
        if (!legacyFallbackEnabled) {
            error("Legacy transport disabled")
        }
        legacyTransport?.pushState(state)
    }

    @Deprecated("Legacy HTTP control path")
    open suspend fun fetchTelemetry(): Telemetry {
        if (!legacyFallbackEnabled) {
            error("Legacy transport disabled")
        }
        return legacyTransport?.fetchTelemetry()
            ?: throw IllegalStateException("Legacy transport not configured")
    }

    companion object {
        fun create(
            endpoint: String,
            sessionId: UUID,
            scope: kotlinx.coroutines.CoroutineScope,
            httpClient: HttpClient = buildRealtimeHttpClient(HttpClient()),
            clock: Clock = Clock.systemUTC(),
            legacyTransport: LegacyTrainTransport? = null,
            legacyFallbackEnabled: Boolean = false
        ): TrainRepository {
            val client = CommandChannelClient(endpoint, sessionId, httpClient, scope, clock)
            return TrainRepository(client, legacyTransport, legacyFallbackEnabled)
        }
    }
}
