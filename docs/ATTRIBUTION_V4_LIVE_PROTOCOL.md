# Attribution v4: predeclared live follow-up

Declared on 2026-10-23 (Asia/Singapore), before observing any v4 provider output. Machine UTC timestamps are retained verbatim in run artifacts; the client calendar date is used here.

This is a single bounded follow-up to the retained Luna v3 round, not a new model search. Generator: `gpt-5.6-luna`, standard/default service tier, low reasoning, explicit cache mode without breakpoints, 8-second timeout and 1,400 output tokens including reasoning. Reviewer stays `gpt-4.1-mini-2025-04-14`, prompt `atlas-cross-event-review-v2`, two reversed presentations per case. At most 10 generation + 20 review requests, no retries, warmups or paid probes. Pre-dispatch reservation cap: $0.10 including reviewers. Missing usage retains its reservation and stops subsequent dispatch; estimates are not invoices.

Frozen development dataset: `cross-event-cases-v1`, SHA-256 `05190bfdd3e6d9519af9b5cb14a6f9d7208a5e4c1353b68f7883d94bb58d10bf`; 10 eligible cases, 2 no-request abstention controls, 17 reference relations. The same original and strong rule baselines remain comparators. Reference goals are withheld from generation. Every fallback and empty plan remains in the denominator.

Changes since live v3 are explicit: attribution-only prompt `atlas-explanation-attribution-v4`, trusted `ATTRIBUTIONS_V4` reply binding, server-owned rule facts and checks, sorted-map request serialization, and verifier v2 timestamp precision/overflow handling. Thus this is a protocol migration comparison, not an isolated causal estimate of a prompt or model effect. Historical artifacts remain immutable. The earlier offline 9/10 acceptance and 16/17 recall were counterfactual projections of old replies; they are not v4 live measurements.

Report initiated requests, known token cost and missing usage, generation P50/P95, whole-plan fallback and empty-plan counts, exact proof-object relation recall, controls, all raw returned JSON and all reversed-position reviews. Do not equate source-valid references with semantic correctness. Count a pair preference as stable only if both presentations agree; report disagreements separately. No live result is a reason for an undisclosed rerun.

Prepare a separate source-blinded human review pack after the run, with local annotations and a separate source mapping. Human results stay absent until a person supplies annotations; no simulated scores. This is development-visible, synthetic evidence, not held-out or production validation. Keep `rules_plus` as the default unless evidence establishes useful improvement beyond that strong baseline. AI remains a read-only explanation layer with no limit mutation or trade execution capabilities.

Pricing rechecked against [official API pricing](https://developers.openai.com/api/docs/pricing) and the [Luna model page](https://developers.openai.com/api/docs/models/gpt-5.6-luna): Luna input/cached/write/output $0.20/$0.02/$0.25/$1.20 per million; fixed reviewer input/cached/output $0.40/$0.10/$1.60. No rate changes from the previous round.

Run once:

```sh
python3 scripts/run-cross-event-eval.py --model gpt-5.6-luna --reviewer-model gpt-4.1-mini-2025-04-14 --limit 10 --max-usd 0.10
```
