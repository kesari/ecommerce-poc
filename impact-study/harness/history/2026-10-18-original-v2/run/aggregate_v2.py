#!/usr/bin/env python3
"""Reverify and summarize one v2 scenario against an explicit reviewed oracle.

Stored verdicts are checked against fresh deterministic scores. Usage and
latency are separate observations; this command never produces a composite.
"""
import argparse
import hashlib
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

from aggregate import HARNESS, product_ineligibility

sys.path.insert(0, str(HARNESS / 'scoring'))
import score_v2

RUNS = HARNESS / 'answers' / 'runs' / 'v2'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def spread(values):
    if not values:
        return {'observed': 0}
    return {'observed': len(values), 'median': statistics.median(values),
            'min': min(values), 'max': max(values)}


def receipt_bundle(answer, report, runs_dir):
    metadata = answer.get('run_receipt_bundle')
    if not isinstance(metadata, dict) or not isinstance(metadata.get('path'), str):
        raise ValueError('runner receipt bundle metadata missing')
    path = Path(metadata['path'])
    path = (path if path.is_absolute() else runs_dir / path).resolve(strict=True)
    root = (runs_dir / 'receipts').resolve(strict=True)
    if not root.is_relative_to(runs_dir) or not path.is_relative_to(root) or not path.is_file():
        raise ValueError('runner receipt bundle must be a file under runs-dir/receipts')
    pin = metadata.get('sha256')
    if not isinstance(pin, str) or digest(path) != pin or report.get('run_receipt_bundle_sha256') != pin:
        raise ValueError('runner receipt bundle hash mismatch')
    bundle = score_v2.read_json(path)
    if answer.get('product_tool_receipts') != bundle.get('receipts'):
        raise ValueError('answer product receipts differ from protected runner bundle')
    return path, pin


def cohort_identity(answer, record, oracle_pin, report):
    fields = ('model', 'model_digest', 'pi_version', 'harness_sha256', 'prompt_sha256',
              'contestant_config_sha256', 'estate_sha256', 'index_sha256')
    for field in ('model', 'harness_sha256', 'prompt_sha256', 'contestant_config_sha256',
                  'estate_sha256', 'index_sha256', 'runner', 'contestant'):
        if not isinstance(answer.get(field), str) or not answer[field]:
            raise ValueError(f'cohort identity missing: {field}')
    identity = {field: answer.get(field) for field in fields}
    identity['budget_policy'] = answer.get('budget_policy')
    identity.update(protocol_version=2, change_id=record['change_id'],
                    snapshot_sha256=record['snapshot_sha256'],
                    oracle_bundle_sha256=oracle_pin,
                    ground_truth_sha256=report['ground_truth_sha256'],
                    scorer_sha256=report['scorer_sha256'],
                    answer_schema_sha256=report['answer_schema_sha256'],
                    runner=answer['runner'], contestant=answer['contestant'],
                    treatment='product-first' if answer.get('product_playbook') else 'baseline')
    return identity


def failure_cost(stub, runs_dir):
    metadata = stub.get('evidence')
    if not isinstance(metadata, dict) or not isinstance(metadata.get('attempt_evidence'), dict):
        return None
    pointer = metadata['attempt_evidence']
    path = Path(pointer['path'])
    path = (path if path.is_absolute() else runs_dir / path).resolve(strict=True)
    if not path.is_relative_to(runs_dir) or not path.name.endswith('.attempt.json') or digest(path) != pointer['sha256']:
        raise ValueError('failed attempt evidence path or hash mismatch')
    attempt = score_v2.read_json(path)
    for field in ('protocol_version', 'change_id', 'runner'):
        if attempt.get(field) != stub.get(field):
            raise ValueError('failed attempt evidence identity mismatch: ' + field)
    if attempt.get('status') != stub.get('status') and not (
            attempt.get('status') == 'runtime_completed' and stub.get('status') in ('invalid_output', 'setup_failed', 'run_failed')):
        raise ValueError('failed attempt evidence status mismatch')
    if not isinstance(attempt.get('run_started_at'), str) or not attempt['run_started_at']:
        raise ValueError('failed attempt evidence has no start identity')
    for field in ('usage', 'exposure', 'budget_policy'):
        reported = metadata.get(field, stub.get('budget_exposure' if field == 'exposure' else field))
        if attempt.get(field) != reported:
            raise ValueError('failed attempt evidence differs from stub: ' + field)
    usage = attempt['usage']
    errors = score_v2.validate(usage, score_v2.schema('run-receipts')['properties']['usage'], 'usage')
    errors += score_v2.validate(attempt['budget_policy'], score_v2.schema('run-receipts')['properties']['budget_policy'], 'policy')
    exposure = attempt['exposure']
    upper = exposure.get('exposure_upper_bound_usd')
    if errors or not isinstance(exposure.get('unknown_usage'), bool) or isinstance(upper, bool) or not isinstance(upper, (int, float)) \
            or not math.isfinite(upper) or upper < usage['cost_usd']:
        raise ValueError('invalid failed attempt cost evidence')
    return {'runner': attempt['runner'], 'run_started_at': attempt['run_started_at'],
            'observed_cost_usd': usage['cost_usd'], 'exposure_upper_bound_usd': upper,
            'unknown_usage': exposure['unknown_usage']}


