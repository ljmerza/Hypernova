# Dev setup notes (this Mac)

On some networks `dl.google.com` serves small files but hangs on large
ones. Google's Maven and the Android SDK
repository both live there, so this Mac uses mirrors. **Nothing below is committed config
that affects other people:** the project only switches to a mirror when a machine opts in.

## Gradle (Google Maven)
- `~/.gradle/gradle.properties` has `hypernova.googleMirror=https://maven.aliyun.com/repository/google`.
- `android/settings.gradle.kts` uses that mirror *instead of* `google()` when the property is set,
  and restricts it to `com.android.*`, `com.google.*` and `androidx.*`. Everything else comes from Maven Central.
- Without the property, it's the normal `google()` repository.

## Android SDK
- `cmdline-tools` isn't installed. SDK packages come from the Tencent mirror of
  Google's repository (`https://mirrors.cloud.tencent.com/AndroidSDK/`), with the SHA-1 checked against
  Google's `repository2-3.xml` from the same mirror. That only catches transfer errors: the checksum
  and the zip come from the same server. A Gradle checksum for Google's real index can be added later.
- `platforms/android-37.0` was installed by hand with a generated `package.xml`, since AGP ignores a platform
  without one. compileSdk 37 is required by Compose 1.12 and Haze 2.
- Emulator: system image `system-images/android-36/google_apis/arm64-v8a` (r07). The AVD `hypernova` is 1260×2800 @ 480dpi,
  matching the Nothing Phone 3's size, with the punch-hole cutout overlay and gesture navigation turned on.
  - Run it headless on the Mac GPU with `emulator -avd hypernova -no-window -no-audio -gpu host`.
  - Don't use `-gpu swiftshader_indirect`: software rendering takes ~800ms a frame and makes motion meaningless.

## Toolchain
- JDK: Android Studio's bundled JBR 21 (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Kotlin 2.4.21: AGP 9's built-in Kotlin picks it up from the root `plugins {}` block, and Haze 2 needs it.

## Screen tests (Robolectric and Roborazzi)
`android/app/src/test/java/com/prudnikweb/hypernova/ui/screens/` draws Home, the chat, the session
sheet, the New session sheet, Settings' parts and Statistics on the JVM (Robolectric, Compose's test
rule) with a fake hub (`FakeHub`, `Samples`): no phone, no network. Each test clicks and checks what the
fake hub was asked, and takes a picture of the screen (Roborazzi). The pictures are in
`android/app/src/test/snapshots/`, one phone size (`robolectric.properties` beside the tests).
- `./gradlew :app:testDebugUnitTest` runs them with the rest; it doesn't compare the pictures.
- `./gradlew :app:verifyRoborazziDebug` compares them: a picture that changed fails its test, and
  `app/build/outputs/roborazzi/<name>_compare.png` shows the old one, the difference and the new one.
- A change to how a screen looks on purpose: `./gradlew :app:recordRoborazziDebug` (all of them, or
  `--tests '*.ui.screens.HomeScreenTest'` for one class) writes them again; look at the new pictures
  before committing them.
- The first run downloads Robolectric's Android jar (SDK 35) from Maven Central.

## Fake provider (other providers' limits, without spending anything)
`daemon/test/fake-provider/` (tests only, never bundled) is a tiny Anthropic-compatible server on
loopback that streams canned replies ("Fake provider here. Nothing went to a model.") and, told to,
answers with another provider's errors, so switching providers, their limits and auto-continue can
be tried end to end. It forwards nothing anywhere and logs no header values or bodies.
- `node test/fake-provider/cli.ts start --port 48279` runs it; `/or/…` is the same server dressed as
  OpenRouter, with a `/or/v1/key` balance the tests set. Its control token is in
  `$HYPERNOVA_SUPPORT/fake-provider/token` (0600).
- On a **test** daemon (`HYPERNOVA_SUPPORT`, its own ports, and `CLAUDE_CONFIG_DIR` pointing at a
  scratch folder, so its sessions' transcripts never reach the owner's daemon and no Claude login is
  involved): `node test/fake-provider/cli.ts setup --daemon 127.0.0.1:<its local port>` adds "Fake
  OpenRouter" (key `fake-or-key`, every model `fake-model`). It refuses port 47322.
- `cli.ts fault <kind> [--sid …] [--times n] [--for 10m] [--retry-after s] [--window min|day]`:
  `credits` (402), `budget` (403, the key's cap), `in_flight` (402, a minute), `rate` (429 with its
  reset; `--window day` names the daily free-model limit), `rate_short`, `rate_bare`, `key` (401),
  `forbidden` (403), `overloaded` (529), `slow`, `cut`, `error_event`, `limit_sub` (the
  subscription's own 429). `clear`, `log`, `balance <usd|none|401>`.
- Claude Code (2.1.300) keeps only an error's message in its transcript ("API Error: 403 Key limit
  exceeded"), not the body's metadata or headers, so the daemon reads limits from those words; the
  recordings are in `daemon/test/fixtures/provider-errors/`.

## Codex (a second agent, without touching the owner's)
The daemon is a client of Codex's own shared app-server (`codex app-server daemon version` names its
socket), which the owner's Codex uses too, so a test daemon's Codex sessions are real Codex threads
on his account: title them "[Hypernova test]", and archive them afterwards (`thread/archive`, or
`codex archive <id>`).
- `daemon/test/fake-codex/server.ts` is a stand-in app-server for tests (it plays a turn out by
  itself and can push approvals); `HYPERNOVA_CODEX` points the daemon at a codex binary (the fake's
  script answers `--version`) and `HYPERNOVA_CODEX_SOCKET` at a socket, skipping the real daemon.
- A test daemon with Codex on: `{"codex": true}` in its `settings.json`, or
  `POST /local/settings {"codex": true}`. Its `<support>/bin/hn run-codex` is the shell's `codex`.
- A Mac with Codex and no Claude Code: run the daemon with a scratch `HOME` and `PATH=/usr/bin:/bin`
  (and no `CLAUDE_BIN`), so neither its usual places nor the login shell find `claude`
  (`daemon/test/codex-only.test.ts` does this). `snapshot.claude.available` is then false.
- Codex's share of Statistics is `<support>/codex-stats.json`, counted as turns end; a test daemon's
  `projectsRoot` in the scratchpad keeps its test folder out of the owner's projects.
- A terminal opened from the phone accepts Codex's "Trust this folder?", which Codex saves in
  `~/.codex/config.toml` (`[projects."<folder>"]`): remove scratch folders' entries afterwards.
- `codex app-server generate-ts --out <dir>` writes the installed version's protocol types: check a
  new Codex against them (docs/specs/codex-app-server.md).
