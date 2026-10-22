// Limits as the phone shows them, in a few words for the lens: the 5-hour window and the week, a
// session paused at a limit ("session limit until 2:00"), an account that can't reply at all
// (signed out, no plan, on hold, out of credits), another provider's trouble. And the sun in the planet column, which says the
// same as the phone's sky: the accretion disk while the window lasts, its quiescence (a dim disk, the
// bright spot) at the limit, the week's low state when the week is spent (no stream, no disk: the white
// dwarf a faint point), an eclipse while usage can't be followed.
// Pure. PROTOCOL.md, "Account".
import type { Account, Blocked, ModelWindow, Provider, Session, Usage } from './types.ts';
import { clockTime, dayClock, HOUR, spokenSpan } from './format.ts';

/** What the sun is now. */
export type SunMode = 'disk' | 'quiet' | 'low' | 'eclipse';

/** Signed out, no plan, on hold: the app can't follow usage, so the sun is eclipsed. */
export const eclipsed = (a: Account | null | undefined) => a?.state === 'logged_out' || a?.state === 'no_subscription' || a?.state === 'on_hold';

/** The account's limit while it holds (its reset ahead, or none said), not one Claude runs past on usage credits. */
export function resting(a: Account | null | undefined, now: number): Account['limit'] {
  const l = a?.limit;
  if (!l || (a!.state !== 'limited' && a!.state !== 'no_credit')) return null;
  return l.resetsAt === null || l.resetsAt > now ? l : null;
}

/** A window used up, its reset still ahead. */
const full = (w: Usage['fiveHour'], now: number) => !!w && w.pct >= 100 && w.resetsAt > now;

/** Which limit holds now: the week's, the 5-hour window's, or none (credits carry Claude past a limit). */
export function limitNow(usage: Usage | null | undefined, account: Account | null | undefined, now: number): 'week' | 'five' | null {
  const l = resting(account, now);
  if (l?.usingCredits) return null;
  if (l?.window === 'seven_day' || full(usage?.sevenDay ?? null, now)) return 'week';
  if (l?.window === 'five_hour' || full(usage?.fiveHour ?? null, now)) return 'five';
  return null;
}

/** The sun: eclipsed, the low state (the week spent; it outranks the 5-hour window), quiescent (the 5-hour window spent), else the disk. */
export function sunMode(usage: Usage | null | undefined, account: Account | null | undefined, now: number): SunMode {
  if (eclipsed(account) || usage?.source === 'stale') return 'eclipse';
  const lim = limitNow(usage, account, now);
  return lim === 'week' ? 'low' : lim === 'five' ? 'quiet' : 'disk';
}

/** When the limit that holds now ends (null when it isn't known, or none holds). */
export function limitEnds(usage: Usage | null | undefined, account: Account | null | undefined, now: number): number | null {
  const lim = limitNow(usage, account, now);
  if (!lim) return null;
  const l = resting(account, now);
  const own = l && (lim === 'week' ? l.window === 'seven_day' : l.window === 'five_hour') ? l.resetsAt : null;
  return own ?? (lim === 'week' ? usage?.sevenDay?.resetsAt : usage?.fiveHour?.resetsAt) ?? null;
}

/**
 * A model's own week (Fable, Sonnet) when it is the tightest limit: fuller than the 5-hour window
 * and the week alike (a tie goes to them), and not empty. A week past its reset counts as empty,
 * like the windows. null otherwise, and from a Mac that sends no models.
 *
 * A model limit Claude Code just hit ("Fable limit") fills that model's week at once, until the
 * limit's reset: the Mac only looks at /usage again a few seconds later, and the glasses shouldn't
 * say "Fable 97%" while Fable is already stopped. The limit and the week are matched by their
 * first word, since the week may carry the model's full name ("Fable 5.1").
 */
