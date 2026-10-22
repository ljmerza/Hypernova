// Lens through the Hypernova app on the same phone (data/bridge.ts, data/link.ts; PROTOCOL.md, "The
// phone bridge"): finding the app, agreeing access with it ("Approve on your phone: Hypernova"),
// signing every call so the token never travels, what an app squatting on the port gets (nothing it
// can use), the fallback to Lens's own pairing, the glasses' settings kept in the app, and the hub
// through it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import nc from 'node:crypto';
import {
  Bridge, BRIDGE_URL, BridgeSigner, EMPTY_BODY, MAX_UNSEALED, PING_MS, WORDS, bodyHash, bridgeKey, canonicalQuery, clampRetryAfter, confirmation, finishPairing, lensPatch, pingProof,
  readBridgeSettings, readCapped, readNewSession, requestSignature, sharedPatch, startPairing, type BridgeSettings, type PreparedCall,
} from '../src/data/bridge.ts';
import { CallKey, END, FROM_APP, FROM_LENS, TEXT, callKeyBytes, endPayload, openAnswer, openFrame, openStream, sealBody, sealFrame, sealedSocketClass } from '../src/data/seal.ts';
import { b64url, fromB64url, hmacSha256, sha256, utf8, x25519 } from '../src/core/crypto.ts';
import { ab, bodyBytes, nanswer, ncallKey, nend, nframe, nopen, nopenFrame, nopenStream, nrecord, sealedResponse } from './seal-helpers.ts';
import { Api } from '../src/data/api.ts';
import { PhoneLink, linkMessage, type LinkDeps } from '../src/data/link.ts';
import { LiveHub, chosen, type HubEvent } from '../src/data/hub.ts';
import { Store } from '../src/data/store.ts';
import { cardWords, openWords } from '../src/phone/status-words.ts';
import type { HubLike } from '../src/data/hub.ts';
import type { Snapshot } from '../src/core/types.ts';

// ------------------------------------------------------------------ a Hypernova app in memory

// The app's side, worked out with node:crypto rather than Lens's own code, so the two can't agree
// by sharing a mistake.
const b64u = (b: Uint8Array) => Buffer.from(b).toString('base64url');
const unb64u = (s: string) => Buffer.from(s, 'base64url');
const nsha = (...parts: Array<string | Uint8Array>) => { const h = nc.createHash('sha256'); for (const p of parts) h.update(p); return h.digest(); };
const nmac = (key: Uint8Array, ...parts: Array<string | Uint8Array>) => { const h = nc.createHmac('sha256', key); for (const p of parts) h.update(p); return h.digest(); };
const latin1 = (b: Uint8Array) => Buffer.from(b).toString('latin1');
const kidOf = (key: Uint8Array) => b64u(nsha('hnb2-kid\n', key)).slice(0, 16);
function appKeys() {
  const { privateKey, publicKey } = nc.generateKeyPairSync('x25519');
  return { privateKey, pub: unb64u(publicKey.export({ format: 'jwk' }).x!) };
}
const agree = (privateKey: nc.KeyObject, peer: Uint8Array) =>
  nc.diffieHellman({ privateKey, publicKey: nc.createPublicKey({ key: { kty: 'OKP', crv: 'X25519', x: b64u(peer) }, format: 'jwk' }) });
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

type Answer = 'pending' | 'allow' | 'deny' | 'expire';

/** The app's side of the bridge, as LensBridge.kt answers it: enough to drive Lens through every turn. */
class FakeApp {
  up = true;
  answer: Answer = 'pending';
  tooMany = 0;
  /** The id of the port it listens on: new each time it opens it. */
  listener = 'listener-1';
  clock: () => number = Date.now;
  /** The allowed Lenses' keys (SHA-256 of their token), by key id: all the app keeps. */
  keys = new Map<string, Buffer>();
  asked: Array<{ name: string; version: string }> = [];
  /** The code the app showed its owner for each ask Lens finished. */
  codes: string[] = [];
  puts: unknown[] = [];
  polls = 0;
  /** Every request as it arrived (bodies as bytes): all that Lens ever sent to whoever listens on the port. */
  wire: string[] = [];
  /** Every answer's body as it went out: all that whoever relays the port could read. */
  answers: string[] = [];
  /** Signed calls' bodies, opened (what Lens meant to send). */
  opened: string[] = [];
  /** Every POST to the Mac the app carried: what Lens made happen, and how many times. */
  executed: string[] = [];
  /** Pictures asked for by name. */
  pictures: string[] = [];
  /** Screens Lens asked the app to open, and how it says it did (`POST /bridge/open`); null: an app from before that call. */
  opens: string[] = [];
  openAnswer: 'app' | 'notification' | 'nothing' | null = 'notification';
  private nonces = new Set<string>();
  settings: Record<string, unknown> = { v: 1, readAloud: false, blip: true, textArrives: 'speech', pace: 200, popups: { needs: true, done: true }, moving: false, eclipse: true, projects: ['hypernova'], language: 'ru', look: 'hypernova', guideAsk: 0 };
  private n = 0;
  private req = new Map<string, { A: Buffer; B: Buffer; nb: Buffer; commit: string; z: Buffer; key?: Buffer }>();

  /** The app knows `token`'s key, as if it had allowed it before. */
  allow(token: string): void { const key = nsha(token); this.keys.set(kidOf(key), key); }

  fetch = (async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    if (!this.up) throw new TypeError('Failed to fetch');
    const url = new URL(String(input));
    assert.equal(url.origin, BRIDGE_URL);
    const method = init?.method ?? 'GET';
    const headers = new Headers(init?.headers);
    const raw = bodyBytes(init?.body);
    const body = latin1(raw);
    this.wire.push(`${method} ${url.pathname}${url.search} ${JSON.stringify([...headers])} ${body}`);
    const p = url.pathname;
    if (p === '/bridge/ping') {
      const kid = url.searchParams.get('kid'), n = url.searchParams.get('n');
      const key = kid ? this.keys.get(kid) : undefined;
      return json(200, { ok: true, app: 'hypernova', version: '1.4.0', bridge: 4, id: this.listener, ...(key && n ? { proof: b64u(nmac(key, `hnb3-ping\n${this.listener}\n${n}`)) } : {}) });
    }
    if (p === '/bridge/request' && method === 'POST') {
      if (this.tooMany) return json(429, { error: { code: 'too_many_requests', message: 'Too many.', retryAfterSeconds: this.tooMany } });
      const b = JSON.parse(body);
      // A Lens of an older protocol (no `bridge`, or less than the app's) is told to update before anyone is asked.
      if (!(Number(b.bridge) >= 4)) return json(400, { error: { code: 'update_lens', message: 'Update Lens.' } });
      this.asked.push({ name: b.name, version: b.version });
      const id = `req-${++this.n}-abcdefgh`;
      const mine = appKeys(), nb = nc.randomBytes(16), A = unb64u(b.key);
      this.req.set(id, { A, B: mine.pub, nb, commit: b.commit, z: agree(mine.privateKey, A) });
      return json(202, { id, key: b64u(mine.pub), nonce: b64u(nb), expiresInSeconds: 120 });
    }
    const one = /^\/bridge\/request\/(.+)$/.exec(p);
    if (one) {
      const r = this.req.get(one[1]);
      if (!r) return json(404, { error: { code: 'no_request', message: 'Ask again.' } });
      if (method === 'POST') {
        const na = unb64u(JSON.parse(body).nonce);
        if (b64u(nsha('hnb2-commit\n', na)) !== r.commit) return json(400, { error: { code: 'bad_commit', message: 'Ask again.' } });
        this.codes.push(String(nsha('hnb2-code\n', r.A, r.B, na, r.nb).readUInt32BE(0) % 1_000_000).padStart(6, '0'));
        r.key = nsha('hnb_' + b64u(nmac(r.z, 'hnb2-token\n', r.A, r.B, na, r.nb)));
        return json(200, { state: 'pending' });
      }
      this.polls++;
      if (!r.key || this.answer === 'pending') return json(200, { state: 'pending' });
      if (this.answer === 'deny') return json(200, { state: 'denied' });
      if (this.answer === 'expire') return json(200, { state: 'expired' });
      this.keys.set(kidOf(r.key), r.key);
      // No settings here: the poll is unsealed; Lens fetches them sealed once the owner confirmed.
      return json(200, { state: 'allowed', confirm: b64u(nmac(r.key, `hnb2-confirm\n${one[1]}`)) });
    }
    // Everything else is signed, its body too, and sealed.
    if (headers.get('authorization')?.startsWith('HNB2 ')) return json(403, { error: { code: 'update_lens', message: 'This Hypernova Lens is older than Hypernova on this phone. Update Lens.' } });
    const v = this.verify(method, url, headers.get('authorization'));
    if (v === 'none') return json(403, { error: { code: 'bridge_access', message: 'Allow Hypernova Lens in Hypernova on your phone first.' } });
    if (v === 'bad') return json(401, { error: { code: 'bridge_signature', message: 'That call’s signature is no good here.' } });
    // Every answer to a signed call is sealed under that call's key: its status, its headers and its body.
    const ck = ncallKey(this.keys.get(v.kid)!, this.listener, v.nonce);
    const sealed = (bytes: Buffer) => { this.answers.push(latin1(bytes)); return sealedResponse(bytes); };
    const answer = (status: number, b: unknown) => sealed(nanswer(ck, status, [['content-type', 'application/json; charset=utf-8']], Buffer.from(JSON.stringify(b))));
    if (b64u(nsha(raw)) !== v.body) return answer(400, { error: { code: 'bad_body', message: 'That call’s body isn’t the one it signed.' } });
    let plain = '';
    if (raw.length) {
      try { plain = Buffer.concat(nopenStream(ck, 1, raw)).toString(); } catch { return answer(400, { error: { code: 'bad_seal', message: 'That call’s body isn’t sealed for this call.' } }); }
      this.opened.push(plain);
    }
    if (p === '/bridge/settings' && method === 'GET') return answer(200, { settings: this.settings });
    if (p === '/bridge/settings' && method === 'PUT') {
      const patch = JSON.parse(plain).settings;
      this.puts.push(patch);
      this.settings = { ...this.settings, ...patch };
      return answer(200, { settings: this.settings });
    }
    if (p === '/bridge/status') return answer(200, { paired: true, mac: 'MacBook Air', link: 'connected', route: 'local', app: '1.4.0' });
    if (p === '/bridge/access' && method === 'DELETE') { this.keys.delete(v.kid); return answer(200, { ok: true }); }
    if (p === '/bridge/open' && method === 'POST') {
      // An app from before this call refuses it as an unknown endpoint, unsealed (as LensBridge.kt's NotFound).
      if (this.openAnswer === null) return json(404, { error: { code: 'not_found', message: 'Unknown endpoint.' } });
      const screen = JSON.parse(plain).screen;
      if (screen !== 'glasses') return answer(400, { error: { code: 'bad_screen', message: 'Hypernova on this phone opens only the glasses’ settings.' } });
      this.opens.push(screen);
      return answer(200, { shown: this.openAnswer });
    }
    if (p === '/v1/preview') return answer(200, { url: 'https://192.168.1.20:47323/p/tok123/index.html?x=1' });
    if (p === '/v1/gone') return answer(403, { error: { code: 'glasses_removed', message: 'These glasses were removed on the Mac.' } });
    if (p === '/v1/echo') return answer(200, { ok: true, body: plain });
    if (/^\/v1\/sessions\/[^/]+\/screenshot$/.test(p)) return answer(200, { shot: { url: '/v1/shots/pic.png', width: 8, height: 8, title: 'A page', source: 'web', pageUrl: null } });
    // Audio: 300 KB, streamed in pieces, its headers inside the seal; `/v1/cut`: the same, cut off before the end.
    if (p === '/v1/big' || p === '/v1/cut') return sealed(nanswer(ck, 200, [['content-type', 'audio/wav'], ['x-sample-rate', '22050']], FakeApp.AUDIO, 64 * 1024, p === '/v1/cut'));
    if (p === '/bridge/shot' && method === 'POST') {
      const name = JSON.parse(plain).name;
      this.pictures.push(name);
      return sealed(nanswer(ck, 200, [['content-type', 'image/png']], Buffer.from(`PNG…bytes of ${name}`)));
    }
    if (p.startsWith('/v1/shots/')) return answer(404, { error: { code: 'not_found', message: 'nothing open here' } });
    if (method === 'POST' && p.startsWith('/v1/')) this.executed.push(`${p} ${plain}`);
    if (p.startsWith('/v1/')) return answer(200, { ok: true, path: p });
    return answer(404, { error: { code: 'not_found', message: 'Unknown endpoint.' } });
  }) as typeof fetch;

  static readonly AUDIO = Buffer.alloc(300_000).map((_, i) => i % 251);

  /**
   * A socket to the app, as `new WebSocket(url)` gives it: the handshake's signature checked, then
   * `script`'s messages (and close) sealed under that socket's key, as the app seals them. What Lens
   * sends is opened and kept (`sent`).
   */
  socketClass(script: Array<{ msgs?: object[]; closeWith?: number }>, urls: string[], sent: string[] = []): typeof WebSocket {
    const app = this;
    let made = 0;
    class FakeSealedSocket {
      static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
      readyState = 0; binaryType = 'blob'; bufferedAmount = 0;
      onopen: (() => void) | null = null;
      onmessage: ((e: { data: ArrayBuffer | string }) => void) | null = null;
      onclose: ((e: { code: number; reason: string }) => void) | null = null;
      onerror: (() => void) | null = null;
      private ck: Buffer | null = null;
      private out = 0;
      private got = 0;
      constructor(url: string) {
        urls.push(url);
        const v = app.verify('GET', new URL(url.replace(/^ws/, 'http')), null);
        if (typeof v === 'object') this.ck = ncallKey(app.keys.get(v.kid)!, app.listener, v.nonce);
        const step = script[Math.min(made++, script.length - 1)];
        setTimeout(() => {
          if (!this.ck || (step.closeWith && !step.msgs)) { this.readyState = 3; this.onerror?.(); this.onclose?.({ code: step.closeWith ?? 1006, reason: '' }); return; }
          this.readyState = 1;
          this.onopen?.();
          for (const m of step.msgs ?? []) this.emit(TEXT, Buffer.from(JSON.stringify(m)));
          if (step.closeWith) setTimeout(() => { this.emit(END, nend(step.closeWith!, 'as the Mac said')); this.readyState = 3; this.onclose?.({ code: 1000, reason: '' }); }, 5);
        }, 5);
      }
      private emit(kind: number, payload: Buffer): void {
        const f = nframe(this.ck!, FROM_APP, this.out++, kind, payload);
        this.onmessage?.({ data: ab(f) });
      }
      send(data: ArrayBuffer | string): void {
        if (typeof data === 'string') { sent.push(`unsealed ${data}`); return; }
        const f = nopenFrame(this.ck!, FROM_LENS, this.got++, new Uint8Array(data));
        sent.push(f.kind === TEXT ? f.payload.toString() : f.kind === END ? `end ${f.payload.readUInt16BE(0)}` : `binary ${f.payload.length}`);
      }
      close(): void { if (this.readyState === 3) return; this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000, reason: '' }), 1); }
    }
    return FakeSealedSocket as unknown as typeof WebSocket;
  }

  /** A call's signature (the header, or `hnb=` on a socket): good once, for this listener, within a minute. */
  verify(method: string, url: URL, auth: string | null): { kid: string; nonce: string; body: string } | 'none' | 'bad' {
    const sig = /^HNB3 (.+)$/.exec(auth ?? '')?.[1] ?? url.searchParams.get('hnb');
    const m = /^([\w-]+)\.(\d+)\.([\w-]+)\.([\w-]{43})\.([\w-]+)$/.exec(sig ?? '');
    if (!m) return 'none';
    const key = this.keys.get(m[1]);
    if (!key) return 'none';
    const query = url.search.slice(1).split('&').filter(q => q && q.split('=')[0] !== 'hnb').join('&');
    const want = b64u(nmac(key, `hnb3\n${this.listener}\n${method}\n${url.pathname}\n${query}\n${m[2]}\n${m[3]}\n${m[4]}`));
    if (want !== m[5] || Math.abs(Number(m[2]) - this.clock()) > 60_000 || this.nonces.has(m[3])) return 'bad';
    this.nonces.add(m[3]);
    return { kid: m[1], nonce: m[3], body: m[4] };
  }
}

