// A small, careful Markdown renderer for the repo's guides (docs/INSTALL.md, docs/LINUX.md) and the
// site's own pages. It covers what those files use, the GitHub way:
//   ATX headings (with GitHub's anchor slugs, deduplicated), paragraphs, hard-wrapped lines,
//   ordered and unordered lists (nested, loose or tight, with code blocks inside items),
//   GitHub pipe tables (with `\|` inside cells), fenced code (``` or ~~~, with a copy button),
//   block quotes, thematic breaks, inline code, **bold**, *italic* / _italic_, ~~strike~~,
//   [links](url "title"), <autolinks>, bare https:// URLs, ![images](img:key), and two trailing
//   spaces or a backslash for a line break.
// Anything else is escaped and shown as text, never passed through as HTML.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const escapeHtml = (s) => String(s).replace(/[&<>"']/g, (c) => ESC[c]);

/** GitHub's heading slug: lower case, punctuation dropped (but - and _), spaces to hyphens. */
export function slugify(text) {
  return text
    .toLowerCase()
    .trim()
    .replace(/<[^>]+>/g, '')
    .replace(/[^\p{L}\p{N}\p{M}\s_-]/gu, '')
    .replace(/\s/g, '-');
}

/** Plain text of an inline Markdown string (for slugs, tables of contents and JSON-LD). */
export function inlineText(md) {
  return md
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/(\*\*|__)(.+?)\1/g, '$2')
    .replace(/(\*|_)(.+?)\1/g, '$2')
    .replace(/\\([\\`*_{}\[\]()#+\-.!|>~])/g, '$1');
}

const RE = {
  blank: /^\s*$/,
  heading: /^ {0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+#+)?[ \t]*$/,
  fence: /^( {0,3})(`{3,}|~{3,})[ \t]*([^`\s]*)[^`]*$/,
  hr: /^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/,
  quote: /^ {0,3}> ?/,
  bullet: /^( {0,3})([-*+])([ \t]+|$)/,
  ordered: /^( {0,3})(\d{1,9})([.)])([ \t]+|$)/,
  tableSep: /^ {0,3}\|?[ \t]*:?-{2,}:?[ \t]*(\|[ \t]*:?-{2,}:?[ \t]*)*\|?[ \t]*$/,
};

/**
 * Renders Markdown to HTML.
 * opts.link(href) → href     rewrites link targets (relative links to GitHub, for instance)
 * opts.image(key, alt) → html  renders ![alt](img:key) (and other images, with key = the URL)
 * opts.headingIds            a Map shared across calls on one page (GitHub numbers repeats)
 * opts.onHeading({level, text, id})   collects headings
 * opts.shift                 adds to every heading's level (a guide's ## stays h2 when 0)
 * opts.codeLabel(lang)       the label above a code block
 */
export function renderMarkdown(src, opts = {}) {
  const ctx = {
    link: opts.link || ((h) => h),
    image: opts.image || ((key, alt) => `<img src="${escapeHtml(key)}" alt="${escapeHtml(alt)}">`),
    ids: opts.headingIds || new Map(),
    onHeading: opts.onHeading || (() => {}),
    shift: opts.shift || 0,
    codeLabel: opts.codeLabel || ((lang) => (lang || 'text').toUpperCase()),
    anchors: opts.anchors !== false,
  };
  const lines = src.replace(/\r\n?/g, '\n').replace(/\t/g, '    ').split('\n');
  return blocks(lines, ctx, false);
}

function uniqueId(ctx, text) {
  const base = slugify(text) || 'section';
  const n = ctx.ids.get(base);
  if (n === undefined) { ctx.ids.set(base, 0); return base; }
  ctx.ids.set(base, n + 1);
  return `${base}-${n + 1}`;
}

function startsBlock(line) {
  return RE.heading.test(line) || RE.fence.test(line) || RE.hr.test(line) || RE.quote.test(line);
}

function indentOf(line) { return line.match(/^ */)[0].length; }

function dedent(line, n) {
  let i = 0;
  while (i < n && line[i] === ' ') i++;
  return line.slice(i);
}

/** Splits a table row into cells, keeping `\|` and pipes inside code spans. */
function cells(row) {
  let s = row.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|') && !s.endsWith('\\|')) s = s.slice(0, -1);
  const out = [];
  let cur = '', code = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (c === '\\' && s[i + 1] === '|') { cur += '|'; i++; continue; }
    if (c === '`') { code = code ? 0 : 1; cur += c; continue; }
    if (c === '|' && !code) { out.push(cur.trim()); cur = ''; continue; }
    cur += c;
  }
  out.push(cur.trim());
  return out;
}

