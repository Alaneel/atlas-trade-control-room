# Atlas GPT-5.6 Luna comparison protocol (2026-10-21, before dispatch)

This run diagnoses the retained v3 result, then replaces only the generator model/configuration. It does not change the frozen cases, references, v3 generation instructions, proof predicates, atomic fallback, rule variants, v2 reviewer rubric, blinding seed or reversed positions.

- Generator: `gpt-5.6-luna`, standard mode, low reasoning effort.
- Reviewer: fixed `gpt-4.1-mini-2025-04-14`, the same reviewer as the prior v3 round. Source labels remain hidden. Reviewer self-preference in the older self-graded round and same-provider correlated errors remain limitations.
- Both: official Responses endpoint, default service tier, 8-second overall deadline, 1,400 output tokens (including reasoning), no tools, no retries, no hidden warmups. Luna uses explicit caching without breakpoints to avoid cache writes for changing single-turn evidence.
- Dataset SHA256: `05190bfdd3e6d9519af9b5cb14a6f9d7208a5e4c1353b68f7883d94bb58d10bf`. Ten eligible cases plus two abstention controls. References remain withheld from the generator.
- At most 10 generation and 20 reviewer requests. A $0.10 per-run pre-dispatch reservation cap includes both. Conservative input ceiling: the transport's 32,768-byte serialized request bound plus 1,024 framing tokens. Output bound: 1,400 tokens. Reserve at the highest applicable input/cache-write rate; settle using reported ordinary/cached/write/output token counts. Unknown usage/model/tier retains its reservation and stops further dispatch. No background calls or account-level spend guarantee.
- Account/model lookup is read only and has no generation token charge. It does not guarantee a later Responses request succeeds.
- Record all failures, empty accepted plans, fallback outputs, two orientations and usage; never rerun a failed case invisibly. A negative run is valid data.
- Primary operational comparison: fallback rate and end-to-end generation P50/P95. Quality: expected explicit relation recall plus two-position stable model preference against basic and strong rules, goal coverage, calibration, citation/unsupported/unsafe flags. Explicit relation objects are not independent semantic truth. No human review or production inference.
- Baseline: retained v3 results (80% fallback, 1/17 expected explicit relations, $0.042706 including reviews). Historical artifacts stay immutable.
- AI remains read only. It receives typed synthetic evidence, cannot modify limits or invoke trade/recovery operations. Existing deterministic cross-event rules stay the product default pending evidence of improvement.

Official compatibility and pricing sources checked on 2026-10-21:

- [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
- [Pricing](https://developers.openai.com/api/docs/pricing): per million tokens, ordinary input $0.20, cached input $0.02, cache writes $0.25, output $1.20.
- [Reasoning](https://developers.openai.com/api/docs/guides/reasoning#reasoning-mode)
- [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching#how-caching-works-gpt-5-6-and-later)

Bundled model-migration guidance was used only for workflow; model and price facts came from these current official pages. `.env.local` is preserved, excluded from source archives, and never printed.
