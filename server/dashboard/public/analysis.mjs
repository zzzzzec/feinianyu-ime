const increment = (map, key, by = 1) => map.set(key, (map.get(key) || 0) + by);
const bounded = (map, key, value) => { map.set(key, value); if (map.size > 1024) map.delete(map.keys().next().value); };
export const percentile = (values, q) => values.length ? [...values].sort((a, b) => a - b)[Math.max(0, Math.ceil(q * values.length) - 1)] : null;
export function dayKey(time) {
    const date = new Date(time);
    return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
}
export const synthetic = row => row.synthetic === true || /^synthetic(?:[_-]|$)/.test(row.type || row.kind || '') || /^dev\.feinianyu\.synthetic\./.test(row.application || '');

/** Same conservative observed-choice rules as scripts/build-input-dataset.py. */
export class Choices {
    constructor(emit) { this.emit = emit; this.decisions = new Map(); this.results = new Map(); this.waiting = new Map(); this.seen = new Set(); this.skipped = new Map(); }
    key(row, id) { return (row.session_id || row._device) + ':' + id; }
    feed(row) {
        if (row.type === 'candidates') {
            if (!Array.isArray(row.candidates) || row.candidates.length > 256) return;
            bounded(this.decisions, this.key(row, row.decision_id), { ...row, candidates: [...(row.candidates || [])] });
        } else if (row.type === 'candidate_page' && row.offset >= 0) {
            const decision = this.decisions.get(this.key(row, row.decision_id));
            if (!decision) return;
            const ranks = new Set(decision.candidates.map(c => c.final_rank));
            for (const c of Array.isArray(row.candidates) ? row.candidates : []) {
                if (decision.candidates.length >= 256) break;
                if (c.display_rank > 16 && !ranks.has(c.display_rank)) {
                    decision.candidates.push({ ...c, final_rank: c.display_rank, original_rank: c.display_rank }); ranks.add(c.display_rank);
                }
            }
        } else if (row.type === 'action_result') {
            const key = this.key(row, row.action?.id); bounded(this.results, key, row);
            for (const commit of this.waiting.get(key) || []) this.commit(commit);
            this.waiting.delete(key);
        } else if (row.type === 'editor_commit' && row.source === 'engine') {
            const key = this.key(row, row.engine_action?.id);
            if (this.results.has(key)) this.commit(row);
            else {
                const rows = this.waiting.get(key) || []; if (rows.length < 8) rows.push(row);
                bounded(this.waiting, key, rows);
            }
        }
    }
    skip(reason) { increment(this.skipped, reason); }
    commit(row) {
        const action = row.engine_action || {}, decision = this.decisions.get(this.key(row, action.decision_id));
        const result = this.results.get(this.key(row, action.id));
        if (!row.success || !result?.handled || !decision) return this.skip('无法关联或提交失败');
        const before = action.input || {}, captured = decision.input || {}, after = result.input || {}, raw = before.raw_input;
        if (!['pinyin', 'shuangpin'].includes(before.mode) || typeof raw !== 'string' || !raw || before.pinyin_mode !== 0 || before.selected_input_length !== 0 || before.raw_cursor !== Array.from(raw).length || after.raw_input !== '' || ['raw_input', 'raw_cursor', 'selected_input_length'].some(k => before[k] !== captured[k])) return this.skip('部分选择或特殊状态');
        if (!decision.original_order_known) return this.skip('原序未知');
        const matches = decision.candidates.filter(c => row.text === c.text || row.text === c.commit_text);
        if (matches.length !== 1) return this.skip('候选缺失或有歧义');
        if (![matches[0].original_rank, matches[0].final_rank].every(n => Number.isSafeInteger(n) && n > 0)) return this.skip('候选位置无效');
        const key = this.key(row, decision.composition_id);
        if (this.seen.has(key)) return this.skip('同轮组合输入重复');
        this.seen.add(key);
        this.emit({ time: row.wall_time_ms, pinyin: raw, text: row.text, context: decision.context || '', original: matches[0].original_rank, rank: matches[0].final_rank,
            mode: before.mode, device: row._device, version: row.app_version || '', method: action.kind === 'candidate_click' ? '点击' : action.sym === 32 ? '空格' : '按键' });
    }
}

