#!/usr/bin/env python3
"""The architecture's rules (CLAUDE.md, "Architecture"; docs/PLATFORMS.md, "The rules' checks").

    scripts/arch-check.py              exit 1 and one line per broken rule: the file, the rule, what to do

Reads the Kotlin as text and compiles nothing, so it takes about a second. Python's standard library
only: every machine with this repo has it, and a shell script can't tell a comment or a string from code.
Checked by scripts/test-arch-check.py. `--root` is for that test.

ALLOWED, below, is every known breach. An entry that's no longer needed fails, so the list shrinks; a new
breach fails, so it can't grow without a line here and a row in docs/LATER.md.
"""
import argparse
import re
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, NamedTuple, Optional, Tuple

UI = "com.prudnikweb.hypernova.ui"
A = "android/"
PHONE_UI = A + "app/src/main/java/com/prudnikweb/hypernova/ui/"
DESK_UI = A + "desktop/src/main/kotlin/com/prudnikweb/hypernova/desktop/ui/"


class Allowed(NamedTuple):
    rule: str
    file: str
    names: Tuple[str, ...]
    later: str  # its row in docs/LATER.md, which has to name each of `names`
    why: str


ALLOWED = [
    Allowed("phone-desktop-same-name", DESK_UI + "AtlasPlate.kt", ("AtlasChart", "AtlasBackground"), "L128",
            "The chart, the one part of Atlas still copied: the phone's fx/Atlas.kt draws on android.graphics."),
    Allowed("phone-desktop-same-name", DESK_UI + "SessionsScreen.kt", ("OfflineCard", "GroupSeam"), "L129",
            "Written after HomeScreen's private ones, which still take the phone's HomeGroup and strings."),
    Allowed("phone-desktop-same-name", DESK_UI + "SessionPane.kt", ("AskCard", "AskPills"), "L129",
            "A question on the pane, after the phone's Home and RunTimeline, which still sit in the phone's ui/."),
    Allowed("phone-desktop-same-name", DESK_UI + "NewSessionPane.kt", ("ProjectLine", "NewProjectForm", "FolderBrowser"), "L129",
            "New session's pickers, after NewSessionPickers.kt, which is still the phone's."),
    Allowed("phone-desktop-same-name", DESK_UI + "HistoryList.kt", ("HistoryList",), "L129",
            "History's list, after the phone's History.kt: the rows came down to :shared, the drawing hasn't."),
    Allowed("phone-desktop-same-name", DESK_UI + "SettingsScreen.kt", ("SendState",), "L129",
            "The debug log's send state, after the phone's DebugLogSection.kt, which is still the phone's."),
]

# :shared may hold state for the screens to read, but not draw them (docs/PLATFORMS.md, "The shared module": JetBrains'
# runtime is what it depends on). This is what's in use; a new name of the same kind is a line here.
SHARED_RUNTIME_STATE = {
    "Immutable", "Stable", "State", "MutableState", "getValue", "setValue", "mutableStateOf", "mutableIntStateOf",
    "mutableLongStateOf", "mutableStateListOf", "mutableStateMapOf", "snapshots.Snapshot",
}
# The one composable in :shared: rememberFullText holds History's search results and draws nothing.
SHARED_COMPOSABLES = {"FullTextSearch.kt": {"Composable", "LaunchedEffect", "remember"}}
# A window's root and a screen are each system's own layout (CLAUDE.md: "each system may lay them out its own way").
OWN_LAYOUT = re.compile(r"App$|.*Screen$")
# animation-core is under the same group and stays allowed: Haze brings it (`:app:dependencyInsight`), whatever sharedui does.
JETBRAINS_POINTERS = re.compile(r"^org\.jetbrains\.compose\.((?:foundation:[\w-]+)|(?:animation:animation(?:-android)?)):", re.M)
CLASSPATH_FILES = ("android/app/classpath-debug.txt", "android/app/classpath-release.txt")
PREVIEW_IGNORE = ".*-preview/"
# CLAUDE.md, "No fingerprints or screen locks". In code; a comment or a string may name them.
BIOMETRIC_CODE = re.compile(
    r"\b(?:BiometricPrompt|BiometricManager|FingerprintManager|OwnerCheck|createConfirmDeviceCredentialIntent"
    r"|setDeviceCredentialAllowed|setUserAuthenticationRequired)\b|\bandroid\.hardware\.(?:biometrics|fingerprint)\b|\bandroidx\.biometric\b"
)
# In what declares a permission or a dependency, as plain text: a Gradle dependency is a string.
BIOMETRIC_BUILD = re.compile(r"androidx\.biometric|USE_BIOMETRIC|USE_FINGERPRINT")
BIOMETRIC_BUILD_FILES = (
    "android/app/src/main/AndroidManifest.xml", "android/app/build.gradle.kts", "android/shared/build.gradle.kts",
    "android/sharedui/build.gradle.kts", "android/desktop/build.gradle.kts", "android/gradle/libs.versions.toml",
    *CLASSPATH_FILES,
)

