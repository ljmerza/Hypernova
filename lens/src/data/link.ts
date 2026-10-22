// How Lens reaches the Mac: through the Hypernova app on this phone when it answers (data/bridge.ts),
// else by a pairing of its own (the older way, kept for setups without the app: iOS for now, a
// private build). With the app, Lens asks once for access ("Approve on your phone: Hypernova"):
// the two agree a token without sending it, Lens keeps it and signs its calls with a key made from
// it, and only once whoever listens on the app's port proved it holds that key. Disconnect on the
// phone takes it back.
import { BRIDGE_PROTOCOL, Bridge, BridgeError, BridgeSigner, WORDS, canSeal, finishPairing, startPairing, type AccessAsk, type AccessPoll, type BridgePing, type BridgeSettings } from './bridge.ts';
import { b64url } from '../core/crypto.ts';

export type LinkState =
  /** Asking whether the app is here. */
  | { kind: 'looking' }
  /** Nothing answers on 127.0.0.1:47380, and Lens has no pairing of its own. */
  | { kind: 'no_app' }
  /** The app answers, but speaks an older bridge (it signs, it doesn't seal): nothing goes to it until it's updated. */
  | { kind: 'old_app'; app: string; protocol: number }
  /** The app is newer than this Lens and refuses it (`update_lens`): Lens is updated in the Even app. */
  | { kind: 'old_lens' }
  /** This page can't seal calls (no WebCrypto: not a secure context, as a dev server opened by a LAN address). */
  | { kind: 'no_seal' }
  /** Waiting for Allow on the phone; `code` is on both screens. */
  | { kind: 'asking'; code: string; until: number }
  /**
   * The app said it was allowed: a tap on the glasses (or Connect on Lens's phone screen) says the
   * owner did allow it, in Hypernova. Whatever holds the port can say "allowed" by itself; it can't
   * make the owner have seen the question on their phone.
   */
  | { kind: 'confirm'; code: string; until: number }
  /** Asked too often lately: Lens asks again by itself at `retryAt`. */
  | { kind: 'busy'; retryAt: number }
  | { kind: 'denied' }
  /** Nobody answered on the phone in time. */
  | { kind: 'expired' }
  /** Allowed on the phone, but nobody tapped the glasses (or Connect) in the app's window to collect it. */
  | { kind: 'unconfirmed' }
  /** Disconnected on the phone (or the Mac forgot the glasses). */
  | { kind: 'revoked' }
  /** Through the app: every call goes to it, signed with a key made from `token` (which stays here). */
  | { kind: 'bridge'; token: string; app: string }
  /** A pairing of its own, straight to the Mac. */
  | { kind: 'direct' };

export interface LinkDeps {
  bridge: Bridge;
  /** The token agreed with the app, kept in the Even App's storage for this app. */
  token(): string | null;
  setToken(token: string | null): Promise<void>;
  /** Lens has a pairing of its own (from before the app). */
  hasPairing(): boolean;
  /** How the phone's question names this Lens, and its version. */
  name: string;
  version: string;
  /** The glasses' settings from the app: on connecting (`first`) and later. */
  onSettings(s: BridgeSettings, first: boolean): void;
  now?(): number;
  sleep?(ms: number): Promise<void>;
  later?(ms: number, fn: () => void): () => void;
}

/** How often Lens asks how its request went, and looks again for an app that wasn't there. */
export const POLL_MS = 1000;
export const LOOK_AGAIN_MS = 4000;
/**
 * How long "Allowed on your phone? Tap to connect" waits for its tap: as long as the app keeps an
 * allowed ask for Lens to collect (LensBridge's COLLECT_TTL_MS), so a tap within it never has to ask
 * the phone again.
 */
export const CONFIRM_MS = 10 * 60_000;
/**
 * A tap counts as the owner's word only this long after the question shows (as the app's own
 * Allow wakes a moment after its code): a tap meant for what was there before isn't taken for it.
 */
export const CONFIRM_ARM_MS = 1500;

export class PhoneLink {
  state: LinkState = { kind: 'looking' };
  onChange: () => void = () => {};
  private d: LinkDeps;
  /** Each start (and stop) begins a new run; an older run's answers are dropped. */
  private gen = 0;
  private cancelLater: (() => void) | null = null;
  /** What signs the calls while Lens goes through the app. */
  private signerNow: BridgeSigner | null = null;
  /** The token agreed in an ask the app says was allowed, kept only once the owner confirms on the glasses. */
  private agreed: { token: string; app: string; shownAt: number } | null = null;

