package com.example.agenticcar.data.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Road grade (%) from a stream of GPS fixes.
 *
 * GPS altitude jitters by several metres, so a fix-to-fix slope is meaningless. Instead:
 *  - altitude is smoothed with an exponential moving average over *distance* travelled
 *    (constant [smoothingM]), so its lag is the same number of metres at any speed; smoothing per
 *    fix would lag less and less while braking and bias the slope,
 *  - distance only accumulates while moving with a usable fix (no jitter while parked),
 *  - grade is rise over run across at least [windowM] metres of travel.
 */
class GradeEstimator(
    private val windowM: Double = 100.0,
    private val smoothingM: Double = 40.0,
    private val minSpeedMs: Double = 2.0,
    private val maxAccuracyM: Double = 25.0,
) {
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var distanceM = 0.0
    private var smoothedAlt: Double? = null
    /** (cumulative distance, smoothed altitude) across the window. */
    private val window = ArrayDeque<Pair<Double, Double>>()

    /** Returns the current grade in percent, or null while it cannot be estimated. */
    fun update(lat: Double, lon: Double, altitudeM: Double?, speedMs: Double?, accuracyM: Double?): Double? {
        val usable = altitudeM != null && (speedMs ?: 0.0) >= minSpeedMs && (accuracyM ?: 0.0) <= maxAccuracyM
        if (!usable) {
            // Restart cleanly after a stop or a bad patch instead of bridging it.
            lastLat = Double.NaN
            window.clear()
            return null
        }
        val step = if (lastLat.isNaN()) 0.0 else haversineM(lastLat, lastLon, lat, lon)
        distanceM += step
        lastLat = lat
        lastLon = lon

        val previous = smoothedAlt
        val alt = if (previous == null) altitudeM else previous + (1 - exp(-step / smoothingM)) * (altitudeM - previous)
        smoothedAlt = alt
        window.addLast(distanceM to alt)
        // Keep the oldest sample that still spans at least the window.
        while (window.size > 2 && distanceM - window[1].first >= windowM) window.removeFirst()

        val (d0, a0) = window.first()
        val span = distanceM - d0
        return if (span >= windowM) ((alt - a0) / span * 100).coerceIn(-MAX_GRADE, MAX_GRADE) else null
    }

    companion object {
        private const val MAX_GRADE = 30.0
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(h))
        }
    }
}
