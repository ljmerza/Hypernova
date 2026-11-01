# Hypernova protocol (v1)

The contract between the daemon and its clients: the Android app, and the `hypernova` command on
the server (docs/LINUX.md). "The Mac app" below is a macOS menu bar client that is no longer in
this repository; the daemon still answers its routes (the admin-token feed, Mac-only features on a
Mac), so they stay documented. The daemon is the source of truth. All JSON is UTF-8, camelCase, and clients
must ignore unknown fields and unknown enum values (show them generically).

## Architecture in one picture

```
 Android app ──https, pinned + device cert (Wi-Fi/VPN)▶ daemon LAN  0.0.0.0:47323 (the same API)
 (development: the emulator ──http 10.0.2.2──────────▶ daemon API  127.0.0.1:47321)
 hypernova command ──http loopback + admin token─────▶ daemon local 127.0.0.1:47322
 Claude Code hooks (http hooks) ─────────────────────▶ daemon local /hooks/*
 Status line tee (marked block) ─▶ files in <support>/statusline/ ◀── daemon watches
 Holder (one per shared terminal) ──PTY──▶ claude (the real terminal UI)
   ▲ unix socket <support>/terms/<id>.sock
   ├── hn (a Mac terminal window)   ├── daemon (control, and a relay per phone)
 Daemon ──Agent SDK──▶ claude (fallback for phone sessions when shared terminals can't run)
```

- `<support>` is `~/.local/share/hypernova` on Linux (`~/Library/Application Support/Hypernova`
  on a Mac), 0700. Logs go to `~/.local/state/hypernova/logs/daemon.log`
  (`~/Library/Logs/Hypernova/daemon.log`).
- **Shared terminals** (see that section): interactive Claude Code running in a
  detached holder process. `claude` typed in a terminal starts one (through the
  shell function the integration installs), and so does every session started
  from the phone. The phone sees the real screen live, types into it, and presses
  Esc; a Mac terminal window can attach and detach at any time. Their events come
  from hooks and transcripts like any terminal session.
- **Sessions started from Hypernova** run in a shared terminal when the Mac can
  (node-pty loads and the hooks are installed). Otherwise they fall back to the
  Agent SDK (`query()` with `resume`): streaming text, approvals through
  `canUseTool`, interrupt. Either way they are ordinary Claude Code sessions.
- **Sessions started in a terminal without sharing** (`command claude`, an IDE,
  another shell) are observed through global hooks and their transcript files.
  Their approvals race the terminal dialog: whoever answers first wins. A prompt
  to one that is still open continues it in a fork (a new shared session); once
  it has ended, prompts resume it in place.
- **Providers**: every Hypernova session runs Claude Code with a provider
  profile, applied as environment variables (see Providers).

## Contract fixtures

`protocol/fixtures/` holds one golden JSON file for each message and answer the app parses: the
snapshot (`snapshot.json`), the live feed's messages (`ws-*.json`) and the routes' answers
(`rest-*.json`). Two suites read them:

- **The daemon** (`daemon/test/contract.test.ts`) builds each shape through its own code (the hub's
  views, the routes over HTTP, the broadcasts as a phone hears them) and fails when one no longer
  deep-equals its file. Times, random ids, the scratch folder, this machine's name and the local
  listener's port and key are normalised first (every time is `1800000000000`, a UUID the test
  didn't pick is `00000000-0000-4000-8000-00000000000N` in the order it first appears).
- **The app** (`android/app/src/test/.../data/ContractTest.kt`) runs every file through its own
  parsers (`Wire`, `LiveHub`'s helpers, `ChangesRepo`, `PermissionRulesWire`…) and checks the
  fields it reads. Gradle puts the folder on the unit tests' classpath, so a changed fixture runs
  them again. A fixture without a check there, or a check without its fixture, fails.

The rule: change the protocol, then the fixtures, then both suites must pass. A shape changed on
purpose is written again with `UPDATE_FIXTURES=1 npm test` in `daemon/` (or `UPDATE_FIXTURES=1 node
--test test/contract.test.ts`), which also deletes fixtures no test makes any more; review the diff,
then make `./gradlew :app:testDebugUnitTest` pass. Never edit a fixture by hand to make one side
pass: the daemon's suite would fail on it next.

## Transport and auth

- Phones use the local route: `https://<the computer's private address>:47323`, the daemon's own
  certificate pinned and the phone's device certificate presented (see Local network (direct
  HTTPS)), on the same Wi-Fi or over the owner's own VPN into that network. The pairing link's
  base URL `u` is that listener at one of the computer's addresses (`https://192.168.1.48:47323`),
  so each computer's pairing has a base URL of its own; `http://10.0.2.2:47321`, the emulator's
  address for this computer, only for a development build's code or with the listener off (the
  debug build allows cleartext to 10.0.2.2 only; a release build refuses it at once). The API is
  the same on both, and the base URL in use is the one everything else is built from. The app's
  route order is Local, then the base URL as it is (see The route).
- Every `/v1` request except `GET /v1/health`, `POST /v1/pair` and a terminal's WebSocket (a
  ticket instead, see Shared terminals) carries `Authorization: Bearer <deviceToken>`. 401 → the app shows
  "Unpaired" and stops retrying. WebSocket: same header on the upgrade request, or
  `?token=<deviceToken>` (a browser can't set headers on a WebSocket). The pages outside `/v1`
  (pairing, download, previews, the checklist) take no bearer token; a preview's or the
  checklist's link carries its own key.
- No CORS: no answer carries `Access-Control-Allow-*` headers and `OPTIONS` reaches no route, so
  a web page in a browser can't read the API. (Until 2026-10-30 the phone port allowed any
  origin for the glasses app's WebView; it was removed with the glasses.)
- Errors: non-2xx with `{"error": {"code": "string", "message": "human text"}}`.

## Pairing

1. `hypernova pair` calls `POST /local/pair/start` and shows a QR code
   of its `link`. **Pairing is local only** (since 2026-10-30; docs/DECISIONS.md, "Pairing is local
   only"): every link points at the computer itself on its own network, nothing public. While the
   LAN listener is on, `link` is the computer's own pairing page on it, `webLink`,
   **`https://<address>:<lp>/pair?c=<code>&n=<urlencoded name>&fp=<fingerprint>&lan=<addr1>,<addr2>&lp=<port>`**,
   at the address a phone's browser is sent to (`pickLanAddress`: IPv4, not a `.1` when there's
   another); `appLink` is the app's own link with the same details,
   `hypernova://pair?v=1&u=<urlencoded base URL>&c=…&n=…&fp=…&lan=…&lp=…`, whose `u` is that same
   `https://<address>:<lp>`. With the listener off (or no address) there is no page a phone could
   open: `link` is `appLink`, whose `u` is the emulator's `http://10.0.2.2:47321` (and `?dev`, a
   development build's code, keeps that `u` and the app link whatever the listener). `lan` lists
   the IPv4 addresses whenever there is one, else the ULAs (`pairingAddresses`: a shorter link is a
   smaller QR code; the snapshot still says them all). The code is single-use and valid for 10
   minutes, or until the Mac stops showing it: `POST /local/pair/cancel {code?}` → `{ok,
   cancelled}` ends that code (without one, every live code); the menu bar calls it when its Pair
   page closes and when a fresh code replaces the old one. (Links from a daemon before 2026-10-30
   may carry `u=http://10.0.2.2:47321`, the tailnet hints `ts`, `ti`, `ta` and `tc`, or be
   `https://hypernova.observer/pair#<the app link's parameters>`; the app still reads them, the
   last from its fragment, never fetching anything.)

   Why that link: phone cameras and Google Lens open https links, never a custom scheme from a QR
   code. The page is on the LAN listener, so a phone on the same Wi-Fi or on the owner's VPN into
   that network opens it; the browser can't vouch for the computer's own certificate and warns
   once (and may ask for a client certificate, which the listener asks every connection for:
   decline it). `GET /pair` on the listener at an address needs no device certificate, answers
   only for a code being shown (404 otherwise), and hands the app **Open Hypernova**, an
   `intent://pair?v=1&u=<this listener>&c=…&n=…&fp=…&lan=…&lp=…#Intent;scheme=hypernova;package=com.prudnikweb.hypernova;S.browser_fallback_url=<this listener>/download/android;end`
   link (followed by itself after a moment on Android). `u` there is the address the browser came
   in on, read from the socket, never from a header; `fp`, `lan` and `lp` are the daemon's own,
   never the page's query. Without the app, the fallback is this computer's download page. On the
   loopback phone API (`127.0.0.1:47321`) `/pair` answers as before (its `u` the request's own origin).
2. The phone's camera opens the page, which opens the app with the app link; the in-app "Paste
   pairing link" field takes the page's link, the app link and the intent hand-over alike
   (`data/Pairing.kt` `PairLinks`). The app handles the `hypernova` scheme, host `pair`; there is no
   App Link (`autoVerify`) for any https domain.
3. The app calls `POST /v1/pair` with `{"code", "deviceName", "model", "appVersion", "client"?,
   "installId"?}` and gets `{"token", "deviceId", "mac": {"id", "name"}}`: first to the `lan`
   addresses on `lp` with the certificate pinned to `fp`, then (nobody answering there, or no `lan`)
   to `u` as it is, on a route of its own, never another computer's. It stores `u` (or, for an older
   link whose `u` is plain http, the listener address that answered: each computer's pairing has
   its own base URL, which is how the app tells their routes apart), the
   token and the Mac name. One phone can pair with several Macs, each its own device there with its
   own token and device certificate (the app keeps them per computer and shows one at a time, with
   a switcher: docs/DECISIONS.md); nothing in the daemon changes for that.
   `installId` (8–64 letters, digits, `_` and `-`) is the app install's own stable id, made once and
   kept with the app's data: pairing the same install again (its token lost, a new code scanned)
   replaces its old device (same `installId` and `client`) instead of adding a twin to the device
   list; the old token stops working and its feeds close (4001).
   Without one (an older app), every pairing is a new device.
   `client` names the app when it isn't the Android one (up to 24 lowercase letters, digits and
   `-`; anything else is dropped). It's kept with the device, for the Mac app's device list.
4. `DELETE /v1/devices/me` forgets this phone on the Mac.

The daemon also serves the app, to install or update it: `GET /download/android`
(no auth; on the local listener too, see Local network (direct HTTPS)) is a page with the version, a Download button and
the install steps; the APK itself is `GET /download/android/Hypernova.apk`. `hypernova pair`
prints that page's address (or the release's, when the server carries no APK).

### The app update

The snapshot's `mac.app` names the APK the computer hands out (`{versionCode, versionName}`), and
`{t:"app"}` says it again when it changes (see WebSocket); a
higher `versionCode` than the running app's is an update (a banner once per version, a
notification while the app is closed, a card in Settings, and Settings → About's "Update available
· 0.6.0" row with **Update**, the same flow as the banner's, with its steps: downloading with its
percent, checking, Allow, Install, Try again). "Get the update" happens in the app
(`data/AppUpdater.kt`, its rules in `data/UpdatePolicy.kt`), never in a browser: a browser can't
check the local listener's own certificate, which only the app pins.

1. **Allowed to install, once.** Without "Install unknown apps" for Hypernova
   (`canRequestPackageInstalls`; the app declares `REQUEST_INSTALL_PACKAGES`), the banner and the
   card say so and open the system page for it; back in the app, the update carries on by itself.
2. **The download, over the app's own route.** `GET <route base>/download/android/Hypernova.apk`
   with the route's own client: the local listener's address with the pinned certificate and the
   device certificate (Local), or the paired base URL as it is (Remote). The path is open
   on every listener without a token (before the auth check; `openOnLan` on the local listener).
   Into the app's cache (`cache/update/`), with its progress (whole percents) in the banner and the
   card; a body shorter than its `Content-Length` is a failure, never an APK to try.
3. **The check.** `PackageManager.getPackageArchiveInfo`: the package must be this app's and its
   `versionCode` higher than the installed one; else it is refused and deleted ("isn't
   Hypernova", "no newer", "isn't an app Android can read").
4. **The install.** A `PackageInstaller` session (`MODE_FULL_INSTALL`); Android shows its own
   "Do you want to update this app?" every time (the moment the user knows the app is about to
   close and come back as the new version). The answer comes back to a receiver that isn't
   exported: shown while the app is on screen, else waiting for a tap on Install; Cancel keeps the
   file and Install asks again; a failure says why. **Android refuses an APK signed with another
   key than the installed app** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, said as "signed with another
   key"): that is the update's integrity, whatever route it came by.
5. **After.** Android ends the app to replace it; `MY_PACKAGE_REPLACED` posts "Hypernova is
   updated" (tap to open) when it was this app that handed Android the update. The downloaded file
   goes with the outcome (installed, failed, put away) and at the next start; leftover sessions are
   abandoned.

"Open in browser" stays on the card only where a browser could open the computer's page: an
`https://` name (never an address, whose certificate only the app pins).

### Where the offered APK comes from

The APK in `<support>/android/` (`hypernova.apk`, with its note `hypernova.json`
`{versionCode, versionName}`; no note, no offer) is either an admin's (`hnctl apk`; a dev build in
a checkout's `dist/` counts when `<support>/android/` has none) or **the latest GitHub release's**,
which the daemon fetches itself (`daemon/src/releases.ts`):

1. **The release.** `GET https://api.github.com/repos/ljmerza/Hypernova/releases/latest`, no
   token, 30 s after start and every 6 hours, with `If-None-Match` (the last ETag: a 304 means the
   same release, and its note isn't read again). A 403 or 429 with `X-RateLimit-Remaining: 0`
   (until `X-RateLimit-Reset`) or `Retry-After`: no request until then. Offline, a 5xx, a 404 (no
   release): a line in the log, the next look in 6 hours; never a failure of the daemon.
2. **The note.** The release's `hypernova-<tag>.json` asset (the release workflow attaches it,
   docs/RELEASING.md): `{versionCode, versionName, sha256, serverSha256}`, `sha256` the APK's and
   `serverSha256` the server package's (hex; from 2026-10-31, see The server's own update). A
   release without a note (from before the notes) offers nothing.
3. **Newer only.** Its `versionCode` must be higher than the APK handed out now; else nothing is
   downloaded. So an admin's build with a higher versionCode (`hnctl apk`, versionCodes are build
   minutes) is never replaced by an older release, nor an equal one.
4. **The APK.** `hypernova-<tag>.apk` into `<support>/android/.hypernova.apk.<pid>.download`
   (256 MB at most, its size as the release lists it), its SHA-256 counted on the way. A different
   SHA-256 than the note's: deleted, nothing offered, tried again at the next look. The
   versionCode handed out is read again (an admin may have published meanwhile).
5. **Published** as `hnctl apk` does it: the old `hypernova.json` goes, the APK is renamed into
   place, then the new note (`{versionCode, versionName, sha256, tag, source: "github"}`) is
   renamed into place; each rename is atomic in the same folder, so no note ever names another
   build's file. Then `{t:"app"}` goes out.

The setting `releaseCheck` (default on; `POST /local/settings {releaseCheck}` or `settings.json`)
turns it off; turned on, it looks at once. `HYPERNOVA_GITHUB_API` points it at another API (tests
use a fake GitHub on a local port).

### The server's own update

On unless the owner turns it off (`autoUpdate`, unset on since 2026-11-01; docs/DECISIONS.md, "Servers update
themselves"): `POST /v1/mac/update {autoUpdate}` from the phone (Settings, the server's section:
**Update this server automatically**), `hypernova auto-update on|off`, or `POST /local/settings
{autoUpdate}`. The release check above hands each release it reads to `daemon/src/server-update.ts`
after the APK's part (it looks while `releaseCheck` is off too, for this alone), so the phones are
offered the release's app before the server restarts.

```ts
ServerUpdate = {
  auto: boolean,                 // the setting
  available: boolean,            // this install can update itself…
  reason: string | null,         // …else why not, a sentence to show ("Installed from a checkout: …")
  state: "idle" | "waiting" | "installing",   // waiting: downloaded and checked, for a quiet moment
  target: string | null,         // the release's tag while waiting or installing
  last: {from, to, at} | null,   // the last update it installed by itself (versions, epoch ms)
}
```

1. **Only an install the installer manages**: Linux, not in a container, this daemon running from
   `<app>/daemon` (`HYPERNOVA_APP`, else `<support>/app`), which `hypernova setup` put there from a
   release's package: its `install.json` says `{"source": "package"}` (setup writes `checkout` when
   it installs from a git checkout: `scripts/install-linux.sh` in a clone, `hnctl update`; an
   install from before the marker has none), started by its systemd user service (`INVOCATION_ID`,
   and `hypernova.service` running this daemon's `main.ts`), with `systemd-run` there. Anything
   else is `available: false` with its reason, in the log once.
2. **Newer only.** The release's tag (`vX.Y.Z`) must be a higher version than the daemon's
   (`daemonVersion`); an equal, older or other tag installs nothing.
3. **The package, checked.** The note's `serverSha256` (the release's
   `hypernova-server-<tag>-linux.tar.gz`; releases from before 2026-10-31 have none, and install
   nothing, logged); the package (128 MB at most, its listed size) into `<support>/updates/`, its
   SHA-256 counted on the way, unpacked with `tar` into a new folder there, which must hold one
   `hypernova-<version>/` with `scripts/install-linux.sh` and a daemon whose version is the tag's.
4. **A quiet moment**: no session `working` or `starting`, no agent running (`agentsRunning` of any
   session that hasn't ended, so also an agent waiting on its background command, see Agents and
   workflows, whose cap keeps a stuck one from holding this) and no approval waiting, 2 minutes on end
   (looked at every 15 s); after 24 hours it goes ahead anyway. Turned off meanwhile: nothing.
5. **The install**, in a transient systemd unit of the user's (`systemd-run --user
   --unit=hypernova-update`, `daemon/src/server-update.sh`, its output in `<logs>/update.log`), with
   the service's environment: a copy of the installed daemon is kept, then the package's own
   `scripts/install-linux.sh --from <it> --yes --no-claude` installs it as a manual update does
   (the new daemon swapped in only once its terminal module works, the service written again and
   restarted). The new daemon must answer `/v1/health` within 60 s (a service still on the old
   process is restarted first); else the copy goes back and the service restarts on it. The unit
   writes `<support>/updates/result.json` `{tag, code, at}`: 0 updated, 3 no copy could be kept,
   4 went back, else the install's own exit (the server as it was).
6. **After.** The waiting install is kept in `<support>/server-update.json`; the new daemon, at
   start, finds itself its version, logs "this server updated itself from A to B", sets `last`
   and looks at the releases 5 s after start (rather than 30 s). The old daemon, or one restarted
   on it, reads a failed result (or an install not back within an hour) as a failure.
7. **Failures** (a download, a checksum, the package, the install) leave the running server as it
   was, are logged, and wait before the same release is tried again: 1 hour, 6, 24, then not again
   until the setting is turned off and on or a newer release comes.

The phone shows the switch only from a daemon that sends `mac.update`, with its line: what it does,
or `reason`, or what it's doing, and `last`. When the daemon's version changes between two
snapshots, the phone's connection log says so ("the server updated: A → B").

## Local network (direct HTTPS)

The phone reaches the daemon directly when both are on one network (the same Wi-Fi, or the Mac
on the phone's hotspot, or a VPN into the Mac's network), and falls back to the base URL as it is
when the local route isn't there (see The route). Nothing is opened to the internet. `daemon/src/lan.ts` (the listener, the
source filter, the addresses, the pairing limits) and `daemon/src/tls.ts` (the certificates).

- **The LAN listener:** HTTPS on port 47323 (`HYPERNOVA_LAN_PORT`; `0` takes a free port, for
  tests), on all interfaces, serving exactly the phone API: the same routes, pages and WebSocket
  upgrades as 127.0.0.1:47321, never the Mac app's local API (the admin token counts for nothing
  there). A connection whose source address isn't loopback, RFC 1918 (10/8, 172.16/12,
  192.168/16), link-local (169.254/16, fe80::/10), CGNAT 100.64/10 or IPv6 ULA fc00::/7 is dropped
  at accept, before any TLS. The daemon setting `lanAccess` (default true; `POST /local/settings
  {lanAccess: false}`) turns the listener off, and the snapshot then says `lan: null`. When the
  port can't be bound the daemon logs it and runs without the listener (`lan: null` too). On Linux,
  unset means off in a container, on a cloud VM, on a machine with a public IPv4 address of its own
  and behind WSL 2's NAT on a Windows PC (`platform.ts`, `lanByDefault`). Behind WSL's NAT (any
  networking mode but mirrored, as `wslinfo --networking-mode` says) no address of the machine is
  one a phone can reach, so `lan.addresses` is `[]` and pairing links carry `lan=` empty, even
  with `lanAccess: true`.
- **What the listener holds** (anyone on the network, a café's Wi-Fi say, can reach it): at most
  32 open connections per source address and 64 in all; one more is closed at accept. A TLS
  handshake must finish within 10 s, and a request's headers within 15 s (408 and closed
  otherwise), counted for a connection's first request from the handshake, so a peer that
  connects and says nothing goes too; an idle keep-alive connection closes after 10 s. A whole
  request may take 15 minutes (attachments over a slow link), and an open WebSocket has no limit.
  A phone needs a few connections at once (its requests and uploads, the live feed, a terminal, a
  preview's page) and reconnects as it goes.
- **Server identity:** a self-signed ECDSA P-256 certificate (CN `Hypernova <mac id>`, valid 10
  years), made once at startup when it's missing, in `<support>/tls/` (`server.key`, `server.crt`;
  files 0600, folder 0700) and kept from then on. `fingerprint` is the lowercase hex SHA-256 of
  the certificate's DER (64 hex digits, no colons). Clients trust exactly that certificate:
  hostnames are IP addresses, so the pin replaces the hostname check. The daemon makes
  certificates with the system's `openssl` (`/usr/bin/openssl`: LibreSSL on macOS, OpenSSL on
  Linux; never Homebrew's).
- **Device certificates (mutual TLS):** the daemon keeps its own device CA (`ca.key`, `ca.crt`,
  ECDSA P-256, 10 years, same folder). The listener asks every connection for a client
  certificate (except a connection that names the daemon's name, see A name on the local
  network). A request is *certified* when its certificate was signed by this CA, is the
  device's active one and the device still exists. Without that, the LAN listener answers only
  `GET /v1/health`, `POST /v1/pair` and `POST /v1/device/cert` (the bearer token's way to a first
  certificate), `GET /download/android` and its APK, and `GET /pair` for a code being shown (any `OPTIONS` is 404); everything else is 401
  `device_cert_required`, and a WebSocket upgrade is refused with 401. A certified request still carries the bearer token where
  the route needs one, and that token must be the same device's (401 `wrong_device` otherwise);
  the routes that take no token (a terminal ticket's WebSocket, a preview, the checklist) are open
  to the certified device. Forgetting a device (`DELETE /v1/devices/me`,
  or `DELETE /local/devices/{id}` from the Mac) revokes its certificate at once: the record is
  gone, so the next request, even on a connection already open, is 401. The daemon keeps only each
  certificate's fingerprint with its device (`devices.json`), never a key of the phone's.
- **`POST /v1/device/cert`** (bearer token; over any route: the LAN listener or
  loopback): `{"csr": "<base64 DER PKCS#10>"}`, made on the phone with an EC P-256 key (kept in
  AndroidKeyStore) and the subject exactly `CN=<deviceId>` → `{"cert": "<base64 DER>", "ca":
  "<base64 DER>", "fingerprint"}`: the device's certificate (CN the deviceId, valid 2 years,
  `clientAuth`), the CA's certificate, and the server certificate's fingerprint. One active
  certificate per device: a new one replaces the old, which stops working at once: the next
  request with it is 401 `device_cert_required`, and the WebSockets opened with it on the LAN
  listener close with 4002 (`certificate replaced`; the app reconnects with the new one). The
  daemon keeps the certificate's expiry with the device (`certExpiresAt`). 400 `bad_csr` when the
  body isn't base64 DER PKCS#10, its signature doesn't check, the subject isn't `CN=<deviceId>` or
  the key isn't EC P-256; 503 `no_tls` when the daemon has no certificates (openssl missing).
- **The snapshot** gains `lan: {port, addresses: string[], fingerprint, deviceCert: boolean} |
  null`: the listener's port, the Mac's private addresses right now (IPv4 in 10/8, 172.16/12 and
  192.168/16, then IPv6 ULA; never loopback, link-local or Tailscale's 100.64/10 and
  fd7a:115c:a1e0::/48 (a Tailscale the owner runs on the machine for something else), however the
  interface writes them; in the order to try), the server
  certificate's fingerprint, and whether this device holds an active certificate that hasn't run
  out (`false` for the Mac app, for none, and once its 2 years are up; the app then asks for a new
  one, as it does on a 401 `device_cert_required` while presenting one). `null` while the listener
  is off. `{t:"lan", lan}` on the WebSocket when the addresses change (the interfaces are re-read
  every 5 s, so a change shows within that) or the listener is turned on or off; that `lan` has no
  `deviceCert` (it didn't change). `GET /local/status` carries the same `lan`, for the Mac app.
- **Pairing over the local network:** `link` and `appLink` carry
  `fp=<fingerprint>&lan=<addr1>,<addr2>&lp=<port>` (the pairing page passes them on; `lan` and `lp`
  none while the listener is off). The app `POST`s `/v1/pair` to each `lan` address on `lp` first
  (in parallel, short timeouts) with the certificate pinned to `fp`, and keeps the address that
  answered as its local route; only when none answers does it try `u` as it is, on a route of its
  own (until 2026-10-30 it tried `u` first, through the route of any computer already paired
  under the same `u`, which every computer then shared: see Pairing). A source is any private
  address, the computer's own subnet or not: a phone on the owner's VPN (192.168.2.x into a
  192.168.1.x network, say) pairs like one on the Wi-Fi. A pairing turned away on the listener
  (no code shown, a wrong or used code, too many tries) is logged with its source, at most a line
  every 10 s (`pairing from <ip> refused: …`). Right after pairing, and for an
  already paired phone once the snapshot says `deviceCert: false`, the app asks for its
  certificate. `/v1/pair` on the LAN listener is **closed** while no code is being shown (403
  `pairing_closed`), and allows at most 5 wrong codes per source address per 10 minutes (429
  `too_many_tries`, with `retryAfterSeconds`); a right code counts for nothing. There is no limit
  across sources, so one peer can't lock everyone else out: the code is 16 random bytes, which no
  number of tries guesses (the daemon remembers the latest 1024 sources). The loopback phone API
  keeps today's rules.
- **Route choice (the app), Local first, always:** on any network (Wi-Fi, a VPN), or while
  hosting a hotspot, try the LAN addresses (the last snapshot's, the pairing link's, and the
  phone's own hotspot subnet probed on the port, a /24 at most, short timeouts, in parallel) and
  prefer the first good mutual-TLS handshake; otherwise the base URL. Every attempt to connect runs
  a probe round first, as does every network change; see The route for the round, the back-off
  and the connection log.
  Everything the app builds from the base URL (terminal tickets and their sockets, attachments, downloads, previews) follows the active route; the daemon builds the URLs
  it hands out (a preview's, the checklist's) from the request's own host and scheme. The Link chip
  and Settings show the route: "Local" or "Remote".
- **The terminal on the local route:** the terminal page's own WebSocket can't trust the pinned
  certificate (a WebView never asks the app about a socket's certificate) nor show the device
  certificate, so there the app opens the ticket's socket itself, `wss://<the Mac's private
  address>:47323/v1/terms/{termId}/ws?ticket=…`, with the same pin and device certificate as its
  other requests, and passes the frames both ways between it and the page, unchanged (binary stays
  binary, text stays text). On the base URL the page opens the socket itself, as before.
- **Previews and the checklist on the local route:** asked for over the LAN listener, `POST
  /v1/preview` and `GET /v1/qa` return `https://<the Mac's private address>:47323/p/<token>/…` and
  `…/qa?k=<key>`. They open in the app's own WebView, which pins the daemon's certificate and
  presents the device certificate as the app's other requests do (no bearer token: the link is the
  key, and a preview's page asks for its own files, with its cookie, the same way). An external
  browser can do neither (it would refuse the certificate, and the listener would answer 401
  `device_cert_required`), so links that leave the app use the base URL: the same path and
  query on the paired base URL `u` (preview tokens and the checklist's key aren't bound to a
  host).

## A name on the local network (removed)

(Until 2026-10-30 this place described an optional private name, `l-<random>.<domain>` from a
name service with a Let's Encrypt certificate, for the browser's download page. It is gone from
the daemon (`names.ts`, `acme.ts`, `hypernova name`, `/local/name*`, the `nameService`,
`nameServiceUrl`, `nameTermsShown` and `acmeDirectory` settings): every page a phone's browser
opens is the LAN listener at an address, with its own certificate, and the page says to compare
the APK's checksum. A settings file from before keeps those keys, unread; `<support>/names/` is
left on disk.)

## The route

