// The battery card, on request ("battery", or a swipe up at the top of the glance): the glasses and
// the Mac, each with its own drawn icon, a five-segment bar, the percent and "charging". A Mac without
// a battery (a Mac mini, an iMac) isn't listed, and neither is one that's away: its last level would
// pass for the current one. Under the Mac, whether Hypernova keeps it awake ("Stay awake"). Pure.
import type { GlassesBattery, MacAwake, MacBattery } from './types.ts';
import { GUARD_BATTERY, LOW_BATTERY } from './format.ts';
import { clockTime } from './format.ts';

export interface PowerRow {
  device: 'glasses' | 'mac';
  label: string;
  percent: number;
  /** 0–5 lit segments: one per started fifth, so 1% still shows one. */
  segments: number;
  charging: boolean;
  /** At 20% or less and not charging. */
  low: boolean;
}

const valid = (b: { percent: number } | null | undefined): b is { percent: number } => !!b && Number.isFinite(b.percent) && b.percent >= 0 && b.percent <= 100;

export const segmentsOf = (percent: number) => Math.max(0, Math.min(5, Math.ceil(Math.round(percent) / 20)));

/** The card's rows: the glasses first (they're on your face), then the Mac. */
export function powerRows(glasses: GlassesBattery | null | undefined, mac: MacBattery | null | undefined): PowerRow[] {
  const rows: PowerRow[] = [];
  const add = (device: PowerRow['device'], label: string, b: { percent: number; charging: boolean; onAC?: boolean }) => {
    const percent = Math.round(b.percent);
    rows.push({ device, label, percent, segments: segmentsOf(percent), charging: b.charging, low: percent <= LOW_BATTERY && !b.charging && !b.onAC });
  };
  if (valid(glasses)) add('glasses', 'GLASSES', glasses as GlassesBattery);
  if (valid(mac)) add('mac', 'MAC', mac as MacBattery);
  return rows;
}

/** A row's words beside its bar, apart: ["84%"], ["62%", "charging"], ["12%", "low"]. */
export function powerWords(r: PowerRow): string[] {
  return [`${r.percent}%`, r.charging ? 'charging' : r.low ? 'low' : ''].filter(Boolean);
}

/** What stands in Stay awake's way, in words (the daemon's codes; anything else as it came). */
export function awakeReason(a: MacAwake): string {
  switch (a.reason) {
    case 'battery': return `battery at ${a.minBattery ?? GUARD_BATTERY}% or less`;
    case 'hot': return 'running hot';
    case 'time': return 'Backpack’s 8 hours are up';
    case 'setup': return 'Backpack needs its setup on the Mac';
    case 'failed': return 'Backpack couldn’t keep a closed lid awake';
    default: return a.reason ?? '';
  }
}

/**
 * Stay awake as a phrase, as the phone's Mac card says it: "Awake with the lid closed" (Backpack,
 * holding), "Awake while working" (holding for sessions), "Keeps awake while working" (nothing to
 * hold for now), "Sleeps as usual" (off); with the time Backpack ends ("… until 18:30"), a guard's
 * countdown ("Sleeps at 14:02: running hot"), or why it can sleep.
 */
export function awakeLine(a: MacAwake | null | undefined, now: number): string | null {
  if (!a) return null;
  if (a.mode === 'off') return 'Sleeps as usual';
  if (a.holding && a.sleepAt && a.sleepAt > now) return `Sleeps at ${clockTime(a.sleepAt)}: ${awakeReason(a)}`;
  const until = a.until && a.until > now ? ` until ${clockTime(a.until)}` : '';
  // A closed lid is fine whenever macOS's SleepDisabled is on (Backpack's, or the owner's own Amphetamine).
  if (a.holding) return `${a.lidClosedOk ? 'Awake with the lid closed' : a.mode === 'backpack' ? 'Awake in Backpack' : 'Awake while working'}${until}${a.reason ? `: ${awakeReason(a)}` : ''}`;
  if (a.reason) return `Can sleep: ${awakeReason(a)}`;
  return a.mode === 'backpack' ? 'Backpack: awake when needed' : 'Keeps awake while working';
}

/** Stay awake as "status" says it, only when it holds with the lid closed or can't hold. */
export function awakeSpoken(a: MacAwake | null | undefined): string {
  if (!a || a.mode === 'off') return '';
  if (a.holding && !a.reason) return a.mode === 'backpack' && a.lidClosedOk ? 'Your Mac stays awake with the lid closed.' : '';
  return a.reason ? `Your Mac can sleep: ${awakeReason(a)}.` : '';
}

/**
 * The mode "stay awake" and "let it sleep" ask for. "Stay awake" is the backpack's: awake with the
 * lid closed, once its one-time setup is done (until then, awake while working). "Let it sleep" undoes
 * that (back to the default, awake only while working); said when nothing more than the default
 * holds, it turns Stay awake off.
 */
export function awakeModeFor(word: 'awake' | 'sleep', a: MacAwake | null | undefined): MacAwake['mode'] {
  if (word === 'awake') return a?.backpackReady ? 'backpack' : 'working';
  return a?.mode === 'backpack' ? 'working' : 'off';
}

/** What the lens says once the Mac has switched. */
export function awakeToast(word: 'awake' | 'sleep', mode: MacAwake['mode']): string {
  if (mode === 'backpack') return 'Your Mac stays awake, lid closed too';
  if (mode === 'working') return word === 'awake' ? 'Awake while working: Backpack needs its setup on the Mac' : 'Back to awake only while working';
  return 'Your Mac can sleep now';
}
