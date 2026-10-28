package com.example.agenticcar.data.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeFilterTest {

    private val rpm = 0x0C
    private val filter = SpikeFilter(mapOf(rpm to 1_500.0))

    private fun feed(vararg values: Double) = values.filter { filter.accept(rpm, it) }

    @Test
    fun corruptSampleFromTheRealTrip_isDropped() =
        assertEquals(listOf(910.0, 866.0, 931.0, 1274.0, 1425.0), feed(910.0, 866.0, 931.0, 4366.0, 1274.0, 1425.0))

    @Test
    fun realStepChange_losesOnlyTheFirstSample() =
        assertEquals(listOf(800.0, 3_100.0, 3_200.0), feed(800.0, 3_000.0, 3_100.0, 3_200.0))

    @Test
    fun gradualChange_passesUntouched() =
        assertEquals(listOf(800.0, 1_800.0, 2_800.0, 3_800.0), feed(800.0, 1_800.0, 2_800.0, 3_800.0))

    @Test
    fun signalsWithoutThreshold_passThrough() {
        assertEquals(true, filter.accept(0x05, 20.0))
        assertEquals(true, filter.accept(0x05, 90.0))
    }
}
