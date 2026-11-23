package dev.silo.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.BuildConfig
import dev.silo.app.data.AppLanguagePref
import dev.silo.app.data.AppThemePref
import dev.silo.app.ipc.*
import dev.silo.app.ui.SheetFeedback
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*

/** Formats minute durations cleanly (30 -> "30m", 60 -> "1h", 90 -> "1h 30m", APP-H16). */
fun formatDurationLabel(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    return when {
        safe < 60 -> "${safe}m"
        safe % 60 == 0 -> "${safe / 60}h"
        else -> "${safe / 60}h ${safe % 60}m"
    }
}

/** Formats second durations cleanly (45 -> "45s", 60 -> "1m", 90 -> "1m 30s"). */
fun formatSecondsLabel(secs: Int): String {
    val safe = secs.coerceAtLeast(0)
    return when {
        safe < 60 -> "${safe}s"
        safe % 60 == 0 -> "${safe / 60}m"
        else -> "${safe / 60}m ${safe % 60}s"
    }
}

val DEFAULT_DNS_PRESET_HOSTS: Set<String> = setOf(
    "dns.quad9.net",
    "dns.mullvad.net",
    "dns.adguard-dns.com",
    "one.one.one.one",
)

/** Returns true iff Private DNS is set to a non-blank custom DoT hostname (APP-H19). */
fun isCustomPrivateDns(
    mode: String,
    specifier: String,
    presetHosts: Collection<String> = DEFAULT_DNS_PRESET_HOSTS,
): Boolean {
    if (mode != "hostname") return false
    val clean = specifier.trim()
    if (clean.isEmpty()) return false
    return presetHosts.none { it.equals(clean, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SystemScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    val fw = state.config.firewall
    val auto = state.config.automation
    val h = state.config.hardening
    val rp = state.status.radioPrivacy

    var showBlocklistSheet by remember { mutableStateOf(false) }
    var showSysctlSheet by remember { mutableStateOf(false) }
    var showProofSheet by remember { mutableStateOf(false) }
    var showAuditSheet by remember { mutableStateOf(false) }
    var showGrapheneSheet by remember { mutableStateOf(false) }
    var showCustomDnsDialog by remember { mutableStateOf(false) }
    var showRollbackDialog by remember { mutableStateOf(false) }
    var showEmergencyFlushDialog by remember { mutableStateOf(false) }
    var customDnsHost by remember { mutableStateOf(state.status.privateDns.specifier) }
    var cidrInput by remember { mutableStateOf("") }

    val importBlocklistLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importIpBlocklistFromUri(uri) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportConfigToUri(uri) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importConfigFromUri(uri) }

    if (showCustomDnsDialog) {
        val trimmedDns = customDnsHost.trim()
        AlertDialog(
            onDismissRequest = { showCustomDnsDialog = false },
            title = { Text(s.secPrivateDns) },
            text = {
                OutlinedTextField(
                    value = customDnsHost,
                    onValueChange = { customDnsHost = it },
                    singleLine = true,
                    label = { Text(s.customDnsHint) },
                )
            },
            confirmButton = {
                Button(
                    enabled = trimmedDns.isNotBlank(),
                    onClick = {
                        showCustomDnsDialog = false
                        vm.setPrivateDns("hostname", trimmedDns)
                    },
                ) { Text(s.apply) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDnsDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showRollbackDialog) {
        AlertDialog(
            onDismissRequest = { showRollbackDialog = false },
            icon = { Icon(Icons.Rounded.Restore, contentDescription = null, tint = SiloAmber) },
            title = { Text(s.rollbackConfirmTitle) },
            text = { Text(s.rollbackConfirmBody) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloAmber, contentColor = Color.Black),
                    onClick = {
                        showRollbackDialog = false
                        vm.rollbackHardening()
                    },
                ) { Text(s.confirmRollback) }
            },
            dismissButton = {
                TextButton(onClick = { showRollbackDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showEmergencyFlushDialog) {
        AlertDialog(
            onDismissRequest = { showEmergencyFlushDialog = false },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = SiloCrimson) },
            title = { Text(s.emergencyFlushConfirmTitle) },
            text = { Text(s.emergencyFlushConfirmBody) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = SiloCrimson),
                    onClick = {
                        showEmergencyFlushDialog = false
                        vm.emergencyFlushFirewall()
                    },
                ) { Text(s.confirmFlush) }
            },
            dismissButton = {
                TextButton(onClick = { showEmergencyFlushDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showGrapheneSheet) {
        val grapheneSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showGrapheneSheet = false },
            sheetState = grapheneSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.grapheneNotesRow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    s.grapheneNotesBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showBlocklistSheet) {
        val blocklistSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showBlocklistSheet = false
                vm.clearSheetFeedback()
            },
            sheetState = blocklistSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            val uniqueEntries = remember(fw.ipBlocklist) { fw.ipBlocklist.distinct() }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.ipBlocklistRow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${uniqueEntries.size} IP/CIDR  •  Backend: ${state.status.ipsetBackend}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SiloEmerald,
                )

                state.sheetFeedback?.let { fb ->
                    SiloInlineFeedbackBanner(
                        message = fb.message,
                        isError = fb.isError,
                        onDismiss = { vm.clearSheetFeedback() },
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = cidrInput,
                        onValueChange = { cidrInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(s.addCidrHint) },
                    )
                    FilledTonalButton(
                        enabled = cidrInput.trim().isNotBlank(),
                        onClick = {
                            val trimmed = cidrInput.trim()
                            if (trimmed.isNotEmpty()) {
                                vm.addSingleBlocklistCidr(trimmed)
                                cidrInput = ""
                            }
                        },
                    ) { Text("+") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { importBlocklistLauncher.launch(arrayOf("text/*", "*/*")) },
                    ) { Text(s.importFile) }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        enabled = uniqueEntries.isNotEmpty(),
                        onClick = { vm.clearIpBlocklist() },
                    ) { Text(s.clearList) }
                }

                if (uniqueEntries.isEmpty()) {
                    Text(
                        s.blocklistEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                        ) {
                            items(items = uniqueEntries, key = { it }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        entry,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = { vm.removeSingleBlocklistCidr(entry) },
                                        modifier = Modifier.minimumInteractiveComponentSize(),
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = s.removeEntry,
                                            tint = SiloCrimson,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                SiloHairline()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSysctlSheet) {
        val sysctlSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showSysctlSheet = false
                vm.clearSheetFeedback()
            },
            sheetState = sysctlSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.inspectSysctl,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { vm.refreshHardeningStatus() },
                        enabled = !state.hardeningLoading,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = s.refresh)
                    }
                }

                state.sheetFeedback?.let { fb ->
                    SiloInlineFeedbackBanner(
                        message = fb.message,
                        isError = fb.isError,
                        onDismiss = { vm.clearSheetFeedback() },
                    )
                }

                if (state.hardeningLoading && state.hardeningStatus.items.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                when {
                    state.hardeningLoading && state.hardeningStatus.items.isEmpty() -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        }
                    }

                    state.hardeningStatus.items.isEmpty() -> {
                        Text(
                            s.sysctlUnavailable,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        state.hardeningStatus.items.forEach { item ->
                            val color = when {
                                !item.supported -> MaterialTheme.colorScheme.onSurfaceVariant
                                item.enforced -> SiloEmerald
                                else -> SiloAmber
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text(
                                        item.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (item.supported) "${item.current ?: "?"} / ${item.desired}" else "N/A",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = color,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            SiloHairline()
                        }
                    }
                }
            }
        }
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

    if (showAuditSheet) {
        val auditSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showAuditSheet = false
                vm.clearSheetFeedback()
            },
            sheetState = auditSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SentinelAuditSheetContent(
                report = state.sentinelReport,
                loading = state.sentinelLoading,
                feedback = state.sheetFeedback,
                onDismissFeedback = { vm.clearSheetFeedback() },
                onRefresh = { vm.runSentinelAudit() },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Private DNS (DoT) 1-Click Switcher
        item {
            SiloSectionLabel(s.secPrivateDns)
            SiloGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val curDns = state.status.privateDns
                    Text(
                        "${s.privateDnsDesc}  (${if (curDns.mode == "hostname") curDns.specifier else curDns.mode})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val dnsPresets = listOf(
                        Triple("off", "", s.dnsOff),
                        Triple("opportunistic", "", s.dnsAuto),
                        Triple("hostname", "dns.quad9.net", "Quad9"),
                        Triple("hostname", "dns.mullvad.net", "Mullvad"),
                        Triple("hostname", "dns.adguard-dns.com", "AdGuard"),
                        Triple("hostname", "one.one.one.one", "1.1.1.1"),
                    )
                    val presetHosts = dnsPresets.filter { it.first == "hostname" }.map { it.second }
                    val isCustomActive = isCustomPrivateDns(curDns.mode, curDns.specifier, presetHosts)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dnsPresets.take(3).forEach { (m, host, label) ->
                            val active = curDns.mode == m && (m != "hostname" || curDns.specifier.equals(host, ignoreCase = true))
                            DnsPill(label, active, Modifier.weight(1f)) { vm.setPrivateDns(m, host) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dnsPresets.drop(3).forEach { (m, host, label) ->
                            val active = curDns.mode == m && curDns.specifier.equals(host, ignoreCase = true)
                            DnsPill(label, active, Modifier.weight(1f)) { vm.setPrivateDns(m, host) }
                        }
                        DnsPill(s.dnsCustom, isCustomActive, Modifier.weight(1f)) {
                            customDnsHost = curDns.specifier
                            showCustomDnsDialog = true
                        }
                    }
                }
            }
        }

        // 2. Network & Radio Privacy (GrapheneOS-style, live from Android Settings Global)
        item {
            SiloSectionLabel(s.secRadioPrivacy)
            SiloGroup {
                SiloSwitchRow(s.enhancedMacRand, s.enhancedMacRandDesc, rp.enhancedMacRandomization) { on ->
                    vm.setRadioPrivacy(enhancedMacRandomization = on)
                }
                SiloHairline()
                SiloSwitchRow(s.disableBgScan, s.disableBgScanDesc, rp.disableWifiBleScanning) { on ->
                    vm.setRadioPrivacy(disableWifiBleScanning = on)
                }
                SiloHairline()
                SiloSwitchRow(s.disableCaptivePortal, s.disableCaptivePortalDesc, rp.disableCaptivePortal) { on ->
                    vm.setRadioPrivacy(disableCaptivePortal = on)
                }
                if (rp.adbEnabledLive) {
                    SiloHairline()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Usb, contentDescription = null, tint = SiloAmber, modifier = Modifier.size(16.dp))
                        Text(s.adbLiveWarning, style = MaterialTheme.typography.labelSmall, color = SiloAmber)
                    }
                }
            }
        }

        // 3. Global Network Guards
        item {
            SiloSectionLabel(s.secGlobalNet)
            SiloGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.fwModeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SiloSegmentBar(
                        options = listOf(
                            FirewallMode.DEFAULT_ALLOW to s.fwModeAllow,
                            FirewallMode.DEFAULT_DENY to s.fwModeDeny,
                        ),
                        selected = fw.mode,
                        onSelect = { m ->
                            vm.updateFirewallConfig { it.copy(mode = m, panicLockdownActive = false) }
                        },
                    )
                    Text(s.blockActionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SiloSegmentBar(
                        options = listOf(
                            BlockAction.REJECT to s.actionReject,
                            BlockAction.DROP to s.actionDrop,
                        ),
                        selected = fw.blockAction,
                        onSelect = { a -> vm.updateFirewallConfig { it.copy(blockAction = a) } },
                    )
                }
                SiloHairline()
                SiloSwitchRow(s.isolateLan, s.isolateLanDesc, fw.isolateLan) { on ->
                    vm.updateFirewallConfig { it.copy(isolateLan = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.dnsLeakGuard, s.dnsLeakGuardDesc, fw.dnsLeakGuard) { on ->
                    vm.updateFirewallConfig { it.copy(dnsLeakGuard = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.ipv6LeakGuard, s.ipv6LeakGuardDesc, fw.ipv6LeakGuard) { on ->
                    vm.updateFirewallConfig { it.copy(ipv6LeakGuard = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.antiPortScan, s.antiPortScanDesc, fw.antiPortScan) { on ->
                    vm.updateFirewallConfig { it.copy(antiPortScan = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.blockHttpGlobal, s.blockHttpGlobalDesc, fw.blockCleartextHttpGlobal) { on ->
                    vm.updateFirewallConfig { it.copy(blockCleartextHttpGlobal = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.normalizeTtl, s.normalizeTtlDesc, fw.normalizeTtlHl) { on ->
                    vm.updateFirewallConfig { it.copy(normalizeTtlHl = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.blockNewApps, s.blockNewAppsDesc, fw.blockNewApps) { on ->
                    vm.updateFirewallConfig { it.copy(blockNewApps = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.sockDestroyOnBlock, s.sockDestroyOnBlockDesc, fw.sockDestroyOnBlock) { on ->
                    vm.updateFirewallConfig { it.copy(sockDestroyOnBlock = on) }
                }
                SiloHairline()
                SiloActionRow(
                    title = s.ipBlocklistRow,
                    subtitle = s.ipBlocklistDesc,
                    icon = Icons.AutoMirrored.Rounded.ListAlt,
                    trailingText = "${fw.ipBlocklist.size}",
                    onClick = {
                        vm.clearSheetFeedback()
                        showBlocklistSheet = true
                    },
                )
            }
        }

        // 4. Privacy Automation (GrapheneOS-style)
        item {
            SiloSectionLabel(s.secAutomation)
            SiloGroup {
                SiloSwitchRow(s.usbAdbLockGuard, s.usbAdbLockGuardDesc, auto.usbAdbLockGuard) { on ->
                    vm.updateAutomationConfig { it.copy(usbAdbLockGuard = on) }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.autoRebootBfu} (${formatDurationLabel(auto.autoRebootMinutes)})",
                    s.autoRebootBfuDesc,
                    auto.autoRebootEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(autoRebootEnabled = on) } }

                if (auto.autoRebootEnabled) {
                    DurationPillRow(
                        options = listOf("30m" to 30, "4h" to 240, "12h" to 720, "18h" to 1080),
                        selected = auto.autoRebootMinutes,
                    ) { mins ->
                        vm.updateAutomationConfig { it.copy(autoRebootMinutes = mins) }
                    }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.autoClearClip} (${formatSecondsLabel(state.config.scopes.clipboardClearSecs)})",
                    s.autoClearClipDesc,
                    state.config.scopes.autoClearClipboard,
                ) { on -> vm.setAutoClearClipboard(on, state.config.scopes.clipboardClearSecs) }
                if (state.config.scopes.autoClearClipboard) {
                    DurationPillRow(
                        options = listOf("15s" to 15, "30s" to 30, "45s" to 45, "1m" to 60, "2m" to 120),
                        selected = state.config.scopes.clipboardClearSecs,
                    ) { secs ->
                        vm.setAutoClearClipboard(true, secs)
                    }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.wifiAutoOff} (${formatDurationLabel(auto.wifiAutoOffMinutes)})",
                    s.wifiAutoOffDesc,
                    auto.wifiAutoOffEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(wifiAutoOffEnabled = on) } }
                if (auto.wifiAutoOffEnabled) {
                    DurationPillRow(
                        options = listOf("5m" to 5, "15m" to 15, "30m" to 30, "1h" to 60),
                        selected = auto.wifiAutoOffMinutes,
                    ) { mins ->
                        vm.updateAutomationConfig { it.copy(wifiAutoOffMinutes = mins) }
                    }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.btAutoOff} (${formatDurationLabel(auto.bluetoothAutoOffMinutes)})",
                    s.btAutoOffDesc,
                    auto.bluetoothAutoOffEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(bluetoothAutoOffEnabled = on) } }
                if (auto.bluetoothAutoOffEnabled) {
                    DurationPillRow(
                        options = listOf("5m" to 5, "15m" to 15, "30m" to 30, "1h" to 60),
                        selected = auto.bluetoothAutoOffMinutes,
                    ) { mins ->
                        vm.updateAutomationConfig { it.copy(bluetoothAutoOffMinutes = mins) }
                    }
                }
                SiloHairline()
                SiloSwitchRow(s.lockSensorsOff, s.lockSensorsOffDesc, auto.sensorsOffOnLock) { on ->
                    vm.updateAutomationConfig { it.copy(sensorsOffOnLock = on) }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.freezerMaster} (${formatSecondsLabel(state.config.freezer.backgroundGraceSecs)})",
                    s.freezerMasterDesc,
                    state.config.freezer.enabled,
                ) { on ->
                    vm.setFreezerEnabled(on, state.config.freezer.backgroundGraceSecs)
                }
                if (state.config.freezer.enabled) {
                    DurationPillRow(
                        options = listOf("10s" to 10, "20s" to 20, "1m" to 60, "2m" to 120),
                        selected = state.config.freezer.backgroundGraceSecs,
                    ) { secs ->
                        vm.setFreezerEnabled(true, secs)
                    }
                }
                if (state.status.frozenUidsCount > 0 || state.status.suspendedPkgsCount > 0) {
                    SiloHairline()
                    SiloActionRow(
                        title = s.thawAll,
                        icon = Icons.Rounded.WbSunny,
                        iconTint = SiloCyan,
                        onClick = { vm.thawAllNow() },
                    )
                }
            }
        }

        // 5. Kernel Hardening (sysctl) + BBRv3 / CAKE
        item {
            SiloSectionLabel(s.secKernelHardening)
            SiloGroup {
                SiloSwitchRow(s.hardeningMaster, s.hardeningMasterDesc, h.enabled) { on ->
                    vm.updateHardeningConfig { it.copy(enabled = on) }
                }
                val availCc = state.status.capabilities.availableTcpCongestion
                if (availCc.isNotEmpty()) {
                    SiloHairline()
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.tcpCongestion, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            DnsPill("Default", h.tcpCongestion.isEmpty(), Modifier.defaultMinSize(minWidth = 72.dp)) {
                                vm.updateHardeningConfig { it.copy(tcpCongestion = "") }
                            }
                            availCc.forEach { cc ->
                                DnsPill(cc, h.tcpCongestion == cc, Modifier.defaultMinSize(minWidth = 72.dp)) {
                                    vm.updateHardeningConfig { it.copy(enabled = true, tcpCongestion = cc) }
                                }
                            }
                        }
                    }
                }
                SiloHairline()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.packetScheduler, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((lbl, q) in listOf("Default" to "", "CAKE" to "cake", "FQ" to "fq", "PIE" to "pie")) {
                            DnsPill(lbl, h.defaultQdisc == q, Modifier.weight(1f)) {
                                vm.updateHardeningConfig {
                                    if (q.isEmpty()) it.copy(defaultQdisc = "")
                                    else it.copy(enabled = true, defaultQdisc = q)
                                }
                            }
                        }
                    }
                }
                SiloHairline()
                SiloActionRow(
                    title = s.inspectSysctl,
                    icon = Icons.Rounded.Memory,
                    trailingText = "${state.hardeningStatus.items.count { it.enforced }}/${state.hardeningStatus.items.count { it.supported }}",
                    onClick = {
                        vm.clearSheetFeedback()
                        showSysctlSheet = true
                        vm.refreshHardeningStatus()
                    },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.rollbackSysctl,
                    icon = Icons.Rounded.Restore,
                    iconTint = SiloAmber,
                    onClick = { showRollbackDialog = true },
                )
            }
        }

        // 6. Verification, Audit & GrapheneOS Hardware Transparency
        item {
            SiloSectionLabel(s.secVerification)
            SiloGroup {
                SiloActionRow(
                    title = s.runLiveProofRow,
                    subtitle = s.runLiveProofDesc,
                    icon = Icons.Rounded.Verified,
                    iconTint = SiloEmerald,
                    onClick = {
                        vm.clearSheetFeedback()
                        showProofSheet = true
                        vm.runLiveProof()
                    },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.runAuditRow,
                    subtitle = s.runAuditDesc,
                    icon = Icons.Rounded.Security,
                    iconTint = SiloCyan,
                    trailingText = state.sentinelReport?.let { "${it.score}/100" },
                    onClick = {
                        vm.clearSheetFeedback()
                        showAuditSheet = true
                        vm.runSentinelAudit()
                    },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.grapheneNotesRow,
                    subtitle = s.grapheneNotesDesc,
                    icon = Icons.Rounded.Info,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { showGrapheneSheet = true },
                )
            }
        }

        // 7. Appearance, Language, Disguise, Backup & Emergency Flush
        item {
            SiloSectionLabel(s.secPreferences)
            SiloGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(s.languageLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SiloSegmentBar(
                        options = listOf(
                            AppLanguagePref.SYSTEM to "Auto",
                            AppLanguagePref.INDONESIAN to "Indonesia",
                            AppLanguagePref.ENGLISH to "English",
                        ),
                        selected = state.languagePref,
                        onSelect = { vm.setLanguage(it) },
                    )
                    Text(s.themeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SiloSegmentBar(
                        options = listOf(
                            AppThemePref.OBSIDIAN to "Obsidian",
                            AppThemePref.AMOLED to "AMOLED",
                            AppThemePref.DYNAMIC to "Dynamic",
                        ),
                        selected = state.themePref,
                        onSelect = { vm.setTheme(it) },
                    )
                }
                SiloHairline()
                SiloSwitchRow(s.disguiseMode, s.disguiseModeDesc, state.disguiseEnabled) { on ->
                    vm.setDisguiseEnabled(on)
                }
                SiloHairline()
                SiloActionRow(
                    title = s.exportBackup,
                    icon = Icons.Rounded.UploadFile,
                    onClick = { exportBackupLauncher.launch("silo-backup.json") },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.importBackup,
                    icon = Icons.Rounded.Download,
                    onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.emergencyFlush,
                    subtitle = s.emergencyFlushDesc,
                    icon = Icons.Rounded.WarningAmber,
                    iconTint = SiloCrimson,
                    onClick = { showEmergencyFlushDialog = true },
                )
            }
        }

        item {
            Text(
                "Silo v${BuildConfig.VERSION_NAME}  •  Daemon v${state.status.version}  •  Kernel ${state.status.capabilities.kernelRelease}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DurationPillRow(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((lbl, value) in options) {
            DnsPill(lbl, selected == value, Modifier.weight(1f)) {
                onSelect(value)
            }
        }
    }
}

@Composable
private fun DnsPill(
    label: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SentinelAuditSheetContent(
    report: SentinelReport?,
    loading: Boolean,
    feedback: SheetFeedback?,
    onDismissFeedback: () -> Unit,
    onRefresh: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val scoreStr = report?.let { "${it.score}/100" } ?: "--/100"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${s.runAuditRow} ($scoreStr)",
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

        if (loading && report != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when {
            loading && report == null -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }

            report == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        s.auditUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) {
                        Text(s.retryAction)
                    }
                }
            }

            else -> {
                report.findings.forEach { f ->
                    val accent = when (f.severity) {
                        AuditSeverity.PASS -> SiloEmerald
                        AuditSeverity.INFO -> SiloCyan
                        AuditSeverity.WARN -> SiloAmber
                        AuditSeverity.CRITICAL -> SiloCrimson
                    }
                    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = accent)
                        Text(f.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        f.recommendation?.let { rec ->
                            Text(rec, style = MaterialTheme.typography.labelSmall, color = SiloAmber)
                        }
                    }
                    SiloHairline()
                }
            }
        }
    }
}

