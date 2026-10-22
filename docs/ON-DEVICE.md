# Hypernova on the phone alone (no Mac)

Research for the owner's question of 2026-10-14: "Can't we run Claude Code in a kind of
Linux image or container on Android? Or something like Docker? So right on the device."
The goal: Hypernova works with no Mac at all. The phone runs Claude Code, the daemon and
the projects, and the app is the control room as it is today.

This is research only: it changed no product code. Two problems it found in the daemon were
fixed separately on 2026-10-14 (sections 3 and 4); the checklist fix is right for a Mac but still
leaves a gap on a phone (section 3, "Loopback"). Section 6 lists what was checked on the Mac and
how. Anything else carries a source link (section 10).

## The short answer

**Yes, without root, but as a Linux userland, not as Docker.** The phone can run a normal
Debian userland under `proot`: a program that fakes a Linux root file system in user space
by intercepting system calls. Claude Code's official linux-arm64 build runs there unchanged,
and so does the core of our daemon. The app would then connect to `127.0.0.1` on the same
phone instead of to a Mac over Tailscale, so the protocol stays the same and the phone becomes
one more host in the host switcher we already planned.

What was proven on the Mac, in Linux arm64 containers (Docker Desktop, kernel 6.12):

- Claude Code 2.1.300 for linux-arm64 starts in Debian 13 and Alpine 3.21, and under
  Termux's own `proot` (built from its current source). Its first-run screen draws in a
  terminal under proot exactly as it does natively.
- The parts of the Hypernova daemon that were tested run on Linux arm64 **with no code
  changes** (only the existing `HYPERNOVA_SUPPORT`, `HYPERNOVA_LOGS` and `CLAUDE_BIN`
  overrides): `npm ci` builds node-pty from source, `/v1/health` answers, requests without a
  token get 401, and `POST /v1/sessions` starts a real shared terminal (holder + PTY +
  `claude`). The same works with the whole daemon running under proot. That covers health,
  auth and sessions only. Other parts need the small platform layer in section 4; the usage
  meters, for one, would stay empty without it.

What is **not** proven: the phone itself. Android adds a system call filter (seccomp), SELinux,
a process killer and battery rules that a container on a Mac doesn't have. The one crash known
in detail (Bun calling `epoll_pwait2`, from Claude Code 2.1.181 on) came from Termux's glibc,
not from Android's filter, and Bun has fixed it (section 3). The first step of the plan
(section 7) is a half-day test on the owner's Nothing Phone 3.

**Docker itself is a no** on a stock phone: it needs kernel namespaces, cgroups and overlay
mounts, which Android doesn't give an ordinary app. It only works inside a full virtual
machine, and the Phone 3 can't run the built-in one (section 2.4).

## 1. How Claude Code is distributed today

### Native binaries since April 2026
- The installed version on the owner's Mac is 2.1.300 (`~/.local/bin/claude` links to
  `~/.local/share/claude/versions/2.1.300`, a 250 MB Mach-O arm64 binary).
- Up to **2.1.112** (2026-04-16) the npm package was plain JavaScript (`cli.js`) that ran on
  any Node 18+. That is why Claude Code used to install with one `npm i -g` inside Termux.
- From **2.1.113** (2026-04-17) npm only ships native binaries. The package
  `@anthropic-ai/claude-code` is now a small wrapper whose `postinstall` links in one of
  eight per-platform packages: `darwin-arm64`, `darwin-x64`, `linux-arm64`, `linux-x64`,
  `linux-arm64-musl`, `linux-x64-musl`, `win32-arm64`, `win32-x64`. Its `engines` asks for
  Node 22+, but only the installer uses Node; `claude` itself doesn't.
- The official installer (`curl -fsSL https://claude.ai/install.sh | bash`) downloads the
  same binaries from `downloads.claude.ai/claude-code-releases/<version>/<platform>/claude`,
  checked against a signed `manifest.json`. There are also apt, dnf and apk repositories.
  Officially supported Linux: Ubuntu 20.04+, Debian 10+, Alpine 3.19+, 4 GB RAM, x64 or ARM64.

### There is no Android build, though the code expects one
- On Termux, Node reports `process.platform === 'android'`. The npm wrapper's platform map
  already turns that into `linux-arm64-android`, but that package **doesn't exist** (npm 404),
  and the 2.1.300 release manifest has no Android entry either (the URL 404s). So
  `npm i -g @anthropic-ai/claude-code` in plain Termux "succeeds" and leaves a stub that
  prints "claude native binary not installed".
