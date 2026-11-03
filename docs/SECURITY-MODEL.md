<!-- Modified by Leonardo Merza, 2026. See NOTICE. -->
# Security model

What runs where, what data lives where, who can reach what, and what is still not closed. This is
the threat model behind [`SECURITY.md`](../SECURITY.md) (what listens where, how to report a
hole). Facts only: each was checked against the code or [`PROTOCOL.md`](PROTOCOL.md) when this was
written (2026-10-18). Where it wasn't, it says **unverified**. Where this page and the code
disagree, the code wins and this page has a bug.

## 1. The pieces and what runs where

| Piece | Runs on | What it is |
| --- | --- | --- |
| Claude Code (`claude`) | the Mac (or Linux box) | Anthropic's program, signed in with the user's own login. Hypernova never reads or copies that login. |
| Codex (`codex`) and its app server | the same machine, used only while it's on for Hypernova | OpenAI's program, signed in with the user's own login. The daemon is one client of Codex's own app server; it reads whether Codex is signed in and on which plan (the email never leaves the daemon), never the login itself. |
| The daemon (`daemon/`, Node) | the same machine | Watches Claude Code through hooks and transcripts, runs the phone's sessions in shared terminals, serves the phone API. The only thing the phone talks to. |
| The `hypernova` command (`daemon/src/cli/`) | the same machine | Setup, pairing, status, the systemd user service. Talks to the daemon over loopback with the admin token. |
| The Android app (`android/`) | the phone | The client. |
| The owner's network | the Wi-Fi, or the owner's own VPN into it | The only route in: the phone reaches the LAN listener (47323) on it. Hypernova carries no VPN of its own (Tailscale was removed on 2026-10-30, docs/DECISIONS.md). |

There is no Hypernova account and no Hypernova server that carries sessions.

## 2. What data exists where

**The Mac** (the only place sessions live). The support folder is
`~/Library/Application Support/Hypernova` (Linux: XDG data folder), `0700`, each secret `0600`:

- Claude Code's own data in `~/.claude`: transcripts, settings, its login. Hypernova reads
  transcripts to show sessions; it does not move them anywhere except to paired devices (below).
- Hypernova's state: the device list (`devices.json`: for each phone its name, the SHA-256 of its
  token, the fingerprint of its certificate), settings, session records and titles.
- Attachments the phone uploaded (`attachments/`, kept 30 days).
- Debug logs a client sent on purpose (`client-logs/<device>/`, the newest 5 per device, kept 30 days, untrusted text;
  see "Client debug logs").
- Tokens and keys: the **admin token**, the **hook secret**, TLS keys (`tls/`: the server key and the
  device CA), provider keys, automations'
  **fire tokens** (only their SHA-256, in `automations.json`) and their webhook outputs' addresses
  and bot tokens (`automation-secrets.json`, never logged, never sent back). An install from before
  2026-10-30 may still have a `tsnet/` folder with a Tailscale node key in it, no longer read.
- The daemon log (`~/Library/Logs/Hypernova/daemon.log`, `0600`, in a `0700` folder): names devices,
  projects and events; never tokens, codes or keys (see `SECURITY.md`).
- The debug log, beside it, only after someone turned it on, and the bundles made from it: section 7, "The
  server's debug log".

**The phone.** Its device token (the daemon keeps only its hash), the Mac's base URL and name, and
sessions' text as the app shows it. The token sits in the app's private preferences, not the
Keystore (**unverified** beyond reading `data/Pairing.kt`). The device certificate's private key is
made on the phone and stays in the Android Keystore. The app's private files are excluded from
backup and device transfer (`allowBackup=false`); an older build's Tailscale node keys
(`files/tsnet`) stay there, no longer read since 2026-10-30. Downloaded files go where the user
saves them.

**The desktop client.** Its device token and certificate are in an owner-only settings file in its own folder (`~/.local/share/hypernova-client` on Linux). Its device key is in the system's keychain where one answers: the Secret Service on Linux, the login keychain on a Mac, DPAPI on Windows, the last two **not yet run outside CI**. Otherwise it's in an owner-only file beside the settings, and Settings says so. The key's bytes are in the process's memory on every system, and any program running as the user can get them from any of these. What a keychain adds is that they aren't readable in a copy of the home folder. **That copy still has the token, which can get a certificate for another key** (docs/DECISIONS.md, "The desktop client's key in the keychain", 10; LATER L124).

**Dictation's audio** never goes to the Mac. The phone's speech recognizer hears it, as Settings →
Voice chooses (`data/PhoneDictation.kt`; docs/DECISIONS.md, 2026-10-31): **Phone's recognizer**
(the default) is the phone's default recognition service, the one the keyboard's voice typing uses,
normally Google's, which may send the audio to Google's servers; **On-device only** is Android's
on-device recognizer, and the audio never leaves the phone (no fallback to the other: a phone
without one has no mic). Hypernova keeps no audio and writes no words down.

## 3. What each outside party sees

| Party | Sees | Does not see |
| --- | --- | --- |
| Anthropic | What Claude Code sends it, exactly as when the user runs Claude Code in a terminal: prompts (including ones typed on the phone), the files Claude reads, usage. The usage probe runs Claude Code itself to read the subscription's limits (**unverified**: not re-read for this page). | Anything about Hypernova's phone or pairing. Hypernova makes no calls to Anthropic's API of its own. |
| The owner's network and VPN | That a phone connects to the server's port 47323, and when. The traffic is TLS to the server's pinned certificate. | Message contents. |
| A provider (OpenRouter, a gateway) | What a session on that provider sends, with its own key. | Other sessions. |
| nodejs.org, Anthropic's installer, GitHub (setup) | Downloads the user asks for (Node.js is checked against its published checksums). | -- |
| GitHub (the app's releases, while `releaseCheck` is on, the default, or `autoUpdate`, on by default) | The daemon's unauthenticated `GET` of `api.github.com/repos/ljmerza/Hypernova/releases/latest`, 30 s after start and every 6 hours (with the last ETag; none while a rate limit lasts), from the server's address with a `hypernova-daemon/<version>` user agent; and, for a newer release only, the downloads of its `hypernova-<tag>.json` and `hypernova-<tag>.apk`, and with `autoUpdate` on, of its `hypernova-server-<tag>-linux.tar.gz`. | No token, nothing about sessions, devices or pairing. |
| The phone's speech recognizer (normally Google's), with Phone's recognizer chosen (the default) | Dictation's audio and words, as when the keyboard's voice typing is used; whether it goes to the recognizer's servers is that service's own choice. Nothing with On-device only. | Sessions, the Mac, pairing. |