/** PhoneLink with the fake app, no real waiting, and the "look again later" calls kept to run by hand. */
function linkWith(app: FakeApp, o: { token?: string | null; pairing?: boolean; fetch?: typeof fetch } = {}) {
  let token = o.token ?? null;
  const settings: Array<{ s: BridgeSettings; first: boolean }> = [];
  const later: Array<{ ms: number; fn: () => void }> = [];
  let now = 1_800_000_000_000;
  app.clock = () => now;
  const d: LinkDeps = {
    bridge: new Bridge(BRIDGE_URL, o.fetch ?? app.fetch),
    token: () => token,
    setToken: async t => { token = t; },
    hasPairing: () => !!o.pairing,
    name: 'Hypernova Lens',
    version: '0.1.9',
    onSettings: (s, first) => settings.push({ s, first }),
    now: () => now,
    // Each poll lets the "owner" act: the test sets app.answer between polls.
    sleep: async () => { now += 1000; await new Promise(r => setImmediate(r)); },
    later: (ms, fn) => { const e = { ms, fn }; later.push(e); return () => { const i = later.indexOf(e); if (i >= 0) later.splice(i, 1); }; },
  };
  const link = new PhoneLink(d);
  const states: string[] = [];
  link.onChange = () => states.push(link.state.kind);
  return { link, states, settings, later, token: () => token, advance: (ms: number) => { now += ms; } };
}

const until = async (pred: () => boolean, what: string) => {
  for (let i = 0; i < 500 && !pred(); i++) await new Promise(r => setImmediate(r));
  // Sealing and opening go through WebCrypto (real time): a little longer, then.
  for (let i = 0; i < 2000 && !pred(); i++) await new Promise(r => setTimeout(r, 1));
  assert.ok(pred(), what);
};

/** The owner's tap on "Allowed on your phone?", a moment after it shows (a tap sooner isn't taken). */
const confirmTap = (t: { link: PhoneLink; advance(ms: number): void }) => { t.advance(2000); t.link.retry(); };

/** Tokens as the app and Lens agree them: long and random, so finding one in the traffic means it was sent. */
const token = () => 'hnb_' + b64u(nc.randomBytes(32));
const OLD = token(), REVOKED = token(), KEPT = token(), K1 = token(), LIVE = token();

/** Nothing that went over the port carries `token`, or the key made from it. */
function neverSent(wire: string[], token: string): void {
  const key = nsha(token);
  for (const w of wire) {
    assert.ok(!w.includes(token.slice(4)), `the token went out: ${w}`);
    assert.ok(!w.includes(b64u(key)) && !w.includes(key.toString('hex')), `its key went out: ${w}`);
  }
}

// ------------------------------------------------------------------ the protocol's numbers

