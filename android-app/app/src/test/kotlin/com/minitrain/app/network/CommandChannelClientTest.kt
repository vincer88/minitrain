package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandChannelClientTest {
    private class FakeSession(private val scope: TestScope) : CommandWebSocketSession {
        private var open = true
        private var congestOnce = false
        val sentFrames = mutableListOf<Pair<Long, ByteArray>>()

        override val isOpen: Boolean
            get() = open

        override suspend fun send(frame: ByteArray): Boolean {
            sentFrames += scope.testScheduler.currentTime to frame
            return if (congestOnce) {
                congestOnce = false
                false
            } else {
                true
            }
        }

        override suspend fun close(reason: CloseReason) {
            open = false
        }

        fun triggerCongestion() {
            congestOnce = true
        }
    }

    @Test
    fun `client degrades to 10Hz on congestion`() = runTest(StandardTestDispatcher()) {
        val session = FakeSession(this)
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID.randomUUID(),
            client = buildRealtimeHttpClient(),
            scope = this,
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        ) { _, _ -> CommandWebSocketFactory { session } }

        val state = MutableStateFlow(ControlState(1.0, Direction.FORWARD, false, false, false))
        val job: Job = client.start(state)

        advanceTimeBy(20)
        assertTrue(session.sentFrames.isNotEmpty())

        session.triggerCongestion()
        advanceTimeBy(20)
        val afterCongestion = session.sentFrames.last().first

        advanceTimeBy(100)
        val finalTimestamp = session.sentFrames.last().first
        assertEquals(100L, finalTimestamp - afterCongestion)

        job.cancelAndJoin()
    }

    @Test
    fun `immediate command is queued`() = runTest(StandardTestDispatcher()) {
        val session = FakeSession(this)
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID.randomUUID(),
            client = buildRealtimeHttpClient(),
            scope = this,
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        ) { _, _ -> CommandWebSocketFactory { session } }

        val state = MutableStateFlow(ControlState(0.0, Direction.FORWARD, false, false, false))
        val job = client.start(state)
        runCurrent()
        val auxiliary = byteArrayOf(0x55, 0x66)
        client.sendCommand(auxiliary)

        advanceTimeBy(200)
        val decoded = session.sentFrames.map { CommandFrameSerializer.decode(it.second) }
        assertTrue(
            decoded.any {
                it.payload.size >= 3 &&
                    it.payload[1] == auxiliary[0] &&
                    it.payload[2] == auxiliary[1]
            }
        )

        job.cancelAndJoin()
    }
}
