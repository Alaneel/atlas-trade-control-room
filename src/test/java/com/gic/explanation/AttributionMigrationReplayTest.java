package com.gic.explanation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Counterfactual adapter replay. This MUST NOT be reported as a new model benchmark. */
class AttributionMigrationReplayTest {
    @Test void projectOnlyRetainedAttributionsWithoutRepairingTheirEvidenceOrProse() throws Exception {
        var json = CrossEventFixtures.JSON; var engine = new ExplanationEngine(new RuleCatalog());
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (String directory : List.of("cross-event-v2-first-round", "cross-event-v3-second-round", "cross-event-luna-round")) {
            Path path = Path.of("docs/examples/ai-explanations", directory, "results.json");
            var recorded = json.readTree(path.toFile()); List<Map<String, Object>> cases = new ArrayList<>();
            int accepted = 0, covered = 0, expected = 0; long originalContextBytes = 0, currentContextBytes = 0;
            for (var row : recorded.path("results")) {
                if (row.path("expected_abstention").asBoolean()) continue;
                String id = row.path("id").asText(); var snapshot = CrossEventFixtures.get(id); var a = engine.analyze(snapshot);
                var trace = java.util.stream.StreamSupport.stream(recorded.path("requests").spliterator(), false)
                    .filter(t -> "generation".equals(t.path("kind").asText()) && id.equals(t.path("case_id").asText())).findFirst().orElseThrow();
                var retained = json.readTree(trace.path("returned_json").asText());
                var projection = json.createObjectNode().set("attributions", retained.path("attributions"));
                String raw = projection.toString();
                var actual = new ExplanationService(ignored -> snapshot, engine, ignored ->
                    new ModelPlanner.Reply(raw, "offline-projection", null, null, false).boundAttributions(a.snapshotHash()))
                    .explain(snapshot.tradeId(), true);
                assertThat(actual.modelCalls()).isZero();
                assertThat(actual.findings()).isEqualTo(a.findings()); assertThat(actual.nextChecks()).isEqualTo(a.checks());
                var relations = new HashSet<>(actual.attributions().stream().map(Explanation.Attribution::relation).toList());
                int hits = 0; for (var relation : row.path("expected_relations")) if (relations.contains(relation.asText())) hits++;
                expected += row.path("expected_relations").size(); covered += hits;
                if ("validated".equals(actual.providerOutcome())) accepted++;
                originalContextBytes += json.writeValueAsBytes(ModelEvidenceContext.create(a)).length;
                currentContextBytes += json.writeValueAsBytes(ModelEvidenceContext.attributions(a)).length;
                cases.add(Map.of("id", id, "retained_outcome", row.at("/ai/providerOutcome").asText(),
                    "counterfactual_outcome", actual.providerOutcome(), "retained_response_sha256",
                    com.gic.reliability.TradeOrder.hash(trace.path("returned_json").asText()),
                    "attributions", actual.attributions(), "reference_relation_hits", hits));
            }
            rounds.add(Map.of("source", directory, "source_sha256", com.gic.reliability.TradeOrder.hash(Files.readString(path)),
                "counterfactual_accepted", accepted, "counterfactual_fallbacks", cases.size() - accepted,
                "reference_relations_covered", covered, "reference_relations_total", expected, "cases", cases,
                "old_context_utf8_bytes", originalContextBytes, "v4_context_utf8_bytes", currentContextBytes));
            if (directory.equals("cross-event-luna-round")) { assertThat(accepted).isEqualTo(9); assertThat(covered).isEqualTo(16); }
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/atlas-attribution-migration-replay.json").toFile(), Map.of(
            "kind", "counterfactual_adapter_projection_not_live_model_evaluation", "real_model_requests", 0, "rounds", rounds,
            "scope", "Only the attributions subtree of retained v2/v3 responses is projected into a server-bound v4 reply. "
                + "No claim, citation, order or prose is repaired. Required facts come from the deterministic baseline. "
                + "The model has NOT seen the new v4 prompt. These conditional adapter outcomes are not measured v4 fallback/quality/cost/latency."));
    }
}
