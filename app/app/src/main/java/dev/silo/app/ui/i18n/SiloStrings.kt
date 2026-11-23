package dev.silo.app.ui.i18n

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.silo.app.data.AppLanguagePref
import java.util.Locale

@Immutable
interface SiloStrings {
    // Bottom navigation (4 crisp short tabs)
    val tabHome: String
    val tabApps: String
    val tabTraffic: String
    val tabSystem: String

    // Screen titles
    val titleHome: String
    val titleApps: String
    val titleTraffic: String
    val titleSystem: String

    // Connection / Hero
    val daemonOffline: String
    val daemonOfflineDesc: String
    val retryBridge: String
    val securityScore: String
    val firewallMaster: String
    val firewallActiveHint: String
    val firewallOffHint: String
    val bootGuardOk: String
    val bootGuardArmed: String
    val metricBlocked: String
    val metricFrozen: String
    val metricEnforced: String

    // Presets
    val presetsTitle: String
    val presetsSubtitle: String
    val presetSafe: String
    val presetSafeDesc: String
    val presetBalanced: String
    val presetBalancedDesc: String
    val presetHardened: String
    val presetHardenedDesc: String
    val presetLockdown: String
    val presetLockdownDesc: String

    // Quick actions & Hardware switches
    val quickActions: String
    val muteMic: String
    val muteCamera: String
    val stateMuted: String
    val stateLive: String
    val clearClipboard: String
    val liveProof: String
    val panicLockdown: String
    val panicLockdownSub: String
    val panicLockdownTitle: String
    val panicLockdownBody: String
    val confirmLockdown: String
    val panicBfu: String
    val panicDialogTitle: String
    val panicDialogBody: String
    val confirmReboot: String
    val cancel: String
    val close: String
    val apply: String
    val reset: String

    // Apps tab & Global Permission Audit
    val searchApps: String
    val filterUser: String
    val filterRestricted: String
    val filterAudit: String
    val filterSystem: String
    val noAppsFound: String
    val riskHigh: String
    val riskMedium: String
    val riskLow: String
    val trackersFound: String
    val activePermsShort: String
    val tempUnblockActive: String
    val auditBanner: String
    val auditAllCategories: String
    val revokeAllVisible: String
    val revokeOne: String
    val noAppsWithPerm: String

    // Risk explanation card inside App Sheet
    val riskWhyTitle: String
    val riskActivePermsLabel: String
    val riskDeclaredOnlyNote: String
    val riskTrackersLabel: String
    val riskFixPermsHint: String
    val riskFixTrackersHint: String
    val revokeAllSensPerms: String

    // Per-app bottom sheet
    val sheetNetwork: String
    val sheetPerms: String
    val sheetFreezer: String
    val allowWifi: String
    val allowWifiDesc: String
    val allowCellular: String
    val allowCellularDesc: String
    val allowVpn: String
    val allowVpnDesc: String
    val blockScreenOff: String
    val blockScreenOffDesc: String
    val allowLan: String
    val allowLanDesc: String
    val blockHttp: String
    val blockHttpDesc: String
    val blockLocalhost: String
    val blockLocalhostDesc: String
    val markVpnApp: String
    val markVpnAppDesc: String
    val tempUnblockTitle: String
    val tempUnblockDesc: String
    val revokeTempUnblock: String
    val killTcpNow: String
    val resetNetRule: String

    // Honest Real-Time Permission & AppOps Controls
    val permsHonestNote: String
    val permStateGranted: String
    val permStateForeground: String
    val permStateIgnored: String
    val permStateDenied: String
    val permStateRunning: String
    val permActionNormal: String
    val permActionFg: String
    val permActionIgnore: String
    val permActionRevoke: String
    val enforcerAutoRevoke: String
    val enforcerStrictLock: String
    val enforcerHint: String
    val lastAccessPrefix: String

    // App Freezer
    val freezerIntro: String
    val autoFreezeEntry: String
    val autoFreezeEntryDesc: String
    val freezeModeSoft: String
    val freezeModeDeep: String
    val freezeNow: String
    val thawNow: String
    val frozenBadge: String
    val suspendedBadge: String

    // Traffic & Privacy Access tab
    val trafficBlocked: String
    val trafficLive: String
    val trafficAccess: String
    val ramOnlyBanner: String
    val accessTimelineBanner: String
    val clearRing: String
    val noBlockedYet: String
    val noConnections: String
    val noAccessEvents: String
    val killSocket: String
    val blockApp: String
    val runningNowBadge: String

    // System tab sections
    val secPrivateDns: String
    val privateDnsDesc: String
    val dnsOff: String
    val dnsAuto: String
    val dnsCustom: String

    val secRadioPrivacy: String
    val enhancedMacRand: String
    val enhancedMacRandDesc: String
    val disableBgScan: String
    val disableBgScanDesc: String
    val disableCaptivePortal: String
    val disableCaptivePortalDesc: String
    val adbLiveWarning: String

    val secGlobalNet: String
    val fwModeLabel: String
    val fwModeAllow: String
    val fwModeDeny: String
    val blockActionLabel: String
    val actionReject: String
    val actionDrop: String
    val isolateLan: String
    val isolateLanDesc: String
    val dnsLeakGuard: String
    val dnsLeakGuardDesc: String
    val ipv6LeakGuard: String
    val ipv6LeakGuardDesc: String
    val antiPortScan: String
    val antiPortScanDesc: String
    val blockHttpGlobal: String
    val blockHttpGlobalDesc: String
    val normalizeTtl: String
    val normalizeTtlDesc: String
    val blockNewApps: String
    val blockNewAppsDesc: String
    val sockDestroyOnBlock: String
    val sockDestroyOnBlockDesc: String
    val ipBlocklistRow: String
    val ipBlocklistDesc: String
    val importFile: String
    val addCidrHint: String
    val clearList: String

    val secAutomation: String
    val usbAdbLockGuard: String
    val usbAdbLockGuardDesc: String
    val autoRebootBfu: String
    val autoRebootBfuDesc: String
    val autoClearClip: String
    val autoClearClipDesc: String
    val wifiAutoOff: String
    val wifiAutoOffDesc: String
    val btAutoOff: String
    val btAutoOffDesc: String
    val lockSensorsOff: String
    val lockSensorsOffDesc: String
    val freezerMaster: String
    val freezerMasterDesc: String
    val thawAll: String

    val secKernelHardening: String
    val hardeningMaster: String
    val hardeningMasterDesc: String
    val inspectSysctl: String
    val rollbackSysctl: String
    val tcpCongestion: String
    val packetScheduler: String

    val secVerification: String
    val runLiveProofRow: String
    val runLiveProofDesc: String
    val runAuditRow: String
    val runAuditDesc: String
    val grapheneNotesRow: String
    val grapheneNotesDesc: String
    val grapheneNotesBody: String

    val secPreferences: String
    val languageLabel: String
    val themeLabel: String
    val disguiseMode: String
    val disguiseModeDesc: String
    val exportBackup: String
    val importBackup: String
    val emergencyFlush: String
    val emergencyFlushDesc: String

    // v3.0.0 Remediation UI & Dialog Copy
    val bgAppBadge: String
    val offlineOnlyBadge: String
    val noInternetPermHint: String
    val permSystemFixedBadge: String
    val permSystemFixedNote: String
    val loadingAppPermsLabel: String
    val retryAction: String
    val permsLoadFailed: String
    val undeclaredPermsSection: String
    val undeclaredPermHint: String
    val legacyScopeTitle: String
    val legacyScopeDesc: String
    val clearLegacyScope: String
    val firewallOffRuleHint: String
    val dismiss: String
    val refresh: String
    val a11yToggleWifi: String
    val a11yToggleCellular: String
    val a11yToggleFreeze: String
    val loadingAccessLog: String
    val accessLogLoadFailed: String
    val protectedBadge: String
    val presetCustom: String
    val panicLockdownActiveBanner: String
    val liveProofUnavailable: String
    val auditUnavailable: String
    val sysctlUnavailable: String
    val customDnsHint: String
    val blocklistEmpty: String
    val removeEntry: String
    val rollbackConfirmTitle: String
    val rollbackConfirmBody: String
    val confirmRollback: String
    val emergencyFlushConfirmTitle: String
    val emergencyFlushConfirmBody: String
    val confirmFlush: String

