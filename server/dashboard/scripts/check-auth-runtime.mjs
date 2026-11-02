import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

// Use workerd, not Node's fetch implementation, for the default JWKS path.
const require = createRequire(import.meta.url);
const { Miniflare } = require(process.env.MINIFLARE_MODULE || 'miniflare');
const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
const jwk = { ...await crypto.subtle.exportKey('jwk', pair.publicKey), kid: 'runtime-fixture' };
const config = { issuer: 'https://fixture.cloudflareaccess.com', audience: 'runtime-fixture', emails: ['owner@example.test'] };
const source = readFileSync(new URL('../src/auth.mjs', import.meta.url), 'utf8') + `
export default { async fetch(request, env) {
    try { return Response.json(await verifyAccess(request, env)); }
    catch (error) { return Response.json({ error: error.code }, { status: error.status || 500 }); }
} };`;
const b64 = value => Buffer.from(value).toString('base64url');
async function token(overrides = {}) {
    const parts = [b64(JSON.stringify({ alg: 'RS256', kid: jwk.kid })), b64(JSON.stringify({ iss: config.issuer, aud: [config.audience], email: config.emails[0], exp: Math.floor(Date.now() / 1000) + 300, ...overrides }))];
    parts.push(b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(parts.join('.')))));
    return parts.join('.');
}
function runtime(mode) {
    let calls = 0;
    const mf = new Miniflare({ modules: true, compatibilityDate: '2025-09-01', port: 0, script: source, bindings: { AUTH_CONFIG: JSON.stringify(config) },
        outboundService: async request => {
            calls++; assert.equal(request.url, config.issuer + '/cdn-cgi/access/certs');
            if (mode === 'redirect') return new Response(null, { status: 302, headers: { Location: 'https://untrusted.example.test/certs' } });
            return Response.json({ keys: [jwk] });
        },
    });
    return { mf, calls: () => calls };
}
async function request(mf, jwt) { return mf.dispatchFetch('https://dashboard.example.test/', { headers: jwt ? { 'Cf-Access-Jwt-Assertion': jwt } : {} }); }
const good = runtime('ok');
try {
    assert.equal((await request(good.mf)).status, 401);
    assert.equal(good.calls(), 0);
    const jwt = await token();
    const response = await request(good.mf, jwt);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { email: config.emails[0] });
    assert.equal(good.calls(), 1);
    assert.equal((await request(good.mf, jwt)).status, 200);
    assert.equal(good.calls(), 1);
    assert.equal((await request(good.mf, await token({ email: 'other@example.test' }))).status, 403);
    assert.equal((await request(good.mf, await token({ exp: 0 }))).status, 401);
    const parts = jwt.split('.'); parts[2] = (parts[2][0] === 'A' ? 'B' : 'A') + parts[2].slice(1);
    assert.equal((await request(good.mf, parts.join('.'))).status, 401);
} finally { await good.mf.dispose(); }
const redirect = runtime('redirect');
try {
    const response = await request(redirect.mf, await token());
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'identity_unavailable' });
    assert.equal(redirect.calls(), 1);
} finally { await redirect.mf.dispose(); }
console.log('PASS workerd: authenticated JWKS fetch, signature, cache, identity, expiry, anonymous rejection and redirect rejection');
