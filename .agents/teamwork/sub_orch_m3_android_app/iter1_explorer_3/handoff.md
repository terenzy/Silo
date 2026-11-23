# Handoff Report — `iter1_explorer_3` (Workstream 3: Quick Settings Tiles, `HomeScreen`, `SystemScreen`, `MainActivity`, Manifest, ProGuard & Gradle)

**Role:** READ-ONLY Codebase Explorer (`iter1_explorer_3`)  
**Dispatched By:** `sub_orch_m3_android_app` (`61dc89a1bcda9db55cf5a47a3576d5ea`)  
**Status:** COMPLETE — Strictly Read-Only Audit & Implementation-Ready Blueprint  
**Scope Covered:** `APP-H01`, `APP-H15`, `APP-H16`, `APP-H17`, `APP-H18`, `APP-H19`, `APP-M09`, `APP-M10`, `APP-M12`, `APP-M13`, `APP-M14`, `APP-M17`, `APP-C06` (Home/System sheets), `APP-L06`, `APP-L07`, `APP-L08` (plus `CB-13`, `CB-26`, `CB-27`, `CB-30`).

---

## 1. Direct File & Line Verification Summary

Every target file was read in full (zero skimming) and cross-referenced against `PROJECT.md § Interface Contracts`, `daemon/src/config.rs`, `SiloModels.kt`, `SiloRepository.kt`, `SiloViewModel.kt`, `SiloStrings.kt`, and `Theme.kt`:

| File | Lines Read | Verified Findings / Status |
|---|---|---|
| `app/app/src/main/java/dev/silo/app/tiles/LockdownTileService.kt` | 1–61 | Missing `isLocked`/`unlockAndRun` on disable (`L21–35`); ignores `panicLockdownActive`; ignores `setFirewallConfig` `Result` failure (`L32`); calls `tile.updateTile()` on `Dispatchers.IO` (`L39,52`); hardcoded English subtitles (`L44,47,50`). |
| `app/app/src/main/java/dev/silo/app/tiles/SensorsOffTileService.kt` | 1–54 | Missing `isLocked`/`unlockAndRun` on unmute (`L20–28`); ignores `setSensorsOff` `Result` failure (`L25`); calls `tile.updateTile()` on `Dispatchers.IO` (`L32,45`); hardcoded English subtitles (`L37,40,43`). |
| `app/app/src/main/java/dev/silo/app/tiles/PanicRebootTileService.kt` | 1–73 | Unsynchronized `armedAtElapsedMs` across Main & IO (`L21,31–32,44,53–54`); uncancelled 5.2s timeout coroutine overwrites `"Rebooting..."` because `elapsedRealtime() - 0L >= 5_000L` is always true (`L32,51–57`); ignores `triggerPanicReboot()` `Result` (`L41`), leaving tile stuck on `"Rebooting..."`; calls `resetVisual()`/`updateTile()` on `Dispatchers.IO` (`L55,65`); hardcoded English subtitles (`L36,48,64`). |
| `app/app/src/main/java/dev/silo/app/ui/screens/HomeScreen.kt` | 1–531 | Score defaults to crimson `0` when `sentinelReport == null` (`L126–132,168`); Boot Guard + DNS footer & daemon switches stay live/enabled offline (`L155–158,193–205,234–328`); duplicate `showProofSheet = true` (`L272–273`); loose preset matching (`L307–311`); `LiveProofSheetContent` lacks `verticalScroll`, loading indicator for re-runs, error/retry fallback, `skipPartiallyExpanded = true`, and inline `sheetFeedback` (`L81–91,468–530`). |
| `app/app/src/main/java/dev/silo/app/ui/screens/SystemScreen.kt` | 1–644 | Custom DNS dialog `Apply` allows blank input (`L74–77`) and `DnsPill(s.dnsCustom, false, ...)` hardcodes `active = false` (`L251`); `BlocklistSheet` cannot list/delete individual CIDRs (`L108–151`); `showSysctlSheet` never fetches fresh hardening status (`L153–190,460`); `SentinelAuditSheet` renders `(0/100)` + blank body with no spinner/retry (`L204–214,602–643`); all 5 sheets omit `skipPartiallyExpanded = true` and inline `sheetFeedback`; `autoRebootMinutes / 60` formats `30m` as `(0h)` (`L365,375`); no duration pickers for clipboard/Wi-Fi/BT/freezer (`L383–407`); `availCc.take(3)` hides 4th+ TCP congestion algorithms like `bbr` (`L436`); Packet Scheduler `"Default"` forces `enabled = true` (`L448–452`); `rollbackHardening` (`L467`) and `emergencyFlushFirewall` (`L557`) have no confirmation `AlertDialog`; duplicate `showProofSheet = true` (`L482–483`); hardcoded `"Silo v3.0.0"` (`L564`); `DnsPill` lacks `TextOverflow.Ellipsis` (`L595`). |
| `app/app/src/main/java/dev/silo/app/MainActivity.kt` | 1–144 | `remember(state.languagePref)` misses live OS locale changes (`L51`); `currentTab` uses `remember` instead of `rememberSaveable` (`L73`); Refresh icon has `contentDescription = null` (`L104`); `NavigationBarItem` label lacks `TextOverflow.Ellipsis` (`L126`). |
| `app/app/src/main/AndroidManifest.xml` | 1–97 | Declares unused `POST_NOTIFICATIONS` (`L14`, verified zero references across `app/app/src/`); `MainActivity` has `android:exported="true"` (`L27`), bypassing Disguise mode aliases; `INTERNET` `tools:node="remove"` is correctly present (`L10–12`) and MUST be preserved. |
| `app/app/proguard-rules.pro` | 1–17 | Missing explicit keep rules for `@Serializable` enum classes & models (`values()`, `valueOf()`, `@SerialName` fields) in `dev.silo.app.ipc.**` and `Signature, EnclosingMethod` attributes. |
| `app/app/build.gradle.kts` | 1–98 | **Already verified OK**: `versionName = "3.0.0"` (`L17`), `buildFeatures { compose = true; buildConfig = true }` (`L65–68`), `junit:4.13.2` & `kotlinx-coroutines-test:1.9.0` (`L95–96`). No edits required. |
| `app/build.gradle.kts` & `app/gradle.properties` | 1–7 each | AGP `8.7.3`, Kotlin `2.0.21`, `nonTransitiveRClass=true`. No edits required. |
| `app/app/src/main/res/values{,-in}/strings.xml` | 1–9 each | Already clean & bilingual (`Silo`/`NetDiag` + all 3 tile labels). No edits required. |
| `daemon/src/config.rs` | 1–660, 780–918 | Verified all 4 `SecurityPreset` definitions (`L545–617`) and exact sanitize bounds (`autoRebootMinutes: 15..10080`, `clipboardClearSecs: 10..3600`, `wifi/btAutoOffMinutes: 2..1440`, `backgroundGraceSecs: 5..600`). |

---

## 2. Shared Cross-Workstream Contracts (Required by Workstream 3)

To stay 100% compatible with Workstreams 1 & 2 (implemented by the single M3 Worker):

1. **`SiloModels.kt`** (per `PROJECT.md § Interface Contracts §3`):
   - Add to `FirewallConfig`: `@SerialName("panic_lockdown_active") val panicLockdownActive: Boolean = false`
   - Add to `DaemonStatus`: `@SerialName("panic_lockdown_active") val panicLockdownActive: Boolean = false`
