package com.gic.explanation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AttributionReplyTest {
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    private ModelPlanner.Reply reply(String raw, ExplanationEngine.Analysis analysis) {
        return new ModelPlanner.Reply(raw, "fixture", null, null, false).boundAttributions(analysis.snapshotHash());
    }
    private String body(CrossEventVerifier.Proposal... proposals) throws Exception {
        return CrossEventFixtures.JSON.writeValueAsString(Map.of("attributions", List.of(proposals)));
    }
    private CrossEventVerifier.Proposal before() {
        return new CrossEventVerifier.Proposal("RECEIPT_BEFORE_UNKNOWN", "R", "E2", List.of("R", "E2"),
            "An acknowledgement or local settlement gap is possible.", "The mechanism is unproven; inspect existing records only.");
    }
    @Test void authoritativeFindingsAndChecksAreAlwaysRenderedByTheServer() throws Exception {
        for (var row : CrossEventFixtures.rows()) {
            var snapshot = CrossEventFixtures.get(row.path("id").asText());
            var service = new ExplanationService(id -> snapshot, engine, a -> reply("{\"attributions\":[]}", a));
            var baseline = service.explain(snapshot.tradeId(), false);
            var result = service.explain(snapshot.tradeId(), true);
            assertThat(result.summary()).isEqualTo(baseline.summary());
            assertThat(result.findings()).isEqualTo(baseline.findings());
            assertThat(result.nextChecks()).isEqualTo(baseline.nextChecks());
            assertThat(result.snapshotHash()).isEqualTo(baseline.snapshotHash());
            assertThat(result.attributions()).isEmpty();
            assertThat(result.mode()).isEqualTo(result.abstained() ? "rules" : "fixture_no_attribution");
        }
    }
    @Test void attributionOnlyRepliesRequireAnExplicitTrustedFormatAndExactRequestBinding() throws Exception {
        var analysis = engine.analyze(CrossEventFixtures.get("receipt_before_uncertainty"));
        String raw = body(before());
        assertThat(ExplanationPlan.parseAndValidate(reply(raw, analysis), analysis).attributions()).hasSize(1);
        assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(new ModelPlanner.Reply(raw, "fixture", null, null, false), analysis))
            .isInstanceOf(ModelPlanner.Failure.class);
        for (String binding : List.of("", "0".repeat(64))) {
            assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(new ModelPlanner.Reply(raw, "fixture", null, null, false)
                .boundAttributions(binding), analysis)).isInstanceOf(ModelPlanner.Failure.class);
        }
        assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(new ModelPlanner.Reply(raw, "fixture", null, null, false)
            .boundAttributions(null), analysis)).isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void modelJsonCannotSetItsOwnFormatBindingFactsOrOperations() throws Exception {
        var analysis = engine.analyze(CrossEventFixtures.get("receipt_before_uncertainty"));
        for (String field : List.of("snapshotHash", "headlineId", "findingIds", "checkIds", "format", "requestSnapshotHash",
                "executeTrade", "increaseLimit", "reconcile")) {
            var attack = (ObjectNode) CrossEventFixtures.JSON.readTree(body(before())); attack.put(field, "malicious-value");
            assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(reply(attack.toString(), analysis), analysis))
                .as(field).isInstanceOf(ModelPlanner.Failure.class);
        }
        String legacy = CrossEventFixtures.JSON.writeValueAsString(ExplanationPlan.baseline(analysis));
        assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(reply(legacy, analysis), analysis)).isInstanceOf(ModelPlanner.Failure.class);
        for (String malformed : List.of("{}", "null", "[]", "{\"attributions\":null}", "{\"attributions\":{}}",
                "{\"attributions\":[],\"attributions\":[]}", "{\"attributions\":[]} {}", "x".repeat(8193))) {
            assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(reply(malformed, analysis), analysis))
                .isInstanceOf(ModelPlanner.Failure.class);
        }
    }
    @Test void oneInvalidOrDuplicateAttributionStillRejectsTheWholeReply() throws Exception {
        var snapshot = CrossEventFixtures.get("receipt_before_uncertainty");
        var analysis = engine.analyze(snapshot);
        var wrong = new CrossEventVerifier.Proposal("REPEATED_UNKNOWN", "E2", "E2", List.of("E2"), "Repeated uncertainty.", "Unknown cause.");
        for (String raw : List.of(body(before(), wrong), body(before(), before()), body(before(), before(), before(), before()))) {
            var result = new ExplanationService(id -> snapshot, engine, a -> reply(raw, analysis)).explain(snapshot.tradeId(), true);
            assertThat(result.providerOutcome()).isEqualTo("invalid_output");
            assertThat(result.mode()).isEqualTo("rules_fallback"); assertThat(result.attributions()).isEmpty();
            assertThat(result.findings()).isEqualTo(engine.analyze(snapshot).findings());
        }
    }
    @Test void v4DoesNotCoerceForeignCitationsBadTypesOrActiveNarrativeIntoValidEvidence() throws Exception {
        var a = engine.analyze(CrossEventFixtures.get("receipt_before_uncertainty"));
        List<java.util.function.Consumer<ObjectNode>> mutations = List.of(
            p -> p.put("fromId", "foreign-event"), p -> p.put("toId", "E0"), p -> p.put("relation", "RECOVERY_COMPLETED"),
            p -> p.putArray("evidenceIds").add("R").add("R"), p -> p.putArray("evidenceIds").add("R").add("E2").add("Q"),
            p -> p.put("evidenceIds", "R,E2"), p -> p.putArray("evidenceIds").add("R").add(42),
            p -> p.putNull("interpretation"), p -> p.put("uncertainty", false),
            p -> p.put("interpretation", "x".repeat(601)), p -> p.put("uncertainty", ""),
            p -> p.put("interpretation", "<script>execute()</script>"),
            p -> p.put("interpretation", "Inspect https://untrusted.example"), p -> p.put("uncertainty", "First\nsecond"));
        for (var mutation : mutations) {
            var payload = (ObjectNode) CrossEventFixtures.JSON.readTree(body(before()));
            mutation.accept((ObjectNode) payload.path("attributions").get(0));
            assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(reply(payload.toString(), a), a))
                .isInstanceOf(ModelPlanner.Failure.class);
        }
    }
    @Test void aReplyBoundToAnotherTradeCannotReuseIdenticallyNamedAliases() throws Exception {
        var a = engine.analyze(CrossEventFixtures.get("receipt_before_uncertainty"));
        var b = engine.analyze(CrossEventFixtures.get("old_receipt_state_still_unknown"));
        assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(reply(body(before()), a), b)).isInstanceOf(ModelPlanner.Failure.class);
    }
    @Test void aConcurrentStateChangeDiscardsTheEntireCorrectlyBoundNarrative() throws Exception {
        var initial = CrossEventFixtures.get("receipt_before_uncertainty");
        var events = new ArrayList<>(initial.events());
        events.add(new ExplanationSnapshot.Event(initial.tradeId() + ":3", initial.tradeId(), 3,
            com.gic.reliability.TradeStatus.EXECUTED, com.gic.reliability.DecisionCode.VENUE_ACCEPTED, initial.ruleVersion(), null,
            initial.events().get(2).at().plusSeconds(1)));
        var fresh = new ExplanationSnapshot(initial.tradeId(), 3, com.gic.reliability.TradeStatus.EXECUTED, initial.amount(), initial.currency(),
            com.gic.reliability.DecisionCode.VENUE_ACCEPTED, initial.ruleVersion(), null, events, initial.receipt(), true);
        AtomicInteger reads = new AtomicInteger(); String raw = body(before());
        var actual = new ExplanationService(id -> reads.getAndIncrement() == 0 ? initial : fresh, engine, a -> reply(raw, a))
            .explain(initial.tradeId(), true);
        assertThat(actual.providerOutcome()).isEqualTo("snapshot_changed"); assertThat(actual.attributions()).isEmpty();
        assertThat(actual.tradeStatus()).isEqualTo("EXECUTED"); assertThat(actual.findings()).isEqualTo(engine.analyze(fresh).findings());
    }
}
