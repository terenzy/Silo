# Silo v3.0.0 — E2E Testing Infrastructure Specification (`TEST_INFRA.md`)

> **Scope & Ownership**: Independent E2E Testing Track (`tests/e2e/**`, `TEST_INFRA.md`, `TEST_READY.md`). Derived strictly from `.agents/teamwork/ORIGINAL_REQUEST.md` and `PROJECT.md` (§ Feature Inventory & § Interface Contracts). Opaque-box, requirement-driven, zero edits to implementation code (`app/`, `daemon/`, `module/`, `scripts/`, `.github/`).

---

## 1. Feature Coverage Matrix (N = 12 Features)

Every feature in `PROJECT.md § Feature Inventory` MUST have dedicated isolated coverage in Tier 1 (`>= 5` happy-path tests each) and Tier 2 (`>= 5` boundary/corner/negative error tests each), pairwise interactions in Tier 3 (`>= 12` tests total), and multi-feature end-user workloads in Tier 4 (`>= 6` scenarios total):

| ID | Feature | Primary Black-Box Test Surfaces | Tier 1 Target | Tier 2 Target |
|---|---|---|---:|---:|
| **F1** | Per-App Permission & AppOps Control | Live `silod` IPC (`inspect_app_permissions`, `set_app_permission_action`, `bulk_revoke_category`) with sandboxed Android CLI doubles (`cmd`, `pm`, `dumpsys`) + Android JVM contract tests | `>= 5` | `>= 5` |
| **F2** | Permission Enforcer (`Kunci Blokir` & `Auto-Cabut`) | Live `silod` IPC (`set_app_perm_guard`, `set_app_permission_action`, `get_config`, restart persistence) + sandboxed `pm set-permission-flags`/`clear-permission-flags` + enforcer contract tests | `>= 5` | `>= 5` |
| **F3** | Real-Time Privacy Monitor & Live Traffic | Live `silod` IPC (`get_privacy_access_log`, `get_live_connections`, `kill_connection`, `get_telemetry`, `clear_telemetry`) + sandboxed `dumpsys appops --op` + Android Monitor contract tests | `>= 5` | `>= 5` |
| **F4** | App ↔ Daemon IPC Bridge, Concurrency & UI Responsiveness | Live `silod` Unix socket (`0600`, raw framing up to 4 MiB, `id` echo, concurrent clients, `<1ms` cached `get_status`, `silod --bridge` reconnect, `silod --once`/`--status`) + Android `SiloClient`/VM contract tests | `>= 5` | `>= 5` |
| **F5** | Kernel Firewall, Panic Lockdown & IP Blocklist | Live `silod` IPC (`set_firewall_config`, `set_app_firewall_rule`, `remove_app_firewall_rule`, `set_temp_unblock`, `clear_temp_unblock`, `replace_ip_blocklist`, `trigger_panic_lockdown`, `emergency_flush_firewall`) + captured `iptables-restore`/`ip6tables-restore` script inspection + Android `BlocklistParser` tests | `>= 5` | `>= 5` |
| **F6** | App Freezer (`SoftCgroup` & `DeepStop`) | Live `silod` IPC (`set_freezer_enabled`, `set_freezer_entry`, `remove_freezer_entry`, `freeze_uid_now`, `thaw_uid_now`, `thaw_all_now`, `get_freezer_status`) + sandboxed `pm`/`am`/`dumpsys` + daemon cgroup safety contract tests | `>= 5` | `>= 5` |
| **F7** | Privacy Automation, Sensors, Clipboard, Radio & DNS | Live `silod` IPC (`set_automation_config`, `set_sensors_off`, `set_sensor_privacy`, `get_private_dns`, `set_private_dns`, `get_radio_privacy`, `set_radio_privacy`, `set_auto_clear_clipboard`, `clear_clipboard_now`, `trigger_panic_reboot`) + sandboxed `cmd`/`dumpsys`/`settings`/`svc` | `>= 5` | `>= 5` |
| **F8** | Kernel Hardening & Security Presets | Live `silod` IPC (`apply_preset` Safe/Balanced/Hardened/Paranoid, `set_hardening_config`, `get_hardening_status`, `rollback_hardening`) + boot-ID `sysctl_orig.json` durability + Android preset/hardening contract tests | `>= 5` | `>= 5` |
| **F9** | Verification, Stealth Sentinel, Config Durability & App Discovery | Live `silod` IPC (`run_sentinel_audit`, `run_live_proof`, `export_config_json`, `import_config_json`), corrupt `config.json` backup recovery (`config.json.corrupt.*`), legacy v2 `scopes.apps` handling + Android AppInventory/export contract tests | `>= 5` | `>= 5` |
| **F10** | Lockscreen Physical Security, Quick Settings Tiles & Offline/Disguise Polish | Compiled Android APK inspected via real `aapt2 dump permissions` & `aapt2 dump xmltree`, `AndroidManifest.xml`, `proguard-rules.pro`, Android JVM tests (QS tiles lockscreen/unlock guards, i18n, zero-INTERNET) | `>= 5` | `>= 5` |
| **F11** | Protected Package & Process/IPC Privilege Boundary | Live `silod` Unix socket & IPC (`flock` singleton `silod.lock`, socket `0600` & inode-verified unlink, protected UID/package rejection across Firewall/Freezer/Permissions/`KillConnection`, `SO_PEERCRED` auth, process-group timeout cleanup) | `>= 5` | `>= 5` |
| **F12** | KernelSU Module Lifecycle, Packaging & CI Security | Real `sh -n`/`bash -n`, isolated sandbox execution of `module/{service,customize,action,uninstall}.sh`, live `silod --version`/`--emergency-flush`/`--uninstall-cleanup`, real `scripts/build_module.sh` packaging + zero-mount check, `.github/workflows/build.yml` & `Cargo.toml` structural/security checks | `>= 5` | `>= 5` |