def cost_summary(facts, rejected):
    unique = {}
    for fact in facts:
        key = (fact['runner'], fact['run_started_at'])
        previous = unique.get(key, fact)
        unique[key] = {**fact,
                       'observed_cost_usd': max(previous['observed_cost_usd'], fact['observed_cost_usd']),
                       'exposure_upper_bound_usd': max(previous['exposure_upper_bound_usd'], fact['exposure_upper_bound_usd']),
                       'unknown_usage': previous['unknown_usage'] or fact['unknown_usage']}
    missing = sum(item.get('cost_evidence') is None for item in rejected)
    return {'known_attempts': len(unique),
            'observed_cost_usd': sum(item['observed_cost_usd'] for item in unique.values()),
            'exposure_upper_bound_usd': None if missing else sum(item['exposure_upper_bound_usd'] for item in unique.values()),
            'unknown_usage_attempts': sum(item['unknown_usage'] for item in unique.values()),
            'failures_without_cost_evidence': missing}


def summarize(attempts):
    metrics = {}
    for kind in attempts[0]['score']['metrics']:
        metrics[kind] = {field: spread([item['score']['metrics'][kind][field] for item in attempts])
                         for field in attempts[0]['score']['metrics'][kind]}
    critical = Counter()
    for item in attempts:
        for claim in item['score']['critical_downstream_action_misses']:
            critical[score_v2.canonical(claim)] += 1
    usage = {}
    for field in ('tokens_consumed', 'cost_usd', 'elapsed_seconds'):
        values = [item['answer'][field] for item in attempts if item['answer'].get(field) is not None]
        usage[field] = {**spread(values), 'missing': len(attempts) - len(values)}
    return {
        'runs': len(attempts), 'metrics': metrics,
        'satisfies_any_repair': sum(item['score']['satisfies_any_repair'] for item in attempts),
        'critical_downstream_action_miss_count': spread([
            len(item['score']['critical_downstream_action_misses']) for item in attempts]),
        'critical_downstream_action_misses': [
            {'claim': json.loads(claim), 'runs_missed': count} for claim, count in sorted(critical.items())],
        'efficiency': usage,
        'reports': [item['report_file'] for item in attempts],
    }


