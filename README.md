# Atlas Trade Control Room

**Atomic capacity reservations, visible trade decisions, and a control room you can try without infrastructure.**

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
2. [LimitService](src/main/java/com/gic/service/LimitService.java) reserves capacity with a MongoDB conditional `findAndModify`: the balance check and `$inc` debit occur in the same document operation. Reads identify one canonical record; they do not authorize the debit.
3. [TradeService](src/main/java/com/gic/service/TradeService.java) calls a separate [simulated executor](src/main/java/com/gic/service/SimulatedTradeExecutor.java). On success it records an executed trade; on definite failure it atomically releases the reservation and records a failed trade. Instrument and capacity rejections are recorded too.
4. [ApprovalService](src/main/java/com/gic/service/ApprovalService.java) stores an explicit exception request with `PENDING` status. A completed reviewer decision workflow is not implemented here.

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

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/trader/verify-instrument` | Query a permitted instrument combination |
| `GET` | `/api/trader/instruments` | List instrument IDs |
| `GET` | `/api/trader/limit/{counterparty}/{instrumentGroup}` | Read available capacity |
| `POST` | `/api/trader/trade` | Evaluate a request and simulate execution |
| `GET` | `/api/trader/trades` | Read stored trade history |
| `POST` | `/api/trader/approval-request` | Create a pending exception request |

## What this prototype does not establish

Atomicity covers each limit document, not an entire trade lifecycle. Reservations do not yet have durable IDs or idempotency keys. Replaying a trade can debit again; replaying compensation can credit again. If execution throws with an unknown outcome, the reservation remains held for reconciliation. Capacity and history writes are not one multi-document transaction.

The executor uses `Thread.sleep(500)` followed by `Math.random() < 0.9`. The delay and probability are simulation parameters, not latency measurements or reliability results. Amounts still use `Double`; an exact-money ledger would require decimal storage and rounding rules. Demo metrics are calculated from session records; API metrics are calculated from returned history. No live latency or SLA measurements are claimed.

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

The next milestones are decimal money, durable reservation IDs and reconciliation, authenticated approval actions, and migration to a supported Spring Boot release.

Released under the [MIT license](LICENSE).
