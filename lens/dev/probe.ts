// Probe the simulator: which glyphs the firmware font has, what a long press does with a menu
// registered, and whether a narrow image column renders. Dev only.
import {
  waitForEvenAppBridge, CreateStartUpPageContainer, RebuildPageContainer, TextContainerProperty, ImageContainerProperty,
  ImageRawDataUpdate, MenuContainerProperty, MenuItemProperty,
} from '@evenrealities/even_hub_sdk';

const log = (...a: unknown[]) => { console.info('[probe]', ...a); document.getElementById('log')!.textContent += a.map(String).join(' ') + '\n'; };
const p = new URLSearchParams(location.search).get('p') ?? 'glyphs';

const GLYPHS = [
  '◐◓◑◒ ◔◕ ◴◵◶◷ ◌◍◎◉',
  '▲△▴▵ ▼▽ ◆◇◈ ★☆ ✦✧',
  '✓✔✗✘ ⚠ ⏵⏸⏹ ▸▹▻ ⟳↻↺',
  '○●□■▶◀ ←→↑↓ ─━│ •·…—',
  '⊙⊚⦿ ∘∙ ⁘⁙ ⋮⋯ ‹› «» ※',
  '♪♫ ☀☼ ✳✴ ❯❮ ⌁ ⌘ ⏎ ↵',
  '█▓▒░ ▁▂▃▄▅▆▇ ▏▎▍▌',
  'ABC abc 0123 · 42% · 2h',
];

