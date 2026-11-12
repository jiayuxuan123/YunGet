const cookieName = '__Host-' + 'yunget-community';
const sessionLife = 30 * 86400;
const randomHex = size => [...crypto.getRandomValues(new Uint8Array(size))]
  .map(byte => byte.toString(16).padStart(2, '0')).join('');
const randomSecret = () => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))))
  .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');

function tokenFrom(request) {
  const token = request.headers.get('Cookie')?.split(';').map(x => x.trim())
    .find(x => x.startsWith(cookieName + '='))?.slice(cookieName.length + 1);
  return /^[a-f0-9]{64}$/.test(token || '') ? token : null;
}
function cookie(token, lifetime = sessionLife) {
  return `${cookieName}=${token}; Path=/; Max-Age=${lifetime}; HttpOnly; Secure; SameSite=Lax`;
}
function equalDigest(a, b) {
  let difference = a.length ^ b.length;
  for (let i = 0; i < 64; i++) difference |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return difference === 0;
}

export function createAccounts({fail, text, hash, stamp, secureResponse}) {
  const identity = owner => ({uid: owner?.uid || null, nickname: owner?.nickname || null,
    recoveryReady: Boolean(owner?.recoveryReady)});
  async function find(env, id) {
    return env.DB.prepare('SELECT id,uid,nickname,recovery_hash IS NOT NULL AS recovery_ready FROM community_accounts WHERE id=?')
      .bind(id).first();
  }
  async function session(request, env) {
    const token = tokenFrom(request);
    if (!token) return null;
    const sessionHash = await hash(token);
    const old = await env.DB.prepare('SELECT id_hash,nickname,account_id FROM sessions WHERE id_hash=? AND expires_at>?')
      .bind(sessionHash, stamp()).first();
    if (!old) return null;
    const accountId = old.account_id || old.id_hash;
    let account = await find(env, accountId);
    if (!account) {
      // Retain the legacy author id, so existing private feedback stays with its owner.
      await env.DB.prepare('INSERT OR IGNORE INTO community_accounts(id,uid,nickname,created_at) VALUES(?,?,?,?)')
        .bind(accountId, randomHex(6), old.nickname, stamp()).run();
      account = await find(env, accountId);
    }
    if (!old.account_id) await env.DB.prepare('UPDATE sessions SET account_id=? WHERE id_hash=? AND account_id IS NULL')
      .bind(accountId, sessionHash).run();
    return {id_hash: account.id, uid: account.uid, nickname: account.nickname,
      recoveryReady: Boolean(account.recovery_ready), session_hash: sessionHash};
  }
  async function who(request, env, owner) {
    if (!owner) return secureResponse(identity(null));
    await env.DB.prepare('UPDATE sessions SET expires_at=? WHERE id_hash=?')
      .bind(stamp() + sessionLife, owner.session_hash).run();
    return secureResponse(identity(owner), 200, {'Set-Cookie': cookie(tokenFrom(request))});
  }
  async function register(env, owner, rawNickname) {
    const nickname = text(rawNickname || '访客', 24);
    if (/(官方|管理员|开发者)/.test(nickname)) fail(422, '请使用个人昵称');
    if (owner) {
      await env.DB.batch([
        env.DB.prepare('UPDATE community_accounts SET nickname=? WHERE id=?').bind(nickname, owner.id_hash),
        env.DB.prepare('UPDATE sessions SET nickname=? WHERE account_id=? OR id_hash=?').bind(nickname, owner.id_hash, owner.id_hash),
      ]);
      return secureResponse({...identity(owner), nickname});
    }
    const token = randomHex(32), id = await hash(token), uid = randomHex(6), secret = randomSecret();
    const digest = await hash(uid + ':' + secret), now = stamp();
    await env.DB.batch([
      env.DB.prepare('INSERT INTO community_accounts(id,uid,nickname,recovery_hash,created_at) VALUES(?,?,?,?,?)')
        .bind(id, uid, nickname, digest, now),
      env.DB.prepare('INSERT INTO sessions(id_hash,nickname,expires_at,account_id) VALUES(?,?,?,?)')
        .bind(id, nickname, now + sessionLife, id),
    ]);
    return secureResponse({uid, nickname, recoveryReady: true, recoveryCode: uid + '.' + secret}, 201,
      {'Set-Cookie': cookie(token)});
  }
  async function recover(env, body) {
    const code = text(body.recoveryCode, 100);
    const parts = /^([a-f0-9]{12})\.([A-Za-z0-9_-]{43})$/.exec(code);
    if (!parts) fail(401, '找回码不正确');
    const account = await env.DB.prepare('SELECT id,uid,nickname,recovery_hash FROM community_accounts WHERE uid=?')
      .bind(parts[1]).first();
    const digest = await hash(parts[1] + ':' + parts[2]);
    if (!equalDigest(digest, account?.recovery_hash || '0'.repeat(64))) fail(401, '找回码不正确');
    const token = randomHex(32);
    await env.DB.prepare('INSERT INTO sessions(id_hash,nickname,expires_at,account_id) VALUES(?,?,?,?)')
      .bind(await hash(token), account.nickname, stamp() + sessionLife, account.id).run();
    return secureResponse({uid: account.uid, nickname: account.nickname, recoveryReady: true}, 200,
      {'Set-Cookie': cookie(token)});
  }
  async function newRecovery(env, owner) {
    const secret = randomSecret();
    await env.DB.prepare('UPDATE community_accounts SET recovery_hash=? WHERE id=?')
      .bind(await hash(owner.uid + ':' + secret), owner.id_hash).run();
    return secureResponse({recoveryCode: owner.uid + '.' + secret});
  }
  async function logout(env, owner) {
    if (owner) await env.DB.prepare('DELETE FROM sessions WHERE id_hash=?').bind(owner.session_hash).run();
    return secureResponse({ok: true}, 200, {'Set-Cookie': cookie('', 0)});
  }
  return {session, who, register, recover, newRecovery, logout, identity};
}
