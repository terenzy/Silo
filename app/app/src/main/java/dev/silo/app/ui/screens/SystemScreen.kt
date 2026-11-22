package dev.silo.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.data.AppLanguagePref
import dev.silo.app.data.AppThemePref
import dev.silo.app.ipc.*
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
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
        AlertDialog(
            onDismissRequest = { showCustomDnsDialog = false },
            title = { Text(s.secPrivateDns) },
            text = {
                OutlinedTextField(
                    value = customDnsHost,
                    onValueChange = { customDnsHost = it },
                    singleLine = true,
                    label = { Text("DoT Hostname (e.g. dns.quad9.net)") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    showCustomDnsDialog = false
                    vm.setPrivateDns("hostname", customDnsHost.trim())
                }) { Text(s.apply) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDnsDialog = false }) { Text(s.cancel) }
            },
        )
    }

    if (showGrapheneSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGrapheneSheet = false },
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
        ModalBottomSheet(
            onDismissRequest = { showBlocklistSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.ipBlocklistRow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${fw.ipBlocklist.size} IP/CIDR  •  Backend: ${state.status.ipsetBackend}",
                    style = MaterialTheme.typography.labelMedium,
                    color = SiloEmerald,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cidrInput,
                        onValueChange = { cidrInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(s.addCidrHint) },
                    )
                    FilledTonalButton(onClick = {
                        if (cidrInput.isNotBlank()) {
                            vm.addSingleBlocklistCidr(cidrInput)
                            cidrInput = ""
                        }
                    }) { Text("+") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { importBlocklistLauncher.launch(arrayOf("text/*", "*/*")) },
                    ) { Text(s.importFile) }
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        enabled = fw.ipBlocklist.isNotEmpty(),
                        onClick = { vm.clearIpBlocklist() },
                    ) { Text(s.clearList) }
                }
            }
        }
    }

    if (showSysctlSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSysctlSheet = false },
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
                Text(s.inspectSysctl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.hardeningStatus.items.forEach { item ->
                    val color = when {
                        !item.supported -> MaterialTheme.colorScheme.onSurfaceVariant
                        item.enforced -> SiloEmerald
                        else -> SiloAmber
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(item.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    if (showAuditSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAuditSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SentinelAuditSheetContent(
                report = state.sentinelReport,
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dnsPresets.take(3).forEach { (m, host, label) ->
                            val active = curDns.mode == m && (m != "hostname" || curDns.specifier == host)
                            DnsPill(label, active, Modifier.weight(1f)) { vm.setPrivateDns(m, host) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        dnsPresets.drop(3).forEach { (m, host, label) ->
                            val active = curDns.mode == m && curDns.specifier == host
                            DnsPill(label, active, Modifier.weight(1f)) { vm.setPrivateDns(m, host) }
                        }
                        DnsPill(s.dnsCustom, false, Modifier.weight(1f)) {
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                        onSelect = { m -> vm.updateFirewallConfig { it.copy(mode = m) } },
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
                    onClick = { showBlocklistSheet = true },
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
                    "${s.autoRebootBfu} (${auto.autoRebootMinutes / 60}h)",
                    s.autoRebootBfuDesc,
                    auto.autoRebootEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(autoRebootEnabled = on) } }

                if (auto.autoRebootEnabled) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((lbl, mins) in listOf("30m" to 30, "4h" to 240, "12h" to 720, "18h" to 1080)) {
                            DnsPill(lbl, auto.autoRebootMinutes == mins, Modifier.weight(1f)) {
                                vm.updateAutomationConfig { it.copy(autoRebootMinutes = mins) }
                            }
                        }
                    }
                }
                SiloHairline()
                SiloSwitchRow(
                    "${s.autoClearClip} (${state.config.scopes.clipboardClearSecs}s)",
                    s.autoClearClipDesc,
                    state.config.scopes.autoClearClipboard,
                ) { on -> vm.setAutoClearClipboard(on, state.config.scopes.clipboardClearSecs) }
                SiloHairline()
                SiloSwitchRow(
                    "${s.wifiAutoOff} (${auto.wifiAutoOffMinutes}m)",
                    s.wifiAutoOffDesc,
                    auto.wifiAutoOffEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(wifiAutoOffEnabled = on) } }
                SiloHairline()
                SiloSwitchRow(
                    "${s.btAutoOff} (${auto.bluetoothAutoOffMinutes}m)",
                    s.btAutoOffDesc,
                    auto.bluetoothAutoOffEnabled,
                ) { on -> vm.updateAutomationConfig { it.copy(bluetoothAutoOffEnabled = on) } }
                SiloHairline()
                SiloSwitchRow(s.lockSensorsOff, s.lockSensorsOffDesc, auto.sensorsOffOnLock) { on ->
                    vm.updateAutomationConfig { it.copy(sensorsOffOnLock = on) }
                }
                SiloHairline()
                SiloSwitchRow(s.freezerMaster, s.freezerMasterDesc, state.config.freezer.enabled) { on ->
                    vm.setFreezerEnabled(on, state.config.freezer.backgroundGraceSecs)
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DnsPill("Default", h.tcpCongestion.isEmpty(), Modifier.weight(1f)) {
                                vm.updateHardeningConfig { it.copy(tcpCongestion = "") }
                            }
                            availCc.take(3).forEach { cc ->
                                DnsPill(cc, h.tcpCongestion == cc, Modifier.weight(1f)) {
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
                                vm.updateHardeningConfig { it.copy(enabled = true, defaultQdisc = q) }
                            }
                        }
                    }
                }
                SiloHairline()
                SiloActionRow(
                    title = s.inspectSysctl,
                    icon = Icons.Rounded.Memory,
                    trailingText = "${state.hardeningStatus.items.count { it.enforced }}/${state.hardeningStatus.items.count { it.supported }}",
                    onClick = { showSysctlSheet = true },
                )
                SiloHairline()
                SiloActionRow(
                    title = s.rollbackSysctl,
                    icon = Icons.Rounded.Restore,
                    iconTint = SiloAmber,
                    onClick = { vm.rollbackHardening() },
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
                        vm.runLiveProof { showProofSheet = true }
                        showProofSheet = true
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
                        vm.runSentinelAudit()
                        showAuditSheet = true
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
                    onClick = { vm.emergencyFlushFirewall() },
                )
            }
        }

        item {
            Text(
                "Silo v3.0.0  •  Daemon v${state.status.version}  •  Kernel ${state.status.capabilities.kernelRelease}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
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
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SentinelAuditSheetContent(
    report: SentinelReport?,
    onRefresh: () -> Unit,
) {
    val s = LocalSiloStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${s.runAuditRow} (${report?.score ?: 0}/100)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
        }

        report?.findings?.forEach { f ->
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
