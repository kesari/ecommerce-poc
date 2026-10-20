#!/usr/bin/env python3
"""Offline paired discovery analysis. No provider or product calls are made.

The caller supplies independently trusted digests for the frozen inputs and the
out-of-band execution ledger. Answer-authored paths or identities never assign a
scheduled slot. Historical single-case aggregators remain unchanged.
"""
import argparse
import hashlib
import json
import math
import random
import re
import statistics
from collections import Counter, defaultdict
from pathlib import Path

import aggregate_v2 as legacy

score = legacy.score_v2
REVISION = '2.2'
FORMAT_VERSION = 1
HASH_FIELDS = ('harness_sha256', 'estate_sha256', 'scorer_sha256',
               'answer_schema_sha256', 'task_template_sha256')
MEASUREMENT_FIELDS = ('model', 'model_digest', 'pi_version', 'harness_sha256',
                      'estate_sha256', 'thinking')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def is_hash(value):
    return isinstance(value, str) and len(value) == 64 and all(c in '0123456789abcdef' for c in value)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def trusted_json(path, pin):
    require(not Path(path).is_symlink(), 'trusted input must not be a symlink')
    require(is_hash(pin) and digest(path) == pin, 'trusted input digest mismatch: ' + str(path))
    return json.loads(Path(path).read_text())


def artifact_path(root, relative, *, directory=False):
    require(isinstance(relative, str) and relative, 'artifact path missing')
    rel = Path(relative)
    require(not rel.is_absolute() and '..' not in rel.parts, 'artifact path must stay relative to artifact root')
    candidate = root
    for part in rel.parts:
        candidate = candidate / part
        require(not candidate.is_symlink(), 'artifact symlinks are forbidden')
    path = candidate.resolve(strict=True)
    require(path.is_relative_to(root), 'artifact escaped root')
    require(path.is_dir() if directory else path.is_file(), 'artifact has wrong file type')
    return path


def pinned_artifact(root, pointer):
    require(isinstance(pointer, dict) and set(pointer) == {'path', 'sha256'}, 'invalid sealed artifact pointer')
    path = artifact_path(root, pointer['path'])
    require(is_hash(pointer['sha256']) and digest(path) == pointer['sha256'], 'sealed artifact digest mismatch')
    return path


def validate_inputs(cases, conditions):
    require(cases.get('campaign_version') == FORMAT_VERSION, 'unsupported case manifest')
    rows = cases.get('cases', [])
    require(len(rows) == 12 and len({r['case_id'] for r in rows}) == 12, 'twelve distinct cases required')
    require(sorted(Counter(r['family'] for r in rows).values()) == [2] * 6, 'six families with two cases each required')
    for row in rows:
        require(isinstance(row['case_id'], str) and re.fullmatch(r'[A-Z]+-?[0-9]+', row['case_id']), 'unsafe case ID')
        require(isinstance(row.get('family'), str) and row['family'], 'case family missing')
        require(isinstance(row.get('compatible_control'), bool), 'compatible control classification must be frozen')
        for key in ('record_sha256', 'oracle_bundle_sha256'):
            require(is_hash(row.get(key)), 'case hash missing: ' + key)
        for key in ('record_path', 'oracle_bundle_path', 'artifact_root'):
            require(isinstance(row.get(key), str) and row[key], 'case path missing: ' + key)
    require(conditions.get('campaign_version') == FORMAT_VERSION and conditions.get('protocol_version') == 2
            and conditions.get('evaluation_revision') == REVISION, 'requires discovery protocol 2 revision 2.2')
    require(isinstance(conditions.get('paid_runs_authorized'), bool), 'paid authorization state must be explicit')
    budget = conditions.get('campaign_budget', {})
    require(isinstance(budget.get('authorization_id'), str) and budget['authorization_id']
            and budget.get('currency') == 'USD' and finite_nonnegative(budget.get('max_total_usd')),
            'explicit campaign authorization ID and USD cap required')
    require(not conditions['paid_runs_authorized'] or budget['max_total_usd'] > 0, 'paid authorization requires a positive campaign cap')
    shared = conditions.get('shared_identity', {})
    for field in HASH_FIELDS:
        require(is_hash(shared.get(field)), 'common hash missing: ' + field)
    for field in ('model', 'pi_version', 'thinking'):
        require(isinstance(shared.get(field), str) and shared[field], 'common identity missing: ' + field)
    require('model_digest' in shared and (shared['model_digest'] is None or
            isinstance(shared['model_digest'], str) and bool(shared['model_digest'])), 'model digest must be explicit, null if unavailable')
    require(sum(r['compatible_control'] for r in rows) >= 2, 'at least two compatible controls required')
    require(not score.validate(shared.get('budget_policy'), score.schema('run-receipts')['properties']['budget_policy'], 'policy'), 'invalid common budget policy')
    require(all(finite_nonnegative(shared['budget_policy'].get(k)) and shared['budget_policy'][k] > 0
                for k in ('timeout_seconds', 'max_tokens', 'max_cost_usd')), 'common budget limits must be positive and finite')
    require(isinstance(shared.get('scoring_policy'), str) and shared['scoring_policy'], 'common scoring policy mode missing')
    arms = conditions.get('arms', {})
    require(len(arms) == 4, 'four distinct arms required')
    require(Counter(a.get('treatment') for a in arms.values()) == {'baseline': 1, 'product-first': 3}, 'one baseline and three treatments required')
    for name, arm in arms.items():
        require(isinstance(name, str) and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9-]{0,95}', name), 'invalid arm name')
        require(isinstance(arm.get('contestant'), str) and arm['contestant'], 'contestant label missing')
        require(is_hash(arm.get('contestant_config_sha256')), 'arm config hash missing')
        require(is_hash(arm.get('index_sha256')) or arm.get('index_sha256') == 'none', 'arm index hash missing')
        require((arm['index_sha256'] == 'none') == (arm['treatment'] == 'baseline'), 'baseline needs no index and products require a frozen index')
        require(set(arm.get('prompt_sha256_by_case', {})) == {r['case_id'] for r in rows}, 'arm task prompts must cover every case')
        require(all(is_hash(v) for v in arm['prompt_sha256_by_case'].values()), 'invalid task prompt hash')
    public_prompts = next(iter(arms.values()))['prompt_sha256_by_case']
    require(all(arm['prompt_sha256_by_case'] == public_prompts for arm in arms.values()),
            'all four arms must receive identical public task prompts for each case')
    policy = conditions.get('analysis', {})
    require(type(policy.get('bootstrap_seed')) is int and type(policy.get('bootstrap_samples')) is int
            and policy['bootstrap_samples'] >= 100, 'frozen bootstrap seed/sample count required')
    require(policy.get('decision_gain') == 0.10, 'practical decision threshold must be 0.10')