  constructor(d: LinkDeps) { this.d = d; }

  private now(): number { return this.d.now?.() ?? Date.now(); }
  private sleep(ms: number): Promise<void> { return this.d.sleep?.(ms) ?? new Promise(r => setTimeout(r, ms)); }
  private later(g: number, ms: number, fn: () => void): void {
    this.cancelLater?.();
    const run = () => { if (g === this.gen) fn(); };
    this.cancelLater = this.d.later?.(ms, run) ?? (() => { const t = setTimeout(run, ms); return () => clearTimeout(t); })();
  }

  private set(s: LinkState): void {
    this.state = s;
    this.onChange();
  }

  /** Through the app right now (its token, which names the route; it never leaves Lens). */
  get token(): string | null { return this.state.kind === 'bridge' ? this.state.token : null; }

  /** What signs the calls through the app right now. */
  get signer(): BridgeSigner | null { return this.state.kind === 'bridge' ? this.signerNow : null; }

  /**
   * Find the app; with a token, have it prove it holds the same key before anything is signed for
   * it; else ask; else Lens's own pairing. A token the listener doesn't prove (Disconnected on the
   * phone, or not the app at all) is kept, and Lens asks only when told to (`ask`: a tap, "Ask
   * again"): nothing answering on the port can make Lens forget its token, or ask by itself.
   */
  async start(ask = false): Promise<void> {
    const g = ++this.gen;
    this.cancelLater?.();
    // Every call through the app is sealed with WebCrypto: without it (a plain LAN page), nothing goes.
    if (!canSeal()) { if (this.d.hasPairing()) this.set({ kind: 'direct' }); else this.set({ kind: 'no_seal' }); return; }
    // Looking again for an app that wasn't there says nothing new on the lens until it answers.
    if (this.state.kind !== 'bridge' && this.state.kind !== 'no_app' && this.state.kind !== 'old_app') this.set({ kind: 'looking' });
    const token = this.d.token();
    const signer = token ? new BridgeSigner(this.d.bridge, token, () => this.now()) : null;
    let app: BridgePing | null;
    let proven = false;
    if (signer) ({ app, proven } = await signer.prove());
    else app = await this.d.bridge.ping();
    if (g !== this.gen) return;
    if (!app) return this.noApp(g);
    // An app that signs but doesn't seal (bridge 2): nothing is signed for it, it's asked for an update.
    if (app.protocol < BRIDGE_PROTOCOL) return this.oldApp(g, app);
    if (signer && proven) {
      try {
        const s = await this.d.bridge.settings(signer);
        if (g !== this.gen) return;
        this.d.onSettings(s, true);
        this.through(signer, app.version);
        return;
      } catch (err) {
        if (g !== this.gen) return;
        if (!(err instanceof BridgeError) || err.code === 'no_app') return this.noApp(g);
        if (err.code === 'update_lens') return this.oldLens();
        if (err.code !== 'bridge_access') {
          // The app is here and answered something else: the token stands, the hub says the rest.
          this.through(signer, app.version);
          return;
        }
      }
    }
    // A key the listener here doesn't prove: Disconnected on the phone, or another app holding the
    // port, which can say just that. Lens keeps the token (the app proves it again once it's back,
    // and a new one replaces it only once agreed) and waits to be told to ask.
    if (token && !ask) { this.set(this.d.hasPairing() ? { kind: 'direct' } : { kind: 'revoked' }); return; }
    return this.ask(g, app.version);
  }

  private through(signer: BridgeSigner, app: string): void {
    this.signerNow = signer;
    this.set({ kind: 'bridge', token: signer.token, app });
  }

  /**
   * A tap on the lens, or "Ask again" on the phone: try once more (a denied or unanswered ask, an app
   * that wasn't there); or, after "allowed", the owner's word that they allowed it.
   */
  retry(): void {
    const k = this.state.kind;
    if (k === 'confirm') { void this.confirm(); return; }
    if (k === 'denied' || k === 'expired' || k === 'unconfirmed' || k === 'revoked' || k === 'no_app') void this.start(true);
    if (k === 'old_app' || k === 'old_lens' || k === 'no_seal') void this.start();
  }

