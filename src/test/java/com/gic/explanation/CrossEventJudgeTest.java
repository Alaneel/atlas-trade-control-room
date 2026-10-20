package com.gic.explanation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CrossEventJudgeTest {
    private ObjectNode valid() {
        return CrossEventFixtures.JSON.valueToTree(Map.of("evaluations", List.of("A", "B", "C").stream().map(label -> Map.of(
            "label", label, "coveredGoalIds", List.of("g1"), "unsupportedClaims", List.of(), "citationErrors", List.of(),
            "unsafeAdvice", List.of(), "clarity", 4, "calibration", 5)).toList(), "comparisons", List.of(
            Map.of("left", "A", "right", "B", "winner", "TIE", "reason", "Equivalent supported content."),
            Map.of("left", "A", "right", "C", "winner", "C", "reason", "C links the recorded events more clearly."),
            Map.of("left", "B", "right", "C", "winner", "TIE", "reason", "Equivalent supported content."))));
    }
    @Test void aReviewerMustScoreExactlyThreeLabelsAndEveryPairOnce() throws Exception {
        assertThat(CrossEventJudge.validate(valid().toString(), Set.of("g1")).path("evaluations")).hasSize(3);
        var duplicate = valid(); ((ObjectNode) duplicate.path("evaluations").get(1)).put("label", "A");
        assertThatThrownBy(() -> CrossEventJudge.validate(duplicate.toString(), Set.of("g1"))).isInstanceOf(IllegalArgumentException.class);
        var wrongWinner = valid(); ((ObjectNode) wrongWinner.path("comparisons").get(0)).put("winner", "C");
        assertThatThrownBy(() -> CrossEventJudge.validate(wrongWinner.toString(), Set.of("g1"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownReferenceGoalsAndOutOfRangeScoresCannotBecomeQualityResults() {
        assertThatThrownBy(() -> CrossEventJudge.validate(valid().toString(), Set.of("different-goal"))).isInstanceOf(IllegalArgumentException.class);
        var invalid = valid(); ((ObjectNode) invalid.path("evaluations").get(0)).put("clarity", 10);
        assertThatThrownBy(() -> CrossEventJudge.validate(invalid.toString(), Set.of("g1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
