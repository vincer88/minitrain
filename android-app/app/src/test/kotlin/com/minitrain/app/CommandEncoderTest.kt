package com.minitrain.app

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.network.CommandFrameSerializer
import com.minitrain.app.network.buildStateFrames
import com.minitrain.app.network.buildHeader
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
    fun `state frame encodes aggregated state`() {
        val session = ByteArray(16) { it.toByte() }
        var sequence = 0
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val frame = buildStateFrames(
            session,
            { ++sequence },
            { now },
            ControlState(2.5, Direction.REVERSE, true, true, true),
            byteArrayOf()
        )
        val encoded = CommandFrameSerializer.encode(frame)
        val decoded = CommandFrameSerializer.decode(encoded)
        assertEquals(1, decoded.header.sequence)
        assertEquals(2.5f, decoded.header.targetSpeedMetersPerSecond)
        assertEquals(Direction.REVERSE, decoded.header.direction)
        assertEquals(0x03.toByte(), decoded.header.lightsOverride)
        assertTrue(decoded.payload.isNotEmpty())
        val flags = decoded.payload.first().toInt()
        assertEquals(0x07, flags)
    }

    @Test
    fun `auxiliary payload is appended after control flags`() {
        val session = ByteArray(16) { it.toByte() }
        val payload = byteArrayOf(0x10, 0x20)
        val frame = buildStateFrames(
            session,
            { 42 },
            { Instant.parse("2024-01-01T00:00:00Z") },
            ControlState(1.0, Direction.FORWARD, false, false, false),
            payload
        )
        assertEquals(payload.size + 1, frame.payload.size)
        assertEquals(0x00, frame.payload.first().toInt())
        assertContentEquals(payload, frame.payload.copyOfRange(1, frame.payload.size))
    }

    @Test
    fun `header builder preserves fields`() {
        val session = ByteArray(16)
        val header = buildHeader(
            session,
            5,
            Instant.ofEpochSecond(10, 5_000),
            3.5,
            Direction.FORWARD,
            0x01,
            4
        )
        assertEquals(5, header.sequence)
        assertEquals(3.5f, header.targetSpeedMetersPerSecond)
        assertEquals(Direction.FORWARD, header.direction)
        assertEquals(0x01.toByte(), header.lightsOverride)
        assertEquals(4, header.auxiliaryPayloadSize)
        assertEquals(10_000_005L, header.timestampMicros)
    }
}
