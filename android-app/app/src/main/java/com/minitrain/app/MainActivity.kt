package com.minitrain.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

class MainActivity : ComponentActivity() {

    private val directoryRepository = TrainDirectoryRepository()
    private val selectionViewModel = TrainSelectionViewModel(directoryRepository)
    private var currentSession: TrainSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val context = this
                    val activeSession: MutableState<TrainSession?> = remember { mutableStateOf(null) }

                    DisposableEffect(Unit) {
                        onDispose {
                            activeSession.value?.release()
                            currentSession = null
                        }
                    }

                    NavHost(navController = navController, startDestination = "train-selection") {
                        composable("train-selection") {
                            TrainSelectionRoute(
                                viewModel = selectionViewModel,
                                onTrainSelected = { endpoint ->
                                    activeSession.value?.release()
                                    val session = createTrainSession(endpoint)
                                    currentSession = session
                                    activeSession.value = session
                                    navController.navigate("train-control")
                                },
                                onTrainUnavailable = { endpoint ->
                                    val session = activeSession.value
                                    if (session?.endpoint?.id == endpoint.id) {
                                        session.release()
                                        activeSession.value = null
                                        if (currentSession?.endpoint?.id == endpoint.id) {
                                            currentSession = null
                                        }
                                        selectionViewModel.updateConnection(endpoint.id, false)
                                        Toast.makeText(
                                            context,
                                            "Le train ${endpoint.name} est indisponible",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        navController.popBackStack()
                                    }
                                }
                            )
                        }
                        composable("train-control") {
                            val session = activeSession.value
                            if (session == null) {
                                LaunchedEffect(Unit) {
                                    navController.popBackStack()
                                }
                            } else {
                                TrainControlScreen(
                                    viewModel = session.viewModel,
                                    trainName = session.endpoint.name,
                                    videoEndpoint = session.endpoint.videoEndpoint,
                                    onBack = {
                                        selectionViewModel.updateConnection(session.endpoint.id, false)
                                        session.release()
                                        activeSession.value = null
                                        if (currentSession?.endpoint?.id == session.endpoint.id) {
                                            currentSession = null
                                        }
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentSession?.let { selectionViewModel.updateConnection(it.endpoint.id, false) }
        currentSession?.release()
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
