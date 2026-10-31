# Ideas: ways to improve or extend Hypernova

A long list, gathered 2026-10-30 from the code at `96098c4`, `docs/DECISIONS.md`, `docs/LATER.md`,
`docs/PROTOCOL.md` and what Claude Code 2.1.314 itself offers (`claude --help`, `claude agents --help`, and the
names and descriptions in its command table). Nothing here is agreed: an idea becomes work only when the owner picks
it, and then it goes into DECISIONS. Ideas already parked in LATER are named by their row (L18, L34…), not repeated.

The owner's constraints hold throughout: local only (no public services, no Tailscale, no hypernova.observer, no
cloud voice), never auto-deny, no Bypass for agents, Codex stays but Claude Code comes first, and a simple,
uncluttered UI. An idea that bends one says so. The last section lists the ones that break them.

**Size:** S (a day or less), M (a few days), L (a week or more). **Where:** daemon, app, or both.

## Top 10 picks

The best value for the effort, in order. ✓ marks the ones built since (2026-10-30/31; the rows below say which day).

1. ✓ **SS1 + SS2, a summary line and folding parts in the session sheet.** The sheet now scrolls through six parts;
   one line (mode, model, context, branch, time) answers most looks, and folded parts keep it uncluttered.
2. ✓ **SC1, `/btw` from the phone.** Ask a side question while an agent works, without derailing it: the thing a
   phone is best placed to do, and Claude Code already has the command.
3. ✓ **NS1 + SS3, effort for Claude Code sessions.** Claude Code takes `--effort low…max` and `/effort`; the phone
   offers effort only for Codex today. Small, and used every day.
4. ✓ **SS5, the rules in effect, read-only.** The sheet says "the phone knows no allow rules"; reading the allow,
   ask and deny lists from the settings files explains why something asked or didn't.
5. ✓ **PA2 + PA3, choose what "Always" covers, and edit the rule first.** Session, this project or everywhere,
   with the rule shown and widened before saving (`Bash(npm test)` to `Bash(npm test:*)`). Fewer asks, on purpose.
6. ✓ **SC2 + SC5, a command palette with the skills.** The "/" list only shows while typing; a palette with
   recent, pinned and skills makes his ~20 skills and Claude Code's commands reachable in two taps.
7. ✓ **HM6, unread and "finished 5 min ago" on Completed cards.** With many `claude agents` jobs, knowing which
   finished replies he hasn't read is the main thing Home is for.
8. ✓ **HI1, full-text history search.** History searches title, project and last line; finding "the session
   where we fixed the PIA port" needs the words inside.
9. **NS2, start in a worktree.** `claude --worktree` keeps parallel agents off each other's files, and
   `claude rm` already cleans worktrees up safely. One switch in the New session sheet.
10. ✓ **AG4, respawn jobs running an old Claude Code.** Claude Code updates itself, but background jobs keep the old
    binary until `claude respawn`; a notice and one button keeps every job current.

## Session sheet and usage view

