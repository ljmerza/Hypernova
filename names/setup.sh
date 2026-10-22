#!/usr/bin/env bash
# One-time setup of the hypernova.observer name service, for the owner. Read setup.md first.
#
# What it does, in order, and each step can be re-run safely:
#   1. installs the dependencies (Wrangler via npx, nothing global)
#   2. signs you in to Cloudflare (`npx wrangler login`, a browser window)
#   3. writes the zone id into wrangler.jsonc
#   4. creates the D1 database and applies the migrations
#   5. stores the DNS-only API token as the Worker's secret (you create it in the dashboard)
#   6. adds the CAA record that allows only Let's Encrypt to issue certificates
#   7. deploys the Worker (which also creates the two custom domains)
#   8. runs the smoke test
#
# Usage:  bash names/setup.sh            (from the repo root, or from names/)
#         ZONE_ID=... bash names/setup.sh (skips the zone id prompt)
set -euo pipefail

cd "$(dirname "$0")"
DOMAIN="$(sed -n 's/.*"DOMAIN": *"\([^"]*\)".*/\1/p' wrangler.jsonc | head -1)"
DB_NAME="$(sed -n 's/.*"database_name": *"\([^"]*\)".*/\1/p' wrangler.jsonc | head -1)"
API="https://api.cloudflare.com/client/v4"

# Calls the Cloudflare API with the token in $CF_TOKEN. The token goes in through a curl config
# on stdin (printf is a shell builtin), never as an argument, so it cannot show up in `ps`.
cf_curl() { printf 'header = "Authorization: Bearer %s"\n' "$CF_TOKEN" | curl -K - "$@"; }

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# Node: the nvm install if present, else whatever is on PATH.
if [ -d "$HOME/.nvm/versions/node" ]; then
  LATEST="$(ls -d "$HOME"/.nvm/versions/node/v* 2>/dev/null | sort -V | tail -1)"
  [ -n "$LATEST" ] && export PATH="$LATEST/bin:$PATH"
fi
command -v node >/dev/null || fail "Node is not installed (the daemon's nvm install is enough)."
command -v curl >/dev/null || fail "curl is needed."
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
[ "$NODE_MAJOR" -ge 22 ] || fail "Node 22 or newer is needed (found $(node --version))."
export WRANGLER_SEND_METRICS=false

say "1. Dependencies"
npm install --no-fund --no-audit >/dev/null
note "installed into names/node_modules"

say "2. Cloudflare sign-in"
if npx wrangler whoami 2>/dev/null | grep -q "You are logged in"; then
  note "already signed in"
else
  npx wrangler login
fi

say "3. Zone id of $DOMAIN"
if grep -q 'FILL-IN-ZONE-ID' wrangler.jsonc; then
  if [ -z "${ZONE_ID:-}" ]; then
    note "Dashboard → $DOMAIN → Overview → right column, 'Zone ID'."
    read -r -p "   Zone id: " ZONE_ID
  fi
  [[ "$ZONE_ID" =~ ^[0-9a-f]{32}$ ]] || fail "That does not look like a zone id (32 hex characters)."
  sed -i '' "s/FILL-IN-ZONE-ID/$ZONE_ID/" wrangler.jsonc
  note "written to wrangler.jsonc"
else
  ZONE_ID="$(sed -n 's/.*"CF_ZONE_ID": *"\([^"]*\)".*/\1/p' wrangler.jsonc | head -1)"
  note "already set: $ZONE_ID"
fi

say "4. D1 database '$DB_NAME'"
if grep -q 'FILL-IN-DATABASE-ID' wrangler.jsonc; then
  # `d1 create` refuses an existing name, so look first.
  DB_ID="$(npx wrangler d1 list --json 2>/dev/null | node -e '
    let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{
      const list = JSON.parse(s); const db = list.find(d => d.name === process.argv[1]);
      process.stdout.write(db ? db.uuid : "");
    })' "$DB_NAME" || true)"
  if [ -z "$DB_ID" ]; then
    OUT="$(npx wrangler d1 create "$DB_NAME")"
    printf '%s\n' "$OUT"
    DB_ID="$(printf '%s' "$OUT" | sed -n 's/.*"database_id": *"\([0-9a-f-]*\)".*/\1/p; s/.*database_id *= *"\([0-9a-f-]*\)".*/\1/p' | head -1)"
  fi
  [ -n "$DB_ID" ] || { read -r -p "   Could not read the database id from Wrangler; paste it: " DB_ID; }
  sed -i '' "s/FILL-IN-DATABASE-ID/$DB_ID/" wrangler.jsonc
  note "database id $DB_ID written to wrangler.jsonc"
else
  note "already set"
fi
npx wrangler d1 migrations apply "$DB_NAME" --remote
note "migrations applied"

say "5. The DNS-only API token"
cat <<EOF
   Create it in the dashboard (profile icon → My Profile → API Tokens → Create Token →
   'Create Custom Token'):
     Token name:    hypernova-names
     Permissions:   Zone · DNS · Edit            (one row, nothing else)
     Zone Resources: Include · Specific zone · $DOMAIN
     Client IP filtering: leave empty
     TTL: leave empty
   Continue to summary → Create Token → copy it. It is shown once.
EOF
read -r -s -p "   Paste the token (not echoed): " CF_TOKEN; printf '\n'
[ -n "$CF_TOKEN" ] || fail "No token given."
VERIFY="$(cf_curl -sS "$API/user/tokens/verify")"
printf '%s' "$VERIFY" | grep -q '"status": *"active"' || fail "Cloudflare does not accept this token: $VERIFY"
note "token is active"
# Prove it can read this zone's records and nothing more is assumed.
cf_curl -sS "$API/zones/$ZONE_ID/dns_records?per_page=1" | grep -q '"success": *true' \
  || fail "The token cannot read the DNS records of zone $ZONE_ID; check its zone resource."
printf '%s' "$CF_TOKEN" | npx wrangler secret put CF_API_TOKEN
note "stored as the Worker secret CF_API_TOKEN"

say "6. CAA record: only Let's Encrypt may issue certificates under $DOMAIN"
EXISTING="$(cf_curl -sS "$API/zones/$ZONE_ID/dns_records?type=CAA&name=$DOMAIN")"
if printf '%s' "$EXISTING" | grep -q '"letsencrypt.org"'; then
  note "already there"
else
  cf_curl -sS -X POST "$API/zones/$ZONE_ID/dns_records" -H "Content-Type: application/json" \
    --data "{\"type\":\"CAA\",\"name\":\"$DOMAIN\",\"ttl\":1,\"data\":{\"flags\":0,\"tag\":\"issue\",\"value\":\"letsencrypt.org\"},\"comment\":\"hypernova-names:caa\"}" \
    | grep -q '"success": *true' || fail "Could not create the CAA record."
  note "created: $DOMAIN CAA 0 issue \"letsencrypt.org\""
fi
unset CF_TOKEN

say "7. Deploy"
npx wrangler deploy
note "Cloudflare also created the custom domains $DOMAIN and api.$DOMAIN (proxied records in the zone)."

say "8. Smoke test"
note "The apex page:"
curl -sS -o /dev/null -w '   https://%{url_effective} → HTTP %{http_code}\n' "https://$DOMAIN/" || note "(not reachable yet; certificates for new custom domains can take a few minutes)"
note "The API:"
curl -sS "https://api.$DOMAIN/" || true; printf '\n'
note "A full round trip (register, address, challenge record, release):"
node smoke.ts "https://api.$DOMAIN"

say "Done. The hourly maintenance runs by itself (cron trigger). Logs: npx wrangler tail"
