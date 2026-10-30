# Releasing

How a public release of Hypernova is made: the Android APK, the Mac app, and the GitHub release
that carries them. Each section says what to build, how to check it, and what must never leak.

## This fork's releases

The fork (ljmerza/Hypernova) doesn't run `scripts/release.sh` and builds no Mac app.
`.github/workflows/android-release.yml` cuts every release: each push to `main` that touches
`android/`, `daemon/`, `scripts/pack-linux.sh` or `scripts/install-linux.sh` (or a manual run)
publishes the next `v<appVersion>-ljm.<n>` with two files:

| File | What |
|---|---|
| `hypernova-<tag>.apk` | the Android app, signed with the fork's release key (repo secrets), `:app:distRelease` |
| `hypernova-server-<tag>-linux.tar.gz` | the Linux server package, `scripts/pack-linux.sh` renamed: the daemon's sources and lockfile, the font, the install script, `LINUX.md` as its README. No `node_modules` and no Node, so one package serves x86_64 and arm64 (the install fetches Node.js for the server and builds node-pty there) |

One job builds both, so one push makes one release and every release has both, even when only the
daemon changed. A release published by hand gets both attached. Installing the server package:
[INSTALL.md](INSTALL.md), "On a Linux server".

## Android

The APK people install is the **release build**: shrunk and renamed by R8, not debuggable (so
nobody with adb can `run-as` the app and read its pairing tokens), without the Compose tooling, and
signed with **the Hypernova release key**. The debug APK (`./gradlew :app:dist`,
`dist/hypernova-debug.apk`) is for development only and is never handed out.

### The release key

| | |
|---|---|
| Where | `~/.hypernova-signing/` on the build Mac (folder `0700`, files `0600`), **outside the repository** |
| Files | `hypernova-release.jks` (the key, PKCS12) and `keystore.properties` (its passwords) |
| Key | RSA 4096, alias `hypernova`, `CN=Hypernova, O=prudnik-web`, valid until 9 March 2054 |
| Certificate SHA-256 | `FC:07:0D:B2:14:89:38:DF:C1:80:A6:01:DC:62:42:1E:AD:8E:F1:0D:98:B5:A0:60:6E:C9:20:89:A5:92:6A:2A` |

`keystore.properties` holds `storeFile` (relative to its own folder, or absolute), `storePassword`,
`keyAlias` and `keyPassword`. A PKCS12 store has one password, so the two passwords are the same.
The build reads the file from `HYPERNOVA_KEYSTORE_PROPS` when that is set, else from
`~/.hypernova-signing/keystore.properties`.

**Back it up now, and keep it secret.** Android installs an update only when it is signed with the
same key as the app already on the phone. So:

- **If the key is lost, Hypernova can never be updated again.** Every phone would have to uninstall
  the app (losing its pairing and settings) and install a new one signed with a new key, and the
  pairing links would stop opening the app until the name service lists the new key.
- **If the key leaks, anyone can make an "update" that phones accept as Hypernova.**

Keep two copies of the whole `~/.hypernova-signing/` folder (the `.jks` and `keystore.properties`
together; one is useless without the other) in two places that are not this Mac, for example an
encrypted USB drive in a drawer and a password manager's secure attachment. Never in the repository,
an unencrypted cloud folder, a chat or an email. Never make a second release key "to start over":
the first public key is the update path for good. (APK Signature Scheme v3, which the APK carries,
can hand over to a new key later with `apksigner rotate`, but only while the old key is still in
hand: one more reason for the backup.)

The key was made once with `keytool -genkeypair -keyalg RSA -keysize 4096 -validity 10000 -alias
hypernova -dname "CN=Hypernova, O=prudnik-web" -storetype PKCS12`, its password 40 random letters
and digits passed through an environment variable (`-storepass:env`), never on a command line.

### Build

