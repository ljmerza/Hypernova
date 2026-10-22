// The Hypernova app on this phone (docs/PROTOCOL.md, "The phone bridge"; docs/DECISIONS.md, "Lens
// talks only to the Hypernova app"). The Even App serves Lens from 127.0.0.1, and the Hypernova app
// listens on 127.0.0.1:47380: Lens asks it once for access (the owner allows it in the app), and
// from then on every call to the Mac goes to the app, which carries it over its own connection with
// the phone's own credentials. So Lens has no pairing, token or Mac address of its own, and one
// public build works for everyone.
//
// Any app on the phone could take that port before Hypernova does (after a reboot, say) and answer
// as if it were Hypernova, so nothing secret ever goes to whoever listens there:
// - Access is agreed, not handed over: Lens and the app each make an X25519 key for the ask and
//   both work out the same token from it; neither half of it travels. The six-digit code both
//   screens show is worked out by each side from the whole exchange (Lens commits to a nonce of its
//   own before it sees the app's), so something in between ends up with two different codes.
// - The token itself never travels either: each call is signed with a key made from it (HMAC with
//   SHA-256(token), all the app keeps), over the method, the path and query, the time, a nonce, the
//   body's hash and the listener's id (new each time the app opens its port), so a signed call is
//   good for that one call, with that body, to that one listener, for a minute.
// - And each call is sealed (data/seal.ts): its body and the whole answer (status, headers, body)
//   under a key of that one call's own, so whatever relays or reads the loopback connection sees
//   only ciphertext, can't change a byte undetected, and can't play it again; the same for every
//   WebSocket message. Before signing anything Lens has the listener prove it holds the same key
//   (the ping's challenge), again after a few quiet seconds; and only the holder of the key can
//   seal an answer, so an answer that opens is the app's (REPLY_HEADER marks one).
//
// The glasses' settings (how text arrives, pop-ups, read aloud…) live in the app now; Lens reads
// them here and keeps a copy as a cache (data/store.ts).
import type { Settings } from './store.ts';
import type { RequestSigner } from './api.ts';
import { DEFAULT_POPS, POP_KINDS, type PopSettings } from '../core/popups.ts';
import { PACE_MAX, PACE_MIN } from '../core/transmission.ts';
import { aesGcm, b64url, concat, fromB64url, hmacSha256, randomBytes, sameBytes, sha256, utf8, x25519, x25519Pair } from '../core/crypto.ts';
import { CallKey, openAnswer, sealBody } from './seal.ts';

export const BRIDGE_URL = 'http://127.0.0.1:47380';
/**
 * The bridge's protocol: 3 agrees keys, signs calls and seals them. An app that says less is one
 * to update (Lens says so, and signs nothing for it); an app that speaks 3 refuses a Lens before it.
 */
export const BRIDGE_PROTOCOL = 4;
/** Whether this page can seal calls at all (WebCrypto: a secure context, as the Even App's `127.0.0.1` is). */
export function canSeal(): boolean { return aesGcm() !== null; }
/**
 * How Lens fetches the app: the default cache mode, so the browser keeps the app's answer to its
 * preflight (two hours) and a call costs one connection, not two; the app says `no-store` on every
 * answer, so nothing else is kept.
 */
export const CACHE: RequestCache = 'default';
/**
 * How long the app's last proof (its ping's, or an answer's) stands: a call later than this has the
 * listener prove itself again first, so a call after a quiet spell never goes to whatever took the
 * port meanwhile (the app's process ended, and another app was quicker to it).
 */
export const PROOF_TTL_MS = 3_000;
/** The header every answer to a signed call carries, `sealed`: its body is the answer sealed under the call's key (data/seal.ts). */
export const REPLY_HEADER = 'X-Hypernova-Bridge';
export const REPLY_SEALED = 'sealed';
/** A sealed answer at most (what Lens reads of one before giving up): audio of a long reply is a few MB. */
export const MAX_ANSWER = 64 * 1024 * 1024;
/** An unsealed answer at most (the ping, the ask's answers, a refusal): a few hundred bytes each; more is nobody Lens listens to. */
export const MAX_UNSEALED = 16 * 1024;
/** How long a ping may take in all, its body included: a listener that trickles one never holds the signer. */
export const PING_MS = 1500;
/**
 * Lens's own words for what the app says without a seal (its refusals before any signature checked
 * out, the ping): never the answer's text, which whatever holds the port could have written to be
 * shown on the lens ("Approve code 482913 on your phone…").
 */
