package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Single sequential evaluation only: reserve a conservative ceiling before dispatch, settle after usage. */
final class EvaluationBudget {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private final BigDecimal cap;
    private final JsonNode prices;
    private BigDecimal committed = BigDecimal.ZERO;
    private boolean stopped;
    EvaluationBudget(BigDecimal cap, JsonNode prices) {
        if (cap.signum() <= 0 || cap.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Budget must be (0,1] USD");
        this.cap = cap; this.prices = prices;
    }
    BigDecimal reserve(String model) {
        JsonNode rates = prices.path("models").get(model);
        if (stopped || rates == null) throw new ModelPlanner.Failure("evaluation_budget_stopped", false);
        // Transport rejects bodies >32768 bytes; add 1024 tokens for server framing. No tools, media or long context.
        BigDecimal worstInput = new BigDecimal(rates.path("input").asText());
        if (rates.has("cache_write")) worstInput = worstInput.max(new BigDecimal(rates.path("cache_write").asText()));
        BigDecimal ceiling = worstInput.multiply(BigDecimal.valueOf(33792))
            .add(new BigDecimal(rates.path("output").asText()).multiply(BigDecimal.valueOf(OpenAiPlanner.MAX_OUTPUT_TOKENS))).divide(MILLION);
        if (committed.add(ceiling).compareTo(cap) > 0) throw new ModelPlanner.Failure("evaluation_budget_exhausted", false);
        committed = committed.add(ceiling); return ceiling;
    }
    void settle(BigDecimal reservation, boolean initiated, ModelPlanner.Usage usage) {
        if (!initiated) { committed = committed.subtract(reservation); return; }
        if (usage == null || usage.inputTokens() == null || usage.inputTokens() > 33792 || usage.outputTokens() == null
                || usage.outputTokens() > OpenAiPlanner.MAX_OUTPUT_TOKENS || !"default".equals(usage.serviceTier())
                || usage.requestedModel() == null || !usage.requestedModel().equals(usage.resolvedModel())) { stopped = true; return; }
        var costs = ExplanationModelReport.cost(List.of(usage), prices);
        if (!"complete_usage_estimate".equals(costs.get("status"))) { stopped = true; return; }
        var actual = (BigDecimal) costs.get("estimated_total_usd");
        committed = committed.subtract(reservation).add(actual);
        if (actual.compareTo(reservation) > 0 || committed.compareTo(cap) > 0) stopped = true;
    }
    Map<String, Object> state() {
        return Map.of("cap_usd", cap, "committed_or_reserved_usd", committed, "stopped", stopped,
            "scope", "Pre-request conservative text-token reservation; unknown usage retains its reservation and stops further dispatch. "
                + "Not an account billing limit. Excludes taxes/account-specific prices. No retries/tools/parallel dispatch.");
    }
}
