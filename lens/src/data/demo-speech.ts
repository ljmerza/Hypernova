// Sample data's Hypernova app on the phone: it "plays" the glasses' speech as the real one does
// (PROTOCOL.md, "Read aloud"): a few progress messages a second, with the characters read so far and
// a voice-like loudness (syllables that swell and fade, quiet between words), then the end. No sound;
// it shows how the lens follows a voice: the wave in the header, the text in step, a tap that stops.
import type { HubEvent } from './hub.ts';
import type { PlayProgress, PlayStart, SpeechSetting } from '../core/speech-out.ts';

/** The demo's read-aloud setting: Soniox, with a phone app playing for the glasses. */
export const DEMO_READ_ALOUD: SpeechSetting = { engine: 'soniox', using: 'soniox', voice: 'Adrian', macVoice: null, rate: 1, model: 'tts-rt-v2', soniox: true, note: null, phone: true };

/** About 15 characters a second, as a voice reads. */
const MS_PER_CHAR = 66;

export class DemoPhoneSpeech {
  private timer: ReturnType<typeof setInterval> | null = null;
  private current: string | null = null;
  private seq = 0;
  constructor(private emit: (e: HubEvent) => void) {}

  play(text: string): PlayStart {
    this.stop();
    const id = `demo-play-${++this.seq}`;
    const of = text.length, total = of * MS_PER_CHAR, started = Date.now();
    this.current = id;
    const send = (state: PlayProgress['state'], ms: number) => {
      const chars = Math.min(of, Math.round(ms / MS_PER_CHAR));
      const ch = text[chars] ?? ' ';
      // A syllable every few letters swells and fades; spaces and stops are quiet.
      const level = /[\p{L}\p{N}]/u.test(ch) ? Math.pow(Math.sin(Math.PI * ((ms / (MS_PER_CHAR * 4)) % 1)), 2) * 0.9 : 0.05;
      this.emit({ t: 'speech_progress', progress: { id, state, ms, chars: state === 'ended' ? of : chars, of, level: state === 'playing' ? level : 0, engine: 'soniox', why: null } });
    };
    this.timer = setInterval(() => {
      const ms = Date.now() - started;
      if (ms >= total) { this.stop(); send('ended', total); } else send('playing', ms);
    }, 250);
    return { id, engine: 'soniox', of, to: { id: 'demo-phone', name: 'This phone' } };
  }

  stop(id?: string): void {
    if (id && id !== this.current) return;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    const was = this.current;
    this.current = null;
    if (was && id) this.emit({ t: 'speech_progress', progress: { id: was, state: 'stopped', ms: 0, chars: 0, of: 0, level: 0, engine: 'soniox', why: 'asked' } });
  }
}
