package com.example.agenticcar.protocol.mcp

import com.example.agenticcar.data.db.TelemetryDao
import com.example.agenticcar.data.db.TripEntity
import com.example.agenticcar.data.recording.BlockCodec
import com.example.agenticcar.data.recording.BlockCodec.Point
import com.example.agenticcar.data.recording.TelemetryRecorder
import com.example.agenticcar.protocol.obd.PidCatalog
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Read side of the block store: decodes compressed samples back into step-hold
 * series and derives trip-level insights an AI can reason about directly.
 */
@Singleton
class TelemetryQueries @Inject constructor(private val dao: TelemetryDao) {

    /** Decoded points per signal, in time order, covering `[fromMs, toMs)` of the trip (ms from trip start). */
    suspend fun series(tripId: Long, signalIds: List<Int>, fromMs: Int, toMs: Int): Map<Int, List<Point>> {
        if (signalIds.isEmpty()) return emptyMap()
        val blocks = dao.blocks(tripId, signalIds, fromMs - TelemetryRecorder.BLOCK_MS, toMs)
        return blocks.groupBy { it.signalId }.mapValues { (signalId, list) ->
            val resolution = PidCatalog.signal(signalId)?.resolution ?: 1.0
            list.flatMap { BlockCodec.decode(it.data, it.startMs, resolution) }
        }
    }

    fun tripEndMs(trip: TripEntity): Int = ((trip.endedAt ?: trip.updatedAt) - trip.id).toInt().coerceAtLeast(0)

    /** Behavioural and mechanical insights for one trip, computed from the full-resolution series. */
    suspend fun insights(trip: TripEntity): TripInsights {
        val end = tripEndMs(trip)
        val ids = listOf(
            PidCatalog.ID_SPEED, PidCatalog.ID_RPM, PidCatalog.ID_COOLANT,
            PidCatalog.ADAPTER_VOLTAGE_ID, PidCatalog.ID_MODULE_VOLTAGE,
        )
        val s = series(trip.id, ids, 0, end + 1)
        val speed = s[PidCatalog.ID_SPEED].orEmpty()
        val rpm = s[PidCatalog.ID_RPM].orEmpty()
        val coolant = s[PidCatalog.ID_COOLANT].orEmpty()
        val volts = s[PidCatalog.ADAPTER_VOLTAGE_ID] ?: s[PidCatalog.ID_MODULE_VOLTAGE].orEmpty()

        val speedBands = linkedMapOf("0" to 0.0, "1-30" to 0.0, "30-60" to 0.0, "60-90" to 0.0, "90-120" to 0.0, ">120" to 0.0)
        forEachHeld(speed, end) { v, ms ->
            val band = when {
                v < 1 -> "0"
                v < 30 -> "1-30"
                v < 60 -> "30-60"
                v < 90 -> "60-90"
                v < 120 -> "90-120"
                else -> ">120"
            }
            speedBands[band] = speedBands.getValue(band) + ms / 60_000.0
        }

        val rpmBands = linkedMapOf("off" to 0.0, "<1000" to 0.0, "1000-2000" to 0.0, "2000-3000" to 0.0, "3000-4000" to 0.0, ">4000" to 0.0)
        forEachHeld(rpm, end) { v, ms ->
            val band = when {
                v < 400 -> "off"
                v < 1000 -> "<1000"
                v < 2000 -> "1000-2000"
                v < 3000 -> "2000-3000"
                v < 4000 -> "3000-4000"
                else -> ">4000"
            }
            rpmBands[band] = rpmBands.getValue(band) + ms / 60_000.0
        }

        val coolantStart = coolant.firstOrNull()
        val warm = coolant.firstOrNull { it.value >= WARM_COOLANT_C }
        val warmUpMinutes = if (coolantStart != null && warm != null && coolantStart.value < WARM_COOLANT_C) {
            (warm.tMs - coolantStart.tMs) / 60_000.0
        } else {
            null
        }

        var chargingSum = 0.0
        var chargingCount = 0
        var restingMin: Double? = null
        for (p in volts) {
            val r = valueAt(rpm, p.tMs) ?: continue
            if (r > 800) {
                chargingSum += p.value
                chargingCount++
            } else if (r < 400) {
                restingMin = min(restingMin ?: p.value, p.value)
            }
        }

        return TripInsights(
            speedBandMinutes = speedBands,
            rpmBandMinutes = rpmBands,
            warmUpMinutesTo80C = warmUpMinutes,
            chargingVoltageMean = if (chargingCount > 0) chargingSum / chargingCount else null,
            restingVoltageMin = restingMin,
            elevation = elevationInsights(trip.id, end, speed, rpm),
        )
    }

