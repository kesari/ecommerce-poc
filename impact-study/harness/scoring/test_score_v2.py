import copy
import hashlib
import json
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import score_v2 as score

SNAPSHOT = '1' * 64


def budget_policy():
    return {'timeout_seconds': 300, 'max_tokens': 1000000, 'max_cost_usd': 10,
            'cost_enforcement': 'catalog_context_reservation',
            'token_enforcement': 'context_window_reservation', 'retries': False, 'compaction': False}


def action(repo='order', before='postalCode', after='postcode'):
    return {'repo': repo, 'target': 'AddressResponse.postalCode', 'operation': 'read_json_field',
            'before': before, 'after': after}


def fixture():
    act = action()
    obligations = [{'repo': 'account', 'obligation': 'validation_only'},
                   {'repo': 'order', 'obligation': 'production_edit'},
                   {'repo': 'shipment', 'obligation': 'validation_only'},
                   {'repo': 'bff', 'obligation': 'unaffected'}]
    record = {'protocol_version': 2, 'change_id': 'REST-001', 'status': 'draft',
              'category': 'rest_contract', 'query': 'What must change?', 'origin_repo': 'account',
              'proposed_change': {'repo': 'account', 'diff': '- postalCode\n+ postcode'},
              'snapshot_sha256': SNAPSHOT,
              'repositories': [{'repo': 'account', 'runtime_status': 'unchanged'},
                               {'repo': 'order', 'runtime_status': 'broken'},
                               {'repo': 'shipment', 'runtime_status': 'unchanged'},
                               {'repo': 'bff', 'runtime_status': 'unchanged'}],
              'symbols': [{'repo': 'order', 'fqn': 'shop.AddressResponse.postalCode'}],
              'edges': [{'provider': 'account', 'consumer': 'order', 'type': 'rest',
                         'identifier': 'GET /addresses', 'field': 'postcode'}],
              'repair_alternatives': [{'name': 'consumer-alias', 'actions': [{**act, 'critical': True}],
                                       'obligations': obligations}]}
    answer = {'protocol_version': 2, 'change_id': 'REST-001', 'snapshot_sha256': SNAPSHOT,
              'findings': {kind: copy.deepcopy(record.get(kind, [])) for kind in score.FIELDS}}
    answer['findings']['actions'] = [act]
    answer['findings']['obligations'] = copy.deepcopy(obligations)
    return record, answer


