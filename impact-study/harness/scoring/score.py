#!/usr/bin/env python3
import argparse
import json
import re
import sys
from pathlib import Path

EVIDENCE_TIERS = {"compiler", "extracted", "contract_matched", "inferred", "hypothesis"}

FINDING_KINDS = ("repositories", "symbols", "contracts", "tests")

TYPE_ALIASES = {"asyncapi": "kafka"}

SEVERITY_WEIGHTS = {
    "critical_runtime_dependency": 1.0,
    "contract_consumer": 0.6,
    "test_suite": 0.3,
    "informational": 0.1,
}

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
    "critical_penalty": 0.15,
}


def norm(value):
    return " ".join(str(value).lower().split())


def norm_type(value):
    """Normalize a contract type before validation and matching.

    Lowercase and trim, then apply the harness synonym map. asyncapi is
    unambiguously event-side, so it maps to kafka. openapi stays distinct
    from rest: the ground truth uses both for the same provider on purpose.
    """
    key = " ".join(str(value).lower().split())
    return TYPE_ALIASES.get(key, key)


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
        criticality = require(item, "criticality", "affected_repositories")
        if criticality not in CRITICALITY_TO_SEVERITY:
            raise SystemExit(
                f"affected_repositories: unknown criticality {criticality!r}"
            )
        repos[norm(require(item, "name", "affected_repositories"))] = CRITICALITY_TO_SEVERITY[
            criticality
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
        norm_type(require(item, "type", "contract")),
        norm(require(item, "identifier", "contract")),
    )


def normalize_contract_item(item):
    """Return a copy with the contract type normalized for validation."""
    if isinstance(item, dict) and isinstance(item.get("type"), str):
        return {**item, "type": norm_type(item["type"])}
    return item


def symbol_prefix_match(reported, ground_truth):
    """Match symbols on declaring-type prefix in either direction.

    A field or method reference credits its declaring type, so
    com.foo.Bar.postalCode matches com.foo.Bar and vice versa.
    Returns (gt_matched, reported_matched).
    """
    gt_matched = set()
    reported_matched = set()
    for gt in ground_truth:
        for rep in reported:
            if rep == gt or rep.startswith(gt + ".") or gt.startswith(rep + "."):
                gt_matched.add(gt)
                reported_matched.add(rep)
    return gt_matched, reported_matched


def weighted_recall(gt_weights, matched):
    """Sum of matched severity weights over sum of all weights."""
    total = sum(gt_weights.values())
    if not total:
        return 1.0
    hit = sum(weight for key, weight in gt_weights.items() if key in matched)
    return hit / total


def severity_weight(severity):
    return SEVERITY_WEIGHTS.get(severity, 0.1)


def test_key(item):
    return norm(require(item, "repo", "test")), norm(require(item, "suite", "test"))


def findings(answer):
    return require(answer, "findings", answer.get("contestant", "answer"))


def evidence_quality(valid_findings, invalid_count):
    total = invalid_count
    evidenced = 0
    distribution = {"schema_invalid": invalid_count} if invalid_count else {}
    for items in valid_findings.values():
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


def answer_schema():
    return load(SCHEMA_DIR / "contestant-answer.schema.json")


def split_findings(answer, schema):
    """Separate the findings that satisfy the schema from the ones that do not.

    One malformed finding must not void an otherwise usable answer: the valid
    findings are scored and the invalid ones are counted against the contestant.
    Contract types are normalized before validation so case differences and
    the asyncapi->kafka alias do not void a run.
    """
    item_schemas = schema["properties"]["findings"]["properties"]
    container = answer.get("findings")
    if not isinstance(container, dict):
        return {}, []
    valid = {}
    invalid = []
    for kind, items in container.items():
        item_schema = item_schemas.get(kind, {}).get("items")
        if item_schema is None:
            invalid.append({
                "kind": kind,
                "index": -1,
                "errors": [f"findings.{kind}: unknown finding kind, expected one of {list(FINDING_KINDS)}"],
            })
            continue
        if not isinstance(items, list):
            continue
        kept = []
        for index, item in enumerate(items):
            candidate = normalize_contract_item(item) if kind == "contracts" else item
            errors = []
            check(candidate, item_schema, f"findings.{kind}[{index}]", errors)
            if errors:
                invalid.append({"kind": kind, "index": index, "errors": errors})
            else:
                kept.append(candidate)
        valid[kind] = kept
    for kind in FINDING_KINDS:
        valid.setdefault(kind, [])
    return valid, invalid


