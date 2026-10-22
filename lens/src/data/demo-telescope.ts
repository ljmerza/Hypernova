// Sample data for Telescope: what each source would show, drawn here so ?demo=1 and the simulator
// shots need no Mac. The emulator's screen changes every few seconds (a build's progress), so
// "watch" has something to follow; the same moment gives the same picture, as the daemon's
// unchanged link does.
import type { ScopeAsk, ScopeSource } from '../core/telescope.ts';
import type { Shot } from '../core/types.ts';
import { demoScreenshot } from './demo.ts';
import { ApiError } from './api.ts';

const MONO = '"JetBrains Mono", ui-monospace, Menlo, monospace';
const SANS = 'Manrope, system-ui, sans-serif';

const cache = new Map<string, string>();
function drawn(key: string, w: number, h: number, draw: (x: CanvasRenderingContext2D) => void): string {
  const hit = cache.get(key);
  if (hit) return hit;
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  draw(c.getContext('2d')!);
  const url = c.toDataURL('image/png');
  cache.set(key, url);
  return url;
}

/** The Hypernova app on a phone, a build's progress at `step`. */
function emulator(step: number): string {
  return drawn(`emu${step}`, 1080, 2400, x => {
    x.fillStyle = '#14110E'; x.fillRect(0, 0, 1080, 2400);
    x.fillStyle = '#B5B0A6'; x.font = `500 40px ${SANS}`; x.fillText('9:41', 60, 80); x.fillText('84%', 940, 80);
    // The sky: the sun (usage) and planets on their orbits.
    x.strokeStyle = 'rgba(245,242,235,0.18)'; x.lineWidth = 3;
    for (const r of [210, 330, 450]) { x.beginPath(); x.ellipse(540, 620, r, r * 0.42, 0, 0, Math.PI * 2); x.stroke(); }
    const g = x.createRadialGradient(540, 620, 0, 540, 620, 150);
    g.addColorStop(0, '#FFF6D8'); g.addColorStop(0.35, '#E8B341'); g.addColorStop(1, 'rgba(232,179,65,0)');
    x.fillStyle = g; x.beginPath(); x.arc(540, 620, 150, 0, Math.PI * 2); x.fill();
    const planet = (a: number, r: number, size: number, c: string) => { x.fillStyle = c; x.beginPath(); x.arc(540 + Math.cos(a) * r, 620 + Math.sin(a) * r * 0.42, size, 0, Math.PI * 2); x.fill(); };
    planet(0.6 + step * 0.25, 210, 22, '#7FFFA0'); planet(2.4, 330, 30, '#B5B0A6'); planet(4.1, 450, 26, '#E8B341');
    x.fillStyle = '#F5F2EB'; x.font = `600 46px ${SANS}`; x.fillText('72% · 1h 12m', 400, 860);
    // Sessions.
    const card = (y: number, name: string, line: string, accent: string) => {
      x.fillStyle = '#1B1814'; x.beginPath(); x.roundRect(48, y, 984, 230, 36); x.fill();
      x.fillStyle = accent; x.beginPath(); x.arc(118, y + 80, 20, 0, Math.PI * 2); x.fill();
      x.fillStyle = '#F5F2EB'; x.font = `600 52px ${SANS}`; x.fillText(name, 165, y + 98);
      x.fillStyle = '#B5B0A6'; x.font = `500 40px ${MONO}`; x.fillText(line, 90, y + 175);
    };
    card(1000, 'hypernova', `BASH ./gradlew build ${'·'.repeat(1 + (step % 4))}`, '#7FFFA0');
    card(1270, '0stems', 'wants to push to main', '#E8B341');
    card(1540, 'hyperscribe', 'done · 3 files changed', '#B5B0A6');
    // The build's progress bar.
    x.fillStyle = '#2C2823'; x.beginPath(); x.roundRect(90, 1195, 900, 14, 7); x.fill();
    x.fillStyle = '#7FFFA0'; x.beginPath(); x.roundRect(90, 1195, 900 * Math.min(1, 0.2 + (step % 6) * 0.15), 14, 7); x.fill();
    x.fillStyle = '#1B1814'; x.beginPath(); x.roundRect(48, 2160, 984, 150, 75); x.fill();
    x.fillStyle = '#7A7468'; x.font = `500 44px ${SANS}`; x.fillText('Ask hypernova…', 110, 2250);
  });
}

