package com.example.agenticcar.data.recording

import com.example.agenticcar.data.LiveMetrics
import com.example.agenticcar.data.RecorderStatus
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.data.db.EventEntity
import com.example.agenticcar.data.db.SignalBlockEntity
import com.example.agenticcar.data.db.TripEntity
import com.example.agenticcar.protocol.obd.PidCatalog
import com.example.agenticcar.protocol.obd.SignalDef
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Reading(val signalId: Int, val value: Double)

enum class DtcKind(val command: String, val responseMode: Int, val eventType: String) {
    STORED("03", 0x43, "DTC_STORED"),
    PENDING("07", 0x47, "DTC_PENDING"),
    PERMANENT("0A", 0x4A, "DTC_PERMANENT"),
}

data class ObdSessionInfo(
    val adapterName: String,
    val adapterVersion: String?,
    val protocol: String?,
    val vin: String?,
    val signalIds: List<Int>,
    val batchSize: Int,
)

/**
 * Turns the reading stream into trips, compressed signal blocks and events.
 *
 * A trip starts when the engine runs (RPM > 400 or charging voltage > 13.5 V)
 * and ends after [ENGINE_OFF_GRACE_MS] without either. Readings outside a trip
 * update live state only. Calls come from the poll loop; methods are
 * synchronised so the service can finish a trip from another thread.
 */
