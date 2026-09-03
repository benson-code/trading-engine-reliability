# Binance QA Suite

**English** | [繁體中文](README.zh-TW.md)

Full-cycle Binance QA portfolio: a runnable Payment API with real transactional ACID + idempotency under concurrency, a live BTC trading engine simulator with MySQL persistence, and a real-time Next.js dashboard.

![CI](https://github.com/benson-code/trading-engine-reliability/actions/workflows/ci.yml/badge.svg)

### Why this project exists

Across 10 years of QA on payment gateways, e-commerce platforms, and a Tier-1 bank's card-payment integration, the failures that hurt most were never on the happy path — they were **silent backend failures**: a debit that committed while its payment row didn't, a retry that double-charged under load, a settlement state that disagreed between two services. Catching those took Oracle SQL, JDBC, and Linux log forensics *after* the fact.

This repo turns that hard-won instinct into **executable proof at the DB layer**: the same ACID-rollback, exactly-once idempotency, and race-condition scenarios I chased in production are reproduced here as automated tests that fail loudly when the invariant breaks — so the bug is caught in CI, not in a reconciliation report.

### Highlights

- **Real service, real DB, real ACID** — `JdbcPaymentRepository.createPayment` runs the balance debit and the payment insert in **one transaction**; `UNIQUE(idempotency_key)` is the concurrency backstop. A retry that races and loses the constraint rolls back — **which undoes its debit** — so the account is debited exactly once regardless of how many retries arrive ([`JdbcPaymentRepositoryTest`](payment-api/src/test/java/com/binance/payment/db/JdbcPaymentRepositoryTest.java)).
- **Concurrency proven, not asserted** — 16 threads call `createPayment` with the same idempotency key; the test ([`ConcurrentIdempotencyTest`](payment-api/src/test/java/com/binance/payment/concurrency/ConcurrentIdempotencyTest.java)) asserts exactly-one debit and one `payment_id` on **both** repository implementations.
- **No WireMock theatre** — every API and integration test exercises the real `PaymentService` through an embedded HTTP server, not a mocked stand-in, so a green suite means the actual service behaves ([commit `668bfc4`](https://github.com/benson-code/trading-engine-reliability/commit/668bfc4) shows the mock-to-real migration).
- **Payment-grade input & access control** — currency must match the account (`422`), amount precision is bounded to `DECIMAL(18,8)` (`400 INVALID_PRECISION`, no silent truncation), and the payment endpoints require an `X-API-Key` (constant-time compared) when configured ([`PaymentAuthTest`](payment-api/src/test/java/com/binance/payment/api/PaymentAuthTest.java)).
- **The frontend is tested for the defect class that took the backend down** — `useTradingEngine` held two collections that only ever grew, one of them copying itself on every message. A Pixel 7 endurance test drives 40k orders — roughly 33 minutes of session — and asserts retained heap did not grow with them: **~2,070 KB → 401 KB**, with per-batch time flattening from 2.27x to 0.98x ([`session-retention.spec.ts`](trading-engine-ui/tests/endurance/session-retention.spec.ts)).
- **Observability built from real incidents** — 24 Prometheus alert rules whose thresholds are reverse-engineered from two measured production incidents · 13 runbooks with CI-enforced coverage · Alertmanager routing with four inhibition rules · one-command incident-scene preservation.
- **CI-enforced quality** — 104 Java tests plus a mobile-web endurance suite · a declarative `BOUNDED-BY` gate that fails any long-lived collection with no eviction and no stated reason it cannot grow · admin-enforced branch protection on `main` · PR-only · four required checks must be green (secret scan first) · rebase-merge preserves the P1/P2/P3 commit narrative.

---

## Repository Structure

```
binance-qa-suite/                  ← Monorepo root (Maven parent POM)
├── payment-api/                   ← Module 1: runnable Payment API + QA tests (Java 17, 46 tests)
├── trading-engine-simulator/      ← Module 2: BTC trading engine (Java 17, 58 tests in CI / 66 with MySQL)
├── trading-engine-ui/             ← Module 3: Real-time dashboard (Next.js 15) + mobile-web endurance test
├── deploy/
│   ├── observability/             ← Module 4: Prometheus · Alertmanager · Grafana · exporters
│   └── systemd/                   ← Service units and credential handling
├── docs/
│   ├── incident-2026-07-14-*/     ← Incident RCA with preserved evidence + SHA256 manifests
│   └── runbooks/                  ← 13 alert-response SOPs (CI-enforced coverage)
└── tools/                         ← CI quality gates + incident forensics
```

**One command runs all 104 Java tests:**
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
| Endurance | Job and idempotency stores stay capped over a long run | JUnit 5, direct store inspection |

**Total: 46 test cases** (16 unit/API/idempotency baseline + 5 real-service E2E + 6 JDBC ACID & negative-path + 3 field-length & HTTP-code accuracy + 4 currency-match + 4 amount-precision + 5 API-key auth + 3 endurance/retention)

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
    │       ├── PaymentRepository.java             (interface — the swap seam)
    │       ├── InMemoryPaymentRepository.java     ← runnable impl (P1)
    │       ├── JdbcPaymentRepository.java         ← real ACID impl (P3)
    │       ├── CurrencyMismatchException.java     ← drives 422
    │       ├── InsufficientBalanceException.java  ← drives 402
    │       └── PaymentService.java
    └── test/java/com/binance/payment/
        ├── unit/PaymentServiceTest.java
        ├── api/
        │   ├── PaymentAPITest.java
        │   ├── IdempotencyTest.java
        │   ├── PaymentServiceE2ETest.java      ← E2E vs the real server
        │   └── JobRetentionEnduranceTest.java  ← settlement must not resurrect evicted jobs
        ├── db/
        │   ├── BalanceVerificationTest.java
        │   └── JdbcPaymentRepositoryTest.java  ← strict accounts + ACID (P3)
        ├── concurrency/ConcurrentIdempotencyTest.java  ← N-thread race (P3)
        ├── endurance/PaymentRetentionTest.java ← idempotency store stays capped
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
# From repo root — runs all 104 tests (both modules)
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

BTC/USDT trading engine demonstrating 4 LeetCode algorithm patterns with 58 automated tests, MySQL persistence, and live WebSocket streaming.

### LeetCode Patterns Implemented

| Pattern | Component | Algorithm |
|---|---|---|
| LC-217 / LC-347 | OrderBook | HashMap duplicate detection + frequency analysis |
| LC-146 | OrderCache | LRU Cache (LinkedHashMap) |
| LC-65 / LC-8 | AmountValidator | Decimal string validation |
| LC-1115 | TradingEngine | Thread alternation via Semaphore |

### Test Results

```
# CI (no MySQL) — verbatim from the Java Tests job:
Tests run: 0, ... -- in com.binance.trading.db.DBValidationTest
Tests run: 58, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

# Local with MySQL:
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

`DBValidationTest` gates itself in `@BeforeAll` via `Assumptions.assumeTrue`. A container-level
assumption aborts the class, so surefire reports it as `Tests run: 0` rather than as 8 skipped —
58 in CI and 66 locally, not 58 + 8 skipped.

> The local figure assumes a **freshly seeded** `binance_test_db`. Running against a database
> that has accumulated orders from an earlier long engine run will fail
> `buySellRatioIsBalanced` — that failure is the 2026-07 incident showing through the data, and
> is analysed in [RCA §8.1](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md).

| Suite | Tests | CI | Local (MySQL) | Description |
|---|---|---|---|---|
| Unit | 44 | ✅ | ✅ | OrderBook, OrderCache, AmountValidator, TradingEngine |
| API | 7 | ✅ | ✅ | RestAssured against live embedded server |
| Integration | 4 | ✅ | ✅ | End-to-end: all 4 patterns verified together |
| Endurance | 3 | ✅ | ✅ | `OrderBookRetentionTest` — collections stay bounded under sustained load |
| DB Validation | 8 | ⏭ Not run | ✅ | Binance QA-style MySQL checks (`-Dgroups=db-validation`) |

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
| BUG-12 | useTradingEngine.ts | `seenIds` grew for the lifetime of the tab and was rebuilt on every message (`new Set(prev).add(id)`) — an O(n) copy whose result nothing read | Deleted; duplicate highlighting already derives from the capped `orders` array |
| BUG-13 | useTradingEngine.ts | `klineMap` never deleted a bucket, while the `klines` array it feeds was capped at `MAX_KLINES` | Evicts in insertion order past `MAX_KLINES`, so it cannot outgrow its own consumer |

---

## Module 3 — Trading Engine UI

Real-time Binance-styled trading dashboard connecting to Module 2 via WebSocket.

### Features

- Live candlestick chart (TradingView Lightweight Charts, 5-second buckets)
- Order book with duplicate highlighting
- Engine stats panel (BUY/SELL counts, cache hit rate, duplicates)
- Thread monitor (BUY/SELL thread activity)
- WebSocket auto-reconnect with exponential backoff

### Testing

Functional tests have no time axis — which is why the entire suite stayed green throughout the
2026-07 GC death spiral. (The exact suite size at the time of the incident cannot be established
from the preserved evidence — see the known-gaps table in the RCA.) This module adds that axis to
the frontend.

| Layer | What it checks | When it runs |
|---|---|---|
| [`check-bounded-collections-ts.sh`](tools/check-bounded-collections-ts.sh) | Every `useState` / `useRef` / module-level collection must evict or carry `// BOUNDED-BY: <reason>` | CI, before `npm ci` — it needs no dependencies |
| [`session-retention.spec.ts`](trading-engine-ui/tests/endurance/session-retention.spec.ts) | 40k orders (~33 min of session) under Pixel 7 emulation; asserts retained heap **after forced GC** did not grow with them | CI, after the build |

```bash
cd trading-engine-ui
npx playwright install chromium
npm run test:e2e          # builds into .next-e2e, serves on :3100
npm run test:e2e:report   # HTML report + trace viewer on :9323
```

What the numbers were, across 40k orders:

| | retained growth | per-batch time | ratio |
|---|---|---|---|
| before | ~2,070 KB | 2896 → 6573 ms | 2.27x |
| after | **401 KB** | 2139 → 2097 ms | **0.98x** |

> **Scope, stated rather than implied.** Pixel 7 emulation is Chromium with a phone's
> viewport, DPR and user agent — not a phone. JS heap and main-thread cost carry over to
> Android Chrome because it is the same engine; GPU compositing, thermal throttling and
> OS-level memory pressure do not.
>
> Elapsed time is recorded but deliberately **not** asserted: identical runs on an idle
> box gave first-to-last ratios of 1.64x and 2.12x, a spread wider than the effect being
> measured. Retained heap varied ~7% across three runs, so the gate sits there instead —
> a threshold inside the noise band tests the CI scheduler, not the code.

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
| Playwright | Mobile-web endurance testing (Pixel 7 emulation, CDP heap measurement) |

---

## Module 4 — Observability & Reliability Platform

A Prometheus/Grafana platform built around two real incidents this repository
suffered in July 2026. Every alert threshold is derived from a **measured value
from those incidents**, not from a vendor default.

### Why it exists

Both incidents shared one property: **every conventional health check passed
while the service was broken.**

| Check | Incident #1 (GC death spiral) | Incident #2 (silent degradation) |
|---|---|---|
| `systemctl status` | `active (running)` ✅ | `active (running)` ✅ |
| TCP port bound | yes ✅ | yes ✅ |
| `GET /api/v1/status` | timed out ❌ | `200 OK` ✅ |
| K8s liveness probe | would fail ❌ | would pass ✅ |
| **Actual business output** | **zero** | **zero, for six days** |

Incident #2 is the harder one: the process answered `200 OK` correctly for six
days while producing nothing. No probe that asks *"is the service responding?"*
can detect that. The platform's answer is to measure **whether work is
progressing**, not whether a process is alive.

Full root-cause analysis: [`docs/incident-2026-07-14-gc-death-spiral/`](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)
(with preserved evidence and SHA256 manifests).

### Architecture

```
Layer 4   Grafana ───── SRE overview · capacity planning · JVM incident replay
                             ▲
Layer 3   Alertmanager ── severity routing · 4 inhibition rules · → runbooks
                             ▲
Layer 2   Prometheus ──── 24 rules / 6 groups · 30d retention
                             ▲
Layer 1   Collection ──── node_exporter (+textfile) · blackbox · mysqld · redis
                             ▲
Layer 0   Monitored ───── payment-api · trading-engine · MySQL · Redis · host
```

All services run with `network_mode: host`. The host's iptables blocks
container→host traffic, and MySQL/Redis bind to `127.0.0.1` only; host
networking lets everything talk over loopback. Exporters bind to `127.0.0.1`
(they expose database internals); only the UI layer is reachable externally.

### Components

| Service | Port | Bind | Role |
|---|---|---|---|
| Prometheus | 9090 | `0.0.0.0` | Scraping and rule evaluation |
| Alertmanager | 9093 | `0.0.0.0` | Routing, grouping, inhibition |
| Grafana | 3001 | `0.0.0.0` | Dashboards (3000 is taken by `next dev`) |
| node_exporter | 9100 | `0.0.0.0` | Host metrics + textfile collector |
| blackbox_exporter | 9115 | `127.0.0.1` | External probing |
| mysqld_exporter | 9104 | `127.0.0.1` | MySQL metrics |
| redis_exporter | 9121 | `127.0.0.1` | Redis metrics |

JVM metrics are sampled by [`jstat-exporter.sh`](deploy/observability/jstat-exporter.sh)
into the textfile collector rather than via a JMX agent: the JVM under
observation was started without `-javaagent`, and restarting it would have
destroyed the clean post-fix reference run.

### Alert design

24 rules in 6 groups, each answering a different question:

| Group | Question | Rules |
|---|---|---|
| `availability` | Can users reach it right now? (blackbox) | 3 |
| `work-progress` | Is work actually progressing? (incident #2) | 3 |
| `jvm-gc` | Is the JVM healthy? (incident #1) | 4 |
| `saturation` | Are resources running out? (USE method) | 4 |
| `capacity` | *How long until* they run out? (`predict_linear`) | 4 |
| `dependencies` | Are MySQL and Redis alive? | 6 |

**Thresholds are reverse-engineered from the incidents:**

| Signal | Threshold | Measured during the incident |
|---|---|---|
| Full GC as a fraction of process uptime | > 10% | **70%** (491,218s STW / 698,732s uptime) |
| Old-gen utilisation | > 85% | **99.99%** |
| Full GC count | rate > 0.1/s | **114,879 collections** |
| Order generation rate | 0 for 10 min | normal is **1,198 rows/min** (≈20/s) |

### Alert routing

`critical` pages immediately; `capacity` alerts (which predict a problem 24
hours out) are deliberately routed to a non-paging channel. Four inhibition
rules keep one failure from producing twenty notifications:

1. `HostDown` suppresses every other alert on that host
2. `critical` suppresses `warning` for the same service
3. `JvmGcTimeRatioHigh` suppresses `JvmOldGenHigh` (symptom-chain collapse)
4. `ServiceDown` suppresses `EngineNotProgressing`

> Alert fatigue is more dangerous than no alerts. An on-call engineer receiving
> 200 notifications a night will miss the 201st.

### Runbooks — enforced, not aspirational

**Every alert carries a `runbook_url`, and CI fails the build if one is
missing.** [`tools/check-alert-runbooks.sh`](tools/check-alert-runbooks.sh)
checks three invariants:

- **R1** every alert has a `runbook_url`
- **R2** the file it points at exists
- **R3** no orphaned runbooks (a document no alert references)

13 runbooks cover all 24 alerts. Each follows the same six sections: trigger
conditions · impact · first three minutes · stopping the bleeding · root-cause
investigation · follow-up. See [`docs/runbooks/`](docs/runbooks/README.md).

> An alert without a documented response is a problem handed to whoever is
> woken at 3am. That is a failure of alert design, not of the person on call.

### Dashboards

| Dashboard | Contents |
|---|---|
| **SRE Overview** | Availability · work progress · JVM · dependencies · host (5 sections, 31 panels) |
| **Capacity Planning** | `predict_linear` projections for disk, heap, Redis and host |
| **Trading Engine JVM** | Purpose-built replay view for incident #1 |

### Incident forensics

[`tools/preserve-scene.sh`](tools/preserve-scene.sh) captures a
non-destructive evidence snapshot in one command — `/proc`, `jstat`,
systemd journal, blackbox probes, database bounds — and writes a SHA256
manifest.

Two deliberate design choices:

- **`jstat` timing out is recorded as evidence, not as a collection failure.**
  During incident #1, `jcmd` and `jstack` could not attach because the JVM's
  attach handshake thread had itself been starved by GC. That failure is a
  positive diagnostic signal.
- **Database queries use a high-watermark bound, never a full table scan.**
  The `orders` table holds tens of millions of rows and is written to
  continuously; a full scan would contend with live writes on a shared host.

### Quick start

```bash
cp deploy/observability/mysqld/.my.cnf.example deploy/observability/mysqld/.my.cnf
$EDITOR deploy/observability/mysqld/.my.cnf && chmod 600 deploy/observability/mysqld/.my.cnf

make obs-up          # start the platform
make obs-status      # containers · scrape targets · firing alerts
make obs-validate    # promtool + amtool config validation
make obs-reload      # hot-reload rules without restarting
make runbooks        # alert ↔ runbook coverage
make preserve        # capture an incident snapshot
```

Grafana: `http://localhost:3001`. On a remote host, forward the port over SSH
rather than opening the firewall:

```bash
ssh -L 3001:127.0.0.1:3001 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 user@host
```

### What it caught on its first day

| Alert | Reality |
|---|---|
| `EngineWorkerStopped` | Order generator had been stopped since **2026-08-26 06:02** — **seven days**, unnoticed |
| `EngineNotProgressing` | Database writes had fallen from 1,198 rows/min to zero |
| `RedisIsUnbounded` | Redis running with `maxmemory=0` / `noeviction` — the same defect class as incident #1's unbounded collections |

The journal shows incident #2 repeating exactly:

```
06:02:02  [DB] Save failed ... Communications link failure
06:02:13  systemd: Stopping binance-trading-engine.service
06:02:13  Main process exited, code=exited, status=143/n/a    ← 143 = 128+15 = SIGTERM
06:02:13  systemd: Started binance-trading-engine.service     ← systemd restarted it successfully
06:02:14  Engine : STOPPED — press RUN in the UI to start     ← the worker never came back
```

For all seven days `systemctl` reported `active (running)`, ports 8092/8093
were bound, and `/api/v1/status` returned `200`. The only signal that could
have caught it is the rate of business output.

---

## Security & Credentials

This is a **public** repository. Anything committed here is published
permanently — git history keeps it even after a later deletion.

### No credential is ever hard-coded

`DBOrderRepository` **refuses to start** when `DB_PASSWORD` is unset. There is
no fallback default. Failing loudly is preferred over silently connecting with
a password that is readable by anyone.

| Component | Credential source | Mode |
|---|---|---|
| trading-engine (systemd) | `/etc/binance-trading-engine.env` via `EnvironmentFile=` | `0640 root:ubuntu` |
| `tools/check-db-integrity.sh` | `DB_PASSWORD` env var, or `deploy/observability/mysqld/.my.cnf` | `0600` |
| `tools/preserve-scene.sh` | same (skips DB capture when absent, does not abort) | `0600` |
| mysqld_exporter | `deploy/observability/mysqld/.my.cnf` | `0600` |

Templates (`*.example`) are version-controlled; the real files are not.
Setup: [`deploy/systemd/README.md`](deploy/systemd/README.md).

**Credentials are not placed in the systemd unit either.** Unit files are mode
`0644` — world-readable on the host. Putting `Environment=DB_PASSWORD=…` there
only moves the secret from git to `/etc`.

**Scripts use `--defaults-extra-file`, never `-p`:**

```bash
mysql -u user -p"$PASSWORD"      # ✗ the password appears in `ps` output
mysql --defaults-extra-file=…    # ✓ protected by file permissions alone
```

### Enforced by CI

[`tools/check-no-secrets.sh`](tools/check-no-secrets.sh) runs as the **first**
CI job; every other job depends on it. It checks four invariants:

- **S1** known credential files are not tracked by git
- **S2** no high-risk secret patterns in tracked files — private keys, AWS/GitHub/Slack tokens, quoted password literals, and shell default-expansions such as `${DB_PASSWORD:-<a real value>}`
- **S3** every credential file has a matching `.example` template
- **S4** templates contain placeholders, not real values

Exceptions live in [`.secretsignore`](.secretsignore) and **each one must carry
a written reason** — an undocumented exception is just a hole.

### Known exposure

An earlier revision of this repository contained a hard-coded password for the
**local test database** (`binance_test_db`). It has been removed from the
working tree, but it remains in git history and must be treated as public.

Practical exposure is nil: MySQL binds to `127.0.0.1` only, and the host
firewall accepts nothing but port 22. The credential grants no access from
outside the machine. It is nonetheless being rotated — a leaked secret is fixed
by changing it, not by deleting the line that showed it.

---

## Repo Conventions

| Setting | Value |
|---|---|
| `main` branch protection | PR-only · 4 required CI checks · `enforce_admins: true` · force-push & deletion disabled · conversation resolution required |
| Repo merge strategy | Squash **disabled** · Merge + Rebase allowed · auto-delete branch on merge |
| Recommended merge mode | **Rebase merge** — keeps the P1 → P2 → P3 commits as a linear narrative on `main` |
| CI triggers | `push` to `main`/`develop` · `pull_request` to `main` |
| Required checks | `Secret Scan` · `Observability Config` · `Java Tests` · `UI Build Check (Next.js 15)` |

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
| Playwright | Mobile-web e2e & endurance testing |
| Prometheus | Metrics collection, alert rule evaluation |
| Alertmanager | Alert routing, grouping and inhibition |
| Grafana | Dashboards (SRE overview, capacity planning) |
| Docker Compose | Observability stack orchestration |
| Bash / Make | Operational interface, CI gates, incident forensics |
