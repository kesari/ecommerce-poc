#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HARNESS = Path(__file__).resolve().parent.parent
RUN_DIR = HARNESS / "run"
RECORDS = HARNESS / "records"
RUNS = HARNESS / "answers" / "runs"
SCORER = HARNESS / "scoring" / "score.py"
DEFAULT_ESTATE = HARNESS.parent.parent / "POC-order-microservices"


def load(path):
    with open(path) as handle:
        return json.load(handle)


def build_prompt(record):
    template = (RUN_DIR / "prompt-template.md").read_text()
    change = record.get("proposed_change", {})
    return (template
            .replace("{{QUERY}}", record["query"])
            .replace("{{CHANGE_REPO}}", change.get("repo", "unspecified"))
            .replace("{{CHANGE_DIFF}}", change.get("diff", "(not supplied)"))
            .replace("{{CHANGE_ID}}", record["change_id"]))


def isolate(estate, scratch):
    """Copy the estate so the agent cannot reach harness/records."""
    target = Path(scratch) / "estate"
    shutil.copytree(
        estate, target,
        ignore=shutil.ignore_patterns("target", "node_modules", ".git", "dist", "build"))
    return target


def json_objects(text):
    """Yield every balanced {...} span, longest-first at each start position."""
    for start, char in enumerate(text):
        if char != "{":
            continue
        depth, in_string, escaped = 0, False, False
        for index in range(start, len(text)):
            current = text[index]
            if in_string:
                if escaped:
                    escaped = False
                elif current == "\\":
                    escaped = True
                elif current == '"':
                    in_string = False
                continue
            if current == '"':
                in_string = True
            elif current == "{":
                depth += 1
            elif current == "}":
                depth -= 1
                if depth == 0:
                    yield text[start:index + 1]
                    break


def extract_json(raw, mode):
    if mode == "claude-json":
        try:
            envelope = json.loads(raw)
        except json.JSONDecodeError:
            pass
        else:
            if isinstance(envelope, dict) and isinstance(envelope.get("result"), str):
                raw = envelope["result"]
            elif isinstance(envelope, dict) and "findings" in envelope:
                return envelope
    if isinstance(raw, dict):
        return raw
    best = None
    for candidate in json_objects(raw):
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict) and "findings" in parsed:
            best = parsed
    if best is not None:
        return best
    raise ValueError("no answer object with a 'findings' key in the output")


def run_once(record, contestant_name, contestant, estate, index, timeout):
    prompt = build_prompt(record)
    with tempfile.TemporaryDirectory(prefix="poc-run-") as scratch:
        workdir = isolate(estate, scratch)
        command = [part
                   .replace("@WORKDIR@", str(workdir))
                   .replace("@PROMPT_TEXT@", prompt)
                   for part in contestant["command"]]
        started = time.monotonic()
        completed = subprocess.run(command, cwd=workdir, capture_output=True,
                                   text=True, timeout=timeout)
        elapsed = round(time.monotonic() - started, 1)

    if completed.returncode != 0:
        raise RuntimeError(f"{contestant_name} exited {completed.returncode}: "
                           f"{completed.stderr.strip()[:400]}")

    try:
        answer = extract_json(completed.stdout, contestant["extract"])
    except ValueError:
        raw_path = RUNS / f"{record['change_id']}-{contestant_name}-{index}.raw.txt"
        RUNS.mkdir(parents=True, exist_ok=True)
        raw_path.write_text(completed.stdout)
        raise ValueError(f"could not parse an answer; raw output saved to {raw_path}")
    answer["change_id"] = record["change_id"]
    answer["contestant"] = contestant["label"]
    answer["runner"] = contestant_name
    answer["run_index"] = index
    answer["elapsed_seconds"] = elapsed
    answer.setdefault("findings", {})
    return answer


def score(answer_path, record_path):
    report_path = answer_path.with_suffix(".score.json")
    subprocess.run([sys.executable, "-B", str(SCORER), "score",
                    "--ground-truth", str(record_path),
                    "--answer", str(answer_path),
                    "--output", str(report_path)],
                   check=True, capture_output=True, text=True)
    return load(report_path)


def main():
    parser = argparse.ArgumentParser(
        description="Run contestants blind against frozen ground-truth records.")
    parser.add_argument("--record", action="append", required=True,
                        help="change id, or 'all'")
    parser.add_argument("--contestant", action="append",
                        help="contestant name; repeatable. default: all defined")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--estate", default=str(DEFAULT_ESTATE))
    parser.add_argument("--timeout", type=int, default=1800)
    args = parser.parse_args()

    contestants = load(RUN_DIR / "contestants.json")
    chosen = args.contestant or list(contestants)
    unknown = [name for name in chosen if name not in contestants]
    if unknown:
        raise SystemExit(f"unknown contestant(s): {unknown}")

    if "all" in args.record:
        record_paths = sorted(RECORDS.glob("*.json"))
    else:
        record_paths = [RECORDS / f"{cid}.json" for cid in args.record]

    estate = Path(args.estate).resolve()
    if not estate.is_dir():
        raise SystemExit(f"estate not found: {estate}")
    RUNS.mkdir(parents=True, exist_ok=True)

    outcomes = []
    for record_path in record_paths:
        record = load(record_path)
        if record.get("status") != "frozen":
            print(f"skipping {record['change_id']}: status is "
                  f"{record.get('status', 'unset')}, only frozen records are scored")
            continue
        for name in chosen:
            for index in range(1, args.runs + 1):
                stem = f"{record['change_id']}-{name}-{index}"
                print(f"running {stem} ...", flush=True)
                try:
                    answer = run_once(record, name, contestants[name],
                                      estate, index, args.timeout)
                except Exception as error:
                    print(f"  FAILED: {error}")
                    outcomes.append({"run": stem, "error": str(error)})
                    continue
                answer_path = RUNS / f"{stem}.json"
                answer_path.write_text(json.dumps(answer, indent=2) + "\n")
                report = score(answer_path, record_path)
                metrics = report["metrics"]
                print(f"  recall {metrics['cross_repo_recall']}  "
                      f"precision {metrics['precision']}  "
                      f"composite {metrics['composite']}  "
                      f"{answer['elapsed_seconds']}s")
                outcomes.append({"run": stem, **metrics})

    print(json.dumps({"outcomes": outcomes}, indent=2))
    if any("error" in outcome for outcome in outcomes):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
