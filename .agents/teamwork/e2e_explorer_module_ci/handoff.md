# E2E Explorer Handoff — Module Scripts, Packaging, CI & Privilege Boundaries (`e2e_explorer_module_ci`)

> **Scope**: Strictly read-only reconnaissance & actionable E2E harness/test blueprint for **F11** (Protected Package & Process/IPC Privilege Boundary) and **F12** (KernelSU Module Lifecycle, Packaging & CI Security), targeting `tests/e2e/harness/{module_harness,packaging_harness,ci_harness}.py` and Tiers 1–4 test suites.
> **Important Concurrency Note**: M1 (`daemon-core`) and M4 (`module-scripts-ci`) may be concurrently editing `daemon/**`, `module/**`, `scripts/**`, and `.github/workflows/build.yml`. Every section below cites BOTH the **current pre-fix code** observed at inspection time AND the **target requirements** from `PROJECT.md` (`§ Feature Inventory` F11–F12 & `§ Interface Contracts § 4`). All E2E assertions MUST test the **target requirements** — never weaken assertions to accommodate pre-fix bugs.

---

## 1. Devbox Toolchain & Runtime Verification (Empirical)

All probes were read-only (temporary `/tmp` files removed via `trap` on exit):

| Check | Empirical Result | Impact on E2E Harness Design |
|---|---|---|
| `/bin/sh` | Symlink to `dash` (`/bin/sh -> dash`) | Strict POSIX shell (matches Android `mksh` far better than `bash`). `dash` supports `kill -0`, `kill -TERM` (exit 143), `kill -KILL`. |
| `/bin/bash` | Installed (`/bin/bash`) | Used for `bash -n scripts/build_module.sh` and running `scripts/build_module.sh`. |
| `python3` | `Python 3.12.16` | **Zero external packages**: `yaml` (PyYAML) is **MISSING**. `tomllib`, `zipfile`, `json`, `socket`, `subprocess`, `tempfile` are ALL **AVAILABLE** in stdlib. |
| `file(1)` | `file-5.45` | Verified exact output strings for crafted ELF fixtures (see §3.2). |
| `gcc`, `zip`, `unzip`, `readlink`, `setsid`, `tr` | All installed (`/usr/bin/*`) | Enables compiling a tiny cached native ELF `silod` test stub (for authentic `/proc/$PID/exe` + `/proc/$PID/cmdline` + `/proc/$PID/comm` tests) and full ZIP verification. |
| `rustup target list --installed` | `x86_64-unknown-linux-gnu` ONLY | `aarch64-unknown-linux-musl` is NOT installed yet. `packaging_harness.py` MUST synthesize a valid 136-byte ELF64 AArch64 static binary by default, while supporting `SILOD_ARM64_BIN` env override for real cross-compiled binaries in Tier 4 / M5. |
| `/data/adb` | Does NOT exist on host | Host filesystem has no `/data`; unprivileged tests MUST sandbox `/data/adb/*` into temp directories. |
| `resolve_bin` (`daemon/src/sys.rs:115-123`) | `cmd`, `pm`, `dumpsys`, `settings`, `svc`, `getprop`, `am`, `ipset` are **NOT** in `/system/bin`, `/vendor/bin`, `/sbin`, `/usr/sbin`, `/usr/bin`, `/bin`. (`iptables`, `iptables-restore`, `ip6tables`, `ip6tables-restore` **ARE** in `/sbin` & `/usr/sbin`.) | On Linux devbox, `silod`'s `resolve_bin()` falls through to bare command name for `cmd`, `pm`, `dumpsys`, `settings`, `svc`, `getprop`, `am`, `ipset` — meaning `PATH`-injected shims 100% intercept `silod --uninstall-cleanup` (`pm`/`cmd`) and `run_cmd` timeout tests (`dumpsys`). |

---

## 2. Objective 1: KernelSU Module Scripts (`module/*`) & Sandboxing Blueprint

### 2.1 Complete Line-by-Line Inventory of `module/*`

#### A. `module/module.prop` (`module/module.prop:1-7`)
- `id=silo` (line 1) — must match `/data/adb/modules/silo/bin/silod` (`SiloClient.kt:31`) and `customize.sh` disable cleanup path.
- `name=Silo Kernel Privacy & Security Suite` (line 2)
- `version=v3.0.0` (line 3) — must match `v` + `daemon/Cargo.toml:3` (`version = "3.0.0"`) and `app/app/build.gradle.kts` (`versionName = "3.0.0"`).
- `versionCode=30000` (line 4) — must match `app/app/build.gradle.kts` (`versionCode = 30000`).
- `author=terenzy` (line 5)
- `description=...` (line 6)

#### B. `module/service.sh` (`module/service.sh:1-76`)
- **Shebang**: `#!/system/bin/sh` (line 1) — invoke via `/bin/sh <script>` on Linux.
- **Variables & Paths**:
  - `MODDIR="${0%/*}"` (line 12) — **Pre-fix bug (`CB-28`)**: breaks if `$0` has no `/` (e.g. `sh service.sh` from `cwd=moddir`). **Target**: portable `MODDIR` resolution (`dirname -- "$0"` / `[ "$MODDIR" = "$0" ] && MODDIR="."`).
  - `SILOD="$MODDIR/bin/silod"` (line 13)
  - `STATE_DIR="/data/adb/silo"` (line 14) — **Hardcoded** (even if M4 adds an env fallback like `${SILO_STATE_DIR:-/data/adb/silo}`, our sandbox rewrites `/data/adb` -> `<sandbox>/data/adb` so it works identically either way).
  - `BOOT_MARKER="$STATE_DIR/.boot_fail_count"` (line 15)
  - `SOCK_PATH="$STATE_DIR/silod.sock"` (line 16)
  - `CFG_PATH="$STATE_DIR/config.json"` (line 17)
  - `PID_FILE="$STATE_DIR/silod.pid"` (line 18)
  - Disable marker: `"$MODDIR/disable"` (line 48)
