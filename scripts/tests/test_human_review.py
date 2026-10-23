import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from human_review import canonical, digest, load, make_packet, score, template, validate_annotations, visible
from human_review_ui import render

ROOT = Path(__file__).resolve().parents[2]


class HumanReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = load(ROOT / "docs/examples/ai-explanations/cross-event-luna-round/results.json")

    def setUp(self):
        self.packet, self.key = make_packet(self.report)
        self.annotation = template(self.packet)
        self.annotation["reviewer_id"] = "TEST-FIXTURE-NOT-A-HUMAN"

    def completed(self, reviewer="TEST-FIXTURE-NOT-A-HUMAN", winner="TIE"):
        annotation = copy.deepcopy(self.annotation)
        annotation["reviewer_id"] = reviewer
        for case in annotation["cases"]:
            for rating in case["evaluations"]:
                rating.update(clarity=4, calibration=5, covered_goal_ids=["g1"])
            for pair in case["comparisons"]:
                pair.update(winner=winner if winner == "TIE" else pair[winner], reason="Synthetic test fixture only")
        return annotation

    def test_reproducible_packet_preserves_actual_fallback_and_all_visible_text(self):
        self.assertEqual(make_packet(self.report), (self.packet, self.key))
        changed, key = make_packet(self.report, 20261024)
        self.assertNotEqual(changed["packet_id"], self.packet["packet_id"])
        by_id = {r["id"]: r for r in self.report["results"]}
        fallback = 0
        for public, private in zip(self.packet["cases"], self.key["cases"]):
            row = by_id[private["source_case_id"]]
            self.assertEqual(set(private["mapping"].values()), {"rules", "strong_rules", "ai"})
            label = next(k for k, v in private["mapping"].items() if v == "ai")
            self.assertEqual(public["options"][label], visible(row["ai"], row["ai"]["attributions"]))
            fallback += row["ai"]["mode"] == "rules_fallback"
        self.assertEqual(fallback, 2)
        public_text = canonical(self.packet).decode()
        for forbidden in ("private_source_mapping", "source_case_id", "gpt-5.6-luna", "providerOutcome", '"strong_rules"', '"rules_fallback"', '"mode"'):
            self.assertNotIn(forbidden, public_text)
        self.assertEqual(len(self.packet["cases"]), 10)

    def test_no_annotations_is_pending_and_does_not_invent_human_scores(self):
        result = score(self.packet, self.key, [])
        self.assertIsNone(result["human_review"])
        self.assertEqual(result["completed_case_ratings"], 0)
        self.assertEqual(result["ai_vs_strong_rules"]["incomplete"], 10)
        self.assertIsNone(result["ratings"]["ai"]["clarity_mean"])

    def test_incomplete_annotation_is_not_a_tie_or_win(self):
        result = score(self.packet, self.key, [self.annotation])
        self.assertEqual(result["ai_vs_rules"]["incomplete"], 10)
        self.assertIsNone(result["human_review"])
        partial = self.completed()
        partial["cases"][0]["comparisons"][0]["reason"] = ""
        result = score(self.packet, self.key, [partial])
        self.assertEqual(result["unique_cases_reviewed"], 9)
        self.assertEqual(result["ai_vs_rules"]["ties"], 9)
        self.assertEqual(result["ai_vs_rules"]["incomplete"], 1)

    def test_ties_valid_and_raters_not_pooled_as_additional_cases(self):
        result = score(self.packet, self.key, [self.completed("fixture1"), self.completed("fixture2")])
        self.assertEqual(result["completed_case_ratings"], 20)
        self.assertEqual(result["unique_cases_reviewed"], 10)
        self.assertEqual(result["ai_vs_strong_rules"]["ties"], 10)
        self.assertEqual(result["ratings"]["ai"]["complete_presentations"], 20)
        self.assertTrue(all(c["missing_or_incomplete_ratings"] == 0 for c in result["per_case"].values()))

    def test_source_mapping_decodes_winners_and_disagreements(self):
        annotations = [self.completed("fixture1"), self.completed("fixture2")]
        for annotation in annotations:
            for row, private in zip(annotation["cases"], self.key["cases"]):
                for pair in row["comparisons"]:
                    pair["winner"] = next((label for label in (pair["left"], pair["right"])
                        if private["mapping"][label] == "ai"), "TIE")
        result = score(self.packet, self.key, annotations)
        self.assertEqual(result["ai_vs_strong_rules"]["wins"], 10)
        annotations[1]["cases"][0]["comparisons"] = self.completed()["cases"][0]["comparisons"]
        result = score(self.packet, self.key, annotations)
        self.assertEqual(result["ai_vs_strong_rules"]["rater_disagreement"], 1)
        self.assertEqual(result["ai_vs_strong_rules"]["wins"], 9)

    def test_duplicate_reviewer_or_key_tampering_rejected(self):
        with self.assertRaises(ValueError):
            score(self.packet, self.key, [self.completed(), self.completed()])
        for mutation in ("packet", "mapping", "source_case"):
            packet, key = copy.deepcopy(self.packet), copy.deepcopy(self.key)
            if mutation == "packet":
                packet["cases"][0]["options"]["A"]["summary"] = "changed"
            elif mutation == "mapping":
                key["cases"][0]["mapping"]["A"] = key["cases"][0]["mapping"]["B"]
            else:
                key["cases"][0]["source_case_id"] = key["cases"][1]["source_case_id"]
            with self.assertRaises(ValueError):
                score(packet, key, [])

    def test_foreign_missing_duplicate_cases_and_scores_rejected(self):
        mutations = [
            lambda a: a.update(packet_id="foreign"),
            lambda a: a.update(reviewer_id=""),
            lambda a: a["cases"].pop(),
            lambda a: a["cases"][0].update(case_id=a["cases"][1]["case_id"]),
            lambda a: a["cases"][0]["evaluations"][0].update(label="D"),
            lambda a: a["cases"][0]["evaluations"][0].update(clarity=True),
            lambda a: a["cases"][0]["evaluations"][0].update(calibration=6),
            lambda a: a["cases"][0]["evaluations"][0].update(covered_goal_ids=["foreign"]),
            lambda a: a["cases"][0]["evaluations"][0].update(covered_goal_ids=["g1", "g1"]),
            lambda a: a["cases"][0]["evaluations"][0].update(unsupported_claims=[""]),
            lambda a: a["cases"][0]["evaluations"][0].update(execute=True),
            lambda a: a["cases"][0]["comparisons"][0].update(winner="C"),
            lambda a: a["cases"][0]["comparisons"][1].update(left="A", right="B"),
        ]
        for mutate in mutations:
            annotation = self.completed()
            mutate(annotation)
            with self.subTest(annotation=annotation["packet_id"]):
                with self.assertRaises(ValueError):
                    validate_annotations(self.packet, annotation)

    def test_json_duplicate_keys_and_non_finite_values_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "invalid.json"
            for source in ('{"case_id":1,"case_id":2}', '{"clarity":NaN}', '{"score":Infinity}'):
                path.write_text(source)
                with self.assertRaises(ValueError):
                    load(path)

    def test_unfinished_run_rejected_and_html_prose_cannot_escape_data(self):
        unfinished = copy.deepcopy(self.report)
        unfinished["complete"] = False
        with self.assertRaises(ValueError):
            make_packet(unfinished)
        self.packet["cases"][0]["options"]["A"]["summary"] = '</script><img src=x onerror="alert(1)">__SCRIPT__'
        html = render(self.packet, self.annotation)
        self.assertNotIn('<img src=x', html)
        self.assertIn('\\u003c/script\\u003e', html)
        self.assertIn("connect-src 'none'", html)
        self.assertIn("textContent", html)
        self.assertNotIn("innerHTML", html)
        self.assertNotIn('fetch(', html)


if __name__ == "__main__":
    unittest.main()
