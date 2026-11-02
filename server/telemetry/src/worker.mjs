export const MAX_BATCH_BYTES = 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEX256 = /^[0-9a-f]{64}$/;

function json(status, value) {
    return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}

export async function sha256(bytes) {
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}

function equalHash(a, b) {
    if (!HEX256.test(a) || !HEX256.test(b)) return false;
    let difference = 0;
    for (let i = 0; i < 64; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return difference === 0;
}

async function authenticate(request, env) {
    let keys;
    try {
        keys = JSON.parse(env.DEVICE_UPLOAD_KEYS);
        if (!Array.isArray(keys) || keys.some(k => !UUID.test(k.device_id) || !HEX256.test(k.token_sha256) || typeof k.enabled !== "boolean")) {
            return { error: json(503, { error: "authentication_unconfigured" }) };
        }
    } catch {
        return { error: json(503, { error: "authentication_unconfigured" }) };
    }
    const match = /^Bearer ([A-Za-z0-9_-]{43,128})$/.exec(request.headers.get("Authorization") || "");
    if (!match) return { error: json(401, { error: "unauthorized" }) };
    const tokenHash = await sha256(new TextEncoder().encode(match[1]));
    const device = keys.find(k => k.enabled && equalHash(k.token_sha256, tokenHash));
    return device ? { device: device.device_id } : { error: json(401, { error: "unauthorized" }) };
}

function positiveInteger(header) {
    if (!/^[1-9][0-9]{0,15}$/.test(header || "")) return null;
    const value = Number(header);
    return Number.isSafeInteger(value) ? value : null;
}

async function boundedBody(request) {
    const reader = request.body?.getReader();
    if (!reader) return null;
    const chunks = [];
    let length = 0;
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        length += value.byteLength;
        if (length > MAX_BATCH_BYTES) {
            await reader.cancel();
            return null;
        }
        chunks.push(value);
    }
    const body = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {
        body.set(chunk, offset);
        offset += chunk.byteLength;
    }
    return body;
}

function receipt(object, metadata, repeated) {
    return json(repeated ? 200 : 201, {
        schema: 1, status: "stored", repeated,
        device_id: metadata.device_id,
        generation: metadata.generation,
        batch_id: metadata.batch_id,
        first_seq: Number(metadata.first_seq),
        last_seq: Number(metadata.last_seq),
        event_count: Number(metadata.event_count),
        bytes: object.size, sha256: metadata.sha256,
        etag: object.etag,
    });
}

async function upload(request, env, batchId) {
    const auth = await authenticate(request, env);
    if (auth.error) return auth.error;
    const generation = request.headers.get("X-Telemetry-Generation") || "";
    const digest = request.headers.get("X-Content-SHA256") || "";
    const first = positiveInteger(request.headers.get("X-Telemetry-First-Seq"));
    const last = positiveInteger(request.headers.get("X-Telemetry-Last-Seq"));
    const count = positiveInteger(request.headers.get("X-Telemetry-Event-Count"));
    if (!UUID.test(generation) || !HEX256.test(digest) || first === null || last === null || count === null || last < first || count > last - first + 1) {
        return json(400, { error: "invalid_batch_metadata" });
    }
    if (request.headers.get("Content-Type")?.split(";")[0].trim() !== "application/gzip" || request.headers.has("Content-Encoding")) {
        return json(415, { error: "expected_raw_gzip_body" });
    }
    const declaredLength = request.headers.get("Content-Length");
    if (declaredLength !== null && (!/^[0-9]+$/.test(declaredLength) || Number(declaredLength) > MAX_BATCH_BYTES)) {
        return json(413, { error: "batch_too_large" });
    }
    const body = await boundedBody(request);
    if (body === null) return json(413, { error: "batch_too_large" });
    if (declaredLength !== null && Number(declaredLength) !== body.byteLength) return json(400, { error: "length_mismatch" });
    if (body.length < 18 || body[0] !== 0x1f || body[1] !== 0x8b || body[2] !== 8) return json(400, { error: "invalid_gzip_header" });
    if (!equalHash(await sha256(body), digest)) return json(422, { error: "checksum_mismatch" });
    const metadata = {
        schema: "1", device_id: auth.device, generation, batch_id: batchId,
        first_seq: String(first), last_seq: String(last), event_count: String(count), sha256: digest,
    };
    // The server selects the device prefix; date/client paths cannot move a batch.
    const key = `v1/events/${auth.device}/${generation}/${batchId}.jsonl.gz`;
    const stored = await env.TELEMETRY.put(key, body, {
        onlyIf: new Headers({ "If-None-Match": "*" }),
        sha256: digest, storageClass: "Standard",
        httpMetadata: { contentType: "application/gzip", cacheControl: "private, no-store" },
        customMetadata: metadata,
    });
    if (stored) return receipt(stored, metadata, false);
    // Conditional writes arbitrate concurrent requests. A lost ACK is safe to retry.
    const existing = await env.TELEMETRY.head(key);
    if (!existing) return json(503, { error: "storage_retry" });
    if (existing.size !== body.length || Object.entries(metadata).some(([k, v]) => existing.customMetadata?.[k] !== v)) {
        return json(409, { error: "batch_id_conflict" });
    }
    return receipt(existing, metadata, true);
}

export default {
    async fetch(request, env) {
        const path = new URL(request.url).pathname;
        if (request.method === "GET" && path === "/health") return json(200, { service: "feinianyu-ime-telemetry", protocol: 1 });
        if (request.method === "GET" && path === "/v1/device") {
            const auth = await authenticate(request, env);
            return auth.error || json(200, { schema: 1, device_id: auth.device, capabilities: ["upload"] });
        }
        const match = /^\/v1\/batches\/([^/]+)$/.exec(path);
        if (!match || !UUID.test(match[1])) return json(404, { error: "not_found" });
        if (request.method !== "POST") return json(405, { error: "method_not_allowed" });
        try {
            return await upload(request, env, match[1]);
        } catch {
            // Never log request bodies, credentials, or R2 error details.
            return json(503, { error: "storage_retry" });
        }
    },
};