test('crypto: SHA-256, HMAC and X25519 as their RFCs give them', () => {
  const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');
  assert.equal(hex(sha256(utf8('abc'))), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  assert.equal(hex(sha256(new Uint8Array(0))), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  // RFC 4231, test case 2.
  assert.equal(hex(hmacSha256(utf8('Jefe'), utf8('what do ya want for nothing?'))), '5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843');
  // RFC 7748, 5.2.
  const k = Buffer.from('a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4', 'hex');
  const u = Buffer.from('e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c', 'hex');
  assert.equal(hex(x25519(k, u)), 'c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552');
  for (let n = 0; n < 70; n++) {
    const b = nc.randomBytes(n);
    assert.equal(hex(sha256(b)), nsha(b).toString('hex'));
    assert.equal(b64url(b), b64u(b));
    assert.deepEqual(Buffer.from(fromB64url(b64url(b))!), b);
  }
  assert.equal(fromB64url('a+b/'), null);
});

/**
 * One exchange with fixed keys and nonces (RFC 7748's two key pairs), worked out with node:crypto:
 * the app's tests (BridgeCryptoTest.kt) hold its own code to the same numbers.
 */
test('the protocol’s numbers: code, token, key id, proof, confirmation and a signature, as the app works them out', () => {
  const lensPriv = Buffer.from('77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a', 'hex');
  const lensNonce = Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex');
  const appNonce = Buffer.from('f0e1d2c3b4a5968778695a4b3c2d1e0f', 'hex');
  const queue = [lensPriv, lensNonce];
  const mine = startPairing(n => { const b = queue.shift()!; assert.equal(b.length, n); return new Uint8Array(b); });
  assert.equal(b64url(mine.pub), 'hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo');
  assert.equal(mine.commit, 'NEeFLkoRsuPswHb3YaLUaMF_9L8BmbPiPm3JGMS786s');
  const done = finishPairing(mine, { key: '3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08', nonce: b64url(appNonce) })!;
  assert.equal(done.code, '392602');
  assert.equal(done.token, 'hnb_vrLDd3PSfnZXLwuwgRZfml7XU2EkJBDBm51AldXQf_A');
  const { key, kid } = bridgeKey(done.token);
  assert.equal(Buffer.from(key).toString('hex'), 'ad0b3896686cf715ed395d1379592b6ef2d185dd883b8bf0543c2063705476a0');
  assert.equal(kid, 'yn8jTX6SL2djsVIw');
  assert.equal(pingProof(key, 'listener-1', 'challenge-nonce-0001'), 'Kuju7EKcMwhaaNi54Uu-eyynpZ5jZNRCIPuXbc1dCl4');
  assert.equal(confirmation(key, 'req-0001'), '0GkXoe0-7WbKDvOm-x652xp9Tkv-x4HCcqhckmy8uZ4');
  assert.equal(canonicalQuery('a=1&&hnb=x.1.y.z&b=%20x'), 'a=1&b=%20x');
  assert.equal(EMPTY_BODY, '47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU');
  const body = bodyHash('{"text":"hi"}');
  assert.equal(body, '57mV76dVxf87hNIYi1jLSukWpZRw6zdh34qBTxF2NQA');
  assert.equal(requestSignature(key, 'listener-1', 'POST', '/v1/sessions/s1/prompt', canonicalQuery('a=1&b=%20x&hnb=old'), '1800000000000', 'nonce-0001', body), '8JgU02H7q1P_pM_K_pusw61MFUbuBvODnwGmr4V5FQU');
  assert.equal(Buffer.from(callKeyBytes(key, 'listener-1', 'nonce-0001')).toString('hex'), 'b1e7d263d5e495f5b65eacc11d330ea1146cfc736bed7e984fe371dff56256c6');
  // What the app may ask Lens to wait, whatever it says.
  assert.equal(clampRetryAfter(3_000_000), 3600);
  assert.equal(clampRetryAfter(0.2), 1);
  assert.equal(clampRetryAfter('x'), 0);
  // A key of small order agrees on zero: no token from it.
  assert.equal(finishPairing(mine, { key: b64url(new Uint8Array(32)), nonce: b64url(appNonce) }), null);
  assert.equal(finishPairing(mine, { key: 'short', nonce: b64url(appNonce) }), null);
});

/**
 * The seal's numbers (data/seal.ts), the same ones the app's tests hold BridgeSeal to
 * (BridgeSealTest.kt): one call's key, a request body sealed, an answer opened, a message and an
 * end marker; and each checked against node:crypto's AES-GCM too.
 */
test('the seal’s numbers: a body, an answer, a message and an end marker, as the app seals and opens them', async () => {
  const key = Buffer.from('ad0b3896686cf715ed395d1379592b6ef2d185dd883b8bf0543c2063705476a0', 'hex');
  const call = await CallKey.derive(key, 'listener-1', 'nonce-0001');
  const ck = ncallKey(key, 'listener-1', 'nonce-0001');
  assert.equal(ck.toString('hex'), 'b1e7d263d5e495f5b65eacc11d330ea1146cfc736bed7e984fe371dff56256c6');
  const request = await sealBody(call, utf8('{"text":"hi"}'));
  assert.equal(Buffer.from(request).toString('hex'), '010000001d67f122c41afc2b8316a9f2c6c2a1d2c93d493be450fcc5a647dbe9c15b');
  assert.equal(Buffer.concat(nopenStream(ck, FROM_LENS, request)).toString(), '{"text":"hi"}');
  const response = Buffer.from('000000005dbe5c7190b614a886f6f1b7bf47e1a9dac9bdc9543cb5640737c3f1f5c01d59fb00b164ab19b378113875f40760f723b84ac01e25f1dbacafab0dc72673cf708b19f3c0e28f0dc4a9538cbf1b91b17a934de18701bdb8d2bb73a033f213010000001b3c519bb7a221007fe6eb1f9aa97c5de61c8e96edd68e0a469be22f', 'hex');
  const a = await openAnswer(call, response);
  assert.equal(a.status, 200);
  assert.deepEqual(a.headers, [['content-type', 'application/json; charset=utf-8']]);
  assert.equal(new TextDecoder().decode(a.body), '{"ok":true}');
  // The app's writer ends with an empty last record instead: the same answer.
  const b = await openAnswer(call, nanswer(ck, 200, [['content-type', 'application/json; charset=utf-8']], Buffer.from('{"ok":true}')));
  assert.deepEqual([b.status, b.headers, new TextDecoder().decode(b.body)], [200, a.headers, '{"ok":true}']);
  assert.equal(Buffer.from(await sealFrame(call, FROM_APP, 0, TEXT, utf8('{"t":"bridge.settings"}'))).toString('hex'), 'c4052090f55aff97a6a2e1e812e3f8d7d8a8c45f29b5644026ca6a4c4c00670ab1e2bc3bfdb2487f');
  const end = await openFrame(call, FROM_LENS, 5, Buffer.from('9460f3dfb40aed4858fcae8db5c80fae52ba98', 'hex'));
  assert.equal(end.kind, END);
  assert.deepEqual([...end.payload], [...endPayload(1000, '')]);
  assert.equal(nopenFrame(ck, FROM_LENS, 5, await sealFrame(call, FROM_LENS, 5, END, endPayload(1000, ''))).kind, END);
  // Changed, moved, repeated, cut or under another key: nothing opens.
  const bad = (bytes: Uint8Array) => assert.rejects(openStream(call, FROM_APP, bytes), /SealBroken|open|cut|end/);
  await bad(response.subarray(0, response.length - 1));
  await bad(Buffer.concat([response.subarray(0, 5 + 0x5d), response.subarray(0, 5 + 0x5d)]));
  await bad(Buffer.from(response).fill(0, 20, 21));
  await bad(Buffer.concat([response, Buffer.from([0])]));
  await bad(nanswer(ck, 200, [], Buffer.from('x'), 64, true));
  const other = await CallKey.derive(key, 'listener-1', 'nonce-0002');
  await assert.rejects(openAnswer(other, response));
  await assert.rejects(openStream(call, FROM_LENS, response));
  // A relay re-sealing a record under its own key (one it made up) opens no better.
  await bad(nrecord(nc.randomBytes(32), FROM_APP, 0, true, Buffer.from('{"status":200,"headers":[]}')));
  assert.equal(nopen(ck, FROM_LENS, 0, 'hnb3-body\nlast', request.subarray(5)).toString(), '{"text":"hi"}');
});

// ------------------------------------------------------------------ finding the app, asking for access

test('discovery: the app answers its ping with its listener id; nothing, an older app, or something else is no app', async () => {
  const app = new FakeApp();
  assert.deepEqual(await new Bridge(BRIDGE_URL, app.fetch).ping(), { version: '1.4.0', protocol: 4, id: 'listener-1', proof: null });
  app.up = false;
  assert.equal(await new Bridge(BRIDGE_URL, app.fetch).ping(), null);
  const other = (async () => new Response('{"ok":true}', { status: 200 })) as unknown as typeof fetch;
  assert.equal(await new Bridge(BRIDGE_URL, other).ping(), null, 'another server on the port is not the app');
  // An app that signs but doesn't seal (bridge 2) is one to update: Lens says so and signs nothing for it.
  const older = (async () => json(200, { ok: true, app: 'hypernova', version: '1.3.0', bridge: 3, id: 'old' })) as unknown as typeof fetch;
  assert.deepEqual(await new Bridge(BRIDGE_URL, older).ping(), { version: '1.3.0', protocol: 3, id: 'old', proof: null });
  const t = linkWith(new FakeApp(), { token: OLD, fetch: older });
  await t.link.start();
  assert.deepEqual(t.link.state, { kind: 'old_app', app: '1.3.0', protocol: 3 });
  assert.equal(linkMessage(t.link.state, 0, false)!.title, 'Update Hypernova on your phone');
  assert.equal(t.later.length, 1, 'it looks again in a few seconds');
  const signer = new BridgeSigner(new Bridge(BRIDGE_URL, older), OLD);
  await assert.rejects(signer.ready(), (e: Error & { code?: string }) => e.code === 'update_app');
  await assert.rejects(new Api(BRIDGE_URL, null, 'bridge', signer).get('/v1/usage'), (e: Error & { code?: string | null }) => e.code === 'update_app');
});

test('access: Lens and the app agree a token without sending it; the lens shows its own code, the same as the app’s', async () => {
  const app = new FakeApp();
  const t = linkWith(app);
  const run = t.link.start();
  await until(() => t.link.state.kind === 'asking', 'asking');
  assert.deepEqual(app.asked, [{ name: 'Hypernova Lens', version: '0.1.9' }]);
  const st = t.link.state as Extract<typeof t.link.state, { kind: 'asking' }>;
  assert.match(st.code, /^\d{6}$/);
  assert.equal(st.code, app.codes[0], 'both screens show the same code');
  const m = linkMessage(t.link.state, 0, false)!;
  assert.equal(m.title, 'Allow it on your phone');
  // The code on a line of its own (the page groups it in two threes, as the phone's prompt does).
  assert.equal(m.code, st.code);
  // The owner takes a moment, then allows it.
  await until(() => app.polls >= 3, 'polling');
  app.answer = 'allow';
  await run;
  // "Allowed on your phone? Tap to connect": nothing is kept until the owner says so on the glasses.
  assert.equal(t.link.state.kind, 'confirm');
  assert.equal((t.link.state as Extract<typeof t.link.state, { kind: 'confirm' }>).code, st.code);
  assert.equal(linkMessage(t.link.state, 0, false)!.title, 'Allowed on your phone?');
  assert.equal(t.token(), null);
  // A tap at once (meant for what was there before) isn't taken for it.
  t.link.retry();
  await new Promise(r => setImmediate(r));
  assert.equal(t.link.state.kind, 'confirm');
  confirmTap(t);
  await until(() => t.link.state.kind === 'bridge', 'connected');
  const token = t.token()!;
  assert.match(token, /^hnb_[\w-]{43}$/);
  assert.equal(t.link.token, token);
  assert.ok(t.link.signer?.proven, 'the app proved it holds the key');
  assert.deepEqual([...app.keys.values()], [nsha(token)], 'the app keeps only its SHA-256');
  neverSent(app.wire, token);
  assert.equal(t.settings.length, 1);
  assert.equal(t.settings[0].first, true);
  assert.equal(t.settings[0].s.language, 'ru');
  // The drawn look, which the app keeps (not Lens's default, the built-in font).
  assert.equal(t.settings[0].s.look, 'hypernova');
  // They came sealed (GET /bridge/settings after the confirm), never from the unsealed poll.
  assert.ok(app.wire.some(w => w.startsWith('GET /bridge/settings ') && w.includes('HNB3 ')), 'settings fetched signed');
  assert.ok(app.answers.length > 0);
  assert.deepEqual(t.states, ['looking', 'asking', 'confirm', 'bridge']);
});

test('access: a kept token is used once the app proves it holds its key; one it doesn’t prove is kept, and Lens asks only when tapped', async () => {
  const app = new FakeApp();
  app.allow(OLD);
  const kept = linkWith(app, { token: OLD });
  await kept.link.start();
  assert.equal(kept.link.state.kind, 'bridge');
  assert.equal(app.asked.length, 0, 'nothing asked of the owner');
  assert.equal(kept.settings[0].first, true);
  assert.ok(app.wire.some(w => w.includes('HNB3 ')), 'the settings call was signed');
  neverSent(app.wire, OLD);

  const before = app.wire.length;
  const refused = linkWith(app, { token: REVOKED });
  await refused.link.start();
  // Disconnected on the phone (or something else on the port saying so): Lens says it and waits.
  assert.equal(refused.link.state.kind, 'revoked');
  assert.equal(linkMessage(refused.link.state, 0, false)!.sub, 'Or Hypernova isn’t running. Tap to connect again.');
  assert.equal(app.asked.length, 0, 'nothing asked of the owner by itself');
  assert.equal(refused.token(), REVOKED, 'kept until a new one is agreed');
  assert.ok(app.wire.slice(before).every(w => !w.includes('HNB3') && !w.includes('hnb=')), 'nothing signed for a listener that didn’t prove itself');
  // A tap asks; the token agreed then takes the old one's place.
  refused.link.retry();
  await until(() => refused.link.state.kind === 'asking', 'asking again');
  app.answer = 'allow';
  await until(() => refused.link.state.kind === 'confirm', 'allowed');
  assert.equal(refused.token(), REVOKED, 'still the old one until the owner confirms');
  confirmTap(refused);
  await until(() => refused.link.state.kind === 'bridge', 'connected');
  assert.notEqual(refused.token(), REVOKED);
  assert.ok(app.keys.has(kidOf(nsha(refused.token()!))));
  neverSent(app.wire, REVOKED);
  neverSent(app.wire, refused.token()!);
});

test('a squatter on the port gets nothing it can use: no proof, so nothing signed, no token, and only public halves of an ask', async () => {
  // Another app took 47380 before Hypernova (after a reboot, say) and answers as if it were it.
  const seen: string[] = [];
  let squatting = true;
  const app = new FakeApp();
  const squatter = (async (input: string | URL | Request, init?: RequestInit) => {
    if (!squatting) return app.fetch(input, init);
    const url = new URL(String(input));
    seen.push(`${init?.method ?? 'GET'} ${url.pathname}${url.search} ${JSON.stringify([...new Headers(init?.headers)])} ${init?.body ?? ''}`);
    if (url.pathname === '/bridge/ping') return json(200, { ok: true, app: 'hypernova', version: '9.9', bridge: 4, id: 'squat', proof: 'A'.repeat(43) });
    if (url.pathname === '/bridge/request') return json(202, { id: 'squat-request', key: b64url(new Uint8Array(32)), nonce: b64url(new Uint8Array(16)), expiresInSeconds: 120 });
    return json(200, { state: 'allowed', confirm: 'A'.repeat(43), settings: {} });
  }) as typeof fetch;
  app.allow(KEPT);
  const t = linkWith(app, { token: KEPT, fetch: squatter });
  await t.link.start();
  assert.equal(t.link.state.kind, 'revoked', 'its proof is no proof');
  assert.equal(t.token(), KEPT, 'and its word can’t make Lens forget the token');
  // Told to ask (a tap), Lens asks it: its fake "key" agrees on nothing, and Lens never even showed it a nonce.
  t.link.retry();
  await until(() => t.link.state.kind === 'expired', 'nothing agreed');
  assert.equal(t.token(), KEPT);
  neverSent(seen, KEPT);
  assert.ok(seen.every(s => !s.includes('HNB3') && !s.includes('hnb=')), 'nothing signed for it');
  assert.ok(!seen.some(s => s.includes('"nonce"')), seen.join('\n'));
  // It lets go of the port and Hypernova takes it: a tap, the app proves the key, and Lens is back
  // with nothing to allow again.
  squatting = false;
  t.link.retry();
  await until(() => t.link.state.kind === 'bridge', 'back through the app');
  assert.equal(app.asked.length, 0);
  assert.equal(t.token(), KEPT);

  // One that answers with a real key of its own can't say "allowed" for the real app either: the
  // confirmation is made with the token both sides agreed, which a third can't work out.
  const own = appKeys();
  let revealed = '';
  const squatter2 = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    if (url.pathname === '/bridge/ping') return json(200, { ok: true, app: 'hypernova', version: '9.9', bridge: 4, id: 'squat' });
    if (url.pathname === '/bridge/request') return json(202, { id: 'squat-request', key: b64u(own.pub), nonce: b64url(new Uint8Array(16).fill(7)), expiresInSeconds: 120 });
    if (init?.method === 'POST') { revealed = String(init.body); return json(200, { state: 'pending' }); }
    return json(200, { state: 'allowed', confirm: 'A'.repeat(43), settings: {} });
  }) as typeof fetch;
  const u = linkWith(app, { fetch: squatter2 });
  await u.link.start();
  assert.equal(u.link.state.kind, 'expired', 'a confirmation that doesn’t check out is no access');
  assert.equal(u.token(), null);
  assert.match(revealed, /"nonce"/);

  // One that plays the whole app (its own key, its own "allowed") gets no further than "Allowed on
  // your phone? Tap to connect": the owner saw no question in Hypernova, and without that tap Lens
  // keeps nothing and its own token stays. Even tapped, the token is one only it knows: the real
  // app never saw it, so it opens nothing there.
  const fake = new FakeApp();
  fake.answer = 'allow';
  const w = linkWith(fake, { token: KEPT });
  w.link.retry();
  await w.link.start(true);
  assert.equal(w.link.state.kind, 'confirm');
  assert.equal(w.token(), KEPT);
  // The tap waits as long as the app keeps an allowed ask to collect (10 minutes), then it says so:
  // allowed on the phone, not confirmed on the glasses (not "not answered on your phone").
  assert.equal(w.later.at(-1)!.ms, 10 * 60_000);
  w.later.at(-1)!.fn();
  assert.equal(w.link.state.kind, 'unconfirmed', 'untapped, it runs out');
  assert.equal(linkMessage(w.link.state, 0, false)!.title, 'Not confirmed on the glasses');
  assert.equal(w.token(), KEPT);
  fake.answer = 'allow';
  w.link.retry();
  await until(() => w.link.state.kind === 'confirm', 'allowed again');
  confirmTap(w);
  await until(() => w.link.state.kind === 'bridge', 'tapped anyway');
  const theirs = w.token()!;
  assert.ok(!app.keys.has(kidOf(nsha(theirs))));
  const real = new Bridge(BRIDGE_URL, app.fetch);
  await assert.rejects(real.settings(new BridgeSigner(real, theirs)), (e: Error & { code?: string }) => e.code === 'bridge_access');
});

