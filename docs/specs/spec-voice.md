# Spec: voice on the phone, and voice in Mac setup

Status: decided, ready to build. Builds on the Soniox work in
`.claude/worktrees/wf_b27fc756-9f4-2` (daemon `stt.ts`, `/v1/stt*`, the Mac's Lens guide), which is
merged first. Mandate: DECISIONS.md, "The owner's second round of notes", the voice bullet: *voice is
offered in the Mac app's setup; once set up, the phone's composer gets a mic; speech is reviewed
before sending ("Review it, but it should be done fast"): captions while you hold, on release the
text is in the box with Send one tap away. No confirm step, no wait.*

The bar: you open a session, press the mic, talk, let go, and your words are already in the box
before you've finished looking down.

---

## 1. Decisions in one place

| # | Decision |
|---|---|
| D1 | **Setup asks.** The Mac Setup gets a **Voice** step (6 of 7), between Tailscale and Your phone, so a phone that pairs already has its mic. Three ways out: connect Soniox (paste key, checked), use Hyperscribe Companion (found automatically), or **Not now**. |
| D2 | **One voice panel on the Mac.** The guide window's Soniox card is pulled out into a shared `VoicePanel` + `VoiceModel`; Setup and the Lens guide both show it. |
| D3 | **The Lens guide skips Voice when voice is ready** at the moment the guide opens: two steps, Gestures ends with Done. The header chip "Voice ready" still opens the voice step. |
| D4 | **The phone's mic** shows where Send is, while the box is empty (no text, no files, nothing uploading) and the Mac offers voice: `stt.ready` (Soniox through the daemon), else the Companion's address from `GET /v1/speech/companion` (the phone streams straight to it; same protocol). |
| D5 | **Hold to talk, tap for hands-free, on the same button.** The mic opens on touch-down. Let go after ≥ 350 ms: done. Let go sooner: it keeps listening (hands-free) until Done. Slide 80 dp away while holding: cancel. |
| D6 | **The text is in the box at once.** On release the box gets everything heard so far immediately (the unsettled tail drawn dim); Soniox's settled text replaces it silently when it lands (target p50 ≤ 300 ms). Send works at once; if tapped before the text settles it waits for it (≤ 1.5 s). The keyboard is never opened or closed by voice. |
| D7 | **Add more by holding Send.** With text in the box, a long press on Send (400 ms) dictates and appends; a tap still sends. |
| D8 | **New-session sheet:** a mic at the end of the PROMPT seam line; while listening, the prompt field becomes the capsule; on release the text lands in the field. Always appends. |
| D9 | **Mic source:** the phone's own mic (best quality, instant). A Bluetooth headset mic is opt-in (Settings, Android 12+), because SCO takes ~0.5–1 s to come up and drops music quality. A wired/USB headset mic is used automatically by Android. |
| D10 | **Battery:** the mic, the stream and any headset route exist only between press and the end. Leaving the app ends listening (words kept). No foreground service, no pre-warming. |
| D11 | **Phone Settings → Voice:** state, key's last four, Language (Auto by default), Headset microphone, and paste/remove a Soniox key (the API already allows it from a paired device). |
| D12 | **Existing installs** (setup already finished) get one quiet popover notice on the Mac, "Talk to your sessions: set up voice · Set up", until voice is ready or they choose Not now. |
| D13 | **No sounds** (design rule). Haptics mark start, cancel-armed, done, cancel. TalkBack hears nothing while the mic is open (it would be transcribed), then one announcement with the text. |
| D14 | **The Mac doesn't listen.** No "hold to try" with the Mac's microphone in Setup (no mic permission on the Mac). The key check is Soniox's silent probe, as built. |

---

## 2. Protocol

No new routes. The contract below is what already exists after the merge, plus two daemon changes
(2.4). PROTOCOL.md, "Speech to text", is updated to say Android uses it too.

### 2.1 Voice available? (snapshot)

```jsonc
// snapshot.stt, and {t:"stt", stt} when it changes
"stt": { "provider": "soniox", "ready": true, "error": null }
// older daemon: no "stt" field → the phone shows no mic
```

The phone's route (computed in `LiveHub`, recomputed on every snapshot and `{t:"stt"}`):

```
stt.ready == true                         → Soniox     (wss://<base>/v1/stt/ws, device token)
else GET /v1/speech/companion → {url}     → Companion  (that url, as given)
else                                      → None       (no mic)
```

`GET /v1/speech/companion` is asked only while `stt.ready` is false: on each (re)connect snapshot and
each `{t:"stt"}`, at most once a minute, cached.

### 2.2 One dictation (phone ↔ daemon, Companion protocol v1)

```
phone  → GET /v1/stt/ws            Authorization: Bearer <device token>   (opened at touch-down)
mac    ← {"type":"hello","ok":true,"name":"Hypernova","version":"…","model":"stt-rt-v5",
          "modelLabel":"Soniox","languages":"*","busy":false}
phone  → {"type":"start","language":"auto"}         // sent on open, not after hello
phone  → binary: 1280 B = 40 ms of 16 kHz s16le mono, from the local queue, then live
mac    ← {"type":"started"}
mac    ← {"type":"snapshot","finalText":"Add a dark","interimText":"mode tog","finished":false}
phone  → {"type":"stop"}                             // 100 ms after release (the tail)
mac    ← {"type":"snapshot","finalText":"Add a dark mode toggle to settings.","interimText":"","finished":true}
         close 1000
errors ← {"type":"error","code":"unreachable","message":"…"}                       // before started
       ← {"type":"error","code":"lost","message":"…","finished":true,"finalText":"…","interimText":""}
cancel → the phone closes the socket (1000) without "stop"
```

`language`: `"auto"` (daemon hints en + ru) or the code picked in Settings (`"en"`, `"ru"`, …).
The phone shows words as `finalText + " " + interimText` (the daemon splits between words).
`hello.busy == true` (Companion only) is the `busy` problem.

### 2.3 Key from the phone (Settings)

```
GET    /v1/stt      → {provider, ready, error, last4, model, savedAt, keysUrl}
POST   /v1/stt/key  {"provider":"soniox","apiKey":"…"} → same + {checked}   (400 no_key | bad_key)
DELETE /v1/stt/key  → same
```

### 2.4 Daemon changes (`daemon/src/stt.ts`)

1. **A stop while Soniox is still connecting keeps the audio.** Today `stop` in `connecting` finishes
   at once with nothing, dropping the held audio (a quick "yes" over a slow link comes back empty).
   Now: remember the stop; when Soniox opens, send the config, the held audio, then the end frame,
   and finish as usual (`finishMs`). A connect failure still errors (`unreachable`).
2. **The log line measures the release.** `stt stream <who> 3.8s done 240ms`: the last figure is the
   time from `stop` to the final words leaving for the client. Never words or audio.
3. Device-neutral words: `idle` → "No sound from the microphone for a while, so listening stopped."
4. **Experiment, decided by numbers:** if the phone's p50 (section 7, phone test 1) is over 300 ms, try sending Soniox
   `{"type":"finalize"}` just before the end frame (the `<fin>` marker is already dropped). Keep
   whichever is faster over 20 holds; note the result in the PR.

---

## 3. Phone

### 3.1 The mic button (session composer)

The 44 dp circle in the 48 dp target where Send sits.

| Box | Button |
|---|---|
| empty, route ≠ None, linked | **Mic**: frosted glass circle (`frostedGlass(glow = accent)`), mint hairline mic glyph |
| empty, route ≠ None, not linked | Mic, dim glyph, no glow; press → hint "Not connected to your Mac." |
| empty, route None | Send, disabled (today's look) |
| text or files | Send (today's), plus long-press → dictate and append (D7) |
| listening (hold) | Mint-filled circle, bg-colour mic glyph, scale 1.12, an outer halo ring that swells with the voice level |
| listening (hands-free) | Mint-filled circle with the Check glyph (tap = Done) |
| settling with no words yet | Glass circle with a small spinning planet (≤ 3 s) |

Send → Mic and back cross-fade (Motion.FAST). The composer never moves while a finger is down: the
capsule is added above it in the same bottom column (like the attach tray), and the column is
bottom-anchored.

### 3.2 The gesture (one reducer, `MicGesture`)

```
Idle ─down──────────► (no permission) request it, stay Idle
     ─down──────────► Holding     dictation = hub.dictate(); haptic GESTURE_START
Holding ─drag > 80dp► Armed       haptic CLOCK_TICK; capsule turns to cancel
Armed   ─drag < 64dp► Holding     (hysteresis)
Holding ─up ≥ 350ms─► finish()    haptic CONFIRM
Holding ─up < 350ms─► HandsFree
Holding ─pointer cancelled─► finish()     (never lose words to a system gesture)
Armed   ─up─────────► cancel()    haptic REJECT
HandsFree ─tap button | Done | Back | app stops─► finish()
HandsFree ─Cancel───► cancel()
Send-with-text ─up < 400ms─► send (today)
Send-with-text ─held 400ms─► Holding (append; no hands-free from here)
any ─dictation Ended/Cancelled─► Idle
```

A new press while the previous dictation is still settling commits that one's text as it stands,
then starts.

### 3.3 Mockups

Legend: `(ı)` mic, `(⌀)` paperclip, `(➤)` send, `(✓)` check; the ringed planet is drawn as
`.-~~~-.`/`(  (●)  )`/`` `-~~~-´ ``; `▍` the mint caret; *dim* = inkMuted.

**Rest, voice ready**
```
│ ...transcript                                      │
│ ╭────────────────────────────────────────────────╮ │
│ │ (⌀)  Message this session…               ( ı ) │ │
│ ╰────────────────────────────────────────────────╯ │
```
First time ever the mic shows, a one-time hint capsule sits above it for 6 s (tap dismisses):
`Hold the mic to talk, or tap it for hands-free.`

**Opening (first 0.3–0.8 s): the rings already move with your voice (local level)**
```
│  ╭──────────────────────────────────────────────╮  │
│  │  .-~~~-.    LISTENING ─────────────────  0:00 │  │
│  │ (  (●)  )   Listening…                        │  │
│  │  `-~~~-´                SLIDE AWAY TO CANCEL  │  │
│  ╰──────────────────────────────────────────────╯  │
│ ╭────────────────────────────────────────────────╮ │
│ │ (⌀)  Listening…                         ((●))  │ │
│ ╰────────────────────────────────────────────────╯ │
```

**Holding, words arriving** (settled in ink, *still changing* dim, caret at the end)
```
│  ╭──────────────────────────────────────────────╮  │
│  │  .-~~~-.    LISTENING ─────────────────  0:07 │  │
│  │ (  (●)  )   Add a dark mode toggle to the     │  │
│  │  `-~~~-´    settings screen and make it       │  │
│  │             *follow the system*▍              │  │
│  │                         SLIDE AWAY TO CANCEL  │  │
│  ╰──────────────────────────────────────────────╯  │
```

**Armed to cancel** (finger 80 dp+ away): the glass light and planet go danger-red, meridians stop,
captions dim
```
│  ╭──────────────────────────────────────────────╮  │
│  │  .- - -.    LET GO TO CANCEL ──────────  0:09 │  │
│  │ (  (×)  )   *Add a dark mode toggle to the*   │  │
│  │  `- - -´    *settings screen and make it…*    │  │
│  ╰──────────────────────────────────────────────╯  │
```

**Hands-free**
```
│  ╭──────────────────────────────────────────────╮  │
│  │  .-~~~-.    HANDS-FREE ────────────────  0:42 │  │
│  │ (  (●)  )   …then run the tests and tell me   │  │
│  │  `-~~~-´    *what failed*▍                    │  │
│  │  ( Cancel )                        ( Done )   │  │
│  ╰──────────────────────────────────────────────╯  │
│ ╭────────────────────────────────────────────────╮ │
│ │ (⌀)  Listening…                          ( ✓ ) │ │
│ ╰────────────────────────────────────────────────╯ │
```
From 1:45 the time reads `0:15 LEFT` in amber; at 2:00 it ends like Done.

**Released**: the capsule sinks into the composer (180 ms) as the text fades in; the dim tail turns
ink when the settled words land
```
│ ╭────────────────────────────────────────────────╮ │
│ │ (⌀)  Add a dark mode toggle to the settings    │ │
│ │      screen and make it *follow the*   ( ➤ )   │ │
│ │      *system*                                  │ │
│ ╰────────────────────────────────────────────────╯ │
```
After the first successful dictation, once: hint `Hold Send to add more.`

**A problem** (one line of dense glass, amber dot, 4 s or tap; words heard are already in the box)
```
│     ╭────────────────────────────────────╮         │
│     │ ●  Your Mac couldn't reach Soniox.  │         │
│     ╰────────────────────────────────────╯         │
│ ╭────────────────────────────────────────────────╮ │
│ │ (⌀)  Message this session…               ( ı ) │ │
```

**Microphone permission** (same hint shape, with an action)
```
│  ╭──────────────────────────────────────────────╮  │
│  │ ●  Allow the microphone to talk to your       │  │
│  │    sessions.                       ( Allow )  │  │
│  ╰──────────────────────────────────────────────╯  │
```

**New-session sheet**
```
  PROMPT ─────────────────────────────────────  ( ı )
  ╭──────────────────────────────────────────────╮
  │ What should Claude do?                        │
  │                                               │
  ╰──────────────────────────────────────────────╯
  holding: the field becomes the capsule, the mic stays under the finger
  PROMPT ─────────────────────────────────────  ((●))
  ╭──────────────────────────────────────────────╮
  │  .-~~~-.    LISTENING ─────────────────  0:05 │
  │ (  (●)  )   Make a landing page for 0stems    │
  │  `-~~~-´    *with the starfield*▍             │
  ╰──────────────────────────────────────────────╯
```

**Settings → Voice**
```
  VOICE ──────────────────────────────────  ● READY
  ╭──────────────────────────────────────────────╮
  │ Soniox                              ••••a1b2  │
  │ Hold the mic in a session to talk. The key    │
  │ stays on your Mac.                            │
  │ ───────────────────────────────────────────── │
  │ Language                              Auto ›  │
  │ Headset microphone                      ( ○)  │
  │ ───────────────────────────────────────────── │
  │ Remove key                                    │
  ╰──────────────────────────────────────────────╯
  off:      "Talk to your sessions: connect Soniox here or in Hypernova on your Mac."
            API KEY [ Paste your Soniox key ]   ( Connect )   Get a key ↗
  error:    the Mac's Stt.error in amber, then the key field ("Paste a new key")
  companion:"Hyperscribe Companion on your Mac" · FOUND, and "Connect Soniox instead" under it
```
Language choices: Auto (the daemon hints English and Russian), then each language in the phone's
`LocaleList` (deduplicated, at most four), shown by their own names ("English", "Русский").

### 3.4 The capsule (`DictationCapsule`)

- Same insets and 24 dp radius as the composer; GlassCard fill (78–86%, nothing reads through),
  top highlight, and a soft mint light inside the glass around the planet (`frostedGlass` glow).
- Left: a 40 dp canvas, the **ringed planet**. Right: seam-style header (label + hairline + `0:07`
  mono 11 inkDim), captions (body 15, final ink, interim inkMuted, mint 2 dp caret blinking on the
  ambient clock), footer (hint label ghost, or Cancel/Done pills in hands-free).
- Height grows with the words to 5 lines, then the oldest line fades under a 24 dp top mask and the
  text keeps to the bottom. Never taller than the space above the composer minus the top bar.
- **The planet** (`planetSphere`, r 6.5 dp, mint, meridians turning 1 turn/3 s; 0.4× while Opening).
  Two rings tilted −18°, aspect 0.34, base radii 11 and 15 dp; radius × (1 + 0.18 L) and
  (1 + 0.32 L); alpha 0.35 + 0.45 L and 0.18 + 0.40 L; the back half drawn before the planet, the
  front half after, so the rings pass in front of it. On a syllable onset (L rises > 0.22 within
  80 ms, L > 0.3) an echo ring leaves the outer ring, 15 → 22 dp, alpha 0.45 → 0 over 700 ms (three
  fixed slots, no allocation). A glow behind, radius 18 dp, alpha 0.10 + 0.18 L.
  Armed: colour eases to danger in 120 ms, meridians stop. Problem: amber. Settling: the rings
  contract into the planet as the capsule sinks.
- `L` is the dictation's level (3.6), read **only in draw**, on the shared ambient clock: ticks never
  recompose. The captions recompose only when a snapshot arrives; the clock text once a second.
- Enter: from the button's corner, scale 0.6 → 1, alpha 0 → 1, 220 ms Motion.Expo. Cancel: shrink
  back into the button, 160 ms.
- Reduce Motion: no spin, no echoes, fixed ring radii, ring alpha in 4 steps of L, fades instead of
  scale.

### 3.5 Copy (`strings.xml`)

| id | text |
|---|---|
| voice_mic | Dictate |
| voice_listening | Listening |
| voice_hands_free | Hands-free |
| voice_waiting | Listening… |
| voice_slide_cancel | Slide away to cancel |
| voice_release_cancel | Let go to cancel |
| voice_left | %1$s left |
| voice_cancel / voice_done | Cancel / Done |
| voice_hint_first | Hold the mic to talk, or tap it for hands-free. |
| voice_hint_more | Hold Send to add more. |
| voice_nothing | Didn't catch that. |
| voice_perm | Allow the microphone to talk to your sessions. |
| voice_perm_allow | Allow |
| voice_perm_off | The microphone is off for Hypernova. |
| voice_perm_settings | Settings |
| voice_err_offline | Not connected to your Mac. |
| voice_err_no_key | Voice isn't set up on your Mac. |
| voice_err_bad_key | Soniox didn't accept the key. Fix it in Settings. |
| voice_err_no_credit | Your Soniox balance is used up. |
| voice_err_busy | Soniox is busy. Try again in a moment. |
| voice_err_unreachable | Your Mac couldn't reach Soniox. |
| voice_err_soniox | Soniox stopped listening. Try again. |
| voice_err_too_long | Two minutes at most at a time. |
| voice_err_mic_busy | Another app is using the microphone. |
| voice_err_mic | The microphone stopped. Try again. |
| voice_err_companion | Couldn't reach Hyperscribe Companion on your Mac. |
| voice_err_companion_busy | Hyperscribe Companion is busy. |
| voice_a11y_listening | Listening |
| voice_a11y_stop / voice_a11y_cancel | Stop dictating / Cancel dictating |
| voice_a11y_more | Dictate more |
| voice_a11y_ready | Ready to send: %1$s |
| settings_voice | Voice |
| settings_voice_ready / _off / _found | Ready / Off / Found |
| settings_voice_body | Hold the mic in a session to talk. The key stays on your Mac. |
| settings_voice_off_body | Talk to your sessions: connect Soniox here or in Hypernova on your Mac. |
| settings_voice_key / _key_hint / _key_new | API key / Paste your Soniox key / Paste a new key |
| settings_voice_connect / _checking | Connect / Checking… |
| settings_voice_get_key | Get a key ↗ |
| settings_voice_remove | Remove key |
| settings_voice_language / _auto | Language / Auto |
| settings_voice_headset | Headset microphone |
| settings_voice_headset_body | Uses a Bluetooth headset's microphone when one is connected. It takes a moment to switch on. |
| settings_voice_companion | Hyperscribe Companion on your Mac |
| settings_voice_soniox_instead | Connect Soniox instead |

Daemon codes → problem: `no_key`→no_key, `bad_key`→bad_key, `no_credit`→no_credit, `busy`→busy,
`unreachable`→unreachable, `soniox`/`lost`→soniox, `too_long`→too_long, `idle`/`bad_audio`/`replaced`
→mic. Socket failure before any words → offline (Companion route: companion). Ended with no words
and no error → voice_nothing (2.5 s, dim dot).

### 3.6 Audio and streaming (`LiveDictation`)

- `AudioRecord(VOICE_RECOGNITION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, max(minBuf, 4×1280))`,
  started at touch-down on a thread at `THREAD_PRIORITY_URGENT_AUDIO`, read in 640-sample frames.
  Init failure or `ERROR_INVALID_OPERATION` → `mic_busy`.
- Each frame: RMS → dBFS → `L = clamp((db + 60) / 50)`, smoothed (attack τ 30 ms, release τ 180 ms),
  stored in a `@Volatile` float.
- The socket opens at touch-down (`Api.socket("/v1/stt/ws")`, or `Net.client.newWebSocket` with the
  Companion's url). Until it's open, frames queue locally (cap 10 s, oldest dropped). On open:
  `start`, the queue, then live frames.
- **Finish:** keep capturing 100 ms (the tail: the last syllable), stop and release the recorder,
  send the rest and `stop`. State → `Settling(final + " " + interim)` at once. `finished: true` →
  `Ended(finalText)`. No answer in 1.5 s → `Ended(optimistic)` and close.
- **Cancel:** stop the recorder, close the socket 1000 without `stop`, `Cancelled`.
- An error with `finalText` → `Ended(text, problem)`: words are never lost. Socket failure → the
  words so far, plus `offline`.
- Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` (usage ASSISTANT, content SPEECH) while
  listening, so music pauses; abandoned at the end. Focus loss (a call) → finish.
- Headset (opt-in, API 31+): capture starts on the phone's mic at once; meanwhile
  `setCommunicationDevice` (first BLE headset, else SCO) with mode `IN_COMMUNICATION`; when
  `OnCommunicationDeviceChangedListener` confirms it (≤ 1.5 s, else stay on the phone mic), swap to
  a new `AudioRecord(VOICE_COMMUNICATION)` with that preferred device, same stream. At the end:
  `clearCommunicationDevice`, mode restored.
- Max 2 min (the daemon's cap); the UI counts down from 1:45. Mobile data: 32 KB/s (3.8 MB for
  2 min) of raw PCM.
- Privacy: nothing written to disk or logged except, in debug builds, `voice release→ended 243ms`.
  Android's own mic indicator shows while listening.
- Demo (`FakeHub`): no mic, no permission; a canned phrase ("Add a dark mode toggle to settings,
  and make it follow the system.") arrives at three words a second with a synthetic level, ends in
  120 ms.

### 3.7 Data API (`data/VoiceApi.kt`, verbatim, Track B's first commit)

```kotlin
package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/** The Mac's speech to text (snapshot `stt`). */
@Immutable data class Stt(val provider: String?, val ready: Boolean, val error: String?)
/** `GET /v1/stt`: the state plus the key's last four characters. */
@Immutable data class SttInfo(val stt: Stt, val last4: String?, val keysUrl: String?)

enum class VoiceRoute { None, Soniox, Companion }
@Immutable data class Voice(val route: VoiceRoute, val stt: Stt? = null)

enum class VoiceProblem { Offline, NoKey, BadKey, NoCredit, Busy, Unreachable, Soniox, TooLong, MicBusy, Mic, Companion, CompanionBusy }

sealed interface DictationState {
    /** Mic on, link to the Mac opening. */
    data object Opening : DictationState
    data class Live(val final: String, val interim: String) : DictationState
    /** Released: [text] is everything heard; from [unsettledFrom] on it may still change. */
    data class Settling(val text: String, val unsettledFrom: Int) : DictationState
    /** The end. [text] may be empty; [problem] is why it ended early, if it did. */
    data class Ended(val text: String, val problem: VoiceProblem? = null) : DictationState
    data object Cancelled : DictationState
}

interface Dictation {
    val state: StateFlow<DictationState>
    /** SystemClock.elapsedRealtime() at touch-down. */
    val startedAt: Long
    /** 0..1, the voice level now; cheap; read it only while drawing. */
    fun level(): Float
    fun finish()
    fun cancel()
}

object VoiceTiming {
    const val HOLD_MS = 350L; const val SEND_HOLD_MS = 400L; const val TAIL_MS = 100L
    const val SETTLE_MS = 1_500L; const val MAX_MS = 120_000L; const val COUNTDOWN_MS = 105_000L
    const val CANCEL_DP = 80; const val UNCANCEL_DP = 64
}
```

`Hub` gains (all defaulted, so nothing else breaks):

```kotlin
val voice: Voice get() = Voice(VoiceRoute.None)
/** Opens the mic now (RECORD_AUDIO must be granted). Null when [voice] has no route. */
fun dictate(language: String = "auto", headset: Boolean = false): Dictation? = null
suspend fun sttInfo(): Result<SttInfo> = Result.failure(HubException("Not in the demo."))
/** [apiKey] null removes the key. Failures carry the daemon's words. */
suspend fun setSttKey(apiKey: String?): Result<SttInfo> = Result.failure(HubException("Not in the demo."))
```

Prefs (`VoicePrefs`, in `data/Voice.kt`): `language` ("auto"), `headset` (false), `dictations`
(count, for the one-time hints), `firstHintShown`, `moreHintShown`.

### 3.8 Edge cases

| Case | Behaviour |
|---|---|
| Permission not yet asked | First press asks (system dialog); nothing records. Granted: the first-use hint shows. |
| Denied / denied for good | Permission hint with Allow / Settings (`shouldShowRequestPermissionRationale` false after a denial → Settings). |
| Revoked while running | Recorder fails → `mic` hint; next press asks again. |
| Link drops mid-dictation | Ends with the words so far in the box + `offline`. |
| Key removed / refused on the Mac mid-dictation | Daemon error with words → box + hint; `{t:"stt"}` hides the mic. |
| App leaves the foreground, screen off, a call | finish() — words kept. |
| Back while listening | Hands-free: Done. Holding: ignored (the finger is down). |
| Navigating away while listening | cancel(). |
| Text typed during hands-free | The dictation appends to it with a space. |
| User edits during Settling | The settled text doesn't overwrite an edited box. |
| Keyboard open at press | Left open; the capsule fits above the composer. |
| Files attached / uploading | No mic (Send / spinner as today); hold Send appends. |
| Two-minute cap | Ends like Done, `too_long` hint. |
| Nothing said | Box unchanged, "Didn't catch that." |
| Companion route, Companion closed | `companion` hint; the route is fetched again on the next snapshot. |
| Older daemon | No `stt` → no mic, no Settings section. |

### 3.9 TalkBack

- The button: role Button, description "Dictate"; state description "Listening" while active.
  Double-tap = start hands-free / Done. Custom actions while active: "Stop dictating", "Cancel
  dictating". On Send with text: custom action "Dictate more". Double-tap-and-hold works as
  push-to-talk (it's a long press).
- While the mic is open, nothing is announced and the capsule isn't a live region (speech would be
  transcribed); start and end are felt (haptics).
- At the end: one polite announcement, "Ready to send: <text>", and focus stays on the button (now
  Send).

---

## 4. Mac

### 4.1 Setup's Voice step

`SetupModel.Step`: `claude, node, service, connect, tailscale, voice, phone` ("Step 6 of 7").
Rail title "Voice"; trail `optional` / `Soniox` / `Companion` / `skipped`; done when
`VoiceModel.ready` or `Defaults.voiceAsked`; busy while saving or testing; needs attention while
`stt.error` is set. Needs the background service (`NeedsService` otherwise).

```
┌─────────────────────────┬──────────────────────────────────────────────────────────┐
│ ✓ Claude Code     v2.1  │  ◉ STEP 6 OF 7 ───────────────────────────────── VOICE   │
│ ✓ Node.js         v24   │                                                          │
│ ✓ Background service    │  Voice.                                                  │
│ ✓ Connect Claude Code   │  Talk to your sessions from your phone and your glasses: │
│ ✓ Tailscale             │  hold the mic, say what to do, read it, send.            │
│ ● Voice        optional │                                                          │
│ ○ Your phone            │  ╭─ ◎ CONNECT SONIOX ──────────────── real-time speech ─╮ │
│                         │  │ API KEY                                              │ │
│                         │  │ ┌──────────────────────────────────────────────────┐ │ │
│                         │  │ │ Paste your Soniox key                            │ │ │
│                         │  │ └──────────────────────────────────────────────────┘ │ │
│                         │  │ ( Connect )   Get a key at console.soniox.com ↗      │ │
│                         │  │ Hypernova tries the key with a moment of silence     │ │
│                         │  │ before keeping it, so you know it works.             │ │
│                         │  ╰──────────────────────────────────────────────────────╯ │
│                         │  OR  Download Hyperscribe Companion ↗                     │
│                         │      It recognises speech on this Mac itself instead.     │
│                         │                                                          │
│                         │  Not now                                                 │
└─────────────────────────┴──────────────────────────────────────────────────────────┘
connected:
│  ╭─ ◉ SONIOX ─────────────────────────────── connected · ••••a1b2 ─╮
│  │ ✓ Connected. Your phone gets a mic in the composer.              │
│  │ ( Test )  Remove key                                             │
│  ╰──────────────────────────────────────────────────────────────────╯
│                                                         ( Continue )
companion found (no Soniox key):
│  OR  ◉ Hyperscribe Companion is set up on this Mac.  FOUND
│      Your phone and glasses use it while Soniox isn't connected.
│  Not now                                                ( Continue )
```

- Lede: "Talk to your sessions from your phone and your glasses: hold the mic, say what to do, read
  it, send." The card's own copy is the guide's (key stays on this Mac, the silence check).
- While this step is showing, Setup's 2 s poll also runs `VoiceModel.load()`, so a Companion
  installed meanwhile turns up as Found by itself.
- Footer: Not connected → `Not now` (ghost, left; Connect in the card is the primary). Ready →
  `Continue` (primary), no Not now. `Not now` sets `Defaults.voiceAsked` and goes on.
- After Connect, in Setup: "Connected. Your phone gets a mic in the composer." (in the guide it
  stays "Connected. Hold the temple and talk.").
- `DevTools.swift:88` names setup renders by `rawValue + 1`: the phone render becomes `setup-7-phone`.

### 4.2 Shared voice model and panel

- `App/VoiceModel.swift` (`@MainActor ObservableObject`, `shared`): moved from `LensGuideModel` —
  `stt`, `keyDraft`, `saving`, `testing`, `result`, `connect()`, `test()`, `remove()`, `loadStt()` —
  plus `companionURL` (`GET /v1/speech/companion`), `ready` (`stt.ready || companionURL != nil`),
  `load()` (both), `heard(stt)`. `LensGuideModel` keeps the live feed and forwards `stt` to it; its
  `connected()` calls `VoiceModel.shared.load()`.
- `UI/VoicePanel.swift`: the `SonioxCard` and the Companion block from `LensGuideView`, with
  `context: .setup | .guide` for the two lines that differ. `LensGuideView.VoiceStep` and Setup's
  `VoiceStep` both use it.

### 4.3 The Lens guide skips voice

`LensGuideModel.steps` is fixed when the window opens: `[.about, .gestures]` if
`VoiceModel.shared.ready`, else all three. Rail, Next/Done and "1 of 2" follow `steps`. If the
daemon's step is `voice` (the phone asked for it), it's appended. The header's "Voice ready" chip
becomes a button to the voice step (appended likewise). Lens' phone page (`lens/src/phone/guide.ts`)
does the same with `sttReady` or the Companion address it already knows.

### 4.4 Popover and Settings

- Notice (accent): "Talk to your sessions: set up voice" · `Set up` → `showSetup(startAt: .voice)`.
  Shown when the daemon is up, `setupNext == nil`, `!VoiceModel.ready`, `!Defaults.voiceAsked`.
- Settings: a row "Voice…" (`waveform`) under "Lens guide…", opening Setup at the voice step.

---

## 5. Performance and battery

- Nothing runs until a press: no socket, no recorder, no headset route, no clock work. The mic
  button at rest is a static draw.
- While listening: one recorder thread (RMS per 40 ms is trivial), one socket, the capsule's 40 dp
  canvas and caret on the shared 30 fps clock (20 on battery saver). Captions recompose ≤ 10×/s.
- After the end: recorder released, socket closed, focus and route given back, within 1.5 s at most.
- Network: 32 KB/s up while listening; the daemon relays as is.

## 6. Safety and privacy

- The Soniox key never reaches the phone (only `last4`); a key pasted on the phone goes straight to
  the daemon and is never kept or logged on the phone.
- The stream uses the device token; the Companion's url (with its token) is kept only in memory.
- No audio or words are written or logged anywhere (daemon log: durations only).
- Voice never sends anything: the text goes into the box, and only Send sends.

---

## 7. Test plan

**Daemon** (`node --test`, stand-in Soniox via `HYPERNOVA_SONIOX_WS`)
1. A `stop` while the stand-in delays its open by 300 ms: the stand-in receives config, every held
   frame, then the end; the client gets `finished: true` with the words.
2. The log line ends with the stop→final ms; it never contains words.
3. A phone device (paired without `client`) streams exactly as Lens does.

**Android, JVM unit tests**
1. `LiveDictation` with a fake socket and fake audio source: Opening → Live → finish → Settling →
   Ended; Settling times out at 1.5 s with the optimistic text; an error with `finalText` keeps the
   words; cancel never sends `stop`; socket failure keeps the words; the 10 s queue drops oldest.
2. Code → `VoiceProblem` table; `hello.busy` → CompanionBusy.
3. `MicGesture` reducer: every transition in 3.2, including the 80/64 dp hysteresis, pointer cancel
   → finish, Send tap vs. 400 ms hold.
4. Protocol: snapshot with and without `stt`; `{t:"stt"}`; route selection incl. the Companion
   fetch at most once a minute.

**On the Nothing Phone 3** (a second daemon per CLAUDE.md, a real key; prompts titled "[Hypernova test]")
1. Latency: 20 holds of one sentence on Wi-Fi and 10 on mobile data; debug log
   `voice release→ended`; target p50 ≤ 300 ms, p95 ≤ 600 ms (Wi-Fi). Run the finalize experiment
   (2.4.4) if missed.
2. Flows: first permission (allow; deny; deny twice → Settings), hold, tap hands-free, slide cancel
   and slide back, Back in hands-free, home button mid-dictation, airplane mode mid-dictation, key
   removed on the Mac mid-dictation, hold Send to append, new-session sheet, demo mode, Reduce
   Motion, TalkBack (double-tap start/stop, double-tap-and-hold), Bluetooth earbuds with the toggle
   on and off, a wired USB-C headset.
3. Screenshots (adb) of each state in 3.3 for the owner; the composer must not move while holding.
4. Battery: 20 dictations, then `dumpsys batterystats` shows no mic or wakelock after the last.

**Mac**
1. `--render-lens-guide` gains the setup voice step in five states (empty, checking, connected,
   refused, Companion found) and the two-step guide.
2. A fresh Setup on a test copy (`HYPERNOVA_SUPPORT`, stand-in Soniox): Voice step → Connect →
   Continue → pair a phone → the phone's mic appears within a second of pairing.
3. Not now → the step shows `skipped`, no notice; a completed setup without voice shows the notice.

**Owner's checklist** (`qa-checklist.ts`, new section "Voice"): `mac-voice` "Setting up voice on the
Mac is clear"; `ph-voice-hold` "Hold to talk: words appear as you speak and are in the box when you
let go"; `ph-voice-free` "Tap for hands-free, then Done"; `ph-voice-cancel` "Sliding away cancels";
`ph-voice-sheet` "Dictating a new session's prompt"; `ph-voice-more` "Hold Send to add more".

---

## 8. Change list, in four parallel tracks

No two tracks touch the same file. Track B lands `VoiceApi.kt` and the defaulted `Hub` members as
its **first commit** (within minutes); Track C builds against them from then on.

**A · Daemon, Lens, docs**
- `daemon/src/stt.ts`: 2.4.1–2.4.3 (and 2.4.4 if the numbers ask for it).
- `daemon/test/stt.test.ts`: daemon tests 1–3.
- `daemon/src/qa-checklist.ts`: the Voice section.
- `lens/src/phone/guide.ts` (+ its caller in `lens/src/phone/app.ts` if the step list is passed in):
  skip voice when ready (4.3).
- `docs/PROTOCOL.md`: "Speech to text" names Android as a client, the route rule (2.1), stop while
  connecting, the log line; "The Lens guide" notes the skip.
- `docs/DECISIONS.md`: a "Voice on the phone (2026-10-15)" section from table 1.

**B · Android data and audio**
- `data/VoiceApi.kt` (new, 3.7 verbatim), `data/Voice.kt` (new: `LiveDictation`, `AudioCapture`,
  `HeadsetRoute`, `VoicePrefs`, `FakeDictation`).
- `data/Hub.kt` (the four members), `data/Protocol.kt` (`Snapshot.stt`, parse), `data/LiveHub.kt`
  (`{t:"stt"}`, route, Companion fetch, `dictate`, `sttInfo`, `setSttKey`), `data/FakeHub.kt` (demo).
- `AndroidManifest.xml`: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`.
- `app/src/test/.../VoiceTest.kt`: Android tests 1, 2, 4.

**C · Android UI**
- `ui/voice/MicGesture.kt` (pure reducer), `ui/voice/MicButton.kt` (button, gesture, permission,
  haptics, TalkBack), `ui/voice/DictationCapsule.kt` (capsule, ringed planet), `ui/voice/VoiceHint.kt`
  (one-line hints) — all new.
- `ui/session/SessionScreen.kt`: the Composer's button, the capsule above it, hold-Send to append,
  the settling tail styling, BackHandler, lifecycle finish.
- `ui/home/NewSessionSheet.kt`: the seam mic and the field ↔ capsule swap.
- `ui/settings/Settings.kt`: the Voice section. `ui/components/Icons.kt`: `Glyph.Mic`.
- `res/values/strings.xml`: 3.5. `docs/DESIGN.md`: a "Voice" entry under Components (capsule,
  ringed planet, the numbers in 3.4).
- `app/src/test/.../MicGestureTest.kt`: Android test 3.

**D · Mac**
- `App/VoiceModel.swift`, `UI/VoicePanel.swift` (new, 4.2).
- `App/LensGuide.swift`, `UI/LensGuideView.swift`: use them; `steps` (4.3).
- `UI/SetupModel.swift`, `UI/SetupView.swift`: the Voice step (4.1).
- `UI/MenuPopover.swift`, `UI/PopoverPages.swift`: notice and row (4.4). `App/AppModel.swift`:
  `Defaults.voiceAsked`.
- `App/LensGuideRender.swift`, `App/DevTools.swift`: the renders in Mac test 1.

---

## 9. Not in this (candidates for LATER)

Opus instead of raw PCM on mobile data; voice commands on the phone ("send it"); dictation in the
live terminal screen; trying the voice with the Mac's own microphone in Setup.
