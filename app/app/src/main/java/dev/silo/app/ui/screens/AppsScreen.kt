package dev.silo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.silo.app.data.AppInventory
import dev.silo.app.data.InstalledApp
import dev.silo.app.data.PrivacyRiskLevel
import dev.silo.app.ipc.*
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.i18n.LocalSiloStrings
import dev.silo.app.ui.theme.*

private enum class AppListFilter { USER, RESTRICTED, SYSTEM }
private enum class AppSheetTab { NETWORK, SCOPES, FREEZER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppListFilter.USER) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }

    val fw = state.config.firewall
    val scopesMap = state.config.scopes.apps
    val freezerMap = state.config.freezer.entries
    val frozenUids = remember(state.freezerStatus.currentlyFrozenUids) {
        state.freezerStatus.currentlyFrozenUids.toSet()
    }
    val suspendedPkgs = remember(state.freezerStatus.suspendedPackages) {
        state.freezerStatus.suspendedPackages.toSet()
    }

    val filteredApps = remember(state.apps, query, filter, fw.rules, scopesMap, freezerMap) {
        val q = query.trim().lowercase()
        state.apps.filter { app ->
            if (app.isProtected) return@filter false
            val isRestricted = fw.rules.containsKey(app.uid) ||
                scopesMap.containsKey(app.packageName) ||
                freezerMap.containsKey(app.uid)
            val matchesFilter = when (filter) {
                AppListFilter.USER -> app.hasLauncher && !app.isSystem
                AppListFilter.RESTRICTED -> isRestricted
                AppListFilter.SYSTEM -> app.isSystem || !app.hasLauncher
            }
            val matchesQuery = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                app.uid.toString().contains(q)
            matchesFilter && matchesQuery
        }
    }

    selectedApp?.let { app ->
        ModalBottomSheet(
            onDismissRequest = { selectedApp = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AppControlSheet(
                app = app,
                state = state,
                vm = vm,
                isFrozen = frozenUids.contains(app.uid),
                isSuspended = suspendedPkgs.contains(app.packageName),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // Borderless tonal search field
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(s.searchApps, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )

        Spacer(Modifier.height(10.dp))

        SiloSegmentBar(
            options = listOf(
                AppListFilter.USER to s.filterUser,
                AppListFilter.RESTRICTED to s.filterRestricted,
                AppListFilter.SYSTEM to s.filterSystem,
            ),
            selected = filter,
            onSelect = { filter = it },
        )

        Spacer(Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(s.noAppsFound, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(filteredApps, key = { _, app -> "${app.uid}:${app.packageName}" }) { index, app ->
                    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW
                    val rule = fw.rules[app.uid] ?: AppFirewallRule(
                        packageName = app.packageName,
                        allowWifi = defaultAllow,
                        allowCellular = defaultAllow,
                        allowVpn = defaultAllow,
                        allowLan = false,
                        allowScreenOff = defaultAllow,
                    )
                    val scopeCount = scopesMap[app.packageName]?.activeCount() ?: 0
                    val isFrozen = frozenUids.contains(app.uid)
                    val isSuspended = suspendedPkgs.contains(app.packageName)
                    val tempSecs = state.status.tempUnblocksRemaining[app.uid]

                    CompactAppRow(
                        app = app,
                        rule = rule,
                        scopeCount = scopeCount,
                        isFrozen = isFrozen,
                        isSuspended = isSuspended,
                        tempUnblockSecs = tempSecs,
                        onClickRow = { selectedApp = app },
                        onToggleWifi = {
                            vm.updateAppFirewallRule(
                                app.uid,
                                rule.copy(packageName = app.packageName, allowWifi = !rule.allowWifi),
                            )
                        },
                        onToggleCell = {
                            vm.updateAppFirewallRule(
                                app.uid,
                                rule.copy(packageName = app.packageName, allowCellular = !rule.allowCellular),
                            )
                        },
                        onToggleFreeze = {
                            if (isFrozen || isSuspended) {
                                vm.thawUidNow(app.uid)
                            } else {
                                vm.freezeUidNow(app.uid)
                            }
                        },
                    )
                    if (index < filteredApps.lastIndex) {
                        SiloHairline()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAppRow(
    app: InstalledApp,
    rule: AppFirewallRule,
    scopeCount: Int,
    isFrozen: Boolean,
    isSuspended: Boolean,
    tempUnblockSecs: Long?,
    onClickRow: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCell: () -> Unit,
    onToggleFreeze: () -> Unit,
) {
    val context = LocalContext.current
    val s = LocalSiloStrings.current
    val iconBitmap by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = AppInventory.loadAppIcon(context, app.packageName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClickRow)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Real App Icon
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!,
                contentDescription = app.label,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Android, contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isSuspended) {
                    MiniTag(s.suspendedBadge, SiloCyan)
                } else if (isFrozen) {
                    MiniTag(s.frozenBadge, SiloCyan)
                } else if (scopeCount > 0) {
                    MiniTag("$scopeCount", SiloEmerald)
                }
            }

            val metaParts = buildList {
                if (tempUnblockSecs != null) {
                    add("${s.tempUnblockActive} ${(tempUnblockSecs / 60) + 1}m")
                }
                if (app.detectedTrackers.isNotEmpty()) {
                    add("${app.detectedTrackers.size} ${s.trackersFound}")
                } else if (app.riskLevel == PrivacyRiskLevel.HIGH) {
                    add(s.riskHigh)
                }
                if (isEmpty()) {
                    add(app.packageName)
                }
            }
            val metaColor = when {
                tempUnblockSecs != null -> SiloEmerald
                app.riskLevel == PrivacyRiskLevel.HIGH -> SiloAmber
                app.detectedTrackers.isNotEmpty() -> SiloAmber
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                metaParts.joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = metaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 3 ultra-clean borderless icon toggles (Wi-Fi / Cellular / Freeze)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            InlineIconToggle(
                icon = if (rule.allowWifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                active = rule.allowWifi,
                activeColor = SiloEmerald,
                inactiveColor = SiloCrimson,
                onClick = onToggleWifi,
            )
            InlineIconToggle(
                icon = if (rule.allowCellular) Icons.Rounded.SignalCellularAlt else Icons.Rounded.SignalCellularOff,
                active = rule.allowCellular,
                activeColor = SiloEmerald,
                inactiveColor = SiloCrimson,
                onClick = onToggleCell,
            )
            InlineIconToggle(
                icon = Icons.Rounded.AcUnit,
                active = isFrozen || isSuspended,
                activeColor = SiloCyan,
                inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                onClick = onToggleFreeze,
            )
        }
    }
}

@Composable
private fun InlineIconToggle(
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
) {
    val tint = if (active) activeColor else inactiveColor
    val bg = if (active) activeColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Surface(
        modifier = Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClick),
        shape = CircleShape,
        color = bg,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun MiniTag(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AppControlSheet(
    app: InstalledApp,
    state: SiloUiState,
    vm: SiloViewModel,
    isFrozen: Boolean,
    isSuspended: Boolean,
) {
    val s = LocalSiloStrings.current
    var tab by remember { mutableStateOf(AppSheetTab.NETWORK) }

    val fw = state.config.firewall
    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW
    val rule = fw.rules[app.uid] ?: AppFirewallRule(
        packageName = app.packageName,
        allowWifi = defaultAllow,
        allowCellular = defaultAllow,
        allowVpn = defaultAllow,
        allowLan = false,
        allowScreenOff = defaultAllow,
    )
    val scope = state.config.scopes.apps[app.packageName] ?: AppIsolationScope()
    val freezerEntry = state.config.freezer.entries[app.uid]
    val tempRemaining = state.status.tempUnblocksRemaining[app.uid]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // App Sheet Header
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${app.packageName}  •  UID ${app.uid}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (app.detectedTrackers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "SDK: ${app.detectedTrackers.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SiloAmber,
                )
            }
        }

        SiloSegmentBar(
            options = listOf(
                AppSheetTab.NETWORK to s.sheetNetwork,
                AppSheetTab.SCOPES to s.sheetScopes,
                AppSheetTab.FREEZER to s.sheetFreezer,
            ),
            selected = tab,
            onSelect = { tab = it },
        )

        when (tab) {
            AppSheetTab.NETWORK -> {
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    SiloSwitchRow(s.allowWifi, s.allowWifiDesc, rule.allowWifi) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowWifi = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowCellular, s.allowCellularDesc, rule.allowCellular) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowCellular = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowVpn, s.allowVpnDesc, rule.allowVpn) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowVpn = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowScreenOff, s.allowScreenOffDesc, rule.allowScreenOff) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowScreenOff = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowLan, s.allowLanDesc, rule.allowLan) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowLan = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.blockHttp, s.blockHttpDesc, rule.blockCleartextHttp) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, blockCleartextHttp = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.blockLocalhost, s.blockLocalhostDesc, rule.blockLocalhost) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, blockLocalhost = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.markVpnApp, s.markVpnAppDesc, rule.isVpnApp) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, isVpnApp = it))
                    }
                }

                // Temporary Internet Grant (15m / 30m / 1h)
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.tempUnblockTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(s.tempUnblockDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tempRemaining != null) {
                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { vm.clearTempUnblock(app.uid) },
                            ) {
                                Text("${s.revokeTempUnblock} (${(tempRemaining / 60) + 1}m)")
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (mins in listOf(15, 30, 60)) {
                                    FilledTonalButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = { vm.setTempUnblock(app.uid, mins) },
                                    ) { Text("${mins}m") }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.killUidSockets(app.uid) },
                    ) { Text(s.killTcpNow) }
                    if (fw.rules.containsKey(app.uid)) {
                        TextButton(onClick = { vm.removeAppFirewallRule(app.uid) }) { Text(s.reset) }
                    }
                }
            }

            AppSheetTab.SCOPES -> {
                Text(s.scopesIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { vm.applyHardenedScope(app.packageName) },
                    ) { Text(s.isolateAllHardened) }
                    if (scope.activeCount() > 0) {
                        TextButton(onClick = { vm.clearAppScope(app.packageName) }) { Text(s.reset) }
                    }
                }
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    SiloSwitchRow(s.scopeContacts, s.scopeContactsDesc, scope.isolateContacts) {
                        vm.setAppScope(app.packageName, scope.copy(isolateContacts = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeStorage, s.scopeStorageDesc, scope.isolateStorage) {
                        vm.setAppScope(app.packageName, scope.copy(isolateStorage = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeSmsCall, s.scopeSmsCallDesc, scope.isolateSmsCalllog) {
                        vm.setAppScope(app.packageName, scope.copy(isolateSmsCalllog = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeClipboard, s.scopeClipboardDesc, scope.blockClipboardRead) {
                        vm.setAppScope(app.packageName, scope.copy(blockClipboardRead = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeSensors, s.scopeSensorsDesc, scope.restrictSensors) {
                        vm.setAppScope(app.packageName, scope.copy(restrictSensors = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeBackground, s.scopeBackgroundDesc, scope.restrictBackground) {
                        vm.setAppScope(app.packageName, scope.copy(restrictBackground = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeLocation, s.scopeLocationDesc, scope.isolateLocation) {
                        vm.setAppScope(app.packageName, scope.copy(isolateLocation = it))
                    }
                    SiloHairline()
                    SiloSwitchRow(s.scopeCamMic, s.scopeCamMicDesc, scope.blockCameraMic) {
                        vm.setAppScope(app.packageName, scope.copy(blockCameraMic = it))
                    }
                }
            }

            AppSheetTab.FREEZER -> {
                Text(s.freezerIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val entry = freezerEntry ?: FreezerEntry(packageName = app.packageName)

                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    SiloSwitchRow(s.autoFreezeEntry, s.autoFreezeEntryDesc, freezerEntry != null) { enable ->
                        if (enable) {
                            vm.setFreezerEntry(app.uid, entry)
                        } else {
                            vm.removeFreezerEntry(app.uid)
                        }
                    }
                }

                SiloSegmentBar(
                    options = listOf(
                        FreezeMode.SOFT_CGROUP to s.freezeModeSoft,
                        FreezeMode.DEEP_STOP to s.freezeModeDeep,
                    ),
                    selected = entry.mode,
                    onSelect = { m -> vm.setFreezerEntry(app.uid, entry.copy(mode = m)) },
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isFrozen || isSuspended) {
                        Button(modifier = Modifier.weight(1f), onClick = { vm.thawUidNow(app.uid) }) {
                            Text(s.thawNow)
                        }
                    } else {
                        Button(modifier = Modifier.weight(1f), onClick = { vm.freezeUidNow(app.uid) }) {
                            Text(s.freezeNow)
                        }
                    }
                }
            }
        }
    }
}
