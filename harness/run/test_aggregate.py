import json
import tempfile
import unittest
from pathlib import Path

import aggregate


class AggregateValidationTests(unittest.TestCase):

    def test_invalid_answer_is_rejected_even_when_score_file_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            runs = Path(directory)
            answer = {
                "change_id": "REST-001",
                "contestant": "agent-only",
                "findings": {
                    "contracts": [{"type": "asyncapi", "identifier": "bad"}]
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

            self.assertFalse(cells)
            self.assertEqual(len(rejected), 1)
            self.assertEqual(rejected[0]["reason"], "answer schema validation failed")


class RenderScorecardTests(unittest.TestCase):
    def test_separates_blind_spots_from_unstable_misses(self):
        def cell(missed):
            return {"change_id": "REST-001", "contestant": "agent-only",
                    "metrics": {"composite": 0.5}, "counts": {"missed_by_severity": {}},
                    "missed": [{"kind": "repository", "key": key, "severity": "contract_consumer"}
                               for key in missed]}

        summary = aggregate.summarize([cell(["always", "sometimes"]), cell(["always"])])
        text = aggregate.render_scorecard(
            {"cells": {"REST-001": {"pi": summary}}, "by_contestant": {}, "rejected": []})

        self.assertIn("composite           0.50", text)
        self.assertIn("blind spots, missed in every run:\n      always", text)
        self.assertIn("unstable, missed in some runs:\n      sometimes", text)


if __name__ == "__main__":
    unittest.main()
