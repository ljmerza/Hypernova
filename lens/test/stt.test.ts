// Speech through the Mac (Soniox, relayed by the daemon; DAEMON.md, "Speech to text"): the stream's
// address, audio held until the Mac says "started", captions, the last words on release, the Mac's
// own words when it refuses, and which engine a hold uses. The daemon is a stand-in that speaks the
// stream protocol as the real one does (daemon/test/stt.test.ts holds the daemon to it).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import nc from 'node:crypto';
import { AutoSpeech, MacSpeech, macEndpoint, macWords, savedWords } from '../src/data/stt.ts';
import { MacLostError } from '../src/data/mac-stream.ts';
import { FINAL_WAIT_MS, RELEASE_TAIL_MS, SpeechError, type MicSource, type SpeechEngine } from '../src/data/speech.ts';
import { CallKey } from '../src/data/seal.ts';
import type { Pairing } from '../src/data/store.ts';
import { BINARY, END, FROM_APP, FROM_LENS, TEXT, ncallKey, nframe, nopenFrame } from './seal-helpers.ts';

/**
 * A Mac's stream in-process: says hello (or refuses), starts, captions each frame with the next
 * word, and finishes on stop, the way daemon/src/stt.ts answers. Like Soniox through it, the words
 * still being worked out when the audio ends (`tail`) come only `tailMs` after the stop;
 * `neverFinish`: they never do.
 */
function fakeMac(o: { refuse?: string; words?: string[]; tail?: string[]; tailMs?: number; neverFinish?: boolean; sealWith?: () => Uint8Array | null } = {}) {
  const seen = { url: '', start: null as any, audio: [] as number[], stops: 0, order: [] as string[], ends: [] as number[] };
  const words = [...(o.words ?? ['Run', 'the', 'tests'])];
  const said: string[] = [];
  class FakeSocket {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    readyState = 0; binaryType = 'blob'; bufferedAmount = 0;
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: unknown }) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: ((e: { code: number }) => void) | null = null;
    /** Through the app: this socket's key, and the counters each way (the app's side, with node:crypto). */
    private ck: Uint8Array | null = null;
    private out = 0;
    private got = 0;
    constructor(url: string) {
      seen.url = url;
      this.ck = o.sealWith?.() ?? null;
      setTimeout(() => {
        this.readyState = 1;
        this.onopen?.();
        if (o.refuse) { this.emit({ type: 'error', code: 'no_key', message: o.refuse }); this.close(1000); return; }
        this.emit({ type: 'hello', ok: true, name: 'Hypernova', model: 'stt-rt-v5', modelLabel: 'Soniox', languages: '*' });
      }, 5);
    }
    private emit(m: object): void {
      const text = JSON.stringify(m);
      if (!this.ck) { setTimeout(() => this.onmessage?.({ data: text }), 1); return; }
      const f = nframe(this.ck, FROM_APP, this.out++, TEXT, Buffer.from(text));
      setTimeout(() => this.onmessage?.({ data: f.buffer.slice(f.byteOffset, f.byteOffset + f.byteLength) }), 1);
    }
    send(raw: unknown): void {
      let data = raw;
      if (this.ck) {
        // Everything from Lens comes sealed, in order; the end marker says why it closes.
        const f = nopenFrame(this.ck, FROM_LENS, this.got++, new Uint8Array(raw as ArrayBuffer));
        if (f.kind === END) { seen.ends.push(f.payload.readUInt16BE(0)); return; }
        data = f.kind === TEXT ? f.payload.toString() : f.payload.buffer.slice(f.payload.byteOffset, f.payload.byteOffset + f.payload.byteLength);
        if (f.kind === BINARY) seen.order.push('sealed audio');
      }
      if (typeof data !== 'string') {
        seen.audio.push((data as ArrayBuffer).byteLength);
        seen.order.push('audio');
        const w = words.shift();
        if (w) { said.push(w); this.emit({ type: 'snapshot', finalText: said.slice(0, -1).join(' '), interimText: said.at(-1), finished: false }); }
        return;
      }
      const m = JSON.parse(data);
      if (m.type === 'start') { seen.start = m; setTimeout(() => this.emit({ type: 'started' }), 20); }
      if (m.type === 'ping') this.emit({ type: 'pong' });
      if (m.type === 'stop') {
        seen.stops++;
        seen.order.push('stop');
        if (o.neverFinish) return;
        setTimeout(() => { this.emit({ type: 'snapshot', finalText: [...said, ...(o.tail ?? [])].join(' '), interimText: '', finished: true }); this.close(1000); }, o.tailMs ?? 0);
      }
    }
    close(code = 1000): void {
      if (this.readyState === 3) return;
      this.readyState = 3;
      setTimeout(() => this.onclose?.({ code }), 1);
    }
  }
  const pairing: Pairing = { baseUrl: 'http://127.0.0.1:47371', token: 'tok en/1', macName: 'Mac', macId: null, deviceId: null };
  return { seen, pairing, socket: FakeSocket as unknown as typeof WebSocket };
}

