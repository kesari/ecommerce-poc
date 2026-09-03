#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path

EVIDENCE_TIERS = {"compiler", "extracted", "contract_matched", "inferred", "hypothesis"}

CRITICALITY_TO_SEVERITY = {
    "critical": "critical_runtime_dependency",
    "contract_consumer": "contract_consumer",
    "test_only": "test_suite",
    "informational": "informational",
}

WEIGHTS = {
    "cross_repo_recall": 0.35,
    "contract_recall": 0.20,
    "precision": 0.15,
    "evidence_quality": 0.10,
    "freshness": 0.10,
    "latency": 0.05,
    "operational_cost": 0.05,
}


def norm(value):
    return " ".join(str(value).lower().split())


def load(path):
    path = Path(path)
    if not path.exists():
        raise SystemExit(f"file not found: {path}")
    with path.open() as handle:
        return json.load(handle)


def require(obj, key, context):
    if key not in obj:
        raise SystemExit(f"{context}: missing required field '{key}'")
    return obj[key]


def gt_index(gt):
    root = require(gt, "ground_truth", gt.get("change_id", "ground truth"))
    repos = {}
    for item in root.get("affected_repositories", []):
        repos[norm(require(item, "name", "affected_repositories"))] = CRITICALITY_TO_SEVERITY[
            require(item, "criticality", "affected_repositories")
        ]
    symbols = {}
    for item in root.get("affected_symbols", []):
        symbols[norm(require(item, "fqn", "affected_symbols"))] = item.get(
            "severity", "informational"
        )
    contracts = {}
    for item in root.get("affected_contracts", []):
        contracts[contract_key(item)] = item.get("severity", "contract_consumer")
    tests = {}
    for item in root.get("required_tests", []):
        tests[test_key(item)] = "test_suite"
    return {
        "repos": repos,
        "symbols": symbols,
        "contracts": contracts,
        "tests": tests,
    }


def contract_key(item):
    return (
        norm(require(item, "type", "contract")),
        norm(require(item, "identifier", "contract")),
    )


def test_key(item):
    return norm(require(item, "repo", "test")), norm(require(item, "suite", "test"))


def findings(answer):
    return require(answer, "findings", answer.get("contestant", "answer"))


def evidence_quality(answer):
    total = 0
    evidenced = 0
    distribution = {}
    for items in findings(answer).values():
        for item in items:
            total += 1
            tier = item.get("evidence_tier")
            has_evidence = bool(str(item.get("evidence") or "").strip())
            if tier in EVIDENCE_TIERS and has_evidence:
                evidenced += 1
            if tier in EVIDENCE_TIERS:
                distribution[tier] = distribution.get(tier, 0) + 1
            else:
                distribution["unclassified"] = distribution.get("unclassified", 0) + 1
    ratio = evidenced / total if total else 0.0
    return ratio, distribution


SCHEMA_DIR = Path(__file__).resolve().parent.parent / "schema"


def check(node, schema, path, errors):
    if not isinstance(schema, dict):
        return
    expected = schema.get("type")

    def matches_type(value, kind):
        return {
            "object": lambda: isinstance(value, dict),
            "array": lambda: isinstance(value, list),
            "string": lambda: isinstance(value, str),
            "number": lambda: isinstance(value, (int, float)) and not isinstance(value, bool),
            "integer": lambda: isinstance(value, int) and not isinstance(value, bool),
            "boolean": lambda: isinstance(value, bool),
            "null": lambda: value is None,
        }.get(kind, lambda: True)()

    allowed_types = expected if isinstance(expected, list) else [expected] if expected else []
    if allowed_types and not any(matches_type(node, kind) for kind in allowed_types):
        errors.append(
            f"{path}: expected {' or '.join(allowed_types)}, got {type(node).__name__}"
        )
        return
    if "enum" in schema and node not in schema["enum"]:
        errors.append(f"{path}: {node!r} is not one of {schema['enum']}")
    if "const" in schema and node != schema["const"]:
        errors.append(f"{path}: {node!r} must be {schema['const']!r}")
    if isinstance(node, str) and "pattern" in schema:
        if re.search(schema["pattern"], node) is None:
            errors.append(f"{path}: {node!r} does not match {schema['pattern']!r}")
    if isinstance(node, (int, float)) and not isinstance(node, bool):
        if "minimum" in schema and node < schema["minimum"]:
            errors.append(f"{path}: {node!r} is below minimum {schema['minimum']}")
        if "maximum" in schema and node > schema["maximum"]:
            errors.append(f"{path}: {node!r} exceeds maximum {schema['maximum']}")
    if isinstance(node, dict):
        for name in schema.get("required", []):
            if name not in node:
                errors.append(f"{path}: missing required field '{name}'")
        for name, value in node.items():
            child = schema.get("properties", {}).get(name)
            if child is not None:
                check(value, child, f"{path}.{name}", errors)
            elif schema.get("additionalProperties") is False:
                errors.append(f"{path}: unexpected field '{name}'")
    if isinstance(node, list) and "items" in schema:
        for index, item in enumerate(node):
            check(item, schema["items"], f"{path}[{index}]", errors)