- **Commands invoked**: `getprop` (`sys.boot_completed`), `sleep` (`2`, `5`, `1` — plus potential watchdog backoff in M4), `mkdir`, `chmod`, `cat`, `tr`, `touch`, `rm`, `kill`, plus (in M4 target) `readlink`/`grep`/`dirname`/`pwd`/`setsid`.
- **Current bugs vs Target requirements (`PROJECT.md` F12, Contract §4, `CB-07`, `CB-18`, `CB-28`)**:
  1. **Boot wait**: Currently `180s` (`service.sh:25`); **Target**: `300s` (`CB-18`). If boot never reaches `1`, exits `0` without arming `.boot_fail_count` or starting `silod`.
  2. **Dead-Man Switch (`FAIL_COUNT >= 2`)**: Runs `"$SILOD" --emergency-flush` (lines 46-50), `touch "$MODDIR/disable"`, `rm -f "$BOOT_MARKER" "$SOCK_PATH" "$PID_FILE"`, exits `0` without launching daemon.
  3. **Stale PID verification (`CB-07` / Contract §4)**: Currently lines 58-64 blindly `kill -TERM "$OLD_PID"` + `sleep 1` without checking `/proc/$OLD_PID/{cmdline,exe}`. **Target**: MUST verify `/proc/$OLD_PID/cmdline` or `/proc/$OLD_PID/exe` matches `silod` before signaling; MUST NOT signal non-`silod` PIDs; for verified `silod`, send `SIGTERM`, wait up to `5s` in a poll loop (escalating to `SIGKILL` if verified `silod` ignores `SIGTERM`), and only then remove/rebind socket and spawn new `silod`.
  4. **Bounded restart watchdog (`CB-18`)**: M4 may add a background watchdog loop (`setsid`/subshell) that restarts `silod` on unexpected crash (capped, not on clean `SIGTERM`). Harness MUST handle background subshell cleanup and non-blocking stdout/stderr capture!

#### C. `module/customize.sh` (`module/customize.sh:1-34`)
- **KernelSU Execution Model**: Sourced (`. "$MODPATH/customize.sh"`) in a POSIX shell where `$MODPATH` is set, `$ARCH` may be set by KSU/Magisk (`arm64`, `arm`, `x64`, `x86`), and functions `ui_print()` and `abort()` are pre-defined!
- **Variables & Paths**:
  - `SKIPUNZIP=0` (line 5)
  - `ARCH="$(getprop ro.product.cpu.abi)"` (line 9) — **Pre-fix bug (`CB-28`)**: overwrites pre-set `$ARCH` and has no fallback. **Target**: check `ro.product.cpu.abi` (`arm64-v8a*`), fallback to `ro.product.cpu.abilist64` and/or pre-set `$ARCH=arm64`; reject `x86_64`, `armeabi-v7a`, empty/unknown via `abort`!
  - `$MODPATH/bin/silod` (lines 19-23): must exist; `chmod 0755`.
  - `$MODPATH/{service,action,uninstall}.sh` (line 24): `chmod 0755`.
  - `/data/adb/silo` (lines 27-29): **Hardcoded** `mkdir -p`, `chmod 0700`, `rm -f /data/adb/silo/.boot_fail_count`.
  - **Target (`CB-18` / Contract §4)**: MUST remove stale `/data/adb/modules/silo/disable` (and/or `$MODPATH/disable` if present) from a prior boot-guard trigger!

#### D. `module/action.sh` (`module/action.sh:1-46`)
- **Variables & Paths**: `MODDIR` (line 5 — Target: portable), `SILOD="$MODDIR/bin/silod"` (line 6), `STATE_DIR="/data/adb/silo"` (line 7 — Hardcoded), `SOCK_PATH`, `CFG_PATH`, `BOOT_MARKER`, `PID_FILE` (lines 8-11), plus `$MODDIR/disable` (Target `CB-18` / Contract §4).
- **Behaviors**:
  - Missing/non-executable `$SILOD` -> prints `[ERROR]` and exits `1` (lines 17-20).
  - Always prints `"$SILOD" --version` (line 22).
  - Healthy branch (`[ -S "$SOCK_PATH" ] && "$SILOD" --socket "$SOCK_PATH" --status`): prints `[OK]` + status JSON, does not restart running daemon (lines 24-26).
  - Recovery branch (lines 27-45):
    - **Target (`CB-18` / Contract §4)**: removes `"$MODDIR/disable"`!
    - **Target (`CB-07` / Contract §4)**: if `$PID_FILE` points to a live verified `silod` PID, stops & waits up to 5s before relaunching; MUST NOT kill a non-`silod` stale PID!
    - Creates `$STATE_DIR` (`0700`), removes `$BOOT_MARKER` & `$SOCK_PATH`, spawns `$SILOD`, writes `$PID_FILE` (`0600`), sleeps `2`, checks `--status`.

#### E. `module/uninstall.sh` (`module/uninstall.sh:1-27`)
- **Variables & Paths**: `MODDIR` (line 5 — Target: portable), `SILOD="$MODDIR/bin/silod"` (line 6), `STATE_DIR="/data/adb/silo"` (line 7 — Hardcoded), `PID_FILE="$STATE_DIR/silod.pid"` (line 8), plus `CFG_PATH="$STATE_DIR/config.json"` (Target `CB-19` / Contract §4).
- **Current bugs vs Target requirements**:
  1. **PID safety (`CB-07` / Contract §4)**: Currently lines 11-18 blindly `kill -TERM "$PID"`, `sleep 2`, `kill -KILL "$PID"`. **Target**: MUST verify `/proc/$PID/cmdline` or `/proc/$PID/exe` matches `silod` before `SIGTERM`/`SIGKILL`; MUST wait up to 5s before escalating to `SIGKILL`; MUST NOT signal non-`silod` PIDs!
  2. **Uninstall cleanup CLI (`CB-19` / Contract §4)**: Currently line 22 runs `"$SILOD" --emergency-flush` without `--config`. **Target**: runs `"$SILOD" --uninstall-cleanup --config "$CFG_PATH"` **BEFORE** `rm -rf "$STATE_DIR"` so `silod` can read `config.json` and reset all Silo-configured AppOps (`scopes.apps` + `scopes.guards`) back to `default` and clear `user-fixed`/`revoked-compat` flags!
  3. **Zero-trace removal**: `rm -rf "$STATE_DIR"` (line 26).

---

### 2.2 Critical Empirical Finding: `/proc/$PID/cmdline` vs `/proc/$PID/exe` on Linux

We empirically tested 5 process spawn styles on this Linux devbox:

| Spawn Method | `/proc/$PID/cmdline` | `readlink -f /proc/$PID/exe` | `/proc/$PID/comm` | Matches `exe` check? | Matches `cmdline` check? |
|---|---|---|---|---|---|
| `/usr/bin/sleep 60` (non-`silod` target) | `/usr/bin/sleep 60` | `/usr/bin/sleep` | `sleep` | **NO** | **NO** |
| `bash -c 'exec -a silod sleep 60'` | `silod 60` | `/usr/bin/sleep` | `sleep` | **NO!** | YES |
| `#!/bin/sh` script named `silod` | `/bin/sh .../silod` | `/usr/bin/dash` | `silod` | **NO!** | YES |
| `cp /bin/sleep <sb>/bin/silod` + exec | `<sb>/bin/silod 60` | `<sb>/bin/silod` | `silod` | **YES** | **YES** |
| **Native ELF binary at `<sb>/bin/silod`** (real `silod` OR tiny compiled C ELF stub) | `<sb>/bin/silod ...` | `<sb>/bin/silod` | `silod` | **YES** | **YES** |

