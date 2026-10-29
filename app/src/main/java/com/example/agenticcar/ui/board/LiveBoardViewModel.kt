package com.example.agenticcar.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agenticcar.data.LiveHistory
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.protocol.obd.PidCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LiveBoardViewModel @Inject constructor(
    private val store: BoardLayoutStore,
    telemetry: TelemetryState,
    private val history: LiveHistory,
) : ViewModel() {

    val layouts: StateFlow<BoardLayoutStore.Snapshot> = store.state

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    val liveIds: StateFlow<Set<Int>> = telemetry.liveSignals.map { it.keys }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Per [Tile.dataKey], refreshed at most 4 times a second: values move in steps, trends scroll smoothly enough. */
    @OptIn(FlowPreview::class)
    val tileData: StateFlow<Map<String, TileData>> =
        combine(telemetry.liveSignals, history.version.sample(REFRESH_MS), store.state) { live, _, snapshot ->
            val now = System.currentTimeMillis()
            val layout = snapshot.active
            (layout.portrait + layout.tiles(landscapeMode = true)).distinctBy { it.dataKey }.associate { tile ->
                val window = history.window(tile.signalId, if (tile.type == TileType.TREND) TREND_WINDOW_MS else LiveHistory.WINDOW_MS)
                val series = if (tile.type == TileType.TREND) SeriesTools.decimate(window, TREND_BUCKETS) else emptyList()
                val route = if (tile.type == TileType.ROUTE) {
                    SeriesTools.route(
                        lat = history.window(PidCatalog.ID_GPS_LATITUDE),
                        lon = history.window(PidCatalog.ID_GPS_LONGITUDE),
                        color = window,
                        holdMs = HOLD_MS,
                        maxGapMs = ROUTE_GAP_MS,
                        maxPoints = ROUTE_POINTS,
                    )
                } else {
                    emptyList()
                }
                val stats = SeriesTools.summarize(window, live[tile.signalId])
                tile.dataKey to stats.copy(series = series, from = now - TREND_WINDOW_MS, to = now, route = route)
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setEditing(on: Boolean) {
        _editing.value = on
    }

    fun select(id: String) = store.select(id)

    fun replaceTiles(landscape: Boolean, tiles: List<Tile>) {
        val layout = store.state.value.active
        store.save(layout.withTiles(landscape, tiles))
    }

    /** Edits [existing] in place, or adds a new tile at the first free spot with its style's default size. */
    fun saveTile(landscape: Boolean, existing: Tile?, signalId: Int, type: TileType, min: Double?, max: Double?) {
        val layout = store.state.value.active
        val tiles = layout.tiles(landscape)
        val columns = BoardGrid.columns(landscape)
        val updated = if (existing != null) {
            tiles.map { if (it.id == existing.id) it.copy(signalId = signalId, type = type, min = min, max = max) else it }
        } else {
            val width = type.defaultWidth.coerceAtMost(columns)
            val (col, row) = BoardGrid.firstFreeSpot(tiles, width, type.defaultHeight, columns)
            tiles + Tile(BoardLayoutStore.newId(), signalId, type, col, row, width, type.defaultHeight, min, max)
        }
        store.save(layout.withTiles(landscape, updated))
    }

    fun deleteTile(landscape: Boolean, id: String) {
        val layout = store.state.value.active
        store.save(layout.withTiles(landscape, layout.tiles(landscape).filterNot { it.id == id }))
    }

    fun addTemplate(template: BoardLayout) {
        store.addTemplate(template)
    }

    fun manage(action: LayoutAction, name: String?) {
        val active = store.state.value.active
        when (action) {
            LayoutAction.NEW -> store.create(name ?: "Board")
            LayoutAction.DUPLICATE -> store.create(name ?: "${active.name} copy", from = active)
            LayoutAction.RENAME -> if (name != null) store.rename(active.id, name)
            LayoutAction.DELETE -> store.delete(active.id)
            LayoutAction.TEMPLATE -> Unit // Picked in a dialog; see [addTemplate].
        }
    }

    private companion object {
        const val REFRESH_MS = 250L
        const val TREND_WINDOW_MS = 2 * 60_000L
        const val TREND_BUCKETS = 240
        const val HOLD_MS = 20_000L
        const val ROUTE_GAP_MS = 30_000L
        const val ROUTE_POINTS = 600
    }
}
