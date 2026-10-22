// Reading a request body without letting a caller make the Worker hold more than it should.
// Two layers: a declared Content-Length over the limit is refused before a single byte is read,
// and the read itself stops as soon as the bytes seen pass the limit, because a caller can lie
// about the length or send chunked with no length at all.

import { ApiError } from "./errors.ts";

export const MAX_BODY_BYTES = 4096;

const tooLarge = () => new ApiError(413, "too_large", "The body is too large.");

/** Refuses a request that declares a body over the limit. Cheap: looks at one header. */
export function checkDeclaredLength(req: Request, limit = MAX_BODY_BYTES): void {
  const declared = req.headers.get("content-length");
  if (declared === null) return;
  if (!/^\d{1,15}$/.test(declared)) throw new ApiError(400, "bad_length", "Content-Length is not a number.");
  if (Number(declared) > limit) throw tooLarge();
}

/** Reads the whole body, but never more than `limit` bytes. */
export async function readCapped(req: Request, limit = MAX_BODY_BYTES): Promise<Uint8Array> {
  checkDeclaredLength(req, limit);
  if (!req.body) return new Uint8Array(0);
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > limit) {
      await reader.cancel().catch(() => {});
      throw tooLarge();
    }
    chunks.push(value);
  }
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.byteLength;
  }
  return out;
}

/** True when the Content-Type is application/json (a charset parameter is fine). */
export function isJsonContentType(req: Request): boolean {
  const type = (req.headers.get("content-type") ?? "").split(";")[0].trim().toLowerCase();
  return type === "application/json";
}