class ScorerTests(unittest.TestCase):
    def setUp(self):
        self.record, self.answer = fixture()

    def evaluate(self):
        return score.score_change(self.record, self.answer)

    def test_correct_predicates_are_diagnostic_until_oracle_is_supplied(self):
        report = self.evaluate()
        self.assertTrue(report['satisfies_any_repair'])
        self.assertFalse(report['benchmark_eligible'])
        self.assertNotIn('composite', report)
        for kind in score.FIELDS:
            self.assertEqual(report['metrics'][kind]['recall'], 1)
            self.assertEqual(report['metrics'][kind]['precision'], 1)
            self.assertEqual(report['evidence'][kind]['verified_correct'], 0)

    def test_wrong_repo_symbol_gets_no_credit(self):
        self.answer['findings']['symbols'][0]['repo'] = 'shipment'
        self.assertEqual(self.evaluate()['metrics']['symbols']['recall'], 0)

    def test_declaring_type_is_not_field_identity(self):
        self.answer['findings']['symbols'][0]['fqn'] = 'shop.AddressResponse'
        self.assertEqual(self.evaluate()['metrics']['symbols']['recall'], 0)

    def test_wrong_consumer_or_direction_gets_no_credit(self):
        for change in ({'consumer': 'shipment'}, {'provider': 'order', 'consumer': 'account'}, {'field': 'postalCode'}):
            with self.subTest(change=change):
                answer = copy.deepcopy(self.answer)
                answer['findings']['edges'][0].update(change)
                self.assertEqual(score.score_change(self.record, answer)['metrics']['edges']['recall'], 0)

    def test_correct_repositories_do_not_credit_wrong_actions(self):
        self.answer['findings']['actions'][0]['after'] = 'wrongField'
        report = self.evaluate()
        self.assertEqual(report['metrics']['repositories']['recall'], 1)
        self.assertEqual(report['metrics']['actions']['recall'], 0)
        self.assertEqual(report['critical_downstream_action_misses'], [action()])
        self.assertFalse(report['satisfies_any_repair'])

    def test_broken_is_distinct_from_production_edit(self):
        self.answer['findings']['obligations'][2]['obligation'] = 'production_edit'
        report = self.evaluate()
        self.assertEqual(report['metrics']['repositories']['recall'], 1)
        self.assertEqual(report['metrics']['obligations']['recall'], 0.75)
        self.assertFalse(report['satisfies_any_repair'])

    def test_unaffected_extra_action_is_false_positive(self):
        self.answer['findings']['actions'].append(action('bff'))
        report = self.evaluate()
        self.assertEqual(report['metrics']['actions']['precision'], 0.5)
        self.assertEqual(report['differences']['actions']['false_positives'], [action('bff')])

    def test_alternative_complete_repair_is_equally_accepted(self):
        alternate = action('account', 'postcode-only', 'postcode-and-postalCode')
        alternate['operation'] = 'retain_json_alias'
        obligations = copy.deepcopy(self.record['repair_alternatives'][0]['obligations'])
        obligations[0]['obligation'] = 'production_edit'
        obligations[1]['obligation'] = 'validation_only'
        self.record['repair_alternatives'].append({'name': 'producer-window', 'actions': [alternate],
                                                   'obligations': obligations})
        self.answer['findings']['actions'] = [alternate]
        self.answer['findings']['obligations'] = obligations
        report = self.evaluate()
        self.assertTrue(report['satisfies_any_repair'])
        self.assertEqual(report['selected_repair'], 'producer-window')
        self.assertEqual(report['metrics']['actions']['recall'], 1)
        self.answer['findings']['actions'].append(action())
        self.assertFalse(self.evaluate()['satisfies_any_repair'])

    def test_mixing_incomplete_alternatives_does_not_make_a_plan(self):
        other = action('order', 'topic.old', 'topic.new')
        other['operation'] = 'subscribe_topic'
        self.record['repair_alternatives'][0]['actions'].append(other)
        third = action('order', 'other.old', 'other.new')
        self.record['repair_alternatives'].append({'name': 'other-plan', 'actions': [third, other],
                                                  'obligations': copy.deepcopy(self.record['repair_alternatives'][0]['obligations'])})
        self.answer['findings']['actions'] = [action(), third]
        report = self.evaluate()
        self.assertFalse(report['satisfies_any_repair'])
        self.assertEqual(report['metrics']['actions']['recall'], 0.5)

    def test_empty_plan_and_unresolved_are_explicit(self):
        for item in self.record['repositories']:
            item['runtime_status'] = 'unresolved'
        self.record['repair_alternatives'][0]['actions'] = []
        self.record['repair_alternatives'][0]['obligations'] = [
            {'repo': item['repo'], 'obligation': 'unresolved'} for item in self.record['repositories']]
        self.answer['findings']['repositories'] = copy.deepcopy(self.record['repositories'])
        self.answer['findings']['obligations'] = copy.deepcopy(self.record['repair_alternatives'][0]['obligations'])
        self.answer['findings']['actions'] = []
        self.assertTrue(self.evaluate()['satisfies_any_repair'])

    def test_nonempty_prose_receipt_or_citation_is_not_semantic_proof(self):
        for items in self.answer['findings'].values():
            for item in items:
                item.update({'evidence': 'This existing line proves absolutely everything.',
                             'receipt_id': 'invented', 'citations': [{'path': 'exists.java', 'line': 1}]})
        report = self.evaluate()
        self.assertTrue(all(x['verified_correct'] == 0 for x in report['evidence'].values()))
        self.assertEqual(report['product_provenance']['status'], 'invalid')

    def test_malformed_claim_is_visible(self):
        self.answer['findings']['actions'].append({'repo': 'bff', 'target': 'ignored'})
        report = self.evaluate()
        self.assertEqual(len(report['invalid_findings']), 1)
        self.assertEqual(report['metrics']['actions']['precision'], 0.5)
        self.assertEqual(report['metrics']['actions']['invalid'], 1)
        self.assertFalse(report['satisfies_any_repair'])
        self.assertFalse(report['benchmark_eligible'])

    def test_unknown_semantic_field_is_invalid_but_runner_metadata_allowed(self):
        self.answer['tokens_consumed'] = 1
        self.answer['product_provenance'] = {'arbitrary': 'runner data, never proof'}
        self.assertEqual(self.evaluate()['status'], 'scored')
        self.answer['findings']['symbols'][0]['expected_id'] = 'leaked'
        self.assertEqual(len(self.evaluate()['invalid_findings']), 1)

    def test_malformed_repo_symbol_is_invalid_without_crashing(self):
        for value in (None, [], 5, '', '   '):
            with self.subTest(value=value):
                answer = copy.deepcopy(self.answer)
                answer['findings']['symbols'].append({'repo': value, 'fqn': 'shop.AddressResponse.postalCode'})
                report = score.score_change(self.record, answer)
                self.assertEqual(report['metrics']['symbols']['precision'], 0.5)
                self.assertEqual(len(report['invalid_findings']), 1)

    def test_operation_vocabulary_is_public_and_closed(self):
        self.answer['findings']['actions'][0]['operation'] = 'oracle-secret-expected-007'
        report = self.evaluate()
        self.assertEqual(len(report['invalid_findings']), 1)
        self.assertEqual(report['metrics']['actions']['recall'], 0)

    def test_protocol_identity_and_snapshot_fail_closed(self):
        for field, value in [('protocol_version', 1), ('change_id', 'OTHER'), ('snapshot_sha256', '2' * 64)]:
            with self.subTest(field=field):
                answer = {**self.answer, field: value}
                self.assertEqual(score.score_change(self.record, answer)['status'], 'invalid_answer')

    def test_synthetic_output_cannot_be_presented_as_real(self):
        self.answer['synthetic'] = True
        self.assertEqual(self.evaluate()['status'], 'invalid_answer')

    def test_ambiguous_record_and_incomplete_obligations_rejected(self):
        self.record['repositories'].append(copy.deepcopy(self.record['repositories'][0]))
        self.assertEqual(self.evaluate()['status'], 'invalid_record')
        self.record, self.answer = fixture()
        self.record['repair_alternatives'][0]['obligations'].pop()
        self.assertEqual(self.evaluate()['status'], 'invalid_record')

    def test_generated_duplicate_permutation_usage_and_bounds(self):
        rng = random.Random(20261016)
        reference = self.evaluate()
        for _ in range(30):
            candidate = copy.deepcopy(self.answer)
            candidate['tokens_consumed'] = rng.randint(0, 100000)
            candidate['elapsed_seconds'] = rng.random() * 5000
            for items in candidate['findings'].values():
                items.extend(copy.deepcopy(items[:rng.randint(0, len(items))]))
                rng.shuffle(items)
            self.assertEqual(score.score_change(self.record, candidate), reference)
        for _ in range(30):
            candidate = copy.deepcopy(self.answer)
            for items in candidate['findings'].values():
                del items[:rng.randint(0, len(items))]
            report = score.score_change(self.record, candidate)
            for metric in report['metrics'].values():
                self.assertTrue(0 <= metric['precision'] <= 1)
                self.assertTrue(0 <= metric['recall'] <= 1)
            if not candidate['findings']['actions']:
                self.assertEqual(report['critical_downstream_action_misses'], [action()])

    def test_repository_and_symbol_renaming_preserves_results(self):
        before = self.evaluate()
        # Rename values consistently without relying on scorer normalization.
        self.record = json.loads(json.dumps(self.record).replace('order', 'checkout').replace('shop.AddressResponse', 'other.Binding'))
        self.answer = json.loads(json.dumps(self.answer).replace('order', 'checkout').replace('shop.AddressResponse', 'other.Binding'))
        after = self.evaluate()
        self.assertEqual(before['metrics'], after['metrics'])
        self.assertEqual(before['satisfies_any_repair'], after['satisfies_any_repair'])

    def test_harmless_json_formatting_and_identity_outer_space(self):
        expected = self.evaluate()
        self.answer = json.loads(json.dumps(self.answer, indent=4, sort_keys=True))
        self.answer['findings']['symbols'][0]['fqn'] = ' shop.AddressResponse.postalCode '
        self.assertEqual(self.evaluate(), expected)

    def test_wrong_top_level_shapes_are_explicit_invalid_inputs(self):
        for value in (None, [], 'answer', 5):
            with self.subTest(value=value):
                self.assertEqual(score.score_change(self.record, value)['status'], 'invalid_answer')
                self.assertEqual(score.score_change(value, self.answer)['status'], 'invalid_record')

    def test_differential_small_exact_checker(self):
        rng = random.Random(29)
        universe = [('a', 'X.f'), ('a', 'X.g'), ('b', 'X.f'), ('b', 'x.f')]
        for _ in range(30):
            expected = rng.sample(universe, rng.randrange(5))
            reported = rng.choices(universe, k=rng.randrange(6))
            self.record['symbols'] = [{'repo': repo.replace('a', 'account').replace('b', 'bff'), 'fqn': fqn} for repo, fqn in expected]
            self.answer['findings']['symbols'] = [{'repo': repo.replace('a', 'account').replace('b', 'bff'), 'fqn': fqn} for repo, fqn in reported]
            # Independent quadratic reference, no scorer key or metric helpers.
            e = list(dict.fromkeys(expected)); r = list(dict.fromkeys(reported))
            hits = sum(1 for x in r if any(x[0] == y[0] and x[1] == y[1] for y in e))
            result = self.evaluate()['metrics']['symbols']
            self.assertEqual(result['correct'], hits)
            self.assertEqual(result['recall'], hits / len(e) if e else 1)
            self.assertEqual(result['precision'], hits / len(r) if r else (0 if e else 1))

    def test_targeted_repo_and_consumer_mutations_are_killed(self):
        original = score.claim_key
        for kind, field in [('symbols', 'repo'), ('edges', 'consumer')]:
            with self.subTest(kind=kind):
                record, answer = fixture()
                answer['findings'][kind][0][field] = 'shipment'
                def faulty_key(k, claim):
                    key = original(k, claim)
                    if k == kind:
                        key = tuple('ignored' if name == field else value for name, value in zip(score.FIELDS[k], key))
                    return key
                def detection():
                    self.assertEqual(score.score_change(record, answer)['metrics'][kind]['recall'], 0)
                detection()
                with patch.object(score, 'claim_key', faulty_key), self.assertRaises(AssertionError):
                    detection()

    def test_targeted_schema_bypass_is_killed(self):
        self.answer['findings']['symbols'][0]['expected_id'] = 'bad'
        def detection():
            self.assertEqual(len(self.evaluate()['invalid_findings']), 1)
        detection()
        with patch.object(score, 'validate', return_value=[]), self.assertRaises(AssertionError):
            detection()


