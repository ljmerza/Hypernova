<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->
# Hypernova

A control room for Claude Code and Codex: a native Android app, a desktop client for Linux, Mac and
Windows, and a daemon on your own server. Its look is Atlas, a star chart (`docs/DESIGN.md`).

Maintainability across operating systems comes first: many more features and much more customisation
are coming, and a feature should only be built once.

## Docs

| File | What it is |
|---|---|
| `docs/DECISIONS.md` | Agreed product decisions. Follow them, and add one for each new decision, quoting the owner. |
| `docs/LATER.md` | What's parked. **Whenever the owner says "for now", "later" or "not yet", add a row.** Read it at the start of each milestone. |
| `docs/PROTOCOL.md` | The contract between the clients and the daemon. |
| `docs/PLATFORMS.md` | The modules, the shared drawing, and the desktop's parity list: what it has of the phone's. |
| `docs/FEATURES.md` | Every user-facing feature and the tests guarding it (covered, partial or gap). Update it whenever a feature or its tests change. |
| `docs/SECURITY-MODEL.md` | What's protected, how, and the risks accepted. |
| `docs/DESIGN.md` | Design tokens, marks and effects. |
| `docs/INSTALL.md` | The one install guide, for strangers. When setup's steps or requirements change, change it and README's short version. |
| `docs/LINUX.md` | The `hypernova` command, every flag, and where things live on the server. |
| `docs/RELEASING.md` | Versions, names, the release workflows and what's proven of them. |
| `docs/DEV-SETUP.md` | Building and running everything locally. |
| `docs/IDEAS.md`, `docs/ON-DEVICE.md`, `docs/specs/` | Ideas and research. Nothing there is promised. |

## Architecture

```
android/app       the phone: a thin shell        android/desktop  the desktop: a thin shell
        \                                                 /
         └──────►  android/sharedui  drawing  ◄──────────┘
                        |
                   android/shared    data and logic
                        |
                  docs/PROTOCOL.md
                        |
                     daemon/         TypeScript on the server
```

**The rules. A feature that breaks one is built wrong, however well it works.**

| Module | Holds | Never |
|---|---|---|
| `android/shared` | Data and logic: the protocol, the hub, models, anything testable without a screen | `android.*`, `R`, `ui`, Compose drawing. What it needs from the system goes through `Seams.kt`, implemented in `app/.../data/AndroidPlatform.kt` and `desktop/.../DesktopPlatform.kt` |
| `android/sharedui` | Every screen part and component: `ui.theme` (tokens), `ui.fx`, `ui.components` (the kit), `ui.chat`, `ui.platform` | `commonMain` only: no `android.*`, `java.awt`, `javax.swing`, `R` or `expect`. `theme`, `fx`, `components` and `platform` never import a feature such as `ui.chat`. What it needs from the system goes through `UiPlatform.kt`: `UiWords`, `UiFonts`, `UiDevice`, `UiInput`, `UiClock`, `UiDiagrams` |
| `android/app` | What's truly Android's: notifications, the widget, voice, the camera, file picking, the manifest, and layouts that are the phone's alone | A copy of something `sharedui` has |
| `android/desktop` | What's truly the desktop's: the window, the tray, the keychain, packaging, two-pane layouts | Its own `HN`, `Type`, `Pill`, `Seam` or any other copy of the kit |
| `daemon` | The server: run directly with no build step; `src/cli/` is the `hypernova` command | Anything a client should decide |

