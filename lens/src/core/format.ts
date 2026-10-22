// Short words for a small screen: durations, what a session is doing, what it wants. Pure.
import type { Approval, GlassesBattery, MacBattery, Session } from './types.ts';

export const SEC = 1000, MIN = 60 * SEC, HOUR = 60 * MIN;

/** "now", "40s", "2m", "1h 12m", "3d". */
export function ago(ms: number): string {
  if (ms < 15 * SEC) return 'now';
  if (ms < MIN) return `${Math.round(ms / SEC)}s`;
  if (ms < HOUR) return `${Math.floor(ms / MIN)}m`;
  if (ms < 24 * HOUR) {
    const h = Math.floor(ms / HOUR), m = Math.floor((ms % HOUR) / MIN);
    return m && h < 10 ? `${h}h ${m}m` : `${h}h`;
  }
  return `${Math.floor(ms / (24 * HOUR))}d`;
}

/**
 * Text without the words ago() writes ("now", "40s", "2m", "1h 12m", "3d"), which change with the
 * clock alone: what's left changes only when something happened. A pair ("1h 12m", "4m 12s") is one
 * word, so "59m" turning into "1h 1m" is no news either.
 */
export function clockless(text: string): string {
  return text.replace(/\b(?:now|\d+[smhd](?: \d+[smhd])?)\b/g, '·');
}

/** A run's length: "48s", "4m 12s", "1h 3m". */
export function duration(ms: number): string {
  if (ms < MIN) return `${Math.max(1, Math.round(ms / SEC))}s`;
  if (ms < HOUR) {
    const m = Math.floor(ms / MIN), s = Math.round((ms % MIN) / SEC);
    return s && m < 10 ? `${m}m ${s}s` : `${m}m`;
  }
  return ago(ms);
}

const clockFormats = new Map<string, Intl.DateTimeFormat>();

/**
 * The time of day for the glance: "14:05" (HH:mm) where the phone's locale has a 24-hour day, "2:05
 * PM" where it has two halves. Only that choice comes from the locale: the digits and AM/PM are
 * always Latin, since the lens font has no others (an Arabic or Korean locale would write glyphs it
 * can't show). `o` pins the locale and zone (tests). Made once per locale: the glance is drawn often.
 */
