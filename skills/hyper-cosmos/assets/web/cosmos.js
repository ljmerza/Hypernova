/*
 * Hyper cosmos for the web: no dependencies, one file.
 *
 *   <canvas id="sky"></canvas>   (position: fixed; inset: 0; z-index: 0)
 *   <canvas id="grain"></canvas> (position: fixed; inset: 0; z-index: 60; pointer-events: none;
 *                                 mix-blend-mode: overlay; opacity: .32)
 *   const sky = HyperCosmos.mount({
 *     canvas: document.getElementById('sky'),
 *     grain: document.getElementById('grain'),
 *     death: true,                 // a backdrop layer (z-index 58) drains the page to grey when the sky dies
 *     band: () => ({ top: 96, bottom: innerHeight * 0.55 }),  // CSS px where the system lives
 *   })
 *   sky.setData({
 *     usage: 0.42,                 // 0..1, the sun's budget (5-hour window, monthly budget, …)
 *     resetAt: Date.now() + 2*3600e3,
 *     limitedAt: null,             // ms when the limit was hit, or null
 *     week: 0.18,                  // 0..1, the slow budget (the universe's light)
 *     weekLimitedAt: null, weekResetAt: Date.now() + 61*3600e3,
 *     groups: [{ name: 'hypernova', items: [{ id: 'a3', label: 'cosmos renderer', state: 'working', minutes: 190 }] }],
 *   })                             // minutes: how long the item has run (planets and moons grow with it)
 *   sky.onHit = hit => { ... }   // {type:'sun'} | {type:'group', name} | {type:'item', group, id}
 *   sky.zoom('hypernova') / sky.zoom(null)
 *
 * A limit that reaches its resetAt is reborn by itself (send fresh data when you have it).
 * Taps on the sky are read from the whole document, ignoring links, buttons, inputs and .hn-card.
 * Supernova and Big Bang draw on their own top canvas (created for you, z-index 59: above the
 * page, under the grain), so the flash covers the chrome too.
 *
 * Mirrors the Compose implementation (assets/compose/Cosmos.kt + Solar.kt): one ambient clock at
 * 30fps that pauses when hidden, 0stems DigitalCosmos tiers, overlay grain at 24fps, a sun that
 * ages with usage, supernova → cooldown → rebirth, heat death → Big Bang, labels that glide.
 * prefers-reduced-motion: a static frame, but timelines still advance once a second.
 */