From `android/`:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:distRelease
```

It writes three files to `dist/`:

- `hypernova.apk`: the release APK (about 13 MB; the debug one is about 48 MB).
- `hypernova.json`: its version note, `{"versionCode": N, "versionName": "0.2.0"}`. The versionCode
  is the build's minute since 2026-01-01, so every build updates the one before.
- `hypernova-mapping.txt`: R8's mapping. **Keep it with the release** (next to the APK in the
  release folder, not necessarily published): it is the only way to read a crash trace from this
  build, with `~/Library/Android/sdk/cmdline-tools/latest/bin/retrace dist/hypernova-mapping.txt
  trace.txt`.

`distRelease` refuses to start without the release key (it never publishes a debug-signed APK) or
while `HYPERNOVA_TSNET_AUTHKEY` is set, and after the build it checks the APK: v2 and v3 signatures,
one signer, and that signer's certificate is the keystore's own (never Android's debug key).

Then `mac/build.sh` bundles `dist/hypernova.apk` and its note into the Mac app (it prefers an APK
without `-debug` in its name to a debug one), and the daemon serves them on the "Get the app" page
and to the phone's in-app update. `HYPERNOVA_RELEASE=1 mac/build.sh` refuses to bundle a debug APK.

**Contributors without the key:** `./gradlew :app:assembleRelease` still builds a release APK, signed
with their own debug key, and warns that it must not be handed out; `:app:distRelease` refuses.
Debug builds (`:app:assembleDebug`, `:app:dist`) are unchanged.

The embedded Tailscale node (`android/tsnet/build.sh`, built into `android/app/libs/tsnet.aar` when
it is missing) is bound from a copy of its sources in a fresh `/tmp/hypernova-tsnet.XXXXXX` folder:
gomobile writes the module's folder into the library's Go build info even with `-trimpath`, so a
build in the checkout would carry the builder's home folder and user name into every APK. To
rebuild it for a release, delete the AAR first (`rm android/app/libs/tsnet.aar`).

### Verify

```bash
BT=~/Library/Android/sdk/build-tools/37.0.0
$BT/apksigner verify --print-certs --verbose --min-sdk-version 24 dist/hypernova.apk
#   Verified using v2 scheme: true, v3 scheme: true; Number of signers: 1;
#   certificate SHA-256 digest fc070db2…a5926a2a (the fingerprint above)
$BT/aapt2 dump badging dist/hypernova.apk | grep -E "package:|sdkVersion|debuggable"
#   minSdkVersion 29, targetSdkVersion 36, and no "application-debuggable" line
```

(`--min-sdk-version 24` makes apksigner check the v2 block too; with the APK's own minSdk 29 it
looks at v3 only.)

The builder's name must not be in the APK. Unzip it into a new, empty folder (on macOS some
renamed resources differ only in case, so read the archive with a script, or unzip on a
case-sensitive disk) and count:

```bash
LC_ALL=C grep -a -r -c "$USER" <unzipped folder> | grep -v ':0$'     # expect nothing
go version -m <unzipped folder>/lib/arm64-v8a/libgojni.so | grep '=>' # a /tmp/hypernova-tsnet.… path
```

On a phone or emulator: `adb shell run-as com.prudnikweb.hypernova ls` must answer "package not
debuggable".

### App Links: the release key on the name service

The pairing QR code is `https://hypernova.observer/pair#…`, and Android opens it straight in the app
only when `https://hypernova.observer/.well-known/assetlinks.json` names the key the installed app
is signed with. The name service's Worker serves it from `ANDROID_CERT_FINGERPRINTS` in
`names/wrangler.jsonc`, which now lists the debug key and the release key:

```
AD:02:C8:A9:F1:19:BE:D5:56:5F:9D:B5:2C:DD:EA:13:85:F9:B0:F4:7A:52:1E:38:2E:F4:3A:BD:5F:6A:82:8B,FC:07:0D:B2:14:89:38:DF:C1:80:A6:01:DC:62:42:1E:AD:8E:F1:0D:98:B5:A0:60:6E:C9:20:89:A5:92:6A:2A
```

It takes effect with the next deploy of the name service (`names/setup.md`). Check with
`curl -s https://hypernova.observer/.well-known/assetlinks.json`, and on a phone with the release
build `adb shell pm verify-app-links --re-verify com.prudnikweb.hypernova` then `adb shell pm
get-app-links com.prudnikweb.hypernova` (`hypernova.observer: verified`). Until then a release
install opens pairing links through the site's page and its "Open Hypernova" button instead.

### Moving from the debug build

