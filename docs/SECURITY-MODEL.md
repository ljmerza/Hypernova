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
| Tailscale | the Mac, the phone (its own app or Hypernova's built-in node) | The route from outside the local network. |

There is no Hypernova account and no Hypernova server that carries sessions.

## 2. What data exists where

**The Mac** (the only place sessions live). The support folder is
`~/Library/Application Support/Hypernova` (Linux: XDG data folder), `0700`, each secret `0600`:

- Claude Code's own data in `~/.claude`: transcripts, settings, its login. Hypernova reads
  transcripts to show sessions; it does not move them anywhere except to paired devices (below).
- Hypernova's state: the device list (`devices.json`: for each phone its name, the SHA-256 of its
  token, the fingerprint of its certificate), settings, session records and titles.
- Attachments the phone uploaded (`attachments/`, kept 30 days).
- Tokens and keys: the **admin token**, the **hook secret**, TLS keys (`tls/`: the server key and the
  device CA), Hypernova's own Tailscale node key (`tsnet/`), provider keys, automations'
  **fire tokens** (only their SHA-256, in `automations.json`) and their webhook outputs' addresses
  and bot tokens (`automation-secrets.json`, never logged, never sent back).
- The daemon log (`~/Library/Logs/Hypernova/daemon.log`, `0600`, in a `0700` folder): names devices,
  projects and events; never tokens, codes or keys (see `SECURITY.md`).

**The phone.** Its device token (the daemon keeps only its hash), the Mac's base URL and name, and
sessions' text as the app shows it. The token sits in the app's private preferences, not the
Keystore (**unverified** beyond reading `data/Pairing.kt`). The device certificate's private key is
made on the phone and stays in the Android Keystore. Hypernova's own Tailscale node keys are in the
app's private files, excluded from backup and device transfer (`allowBackup=false`). Downloaded files
go where the user saves them.

## 3. What each outside party sees

| Party | Sees | Does not see |
| --- | --- | --- |
| Anthropic | What Claude Code sends it, exactly as when the user runs Claude Code in a terminal: prompts (including ones typed on the phone), the files Claude reads, usage. The usage probe runs Claude Code itself to read the subscription's limits (**unverified**: not re-read for this page). | Anything about Hypernova's phone or pairing. Hypernova makes no calls to Anthropic's API of its own. |
| Tailscale | The usual coordination data: node keys, addresses, device names, the account. Traffic between the phone and Mac is WireGuard, end to end; a relay sees ciphertext (Tailscale's own documented behaviour, **unverified here**). | Message contents. |
| A provider (OpenRouter, a gateway) | What a session on that provider sends, with its own key. | Other sessions. |
| nodejs.org, Anthropic's installer, GitHub (setup) | Downloads the user asks for (Node.js is checked against its published checksums). | -- |

No name service and no DNS record: nothing about the computer or its pairing is published.

## 4. Every channel

| Channel | Where | Who can connect | How it is protected |
| --- | --- | --- | --- |
| Loopback phone API | `127.0.0.1:47321`, plain HTTP | Programs on the machine | Bearer device token on every `/v1` route except `health`, `pair` and a terminal's ticketed WebSocket. No CORS headers, so a web page can't read it. Requests that came through a proxy are recognised by their forwarding header and never treated as local. |
| Local API | `127.0.0.1:47322` | Programs on the machine | The **admin token**; hooks use `/hooks/<secret>` (compared in constant time). Never behind the LAN listener or Tailscale. The checklist page has its own key. |
| The fire route | `POST 127.0.0.1:47322/local/automations/<id>/fire` | Programs on the machine (git hooks, cron, CI, `hypernova automation fire`) | That automation's own **fire token** or the admin token, checked before the body is read. A wrong token is 401 alike whether the automation exists, isn't a webhook's or has another token, so nothing tells which ids exist. 64 KB of text at most; 10 fires a minute per automation, then 429 with nothing recorded. The only `/local` route that takes another token than the admin one. A paired device fires the same way over its own routes. |
| LAN listener | `0.0.0.0:47323`, HTTPS, mutual TLS | Phones on the same network or hotspot | Sources outside private ranges are dropped before TLS. The phone pins the daemon's self-signed certificate (fingerprint from the pairing link) and presents a device certificate the daemon signed for it (ECDSA P-256, 2 years, revoked at once when the device is forgotten). Without one only `health`, `pair`, `device/cert` and the app's download page answer. Per-source and total connection caps, handshake and header timeouts; pairing closed unless a code is shown, 5 wrong codes per source per 10 minutes. Can be turned off (`lanAccess`; off by default in containers and on cloud VMs). |
| Tailscale (the app's `serve`, or Hypernova's built-in node) | `https://<mac>.<tailnet>.ts.net:47321` | Devices in the user's tailnet | Tailscale's WireGuard and ACLs, then the same bearer token. Never Funnel, never a public interface. Without tailnet HTTPS certificates the node serves the daemon's pinned certificate. The helper strips any forwarding headers a client sent. |
| The pairing link | `https://<address>:47323/pair?c=…` (QR), the server's own page on the LAN listener | Anyone on the network who sees the QR code while it is valid | The page answers only for a code being shown and hands the app the address the browser came in on (never a header's). See codes below. |
| App update | `GET /download/android/Hypernova.apk` over the app's own route | Anyone who reaches a listener, without a token | The APK is public by design. Android checks it: same package, newer version, same signing key. |

