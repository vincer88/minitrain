package com.minitrain.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.minitrain.app.model.TrainEndpoint

@Composable
fun TrainSelectionRoute(
    viewModel: TrainSelectionViewModel,
    onTrainSelected: (TrainEndpoint) -> Unit,
    onTrainUnavailable: (TrainEndpoint) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainSelectionEvent.ControlScreenRequested -> onTrainSelected(event.endpoint)
                is TrainSelectionEvent.TrainBecameUnavailable -> onTrainUnavailable(event.endpoint)
            }
        }
    }

    TrainSelectionScreen(
        state = state,
        onAddTrain = viewModel::addTrain,
        onRemoveTrain = viewModel::removeTrain,
        onSelectTrain = viewModel::selectTrain,
        modifier = modifier
    )
}

@Composable
fun TrainSelectionScreen(
    state: TrainSelectionUiState,
    onAddTrain: () -> Unit,
    onRemoveTrain: (String) -> Unit,
    onSelectTrain: (String) -> Unit,
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.trains, key = { it.endpoint.id }) { train ->
                    TrainCard(
                        item = train,
                        isSelected = state.selectedTrainId == train.endpoint.id,
                        onRemoveTrain = onRemoveTrain,
                        onSelectTrain = onSelectTrain
                    )
                }
            }
        }
    }
}

@Composable
private fun TrainCard(
    item: TrainItemUiState,
    isSelected: Boolean,
    onRemoveTrain: (String) -> Unit,
    onSelectTrain: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("train-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.endpoint.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.endpoint.commandEndpoint,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = if (item.status.isAvailable) "Disponible" else "Indisponible",
                    modifier = Modifier.testTag("availability-${item.endpoint.id}"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.status.isAvailable) FontWeight.Medium else FontWeight.Bold
                )
                Text(
                    text = if (item.status.isConnected) "Connecté" else "Déconnecté",
                    modifier = Modifier.testTag("connection-${item.endpoint.id}"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (item.status.isConnected) FontWeight.Medium else FontWeight.Normal
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onRemoveTrain(item.endpoint.id) },
                    modifier = Modifier.testTag("remove-${item.endpoint.id}")
                ) {
                    Text("Supprimer")
                }
                Button(
                    onClick = { onSelectTrain(item.endpoint.id) },
                    enabled = item.status.isAvailable,
                    modifier = Modifier.testTag("select-${item.endpoint.id}")
                ) {
                    Text(if (isSelected) "En cours" else "Contrôler")
                }
            }
        }
    }
}
