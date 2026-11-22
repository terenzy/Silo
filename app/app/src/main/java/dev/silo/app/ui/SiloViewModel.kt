package dev.silo.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silo.app.SiloApp
import dev.silo.app.data.AppInventory
import dev.silo.app.data.BlocklistParser
import dev.silo.app.data.InstalledApp
import dev.silo.app.ipc.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SiloUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val connectionError: String? = null,
    val status: DaemonStatus = DaemonStatus(),
    val config: SiloConfig = SiloConfig(),
    val apps: List<InstalledApp> = emptyList(),
    val liveConnections: List<ActiveConnection> = emptyList(),
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val freezerStatus: FreezerStatus = FreezerStatus(),
    val hardeningStatus: HardeningStatus = HardeningStatus(),
    val sentinelReport: SentinelReport? = null,
    val disguiseEnabled: Boolean = false,
    val snackbarMessage: String? = null,
    val monitorAutoRefresh: Boolean = true,
)

class SiloViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SiloApp).repository
    private val _ui = MutableStateFlow(SiloUiState())
    val ui: StateFlow<SiloUiState> = _ui.asStateFlow()

    private var monitorJob: Job? = null

    init {
        refreshAll()
    }

    fun clearSnackbar() {
        _ui.update { it.copy(snackbarMessage = null) }
    }

    private fun toast(msg: String) {
        _ui.update { it.copy(snackbarMessage = msg) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val appCtx = getApplication<Application>()
            val installed = AppInventory.loadInstalledApps(appCtx)
            val disguised = repo.isDisguiseEnabled(appCtx)

            val statusRes = repo.getStatus()
            if (statusRes.isFailure) {
                _ui.update {
                    it.copy(
                        loading = false,
                        connected = false,
                        connectionError = statusRes.exceptionOrNull()?.message
                            ?: "Cannot connect to silod daemon",
                        apps = installed,
                        disguiseEnabled = disguised,
                    )
                }
                return@launch
            }

            val status = statusRes.getOrThrow()
            val cfg = repo.getConfig().getOrDefault(_ui.value.config)
            val freezer = repo.getFreezerStatus().getOrDefault(_ui.value.freezerStatus)
            val hardening = repo.getHardeningStatus().getOrDefault(_ui.value.hardeningStatus)
            val telemetry = repo.getTelemetry(250).getOrDefault(_ui.value.telemetry)
            val conns = repo.getLiveConnections().getOrDefault(emptyList())
            val audit = repo.runSentinelAudit().getOrNull()

            _ui.update {
                it.copy(
                    loading = false,
                    connected = true,
                    connectionError = null,
                    status = status,
                    config = cfg,
                    apps = installed,
                    freezerStatus = freezer,
                    hardeningStatus = hardening,
                    telemetry = telemetry,
                    liveConnections = conns,
                    sentinelReport = audit ?: it.sentinelReport,
                    disguiseEnabled = disguised,
                )
            }
        }
    }

    fun setMonitorPollingActive(active: Boolean) {
        monitorJob?.cancel()
        if (!active) return
        monitorJob = viewModelScope.launch {
            while (isActive) {
                if (_ui.value.connected && _ui.value.monitorAutoRefresh) {
                    refreshMonitorOnce()
                }
                delay(3_000L)
            }
        }
    }

    fun toggleMonitorAutoRefresh(enabled: Boolean) {
        _ui.update { it.copy(monitorAutoRefresh = enabled) }
    }

    fun refreshMonitorOnce() {
        viewModelScope.launch {
            val conns = repo.getLiveConnections().getOrNull()
            val tel = repo.getTelemetry(300).getOrNull()
            val st = repo.getStatus().getOrNull()
            _ui.update { cur ->
                cur.copy(
                    liveConnections = conns ?: cur.liveConnections,
                    telemetry = tel ?: cur.telemetry,
                    status = st ?: cur.status,
                )
            }
        }
    }

    fun applyPreset(preset: SecurityPreset) {
        viewModelScope.launch {
            repo.applyPreset(preset).onSuccess { newCfg ->
                _ui.update { it.copy(config = newCfg) }
                refreshLightStatus()
                toast("Preset ${preset.name} applied")
            }.onFailure { e ->
                toast("Preset failed: ${e.message}")
            }
        }
    }

    fun updateFirewallConfig(transform: (FirewallConfig) -> FirewallConfig) {
        val next = transform(_ui.value.config.firewall)
        viewModelScope.launch {
            repo.setFirewallConfig(next).onSuccess { saved ->
                _ui.update { it.copy(config = it.config.copy(firewall = saved)) }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Firewall update failed: ${e.message}")
            }
        }
    }

    fun updateAppFirewallRule(uid: Int, rule: AppFirewallRule) {
        viewModelScope.launch {
            repo.setAppFirewallRule(uid, rule).onSuccess {
                _ui.update { cur ->
                    val updated = cur.config.firewall.rules.toMutableMap()
                    updated[uid] = rule
                    cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = updated)))
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Rule error: ${e.message}")
            }
        }
    }

    fun removeAppFirewallRule(uid: Int) {
        viewModelScope.launch {
            repo.removeAppFirewallRule(uid).onSuccess {
                _ui.update { cur ->
                    val updated = cur.config.firewall.rules.toMutableMap()
                    updated.remove(uid)
                    cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = updated)))
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Remove rule failed: ${e.message}")
            }
        }
    }

    fun importIpBlocklistFromUri(uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (raw == null) {
                toast("Could not read selected blocklist file")
                return@launch
            }
            val parsed = BlocklistParser.parseLines(raw)
            if (parsed.isEmpty()) {
                toast("No valid IPv4/IPv6/CIDR entries found in file")
                return@launch
            }
            repo.replaceIpBlocklist(parsed).onSuccess { obj ->
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Blocklist synced: ${obj["loaded"]} entries (${obj["backend"]})")
            }.onFailure { e ->
                toast("Blocklist apply failed: ${e.message}")
            }
        }
    }

    fun addSingleBlocklistCidr(cidr: String) {
        val cleaned = BlocklistParser.parseLines(cidr)
        if (cleaned.isEmpty()) {
            toast("Invalid IP/CIDR syntax")
            return
        }
        val merged = (_ui.value.config.firewall.ipBlocklist + cleaned).distinct()
        viewModelScope.launch {
            repo.replaceIpBlocklist(merged).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Added ${cleaned.first()} to kernel blocklist")
            }.onFailure { e ->
                toast("Failed: ${e.message}")
            }
        }
    }

    fun clearIpBlocklist() {
        viewModelScope.launch {
            repo.replaceIpBlocklist(emptyList()).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Kernel IP blocklist cleared")
            }.onFailure { e ->
                toast("Clear failed: ${e.message}")
            }
        }
    }

    fun emergencyFlushFirewall() {
        viewModelScope.launch {
            repo.emergencyFlushFirewall().onSuccess {
                refreshAll()
                toast("Emergency flush complete — all SILO_* chains cleared")
            }.onFailure { e ->
                toast("Flush failed: ${e.message}")
            }
        }
    }

    fun killUidSockets(uid: Int) {
        viewModelScope.launch {
            repo.killUidSockets(uid).onSuccess { killed ->
                refreshMonitorOnce()
                toast("SOCK_DESTROY closed $killed active TCP socket(s)")
            }.onFailure { e ->
                toast("Kill failed: ${e.message}")
            }
        }
    }

    fun killConnection(conn: ActiveConnection) {
        viewModelScope.launch {
            repo.killConnection(conn).onSuccess { killed ->
                refreshMonitorOnce()
                toast("Terminated $killed socket(s)")
            }.onFailure { e ->
                toast("Terminate failed: ${e.message}")
            }
        }
    }

    fun clearTelemetry() {
        viewModelScope.launch {
            repo.clearTelemetry().onSuccess {
                _ui.update { it.copy(telemetry = TelemetrySnapshot()) }
                toast("RAM telemetry ring cleared")
            }
        }
    }

    fun setFreezerEnabled(enabled: Boolean, graceSecs: Int) {
        viewModelScope.launch {
            repo.setFreezerEnabled(enabled, graceSecs).onSuccess { fs ->
                _ui.update {
                    it.copy(
                        freezerStatus = fs,
                        config = it.config.copy(
                            freezer = it.config.freezer.copy(
                                enabled = enabled,
                                backgroundGraceSecs = graceSecs,
                            )
                        ),
                    )
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Freezer toggle failed: ${e.message}")
            }
        }
    }

    fun setFreezerEntry(uid: Int, entry: FreezerEntry) {
        viewModelScope.launch {
            repo.setFreezerEntry(uid, entry).onSuccess { fs ->
                _ui.update { cur ->
                    val next = cur.config.freezer.entries.toMutableMap()
                    next[uid] = entry
                    cur.copy(
                        freezerStatus = fs,
                        config = cur.config.copy(freezer = cur.config.freezer.copy(entries = next)),
                    )
                }
            }.onFailure { e ->
                toast("Freezer entry error: ${e.message}")
            }
        }
    }

    fun removeFreezerEntry(uid: Int) {
        viewModelScope.launch {
            repo.removeFreezerEntry(uid).onSuccess { fs ->
                _ui.update { cur ->
                    val next = cur.config.freezer.entries.toMutableMap()
                    next.remove(uid)
                    cur.copy(
                        freezerStatus = fs,
                        config = cur.config.copy(freezer = cur.config.freezer.copy(entries = next)),
                    )
                }
            }
        }
    }

    fun freezeUidNow(uid: Int) {
        viewModelScope.launch {
            repo.freezeUidNow(uid).onSuccess { fs ->
                _ui.update { it.copy(freezerStatus = fs) }
                refreshLightStatus()
                toast("UID $uid frozen in kernel cgroup v2")
            }.onFailure { e ->
                toast("Freeze failed: ${e.message}")
            }
        }
    }

    fun thawUidNow(uid: Int) {
        viewModelScope.launch {
            repo.thawUidNow(uid).onSuccess { fs ->
                _ui.update { it.copy(freezerStatus = fs) }
                refreshLightStatus()
                toast("UID $uid thawed immediately")
            }.onFailure { e ->
                toast("Thaw failed: ${e.message}")
            }
        }
    }

    fun thawAllNow() {
        viewModelScope.launch {
            repo.thawAllNow().onSuccess { fs ->
                _ui.update { it.copy(freezerStatus = fs) }
                refreshLightStatus()
                toast("All frozen apps thawed")
            }
        }
    }

    fun setAppScope(packageName: String, scope: AppIsolationScope) {
        viewModelScope.launch {
            repo.setAppScope(packageName, scope).onSuccess {
                _ui.update { cur ->
                    val next = cur.config.scopes.apps.toMutableMap()
                    if (scope.activeCount() > 0) next[packageName] = scope else next.remove(packageName)
                    cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(apps = next)))
                }
            }.onFailure { e ->
                toast("Scope update failed: ${e.message}")
            }
        }
    }

    fun applyHardenedScope(packageName: String) {
        viewModelScope.launch {
            repo.applyHardenedScope(packageName).onSuccess { profile ->
                _ui.update { cur ->
                    val next = cur.config.scopes.apps.toMutableMap()
                    next[packageName] = profile
                    cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(apps = next)))
                }
                toast("Hardened Isolation Profile applied to $packageName")
            }.onFailure { e ->
                toast("Profile error: ${e.message}")
            }
        }
    }

    fun clearAppScope(packageName: String) {
        viewModelScope.launch {
            repo.clearAppScope(packageName).onSuccess {
                _ui.update { cur ->
                    val next = cur.config.scopes.apps.toMutableMap()
                    next.remove(packageName)
                    cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(apps = next)))
                }
                toast("Scopes reset to default for $packageName")
            }
        }
    }

    fun setAutoClearClipboard(enabled: Boolean, clearSecs: Int) {
        viewModelScope.launch {
            repo.setAutoClearClipboard(enabled, clearSecs).onSuccess { saved ->
                _ui.update { it.copy(config = it.config.copy(scopes = saved)) }
            }.onFailure { e ->
                toast("Clipboard config error: ${e.message}")
            }
        }
    }

    fun clearClipboardNow() {
        viewModelScope.launch {
            repo.clearClipboardNow()
                .onSuccess { toast("Clipboard cleared") }
                .onFailure { e -> toast("Clipboard clear failed: ${e.message}") }
        }
    }

    fun updateHardeningConfig(transform: (HardeningConfig) -> HardeningConfig) {
        val next = transform(_ui.value.config.hardening)
        viewModelScope.launch {
            repo.setHardeningConfig(next).onSuccess { hs ->
                _ui.update {
                    it.copy(
                        hardeningStatus = hs,
                        config = it.config.copy(hardening = next),
                    )
                }
            }.onFailure { e ->
                toast("Hardening error: ${e.message}")
            }
        }
    }

    fun rollbackHardening() {
        viewModelScope.launch {
            repo.rollbackHardening().onSuccess { hs ->
                _ui.update {
                    it.copy(
                        hardeningStatus = hs,
                        config = it.config.copy(hardening = it.config.hardening.copy(enabled = false)),
                    )
                }
                toast("All sysctl values rolled back to pre-Silo snapshot")
            }.onFailure { e ->
                toast("Rollback error: ${e.message}")
            }
        }
    }

    fun updateAutomationConfig(transform: (AutomationConfig) -> AutomationConfig) {
        val next = transform(_ui.value.config.automation)
        viewModelScope.launch {
            repo.setAutomationConfig(next).onSuccess { saved ->
                _ui.update { it.copy(config = it.config.copy(automation = saved)) }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Automation update failed: ${e.message}")
            }
        }
    }

    fun setSensorsOff(enabled: Boolean) {
        viewModelScope.launch {
            repo.setSensorsOff(enabled).onSuccess { active ->
                _ui.update { it.copy(status = it.status.copy(sensorsOffActive = active)) }
                toast(if (active) "Global Mic/Cam sensors muted" else "Global Mic/Cam sensors restored")
            }.onFailure { e ->
                toast("Sensors toggle failed: ${e.message}")
            }
        }
    }

    fun triggerPanicReboot() {
        viewModelScope.launch {
            repo.triggerPanicReboot().onFailure { e ->
                toast("Panic reboot failed: ${e.message}")
            }
        }
    }

    fun runSentinelAudit() {
        viewModelScope.launch {
            repo.runSentinelAudit().onSuccess { report ->
                _ui.update { it.copy(sentinelReport = report) }
                toast("Sentinel audit complete: Score ${report.score}/100")
            }.onFailure { e ->
                toast("Audit failed: ${e.message}")
            }
        }
    }

    fun exportConfigToUri(uri: Uri) {
        viewModelScope.launch {
            repo.exportConfigJson().onSuccess { jsonStr ->
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                            it.write(jsonStr.toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                toast(if (ok) "Backup exported cleanly" else "Failed to write backup file")
            }.onFailure { e ->
                toast("Export failed: ${e.message}")
            }
        }
    }

    fun importConfigFromUri(uri: Uri) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                }.getOrNull()
            }
            if (raw == null) {
                toast("Could not read backup file")
                return@launch
            }
            repo.importConfigJson(raw).onSuccess {
                refreshAll()
                toast("Configuration imported and applied")
            }.onFailure { e ->
                toast("Import failed: ${e.message}")
            }
        }
    }

    fun setDisguiseEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        repo.setDisguiseEnabled(ctx, enabled)
        _ui.update { it.copy(disguiseEnabled = enabled) }
        toast(if (enabled) "Launcher disguised as NetDiag" else "Launcher restored to Silo")
    }

    private suspend fun refreshLightStatus() {
        val st = repo.getStatus().getOrNull()
        val hs = repo.getHardeningStatus().getOrNull()
        val fs = repo.getFreezerStatus().getOrNull()
        _ui.update { cur ->
            cur.copy(
                status = st ?: cur.status,
                hardeningStatus = hs ?: cur.hardeningStatus,
                freezerStatus = fs ?: cur.freezerStatus,
            )
        }
    }

    override fun onCleared() {
        monitorJob?.cancel()
        super.onCleared()
    }
}
