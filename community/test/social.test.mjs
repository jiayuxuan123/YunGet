import assert from 'node:assert/strict';
import test from 'node:test';
import {readFileSync} from 'node:fs';
import {socialFixture} from './social-fixture.mjs';
import {sanitizeImage} from '../social-media.mjs';

const png = new Uint8Array(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jYZYAAAAASUVORK5CYII=', 'base64'));

test('UID and recovery survive cookie changes without trusting a nickname or public UID', async () => {
  const f = socialFixture();
  try {
    const response = await f.request('/api/session', {method: 'POST', body: {nickname: '同名用户', challenge: await f.proof('/api/session')}});
    assert.equal(response.status, 201);
    const identity = await response.json(), cookie = response.headers.get('Set-Cookie').split(';')[0];
    assert.match(identity.uid, /^[a-f0-9]{12}$/);
    assert.equal(identity.nickname, '同名用户');
    assert.ok(identity.recoveryCode);
    assert.doesNotMatch(JSON.stringify(f.sqlite.prepare('SELECT * FROM community_accounts').all()), new RegExp(identity.recoveryCode));
    const restore = await f.request('/api/account/recover', {method: 'POST', body: {recoveryCode: identity.recoveryCode, challenge: await f.proof('/api/account/recover')}});
    assert.equal(restore.status, 200);
    assert.equal((await restore.json()).uid, identity.uid);
    assert.notEqual(restore.headers.get('Set-Cookie').split(';')[0], cookie);
    const invalid = await f.request('/api/account/recover', {method: 'POST', body: {recoveryCode: identity.uid, challenge: await f.proof('/api/account/recover')}});
    assert.equal(invalid.status, 401);
    assert.match((await f.request('/api/session', {cookie})).headers.get('Set-Cookie'), /HttpOnly; Secure; SameSite=Lax/);
  } finally { f.close(); }
});

test('additive migration and UID association keep legacy private feedback with the same author', async () => {
  const fresh = readFileSync(new URL('../schema.sql', import.meta.url), 'utf8').replaceAll('\r\n', '\n');
  const old = fresh.split('CREATE TABLE IF NOT EXISTS community_accounts')[0].replace('  account_id TEXT,\n', '');
  const f = socialFixture(old);
  try {
    const owner = await f.member('旧昵称', true), feedbackId = crypto.randomUUID(), now = Math.floor(Date.now() / 1000);
    f.sqlite.prepare('INSERT INTO feedback(id,author_id,nickname,product,category,title,body,private,dedupe_key,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)')
      .run(feedbackId, owner.id, owner.nickname, 'yunget', 'bug', '私密反馈', '仅原作者可见', 1, crypto.randomUUID(), now, now);
    f.sqlite.exec(readFileSync(new URL('../migrations/0003_social_community.sql', import.meta.url), 'utf8'));
    const who = await (await f.request('/api/session', {cookie: owner.cookie})).json();
    assert.match(who.uid, /^[a-f0-9]{12}$/);
    assert.equal(f.sqlite.prepare('SELECT author_id FROM feedback').get().author_id, owner.id);
    assert.equal((await f.request('/api/feedback/' + feedbackId, {cookie: owner.cookie})).status, 200);
    const stranger = await f.member('别人');
    assert.notEqual((await f.request('/api/feedback/' + feedbackId, {cookie: stranger.cookie})).status, 200);
    const codeResponse = await f.request('/api/account/recovery', {method: 'POST', cookie: owner.cookie, body: {challenge: await f.proof('/api/account/recovery')}});
    assert.equal(codeResponse.status, 200);
    const code = (await codeResponse.json()).recoveryCode;
    const restored = await f.request('/api/account/recover', {method: 'POST', body: {recoveryCode: code, challenge: await f.proof('/api/account/recover')}});
    const cookie = restored.headers.get('Set-Cookie').split(';')[0];
    assert.equal((await f.request('/api/feedback/' + feedbackId, {cookie})).status, 200);
  } finally { f.close(); }
});

test('posts and comments are idempotent and produce one owner notification', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member('甲'), bob = await f.member('乙'), postId = await f.post(alice);
    const path = '/api/posts/' + postId + '/comments', key = crypto.randomUUID();
    const body = {body: '正常评论', challenge: await f.proof(path)};
    const first = await f.request(path, {method: 'POST', cookie: bob.cookie, key, body});
    assert.equal(first.status, 201);
    const second = await f.request(path, {method: 'POST', cookie: bob.cookie, key, body});
    assert.equal(second.status, 200);
    assert.equal((await first.json()).id, (await second.json()).id);
    assert.equal(f.sqlite.prepare('SELECT comment_count FROM community_posts').get().comment_count, 1);
    const inbox = await (await f.request('/api/notifications', {cookie: alice.cookie})).json();
    assert.equal(inbox.items.length, 1);
    assert.equal(inbox.items[0].actor.uid, bob.uid);
    assert.equal(inbox.items[0].postId, postId);
    assert.equal((await (await f.request('/api/notifications', {cookie: bob.cookie})).json()).items.length, 0);
  } finally { f.close(); }
});