@Singleton
class TelemetryRecorder @Inject constructor(
    private val writer: TelemetryWriter,
    private val state: TelemetryState,
) {
    private var session: ObdSessionInfo? = null
    private var trip: TripAccumulator? = null
    private val live = HashMap<Int, Double>()
    private val dtcs = EnumMap<DtcKind, List<String>>(DtcKind::class.java)
    private var lastMil: Boolean? = null
    private var lastUpdatedAt: Long? = null

    @Synchronized
    fun onSessionStarted(info: ObdSessionInfo) {
        session = info
        publish()
    }

    @Synchronized
    fun onReadings(timeMs: Long, readings: List<Reading>) {
        for (r in readings) live[r.signalId] = r.value
        lastUpdatedAt = timeMs
        val running = engineRunning()

        var current = trip
        if (current == null && running) current = startTrip(timeMs)
        if (current != null) {
            for (r in readings) current.add(timeMs, r)
            when {
                running -> current.lastRunningAt = timeMs
                timeMs - current.lastRunningAt >= ENGINE_OFF_GRACE_MS -> endTrip(timeMs)
            }
            if (trip != null && timeMs - current.lastUpsertAt >= TRIP_UPSERT_INTERVAL_MS) {
                writer.submit(TelemetryWriter.Op.Trip(current.toEntity(timeMs, ended = false)))
                current.lastUpsertAt = timeMs
            }
        }
        checkMil(timeMs)
        publish()
    }

    @Synchronized
    fun onDtcs(timeMs: Long, kind: DtcKind, codes: List<String>) {
        val previous = dtcs[kind].orEmpty()
        for (code in codes - previous.toSet()) event(timeMs, kind.eventType, code)
        if (kind == DtcKind.STORED) for (code in previous - codes.toSet()) event(timeMs, "DTC_CLEARED", code)
        dtcs[kind] = codes
        publish()
    }

    @Synchronized
    fun onDisconnected(timeMs: Long) {
        if (trip != null) endTrip(timeMs)
        session = null
        live.clear()
        lastMil = null
        publish()
    }

    private fun engineRunning(): Boolean {
        val rpm = live[PidCatalog.ID_RPM] ?: 0.0
        val volts = live[PidCatalog.ADAPTER_VOLTAGE_ID] ?: live[PidCatalog.ID_MODULE_VOLTAGE] ?: 0.0
        return rpm > RUNNING_RPM || volts > CHARGING_VOLTAGE
    }

    private fun startTrip(timeMs: Long): TripAccumulator {
        val t = TripAccumulator(timeMs, session) { op -> writer.submit(op) }
        trip = t
        // Seed every trip with a snapshot of all known values, including the rarely polled constants.
        for ((id, value) in live) t.add(timeMs, Reading(id, value))
        writer.submit(TelemetryWriter.Op.Trip(t.toEntity(timeMs, ended = false)))
        return t
    }

    private fun endTrip(timeMs: Long) {
        val t = trip ?: return
        t.closeAll()
        writer.submit(TelemetryWriter.Op.Trip(t.toEntity(timeMs, ended = true)))
        trip = null
    }

    private fun checkMil(timeMs: Long) {
        val mil = live[PidCatalog.ID_MIL]?.let { it >= 0.5 } ?: return
        if (lastMil != null && mil != lastMil) event(timeMs, if (mil) "MIL_ON" else "MIL_OFF", null)
        lastMil = mil
    }

    private fun event(timeMs: Long, type: String, code: String?, value: Double? = null) {
        writer.submit(TelemetryWriter.Op.Event(EventEntity(tripId = trip?.id, t = timeMs, type = type, code = code, value = value)))
    }

    private fun publish() {
        val t = trip
        val s = session
        val volts = live[PidCatalog.ADAPTER_VOLTAGE_ID] ?: live[PidCatalog.ID_MODULE_VOLTAGE]
        state.publishLive(
            metrics = LiveMetrics(
                rpm = live[PidCatalog.ID_RPM]?.roundToInt(),
                speedKmh = live[PidCatalog.ID_SPEED]?.roundToInt(),
                coolantTempCelsius = live[PidCatalog.ID_COOLANT]?.roundToInt(),
                engineLoadPercent = live[PidCatalog.ID_ENGINE_LOAD]?.toFloat(),
                batteryVoltage = volts?.toFloat(),
                tripActive = t != null,
                tripStartedAt = t?.id,
                lastUpdatedAt = lastUpdatedAt,
            ),
            signals = HashMap(live),
            recorder = RecorderStatus(
                sessionActive = s != null,
                adapterVersion = s?.adapterVersion,
                protocol = s?.protocol,
                vin = s?.vin,
                supportedSignals = s?.signalIds?.size ?: 0,
                batchSize = s?.batchSize ?: 1,
                tripId = t?.id,
                tripReadings = t?.rawSamples ?: 0,
                tripStoredSamples = t?.liveStoredSamples() ?: 0,
                tripStoredBytes = t?.liveStoredBytes() ?: 0,
                dtcStored = dtcs[DtcKind.STORED].orEmpty(),
                dtcPending = dtcs[DtcKind.PENDING].orEmpty(),
                dtcPermanent = dtcs[DtcKind.PERMANENT].orEmpty(),
            ),
        )
    }

    companion object {
        const val BLOCK_MS = 60_000
        /** A signal is stored at least this often even when unchanged, bounding step-hold ambiguity. */
        const val HEARTBEAT_MS = 15_000
        const val ENGINE_OFF_GRACE_MS = 30_000L
        private const val TRIP_UPSERT_INTERVAL_MS = 10_000L
        private const val RUNNING_RPM = 400.0
        private const val CHARGING_VOLTAGE = 13.5
    }
}