2. **`SiloViewModel.kt` & `SiloUiState`**:
   - Add `data class SheetFeedback(val message: String, val isError: Boolean = false)`
   - Add to `SiloUiState`:
     - `val liveProofLoading: Boolean = false`
     - `val sentinelLoading: Boolean = false`
     - `val hardeningLoading: Boolean = false`
     - `val sheetFeedback: SheetFeedback? = null`
   - Expose methods on `SiloViewModel`:
     - `fun clearSheetFeedback()`
     - `fun refreshHardeningStatus()` (sets `hardeningLoading = true`, calls `repo.getHardeningStatus()`, handles `.onSuccess` and `.onFailure` via `sheetFeedback`)
     - `fun removeSingleBlocklistCidr(cidr: String)` (under `mutationMutex`, removes `cidr`, calls `repo.replaceIpBlocklist(next)`, checks `rejected`, refreshes config & status, emits localized `sheetFeedback`)
     - Update `runLiveProof()` (no `onReady` callback needed; sets `liveProofLoading = true` -> updates `liveProof` / `sheetFeedback` on failure)
     - Update `runSentinelAudit()` (sets `sentinelLoading = true` -> updates `sentinelReport` / `sheetFeedback` on success/failure)
     - Update `addSingleBlocklistCidr` / `importIpBlocklistFromUri` / `clearIpBlocklist` to also publish `sheetFeedback` so errors/confirmations appear inside `BlocklistSheet` (`APP-C06`).
3. **`Theme.kt`**:
   - Add shared composable `SiloInlineFeedbackBanner(feedback: SheetFeedback?, onDismiss: () -> Unit, modifier: Modifier = Modifier)`:
     ```kotlin
     @Composable
     fun SiloInlineFeedbackBanner(
         message: String,
         isError: Boolean,
         onDismiss: () -> Unit,
         modifier: Modifier = Modifier,
     ) {
         val accent = if (isError) SiloCrimson else SiloEmerald
         Surface(
             modifier = modifier.fillMaxWidth(),
             shape = RoundedCornerShape(12.dp),
             color = accent.copy(alpha = 0.16f),
         ) {
             Row(
                 modifier = Modifier
                     .fillMaxWidth()
                     .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                 verticalAlignment = Alignment.CenterVertically,
                 horizontalArrangement = Arrangement.spacedBy(8.dp),
             ) {
                 Icon(
                     if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                     contentDescription = null,
                     tint = accent,
                     modifier = Modifier.size(18.dp),
                 )
                 Text(
                     text = message,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurface,
                     modifier = Modifier.weight(1f),
                 )
                 IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                     Icon(
                         Icons.Rounded.Close,
                         contentDescription = null,
                         tint = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.size(16.dp),
                     )
                 }
             }
         }
     }
     ```
4. **`SiloStrings.kt`**:
   - Keep `resolveStrings` backward-compatible while supporting explicit `Locale` from `LocalConfiguration.current`:
     ```kotlin
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
     ```
   - Add new bilingual string fields to `SiloStrings`, `StringsId`, `StringsEn`:
     | Field | `StringsId` | `StringsEn` |
     |---|---|---|
     | `refresh` | `"Segarkan"` | `"Refresh"` |
     | `presetCustom` | `"Kustom"` | `"Custom"` |
     | `liveProofUnavailable` | `"Gagal memuat uji penegakan. Pastikan daemon aktif lalu coba lagi."` | `"Unable to load live proof. Ensure daemon is active and retry."` |
     | `auditUnavailable` | `"Hasil audit belum tersedia. Ketuk coba lagi untuk memindai."` | `"Audit results unavailable. Tap retry to scan."` |
     | `sysctlUnavailable` | `"Nilai sysctl belum tersedia."` | `"Sysctl values unavailable."` |
     | `customDnsHint` | `"Hostname DoT (mis. dns.quad9.net)"` | `"DoT Hostname (e.g. dns.quad9.net)"` |
     | `blocklistEmpty` | `"Belum ada IP/CIDR di daftar blokir."` | `"No IP/CIDR entries in blocklist yet."` |
     | `removeEntry` | `"Hapus entri"` | `"Remove entry"` |
     | `rollbackConfirmTitle` | `"Kembalikan Sysctl ke Default?"` | `"Rollback Kernel Hardening?"` |
     | `rollbackConfirmBody` | `"Seluruh parameter pengerasan kernel (sysctl) akan dikembalikan ke nilai bawaan boot dan Pengerasan Kernel dinonaktifkan."` | `"All kernel sysctl hardening parameters will be restored to boot defaults and Kernel Hardening will be disabled."` |
     | `confirmRollback` | `"Kembalikan"` | `"Rollback"` |
     | `emergencyFlushConfirmTitle` | `"Flush Darurat Firewall?"` | `"Emergency Flush Firewall?"` |
     | `emergencyFlushConfirmBody` | `"Seluruh aturan iptables/ip6tables SILO_* dan ipset akan dicabut segera, sehingga semua aplikasi dapat mengakses internet tanpa filter firewall."` | `"All SILO_* iptables/ip6tables rules and ipsets will be detached immediately, leaving all apps unfiltered until re-enabled."` |
     | `confirmFlush` | `"Flush Sekarang"` | `"Flush Now"` |
     | `tileOffline` | `"Offline"` | `"Offline"` |
     | `tileError` | `"Gagal"` | `"Error"` |
     | `tileDefaultDeny` | `"Blokir Default"` | `"Default-Deny"` |
     | `tileDefaultAllow` | `"Izinkan Default"` | `"Default-Allow"` |
     | `tilePanicLocked` | `"Panic Lockdown"` | `"Panic Lockdown"` |
     | `tileOff` | `"Mati"` | `"Off"` |
     | `tileSensorsMuted` | `"Mic/Cam Mati"` | `"Mic/Cam Muted"` |
     | `tileMicMuted` | `"Mic Mati"` | `"Mic Muted"` |
     | `tileCamMuted` | `"Kamera Mati"` | `"Cam Muted"` |
     | `tileSensorsLive` | `"Aktif"` | `"Active"` |
     | `tileRebooting` | `"Memulai ulang..."` | `"Rebooting..."` |
     | `tileRebootFailed` | `"Gagal reboot"` | `"Reboot failed"` |
     | `tileTapAgain5s` | `"Ketuk lagi (5d)"` | `"Tap again (5s)"` |
     | `tileTap2xBfu` | `"Ketuk 2x -> BFU"` | `"Tap 2x -> BFU"` |

---

## 3. Detailed Implementation Blueprint

### 3.1 Quick Settings Tiles (`APP-H01`, `APP-M10`, `CB-13`)

**Shared Design Rules Across All 3 Tiles:**
- Resolve localized strings via `resolveStrings(repo.getLanguagePref(applicationContext))` (reads `SharedPreferences("silo_ui_prefs")` key `"language"` — `SiloRepository.kt:56-60`).
- Perform all `qsTile?.state` / `qsTile?.subtitle` / `qsTile?.updateTile()` mutations strictly on `Dispatchers.Main` (never `Dispatchers.IO`).
- Perform all `unlockAndRun { ... }` calls strictly on `Dispatchers.Main` (Android `TileService` contract).
- Inside `unlockAndRun`, re-check `if (isLocked) return@launch` (defense-in-depth) and re-fetch latest live daemon config before writing so no stale snapshot overwrites concurrent changes made during PIN entry.
- Guard overlapping taps with `AtomicBoolean` (`inFlight`) or Main-thread flag to prevent double-tap races.
- Always cancel `serviceScope` in `onDestroy()`.

#### A. `LockdownTileService.kt` (`L1–61`)
- **Security Policy (`APP-H01`):**
  - `currentlyLockdown = (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) || cfg.firewall.panicLockdownActive`
  - **Enabling Lockdown (`!currentlyLockdown`)**: Allowed immediately **even when `isLocked == true`** (emergency defensive action). Sets `cfg.firewall.copy(enabled = true, mode = FirewallMode.DEFAULT_DENY)`.
  - **Disabling Lockdown (`currentlyLockdown`)**: If `isLocked`, MUST call `unlockAndRun { ... }` on `Dispatchers.Main` before disabling! Sets `cfg.firewall.copy(mode = FirewallMode.DEFAULT_ALLOW, panicLockdownActive = false)`.
