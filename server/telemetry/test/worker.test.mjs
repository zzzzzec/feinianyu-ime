import test from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import worker, { MAX_BATCH_BYTES, sha256 } from "../src/worker.mjs";

const device = "11111111-1111-4111-8111-111111111111";
const generation = "22222222-2222-4222-8222-222222222222";
const batch = "33333333-3333-4333-8333-333333333333";
const token = "synthetic-test-device-token-never-used-in-production";
const body = gzipSync('{"record_id":1,"kind":"synthetic"}\n');
const url = `https://telemetry.invalid/v1/batches/${batch}`;

class Bucket {
    objects = new Map();
    writes = 0;
    async put(key, bytes, options) {
        assert.equal(options.onlyIf.get("If-None-Match"), "*");
        assert.equal(options.sha256, await sha256(bytes));
        if (this.objects.has(key)) return null;
        const object = { key, size: bytes.length, etag: "synthetic-etag", customMetadata: options.customMetadata, bytes: bytes.slice() };
        this.objects.set(key, object);
        this.writes++;
        return object;
    }
    async head(key) { return this.objects.get(key) || null; }
}

async function environment() {
    return {
        DEVICE_UPLOAD_KEYS: JSON.stringify([{ device_id: device, token_sha256: await sha256(new TextEncoder().encode(token)), enabled: true }]),
        TELEMETRY: new Bucket(),
    };
}

async function request(bytes = body, headers = {}) {
    const all = {
        Authorization: `Bearer ${token}`, "Content-Type": "application/gzip",
        "X-Telemetry-Generation": generation,
        "X-Telemetry-First-Seq": "1", "X-Telemetry-Last-Seq": "1", "X-Telemetry-Event-Count": "1",
        "X-Content-SHA256": await sha256(bytes), ...headers,
    };
    return new Request(url, { method: "POST", headers: all, body: bytes });
}

test("no configured keys denies uploads; health reveals no data", async () => {
    const env = await environment();
    delete env.DEVICE_UPLOAD_KEYS;
    assert.equal((await worker.fetch(await request(), env)).status, 503);
    assert.equal((await worker.fetch(new Request("https://telemetry.invalid/health"), env)).status, 200);
    assert.equal(env.TELEMETRY.writes, 0);
});

test("device check verifies actual upload authorization without uploading", async () => {
    const env = await environment();
    const endpoint = 'https://telemetry.invalid/v1/device';
    assert.equal((await worker.fetch(new Request(endpoint), env)).status, 401);
    const allowed = await worker.fetch(new Request(endpoint, { headers: { Authorization: `Bearer ${token}` } }), env);
    assert.equal(allowed.status, 200);
    assert.deepEqual(await allowed.json(), { schema: 1, device_id: device, capabilities: ['upload'] });
    assert.equal(env.TELEMETRY.writes, 0);
});

test("missing, invalid and revoked credentials never touch storage", async () => {
    const env = await environment();
    for (const value of ["", "Bearer bad", `Bearer ${"x".repeat(48)}`]) {
        assert.equal((await worker.fetch(await request(body, { Authorization: value }), env)).status, 401);
    }
    const keys = JSON.parse(env.DEVICE_UPLOAD_KEYS);
    keys[0].enabled = false;
    env.DEVICE_UPLOAD_KEYS = JSON.stringify(keys);
    assert.equal((await worker.fetch(await request(), env)).status, 401);
    assert.equal(env.TELEMETRY.writes, 0);
});

test("stores exact bytes privately under server-selected device and immutable batch", async () => {
    const env = await environment();
    const response = await worker.fetch(await request(body, { "X-Device-Id": "arbitrary-other-device" }), env);
    assert.equal(response.status, 201);
    const ack = await response.json();
    assert.equal(ack.device_id, device);
    assert.equal(ack.sha256, await sha256(body));
    assert.equal(ack.bytes, body.length);
    assert.equal(ack.event_count, 1);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    const stored = [...env.TELEMETRY.objects.values()][0];
    assert.equal(stored.key, `v1/events/${device}/${generation}/${batch}.jsonl.gz`);
    assert.deepEqual(stored.bytes, new Uint8Array(body));
});

test("lost receipt and concurrent duplicate retry acknowledge without overwrite", async () => {
    const env = await environment();
    const responses = await Promise.all([worker.fetch(await request(), env), worker.fetch(await request(), env)]);
    assert.deepEqual(responses.map(r => r.status).sort(), [200, 201]);
    assert.equal(env.TELEMETRY.writes, 1);
    const retry = await worker.fetch(await request(), env);
    assert.equal((await retry.json()).repeated, true);
});

test("same batch with different bytes or metadata conflicts", async () => {
    const env = await environment();
    await worker.fetch(await request(), env);
    assert.equal((await worker.fetch(await request(gzipSync("other synthetic data")), env)).status, 409);
    assert.equal((await worker.fetch(await request(body, { "X-Telemetry-Last-Seq": "2" }), env)).status, 409);
    assert.equal(env.TELEMETRY.writes, 1);
});

test("bad checksum, gzip, sequence range and content encoding rejected", async () => {
    const env = await environment();
    const cases = [
        [await request(body, { "X-Content-SHA256": "0".repeat(64) }), 422],
        [await request(new TextEncoder().encode("this is not a gzip payload")), 400],
        [await request(body, { "X-Telemetry-First-Seq": "2" }), 400],
        [await request(body, { "X-Telemetry-Event-Count": "2" }), 400],
        [await request(body, { "X-Telemetry-Generation": "../escape" }), 400],
        [await request(body, { "Content-Encoding": "gzip" }), 415],
    ];
    for (const [req, status] of cases) assert.equal((await worker.fetch(req, env)).status, status);
    assert.equal(env.TELEMETRY.writes, 0);
});

test("body limit enforced even without Content-Length", async () => {
    const env = await environment();
    const large = new Uint8Array(MAX_BATCH_BYTES + 1);
    assert.equal((await worker.fetch(await request(large), env)).status, 413);
    assert.equal((await worker.fetch(await request(body, { "Content-Length": String(MAX_BATCH_BYTES + 1) }), env)).status, 413);
    assert.equal(env.TELEMETRY.writes, 0);
});

test("no object read/list/delete route and storage errors leak no details", async () => {
    const env = await environment();
    for (const method of ["GET", "DELETE", "PUT"]) assert.equal((await worker.fetch(new Request(url, { method }), env)).status, 405);
    assert.equal((await worker.fetch(new Request("https://telemetry.invalid/v1/events"), env)).status, 404);
    env.TELEMETRY.put = () => { throw new Error("private storage detail"); };
    const response = await worker.fetch(await request(), env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "storage_retry" });
});
