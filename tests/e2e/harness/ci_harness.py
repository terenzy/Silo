"""Pure-stdlib GitHub Actions YAML, Cargo TOML, and ProGuard security verifier."""

from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "build.yml"
CARGO_TOML_PATH = REPO_ROOT / "daemon" / "Cargo.toml"
CARGO_CONFIG_PATH = REPO_ROOT / "daemon" / ".cargo" / "config.toml"
PROGUARD_PATH = REPO_ROOT / "app" / "app" / "proguard-rules.pro"

UNSAFE_GHA_RUN_EXPR_RE = re.compile(
    r"\$\{\{\s*(?:github\.event\.inputs|inputs|github\.head_ref|github\.event\.pull_request\.(?:title|body|head\.ref|head\.label)|github\.event\.issue\.(?:title|body)|github\.event\.comment\.body)\b"
)
REQUIRED_ZERO_MOUNT_ROOTS: tuple[str, ...] = (
    "system",
    "vendor",
    "product",
    "system_ext",
    "odm",
)


@dataclass
class WorkflowStep:
    name: str = ""
    uses: str | None = None
    env: dict[str, str] = field(default_factory=dict)
    run_script: str | None = None
    run_start_line: int = 0


@dataclass
class WorkflowJob:
    job_id: str
    permissions: dict[str, str] = field(default_factory=dict)
    permissions_scalar: str | None = None
    needs: list[str] = field(default_factory=list)
    if_expr: str | None = None
    steps: list[WorkflowStep] = field(default_factory=list)


@dataclass
class WorkflowDoc:
    top_permissions: dict[str, str] = field(default_factory=dict)
    top_permissions_scalar: str | None = None
    jobs: dict[str, WorkflowJob] = field(default_factory=dict)


def _strip_yaml_inline_comment(val: str) -> str:
    in_single = False
    in_double = False
    for idx, ch in enumerate(val):
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == "#" and not in_single and not in_double:
            if idx == 0 or val[idx - 1].isspace():
                return val[:idx].strip()
    return val.strip()


def _unquote(val: str) -> str:
    v = val.strip()
    if len(v) >= 2 and ((v[0] == '"' and v[-1] == '"') or (v[0] == "'" and v[-1] == "'")):
        return v[1:-1]
    return v


def _parse_flow_list(val: str) -> list[str]:
    v = val.strip()
    if v.startswith("[") and v.endswith("]"):
        inner = v[1:-1].strip()
        if not inner:
            return []
        return [_unquote(tok) for tok in inner.split(",") if tok.strip()]
    return [_unquote(v)] if v else []


