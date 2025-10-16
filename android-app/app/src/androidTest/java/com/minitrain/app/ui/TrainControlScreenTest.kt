package com.minitrain.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetProgressAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.minitrain.app.model.ActiveCab
import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.LightsSource
import com.minitrain.app.model.LightsState
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TelemetrySource
import com.minitrain.app.network.FailsafeRampStatus
import com.minitrain.app.network.VideoStreamState
import com.minitrain.app.ui.VideoStreamUiState

@RunWith(AndroidJUnit4::class)
class TrainControlScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val screenshotRule = ScreenshotTestRule()

    private val controlState = MutableStateFlow(
        ControlState(
            targetSpeed = 1.5,
            direction = Direction.FORWARD,
            headlights = true,
            horn = false,
            emergencyStop = false
        )
    )
    private val telemetry = MutableStateFlow(
        Telemetry(
            sessionId = "session",
            sequence = 1,
            commandTimestamp = 0L,
            speedMetersPerSecond = 0.8,
            motorCurrentAmps = 0.4,
            batteryVoltage = 7.2,
            temperatureCelsius = 28.3,
            appliedSpeedMetersPerSecond = 0.7,
            appliedDirection = Direction.FORWARD,
            headlights = true,
            horn = false,
            direction = Direction.FORWARD,
            emergencyStop = false,
            activeCab = ActiveCab.FRONT,
            lightsState = LightsState.FRONT_WHITE_REAR_RED,
            lightsSource = LightsSource.AUTOMATIC,
            source = TelemetrySource.INSTANTANEOUS,
            lightsOverrideMask = 0,
            lightsTelemetryOnly = false
        )
    )
    private val failsafe = MutableStateFlow(FailsafeRampStatus.inactive())
    private val activeCab = MutableStateFlow(ActiveCab.FRONT)
    private val videoState = MutableStateFlow(
        VideoStreamUiState(VideoStreamState.Idle, isBuffering = false, errorMessage = null)
    )

    private val viewModel: TrainViewModel = mockk(relaxed = true)

    @Before
    fun setup() {
        every { viewModel.controlState } returns controlState
        every { viewModel.telemetry } returns telemetry
        every { viewModel.failsafeRampStatus } returns failsafe
        every { viewModel.activeCab } returns activeCab
        every { viewModel.videoStreamState } returns videoState
        every { viewModel.videoPlayer() } returns null
        justRun { viewModel.startVideoStream(any()) }
        justRun { viewModel.stopVideoStream() }
    }

    @Test
    fun speedSliderChangesCallSetTargetSpeed() {
        composeRule.setContent {
            TrainControlScreen(
                viewModel = viewModel,
                trainName = "Zeta",
                videoEndpoint = "https://example.com/video.m3u8",
                onBack = {}
            )
        }

        composeRule.waitForIdle()
        screenshotRule.capture("train_control_initial", composeRule.onRoot())

        composeRule.onNodeWithText("Vitesse cible").assertIsDisplayed()
        composeRule.onNode(hasSetProgressAction())
            .performSemanticsAction(SemanticsActions.SetProgress) { action -> action(3.0f) }

        verify { viewModel.setTargetSpeed(3.0) }
    }

    @Test
    fun directionAndCabSelectionInvokeCallbacks() {
        composeRule.setContent {
            TrainControlScreen(
                viewModel = viewModel,
                trainName = "Zeta",
                videoEndpoint = "https://example.com/video.m3u8",
                onBack = {}
            )
        }

        composeRule.onNodeWithText("Reverse").performClick()
        composeRule.onNodeWithText("Rear").performClick()

        verify { viewModel.setDirection(Direction.REVERSE) }
        verify { viewModel.setActiveCab(ActiveCab.REAR) }
    }

    @Test
    fun videoErrorDisplaysOverlayAndRetryStartsStream() {
        composeRule.setContent {
            TrainControlScreen(
                viewModel = viewModel,
                trainName = "Zeta",
                videoEndpoint = "https://example.com/video.m3u8",
                onBack = {}
            )
        }

        videoState.value = VideoStreamUiState(
            state = VideoStreamState.Error("Flux indisponible"),
            isBuffering = false,
            errorMessage = "Flux indisponible"
        )

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Flux indisponible").assertIsDisplayed()
        composeRule.onNodeWithText("Réessayer").performClick()

        verify { viewModel.startVideoStream("https://example.com/video.m3u8") }
        screenshotRule.capture("train_control_error", composeRule.onRoot())
    }

    @Test
    fun bufferingStateShowsLoadingMessage() {
        composeRule.setContent {
            TrainControlScreen(
                viewModel = viewModel,
                trainName = "Zeta",
                videoEndpoint = "https://example.com/video.m3u8",
                onBack = {}
            )
        }

        videoState.value = VideoStreamUiState(
            state = VideoStreamState.Buffering,
            isBuffering = true,
            errorMessage = null
        )

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Chargement du flux…").assertIsDisplayed()
    }

    @Test
    fun missingVideoEndpointShowsUnavailableMessageAndStopsStream() {
        composeRule.setContent {
            TrainControlScreen(
                viewModel = viewModel,
                trainName = "Zeta",
                videoEndpoint = null,
                onBack = {}
            )
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText("Flux vidéo indisponible").assertIsDisplayed()
        verify { viewModel.stopVideoStream() }
    }
}
