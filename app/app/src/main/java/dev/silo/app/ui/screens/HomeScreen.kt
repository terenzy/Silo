package dev.silo.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.ipc.BlockAction
import dev.silo.app.ipc.FirewallMode
import dev.silo.app.ipc.LiveProofReport
import dev.silo.app.ipc.SecurityPreset
import dev.silo.app.ipc.SiloConfig
import dev.silo.app.ui.SheetFeedback
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*

/**
 * Exact 4-preset posture matcher aligned with `daemon/src/config.rs:545-617` (APP-H15).
 * Returns `null` for custom/partial configs or whenever Panic Lockdown override is active.
 * `autoRebootEnabled` is intentionally excluded because BFU Auto-Reboot is always a manual opt-in.
 */
fun matchSecurityPreset(
    config: SiloConfig,
    panicLockdownActive: Boolean = config.firewall.panicLockdownActive,
): SecurityPreset? {
    val fw = config.firewall
    val h = config.hardening
    val sc = config.scopes
    val auto = config.automation
    val fz = config.freezer

    if (panicLockdownActive || fw.panicLockdownActive) return null

    val isSafe = !fw.enabled &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        !fw.isolateLan &&
        !fw.dnsLeakGuard &&
        !fw.ipv6LeakGuard &&
        !fw.blockCleartextHttpGlobal &&
        !fw.antiPortScan &&
        !fw.normalizeTtlHl &&
        !fw.blockNewApps &&
        !h.enabled &&
        !fz.enabled &&
        !sc.autoClearClipboard &&
        !auto.sensorsOffOnLock &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isSafe) return SecurityPreset.SAFE

    val isBalanced = fw.enabled &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        fw.isolateLan &&
        fw.dnsLeakGuard &&
        fw.ipv6LeakGuard &&
        fw.antiPortScan &&
        !fw.blockCleartextHttpGlobal &&
        !fw.normalizeTtlHl &&
        !fw.blockNewApps &&
        h.enabled &&
        h.disableTcpTimestamps &&
        h.hardenMemoryAndPtrace &&
        h.ipv6PrivacyExtensions &&
        sc.autoClearClipboard &&
        sc.clipboardClearSecs == 60 &&
        !auto.sensorsOffOnLock &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isBalanced) return SecurityPreset.BALANCED

    val allHardeningFlags = h.enabled &&
        h.hideKernelPointers &&
        h.restrictPerfEvents &&
        h.disableUnprivilegedBpf &&
        h.hardenBpfJit &&
        h.disableSuidDumpable &&
        h.protectFsLinksFifos &&
        h.restrictDmesg &&
        h.disableTcpTimestamps &&
        h.hardenNetworkStack &&
        h.hardenMemoryAndPtrace &&
        h.ipv6PrivacyExtensions

    val hardenedNetAndBase = fw.enabled &&
        fw.isolateLan &&
        fw.dnsLeakGuard &&
        fw.ipv6LeakGuard &&
        fw.antiPortScan &&
        fw.blockCleartextHttpGlobal &&
        fw.normalizeTtlHl &&
        fw.ttlValue == 64 &&
        fw.blockNewApps &&
        allHardeningFlags &&
        sc.autoClearClipboard &&
        auto.sensorsOffOnLock

    val isHardened = hardenedNetAndBase &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        sc.clipboardClearSecs == 45 &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isHardened) return SecurityPreset.HARDENED

    val isParanoid = hardenedNetAndBase &&
        fw.mode == FirewallMode.DEFAULT_DENY &&
        fw.blockAction == BlockAction.REJECT &&
        sc.clipboardClearSecs == 30 &&
        auto.wifiAutoOffEnabled &&
        auto.bluetoothAutoOffEnabled &&
        auto.usbAdbLockGuard
    if (isParanoid) return SecurityPreset.PARANOID

    return null
}

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

    val isPanicActive = state.status.panicLockdownActive || state.config.firewall.panicLockdownActive

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
                    },
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
                    },
                ) { Text(s.confirmReboot) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicRebootDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showProofSheet) {
        val proofSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showProofSheet = false
                vm.clearSheetFeedback()
            },
            sheetState = proofSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LiveProofSheetContent(
                proof = state.liveProof,
                loading = state.liveProofLoading,
                feedback = state.sheetFeedback,
                onDismissFeedback = { vm.clearSheetFeedback() },
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
            val score = state.sentinelReport?.score
            val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
            val scoreColor = when {
                !state.connected || score == null -> mutedColor
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
                            enabled = state.connected,
                            onCheckedChange = { on ->
                                vm.updateFirewallConfig {
                                    it.copy(
                                        enabled = on,
                                        panicLockdownActive = if (on) it.panicLockdownActive else false,
                                    )
                                }
                            },
                        )
                    }

                    if (state.connected && isPanicActive) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SiloCrimson.copy(alpha = 0.16f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.GppBad, contentDescription = null, tint = SiloCrimson, modifier = Modifier.size(16.dp))
                                Text(
                                    s.panicLockdownActiveBanner,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SiloCrimson,
                                )
                            }
                        }
                    }

                    // Minimalist inline stats strip (shows "--" when offline or score pending, APP-M12/L07)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = if (state.connected && score != null) "$score" else "--",
                            label = s.securityScore,
                            accent = scoreColor,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = if (state.connected) state.status.blockedEventsTotal.toString() else "--",
                            label = s.metricBlocked,
                            accent = if (state.connected) SiloCyan else mutedColor,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = if (state.connected) "${state.status.frozenUidsCount + state.status.suspendedPkgsCount}" else "--",
                            label = s.metricFrozen,
                            accent = if (state.connected) SiloEmerald else mutedColor,
                        )
                        StatTile(
                            modifier = Modifier.weight(1f),
                            value = if (state.connected) "${state.status.enforcerRulesCount}" else "--",
                            label = s.metricEnforced,
                            accent = if (state.connected) SiloAmber else mutedColor,
                        )
                    }

                    // Live status footer hidden while daemon is offline (APP-L07)
                    if (state.connected) {
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
        }

        // Live Hardware Controls
        item {
            SiloSectionLabel(s.quickActions)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = if (state.status.micMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                        title = s.muteMic,
                        subtitle = if (state.status.micMuted) s.stateMuted else s.stateLive,
                        active = state.connected && state.status.micMuted,
                        accent = SiloAmber,
                        enabled = state.connected,
                        onClick = { vm.setSensorPrivacy("microphone", !state.status.micMuted) },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = if (state.status.cameraMuted) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                        title = s.muteCamera,
                        subtitle = if (state.status.cameraMuted) s.stateMuted else s.stateLive,
                        active = state.connected && state.status.cameraMuted,
                        accent = SiloAmber,
                        enabled = state.connected,
                        onClick = { vm.setSensorPrivacy("camera", !state.status.cameraMuted) },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Wipe Clipboard works natively via Android ClipboardManager even when daemon is offline!
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.ContentPasteOff,
                        title = s.clearClipboard,
                        subtitle = "Android Native",
                        active = false,
                        accent = SiloCyan,
                        enabled = true,
                        onClick = { vm.clearClipboardNow() },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Verified,
                        title = s.liveProof,
                        subtitle = "iptables • appops",
                        active = state.connected && state.liveProof?.ipv4HookAttached == true,
                        accent = SiloEmerald,
                        enabled = state.connected,
                        onClick = {
                            vm.clearSheetFeedback()
                            showProofSheet = true
                            vm.runLiveProof()
                        },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.GppBad,
                        title = s.panicLockdown,
                        subtitle = s.panicLockdownSub,
                        active = state.connected && (
                            isPanicActive || (
                                state.config.firewall.enabled &&
                                    state.config.firewall.mode == FirewallMode.DEFAULT_DENY &&
                                    state.status.micMuted &&
                                    state.status.cameraMuted
                                )
                            ),
                        accent = SiloCrimson,
                        enabled = state.connected,
                        onClick = { showPanicLockdownDialog = true },
                    )
                    QuickActionTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = s.panicBfu,
                        subtitle = "BFU Lock",
                        active = false,
                        accent = SiloCrimson,
                        enabled = state.connected,
                        onClick = { showPanicRebootDialog = true },
                    )
                }
            }
        }

        // Quick Presets (Exact matcher + Custom/Panic badge, APP-H15)
        item {
            val activePreset = matchSecurityPreset(state.config, isPanicActive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SiloSectionLabel(s.presetsTitle)
                if (state.connected && activePreset == null) {
                    val badgeColor = if (isPanicActive) SiloCrimson else SiloCyan
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.16f),
                    ) {
                        Text(
                            if (isPanicActive) s.panicLockdown else s.presetCustom,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                        )
                    }
                }
            }
            SiloGroup {
                PresetRow(
                    title = s.presetSafe,
                    subtitle = s.presetSafeDesc,
                    selected = state.connected && activePreset == SecurityPreset.SAFE,
                    accent = SiloCyan,
                    enabled = state.connected,
                ) { vm.applyPreset(SecurityPreset.SAFE) }
                SiloHairline()
                PresetRow(
                    title = s.presetBalanced,
                    subtitle = s.presetBalancedDesc,
                    selected = state.connected && activePreset == SecurityPreset.BALANCED,
                    accent = SiloEmerald,
                    enabled = state.connected,
                ) { vm.applyPreset(SecurityPreset.BALANCED) }
                SiloHairline()
                PresetRow(
                    title = s.presetHardened,
                    subtitle = s.presetHardenedDesc,
                    selected = state.connected && activePreset == SecurityPreset.HARDENED,
                    accent = SiloAmber,
                    enabled = state.connected,
                ) { vm.applyPreset(SecurityPreset.HARDENED) }
                SiloHairline()
                PresetRow(
                    title = s.presetLockdown,
                    subtitle = s.presetLockdownDesc,
                    selected = state.connected && activePreset == SecurityPreset.PARANOID,
                    accent = SiloCrimson,
                    enabled = state.connected,
                ) { vm.applyPreset(SecurityPreset.PARANOID) }
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (active) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick),
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick)
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
    loading: Boolean,
    feedback: SheetFeedback?,
    onDismissFeedback: () -> Unit,
    onRefresh: () -> Unit,
) {
    val s = LocalSiloStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                s.runLiveProofRow,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = s.refresh)
            }
        }

        feedback?.let { fb ->
            SiloInlineFeedbackBanner(
                message = fb.message,
                isError = fb.isError,
                onDismiss = onDismissFeedback,
            )
        }

        if (loading && proof != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            loading && proof == null -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }

            proof == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s.liveProofUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) {
                        Text(s.retryAction)
                    }
                }
            }

            else -> {
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
}

