# Reliable execution milestone

Atlas now commits exact-currency capacity, a durable trade and its status event in one MongoDB transaction. The previous read/modify/save reservation and random execution path are removed from the trade endpoint.

This milestone uses Java 17 and the repository's existing Spring Boot 2.6 framework. It is a local development simulator. Framework/dependency upgrades, authenticated desk identities and real venue integration remain separate work.

## State and accounting

`availableLimit + reservedLimit + usedLimit = totalLimit`, with all balances nonnegative exact cents stored as BSON Decimal128. A limit is identified by `(counterparty, instrumentGroup, currency)`.

- A confirmed, valid request reserves capacity and inserts `RESERVED` plus its first event together. Insufficient capacity or an ineligible instrument records a terminal `REJECTED` decision without reserving.
- A worker transaction claims a bounded lease and advances `RESERVED` to `SUBMITTED` before contacting the simulated venue.
- A definitive execution moves reserved capacity to used. A definitive venue rejection returns reserved capacity to available. The terminal trade and event commit in the same transaction.
- Any uncertain external result becomes `UNKNOWN` and keeps the reservation. A lost response is not a reason to free funds.
- The worker always queries an existing execution receipt before submitting. Recovery can retry the same stable execution identity because this simulator persists one receipt per identity. A real adapter must provide equivalent idempotency and lookup contracts.
- Lease tokens fence stale workers. Duplicate delivery does not authorize a second settlement. Completed trades are immutable under replay.

`reliable_trades` doubles as the durable pending-work queue: there is no separate broker to synchronize for this milestone. Recovery scans at most 25 due records per polling cycle. Status events contain a unique per-trade sequence and support audit inspection; they are not a complete event-sourced ledger or a state reconstruction format.

Transaction callbacks retry only aborted transient transactions or duplicate-key races, with bounded backoff. An unknown commit repeats commit on the same session/transaction; it never blindly replays the business callback. Exhaustion returns an error and retains the client's idempotency identity.

## Local setup and data compatibility

MongoDB must be an initialized replica set. Startup rejects standalone MongoDB and pre-existing ledgers with missing fields, binary-double/nondecimal amounts, fractional cents, negative balances or inconsistent totals. The included single-node replica set demonstrates transactions; it provides no independent-node failover evidence.

Use a fresh database named `atlas_reliable`. Compose uses a new `mongodb_reliable_data` volume, leaving the previous prototype volume untouched. Do not point this version at old data and infer reserved/used balances from `availableLimit`; a real migration needs a reconciled opening ledger and an audited import. No automatic migration or deletion is provided.

```sh
docker compose up --build
```

Ports publish on loopback. The control room defaults to the connected simulator in Compose; the API and dashboard remain available on ports 8080 and 8501 unless overridden. The simulator has no external exchange access. Existing instrument CSV rows are imported with insert-only semantics; restarting does not duplicate records or reset account balances. `ATLAS_SEED_ENABLED=false` disables fixture import, not ledger validation.

The explicit connected fixture is `NORTHSTAR / EQUITY / USD`, initial capacity `100.00`, with instrument `AAPL / GLOBAL_MARKETS / US / NASDAQ / USD / USD`. Historical CSV limits are separate scopes. Demo trades permanently consume the fixture's available capacity until that disposable database is intentionally replaced.

For host development against Compose MongoDB:

```sh
export MONGODB_URI='mongodb://127.0.0.1:27017/atlas_reliable?replicaSet=atlas-rs&directConnection=true'
mvn spring-boot:run
```

Environment configuration now has one URI path; the conflicting hardcoded `.properties` files are removed. Initializer seeding is intended for local fixtures. Approval-request creation remains the prototype workflow; an approved exception cannot bypass the exact-currency reservation path.

## API

`POST /api/trader/trade` requires an `Idempotency-Key` of 1–128 ASCII letters, digits or `._:-`. The local demo uses a global key namespace; production multi-user operation requires authenticated, tenant-scoped keys and authorization.

