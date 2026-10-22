// IP address parsing and classification, written by hand because the Worker has no `node:net`
// without extra compatibility flags and the rules we need are small: "is this a valid address"
// and "is it private". Private means an address that only makes sense inside a user's own
// network, so publishing it under `l-<name>` can never point at someone else's server.

export type ParsedIp = { family: 4; bytes: number[]; text: string } | { family: 6; bytes: number[]; text: string };

/** Parses an IPv4 or IPv6 address. Returns null for anything that is not one address. */
export function parseIp(input: string): ParsedIp | null {
  const s = input.trim();
  if (s.length === 0 || s.length > 45) return null;
  if (s.includes(":")) return parseIpv6(s);
  return parseIpv4(s);
}

function parseIpv4(s: string): ParsedIp | null {
  // Only dotted decimal with four parts: "10.1" style shorthands are an invitation to confusion.
  const parts = s.split(".");
  if (parts.length !== 4) return null;
  const bytes: number[] = [];
  for (const p of parts) {
    if (!/^(0|[1-9][0-9]{0,2})$/.test(p)) return null;
    const n = Number(p);
    if (n > 255) return null;
    bytes.push(n);
  }
  return { family: 4, bytes, text: bytes.join(".") };
}

function parseIpv6(s: string): ParsedIp | null {
  // Zone ids ("fe80::1%eth0") are local to one machine and never belong in DNS.
  if (s.includes("%")) return null;
  let lower = s.toLowerCase();
  // An embedded IPv4 tail ("::ffff:192.168.0.1") is turned into its two hex groups first.
  const dot = lower.lastIndexOf(".");
  if (dot !== -1) {
    const colon = lower.lastIndexOf(":");
    const v4 = parseIpv4(lower.slice(colon + 1));
    if (!v4) return null;
    const [a, b, c, d] = v4.bytes;
    lower = lower.slice(0, colon + 1) + ((a << 8) | b).toString(16) + ":" + ((c << 8) | d).toString(16);
  }
  const halves = lower.split("::");
  if (halves.length > 2) return null;
  const groups = (part: string): number[] | null => {
    if (part === "") return [];
    const out: number[] = [];
    for (const g of part.split(":")) {
      if (!/^[0-9a-f]{1,4}$/.test(g)) return null;
      out.push(parseInt(g, 16));
    }
    return out;
  };
  const head = groups(halves[0]);
  const tail = halves.length === 2 ? groups(halves[1]) : [];
  if (!head || !tail) return null;
  let words: number[];
  if (halves.length === 2) {
    const missing = 8 - head.length - tail.length;
    if (missing < 1) return null;
    words = [...head, ...new Array(missing).fill(0), ...tail];
  } else {
    if (head.length !== 8) return null;
    words = head;
  }
  const bytes: number[] = [];
  for (const w of words) bytes.push(w >> 8, w & 0xff);
  // One spelling per address (lowercase, no leading zeros, no "::") so the same address given
  // twice is stored once and compared reliably against what we already published.
  const text = words.map((w) => w.toString(16)).join(":");
  return { family: 6, bytes, text };
}

/**
 * True for addresses that are only meaningful inside a private network: RFC 1918, carrier-grade
 * NAT (100.64/10, where Tailscale also lives), and IPv6 unique local addresses. Loopback and
 * link-local are not private in this sense: a phone could never reach them, so they are refused
 * along with everything public.
 */
export function isPrivateAddress(ip: ParsedIp): boolean {
  const b = ip.bytes;
  if (ip.family === 4) {
    if (b[0] === 10) return true;
    if (b[0] === 172 && b[1] >= 16 && b[1] <= 31) return true;
    if (b[0] === 192 && b[1] === 168) return true;
    if (b[0] === 100 && b[1] >= 64 && b[1] <= 127) return true;
    return false;
  }
  // fc00::/7 (unique local addresses).
  return (b[0] & 0xfe) === 0xfc;
}

/**
 * True for addresses that can be reached from the internet, which is the only kind we allow as a
 * caller's public address. Anything special-purpose (private, loopback, link-local, multicast,
 * documentation ranges, unspecified) is refused.
 */
export function isPublicAddress(ip: ParsedIp): boolean {
  const b = ip.bytes;
  if (isPrivateAddress(ip)) return false;
  if (ip.family === 4) {
    if (b[0] === 0 || b[0] === 127) return false;
    if (b[0] === 169 && b[1] === 254) return false;
    if (b[0] === 192 && b[1] === 0 && (b[2] === 0 || b[2] === 2)) return false;
    if (b[0] === 198 && (b[1] === 18 || b[1] === 19)) return false;
    if (b[0] === 198 && b[1] === 51 && b[2] === 100) return false;
    if (b[0] === 203 && b[1] === 0 && b[2] === 113) return false;
    if (b[0] >= 224) return false;
    return true;
  }
  if (b.slice(0, 15).every((x) => x === 0) && b[15] <= 1) return false; // :: and ::1
  if (b[0] === 0xff) return false; // multicast
  if (b[0] === 0xfe && (b[1] & 0xc0) === 0x80) return false; // fe80::/10 link-local
  if (b[0] === 0x20 && b[1] === 0x01 && b[2] === 0x0d && b[3] === 0xb8) return false; // 2001:db8::/32 documentation
  if (b.slice(0, 10).every((x) => x === 0) && b[10] === 0xff && b[11] === 0xff) return false; // ::ffff:0:0/96 mapped IPv4
  return true;
}

/** The DNS record type an address goes into. */
export function recordType(ip: ParsedIp): "A" | "AAAA" {
  return ip.family === 4 ? "A" : "AAAA";
}

/**
 * The counters a registration is charged to. A single IPv4 address is its own bucket. An IPv6
 * caller normally holds a whole /64 (one subnet, billions of addresses) inside a /48 (a site),
 * so it is charged to its /64 and also to its /48; counting exact addresses would make the limit
 * meaningless. IPv4-mapped IPv6 is treated as the IPv4 address it wraps. Values are hashed so the
 * database never holds an address. `hash` is passed in to keep this file free of crypto.
 */
export async function registrationBuckets(raw: string, hash: (text: string) => Promise<string>): Promise<{ caller: string; site: string | null }> {
  const ip = parseIp(raw);
  const short = async (text: string) => (await hash(text)).slice(0, 24);
  if (!ip) return { caller: await short("raw:" + raw), site: null };
  let b = ip.bytes;
  if (ip.family === 6 && b.slice(0, 10).every((x) => x === 0) && b[10] === 0xff && b[11] === 0xff) b = b.slice(12);
  if (b.length === 4) return { caller: await short("v4:" + b.join(".")), site: null };
  const hex = (n: number) => b.slice(0, n).map((x) => x.toString(16).padStart(2, "0")).join("");
  return { caller: await short("v6/64:" + hex(8)), site: await short("v6/48:" + hex(6)) };
}
