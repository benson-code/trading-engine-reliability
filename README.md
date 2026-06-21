# Binance QA Suite

**English** | [繁體中文](README.zh-TW.md)

Full-cycle Binance QA portfolio: a runnable Payment API with real transactional ACID + idempotency under concurrency, a live BTC trading engine simulator with MySQL persistence, and a real-time Next.js dashboard.

![CI](https://github.com/benson-code/binance-qa-suite/actions/workflows/ci.yml/badge.svg)

### Why this project exists

Across 10 years of QA on payment gateways, e-commerce platforms, and a Tier-1 bank's card-payment integration, the failures that hurt most were never on the happy path — they were **silent backend failures**: a debit that committed while its payment row didn't, a retry that double-charged under load, a settlement state that disagreed between two services. Catching those took Oracle SQL, JDBC, and Linux log forensics *after* the fact.

This repo turns that hard-won instinct into **executable proof at the DB layer**: the same ACID-rollback, exactly-once idempotency, and race-condition scenarios I chased in production are reproduced here as automated tests that fail loudly when the invariant breaks — so the bug is caught in CI, not in a reconciliation report.

### Highlights

- **Real service, real DB, real ACID** — `JdbcPaymentRepository.createPayment` runs the balance debit and the payment insert in **one transaction**; `UNIQUE(idempotency_key)` is the concurrency backstop. A retry that races and loses the constraint rolls back — **which undoes its debit** — so the account is debited exactly once regardless of how many retries arrive ([`JdbcPaymentRepositoryTest`](payment-api/src/test/java/com/binance/payment/db/JdbcPaymentRepositoryTest.java)).
- **Concurrency proven, not asserted** — 16 threads call `createPayment` with the same idempotency key; the test ([`ConcurrentIdempotencyTest`](payment-api/src/test/java/com/binance/payment/concurrency/ConcurrentIdempotencyTest.java)) asserts exactly-one debit and one `payment_id` on **both** repository implementations.
- **No WireMock theatre** — every API and integration test exercises the real `PaymentService` through an embedded HTTP server, not a mocked stand-in, so a green suite means the actual service behaves ([commit `668bfc4`](https://github.com/benson-code/binance-qa-suite/commit/668bfc4) shows the mock-to-real migration).
- **Payment-grade input & access control** — currency must match the account (`422`), amount precision is bounded to `DECIMAL(18,8)` (`400 INVALID_PRECISION`, no silent truncation), and the payment endpoints require an `X-API-Key` (constant-time compared) when configured ([`PaymentAuthTest`](payment-api/src/test/java/com/binance/payment/api/PaymentAuthTest.java)).
- **CI-enforced quality** — 98 tests in CI · admin-enforced branch protection on `main` · PR-only · two required checks must be green · rebase-merge preserves the P1/P2/P3 commit narrative.

---

## Repository Structure

```
binance-qa-suite/                  ← Monorepo root (Maven parent POM)
├── payment-api/                   ← Module 1: runnable Payment API + QA tests (Java 17, 43 tests)
├── trading-engine-simulator/      ← Module 2: BTC trading engine (Java 17, 55 tests in CI / 63 with MySQL)
└── trading-engine-ui/             ← Module 3: Real-time dashboard (Next.js 15)
```

**One command runs all 98 Java tests:**
```bash
mvn test   # runs payment-api + trading-engine-simulator in sequence
```

**DB validation (requires live MySQL):**
```bash
mvn test -pl trading-engine-simulator -Dgroups=db-validation
```

---

## Module 1 — Payment API QA Framework

Full-cycle automated testing covering API testing, database verification, idempotency validation, and ACID compliance.

### Test Coverage

| Layer | Scenarios | Tools |
|---|---|---|
| Unit Tests | Validation logic, idempotency service logic | JUnit 5, Mockito |
| API Tests | Happy path, negative cases, async 202 flow | RestAssured vs real `PaymentApiServer` |
| DB Tests | Real JDBC repo: ACID rollback, strict accounts, idempotency constraint | JDBC, H2 (MySQL mode) |
| Integration / E2E | Full flow + async settlement against the real service | RestAssured, embedded JDK HTTP server |
| Concurrency | N-thread idempotency race → exactly-once debit | ExecutorService, both repos |

**Total: 43 test cases** (16 unit/API/idempotency baseline + 5 real-service E2E + 6 JDBC ACID & negative-path + 3 field-length & HTTP-code accuracy + 4 currency-match + 4 amount-precision + 5 API-key auth)

> All API, integration and concurrency tests exercise the real
> `PaymentService` through an embedded HTTP server — no WireMock.
> `JdbcPaymentRepository` provides real transactional ACID with strict
> account semantics; `PaymentRepository` is the swap seam, toggled with
> `PAYMENT_REPO=jdbc` at runtime.

### Key Test Scenarios

**1. Idempotency — Duplicate Payment Prevention**
Simulates client retry: same `idempotency_key` → `PaymentService` short-circuits on `findByIdempotencyKey`, so `createPayment` (and its balance deduction) runs exactly once → API replays the same `payment_id` (`200`, not a second `202`).

**2. ACID — Atomicity & Rollback**
`JdbcPaymentRepository.createPayment` runs the debit + payment insert in one transaction. Insufficient balance or duplicate `idempotency_key` → `rollback()` undoes the debit → balance unchanged, no orphan payment row. Unknown account → rejected (404), nothing persisted. Verified at the DB layer (`JdbcPaymentRepositoryTest`, `BalanceVerificationTest`).

**3. Async Payment Flow (HTTP 202)**
`POST /payments` → `202 Accepted` + `job_id` → `GET /payments/{jobId}/status` → `SUCCESS`. Correct pattern for async payment APIs (vs incorrect 201).

**4. Unit Validation**
`PaymentService` validates amount > 0, non-blank idempotency key, non-blank userId — before touching any repository or network.

### Project Structure

```
payment-api/
├── pom.xml
└── src/
    ├── main/java/com/binance/payment/
    │   ├── Main.java                           ← standalone entry point (:8091)
    │   ├── api/PaymentApiServer.java           ← real embedded JDK HTTP server
    │   ├── model/
    │   │   ├── PaymentRequest.java
    │   │   └── PaymentResponse.java
    │   └── service/
    │       ├── PaymentRepository.java          (interface — the swap seam)
    │       ├── InMemoryPaymentRepository.java  ← runnable impl (P1)
    │       ├── JdbcPaymentRepository.java      ← real ACID impl (P3)
    │       └── PaymentService.java
    └── test/java/com/binance/payment/
        ├── unit/PaymentServiceTest.java
        ├── api/
        │   ├── PaymentAPITest.java
        │   ├── IdempotencyTest.java
        │   └── PaymentServiceE2ETest.java      ← E2E vs the real server
        ├── db/
        │   ├── BalanceVerificationTest.java
        │   └── JdbcPaymentRepositoryTest.java  ← strict accounts + ACID (P3)
        ├── concurrency/ConcurrentIdempotencyTest.java  ← N-thread race (P3)
        ├── integration/PaymentFlowTest.java
        └── util/DatabaseUtil.java
```

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    Payment API (:8091)                       │
│                                                              │
│  POST /api/v1/payments  ──►  PaymentService.processPayment   │
│                                       │                      │
│                                       ▼                      │
│                          PaymentRepository (interface)       │
│                          ├── InMemoryPaymentRepository  (P1) │
│                          └── JdbcPaymentRepository      (P3) │
│                                       │                      │
│                                       ▼                      │
│                          single transaction:                 │
│                          UPDATE accounts SET balance -= ?    │
│                          INSERT INTO payments (...)          │
│                          COMMIT  (or ROLLBACK on any failure)│
│                                                              │
│  GET /api/v1/payments/{jobId}/status  ◄── async settler      │
│                                          (PENDING → SUCCESS) │
└──────────────────────────────────────────────────────────────┘
```

### REST API

| Method | Endpoint | Success | Error codes |
|---|---|---|---|
| POST | `/api/v1/payments` | `202 Accepted` (new) / `200 OK` (idempotent replay) | `400 INVALID_AMOUNT`, `400 INVALID_PRECISION`, `400 VALIDATION_ERROR`, `400 BAD_REQUEST`, `401 UNAUTHORIZED`, `402 INSUFFICIENT_BALANCE`, `404 ACCOUNT_NOT_FOUND`, `422 CURRENCY_MISMATCH`, `500 INTERNAL_ERROR` |
| GET | `/api/v1/payments/{jobId}/status` | `200 OK` with `status: PENDING` / `SUCCESS` | `401 UNAUTHORIZED`, `404 JOB_NOT_FOUND` |
| GET | `/api/v1/health` | `200 {"status":"UP"}` | — (no auth — readiness probe) |

> **Authentication:** when `PAYMENT_API_KEY` is configured, the payment
> endpoints require a matching `X-API-Key` header (constant-time compared) or
> return `401 UNAUTHORIZED`; `/api/v1/health` is always exempt. With no key
> configured the API is open (demo default).

> **Error-code accuracy:** `402 INSUFFICIENT_BALANCE` is reserved for an actual
> insufficient balance (signalled by `InsufficientBalanceException`). Field
> lengths are bounded at the service layer to the schema limits
> (`idempotency_key` ≤ 100, `user_id`/`order_id` ≤ 50, `currency` ≤ 10), so
> oversized input returns `400 VALIDATION_ERROR` — never an opaque `402`/`500`
> from a SQL truncation. An `amount` with more than 8 significant decimal
> places (the `DECIMAL(18,8)` limit) is rejected with `400 INVALID_PRECISION`
> rather than silently truncated; trailing zeros (`100.500000000`) are not
> over-rejected. A payment whose `currency` differs from the account's
> currency is rejected with `422 CURRENCY_MISMATCH` (well-formed but
> unprocessable) — never silently accepted. Any genuinely unexpected server
> fault returns `500 INTERNAL_ERROR`.

### DB Schema (H2 in MySQL mode — `JdbcPaymentRepository`)

```sql
CREATE TABLE accounts (
    user_id   VARCHAR(50)   PRIMARY KEY,
    balance   DECIMAL(18,8) NOT NULL,
    currency  VARCHAR(10)   NOT NULL DEFAULT 'USDT'
);

CREATE TABLE payments (
    payment_id      VARCHAR(50)   PRIMARY KEY,
    order_id        VARCHAR(50)   NOT NULL,
    user_id         VARCHAR(50)   NOT NULL,
    amount          DECIMAL(18,8) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(100)  UNIQUE NOT NULL,
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);
```

The `UNIQUE(idempotency_key)` constraint is the concurrency backstop: under a race, the loser's INSERT fails, its transaction rolls back, the debit it performed is undone, and the API returns the winning transaction's `payment_id`.

### How to Run

```bash
# From repo root — runs all 98 tests (both modules)
mvn test

# Payment module only
cd payment-api && mvn test

# Run the Payment API as a standalone service (no external DB)
mvn package -pl payment-api -am -DskipTests
java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# → POST http://localhost:8091/api/v1/payments   GET /api/v1/health

# Same service on the real JDBC repo (H2 in-mem, MySQL mode, strict accounts):
PAYMENT_REPO=jdbc java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# seeded demo account: USER_DEMO  (unknown users → 404 ACCOUNT_NOT_FOUND)

# With X-API-Key authentication enabled:
PAYMENT_API_KEY=secret java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# payments now require:  curl -H "X-API-Key: secret" ...   (else 401); /health stays open

# Generate Allure report
cd payment-api && mvn allure:report
open payment-api/target/site/allure-maven-plugin/index.html
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
# CI (no MySQL):
Tests run: 55, Failures: 0, Errors: 0, Skipped: 8 — BUILD SUCCESS

# Local with MySQL:
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

| Suite | Tests | CI | Local (MySQL) | Description |
|---|---|---|---|---|
| Unit | 44 | ✅ | ✅ | OrderBook, OrderCache, AmountValidator, TradingEngine |
| API | 7 | ✅ | ✅ | RestAssured against live embedded server |
| Integration | 4 | ✅ | ✅ | End-to-end: all 4 patterns verified together |
| DB Validation | 8 | ⏭ Skipped | ✅ | Binance QA-style MySQL checks (`-Dgroups=db-validation`) |

### Architecture

```
┌─────────────────────────────────────────────────────────┐
│                Trading Engine Simulator                  │
│                                                         │
│  BUY Thread ──┐  (Semaphore alternation)                │
│               ├──► orderBook.addOrder()                 │
│  SELL Thread ─┘   orderCache.put()                      │
│                   orderListener.accept()                 │
│                        │           │                    │
│                        ▼           ▼                    │
│             OrderBook (HashMap)  WebSocket :8093        │
│             OrderCache (LRU)     MySQL (async)          │
│                   │                   │                 │
│                   ▼                   ▼                 │
│             REST API :8092   /api/v1/orders/history     │
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
DB_PASSWORD=your_password java -jar target/trading-engine-simulator-1.0.0.jar

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
| BUG-11 | OrderBook | `getAllOrders()` iterates `synchronizedList` without lock → intermittent `ConcurrentModificationException` | `synchronized (allOrders) { return new ArrayList<>(allOrders); }` |

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

## Repo Conventions

| Setting | Value |
|---|---|
| `main` branch protection | PR-only · 2 required CI checks · `enforce_admins: true` · force-push & deletion disabled · conversation resolution required |
| Repo merge strategy | Squash **disabled** · Merge + Rebase allowed · auto-delete branch on merge |
| Recommended merge mode | **Rebase merge** — keeps the P1 → P2 → P3 commits as a linear narrative on `main` |
| CI triggers | `push` to `main`/`develop` · `pull_request` to `main` |
| Required checks | `Java Tests` · `UI Build Check (Next.js 15)` |

> The portfolio's history was built as a phased refactor. `git log --oneline main` shows the four steps from the empty-shell payment-api to the real ACID-backed service in chronological order — the commit log is itself a design document.

---

## Tech Stack (All Modules)

| Tool | Purpose |
|---|---|
| Java 17 | Backend language |
| Maven | Build & dependency management |
| JUnit 5 | Test framework |
| Mockito | Mocking for unit tests |
| RestAssured | HTTP API assertions |
| JDBC | Driver-agnostic DB access (`java.sql`) — used by `JdbcPaymentRepository` |
| H2 | In-memory database (MySQL mode) |
| MySQL 8 | Persistent order storage |
| Allure | Test report generation |
| GitHub Actions | CI/CD |
| Next.js 15 | Frontend framework |
| TypeScript | Frontend type safety |
| Tailwind CSS | UI styling |
