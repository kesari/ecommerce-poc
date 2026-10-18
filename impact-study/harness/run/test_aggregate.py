import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import aggregate


class AggregateValidationTests(unittest.TestCase):

    def test_structurally_invalid_answer_is_rejected_even_when_score_file_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = {
                "change_id": "REST-001",
                "contestant": "not-a-contestant",
                "findings": {},
            }
            report = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "metrics": {},
                "counts": {"missed_by_severity": {}},
                "missed": [],
            }
            (runs / "REST-001-pi-1.json").write_text(json.dumps(answer))
            (runs / "REST-001-pi-1.score.json").write_text(json.dumps(report))

            cells, rejected = aggregate.collect(runs)

            self.assertFalse(cells)
            self.assertEqual(len(rejected), 1)
            self.assertEqual(rejected[0]["reason"], "answer schema validation failed")

    def test_single_bad_finding_is_tolerated_and_scored(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "findings": {
                    "contracts": [{"type": "carrier-pigeon", "identifier": "bad"}]
                },
            }
            report = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "metrics": {},
                "counts": {"missed_by_severity": {}},
                "missed": [],
            }
            (runs / "REST-001-pi-1.json").write_text(json.dumps(answer))
            (runs / "REST-001-pi-1.score.json").write_text(json.dumps(report))

            cells, rejected = aggregate.collect(runs)

            self.assertTrue(cells)
            self.assertFalse(rejected)

    def test_stale_score_is_rejected_on_hash_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "findings": {},
            }
            (runs / "REST-001-pi-1.json").write_text(json.dumps(answer))
            report = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "metrics": {},
                "counts": {"missed_by_severity": {}},
                "missed": [],
                "answer_sha256": "0" * 64,
            }
            (runs / "REST-001-pi-1.score.json").write_text(json.dumps(report))

            cells, rejected = aggregate.collect(runs)

            self.assertFalse(cells)
            self.assertEqual(rejected[0]["reason"], "stale score: answer hash mismatch")

    def test_synthetic_example_never_enters_scorecard(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "synthetic": True,
                "findings": {},
            }
            (runs / "REST-001-pi-1.json").write_text(json.dumps(answer))
            report = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "metrics": {},
                "counts": {"missed_by_severity": {}},
                "missed": [],
            }
            (runs / "REST-001-pi-1.score.json").write_text(json.dumps(report))

            cells, rejected = aggregate.collect(runs)

            self.assertFalse(cells)
            self.assertIn("synthetic", rejected[0]["reason"])

    def test_rejection_stub_is_counted(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            (runs / "REST-001-pi-1.rejected.json").write_text(json.dumps({
                "change_id": "REST-001",
                "runner": "pi",
                "run_index": 1,
                "reason": "boom",
            }))

            cells, rejected = aggregate.collect(runs)

            self.assertFalse(cells)
            self.assertEqual(len(rejected), 1)
            self.assertEqual(rejected[0]["reason"], "boom")


class EligibilityAndCohortTests(unittest.TestCase):
    def product_answer(self):
        return {
            "contestant": "gortex-first", "product_assisted_eligible": True,
            "estate_sha256": "a" * 64,
            "product_provenance": {"mode": "real_product", "indexed_estate_sha256": "a" * 64},
            "product_playbook": [{"tool": "gortex_query", "ok": True, "receipt_id": "r1",
                                  "parameters": {"operation": "symbol", "query": "Address"}}],
            "product_tool_receipts": [{"id": "r1", "tool": "gortex_query", "operation": "symbol", "success": True,
                                       "parameters": {"operation": "symbol", "query": "Address", "repo": "account-service"}}],
        }

    def test_product_first_requires_each_actual_step_receipt(self):
        answer = self.product_answer()
        self.assertIsNone(aggregate.product_ineligibility(answer, "product-first"))
        answer["product_playbook"][0]["receipt_id"] = "r2"
        self.assertEqual(aggregate.product_ineligibility(answer, "product-first"),
                         "playbook step has no matching invocation receipt")

    def test_product_stamp_does_not_override_wrong_arguments_duplicate_ids_or_stale_snapshot(self):
        answer = self.product_answer()
        answer["product_tool_receipts"][0]["parameters"]["query"] = "Unrelated"
        self.assertIsNotNone(aggregate.product_ineligibility(answer, "product-first"))
        answer = self.product_answer()
        answer["product_tool_receipts"].append(answer["product_tool_receipts"][0])
        self.assertEqual(aggregate.product_ineligibility(answer, "product-first"), "duplicate product receipt id")
        answer = self.product_answer()
        answer["product_provenance"]["indexed_estate_sha256"] = "b" * 64
        self.assertEqual(aggregate.product_ineligibility(answer, "product-first"), "product receipt snapshot mismatch")

    def test_ineligible_product_answer_excluded_even_with_successful_receipt(self):
        answer = self.product_answer()
        answer["product_assisted_eligible"] = False
        self.assertEqual(aggregate.product_ineligibility(answer, "product-first"), "product-assisted run is ineligible")

    def test_natural_adoption_never_silently_enters_product_first_report(self):
        answer = self.product_answer()
        answer["contestant"] = "gortex"
        answer["product_playbook"] = None
        self.assertEqual(aggregate.product_ineligibility(answer, "product-first"),
                         "different product treatment: natural-adoption")
        self.assertIsNone(aggregate.product_ineligibility(answer, "natural-adoption"))
        self.assertIsNone(aggregate.product_ineligibility(answer, "all"))

    def write_run(self, runs, name, **changes):
        answer = {"change_id": "REST-001", "contestant": "agent-only", "findings": {},
                  "tool_calls": 1, "tokens_consumed": 25, **changes}
        report = {"change_id": "REST-001", "contestant": "agent-only", "metrics": {},
                  "counts": {"missed_by_severity": {}}, "missed": []}
        (runs / f"{name}.json").write_text(json.dumps(answer))
        (runs / f"{name}.score.json").write_text(json.dumps(report))

    def test_cheap_valid_answers_are_scored_and_different_cohorts_are_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            self.write_run(runs, "first", prompt_sha256="a" * 64)
            self.write_run(runs, "second", prompt_sha256="b" * 64)
            cells, rejected = aggregate.collect(runs)
            self.assertEqual(len(cells), 2)
            self.assertFalse(rejected)

    def test_v2_cannot_enter_v1_report_without_explicit_protocol_dispatch(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            self.write_run(runs, "v2", protocol_version=2)
            cells, rejected = aggregate.collect(runs)
            self.assertFalse(cells)
            self.assertEqual(rejected[0]["reason"], "answer/report protocol mismatch")

    def test_collect_rechecks_eligibility_instead_of_trusting_saved_score(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = self.product_answer()
            answer.update(change_id="REST-001", findings={})
            answer["product_provenance"].update({
                "product": "gortex", "version": "test", "commit": None,
                "binary_sha256": "b" * 64, "artifact_sha256": "c" * 64,
                "manifest_sha256": None, "query_surface": ["symbol"], "freshness": "verified",
                "adapter_version": "test", "config_sha256": "d" * 64,
                "index_built_at": None, "index_duration_seconds": None,
            })
            answer["product_playbook"][0]["receipt_id"] = "r999"
            text = json.dumps(answer)
            report = {"change_id": "REST-001", "contestant": "gortex-first", "metrics": {},
                      "counts": {"missed_by_severity": {}}, "missed": [],
                      "answer_sha256": hashlib.sha256(text.encode()).hexdigest()}
            for key, target in {
                "ground_truth_sha256": aggregate.HARNESS / "records/REST-001.json",
                "scorer_sha256": aggregate.HARNESS / "scoring/score.py",
                "answer_schema_sha256": aggregate.HARNESS / "schema/contestant-answer.schema.json",
            }.items():
                report[key] = hashlib.sha256(target.read_bytes()).hexdigest()
            (runs / "product.json").write_text(text)
            (runs / "product.score.json").write_text(json.dumps(report))
            cells, rejected = aggregate.collect(runs)
            self.assertFalse(cells)
            self.assertEqual(rejected[0]["reason"], "playbook step has no matching invocation receipt")
            self.assertEqual(rejected[0]["status"], "ineligible_product")

    def test_all_failed_campaign_still_reports_explicit_failure_states(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "attempt.rejected.json").write_text(json.dumps({
                "runner": "agent", "reason": "deadline exceeded", "status": "budget_exhausted",
            }))
            result = subprocess.run([sys.executable, aggregate.__file__, "--runs-dir", directory, "--json"],
                                    capture_output=True, text=True, check=True)
            scorecard = json.loads(result.stdout)
            self.assertFalse(scorecard["cells"])
            self.assertEqual(scorecard["outcomes"]["agent"]["failure_states"], {"budget_exhausted": 1})

    def test_malformed_artifact_does_not_abort_remaining_valid_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            self.write_run(runs, "valid")
            (runs / "broken.score.json").write_text("{")
            cells, rejected = aggregate.collect(runs)
            self.assertEqual(len(cells), 1)
            self.assertEqual(len(rejected), 1)
            self.assertEqual(rejected[0]["status"], "invalid_output")


class RenderScorecardTests(unittest.TestCase):
    def test_separates_blind_spots_from_unstable_misses(self):
        def cell(missed):
            return {"change_id": "REST-001", "contestant": "agent-only",
                    "metrics": {"composite": 0.5}, "counts": {"missed_by_severity": {}},
                    "missed": [{"kind": "repository", "key": key, "severity": "contract_consumer"}
                               for key in missed]}

        summary = aggregate.summarize([cell(["always", "sometimes"]), cell(["always"]), cell(["always"])])
        text = aggregate.render_scorecard(
            {"cells": {"REST-001": {"pi": summary}}, "by_contestant": {}, "rejected": []})

        self.assertIn("0.50", text)
        self.assertIn("blind spots, missed in every run:", text)
        self.assertIn("unstable, missed in some runs:", text)

    def test_small_n_does_not_claim_reproducibility(self):
        def cell(missed):
            return {"change_id": "REST-001", "contestant": "agent-only",
                    "metrics": {"composite": 0.5}, "counts": {"missed_by_severity": {}},
                    "missed": [{"kind": "repository", "key": key, "severity": "contract_consumer"}
                               for key in missed]}

        summary = aggregate.summarize([cell(["always", "sometimes"]), cell(["always"])])
        text = aggregate.render_scorecard(
            {"cells": {"REST-001": {"pi": summary}}, "by_contestant": {}, "rejected": []})

        self.assertNotIn("blind spots", text)
        self.assertIn("missed:", text)

    def test_outcomes_include_rejected_attempts(self):
        cells = {("REST-001", "pi", "a" * 64): [{}, {}]}
        outcomes = aggregate.outcome_summary(cells, [
            {"runner": "pi", "reason": "timeout"},
            {"runner": "other", "reason": "thin"},
        ])
        self.assertEqual(outcomes["pi"], {
            "scored": 2, "rejected": 1, "attempts": 3, "success_rate": 0.6667,
            "failure_states": {"run_failed": 1},
        })
        self.assertEqual(outcomes["other"]["success_rate"], 0.0)


if __name__ == "__main__":
    unittest.main()
