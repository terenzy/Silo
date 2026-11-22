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
    var showPanicRebootDialog by remember { mutableStateOf(false) }
    var showPanicLockdownDialog by remember { mutableStateOf(false) }
    var showProofSheet by remember { mutableStateOf(false) }

    if (showPanicLockdownDialog) {
        AlertDialog(
            onDismissRequest = { showPanicLockdownDialog = false },
            icon = { Icon(Icons.Rounded.GppBad, contentDescription = null, tint = SiloAmber) },
            title = { Text(s.panicLockdownTitle) },
            text = { Text(s.panicLockdownBody) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloAmber, contentColor = Color.Black),
                    onClick = {
                        showPanicLockdownDialog = false
                        vm.triggerPanicLockdown()
                    }
                ) { Text(s.confirmLockdown) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicLockdownDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showPanicRebootDialog) {
        AlertDialog(
            onDismissRequest = { showPanicRebootDialog = false },
            icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = SiloCrimson) },
            title = { Text(s.panicDialogTitle) },
            text = { Text(s.panicDialogBody) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloCrimson),
                    onClick = {
                        showPanicRebootDialog = false
                        vm.triggerPanicReboot()
                    }
                ) { Text(s.confirmReboot) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicRebootDialog = false }) { Text(s.cancel) }
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
                            value = "${state.status.enforcerRulesCount}",
                            label = s.metricEnforced,
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
                    Text(
                        "$bootText  •  DNS: $dnsLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    state.status.lastEnforcerEvent?.let { ev ->
                        Text(
                            ev,
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloEmerald,
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

        // Live Hardware Controls (separated real-time Mic & Camera + Panic Lockdown + Live Proof)
        item {
            SiloSectionLabel(s.quickActions)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Row 1: Separated LIVE Mic & Camera toggles backed by dumpsys sensor_privacy
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = if (state.status.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        title = s.muteMic,
                        subtitle = if (state.status.micMuted) s.stateMuted else s.stateLive,
                        active = state.status.micMuted,
                        accent = SiloAmber,
                        onClick = { vm.setSensorPrivacy("microphone", !state.status.micMuted) },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = if (state.status.cameraMuted) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                        title = s.muteCamera,
                        subtitle = if (state.status.cameraMuted) s.stateMuted else s.stateLive,
                        active = state.status.cameraMuted,
                        accent = SiloAmber,
                        onClick = { vm.setSensorPrivacy("camera", !state.status.cameraMuted) },
                    )
                }
                // Row 2: Wipe Clipboard & Live Kernel Proof
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ContentPasteOff,
                        title = s.clearClipboard,
                        subtitle = "Android Native",
                        active = false,
                        accent = SiloCyan,
                        onClick = { vm.clearClipboardNow() },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Verified,
                        title = s.liveProof,
                        subtitle = "iptables • appops",
                        active = state.liveProof?.ipv4HookAttached == true,
                        accent = SiloEmerald,
                        onClick = {
                            vm.runLiveProof { showProofSheet = true }
                            showProofSheet = true
                        },
                    )
                }
                // Row 3: Panic Lockdown (Non-Destructive) & Emergency Reboot BFU
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.GppBad,
                        title = s.panicLockdown,
                        subtitle = s.panicLockdownSub,
                        active = state.config.firewall.enabled &&
                            state.config.firewall.mode == FirewallMode.DEFAULT_DENY &&
                            state.status.micMuted && state.status.cameraMuted,
                        accent = SiloCrimson,
                        onClick = { showPanicLockdownDialog = true },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = s.panicBfu,
                        subtitle = "BFU Lock",
                        active = false,
                        accent = SiloCrimson,
                        onClick = { showPanicRebootDialog = true },
                    )
                }
            }
        }

        // Quick Presets (Borderless grouped list)
        item {
            SiloSectionLabel(s.presetsTitle)
            SiloGroup {
                val fw = state.config.firewall
                val isSafe = !fw.enabled && !state.config.hardening.enabled
                val isLockdown = fw.enabled && fw.mode == FirewallMode.DEFAULT_DENY
                val isHardened = fw.enabled && !isLockdown && fw.blockCleartextHttpGlobal
                val isBalanced = fw.enabled && !isLockdown && !isHardened

                PresetRow(s.presetSafe, s.presetSafeDesc, isSafe, SiloCyan) {
                    vm.applyPreset(SecurityPreset.SAFE)
                }
                SiloHairline()
                PresetRow(s.presetBalanced, s.presetBalancedDesc, isBalanced, SiloEmerald) {
                    vm.applyPreset(SecurityPreset.BALANCED)
                }
                SiloHairline()
                PresetRow(s.presetHardened, s.presetHardenedDesc, isHardened, SiloAmber) {
                    vm.applyPreset(SecurityPreset.HARDENED)
                }
                SiloHairline()
                PresetRow(s.presetLockdown, s.presetLockdownDesc, isLockdown, SiloCrimson) {
                    vm.applyPreset(SecurityPreset.PARANOID)
                }
            }
            Text(
                s.presetsSubtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier,
    value: String,
    label: String,
    accent: Color,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
            )
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
    val bg = if (active) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = accent.copy(alpha = if (active) 0.28f else 0.14f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = CircleShape,
            color = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant,
        ) {}
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.runLiveProofRow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
        }

        if (proof == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }
        } else {
            SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
                proof.items.forEachIndexed { idx, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (item.verified) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (item.verified) SiloEmerald else SiloAmber,
                            modifier = Modifier.size(19.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.subsystem,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                item.evidence,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (idx < proof.items.lastIndex) SiloHairline()
                }
            }
        }
    }
}
