// How the lens looks (the phone's Settings → "Type on the lens"):
//   'native':   "Built-in", the default: every screen in the glasses' own (firmware) font (pages.ts).
//               The owner, 2026-10-18, on real glasses: "let's use firmware text on glasses, because
//               responsiveness really sucks with images" (docs/DECISIONS.md, LATER L57).
//   'hypernova': a hybrid, the option. The glance (and a pop-up over it) and approvals are drawn on
//               the phone in the app's own type and sent as pictures (drawn.ts, painter.ts); text that
//               streams or scrolls (replies, the live view) and every other screen stay the glasses'
//               own text boxes, where a new line or a swipe is one small text update.
//   'all':      development only (`?look=all`, never offered in Settings): replies drawn too, for
//               trying them on real glasses (docs/LATER.md, L57).
// Only the look changes: the same screens, words and gestures either way. The address (`?look=`)
// wins over the setting, for the simulator. Pure: runs under node.

export type Look = 'native' | 'hypernova' | 'all';
export const LOOKS: readonly Look[] = ['native', 'hypernova', 'all'];
export const DEFAULT_LOOK: Look = 'native';

const isLook = (v: unknown): v is Look => typeof v === 'string' && (LOOKS as readonly string[]).includes(v);

/** The look in use: the address's `look=`, else the setting, else the default. */
export function lookOf(settings: unknown, search: string = globalThis.location?.search ?? ''): Look {
  const asked = new URLSearchParams(search).get('look');
  if (isLook(asked)) return asked;
  const set = (settings as { look?: unknown } | null | undefined)?.look;
  return isLook(set) ? set : DEFAULT_LOOK;
}

/** Replies (arriving, finished, scrolled) are drawn: only in the development look. */
export const drawsReplies = (look: Look | undefined) => look === 'all';
/** The glance and approvals are drawn: in either drawn look. */
export const drawsCards = (look: Look | undefined) => look === 'hypernova' || look === 'all';

/** Development: `?specimen=1|2|3` shows a type specimen instead of the screens (drawn.ts specimen). */
export function specimenOf(search: string = globalThis.location?.search ?? ''): number | null {
  const v = new URLSearchParams(search).get('specimen');
  return v && /^\d$/.test(v) ? Number(v) : null;
}
