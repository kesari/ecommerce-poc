"""Regression checks for exact, independently proved REST notation/repair aliases."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "scoring"))
import build_rest001_equivalence_oracle as builder
import verify_rest001_equivalence as verifier
import score_v2 as scorer

ROOT = HERE / "evidence/REST-001-equivalences-v2.1"
MANIFEST_PIN = "8459ba53740421fe04b56078e0ad7fcc5c919c10fea62964fc2ae5ab440f72b3"
BUNDLE_PIN = "d6ed60fa90d5183dc6b6582effdbfef99d4cb88f9436ae64dbccc0c015d3b317"
RECORD_PATH = HERE.parent / "records/v2/REST-001.json"


def read(path):
    return json.loads(path.read_text())


def answer():
    record = read(RECORD_PATH)
    plan = record["repair_alternatives"][0]
    findings = {kind: copy.deepcopy(record[kind]) for kind in ("repositories", "symbols", "edges")}
    findings["actions"] = [{key: value for key, value in item.items() if key != "critical"} for item in plan["actions"]]
    findings["obligations"] = copy.deepcopy(plan["obligations"])
    return {"protocol_version": 2, "change_id": "REST-001", "snapshot_sha256": record["snapshot_sha256"], "findings": findings}


def evaluate(payload):
    return scorer.score_change(read(RECORD_PATH), payload, oracle_bundle_path=ROOT / "oracle-bundle-v2.1.json",
                               trusted_oracle_sha256=BUNDLE_PIN, artifact_root=ROOT)


class ProtectedEquivalenceProofTest(unittest.TestCase):
    def setUp(self):
        self.identity = read(ROOT / "method-mapping/source-identity.json")
        self.source = (ROOT / "method-mapping/baseline-AccountClient.java").read_text()

    def test_original_record_criteria_test_and_bundle_stay_unchanged(self):
        self.assertEqual(builder.sha(builder.canonical(read(RECORD_PATH))), builder.RECORD_PIN)
        self.assertEqual(builder.sha((HERE / "rest001/criteria.json").read_bytes()), verifier.APPROVED_CRITERIA)
        self.assertEqual(builder.sha((HERE / "rest001/Rest001BoundaryTest.java").read_bytes()), verifier.APPROVED_TEST)
        self.assertEqual(builder.sha((HERE / "evidence/REST-001/oracle-bundle.json").read_bytes()), builder.REFERENCE_BUNDLE_PIN)

    def test_proved_bundle_reproduces_exactly_with_new_external_pin(self):
        built = builder.build(ROOT, RECORD_PATH, MANIFEST_PIN)
        self.assertEqual(built, read(ROOT / "oracle-bundle-v2.1.json"))
        self.assertEqual(builder.sha((ROOT / "oracle-bundle-v2.1.json").read_bytes()), BUNDLE_PIN)
        self.assertEqual(built["scoring_policy"], {"mode": "scoped_reference_v1"})
        self.assertEqual(len(built["equivalences"]), 10)

    def test_wrong_repository_or_record_cannot_receive_component_identity(self):
        for repo, record in (("shipment-service", verifier.RECORD), ("order-service", verifier.CLIENT + ".OtherAddressResponse")):
            with self.subTest(repo=repo, record=record), self.assertRaisesRegex(ValueError, "repository or record"):
                verifier.source_identity(self.source, repo, record)

    def test_changed_component_source_cannot_reuse_snapshot_identity(self):
        source = self.source.replace("String postalCode,", "String postCode,")
        with self.assertRaisesRegex(ValueError, "source differs"):
            verifier.source_identity(source)

    def test_lexical_source_alone_cannot_certify_method_repair(self):
        self.identity["method_mapping_criteria_passed"] = False
        with self.assertRaisesRegex(ValueError, "source-only"):
            builder.exact_equivalences(self.identity, self.source, read(RECORD_PATH), ["proof"])

    def test_incomplete_or_changed_record_phase_identity_is_rejected(self):
        for mutation in ("missing", "changed"):
            identity = copy.deepcopy(self.identity)
            if mutation == "missing":
                identity["phase_record_body_sha256"].pop("faulted-repair")
            else:
                identity["phase_record_body_sha256"]["repaired"] = "0" * 64
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, "component changed"):
                builder.exact_equivalences(identity, self.source, read(RECORD_PATH), ["proof"])

    def test_modified_mapping_patch_or_member_proof_is_rejected(self):
        for path in ("repaired.patch", "source-identity.json", "record-javap.log"):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "proof"
                shutil.copytree(ROOT, root)
                target = root / "method-mapping" / path
                target.write_bytes(target.read_bytes() + b"\nmodified\n")
                with self.assertRaisesRegex(ValueError, "proof artifact changed"):
                    builder.build(root, RECORD_PATH, MANIFEST_PIN)


class ExactEquivalenceScoringTest(unittest.TestCase):
    def test_each_tested_method_target_fulfils_same_required_action(self):
        bundle = read(ROOT / "oracle-bundle-v2.1.json")
        for item in bundle["equivalences"]:
            if item["kind"] != "actions":
                continue
            with self.subTest(target=item["alias"]["target"]):
                payload = answer()
                payload["findings"]["actions"] = [item["alias"]]
                report = evaluate(payload)
                self.assertEqual(report["metrics"]["actions"]["recall"], 1)
                self.assertTrue(report["satisfies_required_actions"])
                self.assertEqual(report["critical_downstream_action_misses"], [])
                self.assertEqual(len(report["equivalences_applied"]), 1)

    def test_accessor_spellings_are_exact_component_symbols(self):
        for alias in (verifier.RECORD + "#postalCode()", verifier.RECORD + ".postalCode()"):
            with self.subTest(alias=alias):
                payload = answer()
                payload["findings"]["symbols"][0]["fqn"] = alias
                self.assertEqual(evaluate(payload)["metrics"]["symbols"]["recall"], 1)

    def test_renamed_field_refers_to_same_exact_directed_rest_change(self):
        payload = answer()
        payload["findings"]["edges"][0]["field"] = "postcode"
        self.assertEqual(evaluate(payload)["metrics"]["edges"]["recall"], 1)
        payload["findings"]["edges"][0].update(provider="order-service", consumer="account-service")
        self.assertEqual(evaluate(payload)["metrics"]["edges"]["recall"], 0)

    def test_wrong_repo_record_method_or_operation_does_not_normalize(self):
        alterations = [
            {"repo": "shipment-service", "target": verifier.CLIENT + ".address"},
            {"target": verifier.CLIENT + ".OtherAddressResponse#postalCode()"},
            {"target": verifier.CLIENT + "#address(UUID)"},
            {"target": verifier.CLIENT},
            {"target": verifier.CLIENT + ".address", "operation": "retain_json_alias"},
            {"target": verifier.CLIENT + ".address", "after": "wrongPostcode"},
        ]
        for change in alterations:
            with self.subTest(change=change):
                payload = answer()
                payload["findings"]["actions"][0].update(change)
                report = evaluate(payload)
                self.assertEqual(report["metrics"]["actions"]["recall"], 0)
                self.assertFalse(report["satisfies_required_actions"])
                self.assertEqual(len(report["critical_downstream_action_misses"]), 1)

    def test_canonical_and_alias_duplicates_do_not_add_credit(self):
        payload = answer()
        canonical = payload["findings"]["actions"][0]
        payload["findings"]["actions"] += [{**canonical, "target": verifier.CLIENT + ".address"},
                                            {**canonical, "target": verifier.RECORD + "#postalCode()"}]
        report = evaluate(payload)
        self.assertEqual(report["metrics"]["actions"]["reported"], 1)
        self.assertEqual(report["metrics"]["actions"]["precision"], 1)
        self.assertEqual(report["evidence"]["actions"]["verified_correct"], 1)


if __name__ == "__main__":
    unittest.main()
