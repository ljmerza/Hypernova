---
title: Claude Code and Codex on a Windows PC, from your phone | Hypernova
description: Experimental: run Hypernova's Linux service in WSL 2 or Docker Desktop on a Windows PC, next to Claude Code and Codex, and pair your Android phone over Tailscale.
h1: Use Claude Code and Codex from your phone when your computer is a Windows PC
lede: There's no Windows app yet, but a Windows PC runs Hypernova's Linux service inside WSL 2 or Docker Desktop. It's experimental: the steps are written down and checked on Linux, and nobody has tried them on a real Windows PC yet.
shot: phone-chat
shotAlt: A session on the phone: the usage strip, Claude's edits and a build, and a NEEDS YOU card with Deny, Always and Allow
related: run-claude-code-on-a-linux-server, claude-code-remote-access-tailscale, claude-code-on-your-phone
---
Hypernova's host is a Mac or a Linux machine. A Windows PC can be one too, through Linux: WSL 2
(Windows Subsystem for Linux) runs a real Ubuntu next to Windows, and Hypernova's Linux service
runs inside it, next to Claude Code and Codex. Docker Desktop, which runs its Linux containers on
WSL 2, is the other way in. Your Android phone pairs with it like with any Linux machine, over
Tailscale.

**This is experimental.** The steps come from Microsoft's and Docker's own documentation, the
service is the one tested on Linux, and its WSL behaviour is checked by unit tests that pretend to
be WSL. Nobody has run it on a Windows PC yet. If you try it, an
[issue](https://github.com/prudnik-web/Hypernova/issues) with what happened helps everyone.

## How it fits together

- **Windows** runs WSL 2, Microsoft's small Linux virtual machine for Windows 10 (version 2004 and
  later) and Windows 11, installed with one command.
- **Ubuntu, inside WSL**, runs Hypernova's service as a systemd user service, and the Linux builds
  of Claude Code and Codex. You use them from an Ubuntu terminal, and `claude` typed there is a
  shared terminal your phone can watch and type into.
- **Your phone** reaches the service over your own Tailscale network, through the node built into
  Hypernova (nothing to install on Windows). Under WSL's default network the PC is out of the
  phone's reach on the Wi‑Fi, and Hypernova knows it: it keeps the local route off and pairs over
  Tailscale.

## Set it up with WSL 2

The full steps, with every command, are on the install page:
[Windows (experimental)](/install/#5-windows-experimental-wsl2-or-docker-desktop). In short:

1. In PowerShell as administrator, `wsl --install -d Ubuntu`, restart, open Ubuntu and make your
   Linux user.
2. Check that systemd runs (`ps -p 1 -o comm=` prints `systemd`). If not, add `[boot]` and
   `systemd=true` to `/etc/wsl.conf` and run `wsl --shutdown` in PowerShell.
3. Inside Ubuntu, download `Hypernova-linux.tar.gz` and run its install script, as on
   [a Linux server](/guides/run-claude-code-on-a-linux-server/). It installs Claude Code's Linux
   build too; Codex you install inside Ubuntu yourself.
4. Run `claude` once to sign in (and `codex login --device-auth` for Codex).
5. Run `hypernova pair`: Tailscale's sign-in first, then the pairing code for the phone's camera.
6. `hypernova doctor` checks every piece, with WSL's own lines: systemd, keeping WSL running, the
   network mode and Tailscale.

## Keep it reachable

WSL stops Ubuntu soon after its last window closes, and a systemd service doesn't keep it running,
so your phone would lose the PC. In `%UserProfile%\.wslconfig` on Windows:

```ini
[general]
instanceIdleTimeout=-1
```

Then `wsl --shutdown` and open Ubuntu again (Microsoft's
[.wslconfig reference](https://learn.microsoft.com/windows/wsl/wsl-config)). WSL also starts only
when something opens it: after Windows restarts, open Ubuntu once. And a sleeping PC answers no
one, so keep it awake in Windows' power settings while you want to reach it.

## The phone on the same Wi‑Fi

On Windows 11 22H2 and later, WSL's **mirrored networking** (`networkingMode=mirrored` under
`[wsl2]` in `.wslconfig`) gives Ubuntu Windows' own addresses. With one Hyper-V firewall rule for
port 47323, a phone on the same Wi‑Fi can then reach Hypernova directly, as it reaches a Mac; the
install page has the exact command. Without it, Tailscale is the way in, which is also the one that
works from anywhere and opens nothing on your network.

## Docker Desktop instead

Docker Desktop for Windows runs Linux containers on WSL 2. Clone the repository, `docker build -t
hypernova .`, run it with a volume for its state, sign in to Claude inside it and pair with
`docker exec -it hypernova hypernova pair`. The phone comes in through the built-in Tailscale node;
nothing is published to your network. The image is tested on arm64 so far: an x86_64 PC builds the
amd64 image, which nobody has tried yet. See [In Docker](/install/#4-in-docker).

## What doesn't work on Windows

- **Agents outside WSL.** Claude Code or Codex installed on Windows itself, and sessions in
  PowerShell or a Windows app, are invisible to Hypernova. Install the agents inside Ubuntu.
- **No tray app.** Everything is the `hypernova` command in an Ubuntu terminal. A native Windows
  host and tray app are planned, with no date.
- **What any Linux host lacks:** the battery, Stay awake, the Mac's voice, app windows in
  Telescope, opening a terminal window from the phone (`hn attach` in an Ubuntu terminal instead).

## FAQ

### Has anyone run this on a Windows PC?

Not yet. The service is the Linux one, tested on Linux and in Linux containers, and its WSL
behaviour (the network, systemd, the advice it gives) is checked by tests that pretend to be WSL.
The Windows side, WSL itself and Docker Desktop, hasn't been tried. That's why it's called
experimental, and why a report from a real PC is the most useful thing you can send.

### Will Hypernova see Claude Code I run in PowerShell?

No. Hypernova sees the sessions on the machine it runs on, and inside WSL that is Ubuntu. Run
Claude Code and Codex in an Ubuntu terminal (Windows Terminal opens one) and your phone sees them,
and can type into them.

### Do I need Tailscale on Windows?

You need a free Tailscale account, but no Tailscale app: Hypernova brings its own node, and
`hypernova pair` shows its sign-in. Under WSL's default network it is the only way in. With
mirrored networking on Windows 11, a phone on the same Wi‑Fi can also come in directly. See
[Remote access with Tailscale](/guides/claude-code-remote-access-tailscale/).

### Does it work on Windows 10?

WSL 2 runs on Windows 10 version 2004 (build 19041) and later, so the WSL route should. Mirrored
networking needs Windows 11 22H2 or later, so on Windows 10 the phone comes in over Tailscale.

### Is a Windows app planned?

A native Windows host with a tray app is on the plan, after the routes through Linux. Until then,
WSL 2 or Docker Desktop is how a Windows PC runs Hypernova.