export const WORDS = {
  no_app: 'Hypernova on this phone isn’t answering. Open it.',
  bridge_access: 'Allow Hypernova Lens in Hypernova on your phone first.',
  update_lens: 'This Hypernova Lens is older than Hypernova on this phone. Update Lens.',
  update_app: 'Update Hypernova on your phone: this Lens needs a newer one.',
  /** A call that changes something got no answer the app sealed, and isn't sent again by itself (it may have reached the Mac). */
  not_sent: 'That didn’t reach Hypernova on this phone. Try again.',
} as const;
/** A version as the ping may say it, shown only when it's plainly one (`1.4.0`); else nothing. */
export const VERSION = /^\d{1,3}(\.\d{1,3}){2}$/;

/** What the app keeps for the glasses (GlassesSettings.kt), as it sends it. */
export interface BridgeSettings {
  readAloud: boolean;
  blip: boolean;
  textArrives: 'all' | 'speech';
  pace: number;
  popups: PopSettings;
  moving: boolean;
  eclipse: boolean;
  projects: string[] | null;
  language: string;
  /** Type on the lens (lens/look.ts): the glasses' built-in font (the default), or the app's own, drawn. */
  look: 'hypernova' | 'native';
  /** "Practise the gestures on the glasses" was pressed then (epoch ms; 0: never). */
  guideAsk: number;
  /**
   * What the phone's new-session sheet last started with (its memory): sessions started from the
   * glasses start on the same provider and model. Never a permission mode (Auto and Bypass only
   * from the phone). null: none said (an app from before it), so the Mac's defaults.
   */
  newSession: NewSessionChoice | null;
}

/** A new session's provider and model, as the phone last chose them (`model` null: the provider's default). */
export interface NewSessionChoice { providerId: string; model: string | null }

/**
 * The settings the app keeps; the rest (the guide's offer) stay Lens's own. Not the
 * voice and its speed either: through the app the phone plays the glasses' speech with the Mac's
 * voice and speed (the app's Settings → Voice → Read aloud), and Lens's own `voice` and `rate` stay
 * 'auto' and 1, which ask the Mac for that same setting when the glasses read by themselves.
 */
export const SHARED_KEYS = ['readAloud', 'blip', 'textArrives', 'pace', 'popups', 'moving', 'eclipse', 'projects', 'language', 'look'] as const;
type SharedKey = typeof SHARED_KEYS[number];

/** The app's own link to the Mac, for the phone's status card (`GET /bridge/status`). */
export interface BridgeStatus {
  paired: boolean;
  mac: string | null;
  link: 'connected' | 'connecting' | 'offline' | 'unpaired' | 'demo';
  route: 'local' | 'tailscale' | 'remote' | null;
  app: string;
}

/** What the app did about "Open Hypernova" (`POST /bridge/open`). */
export type OpenShown = 'app' | 'notification' | 'nothing';

/** The app's answer to its ping: its version, the protocol it speaks, and the id of the port it listens on now. */
export interface BridgePing {
  version: string;
  /** What it speaks (BRIDGE_PROTOCOL); less, and Lens asks for an update rather than talk to it. */
  protocol: number;
  /** New each time the app opens its port: signed calls name it, so one made for another listener is no good here. */
  id: string;
  /** HMAC(key, "hnb3-ping\n" + id + "\n" + n), when Lens asked with a key's id the app knows. */
  proof: string | null;
}

/** The app's answer to an ask: its half of the key agreement. */
export interface AccessAsk { id: string; key: string; nonce: string; expiresInSeconds: number }

export type AccessPoll =
  | { state: 'pending' }
  /** Allowed: `confirm` shows the app worked out the same key (HMAC(key, "hnb2-confirm\n" + id)). The settings come later, sealed (`GET /bridge/settings`). */
  | { state: 'allowed'; confirm: string }
  | { state: 'denied' }
  | { state: 'expired' }
  /** The app no longer knows this ask (it restarted, or many came since). */
  | { state: 'gone' };

// ------------------------------------------------------------------ the keys

const enc = (...parts: Array<string | Uint8Array>) => concat(...parts.map(p => (typeof p === 'string' ? utf8(p) : p)));

/** The key calls are signed with (SHA-256 of the token, all the app keeps of it) and its short public name. */
export function bridgeKey(token: string): { key: Uint8Array; kid: string } {
  const key = sha256(utf8(token));
  return { key, kid: b64url(sha256(enc('hnb2-kid\n', key))).slice(0, 16) };
}

/** Lens's half of an ask: its key pair, and a nonce it commits to now and shows only once it has the app's half. */
export interface PairingStart { priv: Uint8Array; pub: Uint8Array; nonce: Uint8Array; commit: string }

