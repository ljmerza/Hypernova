# hypernova.nikbraun.tech

Hypernova's website: what it is, the install page, the Linux page, guides and the FAQ, in the
Android app's own look. Static files, built by one Node script with no dependencies, served by
Caddy from `/var/www/hypernova`.

The install pages are **rendered from the repository's guides** (`docs/INSTALL.md`, `docs/LINUX.md`),
so the app, the README and the site always say the same thing. Edit the guide, rebuild the site.

## Build, preview, check

Node 22 or later (the repo is tested on Node 24 and 25). From the repository root:

```sh
node site/build.mjs                     # → site/dist/
node site/build.mjs --sample --serve    # + a sample download/latest.json, at http://127.0.0.1:4173/
node site/build.mjs --check             # build, then check every page, link and anchor
```

| Option | What it does |
| --- | --- |
| `--install-md <file>` | The install guide to render (default `docs/INSTALL.md`) |
| `--linux-md <file>` | The Linux guide to render at `/install/linux/` (default `docs/LINUX.md`) |
| `--media <dir>` | Where the intro film is; see "The film" |
| `--latest <file>` | A `latest.json` whose sizes and checksums are printed into the pages (they are shown without JavaScript too) |
| `--sample` | Adds `src/sample/latest.json` as `dist/download/latest.json`, **for local preview only** (made-up sizes and checksums) |
| `--indexnow-key <key>` | Writes `<key>.txt` for IndexNow (read from `site/.cache/indexnow-key` when that exists) |
| `--out <dir>` | Build somewhere else (default `site/dist`) |
| `--no-compress` | Skip the `.br` and `.gz` copies (faster while iterating) |
| `--serve [port]` | Serve the result with the same CSP and download types as Caddy (default port 4173) |
| `--check` | See below; exits 1 on any error |

`--check` reads the built site as a crawler and a careful reviewer would: well-formed HTML (tags
balanced and nested, no block inside a paragraph), one `h1` per page, a unique `<title>` and meta
description, the canonical URL, unique ids, `alt`/`width`/`height` on every image, JSON-LD that
parses, no inline script or style (the CSP forbids them), every internal link, image and `#anchor`
resolving, `sitemap.xml` listing exactly the built pages, `robots.txt` pointing at it, every heading of
`INSTALL.md` and `LINUX.md` present on its page, and the Caddyfile's CSP being the one the preview
serves. Links to `/download/…` are noted, not failed: those files live on the server. External
links are counted, not fetched.

## What's where

