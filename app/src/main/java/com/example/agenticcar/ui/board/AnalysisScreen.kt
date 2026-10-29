package com.example.agenticcar.ui.board

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agenticcar.data.db.TripEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault())
private val dateOnly = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun local(epochMs: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
private fun epoch(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisRoute(modifier: Modifier = Modifier, viewModel: AnalysisViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot by viewModel.layouts.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val result = state.result

    // New trips may have been recorded while another tab was open.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshTrips()
        onPauseOrDispose { }
    }
    BackHandler(enabled = result != null) { viewModel.clearResult() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Analysis") },
                actions = { LayoutPicker(snapshot = snapshot, onSelect = viewModel::selectLayout) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result == null) {
                SelectionPane(state = state, viewModel = viewModel)
            } else {
                ResultHeader(result = result, onChange = viewModel::clearResult)
                val tiles = snapshot.active.tiles(landscape)
                BoardGridView(
                    tiles = tiles,
                    columns = BoardGrid.columns(landscape),
                    editing = false,
                    onTilesChange = {},
                    onTileClick = {},
                ) { tile ->
                    TileView(tile, result.data(tile), TileMode.ANALYSIS)
                }
                Text(
                    "Tiles reuse your saved layout; edit it on the Board tab. Values are averages over the selection, " +
                        "with the minimum–maximum band; lamps show whether they lit at any point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionPane(state: AnalysisUiState, viewModel: AnalysisViewModel) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        AnalysisSource.entries.forEachIndexed { i, source ->
            SegmentedButton(
                selected = state.source == source,
                onClick = { viewModel.setSource(source) },
                shape = SegmentedButtonDefaults.itemShape(i, AnalysisSource.entries.size),
            ) { Text(if (source == AnalysisSource.TRIPS) "Trips" else "Time range") }
        }
    }

    when (state.source) {
        AnalysisSource.TRIPS -> TripPicker(state, viewModel)
        AnalysisSource.RANGE -> RangePicker(state, viewModel)
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = viewModel::analyse, enabled = state.canAnalyse) { Text("Analyse") }
        if (state.loading) CircularProgressIndicator(Modifier.padding(4.dp))
    }
}

@Composable
private fun TripPicker(state: AnalysisUiState, viewModel: AnalysisViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Hide trips under a minute", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = state.hideShortTrips, onCheckedChange = viewModel::setHideShortTrips)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.selectAllVisible(true) }) { Text("Select all") }
        OutlinedButton(onClick = { viewModel.selectAllVisible(false) }, enabled = state.selectedTripIds.isNotEmpty()) { Text("Clear") }
    }
    if (state.visibleTrips.isEmpty()) {
        Text("No trips recorded yet.", style = MaterialTheme.typography.bodyMedium)
    }
    // At most 100 rows: a plain Column inside the page scroll is fine.
    Card(Modifier.fillMaxWidth()) {
        state.visibleTrips.forEach { trip ->
            TripRow(trip, selected = trip.id in state.selectedTripIds, onToggle = { viewModel.toggleTrip(trip.id) })
        }
    }
}

@Composable
private fun TripRow(trip: TripEntity, selected: Boolean, onToggle: () -> Unit) {
    val minutes = AnalysisUiState.durationMs(trip) / 60_000.0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(local(trip.id).format(dayTime), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                String.format(Locale.getDefault(), "%.1f min · %.2f km%s", minutes, trip.distanceKm, trip.maxSpeedKmh?.let { " · max %.0f km/h".format(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RangePicker(state: AnalysisUiState, viewModel: AnalysisViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { viewModel.setRange(System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis()) }, label = { Text("Last hour") })
        AssistChip(onClick = { viewModel.setRange(AnalysisUiState.startOfDay(0), System.currentTimeMillis()) }, label = { Text("Today") })
        AssistChip(onClick = { viewModel.setRange(AnalysisUiState.startOfDay(1), AnalysisUiState.startOfDay(0)) }, label = { Text("Yesterday") })
        AssistChip(onClick = { viewModel.setRange(AnalysisUiState.startOfDay(6), System.currentTimeMillis()) }, label = { Text("Last 7 days") })
    }
    DateTimeRow("From", state.rangeFrom) { viewModel.setRange(it, state.rangeTo) }
    DateTimeRow("To", state.rangeTo) { viewModel.setRange(state.rangeFrom, it) }
    if (state.rangeTo <= state.rangeFrom) {
        Text("“To” must be after “From”.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** A labelled date button and time button for one end of the range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(label: String, epochMs: Long, onChange: (Long) -> Unit) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val current = local(epochMs)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { picking = "date" }) { Text(current.format(dateOnly)) }
        OutlinedButton(onClick = { picking = "time" }) { Text(current.format(timeOnly)) }
    }
    when (picking) {
        "date" -> {
            // The date picker works in UTC midnights.
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = current.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { utc ->
                            val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                            onChange(epoch(LocalDateTime.of(date, current.toLocalTime())))
                        }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) { DatePicker(pickerState) }
        }
        "time" -> {
            val timeState = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        onChange(epoch(LocalDateTime.of(current.toLocalDate(), LocalTime.of(timeState.hour, timeState.minute))))
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
                text = { TimePicker(timeState) },
            )
        }
    }
}

@Composable
private fun ResultHeader(result: AnalysisResult, onChange: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${local(result.from).format(dayTime)} → ${local(result.to).format(if (local(result.from).toLocalDate() == local(result.to).toLocalDate()) timeOnly else dayTime)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    String.format(Locale.getDefault(), "%d trip%s · %.1f min logged · %.1f km", result.tripCount, if (result.tripCount == 1) "" else "s", result.drivingMinutes, result.distanceKm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onChange) { Text("Change") }
        }
    }
}

