# Living data: the sky is the product

Contents: 1. Choosing the metaphor · 2. Layout · 3. Bodies · 4. Labels · 5. Camera ·
6. Life cycle · 7. Death colour · 8. Time warp · 9. Robustness · 10. What failed

The reference implementation is `assets/compose/Solar.kt`. Read its comments before porting.

## 1. Choosing the metaphor

Ask what the user watches most, and what *runs out* or *resets*. That's the sun. Then ask what
they own and switch between (planets) and what lives inside those (moons). Examples:

| Product | Sun (life cycle) | Planets | Moons | Universe (slow budget) |
|---|---|---|---|---|
| Hypernova (Claude Code) | 5-hour usage window | Projects | Sessions | 7-day usage |
| Home-server dashboard | CPU/RAM pressure (or the UPS battery) | Hosts | Containers / services | Disk free, or the month's bandwidth |
| Budget app | This month's spending vs budget | Categories | Transactions / merchants | Yearly savings goal |
| Focus/pomodoro | The current focus block | Projects | Tasks | Daily focus budget |
| Team status | On-call load | Teams | People / incidents | Quarterly error budget |

Rules:
- **One sun.** If several quantities compete, the sun gets the one that really ends something
  (a limit); the rest become readouts or the universe's light.
- **States map to physics,** not to arbitrary colours.
  - Working → the planet turns on its axis (it spins up, and slows to a stop when work ends).
  - Needs you → amber with a contracting focus bracket.
  - Idle → dim.
  - Error → a red flare (sparingly).
- **If the data has no natural "limit/reset",** keep the sun at its young stage and skip the
  supernova. Don't invent drama.

## 2. Layout

- **One system,** centred in a "sky band" that starts just below the real top bar (status inset
  + about 96dp, in dp and not as a fraction) and is about half the screen tall.
  - The sky window (the first item of the list) grows on small screens if the system needs room.
  - Re-layout whenever the band changes, because insets arrive a frame or two late.
- **Orbits:** tilted ellipses (ry/rx 0.36–0.44, seeded per planet), aMin = 0.15w,
  aMax = w/2 − 34dp (room for the outer planet's moons), spaced evenly.
  - With one planet, a = 0.28w.
  - Draw them dashed (1.5/4.5dp) at α 0.18.
- **Kepler speeds:** ω ∝ a^-1.5, with the innermost period 48s. **Integrate the angle**
  (θ += ω·dt), never θ = ω·t: when an orbit's radius changes, absolute-time angles make planets
  race around the system.
- **Seeds from names:** tilt, phase and shade come from `hash(name)`, so the sky is always the
  user's sky. New groups get the next orbit, and orbit radii ease to new targets (so nothing
  snaps).

## 3. Bodies

- **The sun:**
  - Radius and colour come from the usage stops (radius 4.5 → 6.5 → 8 → 10.5 → 14 → 16dp).
  - **A star as a telescope photographs it** (`star()` in Solar.kt), outside in, never flat rings:
    - Bloom: one radial gradient to 7.5r (smaller when zoomed in), α .32 → .17 at 0.1 → .065 at
      0.24 → .02 at 0.5 → 0, roughly 1/d².
    - Corona: 16 tapered, slightly bent streamers from the limb, lengths 1.35–2.65r breathing on
      slow uneven sines (every fourth 1.1r longer), turning 0.012 rad/s, one gradient for all.
    - Chromosphere: a thin glow just outside the limb (to 1.45r).
    - Photosphere with limb darkening: 94% white at the centre → 70% → 32% → its colour,
      slightly darkened, at the edge.
    - Surface once it's over 6dp on screen: granulation (11 soft bright cells, golden-angle
      spread, slowly turning and flickering), clipped to the disc.
    - Diffraction spikes over everything: a horizontal pair 5.5–7r long and a vertical pair at
      62% (so they stop short of the readout below), a radial gradient on thin diamonds.
    - Scintillation: bloom and spikes vary ±5% on slow uneven sines.
    - The readout under it has a soft dark text shadow, so the spikes never hurt it.
  - Pulse amplitude 0.03 → 0.10 past 60%, with a period running continuously from 6s down to
    2.2s. **Integrate the pulse phase** so changing usage never strobes it.
  - Past 75% it turns **active** (fully by 100%): sunspots (dark umbra and penumbra) and three
    low, wide prominence arches off the limb (a soft glow stroke under a thin bright core),
    rising and falling. Past 80% the planets' orbits wobble up to 5dp.
  - The cooling star at the weekly limit is the same star, its spikes fading as it cools.
