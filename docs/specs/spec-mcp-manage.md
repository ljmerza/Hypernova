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
