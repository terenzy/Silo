"""Real Android Gradle build/unit-test driver, aapt2/apksigner/dexdump/javap parsers, and JVM contract runner."""

from __future__ import annotations

import atexit
import fcntl
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[3]
APP_DIR = REPO_ROOT / "app"
APP_MODULE_DIR = APP_DIR / "app"
DEFAULT_RELEASE_APK = APP_MODULE_DIR / "build" / "outputs" / "apk" / "release" / "app-release.apk"
JUNIT_XML_DIR = APP_MODULE_DIR / "build" / "test-results" / "testDebugUnitTest"
KOTLIN_DEBUG_CLASSES = APP_MODULE_DIR / "build" / "tmp" / "kotlin-classes" / "debug"
JAVAC_DEBUG_CLASSES = (
    APP_MODULE_DIR / "build" / "intermediates" / "javac" / "debug" / "compileDebugJavaWithJavac" / "classes"
)
STRINGS_EN_XML = APP_MODULE_DIR / "src" / "main" / "res" / "values" / "strings.xml"
STRINGS_ID_XML = APP_MODULE_DIR / "src" / "main" / "res" / "values-in" / "strings.xml"

_GRADLE_SESSION_LOCK = threading.Lock()
_UNIT_TESTS_DONE = False
_RELEASE_APK_PATH: Path | None = None
_JVM_DRIVER_LOCK = threading.Lock()
_JVM_DRIVER_DIR: Path | None = None
_JVM_DRIVER_CP_KEY: str | None = None


def _cleanup_jvm_driver_cache() -> None:
    global _JVM_DRIVER_DIR, _JVM_DRIVER_CP_KEY
    if _JVM_DRIVER_DIR is not None and _JVM_DRIVER_DIR.exists():
        shutil.rmtree(_JVM_DRIVER_DIR, ignore_errors=True)
    _JVM_DRIVER_DIR = None
    _JVM_DRIVER_CP_KEY = None


atexit.register(_cleanup_jvm_driver_cache)


def android_env() -> dict[str, str]:
    """Build deterministic JDK 21 + Android SDK environment without writing `local.properties`."""
    env = os.environ.copy()
    env.setdefault("JAVA_HOME", "/usr/lib/jvm/java-21-openjdk-amd64")
    env.setdefault("ANDROID_HOME", "/opt/android-sdk")
    env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
    java_bin = str(Path(env["JAVA_HOME"]) / "bin")
    path_parts = env.get("PATH", "").split(os.pathsep)
    if java_bin not in path_parts and Path(java_bin).is_dir():
        env["PATH"] = f"{java_bin}{os.pathsep}{env.get('PATH', '')}"
    return env


def _version_tuple(name: str) -> tuple[int, ...]:
    nums = [int(p) for p in re.findall(r"\d+", name)]
    return tuple(nums) if nums else (0,)


def find_build_tool(tool_name: str) -> Path:
    """Dynamically locate an Android SDK build-tool (`aapt2`, `apksigner`, `dexdump`, `zipalign`)."""
    sdk = Path(android_env()["ANDROID_HOME"])
    bt_root = sdk / "build-tools"
    if not bt_root.is_dir():
        raise RuntimeError(f"Android build-tools directory missing: {bt_root}")
    candidates: list[tuple[tuple[int, ...], Path]] = []
    for ver_dir in bt_root.iterdir():
        if not ver_dir.is_dir():
            continue
        cand = ver_dir / tool_name
        if cand.is_file() and os.access(cand, os.X_OK):
            candidates.append((_version_tuple(ver_dir.name), cand))
    if not candidates:
        raise RuntimeError(f"Android build-tool {tool_name!r} not found under {bt_root}")
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1]


def find_android_jar() -> Path:
    sdk = Path(android_env()["ANDROID_HOME"])
    for api in ("android-35", "android-36", "android-34"):
        jar = sdk / "platforms" / api / "android.jar"
        if jar.is_file():
            return jar
    raise RuntimeError(f"No android.jar found under {sdk / 'platforms'}")


def _gradle_timeout() -> float:
    return float(os.environ.get("SILO_GRADLE_TIMEOUT_SECS", "600"))


def _run_gradlew(tasks: list[str]) -> subprocess.CompletedProcess[str]:
    gradlew = APP_DIR / "gradlew"
    if not gradlew.is_file():
        raise RuntimeError(f"Gradle wrapper missing: {gradlew}")
    lock_path = Path(tempfile.gettempdir()) / "silo_e2e_gradle.lock"
    with open(lock_path, "a+", encoding="utf-8") as lf:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        try:
            cmd = [str(gradlew), *tasks, "--no-daemon", "--console=plain"]
            if os.environ.get("SILO_GRADLE_RERUN") == "1":
                cmd.append("--rerun-tasks")
            return subprocess.run(
                cmd,
                cwd=str(APP_DIR),
                env=android_env(),
                capture_output=True,
                text=True,
                timeout=_gradle_timeout(),
            )
        finally:
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)


def ensure_unit_tests_run() -> Path:
    """Ensure `:app:testDebugUnitTest` has executed (memoized, flock-locked, honors `SILO_SKIP_BUILD=1`)."""
    global _UNIT_TESTS_DONE
    with _GRADLE_SESSION_LOCK:
        if os.environ.get("SILO_SKIP_BUILD") == "1":
            if not JUNIT_XML_DIR.is_dir() or not list(JUNIT_XML_DIR.glob("TEST-*.xml")):
                raise RuntimeError(
                    f"SILO_SKIP_BUILD=1 but JUnit XML results are missing in {JUNIT_XML_DIR}"
                )
            if not KOTLIN_DEBUG_CLASSES.is_dir():
                raise RuntimeError(
                    f"SILO_SKIP_BUILD=1 but compiled debug Kotlin classes missing in {KOTLIN_DEBUG_CLASSES}"
                )
            _UNIT_TESTS_DONE = True
            return JUNIT_XML_DIR

        if _UNIT_TESTS_DONE and JUNIT_XML_DIR.is_dir() and KOTLIN_DEBUG_CLASSES.is_dir():
            return JUNIT_XML_DIR

        proc = _run_gradlew([":app:testDebugUnitTest"])
        if proc.returncode != 0:
            raise AssertionError(
                f"./gradlew :app:testDebugUnitTest failed (rc={proc.returncode}):\n"
                f"STDOUT:\n{proc.stdout[-3000:]}\nSTDERR:\n{proc.stderr[-3000:]}"
            )
        if not JUNIT_XML_DIR.is_dir() or not list(JUNIT_XML_DIR.glob("TEST-*.xml")):
            raise AssertionError(
                f"testDebugUnitTest succeeded but no TEST-*.xml found in {JUNIT_XML_DIR}"
            )
        _UNIT_TESTS_DONE = True
        return JUNIT_XML_DIR


