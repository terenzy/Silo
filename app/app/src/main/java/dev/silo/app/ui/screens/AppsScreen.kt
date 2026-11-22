package dev.silo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

private enum class AppListFilter { USER, RESTRICTED, AUDIT, SYSTEM }
private enum class AppSheetTab { NETWORK, PERMS, FREEZER }

private val ALL_PERM_CATEGORIES = listOf(
    "camera",
    "microphone",
    "location",
    "background_location",
    "contacts",
    "media",
    "sms_calllog",
    "clipboard",
    "sensors",
    "background",
)

private val AUDITABLE_CATEGORIES = listOf(
    "all",
    "camera",
    "microphone",
    "location",
    "background_location",
    "contacts",
    "media",
    "sms_calllog",
    "sensors",
    "background",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppListFilter.USER) }
    var auditCategory by remember { mutableStateOf("all") }
    var selectedAppPkg by remember { mutableStateOf<String?>(null) }

    val fw = state.config.firewall
    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW

    val filtered = remember(state.apps, query, filter, auditCategory, fw, state.config.scopes, state.config.freezer) {
        val q = query.trim().lowercase()
        state.apps.filter { app ->
            if (app.isProtected) return@filter false
            val matchesQuery = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
            if (!matchesQuery) return@filter false

            when (filter) {
                AppListFilter.USER -> !app.isSystem && app.hasLauncher
                AppListFilter.SYSTEM -> app.isSystem
                AppListFilter.RESTRICTED -> {
                    val r = fw.rules[app.uid]
                    val hasNetBlock = r != null && (!r.allowWifi || !r.allowCellular || !r.allowVpn || !r.allowScreenOff)
                    val hasScope = (state.config.scopes.apps[app.packageName]?.activeCount() ?: 0) > 0
                    val hasGuard = (state.config.scopes.guards[app.packageName]?.totalCount() ?: 0) > 0
                    val hasFreeze = state.config.freezer.entries.containsKey(app.uid)
                    hasNetBlock || hasScope || hasGuard || hasFreeze
                }
                AppListFilter.AUDIT -> {
                    if (auditCategory == "all") {
                        app.grantedSensitiveCategories.isNotEmpty()
                    } else {
                        app.grantedSensitiveCategories.contains(auditCategory)
                    }
                }
            }
        }
    }

    // Resolve live selectedApp from fresh state.apps so Risk & Granted Perms update immediately!
    val selectedApp = remember(selectedAppPkg, state.apps) {
        selectedAppPkg?.let { pkg -> state.apps.firstOrNull { it.packageName == pkg } }
    }

    if (selectedApp != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedAppPkg = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AppControlSheet(
                app = selectedApp,
                state = state,
                vm = vm,
                isFrozen = state.freezerStatus.currentlyFrozenUids.contains(selectedApp.uid),
                isSuspended = state.freezerStatus.suspendedPackages.contains(selectedApp.packageName),
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(6.dp))

        // Borderless tonal search bar
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            singleLine = true,
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
                AppListFilter.AUDIT to s.filterAudit,
                AppListFilter.SYSTEM to s.filterSystem,
            ),
            selected = filter,
            onSelect = { filter = it },
        )

        // Global Permission Audit Category Selector + Bulk Revoke
        if (filter == AppListFilter.AUDIT) {
            Spacer(Modifier.height(8.dp))
            Text(
                s.auditBanner,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AUDITABLE_CATEGORIES.forEach { cat ->
                    val label = if (cat == "all") s.auditAllCategories else s.categoryLabel(cat)
                    val selected = auditCategory == cat
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { auditCategory = cat },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (auditCategory != "all" && filtered.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { vm.bulkRevokeCategory(auditCategory, filtered.map { it.packageName }) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                ) {
                    Icon(Icons.Rounded.RemoveModerator, contentDescription = null, tint = SiloCrimson, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${s.revokeAllVisible} (${s.categoryLabel(auditCategory)}: ${filtered.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = SiloCrimson,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (filter == AppListFilter.AUDIT) s.noAppsWithPerm else s.noAppsFound,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(filtered, key = { _, app -> app.packageName }) { idx, app ->
                    if (filter == AppListFilter.AUDIT) {
                        AuditAppRow(
                            app = app,
                            selectedCategory = auditCategory,
                            onOpen = { selectedAppPkg = app.packageName },
                            onRevoke = {
                                if (auditCategory == "all") {
                                    vm.revokeAllSensitiveForApp(app)
                                } else {
                                    vm.setAppPermissionAction(app.packageName, auditCategory, "revoke")
                                }
                            },
                        )
                    } else {
                        // Safe default rule: ALWAYS allowScreenOff = true on quick toggle so user is never surprised!
                        val rule = fw.rules[app.uid] ?: AppFirewallRule(
                            packageName = app.packageName,
                            allowWifi = defaultAllow,
                            allowCellular = defaultAllow,
                            allowVpn = defaultAllow,
                            allowLan = false,
                            allowScreenOff = true,
                        )
                        val isFrozen = state.freezerStatus.currentlyFrozenUids.contains(app.uid)
                        val isSuspended = state.freezerStatus.suspendedPackages.contains(app.packageName)
                        val guardCount = state.config.scopes.guards[app.packageName]?.totalCount() ?: 0
                        val tempLeft = state.status.tempUnblocksRemaining[app.uid]

                        MinimalAppRow(
                            app = app,
                            rule = rule,
                            isFrozen = isFrozen,
                            isSuspended = isSuspended,
                            guardCount = guardCount,
                            tempUnblockSecs = tempLeft,
                            onOpen = { selectedAppPkg = app.packageName },
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
                                    val existing = state.config.freezer.entries[app.uid]
                                    if (existing == null) {
                                        vm.setFreezerEntry(app.uid, FreezerEntry(packageName = app.packageName))
                                    }
                                    vm.freezeUidNow(app.uid)
                                }
                            },
                        )
                    }
                    if (idx < filtered.lastIndex) SiloHairline()
                }
            }
        }
    }
}

@Composable
private fun AuditAppRow(
    app: InstalledApp,
    selectedCategory: String,
    onOpen: () -> Unit,
    onRevoke: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val context = LocalContext.current
    var iconBmp by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        iconBmp = AppInventory.loadAppIcon(context, app.packageName)
    }

    val activeLabels = app.grantedSensitiveCategories.map { s.categoryLabel(it) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconBox(iconBmp, app.label)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                activeLabels.joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = SiloAmber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(
            onClick = onRevoke,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                if (selectedCategory == "all") s.revokeAllVisible else s.revokeOne,
                style = MaterialTheme.typography.labelSmall,
                color = SiloCrimson,
            )
        }
    }
}