/** In-memory state of one trip: open blocks per signal, running totals and event detectors. */
private class TripAccumulator(
    val id: Long,
    private val session: ObdSessionInfo?,
    private val sink: (TelemetryWriter.Op) -> Unit,
) {
    var lastRunningAt = id
    var lastUpsertAt = id
    var rawSamples = 0L
    var storedSamples = 0L
    var storedBytes = 0L

    private val tracks = HashMap<Int, SignalTrack>()
    private var distanceKm = 0.0
    private var fuelL = 0.0
    private var fuelKnown = false
    private var idleMs = 0L
    private var movingMs = 0L
    private var maxSpeed: Double? = null
    private var maxRpm: Double? = null
    private var maxCoolant: Double? = null

    private var rpm = 0.0
    private var fuelType: Double? = null
    private var lastSpeedT = -1L
    private var lastSpeed = 0.0
    private val speedWindow = ArrayDeque<Pair<Long, Double>>()
    private var lastFuelRateT = -1L
    private var lastMafT = -1L
    private val lastEventAt = HashMap<String, Long>()

    fun add(timeMs: Long, reading: Reading) {
        val def = PidCatalog.signal(reading.signalId) ?: return
        val track = tracks.getOrPut(reading.signalId) { SignalTrack(id, def) }
        track.add((timeMs - id).toInt(), reading.value)?.let(::emit)
        rawSamples++
        derive(timeMs, reading.signalId, reading.value)
    }

    fun closeAll() {
        tracks.values.forEach { track -> track.close()?.let(::emit) }
    }

    /** Committed blocks plus the still-open minute, for live display. */
    fun liveStoredSamples(): Long = storedSamples + tracks.values.sumOf { it.openStored.toLong() }

    fun liveStoredBytes(): Long = storedBytes + tracks.values.sumOf { it.openBytes.toLong() }

    fun toEntity(nowMs: Long, ended: Boolean) = TripEntity(
        id = id,
        endedAt = if (ended) nowMs else null,
        updatedAt = nowMs,
        vin = session?.vin,
        protocol = session?.protocol,
        adapter = session?.adapterName,
        // Supported ECU signals plus anything else recorded, such as GPS.
        signalIds = (session?.signalIds.orEmpty() + tracks.keys).distinct().sorted().joinToString(","),
        distanceKm = distanceKm,
        fuelUsedL = if (fuelKnown) fuelL else null,
        idleSeconds = idleMs / 1000.0,
        movingSeconds = movingMs / 1000.0,
        maxSpeedKmh = maxSpeed,
        maxRpm = maxRpm,
        maxCoolantC = maxCoolant,
        rawSamples = rawSamples,
        storedSamples = storedSamples,
        storedBytes = storedBytes,
    )

    private fun emit(block: SignalBlockEntity) {
        storedSamples += block.stored
        storedBytes += block.data.size
        sink(TelemetryWriter.Op.Block(block))
    }

    /** Integrations and event detection that need the raw (uncompressed) stream. */
    private fun derive(t: Long, signalId: Int, v: Double) {
        when (signalId) {
            PidCatalog.ID_SPEED -> onSpeed(t, v)
            PidCatalog.ID_RPM -> {
                rpm = v
                maxRpm = max(maxRpm ?: v, v)
                if (v > OVER_REV_RPM) event(t, "OVER_REV", v, cooldownMs = 10_000)
            }
            PidCatalog.ID_COOLANT -> {
                maxCoolant = max(maxCoolant ?: v, v)
                if (v >= OVERHEAT_C) event(t, "OVERHEAT", v, cooldownMs = 60_000)
            }
            PidCatalog.ID_FUEL_TYPE -> fuelType = v
            PidCatalog.ID_FUEL_RATE -> {
                if (lastFuelRateT >= 0 && t - lastFuelRateT <= MAX_INTEGRATION_GAP_MS) fuelL += v * (t - lastFuelRateT) / 3_600_000.0
                lastFuelRateT = t
                fuelKnown = true
            }
            PidCatalog.ID_MAF -> {
                // Without a fuel-rate PID, estimate petrol consumption from airflow at stoichiometric AFR.
                val petrol = fuelType == null || fuelType == 1.0
                if (lastFuelRateT < 0 && petrol) {
                    if (lastMafT >= 0 && t - lastMafT <= MAX_INTEGRATION_GAP_MS) {
                        fuelL += v / (STOICH_AFR_PETROL * PETROL_DENSITY_G_PER_L) * (t - lastMafT) / 1000.0
                    }
                    fuelKnown = true
                }
                lastMafT = t
            }
            PidCatalog.ADAPTER_VOLTAGE_ID, PidCatalog.ID_MODULE_VOLTAGE ->
                if (rpm > 1_200 && v < LOW_CHARGING_V) event(t, "LOW_CHARGING_VOLTAGE", v, cooldownMs = 120_000)
        }
    }

    private fun onSpeed(t: Long, v: Double) {
        maxSpeed = max(maxSpeed ?: v, v)
        if (lastSpeedT >= 0) {
            val dt = t - lastSpeedT
            if (dt in 1..MAX_INTEGRATION_GAP_MS) {
                distanceKm += (lastSpeed + v) / 2.0 * dt / 3_600_000.0
                if (v < 1.0 && rpm > 400) idleMs += dt else if (v >= 1.0) movingMs += dt
            }
        }
        lastSpeedT = t
        lastSpeed = v

        // Acceleration over a ~1 s window smooths single-sample quantisation noise.
        speedWindow.addLast(t to v)
        while (speedWindow.isNotEmpty() && speedWindow.first().first < t - 1_500) speedWindow.removeFirst()
        val (t0, v0) = speedWindow.first()
        if (t - t0 >= 700) {
            val accel = (v - v0) / ((t - t0) / 1000.0)
            if (accel >= HARSH_ACCEL_KMH_S) event(t, "HARSH_ACCELERATION", accel, cooldownMs = 5_000)
            if (accel <= -HARSH_BRAKE_KMH_S) event(t, "HARSH_BRAKING", accel, cooldownMs = 5_000)
        }
    }

    private fun event(t: Long, type: String, value: Double, cooldownMs: Long) {
        val last = lastEventAt[type]
        if (last != null && t - last < cooldownMs) return
        lastEventAt[type] = t
        sink(TelemetryWriter.Op.Event(EventEntity(tripId = id, t = t, type = type, value = (value * 100).roundToInt() / 100.0)))
    }

    private companion object {
        const val MAX_INTEGRATION_GAP_MS = 5_000L
        const val OVER_REV_RPM = 5_000.0
        const val OVERHEAT_C = 110.0
        const val LOW_CHARGING_V = 12.8
        // ~0.34 g and ~0.4 g, common telematics thresholds.
        const val HARSH_ACCEL_KMH_S = 12.0
        const val HARSH_BRAKE_KMH_S = 14.0
        const val STOICH_AFR_PETROL = 14.7
        const val PETROL_DENSITY_G_PER_L = 745.0
    }
}

