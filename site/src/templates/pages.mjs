// The site's pages. The words come from the README, docs/DECISIONS.md and the app's own strings;
// the install pages are rendered from docs/INSTALL.md and docs/LINUX.md by build.mjs.
import { esc, icon, seam, chip, model, shot, page, crumbs, person, mark } from './parts.mjs';

const TAGLINE = 'A control room for Claude Code and Codex on Android, Mac and Linux';

// ── Home ───────────────────────────────────────────────────────────────────────────────────────

const HIGHLIGHTS = [
  ['phone-home', 'Your sessions as a sky.', 'The sun is an accretion disk, and the disk is your 5-hour window: it drains as you spend it, goes dark at the limit and refills when the window resets. Projects orbit it as planets.',
    'Home: the sun is an accretion disk that shows the 5-hour usage, projects orbit as planets, session cards with a NEEDS YOU chip, the model and the mode below'],
  ['phone-chat', 'Approve from anywhere.', 'Live output and the real terminal. Allow, deny or answer from the phone; risky commands get a second look.',
    'A session: the usage strip, the agent\'s edits and a build, and a NEEDS YOU card with Deny, Always and Allow'],
  ['phone-automations', 'Automations.', 'Morning briefs, “carry on after the limit”, tests when the Mac is plugged in. Never on paid credits without your yes.',
    'Automations: each card is a plain-English sentence with its state and next run'],
  ['phone-dictation', 'Talk to it.', 'Real-time speech recognition (Soniox, with your own key), and replies read aloud through your headphones.',
    'Dictation: the capsule\'s light curve rises with the voice while the words appear'],
  ['phone-new-session', 'Start anything.', 'Type or say the prompt, then pick a project (found by a few letters), a model and a mode. Any provider: Anthropic, OpenRouter or your own gateway.',
    'The new-session sheet: a prompt first, then the project, model and permission mode as chips'],
  ['phone-statistics', 'Honest statistics.', 'Hours, tokens and sessions, not badges. Each day is a star in a galaxy.',
    'Statistics: hours the agents worked, tokens, sessions, and the last 30 days as a spiral galaxy'],
];
const SMALL = [
  ['phone-sky-limit', 'Limits you can read.', 'The disk empties, then a countdown.', 'The sky at the 5-hour limit: the disk is spent, a countdown says when it refills'],
  ['phone-model-picker', 'Switch models', 'in the chat.', 'The model picker in a session: every model the provider offers, with each one\'s own weekly use'],
  ['phone-files', 'Files and downloads', 'from the host.', 'A file download with its progress, a Cancel button and, when it is done, Share, Save and Open with'],
  ['phone-dialog', 'Calm pop-ups,', 'one short line.', 'A floating glass pop-up over a blurred scrim: an eyebrow, a title, one line, two pills'],
];
const ALSO = [
  ['live', 'Connect your way', 'Pair by scanning a QR code or tapping a link. On your Wi‑Fi the phone talks to the host directly over mutual TLS; away from home it uses your Tailscale, the Tailscale app or Hypernova\'s own built-in node. A connection log shows every decision.'],
  ['alert', 'Notifications you can act on', 'Allow and Deny right on an approval\'s notification (after unlocking), a Reply field, and attention cards on Home you swipe away, with Undo.'],
  ['rest', 'Files and downloads', 'Browse a session\'s folder, view a file, download it with progress and resume, then Share, Save or Open with it. Files with keys stay on the host.'],
  ['rest', 'Updates in the app', 'The Mac hands the phone a newer app, and the app installs it, signed with the same key or not at all.'],
  ['live', 'Telescope', 'See what the agent built: the terminal, a local web page, an Android emulator or the iOS Simulator, a window or an image, refreshing live.'],
  ['rest', 'Stay awake and Backpack', 'Keep the Mac working with its lid closed in a bag, with battery and heat guards, and “+2 h” before Backpack ends.'],
  ['rest', 'Limits that explain themselves', 'When a limit lifts, a “Continue now” button when Claude Code would carry on by itself, and other providers\' credit and rate limits.'],
  ['rest', 'Everyday tools', 'The context ring and session cost, the agent\'s to-do list, Rewind, quick prompts, what changed (with diffs and a Commit button), the share target, shortcuts and widgets.'],
  ['rest', 'After a restart', 'Sessions cut short by a reboot wait on Home with “Resume”. History keeps 30 days.'],
];

function sessionCards() {
  return `<div class="sessions" role="list" aria-label="Three example sessions as the app shows them">
  <article class="card edge session" role="listitem"><span class="edge-light" aria-hidden="true"></span>
    ${seam('0STEMS', { state: 'alert', trail: '<span class="mono">c7</span> <span class="mono">⏱ 1m</span>' })}
    <h3>release landing copy</h3>
    <p class="doing amber">wants to run git push origin main</p>
    <div class="foot">${chip('Needs you', 'filled')}<span class="end">${model('Opus')}<span class="mode">${icon.shield()}ask first</span></span></div>
  </article>
  <article class="card session" role="listitem">
    ${seam('HYPERNOVA', { state: 'live', trail: '<span class="mono">a3</span> <span class="mono">⏱ now</span>' })}
    <h3>cosmos renderer</h3>
    <p class="doing accent">Edit: android/…/fx/Disk.kt</p>
    <div class="foot">${chip('Working', 'live')}${chip('2 agents', 'live')}<span class="end">${model('Sonnet')}<span class="mode">${icon.edit()}accept edits</span></span></div>
  </article>
  <article class="card session" role="listitem">
    ${seam('LANDING', { state: 'live', trail: '<span class="mono">e2</span> <span class="mono">⏱ 4m</span>' })}
    <h3>pricing page tests</h3>
    <p class="doing accent">Bash: npm test</p>
    <div class="foot">${chip('Working', 'live')}<span class="model">Codex</span><span class="end">${model('Default')}</span></div>
  </article>
</div>`;
}

