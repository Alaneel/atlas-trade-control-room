# Atlas Trade Control Room

**A Java trade workflow that keeps capacity correct through concurrent requests, duplicate submissions and uncertain execution outcomes.**

Java 17 · Spring Boot · MongoDB transactions · Decimal128 · Streamlit

Atlas checks instrument eligibility, reserves exact-currency capacity and records a durable execution intent. A timeout keeps the reservation until the outcome is known. Stable execution identities, leased recovery and an ordered audit trail make the failure path inspectable.

[Reliable execution design and API](docs/RELIABLE_EXECUTION.md) · [Validation and benchmark](docs/VALIDATION.md) · [Acceptance tests](src/test/java/com/gic/reliability/integration) · [Original architecture notes](ARCHITECTURE.md)

## Run the connected simulator

```sh
docker compose up --build
```

Open the control room at [localhost:8501](http://localhost:8501). The API runs at [localhost:8080](http://localhost:8080), with [Swagger](http://localhost:8080/swagger-ui.html). Compose initializes a single-node MongoDB replica set and uses a new `mongodb_reliable_data` volume, separate from the prototype's data volume.

The connected fixture has `NORTHSTAR / EQUITY / USD` capacity of **100.00**, with `AAPL / GLOBAL_MARKETS / US / NASDAQ`. Submit an amount such as **10.00** and explicitly confirm. Repeated demonstrations consume the fixture; restarting preserves the balance. External execution is a deterministic local simulator, not a live exchange.

For the browser-only sample interface without a backend:

```sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r python_src/requirements.txt
streamlit run python_src/trading_dashboard.py
```

The standalone dashboard defaults to **Demo data**. Sample activity is labelled; connected mode uses backend records and does not invent performance measurements.

## Explain rejection and anomalies

The read-only explanation layer joins versioned rules, the recorded decision and ordered events. It explains insufficient capacity using the balance captured inside that decision transaction. UNKNOWN outcomes remain unknown even when a matching receipt is present.

`GET /api/trader/trades/{id}/explanation` defaults to rules. Optional `?mode=ai` adds cited cross-event interpretations: receipt order, repeated UNKNOWN, eventual settlement and recorded delays. The server checks cited state/time observations while separately labelling model-authored hypotheses. Rule facts stay authoritative; invalid or unavailable output falls back explicitly. There is no AI approval, capacity, reconciliation or execution capability. Legacy/conflicting evidence abstains.

```sh
python3 scripts/explanation-demo.py --offline
```

[Configuration, API and capability boundary](docs/AI_EXPLANATIONS.md) · [Executed evaluation](docs/AI_EXPLANATION_VALIDATION.md) · [Frozen cases](evals/explanations/cases.json)

[Cross-event contract and three-way comparison](docs/CROSS_EVENT_EXPLANATIONS.md) covers the new generation contract, a stronger deterministic comparator, blinded reversed-position model review and a standalone read-only replay. It retains failed rounds and distinguishes provisional model ratings from human evidence.

The [initial two-round comparison](docs/CROSS_EVENT_EVALUATION.md) did not show stable AI gains. Its second round had 80% fallback; against stronger rules, AI had zero stable wins, two ties, seven losses and one disagreement. Both initial rounds cost an estimated $0.0940124 across 60 requests, including reviews.

The [GPT-5.6 Luna follow-up](docs/LUNA_EVALUATION.md) reproduced every failure and held cases, proof checks and reviewer fixed. Fallback fell to 20%, explicit reference-relation recall rose from 1/17 to 14/17, and generation cost fell to $0.0082502 ($0.0396046 with reviews). P50/P95 rose to 4.388/6.289 seconds. Strong rules still cover 17/17, and position-sensitive model reviews do not establish a stable AI advantage. The control room defaults to `rules_plus`; AI stays experimental and read only.

The [v4 follow-up](docs/ATTRIBUTION_V4.md) moves snapshot binding and every rule fact/check out of model output, retains atomic proof rejection, and replaces pair enumeration with linear candidate discovery for the default rule path. It adds 64 development boundary cases, overflow abstention and exact sub-millisecond rendering. Its migration replay is **not a new model benchmark**; no new provider requests were made.

The first [real-model round](docs/examples/ai-explanations/live-20261020/report.md) used six `gpt-4.1-mini-2025-04-14` requests: estimated cost **$0.0034696**, P50 **2.108 s**, P95 **3.746 s**, fallback **0/6**, and all required claims/checks and citations retained. All six presentations matched the rules baseline, so added usefulness is not established. Configure a private `.env.local`, then run `python3 scripts/run-live-explanation-eval.py --limit 6` to archive a separate round; ordinary CI stays offline.

## Inspect the failure path

| Concern | Implementation |
|---|---|
| Exact money and conserved capacity | [Money](src/main/java/com/gic/reliability/Money.java), [Limit](src/main/java/com/gic/model/Limit.java) |
| Atomic reservation and settlement | [ReliableTradeStore](src/main/java/com/gic/reliability/ReliableTradeStore.java) |
| Duplicate request detection | [TradeOrder](src/main/java/com/gic/reliability/TradeOrder.java), [TradeService](src/main/java/com/gic/service/TradeService.java) |
| Unknown outcomes and recovery | [TradeWorker](src/main/java/com/gic/reliability/TradeWorker.java) |
| Durable simulated execution identity | [MongoExecutionGateway](src/main/java/com/gic/reliability/MongoExecutionGateway.java) |
| Transient errors versus uncertain commit | [TransactionRunner](src/main/java/com/gic/reliability/TransactionRunner.java), [configuration](src/main/java/com/gic/reliability/ReliabilityConfiguration.java) |

The invariant is `available + reserved + used = total`, with nonnegative exact-cent balances. Reservation, trade and event commit together. A durable `RESERVED` trade is also pending work; no separate broker is required in this milestone.

`POST /api/trader/trade` now requires `Idempotency-Key` and `confirmed: true`. The same canonical payload/key retrieves the same trade; a different payload returns 409. Pending outcomes return 202 and a stable `tradeId`. Read the trade and its events with `GET /api/trader/trades/{id}` and `GET /api/trader/trades/{id}/events`. Manual `POST .../{id}/reconcile` queries an existing receipt and never submits a new execution. [Complete contract and request body](docs/RELIABLE_EXECUTION.md#api).

## Validate

Use Java 17 and Maven 3.6+. Unit and HTTP contract tests require no database; real transaction tests are explicitly skipped without `ATLAS_MONGO_URI`.

```sh
mvn clean test
# Against a disposable loopback replica set:
export ATLAS_MONGO_URI='mongodb://127.0.0.1:28027/?replicaSet=atlas-rs&directConnection=true'
./scripts/run-acceptance.sh
./scripts/run-acceptance.sh --benchmark -Datlas.benchmark.requests=500 -Datlas.benchmark.threads=8
```

Acceptance tests create uniquely named databases and remove only their own databases. They exercise contention, repeated keys, exact cents, rollback, stale workers, persisted receipts and recovery. The optional benchmark retains per-request samples for one hot account and 64 accounts. Its results cover the durable Java/Mongo service path, excluding HTTP and real venue latency. [Actual executed results and limits](docs/VALIDATION.md).

For the full HTTP demonstration against a fresh fixture:

```sh
python3 scripts/reliability-demo.py --help
```

## Boundaries

- This is an unauthenticated local simulator. Production desk identity, authorization and real venue integration are unfinished.
- Single-node replica-set tests demonstrate transaction behavior, not multi-node failover or power-loss durability.
- The repository retains its existing Spring Boot 2.6 dependency line; framework and dependency modernization remain separate work.
- Old binary-double ledgers require an explicit audited migration. Startup rejects incompatible records; no opening balances are invented.
- Audit events record status transitions. Full event-sourced ledger reconstruction is not implemented.
- Approval-request creation remains a prototype. A complete exception-review workflow is unfinished. Model explanations are read-only and require separately configured API access; offline contract results do not establish model usefulness.

[Contributing](CONTRIBUTING.md) · [Environment example](.env.example) · [MIT license](LICENSE)
