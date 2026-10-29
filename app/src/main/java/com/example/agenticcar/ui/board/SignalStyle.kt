package com.example.agenticcar.ui.board

import com.example.agenticcar.protocol.obd.PidCatalog
import com.example.agenticcar.protocol.obd.SignalDef
import java.util.Locale

/** How a signal is labelled, ranged and formatted on a tile. */
object SignalStyle {

    private val shortLabels = mapOf(
        "rpm" to "RPM",
        "speed" to "Speed",
        "coolant_temp" to "Coolant",
        "engine_load" to "Load",
        "throttle_pos" to "Throttle",
        "intake_map" to "MAP",
        "intake_air_temp" to "Intake air",
        "timing_advance" to "Timing",
        "stft_b1" to "Short trim",
        "ltft_b1" to "Long trim",
        "stft_b2" to "Short trim B2",
        "ltft_b2" to "Long trim B2",
        "maf" to "MAF",
        "fuel_level" to "Fuel level",
        "fuel_rate" to "Fuel rate",
        "fuel_rate_est" to "Fuel rate (est.)",
        "adapter_voltage" to "Battery",
        "module_voltage" to "ECU voltage",
        "mil_on" to "Check engine",
        "dtc_count" to "Fault codes",
        "run_time" to "Run time",
        "gps_altitude" to "Altitude",
        "gps_speed" to "GPS speed",
        "gps_bearing" to "Heading",
        "gps_accuracy" to "GPS accuracy",
        "road_grade" to "Road grade",
        "ambient_temp" to "Outside air",
        "baro_pressure" to "Barometer",
    )

    /** Display ranges for gauges and bars; anything else auto-ranges from what it has seen. */
    private val ranges = mapOf(
        "rpm" to (0.0 to 7_000.0),
        "speed" to (0.0 to 200.0),
        "gps_speed" to (0.0 to 200.0),
        "coolant_temp" to (40.0 to 120.0),
        "engine_load" to (0.0 to 100.0),
        "throttle_pos" to (0.0 to 100.0),
        "intake_map" to (0.0 to 120.0),
        "intake_air_temp" to (-10.0 to 70.0),
        "timing_advance" to (-10.0 to 50.0),
        "stft_b1" to (-25.0 to 25.0),
        "ltft_b1" to (-25.0 to 25.0),
        "stft_b2" to (-25.0 to 25.0),
        "ltft_b2" to (-25.0 to 25.0),
        "maf" to (0.0 to 100.0),
        "fuel_level" to (0.0 to 100.0),
        "fuel_rate" to (0.0 to 20.0),
        "fuel_rate_est" to (0.0 to 20.0),
        "adapter_voltage" to (11.0 to 15.0),
        "module_voltage" to (11.0 to 15.0),
        "road_grade" to (-15.0 to 15.0),
        "mil_on" to (0.0 to 1.0),
    )

    /** Default level at which a [TileType.LAMP] lights. */
    private val lampThresholds = mapOf(
        "mil_on" to 1.0,
        "dtc_count" to 1.0,
        "coolant_temp" to 105.0,
        "rpm" to 5_000.0,
        "engine_load" to 90.0,
        "adapter_voltage" to 15.0,
    )

    fun def(signalId: Int): SignalDef? = PidCatalog.signal(signalId)

    fun label(signalId: Int): String {
        val def = def(signalId) ?: return "Signal %04X".format(signalId)
        return shortLabels[def.key] ?: def.name.substringBefore(" (").substringBefore(",")
    }

    fun unit(signalId: Int): String = def(signalId)?.unit?.takeUnless { it in setOf("bool", "code", "count", "bitmask", "raw") }.orEmpty()

    /** Display range for [tile]: the tile's override, else the signal default, else [observed] padded. */
    fun range(tile: Tile, observed: ClosedFloatingPointRange<Double>? = null): ClosedFloatingPointRange<Double> {
        val default = def(tile.signalId)?.key?.let(ranges::get)
        var lo = tile.min ?: default?.first ?: observed?.start ?: 0.0
        var hi = tile.max ?: default?.second ?: observed?.endInclusive ?: 100.0
        if (tile.min == null && default == null && observed != null) {
            val pad = (observed.endInclusive - observed.start).coerceAtLeast(1.0) * 0.1
            lo = observed.start - pad
            hi = observed.endInclusive + pad
        }
        if (hi <= lo) hi = lo + 1.0
        return lo..hi
    }

    fun lampThreshold(tile: Tile): Double = tile.max ?: def(tile.signalId)?.key?.let(lampThresholds::get) ?: 1.0

    fun format(signalId: Int, value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        val resolution = def(signalId)?.resolution ?: 1.0
        val pattern = when {
            resolution >= 1.0 || kotlin.math.abs(value) >= 1_000 -> "%.0f"
            resolution >= 0.1 -> "%.1f"
            resolution >= 0.01 -> "%.2f"
            else -> "%.5f"
        }
        return String.format(Locale.ROOT, pattern, value)
    }
}