function approval() {
  return `<figure class="approval" aria-label="An approval as the app shows it">
  <div class="band"><span>Needs you</span><span class="mono">auto-deny in 9m</span><span class="end">Bash</span></div>
  <div class="body">
    <p class="why"><span class="label">Hypernova</span><span>Ship the Android build to the phone</span></p>
    <code class="well"><span class="p">$ </span>git push origin main</code>
    <div class="acts" aria-hidden="true"><span class="deny">Deny</span><span>Always</span><span class="allow">Allow</span></div>
    <figcaption class="fine">Left unanswered for 10 minutes, a Claude Code request is denied, and Claude is told you're away, so it stops and waits for you.</figcaption>
  </div>
</figure>`;
}

function meters() {
  return `<div class="card">
  ${seam('CLAUDE USAGE', { state: 'live', trail: 'Claude Code' })}
  <div class="meters">
    <div class="meter"><span class="label">5 hours</span><span class="val">43%</span><span class="reset">${icon.reset()}2h 11m</span><span class="bar"><i class="pace w60"></i><i class="w43"></i></span></div>
    <div class="meter"><span class="label">7 days</span><span class="val">55%</span><span class="reset">${icon.reset()}Tue 20:00</span><span class="bar"><i class="w55"></i></span></div>
  </div>
  <div class="gap"></div>
  ${seam('CODEX USAGE', { state: 'live', trail: 'Codex' })}
  <div class="meters">
    <div class="meter"><span class="label">5 hours</span><span class="val">38%</span><span class="reset">${icon.reset()}3h 40m</span><span class="bar codex"><i class="w38"></i></span></div>
    <div class="meter"><span class="label">7 days</span><span class="val">21%</span><span class="reset">${icon.reset()}Fri 09:00</span><span class="bar codex"><i class="w21"></i></span></div>
  </div>
</div>`;
}

function switchSheet() {
  return `<div class="card switch-sheet" aria-label="The switch sheet, as the app shows it" role="group">
  <span class="label eyebrow">Switch to Codex</span>
  <h3>Switch to Codex</h3>
  <p>Codex goes on with this conversation in a new session in the same folder, with what you carry over as background. It does nothing until your next message.</p>
  <span class="label">Carry over</span>
  <div class="choices" aria-hidden="true"><span class="on">Recent</span><span>Entire</span><span>None</span></div>
  <div class="sheet-acts" aria-hidden="true"><span class="pill ghost small">Cancel</span><span class="pill primary small">Switch</span></div>
</div>`;
}

