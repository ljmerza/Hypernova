---
name: hyper-cosmos
description: >-
  The "Hyper cosmos" design language and build approach behind Hypernova, 0stems, Hyperscribe
  and Hypernavigate: warm near-black, one mint accent, Manrope + JetBrains Mono, a living
  digital-cosmos background with film grain, HUD seams and glass cards, edge-to-edge chrome with
  progressive blur, and data shown as living astronomy (a sun that ages with a budget, a supernova
  at the limit, a cooling heat death, a Big Bang at the reset). Use this skill whenever the user
  wants to build, design or restyle an app, screen, page, dashboard, widget or component "in our
  style", "like Hypernova", "like 0stems", "Hyper-something", "cosmic", "digital cosmos", with a
  starfield, grain or space theme, and whenever they start a new app or product of their own
  (Android/Jetpack Compose, SwiftUI/Mac, or web), even if they don't name the style.
---

# Hyper cosmos

A design language where the interface is a quiet, living night sky, and the most important data
in the product *is* the sky. It grew out of 0stems (atmosphere), Hyperscribe (structure),
Hypernavigate (urgent states) and Hypernova (living data), and it was refined with an owner who
reacts to feel more than to specs. The goal is always the same: it should feel **native, calm,
alive, and visually perfect**, and never like a theme painted on top.

## The five pillars

1. **Warm night, one accent.** Use warm near-black `#14110E` (never pure black), ink `#F5F2EB`,
   and mint `#7FFFA0` as the only accent. Amber `#E8B341` means *needs you*; red `#FF7878` means
   failure. Everything else is greys with a warm cast.
2. **Quiet type.** Use Manrope at weight 500 almost everywhere (200 for the wordmark), with `ss01`
   and tabular figures. JetBrains Mono is for code, hex ids, paths and numbers. Labels are tiny,
   uppercase and widely tracked (10sp, 0.18em).
3. **A living atmosphere.** A sparse drifting cosmos (stars in three tiers, four soft green
   nebulae, slow wind, 2-character hex labels) sits behind everything, with film grain over
   everything. Draw edge to edge, including under the status bar and around camera cutouts:
   content scrolling under the bars is blurred progressively, so there are no black strips.
4. **Structure from Hyperscribe.** The *seam* (a spinning-planet activity meter, a tracked label, a hairline,
   a trail) heads every section. Add pill buttons, glass cards, status chips, honest short copy,
   and *status that only moves when something real happens*.
5. **Data as living astronomy.** Pick the one quantity users care about most (a budget, a quota,
   health, time) and make it the sun. Things the user owns become planets, and the items inside
   them moons. Let the real state drive the sky's life cycle: ageing, supernova, cooling, rebirth.
   The metaphor has to *carry information*; if it's only decoration, leave it out.

## How to build with it

Work in this order. Each step builds on the one before, and skipping ahead (for example animating
before the tokens are settled) causes rework.

1. **Scout the references.** If the user mentions their other projects, read their actual code
   for tokens and effects before designing anything. Don't guess from names. Bundled originals
   are in `assets/` (see "Reference implementations" below).
2. **Interview once, thoroughly.** For a new product, ask every open question in one pass with
   a recommended default for each, so the owner can answer "defaults except 4 and 9". Record
   decisions in `docs/DECISIONS.md`. Whenever the owner says "for now", "later" or "not yet",
   add a row to `docs/LATER.md` (idea, date, why parked, when to revisit).
3. **Tokens first:** colours, type, radii, motion. Copy the values from `references/tokens.md`
   into the platform's theme file. Never hardcode a colour in a component.
4. **Atmosphere:** cosmos, grain, edge chrome. Use `references/atmosphere.md` and port from
   `assets/`. Get one shared ambient clock in place before any animation exists.
5. **Components:** seam, glass card, pill, status chip, meter, mini terminal, diff card,
   approval block, wordmark, Nova-style mark. See `references/components.md`.
