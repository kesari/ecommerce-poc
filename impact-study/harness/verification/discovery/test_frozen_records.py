"""Offline integrity and adversarial scoring of the twelve independently proved cases."""
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / 'harness/scoring'))
sys.path.insert(0, str(HERE))
import score_v2
from freeze_records import prepare


class FrozenDiscoveryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = json.loads((HERE / 'frozen/cases.json').read_text())['cases']
        cls.prepared = prepare(HERE / 'record-candidates/twelve-drafts-v2',
                              json.loads((HERE / 'record-candidates/approved-proof-pins.json').read_text()),
                              json.loads((HERE / 'record-candidates/reviewed-record-sha256.json').read_text()))

    def evaluate(self, row, mutate=None):
        record = json.loads((ROOT / row['record_path']).read_text())
        plan = record['repair_alternatives'][0]
        answer = {'protocol_version':2, 'change_id':record['change_id'], 'snapshot_sha256':record['snapshot_sha256'],
                  'findings':{kind:[{k:c[k] for k in score_v2.FIELDS[kind]} for c in record.get(kind,plan.get(kind,[]))]
                              for kind in score_v2.FIELDS}}
        if mutate:
            mutate(answer['findings'])
        return score_v2.score_change(record,answer, oracle_bundle_path=ROOT / row['oracle_bundle_path'],
                                     trusted_oracle_sha256=row['oracle_bundle_sha256'], artifact_root=ROOT)

    def test_frozen_files_reproduce_independent_review(self):
        self.assertEqual(len(self.rows),12)
        for row in self.rows:
            with self.subTest(case=row['case_id']):
                record, bundle = self.prepared[row['case_id']]
                self.assertEqual(record,json.loads((ROOT / row['record_path']).read_text()))
                self.assertEqual(bundle,json.loads((ROOT / row['oracle_bundle_path']).read_text()))
                self.assertEqual(hashlib.sha256((ROOT / row['record_path']).read_bytes()).hexdigest(),row['record_sha256'])
                self.assertEqual(hashlib.sha256((ROOT / row['oracle_bundle_path']).read_bytes()).hexdigest(),row['oracle_bundle_sha256'])
                self.assertTrue(self.evaluate(row)['complete_supported_impact'])

    def test_omitting_any_required_impact_or_repair_claim_is_incomplete(self):
        for row in self.rows:
            record,_ = self.prepared[row['case_id']]
            for kind in ('repositories','edges','actions','obligations'):
                source = record.get(kind,record['repair_alternatives'][0].get(kind,[]))
                for i in range(len(source)):
                    with self.subTest(case=row['case_id'],kind=kind,index=i):
                        self.assertFalse(self.evaluate(row,lambda f:f[kind].pop(i))['complete_supported_impact'])

    def test_wrong_action_values_and_repository_are_rejected(self):
        for row in self.rows:
            if row['compatible_control']:
                continue
            with self.subTest(case=row['case_id']):
                self.assertFalse(self.evaluate(row,lambda f:f['actions'][0].update(after='wrong'))['complete_supported_impact'])
                self.assertFalse(self.evaluate(row,lambda f:f['actions'][0].update(repo='commerce-platform'))['complete_supported_impact'])

    def test_compatible_controls_reject_unnecessary_production_edits(self):
        for row in self.rows:
            if not row['compatible_control']:
                continue
            with self.subTest(case=row['case_id']):
                def add_action(findings):
                    findings['actions'].append({'repo':findings['repositories'][0]['repo'],'target':'Invented.edit',
                                               'operation':'read_json_field','before':'a','after':'b'})
                report=self.evaluate(row,add_action)
                self.assertFalse(report['complete_supported_impact'])
                self.assertTrue(report['contradictions'])

    def test_independent_review_pin_is_mandatory(self):
        pins=json.loads((HERE / 'record-candidates/approved-proof-pins.json').read_text())
        reviewed=json.loads((HERE / 'record-candidates/reviewed-record-sha256.json').read_text())
        reviewed['H01']='0'*64
        with self.assertRaisesRegex(ValueError,'review pin mismatch'):
            prepare(HERE / 'record-candidates/twelve-drafts-v2',pins,reviewed)
