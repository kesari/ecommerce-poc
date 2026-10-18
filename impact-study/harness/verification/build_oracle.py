#!/usr/bin/env python3
"""Bind the two reviewed cache/Kafka records to their independent execution proof.

No containers or model calls run here. Record/test/criteria and manifest pins are
reviewed inputs. A changed claim requires new review, not a self-generated hash.
"""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


SPECS = {
    "INT-002": {
        "manifest": "79581351534b03b5132fff825babb6bf2099194c7cf01e826e5c27520b076fe9",
        "criteria": "a9641ec955956d8573540f0b7e257ff9a1e84a12b8beb32f2aa222bfe3b3ec38",
        "test": "5c689c5c0fbb0b847179e2addff1b216b5e861af119bc0c4cb2b2c536dd2282a",
        "record": "e8929ab2ef16c7c840337e98665cad1024028433efc1d6ff934acf680dccaabf",
        "test_file": "Int002CacheBoundaryTest.java",
        "test_class": "com.poc.shipment.infrastructure.cache.Int002CacheBoundaryTest",
        "fault_assertion": "ESTIMATE_COMPATIBILITY",
    },
    "KAFKA-001": {
        "manifest": "465980d020d1137dd461b95170c8302af9e76ae80e983cc64d82134f51cb6a01",
        "criteria": "09f8d989c7f92bd9f37397cea66501890e67a691ba0334801563efc4ee589a36",
        "test": "a78b3e84311432e79973717f4e218ec9ce993898663e3e4cab23510da3193649",
        "record": "9bd0313dc80dd46f0b6a1feef89a0d3cb9833dab61cbedc5190ebf71f9a16163",
        "test_file": "Kafka001BoundaryTest.java",
        "test_class": "com.poc.shipment.verification.Kafka001BoundaryTest",
        "fault_assertion": "BASKET_CONSUMER_DELIVERY",
    },
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def build(evidence_root, record_path, trusted_manifest_sha256):
    record = json.loads(record_path.read_text())
    spec = SPECS.get(record.get("change_id"))
    if not spec or sha(canonical(record)) != spec["record"] or record.get("status") != "verified":
        raise ValueError("record differs from the reviewed scoped predicates")
    root = evidence_root.resolve(strict=True)
    raw = (root / "manifest.json").read_bytes()
    if trusted_manifest_sha256 != spec["manifest"] or sha(raw) != trusted_manifest_sha256:
        raise ValueError("execution manifest differs from reviewed external pin")
    manifest = json.loads(raw)
    if manifest.get("status") != "passed" or manifest["snapshot_sha256"] != record["snapshot_sha256"]:
        raise ValueError("execution did not pass on the record snapshot")
    if sha(canonical(manifest["snapshot"])) != record["snapshot_sha256"]:
        raise ValueError("execution pin manifest differs from snapshot")
    if manifest["criteria_sha256"] != spec["criteria"] or manifest["test_sha256"] != spec["test"]:
        raise ValueError("execution used an unreviewed oracle")
    paths = {"manifest.json"}
    for artifact in manifest["artifacts"]:
        relative = Path(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts or str(relative) in paths:
            raise ValueError("unsafe or duplicate artifact path")
        target = (root / relative).resolve(strict=True)
        if not target.is_relative_to(root) or not target.is_file() or sha(target.read_bytes()) != artifact["sha256"]:
            raise ValueError(f"execution artifact changed: {relative}")
        paths.add(str(relative))
    if sha((root / "criteria.json").read_bytes()) != spec["criteria"] or sha((root / spec["test_file"]).read_bytes()) != spec["test"]:
        raise ValueError("reviewed oracle bytes differ")
    criteria = json.loads((root / "criteria.json").read_text())
    if set(manifest["phases"]) != set(criteria["phases"]) or manifest["scenario"] != criteria["scenario"]:
        raise ValueError("wrong scenario or incomplete phase set")
    patch = (root / "changed.patch").read_text()
    if record["change_id"] == "KAFKA-001":
        patch = patch.replace("a/order-service/", "a/").replace("b/order-service/", "b/")
    if patch != record["proposed_change"]["diff"]:
        raise ValueError("proposed diff differs from independently executed change")
    for phase, expected in criteria["phases"].items():
        observation = json.loads((root / f"{phase}-observation.json").read_text())
        execution = manifest["phases"][phase]
        if not execution["passed"] or observation != execution["observations"]:
            raise ValueError(f"{phase}: incomplete or inconsistent observations")
        if any(observation.get(key) != value for key, value in expected.items()):
            raise ValueError(f"{phase}: observations differ from acceptance criteria")
        if sha((root / f"{phase}.patch").read_bytes()) != execution["patch_sha256"]:
            raise ValueError(f"{phase}: executable patch changed")
        command = [item for item in manifest["commands"] if item["log"] == f"{phase}-test.log"]
        if len(command) != 1 or command[0]["exit_code"] != criteria["expected_test_exit"][phase]:
            raise ValueError(f"{phase}: wrong protected test command result")
        if f"-Dverification.phase={phase}" not in command[0]["argv"] or f"-Dtest={Path(spec['test_file']).stem}" not in command[0]["argv"]:
            raise ValueError(f"{phase}: wrong protected test invocation")
        suite = ET.parse(root / f"{phase}-junit.xml").getroot()
        fault = phase == "faulted-repair"
        if suite.attrib["name"] != spec["test_class"] or int(suite.attrib["tests"]) != 1 or int(suite.attrib["errors"]) != 0 or int(suite.attrib["failures"]) != int(fault):
            raise ValueError(f"{phase}: JUnit result is not the required protected check")
        if fault and (not execution["fault_detected"] or spec["fault_assertion"] not in suite.find(".//failure").attrib.get("message", "")):
            raise ValueError("targeted fault was not rejected by the required assertion")
        if record["change_id"] == "INT-002":
            if observation["second_estimate"] != criteria["expected_estimate"] or observation["first_estimate"] != (None if fault else criteria["expected_estimate"]):
                raise ValueError(f"{phase}: estimate compatibility failed")
            if observation["cache_errors"] != 0 or not observation["old_entry_preserved"]:
                raise ValueError(f"{phase}: cache error or destructive namespace change")
        elif observation["broker_records"] != 1 or observation["publisher_acknowledged"] != 1 or not observation["consumer_liveness_control_passed"]:
            raise ValueError(f"{phase}: Kafka delivery/liveness control failed")
    if record["change_id"] == "INT-002" and (root / "changed.patch").read_bytes() != (root / "repaired.patch").read_bytes():
        raise ValueError("benign control must pass without an additional production repair")
    references = ["manifest.json", "criteria.json", spec["test_file"]]
    references += [f"{phase}{suffix}" for phase in criteria["phases"]
                   for suffix in ("-observation.json", "-junit.xml", "-test.log", ".patch")]
    if not set(references) <= paths:
        raise ValueError("required proof artifact missing")
    attestations = []
    for kind in ("repositories", "symbols", "edges"):
        attestations += [{"kind": kind, "claim": claim, "artifact_paths": references} for claim in record[kind]]
    for plan in record["repair_alternatives"]:
        for kind in ("actions", "obligations"):
            attestations += [{"kind": kind, "claim": {key: value for key, value in claim.items() if key != "critical"},
                              "artifact_paths": references} for claim in plan[kind]]
    return {"protocol_version": 2, "change_id": record["change_id"], "record_sha256": spec["record"],
            "snapshot_sha256": record["snapshot_sha256"],
            "artifacts": [{"path": path, "sha256": sha((root / path).read_bytes())} for path in sorted(paths)],
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
        with args.output.open("x") as output:
            output.write(json.dumps(bundle, indent=2) + "\n")
        print(json.dumps({"bundle": str(args.output), "sha256": sha(args.output.read_bytes())}))
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        parser.exit(2, str(error) + "\n")