6. **Living data:** design the metaphor (sun, planets, moons, life cycle), labels and camera
   from `references/living-data.md`. This is the part people remember. Map *real* states onto it.
7. **Motion, performance, accessibility:** apply the rules below and in the references.
8. **Verify like the owner will:** take real screenshots at a real phone size, run frame
   timing, run an independent multi-angle review with skeptical verification, then put a build
   in the owner's hands quickly. See `references/process.md`.

## Taste rules (learned from the owner, keep them)

These came from direct feedback. Each one replaced something the owner disliked.

- **Nothing overlaps, ever.** Labels never sit on other labels, cards, or their own planet.
  Text never shows through a card: when cards scroll over the sky, the sky's labels fade out, and
  card fills are dense enough (78–86%) to hide stars. Placeholder screens hide sky labels.
- **Nothing jumps.** Labels never hop between discrete positions. They glide continuously around
  their body, facing away from the sun. If two collide, the lower-priority one *fades*; it never
  moves away. Re-layouts glide on both axes from where things were drawn. Never compute positions
  from absolute time when a rate can change: integrate angles.
- **Native, not organized.** The owner rejected rows of callouts with long leader lines ("too
  organized") and constellation lines between items. Prefer star-chart annotations: a short tick
  and a name beside the object.
- **Scale gracefully.** Twenty items in a group become a ring of moons around one planet, not a
  galaxy that pushes everything off screen. Systems must be big enough to read as systems: a
  sun, visible orbits, planets on them.
- **A second budget is a second, smaller star, never a second sun.** When two sources each have
  their own quota (two AI agents, two accounts), the one that leads is the sun; the other is a
  smaller system of the same kind in a calmer palette, on the edge of the sky, with its own moons
  and a short label (`CODEX 61%`). With only one source there's no second star and no labels for
  it: a single-source user sees exactly the one-sun sky.
- **Death is colourless and slow.** When the sky "dies" (a limit is reached), the *whole app*
  drains to grey-black (a colour matrix on the root, not just the sky), labels go first, orbits
  vanish, and bodies go cold and drift into emptiness. A heat death cools: the sun goes from
  white-blue to orange to ember to black over about 9s. Colour floods back only with new life.
- **Drama is earned, then quiet.** A supernova or Big Bang can take over the whole screen (flash,
  shockwaves, debris, haptics) because it happens rarely and means something. Everything else
  stays calm. A scramble effect only plays when something new appears. No sounds.
- **Fullscreen, always.** Draw under the status bar and around the camera hole, and blur what
  scrolls under the chrome. A black bar at the top reads as "not native".
- **Copy is short and honest.** Use sentence case and plain words, say what's not ready yet
  ("Arrives in milestone 3."), and don't hype.

## Platform map

| Need | Jetpack Compose (proven) | SwiftUI | Web |
|---|---|---|---|
| Tokens | `assets/compose/Tokens.kt`, `Theme.kt` | `assets/swiftui/HyperCosmos.swift` | `assets/web/hyper-cosmos.css` |
| Ambient clock | `assets/compose/Ambient.kt` | `TimelineView(.animation(minimumInterval: 1/30))` | `requestAnimationFrame` throttled to 30fps |
| Cosmos | `assets/compose/Cosmos.kt` (`Sky`) | `HyperCosmos.swift` (`CosmosView`) | `assets/web/cosmos.js`, original `DigitalCosmos.tsx` |
| Grain | `assets/compose/Grain.kt` | `HyperCosmos.swift` (`GrainView`) | `cosmos.js`, original `GrainField.tsx` |
| Edge blur | `assets/compose/Chrome.kt` (Haze 2) | `.background(.ultraThinMaterial)` + gradient mask | `backdrop-filter: blur()` + `mask-image` |
| Living data | `assets/compose/Solar.kt` | port the math in `references/living-data.md` | same |
| Components | `Primitives.kt`, `Transcript.kt`, `Icons.kt` | port from `references/components.md` | `hyper-cosmos.css` classes |

The Compose files are production code from Hypernova: read them before porting, because their
comments explain the *why* of each number. On other platforms, port the behaviour and the numbers
rather than the code structure.

## Rules that keep it fast

- **One ambient clock** (30fps, 20 on battery saver) drives every ambient animation: cosmos,
  grain, the planet meter, pulsing dots and the cursor. Read it only inside draw or layer callbacks so
  ticks never re-render UI trees. Use no per-component infinite animations. The clock sleeps
  between frames, stops when the app isn't visible, and freezes under Reduce Motion.
- **No per-frame allocation** in the sky: struct-of-arrays for stars, cached brushes, strokes and
  text layouts, and layout recomputed only when the data's *geometry* changes (not on every state
  change).
