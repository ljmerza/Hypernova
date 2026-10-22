// The words of Lens's status card on the phone (status.ts), apart from the page so they can be tested.
import type { HubLike } from '../data/hub.ts';
import type { BridgeStatus, OpenShown } from '../data/bridge.ts';
import { linkWords, offlineWords, type LinkState } from '../data/link.ts';

/** What the card says: a title, a line under it, and its tone. Pure, for the tests. */
export interface CardWords { title: string; sub: string; tone: 'ok' | 'wait' | 'warn'; code?: string }

export function cardWords(link: LinkState, hub: HubLike | null, st: BridgeStatus | null, now: number): CardWords {
  // The connection states, in the same words as the lens (data/link.ts, one table for both).
  if (link.kind === 'direct') return { title: 'Paired with your Mac directly', sub: hub?.macName ?? '', tone: 'ok' };
  const w = linkWords(link, now, false);
  if (w) return { title: w.phone.title, sub: w.phone.sub, tone: w.phone.tone, ...(w.phone.code ? { code: w.phone.code } : {}) };
  const status = hub?.status;
  const mac = hub?.snapshot?.mac.name ?? st?.mac ?? 'your Mac';
  const route = st?.route === 'local' ? 'on the local network' : st?.route === 'tailscale' ? 'over Tailscale' : st?.route === 'remote' ? 'over the internet' : null;
  if (status === 'live' || status === 'polling') return { title: 'Connected to Hypernova ✓', sub: route ? `${mac}, ${route}` : mac, tone: 'ok' };
  if (st && !st.paired) return { title: 'Hypernova isn’t paired with a Mac yet', sub: 'Pair Hypernova on this phone with your Mac first; Lens then connects through it.', tone: 'warn' };
  if (status === 'unpaired') return { title: 'Hypernova isn’t paired with your Mac any more', sub: hub?.problem ?? 'Pair Hypernova on this phone with your Mac again.', tone: 'warn' };
  // The app itself gone quiet (nothing sealed answers on its port): said as that, not as the Mac out of reach.
  if (status === 'offline') { const o = offlineWords(hub?.problem ?? null).phone; return { title: o.title, sub: o.sub, tone: 'warn' }; }
  return { title: 'Connecting to your Mac…', sub: `through Hypernova${route ? `, ${route}` : ''}`, tone: 'wait' };
}

/**
 * What "Open Hypernova" says, in Lens's own words: what the app did when Lens asked it to open
 * (`POST /bridge/open`: the Even App's web view can't open it itself), or, when Lens couldn't ask
 * (null: not allowed yet, no app, an app from before), to open it by hand.
 */
export function openWords(shown: OpenShown | null): string {
  switch (shown) {
    case 'app': return 'Hypernova is open on this phone: Settings → Glasses.';
    case 'notification': return 'Tap “Tap to open Hypernova” in your notifications.';
    case 'nothing': return 'Open Hypernova on your phone: Settings → Glasses.';
    default: return 'Open Hypernova on your phone.';
  }
}
