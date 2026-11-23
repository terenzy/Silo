package dev.silo.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.silo.app.SiloApp
import dev.silo.app.data.*
import dev.silo.app.ipc.*
import dev.silo.app.ui.i18n.SiloStrings
import dev.silo.app.ui.i18n.resolveStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class SheetFeedback(
    val id: Long,
    val message: String,
    val isError: Boolean = false,
)

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
    val snackbarSeq: Long = 0L,
    val monitorAutoRefresh: Boolean = true,
    val loadingAccessLog: Boolean = false,
    val accessLogError: String? = null,
    val loadingAppPerms: Set<String> = emptySet(),
    val appPermErrors: Map<String, String> = emptyMap(),
    val pendingPermKeys: Set<String> = emptySet(),
    val liveProofLoading: Boolean = false,
    val sentinelLoading: Boolean = false,
    val hardeningLoading: Boolean = false,
    val sheetFeedback: SheetFeedback? = null,
    val activeInspectedPackage: String? = null,
)

private sealed interface BoundedReadResult {
    data class Success(val text: String) : BoundedReadResult
    data object TooLarge : BoundedReadResult
    data object Unreadable : BoundedReadResult
}

class SiloViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as SiloApp).repository
    private val _ui = MutableStateFlow(
        SiloUiState(
            languagePref = repo.getLanguagePref(application),
            themePref = repo.getThemePref(application),
        )
    )
    val ui: StateFlow<SiloUiState> = _ui.asStateFlow()

    private val configMutex = Mutex()
    private val inFlightMutations = AtomicInteger(0)
    private val feedbackSeq = AtomicLong(1L)
    private val consecutiveStatusFailures = AtomicInteger(0)

    private var monitorJob: Job? = null
    private var accessLogJob: Job? = null
    private var foregroundSyncJob: Job? = null
    private var resumeReloadJob: Job? = null
    private var refreshAllJob: Job? = null

    @Volatile
    private var monitorActive: Boolean = false

    @Volatile
    private var monitorAccessTabActive: Boolean = false

    @Volatile
    private var lastFullRefreshMs: Long = 0L

    init {
        refreshAll()
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun strings(): SiloStrings = resolveStrings(_ui.value.languagePref)

    private fun errDetail(t: Throwable?): String =
        t?.message?.takeIf { it.isNotBlank() } ?: strings().daemonOfflineDesc

    private fun presetDisplayName(preset: SecurityPreset, s: SiloStrings): String = when (preset) {
        SecurityPreset.SAFE -> s.presetSafe
        SecurityPreset.BALANCED -> s.presetBalanced
        SecurityPreset.HARDENED -> s.presetHardened
        SecurityPreset.PARANOID -> s.presetLockdown
    }

    private fun emitFeedback(message: String, isError: Boolean = false, inSheet: Boolean = false) {
        val seq = feedbackSeq.getAndIncrement()
        _ui.update { cur ->
            cur.copy(
                snackbarMessage = message,
                snackbarSeq = seq,
                sheetFeedback = if (inSheet) {
                    SheetFeedback(seq, message, isError)
                } else {
                    cur.sheetFeedback
                },
            )
        }
    }

    private suspend inline fun <T> withMutationTracking(block: suspend () -> T): T {
        inFlightMutations.incrementAndGet()
        try {
            return block()
        } finally {
            inFlightMutations.decrementAndGet()
        }
    }

    private fun recordStatusFailure(err: Throwable?) {
        val count = consecutiveStatusFailures.incrementAndGet()
        if (count >= 2) {
            val detail = errDetail(err)
            _ui.update { it.copy(connected = false, connectionError = detail) }
        }
    }

    private suspend fun reloadSingleAppIntoState(packageName: String) {
        val updated = AppInventory.reloadSingleApp(getApplication(), packageName) ?: return
        _ui.update { cur ->
            val next = cur.apps.toMutableList()
            val idx = next.indexOfFirst { it.packageName == packageName }
            if (idx >= 0) {
                next[idx] = updated
            } else {
                next.add(updated)
            }
            next.sortWith(AppInventory.appComparator)
            cur.copy(apps = next)
        }
    }

    private suspend fun readBoundedUtf8(uri: Uri, maxBytes: Int): BoundedReadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: return@withContext BoundedReadResult.Unreadable
                stream.use { input ->
                    val out = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
                    val buf = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        total += n
                        if (total > maxBytes) {
                            return@withContext BoundedReadResult.TooLarge
                        }
                        out.write(buf, 0, n)
                    }
                    BoundedReadResult.Success(out.toString(Charsets.UTF_8.name()))
                }
            }.getOrElse { BoundedReadResult.Unreadable }
        }

    fun clearSnackbar() {
        _ui.update { it.copy(snackbarMessage = null) }
    }

    fun clearSheetFeedback() {
        _ui.update { it.copy(sheetFeedback = null) }
    }

    fun setActiveInspectedPackage(packageName: String?) {
        _ui.update { it.copy(activeInspectedPackage = packageName) }
    }

    fun setMonitorAccessTabActive(active: Boolean) {
        monitorAccessTabActive = active
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
     * Deduplicates cold-start double loads (APP-M01), refreshes inspected live permissions, freezer,
     * hardening, and Monitor access log on resume, and flips offline on consecutive failures (APP-H11).
     */
    fun onForegroundChanged(isResumed: Boolean) {
        foregroundSyncJob?.cancel()
        resumeReloadJob?.cancel()
        if (!isResumed) return

        val skipImmediate = refreshAllJob?.isActive == true || (nowMs() - lastFullRefreshMs) < 1_500L
        if (!skipImmediate) {
            resumeReloadJob = viewModelScope.launch {
                val appCtx = getApplication<Application>()
                val freshApps = AppInventory.loadInstalledApps(appCtx)
                val statusRes = repo.getStatus()
                if (statusRes.isFailure) {
                    _ui.update { it.copy(apps = freshApps) }
                    recordStatusFailure(statusRes.exceptionOrNull())
                    return@launch
                }
                consecutiveStatusFailures.set(0)
                val st = statusRes.getOrThrow()
                val cfg = repo.getConfig().getOrNull()
                val fz = repo.getFreezerStatus().getOrNull()
                val hStatus = repo.getHardeningStatus().getOrNull()
                _ui.update { cur ->
                    cur.copy(
                        connected = true,
                        connectionError = null,
                        status = st,
                        config = cfg ?: cur.config,
                        apps = freshApps,
                        freezerStatus = fz ?: cur.freezerStatus,
                        hardeningStatus = hStatus ?: cur.hardeningStatus,
                    )
                }
                _ui.value.activeInspectedPackage?.let { pkg ->
                    inspectAppPermissionsSuspend(pkg)
                }
                if (monitorActive && monitorAccessTabActive) {
                    refreshPrivacyAccessLog(force = true)
                }
            }
        }

        foregroundSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(4_000L)
                if (inFlightMutations.get() > 0 || !_ui.value.connected) continue
                val res = repo.getStatus()
                res.onSuccess { st ->
                    consecutiveStatusFailures.set(0)
                    _ui.update { it.copy(status = st, connected = true, connectionError = null) }
                }.onFailure { err ->
                    recordStatusFailure(err)
                }
            }
        }
    }

    fun refreshAll() {
        if (refreshAllJob?.isActive == true) return
        lastFullRefreshMs = nowMs()
        refreshAllJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val appCtx = getApplication<Application>()
            val installed = AppInventory.loadInstalledApps(appCtx)
            val disguised = repo.isDisguiseEnabled(appCtx)

            val statusRes = repo.getStatus()
            if (statusRes.isFailure) {
                consecutiveStatusFailures.incrementAndGet()
                _ui.update {
                    it.copy(
                        loading = false,
                        connected = false,
                        connectionError = errDetail(statusRes.exceptionOrNull()),
                        apps = installed,
                        disguiseEnabled = disguised,
                    )
                }
                return@launch
            }

            consecutiveStatusFailures.set(0)
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

            _ui.value.activeInspectedPackage?.let { pkg ->
                inspectAppPermissionsSuspend(pkg)
            }
            if (monitorActive && monitorAccessTabActive) {
                refreshPrivacyAccessLog(force = true)
            }
        }
    }

    fun setMonitorPollingActive(active: Boolean) {
        monitorActive = active
        monitorJob?.cancel()
        if (!active) {
            accessLogJob?.cancel()
            return
        }
        monitorJob = viewModelScope.launch {
            while (isActive) {
                if (_ui.value.connected && _ui.value.monitorAutoRefresh) {
                    refreshMonitorOnce()
                }
                delay(3_000L)
            }
        }
    }

    suspend fun refreshMonitorOnce() {
        if (inFlightMutations.get() > 0) return
        val conns = repo.getLiveConnections().getOrNull()
        val tel = repo.getTelemetry(300).getOrNull()
        val stRes = repo.getStatus()
        stRes.onSuccess { st ->
            consecutiveStatusFailures.set(0)
            _ui.update { cur ->
                cur.copy(
                    connected = true,
                    connectionError = null,
                    liveConnections = conns ?: cur.liveConnections,
                    telemetry = tel ?: cur.telemetry,
                    status = st,
                )
            }
        }.onFailure { err ->
            _ui.update { cur ->
                cur.copy(
                    liveConnections = conns ?: cur.liveConnections,
                    telemetry = tel ?: cur.telemetry,
                )
            }
            recordStatusFailure(err)
        }
    }

    fun refreshPrivacyAccessLog(force: Boolean = false) {
        if (!force && accessLogJob?.isActive == true) return
        if (force) accessLogJob?.cancel()
        accessLogJob = viewModelScope.launch {
            refreshPrivacyAccessLogSuspend()
        }
    }

    private suspend fun refreshPrivacyAccessLogSuspend() {
        _ui.update { it.copy(loadingAccessLog = true, accessLogError = null) }
        try {
            val res = repo.getPrivacyAccessLog(80)
            res.onSuccess { events ->
                _ui.update {
                    it.copy(
                        privacyAccessLog = events,
                        loadingAccessLog = false,
                        accessLogError = null,
                    )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                val msg = errDetail(err)
                _ui.update { it.copy(loadingAccessLog = false, accessLogError = msg) }
                emitFeedback("${strings().accessLogLoadFailed}: $msg", isError = true)
            }
        } catch (e: CancellationException) {
            _ui.update { it.copy(loadingAccessLog = false) }
            throw e
        }
    }

    fun applyPreset(preset: SecurityPreset) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    repo.applyPreset(preset).onSuccess { newCfg ->
                        _ui.update { it.copy(config = newCfg, sentinelLoading = true) }
                        val hardening = repo.getHardeningStatus().getOrNull()
                        val freezer = repo.getFreezerStatus().getOrNull()
                        val audit = repo.runSentinelAudit().getOrNull()
                        _ui.update { cur ->
                            cur.copy(
                                hardeningStatus = hardening ?: cur.hardeningStatus,
                                freezerStatus = freezer ?: cur.freezerStatus,
                                sentinelReport = audit ?: cur.sentinelReport,
                                sentinelLoading = false,
                            )
                        }
                        refreshLightStatusSuspend()
                        val s = strings()
                        emitFeedback(s.toastPresetApplied(presetDisplayName(preset, s)))
                    }.onFailure { e ->
                        _ui.update { it.copy(sentinelLoading = false) }
                        emitFeedback(strings().toastPresetFailed(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun updateFirewallConfig(transform: (FirewallConfig) -> FirewallConfig) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prev = _ui.value.config.firewall
                    val next = transform(prev)
                    _ui.update { it.copy(config = it.config.copy(firewall = next)) }
                    repo.setFirewallConfig(next).onSuccess { saved ->
                        _ui.update { it.copy(config = it.config.copy(firewall = saved)) }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(firewall = prev)) }
                        emitFeedback(strings().toastFirewallError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun mutateAppFirewallRule(
        uid: Int,
        packageName: String,
        transform: (AppFirewallRule) -> AppFirewallRule,
    ) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val fw = _ui.value.config.firewall
                    val defaultAllow = fw.mode == FirewallMode.DEFAULT_ALLOW
                    val prevRule = fw.rules[uid]
                    val base = prevRule ?: AppFirewallRule(
                        packageName = packageName,
                        allowWifi = defaultAllow,
                        allowCellular = defaultAllow,
                        allowVpn = defaultAllow,
                        allowLan = false,
                        allowScreenOff = true,
                    )
                    val nextRule = transform(base).copy(packageName = packageName)
                    applyAppFirewallRuleLocked(uid, prevRule, nextRule)
                }
            }
        }
    }

    fun updateAppFirewallRule(uid: Int, rule: AppFirewallRule) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevRule = _ui.value.config.firewall.rules[uid]
                    applyAppFirewallRuleLocked(uid, prevRule, rule)
                }
            }
        }
    }

    fun blockAppFromMonitor(uid: Int, packageName: String, appLabel: String) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevRule = _ui.value.config.firewall.rules[uid]
                    val base = prevRule ?: AppFirewallRule(packageName = packageName)
                    val nextRule = base.copy(
                        packageName = if (packageName.isNotBlank()) packageName else base.packageName,
                        allowWifi = false,
                        allowCellular = false,
                        allowVpn = false,
                        allowScreenOff = false,
                    )
                    val ok = applyAppFirewallRuleLocked(uid, prevRule, nextRule, inSheet = false)
                    if (ok) {
                        emitFeedback(strings().toastAppBlocked(appLabel))
                    }
                }
            }
        }
    }

    private suspend fun applyAppFirewallRuleLocked(
        uid: Int,
        prevRule: AppFirewallRule?,
        nextRule: AppFirewallRule,
        inSheet: Boolean = true,
    ): Boolean {
        _ui.update { cur ->
            val updated = cur.config.firewall.rules.toMutableMap()
            updated[uid] = nextRule
            cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = updated)))
        }
        return repo.setAppFirewallRule(uid, nextRule).fold(
            onSuccess = {
                refreshLightStatusSuspend()
                true
            },
            onFailure = { e ->
                _ui.update { cur ->
                    val restored = cur.config.firewall.rules.toMutableMap()
                    if (prevRule == null) restored.remove(uid) else restored[uid] = prevRule
                    cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = restored)))
                }
                emitFeedback(strings().toastRuleError(errDetail(e)), isError = true, inSheet = inSheet)
                false
            },
        )
    }

    fun removeAppFirewallRule(uid: Int) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevRule = _ui.value.config.firewall.rules[uid]
                    _ui.update { cur ->
                        val updated = cur.config.firewall.rules.toMutableMap()
                        updated.remove(uid)
                        cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = updated)))
                    }
                    repo.removeAppFirewallRule(uid).onSuccess {
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastAppRuleReset(), inSheet = true)
                    }.onFailure { e ->
                        _ui.update { cur ->
                            val restored = cur.config.firewall.rules.toMutableMap()
                            if (prevRule != null) restored[uid] = prevRule
                            cur.copy(config = cur.config.copy(firewall = cur.config.firewall.copy(rules = restored)))
                        }
                        emitFeedback(strings().toastResetError(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun setTempUnblock(uid: Int, durationMinutes: Int) {
        viewModelScope.launch {
            withMutationTracking {
                repo.setTempUnblock(uid, durationMinutes).onSuccess {
                    refreshLightStatusSuspend()
                    emitFeedback(strings().toastTempUnblockGranted(durationMinutes), inSheet = true)
                }.onFailure { e ->
                    emitFeedback(strings().toastTempUnblockFailed(errDetail(e)), isError = true, inSheet = true)
                }
            }
        }
    }

    fun clearTempUnblock(uid: Int) {
        viewModelScope.launch {
            withMutationTracking {
                repo.clearTempUnblock(uid).onSuccess {
                    refreshLightStatusSuspend()
                    emitFeedback(strings().toastTempUnblockRevoked(), inSheet = true)
                }.onFailure { e ->
                    emitFeedback(strings().toastRevokeFailed(errDetail(e)), isError = true, inSheet = true)
                }
            }
        }
    }

    fun importIpBlocklistFromUri(uri: Uri) {
        viewModelScope.launch {
            val parseRes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        BlocklistParser.parseStream(stream)
                    }
                }.getOrNull()
            }
            if (parseRes == null) {
                emitFeedback(strings().toastFileUnreadable(), isError = true, inSheet = true)
                return@launch
            }
            if (parseRes.entries.isEmpty()) {
                emitFeedback(strings().toastNoValidCidr(), isError = true, inSheet = true)
                return@launch
            }
            withMutationTracking {
                configMutex.withLock {
                    repo.replaceIpBlocklist(parseRes.entries).onSuccess { obj ->
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        val loaded = obj["loaded"]?.jsonPrimitive?.intOrNull ?: parseRes.entries.size
                        val rejected = obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0
                        val truncated = parseRes.truncatedByByteCap ||
                            parseRes.truncatedByEntryCap ||
                            parseRes.truncatedByStreamCap
                        emitFeedback(
                            strings().toastBlocklistLoaded(loaded, rejected, truncated),
                            isError = loaded == 0 && rejected > 0,
                            inSheet = true,
                        )
                    }.onFailure { e ->
                        emitFeedback(strings().toastBlocklistImportFailed(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun addSingleBlocklistCidr(cidr: String) {
        val clean = cidr.trim()
        if (clean.isEmpty()) return
        val validated = BlocklistParser.validateEntry(clean)
        if (validated == null) {
            emitFeedback(strings().toastInvalidCidr(clean), isError = true, inSheet = true)
            return
        }
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val existing = _ui.value.config.firewall.ipBlocklist
                    if (existing.contains(validated)) {
                        emitFeedback(strings().toastCidrAlreadyExists(validated), isError = false, inSheet = true)
                        return@withLock
                    }
                    val next = existing + validated
                    repo.replaceIpBlocklist(next).onSuccess { obj ->
                        val rejected = obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        if (rejected > 0) {
                            emitFeedback(strings().toastInvalidCidr(validated), isError = true, inSheet = true)
                        } else {
                            emitFeedback(strings().toastCidrAdded(validated), inSheet = true)
                        }
                    }.onFailure { e ->
                        emitFeedback(strings().toastInvalidCidr(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun removeSingleBlocklistCidr(cidr: String) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val next = _ui.value.config.firewall.ipBlocklist.filterNot { it == cidr }
                    repo.replaceIpBlocklist(next).onSuccess { obj ->
                        val rejected = obj["rejected"]?.jsonPrimitive?.intOrNull ?: 0
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        if (rejected > 0) {
                            val s = strings()
                            val detail = if (s.isIndonesian) "$rejected ditolak" else "$rejected rejected"
                            emitFeedback(s.toastBlocklistImportFailed(detail), isError = true, inSheet = true)
                        } else {
                            emitFeedback(strings().toastCidrRemoved(cidr), inSheet = true)
                        }
                    }.onFailure { e ->
                        emitFeedback(strings().toastBlocklistImportFailed(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun clearIpBlocklist() {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    repo.replaceIpBlocklist(emptyList()).onSuccess {
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastBlocklistCleared(), inSheet = true)
                    }.onFailure { e ->
                        emitFeedback(strings().toastBlocklistImportFailed(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun emergencyFlushFirewall() {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    repo.emergencyFlushFirewall().onSuccess {
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastFlushSuccess())
                    }.onFailure { e ->
                        emitFeedback(strings().toastFlushFailed(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun killUidSockets(uid: Int) {
        viewModelScope.launch {
            val ok = withMutationTracking {
                repo.killUidSockets(uid).fold(
                    onSuccess = { n ->
                        emitFeedback(strings().toastKilledTcp(n), inSheet = true)
                        true
                    },
                    onFailure = { e ->
                        emitFeedback(strings().toastSockDestroyError(errDetail(e)), isError = true, inSheet = true)
                        false
                    },
                )
            }
            if (ok) refreshMonitorOnce()
        }
    }

    fun killConnection(conn: ActiveConnection) {
        viewModelScope.launch {
            val ok = withMutationTracking {
                repo.killConnection(conn).fold(
                    onSuccess = { n ->
                        emitFeedback(strings().toastKilledSocket(n))
                        true
                    },
                    onFailure = { e ->
                        emitFeedback(strings().toastKillSocketFailed(errDetail(e)), isError = true)
                        false
                    },
                )
            }
            if (ok) refreshMonitorOnce()
        }
    }

    fun clearTelemetry() {
        viewModelScope.launch {
            val ok = withMutationTracking {
                repo.clearTelemetry().fold(
                    onSuccess = {
                        emitFeedback(strings().toastTelemetryCleared())
                        true
                    },
                    onFailure = { e ->
                        emitFeedback(strings().toastTelemetryError(errDetail(e)), isError = true)
                        false
                    },
                )
            }
            if (ok) refreshMonitorOnce()
        }
    }

    fun setFreezerEnabled(enabled: Boolean, graceSecs: Int) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prev = _ui.value.config.freezer
                    val next = prev.copy(enabled = enabled, backgroundGraceSecs = graceSecs)
                    _ui.update { it.copy(config = it.config.copy(freezer = next)) }
                    repo.setFreezerEnabled(enabled, graceSecs).onSuccess { fz ->
                        _ui.update { it.copy(freezerStatus = fz) }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(freezer = prev)) }
                        emitFeedback(strings().toastFreezerError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun setFreezerEntry(uid: Int, entry: FreezerEntry) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevEntries = _ui.value.config.freezer.entries
                    val optimistic = prevEntries.toMutableMap().apply { put(uid, entry) }
                    _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = optimistic))) }
                    repo.setFreezerEntry(uid, entry).onSuccess { fz ->
                        _ui.update { it.copy(freezerStatus = fz) }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = prevEntries))) }
                        emitFeedback(strings().toastFreezerError(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun removeFreezerEntry(uid: Int) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevEntries = _ui.value.config.freezer.entries
                    val optimistic = prevEntries.toMutableMap().apply { remove(uid) }
                    _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = optimistic))) }
                    repo.removeFreezerEntry(uid).onSuccess { fz ->
                        _ui.update { it.copy(freezerStatus = fz) }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = prevEntries))) }
                        emitFeedback(strings().toastFreezerError(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    /**
     * Atomically registers/updates the freezer entry and immediately freezes the UID in one
     * serialized coroutine so `freezeUidNow` can never outrace `setFreezerEntry` (APP-M02).
     */
    fun ensureEntryAndFreeze(uid: Int, entry: FreezerEntry) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevEntries = _ui.value.config.freezer.entries
                    if (prevEntries[uid] != entry) {
                        val optimistic = prevEntries.toMutableMap().apply { put(uid, entry) }
                        _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = optimistic))) }
                        val entryRes = repo.setFreezerEntry(uid, entry)
                        if (entryRes.isFailure) {
                            _ui.update { it.copy(config = it.config.copy(freezer = it.config.freezer.copy(entries = prevEntries))) }
                            emitFeedback(
                                strings().toastFreezerError(errDetail(entryRes.exceptionOrNull())),
                                isError = true,
                                inSheet = true,
                            )
                            return@withLock
                        }
                        entryRes.getOrNull()?.let { fz -> _ui.update { it.copy(freezerStatus = fz) } }
                    }
                    repo.freezeUidNow(uid).onSuccess { fz ->
                        _ui.update { it.copy(freezerStatus = fz) }
                        refreshLightStatusSuspend()
                        emitFeedback(fz.lastActionDetail ?: strings().toastAppFrozen(), inSheet = true)
                    }.onFailure { e ->
                        emitFeedback(strings().toastFreezeFailed(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun freezeUidNow(uid: Int) {
        viewModelScope.launch {
            withMutationTracking {
                repo.freezeUidNow(uid).onSuccess { fz ->
                    _ui.update { it.copy(freezerStatus = fz) }
                    refreshLightStatusSuspend()
                    emitFeedback(fz.lastActionDetail ?: strings().toastAppFrozen(), inSheet = true)
                }.onFailure { e ->
                    emitFeedback(strings().toastFreezeFailed(errDetail(e)), isError = true, inSheet = true)
                }
            }
        }
    }

    fun thawUidNow(uid: Int) {
        viewModelScope.launch {
            withMutationTracking {
                repo.thawUidNow(uid).onSuccess { fz ->
                    _ui.update { it.copy(freezerStatus = fz) }
                    refreshLightStatusSuspend()
                    emitFeedback(strings().toastAppThawed(), inSheet = true)
                }.onFailure { e ->
                    emitFeedback(strings().toastThawFailed(errDetail(e)), isError = true, inSheet = true)
                }
            }
        }
    }

    fun thawAllNow() {
        viewModelScope.launch {
            withMutationTracking {
                repo.thawAllNow().onSuccess { fz ->
                    _ui.update { it.copy(freezerStatus = fz) }
                    refreshLightStatusSuspend()
                    emitFeedback(strings().toastThawAllDone())
                }.onFailure { e ->
                    emitFeedback(strings().toastThawFailed(errDetail(e)), isError = true)
                }
            }
        }
    }

    // --- Real-Time Per-App Permissions, AppOps & Enforcer ---

    fun inspectAppPermissions(packageName: String) {
        viewModelScope.launch {
            inspectAppPermissionsSuspend(packageName)
        }
    }

    private suspend fun inspectAppPermissionsSuspend(packageName: String) {
        _ui.update { cur ->
            cur.copy(
                loadingAppPerms = cur.loadingAppPerms + packageName,
                appPermErrors = cur.appPermErrors - packageName,
            )
        }
        repo.inspectAppPermissions(packageName).onSuccess { live ->
            _ui.update { cur ->
                val nextMap = cur.liveAppPerms.toMutableMap()
                nextMap[packageName] = live
                cur.copy(
                    liveAppPerms = nextMap,
                    loadingAppPerms = cur.loadingAppPerms - packageName,
                    appPermErrors = cur.appPermErrors - packageName,
                )
            }
        }.onFailure { e ->
            if (e is CancellationException) {
                _ui.update { it.copy(loadingAppPerms = it.loadingAppPerms - packageName) }
                throw e
            }
            val msg = errDetail(e)
            _ui.update { cur ->
                cur.copy(
                    loadingAppPerms = cur.loadingAppPerms - packageName,
                    appPermErrors = cur.appPermErrors + (packageName to msg),
                )
            }
            emitFeedback("${strings().permsLoadFailed}: $msg", isError = true, inSheet = true)
        }
    }

    fun setAppPermissionAction(packageName: String, category: String, action: String) {
        val normalizedAction = action.trim().lowercase()
        val prefix = "$packageName:$category:"
        val key = "$prefix$normalizedAction"
        if (_ui.value.pendingPermKeys.any { it.startsWith(prefix) }) return

        viewModelScope.launch {
            _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys + key) }
            try {
                withMutationTracking {
                    var strippedGuardFromLock = false
                    var prevGuards: Map<String, AppPermGuard>? = null
                    if (normalizedAction == "allow" || normalizedAction == "default") {
                        configMutex.withLock {
                            val currentGuards = _ui.value.config.scopes.guards
                            val curGuard = currentGuards[packageName]
                            if (curGuard != null && curGuard.lockedDeny.contains(category)) {
                                prevGuards = currentGuards
                                strippedGuardFromLock = true
                                val updatedGuard = curGuard.withStrictLock(category, false)
                                val nextGuards = currentGuards.toMutableMap()
                                if (updatedGuard.totalCount() == 0) {
                                    nextGuards.remove(packageName)
                                } else {
                                    nextGuards[packageName] = updatedGuard
                                }
                                _ui.update { cur ->
                                    cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(guards = nextGuards)))
                                }
                            }
                        }
                    }

                    repo.setAppPermissionAction(packageName, category, normalizedAction).onSuccess { live ->
                        _ui.update { cur ->
                            val nextMap = cur.liveAppPerms.toMutableMap()
                            nextMap[packageName] = live
                            cur.copy(
                                liveAppPerms = nextMap,
                                appPermErrors = cur.appPermErrors - packageName,
                            )
                        }
                        reloadSingleAppIntoState(packageName)
                        if (strippedGuardFromLock) {
                            configMutex.withLock {
                                repo.getConfig().onSuccess { freshCfg ->
                                    _ui.update { cur ->
                                        cur.copy(config = cur.config.copy(scopes = freshCfg.scopes))
                                    }
                                }
                            }
                        }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastPermActionApplied(category, normalizedAction), inSheet = true)
                    }.onFailure { e ->
                        if (strippedGuardFromLock && prevGuards != null) {
                            configMutex.withLock {
                                _ui.update { cur ->
                                    cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(guards = prevGuards!!)))
                                }
                            }
                        }
                        emitFeedback(strings().toastPermError(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            } finally {
                _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys - key) }
            }
        }
    }

    /**
     * Sequential revoke + access-log refresh for Monitor -> Akses Izin (APP-H05).
     * Eliminates the dual-coroutine race and provides per-row pending state and localized feedback.
     */
    fun revokeFromAccessLog(packageName: String, category: String) {
        val prefix = "$packageName:$category:"
        val key = "${prefix}revoke"
        if (_ui.value.pendingPermKeys.any { it.startsWith(prefix) }) return

        viewModelScope.launch {
            _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys + key) }
            try {
                withMutationTracking {
                    repo.setAppPermissionAction(packageName, category, "revoke").onSuccess { live ->
                        _ui.update { cur ->
                            val nextMap = cur.liveAppPerms.toMutableMap()
                            nextMap[packageName] = live
                            cur.copy(liveAppPerms = nextMap, appPermErrors = cur.appPermErrors - packageName)
                        }
                        reloadSingleAppIntoState(packageName)
                        accessLogJob?.cancel()
                        refreshPrivacyAccessLogSuspend()
                        refreshLightStatusSuspend()
                        val appLabel = _ui.value.apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
                        emitFeedback(strings().toastAccessRevoked(category, appLabel))
                    }.onFailure { e ->
                        emitFeedback(strings().toastPermError(errDetail(e)), isError = true)
                    }
                }
            } finally {
                _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys - key) }
            }
        }
    }

    fun toggleAppAutoRevoke(packageName: String, category: String, enabled: Boolean) {
        saveAppPermGuardMutation(
            packageName = packageName,
            category = category,
            keySuffix = "guard_auto",
        ) { current ->
            current.withAutoRevoke(category, enabled)
        }
    }

    fun toggleAppStrictLock(packageName: String, category: String, enabled: Boolean) {
        saveAppPermGuardMutation(
            packageName = packageName,
            category = category,
            keySuffix = "guard_lock",
        ) { current ->
            current.withStrictLock(category, enabled)
        }
    }

    private fun saveAppPermGuardMutation(
        packageName: String,
        category: String,
        keySuffix: String,
        transform: (AppPermGuard) -> AppPermGuard,
    ) {
        val guardPrefix = "$packageName:$category:guard_"
        val key = "$packageName:$category:$keySuffix"
        if (_ui.value.pendingPermKeys.any { it.startsWith(guardPrefix) }) return

        viewModelScope.launch {
            _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys + key) }
            try {
                withMutationTracking {
                    configMutex.withLock {
                        val prevGuards = _ui.value.config.scopes.guards
                        val current = prevGuards[packageName] ?: AppPermGuard()
                        val nextGuard = transform(current).normalized()
                        val optimisticGuards = prevGuards.toMutableMap()
                        if (nextGuard.totalCount() == 0) {
                            optimisticGuards.remove(packageName)
                        } else {
                            optimisticGuards[packageName] = nextGuard
                        }
                        _ui.update { cur ->
                            cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(guards = optimisticGuards)))
                        }

                        repo.setAppPermGuard(packageName, nextGuard).onSuccess { scopes ->
                            _ui.update { cur -> cur.copy(config = cur.config.copy(scopes = scopes)) }
                            reloadSingleAppIntoState(packageName)
                            inspectAppPermissionsSuspend(packageName)
                            refreshLightStatusSuspend()
                            emitFeedback(strings().toastGuardUpdated(), inSheet = true)
                        }.onFailure { e ->
                            _ui.update { cur ->
                                cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(guards = prevGuards)))
                            }
                            emitFeedback(strings().toastGuardError(errDetail(e)), isError = true, inSheet = true)
                        }
                    }
                }
            } finally {
                _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys - key) }
            }
        }
    }

    fun revokeAllSensitiveForApp(app: InstalledApp) {
        val cats = app.grantedSensitiveCategories.toList()
        if (cats.isEmpty()) return
        val key = "${app.packageName}:*:revoke_all"
        if (_ui.value.pendingPermKeys.contains(key)) return

        viewModelScope.launch {
            _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys + key) }
            try {
                withMutationTracking {
                    var successCount = 0
                    var failCount = 0
                    var lastErr: Throwable? = null
                    var lastLive: AppLivePermissions? = null
                    for (cat in cats) {
                        val res = repo.setAppPermissionAction(app.packageName, cat, "revoke")
                        if (res.isSuccess) {
                            successCount++
                            lastLive = res.getOrNull()
                        } else {
                            failCount++
                            lastErr = res.exceptionOrNull()
                        }
                    }
                    if (lastLive != null) {
                        _ui.update { cur ->
                            val nextMap = cur.liveAppPerms.toMutableMap()
                            nextMap[app.packageName] = lastLive
                            cur.copy(liveAppPerms = nextMap, appPermErrors = cur.appPermErrors - app.packageName)
                        }
                    } else {
                        inspectAppPermissionsSuspend(app.packageName)
                    }
                    reloadSingleAppIntoState(app.packageName)
                    refreshLightStatusSuspend()
                    if (successCount == 0 && failCount > 0) {
                        emitFeedback(strings().toastPermError(errDetail(lastErr)), isError = true, inSheet = true)
                    } else {
                        emitFeedback(strings().toastRevokedAllForApp(successCount, app.label), inSheet = true)
                    }
                }
            } finally {
                _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys - key) }
            }
        }
    }

    fun bulkRevokeCategory(category: String, packages: List<String>) {
        if (packages.isEmpty()) return
        val key = "bulk:$category"
        if (_ui.value.pendingPermKeys.contains(key)) return

        viewModelScope.launch {
            _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys + key) }
            try {
                withMutationTracking {
                    repo.bulkRevokeCategory(category, packages).onSuccess { revoked ->
                        if (packages.size <= 15) {
                            for (pkg in packages) {
                                reloadSingleAppIntoState(pkg)
                            }
                        } else {
                            val freshApps = AppInventory.loadInstalledApps(getApplication())
                            _ui.update { it.copy(apps = freshApps) }
                        }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastBulkRevoked(category, revoked))
                    }.onFailure { e ->
                        emitFeedback(strings().toastBulkRevokeFailed(errDetail(e)), isError = true)
                    }
                }
            } finally {
                _ui.update { it.copy(pendingPermKeys = it.pendingPermKeys - key) }
            }
        }
    }

    fun clearLegacyAppScope(packageName: String) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevApps = _ui.value.config.scopes.apps
                    val optimistic = prevApps - packageName
                    _ui.update { cur ->
                        cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(apps = optimistic)))
                    }
                    repo.clearAppScope(packageName).onSuccess {
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastLegacyScopeCleared(), inSheet = true)
                    }.onFailure { e ->
                        _ui.update { cur ->
                            cur.copy(config = cur.config.copy(scopes = cur.config.scopes.copy(apps = prevApps)))
                        }
                        emitFeedback(strings().toastPermError(errDetail(e)), isError = true, inSheet = true)
                    }
                }
            }
        }
    }

    fun setAutoClearClipboard(enabled: Boolean, clearSecs: Int) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prevScopes = _ui.value.config.scopes
                    val optimistic = prevScopes.copy(autoClearClipboard = enabled, clipboardClearSecs = clearSecs)
                    _ui.update { it.copy(config = it.config.copy(scopes = optimistic)) }
                    repo.setAutoClearClipboard(enabled, clearSecs).onSuccess { scopes ->
                        _ui.update { it.copy(config = it.config.copy(scopes = scopes)) }
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(scopes = prevScopes)) }
                        emitFeedback(strings().toastClipboardGuardError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun clearClipboardNow() {
        viewModelScope.launch {
            val nativeOk = repo.clearClipboardNative(getApplication())
            val daemonRes = repo.clearClipboardNow()
            if (nativeOk || daemonRes.isSuccess) {
                emitFeedback(strings().toastClipboardCleared())
            } else {
                emitFeedback(strings().toastClipboardClearFailed(errDetail(daemonRes.exceptionOrNull())), isError = true)
            }
        }
    }

    fun refreshHardeningStatus() {
        viewModelScope.launch {
            _ui.update { it.copy(hardeningLoading = true) }
            repo.getHardeningStatus().onSuccess { hStatus ->
                _ui.update { it.copy(hardeningStatus = hStatus, hardeningLoading = false) }
            }.onFailure { e ->
                _ui.update { it.copy(hardeningLoading = false) }
                emitFeedback(strings().toastHardeningError(errDetail(e)), isError = true, inSheet = true)
            }
        }
    }

    fun updateHardeningConfig(transform: (HardeningConfig) -> HardeningConfig) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prev = _ui.value.config.hardening
                    val next = transform(prev)
                    _ui.update { it.copy(config = it.config.copy(hardening = next)) }
                    repo.setHardeningConfig(next).onSuccess { hStatus ->
                        _ui.update { cur ->
                            cur.copy(
                                config = cur.config.copy(hardening = next),
                                hardeningStatus = hStatus,
                            )
                        }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(hardening = prev)) }
                        emitFeedback(strings().toastHardeningError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun rollbackHardening() {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    repo.rollbackHardening().onSuccess { hStatus ->
                        _ui.update { cur ->
                            cur.copy(
                                config = cur.config.copy(hardening = cur.config.hardening.copy(enabled = false)),
                                hardeningStatus = hStatus,
                            )
                        }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastHardeningRolledBack())
                    }.onFailure { e ->
                        emitFeedback(strings().toastHardeningError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun updateAutomationConfig(transform: (AutomationConfig) -> AutomationConfig) {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    val prev = _ui.value.config.automation
                    val next = transform(prev)
                    _ui.update { it.copy(config = it.config.copy(automation = next)) }
                    repo.setAutomationConfig(next).onSuccess { saved ->
                        _ui.update { it.copy(config = it.config.copy(automation = saved)) }
                        refreshLightStatusSuspend()
                    }.onFailure { e ->
                        _ui.update { it.copy(config = it.config.copy(automation = prev)) }
                        emitFeedback(strings().toastAutomationError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun setSensorPrivacy(sensor: String, enabled: Boolean) {
        viewModelScope.launch {
            withMutationTracking {
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
                    refreshLightStatusSuspend()
                }.onFailure { e ->
                    emitFeedback(strings().toastSensorError(errDetail(e)), isError = true)
                }
            }
        }
    }

    fun setPrivateDns(mode: String, specifier: String = "") {
        viewModelScope.launch {
            withMutationTracking {
                repo.setPrivateDns(mode, specifier).onSuccess { dns ->
                    _ui.update { cur -> cur.copy(status = cur.status.copy(privateDns = dns)) }
                    val label = if (dns.mode == "hostname") dns.specifier else dns.mode
                    emitFeedback(strings().toastPrivateDnsSet(label))
                }.onFailure { e ->
                    emitFeedback(strings().toastPrivateDnsError(errDetail(e)), isError = true)
                }
            }
        }
    }

    fun setRadioPrivacy(
        enhancedMacRandomization: Boolean? = null,
        disableWifiBleScanning: Boolean? = null,
        disableCaptivePortal: Boolean? = null,
    ) {
        viewModelScope.launch {
            withMutationTracking {
                repo.setRadioPrivacy(
                    enhancedMacRandomization = enhancedMacRandomization,
                    disableWifiBleScanning = disableWifiBleScanning,
                    disableCaptivePortal = disableCaptivePortal,
                ).onSuccess { rp ->
                    _ui.update { cur -> cur.copy(status = cur.status.copy(radioPrivacy = rp)) }
                    refreshLightStatusSuspend()
                }.onFailure { e ->
                    emitFeedback(strings().toastRadioError(errDetail(e)), isError = true)
                }
            }
        }
    }

    fun triggerPanicLockdown() {
        viewModelScope.launch {
            withMutationTracking {
                configMutex.withLock {
                    repo.clearClipboardNative(getApplication())
                    repo.triggerPanicLockdown().onSuccess { rep ->
                        val cfg = repo.getConfig().getOrDefault(_ui.value.config)
                        _ui.update { it.copy(config = cfg) }
                        refreshLightStatusSuspend()
                        emitFeedback(strings().toastPanicLockdownActive(rep.socketsDestroyed))
                    }.onFailure { e ->
                        emitFeedback(strings().toastPanicLockdownError(errDetail(e)), isError = true)
                    }
                }
            }
        }
    }

    fun triggerPanicReboot() {
        viewModelScope.launch {
            withMutationTracking {
                repo.triggerPanicReboot().onFailure { e ->
                    emitFeedback(strings().toastPanicRebootFailed(errDetail(e)), isError = true)
                }
            }
        }
    }

    fun runSentinelAudit() {
        viewModelScope.launch {
            _ui.update { it.copy(sentinelLoading = true) }
            repo.runSentinelAudit().onSuccess { report ->
                _ui.update { it.copy(sentinelReport = report, sentinelLoading = false) }
                emitFeedback(strings().toastAuditScore(report.score), inSheet = true)
            }.onFailure { e ->
                _ui.update { it.copy(sentinelLoading = false) }
                emitFeedback(strings().toastAuditFailed(errDetail(e)), isError = true, inSheet = true)
            }
        }
    }

    fun runLiveProof(onReady: (() -> Unit)? = null) {
        viewModelScope.launch {
            _ui.update { it.copy(liveProofLoading = true) }
            repo.runLiveProof().onSuccess { proof ->
                _ui.update { it.copy(liveProof = proof, liveProofLoading = false) }
                onReady?.invoke()
            }.onFailure { e ->
                _ui.update { it.copy(liveProofLoading = false) }
                emitFeedback(strings().toastLiveProofFailed(errDetail(e)), isError = true, inSheet = true)
            }
        }
    }

    fun setDisguiseEnabled(enabled: Boolean) {
        repo.setDisguiseEnabled(getApplication(), enabled)
        _ui.update { it.copy(disguiseEnabled = enabled) }
        emitFeedback(strings().toastDisguiseToggled(enabled))
    }

    fun exportConfigToUri(uri: Uri) {
        viewModelScope.launch {
            repo.exportConfigJson().onSuccess { jsonStr ->
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(jsonStr.toByteArray(Charsets.UTF_8))
                            out.flush()
                            true
                        } ?: false
                    }.getOrDefault(false)
                }
                if (ok) {
                    emitFeedback(strings().toastExportSuccess())
                } else {
                    emitFeedback(strings().toastExportWriteFailed(), isError = true)
                }
            }.onFailure { e ->
                emitFeedback(strings().toastExportFailed(errDetail(e)), isError = true)
            }
        }
    }

    fun importConfigFromUri(uri: Uri) {
        viewModelScope.launch {
            when (val readRes = readBoundedUtf8(uri, MAX_CONFIG_IMPORT_BYTES)) {
                BoundedReadResult.TooLarge -> {
                    emitFeedback(strings().toastFileTooLarge(), isError = true)
                    return@launch
                }
                BoundedReadResult.Unreadable -> {
                    emitFeedback(strings().toastImportEmpty(), isError = true)
                    return@launch
                }
                is BoundedReadResult.Success -> {
                    val raw = readRes.text
                    if (raw.isBlank()) {
                        emitFeedback(strings().toastImportEmpty(), isError = true)
                        return@launch
                    }
                    withMutationTracking {
                        configMutex.withLock {
                            repo.importConfigJson(raw).onSuccess { imported ->
                                _ui.update { it.copy(config = imported) }
                                emitFeedback(strings().toastImportSuccess())
                            }.onFailure { e ->
                                emitFeedback(strings().toastImportFailed(errDetail(e)), isError = true)
                                return@withLock
                            }
                        }
                    }
                    refreshAll()
                }
            }
        }
    }

    private suspend fun refreshLightStatusSuspend() {
        repo.getStatus().onSuccess { st ->
            consecutiveStatusFailures.set(0)
            _ui.update { it.copy(status = st, connected = true, connectionError = null) }
        }.onFailure { err ->
            recordStatusFailure(err)
        }
    }

    companion object {
        private const val MAX_CONFIG_IMPORT_BYTES: Int = 2 * 1024 * 1024
    }
}

