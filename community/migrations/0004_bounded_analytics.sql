DROP INDEX IF EXISTS analytics_events_install;
DROP INDEX IF EXISTS analytics_events_name;
DELETE FROM analytics_events WHERE created_at < strftime('%s','now') - 7 * 86400;
DELETE FROM rate_limits WHERE expires_at < strftime('%s','now');
DELETE FROM used_verifications WHERE expires_at < strftime('%s','now');
