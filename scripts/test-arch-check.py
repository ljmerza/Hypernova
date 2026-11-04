#!/usr/bin/env python3
"""Self-tests for scripts/arch-check.py: the real tree passes, and each kind of breach, planted in a temporary copy of
the sources, fails on the rule it breaks and names the file. What only looks like a breach (a comment, a string, a nested
name) passes.

    scripts/test-arch-check.py            about 30 seconds
    scripts/test-arch-check.py --gradle   the classpath's half that needs Gradle, in a copy of the build files: about a minute
"""
import os
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CHECK = REPO / "scripts/arch-check.py"
PKG = "com/prudnikweb/hypernova"
UI = "com.prudnikweb.hypernova.ui"
SHAREDUI = f"android/sharedui/src/commonMain/kotlin/{PKG}/ui"
SHARED = f"android/shared/src/commonMain/kotlin/{PKG}/data"
APP = f"android/app/src/main/java/{PKG}/ui"
DESK = f"android/desktop/src/main/kotlin/{PKG}/desktop/ui"


def in_ui(feature: str, body: str) -> str:
    return f"package {UI}.{feature}\n\n{body}\n"


SHARED_DATA = "package com.prudnikweb.hypernova.data\n\n"
# name, the rule it must break, then {file: text}: a file that exists is added to; None deletes it.
BREACHES = [
    ("sharedui: a source set beside commonMain", "sharedui-common-only", {"android/sharedui/src/androidMain/kotlin/X.kt": "package x\n"}),
    ("sharedui: import android.*", "sharedui-common-only", {f"{SHAREDUI}/chat/P1.kt": in_ui("chat", "import android.graphics.Paint")}),
    ("sharedui: android.* by its full name", "sharedui-common-only", {f"{SHAREDUI}/chat/P2.kt": in_ui("chat", "val sdk = android.os.Build.VERSION.SDK_INT")}),
    ("sharedui: androidx.activity", "sharedui-common-only", {f"{SHAREDUI}/chat/P3.kt": in_ui("chat", "import androidx.activity.compose.BackHandler")}),
    ("sharedui: java.awt", "sharedui-common-only", {f"{SHAREDUI}/chat/P4.kt": in_ui("chat", "import java.awt.Desktop")}),
    ("sharedui: javax.swing", "sharedui-common-only", {f"{SHAREDUI}/chat/P5.kt": in_ui("chat", "import javax.swing.SwingUtilities")}),
    ("sharedui: R.string", "sharedui-common-only", {f"{SHAREDUI}/chat/P6.kt": in_ui("chat", "val w = R.string.you")}),
    ("sharedui: import of R", "sharedui-common-only", {f"{SHAREDUI}/chat/P7.kt": in_ui("chat", "import com.prudnikweb.hypernova.R")}),
    ("sharedui: expect", "sharedui-common-only", {f"{SHAREDUI}/chat/P8.kt": in_ui("chat", "expect fun now(): Long")}),
    ("sharedui: actual", "sharedui-common-only", {f"{SHAREDUI}/chat/P9.kt": in_ui("chat", "internal actual val here: Int = 1")}),
    ("layers: theme imports a feature", "sharedui-layers", {f"{SHAREDUI}/theme/L1.kt": in_ui("theme", f"import {UI}.chat.AgentRow")}),
    ("layers: fx imports a feature", "sharedui-layers", {f"{SHAREDUI}/fx/L2.kt": in_ui("fx", f"import {UI}.settings.Foo")}),
    ("layers: components names a feature in full", "sharedui-layers", {f"{SHAREDUI}/components/L3.kt": in_ui("components", f"val x = {UI}.chat.something()")}),
    ("layers: platform imports all of a feature", "sharedui-layers", {f"{SHAREDUI}/platform/L4.kt": in_ui("platform", f"import {UI}.chat.*")}),
    ("shared: import android.*", "shared-no-ui", {f"{SHARED}/S1.kt": SHARED_DATA + "import android.content.Context\n"}),
    ("shared: android.* in its tests too", "shared-no-ui", {f"android/shared/src/commonTest/kotlin/{PKG}/S2.kt": "package x\nimport android.os.Looper\n"}),
    ("shared: R", "shared-no-ui", {f"{SHARED}/S3.kt": SHARED_DATA + "val w = R.string.you\n"}),
    ("shared: a ui import", "shared-no-ui", {f"{SHARED}/S4.kt": SHARED_DATA + f"import {UI}.theme.HN\n"}),
    ("shared: Compose drawing", "shared-no-ui", {f"{SHARED}/S5.kt": SHARED_DATA + "import androidx.compose.ui.Modifier\n"}),
    ("shared: foundation", "shared-no-ui", {f"{SHARED}/S6.kt": SHARED_DATA + "import androidx.compose.foundation.layout.Box\n"}),
    ("shared: a new composable", "shared-no-ui", {f"{SHARED}/S7.kt": SHARED_DATA + "import androidx.compose.runtime.Composable\n"}),
    ("desktop: its own object HN", "desktop-no-kit", {f"{DESK}/K1.kt": "package x\n\nobject HN\n"}),
    ("desktop: its own Type", "desktop-no-kit", {f"{DESK}/K2.kt": "package x\n\nobject Type\n"}),
    ("desktop: a private HText", "desktop-no-kit", {f"{DESK}/K3.kt": "package x\n\n@Composable\nprivate fun HText() {}\n"}),
    ("desktop: Pill", "desktop-no-kit", {f"{DESK}/K4.kt": "package x\n\n@Composable\nfun Pill(a: Int) {}\n"}),
    ("desktop: PillKind", "desktop-no-kit", {f"{DESK}/K5.kt": "package x\n\nenum class PillKind { A }\n"}),
    ("desktop: Seam, outside ui/", "desktop-no-kit", {f"android/desktop/src/main/kotlin/{PKG}/desktop/K6.kt": "package x\n\n@Composable\nfun Seam() {}\n"}),
    ("desktop: GlassCard in its tests", "desktop-no-kit", {f"android/desktop/src/test/kotlin/{PKG}/desktop/K7.kt": "package x\n\nfun GlassCard() {}\n"}),
    ("desktop: an extension named as the kit's", "desktop-no-kit", {f"{DESK}/K8.kt": "package x\n\nfun String.routeWord(): String = this\n"}),
    ("desktop: a name just added to the kit", "desktop-no-kit", {f"{SHAREDUI}/components/K9.kt": in_ui("components", "@Composable\nfun BrandNewKitPart() {}"), f"{DESK}/K9b.kt": "package x\n\nfun BrandNewKitPart() {}\n"}),
    ("HN: an object in the phone's code", "one-hn", {f"{APP}/H1.kt": "package x\n\nobject HN\n"}),
    ("HN: nested in :shared", "one-hn", {f"{SHARED}/H2.kt": SHARED_DATA + "class A { object HN }\n"}),
    ("same name: a composable in the phone's ui/ and sharedui", "phone-sharedui-same-name", {f"{APP}/home/D1.kt": "package x\n\n@Composable\nprivate fun Pill(z: Int) {}\n"}),
    ("same name: a class in the phone's ui/ and sharedui", "phone-sharedui-same-name", {f"{APP}/home/D2.kt": "package x\n\nprivate data class PillKind(val z: Int)\n"}),
    ("same name: a composable in the desktop's ui/ and sharedui", "desktop-sharedui-same-name", {f"{DESK}/D3.kt": "package x\n\n@Composable\nprivate fun AgentRow() {}\n"}),
    ("same name: a composable in the phone's ui/ and the desktop's", "phone-desktop-same-name", {f"{DESK}/D4.kt": "package x\n\n@Composable\nprivate fun UsageStrip() {}\n", f"{APP}/session/UsageStripProbe.kt": "package x\n\n@Composable\nfun UsageStrip() {}\n"}),
    ("same name: a new one in a file that's on the allowlist", "phone-desktop-same-name", {f"{DESK}/AtlasPlate.kt": "\n@Composable\nprivate fun TopBar() {}\n", f"{APP}/session/TopBarProbe.kt": "package x\n\n@Composable\nfun TopBar() {}\n"}),
    ("same file and package in two modules", "same-file-same-package", {f"{APP}/theme/Tokens.kt": f"package {UI}.theme\n"}),
    ("same file and package in the phone and :shared", "same-file-same-package", {f"android/app/src/main/java/{PKG}/data/Str.kt": "package com.prudnikweb.hypernova.data\n"}),
    ("classpath: JetBrains' foundation", "app-classpath", {"android/app/classpath-debug.txt": "org.jetbrains.compose.foundation:foundation:1.12.1\n"}),
    ("classpath: JetBrains' foundation-layout", "app-classpath", {"android/app/classpath-release.txt": "org.jetbrains.compose.foundation:foundation-layout-android:1.12.1\n"}),
    ("classpath: JetBrains' animation", "app-classpath", {"android/app/classpath-release.txt": "org.jetbrains.compose.animation:animation:1.12.1\n"}),
    ("classpath: JetBrains' animation-android", "app-classpath", {"android/app/classpath-debug.txt": "org.jetbrains.compose.animation:animation-android:1.12.1\n"}),
    ("classpath: a list gone", "app-classpath", {"android/app/classpath-debug.txt": None}),
    ("previews: the ignore line gone", "previews-ignored", {".gitignore": None}),
    ("biometric: the system's prompt", "no-biometric", {f"{APP}/B1.kt": "package x\n\nimport android.hardware.biometrics.BiometricPrompt\n"}),
    ("biometric: OwnerCheck come back, in sharedui", "no-biometric", {f"{SHAREDUI}/chat/B2.kt": in_ui("chat", "object OwnerCheck")}),
    ("biometric: AndroidX's, in the phone's tests", "no-biometric", {f"android/app/src/test/java/{PKG}/B3.kt": "package x\n\nimport androidx.biometric.BiometricPrompt\n"}),
    ("biometric: a key that waits for the lock", "no-biometric", {f"{APP}/B4.kt": "package x\n\nfun b4(s: Spec) = s.setUserAuthenticationRequired(true)\n"}),
    ("biometric: the manifest's permission", "no-biometric", {"android/app/src/main/AndroidManifest.xml": '<uses-permission android:name="android.permission.USE_BIOMETRIC" />\n'}),
    ("biometric: the dependency", "no-biometric", {"android/app/build.gradle.kts": 'dependencies { implementation("androidx.biometric:biometric:1.1.0") }\n'}),
    ("biometric: on the classpath", "no-biometric", {"android/app/classpath-release.txt": "androidx.biometric:biometric:1.1.0\n"}),
    ("allowlist: the breach is gone, and the entry isn't", "allowlist", {f"{DESK}/HistoryList.kt": None}),
    ("allowlist: its row in LATER is gone", "allowlist", {"docs/LATER.md": None}),
]

