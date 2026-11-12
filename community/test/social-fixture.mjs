import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {solveChallenge} from 'altcha-lib';
import {deriveKey} from 'altcha-lib/algorithms/pbkdf2';
import {createHandler} from '../worker.mjs';
import {issueVerification, scopeForPath} from '../verification.mjs';

export function database(sql = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8')) {
  const sqlite = new DatabaseSync(':memory:'); sqlite.exec(sql);
  const normalize = value => value instanceof ArrayBuffer ? Buffer.from(value) : value;
  const DB = {prepare(sql) {
    let values = [];
    const prepare = () => sqlite.prepare(sql);
    const query = {bind(...args) { values = args.map(normalize); return query; },
      async first() { return prepare().get(...values) ?? null; },
      async all() { return {results: prepare().all(...values)}; },
      execute() {
        const statement = prepare();
        if (statement.columns().length) return {results: statement.all(...values), meta: {changes: 0}};
        const result = statement.run(...values);
        return {results: [], meta: {changes: Number(result.changes), last_row_id: Number(result.lastInsertRowid)}};
      },
      async run() { return query.execute(); },
    };
    return query;
  }, async batch(statements) {
    sqlite.exec('BEGIN');
    try { const results = statements.map(statement => statement.execute()); sqlite.exec('COMMIT'); return results; }
    catch (error) { sqlite.exec('ROLLBACK'); throw error; }
  }};
  return {sqlite, DB};
}

export function socialFixture(sql) {
  const {sqlite, DB} = database(sql);
  const env = {DB, PUBLIC_ORIGIN: 'https://community.example.com', COMMUNITY_VERIFICATION_SECRET: 'synthetic-local-community-proof-key-20261112'};
  const handle = createHandler({verifyAdmin: async request => request.headers.get('X-Test-Admin') === 'local-fixture' ? {developer: true} : null});
  let sequence = 100;
  async function member(nickname = '测试用户', legacy = false) {
    const token = crypto.randomUUID().replaceAll('-', '') + crypto.randomUUID().replaceAll('-', '');
    const id = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)))].map(x => x.toString(16).padStart(2, '0')).join('');
    const uid = (++sequence).toString(16).padStart(12, '0');
    if (!legacy) sqlite.prepare('INSERT INTO community_accounts(id,uid,nickname,created_at) VALUES(?,?,?,?)').run(id, uid, nickname, 1);
    const hasAccountColumn = sqlite.prepare('PRAGMA table_info(sessions)').all().some(x => x.name === 'account_id');
    if (hasAccountColumn) sqlite.prepare('INSERT INTO sessions(id_hash,nickname,expires_at,account_id) VALUES(?,?,?,?)').run(id, nickname, Math.floor(Date.now() / 1000) + 3600, legacy ? null : id);
    else sqlite.prepare('INSERT INTO sessions(id_hash,nickname,expires_at) VALUES(?,?,?)').run(id, nickname, Math.floor(Date.now() / 1000) + 3600);
    return {id, uid, nickname, cookie: '__Host-' + 'yunget-community=' + token};
  }
  async function proof(path) {
    const request = new Request(env.PUBLIC_ORIGIN + path, {headers: {'CF-Connecting-IP': '192.0.2.42'}});
    const challenge = await issueVerification(request, env, scopeForPath(path));
    return btoa(JSON.stringify({challenge, solution: await solveChallenge({challenge, deriveKey})}));
  }
  async function request(path, {method = 'GET', body, cookie, admin = false, key, headers = {}} = {}) {
    const next = new Headers({'CF-Connecting-IP': '192.0.2.42', ...headers});
    if (method !== 'GET') next.set('Origin', env.PUBLIC_ORIGIN);
    if (cookie) next.set('Cookie', cookie);
    if (admin) next.set('X-Test-Admin', 'local-fixture');
    if (key || path.endsWith('/api/media') && method === 'POST') next.set('Idempotency-Key', key || crypto.randomUUID());
    if (body !== undefined && !(body instanceof Uint8Array)) next.set('Content-Type', 'application/json');
    return handle(new Request(env.PUBLIC_ORIGIN + path, {method, headers: next,
      body: body === undefined ? undefined : body instanceof Uint8Array ? body : JSON.stringify(body)}), env);
  }
  async function post(owner, body = {}, admin = false) {
    const path = (admin ? '/admin' : '') + '/api/posts';
    const response = await request(path, {method: 'POST', cookie: owner?.cookie, admin, key: crypto.randomUUID(),
      body: {body: '测试帖子', category: 'discussion', ...(admin ? {} : {challenge: await proof('/api/posts')}), ...body}});
    const data = await response.json();
    assert.equal(response.status, 201, JSON.stringify(data));
    return data.id;
  }
  return {sqlite, env, member, proof, request, post, close: () => sqlite.close()};
}
