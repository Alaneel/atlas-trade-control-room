package com.gic.explanation;

import com.gic.reliability.DecisionCode;
import com.gic.reliability.TradeStatus;
import java.time.Instant;
import java.util.List;

/** Immutable allowlisted read model. Messages, names, keys and credentials never enter the model context. */
public record ExplanationSnapshot(String tradeId, long version, TradeStatus status, String amount,
        String currency, DecisionCode decisionCode, String ruleVersion, String availableAtDecision,
        List<Event> events, Receipt receipt, boolean complete) {
    public ExplanationSnapshot { events = events == null ? List.of() : List.copyOf(events); }
    public record Event(String id, String tradeId, long sequence, TradeStatus status, DecisionCode decisionCode,
                        String ruleVersion, String availableAtDecision, Instant at) { }
    public record Receipt(TradeStatus status, String amount, boolean identityMatches, Instant at) { }
}