export function home(ctx) {
  const s = ctx.site;
  const dl = (k) => `/download/${s.downloads[k].file}`;
  const film = ctx.film;
  const filmHtml = film ? `<section class="section film-section" aria-labelledby="film-h">
  <div class="wrap">
    <div class="head center reveal">${seam('THE FILM', { state: 'live', trail: film.durationText || '' })}<h2 id="film-h" class="title">Hypernova, in motion.</h2></div>
    <div class="film reveal"><video controls preload="none" playsinline poster="${film.poster}" width="${film.width}" height="${film.height}">
      ${film.webm ? `<source src="${film.webm}" type="video/webm">` : ''}<source src="${film.mp4}" type="video/mp4">
      <p>Your browser can't play this film. <a href="${film.mp4}">Download it</a> instead.</p>
    </video></div>
  </div>
</section>` : '';

  const guides = ctx.guides.map((g) => `<li><a class="card guide-card" href="/guides/${g.slug}/">${seam('GUIDE', { trail: esc(g.kicker) })}<h3>${esc(g.h1)}</h3><p>${esc(g.description)}</p><span class="go">Read the guide →</span></a></li>`).join('\n');
  const faqItems = ctx.faq.items.slice(0, 6).map((q) => `<details class="card"><summary>${esc(q.q)}</summary><div class="a">${q.html}</div></details>`).join('\n');

  const body = `
<section class="hero" aria-labelledby="hero-h">
  <div class="wrap hero-grid">
    <div>
      <div class="rise">${seam(`Hypernova ${esc(ctx.version)}`, { state: 'live', trail: 'Open source · Apache-2.0' })}</div>
      <h1 id="hero-h" class="display rise">A control room for Claude Code <span class="soft">and Codex.</span></h1>
      <p class="lede rise">Watch, approve and steer the coding agents on your Mac or Linux server from your Android phone, the Mac's menu bar and Even Realities G2 glasses. It runs on your own machine, with no Hypernova server in between.</p>
      <div class="cta rise">
        <a class="pill primary" href="/install/">${icon.down()}Install Hypernova</a>
        <a class="pill" href="${s.repo}" rel="noopener">${icon.github()}Source on GitHub</a>
      </div>
      <div class="rise">
        <div class="hero-chips" aria-label="Platforms">${chip('Android 10+')}${chip('macOS 14+')}${chip('Linux')}${chip('Even G2')}</div>
        <p class="cta-note">Free and open source. Alpha: used every day, built with Claude Code.</p>
      </div>
    </div>
    <div class="stage" data-orrery>
      ${ctx.images.orrery ? `<img class="orrery-still" src="${ctx.asset(`/img/orrery-${ctx.images.orrery.widths[0][0]}.webp`)}" width="${ctx.images.orrery.widths[0][0]}" height="${ctx.images.orrery.widths[0][1]}" alt="" loading="lazy" decoding="async">` : ''}
      <canvas aria-hidden="true"></canvas>
      <div class="sky-labels" aria-hidden="true"></div>
      <p class="sr-only">An illustration of the app's Home: the sun is an accretion disk showing 43% of the 5-hour window used, with projects as planets round it (two working, one that needs you) and Codex's smaller star at the edge.</p>
      <div class="stage-phone">${shot(ctx, 'phone-chat', HIGHLIGHTS[1][3], { sizes: '300px', eager: true })}</div>
    </div>
  </div>
</section>

<section class="section first" aria-labelledby="why-h">
  <div class="wrap">
    <div class="sessions-head reveal">
      <div class="head">
        ${seam('SESSIONS', { state: 'alert', trail: '<span class="amber">• 1 needs you</span> <span class="accent">• 2 working</span>' })}
        <h2 id="why-h" class="title">Let it run. Step in when it needs you.</h2>
        <p class="lede">Coding agents do their best work when you let them run, and they need you at the worst moment: a command to approve, a question to answer, a limit to wait out. Hypernova keeps you in the loop wherever you are: the phone is your window, the glasses your screen and your voice the keyboard.</p>
      </div>
    </div>
    <div class="reveal">${sessionCards()}</div>
  </div>
</section>

${filmHtml}

<section class="section" id="where" aria-labelledby="where-h">
  <div class="wrap">
    <div class="head reveal">${seam('WHERE IT RUNS', { state: 'live', trail: 'Mac · Linux · Docker' })}<h2 id="where-h" class="title">Your Mac, or a server that never sleeps.</h2>
      <p class="lede">Hypernova's service runs next to your agents, on your own machine. The phone and the glasses pair with any of these the same way.</p></div>
    <div class="grid three">
      <article class="card host reveal">${icon.laptop('glyph-big')}${seam('MAC', { trail: 'macOS 14+ · Apple silicon' })}
        <h3>A Mac, from the menu bar.</h3>
        <p>The menu bar app installs and runs the service, connects Claude Code and Codex, sets up Tailscale and pairs your phone with a QR code. A planet in the menu bar turns while anything works and shows a dot when something needs you.</p>
        <a class="go" href="/install/">Install on a Mac ${icon.right()}</a>
      </article>
      <article class="card host reveal">${icon.server('glyph-big')}${seam('LINUX SERVER', { trail: 'Ubuntu · Debian' })}
        <h3>A VPS or a home server.</h3>
        <p>The same service as a systemd user service on an always-on machine, run as the <code class="inline">hypernova</code> command over SSH. Tailscale is built in, and <code class="inline">hypernova pair</code> shows the pairing code right in the terminal.</p>
        <a class="go" href="/install/linux/">Install on Linux ${icon.right()}</a>
      </article>
      <article class="card host reveal">${icon.box('glyph-big')}${seam('DOCKER', { trail: 'the quickest look' })}
        <h3>A container, to try it.</h3>
        <p>Build the image from a checkout and run it with a volume for its state. Nothing is published to the network; sign in to Claude once and pair with <code class="inline">hypernova pair</code> inside it.</p>
        <a class="go" href="/install/linux/#try-it-in-docker">Try it in Docker ${icon.right()}</a>
      </article>
    </div>
  </div>
</section>


<section class="section" id="features" aria-labelledby="features-h">
  <div class="wrap">
    <div class="head reveal">${seam('ON THE PHONE', { state: 'live', trail: 'Android 10+' })}<h2 id="features-h" class="title">Everything the terminal knows, in your pocket.</h2>
      <p class="lede">The Android app is a native app, not a web page: live sessions, approvals, the real terminal, voice, files and usage, drawn as a night sky.</p></div>
    <div class="shots">
      ${HIGHLIGHTS.map(([k, t, d, alt]) => `<figure class="reveal">${shot(ctx, k, alt)}<figcaption><strong>${t}</strong>${d}</figcaption></figure>`).join('\n      ')}
    </div>
  </div>
</section>

<section class="section" aria-labelledby="small-h">
  <div class="wrap">
    <h2 id="small-h" class="sr-only">More on the phone</h2>
    <div class="shots small">
      ${SMALL.map(([k, t, d, alt]) => `<figure class="reveal">${shot(ctx, k, alt, { sizes: '(min-width: 900px) 260px, 45vw' })}<figcaption><strong>${t}</strong> ${d}</figcaption></figure>`).join('\n      ')}
    </div>
  </div>
</section>

<section class="section" id="approve" aria-labelledby="approve-h">
  <div class="wrap row">
    <div class="reveal">
      ${seam('NEEDS YOU', { state: 'alert', cls: 'amber', trail: '1 waiting' })}
      <h2 id="approve-h" class="title">Approve Claude Code and Codex from your phone.</h2>
      <p class="lede">A command to run, a file to edit, a question, a plan to approve: each request reaches you with what it wants to do, on the phone, in its notification, in the menu bar and on the glasses. Allow or Deny it (or Always, for Claude Code). Risky commands get a second look, and Bypass mode asks you to think twice first.</p>
      <p><a class="pill" href="/guides/approve-claude-code-permissions-from-android/">How approvals work ${icon.right()}</a></p>
    </div>
    <div class="reveal">${approval()}</div>
  </div>
</section>

<section class="section" id="agents" aria-labelledby="agents-h">
  <div class="wrap">
    <div class="head reveal">${seam('TWO AGENTS', { state: 'live', trail: 'Claude Code · Codex' })}<h2 id="agents-h" class="title">Claude Code and Codex, as equals.</h2>
      <p class="lede">Use either one or both. Each session runs on its own agent with its own usage. The agent that leads is the sun; the other is a second, smaller star with its own sessions round it. Switch a conversation to the other agent mid-chat: it carries on in a new session in the same folder, with what you chose to carry over, linked to the old one.</p></div>
    <div class="agents reveal">${meters()}${switchSheet()}</div>
    <p class="more reveal"><a href="/guides/claude-code-and-codex-together/">Claude Code and Codex together</a> · <a href="/guides/codex-on-your-phone/">Codex on your phone</a> · <a href="/guides/claude-code-usage-limits/">Usage limits</a></p>
  </div>
</section>


<section class="section" id="mac" aria-labelledby="mac-h">
  <div class="wrap row flip">
    <div class="reveal">
      ${seam('IN THE MENU BAR', { state: 'live', trail: 'macOS 14+' })}
      <h2 id="mac-h" class="title">The phone's design, on the Mac.</h2>
      <p class="lede">A planet in the menu bar spins while anything works and shows a dot when something needs you. The popover has approvals, sessions in the phone's words, drawn usage meters that show where your pace is heading, Stay awake and automations.</p>
      <p class="muted">The Mac app also installs and runs the service, sets up the <code class="inline">claude</code> shell integration and Tailscale, pairs phones with a QR code and lists the ones you trust, so you can forget one at any time.</p>
    </div>
    <div class="phones reveal">${shot(ctx, 'mac-popover', 'The Mac popover: an approval with Deny, Always and Allow, sessions with their model and mode, projection meters for the 5-hour and weekly usage, Stay awake', { sizes: '(min-width: 900px) 270px, 45vw' })}${shot(ctx, 'mac-automation', 'The Mac popover while an automation runs: AUTO on its session, the run line under Sessions', { sizes: '(min-width: 900px) 270px, 45vw' })}</div>
  </div>
</section>

<section class="section" id="glasses" aria-labelledby="glasses-h">
  <div class="wrap">
    <div class="head reveal">${seam('ON YOUR GLASSES', { state: 'live', trail: 'Even Realities G2' })}<h2 id="glasses-h" class="title">Glance, approve, listen.</h2>
      <p class="lede">Hypernova Lens runs on Even Realities G2 glasses. Glance at your sessions, hold the temple to talk, approve by tap or by voice (“approve push”), hear results read aloud and look through Telescope, in the glasses' own text and the phone's words. It connects through the Hypernova app on the same phone.</p></div>
    <div class="lenses">
      <figure class="reveal">${shot(ctx, 'lens-glance', 'The glance on the G2 in the glasses\' own font: hypernova/ and the time, 42% resets in 2h 11m in the corner, one line per session with its project, what it does and its age', { sizes: '(min-width: 640px) 50vw, 100vw' })}<figcaption>The glance: every session, one line each.</figcaption></figure>
      <figure class="reveal">${shot(ctx, 'lens-needs-you', 'Needs you on the G2: NEEDS YOU 0stems, auto-deny in 8m, wants to run git push origin main, tap allow, double tap deny', { sizes: '(min-width: 640px) 50vw, 100vw' })}<figcaption>Needs you: tap to allow, double tap to deny.</figcaption></figure>
      <figure class="reveal">${shot(ctx, 'lens-result', 'A result card on the G2: the reply scrolls, with what changed and the tests', { sizes: '(min-width: 640px) 50vw, 100vw' })}<figcaption>A result, with what changed and the tests.</figcaption></figure>
      <figure class="reveal">${shot(ctx, 'lens-telescope', 'Telescope on the G2: the page the agent built, dithered across the lens', { sizes: '(min-width: 640px) 50vw, 100vw' })}<figcaption>Telescope: what the agent built, on the lens.</figcaption></figure>
    </div>
    <p class="more reveal"><a href="/guides/claude-code-on-even-g2-glasses/">Claude Code on Even G2 glasses</a></p>
  </div>
</section>

<section class="section" aria-labelledby="also-h">
  <div class="wrap">
    <div class="head reveal">${seam('ALSO ON THE PHONE', { trail: `${ALSO.length} more` })}<h2 id="also-h" class="title">The small things, done properly.</h2></div>
    <div class="grid three">
      ${ALSO.map(([st, t, d]) => `<article class="card feat reveal">${seam(esc(t.toUpperCase()), { state: st })}<p>${d}</p></article>`).join('\n      ')}
    </div>
  </div>
</section>

<section class="section" id="how" aria-labelledby="how-h">
  <div class="wrap">
    <div class="head reveal">${seam('HOW IT WORKS', { state: 'live', trail: 'no server in between' })}<h2 id="how-h" class="title">Your phone talks to your own machine.</h2>
      <p class="lede">A small service runs next to your agents, on your Mac or your Linux server. The phone reaches it directly on your Wi‑Fi, or over your own Tailscale network from anywhere. No Hypernova server carries your sessions.</p></div>
    <div class="diagram reveal">
      <div class="card node">
        ${seam('YOUR PHONE', { state: 'live', trail: 'Android 10+' })}
        <h3>The Android app</h3>
        <p>Kotlin and Jetpack Compose. Lens on the G2 talks only to this app on the same phone, which carries its calls.</p>
        <ul><li>Pairs once with a QR code or a link</li><li>Keeps a per-device token and certificate</li></ul>
      </div>
      <div class="link" aria-hidden="true">
        <span class="label">HTTPS + WebSocket</span>
        <span class="wire"></span>
        <small>your Wi‑Fi (mutual TLS) or your own tailnet</small>
      </div>
      <div class="card node">
        ${seam('YOUR HOST', { state: 'live', trail: 'Mac or Linux' })}
        <h3>The service, next to your agents</h3>
        <p>It watches every Claude Code and Codex session through hooks, transcripts and Codex's own app server, and runs the phone's sessions in shared terminals you can also open on the host.</p>
        <div class="host-list">
          <div class="mini"><b>A Mac</b><span>the menu bar app runs it and sets it up</span></div>
          <div class="mini"><b>A Linux server</b><span>the <code class="inline">hypernova</code> command, a systemd user service</span></div>
        </div>
      </div>
    </div>
    <p class="more reveal">The full contract between the pieces is in <a href="${s.repo}/blob/main/docs/PROTOCOL.md" rel="noopener">PROTOCOL.md</a>.</p>
  </div>
</section>

<section class="section" id="privacy" aria-labelledby="privacy-h">
  <div class="wrap">
    <div class="head reveal">${seam('PRIVACY', { state: 'live', trail: 'yours end to end' })}<h2 id="privacy-h" class="title">Self-hosted, and open source.</h2>
      <p class="lede">There is no Hypernova account and no Hypernova server that carries sessions. The only thing hosted is a small name service for pairing and a private name, and it never sees a session, a prompt or a file.</p></div>
    <div class="grid two">
      <article class="card promise reveal">${seam('YOURS END TO END', { state: 'live' })}<h3>Reached only by devices you paired.</h3><p>The phone API listens on loopback and is reached through your tailnet or your Wi‑Fi's mutual-TLS listener, with a per-device token and certificate from pairing. Nothing is opened to the internet.</p></article>
      <article class="card promise reveal">${seam('KEYS STAY HOME', { state: 'live' })}<h3>Provider keys never leave the host.</h3><p>Provider and Soniox keys are never logged, never sent to the phone, and only ever go to their own provider. Hypernova never reads your Claude login.</p></article>
      <article class="card promise reveal">${seam('VOICE IS OPT-IN', { state: 'rest' })}<h3>Audio goes to Soniox only when you say so.</h3><p>Only when you turn voice on with your own key. The Mac's own voices are the fallback for reading aloud.</p></article>
      <article class="card promise reveal">${seam('GUARD RAILS', { state: 'alert', cls: 'amber' })}<h3>Nothing risky by accident.</h3><p>Risky commands need their own verb by voice. Automations never run on usage credits or another paid provider unless you agree, and never close or copy your own sessions.</p></article>
    </div>
    <p class="more reveal">Apache License 2.0, all of it, Lens included. What runs and is stored where, and what Tailscale, Cloudflare and Let's Encrypt see: <a href="${s.repo}/blob/main/docs/SECURITY-MODEL.md" rel="noopener">the threat model</a>.</p>
  </div>
</section>

<section class="section" id="requirements" aria-labelledby="req-h">
  <div class="wrap need">
    <div class="card reveal">
      ${seam('WHAT YOU NEED', { state: 'live' })}
      <h2 id="req-h" class="title">Requirements</h2>
      <ul>
        <li><strong>A host:</strong> a Mac with Apple silicon on macOS 14 or later, or a Linux machine on Ubuntu or Debian (x86_64 or arm64), including a VPS or a Docker container.</li>
        <li><strong>An agent:</strong> Claude Code, OpenAI's Codex (0.163.0 or later), or both.</li>
        <li><strong>An Android phone</strong> on Android 10 or later.</li>
        <li><strong>Optional:</strong> a Tailscale account to reach the host away from home (on the same Wi‑Fi you don't need one), Even Realities G2 glasses, and a Soniox API key for voice.</li>
      </ul>
    </div>
    <div class="card reveal">
      ${seam('INSTALL', { state: 'live', trail: `v${esc(ctx.version)}` })}
      <p class="heading">Download it here or from GitHub, then follow Setup.</p>
      <p class="muted">The Mac app, the Android app and the Linux build are on this site and on GitHub Releases, with their checksums.</p>
      <div class="cta">
        <a class="pill primary" href="/install/">${icon.down()}Install</a>
        <a class="pill" href="/install/linux/">${icon.server()}On Linux</a>
      </div>
    </div>
  </div>
</section>

<section class="section" id="guides" aria-labelledby="guides-h">
  <div class="wrap">
    <div class="head reveal">${seam('GUIDES', { trail: `${ctx.guides.length} guides` })}<h2 id="guides-h" class="title">Guides</h2>
      <p class="lede">One question each, answered from how the app really works.</p></div>
    <ul class="grid three plain">
      ${guides}
    </ul>
  </div>
</section>

<section class="section" id="faq" aria-labelledby="faq-h">
  <div class="wrap">
    <div class="head reveal">${seam('FAQ', { trail: `${ctx.faq.items.length} answers` })}<h2 id="faq-h" class="title">Questions, answered.</h2></div>
    <div class="faq reveal">${faqItems}</div>
    <p class="more reveal"><a href="/faq/">All the questions</a></p>
  </div>
</section>
`;

  const app = softwareApp(ctx);
  const jsonld = [
    { '@context': 'https://schema.org', '@type': 'WebSite', '@id': `${s.url}/#site`, name: 'Hypernova', url: s.url + '/', description: ctx.homeDescription, inLanguage: 'en', publisher: { '@id': `${s.url}/#author` } },
    app,
    {
      '@context': 'https://schema.org', '@type': 'SoftwareSourceCode', name: 'Hypernova', codeRepository: s.repo,
      programmingLanguage: ['Kotlin', 'Swift', 'TypeScript'], license: 'https://www.apache.org/licenses/LICENSE-2.0', author: { '@id': `${s.url}/#author` },
    },
    { '@context': 'https://schema.org', ...person(ctx) },
  ];
  if (film) {
    jsonld.push({
      '@context': 'https://schema.org', '@type': 'VideoObject', name: film.title, description: film.description,
      thumbnailUrl: s.url + film.poster, contentUrl: s.url + film.mp4, uploadDate: film.uploadDate,
      ...(film.duration ? { duration: film.duration } : {}),
    });
  }
  return page(ctx, {
    path: '/', current: 'home',
    title: 'Hypernova: control Claude Code and Codex from your phone',
    ogTitle: 'Hypernova: a control room for Claude Code and Codex',
    description: ctx.homeDescription,
    jsonld, body,
  });
}

