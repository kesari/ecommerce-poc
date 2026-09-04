#!/usr/bin/env python3
"""Pin verification and deterministic manifests for real product indexes."""

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent
PINS_FILE = ROOT / "pins.json"


def load_pins():
    return json.loads(PINS_FILE.read_text())


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def git(repository, *args):
    result = subprocess.run(
        ["git", "-C", str(repository), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "git command failed")
    return result.stdout.strip()


def nested_value(document, dotted_key):
    value = document
    for key in dotted_key.split("."):
        value = value[key]
    return value


def verify_estate(estate):
    pins = load_pins()["repositories"]
    failures = []
    for name, expected in pins.items():
        repository = estate / name
        if not repository.is_dir():
            failures.append(f"{name}: repository is missing")
            continue
        try:
            head = git(repository, "rev-parse", "HEAD")
            dirty = bool(git(repository, "status", "--porcelain", "--untracked-files=all"))
            git(repository, "cat-file", "-e", f"{expected['commit']}^{{commit}}")
        except RuntimeError as error:
            failures.append(f"{name}: {error}")
            continue
        if head != expected["commit"]:
            failures.append(f"{name}: HEAD {head} != pinned {expected['commit']}")
        if dirty != expected["dirty"]:
            failures.append(f"{name}: dirty={str(dirty).lower()} != pinned dirty={str(expected['dirty']).lower()}")
    if failures:
        raise SystemExit("estate pin verification failed:\n  " + "\n  ".join(failures))
    print(f"verified {len(pins)} repositories against {PINS_FILE}")


def package_tree_sha(path):
    root = Path(path).resolve()
    digest = hashlib.sha256()
    for file in sorted(root.rglob("*.py")):
        digest.update(str(file.relative_to(root)).encode())
        digest.update(b"\0")
        digest.update(file.read_bytes())
        digest.update(b"\0")
    print(digest.hexdigest())


def verify_gortex(estate, binary, require_daemon):
    pins = load_pins()
    expected_tool = pins["toolchain"]
    if sha256_file(binary) != expected_tool["gortex-sha256"]:
        raise SystemExit("Gortex binary does not match its pinned SHA-256")
    version = subprocess.run(
        [str(binary), "version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    if version.returncode or expected_tool["gortex"] not in version.stdout:
        raise SystemExit(f"Gortex version does not match {expected_tool['gortex']}")
    if require_daemon:
        daemon = subprocess.run(
            [str(binary), "daemon", "status"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if daemon.returncode:
            raise SystemExit("Gortex daemon is not running; run setup-gortex.sh")
    repos = subprocess.run(
        [str(binary), "repos", "--json"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if repos.returncode:
        raise SystemExit(repos.stderr.strip() or "could not inspect Gortex repositories")
    indexed = {item["name"]: item for item in json.loads(repos.stdout)}
    failures = []
    for name, expected in pins["repositories"].items():
        item = indexed.get(name)
        if item is None:
            failures.append(f"{name}: not tracked")
            continue
        if Path(item["path"]).resolve() != (estate / name).resolve():
            failures.append(f"{name}: tracked path is {item['path']}")
        if item.get("workspace") != expected_tool["gortex-workspace"]:
            failures.append(f"{name}: workspace is {item.get('workspace')}")
        if item.get("head_commit") != expected["commit"]:
            failures.append(f"{name}: tracked HEAD is {item.get('head_commit')}")
        if item.get("indexed_commit") != expected["commit"] or item.get("stale"):
            failures.append(f"{name}: index is stale or was built at another commit")
    extras = sorted(set(indexed) - set(pins["repositories"]))
    if extras:
        failures.append(f"unexpected tracked repositories: {', '.join(extras)}")
    if failures:
        raise SystemExit("Gortex verification failed:\n  " + "\n  ".join(failures))
    print(f"verified Gortex {expected_tool['gortex']} over {len(indexed)} fresh repositories")


def write_manifest(product, artifacts, metadata, output):
    pins = load_pins()
    artifact_records = []
    for raw in artifacts:
        name, raw_path = raw.split("=", 1)
        path = Path(raw_path).resolve()
        artifact_records.append({
            "name": name,
            "path": str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path),
            "sha256": sha256_file(path),
            "bytes": path.stat().st_size,
        })
    values = {}
    for raw in metadata:
        key, value = raw.split("=", 1)
        try:
            values[key] = json.loads(value)
        except json.JSONDecodeError:
            values[key] = value
    manifest = {
        "schema_version": 1,
        "product": product,
        "pins_sha256": sha256_file(PINS_FILE),
        "repository_revisions": {
            name: item["commit"] for name, item in pins["repositories"].items()
        },
        "artifacts": artifact_records,
        "metadata": values,
    }
    target = Path(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    print(target)


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    verify = commands.add_parser("verify-estate")
    verify.add_argument("--estate", required=True, type=Path)
    pin = commands.add_parser("pin")
    pin.add_argument("key")
    tree = commands.add_parser("package-tree-sha")
    tree.add_argument("path")
    gortex = commands.add_parser("verify-gortex")
    gortex.add_argument("--estate", required=True, type=Path)
    gortex.add_argument("--binary", required=True, type=Path)
    gortex.add_argument("--require-daemon", action="store_true")
    manifest = commands.add_parser("manifest")
    manifest.add_argument("--product", required=True)
    manifest.add_argument("--artifact", action="append", default=[])
    manifest.add_argument("--metadata", action="append", default=[])
    manifest.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "verify-estate":
        verify_estate(args.estate.resolve())
    elif args.command == "pin":
        value = nested_value(load_pins(), args.key)
        print(json.dumps(value) if isinstance(value, (dict, list)) else value)
    elif args.command == "package-tree-sha":
        package_tree_sha(args.path)
    elif args.command == "verify-gortex":
        verify_estate(args.estate.resolve())
        verify_gortex(args.estate.resolve(), args.binary.resolve(), args.require_daemon)
    else:
        write_manifest(args.product, args.artifact, args.metadata, args.output)


if __name__ == "__main__":
    try:
        main()
    except (KeyError, OSError, RuntimeError, ValueError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
