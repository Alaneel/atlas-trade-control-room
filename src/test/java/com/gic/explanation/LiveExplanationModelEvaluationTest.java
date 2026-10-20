package com.gic.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in, bounded real provider evaluation on synthetic fixtures only. */
@EnabledIfSystemProperty(named = "atlas.explanation.live", matches = "true")
class LiveExplanationModelEvaluationTest {
    @Test void evaluateRealProviderSeparatelyFromOfflineContracts() throws Exception {
        String key = System.getenv("OPENAI_API_KEY"), model = System.getenv("OPENAI_MODEL");
        assertThat(key).as("Set OPENAI_API_KEY locally; it is never written to results").isNotBlank();
        assertThat(model).as("Choose an available Structured Outputs model using OPENAI_MODEL").isNotBlank();
        int limit = Integer.parseInt(System.getProperty("atlas.explanation.limit", "6"));
        assertThat(limit).isBetween(1, 12);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        var engine = new ExplanationEngine(new RuleCatalog());
        var provider = new OpenAiPlanner(key, model, Duration.ofSeconds(8));
        List<ExplanationModelReport.CaseResult> results = new ArrayList<>();
        byte[] dataset = Files.readAllBytes(Path.of("evals/explanations/cases.json"));
        var datasetRoot = json.readTree(dataset);
        var rows = datasetRoot.path("cases");
        List<String> order = new ArrayList<>(List.of("capacity_rejection", "currency_scope_missing", "eligibility_rejection",
            "unknown_no_receipt", "unknown_success_receipt", "venue_rejection"));
        for (var row : rows) if (!row.path("expectedAbstention").asBoolean() && !order.contains(row.path("id").asText())) order.add(row.path("id").asText());
        List<String> caseIds = new ArrayList<>();
        for (String caseId : order) {
            com.fasterxml.jackson.databind.JsonNode row = null;
            for (var candidate : rows) if (caseId.equals(candidate.path("id").asText())) row = candidate;
            if (row == null) throw new AssertionError("Missing frozen case " + caseId);
            if (results.size() >= limit) break;
            if (row.path("expectedAbstention").asBoolean()) continue;
            var snapshot = json.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            AtomicReference<ModelPlanner.Reply> reply = new AtomicReference<>();
            AtomicReference<ModelPlanner.Usage> usage = new AtomicReference<>();
            AtomicReference<Long> providerMillis = new AtomicReference<>();
            ModelPlanner observed = analysis -> {
                long start = System.nanoTime();
                try {
                    var returned = provider.propose(analysis); reply.set(returned); usage.set(returned.usage()); return returned;
                } catch (ModelPlanner.Failure ex) { usage.set(ex.usage()); throw ex; }
                finally { providerMillis.set((System.nanoTime() - start) / 1_000_000); }
            };
            Explanation baseline = new ExplanationService(id -> snapshot, engine, observed).explain(snapshot.tradeId(), false);
            Explanation actual = new ExplanationService(id -> snapshot, engine, observed).explain(snapshot.tradeId(), true);
            results.add(new ExplanationModelReport.CaseResult(caseId, row.path("expectedHeadline").asText(), baseline, actual,
                reply.get() == null ? null : reply.get().json(), usage.get(), providerMillis.get()));
            caseIds.add(caseId);
        }
        List<Explanation> controls = new ArrayList<>();
        for (var row : rows) if (row.path("expectedAbstention").asBoolean()) {
            var snapshot = json.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            controls.add(new ExplanationService(id -> snapshot, engine, analysis -> {
                throw new AssertionError("Invalid evidence reached provider; no extra paid request was made");
            }).explain(snapshot.tradeId(), true));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "real_provider_plan_contract_evaluation"); report.put("recorded_at", Instant.now().toString());
        report.put("case_ids", caseIds);
        report.put("model", model); report.put("cases", results.size()); report.put("requested_limit", limit);
        report.put("dataset_version", datasetRoot.path("datasetVersion").asText());
        report.put("dataset_sha256", com.gic.reliability.TradeOrder.hash(new String(dataset, java.nio.charset.StandardCharsets.UTF_8)));
        report.put("rule_version", new RuleCatalog().version()); report.put("prompt_version", OpenAiPlanner.PROMPT_VERSION);
        report.put("summary", ExplanationModelReport.summarize(results, controls,
            json.readTree(Path.of("evals/explanations/pricing.json").toFile())));
        report.put("semantic_faithfulness", null); report.put("human_usefulness", null);
        report.put("scope", "Tests provider plan validity. Rendered factual claims are generated by reviewed rules; this is not independent LLM semantic or usefulness validation.");
        report.put("results", results);
        report.put("abstention_controls", controls);
        Path destination = Path.of(System.getProperty("atlas.explanation.report", "target/atlas-explanation-model-evaluation.json"));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        json.enable(SerializationFeature.INDENT_OUTPUT).writeValue(destination.toFile(), report);
        assertThat(results).anyMatch(result -> java.util.Set.of("model_ordered", "model_attributed").contains(result.explanation().mode()));
    }
}