def build_schedule(cases, conditions, *, cases_manifest_sha256, condition_sha256, seed):
    """Deterministic 144-cell plan, shuffled case order and rotated arm order."""
    validate_inputs(cases, conditions)
    require(is_hash(cases_manifest_sha256) and is_hash(condition_sha256), 'schedule input pins required')
    require(type(seed) is int, 'integer schedule seed required')
    arms = sorted(conditions['arms'])
    case_ordinals = {case_id: index for index, case_id in enumerate(sorted(row['case_id'] for row in cases['cases']))}
    rng = random.Random(seed)
    cells = []
    for repeat in range(1, 4):
        ordered = sorted(row['case_id'] for row in cases['cases'])
        rng.shuffle(ordered)
        for case_id in ordered:
            offset = (case_ordinals[case_id] + repeat - 1) % len(arms)
            for arm in arms[offset:] + arms[:offset]:
                cells.append({'slot_id': f'{case_id}/r{repeat}/{arm}', 'case_id': case_id,
                              'repeat': repeat, 'arm': arm, 'ordinal': len(cells)})
    return {'campaign_version': FORMAT_VERSION, 'protocol_version': 2, 'evaluation_revision': REVISION,
            'condition_sha256': condition_sha256, 'cases_manifest_sha256': cases_manifest_sha256,
            'seed': seed, 'repetitions': 3, 'arms': arms, 'cells': cells}


def expected_identity(cell, schedule_pin, condition_pin, conditions):
    shared, arm = conditions['shared_identity'], conditions['arms'][cell['arm']]
    return {**{field: shared[field] for field in MEASUREMENT_FIELDS},
            'runner': cell['arm'], 'contestant': arm['contestant'], 'run_index': cell['repeat'],
            'contestant_config_sha256': arm['contestant_config_sha256'], 'index_sha256': arm['index_sha256'],
            'task_prompt_sha256': arm['prompt_sha256_by_case'][cell['case_id']],
            'campaign_slot': {'schedule_sha256': schedule_pin, 'condition_sha256': condition_pin,
                              'case_id': cell['case_id'], 'repeat': cell['repeat'], 'arm': cell['arm']}}


def seal_execution_ledger(schedule_pin, condition_pin, recorder_entries, artifact_root, *, campaign_budget_ledger=None):
    """Hash already trusted recorder facts; never infer slot/identity from an answer.

    The caller must review/seal the returned ledger out of band and separately
    pass its digest to analysis. This function creates no runs or authorization.
    """
    root = Path(artifact_root).resolve(strict=True)
    require(is_hash(schedule_pin) and is_hash(condition_pin), 'ledger input pins required')
    entries = []
    for raw in recorder_entries:
        entry = json.loads(canonical(raw))
        require(isinstance(entry.get('measurement_identity'), dict) and entry.get('slot_id'), 'trusted recorder slot/identity required')
        for kind in ('answer', 'score', 'receipts', 'failure', 'attempt'):
            if kind in entry:
                path = artifact_path(root, entry[kind]['path'])
                entry[kind] = {'path': entry[kind]['path'], 'sha256': digest(path)}
        entries.append(entry)
    result = {'campaign_version': FORMAT_VERSION, 'schedule_sha256': schedule_pin,
              'condition_sha256': condition_pin, 'entries': entries}
    require(not entries or campaign_budget_ledger is not None, 'attempt entries require an independently sealed campaign budget ledger')
    if campaign_budget_ledger is not None:
        path = artifact_path(root, campaign_budget_ledger['path'])
        result['campaign_budget_ledger'] = {'path': campaign_budget_ledger['path'], 'sha256': digest(path)}
    return result


def money_nanos(value):
    require(finite_nonnegative(value) and math.ceil(value * 1e9) <= 2 ** 53 - 1, 'unsupported USD value')
    return math.ceil(value * 1e9)


