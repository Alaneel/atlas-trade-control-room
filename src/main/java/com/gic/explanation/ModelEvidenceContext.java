package com.gic.explanation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless short citation aliases and numeric recorded-time offsets, without supplying inferred relations. */
final class ModelEvidenceContext {
    static Map<String, String> aliases(ExplanationEngine.Analysis analysis) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var evidence : analysis.evidence()) {
            String alias = switch (evidence.kind()) {
                case "trade" -> "T";
                case "read_result" -> "Q";
                case "receipt" -> "R";
                case "event" -> "E" + analysis.snapshot().events().stream().filter(e -> e.id().equals(evidence.id())).findFirst().orElseThrow().sequence();
                case "rule" -> "RULE_" + evidence.id().substring(5, evidence.id().indexOf('@'));
                default -> throw new IllegalArgumentException("Unsupported evidence kind");
            };
            if (result.put(alias, evidence.id()) != null) throw new IllegalArgumentException("Duplicate evidence alias");
        }
        return result;
    }
    static CrossEventVerifier.Proposal resolve(CrossEventVerifier.Proposal p, ExplanationEngine.Analysis a) {
        Map<String, String> aliases = aliases(a);
        return new CrossEventVerifier.Proposal(p.relation(), source(p.fromId(), aliases), source(p.toId(), aliases),
            p.evidenceIds().stream().map(ref -> source(ref, aliases)).toList(), p.interpretation(), p.uncertainty());
    }
    private static String source(String ref, Map<String, String> aliases) {
        if (aliases.containsKey(ref)) return aliases.get(ref);
        if (aliases.containsValue(ref)) return ref; // Exact full references in retained/scripted v1/v2 plans.
        throw new ModelPlanner.Failure("invalid_attribution", false);
    }
    static List<String> operandAliases(ExplanationEngine.Analysis a) {
        return aliases(a).keySet().stream().filter(ref -> ref.equals("R") || ref.equals("Q") || ref.matches("E[0-9]+" )).toList();
    }
    static Map<String, Object> create(ExplanationEngine.Analysis a) {
        Map<String, String> reverse = new LinkedHashMap<>(); aliases(a).forEach((alias, source) -> reverse.put(source, alias));
        var s = a.snapshot(); var origin = s.events().get(0).at();
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("latestRecordedState", s.status()); timeline.put("latestSequence", s.version());
        timeline.put("events", s.events().stream().map(e -> Map.of("id", "E" + e.sequence(), "sequence", e.sequence(),
            "status", e.status(), "decisionCode", e.decisionCode(), "recordedAt", e.at().toString(),
            "recordedOffsetMillis", Duration.between(origin, e.at()).toMillis())).toList());
        timeline.put("receipt", s.receipt() == null ? null : Map.of("id", "R", "status", s.receipt().status(),
            "recordedAt", s.receipt().at().toString(), "recordedOffsetMillis", Duration.between(origin, s.receipt().at()).toMillis()));
        timeline.put("receiptQuery", Map.of("id", "Q", "receiptPresent", s.receipt() != null));
        timeline.put("timeSemantics", "Offsets are recorded timestamps relative to E0, not inferred physical execution/arrival times. No relationship or diagnosis has been preselected.");
        Map<String, String> definitions = new LinkedHashMap<>();
        CrossEventVerifier.definitions().forEach((name, text) -> definitions.put(name,
            text.replace("receipt_lookup:<tradeId>", "Q").replace("receipt:<tradeId>", "R")));
        return Map.of("snapshotHash", a.snapshotHash(), "headlineId", a.headlineId(), "timeline", timeline,
            "findings", a.findings().stream().map(f -> Map.of("id", f.id(), "ruleId", f.ruleId(), "text", f.text(),
                "required", f.required(), "evidenceIds", f.evidenceIds().stream().map(reverse::get).toList())).toList(),
            "checks", a.checks().stream().map(c -> Map.of("id", c.id(), "text", c.text(), "required", c.required(),
                "evidenceIds", c.evidenceIds().stream().map(reverse::get).toList())).toList(),
            "evidence", a.evidence().stream().map(e -> Map.of("id", reverse.get(e.id()), "kind", e.kind(), "observation", e.observation())).toList(),
            "relationDefinitions", definitions);
    }
    /** v4 has no model-owned factual selection, ordering or snapshot-copy task. */
    static Map<String, Object> attributions(ExplanationEngine.Analysis a) {
        var legacy = create(a);
        return Map.of("timeline", legacy.get("timeline"), "evidence", legacy.get("evidence"),
            "authoritativeFacts", a.findings().stream().map(Explanation.Finding::text).toList(),
            "relationDefinitions", legacy.get("relationDefinitions"),
            "offsetPrecision", "Numeric offsets are whole milliseconds. If offsets tie, compare the exact recordedAt instants; ties do not imply equal timestamps.");
    }
}