test('5000 tied-timestamp comments use stable indexed cursors and bounded pages', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member(), bob = await f.member(), postId = await f.post(alice), now = Math.floor(Date.now() / 1000);
    const insert = f.sqlite.prepare('INSERT INTO community_comments(id,post_id,author_id,body,dedupe_key,created_at) VALUES(?,?,?,?,?,?)');
    f.sqlite.exec('BEGIN');
    for (let i = 0; i < 5000; i++) insert.run('c_' + i.toString(16).padStart(32, '0'), postId, bob.id, '评论 ' + i, 'seed-' + i, now);
    f.sqlite.exec('COMMIT');
    let cursor = null; const seen = new Set(); let pages = 0;
    do {
      const response = await f.request('/api/posts/' + postId + '/comments?limit=50' + (cursor ? '&after=' + cursor : ''));
      assert.equal(response.status, 200);
      const page = await response.json(); assert.ok(page.items.length <= 50);
      for (const row of page.items) { assert.ok(!seen.has(row.id)); seen.add(row.id); }
      cursor = page.next; pages++; assert.ok(pages <= 100);
    } while (cursor);
    assert.equal(seen.size, 5000);
    const anchor = 'c_' + (4500).toString(16).padStart(32, '0');
    const around = await (await f.request('/api/posts/' + postId + '/comments?around=' + anchor + '&limit=30')).json();
    assert.equal(around.items.length, 30); assert.ok(around.items.some(x => x.id === anchor));
    assert.ok(around.previous && around.next);
    const plan = f.sqlite.prepare('EXPLAIN QUERY PLAN SELECT id FROM community_comments WHERE post_id=? AND hidden=0 AND (created_at>? OR (created_at=? AND id>?)) ORDER BY created_at,id LIMIT 50').all(postId, now, now, anchor);
    assert.match(JSON.stringify(plan), /community_comment_stream/);
    assert.equal(f.sqlite.prepare('SELECT comment_count FROM community_posts').get().comment_count, 5000);
  } finally { f.close(); }
});

test('polls enforce one vote per UID, changes, deadline and option ownership', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member(), bob = await f.member();
    const postId = await f.post(alice, {body: '选择下一步', poll: {options: ['稳定性', '界面'], durationHours: 24}});
    const options = f.sqlite.prepare('SELECT id FROM community_poll_options ORDER BY position').all();
    const vote = optionId => f.request('/api/posts/' + postId + '/vote', {method: 'POST', cookie: bob.cookie, body: {optionId}});
    assert.equal((await vote(options[0].id)).status, 200);
    assert.equal((await vote(options[0].id)).status, 200);
    assert.equal(f.sqlite.prepare('SELECT COUNT(*) AS n FROM community_votes').get().n, 1);
    const changed = await (await vote(options[1].id)).json();
    assert.equal(changed.post.poll.total, 1); assert.equal(changed.post.poll.options[0].votes, 0);
    assert.equal(changed.post.poll.yourVote, options[1].id);
    assert.equal((await vote('o_' + 'a'.repeat(32))).status, 409);
    f.sqlite.prepare('UPDATE community_polls SET expires_at=1').run();
    assert.equal((await vote(options[0].id)).status, 409);
    assert.equal(f.sqlite.prepare('SELECT option_id FROM community_votes').get().option_id, options[1].id);
  } finally { f.close(); }
});

