CREATE TABLE IF NOT EXISTS analytics_events (
  event_id TEXT PRIMARY KEY,
  install_hash TEXT NOT NULL,
  event TEXT NOT NULL,
  product TEXT NOT NULL CHECK(product IN ('yunget','turbodl')),
  app_version TEXT NOT NULL DEFAULT '',
  platform TEXT NOT NULL DEFAULT '',
  properties TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS analytics_events_time ON analytics_events(created_at);
CREATE INDEX IF NOT EXISTS analytics_events_install ON analytics_events(install_hash, created_at);
CREATE INDEX IF NOT EXISTS analytics_events_name ON analytics_events(event, created_at);
