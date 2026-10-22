// Random names: 10 lowercase letters or digits from a cryptographic source. Never a word list,
// never derived from the key or the caller: names end up in Certificate Transparency logs, so
// they must say nothing about who owns them.

const ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
export const NAME_LENGTH = 10;
export const NAME_PATTERN = /^[a-z0-9]{10}$/;

export function randomName(): string {
  let out = "";
  // Rejection sampling: 252 is the largest multiple of 36 that fits in a byte, so every character
  // is equally likely instead of the first 4 being slightly favoured by a plain modulo.
  while (out.length < NAME_LENGTH) {
    const bytes = new Uint8Array(NAME_LENGTH * 2);
    crypto.getRandomValues(bytes);
    for (const b of bytes) {
      if (b >= 252) continue;
      out += ALPHABET[b % 36];
      if (out.length === NAME_LENGTH) break;
    }
  }
  return out;
}