    // Quick Settings Tile Subtitles
    val tileOffline: String
    val tileError: String
    val tileDefaultDeny: String
    val tileDefaultAllow: String
    val tilePanicLocked: String
    val tileOff: String
    val tileSensorsMuted: String
    val tileMicMuted: String
    val tileCamMuted: String
    val tileSensorsLive: String
    val tileRebooting: String
    val tileRebootFailed: String
    val tileTapAgain5s: String
    val tileTap2xBfu: String

    val isIndonesian: Boolean
    fun categoryLabel(id: String): String = if (isIndonesian) {
        when (id) {
            "camera" -> "Kamera"
            "microphone" -> "Mikrofon"
            "location" -> "Lokasi"
            "background_location" -> "Lokasi Latar"
            "contacts" -> "Kontak"
            "media" -> "Foto & Media"
            "sms_calllog" -> "SMS & Panggilan"
            "clipboard" -> "Clipboard"
            "sensors" -> "Sensor Tubuh"
            "background" -> "Latar & Overlay"
            else -> id
        }
    } else {
        when (id) {
            "camera" -> "Camera"
            "microphone" -> "Microphone"
            "location" -> "Location"
            "background_location" -> "Bg Location"
            "contacts" -> "Contacts"
            "media" -> "Photos & Media"
            "sms_calllog" -> "SMS & Calls"
            "clipboard" -> "Clipboard"
            "sensors" -> "Body Sensors"
            "background" -> "Background & Overlay"
            else -> id
        }
    }

    fun categoryDesc(id: String): String = if (isIndonesian) {
        when (id) {
            "camera" -> "Akses kamera depan/belakang"
            "microphone" -> "Perekaman suara & mikrofon"
            "location" -> "GPS & lokasi jaringan saat app dibuka"
            "background_location" -> "Pelacakan lokasi terus-menerus di latar belakang"
            "contacts" -> "Daftar kontak (AppOps ignore menampilkan 0 kontak)"
            "media" -> "Galeri MediaStore (tidak menyembunyikan folder /sdcard)"
            "sms_calllog" -> "Membaca SMS OTP & riwayat panggilan"
            "clipboard" -> "Membaca teks yang Anda salin ke clipboard"
            "sensors" -> "Sensor aktivitas fisik & sensor tubuh"
            "background" -> "Jalan di latar, wakelock, overlay layar, & statistik pakai"
            else -> ""
        }
    } else {
        when (id) {
            "camera" -> "Front and rear camera capture"
            "microphone" -> "Audio and microphone recording"
            "location" -> "GPS and network location while open"
            "background_location" -> "Continuous background location tracking"
            "contacts" -> "Contacts list (AppOps ignore returns 0 contacts)"
            "media" -> "MediaStore gallery access (does not hide /sdcard folder names)"
            "sms_calllog" -> "Read OTP SMS messages and call logs"
            "clipboard" -> "Read copied clipboard text silently"
            "sensors" -> "Physical activity and body sensors"
            "background" -> "Background run, wakelocks, overlay, & usage stats"
            else -> ""
        }
    }

    fun actionLabel(action: String): String = when (action.trim().lowercase(Locale.ROOT)) {
        "allow", "default" -> permActionNormal
        "foreground" -> permActionFg
        "ignore" -> permActionIgnore
        "revoke", "deny" -> permActionRevoke
        else -> action
    }

    fun toastPresetApplied(presetName: String) =
        if (isIndonesian) "Preset $presetName diterapkan" else "Preset $presetName applied"

    fun toastPresetFailed(err: String) =
        if (isIndonesian) "Gagal menerapkan preset: $err" else "Failed to apply preset: $err"

    fun toastFirewallError(err: String) =
        if (isIndonesian) "Kesalahan firewall: $err" else "Firewall error: $err"

    fun toastRuleError(err: String) =
        if (isIndonesian) "Kesalahan aturan: $err" else "Rule error: $err"

    fun toastAppRuleReset() =
        if (isIndonesian) "Aturan jaringan dikembalikan ke default" else "Network rule reset to default"

    fun toastResetError(err: String) =
        if (isIndonesian) "Gagal mereset aturan: $err" else "Reset failed: $err"

    fun toastTempUnblockGranted(mins: Int) =
        if (isIndonesian) "Akses internet sementara diberikan (${mins}m)" else "Temporary internet granted (${mins}m)"

    fun toastTempUnblockFailed(err: String) =
        if (isIndonesian) "Gagal memberi akses sementara: $err" else "Temp unblock failed: $err"

    fun toastTempUnblockRevoked() =
        if (isIndonesian) "Akses sementara dicabut & koneksi TCP diputus" else "Temporary access revoked & TCP cut"

    fun toastRevokeFailed(err: String) =
        if (isIndonesian) "Gagal mencabut: $err" else "Revoke failed: $err"

    fun toastFileUnreadable() =
        if (isIndonesian) "File tidak dapat dibaca" else "Unable to read file"

    fun toastFileTooLarge() =
        if (isIndonesian) "File terlalu besar untuk diimpor" else "File is too large to import"

    fun toastNoValidCidr() =
        if (isIndonesian) "Tidak ditemukan IP/CIDR valid di file" else "No valid IP/CIDR entries found in file"

    fun toastBlocklistLoaded(loaded: Int, rejected: Int = 0, truncated: Boolean = false): String {
        val base = if (isIndonesian) "Memuat $loaded IP/CIDR ke kernel" else "Loaded $loaded IP/CIDR entries into kernel"
        val extras = buildList {
            if (rejected > 0) add(if (isIndonesian) "$rejected ditolak" else "$rejected rejected")
            if (truncated) add(if (isIndonesian) "dibatasi kapasitas IPC" else "capped to IPC limit")
        }
        return if (extras.isEmpty()) base else "$base (${extras.joinToString(", ")})"
    }

    fun toastBlocklistImportFailed(err: String) =
        if (isIndonesian) "Gagal impor daftar blokir: $err" else "Blocklist import failed: $err"

    fun toastCidrAdded(cidr: String) =
        if (isIndonesian) "Ditambahkan: $cidr" else "Added: $cidr"

    fun toastCidrAlreadyExists(cidr: String) =
        if (isIndonesian) "Sudah ada di daftar: $cidr" else "Already in blocklist: $cidr"

    fun toastCidrRemoved(cidr: String) =
        if (isIndonesian) "Dihapus: $cidr" else "Removed: $cidr"

    fun toastInvalidCidr(detail: String) =
        if (isIndonesian) "IP/CIDR tidak valid: $detail" else "Invalid IP/CIDR: $detail"

    fun toastBlocklistCleared() =
        if (isIndonesian) "Daftar blokir IP dikosongkan" else "IP blocklist cleared"

    fun toastFlushSuccess() =
        if (isIndonesian) "Flush darurat berhasil: semua aturan SILO_* dicabut"
        else "Emergency flush complete: all SILO_* rules removed"

    fun toastFlushFailed(err: String) =
        if (isIndonesian) "Flush gagal: $err" else "Flush failed: $err"

    fun toastKilledTcp(count: Int) =
        if (isIndonesian) "Memutus $count koneksi TCP aktif" else "Severed $count active TCP connections"

    fun toastSockDestroyError(err: String) =
        if (isIndonesian) "Gagal memutus TCP: $err" else "SOCK_DESTROY error: $err"

