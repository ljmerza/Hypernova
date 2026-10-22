# hypernova.observer: the name service

A Cloudflare Worker that gives every Hypernova server a random name under `hypernova.observer`
and keeps its DNS records up to date, so the phone can reach the server over real HTTPS with a
Let's Encrypt certificate: a VPS abroad, a Mac at home, or the same Wi-Fi.

Only the name points at the server. No traffic passes through this domain or this Worker; the
phone talks to the server directly. The service runs entirely on Cloudflare's free tier.

```
server (daemon)                      api.hypernova.observer (this Worker)         Cloudflare DNS
  Ed25519 key  ── POST /v1/register ─────▶ random name, key stored
               ── PUT /address (signed) ─▶ <name>        A/AAAA  = caller's public IP ─▶ records
                                           l-<name>      A/AAAA  = the private IPs given
               ── PUT /acme (signed) ────▶ _acme-challenge.<name> TXT             ─▶ record
  Let's Encrypt DNS-01 ◀────────────────────────────────────────────────────── looks up the TXT
               ── DELETE /acme (signed) ─▶ TXT removed (or after an hour by itself)
```

## Layout

- `src/index.ts`: the Worker (`fetch` + `scheduled`), built by `createWorker({fetch, now})` so
  tests can swap the Cloudflare API and the clock.
- `src/api.ts`: the routes and the hourly maintenance. `src/auth.ts`: signature checks.
  `src/dns.ts`: the four Cloudflare DNS API calls. `src/db.ts`: D1 queries. `src/ip.ts`: address
  parsing and the private/public rules. `src/names.ts`: random names. `src/page.ts`: the apex page.
  `src/links.ts`: the app links (`assetlinks.json` and the `/pair` and `/glasses` pages).
- `client.ts`: the reference client (register, sign, update address, set/clear ACME TXT,
  release). No dependencies; the daemon imports it as is. It also defines the signing scheme, and
  the Worker imports that from here so the two cannot drift.
- `migrations/`: the D1 schema. `wrangler.jsonc`: the Worker config. `test/`: Vitest inside
  workerd with a fake Cloudflare API. `setup.sh` + `setup.md`: the owner's one-time setup.
  `smoke.ts`: a round trip against the live service.

## The API

Base: `https://api.hypernova.observer`. JSON in, JSON out. Errors are
`{"error": "<code>", "message": "<plain English>"}` with the matching status; `429` replies carry
`Retry-After`. The domain in the examples below is a var, so a self-hosted copy answers the
same way under its own domain.

### `POST /v1/register` (unsigned)

Body `{"publicKey": "<raw 32-byte Ed25519 public key, base64>"}`.
Reply `201 {"name": "k3x9mq2z7a", "fqdn": "k3x9mq2z7a.hypernova.observer", "lanFqdn": "l-k3x9mq2z7a.hypernova.observer", "domain": "hypernova.observer"}`.

The name is 10 random lowercase letters or digits, never from a word list and never derived
from the key or the caller. Limits: 5 registrations per source address per day (an IPv6 caller
is counted per /64, and a whole /48 may make at most 20), 500 per day in total. The source
address is kept only as a hash in a counter that expires with the day. The request must have
`Content-Type: application/json` (else `415`, before anything else happens), so a web page on
another site cannot post to it as a "simple" cross-origin request. Bodies over 4 KB are refused
(`413`) by their declared Content-Length before any byte is read, and a body with no or a false
length stops being read at 4 KB.

### Every other call is signed

Headers:

| Header | Value |
|---|---|
| `X-Hn-Timestamp` | unix seconds; accepted within ±300 s of the server's clock |
| `X-Hn-Nonce` | 16 random bytes, hex; accepted once per name within the window |
| `X-Hn-Signature` | Ed25519 signature, base64, by the key registered for the name |

The signature covers, joined with `\n`: `hypernova-names-v1`, the method, the path (no query),
the SHA-256 of the body in hex (the hash of the empty string for no body), the timestamp and the
nonce. See `buildSigningString` in `client.ts`. The order of checks: shape of the headers,
timestamp window, signature, nonce, then the per-name limit of 120 accepted calls an hour, so a
stranger who knows a name cannot use up its owner's limit. Every accepted call marks the name as
alive.

### `PUT /v1/names/{name}/address`

Body `{"lan": ["192.168.1.20", "fd12:3456::7"], "public": false}` (both optional; `lan` at most 4).

