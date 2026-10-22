// A round trip against the deployed service, run by setup.sh at the end and usable any time:
//
//   node smoke.ts                      (against https://api.hypernova.observer)
//   node smoke.ts https://api.other.example
//
// It registers a throwaway key, publishes this machine's address plus one private address,
// publishes and removes a challenge record, checks each step through Cloudflare's public DNS
// over HTTPS (so the result does not depend on a local resolver's cache), and releases the name.
// Nothing is left behind. Node 22+ runs it directly.

import { NameServiceClient, generateServerKey } from "./client.ts";

const base = process.argv[2] ?? "https://api.hypernova.observer";
const client = new NameServiceClient({ baseUrl: base });

function step(text: string): void {
  process.stdout.write(`  ${text}\n`);
}

async function resolve(name: string, type: "A" | "AAAA" | "TXT"): Promise<string[]> {
  // Retried for up to a minute: a freshly created record takes a few seconds to appear on
  // Cloudflare's resolvers.
  for (let attempt = 0; attempt < 12; attempt++) {
    const res = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(name)}&type=${type}`, {
      headers: { accept: "application/dns-json" },
    });
    const json = (await res.json()) as { Answer?: { type: number; data: string }[] };
    const want = type === "A" ? 1 : type === "AAAA" ? 28 : 16;
    const got = (json.Answer ?? []).filter((a) => a.type === want).map((a) => a.data.replace(/^"|"$/g, ""));
    if (got.length > 0) return got;
    await new Promise((r) => setTimeout(r, 5000));
  }
  return [];
}

function expectIncludes(got: string[], value: string, what: string): void {
  if (!got.includes(value)) throw new Error(`${what}: expected ${value}, DNS has [${got.join(", ")}]`);
  step(`${what}: ${value} ✓`);
}

const key = await generateServerKey();
let name = "";
try {
  const reg = await client.register(key.publicKey);
  name = reg.name;
  step(`registered ${reg.fqdn}`);

  const addr = await client.updateAddress(key, name, ["192.168.77.7"]);
  if (!addr.public) throw new Error("no public record in the reply: " + JSON.stringify(addr));
  step(`public address as Cloudflare sees it: ${addr.public.address} (${addr.public.type})`);
  expectIncludes(await resolve(addr.fqdn, addr.public.type), addr.public.address, `${addr.fqdn} ${addr.public.type}`);
  expectIncludes(await resolve(addr.lanFqdn, "A"), "192.168.77.7", `${addr.lanFqdn} A`);

  const value = "smoke-" + Math.random().toString(36).slice(2, 12);
  await client.setAcme(key, name, value);
  expectIncludes(await resolve(`_acme-challenge.${addr.fqdn}`, "TXT"), value, `_acme-challenge.${addr.fqdn} TXT`);
  await client.clearAcme(key, name);
  step("challenge record removed");

  const info = await client.info(key, name);
  if (info.txt.length !== 0 || info.lan.length !== 1) throw new Error("info does not match: " + JSON.stringify(info));
  step("info matches");
} finally {
  if (name) {
    await client.release(key, name);
    step(`released ${name}`);
  }
}
process.stdout.write("Smoke test passed.\n");
