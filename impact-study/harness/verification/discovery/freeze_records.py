#!/usr/bin/env python3
"""Promote explicitly reviewed candidates; never infer trust from candidate files."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

from build_records import (HERE, STUDY, PROOFS, candidates, canonical, claim_bindings,
                           verify_record_bindings, safe_path, case_proof_names)
sys.path.insert(0, str(STUDY / 'harness/scoring'))
import score_v2


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(candidate_dir, trusted, reviewed):
    expected = candidates(STUDY, trusted)
    records = {case: json.loads((candidate_dir / (case + '.json')).read_text()) for case in expected}
    bindings = json.loads((candidate_dir / 'claim-proofs.json').read_text())
    verify_record_bindings(records, bindings)
    if records != expected or set(reviewed) != set(records):
        raise ValueError('candidate set differs from regenerated proofs or independent review')
    output = {}
    for case, draft in records.items():
        if hashlib.sha256(canonical(draft)).hexdigest() != reviewed[case]:
            raise ValueError('independent full-record review pin mismatch: ' + case)
        record = {**draft, 'status': 'verified'}
        errors = score_v2.record_errors(record)
        if errors:
            raise ValueError(errors)
        paths = set()
        for name in case_proof_names(case):
            relative = PROOFS[name]
            paths.add(relative)
            manifest = json.loads(safe_path(STUDY, relative).read_text())
            if name == 'audit':
                continue
            artifacts = manifest.get('artifacts', manifest)
            names = artifacts if isinstance(artifacts, dict) else [item['path'] for item in artifacts]
            paths.update(str(Path(relative).parent / p) for p in names)
        claims = []
        for bound in bindings['records'][case]['claims']:
            claim = bound['claim']
            kind = bound['pointer'].split('/')[-2]
            claims.append({'kind': kind, 'claim': {key: claim[key] for key in score_v2.FIELDS[kind]},
                           'artifact_paths': sorted(set(bound['artifact_paths'] + bound['proof_manifests']))})
        bundle = {'protocol_version': 2, 'snapshot_sha256': record['snapshot_sha256'],
                  'change_id': case, 'record_sha256': hashlib.sha256(score_v2.canonical(record).encode()).hexdigest(),
                  'scoring_policy': {'mode': 'scoped_reference_v1'},
                  'artifacts': [{'path': p, 'sha256': sha(safe_path(STUDY, p))} for p in sorted(paths)],
                  'attestations': claims}
        output[case] = (record, bundle)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidates', required=True, type=Path)
    parser.add_argument('--trusted-proofs', required=True, type=Path)
    parser.add_argument('--reviewed-records', required=True, type=Path)
    args = parser.parse_args()
    output = prepare(args.candidates, json.loads(args.trusted_proofs.read_text()), json.loads(args.reviewed_records.read_text()))
    oracle_dir = STUDY / 'harness/verification/discovery/frozen'
    record_dir = STUDY / 'harness/records/v2'
    if oracle_dir.exists() or any((record_dir / (case + '.json')).exists() for case in output):
        raise ValueError('preserve frozen records; output already exists')
    oracle_dir.mkdir()
    rows = []
    families = ['http_binding','web_consumption','messaging','meaning','private_storage','compatible_shape']
    for case, (record, bundle) in sorted(output.items()):
        rp = record_dir / (case + '.json'); bp = oracle_dir / (case + '-oracle.json')
        rp.write_text(json.dumps(record, indent=2)+'\n'); bp.write_text(json.dumps(bundle, indent=2)+'\n')
        proof = score_v2.preflight(record, oracle_bundle_path=bp, trusted_oracle_sha256=sha(bp), artifact_root=STUDY)
        if proof['status'] != 'verified':
            raise ValueError(proof)
        rows.append({'case_id':case, 'family':families[(int(case[1:])-1)//2],
                     'compatible_control':int(case[1:])>=8,
                     'record_path':str(rp.relative_to(STUDY)), 'record_sha256':sha(rp),
                     'oracle_bundle_path':str(bp.relative_to(STUDY)), 'oracle_bundle_sha256':sha(bp),
                     'artifact_root':'.'})
    (oracle_dir / 'cases.json').write_text(json.dumps({'campaign_version':1, 'cases':rows},indent=2)+'\n')
    print(json.dumps({'status':'verified','cases':len(rows),'case_manifest':str(oracle_dir / 'cases.json')}))


if __name__ == '__main__':
    main()
