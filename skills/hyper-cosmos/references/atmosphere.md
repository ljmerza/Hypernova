# Atmosphere: cosmos, grain, chrome, clock

Contents: 1. The ambient clock · 2. The cosmos · 3. Grain · 4. Edge-to-edge chrome · 5. Porting

## 1. The ambient clock

Use one clock for every ambient animation. With separate infinite animations, each one asks for
a frame on every vsync, which is 120 times a second on modern phones.

- It ticks at 30fps (20 on battery saver). It **sleeps between frames**, measuring the sleep from
  the frame's own timestamp: `sleep = frameStart + period − 4ms − now`.
- It stops when the app isn't visible (lifecycle STARTED) and resumes from where it paused, so
  nothing jumps.
- Under Reduce Motion it doesn't tick at all.
- Expose `seconds`, `tri(period)`, `pulse(period)` and `blink(period)`, and read them **only in
  draw or layer callbacks**.
- Compose: `assets/compose/Ambient.kt`. SwiftUI:
  `TimelineView(.animation(minimumInterval: 1/30, paused: reduceMotion))`. Web: a single
  `requestAnimationFrame` loop that skips frames until 33ms have passed, paused on
  `visibilitychange`.

## 2. The cosmos

Port of 0stems `DigitalCosmos.tsx`, in `assets/web/` and `assets/compose/Cosmos.kt` (`Sky`):

- **95 stars** in three tiers.
  - Anchors (7%): size 2.4–3.6, alpha 0.6–0.9, speed 0.08–0.18.
  - Mid (28%): size 1.1–2.0, alpha 0.28–0.5.
  - Background: size 0.5–1.0, alpha 0.10–0.22.
  - Non-anchor speed 0.15–0.55. Sizes and speeds are in dp (or CSS px) per second.
- **Clusters:** 55% of stars sit around 6 centres (radius minSide × 0.12–0.22, distance =
  rand^0.6).
- **Anchor halos:** 4.5× size at α 0.05, 2.5× at α 0.14, 1.4× at α 0.45, then the core.
- **Hex labels:** 2-character labels on every anchor and on 15% of the rest. JetBrains Mono 9sp,
  offset size + 7dp, α × 0.55.
- **4 nebulae.** Radial gradient stops 0 → α, 0.3 → α×0.55, 0.6 → α×0.2, 1 → 0.
  - Base alpha 0.07–0.14, breathing 0.6 + 0.4·sin(t·0.08–0.18).
  - Radius minSide × 0.30–0.55 × **1.5**: 0stems applies dpr twice, so on a phone its clouds come
    out this big.
- **Wind:** angle t·0.04, force 0.4 (nebulae get half). Stars breathe 0.7 + 0.3·sin, twinkle
  0.85 + 0.15·sin. Positions wrap at the edges.
- **Inside the "data band"** (where the living system sits), ambient hex labels drop to 20% and
  stars to 60%, so nothing ambient reads as data.
- **Implementation:**
  - One canvas, struct-of-arrays, brushes built once and translated. Text layouts cached per star.
  - On resize, keep the same sky and rescale positions rather than re-randomising.
  - Keep the sky in a retained holder (a ViewModel) so rotation never restarts it.
- **Galaxy light (a weekly budget):** nebula alpha × light, star alpha × (0.4 + 0.6·light).
  light = 1 − 0.65·w^1.5. At "heat death" both go to 0. In a Big Bang, stars appear only inside a
  growing radius from the origin (80dp soft edge), and nebulae scale with that radius.

## 3. Grain

Port of 0stems `GrainField.tsx`: grey noise with alpha 0..90/255, **overlay** blend, opacity
**0.32**, a new pattern 24 times a second.

- Instead of regenerating a full-screen bitmap, generate 4 noise tiles of 192² **once per
  process, off the main thread**. Each frame, pick a tile and a random offset and draw it as a
  repeating shader.
- **Cell size about 1.1dp.** 0stems' 960×540 canvas stretched to a desktop gives ~1.5 CSS px at
  60cm, and a phone is held at half the distance. Upscale with bilinear filtering to match the
  browser's soft look.
- Frames come from the shared clock: `frame = floor(seconds × 24)`.
- Give it **its own layer that is not offscreen** (alpha 1, default compositing), so the overlay
  still blends with what's beneath, and a tick never re-records the content under it.
- On battery saver or Reduce Motion, draw one static frame.
- Web: a fixed canvas with `mix-blend-mode: overlay; opacity: .32; pointer-events: none;
  z-index: 60`.

## 4. Edge-to-edge chrome

- **Draw everywhere.**
  - Android: `enableEdgeToEdge(dark(TRANSPARENT), dark(TRANSPARENT))`,
    `isNavigationBarContrastEnforced = false`, cutout mode ALWAYS (API 30+) or SHORT_EDGES, and
    `screenOrientation="portrait"` for phone apps.
  - iOS: `.ignoresSafeArea()` on the background.
  - Web: `viewport-fit=cover` and `env(safe-area-inset-*)`.
- **Top and bottom bars blur what scrolls beneath them**, Telegram-style.
  - Full strength under the chrome itself, fading to sharp only over the bar's last ~30dp,
    measured in dp from the bar's real height (not a fraction).
  - Blur radius 22dp, bg tint 42%, no noise (the grain covers that).
- **Only blur the right sources.** The cosmos is source z=0. Each screen's content is z=1 *with a
  key*, and each screen's bars blur only the cosmos plus their own key. Without that, during a
  cross-fade a screen's bars blur the other screen's list.
- **Android specifics (Haze 2):**
  - Use `HazePerformanceMode.Quality` only on API 33+. On 31–32 it switches to a slow layered
    path; use the default there.
  - On API ≤ 30 there's no RenderEffect, so draw a designed scrim with the same shape (solid 94%,
    then fading).
- **Sheets:** full 28dp blur at the cheapest input scale (a uniform blur has no fade to show a
  seam), with the dim and the panel animating separately, so the dim's edge never slides through
  the status bar.
- **Cards:** no per-card blur. Use dense fills instead (see tokens). A dozen live blurs over an
  animated sky would cost more than they show.
- **Web:** `backdrop-filter: blur(22px)` on the bar, plus
  `mask-image: linear-gradient(to bottom, #000 calc(100% - 30px), transparent)`.

## 5. Porting notes

- **SwiftUI:**
  - Draw stars in a `Canvas` inside a `TimelineView`, with state in a reference-type model object
    (not `@State` arrays, which would re-render).
  - Grain as a pre-rendered `CGImage` tile drawn with a random offset and
    `.blendMode(.overlay)`.
  - Use `.background(.ultraThinMaterial)` with a gradient mask for the bars.
- **Web:** `assets/web/cosmos.js` is dependency-free.
  - Call `HyperCosmos.mount(canvas, { grain: grainCanvas })` to get the clock, cosmos and grain,
    with `prefers-reduced-motion` respected.
  - Cap DPR at 2 (1.5 on touch devices) as 0stems does.
- **Performance budget:** the whole atmosphere at 30fps should use less than 25% of a mid-range
  phone's GPU. Measure frame timing on a real-size device (see `process.md`).
