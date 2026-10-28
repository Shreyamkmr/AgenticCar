package com.example.agenticcar.protocol.obd

/** How often a PID is polled. Fast-changing driver inputs get the bandwidth; constants are read rarely. */
enum class PollTier(val periodMillis: Long) {
    FAST(250L),
    MEDIUM(1_000L),
    SLOW(5_000L),
    STATIC(300_000L),
}

/**
 * One decoded quantity.
 *
 * [resolution] is the signal's native OBD step: values are stored as integers
 * `round(value / resolution)`, which is lossless for every linear PID.
 * [deadband] (same unit) suppresses storing changes no larger than it.
 */
data class SignalDef(
    val id: Int,
    val key: String,
    val name: String,
    val unit: String,
    val resolution: Double,
    val deadband: Double = 0.0,
)

/** A Mode 01 PID: [dataBytes] after the PID byte, decoded into one value per entry of [signals]. */
class PidDef(
    val pid: Int,
    val dataBytes: Int,
    val tier: PollTier,
    val signals: List<SignalDef>,
    val decode: (IntArray) -> List<Double?>,
)

/**
 * SAE J1979 Mode 01 PID definitions.
 *
 * Signal ids: the PID itself for the primary value, `0x100 + pid` for a second
 * value packed in the same PID, [ADAPTER_VOLTAGE_ID] for `AT RV`, and
 * `RAW_ID_BASE + pid` for supported PIDs this catalog cannot decode.
 */
object PidCatalog {
    const val ADAPTER_VOLTAGE_ID = 0x1001
    const val RAW_ID_BASE = 0x2000

    // Well-known signal ids used by trip detection and analytics.
    const val ID_MIL = 0x01
    const val ID_DTC_COUNT = 0x101
    const val ID_ENGINE_LOAD = 0x04
    const val ID_COOLANT = 0x05
    const val ID_INTAKE_MAP = 0x0B
    const val ID_RPM = 0x0C
    const val ID_SPEED = 0x0D
    const val ID_TIMING_ADVANCE = 0x0E
    const val ID_INTAKE_AIR_TEMP = 0x0F
    const val ID_MAF = 0x10
    const val ID_MODULE_VOLTAGE = 0x42
    const val ID_FUEL_TYPE = 0x51
    const val ID_FUEL_RATE = 0x5E

    private const val PCT = 100.0 / 255.0
    private const val TRIM = 100.0 / 128.0
    private const val LAMBDA = 2.0 / 65536.0

    val adapterVoltage = SignalDef(
        ADAPTER_VOLTAGE_ID, "adapter_voltage", "Battery voltage at the OBD port (ELM327 AT RV)", "V", 0.1, 0.1,
    )

    // Phone GPS, recorded alongside the ECU signals at ~1 Hz.
    const val ID_GPS_LATITUDE = 0x3001
    const val ID_GPS_LONGITUDE = 0x3002
    const val ID_GPS_ALTITUDE = 0x3003
    const val ID_GPS_SPEED = 0x3004
    const val ID_GPS_BEARING = 0x3005
    const val ID_GPS_ACCURACY = 0x3006
    const val ID_ROAD_GRADE = 0x3007

    val gpsSignals = listOf(
        // 1e-6 degrees is ~0.1 m; deltas between 1 Hz fixes stay small, so this costs ~2 bytes per fix.
        SignalDef(ID_GPS_LATITUDE, "gps_latitude", "GPS latitude", "°", 0.000001),
        SignalDef(ID_GPS_LONGITUDE, "gps_longitude", "GPS longitude", "°", 0.000001),
        SignalDef(ID_GPS_ALTITUDE, "gps_altitude", "Altitude above sea level (GPS, ±5-15 m)", "m", 0.1, 1.0),
        SignalDef(ID_GPS_SPEED, "gps_speed", "GPS ground speed", "km/h", 0.1, 0.5),
        SignalDef(ID_GPS_BEARING, "gps_bearing", "GPS heading (0 = north, clockwise)", "°", 1.0, 3.0),
        SignalDef(ID_GPS_ACCURACY, "gps_accuracy", "GPS horizontal accuracy (68 % radius)", "m", 1.0, 2.0),
        SignalDef(ID_ROAD_GRADE, "road_grade", "Road grade from smoothed GPS altitude over 100 m (+ = uphill)", "%", 0.1, 0.3),
    )

