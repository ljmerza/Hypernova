// Spending as a meter, not a sentence (the owner, 2026-10-18: "projection meters"): the window that
// matters drawn as a bar, used solid, the pace a faint fill to where it leads by the reset, a notch
// and how soon when the limit comes first, else when it resets (lens/drawn.ts). And the same numbers
// in words for the glance's corner in the glasses' own font (limitCorner): "42%  limit in 47m" when
// the pace reaches the limit first, else "42%  resets in 2h 11m"; "limit until 16:40" while spent.
// One choice of window for both: the 5-hour one, a model's week while it is the tightest, the week
// or the 5-hour window at its limit. Pure: runs under node.
import type { Account, Usage } from './types.ts';
import { ago, dayClock, HOUR } from './format.ts';
import { limitEnds, limitNow, resting, tightestModel } from './limits.ts';

export interface SpendMeter {
  /** The window: "5H", "WEEK", or a model's name ("FABLE"). */
  label: string;
  /** Used, 0-100. */
  pct: number;
  /** Where the pace leads by the reset (pct..100); null without one (nothing works, a model's week). */
  pace: number | null;
  /** The limit comes before the reset, this soon (ms); null when the reset comes first. */
  limitInMs: number | null;
  /** Until the reset, or at a limit until it lifts (ms); null when unknown. */
  resetInMs: number | null;
  /** At this window's limit now. */
  spent: boolean;
}

/**
 * The meter for the glance, or null where the corner's words say something a bar can't (signed out,
 * no plan, on hold, out of credits, running on usage credits, nothing known).
 */
export function spendMeter(usage: Usage | null | undefined, account: Account | null | undefined, now: number): SpendMeter | null {
  if (account && ['logged_out', 'no_subscription', 'on_hold', 'no_credit'].includes(account.state)) return null;
  if (!usage || usage.source === 'stale') return null;
  const lim = limitNow(usage, account, now);
  const ends = limitEnds(usage, account, now);
  const back = ends ? Math.max(0, ends - now) : null;
  if (lim) return { label: lim === 'week' ? 'WEEK' : '5H', pct: 100, pace: null, limitInMs: null, resetInMs: back, spent: true };
  if (resting(account, now)?.usingCredits) return null;
  const m = tightestModel(usage, now, account);
  if (m) {
    const name = m.label.trim().split(/\s+/)[0]!.toUpperCase();
    return { label: name, pct: Math.min(100, m.pct), pace: null, limitInMs: null, resetInMs: m.resetsAt !== null ? Math.max(0, m.resetsAt - now) : null, spent: m.pct >= 100 };
  }
  const w = usage.fiveHour;
  if (!w) return null;
  // A window whose reset has passed: the next status line brings the new one.
  if (w.resetsAt <= now) return { label: '5H', pct: 0, pace: null, limitInMs: null, resetInMs: null, spent: false };
  const pct = Math.min(w.pct, 99), resetIn = w.resetsAt - now;
  const rate = usage.rate?.fiveHour;
  if (rate == null || rate <= 0) return { label: '5H', pct, pace: null, limitInMs: null, resetInMs: resetIn, spent: false };
  // Projected: used + pace x minutes to the reset. The Mac's own time to the limit when it says one.
  const projected = pct + (rate * resetIn) / 60_000;
  const eta = usage.rate?.fiveHourEtaMs ?? (projected >= 100 ? ((100 - pct) / rate) * 60_000 : null);
  const first = eta !== null && eta < resetIn;
  return { label: '5H', pct, pace: first ? 100 : Math.min(100, projected), limitInMs: first ? Math.max(0, eta) : null, resetInMs: resetIn, spent: false };
}

/** When a window resets, as the phone's meters say it: "resets in 2h 11m", past 20 hours "resets Tue 8:15". */
function resetsWords(inMs: number, now: number): string {
  if (inMs >= 20 * HOUR) return `resets ${dayClock(now + inMs, now)}`;
  return ago(inMs) === 'now' ? 'resets now' : `resets in ${ago(inMs)}`;
}

/**
 * The glance's corner in words, as its parts (the page puts a gap between them): the spending as the
 * meter has it ("42%", "limit in 47m" when the pace reaches the limit before the reset, else
 * "resets in 2h 11m"); a spent limit until it lifts ("limit until 16:40", "week limit until Fri
 * 10:00", "Fable limit until Tue 15:56"); "on usage credits" past a limit; and what stops the
 * account. A model's own week takes the corner only while it is the tightest limit: the one the
 * owner is about to run into.
 */
export function limitCorner(usage: Usage | null | undefined, account: Account | null | undefined, now: number): string[] {
  switch (account?.state) {
    case 'logged_out': return ['signed out'];
    case 'no_subscription': return ['no plan'];
    case 'on_hold': return ['on hold'];
    case 'no_credit': return ['out of credits'];
  }
  const lim = limitNow(usage, account, now);
  if (lim) {
    const ends = limitEnds(usage, account, now);
    const name = lim === 'week' ? 'week limit' : 'limit';
    return [ends ? `${name} until ${dayClock(ends, now)}` : name];
  }
  if (resting(account, now)?.usingCredits) return ['on usage credits'];
  const m = tightestModel(usage, now, account);
  if (m) {
    if (m.pct >= 100) return [m.resetsAt !== null && m.resetsAt > now ? `${m.label} limit until ${dayClock(m.resetsAt, now)}` : `${m.label} limit`];
    return [`${m.label} ${Math.round(m.pct)}%`, m.resetsAt !== null && m.resetsAt > now ? resetsWords(m.resetsAt - now, now) : ''].filter(Boolean);
  }
  const meter = spendMeter(usage, account, now);
  // Without a reading the meter can't be had (a stale one says nothing either): the window's own words.
  const w = usage?.fiveHour;
  if (!w) return [];
  // A window whose reset has passed: the next status line brings the new one.
  if (w.resetsAt <= now) return ['0%'];
  const pct = `${Math.round(Math.min(w.pct, 99))}%`;
  if (meter?.limitInMs != null) return [pct, ago(meter.limitInMs) === 'now' ? 'limit now' : `limit in ${ago(meter.limitInMs)}`];
  return [pct, resetsWords(w.resetsAt - now, now)];
}
