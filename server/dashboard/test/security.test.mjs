import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandler } from '../src/worker.mjs';
import { verifyAccess } from '../src/auth.mjs';

const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
const publicKey = { ...await crypto.subtle.exportKey('jwk', pair.publicKey), kid: 'fixture-key' };
const config = { issuer: 'https://fixture.cloudflareaccess.com', audience: 'fixture-audience', emails: ['owner@example.test'] };
const env = { AUTH_CONFIG: JSON.stringify(config) };
const base64 = bytes => Buffer.from(bytes).toString('base64url');
async function token(overrides = {}, header = {}) {
    const now = Math.floor(Date.now() / 1000);
    const parts = [base64(JSON.stringify({ alg: 'RS256', kid: 'fixture-key', ...header })), base64(JSON.stringify({ iss: config.issuer, aud: [config.audience], email: 'owner@example.test', exp: now + 600, nbf: now - 1, ...overrides }))];
    const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', pair.privateKey, new TextEncoder().encode(parts.join('.')));
    return parts.join('.') + '.' + base64(signature);
}
const request = async (path = '/', overrides = {}, header = {}) => new Request('https://dashboard.invalid' + path, { headers: { 'Cf-Access-Jwt-Assertion': await token(overrides, header) } });
const authorize = (req, e) => verifyAccess(req, e, async () => [publicKey]);
const worker = createHandler(authorize);
const key = 'v1/events/11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222/33333333-3333-4333-8333-333333333333.jsonl.gz';
test('valid signature, exact audience and allowed identity are required', async () => {
    assert.deepEqual(await authorize(await request(), env), { email: 'owner@example.test' });
    await assert.rejects(authorize(await request('/', { email: 'other@example.test' }), env), e => e.status === 403);
    for (const claims of [{ aud: ['other'] }, { iss: 'https://other.cloudflareaccess.com' }, { exp: 0 }, { nbf: Date.now() / 1000 + 500 }]) await assert.rejects(authorize(await request('/', claims), env), e => e.status === 401);
});
test('forged payloads, none algorithm, missing JWT and missing config fail closed', async () => {
    const signed = await token(); const parts = signed.split('.'); parts[1] = base64(JSON.stringify({ email: 'owner@example.test' }));
    await assert.rejects(authorize(new Request('https://dashboard.invalid', { headers: { 'Cf-Access-Jwt-Assertion': parts.join('.') } }), env));
    await assert.rejects(authorize(await request('/', {}, { alg: 'none' }), env));
    await assert.rejects(authorize(new Request('https://dashboard.invalid'), env));
    await assert.rejects(authorize(await request(), {}), e => e.status === 503);
});
test('no unauthenticated request ever touches R2, including static assets', async () => {
    let reads = 0;
    const restricted = { ...env, TELEMETRY: { list() { reads++; }, get() { reads++; } } };
    for (const path of ['/', '/app.mjs', '/api/info', '/api/batches', '/api/batch?key=' + encodeURIComponent(key)]) {
        assert.equal((await worker.fetch(new Request('https://dashboard.invalid' + path), restricted)).status, 401);
    }
    assert.equal(reads, 0);
});
test('list exposes only the telemetry prefix and paginates without record bodies', async () => {
    const bucket = { async list(options) {
        assert.equal(options.prefix, 'v1/events/'); assert.equal(options.limit, 500); assert.equal(options.cursor, 'opaque');
        return { truncated: true, cursor: 'next', objects: [{ key, size: 10, etag: 'fixture', uploaded: new Date(0), customMetadata: { event_count: '1', first_seq: '1', last_seq: '1', sha256: 'hash' } }, { key: 'private-other-file', size: 99 }] };
    } };
    const response = await worker.fetch(await request('/api/batches?cursor=opaque'), { ...env, TELEMETRY: bucket });
    const value = await response.json(); assert.equal(value.objects.length, 1); assert.equal(value.cursor, 'next'); assert.equal(value.objects[0].count, 1);
    assert.equal(response.headers.get('Cache-Control'), 'private, no-store');
});
test('path traversal and writes are rejected; only existing telemetry objects stream', async () => {
    let reads = 0;
    const bucket = { async get(path) { reads++; assert.equal(path, key); return { size: 3, body: new Uint8Array([1, 2, 3]), httpEtag: '"fixture"' }; } };
    assert.equal((await worker.fetch(await request('/api/batch?key=../../secret'), { ...env, TELEMETRY: bucket })).status, 400);
    assert.equal(reads, 0);
    const response = await worker.fetch(await request('/api/batch?key=' + encodeURIComponent(key)), { ...env, TELEMETRY: bucket });
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()), new Uint8Array([1, 2, 3]));
    assert.equal(response.headers.get('Content-Type'), 'application/gzip'); assert.ok(response.headers.get('Content-Disposition').startsWith('attachment;'));
    const post = new Request('https://dashboard.invalid/api/batches', { method: 'POST', headers: { 'Cf-Access-Jwt-Assertion': await token() } });
    assert.equal((await worker.fetch(post, { ...env, TELEMETRY: bucket })).status, 405);
});
test('authenticated HTML has strict security headers and no external scripts', async () => {
    const response = await worker.fetch(await request('/'), env);
    assert.equal(response.status, 200); assert.equal(response.headers.get('X-Frame-Options'), 'DENY');
    assert.ok(response.headers.get('Content-Security-Policy').includes("script-src 'self'"));
    assert.ok(!(await response.text()).includes('https://cdn'));
    const info = await (await worker.fetch(await request('/api/info'), env)).json(); assert.equal(info.email, 'owner@example.test');
});
