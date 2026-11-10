import {createChallenge, randomInt, verifySolution} from 'altcha-lib';
import {deriveKey} from 'altcha-lib/algorithms/pbkdf2';

export const VERIFICATION_LIFETIME = 300;
export const verificationConfig = {
  provider: 'altcha', challengeUrl: '/api/verification/challenge', script: '/verification/sdk-3.5.0.mjs',
};
const scopes = new Set(['session', 'feedback', 'reply', 'chat']);
const encoder = new TextEncoder();
const now = () => Math.floor(Date.now() / 1000);
export class VerificationError extends Error {
  constructor(message, status = 403) { super(message); this.status = status; }
}

async function binding(request, origin) {
  const value = origin + '\n' + (request.headers.get('CF-Connecting-IP') || 'local');
  const digest = await crypto.subtle.digest('SHA-256', encoder.encode(value));
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}
function secret(env) {
  if (typeof env.COMMUNITY_VERIFICATION_SECRET !== 'string' || env.COMMUNITY_VERIFICATION_SECRET.length < 32) {
    throw new VerificationError('验证服务暂不可用，请稍后重试', 503);
  }
  return env.COMMUNITY_VERIFICATION_SECRET;
}
export function scopeForPath(path) {
  if (path === '/api/session') return 'session';
  if (path === '/api/feedback') return 'feedback';
  if (path === '/api/chat') return 'chat';
  if (/^\/api\/feedback\/[a-f0-9-]{36}\/replies$/.test(path)) return 'reply';
  throw new VerificationError('验证用途无效');
}
export async function issueVerification(request, env, scope) {
  if (!scopes.has(scope)) throw new VerificationError('验证用途无效', 422);
  return createChallenge({
    algorithm: 'PBKDF2/SHA-256', cost: 1500, counter: randomInt(64, 512), deriveKey,
    expiresAt: now() + VERIFICATION_LIFETIME,
    hmacSignatureSecret: secret(env),
    data: {scope, binding: await binding(request, env.PUBLIC_ORIGIN)},
  });
}

export async function consumeVerification(request, env, encoded, scope) {
  const key = secret(env);
  if (typeof encoded !== 'string' || encoded.length < 20 || encoded.length > 4096) throw new VerificationError('请完成验证后再提交');
  let payload;
  try { payload = JSON.parse(atob(encoded)); } catch { throw new VerificationError('验证结果无效，请重新验证'); }
  const challenge = payload?.challenge;
  const params = challenge?.parameters;
  const solution = payload?.solution;
  // Check cheap bounds before any crypto operation or database write.
  if (!params || params.algorithm !== 'PBKDF2/SHA-256' || params.cost !== 1500 || params.keyLength !== 32 ||
      !Number.isInteger(params.expiresAt) || params.expiresAt <= now() || params.expiresAt > now() + VERIFICATION_LIFETIME ||
      typeof challenge.signature !== 'string' || !/^[a-f0-9]{64}$/.test(challenge.signature) ||
      !/^[a-f0-9]{32}$/.test(params.keyPrefix || '') || !/^[a-f0-9]{32}$/.test(params.nonce || '') ||
      !/^[a-f0-9]{32}$/.test(params.salt || '') || !Number.isInteger(solution?.counter) || solution.counter < 0 || solution.counter >= 512 ||
      typeof solution.derivedKey !== 'string' || !/^[a-f0-9]{64}$/.test(solution.derivedKey) ||
      params.data?.scope !== scope || params.data?.binding !== await binding(request, env.PUBLIC_ORIGIN)) {
    throw new VerificationError('验证已过期或不匹配，请重新验证');
  }
  const result = await verifySolution({challenge, solution, deriveKey, hmacSignatureSecret: key});
  if (!result.verified) throw new VerificationError('验证结果无效，请重新验证');
  // D1's unique key makes replay rejection atomic across Worker isolates.
  const saved = await env.DB.prepare('INSERT INTO used_verifications(signature,expires_at) VALUES(?,?) ON CONFLICT(signature) DO NOTHING')
    .bind(challenge.signature, params.expiresAt).run();
  if (saved.meta.changes !== 1) throw new VerificationError('这次验证已使用，请重新验证');
}
