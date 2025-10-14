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
import com.minitrain.app.network.buildRealtimeHttpClient
import com.minitrain.app.repository.TrainRepository
import com.minitrain.app.ui.TrainViewModel
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
private class FakeRepository(scope: kotlinx.coroutines.CoroutineScope) : TrainRepository(
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
    legacyTransport = null
) {
    private val _telemetry = kotlinx.coroutines.flow.MutableSharedFlow<Telemetry>(replay = 1)

    override val telemetry: kotlinx.coroutines.flow.Flow<Telemetry>
        get() = _telemetry

    suspend fun emitTelemetry(telemetry: Telemetry) {
        _telemetry.emit(telemetry)
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
}