    fun toastKilledSocket(count: Int) =
        if (isIndonesian) "Diputus: $count soket" else "Killed: $count socket(s)"

    fun toastKillSocketFailed(err: String) =
        if (isIndonesian) "Gagal memutus soket: $err" else "Failed to kill socket: $err"

    fun toastAppBlocked(label: String) =
        if (isIndonesian) "Internet diblokir untuk $label" else "Blocked internet for $label"

    fun toastTelemetryCleared() =
        if (isIndonesian) "Log blokir RAM dibersihkan" else "RAM block log cleared"

    fun toastTelemetryError(err: String) =
        if (isIndonesian) "Gagal menghapus log: $err" else "Failed to clear logs: $err"

    fun toastFreezerError(err: String) =
        if (isIndonesian) "Kesalahan pembeku: $err" else "Freezer error: $err"

    fun toastAppFrozen() =
        if (isIndonesian) "Aplikasi dibekukan" else "App frozen"

    fun toastFreezeFailed(err: String) =
        if (isIndonesian) "Gagal membekukan: $err" else "Failed to freeze: $err"

    fun toastAppThawed() =
        if (isIndonesian) "Aplikasi dicairkan" else "App thawed"

    fun toastThawFailed(err: String) =
        if (isIndonesian) "Gagal mencairkan: $err" else "Failed to thaw: $err"

    fun toastThawAllDone() =
        if (isIndonesian) "Semua aplikasi dicairkan" else "All apps thawed"

    fun toastPermActionApplied(cat: String, action: String) =
        if (isIndonesian) "Izin ${categoryLabel(cat)} diatur ke ${actionLabel(action)}"
        else "${categoryLabel(cat)} set to ${actionLabel(action)}"

    fun toastAccessRevoked(cat: String, appLabel: String) =
        if (isIndonesian) "Izin ${categoryLabel(cat)} pada $appLabel dicabut"
        else "Revoked ${categoryLabel(cat)} for $appLabel"

    fun toastPermError(err: String) =
        if (isIndonesian) "Kesalahan izin: $err" else "Permission error: $err"

    fun toastGuardUpdated() =
        if (isIndonesian) "Penjaga izin diperbarui" else "Permission guard updated"

    fun toastGuardError(err: String) =
        if (isIndonesian) "Kesalahan penjaga izin: $err" else "Guard error: $err"

    fun toastRevokedAllForApp(count: Int, appLabel: String) =
        if (isIndonesian) "Mencabut $count kategori izin sensitif pada $appLabel"
        else "Revoked $count sensitive permission categories for $appLabel"

    fun toastBulkRevoked(cat: String, count: Int) =
        if (isIndonesian) "Berhasil mencabut izin ${categoryLabel(cat)} pada $count aplikasi"
        else "Revoked ${categoryLabel(cat)} permission on $count apps"

    fun toastBulkRevokeFailed(err: String) =
        if (isIndonesian) "Cabut massal gagal: $err" else "Bulk revoke failed: $err"

    fun toastLegacyScopeCleared() =
        if (isIndonesian) "Aturan isolasi lama dibersihkan" else "Legacy isolation rule cleared"

    fun toastClipboardCleared() =
        if (isIndonesian) "Clipboard dibersihkan" else "Clipboard cleared"

    fun toastClipboardClearFailed(err: String) =
        if (isIndonesian) "Gagal membersihkan clipboard: $err" else "Failed to clear clipboard: $err"

    fun toastClipboardGuardError(err: String) =
        if (isIndonesian) "Gagal mengatur hapus clipboard otomatis: $err"
        else "Failed to update clipboard guard: $err"

    fun toastHardeningError(err: String) =
        if (isIndonesian) "Kesalahan pengerasan kernel: $err" else "Hardening error: $err"

    fun toastHardeningRolledBack() =
        if (isIndonesian) "Seluruh sysctl dikembalikan ke default kernel" else "All sysctls restored to kernel defaults"

    fun toastAutomationError(err: String) =
        if (isIndonesian) "Kesalahan otomasi: $err" else "Automation error: $err"

    fun toastSensorError(err: String) =
        if (isIndonesian) "Kesalahan privasi sensor: $err" else "Sensor privacy error: $err"

    fun toastPrivateDnsSet(label: String) = "Private DNS: $label"

    fun toastPrivateDnsError(err: String) =
        if (isIndonesian) "Kesalahan Private DNS: $err" else "Private DNS error: $err"

    fun toastRadioError(err: String) =
        if (isIndonesian) "Kesalahan privasi radio: $err" else "Radio privacy error: $err"

    fun toastPanicLockdownActive(killed: Int) =
        if (isIndonesian) "Panic Lockdown aktif: $killed TCP diputus, semua aplikasi diblokir, Mic/Cam & Radio dimatikan"
        else "Panic Lockdown active: $killed TCP cut, all apps blocked, Mic/Cam & Radios off"

    fun toastPanicLockdownError(err: String) =
        if (isIndonesian) "Kesalahan Panic Lockdown: $err" else "Panic Lockdown error: $err"

    fun toastPanicRebootFailed(err: String) =
        if (isIndonesian) "Panic reboot gagal: $err" else "Panic reboot failed: $err"

    fun toastAuditScore(score: Int) =
        if (isIndonesian) "Skor Keamanan: $score/100" else "Security Score: $score/100"

    fun toastAuditFailed(err: String) =
        if (isIndonesian) "Audit gagal: $err" else "Audit failed: $err"

    fun toastLiveProofFailed(err: String) =
        if (isIndonesian) "Uji Bukti Nyata gagal: $err" else "Live proof failed: $err"

    fun toastDisguiseToggled(enabled: Boolean) = when {
        enabled && isIndonesian -> "Samaran NetDiag aktif"
        enabled -> "NetDiag disguise enabled"
        isIndonesian -> "Ikon Silo dikembalikan"
        else -> "Silo icon restored"
    }

    fun toastExportSuccess() =
        if (isIndonesian) "Konfigurasi diekspor" else "Configuration exported"

    fun toastExportWriteFailed() =
        if (isIndonesian) "Gagal menulis file cadangan" else "Failed to write backup file"

    fun toastExportFailed(err: String) =
        if (isIndonesian) "Ekspor gagal: $err" else "Export failed: $err"

    fun toastImportEmpty() =
        if (isIndonesian) "File cadangan kosong atau tidak terbaca" else "Backup file is empty or unreadable"

    fun toastImportSuccess() =
        if (isIndonesian) "Konfigurasi dipulihkan" else "Configuration restored"

    fun toastImportFailed(err: String) =
        if (isIndonesian) "Impor gagal: $err" else "Import failed: $err"
}

object StringsId : SiloStrings {
    override val tabHome = "Beranda"
    override val tabApps = "Aplikasi"
    override val tabTraffic = "Monitor"
    override val tabSystem = "Sistem"

    override val titleHome = "Silo"
    override val titleApps = "Kontrol Aplikasi"
    override val titleTraffic = "Monitor Real-Time"
    override val titleSystem = "Sistem & Keamanan"

    override val daemonOffline = "Daemon Root Offline"
    override val daemonOfflineDesc = "Pasang silo-ksu-module-v3.0.0.zip di KernelSU-Next, reboot, lalu izinkan akses root untuk Silo."
    override val retryBridge = "Hubungkan Ulang"
    override val securityScore = "Skor"
    override val firewallMaster = "Firewall Kernel"
    override val firewallActiveHint = "Aktif di Netfilter kernel • Tanpa slot VPN"
    override val firewallOffHint = "Nonaktif • Mode aman awal"
    override val bootGuardOk = "Boot Guard: Stabil"
    override val bootGuardArmed = "Boot Guard: Verifikasi 45d"
    override val metricBlocked = "Diblokir"
    override val metricFrozen = "Beku"
    override val metricEnforced = "Penjaga Izin"

