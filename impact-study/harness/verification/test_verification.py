import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("verify_rest001", HERE / "verify_rest001.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
oracle_spec = importlib.util.spec_from_file_location("build_oracle", HERE / "rest001/build_oracle.py")
oracle = importlib.util.module_from_spec(oracle_spec)
oracle_spec.loader.exec_module(oracle)


class TrustBoundaryTest(unittest.TestCase):
    def args(self, output):
        return Namespace(output=output, estate=Path("/private/tmp/verification-test-estate"), java_home=None,
                         trusted_criteria_sha256=hashlib.sha256((HERE / "rest001/criteria.json").read_bytes()).hexdigest(),
                         trusted_test_sha256=hashlib.sha256((HERE / "rest001/Rest001BoundaryTest.java").read_bytes()).hexdigest())

    def test_oracle_change_requires_external_approval_before_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory) / "new-output")
            args.trusted_test_sha256 = "0" * 64
            with self.assertRaisesRegex(ValueError, "reviewed oracle digest mismatch"):
                verifier.run(args)
            self.assertFalse(args.output.exists())

    def test_output_cannot_enter_searchable_estate(self):
        args = self.args(Path("/private/tmp/verification-test-estate/leaked-output"))
        with self.assertRaisesRegex(ValueError, "outside the estate"):
            verifier.run(args)

    def test_output_cannot_enter_study_repository(self):
        args = self.args(HERE / "leaked-output")
        with self.assertRaisesRegex(ValueError, "outside the estate"):
            verifier.run(args)

    def test_existing_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            (args.output / "preserved.log").write_text("original evidence")
            with self.assertRaisesRegex(ValueError, "must not already exist"):
                verifier.run(args)
            self.assertEqual((args.output / "preserved.log").read_text(), "original evidence")


class EvidenceBindingTest(unittest.TestCase):
    evidence = HERE / "evidence/REST-001"
    record = HERE / "rest001/record-candidate.json"
    approved_manifest = "bf8e18e7bbe346faa89c20e3f0f969d7eb963e567cc8499fda69361e41420b27"

    def test_reviewed_observations_bind_exact_scoped_claims(self):
        bundle = oracle.build(self.evidence, self.record, self.approved_manifest)
        self.assertEqual(bundle["change_id"], "REST-001")
        self.assertEqual(len([x for x in bundle["attestations"] if x["kind"] == "actions"]), 1)
        claim = next(x["claim"] for x in bundle["attestations"] if x["kind"] == "actions")
        self.assertEqual(claim["operation"], "read_json_field")
        self.assertEqual(claim["after"], "postcode")

    def test_edited_execution_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence"
            shutil.copytree(self.evidence, evidence)
            (evidence / "changed-observation.json").write_text('{"bound_postal_code":"560001"}')
            with self.assertRaisesRegex(ValueError, "execution artifact changed"):
                oracle.build(evidence, self.record, self.approved_manifest)

    def test_edited_manifest_does_not_approve_itself(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence"
            shutil.copytree(self.evidence, evidence)
            manifest = evidence / "manifest.json"
            manifest.write_text(manifest.read_text() + "\n")
            with self.assertRaisesRegex(ValueError, "caller's reviewed pin"):
                oracle.build(evidence, self.record, self.approved_manifest)

    def test_cannot_reuse_proof_for_a_different_action(self):
        with tempfile.TemporaryDirectory() as directory:
            record = json.loads(self.record.read_text())
            record["repair_alternatives"][0]["actions"][0]["after"] = "invented"
            altered = Path(directory) / "record.json"
            altered.write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError, "reviewed bounded predicates"):
                oracle.build(self.evidence, altered, self.approved_manifest)


if __name__ == "__main__":
    unittest.main()
