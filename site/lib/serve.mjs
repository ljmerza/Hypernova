// A small static server for previewing dist/, with what the real server does that matters for
// testing: index.html for directories, the styled 404, byte ranges, the same Content-Security-Policy
// and the same types for the downloads. Node's http only.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

export const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self'",
  "font-src 'self'",
  "media-src 'self'",
  "connect-src 'self'",
  "manifest-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'",
  "object-src 'none'",
].join('; ');

export const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.ttf': 'font/ttf',
  '.woff2': 'font/woff2',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.dmg': 'application/x-apple-diskimage',
  '.apk': 'application/vnd.android.package-archive',
  '.gz': 'application/gzip',
};

export function serve(root, { port = 4173, host = '127.0.0.1' } = {}) {
  const server = http.createServer((req, res) => {
    let p;
    try { p = decodeURIComponent(new URL(req.url, 'http://x').pathname); } catch { res.writeHead(400).end(); return; }
    let file = path.join(root, p);
    if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
    let st = fs.statSync(file, { throwIfNoEntry: false });
    if (st && st.isDirectory()) {
      if (!p.endsWith('/')) { res.writeHead(301, { Location: p + '/' }).end(); return; }
      file = path.join(file, 'index.html');
      st = fs.statSync(file, { throwIfNoEntry: false });
    }
    const headers = {
      'Content-Security-Policy': CSP,
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Cache-Control': 'no-store',
      'Accept-Ranges': 'bytes',
    };
    let status = 200;
    if (!st || !st.isFile()) {
      status = 404;
      file = path.join(root, '404.html');
      st = fs.statSync(file, { throwIfNoEntry: false });
      if (!st) { res.writeHead(404, headers).end('Not found'); return; }
    }
    const ext = path.extname(file).toLowerCase();
    headers['Content-Type'] = TYPES[ext] || 'application/octet-stream';
    if (ext === '.dmg' || ext === '.apk' || file.endsWith('.tar.gz')) headers['Content-Disposition'] = `attachment; filename="${path.basename(file)}"`;
    let start = 0, end = st.size - 1;
    const range = status === 200 && req.headers.range && /^bytes=(\d*)-(\d*)$/.exec(req.headers.range);
    if (range) {
      if (range[1] === '' && range[2] !== '') { start = Math.max(0, st.size - +range[2]); }
      else { start = +range[1] || 0; if (range[2] !== '') end = Math.min(end, +range[2]); }
      if (start > end || start >= st.size) { res.writeHead(416, { ...headers, 'Content-Range': `bytes */${st.size}` }).end(); return; }
      status = 206;
      headers['Content-Range'] = `bytes ${start}-${end}/${st.size}`;
    }
    headers['Content-Length'] = String(st.size ? end - start + 1 : 0);
    res.writeHead(status, headers);
    if (req.method === 'HEAD' || st.size === 0) { res.end(); return; }
    fs.createReadStream(file, { start, end }).pipe(res);
  });
  server.listen(port, host, () => console.log(`Serving ${root} at http://${host}:${port}/  (Ctrl-C to stop)`));
  return server;
}
