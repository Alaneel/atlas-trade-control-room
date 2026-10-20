# Atlas cross-event comparison

Model: `gpt-5.6-luna` · Dataset: `atlas-cross-event-cases-v1`

Completed: True · Cases: 10 + 2 abstention controls

| Operational metric | Result |
| --- | --- |
| Initiated requests (generation + review) | 30 |
| Generation fallback | 2/10 |
| Generation P50 / P95 / max | 4388 / 6289 / 6289 ms |
| Usage-based total cost USD | 0.0396046 |
| Missing-usage / unpriced requests | 0 / 0 |

A missing total is unknown cost, not zero. No retries or hidden warmup calls. Paid reviewer requests are included in total cost.

## Mechanical checks

Program-checked AI relation objects: 16.
Rule summaries retained: 10/10.
Abstention without request: 2/2.

- Explicit expected relations, ai: 14/17.
- Explicit expected relations, strong_rules: 17/17.

Checked relation recall measures explicit proof objects, not prose usefulness or independent semantic accuracy. Original rules may express some relevant meaning without proof objects.

## Source-blinded model review

Valid reviews: 20/20.

| Variant | Goal coverage | Clarity (1–5) | Calibration (1–5) | Unsupported / citation / unsafe flags |
| --- | --- | --- | --- | --- |
| rules | 46/60 | 4.15 | 4.7 | 0 / 0 / 0 |
| strong_rules | 57/60 | 4.85 | 4.95 | 0 / 0 / 0 |
| ai | 57/60 | 4.6 | 4.85 | 0 / 0 / 0 |

| AI compared with | Stable wins | Ties | Stable losses | Order-sensitive/disagreement | Incomplete |
| --- | --- | --- | --- | --- | --- |
| original rules | 1 | 1 | 0 | 8 | 0 |
| stronger cross-event rules | 0 | 0 | 2 | 8 | 0 |

Generator and reviewer model IDs are recorded; source labels hidden, positions reversed. Each case is counted once for stable pair preference; rating counts include both presentations. Same-provider preference, correlated error and order effects remain (including self-preference when IDs match). Not human, heldout or production evidence.

## Cases

### receipt_before_uncertainty

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds before the first UNKNOWN event. Latest recorded state=UNKNOWN.
  Interpretation: The matching EXECUTED receipt was recorded before the trade entered UNKNOWN, showing an acknowledgement/commit gap in recorded evidence while the trade state remained uncertain.
  Uncertainty: The receipt’s local recorded time precedes the UNKNOWN event, but it does not establish physical execution or arrival timing.
  Evidence: receipt:05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51, 05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51:2

### receipt_recorded_later

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 28 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The later matching EXECUTED receipt provides evidence to inspect alongside the still-recorded UNKNOWN state. It does not by itself prove execution timing, arrival latency, or a cause for the uncertainty.
  Uncertainty: The receipt was recorded after the UNKNOWN event; its local timestamp does not establish when execution or visibility occurred.
  Evidence: e31127fe60e7c1127f71c767003470c97d139195e5eb63e17e807f06b5d16249:2, receipt:e31127fe60e7c1127f71c767003470c97d139195e5eb63e17e807f06b5d16249

### persistent_unknown_without_receipt

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: 3 UNKNOWN events span 38 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: The sequence contains distinct UNKNOWN observations from the first through the latest UNKNOWN event, preserving uncertainty rather than proving multiple submissions.
  Uncertainty: Recorded UNKNOWN observations recur, but they do not establish repeated venue submissions or a physical fault.
  Evidence: 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:2, 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:4
- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest recorded UNKNOWN state has no observed local receipt, so the outcome remains unresolved and should not be treated as a confirmed failure.
  Uncertainty: The bounded local query found no receipt; this does not prove rejection, non-execution, or absence of an external request.
  Evidence: 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:4, receipt_lookup:5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3

### eventual_execution

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.

- Observed: A matching EXECUTED simulated receipt was recorded 27 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching receipt was recorded after the first UNKNOWN observation, so recorded evidence shows uncertainty preceding receipt visibility without proving a venue or network fault.
  Uncertainty: Receipt recorded later; actual execution or visibility latency is not established.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, receipt:8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509
