# Process: how this owner likes to work

## Working style

- **The owner reacts to feel.** Screenshots and an installable build beat descriptions. Put
  something real in their hands fast, then iterate on what they say. Their feedback is direct and
  visual ("labels jump", "feels overlapped", "should feel dead"). Translate it into the
  underlying rule, add it to the design docs, and fix the class of problem, not the one instance.
- **Ask everything once.** For a new product, send one numbered list of every open question, each
  with a bold recommended default, grouped as Product / Data / Design / Platform / Connection /
  Project. The owner answers like "defaults except 4b, 10: violet".
- **"For now" goes in `docs/LATER.md`:** idea, date, why parked, when to revisit. Read it at the
  start of each milestone. `docs/DECISIONS.md` holds agreed decisions, and `docs/DESIGN.md` holds
  the living design spec (update it whenever the design changes). `CLAUDE.md` points to all three.
- **Milestones, each ending in something installable:** 1. look and feel on fake data, 2.
  backend, 3. real client, 4. notifications, 5. desktop companion, 6. release polish. Fake data
  must *move* (simulated streams, approvals, new items) so the look can be judged alive.
- **Never touch the owner's other running systems** (bots, services, hooks) without asking. If
  something conflicts (another agent session resuming the same conversation, a launchd service
  restarting itself), stop, explain, and ask which to keep.

## Verifying (do it before every handoff)

1. **Build clean,** with no warnings worth keeping.
2. **Run at real phone size.**
   - The Android emulator AVD is 1260×2800 @ 480dpi (Nothing Phone 3 size), with the hole-punch
     cutout overlay (`cmd overlay enable com.android.internal.display.cutout.emulation.hole`) and
     gesture nav, run headless on the host GPU: `emulator -avd <name> -no-window -no-audio -gpu
     host`.
   - Never judge motion on `swiftshader_indirect`, which takes about 800ms a frame.
   - Simulate small phones with `adb shell wm size 1080x2400 && adb shell wm density 480`
     (360×800dp), then `wm size reset`.
3. **Screenshot every state,** not just the happy path: `adb exec-out screencap -p`. Crop with
   `sips -c H W --cropOffset Y X`.
   - Capture sequences for animated events: a burst of frames timed from the trigger, because
     screencap takes ~0.5s.
   - Drive the UI with `adb shell input tap/swipe` and locate elements with `uiautomator dump`.
4. **Frame timing:** `adb shell dumpsys gfxinfo <pkg> reset`, wait 10s, then dump. Confirm the
   ambient clock holds about 30fps (≈300 frames in 10s) and read the GPU percentiles.
5. **Independent review:** run a multi-agent review over the changed code in 4–5 dimensions
   (runtime correctness, layering/blur, performance, design fidelity, platform/accessibility).
   Each finding gets a skeptical verifier who tries to refute it. Then run a second pass that
   checks every confirmed finding is actually fixed and hunts regressions. Fix all confirmed
   ones in one coherent pass rather than spawning parallel editors on the same files.
6. **Deliver.**
   - Over Tailscale: try `tailscale file cp`. If Taildrop is off, use a one-shot HTTP server bound
     to the Mac's tailnet IP that serves only the APK, answers only the phone's tailnet IP (403
     for everyone else), and shuts down after one download.
   - Tell the owner exactly what to try: "drag the sun to the right edge".

## Toolchain gotchas (from this Mac)

- `dl.google.com` can hang on large files through some VPNs. Gradle then gets Google Maven
  from a mirror via an opt-in property in `~/.gradle/gradle.properties`
  (`hypernova.googleMirror=https://maven.aliyun.com/repository/google`).
  - `settings.gradle.kts` uses it *instead of* `google()` when set, scoped to
    `com.android.* / com.google.* / androidx.*`.
  - SDK packages come from `https://mirrors.cloud.tencent.com/AndroidSDK/`, with the SHA-1
    checked. A hand-installed platform needs a generated `package.xml`.
- Compose 1.12 and Haze 2 need compileSdk 37 and Kotlin 2.4 (AGP 9's built-in Kotlin picks up the
  KGP version from the root `plugins {}` block).
- Use Android Studio's JBR as `JAVA_HOME`.
- **Kotlin trap:** inside `apply { }`, an outer parameter named like a field (`t`) shadows the
  field. Use `this.t`.
- **Compose:** read animated values (clocks, pulses) only in draw or `graphicsLayer` lambdas. A
  value read during composition recomposes on every frame.
