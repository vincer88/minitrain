package com.minitrain.app

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minitrain.app.ui.TrainSelectionViewModel
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun deletingActiveTrainReleasesSession() {
        val activity = composeTestRule.activity
        val viewModelField = MainActivity::class.java.getDeclaredField("selectionViewModel")
        viewModelField.isAccessible = true
        val viewModel = viewModelField.get(activity) as TrainSelectionViewModel

        composeTestRule.onNodeWithTag("add-train").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.trains.isNotEmpty()
        }
        val trainId = viewModel.uiState.value.trains.first().endpoint.id

        composeTestRule.onNodeWithTag("activate-$trainId").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.selectedTrain.value?.id == trainId
        }
        composeTestRule.onNodeWithTag("control-overlay").assertExists()

        composeTestRule.runOnIdle {
            viewModel.requestDelete(trainId)
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.trains.none { it.endpoint.id == trainId } &&
                viewModel.selectedTrain.value == null
        }

        composeTestRule.onNodeWithTag("control-overlay").assertDoesNotExist()
    }
}