@Composable
private fun MinimalAppRow(
    app: InstalledApp,
    rule: AppFirewallRule,
    isFrozen: Boolean,
    isSuspended: Boolean,
    guardCount: Int,
    tempUnblockSecs: Long?,
    onOpen: () -> Unit,
    onToggleWifi: () -> Unit,
    onToggleCell: () -> Unit,
    onToggleFreeze: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val context = LocalContext.current
    var iconBmp by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        iconBmp = AppInventory.loadAppIcon(context, app.packageName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIconBox(iconBmp, app.label)

        Column(Modifier.weight(1f)) {
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
                } else if (guardCount > 0) {
                    MiniTag("G:$guardCount", SiloEmerald)
                }
            }

            // Always show concrete reason — never an unexplained scary label!
            val metaParts = buildList {
                if (tempUnblockSecs != null) {
                    add("${s.tempUnblockActive} ${(tempUnblockSecs / 60) + 1}m")
                }
                if (app.grantedSensitiveCategories.isNotEmpty()) {
                    add("${app.grantedSensitiveCategories.size} ${s.activePermsShort}")
                }
                if (app.detectedTrackers.isNotEmpty()) {
                    add("${app.detectedTrackers.size} ${s.trackersFound}")
                }
                if (isEmpty()) {
                    add(app.packageName)
                }
            }
            val metaColor = when {
                tempUnblockSecs != null -> SiloEmerald
                app.riskLevel == PrivacyRiskLevel.HIGH -> SiloCrimson
                app.riskLevel == PrivacyRiskLevel.MEDIUM -> SiloAmber
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
private fun AppIconBox(iconBmp: ImageBitmap?, label: String) {
    if (iconBmp != null) {
        Image(
            bitmap = iconBmp,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
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

    // Fetch REAL-TIME AppOps state from daemon whenever this app sheet opens!
    LaunchedEffect(app.packageName) {
        vm.inspectAppPermissions(app.packageName)
    }

    val fw = state.config.firewall
    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW
    val rule = fw.rules[app.uid] ?: AppFirewallRule(
        packageName = app.packageName,
        allowWifi = defaultAllow,
        allowCellular = defaultAllow,
        allowVpn = defaultAllow,
        allowLan = false,
        allowScreenOff = true,
    )
    val guard = state.config.scopes.guards[app.packageName] ?: AppPermGuard()
    val livePerms = state.liveAppPerms[app.packageName]
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${app.packageName}  •  UID ${app.uid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val (riskLabel, riskColor) = when (app.riskLevel) {
                PrivacyRiskLevel.HIGH -> s.riskHigh to SiloCrimson
                PrivacyRiskLevel.MEDIUM -> s.riskMedium to SiloAmber
                PrivacyRiskLevel.LOW -> s.riskLow to SiloEmerald
            }
            MiniTag(riskLabel, riskColor)
        }

        // To-the-point Risk Breakdown & Actionable Mitigation Card
        if (app.grantedSensitiveCategories.isNotEmpty() || app.detectedTrackers.isNotEmpty()) {
            val accent = if (app.riskLevel == PrivacyRiskLevel.HIGH) SiloCrimson else SiloAmber
            SiloGroup(color = accent.copy(alpha = 0.11f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        s.riskWhyTitle,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                    if (app.grantedSensitiveCategories.isNotEmpty()) {
                        val grantedText = app.grantedSensitiveCategories.joinToString(", ") { s.categoryLabel(it) }
                        Text(
                            "${s.riskActivePermsLabel}: $grantedText",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            s.riskFixPermsHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            s.riskDeclaredOnlyNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (app.detectedTrackers.isNotEmpty()) {
                        Text(
                            "${s.riskTrackersLabel}: ${app.detectedTrackers.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SiloAmber,
                        )
                        Text(
                            s.riskFixTrackersHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (app.grantedSensitiveCategories.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { vm.revokeAllSensitiveForApp(app) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Icon(Icons.Rounded.RemoveModerator, contentDescription = null, tint = SiloCrimson, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.revokeAllSensPerms, style = MaterialTheme.typography.labelMedium, color = SiloCrimson)
                        }
                    }
                }
            }
        }

        SiloSegmentBar(
            options = listOf(
                AppSheetTab.NETWORK to s.sheetNetwork,
                AppSheetTab.PERMS to s.sheetPerms,
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
                    SiloSwitchRow(s.allowLan, s.allowLanDesc, rule.allowLan) {
                        vm.updateAppFirewallRule(app.uid, rule.copy(packageName = app.packageName, allowLan = it))
                    }
                    SiloHairline()
                    // Crystal-clear non-inverted semantics: ON = Block while screen off!
                    SiloSwitchRow(s.blockScreenOff, s.blockScreenOffDesc, !rule.allowScreenOff) { blockWhenAsleep ->
                        vm.updateAppFirewallRule(
                            app.uid,
                            rule.copy(packageName = app.packageName, allowScreenOff = !blockWhenAsleep),
                        )
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

                // Temporary Unblock Timer
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.tempUnblockTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(s.tempUnblockDesc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tempRemaining != null) {
                            FilledTonalButton(
                                onClick = { vm.clearTempUnblock(app.uid) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("${s.revokeTempUnblock} (${(tempRemaining / 60) + 1}m)")
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (mins in listOf(15, 30, 60)) {
                                    FilledTonalButton(
                                        onClick = { vm.setTempUnblock(app.uid, mins) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp),
                                    ) { Text("${mins}m") }
                                }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { vm.killUidSockets(app.uid) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(s.killTcpNow, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (fw.rules.containsKey(app.uid)) {
                        TextButton(
                            onClick = { vm.removeAppFirewallRule(app.uid) },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(s.resetNetRule) }
                    }
                }
            }

            AppSheetTab.PERMS -> {
                Text(
                    s.permsHonestNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Text(
                    s.enforcerHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = SiloCyan,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                val liveByCat = remember(livePerms) {
                    livePerms?.categories?.associateBy { it.category }.orEmpty()
                }

                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    ALL_PERM_CATEGORIES.forEachIndexed { idx, cat ->
                        val catState = liveByCat[cat]
                        val runtimeGranted = app.grantedSensitiveCategories.contains(cat)
                        val appopsMode = catState?.appopsMode ?: "default"
                        val runningNow = catState?.runningNow == true
                        val lastAccess = catState?.lastAccess
                        val isAutoRevoke = guard.autoRevokeOnBg.contains(cat)
                        val isStrictLocked = guard.lockedDeny.contains(cat)

                        PermissionCategoryControlRow(
                            title = s.categoryLabel(cat),
                            desc = s.categoryDesc(cat),
                            runtimeGranted = runtimeGranted,
                            appopsMode = appopsMode,
                            runningNow = runningNow,
                            lastAccess = lastAccess,
                            isAutoRevoke = isAutoRevoke,
                            isStrictLocked = isStrictLocked,
                            onAction = { action -> vm.setAppPermissionAction(app.packageName, cat, action) },
                            onToggleAutoRevoke = { vm.toggleAppAutoRevoke(app.packageName, cat, !isAutoRevoke) },
                            onToggleStrictLock = { vm.toggleAppStrictLock(app.packageName, cat, !isStrictLocked) },
                        )
                        if (idx < ALL_PERM_CATEGORIES.lastIndex) SiloHairline()
                    }
                }
            }

            AppSheetTab.FREEZER -> {
                Text(
                    s.freezerIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                val inList = freezerEntry != null
                val currentMode = freezerEntry?.mode ?: FreezeMode.SOFT_CGROUP
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    SiloSwitchRow(s.autoFreezeEntry, s.autoFreezeEntryDesc, inList) { on ->
                        if (on) {
                            vm.setFreezerEntry(app.uid, FreezerEntry(packageName = app.packageName, mode = currentMode))
                        } else {
                            vm.removeFreezerEntry(app.uid)
                        }
                    }
                    SiloHairline()
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SiloSegmentBar(
                            options = listOf(
                                FreezeMode.SOFT_CGROUP to s.freezeModeSoft,
                                FreezeMode.DEEP_STOP to s.freezeModeDeep,
                            ),
                            selected = currentMode,
                            onSelect = { m ->
                                vm.setFreezerEntry(
                                    app.uid,
                                    (freezerEntry ?: FreezerEntry(packageName = app.packageName)).copy(mode = m),
                                )
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (freezerEntry == null) {
                                vm.setFreezerEntry(app.uid, FreezerEntry(packageName = app.packageName, mode = currentMode))
                            }
                            vm.freezeUidNow(app.uid)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(s.freezeNow) }
                    FilledTonalButton(
                        onClick = { vm.thawUidNow(app.uid) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(s.thawNow) }
                }
            }
        }
    }
}

@Composable
private fun PermissionCategoryControlRow(
    title: String,
    desc: String,
    runtimeGranted: Boolean,
    appopsMode: String,
    runningNow: Boolean,
    lastAccess: String?,
    isAutoRevoke: Boolean,
    isStrictLocked: Boolean,
    onAction: (String) -> Unit,
    onToggleAutoRevoke: () -> Unit,
    onToggleStrictLock: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val (statusLabel, statusColor) = when {
        runningNow -> s.permStateRunning to SiloCrimson
        appopsMode == "ignore" -> s.permStateIgnored to SiloEmerald
        appopsMode == "deny" -> s.permStateDenied to SiloEmerald
        appopsMode == "foreground" -> s.permStateForeground to SiloCyan
        runtimeGranted -> s.permStateGranted to SiloAmber
        else -> s.permStateDenied to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!lastAccess.isNullOrBlank()) {
                    Text(
                        "${s.lastAccessPrefix}: $lastAccess",
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloCyan,
                    )
                }
            }
            MiniTag(statusLabel, statusColor)
        }

        // 4 real action pills: Normal / Foreground / Ignore / Revoke (pm revoke + ignore)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SmallActionPill(
                text = s.permActionNormal,
                active = appopsMode == "default" || appopsMode == "allow",
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = { onAction("allow") },
            )
            SmallActionPill(
                text = s.permActionFg,
                active = appopsMode == "foreground",
                accent = SiloCyan,
                modifier = Modifier.weight(1f),
                onClick = { onAction("foreground") },
            )
            SmallActionPill(
                text = s.permActionIgnore,
                active = appopsMode == "ignore",
                accent = SiloEmerald,
                modifier = Modifier.weight(1f),
                onClick = { onAction("ignore") },
            )
            SmallActionPill(
                text = s.permActionRevoke,
                active = !runtimeGranted && (appopsMode == "ignore" || appopsMode == "deny"),
                accent = SiloCrimson,
                modifier = Modifier.weight(1f),
                onClick = { onAction("revoke") },
            )
        }

        // Daemon Enforcer toggles: Auto-Revoke on Close & Strict Lock
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EnforcerPill(
                text = s.enforcerAutoRevoke,
                active = isAutoRevoke,
                accent = SiloCyan,
                modifier = Modifier.weight(1f),
                onClick = onToggleAutoRevoke,
            )
            EnforcerPill(
                text = s.enforcerStrictLock,
                active = isStrictLocked,
                accent = SiloAmber,
                modifier = Modifier.weight(1f),
                onClick = onToggleStrictLock,
            )
        }
    }
}

@Composable
private fun SmallActionPill(
    text: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (active) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
    ) {
        Box(Modifier.padding(vertical = 6.dp, horizontal = 3.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EnforcerPill(
    text: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (active) accent.copy(alpha = 0.18f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 5.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
