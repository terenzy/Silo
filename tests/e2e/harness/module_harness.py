"""Isolated KernelSU module script sandbox (`module/*.sh`), syntax checks, and native ELF silod stub."""

from __future__ import annotations

import atexit
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import android_shims
from .silod_harness import REPO_ROOT, ensure_silod_built, sweep_sandbox_processes

MODULE_DIR = REPO_ROOT / "module"
BUILD_SCRIPT = REPO_ROOT / "scripts" / "build_module.sh"
MODULE_SCRIPTS: tuple[str, ...] = (
    "customize.sh",
    "service.sh",
    "action.sh",
    "uninstall.sh",
)

_STUB_LOCK = threading.Lock()
_STUB_CACHE_DIR: Path | None = None
_STUB_BIN_PATH: Path | None = None

_DATA_ADB_RE = re.compile(r'(?<![A-Za-z0-9_./])(/data/adb)(?=/|["\'\s;})]|$)')

_NATIVE_SILOD_STUB_C = r"""
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

static volatile sig_atomic_t g_stop = 0;

static void handle_stop(int sig) {
    (void)sig;
    g_stop = 1;
}

static void json_escape(FILE *f, const char *s) {
    fputc('"', f);
    for (; *s; s++) {
        if (*s == '"' || *s == '\\') {
            fputc('\\', f);
            fputc(*s, f);
        } else if (*s == '\n') {
            fputs("\\n", f);
        } else {
            fputc(*s, f);
        }
    }
    fputc('"', f);
}

static void log_invocation(int argc, char **argv, const char *cfg_path) {
    const char *log_path = getenv("SILO_STUB_LOG");
    if (!log_path || !log_path[0]) return;
    int cfg_exists = 0;
    if (cfg_path && cfg_path[0]) {
        struct stat st;
        if (stat(cfg_path, &st) == 0) cfg_exists = 1;
    }
    FILE *f = fopen(log_path, "a");
    if (!f) return;
    fprintf(f, "{\"pid\":%ld,\"cfg_exists\":%s,\"argv\":[", (long)getpid(), cfg_exists ? "true" : "false");
    for (int i = 0; i < argc; i++) {
        if (i > 0) fputc(',', f);
        json_escape(f, argv[i]);
    }
    fprintf(f, "]}\n");
    fclose(f);
}

int main(int argc, char **argv) {
    int is_version = 0;
    int is_status = 0;
    int is_flush = 0;
    int is_uninstall = 0;
    const char *sock_path = NULL;
    const char *cfg_path = NULL;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--version") == 0) is_version = 1;
        else if (strcmp(argv[i], "--status") == 0) is_status = 1;
        else if (strcmp(argv[i], "--emergency-flush") == 0) is_flush = 1;
        else if (strcmp(argv[i], "--uninstall-cleanup") == 0) is_uninstall = 1;
        else if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc) sock_path = argv[++i];
        else if (strcmp(argv[i], "--config") == 0 && i + 1 < argc) cfg_path = argv[++i];
    }

    log_invocation(argc, argv, cfg_path);

    if (is_version) {
        puts("silod 3.0.0");
        return 0;
    }
    if (is_flush) {
        puts("silod: emergency firewall flush, cgroup thaw & unsuspend complete");
        return 0;
    }
    if (is_uninstall) {
        puts("silod: uninstall cleanup complete");
        return 0;
    }
    if (is_status) {
        if (sock_path && strlen(sock_path) < sizeof(((struct sockaddr_un *)0)->sun_path)) {
            int cfd = socket(AF_UNIX, SOCK_STREAM, 0);
            if (cfd >= 0) {
                struct sockaddr_un caddr;
                memset(&caddr, 0, sizeof(caddr));
                caddr.sun_family = AF_UNIX;
                strncpy(caddr.sun_path, sock_path, sizeof(caddr.sun_path) - 1);
                if (connect(cfd, (struct sockaddr *)&caddr, sizeof(caddr)) == 0) {
                    close(cfd);
                    printf("{\"id\":1,\"ok\":true,\"data\":{\"version\":\"3.0.0\",\"pid\":%ld}}\n", (long)getpid());
                    return 0;
                }
                close(cfd);
            }
        }
        fprintf(stderr, "cannot connect to silod socket\n");
        return 1;
    }

    const char *ignore_term = getenv("SILO_STUB_IGNORE_TERM");
    if (ignore_term && strcmp(ignore_term, "1") == 0) {
        signal(SIGTERM, SIG_IGN);
        signal(SIGINT, SIG_IGN);
    } else {
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sa.sa_handler = handle_stop;
        sigaction(SIGTERM, &sa, NULL);
        sigaction(SIGINT, &sa, NULL);
    }

    int srv_fd = -1;
    if (sock_path && strlen(sock_path) < sizeof(((struct sockaddr_un *)0)->sun_path)) {
        unlink(sock_path);
        srv_fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (srv_fd >= 0) {
            struct sockaddr_un addr;
            memset(&addr, 0, sizeof(addr));
            addr.sun_family = AF_UNIX;
            strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);
            umask(0077);
            if (bind(srv_fd, (struct sockaddr *)&addr, sizeof(addr)) == 0) {
                listen(srv_fd, 4);
            } else {
                close(srv_fd);
                srv_fd = -1;
            }
        }
    }

    while (!g_stop) {
        usleep(50000);
    }

    const char *delay_env = getenv("SILO_STUB_EXIT_DELAY_MS");
    if (delay_env && delay_env[0]) {
        long ms = atol(delay_env);
        if (ms > 0 && ms < 10000) usleep((useconds_t)(ms * 1000));
    }

    if (srv_fd >= 0) {
        close(srv_fd);
        if (sock_path) unlink(sock_path);
    }
    return 0;
}
"""


