package com.minitrain.app.ui.video

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.minitrain.app.ui.VideoStreamUiState

/**
 * Simple view that renders the video stream alongside loading and error indicators.
 */
class TrainVideoStreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val playerView = PlayerView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val progressBar = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
    }

    private val errorTextView = TextView(context).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
    }

    init {
        addView(playerView)
        addView(progressBar)
        addView(errorTextView)
    }

    fun setPlayer(player: Player?) {
        playerView.player = player
    }

    fun render(state: VideoStreamUiState) {
        progressBar.visibility = if (state.isBuffering) View.VISIBLE else View.GONE

        val errorMessage = state.errorMessage
        if (errorMessage.isNullOrBlank()) {
            errorTextView.visibility = View.GONE
            errorTextView.text = ""
        } else {
            errorTextView.visibility = View.VISIBLE
            errorTextView.text = errorMessage
        }
    }
}
