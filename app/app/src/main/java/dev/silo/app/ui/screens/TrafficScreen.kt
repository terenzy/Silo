package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.data.AppInventory
import dev.silo.app.ipc.ActiveConnection
import dev.silo.app.ipc.AppFirewallRule
import dev.silo.app.ipc.BlockedPacketEvent
import dev.silo.app.ipc.PrivacyAccessEvent
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TrafficTab { BLOCKED, LIVE, ACCESS }

@Composable
fun TrafficScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var tab by remember { mutableStateOf(TrafficTab.BLOCKED) }

    DisposableEffect(Unit) {
        vm.setMonitorPollingActive(true)
        onDispose { vm.setMonitorPollingActive(false) }
    }

    LaunchedEffect(tab) {
        if (tab == TrafficTab.ACCESS) {
            vm.refreshPrivacyAccessLog()
        }
    }

    val appLabelMap = remember(state.apps) {
        state.apps.associate { it.packageName to it.label }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        SiloSegmentBar(
            options = listOf(
                TrafficTab.BLOCKED to "${s.trafficBlocked} (${state.telemetry.events.size})",
                TrafficTab.LIVE to "${s.trafficLive} (${state.liveConnections.size})",
                TrafficTab.ACCESS to "${s.trafficAccess} (${state.privacyAccessLog.size})",
            ),
            selected = tab,
            onSelect = { tab = it },
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (tab == TrafficTab.ACCESS) s.accessTimelineBanner else s.ramOnlyBanner,
                style = MaterialTheme.typography.labelSmall,
                color = if (tab == TrafficTab.ACCESS) SiloCyan else SiloEmerald,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab == TrafficTab.BLOCKED && state.telemetry.events.isNotEmpty()) {
                TextButton(
                    onClick = { vm.clearTelemetry() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(s.clearRing, style = MaterialTheme.typography.labelMedium)
                }
            } else if (tab == TrafficTab.ACCESS) {
                IconButton(onClick = { vm.refreshPrivacyAccessLog() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        when (tab) {
            TrafficTab.BLOCKED -> {
                if (state.telemetry.events.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.noBlockedYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(state.telemetry.events) { idx, ev ->
                            BlockedRow(ev)
                            if (idx < state.telemetry.events.lastIndex) SiloHairline()
                        }
                    }
                }
            }

            TrafficTab.LIVE -> {
                if (state.liveConnections.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.noConnections, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(state.liveConnections) { idx, conn ->
                            SocketRow(
                                conn = conn,
                                onKill = if (!conn.isListening) {
                                    { vm.killConnection(conn) }
                                } else null,
                                onBlock = if (!conn.isListening && !AppInventory.isProtectedUid(conn.uid)) {
                                    {
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
                                } else null,
                            )
                            if (idx < state.liveConnections.lastIndex) SiloHairline()
                        }
                    }
                }
            }

            TrafficTab.ACCESS -> {
                if (state.privacyAccessLog.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.noAccessEvents, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(state.privacyAccessLog) { idx, ev ->
                            PrivacyAccessRow(
                                ev = ev,
                                appLabel = appLabelMap[ev.packageName] ?: ev.packageName,
                                categoryLabel = s.categoryLabel(ev.category),
                                runningLabel = s.runningNowBadge,
                                revokeLabel = s.revokeOne,
                                onRevoke = {
                                    vm.setAppPermissionAction(ev.packageName, ev.category, "revoke")
                                    vm.refreshPrivacyAccessLog()
                                },
                            )
                            if (idx < state.privacyAccessLog.lastIndex) SiloHairline()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyAccessRow(
    ev: PrivacyAccessEvent,
    appLabel: String,
    categoryLabel: String,
    runningLabel: String,
    revokeLabel: String,
    onRevoke: () -> Unit,
) {
    val accent = when {
        ev.runningNow -> SiloCrimson
        ev.category in setOf("camera", "microphone", "location") -> SiloAmber
        else -> SiloCyan
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    appLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.16f)) {
                    Text(
                        if (ev.runningNow) "$categoryLabel • $runningLabel" else categoryLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                "${ev.op} (${ev.mode})  •  ${ev.lastAccessLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(
            onClick = onRevoke,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(revokeLabel, style = MaterialTheme.typography.labelSmall, color = SiloCrimson)
        }
    }
}

@Composable
private fun BlockedRow(ev: BlockedPacketEvent) {
    val timeStr = remember(ev.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ev.timestampMs))
    }
    val shortReason = ev.reason.removePrefix("SILO_")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ev.packageName ?: ev.uid?.let { "UID $it" } ?: "Kernel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Surface(shape = RoundedCornerShape(6.dp), color = SiloCrimson.copy(alpha = 0.16f)) {
                    Text(
                        shortReason,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloCrimson,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                "${ev.protocol}  ${ev.dstIp}:${ev.dstPort}  •  $timeStr",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SocketRow(
    conn: ActiveConnection,
    onKill: (() -> Unit)?,
    onBlock: (() -> Unit)?,
) {
    val isUserListener = conn.isListening && !AppInventory.isProtectedUid(conn.uid)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    conn.packageName ?: "UID ${conn.uid}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    "${conn.protocol} ${conn.state}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUserListener) SiloAmber else SiloCyan,
                )
            }
            Text(
                if (conn.isListening) {
                    "LISTEN  ${conn.localIp}:${conn.localPort}"
                } else {
                    "${conn.remoteIp}:${conn.remotePort}  (:${conn.localPort})"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onKill != null && conn.protocol.startsWith("TCP")) {
            IconButton(onClick = onKill, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = SiloAmber, modifier = Modifier.size(18.dp))
            }
        }
        if (onBlock != null) {
            IconButton(onClick = onBlock, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = SiloCrimson, modifier = Modifier.size(18.dp))
            }
        }
    }
}
