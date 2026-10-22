// HTTP to the Mac's Hypernova daemon, as the Android app does it (data/Api.kt): bearer token, short
// timeouts, and failures told apart so the phone can say what's wrong in plain words. Through the
// Hypernova app on this phone, each call is signed and sealed instead (data/bridge.ts).
import type { CallKey } from './seal.ts';
import { CACHE, WORDS, refusal } from './bridge.ts';

export type Problem =
  /** Nothing answered: the Mac is asleep, offline, or Tailscale is off on one side. */
  | 'unreachable'
  /** The Mac answered but refused a page inside the Even App: its Hypernova predates Lens (no CORS). */
  | 'update_mac'
  /** The token was rejected: this phone was unpaired on the Mac. */
  | 'unauthorized'
  /** The pairing code expired or was already used. */
  | 'code_expired'
  /** The daemon doesn't have this endpoint yet (DAEMON.md lists what Lens asks for). */
  | 'missing'
  /**
   * Through the Hypernova app on this phone: it no longer lets Lens in (Disconnect on the phone, or
   * the Mac forgot the glasses). Lens asks again when told to (data/link.ts).
   */
  | 'revoked'
  /** The daemon answered with an error. */
  | 'server'
  | 'timeout';

export class ApiError extends Error {
  readonly problem: Problem;
  readonly status: number;
  readonly code: string | null;
  readonly extra: Record<string, unknown>;
  constructor(problem: Problem, message: string, status = 0, code: string | null = null, extra: Record<string, unknown> = {}) {
    super(message);
    this.name = 'ApiError';
    this.problem = problem;
    this.status = status;
    this.code = code;
    this.extra = extra;
  }
}

export interface ApiOptions {
  timeoutMs?: number;
  signal?: AbortSignal;
}

/**
 * Signs and seals calls through the Hypernova app on this phone (data/bridge.ts, BridgeSigner):
 * the bridge token never travels, each call carries a signature made with a key from it instead,
 * and its body and answer are sealed under a key of that call's own (data/seal.ts).
 */
export interface RequestSigner {
  /**
   * A listener that proved it holds the key, proving it again when it may have changed or after a
   * quiet spell (`fresh`: now); throws (`code`: `no_app`, `bridge_access`, `update_app`) otherwise.
   */
  ready(fresh?: boolean): Promise<void>;
  /** `method` to `url` with `body` (the JSON as it would be sent), ready: the `Authorization` header, the body sealed, the call's key. */
  prepare(method: string, url: string, body?: string): Promise<{ auth: string; body?: Uint8Array; call: CallKey }>;
  /** A WebSocket's address on the app for `path`, signed in its query, and the key its messages are sealed with. */
  socket(path: string): Promise<{ url: string; call: CallKey }>;
  /** The app may have changed (a call failed, a socket closed): it proves itself again before the next call. */
  stale(): void;
  /** After an unsealed 401 to `call`: the listener proved again, and it's another one than the call was signed for (only then is the call made afresh). */
  reproved(call: CallKey): Promise<boolean>;
  /** The answer to the call made for `call` when it opens under that call's key (the app's, whole); else null: it isn't the app's, or was changed on the way. */
  answered(res: Response, call: CallKey): Promise<Response | null>;
}

export class Api {
  readonly base: string;
  readonly token: string | null;
  /** 'bridge': the Hypernova app on this phone carries the calls (data/bridge.ts); 'mac': straight to the Mac. */
  readonly via: 'mac' | 'bridge';
  /** Through the app: what signs each call (the token is then null and never sent). */
  readonly signer: RequestSigner | null;

  constructor(base: string, token: string | null, via: 'mac' | 'bridge' = 'mac', signer: RequestSigner | null = null) {
    this.base = base.replace(/\/+$/, '');
    this.token = signer ? null : token;
    this.via = via;
    this.signer = signer;
  }

  get<T>(path: string, o?: ApiOptions): Promise<T> { return this.json<T>('GET', path, undefined, o); }
  post<T>(path: string, body: unknown = {}, o?: ApiOptions): Promise<T> { return this.json<T>('POST', path, body, o); }
  del<T>(path: string, o?: ApiOptions): Promise<T> { return this.json<T>('DELETE', path, undefined, o); }

