package dev.silo.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.ipc.FirewallMode
import dev.silo.app.ipc.LiveProofReport
import dev.silo.app.ipc.SecurityPreset
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var showPanicDialog by remember { mutableStateOf(false) }
    var showProofSheet by remember { mutableStateOf(false) }

    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = SiloCrimson) },
            title = { Text(s.panicDialogTitle) },
            text = { Text(s.panicDialogBody) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloCrimson),
                    onClick = {
                        showPanicDialog = false
                        vm.triggerPanicReboot()
                    }
                ) { Text(s.confirmReboot) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showProofSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProofSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LiveProofSheetContent(
                proof = state.liveProof,
                onRefresh = { vm.runLiveProof() },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!state.connected) {
            item {
                SiloGroup(color = SiloCrimson.copy(alpha = 0.14f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = SiloCrimson)
                            Text(s.daemonOffline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            state.connectionError ?: s.daemonOfflineDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = { vm.refreshAll() },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.retryBridge)
                        }
                    }
                }
            }
        }

        // Minimalist Hero Posture + Master Switch
        item {
            val score = state.sentinelReport?.score ?: 0
            val scoreColor = when {
                !state.connected -> MaterialTheme.colorScheme.onSurfaceVariant
                score >= 85 -> SiloEmerald
                score >= 65 -> SiloAmber
                else -> SiloCrimson
            }
            val fwOn = state.config.firewall.enabled

            SiloGroup {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                s.firewallMaster,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (fwOn) s.firewallActiveHint else s.firewallOffHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = fwOn,
                            onCheckedChange = { on -> vm.updateFirewallConfig { it.copy(enabled = on) } },
                        )
                    }

                    // Minimalist inline stats strip (borderless tonal pills)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = if (state.connected) "$score" else "--",
                            label = s.securityScore,
                            accent = scoreColor,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = state.status.blockedEventsTotal.toString(),
                            label = s.metricBlocked,
                            accent = SiloCyan,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = "${state.status.frozenUidsCount + state.status.suspendedPkgsCount}",
                            label = s.metricFrozen,
                            accent = SiloEmerald,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = "${state.config.scopes.apps.size}",
                            label = s.metricIsolated,
                            accent = SiloAmber,
                        )
                    }

                    // Compact status footer
                    val dnsLabel = when (state.status.privateDns.mode) {
                        "hostname" -> state.status.privateDns.specifier
                        "off" -> s.dnsOff
                        else -> s.dnsAuto
                    }
                    val bootText = if (state.status.bootGuardCleared) s.bootGuardOk else s.bootGuardArmed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "$bootText  •  DNS: $dnsLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    state.status.lastFirewallError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloAmber,
                        )
                    }
                }
            }
        }

        // Quick Actions 2x2 Borderless Grid
        item {
            SiloSectionLabel(s.quickActions)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = if (state.status.sensorsOffActive) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        title = s.sensorsOff,
                        subtitle = s.sensorsOffShort,
                        active = state.status.sensorsOffActive,
                        accent = SiloAmber,
                        onClick = { vm.setSensorsOff(!state.status.sensorsOffActive) },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ContentPasteOff,
                        title = s.clearClipboard,
                        subtitle = "Android Native",
                        active = false,
                        accent = SiloCyan,
                        onClick = { vm.clearClipboardNow() },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Verified,
                        title = s.liveProof,
                        subtitle = "iptables • cgroup",
                        active = state.liveProof?.ipv4HookAttached == true,
                        accent = SiloEmerald,
                        onClick = {
                            vm.runLiveProof { showProofSheet = true }
                            showProofSheet = true
                        },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = s.panicBfu,
                        subtitle = "BFU Lock",
                        active = false,
                        accent = SiloCrimson,
                        onClick = { showPanicDialog = true },
                    )
                }
            }
        }

        // Clean Flat Presets List
        item {
            SiloSectionLabel(s.presetsTitle)
            Text(
                s.presetsSubtitle,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SiloGroup {
                val activePreset = detectActivePreset(state)
                PresetRow(s.presetSafe, s.presetSafeDesc, activePreset == SecurityPreset.SAFE) {
                    vm.applyPreset(SecurityPreset.SAFE)
                }
                SiloHairline()
                PresetRow(s.presetBalanced, s.presetBalancedDesc, activePreset == SecurityPreset.BALANCED) {
                    vm.applyPreset(SecurityPreset.BALANCED)
                }
                SiloHairline()
                PresetRow(s.presetHardened, s.presetHardenedDesc, activePreset == SecurityPreset.HARDENED) {
                    vm.applyPreset(SecurityPreset.HARDENED)
                }
                SiloHairline()
                PresetRow(s.presetLockdown, s.presetLockdownDesc, activePreset == SecurityPreset.PARANOID) {
                    vm.applyPreset(SecurityPreset.PARANOID)
                }
            }
        }
    }
}

private fun detectActivePreset(state: SiloUiState): SecurityPreset? {
    val fw = state.config.firewall
    return when {
        !fw.enabled && !state.config.hardening.enabled -> SecurityPreset.SAFE
        fw.enabled && fw.mode == FirewallMode.DEFAULT_DENY -> SecurityPreset.PARANOID
        fw.enabled && fw.blockCleartextHttpGlobal && fw.normalizeTtlHl -> SecurityPreset.HARDENED
        fw.enabled && fw.isolateLan && fw.dnsLeakGuard -> SecurityPreset.BALANCED
        else -> null
    }
}

@Composable
private fun StatTile(modifier: Modifier, value: String, label: String, accent: Color) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val bg = if (active) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.16f), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PresetRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun LiveProofSheetContent(
    proof: LiveProofReport?,
    onRefresh: () -> Unit,
) {
    val s = LocalSiloStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(s.runLiveProofRow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
        }
        if (proof == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            proof.items.forEach { item ->
                val tint = if (item.verified) SiloEmerald else SiloAmber
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (item.verified) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.subsystem, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(item.evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