test('a refusal after Lens showed its nonce never makes it ask again by itself: each ask is a try at the code', async () => {
  // Something on the port answers the nonce with "too many" (or goes away), hoping Lens asks afresh.
  const app = new FakeApp();
  let asks = 0;
  const port = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    if (url.pathname === '/bridge/request') asks++;
    if (/^\/bridge\/request\/./.test(url.pathname) && init?.method === 'POST') return json(429, { error: { code: 'too_many_requests', message: 'Too many.', retryAfterSeconds: 1 } });
    return app.fetch(input, init);
  }) as typeof fetch;
  const t = linkWith(app, { fetch: port });
  await t.link.start();
  assert.equal(t.link.state.kind, 'expired');
  assert.equal(t.later.length, 0, 'nothing scheduled');
  assert.equal(asks, 1);
  const gone = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    if (url.pathname === '/bridge/request') asks++;
    if (/^\/bridge\/request\/./.test(url.pathname) && init?.method === 'POST') throw new TypeError('Failed to fetch');
    return app.fetch(input, init);
  }) as typeof fetch;
  const u = linkWith(app, { fetch: gone });
  await u.link.start();
  assert.equal(u.link.state.kind, 'expired');
  assert.equal(u.later.length, 0);
  assert.equal(asks, 2);
});

test('pairing through something in between: it can’t make the two codes match', async () => {
  // A relay holds the port while Lens asks and asks the real app itself, each with keys of its own.
  const app = new FakeApp();
  const relay = { lensKey: '', appCode: '' };
  let toApp: ReturnType<typeof startPairing> | null = null;
  let appAnswer: { id: string; key: string; nonce: string } | null = null;
  const theirs = appKeys();
  const mitm = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    const method = init?.method ?? 'GET';
    if (url.pathname === '/bridge/request' && method === 'POST') {
      relay.lensKey = JSON.parse(String(init!.body)).key;
      toApp = startPairing();
      const res = await app.fetch(BRIDGE_URL + '/bridge/request', { method: 'POST', body: JSON.stringify({ name: 'Hypernova Lens', version: '0.1.9', key: b64url(toApp.pub), commit: toApp.commit, bridge: 4 }) });
      appAnswer = await res.json();
      // To Lens: its own key, and the app's nonce (it can't know Lens's yet: Lens only committed to it).
      return json(202, { id: appAnswer!.id, key: b64u(theirs.pub), nonce: appAnswer!.nonce, expiresInSeconds: 120 });
    }
    if (method === 'POST') {
      // Lens's nonce comes now, too late: the app gets the relay's own, committed before.
      return app.fetch(BRIDGE_URL + url.pathname, { method: 'POST', body: JSON.stringify({ nonce: b64url(toApp!.nonce) }) });
    }
    return app.fetch(input, init);
  }) as typeof fetch;
  const t = linkWith(app, { fetch: mitm });
  const run = t.link.start();
  await until(() => t.link.state.kind === 'asking', 'asking');
  const lensCode = (t.link.state as Extract<typeof t.link.state, { kind: 'asking' }>).code;
  assert.equal(app.codes.length, 1);
  assert.notEqual(lensCode, app.codes[0], 'the owner sees two different codes and denies');
  t.link.stop();
  void run;
});

test('signed calls: each good once, for this listener, for its path, within a minute; after the app opens its port again Lens proves it and signs afresh', async () => {
  const app = new FakeApp();
  app.allow(K1);
  const bridge = new Bridge(BRIDGE_URL, app.fetch);
  const signer = new BridgeSigner(bridge, K1);
  await assert.rejects(signer.prepare('GET', BRIDGE_URL + '/bridge/settings'), 'nothing signed before the app proved itself');
  await signer.ready();
  const url = BRIDGE_URL + '/bridge/settings';
  const call = (p: PreparedCall, to = url, method = 'GET') => app.fetch(to, { method, headers: { Authorization: p.auth }, body: p.body ? (p.body.slice().buffer as ArrayBuffer) : undefined });
  /** The answer's status: inside the seal when it opened, else the refusal's own. */
  const status = async (p: PreparedCall, to = url, method = 'GET') => { const r = await call(p, to, method); return (await signer.answered(r, p.call))?.status ?? -r.status; };
  const h = await signer.prepare('GET', url);
  assert.equal(await status(h), 200);
  assert.equal(await status(h), -401, 'the same signature twice: a replay');
  assert.equal(await status(await signer.prepare('GET', url), BRIDGE_URL + '/bridge/status'), -401, 'another path');
  assert.equal(await status(await signer.prepare('GET', url), url, 'PUT'), -401, 'another method');
  assert.equal(await status(await signer.prepare('GET', BRIDGE_URL + '/v1/x?a=1'), BRIDGE_URL + '/v1/x?a=2'), -401, 'another query');
  assert.equal(await status(await signer.prepare('GET', BRIDGE_URL + '/v1/x?a=1'), BRIDGE_URL + '/v1/x?a=1'), 200);
  // A body goes sealed, and only as sealed for this call: swapped for another call's, it's refused.
  const p1 = await signer.prepare('POST', BRIDGE_URL + '/v1/echo', '{"text":"one"}');
  const p2 = await signer.prepare('POST', BRIDGE_URL + '/v1/echo', '{"text":"two"}');
  assert.ok(!latin1(p1.body!).includes('one'), 'the body is sealed');
  const r1 = await call(p1, BRIDGE_URL + '/v1/echo', 'POST');
  assert.deepEqual(await (await signer.answered(r1, p1.call))!.json(), { ok: true, body: '{"text":"one"}' });
  const swapped = await call({ ...p2, body: p1.body }, BRIDGE_URL + '/v1/echo', 'POST');
  assert.equal((await (await signer.answered(swapped, p2.call))!.json()).error.code, 'bad_body');
  const old = new BridgeSigner(bridge, K1, () => Date.now() - 61_000);
  await old.ready();
  assert.equal(await status(await old.prepare('GET', url)), -401, 'a minute late');
  assert.equal((await app.fetch(url, { headers: { Authorization: `Bearer ${K1}` } })).status, 403, 'the token itself opens nothing');
  app.wire.pop();
  // The app opened its port again: a signature for the listener before is no good.
  const before = await signer.prepare('GET', url);
  app.listener = 'listener-2';
  assert.equal(await status(before), -401);
  // Lens's own calls notice, have the app prove itself again, and sign afresh.
  assert.equal((await bridge.settings(signer)).language, 'ru');
  const api = new Api(BRIDGE_URL, K1, 'bridge', signer);
  assert.equal(api.token, null, 'through the app, the token is never sent');
  app.listener = 'listener-3';
  await withGlobals({ WebSocket: undefined, fetch: app.fetch }, async () => {
    assert.deepEqual(await api.get('/v1/usage'), { ok: true, path: '/v1/usage' });
  });
  neverSent(app.wire, K1);
  // A socket's address carries its signature in the query, the token nowhere; its messages are sealed under its own key.
  const ws = await signer.socket('/v1/ws');
  assert.match(ws.url, new RegExp(`^ws://127\\.0\\.0\\.1:47380/v1/ws\\?hnb=[\\w-]{16}\\.\\d+\\.[\\w-]{22}\\.${EMPTY_BODY}\\.[\\w-]{43}$`));
  const v = app.verify('GET', new URL(ws.url.replace(/^ws/, 'http')), null);
  assert.ok(typeof v === 'object');
  assert.equal(ws.call.nonce, v.nonce);
  assert.equal(ws.call.listener, 'listener-3');
});

