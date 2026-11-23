"""Real `scripts/build_module.sh` packaging driver, verified ELF fixtures, and ZIP verifier."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
BUILD_SCRIPT = REPO_ROOT / "scripts" / "build_module.sh"
MODULE_DIR = REPO_ROOT / "module"

EXPECTED_ZIP_FILES: frozenset[str] = frozenset(
    {
        "module.prop",
        "customize.sh",
        "service.sh",
        "action.sh",
        "uninstall.sh",
        "bin/silod",
    }
)
EXECUTABLE_ZIP_FILES: frozenset[str] = frozenset(
    {
        "customize.sh",
        "service.sh",
        "action.sh",
        "uninstall.sh",
        "bin/silod",
    }
)
FORBIDDEN_MOUNT_ROOTS: tuple[str, ...] = (
    "system",
    "vendor",
    "product",
    "system_ext",
    "odm",
)
FORBIDDEN_MOUNT_RE = re.compile(r"^(?:\./)?(system|vendor|product|system_ext|odm)(?:/|$)")


def build_elf64_static(e_machine: int) -> bytes:
    """Build a minimal valid 136-byte ELF64 LE statically linked executable (`file(1)` verified)."""
    # ELF64 header (64B) + 1x PT_LOAD Phdr (56B) + 16B payload = 136B
    ehdr = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + b"\x00" * 9,  # e_ident: 64-bit, LE, EV_CURRENT, SYSV
        2,          # e_type = ET_EXEC
        e_machine,  # e_machine (183=EM_AARCH64, 62=EM_X86_64)
        1,          # e_version = EV_CURRENT
        0x400078,   # e_entry
        64,         # e_phoff
        0,          # e_shoff
        0,          # e_flags
        64,         # e_ehsize
        56,         # e_phentsize
        1,          # e_phnum
        0,          # e_shentsize
        0,          # e_shnum
        0,          # e_shstrndx
    )
    phdr_load = struct.pack(
        "<IIQQQQQQ",
        1,          # p_type = PT_LOAD
        5,          # p_flags = PF_R | PF_X
        0,          # p_offset
        0x400000,   # p_vaddr
        0x400000,   # p_paddr
        136,        # p_filesz
        136,        # p_memsz
        0x1000,     # p_align
    )
    payload = b"\x00" * 16
    blob = ehdr + phdr_load + payload
    assert len(blob) == 136
    return blob


def build_elf64_arm64_dynamic() -> bytes:
    """Build a 280-byte ELF64 AArch64 dynamically linked executable WITH PT_DYNAMIC (`p_type=2`).

    NOTE: `file-5.45` requires BOTH PT_INTERP (3) AND PT_DYNAMIC (2) to classify
    the binary as `dynamically linked` instead of `statically linked, interpreter ...`.
    """
    interp = b"/lib/ld-linux-aarch64.so.1\x00"  # 27 bytes
    interp_padded = interp.ljust(32, b"\x00")   # 32 bytes at offset 232
    dyn_entry = struct.pack("<QQ", 0, 0)        # DT_NULL (16 bytes at offset 264)
    total_size = 64 + 3 * 56 + 32 + 16          # 280 bytes

    ehdr = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + b"\x00" * 9,
        2,          # ET_EXEC
        183,        # EM_AARCH64
        1,          # EV_CURRENT
        0x400100,   # e_entry
        64,         # e_phoff
        0,          # e_shoff
        0,          # e_flags
        64,         # e_ehsize
        56,         # e_phentsize
        3,          # e_phnum (PT_LOAD, PT_INTERP, PT_DYNAMIC)
        0,
        0,
        0,
    )
    phdr_load = struct.pack(
        "<IIQQQQQQ",
        1, 5, 0, 0x400000, 0x400000, total_size, total_size, 0x1000
    )
    phdr_interp = struct.pack(
        "<IIQQQQQQ",
        3, 4, 232, 0x400000 + 232, 0x400000 + 232, len(interp), len(interp), 1
    )
    phdr_dynamic = struct.pack(
        "<IIQQQQQQ",
        2, 6, 264, 0x400000 + 264, 0x400000 + 264, len(dyn_entry), len(dyn_entry), 8
    )
    blob = ehdr + phdr_load + phdr_interp + phdr_dynamic + interp_padded + dyn_entry
    assert len(blob) == 280
    return blob


def build_elf64_arm64_ehdr_only() -> bytes:
    """Build a truncated 64-byte ELF64 AArch64 header with zero program headers."""
    ehdr = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + b"\x00" * 9,
        2, 183, 1, 0x400000, 0, 0, 0, 64, 56, 0, 0, 0, 0,
    )
    assert len(ehdr) == 64
    return ehdr


def build_elf32_arm32_static() -> bytes:
    """Build a minimal 100-byte ELF32 LE ARM (EM_ARM=40) statically linked executable."""
    # ELF32 header (52B) + 1x PT_LOAD Phdr (32B) + 16B payload = 100B
    ehdr = struct.pack(
        "<16sHHIIIIIHHHHHH",
        b"\x7fELF\x01\x01\x01" + b"\x00" * 9,  # 32-bit, LE, EV_CURRENT, SYSV
        2,          # ET_EXEC
        40,         # EM_ARM
        1,          # EV_CURRENT
        0x10054,    # e_entry
        52,         # e_phoff
        0,          # e_shoff
        0x5000000,  # e_flags (EABI5)
        52,         # e_ehsize
        32,         # e_phentsize
        1,          # e_phnum
        0,
        0,
        0,
    )
    phdr_load = struct.pack(
        "<IIIIIIII",
        1, 0, 0x10000, 0x10000, 100, 100, 5, 0x1000
    )
    blob = ehdr + phdr_load + (b"\x00" * 16)
    assert len(blob) == 100
    return blob


def write_elf_fixture(dest: Path, kind: str) -> Path:
    """Write one of the empirically verified binary fixtures to `dest` and chmod 0755."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if kind == "arm64_static":
        data = build_elf64_static(183)
    elif kind == "arm64_dynamic":
        data = build_elf64_arm64_dynamic()
    elif kind == "arm64_ehdr_only":
        data = build_elf64_arm64_ehdr_only()
    elif kind == "x86_64_static":
        data = build_elf64_static(62)
    elif kind == "arm32_static":
        data = build_elf32_arm32_static()
    elif kind == "shell_script":
        data = b"#!/bin/sh\necho hi\n"
    elif kind == "empty":
        data = b""
    else:
        raise ValueError(f"Unknown ELF fixture kind: {kind!r}")
    dest.write_bytes(data)
    dest.chmod(0o755)
    return dest