TOKEN = re.compile(r'"""|"|//|/\*|`[^`\n]*`|\'(?:\\.|[^\\\'\n])[^\'\n]*\'|\{|\}')
IN_STRING = re.compile(r'\\.|\$\{|"|\n')
IN_RAW = re.compile(r'\$\{|"""')
BRACKET = re.compile(r"[{}()\[\]]")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
DECLARATION = re.compile(
    r"""(?<![:.\w@])
    (?P<mods>(?:(?:@[\w.:]+(?:\(\s*\))?|private|internal|public|protected|data|sealed|abstract|open|enum|annotation|value
        |inline|tailrec|operator|infix|suspend|external|const|lateinit|expect|actual|override)\s+)*)
    (?:(?:fun\s+)?(?:class|interface|object|typealias)\s+(?P<type>\w+)
      |fun\s+(?:<[^(=]*?>\s*)?(?:[\w.<>?,\s*]+?\.)?(?P<fun>\w+)\s*\(
      |(?:val|var)\s+(?:<[^=:]*?>\s*)?(?:[\w<>?,\s*]+?\.)?(?P<val>\w+)\s*(?::|=|\bby\b))""",
    re.X,
)


def code_of(text: str) -> str:
    """The text with comments and strings blanked, and every line where it was. What's in a string's ${} stays code."""
    out = list(text)
    end = len(text)

    def blank(a: int, b: int) -> None:
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    i = start = depth = 0
    mode = "code"
    templates: List[Tuple[int, str]] = []
    while True:
        if mode == "code":
            m = TOKEN.search(text, i)
            if not m:
                break
            t, i = m.group(), m.end()
            if t == "//":
                nl = text.find("\n", i)
                i = end if nl < 0 else nl
                blank(m.start(), i)
            elif t == "/*":  # Kotlin's nest
                level, j = 1, i
                while level and j < end:
                    opens, closes = text.find("/*", j), text.find("*/", j)
                    if closes < 0:
                        j = end
                    elif 0 <= opens < closes:
                        level, j = level + 1, opens + 2
                    else:
                        level, j = level - 1, closes + 2
                blank(m.start(), j)
                i = j
            elif t == '"':
                mode, start = "string", i
            elif t == '"""':
                mode, start = "raw", i
            elif t[0] in "'`":
                blank(m.start(), i)
            elif t == "{":
                depth += 1
            elif templates and depth == templates[-1][0]:
                mode = templates.pop()[1]
                start = i
            else:
                depth -= 1
        else:
            m = (IN_STRING if mode == "string" else IN_RAW).search(text, i)
            if not m:
                blank(start, end)
                break
            t = m.group()
            if t == "${":
                blank(start, m.start())
                i = m.end()
                templates.append((depth, mode))
                mode = "code"
            elif t in ('"', "\n", '"""'):
                blank(start, m.start())
                i = m.end()
                while t == '"""' and i < end and text[i] == '"':  # """a"""" ends on its last three
                    i += 1
                mode = "code"
            else:
                i = m.end()
    return "".join(out)


