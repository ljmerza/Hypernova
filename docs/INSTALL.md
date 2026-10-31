# Install Hypernova

Hypernova is a control room for Claude Code and Codex. A small service runs on a Linux server you
own, next to your agents, and you reach it from your phone (the Android app). Your phone reaches that
server over your Wi‑Fi, or from anywhere over your own VPN into that network. There is no
Hypernova server in between.

This is the whole install guide.

## Where Hypernova runs

| | **A Linux server** | **Docker** |
| --- | --- | --- |
| For | A VPS, a home server, a Linux PC | Trying it out, or a box that already runs containers |
| You install | `hypernova-server-<tag>-linux.tar.gz`: the `hypernova` command and the service | The image built from this repository |
| You drive it with | `hypernova` over SSH | `docker exec … hypernova` |
| Runs | Ubuntu or Debian, x86_64 or arm64, systemd | Docker (tested on arm64; the image is about 2.7 GB) |

**A Windows PC** runs the Linux service too, inside WSL 2 (Ubuntu) or in a Docker Desktop container:
**experimental**, documented but not yet tried on a real Windows PC. See
[4. Windows (experimental)](#4-windows-experimental-wsl2-or-docker-desktop).

Whichever you choose, the phone side is the same: **the Android app** (Android 10 or later) pairs
with the server by a code.

## What you need

- **The computer**:
  - a Linux machine on Ubuntu or Debian (others work with their packages installed by hand),
    x86_64 or arm64, with systemd, sudo for the system packages, and a normal user to run it as, or
  - Docker (the image is tested on arm64 so far).
- **An agent** on that computer: [Claude Code](https://docs.claude.com/en/docs/claude-code),
  OpenAI's [Codex](https://github.com/openai/codex) (0.163.0 or later), or both. Either one alone is
  enough. Hypernova can install Claude Code for you; Codex you install yourself.
- **An Android phone** on **Android 10 or later**.
- Optional: your own VPN into the computer's network (WireGuard, a UniFi VPN…), to reach it when
  the phone isn't on its Wi‑Fi. On the same Wi‑Fi you don't need one.

Nothing else: Hypernova brings its own Node.js.

## 1. Download

Releases are on [GitHub](https://github.com/ljmerza/Hypernova/releases/latest). Each one is named
`v<version>-ljm.<n>` (the tag below) and carries:

- `hypernova-server-<tag>-linux.tar.gz`: the Linux server package (one package for x86_64 and
  arm64: Node.js and the terminal module are fetched and built for the server at install)
- `hypernova-<tag>.apk`: the Android app

On the server, `curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash`
downloads the newest server package and installs it in one go (`scripts/get.sh`). By hand,
`gh release download -R ljmerza/Hypernova -p 'hypernova-server-*-linux.tar.gz'` fetches it; or save
it from the release page and copy it over.

## 2. On a Linux server

Everything is the `hypernova` command, over SSH: there is no window. Run it all as
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
   `--no-claude` to the script. Sessions run as Claude Code's background sessions (`claude
   agents`) unless you turned that off; Settings → Agents & providers → Claude Code in the app
   switches it.
5. **The service**: a systemd user service, `hypernova.service`, with **lingering** turned on so it
   runs at boot with nobody logged in.
6. **The integration**: hooks and the status line tap in `~/.claude/settings.json`, and the `claude`
   function in `~/.bashrc` (or `~/.zshrc`).

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

   It shows the **pairing code** as a QR code, and the link under it. The phone pairs over the
   local route, so it must be on the server's network: the same Wi‑Fi, or your VPN into it.
3. Point the phone's camera at the QR code in the terminal (or open the link on the phone). It is
   the server's own pairing page on your network, `https://<the server's address>:47323/pair?…`:
   your browser warns once about the server's own certificate (go on: **Advanced**, then
   **Proceed**), and the page opens Hypernova, which pairs. Nothing outside your network is
   involved. Too narrow a window, or no camera? Copy the link under it and use **Paste pairing
   link** in the app. A code works once and lasts 10 minutes; `hypernova pair` again makes a new one.
4. Allow notifications when the app asks, so approvals reach you.

### Check it

```sh
hypernova status      # what runs, how phones reach it, Claude Code, Codex, who's paired
hypernova doctor      # every piece checked, each problem with the command that fixes it
hypernova logs -f     # the daemon's log
systemctl --user status hypernova
```

### Remote access

- **On the same network** (a home server, a PC), the phone reaches the server directly: the local
  route, port 47323, encrypted and pinned to this server by the pairing code, with the phone's own
  device certificate. It's off on a machine with a public address (a VPS) and in a container:
  `hypernova lan on|off`.
- **Away from home**, use your own VPN into that network (WireGuard, a UniFi VPN…): the phone then
  takes the same local route. Nothing listens on a public port, and Hypernova carries no Tailscale
  of its own any more.

[docs/LINUX.md](LINUX.md) has every command and flag, where things live, and what a server doesn't
have (battery, Stay awake, the Mac's voice).

## 3. In Docker

The quick way to try it, on a machine with Docker. The image is built from this repository:

```sh
git clone https://github.com/ljmerza/Hypernova.git && cd Hypernova
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude              # sign in to Claude once
docker exec -it hypernova hypernova pair      # the pairing code
docker exec -it hypernova hypernova doctor
```

The image is Ubuntu 24.04 with the install script already run in it, as a normal user. Your state
(paired phones, settings, keys, the agents' logins) lives in the `hypernova-home` volume. Nothing is
published to the network, and the phone reaches Hypernova only over the local route: on a Linux
host, add `--network host` to `docker run` and turn the route on with `docker exec hypernova
hypernova lan on`, then a phone on the same network (or your VPN into it) pairs directly.
`--build-arg CLAUDE=no` builds it without Claude Code.

**Codex in the container**: its npm package needs Node.js on the PATH, which the image doesn't
have, so use Codex's own Linux build from its [GitHub releases](https://github.com/openai/codex/releases):

```sh
docker exec hypernova mkdir -p /home/hypernova/.local/bin
docker cp codex hypernova:/home/hypernova/.local/bin/codex
docker exec -it hypernova codex login --device-auth
docker exec -it hypernova hypernova codex on
```

## 4. Windows (experimental): WSL2 or Docker Desktop

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
Ubuntu terminal) and pairing.
`hypernova doctor` and `hypernova pair` know when they run in WSL and say what differs.

**What doesn't:**

- **Claude Code and Codex must be installed inside WSL** (their Linux builds; a Windows install
  that WSL's shell finds through Windows' PATH is ignored). Sessions you run in PowerShell, Command
  Prompt or a Windows app are outside WSL: Hypernova doesn't see them.
- What a Linux server lacks ([LINUX.md](LINUX.md), "What a Linux server doesn't have"): battery,
  Stay awake, the Mac's voice, opening a terminal window from the phone.
- No tray app and no Setup window: everything is the `hypernova` command in an Ubuntu terminal.
- WSL's default network (NAT) keeps the PC off your phone's Wi‑Fi, so the phone has no way in
  until you turn on mirrored networking (below).

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

   It shows the pairing code, as in [Pair your phone](#pair-your-phone); with WSL's default NAT it
   says the phone has no way in until networking is mirrored (below). Install `hypernova-<tag>.apk`
   on the phone first.
7. **Check it**: `hypernova doctor` adds WSL's own lines (systemd, keeping WSL running, the network
   mode), each with what to do.

### Optional: the phone on the same Wi‑Fi (mirrored networking)

On Windows 11 22H2 and later, WSL can share Windows' own network addresses
([Microsoft: mirrored mode networking](https://learn.microsoft.com/windows/wsl/networking#mirrored-mode-networking)).
Then a phone on the same Wi‑Fi can reach Hypernova directly, as it does a Linux server:

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
docker exec -it hypernova hypernova pair      # the pairing code
docker exec -it hypernova hypernova doctor
```

Everything else is as in [3. In Docker](#3-in-docker): Claude Code is inside the image (Codex can be
copied in), your state is in the `hypernova-home` volume and nothing is published to the network.
Docker Desktop's network keeps the container off the phone's Wi‑Fi, so a phone has no way in yet
(untested). Docker Desktop must be running for the
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
- **Then**, as anywhere: uninstall the app on the phone ([Uninstall](#uninstall)).

## Your phone

The Android app is the same for every server, and pairs with one at a time.

- **Get it** from the [release](https://github.com/ljmerza/Hypernova/releases/latest)
  (`hypernova-<tag>.apk`). Android asks before installing from your browser or Files: allow
  installs from that source, then tap **Install**.
- **Pair** by scanning the code `hypernova pair` shows. The camera opens the server's own pairing
  page on your network (`https://<its address>:47323/pair?…`; the browser warns once about its
  certificate), which opens Hypernova; nothing outside your network is involved. **Paste pairing
  link** in the app takes the same link.
- **Notifications**: allow them when the app asks, so approvals reach you with the app closed.

## Claude Code and Codex

| On the computer | What Hypernova does |
| --- | --- |
| **Claude Code only** | Everything as above. |
| **Codex only** | Install with `--no-claude` and run `hypernova codex on`. New sessions go to Codex. |
| **Both** | Claude Code leads (the sun in the app is its usage). Turn Codex on (`hypernova codex on`, **Agents & providers…** on the phone, or a first Codex session from the phone) to use both: each has its own usage meters, and a conversation can switch from one to the other. |
| **Neither** | `hypernova setup` installs Claude Code unless told not to, and `hypernova doctor` says what's missing. |

### Claude Code

- Install it with `hypernova setup`, or yourself with
  `curl -fsSL https://claude.ai/install.sh | bash` (npm installs work too).
- Sign in once with your Claude plan or an API key. Hypernova never reads your Claude login: the
  service asks `claude auth status`.
- Keep it current with `claude update`. Hypernova follows the current Claude Code.

### Codex

Install it in a terminal, then sign in:

```sh
sudo npm i -g @openai/codex   # needs Node.js and npm
codex login --device-auth     # on a server; on a desktop, codex login opens your browser
```

Hypernova needs **Codex 0.163.0 or later** (`codex --version`; `codex update` to update). It uses
Codex only while it's on for Hypernova (`hypernova codex on`, or the switch in the phone's
**Agents & providers**), as one client of Codex's own shared app server, so your Codex sessions
keep running when Hypernova restarts.

### How Hypernova finds your agents

For `claude` and `codex` alike, Hypernova looks in `~/.local/bin` (the official installers), in
the system's folders (`/usr/local/bin` and `/usr/bin`), npm's global folder
(`~/.npm-global/bin`), Volta (`~/.volta/bin`), Bun (`~/.bun/bin`), pnpm, every Node.js version
under nvm (`~/.nvm/versions/node/*/bin`), and whatever your login shell finds (`command -v claude`).
If you installed one somewhere else, make sure a new terminal finds it, then run `hypernova codex`
(or `hypernova doctor`).

## The shell integration

Connecting Claude Code (or turning on Codex, where Claude Code isn't installed) adds one marked
block to your shell's startup file: `~/.bashrc` for bash or `~/.zshrc` for zsh.

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

| Where | What |
| --- | --- |
| `~/.local/share/hypernova` | Paired phones, settings and keys (private to your user); the daemon in `app/`, its Node.js in `node/` |
| `~/.local/state/hypernova/logs/daemon.log` | The daemon's log |
| `~/.local/bin/hypernova` | The command |
| `~/.config/systemd/user/hypernova.service` | The service, with lingering on for your user |
| `~/.claude/settings.json` | Hooks and the status line tap |
| `~/.bashrc` or `~/.zshrc` | The shell integration block |

The service listens on `127.0.0.1:47321` (the phone API, on loopback) and
`127.0.0.1:47322` (the `hypernova` command and the hooks), and on port 47323 on
your Wi‑Fi for paired phones only (mutual TLS; off on a VPS and in a container).
[docs/SECURITY-MODEL.md](SECURITY-MODEL.md) explains what each one lets in.

## Update

**Linux.** Download the new `hypernova-server-<tag>-linux.tar.gz` and run the install script from it again, as
the first time (or the `get.sh` line again). The new daemon is prepared beside the old one and
swapped in once it works; open sessions keep running, and your phones and settings stay.

**Docker.** Pull the repository, build the image again, remove the container and run it again with
the same volume: `docker build -t hypernova . && docker rm -f hypernova`, then the same
`docker run` as before.

**The phone.** Install a newer `hypernova-<tag>.apk` over the old one; your pairing stays. When the
server carries a newer APK, the app offers it and updates itself, as long as it's signed with the
same key.

**Claude Code and Codex** update themselves or with `claude update` and `codex update`. Hypernova
doesn't need to know.

## Uninstall

**On Linux**

```sh
hypernova uninstall            # keeps paired phones and settings, for a reinstall
hypernova uninstall --purge    # removes them too
```

It removes the integration (hooks, status line tap, the shell block), the
service, the command, the daemon and the Node.js it downloaded. Claude Code, Codex and
their logins stay. Lingering stays on too (other services of yours may use it); it says how to
turn it off.

**In Docker**: `docker rm -f hypernova`, then `docker volume rm hypernova-home` to drop its state.

**Then, anywhere**: uninstall Hypernova on the phone like any app.

## Troubleshooting

**On Linux**

- `hypernova doctor` first: each problem comes with the command that fixes it. Then
  `hypernova logs`.
- *"Building the terminal module needs make, g++, python3"*: `sudo apt-get install -y
  build-essential python3`, then `hypernova setup`.
- *"systemd's user manager isn't running"*: `sudo loginctl enable-linger $USER`, or log in over
  SSH, then `hypernova setup`.
- **Codex isn't found.** `command -v codex` in a new login shell must print its path; then
  `hypernova codex`.
- **`claude` in a terminal doesn't show up as shared.** Open a new shell after setup, so the
  integration block is loaded.

**Anywhere**

- **The phone can't reach the computer.** On the same Wi‑Fi it should just work: check that the
  computer is awake and the phone is on that Wi‑Fi (not a guest network that keeps devices apart).
  Away from it (and always for a VPS), the phone must be on your own VPN into the computer's
  network, and the computer's local route on (`hypernova lan on`).

Still stuck? Open an issue with what `hypernova doctor` printed (it names your folders, so look it
over first) and the last lines of `daemon.log`. Security problems: see
[SECURITY.md](../SECURITY.md).

## Build it yourself

**Linux.** From a checkout on the server, `scripts/install-linux.sh` installs from it directly.
`scripts/pack-linux.sh` makes the package (`dist/linux/hypernova-<version>.tar.gz`); each release
attaches it as `hypernova-server-<tag>-linux.tar.gz` ([RELEASING.md](RELEASING.md), "Releases").

**The Android app** needs JDK 21 (Android Studio's, or any other) and the Android SDK:

```sh
cd android
JAVA_HOME=<your JDK 21> ./gradlew :app:assembleDebug
```

[CONTRIBUTING.md](../CONTRIBUTING.md) has the rest.