test('the app’s answers open only under the call’s key: whatever takes the port after it gets one call at most, and only within seconds of the app’s last word', async () => {
  const app = new FakeApp();
  app.allow(K1);
  let now = 1_800_000_000_000;
  app.clock = () => now;
  // The port: the app's, until its process ends and another app takes it.
  let squatter: typeof fetch | null = null;
  const seen: string[] = [];
  const port = (async (input: string | URL | Request, init?: RequestInit) => (squatter ?? app.fetch)(input, init)) as typeof fetch;
  const bridge = new Bridge(BRIDGE_URL, port);
  const signer = new BridgeSigner(bridge, K1, () => now);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: port }, async () => {
    assert.deepEqual(await api.get('/v1/usage'), { ok: true, path: '/v1/usage' });
    const pings = () => app.wire.filter(w => w.startsWith('GET /bridge/ping')).length;
    const before = pings();
    // Busy: the app's answers keep its proof fresh, no ping before each call.
    for (let i = 0; i < 5; i++) { now += 1000; await api.get('/v1/usage'); }
    assert.equal(pings(), before);
    // The Mac's own refusals come through the app, proven: they're the Mac's word.
    await assert.rejects(api.get('/v1/gone'), (e: Error & { problem?: string }) => e.problem === 'revoked');
    // A quiet spell: the next call has the app prove itself first.
    now += 5000;
    await api.get('/v1/usage');
    assert.equal(pings(), before + 1);
    // The app's process ends; another app takes the port at once and answers as the app would.
    squatter = (async (input: string | URL | Request, init?: RequestInit) => {
      const url = new URL(String(input));
      seen.push(`${init?.method ?? 'GET'} ${url.pathname} ${init?.body ?? ''}`);
      if (url.pathname === '/bridge/ping') return json(200, { ok: true, app: 'hypernova', version: '9', bridge: 4, id: 'squat' });
      return json(200, { ok: true, sessions: ['fake'] });
    }) as typeof fetch;
    // Within seconds of the app's last word, one call may reach it: its answer is refused...
    now += 1000;
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'first' }), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    // ...and nothing is signed for it again: it can't prove the key (to Lens, the app no longer knows it).
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'second' }), (e: Error & { problem?: string }) => e.problem === 'revoked');
    await assert.rejects(bridge.settings(signer), (e: Error & { code?: string }) => e.code === 'bridge_access');
    assert.equal(seen.filter(s => s.startsWith('POST')).length, 1, seen.join('\n'));
    assert.ok(!seen.some(s => s.includes('second')));
    // After a quiet spell, not even one.
    squatter = null;
    now += 1000;
    await api.get('/v1/usage');
    squatter = (async (input: string | URL | Request, init?: RequestInit) => {
      const url = new URL(String(input));
      seen.push(`${init?.method ?? 'GET'} ${url.pathname} ${init?.body ?? ''}`);
      if (url.pathname === '/bridge/ping') return json(200, { ok: true, app: 'hypernova', version: '9', bridge: 4, id: 'squat' });
      return json(200, { ok: true });
    }) as typeof fetch;
    now += 5000;
    seen.length = 0;
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'later' }), (e: Error & { problem?: string }) => e.problem === 'revoked');
    assert.deepEqual(seen.map(s => s.split(' ')[1]), ['/bridge/ping']);
  });
  neverSent(app.wire, K1);
});

test('something relaying Lens’s calls reads only ciphertext, and can’t change a call, an answer or its end', async () => {
  const app = new FakeApp();
  app.allow(K1);
  // A relay (another app, holding a connection the web view keeps from while it had the port):
  // everything it sees, both ways.
  const seen: string[] = [];
  let swapRequest = false, swapAnswer = false, cutAnswer = false;
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    const ping = url.pathname === '/bridge/ping';
    if (!ping) seen.push(`${init?.method ?? 'GET'} ${url.pathname} ${latin1(bodyBytes(init?.body))}`);
    const body = swapRequest && init?.method === 'POST' && url.pathname.startsWith('/v1/') ? Buffer.from(bodyBytes(init?.body)).map((b, i) => (i === 9 ? b ^ 1 : b)) : undefined;
    const res = await app.fetch(input, body ? { ...init, body: body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) } : init);
    const bytes = Buffer.from(await res.arrayBuffer());
    if (!ping) seen.push(`=> ${res.status} ${latin1(bytes)}`);
    if (swapAnswer && url.pathname !== '/bridge/ping') return new Response(JSON.stringify({ ok: true, path: '/v1/elsewhere' }), { status: res.status, headers: res.headers });
    if (cutAnswer && url.pathname !== '/bridge/ping') return new Response(bytes.subarray(0, bytes.length - 21), { status: res.status, headers: res.headers });
    return new Response(bytes, { status: res.status, headers: res.headers });
  }) as typeof fetch;
  const bridge = new Bridge(BRIDGE_URL, relay);
  const signer = new BridgeSigner(bridge, K1, app.clock);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: relay }, async () => {
    assert.deepEqual(await api.post('/v1/sessions/s1/prompt', { text: 'run the tests, the key is sk-live-1' }), { ok: true, path: '/v1/sessions/s1/prompt' });
    assert.deepEqual(await api.post('/v1/echo', { text: 'secret prompt' }), { ok: true, body: '{"text":"secret prompt"}' });
    assert.deepEqual(app.opened.at(-1), '{"text":"secret prompt"}', 'the app read the prompt');
    // The relay read every byte that went by (the method and path are in the clear, by design), and
    // none of it was the prompt, the answer, its headers, or even its status.
    for (const line of seen) for (const word of ['run the tests', 'sk-live', 'secret', '"text"', '"ok"', '"path"', 'application/json']) assert.ok(!line.includes(word), `the relay read "${word}": ${line.slice(0, 120)}`);
    assert.ok(seen.filter(l => l.startsWith('=> ')).every(l => l.startsWith('=> 200 ')), 'every sealed answer looks the same outside: a 200');
    // A byte of a prompt changed on the way: the app refuses it (sealed), and Lens says so.
    swapRequest = true;
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'run the tests' }), (e: Error & { code?: string }) => e.code === 'bad_body');
    swapRequest = false;
    // An answer swapped on the way: not the app's, so Lens takes nothing from it.
    swapAnswer = true;
    await assert.rejects(api.get('/v1/usage'), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    await assert.rejects(bridge.status(signer), (e: Error & { code?: string }) => e.code === 'no_app');
    swapAnswer = false;
    // A big answer (audio) comes in pieces, its headers inside the seal, and is taken whole; cut off before its end, it isn't taken at all.
    const big = await api.fetch('POST', '/v1/big', {});
    assert.equal(big.status, 200);
    assert.equal(big.headers.get('x-sample-rate'), '22050');
    assert.equal(big.headers.get('content-type'), 'audio/wav');
    assert.deepEqual(Buffer.from(await big.arrayBuffer()), Buffer.from(FakeApp.AUDIO));
    await assert.rejects(api.fetch('POST', '/v1/cut', {}), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    cutAnswer = true;
    await assert.rejects(api.fetch('POST', '/v1/big', {}), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    cutAnswer = false;
    // A Telescope picture is asked for by its name inside the sealed body and comes back sealed:
    // the relay never sees a name it could fetch from the Mac (or the app) itself.
    seen.length = 0;
    const hub = new LiveHub({ baseUrl: BRIDGE_URL, token: '', macName: 'Mac', macId: null, deviceId: null, signer }, 'bridge');
    const shot = await hub.screenshot('s1');
    assert.match(shot.url, /^blob:/);
    assert.deepEqual(app.pictures, ['pic']);
    assert.ok(seen.every(l => !l.includes('pic') && !l.includes('shots')), seen.join('\n'));
    hub.stop();
  });
  neverSent(app.wire, K1);
});

/**
 * The fourth review (P1): a relay passes a POST to the app (which carries it: done on the Mac),
 * drops the sealed answer and answers an unsealed 401 itself; Lens used to prove the app again and
 * send the call afresh, so the Mac did it twice. Now a call is made afresh only when the listener
 * that proves itself is another than the one the call was signed for.
 */
test('an unsealed 401 from the same listener never has a call sent again; one from a new listener does, once', async () => {
  const app = new FakeApp();
  app.allow(K1);
  let fake401 = false;
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    const res = await app.fetch(input, init);
    if (fake401 && (init?.method === 'POST' || init?.method === 'PUT')) { fake401 = false; await res.arrayBuffer(); return json(401, { error: { code: 'bridge_signature', message: 'x' } }); }
    return res;
  }) as typeof fetch;
  const bridge = new Bridge(BRIDGE_URL, relay);
  const signer = new BridgeSigner(bridge, K1, app.clock);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: relay }, async () => {
    fake401 = true;
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'git push --force' }), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    assert.equal(app.executed.length, 1, 'carried once, never again');
    // The same for the bridge's own calls.
    fake401 = true;
    await assert.rejects(bridge.putSettings(signer, { moving: true }), (e: Error & { code?: string }) => e.code === 'not_sent');
    assert.equal(app.puts.length, 1);
    // The app really opened its port again (a new listener id) after Lens proved the old one: a GET
    // goes afresh once; a POST or PUT never (the fifth round: a relay's 401 and the port reopening
    // meanwhile had the Mac do it twice), and Lens says so in its own words.
    assert.deepEqual(await api.get('/v1/usage'), { ok: true, path: '/v1/usage' });
    app.listener = 'listener-9';
    await assert.rejects(api.post('/v1/sessions/s1/prompt', { text: 'run the tests' }), (e: Error & { code?: string | null }) => e.code === 'not_sent' && e.message === WORDS.not_sent);
    assert.equal(app.executed.length, 1);
    assert.deepEqual(await api.get('/v1/usage'), { ok: true, path: '/v1/usage' }, 'a GET: proved again, made afresh');
    app.listener = 'listener-11';
    await assert.rejects(bridge.putSettings(signer, { moving: true }), (e: Error & { code?: string }) => e.code === 'not_sent');
    assert.equal(app.puts.length, 1);
    assert.equal((await bridge.settings(signer)).language, 'ru', 'a GET of the bridge’s own: the same');
  });
});

/**
 * The fifth round (N-a, D2–D5): every unsealed body Lens reads is read within the call's deadline and
 * a few KB at most: a refusal that trickles, a ping that trickles (which used to wedge the shared
 * prove), a 256 MB refusal.
 */
test('unsealed answers are read within the deadline and a few KB at most: a trickling refusal or ping is no answer', async () => {
  const app = new FakeApp();
  app.allow(K1);
  // A body that trickles a byte at a time; as a real fetch does, the call's abort ends the read.
  const trickle = (status: number, first: string, signal?: AbortSignal | null) => new Response(new ReadableStream<Uint8Array>({
    start(c) { c.enqueue(utf8(first)); signal?.addEventListener('abort', () => { try { c.error(new DOMException('aborted', 'AbortError')); } catch { /* done */ } }); },
    pull(c) { return new Promise(r => setTimeout(() => { if (!signal?.aborted) c.enqueue(utf8(' ')); r(); }, 50)); },
  }), { status, headers: { 'content-type': 'application/json' } });
  let mode: 'pass' | 'trickle403' | 'big401' | 'pingTrickle' = 'pass';
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    if (mode === 'pingTrickle' && url.pathname === '/bridge/ping') return trickle(200, '{"ok":true,"app":"hypernova"', init?.signal);
    if (url.pathname === '/bridge/ping' || mode === 'pass') return app.fetch(input, init);
    if (mode === 'trickle403') return trickle(403, '{"error":{"code":"bridge_access"', init?.signal);
    return new Response(new Uint8Array(MAX_UNSEALED + 1).fill(0x20), { status: 401, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
  const bridge = new Bridge(BRIDGE_URL, relay);
  const signer = new BridgeSigner(bridge, K1, app.clock);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: relay }, async () => {
    const within = async (f: () => Promise<unknown>, ms: number) => { const t0 = Date.now(); await assert.rejects(f()); assert.ok(Date.now() - t0 < ms, `took ${Date.now() - t0} ms`); };
    mode = 'trickle403';
    await within(() => api.post('/v1/echo', { a: 1 }, { timeoutMs: 300 }), 2000);
    mode = 'pass'; await signer.prove();
    mode = 'trickle403';
    await within(() => bridge.status(signer), 6000);
    mode = 'pass'; await signer.prove();
    mode = 'big401';
    await assert.rejects(api.post('/v1/echo', { a: 2 }), (e: Error & { problem?: string }) => e.problem === 'unreachable');
    mode = 'pingTrickle';
    signer.stale();
    const t0 = Date.now();
    assert.equal((await signer.prove()).app, null, 'a trickling ping is no app');
    assert.ok(Date.now() - t0 < PING_MS * 2 + 500);
    await assert.rejects(api.get('/v1/echo', { timeoutMs: 1000 }));
    mode = 'pass';
    assert.deepEqual(await api.get('/v1/usage'), { ok: true, path: '/v1/usage' }, 'and the signer isn’t wedged after');
  });
});

