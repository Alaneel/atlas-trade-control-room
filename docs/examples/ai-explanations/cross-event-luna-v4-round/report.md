# Atlas cross-event comparison

Model: `gpt-5.6-luna` · Dataset: `atlas-cross-event-cases-v1`

Completed: True · Cases: 10 + 2 abstention controls

| Operational metric | Result |
| --- | --- |
| Initiated requests (generation + review) | 30 |
| Generation fallback | 0/10 |
| Generation P50 / P95 / max | 4823 / 6508 / 6508 ms |
| Usage-based total cost USD | 0.039575 |
| Missing-usage / unpriced requests | 0 / 0 |

A missing total is unknown cost, not zero. No retries or hidden warmup calls. Paid reviewer requests are included in total cost.

## Mechanical checks

Program-checked AI relation objects: 20.
Rule summaries retained: 10/10.
Abstention without request: 2/2.

- Explicit expected relations, ai: 17/17.
- Explicit expected relations, strong_rules: 17/17.

Checked relation recall measures explicit proof objects, not prose usefulness or independent semantic accuracy. Original rules may express some relevant meaning without proof objects.

## Source-blinded model review

Valid reviews: 20/20.

| Variant | Goal coverage | Clarity (1–5) | Calibration (1–5) | Unsupported / citation / unsafe flags |
| --- | --- | --- | --- | --- |
| rules | 54/60 | 3.65 | 4.35 | 0 / 0 / 0 |
| strong_rules | 59/60 | 4.7 | 4.95 | 0 / 0 / 0 |
| ai | 60/60 | 4.85 | 4.95 | 0 / 0 / 0 |

| AI compared with | Stable wins | Ties | Stable losses | Order-sensitive/disagreement | Incomplete |
| --- | --- | --- | --- | --- | --- |
| original rules | 8 | 0 | 0 | 2 | 0 |
| stronger cross-event rules | 0 | 3 | 1 | 6 | 0 |

Generator and reviewer model IDs are recorded; source labels hidden, positions reversed. Each case is counted once for stable pair preference; rating counts include both presentations. Same-provider preference, correlated error and order effects remain (including self-preference when IDs match). Not human, heldout or production evidence.

## Cases

### receipt_before_uncertainty

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds before the first UNKNOWN event. Latest recorded state=UNKNOWN.
  Interpretation: The matching receipt is recorded before the UNKNOWN event, indicating a recorded acknowledgement or commit gap that warrants inspecting the existing settlement and event records; it does not establish execution or delivery timing.
  Uncertainty: Recorded timestamps do not prove when execution occurred or when the receipt became visible. The trade remains recorded as UNKNOWN.
  Evidence: receipt:05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51, 05060d618532279b0978a8ca61c6123233c08af021412df7f6f8f10efe739a51:2

### receipt_recorded_later

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: A matching EXECUTED simulated receipt was recorded 28 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching EXECUTED receipt was recorded after the UNKNOWN event, providing evidence to inspect while the trade’s recorded state remains UNKNOWN.
  Uncertainty: Recorded times do not establish when execution occurred, when the receipt became visible, or that the receipt changed the trade state.
  Evidence: e31127fe60e7c1127f71c767003470c97d139195e5eb63e17e807f06b5d16249:2, receipt:e31127fe60e7c1127f71c767003470c97d139195e5eb63e17e807f06b5d16249

### persistent_unknown_without_receipt

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest recorded UNKNOWN remains without a local receipt in the bounded query; this does not establish rejection or that no external execution occurred.
  Uncertainty: Receipt absence is limited to this snapshot and query; execution or receipt visibility outside it is unresolved.
  Evidence: 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:4, receipt_lookup:5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3
- Observed: 3 UNKNOWN events span 38 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Distinct UNKNOWN observations recur from the first to the last recorded UNKNOWN event, preserving uncertainty rather than proving repeated venue submissions.
  Uncertainty: The records show repeated observations only; they do not establish repeated physical submissions or a fault.
  Evidence: 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:2, 5813cba93fdc8e9353d7ec11d1c2157136d18ba3401128711190f64f8ce2e5d3:4

