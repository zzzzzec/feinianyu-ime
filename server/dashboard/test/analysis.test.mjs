import test from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { createHash } from 'node:crypto';
import { Analysis, percentile } from '../public/analysis.mjs';
import { decodeBatch } from '../public/data.mjs';
const meta = { key: 'synthetic-key', device: 'device', generation: 'generation', uploaded: '2026-11-02T00:00:00Z' };
const base = { session_id: 'synthetic-fixture-session', wall_time_ms: Date.UTC(2026, 10, 2), app_version: 'test' };
function fixture() {
    const input = { mode: 'pinyin', raw_input: 'beijng', pinyin_mode: 0, raw_cursor: 6, selected_input_length: 0 };
    const action = { id: 7, decision_id: 2, kind: 'candidate_click', input };
    return [
        { ...base, type: 'candidates', decision_id: 2, composition_id: 1, input, original_order_known: true, context: '我去', candidates: [{ text: '北京', commit_text: '北京', original_rank: 2, final_rank: 1 }] },
        { ...base, type: 'action_result', action, handled: true, input: { raw_input: '' } },
        { ...base, type: 'editor_commit', engine_action: action, source: 'engine', text: '北京', success: true },
    ];
}
function analyze(rows, options = {}) { const analysis = new Analysis({ now: Date.UTC(2026, 10, 3), ...options }); rows.forEach((row, i) => analysis.feed(row, meta, i)); return analysis.summary(); }
test('preserves real typo, original/display rank and committed characters', () => {
    const value = analyze(fixture()); assert.equal(value.choices, 1); assert.equal(value.chars, 2); assert.equal(value.top1, 1);
    assert.equal(value.samples[0].pinyin, 'beijng'); assert.equal(value.samples[0].original, 2); assert.equal(value.samples[0].rank, 1);
    assert.equal(value.labelKind, 'observed_behavior_not_human_gold');
});
test('joins commits before action result and avoids duplicate composition', () => {
    const [decision, result, commit] = fixture(); const second = { ...commit, record_id: 99 };
    const value = analyze([decision, commit, result, second]); assert.equal(value.choices, 1); assert.equal(value.skipped['同轮组合输入重复'], 1);
});
test('failed, partial, ambiguous and pasted commits never become ranking examples', () => {
    for (const mode of ['failed', 'partial', 'ambiguous', 'paste', 'unknown']) {
        const rows = fixture();
        if (mode === 'failed') rows[2].success = false;
        if (mode === 'partial') rows[1].input.raw_input = 'ng';
        if (mode === 'ambiguous') rows[0].candidates.push({ ...rows[0].candidates[0] });
        if (mode === 'paste') rows[2].source = 'paste';
        if (mode === 'unknown') rows[0].original_order_known = false;
        assert.equal(analyze(rows).choices, 0, mode);
    }
});
test('deduplicates installation/generation/record across device re-pairing', () => {
    const analysis = new Analysis({ now: Date.UTC(2026, 10, 3) });
    const row = { ...base, type: 'editor_commit', source: 'direct', text: '字', success: true, record_id: 1, installation_id: 'install', generation: 'db' };
    analysis.feed(row, meta, 0); analysis.feed(row, { ...meta, device: 'new-device' }, 0);
    assert.equal(analysis.summary().events, 1); assert.equal(analysis.summary().duplicates, 1);
});
test('excludes synthetic sessions including their unmarked native events', () => {
    const rows = [{ ...base, type: 'session_start', application: 'dev.feinianyu.synthetic.dataset' }, ...fixture()];
    assert.equal(analyze(rows).events, 0); assert.equal(analyze(rows).testEvents, 4);
    assert.equal(analyze(rows, { includeTests: true }).choices, 1);
});
test('does not double-count input and performance completion observations', () => {
    const rows = [
        { ...base, type: 'completion_display', request_id: 1, shown: true },
        { ...base, type: 'completion_accept', request_id: 1 },
        { ...base, type: 'completion_performance', performance: { phase: 'ui', state: 'shown', request_id: 1, session_id: 'process', end_to_end_ms: 200 } },
        { ...base, type: 'completion_performance', performance: { phase: 'accept', request_id: 1, session_id: 'process' } },
    ];
    const value = analyze(rows); assert.equal(value.shown, 1); assert.equal(value.accepted, 1); assert.equal(value.acceptedRate, 1); assert.equal(value.uiP50, 200);
    const orphan = analyze([{ ...base, type: 'completion_accept', request_id: 1 }]); assert.equal(orphan.acceptedRate, null);
});
test('percentiles use nearest rank, and no values remain missing', () => { assert.equal(percentile([], .95), null); assert.equal(percentile([10, 100, 20], .95), 100); assert.equal(percentile([10, 100, 20], .5), 20); });
test('completion metrics prefer a coherent performance stream when text capture differs', () => {
    const rows = [
        { ...base, type: 'completion_display', request_id: 1, shown: true },
        { ...base, type: 'completion_accept', request_id: 1 },
        ...[1, 2].map(id => ({ ...base, type: 'completion_performance', performance: { phase: 'ui', state: 'shown', request_id: id, session_id: 'process', end_to_end_ms: 200 } })),
        { ...base, type: 'completion_performance', performance: { phase: 'accept', request_id: 1, session_id: 'process' } },
    ];
    const value = analyze(rows);
    assert.equal(value.shown, 2); assert.equal(value.acceptedRate, .5); assert.equal(value.completionSource, 'performance');
});
test('gzip checks integrity, counts, unicode and record sequence', async () => {
    const rows = [{ record_id: 1, text: '你好😀' }, { record_id: 2, text: 'synthetic' }];
    const bytes = gzipSync(rows.map(row => JSON.stringify(row)).join('\n') + '\n');
    const hash = createHash('sha256').update(bytes).digest('hex');
    assert.deepEqual(await decodeBatch(bytes, { sha256: hash, count: 2, first: 1, last: 2 }), rows);
    await assert.rejects(decodeBatch(bytes, { sha256: '0'.repeat(64) }), /checksum/);
    await assert.rejects(decodeBatch(bytes, { count: 1 }), /count/);
    await assert.rejects(decodeBatch(bytes, { first: 2 }), /sequence/);
    await assert.rejects(decodeBatch(gzipSync('{invalid}\n'), {}));
});
test('compressed bombs and oversized records are bounded', async () => {
    await assert.rejects(decodeBatch(gzipSync(' '.repeat(9 * 1024 * 1024)), {}), /too_large/);
    await assert.rejects(decodeBatch(new Uint8Array(1024 * 1024 + 1), {}), /too_large/);
});
