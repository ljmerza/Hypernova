---
title: Approve Claude Code permissions from your phone | Hypernova
description: Answer Claude Code's permission prompts from an Android phone: Allow, Deny or Always, from the app or the notification, with a 10-minute auto-deny.
h1: Approve Claude Code permissions from your phone
lede: When Claude Code stops to ask before a command or an edit, the question comes to your Android phone. Allow it, deny it or answer it there, and the session carries on.
shot: phone-chat
shotAlt: A session in Hypernova on Android: an edit shown as a diff, a build's output in a small terminal, and a NEEDS YOU card asking to run git push origin main, with Deny, Always and Allow
related: claude-code-on-your-phone, claude-code-on-even-g2-glasses, claude-code-remote-access-tailscale
---

In its default mode Claude Code asks before it edits a file or runs a command. That is the right
default, and it is also why a long task often sits still for an hour: it asked a question while you
were away from the keyboard. Hypernova brings those permission prompts from your Mac or Linux
server to your phone, so you can approve Claude Code from wherever you are and keep your guard rails
on.

## What an approval looks like

When a session needs you, its card on Home turns amber and says **NEEDS YOU**, and the request
opens as a card in the chat: the tool (`BASH`, an edit, a web fetch), a line on why, and the exact
command in a well, for example `$ git push origin main`. Under it are three buttons:

- **Allow** runs it this once.
- **Deny** refuses it, and Claude Code is told so.
- **Always** is Claude Code's own "Yes, and don't ask again" for this. The phone asks for your
  fingerprint or screen lock first.

Web and file-read requests can also be allowed for the rest of the session in one answer ("Allow
all web pages this session"), which helps when a research task fetches many sites. Commands and
edits stay one at a time, or go with a mode.

Claude Code's questions (multiple choice) and plans arrive the same way: pick an answer, or
**Approve plan** or **Keep planning**.

## From the notification

You don't have to open the app. An approval arrives as a notification with **Deny**, and **Allow**
when the whole command fits in it. Both ask you to unlock the phone first, and the lock screen shows
neither the command nor the buttons, only who is asking. Notifications for a finished turn have a
**Reply** field, so you can send the next message from there too.

On a Mac the same approval also shows in the menu bar, and on Even Realities G2 glasses if you use
[Hypernova Lens](/guides/claude-code-on-even-g2-glasses/). For a session running in a shared
terminal, the prompt is in that terminal as well. The first answer wins, wherever it
comes from, and the others clear.

## When nobody answers

If a request waits **10 minutes** with no answer, Hypernova denies it and tells Claude Code that you
didn't respond, are probably away from your phone, and that it should not retry or work around it,
but stop and wait. When you are back, the session shows a **Continue** button that tells it so.
Nothing runs that you did not see.

## Modes, and thinking twice

You can change a session's permission mode from the phone: **Ask**, **Edits** (edits files on its
own, asks before commands), **Plan**, **Auto** (decides on its own and asks only when something
looks risky) and **Bypass** (never asks, not even before risky commands).

Choosing Auto or Bypass first opens a think-twice card: a real, sourced incident where an AI agent
with too much freedom did damage, and the line "AI makes mistakes. Never rely on it as on something
that can't." **Keep asking** is the default. Switching to Bypass also needs your fingerprint or
screen lock, and a session started in a terminal without bypass allowed can't be switched to it.

## How to set it up

1. Install Hypernova on your Mac (Apple silicon, macOS 14 or later) and follow Setup. The step
   **Connect Claude Code** adds the hooks that send permission requests to the service on
   `127.0.0.1`. On a Linux server, the install script adds the same hooks
   ([Hypernova on Linux](/install/linux/)).
2. Install the Android app (Android 10 or later) and pair it with the code Setup shows, or with
   `hypernova pair` on Linux.
3. Allow notifications when the app asks.
4. Start Claude Code as usual. The next permission prompt shows on your phone.

Step by step: [install Hypernova](/install/#setup).

## FAQ

### Can I approve Claude Code commands from the lock screen?

The notification's Allow and Deny need the phone unlocked first, and the lock screen shows only
that a session needs you, not the command. That keeps a stranger with your phone from approving
anything.

### What does "Always" do?

It allows the request and adds Claude Code's suggested permission rule, as answering "Yes, and don't
ask again" in the terminal does. Because it changes what Claude Code may do without asking, the
phone asks for your fingerprint or screen lock first.

### What happens if I miss a request?

After 10 minutes it is denied with a message to Claude Code to stop and wait for you. Open the
session and tap **Continue** when you are back.

### Does answering on the phone conflict with the terminal?

No. The request is shown in both places and the first answer wins; the other one resolves by
itself.

### Is it safe to expose this to the internet?

Nothing is exposed. The service listens on the computer's loopback and is reached over your Wi‑Fi with
mutual TLS or through your own tailnet, with a per-device token from pairing. The
[security model](https://github.com/prudnik-web/Hypernova/blob/main/docs/SECURITY-MODEL.md) has the
details.
