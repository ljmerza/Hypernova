// The Lens guide on the phone: the same three steps the Mac's "Hypernova Lens" window shows (what
// Lens is for, the gestures, voice), at the same place: a step taken here moves the Mac's window
// too, and "Show on my Mac" brings that window forward (DAEMON.md, "The Lens guide").
import { h, seam } from './dom.ts';
import { sonioxKeyField } from './speech-card.ts';
import type { Api } from '../data/api.ts';
import type { GuideState, GuideStep } from '../core/types.ts';

export const GUIDE_STEPS: GuideStep[] = ['about', 'gestures', 'voice'];

/**
 * The steps this showing of the guide has, fixed when it opens (DAEMON.md, "The Lens guide"): with
 * voice already working (Soniox ready on the Mac) there's nothing to set up,
 * so it's two steps and Gestures ends with Done. If the Mac's window moved to Voice meanwhile
 * ([step]), Voice comes back at the end.
 */
export function guideSteps(voiceReady: boolean, step: GuideStep): GuideStep[] {
  if (!voiceReady) return GUIDE_STEPS;
  return step === 'voice' ? ['about', 'gestures', 'voice'] : ['about', 'gestures'];
}

/** The words, kept the same as the Mac's window (mac/Sources/Hypernova/UI/LensGuideView.swift). */
export const GUIDE: Record<GuideStep, { rail: string; title: string; lede: string; items?: Array<[string, string]>; foot?: string }> = {
  about: {
    rail: 'What it’s for',
    title: 'Hypernova Lens.',
    lede: 'An on-the-go app for developers: wherever you are, you can still build products and see the results. The Mac does the work; the glasses are where you glance and decide.',
    items: [
      ['Glance', 'Your sessions at a look: who’s working, who needs you, how much of the 5 hours is left.'],
      ['Talk', 'Hold the temple and say what to do. Nothing is sent until you tap.'],
      ['See', 'Approve with a tap, hear replies read aloud, and say “show me” to see what was built.'],
    ],
  },
  gestures: {
    rail: 'Gestures',
    title: 'Four gestures.',
    lede: 'On the temple of the glasses. Under the lens mirror here, the same four are buttons.',
    items: [
      ['Hold', 'Talk to the session in front of you. Let go to read what you said.'],
      ['Tap', 'Open, send, allow, read aloud.'],
      ['Double tap', 'Back, discard, deny. On the glance, it quiets the lens.'],
      ['Swipe', 'Move between sessions and options, and scroll a reply.'],
    ],
    foot: 'You can also say “status”, “send it”, “show me” or “approve”.',
  },
  voice: {
    rail: 'Voice',
    title: 'Voice.',
    lede: 'Soniox turns what you say into text, live as you talk. Connect it once: the key stays on your Mac.',
  },
};

export interface GuideCardDeps {
  guide: GuideState;
  /** This showing's steps ([guideSteps]). */
  steps: GuideStep[];
  /** The Mac has Soniox ready; `sttKnown`: its Hypernova knows Soniox at all. */
  sttReady: boolean;
  sttKnown: boolean;
  /** Why a kept key isn't working (Soniox refused it, no balance), in the Mac's words. */
  sttError: string | null;
  macApi: Api | null;
  move(patch: Partial<GuideState>): void;
  showOnMac(): void;
  redraw(): void;
}

export function guideCard(d: GuideCardDeps): HTMLElement {
  const steps = d.steps;
  const step = steps.includes(d.guide.step) ? d.guide.step : steps[0]!;
  const i = Math.max(0, steps.indexOf(step));
  const c = GUIDE[step];
  const last = i === steps.length - 1;

  // The rail: done steps in mint, this one in ink, the rest as ghosts; a tap goes there.
  const rail = h('div', { class: 'guide-rail', role: 'tablist' }, ...steps.map((s, k) =>
    h('button', { class: k < i ? 'done' : k === i ? 'on' : '', role: 'tab', 'aria-selected': String(k === i), onClick: () => d.move({ step: s }) },
      h('span', { class: 'n' }, `0${k + 1}`), GUIDE[s].rail)));

  let body: HTMLElement | null = null;
  if (c.items) body = h('dl', { class: 'gestures guide-items' }, ...c.items.flatMap(([k, v]) => [h('dt', {}, k), h('dd', {}, v)]));
  if (step === 'voice') {
    body = d.sttReady
      ? h('p', { class: 'guide-ready' }, h('span', { class: 'dot' }), 'Soniox is connected. Hold the temple and talk.')
      : h('div', {},
          d.sttError ? h('div', { class: 'note', style: { margin: '0 0 4px' } }, d.sttError) : null,
          d.sttKnown || !d.macApi ? sonioxKeyField(d, 'guide') : h('p', { class: 'small muted', style: { margin: '0' } }, 'Update Hypernova on your Mac to connect Soniox.'));
  }

  return h('section', { class: 'section guide' },
    seam('Lens guide', `${i + 1} of ${steps.length}`, 'live'),
    h('div', { class: 'hn-card guide-card' },
      rail,
      h('h2', { class: 'title' }, c.title),
      h('p', { class: 'muted guide-lede' }, c.lede),
      body,
      c.foot ? h('p', { class: 'small dim', style: { margin: '12px 2px 0' } }, c.foot) : null,
      h('div', { class: 'row between guide-foot' },
        h('button', { class: 'hn-pill ghost small', onClick: () => d.showOnMac() }, 'Show on my Mac'),
        h('span', { class: 'row' },
          i > 0 ? h('button', { class: 'hn-pill ghost small', onClick: () => d.move({ step: steps[i - 1] }) }, 'Back') : null,
          h('button', { class: 'hn-pill primary small', onClick: () => d.move(last ? { done: true } : { step: steps[i + 1] }) }, last ? 'Done' : 'Next')))));
}
