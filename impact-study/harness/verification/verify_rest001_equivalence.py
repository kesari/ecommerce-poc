#!/usr/bin/env python3
"""Independently verify an alternative REST read mapping with the original oracle.

The original runner, criteria, tests and historical evidence remain unchanged.
Only its source-patch operation is selected differently for this fresh attempt.
"""
import argparse
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import verify_rest001 as original

APPROVED_CRITERIA = "597434b2d44770fe5e5e577f5287d7564ea527eefd234eee2c43a780655782dc"
APPROVED_TEST = "0ec0abbcb2c5e9e4ce002f048421bc2da77522a799e018885b99176896527a54"
APPROVED_SOURCE = "b8f3f84e93ef46d5192d61cee2d04f7bcfddb1cb817b7fa1a75067141346f727"
SNAPSHOT = "fcb7d2471cb0756fb7eaf817c233522c9457861469eb7b68e87a826aebaaf4a8"
CLIENT = "com.poc.order.infrastructure.client.AccountClient"
RECORD = CLIENT + ".AddressResponse"
COMPONENT = RECORD + ".postalCode"
ROUTE = "GET /api/v1/addresses/{addressId}"


def record_body(text):
    match = re.findall(r"\brecord\s+AddressResponse\s*\((.*?)\)\s*\{\s*\}", text, re.S)
    if len(match) != 1:
        raise ValueError("expected exact AddressResponse record declaration")
    return match[0]


def source_identity(source, repo="order-service", record=RECORD):
    if repo != "order-service" or record != RECORD:
        raise ValueError("unproved repository or record identity")
    if original.sha(source.encode()) != APPROVED_SOURCE:
        raise ValueError("source differs from pinned AccountClient")
    body = record_body(source)
    components = [component.strip().split() for component in body.split(",")]
    if components.count(["String", "postalCode"]) != 1 or "@" in body:
        raise ValueError("postalCode is not the exact unannotated String component")
    if len(re.findall(r"public\s+AddressSnapshot\s+address\s*\(String\s+bearerToken,\s*UUID\s+addressId\)", source)) != 1:
        raise ValueError("address(String, UUID) signature not established")
    if len(re.findall(r"\baddress\s*\(", source)) != 1:
        raise ValueError("method-only alias would be ambiguous across overloads")
    if '.uri("/api/v1/addresses/{addressId}", addressId)' not in source or "response.postalCode()" not in source:
        raise ValueError("record read/route relationship not established")
    return {"repo": repo, "record": record, "component": COMPONENT, "component_type": "java.lang.String",
            "method": CLIENT + ".address", "method_parameters": ["java.lang.String", "java.util.UUID"],
            "route": ROUTE, "source_sha256": APPROVED_SOURCE, "snapshot_sha256": SNAPSHOT,
            "record_body_sha256": original.sha(body.encode())}


def method_mapping(path, field):
    source = path.read_text()
    source = source.replace("AddressResponse response = client.get()", "com.fasterxml.jackson.databind.JsonNode response = client.get()")
    source = source.replace(".body(AddressResponse.class);", ".body(com.fasterxml.jackson.databind.JsonNode.class);")
    old = """return new AddressSnapshot(response.fullName(), response.line1(), response.line2(),
                    response.city(), response.state(), response.postalCode(), response.country());"""
    new = f'''return new AddressSnapshot(response.path("fullName").asText(null),
                    response.path("line1").asText(null), response.path("line2").asText(null),
                    response.path("city").asText(null), response.path("state").asText(null),
                    response.path("{field}").asText(null), response.path("country").asText(null));'''
    if source.count(old) != 1:
        raise ValueError("expected exact address mapping block")
    path.write_text(source.replace(old, new))


def run(args):
    if args.trusted_criteria_sha256 != APPROVED_CRITERIA or args.trusted_test_sha256 != APPROVED_TEST:
        raise ValueError("the original reviewed oracle pins are required")
    patch = original.replace_once
    selected = []

    def select_repair(path, old, new):
        if path.name == "AccountClient.java" and old == "String state, String postalCode,":
            field = "wrongPostcode" if 'JsonAlias("wrongPostcode")' in new else "postcode"
            if f'JsonAlias("{field}")' not in new:
                raise ValueError("unexpected reference repair patch")
            selected.append(field)
            method_mapping(path, field)
        else:
            patch(path, old, new)

    output = args.output.resolve()
    captured = io.StringIO()
    # Select the alternative repair; all assertion execution stays in the original runner.
    original.replace_once = select_repair
    try:
        with redirect_stdout(captured):
            code = original.run(args)
    finally:
        original.replace_once = patch
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    (output / "original-runner-result.log").write_text(captured.getvalue())
    if code:
        print(captured.getvalue())
        return code
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    try:
        if selected != ["postcode", "wrongPostcode"] or manifest["snapshot_sha256"] != SNAPSHOT:
            raise ValueError("wrong repair strategy or snapshot")
        source = (output / "baseline-source/order-service" / original.CLIENT).read_text()
        identity = source_identity(source)
        (output / "baseline-AccountClient.java").write_text(source)
        identity["phase_record_body_sha256"] = {}
        for phase in manifest["phases"]:
            actual = (output / phase / "order-service" / original.CLIENT).read_text()
            digest = original.sha(record_body(actual).encode())
            if digest != identity["record_body_sha256"] or "@" in record_body(actual):
                raise ValueError("alternative repair changed/annotated the protected record")
            identity["phase_record_body_sha256"][phase] = digest
        classes = output / "repaired/order-service/target/classes"
        for name, class_name in [("record-javap.log", CLIENT + "$AddressResponse"), ("client-javap.log", CLIENT)]:
            argv = ["javap", "-classpath", str(classes), "-p", "-s", class_name]
            result = subprocess.run(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            (output / name).write_bytes(result.stdout)
            manifest["commands"].append({"argv": argv, "cwd": str(output), "exit_code": result.returncode,
                                         "log": name, "log_sha256": original.sha(result.stdout)})
            if result.returncode:
                raise ValueError("compiled member identity command failed")
        record_javap = (output / "record-javap.log").read_text()
        client_javap = (output / "client-javap.log").read_text()
        if "public java.lang.String postalCode();\n    descriptor: ()Ljava/lang/String;" not in record_javap:
            raise ValueError("compiled record accessor not established")
        if "address(java.lang.String, java.util.UUID);" not in client_javap:
            raise ValueError("compiled address parameter identity not established")
        identity.update({"compiled_accessor_verified": True, "compiled_method_signature_verified": True,
                         "method_mapping_criteria_passed": True, "wrong_field_fault_detected": True})
        (output / "source-identity.json").write_text(json.dumps(identity, indent=2) + "\n")
        shutil.copyfile(Path(__file__), output / "verify_rest001_equivalence.py")
        manifest.update({"repair_strategy": "method_level_json_read_mapping", "source_identity": identity,
                         "strategy_verifier_sha256": original.sha(Path(__file__).read_bytes())})
    except Exception as error:
        manifest.update({"status": "failed", "error": str(error)})
        code = 1
    manifest["artifacts"] = [{"path": path.name, "sha256": original.sha(path.read_bytes())}
                             for path in sorted(output.iterdir()) if path.is_file() and path.name != "manifest.json"]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"status": manifest["status"], "manifest": str(manifest_path), "error": manifest.get("error")}))
    return code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--estate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--trusted-criteria-sha256", required=True)
    parser.add_argument("--trusted-test-sha256", required=True)
    try:
        sys.exit(run(parser.parse_args()))
    except (ValueError, OSError) as error:
        parser.exit(2, str(error) + "\n")