- **Concrete Implementation:**
  ```kotlin
  package dev.silo.app.tiles

  import android.service.quicksettings.Tile
  import android.service.quicksettings.TileService
  import dev.silo.app.SiloApp
  import dev.silo.app.ipc.FirewallMode
  import dev.silo.app.ui.i18n.SiloStrings
  import dev.silo.app.ui.i18n.resolveStrings
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext
  import java.util.concurrent.atomic.AtomicBoolean

  class LockdownTileService : TileService() {
      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      private val inFlight = AtomicBoolean(false)

      private fun tileStrings(): SiloStrings {
          val repo = (application as SiloApp).repository
          return resolveStrings(repo.getLanguagePref(applicationContext))
      }

      private fun updateTileOnMain(state: Int, subtitle: String) {
          val tile = qsTile ?: return
          tile.state = state
          tile.subtitle = subtitle
          tile.updateTile()
      }

      override fun onStartListening() {
          super.onStartListening()
          refreshTile()
      }

      override fun onClick() {
          super.onClick()
          if (!inFlight.compareAndSet(false, true)) return
          val repo = (application as SiloApp).repository
          serviceScope.launch {
              try {
                  val cfgRes = withContext(Dispatchers.IO) { repo.getConfig() }
                  val cfg = cfgRes.getOrNull()
                  if (cfg == null) {
                      updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
                      return@launch
                  }
                  val currentlyLockdown =
                      (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) ||
                          cfg.firewall.panicLockdownActive

                  if (currentlyLockdown) {
                      // Disabling Lockdown weakens protection: require device unlock when locked!
                      if (isLocked) {
                          inFlight.set(false)
                          unlockAndRun {
                              if (!inFlight.compareAndSet(false, true)) return@unlockAndRun
                              serviceScope.launch {
                                  try {
                                      if (isLocked) return@launch
                                      applyDisableLockdown()
                                  } finally {
                                      inFlight.set(false)
                                  }
                              }
                          }
                          return@launch
                      }
                      applyDisableLockdown()
                  } else {
                      // Enabling Lockdown is allowed immediately even on lockscreen
                      val updated = cfg.firewall.copy(
                          enabled = true,
                          mode = FirewallMode.DEFAULT_DENY,
                      )
                      val res = withContext(Dispatchers.IO) { repo.setFirewallConfig(updated) }
                      if (res.isFailure) {
                          updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileError)
                      } else {
                          refreshTileInternal()
                      }
                  }
              } finally {
                  inFlight.set(false)
              }
          }
      }

      private suspend fun applyDisableLockdown() {
          val repo = (application as SiloApp).repository
          // Re-fetch latest config after unlock to prevent stale read-modify-write
          val fresh = withContext(Dispatchers.IO) { repo.getConfig().getOrNull() }
          if (fresh == null) {
              updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
              return
          }
          val updated = fresh.firewall.copy(
              mode = FirewallMode.DEFAULT_ALLOW,
              panicLockdownActive = false,
          )
          val res = withContext(Dispatchers.IO) { repo.setFirewallConfig(updated) }
          if (res.isFailure) {
              updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileError)
          } else {
              refreshTileInternal()
          }
      }

      private fun refreshTile() {
          serviceScope.launch { refreshTileInternal() }
      }

      private suspend fun refreshTileInternal() {
          val repo = (application as SiloApp).repository
          val s = tileStrings()
          val cfg = withContext(Dispatchers.IO) { repo.getConfig().getOrNull() }
          if (cfg == null) {
              updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileOffline)
          } else if (cfg.firewall.panicLockdownActive) {
              updateTileOnMain(Tile.STATE_ACTIVE, s.tilePanicLocked)
          } else if (cfg.firewall.enabled && cfg.firewall.mode == FirewallMode.DEFAULT_DENY) {
              updateTileOnMain(Tile.STATE_ACTIVE, s.tileDefaultDeny)
          } else {
              updateTileOnMain(
                  Tile.STATE_INACTIVE,
                  if (cfg.firewall.enabled) s.tileDefaultAllow else s.tileOff,
              )
          }
      }

      override fun onDestroy() {
          serviceScope.cancel()
          super.onDestroy()
      }
  }
  ```

#### B. `SensorsOffTileService.kt` (`L1–54`)
- **Security Policy (`APP-H01`):**
  - `currentlyMuted = status.sensorsOffActive || status.micMuted || status.cameraMuted`
  - **Muting (`!currentlyMuted` → `setSensorsOff(true)`)**: Instant even when locked.
  - **Unmuting (`currentlyMuted` → `setSensorsOff(false)`)**: If `isLocked`, MUST call `unlockAndRun { ... }` on `Dispatchers.Main` before unmuting!
- **Concrete Implementation:**
  ```kotlin
  package dev.silo.app.tiles

  import android.service.quicksettings.Tile
  import android.service.quicksettings.TileService
  import dev.silo.app.SiloApp
  import dev.silo.app.ui.i18n.SiloStrings
  import dev.silo.app.ui.i18n.resolveStrings
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext
  import java.util.concurrent.atomic.AtomicBoolean

  class SensorsOffTileService : TileService() {
      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
      private val inFlight = AtomicBoolean(false)

      private fun tileStrings(): SiloStrings {
          val repo = (application as SiloApp).repository
          return resolveStrings(repo.getLanguagePref(applicationContext))
      }

      private fun updateTileOnMain(state: Int, subtitle: String) {
          val tile = qsTile ?: return
          tile.state = state
          tile.subtitle = subtitle
          tile.updateTile()
      }

      override fun onStartListening() {
          super.onStartListening()
          refreshTile()
      }

      override fun onClick() {
          super.onClick()
          if (!inFlight.compareAndSet(false, true)) return
          val repo = (application as SiloApp).repository
          serviceScope.launch {
              try {
                  val status = withContext(Dispatchers.IO) { repo.getStatus().getOrNull() }
                  if (status == null) {
                      updateTileOnMain(Tile.STATE_UNAVAILABLE, tileStrings().tileOffline)
                      return@launch
                  }
                  val currentlyMuted = status.sensorsOffActive || status.micMuted || status.cameraMuted
                  if (currentlyMuted) {
                      // Unmuting Mic/Camera weakens physical privacy: require unlock when locked!
                      if (isLocked) {
                          inFlight.set(false)
                          unlockAndRun {
                              if (!inFlight.compareAndSet(false, true)) return@unlockAndRun
                              serviceScope.launch {
                                  try {
                                      if (isLocked) return@launch
                                      applySensorsOff(enabled = false)
                                  } finally {
                                      inFlight.set(false)
                                  }
                              }
                          }
                          return@launch
                      }
                      applySensorsOff(enabled = false)
                  } else {
                      // Muting Mic+Camera is allowed immediately on lockscreen
                      applySensorsOff(enabled = true)
                  }
              } finally {
                  inFlight.set(false)
              }
          }
      }

      private suspend fun applySensorsOff(enabled: Boolean) {
          val repo = (application as SiloApp).repository
          val s = tileStrings()
          val res = withContext(Dispatchers.IO) { repo.setSensorsOff(enabled) }
          res.onSuccess { live ->
              applySensorStateToTile(live.micMuted, live.cameraMuted, s)
          }.onFailure {
              updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileError)
          }
      }

      private fun applySensorStateToTile(micMuted: Boolean, cameraMuted: Boolean, s: SiloStrings) {
          when {
              micMuted && cameraMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileSensorsMuted)
              micMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileMicMuted)
              cameraMuted -> updateTileOnMain(Tile.STATE_ACTIVE, s.tileCamMuted)
              else -> updateTileOnMain(Tile.STATE_INACTIVE, s.tileSensorsLive)
          }
      }

      private fun refreshTile() {
          serviceScope.launch {
              val repo = (application as SiloApp).repository
              val s = tileStrings()
              val status = withContext(Dispatchers.IO) { repo.getStatus().getOrNull() }
              if (status == null) {
                  updateTileOnMain(Tile.STATE_UNAVAILABLE, s.tileOffline)
              } else {
                  val mic = status.micMuted || status.sensorsOffActive
                  val cam = status.cameraMuted || status.sensorsOffActive
                  if (status.micMuted || status.cameraMuted) {
                      applySensorStateToTile(status.micMuted, status.cameraMuted, s)
                  } else {
                      applySensorStateToTile(mic, cam, s)
                  }
              }
          }
      }

      override fun onDestroy() {
          serviceScope.cancel()
          super.onDestroy()
      }
  }
  ```