export function clockTime(now: number, o: { locale?: string; timeZone?: string } = {}): string {
  try {
    const key = `${o.locale ?? ''}|${o.timeZone ?? ''}`;
    let f = clockFormats.get(key);
    if (!f) {
      const zone = o.timeZone ? { timeZone: o.timeZone } : {};
      const r = new Intl.DateTimeFormat(o.locale, { hour: 'numeric', ...zone }).resolvedOptions();
      const halves = r.hour12 ?? /^h1[12]$/.test(r.hourCycle ?? '');
      f = halves
        ? new Intl.DateTimeFormat('en-US', { hour: 'numeric', minute: '2-digit', hourCycle: 'h12', ...zone })
        : new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', ...zone });
      clockFormats.set(key, f);
    }
    return f.format(now);
  } catch {
    const d = new Date(now);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

/**
 * A moment ahead as the phone says it: the time of day within the next 20 hours ("14:05"), later
 * with its weekday ("Fri 10:00"), in English (the lens font is Latin only).
 */
export function dayClock(at: number, now: number, o: { locale?: string; timeZone?: string } = {}): string {
  const time = clockTime(at, o);
  if (at - now < 20 * HOUR) return time;
  let day = WEEKDAYS[new Date(at).getDay()];
  if (o.timeZone) { try { day = new Intl.DateTimeFormat('en-US', { weekday: 'short', timeZone: o.timeZone }).format(at); } catch { /* the device's */ } }
  return `${day} ${time}`;
}

/**
 * The battery in an Even App device status (the SDK's DeviceStatus: `batteryLevel` 0–100,
 * `isCharging`), or null when it gives none or nonsense. The SDK fills in what the Even App left
 * out: a level of 0, and a connection of 'none' (a push only about being worn, a device with no
 * status yet). Glasses that are on and talking can't be at 0%, so 0 off the charger is no reading,
 * and a status that doesn't say how the glasses are connected says nothing about their battery.
 */
export function glassesBatteryOf(s: { batteryLevel?: unknown; isCharging?: unknown; connectType?: unknown } | null | undefined): GlassesBattery | null {
  if (s?.connectType === 'none') return null;
  const raw = s?.batteryLevel;
  const level = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() ? Number(raw) : NaN;
  if (!Number.isFinite(level) || level < 0 || level > 100) return null;
  const charging = s?.isCharging === true || s?.isCharging === 'true';
  if (Math.round(level) === 0 && !charging) return null;
  return { percent: Math.round(level), charging };
}

/** A battery this low, and not charging, is worth a brighter line (the apps warn at the same level). */
export const LOW_BATTERY = 20;
/** Stay awake's own guard (the Mac lets itself sleep): its default, not the warning level above. */
export const GUARD_BATTERY = 15;

const batteryLow = (b: { percent: number; charging: boolean; onAC?: boolean }) => b.percent <= LOW_BATTERY && !b.charging && !b.onAC;
const validBattery = <T extends { percent: number }>(b: T | null | undefined): b is T => !!b && Number.isFinite(b.percent) && b.percent >= 0 && b.percent <= 100;

/** The batteries in "status", only when one runs low: "Your Mac's battery is at 15 percent." */
export function powerSpoken(glasses: GlassesBattery | null | undefined, mac: MacBattery | null | undefined): string {
  const out: string[] = [];
  if (validBattery(glasses) && batteryLow(glasses)) out.push(`The glasses' battery is at ${Math.round(glasses.percent)} percent.`);
  if (validBattery(mac) && batteryLow(mac)) out.push(`Your Mac's battery is at ${Math.round(mac.percent)} percent.`);
  return out.join(' ');
}

export function spokenSpan(ms: number): string {
  if (ms < MIN) return 'under a minute';
  const h = Math.floor(ms / HOUR), m = Math.floor((ms % HOUR) / MIN);
  const hs = h ? `${h} hour${h === 1 ? '' : 's'}` : '';
  const ms_ = m ? `${m} minute${m === 1 ? '' : 's'}` : '';
  return [hs, ms_].filter(Boolean).join(' ') || 'a moment';
}

/**
 * What a session runs, as the phone and the Mac say it: "Bash: ./gradlew build" (its first line), an
 * MCP tool by its server and name ("github create_issue: …"); "Thinking" → "thinking".
 */
export function activityLabel(activity: string | null | undefined): string {
  if (!activity) return '';
  const m = /^([\w.-]+):\s*(.*)$/s.exec(activity.trim());
  if (!m) return activity.trim().toLowerCase();
  const tool = m[1].startsWith('mcp__') ? m[1].split('__').slice(1).join(' ') : m[1];
  const what = m[2].split('\n')[0].trim();
  return what ? `${tool}: ${what}` : tool;
}

/** Spoken activity: "running ./gradlew build", "editing Cosmos.kt". */
export function activitySpoken(activity: string | null | undefined): string {
  if (!activity) return 'working';
  const m = /^([\w.-]+):\s*(.*)$/s.exec(activity.trim());
  if (!m) return activity.trim().toLowerCase();
  const verb: Record<string, string> = { Bash: 'running', Edit: 'editing', MultiEdit: 'editing', Write: 'writing', Read: 'reading', Grep: 'searching for', Glob: 'looking for', WebFetch: 'reading', WebSearch: 'searching the web for', Task: 'running a subagent:', Agent: 'running a subagent:' };
  return `${verb[m[1]] ?? m[1].toLowerCase()} ${m[2].split('\n')[0]}`.trim();
}

const cmdOf = (a: Approval) => String((a.input as { command?: unknown })?.command ?? '').split('\n')[0].trim();
const short = (p: string) => p.split('/').filter(Boolean).slice(-1)[0] ?? p;
const host = (u: string) => { try { return new URL(u).host; } catch { return ''; } };

/**
 * What each tool asks to do, once for both the card and the glance: its verb ("wants to edit"), the
 * input it acts on, and that input shortened for a row ("Cosmos.kt" for a path, a page's host).
 */
const ASKS: Record<string, { lead: string; shortLead?: string; fields: string[]; short?: (v: string) => string }> = {
  Bash: { lead: 'wants to run', fields: ['command'] },
  Edit: { lead: 'wants to edit', fields: ['file_path', 'notebook_path'], short },
  MultiEdit: { lead: 'wants to edit', fields: ['file_path'], short },
  NotebookEdit: { lead: 'wants to edit', fields: ['notebook_path', 'file_path'], short },
  Write: { lead: 'wants to write', fields: ['file_path'], short },
  Read: { lead: 'wants to read', fields: ['file_path'], short },
  WebFetch: { lead: 'wants to open', fields: ['url'], short: host },
  WebSearch: { lead: 'wants to search the web for', shortLead: 'wants to search', fields: ['query'] },
};

/** The input a tool acts on, as ASKS names it (undefined: none given). */
function askedOf(a: Approval): string | undefined {
  const input = (a.input ?? {}) as Record<string, unknown>;
  for (const f of ASKS[a.toolName]?.fields ?? []) if (input[f] !== undefined && input[f] !== null) return String(input[f]);
  return undefined;
}

const toolName = (t: string) => (t.startsWith('mcp__') ? t.split('__').slice(1).join(' ') : t);

/** What a session asks, in a few plain words: "wants to push to main", "wants to edit Cosmos.kt". */
export function wantsLine(a: Approval | null, s?: Session): string {
  if (!a) return s?.menu ? 'shows a menu in its terminal' : 'needs you';
  if (a.kind === 'question') return 'has a question';
  if (a.kind === 'plan') return 'has a plan for you';
  if (a.toolName === 'Bash') {
    const cmd = cmdOf(a);
    const push = /^git\s+push\b(.*)$/.exec(cmd);
    if (push) {
      // What can't be undone reads as such here too, not only on the card: a push that deletes
      // a branch (`--delete`, `-d`, `:branch`) or forces one (`--force`, `-f`, `+branch`).
      const words = push[1].trim().split(/\s+/).filter(Boolean);
      const flags = words.filter(x => x.startsWith('-'));
      const args = words.filter(x => !x.startsWith('-'));
      const ref = args[1] ?? args[0];
      const deletes = flags.some(f => f === '--delete' || /^-[a-zA-Z]*d[a-zA-Z]*$/.test(f)) || !!ref?.startsWith(':');
      const forces = flags.some(f => f.startsWith('--force') || /^-[a-zA-Z]*f[a-zA-Z]*$/.test(f)) || !!ref?.startsWith('+');
      const branch = ref?.replace(/^\+/, '').replace(/^.*:/, '');
      if (!branch) return 'wants to push';
      if (deletes) return `wants to delete branch ${branch}`;
      return forces ? `wants to force-push to ${branch}` : `wants to push to ${branch}`;
    }
    if (/^rm\s/.test(cmd)) return `wants to delete ${cmd.replace(/^rm\s+(-\w+\s+)*/, '')}`;
    return `wants to run ${cmd}`;
  }
  const ask = ASKS[a.toolName];
  if (!ask) return `wants to use ${toolName(a.toolName)}`;
  const v = askedOf(a) ?? '';
  const what = ask.short ? ask.short(v) : v;
  if (!what && a.toolName === 'WebFetch') return 'wants to open a page';
  return `${ask.shortLead ?? ask.lead} ${what}`.trim();
}

/** The exact thing to approve, for the card: the command, the file, the URL. */
export function approvalDetail(a: Approval): { lead: string; detail: string } {
  const ask = ASKS[a.toolName];
  if (!ask) return { lead: 'wants to use', detail: a.summary || a.toolName };
  return { lead: ask.lead, detail: askedOf(a) ?? a.summary };
}

/** A six-digit code in two threes, as the phone's prompt shows it: "482 913". */
export const groupCode = (code: string) => (/^\d{6}$/.test(code) ? `${code.slice(0, 3)} ${code.slice(3)}` : code);

/** Project names as they are said: "0stems" → "zero stems", "hyperscribe" stays. */
export function spokenName(name: string): string {
  const words = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine'];
  return name.replace(/\d/g, d => ` ${words[Number(d)]} `).replace(/[-_.]+/g, ' ').replace(/\s+/g, ' ').trim();
}
