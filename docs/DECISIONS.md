<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->
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
  - **Rewind copies, and the copy replaces the original on every screen** (changed 2026-11-01, see
    "A rewind replaces the session"; it used to leave both, the copy titled "<title> (rewound)"). The
    conversation up to just before the message is copied into a new session with everything the
    card shows, and the message (with its files) comes back into the box. The original is stopped
    and shows nowhere; its transcript stays on disk, hidden. Files Claude changed stay as they are;
    the confirm sheet says so. No rewind on the first message, one not yet on the Mac, or Claude
    Code's own commands and "!" lines. Not Claude Code's /rewind in the terminal: its picker lists
    messages by cut-off text, so the daemon couldn't pick the right one, and it can restore code,
    which is destructive. Rewinding the code too is LATER L46.
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
- **Dictation keeps listening until you end it** (owner, 2026-11-01: "when I use the voice in the app it'll stop recording while I'm talking, but the one in the keyboard won't stop"). Android's recognizer ends by itself at a pause; that no longer ends the dictation, held or hands-free. It ends at letting go, Submit, Done, Cancel, Back, leaving the app or the 2-minute cap. There is no separate silence stop: a long quiet listens on up to the cap, which is what protects the battery. From Android 13 it asks for one segmented session (`EXTRA_SEGMENTED_SESSION`), with no gap; a recognizer that doesn't honour that gets a new session after each pause, which can lose the speech in between and may beep on some phones (a beep goes against "No sounds"; not muted, because muting a stream would also cut the owner's music and could stay muted if the app dies). A real error still ends it with the words kept, and so do five sessions in a row that fail at once. Each new utterance reads on from the last: a small first letter, unless the last ended a sentence or it's "I" or an acronym, so a name opening an utterance loses its capital.
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
    at their defaults on purpose. PROTOCOL.md had the details until the glasses were removed.
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
  mood. Nothing in the physics forecasts the limit; the pace arc and the notch do. (Those two went
  on 2026-11-01: "No estimate of when usage runs out".)
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

1. **Mermaid is bundled** (`third_party/mermaid`, `assets/mermaid`; 11.17.2 then, about 1.2 MB more APK; since 2026-11-02, "Mermaid 12").
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
   downloads the APK, checks the SHA-256, and puts it in `<support>/android/` with the note
   last. Never fails the daemon: offline or an error is a line in the log.
2. **A local build wins when it's newer.** An APK an admin put there, or a dev build,
   with a higher versionCode is never replaced by an older or equal release.
3. **On by default**, the setting `releaseCheck` turns it off (`POST /local/settings` or
   settings.json). docs/SECURITY-MODEL.md says what GitHub sees and how the APK is checked.
4. **Connected phones hear of it**: `{t:"app", app}` when the offered APK changes (a release, an admin's
   APK, removed), so no daemon restart or reconnect is needed. Older apps ignore it.
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
   names it. Switching tabs doesn't leave All. (Settings no longer: "All servers in Settings" below.
   Statistics no longer: "Combined Statistics in All servers" below.)
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
    go with the link). Statistics stays the one picked last's (no longer: "Combined Statistics in All
    servers" below); with one computer nothing changes.

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
4. **At once, whatever sessions are doing** (see Servers update without waiting; until 2026-11-01 it
   waited for a quiet moment).
5. **Installed the way `install-linux.sh --from` installs**, by the package's own script, in a
   transient systemd unit of its own (`systemd-run --user`, `hypernova-update`), not as a child of
   the daemon: the service's restart, which ends the daemon, can't cut the install off. Setup swaps
   the new daemon in only once its terminal module works and restarts the service; a copy of the
   old daemon is kept until the new one answers, and put back (the service restarted on it) when
   it doesn't. Phones, settings and keys stay where they are. Nothing new is installed with it
   (`--no-claude`; no `sudo` without a terminal).
6. **Only an install the installer manages**: a Linux install in `<support>/app` that `hypernova
   setup` put there from a release's package (its `install.json`; setup writes `checkout` for a
   git checkout, so `scripts/install-linux.sh` in a clone is never replaced),
   run by its systemd user service. A checkout, a test copy, a container, an install from before
   the marker or one without the service says "not available here" and why.
7. **The phones learn of it**: the new daemon, at start, sees the update it was waiting for and
   logs it; the snapshot's `mac.update.last` says from and to what, and the phone's connection log
   has a line when the server's version changed between two snapshots. No banner or toast, except for
   an update that failed (Servers update without waiting).

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
   "z2") is never squeezed out. (Since the sort button, below, it says "New" first.)
9. **Unchanged:** the widget, notifications, the session chat screen, History; no daemon change.

## Sorting Home's sessions (2026-11-01)

The owner: "also have another icon for sorting with the default the sort by session name"
(`ui/home/HomeSort.kt`, `HomeSortSheet.kt`).

1. **A sort button beside the filter**, Sessions tab only, the same round glass button: an arrow
   down beside an arrow up. Lit mint with a dot while the sort isn't by name, TalkBack saying which
   ("Sort sessions, Recent activity, newest first").
2. **Its sheet** is the switcher's glass and rows, one per sort with the one in use ticked; a tap
   sorts and closes. A glass sheet rather than a small dropdown: the app has no popup menu, and the
   sheet reads and works as the switcher and the filter do. Under the rows: "Within each group:
   Needs input, Working, Completed."
3. **The sorts:** **Name, A to Z** (the default, the owner's words); **Recent activity, newest
   first** (the time a card says); **Project, A to Z, then name**; **Server, then name** (All
   servers only; kept but not applied with one server, as the filter's server is). Every one ends
   with the name, then the id (then the server's name), so equal keys never swap. No other sort
   was added: none was clearly useful.
4. **Within Home's groups:** Needs input, Working and Completed keep their order; the sort orders
   each. With All servers, across every computer's cards together; while searching, the matches.
5. **This changes Completed's order:** it sorted newest first (owner, 2026-10-30; "Home cards"
   and "Searching Home's sessions" above); by default it is now A to Z like the other groups.
   Recent gives newest first back, in every group (cards then move as they work, which Name
   avoids: the owner's "keep swapping back and forth").
6. **Kept on the phone** (SharedPreferences, `hypernova_ui`, `home_sort`), not per server.
7. **Said by the lit button only**, not on the filter chip: the chip says what's shown and its
   cross clears the filter, and a sort there would ask whether the cross resets it too.
8. **The top bar with four round buttons:** on the tests' 392dp phone at the usual font there is
   no room for "New session" beside the filter, the sort and the lens, so New session steps down
   to "+ New" (TalkBack: "New session"), and to its plus alone when even that doesn't fit; the
   chip's name ("z2", "All") is never squeezed. At 420dp (the emulator DEV-SETUP sizes as the
   Nothing Phone 3) All servers keeps "New session" whole and one server says "New". If the owner
   wants the words back everywhere: move Sort into the filter sheet (one button fewer).
9. **Unchanged:** the widget, notifications, History, the chat screen; no daemon change.

## Combined Statistics in All servers (2026-11-01)

The owner: "when in all view the stats view should be combined of all servers too." Before,
Statistics showed the one picked last's, its subtitle naming it. Now, with All servers chosen, it adds
up every server's (`data/AllStats.kt`, `ui/stats/StatsScreen.kt`). No daemon change: the phone asks
each server's `GET /v1/stats` and does the adding.

1. **Every total is summed**: hours, by the clock, agent work, tokens by kind, sessions, prompts, tool
   calls, files, agents, approvals, limits, Claude's and Codex's parts. The header says
   "All servers · 25 – 31 Oct".
2. **The last 30 days are summed date by date**, each date as its server's calendar has it. "N days
   with work", the busiest day and the streak come from the summed days: two servers taking turns
   make one streak. For the All range, a server's own busiest day from before the last 30 days can
   still win, as its own number (others' days that far back aren't sent), and a streak that fills the
   chart is at least any server's own.
3. **Projects of the same name are one project, summed**, not kept apart under server names. It is
   the same work to the owner, a checkout on two computers, and each project's planet is seeded from
   its name, so two rows would be two identical planets. Eight at most, as on one server. Tools merge
   the same way, eight then "Other".
4. **The longest session is any server's longest**, as it is one session; it doesn't name its server.
5. **No Servers card** (owner, the same day: "not clear what this servers section tells me that's
   different from the first section, remove it."). There was one under the projects: each server that
   answered with its sessions, tokens and hours. The totals are the point here; who was left out stays
   the amber line, and the footer still names the servers these came from.
6. **Each server is asked for the same range, at once**, and the numbers change once, when all have
   answered, so they don't climb as answers come in. A half-minute-old answer is used as it is, as on
   one server. With every linked server's answer kept, they show at once while fresh ones come.
7. **A server that doesn't answer is left out and named**, in amber over the numbers: "k1 isn't
   answering: its numbers aren't in these." That covers a server not linked (not asked at all, so
   it doesn't hold the rest up for the connection's timeout) and one whose answer failed; an older
   answer of its own isn't added in, since the line says its numbers aren't there. One coming back
   is asked at once. None answering: "Couldn't load statistics" with Try again, or the last numbers
   with "Couldn't refresh", as on one server.
8. **Known limits of adding up on the phone:** "by the clock" counts two servers busy at the same
   moment twice (the answers carry no times to overlap); the same file on two servers counts as two;
   two servers on one Claude account can each count the same limit (the phone can't tell accounts
   apart, as with the usage cards); a project outside a server's own eight isn't in that server's
   answer, so a merged project can read a little low.
9. **Unchanged:** Statistics with one server, word for word and picture for picture; the session chat
   screen.

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

## An agent waiting on its background command is still at work (2026-11-01)

The owner's choice. A subagent that starts a background command and waits for it ends its turn, so
SubagentStop fired, the daemon took it as done, and the app said "No agents at work" while the
terminal still listed it (seen live on 2026-11-01).

1. **It stays `running`, its activity saying why** ("Waiting for its background command"). No new
   status, so no break in the protocol: an older app shows a running agent with that line.
2. **Its own file decides, not a sentence.** Claude Code's transcripts record what started
   (`backgroundTaskId`, Monitor's `taskId`, `async_launched`) and what ended (a notification with a
   status, TaskStop). The hook's `background_tasks` says which shells really still run. Only the
   main transcript's "is waiting on its own background work" has no field behind it (`completed`
   either way), so those words are a fallback, not the rule. Checked against the 86 agents of one
   real session, 51 of which started background work: only the agent then running and 3 that had handed
   back a report were left with any outstanding.
3. **A report handed back ends it, whatever it left running.** One real agent reported and left a
   shell going for good; Claude Code still lists it, and it must not stay "at work".
4. **The cap is 2 hours with no write to its file**, then `done` with a report in hand, `stopped`
   without. In this server's transcripts, 148 times an agent went on after ending a turn (for any
   reason, a message from the main agent too, so an upper bound on the waits): the median after 18
   seconds, 95% within 17 minutes, the longest 47 and 123 minutes. 2 hours matches what a silent tool
   call gets, a wrong end corrects itself at its next hook, and too long a cap costs a wrong count
   and a held update. A Monitor running longer extends it to its end, a persistent one to 12 hours
   at most (the 12 is a judgement: the Monitors seen ran 30 minutes at most, none persistent).
5. **The server's own update waited for these agents too** until the same day: it no longer waits for
   anything (Servers update without waiting).

How it's detected and ended: docs/PROTOCOL.md, "Agents and workflows", **Waiting on its own background
work**.

## Servers update without waiting (2026-11-01)

The owner: "the daemon can just update without having to wait, because it's not actually killing the
sessions, they live in Claude Code; it would just be temporarily killing the back end server of this
application, that's fine because it happens fairly quickly, so let's not wait on agents." Before, a
busy session could hold a downloaded update for up to 24 hours (z2 held 0.13.1 for almost 3 hours on
2026-10-31). This replaces "Never in the middle of work" (Servers update
themselves, point 4), and with it the idea of a "ready, waiting" notification and an Update now
button, which the owner dropped in the same breath.

1. **No gate at all.** Once the package's checks pass (a managed install, newer only, the SHA-256,
   the package's own version) it installs. Retries and the rollback are as they were. `state:
   "waiting"` is gone from the wire; an app that doesn't know a state reads it as idle.
2. **Nothing is lost for a waiting approval, so nothing waits for one.** Checked rather than assumed,
   2026-11-01, with Claude Code 2.1.316 and a scratch test daemon (scratch `CLAUDE_CONFIG_DIR` and `HOME`, a
   scripted model on loopback), SIGTERM while its hook held the ask:
   - The ask was never denied and the call never ran: the model got no tool result, and the dialog was on
     the terminal's screen throughout (Claude Code draws it while the hook is held).
   - A session in a holder: 3 s after the restart the phone's snapshot has no approval, and
     `menu.permission` with the command and the dialog's options. Answering through
     `POST /v1/sessions/{id}/menu` ran the command. A question (AskUserQuestion) comes back the same way.
   - A `claude agents` job (how the owner runs): the ask waits in its dialog too, but it has no terminal to
     read, so **the phone would have shown no card**, and its state file reads "PermissionRequest hook
     failed: socket hang up · approve Bash: …", which the daemon took for a question in words. Both
     fixed: at start the daemon attaches a job Claude Code's registry says is waiting with no ask held
     (`attachWaitingJobs`, 4 s and 20 s after start), the same way as an ask handed back after 10 minutes,
     and reads what's after the hook's failure. Answered from the phone route, it ran.
   - The phone's own approval notification goes with the approval, so the app says the session is waiting
     once more (`askedAgain`), or `changed` does if it reconnected before the card was back.
   - Codex 0.164.0 (a scratch app-server, no account): the turn stays `waitingOnApproval` with no client,
     and `thread/resume` from a new connection gets the same `requestApproval` again; accepted, the turn
     goes on.
   - What is lost: an Always rule the phone's card would have offered with its editing sheet (the
     dialog's own "don't ask again" is still there), and a tap on the old card in the moment the daemon is
     down, which doesn't reach it (the app says so, and the dialog's card follows).
3. **Sessions keep working through the restart.** Holders run outside the service's process
   (`KillMode=process`). Hooks are Claude Code's own `http` hooks: one that can't connect fails at once
   and isn't blocking. A turn of 8 two-second commands with the daemon stopped from the third on for 25 s: gaps
   2.07 to 2.31 s between commands, against 2.07 to 2.10 s with it up. The turn finished with the daemon down;
   the terminal shows the failures ("Stop hook error: connect ECONNREFUSED"), and the restarted daemon
   read the whole turn from the transcript and had the session idle.
4. **How long it's down.** Everything long (the copy kept for going back, `npm ci` when the lockfile
   changed, the terminal module's check) is before the swap, with the old daemon serving. Then renames
   and a restart. z2's two real ones on 2026-11-01: SIGTERM to listening again in 0.47 s (03:33:28.795 to
   29.268) and 1.51 s (15:08:42.697 to 44.209). **The phone is slower than that**: its first try again goes at
   once, likely into that gap, and the next 27 to 30 s later (Retry, owner, 2026-10-31). Left as it is.
5. **A failed update is still news**, since nobody else would know: `failure` in the view, a banner on
   screen or a notification off it, once per server for a release's first failure and once when the
   server gives up on it, each opening that server's section of Settings. It goes when the update goes through
   or the setting is turned off. With auto-update off the daemon looks for nothing, so there is no news either.

## A rewind replaces the session (owner, 2026-11-01)

The owner rewound a session, saw two near-identical cards ("why do I see 2") and said it "shouldn't
make another session like this". First answer: the copy takes over and the original goes to History.
Then, the same day, on seeing that plan: "keep everything the same, like the new one completely
replaced the old", and "don't need to change title". That's the decision:

1. **The new one replaces the old on every screen.** One card, the same title exactly (a "(rewound)"
   an older version added stays as it is, no worse), with the later messages gone and the message
   back in the box. The phone lands in it.
2. **It takes on what the session shows**: title, folder and project, permission mode, model, effort,
   provider, created time, an automation's badge and rules, what a switch of agents carried over and
   where it came from, what was allowed "for this session", the cost so far. On the phone: read marks,
   the box's words and files, prompts waiting for the server, side questions. Last active: now, as a
   session just used (a Completed card it had read stays read).
3. **What can't carry over**: its running terminal or `claude agents` job (the copy gets a shared
   terminal with its next prompt, and isn't a job), its context percentage (the context is shorter
   now; it returns with the next reply), the "Agents" cards of agents it ran (their files are under
   the old id), and a block by a limit (it comes back if the limit still holds).
4. **The old one shows nowhere**: not Home, not History, not search, not after a restart. No note, no
   marker.
5. **Nothing is destroyed.** The old transcript stays on disk, untouched; `<support>/rewound.json`
   is all that hides it (Delete's `claude rm` wasn't used: it deletes the job for good, and can refuse). The daemon
   log names both ids and the transcript's path. By hand: drop its line from the file and restart
   the daemon (it's back in History), or `claude --resume <id>` in its folder (back as a running session).
   Still visible outside the app: Claude Code's own `/resume` list lists it beside the copy, and
   `claude agents` keeps a stopped job. Statistics read the transcripts on disk, so they still count it.
6. **Asks first if it's at work.** The confirm sheet says the session is still working (or waiting for
   an answer) and that rewinding stops it; for every rewind, that the conversation goes back and later
   messages are gone from the app, and that files Claude changed stay as they are.
7. **If it can't be stopped** (the terminal didn't close, `claude stop` failed, a terminal no holder
   has, 20 s passed): the rewind still stands, a toast says why, and the original stays on Home
   beside the copy, under the same title. Nothing is deleted either way.
8. **Whatever names the old id follows it.** `session.removed` carries `replacedBy`; a phone that was
   away hears it in the snapshot's `replaced`. A screen on it lands on the new one; its notification
   goes; a session route (a notification's Reply, a prompt kept offline) and an automation's saved
   target reach the new session. Delete doesn't follow: nothing should delete by an old name. Held
   prompts of the old session go with it: they came after the point it went back to.

Older versions. An older app gets all of this from the server, and nothing breaks: the card goes
(`session.removed`), a screen on it goes back, and its own sheet still says the session stays, which
is no longer true, with no working warning. A newer app with an older server keeps two cards, and its
toast says so. Why the copy comes first and files stay: as ever (LATER L46).

## YTD in Statistics, and when All starts (2026-11-01)

The owner: "also do ytd filter", then "also have All tell me when does it start."

1. **Today, Week, Month, YTD, All.** YTD runs from January 1 of the server's own year, by its own
   calendar as the others go (`range=ytd`), through now. The header says "since 1 Jan".
2. **The chart stays the last 30 days**, as under All; a year's view wasn't worth a second chart. In
   January YTD dims the days of last year, as Week dims those before its seven.
3. **All names the year it starts**: "since 24 Sep 2026", "All servers · since 24 Sep 2026", the
   earliest of the servers that answered.
4. **A server too old for a range says so, not an error.** It answers `400 bad_range`: in All servers
   an amber line, "k1 needs updating for this range.", over the others added up (apart from "isn't
   answering": it is answering, and updating is what fixes it); with no other answer, or on one
   server, the same line in a card, with no Try again. The screen asks again each minute anyway, so
   the numbers come by themselves once it's updated. A real failure elsewhere still gets its error.

## All and YTD reach back to the first use (2026-11-01)

The owner, on All's start date, chose to backfill from Claude Code's own summary. All started on 24 Sep
because Claude Code deletes transcripts after 30 days by default, and the daemon's first look kept only
the days still there. His first use was 8 January.

1. **`stats-cache.json` fills in the days before Hypernova's first** (`daemon/src/stats-cache.ts`,
   `docs/PROTOCOL.md`, "Older days"): sessions and tool calls. Read only. With neither the
   file nor a kept copy of it (7), All means as far back as Hypernova has counted.
2. **A day has one source.** Hypernova's first counted day and all after it are Hypernova's, even where
   it saw only part of the day (24, 25 and 28 Sep on z2, short 45 sessions and 3,624 tool calls of the
   file's). Picking the higher number per field would make every day past it the file's on some counts
   and Hypernova's on others.
3. **Messages aren't prompts.** The file's `messageCount` is 33 times the prompts on overlapping days,
   so the Prompts number starts with the hours.
4. **The header says the first use, and one line what starts later**: "since 8 Jan 2026", then "Hours,
   prompts, tokens and projects since 24 Sep 2026." Only where older days are in the answer: All and
   YTD; Today, Week and Month only for a day the file alone knows. In All servers the date is the
   earliest any server has those numbers from; a server without older days has its own start.
5. **Records stay in hours.** Busiest day, longest session, streak and days with work come from
   Hypernova's days only, since a day with no hours can't claim them, and the chart makes up no bars: a
   day only the file knows reads "no hours counted".
6. **Older tokens are left out.** The file's tokens run 2.2 times Hypernova's for the same days (z2,
   29 Sep to 16 Oct), while sessions and tool calls agree within 3% (1.00 and 0.97 times). Hypernova
   counts a message once however many lines log it; why the file's run higher isn't known here. So the
   daemon doesn't read them and doesn't send them, the total tokens are Hypernova's own, and the app
   ignores `backfill.tokens` from an older server. A first version added them; the owner took them out
   the same day.
7. **The older days are kept.** All would fall back to 24 Sep if Claude Code removed the file or changed
   its format, so each active day it has is copied into `<support>/stats.json` (`backfillDays`: date to
   sessions and tool calls, only counts), apart from `days`, which stay Hypernova's own exact counts.
   Each read of a valid file adds the days not kept yet, on every answer and on the daemon's own looks,
   so a server nobody opens keeps them too. A kept day is never lost and never changes, and a day
   Hypernova has counted itself still wins, decided per answer. No `backfillFirst`: the first day is
   the map's earliest key, since a day with only messages is kept as zeros.

## The switcher keeps the page (2026-11-01)

The owner: "when I switch servers in the dropdown the current page shouldn't be changed", "only
exception is the automations view default back to sessions list." Home's tab now lives above each
computer's own Home (`rememberHomeTab`): Sessions, History and Settings stay on screen through a
switch, to another server or to All servers. Automations goes back to Sessions. Each computer's list
still starts at the top.

## Which notifications (2026-11-01)

The owner: "ability to control notifications, what kind of notifications, or even notifications at all
that you would get, on a per server and all server basis." He chose the design; decided
(`data/NotificationPrefs.kt`, `ui/settings/NotificationControls.kt`):

1. **Kept on the phone, not the server.** Each phone chooses for itself, and it works while a server
   can't be reached.
2. **All servers: the defaults.** In Settings' Notifications card, a master **Notifications** switch and
   a switch per group, folded under one row ("Which ones", "All" or "5 of 7") so the tab stays
   scannable. Everything is on by default, as before.
   - **Needs your answer:** `Approval`, `Question`, `Plan`.
   - **Finished:** `Finished`. **Waiting for you:** `Waiting`. **Errors:** `Error`, a job that died too.
   - **Limits and account:** `Limit`, `Account`, and what stops the sessions on a Mac from outside:
     `Backpack` and `Power`, a new kind for its battery and sleeping soon (they were `Approval`, which
     would have put them under Needs your answer; their banner and words are unchanged). No "This Mac"
     row: Limits has it, one switch fewer.
   - **Automations:** `Automation`, notes and questions. **Updates:** `Update`, `ServerUpdateFailed`, and
     the "updated" notification after the app replaced itself (no server's: the defaults decide).
3. **Per server: an override.** In each server's card, in both modes and while it can't be reached:
   **Same as all / Custom / Off**. Custom starts from what the defaults were and shows the same switches
   for that server alone; going to Same or Off and back keeps its set. Keyed by the server's id;
   forgetting the server removes it.
4. **The rule** (`NotificationPrefs.allows(server, kind)`): master off is nothing from any server;
   otherwise the server's override wins, then the defaults.
5. **Only alerts change.** Cards, the session screen, the widget and the app's state are untouched: an
   approval still arrives and can be answered. **Banners in the open app still show**, whatever the
   switches say: the owner is already looking at the app, and the choice is about what reaches him away from it. A
   server watched in the background has no banners, so Off means nothing but its cards there.
6. **One gate, where they're posted:** `Notifier.approval`, `Notifier.notice`, `AutomationNotes.post` and
   `postAsk`, `AppUpdater.replaced`; not at each caller. `AGENT_ASK_DELAY` and the other delays run as
   before, the gate is at their end.
7. **What stays whatever the switches say:** the connection service's permanent notification (Android
   requires it), the media notification with Stop while the phone reads aloud (the owner started it), and
   a reply from a notification that failed (it answers what he just did, and Android keeps the notification
   turning until it changes). Android's own channels and the app's permission still apply on top; the
   existing ask shows when the permission is off.
8. **A switch turned off clears the shade.** Each notification carries its server and kind, read back from
   Android's own list, so it holds for ones posted before the process last ended. Silenced notices also
   take the last one's place, so "waiting" doesn't stay once its session has moved on. Allow, Deny, Reply
   and Answer go on working for one tapped a moment before.
9. **The warning.** Anything that would leave a paired server silent about approvals asks first, through
   the app's confirm, whichever switch does it (the default, a Custom set, Off, the master, or back to
   Same as all with defaults that have it off): "A prompt would then wait silently until you look, which
   is how agents can sit for hours." Already silent, or a default no server follows: no asking.
10. **Nothing is replayed.** What was silenced is not said later when the switch comes back; its card is
    there. A new app version is the exception, since it would otherwise be lost to the banner and to
    other servers: with Updates off the version isn't marked as said.

## Links out of Settings (2026-11-01)

The owner: "put a link to the GitHub repo" in About, and links to his settings for Claude Code and
Codex. About has a GitHub row. The Claude Code and Codex cards in Agents & providers each have an
Account settings row, opening the account's page on the web (`claude.ai/settings/usage`,
`chatgpt.com/#settings`, the addresses those programs link to themselves). He chose that over opening
`~/.claude` or `~/.codex` on the server: those stay closed to the phone, as they hold credentials.
All of them go straight to the browser, with no "Open this link?": they're rows, chosen on purpose.

## The agents' settings, viewed (2026-11-01)

The owner: "view the Claude Code or Codex settings on the server, a JSON or TOML file, but I probably
don't want everything because it's a very long file; look at the file and see what makes sense for us to
be able to set locally on the client." He chose a fixed list, **viewed first, then editable**. This is
step one, the view. Step two, which reverses "the daemon never edits a settings file", was his decision
and he has made it: "The agents' settings, edited", below.

1. **Where.** A Settings row on each agent's card in Agents & providers opens a read-only screen,
   `Route.AgentSettings`, for the hub whose card it is, so it works from All servers as Providers does.
   Nothing on the session's chat screen.
2. **The route.** `GET /v1/agents/settings` (`docs/PROTOCOL.md`, "Agent settings"), a paired phone only.
   Only named keys leave, each checked for its type and shape; anything else in that key is
   "unrecognised" and its value stays on the server. Words-only keys accept the words the agent's own
   schema names, so a new word from a newer agent reads as unrecognised until the list is updated: the
   safe way round.
3. **The user-level file only.** The reply's `scope: "user"` and the screen's first line say so, and that
   a project's own settings can override it.
4. **The links' reasoning still holds** (Links out of Settings, above): `~/.claude` and `~/.codex` stay
   closed to the phone's file browser. This route is the one way out, and only for the list.
5. **Plugin names without their marketplace.** The table asked for plugin names. The part after `@` isn't
   a source, but it isn't asked for either.
6. **A TOML reader, not a dependency** (`daemon/src/toml-reader.ts`). It reads the whole syntax rather than
   matching lines, so a `model = "…"` inside a multi-line string can't be taken for the key. It keeps
   nothing but what its caller asks for.