export function startPairing(random: (n: number) => Uint8Array = randomBytes): PairingStart {
  const { priv, pub } = x25519Pair(random);
  const nonce = random(16);
  return { priv, pub, nonce, commit: pairingCommit(nonce) };
}

export function pairingCommit(nonce: Uint8Array): string { return b64url(sha256(enc('hnb2-commit\n', nonce))); }

/**
 * The code both screens show: six digits from the whole exchange (both public keys, both nonces).
 * Something between Lens and the app has to agree a key with each, so the two sides see different
 * keys; and it must give Lens its half before it learns Lens's nonce, so it can't steer the codes
 * to match (one chance in a million).
 */
export function pairingCode(lensKey: Uint8Array, appKey: Uint8Array, lensNonce: Uint8Array, appNonce: Uint8Array): string {
  const h = sha256(enc('hnb2-code\n', lensKey, appKey, lensNonce, appNonce));
  const n = ((h[0] << 24) | (h[1] << 16) | (h[2] << 8) | h[3]) >>> 0;
  return String(n % 1_000_000).padStart(6, '0');
}

/** The token both sides work out from the agreed secret; it never travels. */
export function pairingToken(shared: Uint8Array, lensKey: Uint8Array, appKey: Uint8Array, lensNonce: Uint8Array, appNonce: Uint8Array): string {
  return 'hnb_' + b64url(hmacSha256(shared, enc('hnb2-token\n', lensKey, appKey, lensNonce, appNonce)));
}

/** Lens's ask finished with the app's half: the code to show and the token, or null when that half isn't a key. */
export function finishPairing(mine: PairingStart, ask: { key: string; nonce: string }): { code: string; token: string } | null {
  const appKey = fromB64url(ask.key), appNonce = fromB64url(ask.nonce);
  if (!appKey || appKey.length !== 32 || !appNonce || appNonce.length < 16 || appNonce.length > 64) return null;
  const shared = x25519(mine.priv, appKey);
  // A key of small order agrees on zero, which anyone knows.
  if (shared.every(b => b === 0)) return null;
  return { code: pairingCode(mine.pub, appKey, mine.nonce, appNonce), token: pairingToken(shared, mine.pub, appKey, mine.nonce, appNonce) };
}

const mac = (key: Uint8Array, text: string) => b64url(hmacSha256(key, utf8(text)));

export function confirmation(key: Uint8Array, requestId: string): string { return mac(key, `hnb2-confirm\n${requestId}`); }
export function pingProof(key: Uint8Array, listener: string, n: string): string { return mac(key, `hnb3-ping\n${listener}\n${n}`); }

/** A body as a signature names it: its SHA-256, base64url (of the body as sent: the sealed bytes). */
export function bodyHash(body: string | Uint8Array): string { return b64url(sha256(typeof body === 'string' ? utf8(body) : body)); }
/** What a call without a body signs. */
export const EMPTY_BODY = bodyHash('');

/** A query as signed: its parts as sent, less empty ones and the signature's own (`hnb`). */
export function canonicalQuery(raw: string): string {
  return raw.split('&').filter(p => {
    if (!p) return false;
    const k = p.split('=')[0];
    let key = k;
    try { key = decodeURIComponent(k); } catch { /* as it is */ }
    return key !== 'hnb';
  }).join('&');
}

/** What a call's signature covers. */
export function requestSignature(key: Uint8Array, listener: string, method: string, path: string, query: string, ts: string, nonce: string, body: string): string {
  return mac(key, `hnb3\n${listener}\n${method}\n${path}\n${query}\n${ts}\n${nonce}\n${body}`);
}

/** How long the app may ask Lens to wait (`retryAfterSeconds`), whatever it says: an hour at most. */
export const MAX_RETRY_AFTER_S = 3600;
export function clampRetryAfter(v: unknown): number {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? Math.min(MAX_RETRY_AFTER_S, Math.ceil(n)) : 0;
}

const sameText = (a: string | null | undefined, b: string) => typeof a === 'string' && sameBytes(utf8(a), utf8(b));

/** A refusal from the app: `bridge_access` (no token, or a revoked one), `too_many_requests`… */
export class BridgeError extends Error {
  readonly code: string;
  readonly status: number;
  readonly retryAfterSeconds: number;
  constructor(code: string, message: string, status = 0, retryAfterSeconds = 0) {
    super(message);
    this.name = 'BridgeError';
    this.code = code;
    this.status = status;
    this.retryAfterSeconds = clampRetryAfter(retryAfterSeconds);
  }
}

