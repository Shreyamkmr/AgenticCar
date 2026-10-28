package com.example.agenticcar.protocol.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.example.agenticcar.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.net.Inet6Address
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TunnelState {
    data object Off : TunnelState
    data object Starting : TunnelState
    /** [publicBase] is the `https://host` to which the public MCP path is appended. */
    data class Running(val publicBase: String) : TunnelState
    data class Error(val message: String) : TunnelState
    data object Unavailable : TunnelState
}

/**
 * Runs the bundled, Android-patched `cloudflared` (built by tools/cloudflared/build.ps1) as a
 * child process that forwards `https://<public host>` to the MCP server on 127.0.0.1.
 *
 * - Quick tunnel: free, no account, new random `*.trycloudflare.com` URL on every start.
 * - Named tunnel: a token from a (free) Cloudflare account, with a fixed hostname on the user's domain.
 *
 * Android has no /etc/resolv.conf and keeps CA certificates outside the Linux paths Go
 * searches, so DNS servers and CA directories are passed through the environment. The process
 * is restarted with back-off if it exits while the tunnel is wanted.
 */
@Singleton
class CloudflareTunnel @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var job: Job? = null
    private var running: TunnelConfig? = null
    @Volatile private var process: Process? = null

    private val binary: File get() = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    val isAvailable: Boolean get() = binary.canExecute()

    /** Starts the tunnel, or restarts it if [config] changed. No-op if already running with it. */
    @Synchronized
    fun start(config: TunnelConfig, originPort: Int) {
        if (job?.isActive == true && running == config) return
        stopLocked()
        if (!isAvailable) {
            _state.value = TunnelState.Unavailable
            return
        }
        running = config
        job = scope.launch(Dispatchers.IO) { superviseProcess(config, originPort) }
    }

    @Synchronized
    fun stop() {
        stopLocked()
        _state.value = TunnelState.Off
    }

    private fun stopLocked() {
        job?.cancel()
        job = null
        running = null
        // A read blocked on the process's output ignores interruption; killing the process ends it.
        process?.destroy()
    }

    private suspend fun superviseProcess(config: TunnelConfig, originPort: Int) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            _state.value = TunnelState.Starting
            val process = launchProcess(config, originPort).also { this.process = it }
            val startedAt = System.currentTimeMillis()
            var lastLine = ""
            try {
                runInterruptible {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            lastLine = line
                            Log.d(TAG, line)
                            onLogLine(config, line)
                        }
                    }
                }
            } finally {
                // Runs on cancellation too: never leave an orphaned cloudflared behind.
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            val exit = process.waitFor()
            Log.w(TAG, "cloudflared exited ($exit): $lastLine")
            _state.value = TunnelState.Error(lastLine.substringAfter(" ERR ").ifBlank { "cloudflared exited ($exit)" }.take(200))
            // A tunnel that ran for a while gets a quick restart; a crash loop backs off.
            backoffMs = if (System.currentTimeMillis() - startedAt > STABLE_RUN_MS) INITIAL_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            delay(backoffMs)
        }
    }

    private fun onLogLine(config: TunnelConfig, line: String) {
        when (config) {
            is TunnelConfig.Quick -> QUICK_URL.find(line)?.value
                ?.takeIf { !it.contains("//api.") }
                ?.let { _state.value = TunnelState.Running(it) }
            is TunnelConfig.Named -> if (line.contains("Registered tunnel connection")) {
                _state.value = TunnelState.Running("https://${config.hostname}")
            }
        }
    }

    private fun launchProcess(config: TunnelConfig, originPort: Int): Process {
        val origin = "http://127.0.0.1:$originPort"
        val args = when (config) {
            is TunnelConfig.Quick -> listOf("tunnel", "--no-autoupdate", "--url", origin)
            // The named tunnel's public hostname must point at http://127.0.0.1:8080 in the Cloudflare dashboard.
            is TunnelConfig.Named -> listOf("tunnel", "--no-autoupdate", "run")
        }
        return ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["CLOUDFLARED_DNS_SERVERS"] = dnsServers().joinToString(",")
                environment()["SSL_CERT_DIR"] = CA_DIRS
                // Passed via the environment, not argv, so it never appears in a process listing.
                if (config is TunnelConfig.Named) environment()["TUNNEL_TOKEN"] = config.token
            }
            .start()
    }

    /** The active network's resolvers first, then public fallbacks. */
    private fun dnsServers(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val system = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty().map { addr ->
            if (addr is Inet6Address) "[${addr.hostAddress?.substringBefore('%')}]:53" else "${addr.hostAddress}:53"
        }
        return (system + FALLBACK_DNS).distinct()
    }

    private companion object {
        const val TAG = "CloudflareTunnel"
        const val BINARY_NAME = "libcloudflared.so"
        const val CA_DIRS = "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts"
        val FALLBACK_DNS = listOf("1.1.1.1:53", "8.8.8.8:53")
        val QUICK_URL = Regex("""https://[a-z0-9-]+\.trycloudflare\.com""")
        const val INITIAL_BACKOFF_MS = 3_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val STABLE_RUN_MS = 60_000L
    }
}
