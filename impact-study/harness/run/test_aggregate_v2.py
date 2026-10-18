import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import aggregate_v2 as aggregate

score = aggregate.score_v2


class AggregateV2Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runs = self.root / 'runs'
        (self.runs / 'receipts').mkdir(parents=True)
        self.record = {
            'protocol_version': 2, 'change_id': 'REST-777', 'status': 'verified',
            'category': 'rest', 'query': 'What changes?', 'origin_repo': 'provider',
            'proposed_change': {'repo': 'provider', 'diff': '- oldField\n+ newField'},
            'snapshot_sha256': '1' * 64,
            'repositories': [{'repo': 'provider', 'runtime_status': 'unchanged'},
                             {'repo': 'consumer', 'runtime_status': 'broken'}],
            'symbols': [{'repo': 'consumer', 'fqn': 'shop.Response.oldField'}],
            'edges': [{'provider': 'provider', 'consumer': 'consumer', 'type': 'rest',
                       'identifier': 'GET /resource', 'field': 'newField'}],
            'repair_alternatives': [{
                'name': 'consumer-binding',
                'obligations': [{'repo': 'provider', 'obligation': 'validation_only'},
                                {'repo': 'consumer', 'obligation': 'production_edit'}],
                'actions': [{'repo': 'consumer', 'target': 'shop.Response.oldField',
                             'operation': 'read_json_field', 'before': 'oldField', 'after': 'newField',
                             'critical': True}],
            }],
        }
        self.findings = {kind: copy.deepcopy(self.record.get(kind, [])) for kind in score.FIELDS}
        self.findings['actions'] = [{key: value for key, value in self.record['repair_alternatives'][0]['actions'][0].items()
                                     if key != 'critical'}]
        self.findings['obligations'] = copy.deepcopy(self.record['repair_alternatives'][0]['obligations'])
        self.record_path = self.root / 'record.json'
        self.record_path.write_text(json.dumps(self.record))
        artifact = self.root / 'observations.json'
        artifact.write_text('{"baseline":"pass","changed":"fail","repaired":"pass"}')
        self.oracle = self.root / 'oracle.json'
        self.oracle.write_text(json.dumps({
            'protocol_version': 2, 'change_id': self.record['change_id'],
            'snapshot_sha256': self.record['snapshot_sha256'],
            'record_sha256': hashlib.sha256(score.canonical(self.record).encode()).hexdigest(),
            'artifacts': [{'path': artifact.name, 'sha256': aggregate.digest(artifact)}],
            'attestations': [{'kind': kind, 'claim': claim, 'artifact_paths': [artifact.name]}
                             for kind, claims in self.findings.items() for claim in claims],
        }))
        self.oracle_pin = aggregate.digest(self.oracle)

    def tearDown(self):
        self.temp.cleanup()

    def collect(self):
        return aggregate.aggregate_runs(self.runs, self.record_path, self.oracle, self.oracle_pin, self.root)

    def write_run(self, name='first', *, wrong=False, product=False, **metadata):
        answer = {'protocol_version': 2, 'change_id': self.record['change_id'],
                  'snapshot_sha256': self.record['snapshot_sha256'], 'findings': copy.deepcopy(self.findings),
                  'runner': 'agent', 'contestant': 'agent-only', 'model': 'provider/model', 'model_digest': None,
                  'pi_version': 'test', 'run_started_at': name,
                  'harness_sha256': '2' * 64, 'prompt_sha256': '3' * 64,
                  'contestant_config_sha256': '4' * 64, 'estate_sha256': '5' * 64, 'index_sha256': 'none',
                  'product_provenance': None, 'product_playbook': None, 'product_tool_receipts': [],
                  'product_assisted_eligible': None, 'tokens_consumed': 25, 'cost_usd': 0.01, 'elapsed_seconds': 2,
                  'budget_policy': {'timeout_seconds': 1800, 'max_tokens': 1000000, 'max_cost_usd': 2,
                                    'cost_enforcement': 'catalog_context_reservation',
                                    'token_enforcement': 'context_window_reservation',
                                    'retries': False, 'compaction': False},
                  **metadata}
        if wrong:
            answer['findings']['actions'] = []
        if product:
            output = '{}'
            answer.update(contestant='gortex-first', product_assisted_eligible=True,
                          product_provenance={'mode': 'real_product', 'indexed_estate_sha256': answer['estate_sha256']},
                          product_playbook=[{'tool': 'product', 'parameters': {'query': 'Response'},
                                             'receipt_id': 'r1', 'ok': True}],
                          product_tool_receipts=[{
                              'id': 'r1', 'tool': 'product', 'operation': 'symbol', 'parameters': {'query': 'Response'},
                              'requested_query': 'Response', 'executed_query': 'Response', 'success': True,
                              'output': output, 'output_normalized': output,
                              'output_sha256': 'a' * 64, 'output_normalized_sha256': hashlib.sha256(output.encode()).hexdigest(),
                              'output_bytes': 2, 'output_full_bytes': 2, 'truncated': False, 'duration_ms': 5, 'error': None,
                          }])
        envelope = {field: answer[field] for field in (
            'protocol_version', 'change_id', 'snapshot_sha256', 'run_started_at', 'prompt_sha256',
            'estate_sha256', 'product_provenance', 'product_playbook')}
        envelope['budget_policy'] = answer['budget_policy']
        envelope['usage'] = {field: answer[field] for field in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')}
        envelope['receipts'] = answer['product_tool_receipts']
        envelope_path = self.runs / 'receipts' / f'{name}.json'
        envelope_path.write_text(json.dumps(envelope))
        receipt_pin = aggregate.digest(envelope_path)
        answer['run_receipt_bundle'] = {'path': str(envelope_path), 'sha256': receipt_pin}
        answer_path = self.runs / f'{name}.json'
        answer_path.write_text(json.dumps(answer))
        report = score.score_change(self.record, answer, oracle_bundle_path=self.oracle,
                                    trusted_oracle_sha256=self.oracle_pin, artifact_root=self.root,
                                    run_receipt_bundle_path=envelope_path, trusted_run_receipts_sha256=receipt_pin)
        self.assertTrue(report['benchmark_eligible'], report)
        report.update(answer_sha256=aggregate.digest(answer_path), ground_truth_sha256=aggregate.digest(self.record_path),
                      scorer_sha256=aggregate.digest(aggregate.HARNESS / 'scoring' / 'score_v2.py'),
                      answer_schema_sha256=aggregate.digest(aggregate.HARNESS / 'schema' / 'contestant-answer-v2.schema.json'),
                      oracle_bundle_sha256=self.oracle_pin, run_receipt_bundle_sha256=receipt_pin)
        report_path = self.runs / f'{name}.score.json'
        report_path.write_text(json.dumps(report))
        return answer_path, report_path, envelope_path

    def assert_rejected(self, reason):
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 0)
        self.assertEqual(len(result['rejected']), 1)
        self.assertIn(reason, result['rejected'][0]['reason'])

    def test_valid_incorrect_runs_are_scored_with_critical_misses(self):
        self.write_run(wrong=True)
        result = self.collect()
        cohort = result['cohorts'][0]
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(cohort['metrics']['actions']['recall']['median'], 0)
        self.assertEqual(cohort['critical_downstream_action_miss_count']['median'], 1)
        self.assertEqual(cohort['satisfies_any_repair'], 0)
        self.assertEqual(cohort['efficiency']['tokens_consumed']['median'], 25)
        self.assertNotIn('composite', json.dumps(result))

    def test_shared_cohort_repeats_keep_cost_separate_from_correctness(self):
        self.write_run('cheap')
        self.write_run('expensive', tokens_consumed=2500, elapsed_seconds=30, cost_usd=1)
        result = self.collect()
        self.assertEqual(len(result['cohorts']), 1)
        cohort = result['cohorts'][0]
        self.assertEqual(cohort['runs'], 2)
        self.assertEqual(cohort['metrics']['actions']['recall']['min'], 1)
        self.assertEqual(cohort['efficiency']['tokens_consumed']['max'], 2500)

    def test_different_harness_and_prompt_do_not_pool(self):
        self.write_run('first')
        self.write_run('prompt', prompt_sha256='6' * 64)
        self.write_run('harness', harness_sha256='7' * 64)
        self.assertEqual(len(self.collect()['cohorts']), 3)

    def test_different_budget_policies_do_not_pool(self):
        self.write_run('first')
        policy = {'timeout_seconds': 900, 'max_tokens': 1000000, 'max_cost_usd': 2,
                  'cost_enforcement': 'catalog_context_reservation',
                  'token_enforcement': 'context_window_reservation', 'retries': False, 'compaction': False}
        self.write_run('second', budget_policy=policy)
        self.assertEqual(len(self.collect()['cohorts']), 2)

    def test_usage_cannot_be_rehashed_into_another_cost_measurement(self):
        answer_path, report_path, _ = self.write_run()
        answer = json.loads(answer_path.read_text())
        answer['cost_usd'] = 0
        answer_path.write_text(json.dumps(answer))
        report = json.loads(report_path.read_text())
        report['answer_sha256'] = aggregate.digest(answer_path)
        report_path.write_text(json.dumps(report))
        self.assert_rejected('usage')

    def test_scored_attempts_over_any_budget_cannot_enter_comparison(self):
        self.write_run('tokens', tokens_consumed=1000001)
        self.write_run('cost', cost_usd=3)
        self.write_run('elapsed', elapsed_seconds=1801)
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 0)
        self.assertEqual(result['outcomes']['rejected'], 3)
        for rejection in result['rejected']:
            self.assertIn('exceeds budget', rejection['reason'])

    def test_answer_tamper_is_rejected(self):
        answer, _, _ = self.write_run()
        answer.write_text(answer.read_text() + ' ')
        self.assert_rejected('answer_sha256 mismatch')

    def test_tampered_verdict_is_recomputed_and_rejected(self):
        _, report, _ = self.write_run(wrong=True)
        document = json.loads(report.read_text())
        document['metrics']['actions']['recall'] = 1
        report.write_text(json.dumps(document))
        self.assert_rejected('independent recomputation: metrics')

    def test_raw_record_format_hash_is_separate_from_canonical_oracle_hash(self):
        self.write_run()
        self.record_path.write_text(json.dumps(self.record, indent=2))
        self.assert_rejected('ground_truth_sha256 mismatch')

    def test_changed_acceptance_record_fails_oracle_before_collection(self):
        self.write_run()
        self.record['query'] = 'Changed acceptance context'
        self.record_path.write_text(json.dumps(self.record))
        with self.assertRaisesRegex(ValueError, 'oracle preflight failed'):
            self.collect()

    def test_stale_oracle_pin_fails_before_collection(self):
        self.write_run()
        self.oracle.write_text(self.oracle.read_text() + ' ')
        with self.assertRaisesRegex(ValueError, 'oracle preflight failed'):
            self.collect()

    def test_runner_receipt_tamper_is_rejected(self):
        _, _, envelope = self.write_run(product=True)
        envelope.write_text(envelope.read_text() + ' ')
        self.assert_rejected('runner receipt bundle hash mismatch')

    def test_matching_real_product_receipt_is_eligible(self):
        self.write_run(product=True)
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(result['cohorts'][0]['identity']['treatment'], 'product-first')

    def test_runner_stamp_cannot_override_ineligible_participation(self):
        answer, report, envelope = self.write_run(product=True)
        value = json.loads(answer.read_text())
        value['product_assisted_eligible'] = False
        answer.write_text(json.dumps(value))
        stored = json.loads(report.read_text())
        stored['answer_sha256'] = aggregate.digest(answer)
        report.write_text(json.dumps(stored))
        self.assert_rejected('product-assisted run is ineligible')

    def test_receipt_bundle_cannot_be_redirected_outside_receipts_directory(self):
        answer, report, envelope = self.write_run()
        outside = self.root / 'outside.json'
        outside.write_bytes(envelope.read_bytes())
        value = json.loads(answer.read_text())
        value['run_receipt_bundle']['path'] = str(outside)
        answer.write_text(json.dumps(value))
        stored = json.loads(report.read_text())
        stored['answer_sha256'] = aggregate.digest(answer)
        report.write_text(json.dumps(stored))
        self.assert_rejected('under runs-dir/receipts')

    def repin_envelope(self, answer_path, report_path, envelope_path, mutate):
        envelope = json.loads(envelope_path.read_text())
        answer = json.loads(answer_path.read_text())
        mutate(envelope, answer)
        envelope_path.write_text(json.dumps(envelope))
        pin = aggregate.digest(envelope_path)
        answer['run_receipt_bundle']['sha256'] = pin
        answer_path.write_text(json.dumps(answer))
        report = json.loads(report_path.read_text())
        report['answer_sha256'] = aggregate.digest(answer_path)
        report['run_receipt_bundle_sha256'] = pin
        report_path.write_text(json.dumps(report))

    def test_rehashed_envelope_cannot_invent_normalized_output(self):
        paths = self.write_run(product=True)
        def mutate(envelope, answer):
            envelope['receipts'][0]['output_normalized'] = 'invented result'
            answer['product_tool_receipts'] = envelope['receipts']
        self.repin_envelope(*paths, mutate)
        self.assert_rejected('normalized output hash mismatch')

    def test_receipts_must_match_scheduled_playbook_step_even_if_envelope_is_well_formed(self):
        paths = self.write_run(product=True)
        def mutate(envelope, answer):
            envelope['product_playbook'][0]['parameters']['query'] = 'Wrong symbol'
            answer['product_playbook'] = envelope['product_playbook']
        self.repin_envelope(*paths, mutate)
        self.assert_rejected('playbook step has no matching invocation receipt')

    def test_duplicate_observation_cannot_inflate_repeat_count(self):
        answer, report, _ = self.write_run()
        (self.runs / 'replayed.json').write_bytes(answer.read_bytes())
        (self.runs / 'replayed.score.json').write_bytes(report.read_bytes())
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(result['outcomes']['rejected'], 1)
        self.assertIn('duplicate attempt', result['rejected'][0]['reason'])

    def test_unversioned_failures_cannot_enter_v2_attempt_counts(self):
        (self.runs / 'legacy.rejected.json').write_text(json.dumps({
            'change_id': self.record['change_id'], 'runner': 'agent', 'reason': 'legacy failure',
        }))
        result = self.collect()
        self.assertEqual(result['outcomes']['rejected'], 0)
        self.assertEqual(result['excluded'][0]['reason'], 'failure stub is not protocol 2')

    def test_copied_receipt_envelope_cannot_inflate_repeat_count(self):
        answer_path, report_path, envelope_path = self.write_run()
        copied = envelope_path.with_name('copied.json')
        copied.write_bytes(envelope_path.read_bytes())
        answer = json.loads(answer_path.read_text())
        answer['run_receipt_bundle']['path'] = str(copied)
        replayed = self.runs / 'replayed.json'
        replayed.write_text(json.dumps(answer))
        report = json.loads(report_path.read_text())
        report['answer_sha256'] = aggregate.digest(replayed)
        (self.runs / 'replayed.score.json').write_text(json.dumps(report))
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(result['outcomes']['rejected'], 1)
        self.assertIn('duplicate attempt', result['rejected'][0]['reason'])

    def test_reformatted_envelope_with_same_attempt_cannot_inflate_repeat_count(self):
        answer_path, report_path, envelope_path = self.write_run()
        copied = envelope_path.with_name('reformatted.json')
        copied.write_text(json.dumps(json.loads(envelope_path.read_text()), indent=2))
        answer = json.loads(answer_path.read_text())
        pin = aggregate.digest(copied)
        answer['run_receipt_bundle'] = {'path': str(copied), 'sha256': pin}
        replayed = self.runs / 'replayed.json'
        replayed.write_text(json.dumps(answer))
        report = json.loads(report_path.read_text())
        report['answer_sha256'] = aggregate.digest(replayed)
        report['run_receipt_bundle_sha256'] = pin
        (self.runs / 'replayed.score.json').write_text(json.dumps(report))
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(result['outcomes']['rejected'], 1)
        self.assertIn('duplicate attempt', result['rejected'][0]['reason'])

    def test_failure_states_retained_without_fabricating_correctness(self):
        (self.runs / 'failed.rejected.json').write_text(json.dumps({
            'protocol_version': 2, 'change_id': self.record['change_id'], 'runner': 'agent',
            'status': 'budget_exhausted', 'reason': 'deadline',
        }))
        result = self.collect()
        self.assertEqual(result['cohorts'], [])
        self.assertEqual(result['outcomes']['failure_states'], {'budget_exhausted': 1})
        self.assertEqual(result['cost_accounting']['failures_without_cost_evidence'], 1)
        self.assertIsNone(result['cost_accounting']['exposure_upper_bound_usd'])

    def write_failure(self, status='budget_exhausted', completed=False):
        answer_path, _, _ = self.write_run()
        policy = json.loads(answer_path.read_text())['budget_policy']
        usage = {'tokens_consumed': 100, 'cost_usd': 0.2, 'elapsed_seconds': 5}
        exposure = {'unknown_usage': not completed, 'pending_request': None,
                    'exposure_upper_bound_usd': 0.2 if completed else 1.2}
        attempt = {'protocol_version': 2, 'change_id': self.record['change_id'], 'runner': 'agent',
                   'run_started_at': 'failed-start', 'status': 'runtime_completed' if completed else status,
                   'usage': usage, 'exposure': exposure, 'budget_policy': policy}
        artifact = self.runs / 'failed.attempt.json'
        artifact.write_text(json.dumps(attempt))
        stub = {'protocol_version': 2, 'change_id': self.record['change_id'], 'runner': 'agent',
                'status': status, 'reason': 'failure retained', 'evidence': {
                    'attempt_evidence': {'path': str(artifact), 'sha256': aggregate.digest(artifact)},
                    'usage': usage, 'exposure': exposure, 'budget_policy': policy}}
        stub_path = self.runs / 'failed.rejected.json'
        stub_path.write_text(json.dumps(stub))
        return artifact, stub_path

    def test_failed_paid_attempts_and_unsettled_exposure_are_in_total_cost(self):
        self.write_failure()
        result = self.collect()
        costs = result['cost_accounting']
        self.assertEqual(costs['known_attempts'], 2)
        self.assertAlmostEqual(costs['observed_cost_usd'], 0.21)
        self.assertAlmostEqual(costs['exposure_upper_bound_usd'], 1.21)
        self.assertEqual(costs['unknown_usage_attempts'], 1)
        self.assertEqual(costs['failures_without_cost_evidence'], 0)

    def test_invalid_output_retains_completed_paid_usage(self):
        self.write_failure(status='invalid_output', completed=True)
        result = self.collect()
        self.assertAlmostEqual(result['cost_accounting']['observed_cost_usd'], 0.21)
        self.assertAlmostEqual(result['cost_accounting']['exposure_upper_bound_usd'], 0.21)

    def test_failed_attempt_cost_cannot_be_replaced_by_unhashed_stub_claims(self):
        artifact, _ = self.write_failure()
        artifact.write_text(artifact.read_text() + ' ')
        result = self.collect()
        self.assertEqual(result['cost_accounting']['failures_without_cost_evidence'], 1)
        self.assertIsNone(result['cost_accounting']['exposure_upper_bound_usd'])
        self.assertIn('hash mismatch', result['rejected'][0]['reason'])

    def test_duplicate_failure_stubs_cannot_double_charge_attempt_cost(self):
        _, stub = self.write_failure()
        (self.runs / 'replayed.rejected.json').write_bytes(stub.read_bytes())
        costs = self.collect()['cost_accounting']
        self.assertEqual(costs['known_attempts'], 2)
        self.assertAlmostEqual(costs['observed_cost_usd'], 0.21)
        self.assertAlmostEqual(costs['exposure_upper_bound_usd'], 1.21)

    def test_malformed_failure_pointer_does_not_abort_other_measurements(self):
        _, stub_path = self.write_failure()
        stub = json.loads(stub_path.read_text())
        del stub['evidence']['attempt_evidence']['sha256']
        stub_path.write_text(json.dumps(stub))
        result = self.collect()
        self.assertEqual(result['outcomes']['scored'], 1)
        self.assertEqual(result['cost_accounting']['failures_without_cost_evidence'], 1)
        self.assertIsNone(result['cost_accounting']['exposure_upper_bound_usd'])


if __name__ == '__main__':
    unittest.main()