/** A "practise" ask's time at most: the year 2100 (an epoch ms that isn't one is no ask). */
export const MAX_GUIDE_ASK = 4_102_444_800_000;

/**
 * The app's settings, checked field by field: anything missing, of the wrong kind or out of range
 * takes Lens's default (a project name 80 characters, 64 of them; a language code; a look of the
 * two; a time that is one), so nothing in them can put Lens in a state it can't show.
 */
export function readBridgeSettings(raw: unknown): BridgeSettings | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  const bool = (k: string, d: boolean) => (typeof o[k] === 'boolean' ? o[k] as boolean : d);
  const pops = { ...DEFAULT_POPS };
  if (o.popups && typeof o.popups === 'object' && !Array.isArray(o.popups)) for (const k of POP_KINDS) { const v = (o.popups as Record<string, unknown>)[k]; if (typeof v === 'boolean') pops[k] = v; }
  const pace = typeof o.pace === 'number' && Number.isFinite(o.pace) ? Math.min(PACE_MAX, Math.max(PACE_MIN, Math.round(o.pace))) : null;
  const projects = Array.isArray(o.projects)
    ? o.projects.filter((p): p is string => typeof p === 'string' && p.trim() !== '').map(p => p.trim().slice(0, 80)).filter((p, i, all) => all.indexOf(p) === i).slice(0, 64)
    : null;
  return {
    readAloud: bool('readAloud', true),
    blip: bool('blip', true),
    textArrives: o.textArrives === 'speech' ? 'speech' : 'all',
    pace: pace ?? 170,
    popups: pops,
    moving: bool('moving', false),
    eclipse: bool('eclipse', false),
    projects,
    language: typeof o.language === 'string' && /^(auto|[a-z]{2,3})$/.test(o.language) ? o.language : 'en',
    look: o.look === 'hypernova' ? 'hypernova' : 'native',
    guideAsk: typeof o.guideAsk === 'number' && Number.isInteger(o.guideAsk) && o.guideAsk > 0 && o.guideAsk <= MAX_GUIDE_ASK ? o.guideAsk : 0,
    newSession: readNewSession(o.newSession),
  };
}

/** A provider's id as the Mac makes them ("anthropic", "openrouter", "p_3f9a…"), and a model's value, checked. */
const PROVIDER_ID = /^[A-Za-z0-9][\w.-]{0,63}$/;
const MODEL = /^[\x21-\x7e][\x20-\x7e]{0,199}$/;

/** The phone's last new-session choice, or null when it's missing or not one (then the Mac's defaults). */
export function readNewSession(raw: unknown): NewSessionChoice | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const o = raw as Record<string, unknown>;
  if (typeof o.providerId !== 'string' || !PROVIDER_ID.test(o.providerId)) return null;
  const model = typeof o.model === 'string' && MODEL.test(o.model.trim()) ? o.model.trim() : null;
  return { providerId: o.providerId, model };
}

/** The app's settings as a change to Lens's own. */
export function lensPatch(b: BridgeSettings): Partial<Settings> {
  return { readAloud: b.readAloud, blip: b.blip, textArrives: b.textArrives, pace: b.pace, popups: { ...b.popups }, moving: b.moving, eclipse: b.eclipse, projects: b.projects ? [...b.projects] : null, language: b.language, look: b.look,
    newSession: b.newSession ? { ...b.newSession } : null };
}

/** The part of a change made on the glasses ("mute", "moving", "eclipse") that the app keeps; null for none. */
export function sharedPatch(patch: Partial<Settings>): Partial<Pick<Settings, SharedKey>> | null {
  const out: Record<string, unknown> = {};
  for (const k of SHARED_KEYS) if (patch[k] !== undefined) out[k] = patch[k];
  return Object.keys(out).length ? out as Partial<Pick<Settings, SharedKey>> : null;
}

/** HTTP to the app on 127.0.0.1. `fetch` is looked up at each call, so tests can stand in for it. */
export class Bridge {
  readonly base: string;
  private fetchImpl: typeof fetch | null;

  constructor(base = BRIDGE_URL, fetchImpl: typeof fetch | null = null) {
    this.base = base.replace(/\/+$/, '');
    this.fetchImpl = fetchImpl;
  }

