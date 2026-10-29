package com.example.agenticcar.protocol.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ParserTest {

    private fun rpm(response: String): Double? =
        Elm327Parser.mode01(response, listOf(0x0C))[0x0C]?.let { PidCatalog.pids.getValue(0x0C).decode(it).first() }

    @Test fun rpm_spaced() = assertEquals(1726.0, rpm("41 0C 1A F8 \r\r>")!!, 0.0)

    @Test fun rpm_afterSearching() = assertEquals(1726.0, rpm("SEARCHING...\r41 0C 1A F8\r\r>")!!, 0.0)

    @Test fun rpm_compact() = assertEquals(1726.0, rpm("410C1AF8\r>")!!, 0.0)

    @Test fun noData() = assertNull(rpm("NO DATA\r\r>"))

    @Test fun shortFrame() = assertNull(rpm("41 0C 1A\r>"))

    @Test
    fun multiPid_singleFrame() {
        val parsed = Elm327Parser.mode01("41 0D 58 05 5A 04 FF\r\r>", listOf(0x0D, 0x05, 0x04))
        assertArrayEquals(intArrayOf(0x58), parsed[0x0D])
        assertArrayEquals(intArrayOf(0x5A), parsed[0x05])
        assertArrayEquals(intArrayOf(0xFF), parsed[0x04])
    }

    @Test
    fun multiPid_isoTpMultiFrame_trimsPadding() {
        // 0x0C = 12 bytes; last frame padded with a zero.
        val response = "00C\r0: 41 0C 1A F8 0D 58\r1: 05 5A 04 FF 11 33 00\r\r>"
        val parsed = Elm327Parser.mode01(response, listOf(0x0C, 0x0D, 0x05, 0x04, 0x11))
        assertEquals(setOf(0x0C, 0x0D, 0x05, 0x04, 0x11), parsed.keys)
        assertArrayEquals(intArrayOf(0x33), parsed[0x11])
    }

    @Test
    fun multiFrame_compactSpacesOff() {
        val parsed = Elm327Parser.mode01("00C\r0:410C1AF80D58\r1:055A04FF113300\r\r>", listOf(0x0C, 0x11))
        assertArrayEquals(intArrayOf(0x1A, 0xF8), parsed[0x0C])
        assertArrayEquals(intArrayOf(0x33), parsed[0x11])
    }

    @Test
    fun multiFrame_lostMiddleFrame_isDropped() {
        // Frame 1 lost over Bluetooth: joining 0 and 2 would read padding and other PIDs' bytes as values.
        val response = "014\r0: 41 0C 1A F8 0D 58\r2: 33 0B 1E 0E 90 00 00\r\r>"
        assertTrue(Elm327Parser.mode01(response, listOf(0x0C, 0x0D, 0x11, 0x0B, 0x0E)).isEmpty())
    }

    @Test
    fun multiFrame_truncatedLastFrame_isDropped() {
        val response = "00E\r0: 41 0C 1A F8 0D 58\r1: 05 5A\r\r>"
        assertTrue(Elm327Parser.mode01(response, listOf(0x0C, 0x0D, 0x05, 0x04, 0x11)).isEmpty())
    }

    @Test
    fun unknownPid_requestedAlone_takesRestOfMessage() {
        val parsed = Elm327Parser.mode01("41 4F 01 02 03 04\r>", listOf(0x4F))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), parsed[0x4F])
    }

    @Test
    fun supportedPids_unionAcrossEcus() {
        // ECU 1 supports 01 and 0C; ECU 2 supports 0D and chains to 0x20.
        val response = "41 00 80 10 00 00\r41 00 00 08 00 01\r\r>"
        assertEquals(setOf(0x01, 0x0C, 0x0D, 0x20), Elm327Parser.supportedPids(response, 0x00))
    }

    @Test
    fun dtcs_canWithCountByte() =
        assertEquals(listOf("P0133", "P0420"), Elm327Parser.dtcs("43 02 01 33 04 20\r>", 0x43))

    @Test
    fun dtcs_legacyPaddedTriplets() =
        assertEquals(listOf("P0133", "C0300"), Elm327Parser.dtcs("43 01 33 43 00 00 00\r>", 0x43))

    @Test
    fun dtcs_none() = assertTrue(Elm327Parser.dtcs("43 00\r>", 0x43).isEmpty())

    @Test
    fun vin_multiFrame() {
        val response = "014\r0: 49 02 01 31 48 47\r1: 43 4D 38 32 36 33 33\r2: 41 30 30 34 33 35 32\r\r>"
        assertEquals("1HGCM82633A004352", Elm327Parser.vin(response))
    }

    @Test fun voltage() = assertEquals(14.1f, Elm327Parser.parseVoltage("14.1V\r\r>")!!, 0.001f)

    @Test fun voltageError() = assertNull(Elm327Parser.parseVoltage("?\r>"))

    @Test
    fun catalog_o2SensorTrimUnusedIsNull() {
        val values = PidCatalog.pids.getValue(0x15).decode(intArrayOf(0x82, 0xFF))
        assertEquals(0.65, values[0]!!, 1e-9)
        assertNull(values[1])
    }
}
