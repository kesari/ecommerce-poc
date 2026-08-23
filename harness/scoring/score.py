#!/usr/bin/env python3
import argparse
import json
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
            "gt_repos": len(index["repos"]),
            "reported_repos": len(reported_repos),
            "matched_repos": len(matched_repos),
            "false_positive_repos": len(fp_repos),
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


def count_by_severity(misses):
    result = {}
    for miss in misses:
        result[miss["severity"]] = result.get(miss["severity"], 0) + 1
    return result


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
    score_parser.add_argument("--output")

    marginal_parser = sub.add_parser("marginal", help="label discoveries unique to a candidate over a baseline")
    marginal_parser.add_argument("--ground-truth", required=True)
    marginal_parser.add_argument("--baseline", required=True)
    marginal_parser.add_argument("--candidate", required=True)
    marginal_parser.add_argument("--output")

    args = parser.parse_args()

    if args.mode == "score":
        report = score_change(load(args.ground_truth), load(args.answer), args.target_latency_seconds)
    else:
        report = marginal(load(args.ground_truth), load(args.baseline), load(args.candidate))

    text = json.dumps(report, indent=2)
    if args.output:
        Path(args.output).write_text(text + "\n")
    print(text)


if __name__ == "__main__":
    main()
