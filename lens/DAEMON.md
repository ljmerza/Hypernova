# Lens and the daemon

Hypernova Lens (this folder) is a third kind of client, after the Android app and the Mac app. It
runs as a web page inside the Even App on the phone and talks to the daemon over the same phone API,
with the same pairing and bearer token. A web page has rules a native app doesn't, and Lens does
two things nothing else asked for: it reads replies aloud and shows pictures of what a session builds
(Telescope).

**Lens goes through the Hypernova app on the same phone now** (13; PROTOCOL.md, "The phone bridge"):
every call below is made to `http://127.0.0.1:47380`, signed with a key agreed with the app (the
token itself never travels), instead of to the Mac with a device token of Lens's own, and the app
carries it to the daemon unchanged, with the phone's token and `X-Hypernova-Via: lens`. What follows holds either way; a private build without
the app still pairs and calls the Mac directly.

**All of it is in the daemon now** (`daemon/src/http.ts`, `speech.ts`, `shots.ts`, `telescope/`, `notes.ts`),
and `docs/PROTOCOL.md` is the daemon's whole contract ("Transport and auth", "Read aloud",
"Telescope", "Speech to text"). This file is the part Lens relies on: each call it makes, exactly as it
makes it, what it expects back, and what it does with every answer. Change the daemon and Lens
together.

