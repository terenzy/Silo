"""Decorator-based E2E test registry with strict tier, feature, and threshold validation."""

from __future__ import annotations

import importlib
import pkgutil
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Sequence

ALL_FEATURES: tuple[str, ...] = tuple(f"F{i}" for i in range(1, 13))
VALID_TIERS: tuple[int, ...] = (1, 2, 3, 4)

TIER_MIN_TOTALS: dict[int, int] = {
    1: 60,
    2: 60,
    3: 12,
    4: 6,
}
MIN_PER_FEATURE_TIER1 = 5
MIN_PER_FEATURE_TIER2 = 5
MIN_OVERALL_TOTAL = 138


@dataclass(frozen=True)
class TestCase:
    test_id: str
    tier: int
    features: tuple[str, ...]
    description: str
    fn: Callable[[], None]
    module_name: str


_REGISTRY: dict[str, TestCase] = {}
_DISCOVERED = False


def e2e_test(
    test_id: str,
    *,
    tier: int,
    features: Sequence[str],
    description: str,
) -> Callable[[Callable[[], None]], Callable[[], None]]:
    """Register an opaque-box E2E test case with strict metadata validation."""
    if not isinstance(test_id, str) or not test_id.strip():
        raise ValueError(f"Invalid empty test_id: {test_id!r}")
    if tier not in VALID_TIERS:
        raise ValueError(f"Test {test_id}: tier must be in {VALID_TIERS}, got {tier!r}")
    if not features:
        raise ValueError(f"Test {test_id}: features must be non-empty")
    norm_features = tuple(dict.fromkeys(str(f).strip().upper() for f in features))
    unknown = [f for f in norm_features if f not in ALL_FEATURES]
    if unknown:
        raise ValueError(f"Test {test_id}: unknown feature IDs {unknown}; valid={ALL_FEATURES}")
    if tier in (3, 4) and len(norm_features) < 2:
        raise ValueError(
            f"Test {test_id}: Tier {tier} tests must span >= 2 distinct features, got {norm_features}"
        )
    if not isinstance(description, str) or not description.strip():
        raise ValueError(f"Test {test_id}: description must be non-empty")

    def decorator(fn: Callable[[], None]) -> Callable[[], None]:
        if not callable(fn):
            raise TypeError(f"Test {test_id}: target is not callable")
        if test_id in _REGISTRY:
            existing = _REGISTRY[test_id]
            raise ValueError(
                f"Duplicate test_id {test_id!r}: already registered in {existing.module_name}"
            )
        _REGISTRY[test_id] = TestCase(
            test_id=test_id,
            tier=tier,
            features=norm_features,
            description=description.strip(),
            fn=fn,
            module_name=fn.__module__,
        )
        return fn

    return decorator


def discover_all_tests(*, force: bool = False) -> list[TestCase]:
    """Import all test modules across tiers 1-4 and return registered tests sorted deterministically."""
    global _DISCOVERED
    if _DISCOVERED and not force:
        return get_registered_tests()

    e2e_root = Path(__file__).resolve().parent.parent
    tier_packages = (
        "tier1_features",
        "tier2_boundaries",
        "tier3_combinations",
        "tier4_scenarios",
    )
    for pkg_name in tier_packages:
        pkg_dir = e2e_root / pkg_name
        if not pkg_dir.is_dir():
            raise RuntimeError(f"Missing expected test package directory: {pkg_dir}")
        full_pkg = f"tests.e2e.{pkg_name}"
        importlib.import_module(full_pkg)
        for mod_info in sorted(pkgutil.iter_modules([str(pkg_dir)]), key=lambda m: m.name):
            if mod_info.name.startswith("_"):
                continue
            importlib.import_module(f"{full_pkg}.{mod_info.name}")

    _DISCOVERED = True
    return get_registered_tests()


def get_registered_tests() -> list[TestCase]:
    """Return all currently registered tests sorted by (tier, test_id)."""
    return sorted(_REGISTRY.values(), key=lambda tc: (tc.tier, tc.test_id))


