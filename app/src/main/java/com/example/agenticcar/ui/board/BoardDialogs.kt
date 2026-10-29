package com.example.agenticcar.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.agenticcar.protocol.obd.PidCatalog

/** Signals worth putting on a tile: live ones first, then the rest of the catalogue by name. */
private fun pickableSignals(liveIds: Set<Int>): List<Int> {
    val noise = setOf(PidCatalog.ID_GPS_LATITUDE, PidCatalog.ID_GPS_LONGITUDE)
    val all = (PidCatalog.knownSignals.map { it.id } + liveIds).distinct().filterNot { it in noise }
    return all.sortedWith(compareBy({ it !in liveIds }, { SignalStyle.label(it).lowercase() }))
}

/** Adds or edits a tile: which signal, which kind of tile, and an optional fixed range. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TileEditorDialog(
    initial: Tile?,
    liveIds: Set<Int>,
    onSave: (signalId: Int, type: TileType, min: Double?, max: Double?) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var signalId by rememberSaveable { mutableStateOf(initial?.signalId ?: PidCatalog.ID_RPM) }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: TileType.GAUGE) }
    var minText by rememberSaveable { mutableStateOf(initial?.min?.toString().orEmpty()) }
    var maxText by rememberSaveable { mutableStateOf(initial?.max?.toString().orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    val signals = remember(liveIds) { pickableSignals(liveIds) }

    fun parse(text: String) = text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val minValid = minText.isBlank() || parse(minText) != null
    val maxValid = maxText.isBlank() || parse(maxText) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add tile" else "Edit tile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = SignalStyle.label(signalId) + SignalStyle.unit(signalId).let { if (it.isEmpty()) "" else " ($it)" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(if (type == TileType.ROUTE) "Colour route by" else "Signal") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        for (id in signals) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        SignalStyle.label(id) + if (id in liveIds) "  •" else "",
                                        fontWeight = if (id in liveIds) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    signalId = id
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Text("Style", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileType.entries.forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                if (type == TileType.LAMP) {
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("Lights at or above") },
                        placeholder = { Text(SignalStyle.format(signalId, SignalStyle.lampThreshold(Tile("", signalId, type, 0, 0, 1, 1)))) },
                        isError = !maxValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minText,
                            onValueChange = { minText = it },
                            label = { Text("Min") },
                            placeholder = { Text("auto") },
                            isError = !minValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = maxText,
                            onValueChange = { maxText = it },
                            label = { Text("Max") },
                            placeholder = { Text("auto") },
                            isError = !maxValid,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Remove tile", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = minValid && maxValid,
                onClick = { onSave(signalId, type, if (type == TileType.LAMP) null else parse(minText), parse(maxText)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists the ready-made boards with a miniature of their landscape arrangement. */
@Composable
fun TemplatePickerDialog(onPick: (BoardLayout) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a template") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BoardTemplates.all.forEach { template ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(template) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LayoutMiniature(template.landscape, BoardGrid.LANDSCAPE_COLUMNS, Modifier.width(96.dp))
                        Column(Modifier.weight(1f)) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                BoardTemplates.descriptions[template.id].orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tiles as small rounded blocks, coloured by style, at the grid's aspect ratio. */
@Composable
private fun LayoutMiniature(tiles: List<Tile>, columns: Int, modifier: Modifier = Modifier) {
    val rows = maxOf(BoardGrid.rowsUsed(tiles), 1)
    val scheme = MaterialTheme.colorScheme
    fun color(type: TileType) = when (type) {
        TileType.GAUGE -> scheme.primary
        TileType.TREND -> scheme.secondary
        TileType.ROUTE -> scheme.tertiary
        TileType.LAMP -> scheme.error
        TileType.VALUE, TileType.BAR -> scheme.outline
    }
    Canvas(modifier.aspectRatio(columns.toFloat() / rows)) {
        val cell = size.width / columns
        val gap = cell * 0.08f
        tiles.forEach { t ->
            drawRoundRect(
                color = color(t.type).copy(alpha = 0.85f),
                topLeft = Offset(t.col * cell + gap, t.row * cell + gap),
                size = Size(t.width * cell - 2 * gap, t.height * cell - 2 * gap),
                cornerRadius = CornerRadius(cell * 0.18f),
            )
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Layout name as a dropdown: pick a saved layout and, where [onManage] is given, create/duplicate/rename/delete. */
@Composable
fun LayoutPicker(
    snapshot: BoardLayoutStore.Snapshot,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onManage: ((LayoutAction) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { open = true }) {
            Text(snapshot.active.name + "  ▾", style = MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            snapshot.layouts.forEach { layout ->
                DropdownMenuItem(
                    text = {
                        Text(layout.name, fontWeight = if (layout.id == snapshot.activeId) FontWeight.SemiBold else FontWeight.Normal)
                    },
                    onClick = {
                        onSelect(layout.id)
                        open = false
                    },
                )
            }
            if (onManage != null) {
                HorizontalDivider()
                LayoutAction.entries.forEach { action ->
                    if (action == LayoutAction.DELETE && snapshot.layouts.size <= 1) return@forEach
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            onManage(action)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

enum class LayoutAction(val label: String) {
    NEW("New layout"),
    TEMPLATE("Add a template…"),
    DUPLICATE("Save as copy…"),
    RENAME("Rename…"),
    DELETE("Delete this layout"),
}