  /** The raw response (audio, images), after the same error handling. */
  async fetch(method: string, path: string, body?: unknown, o: ApiOptions = {}): Promise<Response> {
    let res: Response;
    let code: string | null, message: string, extra: Record<string, unknown>;
    for (let attempt = 0; ; attempt++) {
      // The deadline covers the whole answer, read and opened, not just its head.
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), o.timeoutMs ?? 12_000);
      o.signal?.addEventListener('abort', () => ctrl.abort());
      let sent: { res: Response; call: CallKey | null };
      let proven: Response | null;
      let refused: string | null = null;
      try {
        sent = await this.send(method, path, body, o, ctrl);
        res = sent.res;
        // Through the app: an answer is the app's only when it opens under this very call's key.
        proven = this.signer ? (sent.call ? await this.signer.answered(res, sent.call) : null) : res;
        // An answer that didn't open (the app's own refusals come so: it has no key to seal them
        // with) is taken by its code alone, read within the same deadline and a few KB at most,
        // never its words: Lens has its own for each, and a relay's words on the lens ("approve
        // code … on your phone") mustn't be possible.
        if (this.signer && !proven) refused = await refusal(res);
      } finally {
        clearTimeout(timer);
      }
      const theApps = !!proven;
      if (proven) res = proven;
      if (res.ok && theApps) return res;
      if (this.signer && !theApps) {
        // No access here: said as such.
        if (res.status === 403 && refused === 'bridge_access') throw new ApiError('revoked', 'Disconnected on your phone.', 403, refused);
        // A Lens older than the app: said as such.
        if (res.status === 403 && refused === 'update_lens') throw new ApiError('server', WORDS.update_lens, 403, refused);
        if (res.status === 401 && refused === 'bridge_signature') {
          // A signature for a listener that's gone (the app opened its port again): a GET is proved
          // again and, only when it's a different listener, made afresh once. The same listener means
          // the 401 wasn't the app's: a relay that passed the call on and answered itself. A call that
          // changes something (POST, PUT, DELETE) is never sent again by itself, whoever the listener
          // is now: a relay's 401 and the port reopening meanwhile would have the Mac do it twice.
          // Lens says it didn't reach the app, and the owner tries again.
          if (method === 'GET' && attempt === 0 && sent.call && await this.signer.reproved(sent.call)) continue;
          if (method !== 'GET') { this.signer.stale(); throw new ApiError('unreachable', WORDS.not_sent, 0, 'not_sent'); }
        }
        // Anything else that doesn't open isn't the app's answer: something else holds its port.
        this.signer.stale();
        throw new ApiError('unreachable', WORDS.no_app);
      }
      code = null; message = `The Mac answered ${res.status}.`; extra = {};
      try {
        const j = await res.json() as { error?: { code?: string; message?: string } & Record<string, unknown> };
        if (j?.error) { code = j.error.code ?? null; message = j.error.message ?? message; extra = j.error; }
      } catch { /* not JSON */ }
      break;
    }
    // Through the app: it took Lens's access back, or the Mac forgot these glasses.
    if (this.via === 'bridge' && res.status === 403 && (code === 'bridge_access' || code === 'glasses_removed')) throw new ApiError('revoked', message, 403, code);
    if (res.status === 401) throw new ApiError('unauthorized', this.via === 'bridge' ? 'Hypernova on this phone isn’t paired with your Mac any more. Pair it again there.' : 'This phone isn’t paired with the Mac any more. Pair again.', 401, code);
    if (res.status === 404 && code === 'not_found') throw new ApiError('missing', 'The Mac’s Hypernova doesn’t do this yet.', 404, code);
    throw new ApiError('server', message, res.status, code, extra);
  }

  /** One try: signed and sealed through the app (once it proved itself), else with the token. `ctrl`: the call's deadline (the caller's, which outlives this). */
  private async send(method: string, path: string, body: unknown, o: ApiOptions, ctrl: AbortController): Promise<{ res: Response; call: CallKey | null }> {
    const headers: Record<string, string> = {};
    {
      const text = body === undefined ? undefined : JSON.stringify(body);
      let payload: BodyInit | undefined = text;
      let call: CallKey | null = null;
      if (this.signer) {
        try {
          await this.signer.ready();
          // The body sealed under this call's key: what goes out, and what the signature names.
          const p = await this.signer.prepare(method, this.base + path, text);
          headers.Authorization = p.auth;
          call = p.call;
          payload = p.body ? (p.body.slice().buffer as ArrayBuffer) : undefined;
          if (p.body) headers['Content-Type'] = 'application/octet-stream';
        } catch (err) {
          const code = (err as { code?: string }).code;
          if (code === 'bridge_access') throw new ApiError('revoked', 'Disconnected on your phone.', 403, 'bridge_access');
          if (code === 'update_app') throw new ApiError('server', WORDS.update_app, 0, 'update_app');
          throw new ApiError('unreachable', WORDS.no_app);
        }
      } else {
        if (this.token) headers.Authorization = `Bearer ${this.token}`;
        if (text !== undefined) headers['Content-Type'] = 'application/json';
      }
      try {
        const res = await fetch(this.base + path, { method, headers, body: payload, signal: ctrl.signal, cache: this.signer ? CACHE : 'no-store' });
        return { res, call };
      } catch (err) {
        // The app may have gone, and something else may answer on its port next: it proves itself first.
        this.signer?.stale();
        if (ctrl.signal.aborted && !o.signal?.aborted) throw new ApiError('timeout', 'The Mac didn’t answer in time.');
        if (o.signal?.aborted) throw err;
        throw await this.explain();
      }
    }
  }

  private async json<T>(method: string, path: string, body?: unknown, o?: ApiOptions): Promise<T> {
    const res = await this.fetch(method, path, body, o);
    const text = await res.text();
    return (text ? JSON.parse(text) : {}) as T;
  }

  /**
   * A request that never got an answer: either nothing is there, or the Mac answered and the
   * WebView refused to read it (no CORS headers, which a daemon older than Lens doesn't send). An
   * opaque request tells them apart: it succeeds whenever the Mac is reachable at all.
   */
  private async explain(): Promise<ApiError> {
    // The app on this phone answers whatever the Mac does, so nothing at all means the app isn't running.
    if (this.via === 'bridge') return new ApiError('unreachable', 'Hypernova on this phone isn’t answering. Open it.');
    try {
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 5000);
      await fetch(this.base + '/v1/health', { mode: 'no-cors', signal: ctrl.signal, cache: 'no-store' });
      clearTimeout(t);
      return new ApiError('update_mac', 'Your Mac answered, but its Hypernova is older than Lens. Update Hypernova on the Mac.');
    } catch {
      return new ApiError('unreachable', 'Can’t reach your Mac. Is it awake, with Tailscale on here and there?');
    }
  }
}

/** Human words for any failure. */
export function explain(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof Error) return err.message;
  return String(err);
}
