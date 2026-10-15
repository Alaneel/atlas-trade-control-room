# Atlas Trade Control Room

**Explore a pre-trade decision workflow: instrument checks, counterparty capacity, and exception requests.**

Atlas pairs a Java / Spring Boot API with a Streamlit control room. It is a learning prototype for making a decision path visible, from an instrument query to a simulated execution result and stored trade history.

[Architecture notes](ARCHITECTURE.md) · [Backend services](src/main/java/com/gic/service) · [Dashboard](python_src/trading_dashboard.py) · [Contributing](CONTRIBUTING.md)

## Start with the control room

The dashboard defaults to **Demo data** and runs without the backend. Its sample records let you inspect the interface; they are not observations from a trading system.

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r python_src/requirements.txt
streamlit run python_src/trading_dashboard.py
```

Open [localhost:8501](http://localhost:8501).

For the API, MongoDB, Redis, and dashboard together:

```bash
docker compose up --build
```

Switch off **Demo data** in the sidebar to call the backend. The API is exposed at `localhost:8080`; its [Swagger UI](http://localhost:8080/swagger-ui.html) describes the request schemas.

## Follow a request through the code

1. [InstrumentService](src/main/java/com/gic/service/InstrumentService.java) queries MongoDB for matching instrument attributes, including department. Spring caching wraps the lookup.
2. [LimitService](src/main/java/com/gic/service/LimitService.java) reads a counterparty / instrument-group limit, checks available capacity, subtracts the amount, and saves the record.
3. [TradeService](src/main/java/com/gic/service/TradeService.java) simulates execution. On simulated success it records an executed trade; on failure it releases the reservation and records a failed trade.
4. [ApprovalService](src/main/java/com/gic/service/ApprovalService.java) stores an explicit exception request with `PENDING` status. A completed reviewer decision workflow is not implemented here.

Instrument or capacity checks that reject a trade return a decision before execution; the current service saves history for execution outcomes, not every rejection.

| Method | Route | Purpose |
|---|---|---|
| `POST` | `/api/trader/verify-instrument` | Query a permitted instrument combination |
| `GET` | `/api/trader/instruments` | List instrument IDs |
| `GET` | `/api/trader/limit/{counterparty}/{instrumentGroup}` | Read available capacity |
| `POST` | `/api/trader/trade` | Evaluate a request and simulate execution |
| `GET` | `/api/trader/trades` | Read stored trade history |
| `POST` | `/api/trader/approval-request` | Create a pending exception request |

## What this prototype does not establish

Limit consumption is **read → modify → save**, not an atomic conditional database update. `@Transactional` annotations alone do not establish concurrent reservation correctness; the implementation needs an explicit concurrency design and verification before multiple requests can safely compete for capacity.

“External execution” is `Thread.sleep(500)` followed by `Math.random() < 0.9`. The delay and probability are simulation parameters, not latency measurements or reliability results. Amounts use `Double`; this is not an exact-money ledger.

A Bloom-filter utility and Redis circuit-breaker service exist in the repository, but the inspected instrument/trade decision path does not invoke them. Their presence is not evidence of an integrated fast path or tested failover. Authentication and role-based approval controls should not be inferred from the interface. This project is not a live trading or production risk platform.

## Work on the backend

Backend development uses Java 11+, Maven 3.6+, MongoDB, and Redis. The Compose file selects MongoDB 7 and Redis 7.

```bash
mvn clean test
mvn spring-boot:run
```

To start the dashboard in API mode:

```bash
DEMO_MODE=false BACKEND_API_URL=http://localhost:8080 \
  streamlit run python_src/trading_dashboard.py
```

See [.env.example](.env.example), [docker-compose.yml](docker-compose.yml), and [architecture notes](ARCHITECTURE.md) for configuration. Existing architecture documentation also describes design intentions; use the linked service implementations to distinguish those intentions from the current path.

Released under the [MIT license](LICENSE).
