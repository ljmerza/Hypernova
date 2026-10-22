// The phone side, inside the Even App: pairing, the connected Mac, settings, and the full-size
// Telescope picture, over the living sky (the sun is your usage, planets your projects, moons your
// sessions). A live mirror of the lens with the gestures as buttons works without glasses too.
import './hyper-cosmos.css';
import { FRAME_SANDBOX } from './status.ts';
import './styles.css';
import './cosmos.js';
import type { CosmosSky } from './cosmos.js';
import { h, replace, seam, toast } from './dom.ts';
import { drawLens } from './mirror.ts';
import { decodeQr } from './qr.ts';
import { parsePairLink, type PairLink } from '../core/pairing.ts';
import { activeSessions, isBusy } from '../core/glance.ts';
import { leading } from '../core/types.ts';
import type { GuideState, GuideStep, Shot } from '../core/types.ts';
import { H, W } from '../lens/pages.ts';
import type { LensController } from '../lens/controller.ts';
import type { LensOutput } from '../lens/output.ts';
import type { LensSender } from '../lens/sender.ts';
import type { HubLike, MacVoice } from '../data/hub.ts';
import type { Store, VoiceChoice } from '../data/store.ts';
import { VoiceOut } from '../data/voice.ts';
import { explain, type Api } from '../data/api.ts';
import { moveGuide, showGuideOnMac, type SpeechSource } from '../data/stt.ts';
import { speechCard } from './speech-card.ts';
import { glassesCards } from './glasses-card.ts';
import { folderName } from '../core/nebula.ts';
import { guideCard, guideSteps } from './guide.ts';

export interface PhoneDeps {
  store: Store;
  lens: LensController;
  output: LensOutput;
  sender: LensSender;
  voice: VoiceOut;
  hub(): HubLike | null;
  demo(): boolean;
  pairWith(link: PairLink): Promise<void>;
  unpair(): Promise<void>;
  startDemo(): void;
  capture: (() => Promise<{ base64: string; mimeType?: string } | null>) | null;
  /** What a hold uses now: Soniox through the Mac, sample data, or nothing yet. */
  speechKind(): SpeechSource;
  /** The Mac's API (null with sample data): the Soniox key and the Lens guide go through it. */
  macApi(): Api | null;
}

export class PhoneApp {
  private d: PhoneDeps;
  private root: HTMLElement;
  private sky: CosmosSky | null = null;
  private status = h('span', { class: 'hn-chip' }, '…');
  private mirror = h('canvas', { width: W, height: H, 'aria-label': 'What the glasses show' });
  private mirrorCaption = h('span');
  private mirrorRate = h('span');
  private showSlot = h('div');
  private typeSlot = h('div');
  private settingsSlot = h('div');
  private guideSlot = h('div');
  private guideKey = '';
  private sttKey = '';
  /** The guide with sample data (no Mac to keep its place), and a step taken here until the Mac echoes it. */
  private localGuide: GuideState = { step: 'about', seen: true, done: false };
  private guideOverride: GuideState | null = null;
  /** This showing's steps, fixed when the guide opens (voice ready then: no Voice step); null while it's closed. */
  private shownSteps: GuideStep[] | null = null;
  private seenSent = false;
  private dirty = true;
  private mirrorTimer: ReturnType<typeof setTimeout> | null = null;
  private shot: Shot | null = null;
  private live: string | null = null;
  private macVoices: MacVoice[] = [];
  /** The hub the Mac's voices were asked of (a new pairing asks again). */
  private voicesOf: HubLike | null = null;
  private mode: 'paired' | 'unpaired' | null = null;
  private projectsKey = '';

