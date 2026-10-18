"""Regressions from actual saved answers; no new provider calls or overwritten scores."""
import copy
import json
from pathlib import Path
import unittest

import score_v2 as scorer

HARNESS = Path(__file__).resolve().parent.parent
EVIDENCE = HARNESS / 'verification/evidence/REST-001-equivalences-v2.1'
PIN = 'd6ed60fa90d5183dc6b6582effdbfef99d4cb88f9436ae64dbccc0c015d3b317'


class SavedRepairRegressionTest(unittest.TestCase):
    def grade(self, runner, mutate=None):
        answer = scorer.read_json(HARNESS / f'answers/runs/v2/REST-001-{runner}-1.json')
        if mutate:
            mutate(answer)
        return scorer.score_change(scorer.read_json(HARNESS / 'records/v2/REST-001.json'), answer,
            oracle_bundle_path=EVIDENCE / 'oracle-bundle-v2.1.json', trusted_oracle_sha256=PIN,
            artifact_root=EVIDENCE, run_receipt_bundle_path=answer['run_receipt_bundle']['path'],
            trusted_run_receipts_sha256=answer['run_receipt_bundle']['sha256'])

    def test_record_accessor_spelling_is_accepted_without_erasing_scope_extras(self):
        report = self.grade('pi-codex')
        self.assertTrue(report['benchmark_eligible'])
        self.assertEqual(report['metrics']['actions']['recall'], 1)
        self.assertEqual(report['metrics']['edges']['recall'], 1)
        self.assertTrue(report['satisfies_required_actions'])
        self.assertTrue(report['satisfies_any_repair'])
        self.assertEqual(report['critical_downstream_action_misses'], [])
        extras = report['differences']['repositories']['extras_unverified']
        self.assertIn({'repo': 'shipment-service', 'runtime_status': 'unchanged'}, extras)

    def test_proven_method_mapping_gets_action_credit_but_conflicting_obligation_remains(self):
        report = self.grade('pi-codex-gortex-first')
        self.assertEqual(report['metrics']['actions']['recall'], 1)
        self.assertEqual(report['metrics']['edges']['recall'], 1)
        self.assertTrue(report['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['kind'] == 'obligations' and item['claim']['repo'] == 'account-service'
                            for item in report['contradictions']))

    def test_aliases_never_accept_wrong_repository_field_or_member(self):
        for change in ({'repo': 'basket-service'}, {'after': 'wrongPostcode'},
                       {'target': 'com.poc.order.infrastructure.client.AccountClient.addressUnrelated'}):
            with self.subTest(change=change):
                def mutate(answer):
                    answer['findings']['actions'][0].update(change)
                report = self.grade('pi-codex', mutate)
                self.assertEqual(report['metrics']['actions']['recall'], 0)
                self.assertFalse(report['satisfies_required_actions'])
                self.assertEqual(len(report['critical_downstream_action_misses']), 1)

    def test_extra_correctly_named_action_cannot_hide_a_wrong_known_mapping(self):
        def mutate(answer):
            wrong = copy.deepcopy(answer['findings']['actions'][0])
            wrong['target'] = 'com.poc.order.infrastructure.client.AccountClient.address'
            wrong['after'] = 'wrongPostcode'
            answer['findings']['actions'].append(wrong)
        report = self.grade('pi-codex', mutate)
        self.assertTrue(report['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['kind'] == 'actions' for item in report['contradictions']))


if __name__ == '__main__':
    unittest.main()