/** One signal's open one-minute block: exact stats of every reading plus deadband-filtered samples. */
private class SignalTrack(private val tripId: Long, private val def: SignalDef) {
    private var blockStart = -1
    private var encoder = BlockCodec.Encoder(0)
    private var n = 0
    private var minV = 0.0
    private var maxV = 0.0
    private var sum = 0.0
    private var lastQ = 0L
    private var lastStoredT = 0

    val openStored: Int get() = if (n > 0) encoder.count else 0
    val openBytes: Int get() = if (n > 0) encoder.sizeBytes else 0

    /** Adds a reading; returns the previous block if this reading opened a new minute. */
    fun add(tRelMs: Int, value: Double): SignalBlockEntity? {
        val start = (tRelMs / TelemetryRecorder.BLOCK_MS) * TelemetryRecorder.BLOCK_MS
        var closed: SignalBlockEntity? = null
        if (start != blockStart) {
            closed = close()
            blockStart = start
            encoder = BlockCodec.Encoder(start)
        }
        if (n == 0) {
            minV = value
            maxV = value
        } else {
            minV = min(minV, value)
            maxV = max(maxV, value)
        }
        n++
        sum += value

        val q = Math.round(value / def.resolution)
        // Every block starts with a stored sample, so blocks decode independently.
        val changed = abs(q - lastQ) * def.resolution > def.deadband + EPSILON
        if (encoder.count == 0 || changed || tRelMs - lastStoredT >= TelemetryRecorder.HEARTBEAT_MS) {
            encoder.add(tRelMs, q)
            lastQ = q
            lastStoredT = tRelMs
        }
        return closed
    }

    fun close(): SignalBlockEntity? {
        if (n == 0) return null
        val block = SignalBlockEntity(
            tripId = tripId,
            signalId = def.id,
            startMs = blockStart,
            readings = n,
            stored = encoder.count,
            minValue = minV,
            maxValue = maxV,
            meanValue = sum / n,
            data = encoder.toByteArray(),
        )
        n = 0
        sum = 0.0
        return block
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