# What only looks like a breach. Each is added on its own and must pass.
NOT_BREACHES = [
    ("a comment, a KDoc and a nested one", {f"{SHAREDUI}/chat/N1.kt": in_ui("chat", '// import android.os.Build\n/** java.awt.Desktop, R.string.you /* expect fun x() */ */\nval n1 = 1')}),
    ("a string, and a raw string with code in it", {f"{SHAREDUI}/chat/N2.kt": in_ui("chat", 'val a = "android.os.Build R.string.x"\nval b = """\nimport android.os.Build\nobject HN\n"""\nval c = "${a.length} android.os"')}),
    ("a quote in a character", {f"{SHAREDUI}/chat/N3.kt": in_ui("chat", "val q = '\"'\nval q2 = '\\''\nfun n3(): Int = 1")}),
    ("a name of the kit inside a class or a function", {f"{DESK}/N4.kt": 'package x\n\nclass N4 {\n    fun Pill() {}\n    object Type\n}\n\nfun n4() {\n    fun HText() {}\n}\n'}),
    ("a parameter or a local called expect or actual", {f"{SHAREDUI}/chat/N5.kt": in_ui("chat", "fun n5(expect: Int, actual: Int): Int {\n    val want = expect\n    return actual + want\n}")}),
    ("a composable and a class of one name aren't copies", {f"{APP}/home/N6.kt": "package x\n\nclass ConnectionChip\n"}),
    ("a screen on each shell is each one's own layout", {f"{DESK}/N7.kt": "package x\n\n@Composable\nfun StatisticsScreen() {}\n", f"{APP}/stats/N7b.kt": "package x\n\n@Composable\nfun StatisticsScreen() {}\n"}),
    ("a feature importing the kit", {f"{SHAREDUI}/chat/N8.kt": in_ui("chat", f"import {UI}.theme.HN\nimport {UI}.components.Pill")}),
    ("JetBrains' animation-core, there through Haze", {"android/app/classpath-debug.txt": "org.jetbrains.compose.animation:animation-core:1.12.1\n"}),
    ("state in :shared", {f"{SHARED}/N10.kt": SHARED_DATA + "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.Immutable\n"}),
    ("the banned names in a comment and a string", {f"{APP}/N11.kt": 'package x\n\n// OwnerCheck and BiometricPrompt have gone.\nval n11 = "USE_BIOMETRIC OwnerCheck"\n'}),
    ("a notification's button that needs unlocking", {f"{APP}/N12.kt": "package x\n\nfun n12(b: B) = b.setAuthenticationRequired(true)\n"}),
    ("a certificate's fingerprint", {f"{SHARED}/N13.kt": SHARED_DATA + "fun fingerprint(cert: String): String = cert\n"}),
    ("the same file name in another package", {f"android/app/src/main/java/{PKG}/ui/home/Tokens.kt": f"package {UI}.home\n"}),
    ("the same file name in one module's tests and another's", {f"android/app/src/test/java/{PKG}/ui/theme/Tokens.kt": f"package {UI}.theme\n"}),
]


