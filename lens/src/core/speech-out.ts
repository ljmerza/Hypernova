// Read aloud, as the glasses see it (docs/PROTOCOL.md, "Read aloud"): the Mac's setting (Soniox's
// voices or the Mac's own), a play the Hypernova app on the phone streams and plays natively, how
// far it got, and the small wave on the lens while a voice speaks. Pure: runs under node.

/** The snapshot's `speech` (and `{t:"speech"}`); missing from an older Mac. */
export interface SpeechSetting {
  engine: 'soniox' | 'mac';
  /** What reads now: the Mac's voice while Soniox can't. */
  using: 'soniox' | 'mac';
  voice: string;
  macVoice: string | null;
  rate: number;
  model?: string;
  soniox: boolean;
  note: string | null;
  /** A phone app plays the glasses' speech right now. */
  phone: boolean;
}

/** `POST /v1/speech/play`'s answer: the play, and the phone that plays it. */
export interface PlayStart { id: string; engine: string; of: number; to: { id: string; name: string } }

/** `{t:"speech.progress"}`: how far the phone got. `chars` of `of`: the text read so far, by the voice's own timing. */
export interface PlayProgress {
  id: string;
  state: 'making' | 'playing' | 'ended' | 'stopped' | 'failed';
  ms: number;
  chars: number;
  of: number;
  level: number;
  engine: string;
  why: string | null;
}

/** A progress message as the Mac sent it; null when it isn't one. */
export function readProgress(raw: unknown): PlayProgress | null {
  const p = raw as Partial<PlayProgress> | null;
  if (!p || typeof p !== 'object' || typeof p.id !== 'string') return null;
  const states = ['making', 'playing', 'ended', 'stopped', 'failed'] as const;
  const state = states.find(s => s === p.state);
  if (!state) return null;
  const num = (v: unknown, d = 0) => (typeof v === 'number' && Number.isFinite(v) ? v : d);
  return {
    id: p.id, state, ms: Math.max(0, num(p.ms)), chars: Math.max(0, num(p.chars)), of: Math.max(0, num(p.of)),
    level: Math.min(1, Math.max(0, num(p.level))), engine: typeof p.engine === 'string' ? p.engine : 'soniox', why: typeof p.why === 'string' ? p.why : null,
  };
}

/** A play is over: played to the end, stopped, or never played. */
export const playOver = (s: PlayProgress['state']) => s === 'ended' || s === 'stopped' || s === 'failed';

// ---------------------------------------------------------------- the wave on the lens

/**
 * The glasses' font has no bar characters (font.ts), so the wave is drawn with what it has, short
 * to tall: "." (a dot on the line), ":" (two dots), "|" (a bar the height of a capital). Five of
 * them, spaced, the middle one the voice's loudness now and each pair outward the same a beat
 * earlier, so a syllable rises in the middle and spreads out, as on the phone.
 */
const STEPS = ['.', ':', '|'] as const;

/** One glyph for a loudness 0..1. */
export function waveGlyph(level: number): string {
  return level >= 0.5 ? STEPS[2] : level >= 0.18 ? STEPS[1] : STEPS[0];
}

/**
 * The wave for the lens: [beats] are the loudness of the last few beats, newest first. Five glyphs
 * with a space between (the font's spaces are 5 px; glyphs this narrow run together without them).
 */
export function waveText(beats: number[]): string {
  const at = (d: number) => (beats[d] ?? 0) * (1 - 0.16 * d);
  return [2, 1, 0, 1, 2].map(d => waveGlyph(at(d))).join(' ');
}

/**
 * The beats a wave shows, kept as the voice plays: one each time the lens redraws (about three a
 * second at most, the Bluetooth budget), the newest first. Silence settles it to dots.
 */
export class WaveBeats {
  private beats: number[] = [];
  /** Adds the loudness heard now and gives the wave's text. */
  next(level: number): string {
    this.beats.unshift(Math.min(1, Math.max(0, level)));
    this.beats.length = Math.min(this.beats.length, 3);
    return waveText(this.beats);
  }
  reset(): void { this.beats = []; }
}
