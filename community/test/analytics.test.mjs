import assert from 'node:assert/strict';
import test from 'node:test';
import {createHandler} from '../worker.mjs';

function retiredEnv() {
  let touches = 0;
  return {
    env: {
      DB: {
        prepare() { touches += 1; throw new Error('retired analytics endpoint touched D1'); },
        batch() { touches += 1; throw new Error('retired analytics endpoint touched D1'); },
      },
      PUBLIC_ORIGIN: 'https://community.example.com',
      COMMUNITY_VERIFICATION_SECRET: 'synthetic-analytics-test-secret-not-used-in-production',
    },
    touches: () => touches,
  };
}

test('developer analytics dashboard reports the Google Analytics migration without D1', async () => {
  const setup = retiredEnv();
  const handle = createHandler({verifyAdmin: async () => ({developer: true})});
  const response = await handle(new Request('https://community.example.com/admin/api/analytics?days=7'), setup.env);
  assert.equal(response.status, 410);
  assert.deepEqual(await response.json(), {
    migrated: true,
    provider: 'google-analytics',
    message: '匿名统计已迁移至 Google Analytics，旧 D1 数据不再提供查询。',
  });
  assert.equal(setup.touches(), 0);
});

test('retired analytics endpoints never touch D1', async () => {
  const setup = retiredEnv();
  const handle = createHandler({verifyAdmin: async () => ({developer: true})});
  const legacy = await handle(new Request('https://community.example.com/api/analytics', {
    method: 'POST',
    headers: {'Content-Type': 'application/json', 'X-YunGet-Analytics': '1'},
    body: JSON.stringify({install_id: '10000000-0000-4000-8000-000000000001', events: []}),
  }), setup.env);
  assert.equal(legacy.status, 202);
  assert.deepEqual(await legacy.json(), {accepted: 0, disabled: true});
  assert.equal(setup.touches(), 0);
});