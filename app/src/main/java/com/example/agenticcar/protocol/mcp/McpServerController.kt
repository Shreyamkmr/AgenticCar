package com.example.agenticcar.protocol.mcp

import android.util.Log
import com.example.agenticcar.data.McpServerState
import com.example.agenticcar.data.TelemetryState
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the embedded Ktor/Netty server that exposes MCP over stateless Streamable HTTP at
 * `http://<wifi-ip>:8080/mcp/<token>` (each JSON-RPC request is a POST answered with JSON).
 *
 * Authentication is a secret token in the path ([McpTokenStore]): any other path is a 404.
 * The short local token is refused for tunnelled requests (detected by proxy headers); the
 * long public token works through a tunnel only while public access is switched on.
 *
 * The socket is bound to 127.0.0.1 (for the tunnel) and the Wi-Fi address, never 0.0.0.0,
 * so the server is not reachable over the cellular interface. Netty runs on its own
 * event-loop threads; [start]/[stop] only block an IO thread while binding/unbinding.
 * It reads recorded trips from Room, so it works with or without the car connected.
 */
@Singleton
class McpServerController @Inject constructor(
    private val tools: ObdMcpTools,
    private val tokens: McpTokenStore,
    private val telemetryState: TelemetryState,
) {
    private val lock = Mutex()
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var mcpServer: Server? = null
    private var boundHost: String? = null
    private var boundTokens: Pair<String, String>? = null

    /**
     * Serves on 127.0.0.1 (for the tunnel and on-device clients) plus the Wi-Fi address [host]
     * when there is one, or keeps the current binding if it already matches. Runs to completion
     * even if the caller is cancelled: returning from a cancelled call after Netty bound the
     * port would otherwise drop the engine reference and leak a running server.
     */
    suspend fun start(host: String?) = withContext(NonCancellable) {
        lock.withLock { startLocked(host) }
    }

    suspend fun stop() = withContext(NonCancellable) {
        lock.withLock {
            stopLocked()
            telemetryState.setMcp(McpServerState.Stopped)
        }
    }

    /** Re-serves on the current address with the current token, e.g. after it was regenerated. */
    suspend fun rebind() = withContext(NonCancellable) {
        lock.withLock { if (engine != null) startLocked(boundHost) }
    }

    private suspend fun startLocked(host: String?) {
        val access = tokens.access.value
        val tokenPair = access.localToken to access.publicToken
        val localUrl = host?.let { "http://$it:$PORT$PATH/${access.localToken}" }
        if (engine != null && boundHost == host && boundTokens == tokenPair) {
            Log.i(TAG, "start($host): already bound")
            telemetryState.setMcp(McpServerState.Running(url = localUrl))
            return
        }
        stopLocked()

        Log.i(TAG, "start($host): binding")
        telemetryState.setMcp(McpServerState.Starting)
        val server = tools.createServer()
        try {
            val created = createEngine(host, access, server)
            withContext(Dispatchers.IO) { created.start(wait = false) }
            engine = created
            mcpServer = server
            boundHost = host
            boundTokens = tokenPair
            Log.i(TAG, "start($host): running")
            telemetryState.setMcp(McpServerState.Running(url = localUrl))
        } catch (e: Throwable) {
            // Includes LinkageError: a Netty class missing on Android must surface as an
            // MCP error, not kill the logger process.
            if (e is VirtualMachineError || e is CancellationException) throw e
            Log.e(TAG, "start($host) failed", e)
            runCatching { server.close() }
            telemetryState.setMcp(McpServerState.Error(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    /**
     * Deliberately a plain function: called inside a coroutine, `embeddedServer` resolves to the
     * `CoroutineScope` overload, which makes the server a child job of the caller. A `withContext`
     * around it then waits for the server to stop, i.e. never returns.
     */
    private fun createEngine(
        host: String?,
        access: McpAccess,
        server: Server,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            Netty,
            environment = applicationEnvironment(),
            configure = {
                // Never 0.0.0.0: the server must not be reachable over the cellular interface.
                connector {
                    this.host = LOOPBACK
                    port = PORT
                }
                if (host != null) {
                    connector {
                        this.host = host
                        port = PORT
                    }
                }
            },
        ) {
            val localPath = "$PATH/${access.localToken}"
            val publicPath = "$PATH/${access.publicToken}"
            install(tokenGate(localPath, publicPath))
            for (path in listOf(localPath, publicPath)) {
                // Stateless Streamable HTTP: every JSON-RPC request is a POST answered with JSON,
                // with no long-lived stream. Cloudflare quick tunnels do not deliver SSE bodies,
                // so this is what makes the public URL work; it also suits LAN clients.
                mcpStatelessStreamableHttp(
                    path = path,
                    // The Host check only allows localhost by default and would reject both LAN
                    // clients and HTTPS tunnels. A DNS-rebinding page cannot know the secret path,
                    // so the token makes this check redundant.
                    enableDnsRebindingProtection = false,
                ) { server }
            }
        }

    /**
     * Answers 404 (as if the path did not exist) for:
     *  - the short local token arriving through a tunnel (detected by proxy headers),
     *  - the long public token arriving through a tunnel while public access is off
     *    (read per request, so the switch applies instantly).
     */
    private fun tokenGate(localPath: String, publicPath: String) = createApplicationPlugin("McpTokenGate") {
        onCall { call ->
            val path = call.request.path()
            val proxied = PROXY_HEADERS.any { call.request.headers[it] != null }
            val reject = when (path) {
                localPath -> proxied
                publicPath -> proxied && !tokens.access.value.publicEnabled
                else -> false
            }
            if (reject) call.respond(HttpStatusCode.NotFound)
        }
    }

    private suspend fun stopLocked() = withContext(Dispatchers.IO) {
        val e = engine ?: return@withContext
        val s = mcpServer
        engine = null
        mcpServer = null
        boundHost = null
        boundTokens = null
        Log.i(TAG, "stop: unbinding")
        runCatching { s?.close() }.onFailure { Log.w(TAG, "MCP server close failed", it) }
        runCatching { e.stop(gracePeriodMillis = 500, timeoutMillis = 2_000) }.onFailure { Log.e(TAG, "Netty stop failed", it) }
        Log.i(TAG, "stop: done")
    }

    companion object {
        private const val TAG = "McpServer"

        /** Set by Cloudflare Tunnel, ngrok and other reverse proxies; a direct LAN client sends none. */
        private val PROXY_HEADERS = listOf(
            "CF-Connecting-IP", "CF-Ray", "X-Forwarded-For", "X-Forwarded-Host", "Forwarded", "X-Real-IP", "Via",
        )
        const val PORT = 8080
        const val PATH = "/mcp"
        private const val LOOPBACK = "127.0.0.1"
    }
}
