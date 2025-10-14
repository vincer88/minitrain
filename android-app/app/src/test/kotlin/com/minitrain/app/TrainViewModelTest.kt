package com.minitrain.app

import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.LightsSource
import com.minitrain.app.model.LightsState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TelemetrySource
import com.minitrain.app.network.CommandChannelClient
import com.minitrain.app.network.CommandWebSocketFactory
import com.minitrain.app.network.CommandWebSocketSession
import com.minitrain.app.network.FailsafeRampStatus
import com.minitrain.app.network.buildRealtimeHttpClient
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.minitrain.app.network.VideoStreamClient
import com.minitrain.app.network.VideoStreamState
import com.minitrain.app.repository.TrainRepository
import com.minitrain.app.ui.TrainViewModel
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
private class FakeVideoStreamClient : VideoStreamClient {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<VideoStreamState>(VideoStreamState.Idle)
    override val state: kotlinx.coroutines.flow.StateFlow<VideoStreamState> = _state
    override val player: Player? = null
    val started = mutableListOf<String>()
    var stopCount = 0

    fun emit(state: VideoStreamState) {
        _state.value = state
    }

    override fun start(url: String) {
        started.add(url)
        _state.value = VideoStreamState.Buffering
    }

    override fun stop() {
        stopCount += 1
        _state.value = VideoStreamState.Idle
    }

    override fun release() {}
}

@Suppress("DEPRECATION")
private class FakeRepository(
    scope: kotlinx.coroutines.CoroutineScope,
    val videoClient: FakeVideoStreamClient = FakeVideoStreamClient()
) : TrainRepository(
    CommandChannelClient(
        endpoint = "wss://example/ws",
        sessionId = UUID(0, 0),
        client = buildRealtimeHttpClient(),
        scope = scope,
        clock = Clock.systemUTC()
    ) { _, _ -> CommandWebSocketFactory { object : CommandWebSocketSession {
        override val isOpen: Boolean = false
        override suspend fun send(frame: ByteArray): Boolean = true
        override suspend fun close(reason: CloseReason) {}
        override suspend fun receive(): ByteArray? = null
    } } },
    legacyTransport = null,
    videoStreamClient = videoClient
) {
    private val _telemetry = kotlinx.coroutines.flow.MutableSharedFlow<Telemetry>(replay = 1)
    private val _failsafe = MutableStateFlow(FailsafeRampStatus.inactive())

    override val telemetry: kotlinx.coroutines.flow.Flow<Telemetry>
        get() = _telemetry

    override val failsafeRampStatus: kotlinx.coroutines.flow.StateFlow<FailsafeRampStatus>
        get() = _failsafe

    suspend fun emitTelemetry(telemetry: Telemetry) {
        _telemetry.emit(telemetry)
    }

    fun emitFailsafe(status: FailsafeRampStatus) {
        _failsafe.value = status
    }

    fun emitVideoState(state: VideoStreamState) {
        videoClient.emit(state)
    }
}

class TrainViewModelTest {
    @Test
    fun `setting speed updates state and resets emergency`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)

        viewModel.emergencyStop()
        assertTrue(viewModel.controlState.first().emergencyStop)
        viewModel.setTargetSpeed(2.0)
        kotlinx.coroutines.yield()

        val state = viewModel.controlState.first()
        assertEquals(2.0, state.targetSpeed)
        assertFalse(state.emergencyStop)
        viewModel.clear()
    }

    @Test
    fun `telemetry flow emits updates`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)
        val telemetry = Telemetry(
            sessionId = "00000000-0000-0000-0000-000000000000",
            sequence = 5,
            commandTimestamp = 9876,
            speedMetersPerSecond = 1.0,
            motorCurrentAmps = 0.5,
            batteryVoltage = 11.1,
            temperatureCelsius = 30.0,
            appliedSpeedMetersPerSecond = 0.9,
            appliedDirection = Direction.FORWARD,
            headlights = true,
            horn = false,
            direction = Direction.FORWARD,
            emergencyStop = false,
            activeCab = ActiveCab.FRONT,
            lightsState = LightsState.FRONT_WHITE_REAR_RED,
            lightsSource = LightsSource.AUTOMATIC,
            source = TelemetrySource.INSTANTANEOUS,
            lightsOverrideMask = 0,
            lightsTelemetryOnly = false
        )
        val second = telemetry.copy(speedMetersPerSecond = 1.5)

        val firstDeferred = async { viewModel.telemetry.first { it != null } }
        repository.emitTelemetry(telemetry)
        assertEquals(telemetry, firstDeferred.await())

        val secondDeferred = async { viewModel.telemetry.first { it?.speedMetersPerSecond == 1.5 } }
        repository.emitTelemetry(second)
        val receivedSecond = secondDeferred.await()
        assertNotNull(receivedSecond)
        assertEquals(1.5, receivedSecond.speedMetersPerSecond)
        viewModel.clear()
    }

    @Test
    fun `direction update adjusts control state`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)

        viewModel.setDirection(Direction.REVERSE)
        kotlinx.coroutines.yield()
        assertEquals(Direction.REVERSE, viewModel.controlState.first().direction)
        viewModel.clear()
    }

    @Test
    fun `failsafe ramp inhibits control inputs`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)

        repository.emitFailsafe(FailsafeRampStatus(true, 0.2, Direction.FORWARD))
        yield()

        viewModel.setTargetSpeed(3.0)
        viewModel.setDirection(Direction.REVERSE)
        yield()

        val duringRamp = viewModel.controlState.first()
        assertEquals(0.0, duringRamp.targetSpeed)
        assertEquals(Direction.FORWARD, duringRamp.direction)

        repository.emitFailsafe(FailsafeRampStatus.inactive())
        yield()

        viewModel.setTargetSpeed(3.0)
        yield()

        val afterRamp = viewModel.controlState.first()
        assertEquals(3.0, afterRamp.targetSpeed)
        viewModel.clear()
    }

    @Test
    fun `direction resets to neutral after ramp completion`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)

        viewModel.setDirection(Direction.REVERSE)
        yield()
        assertEquals(Direction.REVERSE, viewModel.controlState.first().direction)

        repository.emitFailsafe(FailsafeRampStatus(true, 0.5, Direction.REVERSE))
        yield()
        repository.emitFailsafe(FailsafeRampStatus.inactive())
        yield()

        val finalState = viewModel.controlState.first()
        assertEquals(Direction.NEUTRAL, finalState.direction)
        viewModel.clear()
    }

    @Test
    fun `video stream state flows through view model`() = runBlocking {
        val videoClient = FakeVideoStreamClient()
        val repository = FakeRepository(this, videoClient)
        val viewModel = TrainViewModel(repository, this)

        viewModel.startVideoStream("https://example.com/stream")
        yield()

        assertEquals(listOf("https://example.com/stream"), videoClient.started)
        val buffering = viewModel.videoStreamState.first()
        assertTrue(buffering.isBuffering)
        assertTrue(buffering.errorMessage == null)

        val mediaItem = MediaItem.Builder().setUri("https://example.com/stream").build()
        repository.emitVideoState(VideoStreamState.Playing(mediaItem))
        yield()

        val playing = viewModel.videoStreamState.first { it.state is VideoStreamState.Playing }
        assertFalse(playing.isBuffering)

        repository.emitVideoState(VideoStreamState.Error("network error"))
        yield()

        val errorState = viewModel.videoStreamState.first { it.errorMessage != null }
        assertEquals("network error", errorState.errorMessage)

        viewModel.stopVideoStream()
        assertEquals(1, videoClient.stopCount)
        viewModel.clear()
    }
}
