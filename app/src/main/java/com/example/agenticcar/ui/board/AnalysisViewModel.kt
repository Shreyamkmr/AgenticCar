package com.example.agenticcar.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agenticcar.data.Sample
import com.example.agenticcar.data.db.TelemetryDao
import com.example.agenticcar.data.db.TripEntity
import com.example.agenticcar.data.recording.SpikeFilter
import com.example.agenticcar.protocol.mcp.TelemetryQueries
import com.example.agenticcar.protocol.obd.PidCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class AnalysisSource { TRIPS, RANGE }

data class AnalysisUiState(
    val source: AnalysisSource = AnalysisSource.TRIPS,
    val trips: List<TripEntity> = emptyList(),
    val selectedTripIds: Set<Long> = emptySet(),
    /** The many sub-minute fragments left by Bluetooth drops are hidden unless asked for. */
    val hideShortTrips: Boolean = true,
    val rangeFrom: Long = startOfDay(0),
    val rangeTo: Long = System.currentTimeMillis(),
    val loading: Boolean = false,
    val result: AnalysisResult? = null,
    val error: String? = null,
) {
    val visibleTrips: List<TripEntity>
        get() = if (hideShortTrips) trips.filter { durationMs(it) >= SHORT_TRIP_MS || it.id in selectedTripIds } else trips

    val canAnalyse: Boolean
        get() = !loading && when (source) {
            AnalysisSource.TRIPS -> selectedTripIds.isNotEmpty()
            AnalysisSource.RANGE -> rangeTo > rangeFrom
        }

    companion object {
        const val SHORT_TRIP_MS = 60_000L
        fun durationMs(trip: TripEntity) = ((trip.endedAt ?: trip.updatedAt) - trip.id).coerceAtLeast(0)
        fun startOfDay(daysAgo: Long): Long =
            LocalDate.now().minusDays(daysAgo).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

data class AnalysisResult(
    val title: String,
    val tripCount: Int,
    val drivingMinutes: Double,
    val distanceKm: Double,
    val from: Long,
    val to: Long,
    /** Per signal id, so any layout can be shown without fetching again. */
    val bySignal: Map<Int, TileData>,
    /** GPS track per colouring signal of the layouts' route tiles. */
    val routes: Map<Int, List<RoutePoint>> = emptyMap(),
) {
    fun data(tile: Tile): TileData {
        val data = bySignal[tile.signalId] ?: TileData.EMPTY
        return if (tile.type == TileType.ROUTE) data.copy(route = routes[tile.signalId].orEmpty()) else data
    }
}

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val dao: TelemetryDao,
    private val queries: TelemetryQueries,
    private val store: BoardLayoutStore,
) : ViewModel() {

    val layouts: StateFlow<BoardLayoutStore.Snapshot> = store.state

    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    init {
        refreshTrips()
    }

    fun refreshTrips() {
        viewModelScope.launch {
            val trips = dao.recentTrips(TRIP_LIST_LIMIT)
            _state.update { it.copy(trips = trips) }
        }
    }

    fun selectLayout(id: String) = store.select(id)

    fun setSource(source: AnalysisSource) = _state.update { it.copy(source = source) }

    fun toggleTrip(id: Long) = _state.update {
        it.copy(selectedTripIds = if (id in it.selectedTripIds) it.selectedTripIds - id else it.selectedTripIds + id)
    }

    fun selectAllVisible(all: Boolean) = _state.update { s ->
        s.copy(selectedTripIds = if (all) s.visibleTrips.map { it.id }.toSet() else emptySet())
    }

    fun setHideShortTrips(hide: Boolean) = _state.update { it.copy(hideShortTrips = hide) }

    fun setRange(from: Long, to: Long) = _state.update { it.copy(rangeFrom = from, rangeTo = to) }

    fun clearResult() = _state.update { it.copy(result = null, error = null) }

    /** Fetches every signal used by any saved layout over the selection, so switching layouts is instant. */
    fun analyse() {
        val s = _state.value
        if (!s.canAnalyse) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                val (trips, from, to) = when (s.source) {
                    AnalysisSource.TRIPS -> {
                        val picked = s.trips.filter { it.id in s.selectedTripIds }.sortedBy { it.id }
                        Triple(picked, picked.first().id, picked.maxOf { it.endedAt ?: it.updatedAt })
                    }
                    AnalysisSource.RANGE -> Triple(dao.tripsOverlapping(s.rangeFrom, s.rangeTo), s.rangeFrom, s.rangeTo)
                }
                if (trips.isEmpty()) error("No trips were recorded in that time.")
                val tiles = store.state.value.layouts.flatMap { it.portrait + it.landscape }
                val routeSignals = tiles.filter { it.type == TileType.ROUTE }.map { it.signalId }.toSet()
                val gps = if (routeSignals.isEmpty()) emptySet() else setOf(PidCatalog.ID_GPS_LATITUDE, PidCatalog.ID_GPS_LONGITUDE)
                val signals = tiles.map { it.signalId }.toSet() + gps
                withContext(Dispatchers.Default) { build(trips, signals.toList(), routeSignals, from, to, s.source) }
            }
            _state.update {
                it.copy(
                    loading = false,
                    result = outcome.getOrNull(),
                    error = outcome.exceptionOrNull()?.message,
                )
            }
        }
    }

    private suspend fun build(
        trips: List<TripEntity>,
        signals: List<Int>,
        routeSignals: Set<Int>,
        from: Long,
        to: Long,
        source: AnalysisSource,
    ): AnalysisResult {
        val merged = HashMap<Int, ArrayList<Sample>>()
        var drivingMs = 0L
        for (trip in trips) {
            val end = trip.endedAt ?: trip.updatedAt
            val fromMs = (from - trip.id).coerceAtLeast(0).toInt()
            val toMs = ((minOf(to, end) - trip.id) + 1).toInt()
            drivingMs += (minOf(to, end) - maxOf(from, trip.id)).coerceAtLeast(0)
            // Trips recorded before the current spike rules are cleaned the same way the live link is now.
            val filter = SpikeFilter(SpikeFilter.OBD_RULES)
            val series = queries.series(trip.id, signals, fromMs, toMs)
            for ((signal, points) in series) {
                val out = merged.getOrPut(signal) { ArrayList() }
                for (p in points) {
                    if (p.tMs < fromMs || p.tMs >= toMs) continue
                    if (filter.accept(signal, p.value)) out += Sample(trip.id + p.tMs, p.value)
                }
            }
        }
        merged.values.forEach { samples -> samples.sortBy { it.t } }
        val lat = merged[PidCatalog.ID_GPS_LATITUDE].orEmpty()
        val lon = merged[PidCatalog.ID_GPS_LONGITUDE].orEmpty()
        val routes = routeSignals.associateWith { signal ->
            SeriesTools.route(lat, lon, merged[signal].orEmpty(), TelemetryQueries.HOLD_MS.toLong(), MAX_GAP_MS, ROUTE_POINTS)
        }
        val bySignal = merged.mapValues { (_, samples) ->
            val stats = SeriesTools.summarizeHeld(samples, TelemetryQueries.HOLD_MS.toLong())
            val trend = SeriesTools.decimate(SeriesTools.collapseGaps(samples, MAX_GAP_MS), TREND_BUCKETS)
            stats.copy(series = trend)
        }
        return AnalysisResult(
            title = when (source) {
                AnalysisSource.TRIPS -> if (trips.size == 1) "1 trip" else "${trips.size} trips"
                AnalysisSource.RANGE -> "Time range"
            },
            tripCount = trips.size,
            drivingMinutes = drivingMs / 60_000.0,
            distanceKm = trips.sumOf { it.distanceKm },
            from = from,
            to = to,
            bySignal = bySignal,
            routes = routes,
        )
    }

    private companion object {
        const val TRIP_LIST_LIMIT = 100
        const val TREND_BUCKETS = 300
        /** Longer silences (between trips, lost connection) are squeezed out of trend charts. */
        const val MAX_GAP_MS = 30_000L
        const val ROUTE_POINTS = 1_500
    }
}
