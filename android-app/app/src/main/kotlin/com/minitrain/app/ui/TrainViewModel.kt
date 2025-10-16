package com.minitrain.app.ui

import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.network.FailsafeRampStatus
import androidx.media3.common.Player
import com.minitrain.app.network.VideoStreamState
import com.minitrain.app.repository.TrainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val _failsafeRampStatus = MutableStateFlow(FailsafeRampStatus.inactive())
    val failsafeRampStatus: StateFlow<FailsafeRampStatus> = _failsafeRampStatus.asStateFlow()
    private val _activeCab = MutableStateFlow(ActiveCab.NONE)
    val activeCab: StateFlow<ActiveCab> = _activeCab.asStateFlow()

    private val realtimeJob: Job
    private val telemetryJob: Job
    private val failsafeJob: Job
    private val videoJob: Job

    private val _videoStreamState = MutableStateFlow(VideoStreamUiState(VideoStreamState.Idle, false, null))
    val videoStreamState: StateFlow<VideoStreamUiState> = _videoStreamState.asStateFlow()

    init {
        realtimeJob = repository.startRealtime(controlState)
        telemetryJob = scope.launch {
            repository.telemetry.collect { telemetry ->
                _telemetry.value = telemetry
                _activeCab.value = telemetry.activeCab
            }
        }
        failsafeJob = scope.launch {
            var wasActive = false
            repository.failsafeRampStatus.collect { status ->
                val previouslyActive = wasActive
                wasActive = status.isActive
                _failsafeRampStatus.value = status
                if (previouslyActive && !status.isActive) {
                    _controlState.update {
                        if (it.direction == Direction.NEUTRAL) {
                            it
                        } else {
                            it.copy(direction = Direction.NEUTRAL)
                        }
                    }
                }
            }
        }
        videoJob = scope.launch {
            repository.videoStreamState.collect { state ->
                _videoStreamState.value = VideoStreamUiState(
                    state = state,
                    isBuffering = state is VideoStreamState.Buffering,
                    errorMessage = (state as? VideoStreamState.Error)?.message
                )
            }
        }
    }

    fun setTargetSpeed(speed: Double) {
        if (_failsafeRampStatus.value.isActive) return
        val sanitized = speed.coerceIn(0.0, 5.0)
        _controlState.value = _controlState.value.copy(targetSpeed = sanitized, emergencyStop = false)
    }

    fun toggleHeadlights(enabled: Boolean) {
        if (_failsafeRampStatus.value.isActive) return
        _controlState.value = _controlState.value.copy(headlights = enabled)
    }

    fun toggleHorn(enabled: Boolean) {
        if (_failsafeRampStatus.value.isActive) return
        _controlState.value = _controlState.value.copy(horn = enabled)
    }

    fun setDirection(direction: Direction) {
        if (_failsafeRampStatus.value.isActive) return
        _controlState.value = _controlState.value.copy(direction = direction)
    }

    fun setActiveCab(cab: ActiveCab) {
        if (_failsafeRampStatus.value.isActive) return
        _activeCab.value = cab
    }

    fun emergencyStop() {
        if (_failsafeRampStatus.value.isActive) return
        _controlState.value = _controlState.value.copy(targetSpeed = 0.0, emergencyStop = true)
    }

    fun clear() {
        realtimeJob.cancel()
        telemetryJob.cancel()
        failsafeJob.cancel()
        videoJob.cancel()
        scope.launch {
            repository.stopRealtime()
            repository.stopVideoStream()
            repository.releaseVideoStream()
        }
        _activeCab.value = ActiveCab.NONE
    }

    fun startVideoStream(url: String) {
        _videoStreamState.value = VideoStreamUiState(VideoStreamState.Buffering, true, null)
        repository.startVideoStream(url)
    }

    fun stopVideoStream() {
        repository.stopVideoStream()
        _videoStreamState.value = VideoStreamUiState(VideoStreamState.Idle, false, null)
    }

    fun videoPlayer(): Player? = repository.videoPlayer
}

data class VideoStreamUiState(
    val state: VideoStreamState,
    val isBuffering: Boolean,
    val errorMessage: String?
)
