package com.minitrain.app.network

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the different states emitted by a [VideoStreamClient].
 */
sealed class VideoStreamState {
    /**
     * The client has no active stream.
     */
    data object Idle : VideoStreamState()

    /**
     * The client is buffering data from the remote stream.
     */
    data object Buffering : VideoStreamState()

    /**
     * A video is currently playing.
     */
    data class Playing(val mediaItem: MediaItem) : VideoStreamState()

    /**
     * Playback finished successfully.
     */
    data object Ended : VideoStreamState()

    /**
     * An unrecoverable error occurred while trying to play the stream.
     */
    data class Error(val message: String, val throwable: Throwable? = null) : VideoStreamState()
}

/**
 * Abstraction over a video streaming client implementation.
 */
interface VideoStreamClient {
    val state: StateFlow<VideoStreamState>
    val player: Player?

    fun start(url: String)
    fun stop()
    fun release()
}

/**
 * Default no-op implementation used when no video streaming backend is configured.
 */
class UnconfiguredVideoStreamClient : VideoStreamClient {
    private val _state = MutableStateFlow<VideoStreamState>(VideoStreamState.Idle)
    override val state: StateFlow<VideoStreamState> = _state.asStateFlow()
    override val player: Player? = null

    override fun start(url: String) {
        _state.value = VideoStreamState.Error("Video streaming client not configured")
    }

    override fun stop() {
        _state.value = VideoStreamState.Idle
    }

    override fun release() {
        _state.value = VideoStreamState.Idle
    }
}

/**
 * Video streaming client backed by ExoPlayer, suitable for HLS/MPEG-DASH streams.
 */
class ExoPlayerVideoStreamClient @JvmOverloads constructor(
    context: Context,
    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()
) : VideoStreamClient {

    private val _state = MutableStateFlow<VideoStreamState>(VideoStreamState.Idle)
    override val state: StateFlow<VideoStreamState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    exoPlayer.currentMediaItem?.let {
                        _state.value = VideoStreamState.Playing(it)
                    }
                }

                Player.STATE_BUFFERING -> _state.value = VideoStreamState.Buffering
                Player.STATE_ENDED -> _state.value = VideoStreamState.Ended
                Player.STATE_IDLE -> _state.value = VideoStreamState.Idle
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = VideoStreamState.Error(error.localizedMessage ?: "Unknown playback error", error)
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    override val player: Player
        get() = exoPlayer

    override fun start(url: String) {
        try {
            val mediaItem = MediaItem.fromUri(url)
            _state.value = VideoStreamState.Buffering
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (t: Throwable) {
            _state.value = VideoStreamState.Error("Failed to start video stream", t)
        }
    }

    override fun stop() {
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _state.value = VideoStreamState.Idle
    }

    override fun release() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
        _state.value = VideoStreamState.Idle
    }
}