  /**
   * `signal`: the caller's own (a signed call keeps its deadline until the answer is read whole);
   * without one, the call has its own of `timeoutMs`.
   */
  private async call(method: string, path: string, o: { auth?: string; body?: unknown; bytes?: Uint8Array; timeoutMs?: number; signal?: AbortSignal } = {}): Promise<Response> {
    const ctrl = new AbortController();
    const timer = o.signal ? null : setTimeout(() => ctrl.abort(), o.timeoutMs ?? 6000);
    const headers: Record<string, string> = {};
    if (o.auth) headers.Authorization = o.auth;
    // `bytes`: a signed call's body, sealed (what its signature names).
    const text = o.body === undefined ? undefined : JSON.stringify(o.body);
    if (o.bytes) headers['Content-Type'] = 'application/octet-stream';
    else if (text !== undefined) headers['Content-Type'] = 'application/json';
    try {
      return await (this.fetchImpl ?? fetch)(this.base + path, { method, headers, body: o.bytes ? (o.bytes.slice().buffer as ArrayBuffer) : text, signal: o.signal ?? ctrl.signal, cache: CACHE });
    } catch {
      throw new BridgeError('no_app', WORDS.no_app);
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  /**
   * A call with `signer`'s signature, sealed, to a listener that proved itself; signed again once
   * when the app says the signature is no good (it opened its port again since: another listener
   * id). An answer that doesn't open under this call's key is no answer: something else holds the port.
   */
  private async signed(method: string, path: string, signer: BridgeSigner, o: { body?: unknown; timeoutMs?: number } = {}): Promise<Response> {
    const text = o.body === undefined ? undefined : JSON.stringify(o.body);
    for (let attempt = 0; ; attempt++) {
      await signer.ready();
      const p = await signer.prepare(method, this.base + path, text);
      // The deadline covers the whole answer, read and opened, not just its head.
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), o.timeoutMs ?? 6000);
      let res: Response;
      let theApps: Response | null;
      let code: string | null = null;
      try {
        res = await this.call(method, path, { bytes: p.body, auth: p.auth, signal: ctrl.signal });
        theApps = await signer.answered(res, p.call);
        // An unsealed refusal's code, read within the same deadline (a trickling one is no answer).
        if (!theApps) code = await refusal(res);
      } catch (err) {
        signer.stale();
        throw err;
      } finally {
        clearTimeout(timer);
      }
      if (theApps) return theApps;
      // The app's own refusals of a signature come unsealed (it has no key to seal them with), so
      // only their code is taken, never their words (Lens has its own): no access, or a Lens older
      // than the app, is said as such. A signature for another listener (401) is made afresh once,
      // only for a GET, and only when the listener proves to be another one than this call was
      // signed for: a call that changes something (PUT, DELETE) may have reached the Mac already
      // (a relay that passed it on and answered 401 itself, the port reopening meanwhile), so it's
      // never sent again by itself: Lens says it didn't reach the app, and the owner tries again.
      if (res.status === 403 && (code === 'bridge_access' || code === 'update_lens')) throw new BridgeError(code, WORDS[code], 403);
      signer.stale();
      if (res.status === 401 && code === 'bridge_signature') {
        if (method === 'GET' && attempt === 0 && await signer.reproved(p.call)) continue;
        if (method !== 'GET') throw new BridgeError('not_sent', WORDS.not_sent);
      }
      throw new BridgeError('no_app', WORDS.no_app);
    }
  }