async function main() {
  const b = await waitForEvenAppBridge();
  b.onEvenHubEvent(ev => log('event', JSON.stringify(ev)));
  const input = new TextContainerProperty({ containerID: 1, containerName: 'input', xPosition: 0, yPosition: 0, width: 576, height: 288, borderWidth: 0, paddingLength: 0, content: '', isEventCapture: 1 });
  const menu = new MenuContainerProperty({ menuItems: [new MenuItemProperty({ itemID: 1, itemName: 'Talk' }), new MenuItemProperty({ itemID: 2, itemName: 'Exit' })] });
  if (p === 'glyphs') {
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 8, yPosition: 4, width: 560, height: 280, borderWidth: 0, paddingLength: 0, content: GLYPHS.join('\n'), isEventCapture: 0 });
    const r = await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 2, textObject: [input, t], menuObject: menu } as never));
    log('created', r);
  } else if (p === 'image') {
    const img = new ImageContainerProperty({ containerID: 3, containerName: 'col', xPosition: 0, yPosition: 0, width: 32, height: 144 });
    const img2 = new ImageContainerProperty({ containerID: 4, containerName: 'big', xPosition: 144, yPosition: 0, width: 288, height: 144 });
    const img3 = new ImageContainerProperty({ containerID: 5, containerName: 'big2', xPosition: 144, yPosition: 144, width: 288, height: 144 });
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 36, yPosition: 4, width: 100, height: 27, borderWidth: 0, paddingLength: 0, content: 'row', isEventCapture: 0, textColor: 2 });
    const r = await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 5, textObject: [input, t], imageObject: [img, img2, img3] } as never));
    log('created', r);
    const draw = (w: number, h: number, f: (c: CanvasRenderingContext2D) => void) => {
      const c = document.createElement('canvas'); c.width = w; c.height = h;
      const x = c.getContext('2d')!; x.fillStyle = '#000'; x.fillRect(0, 0, w, h); f(x);
      const url = c.toDataURL('image/png'), bin = atob(url.slice(url.indexOf(',') + 1)), out = new Uint8Array(bin.length);
      for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    };
    const col = draw(32, 144, x => { for (let i = 0; i < 5; i++) { x.fillStyle = `rgb(${80 + i * 40},${80 + i * 40},${80 + i * 40})`; x.beginPath(); x.arc(16, 14 + i * 28, 7, 0, 7); x.fill(); } });
    const grad = draw(288, 144, x => { for (let i = 0; i < 16; i++) { x.fillStyle = `rgb(${i * 17},${i * 17},${i * 17})`; x.fillRect(i * 18, 0, 18, 144); } });
    const t0 = performance.now();
    log('col', await b.updateImageRawData(new ImageRawDataUpdate({ containerID: 3, containerName: 'col', imageData: col })), Math.round(performance.now() - t0));
    const t1 = performance.now();
    log('big', await b.updateImageRawData(new ImageRawDataUpdate({ containerID: 4, containerName: 'big', imageData: grad })), Math.round(performance.now() - t1));
    log('big2', await b.updateImageRawData(new ImageRawDataUpdate({ containerID: 5, containerName: 'big2', imageData: grad })));
  } else if (p === 'measure') {
    // "|" + ten of a glyph + "|": the distance between the bars gives the glyph's advance.
    const set = '◐◑◌◎▲△▼▽◆◇◈★☆⊙※♪█▒▁▂▃▄▅▆▇▏▎▍▌'.split('');
    const i = Number(new URLSearchParams(location.search).get('i') ?? 0);
    const lines = set.slice(i * 10, i * 10 + 10).map(g => '|' + g.repeat(10) + '|');
    log('measure', lines.map(l => l[1]).join(''));
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 0, yPosition: 0, width: 576, height: 288, borderWidth: 0, paddingLength: 0, content: lines.join('\n'), isEventCapture: 0 });
    await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 2, textObject: [input, t] } as never));
  } else if (p === 'timers') {
    // How late do timers fire under the SDK's background keep-alive?
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 8, yPosition: 4, width: 560, height: 60, borderWidth: 0, paddingLength: 0, content: 'timers', isEventCapture: 0 });
    await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 2, textObject: [input, t] } as never));
    const gaps: number[] = [];
    let last = performance.now();
    const tick = () => { const n = performance.now(); gaps.push(Math.round(n - last)); last = n; if (gaps.length < 12) setTimeout(tick, 340); else log('timeout 340 gaps', gaps.join(',')); };
    setTimeout(tick, 340);
    const t0 = performance.now();
    setTimeout(() => log('timeout 4000 fired after', Math.round(performance.now() - t0)), 4000);
    const mc: number[] = []; let ml = performance.now();
    const ch = new MessageChannel();
    let n = 0;
    ch.port1.onmessage = () => { const x = performance.now(); mc.push(Math.round(x - ml)); ml = x; if (++n < 5) ch.port2.postMessage(0); else log('messagechannel gaps', mc.join(',')); };
    ch.port2.postMessage(0);
    requestAnimationFrame(() => log('raf fired'));
  } else if (p === 'chain') {
    // Timers that set the next one from their own callback (a teleprompter, a script), at three
    // pitches: does each chain keep going, and how late is each step?
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 8, yPosition: 4, width: 560, height: 60, borderWidth: 0, paddingLength: 0, content: 'chain', isEventCapture: 0 });
    await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 2, textObject: [input, t] } as never));
    for (const ms of [600, 1500, 4000]) {
      let last = performance.now(), n = 0;
      const step = () => { const x = performance.now(); log(`chain ${ms}`, ++n, Math.round(x - last)); last = x; if (n < 6) setTimeout(step, ms); };
      setTimeout(step, ms);
    }
  } else if (p === 'speech') {
    const ss = (window as unknown as { speechSynthesis?: SpeechSynthesis }).speechSynthesis;
    log('speechSynthesis', !!ss, ss ? ss.getVoices().length : -1, typeof AudioContext);
    setTimeout(() => log('voices later', ss ? ss.getVoices().length : -1), 1500);
    const t = new TextContainerProperty({ containerID: 2, containerName: 'g', xPosition: 8, yPosition: 4, width: 560, height: 60, borderWidth: 0, paddingLength: 0, content: `speech ${!!ss}`, isEventCapture: 0 });
    await b.createStartUpPageContainer(new CreateStartUpPageContainer({ containerTotalNum: 2, textObject: [input, t] } as never));
  }
  void RebuildPageContainer;
}
void main();