- **New features go in `sharedui` and `shared` by default.** Only what can't be shared goes in a shell. When a phone screen is needed on the desktop, move it rather than copying it.
- **Share the parts and the state; each system may lay them out its own way.** The phone stacks screens and the desktop has two panes. A forced single layout makes both worse.
- **OS differences go behind a small named interface**, so adding a system means implementing that list. Prefer a few well-named seams over many one-off lambdas.
- **Customisation builds on the tokens and on settings described as data**, so a new setting or theme reaches every system without per-OS work.
- **A shared UI library version moves the phone too.** `:app`'s runtime classpath stays the same list plus `:sharedui` unless that's a chosen change: JetBrains' `foundation` and `animation` are `compileOnly` in `commonMain` for this reason. The list is checked in as `android/app/classpath-debug.txt` and `classpath-release.txt`: a chosen change runs `:app:writeRuntimeClasspath` and commits them with it.
- **Strings:** a new string that shared code uses is a line in `Str.kt` and in `StringIds.kt`. Words live in the phone's `res/values`, read at run time on the desktop and held there by `WordsTest`; desktop-only ones are in `strings_desktop.xml`.
- **No two modules with the same file name in the same package:** they clash at dex as the same `XKt` class.
- **`SharedUi.install` comes first** in both apps. A test with no install silently gets the default font.
- **Not everything is shared yet.** `docs/PLATFORMS.md`, "The shared drawing", lists what's left, and the phone's `ui/` still holds most screens. `scripts/arch-check.py` and `:app:checkRuntimeClasspath` enforce these rules on every push and pull request (`docs/PLATFORMS.md`, "The rules' checks"). They can't see logic copied under another name: reviewing that is on you.
- **A new Android permission can change existing features.** Check everything that touches that feature: adding `CAMERA` for Scan made the attachment camera crash in v0.26.0.

## Testing

Tests are how a change is known to work. **Say what ran, what it proved, and what's unproven.** Never call something working because it compiled, and never say a test passed that you didn't run.

| Kind | Where | Run | What it proves |
|---|---|---|---|
| Daemon | `daemon/test/*.test.ts` | `cd daemon && ./node_modules/.bin/tsc -p . && npm test` | The server's logic and routes, in process |
| Real Claude Code | `daemon/test/*-e2e.test.ts`, `daemon/test/fake-provider` | Part of `npm test`, skipped where Claude Code isn't installed, as on CI | What the real binary does, against a scripted API with no account and no model |
| Screen fixtures | `daemon/test/fixtures/` | Part of `npm test` | Real Claude Code screens and transcripts, read as they really are |
| Contract | `protocol/fixtures`, `daemon/test/contract.test.ts`, `ContractTest.kt` | Both suites. `UPDATE_FIXTURES=1 node --test test/contract.test.ts` regenerates | Both sides of the wire agree. Any wire change adds a fixture |
| Shared | `android/shared/src/commonTest`, `hubTest` | `:shared:desktopTest :shared:testAndroidHostTest` | Data code on both targets; the hub against `FakeDaemon` over real mutual TLS |
| Phone screens | `android/app/src/test` | `:app:testDebugUnitTest` | Screens and flows on Robolectric. It doesn't enforce `exported` or real input |
| Screenshots | `android/app/src/test/snapshots` | `:app:verifyRoborazziDebug` | Nothing moved by accident. Only on the phone's screens |
| Desktop | `android/desktop/src/test` | `:desktop:test` | The real screens through `FakeDaemon` over mutual TLS. `RendersTest` draws pictures to `build/renders`; `DemoServerTest` needs a running demo server |
| Packages | `scripts/test-client-package.sh`, `test-server-deb.sh`, `test-install-macos.sh`, `test-macos-smoke.sh`, `test-get-client.sh`, `test-linux-docker.sh`, `test-linux-systemd.sh` | In a container, or on CI's runners | A package installs, opens, pairs and uninstalls as a person would. Mac and Windows only run on CI |
| Release scripts | `scripts/test-next-version.sh`, `test-license-notices.sh` | By hand | Versions, release notes and licence notices |
| Architecture | `scripts/arch-check.py`, `scripts/test-arch-check.py`, `:app:checkRuntimeClasspath` | `scripts/arch-check.py` (2 seconds, no compile); `scripts/test-arch-check.py`, with `--gradle` for the classpath's half; CI runs all of them | The modules' rules hold, new breaches fail, and `:app`'s runtime classpath is the checked-in list. Not logic copied under another name |
| By eye | The emulator (AVDs `hn-demo`, `hn-art`), a real window under Xvfb in a container, `demo/server.ts`, `scripts/demo-shots.sh` | By hand | It really draws. Look at the screenshots yourself |
| Real device | The owner's phone; the checklist at `http://127.0.0.1:47322/qa`, results in `<support>/qa/` | The owner | The only proof of feel, a real camera, real sign-ins and real updates. Read the results first when the owner says they've tested |