Sets `<name>.hypernova.observer` (A or AAAA, TTL 60) to the **caller's own public address** as
Cloudflare saw it (`CF-Connecting-IP`). Nothing in the body can change that, which is what keeps
anyone from pointing a name at a server that is not theirs. The old record is changed in place;
a different family (v4 to v6) swaps the record type.

Sets `l-<name>.hypernova.observer` to the addresses in `lan`, which must all be private:
RFC 1918 (`10/8`, `172.16/12`, `192.168/16`), carrier-grade NAT `100.64/10` (where Tailscale
lives) or IPv6 unique local `fc00::/7`. Loopback, link-local and public addresses are refused
with `lan_not_private`. An empty or missing list removes all LAN records. Only the differences
are sent to Cloudflare.

Refused with `caller_not_public` when the caller's address is not a public one (for instance
behind carrier-grade NAT with no public address; such a server cannot be reached from outside
anyway, and the apps fall back to the pinned self-signed certificate).

`"public": false` leaves `<name>` without any record (and removes one published before): a
computer at home that is reached only on its own network (a Mac) has no use for it, and would
otherwise put the home's public address in DNS under a name anyone can read from the
certificate logs. The caller's address is not looked at then. Anything but `true`/`false` is
refused with `bad_public`. A service from before this option ignores the field.

Reply `{"name", "fqdn", "public": {"type": "A", "address": "…"} | null, "lanFqdn", "lan": [...]}`.

### `PUT /v1/names/{name}/acme`

Body `{"value": "<the DNS-01 challenge text>", "host": "main" | "lan"}` (`host` defaults to
`main`). Creates `_acme-challenge.<name>` (or `_acme-challenge.l-<name>`) `TXT "<value>"`,
TTL 60. At most 4 challenge records per name at a time (`too_many_challenges`); a certificate
for both hosts needs two. Publishing the same value again restarts its hour. Reply
`{"name", "txt": [{"host", "value", "expiresAt"}]}`.

### `DELETE /v1/names/{name}/acme`

Body `{"value": "…", "host": "…"}` (both optional): removes the matching challenge records, or
all of them with no body. Records are also removed one hour after they were published, in case
a client crashes between the challenge and the cleanup.

### `GET /v1/names/{name}`

The name's state: `createdAt`, `lastSeen`, `public`, `lan`, `txt`.

### `DELETE /v1/names/{name}`

Releases the name: every record is removed and the name may be handed out again later.
Names nobody has called for 90 days are released the same way by the hourly maintenance.

### `GET /` on `hypernova.observer`

A static page: what Hypernova is and a link to the repository. No script, no cookies, no
tracking; a `Content-Security-Policy` of `default-src 'none'`. The API host answers `GET /` with
a one-line JSON pointer. Everything else is 404, but for the app links below.

### App links: `/.well-known/assetlinks.json`, `/pair`, `/glasses` (on `hypernova.observer`)

The Mac's pairing QR code is `https://hypernova.observer/pair#v=1&u=…&c=…` (docs/PROTOCOL.md,
"Pairing"): phone cameras open https links, and Android opens this one straight in the Hypernova
app once it has verified the domain against `/.well-known/assetlinks.json` (Android App Links:
package `com.prudnikweb.hypernova`, the signing keys' SHA-256 fingerprints from the
`ANDROID_CERT_FINGERPRINTS` var, comma-separated, so a release key joins the debug one; unset, a
404). The pairing details are in the fragment, after the `#`, which a browser never sends: none of
them reaches this Worker, its logs or Cloudflare's.

When such a link opens in a browser instead (no app yet, the domain not verified yet, a scanner's
own view), `/pair` and `/glasses` are one static page (`src/links.ts`): its script reads the
fragment in the page, keeps the keys the app knows, takes the details out of the address bar (and
so the history entry) and points "Open Hypernova" at an `intent://`
link for Chrome (the app's package, and this page's `#get` as the way back when the app isn't
there), and the page says how to get the app from the computer. No cookies, no analytics, and a
CSP of `default-src 'none'` with the one inline script by its hash and inline styles: no
`connect-src`, nothing loaded, no frames, no forms; `Referrer-Policy: no-referrer` and
`X-Robots-Tag: noindex`. The page is the same for every request (a query changes nothing in it).
Check a deploy with `curl -s https://hypernova.observer/.well-known/assetlinks.json`, and on the
phone `adb shell pm verify-app-links --re-verify com.prudnikweb.hypernova`, then
`adb shell pm get-app-links com.prudnikweb.hypernova` (`hypernova.observer: verified`).

