package com.example.agenticcar.data.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class GradeEstimatorTest {

    /** Drives north at [speedMs] for [seconds] on a constant [gradePct] with ±[noiseM] altitude noise. */
    private fun drive(gradePct: Double, seconds: Int, speedMs: Double = 20.0, noiseM: Double = 0.0): List<Double?> {
        val estimator = GradeEstimator()
        val random = Random(7)
        val metresPerDegreeLat = 111_195.0
        return (0 until seconds).map { s ->
            val distance = s * speedMs
            val noise = if (noiseM > 0) random.nextDouble(-noiseM, noiseM) else 0.0
            estimator.update(
                lat = 53.0 + distance / metresPerDegreeLat,
                lon = -1.8,
                altitudeM = 200 + distance * gradePct / 100 + noise,
                speedMs = speedMs,
                accuracyM = 5.0,
            )
        }
    }

    @Test
    fun steadyClimb_isMeasuredAccurately() {
        val grades = drive(gradePct = 5.0, seconds = 60)
        assertEquals(5.0, grades.last()!!, 0.2)
    }

    @Test
    fun descent_isNegative() {
        assertEquals(-4.0, drive(gradePct = -4.0, seconds = 60).last()!!, 0.2)
    }

    @Test
    fun gpsNoise_isSmoothedToWithinOnePercent() {
        val grades = drive(gradePct = 6.0, seconds = 120, noiseM = 2.0).takeLast(60).filterNotNull()
        assertEquals(6.0, grades.average(), 1.0)
    }

    @Test
    fun noGradeUntilEnoughDistance() {
        // 20 m/s needs 5 s to cover the 100 m window; the first fixes cannot produce a grade.
        assertNull(drive(gradePct = 5.0, seconds = 3).last())
        assertNotNull(drive(gradePct = 5.0, seconds = 10).last())
    }

    @Test
    fun parked_givesNoGrade() {
        assertNull(drive(gradePct = 5.0, seconds = 30, speedMs = 0.5).last())
    }

    @Test
    fun braking_doesNotBiasGrade() {
        // Constant 5 % slope while slowing from 25 m/s to 2.5 m/s: per-fix smoothing used to over-read here.
        val estimator = GradeEstimator()
        var distance = 0.0
        var last: Double? = null
        for (s in 0 until 60) {
            val speed = (25.0 - s * 0.4).coerceAtLeast(2.5)
            distance += speed
            last = estimator.update(
                lat = 53.0 + distance / 111_195.0,
                lon = -1.8,
                altitudeM = 200 + distance * 0.05,
                speedMs = speed,
                accuracyM = 5.0,
            ) ?: last
        }
        assertEquals(5.0, last!!, 0.3)
    }

    @Test
    fun haversine_oneDegreeOfLatitude() {
        assertEquals(111_195.0, GradeEstimator.haversineM(53.0, -1.8, 54.0, -1.8), 50.0)
    }
}
