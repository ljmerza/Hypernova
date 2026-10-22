---
title: Claude Code and Codex together, switching mid-chat | Hypernova
description: Run Claude Code and OpenAI's Codex side by side from your phone: each with its own sessions and usage, and switch a conversation from one agent to the other.
h1: Use Claude Code and Codex together
lede: Hypernova treats Claude Code and OpenAI's Codex as equals. Sessions, approvals and usage for both live in one app, and a conversation can move from one agent to the other without starting over.
shot: phone-home
shotAlt: Hypernova's Home: the leading agent's 5-hour window as the sun in the middle of the sky, projects as planets on their orbits with what their sessions are doing, and session cards below
related: codex-on-your-phone, claude-code-usage-limits, claude-code-on-your-phone
---

Many people keep both coding agents around: Claude Code for some work, Codex for other work, or
one while the other's limit refills. Juggling two terminals and two sets of limits from a phone is
the hard part. Hypernova runs on your Mac, follows both agents there, and gives each the same
treatment on your phone, in the menu bar and on your glasses.

## Two agents as equals

Either agent alone is enough. When both are on your Mac and Codex is turned on for Hypernova:

- **One sky, two stars.** The leading agent is Claude Code when it's installed, otherwise Codex.
  Its 5-hour window is the sun. The other agent, while in use, is a second, smaller star in a calmer
  palette at the edge of the sky, with its own sessions around it and a short label such as
  `CODEX 61%`.
- **Separate usage.** Each agent keeps its own meters: "Claude usage" and "Codex usage" when both
  are in use, plain "Usage" when only one is. Codex's windows are the ones Codex reports for your
  plan.
- **Pick the agent per session.** The new-session sheet offers the choice once both agents are
  there. A session with no agent named goes to the leading one.
- **The same approvals.** Codex asks for commands, file changes and more access (the network, more
  folders) as approval cards, answered with Allow or Deny as Claude's are. Whichever answers first
  wins, the terminal included. Nobody for 10 minutes means no.
- **Shared terminals for both.** Once Codex is on, `codex` typed in a terminal runs in a shared
  terminal, as `claude` does, so your phone can watch it and type into it.
- **Statistics count both** agents' work.

Codex sessions use Codex's own modes, **Read only** and **Can edit**, and its own models and
reasoning effort. Hypernova is one client of Codex's own shared app server, so your Codex sessions
keep running when Hypernova restarts.

## Switch a conversation mid-chat

A conversation can go on with the other agent. Open a session's panel and tap **Switch to Codex**
(or **Switch to Claude** in a Codex session). Then choose what to carry over:

| Carry over | What the other agent starts with |
| --- | --- |
| **Recent** | The last 20 messages, yours and the agent's, with a line for each tool between them |
| **Entire** | The whole conversation, across earlier switches, oldest first |
| **None** | Nothing: the other agent starts fresh |

You read the text and can edit it before you confirm, and you pick how the other agent starts
(Codex's model, effort and mode, or Claude's mode). The other agent then starts a new session in
the same folder, with that text as background, and does nothing until your next message.

The two sessions stay linked: the new one says "Continued from" the old one, the old one says
where it went, and Home shows the new one. If the old session's shared terminal is open on the Mac
or a phone, it follows to the new session. A session still at work offers **Stop and switch**.

## Automations on either agent

An automation's start step can run on Codex, in its sandbox. In an automation Codex asks nobody:
what its mode doesn't allow, it doesn't do. When Codex is signed in with an API key, the app says
that every run costs money. Limit, reset and hook triggers are Claude Code's for now.

## How to set it up

1. Install Codex 0.163.0 or later in a terminal and sign in:

   ```sh
   npm i -g @openai/codex        # or: brew install --cask codex
   codex login
   ```

2. [Install Hypernova](/install/) and run Setup. With Codex only, choose **Use Codex only** in
   its first step.
3. In Setup's **Codex** step, turn on **Use Codex with Hypernova**. You can also do it later in
   **Agents & providers…**, or by starting a Codex session from the phone.
4. Open a new terminal tab, so `codex` runs in a shared terminal from now on.
5. On the phone, start a session and pick Codex, or switch an existing conversation from its panel.

See [Claude Code and Codex](/install/#claude-code-and-codex) in the install guide for what Setup
does on a Mac with one agent, the other or both.

## FAQ

### Does Hypernova install or update Codex for me?

No. Hypernova never installs or updates Codex. Setup shows what it found, its version and the
lines to install it; `codex update` brings an older one up to date.

### What does a switch carry over?

What you choose: the recent part of the conversation, all of it, or nothing. You see the text and
can edit it before the other agent starts.

### Can I switch back?

Yes. A Codex session offers **Switch to Claude**. Each switch makes a new session linked to the one
before, so the whole conversation stays easy to follow.

### Do I need both agents?

No. Hypernova works with Claude Code alone, Codex alone, or both. A Mac with only Codex gets no
Claude Code cards or reminders.

### Which agent's usage does the sun show?

The leading agent's: Claude Code where it's installed, otherwise Codex. The other agent's usage is
the second star and its own meters.
