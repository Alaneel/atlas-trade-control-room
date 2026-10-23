package com.gic.explanation;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExplanationServiceTest {
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    @Test
    void modelUnavailableFallsBackWithoutClaimingARealModelCall() {
        var snapshot = ExplanationFixtures.get("capacity_rejection");
        var service = new ExplanationService(id -> snapshot, engine,
            analysis -> { throw new ModelPlanner.Failure("not_configured", false); });
        var explanation = service.explain(snapshot.tradeId(), true);
        assertThat(explanation.mode()).isEqualTo("rules_fallback");
        assertThat(explanation.providerOutcome()).isEqualTo("not_configured");
        assertThat(explanation.modelCalls()).isZero();
        assertThat(explanation.summary()).contains("5.00", "10.00");
    }
    @Test
    void incompleteHistoryAbstainsBeforeAnyProviderCall() {
        var snapshot = ExplanationFixtures.get("missing_events");
        var explanation = new ExplanationService(id -> snapshot, engine, analysis -> {
            throw new AssertionError("An incomplete snapshot must never reach the provider");
        }).explain(snapshot.tradeId(), true);
        assertThat(explanation.abstained()).isTrue();
        assertThat(explanation.modelCalls()).isZero();
        assertThat(explanation.providerOutcome()).isEqualTo("evidence_abstention");
    }
    @Test
    void changesDuringModelRequestInvalidateItsPlan() throws Exception {
        var initial = ExplanationFixtures.get("unknown_success_receipt");
        var settled = ExplanationFixtures.get("executed_receipt");
        // Use the same real trade identity while replacing its complete immutable view.
        var events = settled.events().stream().map(event -> new ExplanationSnapshot.Event(initial.tradeId() + ":" + event.sequence(),
            initial.tradeId(), event.sequence(), event.status(), event.decisionCode(), event.ruleVersion(), event.availableAtDecision(), event.at())).toList();
        var fresh = new ExplanationSnapshot(initial.tradeId(), settled.version(), settled.status(), settled.amount(), settled.currency(),
            settled.decisionCode(), settled.ruleVersion(), settled.availableAtDecision(), events, settled.receipt(), true);
        AtomicInteger reads = new AtomicInteger();
        var result = new ExplanationService(id -> reads.getAndIncrement() == 0 ? initial : fresh, engine,
            analysis -> {
                try { return new ModelPlanner.Reply(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ExplanationPlan.baseline(analysis)), "fixture", null, null, false); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }).explain(initial.tradeId(), true);
        assertThat(result.providerOutcome()).isEqualTo("snapshot_changed");
        assertThat(result.tradeStatus()).isEqualTo("EXECUTED");
        assertThat(result.summary()).contains("committed as EXECUTED");
    }
    @Test
    void receiptPresenceDoesNotTurnUnknownIntoExecuted() {
        var snapshot = ExplanationFixtures.get("unknown_success_receipt");
        var result = new ExplanationService(id -> snapshot, engine, analysis -> { throw new AssertionError(); }).explain(snapshot.tradeId(), false);
        assertThat(result.tradeStatus()).isEqualTo("UNKNOWN");
        assertThat(result.summary()).contains("uncertain", "remain held");
        assertThat(result.findings()).extracting(Explanation.Finding::id).contains("evidence.receipt");
        assertThat(result.nextChecks()).allSatisfy(check -> assertThat(check.id()).startsWith("inspect."));
    }
    @Test
    void explanationServiceHasOnlyReadModelAndPlanningDependencies() {
        var types = java.util.Arrays.stream(ExplanationService.class.getDeclaredFields()).map(field -> field.getType().getSimpleName()).toList();
        assertThat(types).containsExactlyInAnyOrder("ExplanationReader", "ExplanationEngine", "ModelPlanner");
        assertThat(java.util.Arrays.stream(ExplanationReader.class.getMethods()).map(java.lang.reflect.Method::getName).toList())
            .containsExactly("load");
    }
}