  /**
   * The app refuses this Lens as too old (`update_lens`, signed or not): said as that, and nothing
   * asks again by itself (every ask would be refused the same way). The token is kept: the updated
   * Lens finds the app with it.
   */
  oldLens(): void {
    ++this.gen;
    this.cancelLater?.();
    this.agreed = null;
    this.set({ kind: 'old_lens' });
  }

  /** The owner allowed it on the phone: the agreed token replaces the one kept, and Lens goes through the app. */
  private async confirm(): Promise<void> {
    const a = this.agreed;
    if (this.state.kind !== 'confirm' || !a) return;
    if (this.now() - a.shownAt < CONFIRM_ARM_MS) return;
    const g = ++this.gen;
    this.cancelLater?.();
    this.agreed = null;
    await this.d.setToken(a.token);
    if (g !== this.gen) return;
    const signer = new BridgeSigner(this.d.bridge, a.token, () => this.now());
    const { proven } = await signer.prove();
    if (g !== this.gen) return;
    // The app went away in the moment since: Lens finds it again with the token it keeps.
    if (!proven) { void this.start(); return; }
    // The glasses' settings, signed and sealed (the poll's answer is neither, so they aren't in it).
    try {
      const s = await this.d.bridge.settings(signer);
      if (g !== this.gen) return;
      this.d.onSettings(s, true);
    } catch (err) {
      if (g !== this.gen) return;
      // Not had just now: the live feed brings them (`bridge.settings`) as soon as it opens.
      if (err instanceof BridgeError && err.code === 'no_app') { void this.start(); return; }
      if (err instanceof BridgeError && err.code === 'update_lens') return this.oldLens();
    }
    this.through(signer, a.app);
  }

  /**
   * The app refused the key: Disconnect on the phone, or the Mac forgot these glasses (or another app
   * on the port says so: Lens can't tell them apart). Lens waits to be asked again, and keeps its
   * token meanwhile: when it was only something on the port, the app proves it again at the next
   * tap and nothing has to be allowed afresh.
   */
  async revoked(): Promise<void> {
    if (this.state.kind !== 'bridge') return;
    ++this.gen;
    this.cancelLater?.();
    this.set({ kind: 'revoked' });
  }

  /** Disconnect on the phone's status card: the token goes, here and in the app. */
  async leave(): Promise<void> {
    const signer = this.signer;
    ++this.gen;
    this.cancelLater?.();
    if (signer) await this.d.bridge.leave(signer);
    await this.d.setToken(null);
    this.set({ kind: 'revoked' });
  }

  /** Lens was just paired with a Mac of its own (the older way, on the phone's full page). */
  direct(): void {
    ++this.gen;
    this.cancelLater?.();
    this.set({ kind: 'direct' });
  }

  stop(): void {
    ++this.gen;
    this.cancelLater?.();
  }

  private noApp(g: number): void {
    // An older setup: Lens's own pairing works without the app.
    if (this.d.hasPairing()) { this.set({ kind: 'direct' }); return; }
    if (this.state.kind !== 'no_app') this.set({ kind: 'no_app' });
    // The app may be opened any moment.
    this.later(g, LOOK_AGAIN_MS, () => void this.start());
  }

  /** The app is here but older than this Lens: said, and looked at again now and then (it may be updated any moment). */
  private oldApp(g: number, app: BridgePing): void {
    if (this.d.hasPairing()) { this.set({ kind: 'direct' }); return; }
    if (this.state.kind !== 'old_app' || this.state.app !== app.version) this.set({ kind: 'old_app', app: app.version, protocol: app.protocol });
    this.later(g, LOOK_AGAIN_MS, () => void this.start());
  }

  private fallback(kind: 'denied' | 'expired' | 'unconfirmed'): void {
    this.set(this.d.hasPairing() ? { kind: 'direct' } : { kind });
  }

