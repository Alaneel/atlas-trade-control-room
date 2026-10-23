package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Contract evaluation. Scripted plans are explicitly not real model-quality measurements. */
class ExplanationEvaluationTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());

    @Test
    void frozenCasesCompareRulesWithValidatedAndAdversarialPlannerContracts() throws Exception {
        byte[] dataset = Files.readAllBytes(Path.of("evals/explanations/cases.json"));
        JsonNode root = json.readTree(dataset);
        List<Map<String, Object>> results = new ArrayList<>();
        int expectedAbstentions = 0;
        int eligible = 0;
        int attacksRejected = 0;
        for (JsonNode row : root.path("cases")) {
            ExplanationSnapshot snapshot = json.treeToValue(row.path("snapshot"), ExplanationSnapshot.class);
            var analysis = engine.analyze(snapshot);
            assertThat(analysis.abstained()).as(row.path("id").asText()).isEqualTo(row.path("expectedAbstention").asBoolean());
            assertThat(analysis.headlineId()).as(row.path("id").asText()).isEqualTo(row.path("expectedHeadline").asText());
            if (analysis.abstained()) expectedAbstentions++; else eligible++;
            AtomicInteger plannerCalls = new AtomicInteger();
            ModelPlanner valid = a -> {
                plannerCalls.incrementAndGet();
                try { return new ModelPlanner.Reply(json.writeValueAsString(ExplanationPlan.baseline(a)), "scripted-fixture", null, null, false); }
                catch (Exception ex) { throw new AssertionError(ex); }
            };
            ExplanationService service = new ExplanationService(id -> snapshot, engine, valid);
            Explanation rules = service.explain(snapshot.tradeId(), false);
            Explanation planned = service.explain(snapshot.tradeId(), true);
            assertThat(planned.modelCalls()).isZero();
            assertThat(planned.summary()).isEqualTo(rules.summary());
            assertThat(plannerCalls.get()).isEqualTo(analysis.abstained() ? 0 : 1);
            assertThat(planned.findings()).allSatisfy(finding -> {
                assertThat(finding.evidenceIds()).isNotEmpty();
                assertThat(planned.evidence().stream().map(Explanation.Evidence::id).toList()).containsAll(finding.evidenceIds());
            });
            if (!analysis.abstained()) {
                for (String attack : attacks(analysis)) {
                    assertThatThrownBy(() -> ExplanationPlan.parseAndValidate(attack, analysis)).isInstanceOf(ModelPlanner.Failure.class);
                    Explanation rejected = new ExplanationService(id -> snapshot, engine,
                        a -> new ModelPlanner.Reply(attack, "scripted-attack", null, null, false)).explain(snapshot.tradeId(), true);
                    assertThat(rejected.mode()).isEqualTo("rules_fallback");
                    assertThat(rejected.providerOutcome()).isEqualTo("invalid_output");
                    assertThat(rejected.summary()).isEqualTo(rules.summary());
                    attacksRejected++;
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.path("id").asText()); result.put("expected_headline", row.path("expectedHeadline").asText());
            result.put("expected_abstention", row.path("expectedAbstention").asBoolean());
            result.put("rules", rules); result.put("validated_scripted_plan", planned); results.add(result);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recorded_at", Instant.now().toString()); report.put("kind", "offline_contract_evaluation");
        report.put("dataset_version", root.path("datasetVersion").asText());
        report.put("dataset_sha256", com.gic.reliability.TradeOrder.hash(new String(dataset, java.nio.charset.StandardCharsets.UTF_8)));
        report.put("rule_version", new RuleCatalog().version()); report.put("prompt_version", OpenAiPlanner.PROMPT_VERSION);
        report.put("cases", results.size()); report.put("expected_abstentions", expectedAbstentions);
        report.put("eligible_cases", eligible); report.put("scripted_adversarial_plans_rejected", attacksRejected);
        report.put("real_model_calls", 0); report.put("model_quality", null);
        report.put("real_model_evaluation", "not_requested_offline_runner");
        report.put("scope", "Curated synthetic cases exercise rule agreement, abstention, citations and plan enforcement; they do not establish LLM semantic accuracy or general prompt-injection immunity.");
        report.put("results", results);
        Files.createDirectories(Path.of("target"));
        json.enable(SerializationFeature.INDENT_OUTPUT).writeValue(Path.of("target/atlas-explanation-evaluation.json").toFile(), report);
    }

    private List<String> attacks(ExplanationEngine.Analysis a) throws Exception {
        var baseline = json.valueToTree(ExplanationPlan.baseline(a));
        List<String> attacks = new ArrayList<>();
        var wrongHash = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) wrongHash).put("snapshotHash", "old"); attacks.add(wrongHash.toString());
        var invented = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) invented.path("findingIds")).add("increase.capacity"); attacks.add(invented.toString());
        var omitted = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) omitted).putArray("findingIds"); attacks.add(omitted.toString());
        var mutation = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) mutation).put("executeTrade", true); attacks.add(mutation.toString());
        var check = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) check.path("checkIds")).add("retry.execution"); attacks.add(check.toString());
        var duplicate = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("findingIds")).add(a.headlineId()); attacks.add(duplicate.toString());
        var headline = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) headline).put("headlineId", "execution.accepted");
        if (!a.headlineId().equals("execution.accepted")) attacks.add(headline.toString());
        if (a.checks().stream().anyMatch(Explanation.Check::required)) {
            var missingCheck = baseline.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) missingCheck).putArray("checkIds");
            attacks.add(missingCheck.toString());
        }
        attacks.add("```json\n" + baseline + "\n```"); attacks.add(baseline + " {}");
        attacks.add("{\"snapshotHash\":\"first\",\"snapshotHash\":\"second\",\"headlineId\":\"x\",\"findingIds\":[],\"checkIds\":[]}");
        return attacks;
    }
}