## The security model

**What the service can do, and no more.** Its Cloudflare token can edit the DNS of this one
zone. Every record it creates carries the comment `hypernova-names:<name>`, and it changes or
deletes records only by ids it stored when it created them. The hourly sweep looks only at
records with the tag, and only removes ones older than an hour that the database no longer
knows (a crash between the DNS write and the database write leaves such a record behind). A
record someone made by hand in the dashboard is never touched.

**Who can change a name.** Only the holder of the Ed25519 key it was registered with. There is
no account, no email and no recovery: a lost key means the name is released after 90 days of
silence and the server registers a new one (the apps re-pair to the new name). A stolen key
means the thief can repoint the name, until the owner releases it with the same key or stops
calling; the phone's pairing key is still needed to talk to whatever the name points at.

**Where a name can point.** The public record is always the caller's own address, so a name
can only ever lead to the machine that signed the request (or to its NAT, which is the same
thing from outside). Private records are allowed only in private ranges, which are not
routable from the internet, so the worst a hostile server can do is point `l-<name>` at
another device on the user's own network; that device would still need Hypernova's pairing key.

**Replay and clocks.** A captured request is good for five minutes and one use (the nonce is
inside the signature). A server whose clock is off by more than five minutes gets `expired`
with that explanation.

**Rate limits** bound what one key, one address or everyone together can make the Worker do:
5 registrations per address (IPv6: per /64, 20 per /48) per day, 500 a day in total, 120 accepted calls per name per hour.
Each limit is a D1 row whose key names its window; the hourly maintenance deletes the old ones.
Counters are read before they are written, so calls over a limit cost no writes. A valid call
writes a nonce and a counter row, plus the name's last-seen time at most once an hour.

**Anonymity.** Registration stores the key and two timestamps. The source address is hashed
into a counter that lives one day. Names are random and say nothing about the owner, which
matters because every certificate, and so every name, becomes public in Certificate
Transparency logs. Addresses published in DNS are public by nature; a server that does not need
a public record sends `"public": false` (the daemon on a Mac does), and a user who wants no
record at all uses the local network by address or Tailscale.

**The shared domain.** All names are siblings under one domain, so a page on one could set a
cookie for the others; that is why the apex sets none and why the web app keeps its session out
of cookies. The Public Suffix List fixes this properly once the registration is extended past
two years (plan item 5b). A CAA record allows only Let's Encrypt to issue under the domain.
Names are released after 90 days idle and can be handed out again, so a certificate for an old
name may still exist in the logs; it cannot be used, because the old server's key never
matched the new owner's, and the pairing key is still required.

**If the service is down**, nothing breaks for existing names: the records stay in DNS and the
apps keep their pinned certificates as the fallback. Only renewals and new names wait.

## Costs

Everything fits Cloudflare's free tier with room to spare:

| | Free tier | This service |
|---|---|---|
| Workers requests | 100,000 a day | a server calls a few times a day (address checks, a renewal every ~60 days) |
| D1 | 5 million reads, 100,000 writes a day, 5 GB | a few reads and writes per call |
| Cron triggers | included | one run an hour |
| Custom domains, DNS records | included | 2 custom domains, 1 to 6 records per name |

So the first thousand servers cost nothing beyond the domain's own registration. Let's Encrypt
limits new certificates to 50 a week per registered domain until the domain is on the Public
Suffix List; renewals do not count. If sign-ups ever near that, ZeroSSL is the fallback CA
(and the CAA record would then list it too).

## Development

```sh
export PATH=~/.nvm/versions/node/v25.9.0/bin:$PATH
cd names
npm install
npm test            # vitest, inside workerd, Cloudflare API faked; nothing leaves the machine
npm run typecheck   # tsc
npx wrangler dev    # local Worker with a local D1; DNS calls fail with 502 without a token
```

For `wrangler dev` with real DNS changes, put `CF_API_TOKEN=...` in `names/.dev.vars`
(ignored by git). `wrangler dev` reports `127.0.0.1` as the caller, so `PUT /address` answers
`caller_not_public` there; test that route through the deployed service (`node smoke.ts`).

Tests: `test/fake-cloudflare.ts` is a stand-in for the DNS records API (same shapes, same
envelope, checks the token and the zone), `test/harness.ts` builds the Worker on it with a
movable clock, and `test/setup.ts` gives every test a fresh migrated database. The suite covers
signatures (good, wrong key, replayed, expired, tampered method/path/body), the caller-address
rule, private-only LAN records, both rate limits, the challenge-record lifecycle including the
hour's expiry, release, the 90-day expiry, and the sweep that only ever removes tagged orphans.

