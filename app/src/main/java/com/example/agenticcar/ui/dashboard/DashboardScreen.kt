package com.example.agenticcar.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import com.example.agenticcar.ui.theme.AppearanceCard
import com.example.agenticcar.ui.theme.AppearanceViewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agenticcar.R
import com.example.agenticcar.data.McpServerState
import com.example.agenticcar.data.ObdConnectionState
import com.example.agenticcar.data.RecorderStatus
import com.example.agenticcar.data.VehicleSettings
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.agenticcar.protocol.mcp.TunnelState
import com.example.agenticcar.protocol.obd.PidCatalog
import com.example.agenticcar.service.BondedDevice

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Asked for together with the required ones, but recording works without them (no GPS track). */
private val optionalPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasAllPermissions(): Boolean = requiredPermissions().all { granted(it) }

private fun Context.hasLocationPermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun DashboardRoute(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onEvent(DashboardEvent.PermissionsChanged(context.hasAllPermissions(), context.hasLocationPermission()))
    }
    // Re-check on every resume: the user may grant or revoke from system settings.
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(DashboardEvent.PermissionsChanged(context.hasAllPermissions(), context.hasLocationPermission()))
        onPauseOrDispose { }
    }

    // Two screens only: a saveable flag is simpler than a navigation graph.
    var showPublicAccess by rememberSaveable { mutableStateOf(false) }
    if (showPublicAccess) {
        BackHandler { showPublicAccess = false }
        PublicAccessScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onBack = { showPublicAccess = false },
            modifier = modifier,
        )
        return
    }

    DashboardScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onRequestPermissions = { launcher.launch(requiredPermissions() + optionalPermissions) },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onOpenPublicAccess = { showPublicAccess = true },
        modifier = modifier,
        appearanceSlot = {
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            val appearance by appearanceViewModel.appearance.collectAsStateWithLifecycle()
            AppearanceCard(appearance = appearance, onChange = appearanceViewModel::update)
        },
    )
}

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPublicAccess: () -> Unit,
    modifier: Modifier = Modifier,
    appearanceSlot: @Composable () -> Unit = {},
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroHeader(state = state)

            if (!state.permissionsGranted) {
                PermissionCard(onRequestPermissions = onRequestPermissions, onOpenSettings = onOpenSettings)
            }
            ConnectionCard(state = state, onEvent = onEvent)
            GaugesCard(state = state)
            if (state.recorder.sessionActive) {
                RecordingCard(
                    recorder = state.recorder,
                    signals = state.liveSignals,
                    locationGranted = state.locationGranted,
                    onRequestPermissions = onRequestPermissions,
                )
            }
            McpCard(state = state, onEvent = onEvent, onOpenPublicAccess = onOpenPublicAccess)
            VehicleCard(displacementL = state.engineDisplacementL, onSave = { onEvent(DashboardEvent.EngineSizeSaved(it)) })
            if (state.liveSignals.isNotEmpty()) LiveSignalsCard(signals = state.liveSignals)
            appearanceSlot()
            DeveloperCard(enabled = state.developerMode, onToggle = { onEvent(DashboardEvent.DeveloperModeToggled(it)) })
        }
    }
}