/** Claude Code's screen as the daemon draws it (daemon/src/telescope/terminal.ts). */
function terminal(): string {
  return drawn('term', 1152, 512, x => {
    x.fillStyle = '#0F0D0A'; x.fillRect(0, 0, 1152, 512);
    const lines: Array<Array<[string, string]>> = [
      [['> ', '#7FFFA0'], ['add a --json flag to `stems list`', '#F5F2EB']],
      [],
      [['⏺ ', '#7FFFA0'], ['Bash(cargo test -q)', '#F5F2EB']],
      [['  ⎿ ', '#7A7468'], ['running 48 tests', '#B5B0A6']],
      [['     test result: ok. 48 passed; 0 failed', '#B5B0A6']],
      [],
      [['⏺ ', '#7FFFA0'], ['`stems list --json` prints one object per stem,', '#F5F2EB']],
      [['  with its name, length and peak. 48 tests pass.', '#F5F2EB']],
      [],
      [['╭──────────────────────────────────────────────╮', '#45403A']],
      [['│ ', '#45403A'], ['>', '#F5F2EB']],
      [['╰──────────────────────────────────────────────╯', '#45403A']],
    ];
    const size = 26, lh = 34, adv = size * 0.6;
    x.font = `400 ${size}px ${MONO}`;
    lines.forEach((runs, i) => {
      let col = 0;
      for (const [t, c] of runs) { x.fillStyle = c; x.fillText(t, 18 + col * adv, 18 + (i + 1) * lh - 8); col += [...t].length; }
    });
  });
}

/** A chart a script saved, as matplotlib draws one: light, with axes. */
function chart(): string {
  return drawn('chart', 1600, 1000, x => {
    x.fillStyle = '#ffffff'; x.fillRect(0, 0, 1600, 1000);
    x.fillStyle = '#222'; x.font = `600 48px ${SANS}`; x.fillText('Sessions per day', 120, 90);
    x.strokeStyle = '#222'; x.lineWidth = 3; x.beginPath(); x.moveTo(140, 140); x.lineTo(140, 880); x.lineTo(1520, 880); x.stroke();
    const v = [12, 18, 9, 22, 27, 31, 24];
    v.forEach((n, i) => {
      const h = n * 22;
      x.fillStyle = '#2a7a4b'; x.fillRect(190 + i * 190, 880 - h, 120, h);
      x.fillStyle = '#222'; x.font = `500 32px ${SANS}`; x.fillText(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][i], 205 + i * 190, 930);
      x.fillText(String(n), 230 + i * 190, 860 - h);
    });
  });
}

const PICTURES: Record<Exclude<ScopeSource, 'simulator'>, () => { url: string; width: number; height: number; title: string; label: string }> = {
  web: () => ({ ...demoScreenshot(), title: 'Hyperscribe — Export', label: 'localhost:5173/export' }),
  emulator: () => ({ url: emulator(Math.floor(Date.now() / 6000)), width: 1080, height: 2400, title: 'Android emulator · Pixel 8', label: 'Pixel 8' }),
  terminal: () => ({ url: terminal(), width: 1152, height: 512, title: 'Terminal · 0stems', label: 'terminal' }),
  image: () => ({ url: chart(), width: 1600, height: 1000, title: 'sessions.png', label: 'sessions.png' }),
  window: () => ({ url: demoScreenshot().url, width: 1280, height: 800, title: 'Hyperscribe', label: 'Hyperscribe' }),
};

/** Which source sample data's sessions pick by themselves. */
const BY_PROJECT: Record<string, ScopeSource> = { hypernova: 'emulator', hyperscribe: 'web', '0stems': 'terminal' };

export function demoTelescope(sessionId: string, project: string, ask: ScopeAsk): Shot {
  const source = ask.source ?? BY_PROJECT[project] ?? 'image';
  // As on a Mac with only the Command Line Tools (the daemon's own words).
  if (source === 'simulator') throw new ApiError('server', 'The iOS Simulator needs Xcode on the Mac.', 501, 'no_simulator');
  const p = PICTURES[source]();
  return { sessionId, url: p.url, width: p.width, height: p.height, title: p.title, label: p.label, source, pageUrl: source === 'web' ? 'http://localhost:5173/export' : null, at: Date.now() };
}
