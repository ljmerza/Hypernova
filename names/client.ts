// Reference client for the hypernova.observer name service, for the daemon to import.
//
// No dependencies: WebCrypto (Ed25519, SHA-256) and fetch, which Node 22+ and Workers both have,
// so this file also runs inside the Worker's tests. Node runs it directly with type stripping,
// so it uses only erasable TypeScript (no enums, no parameter properties).
//
// The Worker imports `buildSigningString` and friends from here, so the two sides can never
// drift apart on what exactly is signed.

export const PROTOCOL = "hypernova-names-v1";

/** Signed requests are rejected when their timestamp is further than this from the server's clock. */
export const REPLAY_WINDOW_SECONDS = 300;

export const HEADER_TIMESTAMP = "x-hn-timestamp";
export const HEADER_NONCE = "x-hn-nonce";
export const HEADER_SIGNATURE = "x-hn-signature";

export type Registration = {
  name: string;
  /** `<name>.<domain>`: the public address of the server. */
  fqdn: string;
  /** `l-<name>.<domain>`: the server's private addresses, for the same Wi-Fi. */
  lanFqdn: string;
  domain: string;
};

export type AddressState = {
  name: string;
  fqdn: string;
  /** Null when the call asked for no public record (`public: false`). */
  public: { type: "A" | "AAAA"; address: string } | null;
  lanFqdn: string;
  lan: string[];
};

export type AcmeHost = "main" | "lan";

export type AcmeState = {
  name: string;
  txt: { host: AcmeHost; value: string; expiresAt: number }[];
};

export type NameInfo = {
  name: string;
  fqdn: string;
  lanFqdn: string;
  createdAt: number;
  lastSeen: number;
  public: { type: "A" | "AAAA"; address: string } | null;
  lan: string[];
  txt: { host: AcmeHost; value: string; expiresAt: number }[];
};

export class NameServiceError extends Error {
  status: number;
  code: string;
  /** Seconds to wait before trying again, from a `429`'s `Retry-After`; null when the reply had none. */
  retryAfter: number | null;
  constructor(status: number, code: string, message: string, retryAfter: number | null = null) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

// ---------- the signing scheme, shared with the Worker ----------

/**
 * The exact bytes an Ed25519 signature covers: the protocol name, the method, the path (no query
 * string: the API has none), the SHA-256 of the body, the timestamp and the nonce, one per line.
 * The nonce is inside the signature so a replay cannot simply pick a fresh one.
 */
export function buildSigningString(method: string, path: string, bodySha256Hex: string, timestamp: number, nonce: string): string {
  return [PROTOCOL, method.toUpperCase(), path, bodySha256Hex, String(timestamp), nonce].join("\n");
}

export async function sha256Hex(data: Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  const digest = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
  return toHex(new Uint8Array(digest));
}

export function toHex(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s;
}

export function toBase64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromBase64(text: string): Uint8Array {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function randomNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return toHex(bytes);
}

// ---------- keys ----------

export type ServerKey = {
  /** The raw 32-byte Ed25519 public key, base64, as sent to `POST /v1/register`. */
  publicKey: string;
  privateKey: CryptoKey;
};

/**
 * A fresh Ed25519 key pair. The private key is extractable on purpose: the daemon has to store it
 * (as PKCS#8, see `exportPrivateKey`) and load it again after a restart, and a key that cannot
 * leave memory can only live until the process ends.
 */
export async function generateServerKey(): Promise<ServerKey> {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const raw = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  return { publicKey: toBase64(raw), privateKey: pair.privateKey };
}

/** The private key as PKCS#8 bytes, for storing next to the pairing keys. Keep the file private. */
export async function exportPrivateKey(key: ServerKey): Promise<Uint8Array> {
  return new Uint8Array((await crypto.subtle.exportKey("pkcs8", key.privateKey)) as ArrayBuffer);
}

/** Loads a key pair the daemon stored as PKCS#8 (private) plus its base64 raw public key. */
export async function importServerKey(pkcs8: Uint8Array, publicKey: string): Promise<ServerKey> {
  const privateKey = await crypto.subtle.importKey("pkcs8", pkcs8 as BufferSource, { name: "Ed25519" }, true, ["sign"]);
  return { publicKey, privateKey };
}

// ---------- the client ----------

export type ClientOptions = {
  /** Base URL of the service, e.g. `https://api.hypernova.observer`. */
  baseUrl: string;
  /** Replaces the global fetch, for tests and proxies. */
  fetch?: typeof fetch;
  /** Replaces the clock (unix seconds), for tests. */
  now?: () => number;
};

export class NameServiceClient {
  private baseUrl: string;
  private fetchImpl: typeof fetch;
  private now: () => number;

