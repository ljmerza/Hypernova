// The page frame and the app's components, as HTML. Plain template literals, no dependencies.
// Every value that comes from a file goes through esc(); markup that is already HTML is marked as such.

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// ── Drawn glyphs (1.5 px round strokes, as the app draws its icons) ─────────────────────────────

const svg = (body, cls = 'glyph', vb = '0 0 24 24') => `<svg class="${cls}" viewBox="${vb}" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${body}</svg>`;
export const icon = {
  down: (c) => svg('<path d="M12 4v12M6.5 10.5 12 16l5.5-5.5M5 20h14"/>', c),
  right: (c) => svg('<path d="M5 12h14M13 6l6 6-6 6"/>', c),
  out: (c) => svg('<path d="M14 5h5v5M19 5l-8.5 8.5M18 14v4a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h4"/>', c),
  laptop: (c) => svg('<rect x="4.5" y="5" width="15" height="10.5" rx="1.6"/><path d="M2.5 19h19"/>', c),
  phone: (c) => svg('<rect x="7" y="2.8" width="10" height="18.4" rx="2.2"/><path d="M10.6 18h2.8"/>', c),
  server: (c) => svg('<rect x="4" y="4" width="16" height="6.5" rx="1.6"/><rect x="4" y="13.5" width="16" height="6.5" rx="1.6"/><path d="M7.5 7.25h.01M7.5 16.75h.01M11 7.25h5M11 16.75h5"/>', c),
  box: (c) => svg('<path d="M12 3 20 7.5v9L12 21l-8-4.5v-9z"/><path d="M4 7.5 12 12l8-4.5M12 12v9"/>', c),
  // A PC: a monitor on its stand (no vendor's logo).
  desktop: (c) => svg('<rect x="3.5" y="4" width="17" height="12" rx="1.6"/><path d="M12 16v4M8.5 20h7"/>', c),
  glasses: (c) => svg('<circle cx="7" cy="13.5" r="3.5"/><circle cx="17" cy="13.5" r="3.5"/><path d="M10.5 13.2c1-.8 2-.8 3 0M3.5 13 2.5 9.5M20.5 13l1-3.5"/>', c),
  shield: (c) => svg('<path d="M12 3.5 19 6v5.5c0 4.3-3 7.6-7 9-4-1.4-7-4.7-7-9V6z"/>', c),
  clock: (c) => svg('<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.3 1.6M10 3.5h4"/>', c),
  reset: (c) => svg('<path d="M19 12a7 7 0 1 1-2.05-4.95M19 4.5V9h-4.5"/>', c),
  edit: (c) => svg('<path d="m14.5 5.5 4 4L9 19H5v-4z"/>', c),
  code: (c) => svg('<path d="m8.5 8-4 4 4 4M15.5 8l4 4-4 4"/>', c),
  github: (c) => svg('<path d="M9 19c-4 1.3-4-2-5.5-2.5M14.5 21v-3.4c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.3 4.3 0 0 0-.1-3.2s-1-.3-3.4 1.3a11.5 11.5 0 0 0-6 0C6.3 2.9 5.3 3.2 5.3 3.2a4.3 4.3 0 0 0-.1 3.2A4.6 4.6 0 0 0 3.9 9.6c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21"/>', c),
  check: (c) => svg('<path d="m5 12.5 4.2 4.2L19 7"/>', c),
};

/** The Nova mark: the activity meter's planet, still, lit from the upper left and turned 45°. */
export function mark(size = 26, id = 'nm') {
  return `<svg width="${size}" height="${size}" viewBox="0 0 32 32" fill="none" aria-hidden="true" focusable="false">`
    + `<defs><radialGradient id="${id}g" cx="16" cy="16" r="16" gradientUnits="userSpaceOnUse"><stop offset=".55" stop-color="#7fffa0" stop-opacity=".2"/><stop offset="1" stop-color="#7fffa0" stop-opacity="0"/></radialGradient>`
    + `<radialGradient id="${id}s" cx="11.5" cy="11.5" r="17" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#7fffa0" stop-opacity=".85"/><stop offset=".5" stop-color="#7fffa0" stop-opacity=".26"/><stop offset="1" stop-color="#7fffa0" stop-opacity=".06"/></radialGradient></defs>`
    + `<circle cx="16" cy="16" r="16" fill="url(#${id}g)"/><circle cx="16" cy="16" r="10" fill="url(#${id}s)"/>`
    + `<path d="M16 6A7.07 10 0 0 0 16 26" stroke="#7fffa0" stroke-opacity=".62" stroke-width="1"/><path d="M16 6A7.07 10 0 0 1 16 26" stroke="#7fffa0" stroke-opacity=".24" stroke-width="1"/>`
    + `<circle cx="16" cy="16" r="10" stroke="#7fffa0" stroke-opacity=".45" stroke-width="1"/></svg>`;
}

export const wordmark = () => `<span class="wordmark">hypernova<i>/</i></span>`;

