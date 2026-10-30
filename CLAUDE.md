# Hypernova

A control room for Claude Code: a native Android app and a Mac menu bar app,
in the Hyper* family's "digital cosmos" look.

- `docs/DECISIONS.md`: agreed product decisions. Follow them.
- `docs/LATER.md`: ideas parked "for now". **Whenever the owner says "for now",
  "later" or "not yet" about something, add a row there.** Read it at the start
  of each milestone.
- `docs/DESIGN.md`: design tokens and effects, ported from 0stems
  (`~/Programming/0stems`), Hyperscribe and Hypernavigate.
- `docs/ON-DEVICE.md`: research only, nothing built: Claude Code and the daemon running on the
  phone itself, with no Mac (a Debian userland under `proot`, not Docker).
- `docs/INSTALL.md`: the one install guide, for strangers (the website is built from it). When
  Setup's steps, their words or the requirements change, change it and README's short version too.

Layout: `android/` (Gradle, Kotlin, Compose), `daemon/` (TypeScript on Node 22.18 or newer,
run directly without a build step), `mac/` (SwiftUI menu bar app built with SwiftPM
and Command Line Tools), `skills/hyper-cosmos/` (the design language as a Claude Code
skill, for reuse in other apps). `docs/PROTOCOL.md` is the contract between them.

Daemon: `cd daemon && node src/main.ts` (Node from nvm). Typecheck with `npx tsc -p .`.
State lives in `~/Library/Application Support/Hypernova`, logs in
`~/Library/Logs/Hypernova/daemon.log`. Ports 47321 (phone API, behind `tailscale serve`)
and 47322 (Mac app, hooks, testing checklist at http://127.0.0.1:47322/qa). The owner's
checklist results land in `~/Library/Application Support/Hypernova/qa/`: read them
first when he says he has tested.

Shared terminals: every interactive `claude` (via the `claude` function the integration
adds to `~/.zshrc`) and every phone session runs in a detached holder
(`daemon/src/term/holder.ts`) that owns the PTY; `hn ls` / `hn attach <id>` from a shell,
or "Open" in the menu bar. See `docs/PROTOCOL.md`, "Shared terminals".

Testing while the owner's own install is live: run a second daemon with `HYPERNOVA_SUPPORT`
(it then leaves `~/.zshrc` alone), `HYPERNOVA_LOGS` (else it logs into his `daemon.log`),
`HYPERNOVA_API_PORT` and `HYPERNOVA_LOCAL_PORT`. His daemon
still lists every interactive `claude` on the Mac, so start test sessions with
`--settings '{"disableAllHooks":true}'` (no approvals on his phone), title their prompts
"[Hypernova test]", and delete their transcripts afterwards.

Attachments from the phone are saved in `<support>/attachments` (30 days) and reach
Claude Code as `@"path"` mentions before the prompt (`daemon/src/attachments.ts`;
`docs/PROTOCOL.md`, "Attachments").

Codex (OpenAI's) is a second agent (`daemon/src/codex/`; `docs/PROTOCOL.md`, "Codex" and
"Switching agents"; `docs/specs/codex-app-server.md`): the daemon is a client of Codex's own shared
app-server, only while `settings.codex` is on, and the shell's `codex` runs in a shared terminal
(`hn run-codex`) then. Its state: `<support>/codex-threads.json`, `codex-state.json`, `switches.json`.
Testing with a test daemon: `docs/DEV-SETUP.md`, "Codex". A first attempt that never worked is on
the branch `archive/chatgpt-codex-2026-10-19`: reference only, never merge it.

Mac app: `mac/build.sh` builds `dist/Hypernova.app` (SwiftPM + Command Line Tools, no Xcode) and
bundles the daemon plus the newest `dist/*.apk`; `--release` stops instead of warning, and
`mac/dmg.sh` packs `dist/Hypernova.dmg` (`HYPERNOVA_DIST=<dir>` and `mac/dmg.sh <app> <dmg>` build
into a scratch folder instead). `Hypernova --selftest` dry-runs setup;
`Hypernova --run-step <service|integration|tailscale|uninstall-service>` runs one setup step.

Mac app developer flags (more at the top of `mac/Sources/Hypernova/App/DevTools.swift`):
- `--render-popover <dir> [--live]`: the popover in its nine states on sample data, plus the menu
  bar icon's frames (PNGs) and its turn (GIFs), without asking the daemon. `--live` adds one render
  from the daemon the variables below point at.
- `--popover-selftest <dir>`: the real status item and popover on sample data for about 15 s (a
  second planet shows in the menu bar): checks the turn, the badge and where the panel opens and
  how tall, prints each check and the CPU used, saves the open panel, exits 1 on a failure.

Environment the Mac app honours:
- `HYPERNOVA_SUPPORT`, `HYPERNOVA_API_PORT`, `HYPERNOVA_LOCAL_PORT`: a test daemon's folder (its
  admin token; Setup copies the daemon and downloads Node there) and ports. The LaunchAgent and its
  log keep their usual paths, so never run the service step from a test copy (Setup or
  `--run-step service|uninstall-service`): it replaces or removes the owner's.
- `HYPERNOVA_NO_MOVE` (any value): no "Move Hypernova to Applications?" offer for a `Hypernova.app`
  run from elsewhere, such as `dist/`.
- `HYPERNOVA_DEBUG_CAPTURE=<dir>`: 6 s after launch the app photographs its own windows into `<dir>`
  (Setup if open, the real popover by clicking its own icon, Providers and its OpenRouter editor)
  and writes `report.txt` about each window.

`--popover-selftest` and `HYPERNOVA_DEBUG_CAPTURE` start the whole app, and a copy with the bundle's
id hands over to a running Hypernova and quits, so while the owner's runs, use the SwiftPM build:
`swift build -c release` in `mac/`, then `mac/.build/release/Hypernova`.

`skills/hyper-cosmos/` is a versioned copy of the personal skill at
`~/.claude/skills/hyper-cosmos/` (the copy that actually triggers). When the
design changes, update the skill too (its `assets/compose/` holds this app's
Tokens, Cosmos, Solar and so on), then sync:
`rsync -a --delete ~/.claude/skills/hyper-cosmos/ skills/hyper-cosmos/`.

Build Android with the Android Studio JDK:
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug`
(run from `android/`).

The old Telegram bot is retired and archived in `~/Archive/` (LATER L7).