A phone with the debug APK can't update to the release one (or back): Android refuses an update
signed with another key, and the in-app update says so ("this update is signed with another key
than the app on this phone"). Nothing in the app pins a certificate itself: Android's own check is
the update's integrity (`data/UpdatePolicy.kt`). Uninstall
Hypernova once, install `hypernova.apk`, and pair again (the Mac keeps one device per install, so
remove the old one from the Mac's device list).

## macOS

The Mac app is **ad-hoc signed and not notarized** (no Apple Developer ID yet: LATER L6). macOS asks
whoever opens it the first time to allow it once (docs/INSTALL.md, "The first launch"; the disk
image's "Read me first.txt" says the same), and again after each new version.

```bash
mac/build.sh --release      # dist/Hypernova.app: stops, instead of warning, when something is missing
mac/dmg.sh                  # dist/Hypernova.dmg: the app, an Applications link, "Read me first.txt"
```

`--release` needs the release-signed APK in `dist/` (see Android above), npm (the bundled daemon keeps
its production packages only), Go with its module cache or a network (the Tailscale helper) and the
Command Line Tools. What goes into the bundle, and what stays out:

- The daemon's sources and production `node_modules`, its package lock and `sdk-version` (what
  Setup fetches, below), the Tailscale helper, the fonts, the APK and its version note (the "Get the
  app" page serves them).
- **Not** Claude Code's Agent SDK, none of it. Its JavaScript package
  (`@anthropic-ai/claude-agent-sdk`) is "© Anthropic PBC. All rights reserved", not ours to
  redistribute: Setup's Background service step downloads the version the lockfile pins from npm on
  the user's Mac (about 2 MB; `mac/Sources/Hypernova/Core/ClaudeSDK.swift`), and the daemon runs
  without it until then (`daemon/src/sdk.ts`). Its own Claude Code binaries
  (`@anthropic-ai/claude-agent-sdk-<platform>`, about 230 MB) are never needed: the daemon always
  runs the `claude` installed on the Mac. `build.sh` stops if any file of the SDK reaches the bundle.
- Nor development packages, tests, Windows or Intel builds of node-pty.
- Symbols are stripped and the sources' paths are remapped, so the builder's folders aren't in the
  binary.

Check what you built:

```bash
codesign --verify --deep --strict dist/Hypernova.app && echo sealed
LC_ALL=C grep -a -c "$HOME" dist/Hypernova.app/Contents/MacOS/Hypernova      # 0
find dist/Hypernova.app -path '*claude-agent-sdk*'                           # nothing
du -sh dist/Hypernova.app dist/Hypernova.dmg                                 # about 85 MB and 23 MB
```

`mac/dmg.sh` attaches the finished image read-only, verifies the app's seal inside it and prints its
SHA-256.

## GitHub release

One command builds every file under the name the download links use:

```bash
scripts/release.sh              # from a clean checkout; --allow-dirty for a rehearsal
```

It checks that the daemon, `package.json` and Android versions agree, builds the Android release APK,
the Mac app and its disk image, the Linux package and the Lens (Even Hub) package, looks inside every
one for the builder's home folder and user name (it stops if it finds either), and writes
`dist/release/`:

| File | What |
|---|---|
| `Hypernova.dmg` | the Mac app (Apple silicon, macOS 14+) |
| `Hypernova.apk` | the Android app, release-signed |
| `Hypernova-linux.tar.gz` | the Linux server package (Ubuntu/Debian, x86_64 and arm64) |
| `Hypernova-Lens.ehpk` | the glasses app, for the Even Hub developer portal |
| `SHA256SUMS.txt` | their checksums |
| `latest.json` | version, date, sizes, checksums: for the project site, not attached to the release |

`dist/hypernova-mapping.txt` (R8's mapping for the APK) is **not** published: keep it with the
release in a private place (it reads crash traces), for example
`~/.hypernova-signing/mappings/<version>/`.

Publish (a new version is a new tag; the first public release is `v0.2.0`):

```bash
git tag -a v0.2.0 -m "Hypernova 0.2.0" && git push public v0.2.0
gh release create v0.2.0 dist/release/Hypernova.dmg dist/release/Hypernova.apk \
  dist/release/Hypernova-linux.tar.gz dist/release/Hypernova-Lens.ehpk dist/release/SHA256SUMS.txt \
  --title "Hypernova 0.2.0" --notes-file <notes.md>
```

The notes say what is new in a few lines, the three downloads with their requirements, the Gatekeeper
first-launch step and the checksum command. Then put the same files, and `latest.json`, in the
project site's download folder (`site/README.md`) so the install page's direct links work, and check
a download from the release page against `SHA256SUMS.txt`.

The licence, `NOTICE` and `THIRD-PARTY-NOTICES.md` travel inside every file: the Mac app's Resources,
the Linux package's top folder and the APK's `assets/legal/` (a copy there of the root files and of
`third_party/`'s licences: update them together when a dependency changes).

Rules that keep a release clean: never attach a debug APK, never publish from a dirty tree, never
reuse a tag, and never commit `dist/` or anything from `~/.hypernova-signing/`.
