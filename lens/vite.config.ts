import { defineConfig } from 'vite';
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));

// A private build that may reach a Mac directly (LENS_HOSTS or LENS_WHITELIST, scripts/whitelist.mjs)
// still offers Lens's own pairing; the public build reaches only the Hypernova app on the phone.
const direct = !!process.env.LENS_HOSTS || (process.env.LENS_WHITELIST ?? '').split(',').some(e => e.trim() && !/^(https?|wss?):\/\/(127\.0\.0\.1|localhost)(:\d+)?\//.test(e.trim()));

export default defineConfig({
  base: './',                       // the .ehpk is served from an arbitrary path inside the Even App
  define: {
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(pkg.version),
    'import.meta.env.VITE_LENS_DIRECT': JSON.stringify(direct ? '1' : ''),
  },
  build: { target: 'es2022', assetsInlineLimit: 0 },
  server: { host: '0.0.0.0', port: 5177 },
  preview: { host: '0.0.0.0', port: 5177 },
});
