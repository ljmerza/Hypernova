<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->
# Third-party notices

Hypernova is Apache-2.0 (`LICENSE`, `NOTICE`). It uses the following software, each under its own
licence; the full texts are in the packages themselves (`node_modules/*/LICENSE`, the Maven artifacts)
and in `third_party/`, where `LICENSES-mermaid-bundled.txt` and `LICENSES-xterm-bundled.txt` hold those of the
libraries bundled inside Mermaid's and the unicode-graphemes add-on's single files (in the app, `assets/legal/`).

## Shipped in the Android app (`Hypernova.apk`)

| Component | Licence |
| --- | --- |
| AndroidX (Core, Activity, Lifecycle, CameraX, with the Dagger runtime CameraX uses) and Jetpack Compose (UI, Foundation, Animation, Graphics) | Apache-2.0 |
| ZXing core 3.5.4 (`com.google.zxing:core`; reads the QR code in Scan). The ZXing project's NOTICE: "Barcode4J, Copyright 2002-2010 Jeremias Märki, Copyright 2005-2006 Dietmar Bürkle. Portions of this software were contributed under section 5 of the Apache License. Contributors are listed under: http://barcode4j.sourceforge.net/contributors.html"; "JCommander, Copyright 2010 Cedric Beust cedric@beust.com" | Apache-2.0 |
| Haze (`dev.chrisbanes.haze`) | Apache-2.0 |
| OkHttp and Okio (Square) | Apache-2.0 |
| Kotlin standard library and kotlinx.coroutines (JetBrains) | Apache-2.0 |
| xterm.js 6.0.0 and its add-ons: fit 0.11.0, unicode-graphemes 0.4.0, web-links 0.12.0, search 0.16.0 (the terminal view, `third_party/xterm`). The unicode-graphemes add-on bundles adapted copies of tiny-inflate and unicode-trie (Devon Govett) and tables made with unicode-properties (Per Bothner) | MIT |
| ghostty-web 0.4.0-next.21 (Coder; the trial terminal view, `assets/term-ghostty/`, `third_party/ghostty-web`), with the WebAssembly build of Ghostty's terminal core it bundles | MIT; Ghostty MIT |
| Mermaid 12.2.0 (the chat's diagrams, `third_party/mermaid`), with the libraries its `mermaid.min.js` bundles: D3, dagre-d3-es, ELK (elkjs), cytoscape, KaTeX, marked, lodash-es, Langium, Chevrotain, DOMPurify and others (the notices the bundle kept are at its end) | MIT; D3 ISC, d3-sankey BSD-3-Clause, Chevrotain Apache-2.0, DOMPurify MPL-2.0 or Apache-2.0, elkjs 0.9.3 EPL-2.0, unmodified (its source code is available under that license: tag `0.9.3` of https://github.com/kieler/elkjs) |
| Manrope and JetBrains Mono (`third_party/fonts`) | SIL Open Font License 1.1 |

## In the desktop client's packages (`hypernova-client-*`: `.deb`, `.tar.gz`, `.dmg`, `.msi`)

Read from the Maven POMs of its runtime classpath, 2026-11-02. Each package holds these in its `resources/legal` folder, beside the app's jars.

| Component | Licence |
| --- | --- |
| Compose Multiplatform for Desktop and what it brings (JetBrains: `org.jetbrains.compose.*`, `org.jetbrains.androidx.*`; AndroidX: `androidx.*`) | Apache-2.0 |
| Skiko (JetBrains: `org.jetbrains.skiko`, its native library included), with the Skia graphics library inside it, and what's built into that: HarfBuzz, FreeType, ICU, libpng, libjpeg-turbo, libwebp, Expat, zlib, Wuffs, PIEX | Apache-2.0; the others' own licences, in `LICENSES-skia-bundled.txt` |
| OkHttp and Okio (Square), Kotlin standard library and kotlinx.coroutines with `kotlinx-coroutines-swing`, jetbrains-annotations, JSpecify | Apache-2.0 |
| JSON-java (`org.json`) | Public domain |
| Manrope and JetBrains Mono (`third_party/fonts`) | SIL Open Font License 1.1 (`OFL-*.txt`) |
| The Java runtime each package carries, all of its modules: OpenJDK, as Eclipse Temurin 21's build (source: https://github.com/adoptium/jdk21u) | GPL-2.0 with the Classpath Exception, and the licences of what it holds: its own `legal` folder |

## Shipped in the Mac app (`Hypernova.dmg`) and the Linux package (`Hypernova-linux.tar.gz`)

| Component | Licence |
| --- | --- |
| node-pty (Microsoft) | MIT |
| ws | MIT |
| @xterm/headless, @xterm/addon-serialize and @xterm/addon-unicode-graphemes (with its bundled tiny-inflate and unicode-trie) | MIT |
| @anthropic-ai/claude-agent-sdk (the JavaScript client the daemon uses to run Claude Code; **not bundled**: the Mac app's Setup fetches it from npm onto your Mac, the Linux install with `npm ci`) | Anthropic's terms: "© Anthropic PBC. All rights reserved. Use is subject to Anthropic's Legal Agreements" |
| zod and the other packages of the daemon's `node_modules` | MIT, ISC or Apache-2.0: see each package's own LICENSE file, which stays in the bundle |
| Node.js (downloaded at first use from nodejs.org, not bundled) | MIT and others: https://github.com/nodejs/node/blob/main/LICENSE |
| Manrope and JetBrains Mono | SIL Open Font License 1.1 (`Fonts/OFL-*.txt`) |

Hypernova does **not** include Claude Code, Codex or any other agent: it runs the copy you installed.
