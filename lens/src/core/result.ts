// What a finished turn did, from the session's own events: its reply, the files it changed, how
// its tests and builds went, how long it took. Nothing is guessed from the reply's wording. Pure.
import type { HnEvent } from './types.ts';
import { duration } from './format.ts';
import { plainText } from './text.ts';

type ToolEvent = Extract<HnEvent, { kind: 'tool' }>;

export interface RunCheck {
  ok: boolean;
  passed: number | null;
  failed: number | null;
  command: string;
}

export interface TurnFacts {
  /** The prompt that started the turn. */
  prompt: string;
  /** Claude's last message of the turn, as written. */
  reply: string;
  files: string[];
  tests: RunCheck | null;
  build: RunCheck | null;
  durationMs: number | null;
  /** Agents the turn started (Agent calls), and workflows of them. */
  agents: number;
  workflows: number;
  ok: boolean;
  error: string | null;
  finished: boolean;
}

const EDITS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);

// Test runners by their usual command lines (npm test, vitest, pytest, gradle test tasks…).
export const TEST_RE = /(^|[\s;&|(])((npm|pnpm|yarn|bun)\s+(run\s+)?test\b|npx\s+(vitest|jest|playwright\s+test)|vitest\b|jest\b|pytest\b|python3?\s+-m\s+(pytest|unittest)|node\s+--test\b|go\s+test\b|cargo\s+test\b|swift\s+test\b|xcodebuild\b.*\btest\b|mvn\b.*\btest\b|(\.\/)?gradlew\b.*\b(test|check|connected\w*Test)\b|rspec\b|phpunit\b|deno\s+test\b|mix\s+test\b|dotnet\s+test\b)/;
const BUILD_RE = /(^|[\s;&|(])((npm|pnpm|yarn|bun)\s+run\s+build\b|vite\s+build\b|tsc\b|cargo\s+build\b|swift\s+build\b|xcodebuild\b|(\.\/)?gradlew\b.*\b(assemble\w*|build|bundle\w*|dist)\b|make\b|go\s+build\b|mac\/build\.sh\b)/;

const commandOf = (e: ToolEvent) => String((e.input as { command?: unknown })?.command ?? e.summary ?? '');

/** Counts from the common runners' summaries. */
export function testCounts(output: string): { passed: number | null; failed: number | null } {
  const o = output.slice(-6000);
  const num = (re: RegExp) => { let m: RegExpExecArray | null, last: number | null = null; const g = new RegExp(re.source, `g${re.flags.replace('g', '')}${re.flags.includes('i') ? '' : 'i'}`); while ((m = g.exec(o))) last = Number(m[1]); return last; };
  let passed = num(/(\d+)\s+(?:tests?\s+)?(?:passed|passing)\b/) ?? num(/^#\s*pass\s+(\d+)/m) ?? num(/test result: ok\.\s*(\d+) passed/);
  let failed = num(/(\d+)\s+(?:tests?\s+)?(?:failed|failing)\b/) ?? num(/^#\s*fail\s+(\d+)/m) ?? num(/with (\d+) failures?/);
  const swift = /Executed (\d+) tests?, with (\d+) failures?/i.exec(o);
  if (swift) { failed = Number(swift[2]); passed = Number(swift[1]) - failed; }
  const gradle = /(\d+) tests? completed, (\d+) failed/i.exec(o);
  if (gradle) { failed = Number(gradle[2]); passed = Number(gradle[1]) - failed; }
  return { passed, failed };
}

function check(e: ToolEvent): RunCheck {
  const failedByExit = e.status === 'error' || (e.exitCode !== null && e.exitCode !== undefined && e.exitCode !== 0);
  const { passed, failed } = testCounts(e.output ?? '');
  return { ok: !failedByExit && !(failed && failed > 0), passed, failed, command: commandOf(e) };
}

/** The last turn: everything from the last prompt on. */
export function lastTurn(events: HnEvent[]): HnEvent[] {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.kind === 'user' && (!e.command || e.command.kind === 'prompt' || e.command.kind === 'skill')) return events.slice(i);
  }
  return events;
}

export function turnFacts(events: HnEvent[]): TurnFacts {
  const turn = lastTurn(events);
  const first = turn[0];
  const prompt = first?.kind === 'user' ? first.text : '';
  const tools = turn.filter((e): e is ToolEvent => e.kind === 'tool');
  const files = [...new Set(tools.filter(t => EDITS.has(t.name) && t.status !== 'error').map(t => t.path ?? String(t.input?.file_path ?? t.input?.notebook_path ?? '')).filter(Boolean))];
  const bash = tools.filter(t => t.name === 'Bash' && t.status !== 'running');
  const lastOf = (re: RegExp) => { const hit = [...bash].reverse().find(t => re.test(commandOf(t))); return hit ? check(hit) : null; };
  const tests = lastOf(TEST_RE);
  const build = lastOf(BUILD_RE);
  const replies = turn.filter((e): e is Extract<HnEvent, { kind: 'assistant' }> => e.kind === 'assistant' && !!e.text.trim());
  const result = [...turn].reverse().find((e): e is Extract<HnEvent, { kind: 'result' }> => e.kind === 'result');
  const lastTs = turn.length ? turn[turn.length - 1].ts : null;
  const durationMs = result?.durationMs || (first && lastTs ? lastTs - first.ts : null);
  return {
    prompt,
    reply: replies.at(-1)?.text ?? '',
    files,
    tests,
    // One command can match both (`./gradlew build test`): it counts as the tests.
    build: build && tests && build.command === tests.command ? null : build,
    durationMs: durationMs && durationMs > 0 ? durationMs : null,
    agents: tools.filter(t => t.name === 'Agent' || t.name === 'Task').length,
    workflows: tools.filter(t => t.name === 'Workflow').length,
    ok: result ? result.ok : true,
    error: result?.error ?? null,
    finished: !!result || replies.some(r => !r.streaming),
  };
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

function testWords(t: RunCheck, short: boolean): string {
  const n = (k: number, verb: string, verbs: string) => `${plural(k, 'test')} ${k === 1 ? verb : verbs}`;
  if (t.ok) return t.passed ? (short ? n(t.passed, 'passes', 'pass') : `${plural(t.passed, 'test')} passed`) : short ? 'tests pass' : 'tests passed';
  if (t.failed) return short ? n(t.failed, 'fails', 'fail') : `${plural(t.failed, 'test')} failed`;
  return short ? 'tests fail' : 'tests failed';
}

/** A turn that didn't end well: an error, or a result that says so. */
export const turnStopped = (f: TurnFacts) => !!f.error || !f.ok;

/** For the glance row, a phrase: "3 files, tests pass" ('' when there's nothing to say). */
export function shortFacts(f: TurnFacts): string {
  const parts: string[] = [];
  if (f.files.length) parts.push(plural(f.files.length, 'file'));
  if (f.tests) parts.push(testWords(f.tests, true));
  else if (f.build) parts.push(f.build.ok ? 'build passes' : 'build fails');
  return parts.join(', ');
}

/** A finished turn's row: "finished: 3 files, tests pass", "stopped: 1 file", "finished". */
export function doneLine(f: TurnFacts): string {
  const facts = shortFacts(f);
  return `${turnStopped(f) ? 'stopped' : 'finished'}${facts ? `: ${facts}` : ''}`;
}

/** For the result card, each a phrase: "3 files changed", "48 tests passed", "2 agents", "took 4m 12s". */
export function cardFacts(f: TurnFacts): string[] {
  const parts: string[] = [];
  if (f.files.length) parts.push(`${plural(f.files.length, 'file')} changed`);
  if (f.tests) parts.push(testWords(f.tests, false));
  if (f.build) parts.push(f.build.ok ? 'build passed' : 'build failed');
  if (f.workflows) parts.push(plural(f.workflows, 'workflow'));
  else if (f.agents) parts.push(plural(f.agents, 'agent'));
  if (f.durationMs) parts.push(`took ${duration(f.durationMs)}`);
  return parts;
}

/** Spoken facts, after the reply's first sentence. */
export function spokenFacts(f: TurnFacts): string {
  const parts: string[] = [];
  if (f.files.length) parts.push(`${plural(f.files.length, 'file')} changed`);
  if (f.tests) parts.push(f.tests.ok ? (f.tests.passed ? `${plural(f.tests.passed, 'test')} passed` : 'tests passed') : f.tests.failed ? `${plural(f.tests.failed, 'test')} failed` : 'tests failed');
  if (f.build && !f.build.ok) parts.push('the build failed');
  return parts.length ? `${parts.join(', ')}.` : '';
}

/** The reply's first sentence, as plain text. */
export function firstSentence(reply: string, max = 180): string {
  const text = plainText(reply).replace(/\s+/g, ' ').trim();
  if (!text) return '';
  const m = /^(.+?[.!?])(?=\s+["“(\[]?[A-Z0-9]|\s*$)/.exec(text);
  let s = (m ? m[1] : text).trim();
  if (s.length > max) s = s.slice(0, max - 1).replace(/\s+\S*$/, '') + '…';
  return s;
}