/** The seam: [planet] LABEL ──── trail. state: rest | live | alert. */
export function seam(label, { trail = '', state = 'rest', cls = '', tag = 'div' } = {}) {
  const planet = `<span class="planet${state === 'rest' ? '' : ' ' + state}" aria-hidden="true"></span>`;
  return `<${tag} class="seam${cls ? ' ' + cls : ''}">${planet}<span class="label">${label}</span>${trail ? `<span class="trail">${trail}</span>` : ''}</${tag}>`;
}

export const chip = (text, cls = '') => `<span class="chip${cls ? ' ' + cls : ''}">${text}</span>`;
export const model = (text) => `<span class="model">${text}</span>`;

/** A screenshot: a clean rounded crop with a soft shadow. images.json gives its widths. */
export function shot(ctx, key, alt, { cls = '', sizes = '(min-width: 900px) 300px, 45vw', eager = false } = {}) {
  const im = ctx.images[key];
  if (!im) throw new Error(`unknown image ${key}`);
  const kind = key.startsWith('lens') ? 'lens' : key.startsWith('mac') ? 'mac' : 'phone';
  const ext = im.png ? 'png' : 'webp';
  const widths = im.widths;
  const [w, h] = widths[widths.length - 1];
  const src = (wd) => ctx.asset(`/img/${key}-${wd}.${ext}`);
  const srcset = widths.length > 1 ? ` srcset="${widths.map(([wd]) => `${src(wd)} ${wd}w`).join(', ')}" sizes="${sizes}"` : '';
  const load = eager ? ' fetchpriority="high"' : ' loading="lazy"';
  return `<figure class="shot ${kind}${cls ? ' ' + cls : ''}"><img src="${src(w)}"${srcset} width="${w}" height="${h}" alt="${esc(alt)}"${load} decoding="async"></figure>`;
}

// ── Page frame ─────────────────────────────────────────────────────────────────────────────────

const NAV = [
  ['/#features', 'Features', 'features'],
  ['/#where', 'Where it runs', 'where'],
  ['/guides/', 'Guides', 'guides'],
  ['/faq/', 'FAQ', 'faq'],
];

function header(ctx, current) {
  const link = ([href, text, key]) => `<a href="${href}"${current === key ? ' aria-current="page"' : ''}>${text}</a>`;
  return `<header class="top">
  <div class="wrap">
    <a class="brand" href="/" aria-label="Hypernova, home">${mark(26, 'hm')}${wordmark()}</a>
    <nav class="nav nav-wide" aria-label="Main">
      ${NAV.map(link).join('\n      ')}
      <a href="${ctx.site.repo}" rel="noopener">GitHub</a>
    </nav>
    <details class="menu">
      <summary aria-label="Menu"><span class="bars" aria-hidden="true"></span><span class="menu-text">Menu</span></summary>
      <nav class="menu-panel" aria-label="Main">
        ${NAV.map(link).join('\n        ')}
        <a href="/install/"${current === 'install' ? ' aria-current="page"' : ''}>Install</a>
        <a href="/install/linux/"${current === 'linux' ? ' aria-current="page"' : ''}>Install on Linux</a>
        <a href="${ctx.site.repo}" rel="noopener">GitHub</a>
      </nav>
    </details>
    <a class="pill primary" href="/install/"${current === 'install' ? ' aria-current="page"' : ''}>${icon.down()}Install</a>
  </div>
</header>`;
}

function footer(ctx) {
  const s = ctx.site;
  const gh = (p) => `${s.repo}/blob/main/${p}`;
  const guides = ctx.guides.map((g) => `<li><a href="/guides/${g.slug}/">${esc(g.nav)}</a></li>`).join('');
  return `<footer class="site-foot">
  <div class="wrap">
    <div class="site-foot-grid">
      <div>
        <a class="brand" href="/" aria-label="Hypernova, home">${mark(24, 'fm')}${wordmark()}</a>
        <p>A control room for Claude Code and Codex: on your Android phone, in your Mac's menu bar and on Even G2 glasses, for agents that run on your Mac or your own Linux server.</p>
        <p>Open source under the Apache License 2.0. Made by <a href="${s.author.url}" rel="author">${esc(s.author.name)}</a>.</p>
      </div>
      <div>
        <h2>Install</h2>
        <ul>
          <li><a href="/install/">Mac and Android</a></li>
          <li><a href="/install/linux/">Linux server or VPS</a></li>
          <li><a href="/install/linux/#try-it-in-docker">Docker</a></li>
          <li><a href="/install/#build-it-yourself">Build from source</a></li>
          <li><a href="${s.releases}" rel="noopener">GitHub releases</a></li>
        </ul>
      </div>
      <div>
        <h2>Guides</h2>
        <ul>${guides}<li><a href="/faq/">FAQ</a></li></ul>
      </div>
      <div>
        <h2>Project</h2>
        <ul>
          <li><a href="${s.repo}" rel="noopener">Source on GitHub</a></li>
          <li><a href="${s.issues}" rel="noopener">Issues</a></li>
          <li><a href="${gh('docs/SECURITY-MODEL.md')}" rel="noopener">Security model</a></li>
          <li><a href="${gh('docs/PROTOCOL.md')}" rel="noopener">Protocol</a></li>
          <li><a href="${gh('docs/DECISIONS.md')}" rel="noopener">Decisions</a></li>
          <li><a href="${gh('docs/LINUX.md')}" rel="noopener">Linux and Docker</a></li>
          <li><a href="${gh('LICENSE')}" rel="noopener">License</a></li>
          <li><a href="/llms.txt">llms.txt</a></li>
        </ul>
      </div>
    </div>
    <div class="legal">
      <p>© 2026 <a href="${s.author.url}">${esc(s.author.name)}</a>. Hypernova ${esc(ctx.version)}, Apache-2.0. Fonts: Manrope and JetBrains Mono, <a href="${ctx.asset('/fonts/OFL-Manrope.txt')}">OFL</a>.</p>
      <p>Claude and Claude Code are products of Anthropic, Codex of OpenAI, G2 of Even Realities. Hypernova is an independent project, not affiliated with any of them.</p>
    </div>
  </div>
</footer>`;
}

