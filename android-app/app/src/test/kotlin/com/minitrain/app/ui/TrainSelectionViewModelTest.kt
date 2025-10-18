package com.minitrain.app.ui

import com.minitrain.app.model.TrainConnectionPhase
import com.minitrain.app.model.TrainConnectionStatus
import com.minitrain.app.model.TrainDirectory
import com.minitrain.app.model.TrainEndpoint
import com.minitrain.app.repository.TrainDirectoryRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle

@OptIn(ExperimentalCoroutinesApi::class)
class TrainSelectionViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(testDispatcher + Job())
    private val repository = TrainDirectoryRepository(TrainDirectory())
    private val viewModel = TrainSelectionViewModel(repository, scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun addTrainAddsEntryToState() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        viewModel.addTrain(endpoint)
        scope.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.trains.size)
        assertEquals(endpoint, state.trains.first().endpoint)
    }

    @Test
    fun removeTrainDeletesEntry() {
        val endpoint1 = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        val endpoint2 = TrainEndpoint("id-2", "Train 2", "wss://train-2")
        repository.addTrain(endpoint1)
        repository.addTrain(endpoint2)

        viewModel.removeTrain("id-1")
        scope.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(endpoint2), state.trains.map { it.endpoint })
    }

    @Test
    fun selectTrainEmitsNavigationEventWhenAvailable() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        repository.addTrain(endpoint)

        val events = mutableListOf<TrainSelectionEvent>()
        val job = scope.launch { viewModel.events.collect { events.add(it) } }

        viewModel.selectTrain("id-1")
        scope.advanceUntilIdle()

        assertTrue(events.any { it is TrainSelectionEvent.ControlScreenRequested && it.endpoint == endpoint })
        job.cancel()
    }

    @Test
    fun selectTrainDoesNothingWhenUnavailable() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        repository.addTrain(endpoint)
        repository.setAvailability("id-1", false)

        val events = mutableListOf<TrainSelectionEvent>()
        val job = scope.launch { viewModel.events.collect { events.add(it) } }

        viewModel.selectTrain("id-1")
        scope.advanceUntilIdle()

        assertTrue(events.isEmpty())
        job.cancel()
    }

    @Test
    fun unavailableTrainEmitsLostControlEvent() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        repository.addTrain(endpoint)
        viewModel.selectTrain("id-1")
        scope.advanceUntilIdle()

        val events = mutableListOf<TrainSelectionEvent>()
        val job = scope.launch { viewModel.events.collect { events.add(it) } }

        repository.setAvailability("id-1", false)
        scope.advanceUntilIdle()

        assertTrue(events.any { it is TrainSelectionEvent.TrainLost && it.endpoint == endpoint })
        assertNull(viewModel.selectedTrain.value)
        job.cancel()
    }

    @Test
    fun stateReflectsExternalDirectoryChanges() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        repository.addTrain(endpoint)
        repository.updateConnection("id-1", true)
        repository.setAvailability("id-1", false)

        scope.advanceUntilIdle()

        val state = viewModel.uiState.value
        val item = state.trains.first()
        assertEquals(TrainConnectionStatus(TrainConnectionPhase.LOST), item.status)
    }

    @Test
    fun trainRecoveringEmitsAvailableEvent() {
        val endpoint = TrainEndpoint("id-1", "Train 1", "wss://train-1")
        repository.addTrain(endpoint)
        repository.setAvailability("id-1", false)

        scope.advanceUntilIdle()

        val events = mutableListOf<TrainSelectionEvent>()
        val job = scope.launch { viewModel.events.collect { events.add(it) } }

        repository.setAvailability("id-1", true)
        scope.advanceUntilIdle()

        assertTrue(events.any { it is TrainSelectionEvent.TrainAvailable && it.endpoint == endpoint })
        job.cancel()
    }
}
