-- The whole state of the name service. Times are unix seconds.

-- One row per handed-out name. No source address is stored: the service is anonymous, and the
-- per-address registration limit works on a hash that expires with its day.
CREATE TABLE names (
  name TEXT PRIMARY KEY,
  public_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);
CREATE INDEX names_last_seen ON names(last_seen);

-- The DNS records we created at Cloudflare, by Cloudflare's id, so updates and deletes never
-- touch anything else. kind: 'public' (<name>), 'lan' (l-<name>) or 'acme' (_acme-challenge).
CREATE TABLE records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL REFERENCES names(name) ON DELETE CASCADE,
  kind TEXT NOT NULL,
  cf_id TEXT NOT NULL,
  rr_name TEXT NOT NULL,
  rr_type TEXT NOT NULL,
  content TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE INDEX records_name ON records(name);
CREATE INDEX records_expires ON records(expires_at);

-- Nonces of accepted signed requests, kept for the replay window.
CREATE TABLE nonces (
  name TEXT NOT NULL,
  nonce TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (name, nonce)
);
CREATE INDEX nonces_expires ON nonces(expires_at);

-- Rate-limit counters per window; the key names the window, so a row dies with it.
CREATE TABLE limits (
  key TEXT PRIMARY KEY,
  count INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX limits_expires ON limits(expires_at);
