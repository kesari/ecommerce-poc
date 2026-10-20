"""Keep the original model-smoke verdicts reproducible after scorer repairs."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

HARNESS = Path(__file__).resolve().parent.parent
SNAPSHOT = HARNESS / 'history/2026-10-18-original-v2'
REVISION_21 = HARNESS / 'history/2026-10-19-v2.1'


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

    def test_revision_21_implementation_bytes_stay_immutable(self):
        manifest = json.loads((REVISION_21 / 'manifest.json').read_text())
        for entry in manifest['files']:
            with self.subTest(path=entry['path']):
                self.assertEqual(hashlib.sha256((REVISION_21 / entry['path']).read_bytes()).hexdigest(), entry['sha256'])

    def test_revision_21_reruns_replay_without_the_current_scorer(self):
        evidence = HARNESS / 'verification/evidence/REST-001-equivalences-v2.1'
        result = subprocess.run([
            'python3', str(REVISION_21 / 'run/aggregate_v2.py'),
            '--record', str(REVISION_21 / 'records/v2/REST-001.json'),
            '--oracle-bundle', str(evidence / 'oracle-bundle-v2.1.json'),
            '--trusted-oracle-sha256', 'd6ed60fa90d5183dc6b6582effdbfef99d4cb88f9436ae64dbccc0c015d3b317',
            '--artifact-root', str(evidence),
            '--runs-dir', str(HARNESS / 'answers/runs/v2/v2-1-repair-fix-2026-10-18'),
        ], check=True, capture_output=True, text=True)
        report = json.loads(result.stdout)
        self.assertEqual(report['outcomes'], {'scored': 2, 'rejected': 0, 'failure_states': {}})
        for cohort in report['cohorts']:
            self.assertEqual(cohort['satisfies_any_repair'], 1)
            self.assertEqual(cohort['satisfies_required_actions'], 1)
        self.assertEqual(report['cost_accounting']['unknown_usage_attempts'], 0)


if __name__ == '__main__':
    unittest.main()