  /**
   * An unsealed call (the ask, its nonce, its poll) and its answer as JSON, read whole within
   * `timeoutMs` and MAX_UNSEALED: a refusal's code and `retryAfterSeconds` are taken, never its
   * words (Lens has its own: WORDS, by code).
   */
  private async json<T>(method: string, path: string, o: { body?: unknown; timeoutMs?: number } = {}): Promise<{ status: number; body: T }> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), o.timeoutMs ?? 6000);
    try {
      const res = await this.call(method, path, { body: o.body, signal: ctrl.signal });
      const bytes = await readCapped(res, MAX_UNSEALED);
      if (!bytes) throw new BridgeError('no_app', WORDS.no_app);
      let body: Record<string, any> = {};
      try { body = JSON.parse(new TextDecoder().decode(bytes)); } catch { /* not JSON */ }
      if (res.ok) return { status: res.status, body: (body ?? {}) as T };
      const e = body?.error ?? {};
      const code = typeof e.code === 'string' && /^[a-z_]{1,40}$/.test(e.code) ? e.code : 'bridge_error';
      throw new BridgeError(code, (WORDS as Record<string, string>)[code] ?? 'Hypernova on this phone refused that.', res.status, Number(e.retryAfterSeconds) || 0);
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * Is the app here? Its version, protocol and listener id when it is; null when nothing answers,
   * or something else does. With `challenge` (a key's id and a fresh nonce) the app also proves it
   * holds that key, when it does (`proof`); Lens checks that before it signs anything for it. An
   * app that speaks less than BRIDGE_PROTOCOL answers too (`protocol`): Lens then asks for an update.
   */
  async ping(timeoutMs = PING_MS, challenge?: { kid: string; n: string }): Promise<BridgePing | null> {
    try {
      const q = challenge ? `?kid=${encodeURIComponent(challenge.kid)}&n=${encodeURIComponent(challenge.n)}` : '';
      // Head and body within `timeoutMs`, and a few KB at most: a trickling or endless ping is no app.
      const { status, body: j } = await this.json<{ app?: string; version?: string; bridge?: number; id?: unknown; proof?: unknown }>('GET', '/bridge/ping' + q, { timeoutMs });
      if (status !== 200) return null;
      if (j?.app !== 'hypernova' || !(Number(j.bridge) >= 1) || typeof j.id !== 'string' || !j.id) return null;
      // Unsigned, like `bridge`: shown only when it's plainly a version, never as words.
      const version = typeof j.version === 'string' && VERSION.test(j.version) ? j.version : '';
      return { version, protocol: Number(j.bridge), id: j.id, proof: typeof j.proof === 'string' ? j.proof : null };
    } catch { return null; }
  }

  /** "May I connect?" with Lens's half of the key agreement; the app answers with its own. */
  async ask(name: string, version: string, key: string, commit: string): Promise<AccessAsk> {
    // `bridge`: the protocol this Lens speaks; an app that speaks a newer one refuses an older ask (update_lens).
    const { body: r } = await this.json<Partial<AccessAsk>>('POST', '/bridge/request', { body: { name, version, key, commit, bridge: BRIDGE_PROTOCOL } });
    if (!r.id || typeof r.key !== 'string' || typeof r.nonce !== 'string') throw new BridgeError('bridge_error', 'Hypernova on this phone didn’t take the request.');
    return { id: r.id, key: r.key, nonce: r.nonce, expiresInSeconds: Number(r.expiresInSeconds) || 120 };
  }

  /** Lens's nonce, now that it has the app's half: the app works out the code and asks its owner. */
  async reveal(id: string, nonce: string): Promise<void> {
    await this.json('POST', `/bridge/request/${encodeURIComponent(id)}`, { body: { nonce }, timeoutMs: 5000 });
  }

  async poll(id: string): Promise<AccessPoll> {
    let r: { state?: string; confirm?: string };
    try {
      r = (await this.json<{ state?: string; confirm?: string }>('GET', `/bridge/request/${encodeURIComponent(id)}`, { timeoutMs: 5000 })).body;
    } catch (err) {
      if (err instanceof BridgeError && err.status === 404) return { state: 'gone' };
      throw err;
    }
    switch (r.state) {
      // The settings aren't here (the poll is unsealed): they come sealed once the owner confirmed.
      case 'allowed': return typeof r.confirm === 'string' ? { state: 'allowed', confirm: r.confirm } : { state: 'gone' };
      case 'pending': case 'denied': case 'expired': return { state: r.state };
      default: return { state: 'gone' };
    }
  }

  /** A sealed answer (opened by `signed`: the app's own word, whole) as JSON; its refusal, with the app's words, as a BridgeError. */
  private async opened<T>(res: Response): Promise<T> {
    let body: Record<string, any> = {};
    try { body = await res.json(); } catch { /* not JSON */ }
    if (res.ok) return body as T;
    const e = body?.error ?? {};
    throw new BridgeError(String(e.code ?? 'bridge_error'), String(e.message ?? `Hypernova on this phone answered ${res.status}.`), res.status, Number(e.retryAfterSeconds) || 0);
  }

  async status(signer: BridgeSigner): Promise<BridgeStatus> {
    return this.opened<BridgeStatus>(await this.signed('GET', '/bridge/status', signer, { timeoutMs: 4000 }));
  }

  /** The glasses' settings as the app keeps them; a 403 `bridge_access` says the key is no good. */
  async settings(signer: BridgeSigner): Promise<BridgeSettings> {
    const r = await this.opened<{ settings?: unknown }>(await this.signed('GET', '/bridge/settings', signer, { timeoutMs: 4000 }));
    return readBridgeSettings(r.settings) ?? readBridgeSettings({})!;
  }

  async putSettings(signer: BridgeSigner, patch: Partial<BridgeSettings>): Promise<BridgeSettings | null> {
    const r = await this.opened<{ settings?: unknown }>(await this.signed('PUT', '/bridge/settings', signer, { body: { settings: patch }, timeoutMs: 4000 }));
    return readBridgeSettings(r.settings);
  }

  /**
   * "Open Hypernova": the Even App's web view hands no link of another app's to Android (nor has its
   * SDK a way to open one), so Lens asks the app to open itself at the glasses' settings. `shown` is
   * how: `app` (it was on screen, and went there), `notification` ("Tap to open Hypernova" is up), or
   * `nothing` (its notifications are off). Throws when it can't ask (no access, no app, an app
   * from before this call: a 404 that isn't sealed).
   */
  async open(signer: BridgeSigner, screen: 'glasses' = 'glasses'): Promise<OpenShown> {
    const r = await this.opened<{ shown?: unknown }>(await this.signed('POST', '/bridge/open', signer, { body: { screen }, timeoutMs: 4000 }));
    return r.shown === 'app' || r.shown === 'notification' ? r.shown : 'nothing';
  }

  /** Lens lets go of its access (Disconnect on the phone's status card). */
  async leave(signer: BridgeSigner): Promise<void> {
    try { await this.opened(await this.signed('DELETE', '/bridge/access', signer, { timeoutMs: 4000 })); } catch { /* gone already */ }
  }
}

/** A call ready to go: its signature, its body sealed (when it has one), and the key its answer opens with. */
export interface PreparedCall { auth: string; body?: Uint8Array; call: CallKey }

/** An unsealed refusal's `error.code` (a few KB read at most), and nothing else of it; null when it has none. */
export async function refusal(res: Response): Promise<string | null> {
  try {
    const bytes = await readCapped(res, MAX_UNSEALED);
    if (!bytes) return null;
    const j = JSON.parse(new TextDecoder().decode(bytes)) as { error?: { code?: unknown } };
    return typeof j?.error?.code === 'string' ? j.error.code : null;
  } catch { return null; }
}

/** The body of `res`, whole, or null once it passes `max` bytes (the rest is left unread) or can't be read. */
export async function readCapped(res: Response, max: number): Promise<Uint8Array | null> {
  const declared = Number(res.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > max) { try { await res.body?.cancel(); } catch { /* gone */ } return null; }
  const reader = res.body?.getReader();
  if (!reader) {
    try { const b = new Uint8Array(await res.arrayBuffer()); return b.length > max ? null : b; } catch { return null; }
  }
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > max) { try { await reader.cancel(); } catch { /* gone */ } return null; }
      parts.push(value);
    }
  } catch { return null; }
  return concat(...parts);
}