- **The grain lives in its own layer** (non-offscreen, so the overlay blend still works) and
  generates its tiles once, off the main thread.
- **Blur only the chrome** (top and bottom bars and sheets) and never every card; use
  full-resolution blur only where the platform has a single-pass progressive path.
- **Rare, dramatic moments can run at the full panel rate** while they're active, and must not
  exist at all otherwise.

## Rules that keep it humane

- **Reduce Motion:** draw one static frame, but timelines (cooldowns, countdowns) still advance on
  the wall clock and redraw once a second. Every effect snaps to its end state.
- **Timelines run on the wall clock, animation on the ambient clock.** Then a life cycle still
  completes if the clock is frozen, the screen is hidden, or the app restarts mid-cooldown. A
  limit that happened while the app wasn't watching restores *quietly*, without replaying the
  blast.
- **The sky must be reachable without sight:** a content description that summarises it, plus
  custom actions (filter by group, jump to details). Meters are sliders, and limit changes are
  announced through a polite live region.
- **Touch targets are 48dp**, icons are drawn (fonts often lack ✓ ⎋), and every string lives in
  resources.

## Reference files

Read only what the current step needs:

- `references/tokens.md`: colours, type scale, radii, spacing, motion, with Compose, SwiftUI
  and CSS snippets.
- `references/atmosphere.md`: the cosmos and grain parameters, edge-to-edge chrome, progressive
  blur, the ambient clock, per-platform porting notes.
- `references/components.md`: seam, glass card, pill, chip, meter, mini terminal, diff card,
  approval block, icons, Nova mark and wordmark, with exact values.
- `references/living-data.md`: turning a product's data into a solar system, the full life
  cycle (ageing, supernova, cooldown, rebirth, heat death, Big Bang), gliding labels, camera
  zoom, death colour, time warp for demos.
- `references/process.md`: how the owner likes to work (interview, LATER.md, milestones),
  verification (emulator at phone size, screenshots, frame timing), independent review
  workflows, delivering builds, toolchain gotchas.

## Reference implementations

- `assets/compose/`: Hypernova's real files. `Tokens.kt`, `Theme.kt` (motion prefs, selection
  colours), `Ambient.kt` (shared clock), `Cosmos.kt` (ambient sky + `CosmosState`, overlay, death
  colour effect), `Grain.kt`, `Solar.kt` (the living solar system: life cycle, labels, camera),
  `Primitives.kt`, `Chrome.kt` (edge blur), `Icons.kt`, `Transcript.kt` (mini terminal, diff,
  approval), `Scramble.kt`, and `nova_mark_launcher.xml`.
- `assets/web/`: `DigitalCosmos.tsx` and `GrainField.tsx` (the 0stems originals),
  `hyper-cosmos.css` (tokens and components as CSS), and `cosmos.js` (a dependency-free cosmos +
  grain + ambient clock for any page).
- `assets/swiftui/HyperCosmos.swift`: tokens, cosmos and grain for SwiftUI (Mac and iOS).

These files are the source of truth for numbers. When this document and a file disagree, the
file wins, because it's what shipped.
