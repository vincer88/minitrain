package com.minitrain.app.ui

import com.minitrain.app.model.TrainConnectionPhase
import com.minitrain.app.model.TrainConnectionStatus
import com.minitrain.app.model.TrainEndpoint
import com.minitrain.app.repository.TrainDirectoryRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TrainSelectionViewModel(
    private val directoryRepository: TrainDirectoryRepository,
    coroutineScope: CoroutineScope? = null
) {
    private val ownsScope: Boolean
    private val scope: CoroutineScope
    private val internalJob: Job?
    private val counter = AtomicInteger(directoryRepository.directory.value.trains.size)
    private val statusCache = mutableMapOf<String, TrainConnectionPhase>()

    private val _uiState = MutableStateFlow(TrainSelectionUiState())
    val uiState: StateFlow<TrainSelectionUiState> = _uiState.asStateFlow()

    private val _selectedTrain = MutableStateFlow<TrainEndpoint?>(null)
    val selectedTrain: StateFlow<TrainEndpoint?> = _selectedTrain.asStateFlow()

    private val _events = MutableSharedFlow<TrainSelectionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TrainSelectionEvent> = _events

    init {
        val providedScope = coroutineScope
        if (providedScope == null) {
            val job = Job()
            scope = CoroutineScope(Dispatchers.Main.immediate + job)
            internalJob = job
            ownsScope = true
        } else {
            scope = providedScope
            internalJob = null
            ownsScope = false
        }

        scope.launch {
            directoryRepository.directory.collect { directory ->
                val trains = directory.trains.map { TrainItemUiState(it.endpoint, it.status) }
                val connectedEntry = directory.trains.firstOrNull { it.status.isConnected }
                val selectedEndpoint = connectedEntry?.endpoint
                _selectedTrain.value = selectedEndpoint
                val currentIds = directory.trains.map { it.endpoint.id }.toSet()
                val iterator = statusCache.keys.iterator()
                while (iterator.hasNext()) {
                    val id = iterator.next()
                    if (id !in currentIds) {
                        iterator.remove()
                    }
                }
                directory.trains.forEach { entry ->
                    val previous = statusCache[entry.endpoint.id]
                    val current = entry.status.phase
                    statusCache[entry.endpoint.id] = current
                    if (previous != null && previous != current) {
                        when {
                            previous == TrainConnectionPhase.IN_PROGRESS && current == TrainConnectionPhase.LOST -> {
                                scope.launch {
                                    _events.emit(TrainSelectionEvent.TrainLost(entry.endpoint))
                                }
                            }

                            previous == TrainConnectionPhase.LOST && current == TrainConnectionPhase.AVAILABLE -> {
                                scope.launch {
                                    _events.emit(TrainSelectionEvent.TrainAvailable(entry.endpoint))
                                }
                            }
                        }
                    }
                }
                _uiState.value = TrainSelectionUiState(
                    trains = trains,
                    selectedTrainId = selectedEndpoint?.id
                )
            }
        }
    }

    fun addTrain(endpoint: TrainEndpoint? = null) {
        val newEndpoint = endpoint ?: generateEndpoint()
        directoryRepository.addTrain(newEndpoint)
    }

    fun removeTrain(id: String) {
        directoryRepository.removeTrain(id)
    }

    fun selectTrain(id: String) {
        val entry = directoryRepository.directory.value.trains.firstOrNull { it.endpoint.id == id }
            ?: return
        if (!entry.status.isAvailable) {
            return
        }
        directoryRepository.markConnected(id)
        scope.launch {
            _events.emit(TrainSelectionEvent.ControlScreenRequested(entry.endpoint))
        }
    }

    fun showDetails(id: String) {
        val entry = directoryRepository.directory.value.trains.firstOrNull { it.endpoint.id == id }
            ?: return
        scope.launch {
            _events.emit(TrainSelectionEvent.DetailsRequested(entry.endpoint))
        }
    }

    fun setAvailability(id: String, isAvailable: Boolean) {
        directoryRepository.setAvailability(id, isAvailable)
    }

    fun updateConnection(id: String, isConnected: Boolean) {
        directoryRepository.updateConnection(id, isConnected)
    }

    fun clear() {
        if (ownsScope) {
            internalJob?.cancel()
        }
    }

    private fun generateEndpoint(): TrainEndpoint {
        val next = counter.incrementAndGet()
        val id = "train-$next-${UUID.randomUUID()}"
        return TrainEndpoint(
            id = id,
            name = "Train $next",
            commandEndpoint = "wss://train-$next",
            videoEndpoint = null
        )
    }
}

data class TrainSelectionUiState(
    val trains: List<TrainItemUiState> = emptyList(),
    val selectedTrainId: String? = null
)

data class TrainItemUiState(
    val endpoint: TrainEndpoint,
    val status: TrainConnectionStatus
)

sealed interface TrainSelectionEvent {
    data class ControlScreenRequested(val endpoint: TrainEndpoint) : TrainSelectionEvent
    data class TrainLost(val endpoint: TrainEndpoint) : TrainSelectionEvent
    data class TrainAvailable(val endpoint: TrainEndpoint) : TrainSelectionEvent
    data class DetailsRequested(val endpoint: TrainEndpoint) : TrainSelectionEvent
}