No name service and no DNS record: nothing about the computer or its pairing is published.

## 4. Every channel

| Channel | Where | Who can connect | How it is protected |
| --- | --- | --- | --- |
| Loopback phone API | `127.0.0.1:47321`, plain HTTP | Programs on the machine | Bearer device token on every `/v1` route except `health`, `pair` and a terminal's ticketed WebSocket. No CORS headers, so a web page can't read it. Requests that came through a proxy are recognised by their forwarding header and never treated as local. |
| Local API | `127.0.0.1:47322` | Programs on the machine | The **admin token**; hooks use `/hooks/<secret>` (compared in constant time). Never behind the LAN listener. The checklist page has its own key. |
| The fire route | `POST 127.0.0.1:47322/local/automations/<id>/fire` | Programs on the machine (git hooks, cron, CI, `hypernova automation fire`) | That automation's own **fire token** or the admin token, checked before the body is read. A wrong token is 401 alike whether the automation exists, isn't a webhook's or has another token, so nothing tells which ids exist. 64 KB of text at most; 10 fires a minute per automation, then 429 with nothing recorded. The only `/local` route that takes another token than the admin one. A paired device fires the same way over its own routes. |
| LAN listener | `0.0.0.0:47323`, HTTPS, mutual TLS | Phones on the same network or hotspot, or over the owner's own VPN into it: the only way a phone comes in | Sources outside private ranges are dropped before TLS. The phone pins the daemon's self-signed certificate (fingerprint from the pairing link) and presents a device certificate the daemon signed for it (ECDSA P-256, 2 years, revoked at once when the device is forgotten). Without one only `health`, `pair`, `device/cert` and the app's download page answer. Per-source and total connection caps, handshake and header timeouts; pairing closed unless a code is shown, 5 wrong codes per source per 10 minutes. Can be turned off (`lanAccess`; off by default in containers and on cloud VMs). |
| The pairing link | `https://<address>:47323/pair?c=…` (QR), the server's own page on the LAN listener | Anyone on the network who sees the QR code while it is valid | The page answers only for a code being shown and hands the app the address the browser came in on (never a header's). See codes below. |
| App update | `GET /download/android/Hypernova.apk` over the app's own route | Anyone who reaches a listener, without a token | The APK is public by design. Android checks it: same package, newer version, same signing key. |
| The release check (outgoing) | The daemon to `https://api.github.com` and GitHub's asset downloads (`daemon/src/releases.ts`), 30 s after start and every 6 hours; off with `releaseCheck: false` | -- (the daemon calls out; nothing listens) | HTTPS with the system's certificate checks; the asset links must be `https`. The release's note (`hypernova-<tag>.json`, 64 KB at most) gives the versionCode and the APK's SHA-256; the APK (256 MB at most, the size the release lists) is offered only if its SHA-256 matches the note and its versionCode is higher than the APK handed out now, so an admin's newer build is never replaced. Written to a temporary file and renamed into place, note last. The SHA-256 comes from the same release, so it guards against a broken or mixed-up download, not against someone who can publish releases on the repository. Against an APK uploaded by hand, the phone's own check holds (Android installs an update only when it is signed with the installed app's key); someone who can change and run the release workflow can sign with the key (it is a repository secret), the same risk as for anyone installing a release from its page. Offline, a rate limit or an error: logged, and the next look in 6 hours. |
| The server's own update (outgoing, then run) | `autoUpdate`, **on by default** (owner, 2026-11-01) (`daemon/src/server-update.ts`; docs/PROTOCOL.md, "The server's own update") | -- | Only on an install of a release's package run by its systemd service (never a checkout, a test copy or a container). The server package (128 MB at most) is installed only when its SHA-256 matches the note's `serverSha256` and its daemon's version is the tag, a higher one than the running daemon's (never a downgrade). It then **runs as the owner**: its `install-linux.sh` and its daemon, as a manual update would, in a transient systemd user unit; no `sudo` (no terminal to ask on). The SHA-256 comes from the same release, so, as for the APK, it guards against a broken or mixed-up download, not against someone who can publish releases on the repository: with it on, such a person's release runs on the server within about 6 hours, where the APK still has to pass Android's signature check. Off, it is the same trust as running `get.sh` by hand. |

A phone's browser reaches the pages by address, with the server's own certificate (it warns once);
the app pins that certificate.

## 5. (Removed: the Lens bridge)

The glasses app and its bridge on the phone were removed on 2026-10-30 (docs/DECISIONS.md, "The
glasses are removed"). The section numbers after it are kept.

## 6. Who can do what with what

| Credential | Held by | What it opens | Lifetime and revocation |
| --- | --- | --- | --- |
| **Admin token** | The `hypernova` command, on the server | The local API (setup, pairing, settings, forgetting devices, the service) | Per install; anyone who can read the support folder has it. Not valid on the LAN listener. |
| **Device token** | One paired phone | The whole `/v1` phone API: start sessions, prompts, approvals, terminals, files, settings the phone may change | 256 random bits, stored by the daemon as a hash. Forget the device (`DELETE /local/devices/{id}`, or `DELETE /v1/devices/me` from the phone) and it stops working at once, on open connections too. Pairing the same app install again replaces its old device. |
| **Device certificate** | One phone, for the LAN listener | The LAN listener at all | Two years; one active per device; revoked with the device. |
| **Preview token** | Whoever holds the URL | One folder's files or one dev-server port, no header needed | 12 hours. Never serves dotfiles or key files. |
| **Terminal ticket** | The phone's terminal view | One terminal's WebSocket | One minute, single use, tied to the phone that asked. |
| **Pairing code** | Shown as a QR on the Mac | `POST /v1/pair`, once | 16 random bytes, single use, valid 10 minutes, or until the Mac stops showing it (closing the Pair page cancels it, and a fresh code replaces the old). Pairing on the LAN listener is closed while none is shown. A code's QR is a live credential for that time. |
| **Hook secret** | Claude Code's hooks | `/hooks/<secret>` on the local API | Per install. |
| **Fire token** (`hnf_` + 32 hex) | A script on the Mac (a git hook, cron, CI) | Firing that one automation's webhook trigger, on the local API only; the run goes through the same gates as any firing (one at a time, 24 a day, the account, the budget) | Shown once when made; kept as a hash. A new one (`POST /v1/automations/<id>/token`) stops the old at once; so do deleting the automation or changing its trigger. |
| **Checklist key** | The page's link | The testing checklist | Per link. |

A paired phone can do what the user can do at the keyboard in Claude Code: start sessions in project
folders, answer approvals, type into live terminals, and so spend usage or credits. Files that
usually hold secrets (`.env`, `.npmrc`, `*.pem`, `*.key`, `.ssh`, `.aws` and the like) are listed but
never read, shared or downloaded. The phone browses the projects folder; and only when the owner turns
on **Browse outside projects** (Settings on the phone or the Mac, off by default; the phone asks for
the fingerprint or screen lock first), each session's own folder in the home folder, read-only. Never
a folder outside the home folder, and never the home folder's key places (keys, keychains, cookies,
mail, messages, browsers' profiles, `~/Library/Application Support`, the daemon's state and Claude
Code's: `daemon/src/secretfiles.ts`), checked after links are followed (`Session.browsable` says which
sessions). An "Always" answer, switching to Bypass, starting a session in it and an automation's No
asking each wait for the fingerprint or the screen lock on the phone; notifications and the widget
offer Allow and Deny only. "Bypass" asks the user to think twice, and automations never run on credits or a paid provider without the user's yes.

**The fingerprint is the phone's word, not the daemon's proof.** An "Always" answer, Bypass,
Browse outside projects, and an automation's `confirmBypass` (No asking) and `confirmShell` (shell
steps) are asked for on the phone with `BiometricPrompt` (on the Mac app with Touch ID or the Mac's
password) and then sent as a flag. The daemon can't see a fingerprint and can't check one. The flags
stop a mistaken tap and a notification's or the widget's quick answer; they don't stop a stolen device
token, which can send them like the phone does. That is no more than a stolen phone could do anyway
(start a session in Bypass). Since 2026-10-31 an "Always" may also name its rule and where it goes,
up to the owner's user settings (`docs/PROTOCOL.md`, "What Always covers"), so one waiting ask can
leave a lasting rule as broad as `Bash` everywhere. Claude Code writes that rule itself, from the
hook's answer; the daemon reads and writes no settings file for it, and the answer takes only an
allow rule, never a deny, ask, mode or folder of the phone's. The daemon itself asks for them on every save that needs them,
including a save that keeps the steps as they were.

