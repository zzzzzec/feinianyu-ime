import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('input_dataset', Path(__file__).with_name('build-input-dataset.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class ReplayTest(unittest.TestCase):
    def events(self):
        before = dict(mode='pinyin', raw_input='beijng', raw_cursor=6, selected_input_length=0, pinyin_mode=0)
        candidate = dict(id='7:2', text='北京', commit_text='北京', original_rank=2, final_rank=1)
        action = dict(id=8, kind='key', sym=32, up=False, input=before, decision_id=7)
        return [dict(type='candidates', session_id='s', decision_id=7, composition_id=1, input=before,
                     original_order_known=True, context='我去', candidates=[candidate]),
                dict(type='action_result', session_id='s', action=action, handled=True, input=dict(raw_input='')),
                dict(type='editor_commit', session_id='s', engine_action=action, source='engine', text='北京',
                     success=True, wall_time_ms=1793613600000)]

    def run_events(self, rows):
        samples=[]; builder=module.Builder(samples.append)
        for row in rows: builder.feed(row)
        return samples,builder.counts

    def test_real_typo_and_orders_are_preserved(self):
        samples,_=self.run_events(self.events())
        self.assertEqual(samples[0]['input']['raw_input'],'beijng')
        self.assertEqual(samples[0]['selected']['original_rank'],2)
        self.assertEqual(samples[0]['selected']['final_rank'],1)
        self.assertIn('not_human_gold',samples[0]['label_kind'])

    def test_delayed_editor_or_engine_result(self):
        rows=self.events(); samples,_=self.run_events([rows[0],rows[2],rows[1]])
        self.assertEqual(len(samples),1)

    def test_partial_or_failed_commits_are_not_labels(self):
        for field,value in [('success',False),('source','paste')]:
            rows=self.events();rows[-1][field]=value;self.assertFalse(self.run_events(rows)[0])
        rows=self.events(); rows[1]['input']['raw_input']='jing';self.assertFalse(self.run_events(rows)[0])

    def test_missing_or_ambiguous_candidates_are_not_invented(self):
        rows=self.events(); rows[0]['candidates']*=2;self.assertFalse(self.run_events(rows)[0])
        rows=self.events(); rows[0]['original_order_known']=False;self.assertFalse(self.run_events(rows)[0])

    def test_session_boundary_does_not_join_old_decisions(self):
        rows=self.events(); rows[-1]['session_id']='new';self.assertFalse(self.run_events(rows)[0])

    def test_one_composition_is_one_sample(self):
        rows=self.events(); samples,counts=self.run_events(rows+[copy.deepcopy(rows[-1])])
        self.assertEqual(len(samples),1);self.assertEqual(counts['duplicate_composition'],1)


if __name__=='__main__':unittest.main()
