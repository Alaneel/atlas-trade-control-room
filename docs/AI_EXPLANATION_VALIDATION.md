# Executed explanation validation — 20 October 2026

The explanation layer was implemented and exercised on Java 17.0.14 / macOS arm64 with a dedicated MongoDB 7.0 replica-set container. The initial acceptance was offline. A separately authorized real-provider round subsequently made **six OpenAI requests** using a locally configured key. Independent semantic quality and human usefulness remain unmeasured.

## Real-provider round

Run `20261020T152854Z-058b06ef` used requested and resolved snapshot **`gpt-4.1-mini-2025-04-14`**, standard service tier, six sequential synthetic cases, 8-second deadlines, 512 output-token cap and no application retries or hidden warmups. The real provider received only verified findings, checks and evidence; the runner uses frozen snapshots and has no database or trading connection.

| Metric | Observed result |
| --- | --- |
| Initiated requests / accepted plans | 6 / 6 |
| Fallback rate | 0 / 6 = 0% |
| End-to-end latency P50 / P95 / maximum | 2,108 / 3,746 / 3,746 ms |
| Mean end-to-end latency | 2,350.3 ms |
| Reported input / cached input / output tokens | 6,942 / 0 / 433 |
| Usage-based total cost estimate | **USD 0.0034696** |
| Requests with missing usage / unknown prices | 0 / 0 |

Cost uses the [recorded standard pricing](../evals/explanations/pricing.json), checked against [official pricing](https://developers.openai.com/api/docs/pricing) on 2026-10-20: `(6942 × $0.40 + 433 × $1.60) / 1,000,000`. This is an estimate from returned token counts, not an invoice. Nearest-rank P95 equals the maximum for six observations; this is not a production latency or load claim.

The six cases were capacity rejection, missing exact-currency limit, instrument eligibility rejection, UNKNOWN without receipt, UNKNOWN with a successful receipt and venue rejection. Both final application output and the six accepted real-model plans retained the expected primary reason, every required finding/check and valid citations in **6/6 cases**. The primary reason was first in **6/6**. Both UNKNOWN cases preserved uncertainty (**2/2**). Fourteen additional invalid-evidence controls abstained before the planner, with **zero additional paid requests**.

**All six presentations were identical to the rule baseline.** The round establishes provider compatibility and successful constrained plan validation on these fixtures. It does not show that the model improved understanding, independently diagnosed causes, or added useful prioritization. Factual text is rule-rendered; these are mechanical contract checks on development-visible synthetic cases, not an independent semantic accuracy score. Human usefulness and independent semantic accuracy are explicitly null.

[Readable per-case report](examples/ai-explanations/live-20261020/report.md) · [Raw results with usage, response IDs and returned plans](examples/ai-explanations/live-20261020/results.json) · [Source/dataset manifest](examples/ai-explanations/live-20261020/manifest.json). The manifest records the previous commit plus dirty-state hashes and exact executed source bytes; it does not assign a later commit to the experiment.

## Complete regression

After adding live-run reporting and accounting, a new `mvn verify` with real MongoDB transactions/failpoints discovered **89 tests: 87 passed, zero failures/errors, two skipped**. The optional service benchmark and paid model test stay skipped in ordinary verification; the paid test passed separately in the six-request round above. Three new accounting tests cover cached-token prices, billable refusal usage, missing usage/cache details, unknown model prices and null quality rates for zero accepted plans. Updated explanation line coverage: **365 / 372 (98.1%)**. [Latest regression summary](examples/ai-explanations/live-20261020/regression-summary.json).

The initial acceptance `mvn verify` invocation discovered 86 tests: **84 passed, zero failures/errors, two skipped**. The skipped cases were the optional prior service benchmark and explicitly opted-in real-model evaluation. Real Mongo transaction and failpoint prerequisites were configured, so those checks were executed rather than skipped.

The additions include 11 loopback provider-transport tests, five explanation-service tests, four API contract tests, seven real Mongo explanation tests and one frozen-case evaluation runner. Existing monetary, lease, crash-boundary simulation, seed and transaction-failure tests also passed. [Per-case results](examples/ai-explanations/test-summary.json).

Initial explanation-package line coverage was 348 / 355 (98.0%); its 50% JaCoCo floor is enforced alongside the reliability package. Coverage does not measure model accuracy or application isolation by itself.

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

The real-model round above is complete. The implementation is constrained model-assisted organization of rule-rendered claims, not unrestricted prose generation; useful ordering still requires a broader blinded assessment. Read-only capability tests are application-level evidence, not separate-process isolation or database-role enforcement. Existing production identity and framework-modernization limitations remain.