**Crucial Design Rule for `module_harness.py`**:
- Because `PROJECT.md § Interface Contracts § 4` says *"verify `/proc/$PID/cmdline` (or `/proc/$PID/exe`) contains `silod`"*, M4 might verify `/proc/$PID/exe` OR `/proc/$PID/cmdline` OR both.
- **NEVER use `exec -a silod sleep` or a `#!/bin/sh` wrapper for a "verified `silod`" test process!** Both have `/proc/$PID/exe` pointing to `/usr/bin/sleep` or `/usr/bin/dash`, which would cause false failures if M4 checks `/proc/$PID/exe`!
- **ALWAYS use a real native ELF binary located at `<sandbox>/data/adb/modules/silo/bin/silod`** — either:
  1. **`mode="real"`**: copy/hardlink the real compiled host `silod` (`daemon/target/debug/silod` or `$SILOD_BIN`), OR
  2. **`mode="native_stub"`**: a tiny ~55-line C ELF binary compiled once per test session via `gcc` into a cache dir and copied to `<sandbox>/data/adb/modules/silo/bin/silod`. It satisfies ALL THREE Linux procfs checks (`exe`, `cmdline`, `comm`), logs exact `argv` + file-existence probes (`config.json` readable at `--uninstall-cleanup` time!) to `$SILO_STUB_LOG`, and supports deterministic behaviors (`SILO_STUB_IGNORE_TERM=1` to test `SIGTERM` -> 5s wait -> `SIGKILL` escalation, `SILO_STUB_EXIT_DELAY_MS=100` for graceful shutdown wait, `SILO_STUB_CRASH=1` for watchdog cap testing).
