package com.example.agenticcar.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ObdConnectionState {
    data object Disconnected : ObdConnectionState
    data class Connecting(val deviceName: String) : ObdConnectionState
    data class Initializing(val deviceName: String) : ObdConnectionState
    data class Connected(val deviceName: String) : ObdConnectionState
    data class Error(val message: String) : ObdConnectionState
}

sealed interface McpServerState {
    data object Stopped : McpServerState
    data object Starting : McpServerState
    data object WaitingForWifi : McpServerState
    /** [url] is the Wi-Fi URL, or null when only 127.0.0.1 (the tunnel) is served. */
    data class Running(val url: String?) : McpServerState
    data class Error(val message: String) : McpServerState
}

/** Headline values for the gauges; the full set lives in [TelemetryState.liveSignals]. */
data class LiveMetrics(
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val coolantTempCelsius: Int? = null,
    val engineLoadPercent: Float? = null,
    val batteryVoltage: Float? = null,
    val tripActive: Boolean = false,
    /** Epoch millis when the current trip became active, null when no trip. */
    val tripStartedAt: Long? = null,
    val lastUpdatedAt: Long? = null,
)

data class RecorderStatus(
    val sessionActive: Boolean = false,
    val adapterVersion: String? = null,
    val protocol: String? = null,
    val vin: String? = null,
    val supportedSignals: Int = 0,
    /** PIDs per Mode 01 request (1 when the adapter/protocol cannot batch). */
    val batchSize: Int = 1,
    val tripId: Long? = null,
    val tripReadings: Long = 0,
    val tripStoredSamples: Long = 0,
    val tripStoredBytes: Long = 0,
    val dtcStored: List<String> = emptyList(),
    val dtcPending: List<String> = emptyList(),
    val dtcPermanent: List<String> = emptyList(),
)

/**
 * Single source of truth for live state shared by the foreground service
 * (writer), the MCP tools and the dashboard (readers).
 */
@Singleton
class TelemetryState @Inject constructor() {
    private val _obd = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    val obd: StateFlow<ObdConnectionState> = _obd.asStateFlow()

    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics.asStateFlow()

    /** Latest decoded value of every signal, keyed by signal id. */
    private val _liveSignals = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val liveSignals: StateFlow<Map<Int, Double>> = _liveSignals.asStateFlow()

    private val _recorder = MutableStateFlow(RecorderStatus())
    val recorder: StateFlow<RecorderStatus> = _recorder.asStateFlow()

    private val _mcp = MutableStateFlow<McpServerState>(McpServerState.Stopped)
    val mcp: StateFlow<McpServerState> = _mcp.asStateFlow()

    fun setObd(state: ObdConnectionState) = _obd.update { state }

    fun setMcp(state: McpServerState) = _mcp.update { state }

    fun publishLive(metrics: LiveMetrics, signals: Map<Int, Double>, recorder: RecorderStatus) {
        _metrics.update { metrics }
        _liveSignals.update { signals }
        _recorder.update { recorder }
    }
}