/** Brand banner: the app name on a primary-to-tertiary gradient, with the link and trip status. */
@Composable
private fun HeroHeader(state: DashboardUiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val brush = Brush.linearGradient(listOf(scheme.primary, scheme.secondary, scheme.tertiary))
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(brush)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = scheme.onPrimary)
            Text(state.obd.label(), style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimary.copy(alpha = 0.85f))
            val tripActive = state.metrics.tripActive
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(scheme.onPrimary.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(color = if (tripActive) Color(0xFF2EE6A8) else scheme.onPrimary.copy(alpha = 0.5f))
                Text(
                    stringResource(if (tripActive) R.string.trip_active else R.string.trip_inactive),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permissions_rationale), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestPermissions) { Text(stringResource(R.string.action_grant)) }
                OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_open_settings)) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(state: DashboardUiState, onEvent: (DashboardEvent) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color = state.obd.color())
                Text(state.obd.label(), style = MaterialTheme.typography.titleMedium)
            }
            DevicePicker(
                devices = state.devices,
                selectedAddress = state.selectedDeviceAddress,
                enabled = state.permissionsGranted && !state.obdActive,
                onSelected = { onEvent(DashboardEvent.DeviceSelected(it)) },
            )
            if (state.obdActive) {
                OutlinedButton(onClick = { onEvent(DashboardEvent.DisconnectClicked) }) {
                    Text(stringResource(R.string.action_disconnect))
                }
            } else {
                Button(
                    onClick = { onEvent(DashboardEvent.ConnectClicked) },
                    enabled = state.permissionsGranted && state.selectedDeviceAddress != null,
                ) { Text(stringResource(R.string.action_connect)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(
    devices: List<BondedDevice>,
    selectedAddress: String?,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = devices.firstOrNull { it.address == selectedAddress }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.name} (${it.address})" } ?: stringResource(R.string.device_none),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.device_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Bonded devices are a small, bounded list: a plain loop, no lazy layout.
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("${device.name} (${device.address})") },
                    onClick = {
                        onSelected(device.address)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GaugesCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val m = state.metrics
    val dash = stringResource(R.string.value_unknown)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ArcGauge(
                    value = m.rpm?.toFloat(),
                    maxValue = MAX_RPM,
                    valueText = m.rpm?.toString() ?: dash,
                    label = stringResource(R.string.gauge_rpm),
                    modifier = Modifier.weight(1f),
                )
                ArcGauge(
                    value = m.speedKmh?.toFloat(),
                    maxValue = MAX_SPEED_KMH,
                    valueText = m.speedKmh?.toString() ?: dash,
                    label = stringResource(R.string.gauge_speed),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.metric_coolant, m.coolantTempCelsius?.toString() ?: dash)) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.metric_load, m.engineLoadPercent?.let { "%.0f".format(it) } ?: dash)) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.metric_voltage, m.batteryVoltage?.let { "%.1f".format(it) } ?: dash)) },
                )
            }
            Text(
                text = stringResource(if (m.tripActive) R.string.trip_active else R.string.trip_inactive),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.tripActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun McpCard(
    state: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onOpenPublicAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = state.wifiIpv4?.let { stringResource(R.string.network_wifi_ip, it) }
                            ?: stringResource(R.string.network_no_wifi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.mcpEnabled,
                    // Without Wi-Fi the server still runs on 127.0.0.1 for the public tunnel.
                    enabled = state.permissionsGranted,
                    onCheckedChange = { onEvent(DashboardEvent.McpToggled(it)) },
                )
            }
            when (val mcp = state.mcp) {
                McpServerState.Stopped -> Unit
                McpServerState.Starting -> Text(stringResource(R.string.mcp_starting))
                McpServerState.WaitingForWifi -> Text(stringResource(R.string.mcp_waiting_wifi))
                is McpServerState.Error -> Text(stringResource(R.string.mcp_error, mcp.message), color = MaterialTheme.colorScheme.error)
                is McpServerState.Running -> if (mcp.url != null) {
                    Text(stringResource(R.string.mcp_active_at, mcp.url), style = MaterialTheme.typography.bodyLarge)
                    SelectionContainer {
                        Text(
                            text = stringResource(R.string.mcp_client_hint, mcp.url),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Text(
                        text = stringResource(R.string.mcp_token_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.mcp_no_wifi), style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.public_access_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = publicSummary(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.mcpAccess?.publicEnabled == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onOpenPublicAccess) { Text(stringResource(R.string.action_set_up)) }
            }
        }
    }
}

@Composable
private fun publicSummary(state: DashboardUiState): String = when {
    state.mcpAccess?.publicEnabled != true -> stringResource(R.string.public_access_off)
    else -> when (state.tunnel) {
        is TunnelState.Running -> stringResource(R.string.tunnel_running_short)
        TunnelState.Starting -> stringResource(R.string.tunnel_starting)
        is TunnelState.Error -> stringResource(R.string.tunnel_error_short)
        TunnelState.Unavailable -> stringResource(R.string.tunnel_unavailable)
        TunnelState.Off -> stringResource(R.string.tunnel_waiting_mcp)
    }
}

@Composable
private fun RecordingCard(
    recorder: RecorderStatus,
    signals: Map<Int, Double>,
    locationGranted: Boolean,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.recording_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.recording_session,
                    recorder.supportedSignals,
                    recorder.batchSize,
                    recorder.protocol ?: stringResource(R.string.value_unknown),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            recorder.vin?.let { Text(stringResource(R.string.recording_vin, it), style = MaterialTheme.typography.bodyMedium) }
            if (recorder.tripId != null) {
                Text(
                    text = stringResource(
                        R.string.recording_trip,
                        recorder.tripReadings,
                        recorder.tripStoredSamples,
                        recorder.tripStoredBytes / 1024f,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(stringResource(R.string.recording_waiting_engine), style = MaterialTheme.typography.bodyMedium)
            }
            GpsLine(signals = signals, locationGranted = locationGranted, onRequestPermissions = onRequestPermissions)
            val dtcs = recorder.dtcStored + recorder.dtcPending.map { "$it*" }
            if (dtcs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.recording_dtcs, dtcs.distinct().joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VehicleCard(displacementL: Double?, onSave: (Double?) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(displacementL) { mutableStateOf(displacementL?.toString().orEmpty()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = text.isBlank() || (parsed != null && parsed in VehicleSettings.MIN_L..VehicleSettings.MAX_L)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.vehicle_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.vehicle_engine_size_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.vehicle_engine_size_label)) },
                    isError = !valid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSave(if (text.isBlank()) null else parsed) },
                    enabled = valid && (if (text.isBlank()) null else parsed) != displacementL,
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun DeveloperCard(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.developer_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.developer_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun GpsLine(signals: Map<Int, Double>, locationGranted: Boolean, onRequestPermissions: () -> Unit) {
    val altitude = signals[PidCatalog.ID_GPS_ALTITUDE]
    when {
        altitude != null -> {
            val grade = signals[PidCatalog.ID_ROAD_GRADE]
            val accuracy = signals[PidCatalog.ID_GPS_ACCURACY]
            Text(
                text = stringResource(
                    R.string.gps_status,
                    "%.0f".format(altitude),
                    grade?.let { "%+.1f".format(it) } ?: stringResource(R.string.value_unknown),
                    accuracy?.let { "%.0f".format(it) } ?: stringResource(R.string.value_unknown),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        !locationGranted -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.gps_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermissions) { Text(stringResource(R.string.action_allow)) }
        }
        else -> Text(stringResource(R.string.gps_waiting), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LiveSignalsCard(signals: Map<Int, Double>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.live_signals_title, signals.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show))
                }
            }
            if (expanded) {
                // ~40-90 rows, bounded by what the ECU supports: a plain Column is cheaper than a lazy list here.
                signals.toSortedMap().forEach { (id, value) ->
                    val def = PidCatalog.signal(id) ?: return@forEach
                    Row {
                        Text(def.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(
                            text = "${formatValue(value, def.resolution)} ${def.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun formatValue(value: Double, resolution: Double): String = when {
    resolution >= 1.0 -> "%.0f".format(value)
    resolution >= 0.1 -> "%.1f".format(value)
    resolution >= 0.01 -> "%.2f".format(value)
    else -> "%.3f".format(value)
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun ObdConnectionState.label(): String = when (this) {
    ObdConnectionState.Disconnected -> stringResource(R.string.obd_disconnected)
    is ObdConnectionState.Connecting -> stringResource(R.string.obd_connecting, deviceName)
    is ObdConnectionState.Initializing -> stringResource(R.string.obd_initializing, deviceName)
    is ObdConnectionState.Connected -> stringResource(R.string.obd_connected, deviceName)
    is ObdConnectionState.Error -> stringResource(R.string.obd_error, message)
}

@Composable
private fun ObdConnectionState.color(): Color = when (this) {
    is ObdConnectionState.Connected -> MaterialTheme.colorScheme.primary
    is ObdConnectionState.Error -> MaterialTheme.colorScheme.error
    ObdConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.tertiary
}

private const val MAX_RPM = 8_000f
private const val MAX_SPEED_KMH = 240f