def parse_workflow_yaml(text: str) -> WorkflowDoc:
    """Indentation-aware pure-stdlib GitHub Actions YAML parser."""
    lines = text.splitlines()
    doc = WorkflowDoc()
    n = len(lines)
    i = 0

    def _indent_of(line: str) -> int:
        return len(line) - len(line.lstrip(" "))

    def _collect_block_scalar(start_idx: int, parent_indent: int) -> tuple[str, int]:
        collected: list[str] = []
        block_indent: int | None = None
        j = start_idx
        while j < n:
            raw = lines[j]
            if not raw.strip():
                collected.append("")
                j += 1
                continue
            ind = _indent_of(raw)
            if ind <= parent_indent:
                break
            if block_indent is None:
                block_indent = ind
            collected.append(raw[min(ind, block_indent) :])
            j += 1
        return "\n".join(collected).rstrip("\n"), j

    def _parse_permissions_block(start_idx: int, parent_indent: int, val_part: str) -> tuple[dict[str, str], str | None, int]:
        v = _strip_yaml_inline_comment(val_part)
        if v:
            if v == "{}":
                return {}, None, start_idx
            return {}, _unquote(v), start_idx
        perms: dict[str, str] = {}
        j = start_idx
        while j < n:
            raw = lines[j]
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                j += 1
                continue
            ind = _indent_of(raw)
            if ind <= parent_indent:
                break
            if ":" in stripped:
                k, pv = stripped.split(":", 1)
                perms[k.strip()] = _unquote(_strip_yaml_inline_comment(pv))
            j += 1
        return perms, None, j

    def _parse_steps(start_idx: int, steps_indent: int) -> tuple[list[WorkflowStep], int]:
        steps: list[WorkflowStep] = []
        cur: WorkflowStep | None = None
        cur_item_indent: int | None = None
        j = start_idx

        while j < n:
            raw = lines[j]
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                j += 1
                continue
            ind = _indent_of(raw)
            if ind <= steps_indent:
                break

            is_new_item = stripped.startswith("- ") or stripped == "-"
            if is_new_item and (cur_item_indent is None or ind == cur_item_indent):
                cur_item_indent = ind
                cur = WorkflowStep()
                steps.append(cur)
                after_dash = stripped[1:].strip()
                key_indent = ind + 2
                if not after_dash:
                    j += 1
                    continue
                kv_text = after_dash
            else:
                if cur is None:
                    j += 1
                    continue
                key_indent = ind
                kv_text = stripped

            if ":" not in kv_text:
                j += 1
                continue
            k, v_raw = kv_text.split(":", 1)
            key = k.strip()
            v_clean = _strip_yaml_inline_comment(v_raw)

            if key == "name":
                cur.name = _unquote(v_clean)
                j += 1
            elif key == "uses":
                cur.uses = _unquote(v_clean)
                j += 1
            elif key == "run":
                if v_clean in ("|", "|-", "|+", ">", ">-", ">+"):
                    cur.run_start_line = j + 2
                    script, j = _collect_block_scalar(j + 1, key_indent)
                    cur.run_script = script
                else:
                    cur.run_start_line = j + 1
                    cur.run_script = _unquote(v_clean)
                    j += 1
            elif key == "env":
                j += 1
                while j < n:
                    eraw = lines[j]
                    estripped = eraw.strip()
                    if not estripped or estripped.startswith("#"):
                        j += 1
                        continue
                    eind = _indent_of(eraw)
                    if eind <= key_indent:
                        break
                    if ":" in estripped:
                        ek, ev = estripped.split(":", 1)
                        cur.env[ek.strip()] = _unquote(_strip_yaml_inline_comment(ev))
                    j += 1
            else:
                # Skip nested mapping/scalar blocks cleanly
                if v_clean in ("|", "|-", "|+", ">", ">-", ">+"):
                    _, j = _collect_block_scalar(j + 1, key_indent)
                else:
                    j += 1

        return steps, j

    in_jobs = False
    jobs_indent = 0
    cur_job: WorkflowJob | None = None
    cur_job_indent = 0

    while i < n:
        raw = lines[i]
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            i += 1
            continue
        ind = _indent_of(raw)

        if ind == 0:
            in_jobs = False
            cur_job = None
            if ":" in stripped:
                k, v_raw = stripped.split(":", 1)
                key = k.strip()
                if key == "permissions":
                    perms, scalar, i = _parse_permissions_block(i + 1, 0, v_raw)
                    doc.top_permissions = perms
                    doc.top_permissions_scalar = scalar
                    continue
                if key == "jobs":
                    in_jobs = True
                    jobs_indent = 0
            i += 1
            continue

        if in_jobs:
            # Detect job ID line (direct child of jobs:)
            if (cur_job is None or ind <= cur_job_indent) and ":" in stripped and not stripped.startswith("-"):
                job_id, _ = stripped.split(":", 1)
                job_id = job_id.strip()
                cur_job = WorkflowJob(job_id=job_id)
                cur_job_indent = ind
                doc.jobs[job_id] = cur_job
                i += 1
                continue

            if cur_job is not None and ind > cur_job_indent and ":" in stripped:
                k, v_raw = stripped.split(":", 1)
                key = k.strip()
                v_clean = _strip_yaml_inline_comment(v_raw)
                if key == "permissions":
                    perms, scalar, i = _parse_permissions_block(i + 1, ind, v_raw)
                    cur_job.permissions = perms
                    cur_job.permissions_scalar = scalar
                    continue
                if key == "if":
                    cur_job.if_expr = _unquote(v_clean)
                    i += 1
                    continue
                if key == "needs":
                    if v_clean:
                        cur_job.needs.extend(_parse_flow_list(v_clean))
                        i += 1
                    else:
                        i += 1
                        while i < n:
                            nraw = lines[i]
                            nstripped = nraw.strip()
                            if not nstripped or nstripped.startswith("#"):
                                i += 1
                                continue
                            if _indent_of(nraw) <= ind:
                                break
                            if nstripped.startswith("- "):
                                cur_job.needs.append(_unquote(_strip_yaml_inline_comment(nstripped[2:])))
                            i += 1
                    continue
                if key == "steps":
                    steps, i = _parse_steps(i + 1, ind)
                    cur_job.steps = steps
                    continue
        i += 1

    return doc


