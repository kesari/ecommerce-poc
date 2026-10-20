import copy
import hashlib
import json
import math
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import aggregate_paired as paired


def sha_text(value):
    return hashlib.sha256(value.encode()).hexdigest()


class PairedCampaignTests(unittest.TestCase):
    """Synthetic fixtures live only in a TemporaryDirectory; no model calls."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        policy = {'timeout_seconds': 600, 'max_tokens': 2000000, 'max_cost_usd': 5,
                  'cost_enforcement': 'catalog_context_reservation', 'token_enforcement': 'context_window_reservation',
                  'retries': False, 'compaction': False}
        shared = {'model': 'fixture/model', 'model_digest': None, 'pi_version': 'fixture-sdk',
                  'harness_sha256': '1' * 64, 'estate_sha256': '2' * 64, 'thinking': 'fixture-policy',
                  'scorer_sha256': paired.digest(paired.legacy.HARNESS / 'scoring/score_v2.py'),
                  'answer_schema_sha256': paired.digest(paired.legacy.HARNESS / 'schema/contestant-answer-v2.schema.json'),
                  'task_template_sha256': '3' * 64, 'scoring_policy': 'scoped_reference_v1', 'budget_policy': policy}
        self.cases = {'campaign_version': 1, 'cases': []}
        for i in range(12):
            case_id = f'H{i+1:02}'
            record = {'protocol_version': 2, 'evaluation_revision': '2.2', 'change_id': case_id, 'snapshot_sha256': '4' * 64}
            record_path = self.write(f'records/{case_id}.json', record)
            oracle_path = self.write(f'oracles/{case_id}/oracle.json', {'fixture': case_id})
            self.cases['cases'].append({'case_id': case_id, 'family': f'family-{i//2}',
                                       'record_path': str(record_path.relative_to(self.root)), 'record_sha256': paired.digest(record_path),
                                       'oracle_bundle_path': str(oracle_path.relative_to(self.root)), 'oracle_bundle_sha256': paired.digest(oracle_path),
                                       'artifact_root': str(oracle_path.parent.relative_to(self.root)), 'compatible_control': i >= 10})
        self.conditions = {'campaign_version': 1, 'protocol_version': 2, 'evaluation_revision': '2.2',
                           'paid_runs_authorized': False, 'campaign_budget': {'authorization_id': 'offline-fixture-only', 'currency': 'USD', 'max_total_usd': 0}, 'shared_identity': shared,
                           'arms': {arm: {'contestant': 'agent-only' if arm == 'baseline' else arm + '-first',
                                          'contestant_config_sha256': sha_text(arm), 'index_sha256': 'none' if arm == 'baseline' else sha_text(arm + '-index'),
                                          'treatment': 'baseline' if arm == 'baseline' else 'product-first',
                                          'prompt_sha256_by_case': {r['case_id']: sha_text('public-' + r['case_id']) for r in self.cases['cases']}}
                                    for arm in ('baseline', 'gortex', 'repowise', 'memory')},
                           'analysis': {'bootstrap_seed': 981, 'bootstrap_samples': 200, 'decision_gain': .10}}
        self.entries = []
        self.budget_rows = []
        self.freeze()
        self.real_score = paired.score.score_change
        self.real_preflight = paired.score.preflight
        self.preflight = patch.object(paired.score, 'preflight', return_value={'status': 'verified'}).start()
        self.grader = patch.object(paired.score, 'score_change', side_effect=self.fake_grade).start()
        self.addCleanup(patch.stopall)

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data, indent=2) + '\n')
        return path

    def pointer(self, path):
        return {'path': str(path.relative_to(self.root)), 'sha256': paired.digest(path)}

    def freeze(self):
        self.cases_path = self.write('cases.json', self.cases)
        self.condition_path = self.write('conditions.json', self.conditions)
        self.condition_pin = paired.digest(self.condition_path)
        self.schedule = paired.build_schedule(self.cases, self.conditions, cases_manifest_sha256=paired.digest(self.cases_path),
                                              condition_sha256=self.condition_pin, seed=711)
        self.schedule_path = self.write('schedule.json', self.schedule)
        self.schedule_pin = paired.digest(self.schedule_path)

    def cell(self, case='H01', arm='baseline', repeat=1):
        return next(c for c in self.schedule['cells'] if c['case_id'] == case and c['arm'] == arm and c['repeat'] == repeat)

    def fake_grade(self, record, answer, **options):
        # Keep real independent protected receipt validation in the otherwise
        # isolated score fixtures. Core semantic scoring is tested in its suite.
        verification = paired.score.run_receipts(record, answer, options['run_receipt_bundle_path'], options['trusted_run_receipts_sha256'])
        if verification['status'] != 'verified':
            return {'status': 'invalid', 'benchmark_eligible': False, 'receipt_errors': verification}
        return {'protocol_version': 2, 'evaluation_revision': '2.2', 'status': 'scored', 'benchmark_eligible': True,
                'scoring_policy': 'scoped_reference_v1', 'complete_supported_impact': answer.get('fixture_success', True),
                'product_provenance': {'run_receipts': {'status': 'verified'}},
                'metrics': {'actions': {'recall': 1 if answer.get('fixture_success', True) else 0}},
                'contradictions': answer.get('fixture_unnecessary_edits', [])}

    def add_answer(self, case='H01', arm='baseline', repeat=1, *, success=True, unnecessary=False):
        cell = self.cell(case, arm, repeat)
        mi = paired.expected_identity(cell, self.schedule_pin, self.condition_pin, self.conditions)
        start = f'fixture-{case}-{arm}-{repeat}'
        answer = {'protocol_version': 2, 'change_id': case, 'snapshot_sha256': '4' * 64,
                  **{k: v for k, v in mi.items() if k != 'campaign_slot'}, 'measurement_identity': mi,
                  'run_started_at': start, 'prompt_sha256': sha_text(start + '-effective'),
                  'budget_policy': self.conditions['shared_identity']['budget_policy'],
                  'tokens_consumed': 20, 'cost_usd': .02, 'elapsed_seconds': 2,
                  'product_provenance': None, 'product_playbook': None, 'product_tool_receipts': [],
                  'fixture_success': success, 'fixture_unnecessary_edits': [
                      {'kind': 'actions', 'claim': {'repo': 'fixture'}, 'reason': 'production_action_in_established_no_edit_repository'}] if unnecessary else []}
        if arm != 'baseline':
            output = '{}'
            answer.update(product_assisted_eligible=True,
                          product_provenance={'mode': 'real_product', 'indexed_estate_sha256': mi['estate_sha256']},
                          product_playbook=[{'tool': arm, 'parameters': {}, 'receipt_id': 'r1', 'ok': True}],
                          product_tool_receipts=[{'id': 'r1', 'tool': arm, 'operation': 'fixture', 'parameters': {},
                                                  'requested_query': None, 'executed_query': None, 'success': True,
                                                  'output': output, 'output_sha256': sha_text(output), 'output_bytes': 2,
                                                  'output_full_bytes': 2, 'truncated': False, 'output_normalized': output,
                                                  'output_normalized_sha256': sha_text(output), 'duration_ms': 1, 'error': None}])
        envelope = {k: answer[k] for k in ('protocol_version', 'change_id', 'snapshot_sha256', 'run_started_at',
                                          'prompt_sha256', 'estate_sha256', 'product_provenance', 'product_playbook', 'budget_policy', 'measurement_identity')}
        envelope['receipts'] = answer['product_tool_receipts']
        envelope['usage'] = {k: answer[k] for k in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')}
        receipt_path = self.write(f'runs/receipts/{start}.json', envelope)
        answer['run_receipt_bundle'] = {'path': str(receipt_path), 'sha256': paired.digest(receipt_path)}
        answer_path = self.write(f'runs/{start}.json', answer)
        record = paired.score.read_json(self.root / f'records/{case}.json')
        stored = self.fake_grade(record, answer, run_receipt_bundle_path=receipt_path, trusted_run_receipts_sha256=paired.digest(receipt_path))
        stored.update(ground_truth_sha256=paired.digest(self.root / f'records/{case}.json'),
                      scorer_sha256=self.conditions['shared_identity']['scorer_sha256'],
                      answer_schema_sha256=self.conditions['shared_identity']['answer_schema_sha256'],
                      oracle_bundle_sha256=paired.digest(self.root / f'oracles/{case}/oracle.json'),
                      answer_sha256=paired.digest(answer_path), run_receipt_bundle_sha256=paired.digest(receipt_path))
        stored_path = self.write(f'runs/{start}.score.json', stored)
        entry = {'slot_id': cell['slot_id'], 'measurement_identity': mi, 'run_started_at': start, 'status': 'completed',
                 'answer': self.pointer(answer_path), 'score': self.pointer(stored_path), 'receipts': self.pointer(receipt_path),
                 'campaign_budget_reservation_id': 'reservation-' + start}
        self.budget_rows.append({'ledger_path': str(self.root / 'budget.json'), 'authorization_id': self.conditions['campaign_budget']['authorization_id'],
                                 'reservation_id': entry['campaign_budget_reservation_id'], 'reserved_usd': 5,
                                 'campaign_slot': copy.deepcopy(mi['campaign_slot']), 'run_started_at': start,
                                 'status': 'settled', 'observed_cost_usd': .02, 'exposure_upper_bound_usd': .02,
                                 'unknown_usage': False, 'settled_at': 'fixture-settled'})
        self.entries.append(entry)
        return entry

    def add_failure(self, case='H01', arm='repowise', repeat=1, *, unknown=True):
        cell = self.cell(case, arm, repeat)
        mi = paired.expected_identity(cell, self.schedule_pin, self.condition_pin, self.conditions)
        start = f'failure-{case}-{arm}-{repeat}'
        usage = {'tokens_consumed': 10, 'cost_usd': .1, 'elapsed_seconds': 4}
        exposure = {'unknown_usage': unknown, 'exposure_upper_bound_usd': 3.664 if unknown else .1}
        policy = self.conditions['shared_identity']['budget_policy']
        identity = {'protocol_version': 2, 'change_id': case, 'runner': arm, 'run_started_at': start}
        attempt = {**identity, 'measurement_identity': mi, 'status': 'budget_exhausted', 'usage': usage,
                   'exposure': exposure, 'budget_policy': policy}
        attempt_path = self.write(f'runs/{start}.attempt.json', attempt)
        stub = {**identity, 'measurement_identity': mi, 'status': 'budget_exhausted', 'reason': 'fixture interruption',
                'evidence': {'attempt_evidence': {'path': str(attempt_path), 'sha256': paired.digest(attempt_path)},
                             'usage': usage, 'exposure': exposure, 'budget_policy': policy}}
        stub_path = self.write(f'runs/{start}.rejected.json', stub)
        entry = {'slot_id': cell['slot_id'], 'measurement_identity': mi, 'run_started_at': start,
                 'status': 'failed', 'failure': self.pointer(stub_path), 'attempt': self.pointer(attempt_path),
                 'campaign_budget_reservation_id': 'reservation-' + start}
        self.budget_rows.append({'ledger_path': str(self.root / 'budget.json'), 'authorization_id': self.conditions['campaign_budget']['authorization_id'],
                                 'reservation_id': entry['campaign_budget_reservation_id'], 'reserved_usd': 5,
                                 'campaign_slot': copy.deepcopy(mi['campaign_slot']), 'run_started_at': start,
                                 'status': 'settled', **copy.deepcopy(exposure), 'observed_cost_usd': .1, 'settled_at': 'fixture-settled'})
        self.entries.append(entry)
        return entry

    def write_budget(self):
        reservations = copy.deepcopy(self.budget_rows)
        observed = sum(math.ceil(r['observed_cost_usd'] * 1e9) for r in reservations if r['status'] == 'settled')
        pending = sum(math.ceil(r['reserved_usd'] * 1e9) for r in reservations if r['status'] == 'reserved')
        debit = pending + sum(math.ceil(r['exposure_upper_bound_usd'] * 1e9) for r in reservations if r['status'] == 'settled')
        cap = math.floor(self.conditions['campaign_budget']['max_total_usd'] * 1e9)
        document = {'ledger_version': 1, **self.conditions['campaign_budget'], 'reservations': reservations,
                    'totals': {'observed_cost_usd': observed / 1e9, 'pending_reserved_usd': pending / 1e9,
                               'debit_upper_bound_usd': debit / 1e9, 'remaining_usd': max(0, cap - debit) / 1e9,
                               'over_budget': debit > cap}, 'updated_at': 'fixture-sealed'}
        return self.write('budget.json', document)

    def analyze(self, *, entries=None, include_budget=True, budget_mutator=None):
        ledger = {'campaign_version': 1, 'schedule_sha256': self.schedule_pin, 'condition_sha256': self.condition_pin,
                  'entries': self.entries if entries is None else entries}
        if include_budget and self.budget_rows:
            budget_path = self.write_budget()
            if budget_mutator:
                doc = json.loads(budget_path.read_text());budget_mutator(doc);budget_path.write_text(json.dumps(doc))
            ledger['campaign_budget_ledger'] = self.pointer(budget_path)
        path = self.write('ledger.json', ledger)
        return paired.analyze_campaign(self.schedule_path, self.schedule_pin, self.condition_path, self.condition_pin,
                                       self.cases_path, path, paired.digest(path), self.root)

    def test_schedule_is_reproducible_complete_and_rotates_arms(self):
        again = paired.build_schedule(self.cases, self.conditions, cases_manifest_sha256=paired.digest(self.cases_path),
                                      condition_sha256=self.condition_pin, seed=711)
        self.assertEqual(self.schedule, again)
        self.assertEqual(len(self.schedule['cells']), 144)
        self.assertEqual(len({c['slot_id'] for c in self.schedule['cells']}), 144)
        self.assertEqual({c['repeat'] for c in self.schedule['cells']}, {1, 2, 3})
        self.assertEqual(len({self.schedule['cells'][i]['arm'] for i in range(0, 48, 4)}), 4)
        for case in self.cases['cases']:
            starts = [next(c['arm'] for c in self.schedule['cells'] if c['case_id'] == case['case_id'] and c['repeat'] == repeat) for repeat in range(1, 4)]
            self.assertEqual(len(set(starts)), 3)

    def test_remote_null_digest_and_legitimate_arm_differences_pass(self):
        self.add_answer()
        self.add_answer(arm='repowise')
        result = self.analyze()
        self.assertEqual(result['status'], 'incomplete')
        self.assertEqual(len(result['integrity_errors']), 0)
        self.assertEqual(self.grader.call_count, 2)
        self.assertEqual(result['arms']['repowise']['complete_successes'], 1)

    def test_public_task_prompt_drift_between_arms_is_rejected(self):
        self.conditions['arms']['repowise']['prompt_sha256_by_case']['H01'] = sha_text('different-task')
        with self.assertRaisesRegex(ValueError, 'identical public task prompts'):
            self.freeze()

    def test_all_missing_is_not_model_incorrect_and_no_verdict(self):
        result = self.analyze()
        self.assertEqual(result['scheduled_attempts'], 144)
        self.assertTrue(all(c['complete_supported_impact'] is None for c in result['cells']))
        for arm in result['arms'].values():
            self.assertEqual((arm['attempted'], arm['observed_failures'], arm['missing_unrun']), (0, 0, 36))
            self.assertIsNone(arm['attempted_operational_success'])
            self.assertEqual(arm['scheduled_success_bounds'], [0, 1])
        self.assertTrue(all(c['decision'] == 'incomplete_campaign' for c in result['comparisons'].values()))
        self.assertIsNone(result['global_budget_accounting']['exposure_upper_bound_usd'])

    def test_failure_retained_as_operational_failure_with_pending_exposure(self):
        self.add_answer()
        self.add_failure()
        result = self.analyze()
        self.assertEqual(result['arms']['repowise']['observed_failures'], 1)
        self.assertEqual(result['arms']['repowise']['attempted_operational_success'], 0)
        self.assertIsNone(result['arms']['repowise']['conditional_valid_answer_success'])
        self.assertAlmostEqual(result['cost_accounting']['observed_cost_usd'], .12)
        self.assertAlmostEqual(result['cost_accounting']['exposure_upper_bound_usd'], 3.684)
        self.assertEqual(result['comparisons']['repowise']['observed_pair_counts']['baseline_only_success'], 1)
        self.assertIn('unsettled_provider_exposure', result['global_budget_accounting']['decision_blockers'])

    def test_observed_attempts_require_independently_sealed_global_budget(self):
        self.add_answer()
        with self.assertRaisesRegex(ValueError, 'independently sealed campaign budget'):
            self.analyze(include_budget=False)

    def test_budget_totals_are_recomputed_instead_of_trusted(self):
        self.add_answer()
        def corrupt(doc):
            doc['totals']['observed_cost_usd'] = 0
        with self.assertRaisesRegex(ValueError, 'independent recomputation'):
            self.analyze(budget_mutator=corrupt)

    def test_budget_cap_cannot_be_reset_by_new_sealed_copy(self):
        self.add_answer()
        with self.assertRaisesRegex(ValueError, 'authorization or cap mismatch'):
            self.analyze(budget_mutator=lambda doc: doc.update(max_total_usd=100))

    def test_pending_unrecorded_reservation_retains_exposure(self):
        entry = self.add_answer()
        self.entries.clear()
        row = self.budget_rows[0]
        row.update(status='reserved')
        for key in ('observed_cost_usd', 'exposure_upper_bound_usd', 'unknown_usage', 'settled_at'):
            row.pop(key)
        result = self.analyze()
        self.assertEqual(result['arms']['baseline']['attempted'], 0)
        self.assertEqual(result['global_budget_accounting']['exposure_upper_bound_usd'], 5)
        self.assertIn('pending_or_crashed_reservations', result['global_budget_accounting']['decision_blockers'])
        self.assertEqual(result['global_budget_accounting']['unrecorded_reservations'], [entry['campaign_budget_reservation_id']])

    def test_prior_schedule_exposure_is_retained_under_same_authorization(self):
        self.add_answer()
        old = copy.deepcopy(self.budget_rows[0])
        old.update(reservation_id='prior-campaign', observed_cost_usd=1, exposure_upper_bound_usd=2, unknown_usage=True)
        old['campaign_slot'].update(schedule_sha256='a' * 64, condition_sha256='b' * 64)
        self.budget_rows.append(old)
        result = self.analyze()
        self.assertEqual(result['global_budget_accounting']['totals']['debit_upper_bound_usd'], 2.02)
        self.assertEqual(result['global_budget_accounting']['unknown_usage_reservations'], ['prior-campaign'])

    def test_budget_reservation_id_cannot_be_reassigned_to_other_slot(self):
        entry = self.add_answer()
        other = self.add_answer(case='H02')
        entry['campaign_budget_reservation_id'] = other['campaign_budget_reservation_id']
        with self.assertRaisesRegex(ValueError, 'reservation identity mismatch'):
            self.analyze()

    def test_duplicate_slot_and_reused_receipts_rejected_not_best_of(self):
        entry = self.add_answer()
        with self.assertRaisesRegex(ValueError, 'duplicate ledger slot'):
            self.analyze(entries=[entry, copy.deepcopy(entry)])
        second = self.add_answer(case='H02')
        second['receipts'] = entry['receipts']
        with self.assertRaisesRegex(ValueError, 'reused across slots'):
            self.analyze()

    def test_wrong_common_model_even_sealed_is_rejected(self):
        entry = self.add_answer()
        entry['measurement_identity']['model'] = 'other/model'
        with self.assertRaisesRegex(ValueError, 'ledger identity differs'):
            self.analyze()

    def test_wrong_top_model_stamp_cannot_override_sealed_identity(self):
        entry = self.add_answer()
        path = self.root / entry['answer']['path'];answer = json.loads(path.read_text())
        answer['model'] = 'other/model';path.write_text(json.dumps(answer));entry['answer'] = self.pointer(path)
        self.assertIn('measurement stamp mismatch: model', self.analyze()['integrity_errors'][0]['reason'])

    def test_incompatible_protocol_is_retained_as_rejected_attempt(self):
        entry = self.add_answer()
        path = self.root / entry['answer']['path'];answer = json.loads(path.read_text())
        answer['protocol_version'] = 1;path.write_text(json.dumps(answer));entry['answer'] = self.pointer(path)
        result = self.analyze()
        self.assertEqual(result['arms']['baseline']['attempted'], 1)
        self.assertEqual(result['arms']['baseline']['observed_failures'], 1)
        self.assertIn('incompatible', result['integrity_errors'][0]['reason'])

    def test_sealed_score_tampering_is_regraded(self):
        entry = self.add_answer(success=False)
        p = self.root / entry['score']['path']
        report = json.loads(p.read_text()); report['complete_supported_impact'] = True
        p.write_text(json.dumps(report)); entry['score'] = self.pointer(p)
        result = self.analyze()
        self.assertEqual(result['status'], 'integrity_errors')
        self.assertIn('fresh regrade', result['integrity_errors'][0]['reason'])
        self.assertTrue(all(c['decision'] == 'integrity_errors' for c in result['comparisons'].values()))
        self.assertAlmostEqual(result['cost_accounting']['observed_cost_usd'], .02)
        self.assertEqual(result['cost_accounting']['failures_without_cost_evidence'], 0)

    def test_receipt_false_envelope_is_rejected(self):
        entry = self.add_answer()
        p = self.root / entry['receipts']['path']; receipt = json.loads(p.read_text())
        receipt['measurement_identity']['runner'] = 'repowise';p.write_text(json.dumps(receipt));entry['receipts'] = self.pointer(p)
        result = self.analyze()
        self.assertIn('receipt measurement identity', result['integrity_errors'][0]['reason'])

    def test_modified_artifact_without_new_seal_is_rejected(self):
        entry = self.add_answer()
        (self.root / entry['answer']['path']).write_text('{}')
        self.assertIn('digest mismatch', self.analyze()['integrity_errors'][0]['reason'])

    def test_symlinks_and_escape_paths_rejected(self):
        entry = self.add_answer()
        actual = self.root / entry['answer']['path']
        symlink = self.root / 'linked.json'; symlink.symlink_to(actual)
        entry['answer'] = {'path': 'linked.json', 'sha256': paired.digest(actual)}
        self.assertIn('symlinks', self.analyze()['integrity_errors'][0]['reason'])
        entry['answer']['path'] = '../outside.json'
        self.assertIn('relative', self.analyze()['integrity_errors'][0]['reason'])

    def test_missing_primary_flag_does_not_become_false_correctness(self):
        self.add_answer()
        original = self.fake_grade
        def old_grade(*args, **kwargs):
            result = original(*args, **kwargs);result.pop('complete_supported_impact');return result
        self.grader.side_effect = old_grade
        result = self.analyze()
        self.assertEqual(result['status'], 'integrity_errors')
        self.assertIn('boolean missing', result['integrity_errors'][0]['reason'])

    def test_failed_oracle_hash_and_changed_schedule_block_preflight(self):
        (self.root / self.cases['cases'][0]['oracle_bundle_path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            self.analyze()

    def test_wrong_external_schedule_digest_is_not_self_trusted(self):
        self.schedule['cells'].reverse()
        self.schedule_path.write_text(json.dumps(self.schedule))
        with self.assertRaisesRegex(ValueError, 'trusted input digest'):
            self.analyze()

    def test_malformed_failure_exposure_is_not_reported_as_known_safe_spend(self):
        entry = self.add_failure()
        path = self.root / entry['attempt']['path'];attempt = json.loads(path.read_text())
        attempt['exposure']['exposure_upper_bound_usd'] = .01;path.write_text(json.dumps(attempt));entry['attempt'] = self.pointer(path)
        stub_path = self.root / entry['failure']['path'];stub = json.loads(stub_path.read_text())
        stub['evidence']['attempt_evidence']['sha256'] = entry['attempt']['sha256']
        stub['evidence']['exposure'] = attempt['exposure'];stub_path.write_text(json.dumps(stub));entry['failure'] = self.pointer(stub_path)
        result = self.analyze()
        self.assertEqual(result['status'], 'integrity_errors')
        self.assertIsNone(result['cost_accounting']['exposure_upper_bound_usd'])

    def test_full_144_cell_offline_campaign_retains_every_attempt(self):
        self.conditions['paid_runs_authorized'] = True
        self.conditions['campaign_budget']['max_total_usd'] = 10
        self.freeze()
        for cell in self.schedule['cells']:
            ok = cell['arm'] != 'baseline' or int(cell['case_id'][1:]) > 6
            self.add_answer(cell['case_id'], cell['arm'], cell['repeat'], success=ok)
        result = self.analyze()
        self.assertEqual(result['status'], 'complete')
        self.assertEqual(len(result['cells']), 144)
        self.assertEqual(result['cost_accounting']['known_attempts'], 144)
        self.assertAlmostEqual(result['cost_accounting']['observed_cost_usd'], 2.88)
        for arm in ('gortex', 'repowise', 'memory'):
            self.assertEqual(result['arms'][arm]['attempted'], 36)
            self.assertEqual(result['comparisons'][arm]['case_equal_delta'], .5)
            self.assertEqual(result['comparisons'][arm]['control_valid_pairs'], 6)
            self.assertEqual(result['comparisons'][arm]['decision'], 'demonstrated_practical_benefit')

    def test_full_campaign_benefit_blocked_by_global_budget_violation(self):
        self.conditions['paid_runs_authorized'] = True
        self.conditions['campaign_budget']['max_total_usd'] = .01
        self.freeze()
        for cell in self.schedule['cells']:
            self.add_answer(cell['case_id'], cell['arm'], cell['repeat'], success=cell['arm'] != 'baseline')
        result = self.analyze()
        self.assertEqual(result['status'], 'budget_evidence_unresolved')
        self.assertIn('cost_cap_violation', result['global_budget_accounting']['decision_blockers'])
        self.assertTrue(all(c['decision'] == 'budget_evidence_unresolved' for c in result['comparisons'].values()))

    def test_paid_cost_under_false_authorization_is_reported(self):
        self.add_answer()
        result = self.analyze()
        self.assertIn('paid_cost_without_authorization', result['global_budget_accounting']['decision_blockers'])

    def test_trusted_ledger_seal_does_not_derive_identity_from_answer(self):
        entry = self.add_answer()
        sealed = paired.seal_execution_ledger(self.schedule_pin, self.condition_pin, [entry], self.root,
                                             campaign_budget_ledger=self.pointer(self.write_budget()))
        self.assertEqual(sealed['entries'][0]['measurement_identity'], entry['measurement_identity'])
        with self.assertRaisesRegex(ValueError, 'trusted recorder'):
            paired.seal_execution_ledger(self.schedule_pin, self.condition_pin, [{'answer': entry['answer']}], self.root)

    def test_actual_22_scorer_and_receipts_regrade_a_complete_answer(self):
        # Reuse the old scorer's small semantic fixture, never a saved model run.
        from test_aggregate_v2 import AggregateV2Tests
        fixture = AggregateV2Tests()
        fixture.setUp();self.addCleanup(fixture.tearDown)
        record = copy.deepcopy(fixture.record)
        record.update(change_id='H01', evaluation_revision='2.2', repository_universe=['provider', 'consumer'], snapshot_sha256='4' * 64)
        record_path = self.write('records/H01.json', record)
        oracle = json.loads(fixture.oracle.read_text())
        oracle.update(change_id='H01', snapshot_sha256=record['snapshot_sha256'], record_sha256=sha_text(paired.score.canonical(record)),
                      scoring_policy={'mode': 'scoped_reference_v1'})
        self.write('oracles/H01/observations.json', {'baseline': 'pass', 'changed': 'fail', 'repaired': 'pass'})
        observation = self.root / 'oracles/H01/observations.json'
        oracle['artifacts'][0]['sha256'] = paired.digest(observation)
        oracle_path = self.write('oracles/H01/oracle.json', oracle)
        row = self.cases['cases'][0]
        row.update(record_sha256=paired.digest(record_path), oracle_bundle_sha256=paired.digest(oracle_path))
        self.freeze()
        entry = self.add_answer()
        answer_path = self.root / entry['answer']['path']
        answer = json.loads(answer_path.read_text());answer['findings'] = fixture.findings
        answer_path.write_text(json.dumps(answer));entry['answer'] = self.pointer(answer_path)
        report = self.real_score(record, answer, oracle_bundle_path=oracle_path, trusted_oracle_sha256=paired.digest(oracle_path),
                                 artifact_root=observation.parent, run_receipt_bundle_path=self.root / entry['receipts']['path'],
                                 trusted_run_receipts_sha256=entry['receipts']['sha256'])
        self.assertTrue(report.get('benchmark_eligible'), report)
        self.assertTrue(report.get('complete_supported_impact'), report)
        report.update(ground_truth_sha256=row['record_sha256'], scorer_sha256=self.conditions['shared_identity']['scorer_sha256'],
                      answer_schema_sha256=self.conditions['shared_identity']['answer_schema_sha256'],
                      oracle_bundle_sha256=row['oracle_bundle_sha256'], answer_sha256=entry['answer']['sha256'],
                      run_receipt_bundle_sha256=entry['receipts']['sha256'])
        path = self.write(entry['score']['path'], report);entry['score'] = self.pointer(path)
        self.grader.side_effect = self.real_score
        self.preflight.side_effect = lambda record, **kwargs: self.real_preflight(record, **kwargs) if record['change_id'] == 'H01' else {'status': 'verified'}
        result = self.analyze()
        self.assertEqual(result['integrity_errors'], [])
        self.assertEqual(result['arms']['baseline']['complete_successes'], 1)


class PairedStatisticsTests(unittest.TestCase):
    def fixture(self, *, incomplete=False, unsafe=False):
        cases = {f'H{i+1:02}': {'family': f'f{i//2}', 'compatible_control': i >= 10} for i in range(12)}
        conditions = {'arms': {a: {'treatment': 'baseline' if a == 'base' else 'product-first'} for a in ('base', 'tool', 'other', 'third')},
                      'analysis': {'bootstrap_seed': 33, 'bootstrap_samples': 500, 'decision_gain': .10}}
        cells = []
        for case in cases:
            for repeat in range(1, 4):
                for arm in conditions['arms']:
                    ok = arm != 'base'
                    missing = incomplete and arm == 'tool' and repeat == 3
                    cells.append({'case_id': case, 'repeat': repeat, 'arm': arm,
                                  'state': 'missing_unrun' if missing else 'scored_valid',
                                  'valid_answer': not missing, 'complete_supported_impact': None if missing else ok,
                                  'unnecessary_production_edits': [{'repo': 'x'}] if unsafe and arm == 'tool' and cases[case]['compatible_control'] else []})
        return cells, cases, conditions

    def test_paired_case_equal_delta_and_cluster_intervals(self):
        cells, cases, conditions = self.fixture()
        result = paired.summarize_cells(cells, cases, conditions)
        tool = result['comparisons']['tool']
        self.assertEqual(tool['case_equal_delta'], 1)
        self.assertEqual(tool['case_cluster_interval']['clusters'], 12)
        self.assertEqual(tool['family_cluster_sensitivity']['clusters'], 6)
        self.assertEqual(tool['case_cluster_interval']['low'], 1)
        self.assertEqual(tool['observed_pair_counts']['product_only_success'], 36)
        self.assertEqual(tool['decision'], 'demonstrated_practical_benefit')

    def test_missing_repeats_are_not_independent_cases_and_no_decision(self):
        cells, cases, conditions = self.fixture(incomplete=True)
        result = paired.summarize_cells(cells, cases, conditions)
        tool = result['comparisons']['tool']
        self.assertEqual(tool['observed_pair_counts']['unknown_missing_pair'], 12)
        self.assertEqual(tool['complete_case_count'], 0)
        self.assertEqual(tool['decision'], 'incomplete_campaign')

    def test_compatible_control_safety_blocks_benefit(self):
        cells, cases, conditions = self.fixture(unsafe=True)
        tool = paired.summarize_cells(cells, cases, conditions)['comparisons']['tool']
        self.assertEqual(len(tool['compatible_control_safety_regressions']), 6)
        self.assertEqual(tool['decision'], 'no_demonstrated_practical_benefit')

    def test_cluster_bootstrap_is_seeded_and_not_repeat_resampling(self):
        values = [0, .333, -.333, 1, 0, -.666, .333, 0, 0, .666, 0, 1]
        first = paired.clustered_interval(values, 991, 1000)
        self.assertEqual(first, paired.clustered_interval(values, 991, 1000))
        self.assertNotEqual(first, paired.clustered_interval(values, 992, 1000))
        self.assertEqual(first['clusters'], 12)

    def test_missing_bounds_and_partial_descriptive_case_average(self):
        cells, cases, conditions = self.fixture(incomplete=True)
        tool = paired.summarize_cells(cells, cases, conditions)['comparisons']['tool']
        self.assertEqual(tool['observed_case_equal_delta'], 1)
        self.assertEqual(tool['scheduled_delta_bounds'], [2/3, 1])
        self.assertIsNone(tool['case_equal_delta'])

    def test_unnecessary_edit_filter_does_not_count_a_missed_required_edit(self):
        report = {'contradictions': [
            {'kind': 'obligations', 'claim': {'obligation': 'validation_only'}, 'reason': 'contradicts_established_in_scope_observation'},
            {'kind': 'repositories', 'claim': {'runtime_status': 'broken'}, 'reason': 'contradicts_established_in_scope_observation'},
            {'kind': 'obligations', 'claim': {'obligation': 'production_edit'}, 'reason': 'contradicts_established_in_scope_observation'}]}
        self.assertEqual(paired.unnecessary_edits(report), [report['contradictions'][2]])

    def test_partial_case_means_are_not_weighted_by_number_of_repetitions(self):
        cells, cases, conditions = self.fixture()
        for cell in cells:
            if cell['arm'] not in ('base', 'tool'):
                continue
            if cell['case_id'] == 'H01':
                continue
            if cell['case_id'] == 'H02' and cell['repeat'] == 1:
                cell['complete_supported_impact'] = cell['arm'] == 'base'
            else:
                cell.update(state='missing_unrun', valid_answer=False, complete_supported_impact=None)
        tool = paired.summarize_cells(cells, cases, conditions)['comparisons']['tool']
        self.assertEqual(tool['observed_case_equal_delta'], 0)
        self.assertEqual(tool['observed_pair_counts']['product_only_success'], 3)
        self.assertEqual(tool['observed_pair_counts']['baseline_only_success'], 1)
        self.assertEqual(tool['decision'], 'incomplete_campaign')

    def test_control_runtime_failure_does_not_prove_safety(self):
        cells, cases, conditions = self.fixture()
        cell = next(c for c in cells if c['case_id'] == 'H11' and c['arm'] == 'base' and c['repeat'] == 1)
        cell.update(state='observed_failure', valid_answer=False, complete_supported_impact=False)
        tool = paired.summarize_cells(cells, cases, conditions)['comparisons']['tool']
        self.assertEqual(tool['decision'], 'control_safety_incomplete')
        self.assertEqual(tool['control_valid_pairs'], 5)


if __name__ == '__main__':
    unittest.main()