def answer_errors(answer, schema, name):
    """Structural errors only. Individual bad findings are split out, not fatal."""
    errors = []
    container = answer.get("findings")
    if not isinstance(container, dict):
        check(answer, schema, name, errors)
    else:
        for kind, items in container.items():
            if kind in FINDING_KINDS and not isinstance(items, list):
                errors.append(f"{name}.findings.{kind}: expected array, got {type(items).__name__}")
            elif kind not in FINDING_KINDS:
                errors.append(f"{name}.findings.{kind}: unknown finding kind")
        check({**answer, "findings": {}}, schema, name, errors)
    if str(answer.get("runner", "")).endswith("-real"):
        # Shape gate only: this proves every finding carries an attribution
        # label, not that the label is true or the finding is correct.
        # Truth is the scorer's job; fabrication with a valid label passes here.
        if not isinstance(answer.get("product_provenance"), dict):
            errors.append(f"{name}: real-product runner requires product_provenance")
        if not isinstance(container, dict):
            errors.append(f"{name}: real-product runner requires a findings object")
        else:
            for kind, items in container.items():
                if kind not in FINDING_KINDS or not isinstance(items, list):
                    continue
                for index, item in enumerate(items):
                    if not isinstance(item, dict):
                        errors.append(
                            f"{name}.findings.{kind}[{index}]: real-product finding must be an object with attribution"
                        )
                    elif item.get("attribution") not in {
                        "product_direct", "agent_inferred", "file_search"
                    }:
                        errors.append(
                            f"{name}.findings.{kind}[{index}]: real-product finding requires attribution"
                        )
    return errors