  constructor(options: ClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, "");
    this.fetchImpl = options.fetch ?? ((input, init) => fetch(input, init));
    this.now = options.now ?? (() => Math.floor(Date.now() / 1000));
  }

  /** Claims a random name for this public key. Anonymous, rate-limited per source address. */
  async register(publicKey: string): Promise<Registration> {
    const res = await this.fetchImpl(this.baseUrl + "/v1/register", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ publicKey }),
    });
    return (await this.parse(res)) as Registration;
  }

  /**
   * Points `<name>` at the caller's own public address and `l-<name>` at the given private ones.
   * With `{ public: false }`, `<name>` gets no record at all (a computer reached only on its own
   * network); a service from before that option ignores it and publishes the public record anyway.
   */
  async updateAddress(key: ServerKey, name: string, lan: string[] = [], options: { public?: boolean } = {}): Promise<AddressState> {
    const body = options.public === false ? { lan, public: false } : { lan };
    return (await this.signed(key, "PUT", `/v1/names/${name}/address`, body)) as AddressState;
  }

  /** Publishes a DNS-01 challenge value under `_acme-challenge.<name>` (or `.l-<name>` for `lan`). */
  async setAcme(key: ServerKey, name: string, value: string, host: AcmeHost = "main"): Promise<AcmeState> {
    return (await this.signed(key, "PUT", `/v1/names/${name}/acme`, { value, host })) as AcmeState;
  }

  /** Removes one challenge value, or every challenge of the name when `value` is omitted. */
  async clearAcme(key: ServerKey, name: string, value?: string, host?: AcmeHost): Promise<AcmeState> {
    const body: Record<string, string> = {};
    if (value !== undefined) body.value = value;
    if (host !== undefined) body.host = host;
    return (await this.signed(key, "DELETE", `/v1/names/${name}/acme`, body)) as AcmeState;
  }

  async info(key: ServerKey, name: string): Promise<NameInfo> {
    return (await this.signed(key, "GET", `/v1/names/${name}`)) as NameInfo;
  }

  /** Gives the name up: all its records disappear and it may be handed out again later. */
  async release(key: ServerKey, name: string): Promise<void> {
    await this.signed(key, "DELETE", `/v1/names/${name}`);
  }

  /** One signed call. Exposed so tests can send deliberately wrong requests through the same path. */
  async signed(key: ServerKey, method: string, path: string, body?: unknown): Promise<unknown> {
    const req = await signRequest(key, method, path, body, this.now());
    const res = await this.fetchImpl(this.baseUrl + path, req);
    return this.parse(res);
  }

  private async parse(res: Response): Promise<unknown> {
    const text = await res.text();
    let json: unknown = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    if (!res.ok) {
      const err = (json ?? {}) as { error?: string; message?: string };
      const wait = Number(res.headers.get("retry-after"));
      throw new NameServiceError(res.status, err.error ?? "http_" + res.status, err.message ?? text.slice(0, 200), Number.isFinite(wait) && wait >= 0 ? wait : null);
    }
    return json;
  }
}

/** Builds the headers and body of a signed request; `timestamp` is unix seconds. */
export async function signRequest(key: ServerKey, method: string, path: string, body: unknown, timestamp: number, nonce: string = randomNonce()): Promise<RequestInit> {
  const bodyText = body === undefined ? "" : JSON.stringify(body);
  const bodyBytes = new TextEncoder().encode(bodyText);
  const message = buildSigningString(method, path, await sha256Hex(bodyBytes), timestamp, nonce);
  const signature = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, key.privateKey, new TextEncoder().encode(message)));
  const headers: Record<string, string> = {
    [HEADER_TIMESTAMP]: String(timestamp),
    [HEADER_NONCE]: nonce,
    [HEADER_SIGNATURE]: toBase64(signature),
  };
  if (body !== undefined) headers["content-type"] = "application/json";
  return { method: method.toUpperCase(), headers, body: body === undefined ? undefined : bodyText };
}
