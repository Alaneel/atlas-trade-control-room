package com.gic.explanation;

import java.time.Instant;
import java.util.List;

public record Explanation(String tradeId, long tradeVersion, String tradeStatus, String snapshotHash,
        String ruleVersion, String promptVersion, String mode, String providerOutcome, String model,
        int modelCalls, Long inputTokens, Long outputTokens, long elapsedMillis, boolean abstained,
        String summary, List<Finding> findings, List<Check> nextChecks, List<Evidence> evidence,
        List<String> limitations, Instant generatedAt) {
    public Explanation {
        findings = List.copyOf(findings); nextChecks = List.copyOf(nextChecks);
        evidence = List.copyOf(evidence); limitations = List.copyOf(limitations);
    }
    public record Finding(String id, String ruleId, String text, List<String> evidenceIds, boolean required) {
        public Finding { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Check(String id, String text, List<String> evidenceIds, boolean required) {
        public Check { evidenceIds = List.copyOf(evidenceIds); }
    }
    public record Evidence(String id, String kind, String source, String observation) { }
}
