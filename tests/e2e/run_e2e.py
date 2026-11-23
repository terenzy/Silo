#!/usr/bin/env python3
"""Canonical CLI runner for the Silo v3.0.0 4-Tier Independent Opaque-Box E2E Test Suite.

Usage examples:
  python3 tests/e2e/run_e2e.py --self-check
  python3 tests/e2e/run_e2e.py --list
  python3 tests/e2e/run_e2e.py --tier 1
  python3 tests/e2e/run_e2e.py --tier 2 --feature F1,F2
  python3 tests/e2e/run_e2e.py --test T1-F12-01,T1-F12-07 --json-report /tmp/report.json
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import signal
import sys
import time
import traceback
from pathlib import Path
from typing import Any

# Ensure repo root is on sys.path regardless of invocation cwd
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

from tests.e2e.harness.registry import (  # noqa: E402
    ALL_FEATURES,
    TestCase,
    discover_all_tests,
    filter_tests,
    validate_registry,
)


class _TestTimeoutError(TimeoutError):
    """Raised via SIGALRM when a single E2E test exceeds --timeout seconds."""


def _parse_csv_tokens(raw_values: list[str] | None) -> set[str] | None:
    if not raw_values:
        return None
    out: set[str] = set()
    for item in raw_values:
        for tok in item.split(","):
            tok = tok.strip()
            if tok:
                out.add(tok)
    return out or None


def _parse_tier_arg(tier_arg: str) -> set[int]:
    t = tier_arg.strip().lower()
    if t == "all":
        return {1, 2, 3, 4}
    tiers: set[int] = set()
    for part in t.split(","):
        part = part.strip()
        if not part:
            continue
        if part not in ("1", "2", "3", "4"):
            raise argparse.ArgumentTypeError(
                f"Invalid --tier value {tier_arg!r}; expected 1, 2, 3, 4, comma list, or 'all'"
            )
        tiers.add(int(part))
    if not tiers:
        raise argparse.ArgumentTypeError("Empty --tier value")
    return tiers


def _run_single_test(tc: TestCase, timeout_secs: int) -> dict[str, Any]:
    t0 = time.monotonic()
    prev_handler = None

    def _on_alarm(signum, frame):
        raise _TestTimeoutError(f"Test {tc.test_id} exceeded timeout of {timeout_secs}s")

    status = "PASS"
    err_msg = ""
    tb_text = ""
    try:
        if timeout_secs > 0 and hasattr(signal, "SIGALRM"):
            prev_handler = signal.signal(signal.SIGALRM, _on_alarm)
            signal.alarm(timeout_secs)
        tc.fn()
    except _TestTimeoutError as exc:
        status = "TIMEOUT"
        err_msg = str(exc)
        tb_text = traceback.format_exc()
    except AssertionError as exc:
        status = "FAIL"
        err_msg = str(exc) or exc.__class__.__name__
        tb_text = traceback.format_exc()
    except Exception as exc:
        status = "ERROR"
        err_msg = f"{exc.__class__.__name__}: {exc}"
        tb_text = traceback.format_exc()
    finally:
        if timeout_secs > 0 and hasattr(signal, "SIGALRM"):
            signal.alarm(0)
            if prev_handler is not None:
                signal.signal(signal.SIGALRM, prev_handler)

    elapsed_ms = round((time.monotonic() - t0) * 1000.0, 2)
    return {
        "test_id": tc.test_id,
        "tier": tc.tier,
        "features": list(tc.features),
        "description": tc.description,
        "module": tc.module_name,
        "status": status,
        "duration_ms": elapsed_ms,
        "error": err_msg,
        "traceback": tb_text,
    }


def _cmd_self_check(tests: list[TestCase]) -> int:
    errors, summary = validate_registry(tests)
    tier_counts = summary["tier_counts"]
    t1_pf = summary["tier1_per_feature"]
    t2_pf = summary["tier2_per_feature"]
    print("=== Silo v3.0.0 E2E Suite Self-Check ===")
    print(f"Total registered tests: {summary['total']}")
    print(
        f"By tier: T1={tier_counts[1]} (min 60), T2={tier_counts[2]} (min 60), "
        f"T3={tier_counts[3]} (min 12), T4={tier_counts[4]} (min 6)"
    )
    print("Per-feature counts (Tier 1 / Tier 2, min 5 / 5):")
    for feat in ALL_FEATURES:
        print(f"  {feat:>3}: T1={t1_pf[feat]:>2}  T2={t2_pf[feat]:>2}")
    if errors:
        print("\nSELF-CHECK FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    print("\nSELF-CHECK PASSED: all imports, metadata invariants, unique IDs, and coverage thresholds satisfied.")
    return 0


def _cmd_list(tests: list[TestCase]) -> int:
    for tc in tests:
        feats = ",".join(tc.features)
        print(f"[T{tc.tier}] {tc.test_id:<12} ({feats:<15}) {tc.description}")
    print(f"\nListed {len(tests)} test case(s).")
    return 0


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Silo v3.0.0 Independent Opaque-Box 4-Tier E2E Test Runner"
    )
    p.add_argument(
        "--self-check",
        action="store_true",
        help="Validate imports, unique IDs, metadata invariants, and TEST_INFRA.md coverage thresholds",
    )
    p.add_argument(
        "--list",
        action="store_true",
        help="List matching registered E2E test cases and exit",
    )
    p.add_argument(
        "--tier",
        default="all",
        help="Tier filter: 1, 2, 3, 4, comma-separated list, or 'all' (default: all)",
    )
    p.add_argument(
        "--feature",
        action="append",
        help="Feature filter (F1..F12), repeatable or comma-separated",
    )
    p.add_argument(
        "--test",
        action="append",
        help="Exact test ID filter (e.g. T1-F1-01), repeatable or comma-separated",
    )
    p.add_argument(
        "--json-report",
        type=Path,
        default=None,
        help="Write machine-readable JSON test report to this path",
    )
    p.add_argument(
        "--timeout",
        type=int,
        default=180,
        help="Per-test timeout in seconds (default: 180)",
    )
    p.add_argument(
        "--fail-fast",
        action="store_true",
        help="Stop immediately on the first failing/erroring test",
    )
    p.add_argument(
        "-v",
        "--verbose",
        action="store_true",
        help="Print full tracebacks on failures/errors",
    )
    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        all_tests = discover_all_tests()
    except Exception as exc:
        print(f"FATAL: failed to discover E2E tests: {exc}", file=sys.stderr)
        traceback.print_exc()
        return 1

    if args.self_check:
        return _cmd_self_check(all_tests)

    tiers = _parse_tier_arg(args.tier)
    features_raw = _parse_csv_tokens(args.feature)
    features = {f.upper() for f in features_raw} if features_raw else None
    if features:
        unknown_f = sorted(features - set(ALL_FEATURES))
        if unknown_f:
            print(f"ERROR: unknown --feature value(s): {unknown_f} (valid: {ALL_FEATURES})", file=sys.stderr)
            return 1

    test_ids = _parse_csv_tokens(args.test)
    if test_ids:
        known_ids = {tc.test_id for tc in all_tests}
        unknown_ids = sorted(test_ids - known_ids)
        if unknown_ids:
            print(f"ERROR: unknown --test ID(s): {unknown_ids}", file=sys.stderr)
            return 1

    selected = filter_tests(all_tests, tiers=tiers, features=features, test_ids=test_ids)
    if args.list:
        return _cmd_list(selected)

    if not selected:
        print("ERROR: zero E2E tests matched the requested filters.", file=sys.stderr)
        return 1

    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    t_suite_start = time.monotonic()
    results: list[dict[str, Any]] = []
    passed = failed = errors = timed_out = 0

    print(f"Running {len(selected)} Silo E2E test(s) (timeout={args.timeout}s/test)...")
    for idx, tc in enumerate(selected, start=1):
        res = _run_single_test(tc, args.timeout)
        results.append(res)
        st = res["status"]
        if st == "PASS":
            passed += 1
        elif st == "FAIL":
            failed += 1
        elif st == "TIMEOUT":
            timed_out += 1
            errors += 1
        else:
            errors += 1

        feats = ",".join(tc.features)
        line = f"[{idx:>3}/{len(selected)}] {st:<7} {tc.test_id:<10} [T{tc.tier} {feats}] ({res['duration_ms']:.0f}ms)"
        print(line, flush=True)
        if st != "PASS":
            first_err = res["error"].splitlines()[0] if res["error"] else ""
            print(f"    -> {first_err}", flush=True)
            if args.verbose and res["traceback"]:
                for tb_ln in res["traceback"].splitlines():
                    print(f"    | {tb_ln}", flush=True)
            if args.fail_fast:
                print("Stopping early due to --fail-fast.", flush=True)
                break

    total_ms = round((time.monotonic() - t_suite_start) * 1000.0, 2)
    executed = len(results)
    summary_payload = {
        "started_at": started_at,
        "duration_ms": total_ms,
        "executed": executed,
        "passed": passed,
        "failed": failed,
        "errors": errors,
        "timed_out": timed_out,
        "env": {
            "SILOD_BIN": os.environ.get("SILOD_BIN", ""),
            "SILO_RELEASE_APK": os.environ.get("SILO_RELEASE_APK", ""),
            "SILO_SKIP_BUILD": os.environ.get("SILO_SKIP_BUILD", ""),
        },
        "results": results,
    }

    if args.json_report is not None:
        args.json_report.parent.mkdir(parents=True, exist_ok=True)
        args.json_report.write_text(
            json.dumps(summary_payload, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )

    print(
        f"\nSummary: executed={executed}, passed={passed}, failed={failed}, "
        f"errors={errors} (timed_out={timed_out}), total_time={total_ms:.0f}ms"
    )
    return 0 if (executed > 0 and failed == 0 and errors == 0) else 1


if __name__ == "__main__":
    if hasattr(signal, "SIGPIPE"):
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    sys.exit(main())
