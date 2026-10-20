# Executed explanation validation — 20 October 2026

The explanation layer was implemented and exercised on Java 17.0.14 / macOS arm64 with a dedicated MongoDB 7.0 replica-set container. No model API credential was configured. **Actual OpenAI model calls: zero; independent model quality: unmeasured.**

## Complete regression

The final `mvn verify` invocation discovered 86 tests: **84 passed, zero failures/errors, two skipped**. The skipped cases were the optional prior service benchmark and explicitly opted-in real-model evaluation. Real Mongo transaction and failpoint prerequisites were configured, so those checks were executed rather than skipped.

The additions include 11 loopback provider-transport tests, five explanation-service tests, four API contract tests, seven real Mongo explanation tests and one frozen-case evaluation runner. Existing monetary, lease, crash-boundary simulation, seed and transaction-failure tests also passed. [Per-case results](examples/ai-explanations/test-summary.json).

Explanation-package line coverage was 348 / 355 (98.0%); its 50% JaCoCo floor is enforced alongside the reliability package. Coverage does not measure model accuracy or application isolation by itself.

## Frozen offline cases and adversarial plans

[Cases](../evals/explanations/cases.json) were frozen before any real model use. The [retained evaluation](examples/ai-explanations/atlas-explanation-evaluation.json) contains rule output and valid scripted planner output for every case, with source fixture hash, rule version and prompt version.

- 26 curated synthetic snapshots: 12 with sufficient typed evidence and 14 expected abstentions.
- All 26 matched the predeclared headline/abstention expectations.
- 129 scripted invalid plans were rejected. They included stale snapshot hashes, invented finding/action IDs, extra execution fields, omitted mandatory uncertainty/check items, duplicate IDs, wrong headlines, code fences, trailing JSON and duplicate JSON keys.
- Existing citations remained bound to the allowlisted evidence/rule references.
- Missing history, old rule metadata, cross-trade events, conflicting receipts and unsupported rule versions abstained before contacting the provider.

These are rule/provenance/plan-enforcement results on known synthetic cases. Scripted valid output is labelled `fixture_ordered`, with zero real-model requests. The results do not establish LLM semantic accuracy, useful prioritization, broad distribution coverage or universal prompt-injection resistance. Human usefulness and actual-model semantic measurements remain unmeasured.

## Provider transport, without an external model

Loopback HTTP fixtures exercised the actual Java Responses adapter: strict JSON Schema, `store: false`, no tools, reasoning-plus-message envelopes, token-usage parsing, refusal, tool-call rejection, HTTP errors, oversize bodies, missing configuration and invalid test endpoints.

Both a stalled response before headers and a stalled body after headers reached the bounded failure path. Two in-flight provider requests occupied the configured slots; a third immediately returned busy without another provider call. Raw error text and credentials were not returned to callers. These fixtures verify protocol handling and limits, not real provider compatibility, availability or model quality.

## Real Mongo and real HTTP non-mutation evidence

The seven Mongo explanation tests compared complete business-document hashes across explanation calls. They covered historical-balance evidence versus a later changed balance, UNKNOWN with an already successful receipt, adversarial provider action fields, raw event-message injection, legacy provenance, unknown future decision codes and versioned venue rejection. Limits, trades, audit events, simulated receipts and instruments were unchanged by explanation.

The packaged-JVM [HTTP demo](examples/ai-explanations/atlas-explanation-demo.json) exercised five real business scenarios:

| Scenario | Observed explanation |
|---|---|
| Capacity rejection | Requested 150.00 USD versus 100.00 captured available capacity |
| Eligibility rejection | Recorded instrument/department combination was not approved |
| Executed simulation | Matching execution receipt and committed EXECUTED state |
| UNKNOWN with successful receipt | Explicit uncertainty and held reservation; no automatic settlement |
| Venue rejection | Matching rejection receipt and recorded deterministic release |

For every scenario, rules GET, AI-mode disabled-provider fallback and an invalid POST were followed by the same full business-document hash. All five non-mutation checks passed; POST returned 405. The demo explicitly disabled model access, recorded `rules_fallback`/`disabled`, and made zero real model calls. Owned child JVMs stopped; generated fixture databases were left for the caller's dedicated-container cleanup.

The artifact retains its executed JAR/script hashes and reports a dirty working tree based on the previous commit rather than assigning a future commit to the experiment. [Final source manifest](examples/ai-explanations/source-manifest.json) identifies implementation and fixture bytes separately. The executed demo JAR hash matched the final locally verified JAR.

## Reproduction and limits

[Setup and commands](AI_EXPLANATIONS.md) cover offline evaluation, optional actual-provider evaluation, API configuration and a repeatable real HTTP demonstration. Maven verification, Python compilation, shell syntax, Compose parsing and diff whitespace checks passed. Browser interaction with the revised Streamlit controls and Docker image builds were not part of this local acceptance.

No production API key was available, so the external-model evaluation remains pending. The implementation is constrained model-assisted organization of rule-rendered claims, not unrestricted prose generation; useful ordering still requires real-model and human assessment. Read-only capability tests are application-level evidence, not separate-process isolation or database-role enforcement. Existing production identity and framework-modernization limitations remain.
