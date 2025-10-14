package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandChannelClientTest {
    private class FakeSession(private val scope: TestScope) : CommandWebSocketSession {
        private var open = true
        private var congestOnce = false
        private val incoming = Channel<ByteArray?>(Channel.UNLIMITED)
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
            if (open) {
                open = false
                incoming.trySend(null)
            }
        }

        override suspend fun receive(): ByteArray? = incoming.receive()

        fun triggerCongestion() {
            congestOnce = true
        }

        fun enqueueIncoming(frame: ByteArray) {
            incoming.trySend(frame)
        }
    }

    private fun testClock(scope: TestScope): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(scope.testScheduler.currentTime)
    }

    private fun telemetryJson(timestampMicros: Long): String = """
        {
          "sessionId": "00000000-0000-0000-0000-000000000000",
          "sequence": 1,
          "commandTimestamp": $timestampMicros,
          "speedMetersPerSecond": 0.0,
          "motorCurrentAmps": 0.0,
          "batteryVoltage": 0.0,
          "temperatureCelsius": 0.0,
          "appliedSpeedMetersPerSecond": 0.0,
          "appliedDirection": "FORWARD",
          "headlights": false,
          "horn": false,
          "direction": "FORWARD",
          "emergencyStop": false,
          "activeCab": "FRONT",
          "lightsState": "BOTH_RED",
          "lightsSource": "AUTOMATIC",
          "source": "INSTANTANEOUS",
          "lightsOverrideMask": 0,
          "lightsTelemetryOnly": false
        }
    """.trimIndent()

    private fun telemetryFrame(timestampMicros: Long): ByteArray {
        val payload = telemetryJson(timestampMicros).toByteArray()
        val header = CommandFrameHeader(
            sessionId = ByteArray(16),
            sequence = 1,
            timestampMicros = timestampMicros,
            targetSpeedMetersPerSecond = 0f,
            direction = Direction.FORWARD,
            lightsOverride = 0x80.toByte(),
            auxiliaryPayloadSize = payload.size
        )
        return CommandFrameSerializer.encode(CommandFrame(header, payload))
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

    @Test
    fun `stale telemetry activates failsafe ramp`() = runTest(StandardTestDispatcher()) {
        val session = FakeSession(this)
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID.randomUUID(),
            client = buildRealtimeHttpClient(),
            scope = this,
            clock = testClock(this),
            failsafeConfig = TelemetryFailsafeConfig(
                staleThreshold = Duration.ofMillis(50),
                rampDuration = Duration.ofMillis(200)
            )
        ) { _, _ -> CommandWebSocketFactory { session } }

        val state = MutableStateFlow(ControlState(1.0, Direction.FORWARD, false, false, false))
        val job = client.start(state)
        runCurrent()

        session.enqueueIncoming(telemetryFrame(0))
        runCurrent()

        advanceTimeBy(40)
        runCurrent()
        assertFalse(client.failsafeRampStatus.value.isActive)

        advanceTimeBy(20)
        runCurrent()
        val afterStale = CommandFrameSerializer.decode(session.sentFrames.last().second)
        assertTrue(client.failsafeRampStatus.value.isActive)
        assertEquals(Direction.FORWARD, afterStale.header.direction)
        assertEquals(0x00.toByte(), afterStale.header.lightsOverride)

        job.cancelAndJoin()
    }

    @Test
    fun `failsafe ramp decreases speed to zero`() = runTest(StandardTestDispatcher()) {
        val session = FakeSession(this)
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID.randomUUID(),
            client = buildRealtimeHttpClient(),
            scope = this,
            clock = testClock(this),
            failsafeConfig = TelemetryFailsafeConfig(
                staleThreshold = Duration.ofMillis(30),
                rampDuration = Duration.ofMillis(200)
            )
        ) { _, _ -> CommandWebSocketFactory { session } }

        val state = MutableStateFlow(ControlState(2.0, Direction.FORWARD, false, false, false))
        val job = client.start(state)
        runCurrent()

        session.enqueueIncoming(telemetryFrame(0))
        runCurrent()

        advanceTimeBy(40)
        runCurrent()
        val startFrame = CommandFrameSerializer.decode(session.sentFrames.last().second)
        val startSpeed = startFrame.header.targetSpeedMetersPerSecond.toDouble()

        advanceTimeBy(100)
        runCurrent()
        val midFrame = CommandFrameSerializer.decode(session.sentFrames.last().second)
        val midSpeed = midFrame.header.targetSpeedMetersPerSecond.toDouble()
        val rampStatus = client.failsafeRampStatus.value

        advanceTimeBy(200)
        runCurrent()
        val finalFrame = CommandFrameSerializer.decode(session.sentFrames.last().second)
        val finalSpeed = finalFrame.header.targetSpeedMetersPerSecond.toDouble()
        val finalStatus = client.failsafeRampStatus.value

        assertTrue(midSpeed < startSpeed)
        assertTrue(finalSpeed <= 0.01)
        assertEquals(Direction.NEUTRAL, finalFrame.header.direction)
        assertTrue(rampStatus.isActive)
        assertTrue(rampStatus.progress in 0.0..1.0)
        assertFalse(finalStatus.isActive)

        job.cancelAndJoin()
    }

    @Test
    fun `fresh telemetry clears failsafe ramp`() = runTest(StandardTestDispatcher()) {
        val session = FakeSession(this)
        val client = CommandChannelClient(
            endpoint = "wss://example/ws",
            sessionId = UUID.randomUUID(),
            client = buildRealtimeHttpClient(),
            scope = this,
            clock = testClock(this),
            failsafeConfig = TelemetryFailsafeConfig(
                staleThreshold = Duration.ofMillis(30),
                rampDuration = Duration.ofMillis(200)
            )
        ) { _, _ -> CommandWebSocketFactory { session } }

        val state = MutableStateFlow(ControlState(2.0, Direction.FORWARD, false, false, false))
        val job = client.start(state)
        runCurrent()

        session.enqueueIncoming(telemetryFrame(0))
        runCurrent()

        advanceTimeBy(40)
        runCurrent()
        assertTrue(client.failsafeRampStatus.value.isActive)

        session.enqueueIncoming(telemetryFrame(40_000))
        runCurrent()
        assertFalse(client.failsafeRampStatus.value.isActive)

        job.cancelAndJoin()
    }
}
