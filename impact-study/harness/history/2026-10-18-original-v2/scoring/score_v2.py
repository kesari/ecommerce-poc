#!/usr/bin/env python3
"""Version 2 exact-predicate scoring; independent proof is an optional input.

No model judge, usage floor, identifier-prefix match, or blended score. A trusted
bundle attests only its exact structured claims, never an agent's arbitrary prose.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path

from score import check

SCHEMAS = Path(__file__).resolve().parent.parent / 'schema'
FIELDS = {
    'repositories': ('repo', 'runtime_status'),
    'obligations': ('repo', 'obligation'),
    'symbols': ('repo', 'fqn'),
    'edges': ('provider', 'consumer', 'type', 'identifier', 'field'),
    'actions': ('repo', 'target', 'operation', 'before', 'after'),
}


def read_json(path):
    return json.loads(Path(path).read_text())


def schema(name):
    return read_json(SCHEMAS / f'{name}-v2.schema.json')


def validate(value, spec, context):
    errors = []
    check(value, spec, context, errors)
    def extra_constraints(node, node_spec, path):
        if isinstance(node, float) and not math.isfinite(node):
            errors.append(f'{path}: nonfinite numbers are not valid protocol values')
        elif isinstance(node, (int, float)) and not isinstance(node, bool):
            if 'exclusiveMinimum' in node_spec and node <= node_spec['exclusiveMinimum']:
                errors.append(f"{path}: must exceed {node_spec['exclusiveMinimum']}")
        elif isinstance(node, dict):
            for key, child in node.items():
                extra_constraints(child, node_spec.get('properties', {}).get(key, {}), f'{path}.{key}')
        elif isinstance(node, list):
            for index, child in enumerate(node):
                extra_constraints(child, node_spec.get('items', {}), f'{path}[{index}]')
    extra_constraints(value, spec, context)
    return errors


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def claim_key(kind, claim):
    # Keep case, punctuation and declaring-member identity significant.
    return tuple(claim[field].strip() for field in FIELDS[kind])


def claim_object(kind, key):
    return dict(zip(FIELDS[kind], key))


def metric(expected, reported, invalid_count=0):
    hits = expected & reported
    total_reported = len(reported) + invalid_count
    return {
        'expected': len(expected), 'reported': total_reported, 'correct': len(hits), 'invalid': invalid_count,
        'precision': len(hits) / total_reported if total_reported else (1.0 if not expected else 0.0),
        'recall': len(hits) / len(expected) if expected else 1.0,
    }


def differences(kind, expected, reported):
    return {
        'missed': [claim_object(kind, k) for k in sorted(expected - reported)],
        'false_positives': [claim_object(kind, k) for k in sorted(reported - expected)],
    }


def record_errors(record):
    errors = validate(record, schema('change-ground-truth'), 'record')
    if errors:
        return errors
    repos = [item['repo'].strip() for item in record['repositories']]
    if len(set(repos)) != len(repos):
        errors.append('record.repositories: duplicate repository observation')
    if record['origin_repo'].strip() not in repos:
        errors.append('record.origin_repo: missing repository observation')
    if record['proposed_change']['repo'].strip() != record['origin_repo'].strip():
        errors.append('record.proposed_change.repo: differs from origin_repo')
    plans = record['repair_alternatives']
    if not plans:
        errors.append('record.repair_alternatives: at least one complete plan required')
    names = [plan['name'].strip() for plan in plans]
    if len(set(names)) != len(names):
        errors.append('record.repair_alternatives: duplicate name')
    for plan in plans:
        obligations = plan['obligations']
        if {x['repo'].strip() for x in obligations} != set(repos) or len(obligations) != len(repos):
            errors.append(f"record.{plan['name']}: obligations must cover each repository exactly once")
        edits = {x['repo'].strip() for x in obligations if x['obligation'] == 'production_edit'}
        action_repos = {x['repo'].strip() for x in plan['actions']}
        if edits != action_repos:
            errors.append(f"record.{plan['name']}: production_edit obligations must equal action repositories")
        if len({claim_key('actions', x) for x in plan['actions']}) != len(plan['actions']):
            errors.append(f"record.{plan['name']}: duplicate action")
    for kind in ('symbols', 'edges'):
        for item in record[kind]:
            refs = (item['repo'],) if kind == 'symbols' else (item['provider'], item['consumer'])
            if any(ref.strip() not in repos for ref in refs):
                errors.append(f'record.{kind}: unknown repository')
    return errors


def split_answer(answer):
    spec = schema('contestant-answer')
    # Keep malformed findings visible without allowing one to erase valid peers.
    envelope = {**answer, 'findings': {kind: [] for kind in FIELDS}} if isinstance(answer, dict) else answer
    errors = validate(envelope, spec, 'answer')
    if isinstance(answer, dict) and 'run_started_at' in answer and 'budget_policy' not in answer:
        errors.append('runner-saved answer requires explicit budget_policy')
    valid = {kind: set() for kind in FIELDS}
    invalid = []
    payload = answer.get('findings') if isinstance(answer, dict) else None
    if not isinstance(payload, dict):
        return valid, invalid, errors + ['answer.findings: expected object']
    for unknown in sorted(set(payload) - set(FIELDS)):
        errors.append(f'answer.findings: unexpected field {unknown!r}')
    for kind in FIELDS:
        items = payload.get(kind)
        if not isinstance(items, list):
            errors.append(f'answer.findings.{kind}: expected array')
            continue
        item_spec = spec['properties']['findings']['properties'][kind]['items']
        for item in items:
            problems = validate(item, item_spec, f'findings.{kind}')
            if problems:
                invalid.append({'kind': kind, 'claim': item, 'errors': problems})
            else:
                valid[kind].add(claim_key(kind, item))
    # Stable across permutation, even when the same malformed claim is repeated.
    invalid = [json.loads(s) for s in sorted({canonical(x) for x in invalid})]
    return valid, invalid, sorted(set(errors))


def verified_claims(record, bundle_path, trusted_sha256, artifact_root):
    verified = {kind: set() for kind in FIELDS}
    if bundle_path is None:
        return verified, {'status': 'not_supplied', 'errors': []}
    errors = []
    try:
        data = Path(bundle_path).read_bytes()
        if not trusted_sha256 or hashlib.sha256(data).hexdigest() != trusted_sha256:
            raise ValueError('oracle digest does not match explicit trusted pin')
        bundle = json.loads(data)
        errors.extend(validate(bundle, schema('oracle-bundle'), 'oracle'))
        if errors:
            return verified, {'status': 'invalid', 'errors': sorted(set(errors))}
        if bundle['snapshot_sha256'] != record['snapshot_sha256']:
            errors.append('oracle snapshot differs from record snapshot')
        if bundle['change_id'] != record['change_id']:
            errors.append('oracle change_id differs from record')
        if bundle['record_sha256'] != hashlib.sha256(canonical(record).encode('utf-8')).hexdigest():
            errors.append('oracle record hash differs from full acceptance record')
        if artifact_root is None:
            errors.append('oracle artifact_root is required')
        else:
            root = Path(artifact_root).resolve(strict=True)
            if not root.is_dir():
                raise ValueError('oracle artifact_root must be a directory')
            paths = set()
            for artifact in bundle['artifacts']:
                relative = Path(artifact['path'])
                if relative.is_absolute() or '..' in relative.parts or artifact['path'] in paths:
                    errors.append('oracle artifact path is absolute, escaping, or duplicated')
                    continue
                paths.add(artifact['path'])
                target = (root / relative).resolve(strict=True)
                if not target.is_relative_to(root) or not target.is_file():
                    errors.append('oracle artifact escapes root or is not a file')
                elif hashlib.sha256(target.read_bytes()).hexdigest() != artifact['sha256']:
                    errors.append(f"oracle artifact hash mismatch: {artifact['path']}")
            for attestation in bundle['attestations']:
                kind = attestation['kind']
                # Attest only semantic fields, no permissive evidence metadata.
                item_spec = schema('contestant-answer')['properties']['findings']['properties'][kind]['items']
                semantic_spec = {**item_spec, 'properties': {k: item_spec['properties'][k] for k in FIELDS[kind]}}
                errors.extend(validate(attestation['claim'], semantic_spec, 'oracle.claim'))
                if not attestation['artifact_paths'] or not set(attestation['artifact_paths']) <= paths:
                    errors.append('oracle attestation must bind existing artifacts')
        if errors:
            return verified, {'status': 'invalid', 'errors': sorted(set(errors))}
        receipts = {}
        for receipt in bundle.get('receipts', []):
            if receipt['id'] in receipts or receipt['snapshot_sha256'] != record['snapshot_sha256']:
                raise ValueError('duplicate or cross-snapshot oracle receipt')
            if receipt['artifact_path'] not in paths:
                raise ValueError('oracle receipt is not bound to an artifact')
            body = read_json(root / receipt['artifact_path'])
            if not isinstance(body, dict) or body.get('receipt_id') != receipt['id'] or body.get('snapshot_sha256') != record['snapshot_sha256']:
                raise ValueError('oracle receipt content identity mismatch')
            receipts[receipt['id']] = receipt['artifact_path']
        for item in bundle['attestations']:
            verified[item['kind']].add(claim_key(item['kind'], item['claim']))
        return verified, {'status': 'verified', 'errors': [], 'receipt_ids': sorted(receipts)}
    except (OSError, ValueError, TypeError, KeyError) as exc:
        return verified, {'status': 'invalid', 'errors': [str(exc)]}


def run_receipts(record, answer, bundle_path, trusted_sha256):
    """Check runner attribution independently of the reviewed semantic oracle."""
    if bundle_path is None:
        return {'status': 'not_supplied', 'errors': [], 'receipt_ids': []}
    try:
        data = Path(bundle_path).read_bytes()
        if not trusted_sha256 or hashlib.sha256(data).hexdigest() != trusted_sha256:
            raise ValueError('run receipt digest does not match explicit trusted runner pin')
        bundle = json.loads(data)
        errors = validate(bundle, schema('run-receipts'), 'run_receipts')
        if errors:
            return {'status': 'invalid', 'errors': sorted(set(errors)), 'receipt_ids': []}
        for key in ('protocol_version', 'change_id', 'snapshot_sha256'):
            if bundle[key] != record[key] or bundle[key] != answer.get(key):
                errors.append(f'run receipt {key} differs from record or answer')
        for key in ('run_started_at', 'prompt_sha256', 'estate_sha256', 'product_provenance', 'product_playbook', 'budget_policy'):
            if bundle[key] != answer.get(key):
                errors.append(f'run receipt {key} differs from answer')
        for key in ('tokens_consumed', 'cost_usd', 'elapsed_seconds'):
            if bundle['usage'][key] != answer.get(key):
                errors.append(f'run receipt usage.{key} differs from answer telemetry')
        if answer.get('product_tool_receipts') is not None and answer['product_tool_receipts'] != bundle['receipts']:
            errors.append('run receipt list differs from runner-stamped answer receipts')
        ids = set()
        successful = set()
        for receipt in bundle['receipts']:
            if receipt['id'] in ids:
                errors.append('run receipts contain duplicate ID')
            ids.add(receipt['id'])
            if receipt['success']:
                if not isinstance(receipt['output'], str) or not isinstance(receipt['output_normalized'], str):
                    errors.append('successful receipt must retain output and normalized output')
                    continue
                if receipt['output_sha256'] is None or receipt['output_normalized_sha256'] is None:
                    errors.append('successful receipt must include output hashes')
                    continue
                # The raw hash covers the complete MCP result, which is not stored;
                # its format is schema-checked. The retained normalized text is rehashed.
                if hashlib.sha256(receipt['output_normalized'].encode('utf-8')).hexdigest() != receipt['output_normalized_sha256']:
                    errors.append('run receipt normalized output hash mismatch')
                if receipt['output_bytes'] != len(receipt['output'].encode('utf-8')):
                    errors.append('run receipt retained output byte count mismatch')
                if receipt['output_full_bytes'] is None or receipt['output_full_bytes'] < receipt['output_bytes']:
                    errors.append('run receipt full output byte count is invalid')
                elif not receipt['truncated'] and receipt['output_full_bytes'] != receipt['output_bytes']:
                    errors.append('untruncated receipt must retain full output')
                if receipt['error'] is not None:
                    errors.append('successful receipt cannot have an error')
                successful.add(receipt['id'])
        if errors:
            return {'status': 'invalid', 'errors': sorted(set(errors)), 'receipt_ids': []}
        return {'status': 'verified', 'errors': [], 'receipt_ids': sorted(successful)}
    except (OSError, ValueError, TypeError, KeyError) as exc:
        return {'status': 'invalid', 'errors': [str(exc)], 'receipt_ids': []}


def score_change(record, answer, *, oracle_bundle_path=None, trusted_oracle_sha256=None, artifact_root=None,
                 run_receipt_bundle_path=None, trusted_run_receipts_sha256=None):
    errors = record_errors(record)
    if errors:
        return {'protocol_version': 2, 'status': 'invalid_record', 'errors': sorted(set(errors))}
    reported, invalid, errors = split_answer(answer)
    if isinstance(answer, dict):
        if answer.get('synthetic') is True:
            errors.append('synthetic answers cannot be scored as real agent observations')
        if answer.get('change_id') != record['change_id']:
            errors.append('answer.change_id differs from record')
        if answer.get('snapshot_sha256') != record['snapshot_sha256']:
            errors.append('answer.snapshot_sha256 differs from record')
    if errors:
        return {'protocol_version': 2, 'status': 'invalid_answer', 'errors': sorted(set(errors)), 'invalid_findings': invalid}
    invalid_counts = {kind: sum(x['kind'] == kind for x in invalid) for kind in FIELDS}
    expected = {kind: {claim_key(kind, x) for x in record[kind]} for kind in ('repositories', 'symbols', 'edges')}
    plans = []
    for plan in record['repair_alternatives']:
        actions = {claim_key('actions', x) for x in plan['actions']}
        obligations = {claim_key('obligations', x) for x in plan['obligations']}
        action_metrics = metric(actions, reported['actions'], invalid_counts['actions'])
        obligation_metrics = metric(obligations, reported['obligations'], invalid_counts['obligations'])
        missed = [claim_object('actions', claim_key('actions', x)) for x in plan['actions']
                  if x.get('critical', False) and x['repo'].strip() != record['origin_repo'].strip()
                  and claim_key('actions', x) not in reported['actions']]
        plans.append({'name': plan['name'].strip(), 'actions': action_metrics,
                      'obligations': obligation_metrics,
                      'complete': actions == reported['actions'] and obligations == reported['obligations'],
                      'critical_downstream_action_misses': sorted(missed, key=canonical),
                      'action_differences': differences('actions', actions, reported['actions']),
                      'obligation_differences': differences('obligations', obligations, reported['obligations'])})
    def rank(plan):
        m = plan['actions']
        f1 = 2 * m['precision'] * m['recall'] / (m['precision'] + m['recall']) if m['precision'] + m['recall'] else 0
        return (-int(plan['complete']), -f1, -plan['obligations']['correct'], plan['name'])
    plans.sort(key=rank)
    selected = plans[0]
    chosen = next(x for x in record['repair_alternatives'] if x['name'].strip() == selected['name'])
    expected.update({kind: {claim_key(kind, x) for x in chosen[kind]} for kind in ('actions', 'obligations')})
    verified, oracle = verified_claims(record, oracle_bundle_path, trusted_oracle_sha256, artifact_root)
    invocation_bundle = run_receipts(record, answer, run_receipt_bundle_path, trusted_run_receipts_sha256)
    receipt_refs = {item['receipt_id'] for kind in FIELDS for item in answer['findings'][kind]
                    if isinstance(item, dict) and isinstance(item.get('receipt_id'), str)}
    # Static oracle receipts are a legacy test-only fallback; live invocations
    # belong to the separately trusted runner envelope, never the reviewed oracle.
    receipt_ids = invocation_bundle['receipt_ids'] if run_receipt_bundle_path is not None else oracle.get('receipt_ids', [])
    bad_refs = sorted(receipt_refs - set(receipt_ids))
    provenance = {'status': 'invalid' if bad_refs or invocation_bundle['status'] == 'invalid'
                  else ('verified' if receipt_refs else 'not_supplied'),
                  'unverified_receipt_ids': bad_refs, 'run_receipts': invocation_bundle}
    metrics = {kind: metric(expected[kind], reported[kind], invalid_counts[kind]) for kind in FIELDS}
    origin = record['origin_repo'].strip()
    metrics['downstream_repositories'] = metric({k for k in expected['repositories'] if k[0] != origin},
                                                {k for k in reported['repositories'] if k[0] != origin})
    metrics['origin_repository'] = metric({k for k in expected['repositories'] if k[0] == origin},
                                          {k for k in reported['repositories'] if k[0] == origin})
    metrics['affected_downstream_repositories'] = metric(
        {k for k in expected['repositories'] if k[0] != origin and k[1] == 'broken'},
        {k for k in reported['repositories'] if k[0] != origin and k[1] == 'broken'})
    for obligation in ('production_edit', 'validation_only', 'unaffected', 'unresolved'):
        metrics[f'{obligation}_obligations'] = metric(
            {k for k in expected['obligations'] if k[1] == obligation},
            {k for k in reported['obligations'] if k[1] == obligation})
    proof = {}
    for kind in FIELDS:
        established = reported[kind] & expected[kind] & verified[kind]
        proof[kind] = {'reported': len(reported[kind]) + invalid_counts[kind], 'invalid': invalid_counts[kind], 'verified_correct': len(established),
                       'unverified': [claim_object(kind, k) for k in sorted(reported[kind] - established)]}
    grounded = all(expected[kind] <= verified[kind] for kind in FIELDS)
    return {'protocol_version': 2, 'change_id': record['change_id'], 'status': 'scored',
            'record_status': record['status'], 'benchmark_eligible': record['status'] == 'verified' and not invalid
                and oracle['status'] == 'verified' and grounded and provenance['status'] != 'invalid'
                and ('run_started_at' not in answer or invocation_bundle['status'] == 'verified'),
            'metrics': metrics, 'invalid_findings': invalid,
            'differences': {kind: differences(kind, expected[kind], reported[kind]) for kind in FIELDS},
            'repair_alternatives': plans, 'selected_repair': selected['name'],
            'satisfies_any_repair': selected['complete'] and not invalid,
            'critical_downstream_action_misses': selected['critical_downstream_action_misses'],
            'oracle': oracle, 'oracle_covers_expectations': grounded, 'product_provenance': provenance, 'evidence': proof}


def preflight(record, *, oracle_bundle_path, trusted_oracle_sha256, artifact_root):
    errors = record_errors(record)
    if errors:
        return {'protocol_version': 2, 'status': 'invalid_record', 'errors': sorted(set(errors))}
    verified, oracle = verified_claims(record, oracle_bundle_path, trusted_oracle_sha256, artifact_root)
    if record['status'] != 'verified':
        errors.append('record status must be verified before model runs')
    if oracle['status'] != 'verified':
        errors.extend(oracle['errors'] or ['independent oracle is required'])
    common = all({claim_key(kind, x) for x in record[kind]} <= verified[kind]
                 for kind in ('repositories', 'symbols', 'edges'))
    covered = sorted(plan['name'].strip() for plan in record['repair_alternatives']
                     if common and all({claim_key(kind, x) for x in plan[kind]} <= verified[kind]
                                       for kind in ('actions', 'obligations')))
    if not covered:
        errors.append('oracle must cover common expectations and at least one complete repair alternative')
    return {'protocol_version': 2, 'change_id': record['change_id'],
            'status': 'verified' if not errors else 'unverified', 'errors': sorted(set(errors)),
            'covered_repair_alternatives': covered, 'oracle': oracle}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    validate_parser = sub.add_parser('validate')
    validate_parser.add_argument('path', nargs='?', type=Path)
    validate_parser.add_argument('--kind', choices=('record', 'answer'), default='record')
    validate_parser.add_argument('--record', type=Path)
    score_parser = sub.add_parser('score')
    score_parser.add_argument('--record', required=True, type=Path)
    score_parser.add_argument('--answer', required=True, type=Path)
    score_parser.add_argument('--oracle-bundle', type=Path)
    score_parser.add_argument('--trusted-oracle-sha256')
    score_parser.add_argument('--artifact-root', type=Path)
    score_parser.add_argument('--run-receipts', type=Path)
    score_parser.add_argument('--trusted-run-receipts-sha256')
    preflight_parser = sub.add_parser('preflight')
    preflight_parser.add_argument('--record', required=True, type=Path)
    preflight_parser.add_argument('--oracle-bundle', required=True, type=Path)
    preflight_parser.add_argument('--trusted-oracle-sha256', required=True)
    preflight_parser.add_argument('--artifact-root', required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'validate':
            if not (args.record or args.path) or (args.record and args.path):
                parser.error('validate requires one path or --record PATH')
            value = read_json(args.record or args.path)
            if args.kind == 'record':
                errors = record_errors(value)
                invalid = []
            else:
                _, invalid, errors = split_answer(value)
                errors += [error for claim in invalid for error in claim['errors']]
            report = {'protocol_version': 2, 'status': f'invalid_{args.kind}' if errors else 'valid',
                      'errors': sorted(set(errors)), 'invalid_findings': invalid}
        elif args.command == 'preflight':
            report = preflight(read_json(args.record), oracle_bundle_path=args.oracle_bundle,
                               trusted_oracle_sha256=args.trusted_oracle_sha256, artifact_root=args.artifact_root)
        else:
            report = score_change(read_json(args.record), read_json(args.answer), oracle_bundle_path=args.oracle_bundle,
                                  trusted_oracle_sha256=args.trusted_oracle_sha256, artifact_root=args.artifact_root,
                                  run_receipt_bundle_path=args.run_receipts,
                                  trusted_run_receipts_sha256=args.trusted_run_receipts_sha256)
    except (OSError, ValueError) as exc:
        report = {'protocol_version': 2, 'status': 'input_error', 'errors': [str(exc)]}
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['status'] in ('valid', 'scored', 'verified') else 2


if __name__ == '__main__':
    raise SystemExit(main())
