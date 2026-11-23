package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.data.AppInventory
import dev.silo.app.ipc.ActiveConnection
import dev.silo.app.ipc.BlockedPacketEvent
import dev.silo.app.ipc.PrivacyAccessEvent
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TrafficTab { BLOCKED, LIVE, ACCESS }

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

private fun formatEventTime(timestampMs: Long): String =
    TIME_FMT.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

private fun <T> buildStableKeyedItems(
    items: List<T>,
    baseKey: (T) -> String,
): List<Pair<String, T>> {
    val counts = HashMap<String, Int>(items.size)
    return items.map { item ->
        val raw = baseKey(item)
        val occ = counts.getOrDefault(raw, 0)
        counts[raw] = occ + 1
        "$raw#$occ" to item
    }
}

@Composable
fun TrafficScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var tabName by rememberSaveable { mutableStateOf(TrafficTab.BLOCKED.name) }
    val tab = TrafficTab.entries.firstOrNull { it.name == tabName } ?: TrafficTab.BLOCKED

    DisposableEffect(Unit) {
        vm.setMonitorPollingActive(true)
        onDispose {
            vm.setMonitorAccessTabActive(false)
            vm.setMonitorPollingActive(false)
        }
    }

    LaunchedEffect(tab) {
        val isAccess = tab == TrafficTab.ACCESS
        vm.setMonitorAccessTabActive(isAccess)
        if (isAccess) {
            vm.refreshPrivacyAccessLog(force = false)
        }
    }

    val appLabelMap = remember(state.apps) {
        state.apps.associate { it.packageName to it.label }
    }

    val keyedBlocked = remember(state.telemetry.events) {
        buildStableKeyedItems(state.telemetry.events) { ev ->
            "${ev.timestampMs}_${ev.uid}_${ev.protocol}_${ev.srcPort}_${ev.dstIp}:${ev.dstPort}_${ev.reason}"
        }
    }

    val keyedConnections = remember(state.liveConnections) {
        buildStableKeyedItems(state.liveConnections) { conn ->
            "${conn.uid}_${conn.family}_${conn.protocol}_${conn.state}_${conn.localIp}:${conn.localPort}_${conn.remoteIp}:${conn.remotePort}"
        }
    }

    val keyedAccess = remember(state.privacyAccessLog) {
        buildStableKeyedItems(state.privacyAccessLog) { ev ->
            "${ev.packageName}_${ev.category}_${ev.op}"
        }
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
            onSelect = { tabName = it.name },
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
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
                IconButton(
                    onClick = { vm.refreshPrivacyAccessLog(force = true) },
                    enabled = !state.loadingAccessLog,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    if (state.loadingAccessLog) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = s.refresh,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        when (tab) {
            TrafficTab.BLOCKED -> {
                if (keyedBlocked.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.noBlockedYet, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(keyedBlocked, key = { _, pair -> pair.first }) { idx, (_, ev) ->
                            BlockedRow(ev)
                            if (idx < keyedBlocked.lastIndex) SiloHairline()
                        }
                    }
                }
            }

            TrafficTab.LIVE -> {
                if (keyedConnections.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.noConnections, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        itemsIndexed(keyedConnections, key = { _, pair -> pair.first }) { idx, (_, conn) ->
                            val isProtected = AppInventory.isProtectedUid(conn.uid) ||
                                AppInventory.isProtectedPackage(conn.packageName)
                            val displayName = conn.packageName?.let { appLabelMap[it] ?: it } ?: "UID ${conn.uid}"
                            SocketRow(
                                conn = conn,
                                displayName = displayName,
                                isProtected = isProtected,
                                onKill = if (!conn.isListening && conn.protocol.startsWith("TCP") && !isProtected) {
                                    { vm.killConnection(conn) }
                                } else null,
                                onBlock = if (!isProtected) {
                                    { vm.blockAppFromMonitor(conn.uid, conn.packageName ?: "", displayName) }
                                } else null,
                            )
                            if (idx < keyedConnections.lastIndex) SiloHairline()
                        }
                    }
                }
            }

            TrafficTab.ACCESS -> {
                if (state.loadingAccessLog && keyedAccess.isNotEmpty()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }

                state.accessLogError?.let { err ->
                    SiloGroup(
                        color = SiloCrimson.copy(alpha = 0.14f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = SiloCrimson, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.accessLogLoadFailed,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SiloCrimson,
                                )
                                Text(
                                    err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { vm.refreshPrivacyAccessLog(force = true) },
                                enabled = !state.loadingAccessLog,
                            ) {
                                Text(s.retryAction)
                            }
                        }
                    }
                }

                when {
                    state.loadingAccessLog && keyedAccess.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                                Text(
                                    s.loadingAccessLog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    keyedAccess.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(s.noAccessEvents, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 20.dp),
                        ) {
                            itemsIndexed(keyedAccess, key = { _, pair -> pair.first }) { idx, (_, ev) ->
                                val isProtected = AppInventory.isProtectedUid(ev.uid) ||
                                    AppInventory.isProtectedPackage(ev.packageName)
                                val isPending = state.pendingPermKeys.any {
                                    it.startsWith("${ev.packageName}:${ev.category}:")
                                }
                                PrivacyAccessRow(
                                    ev = ev,
                                    appLabel = appLabelMap[ev.packageName] ?: ev.packageName,
                                    categoryLabel = s.categoryLabel(ev.category),
                                    runningLabel = s.runningNowBadge,
                                    revokeLabel = s.revokeOne,
                                    isProtected = isProtected,
                                    isPending = isPending,
                                    onRevoke = { vm.revokeFromAccessLog(ev.packageName, ev.category) },
                                )
                                if (idx < keyedAccess.lastIndex) SiloHairline()
                            }
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
    isProtected: Boolean,
    isPending: Boolean,
    onRevoke: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val normalizedMode = ev.mode.trim().lowercase(Locale.ROOT)
    val isBlocked = normalizedMode == "ignore" || normalizedMode == "deny"

    val accent = when {
        ev.runningNow -> SiloCrimson
        isBlocked -> SiloEmerald
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

        when {
            isProtected -> {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Text(
                        s.protectedBadge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            isBlocked -> {
                val blockedLabel = if (normalizedMode == "ignore") s.permStateIgnored else s.permStateDenied
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SiloEmerald.copy(alpha = 0.16f),
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = SiloEmerald,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            blockedLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloEmerald,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }

            else -> {
                FilledTonalButton(
                    onClick = onRevoke,
                    enabled = !isPending,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .minimumInteractiveComponentSize(),
                ) {
                    if (isPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = SiloCrimson,
                        )
                    } else {
                        Text(revokeLabel, style = MaterialTheme.typography.labelSmall, color = SiloCrimson)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedRow(ev: BlockedPacketEvent) {
    val timeStr = remember(ev.timestampMs) { formatEventTime(ev.timestampMs) }
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
    displayName: String,
    isProtected: Boolean,
    onKill: (() -> Unit)?,
    onBlock: (() -> Unit)?,
) {
    val s = LocalSiloStrings.current
    val isUserListener = conn.isListening && !isProtected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    displayName,
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
        if (onKill != null) {
            IconButton(
                onClick = onKill,
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = s.killSocket,
                    tint = SiloAmber,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        if (onBlock != null) {
            IconButton(
                onClick = onBlock,
                modifier = Modifier
                    .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                    .minimumInteractiveComponentSize(),
            ) {
                Icon(
                    Icons.Rounded.Block,
                    contentDescription = s.blockApp,
                    tint = SiloCrimson,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