### Client debug logs

**Off by default on both clients.** Settings → Debug logging turns on a detailed log, for 24 hours at most
(a clock that says it's further off than that counts as over). It's for finding what went wrong, and the
rules are the owner's (docs/DECISIONS.md, "A debug log"):

- **Kept on the device,** in the app's private folder on a phone (`files/debug-logs`, excluded from backup
  like the rest), or `files/debug-logs` under the desktop app's folder, 0700 and 0600. About 5 MB at most,
  the oldest going first. Crashes go in a file of their own, 64 KB at most, **with the switch off too**:
  stack trace, app version, time and thread name, no exception's message.
- **It leaves on a tap, to where the tap says:** Send logs, to the server picked, over its own link
  (below); or Share, Android's share sheet, with a read grant for one `FileProvider` URI, one file in a
  folder that holds nothing else, so whatever app the owner picks gets that file and only that. On a desktop
  there's Open folder and Copy path, and it's up to him. No cloud service, no automatic upload, nothing
  sent in the background.
- **On the server,** `<support>/client-logs/<device>/`, 0700 and 0600, the server's own file names, the
  newest 5 per device, 30 days, stored as untrusted bytes and never parsed, served or run (`docs/PROTOCOL.md`,
  "Client logs"). Anyone who can read the support folder can read them. No route reads them back, so a
  stolen device token can't read what was sent, only send more (6 MB, 10 an hour, and 5 kept).
- **One scrubbing layer, for every line** (`data/DebugScrub.kt`), applied just before the disk, the crash
  file included. It cuts: the secrets this run actually holds (device tokens and pairing codes, by value);
  certificates and keys (PEM, long base64); `Authorization`, cookie and other header values; tokens by name
  and by shape (`hn_`, `hnf_`, `sk-`, `ghp_`, JWTs and so on); env-style `NAME=value`; pairing links, ticket
  URLs and every URL down to its host and port; query strings; fingerprints; long random runs; email addresses;
  file paths. `DebugScrubTest` plants each and looks in the files.
- **No conversation content, by construction, since a prompt has no shape to scrub:** call sites log ids,
  sizes and fixed words. A route keeps only segments that can be a word or an id, and a preview's link goes
  whole (`DebugScrub.route`). A feed message logs its type, size and session id. A screen logs its kind and
  ids (`Route.debugWords`, which every new route has to fill in). An exception's message can quote what it
  failed on, so it's left out unless it's the network's own. `DebugLogHubTest` plants session and project
  names, prompts, replies, tool arguments, file names and the server's own words in a real feed and real
  requests, and looks in the file. An "include content" option isn't built. **What this can't promise:** a
  future call site that logs a title in a sentence would get past the scrubber; the test catches it only for
  what the test drives.
