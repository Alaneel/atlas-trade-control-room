package com.gic.explanation;

/** Receives verified claims only. It has neither tools nor a trading-system reference. */
@FunctionalInterface
public interface ModelPlanner {
    Reply propose(ExplanationEngine.Analysis analysis);
    enum ReplyFormat { LEGACY_PLAN, ATTRIBUTIONS_V4 }
    /** Format and binding are supplied by the trusted adapter, never inferred from model JSON. */
    record Reply(String json, String model, Long inputTokens, Long outputTokens, boolean modelRequest, Usage usage,
                 ReplyFormat format, String requestSnapshotHash) {
        public Reply(String json, String model, Long inputTokens, Long outputTokens, boolean modelRequest, Usage usage) {
            this(json, model, inputTokens, outputTokens, modelRequest, usage, ReplyFormat.LEGACY_PLAN, null);
        }
        public Reply(String json, String model, Long inputTokens, Long outputTokens, boolean modelRequest) {
            this(json, model, inputTokens, outputTokens, modelRequest, null);
        }
        public Reply boundAttributions(String snapshotHash) {
            return new Reply(json, model, inputTokens, outputTokens, modelRequest, usage, ReplyFormat.ATTRIBUTIONS_V4, snapshotHash);
        }
    }
    /** Allowlisted response metadata only; never retains credentials, reasoning text or raw provider errors. */
    record Usage(String requestedModel, String resolvedModel, String responseId, String requestId, String serviceTier,
                 Long inputTokens, Long cachedInputTokens, Long outputTokens, Long reasoningTokens, Long cacheWriteTokens) {
        public Usage(String requestedModel, String resolvedModel, String responseId, String requestId, String serviceTier,
                Long inputTokens, Long cachedInputTokens, Long outputTokens, Long reasoningTokens) {
            this(requestedModel, resolvedModel, responseId, requestId, serviceTier, inputTokens, cachedInputTokens,
                outputTokens, reasoningTokens, null);
        }
    }
    final class Failure extends RuntimeException {
        private final String outcome;
        private final boolean modelRequest;
        private final Usage usage;
        public Failure(String outcome, boolean modelRequest) {
            this(outcome, modelRequest, null);
        }
        public Failure(String outcome, boolean modelRequest, Usage usage) {
            super("Explanation provider " + outcome); this.outcome = outcome; this.modelRequest = modelRequest; this.usage = usage;
        }
        public String outcome() { return outcome; }
        public boolean modelRequest() { return modelRequest; }
        public Usage usage() { return usage; }
    }
}