**Everything:** first `scripts/arch-check.py` from the root, then from `android/`:

```bash
export JAVA_HOME=$HOME/Android/jdk-21 ANDROID_HOME=$HOME/Android/sdk PATH=$HOME/Android/jdk-21/bin:$PATH
./gradlew --no-daemon -q :app:assembleDebug :app:cleanTestDebugUnitTest :app:testDebugUnitTest :app:verifyRoborazziDebug \
  :shared:compileKotlinDesktop :shared:desktopTest :shared:testAndroidHostTest :desktop:cleanTest :desktop:test :app:checkRuntimeClasspath --continue
```

**The rules:**
- **A fix starts with a failing test, and you show it failing without the fix.** A test that passes either way proves nothing.
- **Gate on the exit code.** Never chain a commit or a merge past a failure.
- **Measure, don't assume.** Run the real program in a throwaway `HOME` and record what it does, instead of reasoning from memory or from what it prints. Updaters say "success" when nothing changed. Read state back after an action.
- **Before a release, compile from clean.** An incremental build can pass on stale classes after a file is deleted. It has happened here.
- **Screenshots:** view `app/build/outputs/roborazzi/*_compare.png` before re-recording, and explain every change. Don't insert a test between `@Config(...)` and its test.
- **Known flakes under load:** `usage-probe`, `ws-ping`, `holder-move`, `normalize` "masking time grows", `NotifyActionsTest.allow…`, `ChatMoreTest`, `SettingsTabTest` (`BindException` if something holds port 47391), `MutualTlsTest`, `HistoryTabTest`, `WidgetSyncTest`. Rerun one alone before believing it, and say you did. A failure that isn't on this list is a finding, not a flake, until shown otherwise.
- **Green doesn't prove** a real phone, a real login, a real Mac or Windows desktop, a real GPU, or a real model. Say which of those a change depends on.

## Testing while the owner's install is live

- **Never touch** the real service (`hypernova.service`), `~/.local/share/hypernova`, or the real desktop client's folder and keyring. Never run `hypernova setup` or `hypernova uninstall` for a test copy: the service keeps its one name, so it replaces or removes the owner's.
- **Never read** `~/.claude/settings*.json`, `~/.claude.json`, or a project's `settings.local.json`: they can hold secrets.
- **A second daemon:** set `HYPERNOVA_SUPPORT`, `HYPERNOVA_LOGS`, `HYPERNOVA_API_PORT`, `HYPERNOVA_LOCAL_PORT`, `HYPERNOVA_LAN_PORT` and a temp `HOME`. The owner's daemon still lists every interactive `claude`, so start test sessions with `--settings '{"disableAllHooks":true}'`, title their prompts "[Hypernova test]", and delete their transcripts afterwards.
- **Desktop tests:** `HYPERNOVA_CLIENT_KEYCHAIN=off` or a temp home.
- **Processes:** stop only what you started, by its pid or serial. No pattern-based kills.
- **Installing:** nothing on this machine. Use a container, or a download in its own new folder, and say what you fetched.

## Rules that stand

- **Nothing public and no cloud:** no relay, no push service, no Tailscale, no public name service. A phone reaches its server directly over mutual TLS. An iPhone version may use Apple's push one day, and that's the only exception.
- **Allow only beside the whole command.** A list row or a notification that would cut it off has no Allow.
- **Bypass, `danger-full-access` and approval `never` are never a default.** Bypass is chosen each time, with its think-twice confirm. The server refuses them where a default is set.
- **No fingerprints or screen locks.** The owner chose this on 2026-11-04: "no fingerprint stuff". What loosens permissions or runs code gets a one-tap confirm saying what it does, required by the server. Don't add a biometric check.
- **Secrets never reach a client or a log:** credentials, `~/.claude.json`, the key places in `secretfiles.ts`. Mask commands as tool rows do. Attachments and client logs are untrusted text.
- **The session's chat screen stays clean.** Nothing new there unless the owner asks for it, and then a decision records the exception.
- **Don't overstate.** Plain wording, unique names, no hype, no clutter. The README promises only what's there.
- **This is the owner's public repo:** keep work details, machine names and home-lab details out of it.

