package com.gic.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Evaluation-only accounting. Accepted application text and raw provider contract success stay separate. */
final class ExplanationModelReport {
    record CaseResult(String caseId, String expectedHeadline, Explanation rules, Explanation explanation,
                      String returnedPlan, ModelPlanner.Usage providerUsage, Long providerElapsedMillis) { }

    static Map<String, Object> summarize(List<CaseResult> rows, List<Explanation> controls, JsonNode prices) {
        Map<String, Object> result = new LinkedHashMap<>();
        long calls = rows.stream().mapToInt(r -> r.explanation().modelCalls()).sum();
        List<CaseResult> accepted = rows.stream().filter(r -> "model_ordered".equals(r.explanation().mode())).toList();
        long fallbacks = rows.stream().filter(r -> "rules_fallback".equals(r.explanation().mode())).count();
        result.put("cases", rows.size()); result.put("initiated_requests", calls);
        result.put("validated_plans", accepted.size()); result.put("fallbacks", fallbacks);
        result.put("fallback_rate", ratio(fallbacks, rows.size()));
        result.put("provider_outcomes", rows.stream().collect(Collectors.groupingBy(r -> r.explanation().providerOutcome(), LinkedHashMap::new, Collectors.counting())));
        result.put("latency_ms", latency(rows.stream().map(r -> r.explanation().elapsedMillis()).toList()));
        result.put("provider_latency_ms", latency(rows.stream().map(CaseResult::providerElapsedMillis).filter(java.util.Objects::nonNull).toList()));
        result.put("latency_scope", "Sequential end-to-end in-process explanation calls, including network and rendering; no load/concurrency inference. Nearest-rank percentiles; no unreported warmup requests.");
        result.put("cost", cost(rows, prices));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("raw_plan_acceptance", fraction(accepted.size(), rows.size()));
        quality.put("final_application_output", quality(rows));
        quality.put("accepted_model_output", quality(accepted));
        quality.put("unchanged_presentation_vs_rules", rows.stream().filter(r -> r.explanation().findings().equals(r.rules().findings())
            && r.explanation().nextChecks().equals(r.rules().nextChecks())).count());
        quality.put("abstention_controls", fraction(controls.stream().filter(r -> r.abstained() && r.modelCalls() == 0).count(), controls.size()));
        quality.put("abstention_control_requests", controls.stream().mapToInt(Explanation::modelCalls).sum());
        quality.put("human_usefulness", null); quality.put("independent_semantic_accuracy", null);
        quality.put("scope", "Frozen development-visible synthetic cases. Contract/text/citation checks use reviewed rule output, not an independent semantic grader. Final application scores include rule fallback; accepted-model scores exclude it. No human rating or unrestricted model reasoning claim.");
        result.put("quality", quality);
        return result;
    }

    private static Map<String, Object> quality(List<CaseResult> rows) {
        Map<String, Object> score = new LinkedHashMap<>();
        score.put("expected_headline", count(rows, row -> row.explanation().findings().stream().anyMatch(f -> f.id().equals(row.expectedHeadline())
            && f.text().equals(row.explanation().summary()))));
        score.put("reason_placed_first", count(rows, row -> !row.explanation().findings().isEmpty()
            && row.explanation().findings().get(0).id().equals(row.expectedHeadline())));
        score.put("required_findings_and_checks", count(rows, row -> row.rules().findings().stream().filter(Explanation.Finding::required)
            .allMatch(row.explanation().findings()::contains) && row.rules().nextChecks().stream().filter(Explanation.Check::required)
            .allMatch(row.explanation().nextChecks()::contains)));
        score.put("exact_reviewed_text_only", count(rows, row -> row.rules().findings().containsAll(row.explanation().findings())
            && row.rules().nextChecks().containsAll(row.explanation().nextChecks()) && row.explanation().limitations().equals(row.rules().limitations())));
        score.put("citation_integrity", count(rows, row -> {
            Set<String> ids = row.explanation().evidence().stream().map(Explanation.Evidence::id).collect(Collectors.toSet());
            return row.explanation().findings().stream().allMatch(f -> !f.evidenceIds().isEmpty() && ids.containsAll(f.evidenceIds()))
                && row.explanation().nextChecks().stream().allMatch(c -> !c.evidenceIds().isEmpty() && ids.containsAll(c.evidenceIds()));
        }));
        List<CaseResult> uncertain = rows.stream().filter(r -> "execution.unknown".equals(r.expectedHeadline())).toList();
        score.put("unknown_remains_uncertain", count(uncertain, row -> "UNKNOWN".equals(row.explanation().tradeStatus())
            && row.explanation().findings().stream().anyMatch(f -> "execution.unknown".equals(f.id()))
            && row.explanation().findings().stream().noneMatch(f -> "execution.accepted".equals(f.id()))));
        return score;
    }