export function softwareApp(ctx) {
  const s = ctx.site;
  const d = s.downloads;
  return {
    '@context': 'https://schema.org', '@type': 'SoftwareApplication', '@id': `${s.url}/#app`,
    name: 'Hypernova',
    description: 'A control room for Claude Code and Codex: an Android app, a Mac menu bar app and a Linux service that let you watch, approve and steer coding agents on your own machine.',
    url: s.url + '/',
    applicationCategory: 'DeveloperApplication',
    operatingSystem: [d.mac.os, d.linux.os, d.android.os].join('; '),
    softwareVersion: ctx.version,
    datePublished: ctx.date,
    license: 'https://www.apache.org/licenses/LICENSE-2.0',
    isAccessibleForFree: true,
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
    downloadUrl: ['mac', 'linux', 'android'].map((k) => `${s.url}/download/${d[k].file}`),
    installUrl: `${s.url}/install/`,
    screenshot: `${s.url}${ctx.asset('/img/phone-home-630.webp')}`,
    author: { '@id': `${s.url}/#author` },
    sameAs: [s.repo],
    hasPart: [
      { '@type': 'SoftwareApplication', name: 'Hypernova for Mac', operatingSystem: d.mac.os, applicationCategory: 'DeveloperApplication', downloadUrl: `${s.url}/download/${d.mac.file}`, softwareVersion: ctx.version },
      { '@type': 'SoftwareApplication', name: 'Hypernova for Linux', operatingSystem: d.linux.os, applicationCategory: 'DeveloperApplication', downloadUrl: `${s.url}/download/${d.linux.file}`, softwareVersion: ctx.version },
      { '@type': 'MobileApplication', name: 'Hypernova for Android', operatingSystem: d.android.os, applicationCategory: 'DeveloperApplication', downloadUrl: `${s.url}/download/${d.android.file}`, softwareVersion: ctx.version },
    ],
  };
}

