import assets from '../.generated/assets.mjs';
import { verifyAccess, AuthError } from './auth.mjs';
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}';
const OBJECT = new RegExp(`^v1/events/(${UUID})/(${UUID})/(${UUID})\\.jsonl\\.gz$`);
const security = {
    'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer', 'Cross-Origin-Resource-Policy': 'same-origin',
    'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};
const json = (status, value) => Response.json(value, { status, headers: security });

export function createHandler(authorize = verifyAccess) {
    return { async fetch(request, env) {
        try {
            const identity = await authorize(request, env);
            if (!['GET', 'HEAD'].includes(request.method)) return json(405, { error: 'read_only' });
            const url = new URL(request.url);
            if (url.pathname === '/api/info') return json(200, { schema: 1, email: identity.email });
            if (url.pathname === '/api/batches') {
                const cursor = url.searchParams.get('cursor') || undefined;
                if (cursor && (cursor.length > 4096 || /[\r\n]/.test(cursor))) return json(400, { error: 'invalid_cursor' });
                const page = await env.TELEMETRY.list({ prefix: 'v1/events/', limit: 500, cursor, include: ['customMetadata'] });
                const objects = page.objects.filter(o => OBJECT.test(o.key)).map(o => {
                    const match = OBJECT.exec(o.key), m = o.customMetadata || {};
                    return { key: o.key, device: match[1], generation: match[2], batch: match[3], size: o.size, etag: o.etag, uploaded: o.uploaded.toISOString(),
                        first: Number(m.first_seq) || null, last: Number(m.last_seq) || null, count: Number(m.event_count) || null, sha256: m.sha256 || null };
                });
                return json(200, { schema: 1, objects, cursor: page.truncated ? page.cursor : null, truncated: page.truncated });
            }
            if (url.pathname === '/api/batch') {
                const key = url.searchParams.get('key') || '';
                if (!OBJECT.test(key)) return json(400, { error: 'invalid_batch' });
                const object = await env.TELEMETRY.get(key);
                if (!object) return json(404, { error: 'not_found' });
                if (object.size > 1024 * 1024) return json(413, { error: 'batch_too_large' });
                return new Response(request.method === 'HEAD' ? null : object.body, { headers: { ...security, 'Content-Type': 'application/gzip', 'Content-Disposition': 'attachment; filename="' + OBJECT.exec(key)[3] + '.jsonl.gz"', ETag: object.httpEtag } });
            }
            const asset = assets[url.pathname];
            if (asset) return new Response(request.method === 'HEAD' ? null : asset.body, { headers: { ...security, 'Content-Type': asset.type } });
            return json(404, { error: 'not_found' });
        } catch (error) {
            if (error instanceof AuthError) return json(error.status, { error: error.code });
            return json(503, { error: 'data_unavailable' });
        }
    } };
}
export default createHandler();
