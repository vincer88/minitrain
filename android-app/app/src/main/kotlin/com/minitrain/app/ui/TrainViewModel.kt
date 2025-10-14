package com.minitrain.app.ui

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.repository.TrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

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

    private val realtimeJob: Job
    private val telemetryJob: Job

    init {
        realtimeJob = repository.startRealtime(controlState)
        telemetryJob = scope.launch {
            repository.telemetry.collect { telemetry ->
                _telemetry.value = telemetry
            }
        }
    }

    fun setTargetSpeed(speed: Double) {
        val sanitized = speed.coerceIn(0.0, 5.0)
        _controlState.value = _controlState.value.copy(targetSpeed = sanitized, emergencyStop = false)
    }

    fun toggleHeadlights(enabled: Boolean) {
        _controlState.value = _controlState.value.copy(headlights = enabled)
    }

    fun toggleHorn(enabled: Boolean) {
        _controlState.value = _controlState.value.copy(horn = enabled)
    }

    fun setDirection(direction: Direction) {
        _controlState.value = _controlState.value.copy(direction = direction)
    }

    fun emergencyStop() {
        _controlState.value = _controlState.value.copy(targetSpeed = 0.0, emergencyStop = true)
    }

    fun clear() {
        realtimeJob.cancel()
        telemetryJob.cancel()
        scope.launch { repository.stopRealtime() }
    }
}
