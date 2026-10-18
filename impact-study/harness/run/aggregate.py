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
HARNESS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCORING))
import score

METRICS = ["cross_repo_recall", "contract_recall", "precision",
           "symbol_recall", "test_recall", "composite"]


def product_ineligibility(answer, treatment):
    """Recheck participation from receipts; the runner stamp alone is insufficient."""
    label = str(answer.get("contestant", ""))
    provenance = answer.get("product_provenance")
    product = bool(provenance) or label.endswith("-first") or label in {"scip", "gortex", "graphify"}
    if not product:
        return None
    playbook = answer.get("product_playbook")
    actual_treatment = "product-first" if label.endswith("-first") or playbook else "natural-adoption"
    if treatment != "all" and actual_treatment != treatment:
        return "different product treatment: " + actual_treatment
    if answer.get("product_assisted_eligible") is not True:
        return "product-assisted run is ineligible"
    if not isinstance(provenance, dict) or provenance.get("mode") != "real_product":
        return "product provenance missing"
    if not answer.get("estate_sha256") or provenance.get("indexed_estate_sha256") != answer["estate_sha256"]:
        return "product receipt snapshot mismatch"
    receipts = answer.get("product_tool_receipts") or []
    if not receipts or any(not isinstance(r, dict) or not r.get("id") for r in receipts):
        return "product invocation receipts missing or invalid"
    by_id = {r["id"]: r for r in receipts}
    if len(by_id) != len(receipts):
        return "duplicate product receipt id"
    if not any(r.get("success") is True for r in receipts):
        return "no successful product invocation"
    if actual_treatment == "product-first":
        if not isinstance(playbook, list) or not playbook:
            return "product-first playbook missing"
        used = set()
        for step in playbook:
            receipt = by_id.get(step.get("receipt_id"))
            parameters = step.get("parameters")
            if (not receipt or receipt["id"] in used or receipt.get("tool") != step.get("tool")
                    or receipt.get("success") != step.get("ok") or not isinstance(parameters, dict)
                    or not isinstance(receipt.get("parameters"), dict)
                    or any(key not in receipt["parameters"] or receipt["parameters"][key] != value
                           for key, value in parameters.items())):
                return "playbook step has no matching invocation receipt"
            used.add(receipt["id"])
    return None


def collect(runs_dir, treatment="product-first"):
    import hashlib

    cells = defaultdict(list)
    rejected = []
    for report_file in sorted(Path(runs_dir).glob("*.score.json")):
        runner = None
        change_id = None
        def reject(reason, status="invalid_output", **extra):
            rejected.append({"report": report_file.name, "reason": reason,
                             "runner": runner, "change_id": change_id, "status": status, **extra})
        try:
            report = json.loads(report_file.read_text())
            change_id = report.get("change_id")
            runner = report.get("contestant", "unknown")
            answer_file = report_file.with_name(report_file.name.replace(".score", ""))
            if not answer_file.exists():
                reject("answer file missing")
                continue
            answer_text = answer_file.read_bytes()
            answer_doc = json.loads(answer_text.decode("utf-8"))
            runner = answer_doc.get("runner", runner)
            if answer_doc.get("synthetic") is True:
                reject("synthetic example must never enter a scorecard")
                continue
            protocol = answer_doc.get("protocol_version", "v1")
            report_protocol = report.get("protocol_version", "v1")
            if protocol != report_protocol:
                reject("answer/report protocol mismatch")
                continue
            if protocol != "v1":
                reject("unsupported protocol: " + str(protocol))
                continue
            validation = score.validate_files("answer", [str(answer_file)])
            if not validation["valid"]:
                reject("answer schema validation failed", errors=validation["errors"].get(answer_file.name, []))
                continue
            if answer_doc.get("change_id") != change_id or answer_doc.get("contestant") != report.get("contestant"):
                reject("answer/report identity mismatch")
                continue
            expected_sha = report.get("answer_sha256")
            if not expected_sha and answer_doc.get("product_provenance"):
                reject("missing integrity hash: answer_sha256")
                continue
            if expected_sha and hashlib.sha256(answer_text).hexdigest() != expected_sha:
                reject("stale score: answer hash mismatch")
                continue
            integrity_targets = {
                "ground_truth_sha256": HARNESS / "records" / f"{change_id}.json",
                "scorer_sha256": HARNESS / "scoring" / "score.py",
                "answer_schema_sha256": HARNESS / "schema" / "contestant-answer.schema.json",
            }
            integrity_failed = False
            for field, target in integrity_targets.items():
                expected = report.get(field)
                if expected is None:
                    if answer_doc.get("product_provenance") or str(runner).endswith("-real") or str(answer_doc.get("contestant", "")).endswith("-first"):
                        reject(f"missing integrity hash: {field}")
                        integrity_failed = True
                        break
                    continue
                if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
                    reject(f"stale score: {field} mismatch")
                    integrity_failed = True
                    break
            if integrity_failed:
                continue
            ineligible = product_ineligibility(answer_doc, treatment)
            if ineligible:
                reject(ineligible, "ineligible_product")
                continue
            cohort_fields = {
                "protocol_version": protocol,
                "treatment": "product-first" if answer_doc.get("product_playbook") else "natural-adoption" if answer_doc.get("product_provenance") else "baseline",
                **{field: answer_doc.get(field) for field in (
                    "prompt_sha256", "contestant_config_sha256", "estate_sha256", "index_sha256",
                    "harness_sha256", "model", "model_digest", "pi_version")},
                **{field: report.get(field) for field in integrity_targets},
            }
            cohort = hashlib.sha256(json.dumps(cohort_fields, sort_keys=True).encode()).hexdigest()
            cells[(change_id, runner, cohort)].append(report)
        except (ValueError, TypeError, KeyError, AttributeError, OSError) as exc:
            reject(f"unreadable or malformed run artifact: {exc}")
    for stub_file in sorted(Path(runs_dir).glob("*.rejected.json")):
        try:
            stub = json.loads(stub_file.read_text())
        except Exception as exc:
            rejected.append({"report": stub_file.name, "reason": f"unreadable rejection stub: {exc}", "status": "invalid_output"})
            continue
        rejected.append({
            "report": stub_file.name,
            "reason": stub.get("reason", "run failed"),
            "change_id": stub.get("change_id"),
            "runner": stub.get("runner"),
            "status": stub.get("status", "run_failed"),
        })
    return cells, rejected


