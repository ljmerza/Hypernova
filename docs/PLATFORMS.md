# Hypernova on more platforms

Research for the owner's line of 2026-10-15: "I don't want to limit this to platforms for now
because we will lose potential audience" (DECISIONS, "More platforms, planned before built").

This began as a plan only: nothing here was built or migrated when it was written, except where a
section says so ("The shared module", the Linux host). Read it, pick
what you want, and then we start. Facts about outside things (Apple, Kotlin, Tauri) carry a
source link. Facts about our own code come from reading this repo on 2026-10-16.

## The short answer

**Do the web app first, then iOS, with the Linux and Windows hosts in between and after.**

- **Web app.** A page the Mac's own daemon serves. Open it in any browser, or put it on the
  home screen like an app. About 5 weeks. It reaches iPhone, iPad, Windows, Linux and every
  other Android in one go, with no App Store and no review.
- **iOS app.** Share the Android app's screens with Kotlin Multiplatform (Compose
  Multiplatform), instead of writing it all again in Swift. About 10 weeks. It is the real
  answer for iPhone users, but it is the biggest job here.
- **Linux and Windows hosts.** Today the "host" has to be a Mac. A small platform layer in the
  daemon (about 2 to 3 days of it is already planned for Linux, in `ON-DEVICE.md`) lets the
  daemon run on a Linux or Windows computer. A tray app for each is the second half.

The audience argument has two sides: the people who hold the **phone** (Android now; iPhone is
most of the rest) and the people who own the **computer** that runs Claude Code (Mac now;
Windows and Linux are most of the rest). The web app helps the first side fast. The hosts help
the second.

## The shared module (step 1 of the desktop plan, done 2026-11-01)

The owner chose a desktop app that connects, lists sessions and approves, on Android's Kotlin and for
Android and the desktop JVM only (docs/DECISIONS.md, "A shared Android and desktop module"). Step 1 moved
the data layer into a Gradle module of its own, with the phone app's behaviour unchanged.