// ── Install ────────────────────────────────────────────────────────────────────────────────────

/** One download: its system's glyph, what it is and for whom, the file and its size, an arrow. */
function dlCard(ctx, k, glyph) {
  const d = ctx.site.downloads[k];
  return `<a class="dl ${k}" href="/download/${d.file}" data-file="${d.file}" download>
          <span class="os">${glyph}</span>
          <span class="txt"><span class="dl-top"><span class="what">${esc(d.label)}</span>${chip('This device', 'here-tag')}</span><span class="for">${esc(d.detail)}</span></span>
          <span class="meta">${d.file}<br><span data-size>${esc(ctx.sizes[d.file] || 'latest release')}</span></span>
          ${icon.down('arrow')}
        </a>`;
}

function downloads(ctx) {
  const s = ctx.site, d = s.downloads;
  const card = (k, glyph) => dlCard(ctx, k, glyph);
  const sum = (file) => `<div class="sum" data-file="${file}"><span class="name">${file}</span><code data-sha>${esc(ctx.sums[file] || 'SHA-256 in SHA256SUMS.txt')}</code><button type="button" class="copy" hidden${ctx.sums[file] ? ` data-copy="${ctx.sums[file]}"` : ''}>Copy</button></div>`;
  return `<div class="ways" data-downloads>
    <section class="card way direct" aria-labelledby="direct-h">
      ${seam('DIRECT DOWNLOAD', { state: 'live', trail: 'from this site' })}
      <h2 id="direct-h">Download</h2>
      <p>Hypernova <span data-version>${esc(ctx.version)}</span>, released <time data-date datetime="${esc(ctx.date)}">${esc(ctx.dateText)}</time>. The same files as on GitHub, served from this site.</p>
      <div class="dl-list">
        ${card('mac', icon.laptop())}
        ${card('linux', icon.server())}
        ${card('android', icon.phone())}
        ${card('g2', icon.glasses())}
      </div>
      <div class="sums" aria-label="SHA-256 checksums">
        ${['Hypernova.dmg', 'Hypernova-linux.tar.gz', 'Hypernova.apk', 'Hypernova-Lens.ehpk'].map(sum).join('\n        ')}
      </div>
      <div class="release-line"><a href="/download/SHA256SUMS.txt" data-file="SHA256SUMS.txt">SHA256SUMS.txt</a><span>Check with <code class="inline">shasum -a 256 -c SHA256SUMS.txt</code></span></div>
    </section>
    <div class="ways-side">
    <section class="card way gh" aria-labelledby="gh-h">
      ${seam('FROM GITHUB', { trail: 'releases · source' })}
      <h2 id="gh-h">From GitHub</h2>
      <p>Every release, with its notes and checksums, or the source to build yourself.</p>
      <div class="stack">
        <a class="pill" href="${s.releases}" rel="noopener">${icon.github()}<span class="txt">The latest release<small>Hypernova.dmg, Hypernova.apk, Hypernova-linux.tar.gz, Hypernova-Lens.ehpk</small></span></a>
        <a class="pill" href="#build-it-yourself">${icon.code()}<span class="txt">Build it from source<small>Command Line Tools, Node.js, Go; Android Studio's JDK</small></span></a>
        <a class="pill" href="/install/linux/">${icon.server()}<span class="txt">On a Linux server<small>Ubuntu, Debian · or Docker</small></span></a>
      </div>
    </section>
    <aside class="card qr-card" aria-label="Open this page on your phone">${ctx.qr}<div><b>Get it on your phone</b><p>Scan to open this page on your Android phone and download the app there. A Mac also hands the app to your phone during Setup.</p></div></aside>
    </div>
  </div>`;
}

