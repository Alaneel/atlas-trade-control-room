"""Offline, source-blinded review packets and strict annotation scoring. No provider calls."""
import hashlib
import json
import random
from statistics import mean

LABELS = ("A", "B", "C")
SOURCES = ("rules", "strong_rules", "ai")
PAIRS = (("A", "B"), ("A", "C"), ("B", "C"))
FLAGS = ("unsupported_claims", "citation_errors", "unsafe_advice")


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def load(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")))


def visible(explanation, attributions):
    # Match the model-review presentation, including actual whole-plan fallbacks.
    result = {key: explanation[key] for key in ("summary", "findings", "nextChecks", "limitations")}
    result["attributions"] = [{"observation": a["observedRelation"], "interpretation": a["interpretation"],
        "uncertainty": a["uncertainty"], "citations": a["evidenceIds"]} for a in attributions]
    return json.loads(json.dumps(result).replace(explanation["tradeId"], "trade"))


def make_packet(report, seed=20261023):
    if report.get("complete") is not True:
        raise ValueError("Only a completed retained run can become a review packet")
    rows = [row for row in report["results"] if not row["expected_abstention"]]
    if not rows or len({row["id"] for row in rows}) != len(rows):
        raise ValueError("Missing or duplicate eligible cases")
    rng = random.Random(seed)
    rng.shuffle(rows)
    packet = {"schema": "atlas-human-packet-v1", "cases": []}
    key = {"schema": "atlas-human-review-key-v1", "source_report_sha256": digest(report),
           "seed": seed, "source_model": report["model"], "cases": []}
    for index, row in enumerate(rows, 1):
        case_id = f"case-{index:02d}"
        order = list(SOURCES)
        rng.shuffle(order)
        mapping = dict(zip(LABELS, order))
        views = {"rules": visible(row["rules"], []),
                 "strong_rules": visible(row["rules"], row["strong_rule_attributions"]),
                 "ai": visible(row["ai"], row["ai"].get("attributions", []))}
        evidence = json.loads(json.dumps(row["rules"]["evidence"]).replace(row["rules"]["tradeId"], "trade"))
        packet["cases"].append({"case_id": case_id, "evidence": evidence,
            "goals": row["reference_goals"], "forbidden_claims": row["forbidden_claims"],
            "options": {label: views[source] for label, source in mapping.items()}})
        key["cases"].append({"case_id": case_id, "source_case_id": row["id"], "mapping": mapping})
    packet["packet_id"] = digest(packet)
    key["packet_id"] = packet["packet_id"]
    key["packet_sha256"] = digest(packet)
    return packet, key


def template(packet):
    return {"schema": "atlas-human-annotations-v1", "packet_id": packet["packet_id"],
        "reviewer_id": "", "cases": [{"case_id": case["case_id"],
            "evaluations": [{"label": label, "covered_goal_ids": [], **{flag: [] for flag in FLAGS},
                "clarity": None, "calibration": None, "notes": ""} for label in LABELS],
            "comparisons": [{"left": left, "right": right, "winner": None, "reason": ""}
                for left, right in PAIRS]} for case in packet["cases"]]}


def fields(value, expected):
    if not isinstance(value, dict) or set(value) != set(expected):
        raise ValueError("Unexpected or missing annotation fields")


def string(value, maximum=1000):
    if not isinstance(value, str) or len(value) > maximum:
        raise ValueError("Expected bounded text")


def checked_identity(packet, key):
    if key.get("schema") != "atlas-human-review-key-v1" or packet.get("schema") != "atlas-human-packet-v1":
        raise ValueError("Unknown packet/key schema")
    if digest(packet) != key.get("packet_sha256") or packet["packet_id"] != key.get("packet_id"):
        raise ValueError("Packet/key content mismatch")
    if digest({k: v for k, v in packet.items() if k != "packet_id"}) != packet["packet_id"]:
        raise ValueError("Packet identity mismatch")
    public_ids = [c["case_id"] for c in packet["cases"]]
    private_ids = [c["case_id"] for c in key["cases"]]
    if len(set(public_ids)) != len(public_ids) or public_ids != private_ids:
        raise ValueError("Case mapping mismatch")
    if len({c["source_case_id"] for c in key["cases"]}) != len(private_ids):
        raise ValueError("Duplicate underlying case")
    for case in key["cases"]:
        if set(case["mapping"]) != set(LABELS) or set(case["mapping"].values()) != set(SOURCES):
            raise ValueError("Source mapping must be a bijection")


def validate_annotations(packet, annotation):
    fields(annotation, ("schema", "packet_id", "reviewer_id", "cases"))
    if annotation["schema"] != "atlas-human-annotations-v1" or annotation["packet_id"] != packet["packet_id"]:
        raise ValueError("Foreign annotation packet")
    string(annotation["reviewer_id"], 100)
    if not annotation["reviewer_id"].strip():
        raise ValueError("Supply a reviewer pseudonym; an empty template is not a human review")
    if not isinstance(annotation["cases"], list) or len(annotation["cases"]) != len(packet["cases"]):
        raise ValueError("All case slots must be retained, including incomplete cases")
    by_id = {c["case_id"]: c for c in packet["cases"]}
    seen = set()
    status = {}
    for row in annotation["cases"]:
        fields(row, ("case_id", "evaluations", "comparisons"))
        cid = row["case_id"]
        if not isinstance(cid, str) or cid not in by_id or cid in seen:
            raise ValueError("Foreign or duplicate annotation case")
        seen.add(cid)
        goals = {g["id"] for g in by_id[cid]["goals"]}
        if not isinstance(row["evaluations"], list) or len(row["evaluations"]) != 3:
            raise ValueError("Exactly three variant ratings required")
        labels = set()
        complete = True
        for rating in row["evaluations"]:
            fields(rating, ("label", "covered_goal_ids", *FLAGS, "clarity", "calibration", "notes"))
            label = rating["label"]
            if not isinstance(label, str) or label not in LABELS or label in labels:
                raise ValueError("Foreign or duplicate variant label")
            labels.add(label)
            covered = rating["covered_goal_ids"]
            if not isinstance(covered, list) or any(not isinstance(g, str) or g not in goals for g in covered) or len(set(covered)) != len(covered):
                raise ValueError("Unknown or duplicate reference goal")
            for flag in FLAGS:
                if not isinstance(rating[flag], list) or len(rating[flag]) > 20:
                    raise ValueError("Invalid error flags")
                for reason in rating[flag]:
                    string(reason)
                    if not reason.strip():
                        raise ValueError("Error flags require a description")
            for metric in ("clarity", "calibration"):
                score = rating[metric]
                if score is not None and (type(score) is not int or not 1 <= score <= 5):
                    raise ValueError("Scores must be integer 1..5 or null")
                complete = complete and score is not None
            string(rating["notes"], 3000)
        if not isinstance(row["comparisons"], list) or len(row["comparisons"]) != 3:
            raise ValueError("All three pair comparisons required")
        pairs = set()
        for comparison in row["comparisons"]:
            fields(comparison, ("left", "right", "winner", "reason"))
            pair = (comparison["left"], comparison["right"])
            if pair not in PAIRS or pair in pairs:
                raise ValueError("Unknown, reversed or duplicate comparison")
            pairs.add(pair)
            winner = comparison["winner"]
            if winner is not None and winner not in (*pair, "TIE"):
                raise ValueError("Winner must be one of the compared options, TIE, or null")
            string(comparison["reason"], 3000)
            complete = complete and winner is not None and bool(comparison["reason"].strip())
        status[cid] = bool(complete)
    return status


def score(packet, key, annotations):
    checked_identity(packet, key)
    reviewers = set()
    per_case = {c["case_id"]: {"complete_ratings": 0, "ai_vs_rules": [], "ai_vs_strong_rules": []}
                for c in packet["cases"]}
    totals = {s: {"goals_covered": 0, "goals_total": 0, "clarity": [], "calibration": [],
        **{flag: 0 for flag in FLAGS}} for s in SOURCES}
    mappings = {c["case_id"]: c["mapping"] for c in key["cases"]}
    goals = {c["case_id"]: len(c["goals"]) for c in packet["cases"]}
    for annotation in annotations:
        statuses = validate_annotations(packet, annotation)
        reviewer = annotation["reviewer_id"].strip()
        if reviewer in reviewers:
            raise ValueError("Duplicate reviewer ID: repeated files are not independent raters")
        reviewers.add(reviewer)
        for row in annotation["cases"]:
            cid = row["case_id"]
            if not statuses[cid]:
                continue
            per_case[cid]["complete_ratings"] += 1
            mapping = mappings[cid]
            for rating in row["evaluations"]:
                total = totals[mapping[rating["label"]]]
                total["goals_covered"] += len(rating["covered_goal_ids"])
                total["goals_total"] += goals[cid]
                for metric in ("clarity", "calibration"):
                    total[metric].append(rating[metric])
                for flag in FLAGS:
                    total[flag] += len(rating[flag])
            for comparison in row["comparisons"]:
                sources = {mapping[comparison["left"]], mapping[comparison["right"]]}
                if "ai" not in sources:
                    continue
                comparator = next(s for s in sources if s != "ai")
                winner = comparison["winner"]
                per_case[cid]["ai_vs_" + comparator].append("ties" if winner == "TIE" else
                    "wins" if mapping[winner] == "ai" else "losses")
    pairs = {}
    for name in ("ai_vs_rules", "ai_vs_strong_rules"):
        counts = {"wins": 0, "ties": 0, "losses": 0, "rater_disagreement": 0, "incomplete": 0}
        for case in per_case.values():
            outcomes = case[name]
            outcome = "incomplete" if not outcomes else outcomes[0] if len(set(outcomes)) == 1 else "rater_disagreement"
            counts[outcome] += 1
        pairs[name] = counts
    for case in per_case.values():
        case["missing_or_incomplete_ratings"] = len(reviewers) - case["complete_ratings"]
    for total in totals.values():
        total["complete_presentations"] = len(total["clarity"])
        for metric in ("clarity", "calibration"):
            values = total.pop(metric)
            total[metric + "_mean"] = mean(values) if values else None
        total["goal_coverage"] = total["goals_covered"] / total["goals_total"] if total["goals_total"] else None
    complete = sum(c["complete_ratings"] for c in per_case.values())
    return {"schema": "atlas-human-review-summary-v1", "packet_id": packet["packet_id"],
        "human_review": "annotations_supplied_not_identity_verified" if complete else None,
        "reviewer_files": len(reviewers), "eligible_cases": len(per_case),
        "completed_case_ratings": complete, "possible_case_ratings": len(reviewers) * len(per_case),
        "unique_cases_reviewed": sum(c["complete_ratings"] > 0 for c in per_case.values()),
        "ratings": totals, **pairs, "per_case": per_case,
        "scope": "Local annotations are self-reported; reviewer identity and independence are not verified. "
            "Incomplete cases never count as ties/wins. Pair summaries count each underlying case once and require unanimity among its complete raters; missing raters are reported separately. "
            "Multiple raters are not additional independent cases. No held-out, causal or production-quality claim."}
