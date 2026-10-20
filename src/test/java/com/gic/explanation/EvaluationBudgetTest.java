package com.gic.explanation;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvaluationBudgetTest {
    private final com.fasterxml.jackson.databind.JsonNode prices = loadPrices();
    private static com.fasterxml.jackson.databind.JsonNode loadPrices() {
        try { return CrossEventFixtures.JSON.readTree(Path.of("evals/explanations/pricing.json").toFile()); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private ModelPlanner.Usage usage(Long writes) {
        return new ModelPlanner.Usage("gpt-5.6-luna", "gpt-5.6-luna", "resp_fixture", "req_fixture", "default",
            1000L, 100L, 500L, 200L, writes);
    }
    @Test void cacheWritesReplaceOrdinaryInputAndReasoningIsAlreadyIncludedInOutput() {
        var cost = ExplanationModelReport.cost(List.of(usage(200L)), prices);
        assertThat((BigDecimal) cost.get("estimated_total_usd")).isEqualByComparingTo("0.000792");
        assertThat(cost.get("reported_cache_write_tokens")).isEqualTo(200L);
        var unknown = ExplanationModelReport.cost(List.of(usage(null)), prices);
        assertThat(unknown.get("estimated_total_usd")).isNull();
        assertThat((BigDecimal) unknown.get("known_usage_estimate_usd_min")).isEqualByComparingTo("0.000782");
        assertThat((BigDecimal) unknown.get("known_usage_estimate_usd_max")).isEqualByComparingTo("0.000827");
    }
    @Test void cannotDispatchIfReservationWouldExceedCapEvenWhenRealCostWouldBeSmall() {
        var budget = new EvaluationBudget(new BigDecimal("0.01"), prices);
        assertThatThrownBy(() -> budget.reserve("gpt-5.6-luna")).isInstanceOfSatisfying(ModelPlanner.Failure.class,
            ex -> assertThat(ex.outcome()).isEqualTo("evaluation_budget_exhausted"));
        assertThat(budget.state().get("committed_or_reserved_usd")).isEqualTo(BigDecimal.ZERO);
    }
    @Test void settleKnownUsageButRetainUnknownReservationAndStopFurtherDispatch() {
        var budget = new EvaluationBudget(new BigDecimal("0.10"), prices);
        var reservation = budget.reserve("gpt-5.6-luna");
        budget.settle(reservation, true, usage(200L));
        assertThat((BigDecimal) budget.state().get("committed_or_reserved_usd")).isEqualByComparingTo("0.000792");
        reservation = budget.reserve("gpt-5.6-luna"); budget.settle(reservation, true, null);
        assertThat(budget.state().get("stopped")).isEqualTo(true);
        assertThatThrownBy(() -> budget.reserve("gpt-5.6-luna")).isInstanceOf(ModelPlanner.Failure.class);
        assertThat((BigDecimal) budget.state().get("committed_or_reserved_usd")).isGreaterThan(new BigDecimal("0.01"));
    }
    @Test void localRejectionRefundsReservationAndUnknownModelCannotDispatch() {
        var budget = new EvaluationBudget(new BigDecimal("0.10"), prices);
        budget.settle(budget.reserve("gpt-5.6-luna"), false, null);
        assertThat((BigDecimal) budget.state().get("committed_or_reserved_usd")).isEqualByComparingTo("0");
        assertThatThrownBy(() -> budget.reserve("unpriced-model")).isInstanceOf(ModelPlanner.Failure.class);
    }
}
