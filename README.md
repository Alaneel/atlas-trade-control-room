# Atlas Trade Control Room

**Exact decimal capacity, safe request retries, and a control room you can try without infrastructure.**

Atlas pairs a Java / Spring Boot API with a Streamlit control room. It makes a pre-trade decision path visible, from instrument validation through a capacity reservation to a simulated execution result and stored history. It is a reference project with explicit, tested boundaries.

[Architecture notes](ARCHITECTURE.md) · [Backend services](src/main/java/com/gic/service) · [Dashboard](python_src/trading_dashboard.py) · [Contributing](CONTRIBUTING.md)

## Start with the control room

The dashboard defaults to **Demo data** and runs without the backend. Its sample records let you inspect the interface; they are not observations from a trading system.

```bash
git clone https://github.com/Alaneel/atlas-trade-control-room.git
cd atlas-trade-control-room
python3 -m venv .venv
source .venv/bin/activate
pip install -r python_src/requirements.txt
streamlit run python_src/trading_dashboard.py
```

Open [localhost:8501](http://localhost:8501).

Try **New trade** with `NORTHSTAR / EQUITY / AAPL`: submitting consumes session capacity, the limit explorer shows the new balance, and an order over the remaining balance appears in the exception queue. **Reset demo** restores the initial data. Rerendering a page does not submit the order again.

For the API, MongoDB, Redis, and dashboard together:

```bash
docker compose up --build
```

Switch off **Demo data** in the sidebar to call the backend. The API is exposed at `localhost:8080`; its [Swagger UI](http://localhost:8080/swagger-ui.html) describes the request schemas.

## Follow a request through the code

1. [InstrumentService](src/main/java/com/gic/service/InstrumentService.java) queries MongoDB for matching instrument attributes, including department. Spring caching wraps the lookup.
2. [IdempotentTradeService](src/main/java/com/gic/service/IdempotentTradeService.java) admits one owner for an `Idempotency-Key` using a unique MongoDB operation record. Identical retries read that record instead of invoking the trade path again.
3. [LimitService](src/main/java/com/gic/service/LimitService.java) reserves decimal capacity with a MongoDB conditional `findAndModify`: the balance check and `$inc` debit occur in the same document operation. Reads identify one canonical record; they do not authorize the debit.
4. [TradeService](src/main/java/com/gic/service/TradeService.java) calls a separate [simulated executor](src/main/java/com/gic/service/SimulatedTradeExecutor.java). On success it records an executed trade; on definite failure it atomically releases the reservation and records a failed trade. Instrument and capacity rejections are recorded too. The history ID matches the operation ID for reconciliation.
5. [ApprovalService](src/main/java/com/gic/service/ApprovalService.java) stores an explicit exception request with `PENDING` status. A completed reviewer decision workflow is not implemented here.

The HTTP boundary rejects missing attributes and nonfinite, zero, or negative amounts before trading runs. Malformed JSON returns `400`, and a missing limit returns `404`. Seed data uses stable IDs and insert-only writes, so restarting does not duplicate records or replenish spent capacity.

## What is verified

| Scenario | Expected behavior |
|---|---|
| 32 requests each reserve 75 from a balance of 1,000 | Exactly 13 succeed; 25 remains |
| 64 concurrent releases of 10 | All credits are retained |
| Repeated or concurrent seed initialization | One limit pool; consumed capacity preserved |
| Invalid nested instrument or amount | `400` before trade execution |
| Simulator cancellation | Failure returned; cancellation signal preserved |
| Streamlit rerender and reset | No duplicate debit; reset restores isolated session state |
| Container startup | Real Compose health checks and API/UI smoke requests in CI |
| 32 concurrent HTTP requests with the same key | One execution, one debit, one history record |
| Same key with `1.0` then `1.00` | Same cached result; no second debit |
| Same key with changed trade details | `409` before another execution |
| Executor or history storage fails after admission | Retry stays pending; it never re-executes automatically |
| `0.1` and `0.2` reservations | Exact decimal arithmetic and BSON Decimal128 storage |

## Decimal amounts and retry contract

Amounts use Java `BigDecimal`, MongoDB `Decimal128`, and Python `Decimal`. The supported range is up to 26 integral and 8 fractional digits. Extra trailing zeros are ignored; excess significant precision is rejected without rounding. JSON numbers and decimal strings are accepted. Clients should send strings such as `"0.10"` to avoid rounding in their own JSON libraries. Amounts are expressed in the counterparty limit currency; no FX conversion or currency-specific settlement rounding is performed.

Send an `Idempotency-Key` header for every intended trade, then preserve both the key and request for retries. A key contains 1–128 ASCII letters, digits, dots, underscores, or hyphens; a random UUID is a good choice.

This sample matches the bundled instrument and limit fixtures. Run the same command again to replay its decision:

```bash
curl -sS http://localhost:8080/api/trader/trade \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: atlas-first-trade' \
  -d '{"counterparty":"Abbey National Treasury Services PLC","amount":"0.10",
       "instrumentVerificationRequest":{"instrumentGroup":"Bonds",
       "instrument":"0% Convertible Bonds","department":"RE",
       "country":"ASIA PACIFIC REGION","exchange":"Colombo",
       "tradeCurrency":"CHF","settlementCurrency":"MXN"}}'
```

| Response | Meaning |
|---|---|
| `200`, `Idempotency-Replayed: false` | First attempt has a stored final decision |
| `200`, `Idempotency-Replayed: true` | Original final decision returned; no new execution |
| `202`, body status `IN_PROGRESS` | Original attempt is pending or requires reconciliation; keep the same key |
| `409`, `IDEMPOTENCY_CONFLICT` | The key was used with different trade details |
| `400` | Invalid request or key |

`X-Trade-Operation-Id` correlates the operation record and trade history. It is a hash, not a replacement for the original idempotency key. Omitting the key preserves the original independent-submission behavior: repeated calls can execute again.

Trade text must be valid Unicode. Malformed surrogate sequences are rejected with `400 INVALID_TRADE_TEXT` before an operation is admitted, rather than being replaced during fingerprint encoding.

The dashboard keeps a frozen request and key across timeouts and `202` replies. **Retry same trade** resends that saved request; editing and submitting fields or choosing **Start a new trade intent** creates a different intent. This saved state lasts only for the current Streamlit session.

Legacy integer, long, and double balances are converted on access using a value-and-type compare-and-set. Doubles use their shortest round-trip decimal spelling. A value such as `0.30000000000000004` is rejected for explicit reconciliation instead of being silently rounded. Back up an existing database before migrating; already-stored historical floating-point errors cannot be reconstructed.

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/trader/verify-instrument` | Query a permitted instrument combination |
| `GET` | `/api/trader/instruments` | List instrument IDs |
| `GET` | `/api/trader/limit/{counterparty}/{instrumentGroup}` | Read available capacity |
| `POST` | `/api/trader/trade` | Evaluate a request and simulate execution |
| `GET` | `/api/trader/trades` | Read stored trade history |
| `POST` | `/api/trader/approval-request` | Create a pending exception request |

## What this prototype does not establish

Atomicity covers each limit document and the unique operation admission, not an entire trade lifecycle. The same retained key prevents a second trade invocation, but capacity, execution, history, and final-response writes are not one multi-document transaction. A crash or unknown outcome can leave the operation pending and capacity held. There is no recovery worker: `202` does not promise a background retry. Reconciliation is manual, and blindly deleting an operation or retrying with a new key can duplicate execution. Direct low-level compensation is not idempotent.

The executor uses `Thread.sleep(500)` followed by `Math.random() < 0.9`. The delay and probability are simulation parameters, not latency measurements or reliability results. Decimal amounts do not establish a complete money ledger or currency policy. Demo metrics are calculated from session records; API metrics are calculated from returned history. Charts use approximate floats for display, while requests, tables, and balances retain decimal precision.

A Bloom-filter utility and Redis circuit-breaker service exist in the repository, but the inspected instrument/trade decision path does not invoke them. Their presence is not evidence of an integrated fast path or tested failover. Authentication and role-based approval controls should not be inferred from the interface. This project is not a live trading or production risk platform.

## Work on the backend

Backend development uses Java 11+, Maven 3.6+, MongoDB, and Redis. The Compose file selects MongoDB 7 and Redis 7.

```bash
mvn clean test
mvn spring-boot:run

# Demo state and real Streamlit interaction tests
python3 -m unittest discover -s python_src -p 'test_*.py' -v
```

To start the dashboard in API mode:

```bash
DEMO_MODE=false BACKEND_API_URL=http://localhost:8080 \
  streamlit run python_src/trading_dashboard.py
```

See [.env.example](.env.example), [docker-compose.yml](docker-compose.yml), and [architecture notes](ARCHITECTURE.md) for configuration. `MONGODB_URI`, `REDIS_HOST`, and `REDIS_PORT` are honored in the development and production profiles. Set `APP_SEED_DATA_ENABLED=false` to disable fixture loading against an existing database. Logs go to stdout by default; `LOGGING_FILE_NAME` enables a file at a writable path.

The next milestones are automated reconciliation, currency policies, authenticated approval actions, and migration to a supported Spring Boot release.

Released under the [MIT license](LICENSE).