- For **non-`silod` stale PID** tests, ALWAYS spawn `/usr/bin/sleep 60` via absolute path (never using sandbox `PATH`'s `sleep` shim!) so `/proc/$PID/exe`, `/proc/$PID/cmdline`, and `/proc/$PID/comm` contain zero occurrence of `silod`.

---

### 2.3 Rock-Solid Temp Sandbox Design for `tests/e2e/harness/module_harness.py`

Create a context manager `ModuleSandbox` backed by `tempfile.TemporaryDirectory(prefix="silo_mod_e2e_")`:

1. **Directory Layout inside `<sandbox>`**:
   ```text
   <sandbox>/
   ├── bin/                              # Prepended to PATH: getprop shim, configurable sleep shim, optional pm/cmd shims
   ├── logs/
   │   ├── getprop.log
   │   ├── sleep.log                     # Records every sleep duration argument (for verifying 300s boot-wait!)
   │   ├── silod_stub.log                # JSON lines: {"argv":[...], "cfg_exists":bool, "pid":int, "ts":float}
   │   ├── stdout.txt                    # File-backed stdout capture (prevents pipe-hang if background child inherits FD!)
   │   └── stderr.txt                    # File-backed stderr capture
   └── data/
       └── adb/
           ├── silo/                     # Sandbox STATE_DIR (initially absent for customize.sh, or pre-populated)
           ├── modules/
           │   └── silo/                 # Sandbox live MODDIR (contains rewritten service.sh, action.sh, uninstall.sh, bin/silod, optional disable)
           └── modules_update/
               └── silo/                 # Sandbox MODPATH for customize.sh (contains rewritten customize.sh + module files + bin/silod)
   ```
2. **Safe Path Virtualization (Zero Repo Edits, Works Whether Paths Are Hardcoded OR Env-Overridable)**:
   - Read each real script from `<REPO_ROOT>/module/{service,customize,action,uninstall}.sh` (never modifying repo files!).
   - Rewrite occurrences of `/data/adb` on non-comment code lines into `<sandbox>/data/adb` using regex:
     ```python
     re.sub(r'(?<![A-Za-z0-9_./-])(/data/adb)(?=/|["\'\s;]|$)', lambda m: f"{sandbox_root}{m.group(1)}", line)
     ```
   - Why this works universally:
     - `STATE_DIR="/data/adb/silo"` -> `STATE_DIR="<sandbox>/data/adb/silo"`
     - `STATE_DIR="${SILO_STATE_DIR:-/data/adb/silo}"` -> `STATE_DIR="${SILO_STATE_DIR:-<sandbox>/data/adb/silo}"`
     - `rm -f /data/adb/modules/silo/disable` -> `rm -f <sandbox>/data/adb/modules/silo/disable`
     - `MODDIR` naturally resolves to `<sandbox>/data/adb/modules/silo` because the rewritten scripts live inside `<sandbox>/data/adb/modules/silo/`!
     - Real Linux `/proc/$PID/cmdline` and `/proc/$PID/exe` are **UNTOUCHED**, so PID safety runs against real Linux procfs!
3. **Configurable Sandbox `PATH` Shims (`<sandbox>/bin/`)**:
   - `getprop`: Python/sh executable reading a JSON map `<sandbox>/getprop_map.json` (e.g., `{"sys.boot_completed": "1", "ro.product.cpu.abi": "arm64-v8a"}`; supports sequence mode `["0", "0", "1"]` for boot polling tests!). Logs calls to `getprop.log`.
   - `sleep`: Fast configurable shim reading `<sandbox>/sleep_cfg.json`:
     - Logs every requested duration (`arg1`) to `sleep.log` (so boot-timeout test sums logged seconds and asserts `>= 300` in `< 0.3s` wall time!).
     - Scales sleep duration by arg: e.g. default `{"1": 0.12, "2": 0.25, "5": 0.02}` (gives real `silod` 120ms×5 = 600ms for graceful `SIGTERM` exit and 250ms for socket startup after `action.sh`, while keeping tests sub-second; boot-timeout test sets `{"default": 0.0}`!).
   - Optional Android CLI shims (`cmd`, `pm`, `dumpsys`, etc.) when running `mode="real"` `silod --uninstall-cleanup` or `--emergency-flush` so every `cmd appops` and `pm clear-permission-flags` / `pm unsuspend` invocation is recorded!
4. **Sourcing `customize.sh` (Matching KernelSU Installer Semantics)**:
   - Execute via `/bin/sh -c` with a minimal POSIX bootstrap:
     ```sh
     ui_print() { printf '%s\n' "$*"; }
     abort() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }
     . "$1"
     ```
   - Pass `MODPATH=<sandbox>/data/adb/modules_update/silo` in env; set or unset `ARCH` per test case; capture `returncode`, `stdout`, `stderr`.
5. **File-Backed Stdio + Leak-Proof Process Teardown (Critical for `service.sh` / Watchdog)**:
   - NEVER use `capture_output=True` pipes for `service.sh` / `action.sh` because backgrounded `silod` or a watchdog subshell can keep inherited pipe FDs open and stall `communicate()`. Redirect stdout/stderr to `<sandbox>/logs/stdout.txt` and `<sandbox>/logs/stderr.txt` and pass `start_new_session=True`.
   - Inject a unique env tag `SILO_E2E_SANDBOX_ID=<uuid>` into every child process spawned by the sandbox.
   - In `ModuleSandbox.__exit__`:
     1. Kill the spawned process group (`os.killpg`).
     2. Read `<sandbox>/data/adb/silo/silod.pid` if present and terminate if verified inside sandbox.
     3. Scan `/proc/[0-9]*/environ` (owned by our UID, excluding `os.getpid()`) for `SILO_E2E_SANDBOX_ID=<uuid>` and send `SIGTERM` -> wait 0.4s -> `SIGKILL` to every surviving descendant — catches even `setsid`-detached watchdog daemons!
     4. Terminate any dummy `/usr/bin/sleep` target processes tracked by the sandbox.
     5. Remove `<sandbox>` directory.

---

## 3. Objective 2: Packaging Script Surface (`scripts/build_module.sh`)

### 3.1 Audited Behavior & Target Requirements (`scripts/build_module.sh:1-46`, `CB-28`)
- **CLI**: `./scripts/build_module.sh [SILOD_BIN] [OUT_ZIP]`
  - Default `SILOD_BIN`: `$REPO_ROOT/daemon/target/aarch64-unknown-linux-musl/release/silod` (line 6)
  - Default `OUT_ZIP`: `$REPO_ROOT/dist/silo-ksu-module.zip` (line 7)
  - Resolves `REPO_ROOT` relative to `${BASH_SOURCE[0]}` (`$SCRIPT_DIR/..`, lines 4-5) — **allows running a temp copy in an isolated temp workspace copy without modifying the real repository!**
- **Binary Gate (`scripts/build_module.sh:9-20`)**:
  - Rejects missing file (`[ ! -f "$SILOD_BIN" ]`, exit 1).
  - Runs `file "$SILOD_BIN"` and requires `grep -qiE 'ELF 64-bit.*ARM aarch64.*statically linked'` (line 17).
- **Module File Staging (`scripts/build_module.sh:25-28`)**:
  - **Pre-fix bug (`CB-28`)**: Line 25 runs `cp -r "$REPO_ROOT/module/"* "$STAGE_DIR/"` indiscriminately (would package stray files or `product/`/`system_ext/`/`odm/` directories).
  - **Target (`CB-28` / F12)**: Explicitly copy only allowlisted module files (`module.prop`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh`) into `$STAGE_DIR/`, place `$SILOD_BIN` at `$STAGE_DIR/bin/silod`, and `chmod 0755` `bin/silod`, `service.sh`, `action.sh`, `uninstall.sh`, `customize.sh`.
- **Zero-Mount Stealth Check (`scripts/build_module.sh:30-34`)**:
  - **Pre-fix bug (`CB-28`)**: Line 31 checks ONLY `[ -d "$STAGE_DIR/system" ] || [ -d "$STAGE_DIR/vendor" ]` — misses `product`, `system_ext`, `odm`!
  - **Target (`CB-28` / F12)**: Must enforce zero overlays across ALL FIVE Android mount roots: `system`, `vendor`, `product`, `system_ext`, `odm`!
  - **Subtle Black-Box Assertion Rule**: When testing planted forbidden overlay dirs (`module/{system,vendor,product,system_ext,odm}/...`) inside a temp workspace copy:
    - On pre-fix `build_module.sh`, planting `module/product/x`, `module/system_ext/x`, or `module/odm/x` exits `0` AND **packages the forbidden directory into the ZIP**!
    - On fixed `build_module.sh` (`CB-28`), M4 may EITHER (a) fail non-zero on detecting forbidden dirs in `$REPO_ROOT/module/` or `$STAGE_DIR/`, OR (b) omit them via explicit allowlist copy AND check all 5 dirs on `$STAGE_DIR/`.
    - Therefore, assert: **(1) if `rc == 0`, the output ZIP MUST contain ZERO entries matching `(system|vendor|product|system_ext|odm)/`** AND **(2) `scripts/build_module.sh` zero-mount check MUST cover all 5 partition names (`system`, `vendor`, `product`, `system_ext`, `odm`)**. And for a planted stray non-overlay file `module/untracked_junk.txt`, assert it is **NOT** in the output ZIP (verifying explicit copy)!
- **ZIP Creation (`scripts/build_module.sh:36-45`)**:
  - Creates parent dirs (`mkdir -p "$(dirname "$OUT_ZIP")"`), overwrites existing ZIP (`rm -f "$OUT_ZIP"`), zips from `$STAGE_DIR` (`zip -r9 "$OUT_ZIP" .`), cleans up `$STAGE_DIR` via `trap`.

### 3.2 Empirically Verified Synthetic ELF Fixtures (`file-5.45` on Devbox)

CRITICAL EMPIRICAL DISCOVERY: An ELF64 with `PT_INTERP` alone (without `PT_DYNAMIC`) is STILL reported by `file-5.45` as `statically linked, interpreter ...`! To construct a genuine **dynamically linked** ARM64 ELF negative fixture, `PT_DYNAMIC` (`p_type = 2`) MUST be included.

All 6 binary fixtures below were empirically verified with `file-5.45` on this devbox:

| Fixture Name | Size | ELF Structure | Exact `file(1)` Output (Verified) | Expected `build_module.sh` Result |
|---|---:|---|---|---|
| `arm64_static` | 136 B | `ELF64 LE`, `ET_EXEC (2)`, `EM_AARCH64 (183)`, 1× `PT_LOAD` (`p_type=1`, `p_flags=5`) + 16B payload | `ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), statically linked, no section header` | **PASS (exit 0)** |
| `arm64_dynamic` | 280 B | `ELF64 LE`, `EM_AARCH64 (183)`, 3 phdrs: `PT_LOAD (1)` + `PT_INTERP (3)` + **`PT_DYNAMIC (2)`** (`DT_NULL`) | `ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV), dynamically linked, interpreter /lib/ld-linux-aarch64.so.1, no section header` | **REJECT (exit 1)** |
| `arm64_ehdr_only` | 64 B | Truncated 64-byte ELF64 AArch64 header only (no phdrs) | `ELF 64-bit LSB executable, ARM aarch64, version 1 (SYSV)` | **REJECT (exit 1)** |
| `x86_64_static` | 136 B | `ELF64 LE`, `EM_X86_64 (62)`, 1× `PT_LOAD` | `ELF 64-bit LSB executable, x86-64, version 1 (SYSV), statically linked, no section header` | **REJECT (exit 1)** |
| `arm32_static` | 100 B | `ELF32 LE`, `EM_ARM (40)`, 1× `PT_LOAD` | `ELF 32-bit LSB executable, ARM, version 1 (SYSV), statically linked, no section header` | **REJECT (exit 1)** |
| `shell_script` / `empty` / `/bin/true` | 18 B / 0 B / host | Text script / empty / host x86_64 PIE dynamic | `POSIX shell script...` / `empty` / `ELF 64-bit LSB pie executable, x86-64, ... dynamically linked` | **REJECT (exit 1)** |

*(Python `struct` recipes for `arm64_static`, `arm64_dynamic`, `x86_64_static`, and `arm32_static` are ~35 lines of pure stdlib in `tests/e2e/harness/packaging_harness.py`.)*

### 3.3 Output ZIP Inspection Contract (`packaging_harness.py`)
Using stdlib `zipfile.ZipFile`:
1. Normalized entry set (stripping leading `./` and directory entries ending in `/`) equals exact expected set:
   `{"module.prop", "customize.sh", "service.sh", "action.sh", "uninstall.sh", "bin/silod"}`.
2. Unix permission bits `(info.external_attr >> 16) & 0o777` equal `0o755` for `bin/silod`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh`.
3. `zip.read("bin/silod")` SHA-256 equals input binary SHA-256.
4. `zip.read("module.prop")` parses as `key=value` containing `id=silo`, `version=v3.0.0`, `versionCode=30000`.
5. ZERO entry path segments match `^(?:\./)?(system|vendor|product|system_ext|odm)(?:/|$)`.