def top_level_of(code: str) -> str:
    """The code with whatever's inside any bracket blanked: what's left is the file's top level."""
    out = list(code)
    depth = opened = 0
    for m in BRACKET.finditer(code):
        if m.group() in "{([":
            depth += 1
            if depth == 1:
                opened = m.end()
        else:
            depth -= 1
            if depth == 0:
                for k in range(opened, m.start()):
                    if out[k] != "\n":
                        out[k] = " "
    return "".join(out)


class Decl(NamedTuple):
    name: str
    kind: str  # composable, type, fun or val
    private: bool
    line: int


class Source:
    def __init__(self, root: Path, path: Path):
        self.rel = path.relative_to(root).as_posix()
        self.name = path.name
        self.code = code_of(path.read_text(encoding="utf-8"))
        self.lines = self.code.split("\n")
        found = PACKAGE.search(self.code)
        self.package = found.group(1) if found else ""
        self.decls = []
        top = top_level_of(self.code)
        for m in DECLARATION.finditer(top):
            group = "type" if m.group("type") else "fun" if m.group("fun") else "val"
            mods = m.group("mods")
            kind = "composable" if group == "fun" and "Composable" in mods else group
            line = top.count("\n", 0, m.start(group)) + 1
            self.decls.append(Decl(m.group(group), kind, bool(re.search(r"\bprivate\b", mods)), line))


class Breach(NamedTuple):
    file: str
    line: int
    rule: str
    name: str  # what an allowlist entry matches on
    what: str
    todo: str


def check_sharedui_common(root: Path, sharedui: List[Source], out: List[Breach]) -> None:
    rule = "sharedui-common-only"
    src = root / (A + "sharedui/src")
    if src.is_dir():
        for d in sorted(p for p in src.iterdir() if p.name != "commonMain"):
            out.append(Breach(d.relative_to(root).as_posix(), 0, rule, d.name, f"a source set other than commonMain: {d.name}",
                              "Move it to commonMain. What differs by system goes behind UiPlatform.kt."))
    banned = [
        (re.compile(r"(?<![\w.])(android\.[a-z]\w*(?:\.\w+)*)"), "Put what it needs behind UiPlatform.kt, implemented in each shell."),
        (re.compile(r"(?<![\w.])(androidx\.activity(?:\.\w+)*)"), "Put what it needs behind UiPlatform.kt, implemented in each shell."),
        (re.compile(r"(?<![\w.])(java\.awt(?:\.\w+)*|javax\.swing(?:\.\w+)*)"), "Put what it needs behind UiPlatform.kt, implemented in each shell."),
        (re.compile(r"(?<![\w.])(R\.[a-z]\w*)"), "Use a Str key (Str.kt and StringIds.kt) through stringResource, or a UiPlatform seam."),
        (re.compile(r"^\s*import\s+([\w.]*\.R)(?:\.\w+)*\s*$"), "Use a Str key (Str.kt and StringIds.kt) through stringResource, or a UiPlatform seam."),
        (re.compile(r"\b(expect|actual)\s+(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+)?(?:data\s+|enum\s+|sealed\s+|abstract\s+|open\s+|value\s+|annotation\s+|inline\s+|suspend\s+|const\s+)*(?:fun|class|object|interface|typealias|val|var)\b"),
         "Use an interface in UiPlatform.kt: the module has only commonMain, by choice."),
    ]
    for s in sharedui:
        for number, line in enumerate(s.lines, 1):
            for pattern, todo in banned:
                m = pattern.search(line)
                if m:
                    out.append(Breach(s.rel, number, rule, m.group(1), f"uses {m.group(1)}", todo))


