<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->
# Releasing

How a release of Hypernova is made: the client (the Android APK and the desktop packages), the server's packages, and the
GitHub release that carries them. Each section says what to build, how to check it, and what must
never leak.

## Names

Every download says whether it's the server or the client (owner, 2026-11-01):

| | File | Status |
|---|---|---|
| Client | `hypernova-client-vX.Y.Z-android.apk` | built |
| Client | `hypernova-client-vX.Y.Z-linux-amd64.deb`, `…-linux-arm64.deb`, `…-linux-x64.tar.gz` (a folder to unpack), `…-macos-arm64.dmg`, `…-macos-x64.dmg`, `…-windows-x64.msi` | built, the desktop client; the Debian package is called `hypernova-client` |
| Server | `hypernova-server-vX.Y.Z-linux.tar.gz` | built; the name servers update themselves from, and `get.sh`'s |
| Server | `hypernova-server-vX.Y.Z-linux-amd64.deb`, `…-linux-arm64.deb` | built; the Debian package is called `hypernova-server` |
| Server | `hypernova-server-vX.Y.Z-macos-arm64.tar.gz`, `…-macos-x64.tar.gz`, `…-macos-arm64.pkg`, `…-macos-x64.pkg` | built |
| Both | `SHA256SUMS` | every file above that this release has |
| Old names | `hypernova-vX.Y.Z.apk`, `hypernova-vX.Y.Z.json` | **never rename or drop.** Servers already running look for exactly these, and for `hypernova-server-vX.Y.Z-linux.tar.gz` |

The `hypernova` command on a server keeps its name: it's used
everywhere, and renaming it would break every install. The note keeps its name too: it describes the client and
the server's package together.

**A rename of the desktop client** (Nebrio, later) starts in two lines, `android/desktop/packaging/client-names.sh`: the
package name, which every file's name also starts with, and the title with a space that a Mac and Windows show. The Gradle
build, `client-packages.yml`, `release-notes.sh`, `release-not-built.sh` and `test-client-package.sh` all read them. What
stays: the APK's and the server's names above, which servers look for; Windows' `upgradeUuid` and the Mac's `bundleID`
in `android/desktop/build.gradle.kts`, which say it's the same app; and the data folders in `DesktopStorage.kt`, so that a
key already paired isn't stranded. The name in the window is one line of its own, `AppName.kt`.

## Releases

`.github/workflows/android-release.yml` cuts every release: each push to `main` that touches
`android/`, `daemon/`, or the scripts that pack and install the server (or a manual run)
publishes the next version, `vX.Y.Z` (below), with four files:

