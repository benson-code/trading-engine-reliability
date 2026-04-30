# Payment API QA Framework

Full-cycle Binance QA portfolio — payment backend automation, a live BTC trading engine simulator with MySQL persistence, and a real-time Next.js trading dashboard.

![CI](https://github.com/benson-code/payment-api-qa-framework/actions/workflows/ci.yml/badge.svg)

---

## Repository Structure

```
payment-api-qa-framework/          ← Payment API test suite (Java 17, JUnit 5)
trading-engine-simulator/          ← BTC trading engine + REST/WebSocket API (Java 17)
trading-engine-ui/                 ← Real-time trading dashboard (Next.js 15)
```

---

## Module 1 — Payment API QA Framework

Full-cycle automated testing covering API testing, database verification, idempotency validation, and ACID compliance.

### Test Coverage

| Layer | Scenarios | Tools |
|---|---|---|
| Unit Tests | Validation logic, idempotency service logic | JUnit 5, Mockito |
| API Tests | Happy path, negative cases, async 202 flow | RestAssured, WireMock |
| DB Tests | Balance deduction, ACID rollback, idempotency constraint | JDBC, H2 |
| Integration | Full flow: API → async processing → DB verification | WireMock + H2 |

**Total: 11 test cases across 4 layers**

### Key Test Scenarios

**1. Idempotency — Duplicate Payment Prevention**
Simulates client retry: same `Idempotency-Key` header → API returns same `payment_id`, DB `UNIQUE` constraint blocks second insert → balance deducted exactly once.

**2. ACID — Atomicity & Rollback**
Payment exceeds balance → `deductBalance()` throws → explicit `conn.rollback()` → balance unchanged. Verifies DB-level atomicity.

**3. Async Payment Flow (HTTP 202)**
`POST /payments` → `202 Accepted` + `job_id` → `GET /payments/{jobId}/status` → `SUCCESS`. Correct pattern for async payment APIs (vs incorrect 201).

**4. Unit Validation**
`PaymentService` validates amount > 0, non-blank idempotency key, non-blank userId — before touching any repository or network.

### Project Structure

```
src/
├── main/java/com/binance/payment/
│   ├── model/
│   │   ├── PaymentRequest.java
│   │   └── PaymentResponse.java
│   └── service/
│       ├── PaymentRepository.java
│       └── PaymentService.java
└── test/java/com/binance/payment/
    ├── unit/PaymentServiceTest.java
    ├── api/
    │   ├── PaymentAPITest.java
    │   └── IdempotencyTest.java
    ├── db/BalanceVerificationTest.java
    ├── integration/PaymentFlowTest.java
    └── util/DatabaseUtil.java
```

### How to Run

```bash
cd payment-api-qa-framework   # from repo root
mvn test
mvn allure:report
open target/site/allure-maven-plugin/index.html
```

---

## Module 2 — Trading Engine Simulator

BTC/USDT trading engine demonstrating 4 LeetCode algorithm patterns with 55 automated tests, MySQL persistence, and live WebSocket streaming.

### LeetCode Patterns Implemented

| Pattern | Component | Algorithm |
|---|---|---|
| LC-217 / LC-347 | OrderBook | HashMap duplicate detection + frequency analysis |
| LC-146 | OrderCache | LRU Cache (LinkedHashMap) |
| LC-65 / LC-8 | AmountValidator | Decimal string validation |
| LC-1115 | TradingEngine | Thread alternation via Semaphore |

### Test Results

```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

| Suite | Tests | Description |
|---|---|---|
| Unit | 38 | OrderBook, OrderCache, AmountValidator, TradingEngine |
| API | 10 | RestAssured against live embedded server |
| Integration | 7 | End-to-end: all 4 patterns verified together |

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                Trading Engine Simulator                  │
│                                                         │
│  BUY Thread ──┐                                         │
│               ├──► OrderBook (HashMap) ──► REST API :8092│
│  SELL Thread ─┘         │                               │
│               Semaphore  │                              │
│               alternation│                              │
│                          ▼                              │
│                    OrderCache (LRU) ──► WebSocket :8093 │
│                          │                              │
│                    MySQL (async) ──► /api/v1/orders/history│
└─────────────────────────────────────────────────────────┘
```

### REST API

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/status` | Engine metrics, BUY/SELL counts, cache hit rate |
| GET | `/api/v1/orders` | All orders with pagination (`?limit=500`) |
| POST | `/api/v1/orders` | Inject order manually |
| GET | `/api/v1/orders/{id}` | Lookup by ID (LRU cache first) |
| GET | `/api/v1/orders/duplicates` | Duplicate analysis + frequency map |
| GET | `/api/v1/orders/history` | Persistent orders from MySQL |
| POST | `/api/v1/engine/start` | Start order generation |
| POST | `/api/v1/engine/stop` | Stop order generation |

### How to Run

```bash
cd trading-engine-simulator

# Build fat JAR
mvn package -q

# Start (requires MySQL on localhost:3306)
DB_PASSWORD=your_password java -jar target/trading-engine-simulator-1.0-SNAPSHOT.jar

# Run tests (no external DB needed)
mvn test
```

### MySQL Schema

```sql
CREATE TABLE orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(50)   NOT NULL,
    type         VARCHAR(10)   NOT NULL,
    price        DECIMAL(18,2) NOT NULL,
    amount       DECIMAL(18,8) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    thread_name  VARCHAR(50),
    timestamp    BIGINT        NOT NULL,
    is_duplicate TINYINT(1)    DEFAULT 0,
    created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_id  (order_id),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Bugs Fixed (QA Review)

| ID | Component | Issue | Fix |
|---|---|---|---|
| BUG-01 | TradingEngine | Race condition: `volatile boolean` not atomic | `AtomicBoolean.compareAndSet()` |
| BUG-02 | Tests | Port conflict with production server | `findFreePort()` via `ServerSocket(0)` |
| BUG-03 | DBOrderRepository | Async writer not drained before close | `shutdown()` + `awaitTermination(10s)` |
| BUG-04 | DBOrderRepository | Stale JDBC connection after 8h | `conn.isValid(2)` + auto-reconnect |
| BUG-05 | useTradingEngine.ts | WS onmessage silently dies on bad JSON | `try/catch` around `JSON.parse` |
| BUG-06 | TradingApiServer | GET /orders returns unbounded list | `?limit=` pagination (default 500, max 5000) |
| BUG-07 | TradingApiServer | POST body OOM via large payload | `readNBytes(65_536)` cap |
| BUG-08 | TradingEngine | Duplicate IDs cross-thread contamination | Step back by even multiples only |
| BUG-09 | useTradingEngine.ts | No reconnect on WS disconnect | Exponential backoff (1s→30s) |
| BUG-10 | useTradingEngine.ts | O(n²) duplicate detection | `useMemo` pre-computed `Set`, O(1) lookup |

---

## Module 3 — Trading Engine UI

Real-time Binance-styled trading dashboard connecting to Module 2 via WebSocket.

### Features

- Live candlestick chart (TradingView Lightweight Charts, 5-second buckets)
- Order book with duplicate highlighting
- Engine stats panel (BUY/SELL counts, cache hit rate, duplicates)
- Thread monitor (BUY/SELL thread activity)
- WebSocket auto-reconnect with exponential backoff

### How to Run

```bash
cd trading-engine-ui
npm install
npm run dev
# Open http://localhost:3000
```

Configure backend URLs in `.env.local`:
```env
NEXT_PUBLIC_API_URL=http://localhost:8092
NEXT_PUBLIC_WS_URL=ws://localhost:8093
```

### Tech Stack

| Tool | Purpose |
|---|---|
| Next.js 15 | React framework |
| TypeScript | Type safety |
| Tailwind CSS | Styling |
| TradingView Lightweight Charts | Candlestick chart |
| WebSocket | Real-time order stream |

---

## Tech Stack (All Modules)

| Tool | Purpose |
|---|---|
| Java 17 | Backend language |
| Maven | Build & dependency management |
| JUnit 5 | Test framework |
| Mockito | Mocking for unit tests |
| RestAssured | HTTP API assertions |
| WireMock | Mock HTTP server |
| H2 | In-memory database (MySQL mode) |
| MySQL 8 | Persistent order storage |
| Allure | Test report generation |
| GitHub Actions | CI/CD |
| Next.js 15 | Frontend framework |
| TypeScript | Frontend type safety |
| Tailwind CSS | UI styling |
