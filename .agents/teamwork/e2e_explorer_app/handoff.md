# E2E Explorer Handoff — Android App Black-Box Surfaces (`e2e_explorer_app`)

> **Role**: Read-only Explorer for the Independent E2E Testing Track (`e2e_testing_orchestrator`, conv ID `042425bf98efe04f6055f145dfece762`).
> **Scope**: Actionable, verified E2E harness & test blueprint for Android App (`app/`) and App ↔ Daemon cross-boundary contracts across **F1–F10** (especially **F10**, owned exclusively by M3 `android-app`).
> **Note on Concurrent M3 Work**: As of this inspection, `app/` still reflects pre-fix baseline code (e.g., `POST_NOTIFICATIONS` present at `AndroidManifest.xml:14`, `MainActivity` `exported="true"` at `AndroidManifest.xml:27`, `CategoryPermLiveState` lacking additive fields at `SiloModels.kt:226-232`). Every contract below distinguishes **Current Pre-Fix State** from the **Frozen Target Requirement** in `PROJECT.md`. **All E2E tests MUST assert the Frozen Target Requirements — never weaken assertions to pass against pre-fix bugs.**

---

## 1. Gradle, JDK & APK Build/Test Surface (Verified on Devbox)

### 1.1 Verified Toolchain Paths & Versions

Verified directly on this Linux devbox via read-only inspection:

| Tool / Env | Verified Path / Value | Notes |
|---|---|---|
| `JAVA_HOME` | `/usr/lib/jvm/java-21-openjdk-amd64` | OpenJDK `21.0.13.0.3`; `/usr/bin/java`, `/usr/bin/javac`, `/usr/bin/javap`, `/usr/bin/jar`, `/usr/bin/keytool` on `PATH` |
| `ANDROID_HOME` | `/opt/android-sdk` | Platforms installed: `android-34`, `android-35`, `android-36`; `ANDROID_SDK_ROOT` is currently unset (harness MUST export BOTH `ANDROID_HOME` and `ANDROID_SDK_ROOT`) |
| `aapt2` | `/opt/android-sdk/build-tools/35.0.0/aapt2` | Version `2.19-11948202` (also `33.0.2`, `34.0.0` installed; discover dynamically via highest version in `$ANDROID_HOME/build-tools/*/aapt2` with `35.0.0` fallback) |
| `dexdump` | `/opt/android-sdk/build-tools/35.0.0/dexdump` | Inspects `classes*.dex` inside compiled Release APK (`-d`, `-f`, `-h`, `-s`) |
| `apksigner` | `/opt/android-sdk/build-tools/35.0.0/apksigner` | Verifies APK v1/v2/v3 signatures (`apksigner verify --print-certs <apk>`) |
| `zipalign` | `/opt/android-sdk/build-tools/35.0.0/zipalign` | Verifies 4-byte alignment (`zipalign -c 4 <apk>`) |
| `android.jar` | `/opt/android-sdk/platforms/android-35/android.jar` | Compile/runtime stub jar for standalone JVM contract driver |
| Gradle Wrapper | `/home/antigravity/terenzy/Silo/app/gradlew` (`0755`) | Pins Gradle `8.10.2` (`app/gradle/wrapper/gradle-wrapper.properties:3`); AGP `8.7.3`, Kotlin `2.0.21` (`app/build.gradle.kts:2-5`), `jvmTarget = "17"` (`app/app/build.gradle.kts:57-62`) |
| Standalone `kotlinc` | **NOT installed on `PATH`** | Kotlin compiler lives in Gradle plugin/cache; use JDK 21 `/usr/bin/javac` + `/usr/bin/java` for standalone JVM contract harness (zero extra downloads) |

### 1.2 Exact Gradle Tasks & Build Artifacts

From `app/app/build.gradle.kts:8-75` and `.github/workflows/build.yml:122-147`:

| Artifact / Task | Exact Command / Path | Critical Details |
|---|---|---|
| Project Working Dir | `/home/antigravity/terenzy/Silo/app` | Never create `app/local.properties`; pass `ANDROID_HOME=/opt/android-sdk` in subprocess env to keep git clean |
| Unit Tests Task | `./gradlew :app:testDebugUnitTest --no-daemon --console=plain` | Compiles `app/app/build/tmp/kotlin-classes/debug/` + runs JUnit 4 (`junit:junit:4.13.2`, `kotlinx-coroutines-test:1.9.0`) |
| Release APK Task | `./gradlew :app:assembleRelease --no-daemon --console=plain` | `isMinifyEnabled = true`, `isShrinkResources = true`, applies `proguard-android-optimize.txt` + `app/app/proguard-rules.pro` |
| Combined Canonical Build | `./gradlew testDebugUnitTest assembleRelease --no-daemon --console=plain` | Matches `PROJECT.md:18` and `.github/workflows/build.yml:130` |
| Debug APK Task (optional) | `./gradlew :app:assembleDebug --no-daemon --console=plain` | Output: `app/app/build/outputs/apk/debug/app-debug.apk` (`isMinifyEnabled = false`) |
| **Release APK Output Path** | **`app/app/build/outputs/apk/release/app-release.apk`** | **Signed (NOT `-unsigned.apk`)**: `app/app/build.gradle.kts:49` sets `signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")`. Always signed with debug keystore when `SILO_KEYSTORE_PATH` is unset. Harness should resolve `SILO_RELEASE_APK` env override → `app/app/build/outputs/apk/release/app-release.apk` → fallback glob `app/app/build/outputs/apk/release/*.apk` (matching `build.yml:134`). |
| **JUnit XML Report Dir** | **`app/app/build/test-results/testDebugUnitTest/`** | Contains `TEST-<fqcn>.xml` (currently `TEST-dev.silo.app.ProtocolAndParserTest.xml`, plus all new test suites added by M3). Standard Ant/JUnit `<testsuite tests="..." failures="..." errors="..." skipped="...">` XML. |
| Compiled Debug Kotlin Classes | `app/app/build/tmp/kotlin-classes/debug/` | Populated by `testDebugUnitTest` (or `compileDebugKotlin`); contains all `dev/silo/app/**/*.class` unminified! |
| Compiled Debug Java / `BuildConfig` | `app/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes/` | Contains `dev/silo/app/BuildConfig.class` (`VERSION_NAME = "3.0.0"`, `VERSION_CODE = 30000`, `APPLICATION_ID = "dev.silo.app"`). |
| R8 Mapping File | `app/app/build/outputs/mapping/release/mapping.txt` | Generated by `assembleRelease` (`isMinifyEnabled = true`). |
| Gradle Dependency Cache | `${GRADLE_USER_HOME:-~/.gradle}/caches/modules-2/files-2.1/` | Contains downloaded `kotlin-stdlib`, `kotlinx-serialization-{core,json}-jvm`, `kotlinx-coroutines-core-jvm` jars after first Gradle run. |

### 1.3 Reliable `android_harness.py` Execution Design

To avoid Gradle lock races, cold-start timeouts, and redundant multi-minute builds across 138+ E2E tests:

1. **Mandatory Subprocess Environment**:
   ```python
   env = os.environ.copy()
   env.setdefault("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
   env.setdefault("ANDROID_HOME", "/opt/android-sdk")
   env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
   ```
