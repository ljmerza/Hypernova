# Releasing

How a release of Hypernova is made: the Android APK, the Linux server package, and the GitHub
release that carries them. Each section says what to build, how to check it, and what must never
leak.

## Releases

`.github/workflows/android-release.yml` cuts every release: each push to `main` that touches
`android/`, `daemon/`, `scripts/pack-linux.sh` or `scripts/install-linux.sh` (or a manual run)
publishes the next version, `vX.Y.Z` (below), with three files:

| File | What |
|---|---|
| `hypernova-vX.Y.Z.apk` | the Android app, signed with the release key (repo secrets), `:app:distRelease` |
| `hypernova-vX.Y.Z.json` | the APK's version note, `{"versionCode": N, "versionName": "X.Y.Z", "sha256": "<the APK's SHA-256, hex>"}`: `dist/hypernova.json` with the checksum added (`jq`, checked before it's attached). Servers read it to know the release's versionCode without downloading the APK, fetch the APK only when it's newer than the one they hand out, and offer it only when its SHA-256 matches (docs/PROTOCOL.md, "Where the offered APK comes from"). A release without it (from before 2026-10-31) is never offered by a server |
| `hypernova-server-vX.Y.Z-linux.tar.gz` | the Linux server package, `scripts/pack-linux.sh` renamed: the daemon's sources and lockfile, the font, the install script, `LINUX.md` as its README. No `node_modules` and no Node, so one package serves x86_64 and arm64 (the install fetches Node.js for the server and builds node-pty there) |

One job builds both, so one push makes one release and every release has both, even when only the
daemon changed. A release published by hand keeps the tag it was given, and gets all three
attached under that tag's name (the note last, after the APK). Installing the server package: [INSTALL.md](INSTALL.md), "On a Linux
server"; in one line, `curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash`.

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
without a type), each with its `type(scope):` taken off, then how to install, with the one-line
`get.sh` install and the files' own names.

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

## The Linux package

```bash
scripts/pack-linux.sh      # dist/linux/hypernova-<version>.tar.gz; its path is the last line printed
```

The licence, `NOTICE` and `THIRD-PARTY-NOTICES.md` travel inside every file: the Linux package's
top folder and the APK's `assets/legal/` (a copy there of the root files and of `third_party/`'s
licences: update them together when a dependency changes).

Rules that keep a release clean: never attach a debug APK, never reuse a tag, and never commit
`dist/` or a release key.
