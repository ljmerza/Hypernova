// Pop-ups on the glasses: a two-line band over whatever is on the lens, for what's worth knowing
// right away: a session needs you (it stays until you open it or swipe it away), one is done (it
// fades after about six seconds), one stopped, a limit hit or lifted, the Mac's battery at 20%, the
// Mac about to sleep. Tap opens, a swipe dismisses, several merge into one band. Nothing pops up
// while you talk or are being read to: they wait and show after. Each kind has a switch on the
// phone. Pure: the controller passes the time in.
import { ago } from './format.ts';
import type { Parts } from './font.ts';

export type PopKind = 'needs' | 'done' | 'stopped' | 'limit' | 'battery' | 'sleep' | 'note';
export const POP_KINDS: PopKind[] = ['needs', 'done', 'stopped', 'limit', 'battery', 'sleep', 'note'];
export type PopSettings = Record<PopKind, boolean>;

/**
 * Needs-you is on by default (the owner's call). So are the Mac's two warnings, which DECISIONS has
 * pop up by themselves (the battery at 20%, "Your Mac will sleep in 2 minutes"). Done, stopped and
 * the limits are a switch away: a finished turn already takes the glance. An automation's note for
 * the glasses is on: someone chose "read aloud on my glasses" for it.
 */
export const DEFAULT_POPS: PopSettings = { needs: true, done: false, stopped: false, limit: false, battery: true, sleep: true, note: true };

/** What the phone calls each kind. */
export const POP_NAMES: Record<PopKind, string> = {
  needs: 'A session needs you', done: 'A session is done', stopped: 'A session stopped',
  limit: 'A limit is hit or lifted', battery: 'The Mac’s battery at 20%', sleep: 'The Mac is about to sleep',
  note: 'An automation has news',
};

export interface Pop {
  /** One per thing: "needs:<approval id>", "done:<session>:<time>", "limit:<episode>"… The same key replaces. */
  key: string;
  kind: PopKind;
  /** The band's first line, its label then what it's about: ["NEEDS YOU", "0stems"]. */
  head: Parts;
  /** Its second: "wants to run git push origin main". */
  detail: string;
  /** In a merged band, and moving's one line: "0stems needs you". */
  short: string;
  /** What needs you: the project, so several merge into "0stems and hyperscribe need you". */
  who?: string;
  /** Moving's one line when its detail says it all (the Mac's battery); else "<short>: <detail>". */
  line?: string;
  sessionId?: string;
  /** When it came. */
  at: number;
  /** When it first showed: a fading one counts from here, not from when it came. */
  shownAt?: number;
  /** How long it shows; null: until it's opened or dismissed. */
  ttl: number | null;
  /** When it's over whether it showed or not (the Mac about to sleep: once it has, it's old news). */
  until?: number;
  /** An approval's: when it's denied by itself (the band says how soon, as the phone's does). */
  expiresAt?: number;
}

/** About six seconds for a turn that's done. */
export const DONE_MS = 6000;
/** A done or stopped pop-up that waited this long (you talked, or the lens was quiet) is old news. */
export const STALE_MS = 60_000;

/** What leads a merged band: what needs you, then the Mac about to sleep, then the rest. */
const RANK: Record<PopKind, number> = { needs: 0, sleep: 1, battery: 2, limit: 3, stopped: 4, done: 5, note: 6 };

export class PopQueue {
  private list: Pop[] = [];

  get size(): number { return this.list.length; }
  all(): readonly Pop[] { return this.list; }

  add(p: Pop): void {
    this.list = [...this.list.filter(x => x.key !== p.key), p];
  }

  /** Drop the ones `pred` picks (opened, answered elsewhere, a session gone). */
  drop(pred: (p: Pop) => boolean): boolean {
    const n = this.list.length;
    this.list = this.list.filter(p => !pred(p));
    return this.list.length !== n;
  }

  clear(): void { this.list = []; }

  /**
   * What the band shows at `now`, first the one that leads. `held` (you're talking, or being read
   * to, or the lens is quiet): nothing, and nothing starts fading. Faded ones go; done and stopped
   * ones that waited too long go without showing.
   */
  visible(now: number, held: boolean): Pop[] {
    this.list = this.list.filter(p => {
      if (p.until !== undefined && now >= p.until) return false;
      if (p.shownAt !== undefined) return p.ttl === null || now - p.shownAt < p.ttl;
      return !((p.kind === 'done' || p.kind === 'stopped') && now - p.at > STALE_MS);
    });
    if (held) return [];
    for (const p of this.list) if (p.shownAt === undefined) p.shownAt = now;
    return [...this.list].sort((a, b) => RANK[a.kind] - RANK[b.kind] || a.at - b.at);
  }

  /** When the next one fades, for a redraw then (null: none will by itself). */
  nextFade(): number | null {
    const at = this.list.flatMap(p => [p.ttl !== null && p.shownAt !== undefined ? p.shownAt + p.ttl : Infinity, p.until ?? Infinity]).filter(Number.isFinite);
    return at.length ? Math.min(...at) : null;
  }
}

/** "auto-deny in 9m": how soon an approval is denied by itself (the phone's words). */
export function autoDeny(expiresAt: number, now: number): string {
  const ms = expiresAt - now;
  return ms < 15_000 ? 'auto-deny now' : `auto-deny in ${ago(ms)}`;
}

/** "a", "a and b", "a, b and c". */
export function listed(xs: string[]): string {
  return xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs.at(-1)}`;
}

/**
 * The band's words: the leading pop-up's head (with "+2" when more merged in) and one line under it,
 * a phrase: what it says, or, when several need you, who ("0stems and hyperscribe need you"); else
 * what it says and the rest ("wants to push to main, and Mac at 20%"). `detailRight`, at that line's
 * end: how soon what needs you is denied by itself. `line`: all of it on one line (moving).
 */
export function bandWords(pops: readonly Pop[], now = Date.now()): { head: Parts; right: string; detail: string; detailRight?: string; line: string } | null {
  const [top, ...rest] = pops;
  if (!top) return null;
  const all = [top, ...rest];
  const needs = rest.length && all.every(p => p.kind === 'needs' && p.who) ? [...new Set(all.map(p => p.who!))] : null;
  const others = listed(rest.map(p => p.short));
  // A sentence (the battery's) gets another after it; a phrase (what a session wants) runs on.
  const detail = needs && needs.length > 1 ? `${listed(needs)} need you`
    : !rest.length ? top.detail : /[.!?…]$/.test(top.detail) ? `${top.detail} Also ${others}.` : `${top.detail}, and ${others}`;
  return {
    head: top.head,
    right: rest.length ? `+${rest.length}` : '',
    detail,
    ...(top.kind === 'needs' && top.expiresAt ? { detailRight: autoDeny(top.expiresAt, now) } : {}),
    // Moving's one line: "0stems needs you: wants to push to main"; a sentence that says it all, as it is.
    line: top.line ? (rest.length ? detail : top.line) : `${top.short}: ${detail}`,
  };
}
