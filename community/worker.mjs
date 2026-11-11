import {createAdminVerifier} from './auth.mjs';
import {html, style, script} from './web.mjs';
import {analyticsHtml, analyticsScript, analyticsStyle} from './analytics.mjs';
import {consumeVerification, issueVerification, scopeForPath, verificationConfig, VerificationError} from './verification.mjs';

const statuses = new Set(['new', 'planned', 'working', 'resolved', 'closed']);
const categories = new Set(['bug', 'idea', 'question', 'other']);
const products = new Set(['yunget', 'turbodl']);
const analyticsEvents = new Set([
  'app_open', 'resolve_start', 'resolve_success', 'resolve_fail',
  'download_queued', 'download_started', 'download_completed', 'download_failed',
  'save_failed', 'qr_scan_success', 'theme_changed',
]);
const analyticsPropertyKeys = new Set([
  'source', 'engine', 'error', 'kind', 'result', 'api', 'abi',
  'size_bucket', 'duration_bucket', 'connections_bucket', 'ui_style', 'theme',
  'file_kind', 'is_guest',
]);
const cookieName = '__Host-yunget-community';
const sessionLife = 30 * 86400;

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function fail(status, message) { throw new HttpError(status, message); }
function text(value, max, min = 1) {
  if (typeof value !== 'string') fail(422, '请填写完整内容');
  const clean = value.trim().replace(/\r\n/g, '\n');
  if (clean.length < min || clean.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(clean)) fail(422, `内容长度应为 ${min}–${max} 字`);
  return clean;
}
function enumValue(value, allowed) { if (!allowed.has(value)) fail(422, '选项无效'); return value; }
function flag(value, fallback = false) {
  if (value === undefined) return Number(fallback);
  if (typeof value !== 'boolean') fail(422, '开关值无效');
  return Number(value);
}
function uniqueKey(request) { return text(request.headers.get('Idempotency-Key'), 80, 12); }
function stamp() { return Math.floor(Date.now() / 1000); }
async function hash(value) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
async function hmacHash(value, secret) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    {name: 'HMAC', hash: 'SHA-256'},
    false,
    ['sign'],
  );
  const bytes = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
async function readJson(request) {
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) fail(415, '请使用 JSON 提交');
  const reader = request.body?.getReader();
  if (!reader) fail(400, '请求内容为空');
  const chunks = [];
  let total = 0;
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > 16384) { await reader.cancel(); fail(413, '提交内容过大'); }
    chunks.push(value);
  }
  try {
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
    const value = JSON.parse(new TextDecoder().decode(bytes));
    if (!value || Array.isArray(value) || typeof value !== 'object') fail(400, '请求格式无效');
    return value;
  } catch { fail(400, '请求格式无效'); }
}
function analyticsProperties(value) {
  if (value === undefined) return '{}';
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(422, '统计属性无效');
  const entries = Object.entries(value);
  if (entries.length > 20) fail(422, '统计属性过多');
  const clean = {};
  for (const [key, raw] of entries) {
    if (!analyticsPropertyKeys.has(key)) fail(422, `统计属性不支持：${key}`);
    if (typeof raw === 'string') clean[key] = raw.trim().slice(0, 100);
    else if (typeof raw === 'number' && Number.isFinite(raw)) clean[key] = raw;
    else if (typeof raw === 'boolean') clean[key] = raw;
    else fail(422, `统计属性格式无效：${key}`);
  }
  const output = JSON.stringify(clean);
  if (output.length > 1600) fail(413, '统计属性过大');
  return output;
}
function secureResponse(body, status = 200, extra = {}) {
  const headers = new Headers({
    'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin',
    'Strict-Transport-Security': 'max-age=31536000', 'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; worker-src 'self'; frame-src 'none'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    ...extra,
  });
  return new Response(typeof body === 'string' ? body : JSON.stringify(body), {status, headers});
}
async function session(request, env) {
  const token = request.headers.get('Cookie')?.split(';').map(item => item.trim()).find(item => item.startsWith(`${cookieName}=`))?.slice(cookieName.length + 1);
  if (!token || !/^[a-f0-9]{64}$/.test(token)) return null;
  const id = await hash(token);
  return env.DB.prepare('SELECT id_hash, nickname FROM sessions WHERE id_hash = ? AND expires_at > ?').bind(id, stamp()).first();
}
async function rate(request, env, action, limit, seconds) {
  const now = stamp();
  const identifier = await hash(`${new URL(env.PUBLIC_ORIGIN).host}:${request.headers.get('CF-Connecting-IP') || 'unknown'}:${Math.floor(now / 86400)}`);
  const key = `${action}:${identifier}:${Math.floor(now / seconds)}`;
  const row = await env.DB.prepare('INSERT INTO rate_limits(bucket,hits,expires_at) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET hits=hits+1 RETURNING hits')
    .bind(key, now + seconds * 2).first();
  if (row.hits > limit) fail(429, '提交过于频繁，请稍后再试');
}
async function botCheck(request, env, body) {
  try { await consumeVerification(request, env, body.challenge, scopeForPath(new URL(request.url).pathname)); }
  catch (error) { if (error instanceof VerificationError) fail(error.status, error.message); throw error; }
}
const feedbackFields = 'id,nickname,product,category,title,body,app_version,status,private,created_at,updated_at';
function publicFeedback(row, owner) {
  const {author_id, hidden, ...output} = row;
  return {...output, mine: Boolean(owner && author_id === owner.id_hash), private: Boolean(row.private)};
}

