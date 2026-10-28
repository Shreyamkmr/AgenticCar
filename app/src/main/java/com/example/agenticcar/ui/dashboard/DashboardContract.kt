package com.example.agenticcar.ui.dashboard

import com.example.agenticcar.data.LiveMetrics
import com.example.agenticcar.data.McpServerState
import com.example.agenticcar.data.ObdConnectionState
import com.example.agenticcar.data.RecorderStatus
import com.example.agenticcar.protocol.mcp.McpAccess
import com.example.agenticcar.protocol.mcp.TunnelConfig
import com.example.agenticcar.protocol.mcp.TunnelState
import com.example.agenticcar.service.BondedDevice

data class DashboardUiState(
    val permissionsGranted: Boolean = false,
    val devices: List<BondedDevice> = emptyList(),
    val selectedDeviceAddress: String? = null,
    val obd: ObdConnectionState = ObdConnectionState.Disconnected,
    val metrics: LiveMetrics = LiveMetrics(),
    val liveSignals: Map<Int, Double> = emptyMap(),
    val recorder: RecorderStatus = RecorderStatus(),
    val wifiIpv4: String? = null,
    val mcp: McpServerState = McpServerState.Stopped,
    val mcpAccess: McpAccess? = null,
    val tunnel: TunnelState = TunnelState.Off,
) {
    val mcpEnabled: Boolean get() = mcp !is McpServerState.Stopped
    val obdActive: Boolean get() = obd !is ObdConnectionState.Disconnected
}

sealed interface DashboardEvent {
    data class PermissionsChanged(val granted: Boolean) : DashboardEvent
    data class DeviceSelected(val address: String) : DashboardEvent
    data object ConnectClicked : DashboardEvent
    data object DisconnectClicked : DashboardEvent
    data class McpToggled(val enabled: Boolean) : DashboardEvent
    data object McpNewTokenClicked : DashboardEvent
    data class PublicAccessToggled(val enabled: Boolean) : DashboardEvent
    data class TunnelConfigSaved(val config: TunnelConfig) : DashboardEvent
}