function blocks(lines, ctx, tight) {
  let html = '';
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (RE.blank.test(line)) { i++; continue; }

    let m;
    // Fenced code.
    if ((m = line.match(RE.fence))) {
      const ind = m[1].length, fence = m[2], lang = m[3] || '';
      const body = [];
      i++;
      while (i < lines.length) {
        const l = lines[i];
        const close = l.match(/^( {0,3})(`{3,}|~{3,})[ \t]*$/);
        if (close && close[2][0] === fence[0] && close[2].length >= fence.length) { i++; break; }
        body.push(dedent(l, ind));
        i++;
      }
      html += codeBlock(body.join('\n'), lang, ctx);
      continue;
    }
    // Heading.
    if ((m = line.match(RE.heading))) {
      const level = Math.min(6, m[1].length + ctx.shift);
      const raw = m[2].trim();
      const text = inlineText(raw);
      const id = uniqueId(ctx, text);
      ctx.onHeading({ level, text, id, raw });
      const anchor = ctx.anchors && level >= 2
        ? `<a class="md-anchor" href="#${id}" aria-label="Link to this section">#</a>` : '';
      html += `<h${level} id="${id}">${inline(raw, ctx)}${anchor}</h${level}>\n`;
      i++;
      continue;
    }
    // Thematic break.
    if (RE.hr.test(line) && !RE.bullet.test(line.replace(/^( {0,3})([-*+])(?=\S)/, ''))) {
      html += '<hr>\n';
      i++;
      continue;
    }
    // Block quote.
    if (RE.quote.test(line)) {
      const body = [];
      while (i < lines.length && !RE.blank.test(lines[i])) {
        body.push(lines[i].replace(RE.quote, ''));
        i++;
      }
      html += `<blockquote>\n${blocks(body, ctx, false)}</blockquote>\n`;
      continue;
    }
    // Table: a header row, then the delimiter row.
    if (line.includes('|') && i + 1 < lines.length && RE.tableSep.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      const head = cells(line);
      const aligns = cells(lines[i + 1]).map((c) => (c.startsWith(':') && c.endsWith(':') ? 'center' : c.endsWith(':') ? 'right' : c.startsWith(':') ? 'left' : ''));
      i += 2;
      const rows = [];
      while (i < lines.length && !RE.blank.test(lines[i]) && lines[i].includes('|') && !startsBlock(lines[i])) {
        rows.push(cells(lines[i]));
        i++;
      }
      const al = (k) => (aligns[k] ? ` class="al-${aligns[k]}"` : '');
      html += '<div class="md-table" role="region" tabindex="0" aria-label="Table"><table>\n<thead><tr>'
        + head.map((c, k) => `<th scope="col"${al(k)}>${inline(c, ctx)}</th>`).join('')
        + '</tr></thead>\n<tbody>\n'
        + rows.map((r) => '<tr>' + head.map((_, k) => `<td${al(k)}>${inline(r[k] ?? '', ctx)}</td>`).join('') + '</tr>\n').join('')
        + '</tbody></table></div>\n';
      continue;
    }
    // Lists.
    if (RE.bullet.test(line) || RE.ordered.test(line)) {
      const r = list(lines, i, ctx);
      html += r.html;
      i = r.next;
      continue;
    }
    // Paragraph: until a blank line or the start of another block.
    const para = [line];
    i++;
    while (i < lines.length && !RE.blank.test(lines[i]) && !startsBlock(lines[i])
      && !RE.bullet.test(lines[i]) && !/^ {0,3}1[.)][ \t]/.test(lines[i])
      && !(lines[i].includes('|') && i + 1 < lines.length && RE.tableSep.test(lines[i + 1]))) {
      para.push(lines[i]);
      i++;
    }
    const text = para.map((l) => l.trim() === '' ? l : l.replace(/^ +/, '')).join('\n');
    // An image alone on its line is a block (a figure), not a paragraph.
    if (/^!\[[^\]]*\]\([^)]+\)$/.test(text.trim())) { html += inline(text.trim(), ctx) + '\n'; continue; }
    html += tight ? inline(text, ctx) + '\n' : `<p>${inline(text, ctx)}</p>\n`;
  }
  return html;
}

