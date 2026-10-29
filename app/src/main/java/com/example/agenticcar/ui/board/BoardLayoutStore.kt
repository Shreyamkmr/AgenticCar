package com.example.agenticcar.ui.board

import android.content.Context
import android.util.Log
import com.example.agenticcar.protocol.obd.PidCatalog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Saved board layouts, shared by the live board and the analysis page. */
@Singleton
class BoardLayoutStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(val layouts: List<BoardLayout>, val activeId: String) {
        val active: BoardLayout get() = layouts.firstOrNull { it.id == activeId } ?: layouts.first()
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun select(id: String) = update { it.copy(activeId = id) }

    fun save(layout: BoardLayout) = update { s ->
        val replaced = s.layouts.map { if (it.id == layout.id) layout else it }
        s.copy(layouts = if (replaced.any { it.id == layout.id }) replaced else replaced + layout)
    }

    /** Creates a layout (a copy of [from], or the default tiles) and makes it active. */
    fun create(name: String, from: BoardLayout? = null): BoardLayout {
        val layout = (from ?: defaultLayout()).copy(id = newId(), name = name)
        update { it.copy(layouts = it.layouts + layout, activeId = layout.id) }
        return layout
    }

    fun rename(id: String, name: String) = update { s -> s.copy(layouts = s.layouts.map { if (it.id == id) it.copy(name = name) else it }) }

    /** The last layout cannot be deleted. */
    fun delete(id: String) = update { s ->
        if (s.layouts.size <= 1) return@update s
        val rest = s.layouts.filterNot { it.id == id }
        s.copy(layouts = rest, activeId = if (s.activeId == id) rest.first().id else s.activeId)
    }

    private fun update(change: (Snapshot) -> Snapshot) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit().putString(KEY, BoardJson.encode(next.layouts, next.activeId)).apply()
    }

    /** Adds a fresh copy of [template] (new ids, so it can be added again) and makes it active. */
    fun addTemplate(template: BoardLayout): BoardLayout {
        val copy = template.copy(id = newId())
        update { it.copy(layouts = it.layouts + copy, activeId = copy.id) }
        return copy
    }

    private fun load(): Snapshot {
        val stored = prefs.getString(KEY, null)?.let { text ->
            runCatching { BoardJson.decode(text) }.onFailure { Log.w(TAG, "Unreadable board layouts, starting fresh", it) }.getOrNull()
        }
        var layouts = stored?.first?.takeIf { it.isNotEmpty() } ?: listOf(defaultLayout())
        val active = stored?.second?.takeIf { id -> layouts.any { it.id == id } } ?: layouts.first().id
        // Templates are added once, after the user's own layouts; deleting one later is respected.
        if (prefs.getInt(KEY_TEMPLATES_SEEDED, 0) < TEMPLATES_VERSION) {
            layouts = layouts + BoardTemplates.all.filter { t -> layouts.none { it.id == t.id } }
            prefs.edit()
                .putString(KEY, BoardJson.encode(layouts, active))
                .putInt(KEY_TEMPLATES_SEEDED, TEMPLATES_VERSION)
                .apply()
        }
        return Snapshot(layouts, active)
    }

    companion object {
        private const val TAG = "BoardLayoutStore"
        private const val PREFS = "board_layouts"
        private const val KEY = "layouts_v1"
        private const val KEY_TEMPLATES_SEEDED = "templates_seeded"

        /** Bump when new templates should be offered to existing installs. */
        private const val TEMPLATES_VERSION = 1

        fun newId(): String = UUID.randomUUID().toString().take(8)

        /** A sensible first board: the headline engine values, a trend and the warning lamps. */
        fun defaultLayout(): BoardLayout {
            fun t(signal: Int, type: TileType, col: Int, row: Int, w: Int, h: Int) = Tile(newId(), signal, type, col, row, w, h)
            return BoardLayout(
                id = newId(),
                name = "Main",
                portrait = listOf(
                    t(PidCatalog.ID_RPM, TileType.GAUGE, 0, 0, 2, 2),
                    t(PidCatalog.ID_SPEED, TileType.GAUGE, 2, 0, 2, 2),
                    t(PidCatalog.ID_RPM, TileType.TREND, 0, 2, 4, 2),
                    t(PidCatalog.ID_COOLANT, TileType.VALUE, 0, 4, 2, 1),
                    t(PidCatalog.ADAPTER_VOLTAGE_ID, TileType.VALUE, 2, 4, 2, 1),
                    t(PidCatalog.ID_ENGINE_LOAD, TileType.BAR, 0, 5, 2, 1),
                    t(PidCatalog.ID_THROTTLE, TileType.BAR, 2, 5, 2, 1),
                    t(PidCatalog.ID_MIL, TileType.LAMP, 0, 6, 1, 1),
                    t(PidCatalog.ID_DTC_COUNT, TileType.LAMP, 1, 6, 1, 1),
                    t(PidCatalog.ID_COOLANT, TileType.LAMP, 2, 6, 1, 1),
                    t(PidCatalog.ID_SPEED, TileType.ROUTE, 0, 7, 4, 3),
                    t(PidCatalog.ID_SPEED, TileType.TREND, 0, 10, 4, 2),
                ),
            )
        }
    }
}