def resolve_release_apk() -> Path | None:
    override = os.environ.get("SILO_RELEASE_APK", "").strip()
    if override:
        p = Path(override)
        if not p.is_file():
            raise RuntimeError(f"SILO_RELEASE_APK={override!r} does not exist")
        return p.resolve()
    if DEFAULT_RELEASE_APK.is_file():
        return DEFAULT_RELEASE_APK.resolve()
    rel_dir = DEFAULT_RELEASE_APK.parent
    if rel_dir.is_dir():
        apks = sorted(rel_dir.glob("*.apk"))
        if apks:
            return apks[0].resolve()
    return None


def ensure_release_apk_built() -> Path:
    """Resolve or build `app-release.apk` (memoized, flock-locked, honors `SILO_RELEASE_APK` & `SILO_SKIP_BUILD=1`)."""
    global _RELEASE_APK_PATH
    with _GRADLE_SESSION_LOCK:
        override = os.environ.get("SILO_RELEASE_APK", "").strip()
        if override:
            p = Path(override)
            if not p.is_file():
                raise RuntimeError(f"SILO_RELEASE_APK={override!r} does not exist")
            _RELEASE_APK_PATH = p.resolve()
            return _RELEASE_APK_PATH

        if _RELEASE_APK_PATH is not None and _RELEASE_APK_PATH.is_file():
            return _RELEASE_APK_PATH

        if os.environ.get("SILO_SKIP_BUILD") == "1":
            found = resolve_release_apk()
            if found is None:
                raise RuntimeError(
                    f"SILO_SKIP_BUILD=1 but release APK not found at {DEFAULT_RELEASE_APK}"
                )
            _RELEASE_APK_PATH = found
            return found

        proc = _run_gradlew([":app:assembleRelease"])
        if proc.returncode != 0:
            raise AssertionError(
                f"./gradlew :app:assembleRelease failed (rc={proc.returncode}):\n"
                f"STDOUT:\n{proc.stdout[-3000:]}\nSTDERR:\n{proc.stderr[-3000:]}"
            )
        found = resolve_release_apk()
        if found is None:
            raise AssertionError(
                f"assembleRelease succeeded but APK missing under {DEFAULT_RELEASE_APK.parent}"
            )
        _RELEASE_APK_PATH = found
        return found


# --- JUnit XML Parser ---


@dataclass
class JUnitSummary:
    files: list[Path]
    total_tests: int
    failures: int
    errors: int
    skipped: int
    failed_cases: list[str] = field(default_factory=list)


def parse_junit_xml_dir(xml_dir: Path | None = None) -> JUnitSummary:
    d = xml_dir or JUNIT_XML_DIR
    files = sorted(d.glob("TEST-*.xml"))
    if not files:
        raise AssertionError(f"No JUnit TEST-*.xml files found in {d}")
    total = failures = errors = skipped = 0
    failed_cases: list[str] = []
    for f in files:
        root = ET.fromstring(f.read_text(encoding="utf-8"))
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            cases = suite.findall("testcase")
            total += len(cases)
            for tc in cases:
                cname = f"{tc.attrib.get('classname', '')}.{tc.attrib.get('name', '')}"
                if tc.find("failure") is not None:
                    failures += 1
                    failed_cases.append(f"FAIL {cname}")
                if tc.find("error") is not None:
                    errors += 1
                    failed_cases.append(f"ERROR {cname}")
                if tc.find("skipped") is not None:
                    skipped += 1
                    failed_cases.append(f"SKIP {cname}")
    return JUnitSummary(
        files=files,
        total_tests=total,
        failures=failures,
        errors=errors,
        skipped=skipped,
        failed_cases=failed_cases,
    )


# --- aapt2 / apksigner / dexdump / javap Parsers ---


