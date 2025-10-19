package com.minitrain.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.minitrain.app.model.TrainEndpoint
import com.minitrain.app.network.ExoPlayerVideoStreamClient
import com.minitrain.app.repository.TrainDirectoryRepository
import com.minitrain.app.repository.TrainRepository
import com.minitrain.app.ui.TrainControlScreen
import com.minitrain.app.ui.TrainSelectionRoute
import com.minitrain.app.ui.TrainSelectionViewModel
import com.minitrain.app.ui.TrainViewModel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val directoryRepository = TrainDirectoryRepository()
    private val selectionViewModel = TrainSelectionViewModel(directoryRepository)
    private var currentSession: TrainSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val context = this
                    val activeSession: MutableState<TrainSession?> = remember { mutableStateOf(null) }
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val coroutineScope = rememberCoroutineScope()

                    fun releaseSession(updateRepository: Boolean) {
                        val session = activeSession.value ?: return
                        if (updateRepository) {
                            selectionViewModel.updateConnection(session.endpoint.id, false)
                        }
                        session.release()
                        if (currentSession?.endpoint?.id == session.endpoint.id) {
                            currentSession = null
                        }
                        activeSession.value = null
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            releaseSession(updateRepository = true)
                        }
                    }

                    TrainSelectionRoute(
                        viewModel = selectionViewModel,
                        onTrainSelected = { endpoint ->
                            coroutineScope.launch {
                                if (sheetState.isVisible) {
                                    sheetState.hide()
                                }
                                releaseSession(updateRepository = false)
                                val session = createTrainSession(endpoint)
                                currentSession = session
                                activeSession.value = session
                            }
                        },
                        onTrainUnavailable = { endpoint ->
                            val session = activeSession.value
                            if (session?.endpoint?.id == endpoint.id) {
                                coroutineScope.launch {
                                    if (sheetState.isVisible) {
                                        sheetState.hide()
                                    }
                                    releaseSession(updateRepository = false)
                                    Toast.makeText(
                                        context,
                                        "Le train ${endpoint.name} est indisponible",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onTrainAvailable = { endpoint ->
                            Toast.makeText(
                                context,
                                "Le train ${endpoint.name} redevient disponible",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onShowDetails = { endpoint ->
                            Toast.makeText(
                                context,
                                "Détails du train ${endpoint.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDeleteTrain = { endpoint ->
                            coroutineScope.launch {
                                val session = activeSession.value
                                if (session?.endpoint?.id == endpoint.id) {
                                    if (sheetState.isVisible) {
                                        sheetState.hide()
                                    }
                                    releaseSession(updateRepository = true)
                                }
                                selectionViewModel.removeTrain(endpoint.id)
                            }
                        },
                        onDismissControl = { endpoint ->
                            val session = activeSession.value
                            if (session?.endpoint?.id == endpoint.id) {
                                coroutineScope.launch {
                                    if (sheetState.isVisible) {
                                        sheetState.hide()
                                    }
                                    releaseSession(updateRepository = true)
                                }
                            }
                        },
                        controlOverlay = { endpoint, onDismiss ->
                            val session = activeSession.value
                            if (session != null && session.endpoint.id == endpoint.id) {
                                LaunchedEffect(session) {
                                    sheetState.show()
                                }
                                ModalBottomSheet(
                                    modifier = Modifier.testTag("control-overlay"),
                                    onDismissRequest = {
                                        coroutineScope.launch {
                                            sheetState.hide()
                                            onDismiss()
                                        }
                                    },
                                    sheetState = sheetState
                                ) {
                                    TrainControlScreen(
                                        viewModel = session.viewModel,
                                        trainName = session.endpoint.name,
                                        videoEndpoint = session.endpoint.videoEndpoint,
                                        onBack = {
                                            coroutineScope.launch {
                                                sheetState.hide()
                                                onDismiss()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.let {
            selectionViewModel.updateConnection(it.endpoint.id, false)
            it.release()
        }
        currentSession = null
        selectionViewModel.clear()
    }

    private fun createTrainSession(endpoint: TrainEndpoint): TrainSession {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val repository = TrainRepository.create(
            endpoint = endpoint.commandEndpoint,
            sessionId = UUID.randomUUID(),
            scope = scope,
            videoStreamClient = ExoPlayerVideoStreamClient(applicationContext)
        )
        val viewModel = TrainViewModel(repository, scope)
        return TrainSession(endpoint, scope, viewModel)
    }

    private data class TrainSession(
        val endpoint: TrainEndpoint,
        val scope: CoroutineScope,
        val viewModel: TrainViewModel
    ) {
        fun release() {
            viewModel.clear()
            scope.cancel()
        }
    }
}
