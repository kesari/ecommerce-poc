#!/usr/bin/env python3
"""Combine historical REST proof with a new independently tested repair equivalence.

Only exact snapshot/record-bound members and directed contract fields are aliases.
The resulting bundle still requires a newly reviewed external pin.
"""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from verify_rest001_equivalence import (
    APPROVED_CRITERIA, APPROVED_TEST, CLIENT, COMPONENT, RECORD, SNAPSHOT, source_identity,
)
from verify_rest001 import sha

REFERENCE_BUNDLE_PIN = "33b42c1fca9396918b35c584076cca9cb5351ceb09d59889d5c195fd050d12d6"
RECORD_PIN = "7cea7df02d6b51d16ae8a5d1b50d7174b1a6d74e0690713a60050a72f2f19d2f"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def exact_equivalences(identity, source, record, proof_paths):
    expected = source_identity(source, identity["repo"], identity["record"])
    if any(identity.get(key) != value for key, value in expected.items()):
        raise ValueError("member identity differs from exact pinned source")
    flags = ("compiled_accessor_verified", "compiled_method_signature_verified", "method_mapping_criteria_passed", "wrong_field_fault_detected")
    if any(identity.get(key) is not True for key in flags):
        raise ValueError("source-only identity cannot establish a tested repair equivalence")
    if (set(identity["phase_record_body_sha256"]) != {"baseline", "changed", "repaired", "faulted-repair"}
            or set(identity["phase_record_body_sha256"].values()) != {expected["record_body_sha256"]}):
        raise ValueError("record component changed between repair phases")
    if sha(canonical(record)) != RECORD_PIN:
        raise ValueError("equivalences apply only to the unchanged reviewed record")
    action = {key: value for key, value in record["repair_alternatives"][0]["actions"][0].items() if key != "critical"}
    symbol = record["symbols"][0]
    edge = record["edges"][0]
    if action["target"] != COMPONENT or symbol["fqn"] != COMPONENT:
        raise ValueError("canonical target is not the proven record component")
    equivalents = []
    accessor_targets = [RECORD + "#postalCode()", RECORD + ".postalCode()"]
    method_targets = [CLIENT + ".address", CLIENT + "#address(java.lang.String,java.util.UUID)",
                      CLIENT + "#address(String,UUID)", CLIENT + ".address(java.lang.String,java.util.UUID)",
                      CLIENT + ".address(String,UUID)"]
    for target in accessor_targets + method_targets:
        equivalents.append({"kind": "actions", "alias": {**action, "target": target},
                            "canonical": action, "artifact_paths": proof_paths})
    for target in accessor_targets:
        equivalents.append({"kind": "symbols", "alias": {**symbol, "fqn": target},
                            "canonical": symbol, "artifact_paths": proof_paths})
    equivalents.append({"kind": "edges", "alias": {**edge, "field": "postcode"},
                        "canonical": edge, "artifact_paths": proof_paths})
    return equivalents


