import assert from 'node:assert/strict';
import test from 'node:test';

import {createAdminVerifier} from '../auth.mjs';

const encode = bytes => Buffer.from(bytes).toString('base64url');
const encoder = new TextEncoder();

async function signedToken({audience, kid = 'test-key', issuer = 'https://test.cloudflareaccess.com'}) {
  const pair = await crypto.subtle.generateKey(
    {name:'RSASSA-PKCS1-v1_5', modulusLength:2048, publicExponent:new Uint8Array([1,0,1]), hash:'SHA-256'},
    true, ['sign','verify'],
  );
  const publicJwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
  Object.assign(publicJwk, {kid, use:'sig', alg:'RS256'});
  const header = encode(encoder.encode(JSON.stringify({alg:'RS256', kid, typ:'JWT'})));
  const now = Math.floor(Date.now()/1000);
  const payload = encode(encoder.encode(JSON.stringify({
    iss:issuer, aud:[audience], exp:now+300, iat:now-10, nbf:now-10,
    email:'developer@example.com', type:'app', sub:'developer',
  })));
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, encoder.encode(`${header}.${payload}`));
  return {token:`${header}.${payload}.${encode(new Uint8Array(signature))}`, publicJwk};
}

test('verifies a signed Access assertion for the configured audience', async () => {
  const signed = await signedToken({audience:'community-aud'});
  const verify = createAdminVerifier(async () => new Response(JSON.stringify({keys:[signed.publicJwk]}), {status:200}));
  const request = new Request('https://community.example.com/admin/api/session', {
    headers:{'Cf-Access-Jwt-Assertion':signed.token},
  });
  assert.deepEqual(await verify(request, {
    ACCESS_AUD:'community-aud', ACCESS_ISSUER:'https://test.cloudflareaccess.com',
  }), {developer:true});
});

test('rejects a valid signature for another Access application', async () => {
  const signed = await signedToken({audience:'other-aud'});
  const verify = createAdminVerifier(async () => new Response(JSON.stringify({keys:[signed.publicJwk]}), {status:200}));
  const request = new Request('https://community.example.com/admin/api/session', {
    headers:{'Cf-Access-Jwt-Assertion':signed.token},
  });
  assert.equal(await verify(request, {
    ACCESS_AUD:'community-aud', ACCESS_ISSUER:'https://test.cloudflareaccess.com',
  }), null);
});

test('rejects missing and malformed assertions without fetching keys', async () => {
  const verify = createAdminVerifier(async () => { throw new Error('must not fetch keys'); });
  assert.equal(await verify(new Request('https://community.example.com/admin'), {
    ACCESS_AUD:'community-aud', ACCESS_ISSUER:'https://test.cloudflareaccess.com',
  }), null);
  const request = new Request('https://community.example.com/admin', {
    headers:{'Cf-Access-Jwt-Assertion':'one.two.three'},
  });
  assert.equal(await verify(request, {
    ACCESS_AUD:'community-aud', ACCESS_ISSUER:'https://test.cloudflareaccess.com',
  }), null);
});
