# Spec: Codex as a second agent, the app-server facts it rests on

Owner's mandate (2026-10-21): Claude Code and OpenAI Codex in one app. Start a Codex session from
the phone or a Mac terminal, see it next to the Claude ones, chat, stop, approve, pick the model,
open it in a shared terminal, see Codex's usage, switch a conversation from one agent to the other
with its context, and run automations on Codex. A first attempt (by another assistant, 2026-10-19)
never worked and is archived on the branch `archive/chatgpt-codex-2026-10-19`; this spec starts
again from what the installed Codex actually does.

---

## 1. Research findings (verified 2026-10-21, codex-cli 0.163.0)

Every finding comes from a probe script against the owner's running Codex daemon (prompts titled
"[Hypernova test]", threads archived afterwards), plus `codex app-server generate-ts`, which writes
the exact protocol types for the installed version.

### 1.1 Codex runs its own shared server
- `codex app-server daemon version` prints JSON: `{"status":"running", "socketPath":
  "~/.codex/app-server-control/app-server-control.sock", "cliVersion":"0.163.0",
  "appServerVersion":"0.163.0", …}`. `codex app-server daemon start` starts it if it's not running.
  It outlives any client, so Hypernova never runs a server of its own.
- The socket speaks WebSocket (path `/rpc`) carrying JSON-RPC 2.0 without the `jsonrpc` field.
  Node's `ws` connects over it with `createConnection: () => net.connect(socket)` (a `ws+unix:`
  URL would percent-encode the space in "Application Support").
- Handshake: `initialize {clientInfo:{name, title, version}, capabilities:{experimentalApi:true}}`,
  then the notification `initialized`.

### 1.2 Threads and turns
- `thread/start {cwd, sandbox, approvalPolicy:'on-request', approvalsReviewer:'user', model?}`
  returns `{thread:{id, path, …}, model, reasoningEffort, …}`. The thread id is a UUIDv7.
- **A thread has no rollout until its first turn or injected item.** `thread/resume` on such a
  thread fails with `-32600 no rollout found for thread id …`, even while it's loaded and running.
  This is what broke the first attempt: it resumed fresh threads before every prompt.
- `turn/start {threadId, input:[{type:'text', text, text_elements:[]}], clientUserMessageId?,
  model?, effort?, sandboxPolicy?, approvalPolicy?}` works straight after `thread/start` on the same
  connection. The overrides apply to this turn and the ones after it. The user item echoes
  `clientUserMessageId` back as `clientId`.
- Notification order for a plain turn: `thread/status/changed active`, `turn/started`,
  `item/started` and `item/completed` (`userMessage`), `item/started` (`agentMessage`),
  `item/agentMessage/delta`…, `item/completed` (`agentMessage`, with full `text`),
  `thread/status/changed idle`, `turn/completed {turn:{id, status, error, items}}`.
- Other input types: `localImage {path}`, `mention {name, path}` (a file), `image {url}`.
- `turn/interrupt {threadId, turnId}`.
- `thread/read {threadId, includeTurns:true}` returns the turns and their items (it's deprecated
  in favour of `thread/turns/list` plus `thread/items/list`, but still answers in 0.163.0).
- `thread/archive {threadId}` hides a thread from `codex resume`. Hypernova never archives the
  owner's threads; only its tests do.

### 1.3 More than one client on a thread
- A second connection joins a thread with `thread/resume {threadId, excludeTurns:true}`, but only
  once the thread has a rollout (after its first turn or an injected item). From then on both
  connections get every notification for that thread, whichever of them started the turn.
- `thread/status/changed` reaches **every** connection, joined or not. `thread/started` reaches
  only the connection that started the thread. Hypernova uses the first to notice that a thread it
  knows (from a holder) has become joinable.
- `thread/unsubscribe {threadId}` leaves a thread.

### 1.4 Approvals
- Server requests (they carry an `id` and a `method`): `item/commandExecution/requestApproval
  {threadId, turnId, itemId, command, cwd, reason}`, `item/fileChange/requestApproval {threadId,
  turnId, itemId, reason, grantRoot}`, `item/permissions/requestApproval {…, permissions}` and
  `item/tool/requestUserInput {questions:[{id, header, question, options, isSecret}]}`.