def aggregate_runs(runs_dir, record_path, oracle_bundle, trusted_oracle_sha256, artifact_root):
    runs_dir = Path(runs_dir).resolve()
    record_path = Path(record_path)
    record = score_v2.read_json(record_path)
    oracle_options = {'oracle_bundle_path': oracle_bundle, 'trusted_oracle_sha256': trusted_oracle_sha256,
                      'artifact_root': artifact_root}
    preflight = score_v2.preflight(record, **oracle_options)
    if preflight['status'] != 'verified':
        raise ValueError('oracle preflight failed: ' + '; '.join(preflight['errors']))

    expected_hashes = {
        'ground_truth_sha256': digest(record_path),
        'scorer_sha256': digest(HARNESS / 'scoring' / 'score_v2.py'),
        'answer_schema_sha256': digest(HARNESS / 'schema' / 'contestant-answer-v2.schema.json'),
        'oracle_bundle_sha256': trusted_oracle_sha256,
    }
    cohorts = defaultdict(list)
    identities = {}
    seen_receipts = set()
    seen_receipt_digests = set()
    seen_attempts = set()
    rejected = []
    excluded = []
    costs = []
    for report_file in sorted(runs_dir.glob('*.score.json')):
        runner = None
        cost = None
        try:
            stored = score_v2.read_json(report_file)
            if stored.get('change_id') != record['change_id']:
                excluded.append({'report': report_file.name, 'reason': 'different scenario'})
                continue
            answer_file = report_file.with_name(report_file.name.removesuffix('.score.json') + '.json')
            answer = score_v2.read_json(answer_file)
            runner = answer.get('runner')
            if stored.get('protocol_version') != 2 or answer.get('protocol_version') != 2:
                raise ValueError('v2 aggregation requires protocol 2 answer and report')
            if answer.get('synthetic') is True:
                raise ValueError('synthetic answer cannot enter a scorecard')
            for field, expected in {**expected_hashes, 'answer_sha256': digest(answer_file)}.items():
                if stored.get(field) != expected:
                    raise ValueError(f'stale or tampered score: {field} mismatch')
            path, pin = receipt_bundle(answer, stored, runs_dir)
            attribution = score_v2.run_receipts(record, answer, path, pin)
            if attribution['status'] == 'verified':
                cost = {'runner': runner, 'run_started_at': answer['run_started_at'],
                        'observed_cost_usd': answer['cost_usd'], 'exposure_upper_bound_usd': answer['cost_usd'],
                        'unknown_usage': False}
                costs.append(cost)
            regraded = score_v2.score_change(record, answer, **oracle_options,
                                           run_receipt_bundle_path=path, trusted_run_receipts_sha256=pin)
            if regraded.get('status') != 'scored' or not regraded.get('benchmark_eligible'):
                raise ValueError('fresh score is ineligible: ' + score_v2.canonical(regraded))
            if regraded['product_provenance']['run_receipts']['status'] != 'verified':
                raise ValueError('runner receipt envelope did not verify')
            for key, value in regraded.items():
                if stored.get(key) != value:
                    raise ValueError(f'stored verdict differs from independent recomputation: {key}')
            ineligible = product_ineligibility(answer, 'product-first')
            if ineligible:
                raise ValueError('product participation ineligible: ' + ineligible)
            if not answer.get('product_provenance') and (answer.get('product_tool_receipts') or answer.get('product_playbook')):
                raise ValueError('baseline cannot carry unattributed product receipts or playbook')
            for field in ('tokens_consumed', 'cost_usd', 'elapsed_seconds'):
                value = answer.get(field)
                if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float))
                                          or not math.isfinite(value) or value < 0):
                    raise ValueError('invalid usage telemetry: ' + field)
            for field, limit in (('tokens_consumed', 'max_tokens'), ('cost_usd', 'max_cost_usd'),
                                 ('elapsed_seconds', 'timeout_seconds')):
                if answer[field] > answer['budget_policy'][limit]:
                    raise ValueError('scored attempt exceeds budget: ' + field)
            attempt = (runner, answer['run_started_at'])
            if path in seen_receipts or pin in seen_receipt_digests or attempt in seen_attempts:
                raise ValueError("duplicate attempt: runner receipt envelope already counted")
            identity = cohort_identity(answer, record, trusted_oracle_sha256, stored)
            cohort = hashlib.sha256(score_v2.canonical(identity).encode()).hexdigest()
            seen_receipts.add(path)
            seen_receipt_digests.add(pin)
            seen_attempts.add(attempt)
            identities[cohort] = identity
            cohorts[cohort].append({'answer': answer, 'score': regraded, 'report_file': report_file.name})
        except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
            rejected.append({'report': report_file.name, 'runner': runner,
                             'status': 'verification_rejected', 'reason': str(exc), 'cost_evidence': cost})

    for stub_file in sorted(runs_dir.glob('*.rejected.json')):
        try:
            stub = score_v2.read_json(stub_file)
            if stub.get('protocol_version') != 2:
                excluded.append({'report': stub_file.name, 'reason': 'failure stub is not protocol 2'})
                continue
            if stub.get('change_id') != record['change_id']:
                excluded.append({'report': stub_file.name, 'reason': 'different scenario'})
                continue
            cost = failure_cost(stub, runs_dir)
            if cost is not None:
                costs.append(cost)
            rejected.append({'report': stub_file.name, 'runner': stub.get('runner'),
                             'status': stub.get('status', 'run_failed'), 'reason': stub.get('reason', 'run failed'),
                             'cost_evidence': cost})
        except (OSError, ValueError, TypeError, AttributeError, KeyError) as exc:
            rejected.append({'report': stub_file.name, 'runner': None, 'status': 'invalid_output', 'reason': str(exc)})
    return {'protocol_version': 2, 'change_id': record['change_id'],
            'oracle_bundle_sha256': trusted_oracle_sha256,
            'cohorts': [{'cohort_sha256': cohort, 'identity': identities[cohort], **summarize(items)}
                        for cohort, items in sorted(cohorts.items())],
            'outcomes': {'scored': sum(len(items) for items in cohorts.values()), 'rejected': len(rejected),
                         'failure_states': dict(Counter(item['status'] for item in rejected))},
            'cost_accounting': cost_summary(costs, rejected),
            'rejected': rejected, 'excluded': excluded}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record', required=True, type=Path)
    parser.add_argument('--oracle-bundle', required=True, type=Path)
    parser.add_argument('--trusted-oracle-sha256', required=True)
    parser.add_argument('--artifact-root', required=True, type=Path)
    parser.add_argument('--runs-dir', default=RUNS, type=Path)
    parser.add_argument('--json', action='store_true', help='JSON is also the default output')
    args = parser.parse_args()
    try:
        report = aggregate_runs(args.runs_dir, args.record, args.oracle_bundle,
                                args.trusted_oracle_sha256, args.artifact_root)
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(json.dumps({'protocol_version': 2, 'status': 'preflight_failed', 'reason': str(exc)}, indent=2))
        return 2
    print(json.dumps(report, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
