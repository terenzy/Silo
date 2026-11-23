"""Tier 1 (F10) — Lockscreen Physical Security, Quick Settings Tiles & Offline/Disguise Polish happy-path E2E tests."""

from __future__ import annotations

from tests.e2e.harness import android_harness, ci_harness
from tests.e2e.harness.registry import e2e_test
from tests.e2e.harness.silod_harness import ALL_PERM_CATEGORIES


def _find_components(tree: android_harness.XmlNode, tag: str) -> dict[str, android_harness.XmlNode]:
    out: dict[str, android_harness.XmlNode] = {}
    for node in tree.find_all(tag):
        raw_name = str(node.attrs.get("name", ""))
        if raw_name:
            out[android_harness.normalize_component_fqcn(raw_name)] = node
    return out


def _intent_actions_and_categories(comp: android_harness.XmlNode) -> tuple[set[str], set[str]]:
    actions: set[str] = set()
    categories: set[str] = set()
    for flt in comp.find_all("intent-filter"):
        for act in flt.find_all("action"):
            if "name" in act.attrs:
                actions.add(str(act.attrs["name"]))
        for cat in flt.find_all("category"):
            if "name" in cat.attrs:
                categories.add(str(cat.attrs["name"]))
    return actions, categories


@e2e_test(
    "T1-F10-01",
    tier=1,
    features=["F10"],
    description="Release APK builds cleanly, passes apksigner verify, and reports exact package/version/SDK metadata via aapt2 + BuildConfig",
)
def test_release_apk_signature_and_metadata() -> None:
    apk = android_harness.ensure_release_apk_built()
    android_harness.apksigner_verify(apk)

    pkg = android_harness.aapt2_packagename(apk)
    assert pkg == "dev.silo.app", f"Expected packagename dev.silo.app, got {pkg!r}"

    badging = android_harness.aapt2_badging(apk)
    assert badging.get("version_name") == "3.0.0", f"Badging version_name mismatch: {badging}"
    assert badging.get("version_code") == 30000, f"Badging version_code mismatch: {badging}"
    assert badging.get("min_sdk") == 31, f"Badging minSdkVersion expected 31: {badging}"
    assert badging.get("target_sdk") == 35, f"Badging targetSdkVersion expected 35: {badging}"

    bc = android_harness.run_jvm_contract(["build_config"])
    assert bc["version_name"] == "3.0.0" and bc["version_code"] == 30000 and bc["application_id"] == "dev.silo.app"


@e2e_test(
    "T1-F10-02",
    tier=1,
    features=["F10"],
    description="Release APK strictly enforces zero INTERNET, zero POST_NOTIFICATIONS, QUERY_ALL_PACKAGES present, and allowBackup=false",
)
def test_release_apk_zero_internet_and_minimal_permissions() -> None:
    apk = android_harness.ensure_release_apk_built()
    perms = set(android_harness.aapt2_permissions(apk))
    tree = android_harness.aapt2_manifest_tree(apk)
    xml_perms = {
        str(n.attrs.get("name", ""))
        for tag in ("uses-permission", "uses-permission-sdk-23")
        for n in tree.find_all(tag)
    }
    all_perms = perms | xml_perms

    assert "android.permission.QUERY_ALL_PACKAGES" in all_perms, f"Missing QUERY_ALL_PACKAGES: {all_perms}"
    assert "android.permission.INTERNET" not in all_perms, (
        f"CRITICAL OFFLINE VIOLATION: android.permission.INTERNET found in Release APK: {all_perms}"
    )
    assert "android.permission.POST_NOTIFICATIONS" not in all_perms, (
        f"Unused POST_NOTIFICATIONS permission must be removed from AndroidManifest.xml (APP-M09/CB-30): {all_perms}"
    )

    apps = tree.find_all("application")
    assert apps, "Missing <application> element in manifest"
    assert apps[0].attrs.get("allowBackup") is False, f"allowBackup must be false: {apps[0].attrs}"


