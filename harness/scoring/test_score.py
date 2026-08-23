import json
import unittest
from pathlib import Path

import score

sys_path = Path(__file__).parent
GT = {
    "change_id": "REST-001",
    "category": "rest_contract",
    "query": "q",
    "ground_truth": {
        "affected_repositories": [
            {"name": "payment-service", "criticality": "critical"},
            {"name": "checkout-service", "criticality": "contract_consumer"},
            {"name": "reconciliation-service", "criticality": "contract_consumer"},
            {"name": "integration-tests", "criticality": "test_only"},
            {"name": "legacy-reporting", "criticality": "informational"},
        ],
        "affected_symbols": [
            {"fqn": "com.acme.PaymentRequest", "repo": "payment-service"},
        ],
        "affected_contracts": [
            {
                "type": "rest",
                "identifier": "POST /payments/authorisations",
                "provider_repo": "payment-service",
                "consumer_repos": ["checkout-service"],
            },
            {
                "type": "kafka",
                "identifier": "payment.authorised",
                "provider_repo": "payment-service",
                "consumer_repos": ["reconciliation-service"],
            },
        ],
        "required_tests": [
            {"repo": "payment-service", "suite": "PaymentRequestTest"},
        ],
    },
}


def answer(contestant="agent-only", **overrides):
    base = {
        "change_id": "REST-001",
        "contestant": contestant,
        "elapsed_seconds": 150,
        "tokens_consumed": 1000,
        "findings": {
            "repositories": [
                {"name": "payment-service", "evidence_tier": "compiler", "evidence": "owner"},
                {"name": "checkout-service", "evidence_tier": "contract_matched", "evidence": "client call"},
                {"name": "ghost-service", "evidence_tier": "inferred", "evidence": "guess"},
                {"name": "no-provenance-service"},
            ],
            "symbols": [{"fqn": "com.acme.PaymentRequest", "evidence_tier": "compiler", "evidence": "changed"}],
            "contracts": [
                {"type": "REST", "identifier": "post /payments/authorisations", "evidence_tier": "extracted", "evidence": "match"},
            ],
            "tests": [{"repo": "payment-service", "suite": "PaymentRequestTest", "evidence_tier": "compiler", "evidence": "unit"}],
        },
    }
    for key, value in overrides.items():
        if key == "findings":
            base["findings"].update(value)
        else:
            base[key] = value
    return base


class ScoreTests(unittest.TestCase):
    def setUp(self):
        self.report = score.score_change(GT, answer(), 300)

    def test_perfect_on_reported_items(self):
        m = self.report["metrics"]
        self.assertEqual(m["cross_repo_recall"], 0.4)
        self.assertEqual(m["contract_recall"], 0.5)
        self.assertEqual(m["precision"], 2 / 4)
        self.assertEqual(m["symbol_recall"], 1.0)
        self.assertEqual(m["test_recall"], 1.0)

    def test_miss_severity_mapping(self):
        severities = {miss["key"]: miss["severity"] for miss in self.report["missed"]}
        self.assertEqual(severities["legacy-reporting"], "informational")
        self.assertEqual(severities["integration-tests"], "test_suite")

    def test_contract_matching_is_normalized(self):
        missed_keys = [miss["key"] for miss in self.report["missed"]]
        self.assertNotIn("rest post /payments/authorisations", missed_keys)
        kafka = [m for m in self.report["missed"] if m["key"].startswith("kafka")]
        self.assertEqual(kafka[0]["severity"], "contract_consumer")

    def test_false_positives_recorded(self):
        self.assertEqual(self.report["false_positives"]["repositories"], ["ghost-service", "no-provenance-service"])

    def test_evidence_quality_counts_only_valid_provenance(self):
        self.assertAlmostEqual(self.report["metrics"]["evidence_quality"], 6 / 7, places=4)
        self.assertEqual(
            self.report["provenance_distribution"]["unclassified"], 1,
            "finding without tier or evidence counts as unclassified",
        )

    def test_manual_inputs_missing_renormalize_weights(self):
        self.assertEqual(sorted(self.report["excluded_components"]), ["freshness", "operational_cost"])
        total = sum(self.report["weights_applied"].values())
        self.assertAlmostEqual(total, 1.0, places=4)

    def test_composite_matches_hand_computed_value(self):
        composite = self.report["metrics"]["composite"]
        expected = (
            (0.35 / 0.85) * 0.4
            + (0.20 / 0.85) * 0.5
            + (0.15 / 0.85) * 0.5
            + (0.10 / 0.85) * (6 / 7)
            + (0.05 / 0.85) * 1.0
        )
        self.assertAlmostEqual(composite, round(expected, 4), places=3)

    def test_latency_score_caps_at_one(self):
        report = score.score_change(GT, answer(elapsed_seconds=30), 300)
        self.assertEqual(report["metrics"]["latency"], 1.0)

    def test_change_id_mismatch_raises(self):
        with self.assertRaises(SystemExit):
            score.score_change(GT, answer(change_id="REST-002"), 300)


class MarginalTests(unittest.TestCase):
    def gortex_answer(self):
        return answer(
            contestant="gortex",
            findings={
                "repositories": [
                    {"name": "payment-service", "evidence_tier": "compiler", "evidence": "owner"},
                    {"name": "checkout-service", "evidence_tier": "compiler", "evidence": "client"},
                    {"name": "reconciliation-service", "evidence_tier": "contract_matched", "evidence": "topic edge"},
                    {"name": "ghost-service", "evidence_tier": "hypothesis", "evidence": "maybe"},
                ],
                "contracts": [
                    {"type": "rest", "identifier": "POST /payments/authorisations", "evidence_tier": "contract_matched", "evidence": "edge"},
                    {"type": "kafka", "identifier": "payment.authorised", "evidence_tier": "contract_matched", "evidence": "edge"},
                ],
            },
        )

    def test_unique_discoveries_classified_against_ground_truth(self):
        result = score.marginal(GT, answer(), self.gortex_answer())
        self.assertEqual(result["summary"]["unique_true_positives"], 2)
        self.assertEqual(result["summary"]["unique_false_positives"], 0,
                         "ghost-service is reported by both contestants, so it is not a unique discovery")
        tp_keys = [i["key"] for i in result["unique_repository_discoveries"] if i["classification"] == "true_positive"]
        self.assertIn("reconciliation-service", tp_keys)


if __name__ == "__main__":
    unittest.main()
