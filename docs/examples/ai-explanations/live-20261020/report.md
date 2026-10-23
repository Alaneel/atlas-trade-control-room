# Atlas real-model explanation evaluation

Recorded: 2026-10-20T15:29:12.699850Z · Requested model: `gpt-4.1-mini-2025-04-14`

Cases: 6 · Initiated requests: 6 · Validated plans: 6

| Metric | Result |
| --- | --- |
| Fallback rate | 0.0% (0/6) |
| End-to-end latency P50 / P95 / max | 2108 / 3746 / 3746 ms |
| Reported input / cached input / output tokens | 6942 / 0 / 433 |
| Estimated total USD | 0.0034696 |
| Known priced usage USD range | 0.0034696–0.0034696 |
| Requests missing usage / unpriced | 0 / 0 |

Cost is a token-usage estimate at the recorded standard price, not an invoice. A timed-out request can still be billable; missing usage is not zero cost.

## Mechanical quality checks

| Check | Final application output (includes fallback) | Accepted model plans only |
| --- | --- | --- |
| expected_headline | 6/6 | 6/6 |
| reason_placed_first | 6/6 | 6/6 |
| required_findings_and_checks | 6/6 | 6/6 |
| exact_reviewed_text_only | 6/6 | 6/6 |
| citation_integrity | 6/6 | 6/6 |
| unknown_remains_uncertain | 2/2 | 2/2 |

Invalid-evidence abstention controls: 14/14; paid requests: 0.
Presentation identical to rule baseline: 6/6.

Frozen development-visible synthetic cases. Contract/text/citation checks use reviewed rule output, not an independent semantic grader. Final application scores include rule fallback; accepted-model scores exclude it. No human rating or unrestricted model reasoning claim.

## Per-case results

| Case | Mode | Outcome | Latency ms |
| --- | --- | --- |
| capacity_rejection | model_ordered | validated | 3746 |
| currency_scope_missing | model_ordered | validated | 2108 |
| eligibility_rejection | model_ordered | validated | 2281 |
| unknown_no_receipt | model_ordered | validated | 2605 |
| unknown_success_receipt | model_ordered | validated | 1472 |
| venue_rejection | model_ordered | validated | 1890 |

## Rules and real-provider presentation

### capacity_rejection

Summary: Rejected: requested 10.00 USD, but available capacity recorded at that decision was 5.00. No capacity was reserved for this request.

Rule finding order: decision.insufficient_capacity

Application finding order: decision.insufficient_capacity

- Rejected: requested 10.00 USD, but available capacity recorded at that decision was 5.00. No capacity was reserved for this request.

### currency_scope_missing

Summary: Rejected: no initialized limit existed for the exact counterparty/product/currency scope. Capacity in another currency cannot fund this request.

Rule finding order: decision.no_currency_limit

Application finding order: decision.no_currency_limit

- Rejected: no initialized limit existed for the exact counterparty/product/currency scope. Capacity in another currency cannot fund this request.

### eligibility_rejection

Summary: Rejected: the deterministic instrument/department eligibility check did not approve the submitted combination. No capacity was reserved for this request.

Rule finding order: decision.instrument_not_approved

Application finding order: decision.instrument_not_approved

- Rejected: the deterministic instrument/department eligibility check did not approve the submitted combination. No capacity was reserved for this request.

### unknown_no_receipt

Summary: The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

Rule finding order: execution.unknown, evidence.no_receipt, evidence.timeline

Application finding order: execution.unknown, evidence.no_receipt, evidence.timeline

- The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.
- No venue receipt was observed in this snapshot. Its absence does not prove that a real external request never executed.
- The ordered status history is RESERVED → SUBMITTED → UNKNOWN.

### unknown_success_receipt

Summary: The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

Rule finding order: execution.unknown, evidence.receipt, evidence.timeline

Application finding order: execution.unknown, evidence.receipt, evidence.timeline

- The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.
- A matching simulated venue receipt is present with status EXECUTED. A receipt alone does not change the trade state or authorize a new execution.
- The ordered status history is RESERVED → SUBMITTED → UNKNOWN.

### venue_rejection

Summary: The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.

Rule finding order: execution.venue_rejected, evidence.receipt, evidence.timeline

Application finding order: execution.venue_rejected, evidence.receipt, evidence.timeline

- The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.
- A matching simulated venue receipt is present with status REJECTED. A receipt alone does not change the trade state or authorize a new execution.
- The ordered status history is RESERVED → SUBMITTED → REJECTED.

[Pricing source](https://developers.openai.com/api/docs/pricing), verified 2026-10-20.
