CREATE TABLE IF NOT EXISTS used_verifications (
  signature TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS used_verifications_expiry ON used_verifications(expires_at);