Today: name and project, Model (chip, picker, switch agent), Permissions (mode picker, blocked calls), the plan's
windows, Agents (workflows and agents at work), Where (folder, files, runs in, terminal, agent, provider, job, id)
and the card's actions (Rename, Stop, Delete). Cost, the context ring, Compact and the tokens table were taken out
on 2026-10-30 (DECISIONS, "The session sheet", 4); ideas that bring any back say so.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| SS1 | Summary line at the top ✓ built 2026-10-30 | One quiet mono line under the name: mode, model, context %, branch, how long it has run. Most opens of the sheet are "what is it doing and in what mode", answered without scrolling. | S | app |
| SS2 | Folding parts ✓ built 2026-10-30, as tabs | Each seam folds; the sheet opens with only live parts open (blocked calls, agents at work) and the rest folded, remembered per part. Keeps the sheet short as more goes in. | S | app |
| SS3 | Effort in the Model part ✓ built 2026-10-30 | Show and change the session's effort (`/effort`, low to max) next to the model, typed into the terminal as Rename types `/rename`. Unsure whether the status line reports the current effort; it may have to be read from the transcript or remembered. | M | both |
| SS4 | Context back, folded | The context % with Compact and a `/context` breakdown, folded by default. **Reverses** the 2026-10-30 removal (the strip keeps context and Compact from 85%), so only if the owner wants it. | S | app |
| SS5 | Rules in effect (read-only) ✓ built 2026-10-30 | The allow, ask and deny rules from `~/.claude/settings.json`, the project's `.claude/settings.json` and `settings.local.json`, plus the session's own (from "Always" and "session" answers), grouped by source. Explains every ask and every non-ask. | M | both |
| SS6 | Approvals in this session | A short log: each ask, the answer, who answered (phone, terminal, Allow & retry) and when. The daemon already sees `approval` and `approval.resolved`; it only needs keeping. | M | both |
| SS7 | Files touched ✓ built 2026-10-31 | "7 files, +120 −30" with a link to What changed, scoped to the session (the changes route already has `scope: "session"`). | S | app |
| SS8 | Git line ✓ built 2026-10-31 | Branch, ahead/behind, dirty count, and the worktree's name for a worktree session. `changes` already returns the branch. | S | both |
| SS9 | Time ✓ built 2026-10-31 | Started at, running for, this turn's time, last activity. Useful for "is it stuck or slow". | S | app |
| SS10 | Tokens and cost, folded | The tokens table and the estimate under a "Details" fold. **Reverses** "Remove the API cost amount"; listed for completeness. | S | app |
| SS11 | Quick actions row | Compact, Stop turn (Esc), Open terminal, Branch, Export as small pills under the summary. Today the actions are only Rename, Stop and Delete for jobs. | S | app |
| SS12 | Background tasks | Background shells and monitors the session started (Claude Code's `/tasks`, "everything running in the background") listed in the Agents part. Unsure how to read them from outside; `run_in_background` Bash calls in the transcript are a start. | M | both |
| SS13 | Goal | Show and set the session's goal (`/goal`, "a goal Claude checks before stopping"). Unsure where Claude Code keeps it. | M | both |
| SS14 | Plan | The current plan (`/plan` "view the current session plan") readable from the sheet, not only as the approval card. | M | both |
| SS15 | Claude Code version | The version the session runs, amber when older than the installed one, with Respawn (AG4). | S | both |
| SS16 | Extra folders | Folders added with `/add-dir`, and Add from the folder browser. | M | both |
| SS17 | Memory in effect | The CLAUDE.md files and memory the session loaded, each opening in the file viewer (read-only). Answers "why does it think that". | M | both |
| SS18 | MCP servers | Which MCP servers the session has and whether each connected. Unsure where to read this without running `claude mcp list`, which starts servers. | M | both |
| SS19 | Local pin and tag | Pin a session to the top of its group, or give it a colour, kept on the phone only. | S | app |
| SS20 | Workflow phases and tapping agents in the bar | Already parked: L95, L96, L98. | | |

## Slash commands and `/btw`

Today: typing "/" in the composer lists Claude Code's own commands, skills, plugins' and MCP prompts for the
project, with descriptions and argument hints (`daemon/src/commands.ts`, `ui/session/Commands.kt`), cached five
minutes per folder.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| SC1 | `/btw` side questions ✓ built 2026-10-30 | An "Ask aside" action (long-press Send, or a palette entry) that sends `/btw <question>`, and the answer shown as a card that goes away, not in the chat. Claude Code's own description: "Ask a quick side question without interrupting the main conversation". Unsure whether the answer reaches the transcript; it may only show in the terminal, in which case the daemon reads it off the screen as it reads menus. | M | both |
| SC2 | Command palette ✓ built 2026-10-30, the Commands tab | A "/" button opens a sheet: recent and pinned first, then Built-in, Skills, Yours, Plugins, MCP, with search. The list today shows only while the box starts with "/". | M | app |
| SC3 | Argument helpers ✓ built 2026-10-31 | Use the argument hint to offer a field or chips: `/effort` as five chips, `/model` as the model picker, `/rename` with the name filled. | S | app |
| SC4 | Panels as cards | Commands that draw a panel in the TUI (`/context`, `/usage`, `/status`, `/permissions`, `/hooks`, `/mcp`, `/tasks`, `/workflows`, `/skills`) come back as a card with the screen's text, or open the terminal by themselves. Check first how each shows on the phone today. | M | both |
| SC5 | Skills list ✓ built 2026-10-30, the Commands tab | The skills Claude Code reports for the project (his ~20 personal ones and the project's), with descriptions, tap to insert. Not a market (that is L21). | S | app |
| SC6 | Commands in quick prompts | Let a quick prompt chip hold a command (`/compact`, `/code-review`) and draw it as a command. | S | app |
| SC7 | "Opens in the terminal" mark | Commands that need the TUI's own picker (`/config`, `/theme`, `/resume`, `/login`, `/keybindings`) say so in the list, or are hidden. | S | both |
| SC8 | `/compact` with a focus ✓ built 2026-10-31 | A small sheet for Compact with an optional "keep…" line, sent as `/compact <focus>`. | S | app |
| SC9 | Branch and fork from a message | "Branch here" (`/branch`, "a branch of the current conversation at this point") and "Fork to background" (`/fork`, "copy this conversation into a new background session") next to Rewind in the message menu. Unsure how `/branch` differs from Hypernova's Rewind fork in practice. | M | both |
| SC10 | Side task | `/subtask` ("send a subagent off with your full context; its result comes back here") as an action, for "also check X" without a new session. | S | app |
| SC11 | Recap | `/recap` ("a one-line session recap now") on demand, shown on the Home card. It costs a model call, so on demand only. | S | both |
| SC12 | Export | Export the conversation as Markdown and share it through Android's share sheet. The daemon can render the transcript itself rather than typing `/export`. | M | both |
| SC13 | Command history | The commands used in this project, most recent first, at the top of the list. | S | app |
| SC14 | Loops | List and remove the session's loops (`/loops`). Unsure how loops are stored. | M | both |
| SC15 | Reports | `/insights` and `/skill-doctor` reports opened as a document on the phone. | S | both |
| SC16 | Advisor and autocompact | Toggles for `/advisor` ("consult a stronger model at key moments") and `/autocompact` thresholds in the sheet. | S | both |
| SC17 | Refresh the list | A refresh in the list (the cache is five minutes) for a skill just added (`/reload-skills`). | S | app |
| SC18 | `/` commands for Codex | Already parked: L82. | | |

## Permissions and approvals

Today: Allow, Deny, Always (fingerprint; the rule shown, L72), "session" for a tool, Allow & retry on blocked calls,
Claude Code's own terminal dialog answered from the phone, held prompts, the mode picker, never auto-deny.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| PA1 | Rules manager | After SS5's read-only list: add and remove rules from the phone, per file, behind the fingerprint. The daemon already edits `~/.claude/settings.json` safely for hooks (backup, symlinks). Writing settings is a step up; the owner decides. | L | both |
| PA2 | Scope for "Always" ✓ built 2026-10-31 | Choose session, this project (`projectSettings` or `localSettings`) or everywhere (`userSettings`) on the Always sheet; Claude Code's `addRules` takes a destination. | M | both |
| PA3 | Edit the rule ✓ built 2026-10-31 | Change the suggested rule before saving (a prefix, a wildcard). | S | app |
| PA4 | Approvals log | Across sessions and servers: what was asked, answered how, by which device, any rule added. Also an audit trail (SEC2). | M | both |
| PA5 | Suggest a rule | From the log: "You allowed `npm test` 14 times this week. Always allow it in this project?" Never adds one on its own. | M | both |
| PA6 | Allow several ✓ built 2026-10-31 | When one session has several asks waiting (parallel agents), "Allow these 3", each command listed. Only ever Allow; Deny stays one at a time. | S | app |
| PA7 | Why it asked | Show what caused the ask: an ask rule ("Ask rule Bash(python:*) overrides auto mode"), no matching rule, or a file outside the folder. | S | both |
| PA8 | Risk on the card | Already parked: L34 (risk in the daemon). A colour and a long-press for high-risk commands; never a deny. | | |
| PA9 | Auto mode rules | Show `claude auto-mode config` (the effective allow, soft_deny and hard_deny) read-only, so blocked calls make sense, and link to `/auto-mode-setup`. `claude auto-mode critique` costs a model call. | M | both |
| PA10 | Allow & add rule | On a blocked call, beside Allow & retry: add an auto mode allow rule so the same call isn't blocked again. Writes settings; behind the fingerprint. | M | both |
| PA11 | Default mode per project | Settings' "New sessions start in" per project (Plan for one repo, Auto elsewhere). Extends DECISIONS "Default mode for new sessions". | S | app |
| PA12 | Approvals inbox | One screen with every waiting ask across sessions and servers, oldest first. Home's cards hold them now, mixed with everything else. | M | app |
| PA13 | Reminder, never a no | An ask left 5 minutes buzzes again, quietly; at 10 it goes back to the terminal as today. | S | app |
| PA14 | Mode changes in the log | Each mode change (who, from what, to what) in the session's log, so a session that went to Auto is never a surprise. | S | both |
| PA15 | Masked commands in notifications | Already parked: L41. | | |

## Home and the session list

Today: groups (Needs input, Working, Completed), A to Z within a group, Allow and Deny on cards, the server chip and
switcher, usage cards, account and offline cards, the long-press menu.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| HM1 | What it's doing now ✓ built 2026-10-31 | A Working card's second line: the active to-do ("Running the tests 3/7") or the current tool. | S | app |
| HM2 | Swipe actions ✓ built 2026-10-31 | Swipe a Completed card to Stop or Remove (`claude rm`, asking first), a Needs input one to open it. | S | app |
| HM3 | Group by project | An option to group by project inside each status. | S | app |
| HM4 | Clear completed | Remove finished `claude agents` jobs older than a day, listed first; `rm` already refuses a worktree with unpushed work. | M | both |
| HM5 | Order by finish time ✓ built 2026-10-30 | Completed sorted newest first, with "finished 5 min ago". The A to Z rule was for cards that kept swapping; finished ones don't move. | S | app |
| HM6 | Unread replies ✓ built 2026-10-30, per phone | A dot on a card whose last reply hasn't been opened on any of his phones. | S | both |
| HM7 | Elapsed time ✓ built 2026-10-31 | "for 12 min" on Working cards. | S | app |
| HM8 | Search live sessions | The History search over Home's sessions too. | S | app |
| HM9 | Reply from the card | An inline reply field on Needs input and Completed cards, without opening the chat. | M | app |
| HM10 | All servers on one Home | Every paired server's sessions in one list, each tagged with its server (see MS1). | M | app |
| HM11 | Compact density | A denser card for many sessions. | S | app |
| HM12 | Restarted cards, odds and ends | Already parked: L50. | | |

## Chat

Today: the chat from the transcript, folded tool runs, diffs, mermaid, the message menu (copy, quote, read aloud,
select, save as quick prompt, Rewind), held and queued prompts, quick prompts, attachments, menu cards, the to-do
panel, the status bar with agents.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| CH1 | Stop and send | One action: Esc, then the new prompt. Steering is the common case. | S | app |
| CH2 | Jump list | Jump to the open ask, to my last prompt, to the top. | S | app |
| CH3 | Find in chat | Search inside a long conversation. | M | app |
| CH4 | Paths are links | A file path in a reply opens the file or its diff. | M | app |
| CH5 | Comment on a line | From a diff or a file, long-press a line to send "In `path:42`: …" as a prompt. Reviewing from the phone. | M | app |
| CH6 | Suggested next prompt | Claude Code can predict the next prompt (`--prompt-suggestions`, in print/SDK mode); a chip with it after a turn. Unsure it works for terminal sessions. | M | both |
| CH7 | Brief messages | Claude Code's `--brief` (a SendUserMessage tool for agent-to-user messages) could give short phone-friendly updates. Unsure how it behaves; worth a test. | M | both |
| CH8 | Code blocks | Wrap toggle and syntax colours (Highlight.kt has them for diffs). | S | app |
| CH9 | Drafts kept | The unsent text per session kept across an app restart, if it isn't already. | S | app |
| CH10 | Streaming text | Already parked: L18. | | |
| CH11 | Rewind files too | Already parked: L46. `/rewind` now says "restore the code and/or conversation". | | |

## Terminal

Today: xterm.js over the daemon's socket, a key bar (Esc, Tab, Shift+Tab, Ctrl, arrows, ^C, Paste, Enter), jump to
latest.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| TM1 | Custom keys | Pick the key bar's keys (`/`, `~`, PgUp, Ctrl+R and others). | S | app |
| TM2 | Pinch to zoom | Text size by pinch. | S | app |
| TM3 | Copy from scrollback | Select and copy terminal text. | M | app |
| TM4 | Logs without attaching | `claude logs <id>` for a job: its recent output, read-only, never pressing a key in it. | S | both |
| TM5 | A plain shell | A shell in a project folder for `git` and friends. **Bends** "simple": it turns the phone into a remote shell, so behind the fingerprint and off by default. | M | both |
| TM6 | Landscape terminal | Already parked: L9. | | |

## Notifications and widgets

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| NT1 | Mute a session or project | Silence a noisy one. | S | app |
| NT2 | Quiet hours | Silent notifications at night; asks still wait, never denied. | S | app |
| NT3 | Context alert | A notice when a session passes 85% context or auto-compacts. | S | both |
| NT4 | Job died | A notice when a `claude agents` job ends with an error. | S | daemon |
| NT5 | Long turn | Optional "working for 30 min" notice. | S | app |
| NT6 | Quick Settings tile | In the 1.0 scope (DECISIONS, Sessions) but not built: a tile with the needs-you count, opening Home. | S | app |
| NT7 | What changed from the notification | A "What changed" action on a finished notice. | S | app |
| NT8 | Agents in widgets | Already parked: L28. | | |
| NT9 | Push without the app open | Already parked: L1, L12, L70. Needs a relay, which breaks local-only. | | |

## New session

Today: folder browser, model, mode (default in Settings), Codex's model and effort, attachments, voice.

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| NS1 | Effort ✓ built 2026-10-30 | Low, medium, high, xhigh, max (`--effort`), remembered like the mode. | S | both |
| NS2 | Worktree | A switch for `--worktree [name]`, so parallel jobs don't share files. | M | both |
| NS3 | Name | `--name` at start, so `claude agents` and Home show it from the first second. | S | both |
| NS4 | Agent | `--agent` from the user's and project's agents (none in `~/.claude/agents` today). | M | both |
| NS5 | Presets | Save folder, model, mode, effort and a first prompt as a preset. Quick prompts in this sheet are L47. | M | app |
| NS6 | Extra folders | `--add-dir` from the folder browser. | S | both |
| NS7 | From a PR | `--from-pr <number>` for a repo with `gh` signed in on the server. | M | both |
| NS8 | Fallback model | `--fallback-model` for an overloaded model. | S | both |
| NS9 | Same prompt, two ways | Start one prompt in two worktrees with different models, and compare. | L | both |

## Several servers

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| MS1 | One Home for all | See HM10. | M | app |
| MS2 | A colour per server | On the chip and on cards, so the server is clear at a glance. | S | app |
| MS3 | Server health | In the switcher: Hypernova and Claude Code versions, uptime, disk. | M | both |
| MS4 | Rename a server | His own name for each. | S | app |
| MS5 | Per-server notifications | Different sounds or mute per server. | S | app |
| MS6 | Continue on another server | Move a conversation to another computer. Transcripts move; files don't, so it is only for some work. | L | both |

## Automations

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| AU1 | Budget guard | Skip a run when the 5-hour window is over X% or the week over Y%. | M | daemon |
| AU2 | Morning digest | One notice: "3 runs overnight: 2 done, 1 needs you". | S | both |
| AU3 | Run once with changes | Run now with another prompt or model, without editing the automation. | S | app |
| AU4 | Commands and skills as actions | Pick `/code-review` or a skill as a run's prompt. | S | app |
| AU5 | Git trigger | When a branch gets new commits (local polling, no webhooks). | M | daemon |
| AU6 | Runs as `claude agents` jobs | So they show in `claude agents` on the server too. Unsure whether they already do with `claudeAgents` on. | M | daemon |
| AU7 | Next fire times | The next five times an automation fires, in the editor. | S | app |
| AU8 | Round two, odds and ends | Already parked: L54, L73, L74, L62 (the server's time zone). | | |

## Voice (on the phone only)

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| VO1 | Spoken commands | "slash compact" dictated becomes `/compact`. | S | app |
| VO2 | Recognizer check | Say when the on-device recognizer isn't installed, and how to get it. | S | app |
| VO3 | Voice and speed | Choose the phone's text-to-speech voice and speed for Read aloud. | S | app |
| VO4 | Hands-free | Read new replies aloud while a headset is connected. | M | app |

## Files, diffs and previews

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| FL1 | Per-turn changes | What one turn changed, not only since the last commit, from Claude Code's file history. Unsure the file history's format is readable from outside. | M | both |
| FL2 | Comment on a diff line | See CH5. | M | app |
| FL3 | Git history | Log, branches and a commit's diff (the git panel planned for 1.1). | M | both |
| FL4 | Search in files | Read-only search in a project from the phone. | M | both |
| FL5 | Markdown rendered | READMEs and plans drawn as Markdown in the viewer, if not already. | S | app |
| FL6 | Upload into the project | Put a file into the folder, not only as an attachment. A write from the phone, behind a confirm. | M | both |
| FL7 | PDF viewer and share-in | Already parked: L20. | | |

## History and search

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| HI1 | Full-text search ✓ built 2026-10-30 | Search inside every conversation, with the matching line shown. | M | daemon |
| HI2 | Further back | More than 30 days and 300, a page at a time. | S | both |
| HI3 | Resume differently | Resume an ended session with another model, mode or effort. | S | both |
| HI4 | Starred messages | Star a reply to find it later. | M | both |
| HI5 | Export | See SC12. | M | both |

## Statistics

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| ST1 | Approvals stats | Asks per day, time to answer, most-asked commands; feeds PA5. | M | both |
| ST2 | Model mix | Tokens by model over time. | S | app |
| ST3 | Hours heatmap | When Claude works, by hour and weekday. | S | app |
| ST4 | Per-project weeks | Tokens per project per week. | S | app |
| ST5 | Codex in Statistics | Already parked: L84, L85. | | |

## Settings

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| SE1 | Claude Code's settings, read-only | Default model, autocompact, output style, hooks, plugins, MCP servers. | M | both |
| SE2 | Search in Settings | Settings is long. | S | app |
| SE3 | Phone settings kept on the server | Quick prompts, defaults and servers, so a new phone starts as the old one. | M | both |
| SE4 | Testing checklist row | Already parked: L75. | | |

## Agents, workflows and background jobs

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| AG1 | Stop one agent | Stop a single sub-agent or background task. Unsure Claude Code allows this from outside; `/tasks` manages background work in the TUI. | M | both |
| AG2 | Workflows list | Running and finished workflows (`/workflows`) with their result. | M | both |
| AG3 | Jobs started elsewhere | Make sure jobs dispatched from the TUI's agent view look like the phone's own on Home. | S | daemon |
| AG4 | Respawn old jobs ✓ built 2026-10-30 | "3 jobs run 2.1.310, Claude Code is 2.1.314: Respawn". `claude respawn <id>` or `--all`. | S | both |

## Reliability and diagnostics

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| RD1 | Diagnostics screen | `hypernova doctor` on the phone: versions, uptime, hooks and status line in place, the last errors from the log (masked). | M | both |
| RD2 | Hooks gone | Warn when Hypernova's hooks are missing from `~/.claude/settings.json` (another tool rewrote it). | S | daemon |
| RD3 | Looks stuck | A session Working with no transcript change for 20 minutes: "Looks stuck", with Open terminal and Esc. | M | both |
| RD4 | Daemon restarted | A notice saying the daemon restarted and why. | S | both |
| RD5 | Disk space | A warning when the server's disk is low (transcripts, attachments). | S | daemon |
| RD6 | "Mac" wording on Linux | Check every string that says "Mac" goes through ComputerWords on a server ("Couldn't reach your Mac", "Your Mac is away"). | S | app |
| RD7 | Claude Code minimum version | Already parked: L88. | | |

## Security

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| SEC1 | Paired devices on the phone | List the paired devices with last seen, and forget another one (today only `DELETE /v1/devices/me`), behind the fingerprint. | M | both |
| SEC2 | Audit log | Approvals, mode changes, rule and setting changes, by device (see PA4). | M | both |
| SEC3 | Allowed networks | A setting to narrow the listener to chosen subnets (the home LAN and the VPN's), not any private address. | S | daemon |
| SEC4 | App lock | Fingerprint on opening the app, optional. | S | app |
| SEC5 | No screenshots | FLAG_SECURE on approvals and file views, optional. | S | app |
| SEC6 | Certificate expiry | Warn before the listener's certificate expires, with the steps. | S | both |
| SEC7 | Secrets in notifications | Already parked: L41. | | |

## Developer experience and testing

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| DX1 | Record and replay | Record a live socket session to replay in tests and the demo. | M | both |
| DX2 | Screenshot tests | Roborazzi or Paparazzi for the sheet, cards and approval cards. | M | app |
| DX3 | One schema | Generate the Kotlin and TypeScript types from one schema, so PROTOCOL.md and the code can't drift. | L | both |
| DX4 | A fake `claude` | A test double for `claude agents --json`, `attach`, `stop` and `rm`. | M | daemon |
| DX5 | Emulator smoke test in CI | Pair with a test daemon, open a session, answer an ask. | M | both |
| DX6 | Smaller PROTOCOL.md | 3,365 lines; one file per area would be easier to keep true. | S | docs |

## Release and install

| ID | Idea | What and why | Size | Where |
|----|------|--------------|------|-------|
| RL1 | Server update from the phone | "Hypernova 0.2.4 is out on the server": Update, with the release notes. | M | both |
| RL2 | What's new | The release notes after an app update. | S | app |
| RL3 | Debug and release side by side | A separate application id for debug builds. | S | app |
| RL4 | Reproducible APK, image on a registry | Already parked: L86, L87. | | |

## Claude Code features checked

Read from `claude --help`, `claude agents --help` and the command table in the installed 2.1.314 binary (names
and descriptions only). Nothing was run.

- **Confirmed:** `/btw`, `/effort`, `/compact`, `/clear`, `/context`, `/usage` (aliases `/cost`, `/stats`),
  `/rewind` ("restore the code and/or conversation"), `/permissions` (alias `/allowed-tools`), `/branch`,
  `/fork`, `/subtask`, `/goal`, `/recap`, `/plan`, `/tasks`, `/workflows`, `/loops`, `/add-dir`, `/cd`,
  `/export`, `/advisor`, `/autocompact`, `/skills`, `/reload-skills`, `/skill-doctor`, `/insights`, `/rename`.
  Flags: `--effort`, `--worktree`, `--name`, `--agent`, `--add-dir`, `--fallback-model`, `--from-pr`,
  `--prompt-suggestions`, `--brief`. Commands: `claude logs`, `respawn`, `stop`, `rm`, `auto-mode`, `purge`.
- **Not found:** `/review` as a built-in in 2.1.314 (the owner's `code-review` skill covers it). `/agents` is
  marked "(removed)". No `~/.claude/commands` folder exists; his custom commands are skills.
- **Unsure:** how `/btw`'s answer shows (likely in the terminal only), where `/goal`, loops and effort are stored,
  and whether `--prompt-suggestions` and `--brief` work outside print mode.

## Ideas that break the constraints (not recommended)

Listed so they're not suggested again without the owner's say.

- **Cloud sessions:** `--remote-control`, `--cloud`, `/teleport`, `/ultraplan`, `/ultrareview`, `/schedule`
  (routines run remotely). They send the work to Anthropic's cloud, outside the local-only setup.
- **Push through a relay** (L1, L12, L70) and a **public name or web client** (L59, L69).
- **Bypass:** a live Bypass switch (L35) and Bypass in other apps (L36) go against "no Bypass for agents".
- **Any automatic "no"**: no timeouts, batch denies or risk rules that deny for him (PA6, PA8 and PA13 stay
  Allow-only or reminders).
- **Cloud voice** (Soniox, removed 2026-10-30): voice stays on the phone.