```json
{
  "instrumentVerificationRequest": {
    "instrumentGroup": "EQUITY", "instrument": "AAPL",
    "settlementCurrency": "USD", "tradeCurrency": "USD",
    "country": "US", "exchange": "NASDAQ", "department": "GLOBAL_MARKETS"
  },
  "counterparty": "NORTHSTAR", "amount": "10.00", "confirmed": true
}
```

Amounts must be 0.01–999999999.99 with at most two fractional digits. Invalid JSON, missing fields, a missing key or absent confirmation returns 400. The same canonical request and key retrieves the same trade; a different payload with that key returns 409. Monetary spellings such as `10` and `10.00` share a fingerprint after canonicalization. A lost HTTP response must be retried with the original key and body.

The result is `{ "status": "EXECUTED", "message": "...", "tradeId": "..." }`. Terminal outcomes return HTTP 200; pending or uncertain outcomes return 202. Business rejection is recorded as `REJECTED`, distinct from request validation errors.

| Route | Behavior |
|---|---|
| `GET /api/trader/trades/{id}` | Current durable trade |
| `GET /api/trader/trades/{id}/events` | Ordered status audit history |
| `POST /api/trader/trades/{id}/reconcile` | Query receipt and settle known result; never submits a new execution |
| `GET /api/trader/limit/{counterparty}/{group}?currency=USD` | Exact-currency ledger; 404 for missing account |
| `GET /api/trader/trades` | Latest 200 reliable trades; previous `trade_history` records are not migrated |

The API returns a stable hash of the key as its trade reference. Recovery fields and request fingerprints are not exposed as JSON. A caller with a live submission lease may still return 202 on manual reconciliation; poll until the lease expires or the active worker finishes.

## Fault demonstrations and measurement

Configure `ATLAS_EXECUTION_SCENARIO` before starting the backend:

| Scenario | Result |
|---|---|
| `SUCCESS` | Durable simulated execution receipt |
| `REJECT` | Durable simulated rejection receipt |
| `TIMEOUT_AFTER_SUCCESS` | Receipt exists but first submit loses acknowledgement |
| `TIMEOUT_BEFORE_SUBMIT` | No receipt; reservation remains held for later stable-key retry |

These modes are process configuration, never arbitrary request parameters. To observe UNKNOWN before recovery, set `ATLAS_RECOVERY_ENABLED=false`. Manual reconciliation still queries receipts. Background recovery is enabled by default and may settle a known receipt before the UI refreshes.

Run the HTTP demonstration only against a fresh connected fixture:

```sh
python3 scripts/reliability-demo.py --help
```

For independent Mongo acceptance, create a disposable loopback replica set and set `ATLAS_MONGO_URI`. Tests generate unique `atlas_acceptance_<uuid>` databases and drop only those databases. Omitted integration prerequisites produce explicit skips, not integration passes.

```sh
export ATLAS_MONGO_URI='mongodb://127.0.0.1:28027/?replicaSet=atlas-rs&directConnection=true'
./scripts/run-acceptance.sh
./scripts/run-acceptance.sh --benchmark -Datlas.benchmark.requests=500 -Datlas.benchmark.threads=8
```

The benchmark compares one hot account with 64 accounts, retains individual samples and errors, and checks ledger conservation. It measures a closed-loop durable Java service path, including actual database transactions and simulated venue receipt persistence. It excludes HTTP, a real venue and admission-queue delay. Publish environment, failure counts and raw results with throughput and p50/p95/p99. A single local run is not a production capacity or tail-latency guarantee.

The commit-uncertainty failpoint test additionally requires a dedicated MongoDB instance started with `--setParameter enableTestCommands=1` and `ATLAS_MONGO_FAILPOINTS=true`. Never enable test commands on a shared/production instance. The test resets its failpoint in `finally`; run it without other traffic against that instance.

## Remaining work

This milestone establishes a bounded reliable-execution baseline. Next changes can add measured retry/queue metrics, broader generated histories and fault boundaries, then framework/auth modernization. AI explanations should consume versioned rules and events through a read-only interface; they must not grant capacity or authorize execution. Full ledger reconstruction needs additional event data and independent replay validation before any event-sourcing claim.
