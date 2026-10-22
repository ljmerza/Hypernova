# Spec: other providers' limits, the fake provider, and auto-continue

Owner's mandate (DECISIONS, "second round of notes"): each session shows its provider's own state
("OpenRouter · out of credits"), with OpenRouter's balance where its API offers it. Sessions on other
providers keep moving in the sky when the subscription's star is dead, with a subtle ring. When a
limit hits, Claude Code's auto-continue is a button: "Continues by itself at 2:00" with Don't, and
"Continue automatically at 2:00" to turn it back on. All of it is tested end to end against a fake
provider on the Mac that passes requests through to Claude and plays back other providers' errors.

The bar: you hit the limit at 23:40, glance at the phone, see "Continues by itself at 2:00", and go
to bed. In the morning the work is done. OpenRouter runs out of credit, and the phone says so in
those words, with the balance, an "Add credits" button and "Continue on your subscription". When
the sun is dead, the sessions that still run are visibly alive. Nothing hangs silently for 4 minutes.

---

## 1. Research findings (verified 2026-10-15, Claude Code 2.1.300)

### 1.1 What Claude Code sends to a custom `ANTHROPIC_BASE_URL`
I ran a local probe that logged request lines and header names and answered 401, then ran one
`claude -p "[Hypernova test] hi" --model haiku` with the owner's subscription login and only
`ANTHROPIC_BASE_URL` set. The transcript is deleted.
- `HEAD /api/hello` once (a connectivity check, Bun user agent), then `POST /v1/messages?beta=true`.
  There were no `count_tokens` or `/v1/models` calls in `-p` mode.
- Headers: `accept`, `accept-encoding`, `anthropic-beta`, `anthropic-dangerous-direct-browser-access`,
  `anthropic-version: 2023-06-01`, **`authorization`**, `content-type`, `user-agent:
  claude-cli/2.1.300 (external, sdk-cli)`, `x-app: cli`, **`x-claude-code-session-id`**, and the
  `x-stainless-*` set (arch, lang, os, package-version, retry-count, runtime, runtime-version, timeout 600).
- `anthropic-beta` starts with **`oauth-2025-04-20`**, followed by interleaved-thinking,
  context-management, prompt-caching-scope, claude-code-20250219, effort and others.
- Body: `stream: true`, `model: "claude-haiku-5-5"` (for `--model haiku`), `max_tokens: 128000`, 47
  tools, plus `thinking`, `context_management`, `output_config` and `metadata.user_id`.
- **Security consequence:** when Claude Code is signed in with a subscription and no
  `ANTHROPIC_AUTH_TOKEN` or `ANTHROPIC_API_KEY` is set, **it sends the owner's Claude OAuth token to
  whatever base URL is configured**. Hypernova lets a custom provider have no key (the Mac editor
  says "Optional for local gateways"), so that provider receives the owner's Claude login today.
  §4.1 fixes this.
