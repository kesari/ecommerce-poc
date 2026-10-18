#!/usr/bin/env python3
"""Isolated real-Valkey INT-002 check; Docker uses an existing local image only."""
import argparse
import difflib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import xml.etree.ElementTree as ET

from verify_rest001 import HERE, canonical, files, inside, replace_once, sha

CACHE = Path("src/main/java/com/poc/shipment/infrastructure/cache/ValkeyEstimateCache.java")
SERVICE = Path("src/main/java/com/poc/shipment/application/ShipmentService.java")
TEST = Path("src/test/java/com/poc/shipment/infrastructure/cache/Int002CacheBoundaryTest.java")


def run(args):
    output = args.output.resolve()
    if inside(output, args.estate) or inside(output, HERE.parent.parent) or output.exists():
        raise ValueError("output must be new and outside the estate/study")
    criteria_path = HERE / "int002/criteria.json"
    test_path = HERE / "int002/Int002CacheBoundaryTest.java"
    if sha(criteria_path.read_bytes()) != args.trusted_criteria_sha256 or sha(test_path.read_bytes()) != args.trusted_test_sha256:
        raise ValueError("reviewed oracle digest mismatch")
    output.mkdir(parents=True)
    criteria = json.loads(criteria_path.read_text())
    shutil.copyfile(criteria_path, output / "criteria.json")
    shutil.copyfile(test_path, output / "Int002CacheBoundaryTest.java")
    manifest = {"protocol_version": 2, "scenario": criteria["scenario"], "status": "running",
                "criteria_sha256": args.trusted_criteria_sha256, "test_sha256": args.trusted_test_sha256,
                "verifier_sha256": sha(Path(__file__).read_bytes()), "commands": [], "phases": {}}
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)

    def command(argv, cwd, name):
        try:
            result = subprocess.run(argv, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
            code, raw = result.returncode, result.stdout
        except subprocess.TimeoutExpired as error:
            code, raw = 124, (error.stdout or b"") + b"\nVERIFIER COMMAND TIMEOUT\n"
        (output / name).write_bytes(raw)
        manifest["commands"].append({"argv": argv, "cwd": str(cwd), "exit_code": code,
                                     "log": name, "log_sha256": sha(raw)})
        return code, raw

    container = None
    try:
        for cmd, name in [(["java", "-version"], "java-version.log"), (["mvn", "-version"], "maven-version.log")]:
            code, _ = command(cmd, output, name)
            if code:
                raise RuntimeError(f"{name}: preflight failed")
        code, image = command(["docker", "image", "inspect", "valkey/valkey:8", "--format", "{{.Id}}"], output, "valkey-image.log")
        if code:
            raise RuntimeError("cached Valkey image unavailable; no image pull attempted")
        manifest["valkey_image_id"] = image.decode().strip()
        code, created = command(["docker", "run", "--rm", "-d", "--pull=never", "-p", "127.0.0.1::6379", "valkey/valkey:8"], output, "valkey-start.log")
        if code:
            raise RuntimeError("isolated Valkey container failed to start")
        container = created.decode().strip()
        code, port = command(["docker", "port", container, "6379/tcp"], output, "valkey-port.log")
        if code:
            raise RuntimeError("Valkey mapped port unavailable")
        mapped_port = port.decode().strip().rsplit(":", 1)[1]
        pins = json.loads((HERE.parent / "indexes/pins.json").read_text())["repositories"]
        snapshot = {repo: {"commit": item["commit"], "tree": item["tree"]} for repo, item in sorted(pins.items())}
        manifest.update({"snapshot": snapshot, "snapshot_sha256": sha(canonical(snapshot))})
        pin = pins["shipment-service"]
        archive = subprocess.check_output(["git", "-C", str(args.estate / "shipment-service"), "archive", "--format=tar", pin["commit"]])
        source = output / "baseline-source"
        source.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
            bundle.extractall(source, filter="data")
        manifest["baseline_source_files"] = files(source)
        for phase in criteria["phases"]:
            work = output / phase
            shutil.copytree(source, work)
            if phase != "baseline":
                replace_once(work / CACHE, 'return "shipment:estimate:" + hash(', 'return "shipment:estimate:v2:" + hash(')
            if phase == "faulted-repair":
                replace_once(work / SERVICE, "        return estimate;", "        return null;")
            changed = files(work)
            paths = [path for path, digest in changed.items() if manifest["baseline_source_files"].get(path) != digest]
            patch = "".join("".join(difflib.unified_diff((source / path).read_text().splitlines(True),
                            (work / path).read_text().splitlines(True), fromfile="a/" + path, tofile="b/" + path)) for path in paths)
            (output / f"{phase}.patch").write_text(patch)
            (work / TEST).parent.mkdir(parents=True)
            shutil.copyfile(test_path, work / TEST)
            observation_path = output / f"{phase}-observation.json"
            code, _ = command(["mvn", "-o", "-B", "-Dstyle.color=never", "-Dtest=Int002CacheBoundaryTest",
                               f"-Dverification.phase={phase}", f"-Dverification.observation={observation_path}",
                               f"-Dverification.valkey.port={mapped_port}", "test"], work, f"{phase}-test.log")
            if code != criteria["expected_test_exit"][phase] or not observation_path.exists():
                raise RuntimeError(f"{phase}: unexpected test exit or missing observation; infrastructure errors do not count")
            observed = json.loads(observation_path.read_text())
            if any(observed.get(key) != value for key, value in criteria["phases"][phase].items()):
                raise RuntimeError(f"{phase}: observed counters differ from criteria")
            expected = criteria["expected_estimate"]
            if observed["second_estimate"] != expected or observed["cache_errors"] != 0 or not observed["old_entry_preserved"]:
                raise RuntimeError(f"{phase}: estimate/cache integrity criteria failed")
            if observed["first_estimate"] != (None if phase == "faulted-repair" else expected):
                raise RuntimeError(f"{phase}: first estimate differs from criteria")
            report = work / "target/surefire-reports/TEST-com.poc.shipment.infrastructure.cache.Int002CacheBoundaryTest.xml"
            suite = ET.parse(report).getroot()
            if int(suite.attrib["tests"]) != 1 or int(suite.attrib["errors"]) != 0 or int(suite.attrib["failures"]) != int(phase == "faulted-repair"):
                raise RuntimeError(f"{phase}: incorrect protected JUnit result")
            if phase == "faulted-repair" and "ESTIMATE_COMPATIBILITY" not in suite.find(".//failure").attrib.get("message", ""):
                raise RuntimeError("fault failed for an unrelated reason")
            shutil.copyfile(report, output / f"{phase}-junit.xml")
            manifest["phases"][phase] = {"passed": True, "fault_detected": phase == "faulted-repair",
                                         "source_files": changed, "patch_sha256": sha(patch.encode()), "observations": observed}
        if files(source) != manifest["baseline_source_files"]:
            raise RuntimeError("baseline sources changed")
        if sha(criteria_path.read_bytes()) != args.trusted_criteria_sha256 or sha(test_path.read_bytes()) != args.trusted_test_sha256:
            raise RuntimeError("protected oracle changed during verification")
        manifest["status"] = "passed"
    except Exception as error:
        manifest.update({"status": "failed", "error": str(error)})
    finally:
        if container:
            code, _ = command(["docker", "rm", "-f", container], output, "valkey-cleanup.log")
            if code:
                manifest.update({"status": "failed", "error": "Valkey cleanup failed"})
        manifest["artifacts"] = [{"path": path.name, "sha256": sha(path.read_bytes())}
                                 for path in sorted(output.iterdir()) if path.is_file()]
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({"status": manifest["status"], "manifest": str(output / "manifest.json"), "error": manifest.get("error")}))
    return int(manifest["status"] != "passed")


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