---

## 4. Objective 3: CI Workflow & Metadata Surface (`.github/workflows/build.yml`, `daemon/Cargo.toml`, `proguard-rules.pro`)

### 4.1 Audited Defects vs Target Requirements (`CB-28`, `CB-29`, `CB-30`, F12)

| File & Line | Current State (Pre-Fix) | Target Requirement (E2E Assertion) |
|---|---|---|
| `.github/workflows/build.yml:3-4` | Top-level `permissions:\n  contents: write` (over-privileged for build/PR jobs) | Top-level `permissions` MUST NOT grant `write` (`contents: read` or omitted/restricted); `contents: write` MUST be scoped **exclusively** to `jobs.release.permissions`! |
| `.github/workflows/build.yml:174-175, 196` | Unescaped `${{ github.event.inputs.release_tag }}` and `${{ github.event.inputs.prerelease }}` directly inside `run:` shell script (`CB-29` GHA injection risk) | **ZERO** occurrences of `${{ github.event.inputs.* }}` or `${{ inputs.* }}` (or `${{ github.head_ref }}` / `${{ github.event.pull_request.* }}`) inside ANY step `run:` body! Must pass via step `env:` and reference quoted shell vars. (Job `if:` at line 158 is safe GHA expression context.) |
| `.github/workflows/build.yml:177` | Hardcoded `TAG="v3.0.0-$(date -u +%Y%m%d-%H%M)"` | Fallback release tag MUST derive version dynamically from `module/module.prop` (no hardcoded `v3.0.0-` literal). |
| `.github/workflows/build.yml:78` | `grep -qE '[[:space:]](system\|vendor\|product)/'` (misses `system_ext`, `odm`) | Zero-mount check in `daemon-and-module` MUST check all 5 partition roots: `system`, `vendor`, `product`, `system_ext`, `odm`. |
| `.github/workflows/build.yml:55-56, 67-74, 130, 139-145` | Has `cargo clippy --all-targets -- -D warnings`, `cargo test --verbose`, `sh -n` on all 4 module scripts, `./scripts/build_module.sh`, `./gradlew testDebugUnitTest assembleRelease`, `aapt2 dump permissions` zero-`INTERNET` gate, `release` `needs: [daemon-and-module, android-app]` | Preserve and verify ALL gates in their respective jobs (plus `bash -n scripts/build_module.sh` recommended if added; verify `sh -n` covers `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh` or `module/*.sh`). |
| `daemon/Cargo.toml:3,6` | `version = "3.0.0"`, `description = "Silo v2 kernel-backed ..."` (`CB-30` stale `"v2"`) | Parse via stdlib `tomllib`: `package.name == "silod"`, `package.version == "3.0.0"` (matches `module.prop` `version` without `v`), `package.edition == "2021"`, `package.description` MUST NOT contain `"Silo v2"` / stale `"v2"`. Release profile retains `opt-level = "z"`, `lto = true`, `codegen-units = 1`, `panic = "abort"`, `strip = true`. |
| `daemon/.cargo/config.toml:1-7` | `[target.aarch64-unknown-linux-musl]` `linker = "rust-lld"`, `+crt-static`, `relocation-model=static` | Parse via stdlib `tomllib`: verify static musl cross-compile config intact. |
| `app/app/proguard-rules.pro:1-17` | Missing explicit `enum` keep rule (`CB-30`) | Verify explicit `enum` keep rule for `dev.silo.app.ipc.**` (e.g. `-keepclassmembers enum dev.silo.app.ipc.**` or `-keep enum dev.silo.app.ipc.**`). |

### 4.2 Pure-Stdlib YAML Extractor Design for `tests/e2e/harness/ci_harness.py`
Since PyYAML is **not installed** in devbox Python 3.12, `ci_harness.py` MUST use zero third-party packages:
- Implement a clean ~120-line indentation-aware GitHub Actions YAML parser producing:
  - `WorkflowDoc(top_permissions: dict[str, str], jobs: dict[str, WorkflowJob])`
  - `WorkflowJob(id: str, permissions: dict[str, str], needs: list[str], if_expr: str, steps: list[WorkflowStep])`
  - `WorkflowStep(name: str, uses: str | None, env: dict[str, str], run_script: str | None, run_start_line: int)`
