"""Summarize source-blinded, reversed-order reviews without treating model ratings as human truth."""
import math
from statistics import mean


def latency(values):
    values = sorted(values)
    if not values:
        return {"count": 0, "p50": None, "p95": None, "max": None}
    return {"count": len(values), "p50": values[math.ceil(.5 * len(values)) - 1],
            "p95": values[math.ceil(.95 * len(values)) - 1], "max": values[-1], "mean": mean(values)}


def paired_outcomes(rows, comparator):
    counts = {"wins": 0, "ties": 0, "losses": 0, "order_sensitive_or_disagreement": 0, "incomplete": 0}
    details = []
    for row in rows:
        winners = []
        for review in row.get("reviews", []):
            if not review.get("valid"):
                continue
            mapping = review["private_source_mapping"]
            for pair in review["parsed"]["comparisons"]:
                if {mapping[pair["left"]], mapping[pair["right"]]} == {"ai", comparator}:
                    winners.append("TIE" if pair["winner"] == "TIE" else mapping[pair["winner"]])
        if len(winners) != 2:
            outcome = "incomplete"
        elif winners[0] != winners[1]:
            outcome = "order_sensitive_or_disagreement"
        else:
            outcome = {"ai": "wins", "TIE": "ties", comparator: "losses"}[winners[0]]
        counts[outcome] += 1
        details.append({"case_id": row["id"], "outcome": outcome, "orientation_winners": winners})
    return {**counts, "case_count": len(rows), "cases": details}


def summarize(report):
    eligible = [r for r in report["results"] if not r["expected_abstention"]]
    controls = [r for r in report["results"] if r["expected_abstention"]]
    ratings = {name: {"goals_covered": 0, "goals_total": 0, "clarity": [], "calibration": [],
                      "unsupported_claim_flags": 0, "citation_error_flags": 0, "unsafe_advice_flags": 0,
                      "flagged_case_ids": set()} for name in ("rules", "strong_rules", "ai")}
    valid_reviews = 0
    for row in eligible:
        for review in row.get("reviews", []):
            if not review.get("valid"):
                continue
            valid_reviews += 1
            for score in review["parsed"]["evaluations"]:
                rating = ratings[review["private_source_mapping"][score["label"]]]
                rating["goals_covered"] += len(score["coveredGoalIds"])
                rating["goals_total"] += len(row["reference_goals"])
                rating["clarity"].append(score["clarity"])
                rating["calibration"].append(score["calibration"])
                for field, source in (("unsupported_claim_flags", "unsupportedClaims"), ("citation_error_flags", "citationErrors"), ("unsafe_advice_flags", "unsafeAdvice")):
                    rating[field] += len(score[source])
                    if score[source]:
                        rating["flagged_case_ids"].add(row["id"])
    for rating in ratings.values():
        rating["goal_coverage_rate"] = rating["goals_covered"] / rating["goals_total"] if rating["goals_total"] else None
        for metric in ("clarity", "calibration"):
            values = rating.pop(metric)
            rating[metric + "_mean_1_to_5"] = mean(values) if values else None
            rating["rated_presentations"] = len(values)
        rating["flagged_case_ids"] = sorted(rating["flagged_case_ids"])
    expected_total = sum(len(row["expected_relations"]) for row in eligible)
    recalls = {}
    for variant, field in (("ai", "attributions"), ("strong_rules", "strong_rule_attributions")):
        hits = sum(len(set(row["expected_relations"]) & {a["relation"] for a in (row["ai"].get(field, []) if variant == "ai" else row[field])}) for row in eligible)
        recalls[variant] = {"covered": hits, "total": expected_total, "rate": hits / expected_total if expected_total else None}
    return {
        "complete": report["complete"], "model": report["model"], "eligible_cases": len(eligible), "controls": len(controls),
        "request_attempts": len(report["requests"]), "initiated_requests": sum(bool(t["initiated"]) for t in report["requests"]),
        "generation_requests": sum(r["ai"]["modelCalls"] for r in eligible),
        "fallbacks": sum(r["ai"]["mode"] == "rules_fallback" for r in eligible),
        "fallback_rate": sum(r["ai"]["mode"] == "rules_fallback" for r in eligible) / len(eligible) if eligible else None,
        "generation_latency_ms": latency([r["ai"]["elapsedMillis"] for r in eligible]),
        "strong_rules_latency_ms": latency([r["strong_rule_elapsed_ms"] for r in eligible]),
        "cost": report["cost"], "cost_by_kind": report.get("cost_by_kind", {}),
        "mechanical": {
            "checked_attributions": sum(len(r["ai"].get("attributions", [])) for r in eligible),
            "explicit_relation_recall": recalls,
            "original_rule_relation_count": 0,
            "rule_summaries_retained": sum(r["ai"]["summary"] == r["rules"]["summary"] for r in eligible),
            "controls_abstain_without_request": sum(r["ai"]["abstained"] and r["ai"]["modelCalls"] == 0 for r in controls),
            "scope": "Checked relation recall measures explicit proof objects, not prose usefulness or independent semantic accuracy. Original rules may express some relevant meaning without proof objects."
        },
        "valid_model_reviews": valid_reviews, "expected_model_reviews": 2 * len(eligible),
        "model_ratings": ratings, "ai_vs_rules": paired_outcomes(eligible, "rules"),
        "ai_vs_strong_rules": paired_outcomes(eligible, "strong_rules"),
        "human_review": None,
        "review_scope": "Same snapshot generates and grades; source labels hidden, positions reversed. Each case is counted once for stable pair preference; rating counts include both presentations. Self-preference, correlated error and order effects remain. Not human, heldout or production evidence."
    }


