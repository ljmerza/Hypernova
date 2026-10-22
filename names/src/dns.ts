// The Cloudflare DNS API, reduced to the four calls the service needs. Every record it creates
// carries a comment tag ("hypernova-names:<name>") so the owner can tell them apart in the
// dashboard, and the sweep in `scheduled` only ever touches records that carry the tag. Updates
// and deletes go by record id, and the ids come from our own database, so the Worker never edits
// a record it did not create.

export const TAG_PREFIX = "hypernova-names:";

export type DnsRecordType = "A" | "AAAA" | "TXT";

export type DnsRecord = {
  id: string;
  type: DnsRecordType;
  name: string;
  content: string;
  comment: string | null;
  /** ISO timestamp from Cloudflare, used to leave freshly made records alone during the sweep. */
  createdOn: string;
};

export class CloudflareApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type CfEnvelope<T> = { success: boolean; result: T; errors?: { code: number; message: string }[] };

export class DnsClient {
  private base: string;
  private fetchImpl: typeof fetch;

  constructor(
    private zoneId: string,
    private token: string,
    fetchImpl: typeof fetch,
    apiBase = "https://api.cloudflare.com/client/v4",
  ) {
    this.base = `${apiBase}/zones/${zoneId}/dns_records`;
    this.fetchImpl = fetchImpl;
  }

  /** Creates a DNS-only record with a 60 s TTL and our tag. Returns Cloudflare's record id. */
  async create(type: DnsRecordType, name: string, content: string, ownerName: string): Promise<DnsRecord> {
    const res = await this.call("POST", "", {
      type,
      name,
      content,
      ttl: 60,
      proxied: false,
      comment: TAG_PREFIX + ownerName,
    });
    return toRecord(res);
  }

  /** Replaces the content of a record we created earlier. Throws a 404 error when it is gone. */
  async update(id: string, type: DnsRecordType, name: string, content: string, ownerName: string): Promise<DnsRecord> {
    const res = await this.call("PATCH", `/${id}`, {
      type,
      name,
      content,
      ttl: 60,
      proxied: false,
      comment: TAG_PREFIX + ownerName,
    });
    return toRecord(res);
  }

  /** Deletes by id. A record that is already gone counts as deleted. */
  async delete(id: string): Promise<void> {
    try {
      await this.call("DELETE", `/${id}`);
    } catch (e) {
      if (e instanceof CloudflareApiError && e.status === 404) return;
      throw e;
    }
  }

  /** Every record in the zone that carries our tag, across all pages. */
  async listTagged(): Promise<DnsRecord[]> {
    const out: DnsRecord[] = [];
    for (let page = 1; page <= 50; page++) {
      const q = new URLSearchParams({ "comment.startswith": TAG_PREFIX, per_page: "100", page: String(page) });
      const res = (await this.call("GET", `?${q}`)) as RawRecord[];
      for (const r of res) out.push(toRecord(r));
      if (res.length < 100) break;
    }
    return out;
  }

  private async call(method: string, suffix: string, body?: unknown): Promise<unknown> {
    const res = await this.fetchImpl(this.base + suffix, {
      method,
      headers: {
        authorization: `Bearer ${this.token}`,
        "content-type": "application/json",
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    let json: CfEnvelope<unknown> | null = null;
    try {
      json = (await res.json()) as CfEnvelope<unknown>;
    } catch {
      json = null;
    }
    if (!res.ok || !json || !json.success) {
      const detail = json?.errors?.map((e) => `${e.code} ${e.message}`).join("; ") ?? `HTTP ${res.status}`;
      throw new CloudflareApiError(res.status, `Cloudflare DNS API ${method} ${suffix || "/"}: ${detail}`);
    }
    return json.result;
  }
}

type RawRecord = { id: string; type: string; name: string; content: string; comment?: string | null; created_on?: string };

function toRecord(raw: unknown): DnsRecord {
  const r = raw as RawRecord;
  return {
    id: r.id,
    type: r.type as DnsRecordType,
    name: r.name,
    content: r.content,
    comment: r.comment ?? null,
    createdOn: r.created_on ?? "",
  };
}

/** The name a tagged record belongs to, or null when the comment is not one of ours. */
export function ownerOf(record: DnsRecord): string | null {
  if (!record.comment || !record.comment.startsWith(TAG_PREFIX)) return null;
  const name = record.comment.slice(TAG_PREFIX.length);
  return /^[a-z0-9]{10}$/.test(name) ? name : null;
}
