// Verifies a signed request: the server's Ed25519 signature over the method, the path, the
// body's SHA-256, a timestamp and a nonce. The timestamp bounds the replay window and the nonce
// closes it: within the window each nonce is accepted once.

import { HEADER_NONCE, HEADER_SIGNATURE, HEADER_TIMESTAMP, REPLAY_WINDOW_SECONDS, buildSigningString, fromBase64, sha256Hex } from "../client.ts";
import { readCapped } from "./body.ts";
import { ApiError } from "./errors.ts";

export type Verified = {
  name: string;
  body: Uint8Array;
  /** When the nonce may be forgotten: strictly later than the last moment its timestamp is accepted. */
  nonceExpiresAt: number;
  nonce: string;
};

export async function verifySignedRequest(req: Request, name: string, publicKeyB64: string, now: number): Promise<Verified> {
  const tsHeader = req.headers.get(HEADER_TIMESTAMP);
  const nonce = req.headers.get(HEADER_NONCE);
  const sigHeader = req.headers.get(HEADER_SIGNATURE);
  if (!tsHeader || !nonce || !sigHeader) throw new ApiError(401, "unsigned", "Missing signature headers.");
  if (!/^\d{1,12}$/.test(tsHeader)) throw new ApiError(401, "bad_timestamp", "The timestamp must be unix seconds.");
  if (!/^[0-9a-f]{32}$/.test(nonce)) throw new ApiError(401, "bad_nonce", "The nonce must be 16 random bytes in hex.");
  const ts = Number(tsHeader);
  if (Math.abs(now - ts) > REPLAY_WINDOW_SECONDS) throw new ApiError(401, "expired", "The timestamp is outside the 5 minute window; check the server's clock.");

  const body = await readCapped(req);
  const message = buildSigningString(req.method, new URL(req.url).pathname, await sha256Hex(body), ts, nonce);

  let ok = false;
  try {
    const key = await importPublicKey(publicKeyB64);
    ok = await crypto.subtle.verify({ name: "Ed25519" }, key, fromBase64(sigHeader) as BufferSource, new TextEncoder().encode(message));
  } catch {
    ok = false;
  }
  if (!ok) throw new ApiError(401, "bad_signature", "The signature does not match this name's key.");

  // The request is accepted while now <= ts + window, and the hourly prune drops rows with
  // expires_at <= now, so keeping the nonce until one second past that means a replay is never
  // possible at a moment when the timestamp would still pass.
  //
  // Claiming it (a write) is left to the caller, after the cheaper checks, so that a request
  // that will be refused anyway writes nothing.
  return { name, body, nonce, nonceExpiresAt: ts + REPLAY_WINDOW_SECONDS + 1 };
}

export async function importPublicKey(b64: string): Promise<CryptoKey> {
  const raw = fromBase64(b64);
  if (raw.length !== 32) throw new Error("not a raw Ed25519 public key");
  return crypto.subtle.importKey("raw", raw as BufferSource, { name: "Ed25519" }, false, ["verify"]);
}

/** True when the text is a base64 raw Ed25519 public key that WebCrypto accepts. */
export async function isValidPublicKey(b64: string): Promise<boolean> {
  if (typeof b64 !== "string" || !/^[A-Za-z0-9+/]{43}=$/.test(b64)) return false;
  try {
    await importPublicKey(b64);
    return true;
  } catch {
    return false;
  }
}