#### C. `PanicRebootTileService.kt` (`L1–73`)
- **Fixes (`APP-M10`):**
  1. Confine `serviceScope` to `Dispatchers.Main.immediate` AND mark `@Volatile` on `armedAtElapsedMs` and `rebooting`.
  2. Store `private var disarmJob: Job? = null`; cancel `disarmJob` immediately on 2nd tap (and before arming a new timer) so the 5.2s timeout can NEVER race or overwrite `"Rebooting..."`. Also match `armedAtElapsedMs == armedStamp` inside the timeout check.
  3. Inspect `Result<Unit>` returned by `withContext(Dispatchers.IO) { repo.triggerPanicReboot() }`; on failure, clear `rebooting`, set `Tile.STATE_INACTIVE` + `s.tileRebootFailed`, and schedule a 3s visual reset back to `s.tileTap2xBfu` instead of hanging permanently on `"Rebooting..."`.
  4. All `qsTile?.updateTile()` calls run strictly on `Dispatchers.Main`.
  5. Cancel `disarmJob` and `serviceScope` in `onDestroy()`.
  6. Localize all 4 subtitles via `resolveStrings(repo.getLanguagePref(applicationContext))`.
- **Concrete Implementation:**
  ```kotlin
  package dev.silo.app.tiles

  import android.os.SystemClock
  import android.service.quicksettings.Tile
  import android.service.quicksettings.TileService
  import dev.silo.app.SiloApp
  import dev.silo.app.ui.i18n.SiloStrings
  import dev.silo.app.ui.i18n.resolveStrings
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.SupervisorJob
  import kotlinx.coroutines.cancel
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.launch
  import kotlinx.coroutines.withContext

  class PanicRebootTileService : TileService() {
      private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

      @Volatile
      private var armedAtElapsedMs: Long = 0L

      @Volatile
      private var rebooting: Boolean = false

      private var disarmJob: Job? = null

      private fun tileStrings(): SiloStrings {
          val repo = (application as SiloApp).repository
          return resolveStrings(repo.getLanguagePref(applicationContext))
      }

      private fun updateTileOnMain(state: Int, subtitle: String) {
          val tile = qsTile ?: return
          tile.state = state
          tile.subtitle = subtitle
          tile.updateTile()
      }

      override fun onStartListening() {
          super.onStartListening()
          if (!rebooting) {
              disarmJob?.cancel()
              disarmJob = null
              armedAtElapsedMs = 0L
              resetVisual()
          }
      }

      override fun onClick() {
          super.onClick()
          if (rebooting) return
          val s = tileStrings()
          val now = SystemClock.elapsedRealtime()
          if (armedAtElapsedMs > 0L && now - armedAtElapsedMs <= 5_000L) {
              disarmJob?.cancel()
              disarmJob = null
              armedAtElapsedMs = 0L
              rebooting = true
              updateTileOnMain(Tile.STATE_ACTIVE, s.tileRebooting)

              val repo = (application as SiloApp).repository
              serviceScope.launch {
                  val res = withContext(Dispatchers.IO) { repo.triggerPanicReboot() }
                  rebooting = false
                  if (res.isFailure) {
                      updateTileOnMain(Tile.STATE_INACTIVE, tileStrings().tileRebootFailed)
                      disarmJob?.cancel()
                      disarmJob = serviceScope.launch {
                          delay(3_000L)
                          if (!rebooting && armedAtElapsedMs == 0L) {
                              resetVisual()
                          }
                      }
                  }
              }
          } else {
              disarmJob?.cancel()
              armedAtElapsedMs = now
              updateTileOnMain(Tile.STATE_ACTIVE, s.tileTapAgain5s)
              disarmJob = serviceScope.launch {
                  delay(5_200L)
                  if (!rebooting && armedAtElapsedMs == now) {
                      armedAtElapsedMs = 0L
                      resetVisual()
                  }
              }
          }
      }

      private fun resetVisual() {
          updateTileOnMain(Tile.STATE_INACTIVE, tileStrings().tileTap2xBfu)
      }

      override fun onDestroy() {
          disarmJob?.cancel()
          disarmJob = null
          serviceScope.cancel()
          super.onDestroy()
      }
  }
  ```

---

### 3.2 `HomeScreen.kt` (`APP-H15`, `APP-M12`, `APP-C06`, `APP-L07`)

#### A. Pure Unit-Testable `matchSecurityPreset` (`APP-H15`, replaces `HomeScreen.kt:307–311`)
Grounded 100% in `daemon/src/config.rs:545–617` (and `D-06` `panic_lockdown_active` + `D-21` full preset reset). Declare as a public top-level function in `HomeScreen.kt` (`package dev.silo.app.ui.screens`) so JVM unit tests call it directly:

```kotlin
fun matchSecurityPreset(
    config: SiloConfig,
    panicLockdownActive: Boolean = config.firewall.panicLockdownActive,
): SecurityPreset? {
    val fw = config.firewall
    val h = config.hardening
    val sc = config.scopes
    val auto = config.automation
    val fz = config.freezer

    // Panic Lockdown is an emergency override state (cuts all non-protected UIDs unconditionally),
    // never one of the 4 standard presets.
    if (panicLockdownActive || fw.panicLockdownActive) return null

    val isSafe = !fw.enabled &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        !fw.isolateLan &&
        !fw.dnsLeakGuard &&
        !fw.ipv6LeakGuard &&
        !fw.blockCleartextHttpGlobal &&
        !fw.antiPortScan &&
        !fw.normalizeTtlHl &&
        !fw.blockNewApps &&
        !h.enabled &&
        !fz.enabled &&
        !sc.autoClearClipboard &&
        !auto.sensorsOffOnLock &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isSafe) return SecurityPreset.SAFE

    val isBalanced = fw.enabled &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        fw.isolateLan &&
        fw.dnsLeakGuard &&
        fw.ipv6LeakGuard &&
        fw.antiPortScan &&
        !fw.blockCleartextHttpGlobal &&
        !fw.normalizeTtlHl &&
        !fw.blockNewApps &&
        h.enabled &&
        h.disableTcpTimestamps &&
        h.hardenMemoryAndPtrace &&
        h.ipv6PrivacyExtensions &&
        sc.autoClearClipboard &&
        sc.clipboardClearSecs == 60 &&
        !auto.sensorsOffOnLock &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isBalanced) return SecurityPreset.BALANCED

    val allHardeningFlags = h.enabled &&
        h.hideKernelPointers &&
        h.restrictPerfEvents &&
        h.disableUnprivilegedBpf &&
        h.hardenBpfJit &&
        h.disableSuidDumpable &&
        h.protectFsLinksFifos &&
        h.restrictDmesg &&
        h.disableTcpTimestamps &&
        h.hardenNetworkStack &&
        h.hardenMemoryAndPtrace &&
        h.ipv6PrivacyExtensions

    val hardenedNetAndBase = fw.enabled &&
        fw.isolateLan &&
        fw.dnsLeakGuard &&
        fw.ipv6LeakGuard &&
        fw.antiPortScan &&
        fw.blockCleartextHttpGlobal &&
        fw.normalizeTtlHl &&
        fw.ttlValue == 64 &&
        fw.blockNewApps &&
        allHardeningFlags &&
        sc.autoClearClipboard &&
        auto.sensorsOffOnLock

    val isHardened = hardenedNetAndBase &&
        fw.mode == FirewallMode.DEFAULT_ALLOW &&
        sc.clipboardClearSecs == 45 &&
        !auto.usbAdbLockGuard &&
        !auto.wifiAutoOffEnabled &&
        !auto.bluetoothAutoOffEnabled
    if (isHardened) return SecurityPreset.HARDENED

    val isParanoid = hardenedNetAndBase &&
        fw.mode == FirewallMode.DEFAULT_DENY &&
        fw.blockAction == BlockAction.REJECT &&
        sc.clipboardClearSecs == 30 &&
        auto.wifiAutoOffEnabled &&
        auto.bluetoothAutoOffEnabled &&
        auto.usbAdbLockGuard
    if (isParanoid) return SecurityPreset.PARANOID

    return null
}
```
- **Why `autoRebootEnabled` is intentionally NOT checked**: `config.rs:890` (`presets_never_auto_enable_auto_reboot`) and `HomeScreen.kt:330` guarantee Auto-Reboot BFU is always an independent manual opt-in.
- **Why `freezer.enabled` is checked ONLY for `SAFE`**: `config.rs:558` sets `freezer.enabled = false` in `Safe`, while `Balanced`/`Hardened`/`Paranoid` leave `freezer.enabled` untouched.
- **In `HomeScreen.kt:305–328`**:
  - Compute `val isPanicActive = state.status.panicLockdownActive || state.config.firewall.panicLockdownActive`.
  - Compute `val activePreset = matchSecurityPreset(state.config, isPanicActive)`.
  - In the Presets header row (`L305`), when `state.connected && activePreset == null`, render a small tonal pill showing `if (isPanicActive) s.panicLockdown else s.presetCustom` (crimson for Panic, neutral/cyan for Custom) so users clearly see why none of the 4 rows has a checkmark!
  - Pass `selected = activePreset == SecurityPreset.SAFE` (etc.) and `enabled = state.connected` to each `PresetRow`.

