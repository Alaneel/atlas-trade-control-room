# Atlas cross-event comparison

Model: `gpt-4.1-mini-2025-04-14` · Dataset: `atlas-cross-event-cases-v1`

Completed: True · Cases: 10 + 2 abstention controls

| Operational metric | Result |
| --- | --- |
| Initiated requests (generation + review) | 30 |
| Generation fallback | 6/10 |
| Generation P50 / P95 / max | 5357 / 6756 / 6756 ms |
| Usage-based total cost USD | 0.0513064 |
| Missing-usage / unpriced requests | 0 / 0 |

A missing total is unknown cost, not zero. No retries or hidden warmup calls. Paid reviewer requests are included in total cost.

## Mechanical checks

Program-checked AI relation objects: 3.
Rule summaries retained: 10/10.
Abstention without request: 2/2.

- Explicit expected relations, ai: 3/17.
- Explicit expected relations, strong_rules: 17/17.

Checked relation recall measures explicit proof objects, not prose usefulness or independent semantic accuracy. Original rules may express some relevant meaning without proof objects.

## Source-blinded model review

Valid reviews: 10/20.

| Variant | Goal coverage | Clarity (1–5) | Calibration (1–5) | Unsupported / citation / unsafe flags |
| --- | --- | --- | --- | --- |
| rules | 23/30 | 4.4 | 4.7 | 0 / 0 / 0 |
| strong_rules | 30/30 | 4.8 | 5 | 0 / 0 / 0 |
| ai | 25/30 | 4.5 | 4.7 | 0 / 0 / 0 |

| AI compared with | Stable wins | Ties | Stable losses | Order-sensitive/disagreement | Incomplete |
| --- | --- | --- | --- | --- | --- |
| original rules | 0 | 4 | 0 | 0 | 6 |
| stronger cross-event rules | 0 | 0 | 3 | 1 | 6 |

Same snapshot generates and grades; source labels hidden, positions reversed. Each case is counted once for stable pair preference; rating counts include both presentations. Self-preference, correlated error and order effects remain. Not human, heldout or production evidence.

## Cases

### receipt_before_uncertainty

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds before the first UNKNOWN event. Latest recorded state=UNKNOWN.
  Interpretation: The receipt precedes the first UNKNOWN event, so the system received confirmation before the uncertainty was recorded. This temporal order suggests an acknowledgment gap but does not confirm network or venue fault.
  Uncertainty: The recorded receipt time precedes the first UNKNOWN event, indicating the receipt was logged before the trade entered an uncertain execution state, but this does not confirm message delivery or effective execution time.
  Evidence: receipt:05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51, 05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51:2

### receipt_recorded_later

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### persistent_unknown_without_receipt

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### eventual_execution

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.


### eventual_venue_rejection

The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.


### delay_before_submission

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest UNKNOWN event is supported by no matching venue receipt in the snapshot. This missing receipt does not prove a failure or execution rejection, only that no receipt was locally available at check time.
  Uncertainty: The absence of a venue receipt cannot confirm whether the trade was executed or rejected; it only confirms lack of local evidence.
  Evidence: 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:2, receipt_lookup:36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819

### old_receipt_state_still_unknown

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### rapid_terminal_counterexample

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.


### timestamp_sequence_disagreement

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### sparse_unknown_counterexample

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: While the trade status remains UNKNOWN and no receipt is present, this missing receipt does not prove that the trade failed or was never executed; it simply records no local confirmation.
  Uncertainty: The absence of a receipt neither confirms nor denies that the trade executed externally; it only records that no local evidence of execution was found.
  Evidence: 31a22893bba3c99a758eb880d2bc95030cfb2425636afc2ecf6600aa6953f6a0:2, receipt_lookup:31a22893bba3c99a758eb880d2bc95030cfb2425636afc2ecf6600aa6953f6a0

### missing_event_control

Cannot establish a specific cause from this evidence: the event history is missing, truncated or changed during collection. No execution or capacity change is authorized.


### foreign_receipt_control

Cannot establish a specific cause from this evidence: the receipt conflicts with the trade identity or amount. No execution or capacity change is authorized.