def check_sharedui_layers(sharedui: List[Source], out: List[Breach]) -> None:
    base = ("theme", "fx", "components", "platform")
    feature = re.compile(rf"(?<![\w.]){re.escape(UI)}\.(?!(?:{'|'.join(base)})\b)(\w+)")
    for s in sharedui:
        if not re.fullmatch(rf"{re.escape(UI)}\.(?:{'|'.join(base)})(?:\.\w+)*", s.package):
            continue
        for number, line in enumerate(s.lines, 1):
            m = feature.search(line)
            if m:
                out.append(Breach(s.rel, number, "sharedui-layers", m.group(1), f"{s.package} uses the feature package ui.{m.group(1)}",
                                  "ui.theme, ui.fx, ui.components and ui.platform never know a feature: move what both need down into one of them."))


def check_shared(root: Path, shared: List[Source], out: List[Breach]) -> None:
    rule = "shared-no-ui"
    seam = "What it needs from the system goes through Seams.kt, implemented in AndroidPlatform.kt and DesktopPlatform.kt."
    banned = [
        (re.compile(r"(?<![\w.])(android\.[a-z]\w*(?:\.\w+)*)"), seam),
        (re.compile(r"(?<![\w.])(java\.awt(?:\.\w+)*|javax\.swing(?:\.\w+)*)"), seam),
        (re.compile(r"(?<![\w.])(R\.[a-z]\w*)"), "Add a Str key: a line in Str.kt and in StringIds.kt."),
        (re.compile(r"^\s*import\s+([\w.]*\.R)(?:\.\w+)*\s*$"), "Add a Str key: a line in Str.kt and in StringIds.kt."),
        (re.compile(r"(?<![\w.])(com\.prudnikweb\.hypernova\.(?:ui|desktop)(?:\.\w+)*)"), "Data and logic don't know the screens. Move what both need into :shared, or the drawing into :sharedui."),
        (re.compile(r"(?<![\w.])(org\.jetbrains\.(?:compose|skia)(?:\.\w+)*)"), "Drawing goes in :sharedui."),
    ]
    compose = re.compile(r"(?<![\w.])androidx\.compose\.([\w.*]+)")
    for s in shared:
        allowed = SHARED_COMPOSABLES.get(s.name, set())
        for number, line in enumerate(s.lines, 1):
            for pattern, todo in banned:
                m = pattern.search(line)
                if m:
                    out.append(Breach(s.rel, number, rule, m.group(1), f"uses {m.group(1)}", todo))
            m = compose.search(line)
            if m:
                ref = m.group(1)
                tail = ref[len("runtime."):] if ref.startswith("runtime.") else None
                if tail is None:
                    out.append(Breach(s.rel, number, rule, ref, f"uses androidx.compose.{ref}", "Compose drawing goes in :sharedui. :shared only holds state."))
                elif tail not in SHARED_RUNTIME_STATE and tail not in allowed:
                    out.append(Breach(s.rel, number, rule, ref, f"uses androidx.compose.{ref}, which isn't state",
                                      "A composable goes in :sharedui. If it really is state, add it to SHARED_RUNTIME_STATE in scripts/arch-check.py and say why in the commit."))


def kit_of(sharedui: List[Source]) -> Dict[str, Tuple[str, str]]:
    """Every name :app and :desktop can see of :sharedui: public, and internal too, as they're its Kotlin friends."""
    kit: Dict[str, Tuple[str, str]] = {}
    for s in sharedui:
        for d in s.decls:
            if not d.private:
                kit.setdefault(d.name, (s.name, d.kind))
    return kit


def check_desktop_kit(desktop: List[Source], kit: Dict[str, Tuple[str, str]], out: List[Breach]) -> None:
    for s in desktop:
        for d in s.decls:
            if d.name in kit:
                out.append(Breach(s.rel, d.line, "desktop-no-kit", d.name, f"defines {d.name}, which is the kit's (sharedui's {kit[d.name][0]})",
                                  "Use sharedui's. If it's really another thing, give it another name."))


def check_one_hn(others: List[Source], out: List[Breach]) -> None:
    pattern = re.compile(r"\b(?:object|class|interface|typealias)\s+HN\b")
    for s in others:
        for number, line in enumerate(s.lines, 1):
            if pattern.search(line):
                out.append(Breach(s.rel, number, "one-hn", "HN", "defines HN outside sharedui", "Use sharedui's: ui.theme.HN."))