7. **What each key does**, checked on 2026-11-01 against Claude Code 2.1.316 (its schema's own
   descriptions, its docs at `code.claude.com/docs/en/settings-reference`) and Codex 0.164.0 (its binary,
   `--help`, and its docs at `learn.chatgpt.com/docs/config-file/config-reference`, which may describe a newer
   version than that):

   | Key | What it does | When it applies |
   |---|---|---|
   | `effortLevel` | Default effort for models with no saved level. **In the user file it reaches only Opus 5, Fable 5.1 and earlier**: Opus 5.5 and later ignore it there. `/effort` now saves per model under `modelSettings`, not here. | New sessions (read once at session start) |
   | `permissions.defaultMode` | The permission mode new sessions start in. `manual` is `default`. | New sessions |
   | `showThinkingSummaries` | Requests thinking summaries and shows them when expanded. Only what you see, not what the model does. | Docs don't name it; settings reload live unless listed otherwise, and it isn't listed |
   | `autoDreamEnabled` | Background memory consolidation; set, it overrides the server-side default. **In the binary only, not in the docs. Does nothing unless Anthropic's flag offers it to the account.** | Read when it's checked, not only at start (binary) |
   | `includeCoAuthoredBy` | **Deprecated since 2.0.62.** `false` drops Claude's credit from commits and pull requests; ignored once `attribution.commit` or `.pr` is set. | Docs don't name it; as `showThinkingSummaries` |
   | `autoUpdatesChannel` | Which releases auto-updates follow: `latest` or `stable`, about a week old. The schema also takes `rc`, undocumented; `claude install rc` saves it as `stable`. Homebrew ignores the key. | Docs don't name it; it only decides what updates fetch |
   | `inputNeededNotifEnabled` | **Anthropic's push to its Claude phone app**, while Remote Control is connected, when a prompt waits. Not Hypernova's notifications, despite the name. | Docs don't name it |
   | `agentPushNotifEnabled` | **The same app**: lets Claude push when it decides to. Sessions under `claude remote-control` follow the account's value, not this key. | Docs don't name it |
   | `model` | The model a thread starts on when not told otherwise. | Codex's docs don't say; new threads at least |
   | `model_reasoning_effort` | Reasoning effort; the levels depend on the model. | As `model` |
   | `approval_policy` | When Codex stops to ask. Docs: `on-request`, `never` or a granular table; **`untrusted` is unsupported as a setting and `on-failure` deprecated**, though the binary still parses them. | As `model` |
   | `sandbox_mode` | What commands may reach: `read-only`, `workspace-write`, `danger-full-access`. | As `model` |
   | `web_search` | `cached` (the default, OpenAI's index), `indexed`, `live`, `disabled`. | As `model` |
   | `personality` | **Deprecated: `friendly` and `pragmatic` no longer select a style.** Only `none` does anything, dropping the built-in Personality section. | As `model` |

   Not confirmed: when Codex's keys reach a thread already running. Its docs don't say, and the binary has
   a `reload_user_config` operation that wasn't traced, so the screen says nothing about it for Codex.
8. **MCP servers from where Claude Code keeps them** (owner, 2026-11-01: "there is a separate MCP JSON file
   that we'd need to read"). Claude Code's `mcpServers` isn't a key of `settings.json`, so that summary alone
   usually showed none. `claude.mcp` also lists names from `~/.claude.json` (user and local scope) and each
   of its projects' `.mcp.json` (project scope), with the folder's base name, beside the older list so an
   older app keeps working. Only those keys are read of a file that also holds the account's details, and
   `.claude.json` stays closed to the file viewer. Decided here: a server named in `settings.json` lists as
   user scope; a folder under `.claude/worktrees/` has its `.mcp.json` skipped, as a copy of the repository's;
   the home folder's name is `~`, because it's the username; plugins' servers aren't listed, and the row says
   so.

## A session's MCP servers (2026-11-01)

The owner: "for an agent I want to be able to see the MCPs, so when you hold down an agent card you see that
menu that pops up; add the feature that you can see the MCP details for a particular agent, not the global
ones but the ones for that folder."

1. **Where.** MCP servers in the Home card's hold menu, for a Claude session with a folder. A sheet titled with
   the session. Nothing on the chat screen, so the session sheet's copy of the menu leaves it out.
2. **The route.** `GET /v1/sessions/{id}/mcp` (`docs/PROTOCOL.md`, "A session's MCP servers"), a paired phone
   only. Project scope is `.mcp.json` in the folder and each parent; local scope is `~/.claude.json`'s entry.
   The user-level ones are only a count, as a line.
3. **A strict allow-list.** Name, scope, transport, a command's base name, a URL's host. Never args, env,
   headers, a whole URL or a path, and a command's name that says the username is left out too.
4. **Local scope isn't only the exact path.** Asked for that exact path, but Claude Code 2.1.316 files a
   folder's local servers under its repository's root, the main checkout's from a worktree (its
   `projectPathForConfig`, and the changelog's "the project entry … when run below a repository root"). The exact path
   alone would show none for a session in a subfolder or a worktree, so both are read.
5. **Set up, not connected.** The daemon doesn't know a session's live MCP status, so the sheet doesn't guess and
   says so. Showing it would mean asking the running claude.
6. **Not Codex.** The `projects` table in `~/.codex/config.toml` was only seen holding `trust_level`; Codex's binary
   names a trusted repository's own `.codex/config.toml` as the place for a project's MCP servers, and this
   doesn't read that file. So the menu has no such item for a Codex session, and the route answers 409 `not_claude`.

## Account details in the agents' settings (2026-11-01)

The owner asked for details about the account in Settings, then "in like the Claude Code and Codex
settings, another section for the account details". Each agent's settings screen starts with
**Account details**: how it's signed in and its plan, and how much of its 5-hour and 7-day windows
is used, per server. He chose what the server already sends: the email and organization still stay
on the server, so this can't say which account a server is on.

## Delete for every finished session (2026-11-01)

(Item 3 is replaced: see "Delete takes a session off Home, History brings it back", 2026-11-02.)

The owner, on k1: "I can't delete this completed session for some reason, it's pretty common for me
not to be able to do this." The card's menu showed only Rename: Delete was for `claude agents` jobs
and Codex sessions, so a finished session in a plain shared terminal had none, and nothing said why.
His rule: a completed session can always be deleted.

1. **Delete shows on every session at rest**, whatever made it: Home's Completed, ended ones too, on
   the card's menu, the session sheet's Actions (the same `MenuOffer`) and the swipes. One at work or
   waiting on him that is neither a job nor Codex has none, as before. Replaces "nor does a Completed
   card that isn't a job" in HM2: Smart's swipe deletes it, asked first. Codex's card still doesn't
   swipe to Delete (that decision's 4).
2. **Asked first, saying what it does:** a live terminal closes and Claude Code stops there; a job
   whose process still runs stops; otherwise only where it goes.
3. **It goes from Hypernova, the transcript stays.** Home, History and search, after a restart too
   (`<support>/deleted-sessions.json`). This was decided here, not by the owner: Claude Code has no
   delete of its own for a plain session, the words say plainly what happens, and a jsonl removed
   can't be got back. `claude --resume <id>` brings one back. The same for a job: `claude rm` leaves
   the transcript too, so a deleted job used to return to History at the next restart.
4. **What can't be deleted says why.** Idle in a terminal Hypernova doesn't hold: Delete is there,
   and tapping it says to close that terminal first. It isn't killed from here, as with a rewind.
5. **An older server** answers 409 `not_agents` for a session that is no job: the phone says that
   server needs updating, and the card stays.

## Pasting an image, whatever it says it is (2026-11-01)

The owner: "sometimes I can't paste a screenshot right into the text input." Then he added it by hand
with the paperclip. Nothing happened, with nothing said.

1. **A clip needn't call itself an image.** The box only looked at clips described as `image/*`, and
   at items whose resolver type was a specific image. Anything else fell through to the text paste,
   which finds no text in a Uri and does nothing. Now a file with no specific type is read: its first
   bytes decide, and name it (`.png`, not `.img`).
2. **Every box with a paperclip takes one:** the New session sheet's did not take images at all.
3. **Nothing fails silently:** a file that can't be read, or isn't an image, says so and points at
   the paperclip. A web address with no text goes in as words; an image with words keeps both.
4. Nothing else on the chat screen changes.

## Restart session (2026-11-01)

The owner: "restart or reload all MCPs of a session… hold down a session card to show the existing
menu, and it says reload MCPs, or restart session if that's what's needed to reload all of them."
A running Claude Code has no one command that reloads every MCP server: `/mcp reconnect all` only
retries those that failed or need signing in, and `/reload-plugins` is about plugins. A restart
reloads them all, so that's what it says.

1. **Restart session in the card's menu,** with one line under it: "Reloads its MCP servers and
   settings. The conversation carries on." The session sheet's Actions and the swipes have it too
   (the same `MenuOffer`). Wherever something runs that Hypernova can restart, not only an outdated
   job: a job whose process runs (`claude respawn`, as before) and a shared terminal (it closes, and
   `claude --resume <id>` opens in a new one: the provider switch's own path).
2. **The same session:** `--resume` keeps the id, so it stays one card and its transcript stays. It
   comes back in the mode it had, on the model it was started on, at its effort; never in bypass
   (the owner, 2026-10-30: no bypass for agents). A phone's open terminal view closes with the old
   terminal.
3. **Asks only if something would stop:** a turn at work, agents running, an approval waiting (a
   running turn stops), or a terminal open on it on the server (it closes). At rest and unwatched it
   goes at once with a toast. This replaces "Restart asks first" above for a job at rest too.
4. **Not where it can't be done:** none for Codex, for an ended session, or where nothing runs (its
   next prompt loads them). Idle in a terminal Hypernova doesn't hold, it's there and says why, as
   Delete does.
5. **An older server** answers 404: the phone says that server needs updating.

## The agents' settings, edited (owner, 2026-11-01)

Step two of "The agents' settings, viewed" (`docs/LATER.md`, L102). **The owner reversed "the daemon never
edits a settings file"**, with these limits. `daemon/src/agent-settings-edit.ts` is the one place it does;
`daemon/src/always.ts` says so, and the rules still go through Claude Code.

1. **A fixed list, on the server.** Anything else is refused before a file is opened.

   | | Editable, with these values | View only |
   |---|---|---|
   | Claude Code | `permissions.defaultMode`: `plan`, `default`, `dontAsk`, `acceptEdits`, `auto`. `showThinkingSummaries`. `autoUpdatesChannel`: `latest`, `stable` | `effortLevel`, `includeCoAuthoredBy`, `autoDreamEnabled`, `inputNeededNotifEnabled`, `agentPushNotifEnabled` |
   | Codex | `model`: what `/v1/codex/models` lists, view-only without a list. `model_reasoning_effort`: its eight words. `approval_policy`: `on-request`. `sandbox_mode`: `read-only`, `workspace-write`. `web_search`: its four words | `personality` |

2. **Permissive values stay off the phone.** `bypassPermissions`, `approval_policy = "never"` and
   `danger-full-access` can be seen and not chosen. A file holding one can only move off it. **Undo won't
   put one back**: it answers `undo_refused`, and the backup stays on the server for doing that by hand.
   Otherwise a tightening followed by Undo would be a way to set bypass from a phone. The same goes for a
   word the server doesn't know, which could be one, and for `rc`.
3. **Decided here, on evidence, narrower than the plan's table: `approval_policy` offers only `on-request`.**
   The plan ruled out `never` alone. A scratch Codex 0.164.0 refuses to start a thread on `untrusted`
   ("no longer supported; remove this setting") and reads `on-failure` as `on-request`, so offering either
   would break Codex or do nothing. The row is the way off any of them. `manual` isn't offered either: it's
   `default` under another name.
4. **Loosening asks.** One pure function, `loosens()`, on the server: Claude Code's modes in its own order
   (2.1.316's table: `plan` 0; `default`, `dontAsk` 1; `acceptEdits` 2; `auto` 3; bypass 4), approvals
   (`untrusted`, then `on-request` and `on-failure`, then `never`) and the sandbox. A key that isn't set
   counts as what it means (`default`; a scratch Codex's `on-request` and `read-only`). A value it can't
   place counts as loosening. Each choice in the reply carries the flag, so the app holds no copy of the
   order. The app confirms, saying what it allows, then `OwnerCheck`. Tightening asks nothing. The server
   can't see a fingerprint, so this is the phone's check: `docs/SECURITY-MODEL.md`.
5. **Only the bytes of one value change.** Both formats are edited in place by position, JSON too, rather
   than by the re-serialize the plan allowed: a re-serialize changes `1.0`, `\u00e9` and odd spacing. The
   new text is parsed again and compared with the old, and refused if anything else moved.
6. **The file.** A link is **refused, not followed** (`file_is_link`): a write through it would land
   wherever it points, a dotfiles repository say, and the row says so. A read-only file is refused too,
   though a rename could replace it: whoever made it so meant it. Neither agent's folder is created. A new
   file is 0600. Backups: `<support>/settings-backups/<agent>-<timestamp>`, 0600, the last 20 per agent.
7. **When each applies**, checked 2026-11-01 against Claude Code 2.1.316 (its binary; no live session, as a
   scratch config has no account) and Codex 0.164.0 (a scratch app-server with no account, its proxies
   pointing nowhere):

   | Key | The screen says | Evidence |
   |---|---|---|
   | `permissions.defaultMode` | Applies to new sessions. | **Confirmed** in the binary (read when a session's first mode is worked out) and docs. Whether it reaches a running session: not tested, not claimed. |
   | `showThinkingSummaries` | May need a new session. | **Unconfirmed.** The binary reads it at the session's start (the thinking display, set once) and again per request (a beta, a transcript mode), so neither "live" nor "new sessions" is safe to say. |
   | `autoUpdatesChannel` | Applies at the next update. | **Confirmed by reading only**: the updater asks the settings each time. Not seen running. Switching to `stable` can move back a version: Claude Code's own dialog offers to hold with `minimumVersion`, and this doesn't. |
   | `model`, `model_reasoning_effort`, `web_search` | Applies to new threads. | **Confirmed** on the scratch app-server: a file renamed over it reached `config/read` straight away, and `thread/start` for the model and effort, with no restart. `web_search` was seen only in `config/read`. |
   | `approval_policy`, `sandbox_mode` | Applies to new threads that don't choose their own. Ones started from this app do. | **Confirmed** the same way, and **`daemon/src/codex/sessions.ts` names both in its own `thread/start`**, so a phone's thread takes the mode picked on its sheet instead. |

   Not confirmed for Codex: a thread already running. The binary has `reload_user_config`, sent through
   `config/batchWrite`'s `reloadUserConfig`, which a write to the file doesn't do, but a turn can't run
   without an account, so it wasn't watched. The screen says nothing about running threads.

   Claude Code watches its settings files for atomic saves (the binary's watcher), so a rename is seen.
8. **The agents can write these too.** Claude Code's `/config` writes `permissions.defaultMode` and
   `autoUpdatesChannel`, `claude install <channel>` writes the channel, and any of its own settings writes
   rewrites the file whole. Codex's own settings screens write through `config/batchWrite`. There's no shared
   lock. The hash looked at again just before the rename makes the gap small, not zero: an agent's write in
   that last instant can be lost. After one of theirs, the phone's hash is out of date (409, read again) and
   Undo is off.
9. **Each server has its own.** The screen is the hub whose card was tapped; All servers never changes them
   in bulk. Nothing on the session's chat screen. Per-model effort under `modelSettings` is later (`docs/LATER.md`).

## The prompt box wins over the status bar (2026-11-01)

The owner: "there's not enough room for the input. Don't allow the pull-up menu to hide the input
side."

1. **The box is measured first, the bar last.** The bar takes what the box and the rest leave it, so
   nothing it lists can push the box off or clip it, at its 6 lines, with attachments, or with the
   keyboard up.
2. **The opened bar gets half of the room between the header and the box,** with or without the
   keyboard: a cap, worked out as it's measured, not a fold. It doesn't fold by itself when the
   keyboard opens: that would move the card under the finger, and a tap on it folds it anyway. Some
   of the chat stays in view.
3. **Short of room,** it keeps the folded bar and a row while they fit clear of the header, then the
   folded bar alone (over the header if it must: Stop is worth more than the header's words, and the
   header's back with the keyboard down). With less than that it isn't drawn.
4. **Agents and to-dos scroll as one list,** since a list each couldn't share a small panel. It follows
   the agents, not the to-dos, whose tally stays in the bar's row.
5. **The progress line stays clear:** the list stops 8dp above it whatever the scroll, and rows fade at
   an edge with more beyond it.

## The status bar closes when you leave it (2026-11-01)

The owner: "when the agents card is open in the session view … if I go to a different agent view and
then come back to that one, the menu is still open. I don't want it still open; I want it to reset
closed if I leave the view."

Leaving the session's chat closes its opened bar: to an agent's screen, another session or Home, and a
process restart starts it closed. Opening the keyboard isn't leaving, and neither is a configuration
change that recreates the screen (a font size, a split screen; the app is held to portrait, so not a
rotation). This replaces the bar staying open across a visit, which it did as saved state.

## No estimate of when usage runs out (owner, 2026-11-01)

The owner: "the estimated time left until you run out, based on the usage you're using, doesn't seem
accurate, let's remove it." He saw a red "2d 2h" on Home's usage card, with a red notch at the bar's
end, at only 12% of a week just after it reset.

This overturns part of the "spending drawn" decision of 2026-10-18 (the projection meters and the
sun's ring, in `docs/DESIGN.md`, and "The meter stays the source of truth" above). The spending stays
drawn. What it no longer draws is a guess.

1. **A meter shows facts:** how much is used and when the window resets, with its glyph. A window used
   up is still full and red until its reset, along with any limit Claude Code or Codex reported.
2. **Gone everywhere it showed:** the red time-to-limit tag, the red notch at the bar's end, the faint
   fill to where the pace leads, the pace arc, notch and red number at the sun's ring, and TalkBack's
   "spending 0.8% a minute: at this pace…" sentences. The chat's top bar and the New session pickers
   use the same meter. The automation timeline's budget bar never had an estimate.
3. **The code went too.** `UsageProjection` had little left and became `UsageWindow` (used, and time to
   the reset); `ProjectionMeter` became `UsageMeter`.
4. **The daemon stopped sending the estimate, not the rate.** `Usage.rate` loses `fiveHourEtaMs`,
   `sevenDayEtaMs`, `fiveHourLimitAt` and `sevenDayLimitAt`; nothing else read them. It keeps
   `fiveHour` and `sevenDay` (% a minute): they are a reading and not a forecast, and the sun's disk
   flows with them (`DiskModel.flow`). The sky has been hidden on Home since 2026-10-30, so nothing on
   screen uses them today; they stay so the disk still works if it comes back. An older app, given a
   rate and no time to the limit, works one out itself until it is updated: nothing breaks, and the
   estimate lasts only as long as that older app.
5. **The usage probe's looks stay as they were,** every 2 minutes while something works. They also keep
   the meters fresh while no status line refreshes.

## The status line's steps (2026-11-01)

The owner: "for the workflow green bar at the bottom I like to see tiny little dividers for each step." The chat's status line draws a short gap between steps, for a workflow's agents and for to-dos alike: done ones full, the current one breathing, the rest the track. With too many steps to read (each under about three gaps long) it goes back to one line.

## No session id on the cards (2026-11-01)

The owner asked what the two characters on a card were, the start of the session's id, with `1d` reading like an age next to `21d`, and chose to remove them. Home's cards and History's rows show only how long ago; TalkBack says "Active 4 minutes ago." or "Finished …". The session's own header keeps its short id.

## The usage card says its agent and plan (2026-11-01)

The owner: "remove the word official in the usage card", and add the agent and the plan (Pro, Max,
Ultra). The trail of each usage card now says both, `Claude Code Max` or `Codex Pro`, or just the
agent while the plan isn't known. Plans come from what the server already sends, so a plan the app
has no spelling for is still shown, capitalised. "not measured" and "no numbers yet" stay on Claude's
card, and LIMITED on Codex's, since they matter more than the name while they hold.

## The server's packages (2026-11-01)

The owner: "make the CI build a deb and img or whatever is needed for Linux and Mac", and he chose the server first; the desktop client's packages come after the shared module (LATER L105). Then: "make the names of everything clear about a server vs client version."

1. **Every download says server or client** (docs/RELEASING.md, "Names"): `hypernova-server-vX.Y.Z-linux-amd64.deb`, `hypernova-server-vX.Y.Z-macos-arm64.pkg`, `hypernova-client-vX.Y.Z-android.apk`, with the desktop client's names reserved. **The old names stay published**, `hypernova-<tag>.apk`, `hypernova-<tag>.json` and `hypernova-server-<tag>-linux.tar.gz`, because servers already running look for exactly those. A current server reads the client's name first and the old one as a fallback, and fetches one. The Debian package is `hypernova-server`, its command `hypernova-server-setup`. **The `hypernova` command keeps its name**: it's used everywhere, and renaming it would break installs.
2. **The `.deb` doesn't install a server; it lets a user install one.** The install stays per-user (its own Node.js, its own node-pty build, a systemd user service, self-update replacing that user's copy), and a package installs as root. So the `.deb` puts the release in `/usr/lib/hypernova-server`, declares in `Depends` what `install-linux.sh` would otherwise ask `sudo` for, and adds `/usr/bin/hypernova-server-setup`, which a user runs for the normal install from that folder. Its scripts only say what's next. Upgrading it leaves a running server alone; removing or purging it never touches a home folder. `hypernova-server-setup` won't go back from a server that updated itself past the package without `--force`. `scripts/test-server-deb.sh` holds all this, around a running session.
3. **The server installs on a Mac, from the same one-line command**: a LaunchAgent (`daemon/src/cli/launchd.ts`) instead of the unit, `scripts/install-macos.sh`, and `status`, `doctor`, `logs`, `pair` and `uninstall` working there. No menu bar app. `AbandonProcessGroup` is KillMode=process's counterpart: launchd.plist(5) says launchd kills what's left in a dead job's process group unless that's set, and holders have a process group of their own anyway. It starts at login, not at boot, which is what a LaunchAgent can do.
4. **A Mac doesn't update itself yet.** `installKind` says "not available here" with the one-line command, until it has run on a real Mac. A broken self-update on a machine nobody can reach is worse than none (LATER L106).
5. **Tarball and an unsigned `.pkg`, no `.dmg`.** A `.dmg` is for dragging an app to Applications, and this is a background service set up per user. The `.pkg` does what the `.deb` does. It's unsigned while there's no Apple account (LATER L107): docs/INSTALL.md says what a person sees, and the `curl` line avoids it.
6. **The Mac is tested on CI's runners only, and the docs say so.** There's no Mac here. `scripts/test-macos-smoke.sh` is the one real test: install, the LaunchAgent, `status`, `doctor`, the API, restarts, a session surviving them, `uninstall`. What it can't show, what macOS asks a person at the screen, is marked as such.
7. **A package that fails never stops a release.** `android-release.yml` publishes the APK and the Linux tarball first, as before; `server-packages.yml` runs after, with `needs: apk`, and adds what passes its tests, plus `SHA256SUMS`. The release's queue moved to the `apk` job, so a Mac runner that's slow to come can't hold up the next one. The price is some minutes in which a release has only its first files.

## A shared Android and desktop module, keeping OkHttp (owner, 2026-11-01)

The first step towards a desktop app that can connect, list sessions and approve: `android/shared` holds the data layer, for
Android and the desktop JVM (docs/PLATFORMS.md, "The shared module").

1. **Android and JVM only.** No iOS or web target, so **OkHttp, org.json, `java.*` and the mutual TLS code stay as they are**: no
   Ktor, no kotlinx.serialization. This replaces the "OkHttp becomes Ktor" line in PLATFORMS' iOS plan for now; iOS would have to
   take that up again.
2. **The package stays `com.prudnikweb.hypernova.data`,** so the app's imports hardly changed.
3. **No feature changed, and nothing new on the session chat screen.** The tests moved with their code, their names and assertions
   as they were. The hub's tests run twice: off Android, and on Robolectric with Android's side of the seams.
4. **`android/shared`, not a top-level `shared/`:** Gradle's root is `android/`, and the release and CI already follow `android/**`.
5. **No desktop app and no desktop implementations yet.** That is step 2.

## An agent's row says what it's for (2026-11-01)

The owner, of the rows under `main + 3 agents`: instead of "general-purpose", "a short title of what
they're responsible for". The row's first line is the short description the agent was started with,
on a line of its own so it fits, and only the agent's kind stands in when there's none. How long it has
run and its tokens move to the second line, beside what it's doing. Its kind shows there before its
first step.

## The Apache licence's notices (owner, 2026-11-01)

The owner: "as far as the Apache license we'll have to do what legally have to do". This is a fork of
`prudnik-web/Hypernova`, forked at `c64bbf9`. Nobody here is a lawyer: this is a careful reading of the licence's section 4, and
some of its words leave room for doubt (the end of this section).

1. **4(b), a modified file says so:** one line, `Modified by Leonardo Merza, 2026. See NOTICE.`, in a comment at the
   top of each file of the original that differs now. `scripts/license-notices.sh --fix` adds it and `--check` (CI,
   `.github/workflows/license-notices.yml`) lists what's missing, so branches that merge later can't lose it. It gets its own
   workflow with no paths filter: any file can need a notice, and the Android workflow would take 40 minutes for a change to the docs.
2. **A moved file is the original's file, modified,** even with no other change. The script counts a file git pairs with an original at
   40% alike (git's default of 50% missed files that were moved and then much changed, like `ChangesModel.kt` at 48%), and one with the same
   name and parent folder as a file that's gone from where it was, however rewritten (`Voice.kt`, 9% alike). New files get no notice.
3. **Below the header in `scripts/install-linux.sh`,** not on line 2 as in other scripts: its `--help` prints lines 2 to 19 of itself. The
   script finds such scripts by that `sed` and keeps their help the same.
4. **`CHANGES.md` covers what can't hold a comment** (the two JSON files and `NOTICE`) and says that the whole project is modified.
   It's in the server packages, since `daemon/package.json` is. `third_party/`, its copies in the APK's assets, `skills/hyper-cosmos/` and
   test data are left alone: none of them has been modified.
5. **4(d), `NOTICE`:** the original's lines stay, with two lines of ours below them. The APK's copy is the same file, and the script's check keeps it so.
   The same check covers the third-party notices, `third_party/`'s licences in `assets/legal/` (the Ghostty ones had been left out) and a declared licence for each third-party asset.
6. **4(c):** the README had lost two of the original's notices that still apply, on `third_party/`'s own licences and on Codex and OpenAI. They're back. The other
   notices that are gone went with the code they were about: the Mac app, Lens, the website and the Tailscale node.
7. **Section 6, trademarks:** the name and the package name are left for the rename the owner is choosing separately.

**What stays a matter of judgement:**

- "Prominent" isn't defined. A first-line comment is the usual reading, but Markdown hides it when rendered, and it's below a long header in one script.
- Comments don't survive compiling. The APK's code and resources can't carry a per-file line, so there `NOTICE` says it for them.
- A new file can hold code from an original that was split up, too little alike for the script to pair them. `CHANGES.md`'s statement that the
  whole project is modified is what covers those.
- Rename detection is a heuristic, and "alike" isn't a legal test. The script's list is a careful one and not a proof that it's complete.

## The daemon's tests on a Mac (2026-11-02)

The owner, of the first `macos-unit` run on GitHub's Mac runner, 13 failures of 1089: sort each into a test that assumes Linux or a real bug a Mac user would hit, and don't skip to make it green without knowing why.

1. **Most were the tests' own Linux assumptions, and were reproduced here without a Mac:** a temp folder under a symlink (what `/var` is on a Mac) brings seven of them back on Linux. The daemon was right each time: it and Claude Code work in a project's real path. The two tests that hadn't taken their scratch folder's real path now do, as `speech.test.ts` and `usage-probe.test.ts` already did.
2. **The contract tests still compare everything on a Mac.** The fixtures are a Linux server's, because the phone's parsers read them. What a Mac says differently on purpose (`platform`, `unavailable`, Stay awake's default, why it doesn't update itself, "the Mac" for "the server", `say`'s own voices) is checked and then set to the fixture's value. Only the two 501 refusals, which a Mac has no reason to make, aren't run there.
3. **One real finding, not fixed:** `say --file-format=m4af --data-format=aac` hung on that runner until it was killed, and `POST /v1/speech` answered 502 after 40 seconds, while the same `say` made a play's WAV in a second or two. Nothing says why, or whether a person's Mac does it, and changing the format blind would swap a known failure for an unknown one. A play is what the app uses first, and it works there. The tests now ask the machine, and hold the 502 where it can't; `macos-unit` prints what `say` and `afconvert` can make (LATER L108).
4. **`macos-unit` keeps `continue-on-error` until a run has shown it green.** Those fixes were checked on Linux with a symlinked temp folder and with `process.platform` set to `darwin`, not on a Mac.

## Screenshots come from a demo server, not from the app (2026-11-01)

The owner wants demo data back for screenshots that show off the app, on Android now and the desktop client later: "our own data", the best six shots. He didn't want a demo mode back in the app for now.

1. **The app still has no demo** ("No demo mode", 2026-10-31, holds): nothing in `android/` or `daemon/src/` knows about any of this.
2. **Screenshots come from a separate demo server** (`demo/server.ts`, `demo/README.md`). It answers a paired phone like a real server, using the daemon's own pairing and certificate code over the real local route with mutual TLS, from made-up replies, on loopback only. It runs nothing and changes nothing. `scripts/demo-shots.sh` retakes the README's shots. It isn't in the server package or the Docker image.
3. **The data is protocol-format files** (`demo/data/`: three invented servers, one JSON file per reply, nothing of the owner's). Times are placeholders (`"@now-3m"`) so a shot never looks stale. They hold no server logic, so a later iPhone app's demo mode, which App Store review would probably need, can load the same files (`docs/LATER.md`, L105).
4. **A protocol change fails loudly:** `daemon/test/demo-data.test.ts` holds every file against the shapes in `protocol/fixtures/`.

## The sky is gone (2026-11-01)

The owner, choosing the art: "Atlas but remove the animation from the home screen that's hidden, I don't want it at all."

1. **Deleted:** the sun and its accretion disk, the planets and moons, the belt, Codex's star, the companion, the eclipse, the tilt parallax and `SkyWindow`, with what only they used: `Solar.kt`, `Disk.kt`, `CodexStar.kt`, `Companion.kt`, `SkyLayout.kt`, `SkyPlan.kt`, `SkySplit.kt`, `SkyPhysics.kt`, `NameFit.kt`, `Parallax.kt`, `CosmosState`, and their tests and the `sky` baseline. It had been hidden on Home since 2026-10-30. This replaces every decision above about what the sky draws ("The sky, physically true" and its passes); they stay as history. It isn't parked in `docs/LATER.md`: he doesn't want it at all.
2. **Kept, because it still did something after the sky was hidden:**
   - **The thud for a 5-hour limit.** The hidden sky still ran its timeline, so the phone still felt a limit hit while the app was open. It's in `fx/LimitFeel.kt` now, with the same rules (fresh, not twice, not for one found later, nothing under Reduce Motion, the week outranks it).
   - **The filter by one project.** A launcher shortcut or Share to Hypernova sets it, so it isn't only the sky's. It's a plain project name now, with its chip and Browse files. The quiet ones' filter went, as only the belt set it.
   - **The far sky's thinning with the week.**
3. **Removed, though it still ran while hidden:** a limit, the week's low state or a reset scrolled Home's list to the top "to bring the sky into view". There's no sky to show, and it took you away from the usage card at the end of the list.
4. **Lost with `SkyWindow`, all since 2026-10-30, none of it new today, and only said here so that it's known:** TalkBack's one sentence about the sky (usage, projects, needs-you count), and its actions Show only a project, Show the quiet projects, Jump to usage and Show Codex's usage. Home's filter sheet can show one project; usage is at the end of the list; the mark's label opens Statistics. No notification or other path reached usage through the sky. "Tap the sun for your statistics" never showed after 2026-10-30, and went too.
5. **Nothing in the server or the protocol changed.** `Usage.rate` (`UsageRate`, how fast a window is being spent) is still sent and parsed, and the app no longer reads it anywhere on screen. The owner's testing checklist (`daemon/src/qa-checklist.ts`) still has its items about the sky.
6. **`skills/hyper-cosmos/` and `~/.claude/skills` weren't touched.** The skill is a separate, versioned design language, and it no longer matches this app: it still has `Solar.kt` and the planets, and the rule in `CLAUDE.md` about keeping it in step doesn't hold until he decides what the skill should be.

## Atlas (2026-11-01)

The owner chose Atlas's quiet version from `docs/concepts/art/` (branch `art-concepts`; that folder isn't merged). It swaps only what showed on Home: the background, the status marks, the logo and icons, and the colours. Cards, layout, type, navigation and spacing didn't change. How it looks is in `docs/DESIGN.md`, "Colour", "The chart", "Marks".

1. **The far sky went too:** `Cosmos.kt`, `Stars.kt`, `DeepSky.kt` and `SkyArt.kt`, the stars, nebulae and deep-sky pictures, for `fx/Atlas.kt`.
2. **For a later name.** The logo's constellation reads as an N, not the concept's h, because the app is being renamed. No name, app id or command changed here: the app still says Hypernova.
3. **What the concept said and this didn't do:**
   - **No visible slow turn.** The concept allowed "no frames at rest, or 5 for the turn"; this is the first. The grid jumps to one of nine places when the app comes to the front. A turn would redraw the chart and blur the bars 5 times a second, and hairlines moved by under a pixel shimmer. Burn-in with this isn't tested.
   - **Linked is a solid diamond in the connection chip**, in its tone, not the concept's grey hollow one: hollow is idle here, and the chip has its own hollow for not being paired.
   - **Stepping back behind text:** the chart is at 45% behind anything but Home, as the far sky was behind text. The concept didn't draw those screens.
4. **Replaced beyond the concept's list, so no old planet is left:** the connection chip's planet and moon, the blocked line's eclipsed sun, Statistics' project planets, the voice capsule's and the mic's sphere (their rings stay), and a project shortcut's icon. `ic_speaking.xml` isn't a planet (five bars) and `ic_speech_stop.xml` is a square: both stayed.
5. **Colours also repeat, as text, where Kotlin can't reach:** the widget's resources, the terminal's and Mermaid's pages, notifications' tints. The terminal's ANSI blues, magentas and bright reds, and Mermaid's other categorical colours, are as they were. `HN` is still fixed values: the selector (L109) still has to make it switchable.
6. **Measured, on an emulator with a software GPU (`hn-art`, Android 36, `swangle_indirect`), so these say what was asked for and not what a phone costs:** Reduce Motion: Home with four sessions working, 2 frames in 30 s; Settings, 0. Animations on: Settings, 139 frames in 30 s, the grain's own lane alone (it only moves its layer; the chart doesn't redraw for it); Home, 45, as the software GPU gives what it can to the working marks and grain. AtlasTest shows the chart's `draw` running once and not again over a minute of the clock. Nothing was measured on a real phone: no battery or heat figure.
7. **Not tested:** hairlines at low brightness on a real panel. The grid is ink at 15%: about 1.4:1 against the page by arithmetic, so it may vanish on a dim phone, which leaves the page plainer and loses nothing. There's nothing in it to band: no gradient. The emulator can't show either.

## The hyper-cosmos skill is gone (2026-11-02)

The owner: "remove skill". `skills/hyper-cosmos/`, the original project's design language as a Claude
Code skill, stopped matching the app with Atlas, and it's removed. It's in git history. There's no
personal copy on this machine to keep in step with. Older entries that name it are history, and the
sample name `/hyper-cosmos` in tests and the protocol's examples stays: any skill's name would do.

## Mode and effort apart, and the API cost, in the session's top bar (2026-11-02)

The owner, of Settings → Session top bar: "split mode and effort also add API costs as an option". The
bar's Mode and Effort are two items, each with its own switch and place. A choice kept from when they
were one keeps the effort right after the mode, on or off as it was. **API cost** is a new item, off by
default: what the session comes to at API prices, even on a plan, from `costUsd` the usage already
carries, with "≈" when it was worked out here rather than given by Claude Code. It doesn't draw where no
price applies, such as another provider.



## The desktop app (2026-11-02)

The owner: "have another agent start on the step two of the desktop app." Step 2 of the plan in "A shared Android and desktop module": a thin desktop shell, Linux first, that connects, lists sessions and approves. `android/desktop`; how it's laid out and each seam are in docs/PLATFORMS.md, "The desktop app".

1. **Compose for Desktop 1.12.1,** the version of the runtime `:shared` already uses, on Kotlin 2.4.21. The compatibility page's newest was 1.12.2 when it was read; moving means moving `:shared`, whose runtime on Android is the app's. `:app`'s runtime classpath stayed the same, compared line by line for debug and release, with the plugin on the root build's classpath.
2. **The device key started as one owner-only file,** `device-key.json` in the client's own folder, which is now the fallback behind the system's keychain ("The desktop client's key in the keychain"): EC P-256 as on a phone, the same `DeviceCertStore` and CSR, with mode 0600 from the moment the file exists (an ACL naming only the owner where there are no modes), written atomically, never through a symlink, refused where the file system won't hold the mode. The settings file, which holds the device certificates and, where no keychain has them, the bearer tokens ("The desktop client's tokens in the keychain"), is the same. The folder is never the server's, since one computer can run both.
   - **What that isn't.** Android's keystore does the signing itself, on a phone often in hardware, and the app never holds the key's bytes, so a compromised app can use the key but not carry it away. Here the bytes are in a file and in the process's memory: any program running as this user can read them, and so can whoever has the disk or a backup of the home folder, unless the disk is encrypted. Taken, the key and token let someone pass as this computer until it's unpaired on the server. The pairing, the pin and the mutual TLS themselves are no weaker; what's weaker is where the secret sits.
   - **Is a keychain worth doing? Yes, and it's done (LATER L114).** This line once said, from memory and marked so, that the JDK reaches the Mac's Keychain and Windows' certificate store "with nothing added", and that `Windows-MY`'s keys can be non-exportable. Read in JDK 21's sources, that's only half right: `Windows-MY` refuses an EC key from Java, and what `KeychainStore` holds comes back into the process. The corrected account is in that section. What it said of Linux holds: the Secret Service gives a secret to any program of the same user's session once unlocked, and isn't there at all on a headless machine.
3. **Allow only beside the whole command, for now the stricter of the phone's two rules.** A notification and the widget have Allow only when `WidgetModel.answerable` gives the exact command; the phone's Home card has it either way (owner, 2026-10-30), because a tap on that card opens the chat for the details. There's no chat here, so a tool that can't be read whole has Deny, says why, and shows what it can, masked. The rule itself moved down to `:shared` (`AllowRule.kt`, with `Secrets`), so the desktop runs the phone's rule and not a copy; `WidgetModel` calls it and Android's behaviour is the same.
4. **Plain http is refused here as Android's network security config refuses it on a phone.** A JVM has no such config, so OkHttp would have sent a pairing code and then the token in the clear to a link's `http://` address. `Net.httpsOnly` (`:shared`, off on Android, so nothing changes there) makes every request and socket of `Net.client` fail with `Problem.Cleartext`, and `problem_cleartext`'s words show. The pinned client only ever dials `https`.
5. **A desktop isn't told about sleep, the screen or the network,** so these are its nearest honest versions: the clock is `nanoTime` plus any time the wall clock moved on further (Linux's monotonic clock doesn't count a sleep), a retry checks it every second, the network is the JDK's list of interfaces read every 5 s, without virtual bridges, and "the screen came on" is the window getting the focus. There's no hotspot sweep: on a desktop a `.1` address is a Docker bridge.
6. **The words are the phone's,** read from the same `res/values` files at run time and held to `XmlStrings` for every key by `WordsTest`, with "this phone" made "this computer" and "scan the code" made "paste the link" on the way in. Words only this app has are in `desktop/src/main/resources/desktop/strings_desktop.xml`. `versionCode` is as high as an Int goes: a server offers the phone's APK by its number, which is no update for this app.
7. **Copied rather than shared, for now (LATER L115):** `Palette` and the type scale in `ui/Theme.kt` (`Tokens.kt` loads its fonts through `R`; `WordsTest` compares the colours with that file), the chart's numbers in `ui/AtlasPlate.kt` (`fx/Atlas.kt` draws on `android.graphics`), a few parts of the kit in `ui/Kit.kt`, and short rules from `HomeScreen` and `CardNow` (the groups, the sort, a Working card's line). `Marks.kt` has nothing of Android in it, so it isn't copied: the build compiles the app's own file.
8. **Left out of Atlas here, on purpose:** the grain, the blur under the bars (no Haze), the chart's jumps and its burn-in reasons, the pulse of a needs-you mark, and the phone's haptics. A monospaced command has its ligatures off, since JetBrains Mono joined `--` into one mark in the first pictures. The phone's `Type.mono` asks for nothing either way, and nobody has looked at this on the phone.
9. **Not done, and said so on screen or in the code:** no reading aloud, no updates. (Notifications were here too, until "Tray alerts on the desktop client".) A question or a plan says to answer it on the phone. Closing the window ends the process and the link: there's no background.

## Effort per model (2026-11-02)

The owner: "I should be able to edit the effort." `docs/LATER.md`, L104. Checked against Claude Code 2.1.316 by
what actually reaches the API: a real `claude -p` in a throwaway home, against a stand-in `ANTHROPIC_BASE_URL` that records
`output_config.effort` on `/v1/messages`.

| `settings.json` | Model | Effort sent |
|---|---|---|
| nothing | `claude-opus-5-5` | medium (the model's own) |
| `effortLevel: "low"` | `claude-opus-5-5`, `claude-sonnet-5-5` | medium: **ignored** |
| `effortLevel: "low"` | `claude-opus-5`, `claude-sonnet-5` | low |
| `modelSettings.claude-opus-5-5.effortLevel: "low"` | `claude-opus-5-5`, its alias `opus`, and `claude-opus-5-5[1m]` | low |
| the same | `claude-sonnet-5-5` | medium: it's per model |
| `modelSettings.opus.effortLevel: "low"` | `claude-opus-5-5` | low: a key is put in canonical form when read |

1. **What's written is what `/effort` writes:** `modelSettings.<canonical id>.effortLevel` in the user's `settings.json`
   (the binary's writer; the id is the `model` of Claude Code's own list, `GET /v1/models`).
2. **A row per model,** in a section of its own, Effort by model: Claude Code's list, and any other model the file already
   has a level for. Low, medium, high, extra high, or the model's default, which takes the key out. **`max` isn't offered**: the
   schema refuses it in a file and `/effort max` only lasts a session. A model that doesn't have a level may clamp it, as `/effort` would;
   the phone doesn't know each model's levels beyond Claude Code's list, which offers all five on every current model.
3. **Same route, same care.** `PUT /v1/agents/settings` with the key `modelSettings.<model>.effortLevel`: byte-identity, the hash, the backup and Undo as
   every other key, and `null` to take it out (`docs/PROTOCOL.md`, "Effort per model"). No fingerprint: effort lets nothing act without asking.
   The model's shape is checked on the server; the words are its list.
4. **The top-level `effortLevel` stays view-only,** now labelled for what it does: only older models read it.
5. **Beside the fixed list, not in it**, so an older app sees nothing new: `claude.effort` in the reply.
6. **When it applies:** new sessions on that model, measured. A running session that hasn't picked an effort of its own may take it when
   Claude Code reloads settings (its resolver reads the settings table); that wasn't measured, so the screen only promises new sessions.
7. **Codex** is unchanged: `model_reasoning_effort` was already editable, and it's one value, not per model.

## Managing MCP servers from the phone (2026-11-02)

The owner: "I want to be able to click on an MCP and see where they're in the global settings or agent settings, and … how it's set
up and its tools … add new MCPs … on a global or project basis … disable or enable or reconnect. All the features that you can do at MCPs, I want to be
able to do." This replaces "Names only" and "Never args" of "The agents' settings, viewed" 8 and "A session's MCP servers" 3 for this screen: those routes keep their lists as they were.

**The one place it cuts against "bypass stays off the phone":** adding or changing a stdio server, approving a project's servers, and an Undo that puts one back all run a program on
the server. The owner decided that's allowed, behind a confirm showing the whole command and his fingerprint. Enable, disable and reconnect of an existing server need neither.

1. **Where.** Agents & providers › an agent's Settings › MCP servers: `Route.McpServers`, then a server's page, then the form. Nothing on the session's chat screen. The session sheet's MCP view still lists names:
   the phone has no way there to open a screen under the sheet, so the link it may have wasn't added.
2. **What's shown.** Scope, the file, the type, the command and its arguments or the URL, the **names** of env vars and headers, OAuth client and port, timeout. Values of env and
   headers never leave, whatever they look like. Arguments and URLs are masked where they look like a secret: by the flag before them (`--token xyz`, `--api-key=…`, `-H "X: y"`), by a secret-named
   `NAME=value`, a `user:pass@` or a secret-named query value, a `Bearer …`, or a random-looking run (`maskSecrets`). Masks are a heuristic: an argument that holds a secret with no sign of it
   (`mytool hunter2`) would be shown. Only `mcpServers` and a project's MCP keys are read from `~/.claude.json`, which stays closed to the file viewer.
3. **Status comes from Claude Code, per folder.** A process with no prompt and hooks off (as the "/" list asks) answers `mcpServerStatus()`. It's asked per folder because what's on differs per folder: the screen has a
   "Seen from" row. It costs no tokens, needs no login, and starts each server, as a session would, so an answer is kept 20 seconds.
4. **A process under the SDK approves every project server** (2.1.316: `non_interactive`). So it's told, in its flag settings, that each one not yet approved is rejected, and it doesn't start.
   `daemon/src/commands.ts`'s "/" list had no such guard and did start them: closed in "What opening a list may start", below.
5. **A tool's detail comes from the server itself.** Claude Code's status gives a tool's name and hints, no description or schema (2.1.316's reply builder; its types offer a `description` it doesn't fill). So
   `daemon/src/mcp-client.ts` is a small MCP client for stdio, Streamable HTTP and SSE: it starts the server as Claude Code would, with its env and `${VAR}` expanded, asks `tools/list`, `prompts/list`, `resources/list`,
   and stops. Tools are never called. Never for a server that's off or not approved. A plugin's, an organization's or a claude.ai connector's has names and hints only: its env isn't in a file this reads.
   A server signed in through OAuth answers 401 here too, because the tokens are Claude Code's and aren't read: it shows names and hints, saying it needs signing in only if Claude Code also says so.
6. **Adding goes through Claude Code's commands:** `claude mcp add-json`, as the form's fields become its JSON, so its own validation and its writer (and lock) hold. A client secret goes as `MCP_CLIENT_SECRET`. **Other secrets are on that command's argv for a moment**,
   visible to `ps` for other local users of that computer: the CLI has no other way in, and the alternative was hand-writing a file many processes rewrite. Output from these commands is never kept: `claude mcp get` prints env values, so it isn't run at all.
   A failure's line is shown with what was sent and what looks secret taken out.
7. **Editing is remove then add**, since Claude Code has no edit. If the add fails the old entry goes back. A name can't change, nor a scope: that's remove and add. Fields the form doesn't have (`alwaysLoad`, a helper) carry over.
   Removing may drop a stored OAuth sign-in with the entry; not checked, so it may ask to sign in again.
8. **A secret is write-only on the form.** A row already on the server says its value stays, with Replace and Remove. An argument still masked stands for the real one. A mask the server didn't give out is refused rather than written as three dots.
9. **Undo** of the last add, edit or remove, one step, while the entry is what that change left. It's kept like a settings backup: the whole entry, 0600 in a 0700 folder under `<support>`.
10. **Enable and disable are per folder**, as in `/mcp`: Claude Code's writer puts the name in that project's `disabledMcpServers`. There's no global off. A session already running keeps what it has, and the page says Restart session picks it up.
11. **Reconnect** is `reconnectMcpServer()` in a fresh process: it says whether the server connects now. It doesn't reach a running session, which holds its own connections. Typing `/mcp reconnect <name>` in that session still does, and Restart session reloads them all. A per-session reconnect from this screen isn't built.
12. **Approving** writes `enabledMcpjsonServers` in the folder's `.claude/settings.local.json`, where Claude Code's own dialog writes it (it has no command for this), in place and atomically, a link refused. **Claude Code ignores a folder's own approvals until the folder is trusted**, so Approve on an untrusted folder
    says so (409 `not_trusted`): the phone doesn't accept the trust dialog, which covers much more than servers. Turning one down needs no check. `claude mcp reset-project-choices` is "Forget this folder's answers".
13. **OAuth works from the phone**, which wasn't expected: `claude mcp login --no-browser` needs a terminal to take the pasted address, so one runs unseen on the server. The phone opens the page in its browser, which ends at a localhost address that doesn't load; pasted back, only if it's `localhost`, it goes in as typed.
    Tokens stay with Claude Code. A connector of a claude.ai account is managed on claude.ai.

### Every MCP capability, and whether it's here

| Claude Code | Here |
|---|---|
| `claude mcp add`, `add-json` (stdio, http, sse; `-e`, `-H`, `--scope`, `--client-id`, `--client-secret`, `--callback-port`) | Yes, as the form |
| `add-json` of a `ws` server | Shown, not added or edited: `add` doesn't offer it and the form doesn't |
| `claude mcp list`, `get` (status) | Yes, from `mcpServerStatus()`, adding hints and tools |
| `claude mcp remove` | Yes, with Undo |
| `claude mcp login`, `--no-browser`; `logout` | Yes |
| `claude mcp reset-project-choices` | Yes |
| `claude mcp add-from-claude-desktop` | No: Mac and WSL only, and it reads another app's config |
| `claude mcp serve` | No: it makes Claude Code a server, nothing to manage |
| `/mcp`: view tools, enable, disable, reconnect, authenticate, re-authenticate, clear authentication, approve | Yes. Reconnect as in 11 |
| `/mcp`: "Connect on claude.ai", a connector's sign-in | No: it's on claude.ai |
| SDK `setMcpServers()` | No: it only changes one session's own dynamic set |
| SDK `mcp_call` (run a tool) | No: seeing a tool isn't running one, and this is what approvals guard |
| `enableAllProjectMcpServers`, `allowedMcpServers`, `deniedMcpServers`, `managedMcpServers` | Read where they affect approval; not set here |
| `alwaysLoad`, `headersHelper`, per-tool policy | Shown or carried over; not on the form |

## What opening a list may start (owner, 2026-11-02)

The "/" list and the model list came from an SDK process that approves every project server, so opening a list in a folder ran that folder's `.mcp.json`: measured, a marker file. Nobody chose to run anything by opening a list. A session a person starts keeps Claude Code's own behaviour.

1. **One guard, `guardArgs` in `mcp-probe.ts`,** for every process that isn't a session: the "/" list, the model list (the same process, in the home folder), status, toggle, reconnect, the skills list. It's worked out as the process starts, so a caller can't forget it. What isn't approved is named as rejected in the flag settings.
2. **That alone wasn't enough, for status too.** Claude Code also reads a `.mcp.json` that's a link or over the daemon's 256 KB, whose names the daemon won't read, and walks up from the folder's **real** path. All measured with marker files on 2.1.316. So names come from the real path, and a file that can't be read or parsed means `--strict-mcp-config` for that process: no servers at all, the user's too. That costs that one folder's status and list some names, and only in a case this odd.
3. **An approved server's prompts didn't list before this either.** `initialize` answers in about 0.6 s, before a server has connected, so they were never in its answer (only `alwaysLoad` waits). The list now waits up to 3 s for servers still connecting and adds `supportedCommands()`'s. A judgement call: a folder with a slow server takes up to 3 s longer the first time, then the cache covers it. What a phone sends for such a prompt (`/server:prompt (MCP)`) wasn't run, since that costs a model turn.
4. **A folder's `apiKeyHelper` (owner, 2026-11-02: closed).** Those processes ran one from the folder's `.claude/settings.json` or `settings.local.json`, trusted or not, when they start: measured. The flag settings now name the user's own helper, or `""` when there's none, and flag settings beat both of the folder's, so there's no list of files to get wrong. **The user's own helper keeps working**, tested: it still runs, and it doesn't lose to a folder's. **A list needs no sign-in at all**: with none, in a throwaway home, the list and the models answer as usual, and only the plan isn't noted. So nothing changes on the phone for someone whose helper is their only way in. `""` counts as no helper in 2.1.316's binary (`if(!e)`), so a normal login is unaffected: read there, not run, since a real login isn't used in a test. The price: a user's own helper is on that process's command line while it lives, at most 20 s for a list. A session keeps Claude Code's own behaviour, an automation's too. `awsAuthRefresh`, `awsCredentialExport`, `gcpAuthRefresh`, `otelHeadersHelper`, `statusLine`, hooks, `mcpServers` in that file, and a plugin the folder's settings name from a marketplace of their own all ran nothing.
5. **Other things that start a process.** A session from the phone and `claude --bg` are a person's choice, and stay as they were. An automation is the next section's. The usage probe has its own folder and `--strict-mcp-config`. The plugin and skill commands (`plugin list`, `list --available`, `marketplace list`, `details`, `configure`, `prune`, `mcp logout`, `edit-skill-overrides`) left no marker. `mcp-client.ts` only starts what's approved and on.

## Codex's MCP servers (2026-11-02)

The same screens for Codex 0.164.0, for what it really has, checked against the real binary in a throwaway `CODEX_HOME`.

| | Claude Code | Codex |
|---|---|---|
| Scopes | user, local, project, plugin, managed, claude.ai | **One: `~/.codex/config.toml`.** `codex mcp add` says "global". Its binary names a trusted repository's `.codex/config.toml`, not read or written here, so a server there isn't listed. Plugins' show in the status |
| Types | stdio, HTTP, SSE; `ws` shown | stdio, streamable HTTP. SSE is refused |
| Reading | `~/.claude.json`, `.mcp.json` | `codex mcp list --json`, which prints env and header values: read on the server and filtered |
| Status and tools | `mcpServerStatus()` for names; the daemon's own client for details | `mcpServerStatus/list` from a short-lived `codex app-server`: **tools in full, with schemas**, so no client of ours |
| Writing | `claude mcp add-json`, secrets on its argv for a moment | `config/value/write`, Codex's own writer, so **secrets never go on a command line**, and comments in the file stay. `codex mcp add` isn't used: it can't set headers |
| On and off | Per folder | **`enabled` in the server's table: everywhere.** There's no per-folder list, and the page says so |
| Reconnect | A fresh process's `reconnectMcpServer()` | `config/mcpServer/reload` on the daemon's connection, if it has one, then a fresh look. Reasoned, not run: no test has a live shared app-server |
| Approval of a project's servers | Yes | **None**: no such thing. 409 `not_for_codex` |
| Sign-in | `claude mcp login --no-browser` | `codex mcp login --no-browser`, the same paste; Codex takes it through the same hidden terminal |
| Prompts | Listed | Its answer has none. Resources listed |

**`oauth` and `timeout` on a Codex form (owner, 2026-11-02).** The route checked both and then dropped them, so a setting just vanished. `timeout` is implemented: Codex's `tool_timeout_sec`, written with its own writer and read back by its own list, tested on 0.164.0 (that it's Codex that then stops a slow tool isn't run: no model turn). `oauth` is refused by name, 400 `codex_no_oauth`, because it isn't simple: `codex mcp add --oauth-client-id` writes an `[mcp_servers.x.oauth]` table with a client secret in `config.toml`, `codex mcp list --json` doesn't show that table so a page couldn't say it's set, and there's no per-server port, only a `callback_url` it makes up. The app's Codex form never had those fields; neither form has a timeout, so that's for the route and for what's already in the file, which the page shows. **An `oauth` table set with `codex mcp add` now survives (owner, 2026-11-02).** That list doesn't show it and a write replaces the whole table, so one `PUT` used to drop it (measured on 0.164.0). An edit, a remove and their Undo now read what the list doesn't show from Codex's own `config/read`, the user's layer, and carry it, a tool's `approval_mode` too, and it goes nowhere but back into the file. It's dropped on purpose when a server becomes a stdio one, which has no use for a secret. The page still doesn't say a sign-in is set.

`bearer_token_env_var` is on the form for an HTTP server, as a variable's name, never a token. Fields the form doesn't have carry over on an edit (`enabled_tools`, `disabled_tools`, timeouts, `env_vars`, `cwd`, `env_http_headers`), and show on the page.
Not on the form, though the CLI has them: `--oauth-client-id`, `--oauth-client-secret`, `--oauth-client-registration`, `--oauth-resource`, `login --scopes`.

## What an automation may start (owner, 2026-11-02)

An automation runs with nobody watching, in a folder whose `.mcp.json` may have changed since it was saved, so it only gets what the owner has already approved. Measured with marker files on 2.1.316, with no login and a dead API address, so nothing reached a model.

1. **Under the SDK it started whatever was there:** the marker was written for a server nobody approved. Now it isn't, and an approved one still starts. It's worked out at each turn (`runner.ts`), so an approval since counts, and it's only for a session with `automation`: a phone's session stays as it was.
2. **In a shared terminal, the usual way here, it didn't start, and the run stopped instead.** Claude Code asked "New MCP server found in this project", trusted folder or not, with nobody to answer. That's the worse of the two, so **this went beyond the SDK runner the owner named**, in the same function, `startHeld`: the names go in a settings file beside the run's hook file, `automation-hooks-<hash>.json`, 0600, because that file holds the hook's secret and so can't go on a command line. No question, and the run goes on. How long it would have sat there wasn't timed.
3. **What it can't read.** A `.mcp.json` that can't be read here means `--strict-mcp-config` for that run, as for a list: no MCP servers at all, the owner's own too. The run says so.
4. **It says so, three ways, and still runs.** On the step, `RunStep.note`, every run, in amber in the timeline, or on the run's row when there's one step and so no timeline. To the phone, as a note, when the words change, not on every firing, since a daily automation would otherwise repeat it daily; a daemon restart says it once more. In the session's chat, as a notice. Each names the servers. One the owner turned down isn't mentioned: that isn't news to him. Whether Claude finishes the job without the server is up to its prompt, and nothing here checks.
5. **A folder's `apiKeyHelper` isn't held back here.** The list's guard isn't used whole: a folder's helper can be how that folder signs in, and hooks stay on. So an automation in a folder still runs that folder's helper, as a session there does: measured under the SDK, a marker written; not run in a shared terminal. It's his to decide.

## Codex's version on these routes (owner, 2026-11-02)

Two Codexes can be on one machine, here 0.39.0 and 0.164.0 under nvm, and the MCP, plugin and skill routes ran `codexBinary().path` without looking at the version.

1. **The floor is `CODEX_MIN`, the one Hypernova already has, 0.163.0.** There isn't a second number. What these routes use: `codex mcp list --json`, `plugin`, and an `app-server` with `mcpServerStatus/list`, `config/value/write`, `plugin/list`, `skills/list`. Measured: 0.164.0 has all of them; 0.39.0 has `mcp list` alone, says `unexpected argument` to `plugin`, and takes `app-server` for a prompt and stops with "No such device or address". **Those are the only two versions on hand, so the exact first version with all of it isn't known:** one between them could work and be refused. Finding out means fetching others, which wasn't done.
2. **A refusal, not a failure:** 409 `codex_too_old` with `version` and `min`, on every route, before anything but `--version` runs. One that won't say its version is refused too. The app shows the server's words, as it does any refusal here. They don't say `codex update`, as the older message does: 0.39.0 has no such command.
3. **Which one runs.** `findCodex` took the first it found: `~/.local/bin`, `/usr/local/bin`, `/usr/bin`, then nvm's Nodes, newest Node first. That goes by the Node's version, not Codex's: 0.39.0 on Node 24 would have beaten 0.164.0 on Node 20. This machine got the right one because 0.164.0 happens to be on the newer Node. **It now takes the newest Codex of those it finds.** `HYPERNOVA_CODEX` still wins as named, and the login shell's PATH is still only asked when none of those places has one, so a newer one only on that PATH isn't seen.

## Plugins from the phone (2026-11-02)

The owner: "since we're doing MCP management we're going to have to do plugin management as well, adding, removing, etc." Agents & providers › an agent's Settings › Plugins; nothing on the chat screen. Each action is the agent's own command (`claude plugin …`, `codex plugin …`) or Codex's app-server, and what leaves is named fields: `claude plugin list --json` carries a plugin's whole MCP setup, env included, so only its server names are kept.

1. **What runs code, measured.** A plugin's `SessionStart` hook that notes each run, watched through install, disable, enable, update, marketplace update and uninstall: it ran **for none of them**. Its code runs from the next session on.
2. **So a fingerprint for installing a plugin and adding a marketplace**: the source in full on a confirm first (the marketplace, its source, the plugin's source), because they decide what code arrives. **Not for enable, disable, uninstall, removing a marketplace**: they ran nothing. Enabling a plugin lets it run again at the next session, and doesn't ask. **Updating a plugin** didn't ask at first, then did: see 7.
3. **A plugin that installs by running a command** (a marketplace's command source, or a `headersHelper` for its archive): Claude Code reports the command and its hash and won't go on without them. The phone shows that command on a second confirm with the fingerprint and sends the hash back (`--accept-command`). **Only reasoned and tested with a stand-in**: no such plugin was to hand.
4. **A plugin's options:** `claude plugin configure --json` and `--values-stdin`, so values aren't on a command line. A sensitive option is write-only: it says it's set. Claude Code keeps those in its own credentials store.
5. **Details are parsed from text:** `claude plugin details` has no JSON. If its wording changes, the lists come back empty rather than wrong, and the page says it doesn't list anything.
6. **The full list of what can be installed** is thousands long (the official directory): it's searched on the server, 100 at a time, cached 5 minutes.

7. **The fingerprint on an update (owner, 2026-11-02).** A new version can bring new hooks, which run on every session, so updating a plugin now takes `confirmRun` and a confirm saying which plugin. **A marketplace's update was decided by what it does, measured on real git sources, and the two agents differ:**
   - **Claude Code 2.1.316: no fingerprint.** It fetches the new version into its cache, and `plugin list` still says the old one. A new process with hooks on ran none of the new version's hook or MCP server; after `plugin update`, it ran both. `autoUpdate` set on that marketplace changed nothing for this command. It has no `--accept-command`, so no declared command runs from it (reasoned from its `--help`). What it changes is what a later install or update gets, and each of those asks.
   - **Codex 0.164.0: the fingerprint.** `plugin marketplace upgrade` moved the installed plugin from 1.0.0 to 2.0.0 with its new hooks file, there and then, as Codex has no other update of a plugin. So it's a plugin's update under another name. That a Codex session then runs those hooks isn't run: it took no model turn to see the version move.
   Enable stays as it was.

| `claude plugin` / `/plugin` | Here |
|---|---|
| `list`, `list --available` | Yes |
| `details` (inventory, projected tokens) | Yes, for what's installed |
| `install` (`--scope`, `--accept-command`) | Yes. `--marketplace <source>`, `--registry`, `--config` aren't on the screen |
| `uninstall` (`--scope`, `--keep-data`), `enable`, `disable`, `update`, `prune` | Yes in the route. `--keep-data`, `disable --all`, `uninstall --prune` and `prune` have no button |
| `configure` | Yes |
| `marketplace add`, `list`, `remove`, `update` | Yes. `--sparse` and `--claudeai` aren't on the screen |
| `init`, `tag`, `validate`, `eval`, `test`, `--data-size` | No: for writing plugins, at a terminal |

**Codex's**, checked on 0.164.0 with a local marketplace: `codex plugin list`, `add`, `remove`, `marketplace add`, `list`, `upgrade`, `remove`, and `plugin/read` and `config/value/write` for what a plugin brings and for `plugins."id".enabled`. **What differs:** one scope; no `update` of a plugin; no options; no `prune` (409 `not_for_codex`); it needs `plugin@marketplace`; what a plugin brings comes from its own read, with apps too.

## Skills from the phone (2026-11-02)

The owner: "Also skills, I think you can disable and enable, and something else I don't remember." Found, in Claude Code 2.1.316's binary and Codex 0.164.0's schema:

1. **The something else: Claude Code has four states, not two** (`skillOverrides`, what `/skills` writes): `on`; `name-only`, listed without its description, so it costs almost no context; `user-invocable-only`, hidden from Claude but `/name` still works; `off`, hidden from both. All four are here, in words.
2. **The list is Claude Code's own `/skills` menu**, from a process in the folder: name, description, source (user, project, plugin, built-in…), token estimate, state, and what locks it (the skill's own author, its plugin, a managed or flag setting). The request (`get_skills_dialog`) is marked internal in its protocol and isn't in the SDK's types, so **a later version may change it**; a missing answer lists none rather than breaking. A locked one says why and has no picker.
3. **Written through `claude edit-skill-overrides`**, which is what VS Code uses, into the **folder's** `.claude/settings.local.json`: as in `/skills`, it's per folder, and the screen has "Seen from". It reads the user's and a project's too but only writes local.
4. **Codex: on or off**, through `skills/list` and `skills/config/write`, into `config.toml`, for every folder. Source: user, project, built-in, plugin, managed.
5. **Found and not built:** `disableBundledSkills` (one setting, all of Claude Code's own skills off), and a skill's own frontmatter (`disable-model-invocation`, `user-invocable`), which is editing its file. Adding or deleting a skill is a file too, and **"no new way to reach files" holds**: a skill's SKILL.md isn't shown or changed. `claude plugin init` scaffolds one at a terminal. Codex's `skills/extraRoots/set` isn't used.


## The Nebula (2026-11-02)

The owner, of the constellation N that Atlas brought in the day before: "just seems like skeleton bones", "looks very basic". Of the directions in `docs/concepts/icons/` (branch `art-concepts`; that folder isn't merged) he chose **Nebula**: two heavy arms of swept light round a hot core, on violet. How it's drawn is in `docs/DESIGN.md`, "The logo" and "The icons". It replaces "For a later name" in "Atlas" above: the logo no longer spells a letter.

1. **No raster.** The launcher, monochrome and notification icons are VectorDrawables with the concept's coordinates and gradient stops as drawn. On the emulator the launcher's icon comes within 7 levels of 255 of the browser's on average, per colour, in each ring from the core out, and no banding showed at real size or on the 192dp splash.
2. **The app's logo isn't the icon made small** (a judgement call, for him to look at). At 11 to 34dp three translucent layers would be detail nobody can see (reasoned, not tried) and violet isn't in Atlas's palette, so it's the front layer alone, from Atlas's own tokens: ink at the core, `live` at the tips. **It isn't all `live`:** that's the colour of working, and two arms round a centre in it, beside a session's working mark on an assistant's heading, would read as one. Ink at the core keeps it the app's and not a state's.
3. **The splash shows the whole icon,** not the foreground alone, as it used to: alone, the glow ended in a hard ring at the splash's mask, on the page's near-black.
4. **Shortcuts stayed as they were:** New session's plus and a project's working mark never carried the N. On the launcher New session's shows as a small square in a white circle, as `ic_shortcut_new.xml` isn't an adaptive icon: older than this, and not changed here.
5. **`docs/images/social-preview.png`** is the old picture with the N's two places redone: the icon where the large N was, and Home's real mark from the demo server in the phone. Nothing else in it changed. The README's `shot-*.jpg` still show the N until they're retaken.
6. **`NovaMark` keeps its name** for now; the rename is a later job. Its size, tap and TalkBack label didn't change, and the 47 screenshots that changed differ only where the logo is, or where it shows through a blurred bar or sheet.
7. **Seen on an emulator (`hn-art`, Android 36, `swangle_indirect`):** the launcher on plain light and dark wallpapers, themed on both, the status bar and shade, the splash, onboarding, Home and an assistant's heading, with the demo server. **Not seen:** a real phone; a launcher's own squircle mask (this emulator's launcher offers only a circle, so the squircle was seen in a Compose render of the same resources, at 48dp, and in a browser in the concept).

## Environment variables from the phone (2026-11-02)

The owner: "add ability to add and toggle environmental variables … like a list where you can edit or delete, and a lot of them are boolean, and be able to add, similar to a to-do list."

1. **A list to tick.** Agents & providers › an agent's Settings › Environment variables. Name and value on each row, a switch for a flag, a tap to edit, a cross to delete, and an add line at the end. Adding a name with no value writes `1`, because a flag is what's usually being added, and the hint says so.
2. **A flag keeps its form.** The server does the flip, so `True` goes to `False`, `on` to `off`, a JSON `true` to `false`. The phone sends `flip` and never has to say a value.
3. **The server keeps the guarded list, and the phone asks it.** The app tries a change; a 403 `confirm_run` carries the exact change in words, which goes on the confirm, then the fingerprint, then the same request says so. No copy of the list to fall behind in the app. The lists, and how each was found: `docs/SECURITY-MODEL.md`, 20.
4. **Claude Code's precedence and what a running session sees are measured**, a real `claude` with a stand-in API asking it to run `echo`: local over project over user over the shell; a running session has a new or changed value at its next command, **and keeps a deleted one**. That was a process under the SDK. An interactive one goes through the same code in the binary (`settings.env` changing re-applies env), but wasn't run. The screen says this.
5. **Codex's real equivalent is narrower.** `shell_environment_policy.set` only reaches the commands Codex runs: not Codex, not its MCP servers. So `OPENAI_BASE_URL` set here wouldn't move Codex itself. `inherit`, `exclude` and `include_only` beside it aren't touched. Codex's writer turns an inline `set = { … }` into a table when it writes, and keeps comments. Measured with `codex sandbox` and `command/exec`. There's one scope.
6. **Codex's file isn't edited byte by byte.** Claude Code's `settings.json` is, by `jsonEditPath`, with a backup per change. Codex's goes through its own writer with `expectedVersion`, so a change elsewhere is refused by Codex itself; Undo writes the value back rather than the file.

## JSON mode (2026-11-02)

The owner: "there should be like a JSON mode where instead of a nice settings view, click on JSON mode and it'll just show you the raw JSON that you can edit, and it has simple JSON validation when you try to save." And: "you should also be able to collapse JSON in the JSON view, so it's a lot easier to go through a large amount of data."

1. **Where.** A List and JSON switch on Claude Code's settings screen. JSON is its own screen, `Route.AgentRaw`, per scope: user, project, local. Nothing on the chat screen.
2. **Secrets are spliced out by position, not by re-serializing,** so what's shown is the file's own text and a save can keep every byte the owner didn't touch. Saving what was shown changes nothing. The classes and what's masked: `docs/SECURITY-MODEL.md`, 21.
3. **The server judges the change, not the text.** Parse, hash, placeholders back, then compare old and new. A key that was already bypass and hasn't changed passes: otherwise a file holding one couldn't be edited at all, and on the list screens it can move off too.
4. **Unknown keys ask.** The plain list is short, and anything off it needs the fingerprint, so a key added by a later Claude Code doesn't slip through as plain. This means some harmless settings ask. The cost is one tap.
5. **The app also validates,** with its own small strict parser (`data/JsonFold.kt`), for the folding and for an answer before sending. The server's is its own, and it decides. Each gives a line and a column; neither quotes the text.
6. **Folding only decides what's drawn.** The text is a list of rows, each a piece of it, with a folded row keeping what it hides whole beside it. What's saved is those joined, and `JsonFoldTest` holds that this is the text back byte for byte whatever's folded. While typing, the rows stay as they are; they're rebuilt only when something folds or unfolds, and then the whole text is parsed again.
7. **If it isn't valid JSON when a fold is asked for,** everything comes back as one plain row with what was folded in it and a line saying why and where, and folding returns by itself when the text is valid. Nothing typed is lost. Folding off at the moment of an edit would have moved the cursor, so a broken text with folds on just says where it stops until the next fold.
8. **A long file opens folded:** over 40 lines, the top level shows and every level below is folded, so opening one shows its children folded in turn. Short ones open open. Every fold control is 44 by 44.
9. **A file that doesn't parse isn't shown at all,** since nothing in it could be masked. The screen says the line and column, and it's fixed on the server.
10. **Codex is left out.** The TOML reader doesn't look inside inline tables, so `env = { KEY = "…" }` couldn't be masked, and Codex's writer changes one key at a time, so a whole file wouldn't go through it. Its `set` and its model and permission keys are on the list screens.

## Code blocks wrap (2026-11-02)

The owner, of an email Claude drafted in a code block: it "didn't wrap so I had to scroll", "everything
should just wrap because I can just click on it and hold to copy". A code block's long lines wrap at the
screen's edge, and the block no longer scrolls sideways. A long press still copies the text as written,
with no breaks added. Tables keep their sideways scroll when wrapping can't fit their columns.


## The week's pace in the top bar (2026-11-02)

The owner, on an idea for the session's top bar: "you could see what day you're on and if you're ahead or
behind". And, choosing which to build: "do the week pace, git and this model's week". **Week pace** is an
item in Settings → Session top bar, off by default: `Day 2/7 +10%`.

This fits "No estimate of when usage runs out" (2026-11-01), which took away a red "2d 2h" that was a guess:

1. **A meter shows facts, and this is two of them subtracted.** The day is counted from the weekly reset, and
   the figure is the percentage used less the share of the week gone. No rate (it doesn't read `Usage.rate`), no
   forecast and no time to the limit. It says where the week stands, never where it's heading.
2. **It's its own item, not a second tag on the 7-day meter,** whose one tag stays the reset.
3. **Always a neutral colour, with no words on the bar.** The removed estimate was red, and red says something's
   wrong; 10 points over an even week may be fine. "Ahead", "behind" and "overcooking" aren't on screen either.
   TalkBack has to say the sign in words: "Day 2 of 7, 10 points over an even week."
4. **Only while it's real:** while the plan's week is about this session and known, as the bar's meters
   (`planShown`), so not stale or on another provider, and never once the reset has passed, until new numbers
   come. A Codex session uses Codex's own week, as Home's Codex card does, while signed in, and shows nothing
   on a plan without a week.

With no week from the server, or one that's over with the next not in yet, the item doesn't draw (`Usage.weekReported`). It used to read `Day 1/7 +0%` off stand-in numbers. The owner agreed to that fix on 2026-11-02.

## Git in the top bar, asked at calm moments (2026-11-02)

The owner: "do the week pace, git and this model's week". **Git** is an item in Settings → Session top bar,
off by default: `⎇ main +120 −30`, from what `GET /v1/sessions/{id}/changes` already returns. No daemon change.

1. **The route isn't cheap, so it isn't polled.** Each ask spawns five to seven git processes (`daemon/src/changes.ts`:
   `rev-parse` twice, `symbolic-ref`, `status -uall`, `diff --numstat`, and the git line's two, which alone have a
   5-second cache), walks the folder, and reads each untracked file up to 1 MB to count its lines.
2. **When it's asked:** the chat opening, or the item turning on; a turn ending; an edit tool finishing during a turn, at most
   once a minute, 1.5 s after the edits settle; activity outside a turn, for a whole turn the phone slept through;
   and the link coming back. Never with the item off, not even once, and not while the Mac isn't linked.
3. **Not on a timer.** A composition stays alive with the phone in a pocket, so a slow interval would go on asking
   from there. A turn that only reads asks nothing.
4. **Why it differs from the session sheet's GIT part,** which doesn't ask during a turn at all: the sheet is opened to
   be read, while the bar is there all along, and `+120 −30` standing still for an hour-long turn would be a stale
   fact on screen. What Bash changes (`sed`, `git commit`) isn't seen as an edit, so it shows when the turn ends.
5. **Nothing when there's nothing true to say:** a folder that isn't a repository, or the route failing, draws no item and no
   error. A failed ask clears an earlier answer rather than leaving an old one up.
6. **The counts are the route's:** `totalAdd` and `totalDel`, so while it can tell, only the files this session's tools
   touched, as What changed lists them. Colours as What changed's (`+` mint, `−` red). A worktree's name is for TalkBack.

## This model's week in the top bar (2026-11-02)

The owner: "do the week pace, git and this model's week". **This model's week** is an item in Settings → Session top
bar, off by default: the week of the model the session runs, when it has one of its own (`FABLE 41% ↻ Tue 20:00`),
in the compact meter the 5-hour window and the week use, with the bar, and the model's short name as its label. The reset is as the
model's meter on Home has it: a weekday and time more than about a day off, a countdown nearer. It's matched by family
as the model pickers do (`ModelCatalog.week`). It hides on a model with no week of its own or none known yet, and whenever
the plan's windows aren't about this session (`planShown`), so never for Codex. `Usage.models` is read off Claude Code's
/usage screen every 15 minutes, so it can be that old, which Settings says; the bar doesn't ask for a fresh look.

## Delete takes a session off Home, History brings it back (2026-11-02)

The owner, reversing "Delete for every finished session", item 3 (which was decided here, not by him):
"no, delete means remove from the active agents view, but I can always pull it back from history,
otherwise how would agents go to history? The main list view should always have all items Claude agents
view would have, and history acts like the resume slash command for all." This replaces that item 3,
and in "Rename, Stop and Delete for Codex sessions" its item 2.

1. **Home mirrors `claude agents`.** Every background job `claude agents` lists is a card, whatever its
   state and however old, but for what the owner removed here and the rewind's replaced original
   ("Rewind": his decision that the new one fully replaces the old). What 2.1.316 lists (its code and
   https://code.claude.com/docs/en/agent-view): every folder in `~/.claude/jobs`, finished, failed and
   stopped ones too, until `claude rm`; a shell job (`claude --bg --exec`) goes by itself minutes after
   it ends. Two gaps closed: a job whose SessionEnd hook came before its process went stayed `ended`,
   so a stopped job sat in History while `claude agents` still listed it; and a job resumed with no
   prompt reads "blocked" there and would have asked for the owner as if Claude had a question.
   **Not fewer, kinds kept:** Home also keeps what it showed before and `claude agents` doesn't: a plain
   `claude` open in a terminal (`claude agents --json` lists those, its screen doesn't until it's
   backgrounded), a session in a shared terminal, one the daemon runs, Codex's, and one a restart cut
   short. Nothing visible today was dropped. Not on Home: `claude agents`' cloud sessions, which its
   `--json` doesn't give. With `settings.claudeAgents` off, jobs aren't read at all, as that switch says.
2. **Remove from list.** "Delete" misled now, so the menu and the swipes say Remove from list, the
   confirm says "Remove “x” from the list?", and each body says what it stops and that "the
   conversation stays in History: a tap there resumes it". What it does to a process is as before: a
   job's `claude rm` (it stops first, and takes the job from `claude agents`, and its worktree when
   that's safe; Claude Code's refusal still comes back), a shared terminal closes first, a terminal
   Hypernova doesn't hold still says to close it there. A session already in History offers none.
   `<support>/deleted-sessions.json` keeps its name and means "removed from Home": it keeps a late word
   from its stopped claude, or a restart's look at its transcript, from putting it back on Home. Clients
   get `{t:"session"}` ended, not `session.removed`. A `claude rm` in a terminal goes to History too.
3. **Codex too:** nothing here deletes a thread any more (`thread/delete` was for good). A turn running
   stops, its terminal closes, and the thread stays in Codex and in History. A `codex delete` elsewhere
   still makes Hypernova forget it: that one is gone.
4. **History is /resume for all.** Every transcript /resume would offer, from every project, however
   old: past the start's 30 days and 300, the rest come in just after the daemon starts, read a few at
   a time, then one fresh snapshot goes to the phones (`discoverOlder`). Past that reach only what
   /resume offers comes in, so `claude -p` and Agent SDK runs, agents' and teammates' transcripts,
   `/loop` sessions and files with no message don't flood it; those inside the old reach stay, as they
   were visible before. An automation's runs are as they were: one folded row per automation. Search
   covers them all. A rewind's replaced transcript stays hidden.
5. **(A tap no longer resumes: see "History's tap opens to read", the same day.)** `POST /v1/sessions/{id}/resume`:
   no prompt, no turn, and it's on Home. A `claude agents` job under the same id with the setting on (as /resume
   in the agent view: `claude --bg --resume <id>`), else a shared terminal. Resuming takes its line out
   of the file, as a prompt typed into it and `claude --resume <id>` from a terminal do. Its folder gone
   (`claude rm` takes a worktree): it says so and stays in History.
6. **What was deleted before comes back:** every line already in the file shows in History on the first
   start, however old, even one /resume wouldn't offer. The upgrade writes nothing to the file.
7. **There's no way from the phone to delete a transcript for good now.** He didn't ask for one; none
   was added.
8. **All servers:** History lists each computer's the same way, and a tap resumes on the row's own
   computer; a row offline only opens as last seen.

## The desktop app's size on a big screen (2026-11-02)

The owner, running it on Linux on a 4K LG monitor: "the text is too small, everything's too small, I wonder if it's because I'm on a 4K screen." It was: nothing told the JVM to scale, so one dp was one of 3840 pixels. `GDK_SCALE=2` doubled it, sharply, on a virtual screen of that size.

1. **The scale is worked out, first answer wins (`autoScale` in `desktop/.../Scale.kt`):** what the JVM already scales by if that's over 1 (`GDK_SCALE`, `J2D_UISCALE`, `sun.java2d.uiScale`, a Retina screen, Windows' setting), left as it is. Not Linux: 1, as a Mac and Windows tell the JVM their real scale. The desktop's text DPI (`Xft/DPI`, through AWT) if it's 144 or more, over 96. The screen's DPI (`Toolkit.getScreenResolution`) if it's 125 or more, over 96. Otherwise the monitor's short edge: 3240 pixels or more is 3, 2160 is 2, 1800 is 1.5, less is 1. The two DPI answers go to the nearest step and leave a short edge of at least 900 dp, so a 15 inch 1080p laptop stays at 1, and a screen a compositor already scales up isn't scaled much again.
2. **So a 4K panel is 2, or 1.75 at 27 inches and 1.5 at 32 where X tells the truth about its size.** X usually says 96 or thereabouts whatever the panel is, and that can't be told from a real 96, so a low DPI means nothing: a 43 inch 4K screen is 2. A DPI is one number for the whole X screen, not a monitor, so with two monitors it's rarely any use.
3. **No subprocess for GNOME's setting.** The JVM reads GNOME's whole-number scale itself, from memory of its source and not tried on a real GNOME here, and a fractional one in Mutter has no simple read. Xwayland's `Xft/DPI` under a scaled GNOME wasn't tried either.
4. **A `LocalDensity` around the content (`Scaled`), not `sun.java2d.uiScale`.** That property has to be set before AWT starts, and AWT is what measures the screen. It also couldn't change while the window is open, as zooming and a move to another monitor need. Compose lays out, draws and takes the pointer in pixels, so only what dp and sp come to changes: text is drawn at its size, not stretched, and a click lands where it's aimed. A window is still sized in the JVM's units, so its sizes are worked out from the two scales' ratio.
5. **Zoom: `Ctrl` `+`, `Ctrl` `-`, `Ctrl` `0`, in steps of 0.25 from 0.75 to 3.** Reset goes back to automatic. The level shows for a moment at the top. The choice is pixels per dp, one number, in its own settings file (`window`): it beats the automatic scale on every monitor, so with two different monitors a chosen zoom doesn't follow the move. The keys also send a character after the key, which is held back too: without that, `Ctrl` `=` typed an `=` in the pairing field, and only a real keyboard event showed it.
6. **The window:** 960 by 720 dp at the scale, centred, cut down to what's free of the screen. The smallest it goes, 520 by 420 dp, follows the scale and is cut down the same way. Moving to another monitor changes the scale of what's in it and not the window's size in pixels, as resizing under a drag fights the window manager: from 4K to a 1080p monitor it hangs over the edge until it's resized. Zooming doesn't resize it either, unless the smallest size pushes it.
7. **The pair screen and the list keep their widths (560 and 720 dp).** The small card in a huge window was the scale: on a maximized 4K window at 2 it's 1120 pixels of 3840, the proportion a maximized 1080p window has, and the first window is mostly card.
8. **The `.deb` installs where there are no menus.** It was thought to be missing `xdg-utils`, but jpackage already depends on it. The failure was `xdg-desktop-menu` exiting 3, "No writable system menu directory found", on a system with no `/etc/xdg/menus`, under `set -e` in the package's scripts, so it could be neither installed nor removed. That's a container or a bare server, not a normal desktop. `desktop/packaging/deb-menu-optional.sh` makes that call's failure a printed line, on the finished package, as Compose empties jpackage's resource folder just before running it.
8. **All servers:** History lists each computer's the same way.

## History's tap opens to read; Smart swipes Codex too (2026-11-02)

The owner took the coordinator's suggestions on what "Delete takes a session off Home" left for him.

1. **A tap in History opens the conversation to read, with nothing started.** A look shouldn't start a
   claude, and every tap would have put the session back on Home. Replaces that section's item 5. All
   servers and offline rows the same: a row opens on its own computer.
2. **Resuming is writing in it,** the way an ended session's chat already had: a prompt from the box
   (`POST …/prompt`), the same id, in a new shared terminal (one that was a job as a job: see "A resumed
   job comes back as a job", the same day), back on Home. Its line in
   `deleted-sessions.json` goes then. No button: nothing new on the chat screen.
3. **It's plain that it isn't running:** the header already says Ended, and the only change on the chat
   screen is the words of the box's existing hint on an ended session, "It isn't running. Write to resume
   it…", a deliberate small exception to that standing rule, as this is what says how.
4. **`folder_gone` says so as it's written:** `POST …/prompt` on an ended session whose folder has gone
   is 409 `folder_gone`, shown as the toast a refused send has, the words back in the box, and it stays
   in History and removed. Before this a holder was started in a folder that isn't there.
5. **`POST /v1/sessions/{id}/resume` stays** for a client that resumes with no prompt; this app no longer
   calls it.
6. **Smart's swipe no longer skips a Completed Codex card:** it removes it, asked first, since removing
   is no longer for good. Replaces "Smart still keeps a Completed Codex card from swiping" (2026-10-31).
7. **Left as they are:** no delete for good from the phone, older Codex threads not in History
   (docs/LATER.md); recent `claude -p` and SDK runs stay in History; `claudeAgents` off still hides jobs.

## A resumed job comes back as a job (owner, 2026-11-02)

The main list mirrors `claude agents`, and History works like `/resume`. Item 2 above put every session written in back in a shared terminal, so a job came back on Home and in `claude agents --json` but not on the agents screen. Now one that was a job comes back as a job.

1. **Writing in one that was a job (`POST …/prompt`, settings.claudeAgents on)** takes the path `…/resume` takes: `claude --bg --resume <id> --name <its title>`, then the words through `claude attach`. Same id, same transcript. Everything else, a plain terminal's or a phone session's, stays in a shared terminal. So do a session on another provider and one carrying a switch's context, as `…/resume` already had it.
2. **How it's known: Claude Code's own stamp, not a list of ours.** Each message a job writes has `sessionKind: "bg"`, measured on 2.1.316: a plain terminal writes none, a job later resumed in a plain terminal keeps its old lines' stamps, and a `--fork-session` copy has none. Keeping a file of job ids was the other choice, but it would miss every job from before it existed, or that came and went while the daemon was down or the setting was off, and it's more state to keep right. `agentJob` can't say, as it goes with `claude rm`.
3. **The cost of that:** the stamp isn't documented, though the daemon already reads `sessionKind` for /resume's list (`resume-offer.ts`). If Claude Code stops writing it, jobs quietly come back in shared terminals, which is today's behaviour and loses nothing. `test/resume-job-e2e.test.ts` runs the real Claude Code and fails if it does. The whole transcript may be read to answer, but only when someone writes in an ended session.
4. **One gap, left:** a plain session resumed as a job with no prompt, then removed, wrote no message as a job, so it has no stamp and comes back in a shared terminal.
5. **What stays:** `folder_gone` and the session staying in History, the words back in the box on any refusal, its line leaving `deleted-sessions.json`. A failure after the job started leaves a job: writing again attaches and doesn't start a second one. A held prompt still never resumes anything.
6. **It takes longer than a shared terminal's answer,** since the route waits for the job and for `claude attach` to show its box: about 4 s measured here, and the words are in the conversation when it answers.

## A time limit on each of macos-unit's tests (2026-11-02)

`macos-unit` hit its 25-minute job limit and lost the whole result: something hung, and nothing says what. `npm test` stays as it is for everyone. Only that job goes through `scripts/test-with-limits.mjs`.

1. **5 minutes a test (`--test-timeout=300000`).** Measured here, on 12 cores with a load of 30 to 55: of 1243 tests the slowest were 70 s (real Claude Code, skipped on CI), 65 s, 36 s, then 16 s and under. The longest limit a test sets itself is 240 s, and a test's own limit comes first, so 5 minutes doesn't cut any of them short. The Mac runner's speed wasn't measured, which is why it's about four times the slowest. It's a limit on each test, not each file.
2. **`--test-force-exit` was tried and isn't used.** It was the obvious way to stop a timed-out test's timer or socket keeping its file's process up, which otherwise leaves the runner waiting for ever. But it exits once the tests known so far are done, so a file that awaits between its tests silently loses the rest: 19 of 1243 tests vanished here, with nothing failing and the count merely lower. A check that quietly runs less is worse than a slow one. It wouldn't have been enough anyway: measured on Node 24, a leftover child holding its stdout still kept the runner reading.
3. **So the script has a limit for each file, 10 minutes, and one for the run, 19.** Past the first it ends the file's process and what that started, and anything of the run left without its parent that's as old as the limit, and the runner reports that file and goes on to the next. That covers a file that hangs before its first test too, where no test's limit runs. Only the runner's own process group is ever signalled, by pid. The run's limit stops the lot and says what was still going. Results from a file come in order, so a hung file holds back those after it until it's stopped, but they aren't lost.
4. **What can still happen:** a leftover child that started its own process group and holds a pipe to the runner isn't seen by the file's limit, so the run's limit ends it, and files after the stuck one are lost then. Nothing in the tests was found doing that (none uses `stdio: 'inherit'`). Two hung files at once take 10 minutes of the run each. The limits are checked on Linux only: the Mac run hasn't happened yet, so which test hangs is still unknown, and that a Mac's `ps` takes the options used here is from memory, not a run. If it doesn't, the file's limit does nothing and the run's limit still stops everything.
5. **19 of the 25 minutes** leaves the job's earlier steps about 5, and the summary is printed by the same step, so it can't be skipped. `continue-on-error` stays. The summary is in the log and on the run's page: passed and failed, what ran out of time and where, what failed, what was stopped, the ten slowest.

## Node's floor (2026-11-02)

The owner: "the Node minimum". The floor moves to the October 2026 security release, confirmed against nodejs.org's release index on the day: 22.23.4, 24.22.1 and 26.12.1, all dated 2026-10-20 or 21. `engines` is `^22.23.4 || ^24.22.1 || >=26.12.1`.

1. **Each line has its own floor, not one number.** "24.22.1 or newer" would have let in 25.x and 26.0 to 26.12.0, which don't have the fixes. 25 is past its end of life with no October release, so it's out.
2. **The installers and the doctor stay on 24 and up,** as they were, and the 22 line is only for running from a checkout. A server on an older 24 downloads the newest 24 at its next install or update, and `hypernova doctor` fails until then. The old one is left where it is.
3. **The download can't land below it.** `pickRelease` skips releases under the floor, and `install-linux.sh` and `install-macos.sh` check the Node they just unpacked. This only matters for a mirror (`HYPERNOVA_NODE_DIST`): `latest-v24.x` is already past it. A new floor is a change in three places, `node.ts`, both scripts' `node_ok` and `engines`, and `test/node-floor.test.ts` holds them together.

## A blank answer to a GET is a failure (owner, 2026-11-02)

`Api.json` read a blank 2xx as `{}`, so a blank `GET /v1/snapshot` (a proxy, a reply cut short) emptied the session list. Now a blank answer to any GET fails with `empty_body`, so the phone keeps what it has and reconnects. Every JSON GET in the daemon goes through `json()` and has a body, so none relies on the old reading. A blank answer to a POST, PUT or DELETE still reads as `{}`: there it says only that it's done, and callers that need more already check for it (`no_session`, `no_url`).

## The workflows' actions (2026-11-02)

The actions move to their current majors, all on Node 24: checkout 7, setup-java 6, setup-node 7, upload-artifact 7, configure-pages 6, deploy-pages 5, upload-pages-artifact 5. Their release notes were read for what breaks our use, and none does: no job pushes with checkout's saved credentials, and no job checks out a fork on `pull_request_target`; a `package.json` without `packageManager` doesn't get setup-node's automatic cache, and these jobs name `cache: npm` themselves; the Pages site has no dotfiles for upload-pages-artifact to drop.

`gradle/actions/setup-gradle` is 6 with `cache-provider: basic`. Since 6, its default cache is `gradle-actions-caching`, a proprietary component under Gradle's own terms, which using it accepts. `basic` is the MIT provider, a thin wrapper on `actions/cache`. What it costs: it has no restore keys, so a change to a build file or the version catalog starts from an empty cache and downloads everything once, and it has no cleanup or deduplication. Whether it saves only from `main` as before is only known once it has run on GitHub.

## AGP 9.5 with Gradle 9.8 (2026-11-02)

AGP 9.5.0 and Gradle 9.8.2 move together. The wrapper keeps its checksum, the one services.gradle.org publishes for that zip. The build scripts' own deprecations are gone: `directories` for `srcDir`, `android { }` for `androidLibrary { }`.

Left for AGP 10 and Gradle 10, since none of it is ours to change in a line or two: Compose's hot-reload plugin sets a `Configuration.setVisible` (gone in Gradle 11) and uses an attribute type that fails in Gradle 10, which needs a Compose Multiplatform that fixes it; and `compose.desktop.uiTestJUnit4` wants the dependency named directly.

## Mermaid 12 (owner, 2026-11-02)

The owner: "also bump up Mermaid". 12.2.0 replaces 11.17.2, its file checked against npm's published checksum.

1. **The look: ELK's layout and the `neo` look, with `neo`'s shadows and gradient off.** Compared on our dark theme against `layout: 'dagre', look: 'classic'`. ELK's edges run square and stay clear of nodes and labels, where dagre's curves cross labels. It is also narrower, and that decides it on a phone, since a diagram wider than the column shrinks along with its text: 380 wide against 461 for a small flowchart, 744 against 886 for 40 nodes, 1020 against 1132 for a long left-to-right one. `neo`'s default soft shadow reads as a glow and isn't Atlas, so it's off (`dropShadow`, `useGradient`, and CSS for the filter the sequence diagram sets itself). That filter was also a bug: on WebView 133, the picture cut the shadowed box off.
2. **The cost.** `mermaid.min.js` carries ELK whether or not it's used: 3.6 MB becomes 5.5 MB, and the release APK grows by about 0.6 MB. The page got ready in 0.8 to 1.1 s against about 0.7 s on a desktop. A slow phone may show the code a little longer before the picture; past 20 seconds, the picture waits until the chat is opened again, as before. A diagram with an explicit `layout` of its own keeps it.
3. **ELK's licence is new here:** elkjs is EPL-2.0, weak copyleft, the only such licence in the bundle. Its text is in `third_party/mermaid/LICENSES-mermaid-bundled.txt` and `THIRD-PARTY-NOTICES.md` says where its source is.
4. **An old WebView gets the code, not a blank.** Mermaid 12 is built for ES2024, and `minSdk` is 29, whose first WebView is far older, though Play updates it. A page that can't load Mermaid or `render.js` used to say nothing, so the code showed but the app waited 20 seconds each time. `frames.js`, in syntax any WebView reads, now answers every diagram as failed once the page has loaded. Tested by breaking each file in turn in current Chromium, and with `Object.groupBy` removed, where only the use case diagram fails and shows its code. A missing call inside a drawing is already a failure.

## The SDK's usage call (owner, 2026-11-02)

The owner: "try the usage API", meaning `usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET()`. It was tried, and **each look at the plan's usage now asks it first, with the `/usage` screen as the automatic fallback** (`daemon/src/usage-api.ts`, `usage-probe.ts`). The scraper stays. The phone hears no difference.

1. **What the real account showed.** Four reads over about 8 minutes: each took 0.5 to 0.8 s, cost 0, none rate limited. They matched the scraper at the same moment: no 5-hour window, week 34%, Fable 0%. Meanwhile the screen's per-model read was being rate limited every few minutes ("Per-model breakdown unavailable"), and this call wasn't. That's what decided it.
2. **It sends no prompt.** Against a stand-in for `api.anthropic.com`, the only requests were `GET /api/oauth/profile`, `/api/claude_cli/bootstrap`, `/api/claude_code_penguin_mode` and `/api/oauth/usage`, with nothing to `/v1/messages`. `persistSession: false` left no transcript. It runs in the probe's hidden folder with `settingSources: []`, hooks and MCP servers off, and `skipBehaviors: true`, so the local transcripts aren't scanned. How often it looks hasn't changed.
3. **It reads only what's documented:** `five_hour`, `seven_day`, `seven_day_sonnet`, `model_scoped`. The real answer has about 33 keys under `rate_limits`, most with internal names; those are ignored, and so is `seven_day_opus`, which the screen doesn't show. Percents are floored as the screen floors them, so a reading merges the same either way.
4. **Anything unexpected falls back, whole.** No method, a throw, a timeout, a wrong shape, `rate_limits` null (a 429 with nothing saved). So does an answer with no `model_scoped`: that's how one rebuilt from saved data looks, it says nothing else about its age, and its windows could be hours old. No partial reading is ever taken from it. If the screen can't be read either, the last good reading stands, marked stale as before. Each look logs which way it went.
5. **Its time is held back a minute.** An answer can be a snapshot up to 60 seconds old (`SNAPSHOT_MS`, read from Claude Code 2.1.316's code) with no time on it, and looks exactly like a fresh one. A reading is taken as true a minute before it was asked for, so it never overrules a newer status line. If a later Claude Code keeps snapshots longer, this number is wrong without anything failing.
6. **Under `node --test` the real call refuses to start**, so no test can reach the real account by forgetting its stand-in.


## The server's debug log (2026-11-02)

The owner: "also have a debugging mode for the server, so have two separate agents, one debugging for the server and one debugging for the clients." This is the server's. The clients' is its own (`POST /v1/client-logs`, `hypernova client-logs`, the app's Debug logging section).

1. **A log of its own, `debug.log`, beside `daemon.log`,** rolling at 5 MB through `.1`, `.2`, `.3`: 20 MB at most, the oldest first. The everyday log and its lines don't change, other than one line when it's turned on or off, and a day of debugging doesn't push them out. Times line the two up.
2. **Off by default, off by itself after 24 hours.** As he asked. `--hours` goes up to 24, not beyond, so the promise holds however it was asked for. A saved time further off than 24 hours is cut to it at start. Asking again starts the 24 hours again. A line looks at the clock, and so does a look once a minute, so it ends on time with nothing being logged and after a sleep.
3. **Three switches:** `hypernova debug on|off|status`, the setting (`settings.debugUntil`, through `POST /local/settings`, surviving a restart) and a row in the server's section on the phone. Nothing on the desktop app, which has no such screen. With the daemon down the command writes the setting for its next start: a daemon that dies at start is when this is wanted most.
4. **The rules are the write's, default-deny.** No secrets, no conversation, "code must enforce them": every string is its size unless a named field may hold a word, and that word has to look like one (docs/SECURITY-MODEL.md, "The server's debug log"). Session names and project paths count as content, so sessions and devices are their ids, cut to 8, and a folder's last part isn't used even where it could be.
5. **An error's message is its size,** unless it's one of V8's own wordings. Less to read while debugging, but a message can quote anything, and Node's own JSON errors quote the text they choke on. The stack's frames, the class and the code say where.
6. **The bundle filters the everyday logs by the daemon's own words,** read from its `log()` calls at the moment of writing. Scrubbing those logs for what's bad can't work: a prompt on stderr looks like any sentence. So it's what's known that stays, and a line reads as gaps sooner than it leaks.
7. **Writing is queued, batched and off the event loop,** with lines dropped and counted past 2 MB waiting, never waited for. Measured: 0.3 µs a line off and about 50 µs on. A real daemon flat out at about 5,000 requests a second, on a busy machine, with a line for each: no fewer requests, the loop's median delay 1.1 ms to 1.8, its 99th percentile 9 to 15.
8. **No route sends these lines to a phone.** As he asked. An agent or the owner on the server reads them, or sends a bundle.


## A debug log (2026-11-02)

The owner: "some kind of debug mode for the front end client where it logs a lot more details, and then you can pull those logs." He chose both ways to pull them: "send to the server, and share the file."

1. **Off by default, on both clients, and it turns itself off after 24 hours,** showing when. It picks up where it was across a restart, until then. A clock that puts the end further off than 24 hours counts as over.
2. **What goes in while it's on,** one line an event, in plain words where practical, through `DebugLog` in `android/shared`: the app's version, system, model and build at the head of each file and at each start and stop; the connection log's events, TLS handshakes, the live socket opening, closing and failing, network changes; each request's method, route with its ids, status, duration, sizes and a request id if there is one; each live-feed message's type and size; screens, foreground and background; errors with stack traces, caught ones too. The platforms' `Log` seam gained levels and hands its lines on, so what the data layer already logged is in it.
3. **No secrets, enforced in one place:** `DebugScrub`, for every line just before the disk, with a test that plants each kind of secret and reads the files. Device tokens and pairing codes are also cut by value.
4. **No conversation content, and no option to include it.** Prompts, replies, commands, file contents, file names beyond what a route's id shows, tool arguments and **session and project names** stay out: sizes and ids only. A prompt has no shape to scrub, so this rests on what call sites log, on exceptions' messages being left out unless they're the network's own, and on a test planting them in a real feed. Settings says all this, so he knows what a log holds.
5. **Staying local:** it goes only to the server he picks or where he shares it. No cloud service, no automatic upload, nothing without a tap.
6. **On the server:** `POST /v1/client-logs`, a device's route like any other, 6 MB, 10 tries an hour, plain text only; saved under `<support>/client-logs/<device>/`, 0700 and 0600, a name of the server's choosing; the newest 5 per device, 30 days; treated as untrusted text. `hypernova client-logs` finds them, which is what lets him say "check the logs" to an agent on that server. One way: no route reads them back. An older server gets "needs updating", and Share still works. Forgetting a device leaves its logs until they age out: they may be why it was forgotten.
7. **Crashes are saved with the switch off,** stack trace only, in a small file of their own, and go along next time logs are sent or shared. An uncaught exception handler, which hands on to the one before it whatever happens, so a crash goes as it did. A saved crash isn't removed by sending it, only by Clear: a second send still has it. Native crashes and ANRs don't reach it: Android gives a JVM handler neither.
8. **The size:** about 5 MB, five files of 1 MB, oldest first. Off the main thread: a call site queues its line without blocking, a background thread at the lowest priority does the scrubbing and the disk, flushing after each batch, so a crash loses at most what's still queued. A burst past 8,192 queued lines is dropped and counted in the log, rather than slowing the app. Off, a call site reads one volatile field and builds nothing.
9. **Share, on a phone:** one bundled file in a cache folder that holds nothing else, a read grant for its URI alone. FileProvider can't name a single file as a root (its path logic needs a folder), so the folder is the root and it never holds a second file. The log's own folder has no URI at all.
10. **A stop can't always be logged.** Android ends a process without telling it. A phone logs a stop only when its last screen finishes, and otherwise the next file simply starts again. A desktop logs one on the way out.
11. **The desktop app had no Settings, so it got one,** with this as its only section, reached from the top bar and from the pairing screen: a pairing that won't go is one of the things this is for. Open folder and Copy path instead of a share sheet.
12. **Not everything is a line of its own:** a terminal's frames aren't logged, only its socket opening and closing, and the sockets a terminal page opens itself on the base URL (no longer a route a phone normally takes) aren't seen at all. OkHttp doesn't tell an event listener about a WebSocket's upgrade, so a socket's TLS is read from its `onOpen`, and one that fails in the handshake says so through the connection log.

## A 200 from a toggle means it's in the file (2026-11-02)

`daemon/test/mcp-e2e.test.ts` failed about one run in four on a loaded machine: `POST /v1/agents/mcp/toggle` answered 200 and `disabledMcpServers` hadn't changed. So the phone could be told a server was on when it hadn't been saved yet.

1. **The cause, measured in a throwaway home.** `toggleMcpServer()` resolves before Claude Code writes: 2.1.316's handler starts the save of `~/.claude.json` and doesn't wait for it, whichever way it goes. In 30 toggles under load, 12 weren't in the file when it resolved, each there within 400 ms. Our process was then told to stop at once. **Not a lost update:** 36 toggles with a probe just stopped and a `claude mcp get` running beside them never went right and then wrong, because Claude Code's writer re-reads under a lock. Aborting straight after didn't lose one in those 30 either, since its exit flushes, but nothing promises that.
2. **The fix.** The process stays up until the file says so, polled every 50 ms for up to 8 seconds, read under the key Claude Code files a folder by (the repository's root, else the folder). If it never does, a new process tries once: the same one has the change in memory already and would write nothing. After that, 502 `not_saved`, which says the server is as it was. The test's assertion wasn't touched.
3. **The other routes of this shape, checked.** Only this one goes through a process that doesn't wait. `claude mcp add-json`, `remove`, `reset-project-choices`, `logout`, `claude plugin …` and `edit-skill-overrides` await their write before they exit. Approval and the settings files are the daemon's own atomic writes. Codex's `config/value/write` answers after it has written. Tightened anyway, since it costs a read: a remove and an Undo look at the file afterwards as an add already did; a skill's change needs Claude Code's `ok`, and Codex's `effectiveEnabled` to match (409 `not_saved` when a higher layer decides it).


## The desktop client's packages (2026-11-02)

The owner: "let's also get a proper desktop client release on the CI." `.github/workflows/client-packages.yml`; what each job does and what's proven: docs/RELEASING.md, "The desktop client's packages".

1. **Built like the server's, and for the same reason.** It's called with `needs: apk`, so a client file that fails can't stop or undo a release, turns the run red, costs only itself and leaves a "Not built" line. A pull request or a manual run publishes nothing.
2. **One `checksums` job, in `android-release.yml`, after both package workflows.** It used to be the last job of `server-packages.yml`. Two would race: each reads the release and writes `SHA256SUMS`, so the slower could leave out the other's files, and both would edit the notes. `needs` on both, with `always()` and the APK's job having passed, means it reads everything that's coming, and re-running a failed job re-runs it. Its "Not built" lines are now replaced each time (`scripts/release-not-built.sh`, tested in `scripts/test-next-version.sh`), so a file that comes late loses its line. A `.deb` or Mac file that fails on the server's side says so as before, at the end of the notes; the client's goes under Client.
3. **A Mac's version is one major higher than the release's.** jpackage refuses a first number of 0 (`CFBundleVersion.java`, read in JDK 21's source, 2026-11-02), and Compose's own check doesn't catch it, so `0.23.0` would fail late on the runner. `M.m.p` becomes `(M+1).m.p`: always ordered, simple to read back, and no two releases share one. Moving 0.x up to 1.x only would collide with the real 1.0.0. What the app says it is, to a server as it pairs, stays the release. `macOS.packageVersion` covers both the app and the `.dmg`.
4. **An `.msi`'s version stays the release's, up to a fold.** Compose's and Windows Installer's limit is 255.255.65535. With 20 minors from 2026-10-31 to 2026-11-02, 255 isn't far away, so from a minor of 255 it folds into the third number, minor-255 times 64 plus the patch: `0.256.1` is `0.255.65`, still ordered. At `0.1279.0`, or a patch of 64 in that stretch, it can't fold and the `.msi` fails to build by name, costing only itself, until a major resets it (or, for a patch, the next minor). Worked out only on Windows, and only that system's format is named to Compose, since Compose checks the version of every format named on any system, which is how a Linux build first failed on it.
5. **Installed for everyone on a Windows PC, in `C:\Program Files\Hypernova Client`, with User Account Control's question.** jpackage's per-user install goes in `%LOCALAPPDATA%\Hypernova Client`, which is already this app's data folder with the device key in it (`DesktopStorage.kt`): the two shouldn't share a folder, and `DataFolder`'s name mustn't move (a rename mustn't strand a key). A fixed `upgradeUuid` makes a new `.msi` replace the old.
6. **The names are in `android/desktop/packaging/client-names.sh`**, read by Gradle and sourced by the workflow and the scripts: `hypernova-client` and `Hypernova Client`, the second for what a Mac and Windows show. The `.app`, `.dmg` and folder names follow. What must not change with a rename, `upgradeUuid`, `bundleID` and the data folders, is said where it's set and in docs/RELEASING.md, "Names".
7. **Pairing is tested in the installed Java, on all three, with no keyboard.** The bundled runtime has no `java` to run (`lib/runtime` has `lib` and `conf`), and typing into a window needs something different on each system, each with permissions of its own. `PackageProbe`, in no package, goes in by `JAVA_TOOL_OPTIONS=-javaagent:` on the installed launcher, so the launcher, the runtime and the jars are those installed. It runs `Shell`'s real pairing against the demo server, which is mutual TLS and an EC key, and exits before the app's `main`. It reports `java.home`, which has to be in the install. Then the real app starts on that pairing, and the demo server has to say a certified device connected: a one-line log it gained for this. That's the one change outside the packaging, and the app is unchanged.
8. **No Android SDK on these jobs.** `:desktop` configures and builds without one (tried with `sdk.dir` and `ANDROID_HOME` gone), and the arm64 runner has none. Compose brings WiX 3.11 itself. `windows-latest`'s image doesn't list .NET Framework 3.5, which that WiX's own guide asks for: unknown whether it's needed, so a failed build turns it on and tries once more, with a warning that says so.
9. **Licences.** The root's `THIRD-PARTY-NOTICES.md` already had a table for the desktop client, and it needed the Java runtime (GPL-2.0 with the Classpath Exception) and what `libskiko` holds. Reading the strings in Skiko's Linux x64 library found HarfBuzz, FreeType, ICU, libpng, libjpeg-turbo, libwebp, Expat, zlib, Wuffs and PIEX inside it beside Skia, and their licences want their text in a binary's documents: `packaging/legal/LICENSES-skia-bundled.txt`, fetched from each project on that day. The fonts' licences go too, with the root's three, in each package's `resources/legal`. The runtime's are in its own `legal`. The source of the runtime is named by link, not shipped: whether that's all the GPL asks of a redistributor here is the owner's to judge, as nobody has checked it with a lawyer.
10. **The `.deb`'s dependencies are Ubuntu 24.04's names**, as jpackage reads them from the system it builds on, so `libpng16-16t64` and so on. It installs and runs on Ubuntu 24.04 and Debian 13 (tried), and `apt` refuses it on Debian 12 (tried). The tarball is for the rest: on Debian 12 it opened, once `libXi` and the like were installed. The libraries are listed in the workflow, as it would otherwise depend on the runner's image.
11. **Nothing is signed, and docs/INSTALL.md says what a person sees,** marked as from documents, as nobody here has a Mac or Windows PC. LATER L119. What a Mac's local network question does to an app with no signature of its own is unknown, and it could stand between a person and pairing: the first report from a real Mac should say.
12. **The Mac's and Windows' files are unproven until the first CI run.** They've been read and linted, and nothing more.

## Settings in sub pages (2026-11-02)

The owner: "settings page is becoming too long can have sub menus for like session top bar for example", "even a sub page for all the per server stuff in the all setting page", "a subpage for each server". Only where things live changed: no setting went or does anything new. `ui/settings/SettingsPlan.kt` says what shows where.

1. **The tab, with one server and with All servers alike:** a row per server (its name, what's on its page, its link), then **This phone**: rows for Notifications, Session top bar, Card swipes, Typing and voice and Logs, then New sessions and Display in place, the Terminal engine trial when unlocked, and About. New sessions and Display stay in place because each is one control: a page for one control is a tap for nothing.
2. **A server's own page:** its card (the link, Stay awake and battery, route, address, versions, its update and debug switches, its notifications, Retry, Forget), Agents & providers, Read aloud, Files and the testing checklist when unlocked, under its name. Each page reads and writes its own server only. The list "All servers in Settings" point 1 asked for, once per server, is now this page.
3. **One layout, not two.** A single server's Settings had the card and the rest on the page itself, All servers a block per server. Now the one server has one row and the same page. So the server's versions are always on its card, not in About. The **connection log** is always in Logs, as it was with All servers: it is the phone's, every server's lines in it, and it no longer sits inside a server's card with one server. The first line of the tab with All servers ("All servers: each server's settings under its name…") went: the heading **Servers** does its work, and the first server's name no longer reads as the page's title.
4. **What stays different between the two:** with All servers a server that isn't connected shows its card alone, "Its settings show here once it's connected." (no controls from a last snapshot); the one server on screen keeps its parts while it can't be reached, from its last snapshot, as it always has. About's update still comes from the one on screen, or with All servers the newest any connected server offers.
5. **Jumps land inside.** "Turn on in Settings" and a server's failed update ask as they did (`SettingsJump`): Home goes to the Settings tab, the tab opens the page of the server asked for (a session's own from All servers), and the page scrolls to the part, once. A part that server hasn't got, as when it isn't connected, leaves the page at its top. Back from there is the tab.
6. **The same navigation as the agents' screens:** `Route`s on the ViewModel's stack, `NavRow`s and `ScreenScaffold`, so Back, the system's gesture and the Activity being recreated behave as they do there. The app is locked to portrait, so a phone turning doesn't recreate it; a system font size change does, and on an emulator the page stayed open through one. `NavRow` gained an optional slot for the server's connection chip.
7. **Words that said where things were changed:** "the Notifications section further down" is now "the Notifications page in Settings". With no server paired, Typing and voice and Logs now hold Voice recognition and the connection log, which the page used to leave out: nothing depends on them being gone.

## Tray alerts on the desktop client (2026-11-02)

The owner: "A desktop tray alert is a later job" (LATER L113, the reason being that an approval waits unseen behind other windows). Local only: no cloud, no push service.

1. **What's worth an alert is `:shared`'s, not a new rule.** `LiveHub` already turns a request into a banner while the app is on screen and a notification while it isn't, once per approval, an agent's ask only after its wait, and `NotificationPrefs` already holds the owner's switches. `TrayNotifier` is the desktop's `Notifier` behind those, and passes on only what needs input: approvals, questions and plans, a session waiting in its terminal, and an account that needs its owner. The rest of the phone's kinds stay in the log (LATER L123). An automation's question stays out too: this window has no automations to open.
2. **"On screen" now means the window has the focus.** `Core.setVisible` used to stay true for the whole run, so `LiveHub` never called the notifier for the server on screen. `Main.kt` now follows the focus, with 2 seconds' grace (`followFocus`), so a moment on something else, or a window still on its way up, isn't "away". The link doesn't depend on it: `ProcessLink` holds it either way. What was on screen when the focus went counts as seen, as on a phone. A server that's only being watched alerts with the window in front too, as a phone's does, and a click makes it the one on screen.
3. **It says who asks, never what** ("Migrate the payments table needs you. Open the window to see what it wants."). A system's alert can sit in a history or show over a locked screen, as a phone's can, and a desktop can't tell the two apart, so the phone's lock-screen rule is the one kept. **No Allow on it, whatever the command:** rule 3 of "The desktop app" stands, and the alert only opens the window, where Allow is beside the whole command.
4. **Not twice for one request.** The hub's `seenApprovals` and `noticed`, and behind them the notifier's own list of what it has said. A restart forgets, and the window's first 2 seconds count as looking, so what's pending at a start is on the list rather than in the tray.
5. **AWT's tray, not Compose's `Tray`.** Compose's is a thin layer on the same `SystemTray` and `TrayIcon`; going direct keeps the notifier out of the composition, and keeps every way AWT can fail in one `try`. What each system does, read in JDK 21's own sources and marked by whether it has run:

   | | Where the alert comes from | A click | Run? |
   |---|---|---|---|
   | Linux with an X11 tray (`XSystemTrayPeer`: something owns `_NET_SYSTEM_TRAY_S0`) | A small window AWT draws by the icon | An `ActionEvent` | **Yes**: under Xvfb with `stalonetray`, in a container, both the alert and the icon clicked |
   | Linux with no tray: plain GNOME (no owner of that selection), and any session with no panel | Nothing: `SystemTray.isSupported()` is false | | **Yes**: Xvfb with no tray, quiet, no crash. GNOME itself hasn't been seen |
   | Windows (`awt_TrayIcon.cpp`, `NIN_BALLOONUSERCLICK`) | The system's own balloon or toast | An `ActionEvent` | **No**, not until CI opens one, and CI doesn't click |
   | Mac (`CTrayIcon`, `NSUserNotificationCenter`) | The system's notification, through an API Apple has deprecated | `CTrayIcon.m` hears no click on the notification, only on the menu bar's icon. What macOS does with one is unknown here | **No**, and an unsigned app may not be allowed to post at all |

6. **With no tray, it's quiet.** No icon, no alert, nothing thrown, a line in the debug log. Settings says plainly that this desktop can't show one. **The window's title counts what needs input,** "(2) Hypernova", on every system, since it's the one thing left on a desktop with no tray and it reads in any window list. `Taskbar.requestUserAttention` doesn't help here: `XTaskbarPeer` only says it's supported under Unity. GNOME's own notifications would reach it, and are LATER L122.
7. **Closing the window still ends the process, so alerts only come while the app is running.** Settings and docs/INSTALL.md say so: minimize it instead. A background that outlives the window would be a bigger change, and nobody has asked for one.

## The desktop client's key in the keychain (2026-11-02)

LATER L114: the client is packaged for strangers now, and the owner asked for the difference from Android's keystore to be said plainly. "The desktop app", rule 2 still says what a file doesn't protect from. **What this buys is less than a phone's, and is said as such:** the key is no longer lying readable on the disk or in a backup of the home folder. On none of the three does it stop another program running as the same user. **The key alone never closed a stolen folder: the tokens' move does, in "The desktop client's tokens in the keychain", which is the section to read with this one.**

1. **The choice: each system's own program, spoken to on standard input, holding the same text as the file. Nothing bundled.** All three are small and have the same shape (`Keychain.kt`), and a key that's refused or locked falls to the same one path.

   | | Chosen | What it protects from | Non-exportable? | Proven |
   |---|---|---|---|---|
   | **Linux** | The Secret Service through `secret-tool` (libsecret-tools; the `.deb` recommends it). GNOME Keyring, and KWallet since Frameworks 5.97 by its release notes | At rest: encrypted under the keyring's password, so not readable in a backup. Once unlocked, any program on the session bus can read it: CVE-2018-19358, which GNOME disputes because untrusted programs shouldn't have the session bus at all | No, nothing offers it | **Yes**, real `gnome-keyring` 46 in throwaway containers (Ubuntu 24.04, Debian 13). KWallet not tried |
   | **Mac** | The login keychain through `/usr/bin/security`, as a generic password | At rest. Its man page: "the application which creates an item is trusted to access its data without warning", and that's `security`, so any program of this user can ask `security` for it | No | **No: unproven until CI**, written from Apple's man page. Nobody has run it on a person's Mac |
   | **Windows** | DPAPI (`ProtectedData`, `CurrentUser`) through Windows' PowerShell, the sealed bytes in `device-key.dpapi` in the owner-only folder | At rest, tied to this user's login: another user or a copied disk can't undo it without that. Any program of this user can | No | **No: unproven until CI**, written from Microsoft's documents |

2. **The alternatives, per system, and why not.** From the sources themselves (JDK 21u's `src`, libsecret's `tool/secret-tool.c`, Apple's `security.1`, the libraries' own READMEs), except where marked.

   **The JDK's own stores** looked like the simple answer, and aren't:
   - **`KeychainStore` on a Mac** (`apple.security.KeychainStore`, in `java.base`). `engineGetKey` exports the key to a PKCS12 bag and hands the process the bytes on every read, so it's no less exportable than `security`. It only finds a key again as a `SecIdentity`, a key with a certificate, and the JDK has no public way to make the first certificate before the server has signed one. Its item's list of who may read it would name this app: with an ad-hoc signature that's what changes at each update, which would mean a prompt after every update (that part from memory, not tried). So: more code, more prompts, nothing more protected.
   - **`Windows-MY`** (`sun.security.mscapi.CKeyStore`). `engineSetKeyEntry` takes `RSAPrivateCrtKey` only, and anything else throws `UnsupportedOperationException`, so **Java can't put a P-256 key there**, and the protocol's key is P-256. It can *use* one, with `CPrivateKey.getEncoded()` null and `SHA256withECDSA` in `CSignature`, so one imported by `Import-PfxCertificate` would be truly non-exportable (Microsoft's page: "If this parameter is not specified, then the private key cannot be exported"). This is the only real non-exportable key on offer. It would take a self-signed certificate written by hand, a PFX holding the key on disk for a moment, and a TLS handshake through a key handle, all first run on a system nobody here has. Worth it later: LATER L125.

   **A library**:
   - **`java-keyring`** (BSD), one API over all three: brings JNA (Apache-2.0 or LGPL-2.1, with its own native library per system; not in the package today), a D-Bus stack, `jkeychain` and slf4j. Its own README says that with `jpackage`, which is what builds these, it gives "no additional security beyond ordinary user file security" on Windows, and needs a Developer ID signature to mean anything on a Mac. So it would add most of what's bundled for what these programs do already.
   - **`de.swiesend:secret-service`** over `dbus-java` (both MIT), Linux only: no need for `secret-tool` to be installed. Its README's current version is `2.0.0-alpha`, it wants its own slf4j, and it would be the one new dependency for one system. A tarball person who has no `secret-tool` gets words on Settings instead.
   - **D-Bus by hand** on the JDK's Unix sockets: nothing bundled, and a few hundred lines of wire format, authentication and prompt handling to get wrong, where `secret-tool` is a dozen.

   **Hardware:** the Secure Enclave wants a signed app with entitlements (from memory: not looked into, since there's no Apple account, LATER L119). A TPM: not looked into.

3. **The packages' own Java doesn't come into it.** This uses `ProcessBuilder`, in `java.base`. `includeAllModules` stays for what it was there for, and the alternatives above that lean on `jdk.crypto.mscapi` or `Apple` would have had them.
4. **No new dependency, so no new licence.** `secret-tool` isn't in the package. It's recommended by the `.deb`, so `apt` brings it along; a tarball's owner installs it or stays on the file.
5. **An existing install keeps its pairing.** At the first start the file's key goes to the keychain, is read back, must have the same public half and sign a random challenge that checks out against the file's. Only then does the file go. Any failure leaves the file as it was. **The file wins:** it exists only because the keychain hadn't taken the key, so a crash at any step loses nothing, and what's in it is always the newest. Deleting a file doesn't scrub the disk, and **an older backup of the home folder still holds that key**: unpair and pair again if that matters.
6. **A locked or missing keychain never stops a connection.** The key can't be had, so the app makes a new one in the file and connects with it. The token is still good, so the server issues a certificate for it, as it does for a lost one (`POST /v1/device/cert`, which replaces the old). When the keychain answers at a later start, the file's key moves over the old one. To make this hold, `DeviceCertStore.identity()` won't present a certificate for another key than the one there now (through `DeviceKeys.publicKey()`, a new default that says nothing on Android, so nothing changes there): the handshake could only fail, and with nothing presented the server asks and the hub gets a new one. The certificate's signed for each server separately, so every paired server gets there by itself.
   - **`secret-tool lookup` can't tell locked from empty, and `search` can.** With a keyring locked and its prompt not answered, `lookup` exits 1 and says nothing, as for no item at all (seen in a container). This once read as "nothing there" until `store` was refused. A token can't be made again, so since "The desktop client's tokens in the keychain" `search --all` is asked as well: it lists a locked item with no secret, and nothing where there's none.
   - **A prompt is why the first look is before the window** (`prepare`, on the startup thread, for up to 20 s). It's asked once per run: after it doesn't answer, nothing asks until the next start. What's left is pairing's first key, on the event thread, if a keyring locks after the first look and its prompt then waits: the window stalls for at most those 20 s.
7. **Settings says where the key is, in plain words,** and why when it's in the file: locked or not running, no `secret-tool`, or a keychain that didn't give it back. In amber, with what the file doesn't protect from.
8. **Never in a log.** A secret goes to a helper on standard input and never on a command line, where other users of the computer could read it. A helper's output is never logged or put in an exception. `Found` and `HelperResult` have no `toString` that holds it. The private key is given to `DebugScrub.remember`, so it's cut from every debug-log line, and with that from what `POST /v1/client-logs` can send, whatever shape a line makes of it. `KeychainPairingTest` reads every file in the app's folder, the debug log with it on included, and `scripts/test-client-package.sh` looks for the first key in its logs and in the throwaway keyring's files.
9. **Each folder has its own item** (a hash of its path), so a test copy's `HYPERNOVA_CLIENT_HOME` never sees the real client's key, or replaces it. `HYPERNOVA_CLIENT_KEYCHAIN=off` keeps a key in the file, and `:desktop:test` sets it for every test, so running them can't touch the keychain of whoever runs them.
10. **What this didn't close by itself: the bearer token.** It stayed in the owner-only settings file, and `POST /v1/device/cert` is open on the local listener to a paired device's token, for any key (`daemon/src/http.ts`), so a thief with a copy of the home folder could get a certificate of their own and pass as this computer until it was unpaired. It would have shown: the real client's certificate is dropped as the new one's made. L114 was the key. LATER L124 was the token, and it's done: the next section. Where a token is still in the file, because no keychain could take it, this still holds.

## The desktop client's two panes (2026-11-02)

The owner: "the desktop app in my mind has 3 columns of cards", then "I like the double view of agents list and session details in one. maybe that instead of 3 columns". LATER L116. So two panes, and no third column.

1. **The left pane is the list, in the phone's three groups and order. The right is the chosen session.** Choosing keeps the list: a row is the same height chosen or not, its ring and tint draw over what's there, and nothing scrolls for a click. A row only moves when its group changes, as before.
2. **Allow and Deny moved from the list to the pane, so answering takes a click more than it did.** This is rule 3 of "The desktop app": Allow only beside the whole command. A row's line is one line and can be cut, so it can't carry an Allow. What it says is masked as the card's was. The arrows make the click cheap.
3. **Nothing chooses itself.** The window opens on nothing chosen, a session that comes to need an answer doesn't take the pane, and when the chosen one goes the pane says so and waits. An Allow should never appear under the pointer for a session nobody picked. The one exception is a rewind's copy, which goes on as the session it replaced. Each server keeps its own choice for as long as the window is open, and Settings comes back to it. It isn't saved.
4. **What the pane shows is what the list already has:** its project, name and state, what it's doing or asking in full, why it's stopped (Claude Code's own line), how many more asks wait behind this one, then its folder, model, permission mode, where it runs, agent, agents at work, queue, context and times, in the phone's session sheet's words and rules. Nothing is fetched, so nothing new can fail. A question or a plan still says to answer on the phone. Below it, plainly: "The chat and the terminal aren't in this app yet. Open this session on your phone for those." Cost is left out: the list's figure is only sometimes there, and on a plan it isn't money spent. Git isn't there either, as it takes a request of its own.
5. **Narrow is under 760 dp: one pane at a time.** The list, then the session with a Back, and Back finds the list where it was. It goes by dp, so it follows the scale and the zoom: a 4K window at 2 has room to spare, and 1080p at 3 is one pane. The list is 340 to 460 dp, the pane at most 800, and the two stay together in the middle of a very wide window. The smallest window stays 520 by 420 dp.
6. **The keys work with nothing clicked first:** up and down choose as they go, Escape lets go. In one pane the arrows move a mark instead, since choosing would take the list away, and Enter opens. Rows aren't Tab stops, so Tab goes from the top bar to Deny and Allow. An arrow pressed on a pill changes the session and presses nothing. The focus sits on the screen itself (`SessionsScreen`) and comes back there when what had it goes, such as a pressed pill. `Main.kt` isn't involved. That focus is one silent stop as Tab comes round.
7. **Not done:** the chat, the terminal, and anything else from the session sheet that needs a request. A person doesn't get the list's position back after closing the window.

No delay on new Deny and Allow pills. With two asks waiting on one session, the next ask's pills come up where the last ones were, so the second click of a double-click could answer one nobody had read. A 600 ms delay was built and taken out at the owner's word (2026-11-03: "remove the 500ms delay too", "600ms I mean"), so don't add one back without asking.

## The desktop client's History (2026-11-03)

The owner: "got the desktop app up and running and connected to the k1 server ... it shows like all of them in history it looks like it doesn't actually filter out the ones that are closed in the [claude] agents terminal let's keep working on the desktop version to have [feature] parity with the Android app". The filter (`Session.onHome`) left ended sessions with no home on the desktop, so History came first. His rule stands: "history acts like the resume slash command for all".

1. **The phone's rules, not a copy of them.** What's listed, the search, the merge with the server's full text, the fold of an automation's runs and the days all run the phone's code, which moved to `:shared` whole where it was plain: `Session.inHistory`, `HistoryEntry`, `foldRuns`, `HistorySearch.rows`, and `rememberFullText`, which draws nothing. The phone's `AllHistory` calls them.
2. **The same two panes.** Sessions and History are two pills in the top bar, and History takes the left pane. Each list keeps its own choice and scroll for each server while the window is open, so going to look at History loses nothing. The arrows, Escape and one pane in a narrow window work as they do on Sessions. From the search box too: the arrows choose from there, and Escape empties it first.
3. **The search and the chips stay put over the list,** where the phone scrolls them away. The chips wrap instead of going sideways: a mouse has no sideways drag.
4. **Opening one starts nothing and fetches nothing,** until the chat came ("The chat on the desktop" 13: it now reads the transcript, starts nothing, and only writing resumes). The pane was its details, from what the list already has, and said plainly: "Reading it, and writing in it to resume it, aren't in this app yet." Reading a transcript needs the chat, which is being changed elsewhere and moves to a shared module afterwards, so no transcript view was built here.
5. **An automation's row has no runs to open** with no automation screens here. It says how many runs and Auto, and choosing it shows its newest run, with a line saying the others are on the phone.
6. **One server at a time.** The phone's All servers History waits for an All servers here.

## The desktop client's Remove from list and Rename (2026-11-03)

The rule is the owner's from "Delete takes a session off Home, History brings it back": Remove from list moves a session to History, and the transcript stays. `:shared` now holds what the menu offers (`DeleteKind`, `RestartKind`, `MenuOffer`), so both clients go by one rule and the phone's confirm keeps its words.

1. **Buttons in the pane, not a menu.** There's no long-press, and a pane has room. Rename, Stop and Remove from list come after the facts, so Tab still reaches Deny and Allow first and rule 3 of "The desktop app" isn't touched: nothing here is near an Allow.
2. **The phone's confirm and words,** for each kind: what closes, that it goes from `claude agents`, that the conversation stays in History. A terminal Hypernova doesn't hold says why it can't. Stop goes at once, as on the phone. The focus starts on Cancel, since Enter presses what has the focus here and a phone has no such stray key. Escape and a click outside are Cancel.
3. **No Rename in History, which is stricter than the phone.** The server's rename types `/rename` into the session (`daemon/src/http.ts`), and writing in an ended one resumes it: `undelete`, and a claude started. On a phone that's reached only from an opened chat's session sheet, and the phone does the same there, unchanged here. In this pane, which starts nothing, a Rename that starts a session would be a surprise. The owner may want it the other way.
4. **What goes stays on screen.** After Remove the pane keeps the session, now ended, and says it's in History: nothing chooses another, and the list's row is gone.
5. **Restart session and MCP servers aren't here,** and Stop and Restart aren't offered in History, which has nothing running.

## The desktop client's New session (2026-11-03)

For the same parity work: a folder, a prompt, the agent and the mode, in the right pane. What it remembers, the folder's choices, the agent's rule, what's sent and the refusals are `:shared`'s (`NewSessionMemory`, `ProjectPick`, `FolderPick`, `AgentLead`, `Hub.start`).

1. **Bypass isn't offered here, and the words say why.** On the phone it's never a start mode (`NewSessionMemory.START_MODES`): it's chosen each time, through the think-twice card and then the fingerprint or screen lock (`OwnerCheck`; "The owner's morning answers", 2: "Everywhere a phone can give either"). The server asks nothing more for a new session than that it isn't running as root, so that check on the client is the only one. A desktop has nothing to stand in for it: a confirm anybody at the keyboard can click would be the weaker thing the owner said no to. **This is the owner's to change,** if he wants it with some check of the desktop's own.
2. **Codex has its two modes,** read only and can edit (`AgentKind.CODEX_MODES`). `danger-full-access` and approval `never` aren't reachable from here, as on the phone. The server's own rules stay what they are.
3. **Auto keeps its think-twice card, every time, with one of the same real cases** (`res/raw/incidents.json`, which the build copies in). "Keep asking" has the focus. The story's link isn't there: the case's own two sentences are on the card instead.
4. **Skills after a prompt work as on the phone:** the box's words go as typed, lines and all, and the server makes them the skill's arguments (docs/FEATURES.md, N8). The phone's "/" list is in `ui/session/`, being changed elsewhere, so it isn't here yet.
5. **Enter starts it, Shift+Enter is a new line,** the phone's default (`EnterKeys`, there being no Typing setting here). Two Enters start one. The arrows stay in the box while the form is up.
6. **A session started is the one thing that chooses itself,** since someone just asked for it and waited: the form goes and the pane shows it. If the form was put away while it started, nothing moves and it's only in the list. This is the one further exception to "Nothing chooses itself". The words typed stay for next time, as on the phone, through Cancel, Escape, or choosing a row.
7. **The default model and effort, said on the form.** No pickers yet. A subscription at its limit starts on the provider that can take it and says so, as on the phone, since that one may bill.
8. **Not here:** attachments, the "/" list, dictation (none on this app), the model and effort pickers, All servers.
9. **The top bar's lines go by the window's width alone.** With New session and History it no longer fits beside a long link word at 900 dp, and as it wrapped when the chip grew to "Reconnecting…", the panes moved under the pointer as the link changed, Allow with them. A test caught a click landing where Allow had just been. Now it's one line from 920 dp, two under that, and a long server's name gives way. The offline card still moves them when it comes: that's a stale list, and it was already so.

## New sessions start in, per server and agent (owner, 2026-11-03)

The owner: "for the settings new session starts in this doesn't make sense as a global configuration anymore on the phone it should be a per server per provider configuration shouldn't it because [Claude] code and codex have different settings for these so go ahead and move this". This replaces where the older "New sessions start in" section kept it and chose it, what that section says the sheet opens on, and "Settings in sub pages", point 1, where it says New sessions stays on the tab. Bypass's rules don't change.

1. **What each agent offers,** from what really starts them. **Claude Code:** Ask, Edits, Plan, Auto (`default`, `acceptEdits`, `plan`, `auto`). **Codex:** Read only, Can edit (`read-only`, `workspace-write`): `daemon/src/codex/sessions.ts` starts every phone thread with approval `on-request`, so there's no second thing to pair it with, and asking isn't offered. Auto goes through the think-twice card as it's chosen. Can edit asks nothing here, as the sheet's own Can edit doesn't: this only says what the sheet opens on.
2. **Where it lives: the daemon's settings on that server,** `settings.startModes`. Weighed:
   - **The agent's own file** (`permissions.defaultMode`, `sandbox_mode`). Both are already on the fixed list, and it's one less setting. But a terminal's `claude` would follow it, which is more than he asked for, and moving his old value would mean writing Auto into `~/.claude/settings.json` on every server unasked, where "Loosening asks" says a confirm and a fingerprint, per server. A file that's a link can't be written at all. And the agents write these keys themselves, and the file can hold `dontAsk` or bypass, so the sheet would be reading something it doesn't own. Not this.
   - **The daemon's settings.** Simple, one value per server and agent, reaching the snapshot's `claude` and `codex` as `loginContinue` does, so the phone and the desktop read the one value with no second copy and no second parser. **This.**
   - **The phone's prefs, keyed by server and agent.** No protocol, but the desktop wouldn't share it, which is the copy he asked not to have.
3. **So there are two rows that sound alike, and the words separate them.** "New sessions start in" is this; "Starting permission mode" is the file's, now said to be what a session started in a terminal begins in. The same for Codex's Sandbox, whose line already says ones from this app choose their own.
4. **Nothing can widen.** The server saves only an agent's own modes and refuses the rest (400 `bad_mode`, nothing in that body saved). It reads back only those too, so a stale or hand-edited settings file is Ask or Read only rather than what it says. Each client checks again as it reads (`NewSessionMemory.startsIn`), so another agent's word can't cross either. `POST /v1/sessions` checks what it's sent as before, and doesn't use this. Bypass is still chosen each time in the sheet, behind its fingerprint, and goes with that server's sheet: a pick there doesn't come along to another server's.
5. **An older server** sends no `startMode`. For Claude Code the sheet and the form open on the old setting if that server is still owed it (point 6), else Ask; for Codex on that device's last mode. Each is only its own agent's. The row says "needs updating" with nothing to choose, and nothing is sent. With Settings' section gone, the old value can't be changed on the phone any more until that server is updated.
6. **The old value moves once, to Claude Code only, and only to the servers paired when the phone first runs this.** A brand-new server shouldn't quietly start sessions in Auto. `Core.init` writes the list once, as one prefs key beside the old value (`newSession.startModeOwed`, `NewSessionMemory.noteStartModeServers`). Nothing adds to it after that, so a server paired later has nothing saved: Ask on Claude Code, older or not. `LiveHub` moves it as each listed server's snapshot says it can hold it, with `moveStartMode`, which only takes where the server has nothing, so a value chosen meanwhile, or from another phone, stays. So an older one on the list takes it after its own update. A failure tries at the next snapshot. A server forgotten leaves the list at the next start. **When the list is empty, the old value and the list are deleted,** so nothing is left to read and later starts do nothing. Ask (what unset means anyway), Bypass and Codex's words aren't moved and are deleted at the first start. Codex gets nothing from it. **Codex's last mode isn't moved:** it wasn't a setting, so a server with nothing set opens Codex on Read only, narrower than the last one used, until Can edit is chosen on its row. Forgetting a listed server and pairing it again gets it no second time if it keeps its id; with a new id it's a later server.
7. **The sheet, the form and the switch sheet open on it, and follow.** What's shown is the session's own pick if there is one, else that server's value for the agent on screen, so changing agent shows that agent's, a late snapshot or another client's change shows through, and a pick changes nothing kept. With All servers the sheet is the picked server's already. On the desktop, as on the phone, opening in Auto shows no card: it was chosen once, on the row. Picking Auto in the form still asks.
8. **The row is on each agent's Settings on the server's page** (`ui/settings/StartModeRow.kt`), above what's read from the file, so it's there whatever state the file is in. The Settings tab no longer has New sessions. There's no `hypernova` command or `/local/settings` key for it, and the desktop has no row: it only reads the value. **Not run on a real phone or server:** the tests are real routes in the daemon and a real hub against a fake, as elsewhere.

## Updating Claude Code and Codex (owner, 2026-11-02)

The owner: "also add ability to check and update Claude code and codex". Agents & providers › an agent's Settings › Version: what's installed, Check for updates, Update. One server at a time, nothing on the session's chat screen, nothing automatic. `daemon/src/agent-update.ts`; `docs/PROTOCOL.md`, "Updating an agent". Checked on 2026-11-02 against Claude Code 2.1.316, Codex 0.164.0, their binaries and `code.claude.com/docs/en/setup`. **No real update ran.** Measurements used copies in a throwaway home with stand-in `npm`, `curl` and `brew`, and proxies pointing nowhere.

1. **Three ways, and the one built.**
   - **The program's own updater, its result read back (built).** A fixed `claude update` or `codex update`, its output to a file, then `--version`. The vendor keeps deciding what each install needs, and this keeps working when that changes. It can't pick a version, and can't stop Codex at a ceiling.
   - **The daemon as installer:** `npm install -g …@<version>`, or Anthropic's signed manifest and its checksums. That gives version choice and a ceiling. It's a second installer to keep correct for every kind, it steps around Claude Code's lock and its own cleanup of old versions, and it puts a version string on a command line.
   - **A shared terminal with the command typed in.** Least code, and the person sees everything. It has no check, no read-back and no "can't update here", the result is a scrollback to read, and it would run with no fingerprint unless one were added.
2. **The updater's words and exit code prove nothing, so the version is read back.** Measured: `claude update` printed "Successfully updated from 2.1.316 to version 2.1.999" and exited 0 when a stand-in `npm` did nothing. It exits 0 when Homebrew manages it, when updates are off, and, from its code, when another Claude holds its lock. `codex update` printed "Update ran successfully!" when the installer it piped was empty. So `updated` means the version moved, and `unchanged` says it didn't.
3. **What each kind does, measured.** `claude doctor` runs without a terminal and says `Running: native|npm-global|package-manager (…)`, with `Package manager:` beside it. Its update:
   - Native: it reads `downloads.claude.ai/claude-code-releases/<channel>`, takes a lock, downloads and moves the link (from its code). Offline it says "Failed to fetch version…" and exits 1 (measured).
   - npm: `npm view @anthropic-ai/claude-code@latest version --prefer-online`, then `npm install -g @anthropic-ai/claude-code@<version>`, with whichever `npm` is on `PATH`. So the program's own folder goes first on `PATH`: on nvm that's the right Node's. Not measured with a service's real `PATH`.
   - Homebrew: prints `brew upgrade claude-code`, updates nothing, exits 0. **So the phone shows the command rather than run `brew`**, keeping to "only the program's own updater". Claude Code does have `CLAUDE_CODE_PACKAGE_MANAGER_AUTO_UPDATE` for this; it's the owner's to turn on and wasn't run.
   - apt, dnf, apk: from its docs and its code, which only prints. Not run: no such install here.

   Codex's `doctor --json` says `update action`: measured `npm install -g @openai/codex` on the wrapper, `standalone installer` for a copy laid out under `packages/standalone`, and `manual or unknown` for a bare binary and for one under a Linux Caskroom. Its update then ran `npm install -g @openai/codex`, `sh -c 'curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh'`, or exited 1 with its guide's address. `brew upgrade --cask codex`, `bun`, `pnpm` and `vp` are only words in the binary: run on none. A Mac's Homebrew wasn't run.
4. **Where "latest" comes from.** Codex's doctor asks by itself (it answered 0.166.0 on npm, and its probe is GitHub's for a bare binary), so the daemon calls nothing for Codex. Claude Code has no dry run, and its doctor doesn't say, so the daemon fetches the same file `claude update` reads. It's one address for every kind, so an npm or package-manager install may hear of a version a little before it's there: its docs call that a known issue, and the read-back says what really happened. The channel is the doctor's line, or the Homebrew cask's name. **A check runs on a tap and is kept 5 minutes.** Opening the page only runs `--version`, following "What opening a list may start": `claude doctor` reads settings, and the server's outside calls should be somebody's choice. So no card says an update is waiting until someone looks: a real tradeoff, and a periodic check could be added.
5. **Running sessions: nothing is killed. Each measured on real binaries with no account, so no model turn ran.**
   - **An interactive Claude Code, and real holders (`term/holder.ts`) over Claude Code and Codex:** with the program's file deleted under them, each process lived on, and `/help` and `/doctor` still answered, which load code from the file. On tmpfs. This is what npm's in-place update does; a native one doesn't even touch the running file.
   - **`claude agents` jobs:** with a job running on 2.1.314, the launcher moved to 2.1.316. Within a minute the background service logged `shutting down (cause=upgrade…)`, restarted on 2.1.316 and `adopted=1`. The job kept its pid and its old version. Deleting 2.1.314 outright didn't touch it either. Home's existing mark and Respawn take it from there.
   - **Codex's app server:** `codex app-server daemon start` copies Codex to `<CODEX_HOME>/packages/app-server-daemon/releases/<version>` and runs from there, with an update loop of its own, so npm's update can't touch its file. With the npm package's binary really replaced, by a stand-in, `codex update` left the server's pid alone. So after an update **the app server stays on the old version until it restarts,** the result says so, and Hypernova still never restarts it. `codex app-server daemon update` is the command that says it "may interrupt running work", and it did restart the process; the daemon doesn't run it. The binary also holds "Updating the local background server…", which didn't run from `codex update` here: it may be a terminal's `/update`, so **a real version change could behave differently.** A new `codex` opening against an older app server is unproven too, as no second version was on hand.
   - **A network disk:** Claude Code's docs warn that a running session reads its file as it works, and that npm's in-place update can crash sessions on another machine. statfs finds one and the confirm says so. It isn't refused, and it wasn't tried on one.
6. **The fingerprint as for a plugin:** a confirm saying what changes and what happens to what's running, then `OwnerCheck`, then `confirmRun`, which the server requires. A check needs none. The update needs a check first, so the confirm isn't empty.
7. **Which version counts.** `CODEX_MIN` stays the floor, and below it neither `doctor` nor `update` runs, as 0.39.0 reads them as a prompt. **The new `CODEX_CHECKED`, 0.164.0, the newest actually run against these routes:** an update past it says so on the confirm, and isn't refused, since Codex has no ceiling anywhere else and the handshake still tells. A person could refuse at that point, and **whether it should be a hard stop is the owner's call.** Raise the number when a version is checked. **The Agent SDK:** every use passes `pathToClaudeCodeExecutable` with the `claude` the daemon finds, so its bundled binary isn't used, and SDK 0.3.316 (built for 2.1.316) already meets whatever Claude Code's own background updates bring. An update from the phone adds no new risk there, so it says nothing. It wasn't tested against a newer `claude`.
8. **Two installs.** The daemon updates the one it runs: the first of `CLAUDE_BIN` and its usual places for Claude Code, the newest it finds for Codex. Another stays as it was, and `claude update` prints a warning naming both. In the throwaway home, after an npm-kind update there, the native launcher at `~/.local/bin/claude` was gone. It wasn't traced, and `findClaude` prefers the native one, so that path needs an odd setup.
9. **Not done.** A container's install would be lost when it's recreated, and nothing says so here.

## The desktop client's tokens in the keychain (2026-11-03)

LATER L124. A paired device's token can get a certificate for any key, so with the key in the keychain and the token in a file, a copy of the home folder could still pass as this computer. **After this, a copy of the client's folder alone holds no working token where a keychain is working.** That's all it claims. Read rule 7 before saying more.

1. **What the client keeps that authenticates anything, and where it is now.**

   | What | Was | Is | Why |
   |---|---|---|---|
   | Each paired server's token, `servers[].token` in `prefs/hypernova.json` | The settings file | **Its own keychain item, one per server.** The entry in the file says `tokenInKeychain` | It's the bearer credential, and it can get a certificate for any key |
   | The single pairing's `token` key (a phone's, from before the list; `ServerBook` still writes it for the server with id `0`) | The settings file | The same item as that server's, and never written to the disk while the list has it | A second copy of the same token. A desktop folder shouldn't have one, and nothing proves none does |
   | The device key | `device-key.json` | The keychain, already (L114) | |
   | The certificate and the CA, `prefs/hypernova.cert[.<id>].json` | The settings folder | **Stayed** | Not secret: a server shows them to anyone, and they're no use without the key |
   | The pairing code | Only in memory (`Shell.link`, `PairLink`), gone once spent | **Stayed in memory**, never on the disk | It works once and lasts 10 minutes. `Pairing` and `PairLink` no longer print what they hold |
   | `installId`, `deviceId`, `macId`, the listener's fingerprint | The settings file | **Stayed** | They name things, and prove nothing without a token or a code |
   | An automation's webhook token | Only in memory, on a phone: a desktop has no automation screens | Not persisted | |

2. **One item per server, not one item holding all of them.** Forget takes exactly one item, a failed write can't touch another server's, and nothing grows with the list. The cost is a helper run per server at each start: nothing to speak of on Linux, and a PowerShell start per server on Windows (not measured, as it hasn't run there). The item's name is a hash of the folder's path and the server's id (`Keychains.accountOf`), so a test copy never touches a real one, and an id from the settings file can't become a word of a command. On Windows the sealed files are in the folder, named by a hash of the id alone, so they move with it.
3. **The data layer doesn't know, so Android doesn't change.** `ServerBook` keeps writing its list through `Prefs`, as on a phone. `DesktopTokens` stands between that file's memory and its disk: `prepare` puts tokens in what the app reads, `seal` takes them out of what's written, on the thread that writes settings. So the window never waits on a helper, whatever `Core` saves on the event thread, and every save after the first is no helper at all. Considered and not done: a token seam in `:shared`, which would change a phone's code for a desktop's problem. `:shared` got two small things: `Core.restored`, which no phone calls, and `toString` on `Pairing` and `PairLink`, which printed a token and a code.
4. **The move is the key's** ("key in the keychain", 5). Written, read back, the same text, and only then is the file written without it. Any failure leaves the file as it was. **A plain token in the file wins:** it's only there because the keychain hadn't taken it, so it's the newest, and a new pairing made while it was locked goes over the old item when it's back. A crash at any step loses nothing. A half-written settings file left beside it goes with the move. **A new pairing's token never touches the disk** where a keychain takes it.
5. **What happens when the keychain holds the token and is locked at the start. This is where a token isn't a key: it can't be made again.** The choice:
   - **The server waits, and says so.** The window has "Waiting for your keychain": what's wrong, Try again, and that nothing is lost. It's the whole window if there's nothing else to show, and above the rest if there is. **It's never the pairing field,** which would read as being unpaired.
   - **The other servers carry on.** `Core` isn't given a server it has no token for, so no hub dials with nothing, and a token in the file still connects. So does a new pairing, on the file, with a new key.
   - **Nothing is discarded or written over.** The entry stays on the disk exactly as it is whatever else is saved meanwhile, its item is never written, and Forget isn't implied by a save. `DesktopTokens.withheld` is what holds that.
   - **Try again** asks the keychain, brings the server back with no restart, in its place in the list, and moves whatever's in the file across, the key too. It only asks when someone clicks: asking on a timer would put a password prompt up again and again. A start with it unlocked does the same with no click.
   - **A keychain that locks while the app runs changes nothing.** The tokens are in memory by then, and only a start asks for them.
   - **Only the owner ends it.** "Forget <server>" is there for a keychain that's truly gone. It takes the entry and the certificate, and the item when the keychain next answers (`tokensToRemove`, in the settings). Pairing the same computer again does the same, as the server has replaced that device anyway.
   - **Not chosen:** dropping to the pairing field (that's the forced re-pair); keeping a plain copy "for when it's locked" (that's the file again, and the whole point gone); holding back the whole window until it answers (one locked item stops a server whose token is in the file); treating "not there" as lost (a keychain that can't tell would cost a good pairing).
6. **"Not there" is said in words that don't assume.** `secret-tool search --all` tells locked from empty (key section, 6), by gnome-keyring 46 in a container, and `KeychainRead.Missing` is only said after that. **KWallet isn't tried:** if it answers differently, a locked one reads as "no token here", and those words still say to try unlocking it, and Try again stays. A folder that moved, or a home folder restored at another path, looks like this too: **its item's name came from the old path, so it pairs again** (on Linux and a Mac). That's the price of each folder keeping its own.
7. **What this protects, and what it doesn't.**
   - **It does protect** a copy of the client's folder taken alone: a backup, a sync, a folder handed to something, a home folder on a disk that isn't encrypted. Run for real: a copied folder, beside the same keyring, connects to nothing.
   - **Any program running as the user can still ask for the token, on Linux and a Mac, once the keychain is unlocked.** The Secret Service gives it to any program on the session bus (CVE-2018-19358, disputed), and the Mac's item trusts `security`, which any program can run. On Windows any program of this user can undo DPAPI. This doesn't stop malware running as you, and nothing here could.
   - **The token is in this process's memory while it runs.** So is the key.
   - **A copy of the whole login isn't closed.** A disk with the keyring file and the password for it (or the login, for DPAPI) has all of it. The keyring's file is encrypted with the login password, which is only as strong as that password. On Windows a copy that also has the user's profile and their password can undo the seal.
   - **On Windows, same login, the sealed files in a copied folder still open:** there's nothing to tell a copy from the original. What DPAPI stops is another user, or another computer without that login.
   - **Older copies aren't reached.** A backup from before the move still holds the plain token, and so can the disk's free space. Unpair and pair again if that matters. A settings file kept aside as `.corrupt` isn't rewritten either.
   - **Where it fell back to the file, none of this holds,** and Settings says so in amber until it moves.
   - **The server isn't changed.** A token still gets a certificate for any key; it's the token that's harder to take now.
8. **Going back to an older client after the move means pairing again.** An older one finds an entry with no token, drops it and opens on the pairing field, and the item stays in the keychain until something removes it by hand. Not worth more: the client has no updates of its own that would ever take it back.
9. **Size.** A token is `randomToken(32)`, tens of bytes. On Linux `secret-tool store` takes it on standard input: 4 KB came back whole in a container, and **at 8,192 bytes it cuts the rest and still exits 0**, saying "password is too long" on standard error. On a Mac it's hex in one command line of `security -i`, about 300 bytes for a real token. The line's limit there is remembered as 4,096 bytes, from memory, not checked. On Windows it's base64 on standard input. No limit needs checking at the time, as a token that doesn't fit doesn't come back whole, and then it stays in the file.
10. **Never logged, and on no command line.** The same rules as the key (key section, 8): a secret goes to a helper on standard input, nothing a helper says is logged, and each token is given to `DebugScrub.remember` as it's read, so it's cut from every debug-log line and with that from `POST /v1/client-logs`. The tests read every file of the folder with the debug log on.
11. **Proven and not.** Linux: the real package against a real `gnome-keyring` in throwaway containers, the locked case with the password given while the app runs. A container has no display for the keyring's own prompt, so **nobody has yet seen that prompt come up for this on a real desktop**, GNOME's or KDE's. **A Mac and Windows are unproven until CI:** the `security` and PowerShell lines are checked against stand-ins, which proves what's sent and nothing about what they do with it. KWallet isn't tried.

## Scan in the app (2026-11-03)

The owner: "when setting up a new server there's a Code but I don't need that code ... there's the URL that shown which I can copy paste but then there's the QR code which is just the URL ... right now I have to copy and paste the entire URL but it should be automatic where I scan the QR code ... and it opens up and authenticates". "the URL showing will still be normal URL just in case you're just copying and pasting it into the desktop ... the QR code will always be for a phone". "I want the setup to be very easy on the phone so one click if possible".

1. **A Scan button in the app is the main way to pair.** First-run steps and Add a server both have it, fixed above the navigation bar so it's on screen however the steps scroll. Tap Scan, the camera opens, the code is read with no other tap, and pairing starts down the path a pasted link takes. It needs no confirm, as the person started it, and the result screen names the server added. A code that isn't a pairing link leaves the camera for the pairing screen's existing "That link didn't work", whose Back returns to Scan. Paste stays as the fallback.
2. **The QR code still holds the server's own page, unchanged.** Why it isn't simply an app link: the notes of 2026-10-18 say cameras and Google Lens don't open `hypernova://` from a QR code. That hasn't been tried on the owner's phone since, and he may try it later (docs/LATER.md, L126). So a phone's own camera keeps working as it did, and changing this later is one line, as Scan reads either kind.
3. **The camera is asked for on the tap and nowhere else.** Refused: it says so, Paste comes down beside Scan, and nothing asks again until another tap. `android.permission.CAMERA` is in the manifest with `android.hardware.camera` and `.autofocus` not required (`aapt2 dump badging`: both "not required"), so a phone with no camera installs, and shows only Paste. CameraX stops with the screen: the scanner's composable leaving is what unbinds it. If the camera won't start, it says so and offers Paste.
4. **The scanner: ZXing's core with CameraX.** The project's rule is no cloud and nothing public, and the APK is sideloaded, so the questions were what each sends anywhere and whether it needs Google Play services.

   | | What it sends anywhere | Needs Play services on the phone | Licence | APK size |
   |---|---|---|---|---|
   | **ZXing core 3.5.4 + CameraX 1.6.2 (chosen)** | Nothing found. ZXing's POM has no runtime dependencies, and its only `java.net` use is `URLDecoder`, in a class this doesn't use. CameraX's POMs bring no Google Play, Firebase or transport library, the release APK's dependency list has none, and the merged manifest adds only CameraX's own disabled metadata service. Read from the artifacts, not watched on a network. | No | Apache-2.0 (POMs, and ZXing's README). ZXing's NOTICE is copied into our notices. | **Measured +1.29 MB**: 8.87 to 10.16 MB, release build with R8 |
   | ML Kit's bundled model, `com.google.mlkit:barcode-scanning:17.3.0` | Images stay on the phone, but Google's terms say "The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google", and its data disclosure lists device information, latency and API configuration, without saying it can be turned off. Its `common` POM brings `transport-backend-cct` and `firebase-components`. **Fails the rule.** | Not for the model, which is in the APK. Its POM still brings `play-services-base`. | ML Kit Terms of Service, not open source | Docs say about 2.4 MB. Its AAR holds a native library per processor (3.2 to 6.1 MB each, 20 MB in all) and this APK carries all four, so likely far more. Not built here. |
   | Google's code scanner, `play-services-code-scanner:16.1.0` | Docs: "All image processing occurs on the device and Google doesn't store the results or image data". The same disclosure page lists the same metrics for it. **Fails the rule.** | **Yes**, "delegating the task of scanning the code to Google Play services", and it downloads its module the first time. Wouldn't work on a phone without them, which a sideloaded APK can't rule out. | Google's terms, closed | About 0.3 MB for the AAR, plus its dependencies. No camera permission needed, its one real advantage. |

   ZXing's README says the project is in maintenance mode. That's fine for a decoder of a fixed standard, with the code all on the phone; it's the reason to swap it if a QR bug ever needs fixing upstream. Sources, 2026-11-03: developers.google.com/ml-kit (vision/barcode-scanning/android, vision/barcode-scanning/code-scanner, android-data-disclosure, terms), developer.android.com/jetpack/androidx/releases/camera, github.com/zxing/zxing, and the POMs and AARs from Maven Central and Google's Maven.
5. **Proven against what `hypernova pair` draws, and what wasn't.** `protocol/pair-qr.json` holds the daemon's own half-block lines, held to its encoder by `linux-cli.test.ts`. `QrFramesTest` turns them into camera-like frames and reads them with the same `QrFrames` the camera's frames go through. What that found:
   - **A single dead-straight frame can miss a perfect code.** 3 of 60 sample links failed ZXing's detector with every module exact, though all 60 read in its pure-barcode mode, so the daemon's encoder is right. With a little turn and blur each read on most frames (57% to 100% a frame in 30 random poses), and never as anything wrong. A hand never holds still, so the test asks for several frames, not one.
   - **Light-on-dark reads too.** A pipe or a log has no colours of its own, so it tries inverted as well.
   - **Stripes from a terminal's line spacing break detection,** at 1.1 spacing in every pose. Each third frame is also tried with the rows closed up, which reads the usual link near straight, at 1.1 and 1.2 spacing. The longest sample, with four addresses, at those spacings still doesn't read reliably: Paste is there for that.
   - **On an emulator, the release build read one and paired, through CameraX itself.** Android 16's `hn-demo`, the demo server, and `-camera-back imagefile:` with a code the daemon's encoder drew: from a fresh install, Scan, Android's question, Allow, and "Paired with atlas." with the camera closed after. Its image camera crops oddly and leaves about 3 pixels a module, so a small code was needed, and on it the viewfinder stays black though frames reach the decoder; on the emulator's other, fake camera the viewfinder shows. That's the emulator's, as far as can be told, and only a phone can say.
   - **No real phone's camera has read a real screen yet.** Focus, glare, moire and a shaking hand are all unproven; the owner's checklist has it, `mac-pair-code`.
6. **A link from outside the app asks first (a security fix).** `HubViewModel.handleLink` paired at once, so any web page or app that opened a `hypernova://pair` link added a server with nothing asked. `MainActivity` now calls `offerLink`, which shows one screen, **Add this server?**, with the link's name and every address the code would go to, and pairs only on that tap. Words matter here: `n`, `u` and `lan` are the link's own claims, so the screen calls them that and says Hypernova can't check them. docs/SECURITY-MODEL.md, section 6, has the details:
   - **Every address, not the first.** A link can name a friendly listener nobody answers on and send the code to `u` next.
   - **Add waits 600 ms after a link arrives,** so a tap meant for what was on screen doesn't land on it. A newer link takes the place of the one on screen, as a rescan with a fresh code should, and resets the wait.
   - **Extras choose nothing.** An `intent://` link can carry `S.server=<id>`, which would have switched the server on screen.
   - **A link it can't read says so only if nothing else is on,** so a junk link can't cover a pairing under way.
   - **It stays over the screen rather than replacing it,** so Cancel leaves a chat's draft and place as they were. With nothing paired it takes the steps' place, so no camera runs behind it.
7. **A pairing no longer replaces a server on its word alone.** This was the hole left after the tap: an answer can say any `mac.id` and name, and `Servers.withPairing` took the place of whichever paired server matched, dropping its token. Now it must also have answered over the listener pinned to that server's stored fingerprint, or at that server's very base URL (`Servers.takesPlaceOf`). Otherwise it's added beside it. A fingerprint in the link alone proves nothing, as the link can carry the real server's and be answered elsewhere, so `Pairer.pair` says where it was pinned (`Pairing.pinnedAt`, never stored).
   - **What this costs:** the same server paired again whose certificate changed, or that was paired before there were fingerprints, comes up as a second entry, and the old one is Forgotten by hand. A `--purge` reinstall already did that, with its new id. A moved address, the usual case, is proven and replaces as before.
   - **A new server doesn't take a paired one's base URL** (the routes go by it: `ActiveRoutes`): it takes the address that answered.
   - **Not closed:** a server that answers, pinned to its own certificate, at the very address a paired server's base URL names (it took over that address) still shares that base, and so its route. Making the routes go by id instead is a bigger change. `DesktopTokens.replaceWithheld` also still goes by `sameComputer` alone, so on the desktop, with the keychain locked, a pairing claiming a waiting server's id drops that server's token. The desktop only pairs from a pasted link, and was left as it is.
8. **`hypernova pair` says Scan first.** The `Code:` line is gone. Nothing asked for it: no screen takes a typed code, and neither `daemon/test`, `docs/` nor `scripts/` read that line. The code is still in the link, and `code` stays in the `/local/pair/start` reply as asked: `daemon/test/lan.test.ts` reads it from `startPairing`, and nothing in the product does. The https link stays under the QR code for pasting on a desktop, and the desktop client doesn't change.

## The client in one line (owner, 2026-11-03)

The owner: "for the release there should also be a simple [Bash] script that I can run to install the client so there's a server Bash like get server and then a client get client or I could just run a curl on a URL and pipe it to bash".

1. **`scripts/get-client.sh`, at `https://ljmerza.github.io/Hypernova/get-client.sh`.** On each system: a `.deb` through `apt` with sudo, on Linux with `apt`; the tarball under the home on Linux x86_64 without it, or with `--tarball`; the `.dmg`'s app on a Mac; on Git Bash the `.msi`'s address. Linux on arm64 without `apt` has no package, and it says so. The whole script is functions with the call on its last line, so a connection dropped halfway through runs nothing. `test-get-client.sh` cuts it at bytes all the way along and checks.
2. **`get-server.sh` is a second address for `get.sh`**, the same file, from `pages.yml`. `get.sh` stays for good, and existing docs keep it, as installs out there use it. Only README's short version mentions the pair.
3. **The tarball goes in `~/.local/share/hypernova-client-app`, not `~/.local/share/hypernova-client`.** That second one is where the client keeps its key and pairing (`DataFolder`, `DesktopStorage.kt`), so an install replacing it would lose them. A test holds this.
4. **Each install of the tarball is a new folder, and a link, `current`, moves to it in one step,** with `mv -T`. At any moment there's a whole copy to open. A folder only goes once nothing running comes from it (`/proc/*/exe`), so an open copy isn't pulled from under itself. On a Mac the old `.app` is moved aside and the new one moved in, on one disk. Stopped between those two, it puts the old one back.
5. **Nothing is installed unchecked.** `SHA256SUMS` missing, not listing the file, or not matching all stop it. `android-release.yml` publishes in stages, so this happens for real: on 2026-11-03 the run asked at the moment v0.26.0 had its `.deb`s and no `SHA256SUMS`, and that was the answer. The sums come from the same place as the file, so they catch a bad download, not a tampered release: nothing here is signed.
6. **The `.deb` is downloaded in a folder under `/tmp` opened to all to read**, and not `$TMPDIR`, as `apt` reads a local file as its own user, `_apt`. Given a file it can't read, it says "Download is performed unsandboxed as root" and carries on. Run with a umask of 077 and a private `TMPDIR`, no such line.
7. **What a file fetched with `curl` carries, on a Mac: no quarantine flag.** The sources:
   - Apple's Information Property List Key Reference, `LSFileQuarantineEnabled`: `false` is "(Default) Files created by this app are not quarantined by default."
   - Howard Oakley, "Who decides to quarantine files?" (eclecticlight.co, 2025-12-08, on macOS 26 Tahoe): "The default rule for apps that don't run in a sandbox ... is that all new files they create don't have a quarantine xattr attached to them", and of `curl`, "It's simple to demonstrate that the download then doesn't have any quarantine xattr attached to it". "One notable exemption is the command tool `curl`, which is also used by malware to escape quarantine."
   - Oakley, "Launching apps in Sonoma 14.6.1: Full security" (2024-08-27), an ad-hoc signed app with no flag: "Gatekeeper assessment continues, with an XProtect scan", then "Launch then proceeds", with the first-launch prompt described only for quarantined apps.
   - Apple's Platform Security guide, "Gatekeeper and runtime protection": "all software in macOS is checked for known malicious content the first time it's opened".

   So we don't expect "Apple could not verify" after this script, and the script says that, says nobody has seen it on a person's Mac, and points at the steps if it's wrong. **Apple doesn't state any of this for `curl` by name**, and the launch itself was read about on macOS 14, not 26. The `.dmg` keeps no flag to pass on to what's copied from it. The script doesn't run `xattr` or `spctl`, or change a setting: it has nothing to strip, and wouldn't. `test-client-package.sh --get-client` looks at a file from a real `curl` on the Mac runners, so a macOS that changes this turns the run red.
8. **On Windows it prints the `.msi`'s address, not `msiexec`.** Windows asks to allow it and warns as it's unsigned, and the person should be looking at that, not a script that can't see it, and nothing here could try it. There's no PowerShell one-liner: what finds the latest release's file isn't small, and it would be one more unproven thing. WSL is treated as Linux, with a plain note first.
9. **A test-only way in:** `HYPERNOVA_CLIENT_DIR` takes the files and a `SHA256SUMS` from a folder, so CI can prove the script on a package no release has yet, by the same check. `HYPERNOVA_CLIENT_APPLICATIONS` stands in for `/Applications`, so the stand-in tests stay out of a real one on a Mac runner.
10. **In CI, after the package is attached.** A fault in this script turns the run red and costs no file.
11. **Proven and not.** Linux: piped as a person does against the real v0.25.0 files in Ubuntu 24.04, Debian 13 and Fedora containers, and `test-client-package.sh --get-client` (window, pairing, mutual TLS, through the tarball's link too) on the real v0.26.0 `.deb` and tarball in the first two. That took v0.26.0 as the probe has to come from the same commit as the package. **A Mac and Git Bash are unproven until CI:** stand-ins prove what it decides, and nothing about `hdiutil`, `ditto`, `pgrep` or Windows. Nobody has run it on their own Mac.

## Commands only from the app itself (2026-11-03)

A security fix, from a read of `MainActivity` that was reported unverified and then checked. `MainActivity` is exported, as the launcher and pairing links need, and `handle` obeyed any intent: any app could switch the server on screen, open a session, start the update or press "+2 h" with no pairing link. All of it was run from a separate, ordinary APK on Android 16's `hn-demo` before the fix, and refused or ignored after it.

1. **What was real, and what wasn't.** docs/SECURITY-MODEL.md, "Commands from outside the app", has the list. In short: the report was right about the door and overstated what's behind it. **"+2 h" went to the server with nothing asked** (`POST /v1/mac/awake` seen by the demo server; only a Mac has Backpack, and a Linux server answers that it's unavailable). **The update started** (`Idle -> NeedsPermission` in the log), but Android's own "Update this app?" and its signature check still stood, so "no question asked" holds for the download and not the install. **Switching server and opening a session needed an id** no other app can read, except `0` on an old install. No path through it answers an approval, sends text or reads anything back.
2. **A non-exported alias of `MainActivity` is the way in for commands.** The manifest's `.AppCommands`. The app's notifications, widget and shortcuts name it (`MainActivity.command`), `handle` checks the intent's component (`MainActivity.isCommand`), and from any other way in only a pairing link does anything. The choice, Android 10 up:

   | | Holds against another app? | Notifications and widgets already out | Cost |
   |---|---|---|---|
   | **`activity-alias`, `exported="false"` (chosen)** | Yes: Android refuses to start it for another app (a `SecurityException`, seen from an ordinary APK and from the shell), and nothing else can put that component on an intent | Fall through to opening the app | One manifest entry and one `if`. No hop, no window, the same `singleTask` activity |
   | A separate non-exported activity that forwards to `MainActivity` | Not on its own. What it forwards goes to the same exported activity, which still can't tell it from a stranger's, so it also needs state in the process or a secret | The same | An extra activity, a hop for every tap, and a second mechanism |
   | A secret per install, as an extra | Yes while it stays secret | Hold no secret, so the same | `shortcuts.xml` can't hold one, so static shortcuts would have to be dropped or exempted. It would have to be stored, and sits in every `PendingIntent` and shortcut. One leak is permanent until it's rotated, which breaks those |
   | Asking who sent it (`getLaunchedFromUid`, `getCallingPackage`, `getReferrer`) | No, on Android 10 | -- | `getLaunchedFromUid` is API 34 (`api-versions.xml`). `getCallingPackage` is null for a plain `startActivity`. A referrer is the caller's to set |
   | `android:intentMatchingFlags="enforceIntentFilter"` on `MainActivity` | Partly, on Android 16 only | -- | Doesn't stop an extra on the launcher's own action |
   | Moving the launcher to an exported alias, and making `MainActivity` itself not exported | Yes | The same | The launcher's component changes, which is what pinned icons go by. The same `if` is still needed for the alias, so nothing's gained |

   Why it holds on Android 10, from reading, as there's no Android 10 image here: `activity-alias` and its `exported` are API 1, and "If "false", the target activity can be launched through the alias only by components of the same application as the alias or applications with the same user ID" (developer.android.com, activity-alias). `ActivityRecord` in `android10-release` keeps `intent = _intent` and works out the real component separately, so the intent keeps the alias's name. On Android 16 that was watched: a notification's tap reached the running activity and a new one through the alias, and obeyed. **If some phone's Android dropped the alias from the intent, commands would stop and each tap would just open the app:** it fails harmless, not open.
3. **Shortcuts don't need the target exported.** They're started as the app that published them. `LauncherAppsService` says so in `android10-release` and on `main`: "Note the target activity doesn't have to be exported." Watched on Android 16: `START ... cmp=.../.AppCommands ... from uid` Hypernova's, for New session and for a project's. Android's docs don't say it, so it's the source that's relied on.
4. **What happens to what's already out, on the update.**
   - **Notifications: on Android 16 they're gone.** `adb install -r` took every posted Hypernova notification away at once. The app came back by itself (`MY_PACKAGE_REPLACED`) and posted approvals again from the snapshot, through the new code. A note that isn't in the snapshot, like the finished session's tried here, doesn't come back. Only `adb install` was watched, not a real in-app update, and no other Android.
   - **Where one survives** (not seen): its tap names `MainActivity`, the path a stranger's intent takes, so it opens the app and does nothing else. Allow, Deny, Reply and an automation's answers name `NotifyActions`, which didn't change, so they'd work as before.
   - **A widget already placed keeps working.** Placed under the old build, then updated in place: its rows, the computer's name, Allow, Deny and the header all did their jobs. Its rows go through `WidgetTap`, which didn't change; only where that goes next did.
   - **Shortcuts:** a static one is read again with the package and a project's republished, both seen naming the alias in `dumpsys shortcut`. A project's shortcut someone pinned earlier wasn't tried: at worst it opens the app.
5. **Share to Hypernova takes only another app's content.** Checking the share filters found a worse hole than the one reported, in the part meant to be open: it accepted `file://` and any `content://`, and opens them as this app. From an ordinary APK, `file:///data/data/<app>/shared_prefs/hypernova.xml` was in the cache as soon as the screen opened, and one tap on a destination sent `POST /v1/attachments?name=hypernova.xml` to that server. That file holds every server's device token. Now `readShared` refuses `file://` and this app's own providers, a `0@` user prefix included, which Android's own lookup ignores. **What this costs:** nothing that worked. The app has no storage permission, so a `file://` could only ever reach what's private to it, and senders have used `content://` since Android 7 made `file://` an error for them. No real `content://` share from another app was run on the emulator; `ShareTest` lends through a test provider.
6. **Not changed, on purpose: Allow on a notification, for an app with Notification access.** Found on the way, and proven on the emulator: a `NotificationListenerService` the person has granted access pressed Allow on a real approval, with nobody touching anything. A notification's buttons are handed to such apps and run as Hypernova, and "unlocked first" is the shade's rule for a person's press. The person has to grant that access by hand in Android's settings. Stopping it means taking Allow off the notification, or having the receiver ask, and either changes what a notification offers, so that's the owner's to decide. docs/SECURITY-MODEL.md says so.
7. **What the tests do and don't show.** `IntentGuardTest` runs each command through the real activity, `Core`, hubs and scripted daemons, by `create` and by `onNewIntent`, both ways in. Its manifest tests fail if anything new is exported, in the source or merged. A source scan fails if anything sends a command through the exported name. **Robolectric doesn't enforce `exported`,** so that Android does comes from the manifest plus the emulator. **The emulator's proof is from an ordinary APK of its own uid and no permissions,** because `am start` runs as the shell, which holds permissions no app has. The shell was refused the alias too, which is the stronger of the two. **Android 16 only.** There's no Android 10 to 15 image here; item 2 says what stands in for it. The desktop client has none of this: it has no intents, and `android/shared` doesn't name `MainActivity`.

## The time over the chat (2026-11-03)

The owner: "be nice to have time stamps on when somebody talked about something but I don't want it to take up more space ... if I leave a session running completed for a long time I come back to it I'm not sure when's the last time I had talked to it but then also like going back and figuring out like oh this conversation was two days ago for example way up here in the session I need to figure out a way to know like generally what time a message was but then not take up even more space". He chose this, proposed to him as "the floating label". **A deliberate exception to "nothing new on the session's own screen", asked for by the owner:** the label and one line on the message menu, and nothing else.

1. **While scrolling,** a small label over the chat says when what's at the top of the screen was said: `Today · 9:14 PM`, `Yesterday · 9:14 PM`, `Mon Nov 2 · 9:14 PM`, and with its year once it's an older one, `Mon Nov 3, 2025 · 9:14 PM`. "At the top" is the first row not wholly under the top bar; a folded run goes by its first call. It stays while a finger holds the list, and fades a second after the scroll stops.
2. **Only for the owner's own scroll.** The list moves by itself all the time, following Claude's output, and a label flickering over every reply would be worse than none. So it shows only while the list scrolls and the chat isn't following its tail, which a drag ends at once. A scroll by TalkBack or a mouse wheel ends the following only when it stops, so its first scroll shows nothing.
3. **On opening a session that's been quiet, the same label says so for 4 seconds:** `Last message 5 hours ago`, `Last message yesterday`, `Last message 2 days ago`, past 30 days the day itself, `Last message Sep 14`. Under a day it counts hours and after that the calendar, as a Completed card's stamp does.
   - **Quiet means 3 hours.** Lunch or a meeting isn't worth a word, an evening is, and at 3 hours "when did I last talk to this?" starts being a real question. It's one constant, `QUIET_AFTER_MS`.
   - **"Last message" is the newest of the owner's messages and Claude's replies** (a side question's line too), not a tool call or a notice.
   - **Not for a session at work,** or with agents at work or a prompt on its way, however long since a message: that isn't quiet.
   - **Once for each opening.** Coming back from a screen opened over the chat (an agent, a file) is the same opening, as for where the list lands.
   - **Not until it can be true:** while the server isn't linked or the history is loading it says nothing, and it waits half a second after, as a reload may bring something newer. If something newer comes while it shows, it goes at once. A scroll takes its place.
4. **The long-press menu's first line is the exact time,** to the second, in mono dim: `Mon Nov 2 · 9:14:07 PM` (always the date, never "Today"; the year when older). A line and not an action. TalkBack hears "Monday, November 2 at 9:14:07 PM".
5. **What has no real time shows none; nothing is made up.**
   - The Mac's `ts` missing (`Wire.event` falls back to this phone's clock so events still order, and marks it `WEvent.tsGuessed`) or 0: no time. That fallback isn't changed: approvals, running terminals and the copies of sent prompts all order and time by it.
   - A prompt on its way or queued has no event yet: no time. It hasn't been said.
   - Over a row with no time ("Show earlier", a queued prompt) the label goes by the next row on screen that has one. That's a real time of something on screen. With none on screen it doesn't show; a menu with none has no line; a chat with none has no quiet line.
   - A side question's line (`Asides.KEY`) has one: `askedAt`, the Mac's clock (for its first moment, while it's only on this phone, the phone's own).
6. **It takes no room and no touches.** It's a sibling of the list in the screen's `Box`, drawn over it, offset below the top bar, with no pointer input, so a tap, a long-press or a drag on it reaches the chat under it. `ChatTimeLabelTest` measures every row with and without it, and fails if it's moved into the header (tried: its rows moved 43 px; and given a pointer input, the touch and drag tests fail). The bars draw after it, so it can't cover the top bar, and the jump pill, the answer card and an opened status panel, all in the bottom bar, go over it rather than under: nothing there needed moving.
7. **The words follow the phone:** `DateFormat.is24HourFormat` and `getBestDateTimePattern` for its locale, with the comma after the weekday taken out to read as the owner's example does (`Mon Nov 2`, not `Mon, Nov 2`).
8. **Scrolling stays as it was.** No item gets a new field: `Hub.chatTimes` is a map from an item's key to its time, built from the events only when something asks, which nothing does until a scroll or an opening, and kept while the events are the same list. The label reads the list's layout in a `derivedStateOf` only while it shows, to the minute, so it recomposes itself when its words change and never the list (`aScrollRecomposesNothingOfTheListForIt`: each row composed once through 30 steps of scrolling, the screen around it once). On this computer's JVM, not a phone: with 6,000 events, building the map once 2 ms, finding the top row's time 0.2 µs, its words 13 µs. A 6,000-event demo session opened, dragged and said the right times on an emulator; its software GPU took seconds a frame, so that says nothing of smoothness, and **nobody has felt it on a real phone yet.**
9. **Reduce Motion:** it appears and goes at once, as other pop-ups do. **TalkBack doesn't hear it:** hidden from accessibility and no live region, so a fading label never interrupts; the times are on each message's menu.
10. **Not there:** an agent's own screen (it has no menu and isn't what was asked), the desktop client (it has no chat yet). **No server change.**

## The tabs on Settings' pages (2026-11-03)

The owner: "when in any setting some page I want the bottom navigation bar and to always show otherwise you have to keep clicking back when you're deep and nested also if somebody clicks settings again when you're in deeply nested it just takes you back to the main settings page". This changes point 6 of "Settings in sub pages" only in that the pages now have the tabs: they are still `Route`s on the stack, and Back is still one level.

1. **Which pages:** everything in `Route.isSettingsPage` (`data/Navigation.kt`): the server's page, Notifications, Session top bar, Card swipes, Typing and voice, Logs, the Connection log, Agents & providers, a provider's form, an agent's settings, its MCP servers and MCP form, plugins, a plugin and the plugin list, skills, environment variables and raw JSON. The `when` is exhaustive, so a new route has to be decided on. A web page opened from one of them keeps the tabs too (the testing checklist, a link on a page), and sits above them. Settings is the selected tab on all of them.
2. **Not over a session or an automation.** `inSettings` needs only Settings' pages over Home. A chat, its agents, its terminal, its files and an automation's editor have no tabs, and neither does Agents & providers opened from a chat's "Add a provider" or the editor's: Back goes straight back to what it's a detour from, and a tab would throw that away, an editor's unsaved words among them. A page opened over Home from elsewhere (a provider card, the offline card's connection log) has them, since it's over nothing that could be lost; Back from there is still the tab it came from, one level up.
3. **A tab goes straight there, and Settings is always its main page from the top.** From any depth, in one tap, and the same rule for coming back after another tab, rather than the place kept: that's what the owner's second sentence asks, one rule is simple to say, and a kept place would make the same tap mean two things. Back keeps the main page where it was, as it always has. The stack empties and a counter (`settingsFresh`) keys `SettingsTab`, so its saved scroll isn't what comes back. It has to count every tab, not only Settings: Home's saved state holds the list's old place until the tab is next drawn.
4. **Why a second bar over the screens, not the pages inside Home's Settings tab.** The pages are routes pushed over Home, in `AnimatedContent`, and Home draws the tabs. Drawing the pages inside Home would mean Home staying composed under them, its top bar hidden, its Statistics and its four Back handlers each told about the pages, and a second transition inside it. Moving the bar out of Home for good would drag Statistics with it. So `SettingsPageBar` (`home/TabBar.kt`) is the same `BottomTabs` Home draws, in the same place, drawn in `Screens` outside `AnimatedContent`, so it doesn't fade and scale with each page. From Home it appears at once over Home's own, which it hides; going back it fades over the one coming in. The cost is one blur input that isn't per screen (`BlurredBottomOverScreens`): as a page changes it sees both screens' lists for that moment, under the blur.
5. **Clearing the bar.** `screenBottom()` (`components/Kit.kt`) replaces the navigation bar's height in the pages' padding: the bar's measured height, which stands on the navigation bar, so that's never counted twice, and a large font is measured rather than guessed. Pages keep their own 32dp below their last row. Screens that aren't Settings' are unchanged.
6. **The keyboard.** The window doesn't resize (`enableEdgeToEdge`), so the keyboard comes up over the tabs, as it does over Home's: the tabs don't move and never sit over a field. Seen on an emulator: the tab stays at the same place with the keyboard open. **What was already so and isn't changed here:** nothing lifts a field near the bottom of a page above the keyboard. On a provider's form the same tap on the Sonnet slot leaves it under the keyboard in the build before this and in this one (same bounds, both builds).
7. **Unsaved words: a tab does what Back does.** Raw JSON is the one page that asks ("Leave without saving?", `leaving`), so a tab asks with the same words through `AskBeforeTabs`, and Cancel keeps the words and the page. A provider's form keeps what's typed in saved state when it's left, by Back or by a tab, and shows it again when reopened. The rest (MCP form, environment variables' add row, a plugin's marketplace) lose what's typed on leaving and don't ask, on Back and on a tab alike. Back's arrow on raw JSON asks but the system's back gesture doesn't: that was already so, and isn't changed here.
8. **Automations with All servers** says why not, as on Home, and the page stays. `rememberHomeTab` is unchanged: the tab it keeps is the one the tap sets.
9. **Sheets and dialogs** are windows of their own (`GlassWindow`), so they cover the tabs with nothing changed. **A jump** (`SettingsJump`) lands as it did, and the page has the tabs.
10. **The desktop client has no such bar,** and `inSettings` is unused there.

## Compact in the session sheet (2026-11-03)

The owner: "add a compact button to the session top bar menu somewhere". The bar's Compact chip only shows from 85% context, so the sheet the bar opens has a Compact row on Overview, at any fullness, for a Claude Code session that can change. It sends `/compact` as the chip does, once for a double tap, and closes the sheet. Codex sessions have none, as with the chip. The Commands tab's Compact, with its "Keep…" line, stays as it is.

## Carrying on after signing in again (2026-11-03)

The owner: "when my login expired all the sub agents through all my sessions stopped possibly not all of them but ones that hit the login let's make it away so there's a toggle pair [per] server or all where we automatically continue resume any sessions or sub agents once the authentication it's approved again default on". The rules are in docs/PROTOCOL.md, "After signing in again".

1. **What Claude Code does at a lost login, as measured** (2026-11-03, Claude Code 2.1.317, the real binary in a temp `HOME` and `CLAUDE_CONFIG_DIR` against a scripted API on loopback answering 401 `authentication_error`, in `-p`, in a terminal, and as a `claude --bg` job; a fake token, no real login expired, no model called):
   - **The sub agent ends as failed:** "Agent terminated early due to an API error: Please run /login · API Error: 401 …". Its transcript under `<session>/subagents/` has the error, with `isSidechain`.
   - **The parent doesn't stop because of that.** It gets a task notification with `<status>failed</status>`. Every sub agent in these runs came back as "Async agent launched" at once, also with `run_in_background: false`, so the parent was never waiting inside the Agent call.
   - **It stops at its own next request.** That's what really happens when a login is lost: the parent's request after the launch, or the one the notification wakes it for, gets the same 401, and the main transcript has `authentication_failed`, `isSidechain` false. So the session is `blocked: logged_out`, and that's what the daemon carries on. If the API had let the parent's own request through it carried on, with the failed notification in its context, and nothing here is needed.
   - **The process stays.** A terminal shows "Please run /login"; a `claude --bg` job stays alive with `state: "blocked"` and `needs: "login required — run /login · …"`. Only `-p` exits (1).
   - **It takes up a new login by itself.** With `.credentials.json` swapped by rename while the process ran, its next request used the new token, with no restart.
   - **A failed sub agent can be resumed, by its parent only.** `SendMessage` to its id answered `{"success":true,"message":"Resuming agent …","resumedAgentId":…}`, and it then finished `completed`. The launch result and the failed notification both say so ("The user can send it another message and resume it"). There's no command or setting for the daemon to do it from outside, as far as this looked.
   - In auto mode, a tool call whose safety check hits the 401 is "Not run · auto mode's check had no usable answer", so a sub agent can fail to start at all; the parent then hits the same 401.
   - **All of it together, once:** a second daemon in a temp `HOME` with `claude agents` on, real Claude Code, two jobs on a fake API, the first with a sub agent. Both went `blocked: logged_out`, the first also `needs_you` with "login required — run /login". With the login swapped on disk and a prompt in the second, a reply made the account `ok`; 5 s later the first was typed into through `claude attach`, its chat had one row "Continued by itself after signing in again", it called `SendMessage`, the sub agent finished, and it ended idle with nothing blocked. The second, where the prompt was typed, got no continuation.
2. **So it's the parent that's continued, and its words ask it to take up the sub agents:** "continue them with SendMessage, or run them again if you can't". Whether it does is Claude's reading of those words on the day. In the test above the fake API chose SendMessage, so the mechanism is measured and the choice isn't.
3. **How much of "sub agents" this covers:** those of sessions the login stopped, through their parent, once. A sub agent whose parent wasn't stopped gets nothing from this; its parent already knows it failed.
4. **Reuse.** An entry in `resume.json` like a limit's, with `login`. It waits in the existing `hidden` state, fires through `fire()`, keeps its rules on approvals, menus, unsent text and `fired`. No edit in `account.ts` at all: `resume.ts` reads the account when a block clears and at each look, so the sign-in path is untouched.
5. **A reply counts as back,** not only `clear(at, 'login')`. A `claude auth login` in a plain shell tells the daemon nothing, and the check can't end a sign-out an API error reported (docs/PROTOCOL.md, "Signing in again"), so without this he'd have to speak in one session and find the others not moving. A reply to a request sent after the sign-out began is better proof than a sign-in.
6. **Spacing: 5 s, then 4 s apart.** A limit's jitter is random across 30–90 s, there to miss the reset's rush; here the login is back for one person's sessions, so they only need not to land together, and sending in order is simple to test.
7. **A flip: one continuation per session per 15 minutes,** stricter than a limit's two re-arms: a limit passes by the clock, while a login that fails again won't be mended by trying, and each try is a request.
8. **Off, or held back, it says to Continue:** the ready card a limit uses, with its own title ("You're signed in again"), so nothing new is drawn on the chat screen but that title and the notice row. Before this, a session just went idle after a sign-in, with nothing saying it could go on. It needed a kind of its own, `signed_in`: `logged_out` would keep saying "Signed out" on Home and on the desktop client, which isn't changed here.
9. **The switch.** On the server's page (`LoginContinueRow.kt`), stored in that daemon's `settings.json`. For all at once, one row under the list of servers on the Settings tab, shown with two or more: the phone sets each connected server. Not "Same as all / Custom / Off", which is for what's kept on the phone: here each server holds its own, and a third state would have to live somewhere no server can see. One not connected keeps what it has, and one too old says it needs updating.
10. **Codex isn't covered:** it has its own login, and its sessions never have this block.
11. **Not proven:** a real login expiring, and a real `/login` or sign-in from the phone followed by this. Those are read through the existing "Login successful" and check paths, tested with stand-ins. A real expired token may read differently from the 401 used here (Claude Code tries to refresh first, and a refresh wasn't possible in these runs).

## Signing in from a session (2026-11-03)

The owner: "being able to log in didn't fully work I was able to select the subscription or the API I saw that but then after that I didn't show me the long URL to click to be able to login much less the input in order to paste the code that will get back on time authenticated".

1. **What he reached.** Reproduced with real Claude Code 2.1.317 in a signed-out scratch home, a test daemon and this app on an emulator. The app's own Sign in has no such menu, so that was a /login in a session. Its first screen, "Select login method:", is a menu, so it drew as buttons, under the transcript's banner as a title because the dialog's heavy top line wasn't read as one. The next screen, the address and "Paste code here if prompted >", has no menu, so the phone said "Claude is waiting in the terminal. It's asking something only the terminal shows." The live terminal does show the address, on nine lines, with its code prompt, but nothing said so. That he was on this route is inferred from his words: nobody saw his phone.
2. **A /login goes to the app's own sign-in.** From the box and the Commands tab, a sheet over whatever is open (`SignInSheet`) instead of typing into the terminal. A sheet rather than a page, as it's a detour from the chat. Codex's sessions keep their own slashes. `/login` left the "opens in the terminal" list, so the Commands tab doesn't say that any more. What the panel has to say (a code turned away, nothing to paste) is said in the sheet, in red: the app's toasts draw behind a sheet, as seen on the emulator.
3. **Subscription or API, then the existing screen.** Everywhere: Home's card, the blocked card and the sheet ask first, in place, then show the address and the code box as before. API is `claude auth login --console`, checked in its `--help` and on screen; nobody has finished one (5). Bedrock, Vertex and Foundry aren't offered, as there's no flag for them and they're providers here already. A failed sign-in's Try again keeps the choice.
4. **Signed in already is allowed.** A /login while signed in is someone changing account, and was the likeliest case for the owner, whose server is signed in. The sheet says it changes the account, and asks with `again`. Refusing it would have left that person worse off than the terminal did.
5. **What can't be proven here.** No real login can run: a Console sign-in's end, what `claude auth status` says after one (`applyCheck` treats any method but a subscription without a plan as fine), and whether a session already open picks up a sign-in made in another holder. On a first run, the session's own screen stays after the app's sign-in, since Esc does nothing there; restarting the session is the way on. A first run's theme picker, before the sign-in, isn't a sign-in and has no footer, so it isn't read as a menu or this: the session says Starting with no card, and the way on is the top bar's terminal. Not changed here, as Home's card offers Sign in while that shows.
6. **Claude Code's own sign-in on a terminal's screen, one card.** Typed in a terminal on the server, in a job, or a first run: `session.loginScreen`, and one card instead of its menus and the generic waiting card: **Sign in here** (Esc, then the sheet) and **Open the live terminal**. Buttons for its menus would only lead to the prompt no card can finish. Reading the address into a card instead was not done: it would put an address on the session and a second way to type a code, and `claude auth login` already does both with their rules.
7. **Sign in on the Mac only where there's a window.** The blocked card's panel offered it on Linux too, where it's refused with 501; Home's card already checked. Seen on the emulator while reproducing this.
8. **The desktop client has no sign-in,** and a `/login` typed there still goes to the terminal: for its parity list. `Hub.signIn` and `loginScreen` are in the shared module, so it would be a screen's worth of work.

## A keyboard over Settings' pages, and raw JSON's back gesture (2026-11-03)

Two things point 6 and 7 of "The tabs on Settings' pages" left as they were.

1. **A field low on a page scrolls clear of the keyboard.** Settings' pages (`Route.isSettingsPage`) now end where the keyboard starts (`imePadding`, in `App`), so a focused field scrolls above it with Compose's own bring-into-view. It's one place rather than a line on every page, and pages padded for `screenBottom()` are unchanged, which leaves a little more room under the very last row while the keyboard is up. The tab bar is drawn outside it and stays put, behind the keyboard. Other screens aren't touched.
2. **Raw JSON's system back asks as its arrow does**, with the same words. **The MCP form and environment variables' add row still lose what's typed without asking,** on Back and on a tab alike: that's the plan the owner has, not an oversight.

## Taking a photo asks for the camera first (2026-11-03)

The owner: "trying to take a picture crashes the app". Scan added the camera permission to the manifest, and Android refuses `ACTION_IMAGE_CAPTURE` with a SecurityException to an app that declares that permission without holding it, as far as Android's documents say. So a photo for an attachment crashed in v0.26.0 for anyone who hadn't allowed the camera. Camera now asks for the permission first, says so if it's refused, and catches the exception as a backstop.

## The chat on the desktop (2026-11-03)

The owner: "let's keep working on the desktop version to have [feature] parity with the Android app". Offered ways to start, he chose the chat on the desktop, reusing the phone's code, its drawing moved into a shared module so later features land on both, with the phone looking and behaving as it does now. Then, while it was being built, he made maintainability across operating systems the priority, with many features and customisation to come: no copies left beside the shared code, and each operating system's part behind a small named interface. This replaces "A question or a plan says to answer it on the phone" and "There's no chat here" in "The desktop app" 3 and 9, and "A question or a plan still says to answer on the phone" and "Not done: the chat" in "The desktop client's two panes" 4 and 7. It closes LATER L115 but for the chart.

1. **A shared Compose module, with the phone's classpath the same.** L115 warned that JetBrains' Compose UI on Android resolves to AndroidX's, at its own version, and that this had to be avoided or be chosen. A dependency report before anything moved: at 1.12.1 it resolves to the same AndroidX versions the phone's BOM has, through six pointer modules with no jar in them. `compileOnly` in common code keeps those off too: `:app`'s runtime classpath is the same list of modules, debug and release, plus `:sharedui`. The release APK went from 10163069 to 10179521 bytes. How it's laid out and each seam: docs/PLATFORMS.md, "The shared drawing".
2. **The package names of the kit and the tokens stayed, the chat has its own.** Nothing on the phone's screens changed for the kit. The chat moved to `ui.chat`, so `:app`'s files that use it, tests among them, have an import that says so; no test's name or assertion changed.
3. **Only `commonMain`, with one interface out.** It began with `expect` and `actual` in the module's own Android and desktop folders, and moved to `UiPlatform`, implemented in `:app` and `:desktop`, because `R`, `Str.id` and `MermaidBlock` are the app's anyway and a test or a reader can then see all of what's asked in one file.
4. **Rule 3 stands: a tool's ask is on the card, not in the chat.** The phone's approval card has Allow beside a command cut at 12 lines, and Always, and neither fits "Allow only beside the whole command". So a tool's ask still waiting isn't a row here; once answered it goes or, denied, folds, as on the phone. Questions and plans have nothing to run blind, so they're the phone's own card, in the chat, at its end. There's still no delay on Allow and Deny.
5. **The card stays in view, the facts went to the head of the chat.** The card with what's asked is over the chat, its command scrolling past 40% of the pane so Deny and Allow never leave. The facts as they were, ticking, are the chat's first row: a short conversation has them on screen and a long one is a scroll up, which is further than before. A fold or a third pane would have asked more of the window; say if it's the wrong way round.
6. **Selecting is per message and per terminal, as the phone's long press is, not one selection over the whole list.** A text under a selection takes the mouse's press, so one selection over the list would have stopped the rows' and the pills' words being clickable. So a drag can't cross from one message to the next. Ctrl+C and the right-click menu copy.
7. **The keys.** Tab goes from Deny and Allow straight to the box, not through every message on screen; Shift+Tab from the box comes back through them. Page Up and Down move the chat from wherever the keys are. Up, Down and Escape in the box stop there, so nothing chooses another session under words being typed: Escape leaves the box, and the next one lets go of the session as before. A selection's focus on an ask's command is only there after a press, so Tab still goes from the top bar to Deny.
8. **Each session's words wait while another is read, for as long as the window is open.** Not saved.
9. **A link asks first, as on a phone,** then the system's browser, Copy or Cancel. A dev server's address on the server says it's on the phone: on this computer `localhost` is some other program.
10. **A diagram says what it is and shows its source.** Mermaid is a bundled web page on a web view, and the desktop has no web view. Nothing draws it here.
11. **Settings has the Enter key too.** The same two choices and words, kept on this computer, with the phone's default of Send.
12. **What changed on the desktop for one kit:** pills are 44 dp by default rather than 40, its link mark is the phone's at 20 dp rather than 16, and the marks read the phone's clock. Hover and the focus ring are `UiInput`, nothing on the phone.
13. **An ended session has the chat too.** It's what the phone does and the owner's rule: "history acts like the resume slash command", a tap opens it to read and writing resumes it. So one chosen in History reads its transcript and starts nothing, and only sending from the box resumes it. The box says "It isn't running. Write to resume it…", and a note at the chat's start says it's in History. A session that ends while it's being read keeps its chat and what's typed. Rename stays out of History, as "The desktop client's Remove from list and Rename" 3 left it.
14. **Not proven.** Nothing was measured on a real GPU: the scroll's only numbers are from a JVM with none. Xvfb's window has no real browser and runs on software rendering. Nothing has run on a Mac or on Windows, and the phone has only had its tests and baselines, not a person's hands. Times in the label use English's order in other locales here (docs/PLATFORMS.md, `UiClock`).

## Background commands in the session sheet (2026-11-03)

The owner: "a feature so I could see background commands running it says in code one shell for example I can only see what agents are running but not background commands". Idea SS12. What Claude Code really says, measured on 2.1.317, is in docs/PROTOCOL.md, "Background commands".

1. **Under the agents, on the session sheet's Agents tab,** as their own part: "Background commands", with how many run. No part at all when a session has none, or an older server doesn't say. Each row: the command's first line, masked as a tool row is, how it stands, and how long.
2. **The count goes in the top bar's existing agents item:** `2 agents 1 shell`, or `1 shell` alone, and a tap opens the same tab. It reads cleanly because the bar already scrolls sideways and the item only shows while there's something to count, so nothing crowds it. It's off by default as before. Nothing new on the chat screen beyond that.
3. **"Shell" is Claude Code's word, and a Monitor counts as one.** Its footer says "1 shell, 1 monitor", its panel "2 active shells", and its Stop list gives a Monitor as a shell. The row says "Monitor, running". One count, for a one-line bar.
4. **Never a stale "running".** A command can outlive its session (measured: after a crash and after `claude stop`, and nothing is ever written about it), so when a session's process goes with no word on a command, the row says "Started 2h 5m ago, not known if it's still running", with no time ticking and no count. That also goes for Claude Code's own "stopped" on a resume, "didn't finish before the previous session ended", said in the measurement about a command that was still alive. A later Stop's list or notification settles it either way.
5. **Claude Code's list is believed over the daemon's reading:** each turn's Stop names what still runs, so an end that left no line (a Monitor running out, a subagent's) can't stay "running" past the next turn.
6. **It doesn't make a session busy.** Agents at work hold a session in "working"; a dev server left running for hours mustn't, nor keep Stay awake on, nor hold back the Finished notification. The count is only a count.
7. **The last 5 ended ones stay listed,** newest first, under those running: "running or finished" was asked for, and a long session's whole history would be clutter.
8. **Read-only, and no output viewer.** No stop button: from the phone it could only go through the model (`TaskStop`) or the terminal's panel, so it's left for the owner to ask for. The output file is read only for its last line, on the daemon, and isn't sent: the chat has nothing that would show it without a new route.
9. **Codex:** nothing. Not measured (Codex isn't installed where this was built).
10. **Not proven:** a real model's session (the measurement scripted the provider); the Agent SDK's sessions; a Mac (paths and `/private/tmp`); other Claude Code versions, which may word these differently. A shape not recognised shows nothing, or only what a Stop's list says.

## Agents' plans on the phone, and Markdown raw first (2026-11-03)

The owner, on a file he opened from a session, `~/.codex/plans/handoff-….md`, refused as a key place: "tried to view a handoff doc and got this. should be able to view MD files", "in formatted and raw view", and then "raw by default".

1. **A narrow exception, not an open folder.** A regular `*.md` whose real path is in or under `~/.codex/plans/` or `<CLAUDE_DIR>/plans/` can be read. The rest of those folders stays closed. One small function says so (`isAgentPlan`), and every other check in the daemon still counts these files as in a key place (docs/PROTOCOL.md, "Agents' plans"; docs/SECURITY-MODEL.md, section 8, risk 24).
2. **Reading only: no download, Share, Save or Open with,** though a project's Markdown has them. A download is one more way out of a folder that holds sign-ins, and he asked to view. The server says view only, and the phone leaves those buttons off with one line on why. The owner can widen this.
3. **No listing.** The viewer has Back and no breadcrumb, so it never needs the folder's names.
4. **Browse outside projects has no say.** It opens sessions' own folders, and this isn't one. A tool row in a session whose folder is closed opens a path that looks like a plan too (`mayBeAgentPlan`), and the server decides.
5. **Judged on the real path.** A link from elsewhere that leads to a plan opens that plan, the same file anyone may read at its own path. One that leads anywhere else stays closed, and so does a `plans` that is itself a link.
6. **Raw first, everywhere the viewer opens Markdown.** Every opening starts on Raw, with the switch to Formatted, for any `.md` or `.markdown`, not only plans. The viewer keeps nowhere a last choice or a per-file one, and none was added. Before this the viewer only had the raw lines, so nothing assumed formatted first.
7. **Formatted is the chat's Markdown,** the same drawing, a block to a row of a list so a long document scrolls. No second renderer. A file over the viewer's 512 KB of text is cut, in both views, with the line it already had.
8. **Where plans really are.** Claude Code's binary (2.1.317) defaults to `<config dir>/plans`; its `plansDirectory` setting has to stay inside the project, where a project's rules apply. Codex's (0.164.0) names no plans folder, so what writes to `~/.codex/plans` isn't Codex by itself: a skill or a prompt, as far as can be told. `CODEX_HOME` isn't followed: the exception is the key place's own `~/.codex`.
9. **Not proven.** Nothing has run on a real phone or a Mac's disk. A Mac's mixed case rests on its native realpath, as the key places do, and on a Linux test that another spelling is another folder.

## What's new, on the restart notice (2026-11-03)

The owner: "add link to specific release on the update provider notification with restart button". Home's notice about jobs on an older Claude Code has a link beside Restart all, "What's new in 2.1.317", to that version's page on Claude Code's GitHub releases. The version comes from the server, so it only goes into the address if it reads as a version. It goes straight to the browser, as Settings' links do. Only Claude Code has this notice, so there's no Codex link.

## The session sheet's MCP tab (2026-11-03)

The owner: "in the session top bar menu that pops up there should be a new tab for MCPs for us to manage the MCPs like reload them or disconnect whatever you can do in the terminal." (Dictated, "NPCs" in the original.) It replaces "Nothing on the chat screen ... the session sheet's copy of the menu leaves it out" of "A session's MCP servers" 1 only for the sheet: the chat screen itself has nothing new. Routes: `docs/PROTOCOL.md`, "A session's MCP connections".

### What was measured first

Claude Code 2.1.317 in a throwaway home, against the fake provider and a tiny real server that notes each start with its pid and parent's pid. Codex 0.164.0 in a throwaway `CODEX_HOME`, with a real thread and no turn.

| | Measured |
|---|---|
| Settings' reconnect (`POST /v1/agents/mcp/reconnect`) | **Doesn't reach a running session.** It connects from a process of its own and says "connected"; the session's server keeps its pid. Killed under the session (`kill -9`), it stays gone after Settings' reconnect |
| Settings' toggle | **Doesn't reach a running session either.** The file says off; the session's process keeps running and its `/mcp` says connected. Its `/mcp enable` then answers `"x" is still available in this session, but another session disabled it`, and its `/mcp reconnect` `x was disabled in another session — disable and re-enable it in /mcp, or restart, to reconnect` |
| `/mcp`'s screens | The list (`✔` connected, `✘` failed, `◯` off, `…` connecting, `⚠` needs sign-in, with a tool count, by scope, `↓ n more` past the screen, a name cut with `…` when narrow), a server's page (status, **its whole command or URL, its file**, then View tools, Reconnect, Disable, or Authenticate, or Re-authenticate and Clear authentication), and the tools (names and read-only or destructive) |
| What those options do | Reconnect ends the server's process and starts a new one; tools it added since reach the model at its next turn (a deferred-tools note, seen in the request). Disable ends it and adds the name to the folder's `disabledMcpServers`; Enable starts it and takes the name off. Another running session in that folder isn't touched |
| Typed forms | `/mcp reconnect <name>`, `/mcp enable <name>`, `/mcp disable <name>` do the same with no menu, names with spaces too, and answer in words (`Successfully reconnected to x`, `Failed to reconnect to x`, `MCP server "x" disabled`, `is already disabled`, `not found`, `x requires authentication`). `all` means every server. `/mcp reconnect all` only retries what's down |
| Busy (a 40 s turn) | **`/mcp` opens and every typed form runs at once, beside the turn, never queued.** The answer shows for a moment in the row above the box, right-aligned. Esc closes the dialog and the turn goes on; with no dialog up, Esc would stop the turn |
| What it leaves behind | A typed action is a local command in the transcript (`/mcp disable x`, its answer), so it shows in the chat as one, and goes to the model at its next turn under Claude Code's "run directly" caveat, as in a terminal. Opening `/mcp` and closing it writes nothing to the transcript. Each one goes in that terminal's ↑ history |
| A dead server, by itself | Killed under a session, its `/mcp` still said connected, 2 tools, while nothing was running: Claude Code's own view, which this can only repeat. A reconnect fixes it |
| A `claude agents` job | Through `claude attach`, the same screens and forms, on the job's own process (the new server's parent) |
| An SDK session | Each turn is one process. In a turn, `mcpServerStatus()`, `reconnectMcpServer()` and `toggleMcpServer()` all work on that process, though it was started with a string prompt; the toggle reaches the file too. After the turn the same call fails: there's nothing between turns, and the next turn connects afresh and honours the file. **Such a turn also starts a project's server nobody approved**: measured, and the known `non_interactive` rule of "Managing MCP servers" 4 |
| Sign-in | `claude mcp login` (Settings' way) changes nothing in a running session until `/mcp reconnect <name>` there, which then connects. `/mcp`'s own Authenticate shows the address and takes the pasted one on its screen |
| Not reaching a running session | A server added to the file: not listed, `MCP server "x" not found`, until a restart. A project's server approved after it started: the same. A server removed from the file: it keeps it, and reconnects it. **A changed definition in the file is what the next reconnect or enable runs** |
| Codex | `mcpServerStatus/list` with a `threadId` gives `runtimeStatus`, that thread's connection (`failed` for one killed under it, the rest `connected`). `config/value/write` then `config/mcpServer/reload` reach a loaded thread: off stops the server, on starts it. The reload brings back what's down and left a healthy server's pid alone, and there's no reload of one server. A thread not loaded is `thread not found` |

### The ways it could go

1. **Settings' routes, with the tab as a second door.** Cheap, and what's measured above says it would say "reconnected" while the session stays broken. Out.
2. **Drive `/mcp`'s menus by keys for everything.** It works, but each action is a walk through a list that scrolls and resets its cursor on the way back, past a page that prints the command, while another person may be at that terminal.
3. **Each kind through what reaches it, picked.** In a terminal or job, `/mcp <action> <name>` typed by `typeInto`, as `/btw` is; `/mcp` opened and read off the screen only to look (status, tool counts, a tool list), then closed. In an SDK turn, control requests on that turn's process. At rest, the folder's list, said to count from its next turn. Codex, the thread's own status and the reload. The menu is walked only for View tools, which has no typed form.

**Why 3:** it's the only one where the session's own state comes back, the typed forms are Claude Code's own interface for this and work while it's busy, and they need no walk past the page that prints a command.

### What was decided

1. **Never saying what didn't happen.** After every action the state is read back and that decides the `outcome`: `/mcp`'s own words alone never make it `done`. Connected and no answer on the screen is `unconfirmed`, since it isn't known to be a new connection. An older answer on the screen isn't this one: answers are counted first.
2. **What each kind offers,** decided on the server so a client can't get it wrong: reconnect and off or on for a terminal, a job or a turn; off or on at rest, for its next turn, with no reconnect; off or on for Codex, reconnect only for what's down; nothing for a terminal not shared or an ended session, which show what a new session in the folder would get and say that's what it is.
3. **Off and on count further than the session:** the folder's list, so new sessions there too, and the tab says so, as Claude Code's own does. Other running sessions keep theirs. For Codex it's everywhere, and it says that.
4. **Off in the file but on in the session** (another session, or Settings) is said on the row. "Keep on for new sessions" only changes the list, through Settings' own toggle, since `/mcp enable` won't and the connection needn't drop.
5. **A look opens `/mcp` in that terminal for a moment,** so the tab looks once as it's opened and at Check again, never on a timer, and a tool list only when it's asked for. Anyone at that terminal sees it flick. While it's up `watchMenus` leaves that session alone, or the phones would be offered it as a question.
6. **Esc only while a dialog of `/mcp` is on the screen,** checked just before it's pressed: with none, Esc would stop Claude's turn. There's still a moment between the look and the key.
7. **It won't type over anything.** Words in the box, a question or a menu up, a session still starting: nothing's typed, and it says why with Open the live terminal.
8. **Sign in is Settings' own sheet,** then a reconnect in the session, read back: signing in reaches a session only then. At rest it says its next turn connects. A claude.ai connector has no sign-in here.
9. **A name the terminal cut** is made whole from the files that name it. If it can't be (a plugin's on a narrow terminal), the row has no actions and points at the terminal, since a guessed name could be another server. A server called `all` is refused, as `/mcp` takes that for every server.
10. **Adding, removing and changing stay in Settings,** behind their fingerprint, with a link there. The tab's actions are those of "Managing MCP servers from the phone": enable, disable and reconnect of an existing server need no check. **The cost of that:** a reconnect or an enable runs what the file says now, so a definition changed outside Hypernova since the session started runs without one, as it would at Claude Code's next start or its own reconnect. The daemon doesn't know what a session started with.
11. **Nothing on the screen goes further than `mcp-screen.ts`:** a row's name, status and count, a page's options, a tool's name and hints. A page's command and file line are never read, and screens aren't logged. `claude mcp get` still isn't run and `~/.claude.json` stays closed.
12. **One place for what's shared:** `ui/mcp/McpParts.kt` has what Settings' screens and the tab say alike, status words, tool rows, the sign-in sheet, moved out of `McpScreens.kt`. `sessionMcp`'s allow-list says how each runs. The Home card's read-only sheet stays as it was.

### Not proven

A real provider's OAuth page from a phone, a plugin's or a claude.ai connector's row (the section words are from the binary, no such server here), and Codex through the daemon's real connection to a live shared app-server: its calls were measured on an app-server of the test's own, and the route's are tested with a stand-in. No real phone yet.

## The status bar's number, and its details when opened (2026-11-03)

The owner: "[between] my app and then the terminal there's two different timings so like it says that it's something working there's how long it's been working and how many tokens and what you're doing I'd like all these details but I don't want the bar to be bigger than default it just expandable to see all these other details at all times like you can see in the terminal". His two screenshots of one moment: the bar `Running cd ~/projects/_worktrees/… 0:09`; the terminal `Moonwalking… (8m 16s · ↓ 2.9k tokens)` under `Running 6 shell commands · 2m 13s…`, three messages queued.

1. **Why 0:09.** The bar's clock was the phone's: it counted from when the bar appeared on this screen, so opening the chat, coming back to it, or the bar going away and back (an approval with nothing else on) restarted it, whatever the comment said. `turnStartedAt` was right all along; the bar never read it. The queue wasn't it.
2. **What was measured** (Claude Code 2.1.317 in a throwaway home against the fake provider, `daemon/test/status-details-e2e.test.ts`): neither the spinner nor `turnStartedAt` restarts across tool calls. A prompt typed while a tool runs is taken in at the next tool's end, the same turn to both. One still queued when the last reply ends starts a turn of its own, both clocks and the spinner's tokens from 0. The spinner's `↓` is every reply's output tokens added up. **The spinner leaves out a dialog:** 6s before one that stood for 25 seconds, 7s after, where `turnStartedAt` is 32 seconds old. And `Running 6 shell commands`' 6 is a run of calls, finished ones too, `Ran 6 shell commands` when it ends, not six at once.
3. **The folded bar's number is the turn's time as the terminal counts it:** from `turnStartedAt`, less the stops for his answer (`Session.turn`), still `8:16` by its old format. A stop going on holds it still, amber. This replaces "through a stop for your answer" (2026-10-17) for the bar, or the two would differ by every dialog. The session sheet's "This turn", the top bar's stopwatch and Home's "for 12 min" stay on `turnStartedAt`, wall clock with the stops in, as they were agreed; they can read more than the bar after a dialog.
4. **Only its agents at work is no turn:** there the number is how long the oldest of them has run. A workflow keeps its own time and tokens. Only a Mac too old to say when the turn began gets the old count from the bar's appearing.
5. **Nothing new folded.** The same height and contents. This is the one exception to "nothing new on the session chat screen", and it's the opened panel only. So a plain line gets no chevron until it's open: one would take 20dp from the words and change the folded bar. A tap on it opens it, TalkBack hears "Show the details", and a bar with to-dos or agents keeps its chevron. With nothing to say (an older Mac, Thinking) it doesn't open.
6. **Opened, first in the list as one scroll with the agents and to-dos:** what it's doing whole (the command, its lines kept, wrapped, masked by the existing rules; a file tool's line with its whole path), then only what's known: This turn `8m 16s`, Tokens `↓ 2.9k tokens`, This step `2m 13s`, At once `6 commands`, from two up. It opens on them, and follows agents only as they move on. A long command is cut at the 2,000 characters a tool event keeps.
7. **Structured first.** The clock, the step and the count come from the session's state and tool events, and the app ticks them itself. The spinner is read only for tokens while a reply is being written, ahead of the transcript, at one reading in 5 seconds at most and through the look at each terminal the daemon already makes; wording it can't read is no number. So there's no broadcast per spinner frame, and no asking while the panel's open.
8. **"At once" is what's running now, not Claude Code's 6:** started (PreToolUse) and not finished. Six in one reply it runs in turn count as one each.
9. **By kind of session:** a shared terminal has all of it. An SDK session has all of it from the transcript, tokens once a reply is whole. Codex has what it's doing, the turn, the step, at once and its own token count, none of it from a screen. A Mac older than this shows the activity line and the turn's time, with the stops in.
10. **Background commands stay out** (the session sheet's Agents tab, another branch); a row of theirs would go last in the panel.

## One shared UI module, split by folder, with checks (2026-11-03)

The owner: "let's make sure that we are modularizing this correctly so shared components or whatever are properly architected I want to try to prioritize maintainability over all these different operating systems because I'll be adding a lot of features and customer ability". Of three ways, they chose one: "the option one is fine".

1. **One `:sharedui` module split by folder, with automated checks** (chosen). Simple to work in, and it can split later when build times hurt.
2. A separate module per feature: the strongest walls and faster builds at size, but too much build setup for one person, and too early.
3. Shared logic only, with each system drawing its own screens: it can't break another system, but every feature gets built twice, which is what this is meant to avoid.

The rules are in `CLAUDE.md`, "Architecture". `scripts/arch-check.py` and `:app:checkRuntimeClasspath` enforce them (`docs/PLATFORMS.md`, "The rules' checks"). Parts and state are shared; each system may lay them out its own way. Voice, the terminal, the camera, file picking and the widget stay per system.

## Sending never moves the chat (2026-11-04)

The owner: "if I scroll back somewhere and then try to send a command it'll automatically scroll all the way to the bottom I don't want to do this allow me to be able to send messages but it'll stay where I'm at the latest I can with this what's the latest button that appears".

1. **On both clients, a send leaves the chat where it is.** Scrolled back, the owner stays there, the message goes out, and Jump to latest stays on offer. At the end, following carries on as before.
2. **Every way of sending:** the box, voice, a quick prompt and Compact, Replace on the terminal's text card, and Allow & retry in the session sheet. None of them turns following on now.
3. **What still goes to the end:** opening a session, Jump to latest, and scrolling down to the end by hand. Continue on a paused session and a limit card's move to another provider or model do too: those are the owner asking to carry on and see it, and they were left as they were.
4. **The keyboard and a growing box** only move a list that's following already.
