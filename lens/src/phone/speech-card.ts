// Settings → Speech on the phone: which way speech is recognised, the Soniox key (sent to the Mac,
// which keeps it; this phone never stores it) and the language.
import { h, toast } from './dom.ts';
import { explain, type Api } from '../data/api.ts';
import { saveSonioxKey, savedWords, testStt, type SpeechSource } from '../data/stt.ts';
import type { Store } from '../data/store.ts';
import type { SttState } from '../core/types.ts';

const LANGUAGES: Array<[string, string]> = [['en', 'English'], ['auto', 'Detect'], ['ru', 'Russian'], ['de', 'German'], ['fr', 'French'], ['es', 'Spanish'], ['it', 'Italian'], ['uk', 'Ukrainian']];

export interface SpeechCardDeps {
  store: Store;
  /** The Mac's speech state; undefined when its Hypernova predates Soniox. */
  stt: SttState | undefined;
  demo: boolean;
  source: SpeechSource;
  macApi: Api | null;
  redraw(): void;
}

/** "Replace key" shows the field again while a key is connected. */
let replacing = false;

/**
 * The key fields, one per place, kept across redraws: the page redraws Settings whenever a session
 * starts or ends, and a key half pasted (or the keyboard open on it) mustn't vanish then.
 */
const keyFields = new Map<string, { el: HTMLElement; input: HTMLInputElement; deps: Pick<SpeechCardDeps, 'macApi' | 'redraw'> }>();

/**
 * The Soniox key field: Connect sends it to the Mac, which tries it with Soniox before keeping it.
 * [place] names where it shows (Settings, the guide), so each keeps its own text.
 */
export function sonioxKeyField(d: Pick<SpeechCardDeps, 'macApi' | 'redraw'>, place: 'settings' | 'guide', label = 'Soniox API key'): HTMLElement {
  const kept = keyFields.get(place);
  if (kept) {
    kept.deps = d;
    // The same element goes into the new card; if it had the keyboard, it gets it back there.
    const input = kept.input;
    if (document.activeElement === input) {
      const at = [input.selectionStart, input.selectionEnd];
      queueMicrotask(() => { if (input.isConnected) { input.focus({ preventScroll: true }); try { input.setSelectionRange(at[0], at[1]); } catch { /* not a text field any more */ } } });
    }
    return kept.el;
  }
  const input = h('input', { class: 'input', type: 'password', placeholder: 'Paste your key', autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': label }) as HTMLInputElement;
  const go = h('button', { class: 'hn-pill primary small' }, 'Connect');
  const connect = async () => {
    const key = input.value.trim();
    if (!key) { toast('Paste your Soniox key first.'); return; }
    const { macApi: api, redraw } = keyFields.get(place)!.deps;
    if (!api) { toast('Sample data has no Mac to keep a key.'); return; }
    go.textContent = 'Checking…';
    go.setAttribute('disabled', '');
    try {
      const r = await saveSonioxKey(api, key);
      input.value = '';
      replacing = false;
      toast(savedWords(r), 4200);
      redraw();
    } catch (err) {
      toast(explain(err), 4800);
    } finally {
      go.textContent = 'Connect';
      go.removeAttribute('disabled');
    }
  };
  go.addEventListener('click', () => void connect());
  input.addEventListener('keydown', e => { if ((e as KeyboardEvent).key === 'Enter') void connect(); });
  const el = h('label', { class: 'field' }, h('span', { class: 'hn-label' }, label),
    h('div', { class: 'row between' }, input, go),
    h('span', { class: 'small dim', style: { display: 'block', margin: '8px 2px 0' } }, 'Get one at ', h('span', { class: 'mono-inline' }, 'console.soniox.com'), '. It goes to your Mac and stays there.'));
  keyFields.set(place, { el, input, deps: d });
  return el;
}

export function speechCard(d: SpeechCardDeps): HTMLElement {
  const s = d.store.settings;
  const stt = d.stt;

  // What a hold uses now, in one sentence.
  const now: Record<SpeechSource, string> = {
    mac: 'Soniox, through your Mac: words appear as you talk.',
    demo: 'Sample data types a sentence for you when you hold.',
    none: stt || d.demo ? 'Not set up yet. Connect Soniox below: voice goes through your Mac.' : 'Not set up yet. Voice goes through your Mac: update Hypernova there, then connect Soniox here.',
  };
  const chip = h('span', { class: 'hn-chip' }, d.source === 'mac' ? 'soniox' : d.source === 'demo' ? 'sample' : 'off');
  chip.style.setProperty('--c', d.source === 'none' ? 'var(--hn-dim)' : 'var(--hn-accent)');

  // Soniox: only when the Mac's Hypernova knows it (a Mac from before has no `stt`).
  let soniox: HTMLElement | null = null;
  if (stt || d.demo) {
    const testOut = h('div', { class: 'small dim', style: { marginTop: '8px' } });
    // A kept key, working or not: Test tries it again (after adding credit, say), Replace swaps it.
    soniox = stt?.provider && !replacing
      ? h('div', {},
          h('div', { class: 'row', style: { marginTop: '12px' } },
            h('button', { class: 'hn-pill outline small', onClick: async () => {
              if (!d.macApi) return;
              testOut.textContent = 'Testing…';
              testOut.className = 'small dim';
              try { const r = await testStt(d.macApi); testOut.textContent = r.message; testOut.className = `small ${r.ok ? 'ok' : 'bad'}`; } catch (err) { testOut.textContent = explain(err); testOut.className = 'small bad'; }
            } }, 'Test'),
            h('button', { class: 'hn-pill ghost small', onClick: () => { replacing = true; d.redraw(); } }, 'Replace key')),
          testOut)
      : h('div', {}, sonioxKeyField(d, 'settings'),
          replacing ? h('button', { class: 'hn-pill ghost small', style: { marginTop: '10px' }, onClick: () => { replacing = false; d.redraw(); } }, 'Keep the current key') : null);
  }

  return h('div', { class: 'hn-card speech-card' },
    h('div', { class: 'row between' }, h('div', { class: 'hn-label' }, 'Speech'), chip),
    h('p', { class: 'small muted', style: { margin: '8px 2px 0' } }, now[d.source]),
    stt?.error ? h('div', { class: 'note' }, stt.error) : null,
    soniox,
    h('label', { class: 'field' }, h('span', { class: 'hn-label' }, 'Language'),
      h('select', { class: 'input', onChange: (e: Event) => void d.store.update({ language: (e.target as HTMLSelectElement).value }) },
        ...LANGUAGES.map(([v, n]) => h('option', { value: v, ...(v === s.language ? { selected: 'selected' } : {}) }, n)))));
}
