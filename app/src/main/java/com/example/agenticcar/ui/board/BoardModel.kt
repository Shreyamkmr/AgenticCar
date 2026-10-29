package com.example.agenticcar.ui.board

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class TileType(val label: String, val defaultWidth: Int, val defaultHeight: Int) {
    GAUGE("Gauge", 2, 2),
    TREND("Trend", 4, 2),
    VALUE("Value", 2, 1),
    BAR("Bar", 2, 1),

    /** A warning-light symbol that lights when the value reaches [Tile.max]. */
    LAMP("Lamp", 1, 1),

    /** The GPS track, coloured by the tile's signal. */
    ROUTE("Route", 4, 3),
}

/**
 * One tile on the board grid, in cells. [min]/[max] override the signal's default display range
 * (for [TileType.LAMP], [max] is the threshold that lights it); null means automatic.
 */
data class Tile(
    val id: String,
    val signalId: Int,
    val type: TileType,
    val col: Int,
    val row: Int,
    val width: Int,
    val height: Int,
    val min: Double? = null,
    val max: Double? = null,
) {
    /** What the tile shows depends only on its signal and style, so tiles sharing both share data. */
    val dataKey: String get() = "$signalId:${type.name}"

    val right: Int get() = col + width
    val bottom: Int get() = row + height

    fun overlaps(other: Tile): Boolean =
        col < other.right && other.col < right && row < other.bottom && other.row < bottom
}

/** A named board with separate arrangements for portrait and landscape. */
data class BoardLayout(
    val id: String,
    val name: String,
    val portrait: List<Tile>,
    /** Empty until the user arranges landscape; then derived from [portrait] by [BoardGrid.reflow]. */
    val landscape: List<Tile> = emptyList(),
) {
    fun tiles(landscapeMode: Boolean): List<Tile> =
        if (!landscapeMode) portrait else landscape.ifEmpty { BoardGrid.reflow(portrait, BoardGrid.LANDSCAPE_COLUMNS) }

    fun withTiles(landscapeMode: Boolean, tiles: List<Tile>): BoardLayout =
        if (landscapeMode) copy(landscape = tiles) else copy(portrait = tiles)
}

/** Pure grid arithmetic: placement checks, snapping targets and reflow between column counts. */
object BoardGrid {
    const val PORTRAIT_COLUMNS = 4
    const val LANDSCAPE_COLUMNS = 8

    fun columns(landscape: Boolean) = if (landscape) LANDSCAPE_COLUMNS else PORTRAIT_COLUMNS

    /** Whether [candidate] lies inside the grid and overlaps no other tile (itself excluded by id). */
    fun canPlace(tiles: List<Tile>, candidate: Tile, columns: Int): Boolean =
        candidate.col >= 0 && candidate.row >= 0 && candidate.width >= 1 && candidate.height >= 1 &&
            candidate.right <= columns &&
            tiles.none { it.id != candidate.id && it.overlaps(candidate) }

    /** Top-most, then left-most cell where a [width]×[height] tile fits. */
    fun firstFreeSpot(tiles: List<Tile>, width: Int, height: Int, columns: Int): Pair<Int, Int> {
        val w = width.coerceIn(1, columns)
        var row = 0
        while (true) {
            for (col in 0..columns - w) {
                val probe = Tile("", 0, TileType.VALUE, col, row, w, height)
                if (canPlace(tiles, probe, columns)) return col to row
            }
            row++
        }
    }

    /** Re-packs [tiles] in reading order into a grid of [columns], keeping sizes where they fit. */
    fun reflow(tiles: List<Tile>, columns: Int): List<Tile> {
        val out = ArrayList<Tile>(tiles.size)
        for (t in tiles.sortedWith(compareBy({ it.row }, { it.col }))) {
            val w = t.width.coerceAtMost(columns)
            val (col, row) = firstFreeSpot(out, w, t.height, columns)
            out += t.copy(col = col, row = row, width = w)
        }
        return out
    }

    fun rowsUsed(tiles: List<Tile>): Int = tiles.maxOfOrNull { it.bottom } ?: 0
}

/** JSON codec for layouts, kept by hand so the build needs no serialization plugin. */
object BoardJson {
    private const val VERSION = 1

    fun encode(layouts: List<BoardLayout>, activeId: String?): String = buildJsonObject {
        put("version", VERSION)
        activeId?.let { put("active", it) }
        put("layouts", buildJsonArray { layouts.forEach { add(layout(it)) } })
    }.toString()

    /** Returns the layouts and the active id; malformed tiles are skipped rather than failing the whole board. */
    fun decode(text: String): Pair<List<BoardLayout>, String?> {
        val root = Json.parseToJsonElement(text).jsonObject
        val layouts = root["layouts"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                BoardLayout(
                    id = o.string("id")!!,
                    name = o.string("name") ?: "Board",
                    portrait = tiles(o["portrait"] as? JsonArray),
                    landscape = tiles(o["landscape"] as? JsonArray),
                )
            }.getOrNull()
        }
        return layouts to root.string("active")
    }

    private fun layout(l: BoardLayout) = buildJsonObject {
        put("id", l.id)
        put("name", l.name)
        put("portrait", buildJsonArray { l.portrait.forEach { add(tile(it)) } })
        put("landscape", buildJsonArray { l.landscape.forEach { add(tile(it)) } })
    }

    private fun tile(t: Tile) = buildJsonObject {
        put("id", t.id)
        put("signal", t.signalId)
        put("type", t.type.name)
        put("col", t.col)
        put("row", t.row)
        put("w", t.width)
        put("h", t.height)
        t.min?.let { put("min", it) }
        t.max?.let { put("max", it) }
    }

    private fun tiles(array: JsonArray?): List<Tile> = array.orEmpty().mapNotNull { element ->
        runCatching {
            val o = element.jsonObject
            Tile(
                id = o.string("id")!!,
                signalId = o.int("signal")!!,
                type = TileType.valueOf(o.string("type")!!),
                col = o.int("col")!!,
                row = o.int("row")!!,
                width = o.int("w")!!,
                height = o.int("h")!!,
                min = (o["min"] as? JsonPrimitive)?.doubleOrNull,
                max = (o["max"] as? JsonPrimitive)?.doubleOrNull,
            )
        }.getOrNull()
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
}
