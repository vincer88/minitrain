package com.minitrain.app

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.network.CommandFrameSerializer
import com.minitrain.app.network.CommandKind
import com.minitrain.app.network.CommandPayloadType
import com.minitrain.app.network.buildStateFrames
import com.minitrain.app.network.buildHeader
import com.minitrain.app.network.buildTogglePayload
import com.minitrain.app.network.uuidToLittleEndian
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandEncoderTest {
    @Test
    fun `uuid is little endian`() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val encoded = uuidToLittleEndian(uuid)
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        val least = buffer.long
        val most = buffer.long
        assertEquals(uuid.leastSignificantBits, least)
        assertEquals(uuid.mostSignificantBits, most)
    }

    @Test
    fun `state frames include speed and toggles`() {
        val session = ByteArray(16) { it.toByte() }
        var sequence = 0
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val frames = buildStateFrames(
            session,
            { ++sequence },
            { now },
            ControlState(2.5, Direction.REVERSE, true, false, false),
            ControlState(1.0, Direction.FORWARD, false, false, false)
        )
        assertTrue(frames.size >= 3)
        val speedFrame = frames.first()
        assertEquals(CommandPayloadType.Command, speedFrame.header.payloadType)
        val decoded = CommandFrameSerializer.decode(CommandFrameSerializer.encode(speedFrame))
        assertEquals(speedFrame.header.payloadSize, decoded.header.payloadSize)
        val payload = decoded.payload
        assertEquals(CommandKind.SetSpeed.code, payload.first())
    }

    @Test
    fun `toggle payload encodes boolean`() {
        val payload = buildTogglePayload(CommandKind.ToggleHeadlights, true)
        assertContentEquals(byteArrayOf(CommandKind.ToggleHeadlights.code, 1), payload)
    }

    @Test
    fun `header builder preserves fields`() {
        val session = ByteArray(16)
        val header = buildHeader(session, 5, Instant.ofEpochSecond(10, 5), CommandPayloadType.Command, 3)
        assertEquals(5, header.sequence)
        assertEquals(CommandPayloadType.Command, header.payloadType)
        assertEquals(3, header.payloadSize)
        assertEquals(10_000_000_005L, header.timestampNanoseconds)
    }
}