- Answers: `{decision:'accept'|'acceptForSession'|'decline'|'cancel'}`, or for permissions
  `{permissions, scope:'turn'|'session'}`, or for questions `{answers:{[id]:{answers:[…]}}}`.
- **Every joined connection receives the same request.** The first answer wins, and every
  connection then gets `serverRequest/resolved {threadId, requestId}`. A phone and a terminal can
  therefore race on one approval just as they do for Claude.
- While a request waits, `thread/status/changed` carries `activeFlags:['waitingOnApproval']`.

### 1.5 Seeding context without running the model
- `thread/inject_items {threadId, items:[{type:'message', role:'user', content:[{type:'input_text',
  text}]}]}` works on a fresh thread before any turn. It gives the thread a rollout (another client
  can then join it, and a terminal can resume it), and the model sees the text on the next turn: a
  probe injected "the secret word is PLUM", asked for the secret word, and got PLUM.
- Injected items don't appear in `thread/read` turns, so the history Hypernova shows comes from the
  linked earlier session, not from the seed.

### 1.6 The Codex terminal through Hypernova's holder
- `codex --remote unix://<socket> --cd <folder>` runs the normal TUI against a server. With
  `--remote`, the TUI doesn't send its own folder: without `--cd` it ran in the server's folder
  (`~/Programming`), not the shell's.
- A plain WebSocket pass-through (the holder's own socket in front of Codex's) works. The TUI
  connects to `/rpc` and, at launch, sends `thread/start` with a request id starting
  `startup-thread-start-`. The response's `thread.id` is the session. It also starts **temporary
  threads** (request ids starting `temporary-`) to write titles, and unsubscribes them; the holder
  ignores those. `codex resume <id>` goes through `thread/resume`.
- A turn started by another client on the TUI's thread shows up live in the TUI: the probe's
  "From the phone: reply with just the word PHONE" appeared in the terminal, followed by the answer.
- A folder Codex hasn't trusted yet makes the TUI ask "Trust this folder?" first ("1. Trust and
  continue"). Threads started over the API don't ask.
- The TUI follows the owner's own `~/.codex/config.toml` (here "permissions: YOLO mode", that is
  `approvalPolicy: never`). Hypernova doesn't override a terminal's own settings.

### 1.7 Account, usage and models
- `account/read` returns `{account:{type:'chatgpt', email, planType:'prolite'} | {type:'apiKey'} |
  null, requiresOpenaiAuth}`. Hypernova never sends the email to the phone.
- `account/rateLimits/read` returns `{rateLimits:{primary:{usedPercent, windowDurationMins,
  resetsAt (seconds)}, secondary, credits, planType, rateLimitReachedType}, …}`. On this account
  there is a single weekly window (`windowDurationMins: 10080`); a 5-hour window (300) appears as
  `primary` or `secondary` on plans that have one. `account/rateLimits/updated` pushes the same
  snapshot.
- `model/list` returns `{data:[{model, displayName, description, hidden, isDefault,
  supportedReasoningEfforts:[{reasoningEffort, description}], defaultReasoningEffort}]}`. Efforts
  seen: low, medium, high, xhigh, max, ultra (not every model has every one).
- Sandbox modes: `read-only`, `workspace-write`, `danger-full-access`. Approval policies:
  `untrusted`, `on-request`, `never` (plus a granular form).

## 2. What Hypernova does with it

- **Minimum version, not a pin.** 0.163.0 is the floor (the first version these facts were
  verified on); later versions are accepted and checked by the handshake.
- **One connection,** made only when the owner has turned Codex on, with backoff from 1 s to 60 s
  on failure.
- **Fresh threads are never resumed:** `thread/start`, then `turn/start`. A thread a terminal
  started is joined when it becomes joinable.
- **Switching to Codex** starts a thread and injects the approved context. **Switching to Claude**
  starts Claude Code with the context in `--append-system-prompt`. Neither runs the model until the
  owner sends the next message.
- **Shared terminals:** the holder puts a pass-through socket in front of Codex's, launches the TUI
  with `--remote` and `--cd`, and reads the thread id from the startup `thread/start` (or
  `thread/resume`) response.
