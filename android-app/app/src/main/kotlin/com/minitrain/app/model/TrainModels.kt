package com.minitrain.app.model

import kotlinx.serialization.Serializable

enum class Direction {
    NEUTRAL,
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
    val sessionId: String,
    val sequence: Long,
    val commandTimestamp: Long,
    val speedMetersPerSecond: Double,
    val motorCurrentAmps: Double,
    val batteryVoltage: Double,
    val temperatureCelsius: Double,
    val appliedSpeedMetersPerSecond: Double,
    val appliedDirection: Direction,
    val headlights: Boolean,
    val horn: Boolean,
    val direction: Direction,
    val emergencyStop: Boolean,
    val activeCab: ActiveCab,
    val lightsState: LightsState,
    val lightsSource: LightsSource,
    val source: TelemetrySource,
    val lightsOverrideMask: Int,
    val lightsTelemetryOnly: Boolean
)

@Serializable
enum class TelemetrySource {
    INSTANTANEOUS,
    AGGREGATED
}

@Serializable
data class ControlState(
    val targetSpeed: Double,
    val direction: Direction,
    val headlights: Boolean,
    val horn: Boolean,
    val emergencyStop: Boolean
)

@Serializable
data class TrainEndpoint(
    val id: String,
    val name: String,
    val commandEndpoint: String,
    val videoEndpoint: String? = null
)

@Serializable
data class TrainConnectionStatus(
    val isConnected: Boolean = false,
    val isAvailable: Boolean = true
)

@Serializable
data class TrainDirectoryEntry(
    val endpoint: TrainEndpoint,
    val status: TrainConnectionStatus = TrainConnectionStatus()
)

@Serializable
data class TrainDirectory(
    val trains: List<TrainDirectoryEntry> = emptyList()
)

