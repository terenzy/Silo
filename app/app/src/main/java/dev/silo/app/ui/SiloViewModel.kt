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
    val privacyAccessLog: List<PrivacyAccessEvent> = emptyList(),
    val liveAppPerms: Map<String, AppLivePermissions> = emptyMap(),
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
    private var foregroundSyncJob: Job? = null

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

    /**
     * Real-time sync triggered automatically on Activity ON_RESUME and lightweight 4s foreground tick.
     * Ensures external changes (e.g. unblocking Mic/Camera from Android dialog, granting/revoking
     * permissions in Settings, toggling Private DNS or ADB) appear immediately with 0% background drain.
     */
    fun onForegroundChanged(isResumed: Boolean) {
        foregroundSyncJob?.cancel()
        if (!isResumed) return

        // Immediate real-time reload on resume (PackageManager granted perms + live daemon status)
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            val freshApps = AppInventory.loadInstalledApps(appCtx)
            val st = repo.getStatus().getOrNull()
            val cfg = repo.getConfig().getOrNull()
            _ui.update { cur ->
                cur.copy(
                    connected = st != null || cur.connected,
                    status = st ?: cur.status,
                    config = cfg ?: cur.config,
                    apps = freshApps,
                )
            }
        }

        // Lightweight foreground live status poll every 4 seconds (stops immediately on pause)
        foregroundSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(4_000L)
                if (_ui.value.connected) {
                    val st = repo.getStatus().getOrNull()
                    if (st != null) {
                        _ui.update { it.copy(status = st) }
                    }
                }
            }
        }
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
            refreshPrivacyAccessLog()
            while (isActive) {
                if (_ui.value.connected && _ui.value.monitorAutoRefresh) {
                    refreshMonitorOnce()
                }
                delay(3_000L)
            }
        }
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

    fun refreshPrivacyAccessLog() {
        viewModelScope.launch {
            repo.getPrivacyAccessLog(80).onSuccess { events ->
                _ui.update { it.copy(privacyAccessLog = events) }
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
                val loaded = obj["loaded"]?.toString() ?: parsed.size.toString()
                toast("Memuat $loaded IP/CIDR ke kernel")
            }.onFailure { e ->
                toast("Gagal impor blocklist: ${e.message}")
            }
        }
    }

    fun addSingleBlocklistCidr(cidr: String) {
        val clean = cidr.trim()
        if (clean.isEmpty()) return
        val next = (_ui.value.config.firewall.ipBlocklist + clean).distinct()
        viewModelScope.launch {
            repo.replaceIpBlocklist(next).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Ditambahkan: $clean")
            }.onFailure { e ->
                toast("IP/CIDR tidak valid: ${e.message}")
            }
        }
    }

    fun clearIpBlocklist() {
        viewModelScope.launch {
            repo.replaceIpBlocklist(emptyList()).onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Daftar blokir IP dikosongkan")
            }
        }
    }

    fun emergencyFlushFirewall() {
        viewModelScope.launch {
            repo.emergencyFlushFirewall().onSuccess {
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Flush darurat berhasil: semua aturan SILO_* dicabut")
            }.onFailure { e ->
                toast("Flush gagal: ${e.message}")
            }
        }
    }

    fun killUidSockets(uid: Int) {
        viewModelScope.launch {
            repo.killUidSockets(uid).onSuccess { n ->
                toast("Memutus $n koneksi TCP aktif")
                refreshMonitorOnce()
            }.onFailure { e ->
                toast("SOCK_DESTROY error: ${e.message}")
            }
        }
    }

    fun killConnection(conn: ActiveConnection) {
        viewModelScope.launch {
            repo.killConnection(conn).onSuccess { n ->
                toast("Diputus: $n soket")
                refreshMonitorOnce()
            }.onFailure { e ->
                toast("Gagal memutus soket: ${e.message}")
            }
        }
    }

    fun clearTelemetry() {
        viewModelScope.launch {
            repo.clearTelemetry().onSuccess {
                refreshMonitorOnce()
            }
        }
    }

    fun setFreezerEnabled(enabled: Boolean, graceSecs: Int) {
        viewModelScope.launch {
            repo.setFreezerEnabled(enabled, graceSecs).onSuccess { fz ->
                _ui.update { cur ->
                    cur.copy(
                        config = cur.config.copy(
                            freezer = cur.config.freezer.copy(enabled = enabled, backgroundGraceSecs = graceSecs)
                        ),
                        freezerStatus = fz,
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
            repo.setFreezerEntry(uid, entry).onSuccess { fz ->
                _ui.update { cur ->
                    val map = cur.config.freezer.entries.toMutableMap()
                    map[uid] = entry
                    cur.copy(
                        config = cur.config.copy(freezer = cur.config.freezer.copy(entries = map)),
                        freezerStatus = fz,
                    )
                }
            }.onFailure { e ->
                toast("Freezer entry error: ${e.message}")
            }
        }
    }

    fun removeFreezerEntry(uid: Int) {
        viewModelScope.launch {
            repo.removeFreezerEntry(uid).onSuccess { fz ->
                _ui.update { cur ->
                    val map = cur.config.freezer.entries.toMutableMap()
                    map.remove(uid)
                    cur.copy(
                        config = cur.config.copy(freezer = cur.config.freezer.copy(entries = map)),
                        freezerStatus = fz,
                    )
                }
                refreshLightStatus()
            }
        }
    }

    fun freezeUidNow(uid: Int) {
        viewModelScope.launch {
            repo.freezeUidNow(uid).onSuccess { fz ->
                _ui.update { it.copy(freezerStatus = fz) }
                refreshLightStatus()
                toast(fz.lastActionDetail ?: "Aplikasi dibekukan")
            }.onFailure { e ->
                toast("Gagal membekukan: ${e.message}")
            }
        }
    }

    fun thawUidNow(uid: Int) {
        viewModelScope.launch {
            repo.thawUidNow(uid).onSuccess { fz ->
                _ui.update { it.copy(freezerStatus = fz) }
                refreshLightStatus()
                toast("Aplikasi dicairkan")
            }.onFailure { e ->
                toast("Gagal mencairkan: ${e.message}")
            }
        }
    }

    fun thawAllNow() {
        viewModelScope.launch {
            repo.thawAllNow().onSuccess { fz ->
                _ui.update { it.copy(freezerStatus = fz) }
                refreshLightStatus()
                toast("Semua aplikasi dicairkan")
            }
        }
    }

    // --- Real-Time Per-App Permissions, AppOps & Enforcer ---

    fun inspectAppPermissions(packageName: String) {
        viewModelScope.launch {
            repo.inspectAppPermissions(packageName).onSuccess { live ->
                _ui.update { cur ->
                    val nextMap = cur.liveAppPerms.toMutableMap()
                    nextMap[packageName] = live
                    cur.copy(liveAppPerms = nextMap)
                }
            }
        }
    }

    fun setAppPermissionAction(packageName: String, category: String, action: String) {
        viewModelScope.launch {
            repo.setAppPermissionAction(packageName, category, action).onSuccess { live ->
                val freshApps = AppInventory.loadInstalledApps(getApplication())
                _ui.update { cur ->
                    val nextMap = cur.liveAppPerms.toMutableMap()
                    nextMap[packageName] = live
                    cur.copy(liveAppPerms = nextMap, apps = freshApps)
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Izin error: ${e.message}")
            }
        }
    }

    fun toggleAppAutoRevoke(packageName: String, category: String, enabled: Boolean) {
        val current = _ui.value.config.scopes.guards[packageName] ?: AppPermGuard()
        val nextAuto = if (enabled) {
            (current.autoRevokeOnBg + category).distinct()
        } else {
            current.autoRevokeOnBg.filter { it != category }
        }
        saveAppPermGuard(packageName, current.copy(autoRevokeOnBg = nextAuto))
    }

    fun toggleAppStrictLock(packageName: String, category: String, enabled: Boolean) {
        val current = _ui.value.config.scopes.guards[packageName] ?: AppPermGuard()
        val nextLocked = if (enabled) {
            (current.lockedDeny + category).distinct()
        } else {
            current.lockedDeny.filter { it != category }
        }
        saveAppPermGuard(packageName, current.copy(lockedDeny = nextLocked))
    }

    private fun saveAppPermGuard(packageName: String, guard: AppPermGuard) {
        viewModelScope.launch {
            repo.setAppPermGuard(packageName, guard).onSuccess { scopes ->
                val freshApps = AppInventory.loadInstalledApps(getApplication())
                _ui.update { cur ->
                    cur.copy(
                        config = cur.config.copy(scopes = scopes),
                        apps = freshApps,
                    )
                }
                inspectAppPermissions(packageName)
                refreshLightStatus()
            }.onFailure { e ->
                toast("Guard error: ${e.message}")
            }
        }
    }

    fun revokeAllSensitiveForApp(app: InstalledApp) {
        val cats = app.grantedSensitiveCategories.toList()
        if (cats.isEmpty()) return
        viewModelScope.launch {
            var count = 0
            for (cat in cats) {
                if (repo.setAppPermissionAction(app.packageName, cat, "revoke").isSuccess) {
                    count++
                }
            }
            val freshApps = AppInventory.loadInstalledApps(getApplication())
            _ui.update { it.copy(apps = freshApps) }
            inspectAppPermissions(app.packageName)
            refreshLightStatus()
            toast("Mencabut $count kategori izin sensitif pada ${app.label}")
        }
    }

    fun bulkRevokeCategory(category: String, packages: List<String>) {
        if (packages.isEmpty()) return
        viewModelScope.launch {
            repo.bulkRevokeCategory(category, packages).onSuccess { revoked ->
                val freshApps = AppInventory.loadInstalledApps(getApplication())
                _ui.update { it.copy(apps = freshApps) }
                refreshLightStatus()
                toast("Berhasil mencabut izin $category pada $revoked aplikasi")
            }.onFailure { e ->
                toast("Cabut massal gagal: ${e.message}")
            }
        }
    }

    fun setAutoClearClipboard(enabled: Boolean, clearSecs: Int) {
        viewModelScope.launch {
            repo.setAutoClearClipboard(enabled, clearSecs).onSuccess { scopes ->
                _ui.update { it.copy(config = it.config.copy(scopes = scopes)) }
            }
        }
    }

    fun clearClipboardNow() {
        viewModelScope.launch {
            val nativeOk = repo.clearClipboardNative(getApplication())
            val daemonRes = repo.clearClipboardNow()
            if (nativeOk || daemonRes.isSuccess) {
                toast("Clipboard dibersihkan")
            } else {
                toast("Gagal membersihkan clipboard: ${daemonRes.exceptionOrNull()?.message}")
            }
        }
    }

    fun updateHardeningConfig(transform: (HardeningConfig) -> HardeningConfig) {
        val next = transform(_ui.value.config.hardening)
        viewModelScope.launch {
            repo.setHardeningConfig(next).onSuccess { hStatus ->
                _ui.update { cur ->
                    cur.copy(
                        config = cur.config.copy(hardening = next),
                        hardeningStatus = hStatus,
                    )
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Hardening error: ${e.message}")
            }
        }
    }

    fun rollbackHardening() {
        viewModelScope.launch {
            repo.rollbackHardening().onSuccess { hStatus ->
                _ui.update { cur ->
                    cur.copy(
                        config = cur.config.copy(hardening = cur.config.hardening.copy(enabled = false)),
                        hardeningStatus = hStatus,
                    )
                }
                toast("Seluruh sysctl dikembalikan ke default kernel")
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
                toast("Automation error: ${e.message}")
            }
        }
    }

    fun setSensorPrivacy(sensor: String, enabled: Boolean) {
        viewModelScope.launch {
            repo.setSensorPrivacy(sensor, enabled).onSuccess { live ->
                _ui.update { cur ->
                    cur.copy(
                        status = cur.status.copy(
                            micMuted = live.micMuted,
                            cameraMuted = live.cameraMuted,
                            sensorsOffActive = live.micMuted || live.cameraMuted,
                        )
                    )
                }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Sensor privacy error: ${e.message}")
            }
        }
    }

    fun setPrivateDns(mode: String, specifier: String = "") {
        viewModelScope.launch {
            repo.setPrivateDns(mode, specifier).onSuccess { dns ->
                _ui.update { cur -> cur.copy(status = cur.status.copy(privateDns = dns)) }
                val label = if (dns.mode == "hostname") dns.specifier else dns.mode
                toast("Private DNS: $label")
            }.onFailure { e ->
                toast("Private DNS error: ${e.message}")
            }
        }
    }

    fun setRadioPrivacy(
        enhancedMacRandomization: Boolean? = null,
        disableWifiBleScanning: Boolean? = null,
        disableCaptivePortal: Boolean? = null,
    ) {
        viewModelScope.launch {
            repo.setRadioPrivacy(
                enhancedMacRandomization = enhancedMacRandomization,
                disableWifiBleScanning = disableWifiBleScanning,
                disableCaptivePortal = disableCaptivePortal,
            ).onSuccess { rp ->
                _ui.update { cur -> cur.copy(status = cur.status.copy(radioPrivacy = rp)) }
                refreshLightStatus()
            }.onFailure { e ->
                toast("Radio privacy error: ${e.message}")
            }
        }
    }

    fun triggerPanicLockdown() {
        viewModelScope.launch {
            repo.clearClipboardNative(getApplication())
            repo.triggerPanicLockdown().onSuccess { rep ->
                val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                _ui.update { it.copy(config = cfg) }
                refreshLightStatus()
                toast("Panic Lockdown aktif: ${rep.socketsDestroyed} TCP diputus, Firewall Default-Deny, Mic/Cam & Radio dimatikan")
            }.onFailure { e ->
                toast("Panic Lockdown error: ${e.message}")
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
                toast("Skor Keamanan: ${report.score}/100")
            }.onFailure { e ->
                toast("Audit gagal: ${e.message}")
            }
        }
    }

    fun runLiveProof(onReady: (() -> Unit)? = null) {
        viewModelScope.launch {
            repo.runLiveProof().onSuccess { proof ->
                _ui.update { it.copy(liveProof = proof) }
                onReady?.invoke()
            }.onFailure { e ->
                toast("Uji Bukti Nyata gagal: ${e.message}")
            }
        }
    }

    fun setDisguiseEnabled(enabled: Boolean) {
        repo.setDisguiseEnabled(getApplication(), enabled)
        _ui.update { it.copy(disguiseEnabled = enabled) }
        toast(if (enabled) "Samaran NetDiag aktif" else "Ikon Silo dikembalikan")
    }

    fun exportConfigToUri(uri: Uri) {
        viewModelScope.launch {
            repo.exportConfigJson().onSuccess { jsonStr ->
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                            it.write(jsonStr.toByteArray(Charsets.UTF_8))
                        }
                        true
                    }.getOrDefault(false)
                }
                toast(if (ok) "Konfigurasi diekspor" else "Gagal menulis file backup")
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
            if (raw.isNullOrBlank()) {
                toast("File backup kosong atau tidak terbaca")
                return@launch
            }
            repo.importConfigJson(raw).onSuccess { imported ->
                _ui.update { it.copy(config = imported) }
                refreshAll()
                toast("Konfigurasi dipulihkan")
            }.onFailure { e ->
                toast("Impor gagal: ${e.message}")
            }
        }
    }

    private fun refreshLightStatus() {
        viewModelScope.launch {
            repo.getStatus().onSuccess { st ->
                _ui.update { it.copy(status = st, connected = true) }
            }
        }
    }
}