  /**
   * Ask the app for access, agreeing a token with it (data/bridge.ts): Lens's key and a commitment
   * to its nonce go first, the app answers with its key and nonce, then Lens shows its nonce and
   * both work out the code and the token. The code on the lens is Lens's own, never one the
   * listener sent.
   */
  private async ask(g: number, app: string): Promise<void> {
    const mine = startPairing();
    let a: AccessAsk;
    let agreed: { code: string; token: string } | null;
    /** Lens's nonce has gone out: from now on nothing asks again by itself (each ask is a try at the code). */
    let revealed = false;
    try {
      a = await this.d.bridge.ask(this.d.name, this.d.version, b64url(mine.pub), mine.commit);
      if (g !== this.gen) return;
      agreed = finishPairing(mine, a);
      if (!agreed) return this.fallback('expired');
      revealed = true;
      await this.d.bridge.reveal(a.id, b64url(mine.nonce));
    } catch (err) {
      if (g !== this.gen) return;
      if (revealed) return this.fallback('expired');
      if (err instanceof BridgeError && err.code === 'too_many_requests') {
        // Whatever the app (or whatever holds its port) says: between a second and an hour.
        const wait = Math.min(3600, Math.max(1, err.retryAfterSeconds || 30)) * 1000;
        this.set({ kind: 'busy', retryAt: this.now() + wait });
        // Asked too often: asking again later is still what was asked for.
        this.later(g, wait, () => void this.start(true));
        return;
      }
      if (err instanceof BridgeError && err.code === 'no_app') return this.noApp(g);
      // An app newer than this Lens refuses the ask itself: no prompt ever shows, so "not answered"
      // would blame the phone, and asking again would be refused the same way.
      if (err instanceof BridgeError && err.code === 'update_lens') return this.oldLens();
      return this.fallback('expired');
    }
    if (g !== this.gen) return;
    const until = this.now() + a.expiresInSeconds * 1000;
    this.set({ kind: 'asking', code: agreed.code, until });
    while (g === this.gen) {
      await this.sleep(POLL_MS);
      if (g !== this.gen) return;
      let p: AccessPoll;
      try {
        p = await this.d.bridge.poll(a.id);
      } catch {
        // The app went away for a moment: keep asking until the request would have run out.
        if (this.now() > until) return this.fallback('expired');
        continue;
      }
      if (g !== this.gen) return;
      if (p.state === 'pending') {
        if (this.now() > until + 5000) return this.fallback('expired');
        continue;
      }
      if (p.state === 'allowed') {
        // Allowed by an app that worked out the same token, or it's no good.
        if (!BridgeSigner.confirms(agreed.token, a.id, p.confirm)) return this.fallback('expired');
        // And the owner says so on the glasses: whatever holds the port could say "allowed" itself.
        this.agreed = { token: agreed.token, app, shownAt: this.now() };
        this.set({ kind: 'confirm', code: agreed.code, until: this.now() + CONFIRM_MS });
        // Not tapped in time: allowed on the phone, so it isn't "not answered" there.
        this.later(g, CONFIRM_MS, () => { this.agreed = null; this.fallback('unconfirmed'); });
        return;
      }
      return this.fallback(p.state === 'denied' ? 'denied' : 'expired');
    }
  }
}

/** What one connection state says: on the lens (`code` on a line of its own), and on Lens's phone card. */
export interface LinkWords {
  lens: { title: string; sub: string; code?: string };
  phone: { title: string; sub: string; tone: 'ok' | 'wait' | 'warn'; code?: string };
}

/**
 * The words for each connection state, in one table for the lens and Lens's phone card (so they say
 * the same thing). null: connected (the hub's state says the rest, offlineWords).
 */