- Observed: 2 UNKNOWN events span 28 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Two distinct UNKNOWN events document repeated uncertain observations before the later terminal outcome; they do not establish multiple submissions.
  Uncertainty: Repeated UNKNOWN observations do not prove repeated venue submissions.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:3
- Observed: The first UNKNOWN was followed 29 seconds later by recorded EXECUTED settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.
  Interpretation: The later EXECUTED terminal event and matching receipt resolve the earlier UNKNOWN state in the recorded simulator history.
  Uncertainty: The terminal state resolves recorded uncertainty, but does not prove how many submissions occurred or establish real-exchange finality.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:4, receipt:8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509

### eventual_venue_rejection

The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.


### delay_before_submission

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The RESERVED-to-SUBMITTED interval is 120 seconds, above the 60-second inspection threshold. This interval precedes the recorded venue outcome.
  Interpretation: The recorded history shows RESERVED followed by SUBMITTED after the inspection threshold, indicating a dispatch delay in local state progression without proving venue latency.
  Uncertainty: Recorded timing shows dispatch progression, not venue processing or latency.
  Evidence: 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:0, 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:1
- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest recorded UNKNOWN state has no locally observed receipt, so the missing receipt does not establish rejection or prove that no external execution occurred.
  Uncertainty: The bounded local query found no receipt; external execution or receipt visibility cannot be ruled out.
  Evidence: 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:2, receipt_lookup:36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819

### old_receipt_state_still_unknown

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds before the first UNKNOWN event. Latest recorded state=UNKNOWN.
  Interpretation: The receipt was recorded before the first UNKNOWN observation, indicating a possible acknowledgement or commit gap while the trade remained uncertain.
  Uncertainty: Recorded receipt time precedes the first UNKNOWN event; this does not establish physical execution or arrival timing.
  Evidence: receipt:fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:2
- Observed: 2 UNKNOWN events span 117 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Two distinct UNKNOWN events preserve uncertainty across the recorded history without resolving the executed receipt against the current trade state.
  Uncertainty: These are repeated recorded observations, not evidence of repeated venue submissions.
  Evidence: fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:2, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:3
- Observed: The matching simulated receipt predates the latest UNKNOWN event by 118 seconds. This is a recorded receipt-to-state gap, not the current age of the trade.
  Interpretation: The matching EXECUTED receipt and latest UNKNOWN event are separated in recorded time, so the receipt should be inspected alongside the unresolved terminal state.
  Uncertainty: This is a recorded receipt-to-state interval, not proof of current age or execution latency.
  Evidence: receipt:fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:3

### rapid_terminal_counterexample

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching receipt was recorded after the uncertain observation, so local evidence became available later without proving when execution occurred.
  Uncertainty: The receipt was recorded after the first UNKNOWN event; recorded time does not establish physical execution or visibility latency.
  Evidence: 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:2, receipt:0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b
- Observed: The first UNKNOWN was followed 2 seconds later by recorded EXECUTED settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.
  Interpretation: The later committed EXECUTED event, supported by the matching receipt, resolves the earlier UNKNOWN state in the recorded local history.
  Uncertainty: The terminal event resolves the recorded uncertainty, but does not establish how many submissions occurred or prove external settlement finality.
  Evidence: 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:2, 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:3, receipt:0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b

### timestamp_sequence_disagreement

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: Event sequence 1 precedes 2, but its timestamp is later. Sequence order and wall-clock order disagree; elapsed-time attribution is unreliable.
  Interpretation: The SUBMITTED event has a later recorded offset than the subsequent UNKNOWN event, so sequence and recorded time disagree. This limits temporal conclusions about the transition.
  Uncertainty: Recorded timestamps reverse between these sequenced events; physical ordering and cause are not established.
  Evidence: 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:1, 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:2
- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest recorded state is UNKNOWN and no matching receipt was observed. Receipt absence leaves the execution outcome unresolved rather than proving failure.
  Uncertainty: The bounded local query found no receipt; this does not establish rejection, non-execution, or absence of an external request.
  Evidence: 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:2, receipt_lookup:50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2

### sparse_unknown_counterexample

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.


### missing_event_control

Cannot establish a specific cause from this evidence: the event history is missing, truncated or changed during collection. No execution or capacity change is authorized.


### foreign_receipt_control

Cannot establish a specific cause from this evidence: the receipt conflicts with the trade identity or amount. No execution or capacity change is authorized.