2. **Always Pass `--no-daemon --console=plain`**: Prevents persistent Gradle daemons from holding locks across sequential runs or colliding with other processes. (`app/gradle.properties:1` already sets `-Xmx2048m -Dfile.encoding=UTF-8`.)
3. **Cross-Process Lock + In-Process Session Memoization**:
   - Acquire an OS `fcntl.flock(LOCK_EX)` on `/tmp/silo_e2e_gradle.lock` around any `./gradlew` invocation.
   - Memoize `ensure_unit_tests_run()` and `ensure_release_apk_built()` inside `android_harness.py` per runner session so `./gradlew` runs **at most once** per `run_e2e.py` invocation (even if 25+ tests depend on JUnit XML, debug bytecode, or the Release APK).
   - **Honor `SILO_SKIP_BUILD=1`** (`TEST_INFRA.md:93`): when set, skip invoking `./gradlew` and validate existing XML/APK/classes on disk — **failing loudly** (never silently skipping!) if expected build outputs are missing.
   - **Honor `SILO_RELEASE_APK`** (`TEST_INFRA.md:92`): if set and exists, inspect that APK directly without rebuilding.
4. **Cold-Cache Timeout Sizing**:
   - Verified on devbox: `/home/antigravity/.gradle/wrapper/dists/` does **not** yet exist! The first cold Gradle invocation will download `gradle-8.10.2-bin.zip` + AGP/Compose/Kotlin artifacts before compiling.
   - Default timeout: **`600` seconds** (configurable via `SILO_GRADLE_TIMEOUT_SECS`).
5. **Freshness / Stale XML Guard**:
   - When running `./gradlew :app:testDebugUnitTest --no-daemon --console=plain`, Gradle may mark the task `UP-TO-DATE` and leave valid XML files in place (good for speed), OR if M3 deleted/renamed a test class, stale `TEST-*.xml` files could linger. Check `gradlew` return code == `0` AND verify `test-results/testDebugUnitTest/TEST-*.xml` contains `>= 1` XML file with `tests > 0`, `failures == 0`, `errors == 0`, `skipped == 0`. For clean runs when `SILO_SKIP_BUILD != "1"`, consider passing `--rerun` only if explicitly requested (`SILO_GRADLE_RERUN=1`), otherwise let Gradle's incremental cache work while checking exit code and XML timestamps.

---

## 2. Verified Binary APK, Manifest & Resource Inspection Contracts (`aapt2` + `dexdump` + XML)

### 2.1 Exact Verified `aapt2` 2.19-11948202 Output Syntax

Verified via an ephemeral `/tmp` `aapt2 link` + `aapt2 dump` probe against `/opt/android-sdk/platforms/android-35/android.jar` (probe deleted immediately on exit):

1. **`aapt2 dump packagename <apk>`**:
   ```text
   dev.silo.app
   ```
2. **`aapt2 dump permissions <apk>`**:
   ```text
   package: dev.silo.app
   uses-permission: name='android.permission.QUERY_ALL_PACKAGES'
   ```
   - **Parser regex**: `re.findall(r"^uses-permission(?:-[^:]+)?:\s+name='([^']+)'", stdout, re.MULTILINE)` — captures `uses-permission` AND `uses-permission-sdk-23` (so no transitive library can sneak `INTERNET` in via `-sdk-23`!).
3. **`aapt2 dump badging <apk>`**:
   ```text
   package: name='dev.silo.app' versionCode='30000' versionName='3.0.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'
   minSdkVersion:'31'
   targetSdkVersion:'35'
   uses-permission: name='android.permission.QUERY_ALL_PACKAGES'
   ```
   - **Important `aapt2` vs legacy `aapt` difference**: `aapt2 2.19` prints `minSdkVersion:'31'` (NOT `sdkVersion:'31'`). Harness should match `(?:minSdkVersion|sdkVersion):'(\d+)'` and `targetSdkVersion:'(\d+)'`.
4. **`aapt2 dump xmltree --file AndroidManifest.xml <apk>`**:
   ```text
   N: android=http://schemas.android.com/apk/res/android (line=2)
     E: manifest (line=2)
       A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=30000
       A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="3.0.0" (Raw: "3.0.0")
       A: package="dev.silo.app" (Raw: "dev.silo.app")
         E: uses-sdk (line=6)
           A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=31
           A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=35
         E: application (line=8)
           A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
             E: activity (line=9)
               A: http://schemas.android.com/apk/res/android:name(0x01010003)=".MainActivity" (Raw: ".MainActivity")
               A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
             E: activity-alias (line=10)
               A: http://schemas.android.com/apk/res/android:name(0x01010003)=".LauncherDefault" (Raw: ".LauncherDefault")
               A: http://schemas.android.com/apk/res/android:enabled(0x0101000e)=true
               A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
               A: http://schemas.android.com/apk/res/android:targetActivity(0x01010202)=".MainActivity" (Raw: ".MainActivity")
                 E: intent-filter (line=11)
                     E: action (line=12)
                       A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
                     E: category (line=13)
                       A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
   ```
   - **Exact Tree Parser Specification for `android_harness.py`**:
     - Track indentation (leading space count) on a stack of `XmlNode(tag, attrs, children, indent)`.
     - Element line: `r"^(\s*)E:\s+(\S+)"` → pop stack while `stack and stack[-1].indent >= indent`, attach to `stack[-1]`, push new node.
     - Attribute line: `r"^(\s*)A:\s+(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_]+)(?:\(0x[0-9a-fA-F]+\))?=(.+)$"` → attach to top-of-stack element.
     - Value normalization:
       - If value matches `r'^"(.*)"\s+\(Raw:\s+".*"\)$'` → strip quotes (or parse `"..."` prefix).
       - Booleans in `aapt2 2.19` render literally as `true` / `false` (also handle `(type 0x12)0xffffffff` / `(type 0x12)0x0` defensively).
     - **AGP Manifest Merger Component Name Normalization**: In a Gradle-built APK, AGP expands relative names `".MainActivity"` to `"dev.silo.app.MainActivity"` (while raw source XML uses `".MainActivity"`). Always normalize via:
       ```python
       def fqcn(name: str, pkg: str = "dev.silo.app") -> str:
           return f"{pkg}{name}" if name.startswith(".") else name
       ```

### 2.2 Complete Binary APK & Manifest Contract Checklist (Target vs Pre-Fix)

