package io.github.darthr4v3m.ohtello.tello.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TelloStateTest {

    /** A real packet from a Tello on firmware 02.05.01.17, SDK 2.0. */
    private val realPacket =
        "pitch:-1;roll:0;yaw:3;vgx:0;vgy:0;vgz:0;templ:71;temph:74;tof:10;h:0;bat:86;" +
            "baro:-19.19;time:0;agx:-6.00;agy:-1.00;agz:-999.00;\r\n"

    @Test
    fun `every documented field is readable`() {
        val state = parseTelloState(realPacket)
        assertNotNull(state)
        requireNotNull(state)

        assertEquals(86, state.batteryPercent)
        assertEquals(0, state.heightCm)
        assertEquals(10, state.timeOfFlightCm)
        assertEquals(0, state.flightTimeSeconds)
        assertEquals(-19.19, state.barometerCm!!, 0.001)
        assertEquals(71, state.temperatureLowC)
        assertEquals(74, state.temperatureHighC)
        assertEquals(-1, state.pitchDeg)
        assertEquals(0, state.rollDeg)
        assertEquals(3, state.yawDeg)
        assertEquals(0, state.speedXCmS)
        assertEquals(-999.0, state.accelerationZ!!, 0.001)
    }

    @Test
    fun `trailing separator does not produce an empty field`() {
        val state = requireNotNull(parseTelloState(realPacket))
        assertEquals(16, state.fields.size)
        assertFalse(state.fields.keys.any { it.isEmpty() })
    }

    @Test
    fun `line endings are stripped from the raw text and the last value`() {
        val state = requireNotNull(parseTelloState("h:0;bat:86;\r\n"))
        assertEquals("86", state.fields["bat"])
        assertEquals("h:0;bat:86;", state.raw)
    }

    @Test
    fun `unknown keys are kept and missing keys read as null`() {
        // Firmware revisions come and go with fields; one unfamiliar key must
        // not cost us the battery reading in the same packet.
        val state = requireNotNull(parseTelloState("bat:42;mid:-1;wildcard:7;"))
        assertEquals(42, state.batteryPercent)
        assertEquals("7", state.fields["wildcard"])
        assertEquals(-1, state.int("mid"))
        assertNull(state.heightCm)
    }

    @Test
    fun `values that are not numbers read as null instead of throwing`() {
        val state = requireNotNull(parseTelloState("bat:;h:abc;tof:12"))
        assertNull(state.batteryPercent)
        assertNull(state.heightCm)
        assertEquals(12, state.timeOfFlightCm)
    }

    @Test
    fun `payloads with no key value pairs are rejected`() {
        assertNull(parseTelloState(""))
        assertNull(parseTelloState("\r\n"))
        assertNull(parseTelloState("ok"))
        assertNull(parseTelloState(";;;"))
        // A truncated packet still yields whatever survived.
        assertEquals(1, requireNotNull(parseTelloState("pitch:-1;rol")).fields.size)
    }

    @Test
    fun `a value containing a colon keeps everything after the first one`() {
        val state = requireNotNull(parseTelloState("sn:0TQ:ZGA;bat:50;"))
        assertEquals("0TQ:ZGA", state.fields["sn"])
        assertEquals(50, state.batteryPercent)
    }
}