class OracleTests(unittest.TestCase):
    def setUp(self):
        self.record, self.answer = fixture()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.artifact = self.root / 'observations.json'
        self.artifact.write_text('{"baseline":"passed","changed":"failed","repaired":"passed"}\n')
        self.bundle = {'protocol_version': 2, 'snapshot_sha256': SNAPSHOT,
                       'artifacts': [{'path': self.artifact.name, 'sha256': hashlib.sha256(self.artifact.read_bytes()).hexdigest()}],
                       'attestations': [{'kind': kind, 'claim': claim, 'artifact_paths': [self.artifact.name]}
                                        for kind, claims in self.answer['findings'].items() for claim in claims]}
        self.path = self.root / 'trusted-oracle.json'
        self.write_bundle()

    def tearDown(self):
        self.temp.cleanup()

    def write_bundle(self):
        self.bundle['change_id'] = self.record['change_id']
        self.bundle['record_sha256'] = hashlib.sha256(score.canonical(self.record).encode('utf-8')).hexdigest()
        self.path.write_text(json.dumps(self.bundle, sort_keys=True))
        self.pin = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def evaluate(self, pin=True):
        return score.score_change(self.record, self.answer, oracle_bundle_path=self.path,
                                  trusted_oracle_sha256=self.pin if pin else None, artifact_root=self.root)

    def test_complete_external_content_bound_evidence(self):
        self.record['status'] = 'verified'
        self.write_bundle()
        report = self.evaluate()
        self.assertTrue(report['benchmark_eligible'])
        self.assertTrue(report['oracle_covers_expectations'])
        for kind, claims in self.answer['findings'].items():
            self.assertEqual(report['evidence'][kind]['verified_correct'], len(claims))

    def test_missing_or_changed_trust_pin_is_not_proof(self):
        self.assertEqual(self.evaluate(pin=False)['oracle']['status'], 'invalid')
        self.path.write_text(self.path.read_text() + ' ')
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_snapshot_and_artifact_mutation_are_rejected(self):
        self.bundle['snapshot_sha256'] = '2' * 64
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')
        self.bundle['snapshot_sha256'] = SNAPSHOT
        self.write_bundle()
        self.artifact.write_text('tampered')
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_existing_unrelated_artifact_does_not_attest_other_claim(self):
        self.bundle['attestations'] = [self.bundle['attestations'][0]]
        self.write_bundle()
        self.record['status'] = 'verified'
        self.write_bundle()
        report = self.evaluate()
        self.assertEqual(report['oracle']['status'], 'verified')
        self.assertEqual(report['evidence']['actions']['verified_correct'], 0)
        self.assertFalse(report['benchmark_eligible'])

    def test_invalid_or_escaping_artifact_paths_fail_closed(self):
        for bad in ['../observations.json', str(self.artifact)]:
            self.bundle['artifacts'][0]['path'] = bad
            self.write_bundle()
            self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / 'outside.json'; target.write_text(self.artifact.read_text())
            self.artifact.unlink(); self.artifact.symlink_to(target)
            self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_artifact_binding_required(self):
        self.bundle['attestations'][0]['artifact_paths'] = []
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_external_receipt_has_separate_provenance_and_no_semantic_credit(self):
        receipt = self.root / 'receipt.json'
        receipt.write_text(json.dumps({'receipt_id': 'r1', 'snapshot_sha256': SNAPSHOT}))
        self.bundle['artifacts'].append({'path': receipt.name, 'sha256': hashlib.sha256(receipt.read_bytes()).hexdigest()})
        self.bundle['receipts'] = [{'id': 'r1', 'snapshot_sha256': SNAPSHOT, 'artifact_path': receipt.name}]
        self.bundle['attestations'] = []
        self.answer['findings']['actions'][0]['receipt_id'] = 'r1'
        self.write_bundle()
        report = self.evaluate()
        self.assertEqual(report['product_provenance']['status'], 'verified')
        self.assertEqual(report['evidence']['actions']['verified_correct'], 0)
        self.bundle['receipts'][0]['snapshot_sha256'] = '2' * 64
        self.write_bundle()
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')

    def test_targeted_trust_bypass_is_killed(self):
        self.artifact.write_text('tampered')
        def detection():
            self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')
        detection()
        trusted = {kind: set() for kind in score.FIELDS}
        with patch.object(score, 'verified_claims', return_value=(trusted, {'status': 'verified', 'errors': []})), self.assertRaises(AssertionError):
            detection()

    def test_targeted_nonempty_evidence_trust_is_killed(self):
        self.answer['findings']['actions'][0]['evidence'] = 'unrelated prose'
        def detection():
            report = score.score_change(self.record, self.answer)
            self.assertEqual(report['evidence']['actions']['verified_correct'], 0)
        detection()
        invented = {kind: {score.claim_key(kind, claim) for claim in claims} for kind, claims in self.answer['findings'].items()}
        with patch.object(score, 'verified_claims', return_value=(invented, {'status': 'verified', 'errors': []})), self.assertRaises(AssertionError):
            detection()

    def test_targeted_receipt_bypass_is_killed(self):
        self.answer['findings']['actions'][0]['receipt_id'] = 'invented'
        def detection():
            self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        detection()
        original = score.verified_claims
        def faulty_receipts(*args, **kwargs):
            claims, oracle = original(*args, **kwargs)
            oracle['receipt_ids'] = ['invented']
            return claims, oracle
        with patch.object(score, 'verified_claims', faulty_receipts), self.assertRaises(AssertionError):
            detection()

    def test_preflight_requires_verified_record_and_complete_covered_plan(self):
        def preflight():
            return score.preflight(self.record, oracle_bundle_path=self.path,
                                   trusted_oracle_sha256=self.pin, artifact_root=self.root)
        self.assertEqual(preflight()['status'], 'unverified')
        self.record['status'] = 'verified'
        self.write_bundle()
        self.assertEqual(preflight()['status'], 'verified')
        self.assertEqual(preflight()['covered_repair_alternatives'], ['consumer-alias'])
        self.bundle['attestations'] = [item for item in self.bundle['attestations'] if item['kind'] != 'actions']
        self.write_bundle()
        self.assertEqual(preflight()['status'], 'unverified')

    def test_weakened_or_different_record_cannot_reuse_oracle_pin(self):
        self.record['status'] = 'verified'
        self.write_bundle()
        for field, changed in [('symbols', []), ('query', 'Different proposed behavior'), ('change_id', 'REST-002')]:
            with self.subTest(field=field):
                weakened = {**self.record, field: changed}
                report = score.preflight(weakened, oracle_bundle_path=self.path,
                                         trusted_oracle_sha256=self.pin, artifact_root=self.root)
                self.assertEqual(report['status'], 'unverified')
                self.assertEqual(report['oracle']['status'], 'invalid')

    def test_preflight_alternative_is_a_complete_set(self):
        self.record['status'] = 'verified'
        other = copy.deepcopy(self.record['repair_alternatives'][0])
        other['name'] = 'other'
        other['actions'][0]['after'] = 'different'
        self.record['repair_alternatives'].append(other)
        self.write_bundle()
        result = score.preflight(self.record, oracle_bundle_path=self.path,
                                 trusted_oracle_sha256=self.pin, artifact_root=self.root)
        self.assertEqual(result['status'], 'verified')
        self.assertEqual(result['covered_repair_alternatives'], ['consumer-alias'])

    def test_cli_validate_and_score(self):
        record_path = self.root / 'record.json'; record_path.write_text(json.dumps(self.record))
        answer_path = self.root / 'answer.json'; answer_path.write_text(json.dumps(self.answer))
        script = str(Path(score.__file__))
        result = subprocess.run([sys.executable, script, 'validate', '--record', str(record_path)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['status'], 'valid')
        result = subprocess.run([sys.executable, script, 'score', '--record', str(record_path), '--answer', str(answer_path),
                                 '--oracle-bundle', str(self.path), '--trusted-oracle-sha256', self.pin,
                                 '--artifact-root', str(self.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['oracle']['status'], 'verified')
        result = subprocess.run([sys.executable, script, 'validate', '--kind', 'answer', str(answer_path)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.record['status'] = 'verified'; record_path.write_text(json.dumps(self.record))
        self.write_bundle()
        result = subprocess.run([sys.executable, script, 'preflight', '--record', str(record_path),
                                 '--oracle-bundle', str(self.path), '--trusted-oracle-sha256', self.pin,
                                 '--artifact-root', str(self.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['status'], 'verified')


class ScopedEquivalenceTests(unittest.TestCase):
    def setUp(self):
        self.record, self.answer = fixture()
        self.record['status'] = 'verified'
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.artifact = self.root / 'independent-predicate-proof.json'
        self.artifact.write_text('{"method_read_mapping":"passed","wrong_key_fault":"caught"}\n')
        self.path = self.root / 'reviewed-variant-oracle.json'
        self.bundle = {'protocol_version': 2, 'change_id': self.record['change_id'],
                       'record_sha256': '', 'snapshot_sha256': SNAPSHOT,
                       'scoring_policy': {'mode': 'scoped_reference_v1'},
                       'artifacts': [{'path': self.artifact.name, 'sha256': hashlib.sha256(self.artifact.read_bytes()).hexdigest()}],
                       'attestations': [{'kind': kind, 'claim': copy.deepcopy(claim), 'artifact_paths': [self.artifact.name]}
                                        for kind, claims in self.answer['findings'].items() for claim in claims],
                       'equivalences': []}
        self.add_equivalence('actions', target='shop.AccountClient.address')
        self.add_equivalence('symbols', fqn='shop.AddressResponse#postalCode()')
        self.add_equivalence('edges', field='postalCode')
        self.write_bundle()

    def tearDown(self):
        self.temp.cleanup()

    def add_equivalence(self, kind, **alias_changes):
        reference = self.answer['findings'][kind][0]
        canonical_claim = {field: reference[field] for field in score.FIELDS[kind]}
        self.bundle['equivalences'].append({'kind': kind, 'alias': {**canonical_claim, **alias_changes},
                                            'canonical': canonical_claim, 'artifact_paths': [self.artifact.name]})

    def write_bundle(self):
        self.bundle['record_sha256'] = hashlib.sha256(score.canonical(self.record).encode('utf-8')).hexdigest()
        self.path.write_text(json.dumps(self.bundle, sort_keys=True))
        self.pin = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def evaluate(self):
        return score.score_change(self.record, self.answer, oracle_bundle_path=self.path,
                                  trusted_oracle_sha256=self.pin, artifact_root=self.root)

    def use_aliases(self):
        for entry in self.bundle['equivalences']:
            self.answer['findings'][entry['kind']][0] = copy.deepcopy(entry['alias'])

    def test_proven_method_accessor_and_serialized_field_variants_are_accepted(self):
        self.use_aliases()
        report = self.evaluate()
        self.assertTrue(report['satisfies_required_actions'])
        self.assertTrue(report['satisfies_any_repair'])
        self.assertEqual(report['evaluation_revision'], '2.1')
        self.assertTrue(report['benchmark_eligible'])
        self.assertEqual(len(report['equivalences_applied']), 3)
        for kind in ('actions', 'symbols', 'edges'):
            self.assertEqual(report['metrics'][kind]['recall'], 1)
            self.assertEqual(report['evidence'][kind]['verified_correct'], 1)

    def test_supporting_and_out_of_scope_claims_are_unverified_extras(self):
        self.use_aliases()
        extra_symbol = {'repo': 'order', 'fqn': 'shop.AccountClient.address'}
        extra_repo = {'repo': 'billing', 'runtime_status': 'unresolved'}
        extra_obligation = {'repo': 'billing', 'obligation': 'validation_only'}
        self.answer['findings']['symbols'].append(extra_symbol)
        self.answer['findings']['repositories'].append(extra_repo)
        self.answer['findings']['obligations'].append(extra_obligation)
        report = self.evaluate()
        self.assertTrue(report['satisfies_any_repair'])
        self.assertEqual(report['differences']['symbols']['extras_unverified'], [extra_symbol])
        self.assertEqual(report['differences']['repositories']['extras_unverified'], [extra_repo])
        self.assertEqual(report['differences']['obligations']['extras_unverified'], [extra_obligation])
        self.assertEqual(report['differences']['symbols']['false_positives'], [])
        self.assertEqual(report['evidence']['symbols']['verified_correct'], 1)
        self.assertEqual(report['metrics']['symbols']['precision'], 0.5)
        self.assertEqual(report['metric_interpretation']['precision'], 'reference_overlap')

    def test_required_action_coverage_is_separate_from_missing_obligations(self):
        self.use_aliases()
        self.answer['findings']['obligations'] = []
        report = self.evaluate()
        self.assertTrue(report['satisfies_required_actions'])
        self.assertTrue(report['repair_alternatives'][0]['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])

    def test_origin_edit_contradiction_does_not_erase_action_coverage(self):
        self.use_aliases()
        self.answer['findings']['obligations'][0]['obligation'] = 'production_edit'
        report = self.evaluate()
        self.assertTrue(report['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['kind'] == 'obligations' and item['claim']['repo'] == 'account'
                            for item in report['contradictions']))

    def test_wrong_target_after_missing_action_or_direction_remain_uncredited(self):
        cases = [('actions', 'target', 'shop.AccountClient.unknown', 'actions'),
                 ('actions', 'after', 'wrongField', 'actions'),
                 ('edges', 'consumer', 'shipment', 'edges')]
        for kind, field, wrong, metric in cases:
            with self.subTest(kind=kind, field=field):
                saved = copy.deepcopy(self.answer['findings'][kind])
                self.answer['findings'][kind][0][field] = wrong
                report = self.evaluate()
                self.assertEqual(report['metrics'][metric]['recall'], 0)
                if kind == 'actions':
                    self.assertFalse(report['satisfies_required_actions'])
                self.answer['findings'][kind] = saved
        self.answer['findings']['actions'] = []
        self.assertFalse(self.evaluate()['satisfies_required_actions'])

    def test_no_unreviewed_method_or_prefix_normalization(self):
        for target in ('shop.AccountClient#address()', 'AddressResponse', 'AddressResponse.postalCode()'):
            with self.subTest(target=target):
                self.answer['findings']['actions'][0]['target'] = target
                self.assertFalse(self.evaluate()['satisfies_required_actions'])

    def test_no_edit_action_and_in_scope_runtime_contradictions_fail_full_repair(self):
        self.answer['findings']['actions'].append(action('bff'))
        report = self.evaluate()
        self.assertTrue(report['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])
        self.assertEqual(len(report['differences']['actions']['false_positives']), 1)
        self.answer['findings']['actions'].pop()
        self.answer['findings']['repositories'][2]['runtime_status'] = 'broken'
        report = self.evaluate()
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['kind'] == 'repositories' for item in report['contradictions']))

    def test_correct_plus_contradictory_action_is_not_a_complete_plan(self):
        self.answer['findings']['actions'].append(action(after='wrongField'))
        report = self.evaluate()
        self.assertTrue(report['satisfies_required_actions'])
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['reason'] == 'contradicts_required_action_before_after'
                            for item in report['contradictions']))

    def test_wrong_value_at_reviewed_method_target_is_a_contradiction_not_an_equivalence(self):
        for field, wrong in [('before', 'wrongOldField'), ('after', 'wrongNewField')]:
            with self.subTest(field=field):
                extra = {**self.bundle['equivalences'][0]['alias'], field: wrong}
                self.answer['findings']['actions'] = [action(), extra]
                report = self.evaluate()
                self.assertTrue(report['satisfies_required_actions'])
                self.assertFalse(report['satisfies_any_repair'])
                self.assertEqual(report['equivalences_applied'], [])
                self.assertIn(extra, report['differences']['actions']['false_positives'])
                self.assertEqual(report['metrics']['actions']['correct'], 1)

    def test_mutually_exclusive_in_scope_obligations_fail_even_with_expected_present(self):
        self.answer['findings']['obligations'].append({'repo': 'order', 'obligation': 'validation_only'})
        report = self.evaluate()
        self.assertFalse(report['satisfies_any_repair'])
        self.assertTrue(any(item['reason'] == 'mutually_exclusive_in_scope_claims' for item in report['contradictions']))

    def test_alias_and_canonical_duplicate_are_canonicalized_once(self):
        self.answer['findings']['actions'].append(copy.deepcopy(self.bundle['equivalences'][0]['alias']))
        report = self.evaluate()
        self.assertEqual(report['metrics']['actions']['reported'], 1)
        self.assertEqual(report['metrics']['actions']['correct'], 1)
        self.assertEqual(len(report['equivalences_applied']), 1)
        self.assertTrue(report['satisfies_any_repair'])

    def test_equivalence_cannot_change_repository_direction_type_operation_or_values(self):
        checks = [('actions', 'repo', 'shipment'), ('actions', 'operation', 'retain_json_alias'),
                  ('actions', 'before', 'differentOldField'), ('actions', 'after', 'differentNewField'),
                  ('symbols', 'repo', 'shipment'), ('edges', 'provider', 'order'),
                  ('edges', 'consumer', 'shipment'), ('edges', 'type', 'kafka'),
                  ('edges', 'identifier', 'GET /different')]
        original = copy.deepcopy(self.bundle['equivalences'])
        for kind, field, wrong in checks:
            with self.subTest(kind=kind, field=field):
                self.bundle['equivalences'] = copy.deepcopy(original)
                entry = next(item for item in self.bundle['equivalences'] if item['kind'] == kind)
                entry['alias'][field] = wrong
                self.write_bundle()
                report = self.evaluate()
                self.assertEqual(report['oracle']['status'], 'invalid')
                self.assertEqual(report['equivalences_applied'], [])
                self.assertFalse(report['benchmark_eligible'])

    def test_equivalence_canonical_must_be_actual_expected_claim(self):
        self.bundle['equivalences'][0]['canonical']['target'] = 'shop.Unknown.expectedSecretId'
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_conflicting_aliases_and_chains_fail_closed(self):
        alternate = copy.deepcopy(self.record['repair_alternatives'][0])
        alternate['name'] = 'other-declaring-member'
        alternate['actions'][0]['target'] = 'OtherResponse.postalCode'
        self.record['repair_alternatives'].append(alternate)
        first = self.bundle['equivalences'][0]
        conflict = copy.deepcopy(first)
        conflict['canonical']['target'] = 'OtherResponse.postalCode'
        self.bundle['equivalences'].append(conflict)
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')
        conflict['alias'] = copy.deepcopy(first['canonical'])
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')

    def test_equivalence_requires_bound_artifact_and_strict_claim_schema(self):
        for mutation in ('empty_paths', 'missing_paths', 'extra_field', 'wrong_kind'):
            with self.subTest(mutation=mutation):
                original = copy.deepcopy(self.bundle['equivalences'])
                entry = self.bundle['equivalences'][0]
                if mutation == 'empty_paths':
                    entry['artifact_paths'] = []
                elif mutation == 'missing_paths':
                    entry['artifact_paths'] = ['not-listed.json']
                elif mutation == 'extra_field':
                    entry['alias']['expected_id'] = 'oracle-007'
                else:
                    entry['kind'] = 'repositories'
                self.write_bundle()
                self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')
                self.bundle['equivalences'] = original

    def test_modified_or_unpinned_bundle_does_not_enable_aliases_or_scope_policy(self):
        self.use_aliases()
        self.path.write_text(self.path.read_text() + ' ')
        report = self.evaluate()
        self.assertEqual(report['oracle']['status'], 'invalid')
        self.assertFalse(report['satisfies_required_actions'])
        self.assertEqual(report['equivalences_applied'], [])
        self.assertEqual(report['scoring_policy']['mode'], 'strict_reference_v2')
        self.write_bundle()
        self.artifact.write_text('changed proof')
        self.assertFalse(self.evaluate()['satisfies_required_actions'])

    def test_bad_policy_schema_fails_closed_and_model_policy_is_not_authority(self):
        self.bundle['scoring_policy']['mode'] = 'trust_agent'
        self.write_bundle()
        self.assertEqual(self.evaluate()['oracle']['status'], 'invalid')
        del self.bundle['scoring_policy']
        self.write_bundle()
        self.answer['scoring_policy'] = {'mode': 'scoped_reference_v1'}
        self.answer['findings']['obligations'].append({'repo': 'billing', 'obligation': 'validation_only'})
        report = self.evaluate()
        self.assertEqual(report['scoring_policy']['mode'], 'strict_reference_v2')
        self.assertFalse(report['satisfies_any_repair'])

    def test_duplicate_equivalences_and_permutations_have_identical_reports(self):
        self.use_aliases()
        before = self.evaluate()
        self.bundle['equivalences'].append(copy.deepcopy(self.bundle['equivalences'][0]))
        self.bundle['equivalences'].reverse()
        self.bundle['attestations'].reverse()
        self.write_bundle()
        for claims in self.answer['findings'].values():
            claims.reverse()
        self.assertEqual(self.evaluate(), before)

    def test_attested_source_alias_can_establish_only_its_canonical_predicate(self):
        alias = self.bundle['equivalences'][0]['alias']
        for attestation in self.bundle['attestations']:
            if attestation['kind'] == 'actions':
                attestation['claim'] = copy.deepcopy(alias)
        self.answer['findings']['actions'][0] = copy.deepcopy(alias)
        self.write_bundle()
        report = self.evaluate()
        self.assertTrue(report['oracle_covers_expectations'])
        self.assertEqual(report['evidence']['actions']['verified_correct'], 1)


class RunReceiptTests(unittest.TestCase):
    def setUp(self):
        self.record, self.answer = fixture()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.path = self.root / 'run-receipts.json'
        output = '{"matches":["AddressResponse.postalCode"]}'
        raw_mcp = {'content': [{'type': 'text', 'text': output}]}
        receipt = {'id': 'product-0001', 'tool': 'repowise_search', 'operation': 'search',
                   'parameters': {'query': 'AddressResponse.postalCode'}, 'requested_query': None,
                   'executed_query': None, 'success': True, 'output': output,
                   # Same format as products.ts: hash entire raw MCP result, not output text.
                   'output_sha256': hashlib.sha256(json.dumps(raw_mcp, separators=(',', ':')).encode()).hexdigest(),
                   'output_bytes': len(output.encode()), 'output_full_bytes': len(output.encode()),
                   'truncated': False, 'output_normalized': output,
                   'output_normalized_sha256': hashlib.sha256(output.encode()).hexdigest(),
                   'duration_ms': 1.5, 'error': None}
        self.bundle = {'protocol_version': 2, 'change_id': 'REST-001', 'snapshot_sha256': SNAPSHOT,
                       'run_started_at': '2026-10-16T12:00:00.000Z', 'prompt_sha256': '3' * 64,
                       'estate_sha256': '4' * 64, 'receipts': [receipt],
                       'budget_policy': budget_policy(),
                       'usage': {'tokens_consumed': 300, 'cost_usd': 0.003, 'elapsed_seconds': 1.25},
                       'product_provenance': {'product': 'repowise', 'freshness': 'verified'},
                       'product_playbook': [{'tool': 'repowise_search', 'receipt_id': 'product-0001', 'ok': True}]}
        self.answer.update({key: copy.deepcopy(self.bundle[key]) for key in (
            'run_started_at', 'prompt_sha256', 'estate_sha256', 'product_provenance', 'product_playbook', 'budget_policy')})
        self.answer.update(self.bundle['usage'])
        self.answer['product_tool_receipts'] = copy.deepcopy(self.bundle['receipts'])
        self.answer['findings']['symbols'][0]['receipt_id'] = 'product-0001'
        self.write_bundle()

    def tearDown(self):
        self.temp.cleanup()

    def write_bundle(self):
        self.path.write_text(json.dumps(self.bundle))
        self.pin = hashlib.sha256(self.path.read_bytes()).hexdigest()

    def evaluate(self, pin=True):
        return score.score_change(self.record, self.answer, run_receipt_bundle_path=self.path,
                                  trusted_run_receipts_sha256=self.pin if pin else None)

    def test_actual_runner_receipt_validates_attribution_without_oracle_truth(self):
        report = self.evaluate()
        self.assertEqual(report['product_provenance']['status'], 'verified')
        self.assertEqual(report['product_provenance']['run_receipts']['receipt_ids'], ['product-0001'])
        self.assertEqual(report['oracle']['status'], 'not_supplied')
        self.assertEqual(report['evidence']['symbols']['verified_correct'], 0)
        self.assertFalse(report['benchmark_eligible'])

    def test_absent_or_unknown_reference_does_not_verify_provenance(self):
        without_bundle = score.score_change(self.record, self.answer)
        self.assertEqual(without_bundle['product_provenance']['status'], 'invalid')
        self.answer['findings']['symbols'][0]['receipt_id'] = 'invented'
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        del self.answer['findings']['symbols'][0]['receipt_id']
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'not_supplied')

    def test_run_envelope_requires_external_pin_and_exact_bytes(self):
        self.assertEqual(self.evaluate(pin=False)['product_provenance']['status'], 'invalid')
        self.path.write_text(self.path.read_text() + '\n')
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')

    def test_run_and_product_identity_cannot_be_replayed(self):
        for key, replacement in [('change_id', 'REST-002'), ('snapshot_sha256', '5' * 64),
                                 ('run_started_at', '2026-10-16T12:01:00.000Z'),
                                 ('prompt_sha256', '6' * 64), ('estate_sha256', '7' * 64),
                                 ('product_provenance', {'product': 'different'}), ('product_playbook', [])]:
            with self.subTest(key=key):
                saved = self.bundle[key]
                self.bundle[key] = replacement
                self.write_bundle()
                self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
                self.bundle[key] = saved
        self.write_bundle()

    def test_duplicate_and_failed_receipts_cannot_back_claim(self):
        self.bundle['receipts'].append(copy.deepcopy(self.bundle['receipts'][0]))
        self.answer['product_tool_receipts'] = copy.deepcopy(self.bundle['receipts'])
        self.write_bundle()
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        self.bundle['receipts'].pop()
        self.bundle['receipts'][0].update({'success': False, 'output': None, 'output_sha256': None,
                                         'output_bytes': None, 'output_full_bytes': None,
                                         'output_normalized': None, 'output_normalized_sha256': None,
                                         'error': 'daemon unavailable'})
        self.answer['product_tool_receipts'] = copy.deepcopy(self.bundle['receipts'])
        self.write_bundle()
        report = self.evaluate()
        self.assertEqual(report['product_provenance']['run_receipts']['status'], 'verified')
        self.assertEqual(report['product_provenance']['status'], 'invalid')

    def test_output_hash_format_normalized_digest_and_audit_text_are_checked(self):
        receipt = self.bundle['receipts'][0]
        for field, bad in [('output_sha256', 'invalid'), ('output_normalized_sha256', '8' * 64),
                           ('output_bytes', 0), ('output_full_bytes', 1), ('output', 'tampered')]:
            with self.subTest(field=field):
                saved = receipt[field]
                receipt[field] = bad
                self.answer['product_tool_receipts'] = copy.deepcopy(self.bundle['receipts'])
                self.write_bundle()
                self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
                receipt[field] = saved

    def test_required_live_budget_policy_cannot_be_missing_or_forged(self):
        del self.bundle['budget_policy']
        self.write_bundle()
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        self.bundle['budget_policy'] = budget_policy()
        self.write_bundle()
        self.answer['budget_policy']['max_cost_usd'] = 100
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        del self.answer['budget_policy']
        self.assertEqual(self.evaluate()['status'], 'invalid_answer')

    def test_budget_values_fail_closed_at_runner_trust_boundary(self):
        for key, value in [('max_tokens', 0), ('max_tokens', -1), ('max_tokens', 1.5), ('max_tokens', True),
                           ('max_cost_usd', 0), ('max_cost_usd', -1), ('max_cost_usd', float('nan')),
                           ('max_cost_usd', float('inf')), ('timeout_seconds', 0), ('timeout_seconds', -1),
                           ('timeout_seconds', float('-inf')), ('retries', True), ('compaction', True),
                           ('cost_enforcement', 'trust_model'), ('token_enforcement', 'best_effort')]:
            with self.subTest(key=key, value=value):
                self.bundle['budget_policy'] = {**budget_policy(), key: value}
                self.write_bundle()
                report = self.evaluate()
                self.assertEqual(report['product_provenance']['status'], 'invalid')
                self.assertFalse(report['benchmark_eligible'])

    def test_nonfinite_and_negative_answer_budget_is_invalid_output(self):
        for value in (0, -1, float('nan'), float('inf')):
            with self.subTest(value=value):
                self.answer['budget_policy']['max_cost_usd'] = value
                self.assertEqual(self.evaluate()['status'], 'invalid_answer')

    def test_usage_cannot_be_missing_or_rewritten_in_rehashed_answer(self):
        del self.bundle['usage']
        self.write_bundle()
        self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
        self.bundle['usage'] = {'tokens_consumed': 300, 'cost_usd': 0.003, 'elapsed_seconds': 1.25}
        self.write_bundle()
        for key, replacement in [('tokens_consumed', 299), ('cost_usd', 0), ('elapsed_seconds', 0)]:
            with self.subTest(key=key):
                saved = self.answer[key]
                self.answer[key] = replacement
                self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
                self.answer[key] = saved

    def test_usage_values_must_be_finite_nonnegative_numbers(self):
        for key, replacement in [('tokens_consumed', -1), ('tokens_consumed', True), ('tokens_consumed', 2.5),
                                 ('cost_usd', float('nan')), ('cost_usd', -1),
                                 ('elapsed_seconds', float('inf')), ('elapsed_seconds', -1)]:
            with self.subTest(key=key, replacement=replacement):
                saved = self.bundle['usage'][key]
                self.bundle['usage'][key] = replacement
                self.write_bundle()
                self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')
                self.bundle['usage'][key] = saved

    def test_correctness_is_independent_of_consistent_valid_usage(self):
        before = self.evaluate()['metrics']
        self.bundle['usage'] = {'tokens_consumed': 600, 'cost_usd': 0.006, 'elapsed_seconds': 2.5}
        self.answer.update(self.bundle['usage'])
        self.write_bundle()
        report = self.evaluate()
        self.assertEqual(report['product_provenance']['status'], 'verified')
        self.assertEqual(report['metrics'], before)

    def test_static_oracle_cannot_rescue_bad_live_receipt_reference(self):
        original = score.verified_claims
        def static_receipt(*args, **kwargs):
            claims, oracle = original(*args, **kwargs)
            oracle['receipt_ids'] = ['invented']
            return claims, oracle
        self.answer['findings']['symbols'][0]['receipt_id'] = 'invented'
        with patch.object(score, 'verified_claims', static_receipt):
            self.assertEqual(self.evaluate()['product_provenance']['status'], 'invalid')

    def test_score_cli_accepts_separate_run_receipt_bundle(self):
        record = self.root / 'record.json'; record.write_text(json.dumps(self.record))
        answer = self.root / 'answer.json'; answer.write_text(json.dumps(self.answer))
        result = subprocess.run([sys.executable, str(Path(score.__file__)), 'score', '--record', str(record),
                                 '--answer', str(answer), '--run-receipts', str(self.path),
                                 '--trusted-run-receipts-sha256', self.pin], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['product_provenance']['status'], 'verified')


if __name__ == '__main__':
    unittest.main()
