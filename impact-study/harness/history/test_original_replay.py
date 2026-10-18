"""Keep the original model-smoke verdicts reproducible after scorer repairs."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

HARNESS = Path(__file__).resolve().parent.parent
SNAPSHOT = HARNESS / 'history/2026-10-18-original-v2'


class OriginalReplayTest(unittest.TestCase):
    def test_original_implementation_bytes_stay_immutable(self):
        manifest = json.loads((SNAPSHOT / 'manifest.json').read_text())
        for entry in manifest['files']:
            with self.subTest(path=entry['path']):
                self.assertEqual(hashlib.sha256((SNAPSHOT / entry['path']).read_bytes()).hexdigest(), entry['sha256'])

    def test_original_scores_replay_without_the_current_scorer(self):
        result = subprocess.run([
            'python3', str(SNAPSHOT / 'run/aggregate_v2.py'),
            '--record', str(SNAPSHOT / 'records/v2/REST-001.json'),
            '--oracle-bundle', str(HARNESS / 'verification/evidence/REST-001/oracle-bundle.json'),
            '--trusted-oracle-sha256', '33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6',
            '--artifact-root', str(HARNESS / 'verification/evidence/REST-001'),
            '--runs-dir', str(HARNESS / 'answers/runs/v2'),
        ], check=True, capture_output=True, text=True)
        report = json.loads(result.stdout)
        self.assertEqual(report['outcomes'], {'scored': 2, 'rejected': 1, 'failure_states': {'budget_exhausted': 1}})
        for cohort in report['cohorts']:
            self.assertEqual(cohort['metrics']['actions']['recall']['median'], 0)
        self.assertEqual(report['cost_accounting']['unknown_usage_attempts'], 1)


if __name__ == '__main__':
    unittest.main()
