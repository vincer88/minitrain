package com.minitrain.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import com.minitrain.app.R
import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.Direction
import com.minitrain.app.network.VideoStreamState
import com.minitrain.app.ui.video.TrainVideoStreamView

@Composable
fun TrainControlScreen(
    viewModel: TrainViewModel,
    trainName: String,
    videoEndpoint: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val controlState by viewModel.controlState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val failsafeStatus by viewModel.failsafeRampStatus.collectAsState()
    val videoState by viewModel.videoStreamState.collectAsState()
    val activeCab by viewModel.activeCab.collectAsState()

    val videoUrlState = rememberUpdatedState(videoEndpoint)
    val hasRequestedStream = remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onBack)

    LaunchedEffect(videoEndpoint) {
        hasRequestedStream.value = false
        if (videoEndpoint == null) {
            viewModel.stopVideoStream()
        }
    }

    LaunchedEffect(videoState.state, videoState.errorMessage) {
        if (videoState.state is VideoStreamState.Error || videoState.state is VideoStreamState.Idle) {
            hasRequestedStream.value = false
        }
        if (videoState.errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = videoState.errorMessage,
                duration = SnackbarDuration.Short
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopVideoStream() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(trainName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            ) {
                AndroidView(
                    factory = { context ->
                        TrainVideoStreamView(context).apply {
                            setPlayer(viewModel.videoPlayer())
                            setOnSurfaceReadyListener {
                                val url = videoUrlState.value
                                if (!hasRequestedStream.value && url != null) {
                                    hasRequestedStream.value = true
                                    viewModel.startVideoStream(url)
                                }
                            }
                        }
                    },
                    update = { view ->
                        view.setPlayer(viewModel.videoPlayer())
                        view.render(videoState)
                    },
                    modifier = Modifier.matchParentSize()
                )

                CabinOverlay(modifier = Modifier.matchParentSize())

                VideoStateOverlay(
                    videoConfigured = videoEndpoint != null,
                    videoState = videoState,
                    onRetry = {
                        val url = videoUrlState.value
                        if (url != null) {
                            hasRequestedStream.value = true
                            viewModel.startVideoStream(url)
                        }
                    }
                )
            }

            telemetry?.let {
                TelemetryCard(it)
            }

            ControlCard(
                controlState = controlState,
                activeCab = activeCab,
                failsafeActive = failsafeStatus.isActive,
                onSpeedChanged = { viewModel.setTargetSpeed(it) },
                onDirectionSelected = { viewModel.setDirection(it) },
                onCabSelected = { viewModel.setActiveCab(it) },
                onHeadlightsToggled = { viewModel.toggleHeadlights(it) },
                onHornToggled = { viewModel.toggleHorn(it) },
                onEmergencyStop = { viewModel.emergencyStop() }
            )
        }
    }
}

@Composable
private fun VideoStateOverlay(
    videoConfigured: Boolean,
    videoState: VideoStreamUiState,
    onRetry: () -> Unit
) {
    if (!videoConfigured) {
        OverlayMessage(text = "Flux vidéo indisponible")
        return
    }

    when (videoState.state) {
        is VideoStreamState.Error -> {
            OverlayMessage(
                text = videoState.errorMessage ?: "Erreur de lecture",
                actionLabel = "Réessayer",
                onAction = onRetry
            )
        }
        VideoStreamState.Idle -> {
            OverlayMessage(
                text = "Flux arrêté",
                actionLabel = "Démarrer",
                onAction = onRetry
            )
        }
        VideoStreamState.Buffering -> {
            OverlayMessage(text = "Chargement du flux…")
        }
        else -> Unit
    }
}

@Composable
private fun CabinOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    )
                )
            )
            .alpha(0.4f)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.cabine_default),
                contentDescription = "Illustration de la cabine du train",
                modifier = Modifier.fillMaxWidth(0.3f)
            )
            Text(
                text = "Cabine", // fallback label when no image is available
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Surcouche illustrative",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OverlayMessage(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (text.isEmpty()) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (actionLabel != null && onAction != null) {
                    OutlinedButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryCard(telemetry: com.minitrain.app.model.Telemetry) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Vitesse actuelle : ${"%.2f".format(telemetry.speedMetersPerSecond)} m/s",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Direction appliquée : ${telemetry.appliedDirection}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Température : ${"%.1f".format(telemetry.temperatureCelsius)} °C",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlCard(
    controlState: com.minitrain.app.model.ControlState,
    activeCab: ActiveCab,
    failsafeActive: Boolean,
    onSpeedChanged: (Double) -> Unit,
    onDirectionSelected: (Direction) -> Unit,
    onCabSelected: (ActiveCab) -> Unit,
    onHeadlightsToggled: (Boolean) -> Unit,
    onHornToggled: (Boolean) -> Unit,
    onEmergencyStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Vitesse cible", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = controlState.targetSpeed.toFloat(),
                    onValueChange = { onSpeedChanged(it.toDouble()) },
                    valueRange = 0f..5f,
                    enabled = !failsafeActive
                )
                Text(
                    text = "${"%.2f".format(controlState.targetSpeed)} m/s",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Direction", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DirectionChip(
                        direction = Direction.REVERSE,
                        selected = controlState.direction == Direction.REVERSE,
                        enabled = !failsafeActive,
                        onSelect = onDirectionSelected
                    )
                    DirectionChip(
                        direction = Direction.NEUTRAL,
                        selected = controlState.direction == Direction.NEUTRAL,
                        enabled = !failsafeActive,
                        onSelect = onDirectionSelected
                    )
                    DirectionChip(
                        direction = Direction.FORWARD,
                        selected = controlState.direction == Direction.FORWARD,
                        enabled = !failsafeActive,
                        onSelect = onDirectionSelected
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cabine", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CabChip(ActiveCab.FRONT, activeCab == ActiveCab.FRONT, !failsafeActive, onCabSelected)
                    CabChip(ActiveCab.REAR, activeCab == ActiveCab.REAR, !failsafeActive, onCabSelected)
                    CabChip(ActiveCab.NONE, activeCab == ActiveCab.NONE, !failsafeActive, onCabSelected)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ToggleChip(
                    label = "Phares",
                    selected = controlState.headlights,
                    enabled = !failsafeActive,
                    onToggle = onHeadlightsToggled
                )
                ToggleChip(
                    label = "Klaxon",
                    selected = controlState.horn,
                    enabled = !failsafeActive,
                    onToggle = onHornToggled
                )
            }

            Button(onClick = onEmergencyStop, enabled = !failsafeActive) {
                Text("Arrêt d'urgence")
            }
        }
    }
}

@Composable
private fun DirectionChip(
    direction: Direction,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Direction) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(direction) },
        label = { Text(direction.name.lowercase().replaceFirstChar { it.titlecase() }) },
        enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun CabChip(
    cab: ActiveCab,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (ActiveCab) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onSelect(cab) },
        label = { Text(cab.name.lowercase().replaceFirstChar { it.titlecase() }) },
        enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
    )
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label) },
        enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
        )
    )
}
