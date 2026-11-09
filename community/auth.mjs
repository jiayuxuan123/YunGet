const decoder = new TextDecoder();

function decodeBase64Url(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid encoding');
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), char => char.charCodeAt(0));
}

export function createAdminVerifier(fetchKeys = fetch) {
  let cachedKeys;
  let expiresAt = 0;
  let cachedIssuer;
  return async function verifyAdmin(request, env) {
    const token = request.headers.get('Cf-Access-Jwt-Assertion');
    if (!token || token.length > 16384 || !env.ACCESS_AUD || !env.ACCESS_ISSUER) return null;
    try {
      const parts = token.split('.');
      if (parts.length !== 3) return null;
      const header = JSON.parse(decoder.decode(decodeBase64Url(parts[0])));
      const claims = JSON.parse(decoder.decode(decodeBase64Url(parts[1])));
      const now = Date.now() / 1000;
      if (header.alg !== 'RS256' || typeof header.kid !== 'string' || header.crit) return null;
      if (claims.iss !== env.ACCESS_ISSUER || !Array.isArray(claims.aud) || !claims.aud.includes(env.ACCESS_AUD)) return null;
      if (typeof claims.exp !== 'number' || claims.exp <= now || (claims.nbf && claims.nbf > now + 30)) return null;
      if (typeof claims.email !== 'string' || !claims.email || claims.type !== 'app') return null;
      const issuer = new URL(env.ACCESS_ISSUER);
      if (issuer.protocol !== 'https:' || !issuer.hostname.endsWith('.cloudflareaccess.com') || issuer.pathname !== '/') return null;
      const loadKeys = async () => {
        const response = await fetchKeys(`${issuer.origin}/cdn-cgi/access/certs`, {signal: AbortSignal.timeout(8000)});
        if (!response.ok) throw new Error('Key lookup failed');
        const body = await response.text();
        if (body.length > 65536) throw new Error('Key response too large');
        cachedKeys = JSON.parse(body).keys;
        if (!Array.isArray(cachedKeys)) throw new Error('Invalid key response');
        expiresAt = now + 300;
        cachedIssuer = env.ACCESS_ISSUER;
      };
      if (!cachedKeys || expiresAt <= now || cachedIssuer !== env.ACCESS_ISSUER) await loadKeys();
      let key = cachedKeys.find(item => item.kid === header.kid && item.kty === 'RSA');
      if (!key) {
        await loadKeys();
        key = cachedKeys.find(item => item.kid === header.kid && item.kty === 'RSA');
      }
      if (!key) return null;
      const imported = await crypto.subtle.importKey('jwk', key,
        {name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256'}, false, ['verify']);
      const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', imported,
        decodeBase64Url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`));
      return valid ? {developer: true} : null;
    } catch {
      return null;
    }
  };
}
