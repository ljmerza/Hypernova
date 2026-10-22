// Lens's phone screen when it goes through the Hypernova app: one status card. The sky, the sessions
// and the glasses' settings live in the Hypernova app now (the owner: "the glasses app should be
// simple, because all the visuals are in the app for a phone"), so this page only says whether Lens
// is connected, or what's missing, and opens the app: by asking it (`POST /bridge/open`), since the
// Even App's web view hands no link of another app's to Android.
import './hyper-cosmos.css';
import './styles.css';
import { h, replace, toast } from './dom.ts';
import { explain } from '../data/api.ts';
import type { HubLike } from '../data/hub.ts';
import type { BridgeStatus } from '../data/bridge.ts';
import type { LinkState } from '../data/link.ts';
import type { Shot } from '../core/types.ts';
import { cardWords, openWords, type CardWords } from './status-words.ts';
import type { OpenShown } from '../data/bridge.ts';
/**
 * What a previewed page may do inside its frame: run and ask for its own files, never navigate
 * Lens's page or open windows. The page comes from the Mac through the app, unsigned (a frame
 * can't sign), and if Hypernova is gone whatever holds its port serves it instead: so it must stay
 * in its box. (Open question for Even Realities: is the Even App's JS bridge main-frame-only, so a
 * subframe can't reach the app's storage? Lens assumes not, and gives the frame nothing else.)
 */
export const FRAME_SANDBOX = 'allow-scripts allow-same-origin allow-forms';

export interface StatusDeps {
  link(): LinkState;
  hub(): HubLike | null;
  /** The app's own link to the Mac (`GET /bridge/status`), fetched now and then; null until it answers. */
  bridgeStatus(): BridgeStatus | null;
  refreshStatus(): void;
  /** Ask the phone again (denied, unanswered, disconnected). */
  askAgain(): void;
  /** Let go of the app's access (the token goes, here and in the app). */
  disconnect(): Promise<void>;
  /** "Open Hypernova": the app asked to open at Settings → Glasses, and how it did; null when Lens couldn't ask it (no access, no app). */
  openApp(): Promise<OpenShown | null>;
  startDemo(): void;
  /** This build may pair with a Mac directly (a private build, or development): the older way. */
  direct: boolean;
  pairDirect(): void;
}

export class StatusPage {
  private d: StatusDeps;
  private root: HTMLElement;
  private card = h('section', { class: 'hn-card status-card' });
  private shot: Shot | null = null;
  private live: string | null = null;
  private key = '';

  constructor(root: HTMLElement, d: StatusDeps) {
    this.d = d;
    this.root = root;
    document.body.classList.add('status-mode');
    const v = (import.meta.env?.VITE_APP_VERSION as string | undefined) ?? 'dev';
    replace(this.root, h('div', { class: 'hn-page status-page' },
      h('header', { class: 'hn-topbar topbar' }, h('span', { class: 'hn-wordmark' }, 'hypernova'), h('span', { class: 'sub' }, 'lens')),
      this.card,
      h('p', { class: 'foot' }, `Hypernova Lens ${v} by prudnik-web · free software under the Apache-2.0 license · `,
        h('a', { href: 'licenses.txt', onClick: (e: Event) => { e.preventDefault(); void this.openLicenses(); } }, 'Source and licenses'))));
    this.update();
  }

  dispose(): void {
    document.body.classList.remove('status-mode');
    document.querySelector('.viewer')?.remove();
  }

  /** The link, the hub or the app's status changed: the card is redrawn only when its words do. */
  update(): void {
    const link = this.d.link();
    if (link.kind === 'bridge') this.d.refreshStatus();
    const w = cardWords(link, this.d.hub(), this.d.bridgeStatus(), Date.now());
    const key = JSON.stringify([w, link.kind, !!this.shot]);
    if (key === this.key) return;
    this.key = key;
    this.render(link, w);
  }

  lensChanged(): void { /* no mirror here: the lens is on the glasses */ }
  settingsChanged(): void { /* the glasses' settings are in the Hypernova app */ }

