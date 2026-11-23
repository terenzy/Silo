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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import java.util.Locale

private enum class AppListFilter { USER, RESTRICTED, AUDIT, SYSTEM }
private enum class AppSheetTab { NETWORK, PERMS, FREEZER }

/**
 * Strictly mutually-exclusive effective UI mode for a permission category row (APP-C01, APP-C02).
 * Guarantee: across all possible inputs, AT MOST ONE action pill is ever active (0 for UNGRANTED, 1 for others).
 */
enum class PermEffectiveUiMode {
    /** Runtime granted (or AppOps-only allowed) AND AppOps allows normal access ("default"/"allow"). */
    NORMAL,

    /** Runtime granted (or AppOps-only with foreground) AND AppOps mode == "foreground". */
    FOREGROUND,

    /** Runtime still granted (or AppOps-only), AppOps mode == "ignore" (silent fake-empty block). */
    FAKE_EMPTY,

    /** Actively revoked/blocked: (!runtimeGranted && appopsMode in {"ignore","deny"}) OR (appopsMode == "deny"). */
    REVOKED,

    /** Runtime permission NOT granted AND AppOps not actively ignoring/denying (e.g., default/allow/foreground). */
    UNGRANTED;

    val isNormalPillActive: Boolean get() = this == NORMAL
    val isForegroundPillActive: Boolean get() = this == FOREGROUND
    val isFakeEmptyPillActive: Boolean get() = this == FAKE_EMPTY
    val isRevokedPillActive: Boolean get() = this == REVOKED
}

