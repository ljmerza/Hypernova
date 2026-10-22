import test from 'node:test';
import assert from 'node:assert/strict';
import { DISK_IN, DISK_RIM, diskSpan, outburstFront } from '../src/lens/orrery.ts';

test('the disk’s bright span grows from the centre with the budget left, by area', () => {
  assert.equal(diskSpan(0), 0);
  assert.ok(Math.abs(diskSpan(1) - DISK_RIM) < 1e-9, 'a fresh window is the whole disk');
  let last = diskSpan(0);
  for (let k = 1; k <= 20; k++) { const s = diskSpan(k / 20); assert.ok(s > last); last = s; }
  // The last tenth is a thin inner ring, not a tenth of the width.
  assert.ok(diskSpan(0.1) < DISK_IN + 0.3 * (DISK_RIM - DISK_IN));
  assert.ok(diskSpan(0.5) > diskSpan(0.1) + 2);
  assert.equal(diskSpan(2), diskSpan(1));
  assert.equal(diskSpan(-1), 0);
});

test('the outburst lights the span rim-inward in three frames', () => {
  assert.deepEqual([0, 0.2, 0.34, 0.5, 0.67, 0.9, 1, 3].map(outburstFront).map(x => Math.round(x * 3)), [1, 1, 2, 2, 3, 3, 3, 3]);
});