/** The glasses' microphone: frames only when the test says so. */
function mic() {
  let fn: ((pcm: Uint8Array) => void) | null = null;
  let on = false;
  const m: MicSource & { frame(n?: number): void; on: () => boolean } = {
    async start() { on = true; return true; },
    async stop() { on = false; },
    onPcm(f) { fn = f; },
    frame(n = 1280) { if (on) fn?.(new Uint8Array(n)); },
    on: () => on,
  };
  return m;
}

const until = async (ok: () => boolean, ms = 3000) => { const t = Date.now(); while (!ok()) { if (Date.now() - t > ms) throw new Error('timed out'); await new Promise(r => setTimeout(r, 10)); } };

test('the stream lives next to the rest of the Mac: wss through Tailscale, the token in the query', () => {
  const ep = macEndpoint({ baseUrl: 'https://mac.tail.ts.net:47321', token: 'a b+c', macName: 'Mac', macId: null, deviceId: null });
  assert.equal(ep.wsUrl, 'wss://mac.tail.ts.net:47321/v1/stt/ws?token=a%20b%2Bc');
  assert.equal(ep.host, 'mac.tail.ts.net:47321');
  assert.equal(macEndpoint({ baseUrl: 'http://127.0.0.1:47371/', token: 't', macName: '', macId: null, deviceId: null }).wsUrl, 'ws://127.0.0.1:47371/v1/stt/ws?token=t');
});

test('through the Hypernova app on the phone: each stream waits for its listener to prove itself and is sealed under its own key; one that can’t gets no stream, so no audio', async () => {
  const key = nc.randomBytes(32);
  let proves = 0, can = true, ck: Uint8Array | null = null;
  const { seen, socket } = fakeMac({ words: ['Run', 'the', 'tests'], sealWith: () => ck });
  const m = mic();
  // Lens's signer (data/bridge.ts) as far as a stream goes.
  const signer = {
    async ready(fresh?: boolean) {
      assert.equal(fresh, true, 'proved right before, whatever came lately');
      proves++;
      if (!can) throw Object.assign(new Error('no proof'), { code: 'bridge_access' });
    },
    prepare: async () => { throw new Error('not here'); },
    async socket(path: string) {
      const nonce = `nonce-${proves}-abcdefghij`;
      ck = ncallKey(key, 'L1', nonce);
      return { url: `ws://127.0.0.1:47380${path}?hnb=signed-${proves}`, call: await CallKey.derive(key, 'L1', nonce) };
    },
    stale() {},
    reproved: async () => false,
    answered: async (res: Response) => res,
  };
  const pairing: Pairing = { baseUrl: 'http://127.0.0.1:47380', token: '', macName: 'Mac', macId: null, deviceId: null, signer };
  const speech = new MacSpeech(m, () => pairing, () => 'en', socket);
  const captions: string[] = [];
  await speech.start((f, i) => captions.push(`${f}|${i}`));
  assert.equal(proves, 1);
  assert.equal(seen.url, 'ws://127.0.0.1:47380/v1/stt/ws?hnb=signed-1', 'signed for the listener that proved itself, no token');
  // The audio goes sealed, the words come back sealed, and the stop ends with a sealed end marker.
  m.frame(); m.frame(); m.frame();
  await until(() => captions.length >= 3);
  const heard = await speech.stop();
  assert.equal(heard.text, 'Run the tests');
  assert.deepEqual(seen.audio, [1280, 1280, 1280]);
  assert.ok(seen.order.includes('sealed audio'));
  // (The Mac closes first after its last words, so Lens has no end to mark; bridge.test.ts has Lens's own end marker.)
  // The app went, and something else answers on its port: it can't prove the key.
  can = false;
  seen.url = '';
  await assert.rejects(speech.start(() => {}), (e: unknown) => e instanceof SpeechError && /isn’t answering/.test(e.message));
  assert.equal(seen.url, '', 'no stream opened');
  assert.equal(m.on(), false, 'the microphone never started');
});

test('a hold through the Mac: audio waits for "started", captions come as words settle, release gives the whole text', async () => {
  const { seen, pairing, socket } = fakeMac();
  const m = mic();
  const speech = new MacSpeech(m, () => pairing, () => 'ru', socket);
  const captions: string[] = [];
  const started = speech.start((f, i) => captions.push(`${f}|${i}`));
  // Spoken before the Mac said "started": held, then sent.
  await until(() => m.on());
  m.frame(); m.frame();
  await started;
  assert.equal(seen.start.language, 'ru');
  assert.equal(seen.url, 'ws://127.0.0.1:47371/v1/stt/ws?token=tok%20en%2F1');
  m.frame();
  await until(() => captions.length >= 3);
  assert.deepEqual(captions, ['|Run', 'Run|the', 'Run the|tests']);
  assert.deepEqual(seen.audio, [1280, 1280, 1280]);
  assert.deepEqual(await speech.stop(), { text: 'Run the tests', complete: true });
  assert.equal(seen.stops, 1);
  assert.equal(m.on(), false, 'the microphone is off again');
});

