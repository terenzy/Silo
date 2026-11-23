"""Real compiled `silod` process manager, AF_UNIX RPC client, --bridge client, and CLI runner."""

from __future__ import annotations

import fcntl
import json
import os
import re
import signal
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from . import android_shims

REPO_ROOT = Path(__file__).resolve().parents[3]
DAEMON_MANIFEST = REPO_ROOT / "daemon" / "Cargo.toml"

_SESSION_LOCK = threading.Lock()
_CACHED_SILOD_BIN: Path | None = None

PROTECTED_PACKAGES: tuple[str, ...] = (
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.settings",
    "com.android.networkstack",
    "com.android.networkstack.tethering",
    "com.android.providers.settings",
    "com.android.providers.telephony",
    "com.android.shell",
    "com.android.bluetooth",
    "com.android.se",
    "com.samsung.android.honeyboard",
    "com.google.android.inputmethod.latin",
    "dev.silo.app",
    "com.rifsxd.ksunext",
    "me.weishu.kernelsu",
    "com.sukisu.ultra",
    "com.resukisu.resukisu",
    "me.bmax.apatch",
)

ALL_PERM_CATEGORIES: tuple[str, ...] = (
    "camera",
    "microphone",
    "location",
    "background_location",
    "contacts",
    "media",
    "sms_calllog",
    "clipboard",
    "sensors",
    "background",
)

FOREGROUND_SUPPORTED_CATEGORIES: frozenset[str] = frozenset(
    {"camera", "microphone", "location", "sensors"}
)
APPOPS_ONLY_CATEGORIES: frozenset[str] = frozenset({"clipboard", "background"})


def _cargo_env() -> dict[str, str]:
    env = os.environ.copy()
    path_parts = env.get("PATH", "").split(os.pathsep)
    if "/opt/cargo/bin" not in path_parts and Path("/opt/cargo/bin").is_dir():
        env["PATH"] = f"/opt/cargo/bin{os.pathsep}{env.get('PATH', '')}"
    return env


def ensure_silod_built() -> Path:
    """Resolve or compile the real host `silod` binary (memoized per session, flock-locked)."""
    global _CACHED_SILOD_BIN
    with _SESSION_LOCK:
        override = os.environ.get("SILOD_BIN", "").strip()
        if override:
            p = Path(override)
            if not p.is_file() or not os.access(p, os.X_OK):
                raise RuntimeError(
                    f"SILOD_BIN={override!r} does not exist or is not executable"
                )
            _CACHED_SILOD_BIN = p.resolve()
            return _CACHED_SILOD_BIN

        if _CACHED_SILOD_BIN is not None and _CACHED_SILOD_BIN.is_file():
            return _CACHED_SILOD_BIN

        target_dir_env = os.environ.get("CARGO_TARGET_DIR", "").strip()
        target_dir = Path(target_dir_env) if target_dir_env else (REPO_ROOT / "daemon" / "target")
        bin_path = target_dir / "debug" / "silod"

        if os.environ.get("SILO_SKIP_BUILD") == "1":
            if not bin_path.is_file() or not os.access(bin_path, os.X_OK):
                raise RuntimeError(
                    f"SILO_SKIP_BUILD=1 but compiled silod binary not found at {bin_path}"
                )
            _CACHED_SILOD_BIN = bin_path.resolve()
            return _CACHED_SILOD_BIN

        lock_path = Path(tempfile.gettempdir()) / "silo_e2e_cargo.lock"
        with open(lock_path, "a+", encoding="utf-8") as lf:
            fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
            try:
                cmd = ["cargo", "build", "--manifest-path", str(DAEMON_MANIFEST)]
                proc = subprocess.run(
                    cmd,
                    cwd=str(REPO_ROOT),
                    env=_cargo_env(),
                    capture_output=True,
                    text=True,
                    timeout=300,
                )
                if proc.returncode != 0:
                    raise RuntimeError(
                        f"cargo build for silod failed (rc={proc.returncode}):\n"
                        f"STDOUT:\n{proc.stdout[-2000:]}\nSTDERR:\n{proc.stderr[-3000:]}"
                    )
                if not bin_path.is_file():
                    raise RuntimeError(
                        f"cargo build succeeded but binary missing at expected path {bin_path}"
                    )
                _CACHED_SILOD_BIN = bin_path.resolve()
                return _CACHED_SILOD_BIN
            finally:
                fcntl.flock(lf.fileno(), fcntl.LOCK_UN)