def filter_tests(
    tests: Iterable[TestCase],
    *,
    tiers: set[int] | None = None,
    features: set[str] | None = None,
    test_ids: set[str] | None = None,
) -> list[TestCase]:
    """Filter registered tests by tier, feature tag, and/or explicit test IDs."""
    out: list[TestCase] = []
    for tc in tests:
        if tiers is not None and tc.tier not in tiers:
            continue
        if features is not None and not any(f in features for f in tc.features):
            continue
        if test_ids is not None and tc.test_id not in test_ids:
            continue
        out.append(tc)
    return out


def validate_registry(tests: Sequence[TestCase]) -> tuple[list[str], dict[str, object]]:
    """Strictly validate TEST_INFRA.md coverage thresholds and metadata invariants."""
    errors: list[str] = []
    seen_ids: set[str] = set()
    tier_counts: dict[int, int] = {t: 0 for t in VALID_TIERS}
    tier1_per_feature: dict[str, int] = {f: 0 for f in ALL_FEATURES}
    tier2_per_feature: dict[str, int] = {f: 0 for f in ALL_FEATURES}
    all_per_feature: dict[str, int] = {f: 0 for f in ALL_FEATURES}

    for tc in tests:
        if tc.test_id in seen_ids:
            errors.append(f"Duplicate test_id detected: {tc.test_id}")
        seen_ids.add(tc.test_id)

        if tc.tier not in VALID_TIERS:
            errors.append(f"{tc.test_id}: invalid tier {tc.tier}")
            continue
        if not tc.features:
            errors.append(f"{tc.test_id}: empty features list")
        if any(f not in ALL_FEATURES for f in tc.features):
            errors.append(f"{tc.test_id}: invalid feature in {tc.features}")
        if tc.tier in (3, 4) and len(set(tc.features)) < 2:
            errors.append(
                f"{tc.test_id}: Tier {tc.tier} test must cover >= 2 distinct features (got {tc.features})"
            )
        if not tc.description.strip():
            errors.append(f"{tc.test_id}: empty description")
        if not callable(tc.fn):
            errors.append(f"{tc.test_id}: fn is not callable")

        tier_counts[tc.tier] += 1
        for feat in tc.features:
            if feat in all_per_feature:
                all_per_feature[feat] += 1
            if tc.tier == 1 and feat in tier1_per_feature:
                tier1_per_feature[feat] += 1
            elif tc.tier == 2 and feat in tier2_per_feature:
                tier2_per_feature[feat] += 1

    for tier, min_count in TIER_MIN_TOTALS.items():
        if tier_counts[tier] < min_count:
            errors.append(
                f"Tier {tier} has {tier_counts[tier]} tests; minimum required is {min_count}"
            )

    for feat in ALL_FEATURES:
        if tier1_per_feature[feat] < MIN_PER_FEATURE_TIER1:
            errors.append(
                f"Tier 1 feature {feat} has {tier1_per_feature[feat]} tests; minimum required is {MIN_PER_FEATURE_TIER1}"
            )
        if tier2_per_feature[feat] < MIN_PER_FEATURE_TIER2:
            errors.append(
                f"Tier 2 feature {feat} has {tier2_per_feature[feat]} tests; minimum required is {MIN_PER_FEATURE_TIER2}"
            )

    if len(tests) < MIN_OVERALL_TOTAL:
        errors.append(
            f"Total test count is {len(tests)}; minimum required is {MIN_OVERALL_TOTAL}"
        )

    expected_t4_ids = {f"T4-S0{i}" for i in range(1, 8)}
    missing_t4 = sorted(expected_t4_ids - seen_ids)
    if missing_t4:
        errors.append(f"Missing required Tier 4 scenario IDs from TEST_INFRA.md: {missing_t4}")

    summary: dict[str, object] = {
        "total": len(tests),
        "tier_counts": tier_counts,
        "tier1_per_feature": tier1_per_feature,
        "tier2_per_feature": tier2_per_feature,
        "all_per_feature": all_per_feature,
    }
    return errors, summary
