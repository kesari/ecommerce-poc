#!/usr/bin/env python3
"""Bind the reviewed bounded REST record to independently executed observations.

The caller must review and externally pin the resulting bundle. This command
never changes a trusted pin and does not grant arbitrary prose evidence credit.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
APPROVED_CRITERIA = "597434b2d44770fe5e5e577f5287d7564ea527eefd234eee2c43a780655782dc"
APPROVED_TEST = "0ec0abbcb2c5e9e4ce002f048421bc2da77522a799e018885b99176896527a54"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def build(evidence_root, record_path, trusted_manifest_sha256):
    evidence_root = evidence_root.resolve(strict=True)
    manifest_bytes = (evidence_root / "manifest.json").read_bytes()
    if sha(manifest_bytes) != trusted_manifest_sha256:
        raise ValueError("execution manifest digest differs from caller's reviewed pin")
    manifest = json.loads(manifest_bytes)
    if manifest["status"] != "passed":
        raise ValueError("execution did not pass")
    if manifest["criteria_sha256"] != APPROVED_CRITERIA or manifest["test_sha256"] != APPROVED_TEST:
        raise ValueError("execution used unreviewed oracle")
    record = json.loads(record_path.read_text())
    candidate = json.loads((HERE / "record-candidate.json").read_text())
    # Only draft->verified promotion is allowed without reviewing this builder.
    candidate["status"] = record["status"]
    if record != candidate or record["status"] not in ("draft", "verified"):
        raise ValueError("record differs from the reviewed bounded predicates")
    if record["snapshot_sha256"] != manifest["snapshot_sha256"]:
        raise ValueError("record and execution snapshots differ")
    paths = ["manifest.json"]
    for artifact in manifest["artifacts"]:
        relative = Path(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("unsafe artifact path")
        path = (evidence_root / relative).resolve(strict=True)
        if not path.is_relative_to(evidence_root) or sha(path.read_bytes()) != artifact["sha256"]:
            raise ValueError(f"execution artifact changed: {relative}")
        paths.append(str(relative))
    criteria = json.loads((evidence_root / "criteria.json").read_text())
    if sha((evidence_root / "criteria.json").read_bytes()) != APPROVED_CRITERIA:
        raise ValueError("criteria bytes differ from reviewed oracle")
    if sha((evidence_root / "Rest001BoundaryTest.java").read_bytes()) != APPROVED_TEST:
        raise ValueError("test bytes differ from reviewed oracle")
    for phase, expected in criteria["phases"].items():
        observed = json.loads((evidence_root / f"{phase}-observation.json").read_text())
        if any(observed.get(key) != value for key, value in expected.items()):
            raise ValueError(f"{phase}: observations differ from acceptance criteria")
        if not manifest["phases"][phase]["passed"]:
            raise ValueError(f"{phase}: did not pass")
    evidence = ["criteria.json", "Rest001BoundaryTest.java", "manifest.json"]
    evidence += [f"{phase}-{kind}" for phase in criteria["phases"]
                 for kind in ("observation.json", "junit.xml", "boundary-test.log")]
    attestations = []
    for kind in ("repositories", "symbols", "edges"):
        attestations += [{"kind": kind, "claim": claim, "artifact_paths": evidence}
                         for claim in record[kind]]
    for plan in record["repair_alternatives"]:
        for kind in ("actions", "obligations"):
            for claim in plan[kind]:
                attestations.append({"kind": kind, "claim": {k: v for k, v in claim.items() if k != "critical"},
                                     "artifact_paths": evidence})
    return {"protocol_version": 2, "change_id": record["change_id"],
            "record_sha256": sha(json.dumps(record, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()),
            "snapshot_sha256": record["snapshot_sha256"],
            "artifacts": [{"path": path, "sha256": sha((evidence_root / path).read_bytes())} for path in sorted(set(paths))],
            "attestations": attestations}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--trusted-manifest-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        bundle = build(args.evidence_root, args.record, args.trusted_manifest_sha256)
        # Refuse to replace an existing reviewed bundle.
        with args.output.open("x") as output:
            output.write(json.dumps(bundle, indent=2) + "\n")
        print(json.dumps({"bundle": str(args.output), "sha256": sha(args.output.read_bytes())}))
    except (ValueError, OSError) as error:
        parser.exit(2, str(error) + "\n")
