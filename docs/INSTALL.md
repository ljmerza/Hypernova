# Install Hypernova

Hypernova is a control room for Claude Code and Codex. A small service runs on a computer you own,
next to your agents, and you reach it from your phone (the Android app) and, on a Mac, from the menu bar. Your phone reaches that
computer over your Wi‑Fi, or from anywhere through your own Tailscale network. There is no
Hypernova server in between.

This is the whole install guide. Setup, in the Mac app, uses the same step names.

## Where Hypernova runs

| | **A Mac** | **A Linux server** | **Docker** |
| --- | --- | --- | --- |
| For | Your own Mac, at home or in your bag | A VPS, a home server, a Linux PC | Trying it out, or a box that already runs containers |
| You install | Upstream's `Hypernova.dmg` (not built in this fork): the menu bar app, the service inside | `hypernova-server-<tag>-linux.tar.gz`: the `hypernova` command and the service | The image built from this repository |
| You drive it with | Setup and the menu bar | `hypernova` over SSH | `docker exec … hypernova` |
| Runs | macOS 14 or later, Apple silicon | Ubuntu or Debian, x86_64 or arm64, systemd | Docker (tested on arm64; the image is about 2.7 GB) |

**A Windows PC** runs the Linux service too, inside WSL 2 (Ubuntu) or in a Docker Desktop container:
**experimental**, documented but not yet tried on a real Windows PC. See
[5. Windows (experimental)](#5-windows-experimental-wsl2-or-docker-desktop).

Whichever you choose, the phone side is the same: **the Android app** (Android 10 or later) pairs
with the computer by a code. The Mac's menu bar is a second control room, on that Mac only.

## What you need

- **The computer**:
  - a Mac with Apple silicon (M1 or later) on macOS 14 Sonoma or later (Intel Macs aren't
    supported), or
  - a Linux machine on Ubuntu or Debian (others work with their packages installed by hand),
    x86_64 or arm64, with systemd, sudo for the system packages, and a normal user to run it as, or
  - Docker (the image is tested on arm64 so far).
- **An agent** on that computer: [Claude Code](https://docs.claude.com/en/docs/claude-code),
  OpenAI's [Codex](https://github.com/openai/codex) (0.163.0 or later), or both. Either one alone is
  enough. Hypernova can install Claude Code for you; Codex you install yourself.
- **An Android phone** on **Android 10 or later**.
- Optional: a [Tailscale](https://tailscale.com) account, to reach the computer when the phone isn't
  on its network (always, for a VPS). On the same Wi‑Fi you don't need one.

Nothing else: Hypernova brings its own Node.js and its own Tailscale. On a Mac, Setup also
downloads Claude Code's SDK from npm (about 2 MB; see [Background service](#2-background-service)).

## 1. Download

This fork's releases are on [GitHub](https://github.com/ljmerza/Hypernova/releases/latest). Each
one is named `v<version>-ljm.<n>` (the tag below) and carries:

- `hypernova-server-<tag>-linux.tar.gz`: the Linux server package (one package for x86_64 and
  arm64: Node.js and the terminal module are fetched and built for the server at install)
- `hypernova-<tag>.apk`: the Android app

On the server, `gh release download -R ljmerza/Hypernova -p 'hypernova-server-*-linux.tar.gz'`
fetches the newest server package; or save it from the release page and copy it over.

The Mac app (`Hypernova.dmg`) is upstream's and isn't built in this fork: get it from
[upstream's releases](https://github.com/prudnik-web/Hypernova/releases/latest), which also
publish `SHA256SUMS.txt` for their files (`shasum -a 256 -c --ignore-missing SHA256SUMS.txt`).

## 2. On a Mac

### Install the Mac app

1. Open `Hypernova.dmg`.
2. Drag **Hypernova** onto **Applications**.
3. Eject the disk image.

If you open Hypernova from somewhere else (the disk image, Downloads), it offers to move itself:
**Move to Applications**.

### The first launch

Hypernova is open source and signed on the machine that built it, but it is not notarized by
Apple, so macOS stops it the first time. You allow it once.

**macOS 15 Sequoia and macOS 26 Tahoe**

1. Open Hypernova from Applications. macOS says *Apple could not verify “Hypernova” is free of
   malware that may harm your Mac or compromise your privacy*. Click **Done**.
2. Open **System Settings → Privacy & Security** and scroll down to **Security**. Next to
   *“Hypernova” was blocked to protect your Mac*, click **Open Anyway**. (The button stays there
   for about an hour after you tried to open the app.)
3. macOS asks for your password or Touch ID, and once more whether to open Hypernova: click
   **Open Anyway**.

**macOS 14 Sonoma**

1. Open Hypernova from Applications. macOS says it can't be opened because the developer cannot be
   verified. Click **Cancel**.
2. In Applications, **Control‑click** Hypernova, choose **Open**, then click **Open**.
   (System Settings → Privacy & Security → **Open Anyway** works too.)

**Any version, in Terminal**

```sh
xattr -dr com.apple.quarantine /Applications/Hypernova.app
```

Then open Hypernova as usual. macOS asks only once per version.

### Setup

Hypernova lives in the menu bar (a small planet). The first time, it opens **Setup**: seven steps
on a rail. Each step finds what's already there and offers one main button. You can close the
window at any time; Setup picks up where you left off, and **Open setup…** in the menu bar's
settings brings it back. **Check again** looks once more after you've installed something in a
terminal.

| Step | What it does | Can you skip it? |
| --- | --- | --- |
| 1. **Claude Code** | Finds Claude Code and checks that it's signed in. **Install Claude Code** runs Anthropic's official installer. | Yes, with Codex: **Use Codex only** |
| 2. **Background service** | Starts the service your phone talks to, so it works with the menu closed. | No |
| 3. **Connect Claude Code** | Adds Hypernova's hooks, its status line tap and the `claude` shell function. | Skipped with Codex only |
| 4. **Tailscale** | Lets your phone reach this Mac from anywhere. | **Not now**: the same Wi‑Fi is enough |
| 5. **Voice** | Left over from Soniox, which was removed: choose **Not now**. | **Not now** |
| 6. **Codex** | Finds Codex and turns on **Use Codex with Hypernova**. | **Not now** |
| 7. **Your phone** | **Get the app**, then pair it with a code. | No |

#### 1. Claude Code

Setup looks for `claude` (see [How Hypernova finds your agents](#how-hypernova-finds-your-agents)).

- **Found and signed in**: **Continue**.
- **Not signed in**: **Sign in** (once the service runs; you open a page and paste back the code it
  shows), or **Open Terminal to sign in** before that.
- **Not installed**: **Install Claude Code** runs Anthropic's installer, the same as

  ```sh
  curl -fsSL https://claude.ai/install.sh | bash
  ```

  It takes about a minute and doesn't ask for your password. Claude Code lands in
  `~/.local/bin/claude`.
- **Not installed, and you want Codex instead**: install Codex in a terminal (see
  [Codex](#codex)), click **Check again**, then **Use Codex only**. Setup skips Claude Code's two
  steps and turns Codex on. You can add Claude Code later.

#### 2. Background service

**Start background service** does this:

1. **Node.js.** The service runs on Node.js 22.18 or newer. If your Mac has one that stays put
   (Homebrew's, or the nodejs.org installer's), Hypernova uses it. Otherwise it downloads the
   current Node.js LTS from nodejs.org, checks it against nodejs.org's published checksums, and
   keeps it in its own folder.
2. **Copy the service** into `~/Library/Application Support/Hypernova`.
3. **Get Claude Code's SDK.** The service drives Claude Code through Anthropic's Agent SDK
   (`@anthropic-ai/claude-agent-sdk`). It's Anthropic's to hand out, not ours, so the app doesn't
   carry it: Setup downloads it from npm once, about 2 MB, with that Node.js's own npm, at the exact
   version the service was built with (checked against the service's package lock). If your Mac is
   offline, or npm fails, the service starts all the same and the step says what's missing:
   **Fetch again** gets it later (also in **Agents & providers** and the menu bar). Until then the
   "/" list, Rewind and the sessions the service runs itself wait for it; everything else works.
   With Codex only, nothing needs it, and Setup never mentions it.
4. **Add it to your login items**: a launch agent, so it starts with your Mac and restarts itself.
   Hypernova also turns on **Open at login** for the menu bar app (the first time only).
5. **Start it**, and check that it answers.

macOS may show a *Background Items Added* notice. Both items appear in **System Settings → General
→ Login Items & Extensions**; leave them on.

#### 3. Connect Claude Code

**Connect Claude Code** adds three things, each marked as Hypernova's:

- **Hooks** in `~/.claude/settings.json`, so the phone sees sessions, prompts and approvals as they
  happen. They call the service on `127.0.0.1` only.
- **A status line tap**, for your 5‑hour and weekly limits. Your own status line keeps working;
  Hypernova only listens in.
- **Shared terminals**: a `claude` function in `~/.zshrc` (see
  [The shell integration](#the-shell-integration)). Open a new terminal tab for it to take effect.

#### 4. Tailscale

On the same Wi‑Fi your phone reaches the Mac directly, encrypted and pinned to this Mac by the
pairing code. Tailscale is for everywhere else: a private network between your own devices.
Nothing is opened to the internet. You can do this step later.

- **Built in (the usual case).** Hypernova carries its own Tailscale node, so there's no Tailscale
  app to install. Scan the code with your phone or click **Open the sign-in link**, and sign in
  with Google, GitHub, Apple or Microsoft. The Android app carries Tailscale too: sign in there with
  the same account. Your Mac appears in your tailnet as `hypernova-<your Mac's name>`.
- **With the Tailscale app.** If the Tailscale app is on your Mac and signed in, Setup can use it
  instead (**Use the Tailscale app**), and **Turn on phone access** gives Hypernova a private
  `https` address in your tailnet. That needs HTTPS certificates turned on for your tailnet
  (Tailscale admin console → DNS → HTTPS Certificates → Enable; **Open DNS settings** goes there).
- **Key expiry.** Tailscale asks every device to sign in again after a while. In the
  [admin console](https://login.tailscale.com/admin/machines), open your Mac's ⋯ menu and choose
  **Disable key expiry**, so your phone doesn't lose it. With the Tailscale app, Setup and the menu
  bar warn you two weeks ahead.

#### 5. Voice

Choose **Not now**. Voice needs nothing on the computer any more: hold the mic on your phone, say
what to do, read it, send; the phone's own on-device recognizer hears you, so the audio never leaves
the phone (Android 12 or later with an on-device recognizer; without one there's no mic). The Mac
app still shows this step, from when it connected Soniox, which was removed.

#### 6. Codex

Optional, and Hypernova never installs or updates Codex for you. The step shows what it found: the
path, the version (Hypernova needs 0.163.0 or later) and, once it's on, the account.

- **Not installed**: run one of the lines it shows, then **Check again**.
- **Too old**: `codex update`, then **Check again**.
- **Found**: turn on **Use Codex with Hypernova**. Hypernova then connects to Codex's own app
  server, your phone can start Codex sessions, and a `codex` function joins `claude` in
  `~/.zshrc`. Signed out: `codex login`.

You can also turn Codex on later in **Agents & providers…**, or by starting a Codex session from
the phone.

#### 7. Your phone

1. **Get the app**: scan the code with your phone's camera and open the link. It downloads
   `Hypernova.apk` from your Mac. (Or install the `Hypernova.apk` you downloaded.)
2. Android asks before installing from your browser: tap **Settings**, allow installs from this
   source, go back and tap **Install**.
3. On the Mac, click **I have the app**. A pairing code appears.
4. Point the phone's camera at the code. The link (`https://hypernova.observer/pair#…`) opens
   Hypernova and pairs. No camera? Click **Copy link** on the Mac, send it to your phone, and use
   **Paste pairing link** in the app.
5. Allow notifications when the app asks, so approvals reach you.

A code works once and lasts 10 minutes; Setup shows a fresh one by itself. To pair another phone
later, click **Pair a phone** in the menu bar.

When the download page is served over your Wi‑Fi, the phone's browser may warn that it can't check
the page's certificate (it's this Mac's own). Setup shows the file's checksum next to the code:
compare it with the one on the page before installing. With **Use a private name for this Mac**
on (the default), the page has a real certificate instead (see
[docs/SECURITY-MODEL.md](SECURITY-MODEL.md)).

## 3. On a Linux server

Everything is the `hypernova` command, over SSH: there is no menu bar and no window. Run it all as
the user who will use Claude Code or Codex, **not as root** (the script asks `sudo` for system
packages only). On a fresh VPS, make that user first: `adduser claude`, then log in as `claude`.

### Install

From a release's server package (see [Download](#1-download)):

```sh
tmp=$(mktemp -d)
tar -xzf hypernova-server-*-linux.tar.gz -C "$tmp"
"$tmp"/hypernova-*/scripts/install-linux.sh
```

Or from a clone of this repository:

```sh
git clone https://github.com/ljmerza/Hypernova.git && cd Hypernova
scripts/install-linux.sh
```

The script, then `hypernova setup` which it runs for you:

1. **System packages** it lacks, with `apt-get` (it asks for your password through `sudo`): curl,
   tar, openssl, git, build tools (node-pty, which runs the shared terminals, is built on the
   server), `ps` and `lsof`.
2. **Node.js 24** from nodejs.org, checked against nodejs.org's checksums, into Hypernova's own
   folder. A Node.js 24 or newer already on the server is used instead.
3. **The daemon** in `~/.local/share/hypernova/app`, and **the `hypernova` command** in
   `~/.local/bin`.
4. **Claude Code**, with Anthropic's installer, when it isn't there. For Codex alone, add
   `--no-claude` to the script.
5. **The service**: a systemd user service, `hypernova.service`, with **lingering** turned on so it
   runs at boot with nobody logged in.
6. **The integration**: hooks and the status line tap in `~/.claude/settings.json`, and the `claude`
   function in `~/.bashrc` (or `~/.zshrc`).
7. **Tailscale**: the daemon starts its built-in node, signed in later by `hypernova pair`.

It ends with what's left to do. Options: `--no-claude`, `--yes` (no questions). The folder in
`$tmp` isn't needed afterwards. `hypernova: command not found` right after? Log in again (Ubuntu
adds `~/.local/bin` to your PATH at login), or run `~/.local/bin/hypernova`.

### Sign in to your agents

**Claude Code.** Run `claude` once on the server and follow its steps: it shows a link to open on
any device and asks for the code that page gives you. (Paired already? Home's account card on the
phone can sign it in too.)

**Codex** (optional beside Claude Code, the agent without it). Install it, sign it in, and turn it on
for Hypernova:

```sh
sudo apt-get install -y nodejs npm     # if this server has no npm yet
sudo npm i -g @openai/codex            # lands in /usr/local/bin/codex
codex login --device-auth              # a code to enter on any device; no browser needed here
hypernova codex on
```

Or put Codex's own Linux build from its [GitHub releases](https://github.com/openai/codex/releases)
in `~/.local/bin/codex`. With an API key instead of ChatGPT:
`printenv OPENAI_API_KEY | codex login --with-api-key`. `hypernova codex` on its own says what it
found, and `hypernova codex off` lets go of it.

### Pair your phone

1. Install `hypernova-<tag>.apk` (from the [latest release](https://github.com/ljmerza/Hypernova/releases/latest)) on the phone: open it from the phone's Downloads, and when Android asks,
   allow installs from that source. (A Linux server carries no copy of the app to hand out.)
2. On the server:

   ```sh
   hypernova pair
   ```

   The first time, it shows **Tailscale's sign-in** as a QR code and a link: open it and sign in
   with Google, GitHub, Apple or Microsoft. The Android app signs in to Tailscale itself: use the
   same account there. Enter skips this (a home server on your own network doesn't need it, and
   `--local` leaves Tailscale out altogether).
3. Then the **pairing code**: point the phone's camera at the QR code in the terminal; it opens
   Hypernova and pairs. Too narrow a window, or no camera? Copy the `https://hypernova.observer/pair#…`
   link under it and use **Paste pairing link** in the app. A code works once and lasts 10 minutes;
   `hypernova pair` again makes a new one.
4. Allow notifications when the app asks, so approvals reach you.

### Check it

```sh
hypernova status      # what runs, how phones reach it, Claude Code, Codex, who's paired
hypernova doctor      # every piece checked, each problem with the command that fixes it
hypernova logs -f     # the daemon's log
systemctl --user status hypernova
```

### Remote access

- **Built in (the usual case).** The daemon runs its own Tailscale node, `hypernova-<host>` in your
  tailnet, and serves the phone API over it: nothing to install, and nothing listens on a public
  port. `hypernova tailscale` shows who it's signed in as, or its sign-in code; `hypernova tailscale
  logout` signs it out for another account; `control <url>` points it at a Headscale server.
- **With the Tailscale app** on the server instead: install it, sign in and let your user change
  `serve` once, then pair. Your tailnet needs MagicDNS and HTTPS certificates on.

  ```sh
  curl -fsSL https://tailscale.com/install.sh | sh
  sudo tailscale up
  sudo tailscale set --operator=$USER
  hypernova pair
  ```

- **On the same network** (a home server, a PC), the phone also has a direct route, encrypted and
  pinned to this server by the pairing code. It's off on a machine with a public address (a VPS)
  and in a container: `hypernova lan on|off`.
- **Key expiry**: in the [admin console](https://login.tailscale.com/admin/machines), open the
  server's ⋯ menu and choose **Disable key expiry**, so your phone doesn't lose it.

[docs/LINUX.md](LINUX.md) has every command and flag, where things live, and what a server doesn't
have (battery, Stay awake, the Mac's voice).

## 4. In Docker

The quick way to try it, on a machine with Docker. The image is built from this repository:

```sh
git clone https://github.com/ljmerza/Hypernova.git && cd Hypernova
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude              # sign in to Claude once
docker exec -it hypernova hypernova pair      # Tailscale's sign-in, then the pairing code
docker exec -it hypernova hypernova doctor
```

The image is Ubuntu 24.04 with the install script already run in it, as a normal user. Your state
(paired phones, settings, keys, the agents' logins) lives in the `hypernova-home` volume. Nothing is
published to the network: the phone reaches the container through the built-in Tailscale node.
On a Linux host, `--network host` also lets a phone on the same network pair directly.
`--build-arg CLAUDE=no` builds it without Claude Code.

**Codex in the container**: its npm package needs Node.js on the PATH, which the image doesn't
have, so use Codex's own Linux build from its [GitHub releases](https://github.com/openai/codex/releases):

```sh
docker exec hypernova mkdir -p /home/hypernova/.local/bin
docker cp codex hypernova:/home/hypernova/.local/bin/codex
docker exec -it hypernova codex login --device-auth
docker exec -it hypernova hypernova codex on
```

## 5. Windows (experimental): WSL2 or Docker Desktop

There is no Windows app and no native Windows service yet ([PLATFORMS.md](PLATFORMS.md) plans one).
A Windows PC runs Hypernova's **Linux** service instead, inside **WSL 2** (Windows Subsystem for
Linux, with Ubuntu) or in a **Docker Desktop** container, and your phone pairs with it as with any
Linux machine.

> **Experimental.** These steps are written from Microsoft's and Docker's documentation and from
> Hypernova's Linux tests. **Nobody has tried them on a real Windows PC yet.** If you do, please
> [open an issue](https://github.com/ljmerza/Hypernova/issues) with what worked and what
> `hypernova doctor` printed (it names your folders: look it over first).

**What works**, as on any Linux (tested on Linux and in Linux containers, not on Windows): the
service, Claude Code and Codex sessions, approvals, usage, shared terminals (`claude` typed in an
Ubuntu terminal), the built-in Tailscale and pairing.
`hypernova doctor` and `hypernova pair` know when they run in WSL and say what differs.

**What doesn't:**

- **Claude Code and Codex must be installed inside WSL** (their Linux builds; a Windows install
  that WSL's shell finds through Windows' PATH is ignored). Sessions you run in PowerShell, Command
  Prompt or a Windows app are outside WSL: Hypernova doesn't see them.
- What a Linux server lacks ([LINUX.md](LINUX.md), "What a Linux server doesn't have"): battery,
  Stay awake, the Mac's voice, opening a terminal window from the phone.
- No tray app and no Setup window: everything is the `hypernova` command in an Ubuntu terminal.
- WSL's default network (NAT) keeps the PC off your phone's Wi‑Fi: the phone comes in over
  **Tailscale**, which is built in. A direct route on the Wi‑Fi needs mirrored networking (below).

**Not verified on Windows yet:** all of it on real hardware, and in particular WSL stopping and
starting the service, mirrored networking with the Hyper-V firewall rule, Docker Desktop building
the image on an x86_64 PC (the image is tested on arm64 so far), and Windows on Arm.

### With WSL 2 (recommended)

1. **Install WSL and Ubuntu.** In PowerShell as administrator:

   ```powershell
   wsl --install -d Ubuntu
   ```

   Restart when asked, open **Ubuntu** from the Start menu and make your Linux user. Already have
   WSL? Run `wsl --update`, then `wsl -l -v`: Ubuntu's VERSION must be 2 (if it's 1:
   `wsl --set-version Ubuntu 2`). Microsoft's guide:
   [Install WSL](https://learn.microsoft.com/windows/wsl/install).
2. **Check that systemd runs** (the service needs it). In Ubuntu, `ps -p 1 -o comm=` should print
   `systemd`; Ubuntu installed by `wsl --install` has it on. If it prints something else, add these
   two lines to `/etc/wsl.conf` (`sudo nano /etc/wsl.conf`):

   ```ini
   [boot]
   systemd=true
   ```

   then run `wsl --shutdown` in PowerShell and open Ubuntu again
   ([Microsoft: systemd in WSL](https://learn.microsoft.com/windows/wsl/systemd)).
3. **Install Hypernova inside Ubuntu**, as on a Linux server (keep it in your Linux home, not under
   `/mnt/c`):

   ```sh
   cd ~
   # hypernova-server-<tag>-linux.tar.gz from https://github.com/ljmerza/Hypernova/releases/latest
   tmp=$(mktemp -d) && tar -xzf hypernova-server-*-linux.tar.gz -C "$tmp" && "$tmp"/hypernova-*/scripts/install-linux.sh
   ```

   The script says it found WSL, and installs Claude Code's Linux build unless you add
   `--no-claude`. For Codex, install it inside Ubuntu as in
   [Sign in to your agents](#sign-in-to-your-agents).
4. **Sign in** inside Ubuntu: run `claude` once (and `codex login --device-auth` for Codex).
5. **Keep WSL running.** WSL stops Ubuntu soon after its last window closes, and a systemd service
   doesn't keep it running. In Windows, create or edit `%UserProfile%\.wslconfig` (for example
   `notepad "$env:USERPROFILE\.wslconfig"` in PowerShell) and add:

   ```ini
   [general]
   instanceIdleTimeout=-1
   ```

   Then `wsl --shutdown` in PowerShell and open Ubuntu again. `-1` turns WSL's idle shutdown off
   ([Microsoft: .wslconfig](https://learn.microsoft.com/windows/wsl/wsl-config); if your WSL doesn't
   know the setting, `wsl --update`). WSL starts only when something opens it, so after Windows
   restarts, open Ubuntu once. (A Task Scheduler task "at log on" that runs `wsl.exe -d Ubuntu
   --exec true` should do the same without a window; untested.) And the PC must be awake: don't let
   it sleep, in Windows' power settings, while you want to reach it.
6. **Pair your phone**, in Ubuntu:

   ```sh
   hypernova pair
   ```

   It shows Tailscale's sign-in first (the built-in node: nothing to install, nothing opened on your
   network, and it works from anywhere), then the pairing code, as in
   [Pair your phone](#pair-your-phone). Install `hypernova-<tag>.apk` on the phone first.
7. **Check it**: `hypernova doctor` adds WSL's own lines (systemd, keeping WSL running, the network
   mode, Tailscale), each with what to do.

### Optional: the phone on the same Wi‑Fi (mirrored networking)

On Windows 11 22H2 and later, WSL can share Windows' own network addresses
([Microsoft: mirrored mode networking](https://learn.microsoft.com/windows/wsl/networking#mirrored-mode-networking)).
Then a phone on the same Wi‑Fi can reach Hypernova directly, as it does a Mac:

1. In `%UserProfile%\.wslconfig`:

   ```ini
   [wsl2]
   networkingMode=mirrored
   ```

   then `wsl --shutdown` and open Ubuntu again. In Ubuntu, `wslinfo --networking-mode` now prints
   `mirrored`.
2. Let the local route's port in through Windows' Hyper-V firewall, once, in PowerShell as
   administrator:

   ```powershell
   New-NetFirewallHyperVRule -Name Hypernova -DisplayName "Hypernova local route" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 47323
   ```

3. `hypernova status` should show the local route on, with the PC's Wi‑Fi address
   (`hypernova lan on` if it's off), and `hypernova pair` makes a code that carries it.

Untested on Windows. With WSL's default NAT, Hypernova keeps the local route off and leaves WSL's
own addresses out of the pairing link, since no phone can reach them. A `netsh interface portproxy`
forward isn't supported: from inside WSL the service can't learn the PC's Wi‑Fi address to put in
the pairing link.

### With Docker Desktop

Docker Desktop for Windows runs Linux containers on WSL 2 (its "Use the WSL 2 based engine"
setting, on by default where WSL 2 is installed:
[Docker's guide](https://docs.docker.com/desktop/features/wsl/)). There's no published image yet,
so build it from a clone of this repository, in PowerShell (with [Git](https://git-scm.com)) or in
Ubuntu:

```sh
git clone https://github.com/ljmerza/Hypernova.git
cd Hypernova
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude              # sign in to Claude once
docker exec -it hypernova hypernova pair      # Tailscale's sign-in, then the pairing code
docker exec -it hypernova hypernova doctor
```

Everything else is as in [4. In Docker](#4-in-docker): Claude Code is inside the image (Codex can be
copied in), your state is in the `hypernova-home` volume, nothing is published to the network, and
the phone comes in through the built-in Tailscale node. Docker Desktop must be running for the
container to run (it has a setting to start when you sign in). The image is tested on arm64; an
x86_64 PC builds the amd64 image, which nobody has tried yet.

### Updating on Windows

- **WSL:** in Ubuntu, download the new `hypernova-server-<tag>-linux.tar.gz` and run its install script again,
  as in step 3. Open sessions keep running; phones and settings stay. `wsl --update` updates WSL
  itself.
- **Docker Desktop:** `git pull` in the clone, then build, remove and run again as in
  [Update](#update) (the same `docker run` line, the same volume).

### Uninstalling on Windows

- **WSL:** in Ubuntu, `hypernova uninstall` (`--purge` also removes paired phones and settings, as
  on Linux). Then remove what you added on Windows: the lines in `%UserProfile%\.wslconfig`, and the
  firewall rule, in PowerShell as administrator: `Remove-NetFirewallHyperVRule -Name Hypernova`.
  `wsl --unregister Ubuntu` deletes the whole Ubuntu with everything in it: only if you want that.
- **Docker Desktop:** `docker rm -f hypernova`, `docker volume rm hypernova-home` (its state) and
  `docker image rm hypernova`.
- **Then**, as anywhere: uninstall the app on the phone and remove `hypernova-<name>` from your
  tailnet ([Uninstall](#uninstall)).

## Your phone

The Android app is the same for every computer, and pairs with one at a time.

- **Get it** from the [release](https://github.com/ljmerza/Hypernova/releases/latest) (`hypernova-<tag>.apk`), or, from a Mac, by scanning **Get the app** in
  Setup. Android asks before installing from your browser or Files: allow installs from that
  source, then tap **Install**.
- **Pair** by scanning the code (Setup's **Your phone** step or **Pair a phone** in the menu bar on a
  Mac, `hypernova pair` on Linux). The camera opens the `https://hypernova.observer/pair#…` link,
  which opens Hypernova; the details after the `#` never reach that site. **Paste pairing link** in
  the app takes the same link.
- **Notifications**: allow them when the app asks, so approvals reach you with the app closed.

## Claude Code and Codex

| On the computer | What Hypernova does |
| --- | --- |
| **Claude Code only** | Everything as above. On a Mac the Codex step says how to add Codex; **Not now** skips it. |
| **Codex only** | On a Mac, Setup offers **Use Codex only**: Claude Code's two steps are skipped and Codex is turned on. On Linux, install with `--no-claude` and run `hypernova codex on`. New sessions go to Codex. |
| **Both** | Claude Code leads (the sun in the app is its usage). Turn Codex on (Setup's Codex step, **Agents & providers…**, `hypernova codex on`, or a first Codex session from the phone) to use both: each has its own usage meters, and a conversation can switch from one to the other. |
| **Neither** | On a Mac, Setup offers **Install Claude Code**, or the lines to install Codex, and the menu bar says *No agent on this Mac yet*. On Linux, `hypernova setup` installs Claude Code unless told not to, and `hypernova doctor` says what's missing. |

### Claude Code

- Install it from Setup or `hypernova setup`, or yourself with
  `curl -fsSL https://claude.ai/install.sh | bash` (Homebrew and npm installs work too).
- Sign in once with your Claude plan or an API key. Hypernova never reads your Claude login: the
  service asks `claude auth status`.
- Keep it current with `claude update`. Hypernova follows the current Claude Code.

### Codex

Install it in a terminal, then sign in:

```sh
npm i -g @openai/codex        # needs Node.js and npm (on Linux: sudo npm i -g @openai/codex)
brew install --cask codex     # on a Mac, with Homebrew
codex login                   # your browser opens; on a server: codex login --device-auth
```

Hypernova needs **Codex 0.163.0 or later** (`codex --version`; `codex update` to update). It uses
Codex only while it's on for Hypernova (**Use Codex with Hypernova** on a Mac, `hypernova codex on`
on Linux, or the switch in the phone's **Agents & providers**), as one client of Codex's own shared
app server, so your Codex sessions keep running when Hypernova restarts.

### How Hypernova finds your agents

For `claude` and `codex` alike, Hypernova looks in `~/.local/bin` (the official installers), in
Homebrew's and the system's folders (`/opt/homebrew/bin` and `/usr/local/bin` on a Mac;
`/usr/local/bin` and `/usr/bin` on Linux), npm's global folder (`~/.npm-global/bin`), Volta
(`~/.volta/bin`), Bun (`~/.bun/bin`), pnpm, every Node.js version under nvm
(`~/.nvm/versions/node/*/bin`), and whatever your login shell finds (`command -v claude`). If you
installed one somewhere else, make sure a new terminal finds it, then click **Check again** (Mac) or
run `hypernova codex` (Linux).

## The shell integration

Connect Claude Code (or turning on Codex, where Claude Code isn't installed) adds one marked block to
your shell's startup file: `~/.zshrc` on a Mac; on Linux `~/.bashrc` for bash or `~/.zshrc` for zsh.

```sh
# >>> hypernova shared terminals (lets your phone see and type into claude sessions; safe to delete) …
function claude { … }
function codex { … }      # only while Codex is on for Hypernova
function hn { … }
# <<< hypernova shared terminals
```

- `claude` (and `codex`) typed in a terminal, or over SSH, look and work the same, but run in a
  **shared terminal**: your phone can watch them live and type into them.
- Anything else (pipes, `claude -p`, subcommands such as `claude mcp`) goes straight to the real
  command, and so does everything when Hypernova isn't running.
- `command claude`, or `HYPERNOVA_OFF=1`, skips it for one run.
- `hn ls` lists the shared terminals; `hn attach <id>` joins one from any terminal.
- zsh and bash only. With another shell nothing is added: sessions you start in a terminal still
  show on the phone through the hooks, but the phone can't type into them.

Before changing your startup file or `~/.claude/settings.json`, Hypernova saves a copy in its
`backups` folder. If the block's marker lines are changed by hand, Hypernova won't touch the file
again and says how to remove the block yourself.

## What Hypernova puts on the computer

**On a Mac**

| Where | What |
| --- | --- |
| `/Applications/Hypernova.app` | The menu bar app, with the service inside |
| `~/Library/Application Support/Hypernova` | The service (with Claude Code's SDK, from npm), its Node.js, npm's cache for that download, paired phones, settings and keys (private to your user) |
| `~/Library/Logs/Hypernova/daemon.log` | The service's log |
| `~/Library/LaunchAgents/com.prudnikweb.hypernova.daemon.plist` | Starts the service at login |
| Login items | Hypernova itself (**Open at login**) |
| `~/.claude/settings.json` | Hooks and the status line tap (Connect Claude Code) |
| `~/.zshrc` | The shell integration block |

**On Linux**

| Where | What |
| --- | --- |
| `~/.local/share/hypernova` | Paired phones, settings and keys (private to your user); the daemon in `app/`, its Node.js in `node/` |
| `~/.local/state/hypernova/logs/daemon.log` | The daemon's log |
| `~/.local/bin/hypernova` | The command |
| `~/.config/systemd/user/hypernova.service` | The service, with lingering on for your user |
| `~/.claude/settings.json` | Hooks and the status line tap |
| `~/.bashrc` or `~/.zshrc` | The shell integration block |

The service listens on `127.0.0.1:47321` (the phone API, reached through Tailscale) and
`127.0.0.1:47322` (the menu bar app, the `hypernova` command and the hooks), and on port 47323 on
your Wi‑Fi for paired phones only (mutual TLS; off on a VPS and in a container).
[docs/SECURITY-MODEL.md](SECURITY-MODEL.md) explains what each one lets in.

macOS may also ask, later and only when a feature needs it:

- **Files and Folders** (Documents, Desktop, iCloud Drive) for `node`, when your phone opens a
  project there. Allow it, or keep projects elsewhere.
- **Incoming connections** for `node`, if the macOS firewall is on: allow it, or phones on your
  Wi‑Fi can't connect.
- Your **password**, if you choose Backpack mode (keep the Mac awake with its lid closed).

## Update

**The Mac app.** Download the new `Hypernova.dmg`, quit Hypernova (menu bar → **Quit**), drag the
new one onto Applications and replace the old one, then open it. macOS may ask as on the first
launch. The menu bar then says *A newer service is ready*: click **Update**. Shared terminals keep
running, and Claude Code's SDK carries over (when the new service wants another version of it, that
one is downloaded, and the one you had keeps working if it can't be).

**Linux.** Download the new `hypernova-server-<tag>-linux.tar.gz` and run the install script from it again, as
the first time. The new daemon is prepared beside the old one and swapped in once it works; open
sessions keep running, and your phones and settings stay.

**Docker.** Pull the repository, build the image again, remove the container and run it again with
the same volume: `docker build -t hypernova . && docker rm -f hypernova`, then the same
`docker run` as before.

**The phone.** A Mac offers the phone the app it carries, and the app updates itself when a newer
one is signed with the same key. Anywhere, you can install a newer `hypernova-<tag>.apk` over the old
one; your pairing stays.

**Claude Code and Codex** update themselves or with `claude update` and `codex update`. Hypernova
doesn't need to know.

## Uninstall

**On a Mac**

1. In the menu bar, open **Settings** (the gear) → **Uninstall…**. Hypernova removes what it added
   to Claude Code (hooks, status line tap), the block in `~/.zshrc`, the background service and its
   login items, Backpack mode's permission, and this Mac's private name under
   `hypernova.observer`. Claude Code and Codex stay as they are. Tick **Also delete Hypernova's
   data** to remove its folder and logs too (paired phones, providers, the downloaded Node.js).
2. Quit Hypernova and move `/Applications/Hypernova.app` to the Trash.

**On Linux**

```sh
hypernova uninstall            # keeps paired phones and settings, for a reinstall
hypernova uninstall --purge    # removes them too, and signs the built-in Tailscale node out
```

It removes the integration (hooks, status line tap, the shell block), Tailscale's route to the
daemon, the service, the command, the daemon and the Node.js it downloaded. Claude Code, Codex and
their logins stay. Lingering stays on too (other services of yours may use it); it says how to
turn it off.

**In Docker**: `docker rm -f hypernova`, then `docker volume rm hypernova-home` to drop its state.

**Then, anywhere**: uninstall Hypernova on the phone like any app, and with the built-in Tailscale,
remove `hypernova-<name>` from your tailnet in the
[admin console](https://login.tailscale.com/admin/machines) (`--purge` signs it out already).

## Troubleshooting

**On a Mac**

- **“Hypernova is damaged and can't be opened.”** The download was changed on the way or the
  quarantine flag is stuck. Check the file with `shasum -a 256 -c --ignore-missing SHA256SUMS.txt`,
  then run `xattr -dr com.apple.quarantine /Applications/Hypernova.app`.
- **Setup can't find Claude Code or Codex.** Open a new Terminal window and run
  `command -v claude` (or `codex`). If it prints nothing, the command isn't on your PATH: install
  it with one of the lines above, then click **Check again**.
- **Codex: "needs an update".** `codex update`, then **Check again**.
- **The service isn't answering.** Setup → **Background service** → **Start it again**. **Show
  log** opens `~/Library/Logs/Hypernova/daemon.log`.
- **“Claude Code’s SDK isn’t installed”** (on the phone, for the "/" list, Rewind or a new
  session). The Mac was offline during Setup, or npm couldn't reach its registry (a proxy?). Setup →
  **Background service** → **Fetch again**, or the same button in the menu bar or **Agents &
  providers**. The service keeps running and uses it as soon as it's there.
- **`claude` in a terminal doesn't show up as shared.** Open a new tab after Connect Claude Code.
- **See what Setup sees**, without changing anything:

  ```sh
  /Applications/Hypernova.app/Contents/MacOS/Hypernova --selftest
  ```

**On Linux**

- `hypernova doctor` first: each problem comes with the command that fixes it. Then
  `hypernova logs`.
- *"Building the terminal module needs make, g++, python3"*: `sudo apt-get install -y
  build-essential python3`, then `hypernova setup`.
- *"systemd's user manager isn't running"*: `sudo loginctl enable-linger $USER`, or log in over
  SSH, then `hypernova setup`.
- *"Tailscale lets only root or its operator change serve"* (with the Tailscale app):
  `sudo tailscale set --operator=$USER`, then `hypernova pair`.
- **Codex isn't found.** `command -v codex` in a new login shell must print its path; then
  `hypernova codex`.

**Anywhere**

- **The phone can't reach the computer.** On the same Wi‑Fi it should just work: check that the
  computer is awake and the phone is on that Wi‑Fi (not a guest network that keeps devices apart).
  Away from it (and always for a VPS), the phone and the computer must be signed in to the same
  Tailscale account, and the computer's key must not have expired.

Still stuck? Open an issue with what `--selftest` or `hypernova doctor` printed (it names your
folders, so look it over first) and the last lines of `daemon.log`. Security problems: see
[SECURITY.md](../SECURITY.md).

## Build it yourself

**The Mac app.** You need Command Line Tools (`xcode-select --install`), Node.js 22.18 or newer
with npm, and for the built-in Tailscale, Go. Then:

```sh
cd daemon && npm ci && cd ..
mac/build.sh --open                  # dist/Hypernova.app
mac/build.sh --release && mac/dmg.sh # dist/Hypernova.dmg, as released
```

`--release` stops rather than leave something out: it wants a release-signed APK in `dist/` and
the Tailscale helper (built with Go). Claude Code's SDK is never in the app (Setup downloads it on
the Mac that runs it); `npm ci` above puts it in your checkout for development.

**Linux.** From a checkout on the server, `scripts/install-linux.sh` installs from it directly.
`scripts/pack-linux.sh` makes the package (`dist/linux/hypernova-<version>.tar.gz`, with the
built-in Tailscale when Go is around); each release attaches it as
`hypernova-server-<tag>-linux.tar.gz` ([RELEASING.md](RELEASING.md), "This fork's releases").

**The Android app** needs the JDK that comes with Android Studio:

```sh
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug
```

[CONTRIBUTING.md](../CONTRIBUTING.md) has the rest.
