package dev.silo.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silo.app.SiloApp
import dev.silo.app.data.*
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
    val liveProof: LiveProofReport? = null,
    val disguiseEnabled: Boolean = false,
    val languagePref: AppLanguagePref = AppLanguagePref.SYSTEM,
    val themePref: AppThemePref = AppThemePref.OBSIDIAN,
    val snackbarMessage: String? = null,
    val monitorAutoRefresh: Boolean = true,
)

class SiloViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SiloApp).repository
    private val _ui = MutableStateFlow(
        SiloUiState(
            languagePref = repo.getLanguagePref(application),
            themePref = repo.getThemePref(application),
        )
    )
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

    fun setLanguage(pref: AppLanguagePref) {
        repo.setLanguagePref(getApplication(), pref)
        _ui.update { it.copy(languagePref = pref) }
    }

    fun setTheme(pref: AppThemePref) {
        repo.setThemePref(getApplication(), pref)
        _ui.update { it.copy(themePref = pref) }
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
                toast("Preset ${preset.name.lowercase()} diterapkan")
            }.onFailure { e ->
                toast("Gagal menerapkan preset: ${e.message}")
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
                toast("Firewall error: ${e.message}")
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
                toast("Reset error: ${e.message}")
            }
        }
    }

    fun setTempUnblock(uid: Int, durationMinutes: Int) {
        viewModelScope.launch {
            repo.setTempUnblock(uid, durationMinutes).onSuccess {
                refreshLightStatus()
                toast("Temporary internet granted (${durationMinutes}m)")
            }.onFailure { e ->
                toast("Temp unblock failed: ${e.message}")
            }
        }
    }

    fun clearTempUnblock(uid: Int) {
        viewModelScope.launch {
            repo.clearTempUnblock(uid).onSuccess {
                refreshLightStatus()
                toast("Temporary access revoked & TCP cut")
            }.onFailure { e ->
                toast("Revoke failed: ${e.message}")
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
                toast("File tidak dapat dibaca")
                return@launch
            }
            val parsed = BlocklistParser.parseLines(raw)
            if (parsed.isEmpty()) {
                toast("Tidak ditemukan IP/CIDR valid di file")
                return@launch
            }
            repo.replaceIpBlocklist(parsed).onSuccess { obj ->
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Blocklist aktif: ${obj["loaded"]} entri (${obj["backend"]})")
            }.onFailure { e ->
                toast("Blocklist error: ${e.message}")
            }
        }
    }

    fun addSingleBlocklistCidr(cidr: String) {
        val cleaned = BlocklistParser.parseLines(cidr)
        if (cleaned.isEmpty()) {
            toast("Format IP/CIDR tidak valid")
            return
        }
        val merged = (_ui.value.config.firewall.ipBlocklist + cleaned).distinct()
        viewModelScope.launch {
            repo.replaceIpBlocklist(merged).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Ditambahkan: ${cleaned.first()}")
            }.onFailure { e ->
                toast("Gagal: ${e.message}")
            }
        }
    }

    fun clearIpBlocklist() {
        viewModelScope.launch {
            repo.replaceIpBlocklist(emptyList()).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Blocklist IP dikosongkan")
            }.onFailure { e ->
                toast("Gagal: ${e.message}")
            }
        }
    }

    fun emergencyFlushFirewall() {
        viewModelScope.launch {
            repo.emergencyFlushFirewall().onSuccess {
                refreshAll()
                toast("Semua aturan SILO_* di kernel telah dibersihkan")
            }.onFailure { e ->
                toast("Flush gagal: ${e.message}")
            }
        }
    }

    fun killUidSockets(uid: Int) {
        viewModelScope.launch {
            repo.killUidSockets(uid).onSuccess { killed ->
                refreshMonitorOnce()
                toast("SOCK_DESTROY memutus $killed soket TCP aktif")
            }.onFailure { e ->
                toast("Gagal memutus soket: ${e.message}")
            }
        }
    }

    fun killConnection(conn: ActiveConnection) {
        viewModelScope.launch {
            repo.killConnection(conn).onSuccess { killed ->
                refreshMonitorOnce()
                toast("Memutus $killed soket TCP")
            }.onFailure { e ->
                toast("Gagal memutus: ${e.message}")
            }
        }
    }

    fun clearTelemetry() {
        viewModelScope.launch {
            repo.clearTelemetry().onSuccess {
                _ui.update { it.copy(telemetry = TelemetrySnapshot()) }
                toast("Log RAM dibersihkan")
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
                toast("Freezer error: ${e.message}")
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
                toast(fs.lastActionDetail ?: "UID $uid dibekukan")
            }.onFailure { e ->
                toast("Freeze gagal: ${e.message}")
            }
        }
    }

    fun thawUidNow(uid: Int) {
        viewModelScope.launch {
            repo.thawUidNow(uid).onSuccess { fs ->
                _ui.update { it.copy(freezerStatus = fs) }
                refreshLightStatus()
                toast("UID $uid dicairkan")
            }.onFailure { e ->
                toast("Thaw gagal: ${e.message}")
            }
        }
    }

    fun thawAllNow() {
        viewModelScope.launch {
            repo.thawAllNow().onSuccess { fs ->
                _ui.update { it.copy(freezerStatus = fs) }
                refreshLightStatus()
                toast("Semua aplikasi beku & suspend dicairkan")
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
                toast("Scope gagal: ${e.message}")
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
                toast("Isolasi penuh diterapkan ke $packageName")
            }.onFailure { e ->
                toast("Gagal: ${e.message}")
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
                toast("Isolasi direset untuk $packageName")
            }
        }
    }

    fun setAutoClearClipboard(enabled: Boolean, clearSecs: Int) {
        viewModelScope.launch {
            repo.setAutoClearClipboard(enabled, clearSecs).onSuccess { saved ->
                _ui.update { it.copy(config = it.config.copy(scopes = saved)) }
            }.onFailure { e ->
                toast("Clipboard error: ${e.message}")
            }
        }
    }

    /** Uses native Android ClipboardManager FIRST (guaranteed on One UI 7), plus daemon fallback. */
    fun clearClipboardNow() {
        val nativeOk = repo.clearClipboardNative(getApplication())
        viewModelScope.launch {
            val daemonOk = repo.clearClipboardNow().isSuccess
            toast(
                if (nativeOk || daemonOk) "Clipboard berhasil dihapus"
                else "Clipboard gagal dihapus"
            )
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
                toast("Semua parameter sysctl dikembalikan ke default")
            }.onFailure { e ->
                toast("Rollback gagal: ${e.message}")
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
                toast("Otomasi gagal: ${e.message}")
            }
        }
    }

    fun setSensorsOff(enabled: Boolean) {
        viewModelScope.launch {
            repo.setSensorsOff(enabled).onSuccess { active ->
                _ui.update { it.copy(status = it.status.copy(sensorsOffActive = active)) }
                toast(if (active) "Sensor mikrofon & kamera dimatikan" else "Sensor dipulihkan")
            }.onFailure { e ->
                toast("Sensor toggle gagal: ${e.message}")
            }
        }
    }

    fun setPrivateDns(mode: String, specifier: String = "") {
        viewModelScope.launch {
            repo.setPrivateDns(mode, specifier).onSuccess { dns ->
                _ui.update { it.copy(status = it.status.copy(privateDns = dns)) }
                val label = if (dns.mode == "hostname") dns.specifier else dns.mode
                toast("Private DNS: $label")
            }.onFailure { e ->
                toast("Private DNS gagal: ${e.message}")
            }
        }
    }

    fun triggerPanicReboot() {
        viewModelScope.launch {
            repo.triggerPanicReboot().onFailure { e ->
                toast("Panic reboot gagal: ${e.message}")
            }
        }
    }

    fun runSentinelAudit() {
        viewModelScope.launch {
            repo.runSentinelAudit().onSuccess { report ->
                _ui.update { it.copy(sentinelReport = report) }
            }.onFailure { e ->
                toast("Audit gagal: ${e.message}")
            }
        }
    }

    fun runLiveProof(onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            repo.runLiveProof().onSuccess { proof ->
                _ui.update { it.copy(liveProof = proof) }
                onDone?.invoke()
            }.onFailure { e ->
                toast("Live Proof gagal: ${e.message}")
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
                toast(if (ok) "Backup berhasil diekspor" else "Gagal menulis file backup")
            }.onFailure { e ->
                toast("Ekspor gagal: ${e.message}")
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
                toast("File backup tidak bisa dibaca")
                return@launch
            }
            repo.importConfigJson(raw).onSuccess {
                refreshAll()
                toast("Konfigurasi berhasil diimpor")
            }.onFailure { e ->
                toast("Impor gagal: ${e.message}")
            }
        }
    }

    fun setDisguiseEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        repo.setDisguiseEnabled(ctx, enabled)
        _ui.update { it.copy(disguiseEnabled = enabled) }
        toast(if (enabled) "Launcher disamarkan sebagai NetDiag" else "Launcher dikembalikan ke Silo")
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
