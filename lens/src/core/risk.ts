// Commands that can't be taken back: a force push, rm -rf, a deploy or a publish, dropping data, a
// hard reset. On the glasses one of these is approved only by saying its verb ("approve push") once
// its command has been read out, and while moving a tap alone never approves it (DECISIONS, "Glasses
// for cycling and walking"). The daemon has no such check, so the lens reads the command itself:
// shell commands word by word (`sudo`, `env`, `xargs` and variables skipped), SQL by its keywords,
// other tools by their names. A miss is a card that asks as usual; a false alarm costs one word.
// Pure.
import type { Approval } from './types.ts';

export type RiskVerb = 'push' | 'delete' | 'deploy' | 'publish' | 'drop' | 'reset';

export interface Risk {
  /** The word to say after "approve": "approve push". */
  verb: RiskVerb;
  /** What it does, in a few words for the card and the voice: "force push", "rm -rf", "deploy". */
  what: string;
}

/**
 * The words that count as each verb: the one on the card, and what people say instead ("approve
 * remove" for rm, "approve release" for a deploy). Never another kind's: "approve push" on an rm -rf
 * card is refused, so a word meant for one card can't approve another.
 */
const SAID: Record<RiskVerb, string[]> = {
  push: ['push', 'force push', 'push it'],
  delete: ['delete', 'remove', 'rm', 'erase', 'delete it', 'remove it'],
  deploy: ['deploy', 'ship', 'release', 'deploy it', 'ship it'],
  publish: ['publish', 'release', 'publish it'],
  drop: ['drop', 'wipe', 'drop it', 'wipe it'],
  reset: ['reset', 'discard', 'reset it'],
};

/** Does `said` (what came after "approve", as recognised words) name this risk's verb? */
export function verbMatches(risk: Risk, said: string): boolean {
  const w = said.toLowerCase().replace(/[^\p{L}\p{N} ]+/gu, ' ').replace(/\s+/g, ' ').trim();
  return SAID[risk.verb].includes(w);
}

/** Every verb a risky approval may be approved with, for the voice parser. */
export const RISK_WORDS: string[] = [...new Set(Object.values(SAID).flat())];

/** The risk in an approval: a shell command's, or a tool's that deploys or deletes by its name. */
export function approvalRisk(a: Approval | null | undefined): Risk | null {
  if (!a || a.kind !== 'tool') return null;
  if (a.toolName === 'Bash') return commandRisk(String((a.input as { command?: unknown })?.command ?? ''));
  return toolRisk(a.toolName);
}

/** Tools from MCP servers and plugins that say what they do in their names (mcp__vercel__deploy_project). */
function toolRisk(name: string): Risk | null {
  if (!/^mcp__|__/.test(name)) return null;
  const n = name.toLowerCase().split('__').slice(-1)[0];
  if (/(^|_)(deploy|promote|rollback)(_|$)/.test(n)) return { verb: 'deploy', what: 'deploy' };
  if (/(^|_)(publish|release)(_|$)/.test(n)) return { verb: 'publish', what: 'publish' };
  if (/(^|_)(drop|truncate|purge|wipe|reset)(_|$)/.test(n)) return { verb: 'drop', what: 'drop data' };
  if (/(^|_)(delete|destroy|remove)(_|$)/.test(n)) return { verb: 'delete', what: 'delete' };
  return null;
}

