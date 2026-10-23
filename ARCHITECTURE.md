> The reliable execution milestone supersedes the original trade/limit mutation path described below. See [RELIABLE_EXECUTION.md](docs/RELIABLE_EXECUTION.md) for the current transaction, idempotency and recovery contract. This file retains prototype design history, not proof that every component is integrated.

# Architecture Documentation

## System Architecture

### Overview

Atlas Trade Control Room follows a service-oriented architecture with clear separation of concerns.

### Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                        Frontend Layer                        │
│  ┌────────────────────────────────────────────────────┐     │
│  │            Streamlit Dashboard (Python)             │     │
│  └────────────────────────────────────────────────────┘     │
└──────────────────────────┬──────────────────────────────────┘
                           │ REST API
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
│  ┌────────────────────────────────────────────────────┐     │
│  │              Spring Boot Backend (Java)             │     │
│  │                                                      │     │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────┐ │     │
│  │  │ Controllers  │  │   Services   │  │  Models  │ │     │
│  │  └──────────────┘  └──────────────┘  └──────────┘ │     │
│  │                                                      │     │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────┐ │     │
│  │  │   DTOs       │  │  Exceptions  │  │   Utils  │ │     │
│  │  └──────────────┘  └──────────────┘  └──────────┘ │     │
│  └────────────────────────────────────────────────────┘     │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                       Data Layer                             │
│  ┌──────────────────┐           ┌──────────────────┐        │
│  │    MongoDB       │           │      Redis       │        │
│  │  (Primary Store) │           │    (Cache)       │        │
│  └──────────────────┘           └──────────────────┘        │
└─────────────────────────────────────────────────────────────┘
```

### Design Patterns

#### 1. Repository Pattern
- Abstract data access logic
- Interfaces: `LimitRepository`, `TradeHistoryRepository`, `InstrumentRepository`

#### 2. Service Layer Pattern
- Business logic separated from controllers
- Services: `LimitService`, `TradeService`, `InstrumentService`

#### 3. Circuit Breaker Pattern
- Resilience4j for fault tolerance
- Applied to Redis operations

#### 4. Caching Strategy
- L1: Caffeine (in-memory, JVM-local)
- L2: Redis (distributed)

#### 5. Bloom Filter
- Fast instrument existence checks
- Reduces database queries

### Data Flow

#### Trade Execution Flow

```
User Request
    ↓
Streamlit UI
    ↓
REST API (TraderController)
    ↓
Service Layer (TradeService)
    ↓
┌─────────────────────────┐
│ 1. Verify Instrument    │ → Bloom Filter → MongoDB
│ 2. Check Limit          │ → Redis Cache → MongoDB
│ 3. Execute Trade        │ → MongoDB
│ 4. Update Limit         │ → Redis + MongoDB
└─────────────────────────┘
    ↓
Response to UI
```

#### Caching Strategy

```
Request for Limit
    ↓
Check Caffeine Cache
    ↓ (miss)
Check Redis Cache
    ↓ (miss)
Query MongoDB
    ↓
Store in Redis
    ↓
Store in Caffeine
    ↓
Return to Client
```

### Security Architecture

1. **CORS Configuration**: Controlled cross-origin requests
2. **Input Validation**: Spring Validation annotations
3. **Error Handling**: Global exception handler
4. **Security Headers**: XSS protection, frame options

### Monitoring & Observability

1. **Health Checks**: Spring Actuator endpoints
2. **Metrics**: Micrometer + Prometheus
3. **Logging**: SLF4J with structured logging
4. **Distributed Tracing**: (Future: OpenTelemetry)

### Scalability Considerations

#### Horizontal Scaling
- Stateless backend services
- Redis for distributed caching
- MongoDB replica sets

#### Vertical Scaling
- JVM heap tuning
- Connection pooling
- Thread pool optimization

### Performance Optimizations

1. **Bloom Filter**: O(1) instrument verification
2. **Multi-level Caching**: Reduced database load
3. **Connection Pooling**: MongoDB and Redis
4. **Async Processing**: (Future: @Async for notifications)

### Reliability Features

1. **Circuit Breaker**: Prevents cascade failures
2. **Health Checks**: Early problem detection
3. **Retry Logic**: Resilience4j retry mechanism
4. **Graceful Shutdown**: Spring Boot lifecycle

### Technology Choices

#### Why MongoDB?
- Flexible schema for trading instruments
- Document model fits domain entities
- Horizontal scalability

#### Why Redis?
- High-performance caching
- Distributed cache support
- Rich data structures

#### Why Streamlit?
- Rapid UI development
- Python data science ecosystem
- Real-time updates

#### Why Spring Boot?
- Production-ready features
- Strong ecosystem
- Excellent monitoring support

### Future Enhancements

1. **Event-Driven Architecture**: Kafka for event streaming
2. **CQRS Pattern**: Separate read/write models
3. **API Gateway**: Kong or Spring Cloud Gateway
4. **Service Mesh**: Istio for advanced traffic management
5. **Database Sharding**: MongoDB sharding for massive scale
