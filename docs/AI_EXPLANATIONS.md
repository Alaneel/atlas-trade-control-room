# Read-only AI explanation layer

Atlas explains recorded rejection and execution states using versioned rules and immutable evidence. The application renders authoritative decision facts and required checks. V2 additionally lets the optional model connect events and write labelled hypotheses with exact citations. The program verifies the cited temporal/state observations; model prose is not semantically certified. Approvals, capacity changes and trading actions are outside its capability.

The deterministic transaction code continues to choose every business state. See [cross-event reasoning, proof checks and three-way comparison](CROSS_EVENT_EXPLANATIONS.md) for the current contract and the [GPT-5.6 Luna follow-up](LUNA_EVALUATION.md) for diagnosed failures and current measurements. Historical artifacts retain their original behavior and measurements.

## Run without credentials

```sh
mvn test -Dtest=ExplanationEvaluationTest
python3 scripts/explanation-demo.py --offline
```

The offline runner evaluates the Java implementation against 26 frozen synthetic snapshots and prints examples. It has no Docker, database or model dependency. `target/atlas-explanation-evaluation.json` separates rule output, scripted valid planner output, adversarial plan rejection and pending real-model quality.

For the connected application, open **New trade**, enter a trade reference and select **Explain rejection or anomaly**. Rules are the default. The optional AI checkbox requests model ordering; unavailable or invalid output is labelled as a rule fallback. The control only calls the explanation GET route. It does not call reconciliation or execution.

## Configure actual model ordering

Set these locally, never in Git:

```sh
export ATLAS_AI_EXPLANATIONS_ENABLED=true
export OPENAI_API_KEY='<your API key>'
export OPENAI_MODEL='<an available Structured Outputs model>'
```

