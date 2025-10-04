package com.minitrain.app.ui

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.toProtocolValue
import com.minitrain.app.repository.TrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class TrainViewModel(
    private val repository: TrainRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()

    private val _controlState = MutableStateFlow(
        ControlState(
            targetSpeed = 0.0,
            direction = Direction.FORWARD,
            headlights = false,
            horn = false,
            emergencyStop = false
        )
    )
    val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private var pollingJob: Job? = null

    fun setTargetSpeed(speed: Double) {
        val sanitized = speed.coerceIn(0.0, 5.0)
        _controlState.value = _controlState.value.copy(targetSpeed = sanitized, emergencyStop = false)
        sendState()
    }

    fun toggleHeadlights(enabled: Boolean) {
        _controlState.value = _controlState.value.copy(headlights = enabled)
        scope.launch { repository.sendCommand(TrainCommand("headlights", if (enabled) "on" else "off")) }
    }

    fun toggleHorn(enabled: Boolean) {
        _controlState.value = _controlState.value.copy(horn = enabled)
        scope.launch { repository.sendCommand(TrainCommand("horn", if (enabled) "on" else "off")) }
    }

    fun setDirection(direction: Direction) {
        _controlState.value = _controlState.value.copy(direction = direction)
        scope.launch { repository.sendCommand(TrainCommand("set_direction", direction.toProtocolValue())) }
    }

    fun emergencyStop() {
        _controlState.value = _controlState.value.copy(targetSpeed = 0.0, emergencyStop = true)
        scope.launch { repository.sendCommand(TrainCommand("emergency")) }
    }

    private fun sendState() {
        val state = _controlState.value
        scope.launch { repository.pushState(state) }
    }

    fun startTelemetryPolling(interval: Duration = 1.seconds) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (true) {
                val telemetry = withContext(Dispatchers.Default) { repository.fetchTelemetry() }
                _telemetry.value = telemetry
                kotlinx.coroutines.delay(interval)
            }
        }
    }

    fun stopTelemetryPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
}
