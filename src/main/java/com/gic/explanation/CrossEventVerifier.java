package com.gic.explanation;

import com.gic.reliability.TradeStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A proof checker, not a root-cause oracle. It can neither query nor mutate a business system. */
public final class CrossEventVerifier {
    public static final String VERSION = "atlas-cross-event-v1";
    public static final String SCOPE = "Evidence identity, state and recorded temporal relation checked by code. "
        + "Interpretation and uncertainty prose are model-authored hypotheses, not semantically verified facts. "
        + "Recorded time order alone does not prove physical causation. No operations are authorized.";
    public record Proposal(String relation, String fromId, String toId, List<String> evidenceIds,
                           String interpretation, String uncertainty) {
        public Proposal { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }
    public static Map<String, String> definitions() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("RECEIPT_BEFORE_UNKNOWN", "from=matching receipt, to=FIRST UNKNOWN event; receipt time strictly before event. Possible acknowledgement/commit gap, not proof of a network fault.");
        types.put("RECEIPT_AFTER_UNKNOWN", "from=FIRST UNKNOWN event, to=matching receipt; receipt time strictly after event. Receipt recorded later; actual execution/visibility latency is not established.");
        types.put("REPEATED_UNKNOWN", "from=FIRST UNKNOWN, to=LAST UNKNOWN, distinct sequences. Repeated uncertain observations, NOT proof of repeated venue submissions.");
        types.put("RECOVERY_COMPLETED", "from=FIRST UNKNOWN, to=latest terminal event; include matching receipt as a third citation. Terminal recorded state resolves earlier uncertainty, without proving how many submissions occurred.");
        types.put("DISPATCH_DELAY", "from=initial RESERVED, to=SUBMITTED; recorded interval at least 60 seconds (inspection threshold, not a trading rule/SLA). Does not prove venue latency.");
        types.put("SETTLEMENT_GAP", "from=matching receipt, to=latest event (UNKNOWN or terminal); at least 60 seconds. Recorded receipt-to-state interval, not current wall-clock age.");
        types.put("TEMPORAL_ORDER_UNRELIABLE", "from/to=events with increasing sequence but strictly decreasing timestamps. Only this and NO_RECEIPT_NOT_FAILURE may be used if ANY event timestamps reverse; do not diagnose clock skew as a proven root cause.");
        types.put("NO_RECEIPT_NOT_FAILURE", "from=latest UNKNOWN event, to=receipt_lookup:<tradeId>; receipt must be absent. Missing local receipt proves neither rejection nor absence of an external execution.");
        return types;
    }

