# Decisions

These are product decisions agreed with the owner on 2026-10-13. Change them
only on purpose, and log the change here.

## Product
- **Name: Hypernova**, part of the Hyper* family (Hyperscribe, Hypernavigate). We don't use "Claude" in the name because of Anthropic's trademark, but describing it as "a control room for Claude Code" is fine.
- Built to public quality from the start. The repo is local for now (see LATER L4).
- One phone can pair with **several Macs**, with a switcher in the app. Built (owner, 2026-10-30; `android/.../data/Servers.kt`):
  - **One is active**: Home, sessions, chat, Settings, the widgets and the glasses show it, as they showed the one pairing before. (The widget shows every computer since 2026-10-31: "The widget: Needs you"; Home can too: "All servers on Home".)
  - **The others are watched in the background** (each has its own link): their notifications (needs you, finished, errors, approvals) name the computer, and a tap switches to it and opens the session; their needs-you count shows on the chip and in the switcher. Answers, replies and queued prompts go to the computer they belong to.
  - **The Home chip opens the switcher** (a glass sheet): each computer with its link and needs-you count, the active one ticked, and "Pair another Mac" last (the pairing steps, the others kept). It no longer opens Settings. With several paired the chip names the active one in every state, with an amber count while another needs you.
  - **Pairing another computer adds it** (no "Replace?" question any more); pairing one again takes its old place. **Forget** forgets only the active one, and the next comes on screen.
  - The one pairing from before became the first computer, with its own keys (token, certificate, queued prompts) as they were: nothing to pair again.
  - One permanent notification for all: "z2 · +1 watched".
- English only for now (see LATER L5). All strings live in resources.

## Sessions
- **PTY model (4b):** `hc` runs `claude` inside a pseudo-terminal owned by the daemon. The phone and the Mac terminal share the *same* live session. The phone sends prompts and Esc (interrupt) into the PTY, and approvals come through hooks. The phone's chat is rendered natively from the transcript JSONL. Sessions started from the phone run headless, and `hc attach` brings one into a terminal.
- **Mini terminals (5a):** Bash tool calls inside a session render as small live terminal cards.
- **Approvals are answered from the phone** (and later also from the Mac menu bar app: done 2026-10-15, see "The Mac mini app"). If nobody answers within **10 minutes**, the request is denied automatically (**for Codex only since 2026-10-30**: Claude Code's goes back to its own dialog in the terminal, see "Never auto-deny") with a message to Claude: the user didn't respond in time and is probably away, so it shouldn't retry or work around it, and should stop and wait for them to come back. In the app, a session stopped this way shows a **Continue** button that tells it the user is back (milestone 3). Decided 2026-10-13.
- **`claude` starts through `hc`.** A marked block in `~/.zshrc` aliases `claude` to `hc`, so every interactive session can be controlled from the phone without changing habits. `hc` hands non-interactive uses (`-p`, subcommands like `mcp` or `update`, pipes) straight to the real `claude`, and falls back to plain `claude` if the daemon isn't running. `hc uninstall` removes the alias. Sessions started any other way still show up with live transcripts and approvals through the hooks; they just can't be typed into from the phone. The owner left this choice to us; decided 2026-10-13 for the best everyday UX.
- **Usage numbers are the official ones**: the 5-hour and 7-day `rate_limits` (used % and reset time) that Claude Code passes to the status line. The daemon chains into the existing status line script rather than replacing it. Estimates from transcripts are only a fallback, and the app labels them "estimated". Decided 2026-10-13.
- **Daemon runtime until milestone 5:** the system Node (nvm), run by launchd. Bundling Node inside the Mac app comes with milestone 5.
- **The Telegram bot is retired** (2026-10-13): hooks, status line block, launchd service and folder removed. The code is archived in `~/Archive/`. Hypernova installs its own hooks.
- **Sessions survive a restart of the Mac** (owner, 2026-10-16: he wants to "watch from the history what was done last time and continue working on projects"). Built.
  - A conversation a restart cut short stays on Home, marked "Mac restarted · Resume", for 48 hours from the boot, instead of dropping to History. Which ones: open when the Mac went down, or active in the 48 hours before it; the 10 most recent.
  - Opening one shows its whole history. Writing in it resumes it in place: the same conversation, in a new shared terminal. Any ended session resumes the same way, by writing in it.
  - History reaches back 30 days (300 conversations), up from 7 days and 40.
  - Parked: dismissing a card early, a note in the session screen, restarted sessions on the glasses and the Mac popover (LATER L50).
- The 1.0 scope includes everything: approvals (allow, deny, always, edit), inline reply from notifications, new session (project, model, mode, effort), interrupt, AskUserQuestion and plan approval, diff viewer, file browser, attachments from the phone, usage meters, alerts and a widget, history and search, slash commands and skills, share target, Quick Settings tile. The git panel and tablet layouts come in 1.1.

## Design
- 0stems look: green digital cosmos (`DigitalCosmos`), grain overlay (`GrainField`), warm near-black, mint `#7FFFA0`, Manrope 500 with JetBrains Mono.
- **The home sky is one solar system** (decided 2026-10-13, superseding constellations and per-project systems). The **sun is the 5-hour usage**: it ages as usage grows and goes **supernova** at the limit, full-screen and dramatic. A nebula collapses during the cooldown and a new star is born at the reset. **Projects are planets** and **sessions are moons**. The **7-day budget dims the galaxy**.
- Effects: a scramble effect only when a new session appears, subtle haptics, no sounds.
- New logo mark: the **Nova** mark (see DESIGN.md).
- Optimized, but never at the cost of the experience: 30fps cosmos, paused when not visible, and a Reduce Motion toggle.
- **Portrait only for now** (decided while building M1; see LATER L9). Phone layouts are portrait-first, and landscape and tablet layouts come later.
- **Fullscreen, edge to edge.** The app draws under the status bar and around the camera cutout (Nothing Phone 3). Content scrolling under the status bar gets a progressive blur, like Telegram. No black strip.

## Mac
- Menu bar app only. Native SwiftUI, with the TypeScript daemon bundled inside; Setup downloads Node.js (the newest LTS) into the app's own folder when the Mac has none that stays put, so users install nothing.
- The DMG is ad-hoc signed and not notarized (LATER L6): macOS asks once per version (docs/INSTALL.md, "The first launch").

## Android and connection
- Kotlin + Jetpack Compose, minSdk 29 (Android 10), APK through GitHub Releases with an in-app updater.
- **No Firebase for now** (see LATER L1). Background delivery uses a foreground service holding a WebSocket over Tailscale. Its permanent notification shows live sessions and usage.
- Security: QR pairing carries the address, a token and the **pinned certificate fingerprint**. A biometric check guards "Always allow" and bypass mode.
- Transport: Tailscale (`tailscale serve`) by default. An optional local-network listener can be turned on in settings. It uses TLS with the same pinned self-signed certificate plus the token, so other devices on the LAN can't eavesdrop, impersonate the Mac, or call endpoints. The listener only exists while the setting is on.

## Process
- Work in milestones, each ending with something you can install and screenshots.
  1. Design system, cosmos and the home screen with fake data (APK)
  2. Daemon (`hc`, PTY, transcripts, hooks, API, pairing)
  3. Android connected to the real daemon
  4. Notifications, widget, Quick Settings tile, share target, biometrics
  5. Mac menu bar app, onboarding, DMG
  6. Release polish

## Built overnight 2026-10-14 (milestones 2–4 compressed)
The owner asked for a near-final product by morning, plus new features. These were
decided while building; each can be revisited.
- **Sessions Hypernova starts run through the Agent SDK**, not a shared PTY. Each prompt
  runs `claude` through `@anthropic-ai/claude-agent-sdk` with `resume`, so text streams
  live, approvals arrive through `canUseTool`, Interrupt works, and the sessions survive
  daemon restarts. They are ordinary Claude Code sessions (`claude --resume <id>` opens
  them in a terminal). Why: research showed daemon-owned PTYs die with the daemon, the
  `claude` alias collides with claude's own `attach`/`agents` subcommands, and node-pty
  needs a patch on install. The SDK path was proven by the retired bot. The shared live
  terminal (`hc`) was parked as LATER L10. **Superseded the next morning** by "Shared live
  terminals" below.
- **Terminal sessions** are observed through global http hooks plus their transcripts,
  with live status from Claude Code's session registry. Their approvals race the terminal
  dialog (first answer wins). A phone prompt to a terminal session that's still open
  continues it in a fork; once it has ended, it continues in place.
- **Transport:** the daemon listens only on loopback (47321 phone API, 47322 Mac app and
  hooks). Phones reach it through `tailscale serve` on https port 47321 with the
  publicly trusted ts.net certificate, so there's no pinning (the ts.net key rotates on
  every renewal). The pinned self-signed LAN listener is parked (LATER L11).
- **Providers:** profiles that become Claude Code environment variables
  (`ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, model mappings): the built-in
  subscription, OpenRouter, or any Anthropic-compatible custom endpoint. Keys stay on the
  Mac (0600), never sent to the phone. Configurable from the phone and the Mac app.
- **Projects from the phone:** "New project" creates a folder under the projects root
  (default ~/Programming) with `git init`.
- **Artifacts in the app:** project files, images, text, and web previews through signed
  12-hour URLs that a WebView can load (static folders, or any localhost dev server
  proxied through the daemon).
- **Setup is a guided Mac app** (menu bar + setup window): finds or installs Claude Code
  and Node, installs the background service, connects Claude Code, turns on phone access
  in Tailscale, serves the APK by QR, and pairs by QR (the phone's camera opens
  `https://hypernova.observer/pair#…`, an App Link, straight in the app; it was a
  `hypernova://pair` link, which cameras don't open). No Terminal commands.
- **Testing checklist:** the daemon serves a checklist page (`/qa`) where the owner marks
  each check, writes notes and pastes screenshots; results land in
  `~/Library/Application Support/Hypernova/qa/`.

## Shared live terminals (2026-10-14)

The owner asked for shared live terminals ("i wanna have shared live terminals") on the
morning after the overnight build. This replaces the fork behaviour and makes the SDK
path a fallback.

- **One holder process per session** owns claude's PTY and a headless xterm of its
  screen. It runs detached, so closing the terminal window or restarting the daemon
  leaves the session running; the phone and any number of Mac terminal windows attach
  to it. Every client joining gets the current screen.
- **`claude` in a terminal is shared automatically.** The integration step adds a
  marked `claude` shell function to `~/.zshrc`; it only takes over when stdin and stdout
  are a terminal, and hands subcommands (`mcp`, `agents`, `attach`…), `-p`, pipes and
  `--bg` straight to the real claude. Exit code 97 from `hn` always falls back to plain
  claude, so the command never breaks. `command claude` or `HYPERNOVA_OFF=1` skips it.
  This answers last night's collision: the wrapper is named `hn`, and claude's own
  `attach`/`agents` pass through.
- **Phone sessions are shared terminals too**, so "Open on the Mac" brings one up live
  in the owner's terminal app (Alacritty's own IPC, or a `.command` file for Terminal.app
  and iTerm). The Agent SDK path stays as a fallback when node-pty can't load or the
  hooks aren't installed.
- **On the phone, a session has two views:** the native chat (as before; prompts are
  typed into the live terminal) and the Terminal: the real Claude Code screen in
  xterm.js, bundled in the app, with a key bar (Esc, Tab, Shift+Tab, arrows, ^C, /,
  paste, Enter). The keyboard goes through a native input connection (Termux-style, no
  autocorrect), because xterm.js's own text box drops letters with Android keyboards.
- **Size follows whoever used it last** (tmux's "latest"): the phone's screen once you
  type there, the Mac's again when you type there. A phone that is only watching shows
  the Mac's screen whole, scaled down, so neither side's screen gets garbled.
- **Closing a terminal window ends a session started there** (30 seconds later), as plain
  claude would, so dev servers and memory don't pile up. It keeps running if the phone has
  it open at that moment, or if you detach with Ctrl+\. Sessions started on the phone keep
  running until they end or sit unwatched for 12 hours (and quiet for 2).
- **Approvals** still come through the hooks, so a shared session shows approval cards,
  questions and plans in the chat view; the terminal's own dialog can answer too.
  Prompts from the phone are typed only into Claude Code's own empty input box, and Enter
  follows only once the text shows there, so it can never answer a dialog by accident.
- **Removing a phone cuts it off at once**, its live terminal included.

## Attachments from the phone (2026-10-14)

The owner asked to "attach files from the app" (attachments from the phone were already in
the 1.0 scope).

- **A paperclip in the composer** opens a small tray: Photos (the system photo picker),
  Camera and Files (any file). The new-session sheet has the same three under its prompt.
  Up to 10 files per message.
- **Files upload as soon as they're picked**, with a thumbnail and a spinner on each chip;
  Send waits until all of them are on the Mac. Chips stay while you move around the app,
  and come back with the text if sending fails.
- **Photos are scaled down on the phone** (2048 px on the long side, JPEG), which also
  turns them upright and drops their location. PNG screenshots that are small enough go
  as they are, so text stays sharp.
- **On the Mac they live in `~/Library/Application Support/Hypernova/attachments`** for
  30 days, and Claude Code gets them as `@"path"` mentions before the text. That works the
  same in shared terminals, SDK sessions and new sessions, needs no permission prompt,
  and shows images to Claude as images. Measured before choosing: a plain path makes
  Claude ask for permission to read outside the project; a mention doesn't.
- **The chat shows them apart from the text**: thumbnails for pictures, a tile with the
  name and size for other files. Tapping one opens it in the app's viewer.
- **Claude may read attached files without asking** (the owner sent them): a PDF isn't
  inlined by the mention, so Claude opens it with Read, which Hypernova pre-approves for
  the attachments folder only. Found while testing: Claude Code reads a PDF's page ranges
  only with poppler installed, which this Mac doesn't have, so without it Hypernova has
  the first read take the whole PDF. With `brew install poppler`, long PDFs work in page
  ranges too, also in the owner's own terminal sessions.
- **Uploads stream** (phone and Mac never hold a whole file in memory), two at a time, on
  their own connection pool so prompts and approvals never wait behind a big file.

## Notifications, updates and the owner's first feedback (2026-10-14)

From the owner's testing notes (items 1, 2, 4 and 8 of his list) and the session where a
message with two pictures "did nothing".

- **In-app banners** (item 4): while the app is open, a banner slides in at the top when a
  session needs an answer (approval, question, plan), waits in its terminal, finishes a
  turn, or stops on an error; tap it to open the session, swipe it up to dismiss. Never for
  the session whose chat or terminal is on screen. In the background the same events are
  system notifications ("Session updates" channel; approvals keep their own).
- **App updates**: every Android build gets a higher versionCode (minutes since 2026-01-01)
  and a version note next to the APK; the daemon reports it in the snapshot. A newer one on
  the Mac shows a banner once and an "Update" card in Settings, which opens the Mac's
  download page in the browser.
- **Unsent text in a shared terminal stops a prompt, visibly**: instead of a toast (it was
  behind the keyboard), a card shows the text that's in the way with "Replace and send" and
  "Open the live terminal". What's left of the phone's own interrupted prompt is cleared
  without asking. Toasts sit above the keyboard now.
- **Permission mode from the phone** (item 2): tap the session's status line; a shared
  terminal switches by Shift+Tab, checked against Claude Code's footer.
- **A session keeps the folder it started in** (item 1): hooks carry Claude's current
  folder, which moved sessions to subfolders and worktrees and showed them as new projects.
- **Text can be selected and copied** (item 8): your messages and Claude's replies.
- **Fewer approvals during research** (item 7): a research run fetched 61 sites through six
  subagents, and Claude Code asks once per site. Web and read approvals offer "Allow all web
  pages / web searches / file reads this session": one answer covers the ones waiting and
  the rest of the session. Commands and edits stay per-command ("Always") or go with a mode,
  since "all commands" is too broad.
- The prompt box keeps the paperclip and Send on its last line as the text grows.
- **Never silence after a prompt**: from the prompt to the reply, the session's status line
  says what Claude is doing ("THINKING", "BASH") with a timer, in dim green, and its
  activity meter, a small planet, spins on its axis. That planet replaced the three bars
  everywhere (they read as an audio meter). Tried on the way: a vibrating string under the
  last message and in the line, a plucked string, a comet crossing the line with a trail of
  glyphs, and a moon orbiting a planet; the owner chose one planet spinning in place. It
  never snaps between states: finishing, it slows to a stop and fades to grey. The status
  row has no hairline.
- **Older shared terminals get the new behaviour too**: a holder keeps the code it started
  with, so the daemon does "replace", "clear our leftover" and mode changes itself for
  holders that don't announce those capabilities (from their screen, with keys).
- **Reading Claude Code's screen** allows for the owner's status line between the input box
  and the mode line. "Claude is working" is any of: Claude Code's session registry says
  busy, a spinner shows, or the screen is still changing (it shows no spinner while it
  streams text). The input box is only ever cleared when none of them holds.
- **Terminal menus as buttons** (item 3): when a shared terminal shows a menu or dialog in
  place of the input box (the /model picker, "Switch model?", a permission dialog with no
  card on the phone), the daemon reads it off the screen and the session shows it as a card:
  a tap arrows to that option and presses Enter, Cancel presses Esc, and a footer's other
  key ("s to use this session only") becomes a switch. Not while an approval card answers the
  same dialog. A menu counts once it holds still for two looks (under two seconds), and an
  answer names the menu it was for, so it never lands in a different one. Menus that scroll
  say so ("More options in the terminal"); typing into one (AskUserQuestion's "Type
  something") still needs the terminal.
- **The planet is the logo** (owner: "make the planet we used for status bar as the icon"):
  the launcher, notification, Mac app and menu bar icons and the in-app mark are the activity
  meter's planet, still, turned so its two meridians sit evenly.
- **The home sky's planets look like planets** ("now planets look like stars"): lit spheres
  that turn while a session in them works, sized by how long their sessions have run (moons
  too). Orbits aren't drawn whole any more: each planet trails a fading tail of its orbit.
- **The sun looks like a real star** ("make a star look like a more real one, in terms of
  effects as well"): a smooth bloom instead of flat rings, a corona of uneven streamers,
  limb darkening, granulation, diffraction spikes (no twinkle: there is no air in space); near the limit,
  sunspots and prominence arches instead of straight flare lines.
- **"/" lists commands and skills** (item 5): typing "/" in a session's prompt box (or the
  new-session prompt) lists what Claude Code offers in that project: the owner's skills and
  commands and plugins' first, then Claude Code's own, each with what it does and what it
  takes. Typing filters, best match first; a tap puts "/name " in the box and shows the
  arguments it takes. The list comes from Claude Code itself (an SDK process asked for its
  commands, hooks off, no prompt, about a second, then cached), so it's always the installed
  version's. Commands only the interactive terminal has (/resume, /export, /rewind…) aren't
  in it, but typing them still works; menus they open show as cards.
- **Commands from the phone show as sent, with their output under them** (the owner sent
  "/effort", then "/effort ultracode", and the chat stuck on "Sending…" with no answer): a
  slash command shows as typed, args included, and what Claude Code printed for it
  ("Ultracode on (this session only)…") sits under it; "!" shell commands too, with their
  output. "Unknown command" and Claude Code's other warnings show as notices, and a command
  that leaves nothing behind (a picker closed with Esc) doesn't stay on "Sending" for good.
  A session is named after its first prompt, a skill given a task or a "!" command, never
  after "/effort high"; one with only commands is named after the first.
- **Three kinds of command, three looks**: Claude Code's own commands (/effort, /model) are a
  slim COMMAND row with their output under them; skills and the owner's own commands are a
  mint chip with the text he typed as the message (Claude answers them); a shell command
  ("!") is a SHELL row, like a line in a terminal. Typing in the prompt box, the leading
  /name is highlighted. Terminal and Hypernova (Agent SDK) sessions look the same, and a
  session's title never shows a key typed into a command (it's masked as •••).
- **A message sent while Claude works says it's queued** (item 11, the owner: "when I send a
  message while the agent works, it stays sending … the status of a message should be 'waiting
  for the previous response to finish'"). Claude Code takes a prompt while it works and hands
  it to Claude when the step it's on finishes (or as the next turn). The phone shows it at the
  bottom, under Claude's live output, as the terminal does: "YOU · QUEUED" and one quiet line,
  "Claude reads it when this step finishes" ("Waits for the current reply to finish" in a
  Hypernova SDK session, which runs one prompt at a time). The header keeps saying what Claude
  does, with "1 queued". Once Claude has it, it becomes the message in the same place; prompts
  typed in the Mac terminal while Claude works show the same way. Stop in a shared terminal
  keeps the queue, as Claude Code does (the prompt runs right after); Stop in an SDK session
  drops it and says so ("Stopped. 1 queued message wasn't sent."), so nothing hangs. A queued
  message that doesn't go (dropped, or taken back into the terminal's box with the Up arrow)
  leaves the chat, and its text goes back into the phone's message box. A slash command sent
  while Claude works isn't marked queued unless Claude Code's queue shows it: most run at once
  (/cost, /model open their panels over the work).