#### B. Score Tile & Offline Handling (`APP-M12`, `APP-L07`)
- **Lines 126–132 & 166–190**:
  - `val score = state.sentinelReport?.score`
  - `val scoreColor = when { !state.connected || score == null -> MaterialTheme.colorScheme.onSurfaceVariant; score >= 85 -> SiloEmerald; score >= 65 -> SiloAmber; else -> SiloCrimson }`
  - Score `StatTile`: `value = if (state.connected && score != null) "$score" else "--"`, `accent = scoreColor`.
  - Other 3 `StatTile`s (Blocked, Frozen, Enforced): show `"--"` when `!state.connected` (with muted accent `MaterialTheme.colorScheme.onSurfaceVariant`) instead of misleading `"0"`.
- **Lines 155–158**: Master Firewall `Switch`: `enabled = state.connected` and when turning off (`!on`), clear `panicLockdownActive = false`:
  `vm.updateFirewallConfig { it.copy(enabled = on, panicLockdownActive = if (on) it.panicLockdownActive else false) }`.
- **Lines 193–223**: Only render the Boot Guard + DNS footer, `lastEnforcerEvent`, and `lastFirewallError` inside `if (state.connected) { ... }` (when offline, omit the live footer completely so it never shows stale `"Boot Guard: Verifikasi 45d • DNS: Otomatis"`!).
- **Lines 234–300 & 376–466**:
  - Add `enabled: Boolean = true` parameter to `QuickActionTile` and `PresetRow` (applying `.alpha(if (enabled) 1f else 0.45f)` and `.clickable(enabled = enabled, onClick = onClick)`).
  - Disable all daemon-dependent tiles when `!state.connected`: `Mute Mic`, `Mute Camera`, `Live Proof`, `Panic Lockdown`, `Panic BFU`, and all 4 `PresetRow`s.
  - **Keep enabled when offline**:
    1. Reconnect button (`FilledTonalButton` at `L111`) — ALWAYS enabled so user can reconnect!
    2. `Wipe Clipboard` tile (`L255–263`) — works natively via Android `ClipboardManager` even when `silod` is offline!
  - Panic Lockdown `active` predicate (`L284–286`):
    `active = state.connected && (isPanicActive || (state.config.firewall.enabled && state.config.firewall.mode == FirewallMode.DEFAULT_DENY && state.status.micMuted && state.status.cameraMuted))`.

#### C. `LiveProofSheetContent` & ModalBottomSheet (`APP-M12`, `APP-C06`)
- **Lines 271–274**: Remove duplicate `showProofSheet = true` and callback:
  ```kotlin
  onClick = {
      showProofSheet = true
      vm.runLiveProof()
  }
  ```
- **Lines 81–91**:
  ```kotlin
  if (showProofSheet) {
      val proofSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
      ModalBottomSheet(
          onDismissRequest = {
              showProofSheet = false
              vm.clearSheetFeedback()
          },
          sheetState = proofSheetState,
          containerColor = MaterialTheme.colorScheme.surface,
      ) {
          LiveProofSheetContent(
              proof = state.liveProof,
              loading = state.liveProofLoading,
              feedback = state.sheetFeedback,
              onDismissFeedback = { vm.clearSheetFeedback() },
              onRefresh = { vm.runLiveProof() },
          )
      }
  }
  ```
- **Lines 468–530 (`LiveProofSheetContent`)**:
  - Add `Modifier.verticalScroll(rememberScrollState())` on the root `Column`!
  - Render `feedback?.let { SiloInlineFeedbackBanner(it.message, it.isError, onDismissFeedback) }` directly below the header row!
  - Disable refresh `IconButton` while `loading` is `true` and set `contentDescription = s.refresh`.
  - Loading/empty states:
    - If `loading && proof == null`: centered `CircularProgressIndicator(strokeWidth = 2.dp)`.
    - If `loading && proof != null`: `LinearProgressIndicator(modifier = Modifier.fillMaxWidth())` above the proof list so re-runs give clear progress feedback.
    - If `!loading && proof == null`: error/empty text (`s.liveProofUnavailable`) + `FilledTonalButton(onClick = onRefresh) { Text(s.retryBridge) }` instead of spinning forever!

---

### 3.3 `SystemScreen.kt` (`APP-H16`, `APP-H17`, `APP-H18`, `APP-H19`, `APP-M13`, `APP-M14`, `APP-C06`, `APP-L06`)

#### A. Pure Unit-Testable Helpers (`APP-H16`, `APP-H19`)
Declare as public top-level functions in `SystemScreen.kt` (`package dev.silo.app.ui.screens`):
```kotlin
fun formatDurationLabel(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    return when {
        safe < 60 -> "${safe}m"
        safe % 60 == 0 -> "${safe / 60}h"
        else -> "${safe / 60}h ${safe % 60}m"
    }
}

fun formatSecondsLabel(secs: Int): String {
    val safe = secs.coerceAtLeast(0)
    return when {
        safe < 60 -> "${safe}s"
        safe % 60 == 0 -> "${safe / 60}m"
        else -> "${safe / 60}m ${safe % 60}s"
    }
}

val DEFAULT_DNS_PRESET_HOSTS: Set<String> = setOf(
    "dns.quad9.net",
    "dns.mullvad.net",
    "dns.adguard-dns.com",
    "one.one.one.one",
)

fun isCustomPrivateDns(
    mode: String,
    specifier: String,
    presetHosts: Collection<String> = DEFAULT_DNS_PRESET_HOSTS,
): Boolean {
    if (mode != "hostname") return false
    val clean = specifier.trim()
    if (clean.isEmpty()) return false
    return presetHosts.none { it.equals(clean, ignoreCase = true) }
}
```
- Verify `formatDurationLabel(30) == "30m"` (fixes `L365` `(0h)` bug!).
- Wire into Automation titles:
  - `L365`: `"${s.autoRebootBfu} (${formatDurationLabel(auto.autoRebootMinutes)})"`
  - `L384`: `"${s.autoClearClip} (${formatSecondsLabel(state.config.scopes.clipboardClearSecs)})"`
  - `L390`: `"${s.wifiAutoOff} (${formatDurationLabel(auto.wifiAutoOffMinutes)})"`
  - `L396`: `"${s.btAutoOff} (${formatDurationLabel(auto.bluetoothAutoOffMinutes)})"`
  - `L405`: `"${s.freezerMaster} (${formatSecondsLabel(state.config.freezer.backgroundGraceSecs)})"`