function list(lines, start, ctx) {
  const first = lines[start];
  const ordered = RE.ordered.test(first);
  const m0 = ordered ? first.match(RE.ordered) : first.match(RE.bullet);
  const baseIndent = m0[1].length;
  const marker = ordered ? m0[3] : m0[2];
  const startNum = ordered ? parseInt(m0[2], 10) : 1;
  const items = [];
  let i = start;
  let loose = false;
  while (i < lines.length) {
    const line = lines[i];
    const m = ordered ? line.match(RE.ordered) : line.match(RE.bullet);
    if (!m || m[1].length !== baseIndent || (ordered ? m[3] : m[2]) !== marker) break;
    // The content indent: where the text after the marker starts.
    const markerWidth = m[0].length;
    const contentIndent = m[4] === '' || m[3] === '' ? baseIndent + (ordered ? m[2].length + 2 : 2) : markerWidth;
    const body = [line.slice(markerWidth)];
    i++;
    let sawBlank = false;
    while (i < lines.length) {
      const l = lines[i];
      if (RE.blank.test(l)) { body.push(''); sawBlank = true; i++; continue; }
      const ind = indentOf(l);
      if (ind >= contentIndent) {
        if (sawBlank) loose = loose || !isNestedListStart(l, contentIndent) || body.some((b) => b !== '' && false);
        body.push(dedent(l, contentIndent));
        sawBlank = false;
        i++;
        continue;
      }
      // A lazy continuation line of the paragraph (hard-wrapped, not indented).
      if (!sawBlank && ind > baseIndent && !RE.bullet.test(l) && !RE.ordered.test(l) && !startsBlock(l)) {
        body.push(l.trim());
        i++;
        continue;
      }
      if (!sawBlank && ind <= baseIndent && !RE.bullet.test(l) && !RE.ordered.test(l) && !startsBlock(l) && !RE.blank.test(l)) {
        body.push(l.trim());
        i++;
        continue;
      }
      break;
    }
    // Trailing blanks belong between items: a blank before the next item makes the list loose.
    while (body.length && body[body.length - 1] === '') {
      body.pop();
      const next = lines[i];
      const nm = next && (ordered ? next.match(RE.ordered) : next.match(RE.bullet));
      if (nm && nm[1].length === baseIndent) loose = true;
    }
    items.push(body);
  }
  const tag = ordered ? 'ol' : 'ul';
  const startAttr = ordered && startNum !== 1 ? ` start="${startNum}"` : '';
  let html = `<${tag}${startAttr}>\n`;
  for (const body of items) {
    // A tight item keeps its first paragraph bare; anything after it (a nested list, code) renders as blocks.
    html += `<li>${blocks(body, ctx, !loose && isSimple(body))}</li>\n`;
  }
  html += `</${tag}>\n`;
  return { html, next: i };
}

function isNestedListStart(line, indent) {
  const l = dedent(line, indent);
  return RE.bullet.test(l) || RE.ordered.test(l);
}

/** An item whose only blocks are one paragraph and nested lists can stay tight. */
function isSimple(body) {
  let para = 0, inPara = false;
  for (const l of body) {
    if (l === '') { inPara = false; continue; }
    if (RE.bullet.test(l) || RE.ordered.test(l)) { inPara = false; continue; }
    if (RE.fence.test(l) || RE.heading.test(l) || RE.quote.test(l)) return false;
    if (/^ {2,}/.test(l)) continue;
    if (!inPara) { para++; inPara = true; }
  }
  return para <= 1;
}

function codeBlock(code, lang, ctx) {
  const label = ctx.codeLabel(lang);
  // Comments in shell-like blocks are dimmed; the copy button copies the text exactly.
  const shell = /^(sh|bash|zsh|shell|console)$/i.test(lang);
  const body = code.split('\n').map((l) => {
    if (!shell) return escapeHtml(l);
    const k = commentStart(l);
    if (k < 0) return escapeHtml(l);
    return escapeHtml(l.slice(0, k)) + `<span class="c">${escapeHtml(l.slice(k))}</span>`;
  }).join('\n');
  return `<div class="code"><div class="code-head"><span class="code-lang">${escapeHtml(label)}</span>`
    + `<button type="button" class="copy" hidden data-copy-target>Copy</button></div>`
    + `<pre><code${lang ? ` class="language-${escapeHtml(lang)}"` : ''}>${body}</code></pre></div>\n`;
}

/** Where a shell comment starts: a # at the start of a word, outside quotes. */
function commentStart(line) {
  let q = null;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (q) { if (c === q) q = null; continue; }
    if (c === '"' || c === "'") { q = c; continue; }
    if (c === '#' && (i === 0 || /\s/.test(line[i - 1]))) return i;
  }
  return -1;
}

