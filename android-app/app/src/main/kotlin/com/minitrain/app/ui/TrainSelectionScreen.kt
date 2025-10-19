package com.minitrain.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minitrain.app.model.TrainConnectionPhase
import com.minitrain.app.model.TrainEndpoint

@Composable
fun TrainSelectionRoute(
    viewModel: TrainSelectionViewModel,
    onTrainSelected: (TrainEndpoint) -> Unit,
    onTrainUnavailable: (TrainEndpoint) -> Unit = {},
    onTrainAvailable: (TrainEndpoint) -> Unit = {},
    onShowDetails: (TrainEndpoint) -> Unit = {},
    onDismissControl: (TrainEndpoint) -> Unit = {},
    controlOverlay: @Composable (TrainEndpoint, () -> Unit) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val selectedTrain by viewModel.selectedTrain.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainSelectionEvent.ControlScreenRequested -> onTrainSelected(event.endpoint)
                is TrainSelectionEvent.TrainLost -> onTrainUnavailable(event.endpoint)
                is TrainSelectionEvent.TrainAvailable -> onTrainAvailable(event.endpoint)
                is TrainSelectionEvent.DetailsRequested -> onShowDetails(event.endpoint)
            }
        }
    }

    TrainSelectionScreen(
        state = state,
        onAddTrain = viewModel::addTrain,
        onSelectTrain = viewModel::selectTrain,
        onShowDetails = viewModel::showDetails,
        modifier = modifier
    )

    selectedTrain?.let { endpoint ->
        controlOverlay(endpoint) { onDismissControl(endpoint) }
    }
}

@Composable
fun TrainSelectionScreen(
    state: TrainSelectionUiState,
    onAddTrain: () -> Unit,
    onSelectTrain: (String) -> Unit,
    onShowDetails: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "Mes trains",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onAddTrain, modifier = Modifier.testTag("add-train")) {
            Text("Ajouter un train")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (state.trains.isEmpty()) {
            Text(
                text = "Aucun train n'est configuré.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.trains, key = { it.endpoint.id }) { train ->
                    TrainRow(
                        item = train,
                        onSelectTrain = onSelectTrain,
                        onShowDetails = onShowDetails
                    )
                }
            }
        }
    }
}

@Composable
private fun TrainRow(
    item: TrainItemUiState,
    onSelectTrain: (String) -> Unit,
    onShowDetails: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("train-row"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.endpoint.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = statusLabel(item.status.phase),
                modifier = Modifier.testTag("status-${item.endpoint.id}"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = when (item.status.phase) {
                    TrainConnectionPhase.AVAILABLE -> FontWeight.Medium
                    TrainConnectionPhase.IN_PROGRESS -> FontWeight.SemiBold
                    TrainConnectionPhase.LOST -> FontWeight.Bold
                }
            )
        }
        TextButton(
            onClick = { onShowDetails(item.endpoint.id) },
            modifier = Modifier.testTag("details-${item.endpoint.id}")
        ) {
            Text("Détails")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(
            onClick = { onSelectTrain(item.endpoint.id) },
            enabled = item.status.isAvailable,
            modifier = Modifier.testTag("activate-${item.endpoint.id}")
        ) {
            Text("Activer")
        }
    }
}

private fun statusLabel(phase: TrainConnectionPhase): String = when (phase) {
    TrainConnectionPhase.AVAILABLE -> "Disponible"
    TrainConnectionPhase.IN_PROGRESS -> "En cours"
    TrainConnectionPhase.LOST -> "Perdu"
}
