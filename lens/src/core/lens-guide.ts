// The guide on the glasses themselves: each gesture tried on the temple and checked as you do it
// (tap, double tap, swipe, hold), then a few words said for practice: what was heard shows, nothing
// goes to Claude, and it can be skipped because voice isn't needed to use Lens. The first run offers
// it once; "guide" plays it again. What each gesture does to the guide is here, pure; the controller
// draws it and keeps the time.

/** `speak`: words heard on the spoken step (any words: it's practice). */
export type GuideGesture = 'tap' | 'double' | 'swipe' | 'hold' | 'speak';

export interface GuideStepText {
  gesture: GuideGesture;
  /** On the top line, after "GUIDE 1/5": "TAP". */
  title: string;
  /** What to do, on the lens' first line. */
  ask: string;
  /** Said once it's done: what the gesture is for. */
  done: string;
  /** A spoken step: voice isn't required, so double tap skips it. */
  voice?: boolean;
}

export const GUIDE_STEPS: GuideStepText[] = [
  { gesture: 'tap', title: 'TAP', ask: 'Tap the temple once.', done: 'A tap opens, sends and allows.' },
  { gesture: 'double', title: 'DOUBLE TAP', ask: 'Now tap twice, quickly.', done: 'A double tap goes back, discards and denies.' },
  { gesture: 'swipe', title: 'SWIPE', ask: 'Swipe along the temple, either way.', done: 'Swipes move between sessions and options, and scroll.' },
  { gesture: 'hold', title: 'HOLD', ask: 'Hold the temple, then let go.', done: 'Hold and speak to say what to do. Let go: it shows, then sends.' },
  // Practice: whatever is said shows and ticks the step, and nothing is sent (the owner's words to
  // the guide once went to a session). The done line teaches what a real prompt does after the release.
  { gesture: 'speak', title: 'SAY IT', ask: 'Hold, say a few words, let go. Nothing is sent.', done: 'A prompt shows like this, then goes: tap sends at once, double tap cancels.', voice: true },
];

export const GUIDE_OFFER = {
  head: 'LENS GUIDE',
  lines: ['New to the glasses? Learn the temple in a minute:', 'tap, double tap, swipe, hold, and your voice.'],
  hint: ['tap start', 'double tap not now'],
};

export const GUIDE_DONE = {
  head: ['GUIDE', 'DONE'],
  lines: ['You’re set.', 'Say “guide” to see it again.', 'On a bike or a walk: “moving”, “debrief”, “note, …”.'],
  hint: 'tap or double tap to close',
};

/** Where the guide is: -1 the offer, 0…n-1 a step, n done. `checked`: this step was just done. */
export interface GuideState { step: number; checked: boolean; note: string }

export type GuideOutcome =
  /** The step is done: show its line a moment, then the next. */
  | { kind: 'checked'; state: GuideState }
  /** Not this step's gesture: say which it was, and try again. */
  | { kind: 'again'; state: GuideState }
  /** Start the steps (from the offer), or go on to the next one. */
  | { kind: 'next'; state: GuideState }
  /** Leave the guide (not now, done, or a double tap once double tap has been learned). */
  | { kind: 'leave' }
  /** Hold: only the hold and the spoken step use it; elsewhere it's ignored. */
  | { kind: 'ignore' };

const NAME: Record<GuideGesture, string> = { tap: 'a tap', double: 'a double tap', swipe: 'a swipe', hold: 'a hold', speak: 'speech' };
const AGAIN: Record<GuideGesture, string> = { tap: 'Just one tap.', double: 'Two taps, close together.', swipe: 'Slide your finger along the temple.', hold: 'Keep your finger on the temple a moment, then let go.', speak: 'Hold, say a few words, let go.' };

/** What a gesture does in the guide at `s`. */
export function guideGesture(s: GuideState, g: GuideGesture): GuideOutcome {
  const n = GUIDE_STEPS.length;
  if (s.step < 0) return g === 'tap' ? { kind: 'next', state: { step: 0, checked: false, note: '' } } : g === 'double' ? { kind: 'leave' } : { kind: 'ignore' };
  if (s.step >= n) return g === 'tap' || g === 'double' ? { kind: 'leave' } : { kind: 'ignore' };
  const step = GUIDE_STEPS[s.step];
  // Already done and showing why: any gesture moves on rather than waiting, and the next step's own
  // gesture counts for it (the glasses may send a tap just before a double tap).
  if (s.checked) {
    const next = GUIDE_STEPS[s.step + 1];
    if (next && g === next.gesture) return { kind: 'checked', state: { step: s.step + 1, checked: true, note: next.done } };
    return g === 'hold' ? { kind: 'ignore' } : { kind: 'next', state: { step: s.step + 1, checked: false, note: '' } };
  }
  if (g === step.gesture) return { kind: 'checked', state: { ...s, checked: true, note: step.done } };
  // Double tap, once learned, leaves; on the spoken step it skips it (voice isn't required).
  if (g === 'double' && step.voice) return { kind: 'next', state: { step: s.step + 1, checked: false, note: '' } };
  if (g === 'double' && s.step > GUIDE_STEPS.findIndex(x => x.gesture === 'double')) return { kind: 'leave' };
  if (g === 'speak') return { kind: 'ignore' };
  return { kind: 'again', state: { ...s, note: `That was ${NAME[g]}. ${AGAIN[step.gesture]}` } };
}

/** The guide's progress at the right of its top line: ●●○○○. */
export function guideDots(s: GuideState): string {
  const n = GUIDE_STEPS.length;
  const done = Math.max(0, Math.min(n, s.step + (s.checked ? 1 : 0)));
  return '●'.repeat(done) + '○'.repeat(n - done);
}

/** The hint under a step, its parts apart: how to leave, or skip the spoken one. */
export function guideHint(s: GuideState, voiceReady: boolean): string[] {
  const step = GUIDE_STEPS[s.step];
  if (!step) return [];
  if (s.checked) return ['any gesture: next'];
  if (step.voice) return voiceReady ? ['double tap skips: voice isn’t required'] : ['no voice set up yet', 'double tap skips'];
  return s.step > GUIDE_STEPS.findIndex(x => x.gesture === 'double') ? ['double tap leaves the guide'] : [];
}
