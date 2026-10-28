package com.example.agenticcar.service

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sends commands to [ObdLoggerService]; keeps Intent plumbing out of the ViewModel. */
@Singleton
class ServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun connect(deviceAddress: String) = send(
        ObdLoggerService.intent(context, ObdLoggerService.ACTION_CONNECT)
            .putExtra(ObdLoggerService.EXTRA_DEVICE_ADDRESS, deviceAddress),
    )

    fun disconnect() = send(ObdLoggerService.intent(context, ObdLoggerService.ACTION_DISCONNECT))

    fun setMcpEnabled(enabled: Boolean) = send(
        ObdLoggerService.intent(
            context,
            if (enabled) ObdLoggerService.ACTION_MCP_START else ObdLoggerService.ACTION_MCP_STOP,
        ),
    )

    fun rebindMcp() = send(ObdLoggerService.intent(context, ObdLoggerService.ACTION_MCP_REBIND))

    private fun send(intent: android.content.Intent) {
        // Every command promotes the service to foreground in onStartCommand.
        ContextCompat.startForegroundService(context, intent)
    }
}