    /**
     * Elevation profile and how the engine behaved on each kind of slope, or null without GPS.
     * Per-band engine figures are time-weighted samples of the step-hold series at each grade point.
     */
    private suspend fun elevationInsights(tripId: Long, endMs: Int, speed: List<Point>, rpm: List<Point>): ElevationInsights? {
        val ids = listOf(PidCatalog.ID_GPS_ALTITUDE, PidCatalog.ID_ROAD_GRADE, PidCatalog.ID_ENGINE_LOAD, PidCatalog.ID_FUEL_RATE, PidCatalog.ID_MAF)
        val s = series(tripId, ids, 0, endMs + 1)
        val altitude = s[PidCatalog.ID_GPS_ALTITUDE].orEmpty()
        if (altitude.isEmpty()) return null
        val grade = s[PidCatalog.ID_ROAD_GRADE].orEmpty()
        val load = s[PidCatalog.ID_ENGINE_LOAD].orEmpty()
        val fuelRate = s[PidCatalog.ID_FUEL_RATE].orEmpty()
        val maf = s[PidCatalog.ID_MAF].orEmpty()

        // Climb/descent with hysteresis, so GPS noise does not add up to phantom metres.
        var gain = 0.0
        var loss = 0.0
        var anchor = altitude.first().value
        for (p in altitude) {
            val delta = p.value - anchor
            if (delta >= ELEVATION_HYSTERESIS_M) {
                gain += delta
                anchor = p.value
            } else if (delta <= -ELEVATION_HYSTERESIS_M) {
                loss -= delta
                anchor = p.value
            }
        }

        val bands = linkedMapOf<String, BandAccumulator>()
        GRADE_BANDS.forEach { (name, _) -> bands[name] = BandAccumulator() }
        for (i in grade.indices) {
            val p = grade[i]
            val next = if (i + 1 < grade.size) grade[i + 1].tMs else endMs
            val heldMs = min(next - p.tMs, HOLD_MS)
            if (heldMs <= 0) continue
            val band = GRADE_BANDS.first { (_, upper) -> p.value < upper }.first
            bands.getValue(band).add(
                ms = heldMs,
                load = valueAt(load, p.tMs),
                rpm = valueAt(rpm, p.tMs),
                fuel = valueAt(fuelRate, p.tMs),
                maf = valueAt(maf, p.tMs),
                speed = valueAt(speed, p.tMs),
            )
        }

        return ElevationInsights(
            minAltitudeM = altitude.minOf { it.value },
            maxAltitudeM = altitude.maxOf { it.value },
            climbM = gain,
            descentM = loss,
            gradeBands = bands.mapValues { (_, acc) -> acc.result() },
        )
    }

    private class BandAccumulator {
        private var ms = 0L
        private val sums = DoubleArray(5)
        private val weights = LongArray(5)

        fun add(ms: Int, load: Double?, rpm: Double?, fuel: Double?, maf: Double?, speed: Double?) {
            this.ms += ms
            listOf(load, rpm, fuel, maf, speed).forEachIndexed { i, v ->
                if (v != null) {
                    sums[i] += v * ms
                    weights[i] += ms.toLong()
                }
            }
        }