test('images are validated and bound to their uploader before public publication', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member(), bob = await f.member();
    const upload = await f.request('/api/media', {method: 'POST', cookie: alice.cookie, body: png, headers: {'Content-Type': 'image/png'}});
    assert.equal(upload.status, 201);
    const image = await upload.json();
    assert.equal((await f.request(image.url)).status, 404);
    assert.equal((await f.request(image.url, {cookie: alice.cookie})).status, 200);
    const stolen = await f.request('/api/posts', {method: 'POST', cookie: bob.cookie, key: crypto.randomUUID(), body: {body: '不能使用别人的图片', images: [image.id], challenge: await f.proof('/api/posts')}});
    assert.equal(stolen.status, 403);
    const postId = await f.post(alice, {images: [image.id]});
    const publicImage = await f.request(image.url); assert.equal(publicImage.status, 200);
    assert.equal(publicImage.headers.get('Content-Type'), 'image/png');
    const detail = await (await f.request('/api/posts/' + postId)).json();
    assert.equal(detail.post.images.length, 1);
    const invalid = await f.request('/api/media', {method: 'POST', cookie: alice.cookie, body: new TextEncoder().encode('<svg onload="alert(1)"/>'), headers: {'Content-Type': 'image/png'}});
    assert.equal(invalid.status, 422);
    const large = await f.request('/api/media', {method: 'POST', cookie: alice.cookie, body: new Uint8Array(513 * 1024), headers: {'Content-Type': 'image/png'}});
    assert.equal(large.status, 413);
  } finally { f.close(); }
});

test('private image data never appears in feed queries or anonymous notifications', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member(), bob = await f.member(), postId = await f.post(alice);
    assert.equal((await f.request('/api/notifications')).status, 200);
    const anonymous = await (await f.request('/api/notifications')).json(); assert.deepEqual(anonymous.items, []);
    await f.request('/api/posts/' + postId + '/like', {method: 'POST', cookie: bob.cookie, body: {liked: true}});
    await f.request('/api/posts/' + postId + '/like', {method: 'POST', cookie: bob.cookie, body: {liked: true}});
    assert.equal(f.sqlite.prepare('SELECT like_count FROM community_posts').get().like_count, 1);
    assert.notEqual((await f.request('/api/posts/' + postId, {method: 'DELETE', cookie: bob.cookie})).status, 200);
    assert.equal((await f.request('/api/posts/' + postId, {method: 'DELETE', cookie: alice.cookie})).status, 200);
    assert.equal((await f.request('/api/posts/' + postId)).status, 404);
    assert.equal((await (await f.request('/api/posts')).json()).items.length, 0);
  } finally { f.close(); }
});

test('developer management stays server-authorized and not based on a posted nickname', async () => {
  const f = socialFixture();
  try {
    const alice = await f.member('普通用户'), postId = await f.post(alice, {developer: true, pinned: true});
    const original = await (await f.request('/api/posts/' + postId)).json();
    assert.equal(original.post.developer, false); assert.equal(original.post.pinned, false);
    assert.equal((await f.request('/admin/api/posts/' + postId + '/moderate', {method: 'PATCH', cookie: alice.cookie, body: {pinned: true}})).status, 401);
    assert.equal((await f.request('/admin/api/posts/' + postId + '/moderate', {method: 'PATCH', admin: true, body: {pinned: true, status: 'working'}})).status, 200);
    const updated = await (await f.request('/api/posts/' + postId)).json(); assert.equal(updated.post.pinned, true);
    assert.equal((await (await f.request('/api/notifications', {cookie: alice.cookie})).json()).items[0].kind, 'post_status');
    const signup = await f.request('/api/session', {method: 'POST', body: {nickname: '官方开发者', challenge: await f.proof('/api/session')}});
    assert.equal(signup.status, 422);
  } finally { f.close(); }
});

test('image parsing rejects broken headers, unbounded dimensions and appended payloads', () => {
  assert.equal(sanitizeImage(png).width, 1);
  const oversized = png.slice(); new DataView(oversized.buffer).setUint32(16, 50000);
  assert.throws(() => sanitizeImage(oversized), /尺寸/);
  assert.throws(() => sanitizeImage(png.slice(0, 32)), /无效|不完整/);
  assert.throws(() => sanitizeImage(new Uint8Array([...png, 1, 2, 3])), /无效/);
});