- **Tables in replies read as GitHub reads them** (decided 2026-10-14): a table runs until a
  blank line or a line without a pipe, so two tables written back to back with no blank line
  between draw as one card, as on GitHub (the second one's header becomes a row). This replaced
  splitting at the second table's separator, which also split a table at a `|---|---|` divider
  over its totals row. A row of only dashes (three or more in each cell, colons allowed so a
  glued table's separator counts, empty cells aside) draws as a firmer hairline across the card
  rather than a row of "---", and TalkBack skips it; one still being typed waits until it is
  whole, so it never flashes up as dashes first.

## Agents and workflows (2026-10-15)

The owner (item 12): "add agents which are run by a main one in a session, so we see them." Claude
Code 2.1.29x no longer writes subagents' turns into the session's transcript: each agent has a file
of its own, and an Agent or Workflow call returns at once while its agents work for minutes. Until
now the phone showed such a call "done" half a second after launch, with Claude Code's internal
launch note as its output, and nothing of the agents themselves.

- **Each agent is a card under the call that started it**, in the mini terminal's frame: its own
  planet (turning while it works, amber while it waits for an answer, still once done, a cross when
  it failed), "AGENT · EXPLORE" with how long it has run, its tokens and tool calls, what it's for,
  and a live mono line of what it does now ("▸ Bash: ./gradlew build", with the cursor). Once done,
  the card shows the first lines of its report. A tap opens the agent's own transcript, read-only:
  its task folded at the top, everything it did, its report last, fetched again every 1.5 s while
  it runs. Agents an agent started sit under its card.
- **A workflow is one card**: its name and summary, a rail of its phases ("SURVEY 4/4" in mint, the
  live one turning, the rest ghosts), and its agents one line each, grouped by phase; a finished
  phase folds to "+4 done". A row opens that agent.
- **The session says when only its agents work**: Claude Code stays busy while agents run after the
  main turn, so the session read "working" with nothing happening. Now the main agent is idle then,
  and the status line reads "AGENTS · 3 running" (while the main agent works too: "· 3 agents").
  A turn that ends with agents still at work doesn't notify as finished; the turn started by the
  last agent's report does, once. On the Home card (a live "2 AGENTS" chip), in the connection
  notification ("1 working · 2 agents") and on the Mac's menu bar planet and popover, its running
  agents show too, so there a session whose main agent waits on agents never reads as done. Lens and
  the widgets don't count agents yet: Lens announces such a turn as finished (a blip and a DONE
  card), and the widgets count the session as idle (LATER L28).
- **Agents end with their session**: when Claude Code quits (SessionEnd, except for `/clear` and
  resume, where the process lives on; or its process leaves the registry), the agents and workflows
  it ran in the background end with it: an agent is done if its report was its last word, else
  stopped, and its call ends the same way. A workflow none of whose agents run and whose files have
  been quiet for 2 minutes in an ended session (2 hours in a live one) ends stopped, so no card
  spins forever.
- **Approvals name the agent that asks** ("NEEDS YOU · BASH · survey:agents"), and the main turn
  moving on no longer closes an agent's approval as "answered on the Mac".
- **Cheap by design**: what an agent does comes from the hooks it already sends (no file read);
  folders of live sessions are looked at every 2 s and a file is read only when it changed; only
  running agents' own files are followed; a whole agent transcript is read only when the phone
  opens that agent, and while it stays open the phone asks every 1.5 s only for what changed since
  its last look (usually nothing), then once more for the whole of it when the agent ends. No new
  hooks (SubagentStart/Stop would make existing installs look unconnected until a reinstall).
- Item 23, the owner's call: **approvals from agents and background tasks notify only if they're
  still waiting after 10 seconds** (agents often settle them themselves, or the run moves on); the
  main agent's notify at once, and every approval's card is in the session at once either way.
  Background work that asks always runs as an agent (a workflow's agents too), so "from an agent"
  is the test: the approval carries the agent's id.

## Hypernova Lens (2026-10-15)

The owner wants "an on the go app for developers so wherever they are they can still develop
products, see results": a developer out walking with XR glasses, the Mac in the backpack, voice as
the keyboard. Hypernova Lens (`lens/`) is that app for Even Realities G2 glasses. LATER L3 had it
"after 1.0"; v1 was built on 2026-10-15. `lens/README.md` shows every screen; `lens/DAEMON.md` is
what Lens relies on from the daemon, and `docs/PROTOCOL.md` stays the daemon's whole contract.

- **A third client of the same daemon.** Lens is a web page inside the Even App on the phone (Even
  Hub SDK, TypeScript, Vite, no framework). The Mac does the work, the glasses are where you glance
  and decide, the phone is for reading, and the earbuds are a second screen. Nothing goes through
  anyone else's server.
- **v1 is the owner's five things**: the glance, talk, needs-you approvals, result cards read
  aloud, and "show me" screenshots. On 2026-10-16 "show me" grew into Telescope (below), which also
  shows the emulator, a terminal, pictures and Mac app windows.
- **The glance**: up to four sessions that matter (working, needing you, or finished in the last 45
  minutes), the one that needs you on top, the 5-hour usage in the corner. Each line has the app's
  small planet (turning while Claude works, in a focus bracket when it needs you, with a ring when a
  reply waits), and a sun above them is the usage. Text uses the glasses' own text boxes, so a
  change is one small Bluetooth write. A double tap quiets the lens; only something that needs you
  brings it back.
- **Talk**: hold the temple and speak, with captions as you go; let go, then tap sends to the
  session in front of you, double tap throws it away, a swipe sends it elsewhere. Naming a project
  routes it ("in hyperscribe, …"), "new session in 0stems: …" starts one, and "send it" at the end
  skips the tap. Nothing is sent until you tap or say so, so a misheard prompt never runs.
- **Needs you, safely**: "hypernova wants to run git push origin main"; tap allows, double tap
  denies, and taps are ignored for 700 ms after a card appears, so a stray tap can't approve.
  Spoken "approve", "always allow" and "deny, use a feature branch" answer only the card on the
  lens; said anywhere else they bring the card up to read first (a reason said with "deny" stays
  with it). "No, …" is a deny only on a card that asks; anywhere else it's a prompt. Every card can
  be left unanswered (a swipe on a command, "Back · answer later" on a question or menu, Back in
  the long-press menu), and a new approval doesn't take the focus while you're swiping. Why: a
  first build let "approve" allow another session's `git push` that was never on the lens.
- **Pairing**: the Mac's "Pair a phone" QR, scanned in Lens (or its link pasted), works before the
  glasses connect. Each code pairs one device, so the phone app and the glasses each take a fresh
  one (the Mac mini app makes a new code every time). The daemon keeps `client: "lens"` with the
  device. The token lives in the Even App's storage for this app, not the WebView's localStorage.
- **How it talks to the Mac**: the phone API, with a device token of its own. A page in the Even App
  is cross-origin, so the daemon answers `/v1/*` with CORS (`*`, no credentials: the token is a
  header, never a cookie), and the WebSocket takes the token in its query string (a page can't set
  headers on one); if the socket keeps failing and HTTP works, Lens polls the snapshot every 4 s. A
  packaged app can only reach the hosts in its whitelist and the Even Hub documents no wildcards, so
  `npm run pack` writes this Mac's Tailscale name (ports 47321 and 7443) into a git-ignored
  manifest; the repository never names a tailnet.
- **Speech goes through the Mac**: while you hold the temple, the glasses' microphone streams
  through the phone to the Mac over Tailscale; nothing is recorded. v1 used Hyperscribe Companion
  (`wss`, port 7443). Since 2026-10-15 the Mac can use Soniox instead ("Voice through Soniox", below)
  and the Companion stays as the other way. The daemon hands Lens the Companion's address
  (`GET /v1/speech/companion`: only its tailnet https route, never a LAN `ws://` that would expose
  the token, the audio and the words), so the phone pairs once, with Hypernova. The Companion's
  client is Hyperscribe's own, copied in.
- **Results**: when a session finishes, a soft blip and a card: the reply's first sentence and what
  the session's own events show (files changed, tests passed or failed with their counts, builds,
  how long it took). A long reply scrolls (see "A long text on the glasses scrolls", below); a tap reads it aloud. The facts come from
  `events?limit=120&output=8192&turn=last` (each output's last 8 KB, where test counts are); when
  the daemon's last turn starts at a slash command (`/cost` after a reply), Lens asks again without
  `turn=last` (LATER L23).
- **Read aloud by the Mac**: Android's WebView has no speechSynthesis, so the Mac renders the reply
  with `say` (AAC) and the phone plays it. Lens sends a long reply in pieces of at most 8000
  characters, cut between paragraphs, the next rendered while the last plays; one clip at a time per
  device. The text goes to `say` on its standard input and is never logged or written down; the last
  6 clips (32 MB at most) stay in memory by a hash, each dropped exactly 10 minutes after it was
  made, so reading a reply again is instant without the replies lingering. Until the phone has been
  touched once, the lens says "Touch your phone once to allow sound".
- **Telescope** (built 2026-10-16; the owner's "Telescope" note, in the second round below):
  "telescope" puts what the session in front of you is building on the whole lens, from wherever it
  shows: its web page (headless Chrome, as Show me did), the Android emulator or a phone (adb), the
  iOS Simulator, its shared terminal (drawn crisp by the Mac in Hypernova's terminal colours), the
  last picture, chart or PDF page it made, or its Mac app's window. The Mac picks: each source the
  transcript points at (commands it ran, files it wrote or read; never what Claude only wrote
  about), newest first, then a page served from its folder, then the terminal as the fallback for
  command-line work. The terminal isn't ranked by recency, because a web session's last command is
  usually `npm test`. "Telescope emulator / web / terminal / simulator / image / window" picks one;
  "telescope localhost 3000" a page; "telescope pdf page 3" a PDF's page. "Show me" is a quiet alias
  only on its own; "show me the app" or "show me localhost 3000" with more words is an ordinary
  prompt. The lens shows "TELESCOPE · project · source · just now" over a 576 × 256 picture. Tap
  zooms into a quarter cut from the full-size picture; swipes go round the quarters, double tap
  zooms out and then goes back, and a tap on a quarter opens the picture on the phone. "Watch" looks
  again 3 s after each answer for a minute and stops at any gesture. A picture that didn't change
  isn't reloaded or sent again, and only the parts that changed are redrawn.
- **Telescope's limits**:
  - The web source keeps Show me's rules. Only http(s) on this Mac's loopback and never the daemon's
    own ports, checked on every page load (the first address, each redirect, each script or meta
    refresh). Addresses Claude only mentions don't count: a first version that took any localhost
    address from a transcript opened the daemon's own API. Each capture runs in a throwaway Chrome
    context in a profile of its own (never the owner's cookies), with downloads denied, preloading
    off and no debugging port; 20 s at most. Pictures are kept 0600, served for 12 hours by an
    unguessable name and deleted after 24. Frames inside a page aren't held to the loopback rule
    yet (LATER L22).
  - Pictures from files only from inside the session folder by real path, never a secret file,
    never Hypernova's own folder, and only files whose bytes are really a picture.
  - Programs run by full path with fixed arguments, never through a shell.
  - **Mac app windows only of a program whose real path is in the session folder** (or Xcode's build
    folder for a project in it). Never by bundle id, which any folder could claim, and never for a
    session in the home folder. An installed copy elsewhere, like `/Applications/Hypernova.app`,
    isn't shown, on purpose. The Mac app takes the picture, because the daemon can't hold Screen
    Recording in a way the owner could grant. Screen Recording is asked for once, the first time the
    owner says "telescope window"; when the telescope picks by itself it never asks.
- **Frames only on real news**: the planets are a small picture, and every picture costs a Bluetooth
  write. Frames go out only while something moves, at most about three a second, never ahead of
  text. News is the lens showing something new, from the Mac or a gesture: its words with the
  clock's own ("2m", "1h 12m", "now") masked, or the planets' states, focus and usage. The clock, a
  redraw that shows the same, and the hub repeating itself while the Mac is away (reconnects, failed
  checks, a 4 s poll that finds the same snapshot) aren't news. After 90 s without news one last
  frame puts everything at rest; after that only the clock's text goes out, about once a minute.
  Why: an earlier build never stopped: 537 pictures in 3 minutes with nothing happening, because the
  clock counted as news.
- **A session's screens follow it.** A result card is worked out from the turn as it is now: the
  hub's copy, which the socket keeps current. A turn that ran while the socket was down (no finish,
  no events) shows once the Mac is back: a card whose session moved on since (newer activity) is
  loaded again, after a reconnect or on a poll. A reply said on a session's result or live view goes
  back to that session's screen, which shows it at work once the prompt starts it.
- **The time and the batteries on the glance.** The glance shows the time; the batteries show on
  request (second round, below). The glasses' level comes from the Even App's device status. That
  status writes 0 and "none" for what it leaves out, so a 0 off the charger, or a status that
  doesn't say how the glasses are connected, is no reading, and the last level stays. The Mac's
  level is left out of "status" while the Mac is away, because the last level would pass for the
  current one.
- **The phone screen**: inside the Even App, Lens has a screen in the Hypernova look: the living
  sky, a live mirror of the lens with the gestures as buttons (it works without glasses), the last
  "show me" picture, a box to type a prompt instead of saying it, and settings (the Mac, which
  sessions the lens shows, the speech companion, the read-aloud voice and speed, the blip).
- **AGPL-3.0-or-later** (until Lens 0.1.11; Apache-2.0 since, see the owner's morning answers), because Lens copied Hyperscribe's companion client (`lens/src/companion/`),
  which is AGPL. The rest of Hypernova isn't affected: Lens is a separate program talking to the
  daemon's public API. The app shows its license and the third-party notices under "Source and
  licenses". The owner wrote both, so he can relicense the three copied files if Lens should ship
  under another license. Before the `.ehpk` goes to anyone else, its source link needs a real
  address (the repository has no remote yet; LATER L4).
- **Lens versions.** The owner uploaded Lens 0.1.2 to the Even portal on 2026-10-16. The next glasses build is 0.1.3.
- **Checked in the Even Hub simulator 0.9.5, not yet on glasses**: whether a long press reaches the
  app (if not, the long-press menu's hands-free Talk is the way in), whether a double tap arrives
  with a tap first, the whitelist with ports and `wss://`, and the real Companion. The list is in
  `lens/README.md`, "Not verified yet". There's no development shim any more: Lens is developed
  against a second daemon from this tree.

## Home-screen widgets (2026-10-15)

Replaced on 2026-10-31 by one "Needs you" widget across every paired computer ("The widget: Needs
you", below); kept here as history. The owner picked two: a small one with the sun and "2 working · 1 needs you", and a large one with
up to four sessions and Allow / Deny on the widget.

- **Small: the sun.** The 5-hour usage drawn as the sky's star (it ages with usage, a protostar at
  the limit, an ember at the weekly limit, drained of colour when offline), "72% · resets 16:40"
  (amber past 80%) and "2 working · 1 needs you" or "All quiet". 2 × 2 by default; at one cell only
  the star, the percentage and an amber dot when someone needs you; wide, the words sit beside it. A
  tap opens the app.
- **Large: sessions.** A header with the counts (the needs-you part in amber), the oldest approval,
  up to four sessions (a planet, the title, the project, what it's doing; a tap opens that session),
  and the 5-hour and weekly meters. 4 × 3 by default, down to 3 × 2; when room runs out the approval
  comes first, then one session, the meters, then more sessions.
- **Allow and Deny only when the whole command shows** (or the one path, pattern, address or search
  a read-only tool asks about: Read, Glob, Grep, WebFetch, WebSearch): exactly as it will run, line
  breaks and all, nothing cut, nothing masked, no invisible or reordering characters, and only at a
  size that shows all of it. Anything else gets "Open", which opens the session. Questions, plans,
  edits, agents and other tools always open the app, and "Always" stays in the app. Why: Allow
  promises the exact command, and a cut or masked line can hide what follows. The answer goes the
  session screen's way (`POST /v1/approvals/{id}`), with "Allowing…", then gone or "Didn't go
  through".
- **Rows hold still**: needs you first, then working, then idle; within each, busy sessions by when
  they started (newest first), idle ones by when they last did something (newest first). By latest
  activity, two busy sessions would swap places every few seconds and the widget would redraw each
  time.
- **It updates without the app on screen**: while the background connection runs, the widgets
  follow the Mac with no screen of the app open.
- **Stale never looks fresh**: a picture is live only while the app is on screen or the background
  connection runs. Otherwise it says "Last updated 14:05" (a weekday or date when older), or "Not
  connected to your Mac". An approval past the Mac's 10 minutes (Claude Code's goes back to its terminal then,
  Codex's is denied) loses its buttons when the widget isn't live, and a reset time that has passed draws as a fresh window, not as a future time.
- **Cheap**: no update period (it would wake the phone every half hour to redraw what's known), no
  timer of its own, and no redraws while the screen is off: the newest state is drawn when it comes
  on. Changes you'd act on (who's working, who needs you, an approval, the connection) redraw within
  1.5 s, anything else at most every 12 s. A picture that goes out of date by itself (a reset
  passing, an approval the Mac gives up on) is redrawn then by an alarm that doesn't wake the phone.
- **RemoteViews, not Glance**, which would add WorkManager for nothing. Widgets can't load the app's
  fonts, so the system sans at Manrope's weights and monospace stand in.
- **Secrets stay off the home screen**: titles, activity lines and commands are masked by the one
  rule for secrets ("One rule for secrets", below), which the widget shares with the Mac and the
  phone. It shows at most the first 600 characters of a text.

## The Mac mini app (2026-10-15)

The owner: "the button (logo) that opens the mini app should spin when there is activity", and a
popover with the sessions, approvals answerable there, usage and quick actions.

- **The menu bar planet turns while anything works**: a session, or agents a session started, its
  main agent idle or not, so an overnight workflow keeps it turning. One turn in 3 s, spinning up
  over 0.7 s; when the work stops it slows over about a second and stops on its rest pose, never
  mid-turn. Still when idle, outlined while the background service isn't answering. Its tooltip and
  VoiceOver label say it in words ("Hypernova: 1 needs you, 2 sessions working, 3 agents working").
- **Needs you is a dot** cut into the planet's upper right, so it reads on light and dark menu bars
  (the icon is a template, so there's no amber); from two waiting, the number shows beside it.
- **Reduce Motion**: a working icon breathes (a slow fade) instead of turning, so working still
  looks different from idle.
- **It costs nothing while still**: an AppKit status item swapping pre-drawn frames, with a timer
  only while it moves, paused while the displays sleep.
- **The popover is a mini app**: a glass panel under the icon in the hyper-cosmos look, top to
  bottom:
  - the header: the planet logo (turning while sessions or agents work, amber when something needs
    you), `hypernova/`, the Mac's name, and a quiet pill for the connection ("tailnet · 1 phone");
  - one-line notices: a newer background service (Update), unfinished setup, a Tailscale key about
    to expire (Fix), a service error (Restart);
  - NEEDS YOU;
  - SESSIONS: needs you first, then working (and idle with agents at work: "agents · 2 running"),
    then idle; six at most, then "+ 2 more on your phone". A click opens a shared session's
    terminal, a phone glyph shows when a phone has it open, and one stopped while you were away has
    Continue;
  - USAGE: the 5-hour and weekly meters with their reset times;
  - quick actions: Pair a phone, Checklist, Settings; Quit in the footer.
- **Approvals are answered there**: the tool, project and title, Claude Code's reason, the exact
  command (up to 12 lines, as on the phone), the time left of the Mac's 10 minutes, then Deny, Always
  and Allow, and "Allow all web pages this session" where the daemon offers it. An answered card
  folds to one line ("Allowed · git push") for 1.6 s, then goes. Questions and plans show one line,
  "Open terminal" and "or answer on your phone".
- **Pairing a second device**: the Pair page asks for a fresh single-use code every time it opens
  and shows it as a QR, watches for the new device every 2 s, renews the code before it expires, and
  offers "Pair another" with a new code, so the glasses pair after the phone.
- **Settings holds the old menu**: the phone access address, paired phones (Forget), the background
  service (Restart, Show log, Update), Providers, Open setup, Open at login, Uninstall.
- It closes on Esc, a click elsewhere, another app or a space switch. Open, it reads the snapshot
  every 1.5 s; closed, every 5 s, so the icon starts turning soon after a session does. The planet
  replaced the three bars everywhere on the Mac.

## The sky, second pass (2026-10-15)

The owner's notes 15–22 on the home sky, plus a new eclipse. These are the decisions;
`docs/DESIGN.md` (Atmosphere, "One solar system, and the sun is your usage") holds the look in
detail.

- **15, no replays** (the explosion and the rebirth kept repeating): a lifted limit counts only at
  its reset time or once it has held for 20 s, and the same limit coming back never explodes twice
  (nor does the universe die twice). A limit that came while the app wasn't watching fades in with
  no blast, and a rebirth or Big Bang that happened more than 10 s before the app saw it isn't
  replayed. The orbits come back smoothly as the planets form out of the collapsing gas.
- **16, the reborn star**: it ignites deep red in its dust, then warms through orange and pale gold to its own colour.
- **17, out of the plate** (owner: "they move on a plate"): each orbit is tilted, turned and
  slightly eccentric, seen from 20° above, with planets faster near the sun; each planet turns about
  its own tilted axis while a session in it works, is lit from the sun (a crescent in front of it),
  and its moons circle its equator.
- **18, the supernova** (it "looks like confetti"): a blinding flash, a shock shell, and hot gas
  cooling from white to red into a remnant of thin filaments; the planets flare as it passes and keep
  their orbits (changed 2026-10-16). No coloured dots.
- **19, the heat death** (not smooth): it went unseen, because the 7-day meter sits below the sky.
  Now it cools over 14 s (names first, the sun through ember to black, the stars burning out one by
  one, the planets drifting off), and when an event in the sky starts while you watch, the list
  scrolls back up to show it. Opened later, it settles into the dark in 1.6 s.
- **20, planets out of gas**: at the Big Bang (after the 5-hour limit the planets survive, so they only light up again), dust flows onto each orbit as a
  faint lane and gathers into the planet, inner ones first, labels last.
- **21, no size jumps**: one continuous depth scale (no step as a planet passes behind the sun), a
  session's length never shrinks while it lives, and sizes ease over a few seconds.
- **22, parallax**: tilting the phone shifts the sky's layers by up to 9 dp, the solar system stays
  put so taps land. On only while the sky is visible and the app is in front; off under Reduce
  Motion.
- **The eclipse**, for when the app can't follow usage (signed out, a plan that ended): a dark disc
  slides over the sun with a diamond ring, leaving the corona and the line saying why. Until those
  states drive it, the demo shows it on a long press of the sun.

## Claude Code on the phone itself (research, 2026-10-15)

The owner: "can't we run claude code in kind of linux image or container on android? ... So right
on device". Research only; nothing was built. `docs/ON-DEVICE.md` has the whole of it.

- **Yes, without root, as a Linux userland rather than Docker**: Claude Code's official linux-arm64
  build runs unchanged in Debian under `proot` (Termux first, our own userland inside the app
  later), and so does the core of the daemon; the app would show the phone as one more host, "This
  phone". Proven in Linux arm64 containers on the Mac, not yet on a phone. There's no Android build
  of Claude Code, and Docker on a stock phone is a no.
- **Where it would start**: a half-day spike on the Nothing Phone 3 (Android's system call filter,
  the process killer, sign-in, speed, battery), then a Linux layer in the daemon, which also gives
  Hypernova on any Linux box. Parked as LATER L26.

## The owner's second round of notes (2026-10-15, evening)

From his notes after a day with the glasses, and his answers to the questions that followed.
The build details go in the sections above as each piece lands; this records what was decided.

- **The black hole is the supernova's remnant, not a replacement for the star** (owner: "What if
  we replace star with a black hole?"). The star is the 5-hour window and ages with it, so it stays.
  At the limit the supernova's core collapses into a black hole for the cooldown. He noted "in
  reality, black hole doesn't turn back into a star", so at the reset the hole evaporates in one
  flash (Hawking radiation, time-compressed like the 5-hour supernova itself) and the leftover gas
  collapses into the new red star. Sky effects stay physically honest. Built; see "The sky, third
  pass" below.
- **Voice through Soniox for now** (built; see "Voice through Soniox", below. "it is good for real time"): the daemon relays the audio to
  Soniox's real-time API with the owner's key, which stays on the Mac. The Hyperscribe Companion
  stays as the alternative. Voice is offered during the Mac app's setup, not only when glasses
  pair; once it's set up, the phone's composer gets a mic. On the phone, speech is reviewed before
  sending ("Review it, but it should be done fast"): captions show while you hold, and on release
  the text is in the box with Send one tap away. No confirm step, no wait. Built 2026-10-16; see
  "Voice on the phone" below. (The glasses differ on purpose: they have no Send button.)
- **Statistics instead of achievements** ("Maybe it is better to have some statistics? Like hours
  worked, tokens used and so on? So it would also have cosmos aesthetic"). **The sun opens it; it
  isn't a tab** (the owner's decision). Home's bottom bar stays Sessions, History, Settings
  (Automations will join later). Tapping the sun opens Statistics as a screen of its own; Back
  returns to Home as it was. While the sun is eclipsed (signed out, no plan, on hold) its tap still
  goes to the account card. The tap is taught once: the sun's readout gets a second line, "Tap the
  sun for your statistics", until Statistics is first opened. TalkBack has "Open statistics" among
  the sky's actions.
  - **An answer never stands in for another range.** Switching to a range with no answer yet while
    the Mac can't be reached shows "Couldn't load statistics" with Try again, not the last range's
    numbers. A range's own old answer shows with "Couldn't refresh. These are from 14:05."
- **Lens shows a working session live, in two boxes** (owner: "upper shows commands which are now
  running, and below it the output of a session"). The output auto-scrolls, agents show
  everywhere, and a session that is working never shows its old reply as if it were current.
  Reading aloud has its own on/off switch. The glance shows the time. The batteries of the glasses
  and the Mac show on request (the owner chose this over a line on the glance, "GLASSES 84% · MAC
  62%" looked ugly): say "battery" or swipe up on the main screen for a small card, each with its own
  drawn icon, a 5-segment bar, the percent and "charging"; a Mac without a battery isn't listed. A
  battery at 15% still pops up by itself.
- **The Mac's battery shows in the apps** (owner: "don't forget that mac mini and desktop macs
  don't have battery"). The Mac popover shows it only when it's at or under 20% and not charging:
  one quiet amber line under the Mac's name. The phone and the glasses keep their 15% warning. A Mac
  without a battery shows nothing. (Whether both apps should use one rule is a question below.)
- **Limits on other providers** (built 2026-10-16): other providers have no 5-hour or weekly
  windows, only credit, rate limits, keys and being down.
  - **Each session shows its provider's own state**, such as "OpenRouter · out of credits" (amber
    when it needs you) or "OpenRouter · rate limited". The status line says OUT OF CREDITS, RATE
    LIMITED, KEY REFUSED or NOT ANSWERING. The card offers Add credits, Try again and Continue on
    your subscription when out of credits; Fix the key when the key is refused; Try again when the
    provider gave no reset or isn't answering.
  - **OpenRouter's balance** comes from its key's own limits: what's left under the key's cap
    ("$4.20 left"), else what it spent today, else free requests left. It shows in Settings →
    Providers, on the Mac's Providers window and under the companion star. The account's own credit
    needs a second, management key, so it is never claimed (LATER L48). Credit coming back ends "out
    of credits" by itself only for a key cap or budget; otherwise the owner taps Try again.
  - **The companion star.** Sessions on other providers are ringed moons of a white dwarf at the
    sky's lower right, not of their project's planet. The sun's death leaves it be. (Look: DESIGN.md.)
  - **Tested against a fake provider** on the Mac (canned replies and 13 recorded faults, never the
    owner's login). Claude Code keeps only an error's message, so limits are read from those words.
    A bare 429 with no reset in its words waits for Try again.
- **Automations** (owner: "a new category like scheduled tasks - where user could make some
  automation workflow like in apple automation app"): a tab of "When … → Do …" cards. Triggers: a
  time (once, daily, weekdays, every N hours), a limit resetting, a session finishing or needing
  you, the Mac plugged in or low on battery. Actions: start a session in a project with a prompt,
  model and mode; continue a session; run a skill or slash command; notify or read aloud on the
  glasses. They run on the Mac, so they work with the phone off, and every run keeps its
  transcript. Whether an automated session may approve its own commands is the user's choice per
  automation (its permission mode), with a clear warning for the modes that skip asking.
- **Auto-continue is a switch on the card.** "Continues by itself at 2:00" with Don't; "Back at
  2:00" with "Continue automatically at 2:00". Built 2026-10-16.
  - Claude Code decides for its own terminal sessions: its notices drive the card. Don't presses
    Esc; turning it on uses /rate-limit-options, only while idle and never over the owner's text.
  - The Mac carries on SDK sessions at the subscription's reset (30 to 90 s after), and other
    providers' rate limits at their announced reset (5 to 20 s after; owner: yes).
  - At most 2 re-arms in a row, within 24 hours. More than 30 minutes late, it waits for Continue.
  - Never after running out of credit or a refused key (owner: no). When it's over, the card says
    "OpenRouter has credit again" with Continue.
  - A continuation shows as a slim row in the chat, never as the owner's message.
- **Files open properly**: Markdown and code formatted, pictures, video and audio viewable, and
  everything downloadable.
- **Phone ↔ Mac without Wi-Fi**: sharing the phone's mobile data with the Mac already works
  through Tailscale. With no internet at all, a direct local connection (LATER L11) would be
  needed; the owner chose later. A raw Bluetooth link between the apps is not planned (slow,
  short range, limited in the background).
- **Starting a session from the glasses** (owner: "in main menu user can hold temple to start a new
  session. And it will be created in devoted folder (can be changed in settings). But if there a
  project, which needs user's attention, it will show talk to [project]"). Built 2026-10-16.
  - On the glance a hold goes to the first session that needs you (an approval, question, plan or
    terminal menu; never just an unread reply), otherwise to a new session in Nebula. Inside a
    session a hold talks to it, so to talk to a working session you open it first. Naming a project
    in speech still wins.
  - The talk screen always names the target before the tap that sends: "TO 0stems · NEEDS YOU", or
    "NEW · Nebula/landing-page-bakery". After the send, the glasses return to the main screen (the
    owner is asked whether to open the new session's live view instead; see the questions below). A swipe switches between those two first, then the other
    sessions and projects.
  - New sessions get their own subfolder in **Nebula** (`~/Programming/Nebula`, the folder where new
    stars are born; changeable in Settings and the phone, only to a folder inside the home folder,
    because the phone decides where folders get made). The lens names the folder from the first
    prompt in short kebab-case (filler words dropped, at most 4 words, Russian transliterated). The
    Mac makes the name safe and unique, creates Nebula if needed and runs `git init`.
  - Nothing is created before the send tap. That review is the "+ New project" confirmation: the
    header shows the folder name and the hint says "tap make its folder & send". There is no
    separate yes/no card.
  - "+ New session" under the glance's lines opens a picker ("+ New project", then projects,
    newest first). "new session: …" and "new project, …" go to Nebula; "new session in 0stems: …"
    to that project. Said to a terminal session waiting on an approval, a prompt isn't lost: the
    card comes up with it as the deny reason, sent with a double tap.
- **Pop-ups on the glasses** (built): a framed two-line band over the bottom of any screen. A tap
  opens it, a swipe puts away the whole band, and several merge into one ("+2").
  - Needs-you stays until opened or swiped. Done fades after about 6 s, stopped and limit after
    10 s. The Mac's battery at 15% pops once per discharge. "MAC SLEEPS IN 2 MIN" says why (battery,
    heat, 8 hours) and goes when its countdown ends.
  - Nothing shows while you talk, are being read to, or the lens is quiet: they wait, and a done or
    stopped one that waited over a minute is dropped. A needs-you pop-up never appears on that
    session's own screens, or on the glance, which already moves its focus to it.
  - A switch per kind. On by default: needs-you (as the owner said), the Mac's battery, and the Mac
    about to sleep. Off by default: done, stopped and limit, since a finished turn already takes the
    glance.
- **"Telescope": see what's being built, from the glasses** (owner: "when a user says 'show what
  happens' or choose a simpler phrase that has cosmos aesthetic you show on glasses what is being
  built - like android emulator or web page or whatever so user can check result visually on the
  go"). The word is "Telescope", instead of "show me" (the owner: "Make it instead of show me"; "show
  me…" also starts ordinary prompts). It shows what the session in front of you is building, picked
  automatically, newest first: web page, Android emulator or phone, iOS Simulator, its terminal,
  the last picture, chart or PDF page, or a Mac app window. Built; how it picks and what it may
  touch are in "Hypernova Lens" above. **Windows only of a program whose real path is in the
  session folder**, and Screen Recording is asked once, on the first "telescope window". Full
  screen, dithered for the green display; tap zooms into a quarter, swipe goes round the quarters,
  double tap goes back; "watch" refreshes it for up to a minute.
- **Glasses for cycling and walking** (owner: "think of different phrases and other features which
  would made app usage for glasses better, more productive, so I can do whatever i want - cycling,
  walking, and still do develop things"; approved as proposed). Built 2026-10-16; see "Glasses on a
  bike or a walk" below. Later (LATER L32, L33): moving mode by itself when the phone detects
  cycling; the Even ring as a remote.
- **Everyday quick wins, built before the bigger pieces** (approved 2026-10-15): usage in the chat
  (model, a context ring, the session's tokens and cost, the 5-hour window; Compact at 85%);
  Claude's to-do list as a live pinned checklist; "What changed" (files with +/− and a diff, a
  Commit button); long-press on a message (copy, quote, read aloud, rewind to here); quick prompts
  as chips above the box; Share to Hypernova and launcher shortcuts. Also listed: a fingerprint for
  risky approvals, search across sessions, git status on project cards, muting a project.
  The chat part was built 2026-10-16:
  - **Usage in the chat**: a slim glass strip under the session's status line with a context ring
    and its percentage, the model, the cost, the tokens and the 5-hour window. A tap opens the
    breakdown (context in tokens, tokens by kind with agents, the cost, the plan's two windows).
    From 85% context a "Compact" chip sends /compact. **Numbers are honest:** where Claude Code's
    status line gives the window and the cost they're its own; otherwise they're worked out from
    the transcript at list prices and marked "≈". Another provider's sessions show no cost. When
    the line is too narrow, whole parts give way (tokens, then the reset time, then the window),
    never one cut in half.
  - **Claude's to-do list** is pinned over the prompt box only while a turn runs and something is
    left. It reads both TodoWrite and the newer task list. The header folds it to one line.
  - **Long-press on a message** (yours or Claude's): Copy, Quote, Read aloud (the Mac's voice; the
    phone's own when the Mac can't), Select text (selection moved here), and for your own messages
    Rewind to here. (Save as quick prompt left too, 2026-11-01: the chips below left the chat in this
    fork, so it saved to a list nothing showed.)
  - **Rewind branches, it never cuts in place.** The conversation up to just before the message is
    copied into a new session, "<title> (rewound)", and the message (with its files) comes back
    into the box. The original stays, and files Claude changed stay as they are; the confirm sheet
    says so. No rewind on the first message, one not yet on the Mac, or Claude Code's own commands
    and "!" lines. Not Claude Code's /rewind in the terminal: its picker lists messages by cut-off
    text, so the daemon couldn't pick the right one, and it can restore code, which is destructive.
    Rewinding the code too is LATER L46.
  - **Quick prompts**: chips over the box (Continue, Run the tests, Explain the last change, Commit
    this, then your own; 12 at most), kept on the phone. A tap sends at once; a long-press edits
    (emptying one deletes it). They show only while the box is empty and Claude isn't at work,
    waiting for your answer or unable to reply (a little stricter than asked: the owner asked only
    for them to hide while typing).
  - **What changed** is a read-only view of the session's folder (git status and diff, limited to
    files the session's edit tools wrote, with Show all). Its Commit button is a prompt to Claude,
    never a daemon commit, so the repo's own conventions apply; it's disabled while a turn runs.
    Credential files and anything outside the folder are never shown.
  - **Share to Hypernova** is an Android share target (text, links, images, files): pick a project
    or session, or New session, and the composer opens with the content attached. **Launcher
    shortcuts:** New session plus the 3 most recent projects.
- **Automation templates around limits** (owner: "we can add automation templates like the one with
  limits"): carry on after the limit, do it when I have budget again, switch provider at the limit
  (opt-in, costs money), night shift, weekly debrief. They lead the template list.
- **"Stay awake": the Mac in the backpack** (owner: "the idea is that I could use my hypernova even
  when I have my MacBook closed backpack"; he uses Amphetamine for it today). Built 2026-10-16.
  - **While working** (the default): awake while sessions are starting, working or waiting on an
    answer mid-turn, their agents run, an automation asks, or a phone or the glasses are connected.
    It keeps holding 3 minutes after the last of those, because macOS sleeps an idle Mac the moment
    a hold goes. It's the daemon that holds, not the Mac app, because the daemon knows when there's
    work and runs without the app; the hold ends with the daemon even if it is killed.
  - **Backpack**: awake with the lid closed, for 8 hours. Choosing it again restarts the 8 hours;
    when they're up it drops back to While working. **Off** lets the Mac sleep as usual.
  - **The lid-closed permission is a narrow rule, not a helper running as root** (this changes the
    first plan). One rule lets the owner's account switch only the Mac's "disable sleep" setting on
    or off, and nothing else. The Mac app installs it after one macOS password prompt. A rule is
    something macOS enforces to the letter; a helper would be a program with full power running all
    the time.
  - **Putting back**: Backpack never touches a setting that was already on (Amphetamine's). If it
    changed it, it puts it back when Backpack ends. When the daemon itself stops, it puts it back
    only with the lid open, since a closed lid most likely means a restart in a bag.
  - **Guards**: at 15% battery (5 to 50% adjustable) when not on power, in either mode; in Backpack
    also when macOS reports heavy heat, and after the 8 hours. Each counts down 2 minutes first, on
    the phone and the glasses ("Your Mac will sleep in 2 minutes · battery 15%"), and plugging in or
    cooling down calls it off. Desktops get no Backpack and no battery guard.
  - **Where it shows**: the Mac popover (While working / Backpack / Off, plus a line), Mac Settings
    (permission set up or Remove, battery level), the phone's Mac card ("Awake · lid closed · 62%",
    a picker, a line), a crescent on the phone's connection chip while the Mac is awake with the lid
    closed, and "Stay awake" / "Let it sleep" on the glasses. "Stay awake" asks for Backpack once
    it's set up (until then, While working, with a hint); "let it sleep" goes from Backpack to While
    working, and from there to Off.
  - **Removal**: Settings → Remove, Uninstall…, or the setup step's undo. Each ends Backpack first,
    so the setting is put back.
- **More platforms, planned before built** (owner: "I don't want to limit this to platforms for now
  because we will lose potential audience"). Proposed order: a web app served by the owner's own
  Mac (any browser, installable), iOS through Kotlin/Compose Multiplatform sharing the Android
  app's screens and data logic, then Linux and Windows hosts (a platform layer in the daemon and a
  small tray app). A research plan comes first (docs/PLATFORMS.md); nothing is migrated before the
  owner reads it.
- **"Transmission": text that arrives like speech, on the glasses** (owner: "a settings which
  would imitate text appearance like somebody talks … pauses and so on"). Built. A setting, "Text
  arrives: All at once / Like speech", **All at once by default**, so nothing changes until he turns
  it on. Like speech: words appear at a speaking pace (110 to 260 words a minute, default 170), a
  few at a time, with short pauses at commas and longer ones at full stops and paragraphs; in step
  with the voice when a reply is read aloud. It covers Claude's words in the live view, agents'
  reports and the whole of a finished reply, which scrolls as it arrives. Code and commands appear whole, approvals always show at
  once, and a tap shows the rest immediately. Updates are batched by phrase so the glasses get a few
  text updates a second at most.
- **A long text on the glasses scrolls, never pages** (owner, testing on real glasses: "I don't want to see this page split, I would rather have scrollable. So, like, native scrolling, instead of page separation"; with text arriving like speech, one phrase arrived and the rest sat on other pages). Built.
  - A finished reply is one text that scrolls from its start, over its facts: no REPLY pages, no "2/5". A plan read whole (after its three lines) and a diff scroll the same way.
  - A swipe moves one line less than the window, so the line you were on stays in view. A ↓ at the end of the last line says there's more.
  - Text that arrives like speech flows through the whole reply. The view follows the newest line; a swipe back stops it (the words go on underneath); swiping on to the end follows again; a tap shows the rest at once, under where you are. Read aloud, it follows the voice.
  - **The lens scrolls the text itself, not the firmware.** Tried in the Even Hub simulator: the firmware's own scrolling jumps back to the top whenever the box's text changes (every step of words arriving), the app can neither move it nor tell where it is, and a box holds about 1000 bytes. Worth trying again on real glasses (LATER L52).
- **Parity with the phone, on the glasses** (built). The glance corner shows the same limit states
  as the phone: a black hole at the 5-hour limit, a cold ember at the weekly limit, an eclipse when
  signed out, without a plan or on hold, and a short flash when a limit lifts. A session a limit
  paused stays on the glance with the phone's words, and its card says PAUSED. Queued prompts show.
  "Status" says the limits, paused sessions, and a Mac kept awake with its lid closed.

## Voice through Soniox, and the Lens guide (2026-10-15)

The owner: "We have to download hyperscribe companion to use it (voice). But can't we built what's in Hyperscribe companion into hypernova app on mac? Or use soniox api. I gues soniox api is better for now. Well, we can leave an option there…", then "Let's leave soniox for now. And it is good for real time."

- **Soniox, through the Mac, is the voice for now; Hyperscribe Companion stays as the other way.**
  The phone streams the glasses' microphone to the daemon, at the Tailscale address and with the
  device token it already uses, so nothing new goes in the Even App's whitelist. The daemon relays
  it to Soniox's real-time API with the owner's key. Lens uses Soniox while the Mac says voice is
  ready, else the Companion; a hold keeps the one it started with. The daemon speaks the
  Companion's protocol to the phone, so Lens needed no second speech path.
- **The key stays on the Mac** in a private file (readable by the owner only). It's set from the
  Mac's guide window or from Lens on the phone; the phone sends it once and never stores it. It's
  never sent back: clients see only "ready" and its last four characters.
  - Before keeping a key, the Mac tries it with a quarter of a second of silence, so a mistyped key
    is caught at once.
  - A key whose account has no balance is kept and says so; Test tries again. A key tried while
    Soniox is busy, or has a problem of its own, is kept, with Soniox's words. A key tried while
    Soniox can't be reached is kept untried; the next hold tells.
  - Removing the key asks first ("Remove the Soniox key? Voice stops until you add one."), as
    forgetting a device does.
- **Bounded and private.** One hold carries at most 2 minutes of audio; then the words so far are
  kept and listening stops. A stream with no audio for 20 s closes. One stream per device. Audio,
  words and the key are never logged or written down.
- **Hearing right, and only what's needed.** Soniox gets "Claude Code", "Hypernova" and the projects
  in use as hints, so "in hyperscribe, …" is heard right. "In use" means with sessions, or a folder
  changed in the last two weeks; newest first, 20 at most. Folders never used with Claude stay on
  the Mac, and a key's test sends no names. Language hints follow Lens' setting with English beside
  it. No endpoint detection: letting go of the temple ends the utterance. (Sending project names at
  all is a question below.)
- **Glasses pairing opens a guide on the Mac**, the "Hypernova Lens" window, in three steps: what
  Lens is for (the owner's words: "an on the go app for developers so wherever they are they can
  still develop products, see results"); the four gestures; and voice (connect Soniox, with
  Hyperscribe Companion beside it).
- **How the Companion is offered.** It has no app to download yet (a source release). Installed as
  an app: "Open Hyperscribe Companion". Running here from source: its name. Otherwise: "Hyperscribe
  Companion on GitHub ↗", its source and setup steps.
- **The phone shows the same guide at the same place.** The Mac keeps the step, so a step taken on
  either screen moves the other. "Show on my Mac" brings the window forward, and the phone is told
  when no Mac app is listening. Done closes it for good (more glasses pairing won't reopen it);
  Settings → "Lens guide…" on the Mac and "Open the Lens guide" on the phone bring it back.
- **Glasses aren't phones on the Mac.** A device paired by Lens shows as "Even glasses" with a
  glasses glyph, the list is titled "Phones and glasses", and the header pill says "1 phone ·
  glasses". The Mac app's own live connection isn't counted as a phone.
- **The Mac app's connection pill is honest.** It says connected only while it has heard the daemon
  in the last 30 s (the daemon pings every 25 s; the extra 5 s are room for a late ping from a busy
  daemon).

## Voice on the phone (2026-10-16)

The owner's second round of notes asked for voice in the Mac's setup and a mic in the phone's composer, reviewed before sending: "Review it, but it should be done fast". Built; the visual spec is in DESIGN.md, "Voice".

- **Setup asks.** The Mac's Setup has a Voice step (6 of 7, optional) between Tailscale and Your phone, so a phone that pairs already has its mic: connect Soniox (the key is checked with a silent probe), use Hyperscribe Companion (found by itself), or Not now. The Mac never listens; there is no "hold to try".
- **One voice panel on the Mac**, shared by Setup and the Lens guide. The Lens guide skips its Voice step when voice is ready as it opens (two steps, ending in Done); the "Voice ready" chip still opens it. The phone's Lens page does the same.
- **Existing installs** get one quiet popover notice, "Talk to your sessions: set up voice", until voice is ready or they choose Not now. Walking past the step by clicking Your phone doesn't count as answering.
- **The phone's mic sits where Send is** while the box is empty (no text, no files) and the Mac offers voice: Soniox through the daemon while it is ready, else the Companion the Mac knows of. An older daemon: no mic.
- **Hold to talk, tap for hands-free, on the same button.** The mic opens on touch-down. Let go after 350 ms and it's done; sooner and it keeps listening until Done. Slide away to cancel.
- **Hands-free's button is Submit, and it sends** (owner, 2026-11-01: "done should submit what I have to say ... the keyboard would never open"; "make the done button say submit"): the words go in, settle, and are sent as Send sends them, with the keyboard kept shut. Back, a tap on the mic and leaving the app still only end listening and leave the words in the box; held-to-talk still ends at the box. The New session sheet's dictation doesn't send, so its button still says Done.
- **The words are in the box at once.** On release the box gets everything heard (the part that may still change shown dim); Soniox's settled words replace it silently. Send works at once and waits for words still settling (1.5 s at most). An edit made meanwhile wins. Voice never opens or closes the keyboard and never sends anything.
- **Hold Send (400 ms) to add more** when the box has text; a tap still sends. The new-session sheet has a mic at the end of the PROMPT line; it always appends.
- **Mic source:** the phone's own microphone. A Bluetooth headset's is opt-in (Settings → Voice, Android 12+), because its route takes a moment and lowers music quality. A wired headset's is used by Android itself.
- **Battery:** the microphone, the stream and any headset route exist only between the press and the end. Leaving the app ends listening (words kept). No foreground service, nothing warmed up. Hands-free ends at Done, Cancel, Back or the 2-minute cap.
- **Settings → Voice:** the state, the key's last four, Language (Auto hints English and Russian, then the phone's own), Headset microphone, and paste or remove a Soniox key (it goes to the Mac, which tries it first; the phone never keeps it).
- **No sounds.** Haptics mark start, armed to cancel, done and cancel. TalkBack hears nothing while the mic is open (it would be transcribed), then one line: "Ready to send: …".
- **Raw audio for now**: 16 kHz 16-bit, about 32 KB/s on mobile data (Opus: LATER L53).
- **A stop while Soniox is still being reached keeps the audio**, so a quick "yes" over a slow link isn't lost. The log keeps only the time from the phone's stop to the last words, never words.

### On the glasses, letting go of the temple

Owner, on real G2, 2026-10-16: "release the temple… it sends rapidly and I don't even see what is being said, and I think that not whole text was get". This differs from the phone on purpose: the glasses have no Send button, and "tap to send" on a card cut at five lines made prompts feel sent unseen.

- **The microphone stays on 0.4 s after the release**, then Lens waits for Soniox's last words, 2.5 s at most. The last word is often still being said as the finger lifts, and the glasses' audio reaches the phone late; switching off at once lost it.
- **After the release the captions stay and settle** ("getting the last words…"). Then the prompt shows whole, never cut with "…": a long one opens at its end with ↑/↓ marks and scrolls by swipe.
- **It sends by itself after a short count** ("sending in 2"): 2 s for up to two lines, then 1 s more per line, 8 s at most, so a long prompt can be read. Tap sends at once; double tap cancels and keeps nothing; any swipe pauses the count ("tap to send") until a tap sends or a double tap cancels; hold adds more and the count restarts on the next release. "Send it" at the end still sends without the count.
- **If the last words don't come back in time**, what was heard stays, the top line says "last words missing?", there is no count, and it waits for a tap. A voice word heard that way isn't acted on ("stop" might have been "stop the server…").
- **Parked:** a setting to turn the countdown off (owner: "a setting can turn the countdown off later — not now"; LATER L51).

## The Mac's battery, signing in again, and one rule for secrets (2026-10-15)

- **The Mac's battery on the phone** (owner: "add battery status into app (battery of mac, but don't
  forget that mac mini and desktop macs don't have battery)").
  - The daemon reads the battery at start and once a minute. The first reading after the Mac wakes
    comes within 10 s. A phone is told only when the percentage, charging or the power source
    changes, so it isn't woken for a minute-by-minute estimate.
  - **No battery, nothing shown.** A Mac mini, iMac, Mac Studio or Mac Pro shows nothing: no empty
    battery, no "unknown". A UPS on USB, which macOS lists as a battery, isn't one.
  - **Minutes left** means until empty while discharging and until full while charging, and there
    is none when the battery is full, while optimized charging holds it, or while it's still
    estimating. It comes from the battery's own estimate, not macOS's: on macOS 26 the system showed
    "20:00 remaining" for a whole evening while the charge fell about 6% an hour. The battery's
    figure swings with the load, so the daemon averages the pace over the last five minutes.
  - The Mac popover's tooltip gives the time left. Plugged in but not charging, it says so and ends
    "Use a stronger charger to keep them going."; on battery, "Plug it in to keep them going."
- **Signing in again from the phone: a refused code.** Claude Code's sign-in is a plain line prompt
  with no error page: a code the server refuses prints "Login failed: …" and ends the command.
  - **A refused code starts a new sign-in by itself.** The phone is told the code didn't work and
    gets a new page to open (the old page's codes are spent). The 10 minutes start over, up to
    three times; after the fourth refused code it stops and says so. A wrong code the owner pastes
    himself on the Mac starts over the same way.
  - **A right code the account can't use ends it, in Claude Code's words.** An account on hold, or
    one an organisation's policy turns away, would end the same way again, so there is no restart.
  - **Codes that can't work are turned away first**: half a code, or one from another sign-in's
    page. A second code sent while the first is checked is turned away with "wait a moment".
  - **Only Claude Code's "Login successful." counts as signing in.** The owner's sign-out came from
    the server ("Please run /login · API Error: 403") while `claude auth status` still said signed
    in, because it reads the token on disk and a failed sign-in leaves the old one there. So a
    refused code, an account on hold, a Ctrl-C or a time-out never ends a sign-out, and the phone
    never says "signed in" when it isn't.
- **Moving a session to another provider** restarts its claude in the same terminal. For 15 s after
  the new one starts, the old one's end doesn't count as the session ending. Agents it ran in the
  background end with it. One move at a time (another gets "busy"). If the new claude can't start,
  the session has ended and resumes like any other.
- **One rule for secrets.** Session titles on the Mac and the phone, agents' reports and the
  home-screen widget mask secrets by the same rule, and both sides run the same test cases.
  - After a key's name, a value that looks like a credential is masked: 8 or more characters with a
    digit, or 16 or more mixed-case ones. "auth" alone is never a name; AUTH_TOKEN and
    AUTHORIZATION are.
  - After a scheme word (Bearer, Basic, Token and so on) that follows a name, any 8 or more token
    characters are masked; the scheme word stays visible.
  - Curly quotes are never part of a value. An opening one right after the "=" or ":" stays with the
    name and the value inside is judged (PASSWORD=“s3cretpass” becomes PASSWORD=“•••”); prose in
    curly quotes stays prose (password: “forgotten”).
  - After the plural "tokens", a number with commas, dots or underscores between its digits is a
    count, not a credential (tokens: 12,345,678; max_tokens=4096), so usage in a title stays
    readable.
  - curl's and wget's credentials (`-u user:pass`, `--user`, `--proxy-user`, `--oauth2-bearer`) are
    masked only inside a curl or wget command, never past a `;`, `&`, `|` or line break, so
    `docker run -u 1000:1000` stays as written. A quoted string up to 600 characters counts whole,
    so a long JSON body never hides a later `-u`.
  - Ordinary prose stays as written, and masking a long line stays fast.

## The sky, third pass (2026-10-16)

- **Real stars and deep-sky objects** (owner: "stars on the bg look like dots ... some objects like
  in real life which are usually captured by Hubble"). The background stars are drawn in the sun's
  family, with a real sky's colour and brightness proportions and no twinkle (there is no air in
  space; the sun's twinkle went too). A few faint Hubble-like objects (a spiral, an edge-on galaxy,
  an elliptical, star-forming pillars, a ring nebula, a globular cluster, a deep-field corner) sit
  in the farthest layer, placed from a seed that's different for each install and the same at every
  launch. They never compete with the sessions.
- **The 5-hour limit is light, not grey** (owner: "everything turns dark ... I'd like to have
  accretion disk fill like it feels space with it's light ... watching black hole speeding up
  accretion disk"). Only the universe dies: the weekly heat death stays the one truly dark state.
  Through the 5-hour cooldown the app keeps its colours. The black hole's disk lights space, the gas
  and the planets, and it drains faster and brighter toward the reset, with two jets in the last
  minutes.
- **The planets survive.** The supernova's shock flares them and darkens their day side; it doesn't
  destroy them, because a black hole of the star's mass holds the same orbits. At the reset they
  simply light up again with the new star. Planets condense out of dust only after a Big Bang.
- **Names come back around the hole** (a call made while building, since a sky without names felt
  dead): planet names at 70%, orbit wakes and needs-you brackets return once the disk has formed,
  and the camera can fly to a planet. They fade during the evaporation and come back last.
- **Still old elsewhere**: the widget, Lens and the Mac app still show the old cooldown (LATER).

## Bypass mode and the think-twice card (2026-10-16)

- The phone offers Claude Code's fifth permission mode, **Bypass** ("bypass permissions": nothing
  is asked, not even risky commands), next to Ask, Edits, Plan and Auto, in the new-session sheet
  and in a session's mode picker (the daemon already accepts `bypassPermissions`). A session the
  phone started can switch to Bypass later, from its next turn if one is running (switching a
  running turn is untested; LATER L35). A terminal
  session on the Mac that wasn't started with bypass allowed shows Bypass dimmed, with the reason
  (also when Bypass is allowed only through the owner's Claude settings, until it is in bypass; the
  phone's own terminal sessions are started with it allowed). The glasses never set Auto or Bypass
  ("Auto and Bypass only from the phone"). The Mac app doesn't have the think-twice step yet
  (LATER L36).
- Choosing **Auto** or **Bypass** first opens a think-twice card: one real incident, picked at
  random and never the one shown last, where an AI agent with too much freedom did damage (for example, deleting a production
  database or a user's folders). Headline, two plain sentences on what happened, the month and year,
  "Read the story" (the source), and at the bottom: "AI makes mistakes. Never rely on it as on
  something that can't." Buttons: "Use Auto/Bypass anyway" and "Keep asking" (the default). It
  shows every time, with no "don't show again": the point is to make you think twice. Back, a tap
  outside and "Keep asking" all keep the mode. Bypass's card
  is stronger in tone (red-shifted, like a star's warning light) than Auto's.
- The incidents must be real and sourced: each one checked against at least two reputable reports,
  told without exaggeration, with the source link kept in the app. About 8 to 12 cases, Claude Code
  included, not only other companies' tools; the list ships with the app and is updated by hand. Three entries are marked weakly sourced in the
  list's notes (never shown): the Gemini CLI one (its Ars Technica link wasn't opened), the Claude
  Code home-directory one (mostly a Reddit post), and the month of the Terraform one.


## Glasses on a bike or a walk (2026-10-16)

The build of the owner's "Glasses for cycling and walking" note (second round). Details for the
code: `lens/README.md`, "On the glasses"; `lens/DAEMON.md`, section 11.

- **A guide on the glasses themselves, offered once.** The first time the glasses have the Mac's
  sessions and nothing needs the owner, they offer "LENS GUIDE: tap start · double tap not now". A
  lens set up before the guide existed gets the offer once too. Each step asks for one gesture (tap,
  double tap, swipe, hold) and checks it as it is done: the right one is ticked, a wrong one is
  named ("That was a swipe. Just one tap."). The last step is voice practice (hold, say a few words,
  let go; any words tick it, and nothing is sent to Claude), which double tap skips because voice
  isn't required. Pop-ups wait while the steps run. "guide" replays it, and so
  does "Practise the gestures on the glasses" in the phone's Settings.
- **The phrases are whole utterances**, so a sentence that only starts with one stays a prompt
  ("continue with the parser…", "go ahead and rename…"). "show me the diff" stays a prompt; "show
  diff" is the command.
  - **"debrief"** covers what happened since the last debrief (the first one, the last hour): what
    finished, then what needs the owner, then what's running. A turn that ended while the phone
    slept still counts. It is spoken one sentence per item and shown as a list, so a tap answers
    what needs him.
  - **"Note: …"** saves an idea to a notes file in Nebula, under a heading per day, with the session
    in front as the project. Claude isn't started. "note that …" is a prompt.
  - **"next", "previous", "back"**: back leaves a card unanswered.
  - **"one", "two", "three"** answer the question or terminal menu on the lens. For multi-select,
    each number ticks one option and "approve" sends.
  - **"plan first" and "go ahead"** set the mode of the session in front to plan or default. On a
    plan card, go ahead approves it and plan first keeps planning.
  - **Never Auto or Bypass by voice.**
  - **"continue"** carries on a stopped session.
- **Eclipse** ("eclipse" / "eclipse off", also a switch on the phone): only needs-you gets through.
  No other pop-up, no chime for a finished turn, and a finished turn doesn't take the lens.
- **Moving mode** ("moving" / "still", also a switch on the phone): two short lines at the top-left
  and nothing else, with no planets and no animation. A pop-up chimes and takes the first line. A
  finished turn is spoken in one sentence, never over the owner's own voice. Questions and terminal
  menus are read out with numbered options. Telescope's picture stays whole, because he asked to
  look.
- **Risky commands are the lens' own check**, since the daemon has none: force pushes and deleted
  remote branches, `rm -rf`, hard resets and discarded changes; deploys and publishes; dropping
  data. It reads through `sudo`, `npx` and `env`, and MCP tools by their names.
  - The card says "careful: force push can't be undone" under the command.
  - Such a command is approved by voice only with its own verb ("approve push / delete / deploy /
    publish / drop / reset"). Another kind's verb, plain "approve" and "always allow" are refused.
  - A tap still allows it when standing still. Moving, a tap never does: the command is read out,
    and the verb counts only once that reading played to the end. With read-aloud off it can't be
    heard, so the owner says "unmute", or "still" and taps.
- **"What changed?" and "show diff"** use the diffs Claude Code made for each edit, already in the
  turn the lens loads. The answer is spoken in a sentence or two (the files and line counts, then
  Claude's first sentence), and the files are listed with + and - counts.
- **Checked in the simulator, unit tests and a test daemon** (notes, mode, continue). Nothing yet on
  G2 hardware or outdoors (`lens/README.md`, "Not verified yet").

### The glasses after the owner's morning test (2026-10-16)

- **"Like speech" wherever a reply first shows.** A turn started from the glasses arrives like
  speech in the live view or on the result card, for 30 minutes after the send, and the card
  carries on from where the live view was, so a reply never starts over. An old backlog still
  shows at once. (Owner: it worked in an existing session but not in a new one.)
- **"Telescope", even misheard.** Soniox heard "Telescope terminal" as "To the scope terminal".
  The Mac now gives Soniox the glasses' own words (Telescope, debrief, eclipse, note and the
  source names) as hints, and the glasses accept near-misses ("to the scope", "tele scope",
  "telescopes", "the telescope", "tell a scope") only alone or before a source name; anything
  else stays a prompt to Claude.
- **No blank cards in Russian.** A text box on the G2 holds about 999 bytes and Cyrillic takes
  two bytes a letter, so every box stays under 990 bytes: a scrolling window shows fewer rows when
  its lines are dense (swipes still skip nothing), the live feed keeps its newest lines.

### Speech out, and the thinking label (2026-10-16)

- **Read aloud with Soniox** instead of the Mac's voices (owner: "I would add today [Soniox TTS]
  instead of TTS that is configured in settings"). Same key as listening; natural voices, Russian
  included; it streams, so speech starts while Claude is still writing. The Mac's voices stay as
  the fallback (no key, offline) and as a choice in Settings → Voice.
- **The phone plays the glasses' speech** (owner: "it's not possible to send speech from Even
  app ... send speech via Android application ... connect its audio device, like headphones").
  The Even app's web view only makes sound after a touch on the screen, so with the phone in a
  pocket it stayed silent. The Hypernova app plays it natively, through headphones if connected,
  in the background, and reports progress back, so text on the glasses stays in step and a risky
  command counts as heard only when it was played to the end.
- **You can see when it speaks** (owner: "some label or logo animations ... like audio wave ...
  both on Android application, on glasses, so everywhere"): a small wave (a pulsar's beat, in the
  cosmos look) that moves with the voice's real loudness on the message being read, in the status
  line and a "Speaking · Stop" bar on the phone, in the glasses' header, and on the Mac popover's
  session row; flat and gone when it stops.
- **Thinking shows it's alive** (owner: "make this thinking label colored or fading ... when it's
  finished it should be just constant color"): while Claude thinks, the label shimmers between the
  accent colours; when it's done it settles to one colour ("Thought for 12s"). Reduce Motion: a
  steady colour.

### The owner's answers (2026-10-16)

1. Soniox keeps getting recent project names (up to 20) as hints. **Keep.**
2. The Hyperscribe Companion link is hidden until it has a public page; setup offers Soniox only.
3. Eclipse lets the Mac's low-battery and 2-minute sleep warnings through, besides needs-you.
4. Moving mode still shows a Telescope picture whole: you asked for it. **Keep.**
5. A risky card still needs the risky verb ("approve push") by voice, moving or not. **Keep.**
6. Planet names come back around the black hole once the disk forms. **Keep.**
7. Backpack gets an "extend": before its 8 hours end, a pop-up on the phone, the Mac and the
   glasses offers "+2 h".
8. One battery-warning rule for the Mac, the phone and the glasses: **20%** when not charging.
   (Stay awake's guard, which lets the Mac sleep, stays at 15%.)
9. After a new session from the glasses, they open its live view (double tap goes back).
10. Sessions cut by a restart stay on Home whether started on the phone or in a Mac terminal.
11. Quick prompt chips send on a tap. **Keep.**
12. The glasses' send count (2 s, +1 s a line, 8 s at most, a swipe pauses it). **Keep.**
13. "Rewind the code too": yes, later, as a separate and clearly warned option (LATER).
14. A limit that continues by itself offers both "Don't" and "Continue now".

### Tips on the glasses (2026-10-16)

The owner: "add tips for glasses application where we could see some advice how can we use the app
in a more productive way ... the phrases like 'Telescope' — so this phrases we use to activate some
functions". A tip teaches **one spoken phrase and what it does**; the guide teaches the gestures.

- **The phrase first:** `"telescope terminal" · the Mac's terminal on your lens`. One line, the
  phrase bright, what it does dim.
- **Every voice command has a tip**, and a test keeps it so (a new command without a tip fails;
  a tip whose phrase the glasses don't understand fails): telescope (terminal, emulator, phone,
  simulator, web, localhost 3000, window, image), watch, show diff, what changed, status, read
  it, debrief, note, continue, plan first, go ahead, approve + its verb, option two, send, never
  mind, stop, moving, still, eclipse, backpack, extend, let it sleep, battery, mute, guide.
- **At the moment it's useful**, one dim line under the card, never a pop-up: a dev server
  running → "telescope localhost 3000"; tests running → "telescope terminal" then "watch";
  files changed → "what changed" / "show diff"; a long reply → "read it"; the Mac on battery
  with its lid closed → "backpack"; back after a long gap → "debrief"; Claude stopped → "continue".
  Each retires after three showings, or for good once the owner has said it.
- **One phrase of the day** on the quiet main screen (nothing working, nothing needs you); never
  during needs-you, a risky card, speech or Eclipse.
- **"What can I say?"** (also "tips", "commands", "help"): every phrase, grouped (Talk, Approve,
  Watch, On the move, Your Mac), scrolling, never pages; the same list on the phone companion
  page, where tips can be turned off.

## Automations, speech and the answers, built (2026-10-16)

**Automations** (docs/specs/spec-automations.md; the owner: "we can build it today")
- Four tabs: Sessions, History, Automations, Settings; Statistics opens from the sun. The editor
  writes a plain-English sentence; templates lead with the five around limits (When I hit the
  limit, Do it when I have budget again, Night shift, Carry on after the limit, Switch provider
  at the limit), then Morning brief, Weekly debrief and the rest.
- **Never on paid usage, never the owner's work.** A run waits for a limit's reset or is
  skipped, also when usage credits would carry Claude on, and one that crosses onto credits
  stops. Anything that would run on a provider other than the built-in one (starting a session
  there, or continuing one that is on it) needs the owner's "This costs money" on every save.
  An automation closes only terminals it started; it never forks a session open in a plain
  terminal, and its own continues don't trigger it again.
- "Allow next time" reads refusals from the transcript: Claude Code 2.1.301 doesn't fire its
  PermissionDenied hook for dontAsk refusals (recorded in a test fixture).
- Run now never uses up "only the next time". A missed "every N hours" time is caught up only
  within N hours. A run that works or needs you keeps the Mac awake through Stay awake.
- The Mac: one quiet line under SESSIONS (next run, the run working, or "Automations paused"
  with Resume), an AUTO tag, and a "Run automations" switch; no editor. The glasses: a note is a
  card "AUTOMATION · <name>" on the glance or a pop-up otherwise, read aloud if reading is on,
  quiet in Eclipse; a note older than 5 minutes when the glasses first see it isn't shown.

**Speech** (Soniox `tts-rt-v2` over its real-time WebSocket, the listening key)
- Speech starts with a short first piece and streams. The Mac's voice reads when Soniox can't
  (no key, refused, offline, silent for a few seconds, a failure mid-reply, from the word it got
  to). Square brackets are read as text, never as stage directions.
- One setting on the Mac, set from the phone: engine, voice, speed (0.8×–1.3×), Test, and "Glasses
  speak through this phone" (per phone, on by default). The phone plays through its current
  output with music ducked, screen off, a media notification with Stop, and stops for a call or
  unplugged headphones. A risky command counts as heard only when the phone played its last
  sample. No app, or one that doesn't start within 12 s: the glasses play it themselves.
- The speaking wave: see DESIGN "Speaking wave".

**The owner's answers**
- (2) The Companion offer is hidden behind one flag per app. (3) Eclipse lets the Mac's low
  battery, its 2-minute sleep warning and Backpack's "ends soon" through. (7) Backpack offers
  "+2 h" in its last 10 minutes (`{t:"mac_backpack_ending"}`, POST /v1/mac/awake {extend:true}),
  repeatable; the battery and heat guards still apply. (8) One low-battery rule: 20% when not
  charging, re-armed above 25%. (9) A new session from the glasses opens its live view. (14)
  "Don't" and "Continue now" side by side. (15) The thinking label pulses between accent dim and
  bright (about 1.8 s) and settles to one colour when done; steady under Reduce Motion and
  battery saver.

**Glass everywhere, and the voice's light curve** (the owner, 2026-10-16)
- "Please check all the buttons, chips in the app so we have glass style everywhere": every
  button and chip is frosted glass with no outline ring and no solid slab. Primary is mint-tinted
  glass with a mint label, Danger red-tinted, Outline neutral, Ghost fainter; the floating New
  session / New automation is dense glass with a mint "+" lit from inside (the solid mint pill
  "doesn't match the application's style"). Cards, sheets, fields, tables and widgets keep their
  own surfaces.
- "When we record audio through STT ... see kind of space audioform changing when someone speaks
  louder or silent": the dictation capsule's seam is the voice's light curve, 2.4 s of it, the
  newest by the timer: faint star-dots in silence, mirrored strokes of light as the voice rises,
  a glint on the loudest. Under Reduce Motion it stands still and steps four times a second.

## Direction: open source, self-hosted, Tailscale inside (2026-10-16)

Not built yet; the plan after the weekly reset (Oct 21).
- **Open source, entirely** (the owner: "making it entirely open source is fine"). Before the
  launch: a licence for the rest of the repo, an install on a clean machine, one security review
  of the whole remote surface plus `SECURITY.md`, and Anthropic's current terms checked.
- **Claude Code stays the engine.** Hypernova drives the official Claude Code, so a Claude
  subscription stays legitimate. Since April 2026 Anthropic has kept third-party tools like
  OpenClaw off subscription limits. Hypernova never touches Claude Code's login tokens, and the
  Agent SDK fallback is limited to API keys.
- **Self-hosted, like OpenClaw or Hermes:** the daemon runs on your Mac or on any always-on
  Linux machine or VPS. Linux is command-line only: `hypernova setup`, `pair` (prints the QR
  code), `status`, `doctor`, `logs`. The main route is an install script plus a service, with
  Docker as the quick way to try it. Safe by default: it listens only on the machine itself and
  is reachable only through the tailnet, never on a public port.
- **Tailscale inside the apps** (the owner: "we'll have tailscale built into native apps"): the
  daemon and every native app carry Tailscale (`tsnet`). Setup on the server shows the sign-in
  links as QR codes. The app scans the pairing code, then signs in with the same account. There
  is no Tailscale app to install and no server of ours to run. Self-hosters can use Headscale.
  The glasses reach the server through the Hypernova app on the same phone. **Tested on the
  owner's Android phone (2026-10-17, Lens 0.1.5):** the Even app serves Lens from
  `http://127.0.0.1:<port>`, and its web view reached the Hypernova app's loopback server
  (127.0.0.1 and localhost, both 200 in 16–25 ms) with the addresses in the Lens whitelist. So on
  Android a loopback bridge in the Hypernova app is enough (no VPN mode). On iOS the app is
  suspended in the background, so VPN mode (a tailnet-only system VPN) is the plan there.
  The whole chain worked too: Lens → the app → the Mac's `/v1/health`, 200 in 434 ms.
- **Lens talks only to the Hypernova app** (decided from that test). Lens and the app exchange
  everything on the phone over loopback, with no network; the app holds the one connection to
  the server. So one public Lens build works for everyone: its whitelist only needs
  `127.0.0.1:47380`, never a user's tailnet name. The glasses need no pairing of their own, and
  they keep the app's state through network drops. Any app on the phone can reach loopback, so
  the bridge needs a one-time "Allow Hypernova Lens to connect?" in the app before it does more
  than ping.
  - **After its security review (2026-10-17)** the bridge takes pages only from the Even App's Lens
    origin (`http://127.0.0.1:<port>`): never `null` (sandboxed frames, `data:` and `file:` pages)
    and, in the APK the Mac hands out, never a private network address (captive portals, routers);
    a Lens developer's own build may opt in. It carries nothing without a token but a Telescope
    picture and the previews a granted Lens opened, only to be looked at, and only to the paired
    computer. A Lens's grant belongs to the pairing it was made under (Forget or pairing again ends
    it), and Allow on a locked phone needs unlocking. The voice and speed of the glasses' speech
    are the Mac's (Settings → Voice → Read aloud, for phone and glasses at once), so Lens's own stay
    at their defaults on purpose. PROTOCOL.md, "The phone bridge", has the details.
  - **After its second review (2026-10-17)**: any app can take the port while Hypernova doesn't
    hold it (after a reboot, say), so nothing Lens sends there is worth having. Lens and the app
    agree the token at Allow (an X25519 exchange with a six-digit code both work out, compared by
    the owner) and it never travels: each call is signed with a key made from it, body and all, for
    one listener, once, within a minute, and Lens signs only for a listener that proved it holds the
    key, and takes only answers proven with it (status and body). After Allow on the phone, one tap
    on the glasses ("Allowed on your phone? Tap to connect") keeps the new token: another app on the
    port could say "allowed" by itself. The app keeps retrying a busy port and says so meanwhile.
    Plain HTTP on loopback still can't keep a relay from reading (PROTOCOL.md says when one can).
- **Start coding in a few clicks** (the owner, 2026-10-17: "a user clicks several download
  buttons and starts coding in few moments"). Every end carries Tailscale; the user signs in with
  one account on the computer (Mac, Linux, later Windows) and on the phone, and sets up nothing
  else.
  - **Local first, automatically:** Tailscale already takes the direct path when both devices
    share a network. That includes the phone's hotspot with the Mac on it, where the phone is
    the router, and it falls back to the internet route when you walk away. For no signal at
    all, Hypernova adds its own direct local connection, encrypted and pinned to the computer's
    identity from the pairing code.
  - **The computer's setup:**
    1. Only the essential permissions (background service, local network, notifications); the
       rest when a feature first needs it, such as Screen Recording for Telescope. A
       Permissions page shows their status.
    2. "Sign in to connect your devices" (Tailscale).
    3. "Sign in to Claude": Hypernova installs Claude Code with Anthropic's official installer
       and opens its own login.
    4. Optional: GitHub, for cloning projects.
    5. Then the code.
  - **The code** is a QR code drawn as a star field (the dots are stars, the corners ringed
    planets; it still scans), and the moment of pairing is animated on both screens, like
    pairing an Apple Watch. A custom "constellation code" (like App Clip codes) is for later.
  - **Also:** pairing without a camera on the same Wi-Fi (found automatically, the same small
    constellation on both screens to confirm), several computers in one app, removing a lost
    phone, auto-updates for the computer app, and a Doctor page with fix buttons.
- **Clients from one Kotlin codebase** (Compose Multiplatform): Android, iOS, Windows and Mac
  desktop, and a web app (PWA) the daemon serves. iOS needs a small push relay (or ntfy) for
  "needs you" with the app closed. Android keeps its connection, and the web app uses Web Push
  straight from the daemon.
- **Order:** a 1–2 day prototype (Tailscale in the Linux daemon, in the Android app, Lens
  through the phone), the Linux daemon and its commands, Android with Tailscale inside, then
  the shared Kotlin code for iOS, desktop and the web.
- **First: Hypernova on a VPS** (the owner, 2026-10-17: "we should first suggest users an
  opportunity to install the daemon on their VPS ... so we have more audience"). The Linux daemon
  comes before Hypernavigate's city packs. Why Hypernova exists, in his words: "I didn't really
  like the experience of using Claude Code ... I wanted something my own." 
- **The owner's comments on the plan (2026-10-17):**
  - Hypernova installs Claude Code itself; the user only signs in.
  - The glasses app stays simple: its phone page is one status card, and the glasses' settings
    live in the Hypernova app.
  - Speech runs on the device only, chosen in setup with guidance (the owner: "Device only ...
    multiple choice or skip at all ... similar with TTS"):
    - **Voice input:** Nemotron 3.5 ASR on the phone, Soniox, or the phone's built-in
      recognizer. Skip means no mic in the app or on the glasses.
    - **Read aloud:** a voice model on the phone, Soniox TTS, or the phone's built-in voice.
      Skip means no read-aloud.
    - The server never handles audio or speech keys; Soniox calls move to the phone.
  - Hypernavigate is postponed until he decides.
- **How the phone reaches the computer (2026-10-17):** the owner pointed out that VPNs drop
  connections (Claude Code dislikes that), that Android allows one VPN at a time (iOS too), and that
  a VPN's traffic can be filtered on some networks.
  - Claude Code runs on the computer that hosts Hypernova (a Mac or a Linux server); the phone
    only reaches that computer.
  - Three connection types: the local network (or the phone's hotspot), direct HTTPS for a
    server with a public address, and built-in Tailscale for a Mac at home reached from
    outside. Hypernova never takes the phone's VPN slot.
  - Direct HTTPS: a self-signed certificate pinned by the pairing code needs no domain. Real
    names come from a domain the owner buys: random `<name>.hypernova.<tld>` per server via a
    free Cloudflare Worker, Let's Encrypt by DNS check, the Public Suffix List applied for
    early. On a public port, nothing answers without a device's pairing key.
  - **The domain is `hypernova.observer`** (bought by the owner at Cloudflare Registrar,
    2026-10-17), only for users' servers: `<random>.hypernova.observer`, the whole domain on
    the Public Suffix List, a static page at its root.
  - A day-long test from a phone on mobile data to a server decides the default.
- **What the network tests showed (2026-10-17, a phone on mobile data, a server on a public address):**
  - Plain HTTPS to the server opens, then freezes after a few KB on mobile data.
  - Through Cloudflare it doesn't load at all.
  - Home Wi-Fi passes everything.
  - **Tailscale held on mobile data with no VPN** (no drops, about 100 ms); a VPN
    worked but reconnected three times in two minutes.
  - So **Tailscale (built in) is the main route from outside**; direct HTTPS is for the local
    network and hotspot (shipped: the Local route) and for places without such filtering; the
    app keeps whichever route works.
  - Hypernova adds no protocol of its own to disguise traffic. It rides on whatever route
    exists and handles gaps instead: reconnects by itself, queues prompts and taps made offline,
    and catches the owner up afterwards.
  - The Mac is no longer required. A server is a VPS or one's own computer (iOS builds,
    emulators, USB hardware, more power), and one app can switch between several.
- **The owner's choices (2026-10-17, night):**
  - **Licence: Apache-2.0** for everything outside `lens/` (LICENSE, NOTICE); Lens stayed AGPL
    while it carried the Companion client (until 0.1.11; Apache-2.0 since the morning answers).
  - **Hyperscribe's first public release is 1.0.0.**
  - **Hyperscribe Companion gets real installers:** a .dmg for the Mac, an .exe for Windows, an
    AppImage for Linux.
  - **His website goes on Cloudflare Pages.**

## Links that open the app, and firmware text on the glasses (2026-10-18)

- **Hypernova links open in Hypernova** (the owner: "hypernova links don't open in hypernova"):
  phone cameras and Google Lens don't open `hypernova://` from a QR code, and Lens runs in the Even
  App's web view, which hands no link of another app's to Android (and the Even Hub SDK has no way
  to open one). So:
  - The pairing QR code is `https://hypernova.observer/pair#<the app link's parameters>`, an
    Android App Link (the domain's `assetlinks.json` from the name service's Worker, the signing
    keys' fingerprints a Worker var). The details are in the fragment, which never reaches the
    site; without the app (or before Android has verified the domain) the site's page hands over
    with an `intent://` link and says how to get the app from the computer. `hypernova://` keeps
    working.
  - Lens's **Open Hypernova** asks the app through the bridge (`POST /bridge/open`), which opens
    Settings → Glasses when it's on screen and otherwise puts up "Tap to open Hypernova" (Android
    starts an app from the background only on a tap). Without access Lens says to open Hypernova
    on the phone.
- **Firmware text on the glasses is the default** (the owner, on real G2 glasses: "let's use
  firmware text on glasses, because responsiveness really sucks with images"). The glasses' own
  font is the default look in Lens, in the app's Settings → Glasses and in the bridge's settings;
  "Type on the lens" keeps the drawn Hypernova look as an option. Settings kept before this take
  the new default once (until now every save wrote the drawn look, chosen or not). Lens 0.1.10.

## One design across phone, Mac and glasses (2026-10-18)

- The owner: "we should also update Hypernova Lens when we are finished with the Android
  application. And we should also do the same with application for Mac because design differs."
  The Android app leads (the calm sky, projection meters, drawn metadata instead of "·"-joined
  text, the new Start-a-session sheet). Once its current work lands, the Mac menu-bar app and
  Lens get a pass to match it: the same words, order, states and meters, each within its medium
  (Lens in the glasses' own text, so drawn chips become clear spacing and short phrases there).

## The sky, physically true (2026-10-18, night)

The owner, before sleeping: "Think about how can we make this solar system better in terms of
physics … I don't think that's star-borns … after black hole dies … this should be truly from
physics point of view, but still, which would reflect the limits and so on, usage for instance.
So everything should be overthought."

What was wrong: a sun-like star never goes supernova or leaves a black hole (that needs more than
about eight solar masses at birth, twenty or more for a hole); a stellar black hole evaporates in
about 10^67 years and nothing is reborn from one; a red giant doesn't come back to the main
sequence. And the limit cycle must be periodic, because the window resets.

- **The sun is an accretion disk: a white dwarf fed by a companion, the dwarf-nova cycle.** The
  disk is the 5-hour window. Its hot part is the budget left (spent from the rim inward, as a dwarf
  nova's disk cools from its rim in, by area); its light is the spend rate (accretion luminosity is
  the accretion rate: knots spiral in and the inner edge glows while something works, nothing moves
  at rest); the limit is the disk gone quiet (cool, dim, red, the white dwarf a point, the stream's
  bright spot the brightest warm thing); the wait is the stream refilling it (`REFILLS IN 0:24`);
  the reset is the outburst, a heating front sweeping in from the rim; the donor goes once round
  per window, so it is the window's clock like the ring's time arc. The white dwarf survives it
  all. Everything has a real name and a real analogue (SS Cygni, U Geminorum; the disk-instability
  model), and the cycle is periodic by nature. Built; the look is in DESIGN.md ("The sun is an
  accretion disk").
- **Weighed and not chosen:**
  - *A recurrent nova* (T CrB, RS Oph: hydrogen piles up on a white dwarf and ignites every
    decades). Periodic and honest, but the quantity that builds is an invisible surface layer, so
    usage would have had no picture of its own, and the eruption would have made the limit the big
    bright event again, with the wait a fading fireball: "you're out" read as a spectacle.
  - *A Cepheid's pulsation* and *an eclipsing binary*: periodic, but clocks. Nothing in them is
    spent and nothing reaches a threshold at the user's pace; the window's clock is already the
    ring's time arc (and the eclipse is already the sign-out). Kept only as what they are: the
    donor's orbit is the clock.
  - *Main sequence → red giant → planetary nebula → white dwarf*: true, one-way. Nothing makes it
    periodic short of star formation from enriched clouds over millions of years.
  - *A planet's day or seasons*: honest periodicity, but time again, not spending; and a horizon
    would have replaced the whole sky.
- **The meter stays the source of truth.** The ring and its number are unchanged; the disk is the
  mood. Nothing in the physics forecasts the limit; the pace arc and the notch do.
- **Only working things move.** The knots flow only while something spends; a quiet disk through
  the wait and a full disk at rest ask for the far sky's slow frames only (the old hole's disk
  turned at 30 a second through the whole cooldown). Measured idle Home costs the same as before.
- **Kepler everywhere something goes round:** the knots, the moons' shells (6 s inner, slower
  outward by (r/r₀)^1.5), the donor. Planets keep their fixed places: at their distance a year is
  days, so nothing of their orbits shows in five hours, and the labels' slots stay put.
- **The week: the honest low state** (replaced the heat death and the Big Bang kept here at first,
  by the owner's answer of 2026-10-18, "The owner's morning answers" below). At the weekly limit
  the donor's stream stops (a VY Sculptoris low state) and the disk drains to nothing in one short
  transition, the white dwarf a faint point with the donor still on its orbit; nothing else in the
  sky ends. The meters and the line under the sun (`REFILLS IN 2d 4h`) say the wait. At the week's
  reset the stream returns, refills the disk, and it ignites as at the 5-hour reset. The week
  still outranks the 5-hour window. Built on the phone, the widget and the glasses (DESIGN.md,
  "The week"); the Mac draws no sun.
- **Per-model weeks** stay on the meters: a model's week is a slice of the same budget, not a
  second reservoir. **Mac asleep / paused / stale numbers:** nothing is measured, so the disk and
  the ring hold as last seen.
- **The Mac and the glasses follow in the "One design across phone, Mac and glasses" pass**
  (DESIGN.md says what each becomes: the widget's star becomes the disk; Lens's `hole` mode becomes
  `quiet`, its `limit-jets` scene goes, `pop-lifted` draws the outburst; "reborn" becomes "refills"
  everywhere). The hyper-cosmos skill's Solar copy is older still and needs the new sky files
  (LATER L44).

## The owner's morning answers (2026-10-18)

The overnight log of 2026-10-18 left seven calls. The owner, in the morning:

1. **The week: the honest low state.** "Switch to the physically honest one." At the weekly limit
   the donor's stream stops (a VY Sculptoris low state) and the disk drains to nothing; no stars
   burn out and there is no Big Bang. Until the week resets the white dwarf is a faint point in
   an unlit disk; then the stream returns and the disk ignites (the outburst). Replaces the heat
   death and the Big Bang of 2026-10-16 ("The sky, physically true" above).
2. **The fingerprint (or the screen lock) on every "Always" and on switching to Bypass** stays:
   "Yeah. Do it." Everywhere a phone can give either: the chat, the start sheet, automations' No
   asking. The glasses can't check a fingerprint, so Lens offers Allow and Deny, not Always.
3. **The chat's status bar stays while an approval waits** (amber, saying what it waits on), instead
   of stepping aside for the approval card.
4. **Browsing a session's folder outside the projects folder** (such as `~`): "allow … but this should
   be turned on in settings. So, it's up to user." A setting, off by default; secret files and key
   folders stay locked whatever it says (LATER L60).
5. **Lens drops Hyperscribe Companion's client**: voice goes through the Mac (Soniox) only, and Lens
   takes the repository's licence, Apache-2.0 (LATER L68).
6. **A release signing key: later** (LATER L65).
7. **The n8n-style workflow builder: build it**, as `docs/specs/workflows.md` lays it out and in its
   order of work.

## The owner's afternoon answers (2026-10-18)

Asked as multiple choice after the morning's work shipped:

1. **Build four practices from the competitor study** (scratchpad audit, "Top 15"): a worktree per
   session (an "isolated copy" switch on New session, with What changed and clean-up), a pull request
   from the phone (Push, Open pull request through `gh`, the checks' state, "Ask Claude to fix this
   comment"), watch-only sharing (a device or link that can follow a session but not answer or type),
   and an offline prompt queue (prompts typed while the Mac is away send themselves when it's back).
   The owner also asked about n8n: the n8n-style workflows were built this morning ("The owner's
   morning answers", 7); n8n itself can fire a workflow through its webhook trigger and receive its
   results through a plain POST output.
2. **Sessions that carry on at a reset start staggered**, one every 2 minutes, oldest first (LATER L67).
3. **"Always" shows its rule** before the tap: the card and the fingerprint prompt say exactly what
   gets allowed for the session (LATER L72).
4. **Deleting a provider is a soft delete with Undo**; its sessions keep saying which provider they
   ran on (LATER L61).


## Codex as a second agent (2026-10-21)

The owner wants Claude Code and OpenAI Codex in one app. A first attempt by another assistant
(2026-10-19) never worked: every new Codex thread was resumed before Codex had written it down
(`no rollout found`), the shell's `codex` was taken over and failed on those threads, and broken
records retried forever. It is kept on the branch `archive/chatgpt-codex-2026-10-19` and not merged.
Agreed with the owner: rebuild it, with switching mid-conversation and Codex automations. The
app-server facts it rests on are in `docs/specs/codex-app-server.md`; the contract in PROTOCOL.md,
"Codex" and "Switching agents".

1. **Codex's own shared app-server, never a server of Hypernova's.** The daemon is one client of it
   (`codex app-server daemon`), so Codex sessions and their terminals outlive daemon restarts.
2. **Nothing changes for a Claude-only computer**: no Codex: `snapshot.codex` is null and no session
   carries `agent`. The daemon reaches Codex only while the owner uses it (Setup's switch, or the
   first Codex session from the phone), with backoff, and no per-session retry loops.
3. **Codex 0.163.0 or later**, not one exact version: the handshake tells whether a later one still
   speaks the protocol. Hypernova never installs or updates Codex; Setup says how.
4. **A Codex session is a Codex thread** in its own sandbox: Read only or Can edit, Codex asking for
   more on request. Approvals race with a terminal as Claude's do. Rewind, providers, auto-continue
   and terminal menus are Claude Code's only.
5. **The protocol only grows**: `agent`, `switchedFrom`/`switchedTo`, `snapshot.codex` and
   `{t:"codex"}`; no protocol version, so no client of before breaks.
6. **A switch is a new session of the other agent**, seeded with the text the owner read and edited
   (Recent, Entire, None), running nothing until the next message, and linked to the old one. A
   terminal showing the old one follows to the new one.
7. **The shell's `codex`** runs in a shared terminal only once Codex is on for Hypernova, and plain
   codex whenever anything is off (exit 97), as `claude` does.
8. **Codex automations** are a start step on Codex (`providerId: "codex"`) in its sandbox, asking
   nobody, gated on Codex's account. Hook and limit triggers stay Claude Code's (LATER L81).

## Claude Code and Codex as equals (2026-10-22)

After the first Codex install the owner asked: why there's no Codex in Providers; why a new session
offered "GPT Sol" and the session then said "Astra"; why Claude's card says "Usage" and Codex's "Codex
usage"; why Statistics (and much else) say only Claude; whether the solar system counts Codex;
whether the app has different scenarios for Codex, Claude, or both; and that the widgets should know
both. The calls, one rule in every client (PROTOCOL.md, "Codex", Which agent leads):

1. **The leading agent** is Claude Code where it's installed, else Codex. The sun, the widget's star
   and Lens's orrery follow the leading agent's usage. The other agent, while in use, is a second
   star in the sky with its own sessions around it; OpenRouter and other providers keep the white
   dwarf. A new session with no agent named goes to the leading agent.
2. **Usage**: "Claude usage" and "Codex usage" whenever both are in use; plain "Usage" when only one
   is. Codex only: its card is the usage card, with no Claude card waiting for numbers.
3. **Providers becomes "Agents & providers"**: an Agents section (Claude Code and Codex: installed or
   not, version, account, Codex's on/off switch, from the phone too: `POST /v1/codex`) above Claude
   Code's providers, which stay Claude Code's.
4. **The models' default is the owner's own**: Codex's pickers mark the model and effort in
   `~/.codex/config.toml` as the default (what a thread starts on), not `model/list`'s.
5. **Statistics count both agents**: totals over both, `byAgent` splitting them, `days[].codexMs`
   for the chart. Codex's are the turns Hypernova followed, counted as each ends (Codex's own
   rollouts aren't read: LATER L84). "This week…" says "your agents" or "Codex" when Codex worked.
6. **Wording**: wherever "Claude" meant "the agent", it's the session's agent (Codex sessions say
   Codex) or neutral ("the reply", "your agents"). What is truly Claude Code's (its account, its
   install, its providers, Rewind) says "Claude Code", and Rewind isn't offered on Codex.
7. **Codex only works**: a Mac with Codex and no Claude Code gets no Claude nags or cards, Setup has
   a "Use Codex only" path, the shell's `codex` function goes in when Codex is turned on, and new
   sessions, automations and Lens's typed prompt go to Codex.
8. **Claude-only users see no change**, but for the Agents section listing Codex as not installed.

## Going public: the first release, 0.2.0 (2026-10-22)

The owner asked to push to GitHub, make the first release (Mac DMG and Android app), a new film, a
project site and a search presence, after checking the Mac install, that nothing personal is in the
repo, and that Linux is told as a way to run Hypernova, not only Android and Mac.

1. **A fresh repository with one commit.** The working history (390 commits, in which the owner,
   the agents and the notes were mixed) stays in a private archive; the public
   `prudnik-web/Hypernova` starts from a single squashed commit whose author is the GitHub noreply
   address, so no e-mail is in it. Nothing from the old history can be reached by a SHA.
2. **Three ways to host, two ways to hold it.** The computer that runs the agents is a Mac (the menu
   bar app), a Linux server or VPS (`hypernova`) or Docker; the clients are the Android app, the
   glasses and, on a Mac, the menu bar. Every page, the README, the site, the film and the GitHub
   description say so; no text implies "Android and Mac only".
3. **Release files have fixed names**, so `releases/latest/download/<name>` links never change:
   `Hypernova.dmg`, `Hypernova.apk`, `Hypernova-linux.tar.gz`, `Hypernova-Lens.ehpk`,
   `SHA256SUMS.txt` (`scripts/release.sh`, docs/RELEASING.md). The same files are served from the
   project site (hypernova.nikbraun.tech) for a direct download.
4. **The APK is release-signed with a dedicated key** (R8-minified, not debuggable), kept outside the
   repository in `~/.hypernova-signing/` and backed up by the owner. The debug build is for
   development only. Phones with the debug build reinstall once.
5. **The Mac app is ad-hoc signed and not notarized** until there's a Developer ID (L6). The DMG says
   how to allow it. It bundles nothing of Anthropic's: the Agent SDK's own Claude Code binaries are
   left out (the daemon always runs the user's installed `claude`), and its JavaScript package is
   fetched at setup (9).
6. **Node.js: 22.18 or newer, and 24 (LTS) is what the installers fetch** (L64 closed). Node 25 is an
   odd line past its end of life; the Mac already took the newest LTS, and the Linux installer now
   takes the same line.
7. **Codex is set up on a server too** (`hypernova codex [on|off]`, `status`, `doctor`), with Codex
   alone as a complete install.
8. **Found, not bought**: a project site with guides for the words people search, a sitemap,
   structured data, `llms.txt` and an IndexNow submission; no paid promotion, no accounts made for us.
9. **Claude Code's SDK is fetched at setup, not bundled** (owner, 2026-10-22). Its JavaScript package
   (`@anthropic-ai/claude-agent-sdk`) is "© Anthropic PBC. All rights reserved", not ours to
   redistribute, so the DMG doesn't carry it: the Background service step downloads the version the
   daemon's lockfile pins from npm (about 2 MB), once, with the service's own Node.js and its npm,
   into the installed daemon, checked against the lock's integrity; an app update carries it over.
   Never fatal: offline or on an npm error the service starts, and only the "/" list, Rewind and the
   sessions the daemon runs itself wait for it (409 `sdk_missing`, the way to get it as the message),
   with **Fetch again** in Setup, Agents & providers and the popover. It's shown only where Claude
   Code is installed: with Codex alone nothing uses it, so it's fetched quietly and never mentioned.
   The Linux package already left it to `npm ci`. Its peer packages (`@anthropic-ai/sdk`, MIT; the
   MCP SDK, zod and their dependencies) still ship: L91.
10. **Windows through WSL 2 or Docker Desktop, experimental.** The owner asked why Windows isn't
   included. There is no native Windows host yet (PLATFORMS.md; LATER L92), but a Windows PC runs the
   Linux host unchanged inside WSL 2 (Ubuntu) or a Docker Desktop container, and the phone pairs with
   it over the built-in Tailscale node. So Windows is documented now (INSTALL.md, "5. Windows
   (experimental)"; a guide on the site) and labelled **experimental** everywhere, because nobody
   has run it on a Windows PC (LATER L93): it is checked on Linux and by unit tests that simulate
   WSL, and its Windows-side settings come from Microsoft's own pages. The daemon knows it is in WSL
   (`platform.ts`): behind WSL's NAT the local route stays off and its unreachable 172.x addresses
   never go into a pairing link; `hypernova doctor` and `pair` say what to set on Windows (systemd,
   `instanceIdleTimeout=-1`, mirrored networking with its firewall rule). No Windows badge in the
   README, and no text claims native support.

## Choosing a session's folder anywhere (2026-10-29)

The owner, about the new-session sheet's project picker: "I should be able to collapse each of those
sections when choosing a folder. Really I just want a better way to choose a folder… start from my
home folder or manually type in a path, and then start showing the folders from that path… from
anywhere, whether it's my home directory or somewhere random." Before this, the part under a folder
listed only the folders the daemon already knew (sessions' folders and the projects folder's own),
and a folder picked that wasn't one of them fell back to the most recent.

1. **The picker keeps Recent first; every part opens and closes.** Recent and the part holding the
   chosen project start open, the others closed.
2. **"Choose a folder" opens a folder browser** at the chosen folder (so its own folders are one tap
   away), with crumbs back to `~` or `/`, Up, a path field (`~` works; a name typed after the last
   slash finds it among the folders there), Hidden for dot-folders, and **Use** for the folder it's
   on. A chevron on each Recent row opens the browser in that folder. Folders are by name, A to Z:
   the recent ones are already at the top of the picker.
3. **What the browser may see** (`GET /v1/folders`, docs/PROTOCOL.md "Files"): folders' names only,
   never a file and never a key place or a folder of keys. Inside the projects folder always;
   anywhere else, `/` included, only while **Browse outside projects** is on. This widens that setting
   (owner, 2026-10-18, "The owner's morning answers", 4: sessions' folders in the home folder,
   read-only): with it on, the names of any folder outside the key places now reach the phone too. A
   session could already start in any existing folder (`POST /v1/sessions`); only the names are new.
   The file browser's rules don't change.

## The session sheet (2026-10-29)

The owner, about the chat's header: no spinning planet under it and no line saying how many agents
run and which mode the session is in; the usage card stays, and a tap on it opens a Session sheet:
"it's not just usage, it's all the information about this session and what you can change."

1. **The status line keeps its words only** (IDLE, ENDED, PAUSED, where the Mac is), then the usage
   strip. Its trail (the agents and queued counts, the mode tag with its one-tap cycle) is gone; the
   strip's TalkBack sentence says them instead. The cycle went with it (owner, 2026-10-30): the
   sheet's mode picker is the one way to change the mode in the chat.
2. **A tap on the strip or the line opens the session sheet**, which replaces the line's panel and
   the strip's breakdown: usage, the model (and the other agent), the permissions (the mode; the
   blocked calls while any wait), the agents at work and each running workflow's agents done of
   started, where it runs, and the Home card's menu. This supersedes "Permission mode from the phone:
   tap the session's status line" (2026-10-14).
3. **Nothing new from the Mac**: the sheet shows what the session already carries. The phone knows no
   allow rules, so the sheet shows none.
4. **The owner's next pass (2026-10-30).** "Remove the chat/blocked buttons. The permissions section
   should live in the session's details menu": no Chat | Blocked switch over the chat and no Blocked
   tab; the calls, with Allow & retry and Dismiss, are in the sheet's Permissions part, and an amber
   "n blocked" mark after the strip opens them there (Home's card keeps its chip). "Remove the API
   cost amount": no cost on the strip, nor in the sheet. "Add weekly limit to the right of the 5 hour
   window": the strip's `7D` meter after `5H`. The sheet's model came first, then the permissions, then
   the usage (in tabs since the same day: "Session sheet in tabs"), and its usage is the plan's windows only (no context ring, Compact now, tokens table or cost; the strip
   keeps the context and its Compact chip from 85%). "The status ball back on the left of the usage
   bar": the status line's planet is back, the agents and mode stay away.

## Mermaid diagrams in the chat (2026-10-30)

The owner: "Add mermaid diagram render support in the chat." He chose to bundle mermaid.js in the
APK and draw each ```` ```mermaid ```` block in a small offline WebView, themed to the app's dark
colours, with a tap to open it full screen with pinch-zoom, a way to see the code, and the normal code
block when it can't be drawn. Nothing leaves the phone.

1. **Mermaid 11.17.2 is bundled** (`third_party/mermaid`, `assets/mermaid`; about 1.2 MB more APK).
   Its page loads only its own files: a content security policy allows scripts from itself alone, the
   app answers every other request with a 404, navigation is blocked, and mermaid runs with
   `securityLevel: 'strict'`. The source goes in as a JSON string, never as markup.
2. **One hidden WebView draws, the chat shows pictures.** A chat can hold many diagrams, so there is
   no WebView per diagram: one shared page (1 px and clear in the window, made with the first diagram,
   closed after a minute idle) draws each source once and hands back its SVG and a PNG, kept by a
   hash of the source (32 MB, least recently used first). The chat shows the PNG as an image; full
   screen shows the SVG in a WebView with JavaScript off.
3. **Labels are SVG text** (`htmlLabels: false`, off mermaid's default): a canvas that drew an SVG
   with HTML inside (a `foreignObject`) is tainted and can't be read back as a picture.
4. **Only a finished block is drawn:** while its closing fence hasn't arrived (a reply streaming
   in), it's code.

## Back from a session goes Home (2026-10-30)

The owner: "If you're in a session and you click the notification for another session to go into
that second session, when you try to go back it puts you back in the first session. I always want
Back to go back to the main menu that shows all the sessions, so I don't have a session within a
session."

1. **Never a session over another session.** Opening a session from a session's screens (its chat,
   its agents, its terminal, or files and pages opened from them) leaves Home and that session: a
   banner, a notification, "Continued from/in", a forked or moved session. Back lands on Home.
2. **From outside the app** (a notification, a widget, an automation's note) it is always Home and
   the session, as before.
3. **Within one session nothing changes:** its agents, terminal and files still go on top of it, and
   Back returns to it. The session already on screen opened again stays as it is.
4. **Elsewhere it still goes on top** (an automation's runs): Back returns there. The rule is one
   function, `sessionStack` in `data/AppModel.kt`.

## The glasses are removed (2026-10-30)

The owner removed the Even Realities G2 glasses feature, Hypernova Lens, entirely. The entries above
about Lens, the glasses and what was built for them stay as history; none of it is in the code any
more.

1. **Gone:** `lens/` and its release workflow; the phone bridge (`android/.../bridge/`, Settings →
   Glasses, the "Allow Hypernova Lens" prompt, "Glasses speak through this phone"); on the daemon the
   glasses through a phone (`X-Hypernova-Via: lens`, `/v1/devices/me/glasses`, glasses in the Mac's
   device list), the Lens guide (`/v1/guide*`), Nebula and its notes, Telescope and "Show me"
   (`/v1/sessions/{id}/telescope` and `/screenshot`, `/v1/shots/*`, `/local/telescope/*`),
   `turn=last` on events, and the CORS headers the glasses' WebView needed.
2. **Automations** lose the `say` kind ("Read aloud on my glasses"), notify's `glasses` flag and the
   `no_glasses` problem. A saved automation that still uses `say` no longer reads, and is turned off
   with its card saying so. Statistics lose their glasses counts.
3. **The Mac app is left as it is** (out of scope: it isn't built here and the owner doesn't use it).
   It may still call the routes above, which now answer 404, and still show a Lens guide entry.
4. **What only the glasses guarded is gone with them:** the daemon no longer refuses Bypass, "Always"
   or Browse outside projects to a "glasses" device, as there are none. A device that paired as Lens
   in a private build is now an ordinary device: forget it on the Mac.

## Voice stays on the phone: Soniox is removed (2026-10-30)

The owner removed the third-party (cloud) voice, Soniox, and kept the voice that never leaves the
phone. The entries above about Soniox and Hyperscribe Companion stay as history.

1. **Dictation is the phone's own, always:** the mic uses Android's on-device recognizer
   (`PhoneDictation.kt`, from 2026-10-29); a phone without one has no mic. (Refined 2026-10-31,
   "The phone's recognizer for dictation": the phone's default recognizer by default, on-device as
   a setting.) Gone: speech to text
   through the Mac (`stt.ts`, `/v1/stt*`, the snapshot's `stt`, `{t:"stt"}`), the Hyperscribe
   Companion route (`/v1/speech/companion`), the phone's streaming of its microphone, and Settings →
   Voice (the Soniox key, Language, Headset microphone).
2. **Read aloud is the Mac's own voice (`say`), or the phone's own text-to-speech** where the computer
   has none (a Linux server). Gone: Soniox's voices (`tts.ts`), the engine choice and the fallback
   note. `Speech` keeps `engine` and `using`, always `"mac"`, for clients that read them.
3. **Files left alone:** a kept Soniox key (`<support>/stt.json`) is no longer read; its owner deletes
   it. `speech.json`'s Soniox fields are not read, and go with the next save of the setting.
4. **The Mac app is left as it is** (out of scope): its Setup Voice step and Settings → Voice… now
   reach routes that answer 404.


## Onboarding is the server's three steps (2026-10-30)

The owner, about "Connect your computer": make it clear that you download and install the server
part on the server, then have it make a code you scan or a URL you go to.

1. **Three steps:** Install Hypernova on your server (where Claude Code runs; "Open the releases"
   opens the fork's latest release, with the server package), run `hypernova pair` on it (a QR code
   and a link), then scan the code with this phone's camera, or open the link or paste it.
   **Paste pairing link** stays below them.
2. **No "Mac" in the onboarding and pairing sentences.** `ComputerWords` turns "Mac" into "server"
   while a Linux one is active, which made "your Mac or server" read "your server or server" and the
   menu bar's steps read "On a server, click the Hypernova icon in the menu bar". They now name the
   server and `hypernova pair` only.
3. **From the switcher it is "Add a server"** (the switcher's row and the screen's title), with its
   own line under the title. Cancel (or, on first run, the demo) is pinned at the bottom above the
   navigation bar and the keyboard, so it never sits under the system's buttons while the steps
   scroll.

## Tailscale is removed (2026-10-30)

The owner: "remove the Tailscale integration, don't need this". His phone reaches his Linux server
on the home network (the local route) or over his UniFi VPN, which lands on the same network. The
entries above about Tailscale, the built-in nodes, `tailscale serve`, Headscale and the tailnet
hints stay as history; none of it is in the code any more.

1. **One way in: the local route.** The LAN listener (port 47323, the pinned certificate, the
   device certificate) is how a phone reaches the computer, on the same Wi-Fi or over the owner's
   own VPN into that network. The phone API stays on loopback (47321). The pairing link carries
   `fp`, `lan` and `lp`; its base URL `u` is `http://10.0.2.2:47321` (the emulator's, which a real
   phone tries once and passes over).
2. **Gone from the daemon:** its Tailscale helper (`daemon/tsnet/`, `daemon/src/tsnet.ts`), the
   Tailscale app's `tailscale serve` (`/local/tailscale/serve`, `/local/status`'s `tailscale`),
   `/local/tsnet*`, the snapshot's `mac.tailnet`, `{t:"tsnet"}`, the pairing link's `ts`, `ti`, `ta`
   and `tc`, `hypernova tailscale` and `hn tailscale`, and every Tailscale line in `setup`, `pair`,
   `status`, `doctor`, `uninstall`, the install script, the package and the Dockerfile. With the
   local route off, `pair` and `doctor` now say the phone has no way in (`hypernova lan on`).
3. **Gone from the app:** its built-in node (`android/tsnet/`, `tsnet.aar` and the gomobile build,
   `data/Tsnet.kt`), the `Tailscale` route between Local and the base URL, the computers' tailnet
   in the multi-server list (`Servers.nodePlan`, `NodeDestinations`), the sign-in and control
   server cards, Settings' Tailscale rows, "Open Tailscale" and following the computer on its
   tailnet. The routes are Local, then the base URL as it is. The build needs no Go, gomobile or NDK,
   and CI installs none.
4. **Kept as they are, unread:** the daemon's `builtInTailscale` and `tailscaleControlUrl` settings
   and its `<support>/tsnet/` folder; on the phone the stored `tailnet*` and `controlUrl` fields (they
   drop out at the next save of the list of computers) and `files/tsnet`. Nobody's files are
   deleted, and a pairing made before keeps working on the local route.
5. **The Mac app is left as it is** (upstream's, not built here): its Tailscale step and
   `mac/build.sh`'s Tailscale helper no longer work with this daemon.

## Pairing is local only (2026-10-30)

The owner: "Pairing isn't working, at least over VPN. And it still references the observer URL
from the fork. I want the pairing URL to reference the URL of the machine that has the server,
completely local only."

1. **Why a second server couldn't pair** (read from the code; not seen on the phone).
   Since Tailscale went (above), every computer's pairing
   link said `u=http://10.0.2.2:47321`, and the app keeps one route per base URL (`ActiveRoutes`).
   The app posted a new code to `u` first, through that registry: with a computer already paired
   and connected, the request went down *that* computer's local route, which answered 403
   `pairing_closed` (no code shown there), and the app took it as a bad code and never tried the
   new server's addresses. A second server paired anyway would have shared the first one's base
   URL, and so its routes. Nothing about it was the VPN: the listener lets in any private source
   (192.168.2.x into 192.168.1.x included) and the phone tries the local addresses on any network.
   The daemon logged no refusals then, so nothing showed in its log; it does now.
2. **The QR code and the printed link are the server's own pairing page**, on the LAN listener at
   the address a phone's browser is sent to: `https://192.168.1.48:47323/pair?c=…&n=…&fp=…&lan=…&lp=…`.
   A phone camera opens https links (not a custom scheme), so it works with any camera app; the
   browser warns once about the server's own certificate, then the page opens the app
   (`intent://pair?…`, falling back to the server's download page). **Paste pairing link** takes the
   same link. The page answers only for a code being shown. With the listener off, the link is the
   app's own `hypernova://pair?…`.
3. **`u` is that listener too**, so each server's pairing has its own base URL. The app pairs over
   the listener first, with the pin, and only then tries `u` as it is, on a route of its own (never
   another computer's). An older link's plain-http `u` is replaced by the address that answered.
   A base URL that is the listener is not probed as a second route.
4. **Nothing public:** no `https://hypernova.observer/pair` link and no `HYPERNOVA_PAIR_APPLINK`
   switch (local is the only behaviour); the name service is off unless one is configured
   (`nameServiceUrl` or `HYPERNOVA_NAMES_URL`; unset was `https://api.hypernova.observer`), and
   no pairing link ever uses a name. Its code stays, unused. The app has no App Link for any https
   domain; it still reads an older computer's `hypernova.observer/pair#…` link pasted into it,
   from the fragment, without fetching anything.
5. **The link's `lan` is the IPv4 addresses** whenever there is one (a smaller QR code); the
   snapshot still says them all.

## Never auto-deny (2026-10-30)

The owner: Hypernova never says no for him. The 10-minute auto-deny (Sessions, "Approvals are
answered from the phone", 2026-10-13) stays as history for Claude Code; Codex keeps it.

1. **An ask nobody answers goes back to Claude Code.** After 10 minutes the PermissionRequest hook
   answers with no decision (`hooks.ts`, `timeout` → `{}`), so Claude Code asks in its own dialog
   in the session's terminal, and the session waits there. No "no" to Claude, no `stoppedForAway`,
   no `away` row in Blocked calls; the approval resolves as `terminal` and a notice says the question
   is in the terminal now.
2. **Codex keeps the stop.** Its approvals have no terminal dialog to fall back to: after 10 minutes
   its ask is cancelled, `stoppedForAway`, an `away` blocked call and Continue, as before.
3. **The phone can always answer Claude Code's own dialog.** Wherever the session's terminal is
   shared (a holder, or a `claude agents` job through `claude attach`), the daemon reads the dialog
   off the screen (`menu.permission`: the tool, the command whole, its question) and the session
   needs you while it shows, whatever Claude Code's registry says (a `claude agents` job's stays
   "busy"). The chat shows it where the asks go, as an approval card: the command, Deny and Allow,
   and the dialog's other options as it words them; the Home card has Deny and Allow. A tap presses
   that option in the terminal (the menu route), so the same dialog shows there before and after.
   It also comes without the 10 minutes: an ask rule over auto mode ("Ask rule Bash(python:*)
   overrides auto mode for this command") brings it up with no hook asking first. Seen on the
   owner's phone the same day: the Arc session's dialog had no card at all, the session read
   "working", and his three replies sat held behind it.
4. **Never another job's dialog.** A `claude attach` holder that shows another job or the agent list
   (it drifted) has no menu for the session; answering goes through a fresh `claude attach`. Two
   dialogs for different commands are different menus, so an Allow never lands on the next one.
5. **A handed-back ask comes back as a card, with one notice.** Seen on the owner's phone the same
   day: a `claude agents` job with no `claude attach` on it gave four stacked "open the terminal"
   notices (one per ask, its agents' too), no card, and they stayed after he answered. Now the
   first ask handed back on such a job attaches it (never reading a holder that drifted), so the
   dialog becomes the card; the session has one notice, "Still waiting for your answer below."
   while the card shows, removed once the asks are answered.
6. **Left as it is:** automation runs still get Esc on a terminal menu that waits 10 minutes with no
   approval behind it (Automations, "Following"), so a run never needs you all night.
7. **Sessions the daemon runs through the SDK wait** (2026-10-30, follow-up). They still got a no
   with the away message after 10 minutes while the phone was told the ask had gone to the terminal,
   which they don't have. Now their ask simply stays pending (no timer, `expiresAt: null`): the session
   needs you and the phone keeps the card until it is answered, with no notice. An automation's own
   run keeps the stop, away (`autoDeny`, `stoppedForAway`), since automations are left as they are
   (6). The phone's band counts down ("auto-deny in 9m") only where Hypernova will say no: Codex and
   an automation's run, never the owner's Claude Code asks.
8. **Never Esc on the owner's dialog** (follow-up). An automation run that continued one of the
   owner's sessions no longer presses Esc on that session's Claude Code permission dialog (an ask
   handed back there) after 10 minutes: the run stops following it, away, and the dialog stays. The
   run's own sessions keep the Esc of 6.
9. **Automations wait too** (owner, 2026-10-30: "no 10 min no, just keep open forever"). This
   replaces 6, 7's automation exception and 8: an automation's SDK run gets no 10-minute "no"
   (`sdkAwayRule` is always `never`), a run never presses Esc on a terminal menu however long it
   waits, and the 12-hour watch spares a run while it waits on the owner (`needs_you`).

## Default mode for new sessions (2026-10-30)

The owner's new sessions kept starting in Ask: the sheet carried over only Accept edits and Plan,
and he works in Auto. He chose a setting for it.

1. **Settings → New sessions, "New sessions start in": Ask, Edits, Plan or Auto**, kept on the
   phone (`data/NewSessionMemory.kt`, `newSession.startMode`). Ask until he picks one. Choosing
   Auto there goes through the think-twice card, as everywhere; a sheet that then opens in Auto
   shows no card, since it was chosen once already.
2. **The New session sheet opens on it every time.** The last session's mode isn't remembered any
   more (this replaces "the mode unless it was Auto or Bypass"). The Mode chip still changes it
   for that one session, and that never changes the setting.
3. **Bypass isn't offered** as the default: it waits for the fingerprint or the screen lock each
   time it's chosen (Bypass mode and the think-twice card; The owner's morning answers, 2).
4. **Switching a session to Claude Code** (the agent switch sheet) starts in the same mode. Codex's
   modes are untouched: the sheet still carries Codex's last one over (Read only or Can edit).

## Session sheet in tabs (2026-10-30)

More is about to go in the session sheet (the effort, a command list with /btw, a restart job), so
the owner had its one long scroll put in tabs first.

1. **The header stays** (the name, the project and short id), and **one summary line under it is
   always in view**: the mode's glyph and words, the model's chip, how long it has run (start to last
   activity, after a stopwatch), the agents at work and the calls blocked when there are any. Drawn as
   the app's metadata is, not "·"-joined (DESIGN.md); short of room, whole parts give way.
2. **Four tabs: Overview, Permissions, Agents, Session.** Overview has the model and the plan's
   windows; Permissions the mode and the blocked calls; Agents the agents at work with the running
   count; Session where it runs and the Home card's menu. Close stays under them. A Commands tab is
   coming; until then there is none (no empty tab).
3. **It opens on Overview**, or on Permissions with the blocked calls in view from the strip's blocked
   mark. The open tab is kept while the sheet is; the next time it opens on Overview again.
4. **Nothing changes on the session's own screen.** The strip and the status line still open the
   sheet; every action in it does what it did.

## Unread and finished on Completed cards (2026-10-30)

Home, idea HM6: with many `claude agents` jobs the owner couldn't tell which finished replies he
hadn't read.

1. **An unread reply is a mint dot before a Completed card's title**, nothing more (no chip, no new
   row). Unread: its last activity (`lastActivityAt`, the card's time) is later than when he last had
   its chat on screen. Opening it, or having it open while replies come in, marks it read.
2. **Kept on the phone, per server** (`data/Unread.kt`, a preferences file per paired computer), no
   daemon change: each phone keeps its own. The demo keeps none.
3. **First run:** a session never opened on this phone counts from when the phone first kept track
   for that server, so every old job is read and only what finishes after is unread.
4. **Completed cards say when they finished** in their stamp, short: `just now`, `5m ago`, `2h ago`,
   `yesterday`, `3d ago` (hours under a day, then by the calendar). Needs input and Working keep
   `1m`. TalkBack: "Session c7, finished 5 minutes ago."
5. **Completed is newest first** (owner, the same day), by that time, the same time by name. Needs
   input and Working stay A to Z, so their cards hold still; unread doesn't change the order.
6. **Stop for idle `claude agents` jobs** (owner, the same day: he couldn't stop an idle one, only
   Rename and Delete showed). The card's menu offers Stop for any job whose process still runs, idle
   included (`claude stop`, the conversation kept); not for one done or stopped already (the session's
   `agentRunning`, docs/PROTOCOL.md, "Session menu"). An older Mac doesn't say: idle offers no Stop.

## Commands in the session sheet (2026-10-30)

A Commands tab, before Session, for asking Claude something aside while it works (IDEAS SC1) and
reaching his commands and skills without typing "/" (SC2, SC5).

1. **Ask on the side first: Claude Code's `/btw`.** A field and Ask; typed into the session at
   once, while Claude works too (never held, never queued behind the turn: that's what it's for),
   only into a terminal Hypernova holds or a `claude agents` job through `claude attach`, never a
   fork. A holder that shows another session is refused, nothing typed. One question at a time.
2. **Its answer shows in the tab, not in the chat.** Claude Code draws it in a panel and keeps it
   out of the transcript, so the Mac reads it off the screen, closes the panel with Space (Esc
   could stop Claude's turn) and the tab asks for it until it's there. Where it can't be read, or
   it ran past the screen, the tab says so with Open the live terminal. The panel's shape is read
   from Claude Code's code: a live look must confirm it (PROTOCOL, "Side questions").
3. **Then the commands and skills, as the "/" list has them:** Recent (sent from this tab, five),
   Pinned (long-press, in the order pinned), then the owner's, then Claude Code's, with a search.
   Recent and pinned are kept on the phone for every project; a project lists only those it has.
4. **A tap sends one that takes nothing; one with an argument hint opens a field under it first**
   (`<…>` needs something before Send, `[…]` or "optional" doesn't).
5. **Claude Code's commands that open a screen of their own in the terminal are marked, not
   hidden:** last, under "Open in the terminal", each with its mark; a tap sends it and opens the
   live terminal (dimmed where there's none). Claude Code's list doesn't say how a command draws,
   so the Mac keeps that list by hand (`commands.ts`, from 2.1.314's own command table); a
   numbered picker (/model, /effort with nothing after) isn't on it, since the phone answers those.
6. **Left out of the tab:** /btw (it has the field), /logout (one tap would sign Claude Code out on
   the Mac) and /stop (the Session tab's Stop). The "/" list in the session's box is unchanged.

## Restarting jobs on an old Claude Code (2026-10-30)

Claude Code updates itself, but a `claude agents` job keeps the binary it started on until
`claude respawn` (IDEAS AG4). Seen the same day: the hypernova job ran 2.1.312 with 2.1.314 installed.

1. **The daemon knows each job's version.** Claude Code writes `version` into the job's registry
   entry (`~/.claude/sessions/<pid>.json`; the process's `/proc/<pid>/exe` agrees, `versions/2.1.312`).
   The installed one is the file `claude` resolves to (`versions/<version>`), read again as it
   changes, or `claude --version` once per install for any other. Only a job with a process is
   compared: a stopped one has none, and says its version again once woken (job processes are
   `claude bg-spare` processes started ahead, so even a fresh one could be behind).
2. **The phone says it in the session sheet's Session tab only:** "Runs Claude Code 2.1.312; 2.1.314
   is installed", quiet, above the actions, with **Restart** first among them. The Home card's
   long-press menu has Restart too, for such a job only. Nothing on the session's own screen.
3. **Restart asks first,** saying a job at work is interrupted. The attach holder ends first, as for
   Stop; Claude Code's own words come back when it refuses.
4. **One job at a time.** No "restart all" yet: `claude respawn --all` restarts every job, current or not.

## Effort for Claude Code sessions (2026-10-30)

The phone offered a reasoning effort only for Codex. The owner chose both places for Claude Code's
(IDEAS.md, NS1 and SS3):

1. **New session: an Effort chip** between the model and the mode, opening a picker of Default, Low,
   Medium, High, Extra high and Max. Default passes nothing (Claude Code's own); a level starts the
   session with `--effort`. The last one started with is remembered on the phone, as the model is.
   Codex's sessions show no such chip: their effort stays on the model chip and in its picker.
2. **Session sheet, Overview: the effort under the model**, and a tap changes it while the session
   is open: the Mac types `/effort <level>` into its terminal (a `claude agents` job's through
   `claude attach`, never into a holder that shows another session). The five levels only: there is
   no "back to default" from here.
3. **What it shows** is what Claude Code's status line says (it reports the level, default or not),
   else what the session was started with or last set to.
4. **Nothing new on the session's own screen** (owner): not in the chat, not in its header.

## No name service, Mac app or website (2026-10-30)

The owner: remove the visible ties to the project this one started from; a Linux server and the
Android app, local only. The entries above about the Mac app, the name service
(`hypernova.observer`), its App Links and the project website stay as history.

1. **Removed from the repository:** `mac/` (the menu bar app), `names/` (the name service's
   Worker), `site/` (the website), their workflows (`mac.yml`, `names.yml`) and
   `scripts/release.sh` (it built the Mac app's disk image and the glasses' package). Releases are
   `android-release.yml` alone (docs/RELEASING.md).
2. **Removed from the daemon:** the private name and its certificate (`names.ts`,
   `names-client.ts`, `acme.ts`), `hypernova name`, `/local/name` and `/local/name/release`,
   `/local/status`'s `name` and `downloadAnywhere`, `?name` on `/local/pair/start`, and the
   `nameService`, `nameTermsShown`, `nameServiceUrl` and `acmeDirectory` settings (with
   `HYPERNOVA_NAMES_URL` and `HYPERNOVA_ACME_*`). The LAN listener no longer reads the TLS
   ClientHello for a name: every connection goes to the pinned server, as before for an address.
   An older `settings.json` keeps those keys, unread, and `<support>/names/` stays on disk.
3. **Kept:** the licence, `NOTICE` and the third-party notices; the package name
   `com.prudnikweb.hypernova` (in-app updates depend on it); the app still reads a pasted
   `hypernova.observer/pair#…` link from an older computer, from its fragment, fetching nothing.
4. **Links** in the app, the daemon and the docs point at `https://github.com/ljmerza/Hypernova`.

## Previews, Restart all and effort defaults (2026-10-30)

1. **A preview's title is what was tapped** (`localhost:5173`, or the file's name), with the page's
   `<title>` under it when it has one; never the server's address it is proxied through, which
   confused the owner. A dev server that stopped: "Nothing answers on port 5173 on your server."
2. **Restart all** (replaces "One job at a time" above): a quiet, dismissable notice on Home, "3 jobs
   run an older Claude Code", with Restart all, asked first (a job at work is interrupted). Each job
   is `claude respawn <job>`, its attach holder ended first; never `--all`. Nothing on a session's screen.
3. **Effort on the Claude subscription only:** no Effort chip in the New session sheet, and no
   Effort row in the session sheet, for OpenRouter or a custom provider.
4. **A resumed session keeps its effort:** started again in a terminal, it gets `--effort` with the
   one it last had; the SDK passes it with every query. Still no "Default" mid-session.

## Rules in effect (2026-10-30)

IDEAS SS5: the sheet said the phone knows no allow rules. It now shows Claude Code's, read-only, so
the owner can see why a call asked or didn't. This supersedes "Nothing new from the Mac … the sheet
shows none" (The session sheet, 3).

1. **Permissions tab, after the mode and the blocked calls: one "Rules in effect" seam, folded,**
   with the deny, ask and allow counts. Open, one line on how they weigh (deny beats ask, ask beats
   allow), then each source as a row with its counts, which opens to its rules in mono: the managed
   policy, this session's own (an automation's allow rules, "this session" answers), project local,
   project, user, in Claude Code's precedence order, highest first. A file that couldn't be read
   says so on its row; sources with no file share one quiet line. Read each time the tab opens.
   Not for Codex.
2. **Only the permissions lists leave the Mac** (`allow`, `ask`, `deny`, `defaultMode`,
   `additionalDirectories`), cut to size: settings files hold secrets (`env`, `apiKeyHelper`, MCP
   servers). Only the fixed files for the session's own folder are read; the route takes no path
   (docs/PROTOCOL.md, "Rules in effect").
3. **Read-only for now:** adding and removing rules is PA1, the owner's call.

## claude agents on by default (2026-10-30)

`settings.claudeAgents` was off unless written by hand into `settings.json`, which bit the owner on a
second server (his sessions there never showed as `claude agents` jobs).

1. **On for a Linux install.** `hypernova setup` writes `"claudeAgents": true` while `settings.json`
   doesn't say, before the service starts (and tells a running daemon); never over the owner's own
   `false`, nor over a `settings.json` it can't read. Running setup again on a server that never had
   it turns it on.
2. **A switch in the app:** Settings → Agents & providers → Claude Code → Sessions in claude agents
   (`POST /v1/claude {agents}`), with one line saying what it does. The daemon takes it at once,
   without a restart: each poll reads the setting, and turning it on reads the job list right away.

## /btw from the prompt box, commands as typed, pasted images, links (2026-10-30)

The owner typed `/btw I'm testing the btw` in the session's box while Claude worked: the chat drew
a skill's chip and "YOU · SENDING…", and the answer only showed in the terminal, its panel left open.

1. **"/btw <question>" in the box goes as the Commands tab asks it** (the side-question route,
   never held), not as a prompt. A deliberate exception to "nothing new on the session's own
   screen", asked for by the owner: the chat shows the "/btw …" line as a sent command, where it
   falls in time, kept in the app's memory per session (the transcript never has it; an app restart
   forgets it), and **the answer is a card that slides up over the box**, not a row in the chat and
   not a pop-up: "Answering…", then the answer (selectable), Claude Code's error words, or Open the
   live terminal where it couldn't be read or ran past the screen. Swiped sideways or pulled down,
   it goes, on this phone only; the Commands tab still has the last one. Files in the box stay
   there (a side question takes none). This updates "Commands in the session sheet", item 2.
2. **The real panel, seen on the owner's phone** (84 columns): its footer wraps beside the status
   line's "← 4 agents", which the reader didn't expect, so no answer was ever read. The daemon reads
   the footer's rows as one now (PROTOCOL, "Side questions"; `test/fixtures/btw-screens`).
3. **Claude Code's own commands read as typed**: /btw and the others Claude Code runs at once
   (/stop, /tasks…) are COMMAND rows ("/btw I'm testing the btw"), as /model and /effort were, with
   what they printed under them; skills and the owner's commands keep their chip.
4. **Commands Claude Code runs at once are never held** (held prompts): typed now, or refused as an
   unheld prompt is. Regular ones wait their turn as before.
5. **A pasted image is an attachment**, as the paperclip adds one (the clipboard's, or a keyboard's
   such as a screenshot suggestion); anything else pasted stays text. The box became a
   TextFieldState field for it (only those take a keyboard's images); its look is unchanged.
6. **Every web address in the chat is a link** (bare `https://…` and `www.…`, Markdown's
   `[words](url)`), in replies and the owner's messages; not in code blocks, tool output or an
   agent's card (a tap there does something else). **A tap asks first**: "Open this link?" with the
   whole address, its scheme and host bright (what's before an "@" dim, so a lookalike shows),
   Open (the phone's browser), Copy link, Cancel. A dev server on the Mac (`localhost:5173`) opens
   its in-app preview at once, as its pill does; Hypernova's own ports and any other scheme
   (`intent:`, `file:`, `javascript:`, `content:`) are never links.

## Argument helpers in the Commands tab (2026-10-31)

IDEAS SC3 and SC8, in the session sheet's Commands tab only. This updates "Commands in the session
sheet", item 4, for these four.

1. **A tap on Claude Code's own /effort, /model or /rename opens a helper, through its route, not
   typed:** /effort its five levels as chips under its row, the session's lit (`…/effort`, as the
   Overview's Effort part, on the subscription only); /model the Overview's model picker, over the
   sheet (`…/model`); /rename the rename sheet with the name filled (`…/rename`, where the Mac holds
   a terminal or attaches to a job). Where a route doesn't reach, the field as before. A skill of the
   same name is the owner's: its field.
2. **/compact opens a small sheet**: one line on what it does, an optional "Keep…" line, Compact.
   It sends `/compact`, or `/compact <focus>` trimmed onto one line (a line break would send it early).
3. **Recent counts a helper's command once it's sent** (an effort once set); the model picker and
   the rename sheet finish on their own, so those count once opened.
4. **The chat screen is unchanged** (owner, the same day: nothing more added to it): its "/" list,
   its box and the usage strip's Compact, which still sends `/compact` with one tap.

## Time, git and files touched in the session sheet (2026-10-31)

The owner picked ideas SS7, SS8 and SS9 for the session sheet only ("is it stuck or slow", what it
changed, which branch), compact, nothing on the session's own screen.

1. **In the Session tab, before WHERE IT RUNS:** TIME, then GIT. Overview stays the model and the
   plan's windows (the tab most opens land on, kept short), and the summary line already says how
   long it has run; the Session tab is the facts about this run, live ones first.
2. **TIME:** Started (the phone's clock: `14:05`, `Wed 9:30`, `17 Oct 8:00`), Running for (to now;
   `Ran for`, to its last activity, once ended), This turn in mint while it's in a turn (working,
   starting or waiting for an answer), Last activity (`just now`, `2m ago`, `yesterday`).
3. **This turn is the Mac's clock:** a new `turnStartedAt` on the session, set when it goes into a
   turn, kept while it waits on him within it, and cleared when it's out, so reopening the chat
   doesn't restart it. A turn already running when the daemon started counts from Claude Code's
   registry stamp for it, else from then. Home's "for 12 min" reads the same clock (below).
4. **GIT:** Branch with `↑2 ↓1` against its upstream in mint (`no upstream` dim; even shows
   nothing), Worktree when the folder is a linked worktree (git's name for it), Uncommitted files in
   the folder (`none` when clean; left out when the next line already says the same), then the line
   to What changed, `7 files touched` and `+120 −30`, which opens the What changed screen over the
   sheet (scoped to the session as before; `7 files changed` when none is known to be this
   session's). Not a repository: no GIT part. The counts are as of the last fetch: nothing is
   fetched.
5. **The daemon's git line** rides on the changes route (`upstream`, `ahead`, `behind`, `dirty`,
   `worktree`): two read-only local commands in the session's folder, 3 seconds at most (then left
   out, the changes still come), kept 5 seconds per folder and branch.

## Home cards: what it's doing, for how long, and swipes (2026-10-31)

The owner's picks from IDEAS (HM1, HM7, HM2), with the cards kept as uncluttered as they are: no
new chip, no new row, nothing on a session's own screen.

1. **A Working card's second line is what it's doing now** (HM1): the to-do under way as Claude
   words it while under way, with its tally, "Running the tests 3/7" (done of all, as the chat's
   status bar counts), else the session's activity ("Bash: npm test"). One quiet line, ellipsized,
   the tally kept. Needs input keeps its ask; Completed keeps its last line and "finished … ago".
   The daemon sends the list in short on the session (`Session.todo`, PROTOCOL), so Home asks for
   no to-do list per card.
2. **"for 12 min" at that line's end** (HM7): since the current turn started, kept while it waits
   on him within the turn; `for 1 h 5 min` past an hour, `for 1 d 3 h` past a day, nothing in the
   first minute. It's the session sheet's turn clock (`Session.turnStartedAt`, above: set as the
   session goes into a turn, from Claude Code's registry stamp when the daemon first sees it busy,
   cleared once it's idle, failed or ended); the two branches each added one, merged into this one.
   A session working only through its agents has none, so it shows none. The figure ticks on its own minute, alone: no card recomposes for it.
3. **A swipe left on a card** (HM2), a reveal with the action under the card, a haptic tick at the
   threshold, the card springing back: **Needs input opens** it; **Completed stops** a `claude
   agents` job whose process still runs (`agentRunning`, as the menu's Stop), **else deletes** it
   (`claude rm`), asking first with the long-press menu's own confirm. Working cards don't swipe,
   nor does a Completed card that isn't a job. The demo only opens. The long-press menu, the list's
   scroll and Allow and Deny work as before.

## What Always covers, and allowing several (2026-10-31)

IDEAS PA2, PA3 and PA6, the owner's picks. One constraint over all three, his words: "I don't want
to clutter the chat screen, we can't have anything else added to that."

1. **The approval card stays exactly as it was.** Deny, Always, Allow, "Allow all … this session"
   where offered, and the "Yes, and don't ask again" line. Nothing new on the chat screen.
2. **Always opens the Always sheet** where Claude Code suggests a rule: the rule (`Bash(npm test)`)
   in an editable field, quick widenings under it (the command as a prefix, `Bash(npm test *)`, a
   space then `*` as Claude Code 2.1.314's own suggestions write it, the older `:*` in a file still
   read as a prefix; its first two words as one; the whole tool, said as allowing all of it), Claude Code's own to go
   back to, and **where it goes: This session, This project or Everywhere**. It starts on Claude
   Code's rule and Claude Code's place (the project, for a command), so a plain Always allow does
   what Always did before. A rule that isn't `Tool` or `Tool(pattern)` says so and can't be saved.
   Always allow, then the fingerprint or the screen lock, which names the rule and the place. Where
   the suggestion has no rule (a mode, a folder), Always goes straight to the fingerprint as before.
3. **"This project" is the project's local file** (`localSettings`, `.claude/settings.local.json`),
   not `projectSettings`: Claude Code keeps the local one out of git, so a rule from the phone never
   lands in a commit for others; it's also where Claude Code's own "don't ask again" puts it.
   Everywhere is the owner's user settings (`userSettings`). This session saves nothing.
4. **Claude Code writes the rule, never the daemon.** The answer goes back in the hook's allow as
   `updatedPermissions` with that destination, the way its own dialog does it (checked in Claude
   Code 2.1.314). The daemon reads no settings file for this and writes none. Found on the way: the
   old Always already saved Claude Code's suggestion to the project's local file, not "for the
   session" as PROTOCOL said; now it's said and chosen.
5. **Allow these N lives in the session sheet's Permissions tab**, not in the chat and not on the
   Home card: with two or more of a session's tool asks waiting (parallel agents), "Asks waiting",
   each command listed with the agent that asks, and "Allow these 3", confirmed once in a dialog.
   Each is allowed as its own Allow, with no rule. Only Allow: a deny stays one at a time on its
   card, and a question or a plan waits for its own answer. Never automatic. Only the asks the
   phone has as approvals; Claude Code's own dialog in a terminal is answered on its card.

## Questions one at a time (2026-10-31)

The owner, on a question with two questions ("Versioning", "First version"): he "didn't see the
submit button" and couldn't "move back and forth between steps of the form". The card stacked
every question with their descriptions and put Send answers under the last, off the screen.
Only the question card's insides change; nothing is added to the chat screen.

1. **Several questions are steps**: one at a time under "1 of 2 · Versioning", a dot for each
   (filled once answered; a tap goes there), and Skip, Back and Next under it. On the last, Send
   answers sends them all at once, enabled once every question has an answer (one option, at least
   one of a multi-select, or "Other…" with words); while one is open it says which, and a tap goes
   to it. Back and Next keep every answer. Claude Code would take fewer answers (its form lets one
   go unanswered); the phone asks for all, as before.
2. **The buttons always show**: a step's options scroll past 45% of the screen, under its header
   and above the buttons; typing under "Other…" brings the buttons up above the keyboard.
3. **One question stays as it was**: pick, then Send answer (Skip beside it).
4. **Multi-select labels are joined as Claude Code joins them**: by ", ", a label with ", " or a
   quote in it in quotes, so Claude reads the list back right.
5. **The same form in the terminal** (an ask handed back after 10 minutes): its menu card gets the
   form's tabs, Back, a chip per question (✓ once answered) and Submit, Next, pressing ← and → there
   (PROTOCOL, `menu.steps` and the menu route's `move`/`step`). Which tab is open shows only in
   colour, so no chip is marked; a jump presses ← to the first, then → to it. Laid out from Claude
   Code 2.1.314's code, not a captured screen: a live look must confirm the row reads, ← and → move
   there (also from "Type something.", which gets ↑ first) and "Submit answers" shows as an option.

## Semantic versions (2026-10-31)

The owner's decision: proper semantic versions from conventional commits, no more `-ljm.N`.

1. **Each release is the next `vX.Y.Z`**, counted from the commits since the last one, as
   semantic-release does (`scripts/next-version.sh`): a breaking change (`type!:` or a
   `BREAKING CHANGE:` line) is a major, a `feat:` a minor, anything else a patch. Merge commits
   don't count, the commits they bring in do. The first is **v0.3.0**, and nothing goes under it.
2. **The files carry the version:** `hypernova-vX.Y.Z.apk` and
   `hypernova-server-vX.Y.Z-linux.tar.gz`; the APK's versionName is the version (the workflow
   passes `-PappVersion`), its versionCode still the build minute.
3. **The notes group the commits** into Features, Fixes and Other, without the `type(scope):`
   prefix, then the install, with the one-line `get.sh`.
4. **Commits are conventional from now on** (CLAUDE.md, docs/RELEASING.md, "Commits"), for anyone
   working here.
5. **The `v0.2.0-ljm.N` releases (N = 1 to 28) become `v0.2.N`**: tag, title, notes and asset names, by `scripts/rename-releases.sh` (a dry run unless `--apply`).
   The newest stays the latest until v0.3.0. The entries above that name `-ljm` tags are history.

## A notice when a job dies (2026-10-31)

Idea NT4: a `claude agents` job that crashed or ended on an error just showed as done or ended.

1. **Claude Code's own verdict decides** (`daemon/src/job-deaths.ts`): a job's state file
   (`~/.claude/jobs/<job>/state.json`) ends `done` when it finished, `stopped` when it was stopped
   (`claude stop`, the phone's Stop, a signal) and `failed` when it died: a crash or error exit
   ("exit 1 — …", "SIGKILL (137)"), its process gone mid-turn, an API error that ended it, Claude
   giving up. Its `detail` is the reason. Read for every job `claude agents --json --all` doesn't
   show at work or blocked, again only when the file changes. Found in Claude Code 2.1.316 (its
   `crashed` is on the way to `failed`); a later version may say it differently. Hypernova doesn't
   guess from a process vanishing: Claude Code's supervisor watches its own jobs, and one that was
   down says `process gone while supervisor was down` when it's back.
2. **Said once per death, never for the owner's doing.** The daemon keeps each job's last state
   (`<support>/job-states.json`): a death is a job last seen alive now `failed`, so a restart of the
   daemon neither repeats an old one nor misses one while it was down, and a job first seen dead
   says nothing. A job stopped, deleted or restarted from the phone in the last minute isn't a
   death, whatever it ends as; a deleted one leaves the list. One Claude Code is respawning
   (`…; respawning`) is said only if it's still dead a minute later.
3. **The phone says it as a session stopping on an error** (`{t:"job.died"}`, docs/PROTOCOL.md,
   "Session menu"): a banner while the app is open, else a notification in Session updates, "<name>
   stopped on an error" over Claude Code's reason; a tap opens the session. No new channel or
   toggle: the others have none either.
4. **Home's Completed card says `Stopped with an error`** in the danger colour as its line
   (`Session.died`), no new row; the session's chat shows nothing (no clutter there).

## Enter sends (2026-10-31)

The owner: "When I press Enter it [should] submit, it doesn't do new line."

1. **A setting, Settings → Typing → Enter key: Send (the default) or New line**, kept on this phone
   (`data/EnterKey.kt`, `ui/components/EnterKeyPref.kt`); a change reaches open boxes at once.
2. **On Send, Enter is the box's own Send**, the same path as its button (/btw to the side question,
   files, held prompts): the soft keyboard shows Send for Enter, and the text still wraps up to its
   lines (6 in the session's box). An empty box: Enter does nothing, no new line either. Hands-free
   dictation: Enter is Done, then Send once the words are in. A hardware keyboard's Shift+Enter is a
   new line; the soft keyboard has none then (pasted lines still paste as they are). A keyboard that
   types a new line for Enter instead of pressing Send has it taken back and sent.
3. **On New line, Enter is a new line as before** and only the button sends.
4. **Where**: the session's prompt box, the New session prompt (Enter is Start), /btw and a command's
   argument in the Commands tab, and a quick prompt being edited (Enter is Save). Left as they were:
   the automation and workflow prompt editors, the text carried to the other agent, an answer's
   "Other", the edit of a waiting prompt; one-line fields already submit on Done. Nothing new on the
   chat screen: only the key changed.

## The phone's recognizer for dictation (2026-10-31)

The on-device recognizer heard noticeably worse than the keyboard's voice typing. The owner chose a
setting, defaulting to the keyboard's recognizer. This refines "Voice stays on the phone"
(2026-10-30): dictation's audio still never goes to the Mac, and no cloud voice of Hypernova's own
comes back, but by default the phone's own recognizer may send it to Google, as the keyboard does.

1. **Settings → Voice → Voice recognition: Phone's recognizer | On-device only**, default Phone's
   recognizer, kept on the phone (`VoicePrefs`), from the next dictation on. One line says where the
   audio goes: "Phone's recognizer: as your keyboard's voice typing, may use Google's servers.
   On-device only: never leaves the phone, less accurate." Nothing new on the chat screen.
2. **Phone's recognizer** is `SpeechRecognizer.createSpeechRecognizer` (the default recognition
   service, normally Google's), without `EXTRA_PREFER_OFFLINE`. **On-device only** is
   `createOnDeviceSpeechRecognizer` with it, as before. Everything else is as it was: partial
   results (the dim live words), 4 s of quiet, no language set (the recognizer's own), Send while
   dictating, the errors.
3. **Fallbacks** (`DictationChoice.engine`): Phone's recognizer with no default service uses
   on-device. On-device only with no on-device recognizer has no mic ("Dictation isn't available on
   this phone."); it never falls back to the phone's recognizer, which would break its "never leaves
   the phone". Neither: no mic, as before.
4. **Both, from Android 13:** `EXTRA_ENABLE_FORMATTING` = `FORMATTING_OPTIMIZE_QUALITY` (punctuation
   and capitals, tuned for quality over speed) and `EXTRA_MASK_OFFENSIVE_WORDS` = false (words as
   said). Older Android gets neither. Whether a recognizer honours them is its own affair.

## The last 30 days as a bar chart (2026-10-31)

The owner: "remove the galaxy image and make this stats section a generic stats section instead.
Each section is good but make the 30 days generic like the others."

1. **The spiral galaxy is gone** (`ui/stats/Galaxy.kt`: the disc's painted arms, dust and bulge,
   the stars by size and light, the slow turn, the "TODAY" name beside the newest star) and its
   caption "One star a day: its size is the hours, its light the tokens." with it.
2. **In its place a card like the others** (DESIGN.md, Statistics): the same seam `LAST 30 DAYS`
   and trail, a GlassCard, one bar a day for the hours of work, oldest left and today right in the
   accent, a day without work a faint tick, light labels only (the first day, the middle one,
   TODAY, the scale's hours). A tap or a drag across picks a day; its line stays under the chart
   as before (`SAT 31 OCT  ⏱ 9h 17m  ⬡ 435M`), today by default. Days outside the chosen range
   stay, fainter, as the galaxy's did.
3. **Tokens are only in the day's line now**: the bars draw the hours alone (the galaxy drew tokens
   as a star's light). Same data as before (`days[]` of `/v1/stats`), no daemon change.
4. **TalkBack**: the chart says its busiest day, then the picked day; "Day before" and "Day after"
   stay as its actions.

## Rename, Stop and Delete for Codex sessions (2026-10-31)

The owner couldn't rename or delete a Codex session: the Home card's long-press menu and the
session sheet's Actions offered nothing for one. Codex's app-server has the calls (its generated
0.164.0 protocol: `thread/name/set`, `thread/delete`, `thread/archive`), so Hypernova uses Codex's
own, not a name or a hiding of its own (docs/PROTOCOL.md, "Session menu").

1. **Rename** is Codex's `thread/name/set`: the name is Codex's, so `codex resume` shows it too. A
   thread Codex never wrote down, or a Codex without the call, keeps the name in Hypernova only.
2. **Delete** is Codex's `thread/delete`, for good, as `codex delete`; the word stays "Delete"
   because that is what happens. Asked first like a job's Delete, with its own line: "Codex deletes
   the conversation for good, so codex resume won't find it either. A terminal open on it closes."
   Not `thread/archive`: that would be "Archive", and it isn't what the owner asked for. A Codex
   without the call says so and nothing is forgotten. A `codex delete` elsewhere removes the session
   from Hypernova too.
3. **Stop** is the existing interrupt (the turn ends, waiting prompts come back named), offered
   while the session works or waits on the owner. No Restart: that's `claude agents`' own.
4. **Home's swipe stays as decided** (HM2: only a job swipes): a Completed Codex card doesn't swipe
   to Delete, since that one can't be undone. The menu and the session sheet's Actions (the same
   `MenuOffer`) have it. Nothing new on the chat screen.

## No demo mode (2026-10-31)

The owner chose to remove the demo entirely. The entries above that mention the demo are history:
what they say about it no longer holds.

1. **No demo in the app**: no "Explore with demo data" on the onboarding or the "unpaired" screen,
   no Demo row in the switcher, no Demo mode in Settings. A phone with no paired server sees the
   onboarding and the pairing steps only.
2. **Gone with it**: its sample data and its own hub (`FakeHub`) with its automations, files,
   statistics, models, provider checks, dictation, chat usage, to-dos and changes; the sun's
   time warp and long press; the demo-only cron reader (a real server checks schedules itself);
   and every branch for it on the screens, which keep what they did with a real server. `Hub` has
   no `isDemo`, and `LinkState` no `Demo`.
3. **An app last left in the demo** forgets the flag it kept (`demo` in its preferences) the first
   time it starts, and opens on its paired server, or on the onboarding if there is none.
4. Nothing on the daemon was the demo's. Nothing new on the chat screen: this only removes.

## Servers fetch the app's releases; Settings offers the update (2026-10-31)

The owner: after a release nothing told the phone, and Settings showed no update. Decided:

1. **The daemon learns of releases itself.** It asks GitHub for the latest release (unauthenticated,
   30 s after start and every 6 hours, with the ETag, waiting out a rate limit), reads the
   release's `hypernova-vX.Y.Z.json` (versionCode, versionName, the APK's SHA-256; the release
   workflow now attaches it), and only when that versionCode is higher than the APK it hands out,
   downloads the APK, checks the SHA-256, and puts it in `<support>/android/` the way `hnctl apk`
   does (the note last). Never fails the daemon: offline or an error is a line in the log.
2. **A local build wins when it's newer.** An APK an admin put there (`hnctl apk`, a dev build)
   with a higher versionCode is never replaced by an older or equal release.
3. **On by default**, the setting `releaseCheck` turns it off (`POST /local/settings` or
   settings.json). docs/SECURITY-MODEL.md says what GitHub sees and how the APK is checked.
4. **Connected phones hear of it**: `{t:"app", app}` when the offered APK changes (a release, `hnctl
   apk`, removed), so no daemon restart or reconnect is needed. Older apps ignore it.
5. **Settings → About** shows "Update available · 0.6.0" with **Update** under the App row while a
   newer app is offered, the same flow as the banner (downloading with its percent, checking,
   Allow, Install, Try again); nothing when there's none. The App row names the installed version as
   it really is: a local build's versionName now comes from `git describe` (`0.5.0-3-g1a2b3c4`),
   not a fixed default. The update card above stays as it was.
6. Nothing new on the session chat screen.

## The 5-hour window only when reported; the session top bar is the owner's (2026-10-31)

On the owner's Claude Max account, Claude Code's `/usage` stopped showing a "Current session" row at
2026-10-31 05:09 UTC (the usage probe's log went from "5-hour 14%, week 62%" to "week 62%"); the
owner says Anthropic dropped the 5-hour limit for Max and a new Ultra plan, and renamed the plans
Pro, Max and Ultra. We can't check that, so the rule is the data's. Decided:

1. **A 5-hour window shows only while Claude Code reports one.** No list of plans: a plan that has
   one shows it, one that comes back to one shows it again.
2. **The daemon says none, not the last one.** It kept the last 5-hour reading and rolled it into
   fresh, empty windows for ever (a 0% meter with a made-up reset). Now a reading that lists every
   window and has the week without a 5-hour one (the probe's `/usage` screen with no "Current
   session" row, a status line's `rate_limits` with `seven_day` and no `five_hour`) clears it:
   `Usage.fiveHour` is `null`. A 5-hour reading no newer than that (an idle session's status line
   still carrying the old window) doesn't bring it back; a newer one does. A weekly limit (which
   carries the week alone) says nothing about it, and a 5-hour limit the account is stopped at
   keeps it. The probe's log line says "no 5-hour window".
3. **The phone:** no 5H on Home's usage card, the session sheet's Usage part, the chat's top bar or
   the widget's footer. The sun's disk, the widget's star and their TalkBack follow the week then,
   as where Codex leads on a week-only plan. The limit and reset automation templates (Carry on
   after the limit, Do it when I have budget again, Switch provider at the limit, Night shift, Fix
   the tests while I sleep) start on the week, the Carry
   on blurb says "your usage limit", and a run's budget offers no "Of the 5-hour window" (unless
   one is set, so it can be turned off). Plan names are Claude Code's own (`subscriptionType`),
   capitalised: "ultra" is "Ultra".
4. **The session top bar is the owner's choice** (his final word the same day, over two earlier
   passes: mode and effort, agents and the turn's time in the freed space; then mode and effort
   alone). **Settings → Session top bar** lists the parts, each with a switch, in the order the bar
   shows them, moved with up and down arrows (not dragging: the list is inside Settings' scrolling
   list, and arrows work with TalkBack), kept on the phone, applied at once, with **Reset to
   default**. The parts: the context ring, mode and effort (`auto · high`; the effort only for a
   Claude session with one; Codex its mode), the 5-hour window and its reset (only when reported),
   the week and its reset, the model, the tokens, the agents at work (`3 agents`, only when more
   than none; a tap opens the session sheet on its Agents tab) and this turn's time (`4m`, only in a
   turn). **Default:** the context ring, mode and effort, the 5-hour window, the week; the rest off.
5. **Nothing drops for room:** the bar stays one line and scrolls sideways under a finger, fading
   at an edge where there's more; a tap, not a drag, opens the session sheet. TalkBack reads one
   sentence of what it shows, then the queue. This replaces the strip's give-way order (FitRow).
6. Nothing else new on the chat screen.

## Agents with no transcript, and "Mac" on a server (2026-10-31)

The owner tapped a helper agent working in a `claude agents` job's chat ("claude", CLAUDE AGENT,
one tool call) and got "That agent's transcript isn't on the Mac." on a Linux server.

1. **Claude Code's own helpers are not agents of the session.** Its forks of the main conversation
   (memory upkeep, summaries, prompt suggestions) send subagent hooks under an id that names no
   file, with no meta file; in a `claude agents` job they carry its main agent's type, `claude`. A
   hook from an agent the daemon doesn't know counts only once its meta file is found, so these get
   no card (docs/PROTOCOL.md, "Agents and workflows").
2. **An agent's file is looked for further down** its session's own subagents folder when it isn't
   flat or in a workflow's folder (up to 6 folders down, no links), and never outside it.
3. **Starting, not an error.** An agent just started (under 2 minutes) whose file isn't written yet
   answers `starting`; its screen says "Starting…" and asks again every 1.5 s.
4. **The daemon's messages name this computer**: "the server" / "this server" on Linux, "the Mac"
   on a Mac (`MACHINE`, platform.ts). Messages about a Mac-only feature (its voice, macOS' privacy
   question, opening a window, the Mac app's Setup) keep "Mac".
5. On the session chat screen, only that: those helpers' cards no longer show. Nothing new there.

## Searching Home's sessions (2026-10-31)

The owner's pick from IDEAS (HM8): History's search over Home's live sessions too, without
cluttering Home.

1. **A lens in Home's top bar**, before New session (Sessions tab only). A tap opens a search field
   under the bar, over the top of the list, with the keyboard up; Back or its cross closes it and
   clears the words, as does leaving the Sessions tab. Nothing else on Home changes while it's
   closed.
2. **What it searches:** each card's title, project, its folder's own name (when it isn't the
   project's), last line and short hex id (`c7`), any case, every word of the query, History's
   matching (`data/Fuzzy.kt`: scattered letters only when nothing has the words as words). From 3
   characters, once typing has paused 300 ms, the server's full-text search (HI1) is asked too and
   its hits on Home's sessions are merged in, each session once; a hit's card shows the snippet
   under its title, the words in the accent, as History's do. One code path for both
   (`data/HomeSearch.kt` on `HistorySearch`, `rememberFullText` and `HitSnippet` in History.kt).
3. **The groups hold the matches:** Needs input, Working and Completed keep their order (A to Z, and
   newest first for Completed), their counts are the matches, a group with none hides. While words
   are typed the cards above the sessions (account, providers, questions, the usage cards…) step
   aside until the search closes; the offline card stays. Nothing found: a quiet "No sessions
   match".
4. **No daemon change.** `GET /v1/history/search` already searches every session the snapshot lists,
   Home's and History's alike; History leaves Home's hits out on the phone by merging only into its
   ended sessions, and Home merges only into its own.
5. Nothing new on a session's chat screen.

## Card swipes in Settings (2026-10-31)

The owner wanted to set what swiping right or left does on a Home card, not only the one swipe left
decided above ("Home cards: what it's doing, for how long, and swipes", 3).

1. **Settings → Card swipes**, after Display: **Swipe left** and **Swipe right**, each one of
   **Smart**, **Open**, **Stop**, **Delete**, **Rename**, **Restart**, **Mark read**, **Nothing**.
   Smart is the swipe as decided before (Needs input opens; a Completed job stops while its process
   runs, else is deleted; Working and Completed Codex cards don't). **Defaults: left Smart, right
   Nothing**, so nothing changes until he picks.
2. **A pick that doesn't fit a card leaves that way without a swipe**, never something else in its
   place. What fits is the long-press menu's own `MenuOffer`, so a swipe never offers what the menu
   doesn't: Stop for a job or a Codex session while it works or waits (a job idling with its process
   too); Delete for a job or a Codex session; Rename for any; Restart for a job on an older Claude
   Code only; Open for any; Mark read only on a card showing the unread dot.
3. **Codex goes through its own routes** (above, "Rename, Stop and Delete for Codex sessions"):
   Delete picked on purpose deletes a Codex session too, Codex's for good, asked first. Smart still
   keeps a Completed Codex card from swiping to Delete (that decision's 4 stands for Smart).
4. **Confirms as in the menu:** Delete always asks first, Restart asks first, Stop goes at once;
   Rename opens the rename sheet. Mark read marks the reply read on this phone (data/Unread.kt).
5. **Right looks like left, mirrored:** the word in the action's colour under the card at the end it
   uncovers, the haptic tick at 38%, the card springing back. A way with no swipe gives 16dp. Both
   ways on one card don't fight the list's scroll, the long-press menu or Allow and Deny (sideways only
   past the touch slop). TalkBack lists each way's action on the card.
6. **Kept on the phone** (`ui/home/CardSwipePref.kt`, the app's `hypernova_ui` preferences), applied
   to Home at once. Nothing on the session's chat screen.

## Why it asked (2026-10-31)

IDEAS PA7: say what made Claude Code ask, the case the owner hits being an ask rule over auto mode
("Ask rule Bash(python:*) overrides auto mode for this command").

1. **Claude Code's words first, Hypernova's reading second, and said which.** Its terminal dialog
   prints the reason (an ask rule, a permission rule, a hook, a path outside the working folders, a
   safety check); the SDK hands it to a session the daemon runs; the PermissionRequest hook carries
   none (checked in Claude Code 2.1.316), only a suggestion to add a folder when the call reaches
   outside the project's. Otherwise the daemon reads the rules in effect itself: a matching ask
   rule, a file outside the folders, an allow rule that matches anyway (one of Claude Code's own
   checks, most likely), plan mode, or no allow rule. That reading ends "· Hypernova's reading" on
   the phone: it is simplified and may differ from Claude Code's (docs/PROTOCOL.md, "Why it asked").
2. **One line, the rule in mono**, in two places off the chat screen:
   - **The Always sheet**, under the command: when he is about to save an allow rule, an ask rule
     behind the ask matters most (an allow rule doesn't beat it: the next one asks again).
   - **The session sheet's Permissions tab**, next to the rules in effect it comes from: under each
     command of "Asks waiting" when two or more wait, else a small "Why it asked" part for the one
     waiting ask and for Claude Code's own dialog in the terminal (whose card has no Always sheet).
     Nothing shows when nothing waits.
3. **Nothing new on the chat screen or Home.** The approval card, the terminal dialog's card (which
   already shows the dialog's own lines) and the Home card are as they were.
4. **Rules only leave the daemon**, as for the rules in effect: a rule string, never a settings
   file's other keys. A deny rule isn't looked for (one that matched would have refused the call).

## The widget: Needs you (2026-10-31)

The owner: remove the small widget, take the images off the big one and make it a clean list of
what needs him on all his computers, with the details and Deny / Allow. Replaces "Home-screen
widgets (2026-10-15)" above.

1. **One widget.** The small sun widget is gone (placed ones disappear, as Android does when a
   widget's provider goes). The large one stays where it's placed (same provider, `SessionsWidget`)
   and is now **Needs you**.
2. **No pictures.** No sun, planets, usage meters, agents' readouts, nebula or grain: text, the card,
   the command well and the pills, in the app's colours (DESIGN.md, "Widgets"). Usage stays in the
   app and the permanent notification.
3. **Every paired computer, grouped by computer.** The one on screen and the ones watched in the
   background, in the switcher's order; each with something waiting has its name and count, a
   computer with nothing waiting is left out, and with nothing anywhere the list says "Nothing needs
   you". One row per session that needs you: its name and what it waits on (the tool and its command
   or summary; the question; "Plan to review" and the plan's first line; Claude Code's permission
   dialog in its terminal by its title and command; else "Waiting for you"), its oldest approval
   first with "+2 more" for the rest. Oldest wait first. The list scrolls; the widget resizes freely.
4. **Deny and Allow by the notifications' rule.** Deny on every tool request and terminal dialog;
   Allow only beside the whole command, exactly as it will run (`WidgetModel.answerable`, which the
   notifications and Home cards also use); otherwise Open. The answer goes to that row's computer
   (`WidgetSync.answer` with its server; the terminal dialog through the menu route, as a Home card).
   Nothing new to unlock on the home screen: the phone is unlocked there. On a locked phone (a
   lock-screen widget) the buttons answer nothing and open the session after unlocking, as a
   notification's buttons need unlocking first. "Always" stays in the app, with its fingerprint.
5. **A tap opens the session on its computer** (switching to it, `MainActivity.EXTRA_SERVER`);
   questions and plans are answered there. A computer's name opens the app on it.
6. **Offline or not heard from yet: open only.** An offline computer's rows, and a freshly started
   process's kept rows (until that computer's snapshot arrives), are dimmed and only open.
7. **Battery as before:** it redraws when what needs you changes on any computer, at most every
   1.5s, never while the screen is off, with no timer or update period.
8. **No daemon change**, nothing new on the chat screen.

## All servers on Home (2026-10-31)

The owner: "add an option to select All, and show me all servers combined into one view. In this
combined view, if I open a new session, it asks me which server I want." His choice: Home only;
History, Automations, Settings and Statistics keep working on one server and say which. Then: "I
want to be able to select the session and see the details too." Later the same day History joined
in (9): "history can be enabled because you can just search both and that's fine; automations
that's fine to disable."

1. **"All servers" first in the switcher**, only while two or more are paired. Chosen, it is kept on
   the phone (`ServerBook.all`) and survives restarts; picking one computer, or pairing a new one,
   leaves it. With one computer left it no longer shows (the choice waits for a second).
2. **Home's Sessions tab holds every computer's sessions** in its three groups and sorts (A to Z,
   newest first for Completed; the same name and id on two computers by the computer's name), the
   counts all of them. Each card names its computer in its meta line, after the project; nothing
   else on the card changes. Home search filters each computer's and asks each for its full-text
   hits, each merged only into its own (`ui/home/AllHome.kt`).
3. **Everything a card does goes to its own computer:** Allow and Deny, the long-press menu, both
   swipes, its unread mark. **Opening one** makes that computer the one on screen for the
   session's screens (chat, session sheet with its tabs, terminal, agents, files), exactly as with
   one computer, without becoming the one picked; **Back to Home** is "All servers" again and the
   one picked is back on screen (`Core.visit`, `Core.backToPicked`). A notification or widget tap
   while All is chosen opens its session the same way and All stays; their other taps switch, as before.
4. **The cards above the sessions are the one picked last's** (account,
   providers, prompts for gone sessions, outdated jobs, automation questions, the offline card),
   each saying its name: per-computer cards for everything would crowd Home, and a session another
   computer's limit or sign-out stops already says so on its own card (its PAUSED or SIGNED OUT chip).
5. **An offline computer's sessions stay, dimmed**, its tag saying `offline`, and only open (no
   Allow, Deny, swipes or menu), as the widget's rows do: what was running there is still worth
   seeing, and an answer couldn't reach it. "Offline" is any link but connected or reconnecting.
6. **New session asks which computer first**: every paired one with its link, only a linked one
   can be picked; then the New session sheet for it, its folders, models and providers. A
   launcher shortcut or Share keeps to the one picked.
7. **The chip says "All"** on the Sessions and History tabs, its moon the worst of their links;
   Automations and Settings show the one picked last, the chip naming it; Statistics' subtitle
   names it. Switching tabs doesn't leave All. (Settings no longer: "All servers in Settings" below.)
8. **Unchanged:** notifications, the widget, the chat screen; no daemon change.
9. **History works in All servers; Automations doesn't.** History lists every computer's ended
   sessions in one list, most recent first under its days, each row naming its computer in its
   meta line (an automation's row after its name). Merged by time, not grouped by computer: the
   days are History's own groups, and a computer's group would split each day in two. The search
   and the project chips cover them all; each computer is asked for the words inside its own
   conversations and its hits are merged only into its own; "Searching inside conversations…"
   while any of them looks, "Partial results" when any ran out of time. An automation's runs fold
   on each computer on its own (the same automation id on two is two rows). Opening a row, or an
   automation's runs, makes that computer the one on screen, as a Home card does (`Core.visit`);
   Back is All again. An offline computer's rows stay, dimmed and tagged `offline`, and still open;
   a line under History's seam names the computers that are offline: their sessions are as last
   seen and the words inside them aren't searched (`ui/home/AllHistory.kt`). One computer's
   History is as it was. **Automations stays one server's**: its tab says "Automations are one
   server's: pick a server to see them." and stays where it is, and choosing All from it brings
   Sessions.
10. **The usage cards are every computer's** (owner, the same day: "a card for each Claude Code or
    Codex instance for all the servers at the bottom"; `AllUsage` in `ui/home/UsageCards.kt`): under
    the sessions, each computer's own cards by the one-computer rule (Claude's where Claude Code is
    installed, Codex's while in use, "Claude usage" and "Codex usage" only where both show there),
    in the order paired, each with the computer's name after its title. Two computers on the same
    Claude account each keep their card (the phone can't tell one account from two, and the owner
    asked for one per instance). An offline computer's stay, dimmed, `z2 offline`, as its sessions'
    cards do (the last numbers it sent, still worth seeing; dropping them would make cards come and
    go with the link). Statistics stays the one picked last's; with one computer nothing changes.

### All servers in Settings (2026-10-31)

The owner: "it's weird that certain things are being defaulted back to Z2 when you're in all view,
this shouldn't happen ... disable whatever settings don't make sense or have them combined if that
makes sense, such as server settings, you would just see all of them one by one." Before, All
servers only hid the server's parts and left the rest on the one picked. Now nothing in Settings
acts on the one picked while All servers is on (`ui/settings/SettingsPlan.kt` decides what shows):

1. **Each server's own settings, one block per server**, in the order they were paired, each under
   its name and link (a title-sized heading with the connection chip): its card (Stay awake and the
   battery, the route and address, its daemon, Claude Code and Codex versions, Retry while it can't
   be reached, **Forget**, which forgets that server, not the one picked), **Agents & providers**
   (opening it makes that server the one on screen for those screens, as a session's does, until
   Home is back), **Read aloud** (its voices and speed; each Test its own), **Files** (Browse outside
   projects) and the testing checklist when unlocked. Each block reads and writes its own server
   only. "Turn on in Settings" from a session's files scrolls to that session's server's Files.
2. **A server that isn't connected shows its card alone**, saying "Its settings show here once it's
   connected." with its problem and Retry, and Forget: no controls from its last snapshot.
3. **Then "This phone"**: Voice recognition, New sessions, Display, Card swipes, Session top bar,
   Typing, Notifications (Stay connected in the background is one setting for every server's
   link), the connection log (once: it is the phone's, every server's lines in it) and About.
4. **About's update offers the newest app any connected server has** (the one picked first on a
   tie), downloaded from the server that offers it (`AppUpdate.server`; before, the download always
   went to the one on screen). The app is the phone's, so one row, whoever offers it; the versions
   of each server's software are in its own card.
5. **Unchanged:** Settings with one server picked; nothing on the session chat screen; the update
   banner and the update notification still follow the one on screen.

## Servers update themselves (2026-10-31; on by default since 2026-11-01)

The owner: "for the server auto updating allow it to be a setting ... default no, and then yeah the
server updates itself and then pushes to the client that needs update too." Decided
(`daemon/src/server-update.ts`, docs/PROTOCOL.md, "The server's own update"):

1. **A setting, on by default**: `autoUpdate` (unset: on; owner, 2026-11-01: "change auto update to true by default"; it was off by default the day before). On the phone it is one switch in the
   server's own section of Settings, **Update this server automatically**, with a line saying what
   it does, or why this install can't, what it's doing and the last update it installed; on the
   server `hypernova auto-update on|off`, or `POST /local/settings {autoUpdate}`.
2. **The same release check.** The look the daemon already makes for the app's releases (30 s after
   start, every 6 hours; it now runs for the server too while `releaseCheck` is off) hands the
   release on after the APK's part, so the phones are offered the new app before the server
   restarts. Only a release newer than the running daemon's version installs: never a downgrade,
   never the same version again, never a tag that isn't `vX.Y.Z`.
3. **Checked before anything is touched.** The release's note now carries the server package's
   SHA-256 (`serverSha256`, the release workflow); a note without it installs nothing (logged).
   The package is downloaded, its SHA-256 checked, unpacked into a new folder, and its daemon's
   version checked against the tag. A failure at any step leaves the server as it was and waits
   before trying that release again: 1 hour, then 6, then 24, then not until the setting is turned
   off and on, or a newer release comes.
4. **Never in the middle of work.** It waits for no session working or starting and no approval
   waiting, 2 minutes on end; after 24 hours of waiting it goes ahead anyway (the shared terminals
   outlive the restart; an approval still waiting then is lost the way any restart loses one).
5. **Installed the way `install-linux.sh --from` installs**, by the package's own script, in a
   transient systemd unit of its own (`systemd-run --user`, `hypernova-update`), not as a child of
   the daemon: the service's restart, which ends the daemon, can't cut the install off. Setup swaps
   the new daemon in only once its terminal module works and restarts the service; a copy of the
   old daemon is kept until the new one answers, and put back (the service restarted on it) when
   it doesn't. Phones, settings and keys stay where they are. Nothing new is installed with it
   (`--no-claude`; no `sudo` without a terminal).
6. **Only an install the installer manages**: a Linux install in `<support>/app` that `hypernova
   setup` put there from a release's package (its `install.json`; setup writes `checkout` for a
   git checkout, so `hnctl update` and `scripts/install-linux.sh` in a clone are never replaced),
   run by its systemd user service. A checkout, a test copy, a container, an install from before
   the marker or one without the service says "not available here" and why.
7. **The phones learn of it**: the new daemon, at start, sees the update it was waiting for and
   logs it; the snapshot's `mac.update.last` says from and to what, and the phone's connection log
   has a line when the server's version changed between two snapshots. No banner or toast.

## MCP and tool runs fold like Claude Code (2026-11-01)

The owner chose option "2+": fold MCP calls and the run Claude is working in, the way Claude Code
2.1.316's own screen does ("Calling home-assistant 3 times…", then "Called home-assistant 3 times"),
in the app's own words. Before, the run Claude was in stayed open one row per call, runs of one or
two calls never folded (Claude often writes a line between MCP calls, so each was its own row), and
an MCP row said its name twice: `HA_GET_STATE  home-assistant · ha_get_state`
(`ui/session/ToolRuns.kt`, `ToolRunRow.kt`, `data/Items.kt`, `daemon/src/normalize.ts`).

1. **The run Claude is working in folds too.** Its line says **Using N tools**, keeps the planet,
   and shows the latest call under it ("ha_get_state · light.kitchen", "Read · parser.ts"); once
   done it says **Used N tools** with no hint. A tap opens it, and a run the owner opened or shut
   stays that way (`runOpen`); nothing opens by itself any more.
2. **Diffs and failed calls are never folded away**: they stand as their own rows and the run
   folds around them, before and after (a failed terminal, exit code not 0, counts as failed).
   Chosen over keeping such a run open: an open run is the long list (a) set out to fold, one edit
   would open everything around it, and a fold that opened and shut as edits came would jump. And
   it holds for finished runs too, so a diff seen while Claude worked doesn't vanish into the fold
   when the turn ends; Claude Code shows edits and errors on their own lines as well. The fold
   line's "N failed" went with it (a fold no longer holds a failure).
3. **MCP calls fold from one call**: a stretch of work made only of MCP calls, or MCP calls and
   lookups (Read, Grep, Glob, LS, ToolSearch, WebSearch, WebFetch), folds however short; one with
   any other built-in (Bash, Write…) or none of MCP keeps `MIN = 3`. Text ends a run, as in
   Claude Code.
4. **Named by server, with counts**: "Used 3 tools  home-assistant ×3", or "home-assistant ×2 ·
   Read" mixed. The server is the part of `mcp__<server>__<tool>` before the next `__`, as written.
5. **An MCP call says what it was called with** (daemon, docs/PROTOCOL.md "Reading transcripts"):
   the event's summary is its first argument among `entity_id`, `query`, `url`, `path`,
   `file_path`, `name`, `id`, `title`, else its first short one-line string; never a key named like
   a secret (token, password, secret, key, auth, cookie, credential, bearer, signature, private),
   never a blob or a `data:` URL; masked as titles are, cut to 80 characters; the server's name
   when nothing qualifies. The row reads `HA_GET_STATE  light.kitchen`. An older daemon's
   "server · tool" summary, or the server alone, is left off the row rather than repeating the
   name; an older phone shows the new summary as it shows any (free text). Approvals' summaries
   and agents' activity lines are unchanged.
6. **Unchanged:** nothing new on the chat screen (the fold line and rows are the existing ones),
   approvals and the session's other cards.

## Home filters (2026-11-01)

The owner: "add ability on agents list page to filter… one that comes to mind will be useful is just
showing the ones that are working or need approval only… I could hide completed or whatever." His
choice: a filter button in Home's top bar and a small sheet (`ui/home/HomeFilter.kt`,
`HomeFilterSheet.kt`).

1. **A filter button beside the search**, Sessions tab only: the lens's circle with three lines,
   lit mint with a dot while a filter is on. It opens a glass sheet, the switcher's kind.
2. **The sheet:** "Active: Needs you + Working" in one tap (again: every state); **State**, Needs
   you, Working and Completed, each on or off, never all off; **Agent**, All, Claude or Codex,
   only while both agents are in use or a Codex session is on Home; **Project**, All or one of
   Home's projects, only with two or more; **Server**, All or one, only in All servers. The states
   are Home's groups by Home's own rule (`homeGroup`): starting and agents still running are
   Working, an error is Completed. Every tap applies at once; Clear and Done at the bottom.
3. **The chip over the list says what's shown**, Home's one filter row (the one a project from a
   shortcut used): "SHOWING: NEEDS YOU + WORKING", then the agent, project and server after " · ".
   Its cross shows everything. Sessions that need you which the filter hides are counted beside
   it in amber, "2 need you, hidden", and that count clears it too.
4. **Never an empty list without a word:** sessions are there and the filter leaves none: "No
   sessions match this filter" and Clear. With no sessions at all, Home's empty card as before.
5. **Kept on the phone** (SharedPreferences, `hypernova_ui`), not per server, across restarts. A
   server chosen in All servers waits, kept and not applied, while one server is shown, and comes
   back with All. A project or server that's gone narrows to nothing and says so (4).
6. **Search runs within the filter**: the filter comes first, then the words, each computer's own
   in All servers.
7. **The sky's project filter stays apart** (`SkyFilter`, from a launcher shortcut or Share: it
   also picks the New session sheet's project, and Back clears it; it isn't kept). Both apply
   together; the one chip says both, the sky's last, and its Clear clears both.
8. **The top bar still fits:** the round buttons are 40dp wide (48dp tall; a touch still reaches
   48dp), so New session keeps its words on a 392dp phone at the usual font. Short of room (a
   large font, a narrower phone) New session keeps only its plus, so the chip's name ("All",
   "z2") is never squeezed out.
9. **Unchanged:** the widget, notifications, the session chat screen, History; no daemon change.

## xterm.js add-ons (2026-11-01)

The owner approved taking the phone's live terminal from xterm.js 6 with the fit add-on alone to xterm.js
6 with its useful add-ons, for now, over making the Ghostty trial the default (docs/LATER.md, L101).
Exact versions, the npm builds unmodified, in `assets/term/`.

1. **Widths: `@xterm/addon-unicode-graphemes` 0.4.0, on the page and in the holder.** Claude Code
   2.1.316 lays its screen out with `Bun.stringWidth`: one wide character for a whole emoji sequence,
   ambiguous characters narrow. xterm's default made 🚀 1 column, `addon-unicode11` makes 👨‍👩‍👧 6
   and ❤️ 1, and this add-on matched `string-width` 8.3.0 (standing in for Bun, which can't be run
   here) on 32 of 33 samples; the one miss is the Devanagari conjunct क्ष (2, not 1). Upstream still
   calls it experimental, so it's pinned. It costs nothing measurable (a 4 MiB burst: 542 ms, 541 ms
   without). See PROTOCOL, "Shared terminals", **Widths**, for how older holders keep lining up.
2. **Links: `@xterm/addon-web-links` 0.12.0, and OSC 8.** A tap reaches `HN.link`, which goes where a
   link in the chat goes (`LinkHandler`): "Open this link?" and then the browser, a dev server on the
   computer in its preview, anything else nowhere. Never in the terminal's WebView; http and https
   only, checked on the page and again in the app (`termLink`). xterm.js 6's gesture code takes taps,
   so no mouse events come: the page plays them to xterm's link finder alone, on the screen element,
   without bubbling, so a tap selects nothing and claude sees no click.
3. **Search: `@xterm/addon-search` 0.16.0, without a way to start it yet.** The page has `hn.find`,
   `findNext`, `findPrevious` and `findClear`, newest first. The terminal screen has no menu to put it
   in, so where it goes is the owner's choice (docs/LATER.md, L99). It covers the history on the page
   (a snapshot's 2000 lines and what came since, 5000 at most); in Claude Code's fullscreen view, only
   what's on the screen, since it keeps its own history.
4. **GPU drawing: left out for now** (docs/LATER.md, L100). `@xterm/addon-webgl` 0.19.0 against the
   page, 5 runs each, headless Chromium on this server's RTX 3080: 60 fps either way; a steady
   full-screen redraw 142 ms of main-thread work a second against 268, a finger scrolling 111 against
   157, but a 4 MiB burst 876 ms against 542, 247 KB more in the app. Nothing was slow to begin with,
   those numbers aren't a phone's, and the fallback only covers a lost context: a canvas that draws
   nothing goes unnoticed (the page's opaque viewport background hid it completely until overridden).
   Its one visible gain is box-drawing lines without gaps.
5. **Checked by** `daemon/test/term-widths.test.ts`, including a real holder's snapshot, and
   `scripts/term-page-check.mjs` by hand in a headless Chromium (docs/DEV-SETUP.md). Not yet on a phone.
