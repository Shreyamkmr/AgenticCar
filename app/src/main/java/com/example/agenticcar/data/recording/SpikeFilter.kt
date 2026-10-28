package com.example.agenticcar.data.recording

import kotlin.math.abs

/**
 * Drops single-sample spikes: a reading that jumps further than its signal's threshold from the
 * last accepted value is held back, and only accepted if the next reading confirms it.
 *
 * A real step change therefore loses one sample (one poll period of delay); a corrupt reading,
 * such as 931 → 4366 → 1274 RPM at a steady road speed, never reaches storage. Signals without a
 * threshold pass through untouched.
 */
class SpikeFilter(private val thresholds: Map<Int, Double>) {
    private val accepted = HashMap<Int, Double>()
    private val candidate = HashMap<Int, Double>()

    /** Returns true if [value] should be recorded. */
    fun accept(signalId: Int, value: Double): Boolean {
        val threshold = thresholds[signalId] ?: return true
        val last = accepted[signalId]
        if (last == null || abs(value - last) <= threshold) {
            accepted[signalId] = value
            candidate.remove(signalId)
            return true
        }
        val pending = candidate[signalId]
        if (pending != null && abs(value - pending) <= threshold) {
            // Two consecutive readings agree on the new level: it is real.
            accepted[signalId] = value
            candidate.remove(signalId)
            return true
        }
        candidate[signalId] = value
        return false
    }

    fun reset() {
        accepted.clear()
        candidate.clear()
    }
}