def _cleanup_stub_cache() -> None:
    global _STUB_CACHE_DIR, _STUB_BIN_PATH
    if _STUB_CACHE_DIR is not None and _STUB_CACHE_DIR.exists():
        shutil.rmtree(_STUB_CACHE_DIR, ignore_errors=True)
    _STUB_CACHE_DIR = None
    _STUB_BIN_PATH = None


atexit.register(_cleanup_stub_cache)


def ensure_native_silod_stub() -> Path:
    """Compile a tiny native C ELF binary once per session so /proc/$PID/{exe,cmdline,comm} all match `silod`."""
    global _STUB_CACHE_DIR, _STUB_BIN_PATH
    with _STUB_LOCK:
        if _STUB_BIN_PATH is not None and _STUB_BIN_PATH.is_file():
            return _STUB_BIN_PATH
        cache_dir = Path(tempfile.mkdtemp(prefix="silo_e2e_native_stub_"))
        src = cache_dir / "silod_stub.c"
        out = cache_dir / "silod"
        src.write_text(_NATIVE_SILOD_STUB_C, encoding="utf-8")
        proc = subprocess.run(
            ["gcc", "-O2", "-Wall", "-Wextra", "-o", str(out), str(src)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if proc.returncode != 0:
            shutil.rmtree(cache_dir, ignore_errors=True)
            raise RuntimeError(
                f"Failed to compile native C ELF silod stub via gcc (rc={proc.returncode}):\n{proc.stderr}"
            )
        out.chmod(0o755)
        _STUB_CACHE_DIR = cache_dir
        _STUB_BIN_PATH = out
        return out


def check_shell_syntax() -> dict[str, tuple[int, str]]:
    """Run real `sh -n` on all 4 module scripts and `bash -n` on `scripts/build_module.sh`."""
    results: dict[str, tuple[int, str]] = {}
    for name in MODULE_SCRIPTS:
        path = MODULE_DIR / name
        if not path.is_file():
            raise AssertionError(f"Missing module script: {path}")
        proc = subprocess.run(["sh", "-n", str(path)], capture_output=True, text=True, timeout=15)
        results[f"module/{name}"] = (proc.returncode, (proc.stdout + proc.stderr).strip())
    proc = subprocess.run(["bash", "-n", str(BUILD_SCRIPT)], capture_output=True, text=True, timeout=15)
    results["scripts/build_module.sh"] = (proc.returncode, (proc.stdout + proc.stderr).strip())
    return results


def virtualize_script_text(raw_text: str, sandbox_root: Path) -> str:
    """Rewrite `/data/adb` on non-comment lines into `<sandbox_root>/data/adb` without altering `/proc`."""
    sb = str(sandbox_root)
    out_lines: list[str] = []
    for line in raw_text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            out_lines.append(line)
        else:
            out_lines.append(_DATA_ADB_RE.sub(lambda m: f"{sb}{m.group(1)}", line))
    return "\n".join(out_lines) + "\n"


@dataclass
class ScriptRunResult:
    returncode: int
    stdout: str
    stderr: str


class ModuleSandbox:
    """Isolated `/data/adb` virtualization sandbox for testing real `module/*.sh` scripts."""

    def __init__(
        self,
        *,
        silod_mode: str = "native_stub",
        silod_bin: Path | None = None,
        sleep_scale: dict[str, float] | None = None,
        getprop_map: dict[str, Any] | None = None,
    ) -> None:
        if silod_mode not in ("native_stub", "real", "none"):
            raise ValueError(f"Invalid silod_mode: {silod_mode!r}")
        self.silod_mode = silod_mode
        self._explicit_silod_bin = silod_bin
        self._initial_sleep_scale = sleep_scale or {"1": 0.12, "2": 0.25, "5": 0.02, "default": 0.05}
        self._initial_getprop = getprop_map or {
            "sys.boot_completed": "1",
            "sys.user.0.ce_available": "true",
            "ro.product.cpu.abi": "arm64-v8a",
            "ro.product.cpu.abilist64": "arm64-v8a",
        }
        self.sandbox_id = uuid.uuid4().hex
        self._tmpdir_obj: tempfile.TemporaryDirectory[str] | None = None
        self.root: Path = Path()
        self.bin_dir: Path = Path()
        self.logs_dir: Path = Path()
        self.state_dir: Path = Path()
        self.mod_dir: Path = Path()
        self.mod_update_dir: Path = Path()
        self.silod_path: Path = Path()
        self.update_silod_path: Path = Path()
        self.stub_log_path: Path = Path()
        self.sleep_log_path: Path = Path()
        self.getprop_log_path: Path = Path()
        self._tracked_procs: list[subprocess.Popen[Any]] = []
        self._run_counter = 0

    def __enter__(self) -> "ModuleSandbox":
        self._tmpdir_obj = tempfile.TemporaryDirectory(prefix="silo_mod_e2e_")
        self.root = Path(self._tmpdir_obj.name)
        os.chmod(self.root, 0o700)

        self.bin_dir = self.root / "bin"
        self.logs_dir = self.root / "logs"
        self.state_dir = self.root / "data" / "adb" / "silo"
        self.mod_dir = self.root / "data" / "adb" / "modules" / "silo"
        self.mod_update_dir = self.root / "data" / "adb" / "modules_update" / "silo"

        for d in (self.bin_dir, self.logs_dir, self.mod_dir / "bin", self.mod_update_dir / "bin"):
            d.mkdir(parents=True, exist_ok=True)

        self.stub_log_path = self.logs_dir / "silod_stub.log"
        self.sleep_log_path = self.logs_dir / "sleep.log"
        self.getprop_log_path = self.logs_dir / "getprop.log"

        # Install virtualized copies of all real module/*.sh scripts + module.prop
        for target_dir in (self.mod_dir, self.mod_update_dir):
            shutil.copy2(MODULE_DIR / "module.prop", target_dir / "module.prop")
            for name in MODULE_SCRIPTS:
                raw = (MODULE_DIR / name).read_text(encoding="utf-8")
                dst = target_dir / name
                dst.write_text(virtualize_script_text(raw, self.root), encoding="utf-8")
                dst.chmod(0o755)

        self.silod_path = self.mod_dir / "bin" / "silod"
        self.update_silod_path = self.mod_update_dir / "bin" / "silod"

        if self.silod_mode == "real":
            src_bin = self._explicit_silod_bin or ensure_silod_built()
            shutil.copy2(src_bin, self.silod_path)
            shutil.copy2(src_bin, self.update_silod_path)
            self.silod_path.chmod(0o755)
            self.update_silod_path.chmod(0o755)
            # Also install Android shims for real silod (--emergency-flush / --uninstall-cleanup)
            self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            android_shims.install_shims(self.bin_dir, self.state_dir)
        elif self.silod_mode == "native_stub":
            stub_bin = ensure_native_silod_stub()
            shutil.copy2(stub_bin, self.silod_path)
            shutil.copy2(stub_bin, self.update_silod_path)
            self.silod_path.chmod(0o755)
            self.update_silod_path.chmod(0o755)

        self._install_module_shims()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        try:
            # Stop tracked helper processes (e.g., dummy /usr/bin/sleep)
            for p in self._tracked_procs:
                if p.poll() is None:
                    try:
                        p.terminate()
                        p.wait(timeout=1.0)
                    except Exception:
                        try:
                            p.kill()
                            p.wait(timeout=1.0)
                        except Exception:
                            pass
            self._tracked_procs.clear()
            # Stop any pid recorded in silod.pid if it lives inside our sandbox
            pid_file = self.state_dir / "silod.pid"
            if pid_file.is_file():
                try:
                    pid = int(pid_file.read_text(encoding="utf-8").strip())
                    if pid > 1 and pid != os.getpid():
                        exe_path = os.readlink(f"/proc/{pid}/exe")
                        if str(self.root) in exe_path:
                            os.kill(pid, signal.SIGTERM)
                except Exception:
                    pass
            sweep_sandbox_processes(self.sandbox_id)
        finally:
            if self._tmpdir_obj is not None:
                self._tmpdir_obj.cleanup()
                self._tmpdir_obj = None

    def _install_module_shims(self) -> None:
        self.set_getprop_map(self._initial_getprop)
        self.set_sleep_config(self._initial_sleep_scale)

    def set_getprop_map(self, mapping: dict[str, Any]) -> None:
        (self.logs_dir / "getprop_map.json").write_text(json.dumps(mapping), encoding="utf-8")
        gp_bin = self.bin_dir / "getprop"
        has_sequence = any(isinstance(v, list) for v in mapping.values())
        if not has_sequence:
            # Fast pure-POSIX sh shim for scalar maps (avoids 150x Python startup in 300s boot-wait test)
            case_lines: list[str] = []
            for k, v in mapping.items():
                sk = str(k).replace("'", "'\"'\"'")
                sv = str(v).replace("'", "'\"'\"'")
                case_lines.append(f"  '{sk}') out='{sv}' ;;")
            cases_body = "\n".join(case_lines)
            sh_script = (
                "#!/bin/sh\n"
                'key="${1:-}"\n'
                'out=""\n'
                'case "$key" in\n'
                f"{cases_body}\n"
                "esac\n"
                f'printf \'{{"key":"%s","val":"%s"}}\\n\' "$key" "$out" >> "{self.getprop_log_path}"\n'
                'printf "%s\\n" "$out"\n'
            )
            gp_bin.write_text(sh_script, encoding="utf-8")
            gp_bin.chmod(0o755)
            return

        py = os.environ.get("PYTHON3_BIN") or shutil.which("python3") or "/usr/bin/python3"
        getprop_script = (
            f"#!{py}\n"
            "import fcntl, json, os, sys\n"
            "from pathlib import Path\n"
            f"cfg = Path({str(self.logs_dir / 'getprop_map.json')!r})\n"
            f"log = Path({str(self.getprop_log_path)!r})\n"
            f"lck = Path({str(self.logs_dir / 'getprop.lock')!r})\n"
            "key = sys.argv[1] if len(sys.argv) > 1 else ''\n"
            "with open(lck, 'a+') as lf:\n"
            "    fcntl.flock(lf.fileno(), fcntl.LOCK_EX)\n"
            "    data = json.loads(cfg.read_text()) if cfg.exists() else {}\n"
            "    val = data.get(key, '')\n"
            "    if isinstance(val, list):\n"
            "        out = str(val.pop(0)) if len(val) > 1 else (str(val[0]) if val else '')\n"
            "        data[key] = val\n"
            "        cfg.write_text(json.dumps(data))\n"
            "    else:\n"
            "        out = str(val)\n"
            "    with open(log, 'a') as outf:\n"
            "        outf.write(json.dumps({'key': key, 'val': out}) + '\\n')\n"
            "    fcntl.flock(lf.fileno(), fcntl.LOCK_UN)\n"
            "sys.stdout.write(out + '\\n')\n"
        )
        gp_bin.write_text(getprop_script, encoding="utf-8")
        gp_bin.chmod(0o755)

    def set_sleep_config(self, cfg: dict[str, float]) -> None:
        (self.logs_dir / "sleep_cfg.json").write_text(json.dumps(cfg), encoding="utf-8")
        default_dur = float(cfg.get("default", 0.02))
        cases: list[str] = []
        for k, v in cfg.items():
            if k == "default":
                continue
            dur = float(v)
            if dur > 0:
                cases.append(f'  "{k}"|"{k}s") /bin/sleep {dur} ;;')
            else:
                cases.append(f'  "{k}"|"{k}s") : ;;')
        default_cmd = f"/bin/sleep {default_dur}" if default_dur > 0 else ":"
        case_body = "\n".join(cases)
        sleep_sh = (
            "#!/bin/sh\n"
            'arg="${1:-0}"\n'
            'num="${arg%s}"\n'
            f'printf "%s\\n" "$num" >> "{self.sleep_log_path}"\n'
            'case "$arg" in\n'
            f"{case_body}\n"
            f"  *) {default_cmd} ;;\n"
            "esac\n"
        )
        sl_bin = self.bin_dir / "sleep"
        sl_bin.write_text(sleep_sh, encoding="utf-8")
        sl_bin.chmod(0o755)

    def read_sleep_durations(self) -> list[float]:
        if not self.sleep_log_path.is_file():
            return []
        vals: list[float] = []
        for ln in self.sleep_log_path.read_text(encoding="utf-8").splitlines():
            ln = ln.strip()
            if ln:
                vals.append(float(ln))
        return vals

    def read_stub_log(self) -> list[dict[str, Any]]:
        if not self.stub_log_path.is_file():
            return []
        out: list[dict[str, Any]] = []
        for ln in self.stub_log_path.read_text(encoding="utf-8").splitlines():
            ln = ln.strip()
            if ln:
                out.append(json.loads(ln))
        return out

    def read_cmd_log(self) -> list[dict[str, Any]]:
        return android_shims.read_cmd_log(self.state_dir)

    def build_env(self, extra: dict[str, str] | None = None) -> dict[str, str]:
        env = os.environ.copy()
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}"
        env["SILO_E2E_SANDBOX_ID"] = self.sandbox_id
        env["SILO_STUB_LOG"] = str(self.stub_log_path)
        if extra:
            env.update(extra)
        return env

    def spawn_non_silod_dummy(self, seconds: int = 60) -> subprocess.Popen[bytes]:
        """Spawn genuine non-silod `/usr/bin/sleep` via absolute path (never using sandbox PATH!)."""
        p = subprocess.Popen(
            ["/usr/bin/sleep", str(seconds)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        self._tracked_procs.append(p)
        return p

    def spawn_verified_silod_stub(
        self,
        *,
        ignore_term: bool = False,
        socket_path: Path | None = None,
    ) -> subprocess.Popen[bytes]:
        """Spawn authentic native ELF binary at `<sandbox>/data/adb/modules/silo/bin/silod`."""
        args = [str(self.silod_path)]
        if socket_path is not None:
            args.extend(["--socket", str(socket_path)])
        extra = {"SILO_STUB_IGNORE_TERM": "1"} if ignore_term else {}
        p = subprocess.Popen(
            args,
            env=self.build_env(extra),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        self._tracked_procs.append(p)
        time.sleep(0.08)
        return p

    def _run_file_backed(
        self,
        cmd: list[str],
        *,
        cwd: Path | None = None,
        env: dict[str, str] | None = None,
        timeout: float = 20.0,
    ) -> ScriptRunResult:
        """Run script with file-backed stdout/stderr (prevents hangs on inherited background FDs!)."""
        self._run_counter += 1
        out_p = self.logs_dir / f"stdout_{self._run_counter}.txt"
        err_p = self.logs_dir / f"stderr_{self._run_counter}.txt"
        with open(out_p, "wb") as out_f, open(err_p, "wb") as err_f:
            proc = subprocess.Popen(
                cmd,
                cwd=str(cwd) if cwd is not None else str(self.root),
                env=env or self.build_env(),
                stdin=subprocess.DEVNULL,
                stdout=out_f,
                stderr=err_f,
                start_new_session=True,
            )
            try:
                rc = proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except OSError:
                    proc.kill()
                proc.wait(timeout=3.0)
                raise TimeoutError(
                    f"Module script timed out after {timeout}s: {cmd}\n"
                    f"STDOUT:\n{out_p.read_text(errors='replace')}\n"
                    f"STDERR:\n{err_p.read_text(errors='replace')}"
                )
        return ScriptRunResult(
            returncode=rc,
            stdout=out_p.read_text(encoding="utf-8", errors="replace"),
            stderr=err_p.read_text(encoding="utf-8", errors="replace"),
        )

    def run_script(
        self,
        name: str,
        *,
        bare_arg0: bool = False,
        extra_env: dict[str, str] | None = None,
        timeout: float = 20.0,
    ) -> ScriptRunResult:
        if bare_arg0:
            return self._run_file_backed(
                ["/bin/sh", name],
                cwd=self.mod_dir,
                env=self.build_env(extra_env),
                timeout=timeout,
            )
        return self._run_file_backed(
            ["/bin/sh", str(self.mod_dir / name)],
            cwd=self.root,
            env=self.build_env(extra_env),
            timeout=timeout,
        )

    def run_customize(
        self,
        *,
        arch: str | None = None,
        timeout: float = 15.0,
    ) -> ScriptRunResult:
        """Source `customize.sh` with KernelSU's `ui_print()` and `abort()` helpers."""
        env = self.build_env({"MODPATH": str(self.mod_update_dir)})
        if arch is None:
            env.pop("ARCH", None)
        else:
            env["ARCH"] = arch
        bootstrap = (
            'ui_print() { printf "%s\\n" "$*"; }\n'
            'abort() { printf "ABORT: %s\\n" "$*" >&2; exit 1; }\n'
            '. "$1"\n'
        )
        return self._run_file_backed(
            ["/bin/sh", "-c", bootstrap, "--", str(self.mod_update_dir / "customize.sh")],
            cwd=self.mod_update_dir,
            env=env,
            timeout=timeout,
        )
