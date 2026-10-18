#!/usr/bin/env python3
"""Bounded real-Kafka topic-rename oracle; all builds execute in pinned archives."""
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

TOPIC = Path("order-service/src/main/java/com/poc/order/application/EventEnvelope.java")
SHIPMENT_LISTENER = Path("shipment-service/src/main/java/com/poc/shipment/infrastructure/messaging/OrderConfirmedListener.java")
BASKET_LISTENER = Path("basket-service/src/main/java/com/poc/basket/infrastructure/messaging/OrderConfirmedListener.java")
TEST = Path("src/test/java/com/poc/shipment/verification/Kafka001BoundaryTest.java")


def run(args):
    output = args.output.resolve()
    if inside(output, args.estate) or inside(output, HERE.parent.parent) or output.exists():
        raise ValueError("output must be new and outside the estate/study")
    criteria_path, test_path = HERE / "kafka001/criteria.json", HERE / "kafka001/Kafka001BoundaryTest.java"
    if sha(criteria_path.read_bytes()) != args.trusted_criteria_sha256 or sha(test_path.read_bytes()) != args.trusted_test_sha256:
        raise ValueError("reviewed oracle digest mismatch")
    output.mkdir(parents=True)
    shutil.copyfile(criteria_path, output / "criteria.json")
    shutil.copyfile(test_path, output / "Kafka001BoundaryTest.java")
    criteria = json.loads(criteria_path.read_text())
    manifest = {"protocol_version": 2, "scenario": criteria["scenario"], "status": "running",
                "criteria_sha256": args.trusted_criteria_sha256, "test_sha256": args.trusted_test_sha256,
                "verifier_sha256": sha(Path(__file__).read_bytes()), "commands": [], "phases": {}}
    env = os.environ.copy()
    if args.java_home:
        env["JAVA_HOME"] = str(args.java_home.resolve())
        env["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + env["PATH"]
    for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(key, None)
    if args.docker_host:
        env["DOCKER_HOST"] = args.docker_host

    def command(argv, cwd, name):
        try:
            result = subprocess.run(argv, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            code, raw = result.returncode, result.stdout
        except subprocess.TimeoutExpired as error:
            code, raw = 124, (error.stdout or b"") + b"\nVERIFIER COMMAND TIMEOUT\n"
        (output / name).write_bytes(raw)
        manifest["commands"].append({"argv": argv, "cwd": str(cwd), "exit_code": code, "log": name, "log_sha256": sha(raw)})
        return code

    try:
        for cmd, name in [(["java", "-version"], "java-version.log"), (["mvn", "-version"], "maven-version.log"),
                          (["docker", "image", "inspect", "apache/kafka:4.0.0", "--format", "{{.Id}}"], "kafka-image.log")]:
            if command(cmd, output, name):
                raise RuntimeError(f"{name}: preflight failed (no image pulls attempted)")
        pins = json.loads((HERE.parent / "indexes/pins.json").read_text())["repositories"]
        snapshot = {repo: {"commit": item["commit"], "tree": item["tree"]} for repo, item in sorted(pins.items())}
        manifest.update({"snapshot": snapshot, "snapshot_sha256": sha(canonical(snapshot))})
        source = output / "baseline-source"
        source.mkdir()
        for repo in ("order-service", "basket-service", "shipment-service"):
            target = source / repo
            target.mkdir()
            archive = subprocess.check_output(["git", "-C", str(args.estate / repo), "archive", "--format=tar", pins[repo]["commit"]])
            with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
                bundle.extractall(target, filter="data")
        manifest["baseline_source_files"] = files(source)
        for phase in criteria["phases"]:
            work = output / phase
            shutil.copytree(source, work)
            if phase != "baseline":
                replace_once(work / TOPIC, 'return eventType + ".v1";',
                             'return eventType.equals("order.confirmed") ? "order.confirmed.v2" : eventType + ".v1";')
            if phase in ("repaired", "faulted-repair"):
                replace_once(work / SHIPMENT_LISTENER, 'topics = "order.confirmed.v1"', 'topics = "order.confirmed.v2"')
            if phase == "repaired":
                replace_once(work / BASKET_LISTENER, '            "order.confirmed.v1",', '            "order.confirmed.v2",')
            changed = files(work)
            paths = [path for path, digest in changed.items() if manifest["baseline_source_files"].get(path) != digest]
            patch = "".join("".join(difflib.unified_diff((source / path).read_text().splitlines(True),
                            (work / path).read_text().splitlines(True), fromfile="a/" + path, tofile="b/" + path)) for path in paths)
            (output / f"{phase}.patch").write_text(patch)
            shipment = work / "shipment-service"
            # Compile original classes together using Shipment's existing Spring/Kafka/JUnit dependencies.
            selected = [work / "order-service/src/main/java/com/poc/order" / path for path in (
                "application/EventEnvelope.java", "application/port/OutboxRepository.java", "infrastructure/messaging/OutboxPublisher.java")]
            basket = work / "basket-service/src/main/java/com/poc/basket"
            selected += [basket / path for path in ("application/BasketService.java", "application/BasketPricing.java",
                        "infrastructure/messaging/OrderConfirmedListener.java", "infrastructure/messaging/RetryRouter.java",
                        "infrastructure/messaging/InvalidMessageException.java")]
            selected += list((basket / "application/port").glob("*.java")) + list((basket / "domain").rglob("*.java"))
            for original in selected:
                relative = Path(str(original).split("/src/main/java/", 1)[1])
                target = shipment / "src/test/java" / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(original, target)
            (shipment / TEST).parent.mkdir(parents=True)
            shutil.copyfile(test_path, shipment / TEST)
            observation = output / f"{phase}-observation.json"
            code = command(["mvn", "-o", "-B", "-Dstyle.color=never", "-Dtest=Kafka001BoundaryTest",
                            f"-Dverification.phase={phase}", f"-Dverification.observation={observation}", "test"],
                           shipment, f"{phase}-test.log")
            if code != criteria["expected_test_exit"][phase] or not observation.exists():
                raise RuntimeError(f"{phase}: unexpected exit {code} or no observation; infra failure is not scenario evidence")
            observed = json.loads(observation.read_text())
            if any(observed.get(key) != value for key, value in criteria["phases"][phase].items()):
                raise RuntimeError(f"{phase}: observations differ from acceptance criteria")
            if observed["broker_records"] != 1 or observed["publisher_acknowledged"] != 1 or not observed["consumer_liveness_control_passed"]:
                raise RuntimeError(f"{phase}: producer/broker/consumer liveness checks failed")
            report = shipment / "target/surefire-reports/TEST-com.poc.shipment.verification.Kafka001BoundaryTest.xml"
            suite = ET.parse(report).getroot()
            if int(suite.attrib["tests"]) != 1 or int(suite.attrib["errors"]) != 0 or int(suite.attrib["failures"]) != int(phase == "faulted-repair"):
                raise RuntimeError(f"{phase}: protected JUnit result mismatch")
            if phase == "faulted-repair" and "BASKET_CONSUMER_DELIVERY" not in suite.find(".//failure").attrib.get("message", ""):
                raise RuntimeError("fault failed for an unrelated reason")
            shutil.copyfile(report, output / f"{phase}-junit.xml")
            manifest["phases"][phase] = {"passed": True, "fault_detected": phase == "faulted-repair",
                                         "source_files": changed, "patch_sha256": sha(patch.encode()), "observations": observed}
        if files(source) != manifest["baseline_source_files"] or sha(test_path.read_bytes()) != args.trusted_test_sha256 or sha(criteria_path.read_bytes()) != args.trusted_criteria_sha256:
            raise RuntimeError("protected sources or oracle changed during verification")
        manifest["status"] = "passed"
    except Exception as error:
        manifest.update({"status": "failed", "error": str(error)})
    finally:
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
    parser.add_argument("--docker-host")
    parser.add_argument("--trusted-criteria-sha256", required=True)
    parser.add_argument("--trusted-test-sha256", required=True)
    try:
        sys.exit(run(parser.parse_args()))
    except (ValueError, OSError) as error:
        parser.exit(2, str(error) + "\n")
