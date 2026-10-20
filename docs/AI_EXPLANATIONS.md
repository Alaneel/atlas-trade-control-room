# Read-only AI explanation layer

Atlas explains recorded rejection and execution states using versioned rules and immutable evidence. The model can choose and order existing finding/check IDs. The application renders the corresponding reviewed factual text. Model-authored prose, new claims, approvals, capacity changes and trading actions are not part of the output contract.

This is intentionally constrained model-assisted presentation. It does not claim an autonomous financial adviser or unrestricted natural-language diagnosis. The deterministic transaction code continues to choose every business state.

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

Restart the backend after configuring it. No model name or price is silently selected. The implementation sends one bounded request to `https://api.openai.com/v1/responses`, uses `store: false` and strict `text.format` JSON Schema, and supplies no tools or conversation continuation. The API shape follows [official Structured Outputs documentation](https://developers.openai.com/api/docs/guides/structured-outputs).

Provider access is disabled by default. Each process permits at most two concurrent requests, immediately falls back when busy, caps output at 512 tokens, caps input at 32 KiB and retained response bytes at 64 KiB, and uses an 8-second overall response deadline. Redirects and application retries are disabled. A stalled response body is also bounded. Returned token usage is recorded when available; missing usage is null, not a fabricated zero cost. The `model` field identifies the requested model; pin an available model snapshot if comparing runs.

For separately opted-in actual-provider evaluation on synthetic cases:

```sh
mvn test -Dtest=LiveExplanationModelEvaluationTest \
  -Datlas.explanation.live=true -Datlas.explanation.limit=6
```

This makes at most six sequential model requests by default (the limit is bounded to 1–12). It fails clearly when explicitly requested without a key/model. Results are written to `target/atlas-explanation-model-evaluation.json`, including fallbacks, latency and returned usage. The runner tests plan acceptance, not independent semantic truth or usefulness. Human usefulness/semantic scores remain null until separately reviewed. Normal CI never opts into paid calls.

## API and provenance

`GET /api/trader/trades/{id}/explanation?mode=rules|ai` returns an explanation with `Cache-Control: no-store`. The default mode is `rules`. A missing trade returns 404, invalid identity/mode returns 400, and POST to the explanation path returns 405.

The response includes:

- trade reference, recorded state/version and snapshot hash;
- rule and prompt versions;
- mode: `rules`, `model_ordered`, `rules_fallback` or a clearly labelled test-only `fixture_ordered`;
- provider outcome and initiated model-request count;
- grounded findings with rule IDs and evidence references;
- read-only inspection checks, explicit uncertainty/abstention and scope limitations.

An explanation is an observation of the returned version, not an authorization or an eternally current trading decision. After a provider request the service rereads evidence. If the snapshot changes, it discards the old model plan and renders a fresh deterministic explanation. It makes no second provider request.

## Historical evidence rather than guessed causes

New trades and events record `ruleVersion`, a deterministic `decisionCode` and, for insufficient-capacity rejection, `availableAtDecision` captured inside the actual reservation transaction. Causes distinguish unapproved eligibility, insufficient capacity, absence of an exact-currency limit, pending submission, uncertain result and definitive venue outcomes.

The explanation reader does not query today's ledger to reconstruct yesterday's rejection. Old records without typed provenance remain readable by the existing trading API, but explanation abstains rather than interpreting their free-text messages. Unsupported rule versions, incomplete/truncated histories, foreign event IDs, sequence/state disagreement, contradictory amounts and mismatching receipts also cause abstention before any model request.

A matching success receipt while the trade is UNKNOWN does **not** make the explanation mark it EXECUTED. It describes both observations and the held-reservation policy. Only the existing deterministic reconciliation/worker path can settle that state.

Rules are reviewed explanatory descriptions of deterministic code, not an editable model-driven policy engine. Changes to decision semantics require a new rule version and compatible catalog. The initial catalog is [rules-v1.json](../src/main/resources/explanations/rules-v1.json).

## Capability and data boundary

The explanation service depends only on `ExplanationReader`, the rule engine and a planner. Its reader has a `load` method, and the Mongo adapter calls read operations only. It has no `TradeService`, `TradeWorker`, reservation, approval or gateway capability. No model output is dispatched as a function or HTTP operation.

The model receives curated findings, rule text and allowlisted references. Raw event messages, instrument/counterparty names, idempotency keys, request fingerprints and lease tokens are not model inputs. This removes free-text message instructions from that context. The returned plan must match the snapshot, preserve the primary reason and all mandatory uncertainty/check items, use existing IDs exactly once, and contain no extra properties. Function-call output, invented IDs, omitted mandatory items, stale hashes and action fields are rejected. Failure returns the already-computed rules explanation.

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
