# Components

The values are exact. The Compose sources are in `assets/compose/Primitives.kt`,
`Transcript.kt` and `Icons.kt`.

## Seam (the signature element, from Hyperscribe)

`[planet] LABEL ───────────── trail`

- **Planet:** the activity meter, a small planet spinning on its axis in a 12dp box: a sphere
  lit from the upper left (radial gradient 75% → 22% → 4%), a 0.7dp rim at 45%, and meridians:
  longitude λ drawn as a half ellipse r·sin λ wide while cos λ > 0, fainter at the limb and on
  the shadowed side.
  - **Rest:** still, in `inkDim`.
  - **Live:** spinning, one turn in 3s, in `accent`; read from the shared clock inside its own
    small layer.
  - **Alert:** `amber`, still, pulsing (cosine, 1.4s).
  - **Between states it eases, never snaps:** the colour over 1s; the spin slows to a stop
    (1.4s, expo) or spins up (0.7s); the angle advances by the current speed, so it never jumps.
    The seam's label colour eases with it.
  (Replaced, in turn: three bars that read as an audio meter, then a moon orbiting a planet.)
- **Line:** optional (`line = false`): a session's status row is `[planet] LABEL  …  trail`
  with no hairline.
- **Label:** 10sp uppercase, 0.18em tracking, weight 600, then a 1dp hairline filling the row,
  then the trail (10sp, half the tracking, `inkDim`).
- **Uses:** section headers, card headers (project · hex · "2m"), terminal headers
  (BASH · "running 4.2s"), the session state.

## Glass card

- Radius 16. Fill is a vertical gradient `bg800 84% → bg900 78% → bg900 86%`.
- 1dp ring `ink 8%` (amber 35% plus a 5% amber wash when it needs you).
- 1dp highlight lines along the top (ink 25%, horizontal fade) and bottom (ink 12%).
- Press: scale 0.985 over 150ms. Role Button with an `onClickLabel`.
- Layout: seam, then a 12dp gap, title (heading), activity line (monoSmall), then 12dp and a row
  with a status chip on the left and `model · mode` (monoSmall, dim) on the right.

## Pill button

- 44dp tall (34dp compact), full radius, horizontal padding 20.
- **Primary:** accent fill, bg text, ring accentBright 50%. **Outline:** bg800 30% with an ink 22%
  ring. **Ghost:** bg800 20% with a line ring and muted text. **Danger:** transparent, danger text,
  danger 40% ring.
- Press: translate 1dp down, background alpha × 0.8, plus a haptic tick. Optional leading drawn
  icon at 14dp.

## Status chip

- Full radius, 9×4 padding, 5dp dot plus 6dp gap plus a label (10sp, 0.135em, uppercase).
- Live: the dot pulses `1 − 0.65·pulse(2.4s)` in its own layer. Filled variant (needs you): a
  colour fill with bg text and dot.

## Meter

- Label (tracked, dim) … value (monoSmall ink) and trail (monoSmall dim).
- Track: 1dp `line`. Fill: 3dp rounded, accent, amber at ≥80%, danger when the limit is reached.
  Animate over 600ms expo.
- It's a slider for accessibility (`progressBarRangeInfo`, `setProgress`), and its card is a
  polite live region.

## Mini terminal (a shell command in a transcript)

- **Box:** radius 14, `bg950 86%` (plus a running accent wash), 1dp ring that turns accent 28%
  while running, danger 35% on failure, ink 8% otherwise. Padding 14×12.
- **Header:** seam `BASH` with trail `running 4.2s` or `exit 0 · 11.4s`. The planet spins
  while running.
- **Body:**
  - Command line `$ ` (accent) followed by the command (ink), mono 12.
  - Output in monoSmall: errors (`error`, `FAILED`, `e: `) in danger, warnings (`w: `, `warning`)
    in amber, `SUCCESS` and `✓` lines in accent, the rest muted.
  - A blinking block cursor while running (6×12dp, accent, hard on/off from the clock).
- **Collapsed:** the last 7 lines under a top fade (DstIn gradient). Tap to expand, animating the
  size only when not running.
- **Footer:** a drawn check or cross, `exit N`, and "12 lines" or "collapse" on the right.
- Format elapsed time with a fixed locale ("1.5s", never "1,5s").

## Diff card

- Header `EDIT file … +n −m` (accent and danger counts). Each line is a row with a tint
  (`accent 7%` for +, `danger 7%` for −), mark plus text in monoSmall.

## Approval block ("needs you", from Hypernavigate's filled block)

- Radius 16, `bg950 90%` with an amber 5% wash and a 1dp amber 45% ring.
- **Band:** a solid amber strip reading `NEEDS YOU … TOOL` in bg-coloured text, weight 700.
- **Body:** the reason (small, muted), then a command well (radius 10, bg 90%, line ring) with
  `$ ` in amber.
- **Actions:** Deny (danger), Always (outline), Allow (primary, weight 1.2, confirm haptic).
- **Resolved:** folds into one line: `✓ Allowed · git push origin main` or `✕ Denied`.

## Messages

- **User:** label `YOU` (dim), then heading-size text in ink.
- **Assistant:** a small mark (12dp) and label in accentDim, then body text in muted.
  Markdown-lite: **bold** in ink 600, `code` in mono accentBright on accent 8%.