/**
 * The whole page. p: { path, title, description, body, current, jsonld: [objects], scripts: [...],
 * ogImage, noindex, head }
 */
export function page(ctx, p) {
  const s = ctx.site;
  const url = s.url + p.path;
  const og = s.url + (p.ogImage || '/og.jpg');
  const ver = ctx.verification;
  const metas = [
    ver.google && `<meta name="google-site-verification" content="${esc(ver.google)}">`,
    ver.bing && `<meta name="msvalidate.01" content="${esc(ver.bing)}">`,
    ver.yandex && `<meta name="yandex-verification" content="${esc(ver.yandex)}">`,
  ].filter(Boolean).join('\n');
  const ld = (p.jsonld || []).map((o) => `<script type="application/ld+json">${JSON.stringify(o).replace(/</g, '\\u003c')}</script>`).join('\n');
  const scripts = (p.scripts || ['site', 'sky']).map((n) => `<script src="${ctx.asset(`/js/${n}.js`)}" defer></script>`).join('\n');
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${esc(p.title)}</title>
<meta name="description" content="${esc(p.description)}">
${p.noindex ? '<meta name="robots" content="noindex">' : `<link rel="canonical" href="${esc(url)}">`}
<meta name="theme-color" content="#14110e">
<meta name="color-scheme" content="dark">
<meta name="author" content="${esc(s.author.name)}">
<link rel="icon" href="/favicon.ico" sizes="32x32">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="preload" href="${ctx.asset('/fonts/Manrope.ttf')}" as="font" type="font/ttf" crossorigin>
<link rel="stylesheet" href="${ctx.asset('/css/site.css')}">
<link rel="sitemap" type="application/xml" href="/sitemap.xml">
<script src="${ctx.asset('/js/boot.js')}"></script>
${scripts}
<meta property="og:type" content="${p.ogType || 'website'}">
<meta property="og:site_name" content="Hypernova">
<meta property="og:title" content="${esc(p.ogTitle || p.title)}">
<meta property="og:description" content="${esc(p.description)}">
<meta property="og:url" content="${esc(url)}">
<meta property="og:image" content="${esc(og)}">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="Hypernova: the app's sky of sessions with the sun as an accretion disk, and a control room for Claude Code and Codex.">
<meta property="og:locale" content="en_US">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="${esc(p.ogTitle || p.title)}">
<meta name="twitter:description" content="${esc(p.description)}">
<meta name="twitter:image" content="${esc(og)}">
${metas}
${ld}
</head>
<body${p.bodyClass ? ` class="${p.bodyClass}"` : ''}>
<a class="skip" href="#main">Skip to content</a>
<div class="backdrop" aria-hidden="true"></div>
<canvas class="nebulae" aria-hidden="true"></canvas>
<canvas class="sky" aria-hidden="true"></canvas>
<div class="page">
${header(ctx, p.current)}
<main id="main">
${p.body}
</main>
${footer(ctx)}
</div>
<canvas class="grain" aria-hidden="true"></canvas>
</body>
</html>
`;
}

/** Breadcrumbs, visible and as JSON-LD. items: [[name, path], …], the last is this page. */
export function crumbs(ctx, items) {
  const html = `<nav aria-label="Breadcrumb"><ol class="crumbs">${items.map(([n, p], i) => i === items.length - 1
    ? `<li><span aria-current="page">${esc(n)}</span></li>`
    : `<li><a href="${p}">${esc(n)}</a></li>`).join('')}</ol></nav>`;
  const ld = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map(([n, p], i) => ({ '@type': 'ListItem', position: i + 1, name: n, item: ctx.site.url + p })),
  };
  return { html, ld };
}

export function person(ctx) {
  const a = ctx.site.author;
  return { '@type': 'Person', '@id': `${ctx.site.url}/#author`, name: a.name, url: a.url, sameAs: [a.url, a.github] };
}
