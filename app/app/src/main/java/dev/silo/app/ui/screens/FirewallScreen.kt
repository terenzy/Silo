package dev.silo.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.data.InstalledApp
import dev.silo.app.ipc.AppFirewallRule
import dev.silo.app.ipc.BlockAction
import dev.silo.app.ipc.FirewallMode
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloAmber
import dev.silo.app.ui.theme.SiloCrimson
import dev.silo.app.ui.theme.SiloEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirewallScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val fw = state.config.firewall
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var showGlobals by remember { mutableStateOf(true) }
    var cidrInput by remember { mutableStateOf("") }

    val importBlocklistLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importIpBlocklistFromUri(uri)
    }

    val filteredApps = remember(state.apps, query, showSystem, fw.rules) {
        val q = query.trim().lowercase()
        state.apps.filter { app ->
            !app.isProtected &&
                (showSystem || !app.isSystem || fw.rules.containsKey(app.uid)) &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q) || app.uid.toString().contains(q))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Master Firewall & Mode Header
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickSwitchRow(
                        title = "Kernel Firewall Active",
                        subtitle = "Netfilter iptables/ip6tables + ipset (Zero VPN slot used)",
                        checked = fw.enabled,
                        onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(enabled = on) } },
                    )

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = fw.mode == FirewallMode.DEFAULT_ALLOW,
                            onClick = { vm.updateFirewallConfig { it.copy(mode = FirewallMode.DEFAULT_ALLOW) } },
                            label = { Text("Default-Allow") },
                        )
                        FilterChip(
                            selected = fw.mode == FirewallMode.DEFAULT_DENY,
                            onClick = { vm.updateFirewallConfig { it.copy(mode = FirewallMode.DEFAULT_DENY) } },
                            label = { Text("Default-Deny") },
                        )
                        FilterChip(
                            selected = fw.blockAction == BlockAction.REJECT,
                            onClick = {
                                val next = if (fw.blockAction == BlockAction.REJECT) BlockAction.DROP else BlockAction.REJECT
                                vm.updateFirewallConfig { it.copy(blockAction = next) }
                            },
                            label = { Text(if (fw.blockAction == BlockAction.REJECT) "REJECT (Fast)" else "DROP (Silent)") },
                        )
                    }

                    state.status.lastFirewallError?.let { err ->
                        Text("Last error: $err", color = SiloCrimson, style = MaterialTheme.typography.bodySmall)
                    }

                    TextButton(onClick = { showGlobals = !showGlobals }) {
                        Text(if (showGlobals) "Hide Global Anti-Leak Guards" else "Show Global Anti-Leak Guards")
                    }

                    if (showGlobals) {
                        HorizontalDivider()
                        QuickSwitchRow(
                            title = "LAN & Multicast Isolation",
                            subtitle = "Block RFC1918, link-local, mDNS/SSDP scanning unless per-app LAN allowed",
                            checked = fw.isolateLan,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(isolateLan = on) } },
                        )
                        QuickSwitchRow(
                            title = "DNS Leak Guard (Port 53)",
                            subtitle = "Block plain DNS bypass on Wi-Fi/Cellular while keeping Android netd & VPN working",
                            checked = fw.dnsLeakGuard,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(dnsLeakGuard = on) } },
                        )
                        QuickSwitchRow(
                            title = "IPv6 VPN Leak Guard",
                            subtitle = "Drop non-VPN IPv6 egress whenever tun+/wg+ VPN is active",
                            checked = fw.ipv6LeakGuard,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(ipv6LeakGuard = on) } },
                        )
                        QuickSwitchRow(
                            title = "Block Cleartext HTTP (Port 80)",
                            subtitle = "Global cleartext HTTP block for user apps (GrapheneOS-style)",
                            checked = fw.blockCleartextHttpGlobal,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(blockCleartextHttpGlobal = on) } },
                        )
                        QuickSwitchRow(
                            title = "TTL / Hop-Limit Normalization (64)",
                            subtitle = "Normalize IPv4 TTL & IPv6 HL against network/tethering fingerprinting",
                            checked = fw.normalizeTtlHl,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(normalizeTtlHl = on) } },
                        )
                        QuickSwitchRow(
                            title = "Auto-Block Newly Installed Apps",
                            subtitle = "Automatically deny network for newly detected user UIDs",
                            checked = fw.blockNewApps,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(blockNewApps = on) } },
                        )
                        QuickSwitchRow(
                            title = "SOCK_DESTROY on Block",
                            subtitle = "Immediately sever existing active TCP sockets when blocking an app",
                            checked = fw.sockDestroyOnBlock,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(sockDestroyOnBlock = on) } },
                        )
                    }
                }
            }
        }

        // Local IPset Blocklist Manager (Strict Offline-First)
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Kernel IP/CIDR Blocklist", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${fw.ipBlocklist.size} entries (${state.status.ipsetBackend})",
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloEmerald,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = cidrInput,
                            onValueChange = { cidrInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Add IP or CIDR (e.g. 203.0.113.0/24)") },
                        )
                        FilledTonalButton(onClick = {
                            if (cidrInput.isNotBlank()) {
                                vm.addSingleBlocklistCidr(cidrInput)
                                cidrInput = ""
                            }
                        }) { Text("Add") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { importBlocklistLauncher.launch(arrayOf("text/*", "*/*")) },
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import Local File")
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = fw.ipBlocklist.isNotEmpty(),
                            onClick = { vm.clearIpBlocklist() },
                        ) { Text("Clear List") }
                    }
                }
            }
        }

        // Per-App Search & Filter Bar
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Filter apps (${filteredApps.size})") },
                )
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text("System") },
                )
            }
        }

        // Per-App Firewall Matrix
        items(filteredApps, key = { "${it.uid}:${it.packageName}" }) { app ->
            val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW
            val rule = fw.rules[app.uid] ?: AppFirewallRule(
                packageName = app.packageName,
                allowWifi = defaultAllow,
                allowCellular = defaultAllow,
                allowVpn = defaultAllow,
                allowLan = false,
                allowScreenOff = defaultAllow,
            )
            AppFirewallCard(
                app = app,
                rule = rule,
                hasExplicitRule = fw.rules.containsKey(app.uid),
                blockedCount = state.telemetry.perUidBlocked[app.uid] ?: 0L,
                onUpdate = { next -> vm.updateAppFirewallRule(app.uid, next.copy(packageName = app.packageName)) },
                onReset = { vm.removeAppFirewallRule(app.uid) },
                onKillSockets = { vm.killUidSockets(app.uid) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppFirewallCard(
    app: InstalledApp,
    rule: AppFirewallRule,
    hasExplicitRule: Boolean,
    blockedCount: Long,
    onUpdate: (AppFirewallRule) -> Unit,
    onReset: () -> Unit,
    onKillSockets: () -> Unit,
) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${app.packageName}  •  UID ${app.uid}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (blockedCount > 0) {
                    Surface(shape = RoundedCornerShape(8.dp), color = SiloAmber.copy(alpha = 0.16f)) {
                        Text(
                            "$blockedCount blk",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloAmber,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = rule.allowWifi,
                    onClick = { onUpdate(rule.copy(allowWifi = !rule.allowWifi)) },
                    label = { Text("Wi-Fi") },
                )
                FilterChip(
                    selected = rule.allowCellular,
                    onClick = { onUpdate(rule.copy(allowCellular = !rule.allowCellular)) },
                    label = { Text("Cell") },
                )
                FilterChip(
                    selected = rule.allowVpn,
                    onClick = { onUpdate(rule.copy(allowVpn = !rule.allowVpn)) },
                    label = { Text("VPN") },
                )
                FilterChip(
                    selected = rule.allowScreenOff,
                    onClick = { onUpdate(rule.copy(allowScreenOff = !rule.allowScreenOff)) },
                    label = { Text("Bg/Off") },
                )
                FilterChip(
                    selected = rule.allowLan,
                    onClick = { onUpdate(rule.copy(allowLan = !rule.allowLan)) },
                    label = { Text("LAN") },
                )
                FilterChip(
                    selected = rule.blockCleartextHttp,
                    onClick = { onUpdate(rule.copy(blockCleartextHttp = !rule.blockCleartextHttp)) },
                    label = { Text("No HTTP") },
                )
                FilterChip(
                    selected = rule.blockLocalhost,
                    onClick = { onUpdate(rule.copy(blockLocalhost = !rule.blockLocalhost)) },
                    label = { Text("No 127.0") },
                )
                FilterChip(
                    selected = rule.isVpnApp,
                    onClick = { onUpdate(rule.copy(isVpnApp = !rule.isVpnApp)) },
                    label = { Text("VPN App") },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onKillSockets) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Kill TCP")
                }
                if (hasExplicitRule) {
                    TextButton(onClick = onReset) { Text("Reset") }
                }
            }
        }
    }
}