def validate_files(kind, paths):
    schema_file = {
        "record": "change-ground-truth.schema.json",
        "answer": "contestant-answer.schema.json",
    }[kind]
    schema = load(SCHEMA_DIR / schema_file)
    report = {"kind": kind, "schema": schema_file, "checked": [], "errors": {}}
    for raw in paths:
        target = Path(raw)
        files = sorted(target.glob("*.json")) if target.is_dir() else [target]
        for file in files:
            errors = []
            check(load(file), schema, file.name, errors)
            report["checked"].append(file.name)
            if errors:
                report["errors"][file.name] = errors
    report["valid"] = not report["errors"]
    return report


def require_valid(kind, paths):
    report = validate_files(kind, paths)
    if not report["valid"]:
        raise SystemExit(json.dumps(report, indent=2))
    return report


def score_change(gt, answer, target_latency_seconds):
    change_id = require(gt, "change_id", "ground truth")
    if answer.get("change_id") != change_id:
        raise SystemExit(
            f"change_id mismatch: ground truth {change_id}, answer {answer.get('change_id')}"
        )
    index = gt_index(gt)
    found = findings(answer)

    reported_repos = {norm(r["name"]) for r in found.get("repositories", []) if "name" in r}
    matched_repos = reported_repos & set(index["repos"])
    missed_repos = [
        {"kind": "repository", "key": key, "severity": severity}
        for key, severity in sorted(index["repos"].items())
        if key not in matched_repos
    ]
    fp_repos = sorted(reported_repos - set(index["repos"]))

    reported_symbols = {norm(s["fqn"]) for s in found.get("symbols", []) if "fqn" in s}
    matched_symbols = reported_symbols & set(index["symbols"])
    missed_symbols = [
        {"kind": "symbol", "key": key, "severity": index["symbols"][key]}
        for key in sorted(set(index["symbols"]) - matched_symbols)
    ]
    fp_symbols = sorted(reported_symbols - set(index["symbols"]))

    reported_contracts = {contract_key(c) for c in found.get("contracts", [])}
    matched_contracts = reported_contracts & set(index["contracts"])
    missed_contracts = [
        {"kind": "contract", "key": " ".join(key), "severity": index["contracts"][key]}
        for key in sorted(set(index["contracts"]) - matched_contracts)
    ]
    fp_contracts = [" ".join(k) for k in sorted(reported_contracts - set(index["contracts"]))]

    reported_tests = {test_key(t) for t in found.get("tests", [])}
    matched_tests = reported_tests & set(index["tests"])
    missed_tests = [
        {"kind": "test", "key": " ".join(key), "severity": "test_suite"}
        for key in sorted(set(index["tests"]) - matched_tests)
    ]
    fp_tests = [" ".join(k) for k in sorted(reported_tests - set(index["tests"]))]

    repo_recall = len(matched_repos) / len(index["repos"]) if index["repos"] else 1.0
    symbol_recall = len(matched_symbols) / len(index["symbols"]) if index["symbols"] else 1.0
    contract_recall = len(matched_contracts) / len(index["contracts"]) if index["contracts"] else 1.0
    test_recall = len(matched_tests) / len(index["tests"]) if index["tests"] else 1.0
    precision = len(matched_repos) / len(reported_repos) if reported_repos else 0.0
    eq_ratio, provenance = evidence_quality(answer)

    elapsed = answer.get("elapsed_seconds")
    latency_score = min(1.0, target_latency_seconds / elapsed) if elapsed else None

    components = {
        "cross_repo_recall": repo_recall,
        "contract_recall": contract_recall,
        "precision": precision,
        "evidence_quality": eq_ratio,
        "freshness": answer.get("freshness_score"),
        "latency": latency_score,
        "operational_cost": answer.get("operational_cost_score"),
    }
    excluded = [name for name, value in components.items() if value is None]
    applied_weights = {
        name: WEIGHTS[name] for name in components if components[name] is not None
    }
    weight_total = sum(applied_weights.values())
    applied_weights = {name: w / weight_total for name, w in applied_weights.items()}
    composite = sum(applied_weights[name] * components[name] for name in applied_weights)

    return {
        "change_id": change_id,
        "contestant": answer.get("contestant"),
        "metrics": {
            "symbol_recall": round(symbol_recall, 4),
            "test_recall": round(test_recall, 4),
            **{name: round(value, 4) for name, value in components.items() if value is not None},
            "composite": round(composite, 4),
        },
        "weights_applied": {name: round(w, 4) for name, w in applied_weights.items()},
        "excluded_components": excluded,
        "counts": {
            "repositories": tally(index["repos"], reported_repos, matched_repos, fp_repos),
            "symbols": tally(index["symbols"], reported_symbols, matched_symbols, fp_symbols),
            "contracts": tally(index["contracts"], reported_contracts, matched_contracts, fp_contracts),
            "tests": tally(index["tests"], reported_tests, matched_tests, fp_tests),
            "missed_by_severity": count_by_severity(
                missed_repos + missed_symbols + missed_contracts + missed_tests
            ),
        },
        "missed": missed_repos + missed_symbols + missed_contracts + missed_tests,
        "false_positives": {
            "repositories": fp_repos,
            "symbols": fp_symbols,
            "contracts": fp_contracts,
            "tests": fp_tests,
        },
        "provenance_distribution": provenance,
        "raw": {
            "elapsed_seconds": elapsed,
            "tokens_consumed": answer.get("tokens_consumed"),
        },
    }