  constructor(root: HTMLElement, d: PhoneDeps) {
    this.d = d;
    this.root = root;
    const sky = h('canvas', { class: 'hn-sky', 'aria-hidden': 'true' });
    const grain = h('canvas', { class: 'hn-grain', 'aria-hidden': 'true' });
    document.body.prepend(sky);
    document.body.append(grain);
    this.atmosphere = [sky, grain];
    try {
      this.sky = window.HyperCosmos?.mount({ canvas: sky, grain, band: () => ({ top: 70, bottom: Math.max(260, innerHeight * 0.36) }) }) ?? null;
    } catch (err) { console.warn('[phone] sky', err); }
    // Cards scrolling over the sky fade it (its labels must never read through a card): smoothstep
    // over 4–42% of the sky window, down to faint stars.
    const fade = () => {
      const k = Math.min(1, Math.max(0, (scrollY / Math.max(1, innerHeight * 0.36) - 0.04) / 0.38));
      sky.style.opacity = String(1 - 0.82 * k * k * (3 - 2 * k));
    };
    addEventListener('scroll', fade, { passive: true });
    this.unfade = () => removeEventListener('scroll', fade);
    fade();
    this.render();
  }

  private atmosphere: HTMLElement[] = [];
  private unfade: (() => void) | null = null;

  /** Lens switched to the status card (the Hypernova app answered): the sky stops and goes. */
  dispose(): void {
    try { this.sky?.destroy(); } catch { /* gone */ }
    this.sky = null;
    for (const el of this.atmosphere) el.remove();
    this.unfade?.();
    if (this.mirrorTimer) clearTimeout(this.mirrorTimer);
    document.querySelector('.viewer')?.remove();
    document.body.classList.remove('unpaired');
  }

  /** Redraw the mirror soon, at most ~12 times a second. Not requestAnimationFrame: a WebView that
   * isn't on screen (the simulator's, a phone in a pocket) never runs it, and the mirror should be
   * right the moment it's looked at. */
  private kick(): void {
    this.dirty = true;
    if (this.mirrorTimer) return;
    this.mirrorTimer = setTimeout(() => { this.mirrorTimer = null; if (this.dirty) { this.dirty = false; this.drawMirror(); } }, 80);
  }

  /** The hub or the lens changed. */
  update(): void {
    const paired = !!this.d.store.pairing || this.d.demo();
    if ((paired ? 'paired' : 'unpaired') !== this.mode) { this.render(); return; }
    this.refreshStatus();
    this.refreshSky();
    // Rebuilt only when projects or sessions come and go, so typing in a field is never interrupted.
    const snap = this.d.hub()?.snapshot;
    const key = JSON.stringify([(snap?.projects ?? []).map(p => p.name), (snap?.sessions ?? []).filter(s => s.state !== 'ended').map(s => s.id)]);
    if (key !== this.projectsKey) { this.projectsKey = key; this.renderSettings(); this.renderType(); }
    // The Lens guide's place and the Mac's voice: each redraws only its own part.
    const g = JSON.stringify(this.guide());
    if (g !== this.guideKey) { this.guideKey = g; this.renderGuide(); this.renderGestures(); }
    const st = JSON.stringify([snap?.stt ?? null, this.d.speechKind()]);
    if (st !== this.sttKey) { this.sttKey = st; this.renderSettings(); this.renderGuide(); }
    this.kick();
  }

  lensChanged(): void { this.kick(); }

  settingsChanged(): void { this.renderSettings(); }

  /** Telescope's last picture (or only a live page) for its card. */
  setShot(shot: Shot | null, live: string | null): void {
    if (shot) this.shot = shot;
    this.live = live ?? (shot ? null : this.live);
    this.renderShow();
  }

  /** The glasses asked: open it full size. */
  showShot(shot: Shot | null, live: string | null): void {
    if (shot || live) this.setShot(shot, live);
    this.openViewer(!this.shot && !!this.live);
  }

  // ------------------------------------------------------------------ layout

