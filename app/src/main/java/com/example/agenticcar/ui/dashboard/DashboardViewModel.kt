package com.example.agenticcar.ui.dashboard

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agenticcar.data.DeveloperSettings
import com.example.agenticcar.data.ObdConnectionState
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.data.network.WifiAddressMonitor
import com.example.agenticcar.data.VehicleSettings
import com.example.agenticcar.protocol.mcp.CloudflareTunnel
import com.example.agenticcar.protocol.mcp.McpAccess
import com.example.agenticcar.protocol.mcp.McpTokenStore
import com.example.agenticcar.protocol.mcp.TunnelState
import com.example.agenticcar.service.BondedDevice
import com.example.agenticcar.service.ObdBluetoothManager
import com.example.agenticcar.service.ServiceLauncher
import com.example.agenticcar.service.SimulatedElm327Link
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    telemetryState: TelemetryState,
    wifiMonitor: WifiAddressMonitor,
    private val serviceLauncher: ServiceLauncher,
    private val mcpTokens: McpTokenStore,
    tunnel: CloudflareTunnel,
    private val vehicle: VehicleSettings,
    private val developer: DeveloperSettings,
) : ViewModel() {

    private data class LocalState(
        val permissionsGranted: Boolean = false,
        val locationGranted: Boolean = false,
        val bonded: List<BondedDevice> = emptyList(),
        /** The user's pick; null lets [pickDevice] choose. */
        val selectedDeviceAddress: String? = null,
    )

    private val _local = MutableStateFlow(LocalState())

    private val telemetry = combine(
        telemetryState.obd,
        telemetryState.metrics,
        telemetryState.liveSignals,
        telemetryState.recorder,
        telemetryState.mcp,
    ) { obd, metrics, signals, recorder, mcp ->
        DashboardUiState(obd = obd, metrics = metrics, liveSignals = signals, recorder = recorder, mcp = mcp)
    }

    val state: StateFlow<DashboardUiState> =
        combine(
            _local, telemetry, wifiMonitor.ipv4, mcpTokens.access, tunnel.state, vehicle.engineDisplacementL, developer.enabled,
        ) { values ->
            val local = values[0] as LocalState
            val t = values[1] as DashboardUiState
            val ip = values[2] as String?
            val access = values[3] as McpAccess
            val tunnelState = values[4] as TunnelState
            val displacement = values[5] as Double?
            val developerMode = values[6] as Boolean
            // The simulated adapter is a developer tool: offered only while developer mode is on.
            val devices = if (developerMode) listOf(SIMULATED_DEVICE) + local.bonded else local.bonded
            t.copy(
                engineDisplacementL = displacement,
                developerMode = developerMode,
                permissionsGranted = local.permissionsGranted,
                locationGranted = local.locationGranted,
                devices = devices,
                selectedDeviceAddress = pickDevice(local.selectedDeviceAddress, devices),
                wifiIpv4 = ip,
                mcpAccess = access,
                tunnel = tunnelState,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onEvent(event: DashboardEvent) {
        when (event) {
            is DashboardEvent.PermissionsChanged -> onPermissionsChanged(event.granted, event.locationGranted)
            is DashboardEvent.DeviceSelected -> _local.update { it.copy(selectedDeviceAddress = event.address) }
            DashboardEvent.ConnectClicked -> state.value.selectedDeviceAddress?.let(serviceLauncher::connect)
            DashboardEvent.DisconnectClicked -> serviceLauncher.disconnect()
            is DashboardEvent.McpToggled -> serviceLauncher.setMcpEnabled(event.enabled)
            DashboardEvent.McpNewTokenClicked -> {
                mcpTokens.regenerateTokens()
                serviceLauncher.rebindMcp()
            }
            is DashboardEvent.PublicAccessToggled -> {
                mcpTokens.setPublicEnabled(event.enabled)
                // The tunnel forwards to the MCP server, so turning public access on implies MCP on.
                if (event.enabled && !state.value.mcpEnabled) serviceLauncher.setMcpEnabled(true)
            }
            is DashboardEvent.TunnelConfigSaved -> mcpTokens.setTunnel(event.config)
            is DashboardEvent.EngineSizeSaved -> vehicle.setEngineDisplacementL(event.litres)
            is DashboardEvent.DeveloperModeToggled -> {
                developer.setEnabled(event.enabled)
                if (!event.enabled) {
                    // Leaving developer mode stops a running simulation rather than leaving it logging fake trips.
                    val name = when (val obd = state.value.obd) {
                        is ObdConnectionState.Connecting -> obd.deviceName
                        is ObdConnectionState.Initializing -> obd.deviceName
                        is ObdConnectionState.Connected -> obd.deviceName
                        else -> null
                    }
                    if (name == SimulatedElm327Link.DEVICE_NAME) serviceLauncher.disconnect()
                    if (_local.value.selectedDeviceAddress == SimulatedElm327Link.ADDRESS) {
                        _local.update { it.copy(selectedDeviceAddress = null) }
                    }
                }
            }
        }
    }

    private fun onPermissionsChanged(granted: Boolean, locationGranted: Boolean) {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = if (granted) ObdBluetoothManager.bondedDevices(appContext, adapter) else emptyList()
        _local.update { it.copy(permissionsGranted = granted, locationGranted = locationGranted, bonded = bonded) }
    }

    /** Keeps the user's pick while it is offered; otherwise preselects something that looks like an ELM327. */
    private fun pickDevice(chosen: String?, devices: List<BondedDevice>): String? =
        chosen?.takeIf { addr -> devices.any { it.address == addr } }
            ?: devices.firstOrNull { d -> OBD_NAME_HINTS.any { d.name.contains(it, ignoreCase = true) } }?.address
            ?: devices.firstOrNull()?.address

    private companion object {
        val SIMULATED_DEVICE = BondedDevice(name = SimulatedElm327Link.DEVICE_NAME, address = SimulatedElm327Link.ADDRESS)
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI")
    }
}
