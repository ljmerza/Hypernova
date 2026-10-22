// Measures the drawn look (src/lens/look.ts; replies as `?look=all` draws them) against the native one, in headless
// Chromium with the dev server's own modules: a long reply arriving like speech step by step
// (what each step sends: tiles, PNG bytes, 4-bit bytes, LZ4 bytes; the native text update's bytes),
// scrolling it from top to end, the glance's moving planets on their own image vs inside a tile,
// and how long a page takes to draw and encode (at the Mac's speed and with the CPU slowed 4x, a
// rough phone). Battery can't be measured here or in the simulator: bytes stand in for radio time.
//   npm run dev -- --port 5191 &   then   node dev/look-bench.mjs http://127.0.0.1:5191 [out.json]
import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';

const base = process.argv[2] ?? 'http://127.0.0.1:5177';
const outFile = process.argv[3];

async function run(page) {
  return page.evaluate(async () => {
    const [{ Painter, loadLookFonts }, drawn, { Orrery }, { DRAWN_TYPE, NATIVE_TYPE }, pages, tx, text, demo, lz4, tiles] = await Promise.all([
      import('/src/lens/painter.ts'), import('/src/lens/drawn.ts'), import('/src/lens/orrery.ts'), import('/src/lens/typeset.ts'), import('/src/lens/pages.ts'),
      import('/src/core/transmission.ts'), import('/src/core/text.ts'), import('/src/data/demo.ts'), import('/src/core/lz4.ts'), import('/src/lens/tiles.ts'),
    ]);
    await loadLookFonts();
    const utf8 = s => new TextEncoder().encode(s).length;
    const facts = ['3 files changed', '48 tests passed', 'took 4m 12s'];
    const replyView = (type, lines, top, rows) => ({
      kind: 'card', head: ['DONE', 'hyperscribe'], right: '', lead: '', body: type.window(lines, top, rows), facts, factsRows: 2,
      hint: ['tap read aloud', 'swipe scroll', 'double tap back'], orrery: { sun: null, planets: [], seam: { state: 'done' } }, role: 'reply',
      scroll: { top, rows, lines: lines.length }, ...(type.look === 'hypernova' ? { look: 'all' } : {}),
    });
    const bodyOf = view => pages.buildPage(view).boxes.find(b => b.name === 'body')?.content ?? '';
    const out = {};

    // A long reply arriving like speech (170 words a minute), as the result card shows it: the window
    // follows the newest line. Each step is one update: the drawn look's changed tiles, the native body box.
    for (const lang of ['en', 'ru']) {
      const md = demo.LONG_REPLY[lang], plain = text.plainText(md), atoms = tx.codeAtoms(md, plain);
      const painter = new Painter(), orrery = new Orrery(drawn.DRAWN_ORRERY);
      const steps = [];
      let shown = 0, t = 0, prevNative = null, first = true;
      const tops = { native: 0, hypernova: 0 };
      while (shown < plain.length) {
        const st = tx.nextStep(plain, shown, atoms, 170);
        shown = st.end;
        const now = plain.slice(0, shown);
        const row = { ms: st.ms };
        for (const type of [NATIVE_TYPE, DRAWN_TYPE]) {
          const lines = type.readLines(now), rows = type.replyRows(type.readLines(plain), facts);
          const top = tops[type.look] = Math.max(tops[type.look], Math.max(0, lines.length - rows));
          const view = replyView(type, lines, top, rows);
          if (type === NATIVE_TYPE) {
            const body = bodyOf(view);
            row.native = body === prevNative ? 0 : utf8(body);
            prevNative = body;
          } else {
            const f = painter.frame(drawn.drawView(view), orrery, t, true);
            row.drawn = { tiles: f.cost.tiles, png: f.cost.png, gray4: f.cost.gray4, lz4: f.cost.lz4, paint: f.cost.paintMs, encode: f.cost.encodeMs, names: f.tiles.map(x => x.slot.name) };
          }
        }
        if (!first) steps.push(row);
        first = false;
        t += st.ms;
      }
      out[`stream_${lang}`] = steps;
    }

    // Scrolling the whole reply, top to end, a window less a line a swipe.
    for (const lang of ['en', 'ru']) {
      const plain = text.plainText(demo.LONG_REPLY[lang]);
      const res = {};
      for (const type of [NATIVE_TYPE, DRAWN_TYPE]) {
        const lines = type.readLines(plain), rows = type.replyRows(lines, facts);
        const painter = new Painter(), orrery = new Orrery(drawn.DRAWN_ORRERY);
        const swipes = [];
        let top = 0;
        if (type === DRAWN_TYPE) painter.frame(drawn.drawView(replyView(type, lines, 0, rows)), orrery, 0, true);
        while (top < lines.length - rows) {
          top = Math.min(lines.length - rows, top + Math.max(1, rows - 1));
          const view = replyView(type, lines, top, rows);
          if (type === NATIVE_TYPE) swipes.push({ bytes: utf8(bodyOf(view)) });
          else { const f = painter.frame(drawn.drawView(view), orrery, 0, true); swipes.push({ tiles: f.cost.tiles, png: f.cost.png, gray4: f.cost.gray4, lz4: f.cost.lz4 }); }
        }
        res[type.look] = { lines: lines.length, rows, swipes };
      }
      out[`scroll_${lang}`] = res;
    }

    // The glance's planets moving (a working planet, a needs-you bracket), ~3 frames a second for 4 s:
    // on their own 32 x 144 image (the column plan, and the native look) vs inside a 288 x 144 tile.
    {
      const orreryView = { sun: { pct: 72, limit: false }, planets: [{ state: 'needs', focused: true }, { state: 'working', focused: false }, { state: 'done', focused: false }, null] };
      const glance = {
        kind: 'glance', left: 'hypernova/', clock: '14:05', right: ['72%', 'resets in 1h 12m'], empty: '', hint: ['tap answer', 'hold talk to 0stems'], orrery: orreryView, look: 'hypernova',
        rows: [
          { project: '0stems', detail: 'wants to push to main', right: '1m', state: 'needs', focused: true },
          { project: 'hypernova', detail: 'Bash: ./gradlew build', right: '2m', state: 'working', focused: false, tag: ['2 agents running'] },
          { project: 'hyperscribe', detail: 'transcript export', right: '3m', state: 'done', focused: false },
        ], newRow: { label: 'New session', focused: false },
      };
      const own = new Orrery(drawn.DRAWN_ORRERY), levels = c => { const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; const l = new Uint8Array(c.width * c.height); for (let i = 0; i < l.length; i++) l[i] = Math.round(d[i * 4] / 17); return l; };
      const column = [];
      let last = 0;
      for (let k = 0; k < 12; k++) {
        const f = own.frame(orreryView, 1_000_000 + k * 340, false);
        if (f.hash === last) continue;
        last = f.hash;
        const c = tiles.tileCost(levels(own.canvas), 32, { x: 0, y: 0, w: 32, h: 144 });
        column.push({ png: f.png.length, gray4: c.gray4, lz4: c.lz4 });
      }
      // Inside a tile: the same planets, moving, drawn into the first full tile.
      const moving = new Orrery(drawn.DRAWN_ORRERY);
      const planets = { canvas: moving.canvas, frame: (v, now) => moving.frame(v, now, false) };
      const page = { ...drawn.drawView({ ...glance, band: null }), plan: 'full' };
      const painter = new Painter();
      painter.frame(page, planets, 1_000_000, false);
      const inTile = [];
      for (let k = 1; k < 12; k++) { const f = painter.frame(page, planets, 1_000_000 + k * 340, true); if (f.cost.tiles) inTile.push({ tiles: f.cost.tiles, png: f.cost.png, gray4: f.cost.gray4, lz4: f.cost.lz4 }); }
      out.planets = { column, inTile };
    }

    // How long a whole page takes: drawn, quantised, hashed (paint) and its tiles encoded (encode), every tile new.
    {
      const plain = text.plainText(demo.LONG_REPLY.en);
      const lines = DRAWN_TYPE.readLines(plain), rows = DRAWN_TYPE.replyRows(lines, facts);
      const views = {
        glance: { kind: 'glance', left: 'hypernova/', clock: '14:05', right: ['42%', 'resets in 2h 11m'], empty: '', hint: ['tap answer', 'hold talk to 0stems'], look: 'hypernova',
          orrery: { sun: { pct: 42, limit: false }, planets: [{ state: 'needs', focused: true }, { state: 'working', focused: false }] },
          rows: [{ project: '0stems', detail: 'wants to push to main', right: '1m', state: 'needs', focused: true }, { project: 'hypernova', detail: 'Bash: ./gradlew build', right: '2m', state: 'working', focused: false, tag: ['2 agents running'] }],
          newRow: { label: 'New session', focused: false } },
        reply: replyView(DRAWN_TYPE, lines, 0, rows),
        approval: { kind: 'card', head: ['NEEDS YOU', '0stems'], right: 'auto-deny in 9m', lead: 'wants to run', body: DRAWN_TYPE.command('git commit -am "Export remembers the last folder" && git push origin main', ['Ship the fix']), facts: ['Ship the fix'],
          hint: ['tap allow', 'double tap deny', 'swipe back'], orrery: { sun: null, planets: [], seam: { state: 'needs' } }, role: 'approval', look: 'hypernova' },
      };
      const timing = {};
      for (const [name, v] of Object.entries(views)) {
        const painter = new Painter(), orrery = new Orrery(drawn.DRAWN_ORRERY), d = drawn.drawView(v);
        const runs = [];
        for (let k = 0; k < 25; k++) { painter.reset(); const t0 = performance.now(); const f = painter.frame(d, orrery, 0, false); runs.push({ paint: f.cost.paintMs, encode: f.cost.encodeMs, total: performance.now() - t0, tiles: f.cost.tiles, png: f.cost.png, gray4: f.cost.gray4, lz4: f.cost.lz4 }); }
        // The native page for the same view: built from text, no pixels.
        const t0 = performance.now();
        for (let k = 0; k < 25; k++) pages.buildPage({ ...v, look: undefined });
        timing[name] = { runs: runs.slice(3), nativeBuildMs: (performance.now() - t0) / 25, nativeText: pages.buildPage({ ...v, look: undefined }).boxes.reduce((n, b) => n + utf8(b.content), 0) };
      }
      out.timing = timing;
    }
    return out;
  });
}

