import {createMedia} from './social-media.mjs';
import {createInbox} from './social-notifications.mjs';

const categories = new Set(['discussion', 'question', 'idea', 'bug']);
const states = new Set(['new', 'planned', 'working', 'resolved', 'closed']);
const id = prefix => prefix + '_' + crypto.randomUUID().replaceAll('-', '');
const publicAccount = row => ({uid: row.uid, nickname: row.nickname, developer: Boolean(row.developer)});
const postColumns = 'p.id,p.author_id,p.body,p.category,p.product,p.developer,p.pinned,p.hidden,p.locked,p.status,p.comment_count,p.like_count,p.created_at,p.updated_at,a.uid,a.nickname';
const postJoin = 'community_posts p JOIN community_accounts a ON a.id=p.author_id';

export function createSocial(helpers) {
  const {fail, text, readJson, secureResponse, stamp, rate, botCheck, uniqueKey} = helpers;
  const media = createMedia(helpers), inbox = createInbox(helpers);
  async function publish(env, statements) {
    try { return await env.DB.batch(statements); }
    catch (error) {
      if (/UNIQUE constraint failed: community_attachments\.media_id/.test(String(error.message))) fail(409, '图片已用于另一条内容，请重新选择');
      throw error;
    }
  }

  function decode(raw) {
    if (!raw) return null;
    if (raw.length > 160) fail(422, '分页位置无效');
    try {
      const value = JSON.parse(atob(raw.replaceAll('-', '+').replaceAll('_', '/')));
      if (!Number.isSafeInteger(value.t) || value.t < 0 || !/^[pc]_[a-f0-9]{32}$/.test(value.i) || ![0, 1].includes(value.p || 0)) throw Error();
      return {...value, p: value.p || 0};
    } catch { fail(422, '分页位置无效'); }
  }
  const encode = row => btoa(JSON.stringify({t: row.created_at, i: row.id, p: row.pinned || 0}))
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
  function limit(url) {
    const size = Number(url.searchParams.get('limit') || 30);
    if (!Number.isInteger(size) || size < 1 || size > 50) fail(422, '每页数量应为 1–50');
    return size;
  }
  async function actor(env, owner, admin) {
    if (admin) {
      await env.DB.prepare("INSERT OR IGNORE INTO community_accounts(id,uid,nickname,created_at) VALUES('developer','000000000001','开发者',0)").run();
      return {id_hash: 'developer', uid: '000000000001', nickname: '开发者'};
    }
    if (!owner) fail(401, '请先建立社区身份');
    return owner;
  }
  async function post(env, postId, owner, admin) {
    const row = await env.DB.prepare(`SELECT ${postColumns} FROM ${postJoin} WHERE p.id=?`).bind(postId).first();
    if (!row || (row.hidden && !admin && owner?.id_hash !== row.author_id)) fail(404, '帖子不存在或已删除');
    return row;
  }
  async function decorate(env, rows, owner, admin) {
    if (!rows.length) return [];
    const ids = rows.map(row => row.id), marks = ids.map(() => '?').join(',');
    const [images, options, ownVotes, likes] = await env.DB.batch([
      env.DB.prepare(`SELECT l.post_id,m.id,m.width,m.height FROM community_attachments l JOIN community_media m ON m.id=l.media_id WHERE l.post_id IN (${marks}) AND l.comment_id IS NULL ORDER BY m.created_at,m.id`).bind(...ids),
      env.DB.prepare(`SELECT o.id,o.post_id,o.label,o.position,p.expires_at,COUNT(v.account_id) AS votes FROM community_poll_options o JOIN community_polls p ON p.post_id=o.post_id LEFT JOIN community_votes v ON v.option_id=o.id AND v.post_id=o.post_id WHERE o.post_id IN (${marks}) GROUP BY o.id ORDER BY o.position`).bind(...ids),
      env.DB.prepare(`SELECT post_id,option_id FROM community_votes WHERE post_id IN (${marks}) AND account_id=?`).bind(...ids, owner?.id_hash || ''),
      env.DB.prepare(`SELECT post_id FROM community_likes WHERE post_id IN (${marks}) AND account_id=?`).bind(...ids, owner?.id_hash || ''),
    ]);
    return rows.map(row => {
      const choices = options.results.filter(x => x.post_id === row.id);
      const vote = ownVotes.results.find(x => x.post_id === row.id)?.option_id || null;
      return {id: row.id, body: row.body, category: row.category, product: row.product,
        author: publicAccount(row), mine: owner?.id_hash === row.author_id, developer: Boolean(row.developer),
        pinned: Boolean(row.pinned), hidden: Boolean(row.hidden), locked: Boolean(row.locked), status: row.status,
        commentCount: row.comment_count, likeCount: row.like_count, liked: likes.results.some(x => x.post_id === row.id),
        createdAt: row.created_at, updatedAt: row.updated_at,
        images: images.results.filter(x => x.post_id === row.id).map(x => ({id: x.id, width: x.width, height: x.height,
          url: `${admin ? '/admin' : ''}/media/${x.id}`})),
        poll: choices.length ? {expiresAt: choices[0].expires_at, closed: choices[0].expires_at <= stamp(),
          yourVote: vote, total: choices.reduce((sum, x) => sum + x.votes, 0),
          options: choices.map(x => ({id: x.id, label: x.label, votes: x.votes}))} : null};
    });
  }
  async function list(env, url, owner, admin) {
    const size = limit(url), cursor = decode(url.searchParams.get('cursor'));
    const conditions = [admin && url.searchParams.get('include_hidden') === '1' ? '1=1' : 'p.hidden=0'], params = [];
    const category = url.searchParams.get('category');
    if (category) { if (!categories.has(category)) fail(422, '分类无效'); conditions.push('p.category=?'); params.push(category); }
    if (url.searchParams.get('mine') === '1') {
      if (!owner) fail(401, '请先建立社区身份');
      conditions.push('p.author_id=?'); params.push(owner.id_hash);
    }
    if (url.searchParams.has('uid')) {
      const uid = url.searchParams.get('uid');
      if (!/^[a-f0-9]{12}$/.test(uid)) fail(422, 'UID 无效');
      conditions.push('a.uid=?'); params.push(uid);
    }
    if (cursor) {
      conditions.push('(p.pinned<? OR (p.pinned=? AND (p.created_at<? OR (p.created_at=? AND p.id<?))))');
      params.push(cursor.p, cursor.p, cursor.t, cursor.t, cursor.i);
    }
    const {results} = await env.DB.prepare(`SELECT ${postColumns} FROM ${postJoin} WHERE ${conditions.join(' AND ')} ORDER BY p.pinned DESC,p.created_at DESC,p.id DESC LIMIT ?`)
      .bind(...params, size + 1).all();
    const rows = results.slice(0, size);
    return secureResponse({items: await decorate(env, rows, owner, admin), next: results.length > size ? encode(rows.at(-1)) : null});
  }
  async function createPost(request, env, owner, admin) {
    const key = uniqueKey(request), body = await readJson(request);
    const existing = await env.DB.prepare('SELECT id FROM community_posts WHERE author_id=? AND dedupe_key=?').bind(owner.id_hash, key).first();
    if (existing) return secureResponse({id: existing.id});
    await rate(request, env, 'post', 15, 600);
    if (!admin) await botCheck(request, env, body);
    const content = text(body.body || '', 3000, 0), category = body.category || 'discussion';
    if (!categories.has(category)) fail(422, '分类无效');
    const product = body.product || 'yunget';
    if (!['yunget', 'turbodl'].includes(product)) fail(422, '项目无效');
    const imageIds = await media.validate(env, owner, body.images, 4);
    if (!content && !imageIds.length) fail(422, '请填写正文或添加图片');
    const postId = id('p'), now = stamp();
    const statements = [env.DB.prepare('INSERT INTO community_posts(id,author_id,body,category,product,developer,dedupe_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)')
      .bind(postId, owner.id_hash, content, category, product, Number(Boolean(admin)), key, now, now)];
    if (body.poll !== undefined && body.poll !== null) {
      if (!content || !body.poll || !Array.isArray(body.poll.options)) fail(422, '请填写投票问题和选项');
      const options = body.poll.options.map(x => text(x, 60));
      const hours = Number(body.poll.durationHours || 168);
      if (options.length < 2 || options.length > 6 || new Set(options).size !== options.length ||
          !Number.isInteger(hours) || hours < 1 || hours > 720) fail(422, '投票需要 2–6 个不同选项，时长为 1–720 小时');
      statements.push(env.DB.prepare('INSERT INTO community_polls(post_id,expires_at) VALUES(?,?)').bind(postId, now + hours * 3600));
      options.forEach((label, index) => statements.push(env.DB.prepare('INSERT INTO community_poll_options(id,post_id,label,position) VALUES(?,?,?,?)').bind(id('o'), postId, label, index)));
    }
    statements.push(...media.attach(env, owner, imageIds, postId));
    await publish(env, statements);
    return secureResponse({id: postId}, 201);
  }
  async function comments(env, url, postId, owner, admin) {
    await post(env, postId, owner, admin);
    const size = limit(url), after = decode(url.searchParams.get('after')), before = decode(url.searchParams.get('before'));
    let around = url.searchParams.get('around');
    if (Number(Boolean(after)) + Number(Boolean(before)) + Number(Boolean(around)) > 1) fail(422, '分页条件冲突');
    const columns = 'c.id,c.body,c.parent_id,c.created_at,c.developer,c.author_id,a.uid,a.nickname,pa.uid AS parent_uid,pa.nickname AS parent_nickname,CASE WHEN pc.hidden=0 THEN substr(pc.body,1,80) ELSE NULL END AS parent_preview';
    const join = 'community_comments c JOIN community_accounts a ON a.id=c.author_id LEFT JOIN community_comments pc ON pc.id=c.parent_id LEFT JOIN community_accounts pa ON pa.id=pc.author_id';
    const select = async (cursor, reverse, count) => {
      const sign = reverse ? '<' : '>', order = reverse ? 'DESC' : 'ASC';
      const where = cursor ? ` AND (c.created_at${sign}? OR (c.created_at=? AND c.id${sign}?))` : '';
      const params = cursor ? [postId, cursor.t, cursor.t, cursor.i, count] : [postId, count];
      return (await env.DB.prepare(`SELECT ${columns} FROM ${join} WHERE c.post_id=? AND c.hidden=0${where} ORDER BY c.created_at ${order},c.id ${order} LIMIT ?`).bind(...params).all()).results;
    };
    let rows;
    if (around) {
      const anchor = await env.DB.prepare(`SELECT ${columns} FROM ${join} WHERE c.id=? AND c.post_id=? AND c.hidden=0`).bind(around, postId).first();
      if (!anchor) fail(404, '这条评论已不可见');
      const cursor = {t: anchor.created_at, i: anchor.id};
      const olderCount = Math.floor((size - 1) / 2);
      const [older, newer] = await Promise.all([select(cursor, true, olderCount), select(cursor, false, size - 1 - olderCount)]);
      rows = [...older.reverse(), anchor, ...newer];
    } else {
      rows = (await select(after || before, Boolean(before), size)).slice(0, size);
      if (before) rows.reverse();
    }
    let previous = null, next = null;
    if (rows.length) {
      const first = rows[0], last = rows.at(-1);
      const [older, newer] = await Promise.all([select({t: first.created_at, i: first.id}, true, 1), select({t: last.created_at, i: last.id}, false, 1)]);
      if (older.length) previous = encode(first);
      if (newer.length) next = encode(last);
    }
    const images = await media.forComments(env, rows.map(x => x.id), admin);
    return secureResponse({items: rows.map(row => ({id: row.id, body: row.body, author: publicAccount(row),
      createdAt: row.created_at, mine: row.author_id === owner?.id_hash, developer: Boolean(row.developer),
      parent: row.parent_id ? {id: row.parent_id, uid: row.parent_uid, nickname: row.parent_nickname, preview: row.parent_preview} : null,
      images: images.filter(x => x.comment_id === row.id).map(({comment_id, ...image}) => image)})), previous, next});
  }
  async function createComment(request, env, postId, owner, admin) {
    const body = await readJson(request), key = uniqueKey(request);
    const existing = await env.DB.prepare('SELECT id,post_id FROM community_comments WHERE author_id=? AND dedupe_key=?').bind(owner.id_hash, key).first();
    if (existing) {
      if (existing.post_id !== postId) fail(409, '此请求标识已用于另一帖子');
      return secureResponse({id: existing.id, postId});
    }
    const source = await post(env, postId, owner, admin);
    if (source.hidden || source.locked) fail(409, '帖子已关闭评论');
    await rate(request, env, 'comment', 45, 600);
    if (!admin) await botCheck(request, env, body);
    const content = text(body.body || '', 2000, 0), images = await media.validate(env, owner, body.images, 2);
    if (!content && !images.length) fail(422, '请填写评论或添加图片');
    const parent = body.parentId || null;
    if (parent && !await env.DB.prepare('SELECT id FROM community_comments WHERE id=? AND post_id=? AND hidden=0').bind(parent, postId).first()) fail(422, '回复目标无效');
    const commentId = id('c');
    const statements = [env.DB.prepare('INSERT INTO community_comments(id,post_id,author_id,parent_id,body,developer,dedupe_key,created_at) SELECT ?,?,?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM community_posts WHERE id=? AND hidden=0 AND locked=0)')
      .bind(commentId, postId, owner.id_hash, parent, content, Number(Boolean(admin)), key, stamp(), postId),
      ...media.attach(env, owner, images, postId, commentId)];
    const results = await publish(env, statements);
    if (!results[0].meta.changes) fail(409, '帖子已关闭评论');
    return secureResponse({id: commentId, postId}, 201);
  }
  async function handle(request, env, context) {
    const url = new URL(request.url), admin = context.admin, path = url.pathname.replace(/^\/admin(?=\/api\/|\/media\/)/, '');
    if (!path.startsWith('/api/posts') && !path.startsWith('/api/notifications') && !path.startsWith('/media/') && path !== '/api/media') return null;
    const owner = admin ? await actor(env, context.owner, admin) : context.owner;
    const mediaResponse = await media.handle(request, env, {owner, admin, path});
    if (mediaResponse) return mediaResponse;
    const inboxResponse = await inbox.handle(request, env, {owner, admin, path});
    if (inboxResponse) return inboxResponse;
    if (path === '/api/posts') {
      if (request.method === 'GET') return list(env, url, owner, admin);
      if (request.method === 'POST') return createPost(request, env, await actor(env, owner, admin), admin);
    }
    const match = /^\/api\/posts\/(p_[a-f0-9]{32})(?:\/(comments|like|vote|moderate))?$/.exec(path);
    if (!match) return null;
    const [, postId, operation] = match;
    if (request.method === 'GET') {
      if (operation === 'comments') return comments(env, url, postId, owner, admin);
      if (!operation) return secureResponse({post: (await decorate(env, [await post(env, postId, owner, admin)], owner, admin))[0]});
    }
    const writer = await actor(env, owner, admin), source = await post(env, postId, writer, admin);
    if (request.method === 'POST' && operation === 'comments') return createComment(request, env, postId, writer, admin);
    if (request.method === 'POST' && operation === 'like') {
      const body = await readJson(request);
      if (typeof body.liked !== 'boolean' || source.hidden) fail(422, '点赞状态无效');
      await rate(request, env, 'like', 90, 60);
      if (body.liked) await env.DB.prepare('INSERT OR IGNORE INTO community_likes(post_id,account_id,created_at) VALUES(?,?,?)').bind(postId, writer.id_hash, stamp()).run();
      else await env.DB.prepare('DELETE FROM community_likes WHERE post_id=? AND account_id=?').bind(postId, writer.id_hash).run();
      return secureResponse({post: (await decorate(env, [await post(env, postId, writer, admin)], writer, admin))[0]});
    }
    if (request.method === 'POST' && operation === 'vote') {
      const body = await readJson(request);
      await rate(request, env, 'vote', 40, 60);
      if (source.hidden) fail(404, '帖子不可见');
      const result = await env.DB.prepare('INSERT INTO community_votes(post_id,account_id,option_id,created_at) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM community_poll_options o JOIN community_polls p ON p.post_id=o.post_id WHERE o.id=? AND p.post_id=? AND p.expires_at>?) ON CONFLICT(post_id,account_id) DO UPDATE SET option_id=excluded.option_id,created_at=excluded.created_at')
        .bind(postId, writer.id_hash, String(body.optionId || ''), stamp(), String(body.optionId || ''), postId, stamp()).run();
      if (!result.meta.changes) fail(409, '投票已结束或选项无效');
      return secureResponse({post: (await decorate(env, [await post(env, postId, writer, admin)], writer, admin))[0]});
    }
    if (request.method === 'DELETE' && !operation) {
      if (!admin && source.author_id !== writer.id_hash) fail(403, '只能删除自己的帖子');
      await env.DB.prepare('UPDATE community_posts SET hidden=1,updated_at=? WHERE id=?').bind(stamp(), postId).run();
      return secureResponse({ok: true});
    }
    if (request.method === 'PATCH' && operation === 'moderate' && admin) {
      const body = await readJson(request), updates = [], params = [];
      for (const field of ['pinned', 'hidden', 'locked']) if (body[field] !== undefined) {
        if (typeof body[field] !== 'boolean') fail(422, '开关值无效');
        updates.push(field + '=?'); params.push(Number(body[field]));
      }
      if (body.status !== undefined) { if (!states.has(body.status)) fail(422, '状态无效'); updates.push('status=?'); params.push(body.status); }
      if (!updates.length) fail(422, '没有修改内容');
      await env.DB.batch([
        env.DB.prepare(`UPDATE community_posts SET ${updates.join(',')},updated_at=? WHERE id=?`).bind(...params, stamp(), postId),
        env.DB.prepare('INSERT INTO moderation_log(action,target_id,created_at) VALUES(?,?,?)').bind('post:moderate', postId, stamp()),
      ]);
      return secureResponse({ok: true});
    }
    if (request.method === 'DELETE' && operation === 'comments') {
      const body = await readJson(request);
      const comment = await env.DB.prepare('SELECT author_id FROM community_comments WHERE id=? AND post_id=?').bind(String(body.id || ''), postId).first();
      if (!comment || (!admin && comment.author_id !== writer.id_hash)) fail(403, '只能删除自己的评论');
      await env.DB.prepare('UPDATE community_comments SET hidden=1 WHERE id=? AND post_id=?').bind(body.id, postId).run();
      return secureResponse({ok: true});
    }
    return null;
  }
  return {handle};
}