    /** Computed by the app, never read from the car. */
    const val ID_FUEL_RATE_EST = 0x3010

    val derivedSignals = listOf(
        SignalDef(
            ID_FUEL_RATE_EST, "fuel_rate_est",
            "Estimated fuel rate for cars without MAF or fuel-rate PIDs: speed-density from MAP, intake air " +
                "temperature, RPM and the configured engine size, 85 % volumetric efficiency, petrol at lambda 1 (±15 %)",
            "L/h", 0.05, 0.1,
        ),
    )

    private val defs = ArrayList<PidDef>()

    init {
        multi(0x01, 4, PollTier.SLOW,
            SignalDef(ID_MIL, "mil_on", "Check-engine light (MIL) on, 1 = on", "bool", 1.0),
            SignalDef(ID_DTC_COUNT, "dtc_count", "Confirmed emission-related DTC count", "count", 1.0),
        ) { b -> listOf(if (b[0] and 0x80 != 0) 1.0 else 0.0, (b[0] and 0x7F).toDouble()) }
        multi(0x03, 2, PollTier.MEDIUM,
            SignalDef(0x03, "fuel_system_1_status", "Fuel system 1 status (1 open loop cold, 2 closed loop, 4 open loop load, 8 open loop fault, 16 closed loop fault)", "code", 1.0),
            SignalDef(0x103, "fuel_system_2_status", "Fuel system 2 status (same codes)", "code", 1.0),
        ) { b -> listOf(b[0].toDouble(), b[1].toDouble()) }

        linear(0x04, 1, PollTier.FAST, "engine_load", "Calculated engine load", "%", PCT, deadband = 1.0)
        linear(0x05, 1, PollTier.MEDIUM, "coolant_temp", "Engine coolant temperature", "°C", 1.0, -40.0)
        linear(0x06, 1, PollTier.MEDIUM, "stft_b1", "Short-term fuel trim, bank 1", "%", TRIM, -100.0, 1.5)
        linear(0x07, 1, PollTier.MEDIUM, "ltft_b1", "Long-term fuel trim, bank 1", "%", TRIM, -100.0)
        linear(0x08, 1, PollTier.MEDIUM, "stft_b2", "Short-term fuel trim, bank 2", "%", TRIM, -100.0, 1.5)
        linear(0x09, 1, PollTier.MEDIUM, "ltft_b2", "Long-term fuel trim, bank 2", "%", TRIM, -100.0)
        linear(0x0A, 1, PollTier.SLOW, "fuel_pressure", "Fuel pressure (gauge)", "kPa", 3.0)
        linear(0x0B, 1, PollTier.FAST, "intake_map", "Intake manifold absolute pressure", "kPa", 1.0)
        linear(0x0C, 2, PollTier.FAST, "rpm", "Engine speed", "rpm", 0.25, deadband = 20.0)
        linear(0x0D, 1, PollTier.FAST, "speed", "Vehicle speed", "km/h", 1.0)
        linear(0x0E, 1, PollTier.FAST, "timing_advance", "Ignition timing advance, cylinder 1", "°", 0.5, -64.0, 1.0)
        linear(0x0F, 1, PollTier.MEDIUM, "intake_air_temp", "Intake air temperature", "°C", 1.0, -40.0)
        linear(0x10, 2, PollTier.FAST, "maf", "Mass air flow rate", "g/s", 0.01, deadband = 0.2)
        linear(0x11, 1, PollTier.FAST, "throttle_pos", "Absolute throttle position", "%", PCT, deadband = 0.8)
        linear(0x13, 1, PollTier.STATIC, "o2_sensors_present", "O2 sensors present (bitmask, 2 banks)", "bitmask", 1.0)
        linear(0x1C, 1, PollTier.STATIC, "obd_standard", "OBD standard this vehicle conforms to (code)", "code", 1.0)
        linear(0x1D, 1, PollTier.STATIC, "o2_sensors_present_4bank", "O2 sensors present (bitmask, 4 banks)", "bitmask", 1.0)
        linear(0x1F, 2, PollTier.SLOW, "run_time", "Run time since engine start", "s", 1.0, deadband = 4.0)
        linear(0x21, 2, PollTier.STATIC, "distance_with_mil", "Distance travelled with MIL on", "km", 1.0)
        linear(0x22, 2, PollTier.MEDIUM, "fuel_rail_pressure_rel", "Fuel rail pressure relative to manifold vacuum", "kPa", 0.079, deadband = 1.0)
        linear(0x23, 2, PollTier.MEDIUM, "fuel_rail_pressure", "Fuel rail gauge pressure (diesel / direct injection)", "kPa", 10.0, deadband = 100.0)
        linear(0x2C, 1, PollTier.MEDIUM, "commanded_egr", "Commanded EGR", "%", PCT, deadband = 1.0)
        linear(0x2D, 1, PollTier.MEDIUM, "egr_error", "EGR error", "%", TRIM, -100.0, 1.5)
        linear(0x2E, 1, PollTier.MEDIUM, "evap_purge", "Commanded evaporative purge", "%", PCT, deadband = 1.0)
        linear(0x2F, 1, PollTier.SLOW, "fuel_level", "Fuel tank level input", "%", PCT, deadband = 1.0)
        linear(0x30, 1, PollTier.STATIC, "warmups_since_clear", "Warm-ups since codes cleared", "count", 1.0)
        linear(0x31, 2, PollTier.STATIC, "distance_since_clear", "Distance travelled since codes cleared", "km", 1.0)
        multi(0x32, 2, PollTier.SLOW,
            SignalDef(0x32, "evap_vapor_pressure", "Evap system vapor pressure", "Pa", 0.25, 5.0),
        ) { b -> listOf(((b[0] shl 8) or b[1]).toShort() / 4.0) }
        linear(0x33, 1, PollTier.SLOW, "baro_pressure", "Absolute barometric pressure", "kPa", 1.0)
        listOf("b1s1", "b2s1", "b1s2", "b2s2").forEachIndexed { i, pos ->
            linear(0x3C + i, 2, PollTier.SLOW, "catalyst_temp_$pos", "Catalyst temperature ${pos.uppercase()}", "°C", 0.1, -40.0, 5.0)
        }
        linear(0x42, 2, PollTier.MEDIUM, "module_voltage", "Control module voltage", "V", 0.001, deadband = 0.05)
        linear(0x43, 2, PollTier.FAST, "absolute_load", "Absolute load value", "%", PCT, deadband = 2.0)
        linear(0x44, 2, PollTier.MEDIUM, "commanded_lambda", "Commanded air-fuel equivalence ratio", "λ", LAMBDA, deadband = 0.005)
        linear(0x45, 1, PollTier.MEDIUM, "relative_throttle", "Relative throttle position", "%", PCT, deadband = 0.8)
        linear(0x46, 1, PollTier.SLOW, "ambient_temp", "Ambient air temperature", "°C", 1.0, -40.0)
        linear(0x47, 1, PollTier.MEDIUM, "throttle_pos_b", "Absolute throttle position B", "%", PCT, deadband = 0.8)
        linear(0x48, 1, PollTier.MEDIUM, "throttle_pos_c", "Absolute throttle position C", "%", PCT, deadband = 0.8)
        linear(0x49, 1, PollTier.FAST, "accel_pedal_d", "Accelerator pedal position D", "%", PCT, deadband = 0.8)
        linear(0x4A, 1, PollTier.FAST, "accel_pedal_e", "Accelerator pedal position E", "%", PCT, deadband = 0.8)
        linear(0x4B, 1, PollTier.MEDIUM, "accel_pedal_f", "Accelerator pedal position F", "%", PCT, deadband = 0.8)
        linear(0x4C, 1, PollTier.MEDIUM, "commanded_throttle", "Commanded throttle actuator", "%", PCT, deadband = 0.8)
        linear(0x4D, 2, PollTier.STATIC, "time_with_mil", "Time run with MIL on", "min", 1.0)
        linear(0x4E, 2, PollTier.STATIC, "time_since_clear", "Time since trouble codes cleared", "min", 1.0)
        linear(0x51, 1, PollTier.STATIC, "fuel_type", "Fuel type code (1 petrol, 2 methanol, 3 ethanol, 4 diesel, 5 LPG, 6 CNG, 8 electric, 9+ bifuel/hybrid)", "code", 1.0)
        linear(0x52, 1, PollTier.STATIC, "ethanol_pct", "Ethanol fuel percentage", "%", PCT)
        linear(0x53, 2, PollTier.SLOW, "evap_abs_pressure", "Absolute evap system vapor pressure", "kPa", 0.005, deadband = 0.05)
        linear(0x54, 2, PollTier.SLOW, "evap_pressure", "Evap system vapor pressure (wide range)", "Pa", 1.0, -32767.0, 5.0)
        dualTrim(0x55, "stft_secondary_b1", "stft_secondary_b3", "Short-term secondary O2 trim", "bank 1", "bank 3")
        dualTrim(0x56, "ltft_secondary_b1", "ltft_secondary_b3", "Long-term secondary O2 trim", "bank 1", "bank 3")
        dualTrim(0x57, "stft_secondary_b2", "stft_secondary_b4", "Short-term secondary O2 trim", "bank 2", "bank 4")
        dualTrim(0x58, "ltft_secondary_b2", "ltft_secondary_b4", "Long-term secondary O2 trim", "bank 2", "bank 4")
        linear(0x59, 2, PollTier.MEDIUM, "fuel_rail_abs_pressure", "Fuel rail absolute pressure", "kPa", 10.0, deadband = 100.0)
        linear(0x5A, 1, PollTier.FAST, "relative_pedal", "Relative accelerator pedal position", "%", PCT, deadband = 0.8)
        linear(0x5B, 1, PollTier.SLOW, "hybrid_battery_pct", "Hybrid battery pack remaining life", "%", PCT)
        linear(0x5C, 1, PollTier.MEDIUM, "oil_temp", "Engine oil temperature", "°C", 1.0, -40.0)
        linear(0x5D, 2, PollTier.MEDIUM, "injection_timing", "Fuel injection timing", "°", 1.0 / 128.0, -210.0, 0.5)
        linear(0x5E, 2, PollTier.FAST, "fuel_rate", "Engine fuel rate", "L/h", 0.05, deadband = 0.1)
        linear(0x5F, 1, PollTier.STATIC, "emission_standard", "Emission requirements the vehicle is designed to (code)", "code", 1.0)
        linear(0x61, 1, PollTier.FAST, "driver_demand_torque", "Driver's demand engine torque", "%", 1.0, -125.0, 1.0)
        linear(0x62, 1, PollTier.FAST, "actual_torque", "Actual engine torque", "%", 1.0, -125.0, 1.0)
        linear(0x63, 2, PollTier.STATIC, "reference_torque", "Engine reference torque", "Nm", 1.0)
        linear(0xA6, 4, PollTier.SLOW, "odometer", "Odometer", "km", 0.1)

        // O2 sensors: narrowband 0x14-0x1B, wideband voltage type 0x24-0x2B, wideband current type 0x34-0x3B.
        listOf("b1s1", "b1s2", "b1s3", "b1s4", "b2s1", "b2s2", "b2s3", "b2s4").forEachIndexed { i, pos ->
            val label = pos.uppercase()
            multi(0x14 + i, 2, PollTier.MEDIUM,
                SignalDef(0x14 + i, "o2_${pos}_voltage", "O2 sensor $label voltage", "V", 0.005, 0.05),
                SignalDef(0x114 + i, "o2_${pos}_stft", "O2 sensor $label short-term fuel trim", "%", TRIM, 1.5),
            ) { b -> listOf(b[0] * 0.005, if (b[1] == 0xFF) null else (b[1] - 128) * TRIM) }
            multi(0x24 + i, 4, PollTier.MEDIUM,
                SignalDef(0x24 + i, "o2_${pos}_lambda", "Wideband O2 sensor $label equivalence ratio", "λ", LAMBDA, 0.005),
                SignalDef(0x124 + i, "o2_${pos}_wb_voltage", "Wideband O2 sensor $label voltage", "V", 8.0 / 65536.0, 0.02),
            ) { b -> listOf(be(b, 0, 2) * LAMBDA, be(b, 2, 2) * 8.0 / 65536.0) }
            multi(0x34 + i, 4, PollTier.MEDIUM,
                SignalDef(0x34 + i, "o2_${pos}_lambda_c", "Wideband O2 sensor $label equivalence ratio (current type)", "λ", LAMBDA, 0.005),
                SignalDef(0x134 + i, "o2_${pos}_current", "Wideband O2 sensor $label current", "mA", 1.0 / 256.0, 0.05),
            ) { b -> listOf(be(b, 0, 2) * LAMBDA, be(b, 2, 2) / 256.0 - 128.0) }
        }
    }

