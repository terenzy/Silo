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

## Feature Pillars

1. **Kernel Firewall & Anti-Leak Engine (`iptables-restore --noflush` + native Netlink `ipset`)**
   - **Dual-Mode**: starts in safe `Default-Allow`, switchable anytime to strict `Default-Deny` (with `REJECT` fast-fail or silent `DROP`, plus Quick Settings Tile).
   - **MediaTek & Multi-SoC Interface Awareness**: `wlan+`/`swlan+`, MediaTek `ccmni+`/`v4-ccmni+`, Qualcomm/Exynos `rmnet+`/`pdp+`/`clat+`, and VPN `tun+`/`wg+`/`ppp+`/`ipsec+`.
   - **Per-App Matrix**: Wi-Fi, Cellular, VPN, LAN allowlist, Screen-Off/Background block, Cleartext HTTP (port 80) block, Localhost (`127.0.0.1`) isolation, VPN-App exemption, and instant **`SOCK_DESTROY`** (`CONFIG_INET_DIAG_DESTROY=y`) to sever established TCP sockets in `<1 ms`.
   - **Global Guards**: LAN/RFC1918/multicast isolation, DNS Leak Guard (blocks plain port 53 bypass while keeping Android `netd` & VPN tunnels working), IPv6 VPN Leak Guard, TTL/HL normalization (`64`), Auto-Block newly installed apps, and local SAF-imported IP/CIDR blocklists via atomic pure-Rust Netlink `ipset` (`CREATE` + `SWAP` + `DESTROY`, with automatic chain fallback if stock `iptables` lacks `libxt_set`).
2. **Real-Time RAM-Only Traffic & Connection Monitor**
   - Pure-Rust Linux Netlink `NFLOG` listener (rate-limited group 42 with per-reason tags: `SILO_UID`, `SILO_LAN`, `SILO_DNS`, `SILO_HTTP`, `SILO_IPSET`, `SILO_V6LEAK`).
   - Pure-Rust `NETLINK_SOCK_DIAG` (`inet_diag`) live IPv4/IPv6 TCP/UDP socket & listening-port inspector with 1-tap `SOCK_DESTROY` and 1-tap Block UID.
3. **True Kernel App Freezer (`cgroup v2`)**
   - Freezes opted-in apps in kernel scheduler (`cgroup.freeze = 1`) on background grace expiry or screen-off, with `<1 ms` foreground auto-thaw via `/proc/<pid>/oom_score_adj` watcher (0% CPU when idle or screen off) and full thaw on shutdown/uninstall.
4. **GrapheneOS-Inspired Privacy Controls**
   - **App Isolation Scopes (`appops ignore`)**: Fake-empty Contacts, Storage/Media, SMS/CallLog, Clipboard Read, Sensors, Background/Wakelock, Location, and Camera/Mic without app crashes, plus 1-tap **Hardened Profile**.
   - **Auto-Clear Clipboard**: zero-logging clipboard hash tracker that automatically wipes stale clips.
   - **Auto-Reboot to BFU Timer + 2-Tap Panic BFU Tile**: `CLOCK_BOOTTIME`-based lockscreen countdown (ticks accurately across deep Doze, CE-unlock aware so it never loops while already in BFU, hard `>=15 min` safety floor, off by default).
   - **Wi-Fi & Bluetooth Idle Auto-Off** + **Hardware Sensors Kill-Switch** (`cmd sensor_privacy` mic/camera mute + Quick Tile).
5. **Kernel Exploit-Surface Hardening (`sysctl` Guard)**
   - Pre-apply original-value RAM snapshot with 1-tap full rollback.
   - Reversible `kernel.unprivileged_bpf_disabled=2`, `net.core.bpf_jit_harden=2`, `kernel.kptr_restrict=2`, `kernel.perf_event_paranoid=3`, `fs.suid_dumpable=0`, `fs.protected_*`, `net.ipv4.tcp_timestamps=0`, VPN-safe loose `rp_filter=2`, IPv6 `use_tempaddr=2`, plus **TCP BBRv3 & CAKE/FQ** tuner.
6. **Zero-Leak Stealth & Integrity Sentinel**
   - Read-only live security posture score (0–100): SELinux enforcing, `dmesg_restrict`, unprivileged eBPF, user-installed CA certificates, rogue listening ports on ordinary app UIDs, suspicious UID-0 processes, `/data/local/tmp` executable hygiene, and read-only SUSFS/BBG/NoMount status.
   - Local SAF JSON Backup/Restore + optional **App Disguise** (`Silo` ↔ `NetDiag`).

---

## Installation

1. Download **`silo-ksu-module-<tag>.zip`** and **`Silo-<tag>.apk`** from **GitHub Releases**.
2. Open **KernelSU-Next → Modules → `+`**, install **`silo-ksu-module-<tag>.zip`**, and reboot.
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