    override val presetsTitle = "Preset Cepat"
    override val presetsSubtitle = "Atur proteksi dalam 1 ketukan. Auto-Reboot BFU tetap manual agar aman."
    override val presetSafe = "Aman"
    override val presetSafeDesc = "Matikan semua blokir"
    override val presetBalanced = "Seimbang"
    override val presetBalancedDesc = "Firewall + Anti-LAN/DNS/Scan + Hardening"
    override val presetHardened = "Ketat"
    override val presetHardenedDesc = "+ Blokir HTTP, TTL 64, App baru diblokir"
    override val presetLockdown = "Lockdown"
    override val presetLockdownDesc = "Blokir default + USB/ADB guard"

    override val quickActions = "Kontrol Perangkat Live"
    override val muteMic = "Mikrofon"
    override val muteCamera = "Kamera"
    override val stateMuted = "DIMATIKAN"
    override val stateLive = "Aktif (Live)"
    override val clearClipboard = "Hapus Clipboard"
    override val liveProof = "Uji Nyata"
    override val panicLockdown = "Panic Lockdown"
    override val panicLockdownSub = "Putus Net+Mic+Cam"
    override val panicLockdownTitle = "Aktifkan Panic Lockdown?"
    override val panicLockdownBody = "Aksi non-destruktif (tidak menghapus data): mengaktifkan Firewall Blokir Default, memutus semua koneksi TCP aktif, mematikan Mikrofon, Kamera, Wi-Fi, Bluetooth, dan menghapus Clipboard."
    override val confirmLockdown = "Kunci Sekarang"
    override val panicBfu = "Reboot BFU"
    override val panicDialogTitle = "Reboot Darurat ke BFU?"
    override val panicDialogBody = "HP akan reboot bersih ke mode terkunci penuh (BFU) sehingga seluruh kunci enkripsi dihapus dari RAM."
    override val confirmReboot = "Reboot Sekarang"
    override val cancel = "Batal"
    override val close = "Tutup"
    override val apply = "Terapkan"
    override val reset = "Reset"

    override val searchApps = "Cari aplikasi..."
    override val filterUser = "Aplikasi"
    override val filterRestricted = "Terbatas"
    override val filterAudit = "Audit Izin"
    override val filterSystem = "Sistem"
    override val noAppsFound = "Tidak ada aplikasi yang cocok."
    override val riskHigh = "Risiko Tinggi"
    override val riskMedium = "Risiko Sedang"
    override val riskLow = "Risiko Rendah"
    override val trackersFound = "tracker"
    override val activePermsShort = "izin aktif"
    override val tempUnblockActive = "Akses sementara"
    override val auditBanner = "Menampilkan aplikasi yang SEDANG AKTIF diberi izin sensitif (real-time dari sistem):"
    override val auditAllCategories = "Semua"
    override val revokeAllVisible = "Cabut Semua"
    override val revokeOne = "Cabut"
    override val noAppsWithPerm = "Tidak ada aplikasi aktif yang memegang izin ini."

    override val riskWhyTitle = "Analisis Risiko & Solusi"
    override val riskActivePermsLabel = "Izin sensitif aktif saat ini"
    override val riskDeclaredOnlyNote = "Tidak ada izin sensitif yang sedang aktif (izin lain hanya tercantum di APK)."
    override val riskTrackersLabel = "SDK tracker tertanam di APK"
    override val riskFixPermsHint = "Bisa diatasi: cabut izin aktif langsung lewat tombol di bawah atau tab Izin & AppOps."
    override val riskFixTrackersHint = "SDK tracker melekat di APK (tak bisa dihapus), namun komunikasinya diblokir lewat Firewall + Private DNS."
    override val revokeAllSensPerms = "Cabut Semua Izin Sensitif Aktif"

    override val sheetNetwork = "Jaringan"
    override val sheetPerms = "Izin & AppOps"
    override val sheetFreezer = "Pembeku"
    override val allowWifi = "Izinkan Wi-Fi"
    override val allowWifiDesc = "Akses internet lewat jaringan Wi-Fi"
    override val allowCellular = "Izinkan Seluler"
    override val allowCellularDesc = "Akses internet lewat data seluler (ccmni+/rmnet+)"
    override val allowVpn = "Izinkan VPN"
    override val allowVpnDesc = "Akses internet saat terowongan VPN aktif"
    override val blockScreenOff = "Blokir saat Layar Mati"
    override val blockScreenOffDesc = "Putus internet aplikasi ini otomatis saat layar mati / HP tidur"
    override val allowLan = "Izinkan Jaringan Lokal (LAN)"
    override val allowLanDesc = "Pengecualian untuk Chromecast, LocalSend, atau printer lokal"
    override val blockHttp = "Blokir HTTP Polos (Port 80)"
    override val blockHttpDesc = "Cegah koneksi tak terenkripsi agar hanya HTTPS yang lewat"
    override val blockLocalhost = "Blokir Localhost (127.0.0.1)"
    override val blockLocalhostDesc = "Cegah aplikasi memindai port lokal milik aplikasi lain"
    override val markVpnApp = "Tandai sebagai Aplikasi VPN"
    override val markVpnAppDesc = "Bebaskan dari DNS guard agar WireGuard/VPN tidak macet"
    override val tempUnblockTitle = "Izin Internet Sementara"
    override val tempUnblockDesc = "Buka akses sementara lalu otomatis dikunci & diputus saat habis"
    override val revokeTempUnblock = "Cabut Izin Sementara"
    override val killTcpNow = "Putus Koneksi TCP Sekarang"
    override val resetNetRule = "Kembalikan ke Default"

    override val permsHonestNote = "Status dibaca real-time dari sistem & AppOps. Catatan jujur: kontrol Media hanya memblokir galeri/MediaStore — tidak menyembunyikan nama folder /sdcard (itu tugas SUSFS)."
    override val permStateGranted = "AKTIF"
    override val permStateForeground = "SAAT DIBUKA"
    override val permStateIgnored = "PALSU KOSONG"
    override val permStateDenied = "DICABUT"
    override val permStateRunning = "SEDANG DIPAKAI"
    override val permActionNormal = "Normal"
    override val permActionFg = "Saat Dibuka"
    override val permActionIgnore = "Palsukan"
    override val permActionRevoke = "Cabut"
    override val enforcerAutoRevoke = "Auto-Cabut"
    override val enforcerStrictLock = "Kunci Blokir"
    override val enforcerHint = "Auto-Cabut: izin dicabut otomatis saat app ditutup/layar mati. Kunci Blokir: kunci izin (user-fixed) & blokir ulang cepat (<2d). Catatan Android: mencabut izin runtime saat app berjalan akan memaksa OS me-restart app — gunakan Palsukan (AppOps) untuk blokir senyap tanpa menutup app."
    override val lastAccessPrefix = "Akses terakhir"

    override val freezerIntro = "Bekukan aplikasi agar 0% CPU, 0% baterai, dan nol telemetri latar belakang."
    override val autoFreezeEntry = "Masukkan Daftar Auto-Freeze"
    override val autoFreezeEntryDesc = "Otomatis membeku saat pindah ke latar belakang atau layar mati"
    override val freezeModeSoft = "Soft (cgroup v2)"
    override val freezeModeDeep = "Deep (pm suspend)"
    override val freezeNow = "Bekukan Sekarang"
    override val thawNow = "Cairkan Sekarang"
    override val frozenBadge = "BEKU"
    override val suspendedBadge = "SUSPEND"

