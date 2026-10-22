// New sessions from the glasses are born in Nebula (~/Programming/Nebula on the Mac by default, the
// folder where new stars are born), each in a subfolder of its own named after its first prompt:
// "build a landing page for my bakery" starts in Nebula/landing-page-bakery. The Mac makes the name
// unique (landing-page-bakery-2) and runs `git init` there; this is only the name the lens asks for
// and shows before the tap that sends it. Pure.

/** Words that say nothing about the project: politeness, pronouns, glue, and the verbs every prompt starts with. */
const FILLER = new Set([
  'a', 'an', 'the', 'please', 'pls', 'hey', 'hi', 'ok', 'okay', 'so', 'now', 'just', 'then', 'also', 'and', 'or', 'but',
  'can', 'could', 'would', 'will', 'should', 'shall', 'may', 'might', 'must', 'you', 'your', 'i', 'im', 'me', 'my', 'mine', 'we', 'us', 'our',
  "let's", 'lets', 'let', 'want', 'wanna', 'need', 'like', 'id', "i'd", "i'm", 'to', 'for', 'of', 'in', 'on', 'at', 'with', 'from', 'into', 'about',
  'that', 'this', 'these', 'those', 'it', 'its', 'some', 'something', 'new', 'is', 'are', 'be', 'do', 'does', 'make', 'build', 'create', 'write',
  'start', 'help', 'set', 'up', 'there', 'here', 'which', 'what', 'how', 'very', 'really', 'little', 'quick', 'simple',
]);

/** Russian letters as Latin ones, so a prompt said in Russian still names its folder in plain ASCII. */
const CYRILLIC: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: 'z', и: 'i', й: 'y', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p',
  р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'h', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'sch', ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
};

/** At most this many words and characters: a folder name, not a sentence. */
const MAX_WORDS = 4, MAX_CHARS = 32;

/**
 * A short kebab-case folder name from a prompt: "Build me a landing page for my bakery, with a menu"
 * → "landing-page-bakery-menu". Accents dropped, Russian transliterated, filler words skipped, cut
 * between words. Never empty: a prompt of filler alone is a "session".
 */
export function folderName(prompt: string): string {
  const latin = prompt.toLowerCase().replace(/[а-яё]/g, ch => CYRILLIC[ch] ?? '').normalize('NFKD').replace(/[̀-ͯ]/g, '');
  const words = latin.replace(/[’']/g, "'").split(/[^a-z0-9']+/).map(w => w.replace(/^'+|'+$/g, '')).filter(Boolean);
  const kept = words.filter(w => !FILLER.has(w)).map(w => w.replace(/'/g, ''));
  let out = '';
  for (const w of kept.slice(0, MAX_WORDS)) {
    const next = out ? `${out}-${w}` : w;
    if (next.length > MAX_CHARS) { if (!out) out = w.slice(0, MAX_CHARS); break; }
    out = next;
  }
  return out || 'session';
}

/** The name `name` gets next to the folders already there: "landing-page", then "landing-page-2". */
export function uniqueName(name: string, taken: Iterable<string>): string {
  const have = new Set([...taken].map(t => t.toLowerCase()));
  if (!have.has(name.toLowerCase())) return name;
  for (let i = 2; ; i++) if (!have.has(`${name}-${i}`.toLowerCase())) return `${name}-${i}`;
}

/** The Mac's default Nebula, as the phone shows it before the Mac has said. */
export const DEFAULT_NEBULA = '~/Programming/Nebula';
