package com.example.agenticcar.data.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class SpikeFilterTest {

    private val rpm = 0x0C
    private val filter = SpikeFilter(mapOf(rpm to SpikeFilter.Rule(maxStep = 800.0, range = 0.0..8_000.0)))

    private fun feed(vararg values: Double) = values.filter { filter.accept(rpm, it) }

    @Test
    fun corruptSampleFromTheFirstTrip_isDropped() =
        assertEquals(listOf(910.0, 866.0, 931.0, 1274.0, 1425.0), feed(910.0, 866.0, 931.0, 4366.0, 1274.0, 1425.0))

    @Test
    fun idleBlipsFromThe29OctTrip_areDropped() =
        // Stopped, throttle closed: 746 -> 2350 -> 746 within one poll each.
        assertEquals(listOf(746.0, 750.0, 748.0, 752.0), feed(746.0, 2_350.0, 750.0, 2_352.0, 748.0, 752.0))

    @Test
    fun blipJustOverOldThreshold_isDropped() =
        // 1036 -> 2109 passed the previous 1500 rpm threshold.
        assertEquals(listOf(1_036.0, 1_040.0), feed(1_036.0, 2_109.0, 1_040.0))

    @Test
    fun impossibleRpm_isAlwaysDropped() =
        assertEquals(listOf(3_726.0, 3_700.0), feed(3_726.0, 12_432.0, 3_700.0))

    @Test
    fun realStepChange_losesOnlyTheFirstSample() =
        assertEquals(listOf(800.0, 3_100.0, 3_200.0), feed(800.0, 3_000.0, 3_100.0, 3_200.0))

    @Test
    fun fastRamp_keepsAtLeastEveryOtherSample() =
        assertEquals(listOf(800.0, 2_600.0, 4_400.0), feed(800.0, 1_700.0, 2_600.0, 3_500.0, 4_400.0))

    @Test
    fun gradualChange_passesUntouched() =
        assertEquals(listOf(800.0, 1_500.0, 2_200.0, 2_900.0), feed(800.0, 1_500.0, 2_200.0, 2_900.0))

    @Test
    fun signalsWithoutRule_passThrough() {
        assertEquals(true, filter.accept(0x05, 20.0))
        assertEquals(true, filter.accept(0x05, 90.0))
    }
}