const browser = await chromium.launch();
const results = {};
for (const throttle of [1, 4]) {
  const page = await browser.newPage();
  page.on('pageerror', e => console.error('page error', e.message));
  await page.goto(`${base}/?demo=1&quiet`, { waitUntil: 'networkidle' });
  if (throttle > 1) { const cdp = await page.context().newCDPSession(page); await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle }); }
  results[`cpu${throttle}x`] = await run(page);
  await page.close();
}
await browser.close();
if (outFile) writeFileSync(outFile, JSON.stringify(results, null, 1));

// ---------------------------------------------------------------- summary
const r = results.cpu1x;
const mean = a => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0);
const med = a => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.floor(s.length / 2)] : 0; };
const max = a => Math.max(0, ...a);
const kb = b => `${(b / 1024).toFixed(1)} KB`;
for (const lang of ['en', 'ru']) {
  const s = r[`stream_${lang}`];
  const d = s.map(x => x.drawn), dur = s.reduce((n, x) => n + x.ms, 0) / 1000;
  console.log(`\nStreamed reply (${lang}): ${s.length} steps over ${dur.toFixed(0)} s (${(s.length / dur).toFixed(2)} updates/s; closest two ${Math.min(...s.map(x => x.ms))} ms apart)`);
  console.log(`  drawn : tiles/update mean ${mean(d.map(x => x.tiles)).toFixed(2)} (max ${max(d.map(x => x.tiles))}); PNG mean ${kb(mean(d.map(x => x.png)))}, 4-bit ${kb(mean(d.map(x => x.gray4)))}, LZ4 mean ${kb(mean(d.map(x => x.lz4)))} (median ${kb(med(d.map(x => x.lz4)))}, max ${kb(max(d.map(x => x.lz4)))}); whole reply LZ4 ${kb(d.reduce((n, x) => n + x.lz4, 0))}, PNG ${kb(d.reduce((n, x) => n + x.png, 0))}`);
  console.log(`  native: text update mean ${mean(s.map(x => x.native)).toFixed(0)} B (max ${max(s.map(x => x.native))} B); whole reply ${kb(s.reduce((n, x) => n + x.native, 0))}`);
  const sc = r[`scroll_${lang}`];
  for (const look of ['native', 'hypernova']) {
    const v = sc[look];
    const bytes = look === 'native' ? v.swipes.map(x => x.bytes) : v.swipes.map(x => x.lz4);
    console.log(`  scroll ${look}: ${v.lines} lines, ${v.rows} in view, ${v.swipes.length} swipes to the end; per swipe ${look === 'native' ? `${mean(bytes).toFixed(0)} B text` : `${mean(v.swipes.map(x => x.tiles)).toFixed(1)} tiles, LZ4 ${kb(mean(bytes))}, PNG ${kb(mean(v.swipes.map(x => x.png)))}`}`);
  }
}
const p = r.planets;
console.log(`\nPlanets moving: own 32x144 image ${kb(mean(p.column.map(x => x.lz4)))} LZ4 (${kb(mean(p.column.map(x => x.png)))} PNG) a frame; inside a 288x144 tile ${kb(mean(p.inTile.map(x => x.lz4)))} LZ4 (${kb(mean(p.inTile.map(x => x.png)))} PNG) a frame`);
for (const [cpu, res] of Object.entries(results)) {
  for (const [name, t] of Object.entries(res.timing)) {
    console.log(`Render ${name} @${cpu}: paint ${med(t.runs.map(x => x.paint)).toFixed(1)} ms + encode ${med(t.runs.map(x => x.encode)).toFixed(1)} ms (${t.runs[0].tiles} tiles, PNG ${kb(t.runs[0].png)}, LZ4 ${kb(t.runs[0].lz4)}); native page ${t.nativeBuildMs.toFixed(2)} ms, ${t.nativeText} B of text`);
  }
  const s = res.stream_en.map(x => x.drawn);
  console.log(`Stream step @${cpu}: paint ${med(s.map(x => x.paint)).toFixed(1)} ms + encode ${med(s.map(x => x.encode)).toFixed(1)} ms (median)`);
}
