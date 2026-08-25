#!/usr/bin/env python3
"""Roll up per-run score reports into a corpus scorecard.

Reports median and spread per (record, contestant), because a single agent run
measures luck as much as capability. Spread is the reproducibility signal the
architecture proposal asks for.
"""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

RUNS = Path(__file__).resolve().parent.parent / "answers" / "runs"
METRICS = ["cross_repo_recall", "contract_recall", "precision",
           "symbol_recall", "test_recall", "composite"]


def collect(runs_dir):
    cells = defaultdict(list)
    for report_file in sorted(Path(runs_dir).glob("*.score.json")):
        report = json.loads(report_file.read_text())
        answer_file = report_file.with_name(report_file.name.replace(".score", ""))
        runner = report.get("contestant", "unknown")
        if answer_file.exists():
            runner = json.loads(answer_file.read_text()).get("runner", runner)
        cells[(report["change_id"], runner)].append(report)
    return cells


def summarize(reports):
    summary = {"runs": len(reports)}
    for metric in METRICS:
        values = [r["metrics"][metric] for r in reports if metric in r["metrics"]]
        if not values:
            continue
        summary[metric] = {
            "median": round(statistics.median(values), 4),
            "min": round(min(values), 4),
            "max": round(max(values), 4),
            "spread": round(max(values) - min(values), 4),
        }
    severities = defaultdict(int)
    for report in reports:
        for severity, count in report["counts"]["missed_by_severity"].items():
            severities[severity] = max(severities[severity], count)
    summary["worst_missed_by_severity"] = dict(severities)

    unstable = defaultdict(int)
    for report in reports:
        for miss in report["missed"]:
            unstable[miss["key"]] += 1
    summary["missed_in_all_runs"] = sorted(
        key for key, count in unstable.items() if count == len(reports))
    summary["missed_in_some_runs"] = sorted(
        key for key, count in unstable.items() if 0 < count < len(reports))
    return summary


def main():
    parser = argparse.ArgumentParser(description="Corpus scorecard across runs.")
    parser.add_argument("--runs-dir", default=str(RUNS))
    parser.add_argument("--output")
    args = parser.parse_args()

    cells = collect(args.runs_dir)
    if not cells:
        raise SystemExit(f"no .score.json files under {args.runs_dir}")

    scorecard = {"cells": {}, "by_contestant": {}}
    per_contestant = defaultdict(list)
    for (change_id, runner), reports in sorted(cells.items()):
        summary = summarize(reports)
        scorecard["cells"].setdefault(change_id, {})[runner] = summary
        per_contestant[runner].extend(reports)

    for runner, reports in sorted(per_contestant.items()):
        summary = summarize(reports)
        summary["records"] = len({r["change_id"] for r in reports})
        scorecard["by_contestant"][runner] = summary

    text = json.dumps(scorecard, indent=2)
    if args.output:
        Path(args.output).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
