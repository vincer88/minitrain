package com.minitrain.app

import com.minitrain.app.model.TelemetrySource
import com.minitrain.app.network.TelemetryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryParserTest {
    private fun telemetryJson(session: String, sequence: Int, timestamp: Long, source: TelemetrySource = TelemetrySource.INSTANTANEOUS): String =
        """
        {
          "sessionId": "$session",
          "sequence": $sequence,
          "commandTimestamp": $timestamp,
          "speedMetersPerSecond": 1.2,
          "motorCurrentAmps": 0.4,
          "batteryVoltage": 11.0,
          "temperatureCelsius": 29.5,
          "appliedSpeedMetersPerSecond": 1.1,
          "appliedDirection": "FORWARD",
          "headlights": true,
          "horn": false,
          "direction": "FORWARD",
          "emergencyStop": false,
          "activeCab": "FRONT",
          "lightsState": "FRONT_WHITE_REAR_RED",
          "lightsSource": "AUTOMATIC",
          "source": "${source.name}",
          "lightsOverrideMask": 0,
          "lightsTelemetryOnly": false
        }
        """.trimIndent()

    @Test
    fun `parser exposes correlation metadata`() {
        val session = "123e4567-e89b-12d3-a456-426614174000"
        val telemetry = TelemetryParser.parse(telemetryJson(session, 41, 9999L))
        assertEquals(session, telemetry.sessionId)
        assertEquals(41, telemetry.sequence)
        assertEquals(9999L, telemetry.commandTimestamp)
        assertEquals(TelemetrySource.INSTANTANEOUS, telemetry.source)
    }

    @Test
    fun `parser preserves sequence ordering within session`() {
        val session = "123e4567-e89b-12d3-a456-426614174000"
        val first = TelemetryParser.parse(telemetryJson(session, 10, 100L, TelemetrySource.AGGREGATED))
        val second = TelemetryParser.parse(telemetryJson(session, 11, 150L, TelemetrySource.AGGREGATED))
        assertEquals(first.sessionId, second.sessionId)
        assertTrue(second.sequence > first.sequence)
        assertTrue(second.commandTimestamp >= first.commandTimestamp)
    }
}