- Key parser behaviors:
  1. Strips full-line YAML comments (`# ...`) outside literal block scalars (`run: |`), so commented-out checks never satisfy assertions.
  2. Correctly parses both single-line `run: cmd` and multiline block scalars (`run: |`, `run: |-`, `run: >`, `run: >-`) by tracking indentation (`indent > run_key_indent`).
  3. Parses both mapping-style `permissions:\n  contents: read` AND scalar `permissions: read-all` / `permissions: {}` at both top-level (indent 0) and job-level (indent 4 under `jobs:`).
  4. Parses `needs:` in both flow list (`[ daemon-and-module, android-app ]`) and block list (`- daemon-and-module`) forms.
  5. Uses stdlib `tomllib` for `daemon/Cargo.toml` and `daemon/.cargo/config.toml`.

---

## 5. Objective 4: Concrete E2E Test Matrix Proposal (F11 & F12)

> Exceeds `TEST_INFRA.md` minimums (`>= 5` Tier 1 and `>= 5` Tier 2 per feature) with **6+6 tests for F11**, **8+8 tests for F12**, **4 Tier 3 pairwise tests**, and **2 Tier 4 scenario contributions** — 100% opaque-box, zero tautologies.

### 5.1 Feature F11 — Protected Package & Process/IPC Privilege Boundary

#### Tier 1 — Feature Coverage (6 Tests)
| Test ID | Target Surface | Opaque-Box Procedure & Target Assertions |
|---|---|---|
| `T1-F11-01` | Live `silod` AF_UNIX socket | Start `silod` in temp state dir; assert state dir mode `0700`, socket mode `0600` (created with `umask(0o077)`), and same-UID `SO_PEERCRED` client completes `ping` / `get_status` RPC with matching `id`. |
| `T1-F11-02` | Live `silod` `flock` (`silod.lock`) | Start `silod`; verify `silod.lock` exists and is exclusively locked (`fcntl.flock(LOCK_EX | LOCK_NB)` from test raises `BlockingIOError`); send `SIGTERM`; wait for exit `0`; assert lock is released and a fresh `silod` instance starts cleanly on the same state dir. |
| `T1-F11-03` | Live `silod` RPC (`set_app_firewall_rule`) | Attempt to add firewall block rules for protected UIDs (`0` root, `1000` system, `1001000` work-profile system, `99001` isolated process); assert EVERY RPC returns `ok == false` with descriptive error and `get_config` firewall rules remain empty. |
| `T1-F11-04` | Live `silod` RPC (Firewall, Freezer, Permissions) | Attempt `set_app_firewall_rule`, `set_freezer_entry`, and `set_app_permission_action` against protected packages (`dev.silo.app`, `com.rifsxd.ksunext`, `com.android.systemui`, `com.samsung.android.honeyboard`); assert ALL return `ok == false` and mutate zero config state. |
| `T1-F11-05` | Live `silod` socket inode-verified unlink (`CB-07` / Contract §4) | Start `silod` bound to `<tmp>/silod.sock`; record `ino_1`; externally unlink `<tmp>/silod.sock` and bind a replacement Python `AF_UNIX` socket at `<tmp>/silod.sock` with `ino_2 != ino_1`; send `SIGTERM` to `silod`; wait for clean exit `0`; assert `<tmp>/silod.sock` **STILL EXISTS** with inode `ino_2`! |
| `T1-F11-06` | Live `silod` `run_cmd` process-group timeout cleanup (`CB-17`) | Launch `silod` with a sandboxed `PATH` where `dumpsys` spawns a background grandchild holding stdout/stderr open (`(/usr/bin/sleep 30) & /usr/bin/sleep 30`) and logs grandchild PID to a file; invoke `get_privacy_access_log` (or `inspect_app_permissions`); assert RPC completes within bounded timeout (no deadlock!) AND the logged grandchild PID is dead (`os.kill(gpid, 0)` raises `ProcessLookupError`)! |

#### Tier 2 — Boundary, Corner & Negative Error Cases (6 Tests)
| Test ID | Target Surface | Opaque-Box Procedure & Target Assertions |
|---|---|---|
| `T2-F11-01` | Live `silod` duplicate instance contention | While primary `silod` holds `silod.lock` and serves RPCs on `silod.sock`, spawn a 2nd `silod` pointing to the exact same `--socket`/`--config`; assert 2nd `silod` exits non-zero quickly, primary `silod` stays alive, `silod.sock` is NOT unlinked, and subsequent RPCs to primary succeed. |
| `T2-F11-02` | Live `silod` spoofed/empty `package_name` bypass (`CB-12`) | Send `set_app_firewall_rule`, `set_freezer_entry`, and `freeze_uid_now` targeting protected UIDs (`0`, `1000`, `2000`, `1001000`, `99000`) with spoofed benign `package_name: "com.example.fake"` or empty `package_name: ""`; assert ALL are rejected (`ok == false`) and `config.json` stays clean. *(Note: also test protected package name paired with non-protected UID `10555` -> rejected!)* |
| `T2-F11-03` | Live `silod` `kill_connection` privilege & state guard (`CB-12`) | Invoke `kill_connection` targeting UID `0` and UID `1000` (and a local TCP `LISTEN` socket bound by the test process); assert protected UIDs are rejected (`ok == false` or `destroyed == 0`) and the test's `LISTEN` socket remains alive and accepting connections! |
| `T2-F11-04` | Live `silod` input validation / injection boundary | Send malformed/hostile `package_name` values (`""`, `"../etc/passwd"`, `"com.a;id"`, `"com.a\nrm"`, `"A.Upper"`, 300-char string) to `set_app_permission_action`, `set_app_perm_guard`, `set_app_firewall_rule`, and `set_freezer_entry`; assert EVERY call returns `ok == false` and daemon stays healthy. |
| `T2-F11-05` | Live `silod` IPC frame boundary (`> 4 MiB`, invalid UTF-8, invalid JSON) | Send a `4 MiB + 1024 B` line without newline, raw invalid UTF-8 bytes (`\xff\xfe\n`), and truncated JSON (`{"id":7,"cmd":\n`) over raw `AF_UNIX`; assert daemon cleanly rejects/closes offending frames without panicking and a subsequent valid `ping` on a new connection succeeds immediately. |
| `T2-F11-06` | Live `silod` concurrent client saturation (`> 16` clients) | Open 24 simultaneous `AF_UNIX` connections (exceeding `MAX_CONCURRENT_CLIENTS = 16`), hold 16 idle while sending RPCs on them; close them; assert `silod` never deadlocks or leaks client slot counts and immediately serves new connections afterward. |

---

### 5.2 Feature F12 — KernelSU Module Lifecycle, Packaging & CI Security

