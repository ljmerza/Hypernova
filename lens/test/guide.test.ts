// The Lens guide on the phone page: with voice already working it has two steps, not three
// (docs/specs/spec-voice.md, 4.3; DAEMON.md, "The Lens guide").
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { GUIDE_STEPS, guideSteps } from '../src/phone/guide.ts';

test('voice not ready: all three steps, Voice last', () => {
  assert.deepEqual(guideSteps(false, 'about'), ['about', 'gestures', 'voice']);
  assert.deepEqual(guideSteps(false, 'voice'), GUIDE_STEPS);
});

test('voice ready (Soniox on the Mac): Gestures ends it', () => {
  assert.deepEqual(guideSteps(true, 'about'), ['about', 'gestures']);
  assert.deepEqual(guideSteps(true, 'gestures'), ['about', 'gestures']);
});

test('voice ready, but the guide is on Voice (asked for from the other screen): Voice comes back at the end', () => {
  assert.deepEqual(guideSteps(true, 'voice'), ['about', 'gestures', 'voice']);
});
