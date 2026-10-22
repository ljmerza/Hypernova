---
title: Run Claude Code on a Linux server: setup guide | Hypernova
description: Install Hypernova on Ubuntu or Debian, or in Docker, to run Claude Code there and pair your Android phone: commands, the systemd service and fixes.
h1: Run Claude Code on a Linux server and control it from your phone
lede: The how-to: install Hypernova's service next to Claude Code on Ubuntu or Debian, keep it running with systemd, and pair your phone.
shot: phone-home
shotAlt: The Hypernova app's Home: the sun as an accretion disk showing the 5-hour usage, projects orbiting as planets, and session cards with a NEEDS YOU chip
related: claude-code-on-a-vps, claude-code-remote-access-tailscale, claude-code-on-your-phone
---
Hypernova's host can be a Mac or a Linux machine. On Linux there is no menu bar app: everything is
the `hypernova` command, over SSH, and the Android app pairs with it the same way it pairs with a
Mac. This page is the setup. For when a server makes sense and what it changes, see
[Claude Code on a VPS or home server](/guides/claude-code-on-a-vps/).

## What runs where

Hypernova's daemon runs on the server as your own user. It watches every Claude Code
session through Claude Code's hooks and transcripts, runs the phone's sessions in shared terminals,
and serves the phone API. The phone talks only to it.

- **Supported:** Ubuntu and Debian, on x86_64 and arm64. Other distributions work with their
  packages installed by hand.
- **A systemd user service** with lingering, so the daemon starts at boot with nobody logged in.
  It restarts 5 seconds after a failure, and `systemctl --user status|restart|stop hypernova` work
  as usual.
- **Shared terminals.** The installer adds a `claude` shell function to `~/.bashrc` (or `~/.zshrc`),
  so `claude` typed over SSH is a session your phone can see and type into. `hn ls` lists them and
  `hn attach <id>` joins one from any terminal.
- **Everything the phone needs:** sessions, approvals, usage, attachments, automations, statistics
  and Telescope for the terminal, web pages, the Android emulator and PNG pictures.

## Tailscale and the local route

The daemon runs its own Tailscale node, named `hypernova-<host>` on your tailnet; `hypernova pair`
shows its sign-in code the first time, and `hypernova tailscale` shows who it is signed in as. If
the Tailscale app on the server is running and signed in, Hypernova uses its `tailscale serve`
instead (once, run `sudo tailscale set --operator=$USER` so your user may change it).

`hypernova lan on` and `hypernova lan off` switch the direct route for a phone on the same network.
It starts off on a VPS with a public address, on cloud VMs and in containers.

## How to set it up

1. Download `Hypernova-linux.tar.gz` from the [install page](/install/) or the GitHub release,
   and copy it to the server.
2. As the user who will run Claude Code (not root), unpack and install:

   ```sh
   tar -xzf Hypernova-linux.tar.gz && hypernova-*/scripts/install-linux.sh
   ```

   It asks `sudo` only for system packages, brings its own Node.js 24 (checked against nodejs.org's
   checksums) and runs `hypernova setup`. Add `--no-claude` to skip installing Claude Code.
3. Run `claude` once to sign in. Hypernova never reads or copies Claude Code's login; you sign in yourself (or from the phone, through Claude Code's own sign-in).
4. Install the [Android app](/install/) on the phone (a Linux server carries no copy of it to hand
   out) and run `hypernova pair`. Scan the code with the phone's
   camera, or open the link it prints.
5. If something looks wrong, `hypernova doctor` checks every piece and gives the command that fixes
   each problem. `hypernova logs` shows the log.

The full guide, with every command and where things live, is on the
[Linux install page](/install/linux/).

## Try it in Docker

From a checkout of the repository, on any machine with Docker:

```sh
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude            # sign in to Claude once
docker exec -it hypernova hypernova pair    # the pairing code
```

The image is Ubuntu 24.04 with the install already run, as a normal user. Nothing is published to
the network, and your paired phones, settings and Claude's login live in the volume.

## FAQ

### What does the install change on the server?

Hypernova keeps its state and its own Node.js in `~/.local/share/hypernova`, its log in
`~/.local/state/hypernova/logs`, the command in `~/.local/bin/hypernova` and the service in
`~/.config/systemd/user/hypernova.service`. It also adds hooks to Claude Code's settings and a marked
block for the `claude` function to `~/.bashrc` or `~/.zshrc`.

### Can I run it as root?

It works, but `hypernova doctor` warns: Claude Code's sessions would have the whole server, and
Bypass mode isn't available as root. Make a user for it and install there.

### "hypernova: command not found" right after the install?

Log in again (Ubuntu adds `~/.local/bin` to PATH at login), or run `~/.local/bin/hypernova`. For
anything else, `hypernova doctor` names each problem with the command that fixes it.

### How do I update or remove it?

Run the install script again with the new tarball; open sessions keep running. `hypernova
uninstall` removes it, and `--purge` also removes paired phones and settings.

### Can I use Headscale instead of Tailscale's servers?

Yes. `hypernova tailscale control <url>` points the built-in node at your Headscale server, and
`none` returns it to tailscale.com. Without your tailnet's HTTPS certificates, the phone pins the
server's own certificate from the pairing code.
