---
title: Codex on your phone: a remote for the Codex CLI | Hypernova
description: Start, follow and approve OpenAI Codex sessions on your Mac or Linux server from an Android phone, next to Claude Code or on its own. Free and open source.
h1: Codex on your phone
lede: Hypernova works with OpenAI's Codex as well as Claude Code. Start a Codex session from your Android phone, follow it live and answer its requests while it runs on your Mac.
shot: phone-new-session
shotAlt: The new-session sheet in Hypernova on Android: an empty prompt box, then chips for the project, the model and the permission mode, a microphone and Start
related: claude-code-and-codex-together, claude-code-on-your-phone, claude-code-usage-limits
---

Codex, like any coding agent in a terminal, works best when it can run for a while, and it still
needs you now and then: a command it wants to run, a file it wants to change, a question. Hypernova
gives the Codex CLI on your Mac or Linux server a phone: you can start a session from the couch, watch it work and
answer it from wherever you are.

Codex is a full agent in Hypernova, not an add-on. It can sit next to Claude Code, with each one's
sessions and usage side by side, or be the only agent on your Mac or Linux server.

## How Hypernova talks to Codex

Hypernova doesn't run a Codex of its own. Codex has its own shared app server on the computer it runs on (your Mac or Linux server), and
Hypernova's service is one client of it, only while **Use Codex with Hypernova** is on. Your Codex
sessions keep running when Hypernova restarts, and Codex keeps its own history and its own login.

Hypernova never installs or updates Codex for you. It needs **Codex 0.163.0 or later**; later
versions are checked at connection time and used when they still speak the same protocol.

## What a Codex session looks like on the phone

A Codex session is a Codex thread, shown the way Claude Code's sessions are: your messages, the
replies as they stream, the reasoning as a quiet row, commands as small live terminals, and file
changes as diffs.

- **Two modes**, Codex's own sandboxes. **Read only** reads and looks around, and asks before it
  changes anything. **Can edit** edits files and runs commands in the session's folder, and asks
  before it goes further.
- **Approvals.** Commands, file changes, more access (the network, more folders) and questions
  arrive as cards to **Allow** or **Deny**. If the session is also open in a terminal, the first
  answer wins. With no answer in 10 minutes the request is declined and the turn stops.
- **Models and effort.** The new-session sheet and the model picker list Codex's own models with
  their reasoning effort, and the model set in your `~/.codex/config.toml` is marked as the default.
- **Usage.** Codex's own 5-hour and weekly windows show on Home in a **Codex usage** card. With both
  agents in use, Codex is a second, smaller star in the sky with its sessions around it.

## Codex in your terminal, shared

Once Codex is on for Hypernova, a `codex` function joins `claude` in `~/.zshrc`. A `codex` you start
in Terminal then runs in a shared terminal: your phone can follow it live and send it prompts,
which show up in the terminal as they go, and `hn attach <id>` joins it from any other terminal.
Other subcommands go straight to the real `codex`, and so does everything while Codex is off for
Hypernova.

## How to set it up

1. Install Codex on the computer that runs Hypernova (your Mac, or your Linux server) and sign in:

   ```sh
   npm i -g @openai/codex        # or: brew install --cask codex
   codex login                   # on a server without a browser: codex login --device-auth
   ```

2. Install Hypernova for Mac and open **Setup**. In the **Codex** step, turn on **Use Codex with
   Hypernova**. On a Mac without Claude Code, choose **Use Codex only** in the first step instead.
   On a Linux server, run `hypernova codex on` (and `hypernova codex` to see what it found).
3. Install the Android app and pair it with the code Setup shows.
4. On the phone, start a new session. Where both agents are installed, pick Codex as the agent; with
   Codex alone, sessions go to Codex.

You can also turn Codex on later from **Agents & providers** on the Mac or the phone, or simply by
starting a Codex session from the phone. Details: [the Codex step](/install/#6-codex) and
[Claude Code and Codex](/install/#claude-code-and-codex) in the install guide.

## FAQ

### Do I need Claude Code to use Codex with Hypernova?

No. A computer with Codex alone works: on a Mac, Setup offers **Use Codex only** and skips Claude Code's steps; on a Linux server, `hypernova codex on` does it. New
sessions, automations and prompts from the glasses go to Codex.

### Can I switch a conversation between Codex and Claude Code?

Yes. A conversation can go on with the other agent in a new session in the same folder, carrying
over the recent messages, the whole conversation or nothing. See
[Claude Code and Codex together](/guides/claude-code-and-codex-together/).

### Is there an "Always" answer for Codex?

Not yet. Codex approvals are Allow or Deny. Rewind, Claude Code's providers and "continue after the
limit" are Claude Code's only.

### Will Hypernova show the Codex threads I started in the Codex app?

It lists the Codex threads it started or saw in a shared terminal. Threads started elsewhere are
not listed yet.

### Does it cost anything?

Hypernova is free and open source under the Apache License 2.0. Codex runs on your own ChatGPT plan
or API key; signed in with an API key, every session costs money, and the app says so. More in the
[FAQ](/faq/).