    public static Explanation.Attribution verify(Proposal p, ExplanationEngine.Analysis a) {
        if (a.abstained() || p == null || !definitions().containsKey(p.relation())) throw invalid();
        text(p.interpretation(), 600); text(p.uncertainty(), 400);
        ExplanationSnapshot s = a.snapshot();
        var from = event(s, p.fromId()); var to = event(s, p.toId());
        String receipt = "receipt:" + s.tradeId(), lookup = "receipt_lookup:" + s.tradeId();
        var unknowns = s.events().stream().filter(e -> e.status() == TradeStatus.UNKNOWN).toList();
        var firstUnknown = unknowns.isEmpty() ? null : unknowns.get(0);
        var lastUnknown = unknowns.isEmpty() ? null : unknowns.get(unknowns.size() - 1);
        var last = s.events().get(s.events().size() - 1);
        boolean reversed = reversed(s);
        if (reversed && !Set.of("TEMPORAL_ORDER_UNRELIABLE", "NO_RECEIPT_NOT_FAILURE").contains(p.relation())) throw invalid();
        Set<String> expected = Set.of(p.fromId(), p.toId());
        String observation;
        switch (p.relation()) {
            case "RECEIPT_BEFORE_UNKNOWN" -> {
                require(receipt.equals(p.fromId()) && s.receipt() != null && to != null && to.equals(firstUnknown)
                    && s.receipt().at().isBefore(to.at()));
                observation = "A matching " + s.receipt().status() + " simulated receipt was recorded "
                    + seconds(s.receipt().at(), to.at()) + " seconds before the first UNKNOWN event. Latest recorded state=" + s.status() + ".";
            }
            case "RECEIPT_AFTER_UNKNOWN" -> {
                require(from != null && from.equals(firstUnknown) && receipt.equals(p.toId()) && s.receipt() != null
                    && from.at().isBefore(s.receipt().at()));
                observation = "A matching " + s.receipt().status() + " simulated receipt was recorded "
                    + seconds(from.at(), s.receipt().at()) + " seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.";
            }
            case "REPEATED_UNKNOWN" -> {
                require(from != null && to != null && from.equals(firstUnknown) && to.equals(lastUnknown) && from.sequence() < to.sequence());
                observation = unknowns.size() + " UNKNOWN events span " + seconds(from.at(), to.at())
                    + " seconds in this history. Event count is not a count of venue submissions.";
            }
            case "RECOVERY_COMPLETED" -> {
                require(from != null && from.equals(firstUnknown) && to != null && to.equals(last) && to.status().terminal()
                    && from.sequence() < to.sequence() && s.receipt() != null && s.receipt().status() == to.status());
                expected = Set.of(p.fromId(), p.toId(), receipt);
                observation = "The first UNKNOWN was followed " + seconds(from.at(), to.at()) + " seconds later by recorded "
                    + to.status() + " settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.";
            }
            case "DISPATCH_DELAY" -> {
                require(from != null && from.sequence() == 0 && from.status() == TradeStatus.RESERVED && to != null
                    && to.status() == TradeStatus.SUBMITTED && millis(from.at(), to.at()) >= 60000);
                observation = "The RESERVED-to-SUBMITTED interval is " + seconds(from.at(), to.at())
                    + " seconds, above the 60-second inspection threshold. This interval precedes the recorded venue outcome.";
            }
            case "SETTLEMENT_GAP" -> {
                require(receipt.equals(p.fromId()) && s.receipt() != null && to != null && to.equals(last)
                    && (to.status() == TradeStatus.UNKNOWN || to.status().terminal()) && millis(s.receipt().at(), to.at()) >= 60000);
                observation = "The matching simulated receipt predates the latest " + to.status() + " event by "
                    + seconds(s.receipt().at(), to.at()) + " seconds. This is a recorded receipt-to-state gap, not the current age of the trade.";
            }
            case "TEMPORAL_ORDER_UNRELIABLE" -> {
                require(from != null && to != null && from.sequence() < to.sequence() && from.at().isAfter(to.at()));
                observation = "Event sequence " + from.sequence() + " precedes " + to.sequence()
                    + ", but its timestamp is later. Sequence order and wall-clock order disagree; elapsed-time attribution is unreliable.";
            }
            case "NO_RECEIPT_NOT_FAILURE" -> {
                require(from != null && from.equals(last) && from.status() == TradeStatus.UNKNOWN && s.receipt() == null && lookup.equals(p.toId()));
                observation = "The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.";
            }
            default -> throw invalid();
        }
        require(p.evidenceIds().size() == expected.size() && Set.copyOf(p.evidenceIds()).equals(expected));
        require(a.evidence().stream().map(Explanation.Evidence::id).toList().containsAll(expected));
        return new Explanation.Attribution(p.relation(), observation, p.interpretation(), p.uncertainty(), p.evidenceIds(), SCOPE);
    }