def _run_aapt2(args: list[str]) -> str:
    aapt2 = find_build_tool("aapt2")
    proc = subprocess.run([str(aapt2), *args], capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        raise AssertionError(
            f"aapt2 {' '.join(args)} failed (rc={proc.returncode}):\n{proc.stderr}\n{proc.stdout}"
        )
    return proc.stdout


def parse_aapt2_permissions_text(stdout: str) -> list[str]:
    """Parse `aapt2 dump permissions` capturing BOTH `uses-permission` and `uses-permission-sdk-23`."""
    return re.findall(r"^uses-permission(?:-[^:]+)?:\s+name='([^']+)'", stdout, re.MULTILINE)


def aapt2_permissions(apk: Path) -> list[str]:
    return parse_aapt2_permissions_text(_run_aapt2(["dump", "permissions", str(apk)]))


def aapt2_packagename(apk: Path) -> str:
    return _run_aapt2(["dump", "packagename", str(apk)]).strip()


def parse_aapt2_badging_text(stdout: str) -> dict[str, Any]:
    out: dict[str, Any] = {}
    m_pkg = re.search(
        r"package:\s+name='([^']+)'\s+versionCode='(\d+)'\s+versionName='([^']+)'",
        stdout,
    )
    if m_pkg:
        out["package_name"] = m_pkg.group(1)
        out["version_code"] = int(m_pkg.group(2))
        out["version_name"] = m_pkg.group(3)
    m_min = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", stdout)
    if m_min:
        out["min_sdk"] = int(m_min.group(1))
    m_tgt = re.search(r"targetSdkVersion:'(\d+)'", stdout)
    if m_tgt:
        out["target_sdk"] = int(m_tgt.group(1))
    out["permissions"] = parse_aapt2_permissions_text(stdout)
    return out


def aapt2_badging(apk: Path) -> dict[str, Any]:
    return parse_aapt2_badging_text(_run_aapt2(["dump", "badging", str(apk)]))


@dataclass
class XmlNode:
    tag: str
    attrs: dict[str, Any] = field(default_factory=dict)
    children: list["XmlNode"] = field(default_factory=list)
    indent: int = 0

    def find_all(self, tag: str) -> list["XmlNode"]:
        found: list[XmlNode] = []
        if self.tag == tag:
            found.append(self)
        for ch in self.children:
            found.extend(ch.find_all(tag))
        return found


_XMLTREE_ELEM_RE = re.compile(r"^(\s*)E:\s+(\S+)")
_XMLTREE_ATTR_RE = re.compile(
    r"^(\s*)A:\s+(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_.-]+)(?:\(0x[0-9a-fA-F]+\))?=(.+)$"
)
_XMLTREE_QUOTED_RE = re.compile(r'^"(.*)"(?:\s+\(Raw:\s+".*"\))?$')


def _normalize_xmltree_value(raw: str) -> Any:
    val = raw.strip()
    m_q = _XMLTREE_QUOTED_RE.match(val)
    if m_q:
        return m_q.group(1)
    if val == "true" or val == "(type 0x12)0xffffffff" or val == "(type 0x12)0x1":
        return True
    if val == "false" or val == "(type 0x12)0x0":
        return False
    if re.fullmatch(r"-?\d+", val):
        return int(val)
    m_hex = re.fullmatch(r"\(type 0x[0-9a-fA-F]+\)0x([0-9a-fA-F]+)", val)
    if m_hex:
        return int(m_hex.group(1), 16)
    return val


def normalize_component_fqcn(name: str, pkg: str = "dev.silo.app") -> str:
    if name.startswith("."):
        return f"{pkg}{name}"
    if "." not in name:
        return f"{pkg}.{name}"
    return name


def parse_aapt2_xmltree_text(stdout: str) -> XmlNode:
    """Parse `aapt2 dump xmltree` output into a structured `XmlNode` tree."""
    root = XmlNode(tag="__root__", indent=-1)
    stack: list[XmlNode] = [root]

    for line in stdout.splitlines():
        m_e = _XMLTREE_ELEM_RE.match(line)
        if m_e:
            indent = len(m_e.group(1))
            tag = m_e.group(2)
            while len(stack) > 1 and stack[-1].indent >= indent:
                stack.pop()
            node = XmlNode(tag=tag, indent=indent)
            stack[-1].children.append(node)
            stack.append(node)
            continue
        m_a = _XMLTREE_ATTR_RE.match(line)
        if m_a and len(stack) > 1:
            attr_name = m_a.group(2)
            attr_val = _normalize_xmltree_value(m_a.group(3))
            stack[-1].attrs[attr_name] = attr_val

    return root


def aapt2_manifest_tree(apk: Path) -> XmlNode:
    out = _run_aapt2(["dump", "xmltree", "--file", "AndroidManifest.xml", str(apk)])
    return parse_aapt2_xmltree_text(out)


def aapt2_dump_strings(apk: Path) -> str:
    return _run_aapt2(["dump", "strings", str(apk)])


def aapt2_dump_resources(apk: Path) -> str:
    return _run_aapt2(["dump", "resources", str(apk)])


def apksigner_verify(apk: Path) -> str:
    apksigner = find_build_tool("apksigner")
    proc = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        env=android_env(),
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"apksigner verify failed on {apk} (rc={proc.returncode}):\n{proc.stderr}\n{proc.stdout}"
        )
    return proc.stdout


def dexdump_inspect_apk(apk: Path) -> tuple[set[str], list[str]]:
    """Extract ALL `classes*.dex` from APK, verify checksums (`dexdump -c`), and collect all class descriptors."""
    dexdump = find_build_tool("dexdump")
    descriptors: set[str] = set()
    dex_names: list[str] = []
    with tempfile.TemporaryDirectory(prefix="silo_e2e_dex_") as td:
        td_path = Path(td)
        with zipfile.ZipFile(apk, "r") as zf:
            dex_entries = sorted(
                n for n in zf.namelist() if re.fullmatch(r"classes\d*\.dex", n)
            )
            if not dex_entries:
                raise AssertionError(f"No classes*.dex entries found inside {apk}")
            for dex_name in dex_entries:
                dex_names.append(dex_name)
                dest = td_path / dex_name
                dest.write_bytes(zf.read(dex_name))

                chk = subprocess.run(
                    [str(dexdump), "-c", str(dest)],
                    capture_output=True,
                    text=True,
                    timeout=60,
                )
                if chk.returncode != 0:
                    raise AssertionError(
                        f"dexdump -c checksum validation failed for {dex_name}: {chk.stderr}\n{chk.stdout}"
                    )

                dump = subprocess.run(
                    [str(dexdump), "-f", str(dest)],
                    capture_output=True,
                    text=True,
                    timeout=120,
                )
                if dump.returncode != 0:
                    raise AssertionError(
                        f"dexdump -f failed for {dex_name}: {dump.stderr}"
                    )
                for m in re.finditer(r"Class descriptor\s+:\s+'([^']+)'", dump.stdout):
                    descriptors.add(m.group(1))
    return descriptors, dex_names