#### B. Packet Scheduler `"Default"` (`APP-H17`, `SystemScreen.kt:448–452`)
Replace line 450:
```kotlin
for ((lbl, q) in listOf("Default" to "", "CAKE" to "cake", "FQ" to "fq", "PIE" to "pie")) {
    DnsPill(lbl, h.defaultQdisc == q, Modifier.weight(1f)) {
        vm.updateHardeningConfig {
            if (q.isEmpty()) it.copy(defaultQdisc = "")
            else it.copy(enabled = true, defaultQdisc = q)
        }
    }
}
```
Never forces `enabled = true` when selecting `"Default"`!

#### C. Destructive Confirmation `AlertDialog`s (`APP-H18`, `SystemScreen.kt:463–468, 552–558`)
- Add `var showRollbackDialog by remember { mutableStateOf(false) }` and `var showEmergencyFlushDialog by remember { mutableStateOf(false) }`.
- `s.rollbackSysctl` row `onClick = { showRollbackDialog = true }`.
- `s.emergencyFlush` row `onClick = { showEmergencyFlushDialog = true }`.
- Render explicit `AlertDialog`s:
  - `showRollbackDialog`: icon `Icons.Rounded.Restore` (`SiloAmber`), title `s.rollbackConfirmTitle`, text `s.rollbackConfirmBody`, confirm button (`SiloAmber`, `Color.Black`) calling `vm.rollbackHardening()`, dismiss button `s.cancel`.
  - `showEmergencyFlushDialog`: icon `Icons.Rounded.WarningAmber` (`SiloCrimson`), title `s.emergencyFlushConfirmTitle`, text `s.emergencyFlushConfirmBody`, confirm button (`SiloCrimson`) calling `vm.emergencyFlushFirewall()`, dismiss button `s.cancel`.

#### D. Custom DNS Highlight & Blank Validation (`APP-H19`, `SystemScreen.kt:61–83, 246–255`)
- At `L251`:
  ```kotlin
  val presetHosts = dnsPresets.filter { it.first == "hostname" }.map { it.second }
  val isCustomActive = isCustomPrivateDns(curDns.mode, curDns.specifier, presetHosts)
  DnsPill(s.dnsCustom, isCustomActive, Modifier.weight(1f)) {
      customDnsHost = curDns.specifier
      showCustomDnsDialog = true
  }
  ```
- At `L61–83` (Custom DNS `AlertDialog`):
  - Localize field label with `s.customDnsHint`.
  - Compute `val trimmedDns = customDnsHost.trim()`.
  - Set `enabled = trimmedDns.isNotBlank()` on confirm `Button`, passing `vm.setPrivateDns("hostname", trimmedDns)`.

#### E. All `availCc` Congestion Algos + Fresh Sysctl & Audit Sheets (`APP-M13`, `APP-C06`)
1. **Replace `.take(3)` (`L432–441`) with `FlowRow`** (`@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)`):
   ```kotlin
   FlowRow(
       modifier = Modifier.fillMaxWidth(),
       horizontalArrangement = Arrangement.spacedBy(6.dp),
       verticalArrangement = Arrangement.spacedBy(6.dp),
   ) {
       DnsPill("Default", h.tcpCongestion.isEmpty(), Modifier.defaultMinSize(minWidth = 72.dp)) {
           vm.updateHardeningConfig { it.copy(tcpCongestion = "") }
       }
       availCc.forEach { cc ->
           DnsPill(cc, h.tcpCongestion == cc, Modifier.defaultMinSize(minWidth = 72.dp)) {
               vm.updateHardeningConfig { it.copy(enabled = true, tcpCongestion = cc) }
           }
       }
   }
   ```
   All algorithms (including `bbr` when 4th+) are always visible and selectable!
