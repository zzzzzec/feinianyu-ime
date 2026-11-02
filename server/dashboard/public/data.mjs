export async function decodeBatch(bytes, meta) {
    if (bytes.byteLength > 1024 * 1024) throw new Error('batch_too_large');
    if (meta.sha256) {
        const digest = await crypto.subtle.digest('SHA-256', bytes);
        const hex = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
        if (hex !== meta.sha256) throw new Error('checksum_mismatch');
    }
    const reader = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')).getReader();
    const decoder = new TextDecoder('utf-8', { fatal: true });
    const rows = []; let size = 0, pending = '';
    function line(text) {
        if (!text.trim()) return;
        const row = JSON.parse(text);
        if (!row || typeof row !== 'object' || Array.isArray(row) || rows.length >= 2048) throw new Error('invalid_event');
        rows.push(row);
    }
    try {
        while (true) {
            const { done, value } = await reader.read(); if (done) break;
            size += value.byteLength; if (size > 8 * 1024 * 1024) throw new Error('expanded_batch_too_large');
            pending += decoder.decode(value, { stream: true });
            let index; while ((index = pending.indexOf('\n')) >= 0) { const text = pending.slice(0, index); if (text.length > 1024 * 1024) throw new Error('event_too_large'); line(text); pending = pending.slice(index + 1); }
            if (pending.length > 1024 * 1024) throw new Error('event_too_large');
        }
        pending += decoder.decode(); line(pending);
    } finally { await reader.cancel().catch(() => {}); }
    if (Number.isSafeInteger(meta.count) && rows.length !== meta.count) throw new Error('event_count_mismatch');
    const ids = rows.map(r => r.record_id);
    if (ids.every(Number.isSafeInteger) && ids.length) {
        if (ids.some((id, i) => id <= 0 || (i > 0 && id <= ids[i - 1])) || (meta.first && ids[0] !== meta.first) || (meta.last && ids.at(-1) !== meta.last)) throw new Error('sequence_mismatch');
    }
    return rows;
}
