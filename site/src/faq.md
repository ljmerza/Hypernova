---
title: Hypernova FAQ: Claude Code and Codex on your phone
description: Answers about Hypernova: what it is, what it costs, where your code goes, what you need to install it, Tailscale, Codex, updates and uninstalling.
h1: Frequently asked questions
lede: Short answers about Hypernova, the open-source control room for Claude Code and Codex, hosted on your Mac or a Linux server and used from your phone, the menu bar and Even G2 glasses.
---
## The basics

### What is Hypernova?

A control room for Claude Code and OpenAI's Codex. A service on your host watches every session.
The host is a Mac, with a menu bar app, or a Linux machine: a VPS, a home server, a PC or a Docker
container, with the `hypernova` command. The Android app, the Mac's menu bar and Even Realities G2
glasses show the sessions: live output, approvals to answer, questions, usage and limits. You can
start sessions, reply and approve from wherever you are. See
[Claude Code on your phone](/guides/claude-code-on-your-phone/).

### Is it free?

Yes. Hypernova is open source under the Apache License 2.0, Hypernova Lens included. You use your
own Claude plan or API key (or another provider, such as OpenRouter or your own gateway), or your
own Codex account.

### Is it stable?

It's alpha software. It's used every day, but expect rough edges and changes between versions. A
paired phone can drive the agents on your computer, so read
[SECURITY.md](https://github.com/prudnik-web/Hypernova/blob/main/SECURITY.md) before you pair one.

### Is Hypernova made by Anthropic, OpenAI or Even Realities?

No. Hypernova is an independent project and isn't affiliated with Anthropic, OpenAI or Even
Realities. Claude and Claude Code are products of Anthropic; Codex is OpenAI's.

## Privacy and security

### Do I need a Hypernova account or server?

No. There is no Hypernova account and no Hypernova server that carries sessions. Everything runs on
your own host, a Mac or a Linux machine. The only hosted piece is a small name service at
`hypernova.observer` for the pairing page and an optional private name for your host on your
Wi‑Fi; it never sees sessions, prompts, files, pairing codes or tokens.

### Does my code go through anyone else?

Sessions live on your host, the Mac or Linux machine. The phone reaches it directly on your Wi‑Fi
over mutual TLS, or over your own Tailscale network, where traffic between your devices is
end-to-end encrypted. Provider and Soniox keys stay on the host and are never sent to the phone. Your agent still talks to its own
provider, exactly as it does in a terminal. The
[security model](https://github.com/prudnik-web/Hypernova/blob/main/docs/SECURITY-MODEL.md) lists
what each outside party sees.

### Is voice sent anywhere?

Only to the speech service you choose in Setup's Voice step: [Soniox](https://soniox.com) with
your own key (the audio goes to Soniox only then), or Hyperscribe Companion if it's on your Mac.
With voice off, nothing is recorded, and on a Mac its own voices can still read replies aloud.

### What can a paired phone do?

What you can do at the keyboard: start sessions, send prompts, answer approvals and type into the
shared terminals. Files that usually hold secrets, such as `.env` or `*.pem`, are listed but never
read or downloaded. To pair another phone, click **Pair a phone** in the menu bar (or run
`hypernova pair` on a server); a phone you forget is cut off at once.

### Where do I report a bug or a security problem?

Bugs and ideas go to [GitHub issues](https://github.com/prudnik-web/Hypernova/issues). Security
problems don't: [SECURITY.md](https://github.com/prudnik-web/Hypernova/blob/main/SECURITY.md) says
how to report them privately.

## Install and requirements

### What do I need?

A host and a phone. The host is either a Mac with Apple silicon (M1 or later; Intel Macs aren't
supported) on macOS 14 or later, or a Linux machine on Ubuntu or Debian, x86_64 or arm64 (a VPS, a
home server, a PC, or Docker). The phone is Android 10 or later. On the host you need Claude Code,
Codex or both. Tailscale and Even G2 glasses are optional. The full lists are in the
[install guide](/install/#what-you-need) and [Hypernova on Linux](/install/linux/).

### Is there an iPhone app?

Not today. Hypernova is Android only for now. An iOS client is on the list of ideas parked for later,
with no date.

### Can I use it without a Mac?

Yes. The host can be a Linux server, a VPS, a home PC or a Docker container, with the `hypernova`
command instead of the menu bar, and the phone pairs with it the same way. See
[Claude Code on a VPS or home server](/guides/claude-code-on-a-vps/) and
[Hypernova on Linux](/install/linux/).

### Why does macOS warn me the first time I open it?

Hypernova is open source and signed on the machine that built it, but it isn't notarized by Apple,
so macOS stops it the first time. You allow it once; the steps for each macOS version are in
[the first launch](/install/#the-first-launch).

### Do I need Tailscale?

Not on the same Wi‑Fi: the phone reaches your Mac or home server directly. To reach the host from
anywhere else, and always for a VPS, yes: Hypernova carries its own Tailscale node, so a free
Tailscale account is enough and no Tailscale app is needed on the host. See
[Remote access with Tailscale](/guides/claude-code-remote-access-tailscale/).

### How do updates work?

On a Mac, download the new `Hypernova.dmg` and replace the app; the menu bar then offers to update
the service, and shared terminals keep running. On Linux, run the install script again with the new
tarball. A Mac hands the phone the newer app, which updates itself when it's signed with the same key;
anywhere, you can install a newer `Hypernova.apk` over the old one. See [Update](/install/#update).

### How do I uninstall it?

On a Mac, open Settings → **Uninstall…** in the menu bar: it removes the hooks, the shell block, the
service and its login items, then you move the app to the Trash. On Linux, run `hypernova
uninstall`. Claude Code and Codex stay as they are. See [Uninstall](/install/#uninstall).

## Agents and usage

### Does it work with Codex?

Yes, with Codex 0.163.0 or later. Hypernova connects to Codex's own app server while **Use Codex
with Hypernova** is on, and never installs or updates Codex for you. A conversation can switch
between Claude Code and Codex. See
[Claude Code and Codex together](/guides/claude-code-and-codex-together/).

### What happens if I don't answer an approval?

If nobody answers within 10 minutes, the request is denied by itself, and Claude is told you're
probably away, so it stops and waits instead of working around it. The session then shows a
**Continue** button for when you're back.
