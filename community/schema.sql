PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS sessions (
  id_hash TEXT PRIMARY KEY,
  nickname TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limits (
  bucket TEXT PRIMARY KEY,
  hits INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS announcements (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  date TEXT NOT NULL,
  pinned INTEGER NOT NULL DEFAULT 0,
  important INTEGER NOT NULL DEFAULT 0,
  published INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS feedback (
  id TEXT PRIMARY KEY,
  author_id TEXT NOT NULL,
  nickname TEXT NOT NULL,
  product TEXT NOT NULL CHECK(product IN ('yunget','turbodl')),
  category TEXT NOT NULL CHECK(category IN ('bug','idea','question','other')),
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  app_version TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'new' CHECK(status IN ('new','planned','working','resolved','closed')),
  private INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0,
  dedupe_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(author_id, dedupe_key)
);
CREATE INDEX IF NOT EXISTS feedback_public ON feedback(hidden, private, updated_at DESC);
CREATE INDEX IF NOT EXISTS feedback_author ON feedback(author_id, created_at DESC);
CREATE TABLE IF NOT EXISTS replies (
  id TEXT PRIMARY KEY,
  feedback_id TEXT NOT NULL REFERENCES feedback(id),
  author_id TEXT NOT NULL,
  nickname TEXT NOT NULL,
  developer INTEGER NOT NULL DEFAULT 0,
  body TEXT NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0,
  dedupe_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(author_id, dedupe_key)
);
CREATE INDEX IF NOT EXISTS replies_thread ON replies(feedback_id, created_at);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  author_id TEXT NOT NULL,
  nickname TEXT NOT NULL,
  body TEXT NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0,
  dedupe_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(author_id, dedupe_key)
);
CREATE TABLE IF NOT EXISTS moderation_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  action TEXT NOT NULL,
  target_id TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
