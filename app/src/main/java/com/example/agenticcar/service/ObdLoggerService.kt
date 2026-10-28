package com.example.agenticcar.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.agenticcar.MainActivity
import com.example.agenticcar.R
import com.example.agenticcar.data.McpServerState
import com.example.agenticcar.data.ObdConnectionState
import com.example.agenticcar.data.TelemetryState
import com.example.agenticcar.data.network.WifiAddressMonitor
import com.example.agenticcar.data.recording.TelemetryRecorder
import com.example.agenticcar.di.ApplicationScope
import com.example.agenticcar.protocol.mcp.CloudflareTunnel
import com.example.agenticcar.protocol.mcp.McpServerController
import com.example.agenticcar.protocol.mcp.McpTokenStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that owns every long-lived resource:
 *  - the Bluetooth SPP socket and the [ObdSession] poll loop feeding [TelemetryRecorder],
 *  - the lifetime of the MCP server (bound to the current Wi-Fi address).
 *
 * Recording is committed by the process-wide TelemetryWriter, so a trip finished
 * during shutdown is still persisted. [onDestroy] cancels [serviceScope] and
 * closes sockets synchronously so no Bluetooth or network handle outlives the service.
 */
@AndroidEntryPoint
class ObdLoggerService : Service() {

    @Inject lateinit var telemetryState: TelemetryState
    @Inject lateinit var recorder: TelemetryRecorder
    @Inject lateinit var mcpController: McpServerController
    @Inject lateinit var wifiMonitor: WifiAddressMonitor
    @Inject lateinit var mcpTokens: McpTokenStore
    @Inject lateinit var tunnel: CloudflareTunnel
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var obdJob: Job? = null
    private var mcpJob: Job? = null
    private var notificationJob: Job? = null

    @Volatile private var activeLink: Elm327Link? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (notificationJob == null) notificationJob = serviceScope.launch { keepNotificationUpdated() }