| Contract Item | Source Ref (`PROJECT.md` / `app/`) | Current Pre-Fix State | **Target Assertion (MUST PASS)** |
|---|---|---|---|
| Package / App ID | `app/app/build.gradle.kts:13` | `dev.silo.app` | `aapt2 dump packagename` == `"dev.silo.app"` |
| Version Name / Code | `app/app/build.gradle.kts:16-17` | `"3.0.0"` / `30000` | `badging` & `xmltree`: `versionName == "3.0.0"`, `versionCode == 30000` |
| SDK Versions | `app/app/build.gradle.kts:10,14-15` | `minSdk=31`, `targetSdk=35` | `minSdkVersion == 31`, `targetSdkVersion == 35` |
| **ZERO `INTERNET`** | `PROJECT.md:5,19,53` (`F10`) | Removed (`tools:node="remove"`) | `android.permission.INTERNET` ABSENT from BOTH `aapt2 dump permissions` AND `aapt2 dump xmltree` `<uses-permission>` nodes! Also verify no `ACCESS_NETWORK_STATE`/`CHANGE_NETWORK_STATE` unexpectedly crept in unless explicitly needed. |
| **ZERO `POST_NOTIFICATIONS`** | `PROJECT.md:53` (`F10`, `APP-M09`/`CB-30`) | **BUG**: PRESENT at `AndroidManifest.xml:14` | `android.permission.POST_NOTIFICATIONS` ABSENT from BOTH `aapt2 dump permissions` AND `aapt2 dump xmltree`! |
| `QUERY_ALL_PACKAGES` | `AndroidManifest.xml:13` | Present | `android.permission.QUERY_ALL_PACKAGES` PRESENT in `aapt2 dump permissions` |
| `allowBackup="false"` | `AndroidManifest.xml:18` | `false` | `<application>` attribute `allowBackup == False` in `xmltree` |
| `Application` class | `AndroidManifest.xml:17` | `.SiloApp` | `<application>` `name` normalizes to `dev.silo.app.SiloApp` |
| **`MainActivity` NOT exported** | `PROJECT.md:53` (`F10`, `APP-M09`/`CB-30`) | **BUG**: `exported="true"` at `AndroidManifest.xml:27` | `<activity name="dev.silo.app.MainActivity">` has `exported == False` AND **zero** `<intent-filter>` containing `MAIN`/`LAUNCHER`! |
| `LauncherDefault` alias | `AndroidManifest.xml:32-43` | `enabled=true`, `exported=true` | `<activity-alias name="dev.silo.app.LauncherDefault">` has `enabled == True`, `exported == True`, `targetActivity` == `dev.silo.app.MainActivity`, `<intent-filter>` has BOTH `android.intent.action.MAIN` and `android.intent.category.LAUNCHER` |
| `LauncherDisguise` alias | `AndroidManifest.xml:46-57` | `enabled=false`, `exported=true` | `<activity-alias name="dev.silo.app.LauncherDisguise">` has `enabled == False`, `exported == True`, `targetActivity` == `dev.silo.app.MainActivity`, `<intent-filter>` has BOTH `MAIN` and `LAUNCHER` |
| `LockdownTileService` | `AndroidManifest.xml:60-69` | Present | `<service name="dev.silo.app.tiles.LockdownTileService">`: `exported == True`, `permission == "android.permission.BIND_QUICK_SETTINGS_TILE"`, `<intent-filter>` has `android.service.quicksettings.action.QS_TILE` |
| `SensorsOffTileService` | `AndroidManifest.xml:72-81` | Present | `<service name="dev.silo.app.tiles.SensorsOffTileService">`: `exported == True`, `permission == "android.permission.BIND_QUICK_SETTINGS_TILE"`, `<intent-filter>` has `QS_TILE` |
| `PanicRebootTileService` | `AndroidManifest.xml:84-93` | Present | `<service name="dev.silo.app.tiles.PanicRebootTileService">`: `exported == True`, `permission == "android.permission.BIND_QUICK_SETTINGS_TILE"`, `<intent-filter>` has `QS_TILE` |
| APK Signature & Structure | `app/app/build.gradle.kts:42-50` | Debug-fallback signed | `apksigner verify <apk>` exits `0`; `zipfile` confirms `AndroidManifest.xml`, `resources.arsc`, `classes.dex` present and no unstripped `.jks`/keystore/debug leaks |
| Bilingual Resource Strings | `res/values{,-in}/strings.xml` | 5 keys each (`app_name`, `app_name_disguise`, `tile_lockdown`, `tile_sensors`, `tile_panic`) | Both XML files parse cleanly with identical key sets and non-empty values; `app_name_disguise == "NetDiag"`; `aapt2 dump strings`/`resources` confirms Indonesian tile labels (`Kunci Jaringan`, `Matikan Sensor`, `Reboot Darurat`) and English labels (`Silo Lockdown`, `Sensors Off`, `Panic BFU`) are compiled into APK |
| R8 / ProGuard Enum Retention | `PROJECT.md:53` (`F10`, `APP-L08`/`CB-30`), `proguard-rules.pro` | **BUG**: No enum keep rule in `proguard-rules.pro` | (1) `proguard-rules.pro` contains explicit keep rule covering enums in `dev.silo.app.ipc.**` (`-keepclassmembers enum dev.silo.app.ipc.**` / `-keep enum dev.silo.app.ipc.**` / `-keep class dev.silo.app.ipc.** { *; }`); (2) **`dexdump` on compiled Release APK `classes*.dex`** proves `Ldev/silo/app/ipc/FirewallMode;`, `Ldev/silo/app/ipc/BlockAction;`, `Ldev/silo/app/ipc/FreezeMode;`, `Ldev/silo/app/ipc/SecurityPreset;`, `Ldev/silo/app/ipc/AuditSeverity;` and their `$$serializer` classes survived R8 minification! |

---

## 3. Genuine Opaque-Box Behavioral & Cross-Boundary Testing Architecture (F1–F10)

`tests/e2e/` can genuinely test `app/` **100% black-box without touching a single file in `app/`** via **three complementary verification layers**:

### Layer A: Real Gradle Unit Tests + JUnit XML Verification
- Execute `./gradlew :app:testDebugUnitTest --no-daemon --console=plain` (memoized once per E2E run).
- Parse every `TEST-*.xml` in `app/app/build/test-results/testDebugUnitTest/`.
- Assert:
  - Process return code == `0`.
  - Total `<testcase>` count `>= 5` (baseline `ProtocolAndParserTest` has 5 tests; after M3 finishes, expected significantly higher).
  - `failures == 0`, `errors == 0`, `skipped == 0` across all suites.
  - Zero `<failure>` or `<error>` or `<skipped>` elements inside any XML file.

### Layer B: Live Cross-Boundary JVM Contract Driver (Compiled Kotlin `app/` Bytecode ↔ Live Compiled Rust `silod` Binary!)

**Key Discovery**: After `testDebugUnitTest` compiles `app/`, ALL pure-JVM Kotlin bytecode (`dev.silo.app.ipc.*`, `dev.silo.app.data.BlocklistParser`, `dev.silo.app.data.AppInventory`, `dev.silo.app.ui.i18n.SiloStringsKt`, AND **`dev.silo.app.ipc.SiloClient`**!) exists unminified in `app/app/build/tmp/kotlin-classes/debug/`, and all runtime jars exist in `${GRADLE_USER_HOME:-~/.gradle}/caches/modules-2/files-2.1/`.

Using JDK 21's `/usr/bin/javac` and `/usr/bin/java` (already installed!), `android_harness.py` can compile a small Java contract driver into a temporary directory (`/tmp/silo_jvm_contract_XXXXXX`, deleted immediately in `finally:`) with classpath:
1. `app/app/build/tmp/kotlin-classes/debug`
2. `app/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes` (for `BuildConfig`)
3. `/opt/android-sdk/platforms/android-35/android.jar`
4. Discovered jars in `${GRADLE_USER_HOME:-~/.gradle}/caches/modules-2/files-2.1/`:
   - `kotlin-stdlib-*.jar` (excluding `-sources`/`-javadoc`)
   - `kotlinx-serialization-core-jvm-*.jar`
   - `kotlinx-serialization-json-jvm-*.jar`
   - `kotlinx-coroutines-core-jvm-*.jar`

**Why this is extraordinarily powerful (zero mocks of Silo code!)**:
1. **Real `SiloClient` ↔ Real `silod --bridge` ↔ Real `silod` Daemon**:
   - `SiloClient` invokes `su -c "<silodPath> --bridge"` (or `su -c "exec <silodPath> --bridge"`).
   - Instantiate `SiloClient` via reflection supporting BOTH 0-arg default constructor (`SiloClient()` — emitted automatically by Kotlin whenever all constructor params have defaults!) AND 1-arg `(String)` constructor, so M3 adding optional constructor params never breaks the harness.
   - Prepend an isolated `<sandbox>/bin` directory to `PATH` containing an executable `su` shim that intercepts `su -c ...` and execs `<REAL_HOST_SILOD_BIN> --bridge --socket <SANDBOX_SOCK>`!
   - Invoke `SiloClient.callRaw(cmd, params, timeoutMs)` from Java via `BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, cont) -> client.callRaw(...))`!
   - **Result**: Exercises the **REAL compiled Android `SiloClient` bytecode** talking over real pipes to the **REAL compiled Rust `silod --bridge`** talking over a real `0600` AF_UNIX socket to a **REAL running `silod`**!
