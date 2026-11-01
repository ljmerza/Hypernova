// A stand-in for Claude Code as the Agent SDK drives it (`--input-format stream-json`): it answers
// the SDK's `initialize` control request with a fixed list of commands and models, and every other
// control request with an empty success, then waits for its input to close. No model is reached and
// nothing is written anywhere. For the routes that only ask Claude Code what it offers (GET
// /v1/commands, commands.ts).
import { createInterface } from 'node:readline'

const commands = [
  { name: 'review', description: 'Review a pull request', argumentHint: '[pr-number]', builtin: false },
  { name: 'compact', description: 'Clear the conversation history but keep a summary in context', argumentHint: '<optional custom summarization instructions>', builtin: true },
  { name: 'config', description: 'Open the config panel', argumentHint: '', aliases: ['settings'], builtin: true },
  // Left out by the daemon: bound to a terminal on the computer, or removed.
  { name: 'vim', description: 'Toggle between Vim and Normal editing modes', argumentHint: '', builtin: true },
  { name: 'old-thing', description: '(removed) Use /new-thing', argumentHint: '', builtin: true },
]
const models = [
  { value: 'default', displayName: 'Default (recommended)', description: 'Opus 5.5 · Best for everyday tasks', resolvedModel: 'claude-opus-5-5' },
  { value: 'sonnet', displayName: 'Sonnet', resolvedModel: 'claude-sonnet-5-5' },
  { value: 'sonnet[1m]', displayName: 'Sonnet (1M context)', resolvedModel: 'claude-sonnet-5-5[1m]' },
]
// Whatever the SDK does with its end of the pipe once it has its answer, this ends: nothing waits on it.
setTimeout(() => process.exit(0), 10_000)
const say = (o) => process.stdout.write(JSON.stringify(o) + '\n')

for await (const line of createInterface({ input: process.stdin })) {
  let m
  try {
    m = JSON.parse(line)
  } catch {
    continue
  }
  if (m?.type !== 'control_request') continue
  const response = m.request?.subtype === 'initialize' ? { commands, models, agents: [], account: { subscriptionType: 'max' }, output_style: 'default', available_output_styles: ['default'] } : {}
  say({ type: 'control_response', response: { subtype: 'success', request_id: m.request_id, response } })
}