def named(sources: List[Source], kinds: Tuple[str, ...]) -> Dict[str, Tuple[Source, Decl]]:
    found: Dict[str, Tuple[Source, Decl]] = {}
    for s in sources:
        for d in s.decls:
            if d.kind in kinds:
                found.setdefault(d.name, (s, d))
    return found


def check_same_names(rule: str, shell: List[Source], other: List[Source], todo: str, skip: Optional[re.Pattern], out: List[Breach]) -> None:
    for kinds, word in ((("composable",), "composable"), (("type",), "class")):
        theirs = named(other, kinds)
        for s in shell:
            for d in s.decls:
                if d.kind in kinds and d.name in theirs and not (skip and skip.fullmatch(d.name)):
                    there = theirs[d.name][0].rel
                    out.append(Breach(s.rel, d.line, rule, d.name, f"the {word} {d.name} is also in {there}", todo))


def check_same_file(modules: Dict[str, List[Source]], out: List[Breach]) -> None:
    where = defaultdict(list)
    for module, sources in modules.items():
        for s in sources:
            where[(s.package, s.name)].append((module, s))
    for (package, name), found in sorted(where.items()):
        if len({module for module, _ in found}) > 1:
            first = found[0][1].rel
            for _, s in found[1:]:
                out.append(Breach(s.rel, 0, "same-file-same-package", name, f"{package}.{name} is also {first}: they clash as the same {name[:-3]}Kt",
                                  "Rename one of the files."))


def check_classpath(root: Path, out: List[Breach]) -> None:
    rule = "app-classpath"
    for rel in CLASSPATH_FILES:
        f = root / rel
        if not f.is_file():
            out.append(Breach(rel, 0, rule, rel, "the checked-in classpath is missing", "From android/: ./gradlew :app:writeRuntimeClasspath"))
            continue
        for m in JETBRAINS_POINTERS.finditer(f.read_text(encoding="utf-8")):
            out.append(Breach(rel, 0, rule, m.group(1), f"JetBrains' {m.group(1)} is on :app's runtime classpath",
                              "It's only a pointer to AndroidX's, and it stays off: compileOnly in sharedui's commonMain (docs/PLATFORMS.md, \"The shared drawing\")."))


def check_no_biometric(root: Path, sources: List[Source], out: List[Breach]) -> None:
    rule = "no-biometric"
    todo = "The owner said no: CLAUDE.md, \"No fingerprints or screen locks\". What loosens permissions or runs code gets a one-tap confirm saying what it does."
    for s in sources:
        for i, line in enumerate(s.lines, 1):
            for m in BIOMETRIC_CODE.finditer(line):
                out.append(Breach(s.rel, i, rule, m.group(0), f"{m.group(0)} is a fingerprint or screen-lock check", todo))
    for rel in BIOMETRIC_BUILD_FILES:
        f = root / rel
        if f.is_file():
            for m in BIOMETRIC_BUILD.finditer(f.read_text(encoding="utf-8")):
                out.append(Breach(rel, 0, rule, m.group(0), f"{m.group(0)} is there for a fingerprint or screen-lock check", todo))


def check_preview_ignore(root: Path, out: List[Breach]) -> None:
    f = root / ".gitignore"
    lines = f.read_text(encoding="utf-8").splitlines() if f.is_file() else []
    if PREVIEW_IGNORE not in lines:
        out.append(Breach(".gitignore", 0, "previews-ignored", PREVIEW_IGNORE, f"{PREVIEW_IGNORE} isn't there", f"Add the line {PREVIEW_IGNORE}"))


def sources_in(root: Path, *folders: str) -> List[Source]:
    found = []
    for folder in folders:
        found += [Source(root, p) for p in sorted((root / folder).rglob("*.kt"))]
    return found


