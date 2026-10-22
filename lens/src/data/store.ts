// Pairing and settings. They live in the Even App's own key-value store for this app (the SDK's
// setLocalStorage), not in the WebView's localStorage: Even Hub pages may share a WebView origin,
// and the device token opens the Mac's Claude Code sessions. In a plain browser (development) there
// is no Even App, so they fall back to sessionStorage and are gone with the tab.

import { DEFAULT_POPS, type PopSettings } from '../core/popups.ts';
import { PACE_DEFAULT } from '../core/transmission.ts';
import type { RequestSigner } from './api.ts';
import type { NewSessionChoice } from './bridge.ts';

export interface Pairing {
  baseUrl: string;
  token: string;
  macName: string;
  macId: string | null;
  deviceId: string | null;
  /**
   * Through the Hypernova app on this phone: what signs each call (data/bridge.ts), and `token` is
   * then empty, never sent. Only in memory: a pairing that's kept never has one.
   */
  signer?: RequestSigner;
}

export type VoiceChoice = 'auto' | 'phone' | 'off' | `mac:${string}`;

export interface Settings {
  /** Projects whose sessions the glance shows (by name); null = every active session. */
  projects: string[] | null;
  /** Speech language: an ISO code, or 'auto'. */
  language: string;
  /** Read-aloud voice: the Mac's (by its id), the phone's, or automatic. ('off' is from before the switch.) */
  voice: VoiceChoice;
  rate: number;
  /** Replies are read aloud (a tap, "read the reply", "status"); "mute" and "unmute" on the glasses. */
  readAloud: boolean;
  /** A soft tone in the earbuds when a session finishes or needs you. */
  blip: boolean;
  /** "Text arrives": all at once, or like speech, a few words at a time (core/transmission.ts). */
  textArrives: 'all' | 'speech';
  /** How fast text arrives like speech, in words a minute. */
  pace: number;
  /** Which pop-ups the glasses show (core/popups.ts). */
  popups: PopSettings;
  /** On a bike or a walk ("moving" / "still"): two short lines, one sentence per result, risky commands by voice only. */
  moving: boolean;
  /** "Eclipse": only what needs you gets through (no other pop-ups, no finished turn taking the lens). */
  eclipse: boolean;
  /**
   * The glasses have offered their guide (it's offered once; "guide" plays it again). True here, so
   * code without a store (tests) never sees the offer; a store that loads says false until it's made.
   */
  guideOffered: boolean;
  /** Type on the lens (lens/look.ts): the glasses' built-in font ('native', the default), or the app's own, drawn ('hypernova', a hybrid). */
  look: 'hypernova' | 'native';
  /** The provider and model the phone's new-session sheet last used (the app's, data/bridge.ts); null: the Mac's defaults. */
  newSession: NewSessionChoice | null;
}

export const DEFAULT_SETTINGS: Settings = {
  projects: null, language: 'en', voice: 'auto', rate: 1, readAloud: true, blip: true,
  textArrives: 'all', pace: PACE_DEFAULT, popups: { ...DEFAULT_POPS }, moving: false, eclipse: false, guideOffered: true, look: 'native', newSession: null,
};

export interface KeyValue {
  get(key: string): Promise<string>;
  set(key: string, value: string): Promise<boolean>;
}

const K = { pairing: 'lens.pairing', settings: 'lens.settings', bridge: 'lens.bridge' } as const;

const browserKv: KeyValue = {
  async get(key) { try { return sessionStorage.getItem(key) ?? ''; } catch { return ''; } },
  async set(key, value) { try { sessionStorage.setItem(key, value); return true; } catch { return false; } },
};

export class Store {
  pairing: Pairing | null = null;
  /** The token the Hypernova app on this phone gave Lens (data/bridge.ts); null until it allowed Lens. */
  bridgeToken: string | null = null;
  settings: Settings = { ...DEFAULT_SETTINGS };
  private kv: KeyValue = browserKv;
  private listeners = new Set<() => void>();

  async load(kv: KeyValue | null): Promise<void> {
    if (kv) this.kv = kv;
    const parse = <T>(raw: string): T | null => { try { return raw ? JSON.parse(raw) as T : null; } catch { return null; } };
    const p = parse<Pairing>(await this.kv.get(K.pairing).catch(() => ''));
    this.pairing = p?.baseUrl && p.token ? p : null;
    const b = parse<{ token?: string }>(await this.kv.get(K.bridge).catch(() => ''));
    this.bridgeToken = typeof b?.token === 'string' && b.token ? b.token : null;
    const saved = parse<Partial<Settings>>(await this.kv.get(K.settings).catch(() => '')) ?? {};
    // A speech companion's address saved by Lens up to 0.1.11: forgotten, quietly.
    delete (saved as { companionUrl?: unknown }).companionUrl;
    // A pop-up kind added since the settings were saved takes its default.
    // The guide on the glasses is offered once, also to a lens set up before it existed.
    this.settings = { ...DEFAULT_SETTINGS, ...saved, popups: { ...DEFAULT_POPS, ...(saved.popups ?? {}) }, guideOffered: saved.guideOffered ?? false };
    // Reading aloud was turned off by choosing the voice "Off"; it has a switch of its own now.
    if (saved.voice === 'off' && saved.readAloud === undefined) this.settings = { ...this.settings, voice: 'auto', readAloud: false };
    this.emit();
  }

  subscribe(fn: () => void): () => void { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  private emit(): void { for (const fn of this.listeners) fn(); }

  async setPairing(p: Pairing | null): Promise<void> {
    this.pairing = p;
    await this.kv.set(K.pairing, p ? JSON.stringify({ ...p, signer: undefined }) : '').catch(() => false);
    this.emit();
  }

  async setBridgeToken(token: string | null): Promise<void> {
    this.bridgeToken = token;
    await this.kv.set(K.bridge, token ? JSON.stringify({ token }) : '').catch(() => false);
    this.emit();
  }

  /**
   * Settings the Hypernova app sent (they live there now; this is Lens's cache of them). Told only
   * when something changed, so the app's echo of a change made here costs nothing.
   */
  async applyRemote(patch: Partial<Settings>): Promise<boolean> {
    const next = { ...this.settings, ...patch };
    if (JSON.stringify(next) === JSON.stringify(this.settings)) return false;
    await this.update(patch);
    return true;
  }

  async update(patch: Partial<Settings>): Promise<void> {
    this.settings = { ...this.settings, ...patch };
    await this.kv.set(K.settings, JSON.stringify(this.settings)).catch(() => false);
    this.emit();
  }
}
