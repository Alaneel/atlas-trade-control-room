package com.gic.explanation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CrossEventBoundaryTest {
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    @Test void linearSelectionMatchesRetainedEnumerationUnderTheSameProofKernel() throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        for (var test : CrossEventBoundaryFixtures.cases()) {
            var a = engine.analyze(test.snapshot()); assertThat(a.abstained()).as(test.id()).isFalse();
            var actual = CrossEventVerifier.strongBaseline(a);
            assertThat(actual).as(test.id()).isEqualTo(CrossEventRuleOracle.enumerate(a));
            rows.add(Map.of("id", test.id(), "event_count", test.snapshot().events().size(), "snapshot_hash", a.snapshotHash(),
                "snapshot", test.snapshot(), "relations", actual.stream().map(Explanation.Attribution::relation).toList(), "equivalent", true));
        }
        for (var row : CrossEventFixtures.rows()) {
            var a = engine.analyze(CrossEventFixtures.get(row.path("id").asText()));
            assertThat(CrossEventVerifier.strongBaseline(a)).isEqualTo(CrossEventRuleOracle.enumerate(a));
        }
        Files.createDirectories(Path.of("target"));
        CrossEventFixtures.JSON.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .writerWithDefaultPrettyPrinter().writeValue(Path.of("target/atlas-cross-event-boundaries.json").toFile(),
            Map.of("kind", "offline_rule_selection_equivalence", "seed", 20261022, "generated_cases", rows,
                "frozen_cases", 12, "real_model_requests", 0,
                "scope", "Candidate selection equivalence under the same current proof/rendering kernel. Exact-threshold and sub-millisecond wording are separate changes. "
                    + "Development fixtures, not heldout semantic or model-quality evidence."));
    }
    @Test void aOneMillisecondThresholdCrossingChangesOnlySupportedGapRelations() {
        for (long gap : new long[]{59999, 60000, 60001}) {
            var dispatch = engine.analyze(CrossEventBoundaryFixtures.snapshot("dispatch" + gap,
                new long[]{0, gap, gap + 1000}, null, null));
            var dispatchRelations = CrossEventVerifier.strongBaseline(dispatch).stream().map(Explanation.Attribution::relation).toList();
            assertThat(dispatchRelations.contains("DISPATCH_DELAY")).isEqualTo(gap >= 60000);
            assertThat(dispatchRelations).contains("NO_RECEIPT_NOT_FAILURE");
            if (gap == 60000) assertThat(CrossEventVerifier.strongBaseline(dispatch).get(0).observedRelation())
                .contains("at the 60-second inspection threshold").doesNotContain("above");
            var settlement = engine.analyze(CrossEventBoundaryFixtures.snapshot("settlement" + gap,
                new long[]{0, 1000, 2000, 120000}, null, 120000 - gap));
            var settlementRelations = CrossEventVerifier.strongBaseline(settlement).stream().map(Explanation.Attribution::relation).toList();
            assertThat(settlementRelations.contains("SETTLEMENT_GAP")).isEqualTo(gap >= 60000);
        }
    }
    @Test void equalReceiptTimeDoesNotBecomeEitherBeforeOrAfter() {
        for (long delta : new long[]{-1, 0, 1}) {
            var a = engine.analyze(CrossEventBoundaryFixtures.snapshot("direction" + delta,
                new long[]{0, 1000, 2000}, null, 2000 + delta));
            var relations = CrossEventVerifier.strongBaseline(a).stream().map(Explanation.Attribution::relation).toList();
            assertThat(relations.contains("RECEIPT_BEFORE_UNKNOWN")).isEqualTo(delta < 0);
            assertThat(relations.contains("RECEIPT_AFTER_UNKNOWN")).isEqualTo(delta > 0);
        }
    }
    @Test void firstReversedPairKeepsOriginalLexicographicSelectionRatherThanOnlyAdjacentOrMinimum() {
        for (var item : List.of(Map.entry(new long[]{10000, 12000, 9000, 13000}, List.of(0, 2)),
                Map.entry(new long[]{0, 5000, 4000, 3000}, List.of(1, 2)))) {
            var s = CrossEventBoundaryFixtures.snapshot("clock" + item.getValue(), item.getKey(), null, null);
            var relations = CrossEventVerifier.strongBaseline(engine.analyze(s));
            assertThat(relations.get(0).relation()).isEqualTo("TEMPORAL_ORDER_UNRELIABLE");
            assertThat(relations.get(0).evidenceIds()).containsExactly(s.tradeId() + ":" + item.getValue().get(0),
                s.tradeId() + ":" + item.getValue().get(1));
            assertThat(relations).extracting(Explanation.Attribution::relation)
                .doesNotContain("DISPATCH_DELAY", "SETTLEMENT_GAP", "RECEIPT_BEFORE_UNKNOWN", "RECEIPT_AFTER_UNKNOWN", "RECOVERY_COMPLETED", "REPEATED_UNKNOWN");
        }
    }
    @Test void millisecondOverflowAbstainsBeforeEitherRuleOrModelTemporalProcessing() {
        var initial = CrossEventBoundaryFixtures.snapshot("overflow", new long[]{0, 1, 2}, null, null);
        var times = List.of(java.time.Instant.ofEpochMilli(Long.MIN_VALUE), java.time.Instant.ofEpochMilli(Long.MAX_VALUE - 1),
            java.time.Instant.ofEpochMilli(Long.MAX_VALUE));
        var events = initial.events().stream().map(e -> new ExplanationSnapshot.Event(e.id(), e.tradeId(), e.sequence(),
            e.status(), e.decisionCode(), e.ruleVersion(), e.availableAtDecision(), times.get((int) e.sequence()))).toList();
        var s = new ExplanationSnapshot(initial.tradeId(), initial.version(), initial.status(), initial.amount(), initial.currency(),
            initial.decisionCode(), initial.ruleVersion(), null, events, null, true);
        var service = new ExplanationService(id -> s, engine, a -> { throw new AssertionError("Overflow must abstain before a model call"); });
        assertThat(service.explainCrossEventRules(s.tradeId()).abstained()).isTrue();
        var ai = service.explain(s.tradeId(), true);
        assertThat(ai.abstained()).isTrue(); assertThat(ai.modelCalls()).isZero();
        assertThat(ai.summary()).contains("timestamp span");
    }
    @Test void aStrictSubMillisecondRelationDoesNotRenderAsZeroElapsedSeconds() {
        var s = CrossEventBoundaryFixtures.snapshot("nanosecond", new long[]{0, 1000, 2000}, null, 2000L);
        var receipt = new ExplanationSnapshot.Receipt(s.receipt().status(), s.receipt().amount(), true, s.receipt().at().minusNanos(1));
        var precise = new ExplanationSnapshot(s.tradeId(), s.version(), s.status(), s.amount(), s.currency(), s.decisionCode(),
            s.ruleVersion(), s.availableAtDecision(), s.events(), receipt, true);
        var actual = CrossEventVerifier.strongBaseline(engine.analyze(precise));
        assertThat(actual).extracting(Explanation.Attribution::relation).containsExactly("RECEIPT_BEFORE_UNKNOWN");
        assertThat(actual.get(0).observedRelation()).contains("0.000000001 seconds before");
    }
}
