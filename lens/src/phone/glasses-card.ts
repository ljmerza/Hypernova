// Settings on the phone for what the glasses do by themselves: where new sessions are born (Nebula,
// on the Mac), how text arrives (all at once, or like speech, and how fast), and which pop-ups show.
import { h, toast } from './dom.ts';
import { explain, type Api } from '../data/api.ts';
import { getNebula, setNebula, type NebulaInfo } from '../data/nebula.ts';
import { DEFAULT_NEBULA } from '../core/nebula.ts';
import { DEFAULT_POPS, POP_KINDS, POP_NAMES } from '../core/popups.ts';
import { PACE_MAX, PACE_MIN } from '../core/transmission.ts';
import type { Settings, Store } from '../data/store.ts';
import { lookOf } from '../lens/look.ts';

export interface GlassesCardDeps {
  store: Store;
  /** Plays the guide on the glasses (the gestures, tried and checked). */
  startGuide?(): void;
  /** The Mac's API (null with sample data). */
  macApi: Api | null;
  demo: boolean;
  redraw(): void;
}

/** Nebula as the Mac last said, kept across redraws (Settings redraw when sessions come and go). */
let nebula: NebulaInfo | null = null;
let nebulaOf: Api | null = null;
/** The Mac answered that it has no Nebula (a Hypernova from before it). */
let nebulaMissing = false;
/** The folder field, kept so a half-typed path survives a redraw. */
let field: HTMLInputElement | null = null;

/** The cards, in the order they show under Settings. */
export function glassesCards(d: GlassesCardDeps): HTMLElement[] {
  return [nebulaCard(d), moveCard(d), textCard(d), lookCard(d), popupCard(d)];
}

/**
 * On a bike or a walk: moving mode and eclipse (also "moving" / "still" and "eclipse" / "eclipse
 * off" on the glasses), and the guide on the glasses, to try the gestures again.
 */
function moveCard(d: GlassesCardDeps): HTMLElement {
  const { store } = d;
  const s = store.settings;
  const sw = (on: boolean, label: string, flip: () => void) => h('button', { class: `switch ${on ? 'on' : ''}`, role: 'switch', 'aria-checked': String(on), 'aria-label': label, onClick: flip });
  const row = (title: string, sub: string, on: boolean, flip: () => void) => h('div', { class: 'row between', style: { marginTop: '12px', gap: '12px' } },
    h('div', {}, h('div', { class: 'small' }, title), h('div', { class: 'dim small' }, sub)), sw(on, title, flip));
  return h('div', { class: 'hn-card' },
    h('div', { class: 'hn-heading' }, 'On a bike or a walk'),
    row('Moving', 'Two short lines on the lens, one sentence per result, a chime per pop-up. Risky commands only by saying “approve push”.', s.moving, () => void store.update({ moving: !s.moving })),
    row('Eclipse', 'Only what needs you gets through, and the Mac’s low-battery and about-to-sleep warnings: no other pop-ups or sounds.', s.eclipse, () => void store.update({ eclipse: !s.eclipse })),
    h('p', { class: 'dim small', style: { margin: '12px 2px 0' } }, 'Say “debrief” for a catch-up, “note, …” to keep an idea in Nebula, “what changed?” and “show diff”, “one”, “two” or “three” to answer, “plan first” or “go ahead”, “continue”.'),
    d.startGuide ? h('div', { class: 'row', style: { marginTop: '12px' } }, h('button', { class: 'hn-pill ghost small', onClick: () => d.startGuide!() }, 'Practise the gestures on the glasses')) : null);
}

