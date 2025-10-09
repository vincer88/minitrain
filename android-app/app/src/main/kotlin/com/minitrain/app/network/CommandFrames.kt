package com.minitrain.app.network

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.UUID

enum class CommandPayloadType(val code: Short) {
    Command(0x0001),
    LegacyText(0x00FE),
    Heartbeat(0x00FF)
}

enum class CommandKind(val code: Byte) {
    SetSpeed(0x01),
    SetDirection(0x02),
    ToggleHeadlights(0x03),
    ToggleHorn(0x04),
    EmergencyStop(0x05)
}

data class CommandFrameHeader(
    val sessionId: ByteArray,
    val sequence: Int,
    val timestampNanoseconds: Long,
    val payloadType: CommandPayloadType,
    val payloadSize: Int
)

data class CommandFrame(val header: CommandFrameHeader, val payload: ByteArray)

object CommandFrameSerializer {
    private const val HEADER_SIZE = 16 + 4 + 8 + 2 + 2

    fun encode(frame: CommandFrame): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + frame.payload.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(frame.header.sessionId)
        buffer.putInt(frame.header.sequence)
        buffer.putLong(frame.header.timestampNanoseconds)
        buffer.putShort(frame.header.payloadType.code)
        buffer.putShort(frame.header.payloadSize.toShort())
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
        val payloadTypeCode = byteBuffer.short
        val payloadSize = byteBuffer.short.toInt() and 0xFFFF
        if (buffer.size < HEADER_SIZE + payloadSize) {
            throw IllegalArgumentException("Incomplete payload")
        }
        val payload = ByteArray(payloadSize)
        byteBuffer.get(payload)
        val payloadType = CommandPayloadType.entries.firstOrNull { it.code == payloadTypeCode }
            ?: throw IllegalArgumentException("Unknown payload type")
        return CommandFrame(CommandFrameHeader(sessionId, sequence, timestamp, payloadType, payloadSize), payload)
    }
}

fun uuidToLittleEndian(uuid: UUID): ByteArray {
    val buffer = ByteBuffer.allocate(16)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.putLong(uuid.mostSignificantBits)
    buffer.putLong(uuid.leastSignificantBits)
    return buffer.array().reversedArray()
}

fun buildSpeedCommandPayload(speed: Double): ByteArray {
    val buffer = ByteBuffer.allocate(1 + 4).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put(CommandKind.SetSpeed.code)
    buffer.putFloat(speed.toFloat())
    return buffer.array()
}

fun buildDirectionPayload(direction: Direction): ByteArray {
    val buffer = ByteBuffer.allocate(2)
    buffer.put(CommandKind.SetDirection.code)
    buffer.put(if (direction == Direction.FORWARD) 0 else 1)
    return buffer.array()
}

fun buildTogglePayload(kind: CommandKind, enabled: Boolean): ByteArray {
    require(kind == CommandKind.ToggleHeadlights || kind == CommandKind.ToggleHorn) {
        "Toggle payload only valid for headlights or horn"
    }
    val buffer = ByteBuffer.allocate(2)
    buffer.put(kind.code)
    buffer.put(if (enabled) 1 else 0)
    return buffer.array()
}

fun buildEmergencyPayload(): ByteArray = byteArrayOf(CommandKind.EmergencyStop.code)

fun Direction.toProtocolValue(): String = when (this) {
    Direction.FORWARD -> "forward"
    Direction.REVERSE -> "reverse"
}

fun buildHeader(
    sessionId: ByteArray,
    sequence: Int,
    timestamp: Instant,
    payloadType: CommandPayloadType,
    payloadSize: Int
): CommandFrameHeader {
    val nanos = timestamp.epochSecond * 1_000_000_000L + timestamp.nano
    return CommandFrameHeader(sessionId, sequence, nanos, payloadType, payloadSize)
}

fun buildStateFrames(
    sessionId: ByteArray,
    sequenceSupplier: () -> Int,
    timestampProvider: () -> Instant,
    state: ControlState,
    previous: ControlState?
): List<CommandFrame> {
    val frames = mutableListOf<CommandFrame>()
    val baseTimestamp = timestampProvider()
    frames += CommandFrame(
        buildHeader(sessionId, sequenceSupplier(), baseTimestamp, CommandPayloadType.Command, 5),
        buildSpeedCommandPayload(state.targetSpeed)
    )
    if (previous?.direction != state.direction) {
        frames += CommandFrame(
            buildHeader(sessionId, sequenceSupplier(), timestampProvider(), CommandPayloadType.Command, 2),
            buildDirectionPayload(state.direction)
        )
    }
    if (previous?.headlights != state.headlights) {
        frames += CommandFrame(
            buildHeader(sessionId, sequenceSupplier(), timestampProvider(), CommandPayloadType.Command, 2),
            buildTogglePayload(CommandKind.ToggleHeadlights, state.headlights)
        )
    }
    if (previous?.horn != state.horn) {
        frames += CommandFrame(
            buildHeader(sessionId, sequenceSupplier(), timestampProvider(), CommandPayloadType.Command, 2),
            buildTogglePayload(CommandKind.ToggleHorn, state.horn)
        )
    }
    if (state.emergencyStop && previous?.emergencyStop != true) {
        frames += CommandFrame(
            buildHeader(sessionId, sequenceSupplier(), timestampProvider(), CommandPayloadType.Command, 1),
            buildEmergencyPayload()
        )
    }
    return frames
}
