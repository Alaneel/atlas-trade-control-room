package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gic.reliability.TradeOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Paid generation plus blinded, reversed-position model review. Synthetic, not a human/heldout study. */
@EnabledIfSystemProperty(named = "atlas.explanation.cross-event.live", matches = "true")
class CrossEventLiveEvaluationTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
    private final List<Map<String, Object>> traces = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private Path destination;
    private JsonNode prices;

    @Test void compareWithOriginalAndStrongerRulesWithoutLeakingReferencesToGenerator() throws Exception {
        String key = System.getenv("OPENAI_API_KEY"), model = System.getenv("OPENAI_MODEL");
        assertThat(key).as("Configure OPENAI_API_KEY locally").isNotBlank();
        assertThat(model).as("Set the explicitly selected OPENAI_MODEL").isNotBlank();
        int limit = Integer.parseInt(System.getProperty("atlas.explanation.limit", "10"));
        assertThat(limit).isBetween(1, 10);
        destination = Path.of(System.getProperty("atlas.explanation.report", "target/atlas-cross-event-evaluation.json"));
        prices = json.readTree(Path.of("evals/explanations/pricing.json").toFile());
        byte[] dataset = Files.readAllBytes(Path.of("evals/explanations/cross-event-cases.json"));
        var data = json.readTree(dataset);
        report.put("kind", "cross_event_paired_model_review"); report.put("recorded_at", Instant.now().toString());
        report.put("model", model); report.put("dataset_version", data.path("datasetVersion").asText());
        report.put("dataset_sha256", TradeOrder.hash(new String(dataset, StandardCharsets.UTF_8)));
        report.put("generator_prompt", OpenAiPlanner.PROMPT_VERSION); report.put("reviewer_prompt", CrossEventJudge.VERSION);
        report.put("request_cap", 3 * limit); report.put("requested_generation_limit", limit); report.put("complete", false);
        report.put("blinding_seed", 6102026); report.put("results", results); report.put("requests", traces);
        report.put("scope", "Development-visible synthetic cases. Reference goals are withheld from generation, visible to the model reviewer. "
            + "Same model snapshot generates and reviews: self-preference/correlated errors remain possible. Two reversed positions are not independent cases. "
            + "Model ratings are provisional; no human review or production/generalization claim.");
        var engine = new ExplanationEngine(new RuleCatalog());
        var provider = new OpenAiPlanner(key, model, Duration.ofSeconds(8));
        int eligible = 0;
        for (JsonNode row : data.path("cases")) {
            String id = row.path("id").asText();
            var snapshot = json.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            var analysis = engine.analyze(snapshot);
            if (!analysis.abstained() && eligible >= limit) continue;
            ModelPlanner observed = a -> call("generation", id, -1, () -> provider.propose(a));
            var baseline = new ExplanationService(ignored -> snapshot, engine, observed).explain(snapshot.tradeId(), false);
            long strongStart = System.nanoTime();
            var strong = CrossEventVerifier.strongBaseline(analysis);
            long strongMillis = (System.nanoTime() - strongStart) / 1_000_000;
            var actual = new ExplanationService(ignored -> snapshot, engine, observed).explain(snapshot.tradeId(), true);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id); item.put("expected_abstention", row.path("expectedAbstention").asBoolean());
            item.put("expected_relations", row.path("expectedRelations"));
            item.put("reference_goals", row.path("reviewGoals")); item.put("forbidden_claims", row.path("forbiddenClaims"));
            item.put("rules", baseline); item.put("strong_rule_attributions", strong); item.put("ai", actual);
            item.put("strong_rule_elapsed_ms", strongMillis);
            List<Map<String, Object>> reviews = new ArrayList<>(); item.put("reviews", reviews); results.add(item); checkpoint();
            if (analysis.abstained()) {
                assertThat(actual.modelCalls()).isZero(); assertThat(actual.abstained()).isTrue(); continue;
            }
            eligible++;
            Map<String, String> views = Map.of("rules", view(baseline, List.of()), "strong_rules", view(baseline, strong),
                "ai", view(actual, actual.attributions()));
            List<String> order = new ArrayList<>(List.of("rules", "strong_rules", "ai"));
            Collections.shuffle(order, new Random(6102026L + eligible));
            Set<String> goals = new java.util.HashSet<>(); row.path("reviewGoals").forEach(g -> goals.add(g.path("id").asText()));
            for (int orientation = 0; orientation < 2; orientation++) {
                if (orientation == 1) Collections.reverse(order);
                Map<String, String> mapping = new LinkedHashMap<>(), options = new LinkedHashMap<>();
                for (int index = 0; index < 3; index++) {
                    String label = String.valueOf((char) ('A' + index)); mapping.put(label, order.get(index));
                    options.put(label, views.get(order.get(index)).replace(snapshot.tradeId(), "trade"));
                }
                Map<String, Object> context = Map.of("referenceEvidence", json.readTree(json.writeValueAsString(snapshot).replace(snapshot.tradeId(), "trade")),
                    "reviewGoals", row.path("reviewGoals"), "forbiddenClaims", row.path("forbiddenClaims"), "options", options);
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("orientation", orientation); review.put("private_source_mapping", mapping); review.put("input", context);
                review.put("valid", false); reviews.add(review);
                int position = orientation;
                try {
                    var reply = call("review", id, position, () -> provider.requestStructured(CrossEventJudge.INSTRUCTION, context, CrossEventJudge.schema()));
                    review.put("parsed", CrossEventJudge.validate(reply.json(), goals)); review.put("valid", true);
                } catch (ModelPlanner.Failure ex) { review.put("failure", ex.outcome()); }
                catch (Exception ex) { review.put("failure", "invalid_reviewer_output"); }
                checkpoint();
            }
        }
        report.put("complete", true); checkpoint();
        assertThat(results).anyMatch(row -> !((Explanation) row.get("ai")).attributions().isEmpty());
    }

    private ModelPlanner.Reply call(String kind, String id, int orientation, Supplier<ModelPlanner.Reply> request) {
        if (traces.size() >= (int) report.get("request_cap")) throw new ModelPlanner.Failure("evaluation_budget", false);
        long start = System.nanoTime();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("kind", kind); trace.put("case_id", id); trace.put("orientation", orientation);
        try {
            var reply = request.get(); trace.put("initiated", reply.modelRequest()); trace.put("usage", reply.usage());
            trace.put("outcome", "response_completed"); trace.put("returned_json", reply.json()); return reply;
        } catch (ModelPlanner.Failure ex) {
            trace.put("initiated", ex.modelRequest()); trace.put("usage", ex.usage()); trace.put("outcome", ex.outcome()); throw ex;
        } finally {
            trace.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000); traces.add(trace); checkpoint();
        }
    }
    private void checkpoint() {
        try {
            List<ModelPlanner.Usage> usage = traces.stream().filter(t -> Boolean.TRUE.equals(t.get("initiated")))
                .map(t -> (ModelPlanner.Usage) t.get("usage")).toList();
            report.put("cost", ExplanationModelReport.cost(usage, prices));
            Map<String, Object> byKind = new LinkedHashMap<>();
            for (String kind : List.of("generation", "review")) byKind.put(kind, ExplanationModelReport.cost(traces.stream()
                .filter(t -> kind.equals(t.get("kind")) && Boolean.TRUE.equals(t.get("initiated")))
                .map(t -> (ModelPlanner.Usage) t.get("usage")).toList(), prices));
            report.put("cost_by_kind", byKind);
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Path temporary = destination.resolveSibling(destination.getFileName() + ".partial");
            json.writeValue(temporary.toFile(), report);
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) { throw new IllegalStateException("Could not retain evaluation checkpoint", ex); }
    }
    private String view(Explanation explanation, List<Explanation.Attribution> attributions) throws Exception {
        var visible = new LinkedHashMap<String, Object>();
        visible.put("summary", explanation.summary()); visible.put("findings", explanation.findings()); visible.put("nextChecks", explanation.nextChecks());
        visible.put("attributions", attributions.stream().map(a -> Map.of("observation", a.observedRelation(), "interpretation", a.interpretation(),
            "uncertainty", a.uncertainty(), "citations", a.evidenceIds())).toList());
        visible.put("limitations", explanation.limitations()); return json.writeValueAsString(visible);
    }
}
