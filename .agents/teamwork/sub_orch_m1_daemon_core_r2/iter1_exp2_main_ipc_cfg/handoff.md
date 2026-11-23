# Handoff Blueprint — M1 Explorer 2 (`iter1_exp2_main_ipc_cfg`)

**Role**: Read-Only Technical Explorer for Milestone M1 (`daemon-core`)  
**Assigned Files**: `daemon/src/protocol.rs`, `daemon/src/config.rs`, `daemon/src/ipc.rs`, `daemon/src/main.rs`  
**Constraint Verification**: Read-only audit complete; zero source/test/config files modified; zero M2 edits proposed; 100% compatible with untouched M2 files (`firewall.rs`, `netlink.rs`, `freezer.rs`, `automation.rs`, `hardening.rs`, `sentinel.rs`, `telemetry.rs`) and Android M3 `SiloModels.kt`.

---

## 1. Executive Summary & Critical Discoveries

1. **Field-Name Parity Trap Avoided (`protocol.rs:606` vs `PROJECT.md:90`)**:
   - `daemon/src/protocol.rs:606` and `app/app/src/main/java/dev/silo/app/ipc/SiloModels.kt:230` BOTH use `last_access` (`@SerialName("last_access") val lastAccess: String? = null`), **NOT** `last_access_ago` (which appeared in an example snippet in `PROJECT.md`).
   - **Rule**: MUST keep `pub last_access: Option<String>` unchanged in `CategoryPermLiveState`, and add ONLY the 4 additive `#[serde(default)]` fields (`runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`).
2. **M2 `dead_code` Warning Traps Avoided (`cargo clippy --all-targets -- -D warnings`)**:
   - Because `silod` is a binary crate (`[[bin]]`), every `pub fn` in untouched M2 files that is ONLY called by `main.rs` MUST remain called by `main.rs` — specifically:
     - `crate::netlink::destroy_matching_connection` (pre-validate via `list_active_connections`, then call `destroy_matching_connection` for safe non-protected, non-`LISTEN`/`TIME_WAIT` sockets).
     - `AutomationEngine::live_sensor_privacy` (keep calling inside cache refresh and `TriggerPanicLockdown` fallback).
     - `sys::read_uid_package_map` & `sys::foreground_app_uids` remain called by M2 `sentinel.rs` and `freezer.rs`.
3. **Singleton Lock Must Be Acquired Before `sync_all_subsystems()`**:
   - Acquiring `flock(LOCK_EX | LOCK_NB)` ONLY inside `run_ipc_server` would allow a duplicate `silod` to overwrite iptables/sysctls and spawn threads before failing on bind.
   - Solution: `run_daemon` acquires `SingletonLockGuard::acquire(&derive_lock_path(&socket_path))` at startup (before creating `DaemonState` or syncing subsystems) and passes `&lock` to `run_ipc_server_with_lock`; standalone `run_ipc_server` acquires its own `SingletonLockGuard` so direct unit tests also enforce lock contention!
4. **Zero Subprocesses Under `state` Lock (`D-04` / `CB-04`)**:
   - Replace monolithic `Arc<Mutex<DaemonState>>` with `Arc<DaemonContext>` separating fast in-memory `state: Mutex<DaemonState>` from dedicated per-subsystem mutexes (`firewall_mtx`, `freezer_mtx`, `hardening_mtx`, `automation_mtx`, `clipboard_mtx`, `enforcer_mtx`), `sys_cache: RwLock<CachedSystemState>`, `uid_index: RwLock<UidPackageIndex>`, and `telemetry: Mutex<TelemetryRing>`.
   - `GetStatus` touches ONLY `telemetry`, `sys_cache`, and fast `state` — **0 subprocesses, <1ms latency**, never blocked by firewall, freezer, automation, clipboard, or enforcer!
5. **Defense-in-Depth Panic Lockdown (`D-06`)**:
   - Even before M2 edits `firewall.rs`, `main.rs::sync_firewall` clones `FirewallConfig` and clears `rules` + `temp_unblock_set` on the transient effective copy whenever `panic_lockdown_active == true`, blocking ALL non-protected user UIDs unconditionally while keeping the user's saved `st.config.firewall.rules` completely intact on disk!

---

## 2. Exact Line-Level Implementation Blueprint

### File 1: `daemon/src/protocol.rs`

#### Edit 1.1 — Raise Framing Limits (Line 8)
Replace line 8:
```rust
pub const MAX_IPC_LINE_BYTES: usize = 512 * 1024;
```
With:
```rust
pub const MAX_IPC_LINE_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_IPC_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
```
*Rationale*: 50,000 IPv6 CIDRs (~45 B each + JSON overhead) ≈ 2.4 MiB request (fits 4 MiB) and ~3.2 MiB pretty-escaped `export_config_json` response (fits 8 MiB).

#### Edit 1.2 — Add `Default` impl for `PrivateDnsState` (Lines 582–586)
After line 586, add:
```rust
impl Default for PrivateDnsState {
    fn default() -> Self {
        Self {
            mode: "opportunistic".to_string(),
            specifier: String::new(),
        }
    }
}
```
*Rationale*: Matches `automation.rs:168` and Kotlin `SiloModels.kt:208-209`, enabling `#[derive(Default)]` on `CachedSystemState` in `main.rs`.