(Until 2026-10-30 this place described "Tailscale built in": the daemon's and the app's own
Tailscale nodes, `tailscale serve` for the phone API, the pairing link's tailnet hints and the
`Tailscale` route. All of it was removed: docs/DECISIONS.md, "Tailscale is removed". The phone
reaches the computer on its own network, or over the owner's own VPN into that network.)

- **Two routes:** `RouteKind.Local` (the computer's local listener, see Local network (direct
  HTTPS)) and `RouteKind.Remote` (the paired base URL as it is: a public address, or the
  emulator's `http://10.0.2.2:47321`). Local first, always (`data/LanRoute.kt`). A base URL that is
  the listener itself (`https://<one of the local addresses>:<lp>`, a pairing's `u` since 2026-10-30)
  is not a route of its own: the round tries it as Local, with the pin, and logs the base URL as not
  tried ("the base URL is the local listener").
- **A probe round** tries both at once, each `GET /v1/health`: the computer's local addresses with
  the pinned client (4 s each; on any network, Wi-Fi, a VPN or this phone's hotspot) and the base
  URL as it is (8 s). It settles on Local the moment Local answers; on the base URL only once
  Local has failed, except that off the computer's subnet (the phone's Wi-Fi address and the
  computer's not in one IPv4 subnet) Local timing out doesn't hold the round up: it settles on what
  answered and moves to Local if Local answers later. Nothing answering at all fails the attempt at
  once with what was tried.
- **Rounds run** before every attempt to connect (joining one under way, or taking one settled
  under 3 s ago with no failure since), on every network change (the default network, its kind,
  its addresses), when the computer reports new addresses, and, while not on Local with local
  addresses worth trying, after 30 s, 1 minute, then every 2 minutes in the computer's subnet, or
  after 2, 4, 8, 16, then every 30 minutes elsewhere (a network change or a hotspot appearing
  starts over). So a reconnect after any failure tries Local first.
- **A route that fails in use** (the snapshot or the socket over it) falls back to the base URL
  until the next round; failing again within 5 minutes of its last failure, it is tried after the
  other for 8 s, then 16, 32… up to 2 minutes, still tried, and still used when nothing else
  answers. Each route counts its own failures. Local that answers while the computer won't take
  this phone's device certificate there is tried after the other for a minute (a 401
  `device_cert_required` after the certificate was asked for this connection is never an
  unpairing). A local winner is settled on the port it answered on (the snapshot's `lan.port`, not
  only the pairing link's).
- **The offline card** names what the last round tried when nothing answered ("Your Mac doesn't
  answer on this Wi‑Fi", "Another device answers at your Mac's address"), draws each route's answer
  as a tag (`LOCAL  REFUSED`), and links to the **connection log** (`data/ConnLog.kt`; Settings →
  Mac → Connection log): the network each round saw, every probe and its answer (OK, refused, no
  answer, unreachable, no such name, another certificate…), the route chosen and why, the socket
  opening and closing (with the close code) and the waits. The last 600 entries in memory, the
  newest 250 on disk (`files/connlog.jsonl`), logcat (`HypernovaConn`) in debug builds; Copy puts
  it on the clipboard as text. Never a token, key or code: a host and port at most.
- Everything built from the base URL follows the route through `ActiveRoutes`. The Link chip says
  "Local" or "Remote"; Settings draws the route as a chip. `Mac.via` is `"local"` or `"remote"`.
- **What a computer or a phone kept from before** is left alone and not read: the daemon's
  `builtInTailscale` and `tailscaleControlUrl` settings, its `<support>/tsnet/` folder, the app's
  stored `tailnet*` and `controlUrl` fields (they go with the next save of the list of computers),
  and its `files/tsnet` folder. A pairing link that still carries `ts`, `ti`, `ta` or `tc` pairs as
  before, without them.
- **Tests:** on Android `LanRouteTest`, `LocalFirstTest`, `RouteRecheckTest`, `PairLinksTest` and
  `ServersTest`; in the daemon `test/lan.test.ts` and `test/linux-cli.test.ts`.

## REST

| Method & path | Body → Response |
|---|---|
| `GET /v1/health` | → `{ok, protocol: 1, name}` |
| `POST /v1/pair` | see Pairing |
| `GET /pair?c=<code>&n=<Mac name>` | → HTML, no token: the computer's own pairing page (`webLink`, what the QR code opens), which hands over to the app. On the LAN listener at an address it needs no device certificate and answers only for a code being shown (404 otherwise), its `u` the address the browser came in on (see Pairing) |
| `GET /download/android` | → HTML, no token: the app's install page; `GET /download/android/Hypernova.apk` is the APK (see Pairing) |
| `GET /v1/snapshot` | → `Snapshot` |
| `GET /v1/sessions/{id}/events?limit=200` | → `{events: Event[]}` its normalized history, the last `limit` events (300 without one), oldest first. Optional: `output=<n>` (tool outputs cut to their last n characters, 256–65536, with "…\n" in front) |
| `GET /v1/sessions/{id}/agents` | → `{agents: Agent[], workflows: Workflow[]}`: the agents its main agent started and the workflows of them, newest first (the 60 most recent agents and any running; older ones only counted in their workflow). See Agents and workflows |
| `GET /v1/sessions/{id}/agents/{agentId}/events?limit=200&after=<cursor>` | → `{events: Event[], cursor: string, full: boolean}`: that agent's own transcript, read like a session's (its task is the first `user` event, kept first whatever the limit; its report is the last `assistant` one); read-only. `full: true`: the last `limit` events (1–1000), which replace what the client has. With `after` (the `cursor` of an earlier answer): `full: false` and only the events added or changed since then, in transcript order (one with an `id` the client has replaces it, a new one goes at the end; none when nothing changed). A cursor from another read (the file was rewritten, the daemon restarted, the cache dropped it), a malformed one, or more changes than `limit` gets `full: true`. An agent the session has as running, started less than 2 minutes ago, whose file Claude Code hasn't written yet answers `{events: [], cursor: "", full: true, starting: true}`: the phone shows "Starting…" and asks again every 1.5 s, keeping nothing of it. 400 `bad_agent` for an id that isn't an agent's (`a` and 16 hex digits), 404 `no_agent` when the session has no such agent file otherwise (its message names this computer: "isn't on the server" on Linux, "on the Mac" on a Mac). The phone sends its cursor while the agent runs, and fetches the transcript whole once more when it ends |
| `GET /v1/sessions/{id}/usage` | → `{usage: SessionUsage}`: its model, how full its context window is, the tokens it used and what they cost, and the plan's windows (see Session usage, the to-do list and Rewind) |
| `GET /v1/sessions/{id}/todos` | → `{todos: Todo[], updatedAt: number \| null}`: Claude's to-do list as its last TodoWrite or task tool calls left it; empty when it made none (see Session usage, the to-do list and Rewind) |
| `POST /v1/sessions/{id}/rewind` | `{eventId}` → `{session: Session, text, files: Attachment[]}`: the conversation goes on in a new session from just before that `user` event; `text` and `files` are the message, for the prompt box. 404 `no_message`; 409 `first_message`, `not_found`, `no_transcript`, `fork_failed` (see Session usage, the to-do list and Rewind), `sdk_missing` (see Claude Code's SDK) |
| `POST /v1/sessions` | `NewSession` → `{session: Session}`. 400 `no_prompt`, `no_project`, `bad_project` (the folder doesn't exist), `bad_agent`, `bad_mode` (a Codex session's mode isn't `read-only` or `workspace-write`); 409 `mac_privacy` (macOS hasn't let the service open that folder, or is asking on the Mac right now: see Files), `unreadable`, `no_codex`, `codex_too_old`, `sdk_missing` (a Claude Code session the daemon would run itself, with no SDK here: see Claude Code's SDK); 503 `codex_unavailable` (see Codex) |
| `GET /v1/codex` | → `{codex: Codex \| null}` (see Codex) |
| `POST /v1/codex` | `{enabled: boolean}` → `{codex}`: Codex on or off for Hypernova, from the phone (see Codex). 409 `no_codex` |
| `POST /v1/codex/refresh` | `{}` → `{codex}`: Codex's account and limits read now |
| `POST /v1/claude` | `{agents: boolean}` → `{claude}` (the snapshot's `claude`): sessions in `claude agents` on or off (settings.claudeAgents, see Session menu), saved and taken at once, without a restart; every phone hears `{t:"claude"}`. 400 `bad_value` |
| `GET /v1/codex/models?fresh=1` | → `{models: CodexModel[]}`: Codex's models for the pickers (read at most once an hour; `fresh=1` now). 409 `codex_off`, 503 `codex_unavailable` |
| `POST /v1/sessions/{id}/switch/preview` | `{to: "claude" \| "codex", context?: "recent" \| "all" \| "none"}` → `{text, messages, bytes, maxBytes}`: what a switch to the other agent would carry over, for the owner to read and edit (see Switching agents). 400 `bad_agent`, `bad_context`; 409 `same_agent` |
| `POST /v1/sessions/{id}/switch` | `{to, text, model?, effort?, permissionMode?}` → `{session}`: the conversation goes on with the other agent in a new session (see Switching agents). 409 `busy`, `switched` (with `to`), `terminal_session`, `same_agent`, `no_codex`; 413 `context_too_large` (with `maxBytes`) |
| `POST /v1/sessions/{id}/prompt` | `{text, attachments?: string[], replace?: boolean, clientId?: string}` → `{session, queued: boolean}` (`text` may be empty when files are attached). `clientId` (up to 64 letters, digits, `_` and `-`, made by the phone) names this send: the queue entry and the `user` event it becomes carry it back. The same `clientId` to the same session within 10 minutes is one send: a repeat is answered with the first send's answer and types nothing (a send that failed is forgotten, so it can go again). This is what lets the phone's offline queue send a prompt again when it never saw the answer ("The offline queue", in DESIGN.md). `queued`: Claude was working, so the prompt waits in `session.queue` until Claude can take it rather than starting a turn now; in a shared terminal a slash command answers `false` (Claude Code runs many at once even while Claude works: /cost, /model and other panels open over the spinner), and one Claude Code does queue shows in `session.queue` all the same. In a shared terminal it is typed into the live session; 409 with `needs_answer` (an approval is open), `waiting` (a dialog or menu in the terminal), `typing` (unsent text there), `starting`, `not_seen`, `not_sent` (Enter didn't send it) or `terminal_busy`; `typing` carries `pending`, the text in the box, and `replace: true` clears it and sends; a session the daemon runs itself (or would, to go on with one that ended) answers 409 `sdk_missing` without Claude Code's SDK, before anything is queued (see Claude Code's SDK); 410 `attachment_gone` |
| `PUT /v1/sessions/{id}/held/{heldId}`, `DELETE …` | Fork: `{text}` → `{session}`: a held prompt's new words (its files kept), or (DELETE) taken back, never sent. 400 `empty`; 404 `no_session`, `no_held` (sent or removed already); 409 `sending` (being typed right now). See Held prompts |
| `POST /v1/attachments?name=<file name>` | the file's bytes (any Content-Type, max 30 MB) → `{attachment: Attachment}` (see Attachments) |
| `POST /v1/sessions/{id}/interrupt` | `{}` → `{ok}` (Esc in a shared terminal) |
| `POST /v1/sessions/{id}/menu` | `{menuId, choice: number, alt?: boolean}` or `{menuId, cancel: true}` → `{ok}`: answers `session.menu` in its shared terminal (arrow keys to the option, then Enter, or the footer's `alt.key`; `cancel` presses Esc). Claude Code's own permission dialog (`menu.permission`) is answered here too (see Approvals, "In the terminal"). A question form with `menu.steps` also takes `{menuId, move: -1 \| 1}` (← or →: the question before or after) and `{menuId, step: number}` (straight to that tab, 0 the first question, `steps.labels.length` Submit: ← as many times as there are questions, then → `step` times, since which tab is open isn't on the plain screen); on "Type something." ↑ goes first, off the text box. 400 `bad_step`; 409 `menu_gone` (that menu isn't on screen any more), `menu_moved` (the cursor didn't reach the option; a `move` or `step` on a menu with no steps, or past Submit), `no_terminal` |
| `GET /v1/commands?project=` | → `{commands: [{name, description, argumentHint, aliases: string[], builtin, terminal}]}`: the slash commands and skills Claude Code offers in that folder (its own, the owner's, the project's, plugins', MCP prompts), asked of an SDK process started there with hooks off and no prompt; cached 5 minutes per folder. Commands bound to a terminal on the Mac (vim, statusline, …), internal, removed and renamed ones are left out. `terminal`: one of Claude Code's own that opens a screen of its own in the terminal, which the phone can't work (/config, /permissions, /resume, /mcp, /status…; a list the daemon keeps by hand in `commands.ts`, since Claude Code's list doesn't say how a command draws); a numbered picker such as /model's isn't one (the phone answers it as `session.menu`). 409 when Claude Code isn't installed, `sdk_missing` without its SDK (see Claude Code's SDK) |
| `POST /v1/sessions/{id}/btw`, `GET …` | `{question}` → `{aside: Aside}`; GET → `{aside: Aside \| null}`: a side question, Claude Code's `/btw`, and its answer (see Side questions) |
| `GET /v1/sessions/{id}/permissions` | → `{permissions: {sources: RuleFile[], managedOnly}}`: Claude Code's permission rules for the session, read-only, highest precedence first, only the permissions lists of its settings files (see Rules in effect). 404 `no_session`; 409 `unsupported_for_agent` (Codex) |
| `POST /v1/sessions/{id}/terminal` | `{}` → `{termId, url}`: a single-use, one-minute WebSocket URL for the live terminal (409 `no_terminal` if it isn't shared) |
| `POST /v1/sessions/{id}/open` | `{}` → `{app, already}`: opens the shared terminal in a window on the Mac (or brings it forward) |
| `POST /v1/sessions/{id}/continue` | `{reason?: "away" \| "limit"}` → `{session}`: sends "I'm back. Please continue where you left off." (no reason, or `"away"`); `"limit"` sends the continuation after a limit (see Auto-continue), drawn as a notice row, and the session's ready card goes once its turn starts. Its errors are `prompt`'s |
| `POST /v1/sessions/{id}/auto-continue` | `{on: boolean}` → `{session}`: the session carries on by itself after its limit, or not (see Auto-continue). 400 `bad_on`; 404 `no_session`; 409 `not_limited` (nothing to carry on from: no `blocked.resume`, or it's `ready`, or Claude is working again), `not_available` (`canArm`/`canCancel` false), `needs_answer` (an approval is open), `waiting` (another menu or dialog in the terminal, or a change already under way), `typing` (unsent text in the terminal; `pending` has it), `not_confirmed` (Claude Code didn't confirm within 4 s; nothing changed), `terminal_busy`, `no_terminal`. Already as asked: 200, nothing done |
| `GET /v1/speech/voices` | → `{engine: "mac", voices: [{id, name, lang}]}`: the Mac's voices. See Read aloud |
| `GET /v1/speech/settings` | → `{speech: Speech}`: the Mac's voice and speed (see Read aloud) |
| `POST /v1/speech/settings` | `{engine?, macVoice?, rate?}` → `{speech}`. 400 `bad_engine`, `bad_voice`, `bad_rate` |
| `POST /v1/speech` | `{text, voice?, rate?}` → the whole clip read aloud in the Mac's voice, `audio/mp4` (see Read aloud) |
| `POST /v1/speech/play` | `{text, sessionId?, to?: "me"}` → `{play: Play & {to: {id, name}}}`: read aloud by the Hypernova app on the phone that asks, streamed. 400 `bad_target` (the Mac app), 413 `too_long`, 400 `empty` (see Read aloud, "Plays") |
| `GET /v1/speech/plays/{id}/audio` | → `audio/pcm`, 24 kHz 16-bit mono as it's made; only the phone playing it. 404 `no_play`, 410 `play_over`, 502 `speech_failed` |
| `POST /v1/speech/plays/{id}/stop` | `{}` → `{ok, state}`: the phone playing it, or the Mac app. 404 `no_play` |
| `POST /v1/sessions/{id}/mode` | `{permissionMode}` (`default`, `acceptEdits`, `plan`, `auto`, `bypassPermissions`) → `{session, applies?}`. A session the daemon runs takes it from its next turn while a turn runs (the running query can't change its mode): `applies: "next_turn"`, else `"now"`, and the phone says so. A shared terminal switches by Shift+Tab (409 `waiting` with a dialog open, `mode_unavailable` for a mode it can't reach: Bypass when `session.bypassAvailable` is false, answered without pressing any key). A `claude agents` job (`agentJob`) without one gets one first: `claude attach <job>` (which wakes a stopped one), or the attach holder it has unless that drifted to another job or the agent list; 409 `terminal_busy` when the attach doesn't open or shows another session, with no key pressed. The phone offers the mode picker for such a job, and the model and effort ones |
| `POST /v1/sessions/{id}/model` | `{model: string \| null, confirmPaid?: true}` → `{model, session, applies: "now" \| "next_turn"}`: switches the session's model (see A session's model; a `claude agents` job's through `claude attach`, as the effort route). 400 `unknown_model`; 404 `no_session`; 409 `busy` (with `reason`: `needs_answer`, `waiting`, `typing` + `pending`, `starting`, `terminal_busy`), `terminal_model` (a terminal Hypernova doesn't hold: type /model there), `confirm_paid` |
| `POST /v1/sessions/{id}/effort` | `{effort}` (`low`, `medium`, `high`, `xhigh`, `max`) → `{effort, session, applies: "now" \| "next_turn"}`: Claude Code's reasoning effort, `/effort <level>` typed into its terminal (see A session's effort). 400 `unknown_effort`; 404 `no_session`; 409 `busy` (with `reason`: `needs_answer`, `waiting`, `typing` + `pending`, `starting`, `terminal_busy`), `terminal_effort` (a terminal Hypernova doesn't hold: type /effort there), `not_open` (it has ended), `unsupported_for_agent` (Codex: its effort goes with `…/model`) |
| `POST /v1/approvals/{id}` | `ApprovalAnswer` → `{ok}` (409 if already resolved; 400 `bad_scope`, `bad_rule` for an Always's `scope` and `rules`, see Approvals, "What Always covers") |
| `POST /v1/sessions/{id}/allow` | `{ids: string[]}` → `{allowed: string[], gone: string[]}`: "Allow these N", each of the session's asks named allowed as its own Allow (see Approvals). 400 `bad_ids` (none, over 50, not ids, or one waiting that is another session's or not a tool's: nothing is answered then); 404 `no_session` |
| `GET /v1/history/search?q=<words>` | → `{results: SearchHit[], partial: boolean}`: the sessions whose conversations have every word of `q` inside, newest first, 50 at most (see History search). 400 `bad_query` (fewer than 2 or more than 200 characters). An older daemon answers 404, and the phone filters on its own |
| `GET /v1/projects` | → `{projects: Project[], root}` |
| `POST /v1/projects` | `{name, git?: true}` → `{project}` creates `<root>/<name>` |
| `GET /v1/providers` | → `{providers: Provider[]}` |
| `GET /v1/models?provider=` | → `{providers: ModelList[]}`: the models each provider offers (only that one with `provider`), in the providers' order, for the new-session sheet (see Models) |
| `POST /v1/providers/{id}/check` | `{}` → `{provider}`: reads its balance now (OpenRouter; at most once in 10 s, sooner the last reading). 409 `no_balance` for a provider without a balance to read |
| `POST /v1/providers/check` | `{kind?, baseUrl?, apiKey?, name?, id?}` → `{check: ProviderCheck}`: an address and a key checked without saving them (the editors' Check; see Providers). `id`: an existing provider, whose saved key is used when none is sent, only at its own host. 400 `bad_kind`, `missing_url`, `bad_url`, `insecure_url`, `bad_key_format`; 404 `no_provider` |
| `POST /v1/providers` | `ProviderInput` → `{provider, check: ProviderCheck \| null, keyDropped: false}`. 400 `bad_kind`, `missing_url`, `bad_url`, `insecure_url`, `bad_key_format`, `bad_name` |
| `PUT /v1/providers/{id}` | `ProviderInput` → `{provider, check, keyDropped}`: the same checks as `POST`; 404 `no_provider` for an unknown id. `keyDropped`: the address moved to another host without a new key, so the saved key was dropped ("Paste the key for the new address") |
| `DELETE /v1/providers/{id}` | → `{ok}` (the built-in `anthropic` can't be deleted: 400 `cannot_delete`; 404 `no_provider`) |
| `GET /v1/files?path=<abs dir>` | → `Listing` (no `path`: the projects folder). See Files for what may be browsed and the error codes |
| `GET /v1/folders?path=<abs dir, ~ or ~/…>&hidden=1` | → `Folders`: a folder's own folders, names only, for the new-session sheet's folder browser (no `path`: the home folder). See Files, The folder browser |
| `GET /v1/file?path=<abs file>` | → the file's bytes to view: its Content-Type, max 20 MB (an attachment of the phone's own 30 MB), `Accept-Ranges`. `HEAD` too. See Files |
| `GET /v1/file?path=<abs file>&download=1` | → the file to save, streamed: `Content-Disposition: attachment` with its name (RFC 5987 `filename*`), max 500 MB, `Range: bytes=…` → 206 (a dropped download carries on; `If-Range` with its `ETag` or `Last-Modified`), 416 for a range past its end. `HEAD` too. See Files |
| `POST /v1/jobs/respawn` | `{ids?}` → `{results: [{id, job, ok, said}]}`: every `claude agents` job on an older Claude Code restarted, one `claude respawn <job>` each (see Session menu). 409 `not_agents` |
| `POST /v1/preview` | `{path}` or `{port}` → `{url}` (see Artifacts) |
| `GET /p/<token>/<file>` | → the previewed file or proxied page, no token: the signed URL `POST /v1/preview` returns (see Artifacts); 404 `preview_expired` |
| `GET /v1/usage` | → `Usage` |
| `POST /v1/usage/refresh` | `{}` → `{usage: Usage}`: a fresh look at Claude Code's /usage screen for the per-model weeks (and a reading of the 5-hour window and the week), answered once it's done (5–30 s; joins a look under way; one started in the last 30 s, or a signed-out account, answers at once with the usage as it is). See Per-model weeks |
| `GET /v1/mac/awake` | → `{awake: Awake}` (see Stay awake) |
| `GET /v1/mac/files` | → `{files: {browseOutsideProjects}}` (see Files, The setting) |
| `POST /v1/mac/files` | `{browseOutsideProjects: boolean}` → `{files}`; goes out as `{t:"files"}` with the sessions it opens or closes. 400 `bad_value` |
| `GET /v1/mac/update` | → `{update: ServerUpdate}` (see The server's own update) |
| `POST /v1/mac/update` | `{autoUpdate: boolean}` → `{update}`: Update this server automatically on or off, saved at once; on, the releases are looked at now. Goes out as `{t:"update"}`. 400 `bad_value` |
| `POST /v1/mac/awake` | `{mode?: "working" \| "backpack" \| "off", minBattery?: 5–50}` or `{extend: true}` (Backpack's "+2 h", in its last 10 minutes; 409 `too_early`, `not_backpack`) → `{awake: Awake}`. Backpack starts its 8 hours again each time it's chosen. 400 `bad_mode`, `bad_battery`; 409 `needs_setup` (Backpack's one-time setup isn't done), `no_battery` (a desktop Mac). See Stay awake |
| `GET /v1/stats?range=today\|week\|month\|all` | → `Stats`: how long Claude worked, tokens, sessions, prompts, tools, files, agents, records, projects and the last 30 days (see Statistics) |
| `POST /v1/account/check` | `{}` → `{account: Account}`: asks `claude auth status` now (at most once in 10 s; sooner, the last answer). See Account |
| `POST /v1/account/login` | `{where: "phone" \| "mac"}` → `{account}`: signs Claude Code in again in a login holder; `account.login` follows it. 409 `not_needed` when the account is ok, 400 `bad_where`; while one runs, the same one. See Account |
| `POST /v1/account/login/code` | `{code}` → `{ok}`: the sign-in page's code (`<code>#<state>`), typed into the running sign-in. 409 `no_login`, `not_ready` (the code prompt didn't show within 8 s, or a code is already on its way or being checked); 400 `bad_code` (not a whole code, or one from another sign-in's page) |
| `DELETE /v1/account/login` | → `{ok}`: stops the sign-in |
| `POST /v1/sessions/{id}/provider` | `{providerId}` → `{session, forked: boolean}`: continues the conversation on another configured provider; `forked`: in a new session, which `session` is. 404 `no_session`, `no_provider`; 409 `same_provider`, `busy` (Claude is at work, or waits on an approval or a dialog; Claude Code's own dialog in a session a limit stopped doesn't count; or a move of this session is still under way), `terminal_busy`. See Account |
| `DELETE /v1/devices/me` | → `{ok}`: forgets this phone, its device certificate with it |
| `POST /v1/device/cert` | `{csr: base64 DER PKCS#10, CN=<deviceId>, EC P-256}` → `{cert: base64 DER, ca: base64 DER, fingerprint}`: this device's certificate for the LAN listener's mutual TLS (2 years; a new one replaces the old). 400 `bad_csr`, 503 `no_tls` (see Local network (direct HTTPS)) |
| `GET /v1/qa` | → `{url}` a signed URL for the testing checklist page |
| `GET /v1/automations` | → `{automations: Automation[], paused, features}` (see Automations; `features`: what it can do of Workflows, absent from an older daemon) |
| `POST /v1/automations` | `AutomationInput` → `{automation, token?}` (`token`: a webhook trigger's, only in the answer to the save that made it; see Automations, "Workflows") |
| `PUT /v1/automations/{id}` | `AutomationInput` → `{automation, token?}`: a whole replace (an old client's save without `steps`: see "Workflows", Old clients; 409 `has_steps`); a run in progress keeps the old definition. Saved again with the same action, a paid one needs no new `confirmPaid`; switched on by it, the time it was off isn't caught up |
| `DELETE /v1/automations/{id}` | → `{ok}`: a run at work is stopped first, as Stop does (Esc, `stopped`, `by_user`); its runs go too (one whose terminal is still to be tidied stays until it is); the sessions it started stay |
| `POST /v1/automations/{id}/enabled` | `{enabled}` → `{automation}` |
| `GET /v1/automations/{id}` | → `{automation}` |
| `POST /v1/automations/{id}/run` | `{}` → `{run}`: Run now, also while paused or off (it never turns an "only the next time" one off). The same gates as a fired run, except that overlap answers 409 `running`, the daily cap 409 `daily_cap`, and an account or provider problem 409 `limited` (with `until`, the reset, when known), `signed_out`, `no_plan`, `on_hold`, `no_credit`, `provider` or `no_provider` (the reason in `message`), and a provider that costs money it wasn't saved with `confirmPaid` for 409 `confirm_paid`. The run's `state` says what it does: `running`, or `queued` behind two others |
| `GET /v1/automations/{id}/runs?before=<ms>&limit=30` | → `{runs: Run[], total, skipped}`: newest first (`limit` 1–100; `before`: runs fired before then); `total` and `skipped` count every run kept (50 at most), not just the page |
| `POST /v1/automations/runs/{runId}/stop` | `{}` → `{run}`: a queued or waiting run is dropped; a running one gets Esc in each of its sessions. It ends `stopped`, `by_user` |
| `POST /v1/automations/runs/{runId}/retry` | `{fromStep?}` → `{run}`: Retry an ended run (failed, skipped, stopped, or done): Run now with what fired it (the session, the placeholders' values); the new run's `retryOf` names it. With `fromStep`, from that step on, the steps before it taken over from that run (see "Workflows"; 400 `bad_step`, 409 `steps_changed`). 409 `active` while it still runs, the Run now 409s otherwise; 404 `no_run` |
| `POST /v1/automations/runs/{runId}/answer` | `{stepId, answer}` → `{run}`: answers its ask step; any paired device or the Mac app; the first answer counts. 400 `bad_answer`, `bad_step`, 409 `not_asking`, 404 `no_run` (see "Workflows", Answering an ask) |
| `POST /v1/automations/{id}/fire` | the payload (≤ 64 KB, text) → `{run}`: fires a webhook trigger as the event would, with a paired device's token. 409 `paused`, `off`, `not_webhook`; 413 `too_large`; 415 `not_text` (see "Workflows") |
| `POST /v1/automations/{id}/token` | `{}` → `{automation, token}`: a new token for its webhook trigger, the old one stops at once. 409 `not_webhook` |
| `POST /v1/automations/check` | `{trigger}` → `{trigger, next: number[]}`: a trigger checked as a save would (the same codes and `field`s), with its next three due times, for the editor |
| `POST /v1/automations/paused` | `{paused}` → `{paused}`: pause or resume them all |

File paths must be ones the phone may browse (see Files: the projects folder, and with Browse
outside projects on, sessions' own folders in the home folder) or one of the phone's attachments;
anything else is 403 `outside_projects`, or `locked_folder` for a key place (see Files).

## WebSocket `GET /v1/ws`

The server pushes everything; there are no subscriptions. Messages are JSON
objects with a `t` field:

- `{t:"snapshot", snapshot: Snapshot}` right after connecting (and after resyncs).
- `{t:"session", session: Session}` a session was added or changed.
- `{t:"session.removed", id}`: a session deleted for good (a `claude agents` job deleted from the
  Home card's menu, "Session menu"). A session that ends comes as `session` with `state: "ended"`.
- `{t:"job.died", sessionId, job, reason: string | null, at}`: a `claude agents` job died (a crash,
  an error exit, its process gone mid-turn, an API error that ended it), once per death; `reason` is
  Claude Code's, masked, at most 120 characters; `at` epoch ms (see Session menu, "A job that died").
  Its `session` (with `died`) comes first. Clients that don't know it ignore it.
- `{t:"event", sessionId, event: Event}` a new or updated event (same `id`
  replaces the previous version; `assistant` text grows while streaming, and a command
  event comes again with `command.kind: "skill"` once its skill's body is read).
- `{t:"event.removed", sessionId, id}`: an event the daemon made is taken back (the notice that an
  ask is waiting in the terminal, once it's answered; see Approvals). Gone from `events` too. A
  client that doesn't know it keeps the event until it loads the session again.
- `{t:"agent", agent: Agent}` and `{t:"workflow", workflow: Workflow}`: an agent or workflow
  started or changed (same `id` replaces). A status change comes at once; what an agent does now
  and its tokens at most once a second per session. Clients that don't know them ignore them.
- `{t:"approval", approval: Approval}` a new approval request.
- `{t:"approval.resolved", id, sessionId, decision: "allow"|"deny"|"unknown", by: "phone"|"mac"|"terminal"|"timeout"}`
  (`by: "terminal"` means it was answered on the Mac; the daemon can't tell how, so `decision` is `"unknown"`)
- `{t:"usage", usage: Usage}`
- `{t:"codex", codex: Codex | null}`: Codex changed: turned on or off, connected or not, its account or
  its usage windows (once per change; a window that resets is said then). See Codex.
- `{t:"claude", claude}`: Claude Code as the snapshot's `claude` says it, when sessions in `claude
  agents` were turned on or off (`POST /v1/claude`, or `POST /local/settings {claudeAgents}`).
- `{t:"account", account: Account}`: the account changed (once per change, however many sessions
  brought it; see Account).
- `{t:"battery", battery: Battery | null}`: the Mac's battery percentage changed (by 1 or more),
  it started or stopped charging, or the power source changed (see Battery). Minutes left alone
  send nothing; the snapshot has the latest.
- `{t:"awake", awake: Awake}`: Stay awake changed (the mode, holding or not, the lid, a guard; see
  Stay awake).
- `{t:"files", files: {browseOutsideProjects}}`: Browse outside projects was turned on or off (see
  Files); a `{t:"session"}` follows for each session whose `browsable` changed.
- `{t:"update", update: ServerUpdate}`: the server's own update changed: the setting turned on or
  off, a release downloaded and waiting for a quiet moment, its install begun (see The server's own
  update). An app from before it ignores it.
- `{t:"mac_sleep_soon", inSeconds, reason: "battery" | "hot" | "time", percent: number | null,
  stillAwake: boolean, otherApp: string | null}`: about two minutes before the daemon lets the Mac
  sleep (see Stay awake); `percent` is the battery's. `stillAwake`: another app's SleepDisabled
  (`otherApp` when macOS names it, e.g. "Amphetamine") keeps the Mac awake all the same, so say
  "Hypernova lets go…, but Amphetamine is still keeping your Mac awake", never that it will sleep.
- `{t:"mac_backpack_ending", inSeconds, until, extendBy}`: Backpack ends in about ten minutes
  (`until`, epoch ms; sent once per end, again for an extended one). Offer "+2 h" (`extendBy`,
  seconds): `POST /v1/mac/awake {extend: true}`. See Stay awake.
- `{t:"projects", projects: Project[]}` and `{t:"providers", providers: Provider[]}`
- `{t:"devices"}` a phone paired (nothing else in it).
- `{t:"lan", lan: {port, addresses, fingerprint} | null}`: the Mac's private addresses changed, or
  the LAN listener was turned on or off (see Local network (direct HTTPS)).
- `{t:"app", app: {versionCode, versionName} | null}`: the APK the computer hands out changed (a
  GitHub release fetched, `hnctl apk`, a dev build, or none any more), as the snapshot's `mac.app`
  would say it now; the phone updates its offer without reconnecting (see The app update). Looked
  at every 5 s and at once after a release is fetched; said only when it changed. An app from
  before it ignores the message (unknown `t`), and keeps the offer it read at connect.
- `{t:"speech", speech: Speech}`: the Mac's voice or speed changed. `{t:"speech.stop", id}` to the phone app that plays a play; the phone sends
  `{t:"speech.progress", …}` about its own plays; `{t:"speaking", speaking}` to the Mac app's feed
  alone. See Read aloud, "Plays".
- `{t:"automation", automation: Automation}`: an automation was added or changed (its `nextAt` and
  `lastRun` moving too); `{t:"automation.removed", id}`; `{t:"automations", paused, running,
  queued}`: paused or resumed, or the counts changed (see Automations).
- `{t:"run", run: Run}`: an automation's run was created or changed.
- `{t:"note", note: Note}`: an automation's notification, `to: "phone"`. A note with `ask` set asks a question its answers answer (see Automations,
  "Workflows", Answering an ask).
- `{t:"ping"}` every 25 s; the client answers `{t:"pong"}`. Clients reconnect
  once at once, then every 30 s (owner, 2026-10-31), and receive a fresh snapshot.

## Types

```ts
Snapshot = { mac: {id, name, daemonVersion, claudeVersion, projectsRoot,
                    app: {versionCode, versionName} | null,    // the Android app it hands out (see The app update)
                    battery: Battery | null,                   // null: no battery (see Battery)
                    awake: Awake,                              // see Stay awake
                    files: {browseOutsideProjects: boolean},   // see Files, The setting
                    update: ServerUpdate,                      // the server updating itself (see The server's own update)
                    tz: string,                                // the Mac's time zone (IANA, "Europe/Moscow")
                    platform?: "darwin" | "linux",             // the server's system; absent: an older daemon (a Mac)
                    unavailable?: {[feature]: string}},        // Mac-only features this server lacks, each with
                                                               // the sentence to show (see Mac-only features)
             usage: Usage, sessions: Session[], approvals: Approval[],
             projects: Project[], providers: Provider[],
             account: Account,                                   // see Account
             automations?: Automations,                          // absent: a daemon without automations
             lan: {port, addresses: string[], fingerprint, deviceCert: boolean} | null,
                                                                 // the LAN listener (see Local network); null: off
             claude?: {available: boolean, version: string | null,    // Claude Code on this computer: found where
                       sdk?: boolean},                           // it's installed (npm's, nvm's and Volta's
                                                                 // places too), or its hooks are in. Absent:
                                                                 // an older daemon, where it always was.
                                                                 // `sdk`: Claude Code's SDK is in the daemon's
                                                                 // packages (see Claude Code's SDK); absent:
                                                                 // an older daemon, which always carried it.
                                                                 // `agents?: boolean`: sessions live in
                                                                 // `claude agents` (settings.claudeAgents, see
                                                                 // Session menu); absent: an older daemon
             codex?: Codex | null }                              // Codex as a second agent (see Codex); null: not
                                                                 // installed here; absent: an older daemon

Session = {
  id: string,                 // Claude Code session id (uuid); a Codex session's is its thread's id (uuid v7)
  agent?: "codex",            // an OpenAI Codex session (see Codex); absent for Claude Code's, as before Codex
  switchedFrom?: string,      // the session this conversation came from when it switched agents, and the one it
  switchedTo?: string,        // went on in (see Switching agents); absent for every other session
  title: string,              // custom title, AI title, else the first prompt, keys masked (see Reading transcripts)
  cwd: string, projectName: string,   // the folder's name; "Home" for the home folder itself
  origin: "hypernova" | "terminal",
  state: "starting" | "working" | "needs_you" | "idle" | "error" | "ended",
  providerId: string,
  model: string | null,       // the model it uses now (see A session's model): its last reply's id, a /model
                              // since, the status line's (with "[1m]" when on the 1M window), or the one asked
                              // for until the first reply ("opus"); null before any is known
  effort: string | null,      // Claude Code's reasoning effort ("low" … "max", see A session's effort): its status
                              // line's, else what it was started with or last set to from the phone; null when
                              // not known yet (its own default) and for Codex
  permissionMode: "default" | "acceptEdits" | "plan" | "bypassPermissions" | "auto" | "dontAsk"
                | "read-only" | "workspace-write",
                              // dontAsk: runs only what's allowed, skips the rest without asking (automations);
                              // read-only, workspace-write: a Codex session's sandbox (see Codex)
  automation: null | {id, name, runId},   // an automation started it (see Automations); kept across restarts
  browsable: boolean,         // the phone can browse its folder now (see Files): inside the projects folder, or in
                              // the home folder while Browse outside projects is on; false for a folder that's
                              // gone. Follows the setting live. The phone shows the Folder button and file links
                              // only when true
  outsideProjects: boolean,   // its folder is in the home folder, outside the projects folder and no key place:
                              // Browse outside projects opens it (whether or not that is on now)
  bypassAvailable: boolean,   // can switch to "bypassPermissions": always for a session the daemon runs; in a shared terminal only if Claude Code was started with bypass allowed (--allow-dangerously-skip-permissions, --dangerously-skip-permissions or --permission-mode bypassPermissions); true while in bypass. Sessions the phone starts in a terminal always allow it
  createdAt: number, lastActivityAt: number,      // epoch ms
  turnStartedAt: number | null,  // epoch ms: when it went into its turn (from idle, error or ended to starting,
                              // working or needs_you), as the daemon saw it; null out of a turn ("idle",
                              // "error", "ended", and a session whose agents alone work). Kept through a stop
                              // for the owner's answer. A session the daemon first sees busy (as after a daemon
                              // restart) counts from Claude Code's registry stamp, statusUpdatedAt, else from
                              // then. The session sheet's "This turn" and Home's Working card's "for 12 min";
                              // absent: an older daemon
  lastText: string | null,    // last assistant text, trimmed to 200 chars
  activity: string | null,    // what it's doing now, e.g. "Bash: ./gradlew build"
  todo: null | {active: string | null, done: number, total: number},
                              // Claude's to-do list in short (the list GET …/todos returns, see Session usage
                              // and the to-do list): the item in progress as it reads while under way
                              // ("Running the tests"; its text without one; at most 120 characters; null when
                              // none is), how many are completed, and how many there are. Worked out again
                              // after each TodoWrite, TaskCreate or TaskUpdate event; null when Claude made no
                              // list. Home's Working card says "Running the tests 3/7" with it. Absent: an
                              // older daemon
  pendingApprovalIds: string[],
  stoppedForAway: boolean,    // Codex: auto-denied after 10 min → show the Continue button (Claude Code's
                              // ask goes back to its terminal instead, see Approvals; never set for it)
  blockedCalls: number,       // calls refused without the owner, not yet retried or dismissed: its blocked calls (see Blocked calls)
  agentOutdated?: {runs, installed},  // a `claude agents` job whose process runs an older Claude Code than the one
                              // installed ("2.1.312", "2.1.314"): `version` in its registry entry against the file
                              // `claude` resolves to (versions/<version>; else `claude --version`, once per install).
                              // Absent otherwise and for a job with no process. Restart: see Session menu
  died?: {reason: string | null},  // a `claude agents` job Claude Code marked failed: it died, with why in its
                              // words (null when it doesn't say). Until it works again or is deleted; absent
                              // otherwise and from an older daemon. Home's Completed card says it (see Session
                              // menu, "A job that died")
  contextPct: number | null, costUsd: number | null,
  queue: [{id, text, files?: Attachment[], at, clientId: string | null}],
                              // prompts waiting until Claude can take them, oldest first: Claude Code's
                              // queue (typed while Claude works) or an SDK session's; text at most 300
                              // characters (then "…"), files apart, pasted text in place of its tags, a
                              // "!" line the phone sent with its "!"; clientId: the phone send it came
                              // from, null when typed on the Mac
  queued: number,             // queue.length
  held?: [{id, text, files?: Attachment[], at, clientId: string | null}],
                              // Fork: prompts held until Claude Code's input box is back, oldest first
                              // (see Held prompts); text whole, files apart; absent when none
  term: {id, mac: number, phone: number} | null  // its shared terminal; mac/phone = how many have it open
  agentsRunning: number,      // agents its main agent started that run now (see Agents and workflows); with
                              // state, the working rule: working = state working/starting or agentsRunning > 0,
                              // needs_you outranks it (the phone's Session.busy; one rule in every client)
  blocked: null | {            // why Claude can't reply here now (see Account); a real reply clears it
    kind: "limit" | "model" | "no_credit" | "logged_out" | "no_subscription" | "on_hold" | "provider",
    text: string,              // Claude Code's line, request ids removed, at most 200 characters
    resetsAt: number | null,   // epoch ms
    autoResumeAt: number | null, // it carries on by itself then: resume.at while resume.state is "armed", else null
    resume?: null | Resume     // limit, model and provider kinds only: whether it carries on by itself (see Auto-continue)
  },
  endedBy: null | "mac_restart",  // why it ended when nobody ended it: open when the Mac restarted or shut down
                              // (see After a restart of the Mac); null otherwise, and whenever it isn't "ended"
  menu: null | {               // a menu or dialog its terminal shows instead of the input box (none while an approval is open)
    id,                        // title and option labels, hashed; answers name it
    title: string | null, body: string[],
    options: [{label, detail: string | null, current: boolean}],
    cursor: number,            // the option Enter picks now
    more: boolean,             // it scrolls past what the screen shows
    cancel: boolean,           // Esc closes it
    alt: null | {enter, key, label},  // the footer's second way to pick ("s to use this session only")
    permission: null | {       // Fork: Claude Code's own permission dialog (see Approvals, "In the terminal")
      tool: string | null,     // its title: "Bash command", "Edit file"
      command: string | null,  // what it would run or change, between its dashed rules, whole
      question: string,        // "Do you want to proceed?"; options start with Yes and have a No
      why: Why | null          // why it asked: the reason the dialog prints, else (a Bash dialog) the
                               // daemon's reading of the rules; null when neither says (see Approvals,
                               // "Why it asked"); absent from an older daemon
    },
    steps: null | {            // AskUserQuestion's form asking several questions (an ask handed back to the
                               // terminal): its tab row "←  ☐ Versioning  ☒ First version  ✔ Submit  →",
                               // not the title (the question under it is); absent from an older daemon
      labels: string[],        // each question's header as the row shows it (cut with "…" when narrow)
      answered: boolean[]      // ☒: it has an answer. Submit is the tab after the last; which is open
                               // shows only in colour, so it isn't said
    }
  }
}

Event =
  | {id, kind: "user", ts, text, files?: Attachment[],  // files: attached on a phone, apart from the text
       command?: Command,          // a slash or "!" command; text is the command as typed
       clientId?: string}          // the phone send it came from, when the daemon knows it
  | {id, kind: "assistant", ts, text, streaming: boolean}
  | {id, kind: "thinking", ts, chars}
  | {id, kind: "tool", ts, toolUseId, name, summary, input: object,  // input trimmed; an MCP call's
                                                                       // summary is its argument (Reading transcripts)
       status: "running" | "done" | "error", output: string | null,   // trimmed (last 64 KB)
       exitCode: number | null, durationMs: number | null,
       diff: Diff | null, path: string | null}    // path: file the tool read/wrote
  | {id, kind: "approval", ts, approvalId, summary, resolution: null | "allow" | "deny" | "timeout" | "terminal"}
  | {id, kind: "result", ts, ok, durationMs, costUsd, turns, error: string | null}
  | {id, kind: "notice", ts, text, tone: "info" | "warn" | "error",
       command?: string,           // the output of a command: that command's user event id
       unsent?: Unsent,            // queued prompts that left the queue without reaching Claude
       blocker?: string}           // an API error that stopped Claude: Session.blocked's kind (see Account)

Command = { name: string,         // "/effort", "/telegram:access"; "!" for a shell command
            args: string,         // "ultracode", "" when none; for "!" the command line
            kind: "local" | "prompt" | "skill" | "bash" }
            // Claude Code's own; a prompt or plugin command; a skill; "!" mode

Unsent = { reason: "returned" | "dropped",   // back in the terminal's input box; or cleared, refused
                                            // by a hook, or dropped by a Stop in an SDK session
           prompts: [{text, clientId: string | null}] }   // text as in session.queue, without its @-mentions

Diff = { path, added, removed, hunks: [{oldStart, oldLines, newStart, newLines, lines: string[]}] }
       // lines start with " ", "+" or "-"

Approval = {
  id, sessionId, createdAt,
  expiresAt: number | null,                  // createdAt + 10 min, when it leaves the phone for nobody
                                             // answering (see Approvals); null: never (it waits)
  autoDeny: boolean,                         // at expiresAt Hypernova says no (Codex, an automation's
                                             // run): only then does the phone count down to it
  kind: "tool" | "question" | "plan",
  toolName, summary,                         // e.g. "Bash: git push origin main"
  input: object,                             // trimmed tool input
  reason: string | null,                     // why Claude Code asks
  canAlways: boolean,
  alwaysRules: string[],      // the allow rules in Claude Code's suggestion for Always, as a settings
                              // file writes them ("Bash(npm test *)"); [] when it holds none (a mode,
                              // a folder) or there's no Always
  alwaysScope: "session" | "project" | "everywhere" | null,
                              // where Claude Code's suggestion puts them (localSettings and
                              // projectSettings are "project", userSettings "everywhere")
  why: Why | null,            // why it asked (see Approvals, "Why it asked"); null for a question, a
                              // plan, Codex, or when the rules couldn't be read; absent from an older daemon
  canSession: boolean,        // WebFetch, WebSearch, Read, Glob, Grep, LS: "all this session" offered
  questions: Question[] | null,              // kind "question" (AskUserQuestion)
  plan: string | null,                       // kind "plan" (ExitPlanMode markdown)
  agentId: string | null,                    // an agent the main agent started asks, not the main agent
  agentLabel: string | null                  // what that agent is called ("Review the parser", "survey:agents")
}
Question = { question, header, multiSelect, options: [{label, description}] }

ApprovalAnswer =
  | {decision: "allow" | "deny" | "always" | "session", message?: string}
  | {decision: "always", scope: "session" | "project" | "everywhere", rules?: string[]}
                              // where the rule goes and, edited, the rules (1 to 20, "Tool" or
                              // "Tool(pattern)", 2000 characters each); no rules: Claude Code's.
                              // No scope: Claude Code's suggestion as it is (see Approvals)
  | {decision: "allow", answers: {[questionText]: string}}  // labels; multi-select joined by ", " (a label
                              // with ", " or a quote in it JSON-quoted, as Claude Code joins them); the
                              // phone sends one for every question (Claude Code would take fewer)

Usage = { fiveHour: Window | null, sevenDay: Window | null,
          models: ModelWindow[],              // the weeks some models have of their own (Fable,
                                              // Sonnet), from /usage; [] when none or unread; absent
                                              // from an older daemon (see Per-model weeks)
          rate?: Rate | null,                 // the spend pace, only while a session on the subscription
                                              // or its agents work (one on another provider doesn't
                                              // spend these windows); null otherwise; absent from an
                                              // older daemon
          source: "official" | "none" | "stale", observedAt: number | null }
          // "stale": signed out, no plan or on hold (Account): the last numbers known, not rolled
          // into new windows (the status line stops reporting limits then). source and observedAt
          // are the official readings' (status lines, and the usage probe's look at /usage: see
          // Per-model weeks); models have their own source
          // fiveHour null with sevenDay set: Claude Code reports no 5-hour window (a plan without
          // one, Max from 2026-10-31); clients show no 5-hour window then (see "No 5-hour window"
          // under Per-model weeks). Both null: nothing read yet.
Window = { pct: number, resetsAt: number }   // pct 0..100, resetsAt epoch ms
Rate = { fiveHour: number | null,            // %/min, a least-squares line over the last ~10 minutes
                                             // of official readings (status lines, and the usage
                                             // probe's, every 2 minutes while something works);
                                             // null with fewer than 3 readings or under 4 minutes
                                             // of them; 0 when nothing moves
         sevenDay: number | null,            // the same over the last ~30 minutes (min. 12 of them)
         fiveHourEtaMs: number | null,       // time until 100% at this pace, to the minute; null when
         sevenDayEtaMs: number | null,       // the pace is ~0, unknown, or the reset comes first
         fiveHourLimitAt: number | null,     // the same projection as a moment (epoch ms, to the minute),
         sevenDayLimitAt: number | null,     // counted from the last reading: it holds still between
                                             // readings, so every client shows the same "limit at 14:05"
                                             // (never earlier than now); absent from an older daemon
         working: boolean }                  // always true (a Rate exists only then)
// The history restarts at a window's reset and ignores the forced 100% of a limit Claude Code hit
// (daemon/src/usage-rate.ts). The pace is rounded to 0.01, so it doesn't change every few seconds.
ModelWindow = { label: string,               // "Fable", "Sonnet": the name in "Current week (…)",
                                             // without " only"
                pct: number,                 // 0..100, whole (Claude Code floors it)
                resetsAt: number | null }    // epoch ms, to the minute; null when the screen gave none
// Usage also has modelsObservedAt?: number | null (when models were last read),
// modelsStale?: boolean (the last look couldn't read them; these are the last good ones) and
// probe?: {ok: boolean, since: number | null}: false after 3 looks in a row couldn't read /usage,
// [since] the first of them (clients say "per-model weeks not read since 14:05"); true again with
// the next good look

Project = { path, name, lastActivityAt: number | null, sessionCount }

Listing = { path,                       // the folder's real path
            entries: FileEntry[],       // folders first, then files, by name; at most 2,000
            truncated: boolean,         // there are more than 2,000: "2,000 of 51,234. Open a subfolder."
            total: number }             // how many entries the folder has (.DS_Store left out)
FileEntry = { name, path, kind: "dir" | "file",   // a link to a folder is a "dir"
              size, mtime, mime: string | null,     // mime: by its extension; one without an extension by its
                                                    // first bytes (text, else application/octet-stream)
              locked?: "secret" | "outside" | "folder" }  // listed but can't be opened: a file with keys (.env,
                                                    // credentials.json, *.key, *.pem…), a link that leads
                                                    // outside the folders the phone may open, or a key place
                                                    // (~/.ssh, ~/Library/Keychains…) or folder of keys (see Files)

Provider = { id, name, kind: "anthropic" | "openrouter" | "custom",
             baseUrl: string | null, hasKey: boolean,
             models: { default?: string, opus?: string, sonnet?: string, haiku?: string },
             builtIn: boolean,
             status: null | {state: "no_credit" | "bad_key" | "limited" | "down", text, since,
                              resetsAt: number | null,   // "limited" only, and only when the provider said
                              budget: boolean},          // "no_credit" from a cap someone set (the key's limit,
                                                         // a budget), not the account's balance
             // its trouble, until it replies again or is edited ("limited" also ends at its reset,
             // else 3 hours on, which is never shown; "down" after 10 minutes); always null for the
             // built-in one (its trouble is the Account's). Clients show an unknown state generically
             balance: null | Balance,    // OpenRouter only (see Providers)
             paid: boolean }             // running on it costs money (false only when the owner said so,
                                         // and for the built-in one); automations ask before they do
Balance = { remaining: number | null,   // USD left under the key's cap or budget; null: the key has no cap
                                        // (the account's own balance isn't readable with it)
            usedToday: number | null,   // USD spent today (UTC)
            freeLeft: number | null, freeLimit: number | null,   // ":free" model requests left today, of
            checkedAt: number,          // when it was read (epoch ms)
            error: null | "bad_key" | "unreachable" }   // the last read failed: the numbers are the last good ones
ProviderInput = { name, kind, baseUrl?, apiKey?, models, paid? }   // apiKey omitted or "" = keep (at the same
                                         // host); name trimmed, 40 characters at most, made unique (" 2");
                                         // paid omitted = keep (new: true)
ProviderCheck = { state: "ok" | "bad_key" | "no_credit" | "limited" | "not_found" | "unreachable",
                  text,                  // in words: "Key works. $4.20 left.", "Key works. 12 models.",
                                         // "Gateway refused this key.", "Nothing answers at this address."
                  models: string[] | null,      // a custom provider's /v1/models ids (at most 50), when "ok"
                  balance: Balance | null,      // OpenRouter's, when read
                  checkedAt }

NewSession = { projectPath?: string, newProject?: {name, git?: boolean},
               prompt: string, providerId?: string, model?: string,   // model: a ModelOption.value (see Models)
               permissionMode?: string, attachments?: string[],   // prompt may be empty with attachments
               agent?: "claude" | "codex",   // Codex (see Codex): its model a CodexModel.value, its permissionMode
               effort?: string, clientId?: string }   // read-only (the default) or workspace-write, effort one of the
                                                      // model's; providerId is ignored. clientId: the first send's name
                                                      // Claude Code: effort one of low, medium, high, xhigh, max
                                                      // (`--effort`; none: its own default; 400 unknown_effort)

Attachment = { path: string, name: string, mime: string | null, size: number }

Agent = { id,                         // "a" and 16 hex digits
          sessionId,
          toolUseId: string | null,   // the Agent call that started it: in the session's events, or in
                                      // parentAgentId's own transcript
          parentAgentId: string | null,
          workflowId: string | null,  // the workflow run it belongs to, and its phase there
          phase: string | null,
          label: string,              // "Review the parser"; a workflow's agents "survey:agents"
          type: string,               // "Explore", "general-purpose", "workflow-subagent", "fork"…
          model: string | null, background: boolean,
          status: "running" | "done" | "error" | "stopped",
          activity: string | null,    // while running: "Bash: ./gradlew build"
          startedAt: number, endedAt: number | null, lastActivityAt: number,   // epoch ms
          tokens: number | null,      // as Claude Code counts them (Agents and workflows, "Tokens"); null before its first reply
          toolUses: number | null,
          result: string | null }     // its report, at most 600 characters, keys masked

Workflow = { id,                      // the run id, "wf_…"
             sessionId, toolUseId: string | null, taskId: string | null,
             name: string,            // meta.name from its script
             summary: string | null,
             status: "running" | "done" | "error" | "stopped",
             phases: [{title, detail: string | null}],   // its script's, then any its agents name
             agentCount: number, done: number, failed: number,
             startedAt: number, endedAt: number | null,
             tokens: number | null, toolUses: number | null }
```

## Providers

Claude Code supports other providers through environment variables. A provider
profile becomes, for that session's claude process:

| Field | Environment |
|---|---|
| `anthropic` (built in) | nothing: the owner's normal Claude Code login |
| `baseUrl` | `ANTHROPIC_BASE_URL` |
| `apiKey` | `ANTHROPIC_AUTH_TOKEN`, with `ANTHROPIC_API_KEY=""` |
| `models.default` | `ANTHROPIC_MODEL` |
| `models.opus/sonnet/haiku` | `ANTHROPIC_DEFAULT_OPUS_MODEL` / `_SONNET_` / `_HAIKU_` |

OpenRouter's preset uses `baseUrl = https://openrouter.ai/api`. "Custom" accepts
any Anthropic-compatible endpoint (LiteLLM, a local gateway, other vendors'
Anthropic-compatible APIs). Keys are stored in `<support>/providers.json` (0600)
and never sent back to clients (`hasKey` only). Usage meters only apply to the
built-in provider: other providers have no 5-hour or weekly windows, only credit, rate limits,
keys and being down (`Provider.status`, see Account). A session on another provider gets no
`fiveHour`/`sevenDay` in its usage strip (`SessionUsage`, `usageSource: "none"`).

**Addresses and keys** (`daemon/src/store.ts`). Claude Code adds `/v1/messages` to
`ANTHROPIC_BASE_URL`, so a saved address is trimmed and loses a trailing `/`, `/v1/messages`
or `/v1` (OpenRouter documents `https://openrouter.ai/api/v1`, which would otherwise call
`/api/v1/v1/messages`), and its query and fragment. Only `https:`, or `http:` to this computer,
a private network (10/8, 172.16/12, 192.168/16, link-local, `fc00::/7`), Tailscale (100.64/10,
`*.ts.net`) or a `.local` name: anywhere else 400 `insecure_url` ("The key would travel
unencrypted"). No credentials in the address (400 `bad_url`). A key is trimmed and must be
printable ASCII (400 `bad_key_format`: a pasted newline would break every request). A saved key
never follows the address to another host: an edit that moves it without a new key drops the key
(`keyDropped`). Names are at most 40 characters and unique ("OpenRouter 2").

**Check** (`daemon/src/providers-check.ts`). A new key or address is checked when it's saved (the
answer waits for it, 8 s at most, as `check`), and `POST /v1/providers/check` checks one without
saving it. A custom provider: `GET {baseUrl}/v1/models` with the key as `x-api-key` and
`Authorization: Bearer` (free; nothing reaches a model). 200 → `ok` with its model ids; 401/403 →
`bad_key` (also the provider's `status`, when saved); 402 → `no_credit`; 429 → `limited`; 404/405 →
`not_found` (something answers, but not as an Anthropic-compatible API); anything else, or no
answer → `unreachable`. OpenRouter: the balance read below (`ok` with its `balance`, `bad_key`, or
"Saved. Couldn't reach OpenRouter to check the key."). Never through a redirect; neither the key
nor the answer is logged or kept.

**A deleted provider** (`DELETE`) is gone for good, but never stands in for the subscription: an
id that no longer exists is a removed provider, not `anthropic`. A new session on it → 404
`no_provider` (`POST /v1/sessions`, also a resume or a move back); an SDK session's next prompt
isn't sent (a notice "Not sent. Its provider was removed…"); SDK sessions and ended sessions on it
show `blocked: {kind: "provider", text: "Its provider was removed. Move this session to another
provider."}` until moved. A terminal still open keeps the provider it started with until it
ends, and the errors it meets (a refused key, credit gone) are nobody's: neither the Account's
nor a provider status.

**Balance** (`daemon/src/balance.ts`; OpenRouter only): `GET {baseUrl}/v1/key` with the provider's
key, at the provider's own address (where Claude Code sends it anyway), never through a redirect,
8 s at most. `remaining` is `effective_budget.remaining_usd`, else `limit_remaining`; `usedToday`
is `usage_daily`; `freeLeft`/`freeLimit` come from `free_model_daily_requests`. Nothing else of the
answer is kept (not its `label`), and neither the key nor the answer is logged; a balance lives in
memory only. The account's own balance (`/credits`) needs a management key and is never claimed.
It's read when a key is saved (the save answers after it, so the editor can say "Key works · $4.20
left"), on `POST …/check`, while a session works on the provider once the last reading is 5
minutes old, and when the provider runs out of credit: at once, then every 2 minutes for half an
hour, then every 15, until the credit is back or a day has passed. Never more often than once a
minute (10 s for `/check`). A 401 → `status: "bad_key"` ("… refused the key"). More than a cent
left after a `no_credit` that began before the read → the status ends (credit is back; its
sessions become ready, see Auto-continue). `remaining: null` can't tell, so the status holds until
a reply or an edit.

## Models

`GET /v1/models` (`daemon/src/models.ts`): what each provider can start a session on, for the
phone's model picker. Every list is the provider's whole offer; the phone groups and searches it.

```
ModelList   = {providerId, source: "claude" | "builtin" | "openrouter" | "settings",
               models: ModelOption[], checkedAt: number | null, error: null | "unreachable"}
ModelOption = {value: string | null, name, model: string | null, description: string | null,
               context: number | null, price: {input, output} | null, older: boolean,
               slot: "default" | "opus" | "sonnet" | "haiku" | null}
```

- `value` is what `NewSession.model` takes: `null` is the provider's default (no `--model`), else an
  alias (`opus`, `fable`) or an id (`claude-opus-4-8`, `openai/gpt-5`). `model` is the id it runs
  when known, `context` its context window in tokens, `price` dollars per million tokens in and out
  (OpenRouter's catalogue only), `older` an earlier version (listed after the current ones), `slot`
  the provider setting that names it.
- **Claude (the subscription), `source: "claude"`:** Claude Code's own list, the one its `/model`
  picker shows for this account's plan: `default` (as `value: null`), the aliases (`opus`, `fable`,
  `sonnet`, `haiku`), then earlier versions by id (`older: true`). It comes with the `initialize`
  answer of an SDK process started in the home folder with hooks off and no prompt (the same ask as
  the "/" list, whose answers also bring it), so nothing reaches a model. Kept an hour. Until Claude
  Code has answered (it isn't installed, it timed out after 15 s), `source: "builtin"` with
  `error: "unreachable"`: Default, Opus 5.5, Fable 5.1, Sonnet 5.5 and Haiku 5.5. Claude Code puts
  the default's model in its description ("Opus 5.5 · Best for…"); the daemon drops that part, and
  the phone names the default's model from `model`.
- **OpenRouter, `source: "openrouter"`:** the provider's own models first (its `default` slot, always,
  as `value: null`; then `opus`, `sonnet`, `haiku` as those aliases), then its catalogue,
  `GET {baseUrl}/v1/models`, at the provider's own address, without the key (the catalogue is
  public) and never through a redirect, 8 s at most: newest first, only models whose
  `supported_parameters` include `tools` (Claude Code can't work without them), not the `:batch`
  copies, at most 800, one the settings already name not listed twice. Only `id`, `name` (without
  its "Vendor: " prefix), `created`, `context_length` and `pricing` are read. Kept six hours; a
  failed read keeps the last list (or just the settings' models) with `error: "unreachable"` and is
  tried again after five minutes.
- **Any other provider, `source: "settings"`:** the models its settings name, as above.
- The phone asks when the new-session sheet opens, and shows a model with a week of its own
  (`Usage.models`, "Fable", "Sonnet"; matched by the first word of its name) with that week's meter.

## Session menu

The Home card's long-press menu. A session has `agentJob` (the job id) when it is a `claude agents`
job (settings.claudeAgents); with that setting the daemon lists every job `claude agents --json --all`
shows, finished ones too (`idle`, on Home rather than History; one blocked on the owner is `needs_you`).
The setting is on for a Linux install (`hypernova setup` writes it while settings.json doesn't say,
never over a `false`), and the phone turns it on or off (`POST /v1/claude {agents}`, Settings →
Agents & providers → Claude Code); the daemon takes it without a restart (each poll reads it).

- `POST /v1/sessions/{id}/rename` `{name}`: Claude Code's `/rename <name>`, typed into its terminal
  (a `claude agents` job through `claude attach`, which wakes a stopped one). `{session}`; 400
  `bad_name`; 409 `not_open` for an ended session that isn't a `claude agents` job (it would be
  resumed just for that), `no_terminal` for one Hypernova can't type into, and the prompt route's 409s.
- A job's session also has `agentRunning`: its process runs (Claude Code's registry has it), idle
  or not. The phone offers Stop while it works, waits on the owner, or idles with `agentRunning`;
  not for one done or stopped (`false`). An older daemon doesn't send it.
- `POST /v1/sessions/{id}/stop`: `claude stop <job>` (the conversation is kept), whatever its state. `{session}`.
- `DELETE /v1/sessions/{id}`: `claude rm <job>`, then `session.removed`. `{ok: true}`.
- `POST /v1/sessions/{id}/respawn`: `claude respawn <job>`, so the job runs the installed Claude Code
  (Claude Code updates itself; a job keeps its binary until then). Interrupts a job at work. The
  phone offers it (Restart, asked first) only with `session.agentOutdated`. `{session}`, without
  `agentOutdated` (the next registry read has the new process).
- All three: the `claude attach` holder ends first; 409 `not_agents` for a session with no `agentJob`; 409 `agents_refused` with Claude Code's
  own words when it refuses (`rm` keeps a worktree with unpushed work).
- `POST /v1/jobs/respawn` `{ids?: string[]}` → `{results: [{id, job, ok, said}]}`: Restart all, one
  `claude respawn <job>` after another for every session with `agentOutdated` (with `ids`, only
  those of them), each one's `claude attach` holder ended first; never `claude respawn --all`,
  which restarts current jobs too. A job on the installed Claude Code is passed over. `ok: false`
  is a refusal, `said` Claude Code's words either way; a restarted one loses `agentOutdated`. 409
  `not_agents` without settings.claudeAgents. It answers once all are done (up to half a minute
  each), so the phone waits up to two minutes. Home shows "N jobs run an older Claude Code" with
  Restart all (asked first: a job at work is interrupted), swiped away until another job falls behind.

**A Codex session** (2026-10-31; `daemon/src/codex/sessions.ts`) takes the same three routes over
Codex's app-server, with or without a terminal; the phone offers Rename and Delete always, Stop while
it works or waits on the owner, never Restart (409 `not_agents`).
- `rename` `{name}`: Codex's `thread/name/set {threadId, name}`, so `codex resume` shows it too.
  `{session, kept: "codex"}`. `kept: "hypernova"`: the name is in `codex-threads.json` only, for a
  thread Codex never wrote down or a Codex that doesn't know the call. 502 `codex_refused` with
  Codex's words; Codex's own errors when it isn't reachable (`codex_off`, …).
- `stop`: the `interrupt` route's Stop (the turn ends, the queue comes back named). `{session}`.
- `DELETE`: Codex's `thread/delete {threadId}`, for good (as `codex delete`; not `thread/archive`),
  after its shared terminal ends and a running turn is stopped; then the thread leaves
  `codex-threads.json` and `session.removed` goes out. `{ok: true}`. A thread Codex never wrote down
  is only forgotten. 409 `codex_too_old` from a Codex without the call (nothing is forgotten), 502
  `codex_refused`. Codex's `thread/deleted` (a `codex delete` elsewhere) makes the daemon forget the
  session the same way. Home's swipe doesn't delete a Codex card: the menu and the session sheet do.

**A job that died** (`daemon/src/job-deaths.ts`). Claude Code's job state file
(`~/.claude/jobs/<job>/state.json`) ends `done` (finished), `stopped` (`claude stop`, a signal) or
`failed` (died: crash, error exit, process gone mid-turn, an API error, Claude giving up), with the
reason in `detail` (seen in Claude Code 2.1.316). The daemon reads it for each job the list doesn't
show `working` or `blocked` (again only when the file changes) and takes `failed` from either.
- The session has `died: {reason}` while the job is failed; it goes when the job works again.
- `{t:"job.died"}` (see WebSocket) when a job last seen alive is failed now, once. Each job's last
  state is kept in `<support>/job-states.json`, so a restart of the daemon doesn't repeat an old
  death and does say one that came while it was down; a job first seen already failed says nothing.
- Never for the owner's doing: a job stopped, deleted or restarted through these routes in the last
  minute isn't a death, whatever it ends as; `claude rm` takes it off the list.
- A failed job whose detail ends `; respawning` (Claude Code starts it again) is said only if it's
  still failed a minute later.
- The phone: a banner, or a notification in Session updates (as a session stopping on an error:
  "<name> stopped on an error", the reason under it, a tap opens it); the Completed card's line
  `Stopped with an error` in red. Nothing in the chat.

## Blocked calls

A session's blocked calls (the phone's session sheet): calls refused without the owner. `auto_mode`: Claude Code's auto mode
classifier refused it (its PermissionDenied hook, see Hooks, or the tool result "Permission for this
action was denied by the Claude Code auto mode classifier. Reason: …" in the transcript, which stays
the fallback; one row for both). `away`: nobody answered a Codex approval for 10 minutes (see
Approvals; Claude Code's goes back to its terminal instead). Don't ask mode, deny rules and the owner's own no aren't listed (a retry can't get past
them). The same call refused again is one row, the newest refusal's; a call that runs later leaves
the list by itself; refusals more than a day old read from a transcript aren't listed; 20 per
session at most. `Session.blockedCalls` counts them, and a `{t:"session"}` goes out when it changes.

- `GET /v1/sessions/{id}/blocked` → `{blocked: BlockedCall[]}`, newest first.
- `POST /v1/sessions/{id}/blocked/{bid}/retry`: Allow & retry. The owner's approval is typed into
  the session the prompt route's way (never a fork: 409 `no_terminal` for one open in a terminal
  Hypernova doesn't hold), naming the call whole, so auto mode's classifier takes it as his
  consent: "I approve this call that auto mode blocked. Run it again now, exactly as it was:" (an
  `away` one: "I'm back, and I approve the call you were waiting on. …"), then `<tool>: <target>`.
  If Claude Code asks about the same call within 10 minutes, the daemon answers yes once without
  asking. → `{session, queued, blocked}`; the prompt route's 409s (the row stays), 404 `no_blocked`,
  409 `retrying` while one is on its way.
- `DELETE /v1/sessions/{id}/blocked/{bid}`: Dismiss. → `{blocked}`; 404 `no_blocked`.

Both keep the call's tool_use ids in `<support>/blocked-settled.json`, so reading the transcript
again (after a restart) doesn't bring it back.

```
BlockedCall = {
  id, sessionId,
  tool: string,               // "Bash", "Edit", "WebFetch", an MCP tool's full name
  target: string,             // what it would touch: the command, the file, the address, the query
                              // (another tool's input as JSON, 300 characters); keys masked as titles are
  input: object,              // its input as the chat's tool event has it, keys masked
  reason: "auto_mode" | "away",
  why: string | null,         // the classifier's reason, at most 200 characters
  at: number,                 // epoch ms, the newest refusal
  toolUseId: string | null
}
```

## Side questions

Claude Code's `/btw <question>` ("Ask a quick side question without interrupting the main
conversation"), from the session sheet's Commands tab. Claude Code runs it at once, beside a turn
that's running, and draws the answer in a panel in place of the input box; it keeps the answer in
memory only, so it never reaches the transcript. The daemon (`aside.ts`) types it, reads the answer
off the terminal's screen as it reads menus, keeps the last one per session (in memory: a restart
forgets it), and once it has it closes the panel with Space (never Esc: with the panel already gone,
Esc would stop Claude's turn), so the box is back for the next prompt.

- `POST /v1/sessions/{id}/btw` `{question}`: typed into the session's shared terminal (a `claude
  agents` job through `claude attach`, which wakes a stopped one) whether Claude works or not:
  never held (Held prompts) and never queued, and never a fork or a resume. The question goes on
  one line, 2000 characters at most. → `{aside}`, `asking`. 400 `empty`, `too_long`; 404
  `no_session`; 409 `unsupported_for_agent` (Codex), `asking` (the last one is still being
  answered), `needs_answer`, `no_terminal` (a session the daemon runs itself, one open in a terminal
  Hypernova doesn't hold, an ended one), `terminal_busy` (`claude attach` didn't open, or its holder
  shows another session: nothing is typed there), and the prompt route's `waiting`, `typing` (with
  `pending`), `starting`, `not_sent`.
- `GET /v1/sessions/{id}/btw` → `{aside}`: the last one as it stands (null when none). The phone
  asks again every second or so while it's `asking`.

```
Aside = {
  id, question: string,
  state: "asking" | "answered" | "failed" | "unseen",
  answer: string | null,     // answered: the panel's text, the terminal's line breaks inside
                             // paragraphs joined back
  error: string | null,      // failed: Claude Code's own words ("Rate limited")
  clipped: boolean,          // the answer filled the panel, so it may go on past what the screen
                             // showed: the terminal has it all
  askedAt: number, at: number
}
```

`unseen`: no answer could be read (the panel never showed with this question in 4 minutes, the
terminal went, or it showed another session); the phone says to open the terminal.

The panel as the daemon expects it (read from Claude Code 2.1.314's code, then seen on the owner's
phone 2026-10-30 in a `claude attach` holder 84 columns wide, `test/fixtures/btw-screens`): the
earlier questions (`/btw …`, without their answers, five at most, under `(+N earlier /btw)` when
there are more), the question two columns in, then the answer four in (`Answering…` with a spinner,
or `retrying in Ns`, until it's there; wrapped a few columns short of the screen's edge), then a
footer of hints (`↑/↓ to browse · c to copy · f to fork · x to clear history · Esc to close ·`),
which offers `c to copy` once there is an answer. On a narrow terminal the footer wraps ("Esc to" /
"close ·") and the status line's right side ("← 4 agents") shares its rows: the daemon reads the
footer's rows as one, without what sits far to their right. Seen live: `claude attach` draws the
panel itself, and the answer isn't in the transcript. Still to confirm: that the panel replaces the
input box rather than sitting over it (the daemon takes the box's going as "sent"); that Space
closes it; and that a terminal Claude Code thinks isn't in front doesn't hold the panel back
(Claude Code then asks unwatched and reopens the panel later, which the daemon waits for).

The phone asks it from the session's prompt box too: "/btw <question>" typed there goes through
this route, not the prompt route (the composer's text, never held). The chat shows its "/btw …"
line where it falls in time, from the phone's memory (the transcript never has it), and the answer
in a card over the box until it's swiped away; the Commands tab still shows the last one.

## Rules in effect

Claude Code's permission rules for a session, read-only, from the session sheet's Permissions tab
(`permission-rules.ts`): why a call asked or didn't.

- `GET /v1/sessions/{id}/permissions` → `{permissions: {sources: RuleFile[], managedOnly: boolean}}`.
  404 `no_session`; 409 `unsupported_for_agent` (Codex: its sandbox decides). Read fresh each time.

`sources`, highest precedence first, as Claude Code 2.1.314 reads them (its own code):
1. **`managed`**: the managed policy, `managed-settings.json` in `/etc/claude-code` (Linux) or
   `/Library/Application Support/ClaudeCode` (macOS), then each `*.json` in `managed-settings.d/`
   beside it, A to Z, not dotfiles (20 at most). Left out when there's no such file. Server-managed
   settings, MDM and the Windows policy key aren't files the daemon reads.
2. **`session`**: rules Hypernova gave the session itself, no file (`path: null`): an automation's
   `--allowedTools`, the tools allowed "for this session" from the phone and the rules an Always
   added for this session only (see Approvals, "What Always covers"). Left out when none.
3. **`local`**: `<cwd>/.claude/settings.local.json`; 4. **`project`**: `<cwd>/.claude/settings.json`;
   5. **`user`**: `settings.json` in the Claude folder (`CLAUDE_CONFIG_DIR`, else `~/.claude`).
   These three are always listed. `<cwd>` is the session's own folder, as the daemon has it; the
   route takes no path.

```
RuleFile = {
  source: "managed" | "session" | "local" | "project" | "user",
  path: string | null,
  state: "ok" | "missing" | "unreadable" | "invalid" | "too_big",
                              // invalid: not JSON, or not an object; too_big: over 512 KB, not
                              // read; unreadable: not a regular file, or no permission. Only an
                              // ok source has rules.
  allow: string[], ask: string[], deny: string[],
                              // permissions.allow|ask|deny as written; strings only, 200 a list,
                              // 300 characters a rule (longer ones end in "…")
  defaultMode: string | null, // permissions.defaultMode, a bare word only
  additionalDirectories: string[],   // 50 at most
  clipped: boolean            // something above was left out or cut
}
```

`managedOnly`: a managed file says `allowManagedPermissionRulesOnly: true` (at its top or under
`permissions`; which one Claude Code reads wasn't confirmed), so only the managed rules apply.
Deny rules win over ask, ask over allow, wherever each is written.

**Nothing else of a settings file leaves the daemon**: not the file, not `env`, `apiKeyHelper`,
hooks, MCP servers or any other key, and not a parse error's words (Node's quote the text). A file
is opened without blocking and checked to be a regular file on the open file itself.

## A session's model

`Session.model` is the model the session uses now, kept current for every client (the chip, the
usage strip):
- **every reply**: the model of the main agent's latest reply (an alias it started with, `opus`,
  gives way to the id it runs, `claude-opus-5-5`; clients shorten ids for chips);
- **a /model in the terminal** at once: its output ("Set model to Sonnet 5.5 (claude-sonnet-5-5)"),
  and the status line, which Claude Code repaints right away (`model.id`, read every 3 s), when it
  changes and is newer than what the transcript last said. The status line's `[1m]` is kept: replies
  never carry it, and the same model with or without it is the same model;
- every change goes out as a `session` message.

**Switching it from the phone:** `POST /v1/sessions/{id}/model {model}`, where `model` is a
`ModelOption.value` (or its `model` id) from `GET /v1/models` for the session's provider, or `null`
(also `"default"`) for the provider's default. Another provider means moving the session
(`…/provider`). Anything else is 400 `unknown_model`. The session shows the new model at once (the
option's id when known) and broadcasts it, then:
- **a shared terminal**: `/model <value>` (`/model default`) is typed into it as a prompt is (Claude
  Code takes it while Claude works and uses it from its next request): `applies: "next_turn"` while
  the session is working or starting, else `"now"`. When it can't be typed: 409 `busy` with
  `reason` `needs_answer` (an approval is open), `waiting` (a dialog or menu), `typing` (unsent text,
  in `pending`), `starting` or `terminal_busy`;
- **a `claude agents` job** (`agentJob`), a stopped or finished one too: through `claude attach <job>`,
  as the effort route does (it wakes a stopped job), then `/model` typed as above. Never typed into
  an attach holder that shows another job or the agent list (`attachDrifted`): a fresh attach takes
  over, and one that shows another session too, or an attach that doesn't open, is 409 `busy` with
  `reason: "terminal_busy"`, nothing typed and nothing recorded (the session keeps the model it had).
  The phone offers the model picker for such a job, as the mode and effort ones;
- **a session the daemon runs (Agent SDK)**: its next query starts on it (`applies: "next_turn"` while
  a turn runs, else `"now"`);
- **a terminal Hypernova doesn't hold**: 409 `terminal_model` ("Type /model there on the Mac");
- **an ended session**: it resumes on it (`applies: "next_turn"`; kept in memory until it resumes
  for a session the daemon didn't start). Not a `claude agents` job: that one goes through `claude
  attach` (above), as its resume would.

A session on a provider other than the subscription costs money: the switch needs `confirmPaid:
true` (409 `confirm_paid` otherwise), as automations ask, unless the model is free (price 0).

## A session's effort

Claude Code's reasoning effort, `low`, `medium`, `high`, `xhigh` or `max` (`claude --help` of
2.1.314). Codex's is its models' own and goes with `…/model` (see Codex).

**Starting with one:** `NewSession.effort`. A shared terminal and a `claude agents` job get
`--effort <level>`; a session the daemon runs passes it as the Agent SDK's `effort` with every query.
None: nothing is passed, Claude Code's own default. Anything else is 400 `unknown_effort`, before
anything starts. The phone remembers the last one started with, as it does the model. The phone
offers an effort on the Claude subscription only (`providerId` null or `anthropic`): none is sent
for OpenRouter or a custom provider, and their sessions' sheet has no Effort row.

**Resuming:** a shared terminal started again on a conversation (`--resume`, or `--session-id` for
one never written down) gets `--effort` with the effort it last had (`Session.effort`, else what it
was started with or set to), on the subscription only; none known, none passed. An ended terminal
session the daemon goes on with itself (no shared terminals) keeps that effort for the SDK's
`effort`, which goes with every query, resumes included.

**`Session.effort`** is what Claude Code's status line last said (`effort.level`, read every 3 s
with the model; it reports the default too, and a /effort typed in the terminal), else what the
session was started with or last set to from the phone. A status line written before the phone's
last change doesn't take it back. Null until one is known, and for Codex.

**Changing it:** `POST /v1/sessions/{id}/effort {effort}`. The session shows it at once, then:
- **a shared terminal**: `/effort <level>` is typed into it as `/model` is (see A session's model):
  `applies: "next_turn"` while it is working or starting, else `"now"`; the same 409 `busy` reasons;
- **a `claude agents` job** (`agentJob`): through `claude attach <job>`, as the mode route does (a
  stopped job wakes); never typed into an attach holder that shows another job or the agent list
  (`attachDrifted`): a fresh attach takes over, and one that shows another session too is 409 `busy`
  with `reason: "terminal_busy"`, nothing typed;
- **a session the daemon runs**: its next query takes it (`applies: "next_turn"` while a turn runs);
- **a terminal Hypernova doesn't hold**: 409 `terminal_effort`; **an ended session**: 409 `not_open`.

There is no way back to "default" from here: the phone offers the five levels.

## Account

Whether Claude can reply on this Mac, and why not: a usage limit, credit run out, signed out, the
plan's end, a hold. One `Account` for the built-in provider (the owner's Claude login); another
provider's trouble is its own (`Provider.status`). `daemon/src/account.ts`; the classifier is
`daemon/src/blockers.ts`.

```ts
Account = {
  state: "ok" | "limited" | "no_credit" | "logged_out" | "no_subscription" | "on_hold" | "unknown",
  providerId: "anthropic",
  plan: "pro" | "max" | "team" | "enterprise" | null,  // `claude auth status`'s subscriptionType, when known
  limit: null | {
    window: "five_hour" | "seven_day" | "model" | "credits",
    label: string,              // Claude Code's own words: "session limit", "weekly limit", "Opus limit"
    resetsAt: number | null,    // epoch ms
    autoResumeAt: number | null,// Claude Code carries on by itself then ("continuing automatically at 2am")
    usingCredits: boolean       // past the limit on usage credits: Claude carries on, paid
  },
  reason: null | "expired" | "revoked" | "not_logged_in" | "rejected" | "ended" | "trial_ended" | "downgraded"
        | "org_disabled" | "out_of_credits" | "spend_cap" | "api_credit",
  text: string | null,          // Claude Code's line, at most 200 characters, request ids removed;
                                // null when a check found it
  since: number | null,         // when this episode began (epoch ms)
  sessionId: string | null,     // the session that hit it first
  checkedAt: number | null,     // the last `claude auth status`
  login: null | { state: "starting" | "waiting_code" | "finishing" | "failed",
                  url: string | null, where: "phone" | "mac", error: string | null }
}
```

`unknown` and `downgraded` are reserved: the daemon doesn't send them yet.

- **Where it comes from.** Claude Code writes an API error into the transcript as a reply of its
  own (`message.model` `"<synthetic>"`, `isApiErrorMessage: true`) with `error`, `apiError`,
  `apiErrorStatus` and, for a quota, `apiErrorParams.rate_limit_info` (`status`, `resetsAt` in
  seconds, `rateLimitType`, `overageStatus`, `overageResetsAt`, `overageDisabledReason`,
  `isUsingOverage`). The daemon reads those entries (a subagent's left in the main transcript only
  tell the account), the StopFailure hook (at once, before the transcript has the line), the SDK's
  assistant `error`, `rate_limit_event` with `status: "rejected"`, a `result` with `terminal_reason:
  "blocking_limit"` or a 401, 402, 403 or 429 `api_error_status`, `auth_status.error`, Claude
  Code's limit notices (below), the "Your Claude Code trial has ended." dialog in a shared
  terminal, and checks. The kind comes from the fields (Claude Code's words differ between the
  terminal and the SDK, and between versions); the text is shown, and read only when it's all
  there is (a StopFailure hook has no `rate_limit_info`: its "resets 2am (Europe/Moscow)" is read).
- **What each says:**

  | Claude Code says | Built-in provider: the account, and the session's `blocked` | Another provider |
  |---|---|---|
  | `rate_limit` with `apiError: "usage_limit_reached"`, or `rate_limit_info.status: "rejected"`, or a quota line ("You've hit your session limit · resets 2am (Europe/Moscow)") | `limited`; `limit.window` from `rateLimitType` (`five_hour`, `seven_day`; `seven_day_opus`, `_sonnet`, `_overage_included` → `model`; `overage` → `credits`), `resetsAt` from `resetsAt` (else the line's "resets …"); blocked `limit` (`model` for a model's limit) | `status: "limited"` (until its `resetsAt` or the line's "resets …", else 3 hours), blocked `provider` |
  | the same with `overageDisabledReason: "out_of_credits"`, or "You're out of usage credits" (even while it ran on credits) | `no_credit` (`out_of_credits`), window `credits`; blocked `no_credit` | |
  | the same with a spend cap (`org_level_disabled_until`, `org_spend_cap_reached`, "…spend limit") | `no_credit` (`spend_cap`) | |
  | the same with `isUsingOverage`, while the credits take it (`overageStatus` `allowed` or `allowed_warning`, or none) | `limited` with `usingCredits`; not blocked | |
  | `model_requires_usage_credits`, `long_context_credits_required`, "Fable 5 requires usage credits." | unchanged; blocked `model` | |
  | a 429 marked transient, "…(not your usage limit)", or one naming no quota | unchanged; a `warn` notice (Claude Code retries) | |
  | `authentication_failed` ("Please run /login · API Error: 403 …", "Not logged in", "Login expired", "OAuth token revoked", "Failed to authenticate…") | `logged_out`: `expired`, `revoked`, `not_logged_in`, else `rejected` (the plan may have ended); a check; blocked `logged_out` | `status: "bad_key"`, blocked `provider` |
  | `oauth_org_not_allowed` | `no_subscription` (`org_disabled`); a check | `bad_key` |
  | `account_on_hold` | `on_hold` (its text keeps the appeal link); blocked `on_hold` | |
  | "Claude Opus is not available with the Claude Pro plan…" | unchanged; blocked `model`; a check (the plan) | |
  | `billing_error`, "Credit balance is too low" | `no_credit` (`api_credit`); blocked `no_credit` | `status: "no_credit"` |
  | a 402, "Insufficient credits" | | `status: "no_credit"` |
  | OpenRouter's `limit_source` in the body: `openrouter_credits`; `openrouter_key_limit`, `openrouter_guardrail_budget` or `openrouter_workspace_budget` (402 or 403); `openrouter_in_flight_budget` | | `no_credit`; `no_credit` with `budget` (never `bad_key`); `limited` for a minute (or its retry-after) |
  | a 429 from another provider | | `limited`, `resetsAt` from `rate_limit_info.resetsAt`, else `X-RateLimit-Reset` in the body (OpenRouter puts its headers there; ms or s), else "retry after N" or "try again in N seconds\|minutes\|hours", else null |
  | the old "Claude AI usage limit reached\|<epoch>" | `limited`, `five_hour` | |
  | `server_error`, `overloaded`, the network, anything else | unchanged: an `error` notice (`overloaded`: `warn`) | `overloaded`, a 5xx, a connection error: `status: "down"` (ends with a reply, an edit, or after 10 minutes), blocked `provider`; anything else: a notice |

- **Limit notices** (`system`, subtype `informational`, level `notice`, and the warnings Claude
  Code gives when it gives up on carrying on: of these lines only the limit's are shown, as notices,
  without the tails only a terminal can use: " · esc to cancel", " · /rate-limit-options to
  re-arm", " (/rate-limit-options to …)", " · press enter to continue", " · send a prompt to
  continue"): "Usage limit reached[ again] · continuing automatically at 2am" (also "Continuing
  automatically …" and /rate-limit-options' "Claude Code will continue automatically at 2am.")
  sets `limit.autoResumeAt` (that time, else `resetsAt`) and the session's resume; "Automatic
  continue cancelled · …", "… was turned off", "… stopped …" set them to null; "Usage limit has
  reset · press enter to continue" and "Automatic continue did not run …" make it ready; "Usage
  limit reset · continuing automatically" (or "… available again · continuing now") ends the
  limit. See Auto-continue.
- **Episodes.** One per state, window and reset (within 2 minutes): many sessions hitting the same
  limit make one `{t:"account"}`, with the first one's `since`, `sessionId` and text; the rest only
  add what it didn't know (the reset). Another state or window begins another episode, with a new
  `since`: clients notify once per `since`. A sign-out during a limit keeps the limit underneath:
  signing in again brings it back, `since` and all (no new notification), while its reset is
  still ahead. Kept in `<support>/account.json` (0600), so a restart keeps it. Old news changes
  nothing: a limit whose reset has passed, an error from before the account last worked (a reply
  to a request sent after it, a sign-in), or a sign-in or plan error first read more than 30
  minutes after it happened (a history opened late: a check decides). Nor does a session's
  inherited history, which still shows in its chat: a fork's copy of its parent's
  lines (Claude Code marks them `forkedFrom`; the parent's read told the account already), or a
  session's lines from before it moved to another provider (read as the one it ran on then).
- **Clearing.** A real reply on the built-in provider to a request sent after the episode began
  (a model's limit only by a reply from that model; a limit Claude runs past on usage credits,
  `usingCredits`, not by a reply at all, since replies are what the credits buy); "Login
  successful" (/login) ends a sign-out; the "Usage limit reset" notice; the reset passing (looked
  at every 30 s; a limit whose reset Claude Code didn't say lasts 6 hours at most); a check
  (below). A provider's status: a reply from it, or editing or deleting it; `limited` also ends at
  its reset, else 3 hours after it began; `down` after 10 minutes; `no_credit` when its balance
  shows credit again, `bad_key` when a balance read works (see Providers).
- **Sessions.** An API error that stops a session sets its `blocked` and leaves it `idle`, not
  `error` (SDK sessions too: no failed result row). `lastText` stays Claude's own words: the error
  is a `notice` with `blocker`, never an `assistant` event. `blocked` goes with a real reply in that
  session, or once it no longer holds: its reset passed, the account or the provider works again,
  (for a 5-hour or weekly limit) a later request on the account went through, or (for a limit or
  credit) the account has been ok since it stopped the session (its reset, "Usage limit reset").
- **Usage.** A 5-hour or weekly rejection fills that window (`pct: 100` with the rejection's reset),
  which status lines from the same window can't lower, and a restart keeps (from `account.json`).
  While `logged_out`, `no_subscription` or `on_hold`, `Usage.source` is `"stale"`, with the last
  numbers known.
- **The check**: `claude auth status --json` (10 s at most), reading `loggedIn`, `authMethod`,
  `apiProvider` and `subscriptionType` only; the email and organization it also prints are never
  kept, logged or sent. At start; on a sign-in or plan error; after a sign-in (here, or /login in
  a terminal); on `POST /v1/account/check`; when the status line has carried no limits for 30
  minutes from any session on the built-in provider that wrote one lately; and every 30 minutes
  while the account is neither ok nor limited. Not signed in → `logged_out` (`not_logged_in`).
  Signed in with Claude but no plan, when something pointed there (a refused login, a sign-in just
  now) → `no_subscription` (`ended`). Signed in with a plan → `plan`; it ends a sign-out or a
  missing plan a check found, or any after a sign-in Claude Code reported ("Login successful"),
  but not one an API error reported (it reads the token on the Mac; the server may still refuse
  it, and a failed sign-in leaves it there). The SDK's `accountInfo()` (from the
  process the "/" list starts) fills `plan` too.
- **Signing in again** (`POST /v1/account/login`): `claude auth login --claudeai` in a holder of
  its own (not a session; 500 columns wide so the address fits one line; `hn ls` lists it), with
  `login.state` `starting`. The sign-in page's address is read off its screen (an `https://` link
  on claude.ai, claude.com or anthropic.com, joined back if it wraps) into `login.url`, and the
  state becomes `waiting_code`. Each sign-in has an address of its own (its `state` and PKCE
  challenge). The first whole address (with its `state`) stays: a later read replaces it when it
  carries it further, or when it is a new sign-in (a `state` that isn't the start of the shown
  one's, nor it of the new one's) read from an address that runs to the end of its line, as Claude
  Code prints it; a read cut short never does (once the Mac's window opens, the screen narrows). `where: "mac"` also opens it in a terminal window on the Mac, once
  the address is read (as "Open" does). The phone opens the page, which shows a code
  (`<code>#<state>`); `POST /v1/account/login/code` checks it is one, and of this page (a code
  ending with another sign-in's `state` is 400 `bad_code`: Claude Code would end the sign-in over
  it), waits until the code prompt ("Paste code here if prompted >") shows (8 s at most), checks
  the code against the address on that same screen, then types it there as raw keys and Enter
  (not the way prompts are typed, never logged) → `finishing`. While Claude Code checks it, another
  code is 409 `not_ready`. `claude auth login` (Claude Code 2.1.300) is a plain line prompt, not
  the interactive /login screen, and has no error page: a code without both halves gets "Invalid
  code…" and the prompt waits for another (→ `waiting_code` with `error`), and a code the server
  refuses ends the command ("Login failed: …", exit 1). A right code can end it with exit 1 too,
  without "Login failed", when the account can't sign in (on hold, or an organization's policy).
  The command's answer is read below its prompt, on the screen it ended with (the holder's `exit`
  carries it), also after a code the owner pasted himself in the Mac's window. Only "Login
  successful." is a sign-in: its exit code alone counts only when its screen is unknown (a command
  a signal ended, Ctrl-C or the time running out, exits with 0 too). A refused code ("Login
  failed", or, after a code the daemon typed, a failed exit with nothing to read) changes nothing,
  so no check runs: unless the account works again meanwhile (a /login in a terminal, a reply),
  the daemon starts a new sign-in at once in a new holder, at a new address (`starting`, then
  `waiting_code`, with an `error` saying to open the page again: the old page's codes are spent),
  three times in a row at most (the fourth refused code ends it). Any other answer to a code the
  daemon typed → `failed` with Claude Code's words as `error` (a new sign-in would end the same
  way). Otherwise a check when the holder exits, which ends a sign-out the server reported only
  after "Login successful": no longer signed out → `login: null`; else `failed` with `error`.
  After 10 minutes (counted again from each new sign-in) the holder is ended
  (`failed`, timed out); `DELETE` ends it (`login: null`). One left running
  from before a daemon restart is ended at start.
- **Another provider** (`POST /v1/sessions/{id}/provider`): an SDK session takes it with its next
  prompt (its model goes back to the provider's default); a phone-started shared terminal that is
  idle restarts on the same conversation (`--resume`) with the new provider's environment, which
  Claude Code reads only at start (the old claude's end isn't the session's: SessionEnd hooks,
  which SIGHUP makes it run, are passed over until 15 s after the new claude starts, and so is the
  old one's registry entry, by its pid, when it was known; agents it ran in the background end
  with it; if the new claude can't start, the session has `ended`); one started
  in a Mac terminal, or open in a terminal Hypernova doesn't hold, forks (`forked: true`; the new
  session is the one to use; on a Mac without shared
  terminals an SDK session forks with its first prompt); one that has ended resumes on the new
  provider. Its `blocked` clears. A resumed session keeps the provider its record names. A session
  a limit stopped can move even while its terminal shows Claude Code's dialog about the limit; an
  approval or another dialog still makes it `busy`, and so does a move still under way (until 15 s
  after the new claude starts).

## Auto-continue

Whether a session a limit stopped carries on by itself (`daemon/src/resume.ts`), as
`Session.blocked.resume`:

```ts
Resume = {
  state: "armed" | "off" | "ready",
                    // armed: carries on by itself at `at`; off: won't by itself; ready: what stopped
                    // it is over but it didn't carry on: offer Continue (POST …/continue {reason: "limit"})
  at: number | null,  // armed: when it carries on; off: when the limit lifts (null: unknown); ready: when it lifted
  by: "claude" | "hypernova",  // who carries it on: Claude Code in its terminal, or the daemon
  canCancel: boolean,  // armed, and it can be stopped from here (POST …/auto-continue {on: false})
  canArm: boolean,     // off, and it can be turned on from here ({on: true})
  why: null | "cancelled" | "setting" | "far" | "repeated" | "exited" | "slept" | "dropped" | "no_reset" | "back"
}   // clients show an unknown `why` generically
```

- **Who decides.** Claude Code decides for its own terminal sessions on the subscription (it
  carries on at the reset plus 30–90 s, at most twice in a row, within 24 hours, unless its
  `autoContinueAtUsageLimit` setting is false): its notices (see Account) set the state, and until
  one comes (5 s) the resume is null. The daemon carries on what Claude Code can't: an SDK session
  at the subscription's 5-hour or weekly reset (+30–90 s; not one model's limit; the same setting,
  24 hours and two re-arms), a session on another provider `limited` with a reset the provider gave
  (+5–20 s), and a session whose Claude Code exited during the wait (`off`, `exited`; turned on, it
  continues in a new shared terminal). Its text: Claude Code's own ("Your claude.ai usage limit has
  reset. Continue the task you were working on when the limit was reached; do not repeat work that
  is already complete.") for the subscription; "The rate limit has passed. Continue the task you were
  working on when it was reached; do not repeat work that is already complete." for another
  provider. Such a prompt, and Continue's ("You can carry on now. …"), is a notice row in the chat
  ("Continued by itself after the limit", "Continued after the limit"), never the owner's bubble.
- **Never by itself:** out of credit, a refused key, signed out and the rest (`resume: null`). A
  rate limit that named no reset, or a provider `down`, is `off` with `no_reset` and can't be armed
  (the card offers Try again). An observed terminal (not shared) shows its state; both switches are
  off there.
- **Firing.** At `at`, when the session is idle with no approval, menu or dialog (else 20 s later):
  if the account or provider still says limited with a later reset, it waits for that one; out of
  credit or a refused key by then, never. More than 30 minutes late (the Mac slept, the daemon was
  down): `ready`, `slept`. Unsent text in the terminal: `ready`, `dropped` (never typed over). Hit
  again within 15 minutes of carrying on: armed again, twice at most, then `off`, `repeated`.
- **Ready.** When what stopped it is over and it isn't armed, the block stays with `state: "ready"`
  (`why` keeps `cancelled`, `slept`, `dropped`, `repeated`, `exited`; `"back"` for a provider that
  recovered: credit again, a key that works, a limit over). Claude Code's "has reset · press enter"
  and "did not run" notices make one even on a session whose block had cleared. It goes with a
  prompt or a turn, a reply, after 12 hours, or when the session moves to another provider.
- **The switch in a terminal** (`by: "claude"`), one at a time per session, only while Claude is
  idle (the session isn't working or starting, Claude Code's registry doesn't say busy, no
  spinner: else 409 `not_limited`, as it has just carried on and no key is pressed), no approval
  or other dialog is open, and the input box is empty or holds Claude Code's "continue". Whether
  it's armed now is read from the newest of Claude Code's limit lines above the box (its armed
  notice stays in the conversation after a cancel, so an old "esc to cancel" means nothing on its
  own). Off presses Esc once while it's armed; otherwise (and always to turn it on) it types
  `/rate-limit-options` (after clearing "continue" with Backspace, never Ctrl+C) and picks
  "Don't continue automatically" or "Wait here, then continue automatically …" in the menu titled
  "What do you want to do?" (one Claude Code opened itself is answered in place). A menu offering
  the other option means it's that way already: it's closed with Esc and the switch follows.
  Confirmed by Claude Code's notice, or by a limit line on its screen that wasn't there before,
  within 4 s, else 409 `not_confirmed`. Logged without screen contents.
- **Continue after a limit in a terminal** (`POST …/continue {reason: "limit"}`): Claude Code's own
  "continue" in the box (its "press enter to continue" after the Mac slept through the reset) is
  cleared with Backspace and the continuation typed in its place; any other text answers 409
  `typing` as for a prompt.
- Kept in `<support>/resume.json` (0600): a restart keeps what's armed, off or ready.

## Codex (a second agent)

OpenAI's Codex runs next to Claude Code (`daemon/src/codex/`; the app-server facts it rests on are in
`docs/specs/codex-app-server.md`). Nothing changes for a computer without Codex: the snapshot's
`codex` is `null` and no session has an `agent` field.

```ts
Codex = {
  available: boolean,   // installed, version >= min: it can be used here
  enabled: boolean,     // the owner uses it (Setup's switch, or the first Codex session from the phone)
  connected: boolean,   // the daemon reaches Codex's shared app-server now
  version: string | null, min: string,   // "0.163.0": the version these routes were checked against;
                                         // later ones are taken (the handshake tells)
  path: string | null,  // the codex binary
  problem: string | null,   // what's wrong, in words, when it can't be used now
  account: null | {signedIn: boolean, type: "chatgpt" | "apiKey" | string | null, planType: string | null},
                        // null while off; never the email
  usage: null | {fiveHour: Window | null, sevenDay: Window | null, limited: boolean, observedAt: number | null}
                        // Codex's own windows (a plan may have only the week); limited: one is used up
}
CodexModel = { value: string, name: string, description: string | null,
               efforts: string[], defaultEffort: string | null, isDefault: boolean }
```

- **The connection**: the daemon is a client of Codex's own shared app-server (`codex app-server daemon
  version` gives its socket; `daemon start` when it isn't running; Hypernova never stops it). It
  connects only while `enabled`, and tries again 1 s, 2 s … up to a minute apart. Turning Codex on or
  off: `POST /local/settings {codex: boolean}` (the Mac app's Setup), or from the phone `POST /v1/codex
  {enabled: boolean}` → `{codex}` (409 `no_codex` to turn on a Codex
  that isn't installed or is too old); the first `POST /v1/sessions` with `agent: "codex"` turns it on
  too. Turning it on also puts the shell's `codex` function in (and the shell block with it, where
  Claude Code's integration never did); off takes it out.
- **Which agent leads** (the clients' rule, one in every client): Claude Code when `snapshot.claude.available`,
  else Codex when `codex.available`. The sun and the widget's star follow the leading
  agent's usage; the other agent, while in use, is a second star with its own sessions. A new session
  with no `agent` goes to Claude Code, or to Codex when Claude Code isn't installed.
- **A Codex session** is a Codex thread: `Session.agent: "codex"`, `providerId: "codex"`,
  `permissionMode` its sandbox, `read-only` or `workspace-write`, with Codex asking for anything more
  ("on request"). `POST /v1/sessions` with `agent: "codex"` starts the thread and its first turn
  at once. A thread is never resumed before Codex has written it down (its first turn, or a switch's
  context), as Codex refuses that.
- **Prompts** (`POST /v1/sessions/{id}/prompt`, `continue`): a turn, or queued in `session.queue`
  behind the one running (Codex would mix it into the running turn). Attachments go as pictures or
  file mentions. **Stop** (`interrupt`) ends the turn and drops the queue with an `unsent` notice, as
  an SDK session's does. `mode` takes `read-only` or `workspace-write`, `model` one of
  `GET /v1/codex/models` (null: the default) with an optional `effort` it offers (400 `unknown_model`,
  `unknown_effort`); both apply from the next turn (`applies: "next_turn"` while one runs).
- **Events** are the same `Event` kinds as Claude's: the owner's messages, replies (streaming), a
  `thinking` row for reasoning, tools (`Bash` for a command, `Read`, `Grep`, `Glob` for one Codex
  recognized, `Edit` or `Write` per file changed with its diff, `mcp__server__tool`, `WebSearch`), and
  a `result` per turn ("Stopped." for one stopped). The history is Codex's own, read once when a session
  is first opened (`GET …/events`).
- **Approvals**: commands, file changes, more access ("the network", "more folders", for this turn) and
  questions come as `Approval`s, answered as usual (`allow`, `deny`; no `always` or `session`).
  Codex sends a request to every client on its thread and takes the first answer, so a terminal and
  the phone race on it as they do for Claude: the other one's card resolves `by: "terminal"`. A secret
  question or a form the phone can't show leaves a notice to answer it in the terminal. Nobody for 10
  minutes: no, and the turn stops.
- **Usage**: `GET /v1/sessions/{id}/usage` has Codex's context and tokens and Codex's own windows, no
  price. The snapshot's `codex.usage` is the meter.
- **Not for Codex**: `rewind`, `provider`, `auto-continue`, `menu` and a `continue` after a limit answer
  409 `unsupported_for_agent`; `agents` and `todos` are empty. Rename, Stop and Delete: see Session menu.
- **Terminals**: `POST /v1/sessions/{id}/terminal` and `open` start `codex resume` in a shared terminal
  when the session has none (409 `not_started` before Codex wrote the thread down). See Shared
  terminals for the Codex holder and the shell's `codex`.

## Switching agents

A conversation can go on with the other agent. The owner picks what to carry over, reads and edits
it, and confirms; the other agent starts a session of its own in the same folder with that text as
background, and runs nothing until the next message (`daemon/src/switch.ts`).

- `POST /v1/sessions/{id}/switch/preview {to, context}` drafts the text: `recent` (the last 20
  messages, the owner's and the agent's, with a line per tool between them), `all` (the whole
  conversation, across earlier switches, oldest first) or `none`. Over `maxBytes` (96 KB, UTF-8) the
  oldest messages go first. The phone shows it in an editable field with its size.
- `POST /v1/sessions/{id}/switch {to, text, model?, effort?, permissionMode?}`: the session must be at
  rest (409 `busy`: working, starting, waiting for an answer or with a queue: the phone offers Stop), not
  switched already (409 `switched`, `to` where it went), and not a Claude Code open in a terminal
  Hypernova doesn't hold (409 `terminal_session`). `text` may be edited or empty.
- **To Codex**: a new thread with the text put into its history as background (no turn); its mode
  `read-only` unless asked. **To Claude Code**: a new session with the text in `--append-system-prompt`
  (or the SDK's appended system prompt), given again on every resume.
- The two are linked: the old session has `switchedTo`, the new one `switchedFrom`. Prompts to the old
  one answer 409 `switched` with `to`. The phone shows the new one on Home with the earlier one under
  "Continued from", and leaves the old one off Home.
- **A terminal follows**: when the old session's shared terminal is open on the Mac or a phone, the new
  session gets one and the old holder sends its windows and phones `{t:"moved", to}` (see Shared
  terminals); with nobody looking it just ends. A Codex source is left alone afterwards (`ended`).

## Mac-only features (a Linux server)

The daemon also runs on a Linux server or PC, from the command line (docs/LINUX.md). The Mac's own
features aren't there, and the daemon says so instead of failing (`daemon/src/platform.ts`):
`Snapshot.mac.platform` is `"linux"`, and `Snapshot.mac.unavailable` names each missing feature
with one sentence a client can show. On a Mac it is `{}` (and older daemons send neither field).

| Feature key | What's missing | Its sentence |
|---|---|---|
| `battery` | the Mac's battery (`mac.battery` is `null`) | "Hypernova shows the battery of a Mac only." |
| `awake` | Stay awake (`mac.awake` reads Off, nothing held) | "Stay awake is for a Mac. A Linux server stays on by itself; if this PC suspends, turn that off in its power settings." |
| `macVoice` | the Mac's voice (`say`) for read aloud | "The phone reads aloud with its own voice." |
| `openWindow` | a terminal window on the server's screen | "This server has no screen to open a window on. Attach from a terminal instead: hn attach, over SSH." |

Clients that know a key hide that feature (or show it disabled with its sentence); a client that
doesn't know the field keeps working, because every route answers plainly when asked anyway:

- `POST /v1/mac/awake` → 501 `unavailable` `{feature: "awake"}`; `GET` answers `{awake}` (Off).
- `POST /v1/sessions/{id}/open` and `POST /v1/account/login {where: "mac"}` → 501 `unavailable`
  `{feature: "openWindow"}`. Signing in "on the phone" works as on a Mac.
- Read aloud with the Mac's voice → 501 `no_voice` with the `macVoice` sentence; the phone reads with its own voice.

`unavailable`'s sentences are in English, like the daemon's other messages; a client may use its
own words for a key it knows.

## Claude Code's SDK (fetched, not bundled)

The daemon drives Claude Code through Anthropic's Agent SDK (`@anthropic-ai/claude-agent-sdk`) for
three things: the sessions it runs itself (where shared terminals can't run, or Claude Code's hooks
aren't in), the "/" list (`GET /v1/commands`, and Claude Code's model list with it) and Rewind
(`forkSession`). The SDK is Anthropic's ("All rights reserved"), so the Mac app doesn't carry it:
Setup's Background service step fetches the exact version the daemon's lockfile pins from npm, into
the installed daemon (`<support>/app/daemon`), once; the Linux install gets it with the rest of
`npm ci` (docs/INSTALL.md, docs/DECISIONS.md "Going public"). The daemon loads it the first time
one of those needs it (`daemon/src/sdk.ts`), and starts and serves without it: Codex, shared
terminals, approvals, usage and everything else work.

- **Said**: `Snapshot.claude.sdk` (`false` while it isn't there) and `/local/status`'s
  `claude.sdk: {installed, version, wanted}` (`wanted`: the version this daemon pins, from its
  `sdk-version` line or its lockfile). It is read again at most every 5 s, so one fetched while the
  daemon runs is used without a restart.
- **Asked for without it**: 409 `sdk_missing`, with the way to get it as the message ("Claude
  Code’s SDK isn’t installed on this Mac yet. On the Mac, open Hypernova’s Setup, go to Background
  service and click Fetch again."; on Linux "…run hypernova setup again…"). `GET /v1/commands`,
  `POST /v1/sessions/{id}/rewind`, and `POST /v1/sessions` or `…/prompt` for a session the daemon
  would run itself answer it before anything is made or queued. A prompt that reaches such a
  session another way (auto-continue, an automation's continue) isn't sent: the session gets an
  error notice "Not sent. <the message>" and stays idle. An automation's step that would start one
  fails at its start. The model list falls back to the built-in one.
- **The phone's next step**: show the message; there's nothing to try again from the phone until
  the Mac has the SDK. A client that knows `claude.sdk` may hide the "/" list and Rewind while it's
  `false`.
- **On the Mac** the app reads the installed copy's `sdk-version` and the package's version itself
  (no daemon needed), and shows a missing SDK only where Claude Code is installed: in Setup's
  Background service step, the Agents & providers window's Claude Code card and a popover notice,
  each with **Fetch again** (with Codex alone nothing uses it, so nothing says so).

## Battery

The Mac's own battery, so the phone can say when the Mac in the backpack runs
low. `daemon/src/battery.ts`.

```ts
Battery = { percent: number,             // 0–100
            charging: boolean,
            onAC: boolean,               // on the power adapter, charging or not
            minutesLeft: number | null } // discharging: until empty; charging: until full;
                                         // else, or while macOS estimates, null
```

- **`null` is a Mac without a battery** (Mac mini, iMac, Mac Studio, Mac Pro): clients show nothing
  for it, no empty or "unknown" battery. Only the Mac's internal battery counts: a UPS on USB,
  which `pmset` lists the same way, isn't one. `null` also before the first reading, a moment
  after start.
- **Read** with `/usr/bin/pmset -g batt` and `/usr/sbin/ioreg -rn AppleSmartBattery` side by side,
  at start and once a minute by the wall clock, so the first read after the Mac wakes comes within
  10 s (5 s at most each, never blocking, one at a time; a read that fails keeps the last reading;
  no `pmset` at all → `null` for good). pmset's line is `-InternalBattery-0 (id=…)\t84%;
  discharging; 5:12 remaining present: true`: `percent`; `charging` for "charging" and "finishing
  charge" (not "charged", nor "AC attached; not charging", where optimized charging holds it);
  `onAC` from "Now drawing from 'AC Power'"; `present: false` → `null`.
- **Minutes left** come from the battery's own estimate, ioreg's `AvgTimeToEmpty` while discharging
  and `AvgTimeToFull` while charging (65535 is none yet: `null`; `pmset -g rawbatt`'s `Time=` is
  the same number). Each is its last minute's and swings with the load, so the daemon averages the
  pace (percent to go over minutes) of the last five minutes' readings in the same state and
  applies it to the percent to go now; plugging in or out, a full battery or a gap (sleep) starts
  it over. pmset's own time is macOS's power-source estimate, which caps at 20 hours: on macOS 26
  it sat at "20:00 remaining" for a whole evening (28% down to 22%, the charge falling about 6% an
  hour, the battery saying 6 to 7 hours with one 25-hour minute). It counts only without ioreg's
  numbers, and then "(no estimate)", "0:00" and "20:00" (the cap) are no time.
- **Sent** as `{t:"battery"}` when the percentage changes by 1 or more, `charging` or `onAC`
  changes, or a battery appears or goes; `Snapshot.mac.battery` always has the latest reading.

## Stay awake

Keeps the Mac awake while there's something to stay awake for, including in a closed backpack, and
lets it sleep when there's a reason to. `daemon/src/awake.ts`; the Mac popover and the phone's Settings
choose the mode.

```ts
Awake = { mode: "working" | "backpack" | "off",
          holding: boolean,        // keeping the Mac awake right now
          lidClosedOk: boolean,    // a closed lid won't put it to sleep now: macOS's SleepDisabled is on
                                   // (Backpack's, or the owner's own, e.g. Amphetamine's)
          backpackReady: boolean,  // a MacBook with Backpack's one-time setup done
          until: number | null,    // Backpack ends by itself then (epoch ms); null outside Backpack
          extendable: boolean,     // Backpack ends within 10 minutes: offer "+2 h" (below)
          reason: string | null,   // what stands in the way (below); null: nothing
          sleepAt: number | null,  // a guard lets the Mac sleep then ({t:"mac_sleep_soon"} said so)
          lidClosed: boolean | null, // null: no lid (a desktop), or not read yet
          laptop: boolean,         // a battery or a lid: Backpack and the battery guard apply
          minBattery: number,      // the battery guard's level, 15 unless changed
          sleepDisabledBy: "backpack" | "other" | null, // whose SleepDisabled is on; "other" (e.g.
                                   // Amphetamine's) keeps the Mac awake even once Hypernova lets go
          otherApp: string | null } // the app macOS names as keeping it awake, with "other"
```

Clients never say the Mac will sleep while `sleepDisabledBy` is `"other"`: "Hypernova let go at
15%, but Amphetamine is still keeping your Mac awake" (`otherApp`, or "another app").

- **While working** (the default) holds a sleep assertion while a session is starting, working or
  waiting on an answer mid-turn, its agents run, an automation asks for it (`keepAwake(key, on)` in
  awake.ts), or a phone is on `/v1/ws` (the Mac app's own feed doesn't count), and for
  3 minutes after the last of those: macOS sleeps the moment an assertion goes if the Mac has been
  idle long enough, so a socket blinking during a network change would put it to sleep at once.
  **Backpack** holds whatever runs, with the lid closed too, for 8 hours. **Off** holds nothing.
  The mode, the battery level and Backpack's end are kept in `<support>/awake.json`.
- **Extend.** `POST /v1/mac/awake {extend: true}` moves Backpack's end 2 hours on (`until` += 7200 s),
  each time it's asked, but only while `extendable` is true (Backpack, 10 minutes or less left):
  409 `too_early` before that, 409 `not_backpack` outside Backpack. The other guards (battery, heat)
  go on applying, so an extended Backpack still lets go when they say so. About ten minutes out
  the daemon sends `{t:"mac_backpack_ending"}` once and `awake.extendable` turns true, for a notice on
  the phone (with a "+2 h" button) and the Mac popover's Stay awake line. It goes away by itself when `until` moves on.
- **The assertion** is `/usr/bin/caffeinate -i -s -w <daemon pid>`, a child of the daemon: it is
  what knows when there's work, it runs whether or not the Mac app does, and `-w` ends the
  assertion with the daemon, so a daemon that dies (even by SIGKILL) never leaves the Mac unable to
  sleep. A caffeinate that ends by itself is started again within 5 s.
- **A closed lid** sleeps a MacBook on battery whatever assertions say. Only macOS's system-wide
  SleepDisabled (`pmset -a disablesleep 1`, root only) keeps it awake. Backpack runs
  `sudo -n /usr/bin/pmset -a disablesleep 1` (and `0`) against a rule the Mac app installs once with
  the owner's password, `/etc/sudoers.d/hypernova` (`<user> ALL=(root) NOPASSWD:
  /usr/bin/pmset -a disablesleep 0, /usr/bin/pmset -a disablesleep 1`, nothing else; see the Mac
  app's Backpack setup). `-n` never prompts: without the rule it fails at once (`reason: "setup"`),
  and any other failure is `"failed"`; it's tried again after 30 s.
- **Putting back what was there.** Before turning SleepDisabled on, Backpack reads it (`pmset -g`).
  macOS lists it only once someone has set it: pmset's output without the line is 0, its default
  (a Mac that never ran Amphetamine). Found at 1 (the owner's Amphetamine), Backpack never touches it, then or after. Found at 0, it
  writes `{prev: 0, applied: true}` to awake.json *before* switching, and switches back to 0 when
  Backpack ends (the mode changes, a guard lets go, the 8 hours are up), so a daemon that dies in
  between finds the record and puts 0 back once Backpack is over. If it goes back to 0 during
  Backpack (Amphetamine ending its own session), Backpack switches it on itself and puts 0 back
  after. On the daemon's way out it's put back only with the lid open: with it closed this is most
  likely a restart (launchd brings it back in seconds and it carries on), and putting it back would
  put a Mac in a bag to sleep at once. A switch still under way then (its sudo outlives the daemon)
  keeps the record, so the next start puts back whatever landed.
- **Ready** (`backpackReady`): the daemon asks `sudo -n -l /usr/bin/pmset -a disablesleep 1`, which
  lists and never runs, every 5 minutes and at once when `/etc/sudoers.d/hypernova` comes or goes
  (the file is believed until sudo answers). So a rule of another user's doesn't count, and another
  rule that already allows the command (Amphetamine's Enhancer allows it to every admin) does,
  with no setup needed.
- **Guards.** The battery at `minBattery` or less on its own power (any mode but Off); in Backpack,
  macOS's thermal pressure at "heavy" or worse (`notifyutil -g
  com.apple.system.thermalpressurelevel` ≥ 2, the value `ProcessInfo.thermalState` is made from,
  or `pmset -g therm` reporting a thermal warning level of 5 or more or the CPU held at 60% or less
  on an Intel Mac); and Backpack's 8 hours. While the Mac is held, a guard first counts down two
  minutes (`reason` set, `sleepAt` when, `{t:"mac_sleep_soon"}` sent once) and then lets go
  (`holding: false`, `reason` stays while the guard does; Backpack's SleepDisabled is put back). The
  guard clearing first calls it off, but only once it has stayed clear for a minute (plugging in
  counts at once): heat swings between heavy and moderate while a Mac throttles, and each swing
  must not restart the countdown. After letting go for heat, the hold comes back once it has been
  cool for 5 minutes; for the battery, a minute (or plugged in). The same guard's warning isn't
  sent again within 10 minutes. A guard that comes while nothing is held just keeps it from being
  held, with no warning. When Backpack's 8 hours are up the mode goes back to `working`, which a
  closed lid doesn't keep awake; so their warning (`reason: "time"`, `sleepAt`) comes only with the
  lid closed: with it open the Mac stays awake for a connected phone.
- **Read** every 20 s: `pmset -g` (SleepDisabled), `ioreg -r -k AppleClamshellState -d 1` (the
  lid, for "lid closed"; no key: no lid), and in Backpack the two heat readings; the decisions run
  every 5 s and at once when a phone connects or goes, or the mode changes. A desktop Mac (no battery,
  no lid) has `laptop: false`: no Backpack (409 `no_battery`), no battery guard.
- **A test copy** (`HYPERNOVA_SUPPORT` set) never changes the real SleepDisabled, or asks the real
  sudo, unless a stand-in sudo is named, and holds no real assertion unless a stand-in caffeinate is. Stand-ins for tests: `HYPERNOVA_SUDO`, `HYPERNOVA_PMSET` (its `-g` reads; the rule always
  names `/usr/bin/pmset`), `HYPERNOVA_IOREG`, `HYPERNOVA_NOTIFYUTIL`, `HYPERNOVA_CAFFEINATE`,
  `HYPERNOVA_SUDOERS` (the rule's path, for `backpackReady`).

## Automations

"When … → Do …" cards that run on the Mac, so they work with the phone off
(docs/specs/spec-automations.md has the design and the copy). `daemon/src/automations.ts` keeps
them, fires them and follows their runs; `daemon/src/schedule.ts` is the time maths. A run that
starts a session starts an ordinary shared-terminal session, titled `Morning brief, Thu 16 Oct`,
with `Session.automation` set. Times of day are the Mac's own (`Snapshot.mac.tz`). The phone edits
them; the Mac app shows a line and a pause switch.

```ts
Automations = { paused: boolean, list: Automation[],
                running: number, queued: number,     // runs running or needing you; runs waiting for a slot
                notes: Note[],                       // the last 12 hours, at most 20, newest first
                hasBattery: boolean | null,          // power triggers can work here; null: not known yet
                                                     // (the first minute); absent from an older daemon
                features?: Feature[] }               // what it can do of Workflows (below); absent: none

Automation = {
  id: string,                    // "au_" + 10 lowercase hex
  name: string,                  // 1–60 characters
  enabled: boolean,
  onlyOnce: boolean,             // turns itself off after its first run that starts ("once" always does)
  trigger: Trigger,
  action: Action,
  template: string | null,       // the template it was made from (below), or null
  createdAt: number, updatedAt: number,   // epoch ms
  nextAt: number | null,         // the next due time (time triggers); null when off, paused or event-driven
  lastRun: Run | null,           // its newest run, whatever its state
  runCount: number,              // skips and misses included
  paid: boolean,                 // it runs on a provider other than the built-in one: that costs money
                                 // (the one it names, or the one the session it continues is on now),
                                 // unless the owner said that provider costs nothing (Provider.paid)
  problem: null | "no_battery" | "no_project" | "no_session" | "no_provider"
         | "outside_projects"    // its folder is there but outside the projects folder
         | "no_model"            // its model is no longer on its provider's list (as last read)
         | "bad_definition"      // what was kept doesn't read as an automation: it is off until saved again
         | "mode_unavailable"    // Claude Code ran its last run in another mode than asked (lastRun.permissionMode)
}

Trigger =
  | { kind: "time",  at: "HH:MM", days: number[] }   // ISO days 1 (Mon)–7 (Sun), sorted, 1–7 of them
  | { kind: "once",  at: number }                    // epoch ms on a whole minute, in the future when saved
  | { kind: "every", hours: 1|2|3|4|6|8|12, from: "HH:MM", to: "HH:MM" | null, days: number[] }
                     // from, from+hours, … up to and including `to` (null: around the clock), on `days`
  | { kind: "reset", window: "five_hour" | "seven_day", afterLimit: boolean,
      from: "HH:MM" | null, to: "HH:MM" | null }     // only a reset between from and to (to may be past
                                                     // midnight: 00:00–07:00 is the night); null, null: any
  | { kind: "limit", window: "five_hour" | "seven_day" }   // the account hits that limit (once per limit)
  | { kind: "session", event: "finished" | "needs_you", projectPath: string | null,
      from: "mine" | "automated" | "any",            // mine: sessions no automation started
      after: number | null }                         // needs_you only, 1–120 minutes: fires only if the
                                                     // session still needs you that long after (Workflows,
                                                     // "Still needing you"); null: at once
  | { kind: "power", event: "plugged_in" }
  | { kind: "power", event: "low", below: number }   // 5–50, a multiple of 5

Then = "none" | "notify"

Action =
  | { kind: "start", projectPath: string, prompt: string,   // 1–8000 characters; "/name args" runs a skill or command
      providerId: string, model: string | null,
      permissionMode: "dontAsk" | "default" | "acceptEdits" | "auto" | "bypassPermissions",
      allowedTools: string[],                               // Claude Code rules, 0–20, "Bash(git log:*)";
                                                            // none kept with "auto" or "bypassPermissions"
      then: Then }
      // providerId "codex": Codex runs it (see Codex) in its sandbox, asking nobody (approvals "never"):
      // permissionMode "read-only" (the default) or "workspace-write", allowedTools [] (400 bad_rule
      // otherwise). Its gate is Codex's account: signed out skips (signed_out), off skips (no_provider),
      // a used-up window waits for its reset; on an API key it costs money (confirmPaid).
  | { kind: "continue", target: string | "trigger" | "working" | "limited",
                     // a session id | the session that fired it | every working one | every one a limit stopped
      prompt: string,                                       // 0–8000; empty only with "limited" (below)
      providerId: string | null,                            // carry on there (moved as POST /v1/sessions/{id}/provider); null: where it is
      then: Then }
  | { kind: "notify", text: string }                        // 1–500

AutomationInput = { name, enabled, onlyOnce, trigger, action, template?: string | null,
                    confirmBypass?: true,   // required with permissionMode "bypassPermissions"
                    confirmPaid?: true }    // required when it runs on a provider other than the built-in one
                                            // (`paid`), also when the session it continues is on one

Run = {
  id: string,                    // "ru_" + 10 hex
  automationId: string, name: string,      // its name when it ran
  fired: { by: "schedule" | "reset" | "limit" | "session" | "power" | "manual",
           at: number,           // when it was due or the event happened
           sessionId: string | null },     // the session that fired it
  state: "queued" | "waiting" | "running" | "needs_you" | "done" | "failed" | "skipped" | "stopped",
  reason: RunReason | null,
  until: number | null,          // waiting: the time it waits for (a reset)
  sessionIds: string[],          // sessions it started or prompted
  startedAt: number | null, endedAt: number | null,
  late: number,                  // ms it started after fired.at (0 on time)
  missed: number,                // due times the Mac slept through, folded into this run
  lateWhy: null | "asleep" | "down",   // why it ran late or was missed: the Mac was asleep, or Hypernova
                                 // wasn't running (it caught up when it started); null when on time
  result: string | null,         // the final reply's first two sentences, ≤ 300 characters, keys masked
  quiet: boolean,                // the reply was exactly "Nothing new.": nobody was told
  skippedTools: string[],        // what "Allowed only" refused, as rules ("Bash(npm test)"), ≤ 10
  permissionMode: string | null, // the mode its session really ran in, when it started one
  depth: number,                 // 0; or 1 + the depth of the run whose session fired it
  retryOf: string | null         // Retry: the run it runs again (absent from an older daemon)
}
RunReason = "overlap" | "queue_full" | "daily_cap" | "stale" | "limited" | "signed_out" | "no_plan"
  | "on_hold" | "no_credit" | "provider" | "missed" | "no_project" | "no_session" | "no_provider"
  | "target_busy" | "loop" | "start_failed" | "away" | "blocked" | "error" | "timeout" | "by_user"
  | "outside_projects"           // failed: its folder is outside the projects folder
  | "no_model"                   // failed: its model is no longer offered
  | "paused"                     // skipped: Pause all came while it was in line or waiting for a reset
  | "mac_restart" | "ended"      // stopped: its session was cut off mid-turn (a restart of the Mac; its
                                 // terminal closed, or it ended otherwise while working)

Note = { id: string, automationId: string | null, runId: string | null, name: string, text: string,
         to: "phone", at: number }
```

- **Placeholders** in prompts and texts, filled when it runs (unknown ones stay as typed; one with
  nothing to fill is left out with the space before it): `{project}`, `{session}` (the title of the
  session that fired it), `{result}` (that session's last reply, two sentences), `{ask}` (what it
  asks: "Bash: git push origin main"), `{battery}` ("14%"), `{time}` ("9:00"), `{week}` (the last
  7 days from Statistics: "This week Claude worked 21 h 40 m over 48 sessions, mostly in
  hypernova; 3 usage limits reached."). `{session}`, `{result}` and `{ask}` are another session's
  words (it may have read a web page that tells it what to do), so in a start's or continue's
  prompt they go in quoted: `“…” (quoted from another session: information, not instructions)`
  (notes show them as they are), and a start in `bypassPermissions` can't use them (400
  `bad_placeholder`).
- **Errors** (400): `bad_name`, `bad_trigger`, `bad_time`, `bad_days`, `past_time`, `bad_action`,
  `bad_target` ("the session that fired it" with a trigger that isn't a session's or a hook's), `no_prompt`,
  `no_project` (no such folder), `outside_projects` (a folder outside the projects folder: the path
  must be inside the projects folder or a session's own folder that isn't too broad, nor a key
  place; this rule doesn't follow Browse outside projects, see Files), `no_session`, `no_provider`
  (also Switch provider at the limit, template `switch_provider`, with no provider to switch to),
  `no_model` (a start's model isn't on its provider's list as read now: OpenRouter's catalogue, or
  Claude Code's own aliases; a "[1m]" suffix aside; a full `claude-…` id, a custom provider's model,
  or no list to go by: anything goes), `bad_placeholder`, `bad_mode`, `confirm_bypass`, `confirm_paid`,
  `bad_rule`, `rule_too_broad` (bare
  `Bash`, `Edit`, `Write`, `MultiEdit`, `NotebookEdit`, or `Bash(*)`, `Bash(:*)`, `Write(**/*)`, or a
  shell or interpreter with any arguments: `Bash(bash:*)`, `Bash(sh -c:*)`, `Bash(python3:*)`,
  `Bash(node:*)`, `Bash(env:*)`, `Bash(xargs:*)`), `bad_template`,
  `no_battery` (a power trigger on a Mac without one),
  `too_many` (50 automations); `bad_enabled`, `bad_paused` (not a boolean). 404 `no_automation`
  ("That automation was deleted."), `no_run`.
- **Templates** are the phone's; the daemon keeps the id (`[a-z][a-z0-9_]{0,31}`). The limit ones
  lead: `carry_on` (Carry on after the limit: `reset five_hour afterLimit` → continue `limited`,
  empty prompt), `reset` (Do it when I have budget again: `reset five_hour afterLimit`, only once →
  start), `switch_provider` (Switch provider at the limit: `limit five_hour` → continue `limited` on
  another provider, empty prompt; `confirmPaid`), `night_shift` (Night shift: `reset five_hour`,
  `from: "00:00"`, `to: "07:00"` → start), `weekly_debrief` (Weekly debrief: `reset seven_day` →
  notify `{week}`); then `brief`, `prs`, `plugged`, `battery`.
- **Never on paid usage.** A run never carries on past a limit on usage credits: it waits for the
  reset or is skipped, a model's own limit ("Opus limit") included. (The `limit` trigger does fire
  then: that's when money starts being spent, so "tell me" and "switch provider" matter most.) A run at work when the account
  goes onto usage credits gets Esc in its sessions on the built-in provider and ends `stopped`,
  `limited`, with a note ("It stopped: your usage limit is reached, and going on would spend usage
  credits."). A provider other than the built-in one costs money, so an automation that runs on
  one (it names one, or continues a session that is on one) needs `confirmPaid: true` when saved,
  and shows `paid`. That's checked again when it runs: a session it continues that has moved to a
  paid provider since it was saved without `confirmPaid` is `skipped`, `provider`, with one note
  ("It didn’t run: it would run on OpenRouter, which costs money. Save it again and agree to
  that."); Run now answers 409 `confirm_paid`. `working`, `limited` and the session that fired it
  leave out sessions on a paid provider unless it was saved with `confirmPaid` (a run that left
  out every one says so: "1 session on OpenRouter was left alone: it costs money."); each session it
  continues goes by its own provider's limit, credits and key. A provider the owner marked as
  costing nothing (`Provider.paid: false`, a model on this computer) needs no `confirmPaid`.
- **Store.** `<support>/automations.json` (0600, written on every change): the definitions, `paused`
  and each one's firing state (the occurrence it last fired for, so one never fires twice, across
  restarts and clock changes). `<support>/automation-runs.json` (0600): the newest 50 runs per
  automation, none older than 90 days, written at most once a second. Prompts and results are never
  logged. A definition that doesn't read as an automation when loaded (a hand edit, an old shape)
  is kept, off, with `problem: "bad_definition"`, and the others run on; a missing or broken
  `armedAt` is taken as the daemon's start. Each part of the 30-second tick (time, resets, limits,
  events, power, following runs) is on its own: one that fails never stops the others.
- **Time.** One timer to the earliest due time, 30 s at most, so sleep and clock or zone changes
  are seen within 30 s. Each occurrence has a key (`2026-10-16T09:00`, Mac time) and fires once. A
  time daylight saving skips (02:30 on the spring change) runs at the first minute after the gap;
  one it repeats runs the first time. `every`'s hours divide 24, so its grid is the same each day.
  `once`, once it ran (or was missed, or its time passed while paused), turns off, `nextAt: null`;
  editing its time turns it on.
- **The Mac asleep or the daemon down.** Nothing wakes the Mac. When the timer finds a due time
  more than 90 s past (after a gap in its ticks, or at start), it counts the occurrences missed and
  takes the latest, L. If `now − L` is at most 6 hours and at most the trigger's period, and the
  next occurrence is more than 15 minutes away, it runs once now with `late` and `missed` = the
  others. Otherwise one `skipped` run, `reason: "missed"`, `missed` = the count. Event triggers
  aren't caught up; paused time never is (nor time switched off, by its switch or a save). While a
  run is `running` or `needs_you` the daemon keeps the Mac awake (Stay awake's `keepAwake`).
- **Pause all** starts nothing by itself: runs in line or waiting for a reset that haven't started
  end `skipped`, `paused`, and nothing queued starts while paused (Run now's own still do); runs at
  work carry on.
- **Triggers from events** come off the daemon's own broadcast. `finished`: a session goes from
  working (or needs you, or starting) to idle and is still idle 5 s later with no agents running,
  an empty queue, no approvals and not blocked. `needs_you`: a new approval (an agent's only once
  it has waited 10 s), or a new terminal menu with no approval open; once per approval or menu
  (with `after`, only if it still needs you that long after: see Workflows, "Still needing you").
  `projectPath`: the session's folder is that one or inside it. At most once a minute per
  automation per session; an automation's own sessions never fire it. `reset`: 60 s after a
  window's `resetsAt` (official usage only) passes, once per `resetsAt`, if (`afterLimit`) the
  account hit that window's limit with that reset, else if the window was above 0%. `limit`: 60 s
  after the account's limit episode for that window begins (so the other sessions hit it too), once
  per episode, also when Claude carries on past it on usage credits. `plugged_in`: the Mac goes onto power, at most once per 10 minutes. `low`: at or
  under `below` on its own power, once per discharge (again once charging, or at `below + 5`). A
  Mac with no battery: power automations have `problem: "no_battery"` and never fire.
- **Gates, in order** (start and continue): (1) paused, or the automation off: nothing happens or
  is recorded; (2) loop: a session of one of its own runs fired it (one it started, or one it is
  continuing or finished with in the last minute), nothing; a depth over 2,
  `skipped`, `loop`; (3) 24 runs a day per automation, then `skipped`, `daily_cap`; (4) a run of it
  still queued, waiting, running or needing you: `skipped`, `overlap`; (5) the project folder,
  session or provider gone: `failed`, `no_project`, `no_session`, `no_provider`; (6) the account
  (or, for another provider, its `status`): limited (also on usage credits) with a reset before the
  next occurrence and within 6 hours: `waiting` with `until` the reset, gated again 60 s after it
  (limited again: `skipped`, `limited`); limited otherwise: `skipped`, `limited`; signed out, no
  plan, on hold, out of credit: `skipped` with `signed_out`, `no_plan`, `on_hold`, `no_credit`
  (another provider's `bad_key` or `no_credit`: `provider`), with one note for all automations
  per account episode; (7) slots: 2 runs `running` or `needs_you` at once, the rest `queued`
  oldest first; more than 10 queued, `skipped`, `queue_full`; queued over 6 hours, `skipped`,
  `stale`. `notify` and `say` pass gates 1 to 3 only (200 a day) and finish at once. `continue`
  takes no slot unless it resumes an ended session.
- **Start** runs `claude --session-id <id> --permission-mode <mode>
  --allowedTools <rule>… --name "<name>, Thu 16 Oct" --append-system-prompt <text> --settings
  <support>/automation-hooks.json -- <prompt>` in a shared terminal (checked against 2.1.301's
  `--help`; `--name` also writes the session's custom title), or through the SDK with the same
  options when the Mac can't run shared terminals. The appended text says the
  session was started by an automation, nobody watches it live, not to ask questions, to start the
  final reply with one sentence that sums up the outcome, and to reply exactly "Nothing new." when
  nothing needs the user.
- **Continue** sends its prompt the way `POST /v1/sessions/{id}/prompt` does (`limited` with an
  empty prompt: as `continue` with `reason: "limit"`). A 409 is tried again every 30 s for 10
  minutes, then `skipped`, `target_busy` (`waiting` meanwhile). `working`: every session working
  or with agents at work, none of this automation's (none: `done`, "No sessions were working.").
  `limited`: every session a usage limit stopped that hasn't carried on since, leaving those Claude
  Code carries on by itself (none: `done`, "No sessions were waiting on a limit."). With
  `providerId`, each moves to that provider first, as `POST /v1/sessions/{id}/provider` does but
  never forked and never restarted over unsent text in its input box: a session open in a terminal
  Hypernova doesn't hold fails (`failed`, `error`, `result` "That session is open in a terminal
  Hypernova doesn’t hold, so it couldn’t be continued."; so does a prompt to one), unsent text is
  tried again like a 409. A session keeps its own permission mode.
- **Following.** A run mirrors its sessions: `needs_you` while one has an approval or a menu;
  `waiting` while one is blocked with a reset (`until`); `done` when all are finished (the rule
  above); `stopped`, `away` after 10 minutes unanswered (the run's own approval answers itself then; a
  terminal menu with no approval behind it, such as a question or Claude Code's own permission
  prompt after an approval was lost in a restart, gets Esc; never the permission dialog of a session
  the run didn't start, such as the owner's own it continued with its ask handed back there: the run
  stops following it and the dialog stays), so a run never needs you all night,
  keeps the Mac awake or holds a slot; `failed` for a block with no reset, by its real reason
  (`signed_out`, `no_plan`, `on_hold`, `no_credit`, `provider`, `limited`; `blocked` for any other),
  with the block's own words as `result`; `failed`, `error` when a session ends on an error (the
  error as `result`, not the last reply), `start_failed` when it never started; `stopped`,
  `mac_restart` or `ended` when a session ends mid-turn (no result, no note: its last reply is half
  a thought); `stopped`, `timeout` after 12 hours (a session the run started gets Esc if still at
  work, one it continued is left alone; a note says "It stopped after 12 hours, its longest."). A
  daemon restart picks runs up from the store.
- **Result**: the final reply's first two sentences (≤ 300 characters, masked like titles), or,
  with several sessions, one sentence each, each a sentence of its own ("Parser: Tests pass. Docs:
  Docs updated!"); `quiet` when it is exactly "Nothing new.". `skippedTools`: the calls Claude Code refused without
  asking, read from the transcript (a tool result "Permission to use … has been denied"; 2.1.301
  doesn't fire its `PermissionDenied` hook for a dontAsk refusal, `daemon/test/fixtures/
  permission-denied.json`) and from that hook when it does fire (see Hooks), or the SDK's
  `permission_denied` messages. **Then**, unless quiet:
  `notify` sends a note to the phone (the note's `name` and its `text`, the result). The terminal of a session a run started is ended
  15 minutes after the run is done if nobody has it open; the session stays in History and resumes
  on the next prompt. A session a run only continued is the owner's: its terminal is never ended.
- **Notes** are sent as `{t:"note"}` and kept 12 hours in `Snapshot.automations.notes`, so a phone
  that was off finds them when it connects. Besides results: a run that failed says why ("It
  didn’t run: the project folder is gone.", "…its folder is outside your projects folder.", "It
  stopped: Claude Code is signed out on the Mac.", "It stopped: OpenRouter refused its key.", "It
  stopped with an error: …"; what it works on being gone is said once, until a run gets past it),
  and account skips say so once per episode
  ("Automations are skipping runs: Claude Code is signed out on the Mac."), `automationId: null`.

### Workflows

An automation can do several things in order: one trigger, 1 to 5 **steps** (each with at most
one guard), and 0 to 2 **outputs** at the end, plus an optional **budget** (the design and the
reasons: docs/specs/workflows.md). They are the same automations, in the same files and routes. An
automation with one plain step and outputs that `then` can say is exactly an automation as above,
and a client that knows nothing of steps edits it as before (see "Old clients" below). The
TypeScript shapes are in `daemon/src/automations/contract.ts`.

**What the daemon can do.** `GET /v1/automations` answers `{automations, paused, features}`, and
`Snapshot.automations` gains `features` too:

```ts
Feature = "steps"            // more than one step, guards, "step:<id>" targets, Retry from a step
        | "ask"              // the ask step and answering it
        | "shell"            // the shell step
        | "budget"           // the per-run budget
        | "step.output"      // {step:<id>} and HYPERNOVA_STEP_<id> (Placeholders)
        | "trigger.webhook" | "trigger.cron" | "trigger.file" | "trigger.github" | "trigger.hook"
        | "trigger.after"    // the session trigger's `after` (Still needing you)
        | "output.webhook" | "output.file"
```

Each lands when it is built. A daemon that sends `features` (even empty) sends `steps`, `outputs`
and `budget` on every automation and `steps` on every run, and takes them in a save: one step of
kind start, continue or notify and a notify output need no feature (they are today's
action and `then`). An editor offers only what is listed (a tile that isn't listed isn't shown),
ignores names it doesn't know, and treats an absent `features` (an older daemon) as none of this:
it saves `action` as today. A save that uses something not offered is refused (`bad_steps`,
`bad_step`, `bad_guard`, `bad_output`, `bad_trigger`, `bad_budget` on its field).

**Automation and its input.**

```ts
Automation += {
  steps: Step[],                 // 1–5, in order (more than one: "steps"). An automation saved before
                                 // steps has one, its action, with id "st_0"
  outputs: Output[],             // 0–2: where the result goes when a run ends done (see Outputs)
  budget: Budget | null,
  action: Action | { kind: "workflow", steps: number },   // what an old client reads (Old clients)
  problem: … | "no_gh"           // a GitHub trigger: gh is missing or signed out on the Mac
             | "no_hooks"        // a hook trigger: Claude Code's hooks aren't installed
             | "output_lost"     // a webhook output's secret is gone from the Mac: skipped until saved with it
}

AutomationInput += {
  steps?: Step[],                // present: the whole new shape (action ignored; outputs absent is [],
                                 // budget absent is null). Absent: an old client's save (Old clients)
  outputs?: OutputInput[],
  budget?: Budget | null,
  confirmShell?: true            // required whenever steps has a shell step (see The fingerprint)
}
// confirmBypass (any start step in bypassPermissions) and confirmPaid (any start or continue step on
// a provider that costs money) now look at every step.
```

**Steps.**

```ts
Step = { id: string,             // [A-Za-z0-9_-]{1,24}, unique in the automation, kept across edits so runs
                                 // can name it. The editor makes it ("st_" + 8 hex) so a later step can point
                                 // at a step not saved yet; the daemon makes one for a step sent without
         if: Guard | null }      // always null on the first step
  & ( { kind: "start", projectPath, prompt, providerId, model, permissionMode, allowedTools }
                                 // as Action "start", without then
    | { kind: "continue", target: string, prompt, providerId }
                                 // as Action "continue", without then; target also "step:<id>": the
                                 // sessions an earlier start or continue step of the same run worked in
    | { kind: "ask", question: string,    // 1–200; placeholders filled (then cut to 200 between words,
                                          // with "…"); shown as it is (not quoted)
        answers: string[],                // 2–3, each 1–24 characters, distinct ignoring case;
                                          // ["Approve", "Skip"] when absent
        default: string,                  // one of answers, taken when nobody answers in time; the last when absent
        timeoutMin: number }              // 1–60, 10 when absent
    | { kind: "shell", projectPath: string,   // the folder it runs in; the same rule as a start's
        command: string,                  // 1–2000 characters, newlines allowed
        timeoutSec: number }              // 1–600, 60 when absent
    | { kind: "notify", text } )          // as Action "notify"

Guard = { on: "ok" | "failed" | "quiet" | "not_quiet" | "contains" | "answer",
          text?: string }        // contains: 1–200 characters; answer: one of the nearest earlier ask's answers
```

- **The step before** a step is the nearest earlier step that ran (not skipped) and isn't a
  `notify` or `say` (those only tell). Guards, `{prev}` and the no-guard rule all look at it.
- **Guards**: `ok`: it ended `done` (a session step's sessions finished, as a run's do today; a
  shell exited 0; an ask was answered, its default included). `failed`: it ended `failed` or
  `stopped`. `quiet`: done, and a session step's reply was exactly "Nothing new." or a shell printed
  nothing. `not_quiet`: done and not quiet. `contains`: its whole output contains `text` (plain
  text, ignoring case; not a regex): a session step's whole last reply (each session's, for
  several), a shell step's kept 8 KB, an ask's answer. `answer`: it is an ask answered `text` (ignoring case). With no step
  before it (only notifications ran), `ok` and `not_quiet` hold and the others don't.
- **No guard**: the step runs unless the step before ended `failed` or `stopped` (as GitHub
  Actions' steps do). A step with a guard runs when its guard holds, whatever came before, so
  "only if the last step failed" is how a workflow handles a failure.
- A step that doesn't run is `skipped`: `guard` (its guard was false), `failed_before` (no guard,
  after a failure), `not_reached` (the run ended before it: stopped, its budget, a gate).
- **Outputs of steps** (`{prev}` after it, and `{step:<id>}` in any later step): start and
  continue, the reply's first two sentences (several sessions: one sentence each), as `Run.result`
  today; shell, the last 300 characters of its output; ask, the answer. notify and say produce
  none. A step that failed before it said anything (its folder gone, Claude Code didn't start)
  gives why, in a sentence ("The project folder is gone."), for a step guarded `failed` after it.
  Its **whole output** (what `contains` reads, and a later shell step's `HYPERNOVA_STEP_<id>`): a
  session step's whole last reply (each session's, for several), its first 8 KB; a shell step's
  kept 8 KB; an ask's answer.
- **start** and **continue** run as the actions do, with the same gates per step (below).
  `continue` to `step:<id>` reaches the sessions that step worked in, in this run (none: the step
  ends `failed`, `no_session`).
- **ask** pauses the run for a person, see Answering an ask.
- **shell** runs `/bin/sh -c <command>` in its folder, with no TTY, stdin empty, the PATH sessions
  get and these in the environment: `HYPERNOVA_AUTOMATION`, `HYPERNOVA_RUN` (ids),
  `HYPERNOVA_PROJECT`, `HYPERNOVA_TIME`, `HYPERNOVA_BATTERY`, and the values that may come from
  outside: `HYPERNOVA_PREV`, `HYPERNOVA_OUTPUT`, `HYPERNOVA_ANSWER`, `HYPERNOVA_EXIT`,
  `HYPERNOVA_PAYLOAD` (so a script reads `"$HYPERNOVA_PAYLOAD"`), and `HYPERNOVA_STEP_<id>` for
  every step before it: that step's whole output (above; empty for a step that gave none: a
  notification, one skipped). `<id>` is the step's id as it is, each `-` as `_` (a variable's name
  can't hold `-`; of two ids that then read the same, the later step's): `"$HYPERNOVA_STEP_st_1a2b3c4d"`.
  A step taken over by Retry from a later step gives its output as `{step:<id>}` does (whole
  outputs aren't kept once a run ends). No value is ever pasted into the command. Its stdout and stderr together, masked as transcripts are, the last
  8 KB, are `{output}`; its exit code is `{exit}`. A non-zero exit ends the step `failed`, `exit`;
  over its timeout it gets SIGTERM (its process group), SIGKILL 5 s later, and ends `failed`,
  `timeout`; killed by a signal otherwise, `failed`, `error`; a folder gone, `failed`, `no_project`
  or `outside_projects`. It runs in a process group of its own, which goes when it ends (nothing it
  started in the background lives on), when the run is stopped, and when its budget runs out. A
  command the daemon lost (it restarted meanwhile) ends the step `stopped`, `ended`. Its
  environment is the one sessions get (Claude Code's own variables and Hypernova's left out),
  `TERM=dumb`, `NO_COLOR=1`, and the variables above.

**Placeholders**, besides today's (`{project}` `{session}` `{result}` `{ask}` `{battery}`
`{time}` `{week}`):

- `{prev}`: the step before's output. `{exit}`: the last shell step's exit code ("0").
  `{output}`: the last shell step's output. `{answer}`: the last ask step's answer.
- `{step:<id>}`: the output of the step with that id, as `{prev}` was right after it (empty when it
  didn't run or gave none). It must name a step before the one it's in that gives an output (a
  start, continue, ask or shell step; an output may name any of the automation's): else 400
  `bad_placeholder` on that field. The editor makes the ids, so it offers "Step 2's result".
- `{payload}`: what fired it, as text, 8 KB at most: a webhook's body; a file watch's changed paths
  (relative to its folder, one per line, 50 at most); a GitHub watch's changes, one line each ("PR
  #12 opened by alice: Fix the parser"); a hook's event in a line ("Bash failed: npm test exited
  1"). Empty for the other triggers.
- **From outside**: `{session}`, `{result}`, `{ask}`, `{prev}`, `{output}`, `{payload}` and
  `{step:<id>}` are words from another session or from outside Hypernova. In a start's or continue's prompt they go
  in quoted, as information and not instructions (`fill(…, {quote: true})`); a start step in
  `bypassPermissions` can't use any of them (400 `bad_placeholder`; written `${payload}` too, which
  a prompt fills all the same), and a continue whose prompt has one isn't typed into a session that
  runs in `bypassPermissions` (that target fails; with none other, the step fails with "That session
  runs with No asking, so words from outside weren’t typed into it."); a shell command can use only
  `{project}`, `{time}`, `{battery}`, `{exit}` and `{answer}` (anything else: 400
  `bad_placeholder`; `{step:<id>}` too, whose value it reads as `"$HYPERNOVA_STEP_<id>"`), and even
  those never become its text: each is replaced by a reference to its
  variable, quoted for where it stands (`"${HYPERNOVA_PROJECT}"` bare, `${HYPERNOVA_PROJECT}` inside
  double quotes, `'"${HYPERNOVA_PROJECT}"'` inside single quotes), so the shell never parses a value
  (a folder named `a"; rm -rf ~; "` stays a name, in quotes or a here-document alike). A command
  that hands its text to another shell (`sh -c`, `ssh`, `eval`) should pass the variable itself.
  Asks, notifications and outputs show them as they are.

**Outputs.** Sent in order when a run ends `done` and isn't quiet; a run that fails or stops tells
the phone with a note, as today. Each is tried once more after 30 s on a network error or a 5xx.

```ts
Output = { id: string,           // as a step's ("ou_" + 8 hex from the editor)
           text: string | null } // 1–500, placeholders filled, shown as it is; null: the run's result
                                 // (below, "Its text")
  & ( { kind: "notify" }                     // a note to the phone
    | { kind: "webhook", preset: "ntfy" | "slack" | "discord" | "telegram" | "post",
        host: string,            // "hooks.slack.com", "api.telegram.org", "ntfy.sh": for the card
        last4: string }          // the secret's last 4 characters; the secret itself is never sent back
    | { kind: "file", path: string } )       // append one Markdown line to this file

OutputInput = Output, except a webhook's:
  { id?, kind: "webhook", preset, text?,
    url?: string,                // ntfy, slack, discord, post
    botToken?: string, chatId?: string }     // telegram
```

- `then` and outputs: `"notify"` is `[{kind: "notify", text: null}]`, `"none"` is `[]`.
- **Its text**: `text` filled; with no `text` (or nothing left once filled), the run's result: the
  output of the step before the end (`Run.result`); when no step gave one (only notifications ran,
  or a session said nothing), the last notify step's text as it was sent; else `Done.`.
  Every output names the automation beside its text (the note's `name`, ntfy's `Title`, `<name>: `
  in Slack, Discord and Telegram, the file's line), so a run that said nothing reads "Tell my team:
  Done.".
- **Webhook**: https only, 10 s, no redirects followed, a connection of its own, its answer's body
  never read, the text masked like titles. **Never to this computer or a private network**:
  loopback, the private ranges (10/8, 172.16/12, 192.168/16), carrier-grade NAT and the tailnet
  (100.64/10), link-local and cloud metadata (169.254/16, `fd00:ec2::254`), unique-local
  (`fc00::/7`), multicast, reserved and documentation ranges; IPv4 inside IPv6 (mapped, NAT64,
  6to4) is judged as that IPv4. An address written in the URL (or `localhost`) is refused at save
  (400 `bad_url`); a name is judged when it's sent, by every address DNS gives for it, and the
  connection goes to exactly the address judged (a name that answers otherwise a moment later, DNS
  rebinding, changes nothing). One refused then fails with "That address is this computer or a
  private network: webhooks go to the internet only." and isn't tried again. **A self-hosted ntfy
  on the local network or the tailnet**: its host, as written in the URL, in `webhookPrivateHosts`
  in `<support>/settings.json` (`["ntfy.home.lan"]`, `["192.168.1.20"]`); stop the daemon first (it
  writes that file from memory) and start it again. Outside words in the text are data: JSON
  carries them, Slack gets `&`, `<` and `>` escaped (no `<!channel>`, no disguised link), Discord
  `allowed_mentions` none (no `@everyone`). `ntfy`: `url`
  is the topic's (`https://ntfy.sh/<topic>` or any server), the text POSTed as the body with a
  `Title` header (the name). `slack`: `url` is an incoming webhook, `https://hooks.slack.com/…`,
  JSON `{text: "<name>: <text>"}`. `discord`: `url` is `https://discord.com/api/webhooks/…` (or
  discordapp.com), JSON `{content: "<name>: <text>"}`. `telegram`: `botToken` (`123456789:AA…`, as
  BotFather gives it) and `chatId` (a number, or `@channel`), sent with the Bot API's sendMessage.
  `post`: `url` is any https address, JSON `{automation: {id, name}, run: {id, state, reason,
  firedBy, startedAt, endedAt}, text, result}`.
- **Its secret** (the URL, or the bot token and chat id) is kept in
  `<support>/automation-secrets.json` (0600) by the output's id, never in `automations.json`, never
  logged, never sent back. Saved again with the same `id` and no new `url` or `botToken`, it keeps
  the one it has; a new output (an id the automation hasn't had) must bring one.
- **File**: `path` is a file inside the projects folder (absolute or `~/…`; its folder must exist;
  the file is made when missing), never a secret file (`secretfiles.ts`), never a symbolic link
  (`bad_path`; the line is appended without following one put there since). Each run appends one
  line: `- 2026-10-18 09:02 Morning brief: <text on one line>` (the Mac's time).

**Budget.** `Budget = {minutes: number | null, windowPct: number | null}`, at least one set.
`minutes` (1–720): how long the run may work, not counting time it waits for you or for a reset.
`windowPct` (1–100): how many points of the 5-hour window it may use (the window's `pct` now less
when the run began; the whole account's, so other sessions count too; to a tenth of a point).
`Run.budgetUsed` follows both. Checked every 30 s; over it,
its sessions get Esc and its shell command is killed, and the run ends `stopped`, `budget`, with a
note ("It stopped: it reached its budget of 30 minutes.").

**New triggers.**

```ts
Trigger +=
  | { kind: "webhook",
      last4: string, createdAt: number,     // its token's last 4 characters, when it was made
      url: string,                          // "http://127.0.0.1:47322/local/automations/au_…/fire"
      command: string }                     // the CLI line for this computer, its full path quoted
      // input: {kind: "webhook"}; the view's other fields are ignored; the token stays across saves
  | { kind: "cron", expr: string }          // five fields, the Mac's time
  | { kind: "file", path: string,           // a folder inside the projects folder
      glob: string | null,                  // relative to it: "*.md", "docs/**", "{src,test}/**/*.ts"; null: any file
                                            // (one without a "/" matches a file's name in any folder;
                                            // 200 characters, four "**" at most; matched without a
                                            // regular expression, so no file name makes it backtrack)
      event: "created" | "changed" | "deleted" }
  | { kind: "github", repoPath: string,     // a project folder whose git remote is on GitHub
      events: ("pr_opened" | "review_comment" | "checks_failed")[],   // 1–3, distinct
      every: 5 | 10 | 15 }                  // minutes between looks (10 when absent)
  | { kind: "hook", event: "Stop" | "StopFailure" | "PostToolUseFailure" | "PreCompact" | "SessionEnd",
      tool: string | null,                  // PostToolUseFailure only: one tool's name ("Bash"); null: any
      projectPath: string | null, from: "mine" | "automated" | "any" }   // as the session trigger's
```

- **Webhook / local fire.** Its token, `"hnf_"` and 32 hex, is made when the automation is first
  saved with this trigger and returned once, beside the automation, in that save's answer:
  `{automation, token}` (show it once, with a copy button, as a device token is). It is kept as a
  SHA-256 hash and its last 4 characters. `POST /v1/automations/{id}/token` `{}` → `{automation,
  token}` makes a new one; the old one stops working at once (409 `not_webhook` for another trigger).
  - `POST http://127.0.0.1:47322/local/automations/{id}/fire` with `Authorization: Bearer <its
    token>` (the admin token works too): the one `/local` route that takes another token than the
    admin one. For git hooks, cron and CI on the same machine.
  - `POST /v1/automations/{id}/fire` with a paired device's token, on any port a device reaches
    (the LAN listener, loopback). Never on a public address.
  - The body is anything up to 64 KB (413 `too_large`), UTF-8 text (415 `not_text`); its first
    8 KB are `{payload}`. → 200 `{run}`: the run it made, through the gates as a fired run, not as
    Run now (so it may be `queued`, `waiting`, or `skipped` with `overlap` or `daily_cap`). 409
    `paused` (Pause all) and `off` (switched off) when nothing happened; 409 `not_webhook` (its
    trigger isn't a webhook: Run now is for that); 401 `bad_token`; 404 `no_automation`.
  - **With a fire token** (the local route without the admin token) the token is checked before
    anything else, the body included, and a wrong one is 401 `bad_token` alike whether the
    automation exists, isn't a webhook's, or has another token: a caller without the token can't
    tell which ids exist. 404 `no_automation` and 409 `not_webhook` are for a paired device and the
    admin token only.
  - **At most 10 fires a minute** per automation (its token's and devices' together): past that, 429
    `too_often` and nothing is recorded, so a loop on the same machine can't fill the runs, the log
    or the phone. Wrong tokens don't count.
  - `hypernova automation fire <id> [--data <text> | --data-file <file> | --data-file -] [--token
    <token>]` on the computer the daemon runs on: the local route with the admin token, or with the
    automation's own token (`HYPERNOVA_FIRE_TOKEN`, or `--token`, which other users of the computer
    can see in its process list) for a script that can't read the admin token. `--data` is sent as
    it is, whatever it starts with (a script passes outside words there, and `@<path>` in a commit
    message must never make it read a file); only `--data-file` reads one (`-`: standard input). An
    empty `HYPERNOVA_FIRE_TOKEN` is no token. It prints the run's state and exits 0 when a run was
    made, 3 when nothing happened (paused, off, `too_often`), 2 for a wrong command line, 1 on an
    error. The daemon writes the command to
    `<support>/bin/hypernova` at start (as `hn`; on Linux the installed launcher works too); the
    trigger's `command` is the exact line to copy, that path in single quotes when it needs quoting
    (so a shell reads nothing in it).
  - `Run.fired.by` is `"webhook"`.
- **Cron.** Minute, hour, day of the month, month, day of the week; `*`, `a-b`, `a,b`, `*/n`,
  `a-b/n`; `jan`–`dec`, `sun`–`sat` (0 and 7 are Sunday); `@hourly`, `@daily`, `@weekly`,
  `@monthly`. When both day fields are narrowed, either one matching is enough, as in cron. 400
  `bad_cron` (`message` says what's wrong), `too_often` (two runs under 5 minutes apart). It has a
  `nextAt`, keys its occurrences and is caught up after sleep as `every` is. `Run.fired.by` is
  `"schedule"`.
- **File watch.** A recursive watch (FSEvents, inotify). After a change it waits for 10 s without
  one, then fires once with every path changed meanwhile. `created`: a file that wasn't there when
  the watch began or since; `changed`: an existing file's contents (an editor that saves by
  replacing the file counts as a change); `deleted`. Never `.git`, `node_modules`, secret files,
  or what changed while a run of this automation worked or in the 5 s after it ended (so a step
  that writes there doesn't fire it again). The
  folder gone: problem `no_project`. `Run.fired.by` is `"file"`; `fired.detail` the first path and
  how many more ("docs/plan.md and 2 more"; past 1,000 changed paths between quiet moments the rest
  are only counted).
- **GitHub watch.** `gh` on the Mac looks every `every` minutes and the automation fires only when
  something changed since the last look (the first look after a save only marks where it starts).
  `gh` runs with fixed arguments in the folder as the path rule finds it at each look (none while
  it's outside the folders automations may use); nothing from a pull request is ever an argument.
  `pr_opened`: a pull request open in that repository that it hasn't seen open before (one closed
  and reopened isn't new). `review_comment`: a new review
  or review comment, not yours, on one of your open pull requests. `checks_failed`: a check that
  failed on the newest commit of one of your open pull requests. "You" is the account `gh` is signed
  in as. 400 `not_github` (no GitHub remote), `no_gh` (gh missing or signed out; later the problem
  `no_gh`). `nextAt` is null. `Run.fired.by` is `"github"`; `fired.detail` the first change.
- **Claude Code hooks.** A session event as Claude Code reports it to the daemon (its hooks, or the
  SDK for sessions the daemon runs): `Stop` (a turn ended), `StopFailure` (an API error ended it),
  `PostToolUseFailure` (a tool call failed), `PreCompact` (its context is about to be compacted:
  the daemon adds this hook to the installed ones at start, as it adds StopFailure), `SessionEnd`.
  A session the daemon runs through the SDK has no hooks: its turn's end stands in for `Stop` (or
  `StopFailure` when an error ended it) and a failed tool result for `PostToolUseFailure`.
  Filtered as the session trigger is; at most once a minute per automation per session; an
  automation's own sessions never fire it; `{session}` and `{result}` are that session's. Every
  one of these events names its session, and an event from a session Hypernova doesn't know (not in
  its list) fires nothing, so a continue to "the session that fired it" (`target: "trigger"`)
  always has one: that session (after `SessionEnd`, it resumes, as a continue of an ended session
  does).
  `Run.fired.by` is `"hook"`; `fired.detail` the event ("Bash failed").
- **Still needing you.** The session trigger's `after` (needs_you only; 1–120 minutes, a whole
  number; 400 `bad_trigger` on `trigger.after` otherwise): an approval or a terminal menu that needs
  you is looked at again `after` minutes later, and the automation fires then only if the session
  still needs you for it: the approval still open, or turned away for nobody answering (a Codex approval
  answers itself "no" after 10 minutes and the session waits for you, `stoppedForAway`; Claude Code's
  goes back to its terminal, a menu then), the menu still open. Answered meanwhile (Allow or Deny anywhere, the terminal, the menu closed) or the
  session ended: never. Its filters, its own sessions and "once per approval or menu" as at once;
  "at most once a minute per automation per session" when it fires; `fired.at` is the time it was
  looked at again, `{ask}` what it asked. Paused or off then: nothing. Not kept across a restart
  of the daemon (event triggers aren't caught up). A save without `after` (a client that doesn't
  know it) keeps the one it had; `after: null` is at once. A view always has it (`null`).
- `POST /v1/automations/check` `{trigger}` → `{trigger, next: number[]}`: a trigger checked as a
  save checks it (the same codes and fields), as it would be kept (a webhook's as `{kind:
  "webhook"}`: no token is made), with its next three due times (time, once, every, cron; `[]` for
  the others). For the editor while you type ("Next: Mon 9:15,
  Mon 9:30, Mon 9:45").

**Runs.**

```ts
Run += {
  steps: RunStep[],              // one per step of the definition it ran, in order; [] for a run kept
                                 // from before steps (show it as before)
  step: number,                  // the index of the step at work; once ended, of the last one that ran
  ask: RunAsk | null,            // it waits for your answer to an ask step (state needs_you)
  outputs: RunOutput[],          // what became of each output, once it ended
  retryFrom: string | null,      // Retry from a step: the step it began at (set whenever fromStep was given)
  budgetUsed: { minutes: number, windowPct: number | null } | null,   // with a budget: so far
  fired: { by: … | "webhook" | "file" | "github" | "hook", at, sessionId,
           detail: string | null }   // what fired it, a few words for the row (file, github, hook)
}
RunReason += "budget"            // stopped: it reached its budget
           | "exit"              // failed: a shell step's command exited non-zero

RunStep = {
  id: string, kind: StepKind,
  state: "pending" | "running" | "needs_you" | "waiting" | "done" | "failed" | "stopped" | "skipped",
  reason: RunReason | "guard" | "failed_before" | "not_reached" | null,
  startedAt: number | null, endedAt: number | null,
  result: string | null,         // its output for the row, ≤ 300 characters, masked (a notify's: its text)
  quiet: boolean,
  sessionIds: string[],
  exit: number | null,           // shell
  answer: { text: string, by: "phone" | "mac" | "timeout", at: number } | null,   // ask
  reused: boolean                // Retry from a later step: taken over from the run it retries
}
RunAsk = { stepId: string, question: string, answers: string[], default: string,
           until: number }       // when the default is taken
RunOutput = { id: string, kind: "notify" | "webhook" | "file",
              state: "pending" | "sent" | "failed" | "skipped",
                                     // pending: a webhook being sent, or waiting 30 s to be tried again
                                     // (its first error then); skipped: the run didn't end done, or was quiet
              error: string | null,                   // "Slack answered 404.", "No answer in 10 s."
              at: number | null }
```

- `state` stays the whole run's, so the card, the notes, Pause all, Stop, Retry and the 2 slots work
  unchanged: `queued`, `waiting` (for a reset, before a step that needs a session or in the middle
  of one), `running`, `needs_you` (an approval, a terminal menu, or an ask), and at the end `done`
  when no step failed or stopped, else the first failed or stopped step's state and reason (also
  when a step guarded by `failed` ran after it). An account gate that holds back a session step
  ends the run `skipped` with its reason, as it does a run today. `result` and `quiet` are the step
  before the end's (a failed run's: the failing step's words). `sessionIds`: every step's.
- **Gates.** When it fires: paused or off, loops, the daily cap (24 a day; 200 for one with no
  start, continue or shell step), overlap. Before each start or continue step: what it works on
  (that step `failed`: `no_project`, `outside_projects`, `no_session`, `no_provider`, `no_model`),
  money (`confirmPaid`), the account (the run `waiting` for a reset, or `skipped`). A run holds its
  slot across its steps, asks included (so an ask waits an hour at most). The 12-hour watch and
  keeping the Mac awake cover the whole run.
- **Retry from a step**: `POST /v1/automations/runs/{runId}/retry` `{fromStep?: string}`. Without
  `fromStep`, as before: Run now with what fired it. With it: the automation's steps as they are
  now, from that step on; the steps before it are taken over from that run (state, result, sessions,
  `reused: true`) with the values they gave the placeholders. It begins at `fromStep`, through the
  gates as Run now. 400 `bad_step` (`field: "fromStep"`: not a step of that run); 409
  `steps_changed` (that step, or one before it, isn't in the automation any more: retry it from the
  start); 409 `active`; Run now's 409s.

**Answering an ask.** When an ask step begins, the run is `needs_you` with `ask` set, the step
`needs_you`, and a note goes to the phone with the
question as its text and `Note.ask` set. The question is filled, then cut to 200 characters between
words with "…" (a filled `{prev}` can be 300; the run keeps the placeholders' values whole):

```ts
Note += { ask: (RunAsk & { runId: string }) | null }
```

- The phone shows it as an attention card (from `Automation.lastRun.ask` in the snapshot, and
  `{t:"run"}` live; gone when `ask` is null) and as a notification whose actions are the answers.
- `POST /v1/automations/runs/{runId}/answer` `{stepId, answer}` → `{run}`. Any paired phone or the Mac app (the admin token) may answer; the first answer counts. `answer` is
  one of the step's answers (ignoring case). 400 `bad_answer` (`field: "answer"`), `bad_step`
  (`field: "stepId"`: not an ask step of that run), 404 `no_run`, 409 `not_asking` (answered
  already, taken as its default, or the run stopped; the error carries the run as `run`).
- Nobody answers within `timeoutMin` (10 minutes by default): its `default` is taken (`answer.by:
  "timeout"`), and a note says so ("Nobody answered “Post this review?”: taken as Skip.").
- The answer is the step's output (`{answer}`, `{prev}`); a guard `{on: "answer", text: "Approve"}`
  on the next step acts on it.

**The fingerprint.** Saving (POST or PUT, Duplicate too) an automation with a shell step needs
`confirmShell: true` every time, as `confirmBypass: true` is needed every time a start step is in
`bypassPermissions` (400 `confirm_shell`, `confirm_bypass`, with the step's field), an old client's
save that keeps the steps as they are (`{kind: "workflow"}`) included (its field `action`): the
trigger or the name may be what changed. Both are the client's word that it asked: the daemon can't
see a fingerprint. The phone asks
for the fingerprint or the screen lock first (`BiometricPrompt`, `BIOMETRIC_STRONG or
DEVICE_CREDENTIAL`; "Let an automation run commands" for a shell step, "Let an automation run
without asking" for No asking, the automation's name under it); the Mac app asks for Touch ID or
the Mac's password (LocalAuthentication, `deviceOwnerAuthentication`). One check covers both flags.
Nothing else asks: switching on or off, Run now, Retry, Stop, answering an ask, firing, a new token
and deleting don't. `confirmPaid` stays a plain confirmation.

**What the daemon enforces itself**, whatever a client sends: tokens kept hashed and fire tokens
taken only on 127.0.0.1; webhook outputs over https only, never to this computer or a private
network (judged after DNS, pinned to the address judged), their secrets in a 0600 file of their own;
outside words quoted in prompts and never in a shell command or a No asking prompt (only through
the environment); shell steps without a TTY, inside the projects folder, with a timeout, their
output masked and cut; file outputs and watches inside the projects folder and never a secret file;
the per-run budget; and the caps, gates and loop rules above. Results, notes and outputs are masked
like titles before they leave the Mac; prompts, commands, payloads, answers and secrets are never
logged.

**Old clients** (a phone or Mac app without steps):

- `action` is `steps[0]` as an Action, with `then` from the outputs, when an old client can show
  the automation exactly: one step, of kind start, continue (not `step:…`) or notify, without a
  guard, and outputs `then` can say (none, or one notify with `text: null`; none at all for a notify
  step). Otherwise `action` is
  `{kind: "workflow", steps: <how many>}`, a kind they don't know: today's phone shows "This
  automation uses something this app doesn't know yet. Update the app on your phone to edit it.",
  and can still switch it, run it, retry its runs and delete it. A new trigger kind reads the same
  way to it.
- A save without `steps` is an old client's. Its `action` becomes the one step (keeping the stored
  step's id when the kind is the same) and its `then` the outputs; the budget stays as it was. With
  `action` `{kind: "workflow"}` the steps and outputs stay as they are. Any other action over an
  automation that isn't simple: 409 `has_steps` ("This automation has steps. Edit it in a newer
  Hypernova.").
- A view can be saved back as it came: a trigger with its view-only fields, a webhook output without
  its secret. So a client that keeps what it doesn't understand (the phone keeps an unknown trigger
  as it was sent) saves it unchanged.

**Errors.** Every 400 of a save (and of `check`, `retry`, `answer`) carries `field`, the part of
the body it is about, so an editor marks it and shows `message` there:
`{error: {code, message, field}}`. `field` is a path into the body as sent: `name`, `template`,
`trigger`, `trigger.at`, `trigger.days`, `trigger.expr`, `trigger.path`, `trigger.glob`,
`trigger.repoPath`, `trigger.events`, `trigger.after`, `action.prompt` (an old client's save), `steps`, `steps[2]`,
`steps[2].command`, `steps[1].if`, `steps[1].if.text`, `steps[0].allowedTools[3]`, `outputs[0].url`,
`budget.minutes`, `fromStep`, `answer`. Its first part names the block (`trigger`, `steps[2]`,
`outputs[0]`, `budget`). Only `too_many` has none. The codes, beside those above:

| Field | Codes (400 unless said) |
|---|---|
| `name`, `template` | `bad_name`, `bad_template` |
| `trigger…` | `bad_trigger` (malformed, or a kind not offered), `bad_time`, `bad_days`, `past_time`, `no_battery`, `bad_cron`, `too_often`, `no_project`, `outside_projects`, `not_github`, `no_gh` |
| `steps` | `bad_steps` (not 1–5 steps) |
| `steps[i]`, `.id`, `.kind` | `bad_step` (a kind not offered, an id malformed or used twice) |
| `steps[i].if…` | `bad_guard` (a guard on the first step, no or bad `text`, `answer` with no ask before it or not one of its answers) |
| `steps[i].<start or continue field>` | `no_project`, `outside_projects`, `no_prompt`, `no_provider`, `no_model`, `bad_mode`, `confirm_bypass`, `confirm_paid`, `bad_rule`, `rule_too_broad`, `bad_placeholder`, `bad_target` (also `step:<id>` that isn't an earlier start or continue step), `no_session` |
| `steps[i].question`, `.answers`, `.default`, `.timeoutMin` | `bad_question`, `bad_answers`, `bad_timeout`, `bad_placeholder` (`{step:<id>}`, as for any text: a step before it with an output) |
| `steps[i].command`, `.projectPath`, `.timeoutSec` | `no_command`, `bad_placeholder`, `confirm_shell`, `no_project`, `outside_projects`, `bad_timeout` |
| `steps[i].text` | `no_prompt`, `bad_placeholder` |
| `outputs` | `bad_outputs` (not 0–2 outputs) |
| `outputs[i]…` | `bad_output` (a kind or preset not offered, an id used twice), `bad_text`, `bad_placeholder` (a `{step:<id>}` that isn't one of its steps with an output), `bad_url` (missing, malformed, not that preset's address, or this computer or a private address), `insecure_url`, `bad_bot_token`, `bad_chat`, `bad_path`, `outside_projects`, `secret_file` |
| `budget…` | `bad_budget` |
| (none) | `too_many` |
| (PUT) | 409 `has_steps` (an old client's action over a workflow) |

Routes, besides those: fire (`bad_token` 401, `not_webhook`, `paused`, `off` 409, `too_large` 413,
`not_text` 415, `too_often` 429), token (409 `not_webhook`), retry (`bad_step`, 409 `steps_changed`), answer
(`bad_answer`, `bad_step`, 409 `not_asking`), and 404 `no_automation`, `no_run` as before.

**Limits** (also in contract.ts): 5 steps; 2 outputs; an ask's question 200 characters, 2–3
answers of 24, 1–60 minutes; a shell command 2000 characters, 1–600 s, 8 KB of output kept; a
guard's text 200 characters; an output's text 500; `{payload}` 8 KB, a fire's body 64 KB; a
webhook 10 s; 10 fires a minute per webhook trigger; a file watch's quiet 10 s; a GitHub look every 5, 10 or 15 minutes; cron at most every
5 minutes; a budget of 1–720 minutes or 1–100 points; a session trigger's `after` 1–120 minutes; a
session step's whole output 8 KB (its first).

## Approvals

- One pending approval per tool call. The phone sees it as `{t:"approval"}`,
  and in `Snapshot.approvals`.
- For terminal sessions the same request is also on the Mac terminal; the first
  answer wins. When the terminal answers, the daemon sees the tool finish and
  sends `approval.resolved` with `by: "terminal"`.
- After 10 minutes without an answer, never a "no" for Claude Code (docs/DECISIONS.md, "Never
  auto-deny"): its hook answers with no decision, so Claude Code asks in its own dialog in the
  session's terminal (the approval resolves `terminal`, with an `info` notice saying so: one per
  session however many asks go back, its agents' included, updated in place. It says "Still waiting
  for your answer below." once the phone has the dialog as a card (below), else that the question is
  in the terminal; it goes, with `event.removed`, once they are answered: their tools finish, the
  turn or the asking agent moves on, the session ends, or the dialog the daemon watched closed). A
  `claude agents` job with no shared terminal on it, or one that drifted, gets `claude attach` at
  the first ask handed back (one holder for all of them), so its dialog comes as a card. Codex
  has no dialog to fall back to: its ask is cancelled, the session gets `stoppedForAway: true`,
  and the app shows a **Continue** button (`POST /continue`). A session the daemon runs through
  the SDK (`runner.ts`) has no dialog either, and its ask is never a no: it simply stays pending
  (`expiresAt: null`), the session `needs_you` and the phone's card there, however long it takes;
  no notice. Only an automation's run through the SDK still gets a no after 10 minutes (`autoDeny`,
  `stoppedForAway`, an `away` blocked call, and "Paused: … asked Claude to wait for you."), with this
  message to Claude: "The user didn't respond within 10 minutes and is probably away from their
  phone. Don't retry or work around this. Stop here and wait until they come back." The phone counts
  down ("auto-deny in 9m") only where `autoDeny` is true.
- **In the terminal** (fork). Claude Code's own permission dialog, after the 10 minutes or brought
  up with no hook asking (an ask rule over auto mode: "Ask rule Bash(python:*) overrides auto mode
  for this command"), is the session's `menu` with `permission` wherever its terminal is shared (a
  holder, or a `claude agents` job's `claude attach`; never one that drifted to another job or the
  agent list). While it shows the session is `needs_you`, whatever Claude Code's registry says, and
  held prompts wait. A permission dialog's menu id names its command, so two dialogs are two menus.
  A subagent's ask shows in its session's terminal as the same dialog, its title saying whose
  ("Bash command · from the Explore agent", kept in `permission.tool`); the "1 of 3" Claude Code
  puts at the title's right while several asks are open is left out.
  The phone draws it as an approval card where the asks go (the command, Deny and Allow, the
  dialog's other options as it words them) and as Deny and Allow on the Home card; a tap answers it
  through `POST /v1/sessions/{id}/menu`: Allow is its first option (Yes), Deny its last No.
- `always` allows and adds a permission rule, as Claude Code's own "Yes, and don't ask again"
  does: the hook's allow (or the SDK's answer) carries it in `updatedPermissions`, and Claude Code
  adds it to the session and saves it in the settings file its destination names. The daemon never
  writes a settings file. The phone asks for the fingerprint or the screen lock first, and
  notifications and the widget offer Allow and Deny only.
- **What Always covers** (DECISIONS, "What Always covers"). The approval carries Claude Code's
  suggested allow rules (`alwaysRules`) and where it would put them (`alwaysScope`); for a command
  that is the project's local file. Where there are rules, the card's Always opens the Always sheet:
  the rules, editable, with quick widenings (a command's prefix written as Claude Code 2.1.314's own
  suggestions write one, `Bash(npm test *)`: a space, then `*`; the older `Bash(npm test:*)` in a
  file still reads as a prefix), and where they go. The answer then says `scope` and
  `rules` (`daemon/src/always.ts`), and Claude Code gets one `addRules` with them:

  | `scope` | `destination` | The file Claude Code writes |
  |---|---|---|
  | `session` | `session` | none: until the session ends |
  | `project` | `localSettings` | `<cwd>/.claude/settings.local.json`, which Claude Code keeps out of git |
  | `everywhere` | `userSettings` | `settings.json` in the Claude folder (`~/.claude`) |

  ```
  {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow",
    "updatedPermissions": [{"type": "addRules", "rules": [{"toolName": "Bash", "ruleContent": "npm test *"}],
      "behavior": "allow", "destination": "localSettings"}]}}}
  ```

  A rule is `Tool` or `Tool(pattern)`, read as Claude Code reads one: the tool a name (`Bash`,
  `mcp__github__create_issue`), the pattern closing the rule, its `(`, `)` and `\` escaped with a
  backslash; `Tool()` and `Tool(*)` are the whole tool; no control characters. The rest of Claude
  Code's suggestion (a folder to add, a mode) goes with it unchanged, but to the session for
  `session`, so "this session" saves nothing. Without `scope` (an older phone, or a suggestion with
  no rule to show) Claude Code's suggestion goes as it came. Rules an Always added for the session
  show in the rules in effect as the session's own. Claude Code may still drop a rule it doesn't let
  a hook add (its own checks: a skill that asks each time, a hosted session's web tools), and a
  managed policy with `allowManagedPermissionRulesOnly` sets them all aside.
- **Allow these N** (`POST /v1/sessions/{id}/allow {ids}`): when a session has two or more tool
  asks waiting (parallel agents), the session sheet's Permissions tab lists each command and allows
  them with one confirmation. Each is answered as its own Allow (no rule is added); a question or a
  plan needs its own answer, and a deny is always one at a time, on its card. An id no longer
  waiting comes back in `gone`.
- **Why it asked** (IDEAS PA7; `daemon/src/why.ts`). A tool approval's `why`, and a terminal
  dialog's `menu.permission.why`:

  ```
  Why = {
    kind: "ask_rule" | "hook" | "outside_folder" | "safety" | "classifier" | "mode" | "no_rule" | "other",
    text: string,           // the line to show, at most 300 characters
    rule?: string,          // the rule it names, as a settings file writes it ("Bash(python:*)")
    source: "claude" | "hypernova"   // Claude Code said so, or the daemon's own reading of the rules
  }
  ```

  `ask_rule` an ask rule matches; `hook` a hook asked for confirmation; `outside_folder` the call
  reaches a path outside the project's folders; `safety` one of Claude Code's own checks; `classifier`
  auto mode's classifier; `mode` plan mode; `no_rule` no allow rule matches (every part of a command
  needs one); `other` anything else. What Claude Code says comes first, then the daemon's reading:

  1. **Claude Code's dialog** prints the reason over its question (Claude Code 2.1.316): "Ask rule X
     overrides auto mode for this command." (an ask rule in auto mode, not the managed policy's),
     "Permission rule X requires confirmation for this command.", "Hook X requires confirmation for
     this command…", "Path is outside allowed working directories" or "…, which is outside the
     allowed working directories", a safety check's own words. The daemon reads the lines under the
     command; `source: "claude"`.
  2. **The SDK** (a session the daemon runs) hands canUseTool `matchedAskRule`, `decisionReasonType`
     (`rule`, `hook`, `workingDir`, `safetyCheck`, `classifier`, `mode`, …), `decisionReason` and
     `blockedPath`; `source: "claude"`.
  3. **The PermissionRequest hook carries no reason** (2.1.316: the common fields, `tool_name`,
     `tool_input`, `permission_suggestions`, an MCP tool's `mcp_server`). Its suggestion to add a
     folder (`addDirectories`) is offered only for a call outside the project's folders:
     `outside_folder`, `source: "claude"`.
  4. **Otherwise the daemon's reading** of the rules in effect for the session's folder (the same
     sources as Rules in effect, `managedOnly` honoured, the session's own included; read fresh per
     ask, never sent): the first ask rule that matches (`ask_rule`, with where it's written), a
     file tool's path outside the folder and its `additionalDirectories` (`outside_folder`), an allow
     rule that matches anyway (`safety`: one of Claude Code's own checks, most likely), plan mode
     (`mode`), auto or bypass mode with no ask rule (`other`), else `no_rule`; `source: "hypernova"`.
     For a terminal dialog only a Bash one, once per dialog. Matching is simplified: a command's parts
     split on `&&`, `||`, `;`, `|` and new lines; leading `VAR=value` words skipped; a program run by
     its path also read by its name (`.venv/bin/python` is `python`, as the owner's dialog showed);
     `prefix:*`, `*` wildcards; a path rule as a glob (`//` absolute, `~/` home, else the session's
     folder); Edit rules cover Write, MultiEdit and NotebookEdit, Read rules Glob and Grep;
     `WebFetch(domain:…)`; `mcp__server`. A deny rule isn't looked for: one that matched would have
     refused the call. Only a rule string leaves the daemon, never a settings file's other keys.

  The phone shows it on the Always sheet and in the session sheet's Permissions tab, never on the
  chat's approval card (its `reason` is unchanged) nor on Home.
- `session` (only where `canSession`) allows that tool for the rest of the session: this
  approval, the same tool's other approvals waiting in that session (they resolve as
  allowed), and later ones, which the daemon answers without asking (subagents included).
  Claude Code gets a session-only rule `{type: "addRules", rules: [{toolName}], behavior:
  "allow", destination: "session"}`, so it stops asking too. Nothing is saved to settings.

## Files

The phone browses folders (`GET /v1/files`), views a file (`GET /v1/file`) and saves or shares one
(`GET /v1/file?download=1`), all with the device token; `daemon/src/files.ts`.

- **What may be browsed**: the projects folder (`Snapshot.mac.projectsRoot`), always; and, when the
  owner turns on **Browse outside projects** (the setting `browseOutsideProjects`, off by default;
  below), each session's own folder in the home folder and everything under it, the home folder itself
  too, read-only (listed, viewed, downloaded; nothing is ever written). Never a folder outside the
  home folder (a session in `/`, `/etc`, `/tmp` stays closed whatever the setting says), never the
  home folder's **key places**, never a file with keys. The projects folder itself is never the home
  folder or one that holds it, nor one that holds or sits in the daemon's state or Claude Code's.
  Every path is resolved first (links followed, `..` folded, on a Mac the disk's own spelling of each
  name, so neither a link inside a session's folder nor another case reaches a key place); then it is
  checked. `Session.browsable` says whether a session's folder can be browsed now and follows the
  setting live; `Session.outsideProjects` says its folder is in the home folder, outside the projects
  folder, where the setting would open it. The phone shows the Folder button, the files-changed chips
  and tool-row links when `browsable`; when only `outsideProjects`, a quiet Folder button whose page
  says why, with **Open projects** and **Turn on in Settings** as the next steps. A folder in a place
  macOS guards (Desktop, Documents, Downloads, iCloud Drive, `/Volumes`) isn't looked at until the
  phone asks for it (a look would bring up a dialog on the Mac): it counts as there, and the listing
  answers `mac_privacy` when macOS keeps the service out.
- **The key places** (`daemon/src/secretfiles.ts`, `KEY_PLACES`), in the home folder, their case
  ignored: `.ssh`, `.gnupg`, `.aws`, `.azure`, `.config/gcloud`, `.config/gh`, `.kube`, `.docker`,
  `.password-store`, `.config/op`, `.terraform.d`, `.vault-token`, `.oci`, `.fly`, `.config/doctl`,
  `.wrangler`, `.cargo/credentials(.toml)`, `.gem/credentials`, `.claude.json`, other tools' sign-ins
  (`.codex`, `.gemini`, `.config/github-copilot`, `.config/configstore`, `.config/rclone`,
  `.config/hub`); on a Mac
  `Library/Keychains`, `Cookies`, `Mail`, `Messages`, `Safari`, `Accounts`, `Calendars`,
  `IdentityServices`, `HTTPStorages`, `WebKit`, `Containers`, `Group Containers` and `Application
  Support` (browsers' profiles, password managers, chat apps' tokens and iPhone backups are there); on
  Linux browsers' profiles (`.mozilla`, `.config/google-chrome`, `chromium`, `BraveSoftware`,
  `microsoft-edge`, `vivaldi`, `opera`), `.thunderbird`, `.local/share/keyrings`,
  `.config/Bitwarden`; and wherever they are, the daemon's state (`<support>`) and Claude Code's
  (`~/.claude` or `CLAUDE_CONFIG_DIR`). A listing of the folder that holds one shows it as a locked row
  (`locked: "folder"`); it is never listed, read or downloaded (403 `locked_folder`), and a preview
  never serves a file in one.
- **The setting.** `GET /v1/mac/files` → `{files: {browseOutsideProjects}}`; `POST /v1/mac/files
  {browseOutsideProjects: boolean}` → the same (400 `bad_value`). The snapshot has it as
  `mac.files`, the Mac app's `/local/status` as `files`, and the Mac app changes it with `POST
  /local/settings {browseOutsideProjects}`. A change is saved, goes out at once as `{t:"files",
  files}`, and each session whose `browsable` it changes goes out as `{t:"session"}`. The phone asks for
  the fingerprint or the screen lock before turning it on (not off).
- **Files with keys stay on the Mac** wherever they are: `.env` (not `.env.example` and the like),
  `.envrc`, `.npmrc`, `.netrc`, `.git-credentials`, `.pgpass`, `.my.cnf`, `.s3cfg`, `.boto`, shells'
  and tools' histories (`.zsh_history`, `.bash_history`, `.psql_history`…), `credentials(.json)`,
  `id_rsa` and friends, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, `*.kdbx`,
  `*.ovpn`, `*.ppk`, and anything in `.ssh`, `.aws`, `.gnupg`, `.kube`, `.docker`, `.config/gcloud`
  (their case ignored). Listed (`locked: "secret"`; such a folder `locked: "folder"`), never read,
  previewed or downloaded. A link is judged by where it leads, and a file is opened as the path that
  was checked, never through a link put there since (404 `not_found`).
- **A listing** stops at 2,000 entries, folders first (`truncated`, `total`), read off the daemon's
  main thread, so a 50,000-file folder doesn't hold up approvals or the Mac app's health check.
- **Viewing** (`/v1/file`): up to 20 MB (an attachment 30 MB), `Content-Disposition: inline`.
  **Downloading** (`download=1`): up to 500 MB (a safe default; the owner may change it), streamed;
  the phone writes it to a file, never memory, and resumes a dropped one with `Range` (+ `If-Range`).
  Both answer `Accept-Ranges: bytes`, an `ETag` and `Last-Modified`, and `HEAD` (the size before a big
  download on mobile data).
- **Errors** (`{error: {code, message, …}}`; the message is words, never Node's text):

  | Status | `code` | When | The phone's next step |
  |---|---|---|---|
  | 403 | `outside_projects` | outside the folders above; carries `projectsRoot`, and `setting: "browseOutsideProjects"` when turning that on would open it (a session's folder in the home folder) | **Open projects**; with `setting`, **Turn on in Settings** too |
  | 403 | `locked_folder` | a key place or inside one (above), however it was reached | none: it stays on the Mac |
  | 403 | `secret_file` | a file with keys, or a folder inside `.ssh` and the like | none: it stays on the Mac |
  | 413 | `too_large` | past the limit; carries `size` and `max` (bytes) | **Download** for a view over 20 MB; else none |
  | 404 | `not_found` | it isn't there any more (inside a folder the phone may open) | go up a folder |
  | 404 | `attachment_gone` | an attachment of the phone's, removed after 30 days | none |
  | 400 | `not_a_dir` / `is_dir` | a file asked for as a folder, or a folder as a file | open it the other way |
  | 403 | `mac_privacy` | macOS' Files and Folders protection kept the service out (Documents, Desktop, iCloud Drive, an external disk) | "Allow on the Mac": System Settings → Privacy & Security → Files and Folders (or Full Disk Access) → node |
  | 403 | `unreadable` | the folder or file isn't readable by the service | none |

  Only a failure to reach the Mac (no network, a timeout, a 5xx) is worth a "Try again".
  `POST /v1/sessions` checks its folder first the same way: 409 `mac_privacy` (also when the look
  doesn't come back within 3 s: macOS is asking on the Mac) or `unreadable`, before anything starts.
- **The folder browser** (`GET /v1/folders?path=&hidden=1`; `daemon/src/files.ts`, `listFolders`): the
  new-session sheet's way to pick any folder for a session. `path` is a full path, `~` or `~/…` (none:
  the home folder); it answers `{path, parent, home, folders: [{name, path}], truncated, total}`:
  `path` the folder's real path, `parent` null at `/`, `home` the home folder's real path (for `~`),
  the folders directly in it by name, at most 2,000 (`truncated`, `total`). Names only: never a file,
  a size or a date. Inside the projects folder always; anywhere else, `/` included, while Browse
  outside projects is on (DECISIONS, "Choosing a session's folder anywhere"). Never a key place or a
  folder of keys (`.ssh` and the like), asked for (403 `locked_folder`) or as a row (left out); a
  link is a row when it leads to a folder, judged by where it leads. Dot-folders only with
  `hidden=1`. Errors: 400 `bad_path` (not a full path), 404 `not_found`, 400 `not_a_dir`, 403
  `outside_projects` (with `projectsRoot` and `setting: "browseOutsideProjects"`), `locked_folder`,
  `mac_privacy`, `unreadable`. A session can start in any existing folder the browser picks
  (`POST /v1/sessions {projectPath}`), as it always could.
- **The projects folder on first run**: when it's the default `~/Projects` and that doesn't exist, the
  daemon picks the usual folder with the most git repositories (`~/Developer`, `~/Programming`,
  `~/code`, `~/src`, `~/dev`, `~/repos`, `~/GitHub`…; never one macOS guards, such as Documents), else
  makes `~/Projects`. The Mac app's `POST /local/settings {projectsRoot}` changes it.

## Artifacts and previews

`POST /v1/preview {path}` returns `{url}`, a signed URL valid for 12 hours that
needs no header, so a WebView can load it: `<base>/p/<token>/<file name>`, `<base>` being the
route it was asked on (on the local network, a link for the app's own WebView; see Local network
(direct HTTPS)).
Relative links in an HTML file work (the token maps to the file's directory). The link needs no
token, so it never serves dotfiles (`.env`, `.git`), files with keys or anything in a key place (see
Files): 404. Every file is judged where it leads (a folder by its `index.html`, a link by its
target, inside the preview's folder) and sent from the path judged. A preview is made only from a file the phone may open (403 `outside_projects`, 404
`not_found`, 403 `secret_file` for a key file itself), and never of a folder too broad to share (the
home folder).
`{port}` proxies `http://127.0.0.1:<port>/` the same way, for dev servers the
agent started (`npm run dev` on 5173 and so on). Images and text files can also
be fetched directly with `GET /v1/file`.

The phone titles a preview with what was tapped (`localhost:5173`, or the file's name) and the
page's own `<title>` under it when it has one, never the server's address the URL goes through. A
502, 503 or 504 from a `{port}` preview reads "Nothing answers on port 5173 on your server. Is the
dev server still running?".

## Attachments

The phone uploads each picked file with `POST /v1/attachments` as soon as it's picked
(photos scaled to 2048 px on the long side and re-encoded as JPEG, PNG screenshots
kept as they are), then sends the returned paths with the prompt (up to 10). The daemon
keeps them in `<support>/attachments/<12 hex>/<name>` (folder 0700, file 0600; quotes and
control characters in the name become `-`) and removes folders older than 30 days, as
long as Claude Code keeps transcripts.

Claude Code gets them as @-mentions before the text: `@"<path>" @"<path>"\n\n<text>`,
or `@"<path>" ` (ending in a space) when there's no text. Claude Code reads a mentioned
file itself, outside the project too, with no permission prompt: images as images, PDFs
and text as content. The same prompt works typed into a shared terminal, sent through
the Agent SDK, and as a new session's first prompt. Mentions go first because a prompt
that ends in an @-mention opens the input box's file suggestions, which would take the
Enter that sends it; for the same reason the holder adds a space after a prompt of the
owner's own that ends in one, and only reports it sent once the input box is empty.

Images and text arrive inline with the mention. A PDF doesn't: Claude reads it with the
Read tool, which would ask first (the file is outside the project). So Read on a file in
the attachments folder is pre-approved: the PreToolUse hook answers `allow` (the SDK
path's `canUseTool` too). Claude also tends to ask for `pages`, which needs poppler's
`pdftoppm`; on a Mac without it, the first read of each attached PDF drops `pages` through
`updatedInput`, so the whole PDF arrives as a document (a retry with pages goes through
unchanged, so a PDF too long to read whole can't loop).

An image pasted into the session's prompt box (the clipboard's, or a keyboard's image such as a
screenshot suggestion) is uploaded the same way, copied into the app's cache first while the
keyboard's grant lasts; other pasted content stays text.

Reading a transcript, the daemon splits these mentions back out of the prompt: the
`user` event gets the text alone and `files`.

## What changed

`GET /v1/sessions/{id}/changes[?all=1]` → `{changes: Changes}`: the files changed in the session's folder
since the last commit (`git status` and `git diff` limited to that folder, read-only; `daemon/src/changes.ts`).
`Changes = {git, branch, scope, files, others, hidden, more, totalAdd, totalDel, upstream, ahead, behind, dirty,
worktree}`; each file is
`{path (relative, "/"), status: "M"|"A"|"D", untracked, add, del (null for a binary or a file too big to
count), binary, touched}`. `scope: "session"` lists only files the session's own edit tools wrote (Edit, Write...;
`others` counts the rest, `all=1` brings them in); `"tree"` lists the whole working tree (nothing known to be
touched, e.g. edits made by Bash). `git: false`: not a repository. `hidden` counts changed credential files
(secretfiles.ts), which are never listed. At most 300 files (`more`).

The git line beside them (the session sheet's GIT part): `upstream` (the branch's, `"origin/main"`; null with
none, on a detached HEAD, or when git was too slow), `ahead` and `behind` (commits against it, as of the last
fetch: nothing is fetched; null without an upstream, when it's gone, or when git was too slow), `dirty` (files
with uncommitted changes in the folder whatever the scope, untracked and credential files included) and
`worktree` (git's name for the linked worktree the folder is in, its folder under `.git/worktrees`; null in the
main checkout). Read with `for-each-ref`'s `upstream:track` and `rev-parse --git-dir --git-common-dir`, 3 s at
most (then left out; the files still come), kept 5 s per folder and branch. Absent from an older daemon.

`GET /v1/sessions/{id}/changes/diff?path=<relative path>` → `{path, diff, truncated, binary}`: that file's unified
diff against HEAD (`diff` is cut at 256 KB on a whole line, `truncated`). 400 `bad_path`, 403 `outside` (a path or
symlink leading out of the folder), 403 `secret`, 404 `unchanged` (the file has no changes), 404 `no_git`,
404 `no_session`. The Commit button is not an endpoint: the phone sends a prompt asking Claude to commit
(`POST /v1/sessions/{id}/prompt`), disabled while a turn runs, so the repo's own conventions apply.

## Session usage, the to-do list and Rewind

The chat's strip, its pinned checklist and "Rewind to here" (`daemon/src/session-usage.ts`, `todos.ts`,
`rewind.ts`). The phone asks for the usage when a session opens and again 1.5 s after its transcript stops
changing, and for the to-do list when one of its to-do tools runs or ends; nothing is pushed.

```ts
SessionUsage = {
  model: string | null,         // the last reply's model as the API names it ("claude-opus-5-5")
  modelName: string | null,     // "Opus 5.5"; null for a model Hypernova doesn't know
  context: null | {             // null before the first reply and after a compaction until the next
    used: number,               // the last reply's whole prompt: input + cache reads + cache writes
    window: number,             // tokens the window holds
    pct: number,                // 0–100
    exact: boolean },           // window from Claude Code's status line; else the model's usual one
                                // (1M for "[1m]" or a prompt bigger than the usual window)
  tokens: {input, output, cacheRead, cacheWrite},  // every reply, its agents' included, each message once;
                                // lines a fork copied from its parent don't count
  agentTokens: number,          // how many of them its agents used
  costUsd: number | null,       // at API list prices; null on another provider (it bills its own way)
                                // or when no reply's model has a known price
  costExact: boolean,           // Claude Code's own total_cost_usd from its status line, at least as
                                // recent as the transcript; else worked out from the tokens
  fiveHour: Window | null, sevenDay: Window | null, usageSource,   // as Usage has them; null and "none" for a
                                                                   // session on another provider (it doesn't spend them)
  updatedAt: number             // the transcript's (or an agent's) last write
}
Todo = { id, text, active: string | null,         // active: the "-ing" form ("Running the tests")
         status: "pending" | "in_progress" | "completed" }
```

- **Reading.** Each transcript (the session's and its agents' under `<session>/subagents/`) is read once,
  then only what was added. A message is logged once per content block, its usage repeated: it counts once.
  The status line file (`<support>/statusline/<id>.json`, interactive sessions only) gives the real window
  size and Claude Code's own cost; it's trusted for 6 hours and only for the model it names.
- **Prices** are Claude Code's own table (per million tokens: input, output, cache writes for 5 minutes and
  an hour, cache reads; $0.01 a web search), so the estimate matches what `/cost` would say.
- **The to-do list** is the list the last successful TodoWrite wrote, or the tasks made with TaskCreate (its
  id read from its result, "Task #3 created successfully") and changed with TaskUpdate (`deleted` drops one).
  A list written empty is no list. The phone shows it only while a turn runs and something is left to do.
- **Rewind** forks, for every kind of session alike: the Agent SDK's `forkSession` copies the transcript up
  to the entry before the message (its `parentUuid`; a prompt the daemon logged itself under its own id is
  found by its words, the same occurrence of the same text) into a new session titled "<title> (rewound)".
  No claude process runs and nothing is typed into a terminal, so a session that's working or open on the
  Mac is never touched, and the original stays as it was. The new session is the daemon's own (a shared
  terminal resumes it with its first prompt where the Mac runs them, else the SDK). Files Claude changed
  stay as they are; Claude Code's file checkpoints aren't copied. The first message has nothing before it:
  409 `first_message`.

## Per-model weeks (the usage probe)

Claude Code's status line reports only the 5-hour window and the all-models week (2.1.302:
`rate_limits.five_hour`, `seven_day`). The weeks some models have of their own show only on its
`/usage` screen: "Current week (Sonnet only)" (Max and Team plans) and one "Current week (<model>)"
per model with a limit of its own, such as Fable 5.1. The daemon reads them there
(`daemon/src/usage-probe.ts`, `usage-screen.ts`) into `Usage.models`.

The same screen's first two rows, "Current session" (the 5-hour window) and "Current week (all
models)", are official readings too. A status line only refreshes while its session's main agent
talks to Claude, so with agents at work in the background, or the main session waiting on them, it
brings no readings and the spend pace (`Usage.rate`) would go flat; the probe's looks keep it fed.

- **The probe** starts Claude Code in a hidden shared terminal (100 × 50) in a folder of its own,
  `<support>/usage-probe`, with `--settings '{"disableAllHooks":true}' --strict-mcp-config` and a
  new `--session-id`: no hooks or status line (nothing reaches any phone), no MCP servers (a
  plugin's server, such as a Telegram bot, isn't started a second time). Its environment adds
  `DISABLE_AUTOUPDATER=1`, `DISABLE_TELEMETRY=1`, `DISABLE_ERROR_REPORTING=1` and
  `LANG`/`LC_ALL=en_US.UTF-8` (no update download killed seconds later; the screen in English). It says yes to "trust this
  folder?" (its own folder), waits for the input box (30 s; any other dialog, and it gives up
  without pressing anything), types `/usage` and Enter, reads the screen until the rows are there
  and it no longer says "Loading usage data…" (20 s; "Refreshing…" over the last numbers is waited
  out for 8 s, then those count), presses Esc and ends the terminal. Nothing is sent to the model,
  no login token is read or passed, and the only thing typed is `/usage`.
- **Hidden:** the folder holds a marker file (`.hypernova-hidden`), and a terminal, a registry
  entry (`~/.claude/sessions/<pid>.json`) or a transcript whose folder has it is never a session,
  in the list, History or `hn ls`, whichever daemon sees it. After each look the probe **deletes
  its own transcript**: exactly `<Claude config folder>/projects/<scratch folder, every character
  but letters and digits as "-">/<its session id>.jsonl` (`CLAUDE_CONFIG_DIR` respected; the path
  is checked to be that file in that folder, else nothing is deleted), and the project folder if
  the probe made it and it's empty. Nothing else of Claude Code's is touched. Typing `/usage`
  also adds a line to Claude Code's prompt history for that folder only.
- **Reading:** each row is a title, a meter with "N% used" and "Resets <when> (<time zone>)"
  (narrower than 62 columns, the reset joins the title's line). Every row is read (credits too);
  the model weeks are the "Current week (…)" rows other than "(all models)", named as the plan
  names the model ("Fable", or "Fable 5.1"; "Sonnet only" becomes "Sonnet"). The reset is read in
  the zone it names: "7pm", "6:20am" (the next one), "Oct 21, 10am", "Jan 2, 2027, 10am" (a date
  without a year: the year nearest now), and, should a version write them so, "Thu 10am" (the next
  Thursday), "Thu, Oct 22, 10am" and "Oct 21 at 10am", and other locales' "19:00", "21 Oct" and
  "21 Oct, 19:00". In the same window, a screen reading less than a point below the one known is
  that same reading (the screen floors the percent, a status line doesn't): the meter doesn't tick
  back; for the pace a floored percent counts as its middle (41 → 41.5). 2.1.302 floors the percent and gives the
  reset to the minute (the session's as a time within a day, the weeks' with their date).
- **The 5-hour window and the week:** a look that read the screen with its numbers freshly fetched
  (not Claude Code's last ones under a "Refreshing…" that never ended) offers both rows as official
  readings true as of the moment the screen was read, merged with the status lines' by the same
  rules (a later reset wins; within a window the newest reading, even lower; a limit Claude Code
  hit stands until a newer reading, and while the account is still limited in that window, until
  the reset). A reset within 2 minutes of the window's known one keeps the known one (a status
  line's is to the second); a row without a reset, or with one no such window could have (more than
  a window's length ahead), takes the known window's while it hasn't ended, else isn't offered.
  `Usage.source` is `"official"` and `observedAt` moves with them like a status line's.
- **No 5-hour window** (2026-10-31: the owner's Max account's screen lost its "Current session"
  row; DECISIONS.md): a look whose screen has the all-models week and no "Current session" row, or
  a status line whose `rate_limits` has `seven_day` and no `five_hour`, says there is none:
  `Usage.fiveHour` becomes `null` (it was the last reading, rolled into empty windows for ever),
  unless the account is stopped at a 5-hour limit now. A 5-hour reading no newer than that one
  (an idle session's status line still carrying the old window) is passed over; a newer one brings
  the window back. A weekly limit Claude Code hit, which carries only the week, says nothing about
  it. The log line says "no 5-hour window".
- **When:** while any client is connected (the Mac app counts): every 2 minutes while a session on
  the subscription works or its agents run (what `Usage.rate` goes by; sessions on other providers
  don't spend these windows; not at a 5-hour or weekly limit, whose meter stands full with no
  pace), so three looks in four minutes make a pace; idle, every 15 minutes while a phone is
  connected, else hourly (the Mac app's own feed is always there, and every look starts
  Claude Code). The wait doubles after each look that couldn't read the screen (up to 4 hours)
  until one does; after 3 in a row `Usage.probe` says so (`ok: false`, `since`) and `modelsStale`
  is true; on
  `POST /v1/usage/refresh` (the phone asks when its usage opens: the sun's jump to the usage card,
  or the chat's usage breakdown; at most once every 5 minutes, without waiting for the answer, which
  comes as `usage` anyway); and 5 s after the account becomes `limited` (a new episode). Never while
  the account is signed out, without a plan or on hold, nor two at once. The probe's own Claude
  Code never counts as working: its folder is hidden, so it is never a session. A look takes about
  4.5 s and 2.4 s of CPU, nearly all of it Claude Code starting (2.1 s; its holder 0.15 s, the
  helpers it runs at start 0.2 s, the daemon under 0.1 s), measured 2026-10-18 on the owner's Mac
  with Claude Code 2.1.302: every 2 minutes, about 2% of one core while something works. Each look
  adds one `/usage` line to Claude Code's prompt history for the probe's folder.
- **The result:** a look that read the screen replaces `models` whole (a row gone, its week gone)
  and sends `usage` (with `modelsObservedAt`, when they were read, and `modelsStale: false`); one
  that couldn't leaves them as they were. A screen that is there but says "Per-model breakdown
  unavailable (…)" (rate limited) or "Partial usage data" doesn't wipe them: the last good rows and
  their `modelsObservedAt` stay, `modelsStale` is true, and the next look is in 2 minutes instead
  of 15. A week whose reset
  passes reads 0% with the next reset (a week on), like `sevenDay`, until the next look.
- **Status-line files** (`<support>/statusline/<session>.json`, one per session the status line
  ever reported) of sessions not seen for 7 days are deleted at start and every 6 hours.
- **Clients** show each model week as one more meter beside the 5-hour window and the week (the
  sun and its meters stay on the 5-hour window), and the glasses name one only when it is the
  tightest limit. A model limit Claude Code hits ("Fable limit", `Account.limit.window` `"model"`)
  fills that model's week on the phone and the glasses at once, until the limit's reset, without
  waiting for the next look; the limit and the week are matched by their first word. A daemon
  without `models` means none.
- **A real screen for the tests:** `daemon/test/fixtures/usage-screens/` holds screens drawn the way
  2.1.302 lays out the tab; a photograph of the real one belongs in `max-fable.txt`, taken with
  hooks off in a scratch folder, where nothing calls the model.

## Read aloud

Replies read aloud, from a message's long-press menu on the phone. The Mac makes the voice with its
own voices (`say`); the Hypernova app on the phone plays it natively (`readaloud.ts`). A computer
without `say` (a Linux server) has no voice of its own (`unavailable.macVoice`): the phone then reads
with its own text-to-speech. (Soniox's voices were removed on 2026-10-30.)

**Which voice** (`Speech`, in the snapshot as `speech`, and `{t:"speech"}` on each change; kept in
`<support>/speech.json`):

```
Speech = { engine: "mac", using: "mac", macVoice: string | null, rate }
```

- `engine` and `using` are always `"mac"` (kept for clients that read them). `macVoice` is the Mac's
  voice (null: the system voice). `rate` 0.5–2; the Mac's voice gets `round(185 × rate)` words a
  minute.
- `POST /v1/speech/settings {engine?, macVoice?, rate?}`: a Mac voice is checked against `say`'s.
  400 `bad_engine` (anything but `"mac"`), `bad_voice`, `bad_rate` (not a number; out of range is
  clamped). An older file's Soniox fields are not read, and go with the next save.
- `GET /v1/speech/voices` → `{engine: "mac", voices: [{id, name, lang}]}` from `say -v '?'` (`lang`
  like `en-US`), listed once per daemon run (a listing that failed is tried again; none without
  `say`). A line is `Samantha            en_US    # Hello! …`: the language is the last word before
  `#` and the name is everything before it (long names such as `Eddy (English (US))` have a single
  space there).

**The Mac's voice**: `/usr/bin/say [-v <voice>] -r <wpm> -o <support>/tmp/say-<random>.<m4a|wav>`,
no shell, 40 s at most, renders to the file and plays nothing on the Mac: AAC in M4A for a whole
clip (`--file-format=m4af --data-format=aac`), 24 kHz 16-bit PCM for a play (`--file-format=WAVE
--data-format=LEI16@24000`, read from its `data` chunk). The text goes in on its standard input,
never as an argument, so it doesn't show in the process list; `[[` becomes `[ [` (it opens one of
`say`'s embedded commands). The temp folder is 0700 and the file is deleted once read (leftovers at
start-up too). A play's text is read in pieces cut after a sentence (the first at most 400
characters, so the voice starts at once, the rest at most 2000).

**A whole clip**, `POST /v1/speech {text, voice?, rate?}` (the phone's older way): 200 with the bytes
of the whole clip, `audio/mp4` (AAC in M4A), `X-Speech-Engine: mac`.
- `text`: plain text, at most 8000 characters (as JavaScript counts them); longer → 413 `too_long`,
  blank → 400 `empty`.
- `voice`: a Mac voice's `id` (unknown → the system voice); `rate` other than 1 is used instead of the
  setting's.
- One clip at a time per paired device: a new request stops the one being made, which answers 409
  `replaced`. A client that stops waiting stops it too. Other errors: 502 `speech_failed`, 501
  `no_voice` (no `say`).

**Plays**: speech the Mac makes and the Hypernova app on the phone streams and plays.

```
Play = { id, sessionId: string | null, source: "phone", engine: "mac",
         sampleRate: 24000, format: "pcm_s16le", of: number }   // of: the text's length
```

- `POST /v1/speech/play {text, sessionId?, to?: "me"}`, up to 40,000 characters: the phone that asks
  plays it (its Read aloud and its Test); the Mac app can't (400 `bad_target`). One play at a time
  per phone: a new one ends the old (`stopped`, `replaced`). The answer: `{play: Play & {to: {id,
  name}}}`.
- `GET /v1/speech/plays/<id>/audio` (the phone playing it, or the Mac app): `audio/pcm`,
  `X-Sample-Rate: 24000`, `X-Speech-Engine`; what's made so far at once, the rest as it's made, the
  end when all is made. It waits for the first audio: a failure before any → 502 `speech_failed`
  (501 `no_voice`); a play already stopped → 410 `play_over`; 404 `no_play`.
- **The phone reports** on its feed: `{t:"speech.progress", id, state: "started" | "playing" |
  "ended" | "stopped" | "failed", ms, level, why?}`: started when the voice starts, then the position
  (`ms` played) and the loudness of what plays now (`level` 0..1) every 150 ms or so; `ended` once
  the speaker played the last of it; `stopped` (`why`: `phone` for its Stop, the notification or a
  headset's button; `focus` when a call or another player takes the audio; `unplugged` for
  headphones; `network`); `failed` when it never played (`focus`, `http_<status>`, `unreachable`).
  Only the phone playing a play is heard about it.
- `POST /v1/speech/plays/<id>/stop` (the phone playing it, or the Mac app): the phone gets
  `{t:"speech.stop", id}` unless it stopped it itself, and the making stops.
- **Watchdogs**: no `started` within 12 s of the first audio (24 s while none is made) → `failed`
  `no_start` and the phone is told to stop; no word from a playing phone for 15 s → `stopped`
  `lost`; its feed gone and not back within 4 s → `stopped` `lost`. A play is forgotten a minute
  after it ends.
- **The Mac app** hears `{t:"speaking", speaking: {id, sessionId, source, state, level, engine}}`
  on its feed alone, every 120 ms at most while a play goes, and draws the speaking wave on that
  session's row; `ended`, `stopped` or `failed` takes it away.

**Privacy**: the text is never logged or written down; the log has one line a play (`speech play
phone mac 9.2s ended`: the source, the engine, how long, how it ended). Recent reads stay in memory
only, never on disk, by a hash of the voice, rate and text, so the same reply read again starts at
once: the Mac's last 6 whole clips, and the last 6 plays as PCM, 32 MB at most each, the oldest
dropped first, every one dropped exactly 10 minutes after it was made, on a timer of its own (which
never keeps the daemon running).

## Reading transcripts

Events for sessions that run in Claude Code itself (shared terminals, terminals) come from
its transcript, `~/.claude/projects/<folder>/<session id>.jsonl`. Event ids are the entries'
`uuid`s (a tool's is its `tool_use` id, Claude's text `t:<message id>`, a queued prompt's its
`source_uuid`), so reading a file again changes nothing.

- **Hidden**: meta entries (`isMeta`: the caveat before a command, a skill's body or a prompt
  command's expanded text, skills the model loads itself), the summary after compaction,
  system reminders, task notifications (read for how agents ended; see Agents and workflows),
  "[Request interrupted…" and subagents' entries (`isSidechain`, user, assistant and system
  alike; Claude Code 2.1.29x writes those to each agent's own file, read on their own).
- **An Agent or Workflow call** launched in the background (its result's `status` is
  `async_launched` or `remote_launched`) stays `running` until its agent or workflow ends, with no output (Claude Code's
  launch note is internal); then `done` (`error` when it failed, with its summary as output). A
  Workflow call's `summary` is the workflow's name.
- **Slash commands** are recorded as a record of tags, `<command-name>/effort</command-name>`,
  `<command-message>` and `<command-args>` (also `<skill-format>` and `<command-contents>`),
  in a user entry or a `system` entry of subtype `local_command` (/rename, /color…). The
  daemon reads a record the way Claude Code does: from the entry's last text block (pictures
  pasted with a command come as blocks of their own, and are dropped with it); each tag at
  most once, in any order, only whitespace between them; a body ends at its first closing
  tag, except `<command-args>` when last, which runs to the end; `<skill-format>`, if there,
  is `true`. Text that isn't a whole record, such as tags the owner pasted into a prompt, is
  an ordinary prompt shown as typed; a whole record whose name has no "/" is the model
  loading a skill itself, and is hidden. Each command becomes a `user` event whose `text` is
  the command as typed, name and args (`"/effort ultracode"`), so the phone recognises what
  it sent the same way as a prompt, plus `command`. `kind` is `local` for Claude Code's own
  (the record starts with the name) and `prompt` for prompt and plugin commands (it starts
  with the message, and Claude answers). Args are recorded as typed, tokens included:
  clients don't log them, and titles mask them (below).
- **Skills**: Claude Code follows a skill's record with a meta user entry whose parent is the
  record and whose text starts "Base directory for this skill:". When it's read, the
  command's `kind` becomes `skill` and its event is sent again (same `id`). Skills the model
  runs itself through its tool have a tool use as their source, and change nothing.
- **Their output** (`<local-command-stdout>`, `<local-command-stderr>`, a user or system entry
  whose parent is the command) is a `notice` with `command` set to the command's event id:
  `error` when there's stderr or the output starts "Command failed", else `info`; colour
  codes stripped, the last 8000 characters of long output. Empty output makes no event, and
  after a manual /compact the output repeating the summary is skipped. Some commands leave
  nothing at all: a picker closed with Esc, a full-screen view, and an unknown command,
  which only leaves a warning (below). Clients shouldn't wait for an echo forever.
- **"!" mode**: a last text block that is exactly `<bash-input>ls</bash-input>` is a `user`
  event with text `"!ls"` and `command: {name: "!", args: "ls", kind: "bash"}` (with more
  text after it, it's a prompt). Its output (`<bash-stdout>`, `<bash-stderr>`,
  `<bash-exit-code>`) is a notice with `command`: stdout, then stderr and `exit N` (when not
  0) on their own lines, HTML entities decoded, the last 4000 characters of long output.
  `error` when it failed or exited non-zero, else `info`.
- **Pasted text**: Claude Code 2.1.299 logs a long or multi-line paste (the phone's prompts
  too, which the daemon types as pastes) in full, wrapped in tags with an id of 4 hex
  characters, the same at both ends, in user entries, queue entries and queued prompts alike:
  `typed\n\n<pasted_content id="d6ee">\n…the paste…\n</pasted_content id="d6ee">\n\nmore
  typed`. A prompt may hold several, with typed text and @-mentions around them, and one that
  is only a paste starts with the two line breaks (a queue entry logs it trimmed). The daemon
  takes the tags off before reading anything else, as Claude Code reads them back: an opening
  tag ending its line, the paste up to the first closing tag with the same id, up to two line
  breaks either side belonging to the tags. A paste with line breaks then sits on lines of its
  own, and a one-line paste stays inline where spaces set it apart from the typed text. Tags
  the owner typed or pasted himself are escaped by Claude Code (`<\pasted_content`) and are
  left as they are, as is anything not well formed (no closing tag, ids that differ). Events,
  `session.queue`, `unsent` and titles carry the text without tags, so the phone matches its
  copy of a long prompt by text.
- **Prompts typed while Claude works** wait in Claude Code's queue. Claude gets one when the
  step it's on finishes (with that step's tool results), or, when the turn ends first, as the
  next turn.
- **`queue-operation` entries** log each move of that queue at once (`operation`, `timestamp`,
  `content`, no uuid), while a step's own lines are written only when the step ends, sometimes
  minutes later (after a compaction, say). So these keep `session.queue` current:
  - `enqueue` (`content`: the prompt as typed, @-mentions included, pasted text in its tags; a
    "!" line without its "!"; pasted pictures not at all): it joins the queue. Content starting
    with `<` (task notifications, other sessions' messages) isn't the owner's and is left out,
    unless that is a paste's opening tag. There is no `content` for a prompt queued while no
    model turn runs (behind a running "!" line, as a turn ends, as a session starts) nor for an
    SDK prompt. Such a prompt still holds its place in the queue: a phone send being typed into
    that terminal lends it its text and `clientId` (below), so it shows in `session.queue`; one
    typed on the Mac isn't shown until it runs. A `remove` or a `popAll` of it alone names its
    text, and a prompt from the queue whose text matches no entry is the oldest of these (see
    `dequeue`).
  - `remove` with `reason: "absorbed_mid_turn"` (`content`, `commandUuid`): Claude has it. Its
    `user` event comes from the `attachment` entry of type `queued_command` written after the
    step's last lines (`prompt`: text or blocks, `source_uuid` = `commandUuid`, `origin.kind:
    "human"`, `commandMode: "prompt"`), so it sits after what Claude wrote before it, lines
    often written just after the `remove`. When they're held up (after a compaction, for
    minutes), the daemon shows it from the queue entry 3 s on, with that id, and the attachment
    adds nothing. It stays in `session.queue` until its event is sent (3 s at most), so it
    never vanishes in between.
    With `cleared_on_cancel` or `dropped_by_hook` the prompt was never sent: a `warn` notice says
    so, with `unsent`. Other reasons are for agents and tasks.
  - `dequeue` (nothing else): the oldest prompt starts the next turn; it leaves the queue with
    its user entry, which follows (several dequeues come back to back for task notifications,
    then their entries). That entry's `promptSource` doesn't say it came from the queue: Claude
    Code 2.1 logs the owner's as `"typed"` or none (`"queued"` exists in its code but hasn't been
    seen), task notifications and its own prompts as `"system"`, an SDK prompt as `"sdk"`. So the
    daemon counts dequeues, and the next prompt or command uses one up (so does a hidden entry
    with a `promptSource`: a task notification or Claude Code's own prompt). A prompt or command
    that used one up and matches no entry by text takes the oldest entry without text (a command
    at session start, say), never one with other text; a hidden one takes none, as its own
    enqueue had content as a rule, which the queue left out. Its pictures are `[image]` there,
    which the matching leaves aside.
  - `popAll` (`content`): back in the input box for the owner to edit (the Up arrow, or Esc
    while no turn runs); an `info` notice says so, with `unsent`. Entries without text count only
    when the ones with text don't make up all of `content`.

  Entries are matched by text, and the first of two identical prompts goes first. A prompt the
  phone sent claims its entry, and its `user` event carries the send's `clientId`: the oldest
  prompt the daemon typed into that terminal in the last minute with the same text (whitespace
  and a leading "!" aside), or, for an entry without text, the oldest one noted at or before the
  entry's own time (the daemon notes a send before typing it, and Claude Code stamps the entry
  as Enter goes in, on the same clock), which lends that entry its text and `clientId`. A send
  that ran at once or failed is forgotten, and a queued one can be claimed only
  for 5 s after the holder answered (its entry is logged as Enter goes in), so a prompt typed on
  the Mac later never takes its name. A prompt that started a turn is no queue entry's: only its
  own user entry claims it, within 20 s, and only one Claude Code marks `promptRewritten` (a hook
  of its own changed the text on the way in, such as a spellcheck plugin's "clip board" to
  "clipboard", so the phone can't match it by its text): the oldest typed at or before it. A loan is a guess: when the send's own entry is then
  logged with its text (another prompt was queued without text as it was typed, a task
  notification say, and started a turn), the text and `clientId` move to it, and the borrower
  is an entry without text again, unseen. Either way each prompt leaves by its text, so the
  phone's event gets its `clientId` and a prompt typed on the Mac none. A "!" line claimed that
  way shows in `session.queue` with its "!", so it draws as one; one typed on the Mac shows
  without it until it runs. A queued prompt's id is its `source_uuid`, which a user entry for
  the same prompt carries too, so it never shows twice, whichever comes first. Claude Code holds
  no queue while it's idle: what's left once a session has been idle and quiet for 3 s, or when
  its history is first read while it's idle, is dropped (its way out was in a shape the daemon
  doesn't know). Queued messages that aren't the owner's (task notifications, other sessions,
  `isMeta`) are left out.
- **Tool output**: a `Read` shows only its line count ("57 lines"), except a read of a background
  command's output file (`…/tasks/<id>.output`), which keeps the last 4 KB of what was read (a dev
  server's address, say). A `Bash` command run in the background has no output yet
  (`toolUseResult.stdout` is empty, `backgroundTaskId` set): its `output` is the line of its result
  naming its ID and output file ("Command running in background with ID: b8f…. Output is being
  written to: …"), after what it printed so far when it was moved there after a timeout.
- **An MCP call's `summary`** (a tool named `mcp__<server>__<tool>`, Claude Code's or Codex's) is
  what it was called with, not its name again (`normalize.ts`, `eventSummary`): its first argument
  among `entity_id`, `query`, `url`, `path`, `file_path`, `name`, `id`, `title` (in that order; a
  number, or a list's first item with `+N` for the rest, counts there), else its first other
  string argument of up to 120 characters on one line. Never one whose key reads like a secret
  (`token`, `passw…`, `secret`, `key`, `auth…`, `cookie`, `credential`, `bearer`, `signature`,
  `private`, in any case), never a `data:` URL or a blob (40 or more base64 characters with a
  digit and no spaces); what's left is its first line, spaces tidied, masked as titles are (below)
  and cut to 80 characters with "…". Nothing qualifies: the server's name
  (`"home-assistant"`). So `mcp__home-assistant__ha_get_state {entity_id: "light.kitchen"}` is
  `"light.kitchen"`, where daemons before 2026-11-01 sent `"home-assistant · ha_get_state"`, which
  the phone still reads (it shows neither that nor the server alone as the call's target).
  Approvals' `summary` (`"mcp__…: home-assistant · ha_get_state"`) and agents' activity are as before.
- **Warnings**: `system` entries of subtype `informational` at level `warning` or `error`
  ("Unknown command: /effot. Did you mean /effort?") are `warn` or `error` notices. Claude Code's
  usage-limit lines are at level `notice` ("Usage limit reached · continuing automatically at 2am
  · esc to cancel"): those alone are shown, as `info` notices (see Account); other notices and
  levels are left out. A `compact_boundary` is the notice "Conversation compacted".
- **API errors**: an `assistant` entry with `isApiErrorMessage` (model `"<synthetic>"`) is Claude
  Code's error, not Claude's words: a `notice` (`warn` for a limit, else `error`) with `blocker`
  when it stopped Claude, never an `assistant` event and never `lastText` (see Account).
- **Titles**: a custom title (/rename) wins, then the AI's title, then the first prompt: a
  plain prompt, a skill or prompt command given args (`"/hyper-cosmos make a page"`) or a "!"
  command (`"! ls"`). Other commands (`"/effort high"`, a bare `"/review"`) name a session only
  when it has nothing else. History reads the same from the start and end of the file.
  A title from a prompt or command (here, and a Hypernova session's first prompt) has
  anything key-like masked as `•••`: Telegram bot tokens, `sk-…` API keys, GitHub
  (`ghp_…`, `github_pat_…`), Slack (`xox…-`) and AWS (`AKIA…`) tokens, runs of 32 or more
  hex or base64 characters that mix case and digits like random text, a URL's password, in a curl
  or wget command (up to an unquoted `;`, `&`, `|` or line break; a quoted string of up to 600
  characters counts whole) the value of `-u` (`-u user:pass`, `-su …`, `--user=…`; only flags
  that take no value share its dash, so `-XPUT` isn't one), `-U`, `--proxy-user`,
  `--oauth2-bearer` and wget's `--user`, whatever it is (`docker run -u 1000:1000` stays), and a
  credential after a key's name
  (`TOKEN=`, `X-Api-Key:`, `"api_key": `, `--password `, `Authorization: `; a name holds TOKEN,
  SECRET, PASSWORD, PASSWD, API_KEY or AUTHORIZATION, or AUTH joined to more by `_` or `-`
  (`AUTH_TOKEN`, `X-Auth-Key`, `BASIC_AUTH`), never a bare "auth") or a bare `Bearer `. The name stays. A credential after a
  name is 8 or more characters with a digit, or 16 or more token characters (letters, digits,
  `._~+/=-`) mixing upper and lower case; after an Authorization scheme that follows the name
  (Bearer, Basic, Token, Bot, Digest, OAuth, ApiKey: `Authorization: Token …`, which stays in
  view), any 8 or more token characters; after a bare `Bearer`, 16 or more token characters with
  a digit or both cases. After a name ending in the plural "tokens", a number (digits with `,`,
  `.` or `_` between them) is a count, not a credential (`output_tokens: 12,345,678` stays).
  Curly quotes (“ ” ‘ ’) are never part of a value: an opening one right after the `=` or `:`
  stays with the name (`PASSWORD=“•••”`), and a sentence's closing punctuation stays. So prose
  keeps its words ("the auth flow", "Bearer of bad news", "password reset page", "Authorization:
  Bearer header missing"). Every repeat in the rules after a name, a URL, curl or `-u` is
  bounded, so masking stays linear on any line. Agents' reports are masked the same way, and so
  is the Android widget (one rule; WidgetModel.kt, Secrets). The events keep the text as typed.
  `lastText` is only ever Claude's text.

Agent SDK sessions (no transcript reading) get the same `command` on the `user` event for
what the phone sent: `"/name args"` is `local` when the name is one of Claude Code's own (from
the commands list, once `GET /v1/commands` has fetched it), else `prompt`; `"!line"` is
`bash`. One claude process runs one prompt, so a prompt sent while one runs waits in
`session.queue` for the whole reply, and its `user` event comes when it starts (with its
`clientId`). Stop drops what waits rather than running it next, as a terminal would: one
notice, "Stopped. 1 queued message wasn’t sent.", names them in `unsent`, and the phone puts
their text back in its message box.

## Agents and workflows

A session's main agent starts agents (the Agent tool, almost always in the background) and
workflows of them (the Workflow tool: a script that runs agents in phases). The daemon follows
them in `daemon/src/agents.ts`; the phone shows each under the call that started it.

- **Where they are.** Claude Code 2.1.29x writes each agent's turns to a file of its own,
  `~/.claude/projects/<folder>/<session id>/subagents/agent-<id>.jsonl`, next to a small
  `agent-<id>.meta.json` written as it starts (`agentType`, `description`, `toolUseId`,
  `parentAgentId` when an agent started it, `workflowPhase`). A workflow's agents sit in
  `subagents/workflows/<run id>/` (an agent's file not in either is looked for further down the
  session's own subagents folder, up to 6 folders down, links not followed; the folder is named as
  the session's transcript is), next to a `journal.jsonl` (`started` with label and phase, then
  `result` or `failed`); the run's summary is written to `<session id>/workflows/<run id>.json` when
  it ends (status, times, phases, totals and each agent's state, tokens and tool calls). None of it
  is documented, so every field is optional and anything unreadable leaves an agent running or
  stopped rather than failing.
- **Links** come from the main transcript: an Agent call's result names its agent (`agentId`), a
  Workflow call's its run (`runId`, `taskId`, `workflowName`), `SendMessage`'s result an agent it
  woke (`resumedAgentId`), TaskStop's a workflow or agent it stopped (its result's `task_id`, else
  the call's own `task_id`). Each ends with a `<task-notification>`
  (task id, tool use id, `completed`/`failed`/`killed`/`stopped`, usage), logged as a queue entry, a
  hidden user entry and a queued command; each counts once.
- **What an agent does now** comes from the PreToolUse hooks every subagent already sends
  (`agent_id`): no file is read for it. An `agent_id` the daemon doesn't know yet counts only once
  its meta file is found in the session's subagents folder (read then, for its label): Claude Code's
  own helpers (memory upkeep, summaries, prompt suggestions: forks of the main conversation) send
  these hooks too, under an id that names no file (in a `claude agents` job, with its main agent's
  type, `claude`), and get no card. The main turn's Stop lists background tasks still going.
  Sessions run through the Agent SDK report the same through its `task_*` messages.
- **The folders** of sessions that are going, ended in the last 15 minutes or still have agents
  or a workflow running are looked at every 2 s: a meta file, journal or run summary is read only
  when its folder or size changed, and only running agents' own files are followed (from their last
  256 KB), for their activity, tokens and report (an agent's `SubagentHandback` message, a workflow
  agent's structured output). An agent whose file goes quiet (10 minutes in a session that is
  going, 2 once it has ended) is done if its last word was its report, else stopped; one waiting on
  a tool or an approval in a live session is still at work (for up to 2 hours).
- **Waiting on its own background work.** An agent that starts a background command and waits for it
  ends its turn: SubagentStop fires, Claude Code marks it `completed` with the command keeping it
  alive, and wakes it when the command ends (in the same file). It hasn't finished, so its `status`
  stays `running`, and `activity` says why: "Waiting for its background command" ("… its 2 background
  commands", "Waiting for the agents it started", or "Waiting for its background work" when mixed or
  when only Claude Code's word is to go on). No new status and no change on the wire: an older app
  shows a running agent with that line, and everything that counts running agents counts it
  (`agentsRunning`, Home, the widget, Stay awake, the server's own update).
  - **What counts as waiting.** Read from the agent's own file (Claude Code 2.1.316's shapes): its
    turn has ended (its last reply's `stop_reason: "end_turn"`, the SubagentStop hook or its
    `hook_success` line), it hasn't handed back a report, it's a background agent, and work it started
    hasn't ended. Started: a tool result's `toolUseResult.backgroundTaskId` (`run_in_background`, or a
    foreground command the timeout moved there), Monitor's `{taskId, timeoutMs, persistent}`, or
    `status: "async_launched"` / `"remote_launched"` (`agentId` or `taskId`: agents and workflows of its
    own). Ended: a `<task-notification>` for that id with a `<status>` (an entry with
    `origin.kind: "task-notification"`, Claude Code's notice before it and several in one entry when
    they came together, or a `queued_command` attachment with `commandMode: "task-notification"`; not
    a prompt that only quotes one, and a Monitor's event has no status so isn't its end); TaskStop's
    result on that id, or "not running"; or for a shell, a Stop or SubagentStop `background_tasks` that
    no longer names it (work that began in the last 2 s is believed anyway, as the list came first).
    The list's contents are read from the 2.1.316 binary, not from a recorded hook: the session's
    tasks that are `running` or `pending` and not foreground, so a subagent's shells are named. Were
    that wrong, Claude Code's word below still holds the agent.
  - **Claude Code's word on it.** The main transcript gets a `<task-notification>` with
    `<status>completed</status>` and `<summary>Agent "…" is waiting on its own background work</summary>`
    (and a `<note>`). The words are the only signal there: the real end says `completed` too, with
    "… finished". That word doesn't end the agent or its call; it also holds an agent whose file
    shows nothing (its command started before the 256 KB followed) and brings back one a SubagentStop
    just took as done. A wording the daemon doesn't know ends it unless its file shows it waiting.
    Each wait and the real end each count, told apart by their `duration_ms`.
  - **How it ends.** Woken, it's simply at work again (the notification in its file, then its next
    hook). Its report handed back ends it `done`, at once and whatever it left running. Its turn ending
    with nothing outstanding ends it `done`, as usual, and so does Claude Code's "finished".
  - **The main turn's Stop** doesn't end it: `background_tasks` doesn't name a waiting agent, since
    Claude Code counts it as completed, only its shells (the same reading of the binary).
  - **The cap.** A waiting agent whose file has had no write for 2 hours ends, `done` with its report
    in hand or last words, `stopped` if Claude Code said it hadn't reported yet (it then has no
    `result`). A Monitor due to run longer extends that to its end plus 10 minutes, a persistent one
    to 12 hours at most. Its next hook or notification still brings it back or says how it really
    ended, so a wrong end corrects itself, and the count can't stay up for good. Its session ending ends
    it as for any agent; a limit on its session doesn't by itself (it needs no model while it waits).
  - **A daemon restart** re-reads all this from the transcripts: the launch and the end from its file,
    Claude Code's words from the main transcript, in order, and one over the cap ends on sight.
    The daemon logs one line per agent it ends for a reason that could look wrong later (the cap, its
    file going quiet, a Stop no longer naming it), and per hook id it leaves out for having no meta file.
- **Tokens** of a running agent are counted from its file as Claude Code 2.1.31x counts them for its
  own footer (the number beside each agent there): the context its latest reply read
  (`input_tokens + cache_creation_input_tokens + cache_read_input_tokens`) plus the `output_tokens` of
  every reply. A reply is logged once per content block with the same `message.id`, so it counts
  once (its largest output); a line marked `isUnmetered` doesn't count. Claude Code's footer also adds
  an estimate of the reply streaming now, which the file doesn't have. An agent followed from its
  last 256 KB counts only the output in them. When it ends, Claude Code's own total (its task
  notification's `subagent_tokens`, the SDK's `total_tokens`, a run summary's `tokens`) replaces it.
  How long it has run is the phone's own: now (or `endedAt`) less `startedAt`. A workflow's `tokens`
  while it runs is the sum of its agents' (sent again only when one of them changes status, so a
  phone adds up its agents' newer counts itself).
- **Ends nobody announces.** When Claude Code quits (a SessionEnd for any reason but `clear` or
  `resume`, after which the same process goes on; or its process gone: missing from Claude Code's
  registry and no longer running, so a read that catches its file mid-write ends nothing), the
  agents and workflows it ran in the background end with it: the folders are read once more (a
  run summary written as it quit still counts), then a running agent ends `done` if its last word
  was its report (its file is read to its end first), else `stopped`, a running workflow ends
  `stopped`, and the calls that started them stop too. A running workflow none of whose agents run
  ends `stopped` once its journal, folder and agents have been still for 2 minutes in an ended
  session (2 hours in a live one). An Agent call whose result is an error or an interruption (Esc)
  ends the agent it ran in the foreground `stopped` (no notification follows), also one whose meta
  file is read only after that. And, so the count never keeps agents that have gone:
  - **TaskStop** on a background agent ends it `stopped` at once.
  - **The main turn's Stop** lists the background tasks still going (`background_tasks`): a
    background agent of the main agent's that the daemon has running and the list no longer names
    has ended, `done` if its file's last word is its report, else `stopped` (a notification later
    still says how it really ended). Not a workflow's agents or one an agent started (the list names
    the main agent's tasks), not one waiting on its own background work, not one that started in the
    last 2 s, and nothing when the Stop carries no list (an older Claude Code).
  - **An API error** as an agent's last line (a 429, the usage limit, an overloaded API: Claude Code
    writes one only after its own retries) ends it `stopped` once its file has been quiet for 2
    minutes, even waiting on a tool.
  - **While its session is stopped** (`blocked`: a limit, signed out, out of credit, the provider
    failing; not a model's own week), its agents silent for a minute end `stopped` with it.
  - **SubagentStop** (an optional hook, see Hooks) ends a running agent `done`, its
    `last_assistant_message` as its report, unless it's waiting on its own background work (see
    above); one already ended keeps how it ended.
  An agent ended by a guess comes back `running` on its next hook or its SendMessage wake-up.
- **Retries and runs started again.** A workflow step started again under the same journal `key`
  is a retry: the earlier attempt ends `stopped` (unless Claude Code had already said how it
  ended), isn't counted in its workflow, and is left out of `…/agents`; its `agent` message carries
  `superseded: true`, and the phone takes it off its board (older apps ignore the field). A
  Workflow call that starts a run again from where it stopped (`resumeFromRunId`: its result names
  the same `runId`) reopens it: `running` again under the new call (the workflow's `toolUseId` and
  `taskId` become the new call's, and the old call stops if it still ran), with the totals Claude
  Code reports next, and its folder read afresh for the agents it starts now. Its journal goes on,
  so the steps that run again are retries. A run summary older than the new call is the earlier
  attempt's and doesn't end it; one written after it does.
- **Foreground agents.** The daemon takes an Agent or Workflow call as gone to the background (the
  main turn has moved on) only once its result says so (`async_launched` or `remote_launched`, from
  the PostToolUse hook, which comes first, or from the transcript). A foreground agent's call holds
  the turn like any tool, so a prompt typed meanwhile is queued.
- **History** (a session the phone opens later) is read once from its meta files, journals and run
  summaries; whole agent transcripts are only read for `…/agents/{agentId}/events`, cached (8, each
  for 10 minutes after it was last asked for) and then followed, so a cursor gets only what
  changed. Agent and run ids are checked (`a` and 16 hex digits, `wf_…`) before any path is made,
  and the file must sit inside the session's own subagents folder.
- **The session** counts running agents in `agentsRunning`, at least 1 while a workflow runs
  (between its phases, or before its first agent's meta file is read, none of its agents is at
  work, and the session is still busy with it). Claude Code stays busy while agents run after the
  main turn ends; the daemon then keeps the session `idle` (the main agent is) until a
  turn starts again (a prompt, a main-agent tool call, or a task notification starting one).
  **The working rule**, the same in every client (the phone's `Session.busy`, its Home seam, card,
  sky and notification, the Mac's menu bar and popover, the widget): a session that hasn't
  ended is *working* when `state` is `working` or `starting`, or `agentsRunning > 0`; `needs_you`
  (an approval open, or a dialog in its terminal) outranks working. So a session whose main agent
  is idle while 2 agents run is working ("2 AGENTS"), not idle, and counts once among the working.
  The phone doesn't notify "Finished" for a turn that ends while agents still run; the
  turn the last agent's report starts does.
- **Approvals** from an agent carry `agentId` and `agentLabel`. A prompt or the main turn's Stop
  doesn't settle them (they're the agent's, still waiting); the session ending does. The phone
  shows one in its session at once but notifies only if it's still waiting 10 s later (agents
  often settle their own, or ask again differently); the main agent's notify at once.
- SubagentStop is installed as an optional hook (see Hooks): an install without it still counts
  as connected, and the daemon adds it to an older install at start. SubagentStart isn't installed
  (the meta file and the first PreToolUse say the same).

## Statistics

How much the agents have worked on this Mac, for the phone's Stats tab (`daemon/src/stats.ts`). Claude
Code's part is read from its own transcripts, so it covers every session, however it was started.
Codex's is counted by the daemon as each turn it follows ends (`daemon/src/codex/stats.ts`), from the
day Codex was turned on for Hypernova. A few numbers only the daemon sees, and it counts those from
the moment it first runs. Every total covers both agents; `byAgent` splits them.

`GET /v1/stats?range=today|week|month|all` (no `range`: `week`; anything else: 400 `bad_range`) →

```ts
Stats = {
  range, from: number, to: number,   // epoch ms: the range's first midnight (all: `since`), and now
  computedAt: number,                // when the transcripts were last looked at
  since: number | null,              // the first day with any work, kept days included
  countingSince: number,             // when this daemon started counting promptsFrom and approvals
  claudeMs: number,                  // each session's busy time, added up (two sessions at once count twice),
                                     // Codex's included (the name is from before Codex)
  wallMs: number,                    // the time any session was busy (overlaps count once)
  agentMs: number,                   // each agent's own busy time, added up
  tokens: {input, output, cacheRead, cacheWrite},
  sessions: number,                  // sessions started in the range
  prompts: number,                   // prompts the owner sent, from anywhere
  promptsFrom: {phone, voice},   // of those, sent through Hypernova (voice: dictated)
  toolCalls: number, tools: [{name, count}],   // most used first: 8, then "Other"; MCP tools are "MCP"
  filesEdited: number,               // distinct files Edit, Write, MultiEdit and NotebookEdit touched
  agents: number,                    // agents started (workflows' included)
  approvals: {total, phone, mac},   // answered through Hypernova
  limitsHit: {fiveHour, sevenDay, model},   // usage limits reached, on the day each began (model: an "Opus limit")
  busiestDay: {day, claudeMs, tokens} | null,       // day: "2026-10-14", the Mac's own calendar
  longestSession: {id, title, project, claudeMs, startedAt} | null,   // its busy time in the range
  streak: number,                    // days in a row with at least a minute of work, to today (or yesterday)
  projects: [{name, path, claudeMs, tokens, sessions}],   // by busy time, 8 at most
  days: [{day, claudeMs, codexMs, wallMs, tokens, sessions, prompts}],   // the last 30 days, oldest first,
                                     // whatever the range; claudeMs both agents', codexMs Codex's share
  byAgent: {                         // the range's work per agent; codex absent where Codex isn't installed
    claude: {ms, tokens, sessions, prompts, toolCalls},
    codex?: {ms, tokens, sessions, prompts, toolCalls}
  }
}
```

- **Codex's busy time** is each turn's own (`durationMs`, at most 6 hours), on the day it started (in
  `wallMs` too, as it is: Codex working alongside Claude counts twice there); its
  tokens are what Codex counted for the turn (input without cached, cached as `cacheRead`, output);
  its prompts are the owner's messages in those turns, from anywhere; its tools are its commands,
  file changes (`Edit`), MCP calls (`MCP`) and searches; a thread counts as a session on the day of its
  first turn. Turns Hypernova didn't follow (Codex used without it) aren't counted.

- **Busy time is a turn's**, from the prompt (or a command, or a task notification) that starts it to
  the last reply or tool result written before the next one starts. A pause of more than 15 minutes
  with nothing written inside a turn isn't counted (a usage limit Claude Code waits out); builds,
  tests and approvals fit inside. A session's busy time is its own turns joined with its agents'
  (`<session>/subagents/agent-*.jsonl`, a workflow's under `subagents/workflows/<run>/`), so a
  session waiting on its agents is busy and one agent alongside the main turn isn't counted twice.
  Time is cut at midnight into days.
- **Tokens** are the `usage` of each assistant message, counted once though Claude Code logs a message
  once per content block, all four kinds (the total is mostly cache reads). A forked session repeats
  its parent's history with the same uuids and message ids: those lines count once, for whichever file
  is older.
- **Prompts** are user entries the owner wrote (a plain prompt, or a skill or prompt command given with
  its args) and prompts typed while Claude worked (`queued_command` attachments); not Claude Code's own
  commands (`/effort`) or their output, "!" lines, interrupts, task notifications or anything with
  `promptSource: "system"`, nor agents' tasks. A session counts on the day of its first line of its
  own; its project is the folder it started in (a worktree under `.claude/worktrees/` counts as its
  repository).
- **What only the daemon sees**, kept per day in `<support>/stats.json`: a prompt or new session sent
  from a paired device (the Mac app's aren't counted,
  as the transcripts have them) and an approval answered through Hypernova (the Mac app's are `mac`).
  A client that dictated the prompt adds `via: "voice"` to `POST /v1/sessions/{id}/prompt` or `POST
  /v1/sessions`, and it counts as `voice` too.
- **Limits reached** come from the transcripts, so they have a history: the reply Claude Code writes
  when a limit stops it (an API error entry, `isApiErrorMessage`, "You've hit your session limit ·
  resets 4pm (Europe/Moscow)", "… weekly limit …", "… Opus limit …", or older versions' "Claude AI
  usage limit reached|<epoch s>"). Every session it stops, and every retry, writes one, so the same
  words (which limit and its reset) within the limit's span (5 hours; a week for weekly and model
  limits) are one limit. Credit and spend caps don't count. The account adds its own episodes with
  `recordLimitHit(window, at, resetsAt)` (`window` as the account names it: `five_hour`, `seven_day`,
  `model`; kept in `stats.json`): one within 30 minutes of a limit of its window already seen, or with
  the same reset, is that limit, so it never counts twice.
- **Cheap**: the transcripts are listed and each is read once, then only what was added (a file's size
  and mtime say whether to look; one that got shorter or was replaced is read again), in 1 MB pieces
  with a yield between them. A line over 64 KB that is a tool's result is not parsed: only its
  timestamp is read. A file keeps only its numbers, per day. The answer is reused for 15 s (a limit
  from the account shows at once). The daemon looks 90 s after it starts, so the phone's first ask is
  quick, then every 6 hours. Measured on this Mac while it built other things: a first read of 1.3 GB
  of transcripts (661 files) took about 3 s, holding the event loop 20 ms at most (99% of waits under
  10 ms); after that a look takes 20 to 60 ms, and the numbers kept take about 1 MB.
- **Finished days are kept**: Claude Code deletes transcripts after 30 days by default, so each day
  before today is also kept in `stats.json` as it was added up, and the longest session ever (the
  6-hourly look keeps them even if nobody opens the statistics). A day whose files are gone (it adds
  up to less than the kept copy) is answered from the copy, so `all` keeps growing. Files edited on
  such days are counted per day, not as distinct files.

## Held prompts

Fork. The phone's message box can always send: a prompt sent while Claude waits on the owner is held
on the daemon and typed in once Claude Code's input box is back (`daemon/src/held.ts`).

- **Asked for**: `POST /v1/sessions/{id}/prompt` with `hold: true` (the phone app; not with `replace`).
  Where it would be 409 `needs_answer` (an approval, a question or a plan is open for the session) or
  `waiting` (a dialog or menu in the terminal instead of the input box), it's held: 200 `{session,
  queued: true, held: true, heldId}`. A prompt sent while others are held for an open session is held
  behind them, so they go in order. One of Claude Code's own commands it runs at once, while Claude
  works (`/btw`, `/status`, `/stop`… `commands.ts`, `isImmediateCommand`, from 2.1.314's command
  table), is never held: typed now, or the route's own 409. Without `hold` (an older app) nothing changes. `clientId` works as for
  any send: a repeat gets the first answer, and the `user` event or queue entry it becomes carries it.
- **Shown**: `session.held` (absent when none), and a `session` message whenever it changes.
- **Sent** oldest first, one at a time, as soon as the session is ready: no approval open for it, no
  `menu`, not `needs_you` or `starting`, and a shared terminal Hypernova holds (or a `claude agents`
  job, through `claude attach`). How the ask was answered doesn't matter (the phone, a notification,
  the terminal, a Codex auto-deny). The daemon looks when an approval is resolved or the session
  changes, and every 3 s. It's typed the way a prompt is (Shared terminals, "Typing a prompt"): while
  Claude works, Claude Code queues it (`session.queue`). Never a fork or a resume.
- **Not taken** (Claude asks again, unsent text in the box, the terminal busy or starting): it stays
  first in line and is tried again 1.5 s later at the soonest. In the box but not sent (`not_seen`,
  `not_sent`): it leaves the line with a notice, `unsent.reason: "returned"`. The conversation went
  on in another session, or the session has been `ended` for 30 s (it may be resumed at once): every
  held prompt goes, with a notice, `unsent.reason: "dropped"`, naming them so the phone takes their
  text back. A session that no longer exists drops them silently.
- **Kept** in `<support>/held.json` (0600) across daemon restarts: `{sessionId: [{id, prompt, at,
  clientId}]}`, `prompt` as Claude Code gets it (attachments as @-mentions first).

## Shared terminals

One **holder** process per session (`daemon/src/term/holder.ts`, started detached
so it outlives the terminal window and daemon restarts). It owns claude's PTY and
keeps the screen in a headless xterm, so anyone joining gets the current screen.

- Files: `<support>/terms/<id>.json` (meta: pid, childPid, cwd, args, origin,
  sessionId, terminal app, viewer counts) and `<id>.sock` (0600; a short
  `/tmp/hypernova-<uid>/` folder when the path would be too long for a socket).
- Frames on the socket: `[type u8][length u32 BE][payload]`; type 1 is raw
  terminal bytes (output to clients, keystrokes from them), type 2 one JSON message.
- Client → holder: `{t:"hello", v:1, role:"tty"|"phone"|"control", cols, rows, snapshot}`,
  `{t:"resize", cols, rows}`, `{t:"paste", text, id, replace?}`, `{t:"mode", mode, id}`, `{t:"keys", data}`,
  `{t:"detach"}` (this window leaves on purpose), `{t:"kill"}`, `{t:"screen"}`,
  `{t:"move", to, text?}` (control only: the conversation went on in holder `to`, see Switching agents;
  the holder tells its windows and phones, then ends).
- Holder → client: `{t:"hello"|"state", cols, rows, title, childPid, tty, phone, started}` (`hello` also
  has `unicode`, see **Widths**),
  `{t:"snapshot", data}` (serialized screen and scrollback, before any newer
  output), `{t:"pasted", id, ok, reason?, text?, queued?}`, `{t:"moded", id, ok, mode?, reason?}`,
  `{t:"moved", to: string | null, text: string | null}` (follow to holder `to`: `hn attach` reconnects
  there and draws its screen, and the daemon's phone relay does the same, so the phone's page just
  gets the new snapshot; `to` null: show `text` and close),
  `{t:"exit", code, signal, note?, fallback?, text?}`
  (`fallback`: claude never started, so `hn run` runs plain claude; `text`, to the control client
  only: the screen as claude left it, once its last output is drawn, wrapped lines joined).
- **Typing a prompt** (`paste`): only into Claude Code's own input box, and only
  when it's empty (dim suggestion text doesn't count). The text goes in as a
  bracketed paste, and Enter follows only once it shows in the box. A dialog or
  menu replaces the input box, so Enter never answers one. Reasons when it
  doesn't: `no_input` (a dialog or menu is showing), `busy_input` (unsent text in
  the box; `text` is that text), `not_started`, `not_seen`, `not_sent` (the box didn't
  empty after Enter). Prompts are typed one at a time.
- **Typing while Claude works**: the input box is still there, under the spinner. Enter queues
  the prompt in Claude Code (the box empties all the same) and `pasted` says `queued: true`;
  Claude gets it when the step it's on finishes (see Reading transcripts). The daemon also
  checks Claude Code's registry itself just before typing, for holders started before they
  could tell, and the prompt route answers `queued` either way, except for a slash command:
  Claude Code runs many at once over the spinner, so the route answers `false` for one, and
  `session.queue` shows the ones it does queue.
- **Text already in the box** stops a prompt, except what's left of the phone's own last
  prompt (Claude Code puts an interrupted prompt back in the box) or when the phone asked
  to `replace` it: then Ctrl+C clears the box first, only while Claude is idle (the footer
  has no "esc to interrupt"), and never on an empty box.
- A holder's `hello` lists what it can do itself, `caps: ["replace", "mode", "queued"]`. For a
  holder without them (started before), the daemon does the same from outside: `screen`
  to read the box, `keys` for Ctrl+C and Shift+Tab, and two screens 0.7 s apart (still =
  idle).
- Reading the screen: the input box is the text between two rules starting with `❯`; the
  lines below it are the owner's status line (if any) and the mode line; Claude is working
  when its session registry (`~/.claude/sessions/<pid>.json`) says `busy`, a spinner
  ("✢ Flambéing…", "⎿ Running…") shows above the box, or output arrived in the last 0.8 s.
- **Permission mode** (`mode`): Shift+Tab until the line under the box shows it
  ("manual mode on" is `default`, then "accept edits on", "plan mode on", "auto mode on",
  "bypass permissions on"). A mode it can't reach leaves the one it had.
- **Size**: the PTY takes the size of whoever used it last, like tmux: a Mac
  window when it attaches, resizes or types; a phone when it types or resizes, or
  when it attaches with no Mac window open. A phone that is only watching shows
  the Mac's screen whole, scaled down. Claude Code redraws on every resize.
- **Closing a window**: a session started in a Mac terminal ends 30 seconds after
  its last window closes, as plain claude would, unless a phone has it open or the
  window detached with Ctrl+\. Sessions started on the phone keep running.
- **Widths**: the holder's headless xterm and the phone's page count a character's columns the same
  way, with `@xterm/addon-unicode-graphemes` (`term.unicode.activeVersion` `15-graphemes`, ambiguous
  characters narrow): an emoji is 2, and so is a whole sequence (❤️, 👨‍👩‍👧, 👋🏽, 🇫🇷). That is as close as
  xterm.js gets to Claude Code's own count (`Bun.stringWidth`), and a snapshot only lines up where
  the two copies agree. A holder's `hello` says `unicode` before its `snapshot`, and the page takes
  those rules. A holder started before this has no `unicode` and keeps xterm's default widths (`6`),
  until its session ends: holders outlive a daemon update, and the page draws its snapshot by the
  rules it was kept by. A holder that couldn't load the add-on says `6` too. A page from before this
  with a newer holder is out by a column or so on lines with emoji until Claude Code redraws them.
  `hn attach` windows draw with whatever widths their own terminal has, as always.
- Terminal queries (device attributes, cursor position) are answered by the
  attached terminal; the holder answers only when nobody is attached.
- Which conversation a holder runs: Claude Code's registry
  (`~/.claude/sessions/<childPid>.json`), so `/clear` and `/resume` inside it move
  the terminal to the new session id.
- **Codex holders** (`meta.agent: "codex"`) run `codex --remote unix://<id>.cx.sock --cd <folder>`: the TUI
  talks to Codex's app-server through the holder's own socket, which passes every frame on as it is
  and reads the thread from the answers to `thread/start`, `thread/resume` and `thread/fork` (not its
  temporary title threads). That thread is the holder's `sessionId` (in its `hello`/`state` and meta
  file), and a new one becomes a Codex session (origin `terminal`) followed once Codex has written it
  down. Prompts from the phone go over the app-server, not typed into the TUI, and show in it live;
  `paste` and `mode` answer `no_input`. A phone-started one accepts Codex's "Trust this folder?".
- Phone-started sessions auto-accept Claude Code's "trust this folder?" prompt
  (the owner picked the folder in Hypernova). A holder that has had no viewer for
  12 hours and no output for 2 ends its session (it stays in History).
- **`hn`** (`<support>/bin/hn`, written by the daemon): `hn run [claude args]`
  (start and attach; exit 97 means "not interactive, run plain claude"),
  `hn run-codex [codex args]` (the same for Codex's TUI: plain `codex`, a prompt, `resume`, `fork` with
  the TUI's options; anything else, or Codex off for Hypernova, exits 97 and the shell runs plain
  codex), `hn attach [id|session id|project]`, `hn ls`. In an attached window Ctrl+\
  detaches. Any exit other than claude's own resets the terminal's modes
  (keyboard protocol, paste, focus and mouse reports, cursor).
- **Phone view**: `WS /v1/terms/{termId}/ws?ticket=…&cols=…&rows=…` with a ticket
  from `POST /v1/sessions/{id}/terminal`, on the route the phone is using (on the
  local route the app opens it for the terminal page; see Local network (direct
  HTTPS)). Binary frames are terminal bytes both ways; text frames are the holder's
  JSON messages (from the phone only `resize` and `scroll` pass). `{t:"scroll", data}` is the
  phone's finger scrolling an app that keeps its own history (Claude Code's fullscreen view, in
  the alternate screen): mouse-wheel turns as SGR reports, PgUp, PgDn or Ctrl+End, and nothing
  else (anything more is dropped). It reaches the holder as `keys`, so scrolling doesn't take the
  terminal's size as typing does. In the normal screen the page scrolls its own copy of the
  history and sends nothing. Each snapshot (opening, reconnecting) starts at the latest: the page's
  history at its end and, when the fullscreen view takes the mouse, one Ctrl+End, which brings
  Claude Code's own view down on the Mac too (the terminal is shared). It stays there through the
  resizes that follow until the finger scrolls. A tap on a web address stays on the phone: it opens
  as one in the chat does (http and https only; docs/DECISIONS.md, "xterm.js add-ons"), and claude
  sees no click. A phone 8 MB behind is closed
  with 4008 and reconnects. Tickets belong to the phone that asked: forgetting a
  phone (`DELETE /v1/devices/me` or `/local/devices/{id}`) closes its sockets at
  once with 4001, terminals included.

## After a restart of the Mac

A restart or shutdown takes every shared terminal with it, so each conversation open in one ends at
once, though nobody ended it (and the daemon itself is often killed without a clean stop).

- **What was open**: `<support>/open-sessions.json` (0600), `{at, open: [sessionId], cut: {sessionId:
  bootAt}}`, rewritten when the list changes. `open` holds the sessions that aren't `ended` (the
  daemon's own SDK sessions aside), and for a minute more each one that ended: shutting down, macOS
  ends every claude a few seconds before the daemon, and those ends mustn't read as the owner
  quitting them.
- **At start**, when the Mac has booted since the file was written (boot time from the system's
  uptime; `HYPERNOVA_BOOTED_AT`, epoch ms, stands in for it in tests), the sessions in `open` that
  were active in the 48 hours before the boot, and not since, the 10 most recent, are **cut short**:
  `endedBy: "mac_restart"`. Only the daemon restarting (the terminals outlive it) marks nothing.
  The first start with no file yet takes the phone's shared-terminal sessions last active in the
  48 hours before the boot as open then.
- A mark lasts 48 hours from the boot, through daemon restarts (`cut` in the file), and goes when the
  session runs again (a prompt resumes it in place: `claude --resume` in a new shared terminal, the
  same session id) or when the 48 hours are up (its clients get the `session` again).
- The phone keeps a cut-short session on Home ("Mac restarted · resume") rather than in History;
  opening it shows its whole history from the transcript, and writing resumes it.
- Leftovers from before the boot don't count as running: a holder's meta (`createdAt`) or a Claude
  Code registry entry (`startedAt`) from before it is stale even when its pid is alive (another
  process's now).
- **History** reaches back 30 days, the 300 most recently written transcripts (a phone session's own
  transcript however old, found by its id in any project folder). A transcript is read from its
  start until its folder and first prompt are known (past 256 KB the folder alone will do; 8 MB at
  most: a first prompt with pasted pictures can be one line of megabytes), and from its end for the title, Claude's last reply
  (`lastText`, so a card says where it left off before anyone opens it) and, failing the start,
  the folder.
- Resuming a session Claude Code never wrote a transcript for (its claude ended before taking a
  prompt) starts it afresh under its id (`--session-id`): `--resume` refuses an unknown id.

## History search

History finds a session by its title, project and last line on the phone alone; from 3 characters,
once typing has paused for 300 ms, it also asks the current computer for the words inside the
conversations (`daemon/src/search.ts`, idea HI1), and shows both, each session once, by day as
before. `GET /v1/history/search?q=<words>` →

```ts
SearchHit = {
  id: string,            // the session's id, as in the snapshot
  snippet: string,       // about 120 characters of the message with the first hit, whitespace squeezed,
                         // "…" where it was cut; plain text, never HTML
  marks: {start: number, end: number}[],  // the words in `snippet`: UTF-16 offsets, end exclusive, in order
  role: "user" | "assistant",             // who wrote that message
  matches: number,       // how often the words occur in the conversation, all of them counted
}
```

- **What matches:** every word of `q` (split on spaces, at most 8 of 64 characters each) somewhere in
  the conversation, not necessarily in one message; any case. The snippet is from the first
  message with all the words, else the first with any.
- **What is searched:** the owner's prompts (a "!" line and a command with its arguments too, as the
  chat shows them; a prompt queued while Claude worked) and Claude's replies. Never tool calls or
  their output, a command's output, Claude Code's notes (`<system-reminder>`…), meta entries,
  compaction summaries, API errors or subagents' lines (they read as `normalize.ts` reads them).
- **Whose:** the Claude Code sessions the daemon lists (the snapshot's `sessions`, Home and History
  alike; the phone keeps those in its History), whose transcript is in Claude Code's own `projects`
  folder; nothing else is read. Codex's sessions aren't searched (no transcript there).
- **Bounded:** each transcript is read in 1 MB steps, in its first 64 MB only, lines over 8 MB (pictures
  pasted into a prompt) skipped; tool results and tool-call-only lines are never parsed. A search stops
  after 2.5 s with `partial: true`, and when the phone hangs up or the same phone asks again. One
  search runs at a time.
- **Cache:** the text read from each transcript is kept in memory (24 million characters at most, the
  least recently searched dropped first) with its size, mtime and inode. A transcript that grew is
  read on from where it ended; one rewritten or smaller is read afresh. So a search cut short goes
  further the next time, and typing on searches the text already read.
- **The phone** shows "Searching inside conversations…" while it waits, a hit's snippet in place of
  its last line with the words in the accent, and "Partial results…" with `partial`. A 404 (an older
  daemon) turns the full-text search off for that computer until History is opened again; offline, or any
  other failure, leaves the phone's own filter alone. Home's search (idea HM8) asks the same route
  the same way and keeps the hits on its own sessions, History only those on its ended ones. Tests:
  `test/search.test.ts`, Android `HistorySearchTest`, `HomeSearchTest`.

## Local API (127.0.0.1:47322, `Authorization: Bearer <admin token>`)

The admin token is in `<support>/admin-token` (0600); the `hypernova` command reads it.

| Method & path | Purpose |
|---|---|
| `GET /local/status` | `{daemonVersion, apiPort, claude: {path, version, sdk: {installed, version, wanted}}, codex: Codex \| null, hooks: {installed}, statusLine: {installed, supported}, shell: {installed, supported}, devices, connectedClients: n, sessions: Session[], approvals: Approval[], usage, projectsRoot, files: {browseOutsideProjects}, update: ServerUpdate, account: Account, automations?: {paused, count, next: {id, name, at} \| null, running: [{id, name, sessionId, startedAt}]}, lan: {port, addresses, fingerprint, deviceCert: false} \| null, downloadUrl, downloadLocal, downloadTrusted, apk}` (`lan`: the LAN listener, see Local network (direct HTTPS); `null` when off; `downloadTrusted`: false for the listener's own address, whose certificate a browser can't check) (`claude.sdk`: Claude Code's SDK in the daemon's packages, its version and the one this daemon pins, see Claude Code's SDK) (`sessions`: those not ended; `automations`: see Automations, `next` the soonest due, `running` its runs running or needing you, `sessionId` the first session each started) |
| `POST /local/pair/start` | → `{code, link, appLink, webLink, expiresAt, url, download}` (see Pairing; `link`, what the QR code carries, is `webLink`, the listener's own pairing page at the computer's address, or `appLink` while the listener is off; `url` is the base URL `u`, that listener). `?dev`: the emulator's base URL and the app link |
| `POST /local/automations/{id}/fire` | the payload (≤ 64 KB, text) → `{run}`: fires an automation's webhook trigger. Takes `Authorization: Bearer <that automation's fire token>` (or the admin token): the one `/local` route that does. 401 `bad_token`; 409 `paused`, `off`, `not_webhook`; 413, 415 (see Automations, "Workflows") |
| `POST /local/settings` | `{projectsRoot?, lanAccess?: boolean, browseOutsideProjects?: boolean, codex?: boolean, claudeAgents?: boolean, releaseCheck?: boolean, autoUpdate?: boolean}` (`projectsRoot` kept only when that folder exists; `releaseCheck` the app's GitHub releases, see The app update, "Where the offered APK comes from"; `autoUpdate` the server updating itself, on unless set to false, see The server's own update, `hypernova auto-update`; `lanAccess` starts or stops the LAN listener at once, see Local network (direct HTTPS); `browseOutsideProjects`, see Files, The setting; `codex` turns Codex on or off for Hypernova, with the shell's `codex` function, see Codex; `claudeAgents`, sessions in `claude agents`, as `POST /v1/claude`) → the settings |
| `POST /local/integration/install` / `uninstall` | hooks + status line tee + the `claude` shell function (reversible, marked) |
| `GET /local/devices`, `DELETE /local/devices/{id}` | paired phones: `{id, name, model, createdAt, lastSeenAt, appVersion?, client?, certFingerprint?, certExpiresAt?}` (`certFingerprint`: its device certificate's, once it has one, and `certExpiresAt` when that runs out, epoch ms). Deleting one revokes its certificate too |
| `/v1/*` | the whole phone API is also available with the admin token |
| `GET /v1/ws?client=mac` (port 47321) | the Mac app's live feed: the admin token over loopback; in `connectedClients` like any client; the only feed that hears `speaking` |
| `GET /qa` | the testing checklist page. It and the routes it calls (`/qa/state`, `/qa/save`, `/qa/shot`) need its key `?k=` (from `GET /v1/qa`; the page passes it on to its own requests) or the admin token, on either port. The menu's link opens the bare page in a browser: on this port that is redirected to the page with its key, but only for a page opened by hand or by an app (`Sec-Fetch-Site` `none` or `same-origin`, or a browser that sends none), with Host `127.0.0.1` or `localhost`, never for another site's request |

## Hooks (installed by the integration step)

Global `http` hooks in `~/.claude/settings.json`, all pointing at
`http://127.0.0.1:47322/hooks/<secret>`: PermissionRequest (timeout 660),
PreToolUse, PostToolUse, PostToolUseFailure, UserPromptSubmit, Stop,
Notification, SessionStart, SessionEnd (timeout 10), and StopFailure, SubagentStop, PreCompact and
PermissionDenied (timeout 10).
Each entry is
recognised by the URL prefix, so uninstall removes exactly those. StopFailure (Claude Code fires
it in place of Stop when an API error ended the turn: its `error`, `error_details`,
`last_assistant_message`) is optional: installed with the rest and added to an install from
before at daemon start (as the shell block is rewritten; not by a daemon on its own
`HYPERNOVA_SUPPORT`), but the integration counts as installed without it, so an older install
never looks unconnected. It stops the session `idle` with `blocked` (see Account); its message is
Claude Code's error, so `lastText` stays as it was. SubagentStop (an agent's turn ended: `agent_id`,
`last_assistant_message`, `background_tasks`) is optional the same way; it ends that agent, unless it
waits on its own background work (see Agents and workflows).
PreCompact (the context is about to be compacted) is optional the same way; only automations' hook
triggers listen (see Automations, "Workflows"). PermissionDenied (matcher `*`; auto mode's classifier
refused a call: `tool_name`, `tool_input`, `tool_use_id`, `reason`) is optional the same way; the call
goes on the session's Blocked tab (see Blocked calls), and the daemon answers `{}`. Stop, StopFailure, PostToolUseFailure, PreCompact
and SessionEnd are handed to those triggers (for a session the daemon runs, the SDK's turn ends and
failed tool results stand in for them). The daemon ignores hook calls
for sessions it drives through the SDK (it knows their ids), because the SDK
already reports their events and approvals. A plan approval answers with the plan
echoed back as `updatedInput` (an interactive plan dialog only closes that way).
PreToolUse answers `allow` for Read of an attached file (see Attachments); otherwise it
only observes. Hooks from a subagent (`agent_id` set, `session_id` its main session's) tell what
that agent does (see Agents and workflows); they make no tool events of their own.

An automation's run passes one more hook in `--settings <support>/automation-hooks.json` (0600,
so the secret never shows in a process list; never in the owner's settings.json):
`PermissionDenied`, an http hook to the same URL. When Claude Code fires it (a call refused
without asking), the daemon adds the call to the run's `skippedTools` as a rule
(`Bash(<command>)`, or the tool's name) and answers `{}`. 2.1.301 doesn't fire it for a dontAsk
refusal, so the transcript is read too (see Automations).

The integration also adds a marked block to `~/.zshrc` (zsh only): a `claude`
function that runs `hn run "$@"` when both stdin and stdout are a terminal, and
plain `claude` otherwise or when `hn` exits 97. `HYPERNOVA_OFF=1` or
`command claude` skips it. The daemon rewrites the block at start-up so it points
at its own `hn`; uninstall removes it byte for byte. The status line tap writes each session's
status JSON to `<support>/statusline/<session_id>.json`, whatever the owner's status line is: a
bash script that reads `input=$(cat)` gets a marked block after that line; any other command
(ccstatusline through npx, a Python script, an inline command, a script that reads its input
another way) is wrapped as `bash "<support>/bin/statusline.sh" --then <the command, base64>`, which
taps the input, then runs the owner's command with the same input and prints what it prints
(uninstall puts the command back as it was); with none, Hypernova's own silent one.

settings.json is written back through its real path (a dotfiles symlink stays a symlink) with the
mode it had (it can hold `env` keys and the hook secret), 0600 when new; a backup goes to
`<support>/backups` first. At start the daemon also brings hooks that point at it with another
secret (a lost `hook-secret` file, an image built with one) up to the current one, as it adds the
optional hooks (not a daemon on its own `HYPERNOVA_SUPPORT`).
