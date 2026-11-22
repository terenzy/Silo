package dev.silo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.silo.app.data.InstalledApp
import dev.silo.app.ipc.AppIsolationScope
import dev.silo.app.ipc.FreezeMode
import dev.silo.app.ipc.FreezerEntry
import dev.silo.app.ui.SiloUiState
import dev.silo.app.ui.SiloViewModel
import dev.silo.app.ui.theme.SiloCyan
import dev.silo.app.ui.theme.SiloEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IsolationScreen(
    state: SiloUiState,
    vm: SiloViewModel,
) {
    var query by remember { mutableStateOf("") }
    val scopesCfg = state.config.scopes
    val freezerCfg = state.config.freezer
    val frozenSet = remember(state.freezerStatus.currentlyFrozenUids) {
        state.freezerStatus.currentlyFrozenUids.toSet()
    }

    val filteredApps = remember(state.apps, query) {
        val q = query.trim().lowercase()
        state.apps.filter { app ->
            !app.isProtected && !app.isSystem &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // GrapheneOS-style Auto-Clear Clipboard Card
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickSwitchRow(
                        title = "Auto-Clear Clipboard (${scopesCfg.clipboardClearSecs}s)",
                        subtitle = "Zero-logging clipboard guard — wipes primary clip automatically after timeout",
                        checked = scopesCfg.autoClearClipboard,
                        onCheckedChange = { vm.setAutoClearClipboard(it, scopesCfg.clipboardClearSecs) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (sec in listOf(30, 45, 60, 120)) {
                            FilterChip(
                                selected = scopesCfg.clipboardClearSecs == sec,
                                onClick = { vm.setAutoClearClipboard(scopesCfg.autoClearClipboard, sec) },
                                label = { Text("${sec}s") },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.clearClipboardNow() }) { Text("Clear Now") }
                    }
                }
            }
        }

        // Kernel cgroup v2 App Freezer Master Card
        item {
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickSwitchRow(
                        title = "Kernel App Freezer (cgroup v2)",
                        subtitle = "0% CPU/wakelocks when backgrounded or screen off; <1ms auto-thaw on open",
                        checked = freezerCfg.enabled,
                        onCheckedChange = { vm.setFreezerEnabled(it, freezerCfg.backgroundGraceSecs) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Currently frozen: ${frozenSet.size} UID(s)  •  Managed: ${freezerCfg.entries.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = SiloCyan,
                        )
                        OutlinedButton(
                            enabled = frozenSet.isNotEmpty(),
                            onClick = { vm.thawAllNow() },
                        ) { Text("Thaw All") }
                    }
                }
            }
        }

        // Search Bar
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search user apps (${filteredApps.size})") },
            )
        }

        // Per-App Isolation Scopes + Freezer Cards
        items(filteredApps, key = { it.packageName }) { app ->
            val scope = scopesCfg.apps[app.packageName] ?: AppIsolationScope()
            val freezerEntry = freezerCfg.entries[app.uid]
            val isFrozenNow = frozenSet.contains(app.uid)

            AppIsolationCard(
                app = app,
                scope = scope,
                freezerEntry = freezerEntry,
                isFrozenNow = isFrozenNow,
                onUpdateScope = { vm.setAppScope(app.packageName, it) },
                onApplyHardened = { vm.applyHardenedScope(app.packageName) },
                onClearScope = { vm.clearAppScope(app.packageName) },
                onToggleFreezer = { enable ->
                    if (enable) {
                        vm.setFreezerEntry(app.uid, FreezerEntry(packageName = app.packageName))
                    } else {
                        vm.removeFreezerEntry(app.uid)
                    }
                },
                onToggleFreezeMode = { entry ->
                    val nextMode = if (entry.mode == FreezeMode.SOFT_CGROUP) FreezeMode.DEEP_STOP else FreezeMode.SOFT_CGROUP
                    vm.setFreezerEntry(app.uid, entry.copy(mode = nextMode))
                },
                onFreezeNow = { vm.freezeUidNow(app.uid) },
                onThawNow = { vm.thawUidNow(app.uid) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppIsolationCard(
    app: InstalledApp,
    scope: AppIsolationScope,
    freezerEntry: FreezerEntry?,
    isFrozenNow: Boolean,
    onUpdateScope: (AppIsolationScope) -> Unit,
    onApplyHardened: () -> Unit,
    onClearScope: () -> Unit,
    onToggleFreezer: (Boolean) -> Unit,
    onToggleFreezeMode: (FreezerEntry) -> Unit,
    onFreezeNow: () -> Unit,
    onThawNow: () -> Unit,
) {
    Card(shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isFrozenNow) {
                    Surface(shape = RoundedCornerShape(8.dp), color = SiloCyan.copy(alpha = 0.2f)) {
                        Text(
                            "FROZEN",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloCyan,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else if (scope.activeCount() > 0) {
                    Surface(shape = RoundedCornerShape(8.dp), color = SiloEmerald.copy(alpha = 0.16f)) {
                        Text(
                            "${scope.activeCount()} scopes",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SiloEmerald,
                        )
                    }
                }
            }

            // GrapheneOS-style fake-empty AppOps Ignore Scopes
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = scope.isolateContacts,
                    onClick = { onUpdateScope(scope.copy(isolateContacts = !scope.isolateContacts)) },
                    label = { Text("Contacts") },
                )
                FilterChip(
                    selected = scope.isolateStorage,
                    onClick = { onUpdateScope(scope.copy(isolateStorage = !scope.isolateStorage)) },
                    label = { Text("Storage") },
                )
                FilterChip(
                    selected = scope.isolateSmsCalllog,
                    onClick = { onUpdateScope(scope.copy(isolateSmsCalllog = !scope.isolateSmsCalllog)) },
                    label = { Text("SMS/Call") },
                )
                FilterChip(
                    selected = scope.blockClipboardRead,
                    onClick = { onUpdateScope(scope.copy(blockClipboardRead = !scope.blockClipboardRead)) },
                    label = { Text("Clipboard") },
                )
                FilterChip(
                    selected = scope.restrictSensors,
                    onClick = { onUpdateScope(scope.copy(restrictSensors = !scope.restrictSensors)) },
                    label = { Text("Sensors") },
                )
                FilterChip(
                    selected = scope.restrictBackground,
                    onClick = { onUpdateScope(scope.copy(restrictBackground = !scope.restrictBackground)) },
                    label = { Text("Bg/Wake") },
                )
                FilterChip(
                    selected = scope.isolateLocation,
                    onClick = { onUpdateScope(scope.copy(isolateLocation = !scope.isolateLocation)) },
                    label = { Text("Location") },
                )
                FilterChip(
                    selected = scope.blockCameraMic,
                    onClick = { onUpdateScope(scope.copy(blockCameraMic = !scope.blockCameraMic)) },
                    label = { Text("Cam/Mic") },
                )
            }

            // Actions Row (1-tap Hardened Profile + cgroup v2 Freezer)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onApplyHardened, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("Hardened", style = MaterialTheme.typography.labelMedium)
                }
                if (scope.activeCount() > 0) {
                    TextButton(onClick = onClearScope) { Text("Reset") }
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = freezerEntry != null,
                    onClick = { onToggleFreezer(freezerEntry == null) },
                    label = { Text("Auto-Freeze") },
                )
            }

            if (freezerEntry != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onToggleFreezeMode(freezerEntry) }) {
                        Text(if (freezerEntry.mode == FreezeMode.SOFT_CGROUP) "Mode: cgroup v2" else "Mode: Deep Stop")
                    }
                    if (isFrozenNow) {
                        Button(onClick = onThawNow) { Text("Thaw") }
                    } else {
                        FilledTonalButton(onClick = onFreezeNow) { Text("Freeze Now") }
                    }
                }
            }
        }
    }
}
