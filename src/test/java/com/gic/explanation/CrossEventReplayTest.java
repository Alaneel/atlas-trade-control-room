package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Replays every retained provider response through current production guards. No API credentials/calls. */
class CrossEventReplayTest {
    @Test void finalRefactoringPreservesAllRecordedAcceptedAndRejectedOutcomesWithoutPaidRequests() throws Exception {
        var json = CrossEventFixtures.JSON;
        var engine = new ExplanationEngine(new RuleCatalog());
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (String directory : List.of("cross-event-v2-first-round", "cross-event-v3-second-round", "cross-event-luna-round", "cross-event-luna-v4-round")) {
            var recorded = json.readTree(Path.of("docs/examples/ai-explanations", directory, "results.json").toFile());
            int replayed = 0, controls = 0;
            for (var row : recorded.path("results")) {
                String caseId = row.path("id").asText();
                var snapshot = CrossEventFixtures.get(caseId);
                String plan = null;
                String format = null, binding = null;
                for (var trace : recorded.path("requests")) if ("generation".equals(trace.path("kind").asText())
                        && caseId.equals(trace.path("case_id").asText())) {
                    plan = trace.path("returned_json").asText();
                    format = trace.path("reply_format").asText();
                    binding = trace.path("request_snapshot_hash").asText();
                }
                String retainedPlan = plan;
                boolean v4 = "ATTRIBUTIONS_V4".equals(recorded.path("response_contract").asText());
                if (v4 && plan != null) {
                    assertThat(format).isEqualTo("ATTRIBUTIONS_V4");
                    assertThat(binding).isEqualTo(engine.analyze(snapshot).snapshotHash());
                }
                var actual = new ExplanationService(id -> snapshot, engine, ignored -> {
                    if (retainedPlan == null) throw new AssertionError("Abstention must not invoke the replay planner");
                    var reply = new ModelPlanner.Reply(retainedPlan, "recorded-replay", null, null, false);
                    return v4 ? reply.boundAttributions(engine.analyze(snapshot).snapshotHash()) : reply;
                }).explain(snapshot.tradeId(), true);
                assertThat(actual.modelCalls()).isZero();
                assertThat(actual.providerOutcome()).as(directory + ":" + caseId).isEqualTo(row.at("/ai/providerOutcome").asText());
                assertThat(actual.summary()).isEqualTo(row.at("/ai/summary").asText());
                assertThat(actual.snapshotHash()).isEqualTo(row.at("/ai/snapshotHash").asText());
                assertThat(json.<JsonNode>valueToTree(actual.findings())).isEqualTo(row.at("/ai/findings"));
                assertThat(json.<JsonNode>valueToTree(actual.nextChecks())).isEqualTo(row.at("/ai/nextChecks"));
                assertThat(actual.attributions()).hasSize(row.at("/ai/attributions").size());
                for (int index = 0; index < actual.attributions().size(); index++) {
                    var original = row.at("/ai/attributions").get(index); var current = actual.attributions().get(index);
                    assertThat(current.relation()).isEqualTo(original.path("relation").asText());
                    assertThat(json.<JsonNode>valueToTree(current.evidenceIds())).isEqualTo(original.path("evidenceIds"));
                    assertThat(current.observedRelation()).isEqualTo(original.path("observedRelation").asText());
                    assertThat(current.interpretation()).isEqualTo(original.path("interpretation").asText());
                    assertThat(current.uncertainty()).isEqualTo(original.path("uncertainty").asText());
                }
                if (row.path("expected_abstention").asBoolean()) controls++; else replayed++;
            }
            rounds.add(Map.of("round", directory, "provider_responses_replayed", replayed, "abstention_controls", controls,
                "all_outcomes_preserved", true, "real_model_requests", 0));
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/atlas-cross-event-replay.json").toFile(), Map.of(
            "kind", "retained_provider_output_regression_replay", "scope", "Current guards retain the same accepted/rejected observations and text. This is not a new model run or new semantic quality evidence.",
            "rounds", rounds));
    }
}
