package com.example.agenticcar.data.recording

import com.example.agenticcar.protocol.obd.PidCatalog
import kotlin.math.abs

/**
 * Drops corrupt readings before they reach storage or the dashboard.
 *
 * - A value outside its signal's physically possible [Rule.range] is always dropped.
 * - A value further than [Rule.maxStep] from the last accepted one is held back. The next reading
 *   decides: if it stayed nearer the held value than the old level, the level really moved and that
 *   reading is accepted; if it fell back, the held value was a one-sample blip and is never recorded.
 *
 * A real step change therefore loses one sample (one poll period of delay), and an extreme ramp at
 * most every other sample, while blips such as 746 → 2350 → 746 RPM at a standstill are removed.
 * Signals without a rule pass through untouched.
 */
class SpikeFilter(private val rules: Map<Int, Rule>) {

    /** [maxStep]: largest believable change between consecutive polls; [range]: values the signal can take. */
    data class Rule(
        val maxStep: Double,
        val range: ClosedFloatingPointRange<Double> = Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY,
    )

    private val accepted = HashMap<Int, Double>()
    private val candidate = HashMap<Int, Double>()

    /** Returns true if [value] should be recorded. */
    fun accept(signalId: Int, value: Double): Boolean {
        val rule = rules[signalId] ?: return true
        if (value !in rule.range) return false
        val last = accepted[signalId]
        val pending = candidate[signalId]
        val confirmed = last == null ||
            abs(value - last) <= rule.maxStep ||
            // The previous jump was followed rather than undone: the level really moved.
            (pending != null && abs(value - pending) < abs(value - last))
        if (confirmed) {
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

    companion object {
        /**
         * Rules for OBD readings polled ~0.4 s apart, also applied when re-reading trips stored before
         * they existed. 800 rpm per poll is ~2000 rpm/s, faster than a car pulls in gear; a real blip
         * in neutral loses one sample.
         */
        val OBD_RULES: Map<Int, Rule> = mapOf(
            PidCatalog.ID_RPM to Rule(maxStep = 800.0, range = 0.0..8_000.0),
            PidCatalog.ID_SPEED to Rule(maxStep = 30.0, range = 0.0..300.0),
            PidCatalog.ID_TIMING_ADVANCE to Rule(maxStep = 25.0),
            PidCatalog.ID_COOLANT to Rule(maxStep = 15.0),
            PidCatalog.ID_INTAKE_AIR_TEMP to Rule(maxStep = 15.0),
        )
    }
}