@e2e_test(
    "T1-F10-03",
    tier=1,
    features=["F10"],
    description="App Disguise manifest architecture: MainActivity exported=false with no launcher filter; dual aliases target MainActivity",
)
def test_disguise_manifest_mainactivity_and_dual_aliases() -> None:
    apk = android_harness.ensure_release_apk_built()
    tree = android_harness.aapt2_manifest_tree(apk)

    activities = _find_components(tree, "activity")
    main_act = activities.get("dev.silo.app.MainActivity")
    assert main_act is not None, f"MainActivity missing in manifest: {sorted(activities)}"
    assert main_act.attrs.get("exported") is False, (
        f"MainActivity MUST have android:exported='false' to prevent disguise bypass (APP-M09/CB-30): {main_act.attrs}"
    )
    m_acts, m_cats = _intent_actions_and_categories(main_act)
    assert "android.intent.action.MAIN" not in m_acts and "android.intent.category.LAUNCHER" not in m_cats, (
        f"MainActivity must not declare MAIN/LAUNCHER intent-filter directly: {m_acts}, {m_cats}"
    )

    aliases = _find_components(tree, "activity-alias")
    for alias_name, expected_enabled in (
        ("dev.silo.app.LauncherDefault", True),
        ("dev.silo.app.LauncherDisguise", False),
    ):
        node = aliases.get(alias_name)
        assert node is not None, f"Missing activity-alias {alias_name}: {sorted(aliases)}"
        assert node.attrs.get("exported") is True, f"{alias_name} must be exported=true: {node.attrs}"
        assert node.attrs.get("enabled") is expected_enabled, (
            f"{alias_name} enabled expected {expected_enabled}, got {node.attrs}"
        )
        tgt = android_harness.normalize_component_fqcn(str(node.attrs.get("targetActivity", "")))
        assert tgt == "dev.silo.app.MainActivity", f"{alias_name} targetActivity mismatch: {tgt}"
        acts, cats = _intent_actions_and_categories(node)
        assert "android.intent.action.MAIN" in acts and "android.intent.category.LAUNCHER" in cats, (
            f"{alias_name} must have MAIN + LAUNCHER intent-filter: {acts}, {cats}"
        )


@e2e_test(
    "T1-F10-04",
    tier=1,
    features=["F10"],
    description="All 3 QS Tiles declared properly in APK manifest and Lockdown/SensorsOff tiles enforce isLocked + unlockAndRun in bytecode",
)
def test_qs_tiles_manifest_and_lockscreen_unlock_guard() -> None:
    apk = android_harness.ensure_release_apk_built()
    tree = android_harness.aapt2_manifest_tree(apk)
    services = _find_components(tree, "service")

    for svc_fqcn in (
        "dev.silo.app.tiles.LockdownTileService",
        "dev.silo.app.tiles.SensorsOffTileService",
        "dev.silo.app.tiles.PanicRebootTileService",
    ):
        node = services.get(svc_fqcn)
        assert node is not None, f"Missing QS Tile service {svc_fqcn}: {sorted(services)}"
        assert node.attrs.get("exported") is True, f"{svc_fqcn} must be exported=true"
        assert node.attrs.get("permission") == "android.permission.BIND_QUICK_SETTINGS_TILE", (
            f"{svc_fqcn} missing BIND_QUICK_SETTINGS_TILE permission: {node.attrs}"
        )
        acts, _ = _intent_actions_and_categories(node)
        assert "android.service.quicksettings.action.QS_TILE" in acts, f"{svc_fqcn} missing QS_TILE action"

    # Verify compiled bytecode (including $*.class coroutine/lambda inner classes!) enforces lockscreen unlockAndRun (APP-H01/CB-13)
    android_harness.ensure_unit_tests_run()
    for tile_rel in (
        "dev/silo/app/tiles/LockdownTileService",
        "dev/silo/app/tiles/SensorsOffTileService",
    ):
        disasm = android_harness.javap_disassemble_classes(
            android_harness.KOTLIN_DEBUG_CLASSES, tile_rel
        )
        assert "isLocked:()Z" in disasm, (
            f"{tile_rel} MUST check TileService.isLocked() before disabling lockdown / unmuting sensors (APP-H01/CB-13)"
        )
        assert "unlockAndRun:(Ljava/lang/Runnable;)V" in disasm, (
            f"{tile_rel} MUST invoke TileService.unlockAndRun(Runnable) when locked (APP-H01/CB-13)"
        )