def sweep_sandbox_processes(sandbox_id: str) -> None:
    """Best-effort sweep of /proc/*/environ to terminate any descendant carrying SILO_E2E_SANDBOX_ID."""
    if not sandbox_id:
        return
    needle = f"SILO_E2E_SANDBOX_ID={sandbox_id}".encode("utf-8")
    my_pid = os.getpid()
    my_uid = os.getuid()
    victims: list[int] = []
    proc_root = Path("/proc")
    if not proc_root.is_dir():
        return
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        if pid == my_pid:
            continue
        try:
            st = entry.stat()
            if st.st_uid != my_uid:
                continue
            raw_env = (entry / "environ").read_bytes()
            if needle in raw_env:
                victims.append(pid)
        except OSError:
            continue

    for pid in victims:
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError:
            pass
    if victims:
        deadline = time.monotonic() + 0.5
        while time.monotonic() < deadline:
            alive = []
            for pid in victims:
                try:
                    os.kill(pid, 0)
                    alive.append(pid)
                except OSError:
                    pass
            if not alive:
                break
            time.sleep(0.05)
        for pid in victims:
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass


class UnixRpcClient:
    """Raw AF_UNIX stream client for silod newline-delimited JSON-RPC."""

    def __init__(self, socket_path: Path | str, timeout: float = 15.0) -> None:
        self.socket_path = str(socket_path)
        self.timeout = timeout
        self._next_id = 1

    def call(
        self,
        cmd: str,
        params: Any = None,
        *,
        req_id: int | None = None,
        timeout: float | None = None,
    ) -> tuple[dict[str, Any], float]:
        if req_id is None:
            req_id = self._next_id
            self._next_id += 1
        req: dict[str, Any] = {"id": req_id, "cmd": cmd}
        if params is not None:
            req["params"] = params
        payload = (json.dumps(req, separators=(",", ":")) + "\n").encode("utf-8")
        raw_reply, elapsed_ms = self.send_raw(payload, timeout=timeout, expect_reply=True)
        if not raw_reply:
            raise RuntimeError(f"Empty reply from silod on cmd={cmd!r}")
        line = raw_reply.splitlines()[0].decode("utf-8")
        return json.loads(line), elapsed_ms

    def send_raw(
        self,
        data: bytes,
        *,
        timeout: float | None = None,
        expect_reply: bool = True,
    ) -> tuple[bytes, float]:
        t0 = time.monotonic()
        eff_timeout = self.timeout if timeout is None else timeout
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(eff_timeout)
            sock.connect(self.socket_path)
            sock.sendall(data)
            if not expect_reply:
                return b"", (time.monotonic() - t0) * 1000.0
            chunks: list[bytes] = []
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                chunks.append(chunk)
                if b"\n" in chunk:
                    break
            return b"".join(chunks), (time.monotonic() - t0) * 1000.0


class BridgeClient:
    """Persistent `silod --bridge --socket <sock>` subprocess client."""

    def __init__(
        self,
        socket_path: Path | str,
        *,
        silod_bin: Path | None = None,
        env: dict[str, str] | None = None,
    ) -> None:
        self.silod_bin = silod_bin or ensure_silod_built()
        self.socket_path = str(socket_path)
        self._next_id = 1
        self.proc = subprocess.Popen(
            [str(self.silod_bin), "--bridge", "--socket", self.socket_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env or os.environ.copy(),
            bufsize=0,
        )

    def call(
        self,
        cmd: str,
        params: Any = None,
        *,
        req_id: int | None = None,
        timeout: float = 20.0,
    ) -> dict[str, Any]:
        if req_id is None:
            req_id = self._next_id
            self._next_id += 1
        req: dict[str, Any] = {"id": req_id, "cmd": cmd}
        if params is not None:
            req["params"] = params
        raw = (json.dumps(req, separators=(",", ":")) + "\n").encode("utf-8")
        assert self.proc.stdin is not None and self.proc.stdout is not None
        self.proc.stdin.write(raw)
        self.proc.stdin.flush()

        result_box: list[bytes] = []
        err_box: list[BaseException] = []

        def _reader() -> None:
            try:
                assert self.proc.stdout is not None
                line = self.proc.stdout.readline()
                result_box.append(line)
            except BaseException as exc:  # pragma: no cover
                err_box.append(exc)

        t = threading.Thread(target=_reader, daemon=True)
        t.start()
        t.join(timeout=timeout)
        if t.is_alive():
            raise TimeoutError(
                f"BridgeClient timed out after {timeout}s waiting for reply to cmd={cmd!r}"
            )
        if err_box:
            raise err_box[0]
        line = result_box[0] if result_box else b""
        if not line:
            stderr_tail = ""
            if self.proc.poll() is not None and self.proc.stderr is not None:
                stderr_tail = self.proc.stderr.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"BridgeClient received EOF for cmd={cmd!r} (rc={self.proc.poll()}, stderr={stderr_tail!r})"
            )
        return json.loads(line.decode("utf-8"))

    def close(self) -> tuple[int, str]:
        stderr_text = ""
        if self.proc.stdin is not None:
            try:
                if not self.proc.stdin.closed:
                    self.proc.stdin.close()
            except OSError:
                pass
            self.proc.stdin = None
        try:
            self.proc.wait(timeout=5.0)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=3.0)
        if self.proc.stderr is not None:
            try:
                if not self.proc.stderr.closed:
                    stderr_text = self.proc.stderr.read().decode("utf-8", errors="replace")
                    self.proc.stderr.close()
            except OSError:
                pass
            self.proc.stderr = None
        if self.proc.stdout is not None:
            try:
                if not self.proc.stdout.closed:
                    self.proc.stdout.close()
            except OSError:
                pass
            self.proc.stdout = None
        return self.proc.returncode if self.proc.returncode is not None else -1, stderr_text

    def __enter__(self) -> "BridgeClient":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()


