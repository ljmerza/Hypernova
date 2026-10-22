// A session at work, as the lens shows it: what runs now (its main agent's tool, then one line per
// agent at work, newest first) and this turn's output (Claude's words, each agent's report as it
// lands, one short line for each run of tools), which the lens scrolls like a teleprompter. Pure.
import type { Agent, HnEvent, Session } from './types.ts';
import { activityLabel } from './format.ts';
import { agentsLine } from './glance.ts';
import { clean, GAP, nativeFit, nativeWidth, spaces } from './font.ts';
import { lastTurn } from './result.ts';
import { headLines, plainText, wrapAll } from './text.ts';

type ToolEvent = Extract<HnEvent, { kind: 'tool' }>;

/** In front of the main agent's line and of a run of tools; of an agent's line and its report; of a prompt. */
export const MAIN_MARK = '› ', AGENT_MARK = '• ', PROMPT_MARK = '» ';
/** An agent's name takes at most this much of its line, so what it does still shows. */
const LABEL_W = 210;

/** Agents at work, newest first. A workflow step's earlier attempt doesn't count. */
export function runningAgents(agents: Agent[] | null | undefined): Agent[] {
  return (agents ?? []).filter(a => a.status === 'running' && !a.superseded).sort((a, b) => b.startedAt - a.startedAt);
}

/** "• survey:parser   Read: Solar.kt": the agent and what it runs, apart (cleaned first: the gap stays). */
export function agentNow(a: Agent): string {
  return `${AGENT_MARK}${nativeFit(clean(a.label) || 'agent', LABEL_W)}${spaces(GAP)}${clean(activityLabel(a.activity)) || 'thinking'}`;
}

/**
 * The top box: the main agent's tool while it works ("› Bash: ./gradlew build"), then each agent at
 * work. `more`: lines that didn't fit (the page says "+2 more"). Without the agents' list (not
 * loaded yet, or a Mac from before agents) the session's own count stands in: "• 2 agents running".
 */
export function nowLines(s: Session, agents: Agent[] | null, rows: number): { lines: string[]; more: number } {
  const out: string[] = [];
  const running = runningAgents(agents);
  const counted = s.agentsRunning ?? 0;
  // Claude's own line while it runs a tool, or thinks with no agents to show instead.
  const tool = clean(activityLabel(s.activity));
  if ((s.state === 'working' || s.state === 'starting') && (tool || !(running.length || counted))) out.push(`${MAIN_MARK}${tool || (s.state === 'starting' ? 'starting' : 'thinking')}`);
  if (running.length) out.push(...running.map(agentNow));
  // A workflow between its phases counts as one at work with none of its agents running.
  else if (counted) out.push(`${AGENT_MARK}${agentsLine(counted)}`);
  return out.length <= rows ? { lines: out, more: 0 } : { lines: out.slice(0, rows), more: out.length - rows };
}

/** A run of tools as one line, the newest last, each apart: "› +3   Edit: Comet.kt   Bash: ./gradlew build". */
export function toolsLine(tools: ToolEvent[], width: number): string {
  const labels = tools.map(t => clean(activityLabel(`${t.name}: ${t.summary ?? ''}`)) || t.name);
  const gap = spaces(GAP);
  let line = '', shown = 0;
  for (let i = labels.length - 1; i >= 0; i--) {
    const next = shown ? `${labels[i]}${gap}${line}` : labels[i];
    if (shown && nativeWidth(`${MAIN_MARK}${i ? `+${i}${gap}` : ''}${next}`) > width) break;
    line = next;
    shown++;
  }
  const hidden = labels.length - shown;
  return nativeFit(`${MAIN_MARK}${hidden ? `+${hidden}${gap}` : ''}${line}`, width);
}

/**
 * The bottom box, item by item (each item's lines are `width` px): this turn from its prompt on.
 * What Claude writes, a line for each run of tools between its words, and the report of every agent
 * that ended during the turn, where it landed (a run of tools it lands in is cut there). Thinking,
 * approvals and the turn's end aren't here: the top box, the needs card and the result card say
 * those.
 */
/**
 * How much of a piece of text shows now, when text arrives like speech (src/lens/transmit.ts): `key`
 * names it ("e:<event id>", "a:<agent id>"), `at` is when it came, `md` what it was written as.
 */
export type Reveal = (key: string, text: string, at: number, md: string) => string;

export function feedItems(events: HnEvent[], agents: Agent[] | null, width: number, reveal?: Reveal): string[][] {
  const turn = lastTurn(events);
  if (!turn.length) return [];
  const since = turn[0].ts;
  const reports = (agents ?? [])
    .filter(a => a.status !== 'running' && !a.superseded && !!a.result?.trim() && (a.endedAt ?? 0) >= since)
    .sort((a, b) => a.endedAt! - b.endedAt!);
  const items: string[][] = [];
  let tools: ToolEvent[] = [];
  const add = (lines: string[]) => { if (lines.length) items.push(lines); };
  const flush = () => { if (tools.length) add([toolsLine(tools, width)]); tools = []; };
  // Reports that landed before `ts`, in the order they did.
  const landed = (ts: number) => {
    while (reports.length && reports[0].endedAt! <= ts) {
      const a = reports.shift()!;
      flush();
      // The agent's name at once; its report's words as they arrive.
      const report = plainText(a.result!).replace(/\n+/g, ' ');
      add(wrapAll(`${AGENT_MARK}${clean(a.label) || 'agent'} — ${reveal ? reveal(`a:${a.id}`, report, a.endedAt!, a.result!) : report}`, width));
    }
  };
  for (const e of turn) {
    landed(e.ts);
    if (e.kind === 'tool') { tools.push(e); continue; }
    if (e.kind === 'user') { flush(); add(headLines(PROMPT_MARK + clean(e.text), width, 2)); continue; }
    if (e.kind !== 'assistant') continue;
    const plain = plainText(e.text);
    const text = reveal ? reveal(`e:${e.id}`, plain, e.ts, e.text) : plain;
    if (!text.trim()) continue;
    flush();
    add(text.split('\n').flatMap(p => wrapAll(p, width)));
  }
  landed(Infinity);
  flush();
  return items;
}

/** The bottom box's lines: feedItems, one after another. */
export function feedLines(events: HnEvent[], agents: Agent[] | null, width: number): string[] {
  return feedItems(events, agents, width).flat();
}