def run(root: Path):
    done = subprocess.run([sys.executable, str(CHECK), "--root", str(root)], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    return done.returncode, done.stdout


def copy_of_the_tree(to: Path) -> None:
    for module in ("app", "shared", "sharedui", "desktop"):
        src = REPO / "android" / module / "src"
        for f in src.rglob("*.kt"):
            target = to / f.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, target)
    for rel in ("android/app/classpath-debug.txt", "android/app/classpath-release.txt", ".gitignore", "docs/LATER.md"):
        target = to / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(REPO / rel, target)


def plant(root: Path, files: dict) -> None:
    for rel, text in files.items():
        f = root / rel
        if text is None:
            f.unlink()
        else:
            f.parent.mkdir(parents=True, exist_ok=True)
            with f.open("a", encoding="utf-8") as out:
                out.write(text)


def try_it(files: dict):
    """A copy of its own for each, so they run side by side and nothing's left of one in the next."""
    with tempfile.TemporaryDirectory(prefix="arch-check-") as tmp:
        root = Path(tmp)
        copy_of_the_tree(root)
        plant(root, files)
        return run(root)


def names_it(said: str, files: dict) -> bool:
    """It said a file it was given, or the source set that one's in."""
    return any(rel in said or str(Path(*Path(rel).parts[:4])) in said for rel in files)