| Path | What |
| --- | --- |
| `build.mjs` | The build: assets fingerprinted, Markdown rendered, pages, sitemap, robots, `llms.txt`, `llms-full.txt`, precompressed copies |
| `lib/markdown.mjs` | The Markdown renderer (GitHub's heading anchors, lists, tables, code with a copy button; relative repo links go to GitHub, links to the two guides stay on the site) |
| `lib/qr.mjs` | A small QR encoder (the install page's "Get it on your phone"), checked against a decoder |
| `lib/check.mjs`, `lib/serve.mjs` | `--check` and `--serve` |
| `src/templates/` | The pages (`pages.mjs`) and the frame and components (`parts.mjs`) |
| `src/guides/*.md`, `src/faq.md` | The guides and the FAQ: front matter (`title`, `description`, `h1`, `lede`, `shot`, `shotAlt`, `related`), then Markdown; a guide's `## FAQ` with `### Question` blocks also becomes its FAQPage JSON-LD |
| `src/css/site.css` | The look: the app's tokens and components (docs/DESIGN.md) |
| `src/js/` | `boot.js` (marks JavaScript and the device before paint), `site.js` (copy buttons, release details, arrivals), `sky.js` (stars, nebulae, grain and the home's solar system on one clock) |
| `src/img/` | The README's screenshots as WebP (`tools/images.mjs` makes them) and `images.json` |
| `src/static/` | Root files: favicon, touch icon, the 1200×630 social card (`tools/render-static.mjs`) |
| `src/site.json` | Name, URL, version and date (until a `--latest` overrides them), repo, downloads |
| `src/verification.json` | Webmaster-tool verification codes (empty until you claim the site) |
| `src/media-manifest.json` | The film's file names, size and details |
| `deploy/Caddyfile.hypernova` | The Caddy site block |
| `scripts/latest-json.mjs` | Writes `download/latest.json` from the release's files (a reference for the release script) |
| `scripts/indexnow.mjs` | Submits the sitemap's URLs to IndexNow |
| `tools/` | Dev-only renders (screenshots to WebP, the social card, icons) through headless Chrome; they need `puppeteer-core` installed outside the repo (see each file's header) |

## On the server

```
/var/www/hypernova/
  index.html, install/, guides/, faq/, 404.html, assets/, …   ← site/dist (the build)
  media/          hypernova-intro.mp4, hypernova-intro.webm, poster.jpg (when built with --media)
  download/       ← the release puts these here; the build never touches them
    Hypernova.dmg
    Hypernova.apk
    Hypernova-linux.tar.gz
    SHA256SUMS.txt
    latest.json
```

Deploy the build without deleting the downloads:

```sh
node site/build.mjs --check
rsync -av --delete --exclude /download/ site/dist/ <server>:/var/www/hypernova/
```

**`download/latest.json`** is what the install page reads to show the version, the date, each file's
size and its SHA-256 (with a copy button). Without it (or without JavaScript) the buttons still link to
`/download/<file>` and to GitHub, and the page says "latest release". Its format:

```json
{"version":"0.2.0","date":"2026-10-22","files":{
  "Hypernova.dmg":{"size":48213504,"sha256":"…"},
  "Hypernova.apk":{"size":31457280,"sha256":"…"},
  "Hypernova-linux.tar.gz":{"size":2097152,"sha256":"…"},
  "SHA256SUMS.txt":{"size":280,"sha256":"…"}}}
```

The release script writes it after copying the files, for example with
`node site/scripts/latest-json.mjs /var/www/hypernova/download 0.2.0 2026-10-22` (it hashes the files
and writes atomically). Caddy serves it with `Cache-Control: no-cache`, so a new release shows at once.
To print a release's numbers into the pages as well, build with `--latest <path to latest.json>` and
bump `version` and `date` in `src/site.json`.

**Caddy.** `deploy/Caddyfile.hypernova` is the site block: `root /var/www/hypernova`, `encode zstd
gzip`, `file_server` with the build's precompressed `.br`/`.gz`, the security headers and a strict
Content-Security-Policy (every script, style, font and image is a same-origin file; nothing inline,
nothing third-party), a year's immutable cache for the fingerprinted `/assets/`, revalidation for
pages, `latest.json` and the downloads (same names every release), the right types and
`Content-Disposition: attachment` for `.dmg`, `.apk` and `.tar.gz` (byte ranges work, so downloads
resume), and the styled `404.html`. Include it from the main Caddyfile, then
`caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy`.

## The film

The home page shows the intro film only when its files exist. Build with
`--media <dir>`: the build copies `hypernova-intro.mp4`, `hypernova-intro.webm` (optional) and
`poster.jpg` from there into `dist/media/` and turns the film's section on when the mp4 and the poster
are both there. Set the film's `width`, `height` and `duration` (ISO 8601, `PT1M30S`) in
`src/media-manifest.json`: the player keeps that shape while it loads (`preload="none"`, so nothing is
downloaded until play), and the VideoObject JSON-LD uses them.

## Search engines

The pages are static HTML with one `h1`, a unique title and description, canonical URLs, Open Graph and
Twitter cards (`/og.jpg`), JSON-LD (WebSite, SoftwareApplication with a download per platform,
SoftwareSourceCode, Person, BreadcrumbList on inner pages, TechArticle and FAQPage on the guides,
FAQPage on `/faq/`, VideoObject once the film is there), `sitemap.xml` with `lastmod`, `robots.txt`
and `llms.txt`/`llms-full.txt` for AI assistants.

To claim the site and hand over the sitemap (`https://hypernova.nikbraun.tech/sitemap.xml`):

- **Google Search Console** (search.google.com/search-console): *Add property*. The **Domain**
  property is verified by a DNS TXT record on nikbraun.tech and covers every subdomain, so nothing
  changes here. For a **URL prefix** property instead, choose *HTML tag* and put the `content` value in
  `src/verification.json` as `google` (or *HTML file*: its name as `googleFile`), rebuild, deploy,
  verify. Then *Sitemaps* → submit `sitemap.xml`, and *URL inspection* → *Request indexing* for `/`.
- **Bing Webmaster Tools** (bing.com/webmasters): *Import from Google Search Console* is the quickest.
  Otherwise *Add site*, then the meta tag (`bing` in `verification.json`, the `msvalidate.01` value)
  or the XML file (its whole content as `bingXml`, served as `/BingSiteAuth.xml`). Submit the sitemap
  under *Sitemaps*.
- **Yandex Webmaster** (webmaster.yandex.com): *Add site*, then the meta tag (`yandex`) or the HTML
  file (`yandexFile`, like `yandex_1234abcd.html`). Add the sitemap under *Indexing → Sitemap files*.
- **IndexNow** (Bing, Yandex, Seznam, Naver): after each deploy,
  `node site/scripts/indexnow.mjs --dry-run` shows what it will send, and without `--dry-run` it
  submits every URL in the sitemap. The first run makes a key in `site/.cache/indexnow-key`; the
  build then writes `<key>.txt` into `dist/` every time, so deploy once before the first submission.

## Look and rules

Tokens, type and components come from the Android app (`docs/DESIGN.md`, `ui/theme/Tokens.kt`,
`ui/components/Primitives.kt`) and the hyper-cosmos skill: warm near-black, one mint accent, cream text,
Manrope and JetBrains Mono (self-hosted TTFs with their OFL, precompressed), seams, glass cards,
frosted pills and state chips, a sky of stars in real colours with nebulae and film grain, and the
home's solar system with the sun as an accretion disk. Motion is the app's expo ease-out, transform
and opacity only, hover effects only with a mouse; with `prefers-reduced-motion` the sky is one still
frame, and the sky stops while the tab is hidden. Pages read and work without JavaScript: only the
sky, the release details from `latest.json`, the copy buttons and the device hint need it.
No analytics, no CDN, no third-party requests; no personal data beyond the author line.
