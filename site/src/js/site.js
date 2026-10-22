// The page's small helpers. Everything here is an extra: without it the page still reads and every
// link still works.
//  - Copy buttons on code and checksums.
//  - The install page's release details from /download/latest.json (the release script writes it).
//  - Which download suits this device.
//  - Sections easing in once as they arrive, and the guide's contents following the reading.
(function () {
  'use strict';
  var d = document, html = d.documentElement;
  var reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  // ── The small-screen menu closes once a link in it is followed (an anchor on this page too) ──
  var menu = d.querySelector('details.menu');
  if (menu) {
    menu.addEventListener('click', function (e) { if (e.target.closest('a')) menu.open = false; });
    d.addEventListener('keydown', function (e) { if (e.key === 'Escape' && menu.open) { menu.open = false; menu.querySelector('summary').focus(); } });
    d.addEventListener('click', function (e) { if (menu.open && !menu.contains(e.target)) menu.open = false; });
  }

  // ── Copy ──
  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
    return new Promise(function (ok, fail) {
      var ta = d.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.className = 'sr-only';
      d.body.appendChild(ta);
      ta.select();
      try { d.execCommand('copy') ? ok() : fail(); } catch (e) { fail(e); }
      ta.remove();
    });
  }
  var live = d.createElement('div');
  live.className = 'sr-only';
  live.setAttribute('aria-live', 'polite');
  d.body.appendChild(live);
  d.querySelectorAll('button.copy').forEach(function (b) {
    b.hidden = false;
    var label = b.textContent;
    b.addEventListener('click', function () {
      var text = b.getAttribute('data-copy');
      if (text == null) {
        var host = b.closest('.code');
        var code = host && host.querySelector('pre');
        text = code ? code.textContent : '';
      }
      copyText(text).then(function () {
        b.textContent = 'Copied';
        b.classList.add('done');
        live.textContent = 'Copied to the clipboard';
        setTimeout(function () { b.textContent = label; b.classList.remove('done'); }, 1600);
      }, function () { b.textContent = 'Press ⌘C'; });
    });
  });

  // ── Downloads ──
  var box = d.querySelector('[data-downloads]');
  if (box) {
    // Lead with the download that suits this device.
    var kind = html.classList.contains('android') ? 'android' : html.classList.contains('mac') ? 'mac' : html.classList.contains('linux') ? 'linux' : null;
    if (kind) {
      var mine = box.querySelector('.dl.' + kind);
      if (mine) mine.classList.add('here');
    }
    function mb(n) { return n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'; }
    function day(s) {
      var t = Date.parse(s + 'T12:00:00Z');
      return isNaN(t) ? s : new Date(t).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
    }
    fetch('/download/latest.json', { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error(r.status);
      return r.json();
    }).then(function (j) {
      if (!j || !j.files) return;
      d.querySelectorAll('[data-version]').forEach(function (e) { e.textContent = j.version; });
      d.querySelectorAll('[data-date]').forEach(function (e) { e.textContent = day(j.date); e.setAttribute('datetime', j.date); });
      Object.keys(j.files).forEach(function (name) {
        var f = j.files[name];
        d.querySelectorAll('[data-file="' + name + '"]').forEach(function (el) {
          var size = el.querySelector('[data-size]');
          if (size && f.size) size.textContent = mb(f.size);
          var sum = el.querySelector('[data-sha]');
          if (sum && f.sha256) {
            sum.textContent = f.sha256;
            var btn = el.querySelector('button.copy');
            if (btn) btn.setAttribute('data-copy', f.sha256);
          }
          el.classList.add('known');
        });
      });
      box.classList.add('loaded');
    }).catch(function () { /* the static links stay as they are */ });
  }

  // ── Arrivals: each section eases in once, the first time it comes into view ──
  if (!reduce && 'IntersectionObserver' in window) {
    var items = d.querySelectorAll('.reveal');
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) {
        if (!e.isIntersecting) return;
        e.target.classList.remove('reveal-pending');
        e.target.classList.add('reveal-in');
        io.unobserve(e.target);
      });
    }, { rootMargin: '0px 0px -8% 0px' });
    var vh = window.innerHeight;
    items.forEach(function (el) {
      // Only what is still below the fold waits; nothing on screen ever disappears.
      if (el.getBoundingClientRect().top > vh) { el.classList.add('reveal-pending'); io.observe(el); }
    });
  }

  // ── The guide's contents follow the reading ──
  var toc = d.querySelector('.toc');
  if (toc && 'IntersectionObserver' in window) {
    var links = {};
    toc.querySelectorAll('a[href^="#"]').forEach(function (a) { links[a.getAttribute('href').slice(1)] = a; });
    var heads = Array.prototype.filter.call(d.querySelectorAll('.md h2[id]'), function (h) { return links[h.id]; });
    var current = null;
    var spy = new IntersectionObserver(function () {
      var top = null;
      for (var i = 0; i < heads.length; i++) { if (heads[i].getBoundingClientRect().top < window.innerHeight * 0.3) top = heads[i]; }
      var id = top ? top.id : heads[0] && heads[0].id;
      if (id === current) return;
      if (current && links[current]) links[current].classList.remove('on');
      current = id;
      if (links[id]) links[id].classList.add('on');
    }, { rootMargin: '0px 0px -60% 0px', threshold: [0, 1] });
    heads.forEach(function (h) { spy.observe(h); });
  }
})();