- Inside the linux-arm64 binary, the native installer has an `isAndroidEnvironment()` check
  that would pick `linux-arm64-android`; in this build it is compiled to `return false`. The
  embedded Bun (1.4.3) knows a `bun-linux-arm64-android` compile target, and the code already
  places its sockets under Termux's `$PREFIX/tmp` when `TERMUX_VERSION` is set. An official
  Android build looks technically close, but it isn't published. The upstream issue
  ([#50270](https://github.com/anthropics/claude-code/issues/50270), "v2.1.113+ broken on
  Termux/Android") has been open since 2026-04-18. An Anthropic collaborator
  [replied on 2026-04-23](https://github.com/anthropics/claude-code/issues/50270#issuecomment-4308968635):
  "We may add support for Android in the future", which needs "either a bun target for
  `android-arm64` or static musl"; until then, pin 2.1.112. That's a maybe, not a commitment,
  and the issue has no assignee.

### What the linux-arm64 binary needs (checked locally)
- `ELF 64-bit LSB executable, ARM aarch64`, dynamically linked, interpreter
  `/lib/ld-linux-aarch64.so.1`. It needs only `GLIBC_2.17` symbols (glibc from 2012), so any
  Debian or Ubuntu userland works.
- It is a Bun single-file executable ("Bun v1.4.3" inside). It is **not position-independent**
  (`ET_EXEC`), which is one reason it can't be loaded by Android's own linker.
- Its loadable segments are aligned to 64 KiB, so it runs on kernels with 4 KB or 16 KB pages
  (Android 15+ allows 16 KB-page devices).
- 266 MB on disk (120 MB compressed). The native installer keeps the two newest versions plus
  any running one, so budget about 0.8 GB.
- ripgrep is built in (`USE_BUILTIN_RIPGREP`, plus an embedded ugrep 7.8), so there's nothing
  to install for search. The musl build is the exception: the docs say to install ripgrep and
  set `USE_BUILTIN_RIPGREP=0`.
- The musl build (`/lib/ld-musl-aarch64.so.1`) also ran in Alpine.

### How it signs in on Linux
- On Linux the login lives in `~/.claude/.credentials.json`, mode 0600, in plain text. There is
  no keychain. (On a Mac it's the Keychain, with the same file as a fallback.)
- `/login` opens a browser at claude.ai. Claude Code listens on a local callback port; if the
  browser can't reach it, the page shows a code to paste at "Paste code here if prompted".
  Pressing `c` copies the login URL.
- `claude setup-token` makes a **one-year** OAuth token for the subscription, used through
  `CLAUDE_CODE_OAUTH_TOKEN`. It can make model requests but can't start Remote Control or use
  claude.ai connectors.
- `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN` and our provider profiles work the same as on
  the Mac.

## 2. Ways to run Linux on Android without root

| Option | Claude Code | Our daemon | On the Phone 3 | Verdict |
|---|---|---|---|---|
| Termux, native (bionic libc) | Official binary only with patching and shims | Termux's Node 26; Mac-only paths need fixing | Should work, untested | Fragile; community path |
| Termux + proot-distro Debian | Official installer, unchanged | Unchanged (verified on Linux) | Should work, untested | **Best for the prototype** |
| Our own proot userland inside Hypernova | Same as above | Same as above | Should work, untested | **Best for the product** |
| Android's Linux Terminal (AVF VM) | Official installer, unchanged | Unchanged | **Not available** (Snapdragon) | Not for us now |
| Docker / Podman | n/a | n/a | No kernel support | No |
| A QEMU VM with emulated CPU (Podroid) | Works | Works | Works, slowly | Too slow |

### 2.1 Termux, native
Termux is a terminal app with its own package manager, built against Android's libc (bionic).
Claude Code's binary wants glibc, so the community runs it through Termux's `glibc-runner`:
`patchelf` rewrites the binary's interpreter to Termux's glibc loader, and a launcher adds
shims. One maintained package ([gtbuchanan/claude-code-termux](https://github.com/gtbuchanan/claude-code-termux))
needs three, per its README: `uname` reporting a kernel older than 5.11 (to keep Bun off
`epoll_pwait2`, which "segfaults at startup" there,
[bun#32489](https://github.com/oven-sh/bun/issues/32489); the cause is Termux's glibc, and Bun
has fixed it since, see section 3), its own DNS resolver config, and an exec-path fix. Another
([ferrumclaudepilgrim/claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android))
also forces Google DNS and turns off Claude Code's auto-update in favour of its own updater.
Reports in #50270 differ by device and kernel: some phones refuse the patched binary, and the
fallback is pinning 2.1.112, the last JavaScript release, which by now is six months and
160 published versions behind. Old versions get cut off from new models (the binary carries a
"does not support this model; version … or newer is required" error).

For Hypernova this path has a second cost: our daemon would run on Termux's Node, where
`process.platform` is `android`. The Agent SDK's native packages don't install there, and the
status line tap hard-codes `/usr/bin/sed`, `/usr/bin/head` and `/bin/mv`, which don't exist in
Termux.

### 2.2 Termux + proot-distro (Debian or Ubuntu)
`proot-distro` installs a full distribution (now from any Docker/OCI image) and runs it under
`proot`. Inside it, the system looks like normal Linux (`process.platform` is `linux`), so the
**official installer works unchanged**. A community install guide verified this path on a
Pixel 10 Pro (Android 17) and a Galaxy S26 Ultra (Android 16) on 2026-05-16, at about 2 GB
including Claude Code
([install guide](https://cdn.jsdelivr.net/gh/ferrumclaudepilgrim/claude-code-android@main/docs/install.md)).
That predates Claude Code 2.1.181, the first release with Bun 1.4.0 (section 3). Reports after
it in #50270 (2026-06-21) fit section 3: the guide's author
[ran 2.1.185 under proot-distro](https://github.com/anthropics/claude-code/issues/50270#issuecomment-4762625271)
"with no problems", while
[one user's proot setup](https://github.com/anthropics/claude-code/issues/50270#issuecomment-4761970865)
crashed from 2.1.181 until proot reported an older kernel (`proot -k 5.10.0`). That setup was
termux-chroot's binds (Termux's own files, not a distribution) running the patched binary on
Termux's glibc, so it hit that library's `epoll_pwait2` shim. It says nothing about a
proot-distro Debian, where Debian's own glibc runs the binary.

The costs, from the proot-distro README: every system call goes through `ptrace`, so
file-heavy work (`npm install`, compiling) is "noticeably slower" than native; there are no
real namespaces, cgroups, FUSE, systemd or root; and proot can't run inside another proot.
Apart from the speed, none of these matter to Claude Code. Our verified container test shows
the daemon, node-pty, the holder and `claude` all work under Termux's proot.

Speed is also what people using it complain about. The first report in #50270 (2026-04-18)
found proot-distro "too slow for interactive use", and a user who moved from proot to a native
Termux setup found the native one "noticeably smoother … no ptrace tax, instant keyboard, no
startup lag" (2026-06-22). The guide also warns that on Samsung's One UI 8, proot slows down
noticeably while Termux is in the background, and suggests keeping Termux in the foreground or
split-screen.

A side benefit: proot-distro installs OCI images straight from a registry, so a ready-made
"Hypernova Linux" image (Debian + Node + daemon, with Claude Code installed at first run) is a
cheap way to ship the prototype.

### 2.3 Our own userland inside Hypernova (the UserLAnd model)
Instead of asking people to install Termux, the Hypernova app can carry `proot` itself.
[UserLAnd](https://github.com/CypherpunkArmory/UserLAnd) does exactly this on Google Play
today, at targetSdk 37: it ships `proot`, `busybox` and friends as fake `lib*.so` files
(`useLegacyPackaging true`, so Android unpacks them into the app's native library folder,
where running them is allowed) and downloads the distribution's root file system into its
private storage. proot then loads the guest's programs itself, so Android never has to
`exec()` a file from writable storage.

This gives the best experience (one app, one setup screen, our own supervision of the
processes) and is the most work: the root file system, its updates, the login flow and the
process lifetime are all ours to get right.

### 2.4 Android's Linux Terminal (AVF)
Android 15 QPR2 and 16 include a "Linux development environment" in Developer options: a
real Debian VM with its own kernel, run by the Android Virtualization Framework. Docker works
inside it, and so does the official installer.

It's not an option for the owner's phone:
- The Terminal app needs **non-protected VMs**. Qualcomm's chips run their own hypervisor
  (Gunyah) and only allow protected VMs, so Snapdragon phones don't get the feature; Android
  Authority confirmed this for the Snapdragon 8 Elite Gen 5, and Qualcomm only said it is
  "poised to support" it "should market demand arise". Supported today: Google Tensor
  (Pixel 6 and later), Samsung Exynos and reportedly MediaTek.
- The Nothing Phone 3 uses a **Snapdragon 8s Gen 4** and has had Nothing OS 4.0 (Android 16)
  since November 2025. No public source mentions the feature on Nothing phones. Expect the
  toggle to be missing; the owner can check in Settings › System › Developer options in ten
  seconds.
- Even where it exists, a third-party app can't start these VMs on its own:
  `MANAGE_VIRTUAL_MACHINE` is a signature/privileged/development permission, so only a
  sideloaded app that is granted it over adb (`adb shell pm grant`) could. Without that grant,
  Hypernova could only talk to a VM the Terminal app started, through its port forwarding
  (ports 1024 and up, user-approved, reported as flaky in early builds). None of this matters
  on the Phone 3, which has no non-protected VMs to start.

### 2.5 Containers and VMs
- **Docker / Podman on the phone itself:** no. The daemon needs kernel namespaces, cgroups and
  overlay mounts. Android's app sandbox (SELinux and seccomp) doesn't allow them, and proot is
  path translation, not isolation.
- **A full VM in software:** [Podroid](https://github.com/ExTV/Podroid) boots an Alpine VM with
  Podman and Docker under QEMU, without root. Without AVF the CPU is emulated (TCG), which is
  several times slower than native. Fine for a test container, too slow for an agent that runs
  thousands of commands.
- We don't need Docker for Claude Code. A userland is enough, and the "image" idea survives as
  an OCI image that proot-distro installs (2.2).

## 3. Android limits that shape the design

### Running programs from app storage
- Since Android 10, an app targeting API 29+ **can't `execve()` files in its own writable
  storage** (W^X). Google's advice is to run only code that came inside the APK.
- Termux from F-Droid/GitHub stays on targetSdk 28 to keep the old behaviour. Termux says that
  exemption may end: Android 14 already warns about apps below targetSdk 28, and the bar is
  expected to move to 29.
- Termux's Google Play build targets SDK 37 and runs every ELF file through
  `/system/bin/linker64 <file>`, with an `LD_PRELOAD` library that does the same for child
  processes (see `TermuxShellUtils.java` in termux-play-store/termux-apps). Termux itself calls
  this trick likely to be patched one day.
- UserLAnd's approach (2.3) uses only what Android documents as allowed: executables shipped in
  the APK's native library folder. proot's loader then maps the guest's binaries into memory
  itself. That mapping works today on targetSdk 37 (UserLAnd and Termux-on-Play both depend on
  it), but it's a behaviour, not a promise.
- Hypernova targets SDK 36, so an embedded userland must use the UserLAnd model.

### Staying alive in the background
- **Foreground service.** Hypernova already runs one, typed `specialUse`. That type has no time
  limit; `dataSync` and `mediaProcessing` are capped at 6 hours a day for apps targeting
  Android 15. A long agent run needs `specialUse` plus a partial wake lock while a session
  works. The app doesn't declare `WAKE_LOCK` yet; Step 2 in section 7 adds it.
- **Phantom process killer** (Android 12+). Every process an app forks counts. Android allows 32
  across **all apps together**. Beyond that it kills the children of the app that ranks lowest
  first (the highest oom adj), and the oldest first among equals. It also kills a child process
  that uses too much CPU while its app is in the background (checked every 5 minutes). A
  foreground service ranks our app higher, so ours go last, but they can still go. Claude Code
  forks a lot (bash, git, rg, node, test runners), so this matters. Two ways to turn it off:
  - `adb shell settings put global settings_enable_monitor_phantom_procs false` works on 12L
    and later, Android 14+ included, and stays set.
  - On Android 14+ there is also a switch, Developer options › **Disable child process
    restrictions**, but it turns itself back off if Developer options is turned off.

  The Phone 3 is on Android 16, so both work; the adb command is the sturdier one. Setup has to
  explain it, the way the Mac setup explains Tailscale.
- Termux sessions need `termux-wake-lock` and an exemption from battery optimisation, or Doze
  pauses them with the screen off.

### The system call filter (seccomp)
Android's filter for apps allows the system calls its own libc (bionic) makes, plus a short
allowlist, minus a short blocklist
([genseccomp.py](https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/tools/genseccomp.py)).
Anything else sends the process SIGSYS ("Bad system call", exit code 159;
[seccomp_policy.cpp](https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/seccomp/seccomp_policy.cpp)).
That list grows with each Android version, and Bun is built for desktop Linux, so on older
phones it can trip over it: the community guide reports the native binary dying on its Android 8
and 10 test phones (on `statx` and `pidfd_open`).

One case is known in detail, and it turned out not to be Android's filter. Bun 1.4.0, first
shipped in Claude Code 2.1.181, calls `epoll_pwait2` (system call 441) when the kernel reports
5.11 or newer.
- **Android 15 and later allow that call.** bionic added it to its list for Android 15: it is in
  `SYSCALLS.TXT` on the android15 and android16 branches and missing on 11 to 14
  ([android14](https://android.googlesource.com/platform/bionic/+/refs/heads/android14-release/libc/SYSCALLS.TXT),
  [android15](https://android.googlesource.com/platform/bionic/+/refs/heads/android15-release/libc/SYSCALLS.TXT)).
  The Phone 3 is on Android 16, so its filter lets the call through. Android 14 and older block
  it, with SIGSYS.
- **The reported crashes came from Termux's glibc.** Both crashes on record
  ([bun#32489](https://github.com/oven-sh/bun/issues/32489), and the proot one in #50270 from
  section 2.2) were on Android 16 and ran the binary on Termux's glibc (`glibc-runner`). That
  glibc's `syscall()` swaps `epoll_pwait2` for a shim of its own that calls `epoll_pwait`, and
  the shim reads the timeout without checking that there is one
  ([fake_epoll_pwait2.c](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fake_epoll_pwait2.c),
  [fakesyscall.json](https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fakesyscall.json)),
  so a wait with no timeout crashes at address 0: the "Segmentation fault at address 0x00000000"
  in both reports. The install guide says the same: "that one is not a blocked system call."
- **Bun fixed it in June 2026** ([bun#32490](https://github.com/oven-sh/bun/pull/32490)): it now
  asks the kernel directly instead of going through the C library (so no shim sees the call), and
  skips the call when `uname -r` contains `-android` or when
  `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2` is set. Claude Code 2.1.300 has the fix (it bundles
  Bun 1.4.3, and its binary contains the variable's name).

What that means for us:
- **Nothing to do for this call on the Phone 3.** In a proot-distro Debian, Debian's own glibc
  runs the binary, and proot-distro reports its own kernel string (`6.17.0-PRoot-Distro` by
  default, no `-android`), so Bun makes the real call, which Android 16 allows.
- **Set `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2=1` in the userland anyway.** It costs nothing and
  covers phones on Android 14 and older. Set it in the setup script, the login environment and
  the daemon's environment; holders and SDK sessions pass the daemon's environment on to
  `claude`. Faking an old kernel in `uname`, the community's earlier workaround, isn't needed any
  more.
- **Test with a real request.** `claude --version` never reaches the code that waits for
  events, so it passes even when a call there is blocked. `claude -p` (or a prompt in a session)
  shows it.
- **Under proot, a blocked call doesn't look like one.** proot catches the SIGSYS from Android's
  filter and answers the call with "not implemented" (`ENOSYS`)
  ([seccomp.c](https://github.com/termux/proot/blob/master/src/tracee/seccomp.c)), so the
  process isn't killed. Bun falls back to `epoll_pwait` on that answer; other code may not. Watch
  for errors that say "Function not implemented" or `ENOSYS`, a Bun crash banner ("Segmentation
  fault at address …" with a bun.report link), or a blank screen. Exit 159 "Bad system call" is
  what it looks like outside proot.

Checked on the Mac with a seccomp profile that sends SIGSYS on `epoll_pwait2`, as Android 14 and
older do (section 6): `claude --version` works, `claude -p` dies with exit 159 "Bad system call",
and an interactive `claude` draws nothing. With the variable set, both run normally. Under
Termux's proot, `claude -p` runs even without the variable, through the `ENOSYS` fallback. Only
this one call was simulated, so **other system calls Android 16 may block are still the biggest
unknown**, and the spike tests them first.

### Storage
- Projects must live in **private app storage** (the Termux home or our `filesDir`). Shared
  storage (`/sdcard`) is a FUSE file system without Unix permissions, symlinks or exec, which
  breaks git hooks, `node_modules/.bin` and most build tools.
- To make projects visible to the Files app and other apps, expose them through a
  DocumentsProvider (Termux does this).
- Budget: Debian base with build tools about 0.5–1 GB, Claude Code about 0.8 GB with updates,
  Node about 0.15 GB, plus projects and their `node_modules`. The Phone 3 has 256 or 512 GB.
- Android's file-based encryption protects private storage at rest, and other apps can't read
  it. `.credentials.json` stays inside it and never leaves the phone.

### Memory, battery, heat
- In the container test the daemon used about 125 MB and `claude` about 85 MB on its first
  screen. Real sessions grow to several hundred MB each. The Phone 3's 12 or 16 GB is plenty for
  a few sessions.
- Most of an agent's time is waiting on the API, which is cheap. Builds, tests and
  `npm install` under proot are the expensive part, in battery and in heat. No numbers exist
  for this phone; the spike measures them.

### Loopback is shared by every app
Android has no per-app network namespace, so **any app on the phone can connect to the
daemon's ports on `127.0.0.1`**. The phone's daemon must never treat "came from loopback" as
"trusted". Where it stands:
- **Local port (47322):** hooks need their secret path, `/v1/health` is open, and everything
  else needs the admin token, except the testing checklist (`/qa` and the routes its page calls),
  which takes its key (`?k=`, derived from the admin token) instead. Before 2026-10-14 the
  checklist answered any loopback client, including screenshot uploads of up to 25 MB written to
  disk: its only guard checked the Host and Origin headers, which stops a browser page but not a
  native app.
- **The gap that fix leaves on a phone:** the Mac app's menu opens the bare `/qa` page in a
  browser, so on this port a bare `GET /qa` is redirected to the page with its key, unless the
  request says another site sent it (`Sec-Fetch-Site` other than `none` or `same-origin`). Only
  browsers send that header, so any program that asks without it gets the key, and with it the
  checklist's routes on both ports, uploads included. On a Mac that matters little: the owner's
  programs can read the admin token file anyway (sandboxed apps are the exception: they can't
  read it, but could get the key this way). On a phone every app is a separate user that can't
  read it, so a phone host must drop that redirect, or not serve `/qa` on 47322 (section 4).
- **Phone API (47321):** everything needs a device token, except what is open on purpose:
  `/v1/health`, `/v1/pair` (it needs a single-use code), browsers' preflight requests
  (`OPTIONS /v1/*`), the app download page and APK (`/download/android`), the `/pair` page,
  preview links (`/p/<token>`, a random token that expires after 12 hours), Lens's screenshots
  (`/v1/shots/<name>.png`, a random name, 12 hours), the Terminal view's WebSocket
  (`/v1/terms/<id>/ws`, with a single-use ticket from an authenticated call, good for a minute)
  and the checklist with its key (`/qa?k=…`). A phone host should keep it to that list, and
  close the redirect above, which hands out the key.

The same fact helps the login: Chrome can reach Claude Code's local callback port, so the
normal browser login should complete on its own (to confirm in the spike).

### Google Play
Play forbids apps that download executable code from outside Play. An app that downloads a
Linux userland and Claude Code conflicts with that. UserLAnd and Termux are on Play anyway, but
we shouldn't count on the same tolerance. Our channel is GitHub Releases (Play is LATER L8), so
this isn't a blocker, but it means "on-device mode" may never ship through Play.

Claude Code's licence is "All rights reserved", so we **never bundle the binary**: the userland
installs it from Anthropic with the official installer, as the Mac setup does today.

## 4. How Hypernova would work on the phone

```
 Hypernova app (Kotlin) ──http 127.0.0.1:47321 + device token──▶ daemon (Node 25)
   foreground service (specialUse) keeps everything alive          │ inside the Linux userland
   supervises the userland's processes                              │ (proot Debian)
                                                                    ├─▶ holder ─PTY─▶ claude (linux-arm64)
                                                                    │                 ├─ bash, git, rg, node, python…
 Chrome Custom Tab ──claude.ai login──▶ claude's callback port      └─ projects in private storage
```

### The daemon runs in the same userland
The app connects to `http://127.0.0.1:47321` and shows the phone as one more host, next to the
Macs ("This phone"). Everything we built should keep working once the platform layer below is
in: shared terminals and the Terminal view, approvals through hooks, menus as cards,
attachments, previews of dev servers (the daemon already proxies `localhost` ports to the
in-app WebView), agents and workflows, and usage meters from the status line (today's tap writes
to the Mac's folder; see below). Lens's read aloud and "show me" are the exception: they run Mac
programs (below).

Cleartext to loopback is fine, since the traffic never leaves the device. The debug build
already allows `127.0.0.1` (added for `adb reverse`), so the spike needs no app change. The
release build's `network_security_config.xml` allows no cleartext at all, so a "This phone"
host in a release build needs a `127.0.0.1` domain-config there.

Pairing on the same phone needs no QR code, but it can't use the link the daemon makes: with
Tailscale off, `POST /local/pair/start` points it at `http://10.0.2.2:47321`, the emulator's
address for the Mac, which a real phone can't reach. The phone's link is
`hypernova://pair?v=1&u=http%3A%2F%2F127.0.0.1%3A47321&c=<code>&n=This%20phone`, with the code
from that call (without `n=` the app names the host "127.0.0.1"). With Termux, the setup
script (which runs in Termux) opens the app with it through an explicit intent:
`am start -n com.prudnikweb.hypernova/.MainActivity -a android.intent.action.VIEW -d '<the link>'`.
Not `termux-open-url`: that sends an implicit intent, and any app that registers the
`hypernova` scheme could catch the one-time code. With our own userland, the app reads the
admin token straight from the files it owns.

### What the daemon needs for Linux (from reading the code)
A small "platform" layer, about 2–3 days, which also makes the daemon run on any Linux box:
- `util.ts`: support and log folders default to `~/Library/...`. Use
  `~/.local/share/hypernova` and `~/.local/state/hypernova` on Linux. Finding `claude` falls
  back to `/bin/zsh -lc`; use `sh`. The machine name comes from `scutil`; use the phone's
  model on Android.
- `hooks.ts`, the status line tap: it writes to a hard-coded
  `$HOME/Library/Application Support/Hypernova/statusline`, while the daemon reads
  `statusline` in its real support folder (`HYPERNOVA_SUPPORT`). With the support folder
  anywhere else, such as `~/.local/share/hypernova`, the tap's `[ -d "$d" ]` check fails, it
  writes nothing, and the usage meters stay empty with no error. Build the block from the real
  support path, shell-quoted. The checks that recognise our own status line script (status and
  uninstall) also look for `Hypernova/bin/statusline.sh` by that name. And the tap calls
  `/usr/bin/sed`, `/usr/bin/head` and `/bin/mv` by full path (fine in Debian, missing in Termux).
- `hooks.ts`, the shell function: the `claude` function is zsh-only (LATER L15); the phone
  doesn't need it, since nobody types `claude` into a Mac terminal there.
- `services.ts`: Tailscale steps don't apply; hide them.
- `http.ts`, pairing: with no Tailscale, the link's base falls back to the emulator's
  `http://10.0.2.2:47321`. A phone host's is `http://127.0.0.1:47321`.
- `http.ts`, the checklist: a phone host drops the bare-`/qa` redirect on 47322 (or doesn't
  serve `/qa` there), or any app can get the checklist's key (section 3, "Loopback").
- `terms.ts`: "Open on the Mac" (`open -a`, Alacritty's socket, `getconf DARWIN_USER_TEMP_DIR`)
  doesn't apply; the app hides the button for a phone host.
- `term/holder.ts`: `/bin/ps` (package `procps` in Debian; not at that path in Termux).
- **A bug found on the way, now fixed:** `installIntegration()` failed with ENOENT when
  `~/.claude` didn't exist yet (a machine where Claude Code has never run), because `writeJson`
  didn't create the folder. That could happen on a fresh Mac too. Reproduced in the container,
  and fixed in the daemon on 2026-10-14.

Not in that estimate, Lens's Mac-only parts:
- `speech.ts`, read aloud: it renders the clip with the Mac's `/usr/bin/say`. Without it,
  `POST /v1/speech` answers 501 `no_voice`, so a phone host needs another voice engine.
  `GET /v1/speech/companion` hands Lens the address of Hyperscribe Companion, a Mac app (see
  "For the Even G2 glasses").
- `shots.ts`, "show me": it looks for Chrome (or Chromium) only as a Mac app, in `/Applications`
  or `~/Applications`, so on Linux it answers 501 `no_browser`. `HYPERNOVA_CHROME` (there for
  tests) could name Debian's Chromium, which is slow under proot (see below). Chromium there
  usually needs `--no-sandbox`, which shots.ts doesn't pass, so that would take a small wrapper
  script that adds it (or the platform layer adds the flag on Linux). Its search for a running
  server calls `/usr/sbin/lsof` (Debian has it in `/usr/bin`), and on Android it can't see
  listening servers anyway: since Android 10, apps can't read `/proc/net`
  ([privacy changes](https://developer.android.com/about/versions/10/privacy/changes)). The
  other two ways it finds a page (the address a server printed, an HTML file) don't need it.

### Signing in (proposed)
1. Setup would run the official installer inside the userland, then start `claude` in a shared
   terminal.
2. The daemon already reads Claude Code's screen (that's how menus become cards), so it can
   spot the login URL, and the app opens it in a Custom Tab.
3. The browser's redirect should reach Claude Code's callback on loopback and finish the login.
   If it doesn't (some phones, some browsers), the page shows a code and the app types it into
   the terminal.
4. Alternative for people who have a Mac: `claude setup-token` there, paste the one-year token
   on the phone (it can't use Remote Control or claude.ai connectors).

### Projects and files
- New project: as today (`git init` under the projects root, here inside the userland).
- Clone: `git clone` in the userland. For private repos, either `gh auth login` (device-code
  flow in the browser) or an SSH key the app generates and shows for the owner to add on GitHub.
- The file browser, diff viewer and previews work as they do now, because the daemon serves
  them.

### What works on the phone and what doesn't
Works: bash, git, Node and npm, Python and pip, ripgrep, gcc/clang and make, SQLite, curl,
ssh, `gh`; web projects with dev servers on localhost, previewed in the app; tests that don't
need a desktop or a kernel feature; Claude Code's web tools and MCP servers that run as plain
processes.

Doesn't work, or not worth it:
- **Xcode, Swift for Apple platforms, iOS simulators**: they need macOS.
- **Android app builds**: Google publishes the SDK build tools for x86-64 Linux, macOS and
  Windows, not for arm64 Linux. Community arm64 toolchains exist (on-device IDEs use them),
  but they're a project of their own.
- **Docker**, `docker compose`, anything needing namespaces or cgroups, systemd services.
- Claude Code's own sandboxing for Bash (it needs user namespaces).
- GUI apps and headless browsers: Chromium for arm64 installs, but under proot it's slow and
  usually needs `--no-sandbox`. Playwright-style testing is a later question.
- **Offline work**: Claude needs the API either way. What the phone removes is the Mac, Tailscale
  and the home network, not the internet.

### For the Even G2 glasses
Lens, the glasses app (`lens/`), talks to the same daemon API. The owner wants "an on the go
app for developers so wherever they are they can still develop products, see results"; Lens v1
does that with the Mac in the backpack. With the daemon on the phone, glasses + phone would need
no Mac at all, but only part of Lens works there as it is:
- **Works once the platform layer is in:** the glance, needs-you approvals and result cards.
  They only read sessions and answer approvals through the API.
- **Needs the Mac today:** talking (Hyperscribe Companion on the Mac recognises the speech),
  read aloud (the Mac's `say`) and "show me" (headless Chrome on the Mac); see the platform
  layer list.
- **Lens's address is built in.** A packaged Lens can only reach the hosts in its manifest's
  network whitelist, which `npm run pack` fills with the Mac's Tailscale name, over https and
  wss (`lens/README.md`). Each entry covers one scheme, and the glance arrives over a WebSocket
  (`/v1/ws`), so a "This phone" host needs `http://127.0.0.1:47321/` and `ws://127.0.0.1:47321/`
  in that list; with only the http entry, Lens falls back to polling every 4 seconds instead of
  staying live. That takes no code change: `LENS_WHITELIST` sets the exact entries at pack time
  (`LENS_WHITELIST='http://127.0.0.1:47321/,ws://127.0.0.1:47321/' npm run pack`). Whether the
  Even App lets a page reach plain http and ws on loopback is untested.

**Voice without a Mac is an open question for the owner.** Lens recognises speech only on the
Mac, through Hyperscribe Companion (Whisper), and not through Android's speech recognizer
(LATER L2). With no Mac, voice needs another path: Whisper on the phone, the glasses' own speech
pipeline if it offers one, or a second look at that choice.

## 5. Other ways to have "no Mac"
- **Any Linux machine:** the same Linux port runs on a home server or a small VPS, where
  Tailscale works natively, nothing is throttled and Docker works. It's the cheapest robust
  "no Mac" option and needs the platform layer from section 4, minus the phone-only items, plus
  one more: `services.ts` looks for the Tailscale CLI only where a Mac keeps it (the app bundle,
  Homebrew, `/usr/local/bin`), and Tailscale's Linux packages put it at `/usr/bin/tailscale`
  ([pkgs.go](https://github.com/tailscale/tailscale/blob/main/release/dist/unixpkgs/pkgs.go)).
  `HYPERNOVA_TAILSCALE` (there for tests) can point at it meanwhile.
- **Anthropic's own cloud sessions** (Claude Code on the web) need no hardware of ours, but
  Hypernova has no API to drive them today.

## 6. What was checked on the Mac
All in throwaway containers on Docker Desktop (linux/arm64, kernel 6.12.76), with downloads in
the session scratch folder; nothing touched the owner's daemon, ports or settings.

1. `npm view @anthropic-ai/claude-code`: version, `optionalDependencies`, `engines`; the full
   version history (packaging changed between 2.1.112 and 2.1.113).
2. Downloaded the npm tarballs for `claude-code-linux-arm64`, `-linux-arm64-musl` and the
   wrapper; read `install.cjs`, `cli-wrapper.cjs` and the stub.
3. `npm view @anthropic-ai/claude-code-linux-arm64-android`: 404. The release manifest at
   `downloads.claude.ai/claude-code-releases/2.1.300/manifest.json` lists eight platforms, no
   Android; `.../2.1.300/linux-arm64-android/claude` is 404.
4. `file` and `objdump -p` on the binaries: interpreter, `NEEDED` libraries, `GLIBC_2.17`,
   `ET_EXEC`, 64 KiB segment alignment. `strings`: Bun v1.4.3, built-in ripgrep and ugrep,
   `.credentials.json`, `isAndroidEnvironment`, `TERMUX_VERSION`.
5. `claude --version` and `--help`: Debian 13 (glibc) and Alpine 3.21 (musl), no network.
6. Built Termux's proot (`github.com/termux/proot`, current master) for glibc arm64 (Debian's
   own proot 5.1.0 crashes on everything on this kernel, even `ls`, so it proves nothing).
   Under it: `ls`, `claude --version`, `claude --help`, and the interactive first-run screen in
   a PTY.
7. The daemon from a read-only copy of `daemon/` with Node 25.9.0 linux-arm64: `npm ci`
   (node-pty compiled), `/v1/health`, 401 without a token, integration install (after creating
   `~/.claude`, before that bug was fixed), `POST /v1/sessions` → shared terminal started,
   `claude` running in it. Then the same with the daemon itself under proot. Memory: daemon
   about 125 MB, `claude` about 85 MB at its first screen. The status line tap wasn't exercised.
8. The seccomp action of Android 14 and older on `epoll_pwait2` (Android 15 and later allow the
   call), simulated: throwaway containers with no network and a seccomp profile that sends
   SIGSYS on that one call. `claude --version` passes; `claude -p` exits 159 ("Bad system call")
   and an interactive `claude` draws nothing in 12 seconds. With
   `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2=1` both behave as without the profile. Under Termux's
   proot, `claude -p` runs even without the variable.

Not checked: anything on a phone or the emulator (the emulator belongs to another agent today),
signing in, a real session doing work, speed of real builds under proot.

## 7. Recommended path

**Linux first, then the phone, then our own userland.** Each step is useful on its own, and
each one de-risks the next.

| Step | What | Effort | Useful even if we stop here |
|---|---|---|---|
| 0 | Spike on the Phone 3 (below) | Half a day, the owner's hands on the phone | Answers seccomp, login, speed, battery |
| 1 | Linux platform layer in the daemon (section 4) | 2–3 days | Hypernova on any Linux box or VPS |
| 2 | "This phone" in the app, daemon in Termux + proot Debian | 3–5 days | The owner works with no Mac |
| 3 | Our own userland inside Hypernova, one app, guided setup | 3–6 weeks | The public "how could I live without it" version |

### Step 0: the spike (the first prototype's first half)
On the Phone 3, by hand, about half a day:
1. Check Developer options for "Linux development environment" (expected missing). Turn off
   the phantom process killer with
   `adb shell settings put global settings_enable_monitor_phantom_procs false`, or with
   "Disable child process restrictions" if adb isn't at hand.
2. Install Termux from F-Droid or GitHub, then `pkg install proot-distro`,
   `proot-distro install debian`, and log in with the Bun switch set (insurance, section 3):
   `proot-distro login debian --env BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2=1`.
3. Inside: `apt install curl git build-essential python3 procps`, the official Claude Code
   installer, `claude`, sign in. Does the browser redirect complete by itself?
4. Smoke test with a real request, not `--version` (that passes even with a call blocked):
   `claude -p "Reply with OK"` must print a reply. No need to run it without the switch: Android
   16 allows `epoll_pwait2`, so that run would pass and show nothing about proot.
5. A real task in a cloned repo: edit, `git`, `rg`, `npm install`, a test run. Note how long
   each takes next to the Mac.
6. Node 25 (official linux-arm64 tarball), a checkout of the daemon, run it from the same login
   (so it has the switch) with the support, logs and `CLAUDE_BIN` overrides, then pair a debug
   build of the app with it. Get a code with
   `curl -s -X POST -H "Authorization: Bearer $(cat "$HYPERNOVA_SUPPORT/admin-token")" http://127.0.0.1:47322/local/pair/start`
   and, in a second Termux session (outside Debian), open
   `hypernova://pair?v=1&u=http%3A%2F%2F127.0.0.1%3A47321&c=<code>&n=This%20phone` with the
   `am start -n` intent from section 4. Not the link the call returns: without Tailscale it points at the
   emulator's `10.0.2.2`. The usage meters stay empty until the status line fix in section 4.
7. `termux-wake-lock`, screen off, a 30-minute task. Does it survive? Battery used? How hot?

If `claude` fails in one of the ways section 3 lists for a blocked call (under proot: "Function
not implemented" or `ENOSYS`, a Bun crash banner, a blank screen), Android blocked a system
call. Save the bun.report link or the last lines of output, note what it was doing, and stop
there: that's the finding.

### Step 2: the prototype proper ("This phone" through Termux)
- A setup script (`curl … | bash` in Termux) that installs a "Hypernova Linux" Debian with
  proot-distro (or pulls a ready OCI image), Node, the daemon from a release tarball and Claude
  Code through the official installer, then starts the daemon with
  `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2=1` in its environment and opens the app to pair with
  an explicit intent and a `127.0.0.1` link (section 4).
- The app: a "This phone" host (loopback, no Tailscale; a `127.0.0.1` entry in the release
  network security config), the Mac-only actions hidden, `WAKE_LOCK` declared and a partial
  wake lock held while a session works, and start/stop of the daemon through Termux's
  `RUN_COMMAND` intent (the app declares `com.termux.permission.RUN_COMMAND`; the user grants
  it and sets `allow-external-apps`).
- Done when: from the app, the owner clones a repo, starts a session, approves an edit, sees
  tests run and a dev server preview in the app, and it keeps going with the screen off.

### Step 3: the product (own userland)
- `proot`, its loader and `busybox` in the APK as native libraries; a minimal Debian arm64 root
  file system downloaded at setup into private storage; Claude Code, Node and git installed
  inside by the setup screen, with progress, like the Mac's guided setup.
- The foreground service owns the daemon's process and restarts it; projects appear in Files
  through a DocumentsProvider; sign-in through a Custom Tab; updates for the root file system
  and the daemon through the in-app updater.

## 8. Risks

| Risk | Likelihood | What happens | Mitigation |
|---|---|---|---|
| Android's seccomp blocks a call Bun or a tool makes on the Phone 3 | Medium | `claude` fails on its first real request: under proot "Function not implemented", a Bun crash banner or a blank screen ("Bad system call" outside proot) | Spike first, with a real request; proot turns a blocked call into an `ENOSYS` error instead of a kill; `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2=1` for older Androids; Anthropic's Android build (#50270) |
| Phantom process killer kills sessions | High without the setting | Random "signal 9" mid-task | Setup step for the adb setting (or the developer switch); foreground service; few long-lived processes |
| proot is too slow | Medium | `npm install` and tests take several times longer; some users find even typing sluggish (2.2) | Measure in the spike; keep heavy builds for a Linux box (section 5) |
| Android closes the loader trick | Low, years | Embedded userland stops working | Track Termux's APKLF work; the Termux-hosted mode stays as a fallback |
| Login callback doesn't reach the userland | Low | Manual code paste | Paste-code fallback; `setup-token` |
| Anthropic changes packaging again | Medium | Install breaks | Use only the official installer; never patch the binary |
| Battery and heat | Medium | Phone gets hot in long runs | Measure; pause heavy work on low battery (a later idea) |
| Play policy | Certain for Play | Can't ship on Play | GitHub Releases, as decided |

## 9. Not recommended
- **Docker on the phone:** impossible on stock Android without root.
- **AVF Linux Terminal:** not on Snapdragon, and a third-party app can't start the VM without
  an adb permission grant.
- **Patching Claude Code for Termux-native:** works for hobbyists, but it depends on shims that
  break with Bun and Android updates, and puts us in the business of modifying Anthropic's
  binary.
- **Pinning Claude Code 2.1.112:** too old, and new models need newer versions.

## 10. Sources
Claude Code
- Setup and system requirements: https://code.claude.com/docs/en/setup
- Authentication, credentials on Linux, `setup-token`: https://code.claude.com/docs/en/authentication
- Termux breakage since 2.1.113: https://github.com/anthropics/claude-code/issues/50270
  (Anthropic's reply: https://github.com/anthropics/claude-code/issues/50270#issuecomment-4308968635;
  the termux-chroot crash and `-k 5.10.0`: https://github.com/anthropics/claude-code/issues/50270#issuecomment-4761970865;
  2.1.185 under proot-distro: https://github.com/anthropics/claude-code/issues/50270#issuecomment-4762625271)
- Request for an Android package (closed as duplicate): https://github.com/anthropics/claude-code/issues/72620
- Community Termux packaging: https://github.com/gtbuchanan/claude-code-termux and
  https://github.com/ferrumclaudepilgrim/claude-code-android (install paths:
  https://cdn.jsdelivr.net/gh/ferrumclaudepilgrim/claude-code-android@main/docs/install.md, AVF:
  https://cdn.jsdelivr.net/gh/ferrumclaudepilgrim/claude-code-android@main/docs/avf-guide.md)
- Bun's `epoll_pwait2` crash on Termux's glibc: https://github.com/oven-sh/bun/issues/32489 (its
  title blames Android's seccomp, which Android 15 and later don't apply to this call), and its
  fix with the `BUN_FEATURE_FLAG_DISABLE_EPOLL_PWAIT2` switch: https://github.com/oven-sh/bun/pull/32490
- Termux glibc's `epoll_pwait2` shim:
  https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fake_epoll_pwait2.c
  (routed from `syscall()` by
  https://github.com/termux-pacman/glibc-packages/blob/main/gpkg/glibc/fakesyscall.json)

Linux on Android
- proot-distro (OCI images, limitations, `--kernel` and `--env`): https://github.com/termux/proot-distro
- Termux's proot: https://github.com/termux/proot (a blocked call becomes `ENOSYS`:
  https://github.com/termux/proot/blob/master/src/tracee/seccomp.c)
- Termux and targetSdk 28, the Play-compliant plan: https://termux.dev/en/posts/general/2024/11/11/termux-selected-for-nlnet-ngi-mobifree-grant.html
- Termux on Google Play (targetSdk 37, linker64): https://github.com/termux-play-store/termux-apps
- UserLAnd (proot as native libraries, targetSdk 37): https://github.com/CypherpunkArmory/UserLAnd
- Termux `RUN_COMMAND` intent: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- Podroid (QEMU VM with Podman): https://github.com/ExTV/Podroid

Android platform
- The app seccomp filter: built from bionic's system call list
  (https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/tools/genseccomp.py),
  blocked calls get SIGSYS
  (https://android.googlesource.com/platform/bionic/+/refs/heads/android16-release/libc/seccomp/seccomp_policy.cpp);
  `epoll_pwait2` is missing from the list for Android 14
  (https://android.googlesource.com/platform/bionic/+/refs/heads/android14-release/libc/SYSCALLS.TXT)
  and present from Android 15
  (https://android.googlesource.com/platform/bionic/+/refs/heads/android15-release/libc/SYSCALLS.TXT)
- No `/proc/net` for apps since Android 10: https://developer.android.com/about/versions/10/privacy/changes
- Exec from app storage (W^X), Android 10: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission
- Foreground service time limits: https://developer.android.com/develop/background-work/services/fgs/timeout
- Phantom process killer: https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md
- Play's Device and Network Abuse policy: https://support.google.com/googleplay/android-developer/answer/9888379
- AVF permissions: https://android.googlesource.com/platform/packages/modules/Virtualization/+/b32f6328/javalib/AndroidManifest.xml

Devices
- Snapdragon and the Linux Terminal: https://www.androidauthority.com/snapdragon-chips-android-linux-terminal-3608648/
- Qualcomm's Gunyah hypervisor for protected VMs: https://www.qualcomm.com/developer/blog/2024/01/gunyah-hypervisor-software-supporting-protected-vms-android-virtualization-framework
- Nothing OS 4.0 (Android 16) on the Phone 3: https://m.gsmarena.com/nothing_os_40_based_on_android_16_is_now_rolling_out_to_the_nothing_phone_3-news-70401.php
