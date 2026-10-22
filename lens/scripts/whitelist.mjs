#!/usr/bin/env node
// Writes app.local.json: app.json with the network whitelist filled in. The Even App only lets a
// packaged app reach the hosts listed there.
//
// By default Lens reaches only the Hypernova app on the same phone, at 127.0.0.1:47380 (the phone
// bridge, docs/PROTOCOL.md): the app carries everything to your Mac over its own connection. So one
// public build works for everyone and never names anyone's tailnet. A private build may also reach a
// Mac directly, the older way (pairing Lens on its own): LENS_HOSTS adds Hypernova (port 47321) on each
// host, over https and wss.
// app.json itself keeps an empty list; app.local.json is git-ignored.
//
//   node scripts/whitelist.mjs                                  # the public build: the app on this phone only
//   LENS_HOSTS=my-mac.tailnet.ts.net node scripts/whitelist.mjs  # also that Mac, directly
//   LENS_HOSTS=tailscale node scripts/whitelist.mjs              # also this Mac, by `tailscale status --json`
//   LENS_WHITELIST='https://a/,wss://b:1/' node scripts/whitelist.mjs   # exactly these entries (and the bridge)
//   LENS_WILDCARD=1 …                                            # with hosts, also https://*.ts.net/ (untested by Even Realities)
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';

const APP = new URL('../app.json', import.meta.url);
const OUT = new URL('../app.local.json', import.meta.url);
const BRIDGE = ['http://127.0.0.1:47380/', 'ws://127.0.0.1:47380/'];
const PORTS = [47321];
const TS = ['/Applications/Tailscale.app/Contents/MacOS/Tailscale', '/opt/homebrew/bin/tailscale', '/usr/local/bin/tailscale'].find(existsSync) ?? 'tailscale';

function tailscaleName() {
  try {
    const s = JSON.parse(execFileSync(TS, ['status', '--json'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
    return String(s?.Self?.DNSName ?? '').replace(/\.$/, '') || null;
  } catch { return null; }
}

/** Entries that reach something other than this phone's loopback: a build that may pair directly. */
export const reachesMac = entries => entries.some(e => !/^(https?|wss?):\/\/(127\.0\.0\.1|localhost)(:\d+)?\//.test(e));

let direct = [];
if (process.env.LENS_WHITELIST) direct = process.env.LENS_WHITELIST.split(',').map(s => s.trim()).filter(Boolean);
else if (process.env.LENS_HOSTS) {
  const hosts = process.env.LENS_HOSTS.split(',').map(h => h.trim()).filter(Boolean).map(h => (h === 'tailscale' ? tailscaleName() : h));
  if (hosts.some(h => !h)) {
    console.error('whitelist: no Tailscale name found for LENS_HOSTS=tailscale. Name the host instead: LENS_HOSTS=<your-mac>.<tailnet>.ts.net');
    process.exit(1);
  }
  direct = hosts.flatMap(h => PORTS.flatMap(p => [`https://${h}:${p}/`, `wss://${h}:${p}/`]));
  if (process.env.LENS_WILDCARD) direct.push('https://*.ts.net/', 'wss://*.ts.net/');
}
const entries = [...new Set([...BRIDGE, ...direct])];
const viaMac = reachesMac(entries);

const app = JSON.parse(readFileSync(APP, 'utf8'));
const net = app.permissions.find(p => p.name === 'network');
net.whitelist = entries;
if (viaMac) net.desc = 'Connects to the Hypernova app on this phone, and to your own Mac (its address set at build time): Hypernova for your sessions and speech.';
// The camera only scans a Mac's pairing code, which only a build that reaches a Mac directly uses.
else app.permissions = app.permissions.filter(p => p.name !== 'camera');
writeFileSync(OUT, JSON.stringify(app, null, 2) + '\n');
console.log(`app.local.json whitelist: ${entries.join(' ')}${viaMac ? '' : ' (the Hypernova app on this phone only)'}`);