**Layout**, all under `android/` (the Gradle root, so CI's `android/**` paths and the release need nothing new):

| Where | What |
|---|---|
| `shared/src/commonMain/kotlin/.../data/` | The data layer, 74 files, package `com.prudnikweb.hypernova.data` as before: `LiveHub`, `Core`, `LanRoute`, `Protocol`, `Models`, `Api`, `Automations`, `ChatRepo`, `ChangesModel`, the words. `Seams.kt` is what it asks of the platform. `Str.kt` names the strings it uses. |
| `shared/src/commonTest/` | Plain tests (run on both targets), and the off-Android device for the hub's tests: `live/TestDevice.kt`, `live/HubRig.kt`. |
| `shared/src/hubTest/` | The tests of the real `LiveHub` against `FakeDaemon` that need nothing of Android. They compile twice: in `:shared` on `TestDevice`, and in `:app` on Robolectric with the app's own `HubRig`, so Android's side of every seam stays under them. |
| `app/src/main/java/.../data/` | What is truly Android: `AndroidPlatform.kt` (the seams), `StringIds.kt` (`Str` to `R.string`), `ComputerResources`, `AndroidCore`, `AndroidNotifier`, `AutomationNotes`, `HubViewModel`, `NotifyActions`, `AppUpdater`, `Pasted`, `Picked` (with `Thumbs`), `PhoneDictation`, `ContinuousDictation`, `SpeechPlayer`, `SpeakingNotice`, `MarkReadOnScreen`, `BackpackExtend`. |

**The toolchain**, checked by building it (the warning in section 2 about Kotlin 2.4.21): Kotlin 2.4.21, AGP 9.2.0 with
its `com.android.kotlin.multiplatform.library` plugin and `jvm("desktop")` all work together. JetBrains'
`org.jetbrains.compose.runtime:runtime:1.12.1` gives common code `mutableStateOf`, `Immutable` and snapshots on
both targets. On Android it resolves to `androidx.compose.runtime` 1.12.1, the version Haze already brought in: the
app's runtime classpath stayed the same 133 modules when `:shared` was added. With only JVM targets, `commonMain`
sees the JDK, OkHttp and org.json (the platform's on Android, the library on desktop) as they are. The Compose
compiler plugin runs on `:shared` too, so its classes keep the stability the screens read. `:app` marks `:shared` as a
Kotlin friend: without it, a nullable property checked on one line isn't known non-null on the next across
the module line (dozens of places).

**The seams** (`Seams.kt`), each what the code called on Android before, with the same names where it can be:
`Strings` (with `Str`, `Plural`), `Storage`/`Prefs`/`PrefsEditor`, `Ticks`, `MainThread`, `Log`, `AppInfo`, `DeviceInfo`,
`NetworkWatcher`, `ScreenWatcher`, `RetryAlarm`, `DeviceKeys`, `SpeechOutput`, `AutomationNotifier`, `Notifier`,
`BackgroundLink`, with `Platform` to hand them over and `Watch` to stop a watch. `ComputerWords` is now plain Kotlin;
its Android half, the Resources that reword themselves, is `ComputerResources`. `readableLine` and the markdown table
parser came down from `ui/components`, and `ChatRepo` and `ChangesRepo` from `ui`, so `data` imports nothing of `ui`.

**What is left for step 2**, the desktop app itself:
- A `Platform`: every seam above. There are no desktop implementations yet, not even stubs, since nothing needs them to
  compile. `TestDevice` shows the shape of each one.
- `kotlinx-coroutines-swing` (or another `Dispatchers.Main`) on its classpath: `Core.scope` runs on the main dispatcher,
  and `Core` won't load without one.
- Words: read the same `res/values` files (the tests' `XmlStrings` does) or move them to shared resources.
- Where a Linux, Windows or Mac desktop keeps its key and pairing: `DeviceKeys` and `Storage`.
- The screens: nothing of `ui/` moved. `HubViewModel` is still Android's.

## 0. What we have to build on

This is the part that makes the plan cheap in some places and costly in others.

```
 Android app (Kotlin, Compose) ──┐
 Lens (TypeScript, in a WebView) ─┼── http + WebSocket + device token ──▶ daemon (Node 24+)
 Mac menu bar app (SwiftUI) ──────┘      (docs/PROTOCOL.md)                 │
                                                                           └─▶ holder ─PTY─▶ claude
```

- **One contract.** Every app talks to the daemon the same way (`docs/PROTOCOL.md`): REST,
  one live WebSocket (`/v1/ws`), a bearer token from pairing. A new app is a new client of
  that. The daemon doesn't change much for it.
- **A browser already works as a client.** The daemon sends the open CORS headers a browser
  needs, accepts the token as `?token=` on a WebSocket (a browser can't set a header there),
  and hands a terminal a one-minute ticket URL. All three were added for Lens, which is a web
  app running in the glasses' WebView. So the protocol is already browser-ready.
- **The daemon is mostly portable.** About 12,000 lines of TypeScript. The Mac-only parts are
  few and sit in known files: `battery.ts` (`pmset`, `ioreg`), `awake.ts` (`caffeinate`,
  `pmset`), `hooks.ts` and `util.ts` (`~/Library` folders, `zsh`), `shots.ts` (Chrome as a Mac
  app), `speech.ts` (`say`), `terms.ts` ("Open on the Mac"). `ON-DEVICE.md`, section 4, lists
  them one by one and ran the rest of the daemon on Linux arm64 with no changes.
- **The Android app splits cleanly in two.** About 6,300 lines in `data/` (models, the live
  hub, pairing, stats, items) and about 16,400 in `ui/` (screens, the cosmos effects). Only 31
  of its 64 Kotlin files touch Android classes directly, and the cosmos effects are drawn with
  Compose's own drawing code, not Android views. That is why iOS through shared Compose is
  realistic (section 2).
- **The look is already written for the web.** `skills/hyper-cosmos/assets/web/` holds
  `hyper-cosmos.css`, `cosmos.js` (the living background) and a React version
  (`DigitalCosmos.tsx`, `GrainField.tsx`). The web app starts with the right look on day one.

Lens has a ready-made browser client of the daemon (`lens/src/data/api.ts`, `hub.ts`). Since
Lens became Apache-2.0 like the rest of the repo (2026-10-18, it dropped Hyperscribe Companion's
AGPL client), the web app can start from it instead of writing one fresh from `PROTOCOL.md`.

## 1. A web app, served by your own Mac

**The idea.** The daemon already runs on your Mac and already serves a few pages (`/pair`,
`/download/android`, previews). Add one more: the whole control room as a web page at
`https://<your-mac>.ts.net/`. On any phone or computer on your Tailscale network, open it in a
browser, or "Add to Home Screen" and it looks and acts like an app.

### What it reuses
- **The protocol.** All of it, unchanged: sessions, approvals, chat, stats, files,
  attachments, awake, battery, Telescope screenshots.
- **The look.** `hyper-cosmos.css`, `cosmos.js`, and the same tokens as the Android app.
- **Tailscale, as today.** `tailscale serve` already gives the daemon a real HTTPS address
  with a public certificate. That matters: browsers only allow the install, the background
  worker and push on HTTPS ([MDN, Progressive web apps](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps)).
  An address like `http://192.168.x.x` would not qualify.
- **The terminal.** The daemon keeps each terminal's screen in a headless xterm, so a web
  page only needs xterm.js to draw it. Nothing new on the daemon side.

### What is new
1. **Signing in.** Use the pairing we have. The Mac app shows a QR code; scanning it opens a
   page that today hands over to the Android app. For the web it simply stays on that page:
   it calls `POST /v1/pair` with the code and keeps the device token. Two things to decide:
   - Where the token lives. `localStorage` is simplest. The page and the API share one
     origin, load no outside scripts, and never render raw HTML from Claude, so the usual
     "a script steals the token" risk is small. Still, the token would be the key to a
     machine that runs commands, so keep it in IndexedDB behind a service worker only if the
     review later asks for it.
   - Pairing from the same computer. The Mac app's own pairing page needs no QR code when the
     browser is on the Mac itself.
2. **A service worker and a manifest.** These two small files make it installable and let it
   open offline to a "Mac is away" screen instead of a browser error.
3. **Push (Web Push).** The daemon would hold a signing key pair (VAPID, a standard for
   this) and send a message through the browser maker's push server: Apple's, Google's or
   Mozilla's. The phone's browser wakes the service worker, which shows the notification.
   - This **breaks the "no Google, no third party" line** in DECISIONS ("No Firebase for
     now"), so it must be opt-in. What goes through those servers is encrypted, but the fact
     that a message was sent isn't hidden. Same answer as LATER L1: send only a content-free
     "something happened" ping, and let the page fetch the real text from the Mac.
   - It is also a good fit for L12 (notifications while the app is closed), because a browser
     push needs no service of ours running on the phone.
4. **The screens.** Home with the sky, session chat, approvals, terminal, stats, files,
   settings. This is the bulk of the work: it is a rewrite of the Android UI in HTML, CSS and
   TypeScript, not a port. Pick a small framework (Preact, Svelte or plain web components) so
   the page stays light and the daemon needs no build tools at runtime.

### What works and what doesn't
| Thing | In a browser |
|---|---|
| Chat, approvals, menus as cards, stats, files, attachments (a file picker and the camera) | Yes |
| Terminal view | Yes, with xterm.js |
| Cosmos background and grain | Yes, the web assets are written already |
| Notifications while closed | Yes, through Web Push, with limits below |
| Haptics (vibration on a supernova) | Android Chrome yes; iPhone no |
| Home screen widgets | No (a browser page cannot be a widget) |
| A permanent "live sessions" notification | No (that is an Android foreground service) |
| Staying connected in the background | No. The page sleeps; push wakes it |

### Install and push on iPhone, as of today
- Web Push on iPhone works **only for a page added to the Home Screen**, from iOS 16.4 on. In a
  normal Safari tab there is no push ([OneSignal's guide](https://documentation.onesignal.com/docs/en/web-push-for-ios),
  [Pushpad](https://pushpad.xyz/blog/ios-special-requirements-for-web-push-notifications)).
  The permission question must also come from a tap, not on load.
- Since iOS 26, a site added to the Home Screen **opens as a web app by default**, with an
  "Open as web app" switch the user can turn off
  ([WebKit, WWDC25 news](https://webkit.org/?p=16993), [heise](https://heise.de/-10749652)).
  So installing is now just Share, then Add to Home Screen. The manifest is still worth having
  for the icon, the name and the colours.
- Reports say iPhone web push is sometimes unreliable (works, then stops). Plan for an in-page
  "test notification" button and a note in Settings.

### Effort
**About 5 weeks** for one developer with Claude, in this order: the shell, pairing, install and
the live socket (1 week); chat, approvals and the terminal (2 weeks); Home with the sky, stats,
files and attachments (1.5 weeks); Web Push, the offline screen and polish (0.5 week). The
daemon side is about 3 days: serve the files, the pairing page's web mode, push keys.

### Risks
- A web page cannot keep a socket open in the background, so "live" means "live while open".
  Push has to cover the rest, and push is the part Apple is least reliable about.
- Two UIs to keep in step (Android in Compose, web in HTML). Shared tests against the
  protocol, and a short "parity" list in the docs, keep this honest.
- The Mac must be on and on Tailscale, as for the phone app. For a first-time iPhone user,
  Tailscale itself is the hardest step (an app, a login, a key that can expire; LATER L13).

## 2. iOS through Kotlin / Compose Multiplatform

**The idea.** Keep one set of Kotlin screens and data code and build it for Android and for
iPhone. JetBrains' Compose Multiplatform draws the same Compose screens on iOS.

### Where Compose Multiplatform stands
- **Stable on iOS since version 1.8.0, May 2025**, with JetBrains calling it production-ready
  ([JetBrains announcement](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)).
- The newest release the compatibility page lists is **1.12.2**, for iOS 14 and up, with
  desktop on macOS 13, Windows 10 and Ubuntu 20.04, and web on browsers with WasmGC
  ([compatibility table](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)).
- That page doesn't print a stability label, and the search found no 2026 release notes, so
  confirm the current state on the day we start. Our app uses Kotlin 2.4.21, which is newer
  than what that page recommends; check the two work together first.

### What shares well today
Measured on this repo:
- **`data/`** (about 6,300 lines): models, the protocol, the live hub, items, stats, agents,
  demo data. Mostly plain Kotlin. Needs two swaps: OkHttp (Android and desktop only) becomes
  Ktor, which runs everywhere, and `java.*` calls (time, files) move to Kotlin's own libraries.
  Eight files in `data/` import Android classes (`AppModel`, `Core`, `LiveHub`, `Pairing`,
  `Awake`, `Notices`, `Attachments`, `FakeHub`); those are the ones to split.
- **`ui/`** (about 16,400 lines): the screens and the cosmos effects. The effects are drawn
  with Compose's own `Canvas`, which Compose Multiplatform has on iOS. The blur library Haze
  (2.0.1 here) supports iOS too.
- **Fonts, colours and tokens:** shared as resources.

### What is Android-only, and what replaces it
| Android thing | On iOS |
|---|---|
| Home screen widgets (`widget/`, 5 files) | **Rewrite in SwiftUI** (WidgetKit). Widgets can't be Compose. |
| Notifications and the permanent connection notification (`ConnectionService`) | iOS has **no foreground service**. A closed app can't hold a socket. Push (APNs) is the only way, so this needs a relay: Apple's push needs a server key, so LATER L1's "tiny relay" stops being optional on iPhone. |
| AGSL shaders (`ui/fx/Lens.kt`, the only file using `RuntimeShader`) | Skia (inside Compose Multiplatform) has its own shader language, SkSL, close to AGSL; a port of one file is days. Or draw it another way. |
| Haptics, camera, file picker, share sheet, deep link `hypernova://pair` | Small platform bits, written once per side with Kotlin's `expect`/`actual`. |
| In-app WebView for previews (`WebScreen.kt`) | WKWebView, wrapped the same way. |
| The `hypernova://` pairing link and QR scan | Works; iPhone's camera opens the https link, which hands over to the app. |

So the honest count: **most of the screens and the logic come across; widgets, the always-on
connection and the notification path do not.** On iPhone the app is a "look when you open it"
app plus push.

### App Store review, for a remote-control app
This is the part to take seriously. I read the current guidelines
([App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)).
- **4.2.7 "Remote Desktop Clients".** If a reviewer files us as a remote-desktop app, the rules
  are strict: only a user-owned computer, and **"both the host device and client must be
  connected on a local and LAN-based network"**. Tailscale from anywhere is not LAN. We have
  a good argument that it doesn't apply: Hypernova doesn't mirror the Mac's screen; it shows
  its own native screens for the daemon's data. Say so plainly in the review notes.
  Telescope ("show me" screenshots) is the one feature that looks like mirroring; be ready
  to describe it as a still picture of a web page the user asked for.
- **4.2 "Minimum functionality".** A repackaged website is rejected. A native app with the
  sky, approvals and a terminal is far from that; the web app, by contrast, could never go
  to the store.
- **2.5.2, no downloaded code that changes features.** We download none. The daemon runs on
  the user's own Mac, not in the app. Keep it that way: the app must never fetch UI from the
  Mac. (The "preview" WebView shows the user's own dev server, which is content, not our
  code.)
- **5.1.1 privacy.** A privacy policy link in the app and the store page. The app collects
  nothing; say that. Note what the Web Push or APNs relay would see.
- **Review needs a working setup.** Apple will install the app, and a reviewer has no Mac
  running our daemon. Offer a **demo mode** (we already have one: `FakeHub`, `DemoStats`) and
  say how to switch it on in the review notes. This is the usual reason apps like this are
  rejected the first time.
- **A paid developer account** (about 99 USD a year) and a Mac with Xcode. We have the Mac;
  CLAUDE.md says the Mac app builds with Command Line Tools only, so Xcode itself is a new
  large install.

### The SwiftUI-native alternative, and its cost
Write a second app in SwiftUI. The Mac app already shows the way: it has its own client
(`DaemonClient.swift`, `LiveFeed.swift`, `Models.swift`) and the look in Swift
(`HyperCosmos.swift`; `skills/hyper-cosmos/assets/swiftui/`).
- **For:** the most "iPhone" result, real widgets and Live Activities without a bridge,
  Metal shaders directly, no Kotlin-on-iOS surprises.
- **Against:** every screen again, then a second copy of every future change. The Android
  app is about 22,000 lines; a SwiftUI one is the same job again, **about 16 to 20 weeks**
  against **about 10** for the shared route.
- **My pick:** the shared route, with the widgets and the push code in Swift. Revisit only if
  Compose on iOS feels wrong in the first two weeks (the first spike in section 4 tests that).

### Effort
**About 10 weeks**: splitting `data/` and `ui/` into a shared module (2 weeks, mostly moving
files and swapping OkHttp for Ktor); making the iOS target build and run the pairing, Home and
chat (2 weeks); the other screens, the terminal view and previews (2 weeks); push and the relay
(2 weeks); the SwiftUI widgets (1 week); TestFlight, review notes, fixes (1 week). The Android
app keeps working throughout, so each step ships to the owner's phone as before.

### Risks
- Compose Multiplatform on iOS is newer than Android's: scrolling feel, text input (the
  composer) and the keyboard are the usual rough edges. Test the composer first.
- The relay for APNs is a service somebody has to run. It is the one place where "everything
  stays on your Mac" gets a small asterisk.
- No foreground service means no live sky when the app is closed. Set that expectation in the
  store text.

## 3. Linux and Windows hosts

**Status (2026-10-17):** the Linux host is built, command line only (no tray app): the platform
layer is `daemon/src/platform.ts`, the `hypernova` command `daemon/src/cli/`, and how to install
and run it [LINUX.md](LINUX.md). Windows is still only this research.

**Status (2026-10-22):** a Windows PC can run the **Linux** host inside WSL 2 (Ubuntu) or in a
Docker Desktop container, documented as **experimental** in
[INSTALL.md, "4. Windows (experimental)"](INSTALL.md#4-windows-experimental-wsl2-or-docker-desktop).
The daemon knows when it runs in WSL (`isWsl()` and the networking mode from `wslinfo` in
`platform.ts`): behind WSL's NAT the LAN listener is off by default and its unreachable addresses
are never advertised, so the phone comes in over the built-in Tailscale node; `hypernova doctor`,
`pair`, `lan` and `setup` explain systemd, keeping WSL running and mirrored networking
(`daemon/src/cli/wsl.ts`). Verified only on Linux and by unit tests that simulate WSL; nobody has
tried it on a real Windows PC (LATER L93). The native Windows host and its tray app below are still
only this plan (LATER L92).

**Status (2026-11-01):** the server installs on a Mac again, without an app: `scripts/install-macos.sh`, the
LaunchAgent in `daemon/src/cli/launchd.ts` ([INSTALL.md, "On a Mac"](INSTALL.md#on-a-mac-untested-on-a-real-one);
DECISIONS, "The server's packages"). This page's Mac facts, checked against the code that day: state in
`~/Library/Application Support/Hypernova`, logs in `~/Library/Logs/Hypernova`, `HYPERNOVA_SUPPORT` and
`HYPERNOVA_LOGS` winning there as on Linux (`XDG_*` is Linux's only), the shell block in `~/.zshrc` (zsh only;
it now also adds `~/.local/bin` to PATH), and macOS's folder prompts naming `node`. One edge, for test copies:
the status line's tap (`teeBlock` in `hooks.ts`) writes to the Library folder by name, not `HYPERNOVA_SUPPORT`.
Only CI's Mac runners have run it.

**The idea.** Let the daemon run on a Linux or Windows computer, and give those computers a
small tray app like the Mac's menu bar app. Claude Code itself already runs on both
([ON-DEVICE.md](ON-DEVICE.md) lists the official builds: `linux-x64`, `linux-arm64`,
`win32-x64`, `win32-arm64`).

### A platform layer in the daemon
Today the Mac-only calls are scattered. The plan is one `platform/` folder with a small
interface and one file per system (`mac.ts`, `linux.ts`, `windows.ts`). The daemon calls the
interface and never `pmset` directly.

| Job | Mac today | Linux | Windows |
|---|---|---|---|
| Battery and "minutes left" | `pmset`, `ioreg` | `/sys/class/power_supply` (a plain file read) or `upower` | `Get-CimInstance Win32_Battery` (PowerShell) |
| Keep awake while working | `caffeinate -i -s -w <pid>` | `systemd-inhibit --what=sleep` held by a child process | the `SetThreadExecutionState` call, via a tiny helper process (it must stay running) |
| Keep awake with the lid closed (Backpack) | `pmset disablesleep`, with a sudoers rule | `HandleLidSwitch=ignore` through a systemd-logind inhibitor of type `handle-lid-switch` | A power-plan setting (`powercfg`) needing admin; probably "not at first" |
| Terminals (PTY) | `node-pty` and the holder | same, `node-pty` builds on Linux (proven in `ON-DEVICE.md`) | `node-pty` uses ConPTY; the holder's process and signal tricks (`ps`, `SIGWINCH`, detached children) need rework. The hardest line here. |
| Screenshots for Telescope | headless Chrome found in `/Applications` | Chrome or Chromium on `PATH`, usually with `--no-sandbox` | Chrome in `Program Files` |
| Read aloud | `say` | `espeak-ng` or Piper | Windows speech through PowerShell |
| Windows of other apps ("show me" for native windows) | `WindowCapture.swift` | skip | skip |
| Run at login, restart if it dies | LaunchAgent | a systemd **user** service | a Scheduled Task at logon (no admin needed) |
| Where files live | `~/Library/Application Support/Hypernova` | `~/.local/share/hypernova`, logs in `~/.local/state/hypernova` | `%APPDATA%\Hypernova` |
| Hooks and the `claude` shell function | zsh | bash and zsh (L15 becomes real) | PowerShell profile |
| Tailscale | the Mac app helps | the `tailscale` command | the Tailscale app |

What the layer does **not** need to chase on day one: window capture, Backpack with the lid
closed on Windows, "Open on the Mac" (hide the button; the phone is told which host it is).

**Effort.** Linux: **2 to 3 weeks**, and it is the cheaper half because `ON-DEVICE.md` already
did the reading and the container test; the layer written for a Linux server is the same one a
desktop uses (this is also the first step of LATER L26 and L27). Windows: **4 to 6 weeks**,
mostly the terminal holder, paths with spaces and backslashes, and PowerShell instead of zsh.
Do Linux first and Windows will go faster for the shape it leaves.

### The tray app
Three honest options. All three show an icon, a small popover or menu, a setup window and the
pairing QR code, the same as the Mac app.

| | Compose Desktop | Tauri 2 | Native on each system |
|---|---|---|---|
| What it is | The same Kotlin and Compose as the phone app, on the JVM | A small shell (Rust) around a web page | SwiftUI on Mac (have it), GTK on Linux, WinUI on Windows |
| Reuses | Phone screens and the cosmos effects, direct | The web app from section 1, direct | Nothing |
| Size | A JVM comes with it, roughly 50 to 100 MB | Small, uses the system's web view | Smallest |
| Tray | Has a tray API, but a popover under the icon is hand-built | A tray icon with a menu is built in; on **Linux, click events on the icon are not delivered**, only the right-click menu works ([Tauri docs](https://v2.tauri.app/learn/system-tray/)) | Full control |
| Look | Same cosmos look as the phone | Same cosmos look as the web app | Cosmos look written three times |
| Effort | 3 to 4 weeks for both systems | 2 to 3 weeks for both systems, **after** the web app exists | 5 to 7 weeks per system |

**My pick: Tauri, wrapping the web app from section 1.** The tray window is then the web app
at a small size, plus a few extra pages (Setup, Providers) that only the host needs. One
UI covers phone-in-a-browser and the desktop host. Rust is a new thing in the repo, but the
shell stays tiny, and Claude can write it. Compose Desktop is the better pick only if iOS goes
first and we want everything Kotlin. A heads-up for Linux: GNOME has no tray at all unless the
user adds an extension, so the web app should be openable from the browser too, and the CLI
`hn` is the fallback for status.

### Risks
- Windows terminals are where "works on my machine" turns into a long tail.
- Claude Code on Windows has its own quirks (shell choice, Git for Windows). We follow what
  Anthropic ships and don't promise more.
- Signing and trust prompts: unsigned Windows installers show a SmartScreen warning, like the
  Gatekeeper one on the Mac (LATER L6). A signing certificate costs money every year.

## 4. Recommended order

One developer with Claude, full time. These are rough; the first weeks of each step show if
the guess was right.

| # | Step | Weeks | What you get | Why here |
|---|---|---|---|---|
| 0 | **Spikes** (2 to 3 days each, in parallel) | 1 | Three small answers: (a) the pairing page works in a plain browser, (b) the Android `data/` code builds for iOS in a bare Compose Multiplatform project, (c) Web Push reaches an iPhone from the daemon | Cheap, and it tells us if the plan below is wrong before we pay for it |
| 1 | **Web app** | 5 | Everyone with a browser. iPhone users get something now | Biggest reach for the smallest cost; also the base of the desktop tray |
| 2 | **Daemon platform layer + Linux host** | 3 | Linux computers (and a Linux box or small server as a host; the first step of L26 and L27) | Cheap thanks to `ON-DEVICE.md`; makes the daemon honest about the Mac |
| 3 | **iOS app** | 10 | The App Store, widgets, real push | The big one. By now the web app has told us which screens matter |
| 4 | **Windows host, then Tauri tray for Linux and Windows** | 6 to 8 | Windows computers; trays | Last because the terminal work is the longest and its payoff is the least certain |

**Total: about 25 weeks** if run one after another, with no overlap, so six months of a
single developer. The web app and the Linux layer don't depend on each other and could run
side by side if there were two people.

### What to do first
1. **Decide four things** (a message is enough):
   - Web app first, as proposed?
   - Is Web Push through Apple's and Google's servers acceptable if it's opt-in? (It touches
     "No Firebase for now".)
   - Are you willing to pay for an Apple developer account (about 99 USD a year) when we reach
     step 3?
2. **Run the three spikes** (step 0). They need no decisions beyond the four above.
3. **Write one line in LATER** for what is parked here: widgets and always-on connection on
   iPhone, Windows window capture, and Backpack with the lid closed on Windows.

### Risks across the whole plan
- **Too many surfaces for one person.** Each platform adds a test pass for every release. The
  plan fights this by sharing code (the web app is the desktop tray; Compose is both phones)
  rather than by cutting platforms.
- **The hosted-by-you promise.** Everything runs on the user's own computer. Push on iPhone
  needs a small relay; that is the one honest exception, and the store page should say so.
- **Tailscale is the front door for all of it.** If a new user can't get Tailscale working in
  five minutes, none of this matters. LATER L11 (a LAN listener with a pinned certificate) and
  a good first-run screen deserve a look before the public release, whatever the platform.
- **Estimates.** Web and Linux are well known to us, so +/- 25 percent. iOS and Windows could
  each run 50 percent over.

## Sources
- [Compose Multiplatform 1.8.0, iOS is stable](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/) (JetBrains, May 2025)
- [Compose Multiplatform compatibility and versioning](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html) (version 1.12.2)
- [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) (4.2, 4.2.7, 2.5.2, 5.1.1)
- [WebKit, News from WWDC25](https://webkit.org/?p=16993) and [heise on iOS 26 web apps](https://heise.de/-10749652)
- [Web Push for iOS (OneSignal)](https://documentation.onesignal.com/docs/en/web-push-for-ios) and [Pushpad's iOS notes](https://pushpad.xyz/blog/ios-special-requirements-for-web-push-notifications)
- [Tauri 2, system tray](https://v2.tauri.app/learn/system-tray/)
- [MDN, Progressive web apps](https://developer.mozilla.org/en-US/docs/Web/Progressive_web_apps) (HTTPS needed to install)
- In this repo: `docs/PROTOCOL.md`, `docs/ON-DEVICE.md` (sections 1 and 4), `docs/DECISIONS.md`
  ("No Firebase for now", "More platforms"), `docs/LATER.md` (L1, L6, L11, L12, L13, L15, L26,
  L27).

Not checked: iOS 26's own Web Push behaviour (the sources I found describe iOS 16.4 onward and
the iOS 26 install change, not a new push rule), Compose Multiplatform's stability label today
(the version page gives none), and Tauri and Compose Desktop on Windows ARM. Check these in
the step 0 spikes.