2. **Real Live `silod` JSON → Real Compiled Kotlin `SiloModels` Deserialization**:
   - Feed actual JSON payloads emitted by live `silod` directly into `client.getJson().decodeFromString(<Model>.Companion.serializer(), jsonStr)`:
     - `get_status` → `DaemonStatus` (verify `version == "3.0.0"`, `panicLockdownActive` field present & reflects lockdown state!)
     - `get_config` → `SiloConfig` (verify `schemaVersion == 3`, `firewall.panicLockdownActive`, `rules: Map<Int, AppFirewallRule>` string-keyed UIDs, `freezer.entries: Map<Int, FreezerEntry>`, `scopes.guards`)
     - `inspect_app_permissions` / `set_app_permission_action` → `AppLivePermissions` & `CategoryPermLiveState` (verify additive target fields `runtimeGranted`, `hasRuntimePerms`, `supportsForeground`, `systemFixed`, plus `appopsMode`, `runningNow`, `category`)
     - `get_privacy_access_log` → `ListSerializer(PrivacyAccessEvent.Companion.serializer())`
     - `run_sentinel_audit` → `SentinelReport` (verify `AuditSeverity` enum decoding!)
     - `run_live_proof` → `LiveProofReport`
     - `get_freezer_status` → `FreezerStatus`
     - `get_hardening_status` → `HardeningStatus`
     - `trigger_panic_lockdown` → `PanicLockdownReport`
     - `get_telemetry` → `TelemetrySnapshot`
     - `get_live_connections` → `ListSerializer(ActiveConnection.Companion.serializer())`
3. **CRITICAL SCHEMA DRIFT WARNING (`last_access` vs `last_access_ago`)**:
   - In baseline code, BOTH `daemon/src/protocol.rs:606` and `app/.../SiloModels.kt:230` use field name **`last_access`** (`Option<String>` / `String?`).
   - In `PROJECT.md:90`, the illustrative JSON snippet wrote `"last_access_ago": "3m12s ago"`, while `PROJECT.md:84` explicitly stipulates: *"All existing fields MUST remain backward-compatible; additive fields use `#[serde(default)]` in Rust and default values in Kotlin (`ignoreUnknownKeys = true`)"*.
   - **Harness rule**: Verify the exact **live `silod` JSON** deserializes cleanly into compiled Kotlin `CategoryPermLiveState` with `ignoreUnknownKeys = true` AND verify all 4 mandatory additive fields (`runtime_granted` → `runtimeGranted`, `has_runtime_perms` → `hasRuntimePerms`, `supports_foreground` → `supportsForeground`, `system_fixed` → `systemFixed`) round-trip accurately! For the last-access timestamp field, accept either `last_access` or `last_access_ago` in raw JSON while confirming Kotlin reads the timestamp non-null when present.
4. **Live Fault-Injection Against Real Compiled `SiloClient` (`F4` Tier 2)**:
   - Using programmable `<sandbox>/bin/su` fault-injection shims, test every `SiloClient` defect from `PROJECT.md` F4 black-box:
     - **Timeout cancellation (`APP-C03`)**: `su` shim reads request line then sleeps `30s`; call `SiloClient.callRaw("ping", null, 800L)` → MUST return failure in `< 3.0s` (proving blocking `readLine()` is genuinely cancelled/aborted, NOT hung for 30s!) AND kill the stalled bridge subprocess!
     - **Stderr flood non-deadlock (`APP-H02`)**: `su` shim writes **256 KiB** (4x Linux 64 KiB pipe buffer!) to `stderr` BEFORE writing valid JSON response to `stdout` → MUST NOT deadlock and MUST return `ok` response! Also when `su` writes an error to `stderr` and exits with EOF, the returned error message MUST surface the stderr diagnostic!
     - **Response ID mismatch (`APP-H03`)**: `su` shim replies with `{"id": 999999, "ok": true, "data": {"pong": true}}` (wrong `id`!) → MUST fail/reject the mismatched response instead of returning corrupt data!
     - **Structured RPC error preserves bridge (`SiloClient.kt:80`)**: When daemon returns `{"id": reqId, "ok": false, "error": "unknown command"}`, `SiloClient` returns `DaemonRpcException` AND keeps the same bridge PID alive for the subsequent `ping`!
     - **Forceful teardown (`APP-M11`)**: `su` shim traps `SIGTERM` (ignores graceful kill); call `SiloClient.disconnect()` → child process MUST be dead within `2s` (proving `destroyForcibly()` / `SIGKILL`!).
5. **Direct JVM Contract Execution of Real Compiled Pure-Logic Classes**:
   - `BlocklistParser.INSTANCE.parseLines(rawText, maxEntries)` (`F5`/`APP-M07`): test IPv4, IPv6, CIDRs, multi-IP lines (`"198.51.100.1, 203.0.113.2 2001:db8::1"`), comments `#`/`;`, deduplication, rejection of timestamps (`12:30:00`), malformed colons (`:::`), invalid IPv4 octets (`999.1.1.1`, `256.0.0.1`), invalid CIDR masks (`/33`, `/129`), and `maxEntries` cap.
   - `AppInventory.INSTANCE.isProtectedUid(uid)` & `evaluateOfflineRisk(declared, granted, components)` (`F1`/`F9`/`F11`/`APP-H08`/`APP-M08`):
     - Multi-user UID protection (`0`, `1000`, `1051`, `2000`, `101000`, `99001`, `1501000` protected; `10277`, `110277`, `1010277` Work Profile u10, `9510277` Dual Messenger u95, `15010277` Secure Folder u150 NOT protected).
     - `READ_MEDIA_VISUAL_USER_SELECTED` mapped to `"media"` (`APP-H08`).
     - Special AppOps `SYSTEM_ALERT_WINDOW` and `MANAGE_EXTERNAL_STORAGE` NOT counted as revocable runtime granted categories (`APP-H08`/`CB-09`).
     - Activity/Provider tracker components (`com.facebook.ads.AudienceNetworkActivity`, `com.google.firebase.provider.FirebaseInitProvider` / `com.appsflyer.*`) detected (`APP-M08`).
   - `AppPermGuard(autoRevokeOnBg, lockedDeny).totalCount()` (`F2`/`APP-M19`): passing overlapping `autoRevokeOnBg = ["camera"]` and `lockedDeny = ["camera"]` MUST return `1` (not `2` double-count!).
   - `SiloStringsKt.resolveStrings(AppLanguagePref.INDONESIAN / ENGLISH)` (`F10`/`F2`): verifies all 10 `categoryLabel(cat)` entries, distinct EN vs ID labels, non-empty strings, and honest `pm revoke` process-restart explanation copy (`APP-M19`).

### Layer C: Compiled Bytecode (`javap -c` / `dexdump`) Verification for Android Framework Classes

For classes coupled to Android framework APIs (`TileService`, `ModalBottomSheet`, `Compose` screens) that cannot be instantiated on host JVM without Robolectric, inspecting the **compiled bytecode** (`javap -c -p` on `app/app/build/tmp/kotlin-classes/debug/` and `dexdump` on `app-release.apk`) provides genuine, non-brittle, opaque-box verification of **standardized Android SDK / Kotlin stdlib method calls** that M3 cannot rename:

| Feature / Defect | Target Compiled Class | Standardized Bytecode Invariant (`javap -c` / `dexdump`) |
|---|---|---|
| **F10 / `APP-H01` / `CB-13`** | `dev/silo/app/tiles/LockdownTileService*.class` | Bytecode invokes `android/service/quicksettings/TileService.isLocked:()Z` AND `android/service/quicksettings/TileService.unlockAndRun:(Ljava/lang/Runnable;)V`! |
| **F10 / `APP-H01` / `CB-13`** | `dev/silo/app/tiles/SensorsOffTileService*.class` | Bytecode invokes `android/service/quicksettings/TileService.isLocked:()Z` AND `android/service/quicksettings/TileService.unlockAndRun:(Ljava/lang/Runnable;)V`! |
| **F10 / `APP-M10`** | `dev/silo/app/tiles/PanicRebootTileService*.class` | (1) References `kotlinx/coroutines/Dispatchers.getMain` (for `qsTile.updateTile()` on Main dispatcher); (2) invokes `kotlinx/coroutines/Job.cancel` (cancels timeout job on 2nd tap); (3) field for arm timestamp has `ACC_VOLATILE` (or uses `AtomicLong`)! |
| **F4 / `APP-H02` / `APP-M11`** | `dev/silo/app/ipc/SiloClient*.class` | Bytecode invokes `java/lang/Process.getErrorStream:()Ljava/io/InputStream;` AND `java/lang/Process.destroyForcibly:()Ljava/lang/Process;`! |
| **F10 / `APP-L08` / `CB-30`** | `app-release.apk` (`classes*.dex` via `dexdump`) | All 5 `@Serializable` IPC enums (`FirewallMode`, `BlockAction`, `FreezeMode`, `SecurityPreset`, `AuditSeverity`) and their `$$serializer` classes exist in minified Release DEX! |
| **F10 / `APP-L06`** | `dev/silo/app/BuildConfig.class` + `SystemScreenKt.class` | `BuildConfig.VERSION_NAME == "3.0.0"`; `SystemScreenKt` does NOT contain hardcoded literal `"Silo v3.0.0"` (reads `BuildConfig.VERSION_NAME` instead). |
| **F4 / `APP-H06`** | `AppsScreenKt*.class` | Invokes `rememberModalBottomSheetState` with `skipPartiallyExpanded = true` (or passes `true` boolean into `rememberModalBottomSheetState`). |

### 3.1 Summary of F1–F10 App-Side Target Contracts (from `PROJECT.md § Feature Inventory`)

| Feature | Key App-Side Contracts to Verify | Best Black-Box Verification Surface(s) |
|---|---|---|
| **F1** | `CategoryPermLiveState` additive fields (`runtime_granted`, `has_runtime_perms`, `supports_foreground`, `system_fixed`), `AppInventory` aligned runtime permissions (`READ_MEDIA_VISUAL_USER_SELECTED`, special AppOps `SYSTEM_ALERT_WINDOW`/`MANAGE_EXTERNAL_STORAGE` separated), mutually exclusive `PermEffectiveUiMode`, `declaredSensitiveCategories` filtering, disable unsupported `foreground` | Layer B (live `silod` `inspect_app_permissions` → Kotlin `AppLivePermissions` + `AppInventory.evaluateOfflineRisk`), Layer A (M3 unit tests for `PermEffectiveUiMode`) |
| **F2** | `AppPermGuard` mutual exclusion (`auto_revoke_on_bg` ∩ `locked_deny` = ∅) & non-double-counted `totalCount()`, honest `pm revoke` process-restart copy in `SiloStrings` | Layer B (live `silod` `set_app_perm_guard` → Kotlin `ScopesConfig` + `AppPermGuard.totalCount()` + `SiloStrings`), Layer A |
| **F3** | `PrivacyAccessEvent` schema compatibility with live `silod` `get_privacy_access_log`, `TelemetrySnapshot` & `ActiveConnection` schemas, non-overlapping monitor refresh, stable keys, protected pkg filtering | Layer B (live `silod` `get_privacy_access_log` / `get_live_connections` / `get_telemetry` → Kotlin models), Layer A |
| **F4** | `SiloClient` genuine timeout cancellation, `stderr` drain (>64 KB), `resp.id == reqId` verification, `destroyForcibly()`, 4 MiB framing compatibility, `DaemonRpcException` bridge reuse, `AppInventory.reloadSingleApp` | Layer B (real compiled `SiloClient` ↔ real `silod --bridge` + fault-injection `su` shims!), Layer C (`javap` `getErrorStream` + `destroyForcibly`), Layer A |
| **F5** | `FirewallConfig.panicLockdownActive` & `DaemonStatus.panicLockdownActive`, `BlocklistParser` strict IPv4/IPv6 + multi-IP + reject timestamps/bad octets/masks, `replace_ip_blocklist` `rejected > 0` response contract, streaming SAF cap | Layer B (`BlocklistParser.parseLines` direct JVM + live `silod` `trigger_panic_lockdown` / `replace_ip_blocklist` → Kotlin models), Layer A |
| **F6** | `FreezerStatus` & `FreezerEntry` (`SoftCgroup` / `DeepStop`) live `silod` schema round-trip, sequential `ensureEntryAndFreeze` | Layer B (live `silod` `set_freezer_entry` / `get_freezer_status` → Kotlin `FreezerStatus`), Layer A |
| **F7** | `SensorPrivacyState`, `PrivateDnsState`, `RadioPrivacyState`, `AutomationConfig` live `silod` round-trip, `30m` auto-reboot formatting (NOT `0h`), custom DNS active & blank guard | Layer B (live `silod` `set_private_dns` / `set_radio_privacy` / `set_sensor_privacy` / `set_automation_config` → Kotlin models), Layer A (M3 duration formatter unit tests) |
| **F8** | `HardeningStatus` & `apply_preset` (`Safe`/`Balanced`/`Hardened`/`Paranoid`) live `silod` round-trip, exact preset matcher (not matching bare `fw.enabled`), Packet Scheduler `"Default"` not enabling hardening, confirmation dialogs | Layer B (live `silod` `apply_preset` / `get_hardening_status` → Kotlin `SiloConfig`/`HardeningStatus`), Layer A (M3 preset matcher tests) |
| **F9** | `SentinelReport` (`AuditSeverity` enum) & `LiveProofReport` live `silod` round-trip, `export_config_json` / `import_config_json`, `AppInventory` non-launcher user apps + Activity/Provider trackers + bounded `LruCache` | Layer B (live `silod` `run_sentinel_audit` / `run_live_proof` / export-import → Kotlin models + `AppInventory.evaluateOfflineRisk`), Layer A |
| **F10** | **M3-exclusive**: Lockscreen `isLocked`/`unlockAndRun` on `LockdownTileService` & `SensorsOffTileService`, `PanicRebootTileService` volatile + timeout cancel + `Dispatchers.Main`, `MainActivity exported="false"`, zero `INTERNET`, zero `POST_NOTIFICATIONS`, `allowBackup="false"`, dual launcher aliases, 3 QS tiles, bilingual `strings.xml` + `SiloStrings`, `BuildConfig.VERSION_NAME`, R8 ProGuard enum keep | **All 3 Layers**: `aapt2` + `apksigner` + `dexdump` on real `app-release.apk` + `javap` bytecode checks on `tiles/*.class` + Layer A JUnit XML + Layer B `SiloStringsKt` |

---

## 4. Concrete E2E Test Matrix Proposal (Tiers 1–4)

Every test below is **opaque-box, deterministic, non-trivial**, and runs against real compiled Silo artifacts.

### 4.1 Tier 1 — Feature Coverage (Happy Path)