    override val trafficBlocked = "Diblokir"
    override val trafficLive = "Koneksi"
    override val trafficAccess = "Akses Izin"
    override val ramOnlyBanner = "Log jaringan hanya di RAM (0 byte ke disk) — lenyap saat reboot"
    override val accessTimelineBanner = "Timeline nyata dumpsys appops: siapa yang baru mengakses Mic, Kamera, Lokasi, Clipboard, Kontak"
    override val clearRing = "Bersihkan"
    override val noBlockedYet = "Belum ada paket diblokir yang tercatat di RAM."
    override val noConnections = "Tidak ada koneksi aktif saat ini."
    override val noAccessEvents = "Belum ada akses izin sensitif yang tercatat baru-baru ini."
    override val killSocket = "Putus"
    override val blockApp = "Blokir"
    override val runningNowBadge = "AKTIF SEKARANG"

    override val secPrivateDns = "Private DNS (DoT) 1-Klik"
    override val privateDnsDesc = "Enkripsi seluruh query DNS sistem langsung via TLS"
    override val dnsOff = "Mati"
    override val dnsAuto = "Otomatis"
    override val dnsCustom = "Kustom"

    override val secRadioPrivacy = "Privasi Jaringan & Radio (ala GrapheneOS)"
    override val enhancedMacRand = "Randomisasi MAC Wi-Fi Diperketat"
    override val enhancedMacRandDesc = "Acak alamat MAC Wi-Fi untuk mencegah pelacakan perangkat"
    override val disableBgScan = "Matikan Pindai Wi-Fi & BLE Latar"
    override val disableBgScanDesc = "Cegah sistem memindai lokasi lewat Wi-Fi/Bluetooth saat radio mati"
    override val disableCaptivePortal = "Matikan Ping Captive Portal Google"
    override val disableCaptivePortalDesc = "Cegah Android menghubungi connectivitycheck.gstatic.com tiap konek Wi-Fi"
    override val adbLiveWarning = "Status ADB saat ini: AKTIF"

    override val secGlobalNet = "Proteksi Jaringan Global"
    override val fwModeLabel = "Mode Kebijakan Firewall"
    override val fwModeAllow = "Izinkan Default"
    override val fwModeDeny = "Blokir Default"
    override val blockActionLabel = "Respons Blokir"
    override val actionReject = "Tolak Cepat"
    override val actionDrop = "Jatuhkan Diam"
    override val isolateLan = "Isolasi Jaringan Lokal (LAN)"
    override val isolateLanDesc = "Cegah aplikasi memindai perangkat Wi-Fi rumah (RFC1918/mDNS)"
    override val dnsLeakGuard = "Anti-Kebocoran DNS (Port 53)"
    override val dnsLeakGuardDesc = "Blokir bypass DNS polos; netd Android & VPN tetap normal"
    override val ipv6LeakGuard = "Anti-Kebocoran IPv6 saat VPN"
    override val ipv6LeakGuardDesc = "Jatuhkan trafik IPv6 di luar terowongan saat VPN aktif"
    override val antiPortScan = "Anti-Port-Scan Wi-Fi Publik (xt_recent)"
    override val antiPortScanDesc = "Jatuhkan otomatis IP asing yang memindai port masuk ke HP Anda"
    override val blockHttpGlobal = "Blokir HTTP Polos Global (Port 80)"
    override val blockHttpGlobalDesc = "Paksa semua aplikasi memakai koneksi terenkripsi HTTPS"
    override val normalizeTtl = "Normalisasi TTL / Hop Limit (64)"
    override val normalizeTtlDesc = "Samarkan sidik jari TTL jaringan terhadap deteksi operator"
    override val blockNewApps = "Otomatis Blokir Aplikasi Baru"
    override val blockNewAppsDesc = "Kunci internet setiap aplikasi baru hingga Anda mengizinkannya"
    override val sockDestroyOnBlock = "Putus TCP Aktif saat Memblokir"
    override val sockDestroyOnBlockDesc = "Gunakan SOCK_DESTROY kernel untuk memutus koneksi terbuka <1ms"
    override val ipBlocklistRow = "Daftar Blokir IP / CIDR Kernel"
    override val ipBlocklistDesc = "Impor file daftar IP tracker/malware lokal (100% offline)"
    override val importFile = "Impor File"
    override val addCidrHint = "Tambah IP/CIDR (mis. 203.0.113.0/24)"
    override val clearList = "Kosongkan"

    override val secAutomation = "Otomasi Privasi (ala GrapheneOS)"
    override val usbAdbLockGuard = "Kunci USB & ADB saat Layar Terkunci"
    override val usbAdbLockGuardDesc = "Otomatis matikan ADB & set USB charge-only saat terkunci (tetap mati saat unlock; aman untuk Download Mode)"
    override val autoRebootBfu = "Auto-Reboot ke BFU saat Terkunci"
    override val autoRebootBfuDesc = "Reboot otomatis ke mode terenkripsi penuh jika HP lama terkunci"
    override val autoClearClip = "Hapus Clipboard Otomatis"
    override val autoClearClipDesc = "Bersihkan teks tersalin otomatis setelah batas waktu"
    override val wifiAutoOff = "Matikan Wi-Fi saat Idle"
    override val wifiAutoOffDesc = "Matikan radio Wi-Fi otomatis jika lama tidak tersambung"
    override val btAutoOff = "Matikan Bluetooth saat Idle"
    override val btAutoOffDesc = "Matikan radio Bluetooth otomatis jika tidak ada perangkat terhubung"
    override val lockSensorsOff = "Matikan Sensor saat Layar Terkunci"
    override val lockSensorsOffDesc = "Bisukan kamera & mikrofon otomatis ketika layar dikunci"
    override val freezerMaster = "Mesin Auto-Freeze (cgroup v2)"
    override val freezerMasterDesc = "Bekukan otomatis aplikasi yang ada di daftar pembeku"
    override val thawAll = "Cairkan Semua"

    override val secKernelHardening = "Pengerasan Kernel (sysctl)"
    override val hardeningMaster = "Aktifkan Pengerasan Kernel"
    override val hardeningMasterDesc = "Tutup unpriv-eBPF, JIT harden, ASLR/Yama ptrace, anti-redirect/RFC1337, TCP/IPv6 privacy"
    override val inspectSysctl = "Lihat Nilai Kernel Aktual"
    override val rollbackSysctl = "Kembalikan ke Default Kernel"
    override val tcpCongestion = "Kontrol Kongesti TCP"
    override val packetScheduler = "Penjadwal Paket (qdisc)"

    override val secVerification = "Verifikasi & Transparansi"
    override val runLiveProofRow = "Uji Penegakan Nyata (Live Proof)"
    override val runLiveProofDesc = "Buktikan langsung rule iptables, cgroup frozen, appops, sensor & sysctl di kernel"
    override val runAuditRow = "Audit Integritas & Stealth Sentinel"
    override val runAuditDesc = "Periksa SELinux, CA MitM, port liar, proses root, & kebersihan stealth"
    override val grapheneNotesRow = "Batasan Hardware & ROM (Tanpa Gimik)"
    override val grapheneNotesDesc = "Mengapa fitur tertentu GrapheneOS sengaja tidak dipalsukan di SM-A146P"
    override val grapheneNotesBody = "• Hardware MTE & PAC/BTI: CPU Dimensity 700 (ARMv8.2-A) tidak memiliki ekstensi ARMv8.5+ MTE.\n\n• hardened_malloc, Blokir DCL / Exec-Spawning: butuh kompilasi ulang Bionic libc & Zygote pada source ROM AOSP.\n\n• Storage Scopes sejati (folder /sdcard kosong per app): butuh patch OS MediaProvider FUSE; bind-mount per-app merusak stealth (mountinfo). Penyembunyian folder sensitif ditangani SUSFS di kernel.\n\n• Sandboxed Google Play, Locked AVB + Titan M2, & PIN Scrambling: terbatas oleh stock ROM One UI 7 & bootloader unlocked."

