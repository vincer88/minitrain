package com.minitrain.app

import com.minitrain.app.model.ControlState
import com.minitrain.app.model.Direction
import com.minitrain.app.model.Telemetry
import com.minitrain.app.model.TrainCommand
import com.minitrain.app.network.CommandEncoder
import com.minitrain.app.network.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandEncoderTest {
    @Test
    fun `encode command with value`() {
        val command = TrainCommand("set_speed", "1.5")
        val encoded = CommandEncoder.encode(command)
        assertEquals("command=set_speed;value=1.5", encoded)
    }

    @Test
    fun `encode state to json`() {
        val state = ControlState(1.2, Direction.FORWARD, headlights = true, horn = false, emergencyStop = false)
        val json = CommandEncoder.encodeState(state)
        assertTrue(json.contains("\"targetSpeed\":1.2"))
        assertTrue(json.contains("\"direction\":\"FORWARD\""))
    }

    @Test
    fun `parse telemetry json`() {
        val telemetry = Telemetry(1.0, 0.5, 11.1, 30.0, true, false, Direction.FORWARD, false)
        val raw = CommandEncoder.encodeState(
            ControlState(
                targetSpeed = telemetry.speedMetersPerSecond,
                direction = telemetry.direction,
                headlights = telemetry.headlights,
                horn = telemetry.horn,
                emergencyStop = telemetry.emergencyStop
            )
        )
        val parsed = TelemetryParser.parse(
            """{
                "speedMetersPerSecond": ${telemetry.speedMetersPerSecond},
                "motorCurrentAmps": ${telemetry.motorCurrentAmps},
                "batteryVoltage": ${telemetry.batteryVoltage},
                "temperatureCelsius": ${telemetry.temperatureCelsius},
                "headlights": ${telemetry.headlights},
                "horn": ${telemetry.horn},
                "direction": "${telemetry.direction}",
                "emergencyStop": ${telemetry.emergencyStop}
            }"""
        )
        assertEquals(telemetry, parsed)
        assertTrue(raw.contains("targetSpeed"))
    }
}
