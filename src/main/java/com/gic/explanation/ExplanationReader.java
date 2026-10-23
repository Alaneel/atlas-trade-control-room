package com.gic.explanation;

/** Deliberately no reserve, reconcile, dispatch, approval or persistence capability. */
@FunctionalInterface
public interface ExplanationReader {
    ExplanationSnapshot load(String tradeId);
}