    override val secPreferences = "Tampilan, Bahasa & Cadangan"
    override val languageLabel = "Bahasa"
    override val themeLabel = "Tema Tampilan"
    override val disguiseMode = "Mode Samaran (NetDiag)"
    override val disguiseModeDesc = "Ganti ikon & nama launcher agar lebih tersamar"
    override val exportBackup = "Ekspor Konfigurasi"
    override val importBackup = "Impor Konfigurasi"
    override val emergencyFlush = "Flush Darurat Firewall"
    override val emergencyFlushDesc = "Cabut semua aturan SILO_* di kernel tanpa menyentuh sistem Android"

    override val bgAppBadge = "BG"
    override val offlineOnlyBadge = "OFFLINE"
    override val noInternetPermHint = "Aplikasi ini tidak memiliki izin INTERNET di manifest APK."
    override val permSystemFixedBadge = "SISTEM TETAP"
    override val permSystemFixedNote = "Dikunci OS (SYSTEM_FIXED) — Android melarang pm revoke; gunakan Palsukan (AppOps)."
    override val loadingAppPermsLabel = "Memuat status izin real-time..."
    override val retryAction = "Coba Lagi"
    override val permsLoadFailed = "Gagal membaca status izin dari daemon"
    override val undeclaredPermsSection = "Izin Lain (Tidak Dideklarasikan)"
    override val undeclaredPermHint = "Tidak tercantum di manifest APK"
    override val legacyScopeTitle = "Aturan Isolasi Lama (v2) Aktif"
    override val legacyScopeDesc = "Aplikasi ini masih memiliki aturan scope v2 lama. Bersihkan agar dikelola penuh lewat Izin & AppOps v3."
    override val clearLegacyScope = "Bersihkan Aturan Lama"
    override val firewallOffRuleHint = "Firewall utama nonaktif — aturan disimpan & aktif saat Firewall dinyalakan."
    override val dismiss = "Tutup"
    override val refresh = "Segarkan"
    override val a11yToggleWifi = "Ubah akses Wi-Fi"
    override val a11yToggleCellular = "Ubah akses seluler"
    override val a11yToggleFreeze = "Bekukan atau cairkan aplikasi"
    override val loadingAccessLog = "Memindai log akses izin..."
    override val accessLogLoadFailed = "Gagal memuat log akses izin"
    override val protectedBadge = "TERLINDUNGI"
    override val presetCustom = "Kustom"
    override val panicLockdownActiveBanner = "PANIC LOCKDOWN AKTIF — Semua koneksi aplikasi diblokir total"
    override val liveProofUnavailable = "Gagal memuat uji penegakan. Pastikan daemon aktif lalu coba lagi."
    override val auditUnavailable = "Hasil audit belum tersedia. Ketuk coba lagi untuk memindai."
    override val sysctlUnavailable = "Nilai sysctl belum tersedia."
    override val customDnsHint = "Hostname DoT (mis. dns.quad9.net)"
    override val blocklistEmpty = "Belum ada IP/CIDR di daftar blokir."
    override val removeEntry = "Hapus entri"
    override val rollbackConfirmTitle = "Kembalikan Sysctl ke Default?"
    override val rollbackConfirmBody = "Seluruh parameter pengerasan kernel (sysctl) akan dikembalikan ke nilai bawaan boot dan Pengerasan Kernel dinonaktifkan."
    override val confirmRollback = "Kembalikan"
    override val emergencyFlushConfirmTitle = "Flush Darurat Firewall?"
    override val emergencyFlushConfirmBody = "Seluruh aturan iptables/ip6tables SILO_* dan ipset akan dicabut segera, sehingga semua aplikasi dapat mengakses internet tanpa filter firewall."
    override val confirmFlush = "Flush Sekarang"

    override val tileOffline = "Offline"
    override val tileError = "Gagal"
    override val tileDefaultDeny = "Blokir Default"
    override val tileDefaultAllow = "Izinkan Default"
    override val tilePanicLocked = "Panic Lockdown"
    override val tileOff = "Mati"
    override val tileSensorsMuted = "Mic/Cam Mati"
    override val tileMicMuted = "Mic Mati"
    override val tileCamMuted = "Kamera Mati"
    override val tileSensorsLive = "Aktif"
    override val tileRebooting = "Memulai ulang..."
    override val tileRebootFailed = "Gagal reboot"
    override val tileTapAgain5s = "Ketuk lagi (5d)"
    override val tileTap2xBfu = "Ketuk 2x -> BFU"

    override val isIndonesian = true
}

object StringsEn : SiloStrings {
    override val tabHome = "Home"
    override val tabApps = "Apps"
    override val tabTraffic = "Monitor"
    override val tabSystem = "System"

    override val titleHome = "Silo"
    override val titleApps = "App Controls"
    override val titleTraffic = "Real-Time Monitor"
    override val titleSystem = "System & Security"

    override val daemonOffline = "Root Daemon Offline"
    override val daemonOfflineDesc = "Install silo-ksu-module-v3.0.0.zip in KernelSU-Next, reboot, and grant root access to Silo."
    override val retryBridge = "Reconnect Bridge"
    override val securityScore = "Score"
    override val firewallMaster = "Kernel Firewall"
    override val firewallActiveHint = "Enforcing in Linux Netfilter • Zero VPN slot used"
    override val firewallOffHint = "Disabled • Safe-First initial mode"
    override val bootGuardOk = "Boot Guard: Stable"
    override val bootGuardArmed = "Boot Guard: Verifying 45s"
    override val metricBlocked = "Blocked"
    override val metricFrozen = "Frozen"
    override val metricEnforced = "Perm Guard"

    override val presetsTitle = "Quick Presets"
    override val presetsSubtitle = "One-tap security profiles. Auto-Reboot BFU always stays manual opt-in."
    override val presetSafe = "Safe"
    override val presetSafeDesc = "Turn off all blocks"
    override val presetBalanced = "Balanced"
    override val presetBalancedDesc = "Firewall + LAN/DNS/Scan Guard + Hardening"
    override val presetHardened = "Hardened"
    override val presetHardenedDesc = "+ No HTTP, TTL 64, Auto-block new apps"
    override val presetLockdown = "Lockdown"
    override val presetLockdownDesc = "Default-deny + USB/ADB lock guard"

    override val quickActions = "Live Hardware Controls"
    override val muteMic = "Microphone"
    override val muteCamera = "Camera"
    override val stateMuted = "MUTED"
    override val stateLive = "Active (Live)"
    override val clearClipboard = "Wipe Clipboard"
    override val liveProof = "Live Proof"
    override val panicLockdown = "Panic Lockdown"
    override val panicLockdownSub = "Cut Net+Mic+Cam"
    override val panicLockdownTitle = "Trigger Panic Lockdown?"
    override val panicLockdownBody = "Non-destructive (keeps all your data): switches Firewall to Default-Deny, severs all active TCP sockets, mutes Mic & Camera, disables Wi-Fi & Bluetooth, and wipes Clipboard."
    override val confirmLockdown = "Lock Down Now"
    override val panicBfu = "Panic BFU"
    override val panicDialogTitle = "Emergency Reboot to BFU?"
    override val panicDialogBody = "Your device will cleanly reboot into Before-First-Unlock (BFU) state, purging encryption keys from RAM immediately."
    override val confirmReboot = "Reboot Now"
    override val cancel = "Cancel"
    override val close = "Close"
    override val apply = "Apply"
    override val reset = "Reset"

    override val searchApps = "Search apps..."
    override val filterUser = "Apps"
    override val filterRestricted = "Restricted"
    override val filterAudit = "Perm Audit"
    override val filterSystem = "System"
    override val noAppsFound = "No matching apps found."
    override val riskHigh = "High Risk"
    override val riskMedium = "Medium Risk"
    override val riskLow = "Low Risk"
    override val trackersFound = "tracker(s)"
    override val activePermsShort = "active perms"
    override val tempUnblockActive = "Temp access"
    override val auditBanner = "Showing apps CURRENTLY GRANTED sensitive permissions (live from Android):"
    override val auditAllCategories = "All"
    override val revokeAllVisible = "Revoke All"
    override val revokeOne = "Revoke"
    override val noAppsWithPerm = "No apps currently hold this permission."