    private static Map<String, Object> cost(List<CaseResult> rows, JsonNode prices) {
        BigDecimal minimum = BigDecimal.ZERO, maximum = BigDecimal.ZERO;
        int missing = 0, unpriced = 0, missingCache = 0;
        long inputTotal = 0, cachedTotal = 0, outputTotal = 0, reasoningTotal = 0, requests = 0;
        for (CaseResult row : rows) {
            if (row.explanation().modelCalls() == 0) continue;
            requests++;
            ModelPlanner.Usage u = row.providerUsage();
            if (u == null || u.inputTokens() == null || u.outputTokens() == null) { missing++; continue; }
            inputTotal += u.inputTokens(); outputTotal += u.outputTokens();
            if (u.cachedInputTokens() != null) cachedTotal += u.cachedInputTokens();
            if (u.reasoningTokens() != null) reasoningTotal += u.reasoningTokens();
            JsonNode rates = u.resolvedModel() == null ? null : prices.path("models").get(u.resolvedModel());
            if (rates == null || (u.serviceTier() != null && !"default".equals(u.serviceTier()))) { unpriced++; continue; }
            BigDecimal input = new BigDecimal(rates.path("input").asText());
            BigDecimal cached = new BigDecimal(rates.path("cached_input").asText());
            BigDecimal output = new BigDecimal(rates.path("output").asText()).multiply(BigDecimal.valueOf(u.outputTokens()));
            if (u.cachedInputTokens() == null) {
                missingCache++;
                minimum = minimum.add(cached.multiply(BigDecimal.valueOf(u.inputTokens())).add(output));
                maximum = maximum.add(input.multiply(BigDecimal.valueOf(u.inputTokens())).add(output));
            } else {
                BigDecimal value = input.multiply(BigDecimal.valueOf(u.inputTokens() - u.cachedInputTokens()))
                    .add(cached.multiply(BigDecimal.valueOf(u.cachedInputTokens()))).add(output);
                minimum = minimum.add(value); maximum = maximum.add(value);
            }
        }
        Map<String, Object> cost = new LinkedHashMap<>();
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        cost.put("currency", "USD"); cost.put("initiated_requests", requests);
        cost.put("requests_missing_usage", missing); cost.put("requests_unpriced", unpriced);
        cost.put("requests_missing_cache_breakdown", missingCache);
        cost.put("reported_input_tokens", inputTotal); cost.put("reported_cached_input_tokens", cachedTotal);
        cost.put("reported_output_tokens", outputTotal); cost.put("reported_reasoning_tokens", reasoningTotal);
        cost.put("known_usage_estimate_usd_min", minimum.divide(million));
        cost.put("known_usage_estimate_usd_max", maximum.divide(million));
        cost.put("estimated_total_usd", missing == 0 && unpriced == 0 && missingCache == 0 ? maximum.divide(million) : null);
        cost.put("status", missing > 0 || unpriced > 0 ? "incomplete" : missingCache > 0 ? "bounded_cache_uncertainty" : "complete_usage_estimate");
        cost.put("pricing_version", prices.path("version").asText()); cost.put("pricing_source", prices.path("source").asText());
        cost.put("pricing_verified_on", prices.path("verified_on").asText()); cost.put("basis", prices.path("basis").asText());
        return cost;
    }
    private static Map<String, Object> count(List<CaseResult> rows, Predicate<CaseResult> pass) {
        return fraction(rows.stream().filter(pass).count(), rows.size());
    }
    private static Map<String, Object> fraction(long passed, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", passed); result.put("total", total); result.put("rate", ratio(passed, total)); return result;
    }
    private static Double ratio(long value, long total) { return total == 0 ? null : (double) value / total; }
    private static Map<String, Object> latency(List<Long> values) {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<Long> sorted = new ArrayList<>(values); sorted.sort(Long::compare);
        stats.put("count", sorted.size()); stats.put("p50", percentile(sorted, .5)); stats.put("p95", percentile(sorted, .95));
        stats.put("max", sorted.isEmpty() ? null : sorted.get(sorted.size() - 1));
        stats.put("mean", sorted.isEmpty() ? null : sorted.stream().mapToLong(Long::longValue).average().orElseThrow()); return stats;
    }
    private static Long percentile(List<Long> sorted, double p) { return sorted.isEmpty() ? null : sorted.get((int) Math.ceil(sorted.size() * p) - 1); }
}