#### F10 (Owned Exclusively by M3 — **6 Tier 1 tests**, exceeding `>= 5` minimum)
| Test ID | Feature | Test Description & Concrete Assertions |
|---|---|---|
| `T1-F10-01` | `F10` | **Release APK Build, Signature & Metadata Gate**: Build/locate real `app-release.apk`; verify `apksigner verify` passes; verify `aapt2 dump packagename` == `"dev.silo.app"`, `aapt2 dump badging` reports `versionName='3.0.0'`, `versionCode='30000'`, `minSdkVersion:'31'`, `targetSdkVersion:'35'`, and `BuildConfig.VERSION_NAME == "3.0.0"`. |
| `T1-F10-02` | `F10` | **Strict Offline-First & Minimal Permission Gate (`aapt2`)**: Run `aapt2 dump permissions` AND `aapt2 dump xmltree --file AndroidManifest.xml` on `app-release.apk`; verify `QUERY_ALL_PACKAGES` is present, `android.permission.INTERNET` is **absent**, `android.permission.POST_NOTIFICATIONS` is **absent**, and `<application android:allowBackup="false">`. |
| `T1-F10-03` | `F10` | **App Disguise Manifest Architecture (`MainActivity` + Dual Aliases)**: Inspect `aapt2 dump xmltree --file AndroidManifest.xml` on `app-release.apk`; verify `MainActivity` has `exported=false` and ZERO `MAIN`/`LAUNCHER` intent filters, `LauncherDefault` (`enabled=true`, `exported=true`) and `LauncherDisguise` (`enabled=false`, `exported=true`) both target `MainActivity` with `MAIN`+`LAUNCHER`. |
| `T1-F10-04` | `F10` | **Quick Settings Tiles Manifest & Lockscreen Security Contract**: Verify all 3 QS Tile services (`LockdownTileService`, `SensorsOffTileService`, `PanicRebootTileService`) in compiled APK manifest (`exported=true`, `permission=BIND_QUICK_SETTINGS_TILE`, action `QS_TILE`); verify compiled bytecode (`javap -c`) of `LockdownTileService` & `SensorsOffTileService` invokes `TileService.isLocked()` and `TileService.unlockAndRun(Runnable)`. |
| `T1-F10-05` | `F10` | **R8 Release ProGuard IPC Enum & Serializer Survival (`dexdump`)**: Verify `proguard-rules.pro` has explicit enum keep rule for `dev.silo.app.ipc.**` AND inspect `classes*.dex` inside real R8-minified `app-release.apk` via `dexdump` proving `FirewallMode`, `BlockAction`, `FreezeMode`, `SecurityPreset`, `AuditSeverity` and `$$serializer` classes survived shrinking. |
| `T1-F10-06` | `F10` | **Bilingual Localization Parity (APK Resources + JVM `SiloStrings`)**: Verify `res/values/strings.xml` and `res/values-in/strings.xml` have identical keys (`app_name`, `app_name_disguise="NetDiag"`, `tile_lockdown`, `tile_sensors`, `tile_panic`), verify both EN and ID tile labels exist in compiled APK (`aapt2 dump strings`/`resources`), AND invoke compiled `SiloStringsKt.resolveStrings(INDONESIAN)` vs `(ENGLISH)` on JVM verifying distinct non-empty labels across all 10 permission categories and tabs. |

