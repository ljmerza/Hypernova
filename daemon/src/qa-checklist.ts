// What the owner checks. Order matters: each section builds on the previous one.
export const CHECKLIST: { title: string; items: { id: string; title: string; how?: string }[] }[] = [
  {
    title: 'Mac setup',
    items: [
      { id: 'mac-open', title: 'Hypernova opens and shows the setup guide', how: 'Finder → Programming → hypernova → dist → Hypernova.app. It is unsigned, so the first time: right-click → Open → Open. It offers to move itself to Applications (say yes). A nova mark appears in the menu bar and the setup window opens.' },
      { id: 'mac-claude-node', title: 'It finds Claude Code and Node by itself', how: 'The first two steps should turn green without you doing anything.' },
      { id: 'mac-service', title: 'Installing the background service works', how: 'Step 3, "Start background service". It should turn green within a few seconds. (This checklist page is served by that service, so it opens from the menu bar once this step is done.)' },
      { id: 'mac-integration', title: 'Connecting Claude Code is clear and works', how: 'Read the explanation, then connect. Your status line in Claude Code should look exactly as before. Then open a new Alacritty tab or window: the shared terminals start there.' },
      { id: 'mac-tailscale', title: 'Phone access through Tailscale is on, and the expiry warning makes sense', how: 'Step 5 is already on (it was switched on during the night\'s testing). It also warns that this Mac\'s Tailscale key expires on Oct 20: disable key expiry for the Mac and the phone in the Tailscale admin console.' },
      { id: 'mac-apk', title: 'Getting the app onto the phone with the QR code works', how: 'Step 6, "Get the app": scan it with the phone camera (Tailscale on), download, allow installs from the browser when Android asks, and install. It updates the Hypernova you already have.' },
      { id: 'mac-pair', title: 'Pairing with the QR code works', how: 'Tap "I have the app", then scan the pairing code with the camera. The link opens a small page that hands over to Hypernova; it pairs, and the Mac shows "Paired with …". If the camera only shows text, use "Paste pairing link" in the app.' },
    ],
  },
  {
    title: 'Phone basics',
    items: [
      { id: 'ph-onboarding', title: 'The first screens explain what to do', how: 'Before pairing: the guide (Tailscale, Mac, scan). Is anything confusing?' },
      { id: 'ph-home', title: 'The home sky shows your real projects and sessions', how: 'Planets are your projects, moons are sessions. Tap a planet to zoom. Sessions lists the live ones, History the ended ones.' },
      { id: 'ph-notify', title: 'Approvals reach you with the app closed or the phone locked', how: 'Allow notifications when asked. A quiet "MacBook Air · 1 working" notification stays while connected. Lock the phone, start something that needs approval: a "Needs you" notification should arrive; tapping it opens the session. Settings → "Stay connected in the background" turns this off.' },
      { id: 'ph-usage', title: 'The sun matches your real usage', how: 'Compare the sun\'s "% · time" with /usage in Claude Code.' },
      { id: 'ph-connection', title: 'Connection state is honest', how: 'Turn Tailscale off on the phone for a moment, then on. The app should say it is reconnecting, then recover by itself.' },
    ],
  },
  {
    title: 'Sessions from the phone',
    items: [
      { id: 'ph-newproject', title: 'Create a new project folder from the phone', how: 'New session → New project → give it a name. A folder appears in ~/Programming on the Mac (with git initialised).' },
      { id: 'ph-newsession', title: 'Start a session and watch it work live', how: 'Ask for something small, like "make an index.html with a starfield". Text should stream in; tool cards and terminals should appear as it works.' },
      { id: 'ph-approval', title: 'Approvals: Allow, Always and Deny', how: 'Ask it to run a shell command (for example "run git status"). Answer from the phone. Try all three over a few requests.' },
      { id: 'ph-question', title: 'Questions and plans', how: 'Ask it to "ask me a multiple-choice question before you start", and try plan mode. Answer from the phone.' },
      { id: 'ph-interrupt', title: 'Interrupt stops it', how: 'Start something long, then press Interrupt.' },
      { id: 'ph-queue', title: 'Sending another prompt while it works queues it', how: 'Type a second prompt while it is busy. It shows at the bottom as "YOU · QUEUED", under "Claude reads it when this step finishes", and becomes your message when Claude takes it.' },
      { id: 'ph-continue', title: 'The 10-minute away rule and Continue', how: 'Optional: leave an approval unanswered for 10 minutes. It should be denied with a note, and a Continue button should bring it back.' },
    ],
  },
  {
    title: 'Sessions from the terminal',
    items: [
      { id: 'tm-appear', title: 'A session started in a terminal appears on the phone', how: 'In a new Alacritty window, run claude in any project and ask something. It shows up on the phone with its messages. claude should look and feel exactly as before.' },
      { id: 'tm-approval', title: 'Its approvals reach the phone, and the terminal can answer too', how: 'Approve one from the phone, and another from the terminal. When the terminal answers first, the phone card should close.' },
      { id: 'tm-continue', title: 'Typing into a terminal session from the phone', how: 'Send a prompt from the phone to that session. It should appear in your Alacritty window as if you typed it, in the same session (no copy).' },
      { id: 'tm-plain', title: 'Plain claude commands still work', how: 'claude --version, claude mcp list, and echo hi | claude -p "say ok" should behave as before. command claude skips sharing.' },
    ],
  },
  {
    title: 'Shared terminals',
    items: [
      { id: 'st-view', title: 'The live terminal on the phone', how: 'In a session, tap the terminal icon at the top right. You should see the real Claude Code screen. Pinch to change the text size.' },
      { id: 'st-keys', title: 'Typing and keys from the phone', how: 'Tap the screen to type. Try the key bar: esc, tab, ⇧tab (cycles modes), arrows in a menu (like /model), ^C, paste, Enter.' },
      { id: 'st-size', title: 'The size follows whoever uses it', how: 'With the session open on both: the phone shows the Mac window whole (small) while you only watch, fits the phone once you type there, and goes back to the Mac when you type on the Mac.' },
      { id: 'st-close', title: 'Closing a window, and handing a session to the phone', how: 'Close the Alacritty window of a terminal session: it ends about 30 seconds later, like before. Now open another one on the phone first, then close its window: it keeps going on the phone. Or press Ctrl+\\ in the window to detach. The menu bar shows Open next to running sessions; that brings one back, as it is now.' },
      { id: 'st-open', title: 'Open on the Mac from the phone', how: 'Start a session on the phone, then in its terminal view tap the laptop icon. It should open in Alacritty on your Mac, live.' },
      { id: 'st-restart', title: 'Sessions survive the background service restarting', how: 'Menu bar → Restart background service. Running shared sessions keep running and reappear on the phone a few seconds later.' },
    ],
  },
  {
    title: 'Attachments',
    items: [
      { id: 'at-photo', title: 'Attach a photo', how: 'In a session, tap the paperclip, then Photos. Pick one or two, add a question, send. The chips show a spinner until they\'re on the Mac; Claude should see the picture.' },
      { id: 'at-camera', title: 'Take a photo for Claude', how: 'Paperclip → Camera. Take a picture of something with text (a screen, a sign) and ask what it says.' },
      { id: 'at-file', title: 'Attach a file', how: 'Paperclip → Files. Pick a PDF or a text file and ask about it. Also try sending files with no text at all.' },
      { id: 'at-terminal', title: 'In a shared terminal', how: 'Send a photo to a session that\'s open in Alacritty. The window shows @"…/attachments/…" before your text, and Claude reads it.' },
      { id: 'at-new', title: 'Start a session with a file', how: 'New session: under the prompt, Photos / Camera / Files. Attach something and start.' },
      { id: 'at-chat', title: 'Sent files in the chat', how: 'The message shows thumbnails (pictures) or tiles (files) above its text. Tap one to open it.' },
    ],
  },
  {
    title: 'Notifications and updates',
    items: [
      { id: 'nt-banner', title: 'A banner for another session', how: 'Open one session on the phone, and let another one finish, ask for approval or hit an error. A banner slides in at the top; tap it to go there. Swipe it up to dismiss.' },
      { id: 'nt-quiet', title: 'No banner for the session you\'re in', how: 'Stay in a session\'s chat (or its terminal view) while it finishes: no banner for it.' },
      { id: 'nt-background', title: 'Notifications in the background', how: 'Leave the app and let a session finish: a "Session updates" notification. Tap it to open the session.' },
      { id: 'nt-update', title: 'Update available', how: 'After the Mac gets a newer app (the service update), the phone shows an Update banner once, and Settings shows "A newer Hypernova is on your Mac" with Get the update.' },
      { id: 'nt-planets', title: 'Planets in the sky, and the new icon', how: 'The app icon (and the Mac menu bar icon) is the little planet. On the home screen, projects are lit planets that turn while a session in them works; longer sessions make bigger planets; each trails a fading tail instead of a whole orbit.' },
      { id: 'nt-commands', title: 'Commands and skills after "/"', how: 'In a session, type / in the prompt box: your skills and commands, then Claude Code\'s, each with a line on what it does. Type a few letters to narrow it; tap one and it goes in the box with what it takes shown above. The new-session prompt has the same list.' },
      { id: 'nt-command-output', title: 'Commands show as sent, with their answer', how: 'In a shared session, send /effort high from the phone: it shows as "/effort high", stops saying Sending, and Claude Code\'s answer appears under it. Then send /doesnotexist: it doesn\'t stay on Sending, and a warning says the command is unknown.' },
      { id: 'nt-star', title: 'The sun is a real star', how: 'On the home screen the sun has a soft glow, a corona of uneven rays, diffraction spikes and a faint twinkle. As the 5-hour usage climbs it grows, warms to gold and amber, and past 75% gets sunspots and arches of plasma at its edge. The readout under it stays readable.' },
      { id: 'nt-menu', title: 'Terminal menus as buttons', how: 'In a shared session, send /model from the phone: the picker shows as a card. Turn on "Use this session only" and pick a model; answer "Switch model?" there too. Also try Cancel. In the terminal, Claude Code says what you picked.' },
      { id: 'nt-busy', title: 'Unsent text in the terminal', how: 'Type something in a shared session\'s terminal on the Mac without sending, then send from the phone: a card shows that text with Replace and send. Also: send, press Interrupt at once, then send again: it just goes.' },
      { id: 'nt-mode', title: 'Change the permission mode', how: 'In a session, tap the status line (IDLE · model · mode) and pick Ask, Edits, Plan or Auto. A shared terminal on the Mac switches too.' },
      { id: 'nt-copy', title: 'Copy text', how: 'Long-press a message (yours or Claude\'s) to select and copy it.' },
      { id: 'nt-tables', title: 'Tables in replies', how: 'Ask Claude for a table with a number column (for example "compare three options in a table, with their cost"): it shows as a card, numbers on the right in mono, and nothing scrolls on your phone. Then ask for a wide one (six or more columns): it wraps text to fit, and only if it still can\'t fit does it scroll sideways, its edges fading. In plan mode, ask for a plan with a table in it: it sits in the plan card. Long-press a cell and drag across two rows, then paste somewhere: one cell per line. With TalkBack on, each row reads as one stop ("Option A. Cost: $3"), and the actions menu has Copy table.' },
      { id: 'nt-string', title: 'Claude at work', how: 'Send a prompt: the status line at the top shows what Claude is doing (THINKING, BASH …) with a timer, and the little planet at the start of the line spins until the reply is in.' },
      { id: 'nt-session', title: 'Allow all web pages this session', how: 'Ask for some web research in Ask mode. On a web page approval, tap "Allow all web pages this session": the other page approvals waiting go too, and no more come for that session. Commands still ask.' },
      { id: 'nt-queued', title: 'A message sent while Claude works says it\'s queued', how: 'In a shared session, ask for something long (a build), and send another message while it runs. It sits at the bottom as "YOU · QUEUED" with "Claude reads it when this step finishes"; the top line keeps saying what Claude does, with "1 queued". When the step ends it becomes your message, without jumping. Also type one in the Mac terminal while Claude works: the phone shows it as queued too. Then queue one from the phone and press Up in the terminal: it leaves the chat and comes back into the phone\'s message box. Optional, in a session Hypernova runs without a terminal (no terminal icon at the top, which only happens when shared terminals can\'t start): Interrupt with one queued; a notice says it wasn\'t sent, and its text is back in the box.' },
      { id: 'nt-old', title: 'Older sessions too', how: 'In a session started before this update (like a long-running one), try Replace and send and changing the mode: they work there as well.' },
    ],
  },
  {
    title: 'Providers',
    items: [
      { id: 'pv-add', title: 'Add OpenRouter (or a custom provider)', how: 'Settings → Providers (phone) or Providers… (Mac menu). Add your key and a model, e.g. openai/gpt-5 or anthropic/claude-sonnet-4.5.' },
      { id: 'pv-run', title: 'A session runs on that provider', how: 'Start a new session and pick the provider. It should answer, and use tools.' },
      { id: 'pv-balance', title: 'OpenRouter shows what\'s left', how: 'Settings → Providers: the OpenRouter row ends in "$4.20 left" (or "$1.10 today" for a key without a cap). Save a new key: "Key works · $… left". On the Mac, Providers… has the same line and a Check button.' },
      { id: 'pv-companion', title: 'Sessions on OpenRouter orbit their own star', how: 'Run a session on OpenRouter: a small white star with ringed moons shows at the sky\'s lower right ("OPENROUTER · 1", what\'s left under it). When the subscription\'s sun is dead (the session limit), it keeps shining and its moons keep moving.' },
      { id: 'pv-auto', title: 'At a limit, the card says "Continues by itself at …"', how: 'When a session hits the session limit, its card reads "Continues by itself at 2:00" with Don\'t. Don\'t turns it into "Back at 2:00" and "Continue automatically at 2:00"; tapping that turns it back on. In a shared terminal it presses Esc or uses /rate-limit-options there: check the terminal agrees, also after Don\'t then Continue automatically. If the Mac slept through the reset ("press enter to continue", "continue" in the box), Continue on the phone sends it.' },
      { id: 'pv-credits', title: 'Out of credits says so, with the balance', how: 'When OpenRouter runs out: the card reads "OpenRouter · out of credits", "$0.00 left", with Add credits, Try again and Continue on your subscription. A notification says so once.' },
    ],
  },
  {
    title: 'Artifacts',
    items: [
      { id: 'af-files', title: 'Browse a project\'s files', how: 'In a session, the folder icon at the top right; also the "Files changed" strip. Open an image, some code.' },
      { id: 'af-html', title: 'Open a web page the agent made', how: 'Ask for a small HTML page, then open it in the app.' },
      { id: 'af-dev', title: 'Open a running dev server', how: 'Ask it to start a dev server (e.g. Vite). Tap "Open preview" on the localhost link.' },
    ],
  },
  {
    title: 'Mac menu bar',
    items: [
      { id: 'mb-status', title: 'The menu shows sessions, usage and your phone', how: 'Click the nova mark in the menu bar.' },
      { id: 'mb-providers', title: 'Providers window works', how: 'Providers… from the menu.' },
    ],
  },
  {
    title: 'Voice',
    items: [
      { id: 'mac-voice', title: 'Setting up voice on the Mac is clear', how: 'Setup\'s Voice step (or Settings → Voice… in the menu bar): paste a Soniox key from console.soniox.com and Connect. It checks the key with a moment of silence and says "Connected. Your phone gets a mic in the composer." Not now skips it; the menu bar then offers it once.' },
      { id: 'ph-voice-hold', title: 'Hold to talk: words appear as you speak and are in the box when you let go', how: 'Open a session with an empty box: the mic sits where Send is. Hold it and say a sentence. The capsule above shows your words as they settle (the newest dim), the rings round its planet move with your voice. Let go: the words are already in the box, Send one tap away. The first time, Android asks for the microphone.' },
      { id: 'ph-voice-free', title: 'Tap for hands-free, then Done', how: 'Tap the mic instead of holding it: it keeps listening (HANDS-FREE) with Cancel and Done. Say something longer, then Done (or tap the check, or Back). Typing in the box meanwhile is kept; the words go after it.' },
      { id: 'ph-voice-cancel', title: 'Sliding away cancels', how: 'Hold the mic and slide your finger up and away: the capsule turns red, LET GO TO CANCEL. Let go: nothing goes in the box. Slide back before letting go: it listens on.' },
      { id: 'ph-voice-sheet', title: 'Dictating a new session\'s prompt', how: 'New session: the mic is at the end of the PROMPT line. Hold or tap it; the field becomes the capsule, and your words land in the field.' },
      { id: 'ph-voice-more', title: 'Hold Send to add more', how: 'With text in the box, hold Send for a moment: it listens, and what you say is added after the text. A tap still sends. Also try sending right as you let go: it waits for the last words.' },
    ],
  },
  {
    title: 'Automations',
    items: [
      { id: 'au-brief', title: 'Morning brief from the template tells you what changed', how: 'Automations tab → Start from → Morning brief, pick a project, and for a quick try set the time two minutes ahead (today\'s day on). Save. When it\'s due, a session titled "Morning brief, <today>" with an AUTO chip starts by itself, and a notification brings its first sentence. Its card shows the result and Next.' },
      { id: 'au-run-now', title: 'Run now', how: 'Open a saved automation and tap Run now. "Started", and its card says RUNNING with a turning planet; tap the status line to watch the session. Stop on its row ends it.' },
      { id: 'au-asleep', title: 'The Mac asleep at the time, then woken', how: 'Set an automation a few minutes ahead, put the Mac to sleep (lid closed on power, or Apple menu → Sleep) past that time, then wake it. Within half a minute it runs, and its row says "Ran late · the Mac was asleep at …". Asleep more than 6 hours: "Missed".' },
      { id: 'au-pause-mac', title: 'Pause all from the Mac', how: 'Menu bar popover → the automations line, or Settings → Run automations off. The phone\'s tab says Paused; nothing runs until you resume, and what fell due while paused isn\'t run later.' },
      { id: 'au-bypass', title: '"No asking" asks for your fingerprint', how: 'In an automation\'s PERMISSIONS choose No asking: a red warning shows; Save asks for the fingerprint or screen lock first.' },
      { id: 'au-battery', title: 'Wrap up on low battery', how: 'On battery, with a session working, use the Wrap up on low battery template with the level just under the battery now (or wait for 15%). The working session gets "The Mac\'s battery is at …" typed in and wraps up; a notification says so. It fires once per discharge.' },
      { id: 'au-allow-next', title: '"Allow next time" lets the next run through', how: 'Give an Allowed-only automation a prompt that needs a command it isn\'t allowed (say "run the tests"). Its run row shows Allow next time: [Bash(npm test)]. Tap it, then Run now: this time the command runs.' },
      { id: 'au-limit', title: 'Carry on after the limit', how: 'Next time you hit the 5-hour limit with a session mid-task, have the Carry on after the limit template on. A minute after the reset the session carries on by itself (if Claude Code didn\'t already), and you get its result.' },
    ],
  },
  {
    title: 'Overall',
    items: [
      { id: 'all-look', title: 'It looks and feels right', how: 'Anything off in the design, motion, text or flow.' },
      { id: 'all-speed', title: 'It feels fast and doesn\'t drain the battery', how: 'Scrolling, opening sessions, and the phone\'s battery after an hour.' },
    ],
  },
]
