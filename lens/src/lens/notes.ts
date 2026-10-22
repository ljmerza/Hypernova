// An automation's note on the glasses (docs/specs/spec-automations.md §8): its words, so the
// controller stays short. The card is `AUTOMATION   Morning brief` over the text, which scrolls as a
// result's reply does; a band says it while something else is on the lens. Pure.
import type { Pop } from '../core/popups.ts';
import type { AutomationNote } from '../core/types.ts';
import { firstSentence } from '../core/result.ts';
import { plainText, speakable } from '../core/text.ts';

/** The most of a name the header line has room for next to its age. */
const NAME_MAX = 22;

const short = (name: string) => (name.length > NAME_MAX ? `${name.slice(0, NAME_MAX - 1).trimEnd()}…` : name);

/** The card's head: its label and the automation's name, apart: ["AUTOMATION", "Morning brief"]. */
export function noteHead(n: AutomationNote): string[] { return ['AUTOMATION', short(n.name)]; }

/** The card's text, as plain lines of words. */
export function noteText(n: AutomationNote): string { return plainText(n.text).trim() || n.text.trim(); }

/** What's said aloud: the name, then the note ("Morning brief. Three pull requests are waiting."). */
export function noteSpoken(n: AutomationNote): string {
  const body = speakable(n.text).trim();
  return `${n.name.replace(/[.!?…:]+$/, '')}. ${body}`.trim();
}

/** The band for a note that arrived while something else is on the lens; a tap opens the card. */
export function noteBand(n: AutomationNote): Omit<Pop, 'at'> {
  return {
    key: `note:${n.id}`, kind: 'note', head: noteHead(n),
    detail: firstSentence(n.text, 120) || noteText(n).slice(0, 120), short: short(n.name), ttl: 12_000,
  };
}

/** The note id a band's key carries. */
export const noteIdOf = (key: string): string | null => (key.startsWith('note:') ? key.slice(5) : null);