function tocList(heads, cls) {
  return `<ol class="${cls}">${heads.map((h) => `<li><a href="#${h.id}">${esc(h.text)}</a></li>`).join('')}</ol>`;
}

export function install(ctx, doc) {
  const c = crumbs(ctx, [['Home', '/'], ['Install', '/install/']]);
  const h2 = doc.headings.filter((h) => h.level === 2);
  const body = `
<div class="wrap">
  <header class="page-head">
    ${c.html}
    <h1 class="display">Install Hypernova.</h1>
    <p class="lede">${doc.intro}</p>
  </header>
  ${downloads(ctx)}
</div>
<div class="wrap doc" id="guide">
  <nav class="toc" aria-label="On this page">${seam('ON THIS PAGE')}${tocList(h2, '')}</nav>
  <div>
    <details class="card toc-inline"><summary><span class="label">On this page</span><span class="dim">${h2.length} sections</span></summary>${tocList(h2, '')}</details>
    <article class="md">${doc.html}</article>
    <p class="updated">This guide is <a href="${ctx.site.repo}/blob/main/docs/INSTALL.md" rel="noopener">docs/INSTALL.md</a> in the repository, the same words Setup uses.</p>
  </div>
</div>`;
  return page(ctx, {
    path: '/install/', current: 'install',
    title: 'Install Hypernova on Mac, Linux and Android | Hypernova',
    description: `Download Hypernova ${ctx.version} for Mac (Apple silicon, macOS 14+), Linux (Ubuntu, Debian) and Android 10+, directly or from GitHub, then set it up step by step.`,
    jsonld: [c.ld, { '@context': 'https://schema.org', '@type': 'WebPage', name: 'Install Hypernova', url: ctx.site.url + '/install/', about: { '@id': `${ctx.site.url}/#app` } }],
    body,
  });
}