#### App-Side & Cross-Boundary Tier 1 Tests for F1–F9 (Complementing `silod` Tests)
| Test ID | Feature | Test Description & Concrete Assertions |
|---|---|---|
| `T1-F1-APP01` | `F1` | **Live `silod` `inspect_app_permissions` → Compiled Kotlin `AppLivePermissions` Contract**: Run real `silod` with One UI 7 `cmd appops get` shim (`Uid mode: CAMERA: ignore` + `CAMERA: allow; time=+3m12s ago`), invoke via real compiled `SiloClient`, deserialize into compiled `AppLivePermissions`, verify `camera.appopsMode == "ignore"`, `hasRuntimePerms == true`, `supportsForeground == true`, `clipboard.hasRuntimePerms == false`, `clipboard.supportsForeground == false`. |
| `T1-F1-APP02` | `F1` | **Android JVM Unit Test Suite Execution Gate**: Run real `./gradlew :app:testDebugUnitTest --no-daemon` and parse `app/app/build/test-results/testDebugUnitTest/TEST-*.xml`; verify exit code `0`, `tests >= 5`, `failures == 0`, `errors == 0`, `skipped == 0`. |
| `T1-F2-APP01` | `F2` | **Live `silod` `set_app_perm_guard` → Compiled Kotlin `ScopesConfig` + `AppPermGuard` Contract**: Drive real `silod` via real `SiloClient` with `set_app_perm_guard`, deserialize `ScopesConfig` in Kotlin, verify `guards[pkg]` round-trip and `AppPermGuard.totalCount()` + honest `SiloStrings` `pm revoke` copy. |
| `T1-F3-APP01` | `F3` | **Live `silod` Monitor RPCs → Compiled Kotlin `PrivacyAccessEvent` / `ActiveConnection` / `TelemetrySnapshot`**: Drive real `silod` with realistic `dumpsys appops --op` shim via `SiloClient`, deserialize `List<PrivacyAccessEvent>`, `List<ActiveConnection>`, and `TelemetrySnapshot` cleanly in Kotlin bytecode. |
| `T1-F4-APP01` | `F4` | **Real Compiled `SiloClient` ↔ Real `silod --bridge` ↔ Real `silod` Socket End-to-End**: Run real `SiloClient` through sandboxed `su` → real `silod --bridge` → real `silod`; execute sequential `ping`, `get_status`, `get_config`, verify `DaemonStatus.version == "3.0.0"`, `SiloConfig.schemaVersion == 3`, and verify `SiloClient` bytecode references `getErrorStream` and `destroyForcibly`. |
| `T1-F5-APP01` | `F5` | **Compiled `BlocklistParser` Multi-IP / IPv4 / IPv6 / Comment Extraction**: Invoke real compiled `BlocklistParser.parseLines` on JVM with IPv4, IPv4/CIDR, IPv6, IPv6/CIDR, inline `#`/`;` comments, duplicates, and multi-IP lines (`"198.51.100.1, 203.0.113.2 2001:db8::/48"`); verify ALL valid IPs extracted and deduplicated cleanly. |
| `T1-F6-APP01` | `F6` | **Live `silod` `FreezerStatus` → Compiled Kotlin `FreezerStatus` + `FreezeMode` Enum**: Set `SoftCgroup` and `DeepStop` entries on live `silod` via `SiloClient`, deserialize `FreezerStatus` & `SiloConfig.freezer` in Kotlin confirming enum round-trip (`"soft_cgroup"` / `"deep_stop"`). |
| `T1-F7-APP01` | `F7` | **Live `silod` Privacy Controls (`PrivateDns`, `RadioPrivacy`, `SensorPrivacy`, `Automation`) → Compiled Kotlin DTOs**: Call `set_private_dns`, `set_radio_privacy`, `set_sensor_privacy`, `set_automation_config` via real `SiloClient`, deserialize all 4 response DTOs cleanly in Kotlin. |
| `T1-F8-APP01` | `F8` | **Live `silod` `apply_preset` (all 4 presets) & `get_hardening_status` → Compiled Kotlin `SiloConfig` / `HardeningStatus`**: Cycle `SAFE`, `BALANCED`, `HARDENED`, `PARANOID` via real `SiloClient`, deserialize every `SiloConfig` and `HardeningStatus` cleanly in Kotlin. |
| `T1-F9-APP01` | `F9` | **Live `silod` `run_sentinel_audit` & `run_live_proof` + Compiled `AppInventory.evaluateOfflineRisk`**: Deserialize real `SentinelReport` (`AuditSeverity` enum) and `LiveProofReport` in Kotlin; verify `AppInventory.evaluateOfflineRisk` with `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14+ → `"media"`) and Activity/Provider tracker components. |

---

### 4.2 Tier 2 — Boundary, Corner & Negative Error Cases

#### F10 (Owned Exclusively by M3 — **6 Tier 2 tests**, exceeding `>= 5` minimum)
| Test ID | Feature | Test Description & Concrete Assertions |
|---|---|---|
| `T2-F10-01` | `F10` | **Direct `MainActivity` External Launch / Disguise Bypass Rejection**: Inspect compiled APK `xmltree`; assert `MainActivity` does NOT have `exported=true` and has ZERO `<intent-filter>` elements (preventing external apps from bypassing `LauncherDisguise` by launching `dev.silo.app/.MainActivity` directly). |
| `T2-F10-02` | `F10` | **No Network / Hidden Transitive Permissions Leak (`uses-permission-sdk-23` / `<uses-permission>`)**: Scan ALL permission declarations in compiled APK (`aapt2 dump permissions` + `xmltree` including `uses-permission-sdk-23`); assert ZERO occurrence of `INTERNET`, `POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`, or `WRITE_EXTERNAL_STORAGE`. |
| `T2-F10-03` | `F10` | **`PanicRebootTileService` Concurrency, Main-Thread & Cancellation Safety (`javap`)**: Inspect compiled `PanicRebootTileService*.class` bytecode; verify (1) `Dispatchers.getMain` is used (preventing `CalledFromWrongThreadException` / UI race on `updateTile()`), (2) `Job.cancel` is called (preventing 5.2s timeout coroutine from clobbering `"Rebooting..."` after 2nd tap), and (3) arm timestamp field is `@Volatile` (`ACC_VOLATILE`) or `AtomicLong`. |
| `T2-F10-04` | `F10` | **No Hardcoded Version String in `SystemScreen` + Disguise Alias Initially Disabled**: Verify `LauncherDisguise` is strictly `enabled=false` at install time (preventing dual launcher icons on fresh install), `LauncherDefault` is `enabled=true`, and `SystemScreenKt` bytecode does NOT hardcode `"Silo v3.0.0"` literal (uses `BuildConfig.VERSION_NAME`). |
| `T2-F10-05` | `F10` | **`SiloStrings` Unknown Category & Locale Fallback Boundary**: Invoke compiled `SiloStringsKt.resolveStrings(AppLanguagePref.SYSTEM)` and `categoryLabel("unknown_future_category")` / `categoryDesc("unknown_future_category")` on JVM; verify graceful non-null, non-empty fallback without throwing exceptions. |
| `T2-F10-06` | `F10` | **APK Resource & Manifest Integrity Negative Checks (No Backup, No Cleartext Traffic, Valid ZIP/DEX)**: Verify `<application>` does NOT set `allowBackup="true"` or `usesCleartextTraffic="true"` or `debuggable="true"` in `app-release.apk`, and verify `dexdump -c` (DEX checksum validation) passes on EVERY `classes*.dex` inside `app-release.apk`. |

#### App-Side & Cross-Boundary Tier 2 Boundary/Fault-Injection Tests for F1–F9
| Test ID | Feature | Test Description & Concrete Assertions |
|---|---|---|
| `T2-F1-APP01` | `F1` | **Special AppOps (`SYSTEM_ALERT_WINDOW` / `MANAGE_EXTERNAL_STORAGE`) Not Misclassified as Revocable Runtime Grants (`APP-H08`/`CB-09`)**: Invoke compiled `AppInventory.evaluateOfflineRisk` with `declared=[SYSTEM_ALERT_WINDOW, MANAGE_EXTERNAL_STORAGE]`, `granted=[SYSTEM_ALERT_WINDOW, MANAGE_EXTERNAL_STORAGE]`; verify neither is miscounted as a revocable runtime grant that stays permanently stuck after `Cabut Semua` (risk remains `LOW` / not stuck in `grantedCategories`). |
| `T2-F2-APP01` | `F2` | **Overlapping `AppPermGuard` Duplicate Category Non-Double-Count (`APP-M19`)**: Construct compiled `AppPermGuard(autoRevokeOnBg=["camera", "location"], lockedDeny=["camera"])` on JVM; verify `totalCount() == 2` (NOT `3` double-counting `"camera"`!). |
| `T2-F4-APP01` | `F4` | **`SiloClient` Genuine Blocking-I/O Timeout Cancellation (`APP-C03`)**: Point real compiled `SiloClient` at a `su` shim that accepts a request line and sleeps `30s` silently; call `callRaw("ping", null, 800L)` → MUST return failure in `< 3.0s` AND cleanly terminate the stalled bridge child! |
| `T2-F4-APP02` | `F4` | **`SiloClient` >64 KiB Stderr Flood Non-Deadlock & EOF Diagnostic Capture (`APP-H02`)**: Point real compiled `SiloClient` at a `su` shim that writes **256 KiB** to `stderr` before returning a valid JSON response on `stdout` → MUST succeed without pipe deadlock! Second call where `su` writes `"KSU root denied"` to `stderr` and exits with EOF → returned failure message MUST include `"KSU root denied"`. |
| `T2-F4-APP03` | `F4` | **`SiloClient` Response ID Mismatch Rejection (`APP-H03`)**: Point real compiled `SiloClient` at a `su` shim returning `{"id": 999999, "ok": true, "data": {"spoofed": true}}` → MUST reject mismatched `resp.id != reqId` (return failure + reset bridge) rather than returning spoofed payload! |
| `T2-F4-APP04` | `F4` | **`SiloClient` `SIGTERM`-Ignoring Child Forceful Teardown (`APP-M11`)**: Point real compiled `SiloClient` at a `su` shim that traps `SIGTERM`; complete one RPC, call `disconnect()`, and verify child PID exits within `2s` (`destroyForcibly()` / `SIGKILL`). |
| `T2-F5-APP01` | `F5` | **`BlocklistParser` Adversarial / Malformed Token Rejection & Cap (`APP-M07`)**: Invoke compiled `BlocklistParser.parseLines` with timestamps (`12:30:00`), bare colons (`:::`, `::1::2`), out-of-range IPv4 octets (`999.1.1.1`, `256.0.0.1`), invalid CIDR prefixes (`198.51.100.0/33`, `2001:db8::/129`, `10.0.0.1/0`), empty lines, and `maxEntries = 3` cap on 10 valid IPs → MUST reject every invalid token and enforce `size == 3`. |
| `T2-F9-APP01` | `F9` | **`AppInventory` Multi-User Protected UID Boundary (`F9`/`F11`)**: Invoke compiled `AppInventory.isProtectedUid` across multi-user boundaries: `0`, `1000`, `9999`, `90000`, `99999`, `101000`, `1501000` → `true`; `10000`, `10277`, `89999`, `110000`, `1010277` (Work Profile u10), `9510277` (Dual Messenger u95), `15010277` (Secure Folder u150) → `false`. |

---

### 4.3 Tier 3 — Pairwise Cross-Feature App ↔ Daemon Interaction Tests

| Test ID | Feature Pair | Pairwise Cross-Feature Verification |
|---|---|---|
| `T3-APP-01` | `F4 + F5` | **Large Multi-IP Blocklist (> 512 KiB) via Real `SiloClient` → `silod --bridge` → `silod`**: Generate a >600 KiB raw blocklist text (valid IPv4/IPv6 + comments + invalid lines), parse via real compiled `BlocklistParser.parseLines`, push via real compiled `SiloClient.callRaw("replace_ip_blocklist", ...)` through real `silod --bridge` to live `silod`, verify 4 MiB framing succeeds cleanly and `loaded` matches parsed count with `rejected == 0` (then send 1 invalid CIDR directly and verify `rejected == 1`). |
| `T3-APP-02` | `F1 + F2` | **Permission Revoke + `Kunci Blokir` + Override via Real `SiloClient` → Live `silod`**: Through real compiled `SiloClient` ↔ `silod --bridge` ↔ `silod` (with One UI 7 `cmd`/`pm` shims), run `set_app_perm_guard` (enabling `locked_deny=["camera"]` + conflicting `auto_revoke_on_bg=["camera"]`), verify returned Kotlin `ScopesConfig` stripped `"camera"` from `autoRevokeOnBg`, then run `set_app_permission_action("allow")` and verify `locked_deny` auto-cleared and Kotlin `AppLivePermissions` reflects restored state. |
| `T3-APP-03` | `F5 + F10` | **Panic Lockdown End-to-End Wire Contract + Lockscreen Tile Protection**: Trigger `trigger_panic_lockdown` through real compiled `SiloClient` → live `silod`, deserialize `PanicLockdownReport` + subsequent `DaemonStatus` + `SiloConfig` in Kotlin verifying `panicLockdownActive == true`, AND verify compiled `LockdownTileService` bytecode guards disabling lockdown behind `isLocked`/`unlockAndRun`. |
| `T3-APP-04` | `F7 + F10` | **Sensor Privacy Mute/Unmute Wire Contract + `SensorsOffTileService` Lockscreen Guard**: Mute/unmute `microphone` & `camera` through real compiled `SiloClient` → live `silod` (with One UI 7 `dumpsys sensor_privacy` shim), deserialize `SensorPrivacyState` & `DaemonStatus` (`micMuted`, `cameraMuted`, `sensorsOffActive`), AND verify compiled `SensorsOffTileService` bytecode enforces `isLocked`/`unlockAndRun`. |
| `T3-APP-05` | `F8 + F9` | **Preset Switching + Sentinel Audit + Live Proof Cross-Boundary Deserialization**: Apply `PARANOID` then `BALANCED` via real compiled `SiloClient` → live `silod`, verify Kotlin `SiloConfig` has zero leftover Paranoid automation flags, then run `run_sentinel_audit` and `run_live_proof` verifying every field & enum (`AuditSeverity`) deserializes cleanly into Kotlin `SentinelReport` & `LiveProofReport`. |

---

### 4.4 Tier 4 — Real-World Scenario Integration Points (`TEST_INFRA.md § 4`)

| Scenario | App & Cross-Boundary Role |
|---|---|
| **`T4-S01`** (`F1, F2, F3, F4`) | Run Samsung Camera (`com.sec.android.app.camera`) lifecycle using real `SiloClient` ↔ `silod --bridge` ↔ `silod`, verifying Kotlin `AppLivePermissions` & `PrivacyAccessEvent` deserialization alongside `get_status` `< 50ms` responsiveness. |
| **`T4-S02`** (`F5, F4, F7, F11`) | Verify Kotlin `DaemonStatus.panicLockdownActive == true` & `FirewallConfig.panicLockdownActive == true` from real `silod` during Panic Lockdown with Work Profile/Dual Messenger/Secure Folder rules. |
| **`T4-S03`** (`F4, F5, F9`) | Parse realistic FireHOL-like text via real compiled `BlocklistParser`, push >512 KiB payload over real `SiloClient` → `silod --bridge`, verify `rejected > 0` detection + `export_config_json`/`import_config_json` round-trip into Kotlin `SiloConfig`. |
| **`T4-S05`** (`F7, F8, F9`) | Cycle all 4 presets via `SiloClient` verifying clean Kotlin `SiloConfig` + `HardeningStatus` + `LiveProofReport` + `SentinelReport` models. |
| **`T4-S06`** (`F10, F12`) | **Full Release Gate**: Real `./gradlew testDebugUnitTest assembleRelease --no-daemon` → JUnit XML 100% pass → `apksigner verify` → `aapt2 dump permissions`/`badging`/`xmltree` (zero `INTERNET`, zero `POST_NOTIFICATIONS`, `MainActivity exported=false`, dual launcher aliases, 3 QS tiles, `allowBackup=false`) → `dexdump` R8 enum survival → `javap` tile lockscreen guards → paired with `scripts/build_module.sh` zero-mount ZIP & CI workflow security verification. |

---

## 5. Key Gotchas & Implementation Guidance for `android_harness.py`

1. **Zero Modifications to `app/`**: Never write `local.properties`, never add `.kt` test files into `app/app/src/test/`, and never leave temporary files in `app/`. Compile all standalone Java contract drivers inside `tempfile.mkdtemp(prefix="silo_e2e_jvm_")` and delete in `finally:`.
2. **Gradle Cold-Cache First Run**: Because `~/.gradle/wrapper/dists/` is initially empty, the first `gradlew` call will download Gradle 8.10.2 + Maven dependencies. Use a `600s` default timeout (`SILO_GRADLE_TIMEOUT_SECS`) and capture tail stdout/stderr on failure for clear diagnostics.
3. **Resilient Kotlin/JVM Reflection for `SiloClient`**:
   - M3 may add optional default-valued parameters to `SiloClient` constructor (e.g., `commandFactory`, `readTimeout`, etc.).
   - In the Java driver, instantiate `SiloClient` by checking for a 0-arg constructor `SiloClient()` first (automatically generated by Kotlin when all constructor args have defaults), falling back to `(String)` constructor with `"/data/adb/modules/silo/bin/silod"`.
   - Because `SiloClient` spawns `su -c "<silodPath> --bridge"` (or `su -c "exec <silodPath> --bridge"`), intercepting `su` via a temp directory prepended to `PATH` works regardless of `silodPath` or whether `exec` was added!
4. **`CategoryPermLiveState` Reflection / Serialization**:
   - Use `client.getJson().decodeFromString(AppLivePermissions.Companion.serializer(), rawJson)` — Kotlin's `Json` already sets `ignoreUnknownKeys = true`.
   - Read additive properties (`getRuntimeGranted()`, `getHasRuntimePerms()`, `getSupportsForeground()`, `getSystemFixed()`) via reflection or compiled method calls; if a getter is missing because M3 hasn't implemented the frozen contract yet, **fail loudly** with a clear diagnostic naming the missing `PROJECT.md § Interface Contracts` property!
5. **`dexdump` Multi-DEX Handling**:
   - Release APKs with Compose Material 3 may produce `classes.dex`, `classes2.dex`, etc. Extract ALL `classes*.dex` entries from `app-release.apk` into a temp dir and run `dexdump` across all of them when checking R8 enum/serializer retention and DEX checksums (`dexdump -c`).
6. **`javap` Companion / Inner / Lambda Classes**:
   - Kotlin coroutines compile `suspend` lambdas into synthetic inner classes like `LockdownTileService$onClick$1.class` or `LockdownTileService$onClick$1$1.class`!
   - When verifying `LockdownTileService`, `SensorsOffTileService`, `PanicRebootTileService`, or `SiloClient` bytecode via `javap -c -p`, ALWAYS pass **all matching glob files** (`LockdownTileService.class` + `LockdownTileService$*.class`) to `javap -c -p` so calls inside coroutine/runnable lambdas are inspected!