#### Edit 1.3 — Additive Fields on `CategoryPermLiveState` (Lines 602–608)
Replace lines 602–608:
```rust
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CategoryPermLiveState {
    pub category: String,
    pub appops_mode: String,
    pub last_access: Option<String>,
    pub running_now: bool,
    #[serde(default)]
    pub runtime_granted: Option<bool>,
    #[serde(default)]
    pub has_runtime_perms: bool,
    #[serde(default)]
    pub supports_foreground: bool,
    #[serde(default)]
    pub system_fixed: bool,
}
```
*Compatibility Check*:
- Preserves existing field `last_access` (matches `SiloModels.kt:230`).
- All 4 new fields have `#[serde(default)]` (`None`, `false`, `false`, `false` on legacy JSON).
- Only constructed in `daemon/src/scopes.rs` (M1, updated by Explorer 1's blueprint); zero M2 files reference it.

#### Edit 1.4 — Additive `panic_lockdown_active` on `DaemonStatus` (Lines 639–671)
Add to `DaemonStatus` (right after `pub firewall_mode: String,` at line 647 or at line 670 after `pub last_firewall_error: Option<String>,`):
```rust
    #[serde(default)]
    pub panic_lockdown_active: bool,
```
*Compatibility Check*: Only constructed in `daemon/src/main.rs`; zero M2 files construct `DaemonStatus`; Kotlin `SiloClient.kt` uses `ignoreUnknownKeys = true`.

#### Verification of All 49 RPC Variants (`protocol.rs:26-158, 282-514`)
All 49 `DaemonCommand` variants and `parse_ipc_request` match arms remain 100% unchanged and backward compatible with `SiloRepository.kt`, `SiloModels.kt`, and all M2 modules.

---

### File 2: `daemon/src/config.rs`

#### Edit 2.1 — Imports & Atomic Temp Counter (Lines 1–11)
Add `use std::sync::atomic::{AtomicU64, Ordering};` and:
```rust
static CONFIG_TMP_SEQ: AtomicU64 = AtomicU64::new(0);
```
(Prevents temp-file collision if `save_atomic` is ever invoked concurrently within the same PID, e.g. parallel tests.)

#### Edit 2.2 — Add `panic_lockdown_active` to `FirewallConfig` (Lines 179–231)
In `struct FirewallConfig` (after line 183 `pub mode: FirewallMode,`):
```rust
    /// True while 1-tap Panic Lockdown is active (blocks ALL non-protected user UIDs unconditionally,
    /// overriding saved per-app allow rules until explicitly cleared).
    #[serde(default)]
    pub panic_lockdown_active: bool,
```
In `impl Default for FirewallConfig` (after line 215 `mode: FirewallMode::DefaultAllow,`):
```rust
            panic_lockdown_active: false,
```
*M2 Compile Compatibility*: Verified `daemon/src/firewall.rs` tests (lines 836, 860) use `..Default::default()`; zero M2 files construct `FirewallConfig` without `..Default::default()`.

#### Edit 2.3 — `SiloConfig::sanitize()` Mutual Exclusivity & Panic Invariant (Lines 464–492)
1. Right after line 469 (`ttl_value`), enforce `panic_lockdown_active` coherence:
   ```rust
   if !self.firewall.enabled || self.firewall.mode != FirewallMode::DefaultDeny {
       self.firewall.panic_lockdown_active = false;
   }
   ```
2. Replace lines 480–491 (`self.scopes.guards.retain(...)`) so `locked_deny` takes strict precedence and strips duplicates from `auto_revoke_on_bg`:
   ```rust
   self.scopes.guards.retain(|pkg, guard| {
       if !validate_package_name(pkg) || is_protected_package(pkg) {
           return false;
       }
       guard.locked_deny.retain(|c| is_valid_perm_category(c));
       guard.locked_deny.sort();
       guard.locked_deny.dedup();
       guard
           .auto_revoke_on_bg
           .retain(|c| is_valid_perm_category(c) && !guard.locked_deny.contains(c));
       guard.auto_revoke_on_bg.sort();
       guard.auto_revoke_on_bg.dedup();
       !guard.is_empty()
   });
   ```

#### Edit 2.4 — Complete Preset Reset Matrix in `SiloConfig::apply_preset` (`D-21`, Lines 545–617)
Replace lines 545–617 so EVERY preset explicitly sets ALL firewall guard toggles, `panic_lockdown_active = false`, ALL 12 hardening toggles, clipboard settings, and ALL 5 automation enabled flags — guaranteeing zero stale toggles across ANY `(from_preset -> to_preset)` transition (including `Paranoid -> Balanced` and `Paranoid -> Safe`):

```rust
    pub fn apply_preset(&mut self, preset: SecurityPreset) {
        match preset {
            SecurityPreset::Safe => {
                self.firewall.enabled = false;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.panic_lockdown_active = false;
                self.firewall.block_action = BlockAction::Reject;
                self.firewall.isolate_lan = false;
                self.firewall.dns_leak_guard = false;
                self.firewall.ipv6_leak_guard = false;
                self.firewall.block_cleartext_http_global = false;
                self.firewall.anti_port_scan = false;
                self.firewall.normalize_ttl_hl = false;
                self.firewall.ttl_value = 64;
                self.firewall.block_new_apps = false;
                self.freezer.enabled = false;
                self.scopes.auto_clear_clipboard = false;
                self.scopes.clipboard_clear_secs = 45;
                self.hardening = HardeningConfig::default();
                self.automation.auto_reboot_enabled = false;
                self.automation.wifi_auto_off_enabled = false;
                self.automation.bluetooth_auto_off_enabled = false;
                self.automation.sensors_off_on_lock = false;
                self.automation.usb_adb_lock_guard = false;
            }
            SecurityPreset::Balanced => {
                self.firewall.enabled = true;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.panic_lockdown_active = false;
                self.firewall.block_action = BlockAction::Reject;
                self.firewall.isolate_lan = true;
                self.firewall.dns_leak_guard = true;
                self.firewall.ipv6_leak_guard = true;
                self.firewall.anti_port_scan = true;
                self.firewall.block_cleartext_http_global = false;
                self.firewall.normalize_ttl_hl = false;
                self.firewall.ttl_value = 64;
                self.firewall.block_new_apps = false;
                self.hardening.enabled = true;
                self.hardening.hide_kernel_pointers = true;
                self.hardening.restrict_perf_events = true;
                self.hardening.disable_unprivileged_bpf = true;
                self.hardening.harden_bpf_jit = true;
                self.hardening.disable_suid_dumpable = true;
                self.hardening.protect_fs_links_fifos = true;
                self.hardening.restrict_dmesg = true;
                self.hardening.disable_tcp_timestamps = true;
                self.hardening.harden_network_stack = true;
                self.hardening.harden_memory_and_ptrace = true;
                self.hardening.ipv6_privacy_extensions = true;
                self.scopes.auto_clear_clipboard = true;
                self.scopes.clipboard_clear_secs = 60;
                self.automation.auto_reboot_enabled = false;
                self.automation.wifi_auto_off_enabled = false;
                self.automation.bluetooth_auto_off_enabled = false;
                self.automation.sensors_off_on_lock = false;
                self.automation.usb_adb_lock_guard = false;
            }
            SecurityPreset::Hardened => {
                self.firewall.enabled = true;
                self.firewall.mode = FirewallMode::DefaultAllow;
                self.firewall.panic_lockdown_active = false;
                self.firewall.block_action = BlockAction::Reject;
                self.firewall.isolate_lan = true;
                self.firewall.dns_leak_guard = true;
                self.firewall.ipv6_leak_guard = true;
                self.firewall.anti_port_scan = true;
                self.firewall.block_cleartext_http_global = true;
                self.firewall.normalize_ttl_hl = true;
                self.firewall.ttl_value = 64;
                self.firewall.block_new_apps = true;
                self.hardening.enabled = true;
                self.hardening.hide_kernel_pointers = true;
                self.hardening.restrict_perf_events = true;
                self.hardening.disable_unprivileged_bpf = true;
                self.hardening.harden_bpf_jit = true;
                self.hardening.disable_suid_dumpable = true;
                self.hardening.protect_fs_links_fifos = true;
                self.hardening.restrict_dmesg = true;
                self.hardening.disable_tcp_timestamps = true;
                self.hardening.harden_network_stack = true;
                self.hardening.harden_memory_and_ptrace = true;
                self.hardening.ipv6_privacy_extensions = true;
                self.scopes.auto_clear_clipboard = true;
                self.scopes.clipboard_clear_secs = 45;
                self.automation.auto_reboot_enabled = false;
                self.automation.wifi_auto_off_enabled = false;
                self.automation.bluetooth_auto_off_enabled = false;
                self.automation.sensors_off_on_lock = true;
                self.automation.usb_adb_lock_guard = false;
            }
            SecurityPreset::Paranoid => {
                self.apply_preset(SecurityPreset::Hardened);
                self.firewall.mode = FirewallMode::DefaultDeny;
                self.firewall.panic_lockdown_active = false;
                self.firewall.block_action = BlockAction::Reject;
                self.scopes.clipboard_clear_secs = 30;
                self.automation.auto_reboot_enabled = false;
                self.automation.wifi_auto_off_enabled = true;
                self.automation.bluetooth_auto_off_enabled = true;
                self.automation.sensors_off_on_lock = true;
                self.automation.usb_adb_lock_guard = true;
            }
        }
    }
```
*Preserved User Data*: Per-app `firewall.rules`, `firewall.ip_blocklist`, `firewall.nflog_enabled`, `firewall.sock_destroy_on_block`, `freezer.entries`, `freezer.background_grace_secs`, `scopes.apps`, `scopes.guards`, and numeric automation durations (`auto_reboot_minutes`, `wifi_auto_off_minutes`, `bluetooth_auto_off_minutes`) are preserved intentionally. Note: existing test `presets_never_auto_enable_auto_reboot` continues to pass AND even if user previously enabled `auto_reboot_enabled`, switching presets cleanly resets it to `false`.

#### Edit 2.5 — Config Durability: Corrupt Config Backup & Parent-Dir `fsync` (`D-26`, Lines 619–663)
Replace `load_or_default` and `save_atomic`:
```rust
    pub fn load_or_default(path: &Path) -> Self {
        match fs::read(path) {
            Ok(bytes) => match serde_json::from_slice::<SiloConfig>(&bytes) {
                Ok(mut cfg) => {
                    let _ = cfg.sanitize();
                    cfg
                }
                Err(_) => {
                    backup_corrupt_config(path, &bytes);
                    Self::default()
                }
            },
            Err(_) => Self::default(),
        }
    }

    pub fn save_atomic(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent)
                    .map_err(|e| format!("create_dir_all failed: {}", e))?;
                let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
            }
        }

        let seq = CONFIG_TMP_SEQ.fetch_add(1, Ordering::Relaxed);
        let tmp_path = PathBuf::from(format!(
            "{}.tmp.{}.{}",
            path.display(),
            std::process::id(),
            seq
        ));
        let serialized = serde_json::to_string_pretty(self)
            .map_err(|e| format!("serialize config failed: {}", e))?;

        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(0o600)
            .open(&tmp_path)
            .map_err(|e| format!("open temp config failed: {}", e))?;

        file.write_all(serialized.as_bytes())
            .and_then(|_| file.sync_all())
            .map_err(|e| {
                let _ = fs::remove_file(&tmp_path);
                format!("write/sync config failed: {}", e)
            })?;
        drop(file);

        fs::rename(&tmp_path, path).map_err(|e| {
            let _ = fs::remove_file(&tmp_path);
            format!("atomic rename config failed: {}", e)
        })?;
        let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));

        // Durability (D-26): fsync parent directory so atomic rename survives sudden power loss / BFU reboot
        if let Some(parent) = path.parent() {
            let dir_path = if parent.as_os_str().is_empty() {
                Path::new(".")
            } else {
                parent
            };
            if let Ok(dir) = fs::File::open(dir_path) {
                let _ = dir.sync_all();
            }
        }
        Ok(())
    }
```
Helper function (module-private in `config.rs`):
```rust
fn backup_corrupt_config(path: &Path, raw_bytes: &[u8]) {
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut backup_os = path.as_os_str().to_os_string();
    backup_os.push(format!(".corrupt.{}", ts));
    let backup_path = PathBuf::from(backup_os);

    if fs::rename(path, &backup_path).is_ok() {
        let _ = fs::set_permissions(&backup_path, fs::Permissions::from_mode(0o600));
        if let Some(parent) = backup_path.parent() {
            if !parent.as_os_str().is_empty() {
                if let Ok(dir) = fs::File::open(parent) {
                    let _ = dir.sync_all();
                }
            }
        }
        return;
    }

    if let Ok(mut f) = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .mode(0o600)
        .open(&backup_path)
    {
        let _ = f.write_all(raw_bytes);
        let _ = f.sync_all();
    }
}
```
*Edge Cases Covered*:
- Using `fs::read` + `serde_json::from_slice` handles BOTH malformed JSON AND non-UTF-8 binary corruption (which `fs::read_to_string` previously dropped silently!).
- `backup_corrupt_config` preserves exact bytes at `<path>.corrupt.<unix_ts>` with `0600` permissions via atomic rename (falling back to `0600` write if rename fails).

#### Audit of `PROTECTED_PACKAGES` / `is_protected_uid` / `is_protected_package` (`config.rs:42-62, 666-677`)
- `is_protected_uid(uid)`: `let app_id = uid % 100_000; !(10_000..90_000).contains(&app_id)` — accurately protects `0..9999` and `90000..99999` across ALL Android users (user 0, 10, 95, 150).
- `is_protected_package(pkg)`: trims and checks exact match against all 19 critical packages (matches `app/.../AppInventory.kt` parity). No changes needed here; authoritative UID-to-package enforcement happens via `UidPackageIndex` in `main.rs`.

---

### File 3: `daemon/src/ipc.rs`

#### Edit 3.1 — Imports, Type Alias & Constants (Lines 1–14)
Replace lines 1–14:
```rust
use crate::protocol::{
    parse_ipc_request, IpcRequest, IpcResponse, MAX_IPC_LINE_BYTES, MAX_IPC_RESPONSE_BYTES,
};
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

const MAX_CONCURRENT_CLIENTS: usize = 16;
const SERVER_READ_TIMEOUT: Duration = Duration::from_secs(30);
const SERVER_WRITE_TIMEOUT: Duration = Duration::from_secs(15);
const BRIDGE_READ_TIMEOUT: Duration = Duration::from_secs(45);
const BRIDGE_WRITE_TIMEOUT: Duration = Duration::from_secs(15);

type BridgeConn = (BufReader<UnixStream>, UnixStream);

/// Process-wide mutex serializing the tiny `umask(0o077)` -> `bind()` -> restore critical section
/// so parallel Rust tests never race on the process-global umask!
static BIND_UMASK_LOCK: Mutex<()> = Mutex::new(());
```
*(Note: remove unused `Read` import and remove lines 281–282 `_assert_read_trait` so clippy has zero warnings.)*

#### Edit 3.2 — `derive_lock_path`, `SingletonLockGuard`, `UmaskGuard`, `unlink_socket_if_matches` (`CB-07`, `CB-23`)
Insert right after `read_line_bounded` (after line 88):
```rust
/// Derives the singleton lockfile path alongside `socket_path`:
/// - `/data/adb/silo/silod.sock` -> `/data/adb/silo/silod.lock`
/// - `/tmp/foo/test.sock` -> `/tmp/foo/test.lock`
/// - `/tmp/foo/sock` -> `/tmp/foo/sock.lock`
pub fn derive_lock_path(socket_path: &Path) -> PathBuf {
    if socket_path.extension().is_some() {
        socket_path.with_extension("lock")
    } else {
        let mut os = socket_path.as_os_str().to_os_string();
        os.push(".lock");
        PathBuf::from(os)
    }
}

/// Exclusive non-blocking `flock(LOCK_EX | LOCK_NB)` RAII guard preventing duplicate `silod` instances.
/// Intentionally NEVER unlinks the lockfile on drop (unlinking a flocked path creates a classic inode race).
pub struct SingletonLockGuard {
    file: File,
}

impl SingletonLockGuard {
    pub fn acquire(lock_path: &Path) -> Result<Self, String> {
        if let Some(parent) = lock_path.parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| format!("mkdir lock parent: {}", e))?;
                let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
            }
        }
        let file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .truncate(false)
            .mode(0o600)
            .open(lock_path)
            .map_err(|e| format!("open singleton lock {}: {}", lock_path.display(), e))?;
        let _ = fs::set_permissions(lock_path, fs::Permissions::from_mode(0o600));

        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc != 0 {
            let err = io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EWOULDBLOCK)
                || err.kind() == io::ErrorKind::WouldBlock
            {
                return Err(format!(
                    "another silod instance is already running (lock held: {})",
                    lock_path.display()
                ));
            }
            return Err(format!("flock failed on {}: {}", lock_path.display(), err));
        }
        Ok(Self { file })
    }
}

impl Drop for SingletonLockGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

struct UmaskGuard {
    prev: libc::mode_t,
}

impl UmaskGuard {
    fn restrict_077() -> Self {
        let prev = unsafe { libc::umask(0o077) };
        Self { prev }
    }
}

impl Drop for UmaskGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = libc::umask(self.prev);
        }
    }
}

struct SocketUnlinkGuard<'a> {
    socket_path: &'a Path,
    bound_dev_ino: Option<(u64, u64)>,
}

impl Drop for SocketUnlinkGuard<'_> {
    fn drop(&mut self) {
        unlink_socket_if_matches(self.socket_path, self.bound_dev_ino);
    }
}

fn unlink_socket_if_matches(socket_path: &Path, expected_dev_ino: Option<(u64, u64)>) {
    let Some((exp_dev, exp_ino)) = expected_dev_ino else {
        return;
    };
    if let Ok(meta) = fs::symlink_metadata(socket_path) {
        if meta.dev() == exp_dev && meta.ino() == exp_ino {
            let _ = fs::remove_file(socket_path);
        }
    }
}
```

#### Edit 3.3 — `run_ipc_server` & `run_ipc_server_with_lock` (Lines 90–151)
Replace lines 90–151:
```rust
/// Starts the root-only Unix domain IPC server, acquiring the singleton `flock` next to `socket_path`.
pub fn run_ipc_server<H>(
    socket_path: &Path,
    stop: Arc<AtomicBool>,
    handler: Arc<H>,
) -> Result<(), String>
where
    H: Fn(IpcRequest) -> IpcResponse + Send + Sync + 'static,
{
    let lock = SingletonLockGuard::acquire(&derive_lock_path(socket_path))?;
    run_ipc_server_with_lock(socket_path, &lock, stop, handler)
}

/// Starts the IPC server when the caller (`run_daemon`) already holds `SingletonLockGuard`
/// so the lock remains held across startup subsystem sync AND shutdown cleanup (`thaw_all`).
pub fn run_ipc_server_with_lock<H>(
    socket_path: &Path,
    _lock: &SingletonLockGuard,
    stop: Arc<AtomicBool>,
    handler: Arc<H>,
) -> Result<(), String>
where
    H: Fn(IpcRequest) -> IpcResponse + Send + Sync + 'static,
{
    if let Some(parent) = socket_path.parent() {
        if !parent.as_os_str().is_empty() {
            fs::create_dir_all(parent).map_err(|e| format!("mkdir socket parent: {}", e))?;
            let _ = fs::set_permissions(parent, fs::Permissions::from_mode(0o700));
        }
    }

    let (listener, bound_dev_ino) = {
        let _umask_mtx = BIND_UMASK_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let _umask = UmaskGuard::restrict_077();
        if fs::symlink_metadata(socket_path).is_ok() {
            let _ = fs::remove_file(socket_path);
        }
        let listener = UnixListener::bind(socket_path)
            .map_err(|e| format!("bind unix socket {}: {}", socket_path.display(), e))?;
        let _ = fs::set_permissions(socket_path, fs::Permissions::from_mode(0o600));
        let dev_ino = fs::symlink_metadata(socket_path)
            .ok()
            .map(|m| (m.dev(), m.ino()));
        (listener, dev_ino)
    };

    let _cleanup_guard = SocketUnlinkGuard {
        socket_path,
        bound_dev_ino,
    };

    listener
        .set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking listener: {}", e))?;

    let active_clients = Arc::new(AtomicUsize::new(0));

    while !stop.load(Ordering::Relaxed) {
        match listener.accept() {
            Ok((stream, _)) => {
                if active_clients.load(Ordering::SeqCst) >= MAX_CONCURRENT_CLIENTS {
                    drop(stream);
                    continue;
                }
                if verify_peer_cred(&stream).is_err() {
                    drop(stream);
                    continue;
                }
                let _ = stream.set_nonblocking(false);
                let _ = stream.set_read_timeout(Some(SERVER_READ_TIMEOUT));
                let _ = stream.set_write_timeout(Some(SERVER_WRITE_TIMEOUT));

                let h = Arc::clone(&handler);
                let stop_c = Arc::clone(&stop);
                let counter = Arc::clone(&active_clients);
                counter.fetch_add(1, Ordering::SeqCst);

                thread::spawn(move || {
                    handle_client(stream, stop_c, h);
                    counter.fetch_sub(1, Ordering::SeqCst);
                });
            }
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(40));
            }
            Err(_) => {
                thread::sleep(Duration::from_millis(100));
            }
        }
    }

    Ok(())
}
```
*In `handle_client` (lines 183–191)*: also return a clean JSON error response on `InvalidData` (e.g. frame > 4 MiB or bad UTF-8) before breaking:
```rust
            Err(e) if e.kind() == io::ErrorKind::InvalidData => {
                let resp = IpcResponse::err(0, format!("IPC framing error: {}", e));
                if let Ok(mut out) = serde_json::to_string(&resp) {
                    out.push('\n');
                    let _ = writer.write_all(out.as_bytes());
                    let _ = writer.flush();
                }
                break;
            }
            Err(_) => break,
```

#### Edit 3.4 — `run_stdio_bridge`, `run_stdio_bridge_io`, `run_single_rpc`, `connect_socket` (`CB-04`, `CB-23`, `D-24`, Lines 195–279)
Replace lines 195–270:
```rust
fn exchange_on_conn(conn: &mut BridgeConn, payload: &[u8]) -> io::Result<String> {
    let (sock_r, sock_w) = conn;
    sock_w.write_all(payload)?;
    sock_w.flush()?;
    match read_line_bounded(sock_r, MAX_IPC_RESPONSE_BYTES)? {
        Some(resp) => Ok(resp),
        None => Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "silod closed connection unexpectedly",
        )),
    }
}

fn is_reconnectable_err(e: &io::Error) -> bool {
    matches!(
        e.kind(),
        io::ErrorKind::BrokenPipe
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionAborted
            | io::ErrorKind::UnexpectedEof
            | io::ErrorKind::NotConnected
    )
}

/// Persistent stdio-to-Unix-socket bridge invoked by `Silo.apk` via `su -c silod --bridge`.
/// Keeps `/data/adb/silo/silod.sock` strictly `0600` root-only without modifying SELinux policies!
pub fn run_stdio_bridge(socket_path: &Path) -> i32 {
    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut stdin_reader = BufReader::new(stdin.lock());
    let mut stdout_lock = stdout.lock();
    run_stdio_bridge_io(socket_path, &mut stdin_reader, &mut stdout_lock)
}

/// Testable core stdio bridge loop supporting transparent single reconnect+retry on cached-socket failure.
pub fn run_stdio_bridge_io<R: BufRead, W: Write>(
    socket_path: &Path,
    stdin_reader: &mut R,
    stdout_writer: &mut W,
) -> i32 {
    let mut conn: Option<BridgeConn> = connect_socket(socket_path).ok();

    loop {
        let line = match read_line_bounded(stdin_reader, MAX_IPC_LINE_BYTES) {
            Ok(Some(l)) => l,
            Ok(None) => break,
            Err(e) => {
                let err_line = make_bridge_err("", &format!("IPC framing error: {}", e));
                let _ = stdout_writer.write_all(err_line.as_bytes());
                let _ = stdout_writer.write_all(b"\n");
                let _ = stdout_writer.flush();
                break;
            }
        };

        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let mut payload = String::with_capacity(trimmed.len() + 1);
        payload.push_str(trimmed);
        payload.push('\n');

        let had_cached_conn = conn.is_some();
        if conn.is_none() {
            conn = connect_socket(socket_path).ok();
        }

        let response_line = match conn.as_mut() {
            Some(active) => match exchange_on_conn(active, payload.as_bytes()) {
                Ok(resp) => resp,
                Err(first_err) if had_cached_conn && is_reconnectable_err(&first_err) => {
                    // Cached socket died (e.g., silod restarted): transparently reconnect once and retry
                    conn = connect_socket(socket_path).ok();
                    match conn.as_mut() {
                        Some(fresh) => match exchange_on_conn(fresh, payload.as_bytes()) {
                            Ok(resp) => resp,
                            Err(retry_err) => {
                                conn = None;
                                make_bridge_err(
                                    trimmed,
                                    &format!("lost connection to silod daemon: {}", retry_err),
                                )
                            }
                        },
                        None => make_bridge_err(
                            trimmed,
                            "silod daemon is not running (ensure Silo KernelSU module is installed & enabled)",
                        ),
                    }
                }
                Err(err) => {
                    conn = None;
                    let msg = if err.kind() == io::ErrorKind::TimedOut
                        || err.kind() == io::ErrorKind::WouldBlock
                    {
                        "silod request timed out".to_string()
                    } else {
                        format!("silod IPC error: {}", err)
                    };
                    make_bridge_err(trimmed, &msg)
                }
            },
            None => make_bridge_err(
                trimmed,
                "silod daemon is not running (ensure Silo KernelSU module is installed & enabled)",
            ),
        };

        if stdout_writer.write_all(response_line.as_bytes()).is_err()
            || stdout_writer.write_all(b"\n").is_err()
            || stdout_writer.flush().is_err()
        {
            break;
        }
    }
    0
}

/// Sends a single JSON-RPC request to the running daemon and prints the response line.
pub fn run_single_rpc(socket_path: &Path, json_req: &str) -> Result<String, String> {
    let (mut r, mut w) =
        connect_socket(socket_path).map_err(|e| format!("cannot connect to silod: {}", e))?;
    let mut payload = json_req.trim().to_string();
    payload.push('\n');
    w.write_all(payload.as_bytes())
        .and_then(|_| w.flush())
        .map_err(|e| format!("write request failed: {}", e))?;
    read_line_bounded(&mut r, MAX_IPC_RESPONSE_BYTES)
        .map_err(|e| format!("read response failed: {}", e))?
        .ok_or_else(|| "empty response from silod".to_string())
}

fn connect_socket(socket_path: &Path) -> io::Result<BridgeConn> {
    let stream = UnixStream::connect(socket_path)?;
    stream.set_read_timeout(Some(BRIDGE_READ_TIMEOUT))?;
    stream.set_write_timeout(Some(BRIDGE_WRITE_TIMEOUT))?;
    let writer = stream.try_clone()?;
    Ok((BufReader::new(stream), writer))
}
```
*Safety Guarantee*: Retries ONCE **only** when `had_cached_conn == true` AND `is_reconnectable_err` (broken pipe / unexpected EOF / connection reset). Does NOT blindly retry on `TimedOut` (preventing double execution of a slow mutating RPC) or `InvalidData`.

---

### File 4: `daemon/src/main.rs`

#### Edit 4.1 — Imports & OOM Helper (`CB-18`)
Update imports:
- From `crate::config`: add `is_valid_perm_category`, `ScopesConfig`.
- From `crate::ipc`: import `derive_lock_path, run_ipc_server_with_lock, run_single_rpc, run_stdio_bridge, SingletonLockGuard`.
- From `crate::protocol`: add `PrivateDnsState, RadioPrivacyState, SensorPrivacyState`.
- From `crate::scopes`: keep existing imports + coordinate new helpers from Explorer 1 (see § 3 Cross-Explorer Interface Contracts):
  - `set_category_lock_flags` (or `apply_locked_deny_flags` / `clear_locked_deny_flags`)
  - `reset_scopes_for_uninstall`
- From `crate::sys`: remove unused `read_uid_package_map` from `main.rs` (remains used in `sentinel.rs`); keep `boottime_secs, detect_device_locked, detect_screen_on, foreground_app_uids, is_ce_unlocked` (and optionally `top_foreground_app_uids` if Explorer 3 adds it!).
- Add `std::ffi::CString`, `std::os::fd::RawFd`, `std::sync::RwLock`.

Best-effort OOM protection helper (`CB-18`):
```rust
fn set_oom_score_adj(score: i32) {
    let _ = fs::write("/proc/self/oom_score_adj", format!("{}\n", score));
}
```
(Called at the very top of `run_daemon`: `set_oom_score_adj(-900);` — non-fatal if unprivileged in host tests.)

#### Edit 4.2 — `CachedSystemState`, `UidPackageIndex`, `FirewallRuntime`, `DaemonState`, `DaemonContext` (`D-04`, `D-10`, `CB-04`, `CB-12`)

Replace `struct DaemonState` and add decoupled concurrency structures:
```rust
#[derive(Debug, Clone, Default)]
struct CachedSystemState {
    sensor_privacy: SensorPrivacyState,
    private_dns: PrivateDnsState,
    radio_privacy: RadioPrivacyState,
    frozen_uids_count: usize,
    suspended_pkgs_count: usize,
    auto_reboot_remaining_secs: Option<u64>,
    enforcer_actions_count: u64,
    last_enforcer_event: Option<String>,
}

/// Multi-package UID index parsed from `/data/system/packages.list`.
/// Handles shared UIDs (multiple packages sharing one UID) without ever locking `state`.
#[derive(Debug, Clone, Default)]
pub(crate) struct UidPackageIndex {
    pub uid_to_pkgs: HashMap<u32, Vec<String>>,
    pub pkg_to_uid: HashMap<String, u32>,
    pub primary_uid_map: HashMap<u32, String>,
}

impl UidPackageIndex {
    pub fn from_packages_list_str(content: &str) -> Self {
        let mut uid_to_pkgs: HashMap<u32, Vec<String>> = HashMap::new();
        let mut pkg_to_uid: HashMap<String, u32> = HashMap::new();
        let mut primary_uid_map: HashMap<u32, String> = HashMap::new();
        for line in content.lines() {
            let mut parts = line.split_whitespace();
            let Some(pkg) = parts.next() else { continue };
            let Some(uid_str) = parts.next() else { continue };
            let Ok(uid) = uid_str.parse::<u32>() else {
                continue;
            };
            let pkg_s = pkg.to_string();
            pkg_to_uid.insert(pkg_s.clone(), uid);
            primary_uid_map.entry(uid).or_insert_with(|| pkg_s.clone());
            let list = uid_to_pkgs.entry(uid).or_default();
            if !list.iter().any(|p| p == pkg) {
                list.push(pkg_s);
            }
        }
        Self {
            uid_to_pkgs,
            pkg_to_uid,
            primary_uid_map,
        }
    }

    pub fn load_from_system() -> Self {
        match fs::read_to_string("/data/system/packages.list") {
            Ok(content) => Self::from_packages_list_str(&content),
            Err(_) => Self::default(),
        }
    }

    /// Authoritative check: protected if UID itself is system/isolated OR ANY package sharing it is protected.
    pub fn is_uid_protected(&self, uid: u32) -> bool {
        is_protected_uid(uid) || self.has_protected_pkg(uid)
    }

    pub fn has_protected_pkg(&self, uid: u32) -> bool {
        self.uid_to_pkgs
            .get(&uid)
            .map(|pkgs| pkgs.iter().any(|p| is_protected_package(p)))
            .unwrap_or(false)
    }

    pub fn primary_pkg(&self, uid: u32) -> Option<String> {
        self.primary_uid_map.get(&uid).cloned()
    }

    pub fn uid_for_pkg(&self, pkg: &str) -> Option<u32> {
        self.pkg_to_uid.get(pkg).copied()
    }

    pub fn user_id_for_pkg(&self, pkg: &str) -> u32 {
        self.uid_for_pkg(pkg).map(|u| u / 100_000).unwrap_or(0)
    }
}

#[derive(Debug, Default)]
struct FirewallRuntime {
    previously_blocked_uids: HashSet<u32>,
}

/// Fast in-memory config & status metadata ONLY.
/// HARD INVARIANT: `DaemonContext::state` is NEVER held across any `run_cmd`, Netlink I/O, or blocking syscall!
struct DaemonState {
    config: SiloConfig,
    config_path: PathBuf,
    boot_fail_marker: PathBuf,
    started_boottime: u64,
    boot_guard_armed: bool,
    boot_guard_cleared: bool,
    caps: KernelCapabilities,
    firewall_active: bool,
    ipset_entries_loaded: usize,
    ipset_backend: String,
    vpn_active: bool,
    active_interfaces: ActiveInterfaces,
    last_firewall_error: Option<String>,
    temp_unblock_until: BTreeMap<u32, u64>,
    screen_on: bool,
    device_locked: bool,
    ce_unlocked: bool,
    known_user_uids: HashSet<u32>,
    last_packages_mtime: Option<SystemTime>,
}

/// Strict Lock Ordering Invariant (deadlock-free by construction):
///   Level 1 (Slow/subprocess locks — never acquire two simultaneously EXCEPT `RunLiveProof` which locks
///            `hardening_mtx` -> `freezer_mtx` strictly in that order):
///     `firewall_mtx`, `hardening_mtx`, `freezer_mtx`, `automation_mtx`, `clipboard_mtx`, `enforcer_mtx`
///   Level 2 (Leaf fast in-memory locks — NEVER acquire any Level 1 lock while holding Level 2;
///            NEVER run subprocesses or blocking I/O while holding Level 2):
///     `state`, `sys_cache`, `uid_index`, `telemetry`
struct DaemonContext {
    state: Mutex<DaemonState>,
    firewall_mtx: Mutex<FirewallRuntime>,
    hardening_mtx: Mutex<HardeningEngine>,
    freezer_mtx: Mutex<FreezerEngine>,
    automation_mtx: Mutex<AutomationEngine>,
    clipboard_mtx: Mutex<ClipboardGuard>,
    enforcer_mtx: Mutex<PermissionEnforcer>,
    telemetry: Mutex<TelemetryRing>,
    sys_cache: RwLock<CachedSystemState>,
    uid_index: RwLock<UidPackageIndex>,
    enforcer_notify: AtomicBool,
}
```

Key `DaemonContext` helper methods:
1. `is_uid_protected(&self, uid: u32) -> bool`:
   ```rust
   fn is_uid_protected(&self, uid: u32) -> bool {
       if is_protected_uid(uid) {
           return true;
       }
       self.uid_index
           .read()
           .map(|idx| idx.has_protected_pkg(uid))
           .unwrap_or(false)
   }
   ```
2. `build_status(&self) -> Result<DaemonStatus, String>` (**0 subprocesses, <1ms**):
   ```rust
   fn build_status(&self) -> Result<DaemonStatus, String> {
       let total_blocked = self
           .telemetry
           .lock()
           .map(|t| t.total_blocked())
           .unwrap_or(0);
       let cached = self
           .sys_cache
           .read()
           .map(|c| c.clone())
           .unwrap_or_default();
       let st = self
           .state
           .lock()
           .map_err(|_| "state lock poisoned".to_string())?;
       Ok(st.status_from_cache(total_blocked, &cached))
   }
   ```
   Inside `DaemonState::status_from_cache(&self, total_blocked: u64, cached: &CachedSystemState) -> DaemonStatus`:
   - Uses `cached.sensor_privacy`, `cached.private_dns`, `cached.radio_privacy`, `cached.frozen_uids_count`, `cached.suspended_pkgs_count`, `cached.auto_reboot_remaining_secs`, `cached.enforcer_actions_count`, `cached.last_enforcer_event`.
   - Sets `panic_lockdown_active: self.config.firewall.panic_lockdown_active`.
   - Zero subprocesses, zero Level 1 locks!
3. `sync_firewall(&self) -> Result<(), String>` (**zero subprocesses under `state` + Panic Lockdown defense-in-depth `D-06`**):
   ```rust
   fn sync_firewall(&self) -> Result<(), String> {
       let mut fw_rt = self
           .firewall_mtx
           .lock()
           .map_err(|_| "firewall lock poisoned".to_string())?;

       // Brief snapshot under `state`, then DROP `state` before invoking iptables/ipset!
       let (fw_cfg, mut caps, screen_on, temp_set) = {
           let st = self
               .state
               .lock()
               .map_err(|_| "state lock poisoned".to_string())?;
           (
               st.config.firewall.clone(),
               st.caps.clone(),
               st.screen_on,
               st.active_temp_unblock_set(),
           )
       };

       // Defense-in-depth for D-06: while panic_lockdown_active is true, force DefaultDeny and
       // ignore all saved per-app allow rules / temp unblocks WITHOUT mutating persisted config.firewall.rules!
       let mut effective_fw = fw_cfg.clone();
       let effective_temp = if effective_fw.panic_lockdown_active {
           effective_fw.mode = FirewallMode::DefaultDeny;
           effective_fw.rules.clear();
           HashSet::new()
       } else {
           temp_set
       };

       let apply_res = apply_firewall(
           &effective_fw,
           &mut caps,
           screen_on,
           &effective_temp,
           &mut fw_rt.previously_blocked_uids,
       );

       // Brief commit under `state`
       if let Ok(mut st) = self.state.lock() {
           st.caps = caps;
           match &apply_res {
               Ok(res) => {
                   st.firewall_active = effective_fw.enabled;
                   st.ipset_entries_loaded = res.ipset_entries_loaded;
                   st.ipset_backend = res.ipset_backend.clone();
                   st.vpn_active = res.vpn_active;
                   st.active_interfaces = res.active_interfaces.clone();
                   st.last_firewall_error = res.warning.clone();
               }
               Err(e) => {
                   st.last_firewall_error = Some(e.clone());
               }
           }
       }

       apply_res.map(|_| ())
   }
   ```
4. `refresh_cached_system_state(&self)` (**runs OUTSIDE `state`**):
   ```rust
   fn refresh_cached_system_state(&self) {
       let sp = self
           .automation_mtx
           .lock()
           .map(|a| a.live_sensor_privacy())
           .unwrap_or_default();
       let dns = get_private_dns();
       let rp = get_radio_privacy();
       if let Ok(mut c) = self.sys_cache.write() {
           c.sensor_privacy = sp;
           c.private_dns = dns;
           c.radio_privacy = rp;
       }
   }
   ```
5. `sync_all_subsystems(&self)` (**runs OUTSIDE `state`, fixes `CB-10`**):
   - Calls `let _ = self.sync_firewall();`.
   - Snapshots `(cfg_h, cfg_scopes)` under brief `state` lock -> DROPS `state`.
   - If `cfg_h.enabled`: locks `hardening_mtx` -> `h.apply(&cfg_h)`.
   - Legacy scopes non-overlap (`CB-10`): builds `let mut legacy_only = cfg_scopes.clone(); legacy_only.apps.retain(|pkg, _| !cfg_scopes.guards.contains_key(pkg));` -> calls `sync_all_scopes(&legacy_only)` so legacy v2 `scopes.apps` NEVER overwrites v3 guarded apps!
   - Signals `self.enforcer_notify.store(true, Ordering::Release)` so the dedicated `PermissionEnforcer` thread runs startup/reload enforcement of `cfg.scopes.guards.locked_deny` asynchronously!

#### Edit 4.3 — All Critical `dispatch_command` Fixes (`main.rs:323-1005`)

Every single one of the 49 commands follows the zero-subprocess-under-`state` pattern. Highlighted critical handlers:

1. **`GetStatus`**: calls `ctx.build_status()` (<1ms, 0 subprocesses, 0 Level 1 locks).
2. **`ApplyPreset { preset }`**:
   - Brief `state` lock: `apply_preset(preset)` (clears `panic_lockdown_active` + all stale automation/hardening fields), `sanitize()?`, `save_config()?`, snapshot `(saved_cfg, cfg_h, freezer_enabled)` -> DROP `state`.
   - `sync_firewall(ctx)` OUTSIDE `state`.
   - `hardening_mtx`: `apply(&cfg_h)` if enabled else `rollback_all(&cfg_h)`.
   - If `!freezer_enabled`: `freezer_mtx`: `thaw_all()`, update `sys_cache` counts.
   - `sys_cache.write()`: `auto_reboot_remaining_secs = None`.
3. **`SetFirewallConfig { mut config }`**:
   - Pre-strip protected UIDs using `ctx.is_uid_protected(uid)` (checks `uid_index` too!).
   - Brief `state` lock: `probe_cfg.firewall = std::mem::take(&mut config);` -> if `!probe_cfg.firewall.enabled || probe_cfg.firewall.mode != FirewallMode::DefaultDeny` force `panic_lockdown_active = false` -> `probe_cfg.sanitize()?` -> commit & `save_config()?` -> snapshot `saved_fw` -> DROP `state`.
   - `sync_firewall(ctx)` OUTSIDE `state`.
4. **`SetAppFirewallRule { uid, rule }`** (`D-10`, `CB-12`):
   - Reject if `is_protected_uid(uid) || is_protected_package(&rule.package_name) || ctx.is_uid_protected(uid)`.
   - Brief `state` lock: insert rule & `save_config()?` -> DROP `state`.
   - `sync_firewall(ctx)` OUTSIDE `state`.
5. **`EmergencyFlushFirewall`** (`D-06`):
   - Brief `state` lock: `enabled = false`, `panic_lockdown_active = false`, `temp_unblock_until.clear()`, `save_config()`, clone `caps` -> DROP `state`.
   - `firewall_mtx`: `flush_firewall(&caps)`, `previously_blocked_uids.clear()`.
   - Brief `state` lock: `firewall_active = false`, `ipset_entries_loaded = 0`, `ipset_backend = "disabled"`, `last_firewall_error = None`.
6. **`KillUidSockets { uid }`** (`D-10`, `CB-12`):
   - Reject if `ctx.is_uid_protected(uid)`.
   - `destroy_uid_sockets(uid)` outside all locks.
7. **`KillConnection { family, local_ip, local_port, remote_ip, remote_port }`** (`D-10`, `CB-12`, zero edits to `netlink.rs`):
   ```rust
   let primary_map = ctx
       .uid_index
       .read()
       .map(|i| i.primary_uid_map.clone())
       .unwrap_or_default();
   let active = list_active_connections(&primary_map);
   let fam_matches = |conn_fam: u8| -> bool {
       match family {
           4 => conn_fam == libc::AF_INET as u8 || conn_fam == libc::AF_INET6 as u8,
           6 => conn_fam == libc::AF_INET6 as u8,
           f if f == libc::AF_INET as u8 => {
               conn_fam == libc::AF_INET as u8 || conn_fam == libc::AF_INET6 as u8
           }
           f if f == libc::AF_INET6 as u8 => conn_fam == libc::AF_INET6 as u8,
           _ => true,
       }
   };
   let matching: Vec<_> = active
       .into_iter()
       .filter(|c| {
           (c.protocol == "TCP" || c.protocol == "TCP6")
               && fam_matches(c.family)
               && c.local_port == local_port
               && c.remote_port == remote_port
               && c.local_ip == local_ip
               && c.remote_ip == remote_ip
       })
       .collect();

   if matching.is_empty() {
       return IpcResponse::ok(id, serde_json::json!({ "killed": 0 }));
   }
   for c in &matching {
       if ctx.is_uid_protected(c.uid)
           || c.package_name.as_deref().map(is_protected_package).unwrap_or(false)
       {
           return IpcResponse::err(
               id,
               format!("Refused to kill connection owned by protected UID {}", c.uid),
           );
       }
   }
   if matching
       .iter()
       .all(|c| c.is_listening || c.state == "LISTEN" || c.state == "TIME_WAIT")
   {
       return IpcResponse::ok(id, serde_json::json!({ "killed": 0 }));
   }
   match destroy_matching_connection(family, &local_ip, local_port, &remote_ip, remote_port) {
       Ok(killed) => IpcResponse::ok(id, serde_json::json!({ "killed": killed })),
       Err(e) => IpcResponse::err(id, e),
   }
   ```
   *(Also extract the matching/protection check into a pure testable function `classify_kill_connection_target(...)` for unit tests!)*
8. **`SetFreezerEntry { uid, entry }` & `FreezeUidNow { uid }`** (`D-10`, `CB-12`):
   - Reject if `is_protected_uid(uid) || ctx.is_uid_protected(uid) || is_protected_package(&pkg)`.
   - Execute `freezer.freeze_uid_now(...)` / `thaw_uid_now(...)` / `thaw_all()` under `freezer_mtx` OUTSIDE `state`, then immediately update `sys_cache.{frozen_uids_count, suspended_pkgs_count}`.
9. **`SetAppPermissionAction { package_name, category, action }`** (`D-05`, `CB-02`, `CB-03`):
   - Validate `validate_package_name(&package_name) && !is_protected_package(&package_name)` and `is_valid_perm_category(&category)` up front.
   - Normalize `clean_action = action.trim().to_ascii_lowercase()`.
   - If `matches!(clean_action.as_str(), "allow" | "default" | "foreground")`:
     - Under brief `state` lock BEFORE invoking subprocesses (preventing `inotify` race with `PermissionEnforcer`!):
       - If `st.config.scopes.guards.get_mut(&package_name)` has `category` in `locked_deny`:
         - remove `category` from `locked_deny`;
         - if `guard.is_empty()`, remove `package_name` from `guards`;
         - `let _ = st.save_config();`
         - `ctx.enforcer_notify.store(true, Ordering::Release);`
       - Remember `unlocked_from_guard = true`.
     - DROP `state`!
   - Run `set_app_permission_action(&package_name, &category, &action)` OUTSIDE `state`!
   - *(Note: if `set_app_permission_action` returns `Err` for e.g. invalid/unsupported `"foreground"` on a non-foreground category, restore the removed `locked_deny` entry if `unlocked_from_guard` was true so a rejected call never silently unlocks `Kunci Blokir`!)*
10. **`SetAppPermGuard { package_name, guard }`** (`D-05`, `CB-03`, `CB-04`):
    - Reject if `!validate_package_name(&package_name) || is_protected_package(&package_name)` OR if `uid_index` resolves `package_name` to a protected UID.
    - Brief `state` lock:
      - Capture `prev_locked: HashSet<String>` from existing `guards.get(&package_name)`.
      - Insert or remove `guard`; call `st.config.sanitize()` (enforces `locked_deny` stripping duplicates from `auto_revoke_on_bg`!).
      - Capture `new_locked: HashSet<String>` from sanitized `guards.get(&package_name)`.
      - `st.save_config()?`; clone `saved_scopes = st.config.scopes.clone()`.
      - DROP `state`!
    - Compute exact delta OUTSIDE `state`:
      - `newly_added_locks: Vec<String>` = `new_locked.difference(&prev_locked)`
      - `removed_locks: Vec<String>` = `prev_locked.difference(&new_locked)`
    - Resolve `user_id = ctx.uid_index.read().map(|i| i.user_id_for_pkg(&package_name)).unwrap_or(0)`.
    - Apply delta OUTSIDE `state`:
      - For `cat` in `&newly_added_locks`:
        - `let _ = set_app_permission_action(&package_name, cat, "revoke");`
        - `set_category_user_fixed(&package_name, cat, true, user_id);` (from Explorer 1's `scopes.rs`)
      - For `cat` in `&removed_locks`:
        - `set_category_user_fixed(&package_name, cat, false, user_id);`
    - `ctx.enforcer_notify.store(true, Ordering::Release);`
    - Return `IpcResponse::ok(id, saved_scopes)`.
11. **`SetSensorsOff` / `SetSensorPrivacy` / `SetPrivateDns` / `SetRadioPrivacy`**:
    - Run OUTSIDE `state` (`automation_mtx` for sensors; no lock needed for DNS/radio).
    - Immediately update `sys_cache.write()` with the returned live state so the very next `<1ms` `GetStatus` reflects the change instantaneously!
12. **`TriggerPanicLockdown`** (`D-06`, `D-04`):
    - Brief `state` lock: set `firewall.enabled = true`, `firewall.mode = FirewallMode::DefaultDeny`, `firewall.panic_lockdown_active = true`, `temp_unblock_until.clear()`, `save_config()`, read `sd_avail` -> DROP `state`!
    - Outside `state`: `sync_firewall(ctx)`, `destroy_all_user_sockets()`, `automation_mtx.set_sensor_privacy("all", true)` + update `sys_cache.sensor_privacy`, `disable_wifi_and_bt_now()`, `clipboard_mtx.clear_now()`.
13. **`RunLiveProof`**:
    - Brief `state` lock: clone `(cfg_clone, caps_clone)` -> DROP `state`!
    - Acquire `hardening_mtx` then `freezer_mtx` (strict Level 1 order) -> `run_live_proof(&cfg_clone, &caps_clone, &h, &f)` -> drop both.
14. **`ImportConfigJson`**:
    - Parse & sanitize outside locks; strip any UIDs protected in `uid_index`; brief `state` commit + `save_config()?`; call `sync_all_subsystems(ctx)` OUTSIDE `state`.

#### Edit 4.4 — Device Lock Preservation Helper (`D-07`, `CB-06`)
Add pure testable helpers in `main.rs`:
```rust
pub(crate) fn should_poll_device_locked(
    screen_now: bool,
    prev_screen: bool,
    prev_locked: bool,
    tick_count: u64,
) -> bool {
    !screen_now || prev_locked || (prev_screen != screen_now) || tick_count.is_multiple_of(3)
}

pub(crate) fn compute_device_locked<F: FnOnce() -> bool>(
    screen_now: bool,
    prev_screen: bool,
    prev_locked: bool,
    tick_count: u64,
    poll: F,
) -> bool {
    if should_poll_device_locked(screen_now, prev_screen, prev_locked, tick_count) {
        poll()
    } else {
        prev_locked
    }
}
```
Use in Supervisor thread (replacing lines 1080–1085):
```rust
let (prev_screen, prev_locked) = ctx_sup
    .state
    .lock()
    .map(|s| (s.screen_on, s.device_locked))
    .unwrap_or((true, false));
let screen_now = detect_screen_on();
let locked_now = compute_device_locked(
    screen_now,
    prev_screen,
    prev_locked,
    tick_count,
    detect_device_locked,
);
```
*Guaranteed Fix*: `locked_now` is NEVER hardcoded to `false`. While locked (`prev_locked == true`), notification wakeups and Samsung AOD cycles ALWAYS poll `detect_device_locked()` instead of flipping `device_locked` to `false`.

#### Edit 4.5 — Dedicated `PermissionEnforcer` Thread with `inotify` + 1s Watcher (`D-05`, `CB-03`, `CB-10`)
Spawned in `run_daemon`:
```rust
struct InotifyWatcher {
    fd: RawFd,
}

impl InotifyWatcher {
    fn new(dirs: &[&str]) -> Option<Self> {
        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            return None;
        }
        let mask = libc::IN_MOVED_TO | libc::IN_CLOSE_WRITE | libc::IN_CREATE;
        let mut watched = 0usize;
        for &dir in dirs {
            if let Ok(cpath) = CString::new(dir) {
                let wd = unsafe { libc::inotify_add_watch(fd, cpath.as_ptr(), mask) };
                if wd >= 0 {
                    watched += 1;
                }
            }
        }
        if watched == 0 {
            unsafe { libc::close(fd); }
            return None;
        }
        Some(Self { fd })
    }

    /// Waits up to `timeout_ms` for any filesystem event; drains all queued events and returns true if triggered.
    fn wait_and_drain(&self, timeout_ms: i32) -> bool {
        let mut pfd = libc::pollfd {
            fd: self.fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
        if rc <= 0 || (pfd.revents & libc::POLLIN) == 0 {
            return false;
        }
        let mut buf = [0u8; 4096];
        loop {
            let n = unsafe {
                libc::read(
                    self.fd,
                    buf.as_mut_ptr().cast::<libc::c_void>(),
                    buf.len(),
                )
            };
            if n <= 0 {
                break;
            }
        }
        true
    }
}

impl Drop for InotifyWatcher {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.fd);
        }
    }
}
```
Dedicated Enforcer Thread loop:
1. Watches `&["/data/misc_de/0/apexdata/com.android.permission", "/data/system/users/0", "/data/system"]` (gracefully degrades to timer loop on host Linux where those directories don't exist).
2. **Immediate startup pass**: snapshots `scopes_cfg` from `state` (drops `state`), snapshots `pkg_to_uid` from `uid_index` (drops `uid_index`), enforces all `locked_deny` entries via `enforcer_mtx`, updates `sys_cache.{enforcer_actions_count, last_enforcer_event}`.
3. **Steady-state loop** until `stop`:
   - Checks `let notified = ctx.enforcer_notify.swap(false, Ordering::AcqRel);`
   - Snapshots `(scopes_cfg, screen_on)` under brief `state` lock (<10µs) -> DROP `state`.
   - If `scopes_cfg.guards.is_empty()`: sleeps/polls 500ms and continues (zero CPU when idle!).
   - Waits via `inotify.wait_and_drain(500)` (or `thread::sleep(500ms)` if no inotify):
     - If `inotify_fired`: brief 150ms debounce + drain to let atomic rename settle, then runs enforcer immediately with `force_locked_sweep = true` (**<300ms reaction time!**).
     - Else if `notified`: runs immediately with `force_locked_sweep = true`.
     - Else if `screen_on` and `>= 1s` elapsed (or `!screen_on` and `>= 5s` elapsed): reads `fg_uids` (`top_foreground_app_uids()` if Explorer 3 adds it, else `foreground_app_uids()`) OUTSIDE `state`, clones `uid_to_pkgs` + `pkg_to_uid` from `uid_index.read()` (drops read guard!), locks `enforcer_mtx`, runs enforcer tick (checking any guarded foreground app immediately without 15s delay!), updates `sys_cache.{enforcer_actions_count, last_enforcer_event}`!

#### Edit 4.6 — CLI Parsing & `--uninstall-cleanup [--config PATH]` (`CB-19`, Lines 1188–1310)
Extract pure testable parser:
```rust
#[derive(Debug, Clone, PartialEq, Eq)]
enum CliMode {
    Daemon,
    Bridge,
    EmergencyFlush,
    UninstallCleanup,
    Status,
    Version,
    Once(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct CliArgs {
    mode: CliMode,
    socket_path: PathBuf,
    config_path: PathBuf,
    boot_fail_marker: PathBuf,
}

fn parse_cli_args(args: &[String]) -> CliArgs {
    let mut socket_path = PathBuf::from(DEFAULT_SOCKET_PATH);
    let mut config_path = PathBuf::from(DEFAULT_CONFIG_PATH);
    let mut boot_fail_marker = PathBuf::from(format!("{}/.boot_fail_count", DEFAULT_STATE_DIR));
    let mut mode = CliMode::Daemon;

    let mut i = 1usize;
    while i < args.len() {
        match args[i].as_str() {
            "--bridge" => mode = CliMode::Bridge,
            "--emergency-flush" => mode = CliMode::EmergencyFlush,
            "--uninstall-cleanup" => mode = CliMode::UninstallCleanup,
            "--status" => mode = CliMode::Status,
            "--version" => mode = CliMode::Version,
            "--once" => {
                let json = if i + 1 < args.len() {
                    i += 1;
                    args[i].clone()
                } else {
                    String::new()
                };
                mode = CliMode::Once(json);
            }
            "--socket" if i + 1 < args.len() => {
                i += 1;
                socket_path = PathBuf::from(&args[i]);
            }
            "--config" if i + 1 < args.len() => {
                i += 1;
                config_path = PathBuf::from(&args[i]);
            }
            "--boot-marker" if i + 1 < args.len() => {
                i += 1;
                boot_fail_marker = PathBuf::from(&args[i]);
            }
            other => {
                if let Some(v) = other.strip_prefix("--socket=") {
                    socket_path = PathBuf::from(v);
                } else if let Some(v) = other.strip_prefix("--config=") {
                    config_path = PathBuf::from(v);
                } else if let Some(v) = other.strip_prefix("--boot-marker=") {
                    boot_fail_marker = PathBuf::from(v);
                }
            }
        }
        i += 1;
    }

    CliArgs {
        mode,
        socket_path,
        config_path,
        boot_fail_marker,
    }
}
```
Shared cleanup helpers:
```rust
fn run_emergency_flush(config_path: &Path) {
    let caps = probe_capabilities();
    flush_firewall(&caps);
    if let Ok(entries) = fs::read_dir("/sys/fs/cgroup") {
        for e in entries.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with("uid_") {
                let direct = e.path().join("cgroup.freeze");
                if direct.exists() {
                    let _ = fs::write(&direct, "0");
                }
                if let Ok(pids) = fs::read_dir(e.path()) {
                    for p in pids.flatten() {
                        let pf = p.path().join("cgroup.freeze");
                        if pf.exists() {
                            let _ = fs::write(&pf, "0");
                        }
                    }
                }
            }
        }
    }
    if config_path.exists() {
        let cfg = SiloConfig::load_or_default(config_path);
        let pm = crate::sys::resolve_bin("pm");
        let am = crate::sys::resolve_bin("am");
        for (&uid, entry) in &cfg.freezer.entries {
            if validate_package_name(&entry.package_name)
                && !is_protected_package(&entry.package_name)
                && !is_protected_uid(uid)
            {
                let user_str = (uid / 100_000).to_string();
                let _ = crate::sys::run_cmd(
                    &pm,
                    &["unsuspend", "--user", &user_str, &entry.package_name],
                    None,
                    2500,
                );
                let _ = crate::sys::run_cmd(
                    &am,
                    &["set-standby-bucket", &entry.package_name, "active"],
                    None,
                    2500,
                );
            }
        }
    }
}

fn run_uninstall_cleanup(config_path: &Path) -> i32 {
    run_emergency_flush(config_path);
    let mut reset_count = 0usize;
    if config_path.exists() {
        let cfg = SiloConfig::load_or_default(config_path);
        let uid_idx = UidPackageIndex::load_from_system();
        reset_count = crate::scopes::reset_scopes_for_uninstall(&cfg.scopes, &uid_idx.pkg_to_uid);
    }
    println!(
        "silod: uninstall cleanup complete (firewall flushed, cgroups thawed, apps unsuspended, {} scope/guard entries reset)",
        reset_count
    );
    0
}
```
*Safety Detail*: `reset_scopes_for_uninstall` resets Silo-applied AppOps (`--uid` + package) to `"default"` and clears `user-fixed`/`revoked-compat` flags for all packages in `cfg.scopes.apps` and `cfg.scopes.guards`, WITHOUT silently running `pm grant`. Exits `0` cleanly even on host Linux where `iptables`/`pm`/`cmd` binaries are absent.

---

## 3. Cross-Explorer & M2 Compatibility Interface Matrix

| Interface | Provider | Consumer | Exact Contract |
|---|---|---|---|
| `CategoryPermLiveState` | `protocol.rs` (Exp 2) | `scopes.rs` (Exp 1), M3 Kotlin | Keeps `last_access: Option<String>`; adds 4 `#[serde(default)]` fields (`runtime_granted: Option<bool>`, `has_runtime_perms: bool`, `supports_foreground: bool`, `system_fixed: bool`). |
| `FirewallConfig.panic_lockdown_active` | `config.rs` (Exp 2) | `main.rs` (Exp 2), M2 `firewall.rs`, M3 Kotlin | `#[serde(default)] pub panic_lockdown_active: bool` (default `false`); `firewall.rs` tests use `..Default::default()` -> zero M2 compile impact. |
| `DaemonStatus.panic_lockdown_active` | `protocol.rs` (Exp 2) | `main.rs` (Exp 2), M3 Kotlin | `#[serde(default)] pub panic_lockdown_active: bool`. |
| `set_category_user_fixed(pkg, cat, locked, user_id)` | `scopes.rs` (Exp 1) | `main.rs` (`SetAppPermGuard` delta) | Sets (`locked=true`) or clears (`locked=false`) `user-fixed` flag on `spec.runtime_perms` via `pm set-permission-flags` / `pm clear-permission-flags --user <user_id>`. |
| `PermissionEnforcer::tick_with_index(...)` + `enforce_locked_startup(...)` | `scopes.rs` (Exp 1) | `main.rs` (Dedicated Enforcer thread) | Takes `&ScopesConfig`, `screen_on`, `fg_uids: &[u32]`, `uid_to_pkgs: &HashMap<u32, Vec<String>>`, `pkg_to_uid: &HashMap<String, u32>`, `force_locked_sweep: bool`. Keeps existing `PermissionEnforcer::tick` only if tested, or updates all callers/tests cleanly so zero `dead_code` warnings! |
| `reset_scopes_for_uninstall(cfg, pkg_to_uid) -> usize` | `scopes.rs` (Exp 1) | `main.rs` (`--uninstall-cleanup`) | Resets all `cfg.apps` + `cfg.guards` AppOps (`--uid` & pkg) to `default` + clears `user-fixed` & `revoked-compat` (no `pm grant`). |
| `top_foreground_app_uids() -> Vec<u32>` (optional) | `sys.rs` (Exp 3) | `main.rs` (Enforcer thread) | If Explorer 3 provides `top_foreground_app_uids()`, Enforcer thread uses it (`D-18`), while M2 `freezer.rs` keeps using `foreground_app_uids()`. |
| `netlink::destroy_matching_connection` | M2 `netlink.rs` (untouched) | `main.rs` (`KillConnection`) | Still invoked after `main.rs` verifies via `list_active_connections` that matching sockets are non-protected and non-`LISTEN`/`TIME_WAIT` -> zero `dead_code` warnings! |
| `telemetry::TelemetryRing::push` | M2 `telemetry.rs` (untouched) | `main.rs` (NFLOG thread) | Receives `&idx.primary_uid_map` under `uid_index.read()` -> zero `state` lock, zero map clone! |
| `sentinel::run_live_proof` | M2 `sentinel.rs` (untouched) | `main.rs` (`RunLiveProof`) | Receives cloned `(cfg, caps)` + `hardening_mtx` + `freezer_mtx` (strict order, outside `state`). |

---

## 4. Exact Unit Test Plan (For Step 1b Worker)

All tests use unique per-test temp paths (`format!("silo_<test_name>_{}", std::process::id())`) so parallel `cargo test` runs never collide, and clean up temp files/dirs on exit.

### A. `daemon/src/protocol.rs` Tests
1. `ipc_framing_constants_support_full_50k_blocklist`:
   - Assert `MAX_IPC_LINE_BYTES == 4 * 1024 * 1024` and `MAX_IPC_RESPONSE_BYTES == 8 * 1024 * 1024`.
2. `category_perm_live_state_backward_compatible_deserialization`:
   - Deserialize legacy 4-field JSON `{"category":"camera","appops_mode":"ignore","last_access":"2m","running_now":false}` -> succeeds with `runtime_granted == None`, `has_runtime_perms == false`, `supports_foreground == false`, `system_fixed == false`.
   - Round-trip full 8-field `CategoryPermLiveState` and assert exact JSON keys (`last_access`, `runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`).
3. `daemon_status_includes_default_panic_lockdown_active`:
   - Verify `panic_lockdown_active` serializes and deserializes cleanly (defaulting to `false` if absent).

### B. `daemon/src/config.rs` Tests
1. `preset_reset_matrix_leaves_zero_stale_toggles`:
   - Start with `Paranoid` + `firewall.panic_lockdown_active = true` + `automation.auto_reboot_enabled = true` + custom `hardening.tcp_congestion = "bbr"`.
   - Apply `Balanced`: assert `panic_lockdown_active == false`, `mode == DefaultAllow`, `wifi_auto_off_enabled == false`, `bluetooth_auto_off_enabled == false`, `sensors_off_on_lock == false`, `usb_adb_lock_guard == false`, `auto_reboot_enabled == false`, `block_cleartext_http_global == false`, `normalize_ttl_hl == false`, `block_new_apps == false`, `hardening.enabled == true` (all 11 hardening booleans `true`).
   - Apply `Paranoid` again, then apply `Safe`: assert `firewall.enabled == false`, `panic_lockdown_active == false`, `freezer.enabled == false`, `hardening.enabled == false`, `hardening.tcp_congestion.is_empty()`, `scopes.auto_clear_clipboard == false`, ALL 5 `automation.*_enabled == false`.
   - Apply `Paranoid` -> `Hardened`: assert `mode == DefaultAllow`, `panic_lockdown_active == false`, `sensors_off_on_lock == true`, `wifi_auto_off_enabled == false`, `bluetooth_auto_off_enabled == false`, `usb_adb_lock_guard == false`.
2. `sanitize_enforces_guard_mutual_exclusivity_and_panic_invariant`:
   - Insert guard with `locked_deny: ["camera", "invalid_cat", "camera"]` and `auto_revoke_on_bg: ["camera", "microphone", "microphone"]`.
   - Set `firewall.enabled = false`, `firewall.panic_lockdown_active = true`.
   - Run `sanitize()` -> assert `locked_deny == ["camera"]`, `auto_revoke_on_bg == ["microphone"]` (`"camera"` stripped!), and `firewall.panic_lockdown_active == false`.
3. `load_or_default_backs_up_corrupt_config_with_0600`:
   - Write malformed JSON `"{corrupt!!!"` to `<temp_dir>/config.json`.
   - Call `SiloConfig::load_or_default(&path)` -> assert default config returned AND a file matching `config.json.corrupt.*` exists in `<temp_dir>` containing `"{corrupt!!!"` with mode `& 0o777 == 0o600`.
   - Also test non-UTF-8 raw bytes `[0xff, 0xfe, 0x00, 0x80]` -> backed up identically!

### C. `daemon/src/ipc.rs` Tests
1. `derive_lock_path_handles_sock_and_custom_extensions`:
   - `/data/adb/silo/silod.sock` -> `/data/adb/silo/silod.lock`
   - `/tmp/a/test.sock` -> `/tmp/a/test.lock`
   - `/tmp/a/custom` -> `/tmp/a/custom.lock`
2. `singleton_lock_rejects_second_instance_and_releases_on_drop`:
   - Acquire `SingletonLockGuard::acquire(&lock_path)` -> `Ok(g1)`.
   - Second `SingletonLockGuard::acquire(&lock_path)` while `g1` alive -> `Err` mentioning already running.
   - Start `run_ipc_server` on matching `sock_path` while `g1` alive -> returns `Err` immediately!
   - `drop(g1)` -> third `SingletonLockGuard::acquire(&lock_path)` succeeds!
3. `shutdown_preserves_replaced_socket_inode`:
   - Start `run_ipc_server` on `sock_path`, wait for bind.
   - Replace `sock_path` with a new file (creating a different inode).
   - Signal `stop = true`, join server thread.
   - Assert `sock_path` STILL EXISTS (inode mismatch prevented accidental unlink!).
4. `four_mib_frame_accepted_and_oversized_rejected`:
   - Test `read_line_bounded` with a valid 2 MiB JSON line (`<= MAX_IPC_LINE_BYTES`) -> `Ok(Some(...))`.
   - Test `read_line_bounded` with a `MAX_IPC_LINE_BYTES + 1` byte buffer -> `Err(InvalidData)`.
   - Verify socket bind sets `0o600` mode (`meta.permissions().mode() & 0o777 == 0o600`).
5. `bridge_reconnects_once_transparently_after_server_restart`:
   - Start IPC server #1 on `sock_path`.
   - Connect via a custom `BufRead` pipe (or `mpsc`-backed `Read` adapter) into `run_stdio_bridge_io` on a background thread.
   - Send request 1 `{"id":1,"cmd":"ping"}\n` -> read response -> assert `ok: true, id: 1`.
   - Stop server #1 (joins cleanly, releases lock & unlinks old socket), start server #2 on the SAME `sock_path`.
   - Send request 2 `{"id":2,"cmd":"ping"}\n` on the SAME bridge session -> assert bridge transparently reconnects once and returns `ok: true, id: 2` (ZERO transport error)!

### D. `daemon/src/main.rs` Tests
1. `get_status_is_lock_free_and_sub_millisecond_while_subsystems_blocked`:
   - Build `DaemonContext` with pre-populated `CachedSystemState` (`mic_muted=true`, `private_dns.mode="hostname"`, `panic_lockdown_active=true`).
   - Hold ALL 5 slow subsystem locks (`firewall_mtx`, `freezer_mtx`, `automation_mtx`, `clipboard_mtx`, `enforcer_mtx`) simultaneously on the current thread (or background thread holding them via barriers).
   - Dispatch `DaemonCommand::GetStatus` -> assert `resp.ok == true`, elapsed `< 50ms` (realistically `< 1ms`), and `DaemonStatus` fields match cache + `panic_lockdown_active == true`!
2. `protected_uid_rejected_even_with_empty_or_spoofed_package_name`:
   - Populate `UidPackageIndex` with `10250 -> ["com.benign.shared", "dev.silo.app"]` and `10251 -> ["com.rifsxd.ksunext"]`.
   - Dispatch `SetAppFirewallRule { uid: 10250, rule: { package_name: "" } }` -> `Err`!
   - Dispatch `SetAppFirewallRule { uid: 10251, rule: { package_name: "com.fake.app" } }` -> `Err`!
   - Dispatch `SetFreezerEntry { uid: 10250, entry: { package_name: "" } }` -> `Err`!
   - Dispatch `FreezeUidNow { uid: 10250 }` -> `Err`!
   - Dispatch `KillUidSockets { uid: 10250 }` -> `Err`!
   - Verify `KillConnection` pre-check helper rejects protected UID `10250` and skips `LISTEN` / `TIME_WAIT` connections!
3. `panic_lockdown_active_full_lifecycle`:
   - Seed `config.firewall.rules` with UID `10300` (`allow_wifi: true`).
   - Dispatch `TriggerPanicLockdown` -> assert `config.firewall.panic_lockdown_active == true`, `config.firewall.mode == DefaultDeny`, saved `rules.contains_key(&10300)` preserved, `DaemonStatus.panic_lockdown_active == true`.
   - Dispatch `SetFirewallConfig` switching `mode` to `DefaultAllow` -> assert `panic_lockdown_active == false`.
   - Re-trigger lockdown -> dispatch `EmergencyFlushFirewall` -> assert `panic_lockdown_active == false`, `firewall.enabled == false`.
   - Re-trigger lockdown -> dispatch `ApplyPreset { Balanced }` -> assert `panic_lockdown_active == false`.
4. `device_lock_preservation_never_flips_false_on_screen_wake_or_skipped_ticks`:
   - Call `compute_device_locked(true, false, true, 1, || true)` (lockscreen wake on tick 1) -> poll IS invoked, returns `true`.
   - Call `compute_device_locked(true, true, true, 2, || true)` (AOD on tick 2 while previously locked) -> poll IS invoked, returns `true`.
   - Call `compute_device_locked(true, true, false, 1, || panic!("must not poll"))` (unlocked + screen stays on, non-3rd tick) -> skips poll and preserves `false`.
5. `set_app_perm_guard_sanitizes_mutual_exclusivity_and_computes_delta`:
   - Dispatch `SetAppPermGuard` with overlapping `locked_deny: ["camera"]` and `auto_revoke_on_bg: ["camera", "location"]` -> returned `ScopesConfig` has `locked_deny == ["camera"]` and `auto_revoke_on_bg == ["location"]`.
   - Dispatch `SetAppPermissionAction { package_name, category: "camera", action: "allow" }` -> verifies `"camera"` is removed from `guards[pkg].locked_deny` and persisted!
6. `cli_arg_parser_and_uninstall_cleanup_exit_cleanly`:
   - Test `parse_cli_args` for `--uninstall-cleanup`, `--uninstall-cleanup --config /tmp/a.json`, `--config=/tmp/b.json --uninstall-cleanup`, `--bridge --socket /tmp/s.sock`, `--once '{"id":1,"cmd":"ping"}'`, `--emergency-flush`, `--status`, `--version`.
   - Write a temp `config.json` with `freezer.entries` + `scopes.apps` + `scopes.guards` and call `run_uninstall_cleanup(&cfg_path)` -> assert returns `0` cleanly without panicking!

---

## 5. Canonical Verification Command (For Step 1b Worker & Verifiers)

```bash
cd /home/antigravity/terenzy/Silo/daemon && cargo clippy --all-targets -- -D warnings && cargo test --verbose
```
(Every `libc` call used in this blueprint — `flock`, `umask`, `inotify_init1`, `inotify_add_watch`, `poll`, `read`, `close` — uses portable `c_char`/`RawFd` types verified compatible with both `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-musl`.)