def campaign_budget_summary(ledger, entries, cells, conditions, schedule_pin, condition_pin, root):
    """Independently verify the durable global authorization ledger, including crashes."""
    pointer = ledger.get('campaign_budget_ledger')
    require(not entries or pointer is not None, 'attempt entries require an independently sealed campaign budget ledger')
    if pointer is None:
        return {'status': 'not_supplied', 'exposure_upper_bound_usd': None, 'decision_blockers': ['global_budget_evidence_missing'],
                'note': 'No sealed global ledger supplied; missing cells do not establish zero exposure.'}
    budget = score.read_json(pinned_artifact(root, pointer))
    authorization = conditions['campaign_budget']
    require(budget.get('ledger_version') == 1 and all(budget.get(k) == authorization[k] for k in ('authorization_id', 'currency', 'max_total_usd')), 'budget ledger authorization or cap mismatch')
    require(isinstance(budget.get('reservations'), list), 'global budget reservations must be a list')
    cap = math.floor(authorization['max_total_usd'] * 1e9)
    money_nanos(authorization['max_total_usd'])
    rows = {}; debit = pending = observed = 0
    unknown = []; current_rows = []; attempt_breaches = []
    for row in budget['reservations']:
        require(isinstance(row, dict), 'budget reservation must be an object')
        reservation_id = row.get('reservation_id')
        require(isinstance(reservation_id, str) and reservation_id and reservation_id not in rows, 'duplicate or missing budget reservation ID')
        require(row.get('authorization_id') == authorization['authorization_id'], 'reservation authorization mismatch')
        require(isinstance(row.get('ledger_path'), str) and Path(row['ledger_path']).is_absolute(), 'reservation ledger origin path missing')
        require(isinstance(row.get('run_started_at'), str) and row['run_started_at'], 'budget reservation start identity missing')
        reserved = money_nanos(row.get('reserved_usd'));require(reserved > 0, 'budget reservation must be positive')
        slot = row.get('campaign_slot', {})
        require(is_hash(slot.get('schedule_sha256')) and is_hash(slot.get('condition_sha256'))
                and type(slot.get('repeat')) is int and 1 <= slot['repeat'] <= 3
                and isinstance(slot.get('case_id'), str) and isinstance(slot.get('arm'), str), 'budget reservation slot missing')
        if slot['schedule_sha256'] == schedule_pin:
            require(slot['condition_sha256'] == condition_pin and slot['arm'] in conditions['arms'], 'current budget reservation conditions mismatch')
            require(reserved == money_nanos(conditions['shared_identity']['budget_policy']['max_cost_usd']), 'current reservation is not the full attempt cap')
            current_rows.append(row)
        if row.get('status') == 'reserved':
            debit += reserved;pending += reserved
        else:
            require(row.get('status') == 'settled' and isinstance(row.get('unknown_usage'), bool), 'invalid budget reservation status')
            spent = money_nanos(row.get('observed_cost_usd'));upper = money_nanos(row.get('exposure_upper_bound_usd'))
            require(upper >= spent and isinstance(row.get('settled_at'), str) and row['settled_at'], 'invalid budget settlement')
            debit += upper;observed += spent
            if row['unknown_usage'] or upper > spent:
                unknown.append(reservation_id)
            if upper > reserved:
                attempt_breaches.append(reservation_id)
        rows[reservation_id] = row
    require(debit <= 2 ** 53 - 1, 'global budget total exceeds supported precision')
    totals = {'observed_cost_usd': observed / 1e9, 'pending_reserved_usd': pending / 1e9,
              'debit_upper_bound_usd': debit / 1e9, 'remaining_usd': max(0, cap - debit) / 1e9, 'over_budget': debit > cap}
    require(budget.get('totals') == totals, 'global budget totals differ from independent recomputation')
    by_slot = {c['slot_id']: c for c in cells}
    linked = set()
    for slot_id, entry in entries.items():
        reservation_id = entry.get('campaign_budget_reservation_id')
        require(reservation_id in rows and reservation_id not in linked, 'attempt has no unique sealed budget reservation')
        row = rows[reservation_id];linked.add(reservation_id)
        require(row['campaign_slot'] == entry['measurement_identity']['campaign_slot'] and row['run_started_at'] == entry['run_started_at'], 'attempt budget reservation identity mismatch')
        fact = by_slot[slot_id].get('cost')
        if fact and row['status'] == 'settled':
            require(money_nanos(fact['observed_cost_usd']) == money_nanos(row['observed_cost_usd'])
                    and money_nanos(fact['exposure_upper_bound_usd']) <= money_nanos(row['exposure_upper_bound_usd'])
                    and (not fact['unknown_usage'] or row['unknown_usage']), 'runtime spend or exposure differs from sealed budget settlement')
    unrecorded = [r['reservation_id'] for r in current_rows if r['reservation_id'] not in linked and
                  (r['status'] == 'reserved' or r.get('unknown_usage') or money_nanos(r.get('exposure_upper_bound_usd', 0)) > 0)]
    current_spent = sum(money_nanos(r.get('observed_cost_usd', 0)) for r in current_rows)
    blockers = []
    if pending: blockers.append('pending_or_crashed_reservations')
    if unknown: blockers.append('unsettled_provider_exposure')
    if unrecorded: blockers.append('unrecorded_attempt_exposure')
    if debit > cap or attempt_breaches: blockers.append('cost_cap_violation')
    if not conditions['paid_runs_authorized'] and current_spent: blockers.append('paid_cost_without_authorization')
    return {'status': 'blocked' if blockers else 'verified', 'authorization_id': authorization['authorization_id'],
            'totals': totals, 'exposure_upper_bound_usd': debit / 1e9, 'reservations': len(rows),
            'unknown_usage_reservations': unknown, 'unrecorded_reservations': unrecorded,
            'attempt_cap_breaches': attempt_breaches, 'decision_blockers': blockers}