        private fun mean(i: Int) = if (weights[i] > 0) sums[i] / weights[i] else null

        fun result() = GradeBand(
            minutes = ms / 60_000.0,
            meanEngineLoadPct = mean(0),
            meanRpm = mean(1),
            meanFuelRateLh = mean(2),
            meanMafGs = mean(3),
            meanSpeedKmh = mean(4),
        )
    }

    /** Calls [block] with each held value and how long it was held (capped by [HOLD_MS] across gaps). */
    private inline fun forEachHeld(points: List<Point>, endMs: Int, block: (Double, Int) -> Unit) {
        for (i in points.indices) {
            val next = if (i + 1 < points.size) points[i + 1].tMs else endMs
            val held = min(next - points[i].tMs, HOLD_MS)
            if (held > 0) block(points[i].value, held)
        }
    }

    data class TripInsights(
        val speedBandMinutes: Map<String, Double>,
        val rpmBandMinutes: Map<String, Double>,
        val warmUpMinutesTo80C: Double?,
        val chargingVoltageMean: Double?,
        val restingVoltageMin: Double?,
        val elevation: ElevationInsights?,
    )

    data class ElevationInsights(
        val minAltitudeM: Double,
        val maxAltitudeM: Double,
        val climbM: Double,
        val descentM: Double,
        val gradeBands: Map<String, GradeBand>,
    )

    data class GradeBand(
        val minutes: Double,
        val meanEngineLoadPct: Double?,
        val meanRpm: Double?,
        val meanFuelRateLh: Double?,
        val meanMafGs: Double?,
        val meanSpeedKmh: Double?,
    )

    companion object {
        /** A stored value is held at most this long; beyond it the signal is treated as missing. */
        const val HOLD_MS = TelemetryRecorder.HEARTBEAT_MS + 5_000
        private const val WARM_COOLANT_C = 80.0
        private const val ELEVATION_HYSTERESIS_M = 3.0

        /** Band name to exclusive upper bound of road grade in percent. */
        private val GRADE_BANDS = listOf(
            "steep_descent_below_-6" to -6.0,
            "descent_-6_to_-2" to -2.0,
            "flat_-2_to_2" to 2.0,
            "climb_2_to_6" to 6.0,
            "steep_climb_above_6" to Double.POSITIVE_INFINITY,
        )

        /** Step-hold value at [tMs], or null if the last point is older than [HOLD_MS]. */
        fun valueAt(points: List<Point>, tMs: Int): Double? {
            var lo = 0
            var hi = points.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (points[mid].tMs <= tMs) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            if (found < 0) return null
            val p = points[found]
            return if (tMs - p.tMs <= HOLD_MS) p.value else null
        }

        /**
         * Resamples step-hold [points] into [buckets] equal buckets over `[fromMs, toMs)`.
         * A bucket aggregates the points inside it, or carries the held value forward.
         */
        fun resample(points: List<Point>, fromMs: Int, toMs: Int, buckets: Int, stat: String): List<Double?> {
            val width = (toMs - fromMs).toDouble() / buckets
            val out = ArrayList<Double?>(buckets)
            var i = 0
            var held: Point? = null
            while (i < points.size && points[i].tMs < fromMs) held = points[i++]
            for (b in 0 until buckets) {
                val start = fromMs + b * width
                val end = start + width
                var n = 0
                var sum = 0.0
                var lo = Double.POSITIVE_INFINITY
                var hi = Double.NEGATIVE_INFINITY
                while (i < points.size && points[i].tMs < end) {
                    val v = points[i].value
                    n++
                    sum += v
                    lo = min(lo, v)
                    hi = max(hi, v)
                    held = points[i++]
                }
                out += when {
                    n > 0 -> when (stat) {
                        "min" -> lo
                        "max" -> hi
                        else -> sum / n
                    }
                    else -> held?.takeIf { start - it.tMs <= HOLD_MS }?.value
                }
            }
            return out
        }
    }
}
