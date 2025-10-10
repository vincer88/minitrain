package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CommandEncoder {
    private val json = Json { encodeDefaults = true }

    fun encode(command: TrainCommand): String {
        val builder = StringBuilder()
        builder.append("command=").append(command.command)
        command.value?.let {
            builder.append(";value=").append(it)
        }
        return builder.toString()
    }

    fun encodeState(state: ControlState): String = json.encodeToString(state)
}

object TelemetryParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): Telemetry = json.decodeFromString(raw)
}

fun Direction.toProtocolValue(): String = when (this) {
    Direction.NEUTRAL -> "neutral"
    Direction.FORWARD -> "forward"
    Direction.REVERSE -> "reverse"
}