  private render(): void {
    const paired = !!this.d.store.pairing || this.d.demo();
    this.mode = paired ? 'paired' : 'unpaired';
    document.body.classList.toggle('unpaired', !paired);
    const top = h('header', { class: 'hn-topbar topbar' },
      h('span', { class: 'hn-wordmark' }, 'hypernova'), h('span', { class: 'sub' }, 'lens'), h('span', { class: 'grow' }), this.status);
    if (!paired) {
      replace(this.root, h('div', { class: 'hn-page' }, top, h('div', { class: 'sky-space' }), this.pairCard(), this.foot()));
      this.refreshStatus();
      this.refreshSky();
      return;
    }
    replace(this.root, h('div', { class: 'hn-page' },
      top,
      h('div', { class: 'sky-space' }),
      this.guideSlot,
      h('section', { class: 'section' }, seam('Lens', 'live mirror', 'live'),
        h('div', { class: 'lens-frame' }, this.mirror, h('div', { class: 'glow' })),
        h('div', { class: 'caption' }, this.mirrorCaption, this.mirrorRate),
        this.pad()),
      this.showSlot,
      this.typeSlot,
      this.settingsSlot,
      h('section', { class: 'section' }, seam('On the glasses'), this.gestures(), this.gesturesFoot),
      this.foot()));
    this.renderGuide();
    this.renderGestures();
    this.renderShow();
    this.renderType();
    this.renderSettings();
    this.refreshStatus();
    this.refreshSky();
    this.kick();
  }

  private foot(): HTMLElement {
    const v = (import.meta.env?.VITE_APP_VERSION as string | undefined) ?? 'dev';
    return h('p', { class: 'foot' }, `Hypernova Lens ${v} by prudnik-web · free software under the Apache-2.0 license · speech by Soniox, through your Mac · `,
      h('a', { href: 'licenses.txt', onClick: (e: Event) => { e.preventDefault(); void this.openLicenses(); } }, 'Source and licenses'));
  }

  /** The licence, where the source is, and the third-party notices (licenses.txt, shipped in the app). */
  private async openLicenses(): Promise<void> {
    let text: string;
    try { text = await (await fetch('licenses.txt', { cache: 'no-store' })).text(); } catch (err) { toast(explain(err)); return; }
    // The file is wrapped for a terminal; on a phone its paragraphs flow (indented lines and rules stay).
    text = text.replace(/([^\n=])\n(?=[^\n\s=])/g, '$1 ');
    document.querySelector('.viewer')?.remove();
    const v = h('div', { class: 'viewer text', role: 'dialog', 'aria-label': 'Source and licenses' },
      h('header', {}, h('span', { class: 'hn-label' }, 'Source and licenses'), h('span', { class: 'grow', style: { flex: '1' } }),
        h('button', { class: 'hn-pill ghost small', onClick: () => v.remove() }, 'Close')),
      h('div', { class: 'body text' }, h('pre', {}, text)));
    document.body.append(v);
  }

  // ------------------------------------------------------------------ pairing