def gradle_half() -> int:
    fails = 0

    def result(ok: bool, name: str, detail: str) -> None:
        nonlocal fails
        print(f"{'ok   ' if ok else 'FAIL '} {name}{'' if ok else ': ' + detail}")
        fails += 0 if ok else 1

    with tempfile.TemporaryDirectory(prefix="arch-check-gradle-") as tmp:
        root = Path(tmp)
        android = root / "android"
        shutil.copytree(REPO / "android", android, ignore=shutil.ignore_patterns("build", ".gradle", ".kotlin", "src"))
        shutil.copy2(REPO / ".gitignore", root / ".gitignore")

        def gradle(task: str):
            done = subprocess.run(["./gradlew", "--no-daemon", "-q", f":app:{task}RuntimeClasspath"], cwd=android, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            return done.returncode, done.stdout

        lists = [android / "app" / f"classpath-{v}.txt" for v in ("debug", "release")]
        kept = [f.read_text(encoding="utf-8") for f in lists]
        build = android / "app/build.gradle.kts"
        script = build.read_text(encoding="utf-8")

        code, said = gradle("check")
        result(code == 0, "the lists match the build", said)

        lists[0].write_text(kept[0].split("\n", 1)[1], encoding="utf-8")
        code, said = gradle("check")
        result(code != 0 and "classpath-debug.txt differs" in said, "a module missing from the debug list fails", said)
        lists[0].write_text(kept[0], encoding="utf-8")

        lists[1].write_text(kept[1] + "com.example:gone:1.0\n", encoding="utf-8")
        code, said = gradle("check")
        result(code != 0 and "classpath-release.txt differs" in said and "- com.example:gone:1.0" in said, "a module too many in the release list fails", said)
        lists[1].write_text(kept[1], encoding="utf-8")

        build.write_text(script.replace("dependencies {\n", "dependencies {\n    implementation(libs.compose.multiplatform.foundation)\n", 1), encoding="utf-8")
        code, said = gradle("check")
        wanted = ("classpath-debug.txt differs", "classpath-release.txt differs", "+ org.jetbrains.compose.foundation:foundation:", "+ org.jetbrains.compose.animation:animation:")
        result(code != 0 and all(w in said for w in wanted), "JetBrains' foundation added to :app fails, on both lists", said)

        code, said = gradle("write")
        result(code == 0, "the lists can be written for it", said)
        code, said = gradle("check")
        result(code == 0, "and then match the build", said)
        code, said = run(root)
        result(code == 1 and "[app-classpath]" in said and "foundation:foundation" in said, "but the rule still refuses foundation in the lists", said)
    print(f"{fails} failed" if fails else "all passed")
    return 1 if fails else 0


def main() -> int:
    if "--gradle" in sys.argv[1:]:
        return gradle_half()
    fails = 0

    def result(ok: bool, name: str, detail: str = "") -> None:
        nonlocal fails
        print(f"{'ok   ' if ok else 'FAIL '} {name}{'' if ok else ': ' + detail}")
        fails += 0 if ok else 1

    code, said = run(REPO)
    result(code == 0, "the real tree passes", said)
    code, said = try_it({})
    result(code == 0, "the copy passes with nothing planted", said)
    with ThreadPoolExecutor(max_workers=os.cpu_count() or 2) as pool:
        for (name, rule, files), (code, said) in zip(BREACHES, pool.map(lambda case: try_it(case[2]), BREACHES)):
            result(code == 1 and f"[{rule}]" in said and names_it(said, files), f"{name} fails on {rule}", f"exit {code}\n{said}")
        for (name, files), (code, said) in zip(NOT_BREACHES, pool.map(lambda case: try_it(case[1]), NOT_BREACHES)):
            result(code == 0, f"{name} passes", f"exit {code}\n{said}")
    print(f"{fails} failed" if fails else f"all passed: {len(BREACHES)} breaches, {len(NOT_BREACHES)} that only look like one")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
