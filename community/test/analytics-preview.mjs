import {createServer} from 'node:http';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {createHandler} from '../worker.mjs';

// Loopback-only fixture: synthetic rows and an injected verifier, never production D1.
const sqlite = new DatabaseSync(':memory:');
sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url), 'utf8'));
const stamp = Math.floor(Date.now() / 1000);
const insert = sqlite.prepare('INSERT INTO analytics_events VALUES(?,?,?,?,?,?,?,?)');
for (let i = 0; i < 18; i++) {
  insert.run(`fixture-${i}`, `synthetic-install-${i % 3}`, i % 2 ? 'download_completed' : 'download_failed',
    'yunget', i < 8 ? '2.7.31' : '2.7.32', 'android',
    JSON.stringify({engine: 'turbodl', source: i % 3 ? 'quark' : 'xunlei', error: 'rate_limited', http_status: 429}),
    stamp - i * 60);
}
const DB = {prepare(sql) {
  let args = [];
  return {bind(...values) { args = values; return this; }, async first() { return sqlite.prepare(sql).get(...args) ?? null; },
    async all() { return {results: sqlite.prepare(sql).all(...args)}; }};
}};
const handle = createHandler({verifyAdmin: async () => ({developer: true})});
createServer(async (incoming, outgoing) => {
  try {
    const response = await handle(new Request('http://127.0.0.1:8804' + incoming.url),
      {DB, PUBLIC_ORIGIN: 'http://127.0.0.1:8804'});
    outgoing.writeHead(response.status, Object.fromEntries(response.headers));
    outgoing.end(Buffer.from(await response.arrayBuffer()));
  } catch { outgoing.writeHead(500); outgoing.end('fixture failed'); }
}).listen(8804, '127.0.0.1', () => console.log('Analytics fixture: http://127.0.0.1:8804/admin/analytics'));