    val pids: Map<Int, PidDef> = defs.associateBy { it.pid }
    private val signalsById: Map<Int, SignalDef> =
        (defs.flatMap { it.signals } + adapterVoltage + gpsSignals + derivedSignals).associateBy { it.id }
    private val signalsByKey: Map<String, SignalDef> = signalsById.values.associateBy { it.key }
    private val rawKey = Regex("pid_([0-9a-f]{2})_raw")

    fun signal(id: Int): SignalDef? =
        signalsById[id] ?: if (id in RAW_ID_BASE until RAW_ID_BASE + 0x100) rawSignal(id - RAW_ID_BASE) else null

    fun signal(key: String): SignalDef? =
        signalsByKey[key] ?: rawKey.matchEntire(key)?.let { rawSignal(it.groupValues[1].toInt(16)) }

    fun rawSignal(pid: Int) = SignalDef(
        RAW_ID_BASE + pid, "pid_%02x_raw".format(pid), "Mode 01 PID %02X (undecoded, big-endian)".format(pid), "raw", 1.0,
    )

    fun signalsOf(pid: Int): List<SignalDef> = pids[pid]?.signals ?: listOf(rawSignal(pid))

    fun tierOf(signalId: Int): PollTier = when {
        signalId == ADAPTER_VOLTAGE_ID -> PollTier.MEDIUM
        signalId in ID_GPS_LATITUDE..ID_ROAD_GRADE -> PollTier.MEDIUM
        signalId == ID_FUEL_RATE_EST -> PollTier.FAST
        signalId < 0x200 -> pids[signalId and 0xFF]?.tier ?: PollTier.SLOW
        else -> PollTier.SLOW
    }