A phone's browser reaches the pages by address, with the server's own certificate (it warns once);
the app pins that certificate.

## 5. (Removed: the Lens bridge)

The glasses app and its bridge on the phone were removed on 2026-10-30 (docs/DECISIONS.md, "The
glasses are removed"). The section numbers after it are kept.

## 6. Who can do what with what

| Credential | Held by | What it opens | Lifetime and revocation |
| --- | --- | --- | --- |
| **Admin token** | The `hypernova` command, on the server | The local API (setup, pairing, settings, forgetting devices, the service) | Per install; anyone who can read the support folder has it. Not valid on the LAN listener or over Tailscale. |
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
(start a session in Bypass). The daemon itself asks for them on every save that needs them,
including a save that keeps the steps as they were.

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
  the private ranges, carrier-grade NAT and the tailnet (100.64/10), link-local and cloud metadata
  (169.254.169.254, `fd00:ec2::254`), unique-local, multicast, reserved and documentation ranges,
  IPv4 inside IPv6 judged as IPv4. A name is judged after DNS, by every address it has, and the
  connection is pinned to the addresses judged (DNS rebinding changes nothing); no redirect is
  followed; 10 s; the answer's body is never read. To send to a self-hosted ntfy on the local
  network or the tailnet, add its host, as written in the URL, to `webhookPrivateHosts` in
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
- **Limits** hold whatever fires them: one run at a time per automation, 2 working and 10 waiting in
  all, 24 runs a day per automation (200 for one that only tells), 10 fires a minute per webhook
  trigger, and the per-run budget.

## 7. What never leaves the Mac

- Claude Code's login and its credentials.
- Provider keys: never logged, never sent to the phone (it sees only "has a key"), and
  they go only to their own provider.
- The admin token, the hook secret, the TLS keys, Hypernova's Tailscale node key.
- Automations' fire tokens (only their hashes are kept) and webhook outputs' addresses and bot
  tokens: a webhook's secret goes only to its own host.
- Transcripts, attachments and files: they leave the computer only to a paired device, over the routes above,
  and (for Anthropic) as Claude Code itself sends them.
- The pairing details: only to the server's own pairing page on the local network.

## 8. Known residual risks

1. (Removed with the glasses, 2026-10-30: a squatter on the Lens bridge port.)
2. (Removed with the glasses, 2026-10-30: preview tokens and Telescope picture names on the bridge.)
3. **The LAN listener's uncertified pages.** Anyone on the same network can reach, without a device
   certificate: the app's download page and APK, `GET /v1/health` (which includes the computer's name),
   `POST /v1/pair` (closed when no code is shown, 5 wrong codes per source per 10 minutes) and
   `POST /v1/device/cert` (needs a paired device's token), and a shown code's pairing page. The download
   page's certificate is self-signed at the IP address (`hypernova pair` shows the checksum).
4. (Removed with the glasses, 2026-10-30: a relay on the Lens bridge.)
5. **Tokens at rest.** Device tokens on the phone, and the Mac's admin token and provider keys, are
   plain private files or preferences, not the Keychain or Keystore (LATER L30 for the keys). Anyone who
   already runs code as the user on the Mac or the phone has them.
6. (Removed with the name service, 2026-10-30: the private name in Certificate Transparency logs and DNS.)
7. **A lost phone is a lost unlocked keyboard** until it is forgotten on the Mac. Tailscale key expiry
   and its ACLs are the user's to set.
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

Out of scope: someone already running code as the user on the machine, Claude Code's own permission
rules, and weaknesses in Tailscale or the operating system.

## 9. Reporting

Report privately through the repository's Security tab on GitHub ("Report a vulnerability"), as
[`SECURITY.md`](../SECURITY.md) says: what you found, how to reproduce it, which version.
