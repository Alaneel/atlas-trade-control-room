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
- Approval-request creation remains a prototype. AI explanations and a complete exception-review workflow are not implemented in this milestone.

[Contributing](CONTRIBUTING.md) · [Environment example](.env.example) · [MIT license](LICENSE)
