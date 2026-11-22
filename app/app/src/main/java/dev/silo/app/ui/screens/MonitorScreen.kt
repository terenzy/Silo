package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.data.AppInventory
import dev.silo.app.ipc.ActiveConnection
import dev.silo.app.ipc.AppFirewallRule
import dev.silo.app.ipc.BlockedPacketEvent
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloAmber
import dev.silo.app.ui.theme.SiloCrimson
import dev.silo.app.ui.theme.SiloCyan
import dev.silo.app.ui.theme.SiloEmerald
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MonitorTab { BLOCKED, LIVE, LISTENING }

@Composable
fun MonitorScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    var tab by remember { mutableStateOf(MonitorTab.BLOCKED) }

    DisposableEffect(Unit) {
        vm.setMonitorPollingActive(true)
        onDispose { vm.setMonitorPollingActive(false) }
    }

    val liveSockets = remember(state.liveConnections) {
        state.liveConnections.filter { !it.isListening }
    }
    val listeningSockets = remember(state.liveConnections) {
        state.liveConnections.filter { it.isListening }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("RAM-Only Live Telemetry", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Zero-disk ring buffer (${state.telemetry.totalBlocked} total blocked) — vanishes on reboot",
                                style = MaterialTheme.typography.labelSmall,
                                color = SiloEmerald,
                            )
                        }
                        IconButton(onClick = { vm.refreshMonitorOnce() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = tab == MonitorTab.BLOCKED,
                            onClick = { tab = MonitorTab.BLOCKED },
                            label = { Text("Blocked (${state.telemetry.events.size})") },
                        )
                        FilterChip(
                            selected = tab == MonitorTab.LIVE,
                            onClick = { tab = MonitorTab.LIVE },
                            label = { Text("Live (${liveSockets.size})") },
                        )
                        FilterChip(
                            selected = tab == MonitorTab.LISTENING,
                            onClick = { tab = MonitorTab.LISTENING },
                            label = { Text("Listen (${listeningSockets.size})") },
                        )
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.monitorAutoRefresh,
                                onCheckedChange = { vm.toggleMonitorAutoRefresh(it) },
                            )
                            Text("Live 3s refresh", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { vm.clearTelemetry() }) { Text("Clear RAM Ring") }
                    }
                }
            }
        }

        when (tab) {
            MonitorTab.BLOCKED -> {
                if (state.telemetry.events.isEmpty()) {
                    item {
                        Text(
                            "No blocked packet events captured yet in RAM ring buffer.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.telemetry.events) { ev ->
                        BlockedEventCard(ev)
                    }
                }
            }

            MonitorTab.LIVE -> {
                items(liveSockets) { conn ->
                    ConnectionCard(
                        conn = conn,
                        onKill = { vm.killConnection(conn) },
                        onBlockUid = {
                            if (!AppInventory.isProtectedUid(conn.uid)) {
                                vm.updateAppFirewallRule(
                                    conn.uid,
                                    AppFirewallRule(
                                        packageName = conn.packageName ?: "",
                                        allowWifi = false,
                                        allowCellular = false,
                                        allowVpn = false,
                                        allowScreenOff = false,
                                    ),
                                )
                            }
                        },
                    )
                }
            }

            MonitorTab.LISTENING -> {
                items(listeningSockets) { conn ->
                    ConnectionCard(
                        conn = conn,
                        onKill = null,
                        onBlockUid = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockedEventCard(ev: BlockedPacketEvent) {
    val timeStr = remember(ev.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.timestampMs))
    }
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    ev.packageName ?: ev.uid?.let { "UID $it" } ?: "Kernel/Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(shape = RoundedCornerShape(6.dp), color = SiloCrimson.copy(alpha = 0.16f)) {
                    Text(
                        ev.reason,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloCrimson,
                    )
                }
            }
            Text(
                "${ev.protocol}  ${ev.dstIp}:${ev.dstPort}  (src :${ev.srcPort})  •  $timeStr",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    conn: ActiveConnection,
    onKill: (() -> Unit)?,
    onBlockUid: (() -> Unit)?,
) {
    val isUserAppListener = conn.isListening && !AppInventory.isProtectedUid(conn.uid)
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = if (isUserAppListener) {
            CardDefaults.cardColors(containerColor = SiloAmber.copy(alpha = 0.12f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    conn.packageName ?: "UID ${conn.uid}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${conn.protocol} ${conn.state}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUserAppListener) SiloAmber else SiloCyan,
                )
            }
            Text(
                "${conn.localIp}:${conn.localPort} -> ${conn.remoteIp}:${conn.remotePort}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onKill != null || onBlockUid != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onKill != null && conn.protocol.startsWith("TCP")) {
                        TextButton(onClick = onKill) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("SOCK_DESTROY")
                        }
                    }
                    if (onBlockUid != null && !AppInventory.isProtectedUid(conn.uid)) {
                        TextButton(onClick = onBlockUid) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Block UID")
                        }
                    }
                }
            }
        }
    }
}