- **Planets look like planets, not stars:** the activity meter's sphere (`planetSphere` in
  Primitives.kt), lit from the sun, with a rim and meridians that turn while one of its items
  works (one turn in 4s, spun up at 2.5/s and slowed at 1.1/s); a faint 1.7× glow, a little
  brighter while it turns. Meridians only once the planet is 3.4dp or more on screen.
  - **Size is how long its items have run:** radius (3.2 + 1.25·ln(1 + minutes/10)) dp, capped at
    9 and scaled by √(6/max(6, N)) when there are many planets. A moon: (1 + 0.42·ln(1 +
    minutes/10)) dp, capped at 2.6.
  - **Orbits are fading tails, not ellipses:** 1.9 rad of the orbit behind each planet, alpha
    rising as k² toward it, width 0.35→1 of 1.2dp, stopping 2dp short of the planet (so it never
    shows through the translucent sphere), in a tint between muted and the planet's colour.
    40 butt-capped segments (round caps bead where they overlap).
  - Planets in the far half of their orbit (y < cy) are drawn before the sun at 85% size and alpha.
  - Colour is a seeded shade between accentDim and accentBright, or amber when it needs you.
- **Moons:** shells of 4, 7, 10… (radius r + 5dp + shell·3.6dp, ellipse ry 0.45, shell period
  6 + 3·shell seconds).
  - Moons are mint when working, muted when idle and amber when they need you.
  - Many moons read as a ring, so 20 sessions never take over the screen.
- **New items:** a moon spirals out over 1.2s, a planet ignites with a ring flash, and its name
  scrambles in once.

## 4. Labels: never overlap, never jump

What the owner rejected, in order: constellation lines between items; two columns of names;
rows of HUD callouts with long leader lines ("too organized"); labels hopping between eight
candidate sides ("labels jump a lot"). What works:

- **A star-chart annotation:** name (Manrope 600 9sp tracked uppercase) plus count (mono 9sp,
  dim), with a 0.8dp tick from the planet's edge to the label at 45% of its alpha.
- **Radial and continuous:**
  - The label sits on the side of its planet that faces *away from the sun*. Its direction is
    smoothed: shortest-arc lerp, rate 3.5/s.
  - The text alignment blends continuously with the direction: `x = ax − w·(0.5 − 0.5·clamp(1.4·cos))`,
    and the same for y with sin. As the planet orbits, the label glides around it.
- **Edges:** if there's no room outward, bend the direction smoothly toward above or below,
  weighted by how much room is missing. Clamp x to the screen as a last resort.
- **Collisions fade, they don't move.** Place in priority order (focused, needs you, working, the
  rest). If a label overlaps one placed earlier this frame, ease its alpha toward 0.15.
- **The sun's readout is fixed** under the sun (`72% · 1h 12m`, amber past 80%, `REBORN IN 0:24`
  during a cooldown).