def resolve_arm64_binary_or_fixture(work_dir: Path) -> tuple[Path, bool]:
    """Return `(binary_path, is_real_cross_compiled)`.

    Honors `SILOD_ARM64_BIN` when set (failing loudly if the path doesn't exist!),
    otherwise synthesizes the verified 136-byte static ARM64 ELF64 fixture.
    """
    override = os.environ.get("SILOD_ARM64_BIN", "").strip()
    if override:
        p = Path(override)
        if not p.is_file():
            raise RuntimeError(f"SILOD_ARM64_BIN={override!r} does not exist")
        return p.resolve(), True
    # Check if a real cross-compiled binary already exists in daemon/target
    real_candidate = (
        REPO_ROOT / "daemon" / "target" / "aarch64-unknown-linux-musl" / "release" / "silod"
    )
    if real_candidate.is_file():
        return real_candidate, True
    return write_elf_fixture(work_dir / "silod_arm64_static", "arm64_static"), False


@dataclass
class BuildModuleResult:
    returncode: int
    stdout: str
    stderr: str
    out_zip: Path


def run_build_module(
    silod_bin: Path | str,
    out_zip: Path | str,
    *,
    script_path: Path | None = None,
    timeout: float = 30.0,
) -> BuildModuleResult:
    """Execute `scripts/build_module.sh <silod_bin> <out_zip>` without polluting repo `dist/`."""
    script = script_path or BUILD_SCRIPT
    out_p = Path(out_zip)
    proc = subprocess.run(
        ["bash", str(script), str(silod_bin), str(out_p)],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return BuildModuleResult(
        returncode=proc.returncode,
        stdout=proc.stdout,
        stderr=proc.stderr,
        out_zip=out_p,
    )


def create_temp_packaging_workspace(base_dir: Path) -> tuple[Path, Path]:
    """Clone `scripts/build_module.sh` and `module/` into an isolated temp workspace copy."""
    ws = base_dir / "repo_copy"
    (ws / "scripts").mkdir(parents=True, exist_ok=True)
    shutil.copy2(BUILD_SCRIPT, ws / "scripts" / "build_module.sh")
    shutil.copytree(MODULE_DIR, ws / "module")
    return ws, ws / "scripts" / "build_module.sh"


@dataclass
class ZipAudit:
    entries: set[str]
    modes: dict[str, int]
    module_props: dict[str, str]
    silod_sha256: str | None
    forbidden_mount_entries: list[str]


def parse_module_prop_text(text: str) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def inspect_module_zip(zip_path: Path, expected_silod_bin: Path | None = None) -> ZipAudit:
    """Inspect a built KernelSU module ZIP for manifest exactness, modes, hash, and zero-mount compliance."""
    if not zip_path.is_file():
        raise AssertionError(f"Expected module ZIP does not exist: {zip_path}")

    entries: set[str] = set()
    modes: dict[str, int] = {}
    forbidden: list[str] = []
    module_props: dict[str, str] = {}
    silod_sha: str | None = None

    with zipfile.ZipFile(zip_path, "r") as zf:
        for info in zf.infolist():
            norm = info.filename.lstrip("./")
            if not norm or norm.endswith("/"):
                if norm and FORBIDDEN_MOUNT_RE.match(norm):
                    forbidden.append(norm)
                continue
            entries.add(norm)
            if FORBIDDEN_MOUNT_RE.match(norm):
                forbidden.append(norm)
            modes[norm] = (info.external_attr >> 16) & 0o777
        if "module.prop" in entries:
            module_props = parse_module_prop_text(
                zf.read("module.prop").decode("utf-8", errors="replace")
            )
        if "bin/silod" in entries:
            silod_bytes = zf.read("bin/silod")
            silod_sha = hashlib.sha256(silod_bytes).hexdigest()
            if expected_silod_bin is not None:
                expected_sha = hashlib.sha256(expected_silod_bin.read_bytes()).hexdigest()
                if silod_sha != expected_sha:
                    raise AssertionError(
                        f"bin/silod SHA-256 mismatch: zip={silod_sha} vs input={expected_sha}"
                    )

    return ZipAudit(
        entries=entries,
        modes=modes,
        module_props=module_props,
        silod_sha256=silod_sha,
        forbidden_mount_entries=forbidden,
    )


def assert_valid_module_zip(zip_path: Path, expected_silod_bin: Path) -> ZipAudit:
    """Assert every packaging contract requirement on a built Silo KernelSU module ZIP."""
    audit = inspect_module_zip(zip_path, expected_silod_bin=expected_silod_bin)
    if audit.forbidden_mount_entries:
        raise AssertionError(
            f"Zero-mount violation: forbidden overlay entries in ZIP: {audit.forbidden_mount_entries}"
        )
    if audit.entries != EXPECTED_ZIP_FILES:
        raise AssertionError(
            f"ZIP entries mismatch.\nExpected: {sorted(EXPECTED_ZIP_FILES)}\nActual:   {sorted(audit.entries)}"
        )
    for exe_name in sorted(EXECUTABLE_ZIP_FILES):
        mode = audit.modes.get(exe_name, 0)
        if mode != 0o755:
            raise AssertionError(
                f"Expected mode 0755 for {exe_name} in ZIP, got {oct(mode)}"
            )
    if audit.module_props.get("id") != "silo":
        raise AssertionError(f"Expected module.prop id=silo, got {audit.module_props!r}")
    if audit.module_props.get("version") != "v3.0.0":
        raise AssertionError(f"Expected module.prop version=v3.0.0, got {audit.module_props!r}")
    if audit.module_props.get("versionCode") != "30000":
        raise AssertionError(f"Expected module.prop versionCode=30000, got {audit.module_props!r}")
    return audit