#### Tier 1 — Feature Coverage (8 Tests)
| Test ID | Target Surface | Opaque-Box Procedure & Target Assertions |
|---|---|---|
| `T1-F12-01` | Shell syntax + `module.prop` | Run `sh -n` on real `module/{customize,service,action,uninstall}.sh` and `bash -n` on real `scripts/build_module.sh` (all exit `0`); parse `module/module.prop` and verify `id=silo`, `version=v3.0.0`, `versionCode=30000`, `author=terenzy`, matching `daemon/Cargo.toml` version (`3.0.0`). |
| `T1-F12-02` | `module/customize.sh` (`ModuleSandbox`) | Run sandboxed `customize.sh` twice: (a) with `ro.product.cpu.abi=arm64-v8a` and pre-existing stale `.boot_fail_count` + `/data/adb/modules/silo/disable`; (b) with `getprop` returning empty and `ARCH=arm64` fallback. Assert exit `0`, `0755` on `bin/silod` & scripts, `0700` on state dir, `.boot_fail_count` deleted, AND stale `disable` marker deleted! |
| `T1-F12-03` | `module/service.sh` + real `silod` (`ModuleSandbox`) | Run sandboxed `service.sh` with `getprop sys.boot_completed` returning `["0", "1"]` and real compiled `silod` at `bin/silod`; assert state dir created `0700`, `.boot_fail_count` == `"1"` with mode `0600`, `silod.pid` created `0600` pointing to running `silod`, and `silod --socket <sock> --status` returns valid JSON with `ok == true`! |
| `T1-F12-04` | `module/action.sh` + real `silod` (`ModuleSandbox`) | (a) Plant `$MODDIR/disable` & `.boot_fail_count=2` with no running daemon -> run `action.sh` -> assert exit `0`, `$MODDIR/disable` removed, `.boot_fail_count` removed, real `silod` started and responding to `--status`, output prints `silod 3.0.0` & `[OK]`. (b) Run `action.sh` again while `silod` is healthy -> assert same `silod` PID stays alive (not unnecessarily killed) and output includes live status JSON. |
| `T1-F12-05` | `module/uninstall.sh` (`ModuleSandbox` native stub) | Pre-populate state dir with `config.json` and live verified native-ELF `silod` PID in `silod.pid`; run sandboxed `uninstall.sh`; assert exit `0`, verified `silod` PID is terminated, stub log proves `--uninstall-cleanup` (with `--config <state>/config.json`) executed while `config.json` still existed (`cfg_exists == true`), and `$STATE_DIR` is completely removed afterward. |
| `T1-F12-06` | Live `silod` CLI (`--version`, `--emergency-flush`, `--uninstall-cleanup`) | Run real `silod --version` (matches `silod 3.0.0`); run real `silod --emergency-flush --config <cfg>` (exit `0`, logs `pm unsuspend` for configured freezer entries via sandbox `PATH`); run real `silod --uninstall-cleanup --config <cfg>` with configured `scopes.apps` + `scopes.guards.locked_deny` (exit `0`, sandbox `cmd`/`pm` logs confirm AppOps reset to `default` and `user-fixed`/`revoked-compat` flags cleared!). |
| `T1-F12-07` | `scripts/build_module.sh` (`PackagingHarness`) | Run real `scripts/build_module.sh <arm64_static_elf> <tmp_out.zip>`; assert exit `0`, ZIP contains exact 6 files (`module.prop`, `customize.sh`, `service.sh`, `action.sh`, `uninstall.sh`, `bin/silod`), executable mode `0755` on scripts & `bin/silod`, byte-identical `bin/silod`, valid `module.prop`, and ZERO `system|vendor|product|system_ext|odm` entries. |
| `T1-F12-08` | `.github/workflows/build.yml` + `Cargo.toml` + `.cargo/config.toml` (`CIHarness`) | Verify least-privilege permissions (`contents: write` ONLY in `release` job, NOT top-level or build jobs), zero `${{ github.event.inputs.* }}` inside `run:` blocks, dynamic fallback version from `module/module.prop`, 5-root zero-mount regex, required gates (`cargo clippy --all-targets -- -D warnings`, `cargo test`, `sh -n`, `build_module.sh`, `gradlew testDebugUnitTest assembleRelease`, `aapt2 dump permissions` zero-`INTERNET`), `Cargo.toml` v3 description (no `"Silo v2"`), and `.cargo/config.toml` static musl flags. |

#### Tier 2 — Boundary, Corner & Negative Error Cases (8 Tests)
| Test ID | Target Surface | Opaque-Box Procedure & Target Assertions |
|---|---|---|
| `T2-F12-01` | PID-reuse & corrupt PID safety (`service.sh`, `action.sh`, `uninstall.sh`) | Spawn real `/usr/bin/sleep 60` (`DUMMY_PID`) and write it to `silod.pid`; run `service.sh`, `action.sh`, and `uninstall.sh` in separate sandboxes; assert `DUMMY_PID` is **STILL ALIVE** (`os.kill(DUMMY_PID, 0)` succeeds) in ALL THREE scripts! Also test `silod.pid` containing `"0"`, `"abc"`, `""`, and `"99999999"` -> none kill the test process group or fail abnormally. |
| `T2-F12-02` | Hung verified `silod` wait + `SIGKILL` escalation (`service.sh`, `uninstall.sh`) | Spawn native-ELF `silod` stub with `SILO_STUB_IGNORE_TERM=1` at `<sb>/data/adb/modules/silo/bin/silod`; write its PID to `silod.pid`; run `service.sh` and `uninstall.sh`; assert both scripts detect it is genuinely `silod`, wait through poll loop, escalate to `SIGKILL` so hung PID is dead (`os.kill` raises `ProcessLookupError`), and `service.sh` launches replacement cleanly afterward. |
| `T2-F12-03` | `service.sh` Dead-Man Switch (`FAIL_COUNT >= 2`) & 300s Boot Timeout | (a) Plant `.boot_fail_count=2` -> run `service.sh` -> assert stub invoked `--emergency-flush`, `$MODDIR/disable` created, `.boot_fail_count`/`silod.sock`/`silod.pid` removed, and `silod` NOT started as daemon. (b) Set `getprop sys.boot_completed` permanently `"0"` with fast-sleep shim -> run `service.sh` -> assert exit `0`, total simulated sleep duration `>= 300s`, `.boot_fail_count` NOT created, `silod` NOT launched. |
| `T2-F12-04` | `customize.sh` rejection boundaries | Run sandboxed `customize.sh` across 4 failure inputs: `ro.product.cpu.abi=x86_64` (`ARCH=x64`), `ro.product.cpu.abi=armeabi-v7a` (`ARCH=arm`), empty ABI with unset `ARCH`, and valid `arm64-v8a` with missing `$MODPATH/bin/silod`. Assert ALL 4 abort with non-zero exit and diagnostic stderr/stdout. |
| `T2-F12-05` | Portable `MODDIR` (no `/` in `$0`) & missing `$SILOD` boundary | Execute `sh action.sh` and `sh service.sh` with `cwd=<sandbox>/data/adb/modules/silo` (bare filename `$0`, no `/`) -> assert both resolve `MODDIR` cleanly and find `bin/silod` (`CB-28`). Then remove `bin/silod` -> assert `action.sh` exits `1` with `[ERROR]` and `service.sh`/`uninstall.sh` exit `0` gracefully without errors. |
| `T2-F12-06` | `scripts/build_module.sh` invalid binary rejection matrix | Run real `scripts/build_module.sh` against ALL 8 invalid inputs: nonexistent path, 0-byte empty file, POSIX shell script, host `/bin/true`, `x86_64_static` (136B), `arm32_static` (100B), **`arm64_dynamic`** (280B with `PT_DYNAMIC`), and `arm64_ehdr_only` (64B). Assert EVERY ONE exits non-zero and creates NO output ZIP. |
| `T2-F12-07` | `scripts/build_module.sh` stealth overlays, stray files & path edge cases | Inside an isolated temp repo copy: (a) plant forbidden overlays `module/{system,vendor,product,system_ext,odm}/x` one by one -> assert either `rc != 0` OR output ZIP contains zero forbidden overlay entries, AND `build_module.sh` checks all 5 names; (b) plant stray `module/untracked_junk.txt` -> assert NOT in output ZIP; (c) test overwriting existing ZIP and output path containing spaces (`<tmp>/dir with spaces/silo mod.zip`). |
| `T2-F12-08` | `CIHarness` strict workflow & metadata negative/tamper validation | Verify `.github/workflows/build.yml` has zero injection expressions in any `run:` step, zero top-level/build-job `write` permissions, `release` `needs` includes both `daemon-and-module` and `android-app`, AND self-verify the `ci_harness.py` YAML parser rejects synthetic tampered workflows (top-level `contents: write`, `${{ github.event.inputs.release_tag }}` inside `run:`, missing `odm` in zero-mount regex) so the gate itself cannot silently false-pass! |

