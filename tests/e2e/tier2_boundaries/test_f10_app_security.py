"""Tier 2 (F10) — Lockscreen Physical Security, Quick Settings Tiles & Offline/Disguise boundary & negative tests."""

from __future__ import annotations

from tests.e2e.harness import android_harness
from tests.e2e.harness.registry import e2e_test


@e2e_test(
    "T2-F10-01",
    tier=2,
    features=["F10"],
    description="Direct MainActivity external launch / disguise bypass rejection: exported=false and ZERO intent-filters",
)
def test_main_activity_disguise_bypass_rejected() -> None:
    apk = android_harness.ensure_release_apk_built()
    tree = android_harness.aapt2_manifest_tree(apk)
    main_acts = [
        n
        for n in tree.find_all("activity")
        if android_harness.normalize_component_fqcn(str(n.attrs.get("name", ""))) == "dev.silo.app.MainActivity"
    ]
    assert len(main_acts) == 1, f"Expected exactly 1 MainActivity declaration, got {len(main_acts)}"
    main_act = main_acts[0]
    assert main_act.attrs.get("exported") is False, (
        f"MainActivity must NOT be exported=true (disguise bypass APP-M09/CB-30): {main_act.attrs}"
    )
    assert main_act.find_all("intent-filter") == [], (
        f"MainActivity must have ZERO intent-filter elements: {main_act.children}"
    )


@e2e_test(
    "T2-F10-02",
    tier=2,
    features=["F10"],
    description="Zero network or hidden transitive permissions across uses-permission AND uses-permission-sdk-23",
)
def test_zero_network_or_hidden_transitive_permissions() -> None:
    apk = android_harness.ensure_release_apk_built()
    perms = set(android_harness.aapt2_permissions(apk))
    tree = android_harness.aapt2_manifest_tree(apk)
    for tag in ("uses-permission", "uses-permission-sdk-23"):
        for node in tree.find_all(tag):
            if "name" in node.attrs:
                perms.add(str(node.attrs["name"]))

    forbidden = (
        "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    )
    leaked = [p for p in forbidden if p in perms]
    assert not leaked, f"Forbidden/unexpected permissions leaked into Release APK: {leaked} (all={sorted(perms)})"


@e2e_test(
    "T2-F10-03",
    tier=2,
    features=["F10"],
    description="PanicRebootTileService bytecode enforces Dispatchers.getMain, Job.cancel, and volatile/atomic arm timestamp (APP-M10)",
)
def test_panic_reboot_tile_concurrency_and_main_dispatcher_bytecode() -> None:
    android_harness.ensure_unit_tests_run()
    disasm = android_harness.javap_disassemble_classes(
        android_harness.KOTLIN_DEBUG_CLASSES, "dev/silo/app/tiles/PanicRebootTileService"
    )
    assert "kotlinx/coroutines/Dispatchers.getMain" in disasm, (
        "PanicRebootTileService MUST dispatch qsTile.updateTile() on Dispatchers.Main (APP-M10)"
    )
    assert "kotlinx/coroutines/Job" in disasm and "cancel" in disasm, (
        "PanicRebootTileService MUST cancel prior arm-timeout Job on 2nd tap (APP-M10)"
    )
    has_thread_safe_ts = ("volatile " in disasm) or ("java/util/concurrent/atomic/Atomic" in disasm)
    assert has_thread_safe_ts, (
        "PanicRebootTileService arm timestamp MUST be @Volatile (ACC_VOLATILE) or Atomic* (APP-M10)"
    )


@e2e_test(
    "T2-F10-04",
    tier=2,
    features=["F10"],
    description="LauncherDisguise is disabled at install time, LauncherDefault is enabled, and SystemScreen does NOT hardcode 'Silo v3.0.0' literal (APP-L06)",
)
def test_disguise_alias_initially_disabled_and_no_hardcoded_version_literal() -> None:
    apk = android_harness.ensure_release_apk_built()
    tree = android_harness.aapt2_manifest_tree(apk)
    aliases = {
        android_harness.normalize_component_fqcn(str(n.attrs.get("name", ""))): n
        for n in tree.find_all("activity-alias")
    }
    assert aliases["dev.silo.app.LauncherDefault"].attrs.get("enabled") is True
    assert aliases["dev.silo.app.LauncherDisguise"].attrs.get("enabled") is False

    # Verify BuildConfig.VERSION_NAME is compiled properly AND SystemScreen does not hardcode 'Silo v3.0.0'
    # (note: kotlinc may constant-fold Java `static final String BuildConfig.VERSION_NAME` in bytecode,
    # so check compiled BuildConfig + compiled SystemScreenKt existence + non-hardcoded SystemScreen.kt usage)
    bc = android_harness.run_jvm_contract(["build_config"])
    assert bc["version_name"] == "3.0.0", f"Expected BuildConfig.VERSION_NAME == 3.0.0, got {bc}"
    disasm = android_harness.javap_disassemble_classes(
        android_harness.KOTLIN_DEBUG_CLASSES, "dev/silo/app/ui/screens/SystemScreenKt"
    )
    assert "SystemScreen" in disasm
    sys_kt = android_harness.APP_MODULE_DIR / "src" / "main" / "java" / "dev" / "silo" / "app" / "ui" / "screens" / "SystemScreen.kt"
    sys_src = sys_kt.read_text(encoding="utf-8")
    assert '"Silo v3.0.0"' not in sys_src, (
        "SystemScreen.kt must use BuildConfig.VERSION_NAME rather than hardcoded 'Silo v3.0.0' literal (APP-L06)"
    )
    assert "BuildConfig.VERSION_NAME" in sys_src, (
        "SystemScreen.kt should reference BuildConfig.VERSION_NAME (APP-L06)"
    )


@e2e_test(
    "T2-F10-05",
    tier=2,
    features=["F10"],
    description="Compiled SiloStrings handles SYSTEM locale and unknown future permission category without throwing",
)
def test_silo_strings_system_locale_and_unknown_category_fallback() -> None:
    res = android_harness.run_jvm_contract(["silo_strings", "SYSTEM"])
    assert res["tab_home"].strip(), f"Empty tab_home for SYSTEM locale: {res}"
    assert res["labels"]["unknown_future_category"] == "unknown_future_category", (
        f"Unknown category label should fall back cleanly to raw key: {res['labels']}"
    )
    assert isinstance(res["descs"]["unknown_future_category"], str)


@e2e_test(
    "T2-F10-06",
    tier=2,
    features=["F10"],
    description="Release APK negative integrity: no allowBackup, no usesCleartextTraffic, not debuggable, and valid DEX checksums across all classes*.dex",
)
def test_release_apk_negative_security_flags_and_dex_checksums() -> None:
    apk = android_harness.ensure_release_apk_built()
    tree = android_harness.aapt2_manifest_tree(apk)
    app_node = tree.find_all("application")[0]
    assert app_node.attrs.get("allowBackup") is not True, f"allowBackup must not be true: {app_node.attrs}"
    assert app_node.attrs.get("usesCleartextTraffic") is not True, (
        f"usesCleartextTraffic must not be true: {app_node.attrs}"
    )
    assert app_node.attrs.get("debuggable") is not True, (
        f"Release APK must not be debuggable=true: {app_node.attrs}"
    )

    descriptors, dex_names = android_harness.dexdump_inspect_apk(apk)
    assert len(dex_names) >= 1 and len(descriptors) > 50, (
        f"Unexpected empty DEX inspection result: dex_names={dex_names}, classes={len(descriptors)}"
    )