/** The fifth round: the poll's `settings` were unsealed and saved; now there are none there, and the sealed GET's are checked field by field. */
test('settings come only sealed, and every field of them is checked', async () => {
  const app = new FakeApp();
  // A relay rewrites the poll's answer with settings of its own: nothing of them is taken.
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    const res = await app.fetch(input, init);
    if (/^\/bridge\/request\/./.test(new URL(String(input)).pathname) && (init?.method ?? 'GET') === 'GET') {
      const j = await res.json();
      if (j.state === 'allowed') return json(200, { ...j, settings: { readAloud: true, language: 'zu', projects: ['nothing-of-yours'], guideAsk: 1e300, look: 'comic' } });
    }
    return res;
  }) as typeof fetch;
  const t = linkWith(app, { fetch: relay });
  const run = t.link.start();
  await until(() => t.link.state.kind === 'asking', 'asking');
  app.answer = 'allow';
  await run;
  confirmTap(t);
  await until(() => t.link.state.kind === 'bridge', 'connected');
  assert.equal(t.settings.length, 1);
  assert.deepEqual([t.settings[0].s.language, t.settings[0].s.projects, t.settings[0].s.guideAsk, t.settings[0].s.look, t.settings[0].s.readAloud], ['ru', ['hypernova'], 0, 'hypernova', false]);
  const s = readBridgeSettings({ guideAsk: 1e300, look: 'comic', projects: ['a'.repeat(200), 'b', 'b', ...Array.from({ length: 100 }, (_, i) => `p${i}`)], popups: ['x'] })!;
  assert.equal(s.guideAsk, 0);
  // A look that isn't one is the default: the glasses' own font.
  assert.equal(s.look, 'native');
  assert.equal(s.projects!.length, 64);
  assert.equal(s.projects![0].length, 80);
  assert.equal(readBridgeSettings({ guideAsk: 1_800_000_000_000.5 })!.guideAsk, 0);
  assert.equal(readBridgeSettings({ guideAsk: 1_800_000_000_000 })!.guideAsk, 1_800_000_000_000);
  assert.equal(readBridgeSettings([])!, null);
  assert.deepEqual(sharedPatch({ look: 'native', voice: 'phone' }), { look: 'native' });
});

/**
 * The fourth review (P2): words from an unsealed answer (a 403's message, the ping's version)
 * reached the lens, so a relay could show "Approve on your phone: Hypernova · Code 482913" while
 * asking the real app with a key of its own. Lens now has only its own words for what didn't open.
 */
test('nothing a relay writes is ever shown: unsealed refusals get Lens’s own words, the ping’s version only when it is one', async () => {
  const app = new FakeApp();
  app.allow(K1);
  const lure = 'Approve on your phone: Hypernova · Code 482913 · Allow Hypernova Lens to connect?';
  let mode: 'pass' | 'update' | 'access' | 'other' = 'pass';
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input));
    if (url.pathname === '/bridge/ping') {
      const res = await app.fetch(input, init);
      const j = await res.json();
      return json(200, { ...j, version: lure });
    }
    if (mode === 'pass') return app.fetch(input, init);
    const m = mode;
    mode = 'pass';
    if (m === 'update') return json(403, { error: { code: 'update_lens', message: lure } });
    if (m === 'access') return json(403, { error: { code: 'bridge_access', message: lure } });
    return json(500, { error: { code: 'boom', message: lure } });
  }) as typeof fetch;
  const bridge = new Bridge(BRIDGE_URL, relay);
  const signer = new BridgeSigner(bridge, K1, app.clock);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: relay }, async () => {
    assert.equal((await bridge.ping())!.version, '', 'a version that isn’t one is nothing');
    for (const m of ['update', 'access', 'other'] as const) {
      mode = m;
      await assert.rejects(api.post('/v1/echo', { text: 'hi' }), (e: Error) => { assert.ok(!e.message.includes('482913'), `shown: ${e.message}`); assert.ok(Object.values(WORDS).includes(e.message as never) || e.message === 'Disconnected on your phone.', e.message); return true; });
      mode = m;
      await assert.rejects(bridge.status(signer), (e: Error) => { assert.ok(!e.message.includes('482913'), `shown: ${e.message}`); return true; });
    }
  });
  const t = linkWith(app, { token: K1, fetch: (async () => json(200, { ok: true, app: 'hypernova', version: lure, bridge: 2, id: 'old' })) as typeof fetch });
  await t.link.start();
  assert.equal(t.link.state.kind, 'old_app');
  assert.ok(!linkMessage(t.link.state, 0, false)!.sub.includes('482913'));
  assert.ok(!cardWords(t.link.state, null, null, 0).sub.includes('482913'));
});

test('a sealed answer is read within the call’s deadline and only up to a cap', async () => {
  const small = new Response(new Uint8Array(100));
  assert.equal((await readCapped(small, 100))!.length, 100);
  assert.equal(await readCapped(new Response(new Uint8Array(101)), 100), null);
  assert.equal(await readCapped(new Response(new Uint8Array(10), { headers: { 'content-length': '999999999' } }), 100), null);
  // A sealed answer that never ends: the deadline cuts it, and Lens takes nothing.
  const endless = new ReadableStream<Uint8Array>({ pull(c) { c.enqueue(new Uint8Array(1024)); } });
  const app = new FakeApp();
  app.allow(K1);
  const slow = (async (input: string | URL | Request, init?: RequestInit) => {
    const res = await app.fetch(input, init);
    if (new URL(String(input)).pathname === '/bridge/ping') return res;
    return new Response(endless, { status: 200, headers: res.headers });
  }) as typeof fetch;
  const signer = new BridgeSigner(new Bridge(BRIDGE_URL, slow), K1, app.clock);
  const api = new Api(BRIDGE_URL, null, 'bridge', signer);
  await withGlobals({ WebSocket: undefined, fetch: slow }, async () => {
    const t0 = Date.now();
    await assert.rejects(api.get('/v1/usage', { timeoutMs: 300 }), (e: Error & { problem?: string }) => e.problem === 'timeout' || e.problem === 'unreachable');
    assert.ok(Date.now() - t0 < 5000, 'cut by the deadline, not read for ever');
  });
});

test('access: denied or unanswered waits for a tap; asking too often waits by itself', async () => {
  const app = new FakeApp();
  app.answer = 'deny';
  const t = linkWith(app);
  await t.link.start();
  assert.equal(t.link.state.kind, 'denied');
  assert.equal(linkMessage(t.link.state, 0, false)!.sub, 'Tap to ask again.');
  // A tap asks again.
  app.answer = 'expire';
  t.link.retry();
  await until(() => t.link.state.kind === 'expired', 'expired');
  // Asked too often: the app says when; Lens asks again by itself then.
  app.tooMany = 25;
  t.link.retry();
  await until(() => t.link.state.kind === 'busy', 'busy');
  assert.equal(t.later.at(-1)!.ms, 25_000);
  // However long it says (an unproven 429 once made Lens spin): an hour at most.
  app.tooMany = 3_000_000;
  t.later.at(-1)!.fn();
  await until(() => t.later.at(-1)?.ms === 3_600_000, 'clamped');
  assert.match(linkMessage(t.link.state, (t.link.state as unknown as { retryAt: number }).retryAt - 25_000, false)!.sub, /25 s/);
  app.tooMany = 0;
  app.answer = 'allow';
  t.later.at(-1)!.fn();
  await until(() => t.link.state.kind === 'confirm', 'allowed after the wait');
  confirmTap(t);
  await until(() => t.link.state.kind === 'bridge', 'connected after the wait');
  // A tap while connected asks nothing.
  const asked = app.asked.length;
  t.link.retry();
  await new Promise(r => setImmediate(r));
  assert.equal(app.asked.length, asked);
});

test('fallback: no app and Lens’s own pairing → straight to the Mac; no app and none → it keeps looking', async () => {
  const app = new FakeApp();
  app.up = false;
  const paired = linkWith(app, { pairing: true });
  await paired.link.start();
  assert.equal(paired.link.state.kind, 'direct');
  assert.equal(paired.later.length, 0);

  const lone = linkWith(app);
  await lone.link.start();
  assert.equal(lone.link.state.kind, 'no_app');
  assert.equal(linkMessage(lone.link.state, 0, false)!.title, 'Open Hypernova on your phone');
  assert.equal(lone.later.length, 1, 'it looks again in a few seconds');
  // Still nothing: the lens isn't told anything new.
  lone.later.pop()!.fn();
  await until(() => lone.later.length === 1, 'looking again');
  assert.deepEqual(lone.states, ['looking', 'no_app']);
  // The owner opens the app: Lens finds it and asks.
  app.up = true;
  app.answer = 'allow';
  lone.later.pop()!.fn();
  await until(() => lone.link.state.kind === 'confirm', 'found the app');
  confirmTap(lone);
  await until(() => lone.link.state.kind === 'bridge', 'connected');

  // Denied with a pairing of its own: that still works.
  const both = new FakeApp();
  both.answer = 'deny';
  const fb = linkWith(both, { pairing: true });
  await fb.link.start();
  assert.equal(fb.link.state.kind, 'direct');
});

test('revoked: Disconnect on the phone (or the Mac forgetting the glasses) waits for a tap, which asks again', async () => {
  const app = new FakeApp();
  app.allow('hnb_x');
  const t = linkWith(app, { token: 'hnb_x' });
  await t.link.start();
  app.keys.clear();
  await t.link.revoked();
  assert.equal(t.link.state.kind, 'revoked');
  assert.equal(linkMessage(t.link.state, 0, false)!.title, 'Disconnected on your phone');
  // The token stays until a new one is agreed (the word may have come from another app on the port).
  assert.equal(t.token(), 'hnb_x');
  t.link.retry();
  await until(() => t.link.state.kind === 'asking', 'asking again');
  app.answer = 'allow';
  await until(() => t.link.state.kind === 'confirm', 'allowed again');
  confirmTap(t);
  await until(() => t.link.state.kind === 'bridge', 'connected again');
  assert.notEqual(t.token(), 'hnb_x');
  // Leaving from the phone's card: the app forgets the key too.
  app.allow('hnb_y');
  const u = linkWith(app, { token: 'hnb_y' });
  await u.link.start();
  assert.equal(u.link.state.kind, 'bridge');
  await u.link.leave();
  assert.equal(app.keys.has(kidOf(nsha('hnb_y'))), false);
  assert.equal(u.link.state.kind, 'revoked');
});

// ------------------------------------------------------------------ the glasses' settings, kept in the app

