# Setting up the name service (owner, once)

Everything below is done by `bash names/setup.sh`; this page says what each step does and what
to click in the Cloudflare dashboard, so nothing is a surprise. Wrangler runs through `npx` and
nothing is installed globally. About 15 minutes.

Before starting: the domain `hypernova.observer` is on this Cloudflare account, its DNS is on
Cloudflare (it is, with Cloudflare Registrar), two-factor authentication is on, DNSSEC is on.

## 1. Dependencies

`npm install` in `names/`. Wrangler, Vitest and the Workers types; nothing else.

## 2. Sign in

`npx wrangler login` opens a browser window; approve. Wrangler keeps an OAuth token in
`~/.wrangler`. This is the account-wide access Wrangler needs to create the database and deploy;
the Worker itself never gets it (step 5 gives it a much smaller token).

## 3. Zone id

Dashboard → `hypernova.observer` → **Overview** → right column, **Zone ID** (32 hex characters).
The script writes it into `wrangler.jsonc` as the `CF_ZONE_ID` var. It is not a secret.

## 4. D1 database

`npx wrangler d1 create hypernova-names` (or finds it if it exists), writes the id into
`wrangler.jsonc`, then `npx wrangler d1 migrations apply hypernova-names --remote` creates the
tables from `migrations/`. D1 rather than KV because replay protection and rate limits need
reads that see the previous write, and D1's free tier allows 100,000 writes a day (KV: 1,000).

## 5. The DNS-only token

The Worker changes DNS records through the Cloudflare API with a token that can do exactly one
thing: edit the DNS of this one zone. If the Worker or the token ever leaked, that is the whole
blast radius: the records of `hypernova.observer`, nothing else on the account.

Dashboard → profile icon (top right) → **My Profile** → **API Tokens** → **Create Token** →
**Create Custom Token** (at the bottom):

| Field | Value |
|---|---|
| Token name | `hypernova-names` |
| Permissions | **Zone** · **DNS** · **Edit** (one row; delete any extra rows) |
| Zone Resources | **Include** · **Specific zone** · `hypernova.observer` |
| Client IP Address Filtering | empty |
| TTL | empty |

**Continue to summary** → **Create Token**. It is shown once; paste it into the script's prompt
(not echoed). The script checks it with `/user/tokens/verify` and with one read of the zone's
records, then stores it with `npx wrangler secret put CF_API_TOKEN`. It is not written to any
file.

To rotate it later: create a new one the same way, `npx wrangler secret put CF_API_TOKEN`,
then roll the old one in the dashboard.

## 6. CAA record

The script creates `hypernova.observer. CAA 0 issue "letsencrypt.org"` with the token. This
tells every certificate authority that only Let's Encrypt may issue certificates for the domain
and anything under it, so a `<name>.hypernova.observer` certificate can only come from the one
CA the daemon uses.

Cloudflare's own certificates for the two custom domains (the apex page and `api.`) are issued
by Cloudflare's CAs; when a zone has CAA records, Cloudflare adds the entries its own CAs need
automatically (they show as read-only in the DNS list). The smoke test checks that
`https://hypernova.observer/` answers, which confirms this worked. If it does not within ten
minutes, SSL/TLS → Edge Certificates in the dashboard says what is missing.

## 7. Deploy

`npx wrangler deploy` uploads the Worker and, because `wrangler.jsonc` declares two custom
domains, creates the proxied DNS records `hypernova.observer` and `api.hypernova.observer` and
requests their certificates. If the registrar left a placeholder A record at the apex, delete it
first (Wrangler refuses to overwrite a record it did not create).

The cron trigger (`17 * * * *`) is part of the deploy: every hour the Worker removes challenge
records older than an hour, releases names idle for 90 days and removes any tagged record the
database lost track of.

## 8. Smoke test

The script fetches the page and the API, then runs `node smoke.ts`: a throwaway key registers a
name, publishes this Mac's public address plus `192.168.77.7` as a private one, publishes and
removes a challenge record, checks each through Cloudflare's public resolver, and releases the
name. Nothing stays behind. Run it again any time: `node names/smoke.ts`.

## Afterwards

- Logs: `npx wrangler tail` in `names/`. The maintenance run logs one line an hour.
- Records the Worker created are the ones whose comment starts with `hypernova-names:` in the
  DNS list; the Worker never touches any other.
- Limits (per source address 5 registrations a day, 500 a day in total, 120 signed calls per
  name an hour) are vars in `src/api.ts`; override with `vars` in `wrangler.jsonc` and redeploy.
- The Public Suffix List application is parked until the registration is extended past two
  years (plan item 5b).
