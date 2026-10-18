"""Record-specific regressions for the independently executed pilot evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("pilot_oracle_builder", HERE / "build_oracle.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
sys.path.insert(0, str(HERE.parent / "scoring"))
import score_v2 as scorer

BUNDLE_PINS = {
    "REST-001": "33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6",
    "INT-002": "6e9da31219f08cf19efd59208489fe6bfadecb79988480ca27cd6b8bf9d52c37",
    "KAFKA-001": "306d030809024cdba8d209e000dfff2258e51c31b20243f9eabb20e8d68751ab",
}


def record_path(change):
    return HERE.parent / "records/v2" / f"{change}.json"


def load_record(change):
    return json.loads(record_path(change).read_text())


def answer_for(record):
    findings = {kind: copy.deepcopy(record[kind]) for kind in ("repositories", "symbols", "edges")}
    plan = record["repair_alternatives"][0]
    findings["actions"] = [{key: value for key, value in claim.items() if key != "critical"} for claim in plan["actions"]]
    findings["obligations"] = copy.deepcopy(plan["obligations"])
    return {"protocol_version": 2, "change_id": record["change_id"], "snapshot_sha256": record["snapshot_sha256"], "findings": findings}


class ReviewedPilotBundleTest(unittest.TestCase):
    def build(self, change, evidence=None, record=None, manifest_pin=None):
        return builder.build(evidence or HERE / "evidence" / change, record or record_path(change),
                             manifest_pin or builder.SPECS[change]["manifest"])

    def test_cache_control_attests_no_production_edits(self):
        bundle = self.build("INT-002")
        self.assertEqual([claim for claim in bundle["attestations"] if claim["kind"] == "actions"], [])
        obligations = [claim["claim"] for claim in bundle["attestations"] if claim["kind"] == "obligations"]
        self.assertEqual(obligations, [{"repo": "shipment-service", "obligation": "validation_only"}])
        self.assertEqual([claim["claim"] for claim in bundle["attestations"] if claim["kind"] == "repositories"],
                         [{"repo": "shipment-service", "runtime_status": "unchanged"}])

    def test_kafka_attests_only_both_main_subscription_repairs(self):
        bundle = self.build("KAFKA-001")
        actions = [claim["claim"] for claim in bundle["attestations"] if claim["kind"] == "actions"]
        self.assertEqual({claim["repo"] for claim in actions}, {"basket-service", "shipment-service"})
        self.assertEqual(len(actions), 2)
        for action in actions:
            self.assertEqual((action["operation"], action["before"], action["after"]),
                             ("subscribe_topic", "order.confirmed.v1", "order.confirmed.v2"))

    def test_changed_runtime_or_repair_claim_cannot_reuse_execution(self):
        for change in builder.SPECS:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                record = load_record(change)
                if change == "INT-002":
                    record["repositories"][0]["runtime_status"] = "broken"
                else:
                    record["repair_alternatives"][0]["actions"][0]["after"] = "order.confirmed.v3"
                altered = Path(directory) / "record.json"
                altered.write_text(json.dumps(record))
                with self.assertRaisesRegex(ValueError, "reviewed scoped predicates"):
                    self.build(change, record=altered)

    def test_weakened_diff_or_reversed_edge_cannot_reuse_execution(self):
        for mutation in ("diff", "edge"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                record = load_record("KAFKA-001")
                if mutation == "diff":
                    record["proposed_change"]["diff"] = "// TODO rename topic"
                else:
                    record["edges"][0].update(provider="basket-service", consumer="order-service")
                altered = Path(directory) / "record.json"
                altered.write_text(json.dumps(record))
                with self.assertRaisesRegex(ValueError, "reviewed scoped predicates"):
                    self.build("KAFKA-001", record=altered)

    def test_changed_observation_patch_or_junit_is_rejected(self):
        for change in builder.SPECS:
            for artifact in ("changed-observation.json", "repaired.patch", "faulted-repair-junit.xml"):
                with self.subTest(change=change, artifact=artifact), tempfile.TemporaryDirectory() as directory:
                    evidence = Path(directory) / "evidence"
                    shutil.copytree(HERE / "evidence" / change, evidence)
                    path = evidence / artifact
                    path.write_bytes(path.read_bytes() + b"\nmodified\n")
                    with self.assertRaisesRegex(ValueError, "execution artifact changed"):
                        self.build(change, evidence=evidence)

    def test_new_hash_does_not_self_approve_an_edited_manifest(self):
        for change in builder.SPECS:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                evidence = Path(directory) / "evidence"
                shutil.copytree(HERE / "evidence" / change, evidence)
                manifest = evidence / "manifest.json"
                content = json.loads(manifest.read_text())
                content["phases"]["faulted-repair"]["fault_detected"] = False
                manifest.write_text(json.dumps(content))
                new_pin = hashlib.sha256(manifest.read_bytes()).hexdigest()
                with self.assertRaisesRegex(ValueError, "reviewed external pin"):
                    self.build(change, evidence=evidence, manifest_pin=new_pin)

    def test_bundles_bind_every_expected_predicate_in_preflight(self):
        for change in builder.SPECS:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                bundle_path = Path(directory) / "bundle.json"
                bundle_path.write_text(json.dumps(self.build(change)))
                pin = hashlib.sha256(bundle_path.read_bytes()).hexdigest()
                report = scorer.preflight(load_record(change), oracle_bundle_path=bundle_path,
                                          trusted_oracle_sha256=pin, artifact_root=HERE / "evidence" / change)
                self.assertEqual(report["status"], "verified")
                self.assertEqual(len(report["covered_repair_alternatives"]), 1)

    def test_saved_pilot_bundles_pass_external_literal_pin_preflight(self):
        for change, pin in BUNDLE_PINS.items():
            with self.subTest(change=change):
                root = HERE / "evidence" / change
                report = scorer.preflight(load_record(change), oracle_bundle_path=root / "oracle-bundle.json",
                                          trusted_oracle_sha256=pin, artifact_root=root)
                self.assertEqual(report["status"], "verified")
                self.assertEqual(report["errors"], [])
                self.assertEqual(len(report["covered_repair_alternatives"]), 1)

    def test_edited_record_invalidates_existing_scorer_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle_path = Path(directory) / "bundle.json"
            bundle_path.write_text(json.dumps(self.build("KAFKA-001")))
            pin = hashlib.sha256(bundle_path.read_bytes()).hexdigest()
            record = load_record("KAFKA-001")
            record["repair_alternatives"][0]["actions"].pop()
            record["repair_alternatives"][0]["obligations"][-1]["obligation"] = "validation_only"
            report = scorer.preflight(record, oracle_bundle_path=bundle_path, trusted_oracle_sha256=pin,
                                      artifact_root=HERE / "evidence/KAFKA-001")
            self.assertEqual(report["status"], "unverified")
            self.assertTrue(any("record" in error for error in report["errors"]))


class PilotClaimScoringTest(unittest.TestCase):
    def test_invented_edit_is_a_false_positive_in_benign_control(self):
        record = load_record("INT-002")
        answer = answer_for(record)
        answer["findings"]["actions"] = [{"repo": "shipment-service", "target": "invented",
                                           "operation": "subscribe_topic", "before": "old", "after": "new"}]
        answer["findings"]["obligations"][0]["obligation"] = "production_edit"
        report = scorer.score_change(record, answer)
        self.assertEqual(report["metrics"]["actions"]["correct"], 0)
        self.assertEqual(report["metrics"]["actions"]["precision"], 0)
        self.assertEqual(report["metrics"]["obligations"]["recall"], 0)
        self.assertFalse(report["satisfies_any_repair"])

    def test_omitted_basket_repair_is_an_explicit_critical_miss(self):
        record = load_record("KAFKA-001")
        answer = answer_for(record)
        answer["findings"]["actions"] = [action for action in answer["findings"]["actions"] if action["repo"] != "basket-service"]
        report = scorer.score_change(record, answer)
        self.assertEqual(report["metrics"]["repositories"]["recall"], 1)
        self.assertEqual(report["metrics"]["actions"]["recall"], 0.5)
        self.assertEqual([claim["repo"] for claim in report["critical_downstream_action_misses"]], ["basket-service"])
        self.assertFalse(report["satisfies_any_repair"])

    def test_reversing_one_kafka_edge_loses_only_that_edge(self):
        record = load_record("KAFKA-001")
        answer = answer_for(record)
        answer["findings"]["edges"][0].update(provider="basket-service", consumer="order-service")
        report = scorer.score_change(record, answer)
        self.assertEqual(report["metrics"]["edges"]["correct"], 1)
        self.assertEqual(report["metrics"]["edges"]["precision"], 0.5)
        self.assertEqual(report["metrics"]["actions"]["recall"], 1)


if __name__ == "__main__":
    unittest.main()
