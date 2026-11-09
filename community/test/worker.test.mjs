import assert from 'node:assert/strict';
import test from 'node:test';

import {createHandler} from '../worker.mjs';

const env = {
  DB:{},
  PUBLIC_ORIGIN:'https://community.example.com',
  TURNSTILE_SITEKEY:'test-sitekey',
};

test('serves the community shell and static assets with restrictive headers', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const page = await handle(new Request('https://community.example.com/'), env);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('Content-Type'), /text\/html/);
  assert.match(await page.text(), /云取社区/);
  assert.match(page.headers.get('Content-Security-Policy'), /frame-ancestors 'none'/);
  assert.equal(page.headers.get('X-Frame-Options'), 'DENY');

  const script = await handle(new Request('https://community.example.com/app.js'), env);
  assert.equal(script.status, 200);
  assert.match(script.headers.get('Content-Type'), /text\/javascript/);
});

test('requires developer authorization before admin APIs', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/admin/api/feedback'), env);
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), {error:'请先登录开发者账号'});
});

test('rejects cross-origin writes before parsing or using the database', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/api/feedback', {
    method:'POST',
    headers:{Origin:'https://attacker.example', 'Content-Type':'application/json'},
    body:'{}',
  }), env);
  assert.equal(response.status, 403);
  assert.deepEqual(await response.json(), {error:'请在社区页面内操作'});
});

test('exposes only the public Turnstile site key', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/api/config'), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {sitekey:'test-sitekey', version:'2.7.28'});
});

test('thread visibility is available to moderators and never exposes owner ids', async () => {
  const id='10000000-0000-4000-8000-000000000001';
  const row={id,author_id:'private-owner-id',nickname:'local-user',hidden:1,private:0,status:'new'};
  const database={prepare:()=>({bind(){return this;},async first(){return row;},async all(){return {results:[]};}})};
  const handle=createHandler({verifyAdmin:async()=>({email:'developer@example.com'})});
  const response=await handle(new Request('https://community.example.com/admin/api/feedback/'+id),{...env,DB:database});
  assert.equal(response.status,200);
  const payload=await response.json();
  assert.equal(payload.item.hidden,true);
  assert.equal('author_id' in payload.item,false);

  const publicHandle=createHandler({verifyAdmin:async()=>null});
  const publicResponse=await publicHandle(new Request('https://community.example.com/api/feedback/'+id),{...env,DB:database});
  assert.equal(publicResponse.status,404);
});