export class Analysis {
    constructor({ from = 0, includeTests = false, now = Date.now() } = {}) {
        this.from = from; this.includeTests = includeTests; this.now = now;
        this.events = 0; this.chars = 0; this.duplicates = 0; this.tests = 0; this.invalidTime = 0;
        this.seen = new Set(); this.days = new Map(); this.types = new Map(); this.devices = new Map(); this.apps = new Map(); this.sources = new Map();
        this.testSessions = new Set();
        this.samples = []; this.choiceCount = 0; this.originalRanks = [0, 0, 0, 0]; this.ranks = [0, 0, 0, 0];
        this.latencies = []; this.rankTimes = []; this.ttft = []; this.states = new Map();
        this.inputShown = new Set(); this.inputAccepted = new Set(); this.perfShown = new Set(); this.perfAccepted = new Set(); this.dictionaryHits = new Set();
        this.builder = new Choices(sample => {
            this.choiceCount++; this.originalRanks[Math.min(4, Math.max(1, sample.original || 4)) - 1]++;
            this.ranks[Math.min(4, Math.max(1, sample.rank || 4)) - 1]++;
            this.samples.push(sample); this.samples.sort((a, b) => b.time - a.time); if (this.samples.length > 40) this.samples.pop();
        });
    }
    feed(row, meta, index) {
        const id = Number.isSafeInteger(row.record_id) ? `${row.installation_id || meta.device}/${row.generation || meta.generation}/${row.record_id}` : meta.key + ':' + index;
        if (this.seen.has(id)) { this.duplicates++; return; } this.seen.add(id);
        const directTest = synthetic(row);
        if (directTest && row.session_id) this.testSessions.add(row.session_id);
        if (directTest || (row.session_id && this.testSessions.has(row.session_id))) { this.tests++; if (!this.includeTests) return; }
        let time = row.wall_time_ms;
        if (!Number.isFinite(time) || time < 946684800000 || time > this.now + 86400000) { this.invalidTime++; time = Date.parse(meta.uploaded); }
        if (!Number.isFinite(time) || time < this.from) return;
        row = { ...row, _device: meta.device, wall_time_ms: time };
        this.events++; increment(this.days, dayKey(time)); increment(this.types, row.type || row.kind || 'unknown');
        const device = this.devices.get(meta.device) || { id: meta.device, events: 0, latest: 0, versions: new Set() };
        device.events++; device.latest = Math.max(device.latest, time); if (row.app_version) device.versions.add(row.app_version); this.devices.set(meta.device, device);
        if (row.type === 'session_start' && row.application) increment(this.apps, row.application);
        if (row.type === 'editor_commit' && row.success) { this.chars += Array.from(row.text || '').length; increment(this.sources, row.source || 'unknown'); }
        if (row.type === 'candidates' && row.suzu_enabled && Number.isFinite(row.ranking_ms) && row.ranking_ms > 0) this.rankTimes.push(row.ranking_ms);
        const request = (row.session_id || meta.device) + ':' + row.request_id;
        if (row.type === 'completion_display' && row.shown) this.inputShown.add(request);
        if (row.type === 'completion_accept') this.inputAccepted.add(request);
        if (row.type === 'completion_result' && row.result?.source === 'dictionary' && row.result?.matched) this.dictionaryHits.add('input:' + request);
        if (row.type === 'completion_performance' && row.performance) {
            const p = row.performance, key = meta.device + ':' + p.session_id + ':' + p.request_id;
            if (p.phase === 'ui') {
                increment(this.states, p.state || 'unknown');
                if (p.state === 'shown') { this.perfShown.add(key); if (Number.isFinite(p.end_to_end_ms) && p.end_to_end_ms >= 0) this.latencies.push(p.end_to_end_ms); }
            }
            if (p.phase === 'accept') this.perfAccepted.add(key);
            if (p.phase === 'inference') {
                const value = p.first_token_ms ?? p.ttft_ms ?? p.time_to_first_token_ms;
                if (Number.isFinite(value) && value >= 0) this.ttft.push(value);
            }
            if (p.phase === 'dictionary' && p.matched) this.dictionaryHits.add('perf:' + key);
        }
        this.builder.feed(row);
    }
    summary() {
        // The two streams have different session IDs. Keep one coherent stream;
        // prefer performance records, which also work with text capture disabled.
        const usePerformance = this.perfShown.size > 0;
        const shownSet = usePerformance ? this.perfShown : this.inputShown;
        const acceptedSet = usePerformance ? this.perfAccepted : this.inputAccepted;
        const shown = shownSet.size, accepted = [...acceptedSet].filter(key => shownSet.has(key)).length;
        return { events: this.events, chars: this.chars, choices: this.choiceCount, devices: this.devices.size, shown, accepted,
            acceptedRate: shown ? accepted / shown : null, completionSource: usePerformance ? 'performance' : 'input', top1: this.choiceCount ? this.ranks[0] / this.choiceCount : null,
            uiP50: percentile(this.latencies, .5), uiP95: percentile(this.latencies, .95), rankP50: percentile(this.rankTimes, .5), ttftP50: percentile(this.ttft, .5),
            perfSamples: this.latencies.length, duplicates: this.duplicates, testEvents: this.tests, invalidTime: this.invalidTime,
            days: Object.fromEntries(this.days), types: Object.fromEntries(this.types), sources: Object.fromEntries(this.sources), states: Object.fromEntries(this.states),
            ranks: this.ranks, originalRanks: this.originalRanks, samples: this.samples, skipped: Object.fromEntries(this.builder.skipped),
            pendingLinks: [...this.builder.waiting.values()].reduce((sum, rows) => sum + rows.length, 0),
            deviceRows: [...this.devices.values()].map(d => ({ ...d, versions: [...d.versions] })), apps: Object.fromEntries(this.apps), labelKind: 'observed_behavior_not_human_gold' };
    }
}
