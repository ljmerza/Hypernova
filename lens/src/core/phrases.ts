// The words for using Lens on a bike or a walk (DECISIONS, "Glasses for cycling and walking"):
// "debrief", "note: …", "next" / "back", "one" / "two" / "three", "plan first" / "go ahead",
// "continue", "eclipse" / "eclipse off", "moving" / "still", "what changed?", "show diff", "guide",
// and "approve push" for a risky command. Each is a whole utterance, so a sentence that merely starts
// with one of them stays a prompt. Auto and Bypass are never chosen by voice: those words get an
// answer that says so. Pure.
import type { Utterance, VoiceWord } from './voice.ts';
import { RISK_WORDS } from './risk.ts';

const ROAD: Array<[VoiceWord, RegExp]> = [
  ['debrief', /^(debrief|debrief me|give me a debrief|catch me up|brief me|what did i miss|what have i missed|anything new)$/],
  ['next', /^(next|next one|next page|next please|forward)$/],
  ['previous', /^(previous|previous one|previous page|go up)$/],
  ['back', /^(back|go back|back out|answer later|later)$/],
  ['plan', /^(plan first|plan it first|make a plan first|plan mode|planning mode)$/],
  ['goahead', /^(go ahead|go ahead and do it|ask mode|default mode|normal mode)$/],
  ['continue', /^(continue|carry on|keep going|resume|continue please)$/],
  ['eclipse', /^(eclipse|eclipse on|eclipse mode|start eclipse|do not disturb|don't disturb me|dont disturb me)$/],
  ['eclipseoff', /^(eclipse off|end eclipse|stop eclipse|no eclipse|eclipse over|disturb me)$/],
  ['moving', /^(moving|i'm moving|im moving|on the move|moving mode|cycling|i'm cycling|im cycling|walking|i'm walking|im walking)$/],
  ['still', /^(still|i'm still|im still|standing still|still mode|full screen)$/],
  ['changes', /^(what changed|what's changed|whats changed|what has changed|what did it change|what did you change|changes|what's new in the code)$/],
  // "show me the diff" stays a prompt to Claude, as every "show me …" with more words does.
  ['diff', /^(show diff|show the diff|show diffs|diff|show changes|show the changes|the diff)$/],
  ['guide', /^(guide|lens guide|the guide|show the guide|show me the guide|tutorial|how does this work)$/],
  // Never by voice: the phone asks you to think twice first.
  ['refused', /^(auto|auto mode|bypass|bypass mode|bypass permissions|skip permissions|yolo|yolo mode|switch to auto|switch to bypass|go auto)$/],
];

const NUMBERS: Record<string, number> = {
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, '1': 1, '2': 2, '3': 3, '4': 4, '5': 5, '6': 6,
  first: 1, second: 2, third: 3, fourth: 4, fifth: 5, sixth: 6, won: 1, to: 2, too: 2, for: 4,
};

/** "one", "option two", "number 3", "the second one": which option, 1-based. */
export function optionNumber(whole: string): number | null {
  const m = /^(?:(?:pick|choose|answer|option|number|the|pick option|choose option)\s+)?(\w+)(?:\s+(?:one|option))?$/.exec(whole);
  if (!m) return null;
  const n = NUMBERS[m[1]];
  // "to", "too", "for" and "won" only as recognisers mishear a bare number, never after other words.
  if (!n || (/^(won|to|too|for)$/.test(m[1]) && whole !== m[1])) return null;
  return n;
}

/** "Note: …", "note to self …", "take a note …": the idea, as said (punctuation kept). */
const NOTE = /^\s*(?:(?:note|idea)\s*[,:;.!-]+|(?:note to self|take a note|make a note|save a note|new note|jot (?:this |that )?down)\s*[,:;.!-]*)\s*(.*)$/i;

/** A phrase of the cycling-and-walking set, or null. `whole`: the words, lowercase, fillers gone. */
export function parseRoad(raw: string, whole: string): Utterance | null {
  const text = raw.replace(/^\s*(?:(?:ok|okay|hey|hi)(?:\s+(?:hypernova|lens))?|hypernova|please)\b[\s,.!]*/i, '');
  for (const [word, re] of ROAD) if (re.test(whole)) return { kind: 'word', word, rest: '' };
  const n = optionNumber(whole);
  if (n) return { kind: 'word', word: 'option', rest: '', n };
  // "approve push", "approve the deploy", "allow rm": a risky command's own verb.
  const verb = /^(?:approve|allow|yes approve)\s+(?:the\s+|a\s+)?(.+)$/.exec(whole);
  if (verb && RISK_WORDS.includes(verb[1])) return { kind: 'word', word: 'approve', rest: verb[1] };
  if (/^(note|take a note|make a note|note to self|new note)$/.test(whole)) return { kind: 'word', word: 'note', rest: '' };
  const note = NOTE.exec(text);
  if (note && note[1].trim()) return { kind: 'word', word: 'note', rest: note[1].trim() };
  return null;
}
