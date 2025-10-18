package com.minitrain.app.repository

import com.minitrain.app.model.TrainConnectionPhase
import com.minitrain.app.model.TrainConnectionStatus
import com.minitrain.app.model.TrainDirectory
import com.minitrain.app.model.TrainDirectoryEntry
import com.minitrain.app.model.TrainEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TrainDirectoryRepository(
    initialDirectory: TrainDirectory = TrainDirectory()
) {
    private val _directory = MutableStateFlow(initialDirectory)
    val directory: StateFlow<TrainDirectory> = _directory.asStateFlow()

    fun addTrain(endpoint: TrainEndpoint) {
        _directory.update { directory ->
            if (directory.trains.any { it.endpoint.id == endpoint.id }) {
                directory
            } else {
                directory.copy(
                    trains = directory.trains + TrainDirectoryEntry(
                        endpoint = endpoint,
                        status = TrainConnectionStatus()
                    )
                )
            }
        }
    }

    fun removeTrain(id: String) {
        _directory.update { directory ->
            directory.copy(trains = directory.trains.filterNot { it.endpoint.id == id })
        }
    }

    fun markConnected(id: String) {
        _directory.update { directory ->
            directory.copy(
                trains = directory.trains.map { entry ->
                    when (entry.endpoint.id) {
                        id -> entry.copy(
                            status = entry.status.copy(phase = TrainConnectionPhase.IN_PROGRESS)
                        )
                        else -> {
                            if (entry.status.phase == TrainConnectionPhase.LOST) {
                                entry
                            } else {
                                entry.copy(
                                    status = entry.status.copy(phase = TrainConnectionPhase.AVAILABLE)
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    fun setAvailability(id: String, isAvailable: Boolean) {
        _directory.update { directory ->
            directory.copy(
                trains = directory.trains.map { entry ->
                    if (entry.endpoint.id == id) {
                        val newPhase = when {
                            isAvailable && entry.status.phase == TrainConnectionPhase.IN_PROGRESS ->
                                TrainConnectionPhase.IN_PROGRESS
                            isAvailable -> TrainConnectionPhase.AVAILABLE
                            else -> TrainConnectionPhase.LOST
                        }
                        entry.copy(status = entry.status.copy(phase = newPhase))
                    } else {
                        entry
                    }
                }
            )
        }
    }

    fun updateConnection(id: String, isConnected: Boolean) {
        _directory.update { directory ->
            directory.copy(
                trains = directory.trains.map { entry ->
                    if (entry.endpoint.id == id) {
                        val newPhase = when {
                            isConnected -> TrainConnectionPhase.IN_PROGRESS
                            entry.status.phase == TrainConnectionPhase.LOST -> TrainConnectionPhase.LOST
                            else -> TrainConnectionPhase.AVAILABLE
                        }
                        entry.copy(status = entry.status.copy(phase = newPhase))
                    } else {
                        entry
                    }
                }
            )
        }
    }
}
