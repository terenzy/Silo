package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloAmber
import dev.silo.app.ui.theme.SiloCyan
import dev.silo.app.ui.theme.SiloEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HardeningScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val h = state.config.hardening
    val auto = state.config.automation
    val availCc = state.status.capabilities.availableTcpCongestion

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // GrapheneOS-inspired Privacy Automation Card
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GrapheneOS-Inspired Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                    QuickSwitchRow(
                        title = "Auto-Reboot to BFU (${auto.autoRebootMinutes} min)",
                        subtitle = "Cleanly reboots into encrypted BFU if locked past timeout (CLOCK_BOOTTIME, >=15m safe floor, CE-unlock aware)",
                        checked = auto.autoRebootEnabled,
                        onCheckedChange = { on -> vm.updateAutomationConfig { it.copy(autoRebootEnabled = on) } },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, mins) in listOf("30m" to 30, "2h" to 120, "4h" to 240, "12h" to 720, "18h" to 1080, "24h" to 1440)) {
                            FilterChip(
                                selected = auto.autoRebootMinutes == mins,
                                onClick = { vm.updateAutomationConfig { it.copy(autoRebootMinutes = mins) } },
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider()
                    QuickSwitchRow(
                        title = "Wi-Fi Idle Auto-Off (${auto.wifiAutoOffMinutes} min)",
                        subtitle = "Disable Wi-Fi radio automatically when disconnected to reduce tracking & attack surface",
                        checked = auto.wifiAutoOffEnabled,
                        onCheckedChange = { on -> vm.updateAutomationConfig { it.copy(wifiAutoOffEnabled = on) } },
                    )
                    QuickSwitchRow(
                        title = "Bluetooth Idle Auto-Off (${auto.bluetoothAutoOffMinutes} min)",
                        subtitle = "Disable Bluetooth radio when no active device is connected",
                        checked = auto.bluetoothAutoOffEnabled,
                        onCheckedChange = { on -> vm.updateAutomationConfig { it.copy(bluetoothAutoOffEnabled = on) } },
                    )
                    QuickSwitchRow(
                        title = "Lockscreen Sensors Off",
                        subtitle = "Automatically mute hardware mic/camera via sensor_privacy while screen is locked",
                        checked = auto.sensorsOffOnLock,
                        onCheckedChange = { on -> vm.updateAutomationConfig { it.copy(sensorsOffOnLock = on) } },
                    )
                }
            }
        }

        // Kernel Exploit-Surface Hardening Card
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickSwitchRow(
                        title = "Kernel Hardening (sysctl Guard)",
                        subtitle = "Pre-apply original snapshot saved in RAM for instant rollback",
                        checked = h.enabled,
                        onCheckedChange = { on -> vm.updateHardeningConfig { it.copy(enabled = on) } },
                    )

                    QuickSwitchRow(
                        title = "Disable Unprivileged eBPF (Reversible)",
                        subtitle = "Closes unprivileged bpf() exploit surface (value 2 reversible without reboot)",
                        checked = h.disableUnprivilegedBpf,
                        onCheckedChange = { on -> vm.updateHardeningConfig { it.copy(disableUnprivilegedBpf = on) } },
                    )
                    QuickSwitchRow(
                        title = "Harden eBPF JIT & Kernel Pointers",
                        subtitle = "bpf_jit_harden=2, kptr_restrict=2, perf_event_paranoid=3",
                        checked = h.hardenBpfJit && h.hideKernelPointers,
                        onCheckedChange = { on ->
                            vm.updateHardeningConfig {
                                it.copy(hardenBpfJit = on, hideKernelPointers = on, restrictPerfEvents = on)
                            }
                        },
                    )
                    QuickSwitchRow(
                        title = "Filesystem & SUID Dump Protection",
                        subtitle = "suid_dumpable=0, protected_hardlinks/symlinks/fifos/regular",
                        checked = h.protectFsLinksFifos && h.disableSuidDumpable,
                        onCheckedChange = { on ->
                            vm.updateHardeningConfig {
                                it.copy(protectFsLinksFifos = on, disableSuidDumpable = on)
                            }
                        },
                    )
                    QuickSwitchRow(
                        title = "Anti-Uptime TCP & IPv6 Privacy",
                        subtitle = "tcp_timestamps=0, IPv6 temporary addresses (use_tempaddr=2), loose rp_filter=2 (VPN safe)",
                        checked = h.disableTcpTimestamps && h.ipv6PrivacyExtensions,
                        onCheckedChange = { on ->
                            vm.updateHardeningConfig {
                                it.copy(
                                    disableTcpTimestamps = on,
                                    ipv6PrivacyExtensions = on,
                                    hardenNetworkStack = on,
                                )
                            }
                        },
                    )

                    // BBRv3 / CAKE Network Tuner (leverages SM-A146P kernel features!)
                    if (availCc.isNotEmpty()) {
                        HorizontalDivider()
                        Text("TCP Congestion Control (Available in Kernel)", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = h.tcpCongestion.isEmpty(),
                                onClick = { vm.updateHardeningConfig { it.copy(tcpCongestion = "") } },
                                label = { Text("Default") },
                            )
                            for (cc in availCc) {
                                FilterChip(
                                    selected = h.tcpCongestion == cc,
                                    onClick = { vm.updateHardeningConfig { it.copy(tcpCongestion = cc) } },
                                    label = { Text(cc) },
                                )
                            }
                        }
                    }

                    Text("Packet Scheduler (qdisc)", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, q) in listOf("Default" to "", "CAKE" to "cake", "FQ" to "fq", "FQ_CoDel" to "fq_codel")) {
                            FilterChip(
                                selected = h.defaultQdisc == q,
                                onClick = { vm.updateHardeningConfig { it.copy(defaultQdisc = q) } },
                                label = { Text(label) },
                            )
                        }
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.rollbackHardening() },
                    ) {
                        Text("Rollback All Sysctl to Pre-Silo Snapshot")
                    }
                }
            }
        }

        // Live Sysctl Verification Table
        item {
            Text("Live Kernel Parameter Verification", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        items(state.hardeningStatus.items, key = { it.key }) { item ->
            Card(shape = RoundedCornerShape(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(
                            item.key,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val badgeColor = when {
                        !item.supported -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.enforced -> SiloEmerald
                        else -> SiloAmber
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (item.supported) "${item.current ?: "?"} -> ${item.desired}" else "N/A",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                        )
                        item.original?.let { orig ->
                            Text("orig: $orig", style = MaterialTheme.typography.labelSmall, color = SiloCyan)
                        }
                    }
                }
            }
        }
    }
}