(function (global) {
  'use strict';

  const C = {
    bg: [20, 17, 14], ink: [245, 242, 235], muted: [181, 176, 166], dim: [122, 116, 104],
    accent: [127, 255, 160], bright: [182, 255, 201], accentDim: [74, 140, 94],
    amber: [232, 179, 65], danger: [255, 120, 120],
  };
  const rgba = (c, a) => `rgba(${c[0]|0},${c[1]|0},${c[2]|0},${Math.max(0, Math.min(1, a))})`;
  const lerp = (a, b, k) => a + (b - a) * k;
  const lerpC = (a, b, k) => [lerp(a[0], b[0], k), lerp(a[1], b[1], k), lerp(a[2], b[2], k)];
  const clamp = (x, a, b) => Math.max(a, Math.min(b, x));
  const smooth = x => x * x * (3 - 2 * x);
  const expo = x => x >= 1 ? 1 : 1 - Math.pow(2, -10 * x); // close to cubic-bezier(.16,1,.3,1)
  const rnd = (a, b) => a + Math.random() * (b - a);
  const hash = s => { let h = 2166136261; for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619); return (h >>> 0) / 4294967295; };

  const SUN_STOPS = [0, .5, .7, .85, .95, 1];
  const SUN_COLS = [C.accent, [207, 255, 180], [243, 227, 154], C.amber, [255, 122, 74], [255, 90, 60]];
  const SUN_R = [4.5, 6.5, 8, 10.5, 14, 16];
  const COOL_STOPS = [0, .25, .55, .8, 1];
  const COOL_COLS = [[242, 246, 255], [221, 232, 255], [255, 154, 90], [138, 42, 26], [42, 20, 16]];
  function ramp(stops, vals, u, isColor) {
    u = clamp(u, 0, 1);
    for (let i = 1; i < stops.length; i++) if (u <= stops[i]) {
      const k = (u - stops[i - 1]) / (stops[i] - stops[i - 1]);
      return isColor ? lerpC(vals[i - 1], vals[i], k) : lerp(vals[i - 1], vals[i], k);
    }
    return vals[vals.length - 1];
  }

  function mount(opts) {
    const canvas = opts.canvas, ctx = canvas.getContext('2d');
    const gcv = opts.grain, gctx = gcv ? gcv.getContext('2d') : null;
    const ov = opts.overlay || (() => {
      const c = document.createElement('canvas'); c.className = 'hn-overlay'; c.setAttribute('aria-hidden', 'true');
      Object.assign(c.style, { position: 'fixed', inset: '0', width: '100%', height: '100%', zIndex: '59', pointerEvents: 'none' });
      document.body.appendChild(c); return c;
    })();
    const octx = ov.getContext('2d');
    let ovDirty = false;
    // When the sky dies the whole page drains to grey-black. A fixed backdrop-filter layer does it
    // without turning the page into a containing block (fixed and sticky layout keep working), and
    // it sits under the overlay canvas, so a Big Bang's white flood stays white.
    const veil = opts.death === false ? null : (() => {
      const v = document.createElement('div'); v.className = 'hn-death'; v.setAttribute('aria-hidden', 'true');
      Object.assign(v.style, { position: 'fixed', inset: '0', zIndex: '58', pointerEvents: 'none', display: 'none' });
      document.body.appendChild(v); return v;
    })();
    const bandFn = opts.band || (() => ({ top: 96, bottom: innerHeight * 0.55 }));
    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const coarse = matchMedia('(pointer: coarse)').matches;
    const dpr = Math.min(devicePixelRatio || 1, coarse ? 1.5 : 2);
    let W = 0, H = 0;
    let data = { usage: 0, resetAt: 0, limitedAt: null, week: 0, weekLimitedAt: null, weekResetAt: 0, groups: [] };
    const api = { onHit: null };
    const epoch = performance.now();
    const wall = () => (performance.now() - epoch) / 1000;

    // ── Ambient sky (0stems DigitalCosmos) ──
    let stars = [], nebulae = [];
    function build() {
      const minSide = Math.min(W, H);
      nebulae = [[127, 255, 160], [150, 255, 200], [180, 255, 140], [105, 255, 185]].map(c => ({
        x: Math.random() * W, y: Math.random() * H, r: minSide * rnd(.3, .55) * 1.5, a: rnd(.07, .14), c,
        vx: rnd(-.25, .25), vy: rnd(-.25, .25), ph: Math.random() * 6.28, sp: rnd(.08, .18),
      }));
      const clusters = Array.from({ length: 6 }, () => ({ x: Math.random() * W, y: Math.random() * H, r: minSide * rnd(.12, .22) }));
      stars = Array.from({ length: 95 }, () => {
        const r = Math.random(), anchor = r < .07, mid = !anchor && r < .35;
        let x = Math.random() * W, y = Math.random() * H;
        if (Math.random() < .55) { const c = clusters[(Math.random() * 6) | 0], a = Math.random() * 6.28, d = Math.pow(Math.random(), .6) * c.r; x = ((c.x + Math.cos(a) * d) % W + W) % W; y = ((c.y + Math.sin(a) * d) % H + H) % H; }
        const sp = anchor ? rnd(.08, .18) : rnd(.15, .55), ang = Math.random() * 6.28;
        return { x, y, s: anchor ? rnd(2.4, 3.6) : mid ? rnd(1.1, 2) : rnd(.5, 1), a: anchor ? rnd(.6, .9) : mid ? rnd(.28, .5) : rnd(.1, .22),
          vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp, ph: Math.random() * 6.28, ps: rnd(.2, .45), tw: Math.random() * 6.28, ts: rnd(.8, 2.2), anchor,
          label: (anchor || Math.random() < .15) ? ('0123456789abcdef'[(Math.random() * 16) | 0] + '0123456789abcdef'[(Math.random() * 16) | 0]) : null };
      });
    }
    function resize() {
      const ow = W, oh = H;
      W = canvas.width = Math.max(1, Math.floor(canvas.clientWidth * dpr));
      H = canvas.height = Math.max(1, Math.floor(canvas.clientHeight * dpr));
      ov.width = W; ov.height = H;
      if (!stars.length) build(); else { const sx = W / ow, sy = H / oh; stars.forEach(s => { s.x *= sx; s.y *= sy; }); nebulae.forEach(n => { n.x *= sx; n.y *= sy; }); }
      layoutKey = '';
    }

    // ── Solar system ──
    const planets = new Map();
    let layoutKey = '', cx = 0, cy = 0, aMin = 0, aMax = 0, bMax = 0, bandTop = 0;
    let uCur = NaN, sunPhase = 0;
    let limitedSeen = false, weekSeen = false, loud = false;
    let t0 = -1e9, t1 = -1e9, tDark = -1e9, tBang = -1e9;
    let camK = 0, zoomName = null, deathSent = -1;
    const debris = [], neb = [];

    function layout() {
      const b = bandFn();
      bandTop = b.top * dpr;
      const names = data.groups.map(g => g.name);
      for (const k of [...planets.keys()]) if (!names.includes(k)) planets.delete(k);
      data.groups.forEach((g, i) => {
        if (!planets.has(g.name)) {
          const h = hash(g.name);
          planets.set(g.name, { name: g.name, tilt: .36 + h * .08, theta: h * 6.28, moon: h * 6, a: NaN, x: NaN, y: NaN,
            free: limitedSeen || weekSeen, cap: (limitedSeen || weekSeen) ? 0 : 1, capBase: 0, capFrom: 0, fx: NaN, fy: NaN, vx: 0, vy: 0, ang: NaN, la: 0, shade: hash(g.name + '~') });
        }
        const p = planets.get(g.name); p.items = g.items; p.index = i;
      });
      aMax = W / 2 - 34 * dpr; aMin = W * .15; bMax = aMax * .44;
      cx = W / 2; cy = bandTop + 30 * dpr + bMax;
      const n = planets.size;
      [...planets.values()].forEach((p, i) => { p.aT = n === 1 ? W * .28 : aMin + (aMax - aMin) * i / (n - 1); if (isNaN(p.a)) p.a = p.aT; });
    }

    function blast(fresh, t) {
      loud = fresh;
      for (const p of planets.values()) {
        p.free = true; p.capBase = 0;
        if (isNaN(p.x)) { p.fx = cx + p.a * Math.cos(p.theta); p.fy = cy + p.a * p.tilt * Math.sin(p.theta); } else { p.fx = p.x; p.fy = p.y; }
        const dx = p.fx - cx, dy = p.fy - cy, l = Math.max(1, Math.hypot(dx, dy)), sp = rnd(70, 160) * dpr;
        if (fresh) { p.vx = dx / l * sp + rnd(-40, 40) * dpr; p.vy = dy / l * sp * .7 + rnd(-30, 30) * dpr; }
        else { p.fx = cx + dx * 1.35; p.fy = cy + dy * 1.25; p.vx = rnd(-5, 5) * dpr; p.vy = rnd(-4, 4) * dpr; }
      }
      debris.length = 0;
      for (let i = 0; i < 110; i++) { const a = Math.random() * 6.28, s = (90 + Math.pow(Math.random(), .7) * 380) * dpr; debris.push({ vx: Math.cos(a) * s, vy: Math.sin(a) * s, life: rnd(1.6, 5), k: (Math.random() * 3) | 0 }); }
      neb.length = 0;
      const hot = [C.amber, [255, 107, 74], [243, 227, 154]], cool = [C.accent, [95, 224, 200], [157, 255, 184]];
      for (let i = 0; i < 9; i++) neb.push({ ang: Math.random() * 6.28, d: rnd(.12, .57) * aMax, r: rnd(.28, .7) * aMax, spin: rnd(-.03, .03), hot: hot[i % 3], cool: cool[i % 3] });
      if (fresh && navigator.vibrate) navigator.vibrate([40, 120, 80]);
    }

    let limActive = false, weekActive = false;
    function transitions(now, wt) {
      const wk = weekActive;
      if (wk && !weekSeen) {
        weekSeen = true; tDark = wt - (now - data.weekLimitedAt) / 1000;
        for (const p of planets.values()) {
          p.free = true; p.cap = 0; if (!isNaN(p.x)) { p.fx = p.x; p.fy = p.y; }
          const dx = (p.fx || cx) - cx, dy = (p.fy || cy) - cy, l = Math.max(1, Math.hypot(dx, dy)), sp = rnd(9, 18) * dpr;
          p.vx = dx / l * sp; p.vy = dy / l * sp * .6;
        }
      } else if (!wk && weekSeen) {
        weekSeen = false; tBang = reduced ? wt - 6 : wt; limitedSeen = limActive; t1 = -1e9; uCur = usageNow();
        for (const p of planets.values()) { p.free = true; p.fx = cx; p.fy = cy; p.vx = p.vy = 0; p.capBase = 0; p.cap = 0; p.capFrom = tBang + 1.6 + p.index * .22; }
      }
      if (weekSeen) return;
      const lim = limActive;
      if (lim && !limitedSeen) { limitedSeen = true; const age = (now - data.limitedAt) / 1000, fresh = age < 3 && !reduced; t0 = fresh ? wt : wt - Math.max(age, 6); blast(fresh, wt); }
      else if (!lim && limitedSeen) { limitedSeen = false; t1 = reduced ? wt - 4 : wt; uCur = usageNow(); for (const p of planets.values()) if (p.free) { p.capBase = p.cap; p.capFrom = t1 + p.index * .18; } }
    }

    // ── Grain: tiles once, a random tile + offset 24×/s ──
    let tiles = null;
    function makeTiles() {
      tiles = Array.from({ length: 4 }, () => {
        const c = document.createElement('canvas'); c.width = c.height = 192; const g = c.getContext('2d'), im = g.createImageData(192, 192);
        for (let i = 0; i < im.data.length; i += 4) { const v = Math.random() * 255; im.data[i] = im.data[i + 1] = im.data[i + 2] = v; im.data[i + 3] = Math.random() * 90; }
        g.putImageData(im, 0, 0); return c;
      });
    }
    function drawGrain(t) {
      if (!gctx) return;
      if (gcv.width !== gcv.clientWidth || gcv.height !== gcv.clientHeight) { gcv.width = gcv.clientWidth; gcv.height = gcv.clientHeight; }
      if (!tiles) makeTiles();
      // The grain canvas is at CSS resolution; ~1.6 CSS px per cell, softly upscaled like 0stems.
      const f = reduced ? 0 : Math.max(0, Math.floor(t * 24)), tile = tiles[f % 4], cell = 1.6;
      gctx.setTransform(1, 0, 0, 1, 0, 0); gctx.clearRect(0, 0, gcv.width, gcv.height);
      gctx.imageSmoothingEnabled = true;
      const ox = -((f * 73) % 192) * cell, oy = -((f * 151) % 192) * cell;
      gctx.setTransform(cell, 0, 0, cell, ox, oy);
      gctx.fillStyle = gctx.createPattern(tile, 'repeat');
      gctx.fillRect(0, 0, gcv.width / cell + 400, gcv.height / cell + 400);
      gctx.setTransform(1, 0, 0, 1, 0, 0);
    }

    // ── Frame ──
    let lastT = -1;
    function frame(t) {
      const now = Date.now(), wt = wall();
      const dt = lastT < 0 ? 0 : clamp(t - lastT, 0, .05); lastT = t;
      const key = data.groups.map(g => g.name + ':' + g.items.length).join('|') + '|' + bandFn().top;
      if (key !== layoutKey) { layoutKey = key; layout(); }
      else for (const g of data.groups) { const p = planets.get(g.name); if (p) p.items = g.items; }
      limActive = data.limitedAt != null && !(data.resetAt && now >= data.resetAt);
      weekActive = data.weekLimitedAt != null && !(data.weekResetAt && now >= data.weekResetAt);
      transitions(now, wt);
      const target = (limActive || weekActive) ? 1 : usageNow();
      uCur = isNaN(uCur) || reduced ? target : uCur + (target - uCur) * (1 - Math.exp(-dt * 4));
      if (!reduced) sunPhase += dt * 6.283 / (6 - 3.8 * clamp(uCur, 0, 1));
      const remnant = limitedSeen, sb = wt - t0, sr = wt - t1, sd = wt - tDark, sg = wt - tBang;
      const reborn = !remnant && !weekSeen && sr >= 0 && sr < 4, banging = !weekSeen && sg >= 0 && sg < 6;
      const cool = weekSeen ? (reduced ? 1 : smooth(clamp(sd / 9, 0, 1))) : 0;
      const dark = weekSeen ? cool : banging ? 1 - expo(clamp((sg - .6) / 3, 0, 1)) : 0;
      const dead = remnant ? (reduced ? 1 : clamp(sb, 0, 1)) : reborn ? 1 - clamp((sr - 2) / 1.5, 0, 1) : 0;
      const names = weekSeen ? smooth(clamp(sd / 1.5, 0, 1)) : dark;
      const labelsLive = (1 - dead) * (1 - names) * (banging ? clamp((sg - 3.5) / 1.5, 0, 1) : 1);
      const death = weekSeen ? cool : banging ? 1 - smooth(clamp((sg - .6) / 2.4, 0, 1)) : remnant ? (reduced || !loud ? 1 : smooth(clamp((sb - .4) / 2.2, 0, 1))) : reborn ? 1 - smooth(clamp((sr - .1) / 2.2, 0, 1)) : 0;
      if (veil && (Math.abs(death - deathSent) > .004 || (death > .005) !== (deathSent > .005))) {
        deathSent = death;
        if (death > .005) { const f = `saturate(${1 - .97 * death}) brightness(${1 - .42 * death})`; veil.style.display = 'block'; veil.style.backdropFilter = f; veil.style.webkitBackdropFilter = f; }
        else veil.style.display = 'none';
      }
      const light = (1 - .65 * Math.pow(clamp(data.week, 0, .99), 1.5)) * (weekSeen ? 1 - smooth(clamp((cool - .15) / .85, 0, 1)) : 1);
      const revealR = weekSeen ? 0 : banging ? expo(clamp((sg - .5) / 3.5, 0, 1)) * Math.hypot(W, H) : Infinity;

      // Ambient.
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = rgba(C.bg, 1); ctx.fillRect(0, 0, W, H);
      const wa = t * .04, wx = Math.cos(wa) * .4, wy = Math.sin(wa) * .4;
      const nebL = light * (revealR === Infinity ? 1 : clamp(revealR / Math.hypot(W, H), 0, 1));
      for (const n of nebulae) {
        n.x += (n.vx + wx * .5) * dt * dpr; n.y += (n.vy + wy * .5) * dt * dpr;
        if (n.x < -n.r) n.x = W + n.r; if (n.x > W + n.r) n.x = -n.r; if (n.y < -n.r) n.y = H + n.r; if (n.y > H + n.r) n.y = -n.r;
        const a = n.a * (.6 + .4 * Math.sin(t * n.sp + n.ph)) * nebL; if (a < .002) continue;
        const g = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, n.r);
        g.addColorStop(0, rgba(n.c, a)); g.addColorStop(.3, rgba(n.c, a * .55)); g.addColorStop(.6, rgba(n.c, a * .2)); g.addColorStop(1, rgba(n.c, 0));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(n.x, n.y, n.r, 0, 6.283); ctx.fill();
      }
      const bandA = bandTop - 16 * dpr, bandB = cy + bMax + 56 * dpr;
      ctx.font = `${9 * dpr}px "JetBrains Mono", ui-monospace, monospace`; ctx.textBaseline = 'middle';
      for (const s of stars) {
        s.x += (s.vx + wx) * dt * dpr; s.y += (s.vy + wy) * dt * dpr;
        if (s.x < 0) s.x += W; if (s.x > W) s.x -= W; if (s.y < 0) s.y += H; if (s.y > H) s.y -= H;
        const inBand = s.y > bandA && s.y < bandB;
        const rev = revealR === Infinity ? 1 : clamp((revealR - Math.hypot(s.x - cx, s.y - cy)) / (80 * dpr), 0, 1);
        const a = s.a * (.7 + .3 * Math.sin(t * s.ps + s.ph)) * (.85 + .15 * Math.sin(t * s.ts + s.tw)) * (inBand ? .6 : 1) * (light <= .001 ? 0 : .4 + .6 * light) * rev;
        if (a < .002) continue;
        const r = s.s * dpr;
        if (s.anchor) { ctx.fillStyle = rgba(C.accent, a * .05); ctx.beginPath(); ctx.arc(s.x, s.y, r * 4.5, 0, 6.283); ctx.fill(); ctx.fillStyle = rgba(C.accent, a * .14); ctx.beginPath(); ctx.arc(s.x, s.y, r * 2.5, 0, 6.283); ctx.fill(); ctx.fillStyle = rgba(C.accent, a * .45); ctx.beginPath(); ctx.arc(s.x, s.y, r * 1.4, 0, 6.283); ctx.fill(); }
        ctx.fillStyle = rgba(C.accent, a); ctx.beginPath(); ctx.arc(s.x, s.y, r, 0, 6.283); ctx.fill();
        if (s.label) { ctx.fillStyle = rgba(C.accent, a * (inBand ? .2 : .55)); ctx.fillText(s.label, s.x + r + 7 * dpr, s.y); }
      }

      // Planets.
      const life = 1 - dark, wob = clamp((uCur - .8) / .2, 0, 1) * 5 * dpr * (remnant || weekSeen ? 0 : 1);
      for (const p of planets.values()) {
        p.a = reduced ? p.aT : p.a + (p.aT - p.a) * (1 - Math.exp(-dt * 2.5));
        const om = (6.283 / 48) * Math.pow(aMin / p.a, 1.5);
        if (!reduced) { p.theta += dt * om; p.moon += dt * (.3 + .7 * p.cap); }
        const ox = cx + p.a * Math.cos(p.theta) + Math.sin(t * 3.1 + p.shade * 6) * wob, oy = cy + p.a * p.tilt * Math.sin(p.theta) + Math.cos(t * 2.3 + p.shade * 6) * wob * .6;
        if (p.free) {
          if (isNaN(p.fx)) { p.fx = ox; p.fy = oy; }
          if (!reduced && weekSeen) { p.fx += p.vx * dt; p.fy += p.vy * dt; }
          else if (!reduced) {
            const d = Math.exp(-dt * .9); p.vx = p.vx * d + Math.sin(t * .7 + p.shade * 6) * 8 * dpr * dt; p.vy = p.vy * d + Math.cos(t * .5 + p.shade * 6) * 6 * dpr * dt;
            p.fx += p.vx * dt; p.fy += p.vy * dt;
            const l = 20 * dpr, r = W - 20 * dpr, tp = cy - bMax - 6 * dpr, bt = cy + bMax + 6 * dpr;
            if (p.fx < l) { p.fx = l; p.vx = Math.abs(p.vx) * .5; } if (p.fx > r) { p.fx = r; p.vx = -Math.abs(p.vx) * .5; }
            if (p.fy < tp) { p.fy = tp; p.vy = Math.abs(p.vy) * .5; } if (p.fy > bt) { p.fy = bt; p.vy = -Math.abs(p.vy) * .5; }
          }
          const prog = limActive ? clamp((now - data.limitedAt) / Math.max(1, data.resetAt - data.limitedAt), 0, 1) : 1;
          p.cap = weekSeen ? 0 : remnant ? clamp((prog - .88) / .12, 0, 1) * .25 : reduced ? 1 : p.capBase + (1 - p.capBase) * expo(clamp((wt - p.capFrom) / 2.3, 0, 1));
          p.x = p.fx + (ox - p.fx) * p.cap; p.y = p.fy + (oy - p.fy) * p.cap;
          if (!remnant && !weekSeen && p.cap >= .999) { p.free = false; p.cap = 1; p.fx = NaN; }
        } else { p.cap = 1; p.x = ox; p.y = oy; }
      }
      // Camera.
      const zp = zoomName && planets.get(zoomName);
      camK = reduced ? (zp && !remnant && !weekSeen ? 1 : 0) : camK + ((zp && !remnant && !weekSeen ? 1 : 0) - camK) * (1 - Math.exp(-dt * 3.2));
      const ck = expo(clamp(camK, 0, 1)), zs = 1 + 1.6 * ck;
      const camP = zp || (camK > .001 ? lastCam : null); if (zp) lastCam = zp;
      const camX = cx + ((camP ? camP.x : cx) - cx) * ck, camY = cy + ((camP ? camP.y : cy) - cy) * ck, vY = cy + bMax * .15 * ck;
      const S = (x, y) => [cx + (x - camX) * zs, vY + (y - camY) * zs];
      for (const p of planets.values()) if (!isNaN(p.x)) { const q = S(p.x, p.y); p.sx = q[0]; p.sy = q[1]; }
      hitZoom = zs;

      ctx.save(); ctx.translate(cx, vY); ctx.scale(zs, zs); ctx.translate(-camX, -camY);
      // A fading tail along an ellipse behind angle th, brightest at the body and stopping `gap` short of it.
      const tail = (ox, oy, ra, rb, th, arc, col, a, w, dx, dy, gap) => {
        const n = 40, hx = ox + ra * Math.cos(th) + dx, hy = oy + rb * Math.sin(th) + dy;
        let px = ox + ra * Math.cos(th - arc), py = oy + rb * Math.sin(th - arc);
        ctx.lineCap = 'butt';
        for (let i = 1; i <= n; i++) {
          const k = i / n, an = th - arc * (1 - k), x = ox + ra * Math.cos(an) + dx * k, y = oy + rb * Math.sin(an) + dy * k;
          if (Math.hypot(x - hx, y - hy) < gap) break;
          ctx.strokeStyle = rgba(col, a * k * k); ctx.lineWidth = w * (.35 + .65 * k);
          ctx.beginPath(); ctx.moveTo(px, py); ctx.lineTo(x, y); ctx.stroke();
          px = x; py = y;
        }
      };
      // A small planet: lit toward (lx, ly), a rim, and meridians that slide across it as `spin` turns.
      const sphere = (x, y, r, col, spin, lx, ly, a, sw, meridians) => {
        const gx = x + lx * r * .64, gy = y + ly * r * .64, g = ctx.createRadialGradient(gx, gy, 0, gx, gy, r * 1.7);
        g.addColorStop(0, rgba(col, .75 * a)); g.addColorStop(.5, rgba(col, .22 * a)); g.addColorStop(1, rgba(col, .04 * a));
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, 6.283); ctx.fill();
        ctx.lineWidth = sw;
        if (meridians) for (let k = 0; k < 4; k++) {
          const lon = spin + k * 1.5708, depth = Math.cos(lon); if (depth <= .05) continue;
          const half = r * Math.sin(lon);
          ctx.strokeStyle = rgba(col, depth * clamp(.55 + .495 * (half / r) * lx, .1, .9) * a);
          ctx.beginPath(); ctx.ellipse(x, y, Math.max(Math.abs(half), .25), r, 0, half >= 0 ? -1.5708 : 1.5708, half >= 0 ? 1.5708 : 4.7124); ctx.stroke();
        }
        ctx.strokeStyle = rgba(col, .45 * a); ctx.beginPath(); ctx.arc(x, y, r, 0, 6.283); ctx.stroke();
      };
      const toward = (x, y, tx, ty) => { const d = Math.hypot(tx - x, ty - y); return d < 1 ? [-.7071, -.7071] : [(tx - x) / d, (ty - y) / d]; };
      const minutes = it => it.minutes ?? 30;
      // Orbits: each planet trails a fading arc of its own, not the whole ellipse.
      const oa = (remnant ? .05 * (1 - dead) : reborn ? .04 + .14 * expo(clamp(sr / 2.5, 0, 1)) : .18) * (1 - .6 * ck) * life;
      if (oa > .004) for (const p of planets.values()) {
        if (isNaN(p.x) || p.cap < .02) continue;
        const ex = cx + p.a * Math.cos(p.theta), ey = cy + p.a * p.tilt * Math.sin(p.theta);
        const col = lerpC(C.muted, p.items.some(i => i.state === 'needs') ? C.amber : lerpC(C.accentDim, C.bright, .35 + .5 * p.shade), .45);
        tail(cx, cy, p.a, p.a * p.tilt, p.theta, 1.9, col, Math.min(.55, oa * 2.6) * p.cap, 1.2 * dpr / zs, p.x - ex, p.y - ey, (p.r || 4 * dpr) + 2 * dpr / zs);
      }
      // Nebula remnant.
      if ((remnant || reborn) && neb.length) {
        const prog = remnant && limActive ? clamp((now - data.limitedAt) / Math.max(1, data.resetAt - data.limitedAt), 0, 1) : 1;
        const ex = reduced || !loud ? 1 : expo(clamp(sb / 3, 0, 1)), fin = reduced || !loud ? 1 : clamp(sb / 1.5, 0, 1), fout = remnant ? 1 : clamp(1 - sr / 2.5, 0, 1);
        for (const n of neb) {
          const ang = n.ang + (reduced ? 0 : t * n.spin), d = n.d * (.3 + .9 * ex) * (1 - .75 * prog), r = n.r * (.5 + .5 * ex) * (1 - .45 * prog);
          const x = cx + Math.cos(ang) * d, y = cy + Math.sin(ang) * d * .55, col = lerpC(n.hot, n.cool, prog), a = .16 * fin * fout * life;
          const g = ctx.createRadialGradient(x, y, 0, x, y, r); g.addColorStop(0, rgba(col, a)); g.addColorStop(.45, rgba(col, a * .45)); g.addColorStop(1, rgba(col, 0));
          ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, 6.283); ctx.fill();
        }
      }
      const drawPlanet = p => {
        if (isNaN(p.x)) return;
        const n = p.items.length, needs = p.items.some(i => i.state === 'needs'), working = p.items.some(i => i.state === 'working');
        const crowd = Math.sqrt(6 / Math.max(6, planets.size)), back = p.y < cy ? .85 : 1;
        // Bigger the longer its items have run: a few minutes, an hour, a long day.
        const len = p.items.reduce((m, it) => m + minutes(it), 0);
        const r = Math.min(9, 3.2 + 1.25 * Math.log(1 + len / 10)) * dpr * crowd * back; p.r = r;
        const base = lerpC(C.accentDim, C.bright, .35 + .5 * p.shade), col = lerpC(C.dim, needs ? C.amber : base, p.cap);
        const a = (working ? 1 : .8) * life * back * (.3 + .7 * p.cap) * (banging ? p.cap : 1);
        p.moons = [];
        if (a < .005) return;
        // It turns while an item in it works: spins up, and slows to a stop when they're done.
        const want = working && !reduced && p.cap > .99 ? 1 : 0;
        p.spinV = (p.spinV ?? 0) + (want - (p.spinV ?? 0)) * (1 - Math.exp(-dt * (want > (p.spinV ?? 0) ? 2.5 : 1.1)));
        p.spin = (p.spin ?? p.shade * 6) + dt * p.spinV * 6.283 / 4;
        const [lx, ly] = toward(p.x, p.y, cx, cy); // lit from the sun
        ctx.fillStyle = rgba(col, a * (.06 + .06 * p.spinV)); ctx.beginPath(); ctx.arc(p.x, p.y, r * 1.7, 0, 6.283); ctx.fill();
        sphere(p.x, p.y, r, col, p.spin, lx, ly, a, r * .12, r * zs >= 3.4 * dpr);
        if (needs && p.cap > .5 && p !== zp) {
          const k = reduced ? 1 : expo(clamp((t % 2.6) / .35, 0, 1)), b = r + (8 - 3 * k) * dpr, len = 3.5 * dpr;
          ctx.strokeStyle = rgba(C.amber, life); ctx.lineWidth = 1.2 * dpr / zs; ctx.beginPath();
          ctx.moveTo(p.x - b + len, p.y - b); ctx.lineTo(p.x - b, p.y - b); ctx.lineTo(p.x - b, p.y - b + len);
          ctx.moveTo(p.x + b - len, p.y + b); ctx.lineTo(p.x + b, p.y + b); ctx.lineTo(p.x + b, p.y + b - len); ctx.stroke();
        }
        let done = 0, shell = 0; const spread = 1.7 - .7 * p.cap; p.ringR = 0;
        while (done < n) {
          const cap = 4 + shell * 3, cnt = Math.min(cap, n - done), rr = (r + 5 * dpr * crowd + shell * 3.6 * dpr * crowd) * spread, om = 6.283 / (6 + shell * 3);
          for (let j = 0; j < cnt; j++) {
            const it = p.items[done + j], th = j * 6.283 / cnt + shell * .7 + p.moon * om, mx = p.x + Math.cos(th) * rr, my = p.y + Math.sin(th) * rr * .45;
            const mc = it.state === 'needs' ? C.amber : it.state === 'working' ? C.bright : C.muted, ma = (it.state === 'needs' ? 1 : it.state === 'working' ? .95 : .5) * life * back * (.3 + .7 * p.cap);
            // A moon grows with how long its item has run.
            const mr = Math.min(2.6, 1 + .42 * Math.log(1 + minutes(it) / 10)) * dpr * (p === zp ? 1.15 : 1);
            if (p === zp && ck > .02) {
              // Zoomed in: each moon trails its own fading arc, and shows its lit side.
              tail(p.x, p.y, rr, rr * .45, th, 1.3, mc, .32 * ma * ck, .9 * dpr / zs, 0, 0, mr + 1.5 * dpr / zs);
              const [lx, ly] = toward(mx, my, cx, cy);
              sphere(mx, my, mr, mc, 0, lx, ly, ma, mr * .14, false);
            } else {
              ctx.fillStyle = rgba(mc, ma * .12); ctx.beginPath(); ctx.arc(mx, my, mr * 2.4, 0, 6.283); ctx.fill();
              ctx.fillStyle = rgba(mc, ma); ctx.beginPath(); ctx.arc(mx, my, mr, 0, 6.283); ctx.fill();
            }
            p.moons.push({ it, x: mx, y: my, c: mc }); p.ringR = Math.max(p.ringR, rr + mr);
          }
          done += cnt; shell++;
        }
      };
      for (const p of planets.values()) if (p.y < cy) drawPlanet(p);
      // A star as a telescope photographs it (Solar.kt's star()): a bloom falling off smoothly, a
      // corona of uneven streamers, prominence arches when active, a bright chromosphere, a
      // photosphere white-hot at the centre and darker at the limb, granulation and spots once it's
      // big on screen, and faint diffraction spikes. It scintillates a little.
      const W3 = [255, 255, 255];
      const wav = (tt, sd) => .5 + .25 * Math.sin(tt * .73 + sd * 3.1) + .15 * Math.sin(tt * 1.37 + sd * 5.7) + .1 * Math.sin(tt * 2.91 + sd * 1.3);
      const star = (x, y, r, col, a, active, spikes) => {
        if (a <= .005 || r <= .1) return;
        const tw = reduced ? 1 : .9 + .1 * wav(t * 1.8, .3), hot = lerpC(col, W3, .8);
        const grad = (R, stops) => { const g = ctx.createRadialGradient(x, y, 0, x, y, R); for (const [o, c, al] of stops) g.addColorStop(o, rgba(c, al)); return g; };
        const disc = (R, fill) => { ctx.fillStyle = fill; ctx.beginPath(); ctx.arc(x, y, R, 0, 6.283); ctx.fill(); };
        const bR = r * 7.5 * (.45 + .55 / zs);
        disc(bR, grad(bR, [[0, col, .32 * a * tw], [.1, col, .17 * a * tw], [.24, col, .065 * a * tw], [.5, col, .02 * a], [1, col, 0]]));
        ctx.beginPath(); let reach = 0; const rot = reduced ? 0 : t * .012;
        for (let i = 0; i < 16; i++) {
          const ang = rot + i * 6.283 / 16 + .3 * Math.sin(i * 2.7);
          const len = r * (1.35 + 1.3 * (reduced ? .5 : wav(t * .3, i * .77)) + (i % 4 === 1 ? 1.1 : 0)); reach = Math.max(reach, len);
          const half = .16 + .06 * Math.sin(i * 1.9), bend = .12 * Math.sin(i * 3.3 + (reduced ? 0 : t * .2));
          const x1 = x + Math.cos(ang - half) * r * .9, y1 = y + Math.sin(ang - half) * r * .9, x2 = x + Math.cos(ang + half) * r * .9, y2 = y + Math.sin(ang + half) * r * .9;
          const tx = x + Math.cos(ang + bend) * len, ty = y + Math.sin(ang + bend) * len, mx = x + Math.cos(ang + bend * .5) * r * 1.25, my = y + Math.sin(ang + bend * .5) * r * 1.25;
          ctx.moveTo(x1, y1); ctx.quadraticCurveTo(mx + (x1 - x2) * .25, my + (y1 - y2) * .25, tx, ty); ctx.quadraticCurveTo(mx - (x1 - x2) * .25, my - (y1 - y2) * .25, x2, y2); ctx.closePath();
        }
        const cr = reach * 1.05;
        ctx.fillStyle = grad(cr, [[0, hot, .3 * a], [r / cr, col, .24 * a], [Math.min(.9, r * 1.8 / cr), col, .07 * a], [1, col, 0]]); ctx.fill();
        if (active > .01) {
          const pc = lerpC(col, [255, 90, 42], .45); ctx.lineCap = 'round';
          for (let i = 0; i < 3; i++) {
            const th = i * 2.3 + .6 + (reduced ? 0 : t * .03 + .15 * Math.sin(t * .21 + i)), k = reduced ? .6 : wav(t * .45, i * 1.3);
            const h = r * (.1 + .26 * k), d = .3 + .1 * Math.sin(i * 2.3), R = r + h * 1.35, pa = active * a * (.55 + .45 * k);
            ctx.beginPath(); ctx.moveTo(x + Math.cos(th - d) * r * .96, y + Math.sin(th - d) * r * .96);
            ctx.bezierCurveTo(x + Math.cos(th - d * .9) * R, y + Math.sin(th - d * .9) * R, x + Math.cos(th + d * .5) * R, y + Math.sin(th + d * .5) * R, x + Math.cos(th + d) * r * .96, y + Math.sin(th + d) * r * .96);
            ctx.lineWidth = r * .13 + dpr / zs; ctx.strokeStyle = rgba(pc, .16 * pa); ctx.stroke();
            ctx.lineWidth = r * .045 + .4 * dpr / zs; ctx.strokeStyle = rgba(lerpC(pc, W3, .25), .5 * pa); ctx.stroke();
          }
          ctx.lineCap = 'butt';
        }
        disc(r * 1.45, grad(r * 1.45, [[.62, hot, .55 * a], [.72, col, .32 * a], [1, col, 0]]));
        disc(r, grad(r, [[0, lerpC(col, W3, .94), a], [.5, lerpC(col, W3, .7), a], [.82, lerpC(col, W3, .32), a], [1, lerpC(col, [90, 42, 20], .22), a]]));
        const detail = clamp((r * zs - 6 * dpr) / (8 * dpr), 0, 1);
        if (detail > .01) {
          ctx.save(); ctx.beginPath(); ctx.arc(x, y, r * .97, 0, 6.283); ctx.clip();
          const spin = reduced ? 0 : t * .04;
          for (let i = 0; i < 11; i++) {
            const ang = i * 2.39996 + spin, d = r * (.18 + .62 * ((i * .618) % 1)), gk = reduced ? .5 : wav(t * .6, i * .9);
            ctx.fillStyle = rgba(W3, .09 * detail * a * gk); ctx.beginPath(); ctx.arc(x + Math.cos(ang) * d, y + Math.sin(ang) * d * .95, r * (.13 + .08 * gk), 0, 6.283); ctx.fill();
          }
          if (active > .01) for (let i = 0; i < 3; i++) {
            const ang = i * 2.1 + .4 + spin * 1.3, d = r * (.35 + .2 * i), sx = x + Math.cos(ang) * d, sy = y + Math.sin(ang) * d * .6;
            ctx.fillStyle = rgba([42, 20, 12], .45 * active * detail * a); ctx.beginPath(); ctx.arc(sx, sy, r * (.07 + .03 * i), 0, 6.283); ctx.fill();
            ctx.fillStyle = rgba([42, 20, 12], .18 * active * detail * a); ctx.beginPath(); ctx.arc(sx, sy, r * (.12 + .04 * i), 0, 6.283); ctx.fill();
          }
          ctx.restore();
        }
        if (spikes > .01) {
          const sl = r * (5.5 + 1.5 * tw), wd = Math.max(r * .13, .5 * dpr / zs);
          ctx.beginPath();
          for (let k = 0; k < 4; k++) {
            const ang = k * 1.5708, len = k % 2 === 0 ? sl : sl * .62, dx = Math.cos(ang), dy = Math.sin(ang);
            ctx.moveTo(x - dy * wd, y + dx * wd); ctx.lineTo(x + dx * len, y + dy * len); ctx.lineTo(x + dy * wd, y - dx * wd); ctx.closePath();
          }
          ctx.fillStyle = grad(sl, [[0, hot, .7 * a * spikes * tw], [.16, col, .3 * a * spikes * tw], [.5, col, .07 * a * spikes], [1, col, 0]]); ctx.fill();
        }
      };
      // Sun.
      if (weekSeen) {
        if (cool < .999) { const col = ramp(COOL_STOPS, COOL_COLS, cool, true), r = ramp(SUN_STOPS, SUN_R, uCur) * dpr * (1 - .55 * cool), a = 1 - smooth(clamp((cool - .7) / .3, 0, 1)); star(cx, cy, r, col, a, 0, 1 - cool); }
      } else if (remnant) {
        const prog = limActive ? clamp((now - data.limitedAt) / Math.max(1, data.resetAt - data.limitedAt), 0, 1) : 0;
        if (prog < .45) { const bl = reduced ? .6 : Math.pow(.5 + .5 * Math.sin(t * 12.6), 6); ctx.fillStyle = rgba([207, 233, 255], (.3 + .7 * bl) * life); ctx.beginPath(); ctx.arc(cx, cy, 1.4 * dpr, 0, 6.283); ctx.fill(); }
        if (prog > .3) { const k = clamp((prog - .3) / .7, 0, 1), col = lerpC([207, 233, 255], C.accent, k), r = (1.2 + 3.3 * k) * dpr; ctx.fillStyle = rgba(col, .16 * k); ctx.beginPath(); ctx.arc(cx, cy, r * 2.4, 0, 6.283); ctx.fill(); ctx.fillStyle = rgba(col, .35 + .6 * k); ctx.beginPath(); ctx.arc(cx, cy, r, 0, 6.283); ctx.fill(); }
      } else {
        const grow = banging ? Math.max(.3, expo(clamp((sg - .9) / 1.7, 0, 1))) : reborn ? expo(clamp(sr / 1.6, 0, 1)) : 1;
        const base = ramp(SUN_STOPS, SUN_R, uCur) * dpr, r = (4.5 * dpr + (base - 4.5 * dpr) * grow) * (reduced ? 1 : 1 + (.03 + .07 * clamp((uCur - .6) / .4, 0, 1)) * Math.sin(sunPhase));
        const col = banging ? lerpC([255, 255, 255], ramp(SUN_STOPS, SUN_COLS, uCur, true), clamp((sg - 1.2) / 1.8, 0, 1)) : ramp(SUN_STOPS, SUN_COLS, uCur, true);
        const sa = banging ? clamp((sg - .8) / .6, 0, 1) : life;
        // Past 75% it grows active: spots, and prominences arching off the limb.
        star(cx, cy, r, col, sa, clamp((uCur - .75) / .25, 0, 1), 1);
      }
      for (const p of planets.values()) if (p.y >= cy) drawPlanet(p);
      ctx.restore();

      // Labels (screen space): glide around their planet, facing away from the sun; fade on collision.
      const [ssx, ssy] = S(cx, cy), placed = [];
      ctx.textBaseline = 'alphabetic';
      if (!weekSeen) {
        const left = Math.max(0, data.resetAt - now), pct = Math.round(uCur * 100);
        const txt = remnant ? 'REBORN IN ' + fmt(left) : `${pct}% · ${fmt(left)}`;
        ctx.font = remnant ? `600 ${9 * dpr}px Manrope, system-ui` : `${10 * dpr}px "JetBrains Mono", ui-monospace`;
        if (remnant) ctx.letterSpacing = `${2 * dpr}px`;
        const w = ctx.measureText(txt).width, y = ssy + Math.max(ramp(SUN_STOPS, SUN_R, uCur) * dpr * 1.9, 15 * dpr) + 14 * dpr;
        ctx.fillStyle = rgba(remnant ? C.amber : uCur >= .8 ? C.amber : C.dim, .95 * (1 - ck) * (banging ? clamp((sg - 3) / 1.5, 0, 1) : 1));
        ctx.fillText(txt, ssx - w / 2, y); ctx.letterSpacing = '0px';
        placed.push([ssx - w / 2, y - 12 * dpr, ssx + w / 2, y + 2 * dpr]);
      }
      if (labelsLive > .01) {
        const order = [...planets.values()].sort((a, b) => rank(a) - rank(b));
        ctx.font = `600 ${9 * dpr}px Manrope, system-ui`; ctx.letterSpacing = `${1.8 * dpr}px`;
        for (const p of order) {
          if (isNaN(p.x)) continue;
          const [px, py] = S(p.x, p.y), name = p.name.toUpperCase(), count = String(p.items.length);
          const nw = ctx.measureText(name).width; ctx.letterSpacing = '0px'; ctx.font = `${9 * dpr}px "JetBrains Mono", ui-monospace`; const cw = ctx.measureText(count).width;
          const tw = nw + 5 * dpr + cw, th = 12 * dpr, pr = (p.r || 4) * zs, isF = p === zp;
          let target = Math.atan2(py - ssy, px - ssx);
          const ct = Math.cos(target), room = ct < 0 ? px - pr - 15 * dpr : W - 8 * dpr - (px + pr + 7 * dpr), need = tw * Math.abs(ct);
          if (room < need) { const k = clamp((need - room) / tw, 0, 1), vert = Math.sin(target) > 0 ? 1.5708 : -1.5708; let d = vert - target; while (d > Math.PI) d -= 6.283; while (d < -Math.PI) d += 6.283; target += d * k; }
          if (isF && ck > .05) target = -1.5708;
          if (isNaN(p.ang) || reduced) p.ang = target; else { let d = target - p.ang; while (d > Math.PI) d -= 6.283; while (d < -Math.PI) d += 6.283; p.ang += d * (1 - Math.exp(-dt * 3.5)); }
          // Needs-you planets wear a bracket; give their label room to clear it.
          const nb = p.items.some(i => i.state === 'needs') && !isF ? 5 * dpr : 0;
          // Clear the moon ring too (an ellipse, ry = 0.45 rx), so a label never sits on its own moons.
          const prx = Math.max(pr, (p.ringR || 0) * zs), pry = Math.max(pr, (p.ringR || 0) * .45 * zs);
          const ca = Math.cos(p.ang), sa = Math.sin(p.ang), ax = px + ca * (prx + 7 * dpr + nb), ay = py + sa * (pry + 5.6 * dpr + nb);
          const lx = clamp(ax - tw * (.5 - .5 * clamp(ca * 1.4, -1, 1)), 8 * dpr, Math.max(8 * dpr, W - 8 * dpr - tw)), ly = ay - th * (.5 - .5 * clamp(sa * 1.4, -1, 1));
          let ov = 0; for (const q of placed) { const ow = Math.min(lx + tw, q[2]) - Math.max(lx, q[0]), oh = Math.min(ly + th, q[3]) - Math.max(ly, q[1]); if (ow > 0 && oh > 0) ov += ow * oh; }
          const ta = 1 - .85 * clamp(ov / (tw * th * .3), 0, 1); p.la = reduced ? ta : p.la + (ta - p.la) * (1 - Math.exp(-dt * 5));
          placed.push([lx, ly, lx + tw, ly + th]); p.hit = [lx - 6 * dpr, ly - 10 * dpr, lx + tw + 6 * dpr, ly + th + 10 * dpr];
          const needs = p.items.some(i => i.state === 'needs'), a = p.la * labelsLive * (isF ? 1 : 1 - ck) * (p.free ? .5 : 1);
          ctx.font = `600 ${9 * dpr}px Manrope, system-ui`; ctx.letterSpacing = `${1.8 * dpr}px`;
          if (a < .01) continue;
          const col = isF ? C.ink : needs ? C.amber : C.muted;
          const dx = ax - px, dy = ay - py, len = Math.max(1, Math.hypot(dx, dy)), e = (pr + 2 * dpr) / len;
          ctx.strokeStyle = rgba(col, .45 * a); ctx.lineWidth = .8 * dpr; ctx.beginPath(); ctx.moveTo(px + dx * e, py + dy * e); ctx.lineTo(ax, ay); ctx.stroke();
          ctx.fillStyle = rgba(col, .95 * a); ctx.fillText(name, lx, ly + th * .8);
          if (isF && ck > .02) {
            const x0 = lx - 6 * dpr, y0 = ly - 4 * dpr, x1 = lx + tw + 6 * dpr, y1 = ly + th + 3 * dpr, l = 4 * dpr;
            ctx.strokeStyle = rgba(C.accent, .9 * a * ck); ctx.lineWidth = dpr; ctx.beginPath();
            ctx.moveTo(x0, y0 + l); ctx.lineTo(x0, y0); ctx.lineTo(x0 + l, y0); ctx.moveTo(x1 - l, y0); ctx.lineTo(x1, y0); ctx.lineTo(x1, y0 + l);
            ctx.moveTo(x0, y1 - l); ctx.lineTo(x0, y1); ctx.lineTo(x0 + l, y1); ctx.moveTo(x1 - l, y1); ctx.lineTo(x1, y1); ctx.lineTo(x1, y1 - l); ctx.stroke();
          }
          ctx.letterSpacing = '0px'; ctx.font = `${9 * dpr}px "JetBrains Mono", ui-monospace`; ctx.fillStyle = rgba(needs ? C.amber : C.dim, .85 * a); ctx.fillText(count, lx + nw + 5 * dpr, ly + th * .8);
          ctx.font = `600 ${9 * dpr}px Manrope, system-ui`; ctx.letterSpacing = `${1.8 * dpr}px`;
        }
        ctx.letterSpacing = '0px';
      }
      // Zoomed: the planet's items in a calm fixed column; thin leaders follow the moving moons.
      itemHits = [];
      if (zp && zp.moons && zp.moons.length && ck > .02 && labelsLive > .01) {
        const [px, py] = S(zp.x, zp.y), rowH = 22 * dpr, ms = zp.moons.slice(0, 9), more = zp.moons.length - ms.length;
        const rows = ms.map(m => ({ m, hex: String(m.it.id || '').slice(0, 2), label: String(m.it.label || m.it.id || '') }));
        ctx.font = `500 ${11 * dpr}px Manrope, system-ui`; const lw = Math.max(...rows.map(r => ctx.measureText(r.label).width));
        // The column goes on the side facing away from the sun (like the labels), unless it only fits on the other.
        const colW = 22 * dpr + lw, roomR = W - 12 * dpr - (px + 64 * dpr), roomL = px - 64 * dpr - 12 * dpr;
        const right = px >= ssx ? (roomR >= colW || roomR >= roomL) : !(roomL >= colW || roomL >= roomR);
        const x0 = right ? Math.min(px + 64 * dpr, W - 12 * dpr - colW) : Math.max(12 * dpr, px - 64 * dpr - colW);
        let y0 = py - (rows.length - 1) * rowH / 2; y0 = clamp(y0, bandTop + 12 * dpr, Math.max(bandTop + 12 * dpr, H - 12 * dpr - rows.length * rowH));
        const ca = ck * labelsLive;
        rows.forEach((r, i) => {
          const y = y0 + i * rowH, [mx, my] = S(r.m.x, r.m.y), ex = right ? x0 - 6 * dpr : x0 + colW + 6 * dpr;
          ctx.strokeStyle = rgba(r.m.c, .32 * ca); ctx.lineWidth = .6 * dpr; ctx.beginPath(); ctx.moveTo(ex, y - 3 * dpr); ctx.lineTo(mx, my); ctx.stroke();
          ctx.font = `${9 * dpr}px "JetBrains Mono", ui-monospace`; ctx.fillStyle = rgba(C.dim, .9 * ca); ctx.fillText(r.hex, x0, y);
          ctx.font = `500 ${11 * dpr}px Manrope, system-ui`; ctx.fillStyle = rgba(r.m.it.state === 'needs' ? C.amber : r.m.it.state === 'working' ? C.ink : C.muted, .95 * ca); ctx.fillText(r.label, x0 + 22 * dpr, y);
          itemHits.push({ rect: [x0 - 8 * dpr, y - 15 * dpr, x0 + colW + 8 * dpr, y + 7 * dpr], group: zp.name, id: r.m.it.id });
          itemHits.push({ rect: [mx - 10 * dpr, my - 10 * dpr, mx + 10 * dpr, my + 10 * dpr], group: zp.name, id: r.m.it.id });
        });
        if (more > 0) { ctx.font = `${9 * dpr}px "JetBrains Mono", ui-monospace`; ctx.fillStyle = rgba(C.dim, .8 * ca); ctx.fillText(`+${more} more`, x0 + 22 * dpr, y0 + rows.length * rowH); }
      }
      if (weekSeen) {
        const txt = 'THE UNIVERSE RETURNS IN ' + fmt(Math.max(0, data.weekResetAt - now));
        ctx.font = `600 ${9 * dpr}px Manrope, system-ui`; ctx.letterSpacing = `${2 * dpr}px`; const w = ctx.measureText(txt).width;
        ctx.fillStyle = rgba(C.dim, .4 * smooth(clamp((cool - .8) / .2, 0, 1))); ctx.fillText(txt, cx - w / 2, cy); ctx.letterSpacing = '0px';
      }
      lastSun = [ssx, ssy, Math.max(ramp(SUN_STOPS, SUN_R, uCur) * dpr * zs * 1.6, 16 * dpr)];

      // Overlay (supernova / rebirth / Big Bang) on the top canvas, over the page and its chrome.
      const ovOn = !reduced && ((loud && sb >= 0 && sb < 6) || (sr >= 0 && sr < 1.2) || (sg >= 0 && sg < 6));
      if (ovOn || ovDirty) { octx.setTransform(1, 0, 0, 1, 0, 0); octx.clearRect(0, 0, W, H); ovDirty = ovOn; }
      if (ovOn) {
        const ctx = octx, diag = Math.hypot(W, H);
        if (loud && sb >= 0 && sb < 6) {
          if (sb < .55) { ctx.fillStyle = rgba([255, 255, 255], .62 * Math.pow(1 - sb / .55, 2)); ctx.fillRect(0, 0, W, H); }
          if (sb < 2.2) { ctx.fillStyle = rgba(C.amber, .1 * (1 - sb / 2.2)); ctx.fillRect(0, 0, W, H); }
          if (sb < 1.5) { const k = sb / 1.5; ctx.strokeStyle = rgba(lerpC([255, 255, 255], C.amber, k), .85 * (1 - k)); ctx.lineWidth = 2.5 * dpr; ctx.beginPath(); ctx.arc(ssx, ssy, expo(k) * diag, 0, 6.283); ctx.stroke(); }
          for (const d of debris) { if (sb > d.life) continue; const f = (1 - Math.exp(-1.3 * sb)) / 1.3, x = ssx + d.vx * f, y = ssy + d.vy * f, vk = Math.exp(-1.3 * sb); ctx.strokeStyle = rgba([[255, 255, 255], C.amber, [255, 107, 74]][d.k], .9 * (1 - sb / d.life)); ctx.lineWidth = 1.4 * dpr; ctx.beginPath(); ctx.moveTo(x - d.vx * vk * .035, y - d.vy * vk * .035); ctx.lineTo(x, y); ctx.stroke(); }
        }
        if (sr >= 0 && sr < 1.2) { ctx.fillStyle = rgba(C.bright, .14 * (1 - sr / 1.2)); ctx.fillRect(0, 0, W, H); }
        if (sg >= 0 && sg < 6) {
          if (sg < .6) { ctx.fillStyle = rgba([255, 255, 255], .9 * sg / .6); ctx.beginPath(); ctx.arc(ssx, ssy, (1.5 + 10 * sg / .6) * dpr, 0, 6.283); ctx.fill(); }
          if (sg >= .5 && sg < 2.4) { const k = (sg - .5) / 1.9; ctx.fillStyle = rgba([255, 255, 255], .85 * Math.pow(1 - k, 2.2)); ctx.fillRect(0, 0, W, H); ctx.strokeStyle = rgba(C.bright, .6 * (1 - k)); ctx.lineWidth = 3 * dpr; ctx.beginPath(); ctx.arc(ssx, ssy, expo(k) * diag, 0, 6.283); ctx.stroke(); }
        }
      }
      drawGrain(t);
    }
    let lastCam = null, lastSun = [0, 0, 0];
    // A limit that lapsed by the clock starts young, not at the dead star's 100%.
    const usageNow = () => data.limitedAt != null && !limActive && data.usage >= 1 ? 0 : clamp(data.usage, 0, 1);
    const rank = p => p.name === zoomName ? 0 : p.items.some(i => i.state === 'needs') ? 1 : p.items.some(i => i.state === 'working') ? 2 : 3;
    function fmt(ms) { const s = Math.floor(ms / 1000); if (s < 600) return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; if (s < 3600) return `${Math.floor(s / 60)}m`; if (s < 86400) return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`; return `${Math.floor(s / 86400)}d ${Math.floor((s % 86400) / 3600)}h`; }

    // ── The one ambient clock: 30fps, paused when hidden; a 1Hz tick under reduced motion ──
    let raf = 0, last = 0, clockT = 0, base = performance.now();
    function loop(ts) {
      raf = requestAnimationFrame(loop);
      if (ts - last < 33 - 4) return;
      // A rAF timestamp is the frame's start, which can precede performance.now() at mount: never go negative.
      last = ts; clockT = Math.max(clockT, (ts - base) / 1000); frame(clockT);
    }
    const still = () => requestAnimationFrame(() => frame(0));
    function start() { if (reduced) { still(); raf = setInterval(still, 1000); } else { base = performance.now() - clockT * 1000; raf = requestAnimationFrame(loop); } }
    function stop() { if (reduced) clearInterval(raf); else cancelAnimationFrame(raf); }
    document.addEventListener('visibilitychange', () => document.hidden ? stop() : start());
    addEventListener('resize', () => { resize(); if (reduced) still(); });
    if (reduced && document.fonts) document.fonts.ready.then(still);
    let hitZoom = 1, itemHits = [];
    document.addEventListener('click', e => {
      if (e.target.closest && e.target.closest('a,button,input,textarea,select,label,[role=button],.hn-card')) return;
      const r = canvas.getBoundingClientRect(), x = (e.clientX - r.left) * dpr, y = (e.clientY - r.top) * dpr;
      for (const h of itemHits) if (x > h.rect[0] && x < h.rect[2] && y > h.rect[1] && y < h.rect[3]) return api.onHit && api.onHit({ type: 'item', group: h.group, id: h.id });
      for (const p of planets.values()) if (p.hit && x > p.hit[0] && x < p.hit[2] && y > p.hit[1] && y < p.hit[3]) return api.onHit && api.onHit({ type: 'group', name: p.name });
      let best = null, bd = Infinity;
      const ds = Math.hypot(x - lastSun[0], y - lastSun[1]); if (ds < lastSun[2]) { best = { type: 'sun' }; bd = ds / lastSun[2]; }
      for (const p of planets.values()) { if (p.sx === undefined) continue; const d = Math.hypot(x - p.sx, y - p.sy), rr = Math.max((p.r || 4) * hitZoom * 2.2, 18 * dpr); if (d < rr && d / rr < bd) { bd = d / rr; best = { type: 'group', name: p.name }; } }
      if (best && api.onHit) api.onHit(best);
    });

    resize(); start();
    api.setData = d => { data = Object.assign({}, data, d); };
    api.zoom = name => { zoomName = name; };
    api.destroy = () => { stop(); };
    return api;
  }

  global.HyperCosmos = { mount };
})(typeof window !== 'undefined' ? window : this);
