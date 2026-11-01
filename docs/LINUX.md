<!-- Also the server package's README.md (scripts/pack-linux.sh), which has no docs/ folder beside it: link to other docs by full URL. -->
# Hypernova on Linux

The reference for running Hypernova's daemon on a Linux server or PC: every command and flag, where
things live, ports, the service, updates and logs. To install it, follow
[INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md).

There is no window on a server: everything is the `hypernova` command, over SSH. Run it as the user
who runs Claude Code or Codex, not as root. Supported: Ubuntu and Debian, on x86_64 and arm64. Other
distributions work with their packages installed by hand.

## The commands

Each command's flags are in the rows under it.

| Command or flag | What it does |
|---|---|
| **`hypernova setup`** | Checks what's there and installs what's missing: Node.js 24, the daemon, the `hypernova` command, Claude Code (Anthropic's installer), the service with lingering, the hooks and status line, the `claude` shell function. Turns on sessions in `claude agents` unless `settings.json` already says either way (the app: Settings → Agents & providers → Claude Code). Says what it found of Codex. Safe to run again. Never signs in: run `claude` once for that. |
| `--from <dir>` | Install from a checkout or an unpacked package. |
| `--no-claude` | Don't install Claude Code (Codex alone). |
| `--no-service` | Skip the service. |
| `--no-integration` | Skip the hooks and the shell function (a container image). |
| **`hypernova pair`** | The pairing QR code and link (the server's own page on the local route), and where to get the app. Waits for the phone and names it. A code works once, for 10 minutes. With the local route off it says so and prints the app's own `hypernova://pair` link. Exit 1 when the daemon isn't running or the code runs out. |
| `--no-wait`, `--no-wait-phone` | Don't wait for the phone. |
| `--no-qr` | The link and code only. |
| **`hypernova status`** | What runs, how phones reach it, Claude Code, Codex, the integration, paired devices, sessions. Exit 1 when the daemon isn't running. |
| `--json` | As JSON. |
| **`hypernova doctor`** | Every piece checked, each problem with the command that fixes it. Exit 1 when something stops Hypernova working. With Codex alone, a missing Claude Code is only noted; with neither agent, it fails. |
| `--json` | As JSON. |
| **`hypernova logs`** | The daemon's log, its last 100 lines. |
| `-n <lines>`, `--lines <lines>` | That many lines. |
| `-f`, `--follow` | Keep following it. |
| `--terminals` | The shared terminals' log instead. |
| **`hypernova lan on\|off`** | Turns the local route on or off, at once, and keeps it that way ([Ports](#ports)). |
| **`hypernova auto-update [on\|off]`** | The server installing newer releases by itself ([Updating](#updating)). Without a word: on or off, whether this install can and why not, what it's doing, a try that failed, the last update. `on` also looks for a release at once. |
| **`hypernova codex [on\|off]`** | OpenAI's Codex for Hypernova. Without a word: its path, its version against the minimum (0.163.0), on or off, signed in. `on` connects the daemon to Codex's own app server and adds a `codex` shell function; it refuses when Codex is missing or too old. Never installs or signs in Codex ([INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md), "Sign in to your agents"). |
| **`hypernova uninstall`** | Removes the integration, the service, the command, the daemon and the Node.js it downloaded. Keeps paired phones, settings and logs, Claude Code, Codex and their logins, and lingering (it says how to turn that off). Open shared sessions keep running. Asks first; with no terminal it refuses without `--yes`. |
| `--purge` | Paired phones, settings and logs too. |
| `--yes` | Don't ask. |
| **`hypernova run`** | The daemon in the foreground, its log on the terminal too. A container's command, and handy for debugging. Stop the service first: it holds the same ports. |
| **`hypernova automation fire <id>`** | Fires an automation's webhook trigger on this computer, for git hooks, cron or CI. Exit 0: a run was made. 3: nothing happened (paused, off, fired too often). 1: an error. 2: wrong usage. |
| `--data <text>` | The payload, as it is. |
| `--data-file <file>` | The payload from a file; `-` for standard input. Not with `--data`. |
| `--token <token>` | The automation's own token. Without it, `HYPERNOVA_FIRE_TOKEN`, then the admin token. |
| **`hypernova integrate`** | Puts the hooks, the status line and the shell function in again. A container runs it at each start. |
| **`hypernova version`** | The daemon's version. Also `--version`, `-v`. |
| **`hypernova help`** | The list of commands. Also `--help`, `-h`. |

Other names for the same command: `install` (setup), `log` (logs), `autoupdate` (auto-update),
`automations` (automation).

Flags take `--from <dir>` or `--from=<dir>`. **Unknown flags are ignored, not refused**, so check the
spelling: `hypernova uninstall --pruge` still uninstalls, and keeps your state.

### Shared terminals: `hn`

Every phone session runs in a shared terminal. So does `claude` typed in your shell, through a
function in a marked block in `~/.bashrc` or `~/.zshrc` (`# >>> hypernova shared terminals`), and
`codex` while it's on. With another login shell they stay plain. `command claude` or `HYPERNOVA_OFF=1`
skips it for one run. More: [INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md), "The shell integration".

| Command | What it does |
|---|---|
| `hn ls`, `hn list` | Lists the shared sessions. |
| `hn attach [id]`, `hn a` | Opens one in this terminal, by id, session id or project name. Ctrl+\ detaches and leaves it running. |
| `hn run`, `hn run-codex` | What the shell functions call. You don't type these. |

### The install scripts

| Script or option | What it does |
|---|---|
| **`scripts/install-linux.sh`** | System packages, Node.js 24, then `hypernova setup`. It refuses to run under `sudo`, and asks `sudo` for packages itself. |
| `--from <path>` | A checkout, an unpacked package, or a `.tar.gz` or `.tgz`. Without it, the repository or package the script is in. |
| `--no-claude`, `--no-integration` | As for `hypernova setup`. |
| `--yes`, `-y` | For scripts. It asks no questions of its own; `sudo` may still ask for a password. |
| `--help`, `-h` | Its help. |
| **`get.sh`** | `curl -fsSL https://ljmerza.github.io/Hypernova/get.sh \| bash` downloads the latest release's server package and runs its install script. It refuses root. Options go after `bash -s --`. |
| `HYPERNOVA_TAG=<tag>` | Another release, set for `bash`. |
| **`scripts/pack-linux.sh`** | In a clone: writes `dist/linux/hypernova-<version>.tar.gz`. A release attaches it as `hypernova-server-<tag>-linux.tar.gz`. |

## Where things live

| What | Where |
|---|---|
| State | `~/.local/share/hypernova`, mode 0700 (`$XDG_DATA_HOME/hypernova`). Called `<state>` below. |
| The daemon | `<state>/app/daemon`. Its font is in `<state>/app/Fonts`. |
| How it was installed | `<state>/app/daemon/install.json` ([Updating](#updating)) |
| Node.js, when Hypernova downloaded it | `<state>/node/<version>` |
| The Node.js the service runs | Named in `<state>/node-path`. It can be a system Node 24 or newer. |
| Logs | `~/.local/state/hypernova/logs` (`$XDG_STATE_HOME/hypernova/logs`) |
| The command | `~/.local/bin/hypernova`. It's on PATH in Ubuntu's and Debian's default `~/.profile`. |
| The service | `~/.config/systemd/user/hypernova.service` (`$XDG_CONFIG_HOME/systemd/user`) |
| Hooks and status line | `~/.claude/settings.json` (`$CLAUDE_CONFIG_DIR`) |
| The shell block | `~/.bashrc`, or `~/.zshrc` (`$ZDOTDIR`) |

Inside `<state>`:

| What | Where |
|---|---|
| Identity | `daemon.json` |
| Admin token, for the local API and the `hypernova` command only | `admin-token`, mode 0600 |
| Secret in the hooks | `hook-secret` |
| Paired phones | `devices.json` |
| Settings | `settings.json` |
| Certificates | `tls/` |
| Copies of the files the integration changes | `backups/` |
| Attachments from the phone, kept 30 days | `attachments/` |
| Shared terminals | `terms/` |
| `hn`, the status line script, a second `hypernova` for automations | `bin/` |
| The app, downloaded from the latest release for the phones | `android/` |
| The server's own update | `updates/`, `server-update.json` |

## Ports

| Port | Name | Listens on | For |
|---|---|---|---|
| 47321 | Phone API | 127.0.0.1 only | The daemon's API. Phones reach it through the local route. |
| 47322 | Local API | 127.0.0.1 only | The `hypernova` command and the hooks. |
| 47323 | Local route | All addresses, while it's on | How phones come in: HTTPS with mutual TLS. |

`hypernova doctor` reads the kernel's socket table and fails if anything listens on 47321 or 47322
beyond loopback (a port forward, another program).

**The local route** takes connections only from private, link-local, CGNAT and ULA addresses; the
rest are dropped before TLS. Without a phone's device certificate, only health, pairing, the
certificate route and the app's download page answer. Nothing is published: a phone's browser opens
the server's pages at its address, with the server's own certificate. More:
[SECURITY-MODEL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/SECURITY-MODEL.md).

It's on by default, except where no phone shares the network. `hypernova lan on|off` decides either way.

| Off by default on | Why |
|---|---|
| A machine with a public IPv4 address of its own (a VPS) | The port would face the internet. |
| A cloud VM (AWS, Google, Azure, DigitalOcean, Hetzner and others) | It sits behind a NAT. |
| A container | Its bridge network has no phones on it. |
| WSL 2 in any networking mode but mirrored or bridged | It sits behind Windows' NAT. Even when turned on, its addresses are left out of pairing links. |

Pairing links carry the server's private addresses (RFC 1918, else ULA) on real interfaces. Virtual
and tunnel interfaces are never offered: `docker*`, `br-*`, `virbr*`, `veth*`, `tun*`, `wg*` and the
like.

**Away from home**, use your own VPN into the server's network (WireGuard, a UniFi VPN). The phone then
reaches the local route as if it were on the Wi-Fi. A VPS has no home network to join, so it needs
`hypernova lan on` and a VPN onto its private network. Never open port 47323 to the internet.

## Environment variables

`hypernova setup` writes these into the service and the command when they're set. Leave them unset
on a normal install. There is one `hypernova.service`, so `setup` or `uninstall` with them set still
replaces or removes it. `HYPERNOVA_APP`, `_NODE_ROOT`, `_NODE_PATH` and `_LAUNCHER` must be absolute
paths.

| Variable | Default | What it sets |
|---|---|---|
| `HYPERNOVA_SUPPORT` | `<state>` | The state folder. A daemon with this set leaves the shell's startup file alone. |
| `HYPERNOVA_LOGS` | `~/.local/state/hypernova/logs` | The log folder. |
| `HYPERNOVA_APP` | `<state>/app` | The program, apart from the state. |
| `HYPERNOVA_NODE_ROOT` | `<state>/node` | Where Node.js is downloaded to. |
| `HYPERNOVA_NODE_PATH` | `<state>/node-path` | The file naming the Node.js to run. |
| `HYPERNOVA_LAUNCHER` | `~/.local/bin/hypernova` | The command. |
| `HYPERNOVA_API_PORT` | 47321 | The phone API. |
| `HYPERNOVA_LOCAL_PORT` | 47322 | The local API. |
| `HYPERNOVA_LAN_PORT` | 47323 | The local route. |

The rest are read where they're set. For the service, that means a systemd drop-in
(`systemctl --user edit hypernova`).

| Variable | What it does |
|---|---|
| `HYPERNOVA_TAG` | `get.sh`: the release to install. |
| `HYPERNOVA_OFF` | `1`: `claude` and `codex` skip the shared terminal for this run. |
| `HYPERNOVA_FIRE_TOKEN` | `hypernova automation fire`: the automation's own token. |
| `HYPERNOVA_CODEX` | The Codex program, instead of looking for it. |
| `HYPERNOVA_LOG_STDOUT` | Log to standard output too. `hypernova run` sets it. |
| `HYPERNOVA_APK_URL` | Where the pairing page sends a phone for the app. |
| `HYPERNOVA_CONTAINER` | `1` or `0`: this is, or isn't, a container. The image sets `1`. |
| `HYPERNOVA_CLOUD` | `1` or `0`: this is, or isn't, a cloud VM. |
| `HYPERNOVA_WSL` | `1` or `0`: this is, or isn't, WSL. |
| `HYPERNOVA_WSL_NETWORKING` | WSL's networking mode, instead of asking `wslinfo`. |
| `CLAUDE_BIN` | The Claude Code program, instead of looking for it. |

Hypernova sets `HYPERNOVA_TERM`, `HYPERNOVA_CLAUDE` and `HYPERNOVA_MANAGED` for its own sessions, and
gives an automation's shell steps `HYPERNOVA_AUTOMATION`, `_RUN`, `_PROJECT`, `_TIME`, `_BATTERY`,
`_PREV`, `_OUTPUT`, `_ANSWER`, `_EXIT`, `_PAYLOAD` and `_STEP_<id>`. These are stand-ins for tests:
`HYPERNOVA_CLAUDE_INSTALLER`, `_NODE_DIST`, `_GITHUB_API`, `_NO_DEV_APK`, `_BOOTED_AT`,
`_CODEX_SOCKET`, `_SUDO`, `_SUDOERS`, `_CAFFEINATE`, `_PMSET`, `_IOREG`, `_NOTIFYUTIL`, `_E2E_IMAGE`,
`_E2E_PREFIX`, `_SYSTEMD_IMAGE`.

## The service

| | |
|---|---|
| Unit | `hypernova.service`, a systemd **user** service: the daemon and Claude Code run as you, never as root. |
| At boot | **Lingering** starts your user's systemd at boot, so it runs with nobody logged in. Setup turns it on (`loginctl enable-linger`, else `sudo -n`), and says what to run if it can't. |
| When it fails | `Restart=on-failure`, after 5 seconds. |
| When it stops | `KillMode=process`: only the daemon ends. The shared terminals' holders, and your sessions in them, keep running. |
| Its environment | `PATH` (its Node, `~/.local/bin`, the system's folders), `LANG=C.UTF-8`, and the variables above. |
| Commands | `systemctl --user status\|start\|restart\|stop hypernova` |

**Running as root** works, but `doctor` warns: sessions would have the whole server, and Claude Code
refuses to skip permissions as root, so phone sessions run without the Bypass mode (a new session
asking for it gets `bypass_root`). `get.sh` refuses root, and `install-linux.sh` refuses to run under
`sudo`. Make a user for it: `adduser claude`, `loginctl enable-linger claude`, `su - claude`, then
install there.

## Updating

| Installed from | How it updates |
|---|---|
| A release's package, `get.sh`, or any folder with no `.git` | By itself (below). By hand: the `get.sh` line again, or the install script from a newer package. |
| A git checkout | `git pull`, then `scripts/install-linux.sh`. Never by itself. |
| A container | [Updating the container](#updating-the-container). Never by itself. |

Either way the new daemon is prepared beside the old one and swapped in only once its terminal
module works, then the service restarts. Open sessions, paired phones and settings stay.

### By itself

On unless turned off: `hypernova auto-update on|off`, or the app's Settings, the server's section,
**Update this server automatically** (`autoUpdate` in `settings.json`).

| Step | What happens |
|---|---|
| The look | The daemon reads GitHub's latest release 30 seconds after it starts, then every 6 hours. Only a `vX.Y.Z` newer than the daemon installs, never an older or equal one. |
| The checks | The server package's SHA-256 against the release's note (`serverSha256`; a release without one isn't installed), then its daemon's version against the tag. |
| The phones | They're offered the release's app first, before the restart. |
| The install | At once, whatever sessions are doing. It runs the package's `scripts/install-linux.sh --from <it> --yes --no-claude` in a transient unit of its own (`hypernova-update`), so the restart can't cut it off. |
| The restart | The daemon is down for a second or two. Sessions keep running. A hook that fires meanwhile fails without holding the session up (the terminal shows "hook error: connect ECONNREFUSED"). A waiting approval stays in Claude Code's own dialog, and the phone shows it from there. |
| Rollback | A copy of the old daemon is kept in `<state>/app/daemon.rollback`. If the new daemon doesn't answer within about a minute, the copy goes back and the service restarts on it. |
| Afterwards | The new daemon logs "this server updated itself from A to B", and the app shows it under the switch. |

**A failure** leaves the server running the version it had, and is logged. The app says so (a
notification, and the line under the switch), and so does `hypernova auto-update`. That release is
tried again at the first look after 1 hour, then 6, then 24, then not at all. A newer release starts
over. `hypernova auto-update on` forgets the failures and looks at once, even when it's already on.

With no terminal, `sudo` can't ask for a password. If the new version needs a system package, the
install fails and logs why: run it by hand.

The same look downloads the app for the phones (`<state>/android`). Turning that off (`releaseCheck`)
doesn't stop the server's own update.

### When it says "not available here"

`hypernova auto-update` gives the reason:

| It says | What to do |
|---|---|
| "This server runs in a container" | [Updating the container](#updating-the-container). |
| "This daemon doesn't run from its installed copy" | A checkout or a test copy: update it the way it was started. |
| "Installed before servers could update themselves" | Install the latest release once (`get.sh`). |
| "Installed from a checkout" | `git pull`, then `scripts/install-linux.sh`. Or install from a package, and it can from then on. |
| "This daemon isn't run by its systemd service" | `hypernova run`, or a daemon started by hand: `systemctl --user start hypernova`. |
| "The systemd service doesn't run this install" | `hypernova setup`. |
| "No systemd-run here" | Install systemd's `systemd-run`, or update by hand. |

Setup leaves the answer in `install.json`: `checkout` when the folder it installed from, or the one
above it, has a `.git`, else `package`. So a package you made yourself with `scripts/pack-linux.sh`
counts as `package`, and the next newer release replaces it.

## Logs

| File, in `~/.local/state/hypernova/logs` | What | Rolls |
|---|---|---|
| `daemon.log` | The daemon. `hypernova logs`. | At 5 MB, to `daemon.log.1` |
| `terminals.log` | The shared terminals' holders. `hypernova logs --terminals`. | At 5 MB, to `terminals.log.1` |
| `update.log` | The server's own updates. Read the file: `hypernova logs` doesn't show it. | Never |

The folder is 0700 and the logs 0600: they name your devices, so look them over before sharing them.

`journalctl --user -u hypernova` has only what escapes the daemon's own log. `hypernova run` prints
the log on the terminal too, and in a container `docker logs hypernova` has it.

## Containers and WSL

### Docker

The commands are in [INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md),
"3. In Docker". What differs from a server:

| | |
|---|---|
| The daemon | The container's command (`hypernova run`). No systemd, so no service; `--restart unless-stopped` brings it back after a crash or a reboot. |
| The program | In the image, under `/opt/hypernova`. |
| State | In the volume, at `/home/hypernova`: paired phones, settings, keys, the agents' logins. |
| Identity | Each volume makes its own computer id, admin token and hook secret at its first start, so two containers are two computers. The hooks go in at each start (`hypernova integrate`). |
| The network | Nothing is published, and the local route is off. On a Linux host, `--network host` and `hypernova lan on` let a phone in. |
| Health | The image's `HEALTHCHECK` asks the phone API, on the container's own loopback. |
| Building | `--build-arg CLAUDE=no` leaves Claude Code out. |

### Updating the container

Build the image again from a newer clone, remove the container, and run it again with the same volume.
The new image's daemon runs, with your phones and settings:

```sh
docker build -t hypernova . && docker rm -f hypernova
docker run -d --name hypernova --hostname my-hypernova --restart unless-stopped \
  --log-opt max-size=10m -v hypernova-home:/home/hypernova hypernova
```

### WSL on a Windows PC

**Experimental**: written from Microsoft's and Docker's guides, not yet tried on a real Windows PC.
The steps are in [INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md),
"Windows (experimental)". `hypernova setup`, `doctor`, `pair` and `lan` say what differs when they run there:

| | |
|---|---|
| systemd | The service needs it, in `/etc/wsl.conf`. Until then: `hypernova run`, in a terminal you keep open. |
| Staying up | WSL stops this Linux soon after its last Windows terminal closes, unless told otherwise in `.wslconfig`. |
| The network | Behind WSL's NAT no phone can reach it. Mirrored networking lets a phone on the same Wi-Fi in, with a Hyper-V firewall rule for port 47323. |
| The agents | Their Linux builds, inside WSL. A Windows install found through Windows' PATH is ignored. |

A Docker Desktop container runs on WSL's kernel, and no phone can reach it either.

## What a Linux server doesn't have

The daemon keeps a few features that are for a Mac, and on Linux they answer as unavailable. The
snapshot carries `mac.platform: "linux"` and `mac.unavailable`, each feature with its sentence, so a
client can hide it and show the reason. Asked anyway, the API answers 501 with the same sentence
([PROTOCOL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/PROTOCOL.md), "Mac-only features").

| Feature | On Linux |
|---|---|
| Battery | Not shown. |
| Stay awake | Off. A server stays on by itself; if a PC suspends, turn that off in its power settings. |
| The computer's own voice (`say`) | None. The phone reads aloud with its own voice. |
| Opening a terminal window from the phone | None: there's no screen. Use `hn attach` over SSH. |

Everything else works: sessions, approvals, usage, attachments, automations, agents, statistics,
shared terminals and Codex.

## Troubleshooting

`hypernova doctor` first: each problem comes with its fix. Then `hypernova logs`. More in
[INSTALL.md](https://github.com/ljmerza/Hypernova/blob/main/docs/INSTALL.md), "Troubleshooting".

| Symptom | What to do |
|---|---|
| The daemon isn't running | `systemctl --user start hypernova`, and `hypernova logs` says why it stopped. |
| `hypernova: command not found` right after the install | Log in again (Ubuntu adds `~/.local/bin` to PATH at login), or run `~/.local/bin/hypernova`. |
| "hypernova: its files are gone" | Install it again with `scripts/install-linux.sh`. |
| "Building the terminal module needs make, g++, python3" | `sudo apt-get install -y build-essential python3`, then `hypernova setup`. |
| "systemd's user manager isn't running" | `sudo loginctl enable-linger $USER`, or log in over SSH, then `hypernova setup`. |
| The service stops when you log out | Lingering is off: `sudo loginctl enable-linger $USER`. |
| The phone can't reach the server | `hypernova status` shows the local route. `hypernova lan on` if it's off. The phone must be on the server's network, or your VPN into it. See [Ports](#ports). |
| An update failed | `hypernova auto-update` says why; `update.log` has the rest. See [Updating](#updating). |
