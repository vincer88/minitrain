package com.minitrain.app

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandChannelClient
import com.minitrain.app.network.CommandWebSocketFactory
import com.minitrain.app.network.CommandWebSocketSession
import com.minitrain.app.network.buildRealtimeHttpClient
import com.minitrain.app.repository.TrainRepository
import com.minitrain.app.ui.TrainViewModel
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.time.Clock
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Suppress("DEPRECATION")
private class FakeRepository(scope: kotlinx.coroutines.CoroutineScope) : TrainRepository(
    CommandChannelClient(
        endpoint = "wss://example/ws",
        sessionId = UUID(0, 0),
        client = buildRealtimeHttpClient(),
        scope = scope,
        clock = Clock.systemUTC()
    ) { _, _ -> CommandWebSocketFactory { object : CommandWebSocketSession {
        override val isOpen: Boolean = true
        override suspend fun send(frame: ByteArray): Boolean = true
        override suspend fun close(reason: CloseReason) {}
    } } },
    legacyTransport = null
) {
    val commands = mutableListOf<TrainCommand>()
    val states = mutableListOf<ControlState>()
    private val telemetryResponses = ArrayDeque<Telemetry>()

    fun enqueueTelemetry(vararg telemetry: Telemetry) {
        telemetryResponses.addAll(telemetry)
    }

    @Suppress("DEPRECATION")
    override suspend fun sendCommand(command: TrainCommand) {
        commands.add(command)
    }

    @Suppress("DEPRECATION")
    override suspend fun pushState(state: ControlState) {
        states.add(state)
    }

    @Suppress("DEPRECATION")
    override suspend fun fetchTelemetry(): Telemetry {
        if (telemetryResponses.isEmpty()) error("No telemetry enqueued")
        return telemetryResponses.removeFirst()
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
        assertEquals(1, repository.states.size)
    }

    @Test
    fun `polling telemetry emits updates`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)
        val telemetry = Telemetry(1.0, 0.5, 11.1, 30.0, true, false, Direction.FORWARD, false)
        repository.enqueueTelemetry(telemetry, telemetry.copy(speedMetersPerSecond = 1.5))

        val job = launch { viewModel.startTelemetryPolling(10.milliseconds) }
        val first = viewModel.telemetry.first { it != null }
        assertEquals(telemetry, first)
        delay(15)
        viewModel.stopTelemetryPolling()
        job.cancel()
    }

    @Test
    fun `direction command sent`() = runBlocking {
        val repository = FakeRepository(this)
        val viewModel = TrainViewModel(repository, this)

        viewModel.setDirection(Direction.REVERSE)
        kotlinx.coroutines.yield()
        assertEquals(Direction.REVERSE, viewModel.controlState.first().direction)
        assertTrue(repository.commands.any { it.command == "set_direction" && it.value == "reverse" })
    }
}
