import assert from 'node:assert/strict';
import test from 'node:test';
import {solveChallenge} from 'altcha-lib';
import {deriveKey} from 'altcha-lib/algorithms/pbkdf2';
import {consumeVerification, issueVerification, scopeForPath, VerificationError} from '../verification.mjs';

function fixture() {
  const used = new Set();
  const env = {PUBLIC_ORIGIN:'https://community.example.com', COMMUNITY_VERIFICATION_SECRET:'local-test-secret-for-verification-only-0123456789',
    DB:{prepare:()=>({bind(signature){this.signature=signature;return this;},async run(){const fresh=!used.has(this.signature);used.add(this.signature);return {meta:{changes:Number(fresh)}};}})}};
  const request = new Request(env.PUBLIC_ORIGIN+'/api/session',{headers:{'CF-Connecting-IP':'192.0.2.1'}});
  return {env,request,used};
}
async function solution(f, scope='session') {
  const challenge=await issueVerification(f.request,f.env,scope);
  const answer=await solveChallenge({challenge,deriveKey});
  assert.ok(answer);
  return {challenge,solution:answer};
}
const encode = payload => btoa(JSON.stringify(payload));

test('self-hosted proof is accepted once without any external verification request',async()=>{
  const f=fixture();const payload=await solution(f);
  await consumeVerification(f.request,f.env,encode(payload),'session');
  assert.equal(f.used.size,1);
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(payload),'session'),/已使用/);
});

test('a concurrent replay can be consumed by only one Worker request',async()=>{
  const f=fixture();const payload=encode(await solution(f));
  const results=await Promise.allSettled([consumeVerification(f.request,f.env,payload,'session'),consumeVerification(f.request,f.env,payload,'session')]);
  assert.equal(results.filter(item=>item.status==='fulfilled').length,1);
  assert.equal(results.filter(item=>item.status==='rejected').length,1);
});

test('a client cannot claim completion or lower the signed work cost',async()=>{
  const f=fixture();
  await assert.rejects(()=>consumeVerification(f.request,f.env,'verified','session'),VerificationError);
  const payload=await solution(f);payload.challenge.parameters.cost=1;
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(payload),'session'),VerificationError);
  assert.equal(f.used.size,0);
});

test('signatures and solution keys are checked cryptographically',async()=>{
  const f=fixture();const payload=await solution(f);
  const badSignature=structuredClone(payload);badSignature.challenge.signature='0'.repeat(64);
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(badSignature),'session'),VerificationError);
  const badKey=structuredClone(payload);badKey.solution.derivedKey='0'.repeat(64);
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(badKey),'session'),VerificationError);
  assert.equal(f.used.size,0);
});

test('expired challenges and invalid counter ranges are rejected before consumption',async()=>{
  const f=fixture();const payload=await solution(f);
  const expired=structuredClone(payload);expired.challenge.parameters.expiresAt=Math.floor(Date.now()/1000)-1;
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(expired),'session'),VerificationError);
  const badCounter=structuredClone(payload);badCounter.solution.counter=512;
  await assert.rejects(()=>consumeVerification(f.request,f.env,encode(badCounter),'session'),VerificationError);
  assert.equal(f.used.size,0);
});

test('proofs cannot move between write operations or source connections',async()=>{
  const f=fixture();const payload=encode(await solution(f));
  await assert.rejects(()=>consumeVerification(f.request,f.env,payload,'chat'),VerificationError);
  const other=new Request(f.request.url,{headers:{'CF-Connecting-IP':'192.0.2.2'}});
  await assert.rejects(()=>consumeVerification(other,f.env,payload,'session'),VerificationError);
  assert.equal(f.used.size,0);
});

test('unexpected and oversized payloads cannot bypass validation',async()=>{
  const f=fixture();
  for(const payload of ['x'.repeat(4097),encode(null),encode([]),encode({challenge:{parameters:null}})]){
    await assert.rejects(()=>consumeVerification(f.request,f.env,payload,'session'),VerificationError);
  }
});

test('challenge secrets and write scopes must be explicitly valid',async()=>{
  const f=fixture();
  await assert.rejects(()=>issueVerification(f.request,{...f.env,COMMUNITY_VERIFICATION_SECRET:''},'session'),error=>error.status===503);
  await assert.rejects(()=>issueVerification(f.request,f.env,'admin'),error=>error.status===422);
  assert.equal(scopeForPath('/api/feedback/10000000-0000-4000-8000-000000000001/replies'),'reply');
  assert.equal(scopeForPath('/api/session'),'session');
  assert.equal(scopeForPath('/api/chat'),'chat');
  assert.throws(()=>scopeForPath('/admin/api/feedback'),VerificationError);
});