function nebulaCard(d: GlassesCardDeps): HTMLElement {
  const api = d.macApi;
  if (api && nebulaOf !== api) {
    nebulaOf = api;
    // A Mac that's away is asked again on a later redraw.
    void getNebula(api).then(n => { nebula = n; nebulaMissing = !n; d.redraw(); }).catch(() => { nebulaOf = null; });
  }
  const shown = d.demo ? DEFAULT_NEBULA : nebula?.path ?? '';
  if (!field) field = h('input', { class: 'input', placeholder: DEFAULT_NEBULA, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': 'Nebula folder' }) as HTMLInputElement;
  if (document.activeElement !== field) field.value = shown;
  const save = async () => {
    const path = field!.value.trim();
    if (!path) return;
    if (!api) { toast('Sample data has no Mac to keep it.'); return; }
    try { nebula = await setNebula(api, path); toast(`New sessions start in ${nebula.path}`); d.redraw(); } catch (err) { toast(explain(err), 4200); }
  };
  const old = !!api && !d.demo && nebulaMissing;
  return h('div', { class: 'hn-card' },
    h('div', { class: 'hn-heading' }, 'New sessions from the glasses'),
    h('p', { class: 'small muted', style: { margin: '6px 2px 0' } },
      'Hold the temple on the main screen and speak: the session starts in a folder of its own in Nebula, named from what you said, with git set up. When something needs you, the hold talks to it instead.'),
    h('label', { class: 'field' }, h('span', { class: 'hn-label' }, 'Nebula, on the Mac'),
      h('div', { class: 'row between' }, field, h('button', { class: 'hn-pill outline small', onClick: () => void save() }, 'Save'))),
    old ? h('div', { class: 'note' }, 'This Mac’s Hypernova is older than Nebula. Update it to start sessions from the glasses.')
      : nebula && !nebula.exists ? h('p', { class: 'dim small', style: { margin: '8px 2px 0' } }, 'Made when the first session starts there.') : null);
}

function textCard(d: GlassesCardDeps): HTMLElement {
  const { store } = d;
  const s = store.settings;
  const speech = s.textArrives === 'speech';
  const pace = h('input', { type: 'range', min: String(PACE_MIN), max: String(PACE_MAX), step: '10', value: String(s.pace), 'aria-label': 'Pace',
    onChange: (e: Event) => void store.update({ pace: Number((e.target as HTMLInputElement).value) }) });
  return h('div', { class: 'hn-card' },
    h('div', { class: 'hn-label' }, 'Text arrives'),
    h('div', { class: 'seg', style: { marginTop: '10px' } },
      h('button', { class: speech ? '' : 'on', onClick: () => void store.update({ textArrives: 'all' }) }, 'All at once'),
      h('button', { class: speech ? 'on' : '', onClick: () => void store.update({ textArrives: 'speech' }) }, 'Like speech')),
    h('p', { class: 'dim small', style: { margin: '10px 2px 0' } }, speech
      ? 'Replies come a few at a time, with pauses at commas and full stops, in step with the voice when a reply is read aloud. Code and approvals show at once; a tap shows the rest.'
      : 'Text shows as soon as it arrives.'),
    speech ? h('label', { class: 'field' }, h('span', { class: 'hn-label' }, `Pace · ${s.pace} words a minute`), pace) : null);
}

/**
 * Type on the lens (lens/look.ts): the glasses' built-in font throughout (the default), or Hypernova,
 * the hybrid (the glance and approvals drawn in the app's type, replies and the rest in the glasses' font).
 */
function lookCard(d: GlassesCardDeps): HTMLElement {
  const { store } = d;
  const look = lookOf(store.settings);
  const set = (l: Settings['look']) => void store.update({ look: l });
  const forced = /[?&]look=/.test(location.search);
  return h('div', { class: 'hn-card' },
    h('div', { class: 'hn-label' }, 'Type on the lens'),
    h('div', { class: 'seg', style: { marginTop: '10px' } },
      h('button', { class: look === 'native' ? 'on' : '', onClick: () => set('native') }, 'Built-in'),
      h('button', { class: look !== 'native' ? 'on' : '', onClick: () => set('hypernova') }, 'Hypernova')),
    h('p', { class: 'dim small', style: { margin: '10px 2px 0' } }, look !== 'native'
      ? 'The glance and approvals in the app’s own type, with spending as a meter, drawn on the phone and sent as pictures. Replies and the live view keep the glasses’ font: there a new line or a swipe is one small update.'
      : 'The glasses’ own font everywhere: one size, every change a small text update, so the lens keeps up.'),
    forced ? h('p', { class: 'dim small', style: { margin: '6px 2px 0' } }, 'The address sets it for now (?look=).') : null);
}

function popupCard(d: GlassesCardDeps): HTMLElement {
  const { store } = d;
  const on = { ...DEFAULT_POPS, ...store.settings.popups };
  return h('div', { class: 'hn-card' },
    h('div', { class: 'hn-label' }, 'Pop-ups on the glasses'),
    h('p', { class: 'dim small', style: { margin: '8px 2px 4px' } }, 'A band over whatever the lens shows: tap opens it, a swipe puts it away. None while you talk or are read to; they wait.'),
    ...POP_KINDS.map(k => h('div', { class: 'row between', style: { marginTop: '10px' } },
      h('span', { class: 'small' }, POP_NAMES[k]),
      h('button', { class: `switch ${on[k] ? 'on' : ''}`, role: 'switch', 'aria-checked': String(on[k]), 'aria-label': POP_NAMES[k],
        onClick: () => void store.update({ popups: { ...on, [k]: !on[k] } }) }))));
}
