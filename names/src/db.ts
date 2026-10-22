// D1 queries, kept in one place so the API code reads as intent. D1 is strongly consistent, which
// is why it was chosen over KV: a replayed nonce or a rate limit must be caught every time, and
// KV's eventual consistency would let the second request through for up to a minute.

export type NameRow = { name: string; public_key: string; created_at: number; last_seen: number };

export type RecordKind = "public" | "lan" | "acme";

export type RecordRow = {
  id: number;
  name: string;
  kind: RecordKind;
  cf_id: string;
  rr_name: string;
  rr_type: "A" | "AAAA" | "TXT";
  content: string;
  created_at: number;
  expires_at: number | null;
};

export class Db {
  constructor(private d1: D1Database) {}

  async getName(name: string): Promise<NameRow | null> {
    return (await this.d1.prepare("SELECT * FROM names WHERE name = ?").bind(name).first<NameRow>()) ?? null;
  }

  /** Inserts a fresh name. Returns false when the name is already taken (the caller retries). */
  async insertName(name: string, publicKey: string, now: number): Promise<boolean> {
    try {
      await this.d1.prepare("INSERT INTO names (name, public_key, created_at, last_seen) VALUES (?, ?, ?, ?)").bind(name, publicKey, now, now).run();
      return true;
    } catch (e) {
      if (String(e).includes("UNIQUE")) return false;
      throw e;
    }
  }

  async touchName(name: string, now: number): Promise<void> {
    await this.d1.prepare("UPDATE names SET last_seen = ? WHERE name = ?").bind(now, name).run();
  }

  async deleteName(name: string): Promise<void> {
    // The records table cascades, but D1 only enforces foreign keys when asked, so be explicit.
    await this.d1.batch([
      this.d1.prepare("DELETE FROM records WHERE name = ?").bind(name),
      this.d1.prepare("DELETE FROM nonces WHERE name = ?").bind(name),
      this.d1.prepare("DELETE FROM names WHERE name = ?").bind(name),
    ]);
  }

  async namesNotSeenSince(cutoff: number, limit: number): Promise<string[]> {
    const res = await this.d1.prepare("SELECT name FROM names WHERE last_seen < ? ORDER BY last_seen LIMIT ?").bind(cutoff, limit).all<{ name: string }>();
    return res.results.map((r) => r.name);
  }

  async allNames(): Promise<Set<string>> {
    const res = await this.d1.prepare("SELECT name FROM names").all<{ name: string }>();
    return new Set(res.results.map((r) => r.name));
  }

  async records(name: string, kind?: RecordKind): Promise<RecordRow[]> {
    const stmt = kind
      ? this.d1.prepare("SELECT * FROM records WHERE name = ? AND kind = ? ORDER BY id").bind(name, kind)
      : this.d1.prepare("SELECT * FROM records WHERE name = ? ORDER BY id").bind(name);
    return (await stmt.all<RecordRow>()).results;
  }

  async insertRecord(row: Omit<RecordRow, "id">): Promise<void> {
    await this.d1
      .prepare("INSERT INTO records (name, kind, cf_id, rr_name, rr_type, content, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
      .bind(row.name, row.kind, row.cf_id, row.rr_name, row.rr_type, row.content, row.created_at, row.expires_at)
      .run();
  }

  async updateRecordContent(id: number, rrType: string, content: string): Promise<void> {
    await this.d1.prepare("UPDATE records SET rr_type = ?, content = ? WHERE id = ?").bind(rrType, content, id).run();
  }

  async refreshRecordExpiry(id: number, expiresAt: number): Promise<void> {
    await this.d1.prepare("UPDATE records SET expires_at = ? WHERE id = ?").bind(expiresAt, id).run();
  }

  async deleteRecord(id: number): Promise<void> {
    await this.d1.prepare("DELETE FROM records WHERE id = ?").bind(id).run();
  }

  async expiredRecords(now: number, limit: number): Promise<RecordRow[]> {
    const res = await this.d1.prepare("SELECT * FROM records WHERE expires_at IS NOT NULL AND expires_at <= ? LIMIT ?").bind(now, limit).all<RecordRow>();
    return res.results;
  }

  async allRecordCfIds(): Promise<Set<string>> {
    const res = await this.d1.prepare("SELECT cf_id FROM records").all<{ cf_id: string }>();
    return new Set(res.results.map((r) => r.cf_id));
  }

  /** Remembers a nonce. Returns false when it was already used (a replay). */
  async claimNonce(name: string, nonce: string, expiresAt: number): Promise<boolean> {
    try {
      await this.d1.prepare("INSERT INTO nonces (name, nonce, expires_at) VALUES (?, ?, ?)").bind(name, nonce, expiresAt).run();
      return true;
    } catch (e) {
      if (String(e).includes("UNIQUE")) return false;
      throw e;
    }
  }

  /** Current counts of several windows in one read (missing or expired rows count as 0). Reads are cheap; writes are the free tier's scarce thing. */
  async peek(keys: string[], now: number): Promise<number[]> {
    const marks = keys.map(() => "?").join(",");
    const res = await this.d1.prepare(`SELECT key, count FROM limits WHERE key IN (${marks}) AND expires_at > ?`).bind(...keys, now).all<{ key: string; count: number }>();
    const byKey = new Map(res.results.map((r) => [r.key, r.count]));
    return keys.map((k) => byKey.get(k) ?? 0);
  }

  /** Counts one more hit in a window and returns the new total; the row vanishes with the window. */
  async bump(key: string, expiresAt: number): Promise<number> {
    const row = await this.d1
      .prepare("INSERT INTO limits (key, count, expires_at) VALUES (?, 1, ?) ON CONFLICT(key) DO UPDATE SET count = count + 1 RETURNING count")
      .bind(key, expiresAt)
      .first<{ count: number }>();
    return row?.count ?? 1;
  }

  async prune(now: number): Promise<void> {
    await this.d1.batch([
      this.d1.prepare("DELETE FROM nonces WHERE expires_at <= ?").bind(now),
      this.d1.prepare("DELETE FROM limits WHERE expires_at <= ?").bind(now),
    ]);
  }
}
