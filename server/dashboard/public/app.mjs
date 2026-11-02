import { Analysis, dayKey } from './analysis.mjs';
import { decodeBatch } from './data.mjs';
const $ = id => document.getElementById(id);
const number = value => new Intl.NumberFormat('zh-CN').format(value || 0);
const percent = value => value === null ? '—' : (value * 100).toFixed(1) + '%';
const duration = value => value === null ? '—' : Math.round(value) + ' ms';
const bytes = value => value >= 1048576 ? (value / 1048576).toFixed(1) + ' MB' : value >= 1024 ? (value / 1024).toFixed(1) + ' KB' : value + ' B';
const time = value => new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const labels = { action: '输入动作', action_result: '动作结果', candidates: '候选快照', candidate_page: '候选页', editor_commit: '成功上屏', engine_commit: '引擎提交', session_start: '编辑会话', completion_performance: '续写性能', completion_request: '续写请求', completion_result: '续写结果', completion_display: '续写展示', completion_accept: '续写采纳', editor_selection: '光标变化', editor_delete: '删除', unattributed_commit: '未关联上屏' };
const states = { shown: '已展示', timeout: '超时', abstained: '低置信度放弃', filtered: '已过滤', late_or_stale: '过期结果', not_ready: '模型未就绪', inference_error: '推理失败', generated: '已生成', empty: '无候选' };
let all = [], listCursor = null, state = null, controller = null, generation = 0, limit = 200, summary = null;
let bad = new Map(), badRows = 0, eventLimit = false;
const cache = new Map(); let cacheBytes = 0;
function notice(text = '', error = false) { $('notice').hidden = !text; $('notice').textContent = text; $('notice').classList.toggle('error', error); }
function element(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; }
function fromTime() { const range = $('range').value; if (range === 'all') return 0; const date = new Date(); date.setHours(0, 0, 0, 0); if (range !== 'today') date.setDate(date.getDate() - Number(range) + 1); return +date; }
async function api(path, signal, raw = false) {
    const response = await fetch(path, { credentials: 'same-origin', redirect: 'error', signal, cache: 'no-store' });
    if (!response.ok) { const error = new Error([401, 403].includes(response.status) ? '请重新登录后刷新' : '云端请求失败（' + response.status + '）'); error.http = response.status; throw error; }
    return raw ? response.arrayBuffer() : response.json();
}
function cached(key, data) {
    cache.set(key, data); cacheBytes += data.byteLength;
    while (cacheBytes > 8 * 1024 * 1024 && cache.size) { const first = cache.keys().next().value; cacheBytes -= cache.get(first).byteLength; cache.delete(first); }
}
async function batch(meta, signal) {
    const key = meta.key + '#' + meta.etag;
    const buffer = cache.get(key) || await api('/api/batch?key=' + encodeURIComponent(meta.key), signal, true);
    const rows = await decodeBatch(buffer, meta);
    if (!cache.has(key)) cached(key, buffer);
    return rows;
}
function updateDevices() {
    const selected = $('device').value;
    const ids = [...new Set(all.map(m => m.device))].sort();
    $('device').replaceChildren(element('option', '', '全部设备')); $('device').firstChild.value = 'all';
    for (const id of ids) { const option = element('option', '', id.slice(0, 8)); option.value = id; $('device').append(option); }
    $('device').value = ids.includes(selected) ? selected : 'all';
}
async function load({ extendList = false, retry = false } = {}) {
    const turn = ++generation; controller?.abort(); controller = new AbortController(); const signal = controller.signal;
    $('refresh').disabled = true; $('last-check').textContent = '同步云端清单'; notice();
    try {
        let rows = extendList ? [...all] : [], cursor = extendList ? listCursor : null;
        for (let page = 0; page < 10; page++) {
            const data = await api('/api/batches' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : ''), signal);
            if (data.schema !== 1 || !Array.isArray(data.objects)) throw new Error('服务返回的数据格式不匹配');
            rows.push(...data.objects); cursor = data.cursor; if (!cursor) break;
        }
        if (turn !== generation) return;
        all = [...new Map(rows.map(m => [m.key, m])).values()]; listCursor = cursor; updateDevices();
        $('more-list').hidden = !cursor;
        const from = fromTime(), device = $('device').value;
        const eligible = all.filter(m => Date.parse(m.uploaded) >= from && (device === 'all' || m.device === device)).sort((a, b) => Date.parse(b.uploaded) - Date.parse(a.uploaded));
        const selected = eligible.slice(0, limit).sort((a, b) => a.device.localeCompare(b.device) || a.generation.localeCompare(b.generation) || (a.first || 0) - (b.first || 0) || a.key.localeCompare(b.key));
        const signature = [from, device, $('include-tests').checked, limit].join('|');
        const selectedKeys = new Set(selected.map(m => m.key));
        const reset = !state || state.signature !== signature || [...state.processed].some(([key, etag]) => !selectedKeys.has(key) || selected.find(m => m.key === key)?.etag !== etag);
        if (reset) { state = { signature, analysis: new Analysis({ from, includeTests: $('include-tests').checked }), processed: new Map(), maximum: new Map() }; bad = new Map(); badRows = 0; eventLimit = false; }
        if (retry) { for (const key of bad.keys()) state.processed.delete(key); bad.clear(); }
        let pending = selected.filter(m => state.processed.get(m.key) !== m.etag);
        if (pending.some(m => (m.first || 0) < (state.maximum.get(m.device + '/' + m.generation) || 0))) {
            state = { signature, analysis: new Analysis({ from, includeTests: $('include-tests').checked }), processed: new Map(), maximum: new Map() }; bad = new Map(); badRows = 0; eventLimit = false; pending = selected;
        }
        for (let offset = 0; offset < pending.length && !eventLimit; offset += 3) {
            const slice = pending.slice(offset, offset + 3);
            const decoded = await Promise.all(slice.map(async meta => { try { return { meta, rows: await batch(meta, signal) }; } catch (error) { if (signal.aborted || error.http === 401 || error.http === 403) throw error; return { meta, error }; } }));
            if (turn !== generation) return;
            for (const item of decoded) {
                state.processed.set(item.meta.key, item.meta.etag);
                if (item.error) { bad.set(item.meta.key, item.error.message); continue; }
                state.maximum.set(item.meta.device + '/' + item.meta.generation, item.meta.last || 0);
                for (let i = 0; i < item.rows.length; i++) {
                    if (state.analysis.seen.size >= 100000) { eventLimit = true; break; }
                    try { state.analysis.feed(item.rows[i], item.meta, i); } catch { badRows++; }
                }
            }
            $('progress').textContent = '正在分析 ' + Math.min(state.processed.size, selected.length) + ' / ' + selected.length + ' 批次';
            render(state.analysis.summary()); await new Promise(resolve => setTimeout(resolve, 0));
        }
        summary = state.analysis.summary(); render(summary);
        $('progress').textContent = '已分析 ' + number(state.processed.size - bad.size) + ' / ' + number(eligible.length) + ' 批次' + (bad.size ? ' · ' + bad.size + ' 批次读取失败' : '');
        const totalBytes = all.reduce((sum, m) => sum + m.size, 0);
        const latest = all.length ? Math.max(...all.map(m => Date.parse(m.uploaded))) : 0;
        $('storage').textContent = '云端 ' + number(all.length) + (listCursor ? '+' : '') + ' 批次 · ' + bytes(totalBytes) + (latest ? ' · 最近上传 ' + time(latest) : '');
        $('coverage').textContent = (listCursor ? '存储清单尚未列完 · ' : '') + (eventLimit ? '已达 10 万事件分析上限' : 'R2 · 只读查看 · 浏览器本地分析');
        $('more').hidden = eligible.length <= limit || eventLimit;
        $('last-check').textContent = '更新于 ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
        if (eventLimit) notice('已达到本次 10 万条事件分析上限，请选择更短的时间范围。');
        else if (listCursor) notice('存储清单尚未列完，当前统计覆盖已载入的批次。可继续载入清单。');
        else if (bad.size || badRows) notice('有 ' + bad.size + ' 批次或 ' + badRows + ' 条异常记录未纳入统计。点击刷新可重试。', true);
        else if (!all.length) notice('还没有同步数据。请在输入法“遥测”中导入配对文件，并开启采集和自动同步。');
        else if (!summary.events && summary.testEvents && !$('include-tests').checked) notice('当前范围内只有测试记录。手机数据同步后会自动显示，也可勾选“包含测试记录”查看。');
    } catch (error) {
        if (signal.aborted) return;
        notice(error.message === 'Failed to fetch' || error.message.includes('redirect') ? '连接失败或登录已过期，请重新登录后刷新。' : error.message, true);
        $('last-check').textContent = '刷新失败'; $('progress').textContent = '上次已载入的数据仍保留';
    } finally { if (turn === generation) $('refresh').disabled = false; }
}
function render(value) {
    $('event-count').textContent = number(value.events); $('char-count').textContent = number(value.chars); $('choice-count').textContent = number(value.choices); $('device-count').textContent = number(value.devices);
    $('shown').textContent = number(value.shown); $('accepted').textContent = number(value.accepted); $('accept-rate').textContent = percent(value.acceptedRate);
    $('ui-p50').textContent = duration(value.uiP50); $('ui-p95').textContent = duration(value.uiP95); $('rank-p50').textContent = duration(value.rankP50); $('top-one').textContent = percent(value.top1);
    $('perf-note').textContent = (value.completionSource === 'performance' ? '性能采集' : '输入采集') + ' · ' + (value.perfSamples ? number(value.perfSamples) + ' 次展示耗时，含停顿等待' : '开启“采集续写性能样本”后显示耗时');
    renderTrend(value.days); renderRanks(value); renderSamples(value.samples);
    $('states').replaceChildren(...Object.entries(value.states).sort((a, b) => b[1] - a[1]).slice(0, 8).map(([name, count]) => element('span', 'state-tag', (states[name] || name) + ' ' + number(count))));
    const types = Object.entries(value.types).sort((a, b) => b[1] - a[1]).slice(0, 8), max = Math.max(1, ...types.map(t => t[1]));
    $('event-types').replaceChildren(...types.map(([type, count]) => { const row = element('div', 'distribution-row'); row.append(element('span', '', labels[type] || type)); const track = element('span', 'distribution-track'), fill = element('i'); fill.style.width = count / max * 100 + '%'; track.append(fill); row.append(track, element('span', '', number(count))); return row; }));
    if (!types.length) $('event-types').append(element('div', 'empty', '暂无记录'));
    $('devices').replaceChildren(...value.deviceRows.sort((a, b) => b.latest - a.latest).slice(0, 12).map(device => { const row = element('div', 'device-row'), name = element('div', 'device-name', device.id.slice(0, 8)); name.append(element('small', '', (device.versions.join(' / ') || '版本未知') + ' · ' + time(device.latest))); row.append(name, element('span', 'device-count', number(device.events) + ' 条')); return row; }));
    if (!value.deviceRows.length) $('devices').append(element('div', 'empty', '手机同步后显示设备'));
    const skipped = Object.values(value.skipped).reduce((a, b) => a + b, 0);
    $('quality').textContent = '去重 ' + number(value.duplicates) + ' 条 · 测试记录 ' + number(value.testEvents) + ' 条 · 未构成选词样本 ' + number(skipped + value.pendingLinks) + ' 次' + (value.invalidTime ? ' · 时间异常 ' + value.invalidTime + ' 条' : '');
}
function renderTrend(counts) {
    const box = $('trend'); box.replaceChildren(); const keys = Object.keys(counts).sort();
    if (!keys.length) { box.append(element('div', 'chart-empty', '暂无采集趋势')); return; }
    const start = new Date(Math.max(fromTime() || Date.parse(keys[0] + 'T00:00:00'), Date.now() - 29 * 86400000)); start.setHours(0, 0, 0, 0);
    const days = []; for (let date = new Date(start); date <= new Date(); date.setDate(date.getDate() + 1)) days.push(dayKey(+date));
    const values = days.map(d => counts[d] || 0), max = Math.max(1, ...values), w = 600, h = 164;
    const make = (tag, attrs) => { const node = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, String(value)); return node; };
    const svg = make('svg', { viewBox: `0 0 ${w} ${h}`, role: 'img', 'aria-label': '每天采集事件数量' });
    for (let i = 0; i < 4; i++) { const y = 12 + i * 39; svg.append(make('line', { x1: 42, x2: 594, y1: y, y2: y, stroke: '#edf1f8', 'stroke-dasharray': '3 4' })); const label = make('text', { x: 32, y: y + 3, 'text-anchor': 'end' }); label.textContent = number(Math.round(max * (3 - i) / 3)); svg.append(label); }
    const points = values.map((v, i) => [days.length === 1 ? 318 : 48 + i * 536 / (days.length - 1), 129 - v / max * 112]);
    svg.append(make('path', { d: 'M' + points[0][0] + ' 129 L' + points.map(p => p.join(' ')).join(' L') + ' L' + points.at(-1)[0] + ' 129 Z', fill: '#ecf2ff' }));
    svg.append(make('polyline', { points: points.map(p => p.join(',')).join(' '), fill: 'none', stroke: '#3868ee', 'stroke-width': 2.5, 'stroke-linejoin': 'round' }));
    points.forEach((point, i) => { const circle = make('circle', { cx: point[0], cy: point[1], r: 3, fill: '#fff', stroke: '#3868ee', 'stroke-width': 2 }); const title = make('title', {}); title.textContent = days[i] + '：' + number(values[i]); circle.append(title); svg.append(circle); if (days.length < 9 || i % Math.ceil(days.length / 6) === 0 || i === days.length - 1) { const label = make('text', { x: point[0], y: 155, 'text-anchor': 'middle' }); label.textContent = days[i].slice(5).replace('-', '/'); svg.append(label); } });
    box.append(svg); $('trend-note').textContent = '按浏览器本地日期统计' + (days.length >= 30 ? ' · 展示最近 30 天' : '');
}
function renderRanks(value) {
    const max = Math.max(1, ...value.ranks, ...value.originalRanks);
    $('ranks').replaceChildren(...value.ranks.map((count, i) => { const row = element('div', 'rank-row'); row.append(element('span', '', i === 3 ? '4+' : String(i + 1))); const tracks = element('div', 'rank-track'); for (const [n, cls] of [[value.originalRanks[i], 'original'], [count, '']]) { const track = element('span', cls), fill = element('i'); fill.style.width = n / max * 100 + '%'; track.title = (cls ? '原序 ' : '展示序 ') + number(n); track.append(fill); tracks.append(track); } row.append(tracks, element('span', 'rank-value', number(count))); return row; }));
}
function renderSamples(samples) {
    const hide = $('hide-text').checked;
    $('samples').replaceChildren(...samples.map(sample => {
        const row = element('tr'); row.append(element('td', '', time(sample.time)), element('td', '', hide ? '•••' : sample.pinyin), element('td', '', hide ? '•••' : sample.text));
        const ranks = element('td'), pill = element('span', 'rank-pill'); pill.append(element('span', '', String(sample.original)), element('span', '', '→'), element('strong', '', String(sample.rank))); ranks.append(pill); row.append(ranks, element('td', '', sample.method));
        const context = element('td'); if (sample.context && !hide) { const details = element('details'); details.append(element('summary', '', '查看前文'), element('p', '', sample.context)); context.append(details); } else context.textContent = '—'; row.append(context); return row;
    })); $('sample-empty').hidden = samples.length > 0;
}
$('refresh').addEventListener('click', () => load({ retry: true }));
for (const id of ['range', 'device', 'include-tests']) $(id).addEventListener('change', () => { limit = 200; load(); });
$('hide-text').addEventListener('change', () => { if (summary) renderSamples(summary.samples); });
$('more').addEventListener('click', () => { limit += 200; load(); });
$('more-list').addEventListener('click', () => load({ extendList: true }));
$('export').addEventListener('click', () => { if (!summary) return; const value = { schema: 1, exported_at: new Date().toISOString(), coverage: $('progress').textContent, ...summary }; if ($('hide-text').checked) value.samples = value.samples.map(({ pinyin, text, context, ...rest }) => rest); const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' })); const link = element('a'); link.href = url; link.download = 'feinianyu-dashboard-' + dayKey(Date.now()) + '.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); });
async function start() { try { const info = await api('/api/info'); $('viewer').textContent = info.email; } catch { $('viewer').textContent = '登录状态异常'; } await load(); }
setInterval(() => { if (!document.hidden && !$('refresh').disabled) load(); }, 60000);
document.addEventListener('visibilitychange', () => { if (!document.hidden && !$('refresh').disabled) load(); });
start();