export function linux(ctx, doc) {
  const c = crumbs(ctx, [['Home', '/'], ['Install', '/install/'], ['Linux', '/install/linux/']]);
  const h2 = doc.headings.filter((h) => h.level === 2);
  const d = ctx.site.downloads.linux;
  const body = `
<div class="wrap">
  <header class="page-head">
    ${c.html}
    <h1 class="display">Hypernova on Linux.</h1>
    <p class="lede">${doc.intro}</p>
  </header>
  <div class="ways" data-downloads>
    <section class="card way direct" aria-labelledby="direct-h">
      ${seam('DIRECT DOWNLOAD', { state: 'live', trail: 'from this site' })}
      <h2 id="direct-h">Download</h2>
      <p>Hypernova <span data-version>${esc(ctx.version)}</span> for Linux: the service and its install script in one small tarball. Run it as the user who will use Claude Code, not as root.</p>
      <div class="dl-list one">
        ${dlCard(ctx, 'linux', icon.server())}
      </div>
      <div class="code"><div class="code-head"><span class="code-lang">SH</span><button type="button" class="copy" hidden>Copy</button></div><pre><code class="language-sh">tar -xzf ${d.file} &amp;&amp; hypernova-*/scripts/install-linux.sh</code></pre></div>
      <div class="sums"><div class="sum" data-file="${d.file}"><span class="name">${d.file}</span><code data-sha>${esc(ctx.sums[d.file] || 'SHA-256 in SHA256SUMS.txt')}</code><button type="button" class="copy" hidden${ctx.sums[d.file] ? ` data-copy="${ctx.sums[d.file]}"` : ''}>Copy</button></div></div>
    </section>
    <section class="card way gh" aria-labelledby="gh-h">
      ${seam('FROM GITHUB', { trail: 'releases · source' })}
      <h2 id="gh-h">From GitHub</h2>
      <p>The release, or a checkout of the repository: its install script and Dockerfile are at the root.</p>
      <div class="stack">
        <a class="pill" href="${ctx.site.releases}" rel="noopener">${icon.github()}<span class="txt">The latest release<small>${d.file} and SHA256SUMS.txt</small></span></a>
        <a class="pill" href="#try-it-in-docker">${icon.box()}<span class="txt">Try it in Docker<small>build the image from a checkout</small></span></a>
        <a class="pill" href="/install/">${icon.laptop()}<span class="txt">On a Mac instead<small>the menu bar app</small></span></a>
      </div>
    </section>
  </div>
</div>
<div class="wrap doc" id="guide">
  <nav class="toc" aria-label="On this page">${seam('ON THIS PAGE')}${tocList(h2, '')}</nav>
  <div>
    <details class="card toc-inline"><summary><span class="label">On this page</span><span class="dim">${h2.length} sections</span></summary>${tocList(h2, '')}</details>
    <article class="md">${doc.html}</article>
    <p class="updated">This guide is <a href="${ctx.site.repo}/blob/main/docs/LINUX.md" rel="noopener">docs/LINUX.md</a> in the repository. Background: <a href="/guides/claude-code-on-a-vps/">why an always-on host</a> and <a href="/guides/run-claude-code-on-a-linux-server/">the server setup, step by step</a>.</p>
  </div>
</div>`;
  return page(ctx, {
    path: '/install/linux/', current: 'linux',
    title: 'Install Hypernova on Linux: VPS, home server, Docker | Hypernova',
    description: 'Run Hypernova on Ubuntu or Debian (x86_64, arm64) or in Docker: the hypernova command, a systemd user service, built-in Tailscale, and pairing your phone.',
    jsonld: [c.ld, { '@context': 'https://schema.org', '@type': 'WebPage', name: 'Hypernova on Linux', url: ctx.site.url + '/install/linux/', about: { '@id': `${ctx.site.url}/#app` } }],
    body,
  });
}

// ── Guides ─────────────────────────────────────────────────────────────────────────────────────

export function guidesIndex(ctx) {
  const c = crumbs(ctx, [['Home', '/'], ['Guides', '/guides/']]);
  const body = `
<div class="wrap">
  <header class="page-head">
    ${c.html}
    <h1 class="display">Guides.</h1>
    <p class="lede">How Hypernova does one thing each: Claude Code and Codex on your phone, approvals, usage limits, remote access, a server of your own and the glasses.</p>
  </header>
  <ul class="grid three plain guides-list">
    ${ctx.guides.map((g) => `<li><a class="card guide-card" href="/guides/${g.slug}/">${seam('GUIDE', { trail: esc(g.kicker) })}<h2 class="heading">${esc(g.h1)}</h2><p>${esc(g.description)}</p><span class="go">Read the guide →</span></a></li>`).join('\n    ')}
  </ul>
  <p class="more">Something else? <a href="/faq/">The FAQ</a>, or <a href="/install/">the install guide</a>.</p>
</div>`;
  return page(ctx, {
    path: '/guides/', current: 'guides',
    title: 'Hypernova guides: Claude Code and Codex from your phone',
    description: 'Guides to Hypernova: Claude Code and Codex on your Android phone, approving permissions, usage limits, Tailscale remote access, Linux servers and Even G2 glasses.',
    jsonld: [c.ld, {
      '@context': 'https://schema.org', '@type': 'CollectionPage', name: 'Hypernova guides', url: ctx.site.url + '/guides/',
      hasPart: ctx.guides.map((g) => ({ '@type': 'TechArticle', headline: g.h1, url: `${ctx.site.url}/guides/${g.slug}/` })),
    }],
    body,
  });
}