    /** PIDs 00, 20, 40… only report which other PIDs are supported. */
    fun isBitmapPid(pid: Int) = pid % 0x20 == 0

    private fun be(b: IntArray, from: Int, count: Int): Long {
        var r = 0L
        for (i in from until from + count) r = (r shl 8) or b[i].toLong()
        return r
    }

    private fun linear(
        pid: Int,
        bytes: Int,
        tier: PollTier,
        key: String,
        name: String,
        unit: String,
        scale: Double,
        offset: Double = 0.0,
        deadband: Double = 0.0,
    ) {
        // resolution == scale, so the stored integer is exactly the raw ECU value.
        defs += PidDef(pid, bytes, tier, listOf(SignalDef(pid, key, name, unit, scale, deadband))) { b ->
            listOf(be(b, 0, bytes) * scale + offset)
        }
    }

    private fun multi(pid: Int, bytes: Int, tier: PollTier, vararg signals: SignalDef, decode: (IntArray) -> List<Double?>) {
        defs += PidDef(pid, bytes, tier, signals.toList(), decode)
    }

    private fun dualTrim(pid: Int, keyA: String, keyB: String, name: String, bankA: String, bankB: String) {
        multi(pid, 2, PollTier.MEDIUM,
            SignalDef(pid, keyA, "$name, $bankA", "%", TRIM, 1.5),
            SignalDef(0x100 + pid, keyB, "$name, $bankB", "%", TRIM, 1.5),
        ) { b -> listOf((b[0] - 128) * TRIM, (b[1] - 128) * TRIM) }
    }
}
