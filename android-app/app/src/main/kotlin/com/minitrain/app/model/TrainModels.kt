package com.minitrain.app.model

import kotlinx.serialization.Serializable

enum class Direction {
    FORWARD,
    REVERSE
}

@Serializable
data class TrainCommand(
    val command: String,
    val value: String? = null
)

@Serializable
data class Telemetry(
    val speedMetersPerSecond: Double,
    val motorCurrentAmps: Double,
    val batteryVoltage: Double,
    val temperatureCelsius: Double,
    val headlights: Boolean,
    val horn: Boolean,
    val direction: Direction,
    val emergencyStop: Boolean
)

@Serializable
data class ControlState(
    val targetSpeed: Double,
    val direction: Direction,
    val headlights: Boolean,
    val horn: Boolean,
    val emergencyStop: Boolean
)