## How the code runs

**Daemon:** `cd daemon && node src/main.ts`, on Node 22.23.4 or 24.22.1 or newer. On Linux, state lives in `~/.local/share/hypernova` and logs in `~/.local/state/hypernova/logs/daemon.log`. Ports: 47321 (the API on loopback), 47323 (the clients' local route, mutual TLS, the only way in), 47322 (the local API: the `hypernova` command, hooks, the checklist). When the owner says "check the logs", `hypernova client-logs` lists what a client sent, with their paths.

**Shared terminals:** every interactive `claude` (through the `claude` function the integration adds to `~/.bashrc` or `~/.zshrc`) and every session from a client runs in a detached holder (`daemon/src/term/holder.ts`) that owns the PTY; `hn ls` and `hn attach <id>` from a shell. `docs/PROTOCOL.md`, "Shared terminals".

**Codex** is a second agent (`daemon/src/codex/`; `docs/specs/codex-app-server.md`): the daemon is a client of Codex's own shared app-server while `settings.codex` is on, and the shell's `codex` runs in a shared terminal (`hn run-codex`) then. `docs/DEV-SETUP.md`, "Codex". The branch `archive/chatgpt-codex-2026-10-19` is reference only: never merge it.

**Attachments** are saved in `<support>/attachments` for 30 days and reach Claude Code as `@"path"` mentions before the prompt (`daemon/src/attachments.ts`).

**Mac:** no Mac app, but the server installs on one (`scripts/install-macos.sh`, `daemon/src/cli/launchd.ts`), and it has only run on GitHub's runners. `/v1/mac/*` keep their names, and Mac-only features answer as unavailable on Linux.

## Working here

**Parallel work:** one git worktree per piece of work, under `.claude/worktrees/<name>`, with `android/local.properties` copied in and a real copy of `daemon/node_modules`. Never run `npm ci` through a link: it has emptied the original. Agents commit on their branch; they don't push or merge. The integration branch is `agents-home`: merge there, test the merge (a clean Git merge can still fail to compile), and publish only when the owner says so. `docs/DECISIONS.md` conflicts almost every time because everyone appends: keep both sides.

**Releases:** `git push origin agents-home:main`, over SSH for workflow files, builds them: `android-release.yml` publishes the signed APK and the Linux server package, then `server-packages.yml` adds the `.deb`s and Mac packages, `client-packages.yml` the desktop client's, and a checksums job `SHA256SUMS` (`docs/RELEASING.md`). Big changes to packaging or shared code go through a pull request first, since that's the only place Mac and Windows run. A file that fails costs only itself: the notes say "Not built", and re-running the job fills it in. Apple silicon Mac jobs often find no runner and need re-running.

**Commits:** conventional, because the version and release notes are counted from them (`scripts/next-version.sh`). `type(scope): subject`, in plain words with no period: `feat(android): Home cards say what a Working session is doing`. `feat:` is a minor release; `fix:`, `perf:`, `refactor:`, `docs:`, `test:` and `chore:` are patches. `feat!:`, `fix!:` or a `BREAKING CHANGE: …` line is a major, for when clients and the daemon no longer fit each other's older version. Merge commits don't count; the commits they bring in do.

**Licence notices:** this is a fork of an Apache 2.0 project, so a changed file of the original needs a one-line notice. After changing or moving files, run `scripts/license-notices.sh --fix`; CI runs `--check`. New files get none. Never remove a copyright or attribution notice, and keep `NOTICE`, `CHANGES.md` and `THIRD-PARTY-NOTICES.md` true, including the copy in `android/app/src/main/assets/legal`.
