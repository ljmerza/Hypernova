// The glasses as the Even App reports them: connected or not, and their battery, from the SDK's own
// status objects (which fill in what a push leaves out).
import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { DeviceInfo, DeviceStatus, type EvenAppBridge } from '@evenrealities/even_hub_sdk';
import { G2, type G2Handlers } from '../src/lens/g2.ts';

/** An Even App that knows `info` and pushes statuses when told to. */
function glasses(info: DeviceInfo | null) {
  let push: (s: DeviceStatus) => void = () => {};
  const bridge = {
    onEvenHubEvent: () => () => {},
    onDeviceStatusChanged: (fn: (s: DeviceStatus) => void) => { push = fn; return () => {}; },
    getDeviceInfo: async () => info,
  } as unknown as EvenAppBridge;
  const seen = { battery: 0, glasses: [] as boolean[] };
  const h: G2Handlers = {
    onTap() {}, onDoubleTap() {}, onUp() {}, onDown() {}, onHold() {}, onRelease() {}, onMenu() {}, onResync() {}, onExit() {},
    onGlasses: on => { seen.glasses.push(on); },
    onBattery: () => { seen.battery++; },
  };
  const g2 = new G2(bridge, h, [], 99);
  const status = (json: Record<string, unknown>) => push(DeviceStatus.fromJson({ sn: 'G2', ...json }));
  return { g2, seen, status };
}

const flush = async () => { for (let i = 0; i < 5; i++) await Promise.resolve(); };

test('a status without a level, or without a connection, keeps the glasses\' last battery level', async () => {
  // The bridge's answers are given a few seconds: none of that waiting here.
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    // The Even App knows the glasses but has no status for them yet: connected, no level.
    const { g2, seen, status } = glasses(DeviceInfo.fromJson({ sn: 'G2', model: 'g2' }));
    await flush();
    assert.equal(g2.glasses, true);
    assert.equal(g2.battery, null);
    assert.equal(seen.battery, 0);
    status({ connectType: 'connected', batteryLevel: 84 });
    assert.deepEqual(g2.battery, { percent: 84, charging: false });
    assert.equal(seen.battery, 1);
    // Connected, with no level said (the SDK writes 0), and a push only about being worn ('none').
    status({ connectType: 'connected' });
    status({ isWearing: true });
    assert.deepEqual(g2.battery, { percent: 84, charging: false });
    assert.equal(seen.battery, 1, 'no news, so no redraw');
    status({ connectType: 'connected', batteryLevel: 83 });
    assert.deepEqual(g2.battery, { percent: 83, charging: false });
    // Gone: their level goes with them, and a push that says nothing doesn't bring them back.
    status({ connectType: 'disconnected' });
    assert.equal(g2.glasses, false);
    assert.equal(g2.battery, null);
    status({ isWearing: true, batteryLevel: 50 });
    assert.equal(g2.glasses, false);
    assert.equal(g2.battery, null);
    // Back, first without a level, then with one on the charger.
    status({ connectType: 'connected' });
    assert.equal(g2.glasses, true);
    assert.equal(g2.battery, null);
    status({ connectType: 'connected', batteryLevel: 40, isCharging: true });
    assert.deepEqual(g2.battery, { percent: 40, charging: true });
    assert.deepEqual(seen.glasses, [true, false, true]);
  } finally {
    mock.timers.reset();
  }
});

test('the microphone: frames still on their way when the temple is let go count until the glasses say it is off, and none after', async () => {
  mock.timers.enable({ apis: ['setTimeout'] });
  try {
    let push: (ev: unknown) => void = () => {};
    let answer: (ok: boolean) => void = () => {};
    const calls: boolean[] = [];
    const bridge = {
      onEvenHubEvent: (fn: (ev: unknown) => void) => { push = fn; return () => {}; },
      onDeviceStatusChanged: () => () => {},
      getDeviceInfo: async () => null,
      createStartUpPageContainer: async () => 0,
      rebuildPageContainer: async () => true,
      // Switching the microphone off takes a Bluetooth round trip; the test answers it.
      audioControl: async (on: boolean) => { calls.push(on); return on ? true : new Promise<boolean>(r => { answer = r; }); },
    } as unknown as EvenAppBridge;
    const h: G2Handlers = { onTap() {}, onDoubleTap() {}, onUp() {}, onDown() {}, onHold() {}, onRelease() {}, onMenu() {}, onResync() {}, onExit() {}, onGlasses() {} };
    const g2 = new G2(bridge, h, [], 99);
    const got: number[] = [];
    g2.onPcm(pcm => got.push(pcm.byteLength));
    const frame = (n: number) => push({ audioEvent: { audioPcm: new Uint8Array(n) } });
    frame(2);                                       // the microphone isn't on yet
    assert.equal(await g2.start(), true);
    frame(1280);
    const stopping = g2.stop();
    void g2.stop();                                 // asked twice: told once
    frame(640);                                     // said before the release, arriving after it
    answer(true);
    await stopping;
    frame(320);                                     // the glasses said it's off
    assert.deepEqual(got, [1280, 640]);
    assert.deepEqual(calls, [true, false]);
  } finally {
    mock.timers.reset();
  }
});