- A 401 is retried **12 times over 255 s** (gaps 1.5 s, 2 s, 3 s, 5 s, 9 s, 17 s, then 33–40 s; the
  `x-stainless-retry-count` header stays 0 because the retries are Claude Code's own). Only then does
  the turn end: `"Failed to authenticate. API Error: 401 …"`, `error: "authentication_failed"`,
  `apiErrorStatus: 401`, result `terminal_reason: "api_error"`.
- **Every retry writes a transcript entry**: `{type:"system", subtype:"api_error", level:"error",
  error:{status, formatted, message, requestId, isNetworkDown, rateLimits, noResponse}, retryInMs,
  retryAttempt, maxRetries}`. The SDK emits `{type:"system", subtype:"api_retry", attempt, max_retries,
  retry_delay_ms, error_status, error}`. The daemon reads neither today, so a failing provider looks
  like "THINKING" for minutes.
- A 429 whose `retry-after` is too long makes Claude Code give up at once
  (`tengu_api_retry_after_too_long`). A short one is retried silently. The `anthropic-ratelimit-unified-*`
  headers (`-status`, `-reset`, `-representative-claim`, `-5h-*`, `-7d-*`, `-overage-*`) fill
  `rate_limit_info`. API-key and third-party responses carry none of these headers.

### 1.2 Claude Code's auto-continue (from the 2.1.300 binary)
- **When it applies:** only on a claude.ai subscription (not usage-based billing, not an API key, not a
  third-party provider). The rejection must name a reset no more than **24 h** away (`TCt`). It turns
  on by itself unless the setting `autoContinueAtUsageLimit` is false (in `/config` or
  `~/.claude/settings.json`; the owner's file doesn't set it, so it's on).
- **Notices it writes** (`system` informational, level `notice` unless marked warning):
  - armed: "Usage limit reached · continuing automatically {when} · esc to cancel"; "Usage limit
    reached again · continuing automatically {when} · esc to cancel"; "Continuing automatically
    {when} · esc to cancel"
  - cancelled (Esc, Ctrl+C or the menu): "Automatic continue cancelled · /rate-limit-options to re-arm"
  - fired: "Usage limit reset · continuing automatically"; "Usage limit available again · continuing now"
  - stale (the Mac slept through the reset): "Usage limit has reset · press enter to continue"
  - warning, off by setting: "Automatic continue was turned off · this task will not resume on its
    own (/rate-limit-options to wait anyway)"
  - warning, too far: "Automatic continue stopped · the usage limit now resets more than 24 hours
    out, so this task will not resume on its own (/rate-limit-options to wait anyway)"
  - warning, cap: "Automatic continue stopped after repeated usage-limit hits · this task will not
    resume on its own (/rate-limit-options to try again)"
  - warning, dropped: "Automatic continue did not run · the continuation was blocked before it reached
    the model, so this task did not resume on its own · send a prompt to continue"
  - exit or handoff: "Automatic continue cancelled · Claude Code exited during the wait, so the task
    will not resume on its own …" (also relaunched, background, Desktop and cloud variants)
- **Cancel:** Esc, Ctrl+C or the kill-agents chord while armed. Submitting a prompt yourself also
  takes over (`manual_submit`).
- **`/rate-limit-options`** (`local-jsx`, enabled for subscribers). It opens a menu titled
  **"What do you want to do?"** with, in order:
  1. sometimes "Switch to usage credits", "Add funds to continue with …" or "Ask your admin for more usage"
  2. sometimes "Upgrade your plan"
  3. "Stop and wait for limit to reset" ("Stop" on usage billing)
  4. either **"Don’t continue automatically"** (while armed; it answers "Automatic continue cancelled.
     Your session will wait for you instead; /rate-limit-options can arm it again.") or **"Wait here,
     then continue automatically at {time}"** (or "… shortly", or "… when the limit resets"; it
     answers "Claude Code will continue automatically at {time}. Keep this session open; it may still
     pause for permission prompts. Press esc to cancel the wait.")
  5. sometimes "Use your reset now (…)" and other options

  Claude Code opens this menu by itself at a limit when auto-continue isn't armed.
- **Firing:** at the reset plus a random 30–90 s, it queues a hidden (`isMeta`) prompt: "Your claude.ai
  usage limit has reset. Continue the task you were working on when the limit was reached; do not
  repeat work that is already complete." It re-arms at most **2** times in a row (then "cap"). A
  wake more than 30 min past the fire time is "stale" and waits for Enter. It may prefill "continue"
  in the input box.

### 1.3 OpenRouter (docs, 2026-10)
- `GET https://openrouter.ai/api/v1/key` takes the inference key (`Authorization: Bearer`) and returns
  `data: {limit, limit_remaining, limit_reset, usage, usage_daily, usage_weekly, usage_monthly,
  is_free_tier, free_model_daily_requests: {used, limit, remaining}, budgets[], effective_budget:
  {limit_usd, spend_usd, remaining_usd, reset_interval, resets_at} | null, …}`. `limit_remaining` is
  null when the key has no cap.
- `GET /api/v1/credits` (account `total_credits` and `total_usage`) needs a **management key** and
  returns 403 for an inference key. So the account's own balance can't be read with the key Claude
  Code uses. Only the key's cap or budget can.
- 402 means a negative balance (`metadata.limit_source: "openrouter_credits"` or
  `"openrouter_key_limit"`). A 402 with `"openrouter_in_flight_budget"` is transient and comes with a
  `Retry-After`. An admin's budget cap returns **403** with `openrouter_guardrail_budget`,
  `openrouter_workspace_budget` or `openrouter_key_limit`, which is not a bad key. A platform 429 puts
  `X-RateLimit-Limit/Remaining/Reset` (epoch ms) in the error JSON's `metadata.headers` and may send
  `Retry-After`. A limit hit mid-stream arrives as an error event after a 200.

---

## 2. Decisions
1. **Auto-continue is per session, and Claude Code is the truth for its own sessions.** The daemon
   reads Claude Code's notices. "Don't" presses Esc while "esc to cancel" is armed. "Continue
   automatically" runs `/rate-limit-options` and picks "Wait here, then continue automatically …".
2. **Hypernova carries on what Claude Code can't:** SDK sessions at the subscription's reset, sessions
   on other providers at a rate limit's announced reset, and sessions whose Claude Code exited during
   the wait (restarted with `--resume`). The text is the same, the timing (reset + 30–90 s) is the
   same, and so are the cap of 2 re-arms and the 24 h horizon. SDK sessions follow
   `autoContinueAtUsageLimit` too.
3. **Out of credits or a refused key never continues by itself** (nothing to wait for, and a continue
   costs money). When the credit comes back, the session says so and offers Continue (owner question 1).
4. **After the cause ends, a session that didn't carry on keeps a "ready" card with Continue** until
   you act. It never silently goes back to idle.
5. **Live retries show.** "RETRYING · 429 · 3 of 10" in the status line, from Claude Code's own retry
   entries.
6. **One companion star for all other providers.** Their sessions are ringed moons orbiting it, not
   their project's planet. It's unaffected by the sun's life, death, eclipse and Big Bang.
7. **Safety fix:** a non-built-in provider never receives the Claude login unless it is a loopback
   proxy explicitly marked `passLogin` (dev, admin token only).
8. **OpenRouter balance** comes from `/api/v1/key`: what's left under the key's cap or budget, else
   today's spend, plus free-model requests left today.

---

## 3. Protocol (docs/PROTOCOL.md changes)

### 3.1 Types
```ts
Session += {
  retry: null | {                 // Claude Code is retrying a failed API request now
    status: number | null,        // HTTP status; null = no response / network
    attempt: number, max: number,
    nextAt: number,               // epoch ms of the next try
    text: string                  // Claude Code's short form ("429 Rate limit exceeded"), ≤120 chars, request ids removed
  }
}
Session.blocked += {
  resume: null | Resume           // only for kinds "limit", "model" and "provider"
}
// blocked.autoResumeAt stays, = resume.at while resume.state == "armed", else null (old clients)

Resume = {
  state: "armed" | "off" | "ready",
                                  // armed: carries on by itself at `at`
                                  // off: won't by itself (turnable on when canArm)
                                  // ready: the cause is over but it didn't carry on; offer Continue
  at: number | null,              // armed: when it carries on; off: when the limit lifts (null = unknown);
                                  // ready: when it lifted
  by: "claude" | "hypernova",     // who carries it on: Claude Code in its terminal, or the daemon
  canCancel: boolean,             // armed, and the daemon can stop it from here
  canArm: boolean,                // off, and the daemon can turn it on from here
  why: null | "cancelled" | "setting" | "far" | "repeated" | "exited" | "slept" | "dropped" | "no_reset" | "back"
}

Event user += { auto?: "limit" }  // an automatic continuation: drawn as a notice row, not a bubble

Provider += {
  status: null | { state: "no_credit" | "bad_key" | "limited" | "down",  // "down" is new
                   text, since,
                   resetsAt: number | null,      // new: limited only, and only when the provider said so
                   budget: boolean },            // new: no_credit from a budget or key cap, not the balance
  balance: null | Balance,        // OpenRouter only
  passLogin: boolean              // Claude Code's own login goes to this loopback endpoint (dev)
}
Balance = { remaining: number | null,   // USD left under the key's cap/budget; null = no cap (the account's balance isn't readable)
            usedToday: number | null,   // USD, UTC day
            freeLeft: number | null, freeLimit: number | null,   // ":free" model requests today
            checkedAt: number,
            error: null | "bad_key" | "unreachable" }
ProviderInput += { passLogin?: boolean }   // accepted only on the admin port and with a loopback baseUrl
```

### 3.2 Routes
| Method & path | Body → Response |
|---|---|
| `POST /v1/sessions/{id}/auto-continue` | `{on: boolean}` → `{session}`. 404 `no_session`; 409 `not_limited` (no `resume`), `not_available` (`canArm`/`canCancel` false), `needs_answer`, `waiting` (another menu or dialog), `typing` (unsent text in the terminal; `pending`), `not_confirmed` (Claude Code didn't confirm within 5 s; state unchanged), `no_terminal` |
| `POST /v1/sessions/{id}/continue` | `{reason?: "away" \| "limit"}` → `{session}`. `"limit"` sends the continuation text (§4.5) and clears `blocked`. No body behaves as it does now |
| `POST /v1/providers/{id}/check` | `{}` → `{provider}`: reads the balance now (at most once in 10 s). 409 `no_balance` for providers without a balance API |
| `PUT/POST /v1/providers` | also takes `passLogin`. 403 `admin_only` from a phone token; 400 `not_local` when `baseUrl`'s host isn't 127.0.0.1, ::1 or localhost |

No new WebSocket messages: `session` and `providers` carry everything. Clients ignore unknown
`resume.why` and `status.state` values and show them generically.

---

## 4. Daemon behaviour

### 4.1 Provider environment (safety; `store.ts` `providerEnv`)
- With a key: as now (`ANTHROPIC_AUTH_TOKEN=key`, `ANTHROPIC_API_KEY=""`).
- **No key and not `passLogin`:** `ANTHROPIC_AUTH_TOKEN=hypernova-no-key`, `ANTHROPIC_API_KEY=""`, so
  Claude Code never attaches the owner's login. A keyless local gateway still works.
- `passLogin` (loopback only): no auth variables, so Claude Code's own login goes to that proxy.
- Test daemon only: with `HYPERNOVA_SUPPORT` set, `HYPERNOVA_DEV_BASE_URL=<loopback URL>` adds
  `ANTHROPIC_BASE_URL` to the **built-in** provider's environment, so the subscription path can be
  tested through the fake (§5). Otherwise it's ignored with one log line.
- The Mac editor's key placeholder becomes "Optional for local gateways. Your Claude login is never sent."

### 4.2 Classifier (`blockers.ts`, other providers only; the built-in table is unchanged)
In order:
1. The body names `limit_source`. `openrouter_in_flight_budget` → `limited`, `resetsAt` = now + the
   text's retry-after or 60 s. `openrouter_key_limit`, `openrouter_guardrail_budget` or
   `openrouter_workspace_budget` (402 or 403) → `no_credit` with `budget: true`.
   `openrouter_credits` → `no_credit`.
2. 402, "insufficient credits", "credit balance" → `no_credit`.
3. 429 or `rate_limit` → `limited`. `resetsAt` comes from `rate_limit_info.resetsAt`, else
   `"X-RateLimit-Reset":"<ms|s>"` in the text, else "retry after N s" or "try again in N
   seconds|minutes", else **null**. The 3-hour expiry stays internal, for clearing only, and is never shown.
4. 401, 403, `authentication_failed`, `permission_error`, `oauth_org_not_allowed` → `bad_key`.
5. `overloaded`, 5xx, or no response once Claude Code gave up → **`down`** (new). It clears on a reply,
   an edit, or after 10 min.

`classifyNotice` gains the §1.2 texts: armed (also the command output "Claude Code will continue
automatically …"), cancelled (why `cancelled`), off (`setting`, `far`, `repeated`, `exited`), ready
(`slept` for "has reset · press enter", `dropped` for "did not run"), and reset. In chat, Claude Code's
terminal-only tails are dropped: " · esc to cancel", " · /rate-limit-options to re-arm", "
(/rate-limit-options to …)", " · /rate-limit-options to try again", "; /rate-limit-options can arm it
again", " · press enter to continue", " · send a prompt to continue".

### 4.3 Retry state (`normalize.ts`, `runner.ts`, `hub.ts`)
- `system/api_error` entries (terminal) and `system/api_retry` (SDK) give `session.retry`
  (`nextAt` = the entry's time + `retryInMs`). Only the newest is kept, and it's broadcast only when
  the attempt changes. No events are made (no chat noise).
- It clears on any assistant output, the final error (which becomes `blocked` or a notice), a `result`,
  Stop, the session going idle, or `nextAt` + 2 min passing with nothing new.

### 4.4 Provider status and balance (`account.ts`, new `balance.ts`)
- The status keeps `resetsAt` (only one the provider gave) and `budget`.
- **Balance** applies to `kind: "openrouter"`: `GET {baseUrl}/v1/key` (`baseUrl` defaults to
  `https://openrouter.ai/api`), 8 s timeout. `remaining` = `effective_budget.remaining_usd` ??
  `limit_remaining`; `usedToday` = `usage_daily`; `freeLeft`/`freeLimit` come from
  `free_model_daily_requests`. Nothing else is kept. The key, response body and `label` are never
  logged or stored. It lives in memory only.
- When it runs:
  - after the provider is saved with a new key
  - on `POST …/check`
  - when a turn starts on it and the last check is over 5 min old
  - right away when it goes `no_credit`, then every 2 min for 30 min while any session is blocked on
    it, then every 15 min until the block clears or 24 h pass

  Never more often than every 60 s (10 s for `/check`).
- A 401 from `/key` → status `bad_key` ("OpenRouter refused the key"). `remaining > 0.01` after a
  `no_credit` that began before the check → clear the status (credit is back). `remaining: null` can't
  tell, so the status holds until a reply or Try again.

### 4.5 Auto-continue (new `resume.ts`; uses `terms.ts`, `runner.ts`)
State per session: `{episode, state, at, by, why, rearms}`, kept in `<support>/resume.json` (0600).
`blocked.resume` is its view.

**Arming when a session is blocked** (`limit` or `model` with a known reset; `provider` with state `limited`):

| Session | `by` | Initial state |
|---|---|---|
| Built-in, terminal (shared or not) | claude | Waits 5 s for Claude Code's notice. Armed → `armed`. None → `off` (`why: "setting"` if `autoContinueAtUsageLimit` is false, else null). `canCancel`/`canArm` only in a shared terminal. `far` if the reset is over 24 h away (no arm) |
| Built-in, SDK | hypernova | `armed` at reset + rand(30–90 s), unless the setting is off (`off`/`setting`) or the reset is over 24 h away (`off`/`far`). Five-hour and weekly windows only, not one-model limits |
| Other provider, shared terminal or SDK, `limited` with a provider-given reset ≤ 24 h | hypernova | `armed` at reset + rand(5–20 s) |
| Other provider, `limited` with no reset, or `down` | hypernova | `off`, `why: "no_reset"`, `canArm: false` (the card offers Try again) |
| Claude Code exited during the wait | hypernova | `off`, `why: "exited"`, `canArm: true`. Firing resumes it in a new shared terminal (`createSharedSession({resume, prompt})`, SDK fallback) |
| `no_credit`, `bad_key`, signed out and the rest | | `resume: null` |

**Claude Code notices always win** for any session they appear in, including a `passLogin` provider:
armed, cancelled (`off`/`cancelled`), the off reasons, `ready` (`slept`/`dropped`).

**Firing (by hypernova).** At `at`, if the session is idle with no approval, no menu and no unsent
text, and the account or provider isn't known to be still in trouble with a later reset, send the
text with `auto: "limit"`. Built-in: Claude Code's own text (§1.2). Other providers: "The rate limit
has passed. Continue the task you were working on when it was reached; do not repeat work that is
already complete." In a shared terminal it's typed by the normal prompt path; text in the box →
`ready`/`dropped`.
- Hit again: re-arm at the new reset, `rearms + 1`. After 2 re-arms → `off`/`repeated`, `canArm: true`.
- More than 30 min late (the Mac slept, or the daemon was down) → `ready`/`slept`. Don't fire.
- Any prompt you send to the session ends the resume (a new block arms afresh).
- Daemon restart: hypernova schedules come back from `resume.json`. Claude-driven states are re-read
  from the transcript (the newest notice after the block wins).

**Ready.** When a block's cause ends (the reset passes, the provider recovers, the account is ok) and
its resume isn't armed, the daemon keeps `blocked` with `resume.state: "ready"`. It doesn't clear it.
`why` stays (`cancelled`, `slept`, `dropped`), or becomes `"back"` for a provider that recovered.
It clears on a prompt, a reply, the session ending, or after 12 h. Claude Code's "has reset · press
enter" and "did not run" notices can create a ready block on a session whose block already cleared.

**Terminal actions (by claude).** Run one at a time per session, logged without screen contents:
- **Cancel (`on: false`).** Guards: shared terminal, Claude idle (registry not busy, no spinner), no
  approval, `session.menu` null, an input box that's empty or exactly `continue` (Claude Code's
  prefill), and "esc to cancel" visible on screen. Press **Esc once**. Confirmed when a "cancelled"
  notice arrives or "esc to cancel" leaves the screen within 4 s. Fallback if not: type
  `/rate-limit-options`, wait ≤4 s for the menu titled "What do you want to do?", and pick
  `/^Don.t continue automatically$/`. Still nothing → 409 `not_confirmed`.
- **Arm (`on: true`).** Same guards, with "esc to cancel" not visible. If the rate-limit menu is
  already open (Claude Code opened it), pick `/^Wait here, then continue automatically/` in it.
  Otherwise type `/rate-limit-options`, wait ≤4 s for the menu, and pick that option. If it has no
  such option, press Esc to close it → 409 `not_available`. Confirmed by "Claude Code will continue
  automatically …" or an armed notice within 4 s.
- While the daemon drives a menu (≤6 s), `session.menu` isn't published, so no card flashes on the
  phone. A menu Claude Code opens by itself still shows as a menu card, as today.

---

## 5. The fake provider (dev and test only)

**Where:** `daemon/test/fake-provider/` (`/test` is excluded from the Mac bundle by `build.sh`). It's a
Node TypeScript server and CLI with no dependencies.

**How it reaches real Claude.** Claude Code sends its OAuth bearer and `oauth-2025-04-20` beta to the
base URL (§1.1). In **pass** mode the fake forwards each request byte for byte (path, query, body, and
all headers but hop-by-hop ones and `host`) to `https://api.anthropic.com` and streams the answer back,
response headers included (`anthropic-ratelimit-unified-*` too). To Anthropic this is identical to
Claude Code's direct call. Cost stays low because the provider's four model slots are all
`claude-haiku-5-5` (no body rewriting).
- **First implementation step: verify pass-through with one Haiku call.** If Anthropic refuses
  forwarded requests, the fallback is **canned** mode for everything: an Anthropic-shaped SSE reply,
  "Fake provider here. Nothing went to a model." (`message_start` … `message_stop`), a JSON reply for
  `stream:false`, `count_tokens` → `{input_tokens: 1000}`, `HEAD /api/hello` → 200. Every limit test
  still works. Only "sessions really work" is lost.
- **log** mode answers 401 and logs header names plus the `anthropic-beta` value. It's for the §4.1
  check.

**Two providers**, registered by `cli.ts setup` through the test daemon's local API (admin token):
1. "Fake (Claude)": `custom`, `baseUrl http://127.0.0.1:47330`, `passLogin: true`, all model slots
   `claude-haiku-5-5`. Pass mode, so sessions really work.
2. "Fake OpenRouter": `openrouter`, `baseUrl http://127.0.0.1:47330/or`, key `fake-or-key`. Its
   requests are canned (there's no login to forward), and `/or/v1/key` serves a balance the tests set.

**Control API** (loopback only, header `x-fake-token`, token in `<dir>/token`, 0600).
`<dir>` is `$HYPERNOVA_SUPPORT/fake-provider`, else `/tmp/hypernova-fake-<uid>`.
- `POST /_fake/faults` `{kind, sid?, times?, until?, ...params}` → `{fault}`. `sid` matches the
  `x-claude-code-session-id` header (a prefix is fine); without it, every request matches. `times`
  defaults to unlimited; `until` is epoch seconds.
- `GET /_fake/faults` → `{faults, log}`. The log keeps the last 200 lines: time, sid[0..8], method,
  path, status, ms, fault id. No header values, no bodies.
- `DELETE /_fake/faults[/{id}]`
- `POST /_fake/balance` `{remaining, usedToday?, freeLeft?, freeLimit?}`, or `{status: 401}`
- CLI: `node daemon/test/fake-provider/cli.ts start [--port 47330] [--mode pass|canned|log] | setup |
  fault <kind> [--sid …] [--times n] [--for 10m] [--retry-after s] | clear | log | balance <usd|none|401>`.

**Faults.** "Expected" is the design. The first run of each records Claude Code's actual transcript
entries (scrubbed) into `daemon/test/fixtures/provider-errors/<kind>.jsonl`, and the classifier tests
assert against those recordings.

| kind | The fake answers | Claude Code (expected) | Hypernova shows |
|---|---|---|---|
| `credits` | 402 `{"error":{"code":402,"message":"Insufficient credits. Add more using https://openrouter.ai/settings/credits","metadata":{"limit_source":"openrouter_credits"}}}` | Stops at once | `no_credit`; "Fake OpenRouter · out of credits", "$0.00 left" after the check; Add credits; notified once; companion star dims with an amber ring |
| `budget` | 403, `limit_source: "openrouter_key_limit"`, "Key limit exceeded" | Stops | `no_credit` with `budget` ("· budget reached"), **not** key refused |
| `in_flight` | 402, `Retry-After: 20`, `openrouter_in_flight_budget` | Stops | `limited`, about 60 s; "Continues by itself at hh:mm"; fires; works |
| `rate` | 429, `retry-after: 3600`, body `metadata.headers["X-RateLimit-Reset"]` = now + `--for` (ms) | Gives up at once (retry-after too long) | `limited` with that reset; armed; continues by itself once `until` passes |
| `rate_short` | 429, `retry-after: 3`, `--times 2` | Retries silently, then succeeds | "RETRYING · 429 · 1 of 10" for a few seconds; no block |
| `rate_bare` | 429, no headers, plain body | Retries ~10× (~4 min), then stops | Retry line, then `limited` with no reset: "It didn't say for how long." and Try again |
| `key` | 401 `authentication_error` | Retries ~12× (~4 min; measured with OAuth, to be measured with a key) | Retry line, then `bad_key`: "key refused" and Fix the key |
| `forbidden` | 403 `permission_error` | Stops or retries | `bad_key` |
| `overloaded` | 529 `overloaded_error` | Retries, then the error | Retry line, then `down`: "not answering" and Try again |
| `slow` | Holds the first byte `--ms` (default 40000), then passes through | Waits, or retries with `no_response` past its first-byte timeout | The THINKING timer runs, or "RETRYING · no answer · 1 of 10"; never an error from a slow first token |
| `cut` | Passes through, destroys the socket after `--bytes` (default 1500) of the stream | Connection error, retry | Retry line, then the reply completes |
| `error_event` | 200 stream, then `event: error` `{type:"overloaded_error"}` mid-reply | Retries or errors | As `cut` or `overloaded` |
| `limit_sub` | 429 + `anthropic-ratelimit-unified-status: rejected`, `-reset: <until>`, `-representative-claim: five_hour`, body `rate_limit_error` | "You've hit your session limit · resets hh:mm" + auto-continue armed | On a test daemon with `HYPERNOVA_DEV_BASE_URL` (built-in): account limited, supernova → black hole, "Continues by itself at hh:mm" [Don't]; at reset + jitter it continues |

---

## 6. UX

### 6.1 Phone, session screen

**The blocked card** (`BlockedCard`, extended). It's a glass card above the composer: the title in
heading ink, one mono line in accent-dim (amber when it needs you), and pills. Auto-continue lives on
the mono line, with the action as a ghost pill at its end. The primary way on stays as it is.

```
  ⓘ You've hit your session limit · resets 2am         ← Claude Code's notice row
╭──────────────────────────────────────────────╮
│ Session limit reached                          │
│ Continues by itself at 2:00          ( Don't ) │  mono accent-dim · ghost pill
│                                                │
│ [ Continue on OpenRouter ]                     │  existing
╰──────────────────────────────────────────────╯
╭ Claude can't reply until 2:00            ⌁  ➤ ╮  composer hint (existing)
```
After Don't (a brief "Turning off…" in the pill):
```
╭──────────────────────────────────────────────╮
│ Session limit reached                          │
│ Back at 2:00                                   │
│ [ ⟳ Continue automatically at 2:00 ]           │  outline pill
│ [ Continue on OpenRouter ]                     │
╰──────────────────────────────────────────────╯
```
Other providers (title = provider state; amber when it needs you):
```
╭──────────────────────────────────────────────╮   ╭──────────────────────────────────────────────╮
│ OpenRouter · out of credits                    │   │ OpenRouter · rate limited                      │
│ $0.00 left                                     │   │ Continues by itself at 14:05         ( Don't ) │
│ [ Add credits ↗ ] [ Continue on your subscr… ] │   │ [ Continue on your subscription ]              │
╰──────────────────────────────────────────────╯   ╰──────────────────────────────────────────────╯
```
Ready (the cause is over and it didn't carry on):
```
╭──────────────────────────────────────────────╮
│ OpenRouter has credit again                    │
│ $5.00 left                                     │
│ [ ▶ Continue ]                                 │  primary, POST /continue {reason:"limit"}
╰──────────────────────────────────────────────╯
```
- **Status line:** while `retry` is set, "RETRYING" (amber-dim; the planet turns slowly in amber-dim),
  then the detail "429 · 3 of 10" (or "no answer · 3 of 10"). Blocked states read OUT OF CREDITS,
  RATE LIMITED, KEY REFUSED or NOT ANSWERING. Limits keep PAUSED.
- **Top bar subtitle** of a session on another provider: `project · OpenRouter`, with a small ring
  glyph before the provider's name.
- **Chat:** a user event with `auto: "limit"` is a slim notice row, "Continued by itself after the
  limit reset". It's never a bubble.
- A failed toggle: toast "Didn't go through. Open the terminal to check." The pill returns to its
  last state.

### 6.2 Phone, Home and Settings
- **Session card:** on another provider, the seam planet gets the ring. The PhaseChip reads "Out of
  credits", "Rate limited", "Key refused" or "Not answering" (amber when it needs you). The activity
  line reads `OpenRouter · out of credits`, `OpenRouter · continues by itself 14:05`, `session limit ·
  continues by itself 2:00`, or `session limit · back 2:00`.
- **The rest card** (existing "Claude is resting until 2:00") gets one row for auto-continue across
  sessions, and one for live work elsewhere:
```
╭─ ● SESSION LIMIT ─────────────────────── returns in 1h 12m ─╮
│ Claude is resting until 2:00                                   │
│ Your 5-hour limit is used up.                                  │
│ 2 sessions continue by themselves at 2:00             ( Don't ) │
│ ◯ 1 session on OpenRouter keeps going                          │
╰───────────────────────────────────────────────────────────────╯
```
  Don't loops over the armed sessions with `canCancel`. When none is armed and one or more can arm,
  the row is an outline pill, "Continue automatically at 2:00". The second row shows only while a
  session on another provider is working or needs you. Tapping it filters the list to those sessions.
- **Settings → Providers:** each row ends in its trail: "$4.20 left", "$1.10 today" (no cap),
  "37 of 50 free today", or in amber "Out of credits", "Key refused", "Rate limited · back 14:05",
  "Not answering". Opening the screen runs a check (silently). After you save a key, the editor shows
  one mono line: "Key works · $4.20 left", or "Key refused" in amber.
- **Notifications** ("Session updates" channel and in-app banners), once per provider episode
  (provider id + `since`):

| Event | Title | Body |
|---|---|---|
| Out of credits | "OpenRouter is out of credits" | "2 sessions paused. Add credits or continue on another provider." |
| Credit back | "OpenRouter has credit again" | "Paused sessions can carry on." |
| Key refused | "OpenRouter refused its key" | |
| Rate limited, armed | "OpenRouter rate limit" | "Continues by itself at 14:05." |
| Slept through a reset | "The limit reset while your Mac slept" | "2 sessions are waiting." |

  Retries never notify.

### 6.3 The sky: the companion star (`Solar.kt`, `Cosmos.kt`, `Drain.kt`)
```
     ·        ✧        ·            ✦  ◦     ← companion: a white dwarf, ringed moons round it
  ·     ◌        ·           ·    (◦)        OPENROUTER · 2
        ·   ◎ ◌  ·  ⦿  ·   ·            ·    $4.20
     planet (dark)   black hole              ← the sun is dead; the companion system lives
```
- **When it appears:** while one or more non-ended sessions run on a provider whose id isn't
  `anthropic`. It ignites over 1.2 s (a point brightening, no blast) and fades over 2 s after the
  last one ends.
- **Where:** fixed in sky space at (0.84·w, skyTop + 0.2·skyH), drawn before the planets (it's far
  behind), and part of the solar system (no parallax, so taps land).
- **Look:** a white dwarf. A 2.4 dp ink-white core, a 10 dp halo at ink 18%, four faint 8 dp spikes at
  ink 10%, and a slow twinkle (±6%, 0.3 Hz). Its look doesn't age with usage.
- **Trouble:** when every provider with sessions here is in trouble, it dims (core inkDim, halo 8%).
  `no_credit` or `bad_key` adds a 1 dp amber ring.
- **Readout** (mono 10 sp, inkDim; amber when it needs you): "OpenRouter · $4.20", "OpenRouter · out
  of credits", or "2 providers". It fades where orbits cross it, like the sun's.
- **Sessions on other providers are moons of the companion**, not of their project's planet. They sit
  in shells of 4, 7, 10… at 16, 24, 32 dp, inclined 20° like the view, and are lit from the companion.
  Each wears a **ring**: a 1 dp ellipse at 1.9× the moon's radius, in the moon's colour at 45%,
  tilted with the shell. Colours as usual: mint working, dim idle, amber needs you, with the focus
  bracket. Their projects keep their planets (size and turning count all the project's sessions); a
  project with only such sessions is a moonless planet.
- **Label** like a planet's: "OPENROUTER · 2" (two providers: "2 PROVIDERS · 3"). A tap focuses it
  like a planet. The camera flies in, the list filters to sessions on other providers, and the moon
  column lists them. The hit is `CosmosHit.Companion`; the zoom key is `"@providers"`.
- **Death states:** supernova, black hole, heat death, eclipse and Big Bang leave the companion system
  untouched. It keeps shining and its moons keep orbiting. Android 13+: the Drain shader gets a
  second keep-region, a circle round the companion system (outermost shell + 12 dp), where every
  colour stays whole. Android 12 (matrix) drains to sat 0.35 and brightness 0.8 while the companion
  is lit. On every version, while a session on another provider is working or needs you, the app's
  drain stops at sat 0.45, so mint and amber still read in the list. The sky's own death effects
  stay as they are.
- **Reduce Motion:** static, like the other bodies. Shader uniforms are quantised as the hole's are.

### 6.4 Mac popover and Providers
```
 SESSIONS
 ⦿ Fix the parser · hypernova
   paused · continues by itself 2:00                 Don't
 ◯ Port the widget · 0stems                     OpenRouter
   out of credits · $0.00                      Add credits
 ⦿ Survey · lens   retrying · 429 · 3/10
 USAGE   5h ████████████ 100%  2:00    7d ███░░ 61%
         OpenRouter  $4.20 left
```
- Off: "paused · back 2:00" with the link button "Continue automatically". Ready: "limit reset ·
  waiting" with "Continue".
- Providers view: the same trail as the phone, plus a Check button. `passLogin` isn't shown.

### 6.5 Lens
- Glance lines: a provider session's planet gets a ring. The state text reads "continues 2:00",
  "paused · back 2:00", "OpenRouter · no credit" or "retrying 429". No new actions.

---

## 7. Edge cases
- **Many sessions at one limit:** each has its own resume. Home's Don't covers all of them. Account
  episodes are unchanged.
- **Don't arrives as Claude Code fires:** the session is working, so the route answers 409
  `not_limited` and the card is already gone.
- **The input box holds Claude Code's "continue" prefill:** Esc and the menu treat it as empty. Typing
  `/rate-limit-options` over it works (Claude Code rewrites "continue/…" into "/…").
- **Unsent text of your own in the box:** 409 `typing`, and the toast says "There's unsent text in the
  terminal. Open it to finish."
- **Observed terminals** (not shared): states show, both actions are off, and the line ends in
  "· in the terminal".
- **The weekly limit over 24 h away:** "Back Sun 14:00", with no auto option (Claude Code's rule,
  applied to SDK sessions too).
- **Running on usage credits:** not blocked, so there's no resume.
- **Moving the session to another provider** cancels its resume, and its block clears as now.
- **Editing a provider's key:** the status clears, and its blocked sessions become `ready`/`back`
  ("OpenRouter is back" + Continue).
- **The Mac sleeps through the reset:** Claude Code goes stale and Hypernova follows (`ready`/`slept`).
  One notification on wake. Nothing fires late.
- **`remaining: null`** (a key with no cap): no balance claim is made; the trail shows today's spend.
- **The balance check fails or is offline:** the last balance stays with `error`, and the status is
  unchanged.
- **Old clients** ignore `resume`, `retry`, `balance` and `down`, and still see `autoResumeAt`.
- **A `passLogin` provider** hit by `limit_sub`: Claude Code arms as a subscriber and its notices
  drive the state, while Hypernova still counts it as "another provider" (status `limited`).
- **Two providers with sessions:** one companion star, labelled "2 providers".

## 8. Safety
- The §4.1 OAuth fix ships first, in Track A's first commit. The test plan proves it.
- `passLogin` works only on the admin port and with a loopback base URL. `HYPERNOVA_DEV_BASE_URL`
  works only with `HYPERNOVA_SUPPORT` and a loopback URL.
- Keys stay on the Mac. A balance is numbers only. No header values or bodies are logged (daemon or
  fake).
- The daemon answers only the menu titled "What do you want to do?", and only by option label. It
  types only into an empty box. It presses Esc only while idle with "esc to cancel" visible, so it
  never interrupts work.
- Hypernova never auto-continues `no_credit` or `bad_key`, honours `autoContinueAtUsageLimit`, and
  re-arms at most twice.
- The fake: `daemon/test/` only (not bundled), bound to 127.0.0.1, its control API behind a 0600
  token. Its upstream is fixed to api.anthropic.com or canned (a loopback stub only via
  `FAKE_UPSTREAM` in tests). It refuses to start on a non-loopback address.
- Test sessions follow CLAUDE.md: a second daemon, prompts titled "[Hypernova test]", Haiku, hooks
  off where possible, transcripts deleted afterwards. After `limit_sub`, check that
  `claude auth status` and the owner's status line show no limit (the rejection must be per process).

## 9. Performance and battery
- **Phone:** no new polling. The companion is drawn only when present, the moon rings are one cached
  stroke each, and the drain shader is rebuilt only when its quantised inputs change.
- **Daemon:** one timer for the nearest resume. Balance calls are rate-limited (§4.4). Retry state
  comes from lines it already reads, broadcast per attempt.
- **Mac and Lens:** nothing new on a timer.

## 10. Test plan
**Unit** (`node --test`):
- `providers.test.ts`: the classifier on the recorded fixtures (402 credits, 403 key_limit, in-flight
  402, 429 with an X-RateLimit-Reset body, a bare 429, 401, 403 permission, 529), and `classifyNotice`
  on every §1.2 string. `providerEnv`: no key → dummy token; `passLogin` + loopback → no auth vars;
  `passLogin` + remote → 400.
- `resume.test.ts` (fake clock): SDK arm, fire and the cap of 2; slept (more than 30 min late →
  ready); a user prompt cancels; the setting off; over 24 h; restart from `resume.json`; a provider
  `limited` with a reset fires; exited → arm resumes through a stub; ready on recovery; ready clears
  on a prompt or after 12 h.
- `balance`: parse a `/key` fixture; the throttle; `no_credit` clears when remaining > 0; a 401 →
  `bad_key`.
- `normalize`: `system/api_error` → `retry`, cleared by output. An `auto` user event shows as a notice.
- `fake-provider.test.ts`: every fault against a loopback stub upstream (status, headers, body, the
  cut after N bytes, the slow first byte), sid matching, and a request without the token → 401.

**Integration** (test daemon + fake, Haiku):
1. Pass-through: a "Fake (Claude)" session, "[Hypernova test] say hi", gets a real reply. If not, use
   canned mode and note it in DEV-SETUP.
2. The safety fix: a keyless custom provider → the fake in log mode shows `authorization` with an
   `anthropic-beta` that lacks `oauth-2025-04-20`.
3. Every fault in §5's table, in a shared terminal and an SDK session: assert `blocked`, `resume`,
   `retry` and `Provider.status`/`balance` as the table says. Capture phone screenshots. Record the
   fixtures.
4. The subscription path (`HYPERNOVA_DEV_BASE_URL`, `limit_sub --for 3m`): account limited →
   supernova → black hole → "Continues by itself at hh:mm". Don't → a cancelled notice, and the card
   becomes "Continue automatically at hh:mm". Tap it → armed again through `/rate-limit-options`. At
   reset + jitter the session continues and the star is reborn. Then check the owner's install is
   unaffected.
5. Credit back: `credits`, then `balance 5` → within 2 min "has credit again" + Continue, notified once.

**Android:** demo data (FakeHub) gains an armed limit, an OpenRouter session out of credits, one
retrying, and two OpenRouter moons. Unit-test `activeBlock` with ready. Screenshots of each card, the
rest card, Providers, and the companion through a demo supernova (time warp) and under Reduce Motion.

**Mac:** `--render-popover` gains "auto-continue armed", "provider out of credits" and "retrying".

**QA checklist** (owner), new checks:
- the card says "Continues by itself at …"
- Don't, then turning it back on, works in a real terminal
- out of credits shows the balance and Add credits
- the companion keeps moving while the sun is dead
- retries show and then clear
- the slept-through reset shows Continue

## 11. Change list (parallel tracks; no file is in two tracks)
- **A. Daemon** (also lands the protocol docs):
  - new `daemon/src/resume.ts` and `daemon/src/balance.ts`
  - edits to `daemon/src/blockers.ts`, `account.ts`, `store.ts` (providerEnv, `passLogin`,
    `HYPERNOVA_DEV_BASE_URL`), `hub.ts`, `http.ts` (routes), `normalize.ts`, `runner.ts`, `terms.ts`
  - `daemon/test/providers.test.ts`, `resume.test.ts`, `balance.test.ts`, and
    `daemon/test/fixtures/provider-errors/` (fixtures from B's recordings)
  - `docs/PROTOCOL.md`, `docs/DECISIONS.md` (a new section: these decisions), `docs/LATER.md` (rows:
    OpenRouter management key for the account balance; widgets showing ringed provider sessions)
- **B. Fake provider:**
  - `daemon/test/fake-provider/server.ts`, `faults.ts`, `cli.ts`, and `daemon/test/fake-provider.test.ts`
  - `daemon/src/qa-checklist.ts` (the new checks)
  - `docs/DEV-SETUP.md` (a "Fake provider" section)
  - B's `setup` needs A's `passLogin` (until then it prints the manual steps).
- **C1. Android app** (everything but the sky):
  - `data/Protocol.kt` (parse resume, retry, balance, `status.resetsAt`/`budget`, `auto`) and
    `data/Models.kt` (Blocked.resume, ready in activeBlock, Session.retry)
  - `data/Hub.kt`, `LiveHub.kt` (`autoContinue(id, on)`, `continueAfterLimit(id)`, `checkProvider(id)`),
    `FakeHub.kt` (the demo states above), `Notices.kt`
  - `ui/session/SessionScreen.kt`, `ui/home/HomeScreen.kt` (except the cosmos hookup), and
    `ui/settings/Settings.kt`
  - `ui/components/Transcript.kt` (the auto notice row), `ui/components/Primitives.kt` (the seam planet
    ring), `res/values/strings.xml` (§6 copy; also `blocked_carries_on` → "Continues by itself %1$s",
    `blocked_back` → "Back %1$s", `activity_limit_auto` → "%1$s · continues by itself %2$s",
    `a11y_sky` + "%4$d on other providers")
- **C2. Android sky:**
  - `ui/fx/Solar.kt`, `ui/fx/Cosmos.kt` (`providers` parameter, `CosmosHit.Companion`, `"@providers"`
    zoom), `ui/fx/Drain.kt` (the keep-region), and `ui/App.kt` (the `Cosmos(...)` call and the drain
    floor)
  - new `res/values/strings_sky.xml`, and `docs/DESIGN.md` (the companion)
  - The skill copy: `~/.claude/skills/hyper-cosmos/` and an rsync, after C2 lands.
  - C1 handles `CosmosHit.Companion` in HomeScreen's hit callback (filtering to `providerId !=
    "anthropic"`).
- **D. Mac app:** `mac/Sources/Hypernova/Core/Models.swift`, `Core/DaemonClient.swift`,
  `App/AppModel.swift`, `UI/PopoverParts.swift`, `UI/ProvidersView.swift` (trail, Check, placeholder
  copy), `App/PopoverRender.swift` (states)
- **E. Lens:** `lens/src/core/types.ts`, `core/glance.ts`/`format.ts` (state text), `lens/orrery.ts`
  (ring), `data/demo.ts`

Order: A's OAuth fix first. B then records fixtures, and A finishes the classifier on them. C1, C2, D
and E work against §3 from the start (their demo data needs no daemon).

## 12. Owner questions
1. When OpenRouter has credit again (you topped up), should paused sessions continue by themselves?
   **Recommended: no.** Show "OpenRouter has credit again" with Continue, since each continue spends
   money. Built that way.
2. Should Hypernova continue other providers' rate limits by itself at their announced reset, as
   Claude Code does for the subscription? **Recommended: yes, armed by default, with the same Don't.**
   Built that way.
