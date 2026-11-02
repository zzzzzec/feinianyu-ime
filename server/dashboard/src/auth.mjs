const keyCache = new Map();
export class AuthError extends Error {
    constructor(status, code) { super(code); this.status = status; this.code = code; }
}
function decode(value) {
    if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new AuthError(401, 'invalid_session');
    const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
    return Uint8Array.from(raw, c => c.charCodeAt(0));
}
async function loadKeys(issuer, force = false) {
    const cached = keyCache.get(issuer);
    if (!force && cached && cached.expires > Date.now()) return cached.keys;
    let response;
    try { response = await fetch(issuer + '/cdn-cgi/access/certs', { redirect: 'manual', signal: AbortSignal.timeout(5000) }); }
    catch { throw new AuthError(503, 'identity_unavailable'); }
    if (!response.ok) throw new AuthError(503, 'identity_unavailable');
    const value = await response.json();
    if (!Array.isArray(value.keys)) throw new AuthError(503, 'identity_unavailable');
    keyCache.set(issuer, { keys: value.keys, expires: Date.now() + 300000 });
    return value.keys;
}
export async function verifyAccess(request, env, getKeys = loadKeys, now = Date.now() / 1000) {
    let config;
    try {
        config = JSON.parse(env.AUTH_CONFIG);
        if (!/^https:\/\/[a-z0-9-]+\.cloudflareaccess\.com$/.test(config.issuer) || typeof config.audience !== 'string' || !config.audience || !Array.isArray(config.emails) || !config.emails.length) throw new Error();
    } catch { throw new AuthError(503, 'access_unconfigured'); }
    const token = request.headers.get('Cf-Access-Jwt-Assertion') || '';
    if (!token || token.length > 16384) throw new AuthError(401, 'login_required');
    try {
        const parts = token.split('.');
        if (parts.length !== 3) throw new AuthError(401, 'invalid_session');
        const header = JSON.parse(new TextDecoder().decode(decode(parts[0])));
        const claims = JSON.parse(new TextDecoder().decode(decode(parts[1])));
        if (header.alg !== 'RS256' || typeof header.kid !== 'string' || header.kid.length > 256) throw new AuthError(401, 'invalid_session');
        const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
        if (claims.iss !== config.issuer || !audiences.includes(config.audience) || !Number.isFinite(claims.exp) || claims.exp <= now || (claims.nbf !== undefined && (!Number.isFinite(claims.nbf) || claims.nbf > now + 30))) throw new AuthError(401, 'invalid_session');
        const email = typeof claims.email === 'string' ? claims.email.trim().toLowerCase() : '';
        if (!config.emails.some(e => typeof e === 'string' && e.toLowerCase() === email)) throw new AuthError(403, 'not_allowed');
        let keys = await getKeys(config.issuer);
        let jwk = keys.find(k => k.kid === header.kid && k.kty === 'RSA');
        if (!jwk) { keys = await getKeys(config.issuer, true); jwk = keys.find(k => k.kid === header.kid && k.kty === 'RSA'); }
        if (!jwk) throw new AuthError(401, 'invalid_session');
        const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
        const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, decode(parts[2]), new TextEncoder().encode(parts[0] + '.' + parts[1]));
        if (!valid) throw new AuthError(401, 'invalid_session');
        return { email };
    } catch (error) {
        if (error instanceof AuthError) throw error;
        throw new AuthError(401, 'invalid_session');
    }
}
