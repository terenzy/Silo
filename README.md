# Silo — Kernel-Backed Privacy & Security Suite for Rooted Android (Linux 6.x)

**Silo** is a personal, zero-panic, zero-mount, strict offline-first privacy and security suite engineered for rooted Android 12–16 devices (tested and tuned for **Samsung Galaxy A14 5G `SM-A146P` / MediaTek Dimensity 700 / One UI 7 Android 15 / Linux 6.6.127 + KernelSU-Next + SUSFS**).

---

## Core Design Guarantees

1. **Zero Kernel Panic Architecture (Native-First Mainline Subsystems)**
   - Uses **only** verified mainline Linux 6.6 subsystems (`iptables`/`ip6tables` Netfilter, native Linux Netlink `ipset`, `NFLOG`, `inet_diag` + `SOCK_DESTROY`, `cgroup v2 freezer`, `sysctl`, `CLOCK_BOOTTIME`).
   - Zero out-of-tree kernel modules (LKM/KPM), zero experimental inline hooks, zero kernel re-flashing required.
2. **Anti-Bootloop 3-Layer Safety & Download Mode Immunity**
   - Never touches Download Mode, bootloader, partitions, USB gadget sysfs, or `/system`/`/vendor`.
   - `service.sh` starts strictly **after** `sys.boot_completed=1` + 5s settle window.
   - **Dead-Man Switch**: marks `.boot_fail_count` before launching `silod` and clears it automatically after **45s** of stable uptime. If 2 consecutive boots fail stability, Silo auto-disables itself (`disable` marker) and runs `--emergency-flush`.
   - **Hard-Protected Critical UIDs**: UID `0..9999` (`root`, `system`, `radio`, `wifi`, `netd`, `network_stack`, `shell/ADB`), isolated UIDs (`90000..99999`), multi-user system UIDs, Silo itself, and root managers can never be blocked, frozen, or isolated.
3. **Zero-Leak Stealth & Strict Offline-First**
   - **Zero `android.permission.INTERNET`** in `Silo.apk` (enforced by manifest removal AND verified by CI `aapt2 dump permissions` gate).
   - **Zero `/system` or `/vendor` mounts** (no overlay folder in the KernelSU module).
   - **Zero open TCP/UDP ports**: UI communicates with `silod` via a persistent SELinux-clean `su -c silod --bridge` pipe into a `0600` root-only Unix domain socket (`/data/adb/silo/silod.sock`) verified by `SO_PEERCRED` (`uid == 0`).
   - **Zero-Disk Telemetry**: live blocked packet events live strictly in a bounded **RAM-only ring buffer** (2,048 entries) and vanish completely on reboot/BFU.
   - Read-only **Stealth Sentinel** never conflicts with `a146p_stealth_companion`, SUSFS, NoMount, HMA-OSS, or Baseband-guard.

---

## Feature Pillars (v3.0.0)

1. **Kernel Firewall & Anti-Leak Engine (`iptables-restore --noflush -w` + native Netlink `ipset`)**
   - **Dual-Mode**: starts in safe `Default-Allow`, switchable anytime to strict `Default-Deny` (with `REJECT` fast-fail or silent `DROP`, plus Quick Settings Tile).
   - **Auto-Degrade OEM Resilience**: automatically degrades optional targets (`NFLOG`/`TTL`/`HL`/`xt_recent`/`xt_set`) if an OEM `iptables` binary omits a module, and isolates IPv6 errors so IPv4 enforcement never fails.
   - **Per-App Matrix & Temporary Unblock**: Wi-Fi, Cellular (`ccmni+`/`rmnet+`), VPN, LAN allowlist, clear **Block While Screen Off**, Cleartext HTTP (port 80) block, Localhost (`127.0.0.1`) isolation, VPN-App exemption, **Temporary Internet Unblock Timer (15m / 30m / 60m)** with automatic kernel re-lock + `SOCK_DESTROY`.
   - **Global Guards**: LAN/RFC1918/multicast isolation, DNS Leak Guard, IPv6 VPN Leak Guard, **Anti-Port-Scan (`xt_recent`)**, TTL/HL normalization (`64`), Auto-Block newly installed apps, and local SAF-imported IP/CIDR blocklists via atomic pure-Rust Netlink `ipset`.