---

### 5.3 Tier 3 Pairwise Combinations & Tier 4 Scenario Contributions

#### Tier 3 Pairwise Tests (Involving F11 & F12)
1. **`T3-P09` (`F11 × F12`) — `service.sh` Re-Execution Over Live `silod` + `silod.lock` + Socket Inode Safety**:
   Start real `silod` via sandboxed `service.sh`; record `PID_1` and verify `--status`; run `service.sh` a second time; assert `PID_1` (verified `silod`) is cleanly stopped and releases `silod.lock`, `PID_2 != PID_1` binds `silod.sock` without socket-unlink race, and `--status` immediately returns `pid == PID_2`!
2. **`T3-P10` (`F12 × F1 × F2`) — `Kunci Blokir` + AppOps Configuration Followed by `uninstall.sh` -> `silod --uninstall-cleanup`**:
   Start real `silod` in `ModuleSandbox` with sandboxed `cmd`/`pm` shims; via live IPC configure `set_app_permission_action("com.example.cam", "camera", "ignore")` and `set_app_perm_guard("com.example.cam", locked_deny=["camera"])`; write `silod.pid`; execute sandboxed `module/uninstall.sh`; assert `silod` stops cleanly, `--uninstall-cleanup --config` runs BEFORE state deletion and emits `cmd appops ... default` + `pm clear-permission-flags ... user-fixed` for `com.example.cam`, and `$STATE_DIR` is gone afterward!
3. **`T3-P11` (`F12 × F4`) — Dead-Man Switch Auto-Disable -> `action.sh` Recovery -> Live `silod --bridge` RPC**:
   Trigger `service.sh` with `.boot_fail_count=2` (creating `$MODDIR/disable`); invoke `action.sh`; assert `$MODDIR/disable` is removed, real `silod` launches, and a live `silod --bridge` subprocess connects to the recovered socket and executes JSON-RPC round-trips cleanly!
4. **`T3-P12` (`F11 × F5 × F6`) — Protected Boundary Across Firewall + Freezer + `KillConnection` Under `PanicLockdown`**:
   Enable `PanicLockdown` and attempt protected UID/package mutations across firewall rules, freezer entries/immediate freeze, and connection kills; assert every protected target remains immune while unprotected test UIDs are governed normally.

#### Tier 4 Real-World Workload Contributions (`TEST_INFRA.md § 4`)
- **`T4-S04` (`F4`, `F6`, `F8`, `F11`, `F12`)**: Combines `silod_harness` + `module_harness` to test `silod.lock` contention, `--bridge` transparent reconnect across daemon kill/restart, `service.sh` & `uninstall.sh` non-`silod` stale PID protection, and real `silod --uninstall-cleanup` end-to-end.
- **`T4-S06` (`F10`, `F12`)**: Combines `android_harness` + `packaging_harness` + `ci_harness` to verify APK zero-`INTERNET`/manifest/ProGuard gates, real `scripts/build_module.sh` ZIP packaging (using `SILOD_ARM64_BIN` when present, or the verified 136B static ARM64 ELF64 fixture when `aarch64-unknown-linux-musl` is not yet installed), and full `.github/workflows/build.yml` + `Cargo.toml` security verification.

---

## 6. Caveats & Edge Cases for Implementers

1. **No `/data/system/packages.list` on Linux Devbox**: In `T2-F11-02`, host Linux has no `/data/system/packages.list`. Check if M1 adds an optional env/CLI override (e.g. `--packages-list` or `SILO_PACKAGES_LIST`) for UID->package resolution; if not, test UID-level protection (`0`, `1000`, `2000`, `1001000`, `99000`) with empty/spoofed `package_name` AND protected package names with arbitrary user UIDs (and keep `packages.list` multi-package tests in `cargo test` unit/integration tests where `parse_packages_list_str` is directly callable).
2. **`scripts/build_module.sh` Default `OUT_ZIP` Pollution**: Never run `scripts/build_module.sh` without an explicit 2nd argument pointing into a `tempfile.TemporaryDirectory()`, otherwise it writes to `<REPO_ROOT>/dist/silo-ksu-module.zip`.
3. **Clean Git Tree Guarantee**: Every test using `ModuleSandbox` or `PackagingHarness` operates strictly inside `tempfile.TemporaryDirectory()` and cleans up all processes (via `SILO_E2E_SANDBOX_ID` `/proc` sweep) and temp files in `finally:`.
