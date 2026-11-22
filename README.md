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

## Feature Pillars (v2.0.0)

1. **Kernel Firewall & Anti-Leak Engine (`iptables-restore --noflush -w` + native Netlink `ipset`)**
   - **Dual-Mode**: starts in safe `Default-Allow`, switchable anytime to strict `Default-Deny` (with `REJECT` fast-fail or silent `DROP`, plus Quick Settings Tile).
   - **Auto-Degrade OEM Resilience**: automatically degrades optional targets (`NFLOG`/`TTL`/`HL`/`xt_recent`/`xt_set`) if an OEM `iptables` binary omits a module, and isolates IPv6 errors so IPv4 enforcement never fails.
   - **MediaTek & Multi-SoC Interface Awareness**: `wlan+`/`swlan+`, MediaTek `ccmni+`/`v4-ccmni+`, Qualcomm/Exynos `rmnet+`/`pdp+`/`clat+`, and VPN `tun+`/`wg+`/`ppp+`/`ipsec+`.
   - **Per-App Matrix & Temporary Unblock**: Wi-Fi, Cellular, VPN, LAN allowlist, Screen-Off/Background block, Cleartext HTTP (port 80) block, Localhost (`127.0.0.1`) isolation, VPN-App exemption, **Temporary Internet Unblock Timer (15m / 30m / 60m)** with automatic kernel re-lock + `SOCK_DESTROY`, and instant **`SOCK_DESTROY`** (`CONFIG_INET_DIAG_DESTROY=y`) to sever established TCP sockets in `<1 ms`.
   - **Global Guards**: LAN/RFC1918/multicast isolation, DNS Leak Guard, IPv6 VPN Leak Guard, **Anti-Port-Scan & Inbound Flood Guard (`xt_recent`)**, TTL/HL normalization (`64`), Auto-Block newly installed apps, and local SAF-imported IP/CIDR blocklists via atomic pure-Rust Netlink `ipset`.
2. **Real-Time RAM-Only Traffic Monitor & Live Proof Verifier**
   - **Live Enforcement Proof**: real-time verification against live kernel tables (`iptables -w -C OUTPUT -j SILO_OUT`, active rule counts in `SILO_OUT`/`SILO_IN` IPv4/IPv6, live `/proc/sys` values, `cgroup.events` frozen nodes, `cmd appops get`, and Private DNS).
   - Pure-Rust Linux Netlink `NFLOG` listener (rate-limited group 42 with per-reason tags: `SILO_UID`, `SILO_LAN`, `SILO_DNS`, `SILO_HTTP`, `SILO_IPSET`, `SILO_V6LEAK`, `SILO_SCAN`).
   - Pure-Rust `NETLINK_SOCK_DIAG` (`inet_diag`) live IPv4/IPv6 TCP/UDP socket & listening-port inspector with 1-tap `SOCK_DESTROY` and 1-tap Block UID.
3. **True Kernel App Freezer (`cgroup v2`) & Deep Suspend**
   - Discovers real per-process `/proc/<pid>/cgroup` nodes and verifies `cgroup.events` (`frozen 1`) for Soft Freeze, plus real **Deep Suspend (`pm suspend --user 0` + `am force-stop`)** that prevents cold background starts, with automatic `<1 ms` foreground thaw and full clean unsuspend on shutdown/uninstall.
4. **Privacy Controls, Private DNS (DoT) & Offline Risk Scanner**
   - **Private DNS (DoT) 1-Click Switcher**: instant switch between Off, Auto, Quad9, Mullvad, AdGuard, Cloudflare, or custom DoT hostname via `settings put global`.
   - **Offline Privacy Risk & Tracker Scanner**: 100% local `PackageManager` audit of sensitive permissions and known tracker SDK receivers/services (Zero internet required).
   - **App Isolation Scopes (`appops ignore`)**: Fake-empty Contacts, Storage/Media, SMS/CallLog, Clipboard Read, Sensors, Background/Wakelock, Location, and Camera/Mic without app crashes, plus verified read-back via `cmd appops get`.
   - **Native + Root Clipboard Clear & Auto-Clear**, **Auto-Reboot to BFU Timer + 2-Tap Panic BFU Tile**, **Wi-Fi/BT Idle Auto-Off**, and **Hardware Sensors Kill-Switch** (`cmd sensor_privacy` with `dumpsys sensor_privacy` read-back).
5. **Kernel Exploit-Surface Hardening (`sysctl` Guard) & Stealth Sentinel**
   - Pre-apply RAM snapshot with 1-tap full rollback (`unprivileged_bpf_disabled=2`, `bpf_jit_harden=2`, `kptr_restrict=2`, `perf_event_paranoid=3`, `suid_dumpable=0`, `tcp_timestamps=0`, VPN-safe `rp_filter=2`, IPv6 `use_tempaddr=2`, TCP BBRv3 & CAKE/FQ).
   - Read-only live security posture score (0–100) + SUSFS/BBG/NoMount visibility + Local SAF JSON Backup/Restore + App Disguise (`Silo` ↔ `NetDiag`).
6. **Minimalist Borderless UI, Bilingual (ID / EN) & 3 Themes**
   - Clean 4-tab layout (`Home / Apps / Monitor / System`) with real app icons, hairline dividers, segmented pills, per-app bottom sheets, instant **Bahasa Indonesia / English / Auto** switcher, and **Obsidian / AMOLED Pure Black / Dynamic Material You** themes.

---

## Installation / Upgrade to v2.0.0

1. Download **`silo-ksu-module-<tag>.zip`** and **`Silo-<tag>.apk`** from **GitHub Releases**.
2. Open **KernelSU-Next → Modules → `+`**, install **`silo-ksu-module-<tag>.zip`**, and **reboot** (required so the updated `silod` v2.0.0 daemon takes effect).
3. Install **`Silo-<tag>.apk`**.
4. Open **KernelSU-Next → Superuser** and grant root access to **Silo** (automatically hidden on `SM-A146P-KernelSU-SUSFS` via Layer 1 `__ksu_is_allow_uid`; also add to HMA-OSS hide templates if desired).
5. Launch **Silo** — everything starts in **Safe-First** mode (firewall, freezer, hardening, and BFU timer OFF until you enable them or select a preset).

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
