# Third-party notices

Hypernova is Apache-2.0 (`LICENSE`, `NOTICE`). It uses the following software, each under its own
licence; the full texts are in the packages themselves (`node_modules/*/LICENSE`, the Maven artifacts)
and in `third_party/`.

## Shipped in the Android app (`Hypernova.apk`)

| Component | Licence |
| --- | --- |
| AndroidX (Core, Activity, Lifecycle) and Jetpack Compose (UI, Foundation, Animation, Graphics) | Apache-2.0 |
| Haze (`dev.chrisbanes.haze`) | Apache-2.0 |
| OkHttp and Okio (Square) | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines (JetBrains) | Apache-2.0 |
| xterm.js and its fit addon (the terminal view, `third_party/xterm`) | MIT |
| Mermaid 11.17.2 (the chat's diagrams, `third_party/mermaid`), with the libraries its `mermaid.min.js` bundles: D3, dagre-d3-es, cytoscape, KaTeX, marked, lodash-es, Langium, Chevrotain, DOMPurify and others (the notices the bundle kept are at its end) | MIT; D3 ISC, d3-sankey BSD-3-Clause, Chevrotain Apache-2.0, DOMPurify MPL-2.0 or Apache-2.0 |
| Manrope and JetBrains Mono (`third_party/fonts`) | SIL Open Font License 1.1 |

## Shipped in the Mac app (`Hypernova.dmg`) and the Linux package (`Hypernova-linux.tar.gz`)

| Component | Licence |
| --- | --- |
| node-pty (Microsoft) | MIT |
| ws | MIT |
| @xterm/headless and @xterm/addon-serialize | MIT |
| @anthropic-ai/claude-agent-sdk (the JavaScript client the daemon uses to run Claude Code; **not bundled**: the Mac app's Setup fetches it from npm onto your Mac, the Linux install with `npm ci`) | Anthropic's terms: "© Anthropic PBC. All rights reserved. Use is subject to Anthropic's Legal Agreements" |
| zod and the other packages of the daemon's `node_modules` | MIT, ISC or Apache-2.0: see each package's own LICENSE file, which stays in the bundle |
| Node.js (downloaded at first use from nodejs.org, not bundled) | MIT and others: https://github.com/nodejs/node/blob/main/LICENSE |
| Manrope and JetBrains Mono | SIL Open Font License 1.1 (`Fonts/OFL-*.txt`) |

Hypernova does **not** include Claude Code, Codex or any other agent: it runs the copy you installed.
