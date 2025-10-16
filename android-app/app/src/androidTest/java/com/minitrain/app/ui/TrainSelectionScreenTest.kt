package com.minitrain.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
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
        composeTestRule.setContent {
            MaterialTheme {
                TrainSelectionRoute(
                    viewModel = viewModel,
                    onTrainSelected = { launchedTrainId = it.id },
                    onTrainUnavailable = { unavailableTrainId = it.id }
                )
            }
        }

        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.trains.isNotEmpty() }
        val trainId = viewModel.uiState.value.trains.first().endpoint.id

        composeTestRule.onNodeWithTag("select-$trainId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { launchedTrainId == trainId }
        composeTestRule.onNodeWithTag("connection-$trainId").assertTextEquals("Connecté")
        composeTestRule.onNodeWithTag("availability-$trainId").assertTextEquals("Disponible")

        runBlocking {
            withContext(Dispatchers.Main) {
                repository.setAvailability(trainId, false)
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.trains.first().status.isAvailable.not()
        }

        composeTestRule.onNodeWithTag("availability-$trainId").assertTextEquals("Indisponible")
        composeTestRule.onNodeWithTag("connection-$trainId").assertTextEquals("Déconnecté")
        composeTestRule.onNodeWithTag("select-$trainId").assertIsNotEnabled()
        composeTestRule.waitUntil(timeoutMillis = 5_000) { unavailableTrainId == trainId }
    }
}
