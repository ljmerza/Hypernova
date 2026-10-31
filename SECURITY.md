# Security

Hypernova is alpha software (see the README). This page says what it can do to your computer,
what listens where, how it protects itself and how to report a hole. The full wire contract is in
[`docs/PROTOCOL.md`](docs/PROTOCOL.md); [`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md) is the
threat model (what runs and is stored where, who sees what, every channel and credential, the known
residual risks); where this page and the code disagree, the code wins and
this page has a bug, so please tell us.

## What you are trusting it with

Pairing a phone with your computer gives that device **the same power over
Claude Code (and Codex, once you turn it on for Hypernova) that you have at the keyboard**:

- it can start sessions in any project folder, send prompts, and answer approvals
  (allow, deny, "always") for every session on the machine;
- it can **type into the live terminals** that Hypernova runs (and into the agent's input box),
  which means anything the agent may do on your behalf: run commands, edit and delete files,
  push code, spend usage or provider credits;
- it can read what sessions show, browse project folders and see the changes the agent made. Files
  that usually hold secrets (`.env`, `.npmrc`, `*.pem`, `*.key`, `~/.ssh`, `~/.aws`, and similar) are
  listed but never read, shared or downloaded (`daemon/src/secretfiles.ts`).

So a stolen or malware-infected paired phone is as serious as a stolen unlocked laptop. Forget a
device (`DELETE /local/devices/{id}` on the local API) and it is cut off at once, even on a
connection that is already open. Pair only phones you own, and keep Tailscale's own access
controls tight.

Hypernova does not try to defend against someone who already runs code as you on the computer:
that person can read the admin token and the provider keys directly. It also does not make Claude
Code or Codex safer than they are: approvals, "Bypass" mode, Codex's sandbox and the permission
rules are the agents' own, with a few extra guard rails (risky commands such as force pushes, `rm -rf` and deploys need their
own spoken verb; automations never use paid credits without your yes).

There is no Hypernova server. Nothing is sent to us. Dictation is done by the phone's own on-device
recognizer, so the audio never leaves the phone; provider keys go only to their own provider.

## What listens where

| Listener | Address | Who can reach it | What it serves |
| --- | --- | --- | --- |
| Phone API | `127.0.0.1:47321`, plain HTTP on loopback | Only this computer, plus your tailnet through `tailscale serve` (HTTPS with Tailscale's certificate). Never Tailscale Funnel, never the open internet | The `/v1` API, and the pairing, download and preview pages |
| LAN listener | `0.0.0.0:47323`, HTTPS with mutual TLS (turn it off with the `lanAccess` setting) | Phones on the same network or hotspot | Exactly the phone API; never the Mac's local API |
| Local API | `127.0.0.1:47322`, loopback only | The `hypernova` command and Claude Code's hooks, on this computer | Setup, status, pairing, the testing checklist, `/hooks/*` |

Details that matter:

- **Phone API.** Every `/v1` route except `GET /v1/health`, `POST /v1/pair`
  and a terminal's WebSocket (which carries a short-lived ticket) needs the device's bearer token.
  Requests that arrive through `tailscale serve` are recognised by their forwarding header and are
  never treated as local.
- **LAN listener.** Connections from outside private ranges (loopback, RFC 1918, link-local, the
  tailnet's 100.64/10, IPv6 ULA) are dropped before any TLS. The phone pins the daemon's own
  self-signed certificate, and the listener asks for a **device certificate** that the daemon signed
  for that phone; without one it answers only `health`, `pair` and the certificate request. It has
  connection and time limits against a hostile Wi-Fi (32 connections per source, 64 in all,
  handshake in 10 s, headers in 15 s). Pairing on it is closed unless a code is being shown, and a
  source gets 5 wrong codes per 10 minutes.
- **Local API.** Needs the **admin token**. The local port never sits behind the LAN
  listener or `tailscale serve`, and the admin token counts for nothing on the LAN listener. The
  testing checklist page has its own key and refuses requests from other sites.
- **Hooks.** Claude Code's hooks post to `/hooks/<secret>` on the local API; any other path is a
  404. The secret (`hook-secret`) is in the installed hook configuration, so it is as private as
  your Claude Code settings.

## Secrets: what they are, where they live

Everything lives in the support folder (`~/.local/share/hypernova`, XDG's data folder),
created `0700`, with each secret file `0600`.

- **Device tokens.** Made at pairing, 256 bits of randomness, handed to the phone once. The daemon
  keeps only the SHA-256 of each (`devices.json`) and compares in constant time.
- **Pairing codes.** 16 random bytes, valid once and for 10 minutes, kept only as a hash in
  memory. The QR code is a live credential for those 10 minutes: don't post a screenshot of it.
- **Device certificates.** The daemon keeps its own server key and device CA in `tls/` (ECDSA
  P-256). The phone's private key never leaves the phone (Android Keystore); the daemon keeps only
  the fingerprint of each device's certificate. One active certificate per device; a new one
  replaces the old at once.
- **Admin token** (`admin-token`) and **hook secret** (`hook-secret`): random, per install, used
  by the `hypernova` command and the hooks. Anyone who can read them can drive the local API, which is why
  the folder is private to your user.
- **Provider keys.** Stored in the support folder, readable by your user only. They are
  never sent to the phone (the phone sees only "has a key"), and only go to their own provider.
  Treat the support folder as secret.
- **Never logged** (`~/.local/state/hypernova/logs/daemon.log`): tokens, pairing codes, the admin token,
  provider keys, the text read aloud, Claude sign-in codes, and the output
  of Claude's account commands. Tests check several of these (for example "the key and the text
  never reach the log"). The log does name devices, projects and what happened, so look through it
  before attaching it to a public issue.

## Reporting a vulnerability

Please report privately, not in a public issue:

1. Open the repository's **Security** tab on GitHub and choose **Report a vulnerability**
   (GitHub's private security advisories).
2. Say what you found, how to reproduce it, and which version or commit. A short proof is plenty.

You will get an answer as soon as the maintainer can give one; this is a small project, so
please allow a few days. Please give us time to fix it before you publish, and don't test against
other people's computers. We will credit you in the fix unless you prefer not.

Good reports for this project include: reaching the phone API or the local API without a valid
token or certificate, reaching the LAN listener from outside the allowed ranges, a pairing code
that can be guessed or reused, a secret appearing in a log or in a response meant for a phone,
reading a file the phone should not read, and a web page that can drive the local API from a
browser.

Out of scope: attacks that already need code running as your user on the computer, a paired
device doing what pairing allows, and weaknesses in Claude Code, Tailscale or your operating
system themselves (report those upstream).