def quantile(values, probability):
    ordered = sorted(values)
    location = (len(ordered) - 1) * probability
    low = math.floor(location)
    return ordered[low] + (ordered[min(low + 1, len(ordered) - 1)] - ordered[low]) * (location - low)


def clustered_interval(groups, seed, samples):
    """Resample intact cases or families, never individual repetitions."""
    if len(groups) < 2:
        return None
    rng = random.Random(seed)
    estimates = [statistics.mean(rng.choice(groups) for _ in groups) for _ in range(samples)]
    return {'low': quantile(estimates, .025), 'high': quantile(estimates, .975),
            'level': .95, 'clusters': len(groups), 'samples': samples, 'seed': seed,
            'interpretation': 'exploratory percentile interval; three comparisons, no multiplicity-adjusted significance claim'}


def finite_nonnegative(value):
    return not isinstance(value, bool) and isinstance(value, (float, int)) and math.isfinite(value) and value >= 0


def unnecessary_edits(report):
    """Count only edits disproved by a proved no-edit obligation, not every error."""
    return [c for c in report.get('contradictions', [])
            if c.get('kind') == 'actions' and c.get('reason') == 'production_action_in_established_no_edit_repository'
            or c.get('kind') == 'obligations' and c.get('claim', {}).get('obligation') == 'production_edit'
            and c.get('reason') == 'contradicts_established_in_scope_observation']


def native_counts(receipts):
    if not isinstance(receipts, list):
        return None
    return {'attempted': len(receipts), 'failed': sum(r.get('success') is False for r in receipts if isinstance(r, dict))}