export function guide(ctx, g) {
  const c = crumbs(ctx, [['Home', '/'], ['Guides', '/guides/'], [g.nav, `/guides/${g.slug}/`]]);
  const related = g.related.map((slug) => ctx.guides.find((x) => x.slug === slug)).filter(Boolean);
  const url = `${ctx.site.url}/guides/${g.slug}/`;
  const body = `
<div class="wrap">
  <header class="page-head">
    ${c.html}
    ${seam('GUIDE', { state: 'live', trail: esc(g.kicker) })}
    <h1 class="title-xl">${esc(g.h1)}</h1>
    <p class="lede">${esc(g.lede)}</p>
  </header>
  <div class="guide">
    <article class="md">${g.html}
      <p class="updated">Updated <time datetime="${g.updated}">${esc(g.updatedText)}</time> for Hypernova ${esc(ctx.version)}.</p>
      <div class="card cta-card cta-after"><p>Free and open source, for Mac, Linux and Android.</p><a class="pill primary" href="/install/">${icon.down()}Install Hypernova</a></div>
    </article>
    <aside aria-label="Screenshot and install">
      ${shot(ctx, g.shot, g.shotAlt, { sizes: '(min-width: 1040px) 300px, 240px' })}
      <div class="card cta-card">
        <p>Free and open source, for Mac, Linux and Android.</p>
        <a class="pill primary" href="/install/">${icon.down()}Install Hypernova</a>
      </div>
    </aside>
  </div>
  ${related.length ? `<section class="related" aria-labelledby="rel-h"><h2 id="rel-h">Related guides</h2><ul class="grid three plain">${related.map((r) => `<li><a class="card guide-card" href="/guides/${r.slug}/">${seam('GUIDE', { trail: esc(r.kicker) })}<h3>${esc(r.h1)}</h3><p>${esc(r.description)}</p><span class="go">Read the guide →</span></a></li>`).join('')}</ul></section>` : ''}
</div>`;
  const jsonld = [c.ld, {
    '@context': 'https://schema.org', '@type': 'TechArticle', headline: g.h1, description: g.description, url,
    mainEntityOfPage: url, datePublished: g.updated, dateModified: g.updated, inLanguage: 'en',
    author: person(ctx), publisher: person(ctx), image: `${ctx.site.url}/og.jpg`,
    about: { '@id': `${ctx.site.url}/#app` },
  }];
  if (g.faq.length) jsonld.push(faqLd(g.faq));
  return page(ctx, { path: `/guides/${g.slug}/`, current: 'guides', title: g.title, description: g.description, ogType: 'article', jsonld, body });
}

function faqLd(items) {
  return {
    '@context': 'https://schema.org', '@type': 'FAQPage',
    mainEntity: items.map((q) => ({ '@type': 'Question', name: q.q, acceptedAnswer: { '@type': 'Answer', text: q.text } })),
  };
}

export function faq(ctx) {
  const c = crumbs(ctx, [['Home', '/'], ['FAQ', '/faq/']]);
  const f = ctx.faq;
  const body = `
<div class="wrap">
  <header class="page-head">
    ${c.html}
    <h1 class="display">${esc(f.h1)}</h1>
    <p class="lede">${esc(f.lede)}</p>
  </header>
  <div class="faq-page">
    ${f.groups.map((g) => `<section class="faq-group" aria-labelledby="${g.id}"><h2 id="${g.id}">${esc(g.title)}</h2><div class="faq">${g.items.map((q) => `<details class="card" id="${q.id}"><summary>${esc(q.q)}</summary><div class="a">${q.html}</div></details>`).join('\n')}</div></section>`).join('\n    ')}
  </div>
  <p class="more">Not here? Ask in <a href="${ctx.site.issues}" rel="noopener">GitHub issues</a>, or read <a href="/guides/">the guides</a>.</p>
</div>`;
  return page(ctx, {
    path: '/faq/', current: 'faq', title: f.title, description: f.description,
    jsonld: [c.ld, faqLd(f.items)], body,
  });
}

export function notFound(ctx) {
  const body = `
<div class="wrap lost">
  <div>
    <svg class="eclipse" viewBox="0 0 120 120" aria-hidden="true"><defs><radialGradient id="ec" cx="60" cy="60" r="60" gradientUnits="userSpaceOnUse"><stop offset=".42" stop-color="#fff6e0" stop-opacity=".75"/><stop offset=".5" stop-color="#ffd88a" stop-opacity=".32"/><stop offset=".7" stop-color="#ff9a48" stop-opacity=".1"/><stop offset="1" stop-color="#ff9a48" stop-opacity="0"/></radialGradient><radialGradient id="ed" cx="78" cy="44" r="6" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#fff" stop-opacity=".95"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs><circle cx="60" cy="60" r="60" fill="url(#ec)"/><circle cx="60" cy="60" r="26" fill="#14110e"/><circle cx="60" cy="60" r="26.4" fill="none" stroke="#ffe8c0" stroke-opacity=".85" stroke-width=".8"/><circle cx="78.5" cy="41.5" r="6" fill="url(#ed)"/></svg>
    <p class="code404">404 · NOT IN THIS SKY</p>
    <h1 class="title">This page is in eclipse.</h1>
    <p class="lede">Nothing here, or not any more. The sky is still where you left it.</p>
    <div class="cta"><a class="pill primary" href="/">Home</a><a class="pill" href="/install/">Install</a><a class="pill ghost" href="/guides/">Guides</a></div>
  </div>
</div>`;
  return page(ctx, { path: '/404.html', title: 'Not found | Hypernova', description: 'This page is not here. Hypernova: a control room for Claude Code and Codex.', noindex: true, body, scripts: ['site', 'sky'] });
}

export { TAGLINE, mark };