def outcome_summary(cells, rejected):
    scored = defaultdict(int)
    failed = defaultdict(int)
    statuses = defaultdict(lambda: defaultdict(int))
    for (_change_id, runner, _cohort), reports in cells.items():
        scored[runner] += len(reports)
    for item in rejected:
        runner = item.get("runner")
        if runner:
            failed[runner] += 1
            statuses[runner][item.get("status", "run_failed")] += 1
    result = {}
    for runner in sorted(set(scored) | set(failed)):
        total = scored[runner] + failed[runner]
        result[runner] = {
            "scored": scored[runner],
            "rejected": failed[runner],
            "attempts": total,
            "failure_states": dict(statuses[runner]),
            "success_rate": round(scored[runner] / total, 4) if total else 0.0,
        }
    return result


def summarize(reports):
    summary = {"runs": len(reports)}
    for metric in METRICS:
        values = [r["metrics"][metric] for r in reports if metric in r["metrics"]]
        if not values:
            continue
        entry = {
            "median": round(statistics.median(values), 4),
            "min": round(min(values), 4),
            "max": round(max(values), 4),
            "spread": round(max(values) - min(values), 4),
        }
        if len(reports) <= 3:
            entry["values"] = [round(v, 4) for v in values]
        summary[metric] = entry
    severities = defaultdict(int)
    for report in reports:
        for severity, count in report["counts"]["missed_by_severity"].items():
            severities[severity] = max(severities[severity], count)
    summary["worst_missed_by_severity"] = dict(severities)

    unstable = defaultdict(int)
    for report in reports:
        for miss in report["missed"]:
            unstable[(miss["kind"], miss["key"])] += 1
    summary["missed_in_all_runs"] = sorted(
        f"{kind} {key}" for (kind, key), count in unstable.items() if count == len(reports))
    summary["missed_in_some_runs"] = sorted(
        f"{kind} {key}" for (kind, key), count in unstable.items() if 0 < count < len(reports))
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
    if scorecard.get("outcomes"):
        lines.append("")
        lines.append("run outcomes")
        for runner, outcome in scorecard["outcomes"].items():
            lines.append(
                f"  {runner}  {outcome['scored']}/{outcome['attempts']} scored; "
                f"{outcome['rejected']} rejected; success {outcome['success_rate']:.0%}"
            )
    return "\n".join(lines).rstrip()


def render_metrics(summary):
    lines = []
    for metric in METRICS:
        if metric not in summary:
            continue
        value = summary[metric]
        if summary["runs"] <= 3 and "values" in value:
            rendered = ",".join(f"{v:.2f}" for v in value["values"])
            lines.append(f"    {metric:<20}{value['median']:.2f}   runs [{rendered}]")
        else:
            spread = (
                f"   varies {value['min']:.2f}-{value['max']:.2f} across runs"
                if value["spread"]
                else ""
            )
            lines.append(f"    {metric:<20}{value['median']:.2f}{spread}")
    return lines


def render_misses(summary):
    lines = []
    if summary["runs"] < 3:
        keys = summary["missed_in_all_runs"]
        if keys:
            lines.append("    missed:")
            lines += [f"      {key}" for key in keys]
        return lines
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
    parser.add_argument("--treatment", choices=["product-first", "natural-adoption", "all"], default="product-first",
                        help="product treatment included; baseline is always included")
    parser.add_argument("--json", action="store_true", help="print JSON instead of text")
    args = parser.parse_args()

    cells, rejected = collect(args.runs_dir, args.treatment)
    if not cells and not rejected:
        raise SystemExit(f"no run artifacts under {args.runs_dir}")

    scorecard = {"cells": {}, "by_contestant": {}, "rejected": rejected, "outcomes": outcome_summary(cells, rejected)}
    per_contestant = defaultdict(list)
    cohort_counts = defaultdict(int)
    for change_id, runner, _cohort in cells:
        cohort_counts[(change_id, runner)] += 1
    for (change_id, runner, cohort), reports in sorted(cells.items()):
        summary = summarize(reports)
        summary["cohort_sha256"] = cohort
        display_runner = runner if cohort_counts[(change_id, runner)] == 1 else f"{runner}@{cohort[:8]}"
        scorecard["cells"].setdefault(change_id, {})[display_runner] = summary
        per_contestant[(runner, cohort)].extend(reports)

    runner_cohorts = defaultdict(int)
    for runner, _cohort in per_contestant:
        runner_cohorts[runner] += 1
    for (runner, cohort), reports in sorted(per_contestant.items()):
        summary = summarize(reports)
        summary["records"] = len({r["change_id"] for r in reports})
        summary["cohort_sha256"] = cohort
        display_runner = runner if runner_cohorts[runner] == 1 else f"{runner}@{cohort[:8]}"
        scorecard["by_contestant"][display_runner] = summary

    document = json.dumps(scorecard, indent=2)
    if args.output:
        Path(args.output).write_text(document + "\n")
    print(document if args.json else render_scorecard(scorecard))


if __name__ == "__main__":
    main()