def tally(ground_truth, reported, matched, false_positives):
    return {
        "ground_truth": len(ground_truth),
        "reported": len(reported),
        "matched": len(matched),
        "false_positives": len(false_positives),
    }


def count_by_severity(misses):
    result = {}
    for miss in misses:
        result[miss["severity"]] = result.get(miss["severity"], 0) + 1
    return result


SEVERITY_ORDER = [
    "critical_runtime_dependency",
    "contract_consumer",
    "test_suite",
    "informational",
]


def render_score(report):
    """Human-readable score report: what was found, what the composite is made of."""
    metrics = report["metrics"]
    counts = report["counts"]
    lines = [
        f"{report['change_id']}  {report.get('contestant') or 'unknown'}"
        f"  composite {metrics['composite']:.2f}",
        "",
        "  found (matched / in ground truth, and false positives):",
    ]
    for kind in ("repositories", "symbols", "contracts", "tests"):
        item = counts[kind]
        lines.append(
            f"    {kind:<13} {item['matched']}/{item['ground_truth']} matched"
            f"    {item['false_positives']} false positive"
            f"{'' if item['false_positives'] == 1 else 's'}"
            f" out of {item['reported']} reported"
        )

    lines += ["", "  composite is the weighted sum of:"]
    for name, weight in report["weights_applied"].items():
        lines.append(f"    {metrics[name]:.3f} x {weight:.2f}   {name}")
    lines.append(f"    {metrics['composite']:.3f}           = composite")
    if report["excluded_components"]:
        excluded = ", ".join(report["excluded_components"])
        lines.append(f"    weights renormalized; the answer supplied no {excluded}")

    missed = report["missed"]
    if missed:
        lines += ["", f"  missed {len(missed)}, worst first:"]
        lines += [
            f"    {miss['severity']:<28}{miss['kind']:<12}{miss['key']}"
            for miss in sorted(
                missed,
                key=lambda m: (severity_rank(m["severity"]), m["kind"], m["key"]),
            )
        ]

    false_positives = [
        (kind, key) for kind, keys in report["false_positives"].items() for key in keys
    ]
    if false_positives:
        lines += ["", f"  reported but not in ground truth ({len(false_positives)}):"]
        lines += [f"    {kind:<14}{key}" for kind, key in false_positives]

    provenance = ", ".join(
        f"{tier} {count}" for tier, count in sorted(report["provenance_distribution"].items())
    )
    raw = report["raw"]
    lines += [
        "",
        f"  evidence tiers: {provenance or 'none'}",
        f"  {raw.get('elapsed_seconds')}s, {raw.get('tokens_consumed')} tokens",
    ]
    return "\n".join(lines)


