package com.example.agenticcar.protocol.mcp

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TunnelConfig {
    /** Free, no account; a new random `*.trycloudflare.com` URL on every start. */
    data object Quick : TunnelConfig

    /** Free Cloudflare account + own domain; fixed [hostname] routed to http://127.0.0.1:8080. */
    data class Named(val token: String, val hostname: String) : TunnelConfig
}

data class McpAccess(
    /** Short, typeable token for clients on the same Wi-Fi: `/mcp/<localToken>`. */
    val localToken: String,
    /** Long token for clients coming through an HTTPS tunnel: `/mcp/<publicToken>`. */
    val publicToken: String,
    /** Runs the tunnel; tunnelled requests are refused unless this is on. */
    val publicEnabled: Boolean,
    val tunnel: TunnelConfig,
)

/**
 * The MCP server's credentials. Each token is a secret path segment, which works with every
 * MCP client (including ones that cannot send custom headers) and survives an HTTPS tunnel.
 *
 * The short local token is only accepted for requests without tunnel/proxy headers, so it
 * cannot be brute-forced from the internet; the public token is 256 bits.
 */
@Singleton
class McpTokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val _access = MutableStateFlow(load())
    val access: StateFlow<McpAccess> = _access.asStateFlow()

    @Synchronized
    fun regenerateTokens() = save(_access.value.copy(localToken = newLocalToken(), publicToken = newPublicToken()))

    @Synchronized
    fun setPublicEnabled(enabled: Boolean) = save(_access.value.copy(publicEnabled = enabled))

    @Synchronized
    fun setTunnel(config: TunnelConfig) = save(_access.value.copy(tunnel = config))

    private fun load(): McpAccess {
        val namedToken = prefs.getString(KEY_CF_TOKEN, null)
        val namedHost = prefs.getString(KEY_CF_HOST, null)
        val access = McpAccess(
            localToken = prefs.getString(KEY_LOCAL, null)?.takeIf { it.length == LOCAL_LENGTH } ?: newLocalToken(),
            publicToken = prefs.getString(KEY_PUBLIC, null) ?: newPublicToken(),
            publicEnabled = prefs.getBoolean(KEY_PUBLIC_ENABLED, false),
            tunnel = if (namedToken != null && namedHost != null) TunnelConfig.Named(namedToken, namedHost) else TunnelConfig.Quick,
        )
        persist(access)
        return access
    }

    private fun save(access: McpAccess) {
        persist(access)
        _access.value = access
    }

    private fun persist(access: McpAccess) {
        val named = access.tunnel as? TunnelConfig.Named
        prefs.edit()
            .putString(KEY_LOCAL, access.localToken)
            .putString(KEY_PUBLIC, access.publicToken)
            .putBoolean(KEY_PUBLIC_ENABLED, access.publicEnabled)
            .putString(KEY_CF_TOKEN, named?.token)
            .putString(KEY_CF_HOST, named?.hostname)
            .apply()
    }

    private fun newLocalToken(): String = String(CharArray(LOCAL_LENGTH) { LOCAL_ALPHABET[random.nextInt(LOCAL_ALPHABET.length)] })

    private fun newPublicToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private companion object {
        const val PREFS = "mcp_auth"
        const val KEY_LOCAL = "local_token"
        const val KEY_PUBLIC = "token"
        const val KEY_PUBLIC_ENABLED = "public_enabled"
        const val KEY_CF_TOKEN = "cloudflare_tunnel_token"
        const val KEY_CF_HOST = "cloudflare_tunnel_hostname"
        const val LOCAL_LENGTH = 6
        // Lower-case, no look-alikes (0/o, 1/l/i), so it is easy to read off the screen and type.
        const val LOCAL_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