### Coverage Threshold Summary
- **Tier 1 (Feature Coverage)**: `>= 60` tests (`>= 5` per feature `F1`–`F12`)
- **Tier 2 (Boundary, Corner & Negative Error Cases)**: `>= 60` tests (`>= 5` per feature `F1`–`F12`)
- **Tier 3 (Cross-Feature Pairwise Combinations)**: `>= 12` pairwise tests spanning interacting feature pairs
- **Tier 4 (Real-World Application Scenarios)**: `>= 6` multi-feature end-user workload scenarios
- **Total Minimum**: `>= 138` genuine, non-trivial, independently executable test cases

---

## 2. Architecture & Execution Surfaces (Linux Devbox Authenticity)

Because this Linux devbox has no attached Android phone, **every E2E test executes genuinely against real Silo binaries, build outputs, shell scripts, and protocol endpoints** — zero tautologies, zero hardcoded mocks of Silo logic, and zero production code modifications:

```text
tests/e2e/
├── run_e2e.py                              # Canonical CLI runner (--tier 1|2|3|4|all, --feature, --list, --self-check, --json-report)
├── harness/
│   ├── __init__.py
│   ├── registry.py                         # Decorator-based test registry enforcing tier, feature IDs, unique test IDs & thresholds
│   ├── silod_harness.py                    # Real compiled silod process manager, Unix socket client, --bridge client, CLI runner, sandboxed Android-tool PATH shims
│   ├── android_harness.py                  # Real ./gradlew testDebugUnitTest / assembleRelease driver + real aapt2 APK & manifest inspection
│   ├── module_harness.py                   # Real sh -n / bash -n + isolated temp-sandbox execution of module/{service,customize,action,uninstall}.sh
│   ├── packaging_harness.py                # Real scripts/build_module.sh driver with valid/invalid ELF64 inputs & ZIP zero-mount verifier
│   └── ci_harness.py                       # Structural & security verifier for .github/workflows/build.yml, Cargo.toml, proguard-rules.pro
├── tier1_features/                         # >= 60 happy-path tests (>= 5 per F1..F12)
├── tier2_boundaries/                       # >= 60 boundary/corner/negative error tests (>= 5 per F1..F12)
├── tier3_combinations/                     # >= 12 pairwise cross-feature interaction tests
└── tier4_scenarios/                        # >= 6 end-to-end real-world multi-feature workload scenarios
```