// ── Inline ────────────────────────────────────────────────────────────────────────────────────

export function inline(text, ctx) {
  ctx = ctx || { link: (h) => h, image: (k, a) => `<img src="${escapeHtml(k)}" alt="${escapeHtml(a)}">` };
  const slots = [];
  const keep = (html) => `\u0000${slots.push(html) - 1}\u0000`;

  let s = text;
  // Code spans first: nothing inside them is Markdown.
  s = s.replace(/(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)/g, (_, ticks, code) => {
    let c = code.replace(/\n/g, ' ');
    if (/^ .* $/.test(c)) c = c.slice(1, -1);
    return keep(`<code>${escapeHtml(c)}</code>`);
  });
  // Backslash escapes.
  s = s.replace(/\\([\\`*_{}\[\]()#+\-.!|>~<])/g, (_, c) => keep(escapeHtml(c)));
  // Images, then links (their text is inline Markdown too).
  s = s.replace(/!\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+"([^"]*)")?\s*\)/g, (_, alt, src) => {
    const key = src.startsWith('img:') ? src.slice(4) : src;
    return keep(ctx.image(key, inlineText(alt)));
  });
  s = s.replace(/\[((?:[^\[\]]|\[[^\]]*\])*)\]\(\s*<?([^)\s>]+)>?(?:\s+"([^"]*)")?\s*\)/g, (_, label, href, title) => {
    const url = ctx.link(href);
    const ext = /^https?:\/\//.test(url);
    const t = title ? ` title="${escapeHtml(title)}"` : '';
    return keep(`<a href="${escapeHtml(url)}"${t}${ext ? ' rel="noopener"' : ''}>${inline(label, ctx)}</a>`);
  });
  // Autolinks and bare URLs.
  s = s.replace(/<(https?:\/\/[^>\s]+)>/g, (_, u) => keep(`<a href="${escapeHtml(u)}" rel="noopener">${escapeHtml(u)}</a>`));
  s = s.replace(/(^|[\s(])(https?:\/\/[^\s<)]+[^\s<).,;:!?'"])/g, (_, pre, u) => pre + keep(`<a href="${escapeHtml(u)}" rel="noopener">${escapeHtml(u)}</a>`));
  // Now it's safe to escape what's left.
  s = escapeHtml(s);
  // Strong, emphasis, strike.
  s = s.replace(/\*\*(?=\S)([\s\S]*?\S)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^\w])__(?=\S)([\s\S]*?\S)__(?!\w)/g, '$1<strong>$2</strong>');
  s = s.replace(/(^|[^*\w])\*(?=[^\s*])([\s\S]*?[^\s*])\*(?!\*)/g, '$1<em>$2</em>');
  s = s.replace(/(^|[^\w])_(?=\S)([\s\S]*?\S)_(?!\w)/g, '$1<em>$2</em>');
  s = s.replace(/~~(?=\S)([\s\S]*?\S)~~/g, '<del>$1</del>');
  // Hard breaks, then soft ones.
  s = s.replace(/( {2,}|\\)\n/g, '<br>\n');
  s = s.replace(/\u0000(\d+)\u0000/g, (_, n) => slots[+n]);
  // Slots can hold slots (a link's text with code in it).
  s = s.replace(/\u0000(\d+)\u0000/g, (_, n) => slots[+n]);
  return s;
}

/** Splits `---` front matter (simple `key: value` lines) from a Markdown file. */
export function frontMatter(src) {
  const m = src.match(/^---\n([\s\S]*?)\n---\n?/);
  if (!m) return { data: {}, body: src };
  const data = {};
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^([A-Za-z][\w-]*):\s*(.*)$/);
    if (kv) data[kv[1]] = kv[2].replace(/^(["'])(.*)\1$/, '$2').trim();
  }
  return { data, body: src.slice(m[0].length) };
}

/**
 * Splits rendered-ready Markdown into its "## " sections: [{ title, id?, body }]. Text before the
 * first one is the section with title null. Fenced code is respected.
 */
export function sections(md, level = 2) {
  const out = [{ title: null, body: [] }];
  let fence = null;
  for (const line of md.split('\n')) {
    const f = line.match(/^ {0,3}(`{3,}|~{3,})/);
    if (f) { if (!fence) fence = f[1][0]; else if (f[1][0] === fence) fence = null; }
    const h = !fence && line.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
    if (h && h[1].length === level) { out.push({ title: h[2], body: [] }); continue; }
    out[out.length - 1].body.push(line);
  }
  return out.map((s) => ({ title: s.title, body: s.body.join('\n') }));
}