    override val riskWhyTitle = "Risk Breakdown & Mitigation"
    override val riskActivePermsLabel = "Currently granted sensitive permissions"
    override val riskDeclaredOnlyNote = "No sensitive permissions are currently granted (others are only declared in the APK)."
    override val riskTrackersLabel = "Embedded tracker SDKs in APK"
    override val riskFixPermsHint = "Fixable now: revoke active permissions below or inside the Permissions tab."
    override val riskFixTrackersHint = "Tracker SDK code is compiled into the APK, but its network calls are blocked via Firewall + Private DNS."
    override val revokeAllSensPerms = "Revoke All Active Sensitive Permissions"

    override val sheetNetwork = "Network"
    override val sheetPerms = "Permissions"
    override val sheetFreezer = "Freezer"
    override val allowWifi = "Allow Wi-Fi"
    override val allowWifiDesc = "Permit internet traffic over Wi-Fi networks"
    override val allowCellular = "Allow Cellular"
    override val allowCellularDesc = "Permit mobile data traffic (ccmni+/rmnet+)"
    override val allowVpn = "Allow VPN"
    override val allowVpnDesc = "Permit traffic while a VPN tunnel is active"
    override val blockScreenOff = "Block While Screen Off"
    override val blockScreenOffDesc = "Automatically cut internet for this app whenever the screen turns off"
    override val allowLan = "Allow Local Network (LAN)"
    override val allowLanDesc = "Exemption for Chromecast, LocalSend, or local printers"
    override val blockHttp = "Block Cleartext HTTP (Port 80)"
    override val blockHttpDesc = "Prevent unencrypted HTTP so only HTTPS passes"
    override val blockLocalhost = "Block Localhost (127.0.0.1)"
    override val blockLocalhostDesc = "Prevent probing local ports opened by other apps"
    override val markVpnApp = "Mark as VPN Provider"
    override val markVpnAppDesc = "Exempt from DNS leak guard so VPN tunnels stay healthy"
    override val tempUnblockTitle = "Temporary Internet Grant"
    override val tempUnblockDesc = "Temporarily allow access, then auto-lock & cut TCP on expiry"
    override val revokeTempUnblock = "Revoke Temporary Grant"
    override val killTcpNow = "Cut Active TCP Sockets"
    override val resetNetRule = "Reset to Default"

    override val permsHonestNote = "Read live from Android PackageManager & AppOps. Honest note: Media restricts MediaStore/gallery access — it does not hide /sdcard folder names (handled by SUSFS)."
    override val permStateGranted = "GRANTED"
    override val permStateForeground = "FOREGROUND"
    override val permStateIgnored = "FAKE EMPTY"
    override val permStateDenied = "REVOKED"
    override val permStateRunning = "IN USE NOW"
    override val permActionNormal = "Normal"
    override val permActionFg = "While Open"
    override val permActionIgnore = "Fake Empty"
    override val permActionRevoke = "Revoke"
    override val enforcerAutoRevoke = "Auto-Revoke"
    override val enforcerStrictLock = "Lock Blocked"
    override val enforcerHint = "Auto-Revoke: revokes automatically when backgrounded/screen off. Lock Blocked: locks permission (user-fixed) & re-enforces fast (<2s). Android note: revoking a runtime permission while an app is running causes Android to restart its process — use Fake Empty (AppOps) to block silently without closing the app."
    override val lastAccessPrefix = "Last access"

    override val freezerIntro = "Suspend background apps for 0% CPU, 0% wakelocks, and zero silent telemetry."
    override val autoFreezeEntry = "Include in Auto-Freeze List"
    override val autoFreezeEntryDesc = "Automatically freeze when backgrounded or screen locks"
    override val freezeModeSoft = "Soft (cgroup v2)"
    override val freezeModeDeep = "Deep (pm suspend)"
    override val freezeNow = "Freeze Now"
    override val thawNow = "Thaw Now"
    override val frozenBadge = "FROZEN"
    override val suspendedBadge = "SUSPENDED"

    override val trafficBlocked = "Blocked"
    override val trafficLive = "Sockets"
    override val trafficAccess = "Perm Access"
    override val ramOnlyBanner = "Network logs live strictly in RAM (0 bytes to disk) — cleared on reboot"
    override val accessTimelineBanner = "Live dumpsys appops timeline: recent Mic, Camera, Location, Clipboard & Contacts access"
    override val clearRing = "Clear"
    override val noBlockedYet = "No blocked packets captured in RAM yet."
    override val noConnections = "No active sockets right now."
    override val noAccessEvents = "No recent sensitive permission accesses recorded."
    override val killSocket = "Kill"
    override val blockApp = "Block"
    override val runningNowBadge = "ACTIVE NOW"

    override val secPrivateDns = "Private DNS (DoT) 1-Tap"
    override val privateDnsDesc = "Encrypt all system DNS queries natively over TLS"
    override val dnsOff = "Off"
    override val dnsAuto = "Auto"
    override val dnsCustom = "Custom"

    override val secRadioPrivacy = "Network & Radio Privacy (GrapheneOS-Style)"
    override val enhancedMacRand = "Enhanced Wi-Fi MAC Randomization"
    override val enhancedMacRandDesc = "Randomize Wi-Fi MAC addresses to prevent cross-network tracking"
    override val disableBgScan = "Disable Background Wi-Fi & BLE Scanning"
    override val disableBgScanDesc = "Stop Android from scanning nearby Wi-Fi/BLE even when radios are off"
    override val disableCaptivePortal = "Disable Google Captive Portal Pings"
    override val disableCaptivePortalDesc = "Stop connectivitycheck.gstatic.com pings on every Wi-Fi connect"
    override val adbLiveWarning = "Live ADB state: ENABLED"

    override val secGlobalNet = "Global Network Guards"
    override val fwModeLabel = "Firewall Policy Mode"
    override val fwModeAllow = "Default Allow"
    override val fwModeDeny = "Default Deny"
    override val blockActionLabel = "Block Response"
    override val actionReject = "Fast Reject"
    override val actionDrop = "Silent Drop"
    override val isolateLan = "Local Network (LAN) Isolation"
    override val isolateLanDesc = "Block user apps from scanning home LAN devices (RFC1918/mDNS)"
    override val dnsLeakGuard = "DNS Leak Guard (Port 53)"
    override val dnsLeakGuardDesc = "Block plain DNS bypass while keeping Android netd & VPN working"
    override val ipv6LeakGuard = "IPv6 VPN Leak Guard"
    override val ipv6LeakGuardDesc = "Drop non-VPN IPv6 traffic whenever a VPN tunnel is active"
    override val antiPortScan = "Public Wi-Fi Anti-Port-Scan (xt_recent)"
    override val antiPortScanDesc = "Auto-drop remote hosts probing unsolicited inbound ports"
    override val blockHttpGlobal = "Block Cleartext HTTP Globally"
    override val blockHttpGlobalDesc = "Enforce encrypted connections by dropping TCP port 80"
    override val normalizeTtl = "TTL / Hop Limit Normalization (64)"
    override val normalizeTtlDesc = "Normalize packet TTL/HL against carrier network fingerprinting"
    override val blockNewApps = "Auto-Block Newly Installed Apps"
    override val blockNewAppsDesc = "Block internet for new user apps until explicitly allowed"
    override val sockDestroyOnBlock = "Cut Open TCP on Block"
    override val sockDestroyOnBlockDesc = "Use kernel SOCK_DESTROY to sever existing sockets in <1ms"
    override val ipBlocklistRow = "Kernel IP / CIDR Blocklist"
    override val ipBlocklistDesc = "Import local tracker/malware IP lists offline"
    override val importFile = "Import File"
    override val addCidrHint = "Add IP/CIDR (e.g. 203.0.113.0/24)"
    override val clearList = "Clear List"

