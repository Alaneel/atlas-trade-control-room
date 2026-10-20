# Explanation evaluation contract

`cases.json` is a curated, development-visible synthetic suite. Its expected headlines and abstentions are declared independently of a provider response, but the cases were used while developing the implementation. It is not a heldout or real-user dataset.

The offline runner compares rule output and scripted valid ID plans, then attacks the plan validator. It reports the 26-case rule/provenance agreement and rejected adversarial plans separately. Citation existence and exact ID enforcement are mechanical checks, not independent semantic judgements of a language model.

The opt-in real-provider runner starts with a stratified six-case set: capacity rejection, missing currency scope, instrument eligibility rejection, UNKNOWN without receipt, UNKNOWN with successful receipt, and venue rejection. Larger limits add the other eligible cases up to twelve total. Case IDs, failures, fallbacks, latency and returned token usage are retained. Invalid-evidence abstention is evaluated offline without sending those snapshots to a model.

Run `python3 scripts/run-live-explanation-eval.py --check-config`, then `python3 scripts/run-live-explanation-eval.py --limit 6` after configuring the Git-ignored, mode-0600 `.env.local`. Every invocation retains a separate report directory under `target/model-evaluations/`. The report includes the raw returned ID plan (not reasoning text), resolved model/response ID, frozen source/dataset hashes, cached-token-aware cost estimate, latency percentiles and fallback reasons. Refusal/incomplete-response usage remains billable in accounting; missing usage/unknown prices never become a zero total. The versioned `pricing.json` is a recorded rate assumption, not live billing data; verify and update it before a later evaluation date or a different model.

Quality tables distinguish the final application (including deterministic fallback) from accepted real-model plans. Both are mechanical checks against the reviewed rule output. Exact text/citation agreement cannot establish independent semantic accuracy. The number of presentations unchanged from rules is reported alongside plan acceptance; a successful provider request alone does not establish usefulness. Fourteen local abstention controls run without extra paid calls. The initial six-case round is too small for a production performance claim.

## Future human usefulness review

The model cannot author new factual statements in this version; it chooses and orders reviewed claims. Whether that improves a user's understanding is unmeasured. Compare rules and real-model-organized outputs with presentation source concealed, and separately retain the variant mapping and source hashes.

Suggested versioned rubric (`atlas-explanation-usefulness-v1`):

- Does the explanation clearly identify the recorded decision and uncertainty?
- Are the most useful findings placed first?
- Are the read-only checks understandable and relevant?
- Does a reader incorrectly infer approval, released capacity or confirmed execution?

Use independently declared reviewers, preserve disagreements and report coverage. Do not label an LLM-generated score as human review. Keep factual/provenance contract outcomes separate from usefulness ratings. An empty or pending review set has null scores, not perfect scores. No completed human review or real-model usefulness claim accompanies the current artifacts.
