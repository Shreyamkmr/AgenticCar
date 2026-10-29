package com.example.agenticcar.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private enum class DragMode { MOVE, RESIZE }

private data class DragState(val tileId: String, val mode: DragMode, val offset: Offset)

private val TileShape = RoundedCornerShape(16.dp)
private val TileGap = 4.dp

/** Tallest a tile may be resized to, in cells. */
private const val MAX_TILE_HEIGHT = 8

/**
 * Lays [tiles] out on a grid of [columns] square cells. While [editing]: long-press and drag a tile
 * to move it, drag its corner handle to resize it, tap it to configure it. Moves and resizes snap to
 * cells; a ghost shows the target (outlined in the error colour when it would overlap), and a drop
 * on an occupied or off-grid target is undone.
 */
@Composable
fun BoardGridView(
    tiles: List<Tile>,
    columns: Int,
    editing: Boolean,
    onTilesChange: (List<Tile>) -> Unit,
    onTileClick: (Tile) -> Unit,
    modifier: Modifier = Modifier,
    tileContent: @Composable (Tile) -> Unit,
) {
    val currentTiles by rememberUpdatedState(tiles)
    val currentOnChange by rememberUpdatedState(onTilesChange)
    var drag by remember { mutableStateOf<DragState?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cell = maxWidth / columns
        val cellPx = with(LocalDensity.current) { cell.toPx() }
        // Spare rows while editing give tiles somewhere to go.
        val rows = maxOf(BoardGrid.rowsUsed(tiles), 1) + if (editing) 2 else 0

        fun target(tile: Tile, state: DragState): Tile = when (state.mode) {
            DragMode.MOVE -> tile.copy(
                col = ((tile.col * cellPx + state.offset.x) / cellPx).roundToInt().coerceIn(0, columns - tile.width),
                row = ((tile.row * cellPx + state.offset.y) / cellPx).roundToInt().coerceAtLeast(0),
            )
            DragMode.RESIZE -> tile.copy(
                width = ((tile.width * cellPx + state.offset.x) / cellPx).roundToInt().coerceIn(1, columns - tile.col),
                height = ((tile.height * cellPx + state.offset.y) / cellPx).roundToInt().coerceIn(1, MAX_TILE_HEIGHT),
            )
        }

        fun commit() {
            val state = drag ?: return
            drag = null
            val tile = currentTiles.firstOrNull { it.id == state.tileId } ?: return
            val moved = target(tile, state)
            if (moved != tile && BoardGrid.canPlace(currentTiles, moved, columns)) {
                currentOnChange(currentTiles.map { if (it.id == moved.id) moved else it })
            }
        }

        Box(Modifier.fillMaxWidth().height(cell * rows)) {
            if (editing) GridDots(columns = columns, rows = rows, Modifier.fillMaxSize())

            drag?.let { state ->
                val tile = tiles.firstOrNull { it.id == state.tileId } ?: return@let
                val ghost = target(tile, state)
                val ok = BoardGrid.canPlace(tiles, ghost, columns)
                Box(
                    Modifier
                        .offset(cell * ghost.col, cell * ghost.row)
                        .size(cell * ghost.width, cell * ghost.height)
                        .padding(TileGap)
                        .border(2.dp, if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, TileShape),
                )
            }

            for (tile in tiles) {
                key(tile.id) {
                    val state = drag?.takeIf { it.tileId == tile.id }
                    val move = if (state?.mode == DragMode.MOVE) state.offset else Offset.Zero
                    val grow = if (state?.mode == DragMode.RESIZE) state.offset else Offset.Zero
                    val density = LocalDensity.current
                    val widthDp = with(density) { (tile.width * cellPx + grow.x).coerceAtLeast(cellPx).toDp() }
                    val heightDp = with(density) { (tile.height * cellPx + grow.y).coerceAtLeast(cellPx).toDp() }

                    Box(
                        Modifier
                            .offset { IntOffset((tile.col * cellPx + move.x).roundToInt(), (tile.row * cellPx + move.y).roundToInt()) }
                            .size(widthDp, heightDp)
                            .zIndex(if (state != null) 1f else 0f)
                            .padding(TileGap)
                            .clip(TileShape)
                            .then(
                                if (editing) {
                                    Modifier
                                        .pointerInput(tile.id, cellPx, columns) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { drag = DragState(tile.id, DragMode.MOVE, Offset.Zero) },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag = drag?.let { it.copy(offset = it.offset + amount) }
                                                },
                                                onDragEnd = { commit() },
                                                onDragCancel = { drag = null },
                                            )
                                        }
                                        .clickable { onTileClick(tile) }
                                        .border(1.dp, MaterialTheme.colorScheme.outline, TileShape)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        tileContent(tile)
                        if (editing) {
                            ResizeHandle(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .pointerInput(tile.id, cellPx, columns) {
                                        detectDragGestures(
                                            onDragStart = { drag = DragState(tile.id, DragMode.RESIZE, Offset.Zero) },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                drag = drag?.let { it.copy(offset = it.offset + amount) }
                                            },
                                            onDragEnd = { commit() },
                                            onDragCancel = { drag = null },
                                        )
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridDots(columns: Int, rows: Int, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val cell = size.width / columns
        for (r in 0..rows) {
            for (c in 0..columns) {
                drawCircle(color, radius = 2.dp.toPx(), center = Offset(c * cell, r * cell))
            }
        }
    }
}

@Composable
private fun ResizeHandle(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(28.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(topStart = 12.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(7.dp)) {
            val w = 2.dp.toPx()
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
            drawLine(color, Offset(size.width, size.height * 0.5f), Offset(size.width * 0.5f, size.height), w, StrokeCap.Round)
        }
    }
}
