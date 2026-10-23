import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cross_event_report import paired_outcomes, summarize


class CrossEventReportTest(unittest.TestCase):
    def rows(self):
        reviews = []
        for mapping in ({"A": "ai", "B": "rules", "C": "strong_rules"}, {"A": "strong_rules", "B": "rules", "C": "ai"}):
            comparisons = []
            for left, right in (("A", "B"), ("A", "C"), ("B", "C")):
                names = {mapping[left], mapping[right]}
                winner = next((label for label in (left, right) if mapping[label] == "ai"), None) if names == {"ai", "rules"} else "TIE"
                comparisons.append({"left": left, "right": right, "winner": winner, "reason": "Fixture"})
            evaluations = [{"label": label, "coveredGoalIds": ["g1"], "unsupportedClaims": [], "citationErrors": [],
                            "unsafeAdvice": [], "clarity": 4, "calibration": 5} for label in mapping]
            reviews.append({"valid": True, "private_source_mapping": mapping,
                            "parsed": {"comparisons": comparisons, "evaluations": evaluations}})
        return [{"id": "fixture", "reviews": reviews, "expected_abstention": False, "expected_relations": ["REPEATED_UNKNOWN"],
                 "reference_goals": [{"id": "g1"}], "strong_rule_attributions": [{"relation": "REPEATED_UNKNOWN"}],
                 "strong_rule_elapsed_ms": 2, "rules": {"summary": "current UNKNOWN"},
                 "ai": {"mode": "rules_fallback", "modelCalls": 1, "elapsedMillis": 8001, "summary": "current UNKNOWN", "attributions": []}}]

    def test_reversed_labels_map_back_to_one_case_and_stable_ties(self):
        rows = self.rows()
        self.assertEqual(paired_outcomes(rows, "rules")["wins"], 1)
        self.assertEqual(paired_outcomes(rows, "strong_rules")["ties"], 1)
        self.assertEqual(paired_outcomes(rows, "rules")["case_count"], 1)

    def test_incomplete_and_order_disagreement_never_become_wins(self):
        rows = self.rows()
        rows[0]["reviews"][1]["valid"] = False
        self.assertEqual(paired_outcomes(rows, "rules")["incomplete"], 1)
        rows = self.rows()
        pair = next(p for p in rows[0]["reviews"][1]["parsed"]["comparisons"] if p["left"] == "B" and p["right"] == "C")
        pair["winner"] = "B"
        self.assertEqual(paired_outcomes(rows, "rules")["order_sensitive_or_disagreement"], 1)

    def test_fallback_is_in_denominator_and_no_proof_does_not_score_as_recall(self):
        summary = summarize({"results": self.rows(), "requests": [{"initiated": True}], "model": "fixture", "cost": {}, "complete": True})
        self.assertEqual(summary["fallback_rate"], 1)
        self.assertEqual(summary["mechanical"]["explicit_relation_recall"]["ai"]["covered"], 0)
        self.assertEqual(summary["model_ratings"]["ai"]["rated_presentations"], 2)
        self.assertEqual(summary["model_ratings"]["ai"]["goal_coverage_rate"], 1)
        self.assertIsNone(summary["human_review"])

    def test_accepted_empty_v4_reply_is_not_counted_as_a_useful_attribution(self):
        rows = self.rows()
        rows[0]["ai"]["mode"] = "model_no_attribution"
        rows[0]["reviews"] = []
        summary = summarize({"results": rows, "requests": [{"initiated": True}], "model": "fixture", "cost": {}, "complete": True})
        self.assertEqual(summary["fallback_rate"], 0)
        self.assertEqual(summary["mechanical"]["accepted_empty_attribution_plans"], 1)
        self.assertEqual(summary["mechanical"]["checked_attributions"], 0)
        self.assertEqual(summary["mechanical"]["explicit_relation_recall"]["ai"]["covered"], 0)
        self.assertEqual(summary["valid_model_reviews"], 0)
        self.assertIsNone(summary["model_ratings"]["ai"]["goal_coverage_rate"])


if __name__ == "__main__":
    unittest.main()
