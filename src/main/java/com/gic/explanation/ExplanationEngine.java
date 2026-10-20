package com.gic.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gic.reliability.DecisionCode;
import com.gic.reliability.TradeOrder;
import com.gic.reliability.TradeStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static com.gic.explanation.Explanation.*;

/** Computes grounded claims before the model sees any input. No trading or persistence dependencies. */
public final class ExplanationEngine {
    private final RuleCatalog catalog;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public ExplanationEngine(RuleCatalog catalog) { this.catalog = catalog; }

    public record Analysis(String snapshotHash, String headlineId, boolean abstained, List<Finding> findings,
            List<Check> checks, List<Evidence> evidence, List<String> limitations) {
        public Analysis {
            findings = List.copyOf(findings); checks = List.copyOf(checks);
            evidence = List.copyOf(evidence); limitations = List.copyOf(limitations);
        }
    }

    public Analysis analyze(ExplanationSnapshot snapshot) {
        String hash;
        try { hash = TradeOrder.hash(json.writeValueAsString(snapshot)); }
        catch (Exception ex) { throw new IllegalArgumentException("Snapshot cannot be encoded", ex); }
        String tradeRef = "trade:" + snapshot.tradeId() + "@" + snapshot.version();
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence(tradeRef, "trade", "/reliable_trades/" + snapshot.tradeId(),
            "Recorded status=" + snapshot.status() + "; version=" + snapshot.version() + "; historyComplete=" + snapshot.complete()));
        String lookupRef = "receipt_lookup:" + snapshot.tradeId();
        evidence.add(new Evidence(lookupRef, "read_result", "/snapshot/receipt", snapshot.receipt() == null
            ? "No receipt observed by the bounded read query." : "A receipt was observed by the bounded read query."));
        List<String> limitations = new ArrayList<>(List.of(
            "Explanation only: no approval, capacity change or execution authority.",
            "Evidence concerns a local venue simulator; it does not establish real-exchange finality."));
        String problem = validate(snapshot);
        if (problem != null) {
            List<String> refs = List.of(tradeRef, rule("EVIDENCE_REQUIRED", evidence));
            Finding finding = new Finding("evidence.insufficient", "EVIDENCE_REQUIRED",
                "Cannot establish a specific cause from this evidence: " + problem + ". No execution or capacity change is authorized.", refs, true);
            return new Analysis(hash, finding.id(), true, List.of(finding), List.of(
                new Check("inspect.events", "Read the ordered trade events and compare their version and status with the trade record.", refs, true)), evidence, limitations);
        }
        for (ExplanationSnapshot.Event event : snapshot.events()) {
            evidence.add(new Evidence(event.id(), "event", "/trade_events/" + event.id(),
                "sequence=" + event.sequence() + "; status=" + event.status() + "; decision=" + event.decisionCode()));
        }
        String eventRef = snapshot.events().get(snapshot.events().size() - 1).id();
        List<Finding> findings = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        String id;
        String rule;
        String text;
        switch (snapshot.decisionCode()) {
            case INSTRUMENT_NOT_APPROVED:
                id = "decision.instrument_not_approved"; rule = "RISK_INSTRUMENT";
                text = "Rejected: the deterministic instrument/department eligibility check did not approve the submitted combination. No capacity was reserved for this request.";
                checks.add(new Check("inspect.eligibility", "Read the instrument eligibility configuration and the submitted combination; an explanation cannot approve an exception.", List.of(tradeRef, eventRef), true));
                break;
            case INSUFFICIENT_CAPACITY:
                id = "decision.insufficient_capacity"; rule = "RISK_CAPACITY";
                text = "Rejected: requested " + money(snapshot.amount()) + " " + snapshot.currency()
                    + ", but available capacity recorded at that decision was " + money(snapshot.availableAtDecision())
                    + ". No capacity was reserved for this request.";
                checks.add(new Check("inspect.decision_balance", "Inspect the balance captured in the rejection event. Today's available balance cannot explain a past decision.", List.of(tradeRef, eventRef), true));
                break;
            case NO_CURRENCY_LIMIT:
                id = "decision.no_currency_limit"; rule = "RISK_CURRENCY";
                text = "Rejected: no initialized limit existed for the exact counterparty/product/currency scope. Capacity in another currency cannot fund this request.";
                checks.add(new Check("inspect.currency_scope", "Read the exact-currency limit configuration and compare it with the submitted currency.", List.of(tradeRef, eventRef), true));
                break;
            case CAPACITY_RESERVED:
                id = "execution.reserved"; rule = "EXEC_RESERVATION";
                text = "Capacity is reserved and the execution intent is durable. This does not establish a venue execution.";
                checks.add(new Check("inspect.events", "Read the latest trade and submission events to see whether a deterministic worker has progressed.", List.of(tradeRef, eventRef), true));
                break;
            case SUBMISSION_PENDING:
                id = "execution.submitted"; rule = "EXEC_SUBMISSION";
                text = "Submission is recorded, but no terminal settlement is committed. Reserved capacity remains held.";
                checks.add(new Check("inspect.receipt", "Read the venue receipt and compare it with the stable trade reference; do not submit a replacement order based on this explanation.", List.of(tradeRef, eventRef), true));
                break;
            case EXECUTION_OUTCOME_UNKNOWN:
                id = "execution.unknown"; rule = "EXEC_UNKNOWN";
                text = "The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.";
                checks.add(new Check("inspect.receipt", "Read the existing venue receipt and trade events. This explanation does not invoke reconciliation or retry execution.", List.of(tradeRef, eventRef), true));
                break;
            case VENUE_ACCEPTED:
                id = "execution.accepted"; rule = "EXEC_ACCEPTED";
                text = "The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.";
                break;
            case VENUE_REJECTED:
                id = "execution.venue_rejected"; rule = "EXEC_REJECTED";
                text = "The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.";
                break;
            default: throw new IllegalStateException("Unsupported code passed validation");
        }
        findings.add(new Finding(id, rule, text, List.of(tradeRef, eventRef, rule(rule, evidence)), true));
        if (snapshot.receipt() != null) {
            String receiptRef = "receipt:" + snapshot.tradeId();
            evidence.add(new Evidence(receiptRef, "receipt", "/simulated_execution_receipts/" + snapshot.tradeId(),
                "Matching simulated venue receipt status=" + snapshot.receipt().status() + "; amount=" + money(snapshot.receipt().amount())));
            findings.add(new Finding("evidence.receipt", "EXEC_RECEIPT",
                "A matching simulated venue receipt is present with status " + snapshot.receipt().status()
                    + ". A receipt alone does not change the trade state or authorize a new execution.",
                List.of(receiptRef, tradeRef, rule("EXEC_RECEIPT", evidence)), true));
        } else if (snapshot.status() == TradeStatus.UNKNOWN || snapshot.status() == TradeStatus.SUBMITTED) {
            findings.add(new Finding("evidence.no_receipt", "EXEC_UNKNOWN",
                "No venue receipt was observed in this snapshot. Its absence does not prove that a real external request never executed.",
                List.of(tradeRef, lookupRef, rule("EXEC_UNKNOWN", evidence)), true));
        }
        if (snapshot.events().size() > 1) {
            findings.add(new Finding("evidence.timeline", "EXEC_RESERVATION",
                "The ordered status history is " + String.join(" → ", snapshot.events().stream().map(e -> e.status().name()).toList()) + ".",
                snapshot.events().stream().map(ExplanationSnapshot.Event::id).toList(), false));
        }
        return new Analysis(hash, id, false, findings, checks, evidence, limitations);
    }

    private String rule(String id, List<Evidence> evidence) {
        String reference = "rule:" + id + "@" + catalog.version();
        if (evidence.stream().noneMatch(item -> item.id().equals(reference))) {
            evidence.add(new Evidence(reference, "rule", "/explanations/rules-v1.json#/rules/" + id, catalog.text(id)));
        }
        return reference;
    }

    private String validate(ExplanationSnapshot s) {
        if (!s.complete() || s.status() == null || s.events().isEmpty() || s.events().size() > 64
                || s.version() < 0 || s.version() >= 64 || s.events().size() != s.version() + 1) return "the event history is missing, truncated or changed during collection";
        if (s.tradeId() == null || !s.tradeId().matches("[a-f0-9]{64}") || s.currency() == null
                || !s.currency().matches("[A-Z]{3}")) return "the evidence identity or currency is invalid";
        if (!catalog.version().equals(s.ruleVersion()) || s.decisionCode() == null
                || s.decisionCode() == DecisionCode.LEGACY_UNSPECIFIED) return "the recorded rule version or cause is legacy or unsupported";
        try { if (new BigDecimal(money(s.amount())).signum() <= 0) return "the recorded amount is invalid"; }
        catch (RuntimeException ex) { return "the recorded amount is invalid"; }
        TradeStatus previous = null;
        for (int index = 0; index < s.events().size(); index++) {
            var e = s.events().get(index);
            if (!Objects.equals(e.tradeId(), s.tradeId()) || e.sequence() != index
                    || !Objects.equals(e.id(), s.tradeId() + ":" + index) || e.at() == null
                    || !catalog.version().equals(e.ruleVersion()) || !compatible(e.status(), e.decisionCode())) return "event identities, sequence or decision provenance conflict";
            if (previous == null && e.status() != TradeStatus.RESERVED && e.status() != TradeStatus.REJECTED) return "the initial event is incomplete";
            if (previous != null && !allowed(previous, e.status())) return "the recorded state transitions conflict";
            previous = e.status();
        }
        var last = s.events().get(s.events().size() - 1);
        if (last.status() != s.status() || last.decisionCode() != s.decisionCode()
                || !Objects.equals(last.availableAtDecision(), s.availableAtDecision())
                || !compatible(s.status(), s.decisionCode())) return "the trade and latest event disagree";
        if (s.decisionCode() == DecisionCode.INSUFFICIENT_CAPACITY) {
            try {
                BigDecimal available = new BigDecimal(money(s.availableAtDecision()));
                if (available.signum() < 0 || available.compareTo(new BigDecimal(s.amount())) >= 0) return "the recorded capacity does not support the rejection";
            } catch (RuntimeException ex) { return "the historical decision balance is missing or invalid"; }
        }
        var receipt = s.receipt();
        if (receipt != null) {
            try {
                if (!receipt.identityMatches() || receipt.at() == null || receipt.status() == null || !receipt.status().terminal()
                        || new BigDecimal(money(receipt.amount())).compareTo(new BigDecimal(s.amount())) != 0) return "the receipt conflicts with the trade identity or amount";
            } catch (RuntimeException ex) { return "the receipt is malformed"; }
            if (s.status() == TradeStatus.RESERVED || (s.status().terminal() && receipt.status() != s.status())) return "the receipt and recorded state conflict";
            if (s.status() == TradeStatus.REJECTED && s.decisionCode() != DecisionCode.VENUE_REJECTED) return "a risk rejection has an unexpected venue receipt";
        }
        if ((s.decisionCode() == DecisionCode.VENUE_ACCEPTED || s.decisionCode() == DecisionCode.VENUE_REJECTED) && receipt == null) return "the confirming venue receipt is missing";
        return null;
    }

    private boolean compatible(TradeStatus status, DecisionCode code) {
        if (status == null || code == null) return false;
        return switch (status) {
            case RESERVED -> code == DecisionCode.CAPACITY_RESERVED;
            case SUBMITTED -> code == DecisionCode.SUBMISSION_PENDING;
            case UNKNOWN -> code == DecisionCode.EXECUTION_OUTCOME_UNKNOWN;
            case EXECUTED -> code == DecisionCode.VENUE_ACCEPTED;
            case REJECTED -> Set.of(DecisionCode.INSTRUMENT_NOT_APPROVED, DecisionCode.INSUFFICIENT_CAPACITY,
                DecisionCode.NO_CURRENCY_LIMIT, DecisionCode.VENUE_REJECTED).contains(code);
        };
    }
    private boolean allowed(TradeStatus before, TradeStatus after) {
        return before == TradeStatus.RESERVED ? after == TradeStatus.SUBMITTED
            : (before == TradeStatus.SUBMITTED || before == TradeStatus.UNKNOWN)
                && Set.of(TradeStatus.UNKNOWN, TradeStatus.EXECUTED, TradeStatus.REJECTED).contains(after);
    }
    private String money(String value) { return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY).toPlainString(); }
}
