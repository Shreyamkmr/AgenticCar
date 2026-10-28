package com.example.agenticcar.protocol.mcp

import android.content.Context
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.data.db.EventEntity
import com.example.agenticcar.data.db.SignalStatsRow
import com.example.agenticcar.data.db.TelemetryDao
import com.example.agenticcar.data.db.TelemetryDatabase
import com.example.agenticcar.data.db.TripEntity
import com.example.agenticcar.data.location.GradeEstimator
import com.example.agenticcar.data.recording.BlockCodec
import com.example.agenticcar.data.recording.DtcKind
import com.example.agenticcar.protocol.obd.PidCatalog
import com.example.agenticcar.protocol.obd.SignalDef
import dagger.hilt.android.qualifiers.ApplicationContext
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/** Builds the MCP [Server] and registers the read-only telemetry tools. */
@Singleton
class ObdMcpTools @Inject constructor(
    private val dao: TelemetryDao,
    private val queries: TelemetryQueries,
    private val state: TelemetryState,
    @ApplicationContext private val context: Context,
) {
    fun createServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "agentic-car-obd", version = "2.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
            instructionsProvider = { INSTRUCTIONS },
        )

        server.addTool(
            name = "get_vehicle_info",
            description = "Vehicle identity and capabilities: VIN, OBD protocol, adapter, and every signal the ECU supports " +
                "(key, name, unit, polling tier). Call this first to learn which signal keys exist.",
        ) { _ -> tool { vehicleInfo() } }

        server.addTool(
            name = "get_latest_metrics",
            description = "Live snapshot while the car is connected: every current signal value, trip status and recorder stats.",
        ) { _ -> tool { latestMetrics() } }

        server.addTool(
            name = "list_trips",
            description = "Recorded trips (engine-on sessions), newest first, with distance, duration, fuel used and " +
                "economy, max speed/RPM/coolant, idle share and moving average speed.",
            inputSchema = schema(required = emptyList()) {
                number("limit", "Max trips to return (default 20).")
            },
        ) { request -> tool { listTrips(request.arguments.int("limit") ?: 20) } }

        server.addTool(
            name = "get_trip_summary",
            description = "Full summary of one trip: min/max/mean of every recorded signal, trip metrics, event counts and " +
                "derived insights (time in speed and RPM bands, warm-up time, charging and resting voltage). Defaults to " +
                "the latest trip. Alternatively pass start_timestamp/end_timestamp (epoch ms) for per-signal stats over " +
                "any window, which may span trips.",
            inputSchema = schema(required = emptyList()) {
                number("trip_id", "Trip id from list_trips (epoch ms of trip start). Defaults to the latest trip.")
                number("start_timestamp", "Window start, epoch milliseconds (window mode).")
                number("end_timestamp", "Window end, epoch milliseconds. Defaults to now.")
            },
        ) { request ->
            tool {
                val args = request.arguments
                val start = args.long("start_timestamp")
                if (start != null) windowSummary(start, args.long("end_timestamp") ?: System.currentTimeMillis())
                else tripSummary(args.long("trip_id"))
            }
        }

        server.addTool(
            name = "get_signal_series",
            description = "Time series of chosen signals for one trip, resampled into equal buckets on a shared time axis " +
                "(seconds from trip start). Use small max_points for overviews and a narrow from_s/to_s window to zoom in. " +
                "Values are bucket means (or min/max via stat); null means no data.",
            inputSchema = schema(required = listOf("signals")) {
                stringArray("signals", "Signal keys, e.g. [\"rpm\",\"speed\",\"stft_b1\"]. See get_vehicle_info.")
                number("trip_id", "Trip id. Defaults to the latest trip.")
                number("from_s", "Window start, seconds from trip start (default 0).")
                number("to_s", "Window end, seconds from trip start (default trip end).")
                number("max_points", "Buckets to return, 2-1000 (default 120).")
                enumString("stat", "Aggregate per bucket (default mean).", listOf("mean", "min", "max"))
            },
        ) { request -> tool { signalSeries(request.arguments) } }

        server.addTool(
            name = "get_route",
            description = "GPS track of a trip, resampled to at most max_points: rows of [t_s, latitude, longitude, " +
                "altitude_m, gps_speed_kmh, road_grade_pct] plus GPS distance, bounding box and altitude range. " +
                "Use it to map the route or relate engine behaviour to hills (combine with get_signal_series).",
            inputSchema = schema(required = emptyList()) {
                number("trip_id", "Trip id. Defaults to the latest trip.")
                number("max_points", "Track points to return, 2-2000 (default 300).")
            },
        ) { request -> tool { route(request.arguments.long("trip_id"), request.arguments.int("max_points") ?: 300) } }

        server.addTool(
            name = "get_events",
            description = "Detected events, newest first: HARSH_BRAKING, HARSH_ACCELERATION (value = km/h per second), " +
                "OVER_REV, OVERHEAT, LOW_CHARGING_VOLTAGE, MIL_ON/MIL_OFF, DTC_STORED/PENDING/PERMANENT/CLEARED (code = DTC).",
            inputSchema = schema(required = emptyList()) {
                number("trip_id", "Only events of this trip.")
                string("type", "Only this event type.")
                number("limit", "Max events (default 50).")
            },
        ) { request ->
            tool {
                val args = request.arguments
                events(args.long("trip_id"), args.string("type"), args.int("limit") ?: 50)
            }
        }

        server.addTool(
            name = "get_diagnostics",
            description = "Engine health: stored, pending and permanent trouble codes, check-engine light, DTC count, " +
                "distance/time with MIL on and since codes were cleared. Live when connected, otherwise from history.",
        ) { _ -> tool { diagnostics() } }

        server.addTool(
            name = "get_storage_stats",
            description = "Database size and compression: trips, readings received vs samples stored, bytes per reading.",
        ) { _ -> tool { storageStats() } }

        return server
    }

    // region Tools

    private suspend fun vehicleInfo(): JsonObject {
        val rs = state.recorder.value
        val latest = dao.recentTrips(1).firstOrNull()
        val signalIds = if (rs.sessionActive) state.liveSignals.value.keys.sorted() else latest?.signalIdList().orEmpty()
        return buildJsonObject {
            put("source", if (rs.sessionActive) "live" else "last_trip")
            put("vin", if (rs.sessionActive) rs.vin else latest?.vin)
            put("obd_protocol", if (rs.sessionActive) rs.protocol else latest?.protocol)
            put("adapter", rs.adapterVersion ?: latest?.adapter)
            put("pids_per_request", rs.batchSize)
            put("fuel_type", lastValue(PidCatalog.ID_FUEL_TYPE)?.let(::fuelTypeName))
            putJsonArray("signals") {
                signalIds.mapNotNull(PidCatalog::signal).forEach { def ->
                    addJsonObject {
                        put("key", def.key)
                        put("name", def.name)
                        put("unit", def.unit)
                        put("tier", PidCatalog.tierOf(def.id).name.lowercase())
                    }
                }
            }
        }
    }

    private fun latestMetrics(): JsonObject {
        val metrics = state.metrics.value
        val rs = state.recorder.value
        return buildJsonObject {
            put("obd_connection", state.obd.value::class.simpleName)
            put("trip_active", metrics.tripActive)
            put("trip_id", metrics.tripStartedAt)
            put("last_updated_at", metrics.lastUpdatedAt?.let(::iso))
            putJsonObject("recorder") {
                put("trip_readings", rs.tripReadings)
                put("trip_stored_samples", rs.tripStoredSamples)
                put("trip_stored_bytes", rs.tripStoredBytes)
            }
            val live = state.liveSignals.value
            if (live.isEmpty()) {
                put("note", "Not connected to the car. Use list_trips and get_trip_summary for recorded data.")
            }
            putJsonObject("values") {
                live.toSortedMap().forEach { (id, v) -> PidCatalog.signal(id)?.let { put(it.key, round(v, it.resolution)) } }
            }
        }
    }

    private suspend fun listTrips(limit: Int): JsonObject {
        val trips = dao.recentTrips(limit.coerceIn(1, 500))
        return buildJsonObject { putJsonArray("trips") { trips.forEach { add(tripJson(it)) } } }
    }

    private suspend fun tripSummary(tripId: Long?): JsonObject {
        val trip = (if (tripId != null) dao.trip(tripId) else dao.recentTrips(1).firstOrNull())
            ?: return buildJsonObject { put("error", if (tripId != null) "No trip $tripId" else "No trips recorded yet") }
        val insights = queries.insights(trip)
        val eventCounts = dao.eventCounts(trip.id)
        val stats = dao.tripStats(trip.id)
        return buildJsonObject {
            put("trip", tripJson(trip))
            putJsonObject("insights") {
                putJsonObject("speed_band_minutes") { insights.speedBandMinutes.forEach { (k, v) -> put(k, round2(v)) } }
                putJsonObject("rpm_band_minutes") { insights.rpmBandMinutes.forEach { (k, v) -> put(k, round2(v)) } }
                put("warm_up_minutes_to_80c", insights.warmUpMinutesTo80C?.let(::round2))
                put("charging_voltage_mean", insights.chargingVoltageMean?.let(::round2))
                put("resting_voltage_min", insights.restingVoltageMin?.let(::round2))
                val elevation = insights.elevation
                if (elevation == null) {
                    put("elevation", "no GPS recorded for this trip")
                } else {
                    putJsonObject("elevation") {
                        put("min_altitude_m", round(elevation.minAltitudeM, 1.0))
                        put("max_altitude_m", round(elevation.maxAltitudeM, 1.0))
                        put("climb_m", round(elevation.climbM, 1.0))
                        put("descent_m", round(elevation.descentM, 1.0))
                        putJsonObject("by_road_grade") {
                            elevation.gradeBands.forEach { (band, g) ->
                                putJsonObject(band) {
                                    put("minutes", round2(g.minutes))
                                    put("mean_engine_load_pct", g.meanEngineLoadPct?.let { round(it, 0.1) })
                                    put("mean_rpm", g.meanRpm?.let { round(it, 1.0) })
                                    put("mean_fuel_rate_l_h", g.meanFuelRateLh?.let(::round2))
                                    put("mean_maf_g_s", g.meanMafGs?.let(::round2))
                                    put("mean_speed_kmh", g.meanSpeedKmh?.let { round(it, 0.1) })
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("event_counts") { eventCounts.forEach { put(it.type, it.count) } }
            put("signals", statsJson(stats))
        }
    }

    private suspend fun windowSummary(start: Long, end: Long): JsonObject {
        if (end < start) return buildJsonObject { put("error", "end_timestamp must not be before start_timestamp") }
        return buildJsonObject {
            put("window_start", iso(start))
            put("window_end", iso(end))
            put("granularity", "1 minute blocks")
            put("signals", statsJson(dao.statsBetween(start, end)))
        }
    }

    private suspend fun signalSeries(args: JsonObject?): JsonObject {
        val keys = args.stringList("signals")
        if (keys.isEmpty()) return buildJsonObject { put("error", "Pass at least one signal key in 'signals'.") }
        val trip = args.long("trip_id")?.let { dao.trip(it) } ?: dao.recentTrips(1).firstOrNull()
            ?: return buildJsonObject { put("error", "No trips recorded yet") }

        val defs = keys.mapNotNull(PidCatalog::signal)
        val unknown = keys.filter { PidCatalog.signal(it) == null }
        val tripEnd = queries.tripEndMs(trip)
        val from = ((args.double("from_s") ?: 0.0) * 1000).toInt().coerceIn(0, tripEnd)
        val to = ((args.double("to_s")?.times(1000))?.toInt() ?: tripEnd).coerceIn(from + 1, maxOf(tripEnd, from + 1))
        val buckets = (args.int("max_points") ?: 120).coerceIn(2, 1000)
        val stat = args.string("stat") ?: "mean"
        val series = queries.series(trip.id, defs.map { it.id }, from, to)
        val width = (to - from) / buckets.toDouble()

        return buildJsonObject {
            put("trip_id", trip.id)
            put("from_s", from / 1000.0)
            put("to_s", to / 1000.0)
            put("bucket_s", round2(width / 1000.0))
            put("stat", stat)
            putJsonArray("t_s") { repeat(buckets) { b -> add(round2((from + b * width) / 1000.0)) } }
            putJsonObject("series") {
                defs.forEach { def ->
                    val values = TelemetryQueries.resample(series[def.id].orEmpty(), from, to, buckets, stat)
                    put(def.key, buildJsonArray { values.forEach { add(round(it, def.resolution)) } })
                }
            }
            putJsonObject("units") { defs.forEach { put(it.key, it.unit) } }
            if (unknown.isNotEmpty()) putJsonArray("unknown_signals") { unknown.forEach { add(it) } }
        }
    }

    private suspend fun route(tripId: Long?, maxPoints: Int): JsonObject {
        val trip = tripId?.let { dao.trip(it) } ?: dao.recentTrips(1).firstOrNull()
            ?: return buildJsonObject { put("error", "No trips recorded yet") }
        val end = queries.tripEndMs(trip)
        val ids = listOf(
            PidCatalog.ID_GPS_LATITUDE, PidCatalog.ID_GPS_LONGITUDE, PidCatalog.ID_GPS_ALTITUDE,
            PidCatalog.ID_GPS_SPEED, PidCatalog.ID_ROAD_GRADE,
        )
        val s = queries.series(trip.id, ids, 0, end + 1)
        val lat = s[PidCatalog.ID_GPS_LATITUDE].orEmpty()
        val lon = s[PidCatalog.ID_GPS_LONGITUDE].orEmpty()
        if (lat.isEmpty() || lon.isEmpty()) return buildJsonObject {
            put("trip_id", trip.id)
            put("error", "No GPS recorded for this trip (location permission off, or no fix).")
        }

        // Distance over the full-resolution track, pairing each latitude with the longitude held at that time.
        var distanceM = 0.0
        var prev: Pair<Double, Double>? = null
        for (p in lat) {
            val lo = TelemetryQueries.valueAt(lon, p.tMs) ?: continue
            prev?.let { (pLat, pLon) -> distanceM += GradeEstimator.haversineM(pLat, pLon, p.value, lo) }
            prev = p.value to lo
        }

        val buckets = maxPoints.coerceIn(2, 2000)
        val columns = ids.map { id -> TelemetryQueries.resample(s[id].orEmpty(), 0, end + 1, buckets, "mean") }
        val width = (end + 1) / buckets.toDouble()
        val altitudes = s[PidCatalog.ID_GPS_ALTITUDE].orEmpty().map { it.value }
        return buildJsonObject {
            put("trip_id", trip.id)
            put("gps_distance_km", round2(distanceM / 1000))
            put("columns", buildJsonArray {
                listOf("t_s", "latitude", "longitude", "altitude_m", "gps_speed_kmh", "road_grade_pct").forEach { add(it) }
            })
            putJsonArray("points") {
                for (b in 0 until buckets) {
                    val la = columns[0][b] ?: continue
                    val lo = columns[1][b] ?: continue
                    add(buildJsonArray {
                        add(round2(b * width / 1000))
                        add(round(la, 0.000001))
                        add(round(lo, 0.000001))
                        add(columns[2][b]?.let { round(it, 1.0) })
                        add(columns[3][b]?.let { round(it, 1.0) })
                        add(columns[4][b]?.let { round(it, 0.1) })
                    })
                }
            }
            putJsonObject("bounding_box") {
                put("min_lat", round(lat.minOf { it.value }, 0.000001))
                put("max_lat", round(lat.maxOf { it.value }, 0.000001))
                put("min_lon", round(lon.minOf { it.value }, 0.000001))
                put("max_lon", round(lon.maxOf { it.value }, 0.000001))
            }
            if (altitudes.isNotEmpty()) {
                put("min_altitude_m", round(altitudes.min(), 1.0))
                put("max_altitude_m", round(altitudes.max(), 1.0))
            }
        }
    }

    private suspend fun events(tripId: Long?, type: String?, limit: Int): JsonObject {
        val events = dao.events(tripId, type?.uppercase(), limit.coerceIn(1, 1000))
        return buildJsonObject { putJsonArray("events") { events.forEach { add(eventJson(it)) } } }
    }

    private suspend fun diagnostics(): JsonObject {
        val rs = state.recorder.value
        val history = if (rs.sessionActive) emptyMap() else DtcKind.entries.associateWith { dao.events(null, it.eventType, 100) }
        val indicators = listOf(0x01, 0x101, 0x21, 0x4D, 0x31, 0x30, 0x4E)
            .mapNotNull { id -> PidCatalog.signal(id)?.let { def -> lastValue(id)?.let { def to it } } }
        return buildJsonObject {
            put("source", if (rs.sessionActive) "live" else "history")
            if (rs.sessionActive) {
                putJsonArray("stored_dtcs") { rs.dtcStored.forEach { add(it) } }
                putJsonArray("pending_dtcs") { rs.dtcPending.forEach { add(it) } }
                putJsonArray("permanent_dtcs") { rs.dtcPermanent.forEach { add(it) } }
            } else {
                // Offline: the most recent sighting of each code per kind.
                for ((kind, events) in history) {
                    putJsonArray(kind.eventType.lowercase().removePrefix("dtc_") + "_dtcs_seen") {
                        events.distinctBy { it.code }.forEach { e ->
                            addJsonObject {
                                put("code", e.code)
                                put("last_seen", iso(e.t))
                            }
                        }
                    }
                }
            }
            putJsonObject("indicators") {
                indicators.forEach { (def, value) -> put(def.key, round(value, def.resolution)) }
            }
        }
    }

    private suspend fun storageStats(): JsonObject {
        val totals = dao.storageTotals()
        val readings = totals.readingCount ?: 0L
        val stored = totals.storedCount ?: 0L
        fun fileBytes(suffix: String) = context.getDatabasePath(TelemetryDatabase.NAME + suffix).length()
        val dbBytes = fileBytes("")
        // The WAL is a reusable journal that SQLite checkpoints back into the main file; it is not
        // permanent storage, so it is reported separately and excluded from the per-reading figure.
        val walBytes = fileBytes("-wal")
        val payload = totals.byteCount ?: 0L
        return buildJsonObject {
            put("trips", dao.tripCount())
            put("signal_blocks", totals.blockCount)
            put("readings_received", readings)
            put("samples_stored", stored)
            put("stored_fraction", if (readings > 0) round2(stored.toDouble() / readings) else null)
            put("sample_payload_bytes", payload)
            put("payload_bytes_per_reading", if (readings > 0) round2(payload.toDouble() / readings) else null)
            put("database_file_bytes", dbBytes)
            put("wal_journal_bytes", walBytes)
        }
    }

    // endregion

    // region JSON helpers

    private fun tripJson(trip: TripEntity): JsonObject = buildJsonObject {
        val durationS = ((trip.endedAt ?: trip.updatedAt) - trip.id) / 1000.0
        put("trip_id", trip.id)
        put("started_at", iso(trip.id))
        put("ended_at", trip.endedAt?.let(::iso))
        put("active", trip.endedAt == null)
        put("duration_min", round2(durationS / 60))
        put("distance_km", round2(trip.distanceKm))
        put("fuel_used_l", trip.fuelUsedL?.let(::round2))
        put("fuel_l_per_100km", trip.fuelUsedL?.takeIf { trip.distanceKm > 0.5 }?.let { round2(it / trip.distanceKm * 100) })
        put("max_speed_kmh", trip.maxSpeedKmh)
        put("avg_moving_speed_kmh", if (trip.movingSeconds > 0) round2(trip.distanceKm / (trip.movingSeconds / 3600)) else null)
        put("idle_pct", if (durationS > 0) round2(trip.idleSeconds / durationS * 100) else null)
        put("max_rpm", trip.maxRpm)
        put("max_coolant_c", trip.maxCoolantC)
        put("vin", trip.vin)
        put("readings", trip.rawSamples)
        put("stored_samples", trip.storedSamples)
    }

    private fun statsJson(rows: List<SignalStatsRow>): JsonObject = buildJsonObject {
        rows.sortedBy { it.signalId }.forEach { row ->
            val def = PidCatalog.signal(row.signalId) ?: return@forEach
            putJsonObject(def.key) {
                put("min", round(row.minValue, def.resolution))
                put("max", round(row.maxValue, def.resolution))
                put("mean", round(row.meanValue, def.resolution / 10))
                put("unit", def.unit)
                put("readings", row.readings)
            }
        }
    }

    private fun eventJson(e: EventEntity): JsonObject = buildJsonObject {
        put("t", iso(e.t))
        put("trip_id", e.tripId)
        put("trip_offset_s", e.tripId?.let { round2((e.t - it) / 1000.0) })
        put("type", e.type)
        put("code", e.code)
        put("value", e.value)
    }

    /** Live value if connected, else the last recorded value. */
    private suspend fun lastValue(signalId: Int): Double? {
        state.liveSignals.value[signalId]?.let { return it }
        val block = dao.latestBlock(signalId) ?: return null
        val res = PidCatalog.signal(signalId)?.resolution ?: 1.0
        return BlockCodec.decode(block.data, block.startMs, res).lastOrNull()?.value
    }

    private fun TripEntity.signalIdList(): List<Int> = signalIds.split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun fuelTypeName(code: Double): String = when (code.toInt()) {
        1 -> "petrol"
        2 -> "methanol"
        3 -> "ethanol"
        4 -> "diesel"
        5 -> "lpg"
        6 -> "cng"
        7 -> "propane"
        8 -> "electric"
        in 9..23 -> "bifuel_or_hybrid (code ${code.toInt()})"
        else -> "unknown (code ${code.toInt()})"
    }

    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    // endregion

    private suspend fun tool(block: suspend () -> JsonObject): CallToolResult =
        try {
            val json = block()
            CallToolResult(content = listOf(TextContent(json.toString())), structuredContent = json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Error: ${e.message ?: e::class.simpleName}")), isError = true)
        }

    private companion object {
        val INSTRUCTIONS = """
            OBD-II telemetry recorded from the user's car through an ELM327 adapter on their phone.
            Data model: trips are engine-on sessions (trip_id = start time, epoch ms). Each trip stores every signal the
            ECU supports as a compressed time series: a value holds until the next stored point (step-hold), the error is
            bounded by a small per-signal deadband, and stats (min/max/mean) are exact. Events flag harsh driving, faults
            and threshold crossings.
            Suggested workflow: get_vehicle_info (signal keys) -> list_trips -> get_trip_summary(trip_id) ->
            get_signal_series for detail (start with max_points ~100, then zoom with from_s/to_s) -> get_events and
            get_diagnostics for faults. When the phone had a GPS fix, trips also hold gps_latitude/longitude/altitude,
            gps_speed and road_grade (% over 100 m, + = uphill): get_route returns the track, and get_trip_summary
            compares engine load, RPM and fuel use across slope bands. GPS altitude is accurate to ±5-15 m, so trust
            grades over hundreds of metres, not single points. Fuel trims: stft/ltft beyond about ±10% suggest a
            mixture problem; coolant should settle near 85-105 °C; charging voltage should be about 13.5-14.7 V with
            the engine running.
        """.trimIndent()

        fun round(v: Double?, resolution: Double): Double? {
            if (v == null || !v.isFinite()) return null
            // Enough decimals to show the stored resolution: 1e-6° GPS coordinates need 6 (~0.1 m).
            val decimals = ceil(-log10(resolution) - 1e-9).toInt().coerceIn(0, 7)
            val f = 10.0.pow(decimals)
            return (v * f).roundToLong() / f
        }

        fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

        fun JsonObject?.primitive(key: String): JsonPrimitive? = this?.get(key) as? JsonPrimitive

        fun JsonObject?.long(key: String): Long? = primitive(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

        fun JsonObject?.int(key: String): Int? = long(key)?.toInt()

        fun JsonObject?.double(key: String): Double? = primitive(key)?.doubleOrNull

        fun JsonObject?.string(key: String): String? = primitive(key)?.contentOrNull

        /** Accepts a JSON array of strings or a comma-separated string. */
        fun JsonObject?.stringList(key: String): List<String> = when (val v = this?.get(key)) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }.orEmpty()
            else -> emptyList()
        }.filter { it.isNotEmpty() }

        /** Small DSL for tool input schemas. */
        class SchemaBuilder(private val props: JsonObjectBuilder) {
            fun number(name: String, description: String) = props.putJsonObject(name) {
                put("type", "number")
                put("description", description)
            }
            fun string(name: String, description: String) = props.putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }
            fun enumString(name: String, description: String, values: List<String>) = props.putJsonObject(name) {
                put("type", "string")
                put("description", description)
                putJsonArray("enum") { values.forEach { add(it) } }
            }
            fun stringArray(name: String, description: String) = props.putJsonObject(name) {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", description)
            }
        }

        fun schema(required: List<String>, block: SchemaBuilder.() -> Unit): ToolSchema = ToolSchema(
            properties = buildJsonObject { SchemaBuilder(this).block() },
            required = required.ifEmpty { null },
        )
    }
}
