package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandFramesTest {
    @Test
    fun `serializer roundtrips direction`() {
        val session = ByteArray(16)
        Direction.entries.forEachIndexed { index, direction ->
            val frame = buildStateFrames(
                session,
                { index + 1 },
                { Instant.ofEpochSecond(index.toLong()) },
                ControlState(0.5, direction, false, false, false),
                byteArrayOf()
            )
            val decoded = CommandFrameSerializer.decode(CommandFrameSerializer.encode(frame))
            assertEquals(direction, decoded.header.direction)
        }
    }
}
