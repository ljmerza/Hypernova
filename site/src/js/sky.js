/*
 * The living sky behind the site, ported from the Android app (ui/fx/: Ambient, Cosmos, Stars,
 * Grain, Disk, Solar): one ambient clock, stars like real stars drifting on a slow wind, four mint
 * nebulae breathing, film grain, and on the home page the app's solar system: the sun as an
 * accretion disk with the 5-hour window round it, projects as planets on still orbits, sessions as
 * moons, and Codex's smaller star.
 *
 * - One clock at 30 fps that sleeps between frames; each layer draws at its own pace (stars 15 fps,
 *   grain 24, the system 30 while it is on screen), and nothing runs while the tab is hidden.
 * - prefers-reduced-motion: one still frame of each; nothing moves.
 * - The costly parts are made once: the star sprites (one image per star per frame), the disk's
 *   gradients, the grain's tiles; the nebulae are drawn at a quarter size four times a second.
 */
(function () {
  'use strict';

  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
  var coarse = window.matchMedia('(pointer: coarse)').matches;
  var DPR = Math.min(window.devicePixelRatio || 1, coarse ? 1.5 : 2);
  var TAU = Math.PI * 2;
  var SIN_E = 0.342; // the camera looks down on the system's plane from 20° above it

  function clamp(x, a, b) { return x < a ? a : x > b ? b : x; }
  function rgba(c, a) { return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + clamp(a, 0, 1).toFixed(3) + ')'; }
  function lerpC(a, b, k) { return [Math.round(a[0] + (b[0] - a[0]) * k), Math.round(a[1] + (b[1] - a[1]) * k), Math.round(a[2] + (b[2] - a[2]) * k)]; }
  // A seeded random, so the sky is the same sky on every visit (as the app's is at every launch).
  function rng(seed) {
    var s = seed >>> 0;
    return function () { s = (s + 0x6d2b79f5) >>> 0; var t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  }

  // ── The ambient clock ──────────────────────────────────────────────────────────────────────
  // One tick at 30 fps; a layer draws on every tick (`every: 1`) or every other (`every: 2`), and
  // an `urgent` layer (the parallax following a scroll) draws on the very next frame.
  var layers = [];
  var raf = 0, timer = 0, tick = 0, lastTick = 0;
  var PERIOD = 1000 / 30;
  var epoch = performance.now();
  function loop(now) {
    raf = 0;
    var t = (now - epoch) / 1000;
    var due = now - lastTick >= PERIOD - 4;
    if (due) { tick++; lastTick = now; }
    for (var i = 0; i < layers.length; i++) {
      var L = layers[i];
      if (!L.active()) continue;
      if (L.urgent ? L.urgent() : due && tick % L.every === 0) {
        var dt = L.last ? Math.min(0.1, (now - L.last) / 1000) : 0;
        L.last = now;
        L.draw(t, dt);
      }
    }
    schedule(now);
  }
  // Sleeps between ticks, as the app's clock does, instead of waking on every display frame.
  function schedule(now) {
    if (raf || timer || document.hidden || reduce.matches) return;
    var any = false;
    for (var i = 0; i < layers.length; i++) if (!layers[i].urgent && layers[i].active()) any = true;
    if (!any) return;
    var wait = lastTick + PERIOD - now;
    if (wait <= 10) raf = requestAnimationFrame(loop);
    else timer = setTimeout(function () { timer = 0; raf = requestAnimationFrame(loop); }, wait - 6);
  }
  function wake() {
    if (timer) { clearTimeout(timer); timer = 0; }
    if (!raf && !document.hidden && !reduce.matches) raf = requestAnimationFrame(loop);
  }
  function still() {
    // One frame of every layer, for reduced motion and for a resize while still.
    var t = (performance.now() - epoch) / 1000;
    for (var i = 0; i < layers.length; i++) layers[i].draw(t, 0);
  }
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { for (var i = 0; i < layers.length; i++) layers[i].last = 0; wake(); }
  });
  reduce.addEventListener && reduce.addEventListener('change', function () { if (reduce.matches) still(); else wake(); });

  // ── Stars ──────────────────────────────────────────────────────────────────────────────────
  // Six colours by temperature (the app's STAR_COLORS), four tiers (bright to very faint).
  var STAR_COLORS = [[143, 168, 255], [189, 204, 255], [242, 242, 255], [255, 240, 214], [255, 204, 143], [255, 158, 94]];
  var TIER_SHARE = [0.035, 0.11, 0.285, 0.57];
  var COLOR_SHARE = [
    [0.2, 0.22, 0.2, 0.14, 0.14, 0.1],
    [0.08, 0.16, 0.2, 0.22, 0.22, 0.12],
    [0.03, 0.08, 0.14, 0.25, 0.3, 0.2],
    [0.01, 0.04, 0.1, 0.22, 0.33, 0.3],
  ];
  // core, wing, wingAmp, bloom, bloomAmp, spike, spikeAmp, half (CSS px), from fx/SkyArt.kt.
  var TIERS = [
    [0.75, 1.2, 0.45, 3.4, 0.1, 17, 0.55, 18],
    [0.58, 0.9, 0.32, 2.2, 0.07, 8, 0.26, 9],
    [0.46, 0.7, 0.2, 1.3, 0.04, 0, 0, 4],
    [0.4, 0.6, 0.1, 0, 0, 0, 0, 2.4],
  ];
  function pick(r, shares) { var u = r(); for (var i = 0; i < shares.length; i++) { if (u < shares[i]) return i; u -= shares[i]; } return shares.length - 1; }

  /** One sprite per tier and colour: a white-hot core, a halo in its colour, spikes for the bright. */
  function bakeSprites(dpr) {
    var out = [];
    for (var t = 0; t < TIERS.length; t++) {
      var T = TIERS[t];
      var n = 2 * Math.ceil(T[7] * dpr) + 1;
      var h = n / 2;
      out.push([]);
      for (var c = 0; c < STAR_COLORS.length; c++) {
        var cv = document.createElement('canvas');
        cv.width = cv.height = n;
        var g = cv.getContext('2d');
        var img = g.createImageData(n, n);
        var col = STAR_COLORS[c];
        var sc = T[0] * dpr, sw = T[1] * dpr, sb = Math.max(T[3] * dpr, 1), spL = T[5] * dpr, spV = spL * 0.62, spW = Math.max(0.55, 0.24 * dpr);
        for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
          var dx = x + 0.5 - h, dy = y + 0.5 - h, r = Math.sqrt(dx * dx + dy * dy);
          var core = Math.exp(-(r * r) / (2 * sc * sc));
          var wing = T[2] * Math.pow(1 + (r / sw) * (r / sw), -1.6) + T[4] * Math.exp(-(r * r) / (sb * sb));
          var spike = 0;
          if (spL > 0) {
            var hx = Math.exp(-(dy * dy) / (spW * spW)) * Math.max(0, 1 - Math.abs(dx) / spL);
            var vy = Math.exp(-(dx * dx) / (spW * spW)) * Math.max(0, 1 - Math.abs(dy) / spV);
            var ss = clamp((r - 0.6 * sc) / (1.6 * sc), 0, 1);
            spike = T[6] * Math.max(hx * hx, vy * vy) * ss * ss * (3 - 2 * ss);
          }
          var fade = clamp((h - r) / Math.max(1, h * 0.3), 0, 1);
          var l = (wing + spike) * fade;
          var a = clamp(core + l, 0, 1);
          var white = clamp(core / Math.max(1e-4, core + l), 0, 1);
          var i = (y * n + x) * 4;
          img.data[i] = Math.round(col[0] + (255 - col[0]) * white);
          img.data[i + 1] = Math.round(col[1] + (255 - col[1]) * white);
          img.data[i + 2] = Math.round(col[2] + (255 - col[2]) * white);
          img.data[i + 3] = Math.round(a * 255);
        }
        g.putImageData(img, 0, 0);
        out[t].push({ cv: cv, h: h });
      }
    }
    return out;
  }

  function startSky() {
    var cv = document.querySelector('canvas.sky');
    if (!cv) return;
    var neb = document.querySelector('canvas.nebulae');
    var g = cv.getContext('2d');
    var ng = neb && neb.getContext('2d');
    var sprites = bakeSprites(DPR);
    var W = 0, H = 0, stars = [], clouds = [];
    var r = rng(0x5eed1e);
    var NEB = [[127, 255, 160], [150, 255, 200], [180, 255, 140], [105, 255, 185]];
    var scrollY = window.scrollY || 0;
    var drawnScroll = -1;

    function build() {
      var area = W * H / (DPR * DPR);
      var count = clamp(Math.round(area / 4400), 110, 380);
      stars = [];
      for (var i = 0; i < count; i++) {
        var tier = pick(r, TIER_SHARE);
        var col = pick(r, COLOR_SHARE[tier]);
        var ang = r() * TAU;
        stars.push({
          x: r() * W, y: r() * H, t: tier, c: col,
          // The fainter the star, the farther it is: brightness and parallax both fall with the tier.
          a: tier === 0 ? 0.75 + 0.25 * r() : tier === 1 ? 0.55 + 0.3 * r() : tier === 2 ? 0.4 + 0.35 * r() : 0.28 + 0.35 * r(),
          sp: (tier === 0 ? 0.1 : 0.2 + 0.3 * r()) * DPR, ang: ang,
          par: [0.07, 0.05, 0.03, 0.015][tier],
        });
      }
      clouds = NEB.map(function (c) {
        return { x: r(), y: r(), rr: (0.3 + 0.25 * r()) * 1.5, a: 0.07 + 0.07 * r(), c: c, ph: r() * TAU, sp: 0.08 + 0.1 * r() };
      });
    }
    function resize() {
      var w = Math.max(1, Math.round(cv.clientWidth * DPR));
      var h = Math.max(1, Math.round(cv.clientHeight * DPR));
      if (w === W && h === H) return;
      var ow = W, oh = H;
      W = cv.width = w; H = cv.height = h;
      if (neb) { neb.width = Math.max(1, Math.round(cv.clientWidth / 4)); neb.height = Math.max(1, Math.round(cv.clientHeight / 4)); }
      if (!stars.length) build();
      else { for (var i = 0; i < stars.length; i++) { stars[i].x *= W / ow; stars[i].y *= H / oh; } }
      nebLast = -1;
      drawStars(lastT, 0);
    }
    var lastT = 0, nebLast = -1;
    function drawNebulae(t) {
      if (!ng) return;
      var w = neb.width, h = neb.height, m = Math.min(w, h);
      ng.clearRect(0, 0, w, h);
      for (var i = 0; i < clouds.length; i++) {
        var n = clouds[i];
        var breathe = 0.6 + 0.4 * Math.sin(t * n.sp + n.ph);
        var a = n.a * breathe;
        // They drift on the wind at half the stars' force, and shift a little with the scroll.
        var x = (n.x * w + Math.cos(t * 0.04 + i) * 6 + w) % w;
        var y = ((n.y * h - scrollY * 0.012 / 4 * DPR) % h + h) % h;
        var rad = m * n.rr;
        var gr = ng.createRadialGradient(x, y, 0, x, y, rad);
        gr.addColorStop(0, rgba(n.c, a));
        gr.addColorStop(0.3, rgba(n.c, a * 0.55));
        gr.addColorStop(0.6, rgba(n.c, a * 0.2));
        gr.addColorStop(1, rgba(n.c, 0));
        ng.fillStyle = gr;
        ng.fillRect(0, 0, w, h);
      }
    }
    function drawStars(t, dt) {
      lastT = t;
      g.clearRect(0, 0, W, H);
      // Wind: a slow turning drift, as in 0stems' cosmos.
      var wind = t * 0.04, wx = Math.cos(wind) * 0.4 * DPR, wy = Math.sin(wind) * 0.4 * DPR;
      var sy = scrollY * DPR;
      for (var i = 0; i < stars.length; i++) {
        var s = stars[i];
        if (dt) {
          s.x += (Math.cos(s.ang) * s.sp + wx) * dt;
          s.y += (Math.sin(s.ang) * s.sp + wy) * dt;
          if (s.x < -20) s.x += W + 40; else if (s.x > W + 20) s.x -= W + 40;
          if (s.y < -20) s.y += H + 40; else if (s.y > H + 20) s.y -= H + 40;
        }
        var y = s.y - sy * s.par;
        y = ((y % (H + 40)) + H + 40) % (H + 40) - 20;
        var sp = sprites[s.t][s.c];
        g.globalAlpha = s.a;
        g.drawImage(sp.cv, s.x - sp.h, y - sp.h);
      }
      g.globalAlpha = 1;
      drawnScroll = scrollY;
      if (t - nebLast > 0.25 || nebLast < 0) { nebLast = t; drawNebulae(t); }
    }
    resize();
    window.addEventListener('resize', function () { resize(); if (reduce.matches) still(); });
    window.addEventListener('scroll', function () {
      scrollY = window.scrollY || 0;
      if (reduce.matches) return;
      wake();
    }, { passive: true });
    layers.push({
      every: 2, last: 0, // the stars drift a pixel every few seconds: 15 frames a second is plenty
      active: function () { return true; },
      draw: function (t, dt) { if (reduce.matches) { drawStars(t, 0); return; } drawStars(t, dt); },
    });
    // A scroll redraws at once (the parallax must follow the finger), not on the next 20 fps tick.
    layers.push({
      urgent: function () { return drawnScroll !== scrollY; }, last: 0,
      active: function () { return drawnScroll !== scrollY; },
      draw: function (t) { drawStars(t, 0); },
    });
  }

  // ── Grain: 0stems' film grain, overlay at 32%, a new pattern 24 times a second ──────────────
  function startGrain() {
    var cv = document.querySelector('canvas.grain');
    if (!cv) return;
    var g = cv.getContext('2d');
    var tiles = [];
    for (var k = 0; k < 4; k++) {
      var t = document.createElement('canvas');
      t.width = t.height = 192;
      var tg = t.getContext('2d');
      var img = tg.createImageData(192, 192);
      for (var i = 0; i < img.data.length; i += 4) {
        var v = (Math.random() * 255) | 0;
        img.data[i] = img.data[i + 1] = img.data[i + 2] = v;
        img.data[i + 3] = (Math.random() * 90) | 0;
      }
      tg.putImageData(img, 0, 0);
      tiles.push(g.createPattern(t, 'repeat'));
    }
    function size() {
      // Cells of about 1.1 CSS px, scaled up softly by the browser as in 0stems.
      cv.width = Math.max(1, Math.round(cv.clientWidth / 1.1));
      cv.height = Math.max(1, Math.round(cv.clientHeight / 1.1));
    }
    var frame = -1;
    function draw(t) {
      var f = Math.floor(t * 24);
      if (f === frame) return;
      frame = f;
      g.setTransform(1, 0, 0, 1, 0, 0);
      g.clearRect(0, 0, cv.width, cv.height);
      g.setTransform(1, 0, 0, 1, -((Math.random() * 192) | 0), -((Math.random() * 192) | 0));
      g.fillStyle = tiles[f & 3];
      g.fillRect(0, 0, cv.width + 192, cv.height + 192);
    }
    size();
    window.addEventListener('resize', function () { size(); frame = -1; draw((performance.now() - epoch) / 1000); });
    layers.push({ every: coarse ? 2 : 1, last: 0, active: function () { return true; }, draw: draw });
  }

  // ── The solar system (the home page's stage) ──────────────────────────────────────────────
  // The disk's palette (fx/Disk.kt, DiskPalette.Sun and .Codex).
  var SUN = {
    heat: [[238, 244, 255], [255, 246, 224], [255, 216, 138], [255, 154, 72], [224, 84, 42], [154, 42, 22]],
    cool: [122, 36, 22], coolRim: [168, 64, 42], donor: [156, 74, 56], donorShade: [58, 24, 16], spot: [255, 200, 144],
    bloom: [[255, 232, 192], [255, 200, 128], [240, 144, 80]], glow: [[255, 251, 242], [255, 224, 160], [255, 176, 96]],
  };
  var CODEX = {
    heat: [[238, 244, 255], [245, 242, 235], [220, 216, 207], [181, 176, 166], [122, 116, 104], [69, 64, 58]],
    cool: [54, 50, 44], coolRim: [69, 64, 58], donor: [122, 116, 104], donorShade: [34, 31, 27], spot: [245, 242, 235],
    bloom: [[245, 242, 235], [220, 216, 207], [181, 176, 166]], glow: [[255, 255, 255], [238, 244, 255], [181, 176, 166]],
  };
  var HEAT_STOPS = [0, 0.22, 0.48, 0.72, 0.9, 1];
  var R_IN = 2.6 / 13, DONOR_A = 19.5 / 13, DONOR_R = 1.7 / 13, T_IN = 9, INFLOW = 0.05, KNOTS = 14;
  var WD = [238, 242, 255];
  var INK = [245, 242, 235], ACCENT = [127, 255, 160], AMBER = [232, 179, 65], MUTED = [181, 176, 166], DIM = [122, 116, 104];

  function heat(pal, k) {
    k = clamp(k, 0, 1);
    for (var i = 1; i < HEAT_STOPS.length; i++) if (k <= HEAT_STOPS[i]) return lerpC(pal.heat[i - 1], pal.heat[i], (k - HEAT_STOPS[i - 1]) / (HEAT_STOPS[i] - HEAT_STOPS[i - 1]));
    return pal.heat[pal.heat.length - 1];
  }
  function density(k) { return 0.97 - 0.45 * Math.pow(clamp(k, 0, 1), 0.8); }

  /** An accretion disk drawn in unit space (1 = its rim), as fx/Disk.kt draws it. */
  function Disk(pal, seed) {
    var r = rng(seed);
    this.pal = pal;
    this.kS = []; this.kA = []; this.kL = []; this.kB = [];
    for (var i = 0; i < KNOTS; i++) { this.kS.push(r()); this.kA.push(r() * TAU); this.kL.push(0.35 + 0.5 * r()); this.kB.push(0.45 + 0.55 * r()); }
    this.grads = null;
  }
  Disk.prototype.knotR = function (s) { return R_IN + (1 - R_IN) * Math.pow(1 - s, 1.1); };
  Disk.prototype.advance = function (dt, flow) {
    if (flow <= 0 || !dt) return;
    for (var i = 0; i < KNOTS; i++) {
      var rr = this.knotR(this.kS[i]);
      this.kA[i] += dt * TAU / (T_IN * Math.pow(rr / R_IN, 1.5)); // Kepler: ω ∝ r^-1.5
      this.kS[i] += dt * flow * INFLOW;
      if (this.kS[i] >= 1) this.kS[i] -= 1;
    }
  };
  Disk.prototype.build = function (g, hotOut) {
    var p = this.pal, w = 1 - R_IN;
    function rad(k) { return R_IN + w * k; }
    var gr = {};
    gr.bloom = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.bloom.addColorStop(0, rgba(p.bloom[0], 0.34)); gr.bloom.addColorStop(0.18, rgba(p.bloom[1], 0.14)); gr.bloom.addColorStop(0.45, rgba(p.bloom[2], 0.04)); gr.bloom.addColorStop(1, rgba(p.bloom[2], 0));
    gr.cool = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.cool.addColorStop(0, rgba(p.cool, 0)); gr.cool.addColorStop(R_IN - 0.002, rgba(p.cool, 0)); gr.cool.addColorStop(R_IN, rgba(p.cool, 0.6));
    gr.cool.addColorStop(0.55, rgba(p.cool, 0.42)); gr.cool.addColorStop(0.9, rgba(p.coolRim, 0.3)); gr.cool.addColorStop(1, rgba(p.coolRim, 0.1));
    // The hot disk from the inner edge out to the cooling front (the budget left), soft at the front.
    var hot = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    var rh = rad(hotOut), e = 0.07;
    hot.addColorStop(0, rgba(heat(p, 0), 0));
    hot.addColorStop(R_IN - 0.002, rgba(heat(p, 0), 0));
    hot.addColorStop(R_IN, rgba(heat(p, 0), density(0)));
    for (var i = 1; i < HEAT_STOPS.length; i++) {
      var k = HEAT_STOPS[i], rr = rad(k);
      if (rr < rh - 0.004) hot.addColorStop(rr, rgba(heat(p, k), density(k)));
    }
    hot.addColorStop(rh, rgba(heat(p, hotOut), density(hotOut)));
    var eo = Math.min(1, rh + e);
    if (eo > rh + 0.002) hot.addColorStop(eo, rgba(heat(p, hotOut), 0));
    if (eo < 0.999) hot.addColorStop(1, rgba(heat(p, hotOut), 0));
    gr.hot = hot;
    gr.glow = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.glow.addColorStop(0, rgba(p.glow[0], 0.9)); gr.glow.addColorStop(0.3, rgba(p.glow[1], 0.35)); gr.glow.addColorStop(0.6, rgba(p.glow[2], 0.1)); gr.glow.addColorStop(1, rgba(p.glow[2], 0));
    gr.spot = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.spot.addColorStop(0, rgba(p.spot, 1)); gr.spot.addColorStop(0.35, rgba(p.spot, 0.45)); gr.spot.addColorStop(1, rgba(p.spot, 0));
    gr.donorGlow = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.donorGlow.addColorStop(0, rgba(p.donor, 0.35)); gr.donorGlow.addColorStop(0.5, rgba(p.donor, 0.1)); gr.donorGlow.addColorStop(1, rgba(p.donor, 0));
    gr.donorBody = g.createRadialGradient(-0.35, -0.2, 0, -0.35, -0.2, 1.3);
    gr.donorBody.addColorStop(0, rgba(lerpC(p.donor, [255, 255, 255], 0.3), 1)); gr.donorBody.addColorStop(0.5, rgba(p.donor, 1)); gr.donorBody.addColorStop(1, rgba(lerpC(p.donor, p.donorShade, 0.5), 1));
    gr.wdHalo = g.createRadialGradient(0, 0, 0, 0, 0, 1);
    gr.wdHalo.addColorStop(0, rgba(WD, 0.3)); gr.wdHalo.addColorStop(0.35, rgba(WD, 0.08)); gr.wdHalo.addColorStop(1, rgba(WD, 0));
    this.grads = gr;
    this.hotOut = hotOut;
  };
  /** Draws at (cx, cy), [unit] px per rim radius. donor: the donor's angle; flow: 0..1. */
  Disk.prototype.draw = function (g, cx, cy, unit, hotOut, donor, flow, px) {
    if (!this.grads || this.hotOut !== hotOut) this.build(g, hotOut);
    var gr = this.grads, p = this.pal;
    var hair = px / unit; // one CSS px in unit space
    g.save();
    g.translate(cx, cy);
    g.scale(unit, unit);
    // Its light scattered round it.
    g.save(); g.scale(2.6, 2.0); g.globalAlpha = 0.35 + 0.65 * (1 - Math.pow(1 - hotOut, 2)); g.fillStyle = gr.bloom; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill(); g.restore();
    // The disk in the system's plane.
    g.save(); g.scale(1, SIN_E);
    g.globalAlpha = 1; g.fillStyle = gr.cool; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill();
    g.fillStyle = gr.hot; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill();
    g.restore();
    // Knots of hotter gas spiralling in, while something works.
    if (flow > 0.01) {
      var w = 1 - R_IN, kw = Math.max(0.03, 0.9 * hair);
      g.lineCap = 'round';
      for (var i = 0; i < KNOTS; i++) {
        var s = this.kS[i], rr = this.knotR(s), k = (rr - R_IN) / w;
        var life = clamp(s / 0.12, 0, 1) * (1 - clamp((s - 0.88) / 0.12, 0, 1));
        var inside = clamp((hotOut - k) / 0.1, 0, 1) * clamp(k / 0.1, 0, 1);
        var shown = life * inside * flow;
        if (shown <= 0.01) continue;
        var col = lerpC(heat(p, k), [255, 255, 255], 0.35);
        g.strokeStyle = rgba(col, 1);
        g.lineWidth = kw * (1.3 - 0.5 * k);
        var mid = this.kA[i], len = this.kL[i];
        var px0 = 0, py0 = 0;
        for (var j = 0; j <= 5; j++) {
          var u = j / 5, phi = mid + len * (u - 0.5);
          var x = rr * Math.cos(phi), y = rr * Math.sin(phi) * SIN_E;
          if (j > 0) {
            var taper = Math.sin(Math.PI * (u - 0.1));
            g.globalAlpha = clamp(shown * this.kB[i] * density(k) * taper, 0, 1);
            g.beginPath(); g.moveTo(px0, py0); g.lineTo(x, y); g.stroke();
          }
          px0 = x; py0 = y;
        }
      }
      g.globalAlpha = 1;
    }
    // The inner edge's light: the light is the rate.
    var gl = 0.45 + 0.55 * flow;
    g.save(); g.scale(R_IN * 2.4, R_IN * 1.6); g.globalAlpha = clamp(0.55 * gl, 0, 1); g.fillStyle = gr.glow; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill(); g.restore();
    // The white dwarf: a point, its halo, two faint spikes.
    g.save(); g.scale(12 * hair, 12 * hair); g.globalAlpha = 1; g.fillStyle = gr.wdHalo; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill(); g.restore();
    g.strokeStyle = rgba(WD, 0.12); g.lineWidth = 0.8 * hair;
    g.beginPath(); g.moveTo(-9 * hair, 0); g.lineTo(9 * hair, 0); g.moveTo(0, -5.6 * hair); g.lineTo(0, 5.6 * hair); g.stroke();
    g.fillStyle = rgba(WD, 0.22); g.beginPath(); g.arc(0, 0, 3.2 * hair, 0, TAU); g.fill();
    g.fillStyle = rgba(WD, 1); g.beginPath(); g.arc(0, 0, 1.7 * hair, 0, TAU); g.fill();
    // The donor, its stream to the rim (faint in outburst) and the bright spot where it lands.
    var dx = DONOR_A * Math.cos(donor), dy = DONOR_A * Math.sin(donor) * SIN_E;
    var p0 = DONOR_A - DONOR_R * 1.4, x0 = p0 * Math.cos(donor), y0 = p0 * Math.sin(donor);
    var a2 = donor + 0.9, x2 = Math.cos(a2), y2 = Math.sin(a2);
    var x1 = (x0 + x2) * 0.35, y1 = (y0 + y2) * 0.35;
    var sc = lerpC(p.donor, p.spot, 0.55);
    g.strokeStyle = rgba(sc, 0.18 * 0.35); g.lineWidth = Math.max(0.12, 2.2 * hair);
    g.beginPath(); g.moveTo(x0, y0 * SIN_E); g.quadraticCurveTo(x1, y1 * SIN_E, x2, y2 * SIN_E); g.stroke();
    g.strokeStyle = rgba(sc, 0.7 * 0.35); g.lineWidth = Math.max(0.04, 0.7 * hair);
    g.beginPath(); g.moveTo(x0, y0 * SIN_E); g.quadraticCurveTo(x1, y1 * SIN_E, x2, y2 * SIN_E); g.stroke();
    g.save(); g.translate(x2, y2 * SIN_E); g.scale(0.26, 0.2); g.globalAlpha = 0.3; g.fillStyle = gr.spot; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill(); g.restore();
    g.save();
    g.translate(dx, dy); g.rotate(Math.atan2(-dy, -dx)); g.scale(DONOR_R, DONOR_R);
    g.save(); g.scale(2.6, 2.6); g.fillStyle = gr.donorGlow; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill(); g.restore();
    g.fillStyle = rgba(lerpC(p.donor, p.donorShade, 0.35), 1);
    g.beginPath(); g.moveTo(0.64, -0.77); g.quadraticCurveTo(1.25, -0.2, 1.45, 0); g.quadraticCurveTo(1.25, 0.2, 0.64, 0.77); g.closePath(); g.fill();
    g.fillStyle = gr.donorBody; g.beginPath(); g.arc(0, 0, 1, 0, TAU); g.fill();
    g.restore();
    g.restore();
  };

  /** A small planet lit from [lx, ly] (a unit direction toward the light), turning at [spin]. */
  function sphere(g, x, y, r, col, alpha, lx, ly, spin, meridians) {
    g.fillStyle = rgba(col, 0.11 * alpha);
    g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill();
    var gx = x + lx * r * 0.64, gy = y + ly * r * 0.64;
    var gr = g.createRadialGradient(gx, gy, 0, gx, gy, r * 1.7);
    gr.addColorStop(0, rgba(col, 0.78 * alpha)); gr.addColorStop(0.5, rgba(col, 0.24 * alpha)); gr.addColorStop(1, rgba(col, 0.05 * alpha));
    g.fillStyle = gr;
    g.beginPath(); g.arc(x, y, r, 0, TAU); g.fill();
    if (meridians) {
      g.save();
      g.beginPath(); g.arc(x, y, r, 0, TAU); g.clip();
      g.lineWidth = Math.max(0.7, r * 0.12);
      for (var k = 0; k < 4; k++) {
        var lon = spin + k * Math.PI / 2, depth = Math.cos(lon);
        if (depth <= 0.05) continue;
        var half = r * Math.sin(lon), w = Math.max(0.25, Math.abs(half));
        var a = depth * clamp(0.55 + 0.495 * (half / r) * lx, 0.1, 0.9) * alpha;
        g.strokeStyle = rgba(col, a);
        g.beginPath();
        g.ellipse(x, y, w, r, 0, half >= 0 ? -Math.PI / 2 : Math.PI / 2, half >= 0 ? Math.PI / 2 : Math.PI * 1.5);
        g.stroke();
      }
      g.restore();
    }
    g.lineWidth = Math.max(0.7, r * 0.12);
    g.strokeStyle = rgba(col, 0.2 * alpha);
    g.beginPath(); g.arc(x, y, r, 0, TAU); g.stroke();
    // The lit limb toward the sun.
    var ang = Math.atan2(ly, lx);
    g.strokeStyle = rgba(col, 0.45 * alpha);
    g.beginPath(); g.arc(x, y, r, ang - 1.4, ang + 1.4); g.stroke();
  }

  function startSystem() {
    var stage = document.querySelector('[data-orrery]');
    if (!stage) return;
    var cv = stage.querySelector('canvas');
    var g = cv.getContext('2d');
    var labels = stage.querySelector('.sky-labels');
    // The scene: what a busy afternoon looks like on the app's Home.
    var USED = 0.43, GONE = 0.56; // 43% of the window used, 2h 49m of its 5h gone
    var PLANETS = [
      { name: 'hypernova', orbit: 0, ang: 196, state: 'working', words: '3 working', moons: 3, size: 1.15 },
      { name: '0stems', orbit: 1, ang: -42, state: 'needs', words: '2 need you', moons: 2, size: 1.0 },
      { name: 'hyperscribe', orbit: 2, ang: 150, state: 'idle', words: '12m ago', moons: 1, size: 0.9 },
      { name: 'landing', orbit: 3, ang: 60, state: 'working', words: 'working', moons: 1, size: 0.85 },
      { name: 'notes', orbit: 3, ang: -122, state: 'idle', words: '1h ago', moons: 0, size: 0.7 },
    ];
    var sun = new Disk(SUN, 0x5eed);
    var codex = new Disk(CODEX, 0xc0de);
    var W = 0, H = 0, L = null, visible = true;
    var hotOut = (function () {
      // The hot part reaches out to where the budget left puts it, by area (DiskModel.hotRadius).
      var left = 1 - USED, rOut = Math.sqrt(R_IN * R_IN + left * (1 - R_IN * R_IN));
      return (rOut - R_IN) / (1 - R_IN);
    })();
    var labelEls = [];

    function layout() {
      var cw = stage.clientWidth, ch = stage.clientHeight;
      W = Math.round(cw * DPR); H = Math.round(ch * DPR);
      cv.width = W; cv.height = H;
      var wide = cw >= 560;
      var withPhone = !stage.hasAttribute('data-centered') && window.matchMedia('(min-width: 960px)').matches;
      var cx = cw * (withPhone ? 0.38 : 0.5), cy = ch * (withPhone ? 0.36 : 0.42);
      // The outer orbit fills the room there is (beside the phone, its left side is the limit),
      // and everything else scales from it: the disk's rim is the orbit's 1/6.35.
      var outer = Math.min(withPhone ? cx - 24 : Math.min(cx, cw - cx) - 16, ch * 0.85);
      var R = clamp(outer / 6.35, 18, 46);
      var u = R / 34;
      var orbits = [2.7, 3.85, 5.05, 6.35].map(function (k) { return outer * k / 6.35; });
      L = { cw: cw, ch: ch, u: u, R: R, cx: cx, cy: cy, orbits: orbits, wide: wide, ring: 0 };
      L.ring = Math.max(20, R * 1.55 + 6);
      L.planets = PLANETS.map(function (p) {
        var a = orbits[p.orbit], th = p.ang * Math.PI / 180;
        var x = cx + a * Math.cos(th), y = cy + a * Math.sin(th) * SIN_E, z = Math.sin(th);
        var r = (5.2 + 2.2 * p.size) * Math.max(0.75, u) * (1 + 0.12 * z);
        var dx = cx - x, dy = cy - y, d = Math.sqrt(dx * dx + dy * dy) || 1;
        return { p: p, x: x, y: y, z: z, r: r, lx: dx / d, ly: dy / d, spin: Math.random() * TAU, moonPh: Math.random() * TAU };
      });
      // Codex's star: smaller and calmer, in a row of its own under the system, its words beside it.
      L.codex = { x: Math.max(R, cx - outer * 0.82), y: Math.min(ch - R * 0.9, cy + outer * SIN_E + R * 2.4), R: R * 0.6 };
      placeLabels();
      if (reduce.matches) draw((performance.now() - epoch) / 1000, 0);
    }

    function label(cls, b, span, x, y) {
      var el = document.createElement('div');
      el.className = 'sky-label ' + cls;
      el.setAttribute('aria-hidden', 'true');
      if (b) { var bb = document.createElement('b'); bb.textContent = b; el.appendChild(bb); }
      if (span) { var ss = document.createElement('span'); ss.textContent = span; el.appendChild(ss); }
      el.style.left = Math.round(x) + 'px';
      el.style.top = Math.round(y) + 'px';
      labels.appendChild(el);
      labelEls.push(el);
      return el;
    }
    function placeLabels() {
      if (!labels) return;
      labelEls.forEach(function (e) { e.remove(); });
      labelEls = [];
      var small = L.cw < 520;
      L.planets.forEach(function (q) {
        if (small && q.p.name === 'notes') return;
        var below = q.z > 0.35;
        var off = q.r + (q.p.state === 'needs' ? 10 : 7);
        var cls = (below ? 'below ' : '') + (q.p.state === 'working' ? 'working' : q.p.state === 'needs' ? 'needs' : 'idle');
        label(cls, q.p.name.toUpperCase(), q.p.words, q.x, below ? q.y + off : q.y - off);
      });
      label('below readout', null, Math.round(USED * 100) + '%', L.cx, L.cy + L.ring + 8);
      label('right working', 'CODEX ' + 38 + '%', 'working', L.codex.x + L.codex.R * 1.9 + 10, L.codex.y);
      requestAnimationFrame(function () { labelEls.forEach(function (e, i) { setTimeout(function () { e.classList.add('on'); }, reduce.matches ? 0 : 120 + i * 50); }); });
    }

    function drawOrbits() {
      g.lineWidth = 1;
      for (var i = 0; i < L.orbits.length; i++) {
        var a = L.orbits[i];
        g.strokeStyle = rgba(INK, 0.13);
        g.beginPath(); g.ellipse(L.cx, L.cy, a, a * SIN_E, 0, 0, TAU); g.stroke();
      }
    }
    function drawRing() {
      var r = L.ring, cx = L.cx, cy = L.cy, top = -Math.PI / 2;
      g.lineCap = 'round';
      g.lineWidth = 1.6;
      g.strokeStyle = rgba(INK, 0.09);
      g.beginPath(); g.arc(cx, cy, r, 0, TAU); g.stroke();
      g.lineWidth = 2.2;
      g.strokeStyle = rgba(ACCENT, 0.3);
      g.beginPath(); g.arc(cx, cy, r, top + TAU * USED, top + TAU * 0.6); g.stroke();
      g.strokeStyle = rgba(ACCENT, 0.9);
      g.beginPath(); g.arc(cx, cy, r, top, top + TAU * USED); g.stroke();
      // The window's clock, outside: its five hours a faint circle, the time gone a brighter arc.
      g.lineWidth = 1;
      g.strokeStyle = rgba(MUTED, 0.12);
      g.beginPath(); g.arc(cx, cy, r + 4, 0, TAU); g.stroke();
      g.strokeStyle = rgba(MUTED, 0.45);
      g.beginPath(); g.arc(cx, cy, r + 4, top, top + TAU * GONE); g.stroke();
    }
    function drawMoons(q, t, back) {
      var n = q.p.moons;
      if (!n) return;
      var col = q.p.state === 'working' ? ACCENT : q.p.state === 'needs' ? AMBER : DIM;
      var moving = q.p.state === 'working';
      for (var i = 0; i < n; i++) {
        var shell = q.r + (6 + i * 4.5) * Math.max(0.8, L.u);
        var period = 6 * Math.pow(shell / (q.r + 6), 1.5);
        var th = q.moonPh + i * 2.1 + (moving ? t * TAU / period : 0);
        var z = Math.sin(th);
        if ((z < 0) !== back) continue;
        var x = q.x + shell * Math.cos(th), y = q.y + shell * Math.sin(th) * SIN_E;
        var mr = 1.5 * Math.max(0.8, L.u);
        g.fillStyle = rgba(col, back ? 0.55 : 0.95);
        g.beginPath(); g.arc(x, y, mr, 0, TAU); g.fill();
        if (moving) { g.strokeStyle = rgba(col, 0.45 * (back ? 0.6 : 1)); g.lineWidth = 0.8; g.beginPath(); g.arc(x, y, mr * 1.9, 0, TAU); g.stroke(); }
      }
    }
    function drawPlanet(q, t) {
      var st = q.p.state;
      var col = st === 'working' ? ACCENT : st === 'needs' ? AMBER : MUTED;
      var alpha = st === 'idle' ? 0.75 : 1;
      if (st === 'working') q.spin += (t - (q.lt || t)) * TAU / 6;
      q.lt = t;
      // A soft glow round a live planet.
      if (st !== 'idle') {
        var gg = g.createRadialGradient(q.x, q.y, q.r * 0.5, q.x, q.y, q.r * 3);
        gg.addColorStop(0, rgba(col, 0.16)); gg.addColorStop(1, rgba(col, 0));
        g.fillStyle = gg; g.beginPath(); g.arc(q.x, q.y, q.r * 3, 0, TAU); g.fill();
      }
      drawMoons(q, t, true);
      sphere(g, q.x, q.y, q.r, col, alpha, q.lx, q.ly, q.spin + Math.PI / 4, true);
      drawMoons(q, t, false);
      if (st === 'needs') {
        // The focus bracket, amber, breathing on the 1.4 s pulse.
        var b = q.r + 6 * Math.max(0.85, L.u), arm = 4 * Math.max(0.85, L.u);
        var pa = reduce.matches ? 1 : 0.7 + 0.3 * (0.5 + 0.5 * Math.cos(t * TAU / 1.4));
        g.strokeStyle = rgba(AMBER, pa);
        g.lineWidth = 1.3; g.lineCap = 'square';
        g.beginPath();
        [[-1, -1], [1, -1], [1, 1], [-1, 1]].forEach(function (s) {
          var x = q.x + s[0] * b, y = q.y + s[1] * b;
          g.moveTo(x, y - s[1] * arm); g.lineTo(x, y); g.lineTo(x - s[0] * arm, y);
        });
        g.stroke();
      }
    }
    var lastT = 0;
    function draw(t, dt) {
      if (!L) return;
      lastT = t;
      g.setTransform(DPR, 0, 0, DPR, 0, 0);
      g.clearRect(0, 0, L.cw, L.ch);
      // A warm haze round the system: the disk's light in the space round it.
      var hz = g.createRadialGradient(L.cx, L.cy, 0, L.cx, L.cy, L.orbits[3] * 1.05);
      hz.addColorStop(0, 'rgba(255,176,112,0.07)'); hz.addColorStop(0.5, 'rgba(255,176,112,0.025)'); hz.addColorStop(1, 'rgba(255,176,112,0)');
      g.fillStyle = hz; g.fillRect(0, 0, L.cw, L.ch);
      drawOrbits();
      var behind = L.planets.filter(function (q) { return q.z < 0; });
      var front = L.planets.filter(function (q) { return q.z >= 0; });
      behind.forEach(function (q) { drawPlanet(q, t); });
      sun.advance(dt, 1);
      drawRing();
      sun.draw(g, L.cx, L.cy, L.R, hotOut, -Math.PI / 2 + TAU * GONE, 1, 1);
      front.forEach(function (q) { drawPlanet(q, t); });
      // Codex's star with one moon going round while its session works.
      codex.advance(dt, 1);
      codex.draw(g, L.codex.x, L.codex.y, L.codex.R, 0.85, -Math.PI / 2 + TAU * 0.3, 1, 1);
      var mt = L.codex, sh = mt.R * 2.1, th = t * TAU / 8 + 1.2;
      g.fillStyle = rgba(INK, Math.sin(th) < 0 ? 0.5 : 0.9);
      g.beginPath(); g.arc(mt.x + sh * Math.cos(th), mt.y + sh * Math.sin(th) * SIN_E, 1.5, 0, TAU); g.fill();
    }

    layout();
    var ro = new ResizeObserver(function () { layout(); draw(lastT, 0); });
    ro.observe(stage);
    if ('IntersectionObserver' in window) {
      new IntersectionObserver(function (es) { visible = es[0].isIntersecting; if (visible) wake(); }, { rootMargin: '80px' }).observe(stage);
    }
    layers.push({ every: 1, last: 0, active: function () { return visible; }, draw: draw });
    stage.classList.add('drawn');
  }

  function start() {
    try { startSky(); } catch (e) { /* the gradient backdrop stays */ }
    try { startGrain(); } catch (e) { /* no grain */ }
    try { startSystem(); } catch (e) { /* the still picture stays */ }
    if (reduce.matches) still(); else wake();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