@dataclass
class CliResult:
    returncode: int
    stdout: str
    stderr: str


def run_silod_cli(
    args: list[str],
    *,
    silod_bin: Path | None = None,
    bin_dir: Path | None = None,
    use_unshare: bool = False,
    timeout: float = 25.0,
    extra_env: dict[str, str] | None = None,
) -> CliResult:
    """Invoke `silod` CLI (`--version`, `--once`, `--status`, `--emergency-flush`, `--uninstall-cleanup`)."""
    binary = silod_bin or ensure_silod_built()
    env = os.environ.copy()
    if bin_dir is not None:
        env["PATH"] = f"{bin_dir}{os.pathsep}{env.get('PATH', '')}"
    if extra_env:
        env.update(extra_env)

    if use_unshare and bin_dir is not None and (bin_dir / "xtables_shim").is_file():
        cmd = [
            "unshare",
            "-Urm",
            "sh",
            "-c",
            'mount --bind "$1" /usr/sbin/xtables-nft-multi && shift && exec "$@"',
            "--",
            str(bin_dir / "xtables_shim"),
            str(binary),
            *args,
        ]
    else:
        cmd = [str(binary), *args]

    proc = subprocess.run(
        cmd,
        env=env,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return CliResult(returncode=proc.returncode, stdout=proc.stdout, stderr=proc.stderr)


class SiloDaemonSandbox:
    """Isolated temp sandbox running real compiled `silod` with Android CLI & xtables shims."""

    def __init__(
        self,
        *,
        use_unshare: bool = True,
        auto_start: bool = True,
        initial_config: dict[str, Any] | str | bytes | None = None,
        startup_timeout: float = 8.0,
        silod_bin: Path | None = None,
    ) -> None:
        self.use_unshare = use_unshare
        self.auto_start = auto_start
        self.initial_config = initial_config
        self.startup_timeout = startup_timeout
        self.silod_bin = silod_bin or ensure_silod_built()
        self.sandbox_id = uuid.uuid4().hex
        self._tmpdir_obj: tempfile.TemporaryDirectory[str] | None = None
        self.root: Path = Path()
        self.state_dir: Path = Path()
        self.bin_dir: Path = Path()
        self.socket_path: Path = Path()
        self.config_path: Path = Path()
        self.boot_marker_path: Path = Path()
        self.lock_path: Path = Path()
        self.sysctl_orig_path: Path = Path()
        self.proc: subprocess.Popen[bytes] | None = None
        self._stdout_f = None
        self._stderr_f = None
        self._rpc_id = 1

    def __enter__(self) -> "SiloDaemonSandbox":
        self._tmpdir_obj = tempfile.TemporaryDirectory(prefix="silo_e2e_daemon_")
        self.root = Path(self._tmpdir_obj.name)
        os.chmod(self.root, 0o700)

        self.state_dir = self.root / "state"
        self.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.state_dir, 0o700)

        self.bin_dir = self.root / "bin"
        self.socket_path = self.state_dir / "silod.sock"
        self.config_path = self.state_dir / "config.json"
        self.boot_marker_path = self.state_dir / ".boot_fail_count"
        self.lock_path = self.state_dir / "silod.lock"
        self.sysctl_orig_path = self.state_dir / "sysctl_orig.json"

        android_shims.install_shims(self.bin_dir, self.state_dir)

        if self.initial_config is not None:
            if isinstance(self.initial_config, bytes):
                self.config_path.write_bytes(self.initial_config)
            elif isinstance(self.initial_config, str):
                self.config_path.write_text(self.initial_config, encoding="utf-8")
            else:
                self.config_path.write_text(
                    json.dumps(self.initial_config, indent=2), encoding="utf-8"
                )
            os.chmod(self.config_path, 0o600)

        if self.auto_start:
            self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        try:
            self.stop()
        finally:
            sweep_sandbox_processes(self.sandbox_id)
            if self._tmpdir_obj is not None:
                self._tmpdir_obj.cleanup()
                self._tmpdir_obj = None

    def build_env(self) -> dict[str, str]:
        env = os.environ.copy()
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}"
        env["SILO_E2E_SANDBOX_ID"] = self.sandbox_id
        return env

    def start(self, *, wait_ready: bool = True) -> None:
        if self.proc is not None and self.proc.poll() is None:
            raise RuntimeError("SiloDaemonSandbox already has a running silod process")

        silod_args = [
            str(self.silod_bin),
            "--socket",
            str(self.socket_path),
            "--config",
            str(self.config_path),
            "--boot-marker",
            str(self.boot_marker_path),
        ]
        if self.use_unshare:
            cmd = [
                "unshare",
                "-Urm",
                "sh",
                "-c",
                'mount --bind "$1" /usr/sbin/xtables-nft-multi && shift && exec "$@"',
                "--",
                str(self.bin_dir / "xtables_shim"),
                *silod_args,
            ]
        else:
            cmd = silod_args

        self._stdout_f = open(self.root / "silod.stdout", "ab")
        self._stderr_f = open(self.root / "silod.stderr", "ab")
        self.proc = subprocess.Popen(
            cmd,
            env=self.build_env(),
            stdin=subprocess.DEVNULL,
            stdout=self._stdout_f,
            stderr=self._stderr_f,
            start_new_session=True,
        )

        if wait_ready:
            self.wait_ready(timeout=self.startup_timeout)

    def wait_ready(self, timeout: float = 8.0) -> None:
        deadline = time.monotonic() + timeout
        last_err: Exception | None = None
        while time.monotonic() < deadline:
            if self.proc is not None and self.proc.poll() is not None:
                raise RuntimeError(
                    f"silod exited prematurely (rc={self.proc.returncode}).\n"
                    f"STDOUT:\n{self.read_stdout()}\nSTDERR:\n{self.read_stderr()}"
                )
            if self.socket_path.exists():
                try:
                    resp, _ = self.rpc("ping", timeout=1.5)
                    if resp.get("ok"):
                        return
                except Exception as exc:
                    last_err = exc
            time.sleep(0.05)
        raise RuntimeError(
            f"silod did not become ready within {timeout}s (last_err={last_err}).\n"
            f"STDOUT:\n{self.read_stdout()}\nSTDERR:\n{self.read_stderr()}"
        )

    def stop(self, sig: int = signal.SIGTERM, wait_secs: float = 5.0) -> int | None:
        rc: int | None = None
        if self.proc is not None:
            if self.proc.poll() is None:
                try:
                    self.proc.send_signal(sig)
                except OSError:
                    pass
                try:
                    rc = self.proc.wait(timeout=wait_secs)
                except subprocess.TimeoutExpired:
                    try:
                        self.proc.kill()
                    except OSError:
                        pass
                    rc = self.proc.wait(timeout=3.0)
            else:
                rc = self.proc.returncode
            self.proc = None
        for fh in (self._stdout_f, self._stderr_f):
            if fh is not None:
                try:
                    fh.close()
                except OSError:
                    pass
        self._stdout_f = None
        self._stderr_f = None
        return rc

    def restart(self, sig: int = signal.SIGTERM) -> None:
        self.stop(sig=sig)
        self.start(wait_ready=True)

    def read_stdout(self) -> str:
        p = self.root / "silod.stdout"
        return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""

    def read_stderr(self) -> str:
        p = self.root / "silod.stderr"
        return p.read_text(encoding="utf-8", errors="replace") if p.exists() else ""

    def client(self, timeout: float = 15.0) -> UnixRpcClient:
        return UnixRpcClient(self.socket_path, timeout=timeout)

    def open_bridge(self) -> BridgeClient:
        return BridgeClient(self.socket_path, silod_bin=self.silod_bin, env=self.build_env())

    def rpc(
        self,
        cmd: str,
        params: Any = None,
        *,
        req_id: int | None = None,
        timeout: float = 15.0,
    ) -> tuple[dict[str, Any], float]:
        if req_id is None:
            req_id = self._rpc_id
            self._rpc_id += 1
        return UnixRpcClient(self.socket_path, timeout=timeout).call(
            cmd, params, req_id=req_id, timeout=timeout
        )

    def rpc_ok(
        self,
        cmd: str,
        params: Any = None,
        *,
        req_id: int | None = None,
        timeout: float = 15.0,
    ) -> Any:
        resp, _ = self.rpc(cmd, params, req_id=req_id, timeout=timeout)
        if not resp.get("ok"):
            raise AssertionError(
                f"Expected ok=true for RPC {cmd!r} params={params!r}, got: {resp!r}"
            )
        return resp.get("data")

    def rpc_err(
        self,
        cmd: str,
        params: Any = None,
        *,
        req_id: int | None = None,
        timeout: float = 15.0,
    ) -> str:
        resp, _ = self.rpc(cmd, params, req_id=req_id, timeout=timeout)
        if resp.get("ok") is not False:
            raise AssertionError(
                f"Expected ok=false for RPC {cmd!r} params={params!r}, got: {resp!r}"
            )
        return str(resp.get("error") or "")

    def run_cli(
        self,
        args: list[str],
        *,
        use_unshare: bool | None = None,
        timeout: float = 25.0,
    ) -> CliResult:
        eff_unshare = self.use_unshare if use_unshare is None else use_unshare
        return run_silod_cli(
            args,
            silod_bin=self.silod_bin,
            bin_dir=self.bin_dir,
            use_unshare=eff_unshare,
            timeout=timeout,
            extra_env={"SILO_E2E_SANDBOX_ID": self.sandbox_id},
        )

    # --- Android state & shim control helpers ---

    def update_android_state(self, mutator) -> Any:
        return android_shims.update_state(self.state_dir, mutator)

    def read_android_state(self) -> dict[str, Any]:
        return android_shims.read_state(self.state_dir)

    def read_cmd_log(self) -> list[dict[str, Any]]:
        return android_shims.read_cmd_log(self.state_dir)

    def last_iptables_script(self, *, v6: bool = False) -> str:
        p = self.state_dir / ("last_ipt_v6.rules" if v6 else "last_ipt_v4.rules")
        return p.read_text(encoding="utf-8") if p.exists() else ""

    def set_package_appops(
        self,
        pkg: str,
        op: str,
        *,
        uid_mode: str | None = None,
        pkg_mode: str | None = None,
        time_str: str | None = None,
        duration: str | None = None,
        running: bool = False,
        uid: int = 10277,
    ) -> None:
        def _mut(st: dict[str, Any]) -> None:
            pinfo = st.setdefault("packages", {}).setdefault(
                pkg,
                {
                    "uid": uid,
                    "appops": {},
                    "runtime_perms": {},
                    "suspended_users": [],
                    "standby_bucket": "active",
                },
            )
            pinfo["uid"] = uid
            pinfo.setdefault("appops", {})[op] = {
                "uid_mode": uid_mode,
                "pkg_mode": pkg_mode,
                "time": time_str,
                "duration": duration,
                "running": running,
            }

        self.update_android_state(_mut)

    def set_runtime_perm(
        self,
        pkg: str,
        perm: str,
        *,
        granted: bool = True,
        flags: list[str] | None = None,
        uid: int = 10277,
    ) -> None:
        def _mut(st: dict[str, Any]) -> None:
            pinfo = st.setdefault("packages", {}).setdefault(
                pkg,
                {
                    "uid": uid,
                    "appops": {},
                    "runtime_perms": {},
                    "suspended_users": [],
                    "standby_bucket": "active",
                },
            )
            pinfo["uid"] = uid
            pinfo.setdefault("runtime_perms", {})[perm] = {
                "granted": granted,
                "flags": list(flags or []),
            }

        self.update_android_state(_mut)

    def set_suspended(self, pkg: str, *, user_id: int = 0, suspended: bool = True, uid: int = 10277) -> None:
        def _mut(st: dict[str, Any]) -> None:
            pinfo = st.setdefault("packages", {}).setdefault(
                pkg,
                {
                    "uid": uid,
                    "appops": {},
                    "runtime_perms": {},
                    "suspended_users": [],
                    "standby_bucket": "active",
                },
            )
            pinfo["uid"] = uid
            cur = set(pinfo.get("suspended_users", []))
            if suspended:
                cur.add(user_id)
            else:
                cur.discard(user_id)
            pinfo["suspended_users"] = sorted(cur)

        self.update_android_state(_mut)

    def set_dumpsys_appops_raw(self, op: str, raw_text: str) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st.setdefault("dumpsys_appops_raw", {})[op] = raw_text

        self.update_android_state(_mut)

    def set_dumpsys_sensor_privacy_raw(self, raw_text: str | None) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st["dumpsys_sensor_privacy_raw"] = raw_text

        self.update_android_state(_mut)

    def set_prop(self, key: str, value: str) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st.setdefault("props", {})[key] = value

        self.update_android_state(_mut)

    def set_setting_global(self, key: str, value: str) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st.setdefault("settings_global", {})[key] = value

        self.update_android_state(_mut)

    def set_trust_state(self, *, device_locked: bool, showing_lockscreen: bool | None = None) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st["trust"] = {
                "device_locked": device_locked,
                "showing_lockscreen": device_locked if showing_lockscreen is None else showing_lockscreen,
            }

        self.update_android_state(_mut)

    def set_power_state(self, *, wakefulness: str = "Awake", display_state: str = "ON") -> None:
        def _mut(st: dict[str, Any]) -> None:
            st["power"] = {"wakefulness": wakefulness, "display_state": display_state}

        self.update_android_state(_mut)

    def inject_fault(
        self,
        tool: str,
        *,
        argv_contains: list[str] | str | None = None,
        stdin_contains: str | None = None,
        exit_code: int = 1,
        stdout: str = "",
        stderr: str = "",
        once: bool = False,
        sleep_secs: float = 0.0,
        spawn_grandchild_secs: float = 0.0,
        grandchild_pid_file: str | None = None,
    ) -> None:
        def _mut(st: dict[str, Any]) -> None:
            st.setdefault("faults", []).append(
                {
                    "tool": tool,
                    "argv_contains": argv_contains,
                    "stdin_contains": stdin_contains,
                    "exit_code": exit_code,
                    "stdout": stdout,
                    "stderr": stderr,
                    "once": once,
                    "sleep_secs": sleep_secs,
                    "spawn_grandchild_secs": spawn_grandchild_secs,
                    "grandchild_pid_file": grandchild_pid_file,
                }
            )

        self.update_android_state(_mut)

    def clear_faults(self) -> None:
        self.update_android_state(lambda st: st.__setitem__("faults", []))


