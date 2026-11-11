import assert from 'node:assert/strict';
import test from 'node:test';

import {createHandler} from '../worker.mjs';

const env = {
  DB:{},
  PUBLIC_ORIGIN:'https://community.example.com',
  COMMUNITY_VERIFICATION_SECRET:'local-unit-test-secret-only-01234567890123',
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

test('exposes only same-origin verification configuration', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/api/config'), env);
  assert.equal(response.status, 200);
  const config=await response.json();
  assert.equal(config.verification.provider,'altcha');
  assert.equal(config.verification.challengeUrl,'/api/verification/challenge');
  assert.equal(config.verification.script,'/verification/sdk-3.5.0.mjs');
  assert.equal('COMMUNITY_VERIFICATION_SECRET' in config,false);
  assert.equal('sitekey' in config,false);
});

test('analytics dashboard is protected by developer auth and served as a separate page', async () => {
  const denied = createHandler({verifyAdmin:async()=>null});
  const deniedResponse = await denied(new Request('https://community.example.com/admin/analytics'), env);
  assert.equal(deniedResponse.status, 401);

  const handle = createHandler({verifyAdmin:async()=>({email:'developer@example.com'})});
  const page = await handle(new Request('https://community.example.com/admin/analytics'), env);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /云取匿名统计/);
});

test('analytics ingestion requires the dedicated app header before using the database', async () => {
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/api/analytics', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body:'{}',
  }), env);
  assert.equal(response.status, 403);
  assert.deepEqual(await response.json(), {error:'统计请求来源无效'});
});

test('analytics rejects oversized batches before inserting events', async () => {
  const database = {
    prepare:()=>({
      bind(){return this;},
      async first(){return {hits:1};},
    }),
  };
  const handle = createHandler({verifyAdmin:async()=>null});
  const response = await handle(new Request('https://community.example.com/api/analytics', {
    method:'POST',
    headers:{'Content-Type':'application/json','X-YunGet-Analytics':'1'},
    body:JSON.stringify({
      install_id:'10000000-0000-4000-8000-000000000001',
      events:Array.from({length:21},(_,index)=>({
        event_id:`20000000-0000-4000-8000-${String(index).padStart(12,'0')}`,
        event:'app_open',
        product:'yunget',
      })),
    }),
  }), {...env, DB:database});
  assert.equal(response.status, 422);
  assert.deepEqual(await response.json(), {error:'统计事件数量无效'});
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