test('settings: the app’s are checked, become Lens’s, and only what the app keeps goes back to it', () => {
  const s = readBridgeSettings({ readAloud: false, blip: 'x', textArrives: 'warp', pace: 9999, popups: { done: true, bogus: true }, projects: ['a', '', 3], language: 'Klingon', guideAsk: -1 })!;
  assert.equal(s.readAloud, false);
  assert.equal(s.blip, true);
  assert.equal(s.textArrives, 'all');
  assert.equal(s.pace, 260);
  assert.equal(s.popups.done, true);
  assert.equal(s.popups.needs, true);
  assert.ok(!('bogus' in s.popups));
  assert.deepEqual(s.projects, ['a']);
  assert.equal(s.language, 'en');
  assert.equal(s.guideAsk, 0);
  assert.equal(readBridgeSettings(null), null);
  const patch = lensPatch(s);
  assert.equal(patch.readAloud, false);
  assert.ok(!('guideAsk' in patch));
  assert.deepEqual(sharedPatch({ readAloud: true, voice: 'phone', guideOffered: true }), { readAloud: true });
  assert.equal(sharedPatch({ voice: 'phone' }), null);
});

test('settings: a speech companion address saved by an older Lens is forgotten on load, quietly', async () => {
  const kv = new Map<string, string>([['lens.settings', JSON.stringify({ language: 'ru', companionUrl: 'wss://m.ts.net:7443/ws?token=secret' })]]);
  const store = new Store();
  await store.load({ get: async k => kv.get(k) ?? '', set: async (k, v) => { kv.set(k, v); return true; } });
  assert.equal(store.settings.language, 'ru');
  assert.ok(!('companionUrl' in store.settings));
  await store.update({ blip: false });
  assert.ok(!(kv.get('lens.settings') ?? '').includes('secret'));
});

test('settings: the store takes the app’s copy, and its echo of a change is no change', async () => {
  const store = new Store();
  let told = 0;
  store.subscribe(() => told++);
  const s = readBridgeSettings(new FakeApp().settings)!;
  assert.equal(await store.applyRemote(lensPatch(s)), true);
  assert.equal(store.settings.textArrives, 'speech');
  assert.equal(store.settings.pace, 200);
  assert.equal(store.settings.eclipse, true);
  assert.deepEqual(store.settings.projects, ['hypernova']);
  assert.equal(store.settings.voice, 'auto', 'the voice stays Lens’s own');
  const before = told;
  assert.equal(await store.applyRemote(lensPatch(s)), false);
  assert.equal(told, before);
  await store.setBridgeToken(K1);
  const again = new Store();
  const kv = new Map<string, string>();
  await again.load({ get: async k => kv.get(k) ?? '', set: async (k, v) => { kv.set(k, v); return true; } });
  await again.setBridgeToken('hnb_saved');
  const third = new Store();
  await third.load({ get: async k => kv.get(k) ?? '', set: async (k, v) => { kv.set(k, v); return true; } });
  assert.equal(third.bridgeToken, 'hnb_saved');
});

test('settings: a change made on the glasses reaches the app', async () => {
  const app = new FakeApp();
  app.allow('hnb_z');
  const b = new Bridge(BRIDGE_URL, app.fetch);
  const back = await b.putSettings(new BridgeSigner(b, 'hnb_z'), sharedPatch({ readAloud: true, moving: true })!);
  assert.deepEqual(app.puts, [{ readAloud: true, moving: true }]);
  assert.equal(back?.moving, true);
  await assert.rejects(b.putSettings(new BridgeSigner(b, 'hnb_wrong'), { moving: false }), (e: Error & { code?: string }) => e.code === 'bridge_access');
});

test('Open Hypernova: Lens asks the app (its web view can’t open it), signed and sealed; without access, or an app from before, it says to open it by hand', async () => {
  const app = new FakeApp();
  app.allow('hnb_z');
  const b = new Bridge(BRIDGE_URL, app.fetch);
  const signer = new BridgeSigner(b, 'hnb_z');
  assert.equal(await b.open(signer), 'notification');
  assert.deepEqual(app.opens, ['glasses']);
  // Sealed like every other call: the screen asked for never shows on the wire, nor the answer.
  assert.ok(app.wire.every(w => !w.includes('"screen"')), 'the body went sealed');
  assert.ok(app.answers.every(a => !a.includes('notification')), 'the answer came sealed');
  assert.equal(openWords('notification'), 'Tap “Tap to open Hypernova” in your notifications.');
  app.openAnswer = 'app';
  assert.equal(await b.open(signer), 'app');
  app.openAnswer = 'nothing';
  assert.equal(await b.open(signer), 'nothing');
  assert.match(openWords('nothing'), /^Open Hypernova on your phone: Settings → Glasses\.$/);
  // No access: a refusal, and Lens's own words.
  await assert.rejects(b.open(new BridgeSigner(b, 'hnb_wrong')), (e: Error & { code?: string }) => e.code === 'bridge_access');
  // An app from before /bridge/open: its unsealed 404 is no answer of the app's; Lens says to open it.
  app.openAnswer = null;
  await assert.rejects(b.open(new BridgeSigner(b, 'hnb_z')), (e: Error & { code?: string }) => e.code === 'no_app');
  assert.equal(openWords(null), 'Open Hypernova on your phone.');
  // The app isn't there at all.
  app.up = false;
  await assert.rejects(b.open(new BridgeSigner(b, 'hnb_z')));
});

// ------------------------------------------------------------------ the hub through the app

const T0 = 1_800_000_000_000;
function snapshot(): Snapshot {
  return {
    mac: { id: 'm', name: 'MacBook Air', daemonVersion: '0' },
    usage: { fiveHour: { pct: 10, resetsAt: T0 + 4_000_000 }, sevenDay: null, source: 'official', observedAt: T0 },
    sessions: [], approvals: [], projects: [],
  };
}

/** A socket that opens and says `msgs`, or closes with `closeWith` instead; the URLs it was given are kept. */
function fakeSocket(script: Array<{ msgs?: object[]; closeWith?: number }>, urls: string[]) {
  let made = 0;
  return class FakeSocket {
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: ((e: { code: number }) => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(url: string) {
      urls.push(url);
      const step = script[Math.min(made++, script.length - 1)];
      setTimeout(() => {
        if (step.closeWith && !step.msgs) { this.onerror?.(); this.onclose?.({ code: step.closeWith }); return; }
        this.onopen?.();
        for (const m of step.msgs ?? []) this.onmessage?.({ data: JSON.stringify(m) });
        if (step.closeWith) setTimeout(() => this.onclose?.({ code: step.closeWith! }), 5);
      }, 5);
    }
    send(): void {}
    close(): void {}
  };
}

async function withGlobals<T>(o: { WebSocket: unknown; fetch: unknown }, fn: () => Promise<T>): Promise<T> {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = { WebSocket: g.WebSocket, fetch: g.fetch };
  g.WebSocket = o.WebSocket;
  g.fetch = o.fetch;
  try { return await fn(); } finally { g.WebSocket = saved.WebSocket; g.fetch = saved.fetch; }
}

/** Lens's route through `app`, as main.ts makes it: the app's address, no token, a signer. */
function viaApp(app: FakeApp, token = LIVE) {
  app.allow(token);
  return { baseUrl: BRIDGE_URL, token: '', macName: 'your Mac', macId: null, deviceId: null, signer: new BridgeSigner(new Bridge(BRIDGE_URL, app.fetch), token) };
}

test('hub through the app: its socket is signed (no token) and every message sealed; the settings come first, then the Mac', async () => {
  const urls: string[] = [];
  const settings = { t: 'bridge.settings', settings: { readAloud: false, pace: 150 } };
  const app = new FakeApp();
  const route = viaApp(app);
  const sent: string[] = [];
  const socket = app.socketClass([{ msgs: [settings, { t: 'snapshot', snapshot: snapshot() }] }], urls, sent);
  await withGlobals({ WebSocket: socket, fetch: async () => { throw new Error('no HTTP expected'); } }, async () => {
    const hub = new LiveHub(route, 'bridge');
    const seen: HubEvent[] = [];
    hub.subscribe(e => seen.push(e));
    hub.start();
    await until(() => hub.status === 'live', 'live');
    hub.stop();
    await new Promise(r => setTimeout(r, 20));
    assert.match(urls[0], /^ws:\/\/127\.0\.0\.1:47380\/v1\/ws\?hnb=[\w.-]+$/);
    // Lens closed it: a sealed end marker went first.
    assert.deepEqual(sent, ['end 1000']);
    neverSent([...urls, ...app.wire], LIVE);
    const s = seen.find(e => e.t === 'bridge_settings') as Extract<HubEvent, { t: 'bridge_settings' }>;
    assert.ok(s);
    assert.equal(s.settings.readAloud, false);
    assert.equal(s.settings.pace, 150);
    assert.equal(hub.macName, 'MacBook Air');
  });
  // Straight to a Mac, nobody else may send Lens settings on its feed.
  const urls2: string[] = [];
  await withGlobals({ WebSocket: fakeSocket([{ msgs: [settings, { t: 'snapshot', snapshot: snapshot() }] }], urls2), fetch: async () => { throw new Error('none'); } }, async () => {
    const hub = new LiveHub({ baseUrl: 'https://mac.example.ts.net:47321', token: 'device', macName: 'your Mac', macId: null, deviceId: null });
    const seen: HubEvent[] = [];
    hub.subscribe(e => seen.push(e));
    hub.start();
    await until(() => hub.status === 'live', 'live');
    hub.stop();
    assert.equal(urls2[0], 'wss://mac.example.ts.net:47321/v1/ws?token=device');
    assert.ok(!seen.some(e => e.t === 'bridge_settings'));
  });
});

test('hub through the app: Disconnect on the phone closes the feed (4003, sealed in its end marker), the app can’t prove the key, the hub says revoked', async () => {
  const urls: string[] = [];
  const app = new FakeApp();
  const route = viaApp(app);
  const socket = app.socketClass([{ msgs: [{ t: 'snapshot', snapshot: snapshot() }], closeWith: 4003 }, { closeWith: 1006 }], urls);
  await withGlobals({ WebSocket: socket, fetch: app.fetch }, async () => {
    const hub = new LiveHub(route, 'bridge');
    const seen: string[] = [];
    hub.subscribe(e => seen.push(e.t));
    hub.start();
    await until(() => hub.status === 'live', 'live');
    // Disconnect in Settings → Glasses: the app forgets the key and closes the feed.
    app.keys.clear();
    await until(() => seen.includes('revoked'), 'revoked');
    assert.equal(hub.status, 'unpaired');
    assert.equal(hub.problem, 'Disconnected on your phone.');
    hub.stop();
  });
});

/**
 * The socket's messages open only in order and only under this socket's key: a message slipped
 * in, changed, or sent in the clear ends the socket (as cut off: 1006), so the hub proves the app
 * again before the next; and a close without the app's sealed end marker is a cut too.
 */
test('hub through the app: a message that isn’t the app’s ends the socket, a close without the end marker is a cut', async () => {
  const app = new FakeApp();
  const route = viaApp(app);
  const key = app.keys.get(bridgeKey(LIVE).kid)!;
  let mode: 'plain' | 'other-key' | 'skip' | 'no-end' = 'plain';
  class Meddling {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    readyState = 0; binaryType = 'blob'; bufferedAmount = 0;
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: ArrayBuffer | string }) => void) | null = null;
    onclose: ((e: { code: number; reason: string }) => void) | null = null;
    onerror: (() => void) | null = null;
    closedWith: number | null = null;
    constructor(url: string) {
      const v = app.verify('GET', new URL(url.replace(/^ws/, 'http')), null) as { nonce: string };
      const ck = ncallKey(key, app.listener, v.nonce);
      const snap = Buffer.from(JSON.stringify({ t: 'snapshot', snapshot: snapshot() }));
      const frame = ab;
      setTimeout(() => {
        this.readyState = 1;
        this.onopen?.();
        this.onmessage?.({ data: frame(nframe(ck, FROM_APP, 0, TEXT, snap)) });
        if (mode === 'plain') this.onmessage?.({ data: JSON.stringify({ t: 'change' }) });
        if (mode === 'other-key') this.onmessage?.({ data: frame(nframe(nc.randomBytes(32), FROM_APP, 1, TEXT, snap)) });
        if (mode === 'skip') this.onmessage?.({ data: frame(nframe(ck, FROM_APP, 2, TEXT, snap)) });
        if (mode === 'no-end') setTimeout(() => this.close(1000), 5);
      }, 5);
    }
    send(): void {}
    close(code = 1000): void { if (this.readyState === 3) return; this.readyState = 3; this.closedWith = code; setTimeout(() => this.onclose?.({ code: 1000, reason: '' }), 1); }
  }
  for (const m of ['plain', 'other-key', 'skip', 'no-end'] as const) {
    mode = m;
    await withGlobals({ WebSocket: Meddling, fetch: app.fetch }, async () => {
      const hub = new LiveHub(route, 'bridge');
      const statuses: string[] = [];
      hub.subscribe(() => statuses.push(hub.status));
      hub.start();
      // Live on the app's own snapshot, then over at once: the socket is no longer the app's.
      await until(() => statuses.includes('live') && hub.status === 'offline', `live, then ended (${m}): ${statuses.join(',')}`);
      hub.stop();
    });
  }
});