test('the release: the microphone stays on a moment, then the end of the audio, then the Mac’s last words, which come after it, are waited for', async () => {
  // The last word was still being said, and its audio still on its way, when the temple was let go;
  // Soniox settles the last words only once it knows the audio ended.
  const { seen, pairing, socket } = fakeMac({ words: ['Run', 'the'], tail: ['whole', 'suite'], tailMs: 600 });
  const m = mic();
  const speech = new MacSpeech(m, () => pairing, () => 'en', socket);
  const captions: string[] = [];
  await speech.start((f, i) => captions.push([f, i].filter(Boolean).join(' ')));
  m.frame(); m.frame();
  await until(() => captions.length >= 2);
  const t0 = Date.now();
  const stopping = speech.stop();
  assert.equal(speech.listening, false);
  await new Promise(r => setTimeout(r, RELEASE_TAIL_MS / 2));
  assert.equal(m.on(), true, 'still on just after the release');
  m.frame();                                       // the late frame still goes, before the end of the audio
  const heard = await stopping;
  const took = Date.now() - t0;
  assert.deepEqual(heard, { text: 'Run the whole suite', complete: true });
  assert.deepEqual(seen.order, ['audio', 'audio', 'audio', 'stop']);
  assert.ok(took >= RELEASE_TAIL_MS + 600 - 20 && took < RELEASE_TAIL_MS + 600 + 500, `waited ${took} ms for the last words`);
  assert.equal(m.on(), false);
});

test('the cap: when the last words never come back, what was heard is kept after FINAL_WAIT_MS, marked incomplete', async () => {
  const { pairing, socket } = fakeMac({ words: ['Deploy', 'the', 'staging'], neverFinish: true });
  const m = mic();
  const speech = new MacSpeech(m, () => pairing, () => 'en', socket);
  speech.tailMs = 0;
  const captions: string[] = [];
  await speech.start((f, i) => captions.push(`${f}|${i}`));
  m.frame(); m.frame(); m.frame();
  await until(() => captions.length >= 3);
  const t0 = Date.now();
  const heard = await speech.stop();
  const took = Date.now() - t0;
  assert.deepEqual(heard, { text: 'Deploy the staging', complete: false });
  assert.ok(took >= FINAL_WAIT_MS - 20 && took < FINAL_WAIT_MS + 600, `gave up after ${took} ms`);
});

test('a Mac that refuses (no Soniox key): its own words', async () => {
  const { pairing, socket } = fakeMac({ refuse: 'Connect Soniox in Hypernova on your Mac first.' });
  const m = mic();
  const speech = new MacSpeech(m, () => pairing, () => 'en', socket);
  await assert.rejects(speech.start(() => {}), (err: unknown) => err instanceof SpeechError && err.message === 'Connect Soniox in Hypernova on your Mac first.');
  assert.equal(m.on(), false);
  assert.match(macWords(new MacLostError('Could not connect to x', 'unreachable')), /Can’t reach your Mac/);
  await assert.rejects(new MacSpeech(m, () => null, () => 'en').start(() => {}), /Pair Lens/);
});

test('each hold uses Soniox while the Mac has it and keeps it until the hold ends; with none it says what the Mac needs', async () => {
  const calls: string[] = [];
  const mac: SpeechEngine = {
    kind: 'mac', listening: false,
    async start() { calls.push('mac start'); },
    async stop() { calls.push('mac stop'); return { text: 'mac', complete: true }; },
    cancel() { calls.push('mac cancel'); },
  };
  let ready = true;
  const auto = new AutoSpeech(() => (ready ? mac : null));
  assert.equal(auto.source(), 'mac');
  await auto.start(() => {});
  ready = false; // the Mac lost its key mid-hold
  assert.equal(auto.source(), 'mac');
  assert.equal((await auto.stop()).text, 'mac');
  assert.equal(auto.source(), 'none');
  await assert.rejects(auto.start(() => {}), (err: unknown) => err instanceof SpeechError && /Soniox on your Mac/.test(err.message) && /Settings, Speech/.test(err.message));
  assert.deepEqual(calls, ['mac start', 'mac stop']);
});

test('a saved key in the Mac’s words: ready, untried, kept while Soniox was busy, or the account’s problem', () => {
  const base = { provider: 'soniox' as const, last4: 'abcd', error: null };
  assert.equal(savedWords({ ...base, ready: true, checked: true, problem: null }), 'Soniox connected. Hold the temple and talk.');
  assert.equal(savedWords({ ...base, ready: true, checked: false, problem: 'Couldn’t reach Soniox from this Mac. Is it online?' }), 'Saved. Soniox couldn’t be reached to check it just now.');
  assert.equal(savedWords({ ...base, ready: true, checked: true, problem: 'Soniox is at its limit of streams at once. Try again in a moment.' }), 'Saved. Soniox is at its limit of streams at once. Try again in a moment.');
  assert.equal(savedWords({ ...base, ready: false, checked: true, problem: null, error: 'Your Soniox balance is used up. Add credit at console.soniox.com.' }), 'Your Soniox balance is used up. Add credit at console.soniox.com.');
});
