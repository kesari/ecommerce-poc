import json
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


if __name__ == "__main__":
    unittest.main()