2. **`showSysctlSheet` (`L153–190, 460`)**:
   - On click (`L460`): `{ showSysctlSheet = true; vm.refreshHardeningStatus() }`.
   - Use `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
   - Clear feedback on dismiss (`vm.clearSheetFeedback()`).
   - Add header `Row` with Refresh `IconButton(enabled = !state.hardeningLoading, onClick = { vm.refreshHardeningStatus() })`, inline `state.sheetFeedback` banner, `LinearProgressIndicator` / `CircularProgressIndicator` when `state.hardeningLoading`, and fallback empty state `s.sysctlUnavailable` if `!state.hardeningLoading && state.hardeningStatus.items.isEmpty()`.
3. **`showProofSheet` (`L192–202, 481–484`)**:
   - Remove duplicate `showProofSheet = true` (`L482–483` → `{ showProofSheet = true; vm.runLiveProof() }`).
   - Use `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)` and pass `loading = state.liveProofLoading`, `feedback = state.sheetFeedback`, `onDismissFeedback = { vm.clearSheetFeedback() }`.
4. **`SentinelAuditSheetContent` (`L204–214, 602–643`)**:
   - Use `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)` and pass `loading = state.sentinelLoading`, `feedback = state.sheetFeedback`, `onDismissFeedback = { vm.clearSheetFeedback() }`.
   - Title: `val scoreStr = report?.let { "${it.score}/100" } ?: "--/100"` → `"${s.runAuditRow} ($scoreStr)"`.
   - Render inline `SiloInlineFeedbackBanner` when `feedback != null`.
   - If `loading && report == null`: centered `CircularProgressIndicator(strokeWidth = 2.dp)`.
   - If `loading && report != null`: `LinearProgressIndicator(modifier = Modifier.fillMaxWidth())` above findings.
   - If `!loading && report == null`: show `s.auditUnavailable` + `FilledTonalButton(onClick = onRefresh) { Text(s.retryBridge) }`.
5. **`showGrapheneSheet` (`L85–106`)**: Add `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`.

#### F. Blocklist Entry List + Per-Entry Delete & Automation Duration Pickers (`APP-M14`, `APP-C06`)
1. **`BlocklistSheet` (`L108–151`)**:
   - Use `sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)` and clear `sheetFeedback` on dismiss.
   - Render inline `state.sheetFeedback?.let { SiloInlineFeedbackBanner(it.message, it.isError) { vm.clearSheetFeedback() } }`.
   - Disable `+` button when `cidrInput.trim().isBlank()`.
   - Render scrollable entry list (`val uniqueEntries = remember(fw.ipBlocklist) { fw.ipBlocklist.distinct() }`):
     - If `uniqueEntries.isEmpty()`: muted text `s.blocklistEmpty`.
     - Else: `LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp))` using `items(items = uniqueEntries, key = { it })` with monospace CIDR text (`maxLines = 1, overflow = TextOverflow.Ellipsis`) and delete `IconButton(onClick = { vm.removeSingleBlocklistCidr(entry) })` with `Icons.Rounded.DeleteOutline` (`SiloCrimson`, `contentDescription = s.removeEntry`).
     - Note: Import `androidx.compose.foundation.lazy.items`!
2. **Automation Duration Pickers (`L364–417`)** — all values verified against `daemon/src/config.rs` clamps:
   - Extract DRY helper `@Composable private fun DurationPillRow(options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit)` matching `L370–381`.
   - **Auto-Reboot BFU** (clamp `15..10080` min): `listOf("30m" to 30, "4h" to 240, "12h" to 720, "18h" to 1080)` (shown when `auto.autoRebootEnabled`).
   - **Auto-Clear Clipboard** (clamp `10..3600` secs): `listOf("15s" to 15, "30s" to 30, "45s" to 45, "1m" to 60, "2m" to 120)` (shown when `state.config.scopes.autoClearClipboard`; covers all 3 preset values 30/45/60s!; calls `vm.setAutoClearClipboard(true, secs)`).
   - **Wi-Fi Idle Auto-Off** (clamp `2..1440` min): `listOf("5m" to 5, "15m" to 15, "30m" to 30, "1h" to 60)` (shown when `auto.wifiAutoOffEnabled`; calls `vm.updateAutomationConfig { it.copy(wifiAutoOffMinutes = mins) }`).
   - **Bluetooth Idle Auto-Off** (clamp `2..1440` min): `listOf("5m" to 5, "15m" to 15, "30m" to 30, "1h" to 60)` (shown when `auto.bluetoothAutoOffEnabled`; calls `vm.updateAutomationConfig { it.copy(bluetoothAutoOffMinutes = mins) }`).
   - **Auto-Freeze Grace Period** (clamp `5..600` secs): `listOf("10s" to 10, "20s" to 20, "1m" to 60, "2m" to 120)` (shown when `state.config.freezer.enabled`; calls `vm.setFreezerEnabled(true, secs)`).

#### G. Version String & Pill Polish (`APP-L06`, `APP-H06`)
- `import dev.silo.app.BuildConfig` and `import androidx.compose.ui.text.style.TextOverflow`.
- `L564`: `"Silo v${BuildConfig.VERSION_NAME}  •  Daemon v${state.status.version}  •  Kernel ${state.status.capabilities.kernelRelease}"`.
- `DnsPill` (`L574–599`):
  - Add `Modifier.defaultMinSize(minHeight = 40.dp)` to inner `Box` (or `Surface`) for comfortable touch targets.
  - Add `maxLines = 1, overflow = TextOverflow.Ellipsis` on `Text(...)` (`L595`).

---

### 3.4 `MainActivity.kt`, `AndroidManifest.xml`, `proguard-rules.pro`, Gradle (`APP-M09`, `APP-M17`, `APP-L06`, `APP-L08`)

#### A. `MainActivity.kt` (`L1–144`)
1. **Live OS Locale Reactivity (`L51`, `APP-L06`)**:
   ```kotlin
   import androidx.compose.runtime.saveable.rememberSaveable
   import androidx.compose.ui.platform.LocalConfiguration
   import androidx.compose.ui.text.style.TextOverflow
   import java.util.Locale
   ...
   val configuration = LocalConfiguration.current
   val activeLocale = remember(configuration) {
       configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
   }
   val strings = remember(state.languagePref, activeLocale) {
       resolveStrings(state.languagePref, activeLocale)
   }
   ```
2. **Survivable Bottom Nav Tab (`L73`, `APP-M17`)**:
   ```kotlin
   var currentTabName by rememberSaveable { mutableStateOf(SiloNavTab.HOME.name) }
   val currentTab = SiloNavTab.entries.firstOrNull { it.name == currentTabName } ?: SiloNavTab.HOME
   ```
   Update `onClick = { currentTabName = tab.name }` at `L124`. (Saving the enum `String` name avoids Java `Serializable` reflection quirks under R8 and survives rotation, split-screen, and process recreation.)
3. **TopAppBar Refresh `contentDescription` (`L104`, `APP-L06`)**:
   `Icon(Icons.Rounded.Refresh, contentDescription = strings.refresh)`
4. **NavigationBarItem Label Ellipsis (`L126`, `APP-L06`)**:
   `label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }`

#### B. `AndroidManifest.xml` (`L10–30`, `APP-M09`, `CB-30`)
1. **PRESERVE** lines 10–12 verbatim (zero-`INTERNET` CI gate invariant!):
   ```xml
   <uses-permission
       android:name="android.permission.INTERNET"
       tools:node="remove" />
   ```
2. **KEEP** line 13 (`QUERY_ALL_PACKAGES`).
3. **DELETE** line 14 (`<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`).
4. **MODIFY** line 27 on `.MainActivity`: change `android:exported="true"` → `android:exported="false"`.
   - **KEEP** `LauncherDefault` (`L35`) and `LauncherDisguise` (`L49`) at `android:exported="true"` (Android routes exported `<activity-alias>` entries to non-exported `targetActivity` while preventing external apps from invoking `.MainActivity` directly when Disguise mode is enabled!).
   - **KEEP** all 3 QS Tile services (`L60–93`) unchanged (`exported="true"` + `BIND_QUICK_SETTINGS_TILE`).

#### C. `proguard-rules.pro` (`L1–17`, `APP-L08`, `CB-30`)
Update to keep all `@Serializable` models, enums (`values()`, `valueOf()`, `@SerialName` fields), and generic signatures in `dev.silo.app.ipc.**`:
```proguard
# Keep Kotlinx Serialization models & enums for IPC protocol with silod
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.silo.app.ipc.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class dev.silo.app.ipc.** { *; }
-keepclassmembers enum dev.silo.app.ipc.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keepclassmembers class dev.silo.app.ipc.** {
    *** Companion;
}
-keepclasseswithmembers class dev.silo.app.ipc.** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

#### D. Gradle Verification (`app/app/build.gradle.kts`)
- Confirmed `buildFeatures { compose = true; buildConfig = true }` is already enabled at `L65–68` and `versionName = "3.0.0"` at `L17`. `BuildConfig.VERSION_NAME` compiles directly without modifying any Gradle file.

---

## 4. Concrete JVM Unit Test Specification

Add a dedicated test file `app/app/src/test/java/dev/silo/app/SystemAndHomeLogicTest.kt` (or extend `ProtocolAndParserTest.kt`) covering all pure helpers with zero Android runtime mocks:

