# Files that open properly — design spec

Owner: "would be great if we could open not only text files (btw would be great if they would have
proper formatting), but also pictures, videos and audio, and would be able to download it."
DECISIONS, "The owner's second round of notes": **Files open properly: Markdown and code formatted,
pictures, video and audio viewable, and everything downloadable.**

What makes the owner say "how did I live without it":
- Claude says "I wrote it to `docs/PLAN.md`" and **the path is a link**. One tap and it reads like a
  document, with headings, tables, code in colour, and a Contents list. `src/hub.ts:212` opens at line 212.
- A video Claude rendered **plays at once and seeks anywhere instantly**. Audio plays with one tap.
- **Download is one tap**: a ring fills, then a check, and the file is in Downloads/Hypernova. Share
  sends the file itself.
- A dev server's log **follows live**. A CSV is a table, and tapping a row shows it as a card.
- **Files** for a session lists what Claude created and edited there, with thumbnails.

---

## 1. Today (survey)

- **Daemon**: `GET /v1/file?path=` streams the whole file (`services.ts` `sendFile`), capped at 20 MB
  (30 MB for attachments), `cache-control: no-store`, with no Range, HEAD or ETag. MIME comes from a
  small extension map (`mimeOf`), and there's no `video/*` except mp4 and no audio at all.
  `allowedPath` accepts the projects root, any session cwd and attachments, and nothing else, so a
  file Claude read in `~/Downloads` gets 403 when tapped. `GET /v1/files` lists a folder.
  `POST /v1/preview` signs a URL for HTML and dev servers (`/p/<token>/…`, no dotfiles).
- **Phone** (`ui/files/Files.kt`, `FileViewer`): it downloads the whole file into memory (32 MB
  max). Images are decoded with `BitmapFactory`, with pinch and pan but no zoom around the fingers
  and no bounds. Everything else shows as mono lines with a gutter, with no formatting, no colour
  and a 512 KB cap. A binary file shows "can't be shown". It can't play video or audio, can't show
  PDFs (LATER L20) and can't download or share. HTML gets an "Open as page" pill.
- **Chat markdown** (`Transcript.kt` `Markdown`/`blocks`/`markdownLite`): headings, lists, fences,
  tables, rules, `**bold**` and `` `code` ``. It has no links, italics, quotes, task lists or
  highlighting.
