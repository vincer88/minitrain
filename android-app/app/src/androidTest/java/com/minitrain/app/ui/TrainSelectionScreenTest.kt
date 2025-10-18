package com.minitrain.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minitrain.app.repository.TrainDirectoryRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainSelectionScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var repository: TrainDirectoryRepository
    private lateinit var viewModel: TrainSelectionViewModel

    @BeforeTest
    fun setUp() {
        repository = TrainDirectoryRepository()
        viewModel = TrainSelectionViewModel(repository)
        composeTestRule.setContent {
            MaterialTheme {
                TrainSelectionRoute(
                    viewModel = viewModel,
                    onTrainSelected = {},
                    onTrainUnavailable = {}
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        viewModel.clear()
    }

    @Test
    fun addTrainButtonAddsNewEntry() {
        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.isNotEmpty() }
        composeTestRule.onAllNodesWithTag("train-card").assertCountEquals(1)
    }

    @Test
    fun removeTrainDeletesEntryFromList() {
        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.size == 2 }
        val firstId = viewModel.uiState.value.trains.first().endpoint.id

        composeTestRule.onNodeWithTag("remove-$firstId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.size == 1 }
        composeTestRule.onAllNodesWithTag("train-card").assertCountEquals(1)
    }

    @Test
    fun trainBecomingUnavailableUpdatesIndicators() {
        var launchedTrainId: String? = null
        var unavailableTrainId: String? = null
        var availableTrainId: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                TrainSelectionRoute(
                    viewModel = viewModel,
                    onTrainSelected = { launchedTrainId = it.id },
                    onTrainUnavailable = { unavailableTrainId = it.id },
                    onTrainAvailable = { availableTrainId = it.id }
                )
            }
        }

        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.isNotEmpty() }
        val trainId = viewModel.uiState.value.trains.first().endpoint.id

        composeTestRule.onNodeWithTag("select-$trainId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { launchedTrainId == trainId }
        composeTestRule.onNodeWithTag("status-$trainId").assertTextEquals("En cours")

        runBlocking {
            withContext(Dispatchers.Main) {
                repository.setAvailability(trainId, false)
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.trains.first().status.isLost
        }

        composeTestRule.onNodeWithTag("status-$trainId").assertTextEquals("Perdu")
        composeTestRule.onNodeWithTag("select-$trainId").assertIsNotEnabled()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { unavailableTrainId == trainId }

        runBlocking {
            withContext(Dispatchers.Main) {
                repository.setAvailability(trainId, true)
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.trains.first().status.isAvailable
        }

        composeTestRule.onNodeWithTag("status-$trainId").assertTextEquals("Disponible")
        composeTestRule.waitUntil(timeoutMillis = 5_000) { availableTrainId == trainId }
    }

    @Test
    fun controlOverlayDisplayedWhenTrainSelected() {
        composeTestRule.setContent {
            MaterialTheme {
                TrainSelectionRoute(
                    viewModel = viewModel,
                    onTrainSelected = {},
                    onTrainUnavailable = {},
                    controlOverlay = { _, _ ->
                        Box(modifier = Modifier.testTag("overlay"))
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.isNotEmpty() }
        val trainId = viewModel.uiState.value.trains.first().endpoint.id

        composeTestRule.onNodeWithTag("overlay").assertDoesNotExist()

        composeTestRule.onNodeWithTag("select-$trainId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.selectedTrain.value?.id == trainId }

        composeTestRule.onNodeWithTag("overlay").assertExists()
    }
}
