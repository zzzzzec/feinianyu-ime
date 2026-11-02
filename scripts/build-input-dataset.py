"""Build observed-choice replay samples from a local Feinianyu dataset ZIP.

Never prints text. All examples remain private; human correctness is not inferred.
"""
import argparse
from collections import Counter, OrderedDict
from datetime import datetime, timezone
import json
from pathlib import Path
import zipfile


class Builder:
    def __init__(self, emit):
        self.emit = emit
        self.decisions = OrderedDict()
        self.results = OrderedDict()
        self.waiting = {}
        self.seen = set()
        self.counts = Counter()

    @staticmethod
    def bounded(cache, key, value):
        cache[key] = value
        if len(cache) > 1024:
            cache.popitem(last=False)

    def feed(self, event):
        self.counts['events'] += 1
        session = event.get('session_id')
        kind = event.get('type')
        if kind == 'candidates':
            self.bounded(self.decisions, (session, event.get('decision_id')), event)
        elif kind == 'candidate_page' and event.get('offset', -1) >= 0:
            decision = self.decisions.get((session, event.get('decision_id')))
            if decision:
                existing = {c['final_rank'] for c in decision['candidates']}
                for row in event.get('candidates', []):
                    rank = row.get('display_rank')
                    # Suzu only changes the first ten. Larger fetched ranks retain engine order.
                    if rank and rank > 16 and rank not in existing:
                        decision['candidates'].append(dict(row, final_rank=rank, original_rank=rank,
                            id=str(event['decision_id']) + ':' + str(rank), consumed_range=None))
        elif kind == 'action_result':
            action = event.get('action') or {}
            key = session, action.get('id')
            self.bounded(self.results, key, event)
            for pending in self.waiting.pop(key, []):
                self.commit(pending)
        elif kind == 'editor_commit':
            if event.get('source') != 'engine':
                self.counts['non_pinyin_commits'] += 1
                return
            key = session, (event.get('engine_action') or {}).get('id')
            if key not in self.results:
                self.waiting.setdefault(key, []).append(event)
                if len(self.waiting) > 1024:
                    self.waiting.pop(next(iter(self.waiting)))
                    self.counts['missing_action_result'] += 1
            else:
                self.commit(event)

    def commit(self, event):
        self.counts['engine_commits'] += 1
        action = event.get('engine_action') or {}
        session = event.get('session_id')
        decision = self.decisions.get((session, action.get('decision_id')))
        result = self.results.get((session, action.get('id')), {})
        if not event.get('success') or not result.get('handled') or not decision:
            self.counts['unlinked_or_failed'] += 1
            return
        before = action.get('input') or {}
        captured = decision.get('input') or {}
        after = result.get('input') or {}
        raw = before.get('raw_input')
        if (before.get('mode') not in ('pinyin', 'shuangpin') or not raw or
                before.get('pinyin_mode') != 0 or before.get('selected_input_length') != 0 or
                before.get('raw_cursor') != len(raw) or after.get('raw_input') != '' or
                any(before.get(k) != captured.get(k) for k in ('raw_input', 'raw_cursor', 'selected_input_length'))):
            self.counts['partial_special_or_stale'] += 1
            return
        if not decision.get('original_order_known'):
            self.counts['unknown_original_order'] += 1
            return
        text = event.get('text')
        matches = [c for c in decision['candidates'] if text in (c.get('text'), c.get('commit_text'))]
        if len(matches) != 1:
            self.counts['uncaptured_or_ambiguous_target'] += 1
            return
        group = session, decision.get('composition_id')
        if group in self.seen:
            self.counts['duplicate_composition'] += 1
            return
        self.seen.add(group)
        date = datetime.fromtimestamp(event['wall_time_ms'] / 1000, timezone.utc).date().isoformat()
        sample = dict(schema=1, task='observed_candidate_choice', label_kind='observed_behavior_not_human_gold',
            session_id=session, composition_id=group[1], decision_id=action['decision_id'], date_utc=date,
            wall_time_ms=event['wall_time_ms'], input=before, context=decision.get('context', ''),
            candidates=decision['candidates'], selected=matches[0], committed_text=text,
            selection_method='candidate_click' if action.get('kind') == 'candidate_click' else
                'space' if action.get('sym') == 32 else 'key',
            candidate_consumed_ranges_known=False, app_version=event.get('app_version'), build_commit=event.get('build_commit'))
        self.emit(sample)
        self.counts['samples'] += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('--output-dir', required=True, type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    target = args.output_dir / 'observed-choices.jsonl'
    if target.exists():
        parser.error('Choose a new output directory; existing samples are not overwritten.')
    with target.open('w', encoding='utf-8', newline='\n') as output, zipfile.ZipFile(args.export) as source:
        manifest = json.loads(source.read('manifest.json'))
        if manifest.get('schema') != 1:
            parser.error('Unsupported export schema')
        builder = Builder(lambda row: output.write(json.dumps(row, ensure_ascii=False) + '\n'))
        with source.open('events.jsonl') as events:
            for line in events:
                builder.feed(json.loads(line))
    builder.counts['pending_without_result'] = sum(map(len, builder.waiting.values()))
    report = dict(counts=dict(builder.counts), note='Behavioral replay, not human-gold accuracy. '
        'Split by date/session; do not count pinyin prefixes as independent examples. '
        'Candidate coverage ranges are unknown; partial/special/ambiguous decisions are excluded. '
        'Raw events retain paste, completion, edits and exclusions for separate analysis.')
    (args.output_dir / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