  private render(link: LinkState, w: CardWords): void {
    const open = h('button', { class: 'hn-pill primary small', onClick: () => void this.openApp() }, 'Open Hypernova');
    const again = h('button', { class: 'hn-pill outline small', onClick: () => this.d.askAgain() }, link.kind === 'revoked' ? 'Connect again' : 'Ask again');
    const buttons: Array<HTMLElement | null> = [];
    switch (link.kind) {
      case 'bridge':
        buttons.push(open, h('button', { class: 'hn-pill ghost small', onClick: () => void this.disconnect() }, 'Disconnect'));
        break;
      case 'denied': case 'expired': case 'unconfirmed': case 'revoked': buttons.push(again, open); break;
      // Hypernova's own notification carries Allow and Deny; Open can't work before it's allowed.
      case 'asking': break;
      // Nothing here updates Lens: the Even app does.
      case 'old_lens': break;
      // Not where "Connect again" was: a tap meant for that isn't taken for this.
      case 'confirm': buttons.push(open, h('button', { class: 'hn-pill outline small', onClick: () => this.d.askAgain() }, 'Connect')); break;
      case 'no_app': buttons.push(open, h('button', { class: 'hn-pill ghost small', onClick: () => this.d.startDemo() }, 'Try it with sample data')); break;
      case 'old_app': buttons.push(open); break;
      case 'no_seal': buttons.push(h('button', { class: 'hn-pill ghost small', onClick: () => this.d.startDemo() }, 'Try it with sample data')); break;
      default: buttons.push(open);
    }
    const directOffer = this.d.direct && (link.kind === 'no_app' || link.kind === 'denied' || link.kind === 'expired' || link.kind === 'unconfirmed' || link.kind === 'revoked')
      ? h('p', { class: 'dim small', style: { margin: '14px 2px 0' } }, 'No Hypernova app here? ',
        h('a', { href: '#', onClick: (e: Event) => { e.preventDefault(); this.d.pairDirect(); } }, 'Pair Lens with your Mac directly'))
      : null;
    const mark = h('span', { class: `status-mark ${w.tone}`, 'aria-hidden': 'true' });
    replace(this.card,
      h('div', { class: 'status-head' }, mark, h('h1', { class: 'hn-title status-title' }, w.title)),
      w.sub ? h('p', { class: 'muted status-sub' }, w.sub) : null,
      w.code ? h('div', { class: 'status-code', 'aria-label': `Code ${w.code.split('').join(' ')}` }, ...w.code.split('').map(c => h('span', {}, c))) : null,
      link.kind === 'bridge' ? h('p', { class: 'dim small', style: { margin: '12px 2px 0' } }, 'How the glasses read, pop up and speak is set in Hypernova on this phone: Settings → Glasses.') : null,
      buttons.length ? h('div', { class: 'row', style: { marginTop: '16px' } }, ...buttons) : null,
      directOffer,
      this.shot ? h('div', { class: 'row', style: { marginTop: '12px' } }, h('button', { class: 'hn-pill ghost small', onClick: () => this.openViewer() }, 'Last Telescope picture')) : null);
  }

  /** Asks the app to open (Lens can't open it itself); says, in Lens's own words, what to do next. */
  private async openApp(): Promise<void> {
    let shown: OpenShown | null = null;
    try { shown = await this.d.openApp(); } catch { /* said as "open it" */ }
    toast(openWords(shown), 4200);
  }

  private async disconnect(): Promise<void> {
    try { await this.d.disconnect(); toast('Disconnected'); } catch (err) { toast(explain(err)); }
  }

  /** Telescope's last picture: the glasses can open it here (a tap on a zoomed quarter). */
  setShot(shot: Shot | null, live: string | null): void {
    if (shot) this.shot = shot;
    this.live = live ?? (shot ? null : this.live);
    this.update();
  }

  showShot(shot: Shot | null, live: string | null): void {
    if (shot || live) this.setShot(shot, live);
    this.openViewer(!this.shot && !!this.live);
  }

  private openViewer(live = !this.shot): void {
    document.querySelector('.viewer')?.remove();
    const src = live ? this.live : this.shot?.url;
    if (!src) return;
    const v = h('div', { class: 'viewer', role: 'dialog', 'aria-label': 'Telescope' },
      h('header', {}, h('span', { class: 'hn-label' }, live ? 'Live page' : this.shot?.title || 'Telescope'), h('span', { class: 'grow', style: { flex: '1' } }),
        h('button', { class: 'hn-pill ghost small', onClick: () => v.remove() }, 'Close')),
      // Sandboxed: whatever serves the page (the app carries it from the Mac; if Hypernova is gone,
      // whatever holds its port) gets no way out of the frame: no top navigation, no popups.
      h('div', { class: 'body' }, live ? h('iframe', { src, title: 'Live page', sandbox: FRAME_SANDBOX }) : h('img', { src, alt: this.shot?.title || 'Telescope picture' })));
    document.body.append(v);
  }

  private async openLicenses(): Promise<void> {
    let text: string;
    try { text = await (await fetch('licenses.txt', { cache: 'no-store' })).text(); } catch (err) { toast(explain(err)); return; }
    text = text.replace(/([^\n=])\n(?=[^\n\s=])/g, '$1 ');
    document.querySelector('.viewer')?.remove();
    const v = h('div', { class: 'viewer text', role: 'dialog', 'aria-label': 'Source and licenses' },
      h('header', {}, h('span', { class: 'hn-label' }, 'Source and licenses'), h('span', { class: 'grow', style: { flex: '1' } }),
        h('button', { class: 'hn-pill ghost small', onClick: () => v.remove() }, 'Close')),
      h('div', { class: 'body text' }, h('pre', {}, text)));
    document.body.append(v);
  }
}