| # | What | Calls | In Lens |
|---|---|---|---|
| 1 | CORS and the WebSocket token | every `/v1/*`, `GET /v1/ws?token=` | `src/data/api.ts`, `src/data/hub.ts` |
| 2 | Read aloud | `POST /v1/speech/play`, `{t:"speech.progress"}`, `POST /v1/speech/plays/{id}/stop`, the snapshot's `speech`; else `GET /v1/speech/voices`, `POST /v1/speech` | `src/data/voice.ts`, `src/data/hub.ts`, `src/core/speech-out.ts`, Settings on the phone |
| 3 | Telescope | `POST /v1/sessions/{id}/telescope`, `GET /v1/shots/<token>.png`, `POST /v1/preview`; `POST /v1/sessions/{id}/screenshot` from a Mac before Telescope | `src/lens/controller.ts` (`telescope`, `watch`), `src/lens/output.ts`, `src/lens/gray.ts`, `src/core/telescope.ts` |
| 4 | (dropped) The speech companion's address | none: Lens stopped asking in 0.1.12 | |
| 5 | Pairing, the last turn, the live feed | `POST /v1/pair`, `GET /v1/sessions/{id}/events`, `GET /v1/snapshot` | `src/data/hub.ts` |
| 6 | A session's agents | `GET /v1/sessions/{id}/agents`, `agent` messages on the socket | `src/data/hub.ts`, `src/lens/controller.ts` (the live view) |
| 7 | The Mac's battery | `snapshot.mac.battery`, `battery` messages on the socket | `src/data/hub.ts`, `src/core/power.ts`, the battery card, a pop-up at 20% |
| 8 | Speech to text through Soniox | `GET /v1/stt/ws?token=`, `POST /v1/stt/key`, `POST /v1/stt/test`, the snapshot's `stt` | `src/data/stt.ts`, Settings → Speech (`src/phone/speech-card.ts`) |
| 9 | The Lens guide | `POST /v1/guide`, `POST /v1/guide/show`, the snapshot's `guide` | `src/phone/guide.ts` |
| 10 | New sessions in Nebula, limits, Stay awake | `POST /v1/sessions` with `newProject.nebula`, `GET`/`POST /v1/nebula`, the snapshot's `account` and `mac.awake`, `account`, `awake` and `mac_sleep_soon` on the socket, `POST /v1/mac/awake` | `src/core/nebula.ts`, `src/core/limits.ts`, `src/core/power.ts`, `src/core/popups.ts`, `src/data/hub.ts`, `src/data/nebula.ts`, `src/lens/controller.ts`, Settings on the phone (`src/phone/glasses-card.ts`) |
| 11 | On a bike or a walk: notes, the mode, continue, what changed | `POST /v1/nebula/notes`, `POST /v1/sessions/{id}/mode` (`plan`, `default` only), `POST /v1/sessions/{id}/continue`, the `diff` of edit events in the last turn (5) | `src/core/phrases.ts`, `src/core/changes.ts`, `src/core/debrief.ts`, `src/core/risk.ts`, `src/data/hub.ts`, `src/lens/controller.ts` |
| 12 | Automation notes | `note` messages on the socket (`to: "glasses"`), the snapshot's `automations.notes`, `POST /v1/speech` to read one | `src/core/notes.ts`, `src/lens/notes.ts`, `src/data/hub.ts`, `src/lens/controller.ts` (the `note` screen) |
| 13 | Through the Hypernova app on the phone | `GET /bridge/ping`, `POST /bridge/request`, `GET /bridge/request/{id}`, `GET /bridge/status`, `GET`/`PUT /bridge/settings`, `DELETE /bridge/access` on `127.0.0.1:47380`; every call above through it, signed and sealed (protocol 3), `{t:"bridge.settings"}` on the socket | `src/data/bridge.ts`, `src/data/seal.ts`, `src/data/link.ts`, `src/data/hub.ts`, `src/phone/status.ts`, `src/main.ts` |
| 14 | Automation asks | a note with `ask` on the socket (and in the snapshot's `automations.notes`), `{t:"run"}` on the socket, `POST /v1/automations/runs/{runId}/answer` | `src/core/ask.ts`, `src/lens/asks.ts`, `src/data/hub.ts`, `src/lens/controller.ts` (the `ask` screen) |

## 1. CORS and the WebSocket

The Even App loads Lens from its own origin, so every call to `https://<mac>.ts.net:47321` is
cross-origin, and one with an `Authorization` header or a JSON body is preflighted. The daemon
answers every `/v1/*` request on the phone port, errors included, with
`Access-Control-Allow-Origin: *`, and `OPTIONS /v1/*` with 204 before any authentication (methods
`GET, POST, PUT, DELETE`, headers `Authorization, Content-Type`, max age 7200 s, and
`Access-Control-Allow-Private-Network: true` when Chromium asks). No credentials mode: the token is
a header, never a cookie, which is why `*` is safe. A browser can't set headers on a WebSocket, so
Lens opens `/v1/ws?token=<deviceToken>`.

Everything Lens calls stays under `/v1`, Telescope's pictures included (3): pages outside `/v1`
(previews, downloads) send no CORS headers.

What Lens does with the answers (`api.ts`):

- **401**: "This phone isn't paired with the Mac any more", and the hub stops.
- **404 `not_found`** (a route this Hypernova doesn't have): the `missing` problem; each feature
  below says what it does then.
- **No answer at all**: an opaque `no-cors` request to `/v1/health` tells a Mac that answered
  without CORS ("Update Hypernova on the Mac": a daemon from before Lens) from one that isn't
  there ("Can't reach your Mac…").
- Any other error: the daemon's own `message`, on the lens or the phone.

## 2. Read aloud

The lens reads a finished reply into the earbuds (a tap on the result card, "read the reply") and
the "status" word speaks a summary. Android's WebView has no voices of its own, so the Mac speaks:
Soniox's natural voices, or its own (the snapshot's `speech.using`; chosen in the Hypernova app's
Settings → Voice).

**First, the Hypernova app on the phone plays it** (PROTOCOL.md, "Read aloud", "Plays"), because the
Even App's WebView only makes sound after a touch on the phone's screen. While the socket is live and
the snapshot's `speech.phone` is true, `POST /v1/speech/play {text, sessionId}` → `{play: {id, of,
engine, to}}`; the app streams and plays it (headphones, screen off), and the socket brings
`{t:"speech.progress", progress: {id, state, ms, chars, of, level, why}}` a few times a second:
`chars / of` keeps text that arrives like speech in step, `level` moves the speaking wave in the
header (`. : | : .`, at most three steps a second, `src/core/speech-out.ts`), `ended` is the only
"heard to the end" (a risky command while moving). A tap: `POST /v1/speech/plays/{id}/stop`. A 409
`no_player`, or a play that never starts (`failed` before any `playing`), falls back to the clip
below; 20 s without a word from the phone mid-play ends the reading.

`GET /v1/speech/voices` → `{voices: [{id, name, lang}]}`, the Mac's `say` voices (`lang` like
`en-US`). Settings → Read aloud lists them; the choice is kept as `mac:<id>`. Asked when
Settings is first drawn, and again on a later redraw if that failed (the Mac was away) or after a
new pairing.

`POST /v1/speech` with

```
{"text": "Fixed the export. Three files changed, 48 tests passed.", "voice": "Samantha", "rate": 1.1}
→ 200 audio/wav (Soniox) or audio/mp4 (AAC in M4A, the Mac's voice), the whole clip
```

- `text`: plain words. Lens strips markdown and names code blocks rather than reading them
  (`speakable`), and cuts a long reply into pieces of at most 8000 characters (1200 while Soniox
  reads, so each is made in moments), between paragraphs (`speechChunks`). It asks for the next piece only once the previous one has arrived, while that
  one plays: the daemon renders one clip per device and a newer request stops the older one.
- `voice`: the `id` chosen in Settings, or none for the Mac's own default. `rate`: Settings' speed,
  0.5–2.
- Lens decodes the bytes with Web Audio (`decodeAudioData`) and plays them through the one audio
  context a touch on the phone unlocked. It waits 45 s for a clip (the daemon gives `say` 40 s; 8000
  characters take about 3 s with the default voice).

| Answer | Lens |
|---|---|
| 404 `not_found` (a Hypernova from before read-aloud), 501 `no_voice` (no `say`) | stops asking this Mac; an iPhone's own voice reads, on Android the lens says "No voice here" and the reply stays in pages |
| 409 `replaced` | only happens when Lens itself started a newer read, which has taken over |
| 502 `speech_failed`, a timeout | nothing is read this time; what was already read stays read |
| 400 `empty`, 413 `too_long` | never sent |

## 3. Telescope

"Telescope" (a voice word, or the long-press menu) puts what the session in front of you is
building on the whole lens: its web page, the Android emulator or a phone, the iOS Simulator, its
terminal, a picture or PDF page it made, or its Mac app's window, whichever it touched last (the
Mac picks: PROTOCOL.md, "Telescope"). "Telescope emulator", "web", "terminal", "simulator",
"image", "window" pick one; "telescope phone" and "telescope emulator" also say which kind of
Android device (the glasses can't say a device id); "telescope localhost 3000" a page on the Mac;
"telescope pdf page 3" a PDF's page. Any other words after "telescope" let the Mac pick: said to the
glasses, "telescope …" is never a prompt. "Show me" on its own does the same as "telescope" ("show
me …" with more words is a prompt). The phone keeps the last picture in a card; a tap opens it full
size.

`POST /v1/sessions/{id}/telescope` with `{}` (the Mac picks), `{"source": "emulator"}` (or `web`,
`simulator`, `terminal`, `image`, `window`), `{"source": "emulator", "prefer": "phone"}` (or
`"emulator"`), `{"source": "web", "url": "http://localhost:3000/"}` (an address said: `spokenUrl`),
or `{"source": "image", "page": 3}`. Watching adds `"watch": true` and the picture's `device`. Lens waits 45 s (finding the
page, then Chrome, 20 s at most, maybe after another session's capture; adb and the drawings take
about a second).

```
→ 200 {"shot": {"url": "/v1/shots/7288f477c461c8f7b097c8a7.png", "width": 1260, "height": 2800,
                "source": "emulator", "title": "Android emulator · Pixel 8", "label": "Pixel 8",
                "pageUrl": null, "device": "emulator-5554", "digest": "d91593f1e0d2a03139ef4261",
                "at": 1792106371287, "auto": true}}
```

The picture is at most 1600 pixels on its longer side (a phone's 1260 × 2800 screen comes as
720 × 1600). Picking by itself, the Mac may add `skipped: {source, code, message}`: a source the
session points at that it passed over for something the owner can fix ("Say “telescope window” to
allow Screen Recording.", "Open Hypernova on the Mac to see the app’s window.", "Several Android
devices: say “telescope phone” or “telescope emulator”."). Lens says the message on the label's
right for 4 s, over the picture it got instead.

- `url` is relative: Lens resolves it against the paired base URL and loads it **without** the
  bearer header (`fetch` in `cors` mode, then `createImageBitmap`; an `<img>` with
  `crossOrigin="anonymous"` if that fails), because the daemon serves it by its unguessable name for
  12 hours (`image/png`, `Cache-Control: private, max-age=43200`, and CORS, which reading a canvas's
  pixels needs). The phone shows the same URL in an `<img>`.
- **On the lens**: the label line, "TELESCOPE · hypernova · emulator · just now" (the project, the
  source, the picture's age), over the picture: 576 × 256, four 288 × 128 image tiles. The picture
  is kept full size; the lens gets it fitted, turned dark if it's light, contrast stretched and
  error-diffused to the 16 levels, tile by tile (`ditherScope`); the terminal, drawn for the lens,
  goes to the nearest levels without dithering. A tall picture's whole view (a phone's screen) is
  its top and bottom halves side by side, twice as large as it would be alone (`sideBySide`).
  Tap zooms into a quarter, cut from the full-size
  picture (so a quarter is sharper, never an enlargement): the four corners of a wide picture,
  four bands down a phone's screen (whichever is closer in shape to the lens). Swipes go round the
  quarters ("2/4" on the right), double tap zooms out, then goes back to where you were; a tap on
  a quarter opens the picture on the phone.
- **Watch** ("watch", or the menu's Watch): the same source (and device) again 3 s after each
  answer, for a minute, with `"watch": true` (the Mac keeps only the newest two frames); any
  gesture stops it ("watching" on the right meanwhile). The Bluetooth budget: the Mac answers a
  picture that hasn't changed with the same link, which Lens doesn't load again, and the label's
  age stays at the last change; a changed picture is dithered tile by tile, a tile in which almost
  nothing moved (under 0.25 % of its pixels, scattered: a twinkle, a caret's blink) keeps its old
  pixels, while a few pixels that moved together (a digit, "FAIL" → "PASS") are a change and go,
  and the sender writes only the tiles whose pixels differ from what the glasses have. The
  terminal, drawn for the lens, has no noise: every change in it goes. So a still screen costs
  nothing and a progress bar one tile. A failed look stops watching and says why; the last picture
  stays.
- **No picture**: the daemon's words in the picture's place.

| Answer | Lens |
|---|---|
| 404 `nothing_to_show`, `no_emulator`, `no_phone`, `no_device`, `simulator_off`, `no_picture`, `no_app`, `no_window`; 409 `which_device`, `device_unauthorized`, `device_offline`, `no_terminal`, `busy`; 403 `not_local`, `screen_recording`; 400 `bad_url`, `bad_device`; 501 `no_adb`, `no_simulator`; 503 `no_mac_app`; 502/504 `capture_failed`, `shot_failed`; 422 `locked_pdf`; 404 `no_session` | the daemon's message where the picture would be; double tap goes back |
| 501 `no_browser` (web, or picked) | the message ("Install Google Chrome on the Mac…"), and the live page instead: the port you named, else a dev server the session's events show (`devServerPort`), through `POST /v1/preview {port}`; a tap opens it on the phone |
| 404 `not_found` (a Hypernova from before Telescope) | for the web or a picked source: `POST /v1/sessions/{id}/screenshot` (its page, as Show me did, `source: "url"`); a Hypernova from before screenshots too: "This Mac can't take pictures yet", with the same live-page fallback |
| the picture: 404 `shot_expired`, or pixels this WebView can't read | "The picture didn't load here. Tap to see it on your phone." |

`test/lens.test.ts` ("telescope: each answer of the daemon's telescope route", and the zoom and
watch tests) holds Lens to this table.

## 4. The speech companion's address

Dropped. Up to Lens 0.1.11 it asked `GET /v1/speech/companion` for Hyperscribe Companion's address;
since 0.1.12 voice goes only through the Mac (8), and Lens no longer calls it. A saved companion
address is forgotten when settings load.

## 5. Pairing, the last turn, the live feed

- **Pairing**: `POST /v1/pair {"code", "deviceName": "<glasses> · Lens", "model": "Even G2 ·
  Hypernova Lens", "appVersion", "client": "lens"}`. The daemon keeps `client` with the device, so
  the Mac app's device list can say "Even G2 glasses". Any 4xx means the code is no good.
- **The last turn** (the result card's facts, read-aloud, and the live view's bottom box): `GET
  /v1/sessions/{id}/events?limit=120&output=8192&turn=last`: only events from the last thing typed
  on, and each command's output cut to its last 8 KB (where test counts are). The daemon's last turn
  starts at a slash command too (`/cost` typed after a reply), while Lens' starts at the last
  prompt, so when the first event is a command Lens asks again without `turn=last`. A daemon
  without these parameters ignores them.
- **The live feed**: the WebSocket's `snapshot` and then each change, as for the Android app. When
  the socket fails twice, `GET /v1/snapshot` tells why; if HTTP works, Lens polls it every 4 s. A
  retry or poll that finds nothing new tells the lens nothing, so the planets come to rest while the
  Mac is away (`test/hub.test.ts`). `event` messages extend the turn Lens has loaded for a session
  (others are ignored); a `snapshot` on the socket (a reconnect) drops what was loaded, since what
  happened meanwhile never came, and it is asked for again when next needed.
- **The live view** (a working session on the lens) loads the last turn once when it opens, and the
  socket keeps it current. Without the socket (polling), it asks `events?…&turn=last` again every
  4 s, for that session only and only while it works; while the Mac is away, not at all.

## 6. A session's agents

The live view's top box lists the agents a session's main agent started that are at work now
(newest first, with what each does), and its bottom box shows each agent's report as it lands.

`GET /v1/sessions/{id}/agents` → `{agents: Agent[], workflows: Workflow[]}` (PROTOCOL.md, "Agents
and workflows"). Lens reads `agents` only, and of each agent `id`, `label`, `status`, `activity`
(while it runs: `"Read: core/Solar.kt"`), `startedAt`, `endedAt` and `result` (its report, at most
600 characters); `superseded: true` (a workflow step's earlier attempt) leaves an agent out.
Workflows aren't read: their agents are in `agents`, and the session's `agentsRunning` counts a
workflow between its phases.

- **When**: once when the live view opens on a session; again when that session's `agentsRunning`
  changes; every 4 s while the socket is down (with the turn, above). Never for a session that isn't
  on the lens.
- **`agent` messages** (`{t: "agent", agent}`) update the list of a session Lens has loaded (same
  `id` replaces, a new one is added, `superseded` removes); for other sessions they're ignored. A
  `snapshot` on the socket drops the lists, as it does the turns.
- **Before the list comes**, or from a Mac without the route, the top box says the session's own
  count: "• 2 agents at work".

| Answer | Lens |
|---|---|
| 404 `not_found` (a Hypernova from before agents) | stops asking this Mac; the session's `agentsRunning` is all it shows |
| 404 `no_session` | the session has ended; the lens shows that when the Mac says so (its result card) |
| No answer, a timeout | the box keeps what it had; asked again no sooner than 5 s later |

## 7. The Mac's battery

The battery card (on request: "battery", or a swipe up from the glance's first line) shows the Mac's
battery under the glasses' own, so you know whether the Mac in your bag will last; at 20% a pop-up
says so by itself. Lens reads it exactly as PROTOCOL.md has it:

- `snapshot.mac.battery`: `null` for a Mac without a battery (a Mac mini, an iMac, a Mac Studio or
  Pro), else `{percent, charging, onAC, minutesLeft}` (percent 0–100, minutesLeft a number or
  null). Missing (a Hypernova from before batteries) is taken as `null`.
- `{t: "battery", battery}` on the socket, which the daemon sends when the percentage moves by a
  point or more, or charging or power changes (it reads `pmset -g batt` every 60 s). Lens puts it in
  its snapshot; one equal to what it has changes nothing (no redraw). A polled snapshot carries it
  too.

| The Mac says | Lens |
|---|---|
| `{percent: 62, charging: false, onAC: false, …}` | the Mac's row: a drawn laptop, four of five segments lit, `MAC 62%` |
| `charging: true` | `MAC 62% · charging`, a bolt after the bar |
| `null`, or no `battery` at all | no row for the Mac (the glasses' alone; Stay awake's line under them, "Mac: …") |
| a percent of 20 or less, not charging and not on power | `· low`; a pop-up "MAC BATTERY · 20%" once per discharge (again after it charges or climbs past 25%), and "status" says "Your Mac's battery is at 20 percent." |
| anything while the Mac is away (offline) | no row for the Mac: the last level would pass for the current one |

`minutesLeft` isn't shown: it moves by itself between messages, so on the lens it would be stale.
The percents are text and aren't news; the drawn bars are one small image, sent again only when a
row's segments or charging change.

## 8. Speech to text through Soniox

The only way to speech: the daemon relays the glasses' microphone to Soniox's real-time API with a
key kept on the Mac (PROTOCOL.md, "Speech to text"). At each hold Lens uses it while the snapshot
says `stt.ready` (also as `{t:"stt"}` on the live feed); else the hold says what the Mac needs
("Voice needs Soniox on your Mac. Connect it on your phone: Settings, Speech."). A hold keeps what it
started with (`AutoSpeech`).

- **The stream** is `wss://<mac>.ts.net:47321/v1/stt/ws?token=<deviceToken>`: the same host and token
  as everything else, so nothing more goes in the whitelist. `MacSpeech` drives it
  with Lens's own client (`startMacStream`, `src/data/mac-stream.ts`):
  `hello`, `start {language}`, `started`, PCM frames, `snapshot`s, `stop`, the last `snapshot` with
  `finished: true`. Audio said before `started` is held (30 s at most) and sent then.
- **The release** (`StreamSpeech.stop`, `src/data/speech.ts`): the microphone stays on 0.4 s after
  the temple is let go (`RELEASE_TAIL_MS`: the word being finished, and audio still on its way from
  the glasses; the G2 passes on frames until the Even App says the microphone is off), then `stop`.
  Soniox settles the last words only once the audio has ended, so Lens waits for the `snapshot` with
  `finished: true`, 2.5 s at most (`FINAL_WAIT_MS`; the daemon itself gives Soniox 5 s). Past that,
  or when the link fails on the way, it keeps the words it had, closes the socket (the daemon logs
  `phone left`) and marks them incomplete: the lens says "last words missing?" and waits for a tap
  instead of sending them after its count.
- **When the Mac refuses** (no key, Soniox refused the key or has no balance, Soniox away), its
  `error` message is what the lens shows ("Connect Soniox in Hypernova on your Mac first."). When the
  stream never opens: "Can't reach your Mac for speech. Is it awake, with Tailscale on?". An error
  after `started` carries the words so far, which the lens keeps.
- **The key**: Settings → Speech (and the guide's Voice step) has a password field; Connect sends
  `POST /v1/stt/key {provider: "soniox", apiKey}`, waits up to 25 s while the Mac tries it, and shows
  the daemon's words: 400 `bad_key` ("Soniox didn't accept that key…"), 409 `removed` (the key was
  removed on the Mac while this one was tried), or kept: `checked: false` when Soniox couldn't be
  reached to check it, else `problem` with Soniox's words when it answered but the try didn't pass
  (busy, a problem on its side), or `error` when the account has no balance. Lens never stores the
  key. Test is `POST /v1/stt/test` → `{ok, message}`. Removing the key is the Mac's.

## 9. The Lens guide

The Mac opens a "Hypernova Lens" window when glasses pair; Lens' phone page shows the same three
steps (what it's for, gestures, voice) at the same place (PROTOCOL.md, "The Lens guide").

- The step is the snapshot's `guide` (`{t:"guide"}` when it moves). Next, Back and Done are
  `POST /v1/guide {step}` / `{done: true}`; the card shows the new step at once and the Mac's echo
  follows. Showing the card marks it `seen: true`. A Mac without `guide` in its snapshot gets no card.
- "Show on my Mac" is `POST /v1/guide/show {step}` → `{macs}`: 0 means the Mac app isn't running
  ("Open Hypernova in your Mac's menu bar, then try again.").
- Once done, "Open the Lens guide" under the gestures sends `{done: false}`, which starts it again.
- With sample data the guide keeps its place on the page itself.

## 10. New sessions in Nebula, limits, Stay awake

**Nebula.** A hold on the glance (with nothing that needs you), "new session: …", and "+ New project"
in the picker start a session in a new subfolder of Nebula. Lens names it from the prompt
(`folderName`: short kebab-case, filler words dropped, at most four words and 32 characters) and
sends

```
POST /v1/sessions {newProject: {name: "landing-page-bakery", git: true, nebula: true}, prompt}
```

The daemon makes the name safe and unique in Nebula (`landing-page-bakery-2`), makes Nebula if it
isn't there yet, makes the folder, runs `git init`, and answers `{session}` as for any new session;
Lens shows "NEW PROJECT   <session.projectName>". A new session from the glasses (in Nebula or in a
project) also sends `providerId` and `model` when the Hypernova app on the phone says what its
new-session sheet last used (the bridge settings' `newSession`), and only while the snapshot's
`providers` still has that provider; never `permissionMode` (Auto and Bypass are the phone's). A Mac from before Nebula ignores `nebula` and makes
the folder in its projects folder instead. Settings on the phone read `GET /v1/nebula` → `{nebula:
{path, default, exists}}` (paths `~/…` inside the home folder) and move it with `POST /v1/nebula
{path}` (400 `bad_path` outside the home folder, the home folder itself, Library, a hidden folder or
a file); a 404 `not_found` there says "older than Nebula". Nothing is made before the tap that sends.

**Limits** (PROTOCOL.md, "Account"). Lens keeps `snapshot.account` (`{t: "account"}` replaces it)
and `snapshot.providers` (`{t: "providers"}` replaces them), and reads `usage` (its `rate` too) and
each session's `blocked`, `providerId`, `stoppedForAway`, `permissionMode` and `queued`. The corner's
parts are a gap apart (no "·"):

| The Mac says | Lens |
|---|---|
| `fiveHour` below its limit | "42%   resets in 2h 11m"; while `rate` says the limit comes before the reset (`fiveHourEtaMs`, else worked out from `fiveHour` %/min), "62%   limit in 47m" |
| `fiveHour.pct` 100 with its reset ahead, or `account.limit.window: "five_hour"` while `limited` | the sun's disk quiet (a dim ellipse, the point and a bright spot on its rim); the corner "limit until 16:40" |
| `sevenDay.pct` 100, or `limit.window: "seven_day"` | the low state: the white dwarf a faint point alone (it outranks the 5-hour limit); "week limit until Fri 10:00" (the weekday past 20 hours) |
| `limit.usingCredits` | the disk; "on usage credits" |
| `usage.models` (a model's own week, PROTOCOL.md "Per-model weeks"), only while one is fuller than both the 5-hour window and the week | the corner "Fable 91%   resets Tue 8:15", or at 100% "Fable limit until Tue 8:15" (also at once when `limit.window: "model"` names that model, "Fable limit", before the Mac looks again); the sun stays on the 5-hour window |
| `account.state` `logged_out`, `no_subscription`, `on_hold` (or `usage.source: "stale"`) | an eclipse; "signed out", "no plan", "on hold" |
| `no_credit` | "out of credits" |
| a new limit episode (`since`), and its end | pop-ups "SESSION LIMIT REACHED" and "LIMIT LIFTED" (when that switch is on), and the disk's outburst |
| `session.blocked` | the session stays on the glance, in the phone's words: "session limit until 2:00, then carries on", "session limit until 2:00", "paused at the session limit", "session limit over, ready to continue" (`resume.state: "ready"`), "signed out on the Mac", "can't reply without a Claude plan"; its card says PAUSED |
| `blocked.kind: "provider"` with the session's `providerId` in `providers` | the provider's trouble: "OpenRouter is out of credits" ("reached its budget"), "… refused its key", "… is rate limited until 14:05", "… isn't answering", "… carries on by itself at 14:05" (`resume` armed), "… is ready to continue"; its card says CAN'T REPLY (PAUSED while it carries on by itself) |
| a provider's `status` starting (by `state` and `since`), and a `no_credit` one ending while sessions wait on it | one pop-up per episode (the limit switch): "OUT OF CREDITS   OpenRouter" / "1 session paused. Add credits or continue on another provider.", "KEY REFUSED", "RATE LIMITED", "NOT ANSWERING"; "CREDIT AGAIN" |
| `session.stoppedForAway` (an approval denied by itself after 10 minutes) | the session stays on the glance: "paused after 10 minutes without an answer"; its card and its band say PAUSED; a tap on the card is `POST /v1/sessions/{id}/continue` (11) |
| `approval.expiresAt` | needs cards and the needs band say "auto-deny in 9m" |
| `session.permissionMode` other than `default` | the live view's and the cards' right side: "plan", "accept edits", "auto", "bypass", "allowed only" |
| `session.queued` | "2 queued" after its line (a gap apart), "2 queued" on its live view |

**Stay awake.** `snapshot.mac.awake` (`{mode: "working" | "backpack" | "off", holding, lidClosedOk,
backpackReady, until, reason}`; `{t: "awake", awake}` replaces it) is the line under the Mac on the
battery card. "Stay awake" sends `POST /v1/mac/awake {mode: "backpack"}` (`"working"` until
`backpackReady`); "let it sleep" sends `"working"` from Backpack, else `"off"`. The answer's
`{awake}` replaces Lens' copy at once. A Mac without `awake` in its snapshot gets "This Mac's
Hypernova can't keep it awake yet". `{t: "mac_sleep_soon", inSeconds, reason: "battery" | "hot" |
"time"}` is the pop-up "MAC SLEEPS IN 2 MIN" with why, for `inSeconds`.
About ten minutes before Backpack's 8 hours end, `{t: "mac_backpack_ending", inSeconds, until, extendBy}`
is the pop-up "BACKPACK ENDS IN 10 MIN" (its own kind of the Mac's warnings, so Eclipse lets it
through): a tap on it, or "extend", sends `POST /v1/mac/awake {extend: true}` (+2 h; 409 `too_early`
or `not_backpack` are said in words) and the pop-up goes; so does it when `awake.until` moves on
(extended on the Mac or the phone). The Mac's low-battery warning is 20% (once per discharge, again
after it charges or climbs past 25%); Stay awake's own guard stays at its `minBattery` (15 by default).

## 11. On a bike or a walk: notes, the mode, continue, what changed

Most of what the glasses do on the move is Lens' own: the guide, moving mode, eclipse, debrief (from
the snapshot and the turns it has), "next" / "back", the numbers (answers as in 5: `POST
/v1/approvals/{id}` with `answers`, or a terminal menu's choice), and risky commands (Lens reads the
command; the daemon has no risk check of its own). Four things go to the Mac:

**"Note: …"** is `POST /v1/nebula/notes {text, project?}` → `{note: {path, at, line}}`: the daemon
appends `- 14:05 · hypernova · the idea` to `<Nebula>/notes.md` (made, with Nebula, if it isn't
there), under a `## 2026-10-16` heading per day, in the Mac's time (PROTOCOL.md, "Nebula"). `project`
is the session in front of you (its `projectName`), none on the glance's "+ New session". Claude
isn't started.

| Answer | Lens |
|---|---|
| 200 | "Noted · the idea" on the lens (moving: "Noted." in the earbuds too) |
| 400 `empty`, 413 `too_long` (over 4000 characters) | the daemon's message (a note is said, so neither comes up in practice) |
| 404 `not_found` (a Hypernova from before notes) | "This Mac’s Hypernova can’t keep notes yet. Update it" |

**"Plan first" / "go ahead"** is `POST /v1/sessions/{id}/mode {permissionMode: "plan" | "default"}`
for the session in front of you. Lens never sends `auto`, `acceptEdits` or `bypassPermissions` (its
`setMode` takes only these two): Auto and Bypass are the phone's, behind its think-twice card, and
"auto mode" or "bypass" said to the glasses gets "Auto and Bypass only from the phone". `applies:
"next_turn"` adds "· from its next turn". 409 `terminal_mode`, `waiting`, `mode_unavailable`, 404
`no_session`: the daemon's message (lensWords).

**"Continue"** is `POST /v1/sessions/{id}/continue {}` (the daemon's "I'm back. Please continue
where you left off.") for the session in front of you, else one with `stoppedForAway`. 409
`needs_answer`: "… is waiting for your answer first"; others, the daemon's message.

**"What changed?" / "show diff"** ask nothing new: an edit event (`Edit`, `Write`, `MultiEdit`,
`NotebookEdit`) carries `diff: {path, added, removed, hunks: [{oldStart, oldLines, newStart,
newLines, lines}]}` (PROTOCOL.md, "Types"; `lines` start with `+`, `-` or a space, as Claude Code's
patch has them), and the last turn (5) has the events. `output=8192` cuts tool output, never a diff.
A file made in the turn has hunks starting at 0, shown as "new file". An event without `diff` (a
Hypernova from before diffs) still names its file, without counts or a diff page. The daemon cuts
a diff at 40 hunks of 400 lines; Lens shows at most 12 pages a file and says the rest is on the phone.

## 12. Automation notes

An automation can tell you something (`notify`, `say`, or "when it's done" on a session action;
PROTOCOL.md, "Automations"). The Mac makes a note `{id, automationId, runId, name, text, to, at}` and
sends it as `{t: "note", note}` to every client at once; the last 12 hours of them also ride in
`snapshot.automations.notes`. Lens shows only the ones with `to: "glasses"`; the phone shows the
others. Nothing is asked of the daemon for them, and Lens never writes an automation (it has no
editor): `say` with no glasses paired is refused by the daemon (`no_glasses`), and a `say` with the
glasses paired but not connected goes to the phone instead.

| What | Lens |
|---|---|
| `{t: "note"}`, `to: "glasses"` | shown once, by its `id` |
| `{t: "note"}`, `to: "phone"` | ignored |
| `snapshot.automations.notes` (on connecting, and on a reconnect) | the glasses' ones not shown yet, and only if `at` is under 5 minutes old; the rest are marked seen without showing, so glasses put on after a morning don't read out the morning |
| a note with no `id` or no `text`, an unknown `to` | ignored; unknown fields are left alone |

**On the lens** (`src/lens/notes.ts`, the controller's `note` screen). On the glance a note takes the
lens as a card, `AUTOMATION · Morning brief` with its age on the right, the text as one that scrolls
(swipes, a window less a line, as a result's reply does), and `tap read aloud · double tap back`.
Over any other screen it is a pop-up band instead, "tap open · swipe away", about 12 seconds, merged
behind anything that needs you; a tap opens the card. With reading aloud on (Settings, not "mute") it is
read at once, "Morning brief. Three pull requests need a look…", through `POST /v1/speech` as a
result is (2); a tap on the card reads it again or stops the voice; muted, the card still shows
and says so in its hint. Not while you talk or the guide runs (it waits as a band). Eclipse keeps
all of it quiet, and so does the phone's switch **An automation has news** (Settings → Pop-ups on
the glasses; on by default, since someone chose to hear it on the glasses).

Mac and phone ids: the note's `id` is the dedupe key, kept for the life of the page; the lens keeps
the last 20 notes to reopen from a band.

## Checked against the daemon

2026-10-15, Lens' own code against a second daemon from this tree (CLAUDE.md, "Testing while the
owner's own install is live"; ports 47481/47482), both from Node (`LiveHub`) and from the real page
in Chromium paired by a development link:

- pairing, the device listed with `client: "lens"`; the WebSocket live with `?token=`; a preflight
  (204, `*`, the methods and headers above, max age 7200, private network); a 401 with CORS, read
  as unpaired;
- 184 voices; a 50 KB `ftyp` clip in 0.6 s, which Web Audio decodes; 400 `empty`, 413 `too_long`;
- a screenshot of a loopback page (its `<title>`, `pageUrl`), the picture without a token (200
  `image/png`, CORS `*`, `private, max-age=43200`), dithered on the lens and shown on the phone;
  404 `shot_expired` for an unknown one; 403 `not_local` for the daemon's port and for another host;
  502 `shot_failed` where nothing listens; 404 `no_session`;
- with no Chrome on the Mac (`HYPERNOVA_CHROME` pointing nowhere), "show me localhost 47491": 501
  `no_browser` with its message on the lens and the live page through `POST /v1/preview`, which
  opens;
- the last turn's events; 404 `not_found` read as `missing`.

Again in the evening of 2026-10-15, `LiveHub` from Node against a daemon from this tree on 47871/47872
(this MacBook Air, on its battery): pairing and the live socket; `mac.battery` in the snapshot
(`{percent: 37, charging: false, onAC: false, minutesLeft: 1200}`, which the glance writes "MAC
37%"); a `{t: "battery"}` message about half a minute later as the level fell to 36%, which updated
Lens' snapshot; every session carrying `agentsRunning`; a session's last turn (`turn=last`) and its
agents through `GET /v1/sessions/{id}/agents`. Each check unpaired itself afterwards.

Telescope, 2026-10-16, against a daemon from this tree on 47771/47772 with a "[Hypernova test]"
transcript of its own (a chart written, the app installed with `adb -s emulator-5554`) and the
Android emulator running: picked by itself, the emulator (`auto: true`, 1260 × 2800 in about 0.9 s,
named by its AVD); `image`, the chart the transcript wrote (an SVG drawn at 1600 × 800); `terminal`
409 `no_terminal` (not a shared session); `simulator` 501 `no_simulator` (this Mac has only the
Command Line Tools); `web` 404 `nothing_to_show`; `window` 404 `no_app`; `device: "--help"` 400
`bad_device`; `source: "telescope"` 400 `bad_source`. A picture whose project sat inside the
daemon's own support folder was refused, as it should be. The same test daemon paired with the
simulator by a development link: the real emulator's screen on the lens, labelled, and its four
bands readable zoomed in. The emulator showing Hypernova's own app (its sky moves) gives a new
picture every time, which is what the tile check while watching is for. With a transcript in
`mac/` that ran `.build/release/Hypernova`, and that build running (`--feed-selftest`, the Mac app's
live feed without a window) against the same daemon: picked by itself, the window source found the
program by its relative path, asked the Mac app on its feed, which answered that Screen Recording
isn't allowed without asking macOS (it never does when the telescope picks), and the lens got 403
`screen_recording`, "Allow Screen Recording on your Mac first: …".

On a bike or a walk, 2026-10-16, `LiveHub` from Node against a daemon from this tree on 47991/47992
with its own home folder (so Nebula and its notes stayed in the scratch folder): paired, live on the
socket; two notes, one with a project and one without, which the daemon wrote as `- 03:31 ·
hypernova · comets should fade faster at night` and `- 03:31 · no project this time` under `##
2026-10-16` in `~/Programming/Nebula/notes.md` (the `# Notes` heading made with the file); an empty
note 400 `empty`; the mode and continue routes answered 404 `no_session` for a session that doesn't
exist, as Lens reads them. Unpaired itself afterwards.

## 13. Through the Hypernova app on the phone

The Even App serves Lens from `http://127.0.0.1:<port>`, and the Hypernova Android app listens on
`127.0.0.1:47380` (PROTOCOL.md, "The phone bridge", has the whole contract). The public build's
whitelist is only `http://127.0.0.1:47380/` and `ws://127.0.0.1:47380/` (`scripts/whitelist.mjs`;
`LENS_HOSTS` adds a Mac's own address for a private build, which then also offers pairing directly).

Any app on the phone can take 47380 while Hypernova doesn't hold it, and keep relaying a connection
it accepted after Hypernova has the port back, so Lens never sends anything there that's worth
having, and nothing it sends or gets can be read on the way: every call is signed, and its body,
its whole answer and every socket message are sealed under a key of that call's own (protocol 3;
`src/data/bridge.ts`, `src/data/seal.ts`, `src/core/crypto.ts`; the numbers are in PROTOCOL.md):

**Finding the app and asking for access** (`src/data/link.ts`, `PhoneLink`):
- `GET /bridge/ping`, 1.5 s head and body, 16 KB at most, with a challenge for the key Lens keeps
  (`?kid=&n=`). Nothing (or not `app: "hypernova"`): with a pairing of its own and a build that may
  reach a Mac, Lens uses it as before; else the lens says "Open Hypernova on your phone" and looks
  again every 4 s (at once when the page comes back on screen). `bridge` under 4: "Update Hypernova
  on your phone", nothing signed for it, looked at again every 4 s. A page without WebCrypto
  (not a secure context: a dev server opened by a LAN address) can't seal: "Lens can't connect from
  this page".
- A token kept from before (the Even App's storage, `lens.bridge`) is used only when the ping's
  `proof` shows the listener holds its key; then `GET /bridge/settings`, signed. Not proved (the app
  disconnected it, or another app holds the port and can only say it doesn't know it): the lens says
  "Disconnected on your phone · Tap to connect again" and keeps the token (the app proves it again
  once it's back); a tap, or "Ask again", asks.
- The ask agrees a token, never sends one: `POST /bridge/request {name: "Hypernova Lens", version, key,
  commit, bridge: 4}` (Lens's X25519 key, a commitment to its nonce, the protocol it speaks) → `{id,
  key, nonce}` (the app's; 400 `update_lens` for a Lens of an older protocol: "Update Hypernova Lens",
  asked again only on a tap), then `POST /bridge/request/{id} {nonce}`. Each side works out the
  six-digit code and the token by itself: the lens says "Allow it on your phone" with `Code   392 602`
  on a line of its own (two threes, as the phone's prompt shows it), and the phone's status card shows
  the same code and points at Hypernova's notification (no Open button: it can't work before Allow). Lens polls `GET /bridge/request/{id}` once a second:
  `allowed` brings `confirm` (good only when it's made with the agreed key) and nothing else (the
  poll is unsealed: the settings come by a signed, sealed `GET /bridge/settings` after the tap), and
  the lens asks "Allowed on your phone? Tap to connect" (Connect on the phone screen; a tap counts 1.5 s
  after it shows, and only for Hypernova's own prompt): only that tap keeps the new token, in place of a kept one (whatever holds the port could say "allowed" itself; it can't
  make the owner have seen the question in Hypernova). The tap is awaited 10 minutes, as long as the
  app keeps an allowed ask to collect; none in that time is "Not confirmed on the glasses" (not "not
  answered": it was allowed). `denied`, `expired` (also 5 s past the ask's two minutes) and that one
  wait for a tap on the glasses or "Ask again" on the phone,
  or use Lens's own pairing when it has one; 429 `too_many_requests` to the ask waits
  `retryAfterSeconds` and asks by itself. Once Lens has shown its nonce, nothing makes it ask again
  by itself.

**Signed and sealed calls** (`BridgeSigner.prepare`, `answered`; `data/seal.ts`): each call carries
`Authorization: HNB3 <kid>.<ts>.<nonce>.<body>.<mac>`, an HMAC with SHA-256(token) over the listener's
id, the method, the path and query, the time, a nonce and the SHA-256 of the body as sent: good
once, with that body, for that listener, for a minute. The body as sent is the JSON sealed
(AES-256-GCM, WebCrypto) under the call's key (HMAC of the same key over the listener id and the
nonce), and the answer comes back as an outer 200 `X-Hypernova-Bridge: sealed` whose body is the
real status, headers and body sealed under the same key, in records with an end marker: Lens opens
it whole or takes nothing (a relay sees ciphertext and a 200, and can't change, replay or cut a
thing). A socket carries its signature as `?hnb=`, and every message on it goes sealed both ways
(`sealedSocketClass`: a wrapper that seals what Lens sends, opens the app's messages in order, and
reports the close code the app sealed into its end marker, 1006 for a cut). Lens signs only for a
listener that proved the key, and has it prove itself again after 3 s without a word from it,
right before each socket, and after a failed call or a closed feed. 401 `bridge_signature` (the app
opened its port again: a new listener id): a `GET` is proved again and, only when the listener
proves to be a different one than the call was signed for, signed afresh once; a `POST`, `PUT` or
`DELETE` is never sent again by itself (a relay's 401 with the port reopening meanwhile would have
the Mac do it twice): "That didn't reach Hypernova on this phone. Try again." Every unsealed answer (a refusal, the ping) is taken by its code
alone: Lens has fixed words for each and never shows the answer's text, and the ping's `version` is
shown only when it's plainly one (`1.4.0`). 403 `update_lens` (this Lens is older than the app),
on the settings while connecting or on any call later (the hub tells the link): "Update Hypernova
Lens / Hypernova on your phone needs a newer Lens. Update it in the Even app.", on the lens and the
phone card alike, and nothing more is asked.

**The hub through the app** (`src/data/hub.ts`, `LiveHub(pairing, 'bridge')`): the base URL is
`http://127.0.0.1:47380` and every call signed and sealed, so the socket is
`ws://127.0.0.1:47380/v1/ws?hnb=…` (sealed) and speech to text `ws://127.0.0.1:47380/v1/stt/ws?hnb=…`
(8, the glasses' audio sealed frame by frame); Telescope's pictures are asked for by name inside a
sealed body (`POST /bridge/shot {name}`), come back sealed and are shown from a `blob:` URL (3), so
no name is ever on the wire; a preview's link is rewritten to the app's address (the Mac names its own). The app carries a preview's page without a signature (a frame can't
sign) and in the clear, only to be looked at, only when Lens opened it through the app itself (its
`POST /v1/preview` there), and Lens shows it in an iframe sandboxed `allow-scripts allow-same-origin
allow-forms` (no top navigation, no popups: if Hypernova is gone, whatever holds its port serves that
page). Only Lens's own origin, `http://127.0.0.1:<port>` as the Even App serves it, may call the app:
a page with any other (`null` among them) gets 403 `bridge_origin` and no CORS.

| The app or the Mac says | Lens |
|---|---|
| `{t: "bridge.settings", settings}` on the socket (first on each feed, then at each change) | the settings are Lens's (`Store.applyRemote`: nothing happens when they didn't change); `guideAsk` newer than the last one seen plays the guide |
| 403 `bridge_access` or `glasses_removed`, a feed closed with 4003 or 4001 (asked over HTTP at once) | "Disconnected on your phone · Tap to connect again"; the token stays until a new one is agreed |
| an answer that doesn't open under the call's key (unsealed, changed, cut off, another key) | not the app's: "Hypernova on this phone isn't answering. Open it."; it proves itself before the next call |
| a socket message that doesn't open, or comes unsealed; a close without the sealed end marker | the socket ends (1006, cut off); the hub proves the app again and reopens |
| 403 `update_lens` (signed), 400 `update_lens` (the ask) | "Update Hypernova Lens / Hypernova on your phone needs a newer Lens. Update it in the Even app."; the hub stops |
| `bridge: 2` on the ping | "Update Hypernova on your phone"; nothing is signed for it |
| 401 | "Hypernova on this phone isn't paired with your Mac any more" |
| 503 `not_paired`, 502 `mac_unreachable` | the app's message, offline; the hub keeps retrying |
| no answer at all | "Hypernova on this phone isn't answering. Open it."; with the Mac's sessions already on the glance, they stay at rest ("offline since 14:32") under a band, "HYPERNOVA STOPPED / Open Hypernova on your phone" |
| `newSession: {providerId, model}` in the settings (the phone's new-session sheet remembers them) | new sessions from the glasses send them in `POST /v1/sessions` (10), while the Mac still lists that provider; missing (an app from before it), the Mac's defaults |

**Settings.** Read aloud, the blip, how text arrives and its pace, pop-ups, moving, eclipse, which
sessions the glance shows, the speech language and the type on the lens (`look`: Hypernova's own,
drawn, or the built-in font) live in the app (Settings → Glasses); Lens takes them only sealed and
checks every field. A change the
glasses make by voice ("mute", "moving", "eclipse") is `PUT /bridge/settings` (only those keys). Lens's
phone screen through the app is one status card (`src/phone/status.ts`): "Connected to Hypernova ✓",
the Mac and the route, or what's missing, with Open Hypernova, Ask again and Disconnect (`DELETE
/bridge/access`). **Open Hypernova** is `POST /bridge/open {screen: "glasses"}` (signed and sealed):
the Even App's web view hands no `hypernova://` (or `intent://`) link to Android and its SDK has no
way to open another app, so Lens asks the app, which opens at Settings → Glasses when it's on screen
(`shown: "app"`), else puts up a heads-up notification, "Tap to open Hypernova" (`"notification"`),
or can do neither (`"nothing"`: its notifications are off). Lens then says, in its own words, "Tap
“Tap to open Hypernova” in your notifications." (or "Open Hypernova on your phone: Settings →
Glasses."); without access, with no app, or with an app from before the call (its unsealed 404),
it asks nothing and says "Open Hypernova on your phone." The full page (the mirror, the settings,
pairing) is for sample data and Lens's own pairing.

**The daemon** sees the glasses as before, through the phone (PROTOCOL.md, "The glasses through the
phone"): the Mac's device list says "Even glasses · <phone>", the Lens guide opens the first time,
read-aloud plays on the glasses' own phone, and "Forget" on the Mac disconnects them on the phone.

`test/bridge.test.ts` holds Lens to this: the protocol's and the seal's numbers (against
node:crypto, and the same vectors as the app's tests), discovery and an older app, agreeing access
and its answers, signed and sealed calls, a squatter and a relay on the port (which reads only
ciphertext and can't change, cut or replay a thing), the fallback, the settings both ways, Open
Hypernova, the hub through the app with sealed sockets.

## 14. Automation asks

A workflow's `ask` step pauses a run for a person (PROTOCOL.md, "Workflows", "Answering an ask"). The
daemon sends the glasses a note `{t: "note", note}` with `to: "glasses"`, the question as its `text`
and `ask: {runId, stepId, question, answers, default, until}` set. A Mac without workflows sends none,
and nothing changes in Lens.

| What | Lens |
|---|---|
| a note with `ask` (live, or in `snapshot.automations.notes`) | a card to answer, once per `runId:stepId`; not a note to read. Kept from the snapshot while `ask.until` is ahead, however old the note is |
| an `ask` with under 2 answers, no `runId`, `stepId` or `until` | ignored; more than 3 answers: the first 3; an unknown `default`: the last answer |
| `{t: "run", run}` | `run.ask` null (or another step) closes the card of that run; the step's `answer: {text, by}` says how |
| `POST /v1/automations/runs/{runId}/answer` `{stepId, answer}` | the wearer's tap or spoken answer, the answer's own text. The daemon takes the answer as the glasses' (`X-Hypernova-Via: lens` through the phone, so `answer.by` is `glasses`); nothing more is sent |
| 409 `not_asking` | someone was first (the error's `run` says who): "Already answered / Approve, on your phone", then the card closes |
| 404 `no_run`, 400 `bad_answer` | said in words (`lensWords`) and the card stays |
| `ask.until` passes | "Took the default: Skip" (or what `answer.by: "timeout"` says), then the card closes |

**On the lens** (`src/lens/asks.ts`, the controller's `ask` screen, a choice card like a question's).
`ASK   Review before merge` over the question (two lines, firmware text), the answers (the default marked
`(default)`), and in the header's corner the default with its clock in one line, `Skip in 9m`, `Skip in
40s`, `Skip now`. Swipe chooses, tap answers, double tap sets it aside: it stays as a band (`ASK`, tap
opens) until the default is taken. On the glance it takes the lens at once, as an approval does; over
any other screen it is a band. Eclipse lets it through, as it does what needs you, and the phone's
switch **A session needs you** governs it. With reading aloud on it is read out ("Review before merge
asks: Post this review? Approve, or Skip."). Voice: saying an answer's own words ("skip", "approve it",
"post"), or its number ("one", "the second"), answers it; a sentence that only contains one does not.
The phone screen's mirror of the lens shows the card like any other.
