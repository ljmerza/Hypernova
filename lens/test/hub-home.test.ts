// The hub and the Mac's news part A reads (DAEMON.md 10): the account (limits), Stay awake, the Mac
// about to sleep; and the calls it makes: a new session in Nebula, Stay awake's mode.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { LiveHub, type HubEvent } from '../src/data/hub.ts';
import type { Snapshot } from '../src/core/types.ts';

const BASE = 'https://mac.example.ts.net:47321';
const snap: Snapshot = {
  mac: { id: 'm', name: 'Mac', daemonVersion: '0' }, usage: { fiveHour: { pct: 10, resetsAt: Date.now() + 3600_000 }, sevenDay: null, source: 'official', observedAt: null },
  sessions: [], approvals: [], projects: [],
};

/** A socket that opens and says `messages`, one after another. */
function socket(messages: unknown[]) {
  return class {
    onopen: (() => void) | null = null;
    onmessage: ((e: { data: string }) => void) | null = null;
    onclose: ((e: { code: number }) => void) | null = null;
    onerror: (() => void) | null = null;
    constructor() {
      setTimeout(() => {
        this.onopen?.();
        for (const m of messages) this.onmessage?.({ data: JSON.stringify(m) });
      }, 5);
    }
    send(): void {}
    close(): void {}
  };
}

test('the hub keeps the account and Stay awake, and passes on the Mac about to sleep', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.WebSocket;
  const awake = { mode: 'backpack', holding: true, lidClosedOk: true, backpackReady: true, until: null, reason: null };
  g.WebSocket = socket([
    { t: 'snapshot', snapshot: snap },
    { t: 'account', account: { state: 'limited', since: 1, limit: { window: 'five_hour', label: 'session limit', resetsAt: 2, autoResumeAt: null, usingCredits: false } } },
    { t: 'awake', awake },
    { t: 'mac_sleep_soon', inSeconds: 120, reason: 'hot', percent: 48 },
  ]);
  try {
    const hub = new LiveHub({ baseUrl: BASE, token: 't', macName: 'Mac', macId: null, deviceId: null });
    const events: HubEvent[] = [];
    hub.subscribe(e => events.push(e));
    hub.start();
    await new Promise(r => setTimeout(r, 30));
    assert.equal(hub.snapshot?.account?.state, 'limited');
    assert.deepEqual(hub.snapshot?.mac.awake, awake);
    assert.deepEqual(events.find(e => e.t === 'sleep_soon'), { t: 'sleep_soon', inSeconds: 120, reason: 'hot', percent: 48, stillAwake: false, otherApp: null });
    hub.stop();
  } finally { g.WebSocket = saved; }
});

test('a new session in Nebula and Stay awake’s mode, as the daemon takes them', async () => {
  const g = globalThis as unknown as Record<string, unknown>;
  const saved = g.fetch;
  const sent: Array<{ path: string; body: unknown }> = [];
  g.fetch = async (url: string, init: { body?: string }) => {
    const path = new URL(url).pathname;
    sent.push({ path, body: init.body ? JSON.parse(init.body) : null });
    const body = path === '/v1/mac/awake' ? { awake: { mode: 'off', holding: false, lidClosedOk: false, backpackReady: true, until: null, reason: null } } : { session: null };
    return new Response(JSON.stringify(body), { status: 200 });
  };
  try {
    const hub = new LiveHub({ baseUrl: BASE, token: 't', macName: 'Mac', macId: null, deviceId: null });
    await hub.newNebulaSession('landing-page-bakery', 'build a landing page for my bakery');
    const awake = await hub.setAwake('off');
    assert.deepEqual(sent, [
      { path: '/v1/sessions', body: { newProject: { name: 'landing-page-bakery', git: true, nebula: true }, prompt: 'build a landing page for my bakery' } },
      { path: '/v1/mac/awake', body: { mode: 'off' } },
    ]);
    assert.equal(awake?.mode, 'off');
  } finally { g.fetch = saved; }
});
