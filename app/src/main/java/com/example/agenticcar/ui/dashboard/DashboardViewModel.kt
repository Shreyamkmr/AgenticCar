package com.example.agenticcar.ui.dashboard

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agenticcar.BuildConfig
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.data.network.WifiAddressMonitor
import com.example.agenticcar.protocol.mcp.CloudflareTunnel
import com.example.agenticcar.protocol.mcp.McpTokenStore
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
) : ViewModel() {

    private data class LocalState(
        val permissionsGranted: Boolean = false,
        val locationGranted: Boolean = false,
        val devices: List<BondedDevice> = emptyList(),
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
        combine(_local, telemetry, wifiMonitor.ipv4, mcpTokens.access, tunnel.state) { local, t, ip, access, tunnelState ->
            t.copy(
                permissionsGranted = local.permissionsGranted,
                locationGranted = local.locationGranted,
                devices = local.devices,
                selectedDeviceAddress = local.selectedDeviceAddress,
                wifiIpv4 = ip,
                mcpAccess = access,
                tunnel = tunnelState,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun onEvent(event: DashboardEvent) {
        when (event) {
            is DashboardEvent.PermissionsChanged -> onPermissionsChanged(event.granted, event.locationGranted)
            is DashboardEvent.DeviceSelected -> _local.update { it.copy(selectedDeviceAddress = event.address) }
            DashboardEvent.ConnectClicked -> _local.value.selectedDeviceAddress?.let(serviceLauncher::connect)
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
        }
    }

    private fun onPermissionsChanged(granted: Boolean, locationGranted: Boolean) {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val bonded = if (granted) ObdBluetoothManager.bondedDevices(appContext, adapter) else emptyList()
        // Debug builds offer an in-app ELM327 so the full pipeline can be tested without a car.
        val devices = if (BuildConfig.DEBUG) listOf(SIMULATED_DEVICE) + bonded else bonded
        _local.update { current ->
            current.copy(
                permissionsGranted = granted,
                locationGranted = locationGranted,
                devices = devices,
                // Keep the user's pick; otherwise preselect something that looks like an ELM327.
                selectedDeviceAddress = current.selectedDeviceAddress?.takeIf { addr -> devices.any { it.address == addr } }
                    ?: devices.firstOrNull { d -> OBD_NAME_HINTS.any { d.name.contains(it, ignoreCase = true) } }?.address
                    ?: devices.firstOrNull()?.address,
            )
        }
    }

    private companion object {
        val SIMULATED_DEVICE = BondedDevice(name = SimulatedElm327Link.DEVICE_NAME, address = SimulatedElm327Link.ADDRESS)
        val OBD_NAME_HINTS = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "KONNWEI")
    }
}
