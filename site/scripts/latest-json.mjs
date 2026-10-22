#!/usr/bin/env node
// Writes download/latest.json for the site from the release's files, the format the install page
// reads (a reference for the release script; plain Node, no dependencies):
//
//   node site/scripts/latest-json.mjs <download dir> <version> [<date YYYY-MM-DD>]
//
// {"version":"0.2.0","date":"2026-10-22","files":{"Hypernova.dmg":{"size":N,"sha256":"…"},…}}
// It lists the files the page knows that are in the folder: Hypernova.dmg, Hypernova.apk,
// Hypernova-linux.tar.gz, Hypernova-Lens.ehpk and SHA256SUMS.txt. Write it last, after the files themselves are in place.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const [dir, version, date = new Date().toISOString().slice(0, 10)] = process.argv.slice(2);
if (!dir || !version) { console.error('usage: latest-json.mjs <download dir> <version> [<date>]'); process.exit(2); }
if (!/^\d+\.\d+\.\d+([-.+][\w.]+)?$/.test(version) || !/^\d{4}-\d{2}-\d{2}$/.test(date)) { console.error('latest-json: version like 0.2.0, date like 2026-10-22'); process.exit(2); }
const names = ['Hypernova.dmg', 'Hypernova.apk', 'Hypernova-linux.tar.gz', 'Hypernova-Lens.ehpk', 'SHA256SUMS.txt'];
const files = {};
for (const name of names) {
  const f = path.join(dir, name);
  if (!fs.existsSync(f)) { console.warn(`latest-json: ${name} is not in ${dir}, left out`); continue; }
  const hash = crypto.createHash('sha256');
  const fd = fs.openSync(f, 'r');
  const buf = Buffer.alloc(1 << 20);
  for (let n; (n = fs.readSync(fd, buf, 0, buf.length, null)) > 0;) hash.update(buf.subarray(0, n));
  fs.closeSync(fd);
  files[name] = { size: fs.statSync(f).size, sha256: hash.digest('hex') };
}
const out = path.join(dir, 'latest.json');
fs.writeFileSync(out + '.tmp', JSON.stringify({ version, date, files }, null, 2) + '\n');
fs.renameSync(out + '.tmp', out); // atomic: the page never reads half a file
console.log(`Wrote ${out}: ${Object.keys(files).join(', ')}`);
