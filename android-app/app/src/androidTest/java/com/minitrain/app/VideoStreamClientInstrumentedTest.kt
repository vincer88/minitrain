package com.minitrain.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.minitrain.app.network.ExoPlayerVideoStreamClient
import com.minitrain.app.network.VideoStreamState
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoStreamClientInstrumentedTest {

    @Test
    fun startStreamEmitsErrorWhenSourceUnavailable() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val client = ExoPlayerVideoStreamClient(context)

        val states = mutableListOf<VideoStreamState>()
        val job = launch {
            client.state.take(2).toList(states)
        }

        client.start("asset:///missing_stream.m3u8")

        withTimeout(5_000) {
            job.join()
        }

        client.stop()
        client.release()

        assertTrue(states.isNotEmpty())
        assertTrue(states.last() is VideoStreamState.Error)
    }
}
