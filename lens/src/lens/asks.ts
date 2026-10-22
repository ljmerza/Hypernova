// An automation's ask on the glasses (core/ask.ts): its words, so the controller stays short.
// The card is `ASK   Review before merge` over the question, the answers to pick and the default's
// clock; a band says it while something else is on the lens. Pure.
import type { Pop } from '../core/popups.ts';
import type { AskCard } from '../core/ask.ts';
import { firstSentence } from '../core/result.ts';

/** The most of a name the header line has room for next to the clock. */
const NAME_MAX = 20;

const short = (name: string) => (name.length > NAME_MAX ? `${name.slice(0, NAME_MAX - 1).trimEnd()}…` : name);

/** The card's head: ["ASK", "Review before merge"]. */
export function askHead(c: Pick<AskCard, 'name'>): string[] { return ['ASK', short(c.name)]; }

/** The band for an ask while something else is on the lens; a tap opens the card, and it goes at the deadline. */
export function askBand(c: AskCard): Omit<Pop, 'at'> {
  return {
    key: `ask:${c.key}`, kind: 'needs', head: askHead(c), detail: firstSentence(c.question, 120) || c.question.slice(0, 120),
    short: `${short(c.name)} asks`, who: short(c.name), ttl: null, until: c.until,
  };
}
