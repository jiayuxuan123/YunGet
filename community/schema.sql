PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS sessions (
  id_hash TEXT PRIMARY KEY,
  nickname TEXT NOT NULL,
  account_id TEXT,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS rate_limits (
  bucket TEXT PRIMARY KEY,
  hits INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS used_verifications (
  signature TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS used_verifications_expiry ON used_verifications(expires_at);
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


CREATE TABLE IF NOT EXISTS community_accounts (
  id TEXT PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  nickname TEXT NOT NULL,
  recovery_hash TEXT,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON sessions(account_id);
CREATE TABLE IF NOT EXISTS community_posts (
  id TEXT PRIMARY KEY,
  author_id TEXT NOT NULL REFERENCES community_accounts(id),
  body TEXT NOT NULL DEFAULT '',
  category TEXT NOT NULL CHECK(category IN ('discussion','question','idea','bug')),
  product TEXT NOT NULL DEFAULT 'yunget',
  developer INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0,
  locked INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'new',
  comment_count INTEGER NOT NULL DEFAULT 0,
  like_count INTEGER NOT NULL DEFAULT 0,
  dedupe_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(author_id,dedupe_key)
);
CREATE INDEX IF NOT EXISTS community_feed ON community_posts(hidden,pinned DESC,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS community_author_posts ON community_posts(author_id,hidden,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS community_comments (
  id TEXT PRIMARY KEY,
  post_id TEXT NOT NULL REFERENCES community_posts(id),
  author_id TEXT NOT NULL REFERENCES community_accounts(id),
  parent_id TEXT REFERENCES community_comments(id),
  body TEXT NOT NULL DEFAULT '',
  developer INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0,
  dedupe_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(author_id,dedupe_key)
);
CREATE INDEX IF NOT EXISTS community_comment_stream ON community_comments(post_id,hidden,created_at,id);
CREATE TABLE IF NOT EXISTS community_likes (
  post_id TEXT NOT NULL REFERENCES community_posts(id),
  account_id TEXT NOT NULL REFERENCES community_accounts(id),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(post_id,account_id)
);
CREATE TABLE IF NOT EXISTS community_polls (
  post_id TEXT PRIMARY KEY REFERENCES community_posts(id),
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS community_poll_options (
  id TEXT PRIMARY KEY,
  post_id TEXT NOT NULL REFERENCES community_polls(post_id),
  label TEXT NOT NULL,
  position INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS community_options_post ON community_poll_options(post_id,position);
CREATE TABLE IF NOT EXISTS community_votes (
  post_id TEXT NOT NULL REFERENCES community_polls(post_id),
  account_id TEXT NOT NULL REFERENCES community_accounts(id),
  option_id TEXT NOT NULL REFERENCES community_poll_options(id),
  created_at INTEGER NOT NULL,
  PRIMARY KEY(post_id,account_id)
);
CREATE INDEX IF NOT EXISTS community_votes_option ON community_votes(post_id,option_id);
CREATE TABLE IF NOT EXISTS community_media (
  id TEXT PRIMARY KEY,
  owner_id TEXT NOT NULL REFERENCES community_accounts(id),
  mime TEXT NOT NULL,
  size INTEGER NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  digest TEXT NOT NULL,
  upload_key TEXT NOT NULL,
  data BLOB NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(owner_id,upload_key)
);
CREATE INDEX IF NOT EXISTS community_media_pending ON community_media(owner_id,created_at);
CREATE TABLE IF NOT EXISTS community_attachments (
  media_id TEXT PRIMARY KEY REFERENCES community_media(id) ON DELETE CASCADE,
  post_id TEXT NOT NULL REFERENCES community_posts(id),
  comment_id TEXT REFERENCES community_comments(id)
);
CREATE INDEX IF NOT EXISTS community_attachments_post ON community_attachments(post_id,comment_id);
CREATE TABLE IF NOT EXISTS community_notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id TEXT NOT NULL,
  actor_id TEXT,
  kind TEXT NOT NULL,
  post_id TEXT,
  comment_id TEXT,
  feedback_id TEXT,
  preview TEXT NOT NULL DEFAULT '',
  event_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  read_at INTEGER,
  UNIQUE(account_id,event_key)
);
CREATE INDEX IF NOT EXISTS community_inbox ON community_notifications(account_id,id DESC);
CREATE INDEX IF NOT EXISTS community_unread ON community_notifications(account_id,read_at);
CREATE TABLE IF NOT EXISTS community_announcement_reads (
  account_id TEXT NOT NULL,
  announcement_id TEXT NOT NULL,
  revision INTEGER NOT NULL,
  PRIMARY KEY(account_id,announcement_id)
);
CREATE TRIGGER IF NOT EXISTS community_comment_insert AFTER INSERT ON community_comments BEGIN
  UPDATE community_posts SET comment_count=comment_count+1 WHERE id=NEW.post_id;
  INSERT OR IGNORE INTO community_notifications(account_id,actor_id,kind,post_id,comment_id,preview,event_key,created_at)
    SELECT author_id,NEW.author_id,'comment',NEW.post_id,NEW.id,substr(NEW.body,1,120),'comment:'||NEW.id,NEW.created_at
    FROM community_posts WHERE id=NEW.post_id AND author_id<>NEW.author_id;
  INSERT OR IGNORE INTO community_notifications(account_id,actor_id,kind,post_id,comment_id,preview,event_key,created_at)
    SELECT author_id,NEW.author_id,'reply',NEW.post_id,NEW.id,substr(NEW.body,1,120),'comment:'||NEW.id,NEW.created_at
    FROM community_comments WHERE id=NEW.parent_id AND author_id<>NEW.author_id
      AND author_id<>(SELECT author_id FROM community_posts WHERE id=NEW.post_id);
END;
CREATE TRIGGER IF NOT EXISTS community_comment_visibility AFTER UPDATE OF hidden ON community_comments
WHEN NEW.hidden<>OLD.hidden BEGIN
  UPDATE community_posts SET comment_count=max(0,comment_count+CASE WHEN NEW.hidden=0 THEN 1 ELSE -1 END)
    WHERE id=NEW.post_id;
END;
CREATE TRIGGER IF NOT EXISTS community_like_insert AFTER INSERT ON community_likes BEGIN
  UPDATE community_posts SET like_count=like_count+1 WHERE id=NEW.post_id;
END;
CREATE TRIGGER IF NOT EXISTS community_like_delete AFTER DELETE ON community_likes BEGIN
  UPDATE community_posts SET like_count=max(0,like_count-1) WHERE id=OLD.post_id;
END;
CREATE TRIGGER IF NOT EXISTS community_feedback_status AFTER UPDATE OF status ON feedback
WHEN NEW.status<>OLD.status BEGIN
  INSERT OR IGNORE INTO community_notifications(account_id,kind,feedback_id,preview,event_key,created_at)
    VALUES(NEW.author_id,'feedback_status',NEW.id,NEW.status,'feedback-status:'||NEW.id||':'||lower(hex(randomblob(8))),NEW.updated_at);
END;
CREATE TRIGGER IF NOT EXISTS community_feedback_reply AFTER INSERT ON replies BEGIN
  INSERT OR IGNORE INTO community_notifications(account_id,actor_id,kind,feedback_id,preview,event_key,created_at)
    SELECT author_id,NEW.author_id,'feedback_reply',NEW.feedback_id,substr(NEW.body,1,120),'feedback-reply:'||NEW.id,NEW.created_at
    FROM feedback WHERE id=NEW.feedback_id AND author_id<>NEW.author_id;
END;

CREATE TRIGGER IF NOT EXISTS community_post_status AFTER UPDATE OF status ON community_posts
WHEN NEW.status<>OLD.status BEGIN
  INSERT INTO community_notifications(account_id,actor_id,kind,post_id,preview,event_key,created_at)
    VALUES(NEW.author_id,'developer','post_status',NEW.id,NEW.status,'post-status:'||NEW.id||':'||lower(hex(randomblob(8))),NEW.updated_at);
END;
