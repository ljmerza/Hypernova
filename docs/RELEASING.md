# Releasing

How a release of Hypernova is made: the Android APK, the Linux server package, and the GitHub
release that carries them. Each section says what to build, how to check it, and what must never
leak.

## Releases

`.github/workflows/android-release.yml` cuts every release: each push to `main` that touches
`android/`, `daemon/`, `scripts/pack-linux.sh` or `scripts/install-linux.sh` (or a manual run)
publishes the next `v<appVersion>-ljm.<n>` with two files:

| File | What |
|---|---|
| `hypernova-<tag>.apk` | the Android app, signed with the release key (repo secrets), `:app:distRelease` |
| `hypernova-server-<tag>-linux.tar.gz` | the Linux server package, `scripts/pack-linux.sh` renamed: the daemon's sources and lockfile, the font, the install script, `LINUX.md` as its README. No `node_modules` and no Node, so one package serves x86_64 and arm64 (the install fetches Node.js for the server and builds node-pty there) |

One job builds both, so one push makes one release and every release has both, even when only the
daemon changed. A release published by hand gets both attached. The notes list the commits since
the release before (`scripts/release-notes.sh`). Installing the server package:
[INSTALL.md](INSTALL.md), "On a Linux server".

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
- `hypernova.json`: its version note, `{"versionCode": N, "versionName": "0.2.0"}`. The versionCode
  is the build's minute since 2026-01-01, so every build updates the one before.
- `hypernova-mapping.txt`: R8's mapping. **Keep it with the release** (the workflow uploads it as
  an artifact; it isn't published): it is the only way to read a crash trace from this build,
  with `$ANDROID_HOME/cmdline-tools/latest/bin/retrace dist/hypernova-mapping.txt trace.txt`.

`distRelease` refuses to start without the release key (it never publishes a debug-signed APK),
and after the build it checks the APK: v2 and v3 signatures,
one signer, and that signer's certificate is the keystore's own (never Android's debug key).

The daemon serves `dist/hypernova.apk` and its note (or a copy in `<support>/android/`) on the
"Get the app" page and to the phone's in-app update; it prefers an APK without `-debug` in its
name to a debug one.

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

## The Linux package

```bash
scripts/pack-linux.sh      # dist/linux/hypernova-<version>.tar.gz; its path is the last line printed
```

The licence, `NOTICE` and `THIRD-PARTY-NOTICES.md` travel inside every file: the Linux package's
top folder and the APK's `assets/legal/` (a copy there of the root files and of `third_party/`'s
licences: update them together when a dependency changes).

Rules that keep a release clean: never attach a debug APK, never reuse a tag, and never commit
`dist/` or a release key.
