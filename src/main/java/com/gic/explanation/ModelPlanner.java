package com.gic.explanation;

/** Receives verified claims only. It has neither tools nor a trading-system reference. */
@FunctionalInterface
public interface ModelPlanner {
    Reply propose(ExplanationEngine.Analysis analysis);
    record Reply(String json, String model, Long inputTokens, Long outputTokens, boolean modelRequest) { }
    final class Failure extends RuntimeException {
        private final String outcome;
        private final boolean modelRequest;
        public Failure(String outcome, boolean modelRequest) {
            super("Explanation provider " + outcome); this.outcome = outcome; this.modelRequest = modelRequest;
        }
        public String outcome() { return outcome; }
        public boolean modelRequest() { return modelRequest; }
    }
}
