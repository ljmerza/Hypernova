---
title: Claude Code on a VPS or home server | Hypernova
description: Why an always-on Linux host for Claude Code helps, and how a VPS, a home server or Docker pairs with your Android phone through self-hosted Hypernova.
h1: Claude Code on a VPS or home server, reachable from your phone
lede: Your laptop sleeps, closes and travels. An always-on Linux host doesn't, so the sessions you start from your phone keep running there until they need you.
shot: phone-new-session
shotAlt: The new-session sheet on the phone: a prompt box asking What should Claude do?, then the project, the model and the permission mode as chips, a microphone and a Start button
related: run-claude-code-on-a-linux-server, claude-code-remote-access-tailscale, claude-code-on-your-phone
---
Claude Code does its best work on long jobs: a refactor, a test run that takes a while, a build.
On a laptop, those jobs stop when the lid closes. Hypernova's host doesn't have to be a laptop. It
can be a Mac with the menu bar app, or a Linux machine that stays on: a VPS, a home server, a spare
PC, or a Docker container. The Android app pairs with either the same way.

This page is about when an always-on host makes sense and what it changes. The commands are in
[Run Claude Code on a Linux server](/guides/run-claude-code-on-a-linux-server/).

## Why an always-on host

- **Sessions keep running.** Start a job from your phone, put the phone away, and it carries on
  whether your laptop is open, asleep or at home. You come back to it when it needs you, not when
  your laptop is awake.
- **One place for your agents.** Every `claude` you run over SSH (in bash or zsh), and every
  session you start from the phone, runs in a shared terminal on that one machine. The phone sees them all, and `hn attach`
  joins any of them from a terminal.
- **No Mac needed.** If your computer isn't a Mac, a Linux host is how you use Hypernova at all.

A Mac can do much of this too: Hypernova's **Stay awake** keeps it working while sessions run, and
Backpack keeps it awake with the lid closed. A server simply stays on by itself.

## What you give up on a server

The Mac's own features are switched off on Linux and say why: the battery, Stay awake, the Mac's
voice for reading replies (Soniox reads them on the phone instead), opening a terminal window on the
Mac, and Telescope's app windows and iOS Simulator. Telescope still shows a session's terminal, its
web pages (with Chrome or Chromium installed), the Android emulator and PNG pictures.

## How the phone reaches it

Nothing faces the internet. Hypernova's APIs listen on `127.0.0.1` only, and your phone reaches the
host over your own Tailscale network. Tailscale is built in: the service runs its own node on your
tailnet, and the Android app carries Tailscale too, so both sign in to the same account and find
each other from anywhere. See [Remote access with Tailscale](/guides/claude-code-remote-access-tailscale/).

The direct route over a local network is for a phone on the same Wi‑Fi, so it fits a home server.
On a VPS with a public address of its own, or a cloud VM, it is **off by default**: your phone is
never on that network, and the port would face the internet.

## Run it as a normal user

Hypernova runs as a systemd **user** service, as the user who runs Claude Code. It works as root,
but `hypernova doctor` warns, since Claude Code's sessions would have the whole server, and Bypass
mode isn't available there. On a fresh VPS, make a user first and install
there.

Keep in mind what pairing means: a paired phone can do what that user can do at the keyboard in
Claude Code. Pair only phones you own.

## How to set it up

1. Pick the host: a VPS running Ubuntu or Debian (x86_64 or arm64), a home server or PC, or Docker
   on any machine.
2. Make a normal user for Claude Code and put your projects on the host, for example by cloning
   your repositories there.
3. Install Hypernova's service from `Hypernova-linux.tar.gz` (the
   [Linux install page](/install/linux/) has the commands). Setup installs Claude Code too when
   it's missing.
4. Run `claude` once on the host to sign in. Hypernova never reads your Claude login.
5. Install the Android app and run `hypernova pair`. With the built-in Tailscale it first shows a
   sign-in code; then scan the pairing code with your phone's camera.

The step-by-step guide, with the systemd service, Docker and troubleshooting, is
[Run Claude Code on a Linux server](/guides/run-claude-code-on-a-linux-server/).

## FAQ

### Do I need to open a port on the VPS?

No. The APIs listen on loopback, and the phone comes in over your tailnet. `hypernova doctor` fails
if anything listens on Hypernova's ports beyond loopback.

### Does closing SSH stop my sessions?

No. Sessions run in shared terminals that the service holds, and the service starts at boot with
nobody logged in (lingering). Restarting or updating the service leaves those terminals running.

### Can my hosting provider see my sessions?

Whoever runs the machine can reach what is on it, as with any server. Hypernova adds no server of
its own in between: sessions stay on your host, and the phone talks to it directly.

### Can I use a home server instead of a VPS?

Yes. On a home machine behind your router, the direct route is on, so a phone on the same Wi‑Fi
connects without Tailscale; away from home it uses your tailnet. `hypernova lan on` or `off`
switches it.

### Do I need a Mac as well?

No. A Linux host is a full host for the phone: sessions, approvals, usage, automations and
statistics all work there.