        Log.i(TAG, "command ${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> intent.getStringExtra(EXTRA_DEVICE_ADDRESS)?.let(::startObd)
            ACTION_DISCONNECT -> stopObd()
            ACTION_MCP_START -> startMcp()
            ACTION_MCP_STOP -> stopMcp()
            ACTION_MCP_REBIND -> if (mcpJob != null) serviceScope.launch { mcpController.rebind() }
            ACTION_STOP -> {
                stopObd()
                stopMcp()
            }
        }
        stopIfIdle()
        // Not sticky: after a process kill the user reconnects from the dashboard,
        // rather than the service silently restarting without a device address.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // Close the RFCOMM socket now instead of waiting for coroutine cancellation.
        activeLink?.close()
        activeLink = null
        // Unbinding Netty blocks for up to the grace period; do it off the main thread
        // in a scope that outlives this service instance.
        tunnel.stop()
        appScope.launch { mcpController.stop() }
        // Ends and persists any open trip even if the poll coroutine has not unwound yet.
        recorder.onDisconnected(System.currentTimeMillis())
        telemetryState.setObd(ObdConnectionState.Disconnected)
        super.onDestroy()
    }

    // region OBD

    private fun startObd(address: String) {
        obdJob?.cancel()
        obdJob = serviceScope.launch { connectLoop(address) }
    }

    private fun stopObd() {
        obdJob?.cancel()
        obdJob = null
        activeLink?.close()
        recorder.onDisconnected(System.currentTimeMillis())
        telemetryState.setObd(ObdConnectionState.Disconnected)
    }

    /** Connects, initialises and polls; reconnects with a delay after any link failure. */
    private suspend fun connectLoop(address: String) {
        val simulated = address == SimulatedElm327Link.ADDRESS
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (!simulated && adapter == null) {
            telemetryState.setObd(ObdConnectionState.Error(getString(R.string.error_no_bluetooth)))
            return
        }
        while (serviceScope.isActive) {
            val link: Elm327Link = if (simulated) {
                SimulatedElm327Link()
            } else {
                ObdBluetoothManager(applicationContext, requireNotNull(adapter))
            }
            activeLink = link
            try {
                telemetryState.setObd(ObdConnectionState.Connecting(address))
                val name = link.connect(address)
                telemetryState.setObd(ObdConnectionState.Initializing(name))
                ObdSession(link, recorder, name).run(
                    onReady = { telemetryState.setObd(ObdConnectionState.Connected(name)) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A user disconnect closes the socket, surfacing here as an IOException.
                currentCoroutineContext().ensureActive()
                telemetryState.setObd(ObdConnectionState.Error(e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                link.close()
                if (activeLink === link) activeLink = null
                // Closes the open trip; the writer persists it independently of this scope.
                recorder.onDisconnected(System.currentTimeMillis())
            }
            delay(RECONNECT_DELAY_MILLIS)
        }
    }

    // endregion

    // region MCP

    @OptIn(FlowPreview::class)
    private fun startMcp() {
        if (mcpJob != null) return
        // Reflect the request immediately so the switch does not look ignored while Wi-Fi resolves.
        telemetryState.setMcp(McpServerState.Starting)
        mcpJob = serviceScope.launch {
            launch {
                // Rebind whenever the Wi-Fi address changes; without Wi-Fi only 127.0.0.1 (the
                // tunnel) is served. Brief drop-outs are ignored rather than tearing down clients.
                wifiMonitor.ipv4.debounce { ip -> if (ip == null) WIFI_LOSS_DEBOUNCE_MILLIS else 0L }.collectLatest { ip ->
                    Log.i(TAG, "MCP Wi-Fi address: $ip")
                    mcpController.start(ip)
                }
            }
            // The public tunnel runs exactly while public access is on (and MCP is on).
            mcpTokens.access.map { it.publicEnabled to it.tunnel }.distinctUntilChanged().collect { (enabled, config) ->
                if (enabled) tunnel.start(config, McpServerController.PORT) else tunnel.stop()
            }
        }
    }

    private fun stopMcp() {
        mcpJob?.cancel()
        mcpJob = null
        tunnel.stop()
        appScope.launch { mcpController.stop() }
    }

    // endregion

    private fun stopIfIdle() {
        if (obdJob == null && mcpJob == null) stopSelf()
    }

    // region Notification

    private fun promoteToForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting)), type)
        true
    } catch (e: SecurityException) {
        // connectedDevice type requires BLUETOOTH_CONNECT on Android 14+.
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException when started from the background.
        false
    }

    private suspend fun keepNotificationUpdated() {
        combine(telemetryState.obd, telemetryState.metrics.map { it.tripActive }, telemetryState.mcp) { obd, trip, mcp ->
            listOf(obd.label(), if (trip) getString(R.string.notification_trip_active) else null, mcp.label())
                .filterNotNull()
                .joinToString(" · ")
        }
            .distinctUntilChanged()
            .collect { text ->
                if (canPostNotifications()) {
                    @Suppress("MissingPermission") // checked by canPostNotifications()
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ObdLoggerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ObdConnectionState.label(): String = when (this) {
        ObdConnectionState.Disconnected -> getString(R.string.obd_disconnected)
        is ObdConnectionState.Connecting -> getString(R.string.obd_connecting, deviceName)
        is ObdConnectionState.Initializing -> getString(R.string.obd_initializing, deviceName)
        is ObdConnectionState.Connected -> getString(R.string.obd_connected, deviceName)
        is ObdConnectionState.Error -> getString(R.string.obd_error, message)
    }

    private fun McpServerState.label(): String? = when (this) {
        McpServerState.Stopped -> null
        McpServerState.Starting -> getString(R.string.mcp_starting)
        McpServerState.WaitingForWifi -> getString(R.string.mcp_waiting_wifi)
        is McpServerState.Running -> getString(R.string.mcp_running, url ?: getString(R.string.mcp_no_wifi_short))
        is McpServerState.Error -> getString(R.string.mcp_error, message)
    }

    // endregion

    companion object {
        const val ACTION_CONNECT = "com.example.agenticcar.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.agenticcar.action.DISCONNECT"
        const val ACTION_MCP_START = "com.example.agenticcar.action.MCP_START"
        const val ACTION_MCP_STOP = "com.example.agenticcar.action.MCP_STOP"
        /** Re-serve MCP under the current token after it was regenerated. */
        const val ACTION_MCP_REBIND = "com.example.agenticcar.action.MCP_REBIND"
        const val ACTION_STOP = "com.example.agenticcar.action.STOP"
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val TAG = "ObdLoggerService"
        private const val CHANNEL_ID = "obd_logger"
        private const val NOTIFICATION_ID = 1001

        private const val RECONNECT_DELAY_MILLIS = 5_000L
        private const val WIFI_LOSS_DEBOUNCE_MILLIS = 3_000L

        fun intent(context: Context, action: String): Intent =
            Intent(context, ObdLoggerService::class.java).setAction(action)
    }
}
