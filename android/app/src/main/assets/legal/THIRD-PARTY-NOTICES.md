# Third-party notices

Hypernova is Apache-2.0 (`LICENSE`, `NOTICE`). It uses the following software, each under its own
licence; the full texts are in the packages themselves (`node_modules/*/LICENSE`, the Maven artifacts,
Go's module cache) and in `third_party/`.

## Shipped in the Android app (`Hypernova.apk`)

| Component | Licence |
| --- | --- |
| AndroidX (Core, Activity, Lifecycle) and Jetpack Compose (UI, Foundation, Animation, Graphics) | Apache-2.0 |
| Haze (`dev.chrisbanes.haze`) | Apache-2.0 |
| OkHttp and Okio (Square) | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines (JetBrains) | Apache-2.0 |
| The built-in Tailscale node: `tailscale.com/tsnet` and its Go dependencies, the Go runtime, gomobile | BSD-3-Clause (Tailscale Inc., The Go Authors) and the licences of its Go modules |
| xterm.js and its fit addon (the terminal view, `third_party/xterm`) | MIT |
| Manrope and JetBrains Mono (`third_party/fonts`) | SIL Open Font License 1.1 |

## Shipped in the Mac app (`Hypernova.dmg`) and the Linux package (`Hypernova-linux.tar.gz`)

| Component | Licence |
| --- | --- |
| node-pty (Microsoft) | MIT |
| ws | MIT |
| @xterm/headless and @xterm/addon-serialize | MIT |
| @anthropic-ai/claude-agent-sdk (the JavaScript client the daemon uses to run Claude Code; the Mac app bundles it, the Linux install fetches it from npm) | Anthropic's terms: "© Anthropic PBC. All rights reserved. Use is subject to Anthropic's Legal Agreements" |
| zod and the other packages of the daemon's `node_modules` | MIT, ISC or Apache-2.0: see each package's own LICENSE file, which stays in the bundle |
| Node.js (downloaded at first use from nodejs.org, not bundled) | MIT and others: https://github.com/nodejs/node/blob/main/LICENSE |
| The built-in Tailscale helper (`hypernova-tsnet`) | BSD-3-Clause, as above |
| Manrope and JetBrains Mono | SIL Open Font License 1.1 (`Fonts/OFL-*.txt`) |

Hypernova does **not** include Claude Code, Codex or any other agent: it runs the copy you installed.

## Shipped in Hypernova Lens (`Hypernova-Lens.ehpk`)

See `lens/public/licenses.txt` (the Even Hub SDK, Vite's preload helper: MIT; the fonts: OFL).