### Key Authenticity Rules
1. **Real Compiled `silod`**: Built once on demand via `cargo build` (or overridden via `SILOD_BIN`), launched in an isolated `mktemp`-backed directory (`chmod 700`) with dedicated `--socket`, `--config`, and `--boot-marker` paths. Never touches `/data/adb/silo`.
2. **Real Black-Box IPC**: Tests drive `silod` over real AF_UNIX stream sockets (checking exact wire JSON-RPC framing, `id` echo, 4 MiB line limit, concurrent connections, `0600` mode, `flock` singleton) and real `silod --bridge` / `--once` / `--status` / `--version` / `--emergency-flush` / `--uninstall-cleanup` subprocesses.
3. **Sandboxed Android CLI Environment (where OS commands are invoked by `silod`)**: Where `silod` invokes Android CLI tools (`cmd`, `pm`, `dumpsys`, `settings`, `svc`, `getprop`, `iptables-restore`, `ip6tables-restore`), tests inject realistic Android 15 / One UI 7 CLI responder executables into the spawned `silod` process's isolated sandbox `PATH` (recording exact argv/stdin invocations and returning realistic One UI 7 outputs, e.g., `Uid mode: CAMERA: ignore` + `CAMERA: allow; time=+3m12s ago`) **only when `silod` resolves via `PATH`** — testing the **real compiled Rust binary's** parsing, invocation order, error handling, and rule generation end-to-end!
4. **Real Android Build & APK Verification**: Invokes real `./gradlew testDebugUnitTest` (and `assembleRelease` / `aapt2 dump permissions` & `aapt2 dump xmltree`) without modifying `app/`.
5. **Real Module & Packaging Execution**: Executes real `module/*.sh` scripts in isolated temp sandboxes (testing stale non-`silod` PID protection, verified `silod` termination, `disable` cleanup, `--uninstall-cleanup` invocation) and real `scripts/build_module.sh` against real/valid/invalid ELF binaries and inspecting the generated `.zip`.
6. **Zero Side Effects**: Every test creates and cleans up its own temp directory; running `run_e2e.py` leaves the git working tree completely clean.

---

## 3. Runner CLI & Pass/Fail Semantics

### Canonical Invocations
```bash
# Self-validate harness structure, imports, unique IDs, and coverage thresholds
python3 tests/e2e/run_e2e.py --self-check

# List all registered test cases with tier & feature tags
python3 tests/e2e/run_e2e.py --list

# Execute individual tiers sequentially (used by Final Milestone Phase 1: Tiers 1 -> 2 -> 3 -> 4)
python3 tests/e2e/run_e2e.py --tier 1
python3 tests/e2e/run_e2e.py --tier 2
python3 tests/e2e/run_e2e.py --tier 3
python3 tests/e2e/run_e2e.py --tier 4

# Execute full 4-tier E2E suite
python3 tests/e2e/run_e2e.py --tier all

# Filter by feature or export machine-readable report
python3 tests/e2e/run_e2e.py --tier 1 --feature F1 --json-report /tmp/silo_e2e_report.json
```

### Environment Overrides
- `SILOD_BIN`: Path to prebuilt host `silod` binary (default: builds/uses `daemon/target/debug/silod`).
- `SILOD_ARM64_BIN`: Optional path to prebuilt static ARM64 musl `silod` binary for Tier 4 release packaging checks.
- `SILO_RELEASE_APK`: Optional path to prebuilt release APK (default: `app/app/build/outputs/apk/release/app-release.apk`, built on demand if missing).
- `SILO_SKIP_BUILD=1`: Skip auto-invoking `cargo build` / `./gradlew` when artifacts are already prebuilt.

### Strict Pass/Fail Semantics
- **Exit `0`**: Every selected test executed and passed (`failed == 0`, `errors == 0`, `executed > 0`).
- **Exit `1`**: One or more tests failed, errored, or timed out, OR `executed == 0`, OR `--self-check` detected a coverage threshold violation.
- **No Silent Skips**: Required checks MUST NOT silently `skip` and report pass when a target binary, APK, or feature contract is missing/broken; they fail loudly with actionable diagnostic output.

---

## 4. Planned Tier 4 Real-World Application Scenarios (`>= 6` Scenarios)

1. **`T4-S01` — One UI 7 Samsung Camera (`com.sec.android.app.camera`) Permission Revoke, `Kunci Blokir`, Manual Override & Monitor Workflow (`F1`, `F2`, `F3`, `F4`)**:
   Full lifecycle: inspect Samsung Camera with conflicting `Uid mode: CAMERA: ignore` + historical `CAMERA: allow; time=+3m12s ago`, revoke `camera`, enable `Kunci Blokir` (verifying `user-fixed` flag + mutually exclusive `auto_revoke_on_bg` cleanup), override back to `allow` (verifying `locked_deny` auto-removed + `user-fixed` cleared + `pm grant`), and verify Monitor `get_privacy_access_log` reports strict mode & excludes protected packages while concurrent `get_status` returns in `< 50ms`.