- **What a log does show:** session and agent ids, the routes a device used and when, its model and system,
  the server's name and addresses, the network kind. Enough to see how someone uses the app, and the reason
  it stays on the device until he sends it.

### Automations and words from outside

Automations act with nobody watching, on events from outside: a webhook's body, the names of files
a watch saw, pull requests' titles, authors and review comments, a tool's error in a hook event,
another session's reply. All of that is **outside text**, and the daemon treats it so
(`docs/PROTOCOL.md`, "Workflows"):

- **Prompts.** Outside words (an earlier step's output, `{step:<id>}`, among them) go into a
  start's or continue's prompt quoted, as information and not instructions. They never go into a start in No asking (refused at save, `{payload}` and
  `${payload}` alike) or into a session running in No asking (a continue skips it and says so).
- **Shell steps** never get outside words in their command line: values reach a command only as
  `HYPERNOVA_*` environment variables (an earlier step's whole output as `HYPERNOVA_STEP_<id>`;
  `{step:<id>}` in a command is refused at save), and even the safe placeholders (`{project}`, `{time}`,
  `{battery}`, `{exit}`, `{answer}`) become quoted references to their variables, so the shell
  never parses a value. A step runs as the owner with `/bin/sh -c`, no TTY, stdin empty, in the
  projects folder or a session's own folder (never a key place, checked again when it runs), with
  the environment sessions get minus Claude Code's and Hypernova's variables (no admin token, hook
  secret, device tokens or provider keys: those live in files, never in the daemon's
  environment), a timeout, its whole process group killed at the end, its output masked like
  transcripts and cut to 8 KB. Saving one needs the fingerprint every time.
- **Asks, notifications and outputs** show outside words as they are, as text (the phone's
  and the Mac app's note views: **unverified** here). Webhook presets carry them
  as data: JSON; Slack's `&`, `<`, `>` escaped; Discord mentions nobody; Telegram without a parse
  mode.
- **Webhook outputs** go over https only, never to this computer or a private network: loopback,
  the private ranges, carrier-grade NAT (100.64/10, which VPNs hand out), link-local and cloud metadata
  (169.254.169.254, `fd00:ec2::254`), unique-local, multicast, reserved and documentation ranges,
  IPv4 inside IPv6 judged as IPv4. A name is judged after DNS, by every address it has, and the
  connection is pinned to the addresses judged (DNS rebinding changes nothing); no redirect is
  followed; 10 s; the answer's body is never read. To send to a self-hosted ntfy on the local
  network or a VPN, add its host, as written in the URL, to `webhookPrivateHosts` in
  `<support>/settings.json` (with the daemon stopped, then start it).
- **File outputs and watches** stay inside the projects folder and never touch a file with keys. A
  file output is appended without following a link (one put there after the check is refused). A
  watch ignores `.git`, `node_modules`, files with keys and its own run's writes, keeps 1,000
  changed paths and counts the rest; its glob is matched in linear time, so a file named to make a
  pattern backtrack can't hold the daemon up.
- **GitHub watches** run `gh` with fixed arguments in the folder as checked at each look; nothing
  from a pull request is ever an argument.
- **Hook triggers** come from Claude Code's hooks on the hook secret's route, or from the daemon's
  own SDK sessions. Anything on the machine without the secret can't send one.
- **A project's MCP servers:** a run starts only what the owner has already approved, whichever way it runs (`daemon/test/automation-mcp-e2e.test.ts`: marker files, real Claude Code), and says what it went without. So a `.mcp.json` a pull brought in doesn't run with nobody watching. Its folder's `apiKeyHelper` still does, as in a session there (`docs/DECISIONS.md`, "What an automation may start", 5).
- **Limits** hold whatever fires them: one run at a time per automation, 2 working and 10 waiting in
  all, 24 runs a day per automation (200 for one that only tells), 10 fires a minute per webhook
  trigger, and the per-run budget.

## 7. What never leaves the Mac

- Claude Code's login and its credentials.
- Provider keys: never logged, never sent to the phone (it sees only "has a key"), and
  they go only to their own provider.
- The admin token, the hook secret, the TLS keys.
- Automations' fire tokens (only their hashes are kept) and webhook outputs' addresses and bot
  tokens: a webhook's secret goes only to its own host.
- A client's debug log leaves the device only on a tap, to the server picked or where its owner shares it.
- Transcripts, attachments and files: they leave the computer only to a paired device, over the routes above,
  and (for Anthropic) as Claude Code itself sends them.
- The pairing details: only to the server's own pairing page on the local network.
- Claude Code's settings files (their `env`, `apiKeyHelper`, hooks, MCP servers): a paired device
  gets only their permission lists, for the session's own folder (`docs/PROTOCOL.md`, "Rules in
  effect", 2026-10-30), and, from the user-level `settings.json` and Codex's `config.toml`, the fixed
  list of `GET /v1/agents/settings` (`docs/PROTOCOL.md`, "Agent settings", 2026-11-01): named values,
  each checked for its type and shape, and as summaries the counts of permission rules and of Codex's
  projects, plugin names with on or off, MCP server names and hook event names. Never `env`, hook and
  status line commands, `awsAuthRefresh`, marketplace sources, `autoMode.environment`, `skip*` keys,
  or MCP commands, args and env (`daemon/test/agent-settings.test.ts` plants a secret in each). Those
  names do show what's set up on the server, to a paired phone, which can already start sessions there.
  The GET only reads, for a device token and not the admin token; `~/.claude` and `~/.codex` stay
  closed to the phone's file browser. What writes is in section 8, risk 17.
- Claude Code's `~/.claude.json` and projects' `.mcp.json` (the account's details, OAuth data, history, MCP
  commands, args, env, URLs and headers): a paired device gets only MCP server names from the `mcpServers`
  tables, each checked as a name, with a scope and a folder's base name (`claude.mcp` of the same route;
  the home folder is `~`). Each file is read per request, with a size cap, and nothing is kept or logged;
  a parse error, which could quote the file, is never kept. `.mcp.json` isn't followed through a link and
  must be a regular file. `.claude.json` stays a blocked file for the phone's file viewer
  (`daemon/src/secretfiles.ts`): this route doesn't change that. `daemon/test/agent-settings.test.ts`
  plants a secret in each of those places and checks the raw HTTP body. Those names and base names do show
  which projects and tools are on the server, to a paired phone, which can already start sessions there.
- The same files, for one session's folder (`GET /v1/sessions/{id}/mcp`, `docs/PROTOCOL.md`, "A session's MCP
  servers", 2026-11-01): each server's name and scope, its transport, a stdio command's base name and an http,
  sse or ws URL's host, each checked. Never args, env, headers, `headersHelper`, OAuth settings, a URL's path,
  port, query or credentials, or a path; a command name that says the username, or a host that looks like a
  secret, is left out, and the home folder is `~`. For a device token only. The same test file plants a secret in
  each of those places, in a command path, and in a URL's query and userinfo, and checks the raw HTTP body. A
  host does say where a server lives, to a paired phone.

### The server's debug log

`debug.log` (`daemon/src/debug-log.ts`; `docs/LINUX.md`, "The debug log"; owner, 2026-11-02): off by default, off by itself within 24 hours, 20 MB at most, `0600` in a `0700` folder. **It never leaves the computer by itself:** no route reads it, for a phone or anyone. A paired device can turn it on or off (`POST /v1/mac/debug`) and see when it ends, nothing more. So can a stolen token: that fills 20 MB of a disk and writes a line in `daemon.log` naming the device, and shows it nothing.

The rules are at the write (`daemon/src/debug-scrub.ts`), so no call site has to remember them, and `daemon/test/debug-log.test.ts` plants each kind through every field and checks the file itself:

- **An event is one of a fixed list; a field's name is a plain word.** Anything else there is its size or goes.
- **A string is its size by default**: `[39 chars]`. Only named fields may hold a word, and only one word, short, that doesn't look like a key: a method, a state, a reason, a tool's or a program's name, an address on the network. Ids are their first 8 characters. Lists, objects and buffers are never opened.
- **Names that mean a secret or the owner's words win:** `token`, `cert`, `code`, `authorization` and the like are `•••`; `prompt`, `command`, `cwd`, `title`, `file`, `url`, `env`, `headers` and the like are their size.
- **A route is its own words.** Never its query. Only the words of the protocol's routes and a session's UUID (cut to 8) stay, so `/hooks/<secret>`, `/p/<token>/…`, an MCP server's name and a file's are `:x`.
- **A command is its program's base name, its known sub-commands and its flags' names.** Never a value, `--flag=value`'s included, nothing after `--`, and no other word: a prompt is a positional word.
- **An error is its class, its code, its system call and its stack's frames**, with any file outside the daemon as `[path]`. Its message only when it's one of V8's own wordings, since a message can quote anything: Node's JSON errors quote the text, file errors the path.
- **Then the whole line goes through the masks**: live values by name (the admin token, the hook secret, a pairing code being shown), PEM blocks, credential headers, anything after a name that says secret, URLs cut to their host, `maskSecrets` and `maskText`, and paths.

**`hypernova debug bundle`** writes one file meant to be shared, so it's stricter. Debug lines are scrubbed again and any not in the writer's shape is left out. `daemon.log` and `terminals.log` hold what they always held (folders, phones' names, an attachment's name, what Claude Code said on stderr, a terminal's command line), so of those only words that appear in the daemon's own `log()` calls stay, read from its source, with numbers, short ids and masks; every other word is `…`. The user's, the computer's and the phones' names and the home folder are taken out. It's `0600`, and goes nowhere until the owner sends it.

**What doesn't hold.** A word the daemon's log lines also use stays in a bundle's everyday lines: a prompt's "the" and "failed" remain, though not the sentence. A one-word value in a word field can't be told from a word of the daemon's: a tool's name is shown, and an MCP tool's name says which server, and a stdio server's program name is shown. A short secret that a call site passed under a word field's name, with nothing to show what it is, would be written; the calls in this repository don't, and only the owner's code runs there. Masks are heuristics, so the fixed shapes above are what's relied on, not them. Doctor's lines in a bundle keep program paths and the server's own addresses. The log says when things happened and which device did them. The bundle is meant to be looked at before it's sent.

## 8. Known residual risks

1. (Removed with the glasses, 2026-10-30: a squatter on the Lens bridge port.)
2. (Removed with the glasses, 2026-10-30: preview tokens and Telescope picture names on the bridge.)
3. **The LAN listener's uncertified pages.** Anyone on the same network can reach, without a device
   certificate: the app's download page and APK, `GET /v1/health` (which includes the computer's name),
   `POST /v1/pair` (closed when no code is shown, 5 wrong codes per source per 10 minutes) and
   `POST /v1/device/cert` (needs a paired device's token), and a shown code's pairing page. The download
   page's certificate is self-signed at the IP address (`hypernova pair` shows the checksum).
4. (Removed with the glasses, 2026-10-30: a relay on the Lens bridge.)
5. **Tokens at rest.** Device tokens on the phone and on the desktop client, and the Mac's admin token and provider keys, are
   plain private files or preferences, not the Keychain or Keystore (LATER L30 for the keys, L124 for the desktop client's token). Anyone who
   already runs code as the user on the Mac, the desktop or the phone has them.
6. (Removed with the name service, 2026-10-30: the private name in Certificate Transparency logs and DNS.)
7. **A lost phone is a lost unlocked keyboard** until it is forgotten on the Mac. Who can join the Wi-Fi
   and the VPN into it is the user's to set.
8. (Removed with the glasses, 2026-10-30: an iframe on a Telescope page.)
9. **Approval notifications** show the raw command on the lock screen unless the phone hides sensitive
   notifications (LATER L41).
10. **Quoting is not a wall.** Outside words in an automation's prompt are quoted as information, but
    a session that isn't in No asking still reads them and may act on them within what its mode
    lets it do (its allow rules, Auto, accepting edits). An automation fed by outside words is best
    kept on Allowed only, with narrow rules.
11. **The fingerprint flags are the client's word** (section 6): a stolen device token can send them.
12. **A fire token on a command line** (`hypernova automation fire --token`) shows in the process
    list to other users of the computer; `HYPERNOVA_FIRE_TOKEN` doesn't.
13. **Whoever listens on 127.0.0.1:47322 while the daemon is down** gets what the CLI sends there:
    the admin token or a fire token (`daemon/src/cli/api.ts`; the Mac app's own calls,
    **unverified**). Only another user of the same computer gains from that: the owner's own programs
    can read the admin token anyway.
14. **A shell step runs as the owner, with his environment** (an SSH agent, keys exported in the
    shell the daemon was started from): its command is his, and only his words are in it.
15. **A file the phone opens is checked, then opened**: a link swapped in at the file itself is
    refused, but one swapped in for a folder above it between the two is followed. Only a program
    that can already write in that folder can do that, and what it reaches goes to the owner's own
    phone.
16. **The server's own update trusts the release** (`autoUpdate`, on by default): whoever can
    publish a release on the repository, or change and run the release workflow, gets code run on
    every server that has it on, without the owner's look (section 4). The package's SHA-256 is the
    release's own; the package itself carries no signature.

Out of scope: someone already running code as the user on the machine, Claude Code's own permission
rules, and weaknesses in the VPN or the operating system.
17. **Changing the agents' settings from a paired phone** (`PUT /v1/agents/settings`, 2026-11-01; `docs/PROTOCOL.md`,
    "Agent settings"). The only route that writes those files, so a new surface. The threat is a stolen pairing:
    a device token and its certificate, taken from a phone or a backup of it. With them, an attacker can move
    the starting permission mode as far as `auto`, Codex's sandbox as far as `workspace-write`, and pick the update
    channel, a model and a web search setting, for new sessions on that server. They can undo the last change,
    unless what it would restore is off the list.

    What holds on the server, whoever is asking: only the listed keys and values. `bypassPermissions`,
    `approval_policy = "never"` and `danger-full-access` can't be set, nor restored by Undo, and `hooks`, `env`,
    rules, status line and MCP entries can't be reached, so this route can't be made to run a command. Each change
    leaves a backup and a log line. A file that changed is refused; a link isn't followed.

    What doesn't hold: **the confirm and the fingerprint before a loosening are the phone's own check, as in risk 11. The
    server can't see them**, and only flags the change in its reply and its log. A stolen token skips them. Moving to `auto`
    or `workspace-write` stays possible with one. Nothing here adds to what that token already had: it can start
    sessions, answer approvals and open terminals on the server, where these files can be edited directly. Forgetting
    the device ends it. Backups hold whole settings files, secrets in `env` included: 0600 in a 0700 folder under
    `<support>`, 20 per agent, and they go with the state folder.

18. **Managing an agent's MCP servers from a paired phone** (`docs/PROTOCOL.md`, "An agent's MCP servers", 2026-11-02; `docs/DECISIONS.md`, "Managing MCP servers from the phone"). This one can run a program on the server by design: add a stdio server,
    or approve a project's. The threat is a stolen pairing again. **The confirm and the fingerprint are the phone's own check; the server only sees the claim, `confirmRun`**, as with an automation's `confirmShell`, and refuses without it. A stolen token sends the claim.
    Such a server only runs once a session or a status starts it. As with risk 11, that token could already open a terminal, so this isn't a new power; it's a new, quieter way to keep one, because it outlives the session and the device being forgotten. Check the list after a lost phone.

    What holds on the server, whoever's asking: env and header values and tokens are never in a reply, on any route, tested with a planted secret in the raw bodies. Names, commands and URLs are checked. A status never starts a project's server nobody approved,
    and neither does seeing its tools, the "/" list or the model list (`guardArgs`, `daemon/test/mcp-guard-e2e.test.ts`: marker files, real Claude Code), including a `.mcp.json` above the folder, through a linked folder, that is itself a link, or that is too big to read here. A folder has to be one the daemon knows. Nothing new reaches the file viewer: `~/.claude.json` stays closed to it. A trust dialog isn't accepted from here. No tool can be run from here.

    The same processes don't run a folder's own `apiKeyHelper` either (`.claude/settings.json` or `settings.local.json`; marker files in the same test). The user's own still runs, and while such a process lives its command is on that process's command line, visible to other users of the computer.

    What doesn't hold: masking of arguments is a heuristic, and a secret with no sign of being one is shown. A value typed on the form is on `claude mcp add-json`'s command line for a moment, visible to `ps` for other users of that computer. The Undo file holds a whole entry, secrets too, as settings backups do (0600 in a 0700 folder).
19. **Plugins and skills from a paired phone** (`docs/PROTOCOL.md`, "An agent's plugins" and "An agent's skills", 2026-11-02). Installing or updating a plugin, or adding a marketplace, is someone else's code on the server, the same way as risk 18, behind the same claim (`confirmRun`). So is updating a marketplace for Codex, which also moves what's installed from it to the new version (measured on 0.164.0). A plugin's hooks are worse than a server: they run on every session, with no permission asked. A stolen token can add a marketplace and install from it, and send the claim. After a lost phone, check the plugin list too. Enable and Claude Code's marketplace update don't ask: a token can turn back on what was off, and a marketplace update fetches a new version without moving what's installed to it (measured on 2.1.316: a new process still ran the old one). Claude Code's own auto-update of a marketplace set to it, at a session's start, isn't this route and wasn't changed. None of these runs anything until the next session, measured with a marked hook.
    What holds: a plugin's MCP env and config never leave, sensitive options are write-only and go over stdin, an id or a source can't read as an option on the command line, and a skill's file isn't reachable from here.
20. **An agent's environment variables from a paired phone** (`daemon/src/agent-env.ts`; `docs/PROTOCOL.md`, "An agent's environment variables", 2026-11-02). An environment variable can change what runs and where data goes, on every later session, without a prompt: `LD_PRELOAD` loads a library into every command, `ANTHROPIC_BASE_URL` sends every prompt and file elsewhere, `NODE_TLS_REJECT_UNAUTHORIZED` makes that invisible. As in risks 11, 17 and 18, the fingerprint is the phone's own check and the server sees only the claim (`confirmRun`), so a stolen token can send it. A stolen token could already open a terminal; this is a quieter way to keep a foothold, and it outlives the device being forgotten. Check the list after a lost phone. The project scope writes a file a repository may share with others.

    **Refused outright, confirmed or not; they can only be removed, and Undo won't restore one:** `IS_SANDBOX`, `CLAUDE_CODE_SANDBOXED` (both tell Claude Code it's sandboxed: bypass as root, every folder trusted), `CLAUDE_CODE_EVEN_MORE_DANGEROUSLY_SKIP_PERMISSIONS`, `CLAUDE_CODE_IGNORE_PERSISTED_TRUST`, `CLAUDE_CODE_RELAUNCH_HOME_TRUST`, `CLAUDE_CODE_AUTO_MODE_EXTERNAL_PERMISSIONS`, `CLAUDE_CODE_DISABLE_ULTRAREVIEW_TRUST_CHECK`, `CLAUDE_BG_WORKSPACE_TRUSTED`, `CLAUDE_BG_SESSION_PERMISSION_RULES`, `CLAUDE_CHROME_PERMISSION_MODE`, `CLAUDE_CODE_REMOTE_TOOLS_HOST_ALLOWS_UNATTENDED`, and any name holding `BYPASS`, `DANGEROUS`, `YOLO`, `SKIP_PERMISSION`, `FULL_ACCESS`, `NO_SANDBOX` or `UNSAFE`. Found in Claude Code 2.1.316's binary. Codex 0.164.0's binary has no such variable, and its `set` only reaches commands, so `danger-full-access` can't come from here anyway; the same list applies.

    **Fingerprint, setting or removing, in capitals as Claude Code reads them.** What runs: `PATH`, `HOME`, `ENV`, `BASH_ENV`, `ZDOTDIR`, `SHELL`, `IFS`, `PROMPT_COMMAND`, `LD_*`, `DYLD_*`, `NODE_OPTIONS`, `NODE_PATH`, `PYTHONPATH`, `PYTHONHOME`, `PYTHONSTARTUP`, `PERL5LIB`, `PERL5OPT`, `RUBYLIB`, `RUBYOPT`, `GIT_SSH`, `GIT_SSH_COMMAND`, `GIT_EXEC_PATH`, `GIT_ASKPASS`, `SSH_ASKPASS`, `GIT_CONFIG*`, `EDITOR`, `VISUAL`, `PAGER`, `BROWSER`, `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `CODEX_SQLITE_HOME`, `CLAUDE_BIN`, `CLAUDE_CODE_SHELL*`, `CLAUDE_CODE_GIT_BASH_PATH`, `CLAUDE_CODE_PLUGIN_*DIR`, `DIRS`, `URL`, `CLAUDE_CODE_MANAGED_SETTINGS_PATH`, `CLAUDE_TEST_*`, `HYPERNOVA_*`, anything holding `HELPER`. Where data goes, and whose account: `*_BASE_URL`, `*_ENDPOINT*`, `*_PROXY*`, `NO_PROXY`, `AWS_ENDPOINT_URL*`, `NODE_TLS_REJECT_UNAUTHORIZED`, `NODE_EXTRA_CA_CERTS`, `NODE_USE_ENV_PROXY`, `NODE_USE_SYSTEM_CA`, `SSL_CERT_FILE`, `SSL_CERT_DIR`, `CURL_CA_BUNDLE`, `REQUESTS_CA_BUNDLE`, `*_CA_BUNDLE`, `*_CA_CERT(S)`, `CODEX_CA_CERTIFICATE`, `CODEX_EXEC_SERVER_URL`, `ANTHROPIC_CUSTOM_HEADERS`, `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `OPENAI_API_KEY`, `CODEX_API_KEY`, `AWS_CONFIG_FILE`, `AWS_SHARED_CREDENTIALS_FILE`, `GOOGLE_APPLICATION_CREDENTIALS`, `*_TOKEN_FILE*`, `*_FILE_DESCRIPTOR`, `CLAUDE_CODE_USE_*`, `CLAUDE_CODE_SKIP_*_AUTH`, `CLAUDE_CODE_API_*`, `CLAUDE_CODE_CLIENT_CERT*`, `CLAUDE_CODE_CERT_STORE`, `CLAUDE_CODE_REMOTE_*`, `CLAUDE_CODE_MCP_ALLOWLIST_ENV`, `MCP_PROXY_*`, `OTEL_*`, `CLAUDE_CODE_ENABLE_TELEMETRY`. A check switched off: `CLAUDE_CODE_DISABLE_*` holding `RM`, `DENY`, `TRUST`, `PERMISSION` or `SANDBOX`.

    **What doesn't hold.** A list of names can't be complete: a newer agent, or a tool of the owner's, can read a name that isn't on it, and that goes unguarded. Masking is a heuristic: `FOO=hunter2` is shown. A value on `NAME_WITH_KEY` is hidden whatever it is, flags aside. Managed settings aren't read, so an organization's value isn't shown as winning.
21. **Claude Code's settings files as raw JSON from a paired phone** (`daemon/src/agent-raw.ts`; `docs/PROTOCOL.md`, "JSON mode", 2026-11-02). This is the widest of the settings routes: the list screens offer named keys, and this takes the whole of three files, so a change to any key in them. `hooks` alone can run anything at every session. The same limit as 18 and 20: the fingerprint is the phone's, the server sees the claim (`confirmRun`), so a stolen token can send it. `~/.claude.json` isn't reachable: a file is a scope's name, never a path. Codex has no raw mode.

    **Every key's class, checked on the server by comparing the old document with the new, against the SDK's `Settings` type (0.3.296) and Claude Code 2.1.316's binary. A key unchanged is never judged.**

    - **Refused, confirmed or not, with the key named:** `permissions.defaultMode` set to anything but `plan`, `default`, `manual`, `dontAsk`, `acceptEdits`, `auto`; any key at any depth holding "dangerous" set to something other than false (`skipDangerousModePermissionPrompt`, `allowDangerouslySkipPermissions`, `dangerouslySkipPermissions`, `dangerouslyDisableSandbox`); `skipAutoPermissionPrompt`; an `env` entry on risk 20's refused list. Removing any of these is allowed and asks nothing. Undo that would restore one is refused.
    - **Fingerprint, by the direction it goes:** `permissions.allow` or `permissions.additionalDirectories` gaining an entry; `permissions.ask` or `permissions.deny` losing one; `permissions.defaultMode` moving up the order (risk 17's); any other key under `permissions`, such as `disableBypassPermissionsMode`. The opposite directions are plain. An `env` entry on risk 20's guarded list, set or removed.
    - **Fingerprint, any change: every other key not on the plain list below.** By name, because they run code or decide what may: `hooks`, `statusLine`, `subagentStatusLine`, `apiKeyHelper`, `proxyAuthHelper`, `otelHeadersHelper`, `awsAuthRefresh`, `awsCredentialExport`, `gcpAuthRefresh`, `processWrapper`, `policyHelper`, `fileSuggestion`, `defaultShell`, `mcpServers`, `managedMcpServers`, `allowedMcpServers`, `deniedMcpServers`, `enableAllProjectMcpServers`, `enabledMcpjsonServers`, `disabledMcpjsonServers`, `enabledPlugins`, `prependPlugins`, `appendPlugins`, `extraKnownMarketplaces`, `additionalMarketplaces`, `strictKnownMarketplaces`, `allowedMarketplaces`, `blockedMarketplaces`, `pluginConfigs`, `sandbox`, `disableAllHooks`, `disableAutoMode`, `disableSkillShellExecution`, `allowedHttpHookUrls`, `httpHookAllowedEnvVars`, `allowManaged*`, `strictPluginOnlyCustomization`, `worktree`, `claudeMd`, `claudeMdExcludes`, `autoMemoryDirectory`, `plansDirectory`, `sshConfigs`, `remote`, `remoteControlAtStartup`, `disableRemoteControl`, `channelsEnabled`, `allowedChannelPlugins`, `autoUploadSessions`, `crossSessionInbound`, `skipWebFetchPreflight`, `forceLogin*`, `gatewayInternalNetworks`, `allowedProviders`, `availableModels`, `modelOverrides`, `modelPricing`, `agent`, `prUrlTemplate`, `minimumVersion`, `requiredMinimumVersion`, `requiredMaximumVersion`. **And any key this list has never heard of**, so a newer Claude Code's next key asks by default. An `env` that isn't a table asks too.
    - **Plain, no fingerprint, because they only change how things look, which model or how much:** `PLAIN_KEYS` in `agent-raw.ts`: theme, editor mode, scroll and display settings, language, output style, spinner words, `model`, `fallbackModel`, `advisorModel`, `effortLevel`, `maxEffortLevel`, `modelSettings`, thinking and fast mode, prompt cache TTLs, `cleanupPeriodDays`, `respectGitignore`, `attribution`, `includeCoAuthoredBy`, `includeGitInstructions`, `skillOverrides`, `disableBundledSkills`, output caps, `autoUpdatesChannel`, memory and dream on or off, push notifications, voice, announcements, workflow and artifact on or off, `teammateMode`, `fileCheckpointingEnabled`, `autoContinueAtUsageLimit`. A plain env variable's change is plain too.

    **Secrets.** As in `docs/PROTOCOL.md`, "JSON mode". A placeholder can't move a secret: it's accepted only at the same path, where what's stored is itself hidden, so renaming a key to a name that isn't masked, or pointing a visible key at a hidden value, is refused. A secret in a confirm's words is the placeholder. Hidden values stay in the Undo's backup, 0600 in a 0700 folder.

    **What raw mode lets through that the list screens wouldn't.** With the fingerprint: hooks, the commands and helpers above, MCP servers, plugins and marketplaces, wide permission rules, and every key no screen offers, including ones Claude Code adds later. Without one: `autoUpdatesChannel: "rc"`, which the list doesn't offer, `maxEffortLevel`, and any plain key to a value Claude Code may ignore or refuse; and a whole-file save can reorder or reformat anything, since the text is the owner's. `permissions.defaultMode: "auto"` from an unset or lower mode needs the fingerprint as on the list. Moving off a refused value is allowed, as on the list.

    **What doesn't hold.** The plain list is a judgment and a key on it could grow a meaning. A secret with no sign of being one is sent, as everywhere (risk 20). A file's secret inside a hook's command that doesn't look like one is sent as part of that command. Swapping two hidden items in one list swaps their values.

## 9. Reporting

Report privately through the repository's Security tab on GitHub ("Report a vulnerability"), as
[`SECURITY.md`](../SECURITY.md) says: what you found, how to reproduce it, which version.
