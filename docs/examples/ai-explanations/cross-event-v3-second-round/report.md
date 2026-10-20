# Atlas cross-event comparison

Model: `gpt-4.1-mini-2025-04-14` · Dataset: `atlas-cross-event-cases-v1`

Completed: True · Cases: 10 + 2 abstention controls

| Operational metric | Result |
| --- | --- |
| Initiated requests (generation + review) | 30 |
| Generation fallback | 8/10 |
| Generation P50 / P95 / max | 3526 / 4207 / 4207 ms |
| Usage-based total cost USD | 0.042706 |
| Missing-usage / unpriced requests | 0 / 0 |

A missing total is unknown cost, not zero. No retries or hidden warmup calls. Paid reviewer requests are included in total cost.

## Mechanical checks

Program-checked AI relation objects: 1.
Rule summaries retained: 10/10.
Abstention without request: 2/2.

- Explicit expected relations, ai: 1/17.
- Explicit expected relations, strong_rules: 17/17.

Checked relation recall measures explicit proof objects, not prose usefulness or independent semantic accuracy. Original rules may express some relevant meaning without proof objects.

## Source-blinded model review

Valid reviews: 20/20.

| Variant | Goal coverage | Clarity (1–5) | Calibration (1–5) | Unsupported / citation / unsafe flags |
| --- | --- | --- | --- | --- |
| rules | 52/60 | 4.3 | 4.5 | 0 / 0 / 0 |
| strong_rules | 60/60 | 4.9 | 5 | 0 / 0 / 0 |
| ai | 54/60 | 4.4 | 4.5 | 0 / 0 / 0 |

| AI compared with | Stable wins | Ties | Stable losses | Order-sensitive/disagreement | Incomplete |
| --- | --- | --- | --- | --- | --- |
| original rules | 1 | 6 | 0 | 3 | 0 |
| stronger cross-event rules | 0 | 2 | 7 | 1 | 0 |

Same snapshot generates and grades; source labels hidden, positions reversed. Each case is counted once for stable pair preference; rating counts include both presentations. Self-preference, correlated error and order effects remain. Not human, heldout or production evidence.

## Cases

### receipt_before_uncertainty

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### receipt_recorded_later

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### persistent_unknown_without_receipt

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: 3 UNKNOWN events span 38 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Repeated UNKNOWN status events from earlier to later sequences indicate persistent uncertainty over time, showing no new execution confirmation or rejection.
  Uncertainty: While repeated UNKNOWN statuses confirm ongoing uncertainty, they do not prove multiple independent submissions or retries; the reason for persistence is not discernible.
  Evidence: 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:2, 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:4

### eventual_execution

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.


### eventual_venue_rejection

The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.


### delay_before_submission

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### old_receipt_state_still_unknown

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### rapid_terminal_counterexample

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.


### timestamp_sequence_disagreement

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### sparse_unknown_counterexample

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### missing_event_control

Cannot establish a specific cause from this evidence: the event history is missing, truncated or changed during collection. No execution or capacity change is authorized.


### foreign_receipt_control

Cannot establish a specific cause from this evidence: the receipt conflicts with the trade identity or amount. No execution or capacity change is authorized.