  private pairCard(): HTMLElement {
    const paste = h('input', { class: 'input', placeholder: 'https://your-mac.ts.net:47321/pair?c=…', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', inputmode: 'url' });
    const go = async (text: string) => {
      const link = parsePairLink(text);
      if (!link) { toast('That isn’t a Hypernova pairing code.'); return; }
      try { await this.d.pairWith(link); toast(`Paired with ${link.macName}`); } catch (err) { toast(explain(err), 4200); }
    };
    return h('section', { class: 'hn-card stack' },
      h('h1', { class: 'hero' }, 'Your Mac, on your glasses.'),
      h('p', { class: 'muted', style: { margin: '0' } }, 'Glance at your Claude Code and Codex sessions, talk to them, answer them, and see what they built — with the Mac in your bag.'),
      h('p', { class: 'muted small', style: { margin: '6px 0 0' } }, 'On the Mac: Hypernova in the menu bar → Pair a phone. Then scan its code here.'),
      h('div', { class: 'row', style: { marginTop: '14px' } },
        h('button', { class: 'hn-pill primary', disabled: !this.d.capture, onClick: () => void this.scan(go) }, 'Scan the code'),
        h('button', { class: 'hn-pill ghost', onClick: () => this.d.startDemo() }, 'Try it with sample data')),
      h('label', { class: 'field' }, h('span', { class: 'hn-label' }, 'Or paste the link'),
        h('div', { class: 'row between' }, paste, h('button', { class: 'hn-pill outline small', onClick: () => void go(paste.value) }, 'Pair'))));
  }

  private async scan(then: (text: string) => Promise<void>): Promise<void> {
    if (!this.d.capture) { toast('The camera works inside the Even App.'); return; }
    try {
      const img = await this.d.capture();
      if (!img) return;
      const text = await decodeQr(img);
      if (!text) { toast('No code found in that photo. Hold the phone a little further away.'); return; }
      await then(text);
    } catch (err) { toast(explain(err), 4000); }
  }

  // ------------------------------------------------------------------ status and sky

  private refreshStatus(): void {
    const hub = this.d.hub();
    const name = hub?.macName ?? this.d.store.pairing?.macName ?? '';
    const st = this.d.demo() ? 'live' : hub?.status ?? 'off';
    const words: Record<string, string> = { live: name, polling: name, connecting: 'connecting', offline: 'offline', unpaired: 'unpaired', off: 'not paired' };
    this.status.textContent = this.d.demo() ? 'sample data' : words[st] ?? st;
    this.status.className = `hn-chip ${st === 'live' || st === 'polling' ? 'live' : ''}`;
    this.status.style.setProperty('--c', st === 'offline' || st === 'unpaired' ? 'var(--hn-amber)' : st === 'live' || st === 'polling' ? 'var(--hn-accent)' : 'var(--hn-dim)');
  }

  private refreshSky(): void {
    const snap = this.d.hub()?.snapshot;
    if (!this.sky) return;
    if (!snap) { this.sky.setData({ usage: 0.05, groups: [], readout: false }); return; }
    const now = Date.now();
    const u = snap.usage?.fiveHour, w = snap.usage?.sevenDay;
    const groups = new Map<string, Array<{ id: string; label: string; state: 'working' | 'needs' | 'idle'; minutes: number }>>();
    for (const s of snap.sessions.filter(x => x.state !== 'ended')) {
      const needs = s.state === 'needs_you' || snap.approvals.some(a => a.sessionId === s.id) || !!s.menu;
      // A session whose agents still work is at work, its main agent idle or not.
      const item = { id: s.id.slice(0, 2), label: s.title, state: needs ? 'needs' as const : isBusy(s, this.d.hub()?.held) ? 'working' as const : 'idle' as const, minutes: Math.max(1, (now - s.createdAt) / 60_000) };
      if (!groups.has(s.projectName)) groups.set(s.projectName, []);
      groups.get(s.projectName)!.push(item);
    }
    this.sky.setData({
      usage: u ? u.pct / 100 : 0.05, resetAt: u?.resetsAt ?? null, limitedAt: u && u.pct >= 100 ? u.resetsAt - 5 * 3600_000 : null,
      week: w ? w.pct / 100 : 0, weekResetAt: w?.resetsAt ?? null, readout: !!u,
      groups: [...groups].map(([name, items]) => ({ name, items })),
    });
  }

  // ------------------------------------------------------------------ the mirror

  private drawMirror(): void {
    const c = this.mirror.getContext('2d');
    if (!c) return;
    const page = this.d.sender.shown ?? this.d.output.page;
    drawLens(c, page, { orrery: this.d.output.orreryCanvas, shot: this.d.output.shotCanvas, power: this.d.output.powerCanvas, look: this.d.output.lookCanvas });
    this.mirrorCaption.textContent = this.d.sender.sinkName;
    const s = this.d.sender.stats;
    this.mirrorRate.textContent = `${s.rebuilds} pages · ${s.upgrades} text · ${s.images} images`;
  }

  private pad(): HTMLElement {
    const L = this.d.lens;
    const hold = h('button', { class: 'hold', 'aria-label': 'Hold to talk' }, 'Hold to talk');
    const down = (e: Event) => { e.preventDefault(); hold.classList.add('held'); void L.hold(); };
    const up = () => { if (!hold.classList.contains('held')) return; hold.classList.remove('held'); void L.release(); };
    hold.addEventListener('pointerdown', down);
    hold.addEventListener('pointerup', up);
    hold.addEventListener('pointerleave', up);
    hold.addEventListener('pointercancel', up);
    return h('div', { class: 'pad' },
      h('button', { onClick: () => L.up(), 'aria-label': 'Swipe up' }, '↑'),
      h('button', { onClick: () => L.down(), 'aria-label': 'Swipe down' }, '↓'),
      h('button', { onClick: () => L.tap() }, 'Tap'),
      h('button', { onClick: () => L.doubleTap() }, 'Double'),
      hold);
  }

  private gestures(): HTMLElement {
    const g = (k: string, v: string) => [h('dt', {}, k), h('dd', {}, v)];
    return h('div', { class: 'hn-card' }, h('dl', { class: 'gestures' },
      ...g('Look up', 'the glance: a line per session, the one that needs you on top, usage in the corner'),
      ...g('Hold', 'talk to the session in front of you; on the glance, to what needs you, else a new session in Nebula; let go and it shows whole, then goes in 2 s (a long one waits longer, up to 8 s; a swipe pauses it until you tap)'),
      ...g('Tap', 'open a line · send now · allow · pick · read aloud · pause a working session’s scroll'),
      ...g('Double tap', 'back · cancel what you said · deny · quiet the lens'),
      ...g('Swipe', 'move between sessions, options, or where a message goes; scroll a reply, a plan, a diff or a working session’s output; up from the top of the glance: the batteries; past the end of a card that needs you: answer later; on a pop-up: put it away'),
      ...g('Say', '“status”, “stop”, “read the reply”, “send it”, “telescope” (or “telescope emulator”, “web”, “terminal”, “simulator”, “image”, “window”), “watch”, “mute”, “unmute”; “approve” or “deny, use a branch” answer the card on the lens (anywhere else they bring it up to read first); “in hyperscribe, …” to pick a project, “new session in 0stems: …” or “new session: …” (in Nebula) to start one; “battery”; “stay awake”, “let it sleep”; on a bike or a walk: “moving” and “still”, “debrief”, “note, …”, “next”, “back”, “one”, “two”, “three”, “plan first”, “go ahead”, “continue”, “eclipse”, “what changed?”, “show diff”, and “approve push” for a risky command; “guide” to practise the gestures')));
  }

  // ------------------------------------------------------------------ the Lens guide

  private gesturesFoot = h('div');

  /** The guide's place: the Mac's (shared with its window), or this page's own with sample data. */
  private guide(): GuideState | null {
    if (this.d.demo()) return this.guideOverride ?? this.localGuide;
    const g = this.d.hub()?.snapshot?.guide;
    if (!g) return null;
    return this.guideOverride ?? g;
  }

  private renderGuide(): void {
    const g = this.guide();
    if (!g || g.done) { this.shownSteps = null; replace(this.guideSlot); return; }
    const api = this.d.macApi();
    if (api && !g.seen && !this.seenSent) { this.seenSent = true; void moveGuide(api, { seen: true }).catch(() => { this.seenSent = false; }); }
    const stt = this.d.hub()?.snapshot?.stt;
    // Voice already works (Soniox on the Mac): the guide skips it.
    this.shownSteps ??= guideSteps(!!stt?.ready, g.step);
    // The Mac's window went to Voice (asked from the phone): it comes back at the end.
    if (!this.shownSteps.includes(g.step)) this.shownSteps = [...this.shownSteps, g.step];
    replace(this.guideSlot, guideCard({
      guide: g, steps: this.shownSteps, sttReady: !!stt?.ready, sttKnown: !!stt, sttError: stt?.error ?? null, macApi: api,
      move: patch => void this.moveGuide(patch),
      showOnMac: () => void this.showOnMac(g),
      redraw: () => this.renderSettings(),
    }));
  }

  /** "Lens guide" under the gestures, once the guide is done, to see it again. */
  private renderGestures(): void {
    const g = this.guide();
    replace(this.gesturesFoot, g?.done
      ? h('div', { class: 'row', style: { marginTop: '10px' } }, h('button', { class: 'hn-pill ghost small', onClick: () => void this.moveGuide({ done: false }) }, 'Open the Lens guide'))
      : null);
  }

  private async moveGuide(patch: Partial<GuideState>): Promise<void> {
    const cur = this.guide();
    if (!cur) return;
    const next: GuideState = { ...cur, ...patch, seen: true, ...(patch.done === false && !patch.step ? { step: 'about' as const } : {}) };
    this.guideOverride = next;
    if (this.d.demo()) this.localGuide = next;
    this.update();
    const api = this.d.macApi();
    if (!api) { this.guideOverride = null; return; }
    try { await moveGuide(api, patch); } catch (err) { toast(explain(err)); }
    this.guideOverride = null;
    this.update();
  }

  private async showOnMac(g: GuideState): Promise<void> {
    const api = this.d.macApi();
    if (!api) { toast('Sample data has no Mac to show it on.'); return; }
    try {
      const macs = await showGuideOnMac(api, g.step);
      toast(macs > 0 ? 'On your Mac now' : 'Open Hypernova in your Mac’s menu bar, then try again.', 3600);
    } catch (err) { toast(explain(err), 4000); }
  }

  // ------------------------------------------------------------------ telescope

  private renderShow(): void {
    if (!this.shot && !this.live) { replace(this.showSlot); return; }
    const ago = this.shot ? Math.max(0, Math.round((Date.now() - this.shot.at) / 60_000)) : 0;
    replace(this.showSlot, h('section', { class: 'section' },
      seam('Telescope', this.shot ? `${this.shot.title || 'picture'} · ${ago ? `${ago}m ago` : 'now'}` : 'live page'),
      h('div', { class: 'hn-card shot-card' },
        this.shot ? h('img', { class: 'shot', src: this.shot.url, alt: this.shot.title || 'Screenshot', onClick: () => this.openViewer() }) : null,
        h('div', { class: 'meta' },
          h('span', { class: 'mono-s' }, this.shot?.pageUrl ?? this.shot?.label ?? this.shot?.path ?? this.live ?? ''),
          h('span', { class: 'row' },
            this.live ? h('button', { class: 'hn-pill outline small', onClick: () => this.openViewer(true) }, 'Open live') : null,
            this.shot ? h('button', { class: 'hn-pill ghost small', onClick: () => this.openViewer() }, 'Full size') : null)))));
  }

  private openViewer(live = !this.shot): void {
    document.querySelector('.viewer')?.remove();
    const src = live ? this.live : this.shot?.url;
    if (!src) return;
    const close = () => v.remove();
    const v = h('div', { class: 'viewer', role: 'dialog', 'aria-label': 'Telescope' },
      h('header', {}, h('span', { class: 'hn-label' }, live ? 'Live page' : this.shot?.title || 'Screenshot'), h('span', { class: 'grow', style: { flex: '1' } }),
        h('button', { class: 'hn-pill ghost small', onClick: close }, 'Close')),
      h('div', { class: 'body' }, live ? h('iframe', { src, title: 'Live page', sandbox: FRAME_SANDBOX }) : h('img', { src, alt: this.shot?.title || 'Screenshot' })));
    document.body.append(v);
  }

  // ------------------------------------------------------------------ type instead

  private renderType(): void {
    const hub = this.d.hub();
    const snap = hub?.snapshot;
    if (!snap) { replace(this.typeSlot); return; }
    const sessions = activeSessions(snap, { projects: null }, { workingSince: new Map(), unseen: new Map() }, Date.now()).slice(0, 8);
    const target = h('select', { class: 'input', 'aria-label': 'Session' },
      ...sessions.map(s => h('option', { value: `s:${s.id}` }, `${s.projectName} · ${s.title}`.slice(0, 70))),
      ...snap.projects.slice(0, 8).map(p => h('option', { value: `p:${p.path}` }, `New session in ${p.name}`)),
      // As a hold on the glasses' main screen: a folder of its own in Nebula, named from the prompt.
      hub?.newNebulaSession ? h('option', { value: 'n:' }, 'New project in Nebula') : null);
    // A new session goes to the agent that leads (the Mac picks Codex where Claude Code isn't installed).
    const text = h('textarea', { class: 'input', placeholder: leading(snap).codex ? 'What should Codex do?' : 'What should Claude do?', rows: '3' });
    const send = async () => {
      const t = text.value.trim();
      if (!t || !hub) return;
      const v = target.value;
      try {
        if (v.startsWith('s:')) { const r = await hub.prompt(v.slice(2), t); toast(r.queued ? 'Queued' : 'Sent'); }
        else if (v === 'n:' && hub.newNebulaSession) { const s = await hub.newNebulaSession(folderName(t), t); toast(`New project: ${s?.projectName ?? folderName(t)}`); }
        else { await hub.newSession(v.slice(2), t); toast('New session started'); }
        text.value = '';
      } catch (err) { toast(explain(err), 4000); }
    };
    replace(this.typeSlot, h('section', { class: 'section' }, seam('Type instead'),
      h('div', { class: 'hn-card' }, target, h('div', { style: { height: '8px' } }), text,
        h('div', { class: 'row', style: { marginTop: '10px', justifyContent: 'flex-end' } }, h('button', { class: 'hn-pill primary small', onClick: () => void send() }, 'Send')))));
  }

  // ------------------------------------------------------------------ settings

  private renderSettings(): void {
    const { store } = this.d;
    const s = store.settings;
    const hub = this.d.hub();
    const snap = hub?.snapshot;
    const p = store.pairing;

    const mac = h('div', { class: 'hn-card' },
      h('div', { class: 'row between' }, h('div', {}, h('div', { class: 'hn-heading' }, this.d.demo() ? 'Sample data' : hub?.macName ?? p?.macName ?? 'Mac'),
        h('div', { class: 'mono-s' }, this.d.demo() ? 'no Mac: everything here is made up' : p?.baseUrl ?? '')),
        h('button', { class: 'hn-pill danger small', onClick: () => void this.d.unpair() }, this.d.demo() ? 'Leave' : 'Unpair')),
      hub?.problem && !this.d.demo() ? h('div', { class: 'note' }, hub.problem) : null);

    // Which sessions the glance shows.
    const names = [...new Set([...(snap?.projects ?? []).map(x => x.name), ...(snap?.sessions ?? []).map(x => x.projectName)])].slice(0, 24);
    const only = s.projects !== null;
    const choices = h('div', { class: 'choices' }, ...names.map(n => {
      const on = !!s.projects?.includes(n);
      return h('button', { class: `choice ${on ? 'on' : ''}`, onClick: () => {
        const cur = new Set(store.settings.projects ?? []);
        if (on) cur.delete(n); else cur.add(n);
        void store.update({ projects: [...cur] });
      } }, n);
    }));
    const lensCard = h('div', { class: 'hn-card' },
      h('div', { class: 'hn-label' }, 'Sessions on the lens'),
      h('div', { class: 'seg', style: { marginTop: '10px' } },
        h('button', { class: only ? '' : 'on', onClick: () => void store.update({ projects: null }) }, 'All active'),
        h('button', { class: only ? 'on' : '', onClick: () => void store.update({ projects: s.projects ?? [] }) }, 'Only these projects')),
      only ? choices : h('p', { class: 'dim small', style: { margin: '10px 2px 0' } }, 'Sessions that work, need you, or finished in the last 45 minutes; four at most, the one that needs you first.'));

    // Speech: Soniox through the Mac (speech-card.ts).
    const speech = speechCard({
      store, stt: snap?.stt, demo: this.d.demo(), source: this.d.speechKind(), macApi: this.d.macApi(),
      redraw: () => this.renderSettings(),
    });

    // Read aloud: the switch first, on its own; then the voice it reads with.
    const readOn = s.readAloud !== false;
    const readSwitch = h('button', { class: `switch ${readOn ? 'on' : ''}`, role: 'switch', 'aria-checked': String(readOn), 'aria-label': 'Read replies aloud',
      onClick: () => { if (readOn) this.d.voice.stop(); void store.update({ readAloud: !readOn }); } });
    const aloud = h('div', { class: 'hn-card' },
      h('div', { class: 'row between' },
        h('div', {}, h('div', { class: 'hn-heading' }, 'Read replies aloud'),
          h('div', { class: 'small muted', style: { marginTop: '4px' } }, readOn ? 'Tap a reply on the lens to hear it.' : 'Off: taps and “status” stay silent.')),
        readSwitch),
      h('p', { class: 'dim small', style: { margin: '10px 2px 0' } }, 'On the glasses, say “mute” or “unmute”.'));
    const phoneVoices = VoiceOut.phoneVoices();
    const voiceSel = h('select', { class: 'input', onChange: (e: Event) => void store.update({ voice: (e.target as HTMLSelectElement).value as VoiceChoice }) },
      h('option', { value: 'auto' }, 'Automatic (the Mac’s voice, else the phone’s)'),
      ...this.macVoices.map(v => h('option', { value: `mac:${v.id}` }, `Mac · ${v.name} (${v.lang})`)),
      phoneVoices.length ? h('option', { value: 'phone' }, 'This phone’s voice') : null);
    voiceSel.value = s.voice === 'off' ? 'auto' : s.voice;
    const rate = h('input', { type: 'range', min: '0.7', max: '1.6', step: '0.05', value: String(s.rate), onChange: (e: Event) => void store.update({ rate: Number((e.target as HTMLInputElement).value) }) });
    const blip = h('button', { class: `switch ${s.blip ? 'on' : ''}`, role: 'switch', 'aria-checked': String(s.blip), 'aria-label': 'Soft blip', onClick: () => void store.update({ blip: !s.blip }) });
    const read = h('div', { class: 'hn-card' },
      h('div', { class: 'hn-label' }, 'Voice'),
      h('p', { class: 'small muted', style: { margin: '8px 2px 0' } }, snap?.speech?.phone
        ? `Replies are read by the Hypernova app on this phone, through your headphones, screen off or not: ${snap.speech.using === 'soniox' ? `Soniox · ${snap.speech.voice}` : 'the Mac’s voice'}. Change it in Hypernova’s Settings → Voice.`
        : 'Replies are read into your earbuds. Android’s WebView has no voices of its own, so the Mac speaks them. With the Hypernova app connected on this phone, it plays them instead, with the screen off.'),
      h('label', { class: 'field' }, h('span', { class: 'hn-label' }, 'Voice'), voiceSel),
      h('label', { class: 'field' }, h('span', { class: 'hn-label' }, `Speed · ${s.rate.toFixed(2)}×`), rate),
      h('div', { class: 'row between', style: { marginTop: '14px' } }, h('span', { class: 'small' }, 'Soft blip when a session finishes or needs you'), blip),
      h('div', { class: 'row', style: { marginTop: '12px' } },
        h('button', { class: 'hn-pill outline small', onClick: async () => {
          this.d.voice.unlock();
          const who = await this.d.voice.speak('Hypernova Lens is ready. Hold the temple to talk.');
          toast(who === 'app' ? 'That was the Hypernova app on this phone' : who === 'mac' ? 'That was your Mac’s voice' : who === 'phone' ? 'That was the phone’s voice'
            : who === 'locked' ? 'The phone isn’t letting Lens play sound yet. Touch the screen and try again.' : 'No voice available: update Hypernova on the Mac');
        } }, 'Test voice'),
        h('button', { class: 'hn-pill ghost small', onClick: () => { this.d.voice.unlock(); this.d.voice.blip('done'); } }, 'Test blip')),
      this.d.voice.locked ? h('p', { class: 'dim small', style: { margin: '10px 2px 0' } }, 'Touch this screen once after opening Lens so the glasses can speak through the phone.') : null);

    // New sessions (Nebula), how text arrives, and pop-ups: what the glasses do by themselves.
    const glasses = glassesCards({ store, macApi: this.d.macApi(), demo: this.d.demo(), redraw: () => this.renderSettings(), startGuide: () => this.d.lens.startGuide() });
    replace(this.settingsSlot, h('section', { class: 'section stack' }, seam('Settings'), aloud, mac, lensCard, ...glasses, speech, read));
    if (hub && !this.d.demo() && this.voicesOf !== hub) {
      this.voicesOf = hub;
      // A Mac that was away when Lens opened is asked again on a later redraw (its snapshot coming
      // in is one), so its voices don't wait for Lens to be reopened. A request that went out while
      // it was away and failed once it was back asks again at once: that redraw may have come and
      // gone meanwhile. Only then: one that fails with the Mac there (an older Mac) waits for the next.
      const up = () => hub.status === 'live' || hub.status === 'polling';
      const askedUp = up();
      void hub.voices().then(v => { if (v.length) { this.macVoices = v; this.renderSettings(); } }).catch(() => {
        if (this.voicesOf !== hub) return;
        this.voicesOf = null;
        if (!askedUp && up()) this.renderSettings();
      });
    }
  }
}