def load_workflow(path: Path | None = None) -> WorkflowDoc:
    p = path or WORKFLOW_PATH
    if not p.is_file():
        raise AssertionError(f"Workflow file missing: {p}")
    return parse_workflow_yaml(p.read_text(encoding="utf-8"))


def audit_workflow_security(doc: WorkflowDoc) -> list[str]:
    """Validate all F12 CI workflow security, least-privilege, and build-gate requirements.

    Returns a list of concrete violation strings (empty == compliant).
    """
    violations: list[str] = []

    # 1. Least-privilege permissions: NO write at top-level or non-release build jobs
    if doc.top_permissions_scalar and "write" in doc.top_permissions_scalar.lower():
        violations.append(
            f"Top-level workflow permissions grant write ({doc.top_permissions_scalar!r})"
        )
    for scope, level in doc.top_permissions.items():
        if level.lower() == "write":
            violations.append(
                f"Top-level workflow permission {scope}: write is over-privileged (must be scoped strictly to release job)"
            )

    for required_job in ("daemon-and-module", "android-app", "release"):
        if required_job not in doc.jobs:
            violations.append(f"Missing required workflow job: {required_job!r}")

    for jid, job in doc.jobs.items():
        if jid != "release":
            if job.permissions_scalar and "write" in job.permissions_scalar.lower():
                violations.append(f"Non-release job {jid!r} grants write permissions")
            for scope, level in job.permissions.items():
                if level.lower() == "write":
                    violations.append(f"Non-release job {jid!r} grants {scope}: write")

    release_job = doc.jobs.get("release")
    if release_job is not None:
        if release_job.permissions.get("contents", "").lower() != "write":
            violations.append(
                f"release job must explicitly scope permissions.contents: write (got {release_job.permissions!r})"
            )
        for dep in ("daemon-and-module", "android-app"):
            if dep not in release_job.needs:
                violations.append(
                    f"release job `needs` missing {dep!r} (got {release_job.needs!r})"
                )

    # 2. Zero unescaped untrusted GHA expressions inside ANY step `run:` body
    for jid, job in doc.jobs.items():
        for step in job.steps:
            if not step.run_script:
                continue
            m = UNSAFE_GHA_RUN_EXPR_RE.search(step.run_script)
            if m:
                violations.append(
                    f"Job {jid!r} step {step.name!r} injects unescaped GHA expression {m.group(0)!r} directly in `run:` (must pass via `env:`)"
                )

    # 3. Release fallback tag must not hardcode `v3.0.0-` literal (derive from module/module.prop)
    if release_job is not None:
        rel_runs = "\n".join(s.run_script or "" for s in release_job.steps)
        if re.search(r'["\']v\d+\.\d+\.\d+-', rel_runs):
            violations.append(
                "release job hardcodes version literal (e.g. 'v3.0.0-...') instead of deriving dynamically from module/module.prop"
            )
        if "module.prop" not in rel_runs:
            violations.append(
                "release job does not derive fallback tag version from module/module.prop"
            )

    # 4. daemon-and-module required gates
    dm_job = doc.jobs.get("daemon-and-module")
    if dm_job is not None:
        dm_runs = "\n".join(s.run_script or "" for s in dm_job.steps)
        if "cargo clippy" not in dm_runs or "-D warnings" not in dm_runs:
            violations.append("daemon-and-module missing `cargo clippy ... -D warnings` gate")
        if "cargo test" not in dm_runs:
            violations.append("daemon-and-module missing `cargo test` gate")
        if "sh -n" not in dm_runs:
            violations.append("daemon-and-module missing `sh -n` syntax gate for module scripts")
        if "build_module.sh" not in dm_runs:
            violations.append("daemon-and-module missing `scripts/build_module.sh` packaging step")
        # Zero-mount regex must cover all 5 partition roots
        missing_roots = [r for r in REQUIRED_ZERO_MOUNT_ROOTS if not re.search(rf"\b{r}\b", dm_runs)]
        if missing_roots:
            violations.append(
                f"daemon-and-module zero-mount ZIP check missing partition roots: {missing_roots}"
            )

    # 5. android-app required gates
    app_job = doc.jobs.get("android-app")
    if app_job is not None:
        app_runs = "\n".join(s.run_script or "" for s in app_job.steps)
        if "testDebugUnitTest" not in app_runs or "assembleRelease" not in app_runs:
            violations.append("android-app missing `./gradlew testDebugUnitTest assembleRelease` gate")
        has_aapt2_perm_dump = (
            "aapt2" in app_runs.lower()
            and "dump permissions" in app_runs
            and "android.permission.INTERNET" in app_runs
        )
        if not has_aapt2_perm_dump:
            violations.append("android-app missing `aapt2 dump permissions` zero-INTERNET gate")

    return violations