export function linkWords(s: LinkState, now: number, direct: boolean): LinkWords | null {
  const both = (title: string, lens: string, phone: string, tone: LinkWords['phone']['tone']): LinkWords => ({ lens: { title, sub: lens }, phone: { title, sub: phone, tone } });
  switch (s.kind) {
    case 'looking': return { lens: { title: 'Looking for Hypernova…', sub: 'on this phone' }, phone: { title: 'Looking for Hypernova…', sub: 'Lens reaches your Mac through the Hypernova app on this phone.', tone: 'wait' } };
    case 'no_app': return {
      lens: { title: 'Open Hypernova on your phone', sub: direct ? 'Lens reaches your Mac through the Hypernova app, or pair Lens on its phone screen.' : 'Lens reaches your Mac through the Hypernova app.' },
      phone: { title: 'Hypernova isn’t open on this phone', sub: 'Lens reaches your Mac through the Hypernova app. Open it (it stays connected in the background), and Lens finds it by itself.', tone: 'warn' },
    };
    case 'old_app': {
      const which = s.app ? `Hypernova ${s.app}` : 'Hypernova on your phone';
      return { lens: { title: 'Update Hypernova on your phone', sub: `${which} is older than this Lens. Update it, and Lens connects by itself.` },
        phone: { title: 'Update Hypernova on this phone', sub: `${s.app ? `Hypernova ${s.app}` : 'Hypernova on this phone'} is older than this Lens (it signs calls but doesn’t seal them). Update it; Lens then connects by itself.`, tone: 'warn' } };
    }
    case 'old_lens': return both('Update Hypernova Lens', 'Hypernova on your phone needs a newer Lens. Update it in the Even app.', 'Hypernova on this phone needs a newer Lens. Update it in the Even app; it connects by itself after.', 'warn');
    case 'no_seal': return both('Lens can’t connect from this page', 'Open Lens from the Even app on this phone.', 'Every call to Hypernova is sealed with the browser’s own cryptography, which this page doesn’t have (it isn’t a secure context). Open Lens from the Even app.', 'warn');
    case 'asking': return {
      lens: { title: 'Allow it on your phone', sub: '', code: s.code },
      phone: { title: 'Allow it on your phone', sub: 'Hypernova asks “Allow Hypernova Lens to connect?” in a notification: pull it down to allow (or open Hypernova to answer). Allow it if it shows this code:', tone: 'wait', code: s.code },
    };
    case 'confirm': return {
      lens: { title: 'Allowed on your phone?', sub: 'Tap to connect, if you allowed it in Hypernova’s own prompt.', code: s.code },
      phone: { title: 'Allowed on your phone?', sub: 'Connect only if you allowed Lens in Hypernova’s own prompt or notification (check it’s from Hypernova), with this code. Or tap the glasses.', tone: 'wait', code: s.code },
    };
    case 'busy': {
      const secs = Math.max(1, Math.ceil((s.retryAt - now) / 1000));
      return both('Asked too often', `Asking your phone again in ${secs} s.`, `Lens asks your phone again in ${secs} s.`, 'wait');
    }
    case 'denied': return both('Not allowed on your phone', 'Tap to ask again.', 'Hypernova on this phone said no. Ask again if that was a mistake.', 'warn');
    case 'expired': return both('Not answered on your phone', 'Tap to ask again.', 'Nobody allowed Lens in Hypernova in time.', 'warn');
    case 'unconfirmed': return both('Not confirmed on the glasses', 'Tap to ask again.', 'Hypernova on this phone allowed Lens, but the glasses weren’t tapped (nor Connect here) within 10 minutes. Ask again.', 'warn');
    case 'revoked': return both('Disconnected on your phone', 'Or Hypernova isn’t running. Tap to connect again.', 'Hypernova on this phone doesn’t know Lens’s key: it was disconnected there, or Hypernova isn’t running and something else answers on its port. Connect again: if Hypernova is back, Lens reconnects by itself; if it was disconnected, Lens asks to be allowed once more.', 'warn');
    default: return null;
  }
}

/** What the lens says while it isn't connected through the app (or its own pairing). */
export function linkMessage(s: LinkState, now: number, direct: boolean): { title: string; sub: string; code?: string } | null {
  return linkWords(s, now, direct)?.lens ?? null;
}

/**
 * The Mac out of reach with nothing kept to show (no snapshot yet), in the same words on the lens and
 * the phone card: the app on this phone gone quiet is said as that (`problem` is the bridge's own
 * words then), not as the Mac.
 */
export function offlineWords(problem: string | null): { lens: { title: string; sub: string }; phone: { title: string; sub: string } } {
  if (problem === WORDS.no_app) return {
    lens: { title: 'Hypernova on your phone isn’t answering', sub: 'Open it, and Lens reconnects by itself.' },
    phone: { title: 'Hypernova on this phone isn’t answering', sub: 'Open Hypernova on this phone; Lens reconnects by itself.' },
  };
  return {
    lens: { title: 'Can’t reach your Mac', sub: problem ?? 'Is the Mac awake? Hypernova keeps trying.' },
    phone: { title: 'Hypernova can’t reach your Mac', sub: problem ?? 'Is the Mac awake? Hypernova on this phone keeps trying.' },
  };
}
