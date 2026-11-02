# Install Hypernova

Hypernova is a control room for Claude Code and Codex. Two parts: **the server**, a small service on a
Linux server you own, next to your agents, and **the client**, the Android app you reach it from. The
phone talks straight to your server, over your Wi‑Fi or your own VPN into that network. There is no
Hypernova server in between.

This page takes you from nothing to a paired phone. Then: [away from home or on a
VPS](#away-from-home-or-on-a-vps), [update](#update), [uninstall](#uninstall),
[Codex](#codex-optional), [a Mac](#on-a-mac-untested-on-a-real-one), [Windows](#windows-experimental),
[containers](#in-a-container), [troubleshooting](#troubleshooting). Every command and flag is in
[LINUX.md](LINUX.md).

## What you need

- A server on **Ubuntu or Debian**, x86_64 or arm64, with systemd, and a normal user with sudo.
  Other distributions work with their packages installed by hand. A Mac works too, not yet tried on a
  real one: [On a Mac](#on-a-mac-untested-on-a-real-one).
- A phone on **Android 10 or later**, on the server's network or your own VPN into it.
- A Claude plan or an API key, to sign in to Claude Code. Or Codex instead:
  [Codex (optional)](#codex-optional).

The install brings everything else, Node.js included.

## Install

### 1. Install the server

Run the install as the user who will run the agents, not root. It refuses to run as root. If this
server only has root so far, as on a fresh VPS, make that user first, as root:

```sh
adduser claude
usermod -aG sudo claude
loginctl enable-linger claude
su - claude
```

Then, on the server:

```sh
curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
```

It asks for your password through `sudo`, for system packages only. It takes a few minutes and ends
with what's left to do.

When it's done, **log out and back in**, or open a new SSH session. A new login is what puts
`~/.local/bin` on your PATH, and a new shell is what loads the `claude` function.

What it put on your server: [What's on your server](#whats-on-your-server). Another release, no
questions, the steps by hand, or a package of your own: [LINUX.md, "Install"](LINUX.md#install).

**Or with the `.deb`**, for the same install with `apt` bringing the system packages. Download
`hypernova-server-<tag>-linux-amd64.deb` (x86_64) or `…-linux-arm64.deb` from the
[latest release](https://github.com/ljmerza/Hypernova/releases/latest), then:

```sh
sudo apt install ./hypernova-server-<tag>-linux-amd64.deb
hypernova-server-setup        # as the user who will run the agents, not root
```

The package only puts the release in `/usr/lib/hypernova-server` and adds that command: it starts no
service and touches no home folder. `hypernova-server-setup` is the install above, from that folder, and
everything after it is the same, including the server updating itself. Upgrading or removing the package
never touches a server that's already set up: [Uninstall](#uninstall).

### 2. Sign in to Claude Code

```sh
claude
```

Follow its steps: it shows a link to open on any device and asks for the code that page gives you.
You only do this once. (Once you've paired, Home's account card on the phone can sign it in too.)

### 3. Pair your phone

1. **Get the client, the app.** On the phone, download `hypernova-client-<tag>-android.apk` from the
   [latest release](https://github.com/ljmerza/Hypernova/releases/latest) and open it from your
   Downloads. When Android asks, allow installs from that source, then tap **Install**.
2. **Show the code.** On the server:

   ```sh
   hypernova pair
   ```

   It shows the pairing code as a QR code, and the link under it. It also says where to get the app:
   the releases page, or the server's own download page once the server has fetched the release's app,
   soon after it starts.
3. **Scan it** with the phone's camera, with the phone on the server's network (on a VPS or cloud VM,
   see [Away from home, or on a VPS](#away-from-home-or-on-a-vps) first). This opens the server's own
   pairing page, `https://<the server's address>:47323/pair?…`. Your browser warns once
   about the server's own certificate: go on (**Advanced**, then **Proceed**), and the page opens
   Hypernova, which pairs. The server says `Paired with …`.

   No camera, or a window too narrow for the code? Copy the link under it and use **Paste pairing
   link** in the app. A code works once and lasts 10 minutes; `hypernova pair` again makes a new one.
4. **Allow notifications** when the app asks, so approvals reach you with the app closed.

To add another server, run `hypernova pair` on it. The ones you paired stay, and Home's chip switches
between them.

### 4. Check it

```sh
hypernova status      # what runs, how phones reach it, Claude Code, Codex, who's paired
hypernova doctor      # every piece checked, each problem with the command that fixes it
```

That's it. Something wrong? See [Troubleshooting](#troubleshooting).

## Away from home, or on a VPS

The phone has one way in: the **local route**, port 47323. It's encrypted and pinned to this server
by the pairing code, and it asks for the phone's own device certificate (mutual TLS). Connections from
public addresses are dropped. Hypernova has no relay, name service or VPN of its own.

- **On the same network** (a home server, a PC), it just works.
- **Away from home**, use your own VPN into that network (WireGuard, say, or your router's VPN). The
  phone then takes the same route.
- **On a VPS or a cloud VM**, the route is off by default, because the phone is never on that
  server's network. You need a VPN that puts the phone on the server's private network, then
  `hypernova lan on`. Never open port 47323 to the internet: on a machine with a public address, block
  it with a firewall, because a scan can still find the port.

The pairing link only names private addresses on the server's real network interfaces, never a
tunnel's (`wg0`, `tun0`…). So the VPN has to land the phone on a network the server reaches through a
normal interface.

`hypernova lan on|off` turns the route on or off. What each port lets in:
[LINUX.md, "Security"](LINUX.md#security) and [SECURITY-MODEL.md](SECURITY-MODEL.md).

## Update

**The server updates itself**, unless you turn that off. It looks at GitHub's latest release 30
seconds after it starts, then every 6 hours. A newer release is checked against its SHA-256 and
installed at once, whatever sessions are doing. Sessions keep running through the restart, your phones
and settings stay, and if the new daemon doesn't answer, the old one is put back.

```sh
hypernova auto-update         # how it stands: on or off, whether this install can, the last update
hypernova auto-update off     # or on
```

The app has the same switch: Settings, in the server's section, **Update this server automatically**.

Only an install from a release's package does this (the `curl` line above, the `.deb`'s
`hypernova-server-setup`, or the package's own install script). An install from a git clone or in a
container doesn't, nor does [a Mac](#on-a-mac-untested-on-a-real-one) yet, and `hypernova auto-update`
says why. It also never asks for `sudo`, so a release that needs a new system package fails and
leaves the server as it was: install that one by hand.

**By hand**, run the `curl` line again, or after upgrading the `.deb`, `hypernova-server-setup` (it won't
go back from a newer server unless you add `--force`). The new daemon is prepared beside the old one and
swapped in once it works. Upgrading the `.deb` alone changes nothing that's running. From a clone: `git pull`, then `scripts/install-linux.sh`. In a container: see
[In a container](#in-a-container). Details: [LINUX.md, "Updating"](LINUX.md#updating).

**The phone.** The same check on the server fetches the release's app and offers it to your phones,
before the server restarts: the app shows a banner, and Settings → About shows **Update available**
with **Update**. Your pairing stays. Or install a newer `hypernova-client-<tag>-android.apk` over the old one. Either
way, it must be signed with the same key. `"releaseCheck": false` in
`~/.local/share/hypernova/settings.json` turns the app's part off; what GitHub sees is in
[SECURITY-MODEL.md](SECURITY-MODEL.md).

**Claude Code and Codex** update themselves, or with `claude update` and `codex update`. Hypernova
needs Codex 0.163.0 or later.

## Uninstall

```sh
hypernova uninstall            # keeps paired phones and settings, for a reinstall
hypernova uninstall --purge    # removes them, and the logs, too
```

It asks first (`--yes` skips that). It removes the integration (hooks, status line tap, the shell
block), the service, the command, the daemon and the Node.js it downloaded. Claude Code, Codex and
their logins stay, and shared sessions still open keep running until you close them. Lingering stays
on too (other services of yours may use it); it says how to turn it off.

If you used the `.deb`, also `sudo apt remove hypernova-server` (or `purge`; they do the same). Either
removes only the package's own files: a server set up from it keeps running, with its paired phones and
settings, until that user runs `hypernova uninstall`.

Then uninstall the client on the phone like any app.

## Codex (optional)

| On the server | What Hypernova does |
| --- | --- |
| **Claude Code only** | Everything above. |
| **Both** | Claude Code leads (the sun in the app is its usage). Turn Codex on to use both: each has its own usage meters, and a conversation can switch from one to the other. |
| **Codex only** | New sessions go to Codex. Install without Claude Code, as below. |
| **Neither** | The install adds Claude Code unless told not to, and `hypernova doctor` says what's missing. |

Hypernova never installs Codex or signs it in. Install it, sign it in, and turn it on:

```sh
sudo apt-get install -y nodejs npm     # if this server has no npm yet
sudo npm i -g @openai/codex            # lands in /usr/local/bin/codex
codex login --device-auth              # a code to enter on any device; no browser needed here
hypernova codex on
```

- Or put Codex's own Linux build from its [GitHub releases](https://github.com/openai/codex/releases)
  in `~/.local/bin/codex`.
- With an API key instead of ChatGPT: `printenv OPENAI_API_KEY | codex login --with-api-key`.
- `hypernova codex` on its own says what it found. `hypernova codex off` lets go of it.
- You can also turn it on from the phone, in **Agents & providers**, and a first Codex session from
  the phone turns it on too.

For Codex alone, install without Claude Code:

```sh
curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash -s -- --no-claude
```

Hypernova uses Codex only while it's on, as one client of Codex's own shared app server, so your Codex
sessions keep running when Hypernova restarts. While it's on, `codex` in a new terminal runs in a
shared terminal, like `claude`.

<a id="4-windows-experimental-wsl2-or-docker-desktop"></a>

## On a Mac (untested on a real one)

The server runs on a Mac too: Apple silicon or Intel, macOS 13.5 or later. There's no menu bar app; it's
the same `hypernova` command as on Linux, run in Terminal.

**Nobody has run this on a Mac they use yet.** The only Mac it has run on is CI's runner, which installs
it, checks it and removes it for each release (`scripts/test-macos-smoke.sh`). What that can't show, such
as what macOS asks a person at the screen, comes from Apple's documents and the Mac app this project used
to have, and is marked below. If you try it, an issue saying how it went helps.

### Install it

As the user who will run the agents, in Terminal at the Mac:

```sh
curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
```

It needs no `sudo`. It puts Node.js 24, the daemon and Claude Code in your own folders, then starts the
service. **Then open a new terminal**: Hypernova's block in `~/.zshrc` is what puts `~/.local/bin` on your
PATH, which a Mac doesn't do by itself. Then [sign in and pair](#2-sign-in-to-claude-code) as on Linux.

Each release also has the same thing as files, named for the Mac's processor (`uname -m` says `arm64`, or
`x86_64` for `x64`):

| File | How |
|---|---|
| `hypernova-server-<tag>-macos-arm64.tar.gz`, `…-macos-x64.tar.gz` | Unpack it, then `bash hypernova-*/scripts/install-macos.sh`. |
| `hypernova-server-<tag>-macos-arm64.pkg`, `…-macos-x64.pkg` | The Installer puts the release in `/usr/local/lib/hypernova-server` and adds `hypernova-server-setup`, nothing else. Then, in Terminal, as your user: `hypernova-server-setup`. |

### What macOS shows you

**The `curl` line shows no Gatekeeper warning**: only files a browser saved carry the mark Gatekeeper looks
for, and `curl` doesn't add it. The same goes for a package you fetch with `curl` or `gh`.

**A `.pkg` saved by a browser is blocked**, because it isn't signed. To open it:

- **macOS 15 and later:** macOS says *Apple could not verify "…" is free of malware that may harm your Mac
  or compromise your privacy*. Click **Done**. Open **System Settings → Privacy & Security**, scroll down
  to **Security**, and click **Open Anyway** next to it. Give your password or Touch ID, then **Open
  Anyway** once more.
- **macOS 14:** macOS says it can't be opened because it's from an unidentified developer. Click **OK**,
  then Control‑click the `.pkg`, choose **Open**, then **Open**.
- **Or in Terminal, on any version:** `xattr -d com.apple.quarantine <file>.pkg`, then
  `sudo installer -pkg <file>.pkg -target /`.

Those words are what macOS showed for the old Mac app's unsigned download; a `.pkg` may word them a little
differently.

**After the install**, as far as we know from Apple's documents and the old Mac app, and not yet seen with
this install:

- A **Background Items Added** notice: that's the service. It's listed in **System Settings → General →
  Login Items & Extensions**, under Allow in the Background. Leave it on.
- **"node" would like to access files in your Documents folder**, or Desktop, Downloads, iCloud Drive or an
  external disk, the first time something opens one. The service runs as `node`. Say **Allow**. If nobody
  is at the screen to answer, the app says so, and it's in **System Settings → Privacy & Security → Files
  and Folders** (or Full Disk Access).
- With the **firewall** on, it may ask whether `node` may accept incoming connections. Phones come in
  through it, so **Allow**. macOS may also ask about `node` and your local network: allow that too.

### What's different from Linux

| | On a Mac |
|---|---|
| The service | A LaunchAgent, `~/Library/LaunchAgents/com.prudnikweb.hypernova.daemon.plist`. launchd restarts it when it dies; sessions keep running through a restart. |
| When it starts | **When you log in, not at boot.** After a restart the server waits for a login. For a Mac that should come back by itself, turn on **Automatic login** (System Settings → Users & Groups; FileVault keeps it off). Over `ssh` with nobody logged in at the screen there's no service, and setup says so. |
| Updates | **By hand: run the `curl` line again.** A Mac doesn't update itself yet, because a broken update on a machine nobody can reach is worse than none, and it has never run on a real Mac. `hypernova auto-update` and the app's Settings say so. The phone's app still updates as usual. |
| State | `~/Library/Application Support/Hypernova` |
| Logs | `~/Library/Logs/Hypernova/daemon.log`, and `daemon.stderr.log` for what the daemon couldn't log |
| The terminal module | node-pty comes prebuilt for both processors, so nothing is compiled and **Xcode isn't needed for it**. Apple's Command Line Tools (`xcode-select --install`) are for git, which Claude Code and the app's git views use. The install says if they're missing, and goes on. |
| Sleeping | A Mac that sleeps is away from your phone. The app's **Stay awake** keeps it up while Claude works. |
| Your shell | The `claude` function goes in `~/.zshrc`, for zsh, a Mac's default. With another login shell `claude` stays plain, and add `~/.local/bin` to your PATH yourself. |

`hypernova status`, `doctor`, `pair`, `logs` and `uninstall` work as on Linux. To restart the service by
hand: `launchctl kickstart -k gui/$(id -u)/com.prudnikweb.hypernova.daemon`.

**To remove it**, `hypernova uninstall` (`--purge` for paired phones and settings too). If you used the
`.pkg`, that leaves its files:
`sudo rm -rf /usr/local/lib/hypernova-server /usr/local/bin/hypernova-server-setup`, then
`sudo pkgutil --forget com.prudnikweb.hypernova.server`.

## Windows (experimental)

There is no Windows app and no native Windows service. A Windows PC runs Hypernova's **Linux** service
instead, inside **WSL 2** (Windows Subsystem for Linux, with Ubuntu) or in a **Docker Desktop**
container.

> **Experimental.** These steps are written from Microsoft's and Docker's documentation and from
> Hypernova's Linux tests. **Nobody has tried them on a real Windows PC yet.** That includes WSL
> stopping and starting the service, mirrored networking with the Hyper-V firewall rule, Docker
> Desktop building the image on an x86_64 PC, and Windows on Arm. If you try, please
> [open an issue](https://github.com/ljmerza/Hypernova/issues) with what worked and what
> `hypernova doctor` printed (it names your folders: look it over first).

**What should work**, as on any Linux: the service, Claude Code and Codex sessions, approvals, usage,
shared terminals (`claude` typed in an Ubuntu terminal) and pairing. `hypernova doctor` and
`hypernova pair` know when they run in WSL and say what differs.

**What doesn't:**

- **Claude Code and Codex must be installed inside WSL** (their Linux builds; a Windows install that
  WSL's shell finds through Windows' PATH is ignored). Sessions you run in PowerShell, Command Prompt
  or a Windows app are outside WSL: Hypernova doesn't see them.
- No tray app: everything is the `hypernova` command in an Ubuntu terminal.
- WSL's default network (NAT) keeps the PC off your phone's Wi‑Fi, so the phone has no way in until
  you turn on mirrored networking (step 6 below).
- What any Linux server lacks: [LINUX.md, "What a Linux server doesn't have"](LINUX.md#what-a-linux-server-doesnt-have).

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
3. **Install Hypernova inside Ubuntu**, in your Linux home, not under `/mnt/c`:

   ```sh
   cd ~
   curl -fsSL https://ljmerza.github.io/Hypernova/get.sh | bash
   ```

   It says it found WSL, and installs Claude Code's Linux build unless you add `--no-claude`
   ([Codex](#codex-optional) likewise goes inside Ubuntu).
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
6. **Let the phone in with mirrored networking.** On Windows 11 22H2 and later, WSL can share Windows'
   own network addresses
   ([Microsoft: mirrored mode networking](https://learn.microsoft.com/windows/wsl/networking#mirrored-mode-networking)).
   In `%UserProfile%\.wslconfig`:

   ```ini
   [wsl2]
   networkingMode=mirrored
   ```

   Then `wsl --shutdown` and open Ubuntu again. In Ubuntu, `wslinfo --networking-mode` now prints
   `mirrored`. Let the local route's port in through Windows' Hyper-V firewall, once, in PowerShell
   as administrator:

   ```powershell
   New-NetFirewallHyperVRule -Name Hypernova -DisplayName "Hypernova local route" -Direction Inbound -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 47323
   ```

   `hypernova status` should show the local route on, with the PC's Wi‑Fi address (`hypernova lan on`
   if it's off).
7. **Pair your phone**, in Ubuntu, with `hypernova pair`, as in
   [Pair your phone](#3-pair-your-phone). With WSL's default NAT it says the phone has no way in.
8. **Check it**: `hypernova doctor` adds WSL's own lines (systemd, keeping WSL running, the network
   mode), each with what to do.

With WSL's default NAT, Hypernova keeps the local route off and leaves WSL's own addresses out of the
pairing link, since no phone can reach them. A `netsh interface portproxy` forward isn't supported:
from inside WSL the service can't learn the PC's Wi‑Fi address to put in the pairing link.

**Update**: as on a Linux server ([Update](#update)). `wsl --update` updates WSL itself.

**Uninstall**: `hypernova uninstall` in Ubuntu ([Uninstall](#uninstall)). Then remove what you added
on Windows: the lines in `%UserProfile%\.wslconfig`, and the firewall rule, in PowerShell as
administrator: `Remove-NetFirewallHyperVRule -Name Hypernova`. `wsl --unregister Ubuntu` deletes the
whole Ubuntu with everything in it: only if you want that.

### With Docker Desktop

Docker Desktop for Windows runs Linux containers on WSL 2 (its "Use the WSL 2 based engine" setting,
on by default where WSL 2 is installed: [Docker's guide](https://docs.docker.com/desktop/features/wsl/)).
**Its network keeps the container off the phone's Wi‑Fi, so a phone has no way in yet** (untested).
Use WSL if you want to pair.

There's no published image yet, so build it from a clone of this repository, in PowerShell (with
[Git](https://git-scm.com)) or in Ubuntu:

```sh
git clone https://github.com/ljmerza/Hypernova.git
cd Hypernova
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude              # sign in to Claude once
docker exec -it hypernova hypernova doctor
```

Docker Desktop must be running for the container to run (it has a setting to start when you sign in).
The rest, updating and uninstalling included, is as in [In a container](#in-a-container).

## In a container

A way to try it, on a machine with Docker. The image is built from this repository, is large (over a
gigabyte), and is tested on arm64 so far. **The phone only gets in on a Linux host, with
`--network host`:**

```sh
git clone https://github.com/ljmerza/Hypernova.git && cd Hypernova
docker build -t hypernova .
docker run -d --name hypernova --hostname my-hypernova --network host --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
docker exec -it hypernova claude              # sign in to Claude once
docker exec hypernova hypernova lan on        # the phone's way in: off by default in a container
docker exec -it hypernova hypernova pair      # the pairing code
docker exec -it hypernova hypernova doctor
```

The image is Ubuntu 24.04 with the install script already run in it, as a normal user. Your state
(paired phones, settings, keys, the agents' logins) lives in the `hypernova-home` volume. Without
`--network host` nothing is published to the network and a phone has no way in. With it, the
container uses the host's ports, so don't run it beside a Hypernova installed on that host.
`--build-arg CLAUDE=no` builds it without Claude Code. More: [LINUX.md, "Try it in Docker"](LINUX.md#try-it-in-docker).

**Codex**: its npm package needs Node.js on the PATH, which the image doesn't have, so use Codex's own
Linux build from its [GitHub releases](https://github.com/openai/codex/releases):

```sh
docker exec hypernova mkdir -p /home/hypernova/.local/bin
docker cp codex hypernova:/home/hypernova/.local/bin/codex
docker exec -it hypernova codex login --device-auth
docker exec -it hypernova hypernova codex on
```

**Update**: a container never updates itself. In the clone, `git pull`, then
`docker build -t hypernova . && docker rm -f hypernova`, then the same `docker run` as before, with the
same volume.

**Uninstall**: `docker rm -f hypernova`, `docker volume rm hypernova-home` (its state) and
`docker image rm hypernova`. Then uninstall the app on the phone.

## Troubleshooting

Start with `hypernova doctor`: each problem comes with the command that fixes it. Then `hypernova logs`
(`-f` to follow) and `systemctl --user status hypernova`.

### Installing

- **`get.sh: run it as the user who will run the agents, not root`.** Make a user first:
  [Install the server](#1-install-the-server).
- **`hypernova: command not found`, or `claude`, right after the install.** Log in again (Ubuntu adds
  `~/.local/bin` to your PATH at login), or run `~/.local/bin/hypernova`.
- **`sudo` says your user isn't allowed, or `installing packages needs root, and there's no sudo`.**
  As root, install the packages it names, or give the user sudo.
- **`no apt-get here: install these yourself, then run this again`.** Another distribution: install
  the packages it names with its own package manager.
- **`Building the terminal module needs make, g++, python3`.** `sudo apt-get install -y build-essential
  python3`, then `hypernova setup`.
- **`systemd's user manager isn't running`.** `sudo loginctl enable-linger $USER`, or log in over SSH,
  then `hypernova setup`.
- **`Lingering is off: the service would stop when you log out`.** `sudo loginctl enable-linger $USER`.
- **`The daemon isn't running`.** `systemctl --user start hypernova`, then `hypernova logs` to see why
  it stopped.
- **`hypernova-server-setup: run it as the user who will run the agents`.** Without `sudo`, as that user.
- **`Your server is already on …, newer than this package`.** It updated itself past the `.deb`, which is
  fine: nothing to do. `hypernova-server-setup --force` goes back to the package's version.
- **On a Mac: `Nobody is logged in at this Mac's screen`.** The service lives in the login session: log in
  at the screen, then `hypernova setup`. Until then, `hypernova run` in a terminal you keep open.
- **On a Mac: `launchctl bootstrap … Bootstrap failed`.** Look under **System Settings → General → Login
  Items & Extensions → Allow in the Background**. Over `ssh` it can also fail with someone logged in: run
  `hypernova setup` in Terminal at the screen.
- **On a Mac: `hypernova: command not found`, or `claude`.** Open a new terminal. With a login shell other
  than zsh: `export PATH="$HOME/.local/bin:$PATH"` in that shell's startup file.

### Pairing and reaching the server

- **`The local network route is off, so a phone has no way in yet`.** `hypernova lan on`, only on a
  network you trust. It's off by default on a VPS, a cloud VM and in a container. You also see this
  when the server has no private address on a normal interface: see
  [Away from home, or on a VPS](#away-from-home-or-on-a-vps).
- **The browser warns about the certificate.** Expected, once: it's the server's own certificate. Go
  on (**Advanced**, then **Proceed**).
- **`This window is … columns wide; the code needs …`.** Widen the window or zoom out, or copy the
  link under the code into **Paste pairing link** in the app.
- **`The code ran out before a phone used it`.** `hypernova pair` makes a new one.
- **The phone can't reach the server.** On the same Wi‑Fi it should just work. Check that:
  - the server is awake, and `hypernova status` shows the local route on, with an address;
  - the phone is on that Wi‑Fi, not a guest network that keeps devices apart;
  - a firewall on the server (`ufw`, say) lets TCP port 47323 in from your network;
  - away from it, and always for a VPS, the phone is on your own VPN into the server's network.

### Using it

- **`Claude Code isn't signed in`, or Home says it's signed out.** Run `claude` once on the server, or
  tap **Sign in** on Home's account card.
- **`claude` in a terminal doesn't show up as shared.** Open a new shell after the install, so the
  integration block is loaded.
- **Claude Code or Codex isn't found.** Hypernova looks in `~/.local/bin` (the official installers),
  `/usr/local/bin`, `/usr/bin`, npm's global folder (`~/.npm-global/bin`), Volta (`~/.volta/bin`), Bun
  (`~/.bun/bin`), pnpm, every Node.js version under nvm (`~/.nvm/versions/node/*/bin`), and whatever
  your login shell finds. `command -v codex` in a new login shell must print its path; then run
  `hypernova codex` (or `hypernova doctor`).
- **`hook error: connect ECONNREFUSED` in a terminal.** The daemon was restarting, as in an update. The
  session carries on, and a waiting approval stays in Claude Code's own dialog.
- **`Auto-update isn't available here`, or an update failed.** `hypernova auto-update` says why and when
  it tries again. To install by hand, run the `curl` line again.
- **`The Hypernova block in ~/.bashrc was edited`.** Hypernova won't change that file. Remove the lines
  between `>>> hypernova` and `<<< hypernova` yourself, then try again.

Still stuck? [Open an issue](https://github.com/ljmerza/Hypernova/issues) with what `hypernova doctor`
printed (it names your folders, so look it over first) and the last lines of `daemon.log`. Security
problems: see [SECURITY.md](../SECURITY.md).

## What's on your server

The install puts in what's missing (on a Mac: [On a Mac](#on-a-mac-untested-on-a-real-one)):

- **System packages**, with `apt-get`: curl, tar, gzip, openssl, git, `ps`, `lsof`, and build tools
  with python3, because the terminal module (node-pty) is built on the server. The `.deb` declares these
  as its dependencies instead, and adds `/usr/lib/hypernova-server` and `hypernova-server-setup`.
- **Node.js 24** from nodejs.org, checked against nodejs.org's checksums, into Hypernova's own folder.
  A Node.js 24 or newer already on the server is used instead.
- **The daemon** in `~/.local/share/hypernova/app`, and **the `hypernova` command** in `~/.local/bin`.
  Paired phones, settings and keys are in `~/.local/share/hypernova`, private to your user, and the log
  is `~/.local/state/hypernova/logs/daemon.log`.
- **Claude Code**, with Anthropic's installer (`curl -fsSL https://claude.ai/install.sh | bash`; an npm
  install works too).
- **The service**: a systemd user service, `~/.config/systemd/user/hypernova.service`, with lingering
  on, so it runs at boot with nobody logged in.
- **The integration**: hooks and a status line tap in `~/.claude/settings.json`, and a block in
  `~/.bashrc` or `~/.zshrc` (below).

The service listens on `127.0.0.1:47321` (the phone API, on loopback), `127.0.0.1:47322` (the
`hypernova` command and the hooks), and port 47323 on your network for paired phones only (mutual TLS;
off on a VPS, a cloud VM and in a container). More: [LINUX.md, "Where things live"](LINUX.md#where-things-live).

Hypernova never reads your Claude login: the service asks `claude auth status`. New sessions run as
Claude Code's background sessions (`claude agents`), unless you turn that off in the app: Settings →
Agents & providers → Claude Code → **Sessions in claude agents**.

### The shell integration

The install adds one marked block to your shell's startup file:

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
- zsh and bash only. With another shell nothing is added: sessions you start in a terminal still show
  on the phone through the hooks, but the phone can't type into them.

Before changing your startup file or `~/.claude/settings.json`, Hypernova saves a copy in its `backups`
folder.

## Build it yourself

**The server.** From a clone, on the server:

```sh
git clone https://github.com/ljmerza/Hypernova.git && cd Hypernova
scripts/install-linux.sh
```

This install doesn't update itself: see [Update](#update). `scripts/pack-linux.sh` makes a package of
your own: [LINUX.md, "Install"](LINUX.md#install).

**The Android app** needs JDK 21 (Android Studio's, or any other) and the Android SDK:

```sh
cd android
JAVA_HOME=<your JDK 21> ./gradlew :app:assembleDebug
```

[CONTRIBUTING.md](../CONTRIBUTING.md) has the rest.
