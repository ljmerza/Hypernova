# Hypernova

Hypernova lets you run Claude Code agents on your own Linux server and drive them from an Android
phone. You approve or deny what they want to do, answer their questions, start new sessions and
follow along, from the couch or from anywhere you have a VPN into your network. Nothing goes
through a cloud service of ours, the phone talks straight to your server.

[Releases](https://github.com/ljmerza/Hypernova/releases/latest) ·
[Install guide](docs/INSTALL.md) ·
[Linux server](docs/LINUX.md) ·
[Protocol](docs/PROTOCOL.md)

## Install

On the server (Ubuntu or Debian, x86_64 or arm64), as the user who'll run the agents, not root.

```bash
curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
```

That installs the system packages it needs (it asks for sudo), Node.js, the Hypernova service and
Claude Code. Then sign in to Claude Code once and pair your phone.

```bash
claude
hypernova pair
```

Install the app from the [latest release](https://github.com/ljmerza/Hypernova/releases/latest)
(`hypernova-<tag>.apk`, Android 10 or later) and scan the QR code `hypernova pair` shows.
`hypernova doctor` checks every piece if something doesn't work.

To update the server run the same `curl` line again. Docker and the manual steps are in
[`docs/LINUX.md`](docs/LINUX.md).

## What you can do from the phone

- See every session grouped by what needs you, what's working and what's done, with Allow and Deny
  right on the card
- Answer Claude Code's own permission prompts and questions, nothing gets auto-denied
- Send messages while an agent is busy, they're queued and sent when it's ready
- Open the live terminal of any session
- Change the mode and effort of a running session, run slash commands and ask `/btw`
  questions from the session's details
- Start new sessions in any project folder
- Search the full text of past sessions
- Run automations on a schedule, use several servers, and dictate with the phone's own voice
  recognition

Claude Code's background agents (`claude agents`) show up as sessions too: on by default, and
Settings → Agents & providers → Claude Code in the app turns it off.

## Privacy

The phone API only listens on the server itself and is reached through a mutual-TLS listener on
your network (port `47323`), with a certificate and token per paired phone. Provider keys never
leave the server and voice stays on the phone. The full threat model is in
[`docs/SECURITY-MODEL.md`](docs/SECURITY-MODEL.md).

## Develop

```bash
cd daemon && npm ci && npx tsc -p . && npm test       # the server
cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest   # the app (JDK 21, Android SDK)
```

[`CLAUDE.md`](CLAUDE.md) explains how to run a second server next to a live install for testing.

## License

Apache License 2.0, see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE). Claude and Claude Code are
products of Anthropic, Hypernova isn't affiliated with Anthropic.