2. **Real-Time Permission & AppOps Enforcer + Privacy Access Timeline (Zero Gimmicks)**
   - **Live Real-Time Sync**: syncs separated **Microphone** & **Camera** mute state directly from `dumpsys sensor_privacy`, live Android runtime grant flags (`PackageManager`), and live `cmd appops get` on every `ON_RESUME` and 4s foreground tick.
   - **4-Mode Per-Category Controller**: Normal, Foreground-Only (`foreground`), Fake-Empty (`appops ignore`), and Real Revoke (`pm revoke` + `appops ignore`) across 10 honest categories (explicitly noting Media controls MediaStore, not POSIX `/sdcard` folder names handled by SUSFS).
   - **Daemon Enforcer**: per-app **Auto-Revoke on Close** (revokes sensitive permissions automatically when backgrounded/screen off) and **Strict Lock** (re-enforces immediately if changed externally).
   - **Global Permission Audit ("Who Has Access?") & Real-Time Access Timeline**: filter apps by currently granted sensitive permissions with 1-tap Bulk Revoke, plus live `dumpsys appops --op` timeline showing recent & active Mic/Camera/Location/Clipboard/Contacts accesses.
   - **Actionable Risk Breakdown**: classifies risk from **currently granted** sensitive permissions + embedded tracker SDKs, showing exact reasons and 1-tap mitigation.
3. **GrapheneOS-Aligned Privacy Automation, Radio Privacy & Panic Lockdown**
   - **1-Tap Non-Destructive Panic Lockdown**: engages Default-Deny firewall, severs all active user TCP sockets (`SOCK_DESTROY`), mutes Mic & Camera, disables Wi-Fi & Bluetooth, and wipes Clipboard.
   - **Safe USB & ADB Lockscreen Guard**: sets `adb_enabled=0` and USB charge-only via Android framework on lock (stays off on unlock; never touches hardware USB sysfs or Download Mode).
   - **Network & Radio Privacy**: Enhanced Wi-Fi MAC Randomization, disable background Wi-Fi/BLE scanning, optional disable Google Captive Portal pings, and 1-click **Private DNS (DoT)**.
   - **True Kernel App Freezer (`cgroup v2`) & Deep Suspend (`pm suspend`)**, Auto-Reboot to BFU (`CLOCK_BOOTTIME`), Wi-Fi/BT Idle Auto-Off, and Native + Root Clipboard Auto-Clear.
4. **Reversible Kernel Hardening (`sysctl` Guard) & Live Proof Verifier**
   - Pre-apply RAM snapshot with 1-tap rollback: `unprivileged_bpf_disabled=2`, `bpf_jit_harden=2`, `kptr_restrict=2`, `perf_event_paranoid=3`, `randomize_va_space=2`, `yama.ptrace_scope=2` (if supported), `suid_dumpable=0`, `tcp_timestamps=0`, `tcp_rfc1337=1`, ICMP redirect/source-route/broadcast guards, VPN-safe `rp_filter=2`, IPv6 `use_tempaddr=2`, and TCP BBRv3 / CAKE / FQ.
   - **Live Enforcement Proof** verifies live kernel `iptables` hooks, rule counts, `/proc/sys` values, `cgroup.events`, `pm suspend`, `appops`, `sensor_privacy`, and `Private DNS`/`Radio Privacy`.
5. **Minimalist Borderless UI, Bilingual (ID / EN) & 3 Themes**
   - Clean 4-tab layout (`Home / Apps / Monitor / System`), instant **Auto / Bahasa Indonesia / English**, and **Obsidian / AMOLED / Dynamic** themes.

---

## Installation / Upgrade to v3.0.0

1. Download **`silo-ksu-module-<tag>.zip`** and **`Silo-<tag>.apk`** from **GitHub Releases**.
2. Open **KernelSU-Next → Modules → `+`**, install **`silo-ksu-module-<tag>.zip`**, and **reboot** (required so `silod` v3.0.0 replaces the older daemon).
3. Install **`Silo-<tag>.apk`**.
4. Open **KernelSU-Next → Superuser** and grant root access to **Silo**.
5. Launch **Silo** — everything starts in **Safe-First** mode until you enable controls or pick a preset.

---

## Building Locally

```bash
# 1. Test & cross-compile static ARM64 Rust daemon + KernelSU module
cd daemon
cargo test
cargo build --release --target aarch64-unknown-linux-musl
cd ..
./scripts/build_module.sh

# 2. Test & build Android Release APK
cd app
./gradlew testDebugUnitTest assembleRelease
```

### Optional Custom Release Signing (GitHub Actions Secrets)

Configure these optional repository secrets in GitHub Settings → Secrets and variables → Actions to sign release APKs with your private keystore:
- `SILO_KEYSTORE_BASE64`
- `SILO_KEYSTORE_PASSWORD`
- `SILO_KEY_ALIAS`
- `SILO_KEY_PASSWORD`

---

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
