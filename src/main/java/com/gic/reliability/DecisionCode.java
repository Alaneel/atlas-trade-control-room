package com.gic.reliability;

/** Deterministic decision provenance; explanation code never chooses these values. */
public enum DecisionCode {
    CAPACITY_RESERVED, INSTRUMENT_NOT_APPROVED, INSUFFICIENT_CAPACITY,
    NO_CURRENCY_LIMIT, SUBMISSION_PENDING, EXECUTION_OUTCOME_UNKNOWN,
    VENUE_ACCEPTED, VENUE_REJECTED, LEGACY_UNSPECIFIED;

    public static final String RULE_VERSION = "atlas-trade-rules-v1";
}