```kotlin
package dev.silo.app

import dev.silo.app.ipc.*
import dev.silo.app.ui.screens.formatDurationLabel
import dev.silo.app.ui.screens.formatSecondsLabel
import dev.silo.app.ui.screens.isCustomPrivateDns
import dev.silo.app.ui.screens.matchSecurityPreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SystemAndHomeLogicTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun safeConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(enabled = false, mode = FirewallMode.DEFAULT_ALLOW),
        hardening = HardeningConfig(enabled = false),
        freezer = FreezerConfig(enabled = false),
        scopes = ScopesConfig(autoClearClipboard = false),
        automation = AutomationConfig(
            sensorsOffOnLock = false,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun balancedConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(
            enabled = true,
            mode = FirewallMode.DEFAULT_ALLOW,
            isolateLan = true,
            dnsLeakGuard = true,
            ipv6LeakGuard = true,
            antiPortScan = true,
            blockCleartextHttpGlobal = false,
            normalizeTtlHl = false,
            blockNewApps = false,
        ),
        hardening = HardeningConfig(enabled = true),
        scopes = ScopesConfig(autoClearClipboard = true, clipboardClearSecs = 60),
        automation = AutomationConfig(
            sensorsOffOnLock = false,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun hardenedConfig(): SiloConfig = SiloConfig(
        firewall = FirewallConfig(
            enabled = true,
            mode = FirewallMode.DEFAULT_ALLOW,
            isolateLan = true,
            dnsLeakGuard = true,
            ipv6LeakGuard = true,
            antiPortScan = true,
            blockCleartextHttpGlobal = true,
            normalizeTtlHl = true,
            ttlValue = 64,
            blockNewApps = true,
        ),
        hardening = HardeningConfig(enabled = true),
        scopes = ScopesConfig(autoClearClipboard = true, clipboardClearSecs = 45),
        automation = AutomationConfig(
            sensorsOffOnLock = true,
            usbAdbLockGuard = false,
            wifiAutoOffEnabled = false,
            bluetoothAutoOffEnabled = false,
        ),
    )

    private fun paranoidConfig(): SiloConfig = hardenedConfig().let { base ->
        base.copy(
            firewall = base.firewall.copy(
                mode = FirewallMode.DEFAULT_DENY,
                blockAction = BlockAction.REJECT,
            ),
            scopes = base.scopes.copy(clipboardClearSecs = 30),
            automation = base.automation.copy(
                wifiAutoOffEnabled = true,
                bluetoothAutoOffEnabled = true,
                usbAdbLockGuard = true,
            ),
        )
    }

    @Test
    fun formatDurationLabelFormatsMinutesHoursAndMixedCorrectly() {
        assertEquals("0m", formatDurationLabel(0))
        assertEquals("0m", formatDurationLabel(-15))
        assertEquals("15m", formatDurationLabel(15))
        assertEquals("30m", formatDurationLabel(30)) // Regression for APP-H16: never "0h"!
        assertEquals("59m", formatDurationLabel(59))
        assertEquals("1h", formatDurationLabel(60))
        assertEquals("1h 30m", formatDurationLabel(90))
        assertEquals("4h", formatDurationLabel(240))
        assertEquals("12h", formatDurationLabel(720))
        assertEquals("18h", formatDurationLabel(1080))
        assertEquals("24h", formatDurationLabel(1440))
    }

    @Test
    fun formatSecondsLabelFormatsSecondsMinutesAndMixedCorrectly() {
        assertEquals("0s", formatSecondsLabel(0))
        assertEquals("0s", formatSecondsLabel(-5))
        assertEquals("10s", formatSecondsLabel(10))
        assertEquals("20s", formatSecondsLabel(20))
        assertEquals("45s", formatSecondsLabel(45))
        assertEquals("59s", formatSecondsLabel(59))
        assertEquals("1m", formatSecondsLabel(60))
        assertEquals("1m 30s", formatSecondsLabel(90))
        assertEquals("2m", formatSecondsLabel(120))
        assertEquals("5m", formatSecondsLabel(300))
    }

    @Test
    fun matchSecurityPresetMatchesAllFourCanonicalPresets() {
        assertEquals(SecurityPreset.SAFE, matchSecurityPreset(safeConfig()))
        assertEquals(SecurityPreset.BALANCED, matchSecurityPreset(balancedConfig()))
        assertEquals(SecurityPreset.HARDENED, matchSecurityPreset(hardenedConfig()))
        assertEquals(SecurityPreset.PARANOID, matchSecurityPreset(paranoidConfig()))
    }

    @Test
    fun matchSecurityPresetAllowsManualAutoRebootOptIn() {
        assertEquals(
            SecurityPreset.SAFE,
            matchSecurityPreset(safeConfig().copy(automation = AutomationConfig(autoRebootEnabled = true))),
        )
        assertEquals(
            SecurityPreset.BALANCED,
            matchSecurityPreset(
                balancedConfig().let {
                    it.copy(
                        automation = it.automation.copy(autoRebootEnabled = true),
                        freezer = it.freezer.copy(enabled = true),
                    )
                }
            ),
        )
    }

    @Test
    fun matchSecurityPresetReturnsNullForPartialOrCustomConfigs() {
        // APP-H15 regression: enabling ONLY firewall must NOT match BALANCED
        val fwOnly = SiloConfig(firewall = FirewallConfig(enabled = true))
        assertNull(matchSecurityPreset(fwOnly))

        // APP-H15 regression: enabling ONLY firewall + HTTP block must NOT match HARDENED
        val fwHttpOnly = SiloConfig(
            firewall = FirewallConfig(enabled = true, blockCleartextHttpGlobal = true)
        )
        assertNull(matchSecurityPreset(fwHttpOnly))

        // Enabling ONLY Default-Deny without hardening/automation must NOT match PARANOID
        val denyOnly = SiloConfig(
            firewall = FirewallConfig(enabled = true, mode = FirewallMode.DEFAULT_DENY)
        )
        assertNull(matchSecurityPreset(denyOnly))

        // Mutating one defining flag on each preset yields custom (null)
        assertNull(matchSecurityPreset(safeConfig().copy(freezer = FreezerConfig(enabled = true))))
        assertNull(matchSecurityPreset(balancedConfig().let { it.copy(firewall = it.firewall.copy(isolateLan = false)) }))
        assertNull(matchSecurityPreset(balancedConfig().let { it.copy(scopes = it.scopes.copy(clipboardClearSecs = 45)) }))
        assertNull(matchSecurityPreset(hardenedConfig().let { it.copy(automation = it.automation.copy(sensorsOffOnLock = false)) }))
        assertNull(matchSecurityPreset(hardenedConfig().let { it.copy(firewall = it.firewall.copy(ttlValue = 128)) }))
        assertNull(matchSecurityPreset(paranoidConfig().let { it.copy(firewall = it.firewall.copy(blockAction = BlockAction.DROP)) }))
        assertNull(matchSecurityPreset(paranoidConfig().let { it.copy(automation = it.automation.copy(usbAdbLockGuard = false)) }))
    }

    @Test
    fun matchSecurityPresetReturnsNullWhenPanicLockdownActive() {
        // Explicit panicLockdownActive parameter overrides even a matching PARANOID config
        assertNull(matchSecurityPreset(paranoidConfig(), panicLockdownActive = true))
        assertNull(matchSecurityPreset(safeConfig(), panicLockdownActive = true))

        // Embedded config.firewall.panicLockdownActive == true via default argument
        val panicCfg = paranoidConfig().let {
            it.copy(firewall = it.firewall.copy(panicLockdownActive = true))
        }
        assertNull(matchSecurityPreset(panicCfg))
    }

    @Test
    fun isCustomPrivateDnsDistinguishesPresetsAndBlankFromCustomHostnames() {
        assertFalse(isCustomPrivateDns("off", ""))
        assertFalse(isCustomPrivateDns("opportunistic", ""))
        assertFalse(isCustomPrivateDns("hostname", ""))
        assertFalse(isCustomPrivateDns("hostname", "   "))
        assertFalse(isCustomPrivateDns("hostname", "dns.quad9.net"))
        assertFalse(isCustomPrivateDns("hostname", "DNS.MULLVAD.NET"))
        assertTrue(isCustomPrivateDns("hostname", "dns.custom.example"))
    }

    @Test
    fun panicLockdownActiveRoundTripsAndDefaultsFalseWhenOmitted() {
        val cfg = SiloConfig(firewall = FirewallConfig(enabled = true, panicLockdownActive = true))
        val encoded = json.encodeToString(cfg)
        assertTrue(encoded.contains("\"panic_lockdown_active\":true"))
        assertEquals(cfg, json.decodeFromString<SiloConfig>(encoded))

        val legacyStatus = json.decodeFromString<DaemonStatus>("""{"version":"3.0.0"}""")
        assertFalse(legacyStatus.panicLockdownActive)
    }
}
```

---

## 5. Verification Commands (Post-Implementation by Worker / Verifiers)

1. Run full JVM tests + Release APK R8 build (validates ProGuard rules, `BuildConfig.VERSION_NAME`, Compose `FlowRow`, `ModalBottomSheet`, and all unit tests):
   ```bash
   cd /home/antigravity/terenzy/Silo/app && ./gradlew testDebugUnitTest assembleRelease --no-daemon
   ```
2. Verify zero-`INTERNET` and removed `POST_NOTIFICATIONS` permission gate:
   ```bash
   /opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions /home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk
   ```
   Must contain `QUERY_ALL_PACKAGES` and MUST NOT contain `android.permission.INTERNET` or `android.permission.POST_NOTIFICATIONS`.
3. Verify `MainActivity` `exported="false"` while aliases remain `exported="true"`:
   ```bash
   /opt/android-sdk/build-tools/35.0.0/aapt2 dump xmltree /home/antigravity/terenzy/Silo/app/app/build/outputs/apk/release/app-release.apk --file AndroidManifest.xml
   ```
