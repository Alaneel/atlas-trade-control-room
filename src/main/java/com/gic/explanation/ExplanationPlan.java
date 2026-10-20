package com.gic.explanation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All provider-controlled values are exact IDs, never executable instructions or unchecked prose. */
public record ExplanationPlan(String snapshotHash, String headlineId, List<String> findingIds, List<String> checkIds) {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public ExplanationPlan { findingIds = List.copyOf(findingIds); checkIds = List.copyOf(checkIds); }
    public static ExplanationPlan baseline(ExplanationEngine.Analysis analysis) {
        return new ExplanationPlan(analysis.snapshotHash(), analysis.headlineId(), analysis.findings().stream()
            .map(Explanation.Finding::id).toList(), analysis.checks().stream().map(Explanation.Check::id).toList());
    }
    public static ExplanationPlan parseAndValidate(String raw, ExplanationEngine.Analysis analysis) {
        try {
            if (raw == null || raw.length() > 8192) throw new IllegalArgumentException();
            JsonNode node = JSON.readTree(raw);
            Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
            if (!node.isObject() || !fields.equals(Set.of("snapshotHash", "headlineId", "findingIds", "checkIds"))
                    || !node.path("snapshotHash").isTextual() || !node.path("headlineId").isTextual()) throw new IllegalArgumentException();
            ExplanationPlan result = new ExplanationPlan(node.get("snapshotHash").asText(), node.get("headlineId").asText(),
                strings(node.path("findingIds")), strings(node.path("checkIds")));
            if (!analysis.snapshotHash().equals(result.snapshotHash()) || !analysis.headlineId().equals(result.headlineId())) throw new IllegalArgumentException();
            Set<String> allowed = new HashSet<>(analysis.findings().stream().map(Explanation.Finding::id).toList());
            Set<String> required = new HashSet<>(analysis.findings().stream().filter(Explanation.Finding::required).map(Explanation.Finding::id).toList());
            validateIds(result.findingIds(), allowed, required);
            validateIds(result.checkIds(), new HashSet<>(analysis.checks().stream().map(Explanation.Check::id).toList()),
                new HashSet<>(analysis.checks().stream().filter(Explanation.Check::required).map(Explanation.Check::id).toList()));
            return result;
        } catch (Exception ex) { throw new ModelPlanner.Failure("invalid_output", false); }
    }
    private static List<String> strings(JsonNode list) {
        if (!list.isArray() || list.size() > 16) throw new IllegalArgumentException();
        List<String> result = new ArrayList<>();
        for (JsonNode item : list) {
            if (!item.isTextual() || item.asText().length() > 100) throw new IllegalArgumentException();
            result.add(item.asText());
        }
        return result;
    }
    private static void validateIds(List<String> values, Set<String> allowed, Set<String> required) {
        Set<String> actual = new HashSet<>(values);
        if (actual.size() != values.size() || !allowed.containsAll(actual) || !actual.containsAll(required)) throw new IllegalArgumentException();
    }
}