| File | What |
|---|---|
| `hypernova-client-vX.Y.Z-android.apk` | the client, the Android app, signed with the release key (repo secrets), `:app:distRelease` |
| `hypernova-vX.Y.Z.apk` | the same file, for servers that only know this name. A server reads the client's name first, this one if a release has no other, and fetches one (`daemon/src/releases.ts`) |
| `hypernova-vX.Y.Z.json` | the version note, `{"versionCode": N, "versionName": "X.Y.Z", "sha256": "<the APK's SHA-256, hex>", "serverSha256": "<the server package's SHA-256, hex>"}`: `dist/hypernova.json` with the checksums added (`jq`, checked before it's attached; `serverSha256` from 2026-10-31, for servers that update themselves, docs/PROTOCOL.md, "The server's own update", which install nothing from a note without it). Servers read it to know the release's versionCode without downloading the APK, fetch the APK only when it's newer than the one they hand out, and offer it only when its SHA-256 matches (docs/PROTOCOL.md, "Where the offered APK comes from"). A release without it (from before 2026-10-31) is never offered by a server |
| `hypernova-server-vX.Y.Z-linux.tar.gz` | the Linux server package, `scripts/pack-linux.sh` renamed: the daemon's sources and lockfile, the font, the install script, `LINUX.md` as its README. No `node_modules` and no Node, so one package serves x86_64 and arm64 (the install fetches Node.js for the server and builds node-pty there) |

One job builds both, so one push makes one release and every release has both, even when only the
daemon changed. A release published by hand keeps the tag it was given, and gets them all
attached under that tag's name (the note last, after the APK). Installing the server package: [INSTALL.md](INSTALL.md), "Install";
in one line, `curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash`.

### The server's other packages

That job's release is then added to by `.github/workflows/server-packages.yml`, called with `needs: apk`:

| Job | Runner | Attaches, once its tests pass |
|---|---|---|
| `deb` (matrix) | `ubuntu-24.04`, `ubuntu-24.04-arm` | `hypernova-server-vX.Y.Z-linux-<arch>.deb`, after `scripts/test-server-deb.sh` on Ubuntu 24.04 and Debian 12 |
| `macos` (matrix) | `macos-latest` (arm64), `macos-26-intel` (x64) | `hypernova-server-vX.Y.Z-macos-<arch>.tar.gz`, then `.pkg`, each after `scripts/test-macos-smoke.sh` |
| `macos-unit` | `macos-latest` | nothing: the daemon's whole suite on a Mac, through `scripts/test-with-limits.mjs` (5 minutes a test, 10 a file, 19 the run, and a summary). `continue-on-error` until a run has shown it green; nothing depends on it either way |

Then `android-release.yml`'s own `checksums` job, on `ubuntu-latest`, once this workflow and `client-packages.yml` have
both finished, however they finished: `SHA256SUMS`, from what the release really has, and a "Not built" line in the notes for
anything it lacks (`scripts/release-not-built.sh`). It's one job after both because two would each read the release and
write the file, and the slower would leave out the other's files. Re-running a failed package job brings it round again, so
the file is whole and the line goes.

**A failure there can't stop a release.** The APK and the Linux tarball are published before any of it
starts, and it only runs if they were. The queue for the release moved from the whole run to the `apk` job,
so a Mac runner that's slow to come doesn't hold up the next release either. A job that fails costs its own
files, turns the run red, and is said in the release's notes. The price: for some minutes after a release
is published, the `.deb`s and Mac files aren't there yet, and `get.sh` on a Mac says to try again soon.

**To try these without publishing:** a pull request that touches them runs `server-packages.yml` with
nothing attached, and keeps the files as the run's artifacts.

The note `hypernova-vX.Y.Z.json` keeps exactly its fields: a server reads `versionCode`, `versionName`,
`sha256` and `serverSha256`. The new files' checksums are in `SHA256SUMS`, not the note.

### The Mac packages

**Tarball and `.pkg`, no `.dmg`.** A `.dmg` is for dragging an app to Applications; this is a background
service set up per user, with nothing to drag. The `.pkg` does what the `.deb` does: `pkgbuild` and
`productbuild` put the release in `/usr/local/lib/hypernova-server` and add `hypernova-server-setup`, and
its scripts do nothing else. Its last page says what to run next. The tarball is what `get.sh` uses.

**Unsigned.** What that shows a person: [INSTALL.md, "What macOS shows you"](INSTALL.md#what-macos-shows-you).
The `curl` line isn't affected. Signing the `.pkg` would need:

- a paid Apple Developer Program membership and a **Developer ID Installer** certificate;
- secrets for the certificate as base64 `.p12` and its password, and for notarizing an App Store Connect API
  key, or an Apple ID with an app-specific password and the team's ID;
- in `pack-macos.sh`, `productbuild --sign "Developer ID Installer: …"`, then
  `xcrun notarytool submit --wait` and `xcrun stapler staple` on the runner.

Nothing inside needs signing of its own: the `.pkg` holds text, and Node.js and node-pty come at install time,
each signed or not by its own publisher. Without those secrets the workflow goes on unsigned.

**What's proven, and how.** Nobody here has a Mac, so GitHub's runners are the only Macs it has run on. Their
first run (2026-11-02, macOS 26 on arm64 and on Intel, from a pull request) passed `scripts/test-macos-smoke.sh`
from both the tarball and the `.pkg`:

| Proven there | Not proven anywhere |
|---|---|
| A login session at the screen (`gui/<uid>`), `launchctl` bootstrap, bootout and kickstart, the words `launchd.ts` reads | **A person's own Mac** |
| `KeepAlive` after a `kill -9`; a session's holder surviving that, a kickstart and a reinstall | A real reboot, and Automatic login |
| Node.js fetched for the processor; node-pty prebuilt, nothing compiled; `doctor` with no problems; LibreSSL's certificates | What macOS asks a person: Gatekeeper, Background Items, folder and network prompts |
| `pkgbuild`, `productbuild` and `sudo installer` on the `.pkg` | macOS older than 26 |
| | `get.sh` against a real release on a Mac (only with stand-ins so far), and attaching the files to a release |

A Mac doesn't update itself until it's been run on a person's own Mac (`installKind`,
`daemon/src/server-update.ts`). A whole clip of read aloud, `POST /v1/speech`, hung on that runner's `say`:
docs/LATER.md, L108.

### The desktop client's packages

`.github/workflows/client-packages.yml`, called like `server-packages.yml` with `needs: apk`, so all of "A failure there
can't stop a release" holds for it, and tried the same way: a pull request that touches `android/desktop`, `android/shared`
and so on, or a manual run, attaches nothing and keeps the files and screenshots as the run's artifacts.

| Job | Runner | Attaches, once `scripts/test-client-package.sh` has passed |
|---|---|---|
| `linux` (matrix) | `ubuntu-24.04`, `ubuntu-24.04-arm` | `hypernova-client-vX.Y.Z-linux-<arch>.deb`; on amd64 also `…-linux-x64.tar.gz`, the folder `:desktop:createDistributable` makes |
| `macos` (matrix) | `macos-latest` (arm64), `macos-26-intel` (x64) | `hypernova-client-vX.Y.Z-macos-<arch>.dmg` |
| `windows` | `windows-latest` | `hypernova-client-vX.Y.Z-windows-x64.msi` |

Each carries its own Java, all of its modules (`includeAllModules`). A package can only be built on its own system. No job
installs the Android SDK: `:desktop` builds without it, and `ubuntu-24.04-arm` hasn't got one.

**The test** (`scripts/test-client-package.sh`, from `android/`'s `:desktop:probeJar`): it installs on a person's path
(`apt-get install -y ./x.deb`; the `.dmg` mounted and the `.app` copied to `/Applications`; `msiexec /i … /qn`; the
tarball unpacked), checks the version and the licences, opens it and waits for its window (Xvfb on Linux, the
runner's screen elsewhere), says on Linux that with no display it stops, then pairs with the demo server inside the
installed launcher. `PackageProbe`, which is in no package, goes in by `JAVA_TOOL_OPTIONS=-javaagent:`, so the runtime and the
jars are those installed, and it has to report a `java.home` inside the install. It makes the EC key, gets the certificate and
reaches the feed over mutual TLS. The real app is then started on that pairing, and the demo server has to say a
certified device connected. On Windows a second `.msi` a patch newer goes over the first, and only one entry may be left.
Then it's removed, and nothing may be left. The screenshots are in the run's artifacts: **look at them**, as a test can't.

To try it on Linux here: in a throwaway `ubuntu:24.04` with `sudo`, `CI=true`, node on the path, `npm ci --ignore-scripts`
in `daemon/`, and `xvfb xdotool imagemagick openssl`. `--tarball` needs no `CI`, as it installs nothing.

**The versions.** The tag gives `-PdesktopVersion`, and each system takes it its own way (`android/desktop/build.gradle.kts`;
`./gradlew -q :desktop:packageVersions -PdesktopVersion=…` prints them):

| | v0.23.0 | v0.256.1 | v1.0.0 | Why |
|---|---|---|---|---|
| What the app says it is (`AppInfo`, to a server as it pairs) | 0.23.0 | 0.256.1 | 1.0.0 | The release it is |
| `.deb`, tarball | 0.23.0 | 0.256.1 | 1.0.0 | |
| `.dmg` (`CFBundleShortVersionString`) | **1**.23.0 | 1.256.1 | **2**.0.0 | jpackage refuses a first number of 0 (`CFBundleVersion.java` in JDK 21). One higher keeps them ordered, with no two releases alike |
| `.msi` | 0.23.0 | **0.255.65** | 1.0.0 | Windows Installer takes 255.255.65535 at most, and minors are counted quickly here (20 of them from 2026-10-31 to 2026-11-02). From 255 the minor folds into the third number, minor-255 times 64 plus the patch, still ordered. It runs out at 0.1279.0, or at a patch of 64 in that stretch, where the `.msi` fails to build by name and costs only itself; a major step starts it again |

Anything after the numbers (a tag by hand, `v0.30.0-rc1`) stays in the file's name and the app's own version, and not in the package's.

**Windows upgrades** come from one fixed `upgradeUuid`. Never change it, a rename included: every install would stay beside the new one.

**Icons.** `android/desktop/icons/`: `.icns` and `.ico` are checked in, made by `make-icons.py` (Pillow) from the 512 px
`hypernova-client.png`, a stopgap crop (LATER L120). Run it again when that picture changes.

**The licences.** `:desktop:clientLegal` puts `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES.md`, the fonts' licences and
`packaging/legal/LICENSES-skia-bundled.txt` in every package's `resources/legal` folder, beside the app's jars
(`/opt/hypernova-client/lib/app/resources/legal` on Linux, `Contents/app/resources/legal` on a Mac, `app\resources\legal` on Windows). The Java carries its own in `legal`
under its runtime. The other libraries' are inside their jars. What Skiko's native library holds was read from the strings
in the Linux x64 library: when Skiko moves, look again.

**Unsigned.** There are no secrets, so nothing is signed: what a person sees is in [INSTALL.md, "The desktop client"](INSTALL.md#the-desktop-client).
Compose signs a Mac's native code ad hoc, with no identity, and not for Gatekeeper. Signing would need:

- **Mac:** a paid Apple Developer Program membership and a **Developer ID Application** certificate (not Installer, as the `.pkg`'s);
  secrets for it as a base64 `.p12`, its password, and an Apple ID's app-specific password with the team's ID, or an App Store Connect API key; on the runner, a
  temporary keychain holding the certificate; and Compose's own `macOS { signing { sign.set(true); identity.set("…") } notarization { … } }`, then `:desktop:notarizeDmg`.
  The app may then need entitlements of its own, for a Java runtime under the hardened runtime: not tried. LATER L119.
- **Windows:** a code-signing certificate (from a certificate authority, or Azure's Trusted Signing), and `signtool sign` on the
  launcher before the `.msi` is made and on the `.msi` after. jpackage has no option for it, so that launcher means
  building from `createDistributable` with a step between. SmartScreen's warning goes with reputation, which a new certificate has to earn. LATER L119.

**What's proven, and how.** Nobody here has a Mac or a Windows PC, and on them it has run nowhere yet:

| Proven | Not yet |
|---|---|
| On Ubuntu 24.04 and Debian 13 in containers, amd64, as a user with `sudo`, under Xvfb: the `.deb` and the tarball, all of the test above, with the screenshots looked at (2026-11-02). The `.deb` also with apt's recommended packages off, so what it `Depends` on is enough | **All of the `.dmg` and the `.msi`, the arm64 `.deb`, the job's own steps, and attaching:** until the first run on GitHub's runners |
| | A person's Mac or Windows: Gatekeeper, SmartScreen, macOS's local network question |
| | A real Linux desktop's menu entry (a container has no menus) |

### Versions

Semantic versions, counted from the commits the way semantic-release does it, by
`scripts/next-version.sh` (its self-tests: `scripts/test-next-version.sh`). The last release is the
highest `vX.Y.Z` tag the pushed commit contains (a tag with anything after the numbers doesn't
count). The commits after it, merge commits left out (the commits a merge brings in count on their
own), decide the next one:

| In the commits | Step | e.g. from v0.3.0 |
|---|---|---|
| a `type!:` subject, or a `BREAKING CHANGE:` line in a body | major | v1.0.0 |
| a `feat:` | minor | v0.4.0 |
| anything else: `fix:`, `perf:`, `docs:`, `chore:`, a message without a type, or no commit at all (a manual rebuild) | patch | v0.3.1 |

Never under **v0.3.0**, the first release with these versions: the v0.2.x before it are the old
`v0.2.0-ljm.N` builds renamed to `v0.2.N` (`scripts/rename-releases.sh`), and `v0.2.0` itself is
upstream's tag. The workflow refuses a tag that already exists.

The version is the app's versionName: the workflow builds with `-PappVersion=X.Y.Z`;
`appVersion` in `android/app/build.gradle.kts` is only the default for a local build. The
versionCode stays the build's minute, so any newer build updates the one before.

### Commits

Conventional commits, since they make the version and the notes: `type(scope): subject`, the scope
optional, the subject in the repo's plain style (what changed, in words, no period), e.g.
`fix(daemon): a held prompt goes in once the box is back`.

- `feat:` something new (a minor release)
- `fix:` something that was wrong
- `perf:`, `refactor:`, `docs:`, `test:`, `chore:` the rest (with `fix:`, a patch release)
- `feat!:` (any type with `!`), or a `BREAKING CHANGE: …` line in the body: a change that breaks
  what was there, like a phone and a daemon that no longer fit each other's older version (a major)

### Notes

`scripts/release-notes.sh <tag> <previous tag> <commit>` writes them: the commits since the release
before, grouped into **Features** (`feat`), **Fixes** (`fix`) and **Other** (the rest, and messages
without a type), each with its `type(scope):` taken off, then how to install, under **Client** and
**Server** headings, with the one-line `get.sh` install and the files' own names, and a line saying the
old-named files are only there for older servers. They're written before the desktop and Mac files exist, so
they name them all; `scripts/release-not-built.sh` then adds a **Not built for this release:** line for what never came, the
client's under Client and the server's at the end, and takes the line away when a re-run brings the file.

### Renaming the old releases

`scripts/rename-releases.sh` turns each `v0.2.0-ljm.N` release into `v0.2.N`: a new tag at the same
commit, the release moved onto it and retitled, the tag names in its notes rewritten, its assets
renamed (`hypernova-v0.2.0-ljm.N.apk` to `hypernova-v0.2.N.apk`, the server package likewise; an
early plain `hypernova.apk` keeps its name), then the old tag deleted. The newest stays the latest
release until v0.3.0. It is a dry run (the plan, nothing changed) unless given `--apply`, and safe
to run again. Run it before the first v0.3.0 is published, so that release's notes start from the
last v0.2.N rather than from upstream's v0.2.0. Clones keep the old tags until
`git tag -l 'v0.2.0-ljm.*' | xargs -r git tag -d`.

## Android

The APK people install is the **release build**: shrunk and renamed by R8, not debuggable (so
nobody with adb can `run-as` the app and read its pairing tokens), without the Compose tooling, and
signed with **the Hypernova release key**. The debug APK (`./gradlew :app:dist`,
`dist/hypernova-debug.apk`) is for development only and is never handed out.

### The release key

The workflow reads it from four repository secrets: `HYPERNOVA_KEYSTORE_B64` (base64 of the
`.jks`), `HYPERNOVA_KEYSTORE_PASSWORD`, `HYPERNOVA_KEY_ALIAS` and `HYPERNOVA_KEY_PASSWORD`. It
writes them into a `keystore.properties` in the runner's temporary folder and points
`HYPERNOVA_KEYSTORE_PROPS` at it.

A local release build reads the same kind of file: `keystore.properties` holds `storeFile`
(relative to its own folder, or absolute), `storePassword`, `keyAlias` and `keyPassword`, from
`HYPERNOVA_KEYSTORE_PROPS` when that is set, else from `~/.hypernova-signing/keystore.properties`
(folder `0700`, files `0600`), **outside the repository**. A PKCS12 store has one password, so the
two passwords are the same. A release APK's signing certificate (and its SHA-256) is printed by
`apksigner verify --print-certs` (see Verify).

**Keep a backup of the key, and keep it secret.** Android installs an update only when it is
signed with the same key as the app already on the phone. So:

- **If the key is lost, Hypernova can never be updated again.** Every phone would have to uninstall
  the app (losing its pairing and settings) and install a new one signed with a new key.
- **If the key leaks, anyone can make an "update" that phones accept as Hypernova.**

Keep the `.jks` and its passwords together, somewhere other than the repository secrets (an
encrypted drive, a password manager's secure attachment). Never in the repository, an unencrypted
cloud folder, a chat or an email. Never make a second release key "to start over": the first key
is the update path for good. (APK Signature Scheme v3, which the APK carries, can hand over to a
new key later with `apksigner rotate`, but only while the old key is still in hand: one more
reason for the backup.)

### Build

From `android/`, with JDK 21:

```bash
./gradlew :app:distRelease
```

It writes three files to `dist/`:

- `hypernova.apk`: the release APK (about 13 MB; the debug one is about 48 MB).
- `hypernova.json`: its version note, `{"versionCode": N, "versionName": "0.3.0"}`. The versionCode
  is the build's minute since 2026-01-01, so every build updates the one before; the versionName
  is `-PappVersion` when given (a release, "Versions" above), else what `git describe` says of the
  checkout: the last release's tag without its `v`, and after it the commits since, the commit and
  `-dirty` for uncommitted changes (`0.5.0`, `0.5.0-3-g1a2b3c4-dirty`), or `0.0.0-dev` without git
  or a tag. Settings → About shows it with the build.
- `hypernova-mapping.txt`: R8's mapping. **Keep it with the release** (the workflow uploads it as
  an artifact; it isn't published): it is the only way to read a crash trace from this build,
  with `$ANDROID_HOME/cmdline-tools/latest/bin/retrace dist/hypernova-mapping.txt trace.txt`.

`distRelease` refuses to start without the release key (it never publishes a debug-signed APK),
and after the build it checks the APK: v2 and v3 signatures,
one signer, and that signer's certificate is the keystore's own (never Android's debug key).

The daemon serves `dist/hypernova.apk` and its note (or a copy in `<support>/android/`) on the
"Get the app" page and to the phone's in-app update; it prefers an APK without `-debug` in its
name to a debug one. It also puts the latest GitHub release's APK in `<support>/android/` itself
when that release's versionCode (its `hypernova-vX.Y.Z.json`) is higher than the one it hands out
(checked 30 s after start and every 6 hours; `releaseCheck: false` turns it off), and connected
phones hear of a new one at once (`{t:"app"}`, docs/PROTOCOL.md).

**Without the key:** `./gradlew :app:assembleRelease` still builds a release APK, signed
with your own debug key, and warns that it must not be handed out; `:app:distRelease` refuses.
Debug builds (`:app:assembleDebug`, `:app:dist`) are unchanged.

The app carries no Tailscale node (removed 2026-10-30): no Go, gomobile, NDK or
`libs/tsnet.aar` is needed to build it.

### Verify

```bash
BT=$ANDROID_HOME/build-tools/37.0.0
$BT/apksigner verify --print-certs --verbose --min-sdk-version 24 dist/hypernova.apk
#   Verified using v2 scheme: true, v3 scheme: true; Number of signers: 1;
#   certificate SHA-256 digest: the release key's
$BT/aapt2 dump badging dist/hypernova.apk | grep -E "package:|sdkVersion|debuggable"
#   minSdkVersion 29, targetSdkVersion 36, and no "application-debuggable" line
```

(`--min-sdk-version 24` makes apksigner check the v2 block too; with the APK's own minSdk 29 it
looks at v3 only.)

The builder's name must not be in the APK. Unzip it into a new, empty folder and count:

```bash
LC_ALL=C grep -a -r -c "$USER" <unzipped folder> | grep -v ':0$'     # expect nothing
```

On a phone or emulator: `adb shell run-as com.prudnikweb.hypernova ls` must answer "package not
debuggable".

### Moving from the debug build

A phone with the debug APK can't update to the release one (or back): Android refuses an update
signed with another key, and the in-app update says so ("this update is signed with another key
than the app on this phone"). Nothing in the app pins a certificate itself: Android's own check is
the update's integrity (`data/UpdatePolicy.kt`). Uninstall
Hypernova once, install `hypernova.apk`, and pair again (the server keeps one device per install,
so remove the old one: `DELETE /local/devices/{id}`).

## The server's packages

```bash
scripts/pack-linux.sh              # dist/linux/hypernova-<version>.tar.gz; its path is the last line printed
scripts/pack-deb.sh amd64          # dist/deb/hypernova-server_<version>_amd64.deb (or arm64)
scripts/pack-macos.sh arm64        # dist/macos/hypernova-server-<version>-macos-arm64.tar.gz (or x64)
scripts/pack-macos.sh arm64 --pkg  # …macos-arm64.pkg: on a Mac only

scripts/test-server-deb.sh         # the .deb in Docker on Ubuntu 24.04, Debian 12 and 13: install, a user's
                                   # setup, upgrade, remove, purge, around a running server (privileged containers)
scripts/test-install-macos.sh --docker   # the Mac's scripts under bash 3.2, with stand-ins for macOS
scripts/test-macos-smoke.sh --tarball …  # on a Mac runner only: the real LaunchAgent. Not on a Mac you use
```

The licence, `NOTICE` and `THIRD-PARTY-NOTICES.md` travel inside every file: each server package's
top folder, the APK's `assets/legal/` (a copy there of the root files and of `third_party/`'s
licences: update them together when a dependency changes) and each desktop package's `resources/legal`. The server packages hold `CHANGES.md` too.
Before a release, `scripts/license-notices.sh --check` says whether those copies match and every modified file of the original
says so (CI's `license-notices` workflow runs it; docs/DECISIONS.md, "The Apache licence's notices").

Rules that keep a release clean: never attach a debug APK, never reuse a tag, and never commit
`dist/` or a release key.