- **Tappable today**: `ToolRow` (Read/Write/Edit path), the `DiffCard` header, the "FILES CHANGED"
  strip (computed from the items loaded on the phone, so it's incomplete after `turn=last`) and
  `SentFiles` thumbnails, which fetch the full file. **Paths in Claude's text are never tappable.**
- **Dependencies**: Compose, Haze, OkHttp 4.12 and org.json. There's no Coil, Media3 or Material.
  FileProvider (`${applicationId}.files`) already exists for camera photos. The shipped APK is the
  **debug** build, 35 MB.

## 2. Decisions

1. **One file route, made proper**: `/v1/file` gains Range, HEAD, ETag/304 and If-Range, with no
   size cap. New routes: `/v1/file/meta` (kind detection on the Mac), `/v1/thumb` and
   `/v1/sessions/{id}/files`. There are no new WS messages.
2. **What may be served**: the current roots, attachments, **plus any file the daemon has seen
   referenced in a transcript** (a tool's path, a path Claude wrote in a reply). **Secrets never
   leave the Mac** (§4.1; owner question 1).
3. **Paths in replies become links**: the daemon finds them, checks that they exist and sends them
   as `refs` on the finished `assistant` event. The phone doesn't guess.
4. **One markdown renderer** for chat and files, extended with links, italics, strikethrough,
   quotes, task lists, images, front matter and highlighted fences. It moves to
   `ui/components/Markdown.kt` and gets a lazy API for long documents.
5. **Code highlighting is written in-house**: a small regex lexer (`ui/components/Highlight.kt`, about
   400 lines, no dependency) with line-start states, highlighting each line only when it's on
   screen. TextMate or tree-sitter ports cost megabytes and grammars, and Prism4j is dead.
6. **Video and audio use Media3 ExoPlayer 1.11.1** (`media3-exoplayer` and `media3-datasource-okhttp`
   only), on our OkHttp client with the bearer header. There is **no `media3-ui` or
   `media3-ui-compose`**: ui-compose pulls Compose BOM 2026.05 over ours, and its controls aren't
   ours anyway. A `SurfaceView` in `AndroidView` with our own glass controls.
7. **PDFs use the platform `PdfRenderer`**, which costs nothing in the APK. This closes the PDF half
   of L20.
8. **HTML and SVG open as a page** (the existing preview), and the web screen gets a "Source" button.
9. **Download**: the phone streams into `MediaStore.Downloads` under `Download/Hypernova/`. It needs
   no permission on API 29+ (our minSdk), and retry resumes. **Share** sends the file itself through
   FileProvider. "Open in another app" covers everything we can't show.
10. **Thumbnails are made on the Mac** with tools macOS already has: `sips` for pictures and
    `qlmanage` (Quick Look) for video, PDF and office files. They're cached on disk.
11. **Video full screen turns the phone sideways**, for that view only (owner question 2). Audio
    stops when you leave the viewer, and background playback is LATER (owner question 3).

---

## 3. Protocol (additions to `docs/PROTOCOL.md`)

### REST

| Method & path | Body → Response |
|---|---|
| `GET` / `HEAD /v1/file?path=<abs>[&download=1]` | → the bytes, any size. `Range: bytes=a-b \| a- \| -n` (one range) → 206 + `Content-Range`, while a multi-range request gets 200 whole and a range past the end gets 416 `bad_range` with `Content-Range: bytes */<size>`. Headers: `Content-Type` (from detection), `Content-Length`, `Accept-Ranges: bytes`, `ETag: "<size>-<mtimeMs>-<ino>"`, `Last-Modified`, `Cache-Control: private, no-cache`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: sandbox`, and `Content-Disposition: inline` (`attachment` with `download=1`) plus `filename*=UTF-8''<name>`. `If-None-Match` → 304. `If-Range` (an ETag) that doesn't match → 200 whole. A directory serves its `index.html` (as today) |
| `GET /v1/file/meta?path=<abs>` | → `{file: FileMeta}` |
| `GET /v1/thumb?path=<abs>&px=256` | → `image/jpeg` (pictures) or `image/png` (Quick Look). `px` snaps up to 128/256/512/1024, and the long side is at most `px`. `Cache-Control: private, max-age=86400` and an ETag. 404 `no_thumb`, 503 `busy` |
| `GET /v1/sessions/{id}/files` | → `{files: SessionFile[]}`, newest `at` first, 300 at most. 404 `no_session` |

Errors on every file route: 400 `bad_path` (not absolute), 403 `forbidden` ("That file is outside
your projects.", as today), 403 `secret` ("This looks like a secrets file, so it stays on the Mac."),
404 `file_gone` ("This file is no longer on the Mac."), 404 `attachment_gone` (as today). The old
"Unknown endpoint." 404 `not_found` tells a new phone it's talking to an old daemon (§5.9).

`POST /v1/preview {path}` and `GET /v1/files` apply the same policy (§4.1). `/p/<token>/…` gains Range
too, because it uses the same sender.

CORS (phone port, `/v1/*`): `Access-Control-Allow-Headers` adds `Range, If-None-Match, If-Range`, and
the new `Access-Control-Expose-Headers` is `Content-Range, Accept-Ranges, Content-Length, ETag,
Content-Disposition`.

### Types

```ts
FileKind = "markdown" | "code" | "json" | "csv" | "log" | "text" | "html" | "svg"
         | "image" | "video" | "audio" | "pdf" | "dir" | "other"      // clients: unknown → "other"

FileMeta = { path: string,          // the real path (symlinks followed)
             name: string, size: number, mtime: number,   // epoch ms
             mime: string,          // "application/octet-stream" when unknown
             kind: FileKind,
             lang: string | null,   // kind "code" (and json/html/svg sources): "kotlin", "typescript", … (§4.2)
             text: boolean,         // valid UTF-8 (or a UTF-16 BOM) with no NUL in the first 8 KB
             etag: string,
             thumb: boolean }       // GET /v1/thumb can draw it

SessionFile = { path, name,
                how: "created" | "edited" | "attached" | "mentioned",  // strongest wins, in that order
                at: number,             // the last time the session touched or mentioned it
                added: number, removed: number, edits: number,   // lines over all its edits; 0 otherwise
                agent: string | null,   // the agent's label when only an agent touched it
                exists: boolean,        // false: deleted or moved since
                size: number | null, mtime: number | null, kind: FileKind | null, thumb: boolean }

Ref = { text: string,         // the exact substring of the reply ("src/hub.ts:212", "~/out/demo.mp4")
        path: string,         // real absolute path
        line: number | null,  // from ":212", ":212:5" or "#L212"
        dir: boolean }        // a folder: opens the folder browser

FileEntry += { kind: FileKind | null, secret: boolean }
Event "assistant" += { refs?: Ref[] }   // only once streaming is false; at most 40
```

WebSocket: nothing new. `event` messages carry `refs`. Lens and older phones ignore the new fields.

---

## 4. Daemon

New `daemon/src/files.ts` (policy, detection, sending, refs) and `daemon/src/thumbs.ts`. `services.ts`
keeps `listDir`, `createPreview` and `servePreview` and calls into them.

### 4.1 Access policy: `servable(p) → {real} | {error}`

1. `p` must be absolute. Take `realpath` (following symlinks), and check the **real** path and the
   requested path alike.
2. **Secrets (403 `secret`)**: the basename is `.env` or `.env.*` (except `.example`, `.sample`,
   `.template` and `.dist`), `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.keystore`, `*.jks`, `*.kdbx`, `id_rsa`,
   `id_dsa`, `id_ecdsa` or `id_ed25519` (not `.pub`), `.netrc`, `.npmrc`, `.pypirc`, `.git-credentials`,
   `credentials.json` or `.credentials.json`. Or a path segment is `.ssh`, `.gnupg`, `.aws`, `.kube`
   or `.git`. Or the file is inside `<support>` but outside `<support>/attachments`, or inside
   `~/Library/Keychains` or `~/.config/gh`.
3. **Allowed**: inside a root (the projects root, or any session cwd other than `$HOME` or `/`, as
   today), inside `<support>/attachments`, or **in the referenced index**.
4. Anything else is 403 `forbidden`.

**Referenced index** (a `Set<realPath>` in `files.ts`, filled from `hub.ts` and `agents.ts` as
histories are read): every tool event's `path` (Read, Write, Edit, MultiEdit, NotebookEdit; Read
counts here, so the ToolRows that get 403 today open), every `Ref.path`, and every attachment. It
lives in memory and is rebuilt as transcripts are read again. A session's history is read before its
events reach the phone, so anything visible there is already indexed.

Previews: a previewed file **outside the roots** serves only itself. Its siblings are served only
if they pass `servable`, so a `/tmp/report.html` never exposes the rest of `/tmp`. Secrets get 404
in previews too.

### 4.2 Detection: `detect(real) → FileMeta`

- **By name first**:
  - markdown: md, markdown, mdx.
  - json: json, jsonc, jsonl, ndjson, geojson, webmanifest.
  - csv: csv, tsv.
  - log: log, out, err, `*.log.N`, `nohup.out`, `…/tasks/*.output`.
  - html: html, htm.
  - svg: svg.
  - image: png, jpg, jpeg, gif, webp, bmp, heic, heif, avif, ico, tif, tiff.
  - video: mp4, m4v, mov, webm, mkv, 3gp, avi.
  - audio: mp3, m4a, aac, wav, flac, ogg, oga, opus, amr.
  - pdf: pdf.
  - text: txt, and extensionless names such as `LICENSE`.
  - code: the existing `TEXT_EXT` plus h, hpp, cc, m, mm, cs, dart, scala, php, lua, fish, groovy,
    scss, less, astro, plist, ini, cfg, conf, properties, and by name `Dockerfile`, `Makefile`,
    `Gemfile`, `Podfile` and `*.gradle`. `.ts` is always TypeScript.
- **Then by content** (the first 64 bytes) for anything with no extension or one it doesn't know:
  - `%PDF-` is a pdf.
  - The PNG signature, `FF D8 FF`, `GIF8` and `RIFF…WEBP` are images.
  - `RIFF…WAVE`, `ID3`, an MPEG frame sync, `OggS` and `fLaC` are audio.
  - `1A 45 DF A3` is video.
  - `ftyp` at offset 4 depends on the brand: `M4A `/`M4B ` is audio, `heic`/`heix`/`mif1` is an
    image, and anything else is video.
  - Otherwise, `text` if it's UTF-8 with no NUL; then json if it starts with `{` or `[`.
  - Anything left is `other`.
- A "code" file whose content isn't text becomes `other`. **The MIME map is extended** to match every
  kind above (`video/quicktime`, `audio/mpeg`, `audio/mp4`, `audio/flac`, `audio/ogg`, `video/webm`,
  `video/x-matroska`, `image/heic` and so on). Text types get `; charset=utf-8`, or `utf-16le`/`be`
  from a BOM.
- `lang` comes from the extension or name, using these ids: kotlin, java, swift, typescript,
  javascript, python, ruby, go, rust, c, cpp, objc, csharp, dart, scala, php, lua, shell, sql, json,
  yaml, toml, ini, xml (also html, svg, vue, svelte, astro, plist), css, markdown, diff, dockerfile,
  makefile, groovy.
- `thumb: true` for image, video, pdf, svg, and docx/xlsx/pptx/pages/numbers/key/rtf. Never for
  html, which Quick Look would render with WebKit.
- Reads at most 8 KB, and caches the result by `etag` (LRU of 2,000). `GET /v1/files` listings use
  the name rules only, so a folder of 500 files reads none of them.

### 4.3 Sending

`serveFile(req, res, real, {download})` replaces `sendFile`. It parses one range strictly (integers,
`a ≤ b`, the end clamped to `size-1`) and streams with `createReadStream({start, end})`.
`If-None-Match` is checked before the range. There's no cap: the phone asks only for what it needs.
It logs downloads only (`download=1`: the name and size, never the content). It never logs ranges,
since a player makes dozens.

### 4.4 Thumbnails (`thumbs.ts`)

- **Pictures**: `/usr/bin/sips -Z <px> -s format jpeg -s formatOptions 80 <src> --out <tmp>.jpg`
  (handles HEIC, TIFF and RAW).
- **Video, PDF, SVG and office files**: `/usr/bin/qlmanage -t -s <px> -o <tmpdir> <src>`, which gives
  `<tmpdir>/<basename>.png`.
- Both are spawned with an argument array (no shell; paths are absolute so they can't look like
  flags), with a 10 s timeout and 2 at a time. Up to 32 wait, and past that it's 503 `busy`.
- Cached in `<support>/thumbs/` (0700) as `sha256(real + etag + px)`. The cache is cut to 200 MB by
  access time, and files older than 30 days go at start-up. A failure is remembered for 10 minutes
  (404 `no_thumb`), so a broken file doesn't spawn a process for every row.

### 4.5 Refs (paths in replies)

`findRefs(text, cwd, known, exists)` in `files.ts` is pure, with `exists` injected for tests. It runs
in `normalize.ts` when an assistant event is finished, for SDK sessions and transcripts alike, and
for agents' transcripts.

- **Candidates**:
  - the content of inline code spans;
  - markdown link targets `[x](target)`;
  - bare tokens that start with `/`, `~/` or `./`, or that contain `/` and end in `.ext`, or that are
    a bare `name.ext` matching a file this session touched (`known`: basename → paths, used only
    when exactly one path matches).
- **Cleanup**: strip trailing `.,;:)]'"` and pull a line out of `:N`, `:N:M` or `#LN`. Skip anything
  in a fenced code block and anything with a scheme (`http:`). Skip text over 64 KB.
- **Resolve**: `~` is `$HOME`, relative paths are against the session cwd, then `realpath`. Keep a
  candidate only if it exists and isn't a secret.
- **Limits**: 40 refs per message, and the stats are cached for 60 s (2,000 entries). Refs are kept
  on the event, so they aren't recomputed when it's sent again.

### 4.6 Session files

`hub.ts` keeps `Rec.files: Map<real, SessionFile>`, updated where events are added (`readNew` and
`putEvent`):

- **Edits**: a finished, successful Write is `created` when its result says `type: "create"`, and
  otherwise `edited`. Edit, MultiEdit and NotebookEdit are `edited`. Each adds its diff's
  `added`/`removed` and `edits += 1`.
- **Other sources**: the `files` on user events are `attached`, and the refs are `mentioned`.
- **Agents**: `agents.ts` feeds its agents' edits into the parent session, with `agent` set to the
  label (cleared once the main agent touches the file).
- **Read** alone doesn't list a file.

`GET /v1/sessions/{id}/files` calls `ensureParsed` first, then stats each file (`exists`, `size`,
`mtime`), with `kind` and `thumb` taken from `detect`.

---

## 5. Phone

### 5.1 Finding a file

- **In a reply**: every `Ref` is a link. In an inline code span it's the usual mono on its mint tint,
  plus an underline. In prose it's `accent` with an underline. A tap calls
  `opener.fileAt(path, line)`, or the folder browser for `dir`. Markdown links `[x](rel/path#L4)`
  resolve against the session cwd. `http(s)`/`mailto` open the browser. Long-press selection still
  works (`LinkAnnotation.Clickable` inside `SelectionContainer`).
- **Tool rows, diff cards and attachments**: as today. Rows outside the roots now open, because of
  the referenced index.
- **Session header**: the Folder glyph opens **Files** for the session: `Segmented` "This session" /
  "Folder", with "This session" first (the old browser is the "Folder" tab).
- **The FILES CHANGED strip** is now fed by `GET /v1/sessions/{id}/files` (created and edited),
  merged with the live items so a new edit shows at once. It refetches 1 s after an edit tool
  finishes.
- **Attachments** (`SentFiles`) use `/v1/thumb?px=256` instead of downloading the whole file. PDFs
  and videos get thumbnails too.

```
 ·        ·                 ·           ·
 ‹  Files                                       
    hypernova · 7 this session                   
   ╭──────────────────────╮╭─────────────────╮  
   │     This session     ││     Folder      │  
   ╰──────────────────────╯╰─────────────────╯  
 ◉ CREATED ───────────────────────────────── 2   
  ┌────┐ spec-files.md                           
  │ ≡  │ docs · +214 · 2m                       
  └────┘                                          
  ┌────┐ demo.mp4                           ◔    
  │▶░░░│ out · 14 MB · 5m                        
  └────┘                                          
 ◉ EDITED ────────────────────────────────── 4   
  ┌────┐ Files.kt                                
  │ {} │ ui/files · +88 −31 · 3 edits · 6m       
  └────┘                                          
  ┌────┐ hub.ts                    by survey:agents
  │ {} │ src · +12 −2 · 1m                       
 ◉ YOU SENT ──────────────────────────────── 1   
 ◉ MENTIONED ─────────────────────────────── 0   
```

- **Rows**: 64dp, a 44dp thumbnail (radius 8, ink-8% ring; a kind glyph on bg800 when there's no
  thumb, and a ▶ badge on videos), the name in body ink with `MiddleEllipsis`, and a mono dim line
  (the folder relative to cwd · size or `+a −r` · `n edits` when more than 1 · age). `agent` sits in
  mono dim at the right. A file that no longer exists has a ghosted name and the trail "gone". An
  empty section is hidden. A running download draws its ring at the right.
- **Folder tab**: today's browser. Rows get a thumbnail when `thumb`. A `secret` row is dim, with
  "stays on the Mac" in place of its size and no tap.

### 5.2 Viewer chrome (all kinds)

```
 ‹  PROTOCOL.md                          ⇪    ⤓   ← GlyphButtons: Share, Download
    ~/Programming/hypernova/docs · 41 KB          ← mono dim, MiddleEllipsis
   ╭──────────────╮╭──────────────╮  ☰ Contents   ← `below`: Segmented + chip (per kind)
   │  Formatted   ││    Source    │
   ╰──────────────╯╰──────────────╯
 ░░░░░░░░░ progressive blur over content ░░░░░░░░░
```

- `ScreenScaffold` as today: Haze progressive blur, keyed source `file:<path>`. The sky drops to its
  session-screen dim and its labels hide (as on placeholder screens), so nothing reads through
  the text.
- **Download glyph states**:
  - idle: `Download`, ink;
  - running: a 20dp ring (inkGhost track, accent arc, the percentage for TalkBack). A tap stops it.
  - done: `Check` in accent. A tap opens "Open in another app" on the saved copy.
  - failed: `Download` in danger, plus a toast.
- **Share**: the same ring while it fetches into the cache, then the system share sheet.
- **Mode** (`Segmented`, remembered per kind in SharedPreferences):
  - Markdown: Formatted / Source.
  - CSV: Table / Text.
  - JSON: Pretty / Raw.
  - Log: a `Follow` StatusChip instead (§5.4).
- **Reading plates**: code, JSON, CSV and log draw on a full-bleed plate (bg950 at 86%, no radius,
  hairline at the top) that starts under the bar. Markdown reads on the dimmed sky, as a reply does.
  Images, video and PDF sit on a bg950 92% stage.
- **Kept up to date**: `LiveHub` exposes `fileTouched: SharedFlow<String>` (paths of edit tools that
  finished). When the open file is touched, or the app comes back to the front, the viewer checks
  its `etag` again:
  - text kinds reload in place, keeping the first visible line;
  - media and PDF show a chip, "Changed on the Mac · Reload".

### 5.3 Markdown

`ui/components/Markdown.kt` exposes:

- `parseMarkdown(text, document = false, streaming = false): MdDoc(blocks, headings)`;
- `@Composable MdBlockView(block, ctx)`, used as LazyColumn items in the viewer;
- `Markdown(text, …, ctx)`, which keeps the chat's Column, as today.

`MdContext(baseDir, refs, onLink, image)`. What it handles:

- **Inline**: `**b**`, `*i*`/`_i_`, `~~s~~`, `` `c` ``, `[t](u)`, `<https://…>` and bare `https://` URLs.
- **Blocks**:
  - headings: h1 `Type.title` 600, h2 `Type.heading` 600 with 6dp above, h3–h6 body 600;
  - lists, nested by indent as today;
  - task lists: a 14dp box ring with `Check` in accent when done;
  - blockquotes: a 2dp accentDim bar and muted text;
  - fences highlighted by `lang`;
  - tables (`MdTable`, unchanged) and rules;
  - `![alt](rel.png)` on its own line: the picture via `hub.thumb(px=1024)`, full width, and a tap
    opens it;
  - remote images aren't fetched: they show as an alt link to the browser;
  - `<!-- -->` is hidden, and `<br>` is a line break;
  - with `document = true`, a leading `---` YAML block is front matter: a dim mono card.
- **Links**: `#anchor` scrolls to the heading (GitHub slugs). A relative path resolves against the
  document's folder.
- **Contents**: the chip shows when there are more than 5 headings. It opens a glass sheet of
  headings, indented by level, and a tap scrolls there.
- **Source mode** is the code view with `lang = markdown`.

```
 ‹  PLAN.md                               ⇪    ⤓
    ~/Programming/0stems · 12 KB
   ( Formatted )( Source )        ☰ Contents
 
  Milestone 3                                     ← title 22
  The sky, second pass                            ← heading 16
  Ship the black hole first, then the reset.
  ▏ The owner: "in reality, black hole doesn't    ← quote bar accentDim
  ▏ turn back into a star"
  ☑ Supernova collapses                           ← task, Check accent
  ☐ Hawking flash
  ╭─────────────────────────────────────────╮
  │ kotlin                                   │   ← fence: highlighted
  │ val hole = Remnant(core, cooldown)       │
  ╰─────────────────────────────────────────╯
  See `src/fx/Solar.kt:212` for the timeline.     ← ref: tappable
```

### 5.4 Code, text, JSON, CSV, log

- **Fetch**: `hub.fileText` asks for the first 2 MB (`Range: bytes=0-2097151`), or the **last** 2 MB
  for logs (`bytes=-2097152`). A partial file ends (or for logs, starts) with a dim note.
- **Code**: a LazyColumn of lines, with the gutter (inkGhost) and the text both in `Type.monoSmall`. It
  **always wraps**, with continuation lines indented under the gutter (sideways scrolling across a lazy list
  is fiddly, and phone screens are narrow). Lines over 2,000 characters are cut. `fileAt(path, line)`
  scrolls to `line − 3` and tints that line accent 8% for 2 s (static under Reduce Motion).
  Long-press selects text. There's no Copy all (Share covers it).
- **Highlighter** (`Highlight.prepare(text, lang)` off the main thread):
  - One pass records each line's start state: block comment, triple-quoted string, template
    string, or inside a markup tag.
  - `prepared.line(i)` builds that line's `AnnotatedString` when it's composed. Rules are
    per-family regex lists tried with `lookingAt`:
    - C-like (kotlin, java, swift, ts/js, go, rust, c/cpp/objc, csharp, dart, scala, php, groovy);
    - hash-comment (python, ruby, shell, yaml, toml, ini, dockerfile, makefile);
    - sql, json, markup (xml/html/svg/vue/svelte), css, markdown and diff.
  - Unknown languages are plain.
- **Code palette**: new `HN.code*` tokens, so components never use raw colours.

  | token | value | used for |
  |---|---|---|
  | codeKeyword | accent `#7FFFA0` | keywords, markup tags |
  | codeString | `#D6C8A4` | strings, attribute values. A warm sand far less saturated than amber, so it never reads as "needs you" |
  | codeNumber | accentBright `#B6FFC9` | numbers, constants, `true`/`null` |
  | codeComment | inkDim `#7A7468`, italic | comments |
  | codeType | ink `#F5F2EB` | Capitalised identifiers, JSON/YAML keys |
  | codeAttr | accentDim `#4A8C5E` | annotations, decorators, attribute names |
  | codePunct | inkDim | operators, brackets |
  | codePlain | inkMuted `#B5B0A6` | everything else |
  | diff +/− | the DiffCard tints | patch lines |

- **Text** (`.txt`, `text`): Manrope body, wrapped, with no gutter.
- **JSON**:
  - Pretty mode re-indents with a string-aware streaming indenter that keeps key order and number
    text exactly (org.json would reorder). It does that only when a line is over 300 characters;
    readable files are shown as written.
  - JSONL shows each record pretty, with hairlines between them, for the first 1,000 records.
  - Invalid JSON is shown as is.
- **CSV/TSV**:
  - Parsing: RFC 4180 quotes. The delimiter (`,` `;` tab `|`) is the most frequent in the first
    line, outside quotes.
  - Layout: the first row is a sticky header in tracked labels. Column widths come from the first 200
    rows (3–28 mono characters), and cells are cut with an ellipsis. Number columns are right-aligned.
    Row numbers sit in inkGhost. The whole grid scrolls sideways (a `horizontalScroll` Box around a
    fixed-width LazyColumn).
  - **Tapping a row** opens a glass sheet, "Row 214", with each `header` in a tracked label over its
    value in body.
  - It stops at 5,000 rows.
- **Log**:
  - Opens **at the end**. Lines are toned the way `CommandOutputRow` tones them: error, fatal,
    exception and panic in danger; warn in amber; passed, done and ✓ in accent; a leading timestamp
    in inkDim.
  - **Follow**: on by default when `mtime` is within the last 2 minutes. While the screen is visible
    it asks every 2 s (5 s on battery saver) with `If-None-Match` + `Range: bytes=<size>-`:
    - 304 means nothing new.
    - 206 is appended, and the view sticks to the bottom only if you were already there.
    - 416 with a smaller size means the log rotated, so it reloads the tail.
  - Follow stops after 10 minutes with no growth (the chip goes still). It's a StatusChip that
    pulses while following, and a tap toggles it.

### 5.5 Pictures

- **Fetch**: `hub.file(path)` (whole bytes, up to 64 MB; past that, "too big to show here" and
  Download).
- **Decode**: `ImageDecoder`, off the main thread, with a target size so the long side is at most
  4,096 and the picture at most 16 MP, as a hardware bitmap. That covers PNG, JPEG, WebP, GIF, HEIC
  and BMP, plus AVIF on API 31+.
- **Animated GIF/WebP** use `AnimatedImageDrawable` through a 40-line `DrawablePainter`. Under Reduce
  Motion they show the first frame with a Play chip.
- **Zoom**: pinch around the fingers' centroid (1–8×). The pan is clamped so the picture's edge never
  leaves the screen's edge. A double-tap zooms 2.5× at that point and back to fit.
- **Chrome**: a single tap hides and shows the chrome (immersive).
- **Info line** at the foot, mono dim: `1920 × 1080 · PNG · 2.4 MB`. The first time ever it says
  "Pinch to zoom" instead.

### 5.6 Video and audio (`data/Playback.kt`)

**Playback setup**:
- **One player for the app**: `ExoPlayer` built on `OkHttpDataSource.Factory(Net.client)` with
  `Authorization: Bearer`, `setHandleAudioBecomingNoisy(true)` and audio focus handled.
- **Created and released with the viewer**: it's built when a viewer opens and released when it
  leaves; it pauses on `ON_STOP`.
- **Resume position**: positions are remembered per path for the process's life and resumed when
  over 10 s and before the last 5 s.
- **Opening plays at once**: you tapped it to watch it.

**Video**:

```
 ‹  demo.mp4                               ⇪    ⤓
    ~/Programming/0stems/out · 14 MB
 ┌─────────────────────────────────────────────┐
 │                                             │
 │              ( video, aspect fit )          │   ← SurfaceView, bg950 stage
 │                                             │
 └─────────────────────────────────────────────┘
   ╭───────────────────────────────────────────╮
   │  ❚❚   0:42 ━━━━━━━━●─────────── 3:15   ⤢  │    ← glass dock (GlassCard 86%, r16)
   ╰───────────────────────────────────────────╯
```

- **Dock**: Play/Pause, the elapsed time in mono, a scrub bar (a 2dp hairline track in inkGhost, the
  buffered part in inkDim, the played part in accent, and a 12dp thumb that grows to 16dp while
  dragging, with the time over it), the duration, and Full screen.
- **Hiding**: while playing, the chrome and the dock fade after 3 s. A tap on the picture shows
  them. A double-tap on the left or right third seeks −10 or +10 s, with a ghost "−10 s" label.
- **Poster**: before the first frame, the `/v1/thumb?px=1024` picture with a turning `Planet` over
  it.
- **Full screen**: `requestedOrientation = SENSOR_LANDSCAPE`, the system bars hidden, and the
  dock only. Back or the Collapse glyph restores portrait. The screen stays on while playing.
- The video sits on a SurfaceView (a hardware overlay, cheapest on battery), so nothing Haze-blurs
  over it. The dock is opaque glass, not a blur.

**Audio**:

```
 ‹  theme.m4a                              ⇪    ⤓
    ~/Music/demos · 3.1 MB

                     ◉                    ← Planet, 96dp: turning while it plays
                 theme.m4a                ← title 22
                AAC · 2:48                ← mono dim

   0:42 ━━━━━━━━━●────────────────── 2:48
            ⟲10        ❚❚        10⟳      ← 56dp primary Pill for play/pause
```

- **Errors**:
  - Unsupported codec (ProRes, some AVI): "This phone can't play this video." with Download and
    "Open in another app".
  - Network: "Lost the Mac mid-way." with "Try again", which resumes from the position.

### 5.7 PDF

- **Getting the file**: `Downloads.local()` puts it in the cache, with the ring on a placeholder page.
  It's capped at 200 MB: "This PDF is too big to show here (240 MB)." plus Download.
- **Rendering**: `PdfRenderer` on a single-thread dispatcher. Pages render at the screen width × the
  zoom, at most 4,096 px wide, into an LRU of about 48 MB. Each page sits on the dark stage as paper:
  white, radius 4, an ink-8% ring and 8dp gaps.
- **Zoom**: pinch zooms the column 1–4× through `graphicsLayer`, and the visible pages render again
  at the new scale 250 ms after it settles.
- **Page chip**: a glass chip at the bottom right shows `3 / 12` in mono (TalkBack: "Page 3 of 12").
- **Errors**: "This PDF is locked with a password." or "Can't open this PDF.", each with Download.

### 5.8 Download, Share, Open in another app (`data/Downloads.kt`)

- **Download**: `MediaStore.Downloads`, `RELATIVE_PATH = Download/Hypernova/`, `IS_PENDING = 1`
  while writing.
  - **Streaming**: it streams `GET /v1/file?download=1` through OkHttp in 64 KB copies. Progress
    goes out at most 10 times a second as `StateFlow<DlState>` per path.
  - **Finishing**: on success `IS_PENDING = 0`, and the toast "Saved to Downloads/Hypernova". On a
    stop, the pending row is deleted.
  - **On a failure** the pending row is kept, and Retry asks for `Range: bytes=<written>-` with
    `If-Range: <etag>`. A 200 means the file changed, so it truncates and starts over.
  - Name clashes get MediaStore's own "name (1).ext". Two downloads run at once, and the rest queue
    at 0%.
  - **Lifetime**: downloads run in the app scope, so leaving the viewer doesn't stop them. With
    "Stay connected in the background" on, the foreground service keeps them alive, and otherwise
    a killed process leaves a pending row that's cleaned up at the next start.
  - The phone remembers `path + etag → uri`, so a file already saved shows the check.
- **Local copy** (Share, PDF, Open in another app): `cacheDir/files/<etag>/<name>`, reused while the
  etag matches. Cleared after 24 h, or past 500 MB. `file_paths.xml` adds
  `<cache-path name="files" path="files/"/>`.
- **Share**: `ACTION_SEND` with the FileProvider URI, `type = mime` and the read grant, through a
  chooser titled with the file name.
- **Open in another app**: `ACTION_VIEW` on the saved URI, or the cache copy, through a chooser.
  It's offered on the done check and on the "other" card.

### 5.9 Other files, and an older Mac

**"Other"** (zip, docx and so on) shows a centred glass card: the `/v1/thumb?px=512` picture or a
kind glyph, the name, the size and the mime in mono, then "Hypernova can't show this kind of file.",
then Download (primary) and "Open in another app" (outline).

**An older daemon** (404 `not_found` from `/v1/file/meta`): kinds come from the extension, the
file is fetched whole as today, and video, audio and PDF say "Update Hypernova on your Mac to open
this here."

### 5.10 Copy (new strings; `strings_files.xml`, `strings_markdown.xml`, `strings_downloads.xml`)

| id | text |
|---|---|
| files_tab_session / files_tab_folder | This session / Folder |
| files_subtitle | %1$s · %2$d this session |
| files_created / edited / sent / mentioned | CREATED / EDITED / YOU SENT / MENTIONED |
| files_edits | %d edits |
| files_gone | gone |
| files_secret | stays on the Mac |
| files_empty | Files Claude creates or edits in this session show up here. |
| view_formatted / source / table / text / pretty / raw | Formatted / Source / Table / Text / Pretty / Raw |
| view_contents | Contents |
| view_follow / view_following | Follow / Following |
| view_first / view_last | Showing the first %1$s of %2$s. / Showing the last %1$s of %2$s. |
| view_rows_cut | Showing the first 5,000 rows. |
| view_line_cut | Long lines are cut at 2,000 characters. |
| view_row | Row %d |
| view_changed / view_reload | Changed on the Mac / Reload |
| view_zoom_hint | Pinch to zoom |
| view_other | Hypernova can't show this kind of file. |
| view_open_with | Open in another app |
| view_too_big | This file is too big to show here (%s). |
| view_image_cant | Can't show this picture. |
| view_video_cant / view_audio_cant | This phone can't play this video. / This phone can't play this file. |
| view_lost | Lost the Mac mid-way. |
| view_try_again | Try again |
| pdf_locked / pdf_cant | This PDF is locked with a password. / Can't open this PDF. |
| pdf_page / pdf_page_a11y | %1$d / %2$d / Page %1$d of %2$d |
| view_update_mac | Update Hypernova on your Mac to open this here. |
| a11y: dl / dl_running / dl_done / share | Download %1$s, %2$s / Downloading, %d%%. Tap to stop. / Saved. Tap to open. / Share |
| a11y: play / pause / back10 / fwd10 / full / exit_full | Play / Pause / Back 10 seconds / Forward 10 seconds / Full screen / Exit full screen |
| dl_saved / dl_stopped / dl_failed / dl_space | Saved to Downloads/Hypernova / Download stopped. / Couldn't download it. Try again. / Not enough space on the phone. |
| md_open_link (a11y) | Open %s |

Server messages (`secret`, `forbidden`, `file_gone`) are shown as the daemon sends them.

---

## 6. Mac and glasses

- **Mac app**: no change. Its popover shows no transcripts, and the files are local there anyway.
- **Lens**: no change. The new fields are additive and ignored. A picture Claude made could show on
  the lens the way "Show me" does; that's proposed as a LATER row, not built.

## 7. Edge cases

- Deleted or moved since: 404 `file_gone`. The viewer shows the message over the dim path, and the
  Files row is ghosted ("gone").
- Edited while open: §5.2.
- A path with spaces, Unicode or `#` is query-encoded. `filename*` follows RFC 5987.
- Not UTF-8: a UTF-16 BOM is decoded. Otherwise it's U+FFFD replacement, and NUL bytes make it
  `other`.
- A single 3 MB line of minified JS: the 2 MB fetch, one line cut at 2,000 characters, and both notes.
- A ref to a file deleted later: the link stays, and the tap shows "no longer on the Mac".
- The same text appearing twice in a reply: every occurrence is linked.
- Demo mode: file routes fail as today ("Pair with your Mac…"). Links and Files are hidden, as tool
  rows are now.
- Offline or a dropped connection: the existing error copy and Try again. Already-saved downloads
  still open.
- Disk full on the phone: `dl_space`.
- A big download on mobile data: the size is always in the subtitle and the TalkBack label, and
  there's no extra confirm (the owner shares mobile data with the Mac anyway).
- Two viewers stacked (a link from markdown to another file): each has its own state. The player is
  released when its viewer is popped.
- A symlink inside a project pointing out: the real path is checked, so it serves only if that path
  is allowed itself.
- Thumbnails: Quick Look can't draw everything (no_thumb). The row shows its kind glyph, and the
  failure is cached on both sides.

## 8. Safety

- Every route needs the device token, as today. **No token in URLs** (ExoPlayer sends the header),
  so tokens never land in logs, the MediaStore or another app.
- The policy (§4.1) runs on the real path after symlinks. Secrets are never served, previewed or
  downloaded. A file outside the roots is servable only because a transcript named it. Previews of
  such a file don't expose its siblings.
- Responses carry `Content-Security-Policy: sandbox` and `nosniff`, so a served HTML or SVG is
  inert if a browser ever loads it raw.
- Thumbnail tools run with no shell, a timeout and a concurrency cap. Quick Look generators are
  sandboxed by macOS.
- Share and Open grant read access to one URI per intent (`FLAG_GRANT_READ_URI_PERMISSION`).
  Downloads go only where the user asked (public Downloads/Hypernova).
- The daemon logs downloads (name and size), never content or ranges.

## 9. Performance, battery, APK

- **Targets** (Nothing Phone 3 over the tailnet):
  - meta under 150 ms;
  - a 100 KB markdown file painted within 400 ms;
  - a 3 MB photo within 700 ms;
  - a video's first frame within 1.5 s, and a seek within 500 ms;
  - scrolling a 20,000-line file at full frame rate with no dropped frames from highlighting.
- **Nothing polls** except log Follow (only while visible, 2 s, or 5 s on battery saver, stopping
  after 10 idle minutes).
- **The player** is a SurfaceView, released when the viewer leaves, never running in the
  background, and keeps the screen on only while playing.
- **Memory**: text at most 2 MB; pictures at most 16 MP, as hardware bitmaps; PDF pages in an LRU of
  48 MB; thumbnails in memory (LRU of 60) and in a separate `Net.thumbs` OkHttp client with a 32 MB
  disk cache (their responses are cacheable; JSON stays `no-store`).
- **Daemon**: detection reads 8 KB, cached by etag. Ref stats are cached for 60 s. Thumbnails run 2
  at a time and are cached on disk. Files are streamed and never buffered.
- **APK**: Media3 1.11.1 (`exoplayer`, `common`, `extractor`, `container`, `decoder`, `datasource`,
  `database`, `datasource-okhttp`: about 3.6 MB of AARs) brings Guava 33.3.1-android with it. Expect
  **about +4 MB on the shipped debug APK (35 → about 39 MB)** and about +1.5 MB on an R8 release.
  The budget is +5 MB debug; check it with `apkanalyzer` before merging. Highlighting, PDF, CSV and
  JSON add no dependency.

## 10. Test plan

**Daemon** (`node --test`, fixtures in `daemon/test/fixtures/files/`):

- `files.test.ts`:
  - policy: roots, attachments, a referenced file outside the roots, unreferenced 403, every secret
    rule (with `.env.example` allowed), a symlink escaping a project, `.git/`;
  - Range: 0-, a-b, -n, past the end → 416, multi-range → 200, HEAD, If-None-Match → 304, a
    matching and a stale If-Range;
  - `detect` for each kind by name and by magic (an extensionless PNG, MP4 brands, an ID3 MP3,
    UTF-16 with a BOM, a binary `.ts`);
  - the CORS expose headers;
  - previews of a file outside the roots not serving its siblings.
- `normalize.test.ts`, refs:
  - inline code, a link target, bare absolute and relative paths, a bare basename that's unique among
    the known files (and an ambiguous one dropped);
  - `:N:M` and `#LN`, trailing punctuation;
  - a fenced block ignored, a missing file dropped, a secret dropped;
  - at most 40, and none while streaming.
- `http.test.ts`: `/v1/sessions/{id}/files` covering created vs edited, attached, mentioned, the
  strongest-wins merge, agent attribution, `exists:false` after deletion, ordering and 404.
- `thumbs.test.ts`: the cache key, failures remembered, the concurrency cap → 503, with the spawner
  stubbed. On darwin, a real `sips` and `qlmanage` run on a PNG and an MP4 fixture, skipped elsewhere.

**Android unit tests** (new `app/src/test`, junit4): Highlight (golden token runs for Kotlin, TS,
Python, JSON, YAML and HTML, block comments across lines), `parseMarkdown` (tasks, quotes, links,
images, front matter, anchors, the streaming table), the CSV parser (quotes, delimiters, CRLF),
the JSON indenter (order, strings holding braces, numbers kept), and the Range/resume decision logic
in Downloads.

**On the phone** (new "Files" section in `qa-checklist.ts`):

| id | check |
|---|---|
| fl-link | Ask Claude to write a short `NOTES.md` and mention it, then tap the path in its reply: it opens formatted. A `file.ts:20` mention opens at line 20. |
| fl-markdown | Open `docs/PROTOCOL.md`: headings, tables, code in colour, and Contents jumps. Source shows the raw file. |
| fl-code | A Kotlin and a TS file are coloured, and long lines wrap. |
| fl-data | A CSV shows as a table and a row opens as a card. A minified JSON is pretty. A growing log follows live. |
| fl-image | A photo zooms around your fingers and won't pan off screen. A GIF moves. |
| fl-video | A video Claude made plays at once, seeks anywhere quickly and goes full screen sideways. |
| fl-audio | An mp3 plays, and unplugging headphones pauses it. |
| fl-pdf | A PDF's pages show and zoom sharp. |
| fl-download | Download shows a ring, then a check. The file is in Downloads/Hypernova, and Share sends it to another app. |
| fl-files | Files › This session lists what Claude created and edited, with thumbnails. |
| fl-secret | A project's `.env` says it stays on the Mac. |

**Manual performance**: frame timing while flinging a 20,000-line file and a 5,000-row CSV; a 4K
video seeking over mobile data; the APK size compared with the budget.

---

## 11. Tracks (no file touched by two tracks)

B goes first, because it holds the contracts. A, C and D can start at once against this spec, and
C and D compile against B's signatures (below).

**Track A: daemon and docs**
- new `daemon/src/files.ts` and `daemon/src/thumbs.ts`;
- `daemon/src/http.ts` (routes, CORS), `daemon/src/services.ts` (use the policy and `serveFile`,
  extend MIME), `daemon/src/normalize.ts` (refs), `daemon/src/hub.ts` (`Rec.files`, the index
  feed), `daemon/src/agents.ts` (agent edits into the parent), `daemon/src/qa-checklist.ts`;
- tests: new `daemon/test/files.test.ts` and `daemon/test/thumbs.test.ts`, edits to
  `normalize.test.ts` and `http.test.ts`, and `daemon/test/fixtures/files/*`;
- docs: `docs/PROTOCOL.md` (§3: REST rows, Types, the CORS line, an "Artifacts and previews" note on
  the policy), `docs/DECISIONS.md` (the build details under the owner's second round),
  `docs/LATER.md` (L20 PDF part closed; new rows for background audio and pictures on the lens, if
  the owner agrees).

**Track B: Android data and media plumbing**
- `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`: media3 1.11.1
  (`exoplayer`, `datasource-okhttp`), plus `testImplementation` junit 4.13.2;
- `data/Protocol.kt`: `FileKind`, `FileMeta`, `SessionFile`, `FileHow`, `Ref`, `FileText`,
  `FileEntry.kind/secret`, `WEvent.Assistant.refs`, and the wire parsing;
- `data/Models.kt` (`Item.Assistant.refs`), `data/Items.kt` (carry refs);
- `data/Api.kt`: range and conditional GET, streaming to a sink with progress, and the `Net.thumbs`
  cached client;
- `data/Hub.kt`, `data/LiveHub.kt` (and `fileTouched`), `data/FakeHub.kt` (demo failures);
- new `data/Downloads.kt` and `data/Playback.kt`;
- `res/xml/file_paths.xml`, new `res/values/strings_downloads.xml`;
- tests: `app/src/test/.../DownloadsResumeTest.kt`.

```kotlin
// Hub (B); C and D code against these.
suspend fun fileMeta(path: String): Result<FileMeta>                     // legacy daemon: ApiException code "not_found"
suspend fun fileText(path: String, tail: Boolean = false, max: Int = 2 shl 20): Result<FileText>
suspend fun fileMore(path: String, from: Long, etag: String?): Result<FileText?>   // null = unchanged; FileText(text, size, from, partial, etag)
suspend fun sessionFiles(sessionId: String): Result<List<SessionFile>>
suspend fun thumb(path: String, px: Int = 256): Result<ImageBitmap>
suspend fun file(path: String): Result<FileBytes>                       // existing; max raised to 64 MB
val media: MediaAccess?                                                  // null in demo; MediaAccess(url(path), headers)
val fileTouched: SharedFlow<String>
object Downloads { fun state(path: String): StateFlow<DlState>; fun save(hub: Hub, meta: FileMeta); fun cancel(path: String)
    suspend fun local(hub: Hub, meta: FileMeta): Result<File>; fun share(ctx: Context, file: File, meta: FileMeta); fun openWith(ctx: Context, uri: Uri, mime: String) }
sealed interface DlState { data object Idle; data class Running(val fraction: Float?); data class Done(val uri: Uri); data class Failed(val message: String) }
object Playback { fun player(ctx: Context, media: MediaAccess): ExoPlayer; fun release(); fun position(path: String): Long; fun remember(path: String, ms: Long) }
```

**Track C: viewer and Files screens**
- `ui/files/Files.kt` is replaced by new files:
  - `ui/files/FilesScreen.kt` (tabs, session list, folder browser);
  - `ui/files/Viewer.kt` (meta, dispatch, chrome, actions, change watch, legacy fallback);
  - `ui/files/TextViews.kt` (code, text, JSON, log);
  - `ui/files/TableView.kt`, `ui/files/Csv.kt`, `ui/files/JsonPretty.kt`;
  - `ui/files/ImageView.kt` (with `DrawablePainter`), `ui/files/MediaViews.kt` (video, audio,
    dock), `ui/files/PdfView.kt`.
- Routing and chrome:
  - `data/AppModel.kt`: `Route.FileView(path, line: Int? = null, source: Boolean = false)`,
    `Route.Files(…, sessionId: String? = null)`, `Route.Web(…, sourcePath: String? = null)`;
  - `ui/App.kt`: Opener adds `fileAt: (String, Int?) -> Unit = { p, _ -> file(p) }` and
    `sessionFiles: (sessionId: String, root: String, title: String) -> Unit = { _, r, t -> files(r, t) }`,
    both additive. HTML
    and SVG go to the preview with `sourcePath`. Orientation goes back to portrait when the video
    leaves.
  - `ui/web/WebScreen.kt` (the Source action), `ui/components/Icons.kt` (Download, Share, Pause,
    Expand, Collapse, Back10, Fwd10, Rows), new `res/values/strings_files.xml`.
- Docs and skill: `docs/DESIGN.md` (a "Viewer" entry covering the plates, dock, scrub bar and code
  palette), then the hyper-cosmos skill's `references/components.md`, synced into
  `skills/hyper-cosmos/`.
- Tests: `app/src/test/.../CsvTest.kt` and `JsonPrettyTest.kt`.

**Track D: markdown, highlighting and chat links**
- New `ui/components/Markdown.kt` (moved out of `Transcript.kt` and extended, §5.3) and
  `ui/components/Highlight.kt` (§5.4).
- `ui/components/Transcript.kt`: drop the moved code. `AssistantMessage` builds an `MdContext` from
  `item.refs` and `LocalOpener`, and `isWebPage` takes svg.
- `ui/theme/Tokens.kt` (`HN.code*`).
- `ui/session/SessionScreen.kt`: the strip from `sessionFiles`, and the Folder button calls
  `opener.sessionFiles`.
- `ui/session/Attach.kt`: `SentFiles` thumbnails through `hub.thumb`.
- New `res/values/strings_markdown.xml`.
- Tests: `app/src/test/.../MarkdownTest.kt` and `HighlightTest.kt`.

Leave `res/values/strings.xml` alone: the old `file_binary`, `file_too_big`, `image_zoom_hint` and
`open_as_page` go in a later cleanup, since other work edits that file now.

## 12. Not in this round

- Background audio with notification controls (`media3-session` and a media foreground service).
- Swiping between pictures.
- Editing files.
- Folding JSON.
- A waveform for audio.
- Showing pictures on the lens.
- Office files rendered as text (the Mac's `textutil`).
- Paths in tool output and in your own prompts as links.

## 13. Owner questions

1. **Secrets stay on the Mac**: `.env`, keys, `.ssh` and the like can't be opened, shared or
   downloaded from the phone, even inside a project (today `/v1/file` would serve a project's
   `.env`). *Recommend: yes.*
2. **Video full screen turns the phone sideways**, the only landscape view; the rest stays portrait
   (L9). *Recommend: yes.*
3. **Audio that keeps playing with the screen off** needs a media notification and about +300 KB.
   *Recommend: later; audio stops when you leave the viewer for now.*
