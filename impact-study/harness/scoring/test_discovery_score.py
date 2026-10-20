"""Tests of the discovery endpoint, independent of paid agent runs."""
import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import score_v2 as score
from test_score_v2 import fixture


class DiscoveryScoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.record, self.answer = fixture()
        self.record.update(status='verified', evaluation_revision='2.2',
                           repository_universe=['account', 'order', 'shipment', 'bff', 'other'])
        self.evidence = self.root / 'observed.json'
        self.evidence.write_text('{"independently_reviewed":true}\n')
        self.refresh_oracle()

    def refresh_oracle(self):
        claims = score.record_claim_keys(self.record)
        self.bundle = {'protocol_version': 2, 'change_id': self.record['change_id'],
                       'snapshot_sha256': self.record['snapshot_sha256'],
                       'record_sha256': hashlib.sha256(score.canonical(self.record).encode()).hexdigest(),
                       'scoring_policy': {'mode': 'scoped_reference_v1'},
                       'artifacts': [{'path': self.evidence.name, 'sha256': hashlib.sha256(self.evidence.read_bytes()).hexdigest()}],
                       'attestations': [{'kind': k, 'claim': score.claim_object(k, c), 'artifact_paths': [self.evidence.name]}
                                        for k, values in claims.items() for c in sorted(values)]}
        self.save_oracle()

    def save_oracle(self):
        self.path = self.root / 'oracle.json'
        self.path.write_text(json.dumps(self.bundle, sort_keys=True))
        self.pin = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def evaluate(self):
        return score.score_change(self.record, self.answer, oracle_bundle_path=self.path,
                                  trusted_oracle_sha256=self.pin, artifact_root=self.root)

    def test_full_supported_answer_passes(self):
        report = self.evaluate()
        self.assertTrue(report['complete_supported_impact'])
        self.assertTrue(report['benchmark_eligible'])

    def test_required_actions_alone_do_not_establish_complete_impact(self):
        for kind in ('repositories', 'edges', 'obligations'):
            with self.subTest(kind=kind):
                saved = self.answer['findings'][kind]
                self.answer['findings'][kind] = []
                report = self.evaluate()
                self.assertTrue(report['satisfies_required_actions'])
                self.assertFalse(report['complete_supported_impact'])
                self.assertTrue(report['complete_supported_impact_details']['missing'][kind])
                self.answer['findings'][kind] = saved

    def test_supporting_symbols_are_diagnostic_not_primary(self):
        self.answer['findings']['symbols'] = []
        report = self.evaluate()
        self.assertTrue(report['complete_supported_impact'])
        self.assertEqual(report['metrics']['symbols']['recall'], 0)

    def test_wrong_edge_direction_or_field_blocks_primary(self):
        for changed in ({'provider': 'order', 'consumer': 'account'}, {'field': 'wrong'}):
            with self.subTest(changed=changed):
                original = copy.deepcopy(self.answer['findings']['edges'][0])
                self.answer['findings']['edges'][0].update(changed)
                self.assertFalse(self.evaluate()['complete_supported_impact'])
                self.answer['findings']['edges'][0] = original

    def test_nonrepository_endpoint_is_invalid_without_erasing_correct_action(self):
        self.answer['findings']['edges'][0]['consumer'] = 'shop.AddressResponse'
        report = self.evaluate()
        self.assertFalse(report['complete_supported_impact'])
        self.assertTrue(report['satisfies_required_actions'])
        self.assertEqual(report['metrics']['edges']['invalid'], 1)
        self.assertEqual(report['metrics']['edges']['correct'], 0)

    def test_material_extras_block_complete_without_being_declared_false(self):
        self.answer['findings']['obligations'].append({'repo': 'other', 'obligation': 'production_edit'})
        report = self.evaluate()
        self.assertFalse(report['complete_supported_impact'])
        self.assertEqual(report['contradictions'], [])
        self.assertEqual(len(report['complete_supported_impact_details']['unverified_material_claims']), 1)

    def test_extra_background_context_is_allowed(self):
        self.answer['findings']['repositories'].append({'repo': 'other', 'runtime_status': 'unchanged'})
        self.answer['findings']['edges'].append({'provider': 'other', 'consumer': 'bff', 'type': 'rest', 'identifier': 'GET /health', 'field': ''})
        self.assertTrue(self.evaluate()['complete_supported_impact'])

    def test_compatible_control_and_unnecessary_edit(self):
        self.record['edges'] = self.record['symbols'] = []
        self.record['repositories'][1]['runtime_status'] = 'unchanged'
        plan = self.record['repair_alternatives'][0]
        plan['actions'] = []
        plan['obligations'][1]['obligation'] = 'validation_only'
        self.answer['findings'] = {kind: copy.deepcopy(self.record.get(kind, plan.get(kind, []))) for kind in score.FIELDS}
        self.refresh_oracle()
        self.assertTrue(self.evaluate()['complete_supported_impact'])
        self.answer['findings']['actions'].append({'repo': 'order', 'target': 'Reader.read', 'operation': 'read_json_field', 'before': 'a', 'after': 'b'})
        report = self.evaluate()
        self.assertFalse(report['complete_supported_impact'])
        self.assertTrue(report['contradictions'])

    def test_numeric_conversion_is_exact_and_validated(self):
        action = self.record['repair_alternatives'][0]['actions'][0]
        action.update(operation='scale_numeric_field', before='1', after='100')
        self.answer['findings']['actions'] = [{k: v for k, v in action.items() if k != 'critical'}]
        self.refresh_oracle()
        self.assertTrue(self.evaluate()['complete_supported_impact'])
        self.answer['findings']['actions'][0]['after'] = '10'
        self.assertFalse(self.evaluate()['complete_supported_impact'])
        for invalid in ('0', '-1', '1.0', '01', '100 INR'):
            self.answer['findings']['actions'][0]['after'] = invalid
            self.assertEqual(len(self.evaluate()['invalid_findings']), 1)

    def test_oracle_and_record_completeness_are_required(self):
        self.pin = '0' * 64
        self.assertFalse(self.evaluate()['complete_supported_impact'])
        self.refresh_oracle()
        self.bundle['attestations'] = [x for x in self.bundle['attestations'] if x['kind'] != 'repositories']
        self.save_oracle()
        self.assertFalse(self.evaluate()['complete_supported_impact'])

    def test_public_inventory_is_required_and_cannot_omit_evaluated_repo(self):
        self.record['repository_universe'].remove('order')
        self.assertEqual(self.evaluate()['status'], 'invalid_record')
        del self.record['repository_universe']
        self.assertEqual(self.evaluate()['status'], 'invalid_record')

    def test_legacy_primary_is_explicitly_not_applicable(self):
        del self.record['evaluation_revision'], self.record['repository_universe']
        self.refresh_oracle()
        self.assertIsNone(self.evaluate()['complete_supported_impact'])

    def test_discovery_cannot_bypass_contradictions_using_legacy_strict_policy(self):
        del self.bundle['scoring_policy']
        self.save_oracle()
        self.answer['findings']['obligations'].append({'repo': 'order', 'obligation': 'unaffected'})
        report = self.evaluate()
        self.assertFalse(report['complete_supported_impact'])
        self.assertTrue(report['contradictions'])
