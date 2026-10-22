package com.gic.explanation;

import java.util.ArrayList;
import java.util.List;

/** Retained pair-enumeration algorithm, using the current proof/rendering kernel. Test only. */
final class CrossEventRuleOracle {
    static List<Explanation.Attribution> enumerate(ExplanationEngine.Analysis a) {
        if (a.abstained()) return List.of();
        var s = a.snapshot(); List<Explanation.Attribution> result = new ArrayList<>();
        List<String> refs = new ArrayList<>(s.events().stream().map(ExplanationSnapshot.Event::id).toList());
        refs.add("receipt:" + s.tradeId()); refs.add("receipt_lookup:" + s.tradeId());
        for (String relation : List.of("TEMPORAL_ORDER_UNRELIABLE", "DISPATCH_DELAY", "SETTLEMENT_GAP", "RECOVERY_COMPLETED",
                "RECEIPT_BEFORE_UNKNOWN", "RECEIPT_AFTER_UNKNOWN", "REPEATED_UNKNOWN", "NO_RECEIPT_NOT_FAILURE")) {
            Explanation.Attribution found = null;
            outer: for (String from : refs) for (String to : refs) {
                if (from.equals(to)) continue;
                List<String> evidence = relation.equals("RECOVERY_COMPLETED") ? List.of(from, to, "receipt:" + s.tradeId()) : List.of(from, to);
                try {
                    found = CrossEventVerifier.verify(new CrossEventVerifier.Proposal(relation, from, to, evidence,
                        CrossEventVerifier.interpretation(relation), CrossEventVerifier.uncertainty(relation)), a);
                    break outer;
                } catch (ModelPlanner.Failure | IllegalArgumentException ignored) { }
            }
            if (found != null) result.add(new Explanation.Attribution(found.relation(), found.observedRelation(), found.interpretation(),
                found.uncertainty(), found.evidenceIds(), "Program-checked observations and deterministic reviewed hypotheses. "
                    + "Physical causation is not established. No model request or operation authority."));
        }
        return result.stream().limit(3).toList();
    }
}
