# Hypernova

A control room for Claude Code: a native Android app and a daemon on a Linux server,
in the Hyper* family's "digital cosmos" look.

- `docs/DECISIONS.md`: agreed product decisions. Follow them.
- `docs/LATER.md`: ideas parked "for now". **Whenever the owner says "for now",
  "later" or "not yet" about something, add a row there.** Read it at the start
  of each milestone.
- `docs/DESIGN.md`: design tokens and effects, ported from 0stems
  (`~/Programming/0stems`), Hyperscribe and Hypernavigate.
- `docs/ON-DEVICE.md`: research only, nothing built: Claude Code and the daemon running on the
  phone itself, with no server (a Debian userland under `proot`, not Docker).
- `docs/INSTALL.md`: the one install guide, for strangers. When setup's steps, their words or the
  requirements change, change it and README's short version too.
- `docs/LINUX.md`: the `hypernova` command, every flag, and where things live on the server.
- `docs/FEATURES.md`: every user-facing feature and the tests that guard it (covered, partial or
  gap). Update it whenever a feature or its tests change.

Layout: `android/` (Gradle, Kotlin, Compose), `daemon/` (TypeScript on Node 22.18 or newer,
run directly without a build step; `daemon/src/cli/` is the `hypernova` command), `scripts/`
(the Linux install, `pack-linux.sh`, the Docker entrypoint, end-to-end checks),
`skills/hyper-cosmos/` (the design language as a Claude Code skill, for reuse in other apps).
`docs/PROTOCOL.md` is the contract between them.

Daemon: `cd daemon && node src/main.ts` (Node from nvm). Typecheck with `npx tsc -p .`, test with
`npm test`. On Linux, state lives in `~/.local/share/hypernova`, logs in
`~/.local/state/hypernova/logs/daemon.log`. Ports 47321 (phone API on loopback), 47323 (the phones'
local route: mutual TLS on the network, the only way phones come in; Tailscale was removed
2026-10-30, docs/DECISIONS.md)
and 47322 (the local API: the `hypernova` command, hooks, testing checklist at
http://127.0.0.1:47322/qa). The owner's checklist results land in `<support>/qa/`: read them
first when he says he has tested.

Shared terminals: every interactive `claude` (via the `claude` function the integration
adds to `~/.bashrc` or `~/.zshrc`) and every phone session runs in a detached holder
(`daemon/src/term/holder.ts`) that owns the PTY; `hn ls` / `hn attach <id>` from a shell.
See `docs/PROTOCOL.md`, "Shared terminals".

Testing while the owner's own install is live: run a second daemon with `HYPERNOVA_SUPPORT`
(it then leaves the shell's startup file alone), `HYPERNOVA_LOGS` (else it logs into his
`daemon.log`), `HYPERNOVA_API_PORT`, `HYPERNOVA_LOCAL_PORT` and `HYPERNOVA_LAN_PORT`. His daemon
still lists every interactive `claude` on the server, so start test sessions with
`--settings '{"disableAllHooks":true}'` (no approvals on his phone), title their prompts
"[Hypernova test]", and delete their transcripts afterwards. Never run `hypernova setup` or
`hypernova uninstall` for a test copy: the systemd user service keeps its one name
(`hypernova.service`), so it replaces or removes the owner's.

Attachments from the phone are saved in `<support>/attachments` (30 days) and reach
Claude Code as `@"path"` mentions before the prompt (`daemon/src/attachments.ts`;
`docs/PROTOCOL.md`, "Attachments").

Codex (OpenAI's) is a second agent (`daemon/src/codex/`; `docs/PROTOCOL.md`, "Codex" and
"Switching agents"; `docs/specs/codex-app-server.md`): the daemon is a client of Codex's own shared
app-server, only while `settings.codex` is on, and the shell's `codex` runs in a shared terminal
(`hn run-codex`) then. Its state: `<support>/codex-threads.json`, `codex-state.json`, `switches.json`.
Testing with a test daemon: `docs/DEV-SETUP.md`, "Codex". A first attempt that never worked is on
the branch `archive/chatgpt-codex-2026-10-19`: reference only, never merge it.

Releases: `.github/workflows/android-release.yml` builds the signed APK and the Linux server package
(`scripts/pack-linux.sh`) on each push to `main` that touches the app, the daemon or the install
scripts (docs/RELEASING.md). There is no Mac app, website or name service in this repository; the
daemon keeps the `/v1/mac/*` route names and its Mac-only features (battery, Stay awake), which
answer as unavailable on Linux (docs/PROTOCOL.md, "Mac-only features").

Commits: conventional commits, because the release's version and notes are counted from them
(`scripts/next-version.sh`, docs/RELEASING.md, "Versions"). `type(scope): subject`, the scope
optional, the subject in the repo's plain style (what changed, in words, no period):
`feat(android): Home cards say what a Working session is doing`. Types: `feat:` (something new:
the next release is a minor one), `fix:`, `perf:`, `refactor:`, `docs:`, `test:`, `chore:` (a
patch). A breaking change, the phone and the daemon no longer fitting each other's older version,
is `feat!:` / `fix!:` or a `BREAKING CHANGE: …` line in the body (a major). Merge commits don't
count, the commits they bring in do.

`skills/hyper-cosmos/` is a versioned copy of the personal skill at
`~/.claude/skills/hyper-cosmos/` (the copy that actually triggers). When the
design changes, update the skill too (its `assets/compose/` holds this app's
Tokens, Cosmos, Solar and so on), then sync:
`rsync -a --delete ~/.claude/skills/hyper-cosmos/ skills/hyper-cosmos/`.

Build Android with JDK 21 and the Android SDK (here in `~/Android`), from `android/`:
`JAVA_HOME=$HOME/Android/jdk-21 ANDROID_HOME=$HOME/Android/sdk ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest`.