    override val secAutomation = "Privacy Automation (GrapheneOS-Style)"
    override val usbAdbLockGuard = "Lock USB & ADB on Lockscreen"
    override val usbAdbLockGuardDesc = "Disables ADB & sets USB charge-only when locked (stays off on unlock; Download Mode safe)"
    override val autoRebootBfu = "Auto-Reboot to BFU When Locked"
    override val autoRebootBfuDesc = "Cleanly reboot into encrypted BFU if left locked past timeout"
    override val autoClearClip = "Auto-Clear Clipboard"
    override val autoClearClipDesc = "Automatically wipe stale copied text after timeout"
    override val wifiAutoOff = "Wi-Fi Idle Auto-Off"
    override val wifiAutoOffDesc = "Turn off Wi-Fi radio automatically when disconnected"
    override val btAutoOff = "Bluetooth Idle Auto-Off"
    override val btAutoOffDesc = "Turn off Bluetooth radio when no device is connected"
    override val lockSensorsOff = "Mute Sensors on Lockscreen"
    override val lockSensorsOffDesc = "Automatically mute mic & camera while screen is locked"
    override val freezerMaster = "Auto-Freeze Engine (cgroup v2)"
    override val freezerMasterDesc = "Automatically freeze apps registered in your freezer list"
    override val thawAll = "Thaw All"

    override val secKernelHardening = "Kernel Hardening (sysctl)"
    override val hardeningMaster = "Enable Kernel Hardening"
    override val hardeningMasterDesc = "Reversible unpriv-BPF off, JIT harden, ASLR/Yama ptrace, anti-redirect/RFC1337, TCP/IPv6 privacy"
    override val inspectSysctl = "Inspect Live Kernel Values"
    override val rollbackSysctl = "Rollback to Kernel Defaults"
    override val tcpCongestion = "TCP Congestion Control"
    override val packetScheduler = "Packet Scheduler (qdisc)"

    override val secVerification = "Verification & Transparency"
    override val runLiveProofRow = "Run Live Enforcement Proof"
    override val runLiveProofDesc = "Directly inspect live iptables chains, cgroups, appops, sensors & sysctl"
    override val runAuditRow = "Stealth & Integrity Sentinel Audit"
    override val runAuditDesc = "Verify SELinux, MitM CAs, rogue listeners, root procs & stealth"
    override val grapheneNotesRow = "Hardware & ROM Boundaries (No Gimmicks)"
    override val grapheneNotesDesc = "Why certain GrapheneOS features are intentionally omitted on SM-A146P"
    override val grapheneNotesBody = "• Hardware MTE & PAC/BTI: Dimensity 700 (ARMv8.2-A) lacks ARMv8.5+ hardware MTE.\n\n• hardened_malloc, DCL / Exec-Spawning block: requires rebuilding Bionic libc & Zygote inside AOSP ROM source.\n\n• True Storage Scopes (per-app empty /sdcard): requires OS MediaProvider FUSE patches; per-app bind-mounts expose mountinfo to detectors. Path hiding belongs to kernel SUSFS.\n\n• Sandboxed Google Play, Locked AVB + Titan M2, & Lockscreen PIN Scrambling: constrained by stock One UI 7 ROM & unlocked bootloader."

    override val secPreferences = "Appearance, Language & Backup"
    override val languageLabel = "Language"
    override val themeLabel = "Theme"
    override val disguiseMode = "App Disguise (NetDiag)"
    override val disguiseModeDesc = "Swap launcher icon and name for stealth"
    override val exportBackup = "Export Config JSON"
    override val importBackup = "Import Config JSON"
    override val emergencyFlush = "Emergency Flush Firewall"
    override val emergencyFlushDesc = "Detach all SILO_* kernel chains without touching Android netd"

    override val bgAppBadge = "BG"
    override val offlineOnlyBadge = "OFFLINE"
    override val noInternetPermHint = "This app does not declare the INTERNET permission in its manifest."
    override val permSystemFixedBadge = "SYSTEM FIXED"
    override val permSystemFixedNote = "OS-locked (SYSTEM_FIXED) — Android blocks pm revoke; use Fake Empty (AppOps)."
    override val loadingAppPermsLabel = "Loading live permission state..."
    override val retryAction = "Retry"
    override val permsLoadFailed = "Failed to read live permissions from daemon"
    override val undeclaredPermsSection = "Other Permissions (Undeclared)"
    override val undeclaredPermHint = "Not declared in APK manifest"
    override val legacyScopeTitle = "Legacy Isolation Scope (v2) Active"
    override val legacyScopeDesc = "This app still has a legacy v2 scope rule. Clear it to manage permissions cleanly via v3."
    override val clearLegacyScope = "Clear Legacy Rule"
    override val firewallOffRuleHint = "Master firewall is off — rules are saved & apply when Firewall is enabled."
    override val dismiss = "Dismiss"
    override val refresh = "Refresh"
    override val a11yToggleWifi = "Toggle Wi-Fi access"
    override val a11yToggleCellular = "Toggle cellular access"
    override val a11yToggleFreeze = "Freeze or thaw app"
    override val loadingAccessLog = "Scanning permission access log..."
    override val accessLogLoadFailed = "Failed to load permission access log"
    override val protectedBadge = "PROTECTED"
    override val presetCustom = "Custom"
    override val panicLockdownActiveBanner = "PANIC LOCKDOWN ACTIVE — All app traffic is hard-blocked"
    override val liveProofUnavailable = "Unable to load live proof. Ensure daemon is active and retry."
    override val auditUnavailable = "Audit results unavailable. Tap retry to scan."
    override val sysctlUnavailable = "Sysctl values unavailable."
    override val customDnsHint = "DoT Hostname (e.g. dns.quad9.net)"
    override val blocklistEmpty = "No IP/CIDR entries in blocklist yet."
    override val removeEntry = "Remove entry"
    override val rollbackConfirmTitle = "Rollback Kernel Hardening?"
    override val rollbackConfirmBody = "All kernel sysctl hardening parameters will be restored to boot defaults and Kernel Hardening will be disabled."
    override val confirmRollback = "Rollback"
    override val emergencyFlushConfirmTitle = "Emergency Flush Firewall?"
    override val emergencyFlushConfirmBody = "All SILO_* iptables/ip6tables rules and ipsets will be detached immediately, leaving all apps unfiltered until re-enabled."
    override val confirmFlush = "Flush Now"

    override val tileOffline = "Offline"
    override val tileError = "Error"
    override val tileDefaultDeny = "Default-Deny"
    override val tileDefaultAllow = "Default-Allow"
    override val tilePanicLocked = "Panic Lockdown"
    override val tileOff = "Off"
    override val tileSensorsMuted = "Mic/Cam Muted"
    override val tileMicMuted = "Mic Muted"
    override val tileCamMuted = "Cam Muted"
    override val tileSensorsLive = "Active"
    override val tileRebooting = "Rebooting..."
    override val tileRebootFailed = "Reboot failed"
    override val tileTapAgain5s = "Tap again (5s)"
    override val tileTap2xBfu = "Tap 2x -> BFU"

    override val isIndonesian = false
}

fun resolveStrings(
    pref: AppLanguagePref,
    locale: Locale = Locale.getDefault(),
): SiloStrings {
    return when (pref) {
        AppLanguagePref.INDONESIAN -> StringsId
        AppLanguagePref.ENGLISH -> StringsEn
        AppLanguagePref.SYSTEM -> {
            val lang = locale.language.lowercase(Locale.ROOT)
            if (lang == "id" || lang == "in") StringsId else StringsEn
        }
    }
}

val LocalSiloStrings = compositionLocalOf<SiloStrings> { StringsEn }