/**
 * Signs and seals Lens's calls through the app with the key made from its token, for a listener
 * that proved it holds that key. A listener that may have changed (a call failed, the live feed
 * closed: the app may have gone, and another app may have the port now) is asked to prove it again
 * before the next call; one that can't gets nothing. An answer counts only when it opens under the
 * key of the call it answers, for the listener that call was signed for.
 */
export class BridgeSigner implements RequestSigner {
  readonly token: string;
  readonly kid: string;
  private key: Uint8Array;
  private bridge: Bridge;
  private now: () => number;
  /** The proven listener's id; null until proven, and again once it may have changed. */
  private listener: string | null = null;
  /** When the listener last proved itself (its ping, or an answer that opened). */
  private provenAt = 0;
  private proving: Promise<{ app: BridgePing | null; proven: boolean }> | null = null;

  constructor(bridge: Bridge, token: string, now: () => number = Date.now) {
    this.bridge = bridge;
    this.token = token;
    this.now = now;
    const k = bridgeKey(token);
    this.key = k.key;
    this.kid = k.kid;
  }

  get proven(): boolean { return this.listener !== null; }

  /** Pings with a fresh challenge: the app answering, and whether it proved it holds this key (an app that speaks less than BRIDGE_PROTOCOL never does). */
  prove(): Promise<{ app: BridgePing | null; proven: boolean }> {
    this.proving ??= (async () => {
      const n = b64url(randomBytes(16));
      // The ping has a deadline of its own (head and body); this is the belt to its braces: a shared
      // promise that never settled would hold every call behind it.
      const app = await Promise.race([
        this.bridge.ping(PING_MS, { kid: this.kid, n }),
        new Promise<null>(r => setTimeout(() => r(null), PING_MS * 2)),
      ]);
      const proven = !!app && app.protocol >= BRIDGE_PROTOCOL && sameText(app.proof, pingProof(this.key, app.id, n));
      this.listener = proven ? app!.id : null;
      if (proven) this.provenAt = this.now();
      return { app, proven };
    })().finally(() => { this.proving = null; });
    return this.proving;
  }