def sealed_completed_cost(entry, cell, identity, root, conditions):
    """Retain trusted runtime cost even when an answer or stored score is rejected."""
    require(entry.get('status') == 'completed', 'not a completed envelope')
    envelope = score.read_json(pinned_artifact(root, entry['receipts']))
    require(envelope.get('measurement_identity') == identity and envelope.get('change_id') == cell['case_id']
            and envelope.get('protocol_version') == 2 and envelope.get('run_started_at') == entry['run_started_at'], 'untrusted cost identity')
    require(envelope.get('budget_policy') == conditions['shared_identity']['budget_policy'], 'untrusted cost policy')
    usage = envelope.get('usage', {})
    require(all(finite_nonnegative(usage.get(k)) for k in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')), 'untrusted cost usage')
    return {'runner': cell['arm'], 'run_started_at': entry['run_started_at'], 'observed_cost_usd': usage['cost_usd'],
            'exposure_upper_bound_usd': usage['cost_usd'], 'unknown_usage': False}


def verify_entry(entry, cell, cases, conditions, schedule_pin, condition_pin, root):
    identity = expected_identity(cell, schedule_pin, condition_pin, conditions)
    require(entry.get('measurement_identity') == identity, 'sealed measurement identity differs from frozen slot')
    require(isinstance(entry.get('run_started_at'), str) and entry['run_started_at'], 'sealed start identity missing')
    row = cases[cell['case_id']]
    common = conditions['shared_identity']
    if entry.get('status') == 'failed':
        stub_path = pinned_artifact(root, entry['failure'])
        attempt_path = pinned_artifact(root, entry['attempt'])
        stub, attempt = score.read_json(stub_path), score.read_json(attempt_path)
        for value in (stub, attempt):
            for key, expected in (('protocol_version', 2), ('change_id', cell['case_id']),
                                  ('runner', cell['arm']), ('run_started_at', entry['run_started_at'])):
                require(value.get(key) == expected, 'failed attempt identity mismatch: ' + key)
        require(attempt.get('measurement_identity') == identity and stub.get('measurement_identity') == identity, 'failed attempt envelope identity mismatch')
        require(stub.get('status') in ('setup_failed', 'run_failed', 'invalid_output', 'budget_exhausted', 'provider_failed', 'budget_exceeded', 'timeout'), 'unknown failure state')
        # Existing helper checks schemas, exposure bounds and stub/attempt agreement.
        pointer = stub.get('evidence', {}).get('attempt_evidence', {})
        require(isinstance(pointer.get('path'), str), 'failure stub attempt pointer missing')
        declared = Path(pointer['path'])
        declared = (declared if declared.is_absolute() else stub_path.parent / declared).resolve(strict=True)
        require(declared == attempt_path and pointer.get('sha256') == entry['attempt']['sha256'], 'failure pointer differs from sealed attempt')
        normalized_stub = json.loads(canonical(stub))
        normalized_stub['evidence']['attempt_evidence']['path'] = str(attempt_path)
        fact = legacy.failure_cost(normalized_stub, root)
        require(fact is not None, 'failure has no trusted usage/exposure evidence')
        require(all(finite_nonnegative(attempt['usage'].get(k)) for k in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')), 'invalid failure usage')
        require(attempt.get('budget_policy') == common['budget_policy'], 'failure budget policy drift')
        return {'state': 'observed_failure', 'failure_state': stub['status'], 'complete_supported_impact': False,
                'run_started_at': entry['run_started_at'], 'cost': fact, 'valid_answer': False,
                'unnecessary_production_edits': None, 'efficiency': attempt['usage'],
                'native_operations': native_counts(attempt.get('productReceipts'))}
    require(entry.get('status') == 'completed', 'unknown sealed entry status')
    answer_path, stored_path, receipt_path = [pinned_artifact(root, entry[k]) for k in ('answer', 'score', 'receipts')]
    answer, stored, envelope = [score.read_json(p) for p in (answer_path, stored_path, receipt_path)]
    require(answer.get('measurement_identity') == identity and envelope.get('measurement_identity') == identity, 'answer/receipt measurement identity mismatch')
    for key, expected in identity.items():
        if key == 'campaign_slot':
            continue  # The complete slot is already bound in the nested protected identity.
        require(answer.get(key) == expected, 'answer measurement stamp mismatch: ' + key)
    require(answer.get('run_started_at') == entry['run_started_at'], 'answer start differs from sealed identity')
    require(answer.get('protocol_version') == 2 and answer.get('change_id') == cell['case_id'] and not answer.get('synthetic'), 'incompatible or synthetic answer')
    require(answer.get('budget_policy') == common['budget_policy'], 'answer budget policy drift')
    pointer = answer.get('run_receipt_bundle', {})
    require(pointer.get('sha256') == entry['receipts']['sha256'], 'answer receipt pointer differs from sealed envelope')
    require(isinstance(pointer.get('path'), str), 'answer receipt path missing')
    declared = Path(pointer['path'])
    declared = (declared if declared.is_absolute() else answer_path.parent / declared).resolve(strict=True)
    require(declared == receipt_path, 'answer receipt path differs from sealed envelope')
    hashes = {'ground_truth_sha256': row['record_sha256'], 'scorer_sha256': common['scorer_sha256'],
              'answer_schema_sha256': common['answer_schema_sha256'], 'oracle_bundle_sha256': row['oracle_bundle_sha256'],
              'answer_sha256': entry['answer']['sha256'], 'run_receipt_bundle_sha256': entry['receipts']['sha256']}
    for key, value in hashes.items():
        require(stored.get(key) == value, 'stored score hash mismatch: ' + key)
    record_path = artifact_path(root, row['record_path'])
    record = score.read_json(record_path)
    fresh = score.score_change(record, answer, oracle_bundle_path=artifact_path(root, row['oracle_bundle_path']),
                               trusted_oracle_sha256=row['oracle_bundle_sha256'], artifact_root=artifact_path(root, row['artifact_root'], directory=True),
                               run_receipt_bundle_path=receipt_path, trusted_run_receipts_sha256=entry['receipts']['sha256'])
    require(fresh.get('status') == 'scored' and fresh.get('benchmark_eligible') is True, 'fresh score is not eligible')
    actual_policy = fresh.get('scoring_policy')
    actual_policy = actual_policy.get('mode') if isinstance(actual_policy, dict) else actual_policy
    require(fresh.get('evaluation_revision') == REVISION and actual_policy == common['scoring_policy'], 'scoring revision or policy drift')
    require(type(fresh.get('complete_supported_impact')) is bool, 'revision 2.2 complete_supported_impact boolean missing')
    require(fresh.get('product_provenance', {}).get('run_receipts', {}).get('status') == 'verified', 'runner envelope did not independently verify')
    for key, value in fresh.items():
        require(stored.get(key) == value, 'stored verdict differs from fresh regrade: ' + key)
    arm = conditions['arms'][cell['arm']]
    if arm['treatment'] == 'product-first':
        require(not legacy.product_ineligibility(answer, 'product-first'), 'product participation is ineligible')
    else:
        require(not answer.get('product_provenance') and not answer.get('product_tool_receipts') and not answer.get('product_playbook'), 'baseline carries product treatment')
    for field, limit in (('tokens_consumed', 'max_tokens'), ('cost_usd', 'max_cost_usd'), ('elapsed_seconds', 'timeout_seconds')):
        require(finite_nonnegative(answer.get(field)) and answer[field] <= common['budget_policy'][limit], 'invalid or over-budget telemetry: ' + field)
    unnecessary = unnecessary_edits(fresh)
    return {'state': 'scored_valid', 'complete_supported_impact': fresh['complete_supported_impact'],
            'valid_answer': True, 'run_started_at': entry['run_started_at'], 'metrics': fresh.get('metrics', {}),
            'critical_downstream_action_misses': fresh.get('critical_downstream_action_misses', []),
            'complete_supported_impact_details': fresh.get('complete_supported_impact_details'),
            'contradictions': fresh.get('contradictions', []),
            'unnecessary_production_edits': unnecessary,
            'native_operations': native_counts(envelope.get('receipts')),
            'cost': {'runner': cell['arm'], 'run_started_at': entry['run_started_at'],
                     'observed_cost_usd': answer['cost_usd'], 'exposure_upper_bound_usd': answer['cost_usd'], 'unknown_usage': False},
            'efficiency': {f: answer[f] for f in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')}}


def summarize_cells(cells, case_rows, conditions):
    baseline = next(name for name, arm in conditions['arms'].items() if arm['treatment'] == 'baseline')
    by_slot = {(c['case_id'], c['repeat'], c['arm']): c for c in cells}
    totals = {}
    for arm in conditions['arms']:
        selected = [c for c in cells if c['arm'] == arm]
        missing = sum(c['state'] == 'missing_unrun' for c in selected)
        successes = sum(c.get('complete_supported_impact') is True for c in selected)
        attempted = len(selected) - missing
        valid = sum(c.get('valid_answer') is True for c in selected)
        valid_cells = [c for c in selected if c.get('valid_answer')]
        metric_fields = {(kind, field) for c in valid_cells for kind, values in c.get('metrics', {}).items() for field in values}
        metric_summary = {}
        for kind, field in sorted(metric_fields):
            metric_summary.setdefault(kind, {})[field] = legacy.spread([c['metrics'][kind][field] for c in valid_cells if kind in c.get('metrics', {}) and field in c['metrics'][kind]])
        controls = [c for c in valid_cells if case_rows[c['case_id']]['compatible_control']]
        totals[arm] = {'scheduled': len(selected), 'attempted': attempted, 'missing_unrun': missing,
                       'scored_valid': valid, 'observed_failures': attempted - valid, 'complete_successes': successes,
                       'scheduled_success_fraction': successes / len(selected),
                       'scheduled_success_bounds': [successes / len(selected), (successes + missing) / len(selected)],
                       'attempted_operational_success': successes / attempted if attempted else None,
                       'conditional_valid_answer_success': successes / valid if valid else None,
                       'failure_states': dict(Counter(c.get('failure_state', c['state']) for c in selected if c['state'] not in ('scored_valid', 'missing_unrun'))),
                       'metrics': metric_summary,
                       'critical_action_miss_count': legacy.spread([len(c.get('critical_downstream_action_misses', [])) for c in valid_cells]),
                       'unverified_material_claim_count': legacy.spread([len((c.get('complete_supported_impact_details') or {}).get('unverified_material_claims', [])) for c in valid_cells]),
                       'disproved_edit_claim_count': legacy.spread([len(c.get('unnecessary_production_edits') or []) for c in valid_cells]),
                       'control_valid_answers': len(controls),
                       'compatible_control_specificity_conditional_valid': sum(not c.get('unnecessary_production_edits') for c in controls) / len(controls) if controls else None,
                       'native_operations': {'attempts_with_evidence': sum(c.get('native_operations') is not None for c in selected),
                                             'attempted': sum((c.get('native_operations') or {}).get('attempted', 0) for c in selected),
                                             'failed': sum((c.get('native_operations') or {}).get('failed', 0) for c in selected)},
                       'cost_accounting': legacy.cost_summary([c['cost'] for c in selected if c.get('cost')],
                                                              [{'cost_evidence': None} for c in selected if c['state'] != 'missing_unrun' and not c.get('cost')]),
                       'efficiency': {field: legacy.spread([c['efficiency'][field] for c in selected if 'efficiency' in c])
                                      for field in ('tokens_consumed', 'cost_usd', 'elapsed_seconds')}}
    complete_campaign = all(c['state'] in ('scored_valid', 'observed_failure') for c in cells)
    comparisons = {}
    policy = conditions['analysis']
    for arm in conditions['arms']:
        if arm == baseline:
            continue
        case_results = []
        control_regressions = []
        control_valid_pairs = 0
        control_scheduled_pairs = sum(r['compatible_control'] for r in case_rows.values()) * 3
        scheduled_bounds = []
        for case_id, row in sorted(case_rows.items()):
            counts = Counter(); diffs = []
            conditional = Counter()
            bounds = []
            for repeat in range(1, 4):
                b, p = [by_slot[(case_id, repeat, a)] for a in (baseline, arm)]
                bv = None if b['state'] == 'missing_unrun' else int(b.get('complete_supported_impact') is True)
                pv = None if p['state'] == 'missing_unrun' else int(p.get('complete_supported_impact') is True)
                bounds.append(((0 if pv is None else pv) - (1 if bv is None else bv),
                               (1 if pv is None else pv) - (0 if bv is None else bv)))
                if b['state'] == 'missing_unrun' or p['state'] == 'missing_unrun':
                    counts['unknown_missing_pair'] += 1
                    continue
                bv, pv = b.get('complete_supported_impact') is True, p.get('complete_supported_impact') is True
                label = 'product_only_success' if pv and not bv else 'baseline_only_success' if bv and not pv else 'tie_success' if pv else 'tie_failure'
                counts[label] += 1; diffs.append(int(pv) - int(bv))
                if b.get('valid_answer') and p.get('valid_answer'):
                    conditional[label] += 1
                    if row['compatible_control']:
                        control_valid_pairs += 1
                if row['compatible_control'] and p.get('valid_answer') and p.get('unnecessary_production_edits') and not b.get('unnecessary_production_edits'):
                    control_regressions.append({'case_id': case_id, 'repeat': repeat})
            case_results.append({'case_id': case_id, 'family': row['family'], 'compatible_control': row['compatible_control'],
                                 'pairs': dict(counts), 'valid_answer_pairs': dict(conditional), 'observed_pairs': len(diffs),
                                 'mean_paired_difference': statistics.mean(diffs) if diffs else None,
                                 'complete_case': len(diffs) == 3})
            scheduled_bounds.append([statistics.mean(pair[side] for pair in bounds) for side in (0, 1)])
        complete_cases = [c for c in case_results if c['complete_case']]
        per_family = defaultdict(list)
        for c in complete_cases:
            per_family[c['family']].append(c['mean_paired_difference'])
        family_means = {name: statistics.mean(values) for name, values in sorted(per_family.items())}
        delta = statistics.mean(c['mean_paired_difference'] for c in complete_cases) if complete_cases else None
        interval = clustered_interval([c['mean_paired_difference'] for c in complete_cases], policy['bootstrap_seed'], policy['bootstrap_samples']) if complete_cases else None
        family_interval = clustered_interval(list(family_means.values()), policy['bootstrap_seed'], policy['bootstrap_samples']) if family_means else None
        decision = 'incomplete_campaign'
        if complete_campaign:
            if control_valid_pairs != control_scheduled_pairs:
                decision = 'control_safety_incomplete'
            else:
                decision = 'demonstrated_practical_benefit' if delta >= policy['decision_gain'] and interval and interval['low'] > 0 and not control_regressions else 'no_demonstrated_practical_benefit'
        comparisons[arm] = {'baseline': baseline, 'case_results': case_results,
                            'observed_pair_counts': dict(sum((Counter(c['pairs']) for c in case_results), Counter())),
                            'complete_case_count': len(complete_cases), 'case_equal_delta': delta,
                            'observed_case_equal_delta': statistics.mean(c['mean_paired_difference'] for c in case_results if c['observed_pairs']) if any(c['observed_pairs'] for c in case_results) else None,
                            'scheduled_delta_bounds': [statistics.mean(pair[side] for pair in scheduled_bounds) for side in (0, 1)],
                            'case_cluster_interval': interval, 'family_deltas': family_means, 'family_cluster_sensitivity': family_interval,
                            'compatible_control_safety_regressions': control_regressions,
                            'control_valid_pairs': control_valid_pairs, 'control_scheduled_pairs': control_scheduled_pairs, 'decision': decision,
                            'inference_limit': 'Partial paired summaries are descriptive; repetitions are not independent cases. Missing pairs remain unknown.'}
    costs = [c['cost'] for c in cells if c.get('cost')]
    without = [c for c in cells if c['state'] != 'missing_unrun' and not c.get('cost')]
    return {'complete_campaign': complete_campaign, 'arms': totals, 'comparisons': comparisons,
            'cost_accounting': legacy.cost_summary(costs, [{'cost_evidence': None} for _ in without]),
            'setup_accounting': {'status': 'reported_separately', 'included_in_attempt_cost': False,
                                 'note': 'Index construction/update and setup measurements require their own evidence; no zero cost is inferred.'}}


def analyze_campaign(schedule_path, trusted_schedule_sha256, condition_path, trusted_condition_sha256,
                     case_manifest_path, ledger_path, trusted_ledger_sha256, artifact_root):
    schedule = trusted_json(schedule_path, trusted_schedule_sha256)
    conditions = trusted_json(condition_path, trusted_condition_sha256)
    cases_manifest = trusted_json(case_manifest_path, schedule['cases_manifest_sha256'])
    ledger = trusted_json(ledger_path, trusted_ledger_sha256)
    expected = build_schedule(cases_manifest, conditions, cases_manifest_sha256=schedule['cases_manifest_sha256'],
                              condition_sha256=trusted_condition_sha256, seed=schedule['seed'])
    require(schedule == expected, 'schedule differs from deterministic frozen design')
    require(ledger.get('campaign_version') == FORMAT_VERSION and ledger.get('schedule_sha256') == trusted_schedule_sha256
            and ledger.get('condition_sha256') == trusted_condition_sha256, 'sealed ledger campaign mismatch')
    raw_root = Path(artifact_root)
    require(not raw_root.is_symlink(), 'artifact root must not be a symlink')
    root = raw_root.resolve(strict=True)
    common = conditions['shared_identity']
    require(common['scorer_sha256'] == digest(legacy.HARNESS / 'scoring/score_v2.py'), 'frozen scorer differs from current scorer')
    require(common['answer_schema_sha256'] == digest(legacy.HARNESS / 'schema/contestant-answer-v2.schema.json'), 'frozen answer schema differs from current schema')
    cases = {r['case_id']: r for r in cases_manifest['cases']}
    for case_id, row in cases.items():
        record_path = pinned_artifact(root, {'path': row['record_path'], 'sha256': row['record_sha256']})
        oracle_path = pinned_artifact(root, {'path': row['oracle_bundle_path'], 'sha256': row['oracle_bundle_sha256']})
        record = score.read_json(record_path)
        require(record.get('change_id') == case_id and record.get('protocol_version') == 2, 'case record identity mismatch')
        require(record.get('evaluation_revision') == REVISION, 'case record is not discovery revision 2.2')
        result = score.preflight(record, oracle_bundle_path=oracle_path, trusted_oracle_sha256=row['oracle_bundle_sha256'],
                                 artifact_root=artifact_path(root, row['artifact_root'], directory=True))
        require(result.get('status') == 'verified', 'case oracle failed independent preflight: ' + case_id)
    planned = {c['slot_id']: c for c in schedule['cells']}
    entries = {}
    seen_attempts, seen_pointers = set(), set()
    require(isinstance(ledger.get('entries'), list), 'sealed ledger entries must be a list')
    for entry in ledger['entries']:
        require(isinstance(entry, dict), 'sealed ledger entry must be an object')
        required = {'slot_id', 'measurement_identity', 'run_started_at', 'status', 'campaign_budget_reservation_id'}
        required |= {'answer', 'score', 'receipts'} if entry.get('status') == 'completed' else {'failure', 'attempt'}
        require(set(entry) == required, 'sealed entry fields do not match completed or failed state')
        slot = entry.get('slot_id')
        require(slot in planned and slot not in entries, 'off-schedule or duplicate ledger slot')
        require(entry.get('measurement_identity') == expected_identity(planned[slot], trusted_schedule_sha256, trusted_condition_sha256, conditions), 'ledger identity differs from frozen slot')
        attempt_key = (planned[slot]['arm'], entry.get('run_started_at'))
        require(attempt_key not in seen_attempts, 'duplicate runner/start attempt identity')
        seen_attempts.add(attempt_key)
        for key in ('answer', 'receipts', 'attempt'):
            if key in entry:
                pointer_key = (key, entry[key].get('sha256'))
                require(pointer_key not in seen_pointers, 'duplicate answer/receipt/attempt reused across slots')
                seen_pointers.add(pointer_key)
        entries[slot] = entry
    cells, errors = [], []
    for cell in schedule['cells']:
        entry = entries.get(cell['slot_id'])
        if entry is None:
            cells.append({**cell, 'state': 'missing_unrun', 'complete_supported_impact': None, 'valid_answer': False})
            continue
        try:
            fact = verify_entry(entry, cell, cases, conditions, trusted_schedule_sha256, trusted_condition_sha256, root)
        except (OSError, ValueError, TypeError, KeyError, AttributeError) as exc:
            fact = {'state': 'verification_rejected', 'failure_state': 'verification_rejected',
                    'complete_supported_impact': False, 'valid_answer': False, 'reason': str(exc)}
            errors.append({'slot_id': cell['slot_id'], 'reason': str(exc)})
            try:
                fact['cost'] = sealed_completed_cost(entry, cell, expected_identity(cell, trusted_schedule_sha256, trusted_condition_sha256, conditions), root, conditions)
            except (OSError, ValueError, TypeError, KeyError, AttributeError):
                pass
        cells.append({**cell, **fact})
    result = summarize_cells(cells, cases, conditions)
    result['global_budget_accounting'] = campaign_budget_summary(ledger, entries, cells, conditions, trusted_schedule_sha256, trusted_condition_sha256, root)
    result['cost_accounting'].update(scope='sealed_observed_attempts_only',
                                     slots_without_sealed_attempt=len(cells) - len(entries),
                                     note='Unrecorded slots have no cost evidence; known attempt totals do not bound unrecorded campaign exposure.')
    if errors:
        for comparison in result['comparisons'].values():
            comparison['decision'] = 'integrity_errors'
    elif result['global_budget_accounting']['decision_blockers']:
        for comparison in result['comparisons'].values():
            if comparison['decision'] != 'incomplete_campaign':
                comparison['decision'] = 'budget_evidence_unresolved'
    status = 'complete' if result['complete_campaign'] else 'incomplete'
    if errors:
        status = 'integrity_errors'
    elif result['complete_campaign'] and result['global_budget_accounting']['decision_blockers']:
        status = 'budget_evidence_unresolved'
    return {'campaign_version': FORMAT_VERSION, 'protocol_version': 2, 'evaluation_revision': REVISION,
            'schedule_sha256': trusted_schedule_sha256, 'condition_sha256': trusted_condition_sha256,
            'ledger_sha256': trusted_ledger_sha256, 'status': status,
            'campaign_authorization': conditions['campaign_budget'], 'paid_runs_authorized': conditions['paid_runs_authorized'],
            'scheduled_attempts': len(cells), 'cells': cells, 'integrity_errors': errors, **result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    plan = sub.add_parser('schedule')
    plan.add_argument('--cases', type=Path, required=True)
    plan.add_argument('--trusted-cases-sha256', required=True)
    plan.add_argument('--conditions', type=Path, required=True)
    plan.add_argument('--trusted-condition-sha256', required=True)
    plan.add_argument('--seed', type=int, required=True)
    run = sub.add_parser('analyze')
    for name in ('schedule', 'conditions', 'cases', 'ledger', 'artifact-root'):
        run.add_argument('--' + name, type=Path, required=True)
    for name in ('schedule', 'condition', 'ledger'):
        run.add_argument('--trusted-' + name + '-sha256', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'schedule':
            result = build_schedule(trusted_json(args.cases, args.trusted_cases_sha256), trusted_json(args.conditions, args.trusted_condition_sha256),
                                    cases_manifest_sha256=args.trusted_cases_sha256, condition_sha256=args.trusted_condition_sha256, seed=args.seed)
        else:
            result = analyze_campaign(args.schedule, args.trusted_schedule_sha256, args.conditions, args.trusted_condition_sha256,
                                      args.cases, args.ledger, args.trusted_ledger_sha256, args.artifact_root)
        print(json.dumps(result, indent=2, allow_nan=False))
        return 1 if result.get('integrity_errors') else 0
    except (OSError, ValueError, TypeError, KeyError) as exc:
        print(json.dumps({'status': 'preflight_failed', 'reason': str(exc)}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
