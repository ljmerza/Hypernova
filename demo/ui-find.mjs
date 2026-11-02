// For scripts/demo-shots.sh: reads a `uiautomator dump` from stdin and prints the centre ("x y") of
// the first thing on screen whose text or content description matches the regular expression in the
// arguments. Nothing printed and exit 1 when there's none. The second argument, "bounds", prints
// "left top right bottom" instead.
let xml = ''
for await (const chunk of process.stdin) xml += chunk
const want = new RegExp(process.argv[2])
for (const [node] of xml.matchAll(/<node\b[^>]*>/g)) {
  const text = /\btext="([^"]*)"/.exec(node)?.[1] ?? ''
  const desc = /\bcontent-desc="([^"]*)"/.exec(node)?.[1] ?? ''
  if (!want.test(text) && !want.test(desc)) continue
  const b = /\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(node)
  if (!b) continue
  const [l, t, r, u] = b.slice(1).map(Number)
  if (l === r || t === u) continue
  console.log(process.argv[3] === 'bounds' ? `${l} ${t} ${r} ${u}` : `${Math.round((l + r) / 2)} ${Math.round((t + u) / 2)}`)
  process.exit(0)
}
process.exit(1)
