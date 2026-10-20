package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExplanationModelReportTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ExplanationEngine engine = new ExplanationEngine(new RuleCatalog());
    private final ExplanationSnapshot snapshot = ExplanationFixtures.get("capacity_rejection");
    private final Explanation rules = new ExplanationService(id -> snapshot, engine, a -> { throw new AssertionError(); })
        .explain(snapshot.tradeId(), false);

    private ModelPlanner.Usage usage(String model, Long input, Long cache, Long output) {
        return new ModelPlanner.Usage("gpt-4.1-mini-2025-04-14", model, "resp_fixture", "req_fixture", "default", input, cache, output, 0L);
    }
    private ExplanationModelReport.CaseResult sample(ModelPlanner.Usage usage, boolean refusal) {
        var actual = new ExplanationService(id -> snapshot, engine, a -> {
            if (refusal) throw new ModelPlanner.Failure("refused", true, usage);
            return new ModelPlanner.Reply(json.valueToTree(ExplanationPlan.baseline(a)).toString(), usage.requestedModel(),
                usage.inputTokens(), usage.outputTokens(), true, usage);
        }).explain(snapshot.tradeId(), true);
        return new ExplanationModelReport.CaseResult("capacity_rejection", "decision.insufficient_capacity", rules, actual, null, usage, 10L);
    }
    private JsonNode report(List<ExplanationModelReport.CaseResult> rows) throws Exception {
        return json.valueToTree(ExplanationModelReport.summarize(rows, List.of(),
            json.readTree(Path.of("evals/explanations/pricing.json").toFile())));
    }
    @Test void cachedUsageAndBillableRefusalsAreIncludedWithoutDoubleCountingReasoning() throws Exception {
        var accepted = sample(usage("gpt-4.1-mini-2025-04-14", 1000L, 500L, 100L), false);
        var refused = sample(usage("gpt-4.1-mini-2025-04-14", 300L, 0L, 20L), true);
        assertThat(refused.explanation().inputTokens()).isEqualTo(300L);
        var report = report(List.of(accepted, refused));
        assertThat(report.at("/cost/estimated_total_usd").decimalValue()).isEqualByComparingTo("0.000562");
        assertThat(report.path("fallback_rate").asDouble()).isEqualTo(.5);
        assertThat(report.at("/quality/accepted_model_output/expected_headline/total").asInt()).isEqualTo(1);
        assertThat(report.at("/quality/final_application_output/expected_headline/total").asInt()).isEqualTo(2);
    }
    @Test void missingUsageIsUnknownCostAndZeroAcceptedPlansHaveNullQualityRate() throws Exception {
        var row = sample(null, true);
        var report = report(List.of(row));
        assertThat(report.at("/cost/estimated_total_usd").isNull()).isTrue();
        assertThat(report.at("/cost/requests_missing_usage").asInt()).isEqualTo(1);
        assertThat(report.at("/quality/accepted_model_output/expected_headline/rate").isNull()).isTrue();
        assertThat(report.at("/quality/human_usefulness").isNull()).isTrue();
    }
    @Test void unknownPricesRemainUnknownAndMissingCacheCountsProduceARange() throws Exception {
        var range = report(List.of(sample(usage("gpt-4.1-mini-2025-04-14", 1000L, null, 100L), false)));
        assertThat(range.at("/cost/known_usage_estimate_usd_min").decimalValue()).isEqualByComparingTo("0.00026");
        assertThat(range.at("/cost/known_usage_estimate_usd_max").decimalValue()).isEqualByComparingTo("0.00056");
        assertThat(range.at("/cost/estimated_total_usd").isNull()).isTrue();
        var unknown = report(List.of(sample(usage("unpriced-future-model", 1000L, 0L, 100L), false)));
        assertThat(unknown.at("/cost/requests_unpriced").asInt()).isEqualTo(1);
        assertThat(unknown.at("/cost/estimated_total_usd").isNull()).isTrue();
    }
}
