package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID

data class CommandFrameHeader(
    val sessionId: ByteArray,
    val sequence: Int,
    val timestampMicros: Long,
    val targetSpeedMetersPerSecond: Float,
    val direction: Direction,
    val lightsOverride: Byte,
    val auxiliaryPayloadLength: Int
)

data class CommandFrame(val header: CommandFrameHeader, val payload: ByteArray)

object CommandFrameSerializer {
    private const val HEADER_SIZE = 16 + 4 + 8 + 4 + 1 + 1 + 2

    fun encode(frame: CommandFrame): ByteArray {
        if (frame.payload.size != frame.header.auxiliaryPayloadLength) {
            throw IllegalArgumentException("Payload length mismatch")
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(frame.header.sessionId)
        buffer.putInt(frame.header.sequence)
        buffer.putLong(frame.header.timestampMicros)
        buffer.putFloat(frame.header.targetSpeedMetersPerSecond)
        buffer.put(directionToProtocol(frame.header.direction))
        buffer.put(frame.header.lightsOverride)
        buffer.putShort(frame.header.auxiliaryPayloadLength.toShort())
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decode(buffer: ByteArray): CommandFrame {
        if (buffer.size < HEADER_SIZE) {
            throw IllegalArgumentException("Buffer too small")
        }
        val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val sessionId = ByteArray(16)
        byteBuffer.get(sessionId)
        val sequence = byteBuffer.int
        val timestamp = byteBuffer.long
        val targetSpeed = byteBuffer.float
        val directionCode = byteBuffer.get()
        val lightsOverride = byteBuffer.get()
        val payloadLength = byteBuffer.short.toInt() and 0xFFFF
        if (buffer.size < HEADER_SIZE + payloadLength) {
            throw IllegalArgumentException("Incomplete payload")
        }
        val payload = ByteArray(payloadLength)
        byteBuffer.get(payload)
        val direction = protocolToDirection(directionCode)
        return CommandFrame(
            CommandFrameHeader(sessionId, sequence, timestamp, targetSpeed, direction, lightsOverride, payloadLength),
            payload
        )
    }

    private fun directionToProtocol(direction: Direction): Byte = when (direction) {
        Direction.NEUTRAL -> 0
        Direction.FORWARD -> 1
        Direction.REVERSE -> 2
    }.toByte()

    fun protocolToDirection(code: Byte): Direction = when (code.toInt() and 0xFF) {
        0 -> Direction.NEUTRAL
        1 -> Direction.FORWARD
        2 -> Direction.REVERSE
        else -> throw IllegalArgumentException("Unknown direction code: $code")
    }
}

fun uuidToLittleEndian(uuid: UUID): ByteArray {
    val buffer = ByteBuffer.allocate(16)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    return buffer.array().reversedArray()
}

fun littleEndianBytesToUuid(bytes: ByteArray): UUID {
    require(bytes.size == 16) { "UUID must be 16 bytes" }
    val buffer = ByteBuffer.wrap(bytes.reversedArray())
    buffer.order(ByteOrder.BIG_ENDIAN)
    val most = buffer.long
    val least = buffer.long
    return UUID(most, least)
}

fun buildHeader(
    sessionId: ByteArray,
    sequence: Int,
    timestamp: Instant,
    targetSpeed: Double,
    direction: Direction,
    lightsOverride: Byte = 0,
    payloadLength: Int
): CommandFrameHeader {
    require(sessionId.size == 16) { "Session identifier must be 16 bytes" }
    require(payloadLength in 0..0xFFFF) { "Payload too large" }
    val micros = timestamp.epochSecond * 1_000_000L + timestamp.nano / 1_000
    return CommandFrameHeader(
        sessionId = sessionId,
        sequence = sequence,
        timestampMicros = micros,
        targetSpeedMetersPerSecond = targetSpeed.toFloat(),
        direction = direction,
        lightsOverride = lightsOverride,
        auxiliaryPayloadLength = payloadLength
    )
}

private fun buildControlFlags(state: ControlState): Byte {
    var flags = 0
    if (state.headlights) {
        flags = flags or 0x01
    }
    if (state.horn) {
        flags = flags or 0x02
    }
    if (state.emergencyStop) {
        flags = flags or 0x04
    }
    return flags.toByte()
}

fun buildStateFrames(
    sessionId: ByteArray,
    sequenceSupplier: () -> Int,
    timestampProvider: () -> Instant,
    state: ControlState,
    auxiliaryPayload: ByteArray = byteArrayOf(),
    lightsOverride: Byte? = null
): CommandFrame {
    val timestamp = timestampProvider()
    val sequence = sequenceSupplier()
    val controlFlags = buildControlFlags(state)
    val payload = ByteArray(1 + auxiliaryPayload.size)
    payload[0] = controlFlags
    if (auxiliaryPayload.isNotEmpty()) {
        auxiliaryPayload.copyInto(payload, destinationOffset = 1)
    }
    val computedLightsOverride = lightsOverride ?: if (state.headlights) 0x03.toByte() else 0x00
    val header = buildHeader(
        sessionId = sessionId,
        sequence = sequence,
        timestamp = timestamp,
        targetSpeed = state.targetSpeed,
        direction = state.direction,
        lightsOverride = computedLightsOverride,
        payloadLength = payload.size
    )
    return CommandFrame(header, payload)
}
