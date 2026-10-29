package com.example.agenticcar.ui.board

import com.example.agenticcar.data.Sample
import kotlin.math.max
import kotlin.math.min

/**
 * What a tile shows. Live: [value] is the latest reading and [series] the recent window.
 * Analysis: [value] is the mean over the selection and [min]/[max] its extremes.
 */
data class TileData(
    val value: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val mean: Double? = null,
    val series: List<Sample> = emptyList(),
    /** Time axis of a trend; null fits it to [series]. */
    val from: Long? = null,
    val to: Long? = null,
    /** GPS track for [TileType.ROUTE] tiles, coloured by the tile's signal. */
    val route: List<RoutePoint> = emptyList(),
) {
    val observedRange: ClosedFloatingPointRange<Double>?
        get() = if (min != null && max != null) min..max else null

    companion object {
        val EMPTY = TileData()
    }
}

/** One GPS fix of a route; [value] is the colouring signal at that moment (NaN when unknown). */
data class RoutePoint(val t: Long, val lat: Double, val lon: Double, val value: Double, val breakBefore: Boolean = false)

object SeriesTools {

    /**
     * Pairs every latitude fix with its longitude and the colouring signal (step-hold, at most
     * [holdMs] old). A pause longer than [maxGapMs] between fixes starts a new line, so separate
     * trips or a lost GPS fix are not joined by a straight jump. Keeps at most about [maxPoints].
     */
    fun route(lat: List<Sample>, lon: List<Sample>, color: List<Sample>, holdMs: Long, maxGapMs: Long, maxPoints: Int): List<RoutePoint> {
        if (lat.isEmpty() || lon.isEmpty()) return emptyList()
        val out = ArrayList<RoutePoint>(minOf(lat.size, maxPoints + 1))
        val step = (lat.size + maxPoints - 1) / maxPoints
        var lonIndex = 0
        var colorIndex = 0
        var prevFixT: Long? = null
        var pendingBreak = false
        for ((i, fix) in lat.withIndex()) {
            while (lonIndex + 1 < lon.size && lon[lonIndex + 1].t <= fix.t) lonIndex++
            val lonSample = lon[lonIndex].takeIf { it.t <= fix.t && fix.t - it.t <= LAT_LON_SKEW_MS } ?: continue
            // Gaps are measured between consecutive fixes, before thinning.
            if (prevFixT != null && fix.t - prevFixT > maxGapMs) pendingBreak = true
            prevFixT = fix.t
            // Thin long tracks, but never drop the fix that starts a new line or the last one.
            if (!pendingBreak && i % step != 0 && i != lat.lastIndex) continue
            while (colorIndex + 1 < color.size && color[colorIndex + 1].t <= fix.t) colorIndex++
            val c = color.getOrNull(colorIndex)?.takeIf { it.t <= fix.t && fix.t - it.t <= holdMs }?.v ?: Double.NaN
            out += RoutePoint(fix.t, fix.v, lonSample.v, c, breakBefore = pendingBreak && out.isNotEmpty())
            pendingBreak = false
        }
        return out
    }

    /** Latitude and longitude are recorded together; this only tolerates their storage timestamps differing slightly. */
    private const val LAT_LON_SKEW_MS = 2_000L

    fun finite(samples: List<Sample>) = samples.filter { it.v.isFinite() }

    /** Min, max and plain mean of the finite values, or nulls when there are none. */
    fun summarize(samples: List<Sample>, value: Double?): TileData {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var n = 0
        for (s in samples) {
            if (!s.v.isFinite()) continue
            lo = min(lo, s.v)
            hi = max(hi, s.v)
            sum += s.v
            n++
        }
        return if (n == 0) {
            TileData(value = value, series = samples)
        } else {
            TileData(value = value, min = lo, max = hi, mean = sum / n, series = samples)
        }
    }

    /**
     * Like [summarize], but each stored sample counts for as long as it was held (until the next
     * sample, at most [holdMs]). Deadband storage keeps steady stretches sparse and changes dense,
     * so a plain mean would over-weight the busy moments. [value] becomes the time-weighted mean.
     */
    fun summarizeHeld(samples: List<Sample>, holdMs: Long): TileData {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var weighted = 0.0
        var weight = 0L
        for (i in samples.indices) {
            val s = samples[i]
            if (!s.v.isFinite()) continue
            lo = min(lo, s.v)
            hi = max(hi, s.v)
            val next = samples.getOrNull(i + 1)?.t ?: s.t
            val held = (next - s.t).coerceIn(0, holdMs).coerceAtLeast(1)
            weighted += s.v * held
            weight += held
        }
        if (weight == 0L) return TileData(series = samples)
        val mean = weighted / weight
        return TileData(value = mean, min = lo, max = hi, mean = mean, series = samples)
    }

    /**
     * Shrinks [samples] to about 2 × [buckets] points, keeping each bucket's minimum and maximum
     * in time order so spikes and dips survive. Line breaks (NaN) are kept.
     */
    fun decimate(samples: List<Sample>, buckets: Int): List<Sample> {
        if (samples.size <= buckets * 2) return samples
        val from = samples.first().t
        val span = (samples.last().t - from).coerceAtLeast(1)
        val out = ArrayList<Sample>(buckets * 2 + 16)
        var i = 0
        for (b in 0 until buckets) {
            val end = from + span * (b + 1) / buckets
            var lo: Sample? = null
            var hi: Sample? = null
            var gap = false
            while (i < samples.size && (samples[i].t <= end || b == buckets - 1)) {
                val s = samples[i++]
                if (!s.v.isFinite()) {
                    gap = true
                    continue
                }
                if (lo == null || s.v < lo.v) lo = s
                if (hi == null || s.v > hi.v) hi = s
            }
            if (gap) out += Sample(lo?.t ?: end, Double.NaN)
            when {
                lo == null || hi == null -> Unit
                lo === hi -> out += lo
                lo.t <= hi.t -> { out += lo; out += hi }
                else -> { out += hi; out += lo }
            }
        }
        return out
    }

    /**
     * Shifts time so that every gap longer than [maxGapMs] (between trips, or a lost connection)
     * shrinks to [maxGapMs] and gets a line break; several trips then share one chart without
     * hours of empty space between them.
     */
    fun collapseGaps(samples: List<Sample>, maxGapMs: Long): List<Sample> {
        if (samples.isEmpty()) return samples
        val out = ArrayList<Sample>(samples.size + 8)
        var shift = 0L
        var prev = samples.first().t
        for (s in samples) {
            val gap = s.t - prev
            if (gap > maxGapMs) {
                out += Sample(prev - shift + 1, Double.NaN)
                shift += gap - maxGapMs
            }
            out += Sample(s.t - shift, s.v)
            prev = s.t
        }
        return out
    }
}