def severity_rank(severity):
    return SEVERITY_ORDER.index(severity) if severity in SEVERITY_ORDER else len(SEVERITY_ORDER)


def marginal(gt, baseline_answer, candidate_answer):
    index = gt_index(gt)
    base_repos = {norm(r["name"]) for r in findings(baseline_answer).get("repositories", []) if "name" in r}
    cand_repos = {norm(r["name"]) for r in findings(candidate_answer).get("repositories", []) if "name" in r}
    base_contracts = {contract_key(c) for c in findings(baseline_answer).get("contracts", [])}
    cand_contracts = {contract_key(c) for c in findings(candidate_answer).get("contracts", [])}

    unique_repos = cand_repos - base_repos
    unique_contracts = cand_contracts - base_contracts

    def classify_repos(names):
        out = []
        for name in sorted(names):
            if name in index["repos"]:
                out.append({"key": name, "classification": "true_positive", "severity": index["repos"][name]})
            else:
                out.append({"key": name, "classification": "false_positive"})
        return out

    def classify_contracts(keys):
        out = []
        for key in sorted(keys):
            if key in index["contracts"]:
                out.append({"key": " ".join(key), "classification": "true_positive", "severity": index["contracts"][key]})
            else:
                out.append({"key": " ".join(key), "classification": "false_positive"})
        return out

    return {
        "baseline": baseline_answer.get("contestant"),
        "candidate": candidate_answer.get("contestant"),
        "unique_repository_discoveries": classify_repos(unique_repos),
        "unique_contract_discoveries": classify_contracts(unique_contracts),
        "summary": {
            "unique_true_positives": sum(
                1
                for item in classify_repos(unique_repos) + classify_contracts(unique_contracts)
                if item["classification"] == "true_positive"
            ),
            "unique_false_positives": sum(
                1
                for item in classify_repos(unique_repos) + classify_contracts(unique_contracts)
                if item["classification"] == "false_positive"
            ),
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Score contestant answers against frozen ground truth.")
    sub = parser.add_subparsers(dest="mode", required=True)

    score_parser = sub.add_parser("score", help="score one answer against one ground-truth record")
    score_parser.add_argument("--ground-truth", required=True)
    score_parser.add_argument("--answer", required=True)
    score_parser.add_argument("--target-latency-seconds", type=float, default=300.0)
    score_parser.add_argument("--output", help="write the full JSON report here")
    score_parser.add_argument("--json", action="store_true", help="print JSON instead of text")

    validate_parser = sub.add_parser("validate", help="check records or answers against their JSON schema")
    validate_parser.add_argument("--kind", choices=["record", "answer"], required=True)
    validate_parser.add_argument("paths", nargs="+", help="files or directories")
    validate_parser.add_argument("--output")

    marginal_parser = sub.add_parser("marginal", help="label discoveries unique to a candidate over a baseline")
    marginal_parser.add_argument("--ground-truth", required=True)
    marginal_parser.add_argument("--baseline", required=True)
    marginal_parser.add_argument("--candidate", required=True)
    marginal_parser.add_argument("--output")

    args = parser.parse_args()

    if args.mode == "score":
        require_valid("record", [args.ground_truth])
        require_valid("answer", [args.answer])
        report = score_change(load(args.ground_truth), load(args.answer), args.target_latency_seconds)
    elif args.mode == "validate":
        report = validate_files(args.kind, args.paths)
    else:
        report = marginal(load(args.ground_truth), load(args.baseline), load(args.candidate))

    document = json.dumps(report, indent=2)
    if args.output:
        Path(args.output).write_text(document + "\n")
    if args.mode == "score" and not args.json:
        print(render_score(report))
    else:
        print(document)
    if args.mode == "validate" and not report["valid"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
