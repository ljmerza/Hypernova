# Tokens

The values are ported 1:1 from 0stems (`tailwind.config.ts`, `globals.css`) and refined in
Hypernova. Keep them in one theme file per platform and never inline them.

## Colour

| Token | Hex | Use |
|---|---|---|
| `bg` | `#14110E` | Base. Warm near-black, never `#000`. |
| `bg950` | `#0F0D0A` | Terminals, deepest wells |
| `bg900` | `#1B1814` | Card base |
| `bg800` | `#221F1B` | Raised surfaces, inputs |
| `bg700` | `#2C2823` | Disabled fills |
| `bg600` | `#36322C` | Hover |
| `line` | `#2C2823` | Hairlines |
| `lineSoft` | `#221F1B` | Faint dividers |
| `lineStrong` | `#45403A` | Handles, strong rules |
| `ink` | `#F5F2EB` | Primary text |
| `inkMuted` | `#B5B0A6` | Secondary text |
| `inkDim` | `#7A7468` | Tertiary text, labels |
| `inkGhost` | `#45403A` | Inactive tabs, placeholders |
| `accent` | `#7FFFA0` | Mint: live, primary action, success. **The only accent.** |
| `accentBright` | `#B6FFC9` | Highlights on mint, code in prose |
| `accentDim` | `#4A8C5E` | Quiet accent text |
| `accentDeep` | `#2A5A38` | Accent wells |
| `amber` | `#E8B341` | **Needs you**: approvals, waiting, near the limit |
| `danger` | `#FF7878` | Errors, deny, failed exits, limit reached |
| `star` | `#7FFFA0` | Cosmos tint (rgb 127,255,160) |

Opacity conventions:
- Card fill is a vertical gradient `bg800 @84% → bg900 @78% → bg900 @86%`.
- Card ring is `ink @8%`, or `amber @35%` when the card needs you.
- Card highlights are lines along the top (`ink @25%`) and bottom (`ink @12%`).
- Running terminals get an accent wash `accent @6% → transparent`.

The life-cycle palettes used by the living sky:
- **Sun by usage:** `accent` at 0 → `#CFFFB4` at 0.5 → `#F3E39A` at 0.7 → `amber` at 0.85 →
  `#FF7A4A` at 0.95 → `#FF5A3C` at 1.0.
- **Cooling star:** `#F2F6FF` → `#DDE8FF` at 0.25 → `#FF9A5A` at 0.55 → `#8A2A1A` at 0.8 →
  `#2A1410` at 1.0.
- **Nebula:** hot clouds are `amber`, `#FF6B4A` and `#F3E39A`; cool ones are `accent`, `#5FE0C8`
  and `#9DFFB8`. Ambient nebulae use `[127,255,160] [150,255,200] [180,255,140] [105,255,185]`.

## Type

- **Manrope** (variable): weight 500 everywhere, 200 for the wordmark, 600 for labels. Set
  `font-feature-settings: "ss01", "tnum"`.
- **JetBrains Mono** (variable): terminals, hex ids, paths, numbers in meters.
- Both are OFL fonts from Google Fonts' GitHub (`ofl/manrope/Manrope[wght].ttf`,
  `ofl/jetbrainsmono/JetBrainsMono[wght].ttf`). Bundle the variable files and their OFL text.

| Style | Size / line | Tracking | Notes |
|---|---|---|---|
| display | 34 / 33 | −0.03em | Hero only |
| title | 22 / 26 | −0.015em | Screen titles, sheet headlines (end with a full stop: "Start a session.") |
| heading | 16 / 21 | −0.01em | Card titles, user messages |
| body | 15 / 22 | 0 | Prose |
| small | 13 / 18 | 0 | Pills, secondary |
| label | 10 / 12, weight 600 | **0.18em, UPPERCASE** | Seams, chips, eyebrows |
| wordmark | 20 / 22, weight 200 | −0.02em | Lowercase name + mint slash: `hypernova/` |
| mono | 12 / 18 | 0 | Commands |
| monoSmall | 11 / 16 | 0 | Output, paths, trails |

## Shape and spacing

- Radii: cards 16, terminals and diffs 14, chips and pills full, sheets 24 (top corners),
  segmented track full, command wells 10.
- Screen gutter 16–20dp, card padding 16 × 14dp, section gap 10dp between cards, 18dp between
  transcript items.
- Hairlines are 1dp. Strokes in the sky are 0.8–1.4dp.
- Touch targets are at least 48dp (40dp visual circles are fine inside a 48dp box).

## Motion

- **Easing:** expo-out, `cubic-bezier(0.16, 1, 0.3, 1)`, for everything that arrives.
- **Durations:** 150ms press and hover, 180ms dialogs and fades, 500ms reveals, 600ms sections.
  Screen transitions cross-fade with a 2–3% scale.
- **Press:** scale to 0.985 (cards) or translate 1dp down (pills), plus a light haptic tick.
  Approving something gets a confirm haptic.
- **Scramble-in:** 700ms, glyphs `A–Z0–9#@$%&*`, a per-character threshold. Use it only for
  things that just appeared, and only once per item (track "already revealed" outside the view).

## Snippets

**Compose:** see `assets/compose/Tokens.kt` (object `HN`, `Type`, `Motion`) and `Theme.kt`
(`MotionPrefs`, `LocalMotion`, selection colours, the `HText` primitive).

**SwiftUI:**
```swift
extension Color {
    static let hnBg = Color(red: 0x14/255, green: 0x11/255, blue: 0x0E/255)
    static let hnInk = Color(red: 0xF5/255, green: 0xF2/255, blue: 0xEB/255)
    static let hnAccent = Color(red: 0x7F/255, green: 0xFF/255, blue: 0xA0/255)
    static let hnAmber = Color(red: 0xE8/255, green: 0xB3/255, blue: 0x41/255)
}
extension Animation { static let hnExpo = Animation.timingCurve(0.16, 1, 0.3, 1, duration: 0.5) }
// Labels: .font(.custom("Manrope", size: 10).weight(.semibold)).tracking(1.8).textCase(.uppercase)
```
The full set is in `assets/swiftui/HyperCosmos.swift`.

**CSS:** see `assets/web/hyper-cosmos.css` (`:root` custom properties and classes `.hn-seam`,
`.hn-card`, `.hn-pill`, `.hn-chip`, `.hn-meter`, `.hn-term`).