/** The first risky part of a shell command line, or null. */
export function commandRisk(command: string): Risk | null {
  const cmd = command.replace(/\\\n/g, ' ');
  // SQL anywhere in the line (psql -c "…", sqlite3 db "…", a heredoc): dropping or emptying tables.
  if (/\b(drop\s+(table|database|schema|index|view|collection)|truncate\s+(table\s+)?[\w."`]+)\b/i.test(cmd)) return { verb: 'drop', what: 'drop data' };
  if (/\bdelete\s+from\s+[\w."`]+\s*(;|$|["'])/im.test(cmd)) return { verb: 'drop', what: 'delete every row' };
  if (/\b(flushall|flushdb|dropDatabase\s*\()/i.test(cmd)) return { verb: 'drop', what: 'drop data' };
  for (const seg of segments(cmd)) {
    const r = segmentRisk(seg);
    if (r) return r;
  }
  return null;
}

/** The simple commands in a line: split at ; && || | and newlines, each one's words without the wrappers. */
function segments(cmd: string): string[][] {
  return cmd.split(/&&|\|\||[;|\n]|\$\(|`/).map(s => {
    let words = s.trim().split(/\s+/).filter(Boolean).map(w => w.replace(/^["'(]+|["')]+$/g, ''));
    // What only runs the real command: sudo, env, xargs, time, nohup, npx and its kin, and VAR=value in front.
    for (;;) {
      const w = words[0];
      if (!w) break;
      if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(w)) { words = words.slice(1); continue; }
      if (/^(sudo|env|xargs|time|nohup|command|exec|caffeinate|npx|bunx|pnpx)$/.test(w)) { words = words.slice(1); while (words[0]?.startsWith('-')) words = words.slice(1); continue; }
      if ((w === 'pnpm' || w === 'yarn') && (words[1] === 'exec' || words[1] === 'dlx')) { words = words.slice(2); continue; }
      break;
    }
    return words;
  }).filter(w => w.length);
}

/** Short flags as letters, long ones as words: `-rf --force` → {r, f, force}. */
function flags(words: string[]): Set<string> {
  const out = new Set<string>();
  for (const w of words) {
    if (w === '--') break;
    if (w.startsWith('--')) out.add(w.slice(2).split('=')[0]);
    else if (/^-[A-Za-z]+$/.test(w)) for (const ch of w.slice(1)) out.add(ch);
  }
  return out;
}

const DEPLOY_SCRIPT = /^(deploy|release|publish|ship)([:-]\w+)?$/;

function segmentRisk(words: string[]): Risk | null {
  const [cmd0, ...args] = words;
  const cmd = cmd0.split('/').pop()!.toLowerCase();
  const f = flags(args);
  const a1 = args.find(x => !x.startsWith('-'))?.toLowerCase() ?? '';
  const has = (...xs: string[]) => args.some(x => xs.includes(x.toLowerCase()));
  switch (cmd) {
    case 'rm':
      return (f.has('r') || f.has('R') || f.has('recursive')) && (f.has('f') || f.has('force')) ? { verb: 'delete', what: 'rm -rf' } : null;
    case 'find':
      return has('-delete') || (args.includes('-exec') && args.some(x => x === 'rm')) ? { verb: 'delete', what: 'delete files' } : null;
    case 'mkfs': case 'shred':
      return { verb: 'delete', what: 'erase' };
    case 'dd':
      return args.some(x => /^of=\/dev\//.test(x)) ? { verb: 'delete', what: 'overwrite a disk' } : null;
    case 'diskutil':
      return /^(erase|zerodisk|secureerase|partitiondisk)/i.test(a1) ? { verb: 'delete', what: 'erase a disk' } : null;
    case 'dropdb':
      return { verb: 'drop', what: 'drop a database' };
    case 'git': return gitRisk(args);
    case 'npm': case 'pnpm': case 'yarn': case 'bun': {
      if (a1 === 'publish' || (a1 === 'npm' && has('publish'))) return { verb: 'publish', what: `${cmd} publish` };
      const script = a1 === 'run' || a1 === 'run-script' ? args.filter(x => !x.startsWith('-'))[1]?.toLowerCase() ?? '' : a1;
      return DEPLOY_SCRIPT.test(script) ? { verb: 'deploy', what: `${cmd} ${script}` } : null;
    }
    case 'make':
      return DEPLOY_SCRIPT.test(a1) ? { verb: 'deploy', what: `make ${a1}` } : null;
    case 'cargo': case 'twine': case 'vsce': case 'ovsx':
      return a1 === 'publish' || a1 === 'upload' ? { verb: 'publish', what: `${cmd} ${a1}` } : null;
    case 'gem':
      return a1 === 'push' ? { verb: 'publish', what: 'gem push' } : null;
    case 'pod':
      return a1 === 'trunk' && has('push') ? { verb: 'publish', what: 'pod trunk push' } : null;
    case 'mvn': case 'gradle': case 'gradlew':
      return args.some(x => /^(deploy|publish\w*)$/.test(x)) ? { verb: 'publish', what: `${cmd} publish` } : null;
    case 'docker':
      return a1 === 'push' || (a1 === 'image' && has('push')) ? { verb: 'publish', what: 'docker push' } : null;
    case 'gh':
      return a1 === 'release' && has('create', 'upload', 'delete') ? { verb: 'publish', what: 'gh release' } : a1 === 'repo' && has('delete') ? { verb: 'delete', what: 'delete a repository' } : null;
    case 'vercel':
      // A preview deploy changes nothing anyone uses; production does.
      return f.has('prod') || f.has('production') || a1 === 'promote' || a1 === 'rollback' ? { verb: 'deploy', what: 'deploy to production' } : a1 === 'remove' || a1 === 'rm' ? { verb: 'delete', what: 'vercel remove' } : null;
    case 'netlify': case 'fly': case 'flyctl': case 'wrangler': case 'firebase': case 'serverless': case 'sls': case 'cdk': case 'eb': case 'amplify': case 'railway': case 'render':
      if (/^(deploy|publish|up|release)$/.test(a1)) return { verb: 'deploy', what: `${cmd} ${a1}` };
      return /^(destroy|delete|remove)$/.test(a1) ? { verb: 'delete', what: `${cmd} ${a1}` } : null;
    case 'heroku':
      return /^(releases:rollback|ps:scale|apps:destroy|pg:reset)/.test(a1) ? { verb: 'deploy', what: `heroku ${a1}` } : null;
    case 'kubectl':
      return /^(apply|delete|replace|rollout|scale|set|patch|drain)$/.test(a1) ? { verb: 'deploy', what: `kubectl ${a1}` } : null;
    case 'helm':
      return /^(install|upgrade|uninstall|delete|rollback)$/.test(a1) ? { verb: 'deploy', what: `helm ${a1}` } : null;
    case 'terraform': case 'tofu': case 'pulumi':
      return /^(apply|destroy|up|import)$/.test(a1) ? { verb: 'deploy', what: `${cmd} ${a1}` } : null;
    case 'gcloud': case 'aws': case 'az': {
      if (args.some(x => /^deploy$/.test(x))) return { verb: 'deploy', what: `${cmd} deploy` };
      const gone = args.find(x => /^(delete|destroy|rm|rb|terminate-instances|delete-stack)$/.test(x));
      if (gone) return { verb: 'delete', what: `${cmd} ${gone}` };
      return cmd === 'aws' && a1 === 's3' && f.has('delete') ? { verb: 'delete', what: 'aws s3 sync --delete' } : null;
    }
    case 'eas':
      return a1 === 'submit' || (a1 === 'update' && !f.has('help')) ? { verb: 'publish', what: `eas ${a1}` } : null;
    case 'fastlane':
      return /^(release|deploy|deliver|beta|upload|supply|pilot)/.test(a1) ? { verb: 'publish', what: `fastlane ${a1}` } : null;
    case 'redis-cli':
      return has('flushall', 'flushdb') ? { verb: 'drop', what: 'flush redis' } : null;
    case 'prisma':
      return (a1 === 'migrate' && has('reset')) || f.has('force-reset') || f.has('accept-data-loss') ? { verb: 'drop', what: 'reset the database' } : null;
    case 'supabase':
      return a1 === 'db' && has('reset') ? { verb: 'drop', what: 'reset the database' } : null;
    case 'rails': case 'rake':
      return args.some(x => /^db:(drop|reset|schema:load|purge)/.test(x)) ? { verb: 'drop', what: 'reset the database' } : null;
    case 'python': case 'python3':
      return args[0] === 'manage.py' && has('flush', 'reset_db') ? { verb: 'drop', what: 'flush the database' } : null;
  }
  // A deploy script of the project's own: ./deploy.sh, scripts/release.sh.
  if (/^(deploy|release|publish)([-_.]\w+)*(\.sh)?$/.test(cmd) && cmd0.includes('/')) return { verb: 'deploy', what: cmd0.split('/').pop()! };
  return null;
}

function gitRisk(args: string[]): Risk | null {
  // Global options come before the subcommand: git -C path push …
  let i = 0;
  while (i < args.length && args[i].startsWith('-')) i += args[i] === '-C' || args[i] === '-c' ? 2 : 1;
  const sub = args[i]?.toLowerCase() ?? '';
  const rest = args.slice(i + 1);
  const f = flags(rest);
  const plain = rest.filter(x => !x.startsWith('-'));
  switch (sub) {
    case 'push':
      // Rewrites what others have: --force, -f, --force-with-lease, a "+branch" refspec, --mirror.
      if (f.has('force') || f.has('f') || f.has('force-with-lease') || f.has('force-if-includes') || f.has('mirror') || plain.some(x => x.startsWith('+'))) return { verb: 'push', what: 'force push' };
      if (f.has('delete') || f.has('d') || plain.slice(1).some(x => x.startsWith(':') && x.length > 1)) return { verb: 'push', what: 'delete a remote branch' };
      return null;
    case 'reset':
      return f.has('hard') || f.has('merge') || f.has('keep') ? { verb: 'reset', what: 'git reset --hard' } : null;
    case 'clean':
      return f.has('f') || f.has('force') ? { verb: 'delete', what: 'git clean' } : null;
    case 'checkout': case 'restore':
      // Throwing away uncommitted work: `git checkout -- .`, `git restore .` (not --staged alone).
      if (sub === 'restore' && (f.has('staged') || f.has('S')) && !(f.has('worktree') || f.has('W'))) return null;
      return plain.some(x => x === '.' || x === ':/' || x === '*') && (sub === 'restore' || rest.includes('--') || plain.length === 1) ? { verb: 'reset', what: 'discard changes' } : null;
    case 'branch':
      return f.has('D') || (f.has('d') && f.has('f')) || (f.has('delete') && f.has('force')) ? { verb: 'delete', what: 'delete a branch' } : null;
    case 'stash':
      return plain[0] === 'clear' || plain[0] === 'drop' ? { verb: 'drop', what: `git stash ${plain[0]}` } : null;
    case 'filter-branch': case 'filter-repo':
      return { verb: 'push', what: 'rewrite history' };
  }
  return null;
}

/**
 * The card's line and the voice's sentence about it: "Careful: 0stems wants to force push: git push
 * --force origin main. Say “approve push” to allow it, or “deny”."
 */
export function riskSpoken(project: string, risk: Risk, detail: string): string {
  return `Careful. ${project} wants to ${risk.what}: ${detail.replace(/\s+/g, ' ').trim()}. To allow it, say approve ${risk.verb}. Or say deny.`;
}