### eventual_execution

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.

- Observed: 2 UNKNOWN events span 28 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: The recorded history contains repeated UNKNOWN observations before the terminal outcome; this shows uncertainty was observed more than once, not that the venue received multiple submissions.
  Uncertainty: The events do not establish repeated physical submissions or the cause of the uncertainty.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:3
- Observed: A matching EXECUTED simulated receipt was recorded 27 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching EXECUTED receipt was recorded after the first UNKNOWN observation, so the receipt provides later recorded evidence while not proving when execution or receipt visibility occurred.
  Uncertainty: Recorded timestamps do not establish actual execution, network, or acknowledgement timing.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, receipt:8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509
- Observed: The first UNKNOWN was followed 29 seconds later by recorded EXECUTED settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.
  Interpretation: The later terminal EXECUTED event and matching receipt resolve the earlier recorded uncertainty within the local workflow; this supports recorded simulated execution without proving external settlement finality.
  Uncertainty: The receipt is supporting evidence, not a terminal trade event, and the record does not establish how the outcome became known.
  Evidence: 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:2, 8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509:4, receipt:8367c9ac061bff52da958ff88eee1a7afc12e2dc6c87535e78df248d9039e509

### eventual_venue_rejection

The local simulated venue returned a matching rejection receipt and the trade is committed as REJECTED. The deterministic workflow returned the reservation once.

- Observed: A matching REJECTED simulated receipt was recorded 17 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching rejected receipt was recorded after the first UNKNOWN observation. This establishes recorded ordering only; it does not establish when execution occurred or when the receipt became visible.
  Uncertainty: Recorded times do not prove execution, acknowledgement, or visibility latency.
  Evidence: 350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a:2, receipt:350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a
- Observed: 2 UNKNOWN events span 8 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Two distinct UNKNOWN events were recorded before the terminal rejection, showing repeated uncertain observations during the workflow.
  Uncertainty: This does not prove repeated venue submissions or a physical fault.
  Evidence: 350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a:2, 350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a:3
- Observed: The first UNKNOWN was followed 18 seconds later by recorded REJECTED settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.
  Interpretation: The later REJECTED terminal event, supported by the matching rejected receipt, resolves the earlier UNKNOWN state and records the completed recovery outcome.
  Uncertainty: The receipt and terminal event establish recorded outcome, not how many submissions occurred or when the venue processed them.
  Evidence: 350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a:2, 350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a:4, receipt:350f882b74df90a0c52a3a1cf36bccac6665f88edf34b1152824bacf739cf68a

### delay_before_submission

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The RESERVED-to-SUBMITTED interval is 120 seconds, above the 60-second inspection threshold. This interval precedes the recorded venue outcome.
  Interpretation: The recorded path from RESERVED to SUBMITTED spans the defined inspection threshold, highlighting a notable recorded dispatch interval without establishing venue latency or the cause of the delay.
  Uncertainty: This compares recorded timestamps only; it does not prove when submission reached the venue or why the interval occurred.
  Evidence: 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:0, 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:1
- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: No receipt was observed after the latest UNKNOWN event in the bounded query, so the recorded evidence does not resolve whether execution occurred or was rejected.
  Uncertainty: Receipt absence is limited to this snapshot and query; it does not prove that no external request executed or that a receipt could not exist elsewhere.
  Evidence: 36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819:2, receipt_lookup:36c214ce1971305a2e6b52dab86a97243ea843255e1a8c0fc25416d1e9536819

### old_receipt_state_still_unknown

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: 2 UNKNOWN events span 117 seconds in this history. Event count is not a count of venue submissions.
  Interpretation: Two distinct UNKNOWN observations were recorded in sequence, so uncertainty persisted across the status history without establishing repeated venue submissions.
  Uncertainty: The observations do not prove a physical fault, repeated submission, or execution outcome.
  Evidence: fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:2, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:3
- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds before the first UNKNOWN event. Latest recorded state=UNKNOWN.
  Interpretation: The matching receipt was recorded before the first UNKNOWN event, indicating a recorded receipt-to-status ordering gap; this does not establish when execution or acknowledgement occurred.
  Uncertainty: Recorded times are local timestamps and do not prove execution, delivery, or visibility timing.
  Evidence: receipt:fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:2
- Observed: The matching simulated receipt predates the latest UNKNOWN event by 118 seconds. This is a recorded receipt-to-state gap, not the current age of the trade.
  Interpretation: The matching receipt precedes the latest UNKNOWN event by the recorded timeline, while the trade remains UNKNOWN; settlement has not been reflected in the latest recorded state.
  Uncertainty: The recorded gap does not prove a current delay, failure, or definitive execution state.
  Evidence: receipt:fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825, fd6dff2a40c5ed00ef043657ab98ab04f3aa9c0c4c726b5f6d8c869751c6a825:3

### rapid_terminal_counterexample

The local simulated venue returned a matching execution receipt and the trade is committed as EXECUTED. Recorded capacity usage is settled by the deterministic workflow.

- Observed: A matching EXECUTED simulated receipt was recorded 1 seconds after the first UNKNOWN event. Receipt creation time is not a network-arrival measurement.
  Interpretation: The matching receipt was recorded after the first UNKNOWN observation, so the recorded evidence later supported execution; this does not establish when execution or receipt visibility occurred.
  Uncertainty: Recorded times describe local observation, not proven venue execution or acknowledgement delivery.
  Evidence: 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:2, receipt:0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b
- Observed: The first UNKNOWN was followed 2 seconds later by recorded EXECUTED settlement with a matching simulated receipt. The earlier UNKNOWN is historical; no new execution is authorized.
  Interpretation: The later terminal EXECUTED event, together with the matching receipt, resolved the earlier UNKNOWN state in the recorded workflow without proving how many submissions occurred.
  Uncertainty: The receipt and terminal event establish recorded simulated execution, not real-exchange or settlement finality.
  Evidence: 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:2, 0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b:3, receipt:0f6b58f3b82339a4f861082a4656daed1af8ce84713fb3e9133b51e444c11c3b

### timestamp_sequence_disagreement

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: Event sequence 1 precedes 2, but its timestamp is later. Sequence order and wall-clock order disagree; elapsed-time attribution is unreliable.
  Interpretation: E2 has a higher sequence but an earlier recorded time than E1, so timestamp order is unreliable; this does not establish a dispatch delay or physical event order.
  Uncertainty: This is a recorded-order discrepancy only; it does not prove clock skew, network behavior, or when either record became visible.
  Evidence: 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:1, 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:2
- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: No receipt was observed after the latest UNKNOWN event in the bounded query; absence of a local receipt proves neither rejection nor absence of external execution.
  Uncertainty: The query establishes only that no receipt was observed in this snapshot; acknowledgement or execution may be recorded elsewhere or later.
  Evidence: 50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2:2, receipt_lookup:50e55a8513d13043e0b591dbe199b0e0a8ec4e2f87e9727e54860130fc4e36f2

### sparse_unknown_counterexample

The execution outcome is uncertain. A timeout proves neither rejection nor execution; reserved capacity must remain held until deterministic reconciliation.

- Observed: The latest recorded trade state is UNKNOWN and no local receipt was observed. Neither venue rejection nor absence of execution is established.
  Interpretation: The latest recorded state is UNKNOWN and the bounded query found no receipt; this does not establish rejection or absence of external execution.
  Uncertainty: Receipt absence is limited to this snapshot and query; execution, acknowledgement, and later visibility remain unresolved.
  Evidence: 31a22893bba3c99a758eb880d2bc95030cfb2425636afc2ecf6600aa6953f6a0:2, receipt_lookup:31a22893bba3c99a758eb880d2bc95030cfb2425636afc2ecf6600aa6953f6a0

### missing_event_control

Cannot establish a specific cause from this evidence: the event history is missing, truncated or changed during collection. No execution or capacity change is authorized.


### foreign_receipt_control

Cannot establish a specific cause from this evidence: the receipt conflicts with the trade identity or amount. No execution or capacity change is authorized.
