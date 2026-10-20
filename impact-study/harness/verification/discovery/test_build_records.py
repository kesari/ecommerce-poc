import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('discovery_build_records', HERE / 'build_records.py')
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class CandidateRecordsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.trusted = json.loads((HERE / 'record-candidates/approved-proof-pins.json').read_text())
        cls.records = builder.candidates(builder.STUDY, cls.trusted)

    def test_all_twelve_records_are_draft_and_source_bound(self):
        self.assertEqual(set(self.records), {f'H{i:02}' for i in range(1, 13)})
        for record in self.records.values():
            self.assertEqual(record['status'], 'draft')
            self.assertEqual(record['evaluation_revision'], '2.2')
            self.assertEqual(len(record['repository_universe']), 10)
            self.assertEqual(record['repository_universe'], sorted(record['repository_universe']))
            self.assertTrue(record['query'].startswith(builder.QUERY))
            self.assertNotIn(record['origin_repo'] + '/src/', record['proposed_change']['diff'])
        self.assertEqual(len(self.records['H01']['repair_alternatives'][0]['actions']), 2)
        self.assertEqual({x['repo'] for x in self.records['H01']['repair_alternatives'][0]['actions']}, {'basket-service', 'commerce-web'})
        self.assertEqual(self.records['H07']['repair_alternatives'][0]['actions'][0]['operation'], 'scale_numeric_field')
        self.assertIn('until normal expiration', self.records['H08']['query'])
        for case in ('H08', 'H09', 'H10', 'H11', 'H12'):
            self.assertEqual(self.records[case]['edges'], [])
            self.assertEqual(self.records[case]['repair_alternatives'][0]['actions'], [])

    def test_product_contract_identity_uses_public_route_not_test_query(self):
        for case in ('H01', 'H04'):
            product_edges = [edge for edge in self.records[case]['edges'] if edge['consumer'] in ('commerce-bff', 'commerce-web')]
            self.assertEqual(len(product_edges), 2)
            self.assertTrue(all(edge['identifier'] == 'GET /api/v1/products' for edge in product_edges))

    def test_full_record_binding_rejects_changes_outside_fact_subset(self):
        bindings = builder.claim_bindings(self.records, self.trusted)
        builder.verify_record_bindings(self.records, bindings)
        for key, value in [('query', 'Altered public task'), ('status', 'verified'), ('snapshot_sha256', '0' * 64)]:
            altered = copy.deepcopy(self.records)
            altered['H01'][key] = value
            with self.assertRaisesRegex(ValueError, 'full record binding mismatch'):
                builder.verify_record_bindings(altered, bindings)

    def test_granular_claim_evidence_cannot_be_changed(self):
        bindings = builder.claim_bindings(self.records, self.trusted)
        bindings['records']['H01']['claims'][0]['artifact_paths'] = ['../../outside.json']
        with self.assertRaisesRegex(ValueError, 'claim evidence bindings changed'):
            builder.verify_record_bindings(self.records, bindings)

    def test_candidate_output_cannot_enter_an_indexed_source_estate(self):
        with self.assertRaisesRegex(ValueError, 'outside indexed estates'):
            builder.checked_output(Path('/Users/rajmohan/Projects/POC/POC-order-microservices/catalog-service/draft-oracles'))

    def test_wrong_external_manifest_pin_rejected(self):
        trusted = dict(self.trusted)
        trusted[builder.PROOFS['H01']] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'external proof pin mismatch'):
            builder.candidates(builder.STUDY, trusted)

    def test_missing_external_manifest_pin_rejected(self):
        trusted = dict(self.trusted)
        del trusted[builder.PROOFS['audit']]
        with self.assertRaisesRegex(ValueError, 'trusted registry'):
            builder.candidates(builder.STUDY, trusted)

    def test_changed_public_source_pin_rejected(self):
        original = builder.load
        def changed(root, relative):
            value = original(root, relative)
            if relative == 'harness/indexes/pins.json':
                value['repositories']['catalog-service']['tree'] = '0' * 40
            return value
        with patch.object(builder, 'load', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'source audit pins'):
                builder.candidates(builder.STUDY, self.trusted)

    def test_changed_artifact_rejected_even_with_valid_manifest_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            artifact = root / 'evidence/log.txt'
            artifact.write_text('reviewed result')
            manifest = root / 'evidence/manifest.json'
            manifest.write_text(json.dumps({'log.txt': builder.digest(artifact.read_bytes())}))
            trusted = {'evidence/manifest.json': builder.digest(manifest.read_bytes())}
            builder.validate_manifest(root, 'evidence/manifest.json', trusted)
            artifact.write_text('changed result')
            with self.assertRaisesRegex(ValueError, 'artifact hash mismatch'):
                builder.validate_manifest(root, 'evidence/manifest.json', trusted)

    def test_manifest_cannot_reference_outside_its_evidence_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            (root / 'outside.txt').write_text('outside')
            manifest = root / 'evidence/manifest.json'
            manifest.write_text(json.dumps({'../outside.txt': builder.digest(b'outside')}))
            trusted = {'evidence/manifest.json': builder.digest(manifest.read_bytes())}
            with self.assertRaisesRegex(ValueError, 'unsafe proof path'):
                builder.validate_manifest(root, 'evidence/manifest.json', trusted)

    def test_symlink_cannot_escape_evidence_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'evidence').mkdir()
            (root / 'outside.txt').write_text('outside')
            (root / 'evidence/link.txt').symlink_to(root / 'outside.txt')
            with self.assertRaisesRegex(ValueError, 'escapes root'):
                builder.safe_path(root / 'evidence', 'link.txt')


if __name__ == '__main__':
    unittest.main()