def markdown(report, summary):
    cost = summary["cost"]
    latency_stats = summary["generation_latency_ms"]
    lines = ["# Atlas cross-event comparison", "", f"Model: `{report['model']}` · Dataset: `{report['dataset_version']}`", "",
             f"Completed: {summary['complete']} · Cases: {summary['eligible_cases']} + {summary['controls']} abstention controls", "",
             "| Operational metric | Result |", "| --- | --- |",
             f"| Initiated requests (generation + review) | {summary['initiated_requests']} |",
             f"| Generation fallback | {summary['fallbacks']}/{summary['eligible_cases']} |",
             f"| Generation P50 / P95 / max | {latency_stats['p50']} / {latency_stats['p95']} / {latency_stats['max']} ms |",
             f"| Usage-based total cost USD | {cost['estimated_total_usd']} |",
             f"| Missing-usage / unpriced requests | {cost['requests_missing_usage']} / {cost['requests_unpriced']} |", "",
             "A missing total is unknown cost, not zero. No retries or hidden warmup calls. Paid reviewer requests are included in total cost.", "",
             "## Mechanical checks", "",
             f"Program-checked AI relation objects: {summary['mechanical']['checked_attributions']}.",
             f"Rule summaries retained: {summary['mechanical']['rule_summaries_retained']}/{summary['eligible_cases']}.",
             f"Abstention without request: {summary['mechanical']['controls_abstain_without_request']}/{summary['controls']}.", ""]
    for name, score in summary["mechanical"]["explicit_relation_recall"].items():
        lines.append(f"- Explicit expected relations, {name}: {score['covered']}/{score['total']}.")
    lines += ["", summary["mechanical"]["scope"], "", "## Source-blinded model review", "",
              f"Valid reviews: {summary['valid_model_reviews']}/{summary['expected_model_reviews']}.", "",
              "| Variant | Goal coverage | Clarity (1–5) | Calibration (1–5) | Unsupported / citation / unsafe flags |",
              "| --- | --- | --- | --- | --- |"]
    for name, rating in summary["model_ratings"].items():
        lines.append(f"| {name} | {rating['goals_covered']}/{rating['goals_total']} | {rating['clarity_mean_1_to_5']} | {rating['calibration_mean_1_to_5']} | {rating['unsupported_claim_flags']} / {rating['citation_error_flags']} / {rating['unsafe_advice_flags']} |")
    lines += ["", "| AI compared with | Stable wins | Ties | Stable losses | Order-sensitive/disagreement | Incomplete |",
              "| --- | --- | --- | --- | --- | --- |"]
    for name, key in (("original rules", "ai_vs_rules"), ("stronger cross-event rules", "ai_vs_strong_rules")):
        pair = summary[key]
        lines.append(f"| {name} | {pair['wins']} | {pair['ties']} | {pair['losses']} | {pair['order_sensitive_or_disagreement']} | {pair['incomplete']} |")
    lines += ["", summary["review_scope"], "", "## Cases", ""]
    for row in report["results"]:
        lines += [f"### {row['id']}", "", row["ai"]["summary"], ""]
        for attribution in row["ai"].get("attributions", []):
            lines += [f"- Observed: {attribution['observedRelation']}", f"  Interpretation: {attribution['interpretation']}",
                      f"  Uncertainty: {attribution['uncertainty']}", "  Evidence: " + ", ".join(attribution["evidenceIds"])]
        lines.append("")
    return "\n".join(lines)
