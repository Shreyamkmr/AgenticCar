package com.example.agenticcar.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.agenticcar.R
import com.example.agenticcar.protocol.mcp.McpAccess
import com.example.agenticcar.protocol.mcp.TunnelConfig
import com.example.agenticcar.protocol.mcp.TunnelState

/** Configure the HTTPS tunnel first, then switch public access on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicAccessScreen(
    state: DashboardUiState,
    onEvent: (DashboardEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val access = state.mcpAccess ?: return
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.public_access_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.public_access_intro), style = MaterialTheme.typography.bodyMedium)
            TunnelConfigCard(config = access.tunnel, locked = access.publicEnabled, onSave = { onEvent(DashboardEvent.TunnelConfigSaved(it)) })
            StatusCard(access = access, tunnel = state.tunnel, onEvent = onEvent)
        }
    }
}

@Composable
private fun TunnelConfigCard(config: TunnelConfig, locked: Boolean, onSave: (TunnelConfig) -> Unit, modifier: Modifier = Modifier) {
    var named by rememberSaveable(config) { mutableStateOf(config is TunnelConfig.Named) }
    var token by rememberSaveable(config) { mutableStateOf((config as? TunnelConfig.Named)?.token.orEmpty()) }
    var hostname by rememberSaveable(config) { mutableStateOf((config as? TunnelConfig.Named)?.hostname.orEmpty()) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tunnel_step_1), style = MaterialTheme.typography.titleMedium)
            ModeOption(selected = !named, enabled = !locked, title = R.string.tunnel_quick_title, body = R.string.tunnel_quick_body) { named = false }
            ModeOption(selected = named, enabled = !locked, title = R.string.tunnel_named_title, body = R.string.tunnel_named_body) { named = true }
            if (named) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    enabled = !locked,
                    label = { Text(stringResource(R.string.tunnel_token_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hostname,
                    onValueChange = { hostname = it.trim().removePrefix("https://").trimEnd('/') },
                    enabled = !locked,
                    label = { Text(stringResource(R.string.tunnel_hostname_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val pending = if (named) TunnelConfig.Named(token, hostname) else TunnelConfig.Quick
            val valid = !named || (token.isNotEmpty() && hostname.contains('.'))
            if (locked) {
                Text(stringResource(R.string.tunnel_locked_note), style = MaterialTheme.typography.bodySmall)
            } else if (pending != config) {
                Button(onClick = { onSave(pending) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, enabled: Boolean, title: Int, body: Int, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(access: McpAccess, tunnel: TunnelState, onEvent: (DashboardEvent) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tunnel_step_2), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = access.publicEnabled, onCheckedChange = { onEvent(DashboardEvent.PublicAccessToggled(it)) })
            }
            Text(
                text = stringResource(if (access.publicEnabled) R.string.public_access_on else R.string.public_access_off),
                style = MaterialTheme.typography.bodySmall,
                color = if (access.publicEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (access.publicEnabled) {
                when (tunnel) {
                    TunnelState.Off -> Text(stringResource(R.string.tunnel_waiting_mcp))
                    TunnelState.Starting -> Text(stringResource(R.string.tunnel_starting))
                    TunnelState.Unavailable -> Text(stringResource(R.string.tunnel_unavailable), color = MaterialTheme.colorScheme.error)
                    is TunnelState.Error -> Text(stringResource(R.string.tunnel_error, tunnel.message), color = MaterialTheme.colorScheme.error)
                    is TunnelState.Running -> {
                        val url = "${tunnel.publicBase}/mcp/${access.publicToken}"
                        Text(stringResource(R.string.tunnel_public_url), style = MaterialTheme.typography.bodyMedium)
                        SelectionContainer {
                            Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Button(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("MCP URL", url))
                        }) { Text(stringResource(R.string.action_copy_url)) }
                        if (access.tunnel is TunnelConfig.Quick) {
                            Text(stringResource(R.string.tunnel_quick_changes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { onEvent(DashboardEvent.McpNewTokenClicked) }) {
                Text(stringResource(R.string.action_new_token))
            }
        }
    }
}