def checked_artifacts(root, artifacts):
    result = []
    seen = set()
    for artifact in artifacts:
        relative = Path(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts or artifact["path"] in seen:
            raise ValueError("unsafe or duplicate proof artifact")
        target = (root / relative).resolve(strict=True)
        if not target.is_relative_to(root.resolve()) or not target.is_file() or sha(target.read_bytes()) != artifact["sha256"]:
            raise ValueError(f"proof artifact changed: {relative}")
        seen.add(artifact["path"])
        result.append(artifact)
    return result


def build(root, record_path, trusted_method_manifest_sha256):
    root = root.resolve(strict=True)
    reference, method = root / "reference", root / "method-mapping"
    reference_bytes = (reference / "oracle-bundle.json").read_bytes()
    if sha(reference_bytes) != REFERENCE_BUNDLE_PIN:
        raise ValueError("historical reference bundle differs from its reviewed pin")
    bundle = json.loads(reference_bytes)
    record = json.loads(record_path.read_text())
    if sha(canonical(record)) != RECORD_PIN or bundle["record_sha256"] != RECORD_PIN or record["snapshot_sha256"] != SNAPSHOT:
        raise ValueError("record changed; historical expectations cannot be replaced")
    manifest_bytes = (method / "manifest.json").read_bytes()
    if not trusted_method_manifest_sha256 or sha(manifest_bytes) != trusted_method_manifest_sha256:
        raise ValueError("method repair manifest differs from caller's reviewed pin")
    manifest = json.loads(manifest_bytes)
    if manifest["status"] != "passed" or manifest["snapshot_sha256"] != SNAPSHOT or manifest.get("repair_strategy") != "method_level_json_read_mapping":
        raise ValueError("method-level repair did not pass on the same snapshot")
    if manifest["criteria_sha256"] != APPROVED_CRITERIA or manifest["test_sha256"] != APPROVED_TEST:
        raise ValueError("alternative repair changed the original criteria or tests")
    reference_artifacts = checked_artifacts(reference, bundle["artifacts"])
    method_artifacts = checked_artifacts(method, manifest["artifacts"])
    criteria = json.loads((method / "criteria.json").read_text())
    if sha((method / "criteria.json").read_bytes()) != APPROVED_CRITERIA or sha((method / "Rest001BoundaryTest.java").read_bytes()) != APPROVED_TEST:
        raise ValueError("protected acceptance bytes changed")
    if set(manifest["phases"]) != set(criteria["phases"]):
        raise ValueError("incomplete original acceptance phase set")
    for phase, expected in criteria["phases"].items():
        execution = manifest["phases"][phase]
        observation = json.loads((method / f"{phase}-observation.json").read_text())
        if not execution["passed"] or observation != execution["observations"] or any(observation.get(key) != value for key, value in expected.items()):
            raise ValueError(f"{phase}: method repair did not meet the original observations")
        if sha((method / f"{phase}.patch").read_bytes()) != execution["patch_sha256"]:
            raise ValueError(f"{phase}: method repair patch changed")
        suite = ET.parse(method / f"{phase}-junit.xml").getroot()
        fault = phase == "faulted-repair"
        if int(suite.attrib["tests"]) != 1 or int(suite.attrib["errors"]) != 0 or int(suite.attrib["skipped"]) != 0 or int(suite.attrib["failures"]) != int(fault):
            raise ValueError(f"{phase}: protected test result differs from original acceptance")
        if fault and (not execution["fault_detected"] or "REPAIR_BINDING" not in suite.find(".//failure").attrib.get("message", "")):
            raise ValueError("wrong-field mapping fault was not detected by REPAIR_BINDING")
        for suffix, expected_exit in (("producer-compile.log", 0), ("boundary-test.log", criteria["expected_test_exit"][phase])):
            commands = [item for item in manifest["commands"] if item["log"] == f"{phase}-{suffix}"]
            if len(commands) != 1 or commands[0]["exit_code"] != expected_exit:
                raise ValueError(f"{phase}: wrong compile or boundary-test command result")
    identity = json.loads((method / "source-identity.json").read_text())
    if identity != manifest["source_identity"]:
        raise ValueError("source identity differs from executed manifest")
    proof_paths = ["method-mapping/" + item["path"] for item in method_artifacts]
    proof_paths += ["method-mapping/manifest.json", "reference/oracle-bundle.json"]
    equivalents = exact_equivalences(identity, (method / "baseline-AccountClient.java").read_text(), record, proof_paths)
    artifacts = [{"path": "reference/" + item["path"], "sha256": item["sha256"]} for item in reference_artifacts]
    artifacts += [{"path": "method-mapping/" + item["path"], "sha256": item["sha256"]} for item in method_artifacts]
    artifacts += [{"path": "reference/oracle-bundle.json", "sha256": REFERENCE_BUNDLE_PIN},
                  {"path": "method-mapping/manifest.json", "sha256": trusted_method_manifest_sha256}]
    attestations = [{**item, "artifact_paths": ["reference/" + path for path in item["artifact_paths"]]}
                    for item in bundle["attestations"]]
    return {**bundle, "artifacts": artifacts, "attestations": attestations, "equivalences": equivalents,
            "scoring_policy": {"mode": "scoped_reference_v1"}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--record", type=Path, required=True)
    parser.add_argument("--trusted-method-manifest-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        bundle = build(args.evidence_root, args.record, args.trusted_method_manifest_sha256)
        with args.output.open("x") as output:
            output.write(json.dumps(bundle, indent=2) + "\n")
        print(json.dumps({"bundle": str(args.output), "sha256": sha(args.output.read_bytes())}))
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        parser.exit(2, str(error) + "\n")
