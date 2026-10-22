// A stand-in for the Cloudflare DNS records API: the four calls the Worker makes, with the same
// request shapes, envelope and errors, over an in-memory record list. It also checks the token
// and the zone, so a test would notice if the Worker ever called with the wrong ones.

export type FakeRecord = {
  id: string;
  type: string;
  name: string;
  content: string;
  ttl: number;
  proxied: boolean;
  comment: string | null;
  created_on: string;
};

export class FakeCloudflare {
  records = new Map<string, FakeRecord>();
  calls: { method: string; path: string }[] = [];
  /** Makes the next call fail with this status, to test error paths. */
  failNext: number | null = null;
  private nextId = 1;

  constructor(
    public now: () => number,
    private token = "test-token",
    private zone = "zone-test",
    private base = "https://cloudflare.invalid/client/v4",
  ) {}

  /** A record that is not ours (no tag): the Worker must never touch it. */
  seedForeign(type: string, name: string, content: string, comment: string | null = null): FakeRecord {
    return this.add(type, name, content, comment, this.now());
  }

  /** A tagged record the database does not know about, as left behind by a crash. */
  seedOrphan(ownerName: string, ageSeconds: number): FakeRecord {
    return this.add("A", `${ownerName}.hypernova.observer`, "203.0.113.9", `hypernova-names:${ownerName}`, this.now() - ageSeconds);
  }

  byName(name: string): FakeRecord[] {
    return [...this.records.values()].filter((r) => r.name === name);
  }

  private add(type: string, name: string, content: string, comment: string | null, createdAt: number): FakeRecord {
    const rec: FakeRecord = {
      id: `rec${String(this.nextId++).padStart(4, "0")}`,
      type,
      name,
      content,
      ttl: 60,
      proxied: false,
      comment,
      created_on: new Date(createdAt * 1000).toISOString(),
    };
    this.records.set(rec.id, rec);
    return rec;
  }

  fetch: typeof fetch = async (input, init) => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
    const method = (init?.method ?? "GET").toUpperCase();
    const headers = new Headers(init?.headers);
    this.calls.push({ method, path: url.pathname + url.search });

    if (this.failNext !== null) {
      const status = this.failNext;
      this.failNext = null;
      return envelope(status, null, [{ code: 9999, message: "injected failure" }]);
    }
    if (headers.get("authorization") !== `Bearer ${this.token}`) return envelope(403, null, [{ code: 9109, message: "Invalid access token" }]);
    const prefix = `${new URL(this.base).pathname}/zones/${this.zone}/dns_records`;
    if (!url.pathname.startsWith(prefix)) return envelope(404, null, [{ code: 7003, message: "Could not route to " + url.pathname }]);
    const rest = url.pathname.slice(prefix.length);
    const body = init?.body ? (JSON.parse(String(init.body)) as Partial<FakeRecord>) : null;

    if (rest === "" && method === "POST") {
      if (!body || !body.type || !body.name || !body.content) return envelope(400, null, [{ code: 1004, message: "DNS Validation Error" }]);
      const rec = this.add(body.type, body.name, body.content, body.comment ?? null, this.now());
      rec.ttl = body.ttl ?? 1;
      rec.proxied = body.proxied ?? false;
      return envelope(200, rec);
    }
    if (rest === "" && method === "GET") {
      let list = [...this.records.values()];
      const startsWith = url.searchParams.get("comment.startswith");
      if (startsWith) list = list.filter((r) => r.comment?.startsWith(startsWith));
      const per = Number(url.searchParams.get("per_page") ?? 100);
      const page = Number(url.searchParams.get("page") ?? 1);
      return envelope(200, list.slice((page - 1) * per, page * per));
    }
    const m = /^\/([^/]+)$/.exec(rest);
    if (m) {
      const rec = this.records.get(m[1]);
      if (!rec) return envelope(404, null, [{ code: 81044, message: "Record does not exist." }]);
      if (method === "PATCH") {
        Object.assign(rec, {
          type: body?.type ?? rec.type,
          name: body?.name ?? rec.name,
          content: body?.content ?? rec.content,
          ttl: body?.ttl ?? rec.ttl,
          proxied: body?.proxied ?? rec.proxied,
          comment: body?.comment === undefined ? rec.comment : body.comment,
        });
        return envelope(200, rec);
      }
      if (method === "DELETE") {
        this.records.delete(rec.id);
        return envelope(200, { id: rec.id });
      }
    }
    return envelope(405, null, [{ code: 7005, message: "Method not allowed" }]);
  };
}

function envelope(status: number, result: unknown, errors: { code: number; message: string }[] = []): Response {
  return new Response(JSON.stringify({ success: status < 400, result, errors, messages: [] }), {
    status,
    headers: { "content-type": "application/json" },
  });
}