@e2e_test(
    "T1-F10-05",
    tier=1,
    features=["F10"],
    description="ProGuard rules retain dev.silo.app.ipc enums and dexdump proves all 5 IPC enums & serializers survive R8 in Release APK",
)
def test_r8_proguard_ipc_enum_retention_in_release_dex() -> None:
    pg_violations = ci_harness.audit_proguard_rules()
    assert not pg_violations, f"ProGuard rule audit failed: {pg_violations}"

    apk = android_harness.ensure_release_apk_built()
    descriptors, dex_names = android_harness.dexdump_inspect_apk(apk)
    assert dex_names, "No DEX files inspected"

    required_enums = (
        "Ldev/silo/app/ipc/FirewallMode;",
        "Ldev/silo/app/ipc/BlockAction;",
        "Ldev/silo/app/ipc/FreezeMode;",
        "Ldev/silo/app/ipc/SecurityPreset;",
        "Ldev/silo/app/ipc/AuditSeverity;",
    )
    missing = [desc for desc in required_enums if desc not in descriptors]
    assert not missing, (
        f"R8 stripped required @Serializable IPC enums from Release APK {dex_names} (APP-L08): missing={missing}"
    )


@e2e_test(
    "T1-F10-06",
    tier=1,
    features=["F10"],
    description="Bilingual localization parity across strings.xml, compiled APK resources, and JVM SiloStrings across all 10 categories",
)
def test_bilingual_localization_parity_resources_and_jvm() -> None:
    en_xml = android_harness.parse_android_strings_xml(android_harness.STRINGS_EN_XML)
    id_xml = android_harness.parse_android_strings_xml(android_harness.STRINGS_ID_XML)
    assert set(en_xml.keys()) == set(id_xml.keys()), (
        f"strings.xml key mismatch EN={sorted(en_xml)} vs ID={sorted(id_xml)}"
    )
    for key in ("app_name", "app_name_disguise", "tile_lockdown", "tile_sensors", "tile_panic"):
        assert key in en_xml and en_xml[key].strip(), f"Empty/missing EN string key {key!r}"
        assert key in id_xml and id_xml[key].strip(), f"Empty/missing ID string key {key!r}"
    assert en_xml["app_name_disguise"] == "NetDiag"
    assert id_xml["app_name_disguise"] == "NetDiag"

    apk = android_harness.ensure_release_apk_built()
    res_dump = android_harness.aapt2_dump_strings(apk) + "\n" + android_harness.aapt2_dump_resources(apk)
    for label in ("Silo Lockdown", "Sensors Off", "Kunci Jaringan", "Matikan Sensor", "NetDiag"):
        assert label in res_dump, f"Expected localized resource string {label!r} in compiled Release APK"

    s_en = android_harness.run_jvm_contract(["silo_strings", "ENGLISH"])
    s_id = android_harness.run_jvm_contract(["silo_strings", "INDONESIAN"])
    assert s_en["is_indonesian"] is False and s_id["is_indonesian"] is True
    for cat in ALL_PERM_CATEGORIES:
        assert s_en["labels"][cat].strip(), f"Empty EN label for {cat}"
        assert s_id["labels"][cat].strip(), f"Empty ID label for {cat}"
        assert s_en["descs"][cat].strip(), f"Empty EN desc for {cat}"
        assert s_id["descs"][cat].strip(), f"Empty ID desc for {cat}"
    assert s_en["labels"]["camera"] != s_id["labels"]["camera"]
    assert s_en["labels"]["location"] != s_id["labels"]["location"]
