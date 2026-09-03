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


if __name__ == "__main__":
    unittest.main()