val ALL_PERM_CATEGORIES: List<String> = listOf(
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

val APPOPS_ONLY_CATEGORIES: Set<String> = setOf("clipboard", "background")

/** Exact foreground-capable category set from PROJECT.md § Interface Contracts § 2. */
val FOREGROUND_SUPPORTED_CATEGORIES: Set<String> = setOf(
    "camera",
    "microphone",
    "location",
    "sensors",
)

private val AUDITABLE_CATEGORIES: List<String> = listOf(
    "all",
    "camera",
    "microphone",
    "location",
    "background_location",
    "contacts",
    "media",
    "sms_calllog",
    "sensors",
)

/**
 * Pure, strictly mutually-exclusive UI mode resolver (APP-C01, APP-C02).
 * `Normal` is NEVER active when `hasRuntimePerms && !runtimeGranted`.
 */
fun resolvePermUiMode(
    hasRuntimePerms: Boolean,
    runtimeGranted: Boolean,
    appopsMode: String,
): PermEffectiveUiMode {
    val mode = appopsMode.trim().lowercase(Locale.ROOT)
    return if (hasRuntimePerms) {
        if (!runtimeGranted) {
            when (mode) {
                "ignore", "deny" -> PermEffectiveUiMode.REVOKED
                else -> PermEffectiveUiMode.UNGRANTED
            }
        } else {
            when (mode) {
                "ignore" -> PermEffectiveUiMode.FAKE_EMPTY
                "deny" -> PermEffectiveUiMode.REVOKED
                "foreground" -> PermEffectiveUiMode.FOREGROUND
                else -> PermEffectiveUiMode.NORMAL
            }
        }
    } else {
        when (mode) {
            "ignore" -> PermEffectiveUiMode.FAKE_EMPTY
            "deny" -> PermEffectiveUiMode.REVOKED
            "foreground" -> PermEffectiveUiMode.FOREGROUND
            else -> PermEffectiveUiMode.NORMAL
        }
    }
}

/**
 * Resolves UI mode combining live daemon `CategoryPermLiveState?` with local `PackageManager` fallback.
 */
fun resolvePermUiMode(
    category: String,
    liveState: CategoryPermLiveState?,
    localRuntimeGranted: Boolean,
): PermEffectiveUiMode {
    val hasRuntime = resolveHasRuntimePerms(category, liveState)
    val effectiveGranted = if (!hasRuntime) {
        false
    } else {
        liveState?.runtimeGranted ?: localRuntimeGranted
    }
    val mode = liveState?.appopsMode ?: "default"
    return resolvePermUiMode(
        hasRuntimePerms = hasRuntime,
        runtimeGranted = effectiveGranted,
        appopsMode = mode,
    )
}

fun resolveHasRuntimePerms(
    category: String,
    liveState: CategoryPermLiveState?,
): Boolean = (liveState?.hasRuntimePerms ?: true) && !APPOPS_ONLY_CATEGORIES.contains(category)

fun resolveSupportsForeground(
    category: String,
    liveState: CategoryPermLiveState?,
): Boolean {
    val daemonFlag = liveState?.supportsForeground ?: true
    return if (category in ALL_PERM_CATEGORIES) {
        daemonFlag && FOREGROUND_SUPPORTED_CATEGORIES.contains(category)
    } else {
        daemonFlag
    }
}

/** Ceiling-rounded temporary unblock minutes (60s -> 1m, 61s -> 2m, APP-L01). */
fun formatTempUnblockMinutes(remainingSecs: Long): Long =
    ((remainingSecs.coerceAtLeast(0L) + 59L) / 60L).coerceAtLeast(1L)

/**
 * Splits permission categories into primary (declared/granted/AppOps-only/active guard/non-default op)
 * and undeclared (collapsible secondary section) so irrelevant manifest permissions don't clutter UI (APP-H10).
 */
fun partitionPermCategories(
    allCategories: List<String>,
    declaredCategories: Set<String>,
    grantedCategories: Set<String>,
    guard: AppPermGuard,
    liveByCat: Map<String, CategoryPermLiveState>,
): Pair<List<String>, List<String>> {
    val primary = mutableListOf<String>()
    val undeclared = mutableListOf<String>()
    for (cat in allCategories) {
        val live = liveByCat[cat]
        val mode = live?.appopsMode?.trim()?.lowercase(Locale.ROOT) ?: "default"
        val hasNonDefaultAppOp = mode != "default" && mode != "allow"
        val hasGuard = guard.autoRevokeOnBg.contains(cat) || guard.lockedDeny.contains(cat)
        val showPrimary = APPOPS_ONLY_CATEGORIES.contains(cat) ||
            declaredCategories.contains(cat) ||
            grantedCategories.contains(cat) ||
            hasGuard ||
            hasNonDefaultAppOp ||
            live?.runningNow == true
        if (showPrimary) primary.add(cat) else undeclared.add(cat)
    }
    return primary to undeclared
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    val s = LocalSiloStrings.current
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(AppListFilter.USER.name) }
    val filter = AppListFilter.entries.firstOrNull { it.name == filterName } ?: AppListFilter.USER
    var auditCategory by rememberSaveable { mutableStateOf("all") }
    var selectedAppPkg by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSheetTabName by rememberSaveable { mutableStateOf(AppSheetTab.NETWORK.name) }
    val selectedSheetInitialTab = AppSheetTab.entries.firstOrNull { it.name == selectedSheetTabName } ?: AppSheetTab.NETWORK

    val fw = state.config.firewall
    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW

    val filtered = remember(state.apps, query, filter, auditCategory, fw, state.config.scopes, state.config.freezer) {
        val q = query.trim().lowercase(Locale.ROOT)
        state.apps.filter { app ->
            if (app.isProtected) return@filter false
            val matchesQuery = q.isEmpty() ||
                app.label.lowercase(Locale.ROOT).contains(q) ||
                app.packageName.lowercase(Locale.ROOT).contains(q)
            if (!matchesQuery) return@filter false

            when (filter) {
                AppListFilter.USER -> !app.isSystem
                AppListFilter.SYSTEM -> app.isSystem
                AppListFilter.RESTRICTED -> {
                    val r = fw.rules[app.uid]
                    val hasNetBlock = r != null && (
                        !r.allowWifi ||
                            !r.allowCellular ||
                            !r.allowVpn ||
                            !r.allowScreenOff ||
                            r.blockCleartextHttp ||
                            r.blockLocalhost
                        )
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

    val selectedApp = remember(selectedAppPkg, state.apps) {
        selectedAppPkg?.let { pkg -> state.apps.firstOrNull { it.packageName == pkg } }
    }

    if (selectedApp != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                selectedAppPkg = null
                vm.clearSheetFeedback()
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AppControlSheet(
                app = selectedApp,
                state = state,
                vm = vm,
                initialTab = selectedSheetInitialTab,
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
                        Icon(Icons.Rounded.Close, contentDescription = s.dismiss, modifier = Modifier.size(18.dp))
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
            onSelect = { filterName = it.name },
        )

        if (!fw.enabled && filter != AppListFilter.AUDIT) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SiloAmber.copy(alpha = 0.13f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    s.firewallOffRuleHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = SiloAmber,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

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
                        onClick = { auditCategory = cat },
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (auditCategory != "all" && filtered.isNotEmpty()) {
                val bulkBusy = state.pendingPermKeys.contains("bulk:$auditCategory")
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { vm.bulkRevokeCategory(auditCategory, filtered.map { it.packageName }) },
                    enabled = !bulkBusy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 42.dp),
                ) {
                    if (bulkBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = SiloCrimson,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.RemoveModerator,
                            contentDescription = null,
                            tint = SiloCrimson,
                            modifier = Modifier.size(16.dp),
                        )
                    }
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
                        val rowPending = state.pendingPermKeys.any { it.startsWith("${app.packageName}:") }
                        AuditAppRow(
                            app = app,
                            selectedCategory = auditCategory,
                            isPending = rowPending,
                            onOpen = {
                                selectedSheetTabName = AppSheetTab.PERMS.name
                                selectedAppPkg = app.packageName
                            },
                            onRevoke = {
                                if (auditCategory == "all") {
                                    vm.revokeAllSensitiveForApp(app)
                                } else {
                                    vm.setAppPermissionAction(app.packageName, auditCategory, "revoke")
                                }
                            },
                        )
                    } else {
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
                            onOpen = {
                                selectedSheetTabName = AppSheetTab.NETWORK.name
                                selectedAppPkg = app.packageName
                            },
                            onToggleWifi = {
                                vm.mutateAppFirewallRule(app.uid, app.packageName) { cur ->
                                    cur.copy(allowWifi = !cur.allowWifi)
                                }
                            },
                            onToggleCell = {
                                vm.mutateAppFirewallRule(app.uid, app.packageName) { cur ->
                                    cur.copy(allowCellular = !cur.allowCellular)
                                }
                            },
                            onToggleFreeze = {
                                if (isFrozen || isSuspended) {
                                    vm.thawUidNow(app.uid)
                                } else {
                                    val entry = state.config.freezer.entries[app.uid]
                                        ?: FreezerEntry(packageName = app.packageName)
                                    vm.ensureEntryAndFreeze(app.uid, entry)
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
    isPending: Boolean,
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
            enabled = !isPending,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .minimumInteractiveComponentSize(),
        ) {
            if (isPending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = SiloCrimson,
                )
            } else {
                Text(
                    if (selectedCategory == "all") s.revokeAllVisible else s.revokeOne,
                    style = MaterialTheme.typography.labelSmall,
                    color = SiloCrimson,
                )
            }
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!app.isSystem && !app.hasLauncher) {
                    MiniTag(s.bgAppBadge, SiloAmber)
                }
                if (!app.hasInternetPermission) {
                    MiniTag(s.offlineOnlyBadge, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isSuspended) {
                    MiniTag(s.suspendedBadge, SiloCyan)
                } else if (isFrozen) {
                    MiniTag(s.frozenBadge, SiloCyan)
                }
                if (guardCount > 0) {
                    MiniTag("G:$guardCount", SiloEmerald)
                }
            }

            val metaParts = buildList {
                if (tempUnblockSecs != null) {
                    add("${s.tempUnblockActive} ${formatTempUnblockMinutes(tempUnblockSecs)}m")
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

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            InlineIconToggle(
                icon = if (rule.allowWifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                contentDescription = s.a11yToggleWifi,
                active = rule.allowWifi,
                activeColor = SiloEmerald,
                inactiveColor = SiloCrimson,
                onClick = onToggleWifi,
            )
            InlineIconToggle(
                icon = if (rule.allowCellular) Icons.Rounded.SignalCellularAlt else Icons.Rounded.SignalCellularOff,
                contentDescription = s.a11yToggleCellular,
                active = rule.allowCellular,
                activeColor = SiloEmerald,
                inactiveColor = SiloCrimson,
                onClick = onToggleCell,
            )
            InlineIconToggle(
                icon = Icons.Rounded.AcUnit,
                contentDescription = s.a11yToggleFreeze,
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
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
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
    contentDescription: String,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
) {
    val tint = if (active) activeColor else inactiveColor
    val bg = if (active) activeColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .minimumInteractiveComponentSize(),
        shape = CircleShape,
        color = bg,
    ) {
        Box(
            modifier = Modifier.size(38.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppControlSheet(
    app: InstalledApp,
    state: SiloUiState,
    vm: SiloViewModel,
    initialTab: AppSheetTab,
    isFrozen: Boolean,
    isSuspended: Boolean,
) {
    val s = LocalSiloStrings.current
    var tabName by rememberSaveable(app.packageName, initialTab.name) { mutableStateOf(initialTab.name) }
    val tab = AppSheetTab.entries.firstOrNull { it.name == tabName } ?: AppSheetTab.NETWORK
    var showUndeclared by rememberSaveable(app.packageName) { mutableStateOf(false) }

    // Track active inspected package so ON_RESUME / Refresh keeps live AppOps in sync (APP-H11)
    DisposableEffect(app.packageName) {
        vm.clearSheetFeedback()
        vm.setActiveInspectedPackage(app.packageName)
        vm.inspectAppPermissions(app.packageName)
        onDispose {
            vm.setActiveInspectedPackage(null)
        }
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
    val isLoadingPerms = state.loadingAppPerms.contains(app.packageName)
    val permsError = state.appPermErrors[app.packageName]
    val legacyScopeCount = state.config.scopes.apps[app.packageName]?.activeCount() ?: 0
    val freezerEntry = state.config.freezer.entries[app.uid]
    val tempRemaining = state.status.tempUnblocksRemaining[app.uid]
    val revokingAll = state.pendingPermKeys.contains("${app.packageName}:*:revoke_all")

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
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

        // Visible inline feedback banner inside sheet window (APP-C06)
        state.sheetFeedback?.let { fb ->
            SiloInlineFeedbackBanner(
                message = fb.message,
                isError = fb.isError,
                onDismiss = { vm.clearSheetFeedback() },
            )
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
                            enabled = !revokingAll,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .defaultMinSize(minHeight = 42.dp),
                        ) {
                            if (revokingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = SiloCrimson,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.RemoveModerator,
                                    contentDescription = null,
                                    tint = SiloCrimson,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
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
            onSelect = { tabName = it.name },
        )

        when (tab) {
            AppSheetTab.NETWORK -> {
                if (!fw.enabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SiloAmber.copy(alpha = 0.13f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            s.firewallOffRuleHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloAmber,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                if (!app.hasInternetPermission) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            s.noInternetPermHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                    SiloSwitchRow(s.allowWifi, s.allowWifiDesc, rule.allowWifi) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(allowWifi = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowCellular, s.allowCellularDesc, rule.allowCellular) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(allowCellular = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowVpn, s.allowVpnDesc, rule.allowVpn) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(allowVpn = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.allowLan, s.allowLanDesc, rule.allowLan) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(allowLan = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.blockScreenOff, s.blockScreenOffDesc, !rule.allowScreenOff) { blockWhenAsleep ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(allowScreenOff = !blockWhenAsleep) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.blockHttp, s.blockHttpDesc, rule.blockCleartextHttp) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(blockCleartextHttp = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.blockLocalhost, s.blockLocalhostDesc, rule.blockLocalhost) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(blockLocalhost = on) }
                    }
                    SiloHairline()
                    SiloSwitchRow(s.markVpnApp, s.markVpnAppDesc, rule.isVpnApp) { on ->
                        vm.mutateAppFirewallRule(app.uid, app.packageName) { it.copy(isVpnApp = on) }
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
                                Text("${s.revokeTempUnblock} (${formatTempUnblockMinutes(tempRemaining)}m)")
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

                // Legacy v2 scope migration banner (APP-M18)
                if (legacyScopeCount > 0) {
                    SiloGroup(color = SiloAmber.copy(alpha = 0.14f)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${s.legacyScopeTitle} ($legacyScopeCount)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = SiloAmber,
                            )
                            Text(
                                s.legacyScopeDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FilledTonalButton(
                                onClick = { vm.clearLegacyAppScope(app.packageName) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                            ) {
                                Text(s.clearLegacyScope, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                val liveByCat = remember(livePerms) {
                    livePerms?.categories?.associateBy { it.category }.orEmpty()
                }

                when {
                    isLoadingPerms && livePerms == null -> {
                        SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    s.loadingAppPermsLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    permsError != null && livePerms == null -> {
                        SiloGroup(color = SiloCrimson.copy(alpha = 0.13f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    s.permsLoadFailed,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SiloCrimson,
                                )
                                Text(
                                    permsError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(
                                    onClick = { vm.inspectAppPermissions(app.packageName) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.retryAction)
                                }
                            }
                        }
                    }

                    else -> {
                        if (isLoadingPerms) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        if (permsError != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SiloAmber.copy(alpha = 0.14f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        permsError,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SiloAmber,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { vm.inspectAppPermissions(app.packageName) }) {
                                        Text(s.retryAction, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }

                        val (primaryCats, undeclaredCats) = remember(
                            app.declaredSensitiveCategories,
                            app.grantedSensitiveCategories,
                            guard,
                            liveByCat,
                        ) {
                            partitionPermCategories(
                                allCategories = ALL_PERM_CATEGORIES,
                                declaredCategories = app.declaredSensitiveCategories,
                                grantedCategories = app.grantedSensitiveCategories,
                                guard = guard,
                                liveByCat = liveByCat,
                            )
                        }

                        SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                            primaryCats.forEachIndexed { idx, cat ->
                                PermissionCategoryControlRow(
                                    packageName = app.packageName,
                                    category = cat,
                                    title = s.categoryLabel(cat),
                                    desc = s.categoryDesc(cat),
                                    localRuntimeGranted = app.grantedSensitiveCategories.contains(cat),
                                    catState = liveByCat[cat],
                                    isUndeclared = false,
                                    isAutoRevoke = guard.autoRevokeOnBg.contains(cat),
                                    isStrictLocked = guard.lockedDeny.contains(cat),
                                    pendingPermKeys = state.pendingPermKeys,
                                    onAction = { action -> vm.setAppPermissionAction(app.packageName, cat, action) },
                                    onToggleAutoRevoke = { vm.toggleAppAutoRevoke(app.packageName, cat, !guard.autoRevokeOnBg.contains(cat)) },
                                    onToggleStrictLock = { vm.toggleAppStrictLock(app.packageName, cat, !guard.lockedDeny.contains(cat)) },
                                )
                                if (idx < primaryCats.lastIndex) SiloHairline()
                            }
                        }

                        if (undeclaredCats.isNotEmpty()) {
                            Surface(
                                onClick = { showUndeclared = !showUndeclared },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${s.undeclaredPermsSection} (${undeclaredCats.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Icon(
                                        if (showUndeclared) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            if (showUndeclared) {
                                SiloGroup(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
                                    undeclaredCats.forEachIndexed { idx, cat ->
                                        PermissionCategoryControlRow(
                                            packageName = app.packageName,
                                            category = cat,
                                            title = s.categoryLabel(cat),
                                            desc = s.categoryDesc(cat),
                                            localRuntimeGranted = app.grantedSensitiveCategories.contains(cat),
                                            catState = liveByCat[cat],
                                            isUndeclared = true,
                                            isAutoRevoke = guard.autoRevokeOnBg.contains(cat),
                                            isStrictLocked = guard.lockedDeny.contains(cat),
                                            pendingPermKeys = state.pendingPermKeys,
                                            onAction = { action -> vm.setAppPermissionAction(app.packageName, cat, action) },
                                            onToggleAutoRevoke = { vm.toggleAppAutoRevoke(app.packageName, cat, !guard.autoRevokeOnBg.contains(cat)) },
                                            onToggleStrictLock = { vm.toggleAppStrictLock(app.packageName, cat, !guard.lockedDeny.contains(cat)) },
                                        )
                                        if (idx < undeclaredCats.lastIndex) SiloHairline()
                                    }
                                }
                            }
                        }
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
                            val entry = freezerEntry ?: FreezerEntry(packageName = app.packageName, mode = currentMode)
                            vm.ensureEntryAndFreeze(app.uid, entry)
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
    packageName: String,
    category: String,
    title: String,
    desc: String,
    localRuntimeGranted: Boolean,
    catState: CategoryPermLiveState?,
    isUndeclared: Boolean,
    isAutoRevoke: Boolean,
    isStrictLocked: Boolean,
    pendingPermKeys: Set<String>,
    onAction: (String) -> Unit,
    onToggleAutoRevoke: () -> Unit,
    onToggleStrictLock: () -> Unit,
) {
    val s = LocalSiloStrings.current
    val runningNow = catState?.runningNow == true
    val systemFixed = catState?.systemFixed == true
    val lastAccess = catState?.effectiveLastAccess
    val uiMode = resolvePermUiMode(category, catState, localRuntimeGranted)
    val supportsFg = resolveSupportsForeground(category, catState)

    val rowPrefix = "$packageName:$category:"
    val allowPending = pendingPermKeys.contains("${rowPrefix}allow") || pendingPermKeys.contains("${rowPrefix}default")
    val fgPending = pendingPermKeys.contains("${rowPrefix}foreground")
    val ignorePending = pendingPermKeys.contains("${rowPrefix}ignore")
    val revokePending = pendingPermKeys.contains("${rowPrefix}revoke") || pendingPermKeys.contains("${rowPrefix}deny")
    val autoGuardPending = pendingPermKeys.contains("${rowPrefix}guard_auto")
    val lockGuardPending = pendingPermKeys.contains("${rowPrefix}guard_lock")
    val actionBusy = allowPending || fgPending || ignorePending || revokePending
    val guardBusy = autoGuardPending || lockGuardPending

    val (statusLabel, statusColor) = when {
        runningNow -> s.permStateRunning to SiloCrimson
        uiMode == PermEffectiveUiMode.REVOKED -> s.permStateDenied to SiloEmerald
        uiMode == PermEffectiveUiMode.FAKE_EMPTY -> s.permStateIgnored to SiloEmerald
        uiMode == PermEffectiveUiMode.FOREGROUND -> s.permStateForeground to SiloCyan
        uiMode == PermEffectiveUiMode.NORMAL -> s.permStateGranted to SiloAmber
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
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (systemFixed) {
                    Text(
                        s.permSystemFixedNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloAmber,
                    )
                }
                if (isUndeclared) {
                    Text(
                        s.undeclaredPermHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                if (!lastAccess.isNullOrBlank()) {
                    Text(
                        "${s.lastAccessPrefix}: $lastAccess",
                        style = MaterialTheme.typography.labelSmall,
                        color = SiloCyan,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MiniTag(statusLabel, statusColor)
                if (systemFixed) {
                    MiniTag(s.permSystemFixedBadge, SiloAmber)
                }
            }
        }

        // Mutually-exclusive action pills: Normal / [While Open if supported] / Fake Empty / Revoke
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            SmallActionPill(
                text = s.permActionNormal,
                active = uiMode.isNormalPillActive,
                accent = MaterialTheme.colorScheme.primary,
                enabled = !actionBusy,
                loading = allowPending,
                modifier = Modifier.weight(1f),
                onClick = { onAction("allow") },
            )
            if (supportsFg || uiMode == PermEffectiveUiMode.FOREGROUND) {
                SmallActionPill(
                    text = s.permActionFg,
                    active = uiMode.isForegroundPillActive,
                    accent = SiloCyan,
                    enabled = !actionBusy && supportsFg,
                    loading = fgPending,
                    modifier = Modifier.weight(1f),
                    onClick = { onAction("foreground") },
                )
            }
            SmallActionPill(
                text = s.permActionIgnore,
                active = uiMode.isFakeEmptyPillActive,
                accent = SiloEmerald,
                enabled = !actionBusy,
                loading = ignorePending,
                modifier = Modifier.weight(1f),
                onClick = { onAction("ignore") },
            )
            SmallActionPill(
                text = s.permActionRevoke,
                active = uiMode.isRevokedPillActive,
                accent = SiloCrimson,
                enabled = !actionBusy,
                loading = revokePending,
                modifier = Modifier.weight(1f),
                onClick = { onAction("revoke") },
            )
        }

        // Mutually-exclusive Daemon Enforcer toggles: Auto-Revoke & Strict Lock
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EnforcerPill(
                text = s.enforcerAutoRevoke,
                active = isAutoRevoke,
                accent = SiloCyan,
                enabled = !guardBusy,
                loading = autoGuardPending,
                modifier = Modifier.weight(1f),
                onClick = onToggleAutoRevoke,
            )
            EnforcerPill(
                text = s.enforcerStrictLock,
                active = isStrictLocked,
                accent = SiloAmber,
                enabled = !guardBusy,
                loading = lockGuardPending,
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
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .minimumInteractiveComponentSize()
            .alpha(if (enabled || loading) 1f else 0.55f),
        shape = RoundedCornerShape(10.dp),
        color = if (active) accent.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            } else {
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
}

@Composable
private fun EnforcerPill(
    text: String,
    active: Boolean,
    accent: Color,
    enabled: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .minimumInteractiveComponentSize()
            .alpha(if (enabled || loading) 1f else 0.55f),
        shape = RoundedCornerShape(10.dp),
        color = if (active) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 40.dp)
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
            } else {
                Icon(
                    if (active) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(5.dp))
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