def run_cargo_unit_test(
    filter_expr: str,
    *,
    require_min_tests: int = 1,
    timeout: float = 180.0,
) -> CliResult:
    """Narrow fallback running a targeted Rust unit test for kernel/Android-hardcoded paths.

    Fails loudly if 0 tests match (preventing silent false-passes).
    """
    lock_path = Path(tempfile.gettempdir()) / "silo_e2e_cargo.lock"
    with open(lock_path, "a+", encoding="utf-8") as lf:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        try:
            cmd = [
                "cargo",
                "test",
                "--manifest-path",
                str(DAEMON_MANIFEST),
                filter_expr,
                "--",
                "--nocapture",
            ]
            proc = subprocess.run(
                cmd,
                cwd=str(REPO_ROOT),
                env=_cargo_env(),
                capture_output=True,
                text=True,
                timeout=timeout,
            )
        finally:
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)

    combined = proc.stdout + "\n" + proc.stderr
    if proc.returncode != 0:
        raise AssertionError(
            f"cargo test {filter_expr!r} failed (rc={proc.returncode}):\n{combined[-3000:]}"
        )
    # Count executed tests across test binaries ("running N tests" / "running N test")
    counts = [int(m.group(1)) for m in re.finditer(r"running (\d+) tests?", combined)]
    total_ran = sum(counts)
    if total_ran < require_min_tests:
        raise AssertionError(
            f"cargo test {filter_expr!r} ran {total_ran} tests (required >= {require_min_tests}); "
            f"target Rust unit test is missing!\nOutput:\n{combined[-2000:]}"
        )
    return CliResult(returncode=proc.returncode, stdout=proc.stdout, stderr=proc.stderr)