export function createHandler({fetchRemote = fetch, verifyAdmin = createAdminVerifier()} = {}) {
  return async function handle(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/$/, '') || '/';
    const isAdmin = path === '/admin' || path.startsWith('/admin/');
    try {
      if (!env.PUBLIC_ORIGIN || !env.DB) fail(503, '服务正在配置');
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        const analyticsPost = path === '/api/analytics' && request.method === 'POST';
        if (analyticsPost) {
          if (request.headers.get('X-YunGet-Analytics') !== '1') fail(403, '统计请求来源无效');
        } else if (request.headers.get('Origin') !== env.PUBLIC_ORIGIN) {
          fail(403, '请在社区页面内操作');
        }
        if (!['POST', 'PATCH', 'DELETE'].includes(request.method)) fail(405, '请求方法不支持');
      }
      const admin = isAdmin ? await verifyAdmin(request, env) : null;
      if (isAdmin && !admin) fail(401, '请先登录开发者账号');
      if (request.method === 'GET' && ['/', '/admin', '/chat'].includes(path)) return secureResponse(html, 200, {'Content-Type': 'text/html; charset=utf-8'});
      if (request.method === 'GET' && path === '/admin/analytics') return secureResponse(analyticsHtml, 200, {'Content-Type': 'text/html; charset=utf-8'});
      if (request.method === 'GET' && path === '/app.css') return secureResponse(style, 200, {'Content-Type': 'text/css; charset=utf-8', 'Cache-Control': 'public,max-age=300'});
      if (request.method === 'GET' && path === '/app.js') return secureResponse(script, 200, {'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'public,max-age=300'});
      if (request.method === 'GET' && path === '/analytics.css') return secureResponse(analyticsStyle, 200, {'Content-Type': 'text/css; charset=utf-8', 'Cache-Control': 'public,max-age=300'});
      if (request.method === 'GET' && path === '/analytics.js') return secureResponse(analyticsScript, 200, {'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'public,max-age=300'});
      if (request.method === 'GET' && path === '/api/config') return secureResponse({verification: verificationConfig, version: '2.7.31'});
      if (request.method === 'GET' && path.startsWith('/verification/')) {
        if (!env.ASSETS) fail(503, '验证资源暂不可用');
        const resource = await env.ASSETS.fetch(request);
        const headers = secureResponse('', resource.status, {'Content-Type': resource.headers.get('Content-Type') || 'text/plain', 'Cache-Control':resource.ok?'public,max-age=31536000,immutable':'no-store'}).headers;
        return new Response(resource.body, {status:resource.status, headers});
      }
      if (request.method === 'GET' && path === '/api/verification/challenge') {
        if (request.headers.get('Sec-Fetch-Site') === 'cross-site' || (request.headers.get('Origin') && request.headers.get('Origin') !== env.PUBLIC_ORIGIN)) fail(403, '请在社区页面内操作');
        await rate(request, env, 'verification-challenge', 30, 60);
        try { return secureResponse(await issueVerification(request, env, url.searchParams.get('scope'))); }
        catch (error) { if (error instanceof VerificationError) fail(error.status, error.message); throw error; }
      }
      if (request.method === 'GET' && path === '/api/announcements') {
        const {results} = await env.DB.prepare('SELECT id,title,body,date,pinned,important FROM announcements WHERE published=1 ORDER BY pinned DESC,date DESC,updated_at DESC LIMIT 40').all();
        return secureResponse({items: results.map(row => ({...row, pinned: Boolean(row.pinned), important: Boolean(row.important)}))}, 200, {'Cache-Control': 'public,max-age=15'});
      }
      if (request.method === 'POST' && path === '/api/analytics') {
        await rate(request, env, 'analytics-ip', 60, 3600);
        if (!env.COMMUNITY_VERIFICATION_SECRET) fail(503, '统计服务暂不可用');
        const body = await readJson(request);
        const installId = text(body.install_id, 64, 32);
        if (!/^[a-f0-9-]{32,64}$/i.test(installId)) fail(422, '安装标识无效');
        const installHash = await hmacHash(installId, env.COMMUNITY_VERIFICATION_SECRET);
        await rate(request, env, `analytics-install-${installHash.slice(0, 24)}`, 400, 86400);
        if (!Array.isArray(body.events) || body.events.length < 1 || body.events.length > 20) fail(422, '统计事件数量无效');
        const statements = body.events.map((entry) => {
          if (!entry || typeof entry !== 'object' || Array.isArray(entry)) fail(422, '统计事件格式无效');
          const eventId = text(entry.event_id, 64, 32);
          if (!/^[a-f0-9-]{32,64}$/i.test(eventId)) fail(422, '统计事件标识无效');
          const event = enumValue(entry.event, analyticsEvents);
          const product = enumValue(entry.product || 'yunget', products);
          const appVersion = text(entry.app_version || '', 32, 0);
          const platform = text(entry.platform || 'android', 24, 0);
          return env.DB.prepare(
            'INSERT INTO analytics_events(event_id,install_hash,event,product,app_version,platform,properties,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_id) DO NOTHING',
          ).bind(eventId, installHash, event, product, appVersion, platform, analyticsProperties(entry.properties), stamp());
        });
        const results = await env.DB.batch(statements);
        const accepted = results.reduce((sum, result) => sum + Number(result.meta?.changes || 0), 0);
        return secureResponse({accepted}, 202);
      }
      if (request.method === 'GET' && path === '/admin/api/session') return secureResponse({developer: true});
      if (request.method === 'GET' && path === '/admin/api/analytics') {
        const days = Math.max(1, Math.min(90, Number(url.searchParams.get('days')) || 30));
        const since = stamp() - days * 86400;
        const [summaryRow, daily, versions, events, errors, engines] = await Promise.all([
          env.DB.prepare(`SELECT COUNT(*) AS events,COUNT(DISTINCT install_hash) AS installs,
            COALESCE(SUM(CASE WHEN event='app_open' THEN 1 ELSE 0 END),0) AS app_opens,
            COALESCE(SUM(CASE WHEN event='resolve_start' THEN 1 ELSE 0 END),0) AS resolve_starts,
            COALESCE(SUM(CASE WHEN event='download_started' THEN 1 ELSE 0 END),0) AS download_starts,
            COALESCE(SUM(CASE WHEN event='download_completed' THEN 1 ELSE 0 END),0) AS download_successes,
            COALESCE(SUM(CASE WHEN event='download_failed' THEN 1 ELSE 0 END),0) AS download_failures,
            COALESCE(SUM(CASE WHEN event='save_failed' THEN 1 ELSE 0 END),0) AS save_failures
            FROM analytics_events WHERE created_at>=?`).bind(since).first(),
          env.DB.prepare(`SELECT date(created_at,'unixepoch','localtime') AS day,
            COUNT(DISTINCT install_hash) AS installs,COUNT(*) AS count
            FROM analytics_events WHERE created_at>=? GROUP BY day ORDER BY day`).bind(since).all(),
          env.DB.prepare(`SELECT app_version,COUNT(DISTINCT install_hash) AS installs,COUNT(*) AS count
            FROM analytics_events WHERE created_at>=? GROUP BY app_version ORDER BY count DESC LIMIT 12`).bind(since).all(),
          env.DB.prepare(`SELECT event,COUNT(*) AS count FROM analytics_events
            WHERE created_at>=? GROUP BY event ORDER BY count DESC`).bind(since).all(),
          env.DB.prepare(`SELECT COALESCE(json_extract(properties,'$.error'),'unknown') AS error,COUNT(*) AS count
            FROM analytics_events WHERE created_at>=? AND event IN ('download_failed','save_failed')
            GROUP BY error ORDER BY count DESC LIMIT 12`).bind(since).all(),
          env.DB.prepare(`SELECT COALESCE(json_extract(properties,'$.engine'),'unknown') AS engine,COUNT(*) AS count
            FROM analytics_events WHERE created_at>=? AND json_extract(properties,'$.engine') IS NOT NULL
            GROUP BY engine ORDER BY count DESC LIMIT 12`).bind(since).all(),
        ]);
        const summary = Object.fromEntries(
          Object.entries(summaryRow || {}).map(([key, value]) => [key, Number(value || 0)]),
        );
        return secureResponse({
          days,
          summary,
          daily: daily.results,
          versions: versions.results,
          events: events.results,
          errors: errors.results,
          engines: engines.results,
        });
      }
      const owner = await session(request, env);
      if (request.method === 'GET' && path === '/api/session') return secureResponse({nickname: owner?.nickname || null});
      if (request.method === 'POST' && path === '/api/session') {
        await rate(request, env, 'session', 10, 600);
        const body = await readJson(request);
        await botCheck(request, env, body, fetchRemote);
        const nickname = text(body.nickname || '访客', 24);
        if (/(官方|管理员|开发者)/.test(nickname)) fail(422, '请使用个人昵称');
        if (owner) {
          await env.DB.prepare('UPDATE sessions SET nickname=? WHERE id_hash=?').bind(nickname, owner.id_hash).run();
          return secureResponse({nickname});
        }
        const token = [...crypto.getRandomValues(new Uint8Array(32))].map(byte => byte.toString(16).padStart(2, '0')).join('');
        const name = `${nickname}·${token.slice(0, 4)}`;
        await env.DB.prepare('INSERT INTO sessions(id_hash,nickname,expires_at) VALUES(?,?,?)').bind(await hash(token), name, stamp() + sessionLife).run();
        return secureResponse({nickname: name}, 201, {'Set-Cookie': `${cookieName}=${token}; Path=/; Max-Age=${sessionLife}; HttpOnly; Secure; SameSite=Lax`});
      }
      if (request.method === 'GET' && (path === '/api/feedback' || path === '/admin/api/feedback')) {
        const page = Math.max(0, Math.min(10000, Number(url.searchParams.get('page')) || 0));
        const status = url.searchParams.get('status');
        if (status && !statuses.has(status)) fail(422, '状态无效');
        const mine = url.searchParams.get('mine') === '1';
        if (mine && !owner && !admin) fail(401, '请先设置昵称');
        const conditions = admin ? ['1=1'] : ['hidden=0', '(private=0 OR author_id=?)'];
        const params = admin ? [] : [owner?.id_hash || ''];
        if (mine) {conditions.push('author_id=?'); params.push(owner?.id_hash || '');}
        if (status) {conditions.push('status=?'); params.push(status);}
        const {results} = await env.DB.prepare(`SELECT ${feedbackFields},author_id,hidden FROM feedback WHERE ${conditions.join(' AND ')} ORDER BY updated_at DESC,id DESC LIMIT 31 OFFSET ?`).bind(...params, page * 30).all();
        return secureResponse({items: results.slice(0, 30).map(row => ({...publicFeedback(row, owner), ...(admin ? {hidden: Boolean(row.hidden)} : {})})), more: results.length > 30});
      }
      if (request.method === 'POST' && path === '/api/feedback') {
        if (!owner) fail(401, '请先设置昵称');
        await rate(request, env, 'feedback', 3, 600);
        const body = await readJson(request);
        await botCheck(request, env, body, fetchRemote);
        const dedupe = uniqueKey(request);
        const id = crypto.randomUUID();
        const now = stamp();
        await env.DB.prepare('INSERT INTO feedback(id,author_id,nickname,product,category,title,body,app_version,private,dedupe_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(author_id,dedupe_key) DO NOTHING')
          .bind(id, owner.id_hash, owner.nickname, enumValue(body.product, products), enumValue(body.category, categories), text(body.title, 100, 3), text(body.body, 6000, 5), text(body.app_version || '', 32, 0), flag(body.private), dedupe, now, now).run();
        const row = await env.DB.prepare('SELECT id FROM feedback WHERE author_id=? AND dedupe_key=?').bind(owner.id_hash, dedupe).first();
        return secureResponse({id: row.id}, 201);
      }
      const threadMatch = path.match(/^\/(?:admin\/)?api\/feedback\/([a-f0-9-]{36})(\/replies)?$/);
      if (threadMatch) {
        const id = threadMatch[1];
        const row = await env.DB.prepare(`SELECT ${feedbackFields},author_id,hidden FROM feedback WHERE id=?`).bind(id).first();
        if (!row || (!admin && (row.hidden || (row.private && row.author_id !== owner?.id_hash)))) fail(404, '反馈不存在或不可见');
        if (request.method === 'GET' && !threadMatch[2]) {
          const {results} = await env.DB.prepare('SELECT id,nickname,developer,body,created_at FROM replies WHERE feedback_id=? AND hidden=0 ORDER BY created_at,id LIMIT 200').bind(id).all();
          return secureResponse({item: {...publicFeedback(row, owner), ...(admin ? {hidden: Boolean(row.hidden)} : {})}, replies: results.map(reply => ({...reply, developer: Boolean(reply.developer)}))});
        }
        if (request.method === 'POST' && threadMatch[2]) {
          if (!owner && !admin) fail(401, '请先设置昵称');
          const body = await readJson(request);
          if (!admin) {await rate(request, env, 'reply', 12, 600); await botCheck(request, env, body, fetchRemote);}
          const replyId = crypto.randomUUID();
          const now = stamp();
          const author = admin ? 'developer' : owner.id_hash;
          const dedupe = uniqueKey(request);
          await env.DB.batch([
            env.DB.prepare('INSERT INTO replies(id,feedback_id,author_id,nickname,developer,body,dedupe_key,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(author_id,dedupe_key) DO NOTHING').bind(replyId, id, author, admin ? '开发者' : owner.nickname, Number(Boolean(admin)), text(body.body, 3000), dedupe, now),
            env.DB.prepare('UPDATE feedback SET updated_at=? WHERE id=?').bind(now, id),
          ]);
          return secureResponse({ok: true}, 201);
        }
        if (request.method === 'PATCH' && admin && !threadMatch[2]) {
          const body = await readJson(request);
          const status = body.status === undefined ? row.status : enumValue(body.status, statuses);
          const hidden = body.hidden === undefined ? row.hidden : flag(body.hidden);
          await env.DB.batch([
            env.DB.prepare('UPDATE feedback SET status=?,hidden=?,updated_at=? WHERE id=?').bind(status, hidden, stamp(), id),
            env.DB.prepare('INSERT INTO moderation_log(action,target_id,created_at) VALUES(?,?,?)').bind(`feedback:${status}:${hidden}`, id, stamp()),
          ]);
          return secureResponse({ok: true});
        }
        if (request.method === 'DELETE' && !admin && row.author_id === owner?.id_hash) {
          await env.DB.prepare('UPDATE feedback SET hidden=1 WHERE id=?').bind(id).run();
          return secureResponse({ok: true});
        }
        fail(405, '请求方法不支持');
      }
      if (request.method === 'GET' && path === '/api/chat') {
        const since = Math.max(0, Number(url.searchParams.get('after')) || 0);
        const {results} = await env.DB.prepare('SELECT id,nickname,body,created_at FROM (SELECT id,nickname,body,created_at FROM messages WHERE hidden=0 AND id>? AND created_at>? ORDER BY id DESC LIMIT 60) ORDER BY id').bind(since, stamp() - 30 * 86400).all();
        return secureResponse({items: results});
      }
      if (request.method === 'POST' && path === '/api/chat') {
        if (!owner) fail(401, '请先设置昵称');
        await rate(request, env, 'chat', 10, 60);
        const body = await readJson(request);
        await botCheck(request, env, body, fetchRemote);
        await env.DB.prepare('INSERT INTO messages(author_id,nickname,body,dedupe_key,created_at) VALUES(?,?,?,?,?) ON CONFLICT(author_id,dedupe_key) DO NOTHING').bind(owner.id_hash, owner.nickname, text(body.body, 500), uniqueKey(request), stamp()).run();
        return secureResponse({ok: true}, 201);
      }
      if (request.method === 'GET' && path === '/admin/api/announcements') return secureResponse({items: (await env.DB.prepare('SELECT * FROM announcements ORDER BY updated_at DESC LIMIT 100').all()).results});
      if (request.method === 'POST' && path === '/admin/api/announcements') {
        const body = await readJson(request);
        const id = body.id ? text(body.id, 100) : crypto.randomUUID();
        const date = text(body.date || new Date().toISOString().slice(0, 10), 10, 10);
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) fail(422, '日期格式无效');
        await env.DB.batch([
          env.DB.prepare('INSERT INTO announcements(id,title,body,date,pinned,important,published,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,body=excluded.body,date=excluded.date,pinned=excluded.pinned,important=excluded.important,published=excluded.published,updated_at=excluded.updated_at').bind(id, text(body.title, 120), text(body.body, 10000), date, flag(body.pinned), flag(body.important), flag(body.published, true), stamp()),
          env.DB.prepare('INSERT INTO moderation_log(action,target_id,created_at) VALUES(?,?,?)').bind('announcement:save', id, stamp()),
        ]);
        return secureResponse({id}, 201);
      }
      if (request.method === 'GET' && path === '/admin/api/chat') return secureResponse({items: (await env.DB.prepare('SELECT id,nickname,body,hidden,created_at FROM messages ORDER BY id DESC LIMIT 100').all()).results});
      if (request.method === 'PATCH' && path.match(/^\/admin\/api\/(?:messages|replies)\/[\w-]+$/)) {
        const parts = path.split('/');
        const table = parts.at(-2);
        const id = parts.at(-1);
        const body = await readJson(request);
        await env.DB.batch([
          env.DB.prepare(`UPDATE ${table} SET hidden=? WHERE id=?`).bind(flag(body.hidden), id),
          env.DB.prepare('INSERT INTO moderation_log(action,target_id,created_at) VALUES(?,?,?)').bind(`${table}:visibility`, id, stamp()),
        ]);
        return secureResponse({ok: true});
      }
      fail(404, '页面不存在');
    } catch (error) {
      if (error instanceof HttpError) return secureResponse({error: error.message}, error.status);
      console.error(JSON.stringify({event: 'community_request_failed', path, requestId: crypto.randomUUID()}));
      return secureResponse({error: '服务暂时不可用，请稍后重试'}, 503);
    }
  };
}

export default {fetch: createHandler(), async scheduled(_controller, env) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM sessions WHERE expires_at<?').bind(stamp()),
    env.DB.prepare('DELETE FROM rate_limits WHERE expires_at<?').bind(stamp()),
    env.DB.prepare('DELETE FROM used_verifications WHERE expires_at<?').bind(stamp()),
    env.DB.prepare('DELETE FROM messages WHERE created_at<?').bind(stamp() - 30 * 86400),
    env.DB.prepare('DELETE FROM analytics_events WHERE created_at<?').bind(stamp() - 180 * 86400),
  ]);
}};
