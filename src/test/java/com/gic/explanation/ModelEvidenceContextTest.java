package com.gic.explanation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ModelEvidenceContextTest {
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    @Test void shortAliasesAndNumericOffsetsPreserveEvidenceWithoutSupplyingAnswersOrOpaqueIds() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_recorded_later"); var analysis = engine.analyze(snapshot);
        var context = CrossEventFixtures.JSON.valueToTree(ModelEvidenceContext.create(analysis));
        assertThat(context.at("/timeline/events/2/id").asText()).isEqualTo("E2");
        assertThat(context.at("/timeline/events/2/recordedOffsetMillis").asLong()).isEqualTo(2000);
        assertThat(context.at("/timeline/receipt/recordedOffsetMillis").asLong()).isEqualTo(30000);
        assertThat(context.toString()).doesNotContain(snapshot.tradeId(), "expectedRelations", "reviewGoals", "availableRelations");
        assertThat(ModelEvidenceContext.aliases(analysis).get("R")).isEqualTo("receipt:" + snapshot.tradeId());
    }
    @Test void aliasesResolveWithinTheSnapshotAndUnknownOrForeignReferencesStayInvalid() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_before_uncertainty"); var analysis = engine.analyze(snapshot);
        var baseline = ExplanationPlan.baseline(analysis);
        var proposal = new CrossEventVerifier.Proposal("RECEIPT_BEFORE_UNKNOWN", "R", "E2", List.of("R", "E2"),
            "An acknowledgement or commit gap is possible.", "The physical cause is unproven.");
        var plan = new ExplanationPlan(baseline.snapshotHash(), baseline.headlineId(), baseline.findingIds(), baseline.checkIds(), List.of(proposal));
        var parsed = ExplanationPlan.parseAndValidate(CrossEventFixtures.JSON.valueToTree(plan).toString(), analysis);
        assertThat(parsed.attributions().get(0).fromId()).isEqualTo("receipt:" + snapshot.tradeId());
        assertThat(parsed.attributions().get(0).evidenceIds()).containsExactly("receipt:" + snapshot.tradeId(), snapshot.tradeId() + ":2");
        var wrong = new CrossEventVerifier.Proposal("RECEIPT_BEFORE_UNKNOWN", "R", "E999", List.of("R", "E999"), "Possible gap.", "Unproven.");
        assertThatThrownBy(() -> ModelEvidenceContext.resolve(wrong, analysis)).isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void numericOffsetsStillExposeReversedTimeAndAbsentReceiptsHaveNoReceiptAlias() throws Exception {
        var analysis = engine.analyze(CrossEventFixtures.get("timestamp_sequence_disagreement"));
        var context = CrossEventFixtures.JSON.valueToTree(ModelEvidenceContext.create(analysis));
        assertThat(context.at("/timeline/events/1/recordedOffsetMillis").asLong()).isEqualTo(5000);
        assertThat(context.at("/timeline/events/2/recordedOffsetMillis").asLong()).isEqualTo(3000);
        assertThat(ModelEvidenceContext.operandAliases(analysis)).contains("E0", "E1", "E2", "Q").doesNotContain("R");
    }
    @Test void v4KeepsIdenticalTypedEvidenceButNoModelOwnedFactualSelectionOrSnapshotCopy() throws Exception {
        for (var row : CrossEventFixtures.rows()) {
            var analysis = engine.analyze(CrossEventFixtures.get(row.path("id").asText()));
            if (analysis.abstained()) continue;
            var original = CrossEventFixtures.JSON.valueToTree(ModelEvidenceContext.create(analysis));
            var current = CrossEventFixtures.JSON.valueToTree(ModelEvidenceContext.attributions(analysis));
            assertThat(current.path("timeline")).isEqualTo(original.path("timeline"));
            assertThat(current.path("evidence")).isEqualTo(original.path("evidence"));
            assertThat(current.path("relationDefinitions")).isEqualTo(original.path("relationDefinitions"));
            assertThat(current.toString()).doesNotContain(analysis.snapshotHash(), "snapshotHash", "headlineId", "required",
                "findingIds", "checkIds", "expectedRelations", "reviewGoals", "availableRelations");
            assertThat(current.path("authoritativeFacts").size()).isEqualTo(analysis.findings().size());
        }
    }
}