For a repeatable local evaluation, put these three variables in a **private, Git-ignored `.env.local`** in the repository root instead. Create an API key in the [OpenAI dashboard](https://platform.openai.com/api-keys); never paste it into chat, commit it, or put it in a frontend variable. Restrict the file with `chmod 600 .env.local`. The evaluation wrapper parses only the three documented names, without sourcing or executing the file. An export in another terminal does not configure an already-running Codex process. Spring does not load this file automatically; for the connected app, export variables into the backend process or use Compose's `.env` configuration.

The initial measured baseline uses `OPENAI_MODEL=gpt-4.1-mini-2025-04-14`, a fixed snapshot with Responses and Structured Outputs support and no reasoning step ([model documentation](https://developers.openai.com/api/docs/models/gpt-4.1-mini)). This is an explicit evaluation choice, not a hidden application default. Select a model available to your own API project. Documented model existence does not establish account access.

Restart the backend after configuring it. No model name or price is silently selected. The implementation sends one bounded request to `https://api.openai.com/v1/responses`, uses `store: false` and strict `text.format` JSON Schema, and supplies no tools or conversation continuation. The API shape follows [official Structured Outputs documentation](https://developers.openai.com/api/docs/guides/structured-outputs).

Provider access is disabled by default. Each process permits at most two concurrent requests, immediately falls back when busy, caps v2 output at 1,400 tokens (v1: 512), input at 32 KiB and retained response at 64 KiB, and uses an 8-second overall deadline. Redirects and application retries are disabled. A stalled response body is also bounded. Requests explicitly select the standard `default` service tier. Returned token usage is recorded when available, including refusal/incomplete-response usage; missing usage is null, not zero cost. The public `model` field identifies the requested model. Evaluation additionally retains allowlisted resolved model, response/request IDs, service tier, cached input and reasoning token counts, without reasoning text or raw error bodies.

For separately opted-in actual-provider evaluation on synthetic cases:

```sh
python3 scripts/run-live-explanation-eval.py --check-config  # no network request; no secret output
python3 scripts/run-live-explanation-eval.py --limit 6
```

Each invocation archives `results.json`, `report.md`, `manifest.json` and a sanitized runner log in a fresh `target/model-evaluations/<UTC>-<random>/` directory. It does not overwrite failed runs. The manifest binds source hashes, dataset, prompt/rule version, model, request limits and actual returned usage. It uses cached Maven dependencies (`-o`) and needs Java 17 and Maven. Missing dependencies should be installed by a normal offline-test build first. It never downloads dependencies as part of a paid run.

The lower-level test entry point remains available when variables are already in its process environment:

```sh
mvn test -Dtest=LiveExplanationModelEvaluationTest \
  -Datlas.explanation.live=true -Datlas.explanation.limit=6
```

This makes at most six sequential model requests by default (the limit is bounded to 1–12). It fails clearly when explicitly requested without a key/model. The lower-level test writes `target/atlas-explanation-model-evaluation.json`. It also runs 14 invalid-evidence abstention controls behind a planner that cannot send a paid request. Normal CI never opts into paid calls.

Reports include end-to-end and provider P50/P95/maximum latency (nearest-rank percentiles), initiated requests, fallback rate/reasons, raw returned ID plan, rule baseline and final presentation for each case. There are no hidden warmup calls. Six observations are a demonstration, not a production latency distribution or load benchmark.

Cost is a USD **usage-based estimate**, not an invoice or a claim about remaining account credits. The versioned [pricing table](../evals/explanations/pricing.json) records the [official standard rates](https://developers.openai.com/api/docs/pricing), verified 2026-10-20: GPT-4.1 Mini input $0.40, cached input $0.10, output $1.60 per million tokens. Compute `(input-cached)*input_rate + cached*cached_rate + output*output_rate`, then divide by one million. Output tokens already include reasoning tokens. Unknown model/tier prices and missing usage leave total cost null; known usage is reported separately. Missing cache detail produces a range. A timed-out request can still be billable.

Quality is reported separately for raw plan acceptance, final application output (including fallback) and accepted-model plans only. Mechanical checks cover expected headline, primary reason placed first, required uncertainty/checks, exact reviewed text and valid citations. Invalid-evidence abstention controls make no real-model call. All cases are synthetic and development-visible. These checks do not establish independent semantic accuracy or improved user understanding; human usefulness/semantic scores remain null. An output identical to the rule baseline is counted explicitly, so successful model integration is not presented as demonstrated added value.

## API and provenance

`GET /api/trader/trades/{id}/explanation?mode=rules|rules_plus|ai` returns an explanation with `Cache-Control: no-store`. The API default stays `rules` for compatibility; the control-room selector defaults to the useful zero-model `rules_plus` cross-event mode. AI remains experimental after the negative [real comparison](CROSS_EVENT_EVALUATION.md). A missing trade returns 404, invalid identity/mode returns 400, and POST returns 405.

The response includes:

- trade reference, recorded state/version and snapshot hash;
- rule and prompt versions;
- mode: `rules`, `rules_plus`, `model_ordered`, `model_attributed`, `rules_fallback` or clearly labelled fixture modes;
- provider outcome and initiated model-request count;
- grounded findings with rule IDs and evidence references;
- read-only inspection checks, explicit uncertainty/abstention and scope limitations.
- optional `attributions`, separating program-checked observations from model-authored interpretations and uncertainties.

An explanation is an observation of the returned version, not an authorization or an eternally current trading decision. After a provider request the service rereads evidence. If the snapshot changes, it discards the old model plan and renders a fresh deterministic explanation. It makes no second provider request.

## Historical evidence rather than guessed causes

New trades and events record `ruleVersion`, a deterministic `decisionCode` and, for insufficient-capacity rejection, `availableAtDecision` captured inside the actual reservation transaction. Causes distinguish unapproved eligibility, insufficient capacity, absence of an exact-currency limit, pending submission, uncertain result and definitive venue outcomes.

The explanation reader does not query today's ledger to reconstruct yesterday's rejection. Old records without typed provenance remain readable by the existing trading API, but explanation abstains rather than interpreting their free-text messages. Unsupported rule versions, incomplete/truncated histories, foreign event IDs, sequence/state disagreement, contradictory amounts and mismatching receipts also cause abstention before any model request.

A matching success receipt while the trade is UNKNOWN does **not** make the explanation mark it EXECUTED. It describes both observations and the held-reservation policy. Only the existing deterministic reconciliation/worker path can settle that state.

Rules are reviewed explanatory descriptions of deterministic code, not an editable model-driven policy engine. Changes to decision semantics require a new rule version and compatible catalog. The initial catalog is [rules-v1.json](../src/main/resources/explanations/rules-v1.json).

## Capability and data boundary

The explanation service depends only on `ExplanationReader`, the rule engine and a planner. Its reader has a `load` method, and the Mongo adapter calls read operations only. It has no `TradeService`, `TradeWorker`, reservation, approval or gateway capability. No model output is dispatched as a function or HTTP operation.

The model receives curated findings, rules, an allowlisted typed timeline and references. Raw event messages, instrument/counterparty names, keys, fingerprints and lease tokens are not model inputs. The plan must match the snapshot, preserve the reason and required checks, use valid IDs once and contain no extra fields. Function calls, invented IDs, omitted mandatory items, stale hashes and action fields are rejected. V2 attribution references and temporal/state predicates are separately checked. Interpretation text remains a labelled hypothesis, not a verified business fact. Failure returns the rules explanation.

This is an application capability boundary, not a separate-process or database-role security boundary: the local backend retains its existing database identity and unauthenticated development API. The adapter contract and tests do not prove general prompt-injection immunity or production tenant isolation.

## Real local HTTP demonstration

After building the JAR, start a dedicated MongoDB replica set published only on loopback port 28027 with the ownership label checked by the script. For example:

```sh
docker run --rm -d --name atlas-ai-demo \
  --label io.github.alaneel.atlas.task=atlas-ai-demo \
  --tmpfs /data/db --tmpfs /data/configdb \
  -p 127.0.0.1:28027:27017 mongo:7.0 \
  --replSet atlas-rs --bind_ip_all
for attempt in $(seq 1 40); do
  if docker exec atlas-ai-demo mongosh --quiet --eval \
    'try {rs.status()} catch(e) {if(e.code===94) rs.initiate({_id:"atlas-rs",members:[{_id:0,host:"localhost:27017"}]}); else throw e;} if(!db.hello().isWritablePrimary) quit(1);'; then
    break
  fi
  sleep 1
done
mvn package -DskipTests
python3 scripts/explanation-demo.py \
  --mongo-container atlas-ai-demo --owner-value atlas-ai-demo
# After inspecting results, remove only this explicitly created demo container:
docker rm -f atlas-ai-demo
```

The script creates a UUID-named fixture database and starts/stops only its own loopback JVMs. It demonstrates capacity rejection, ineligible instrument, recorded execution, UNKNOWN with success receipt, and venue rejection. Before/after full business-document hashes verify that rule explanation, disabled-provider fallback and invalid POST do not write to limits, trades, events, receipts or instruments. Model calls are disabled explicitly for this demonstration. It retains `target/atlas-explanation-demo.json` and leaves its generated database for the caller's dedicated-container cleanup.

See [executed evaluation and limits](AI_EXPLANATION_VALIDATION.md) for actual results, rather than treating configured tests as executed evidence.