- **When cards scroll over the sky,** fade the whole system (smoothstep over 4–42% of the sky
  window's height). On other tabs, hide it.

## 5. Camera

- **Tapping a planet or its label** sets the focus and filters the list to that group (a single
  state, hoisted, saveable). Back, tapping the sun, "show all", or tapping the planet again
  clears it.
- **Zoom 2.6×, expo-eased at rate 3.2/s.** Following the planet:
  `screen = view + (world − cam)·zoom`, with `cam = lerp(sun, planet, k)` and the view anchor
  shifted down 15% of bMax.
  - Draw the world layer inside the transform, dividing stroke widths by the zoom.
  - Draw **all labels in screen space**, so they never scale.
- **Zoomed:**
  - The focused name turns ink-white above the planet, with a mint focus frame. Other labels fade
    out.
  - Moons trail fading tails of their own (1.3 rad) and show their lit side. Their items are listed in a **fixed column** beside the planet (hex +
    name, 22dp rows), each with a 0.6dp line to its moon. The moons move; the names don't.
  - Tapping a moon or its row opens that item.
- **Taps go to the nearest body relative to its size,** so a big sun never swallows nearby
  planets. The sun's target is max(sunR·1.6, 16dp).

## 6. Life cycle

Timelines run on **wall-clock seconds** (`wt`), and animation phases on the ambient clock. A
limit that started more than ~3s ago, or under Reduce Motion, restores **quietly**: no blast, no
overlay, no haptics, with bodies already scattered.

**Ageing (live):** the sun colour, size, pulse and flares follow usage, eased at rate 4/s. Snap
the value at transitions (the newborn star is young, not the dead one's 100%).

**Supernova (limit reached):**
1. 0–0.35s: the star swells 2.5× and goes white-hot.
2. The full-screen overlay (above every screen and sheet, under the grain) plays:
   - a white flash, 0.62·(1−t/0.55)²;
   - an amber wash, 0.10·(1−t/2.2);
   - shockwave 1: radius expo(t/1.5)·diagonal, white→amber, 2.5dp, plus a 16dp soft band at 14%;
   - shockwave 2: delayed 0.2s, 2.4s long, red, 0.8×diagonal;
   - 110 debris streaks: speed 90–470dp/s, drag 1.3, life 1.6–5s, white, amber or red.
3. Haptics: a long press, then 140ms later a reject (API 30+). Remember blasts already felt, so a
   rotation doesn't buzz again.
4. Planets are thrown: radial 70–160dp/s plus a random tangent, drag 0.9/s, a slow wander. They
   bounce off soft walls **inside the orbit region**, never over the labels.
5. **Everything dies:** labels, orbits and the readout fade within 1s, and planets and moons turn
   grey (alpha 0.3, moon rings ×1.7, moon spin ×0.3).

**Cooldown:**
- Nine nebula clouds expand for 3s, then collapse toward the centre as progress → 1: distance
  ×(1 − 0.75p), radius ×(1 − 0.45p).
- Their colour moves from hot to cool, and the nebula fades in over 1.5s.
- A pulsar blinks first (pow(sin, 6), 2Hz). From p > 0.3 a protostar grows, with an accretion
  disk whose dash phase turns.
- From p > 0.88, planets start drifting home (up to 25% captured).

**Rebirth (reset):**
- A mint ignition ring and a soft mint flash (14%).
- The sun grows out of the protostar over 1.6s.
- Planets are captured from where the pull left them (`capBase`), staggered 0.18s per orbit,
  over 2.3s expo.
- Labels return 2–3.5s after the reset. The nebula fades over 2.5s.

**Heat death (the slow budget reaches its limit):**
- **Names go first** (1.5s). Then over **9s** the sun cools (white-hot → white-blue → orange →
  ember → black), shrinks to 45%, and its alpha goes out over the last 30%.
- Planets **float away**: outward 9–18dp/s, no walls and no drag, dimming with the cooling.
- The galaxy's light follows the cooling exactly (it starts dimming at 15%).
- Total darkness. Only `THE UNIVERSE RETURNS IN 2d 4h` remains (40% alpha, fading in over the
  last 20%).

**Big Bang (the slow budget resets):**
- 0–0.6s: a singular point of light grows (1.5 → 11.5dp).
- 0.5–2.4s: a white flood over the whole screen, 0.85·(1−k)^2.2, with a mint ring expo-racing to
  the diagonal and a teal ring delayed.
- Stars reveal from the centre outward, over 3.5s of expo radius.
- The first star appears at 0.8s, white-hot, cooling to its usage colour by 3s.
- Planets form out of the sun one by one (1.6s + 0.22s per orbit), and labels fade in last
  (3.5–5s).

## 7. Death colour

The whole app, not only the sky, loses its colour when the sky dies. On Android 12+:
- Put a `RenderEffect.createColorFilterEffect(ColorMatrix)` on the app's root graphics layer:
  saturation `1 − 0.97·d`, then scale RGB by `1 − 0.42·d`.
- Cache it per 1/40 step, and use `null` when d ≈ 0, so the root isn't offscreen normally.
- Below API 31, use a dark veil `bg × 0.55·d` instead.

d ramps in after a supernova (0.4–2.6s), follows the cooling in a heat death, and drains out with
the rebirth (0.1–2.3s) or the Big Bang (0.6–3s). Read it only in the layer callback. Web uses
`filter: saturate() brightness()` on the app root; SwiftUI uses `.saturation()` and
`.brightness()` on the root view.

## 8. Time warp (demos)

Owners judge the life cycle by feel, so give them a way to scrub it:
- **Drag the sun sideways** to age or rejuvenate it. This is a hand-rolled gesture that only
  starts on the sun, so list scrolling isn't blocked.
- Drag the usage meters; drags are relative (no jump to the finger).
- **Commit the limit only on release at the end,** and use a 30s demo cooldown.
- Never let a background "creep" pull a warped value back down.

## 9. Robustness checklist

- Compute positions every frame **even while hidden** (focus 0), and skip only the drawing.
  Otherwise a blast while away piles everything on the sun.
- A group created during a death is born dead (free, cap 0), so it joins the capture.
- Re-measure text when density or font scale changes, and invalidate the geometry signature then.
- Under Reduce Motion: snap the eased values (orbit radii, usage, camera), and set the rebirth
  and Big Bang windows as already over.
- The overlay composable exists only while an event is active (a state flag set by the
  renderer), and then runs at the full frame rate.
- Read hub state without observation in the sky's draw while the clock runs, so a 120Hz drag
  doesn't redraw the sky on every touch event.

## 10. What failed (don't repeat)

- Constellation lines linking items ("I don't like how projects are organised as connected
  stars"), even though the owner liked the *logic* of grouping.
- One solar system per project: a project with 20 sessions became a galaxy that pushed the
  others off screen.
- HUD callout rows with long fading leaders: too organised and busy.
- Discrete label placement (8 candidate sides): visible jumps whenever moons or planets passed.
- Colourful deaths (an amber/red nebula over a green app): death must be colourless.
- A 3s fade to black for the heat death: it has to *cool*, slowly, with things drifting away.