test('a sealed socket sends only what will go: before it opens, an error; after it closed, nothing (and nothing counted)', async () => {
  const key = nc.randomBytes(32);
  const call = await CallKey.derive(key, 'L1', 'nonce-s-0001');
  const ck = ncallKey(key, 'L1', 'nonce-s-0001');
  const got: string[] = [];
  class Inner {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    readyState = 0; binaryType = 'blob'; bufferedAmount = 0;
    onopen: (() => void) | null = null; onmessage: unknown = null; onclose: ((e: { code: number; reason: string }) => void) | null = null; onerror: unknown = null;
    static last: Inner;
    constructor() { Inner.last = this; }
    open() { this.readyState = 1; this.onopen?.(); }
    send(d: ArrayBuffer) { got.push(`${new Uint8Array(d).length}`); }
    close() { this.readyState = 3; setTimeout(() => this.onclose?.({ code: 1000, reason: '' }), 1); }
  }
  const Sealed = sealedSocketClass(Inner as unknown as typeof WebSocket, call);
  const ws = new Sealed('ws://x');
  assert.throws(() => ws.send('too soon'), /InvalidStateError|connecting/);
  Inner.last.open();
  ws.send('{"type":"start"}');
  ws.send(new Uint8Array(4));
  await new Promise(r => setTimeout(r, 20));
  assert.equal(got.length, 2);
  // In order and counted from 0: the app opens them so.
  const frames = got.map((_, i) => i);
  assert.equal(frames.length, 2);
  Inner.last.close();
  await new Promise(r => setTimeout(r, 5));
  ws.send('after the close');
  await new Promise(r => setTimeout(r, 10));
  assert.equal(got.length, 2, 'dropped');
  void ck;
});

test('hub through the app: the app not answering is said as such; a preview opens through the app too', async () => {
  const app = new FakeApp();
  const route = viaApp(app);
  await withGlobals({ WebSocket: fakeSocket([{ closeWith: 1006 }], []), fetch: app.fetch }, async () => {
    const hub = new LiveHub(route, 'bridge');
    assert.equal(await hub.preview({ port: 5173 }), 'http://127.0.0.1:47380/p/tok123/index.html?x=1');
    app.up = false;
    await assert.rejects(hub.api.get('/v1/usage'), /Hypernova on this phone isn’t answering/);
    hub.stop();
  });
});

// ------------------------------------------------------------------ the phone's status card

test('the status card: connected, or what’s missing', () => {
  const live = { status: 'live', problem: null, macName: 'MacBook Air', snapshot: snapshot() } as unknown as HubLike;
  const st = { paired: true, mac: 'MacBook Air', link: 'connected', route: 'local', app: '1.4.0' } as const;
  const ok = cardWords({ kind: 'bridge', token: 't', app: '1.4.0' }, live, st, 0);
  assert.equal(ok.title, 'Connected to Hypernova ✓');
  assert.equal(ok.sub, 'MacBook Air, on the local network');
  assert.equal(ok.tone, 'ok');
  const asking = cardWords({ kind: 'asking', code: '482913', until: 0 }, null, null, 0);
  assert.equal(asking.title, 'Allow it on your phone');
  assert.equal(asking.code, '482913');
  // Hypernova's own notification is where it's answered: the card says so.
  assert.match(asking.sub, /notification: pull it down to allow/);
  assert.equal(asking.title, linkMessage({ kind: 'asking', code: '482913', until: 0 }, 0, false)!.title, 'the lens and the card say the same');
  assert.equal(cardWords({ kind: 'no_app' }, null, null, 0).title, 'Hypernova isn’t open on this phone');
  assert.equal(cardWords({ kind: 'bridge', token: 't', app: '' }, { status: 'connecting', problem: null, macName: '', snapshot: null } as unknown as HubLike, { ...st, paired: false }, 0).title, 'Hypernova isn’t paired with a Mac yet');
  const off = cardWords({ kind: 'bridge', token: 't', app: '' }, { status: 'offline', problem: 'Hypernova on your phone can’t reach your Mac right now.', macName: '', snapshot: null } as unknown as HubLike, st, 0);
  assert.equal(off.title, 'Hypernova can’t reach your Mac');
  assert.equal(off.tone, 'warn');
  assert.equal(cardWords({ kind: 'revoked' }, null, null, 0).title, 'Disconnected on your phone');
  // What a tap does then: it reconnects if Hypernova is back, asks again if it was disconnected.
  assert.match(cardWords({ kind: 'revoked' }, null, null, 0).sub, /isn’t running.*reconnects by itself.*asks to be allowed/);
  assert.equal(cardWords({ kind: 'bridge', token: 't', app: '1.4.0' }, live, { ...st, route: 'remote' }, 0).sub, 'MacBook Air, over the internet');
  assert.equal(cardWords({ kind: 'bridge', token: 't', app: '1.4.0' }, live, { ...st, route: 'tailscale' }, 0).sub, 'MacBook Air, over Tailscale');
  assert.equal(cardWords({ kind: 'old_lens' }, null, null, 0).title, 'Update Hypernova Lens');
  assert.equal(cardWords({ kind: 'unconfirmed' }, null, null, 0).title, 'Not confirmed on the glasses');
  // The app itself gone quiet is said as that, on the card and on the lens.
  const quiet = { status: 'offline', problem: WORDS.no_app, macName: '', snapshot: null } as unknown as HubLike;
  assert.equal(cardWords({ kind: 'bridge', token: 't', app: '' }, quiet, st, 0).title, 'Hypernova on this phone isn’t answering');
  assert.equal(cardWords({ kind: 'old_app', app: '1.3.0', protocol: 2 }, null, null, 0).title, 'Update Hypernova on this phone');
  assert.equal(cardWords({ kind: 'no_seal' }, null, null, 0).tone, 'warn');
});

// ------------------------------------------------------------------ an older Lens

test('an app newer than this Lens refuses it: “Update Hypernova Lens”, asked or signed, never an endless “not answered”', async () => {
  // The ask refused (400 update_lens): no prompt ever shows on the phone, so it isn't "not answered".
  const app = new FakeApp();
  let asks = 0;
  const refusing = (async (input: string | URL | Request, init?: RequestInit) => {
    if (new URL(String(input)).pathname === '/bridge/request' && (init?.method ?? 'GET') === 'POST') { asks++; return json(400, { error: { code: 'update_lens', message: 'Update Lens.' } }); }
    return app.fetch(input, init);
  }) as typeof fetch;
  const t = linkWith(app, { fetch: refusing });
  await t.link.start(true);
  assert.equal(t.link.state.kind, 'old_lens');
  const m = linkMessage(t.link.state, 0, false)!;
  assert.equal(m.title, 'Update Hypernova Lens');
  assert.match(m.sub, /needs a newer Lens\. Update it in the Even app/);
  assert.equal(t.later.length, 0, 'nothing asks again by itself');
  // A tap looks once more (an app put back to an older version would let it in); refused, it says the same.
  t.link.retry();
  await until(() => asks === 2 && t.link.state.kind === 'old_lens', 'once more, the same');
  assert.equal(t.later.length, 0);

  // Signed (403 update_lens on the settings): the same state, the token kept for the updated Lens.
  const app2 = new FakeApp();
  app2.allow(KEPT);
  const signed = (async (input: string | URL | Request, init?: RequestInit) => {
    if (new URL(String(input)).pathname === '/bridge/settings') return json(403, { error: { code: 'update_lens', message: 'Update Lens.' } });
    return app2.fetch(input, init);
  }) as typeof fetch;
  const w = linkWith(app2, { token: KEPT, fetch: signed });
  await w.link.start();
  assert.equal(w.link.state.kind, 'old_lens');
  assert.equal(w.token(), KEPT);

  // And while connected: the hub's call refused so says it to the link (main.ts), not "Can't reach your Mac".
  const app3 = new FakeApp();
  const route = viaApp(app3);
  const relay = (async (input: string | URL | Request, init?: RequestInit) => {
    if (new URL(String(input)).pathname === '/v1/snapshot') return json(403, { error: { code: 'update_lens', message: 'Update Lens.' } });
    return app3.fetch(input, init);
  }) as typeof fetch;
  await withGlobals({ WebSocket: fakeSocket([{ closeWith: 1006 }], []), fetch: relay }, async () => {
    const hub = new LiveHub(route, 'bridge');
    const seen: string[] = [];
    hub.subscribe(e => seen.push(e.t));
    await (hub as unknown as { checkHttp(): Promise<void> }).checkHttp();
    assert.ok(seen.includes('old_lens'), seen.join());
    hub.stop();
  });
});

test('the phone’s last provider and model go with a new session from the glasses, checked; never a mode', async () => {
  assert.deepEqual(readNewSession({ providerId: 'openrouter', model: 'openai/gpt-5' }), { providerId: 'openrouter', model: 'openai/gpt-5' });
  assert.deepEqual(readNewSession({ providerId: 'anthropic', model: null }), { providerId: 'anthropic', model: null });
  assert.equal(readNewSession({ providerId: '../etc', model: 'x' }), null);
  assert.equal(readNewSession({ model: 'opus' }), null);
  assert.equal(readNewSession('openrouter'), null);
  assert.deepEqual(readNewSession({ providerId: 'openrouter', model: 'bad\nmodel' }), { providerId: 'openrouter', model: null });
  // An app from before it says nothing: the Mac's defaults.
  assert.equal(readBridgeSettings({})!.newSession, null);
  assert.deepEqual(lensPatch(readBridgeSettings({ newSession: { providerId: 'openrouter', model: 'x-ai/grok-4', permissionMode: 'bypassPermissions' } })!).newSession, { providerId: 'openrouter', model: 'x-ai/grok-4' });
  // What goes to the Mac: the provider and the model, never a permission mode.
  assert.deepEqual(chosen({ providerId: 'openrouter', model: 'x-ai/grok-4' }), { providerId: 'openrouter', model: 'x-ai/grok-4' });
  assert.deepEqual(chosen({ providerId: 'anthropic', model: null }), { providerId: 'anthropic' });
  assert.deepEqual(chosen(null), {});
});