def load_cargo_toml(path: Path | None = None) -> dict[str, Any]:
    p = path or CARGO_TOML_PATH
    if not p.is_file():
        raise AssertionError(f"Cargo.toml missing: {p}")
    return tomllib.loads(p.read_text(encoding="utf-8"))


def load_cargo_config(path: Path | None = None) -> dict[str, Any]:
    p = path or CARGO_CONFIG_PATH
    if not p.is_file():
        raise AssertionError(f".cargo/config.toml missing: {p}")
    return tomllib.loads(p.read_text(encoding="utf-8"))


def audit_cargo_metadata() -> list[str]:
    """Validate `daemon/Cargo.toml` and `daemon/.cargo/config.toml`."""
    violations: list[str] = []
    cargo = load_cargo_toml()
    pkg = cargo.get("package", {})
    if pkg.get("name") != "silod":
        violations.append(f"Cargo.toml package.name expected 'silod', got {pkg.get('name')!r}")
    if pkg.get("version") != "3.0.0":
        violations.append(f"Cargo.toml package.version expected '3.0.0', got {pkg.get('version')!r}")
    if str(pkg.get("edition")) != "2021":
        violations.append(f"Cargo.toml package.edition expected '2021', got {pkg.get('edition')!r}")
    desc = str(pkg.get("description", ""))
    if not desc or re.search(r"\bv2\b", desc, re.IGNORECASE):
        violations.append(
            f"Cargo.toml package.description contains stale 'v2' or is empty: {desc!r}"
        )

    rel_prof = cargo.get("profile", {}).get("release", {})
    if rel_prof.get("opt-level") != "z":
        violations.append(f"Cargo.toml profile.release.opt-level expected 'z', got {rel_prof.get('opt-level')!r}")
    if rel_prof.get("lto") is not True:
        violations.append(f"Cargo.toml profile.release.lto expected true, got {rel_prof.get('lto')!r}")
    if rel_prof.get("panic") != "abort":
        violations.append(f"Cargo.toml profile.release.panic expected 'abort', got {rel_prof.get('panic')!r}")
    if rel_prof.get("strip") is not True:
        violations.append(f"Cargo.toml profile.release.strip expected true, got {rel_prof.get('strip')!r}")

    cfg = load_cargo_config()
    arm_cfg = cfg.get("target", {}).get("aarch64-unknown-linux-musl", {})
    if arm_cfg.get("linker") != "rust-lld":
        violations.append(
            f".cargo/config.toml aarch64 musl linker expected 'rust-lld', got {arm_cfg.get('linker')!r}"
        )
    rustflags = " ".join(arm_cfg.get("rustflags", []))
    if "+crt-static" not in rustflags or "relocation-model=static" not in rustflags:
        violations.append(
            f".cargo/config.toml missing static musl rustflags (+crt-static / relocation-model=static): {rustflags!r}"
        )
    return violations


def audit_proguard_rules(path: Path | None = None) -> list[str]:
    """Verify `app/app/proguard-rules.pro` retains Kotlin serialization enums in `dev.silo.app.ipc.**`."""
    p = path or PROGUARD_PATH
    if not p.is_file():
        return [f"proguard-rules.pro missing: {p}"]
    active_lines = [
        line.strip()
        for line in p.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    joined = "\n".join(active_lines)
    violations: list[str] = []

    has_enum_keep = bool(
        re.search(r"-keep(?:classmembers|names)?\s+enum\s+dev\.silo\.app\.ipc\.\*", joined)
        or re.search(r"-keep(?:classmembers)?\s+enum\s+\*\*", joined)
        or re.search(r"-keep\s+class\s+dev\.silo\.app\.ipc\.\*\*\s*\{\s*\*\s*;\s*\}", joined)
    )
    if not has_enum_keep:
        violations.append(
            "proguard-rules.pro missing explicit keep rule for @Serializable enums in dev.silo.app.ipc.** (APP-L08 / CB-30)"
        )
    return violations
