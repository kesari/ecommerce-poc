#!/usr/bin/env python3
"""Roll up per-run score reports into a corpus scorecard.

Reports median and spread per (record, contestant), because a single agent run
measures luck as much as capability. Spread is the reproducibility signal the
architecture proposal asks for.
"""
import argparse
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

RUNS = Path(__file__).resolve().parent.parent / "answers" / "runs"
SCORING = Path(__file__).resolve().parent.parent / "scoring"
sys.path.insert(0, str(SCORING))
import score

METRICS = ["cross_repo_recall", "contract_recall", "precision",
           "symbol_recall", "test_recall", "composite"]


def collect(runs_dir):
    cells = defaultdict(list)
    rejected = []
    for report_file in sorted(Path(runs_dir).glob("*.score.json")):
        report = json.loads(report_file.read_text())
        answer_file = report_file.with_name(report_file.name.replace(".score", ""))
        if not answer_file.exists():
            rejected.append({"report": report_file.name, "reason": "answer file missing"})
            continue
        validation = score.validate_files("answer", [str(answer_file)])
        if not validation["valid"]:
            rejected.append({
                "report": report_file.name,
                "reason": "answer schema validation failed",
                "errors": validation["errors"].get(answer_file.name, []),
            })
            continue
        runner = report.get("contestant", "unknown")
        runner = json.loads(answer_file.read_text()).get("runner", runner)
        cells[(report["change_id"], runner)].append(report)
    return cells, rejected


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


def render_scorecard(scorecard):
    """Human-readable scorecard: score per cell, then the misses that repeat."""
    lines = []
    for change_id, runners in scorecard["cells"].items():
        lines.append(change_id)
        for runner, summary in runners.items():
            lines += [f"  {runner}  ({summary['runs']} run(s))"]
            lines += render_metrics(summary)
            lines += render_misses(summary)
        lines.append("")

    if len(scorecard["by_contestant"]) > 1 or len(scorecard["cells"]) > 1:
        lines.append("across all records")
        for runner, summary in scorecard["by_contestant"].items():
            lines.append(f"  {runner}  ({summary['runs']} run(s), {summary['records']} record(s))")
            lines += render_metrics(summary)
        lines.append("")

    for entry in scorecard["rejected"]:
        lines.append(f"rejected {entry['report']}: {entry['reason']}")
    return "\n".join(lines).rstrip()


def render_metrics(summary):
    lines = []
    for metric in METRICS:
        if metric not in summary:
            continue
        value = summary[metric]
        spread = (
            f"   varies {value['min']:.2f}-{value['max']:.2f} across runs"
            if value["spread"]
            else ""
        )
        lines.append(f"    {metric:<20}{value['median']:.2f}{spread}")
    return lines


def render_misses(summary):
    lines = []
    for label, keys in (
        ("blind spots, missed in every run", summary["missed_in_all_runs"]),
        ("unstable, missed in some runs", summary["missed_in_some_runs"]),
    ):
        if not keys:
            continue
        lines.append(f"    {label}:")
        lines += [f"      {key}" for key in keys]
    return lines


def main():
    parser = argparse.ArgumentParser(description="Corpus scorecard across runs.")
    parser.add_argument("--runs-dir", default=str(RUNS))
    parser.add_argument("--output", help="write the full JSON scorecard here")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    args = parser.parse_args()

    cells, rejected = collect(args.runs_dir)
    if not cells:
        reason = f"; rejected {len(rejected)} invalid report(s)" if rejected else ""
        raise SystemExit(f"no valid .score.json files under {args.runs_dir}{reason}")

    scorecard = {"cells": {}, "by_contestant": {}, "rejected": rejected}
    per_contestant = defaultdict(list)
    for (change_id, runner), reports in sorted(cells.items()):
        summary = summarize(reports)
        scorecard["cells"].setdefault(change_id, {})[runner] = summary
        per_contestant[runner].extend(reports)

    for runner, reports in sorted(per_contestant.items()):
        summary = summarize(reports)
        summary["records"] = len({r["change_id"] for r in reports})
        scorecard["by_contestant"][runner] = summary

    document = json.dumps(scorecard, indent=2)
    if args.output:
        Path(args.output).write_text(document + "\n")
    print(document if args.json else render_scorecard(scorecard))


if __name__ == "__main__":
    main()
