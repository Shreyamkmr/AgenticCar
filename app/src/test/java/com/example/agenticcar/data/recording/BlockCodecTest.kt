package com.example.agenticcar.data.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockCodecTest {

    @Test
    fun roundTrip_preservesTimesAndQuantisedValues() {
        val resolution = 0.25 // rpm
        val start = 120_000
        val encoder = BlockCodec.Encoder(start)
        val input = listOf(120_000 to 800.0, 120_250 to 812.5, 120_500 to 3_650.75, 150_000 to 0.0, 179_990 to 16_383.75)
        input.forEach { (t, v) -> encoder.add(t, Math.round(v / resolution)) }

        val decoded = BlockCodec.decode(encoder.toByteArray(), start, resolution)

        assertEquals(input.map { it.first }, decoded.map { it.tMs })
        input.zip(decoded).forEach { (expected, actual) -> assertEquals(expected.second, actual.value, 1e-9) }
    }

    @Test
    fun negativeValues_roundTrip() {
        val encoder = BlockCodec.Encoder(0)
        encoder.add(0, -128) // fuel trim -100 %
        encoder.add(1_000, 127)
        val decoded = BlockCodec.decode(encoder.toByteArray(), 0, 100.0 / 128)
        assertEquals(-100.0, decoded[0].value, 1e-9)
        assertEquals(99.21875, decoded[1].value, 1e-9)
    }

    @Test
    fun steadySignalAt4Hz_costsAboutTwoBytesPerSample() {
        val encoder = BlockCodec.Encoder(0)
        repeat(240) { i -> encoder.add(i * 250, 3_200L + (i % 3)) } // one minute of RPM jitter
        val bytesPerSample = encoder.toByteArray().size / 240.0
        assertTrue("was $bytesPerSample", bytesPerSample <= 2.1)
    }
}
