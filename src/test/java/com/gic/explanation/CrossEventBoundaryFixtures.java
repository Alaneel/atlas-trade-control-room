package com.gic.explanation;

import com.gic.reliability.DecisionCode;
import com.gic.reliability.TradeOrder;
import com.gic.reliability.TradeStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Deterministic development fixtures, not unseen model evaluation data. */
final class CrossEventBoundaryFixtures {
    static final Instant ORIGIN = Instant.parse("2026-10-22T00:00:00Z");
    record Case(String id, ExplanationSnapshot snapshot) { }
    static ExplanationSnapshot snapshot(String name, long[] milliseconds, TradeStatus terminal, Long receiptMillis) {
        String id = TradeOrder.hash("boundary/" + name); List<ExplanationSnapshot.Event> events = new ArrayList<>();
        for (int i = 0; i < milliseconds.length; i++) {
            TradeStatus state = i == 0 ? TradeStatus.RESERVED : i == 1 ? TradeStatus.SUBMITTED
                : i == milliseconds.length - 1 && terminal != null ? terminal : TradeStatus.UNKNOWN;
            events.add(new ExplanationSnapshot.Event(id + ":" + i, id, i, state, decision(state), "atlas-trade-rules-v1", null,
                ORIGIN.plusMillis(milliseconds[i])));
        }
        var last = events.get(events.size() - 1);
        var receipt = receiptMillis == null ? null : new ExplanationSnapshot.Receipt(terminal == null ? TradeStatus.EXECUTED : terminal,
            "10.00", true, ORIGIN.plusMillis(receiptMillis));
        return new ExplanationSnapshot(id, last.sequence(), last.status(), "10.00", "USD", last.decisionCode(),
            "atlas-trade-rules-v1", null, events, receipt, true);
    }
    static DecisionCode decision(TradeStatus state) {
        return switch (state) {
            case RESERVED -> DecisionCode.CAPACITY_RESERVED;
            case SUBMITTED -> DecisionCode.SUBMISSION_PENDING;
            case UNKNOWN -> DecisionCode.EXECUTION_OUTCOME_UNKNOWN;
            case EXECUTED -> DecisionCode.VENUE_ACCEPTED;
            case REJECTED -> DecisionCode.VENUE_REJECTED;
        };
    }
    static List<Case> cases() {
        List<Case> rows = new ArrayList<>();
        for (long gap : new long[]{59999, 60000, 60001}) {
            add(rows, "dispatch_" + gap, new long[]{0, gap, gap + 1000}, null, null);
            add(rows, "settlement_" + gap, new long[]{0, 1000, 2000, 120000}, null, 120000 - gap);
        }
        for (long delta : new long[]{-1, 0, 1}) add(rows, "receipt_direction_" + delta, new long[]{0, 1000, 2000}, null, 2000 + delta);
        for (int unknowns : new int[]{1, 2, 6, 61}) {
            long[] offsets = new long[unknowns + 3]; for (int i = 0; i < offsets.length; i++) offsets[i] = i * 1000L;
            add(rows, "recovery_size_" + offsets.length, offsets, TradeStatus.EXECUTED, offsets[offsets.length - 1] - 500);
        }
        add(rows, "equal_event_times", new long[]{0, 1000, 1000, 1000}, null, null);
        add(rows, "non_adjacent_reversal", new long[]{10000, 12000, 9000, 13000}, null, null);
        add(rows, "earliest_reversal_not_suffix_minimum", new long[]{0, 5000, 4000, 3000}, null, null);
        Random random = new Random(20261022L);
        for (int sample = 0; sample < 48; sample++) {
            long[] offsets = new long[3 + random.nextInt(7)];
            for (int i = 1; i < offsets.length; i++) offsets[i] = offsets[i - 1] + random.nextInt(90001);
            if (sample % 4 == 0) offsets[1] = offsets[offsets.length - 1] + 1;
            TradeStatus terminal = sample % 3 == 0 ? TradeStatus.REJECTED : sample % 3 == 1 ? TradeStatus.EXECUTED : null;
            Long receipt = terminal == null && sample % 2 == 0 ? null : offsets[offsets.length - 1] - random.nextInt(120001);
            add(rows, "seeded_" + sample, offsets, terminal, receipt);
        }
        return List.copyOf(rows);
    }
    private static void add(List<Case> rows, String id, long[] offsets, TradeStatus state, Long receipt) {
        rows.add(new Case(id, snapshot(id, offsets, state, receipt)));
    }
}
