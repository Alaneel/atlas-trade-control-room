package com.gic.explanation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CrossEventVerifierTest {
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    private CrossEventVerifier.Proposal proposal(String relation, String from, String to, List<String> refs) {
        return new CrossEventVerifier.Proposal(relation, from, to, refs,
            "A possible explanation of this observed pattern.", "The physical cause is not established; inspect existing records only.");
    }
    @Test void frozenRelationsAreSupportedByTheStrongComparatorAndInvalidEvidenceAbstains() throws Exception {
        for (var row : CrossEventFixtures.rows()) {
            var snapshot = CrossEventFixtures.JSON.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            var analysis = engine.analyze(snapshot);
            assertThat(analysis.abstained()).as(row.path("id").asText()).isEqualTo(row.path("expectedAbstention").asBoolean());
            var strong = CrossEventVerifier.strongBaseline(analysis);
            for (var relation : row.path("expectedRelations")) assertThat(strong).as(row.path("id").asText())
                .extracting(Explanation.Attribution::relation).contains(relation.asText());
            if (analysis.abstained()) assertThat(strong).isEmpty();
        }
    }
    @Test void actualTimeDirectionAndFirstUnknownAreCheckedRatherThanTrustingAModelLabel() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_recorded_later");
        var analysis = engine.analyze(snapshot); String receipt = "receipt:" + snapshot.tradeId();
        String unknown = snapshot.events().get(2).id();
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown, List.of(receipt, unknown)), analysis))
            .isInstanceOf(ModelPlanner.Failure.class);
        assertThat(CrossEventVerifier.verify(proposal("RECEIPT_AFTER_UNKNOWN", unknown, receipt, List.of(unknown, receipt)), analysis).observedRelation())
            .contains("28 seconds after", "not a network-arrival measurement");
        var repeated = CrossEventFixtures.get("old_receipt_state_still_unknown");
        String latest = repeated.events().get(3).id(), repeatedReceipt = "receipt:" + repeated.tradeId();
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("RECEIPT_BEFORE_UNKNOWN", repeatedReceipt, latest, List.of(repeatedReceipt, latest)), engine.analyze(repeated)))
            .isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void aReceiptCannotInventSettlementAndCompletedRecoveryNeedsThreeRelevantCitations() throws Exception {
        var unknown = CrossEventFixtures.get("receipt_before_uncertainty");
        String from = unknown.events().get(2).id(), to = unknown.events().get(2).id();
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("RECOVERY_COMPLETED", from, to, List.of(from)), engine.analyze(unknown)))
            .isInstanceOfAny(ModelPlanner.Failure.class, IllegalArgumentException.class);
        var done = CrossEventFixtures.get("eventual_execution"); var analysis = engine.analyze(done);
        String first = done.events().get(2).id(), terminal = done.events().get(4).id();
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("RECOVERY_COMPLETED", first, terminal, List.of(first, terminal)), analysis))
            .isInstanceOf(ModelPlanner.Failure.class);
        var checked = CrossEventVerifier.verify(proposal("RECOVERY_COMPLETED", first, terminal, List.of(first, terminal, "receipt:" + done.tradeId())), analysis);
        assertThat(checked.observedRelation()).contains("29 seconds", "EXECUTED", "historical");
    }
    @Test void reversedTimestampsAndRapidCounterexamplesCannotBecomeLongDelayDiagnoses() throws Exception {
        var skew = CrossEventFixtures.get("timestamp_sequence_disagreement");
        String from = skew.events().get(1).id(), to = skew.events().get(2).id();
        assertThat(CrossEventVerifier.verify(proposal("TEMPORAL_ORDER_UNRELIABLE", from, to, List.of(from, to)), engine.analyze(skew)).observedRelation()).contains("disagree");
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("DISPATCH_DELAY", skew.events().get(0).id(), from,
            List.of(skew.events().get(0).id(), from)), engine.analyze(skew))).isInstanceOf(ModelPlanner.Failure.class);
        var rapid = CrossEventFixtures.get("rapid_terminal_counterexample");
        String receipt = "receipt:" + rapid.tradeId(), last = rapid.events().get(3).id();
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("SETTLEMENT_GAP", receipt, last, List.of(receipt, last)), engine.analyze(rapid)))
            .isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void extraActionsForeignCitationsAndActiveMarkupAreRejectedAtThePlanBoundary() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_before_uncertainty"); var analysis = engine.analyze(snapshot);
        var plan = CrossEventFixtures.JSON.valueToTree(ExplanationPlan.baseline(analysis));
        var mutable = (com.fasterxml.jackson.databind.node.ObjectNode) plan;
        String receipt = "receipt:" + snapshot.tradeId(), unknown = snapshot.events().get(2).id();
        var valid = proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown, List.of(receipt, unknown));
        mutable.putArray("attributions").add(CrossEventFixtures.JSON.valueToTree(valid));
        assertThat(ExplanationPlan.parseAndValidate(plan.toString(), analysis).attributions()).hasSize(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) plan.path("attributions").get(0)).put("executeTrade", true);
        assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(plan.toString(), analysis)).isInstanceOf(ModelPlanner.Failure.class);
        assertThatThrownBy(() -> CrossEventVerifier.verify(proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown, List.of(receipt, "foreign-event")), analysis))
            .isInstanceOf(ModelPlanner.Failure.class);
        assertThatThrownBy(() -> CrossEventVerifier.verify(new CrossEventVerifier.Proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown,
            List.of(receipt, unknown), "<a href='https://bad.example'>Open</a>", "uncertain"), analysis)).isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void modelNarrativeIsExposedAsAHypothesisWhileTheRuleFactsAndCurrentStateStayAuthoritative() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_before_uncertainty"); var analysis = engine.analyze(snapshot);
        var baseline = ExplanationPlan.baseline(analysis);
        String receipt = "receipt:" + snapshot.tradeId(), unknown = snapshot.events().get(2).id();
        var proposed = new ExplanationPlan(baseline.snapshotHash(), baseline.headlineId(), baseline.findingIds(), baseline.checkIds(),
            List.of(proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown, List.of(receipt, unknown))));
        var result = new ExplanationService(id -> snapshot, engine, ignored -> new ModelPlanner.Reply(
            CrossEventFixtures.JSON.valueToTree(proposed).toString(), "fixture", null, null, false)).explain(snapshot.tradeId(), true);
        assertThat(result.mode()).isEqualTo("fixture_attributed");
        assertThat(result.tradeStatus()).isEqualTo("UNKNOWN");
        assertThat(result.summary()).contains("uncertain", "remain held");
        assertThat(result.attributions()).hasSize(1);
        assertThat(result.attributions().get(0).validationScope()).contains("not semantically verified", "No operations");
    }
    @Test void aStateChangeDuringGenerationDiscardsTheEntireCrossEventNarrative() throws Exception {
        var initial = CrossEventFixtures.get("receipt_before_uncertainty");
        var events = new java.util.ArrayList<>(initial.events());
        events.add(new ExplanationSnapshot.Event(initial.tradeId() + ":3", initial.tradeId(), 3,
            com.gic.reliability.TradeStatus.EXECUTED, com.gic.reliability.DecisionCode.VENUE_ACCEPTED, initial.ruleVersion(), null,
            initial.events().get(2).at().plusSeconds(1)));
        var fresh = new ExplanationSnapshot(initial.tradeId(), 3, com.gic.reliability.TradeStatus.EXECUTED, initial.amount(), initial.currency(),
            com.gic.reliability.DecisionCode.VENUE_ACCEPTED, initial.ruleVersion(), null, events, initial.receipt(), true);
        var readCount = new java.util.concurrent.atomic.AtomicInteger();
        var result = new ExplanationService(id -> readCount.getAndIncrement() == 0 ? initial : fresh, engine, analysis -> {
            var baseline = ExplanationPlan.baseline(analysis);
            String receipt = "receipt:" + initial.tradeId(), unknown = initial.events().get(2).id();
            var plan = new ExplanationPlan(baseline.snapshotHash(), baseline.headlineId(), baseline.findingIds(), baseline.checkIds(),
                List.of(proposal("RECEIPT_BEFORE_UNKNOWN", receipt, unknown, List.of(receipt, unknown))));
            return new ModelPlanner.Reply(CrossEventFixtures.JSON.valueToTree(plan).toString(), "fixture", null, null, false);
        }).explain(initial.tradeId(), true);
        assertThat(result.providerOutcome()).isEqualTo("snapshot_changed");
        assertThat(result.tradeStatus()).isEqualTo("EXECUTED");
        assertThat(result.attributions()).isEmpty();
    }
}