- **Table** (a markdown table in a reply or a plan; `assets/compose/MarkdownTable.kt`, which draws
  GlassCard's `TopHighlight` from Primitives.kt, made internal there):
  - **Card:** radius 14, `bg950 78%`, a 1dp ring at ink 8%, and the glass card's top highlight.
  - **Header:** on a `bg800 45%` band with a 1dp `line` under it, in the tracked label (inkDim), 9dp
    above and below. A header over 24 characters, or with code, is small 600 in ink instead (code
    decides at once, length once the header line is whole).
  - **Cells:** small (13sp), padding 12 at the sides (14 at the card's edges) and 8 above and below,
    `lineSoft` hairlines between rows, a first text column in ink as the row names. Number columns
    ("3.2k", "1.6%", "$0.42") sit on the right in monoSmall, on the text's baseline, and never wrap
    (their header may).
  - **Widths:** each column takes what it needs and the rest is shared like a browser's table
    (text at least 96dp). Only when wrapped text still can't fit does it scroll sideways, text at
    208dp, with 28dp fades on the sides that have more. Both grow with the font size. Widths glide
    over 500ms expo while a reply streams in; a finished one, or Reduce Motion, snaps.
  - **Streaming:** it shows from the header's first word, never as a paragraph of pipes; a number
    column, once seen, stays on the right until the table is done.
  - **TalkBack:** one stop per row ("System prompt. Tokens: 3.2k"), the header a heading, empty rows
    skipped, and a "Copy table" action that copies the markdown. A long-press selection copies a
    cell per line.
  - `---` in a reply is a 1dp `line` hairline with 6dp above and below.
- **Tool row:** chevron icon, verb (label), target (monoSmall, middle ellipsis).
- **Commands look like what they are, three ways** (never like a plain message):
  - *A setting* (Claude Code's own: /effort, /model, /context): a slim row, label `COMMAND`
    (dim) instead of `YOU`, then the command in mono 12sp (`/` accent, name ink, args muted),
    and its result right under it as `└ result` in monoSmall muted (danger for an error), long
    output folded to 5 lines with "show all". Tighter rhythm than a message (no extra top gap).
  - *A skill or your own command* (a prompt): label `YOU · SKILL` (or `YOU · COMMAND`), a mint
    chip (full pill, 1dp accent ring at 35%, accent fill 8%, an 11dp still planet, the name in
    mono accent without the slash), then the text typed after it as an ordinary user message.
  - *A shell line* (`!`): label `SHELL`, `$` in accent then the line in mono ink, its output
    under it folded to its last lines, like a terminal.
  - Before the server confirms it, the phone's own copy already takes its final look, with
    `· SENDING…` in the label.
  - **Queued** (sent while the agent works; it takes it later): `· QUEUED` in the label, drawn
    last, under the agent's live output (as the terminal draws its queue), with one caption line
    under the text in small, inkDim saying when it's read ("Claude reads it when this step
    finishes"). No colour or pulse of its own: it changes only when it's delivered, in place.

## Composer

- Pill field: radius 24, bg800 55%, ink 10% ring. Placeholder "Message this session…".
- Typing "/" opens the commands list above the field (yours first, then the tool's, each with
  a line on what it does; typing filters, best match first). The leading `/name` (or `!`) in
  the field is drawn in mono accent on an accent 10% wash, so a command shows as one before
  it's sent; a prompt with files attached isn't a command, so nothing is marked.
- Send: a 44dp circle, accent when ready, with a drawn send arrow.
- Above it: "Interrupt" (ghost pill, esc icon) while working, and "Jump to latest" (expands
  vertically, only when you've scrolled away from the end).

## Icons (drawn, 1.5dp round strokes)

Check, cross, back, send, esc (ring plus stop square), plus, chevron. Fonts usually lack ✓ and ⎋,
and system fallbacks render at the wrong weight, so draw them all for one consistent stroke.

## Brand

- **Nova mark:** the activity meter's planet, still, lit from the upper left and turned 45° so its
  two meridians sit evenly (a globe). It is the launcher icon (adaptive: the bg tile with a few
  faint stars; a faint glow, then the planet at r 22 of 108; its own white monochrome layer), the
  notification icon, the Mac app and menu bar icons (outline only while the service is down), the
  brand row at 22dp, and assistant messages at 12dp. (It replaced an anchor star in a focus
  bracket, which read as a star.)
- **Wordmark:** lowercase name in Manrope 200 (inkMuted) plus a mint `/`, like `hypernova/`,
  `0stems/` and `prudnikov/`.
- **Family naming:** Hyper* (Hyperscribe, Hypernavigate, Hypernova). Avoid putting third-party
  trademarks in product names.

## Navigation

- **Bottom tabs:** text-only, 10sp tracked uppercase, active in ink and inactive in inkGhost, on
  a blurred bottom bar. Tabs are selectable with Role.Tab.
- **Sheets:** grab handle 36×4, title ends with a full stop, sections headed by seams.
  - Rows: radio circles (18dp ring, 10dp fill).
  - Segmented control: a full-radius track with the active segment filled ink and bg text.
- **Placeholders say honestly when the feature arrives:** "Every past session, searchable.
  Arrives with the daemon in milestone 3."