def javap_disassemble_classes(class_dir: Path, rel_prefix: str) -> str:
    """Run `javap -c -p` across `Prefix.class` AND all `Prefix$*.class` inner/lambda classes!"""
    rel_p = Path(rel_prefix)
    parent = class_dir / rel_p.parent
    stem = rel_p.name
    if not parent.is_dir():
        raise AssertionError(f"Compiled class directory missing: {parent}")
    matches: list[Path] = []
    primary = parent / f"{stem}.class"
    if primary.is_file():
        matches.append(primary)
    matches.extend(sorted(parent.glob(f"{stem}$*.class")))
    if not matches:
        raise AssertionError(
            f"No compiled class files found matching {stem}(|$*).class in {parent}"
        )
    env = android_env()
    javap_bin = str(Path(env["JAVA_HOME"]) / "bin" / "javap")
    proc = subprocess.run(
        [javap_bin, "-c", "-p", *[str(m) for m in matches]],
        env=env,
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        raise AssertionError(f"javap failed (rc={proc.returncode}): {proc.stderr}")
    return proc.stdout


def parse_android_strings_xml(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise AssertionError(f"strings.xml missing: {path}")
    root = ET.fromstring(path.read_text(encoding="utf-8"))
    out: dict[str, str] = {}
    for elem in root.findall("string"):
        name = elem.attrib.get("name")
        if name:
            out[name] = elem.text or ""
    return out


# --- Standalone JDK 21 JVM Cross-Boundary Contract Driver ---


def _discover_gradle_runtime_jars() -> list[Path]:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    cache_root = gradle_home / "caches" / "modules-2" / "files-2.1"
    if not cache_root.is_dir():
        raise RuntimeError(
            f"Gradle dependency cache not found at {cache_root}; run :app:testDebugUnitTest first"
        )
    wanted = [
        ("org.jetbrains.kotlin", "kotlin-stdlib"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm"),
        ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
        ("org.jetbrains", "annotations"),
    ]
    jars: list[Path] = []
    for group, artifact in wanted:
        art_dir = cache_root / group / artifact
        if not art_dir.is_dir():
            if artifact == "annotations":
                continue
            raise RuntimeError(f"Required Gradle cached artifact missing: {art_dir}")
        found = [
            p
            for p in art_dir.rglob(f"{artifact}-*.jar")
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))
        ]
        if not found:
            if artifact == "annotations":
                continue
            raise RuntimeError(f"No runtime JAR found for {group}:{artifact} under {art_dir}")
        found.sort(key=lambda p: _version_tuple(p.name), reverse=True)
        jars.append(found[0])
    return jars


_JVM_DRIVER_JAVA = r"""
import dev.silo.app.BuildConfig;
import dev.silo.app.data.AppInventory;
import dev.silo.app.data.AppLanguagePref;
import dev.silo.app.data.BlocklistParser;
import dev.silo.app.data.OfflineRiskResult;
import dev.silo.app.ipc.*;
import dev.silo.app.ui.i18n.SiloStrings;
import dev.silo.app.ui.i18n.SiloStringsKt;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.serialization.DeserializationStrategy;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class SiloJvmContractDriver {

    private static String esc(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String jsonList(Collection<String> items) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String it : items) {
            if (!first) sb.append(",");
            first = false;
            sb.append(esc(it));
        }
        sb.append("]");
        return sb.toString();
    }

    private static SiloClient instantiateClient() throws Exception {
        Class<SiloClient> cls = SiloClient.class;
        try {
            Constructor<SiloClient> c0 = cls.getConstructor();
            return c0.newInstance();
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Constructor<SiloClient> c1 = cls.getConstructor(String.class);
            return c1.newInstance("/data/adb/modules/silo/bin/silod");
        } catch (NoSuchMethodException ignored) {
        }
        // Fallback: Kotlin synthetic $default constructor (..., int mask, DefaultConstructorMarker)
        for (Constructor<?> ctor : cls.getConstructors()) {
            Class<?>[] pt = ctor.getParameterTypes();
            if (pt.length >= 3
                && pt[pt.length - 1].getName().equals("kotlin.jvm.internal.DefaultConstructorMarker")
                && pt[pt.length - 2] == int.class) {
                Object[] cargs = new Object[pt.length];
                for (int i = 0; i < pt.length - 2; i++) {
                    if (pt[i] == boolean.class) cargs[i] = Boolean.FALSE;
                    else if (pt[i] == int.class) cargs[i] = 0;
                    else if (pt[i] == long.class) cargs[i] = 0L;
                    else cargs[i] = null;
                }
                cargs[pt.length - 2] = -1; // use defaults for all parameters
                cargs[pt.length - 1] = null;
                return (SiloClient) ctor.newInstance(cargs);
            }
        }
        throw new NoSuchMethodException("No compatible SiloClient constructor found");
    }

    private static Method findCallRawMethod() throws Exception {
        for (Method m : SiloClient.class.getMethods()) {
            String n = m.getName();
            if ((n.equals("callRaw") || n.startsWith("callRaw-")) && m.getParameterCount() == 4) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt[0] == String.class && pt[2] == long.class && Continuation.class.isAssignableFrom(pt[3])) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodException("SiloClient.callRaw(String, JsonElement, long, Continuation) not found");
    }

    private static Method findDisconnectMethod() throws Exception {
        for (Method m : SiloClient.class.getMethods()) {
            if (m.getName().equals("disconnect") && m.getParameterCount() == 1
                && Continuation.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return m;
            }
        }
        for (Method m : SiloClient.class.getMethods()) {
            if (m.getName().equals("disconnect") && m.getParameterCount() == 0) {
                return m;
            }
        }
        throw new NoSuchMethodException("SiloClient.disconnect() or disconnect(Continuation) not found");
    }

    private static Object invokeCallRaw(SiloClient client, String cmd, JsonElement params, long timeoutMs) throws Throwable {
        Method callRaw = findCallRawMethod();
        return BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            new Function2<CoroutineScope, Continuation<? super Object>, Object>() {
                @Override
                public Object invoke(CoroutineScope scope, Continuation<? super Object> cont) {
                    try {
                        return callRaw.invoke(client, cmd, params, timeoutMs, cont);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        );
    }

    private static void invokeDisconnect(SiloClient client) throws Throwable {
        Method disc = findDisconnectMethod();
        if (disc.getParameterCount() == 0) {
            disc.invoke(client);
            return;
        }
        BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            new Function2<CoroutineScope, Continuation<? super Object>, Object>() {
                @Override
                public Object invoke(CoroutineScope scope, Continuation<? super Object> cont) {
                    try {
                        return disc.invoke(client, cont);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        Throwable cause = ite.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        throw new RuntimeException(cause);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        );
    }

    private static Object unboxKotlinResult(Object res) throws Exception {
        if (res == null) return null;
        if (res.getClass().getName().equals("kotlin.Result")) {
            Method unbox = res.getClass().getMethod("unbox-impl");
            return unbox.invoke(res);
        }
        return res;
    }

    private static Throwable extractResultError(Object res) throws Exception {
        Object inner = unboxKotlinResult(res);
        if (inner == null) return null;
        if (inner.getClass().getName().equals("kotlin.Result$Failure")) {
            java.lang.reflect.Field f = inner.getClass().getField("exception");
            return (Throwable) f.get(inner);
        }
        return null;
    }

    private static JsonElement extractResultValue(Object res) throws Exception {
        Object inner = unboxKotlinResult(res);
        if (inner instanceof JsonElement) return (JsonElement) inner;
        return null;
    }

    private static Object requireGetter(Object obj, String getterName) throws Exception {
        try {
            Method m = obj.getClass().getMethod(getterName);
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                "Missing frozen Interface Contract getter " + obj.getClass().getSimpleName() + "." + getterName + "()"
            );
        }
    }

    private static String getOptionalStringGetter(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = obj.getClass().getMethod(name);
                Object v = m.invoke(obj);
                return v == null ? null : v.toString();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String collectAllStringGetters(Object obj) {
        StringBuilder sb = new StringBuilder();
        sb.append(obj.toString());
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() == 0
                && m.getReturnType() == String.class
                && Modifier.isPublic(m.getModifiers())
                && !Modifier.isStatic(m.getModifiers())
                && m.getDeclaringClass() != Object.class) {
                try {
                    Object v = m.invoke(obj);
                    if (v instanceof String) {
                        sb.append(" ").append((String) v);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> invokeParseLines(String rawText, int maxEntries) throws Exception {
        Object inst = BlocklistParser.INSTANCE;
        try {
            Method m = inst.getClass().getMethod("parseLines", String.class, int.class);
            return (List<String>) m.invoke(inst, rawText, maxEntries);
        } catch (NoSuchMethodException ignored) {
        }
        for (Method m : inst.getClass().getMethods()) {
            if (m.getName().equals("parseLines$default") && m.getParameterCount() >= 4) {
                Class<?>[] pt = m.getParameterTypes();
                Object[] pargs = new Object[pt.length];
                pargs[0] = inst;
                pargs[1] = rawText;
                pargs[2] = maxEntries;
                for (int i = 3; i < pt.length - 2; i++) {
                    if (pt[i] == boolean.class) pargs[i] = Boolean.FALSE;
                    else if (pt[i] == int.class) pargs[i] = 0;
                    else pargs[i] = null;
                }
                int mask = ~((1 << 2) - 1); // params 0 (rawText) and 1 (maxEntries) supplied
                pargs[pt.length - 2] = mask;
                pargs[pt.length - 1] = null;
                return (List<String>) m.invoke(null, pargs);
            }
        }
        Method m1 = inst.getClass().getMethod("parseLines", String.class);
        List<String> all = (List<String>) m1.invoke(inst, rawText);
        return all.size() > maxEntries ? all.subList(0, maxEntries) : all;
    }

    private static OfflineRiskResult invokeEvaluateOfflineRisk(
        List<String> declared,
        List<String> granted,
        List<String> comps
    ) throws Exception {
        Object inst = AppInventory.INSTANCE;
        for (Method m : inst.getClass().getMethods()) {
            if (m.getName().equals("evaluateOfflineRisk") && m.getParameterCount() == 3) {
                Class<?>[] pt = m.getParameterTypes();
                Object a0 = Set.class.isAssignableFrom(pt[0]) ? new LinkedHashSet<>(declared) : declared;
                Object a1 = Set.class.isAssignableFrom(pt[1]) ? new LinkedHashSet<>(granted) : granted;
                Object a2 = Set.class.isAssignableFrom(pt[2]) ? new LinkedHashSet<>(comps) : comps;
                return (OfflineRiskResult) m.invoke(inst, a0, a1, a2);
            }
        }
        for (Method m : inst.getClass().getMethods()) {
            if (m.getName().equals("evaluateOfflineRisk$default") && m.getParameterCount() >= 6) {
                Class<?>[] pt = m.getParameterTypes();
                Object[] pargs = new Object[pt.length];
                pargs[0] = inst;
                pargs[1] = Set.class.isAssignableFrom(pt[1]) ? new LinkedHashSet<>(declared) : declared;
                pargs[2] = Set.class.isAssignableFrom(pt[2]) ? new LinkedHashSet<>(granted) : granted;
                pargs[3] = Set.class.isAssignableFrom(pt[3]) ? new LinkedHashSet<>(comps) : comps;
                for (int i = 4; i < pt.length - 2; i++) {
                    if (pt[i] == boolean.class) pargs[i] = Boolean.FALSE;
                    else if (pt[i] == int.class) pargs[i] = 0;
                    else pargs[i] = null;
                }
                int mask = ~((1 << 3) - 1); // first 3 args supplied, rest use Kotlin defaults
                pargs[pt.length - 2] = mask;
                pargs[pt.length - 1] = null;
                return (OfflineRiskResult) m.invoke(null, pargs);
            }
        }
        throw new NoSuchMethodException("AppInventory.evaluateOfflineRisk(3 args) not found");
    }

    private static void decodeAndValidateModel(Json json, String modelName, String rawJson) throws Exception {
        if (modelName.equals("DaemonStatus")) {
            DaemonStatus st = json.decodeFromString(DaemonStatus.Companion.serializer(), rawJson);
            Object panic = requireGetter(st, "getPanicLockdownActive");
            System.out.println("{\"model\":\"DaemonStatus\",\"version\":" + esc(st.getVersion())
                + ",\"firewall_mode\":" + esc(st.getFirewallMode())
                + ",\"panic_lockdown_active\":" + panic + "}");
        } else if (modelName.equals("SiloConfig")) {
            SiloConfig cfg = json.decodeFromString(SiloConfig.Companion.serializer(), rawJson);
            Object panic = requireGetter(cfg.getFirewall(), "getPanicLockdownActive");
            System.out.println("{\"model\":\"SiloConfig\",\"schema_version\":" + cfg.getSchemaVersion()
                + ",\"fw_mode\":" + esc(cfg.getFirewall().getMode().name())
                + ",\"panic_lockdown_active\":" + panic
                + ",\"rules_count\":" + cfg.getFirewall().getRules().size()
                + ",\"freezer_entries\":" + cfg.getFreezer().getEntries().size()
                + ",\"guards_count\":" + cfg.getScopes().getGuards().size() + "}");
        } else if (modelName.equals("AppLivePermissions")) {
            AppLivePermissions lp = json.decodeFromString(AppLivePermissions.Companion.serializer(), rawJson);
            StringBuilder sb = new StringBuilder("{\"model\":\"AppLivePermissions\",\"package_name\":");
            sb.append(esc(lp.getPackageName())).append(",\"categories\":[");
            boolean first = true;
            for (CategoryPermLiveState c : lp.getCategories()) {
                if (!first) sb.append(",");
                first = false;
                Object rg = requireGetter(c, "getRuntimeGranted");
                Object hrp = requireGetter(c, "getHasRuntimePerms");
                Object sf = requireGetter(c, "getSupportsForeground");
                Object sysf = requireGetter(c, "getSystemFixed");
                String lastAcc = getOptionalStringGetter(c, "getLastAccess", "getLastAccessAgo");
                sb.append("{\"category\":").append(esc(c.getCategory()))
                  .append(",\"appops_mode\":").append(esc(c.getAppopsMode()))
                  .append(",\"last_access\":").append(esc(lastAcc))
                  .append(",\"running_now\":").append(c.getRunningNow())
                  .append(",\"runtime_granted\":").append(rg == null ? "null" : rg.toString())
                  .append(",\"has_runtime_perms\":").append(hrp)
                  .append(",\"supports_foreground\":").append(sf)
                  .append(",\"system_fixed\":").append(sysf)
                  .append("}");
            }
            sb.append("]}");
            System.out.println(sb.toString());
        } else if (modelName.equals("ScopesConfig")) {
            ScopesConfig sc = json.decodeFromString(ScopesConfig.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"ScopesConfig\",\"guards_count\":" + sc.getGuards().size()
                + ",\"auto_clear_clipboard\":" + sc.getAutoClearClipboard() + "}");
        } else if (modelName.equals("PrivacyAccessEventList")) {
            DeserializationStrategy<List<PrivacyAccessEvent>> ser =
                BuiltinSerializersKt.ListSerializer(PrivacyAccessEvent.Companion.serializer());
            List<PrivacyAccessEvent> evs = json.decodeFromString(ser, rawJson);
            System.out.println("{\"model\":\"PrivacyAccessEventList\",\"count\":" + evs.size() + "}");
        } else if (modelName.equals("ActiveConnectionList")) {
            DeserializationStrategy<List<ActiveConnection>> ser =
                BuiltinSerializersKt.ListSerializer(ActiveConnection.Companion.serializer());
            List<ActiveConnection> conns = json.decodeFromString(ser, rawJson);
            System.out.println("{\"model\":\"ActiveConnectionList\",\"count\":" + conns.size() + "}");
        } else if (modelName.equals("TelemetrySnapshot")) {
            TelemetrySnapshot ts = json.decodeFromString(TelemetrySnapshot.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"TelemetrySnapshot\",\"total_blocked\":" + ts.getTotalBlocked() + "}");
        } else if (modelName.equals("FreezerStatus")) {
            FreezerStatus fs = json.decodeFromString(FreezerStatus.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"FreezerStatus\",\"enabled\":" + fs.getEnabled()
                + ",\"cgroup_v2_available\":" + fs.getCgroupV2Available() + "}");
        } else if (modelName.equals("PrivateDnsState")) {
            PrivateDnsState dns = json.decodeFromString(PrivateDnsState.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"PrivateDnsState\",\"mode\":" + esc(dns.getMode())
                + ",\"specifier\":" + esc(dns.getSpecifier()) + "}");
        } else if (modelName.equals("RadioPrivacyState")) {
            RadioPrivacyState rp = json.decodeFromString(RadioPrivacyState.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"RadioPrivacyState\",\"enhanced_mac\":" + rp.getEnhancedMacRandomization() + "}");
        } else if (modelName.equals("SensorPrivacyState")) {
            SensorPrivacyState sp = json.decodeFromString(SensorPrivacyState.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"SensorPrivacyState\",\"mic_muted\":" + sp.getMicMuted()
                + ",\"camera_muted\":" + sp.getCameraMuted() + "}");
        } else if (modelName.equals("AutomationConfig")) {
            AutomationConfig ac = json.decodeFromString(AutomationConfig.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"AutomationConfig\",\"sensors_off_on_lock\":" + ac.getSensorsOffOnLock() + "}");
        } else if (modelName.equals("HardeningStatus")) {
            HardeningStatus hs = json.decodeFromString(HardeningStatus.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"HardeningStatus\",\"enabled\":" + hs.getEnabled()
                + ",\"items\":" + hs.getItems().size() + "}");
        } else if (modelName.equals("SentinelReport")) {
            SentinelReport sr = json.decodeFromString(SentinelReport.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"SentinelReport\",\"score\":" + sr.getScore()
                + ",\"findings\":" + sr.getFindings().size() + "}");
        } else if (modelName.equals("LiveProofReport")) {
            LiveProofReport lp = json.decodeFromString(LiveProofReport.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"LiveProofReport\",\"items\":" + lp.getItems().size()
                + ",\"ipv4_hook\":" + lp.getIpv4HookAttached() + "}");
        } else if (modelName.equals("PanicLockdownReport")) {
            PanicLockdownReport pr = json.decodeFromString(PanicLockdownReport.Companion.serializer(), rawJson);
            System.out.println("{\"model\":\"PanicLockdownReport\",\"firewall_locked\":" + pr.getFirewallLocked()
                + ",\"mic_muted\":" + pr.getMicMuted() + ",\"camera_muted\":" + pr.getCameraMuted() + "}");
        } else {
            throw new IllegalArgumentException("Unknown modelName: " + modelName);
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            System.err.println("Usage: SiloJvmContractDriver <action> [args...]");
            System.exit(2);
        }
        String action = args[0];

        if (action.equals("build_config")) {
            System.out.println("{\"version_name\":" + esc(BuildConfig.VERSION_NAME)
                + ",\"version_code\":" + BuildConfig.VERSION_CODE
                + ",\"application_id\":" + esc(BuildConfig.APPLICATION_ID) + "}");
            return;
        }

        if (action.equals("blocklist_parser")) {
            String rawText = Files.readString(Paths.get(args[1]), StandardCharsets.UTF_8);
            int maxEntries = Integer.parseInt(args[2]);
            List<String> parsed = invokeParseLines(rawText, maxEntries);
            System.out.println("{\"entries\":" + jsonList(parsed) + ",\"count\":" + parsed.size() + "}");
            return;
        }

        if (action.equals("app_inventory_uid")) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 1; i < args.length; i++) {
                if (i > 1) sb.append(",");
                int uid = Integer.parseInt(args[i]);
                boolean prot = AppInventory.INSTANCE.isProtectedUid(uid);
                sb.append(esc(String.valueOf(uid))).append(":").append(prot);
            }
            sb.append("}");
            System.out.println(sb.toString());
            return;
        }

        if (action.equals("app_inventory_risk")) {
            List<String> declared = args[1].isEmpty() ? Collections.emptyList() : Arrays.asList(args[1].split(","));
            List<String> granted = args[2].isEmpty() ? Collections.emptyList() : Arrays.asList(args[2].split(","));
            List<String> comps = args[3].isEmpty() ? Collections.emptyList() : Arrays.asList(args[3].split(","));
            OfflineRiskResult r = invokeEvaluateOfflineRisk(declared, granted, comps);
            System.out.println("{\"level\":" + esc(r.getLevel().name())
                + ",\"granted\":" + jsonList(r.getGrantedCategories())
                + ",\"declared\":" + jsonList(r.getDeclaredCategories())
                + ",\"trackers\":" + jsonList(r.getDetectedTrackers()) + "}");
            return;
        }

        if (action.equals("perm_guard_total")) {
            List<String> auto = args[1].isEmpty() ? Collections.emptyList() : Arrays.asList(args[1].split(","));
            List<String> locked = args[2].isEmpty() ? Collections.emptyList() : Arrays.asList(args[2].split(","));
            AppPermGuard g = new AppPermGuard(auto, locked);
            System.out.println("{\"total_count\":" + g.totalCount() + "}");
            return;
        }

        if (action.equals("silo_strings")) {
            AppLanguagePref pref = AppLanguagePref.valueOf(args[1]);
            SiloStrings s = SiloStringsKt.resolveStrings(pref);
            String[] cats = new String[] {
                "camera", "microphone", "location", "background_location",
                "contacts", "media", "sms_calllog", "clipboard", "sensors", "background",
                "unknown_future_category"
            };
            StringBuilder sb = new StringBuilder("{\"is_indonesian\":" + s.isIndonesian()
                + ",\"tab_home\":" + esc(s.getTabHome())
                + ",\"tab_apps\":" + esc(s.getTabApps())
                + ",\"tab_traffic\":" + esc(s.getTabTraffic())
                + ",\"tab_system\":" + esc(s.getTabSystem())
                + ",\"labels\":{");
            for (int i = 0; i < cats.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(esc(cats[i])).append(":").append(esc(s.categoryLabel(cats[i])));
            }
            sb.append("},\"descs\":{");
            for (int i = 0; i < cats.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(esc(cats[i])).append(":").append(esc(s.categoryDesc(cats[i])));
            }
            sb.append("},\"all_text\":").append(esc(collectAllStringGetters(s))).append("}");
            System.out.println(sb.toString());
            return;
        }

        if (action.equals("decode_model")) {
            String modelName = args[1];
            String rawJson = Files.readString(Paths.get(args[2]), StandardCharsets.UTF_8);
            SiloClient client = instantiateClient();
            decodeAndValidateModel(client.getJson(), modelName, rawJson);
            return;
        }

        if (action.equals("silo_client_call")) {
            // args: silo_client_call <cmd> <params_json_file_or_null> <timeout_ms> <optional_model_name> [second_cmd]
            String cmd = args[1];
            String paramsPath = args[2];
            long timeoutMs = Long.parseLong(args[3]);
            String modelName = args.length > 4 ? args[4] : "";
            String secondCmd = args.length > 5 ? args[5] : "";

            SiloClient client = instantiateClient();
            Json json = client.getJson();
            JsonElement paramsElem = null;
            if (!paramsPath.equals("null")) {
                String ptxt = Files.readString(Paths.get(paramsPath), StandardCharsets.UTF_8);
                paramsElem = json.parseToJsonElement(ptxt);
            }
            long t0 = System.nanoTime();
            Object res = invokeCallRaw(client, cmd, paramsElem, timeoutMs);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            Throwable err = extractResultError(res);

            String secondOut = "null";
            if (!secondCmd.isEmpty()) {
                Object res2 = invokeCallRaw(client, secondCmd, null, timeoutMs);
                Throwable err2 = extractResultError(res2);
                if (err2 != null) {
                    secondOut = "{\"ok\":false,\"error\":" + esc(err2.getMessage()) + ",\"class\":" + esc(err2.getClass().getName()) + "}";
                } else {
                    JsonElement v2 = extractResultValue(res2);
                    secondOut = "{\"ok\":true,\"data\":" + (v2 == null ? "null" : v2.toString()) + "}";
                }
            }

            try {
                if (err != null) {
                    System.out.println("{\"ok\":false,\"elapsed_ms\":" + elapsedMs
                        + ",\"error_class\":" + esc(err.getClass().getName())
                        + ",\"error\":" + esc(err.getMessage())
                        + ",\"second\":" + secondOut + "}");
                } else {
                    JsonElement val = extractResultValue(res);
                    String rawVal = val == null ? "null" : val.toString();
                    if (!modelName.isEmpty() && val != null) {
                        // Validate deserialization directly into Kotlin model
                        decodeAndValidateModel(json, modelName, rawVal);
                        return;
                    }
                    System.out.println("{\"ok\":true,\"elapsed_ms\":" + elapsedMs
                        + ",\"data\":" + rawVal
                        + ",\"second\":" + secondOut + "}");
                }
            } finally {
                invokeDisconnect(client);
            }
            return;
        }

        throw new IllegalArgumentException("Unknown action: " + action);
    }
}
"""


def _ensure_jvm_driver_compiled(env: dict[str, str], cp_parts: list[str]) -> Path:
    """Compile SiloJvmContractDriver once per session into an atexit-cleaned temp dir."""
    global _JVM_DRIVER_DIR, _JVM_DRIVER_CP_KEY
    compile_cp = os.pathsep.join(cp_parts)
    with _JVM_DRIVER_LOCK:
        if (
            _JVM_DRIVER_DIR is not None
            and (_JVM_DRIVER_DIR / "SiloJvmContractDriver.class").is_file()
            and _JVM_DRIVER_CP_KEY == compile_cp
        ):
            return _JVM_DRIVER_DIR

        if _JVM_DRIVER_DIR is not None and _JVM_DRIVER_DIR.exists():
            shutil.rmtree(_JVM_DRIVER_DIR, ignore_errors=True)
            _JVM_DRIVER_DIR = None
            _JVM_DRIVER_CP_KEY = None

        work_dir = Path(tempfile.mkdtemp(prefix="silo_e2e_jvm_"))
        javac_bin = str(Path(env["JAVA_HOME"]) / "bin" / "javac")
        src = work_dir / "SiloJvmContractDriver.java"
        src.write_text(_JVM_DRIVER_JAVA, encoding="utf-8")
        comp = subprocess.run(
            [javac_bin, "-cp", compile_cp, "-d", str(work_dir), str(src)],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        if comp.returncode != 0:
            shutil.rmtree(work_dir, ignore_errors=True)
            raise AssertionError(
                f"Failed to compile JVM contract driver against app/ bytecode (rc={comp.returncode}):\n"
                f"STDERR:\n{comp.stderr}\nSTDOUT:\n{comp.stdout}"
            )
        _JVM_DRIVER_DIR = work_dir
        _JVM_DRIVER_CP_KEY = compile_cp
        return work_dir


def run_jvm_contract(
    args: list[str],
    *,
    extra_path_dir: Path | None = None,
    extra_env: dict[str, str] | None = None,
    timeout: float = 45.0,
) -> dict[str, Any]:
    """Compile (cached per session) and run the standalone JDK 21 JVM contract driver against compiled `app/` Kotlin classes."""
    ensure_unit_tests_run()
    env = android_env()
    java_bin = str(Path(env["JAVA_HOME"]) / "bin" / "java")

    cp_parts: list[str] = [
        str(KOTLIN_DEBUG_CLASSES),
        str(find_android_jar()),
    ]
    if JAVAC_DEBUG_CLASSES.is_dir():
        cp_parts.append(str(JAVAC_DEBUG_CLASSES))
    cp_parts.extend(str(j) for j in _discover_gradle_runtime_jars())

    driver_dir = _ensure_jvm_driver_compiled(env, cp_parts)

    run_env = env.copy()
    if extra_path_dir is not None:
        run_env["PATH"] = f"{extra_path_dir}{os.pathsep}{run_env.get('PATH', '')}"
    if extra_env:
        run_env.update(extra_env)

    run_cp = os.pathsep.join([str(driver_dir), *cp_parts])
    proc = subprocess.run(
        [java_bin, "-cp", run_cp, "SiloJvmContractDriver", *args],
        env=run_env,
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if proc.returncode != 0:
        raise AssertionError(
            f"JVM contract driver failed (args={args}, rc={proc.returncode}):\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )
    lines = [ln.strip() for ln in proc.stdout.splitlines() if ln.strip()]
    if not lines:
        raise AssertionError(
            f"JVM contract driver produced empty stdout (args={args}, stderr={proc.stderr!r})"
        )
    return json.loads(lines[-1])


def install_su_bridge_wrapper(
    bin_dir: Path,
    *,
    silod_bin: Path,
    socket_path: Path,
) -> Path:
    """Create a sandboxed `su` wrapper in `bin_dir` redirecting `SiloClient` to real `silod --bridge`."""
    bin_dir.mkdir(parents=True, exist_ok=True)
    su_path = bin_dir / "su"
    su_path.write_text(
        "#!/bin/sh\n"
        f'exec "{silod_bin}" --bridge --socket "{socket_path}"\n',
        encoding="utf-8",
    )
    su_path.chmod(0o755)
    return su_path


def install_custom_su_shim(bin_dir: Path, script_body: str) -> Path:
    """Create a programmable fault-injection `su` shim in `bin_dir` for `SiloClient` negative tests."""
    bin_dir.mkdir(parents=True, exist_ok=True)
    su_path = bin_dir / "su"
    su_path.write_text(script_body, encoding="utf-8")
    su_path.chmod(0o755)
    return su_path
