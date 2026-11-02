# Managing an agent's effort, MCP servers, plugins and skills from the phone

What `docs/PROTOCOL.md` should say for these routes. It's kept here while that file is being rewritten, to be reconciled.
All of these are for a paired phone only: 401 `unauthorized` otherwise, on the local API too. Decisions: `docs/DECISIONS.md`.

## Agent settings: effort per model

`GET /v1/agents/settings` gains, under `claude`, beside the fixed list:

```json
"effort": {
  "rows": [
    { "key": "modelSettings.claude-opus-5-5.effortLevel", "state": "set", "value": "low", "model": "claude-opus-5-5", "name": "Opus 5.5", "editable": true },
    { "key": "modelSettings.claude-sonnet-5-5.effortLevel", "state": "unset", "model": "claude-sonnet-5-5", "name": "Sonnet 5.5", "editable": true }
  ],
  "choices": [ { "value": "low", "loosens": false }, { "value": "medium", "loosens": false }, { "value": "high", "loosens": false }, { "value": "xhigh", "loosens": false }, { "value": null, "loosens": false } ]
}
```

Left out when the file is a link, read-only, malformed or unreadable. `state` is as in `values`: anything but the four words is `unrecognised`, its value kept on the server.

`PUT /v1/agents/settings` takes such a `key` with `value` one of the four words, or `null` to take it out (a table left empty goes too). Off that shape: 403 `key_not_editable`; another word, 403 `value_not_allowed`. `null` is refused on any other key. `expected`, the reply, the backup, `agent_settings` and Undo are as for every other key; `undo.to` of `null` means not set.

## An agent's MCP servers

Query parameters: `agent` (`claude`, `codex`), `name`, `scope`, `folder`. A folder on the wire is an id and a base name, never its path: `home` is the home folder. A folder must be one the daemon knows: the home folder, Claude Code's projects, projects, a session's, or a parent of one; else 404 `no_folder`. A refusal is the usual `{error:{code,message}}`; a route an older server doesn't have is 404 `not_found`.

- `GET /v1/agents/mcp?agent=`: `{agent, state, servers: [{name, scope, folder?, project?, transport, summary?, approval?}], folders: [{id, name}], hidden, foldersNotRead, scopes, undo}`. `scope`: `user`, `local`, `project`, `settings`. `summary`: a command's base name or a host. `approval`: `approved`, `pending`, `rejected`. Starts nothing. `undo`: `{op, name, scope, folder, needsCheck, command}` or null.
- `GET /v1/agents/mcp/status?agent=&folder=&fresh=1`: `{agent, folder, servers: [{name, status, source, serverName?, version?, error?, tools: [{name, readOnly?, destructive?, openWorld?}], setup?}]}`. Starts the servers, as a session there would. `status`: `connected`, `failed`, `needs-auth`, `pending`, `disabled`, `waiting` (a project's, not approved, not started), `rejected`. `source`: Claude Code's word (`user`, `project`, `local`, `plugin`, `managed`, `claudeai`…). `setup` only for what no file here describes. Kept 20 seconds unless `fresh=1`.
- `GET /v1/agents/mcp/server?agent=&name=&scope=&folder=`: `{agent, name, scope, folder?, project?, where, transport, command?, args?, url?, envNames, headerNames, headersHelper?, oauth?: {clientId?, callbackPort?}, timeout?, alwaysLoad?, hidden, approval?, hash, editable}`. 404 `no_server`.
- `GET /v1/agents/mcp/tools?agent=&name=&scope=&folder=`: `{agent, name, serverName?, version?, instructions?, tools: [{name, description?, annotations: {title?, readOnly?, destructive?, idempotent?, openWorld?}, params: [{name, type, required, description?, default?, choices?}], schema?}], prompts: [{name, description?, args?}], resources: [{name, description?, uri?}], moreTools, whyNot?}`. `whyNot`: `approval`, `disabled`, `no_direct`, `needs_auth`, `too_slow`, `failed`. Text is the server's own, masked and capped.

Changes answer `{ok: true, …}` and broadcast `{t:"agent_settings", agent}`.

- `POST /v1/agents/mcp/servers`, to add; `PUT`, to change. `{agent, scope, folder?, name, transport: stdio|http|sse, command, args, env: [{name, value} | {name, keep: true}], url, headers: […], oauth: {clientId, callbackPort, clientSecret?}, timeout, confirmRun, expected}`. `expected`: the page's `hash`, for `PUT`. An argument or URL still holding what the page masked stands for the real value. 403 `confirm_run` when it would change what runs and `confirmRun` isn't true. 409 `exists`, `file_changed`, `file_is_link`. 400 `bad_name`, `bad_transport`, `bad_command`, `bad_args`, `bad_url`, `bad_pairs`, `bad_pair_name`, `bad_pair_value`, `nothing_to_keep`, `masked_unknown`, `bad_oauth`, `bad_timeout`, `too_big`, `bad_scope`. 502 `cli_failed` with what the command said, cleaned.
- `POST /v1/agents/mcp/servers/remove`: `{agent, scope, folder?, name, expected}`.
- `POST /v1/agents/mcp/undo`: `{agent, confirmRun?}`. 403 `confirm_run` when what it puts back runs something; 409 `nothing_to_undo`, `file_changed`.
- `POST /v1/agents/mcp/toggle`: `{agent, name, folder, enabled}`. For that folder. 409 `needs_approval`.
- `POST /v1/agents/mcp/reconnect`: `{agent, name, folder}`: `{ok, server}` with its state from a fresh process.
- `POST /v1/agents/mcp/approval`: `{agent, name, folder, approved, confirmRun?}`. 403 `confirm_run` for yes without it; 409 `not_trusted`.
- `POST /v1/agents/mcp/reset-choices`: `{agent, folder}`.
- `POST /v1/agents/mcp/login`: `{agent, name, folder}`: `{ok, done: false, login, url}`, or `{ok, done: true, words?}` when there's no sign-in to do. 409 `no_terminal`.
- `POST /v1/agents/mcp/login/finish`: `{agent, login, address}`. `address` must be on localhost; 400 `bad_address`, 404 `no_login`, 409 `still_waiting`.
- `POST /v1/agents/mcp/login/cancel`: `{agent, login}`. `POST /v1/agents/mcp/logout`: `{agent, name, folder}`.

### For Codex

The same routes with `agent=codex`. `scope` is `user`, `scopes` `["user"]`, `folders` the home folder alone, and rows add `enabled`. The page adds `bearerEnv?`, `cwd?`, `toolsOn?`, `toolsOff?`, `startupTimeout?`, `enabled`, `disabledReason?`, `authStatus?` (`unsupported`, `not_logged_in`, `bearer_token`, `o_auth`, `unknown`). A form may send `bearerEnv` (a variable's name, or null), and no `oauth`; `transport: sse` is 400 `bad_transport`. `POST .../approval` and `.../reset-choices` are 409 `not_for_codex`. `no_codex`, 409: not installed.

