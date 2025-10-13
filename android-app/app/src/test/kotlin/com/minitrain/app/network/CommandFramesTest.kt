package com.minitrain.app.network

import com.minitrain.app.model.Direction
import kotlin.test.Test
import kotlin.test.assertContentEquals

class CommandFramesTest {
    @Test
    fun `buildDirectionPayload encodes neutral`() {
        val payload = buildDirectionPayload(Direction.NEUTRAL)
        assertContentEquals(byteArrayOf(CommandKind.SetDirection.code, 0), payload)
    }

    @Test
    fun `buildDirectionPayload encodes forward`() {
        val payload = buildDirectionPayload(Direction.FORWARD)
        assertContentEquals(byteArrayOf(CommandKind.SetDirection.code, 1), payload)
    }

    @Test
    fun `buildDirectionPayload encodes reverse`() {
        val payload = buildDirectionPayload(Direction.REVERSE)
        assertContentEquals(byteArrayOf(CommandKind.SetDirection.code, 2), payload)
    }
}