export function tightestModel(usage: Usage | null | undefined, now: number, account?: Account | null): ModelWindow | null {
  const live = (pct: number, resetsAt: number | null) => (resetsAt !== null && resetsAt <= now ? 0 : pct);
  const family = (s: string) => s.trim().split(/\s+/)[0]!.toLowerCase();
  const hit = resting(account, now);
  const stopped = hit && hit.window === 'model' && !hit.usingCredits && hit.label ? family(hit.label) : null;
  let best: ModelWindow | null = null;
  for (const m of usage?.models ?? []) {
    const w = stopped && family(m.label) === stopped ? { ...m, pct: 100, resetsAt: hit!.resetsAt ?? m.resetsAt } : m;
    const pct = live(w.pct, w.resetsAt);
    if (pct > 0 && (!best || pct > best.pct)) best = { ...w, pct };
  }
  if (!best) return null;
  const five = usage?.fiveHour ? live(usage.fiveHour.pct, usage.fiveHour.resetsAt) : 0;
  const week = usage?.sevenDay ? live(usage.sevenDay.pct, usage.sevenDay.resetsAt) : 0;
  return best.pct > Math.max(five, week) ? best : null;
}

/** "Session limit", "Weekly limit", "Opus limit": Claude Code's own words when it gave them. */
export function limitLabel(limit: Account['limit'] | null | undefined): string {
  const own = limit?.label?.trim();
  if (own) return own[0].toUpperCase() + own.slice(1);
  return limit?.window === 'seven_day' ? 'Weekly limit' : limit?.window === 'model' ? 'Model limit' : limit?.window === 'credits' ? 'Usage credit limit' : 'Session limit';
}

const HIT = /hit your ([^·]+?)\s*(?:·|$)/i;

/** The limit a session's block names: the account's when it's the same one, else Claude Code's line. */
function blockLabel(b: Blocked, account: Account | null | undefined): string {
  const own = account?.limit && (b.kind === 'model') === (account.limit.window === 'model') ? account.limit : null;
  if (own) return limitLabel(own);
  const said = HIT.exec(b.text ?? '')?.[1]?.trim();
  if (said) return said[0].toUpperCase() + said.slice(1);
  return b.kind === 'model' ? 'Model limit' : 'Session limit';
}

/** "at 2:00" within the next 20 hours; later its weekday, "Fri 10:00" (as the phone says it). */
export function atClock(at: number, now: number): string {
  return at - now < 20 * HOUR ? `at ${clockTime(at)}` : dayClock(at, now);
}

/** A provider's trouble in the phone's words: "OpenRouter is out of credits", "… refused its key". */
export function providerSentence(p: Provider, now: number): string {
  const st = p.status;
  switch (st?.state) {
    case 'no_credit': return st.budget ? `${p.name} reached its budget` : `${p.name} is out of credits`;
    case 'bad_key': return `${p.name} refused its key`;
    case 'limited': return st.resetsAt && st.resetsAt > now ? `${p.name} is rate limited until ${dayClock(st.resetsAt, now)}` : `${p.name} is rate limited`;
    case 'down': return `${p.name} isn’t answering`;
    default: return `${p.name} can’t reply`;
  }
}

/** The provider a session runs on, when the Mac says (an older one doesn't). */
export function providerOf(s: Session, providers: Provider[] | null | undefined): Provider | null {
  return (s.providerId && providers?.find(p => p.id === s.providerId)) || null;
}

/**
 * A paused session's line, the phone's words (a short phrase, never fields): "session limit until
 * 2:00", "session limit until 2:00, then carries on", "paused at the session limit", "session limit
 * over, ready to continue"; "signed out on the Mac"; for another provider, its trouble ("OpenRouter
 * is out of credits", "OpenRouter carries on by itself at 14:05"). null: not blocked.
 */