2. **`T4-S02` — Panic Lockdown With Prior `DefaultAllow` Custom Rules & Multi-User Isolation (`F5`, `F4`, `F7`, `F11`)**:
   Configure `DefaultAllow` with explicit `allow_wifi`/`allow_cellular` custom rules on multiple user apps (including Work Profile u10, Dual Messenger u95, Secure Folder u150) + VPN `xfrm+`, trigger `trigger_panic_lockdown`, verify `DaemonStatus.panic_lockdown_active == true`, verify generated `iptables-restore`/`ip6tables-restore` blocks ALL non-protected user UIDs unconditionally despite saved `allow_*` rules while still protecting Silo/KSU/system UIDs, then verify clearing via `apply_preset("balanced")`.
3. **`T4-S03` — Large IP/CIDR Blocklist Import (> 512 KiB / ~25,000+ CIDRs) Over `--bridge` + Config Export/Import Durability (`F4`, `F5`, `F9`)**:
   Push a realistic multi-megabyte JSON-RPC `replace_ip_blocklist` payload (> 512 KiB, < 4 MiB) containing valid IPv4/IPv6 CIDRs plus invalid tokens over real `silod --bridge`, verifying 4 MiB framing handles it cleanly, `rejected > 0` accurately reports bad entries, `export_config_json` / `import_config_json` round-trip preserves state, and corrupt `config.json` triggers automatic `config.json.corrupt.*` backup on restart.
4. **`T4-S04` — Daemon Crash, Restart, `--bridge` Transparent Reconnect & Boot/Uninstall Lifecycle (`F4`, `F6`, `F8`, `F11`, `F12`)**:
   Start `silod` with active firewall/freezer/guards/hardening config, verify `silod.lock` blocks a duplicate daemon instance, kill `silod` while a `silod --bridge` client is attached, restart `silod`, verify `--bridge` transparently reconnects and succeeds on next RPC, verify stale non-`silod` PID protection in `module/service.sh` and `module/uninstall.sh`, and run `silod --uninstall-cleanup` verifying full firewall flush + AppOps/flag reset.
5. **`T4-S05` — Preset Switching (`Paranoid` → `Balanced` → `Safe` → `Hardened`) With Automation, Lockscreen, Sensors & Hardening Rollback (`F7`, `F8`, `F9`)**:
   Cycle all 4 security presets verifying every automation toggle (`wifi_auto_off_enabled`, `bluetooth_auto_off_enabled`, `usb_adb_lock_guard`, `sensors_off_on_lock`, `auto_clear_clipboard`) and hardening setting resets cleanly with zero leftover Paranoid flags, test lockscreen sensor privacy + `adb_wifi_enabled 0` + `ClipboardGuard` `label=null` handling, and verify `rollback_hardening` + `run_live_proof` + `run_sentinel_audit`.
6. **`T4-S06` — End-to-End Release Artifact Security Gate: Android APK, KernelSU Zero-Mount Module ZIP & CI Pipeline (`F10`, `F12`)**:
   Verify real Android unit tests + Release APK (`aapt2 dump permissions` zero `INTERNET` & zero `POST_NOTIFICATIONS`, `MainActivity exported=false`, dual launcher aliases `exported=true`, 3 QS tiles, ProGuard enum keep rules), package a KernelSU module ZIP via real `scripts/build_module.sh` validating static ARM64 ELF enforcement and zero-mount (`system|vendor|product|system_ext|odm`) compliance, and verify `.github/workflows/build.yml` least-privilege permissions & safe env-escaped inputs.
7. **`T4-S07` — App Freezer (`SoftCgroup` & `DeepStop`) Lifecycle + Protected Boundary Defense (`F6`, `F11`, `F5`)**:
   Configure mixed `SoftCgroup` and `DeepStop` entries, test freeze/thaw/thaw-all operations, attempt protected UID & spoofed-package-name bypasses on `set_app_firewall_rule`, `set_freezer_entry`, `freeze_uid_now`, and `kill_connection` (all rejected!), and verify restart durability.
