#!/usr/bin/env python3
"""Independent REST-001 boundary check. Artifacts must remain outside the model estate."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
DTO = Path("src/main/java/com/poc/account/api/dto/AddressResponse.java")
CLIENT = Path("src/main/java/com/poc/order/infrastructure/client/AccountClient.java")
JAVA_TEST = Path("src/test/java/com/poc/order/verification/Rest001BoundaryTest.java")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def inside(path, root):
    return path.resolve().is_relative_to(root.resolve())


def replace_once(path, old, new):
    text = path.read_text()
    if text.count(old) != 1:
        raise ValueError(f"patch anchor must occur exactly once: {path}: {old!r}")
    path.write_text(text.replace(old, new))


def files(root):
    return {str(path.relative_to(root)): sha(path.read_bytes())
            for path in sorted(root.rglob("*")) if path.is_file() and "target" not in path.relative_to(root).parts}


def run(args):
    output = args.output.resolve()
    estate = args.estate.resolve()
    if inside(output, estate) or inside(output, HERE.parent.parent):
        raise ValueError("output must be outside the estate and study repository")
    if output.exists():
        raise ValueError("output must not already exist; each attempt needs a fresh evidence directory")
    criteria_path = HERE / "rest001/criteria.json"
    test_path = HERE / "rest001/Rest001BoundaryTest.java"
    for path, expected in [(criteria_path, args.trusted_criteria_sha256), (test_path, args.trusted_test_sha256)]:
        if sha(path.read_bytes()) != expected:
            raise ValueError(f"reviewed oracle digest mismatch: {path}")
    criteria = json.loads(criteria_path.read_text())
    output.mkdir(parents=True)
    commands = []
    manifest = {"protocol_version": 2, "scenario": criteria["scenario"], "status": "running",
                "criteria_sha256": args.trusted_criteria_sha256, "test_sha256": args.trusted_test_sha256,
                "verifier_sha256": sha(Path(__file__).read_bytes()), "commands": commands, "phases": {}}
    (output / "criteria.json").write_bytes(criteria_path.read_bytes())
    (output / "Rest001BoundaryTest.java").write_bytes(test_path.read_bytes())
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    # Never allow a caller's Maven/JVM flags to silently bypass the protected checks.
    for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)

    def command(argv, cwd, log_name):
        started = time.monotonic()
        try:
            process = subprocess.run(argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, timeout=180)
            code, raw = process.returncode, process.stdout
        except subprocess.TimeoutExpired as error:
            code, raw = 124, (error.stdout or b"") + b"\nVERIFIER COMMAND TIMEOUT\n"
        (output / log_name).write_bytes(raw)
        commands.append({"argv": argv, "cwd": str(cwd), "exit_code": code, "log": log_name,
                         "log_sha256": sha(raw), "duration_seconds": round(time.monotonic() - started, 3)})
        return code

    try:
        command(["java", "-version"], output, "java-version.log")
        command(["mvn", "-version"], output, "maven-version.log")
        pins_path = HERE.parent / "indexes/pins.json"
        pins = json.loads(pins_path.read_text())["repositories"]
        snapshot = {repo: {"commit": item["commit"], "tree": item["tree"]}
                    for repo, item in sorted(pins.items())}
        manifest["snapshot_sha256"] = sha(canonical(snapshot))
        manifest["snapshot_hash_algorithm"] = "SHA256 of compact sort_keys JSON {repo:{commit,tree}} for all pinned repositories"
        manifest["snapshot"] = snapshot
        manifest["pins_file_sha256"] = sha(pins_path.read_bytes())
        source = output / "baseline-source"
        source.mkdir()
        for repo in ("account-service", "order-service"):
            pin = pins[repo]
            actual_tree = subprocess.check_output(["git", "-C", str(estate / repo), "rev-parse", pin["commit"] + "^{tree}"]).decode().strip()
            if actual_tree != pin["tree"]:
                raise ValueError(f"pinned tree mismatch: {repo}")
            archive = subprocess.check_output(["git", "-C", str(estate / repo), "archive", "--format=tar", pin["commit"]])
            target = source / repo
            target.mkdir()
            with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
                bundle.extractall(target, filter="data")
        manifest["baseline_source_files"] = files(source)
        manifest["baseline_source_sha256"] = sha(canonical(manifest["baseline_source_files"]))
        for phase in criteria["phases"]:
            work = output / phase
            shutil.copytree(source, work)
            account, order = work / "account-service", work / "order-service"
            if phase != "baseline":
                replace_once(account / DTO, "String postalCode,", "String postcode,")
            if phase in ("repaired", "faulted-repair"):
                alias = "postcode" if phase == "repaired" else "wrongPostcode"
                replace_once(order / CLIENT, "String state, String postalCode,",
                             f'String state, @com.fasterxml.jackson.annotation.JsonAlias("{alias}") String postalCode,')
            production_files = files(work)
            changed_paths = [path for path, digest in production_files.items()
                             if manifest["baseline_source_files"].get(path) != digest]
            import difflib
            patch = "".join("".join(difflib.unified_diff((source / path).read_text().splitlines(True),
                           (work / path).read_text().splitlines(True), fromfile="a/" + path, tofile="b/" + path))
                           for path in changed_paths)
            (output / f"{phase}.patch").write_text(patch)
            manifest["phases"][phase] = {"source_files": production_files, "patch_sha256": sha(patch.encode())}
            # Compile the full actual producer, not just a hand-written DTO substitute.
            code = command(["mvn", "-o", "-B", "-Dstyle.color=never", "-Dmaven.test.skip=true", "compile"],
                           account, f"{phase}-producer-compile.log")
            if code:
                raise RuntimeError(f"{phase}: producer compile failed (exit {code})")
            provider_copy = order / "src/test/java/com/poc/account/api/dto/AddressResponse.java"
            provider_copy.parent.mkdir(parents=True)
            provider_copy.write_bytes((account / DTO).read_bytes())
            (order / JAVA_TEST).parent.mkdir(parents=True)
            (order / JAVA_TEST).write_bytes(test_path.read_bytes())
            observation = output / f"{phase}-observation.json"
            code = command(["mvn", "-o", "-B", "-Dstyle.color=never", "-Dtest=Rest001BoundaryTest",
                            f"-Dverification.phase={phase}", f"-Dverification.observation={observation}", "test"],
                           order, f"{phase}-boundary-test.log")
            if code != criteria["expected_test_exit"][phase]:
                raise RuntimeError(f"{phase}: unexpected boundary test exit {code}")
            if not observation.exists():
                raise RuntimeError(f"{phase}: no runtime observation (infrastructure failure is not fault detection)")
            observed = json.loads(observation.read_text())
            for key, expected in criteria["phases"][phase].items():
                if observed.get(key) != expected:
                    raise RuntimeError(f"{phase}: {key}: expected {expected!r}; observed {observed.get(key)!r}")
            report = order / "target/surefire-reports/TEST-com.poc.order.verification.Rest001BoundaryTest.xml"
            suite = ET.parse(report).getroot()
            if int(suite.attrib["tests"]) != 1 or int(suite.attrib["errors"]) != 0:
                raise RuntimeError(f"{phase}: wrong JUnit suite or infrastructure error")
            expected_failures = 1 if phase == "faulted-repair" else 0
            if int(suite.attrib["failures"]) != expected_failures:
                raise RuntimeError(f"{phase}: wrong assertion failure count")
            if phase == "faulted-repair":
                failure = suite.find(".//failure")
                if failure is None or "REPAIR_BINDING" not in failure.attrib.get("message", ""):
                    raise RuntimeError("faulted repair failed for an unrelated reason")
            shutil.copyfile(report, output / f"{phase}-junit.xml")
            manifest["phases"][phase].update({"passed": True, "observations": observed,
                                             "fault_detected": phase == "faulted-repair"})
        if files(source) != manifest["baseline_source_files"]:
            raise RuntimeError("protected baseline source changed during verification")
        if sha(test_path.read_bytes()) != args.trusted_test_sha256 or sha(criteria_path.read_bytes()) != args.trusted_criteria_sha256:
            raise RuntimeError("protected oracle changed during verification")
        manifest["status"] = "passed"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
    finally:
        manifest["artifacts"] = [{"path": str(path.relative_to(output)), "sha256": sha(path.read_bytes())}
                                 for path in sorted(output.iterdir()) if path.is_file() and path.name != "manifest.json"]
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"status": manifest["status"], "manifest": str(output / "manifest.json"),
                      "error": manifest.get("error")}, indent=2))
    return 0 if manifest["status"] == "passed" else 1


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
