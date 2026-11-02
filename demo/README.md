# The demo server

A small separate program that answers a phone like a real Hypernova server, from made-up replies, so
the README's screenshots show the app at its best with nothing real in them. The app has no demo mode
and knows nothing of this (`docs/DECISIONS.md`, "Screenshots come from a demo server").

It runs nothing and changes nothing: no Claude Code, no sessions. Tapping Allow gets a refusal.

## Run it

```bash
node demo/server.ts           # atlas, juniper and pico, on 47341, 47342 and 47343
node demo/server.ts atlas     # just that one
```

It needs Node 22.23.4 or 24.22.1 or newer, `npm install` already run in `daemon/` (it uses that folder's `ws`), and
`openssl`. Each server prints its pairing link, also in `~/.cache/hypernova-demo/<server>/pair-link`:

```bash
adb -s emulator-5582 shell "am start -a android.intent.action.VIEW -d '<the link>' com.prudnikweb.hypernova"
```

A code works once, and a used one is replaced in that file at once.

It listens on `127.0.0.1` only, and tells the phone to dial `10.0.2.2`, the emulator's name for this
computer's loopback, so a phone on the Wi-Fi can't reach it. The only way in is the one real phones
use: the local route, with the server's certificate pinned and the phone's device certificate
presented. That code is the daemon's own (`daemon/src/tls.ts`, `store.ts`), run against the demo's own
folder, so the real daemon, its state and its ports are left alone.

| Variable | Default | |
|---|---|---|
| `HYPERNOVA_DEMO_PORT` | `47341` | The first server's port; the others follow it. |
| `HYPERNOVA_DEMO_HOME` | `~/.cache/hypernova-demo` | Each server's certificates and paired phones. |
| `HYPERNOVA_DEMO_ADDRESS` | `10.0.2.2` | What the phone is told to dial. |

A request the demo has no reply for is answered 404 and printed as `no reply for GET …`: that's how to
find what a new screen needs.

## Retake the shots

```bash
scripts/demo-shots.sh                     # all six, written to docs/images/shot-*.jpg
scripts/demo-shots.sh chat changes        # just these
DEMO_KEEP=1 scripts/demo-shots.sh home    # leave the emulator and servers up, for another go
```

It builds the debug APK if there isn't one, starts the servers and the emulator, installs the app,
pairs it with all three servers, walks to each screen, crops off the status and navigation bars, and
writes 945 px wide JPEGs without metadata. **Look at every shot afterwards.** The walk finds things by
their text, but a few taps go by position (the sheet's strip, the planet), so a layout change can land one
in the wrong place.

The six shots are of real screens, in `shot_*` in the script:

| Shot | What it shows | Data |
|---|---|---|
| `shot-home` | Home on atlas: the groups, Allow and Deny on a card | `atlas/snapshot.json` |
| `shot-chat` | The invoice session: a denied push, the workflow's card, its status line with a divider per step | `atlas/sessions/5e550001-…/events.json`, `agents.json` |
| `shot-changes` | A diff under review in that session | `…/changes.json`, `…/diff-*.json` |
| `shot-all-servers` | All servers: the three machines in one list | every `snapshot.json` |
| `shot-statistics` | Hours, tokens, the 30-day chart | `atlas/stats-all.json` |
| `shot-automations` | Scheduled and triggered work | `atlas/snapshot.json`, `automations.list` |

The chat's status line draws its dividers only while a session works. Waiting on an approval it's one
solid amber line (`StatusLine` in `StatusBar.kt`), so a shot can't have both Allow and Deny and the
dividers. That's why the chat's approval is one the owner has already turned down, the only kind of
answered tool approval the chat keeps, and Allow and Deny are on Home's shot.

### The emulator

An AVD named `hn-demo` (`DEMO_AVD`): a Pixel 7 profile at 1080x2400, Android 36, `google_apis`, x86_64.
It runs on `emulator-5582` (`DEMO_EMU_PORT`), always addressed by that serial. Tried here, on an RTX 3080
with no display attached (emulator 37.2.13):

| `-gpu` | What happened |
|---|---|
| `swangle_indirect` (the script's) | Draws the chart, glows and glass blur. Software, so slow but steady. |
| `swiftshader_indirect` | The emulator crashes with a segfault as the app opens. |
| `host` | Needs an X display (`Failed to get EGL display`). With `ANDROID_EGL_ON_EGL=1` it falls back to its software translator and crashes too. |

Port 5580 is taken on the machine this was set up on: with its console port taken, the emulator boots
but adb stays `offline` for good. Three settings, all made by the script:

- **Wi-Fi off, mobile data on.** An emulator with both keeps swapping networks and the app reconnects
  at each swap; its Wi-Fi also drops out on its own.
- **Animations off while walking** (`animator_duration_scale 0`, the app's Reduce Motion). In software a
  moving screen draws slowly (Home, 45 frames in 30 s: docs/DECISIONS.md, "Atlas"), so taps and swipes arrive long after they're sent and
  uiautomator never sees a quiet screen. They're back on for a few seconds before each capture, so the
  shot is the app as it normally draws.
- **No heads-up notifications**, as the demo's approvals would drop down over the shots.

## The data

`demo/data/<server>/` holds one JSON file per reply, in **exactly the shapes a real server sends**
(`docs/PROTOCOL.md`), with no server logic in them, so another client's demo mode can load the same files.
An iPhone version would probably need one for App Store review (`docs/LATER.md`, L105).

| File | The reply to |
|---|---|
| `snapshot.json` | `GET /v1/snapshot` and the feed's first message. `lan` stays `null`: the server fills in its own |
| `stats-<range>.json` | `GET /v1/stats?range=<range>` |
| `models.json`, `commands.json` | `GET /v1/models`, `GET /v1/commands` |
| `sessions/<id>/events.json` | `GET /v1/sessions/<id>/events` |
| `sessions/<id>/agents.json` | `GET /v1/sessions/<id>/agents` |
| `sessions/<id>/usage.json`, `todos.json` | `GET /v1/sessions/<id>/usage`, `…/todos` |
| `sessions/<id>/changes.json` | `GET /v1/sessions/<id>/changes` |
| `sessions/<id>/diff-*.json` | `GET /v1/sessions/<id>/changes/diff?path=`, the file whose `path` matches |

Files are read for each request, so an edit shows the next time the app asks, with no restart.

### Times

A string that is exactly one of these stands for a time worked out as the file is read, so a shot never
looks stale. Everything else is literal. `demo/placeholders.ts` is the one place that does it, in a few
lines with no dependencies.

| Placeholder | Becomes |
|---|---|
| `"@now"`, `"@now-3m"`, `"@now+2h"`, `"@now-1d4h"` | Epoch ms. Units `s`, `m`, `h`, `d`. |
| `"@day"`, `"@day-3"`, `"@day+1"` | `"YYYY-MM-DD"`, that many days from today. |
| `"@next 07:30"` | Epoch ms of the next 07:30. |
| `"@next 07:30 1-5"`, `"@next 16:00 5"` | The same, on those ISO days only (1 Monday to 7 Sunday). |
| `"@last 02:00"`, `"@last 07:30 1-5"` | The same, the latest one that has passed: a scheduled run's last time. |

### Nothing real

Three invented machines (atlas, juniper, pico) and invented projects (ledgerline, orchard-sync, quillpad,
tinroof, nightjar, parcelkit, fieldnotes). None of the owner's servers, projects or session names, no real
emails, tokens or addresses. Claude and Codex model names may be real.

### Keeping it honest

`daemon/test/demo-data.test.ts`, part of the daemon's `npm test`, holds every file here against the shapes
in `protocol/fixtures/`, which `contract.test.ts` keeps equal to what the daemon really sends. A field the
protocol lost, gained or changed the type of fails there, naming the file and the field. A new kind of file
must be added to that test or it fails too. The test also runs this server through a real pairing and mutual
TLS, and looks for the owner's names and for anything shaped like an email, an address or a token. What it
can't see is how a value reads on screen: that's what looking at the shots is for.

Typecheck this folder with `npx tsc -p ../demo` from `daemon/`.
