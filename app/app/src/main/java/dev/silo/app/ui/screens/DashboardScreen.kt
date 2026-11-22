package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.ipc.FirewallMode
import dev.silo.app.ipc.SecurityPreset
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloAmber
import dev.silo.app.ui.theme.SiloCrimson
import dev.silo.app.ui.theme.SiloCyan
import dev.silo.app.ui.theme.SiloEmerald

@Composable
fun DashboardScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    var showPanicDialog by remember { mutableStateOf(false) }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = SiloCrimson) },
            title = { Text("Panic Reboot to BFU?") },
            text = {
                Text(
                    "Device will cleanly reboot back into Before-First-Unlock (BFU) encrypted state so all File-Based Encryption keys are purged from RAM immediately."
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloCrimson),
                    onClick = {
                        showPanicDialog = false
                        vm.triggerPanicReboot()
                    }
                ) { Text("Reboot to BFU") }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Connection / Root Status Banner
        if (!state.connected) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SiloCrimson.copy(alpha = 0.16f)),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = SiloCrimson)
                            Text("Root Daemon Offline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            state.connectionError ?: "Install silo-ksu-module.zip in KernelSU-Next, reboot, and grant root to Silo.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { vm.refreshAll() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry Root Bridge")
                        }
                    }
                }
            }
        }

        // Security Posture Hero Card
        item {
            val score = state.sentinelReport?.score ?: 0
            val scoreColor = when {
                score >= 85 -> SiloEmerald
                score >= 65 -> SiloAmber
                else -> SiloCrimson
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("SILO KERNEL POSTURE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (state.connected) "Kernel ${state.status.capabilities.kernelRelease}" else "Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = scoreColor.copy(alpha = 0.16f),
                        ) {
                            Text(
                                "$score / 100",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                color = scoreColor,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MetricPill(
                            modifier = Modifier.weight(1f),
                            label = "Firewall",
                            value = if (state.status.firewallActive) state.status.firewallMode else "OFF",
                            accent = if (state.status.firewallActive) SiloEmerald else SiloAmber,
                        )
                        MetricPill(
                            modifier = Modifier.weight(1f),
                            label = "Blocked",
                            value = state.status.blockedEventsTotal.toString(),
                            accent = SiloCyan,
                        )
                        MetricPill(
                            modifier = Modifier.weight(1f),
                            label = "Frozen",
                            value = "${state.status.frozenUidsCount} UID",
                            accent = SiloEmerald,
                        )
                    }

                    val guardLabel = when {
                        !state.connected -> "Dead-Man Switch: awaiting daemon"
                        state.status.bootGuardCleared -> "Dead-Man Switch: Cleared (Boot Stable)"
                        state.status.bootGuardArmed -> "Dead-Man Switch: Armed (verifying 45s stability)"
                        else -> "Dead-Man Switch: Ready"
                    }
                    Text(guardLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Security Presets Card
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Security Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "One-tap hardening profiles. Auto-Reboot BFU always remains manual opt-in for safety.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PresetButton("Safe", SecurityPreset.SAFE, Modifier.weight(1f)) { vm.applyPreset(it) }
                        PresetButton("Balanced", SecurityPreset.BALANCED, Modifier.weight(1f)) { vm.applyPreset(it) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PresetButton("Hardened", SecurityPreset.HARDENED, Modifier.weight(1f)) { vm.applyPreset(it) }
                        PresetButton("Paranoid", SecurityPreset.PARANOID, Modifier.weight(1f)) { vm.applyPreset(it) }
                    }
                }
            }
        }

        // Tactical Quick Controls
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tactical Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    QuickSwitchRow(
                        title = "Kernel Firewall",
                        subtitle = if (state.config.firewall.mode == FirewallMode.DEFAULT_DENY) "Strict Default-Deny" else "Flexible Default-Allow",
                        checked = state.config.firewall.enabled,
                        onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(enabled = on) } },
                    )
                    HorizontalDivider()
                    QuickSwitchRow(
                        title = "Hardware Sensors Kill-Switch",
                        subtitle = "Global microphone & camera privacy mute (cmd sensor_privacy)",
                        checked = state.status.sensorsOffActive,
                        onCheckedChange = { vm.setSensorsOff(it) },
                    )
                    HorizontalDivider()
                    QuickSwitchRow(
                        title = "Kernel Exploit-Surface Hardening",
                        subtitle = "Reversible unpriv-BPF off, JIT harden, anti-uptime TCP, IPv6 privacy",
                        checked = state.config.hardening.enabled,
                        onCheckedChange = { on -> vm.updateHardeningConfig { it.copy(enabled = on) } },
                    )
                    HorizontalDivider()
                    QuickSwitchRow(
                        title = "Kernel App Freezer (cgroup v2)",
                        subtitle = "${state.config.freezer.entries.size} app(s) managed, instant foreground auto-thaw",
                        checked = state.config.freezer.enabled,
                        onCheckedChange = { on ->
                            vm.setFreezerEnabled(on, state.config.freezer.backgroundGraceSecs)
                        },
                    )
                }
            }
        }

        // Active Interfaces & Capabilities Overview
        item {
            val caps = state.status.capabilities
            val ifaces = state.status.activeInterfaces
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Kernel Engine Capabilities", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Interfaces: Wi-Fi[${ifaces.wifi.joinToString().ifEmpty { "-" }}]  Cell[${ifaces.cellular.joinToString().ifEmpty { "-" }}]  VPN[${ifaces.vpn.joinToString().ifEmpty { "-" }}]",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Netfilter: iptables=${caps.iptablesAvailable}  ip6=${caps.ip6tablesAvailable}  NFLOG=${caps.nflogAvailable}  TTL/HL=${caps.ttlTargetAvailable}/${caps.hlTargetAvailable}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Advanced: ipsetNetlink=${caps.ipsetNetlinkAvailable}  xt_set=${caps.xtSetAvailable}  SOCK_DESTROY=${caps.sockDestroyAvailable}  cgroupv2=${caps.cgroupV2FreezerAvailable}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Zero-Disk Telemetry: RAM-only ring buffer  |  APK Network Permission: NONE",
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloEmerald,
                    )
                }
            }
        }

        // Emergency Actions Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SiloCrimson.copy(alpha = 0.09f)),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Emergency & BFU Controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { vm.emergencyFlushFirewall() },
                        ) {
                            Text("Flush Firewall")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = SiloCrimson),
                            onClick = { showPanicDialog = true },
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Panic BFU")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(modifier: Modifier, label: String, value: String, accent: Color) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    preset: SecurityPreset,
    modifier: Modifier,
    onClick: (SecurityPreset) -> Unit,
) {
    FilledTonalButton(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        onClick = { onClick(preset) },
    ) {
        Text(label)
    }
}

@Composable
fun QuickSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