  /**
   * The listener proved itself lately, or proves itself now (`fresh`: now, whatever came lately, as
   * before a socket opens); throws (`no_app`, `bridge_access`) when it can't.
   */
  async ready(fresh = false): Promise<void> {
    if (!fresh && this.listener && this.now() - this.provenAt < PROOF_TTL_MS) return;
    const r = await this.prove();
    if (r.proven) return;
    if (!r.app) throw new BridgeError('no_app', WORDS.no_app);
    if (r.app.protocol < BRIDGE_PROTOCOL) throw new BridgeError('update_app', WORDS.update_app);
    throw new BridgeError('bridge_access', WORDS.bridge_access, 403);
  }

  stale(): void { this.listener = null; }

  /**
   * After an unsealed 401 to `call`: the listener proves itself again, and whether it's another
   * one than `call` was signed for (the app opened its port again: the call never reached it, and
   * may be made afresh). The same listener means the 401 wasn't the app's (a relay's, after passing
   * the call on): nothing is sent again.
   */
  async reproved(call: CallKey): Promise<boolean> {
    this.stale();
    const r = await this.prove();
    return r.proven && r.app!.id !== call.listener;
  }

  /**
   * `res`, the answer to the call made for `call`, when it opens under that call's key (sealed by
   * the app, for this listener and nonce; its status and headers inside): the answer as the app
   * gave it. Null when it doesn't: it isn't the app's, or something changed it on the way. One that
   * does counts as the listener it was signed for proving itself again.
   */
  async answered(res: Response, call: CallKey): Promise<Response | null> {
    if (res.headers.get(REPLY_HEADER) !== REPLY_SEALED) return null;
    const bytes = await readCapped(res, MAX_ANSWER);
    if (!bytes) return null;
    let a: Awaited<ReturnType<typeof openAnswer>>;
    try { a = await openAnswer(call, bytes); } catch { return null; }
    if (a.status < 200) return null;
    if (this.listener === call.listener) this.provenAt = this.now();
    const empty = a.status === 204 || a.status === 205 || a.status === 304;
    return new Response(empty ? null : (a.body.slice().buffer as ArrayBuffer), { status: a.status, headers: a.headers });
  }

  /** The call's key, for the listener that proved itself and a fresh nonce; throws when none has. */
  private async callKey(): Promise<{ call: CallKey; ts: string }> {
    const listener = this.listener;
    if (!listener) throw new BridgeError('no_app', WORDS.no_app);
    return { call: await CallKey.derive(this.key, listener, b64url(randomBytes(16))), ts: String(Math.round(this.now())) };
  }

  private signature(call: CallKey, ts: string, method: string, url: string, hash: string): string {
    const u = new URL(url);
    const sig = requestSignature(this.key, call.listener, method.toUpperCase(), u.pathname, canonicalQuery(u.search.slice(1)), ts, call.nonce, hash);
    return `${this.kid}.${ts}.${call.nonce}.${hash}.${sig}`;
  }

  /**
   * `method` to `url` (as the page sends it) with `body` (the JSON as Lens would have sent it),
   * ready to go: the body sealed under a key of this call's own, and the signature over it
   * (`HNB3 kid.time.nonce.body.mac`, `body` the sealed bytes' hash).
   */
  async prepare(method: string, url: string, body?: string): Promise<PreparedCall> {
    const { call, ts } = await this.callKey();
    const sealed = body === undefined ? undefined : await sealBody(call, utf8(body));
    const hash = sealed ? bodyHash(sealed) : EMPTY_BODY;
    return { auth: `HNB3 ${this.signature(call, ts, method, url, hash)}`, body: sealed, call };
  }

  /** A WebSocket's address on the app (`/v1/ws`, `/v1/stt/ws`), signed in its query (a page can't set its headers), and the key its messages are sealed with. */
  async socket(path: string): Promise<{ url: string; call: CallKey }> {
    const { call, ts } = await this.callKey();
    const u = new URL(this.bridge.base + path);
    const signature = this.signature(call, ts, 'GET', u.toString(), EMPTY_BODY);
    return { url: `${u.protocol === 'https:' ? 'wss' : 'ws'}://${u.host}${u.pathname}${u.search ? u.search + '&' : '?'}hnb=${signature}`, call };
  }

  /** Whether the app's word that it allowed this ask is from one that worked out the same key. */
  static confirms(token: string, requestId: string, confirm: string): boolean {
    return sameText(confirm, confirmation(bridgeKey(token).key, requestId));
  }
}
