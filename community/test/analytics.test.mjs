import assert from 'node:assert/strict';
import test from 'node:test';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createHandler} from '../worker.mjs';

function fixture() {
  const sqlite = new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
  const db = {
    prepare(sql) {
      let args = [];
      return {
        bind(...values) { args = values; return this; },
        async first() { return sqlite.prepare(sql).get(...args) ?? null; },
        async all() { return {results: sqlite.prepare(sql).all(...args)}; },
        async run() { return {meta: {changes: Number(sqlite.prepare(sql).run(...args).changes)}}; },
      };
    },
    async batch(statements) { return Promise.all(statements.map(statement => statement.run())); },
  };
  const env = {DB: db, PUBLIC_ORIGIN: 'https://community.example.com', COMMUNITY_VERIFICATION_SECRET: 'synthetic-analytics-test-secret-not-used-in-production'};
  const handle = createHandler({verifyAdmin: async () => ({developer: true})});
  let seq = 0;
  const seed = (event, properties = {}, stamp = Math.floor(Date.now() / 1000)) => {
    sqlite.prepare('INSERT INTO analytics_events VALUES(?,?,?,?,?,?,?,?)')
      .run(`event-${++seq}`, 'synthetic-install', event, 'yunget', '2.7.31', 'android', JSON.stringify(properties), stamp);
  };
  return {sqlite, env, handle, seed};
}

test('dashboard separates reports from success probability and counts affected installs', async () => {
  const f = fixture();
  try {
    f.seed('download_started', {source: 'uc', engine: 'turbodl'});
    f.seed('download_started', {source: 'uc', engine: 'turbodl'});
    f.seed('download_started', {source: 'uc', engine: 'turbodl'});
    f.seed('download_completed', {source: 'uc', engine: 'turbodl'});
    f.seed('download_failed', {source: 'uc', engine: 'turbodl', error: 'timeout'});
    f.seed('resolve_fail', {kind: 'share', error: 'auth'});
    const response = await f.handle(new Request('https://community.example.com/admin/api/analytics?days=7'), f.env);
    assert.equal(response.status, 200);
    const data = await response.json();
    assert.equal(data.summary.download_outcomes, 2);
    assert.equal(data.summary.download_completion_share, 0.5);
    assert.equal(data.summary.resolve_failures, 1);
    assert.equal(data.errors.find(row => row.event === 'resolve_fail').source, 'unknown');
    assert.equal(data.errors.find(row => row.error === 'timeout').installs, 1);
    assert.equal(data.versions[0].installs, 1);
    assert.match(data.methodology, /成功率/);
  } finally { f.sqlite.close(); }
});

test('daily chart uses the same Asia Shanghai calendar as its range', async () => {
  const f = fixture();
  try {
    const now = new Date();
    const shanghaiDay = new Date(now.getTime() + 8 * 3600_000).toISOString().slice(0, 10);
    const earlyToday = Math.floor(Date.parse(shanghaiDay + 'T00:01:00+08:00') / 1000);
    f.seed('app_open', {}, earlyToday);
    const response = await f.handle(new Request('https://community.example.com/admin/api/analytics?days=1'), f.env);
    const data = await response.json();
    assert.equal(data.daily.length, 1);
    assert.equal(data.daily[0].day, shanghaiDay);
    assert.equal(data.timezone, 'Asia/Shanghai');
  } finally { f.sqlite.close(); }
});

test('ingestion rejects private-looking values even under an allowed property key', async () => {
  const f = fixture();
  try {
    const response = await f.handle(new Request('https://community.example.com/api/analytics', {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-YunGet-Analytics': '1'},
      body: JSON.stringify({install_id: '10000000-0000-4000-8000-000000000001', events: [{
        event_id: '20000000-0000-4000-8000-000000000001', event: 'download_failed', app_version: '2.7.31',
        properties: {source: 'https://example.invalid/private?token=synthetic'},
      }]}),
    }), f.env);
    assert.equal(response.status, 422);
    assert.equal(f.sqlite.prepare('SELECT COUNT(*) AS n FROM analytics_events').get().n, 0);
  } finally { f.sqlite.close(); }
});
