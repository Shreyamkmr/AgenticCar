package com.example.agenticcar.ui.board

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Which dialog is open; tile dialogs carry the tile id ("" for a new tile). */
private sealed interface BoardDialog {
    data class EditTile(val tileId: String) : BoardDialog
    data class Name(val action: LayoutAction) : BoardDialog
    data object Templates : BoardDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveBoardRoute(modifier: Modifier = Modifier, viewModel: LiveBoardViewModel = hiltViewModel()) {
    val snapshot by viewModel.layouts.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val tileData by viewModel.tileData.collectAsStateWithLifecycle()
    val liveIds by viewModel.liveIds.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val tiles = snapshot.active.tiles(landscape)
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val open = dialog?.let(::decodeDialog)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                // Slimmer when sideways, so a three-row board fits without scrolling.
                expandedHeight = if (landscape) 48.dp else TopAppBarDefaults.TopAppBarExpandedHeight,
                title = {
                    LayoutPicker(
                        snapshot = snapshot,
                        onSelect = viewModel::select,
                        onManage = { action ->
                            when (action) {
                                LayoutAction.DELETE -> viewModel.manage(action, null)
                                LayoutAction.TEMPLATE -> dialog = encodeDialog(BoardDialog.Templates)
                                else -> dialog = encodeDialog(BoardDialog.Name(action))
                            }
                        },
                    )
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = { dialog = encodeDialog(BoardDialog.EditTile("")) }) { Text("Add tile") }
                        Button(onClick = { viewModel.setEditing(false) }) { Text("Done") }
                    } else {
                        TextButton(onClick = { viewModel.setEditing(true) }) { Text("Edit") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hint = when {
                editing -> "Long-press a tile and drag to move it · drag its corner to resize · tap it to change it. " +
                    (if (landscape) "Landscape" else "Portrait") + " has its own arrangement."
                liveIds.isEmpty() -> "Not connected: connect to the car on the Logger tab to see live values."
                else -> null
            }
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            BoardGridView(
                tiles = tiles,
                columns = BoardGrid.columns(landscape),
                editing = editing,
                onTilesChange = { viewModel.replaceTiles(landscape, it) },
                onTileClick = { dialog = encodeDialog(BoardDialog.EditTile(it.id)) },
            ) { tile ->
                TileView(tile, tileData[tile.dataKey] ?: TileData.EMPTY, TileMode.LIVE)
            }
        }
    }

    when (open) {
        is BoardDialog.EditTile -> {
            val existing = tiles.firstOrNull { it.id == open.tileId }
            TileEditorDialog(
                initial = existing,
                liveIds = liveIds,
                onSave = { signal, type, min, max ->
                    viewModel.saveTile(landscape, existing, signal, type, min, max)
                    dialog = null
                },
                onDelete = existing?.let {
                    {
                        viewModel.deleteTile(landscape, it.id)
                        dialog = null
                    }
                },
                onDismiss = { dialog = null },
            )
        }
        is BoardDialog.Name -> NameDialog(
            title = open.action.label.trimEnd('…'),
            initial = when (open.action) {
                LayoutAction.RENAME -> snapshot.active.name
                LayoutAction.DUPLICATE -> "${snapshot.active.name} copy"
                else -> "Board ${snapshot.layouts.size + 1}"
            },
            onConfirm = {
                viewModel.manage(open.action, it)
                if (open.action == LayoutAction.NEW) viewModel.setEditing(true)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        BoardDialog.Templates -> TemplatePickerDialog(
            onPick = {
                viewModel.addTemplate(it)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

// The open dialog survives rotation as a plain string.
private fun encodeDialog(d: BoardDialog): String = when (d) {
    is BoardDialog.EditTile -> "tile:${d.tileId}"
    is BoardDialog.Name -> "name:${d.action.name}"
    BoardDialog.Templates -> "templates"
}

private fun decodeDialog(s: String): BoardDialog? = when {
    s.startsWith("tile:") -> BoardDialog.EditTile(s.removePrefix("tile:"))
    s.startsWith("name:") -> runCatching { BoardDialog.Name(LayoutAction.valueOf(s.removePrefix("name:"))) }.getOrNull()
    s == "templates" -> BoardDialog.Templates
    else -> null
}