    /** Strong deterministic comparator: same evidence and proof kernel, no provider/model-generated prose. */
    public static List<Explanation.Attribution> strongBaseline(ExplanationEngine.Analysis a) {
        if (a.abstained()) return List.of();
        var s = a.snapshot();
        List<Explanation.Attribution> result = new ArrayList<>();
        List<String> refs = new ArrayList<>(s.events().stream().map(ExplanationSnapshot.Event::id).toList());
        refs.add("receipt:" + s.tradeId()); refs.add("receipt_lookup:" + s.tradeId());
        for (String relation : List.of("TEMPORAL_ORDER_UNRELIABLE", "DISPATCH_DELAY", "SETTLEMENT_GAP", "RECOVERY_COMPLETED",
                "RECEIPT_BEFORE_UNKNOWN", "RECEIPT_AFTER_UNKNOWN", "REPEATED_UNKNOWN", "NO_RECEIPT_NOT_FAILURE")) {
            Explanation.Attribution found = null;
            outer: for (String from : refs) for (String to : refs) {
                if (from.equals(to)) continue;
                List<String> evidence = relation.equals("RECOVERY_COMPLETED") ? List.of(from, to, "receipt:" + s.tradeId()) : List.of(from, to);
                try {
                    var p = new Proposal(relation, from, to, evidence, interpretation(relation), uncertainty(relation));
                    found = verify(p, a); break outer;
                } catch (ModelPlanner.Failure | IllegalArgumentException ignored) { /* unsupported candidate */ }
            }
            if (found != null) result.add(new Explanation.Attribution(found.relation(), found.observedRelation(), found.interpretation(),
                found.uncertainty(), found.evidenceIds(), "Program-checked observations and deterministic reviewed hypotheses. "
                    + "Physical causation is not established. No model request or operation authority."));
        }
        return result.stream().limit(3).toList();
    }
    static String interpretation(String relation) {
        return switch (relation) {
            case "RECEIPT_BEFORE_UNKNOWN" -> "This pattern is consistent with an acknowledgement or local settlement gap after the simulated venue recorded an outcome.";
            case "RECEIPT_AFTER_UNKNOWN" -> "The receipt record followed uncertainty; later recording or progress can explain the differing observations.";
            case "REPEATED_UNKNOWN" -> "Uncertainty persisted across multiple observations; those events alone do not establish repeated submissions.";
            case "RECOVERY_COMPLETED" -> "A later terminal settlement resolves the earlier uncertain state in the recorded history.";
            case "DISPATCH_DELAY" -> "A visible delay occurred before recorded submission, so it should not be attributed solely to the venue.";
            case "SETTLEMENT_GAP" -> "Receipt recording and local state progress are separated; inspecting the local settlement path may explain the gap.";
            case "TEMPORAL_ORDER_UNRELIABLE" -> "Clock skew or inconsistent timestamp recording are possible, so rely on event sequence for state order.";
            default -> "The evidence cannot distinguish an absent execution from an executed request whose local receipt was not observed.";
        };
    }
    static String uncertainty(String relation) {
        return switch (relation) {
            case "RECEIPT_BEFORE_UNKNOWN" -> "These records cannot distinguish acknowledgement loss from a local commit interruption or another cause. Inspect existing event and receipt logs only.";
            case "RECEIPT_AFTER_UNKNOWN" -> "Receipt creation time is not a proven execution or network-arrival time. Delayed recording, visibility or progress remain alternatives.";
            case "REPEATED_UNKNOWN" -> "The number of external requests and their physical outcomes are not established by repeated state observations. Inspect existing submission logs only.";
            case "RECOVERY_COMPLETED" -> "The final state is recorded, but the mechanism of recovery and number of venue submissions remain unknown. No further trading action is implied.";
            case "DISPATCH_DELAY" -> "The 60-second threshold is an inspection heuristic, not a business SLA. Scheduling, process pauses and timestamp issues are unproven alternatives.";
            case "SETTLEMENT_GAP" -> "The interval is historical, not current wall-clock age. It does not establish why local state progress lagged receipt recording.";
            case "TEMPORAL_ORDER_UNRELIABLE" -> "Clock skew and timestamp-recording problems are possible alternatives. Neither is established; use sequence order for the recorded states.";
            default -> "Missing local evidence does not prove venue failure or no execution. Inspect existing records without issuing a new trading request.";
        };
    }
    private static ExplanationSnapshot.Event event(ExplanationSnapshot s, String id) { return s.events().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null); }
    private static boolean reversed(ExplanationSnapshot s) {
        for (int i = 1; i < s.events().size(); i++) if (s.events().get(i - 1).at().isAfter(s.events().get(i).at())) return true;
        return false;
    }
    private static long millis(java.time.Instant from, java.time.Instant to) { return Duration.between(from, to).toMillis(); }
    private static String seconds(java.time.Instant from, java.time.Instant to) { return java.math.BigDecimal.valueOf(millis(from, to), 3).stripTrailingZeros().toPlainString(); }
    private static void text(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(c -> Character.isISOControl(c))
            && !value.matches("(?is).*(https?://|```|[<>]).*"));
    }
    private static void require(boolean pass) { if (!pass) throw invalid(); }
    private static ModelPlanner.Failure invalid() { return new ModelPlanner.Failure("invalid_attribution", false); }
}
