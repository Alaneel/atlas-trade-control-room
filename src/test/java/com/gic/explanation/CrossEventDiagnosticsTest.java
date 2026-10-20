package com.gic.explanation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Diagnose retained outputs with the actual production parser and proof checker; no provider calls. */
class CrossEventDiagnosticsTest {
    @Test void everyFallbackIsReproducedAndEveryProposalIsInspectedWithoutSalvagingIt() throws Exception {
        var json = CrossEventFixtures.JSON;
        var source = Path.of(System.getProperty("atlas.explanation.diagnose",
            "docs/examples/ai-explanations/cross-event-v3-second-round/results.json"));
        var recorded = json.readTree(source.toFile());
        var engine = new ExplanationEngine(new RuleCatalog());
        var cases = new ArrayList<Map<String, Object>>();
        int rejected = 0, independentlyValidDiscarded = 0, empty = 0;
        for (var row : recorded.path("results")) {
            if (row.path("expected_abstention").asBoolean()) continue;
            String id = row.path("id").asText();
            var analysis = engine.analyze(CrossEventFixtures.get(id));
            var trace = java.util.stream.StreamSupport.stream(recorded.path("requests").spliterator(), false)
                .filter(t -> id.equals(t.path("case_id").asText()) && "generation".equals(t.path("kind").asText())).findFirst().orElseThrow();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("case_id", id); item.put("transport_outcome", trace.path("outcome").asText());
            item.put("retained_application_outcome", row.at("/ai/providerOutcome").asText());
            cases.add(item);
            if (!trace.has("returned_json")) { item.put("diagnosis", "provider_response_unavailable"); continue; }
            String raw = trace.path("returned_json").asText();
            boolean accepted;
            try { ExplanationPlan.parseAndValidate(raw, analysis); accepted = true; }
            catch (ModelPlanner.Failure failure) { accepted = false; assertThat(failure.outcome()).isEqualTo("invalid_output"); }
            item.put("whole_plan_accepted", accepted);
            assertThat(accepted).isEqualTo("validated".equals(row.at("/ai/providerOutcome").asText()));
            if (!accepted) rejected++;
            var plan = json.readTree(raw);
            var baseOnly = plan.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) baseOnly).putArray("attributions");
            try { ExplanationPlan.parseAndValidate(baseOnly.toString(), analysis); item.put("base_contract_valid", true); }
            catch (ModelPlanner.Failure failure) { item.put("base_contract_valid", false); }
            var proposals = new ArrayList<Map<String, Object>>(); item.put("proposals", proposals);
            var seen = new HashSet<String>();
            int validInPlan = 0;
            for (var node : plan.path("attributions")) {
                Map<String, Object> detail = new LinkedHashMap<>(); proposals.add(detail);
                String relation = node.path("relation").asText();
                detail.put("relation", relation); detail.put("from", node.path("fromId").asText());
                detail.put("to", node.path("toId").asText()); detail.put("citations", node.path("evidenceIds"));
                detail.put("duplicate_relation", !seen.add(relation));
                try {
                    var p = ModelEvidenceContext.resolve(json.treeToValue(node, CrossEventVerifier.Proposal.class), analysis);
                    CrossEventVerifier.verify(p, analysis);
                    detail.put("proof_valid", true); detail.put("guard_result", "accepted_independently"); validInPlan++;
                } catch (ModelPlanner.Failure failure) {
                    detail.put("proof_valid", false); detail.put("guard_result", failure.outcome());
                } catch (IllegalArgumentException failure) {
                    detail.put("proof_valid", false); detail.put("guard_result", "invalid_shape_or_duplicate_operand");
                }
            }
            if (proposals.isEmpty()) empty++;
            int discarded = accepted ? 0 : validInPlan;
            item.put("independently_valid_proposals_discarded", discarded); independentlyValidDiscarded += discarded;
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("kind", "offline_production_guard_diagnosis"); report.put("source", source.toString());
        report.put("source_sha256", com.gic.reliability.TradeOrder.hash(Files.readString(source)));
        report.put("real_model_requests", 0); report.put("cases", cases); report.put("rejected_plans", rejected);
        report.put("empty_attribution_plans", empty); report.put("independently_valid_proposals_discarded", independentlyValidDiscarded);
        report.put("scope", "Each proposal is checked independently for diagnosis only. Production still rejects the whole plan on any invalid or duplicate claim. "
            + "Guard codes describe the first failing predicate within each proposal; other defects may also exist. Prose is not semantically validated.");
        Path output = Path.of(System.getProperty("atlas.explanation.diagnostics-report", "target/atlas-cross-event-diagnostics.json"));
        Files.createDirectories(output.toAbsolutePath().getParent()); json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        if (source.toString().contains("cross-event-v3-second-round")) {
            assertThat(rejected).isEqualTo(8); assertThat(empty).isEqualTo(1);
            assertThat(cases).allSatisfy(c -> { assertThat(c.get("base_contract_valid")).isEqualTo(true);
                assertThat(c.get("transport_outcome")).isEqualTo("response_completed"); });
        }
    }
}