def validate_files(kind, paths):
    schema_file = {
        "record": "change-ground-truth.schema.json",
        "answer": "contestant-answer.schema.json",
    }[kind]
    schema = load(SCHEMA_DIR / schema_file)
    report = {"kind": kind, "schema": schema_file, "checked": [], "errors": {}}
    if kind == "answer":
        report["invalid_findings"] = {}
    for raw in paths:
        target = Path(raw)
        files = sorted(target.glob("*.json")) if target.is_dir() else [target]
        for file in files:
            document = load(file)
            if kind == "answer":
                errors = answer_errors(document, schema, file.name)
                invalid = split_findings(document, schema)[1]
                if invalid:
                    report["invalid_findings"][file.name] = invalid
            else:
                errors = []
                check(document, schema, file.name, errors)
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
    if answer.get("synthetic") is True:
        raise SystemExit("synthetic example must never be scored as a result")
    change_id = require(gt, "change_id", "ground truth")
    if answer.get("change_id") != change_id:
        raise SystemExit(
            f"change_id mismatch: ground truth {change_id}, answer {answer.get('change_id')}"
        )
    index = gt_index(gt)
    findings(answer)  # required field; the split below decides what is usable
    found, invalid_findings = split_findings(answer, answer_schema())
    invalid = {kind: 0 for kind in FINDING_KINDS}
    for item in invalid_findings:
        invalid[item["kind"]] = invalid.get(item["kind"], 0) + 1

    reported_repos = {norm(r["name"]) for r in found.get("repositories", []) if "name" in r}
    matched_repos = reported_repos & set(index["repos"])
    missed_repos = [
        {"kind": "repository", "key": key, "severity": severity}
        for key, severity in sorted(index["repos"].items())
        if key not in matched_repos
    ]
    fp_repos = sorted(reported_repos - set(index["repos"]))

    reported_symbols_raw = [norm(s["fqn"]) for s in found.get("symbols", []) if "fqn" in s]
    reported_symbols = set(reported_symbols_raw)
    gt_symbols = set(index["symbols"])
    gt_matched_symbols, reported_matched_symbols = symbol_prefix_match(
        reported_symbols, gt_symbols
    )
    matched_symbols = gt_matched_symbols
    missed_symbols = [
        {"kind": "symbol", "key": key, "severity": index["symbols"][key]}
        for key in sorted(gt_symbols - matched_symbols)
    ]
    fp_symbols = sorted(reported_symbols - reported_matched_symbols)

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

    repo_weights = {k: severity_weight(v) for k, v in index["repos"].items()}
    contract_weights = {k: severity_weight(v) for k, v in index["contracts"].items()}
    symbol_weights = {k: severity_weight(v) for k, v in index["symbols"].items()}
    repo_recall = weighted_recall(repo_weights, matched_repos)
    contract_recall = weighted_recall(contract_weights, matched_contracts)
    symbol_recall = weighted_recall(symbol_weights, matched_symbols)
    test_recall = len(matched_tests) / len(index["tests"]) if index["tests"] else 1.0
    # Overall precision across every finding kind. An invalid finding was
    # still reported, so every invalid item costs precision once.
    raw_reported = (
        len(found.get("repositories", []))
        + len(found.get("symbols", []))
        + len(found.get("contracts", []))
        + len(found.get("tests", []))
        + len(invalid_findings)
    )
    total_matched = (
        len(matched_repos)
        + len(reported_matched_symbols)
        + len(matched_contracts)
        + len(matched_tests)
    )
    precision = total_matched / raw_reported if raw_reported else 0.0
    eq_ratio, provenance = evidence_quality(found, len(invalid_findings))

    missed_all = missed_repos + missed_symbols + missed_contracts + missed_tests
    critical_misses = sum(
        1 for miss in missed_all if miss["severity"] == "critical_runtime_dependency"
    )
    critical_penalty = max(0.0, 1.0 - 0.25 * critical_misses)

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
        "critical_penalty": critical_penalty,
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
            "repositories": tally(len(index["repos"]), len(found.get("repositories", [])), len(matched_repos), len(fp_repos), invalid["repositories"]),
            "symbols": tally(len(index["symbols"]), len(found.get("symbols", [])), len(reported_matched_symbols), len(fp_symbols), invalid["symbols"]),
            "contracts": tally(len(index["contracts"]), len(found.get("contracts", [])), len(matched_contracts), len(fp_contracts), invalid["contracts"]),
            "tests": tally(len(index["tests"]), len(found.get("tests", [])), len(matched_tests), len(fp_tests), invalid["tests"]),
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
        "invalid_findings": invalid_findings,
        "provenance_distribution": provenance,
        "raw": {
            "elapsed_seconds": elapsed,
            "tokens_consumed": answer.get("tokens_consumed"),
        },
    }


def tally(ground_truth_count, reported_raw, matched_count, false_positives_count, invalid=0):
    return {
        "ground_truth": ground_truth_count,
        "reported": reported_raw + invalid,
        "matched": matched_count,
        "false_positives": false_positives_count,
        "invalid": invalid,
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

    invalid = report["invalid_findings"]
    if invalid:
        lines += ["", f"  discarded, not valid against the schema ({len(invalid)}):"]
        lines += [
            f"    {item['kind']}[{item['index']}]  {error}"
            for item in invalid
            for error in item["errors"]
        ]

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
    score_parser.add_argument("--allow-draft", action="store_true", help="score a non-frozen record")

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
        gt_document = load(args.ground_truth)
        if gt_document.get("status") != "frozen" and not args.allow_draft:
            raise SystemExit(
                f"record {gt_document.get('change_id')} status is {gt_document.get('status')!r}: only frozen records may be scored (use --allow-draft to override)"
            )
        answer_bytes = Path(args.answer).read_bytes()
        ground_truth_bytes = Path(args.ground_truth).read_bytes()
        scorer_bytes = Path(__file__).read_bytes()
        schema_bytes = (SCHEMA_DIR / "contestant-answer.schema.json").read_bytes()
        import hashlib

        report = score_change(gt_document, load(args.answer), args.target_latency_seconds)
        report["answer_sha256"] = hashlib.sha256(answer_bytes).hexdigest()
        report["ground_truth_sha256"] = hashlib.sha256(ground_truth_bytes).hexdigest()
        report["scorer_sha256"] = hashlib.sha256(scorer_bytes).hexdigest()
        report["answer_schema_sha256"] = hashlib.sha256(schema_bytes).hexdigest()
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