## Self-hosting under another domain

The service is tied to its domain only through configuration:

1. Put the domain on Cloudflare (free plan is enough) and buy it anywhere.
2. In `wrangler.jsonc`: `DOMAIN`, the two `routes` patterns (`api.<domain>` and `<domain>`),
   and a new `name`. `API_HOST` is a var if the API should answer somewhere other than `api.`.
3. Run `setup.sh`: it reads the domain from `wrangler.jsonc`, so the zone id, the database, the
   DNS-only token, the CAA record and the deploy all target the new domain.
4. Point the daemon at `https://api.<domain>` (the daemon's name-service URL setting, once it
   lands), or construct `NameServiceClient` with that `baseUrl`.

One name service per domain; a server registered with one does not exist on another. For a
private deployment, lower `REGISTER_PER_DAY`, `REGISTER_PER_IP_PER_DAY` and `REGISTER_PER_48_PER_DAY` in `vars`, or add a
Cloudflare Access policy in front of `api.<domain>`.

**Let's Encrypt's budget.** Until the domain is on the Public Suffix List, all names share Let's Encrypt's
50 new certificates a week. The Worker therefore counts a name's first-ever challenge against a daily
budget for everyone (`ACME_FIRST_PER_DAY`, 6; HTTP 429 `acme_busy` with `Retry-After`), exempts names that
published before (renewals), and limits one name to `ACME_PER_NAME_PER_WEEK` (8) new challenges a week
(`acme_name_limit`). The daemon then goes on without the private name.

## What the daemon does with it

`daemon/src/names.ts` (docs/PROTOCOL.md, "A name on the local network"). On a Mac (and a Linux
PC with the local network listener on), only once the user has been shown what the name is and
that Let's Encrypt's terms are accepted for it (Setup's phone step, or `hypernova pair`), and only
while a code that uses the name is on screen:

- An Ed25519 key, made once and kept in `<support>/names/name.json` (0600, PKCS#8 through
  `exportPrivateKey` / `importServerKey`), registers a name. The reply is checked (a plain name
  under the expected domain); redirects aren't followed; replies are read up to 64 KB.
- `updateAddress(key, name, [the Wi-Fi address], { public: false })` while a code is shown:
  `l-<name>` points at the one address the download link uses, and `<name>` gets **no** record.
  Thirty minutes after the last code, `updateAddress(key, name, [], { public: false })` takes the
  address out again. A service that answers with a public record anyway (deployed before
  `public: false`) gets the name released at once, and the daemon stops until the service is
  redeployed and the setting is turned off and on.
- A certificate for `l-<name>` only, from Let's Encrypt by DNS-01 through `setAcme(…, "lan")` /
  `clearAcme`, first asked for when a code is first shown, renewed from 30 days before it runs
  out (renewals need no address record); staging until the first real run is checked
  (`HYPERNOVA_ACME_DIRECTORY=production`, or the `acmeDirectory` setting).
- The LAN listener serves it to browsers that name the host (SNI), and only the download page,
  the APK and a shown code's pairing page there; the app keeps the pinned IP route. The download
  link (with either Tailscale route) and, with the built-in node, the pairing page's web link use
  the name only while it works: trusted certificate, current address, and this network's
  resolver finding it.
- The ACME account accepts Let's Encrypt's terms (https://letsencrypt.org/repository/) on the
  user's behalf; the Mac app's Setup says so next to its "Use a private name for this Mac" switch,
  and `hypernova pair` once on Linux.
- Uninstalling (or turning the `nameService` setting off) calls `release`; when the service
  can't be reached the key is kept and the release retried. `unknown_name` (the name expired or
  the database lost it) registers a new one. `429`s wait for `Retry-After`.

Calls per server: a registration the first time a code is shown, two address updates per
session of codes (published, then withdrawn), and two calls per certificate (the challenge
record published, then removed), about every 60 days. New certificates count against Let's Encrypt's 50 a week for the whole domain until it is
on the Public Suffix List (renewals don't), so the daemon keeps its certificate and never asks
for a new one without a reason (a new name, a new CA, or the renewal window).

The daemon carries a byte-for-byte copy of `client.ts` as `daemon/src/names-client.ts` (the
daemon ships without `names/`); a daemon test fails when they differ. After editing `client.ts`:
`cp names/client.ts daemon/src/names-client.ts`.