export function blockedLine(s: Session, account: Account | null | undefined, now: number, providers?: Provider[] | null): string | null {
  const b = s.blocked;
  if (!b) return null;
  switch (b.kind) {
    case 'limit': case 'model': {
      const l = blockLabel(b, account).toLowerCase();
      if (b.resume?.state === 'ready') return `${l} over, ready to continue`;
      if (b.autoResumeAt && b.autoResumeAt > now) return `${l} until ${dayClock(b.autoResumeAt, now)}, then carries on`;
      if (b.resetsAt && b.resetsAt > now) return `${l} until ${dayClock(b.resetsAt, now)}`;
      if (b.kind === 'model' && !b.resetsAt) return 'this model isn’t available';
      return `paused at the ${l}`;
    }
    case 'logged_out': return 'signed out on the Mac';
    case 'no_subscription': return 'can’t reply without a Claude plan';
    case 'on_hold': return 'account on hold';
    case 'no_credit': return 'out of usage credits';
    case 'provider': {
      const p = providerOf(s, providers);
      if (!p) return 'can’t reply right now';
      const r = b.resume;
      if (r?.state === 'armed' && r.at) return `${p.name} carries on by itself ${atClock(r.at, now)}`;
      if (r?.state === 'ready') return `${p.name} is ready to continue`;
      return providerSentence(p, now);
    }
    default: return 'can’t reply right now';
  }
}

/** A paused session in "status": "hyperscribe is paused by the session limit, back at 2:00." */
export function blockedSpoken(name: string, s: Session, account: Account | null | undefined, now: number, providers?: Provider[] | null): string | null {
  const b = s.blocked;
  if (!b) return null;
  if (b.kind === 'limit' || b.kind === 'model') {
    const l = blockLabel(b, account).toLowerCase();
    if (b.resume?.state === 'ready') return `${name} waits for you: the ${l} is over.`;
    const at = b.autoResumeAt && b.autoResumeAt > now ? b.autoResumeAt : b.resetsAt && b.resetsAt > now ? b.resetsAt : null;
    const how = b.autoResumeAt && b.autoResumeAt > now ? 'carries on by itself' : 'is back';
    return at ? `${name} is paused by the ${l}, and ${how} in ${spokenSpan(at - now)}.` : `${name} is paused by the ${l}.`;
  }
  return `${name} can’t reply: ${blockedLine(s, account, now, providers)}.`;
}

/** The limits in "status": the week before the 5-hour window, and what stops the account. */
export function limitSpoken(usage: Usage | null | undefined, account: Account | null | undefined, now: number, limitInMs: number | null = null): string {
  switch (account?.state) {
    case 'logged_out': return 'Claude Code is signed out on your Mac, so sessions can’t reply.';
    case 'no_subscription': return 'Your Claude plan may have ended, so sessions can’t reply.';
    case 'on_hold': return 'Your account is on hold, so sessions can’t reply.';
    case 'no_credit': return 'You’re out of usage credits, so sessions can’t reply.';
  }
  const lim = limitNow(usage, account, now);
  const ends = limitEnds(usage, account, now);
  if (lim === 'week') return ends ? `The weekly limit is used up. Claude is back in ${spokenSpan(ends - now)}.` : 'The weekly limit is used up.';
  if (lim === 'five') {
    const auto = account?.limit?.autoResumeAt;
    if (auto && auto > now) return `You’re at the session limit. Waiting sessions carry on by themselves in ${spokenSpan(auto - now)}.`;
    return ends ? `You’re at the session limit. It resets in ${spokenSpan(ends - now)}.` : 'You’re at the session limit.';
  }
  if (resting(account, now)?.usingCredits) return 'The limit is used up, so replies come out of your usage credits.';
  const w = usage?.fiveHour;
  if (!w) return '';
  const week = usage?.sevenDay && usage.sevenDay.pct >= 80 ? ` This week is at ${Math.round(usage.sevenDay.pct)} percent.` : '';
  // The pace reaches the limit before the reset (core/meter.ts): that's the news, as on the phone.
  if (limitInMs !== null) return `Usage is at ${Math.round(w.pct)} percent. At this pace you reach the limit in ${spokenSpan(limitInMs)}, before it resets.${week}`;
  return `Usage is at ${Math.round(w.pct)} percent, resetting in ${spokenSpan(Math.max(0, w.resetsAt - now))}.${week}`;
}

/**
 * A limit coming or going, for the pop-up: what it says, or null when nothing changed. Keyed by
 * episode (the account's `since`), so a limit that many sessions hit pops up once.
 */
export function limitKey(usage: Usage | null | undefined, account: Account | null | undefined, now: number): string | null {
  const lim = limitNow(usage, account, now);
  if (!lim) return null;
  return `${lim}:${account?.since ?? limitEnds(usage, account, now) ?? ''}`;
}
