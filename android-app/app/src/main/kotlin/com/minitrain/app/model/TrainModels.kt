package com.minitrain.app.model

import kotlinx.serialization.Serializable

enum class Direction {
    FORWARD,
    REVERSE
}

@Serializable
enum class ActiveCab {
    NONE,
    FRONT,
    REAR
}

@Serializable
enum class LightsState {
    BOTH_RED,
    FRONT_WHITE_REAR_RED,
    FRONT_RED_REAR_WHITE,
    BOTH_OFF,
    BOTH_WHITE
}

@Serializable
enum class LightsSource {
    AUTOMATIC,
    OVERRIDE,
    FAIL_SAFE
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
    val emergencyStop: Boolean,
    val activeCab: ActiveCab,
    val lightsState: LightsState,
    val lightsSource: LightsSource,
    val lightsOverrideMask: Int,
    val lightsTelemetryOnly: Boolean
)

@Serializable
data class ControlState(
    val targetSpeed: Double,
    val direction: Direction,
    val headlights: Boolean,
    val horn: Boolean,
    val emergencyStop: Boolean
)

