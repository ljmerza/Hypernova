# Contributing

Thanks for looking. Hypernova is alpha and moves fast, so for anything bigger than a small fix,
open an issue first and say what you want to change. Security problems do not go in issues: see
[`SECURITY.md`](SECURITY.md).

Licences are in the README. Lens is AGPL-3.0-or-later and must stay separate: don't copy Lens
code into the other folders. By contributing you agree your change goes under the licence of the
folder it touches.

## Layout

| Folder | What | Tools |
| --- | --- | --- |
| `daemon/` | The daemon: sessions, hooks, shared terminals, the phone API, automations, speech. TypeScript on Node 22.18 or newer, run directly with no build step | Node 24 or newer, npm |
| `lens/` | Hypernova Lens for Even Realities G2 glasses. TypeScript and Vite | Node 24 or newer, npm |
| `android/` | The Android app, Kotlin and Jetpack Compose | JDK 21 (Android Studio's works), Gradle wrapper |
| `mac/` | The menu bar app, SwiftUI built with SwiftPM (no Xcode project) | Swift 6, macOS 14+ |
| `names/` | The name service, a Cloudflare Worker (tests run locally, never against Cloudflare) | Node 24 or newer, npm |
| `docs/` | [`PROTOCOL.md`](docs/PROTOCOL.md) is the contract between the layers; [`DECISIONS.md`](docs/DECISIONS.md) is what is agreed; [`LATER.md`](docs/LATER.md) is what is parked; [`specs/`](docs/specs) are designs; [`INSTALL.md`](docs/INSTALL.md) is the install guide; [`RELEASING.md`](docs/RELEASING.md) is how a release is made | |
| `skills/`, `third_party/`, `site/` | The design language as a skill, fonts and xterm.js, the project website (hypernova.nikbraun.tech) | |

Read `docs/PROTOCOL.md` before changing anything the layers say to each other, and
`docs/DECISIONS.md` before changing behaviour: follow what is agreed there or raise it in the issue.
If you change the protocol, change the daemon, the clients that use it and the document together.

## Checks, layer by layer

Run the ones for the folder you touched. GitHub Actions runs the same commands
(`.github/workflows/`), so a green run locally is the goal.

```bash
# daemon (Node 24 or newer)
cd daemon && npm ci && npx tsc -p . && npm test

# lens (Node 24 or newer)
cd lens && npm ci && npm test && npx tsc --noEmit -p .
npm run pack            # also builds out.ehpk; needs a local app.local.json (not committed)

# names (Node 24 or newer)
cd names && npm ci && npx tsc -p . && npx vitest run

# android (JDK 21; the Android SDK with platform 37)
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest

# mac (macOS 14+, Swift 6; CI runs it only by hand and weekly)
cd mac && swift build -c release
```

Tests must not depend on your machine: no real Claude login, no Tailscale, no files in your home
folder or in the live support folder, no fixed ports of a running install. Use a temporary folder
and `HYPERNOVA_SUPPORT`, and port `0` where a test needs a port. If a test truly needs something
only one OS has, skip it on the others with a reason, as the Telescope tests do for macOS.

## Running a second daemon next to a live one

If you use Hypernova yourself while developing, never point a test at your own install. Run a
second daemon with its own folder, log and ports:

```bash
cd daemon
HYPERNOVA_SUPPORT=/tmp/hn-test/support HYPERNOVA_LOGS=/tmp/hn-test/logs \
HYPERNOVA_API_PORT=48321 HYPERNOVA_LOCAL_PORT=48322 HYPERNOVA_LAN_PORT=48323 \
CLAUDE_CONFIG_DIR=/tmp/hn-test/claude \
node src/main.ts
```

- `HYPERNOVA_SUPPORT` also keeps the daemon away from your `~/.zshrc` (it won't install the
  `claude` shell function). `HYPERNOVA_LOGS` keeps it out of your real `daemon.log`.
- Your own daemon still lists every interactive `claude` on the computer. Start test sessions with
  `--settings '{"disableAllHooks":true}'` so no approval lands on your phone, give their prompts
  the title "[Hypernova test]", and delete their transcripts afterwards.
- `CLAUDE_CONFIG_DIR` pointing at a scratch folder keeps a test daemon's sessions and Claude
  login out of the picture.
- The Mac app honours the same variables. Never run its service step from a test copy (Setup or
  `--run-step service|uninstall-service`): it replaces or removes your real service. Use
  `HYPERNOVA_NO_MOVE=1` when running a build from `dist/`, and run the SwiftPM build
  (`mac/.build/release/Hypernova`) rather than a copy with the bundle's id while yours is running.
- A fake provider (`daemon/test/fake-provider/`, see [`docs/DEV-SETUP.md`](docs/DEV-SETUP.md)) lets
  you try other providers, limits and auto-continue without spending anything.
- With adb, always name the device (`adb -s <serial> ...`), so a command never lands on a phone you
  didn't mean.

## Style

- **Comments say why**, in plain English: the reason, the trap, the thing that looked simpler and
  failed. Don't narrate what the next line does.
- **User-facing words live in resources**: Android's `res/values/strings_*.xml`, never literals in
  Kotlin. Keep them short, plain and kind; say what happened and what to do, not an error code.
- Keep to the look in [`docs/DESIGN.md`](docs/DESIGN.md) (the `skills/hyper-cosmos` skill has
  the tokens).
- TypeScript is strict and erasable-syntax only (no enums, no namespaces), because the daemon runs
  without a build step: imports end in `.ts`.
- New logic goes in a new file with a test, not into a file that is already long. Secrets, tokens
  and the text a person speaks or reads aloud are never logged.
- Small, focused changes. A change that adds a setting, a route or a screen also updates the
  protocol document and the tests.
- Ideas for "later" go in `docs/LATER.md`, not into the code as half-done features.