def apply_allowlist(root: Path, found: List[Breach]) -> Tuple[List[Breach], int]:
    later = root / "docs/LATER.md"
    rows = later.read_text(encoding="utf-8").splitlines() if later.is_file() else []
    left = list(found)
    used = 0
    for entry in ALLOWED:
        row = next((r for r in rows if re.match(rf"\|\s*{entry.later}\s*\|", r)), None)
        for name in entry.names:
            hits = [b for b in left if (b.rule, b.file, b.name) == (entry.rule, entry.file, name)]
            if hits:
                used += len(hits)
                left = [b for b in left if b not in hits]
                if row is None or name not in row:
                    left.append(Breach(entry.file, 0, "allowlist", name, f"its allowlist entry points at {entry.later} in docs/LATER.md, and that row doesn't name it",
                                       f"Fix the row or the entry in scripts/arch-check.py. Why it's allowed: {entry.why}"))
            else:
                left.append(Breach(entry.file, 0, "allowlist", name, f"the allowlist has {name} here, and it's no longer a breach",
                                   f"Good: take it out of ALLOWED in scripts/arch-check.py, and out of {entry.later} in docs/LATER.md."))
    return left, used


def check(root: Path) -> Tuple[List[Breach], int, int]:
    sharedui = sources_in(root, A + "sharedui/src")
    shared = sources_in(root, A + "shared/src")
    app_all = sources_in(root, A + "app/src")
    desktop_all = sources_in(root, A + "desktop/src")
    app = [s for s in app_all if s.rel.startswith((A + "app/src/main/", A + "app/src/debug/"))]
    desktop = [s for s in desktop_all if s.rel.startswith(A + "desktop/src/main/")]
    phone_ui = [s for s in app if s.rel.startswith(PHONE_UI)]
    desk_ui = [s for s in desktop if s.rel.startswith(DESK_UI)]

    out: List[Breach] = []
    check_sharedui_common(root, sharedui, out)
    check_sharedui_layers(sharedui, out)
    check_shared(root, shared, out)
    check_desktop_kit(desktop_all, kit_of(sharedui), out)
    check_one_hn(app_all + shared + desktop_all, out)
    move = "Move it into sharedui and use it from both. If it's really another thing, give it another name."
    check_same_names("phone-sharedui-same-name", phone_ui, sharedui, move, None, out)
    check_same_names("desktop-sharedui-same-name", desk_ui, sharedui, move, None, out)
    check_same_names("phone-desktop-same-name", desk_ui, phone_ui, "Don't copy: move the phone's into sharedui and use it from both (CLAUDE.md, \"The rules\"). If it's really another thing, give it another name.", OWN_LAYOUT, out)
    check_same_file({"app": app, "shared": [s for s in shared if "/src/commonMain/" in s.rel], "sharedui": sharedui, "desktop": desktop}, out)
    check_classpath(root, out)
    check_no_biometric(root, sharedui + shared + app_all + desktop_all, out)
    check_preview_ignore(root, out)
    left, allowed = apply_allowlist(root, out)
    return left, allowed, len(sharedui) + len(shared) + len(app_all) + len(desktop_all)


def main() -> int:
    parser = argparse.ArgumentParser(description="Hypernova's architecture rules, without compiling.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent, help="the repository (default: this one)")
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / A).is_dir():
        print(f"arch-check: no android/ in {root}", file=sys.stderr)
        return 2
    began = time.monotonic()
    left, allowed, files = check(root)
    took = time.monotonic() - began
    for b in sorted(left, key=lambda b: (b.rule, b.file, b.line)):
        print(f"{b.file}{f':{b.line}' if b.line else ''}: [{b.rule}] {b.what}. {b.todo}")
    if left:
        print(f"arch-check: {len(left)} broken in {files} Kotlin files, {took:.1f}s. See docs/PLATFORMS.md, \"The rules' checks\".")
        return 1
    print(f"arch-check: ok, {files} Kotlin files, {allowed} known breaches on the allowlist, {took:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
