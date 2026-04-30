# Binance Trading Engine Simulator

A production-grade QA demo project simulating a live BTC/USDT trading engine — built to demonstrate **4 core LeetCode patterns** applied to real financial system design.

Pairs with a [Next.js real-time dashboard](https://github.com/benson-code/trading-engine-ui) for live order visualization.

---

## What This Demonstrates

| LeetCode | Pattern | Implementation |
|----------|---------|---------------|
| LC-217 / LC-347 | HashMap Duplicate Detection / Top-K | `OrderBook` — `ConcurrentHashMap` counts every submission; exposes duplicates and frequency ranking |
| LC-146 | LRU Cache | `OrderCache` — `LinkedHashMap(accessOrder=true)` with `removeEldestEntry` eviction |
| LC-65 / LC-8 | Valid Number / String-to-Int | `AmountValidator` — regex + `BigDecimal` validates BTC amounts (1 satoshi to 9.99... BTC) |
| LC-1115 | Print FooBar Alternately | `TradingEngine` — Semaphore pair enforces strict `BUY→SELL→BUY→SELL` thread alternation |

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  TradingEngine (Java)                    │
│                                                          │
│  BUY-THREAD ──buySemaphore──►  generateOrder("BUY")      │
│       │                              │                   │
│       │                        OrderBook (LC-217/347)    │
│       │                        OrderCache (LC-146)       │
│       │                        AmountValidator (LC-65)   │
│       │                              │                   │
│  SELL-THREAD ◄─sellSemaphore──  generateOrder("SELL")    │
│                                      │                   │
│                              orderListener               │
│                            ╱           ╲                 │
│              WebSocket (8093)      DB-WRITER thread      │
│                    │                    │                │
│              Next.js UI           MySQL (3306)           │
│              (real-time)          (persistent)           │
│                                                          │
│              REST API (8092) ─── all endpoints           │
└──────────────────────────────────────────────────────────┘
```

---

## Tech Stack

**Backend**
- Java 17, Maven 3
- `com.sun.net.httpserver.HttpServer` — zero-dependency REST server
- `org.java-websocket 1.5.4` — real-time WebSocket push
- `jackson-databind 2.16` — JSON serialization
- `mysql-connector-j 8.3` — JDBC persistence

**Testing**
- JUnit 5.10, Mockito 5.8
- RestAssured 5.4 — live HTTP API tests
- Allure 2.25 — test reporting
- AspectJ 1.9 — Allure instrumentation

**Infrastructure**
- MySQL 8 (Docker) — `binance_test_db.orders`
- Fat JAR via `maven-shade-plugin` — single-command startup

---

## Project Structure

```
src/
├── main/java/com/binance/trading/
│   ├── Main.java                    # Entry point: wires engine → WS → DB → REST
│   ├── engine/
│   │   ├── TradingEngine.java       # LC-1115: Semaphore-based BUY/SELL alternation
│   │   ├── OrderBook.java           # LC-217/347: ConcurrentHashMap duplicate detection
│   │   ├── OrderCache.java          # LC-146: LinkedHashMap LRU cache
│   │   └── AmountValidator.java     # LC-65/8: regex + BigDecimal validation
│   ├── model/
│   │   ├── Order.java               # Order entity (Builder pattern)
│   │   ├── OrderStatus.java         # PENDING / FILLED / CANCELLED
│   │   └── ValidationResult.java    # Validation result wrapper
│   ├── api/
│   │   └── TradingApiServer.java    # REST API (8092): status / orders / engine control
│   ├── ws/
│   │   └── TradingWebSocketServer.java  # WebSocket (8093): ORDER_CREATED / STATS_UPDATE
│   └── db/
│       └── DBOrderRepository.java   # MySQL persistence with async writer + auto-reconnect
│
└── test/java/com/binance/trading/
    ├── unit/
    │   ├── DuplicateOrderDetectorTest.java   # 6 tests  — LC-217/347
    │   ├── LRUCacheTest.java                 # 9 tests  — LC-146
    │   ├── AmountValidatorTest.java          # 26 tests — LC-65/8 (@ParameterizedTest)
    │   └── AlternatePrintTest.java           # 3 tests  — LC-1115 (Semaphore)
    ├── api/
    │   └── TradingEngineApiTest.java         # 7 tests  — RestAssured live HTTP
    └── integration/
        └── TradingFlowIntegrationTest.java   # 4 tests  — all 4 patterns end-to-end
```

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | `java -version` |
| Maven | 3.8+ | `mvn -version` |
| Docker | any | for MySQL container |

### 1. Start MySQL

```bash
docker run -d \
  --name trading-mysql \
  -e MYSQL_DATABASE=binance_test_db \
  -e MYSQL_USER=binance_user \
  -e MYSQL_PASSWORD=BinanceTest2026 \
  -e MYSQL_ROOT_PASSWORD=BinanceTest2026 \
  -p 3306:3306 \
  mysql:8.0
```

The `orders` table is created automatically on first startup.

> **Password**: set `DB_PASSWORD` env var to override the default (`BinanceTest2026`).

---

## Build & Run

```bash
# Build fat JAR (bundles all dependencies including Java-WebSocket)
mvn package -DskipTests

# Run
java -jar target/trading-engine-simulator-1.0.0.jar
```

Output:
```
[DB] Connected to MySQL: binance_test_db
[WS] WebSocket server started on port 8093
╔══════════════════════════════════════════════╗
║    Binance Trading Engine Simulator          ║
╚══════════════════════════════════════════════╝
  REST API  : http://0.0.0.0:8092/api/v1/status
  WebSocket : ws://0.0.0.0:8093
  Engine    : STOPPED — press RUN in the UI to start
```

The engine does **not** auto-start. Use the RUN button in the UI or `POST /api/v1/engine/start`.

---

## Run Tests

```bash
mvn test
```

```
Tests run: 55, Failures: 0, Errors: 0, Skipped: 0  ✅
```

| Test Class | Count | What It Covers |
|-----------|-------|----------------|
| `AmountValidatorTest` | 26 | Valid/invalid amounts: null, blank, negative, leading zeros, max satoshi, overflow, whitespace |
| `LRUCacheTest` | 9 | Eviction at capacity, hit/miss counting, access-order promotion, `hitRate` |
| `DuplicateOrderDetectorTest` | 6 | `hasDuplicates`, `findDuplicateOrderIds`, `getTopKDuplicates`, frequency map |
| `AlternatePrintTest` | 3 | Strict alternation, deadlock prevention, race condition under concurrency |
| `TradingEngineApiTest` | 7 | Live RestAssured: status, GET/POST orders, 404, CORS, duplicate endpoint |
| `TradingFlowIntegrationTest` | 4 | All 4 patterns together: HashMap + LRU + validation + thread alternation |

Generate Allure report:
```bash
mvn allure:report
open target/site/allure-maven-plugin/index.html
```

---

## REST API Reference

Base URL: `http://localhost:8092`

All responses include CORS headers (`Access-Control-Allow-Origin: *`).

### Engine Control

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/v1/engine/start` | Start BUY/SELL thread alternation |
| `POST` | `/api/v1/engine/stop` | Stop the engine gracefully |

```bash
curl -X POST http://localhost:8092/api/v1/engine/start
# → {"status":"RUNNING","message":"Engine started"}
```

### Status

```bash
curl http://localhost:8092/api/v1/status
```
```json
{
  "status": "RUNNING",
  "total_generated": 60,
  "unique_orders": 54,
  "total_orders": 60,
  "duplicate_count": 6,
  "cache_size": 54,
  "cache_hit_count": 0,
  "cache_miss_count": 0,
  "cache_hit_rate": 0.0,
  "has_duplicates": true,
  "buy_count": 30,
  "sell_count": 30,
  "last_price": 96416.03
}
```

### Orders

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/orders?limit=500` | In-memory orders (default 500, max 5000) |
| `GET` | `/api/v1/orders/{id}` | Single order — LRU cache first, then OrderBook |
| `POST` | `/api/v1/orders` | Inject order manually; returns `201` if new, `200` if duplicate |
| `GET` | `/api/v1/orders/duplicates` | Duplicate analysis: IDs + frequency map |
| `GET` | `/api/v1/orders/history?limit=100` | Persistent orders from MySQL |

```bash
# Inject a new order
curl -X POST http://localhost:8092/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"order_id":"QA-TEST-001","type":"BUY","symbol":"BTCUSDT",
       "amount":"0.50000000","price":95000,"status":"PENDING","timestamp":1700000000000}'
# → {"order_id":"QA-TEST-001","is_new":true,"message":"Order created"}  (201)

# Inject the same order again
# → {"order_id":"QA-TEST-001","is_new":false,"message":"Duplicate order detected — not reprocessed"}  (200)

# Query MySQL history
curl "http://localhost:8092/api/v1/orders/history?limit=5"
# → {"source":"MySQL binance_test_db.orders","total_in_db":159,"returned":5,"orders":[...]}
```

---

## WebSocket Stream

Connect: `ws://localhost:8093`

Two message types are broadcast:

### `ORDER_CREATED`
Emitted on every order generated by the engine.
```json
{
  "type": "ORDER_CREATED",
  "data": {
    "order_id": "ORD-BUY-000061",
    "type": "BUY",
    "symbol": "BTCUSDT",
    "amount": "0.36352647",
    "price": 93693.75,
    "status": "PENDING",
    "timestamp": 1777475020784,
    "thread_name": "BUY-THREAD"
  }
}
```

### `STATS_UPDATE`
Broadcast every 1 second while clients are connected.
```json
{
  "type": "STATS_UPDATE",
  "data": {
    "status": "RUNNING",
    "buy_count": 30,
    "sell_count": 30,
    "last_price": 96416.03,
    "cache_hit_rate": 0.0
  }
}
```

---

## MySQL Schema

```sql
CREATE TABLE orders (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(50)   NOT NULL,
    type         VARCHAR(10)   NOT NULL,      -- BUY or SELL
    price        DECIMAL(18,2) NOT NULL,
    amount       DECIMAL(18,8) NOT NULL,
    status       VARCHAR(20)   NOT NULL,
    thread_name  VARCHAR(50),
    timestamp    BIGINT        NOT NULL,      -- Unix ms
    is_duplicate TINYINT(1)    DEFAULT 0,
    created_at   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_id  (order_id),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Useful queries:
```sql
-- BUY/SELL split
SELECT type, COUNT(*) as count FROM orders GROUP BY type;

-- Duplicate orders
SELECT order_id, COUNT(*) as freq FROM orders GROUP BY order_id HAVING freq > 1;

-- Orders per minute
SELECT DATE_FORMAT(FROM_UNIXTIME(timestamp/1000), '%Y-%m-%d %H:%i') as minute,
       COUNT(*) as orders
FROM orders
GROUP BY minute ORDER BY minute DESC LIMIT 10;
```

---

## Engine Configuration

Configured in `Main.java`:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `intervalMs` | `100` | Sleep between orders per thread → ~20 orders/sec total |
| `duplicateProbability` | `0.05` | 5% of orders reuse a recent ID to simulate duplicates |
| REST port | `8092` | `java -jar ... 8092 8093` to override |
| WS port | `8093` | second arg |

**Memory at 20 orders/sec**: ~80 MB/hour (in-memory `OrderBook`). All orders are also persisted to MySQL.

---

## Bugs Fixed (QA Review)

During self-review, 11 bugs were identified and fixed:

| Severity | Bug | Fix |
|----------|-----|-----|
| 🔴 Critical | `start()` race condition — non-atomic check-then-set | `AtomicBoolean.compareAndSet()` |
| 🔴 Critical | Tests bind to production ports 8092/8093 → `BindException` | `findFreePort()` per test class |
| 🟠 High | `close()` calls `shutdownNow()` before DB queue drains | `shutdown()` + `awaitTermination(10s)` |
| 🟠 High | Single JDBC connection silently dies after 8h | `getConn()` with `isValid(2)` auto-reconnect |
| 🟠 High | `GET /api/v1/orders` returns all orders with no limit | `?limit=` param, default 500, max 5000 |
| 🟠 High | `POST` body size unlimited → OOM vector | `readNBytes(65_536)` cap |
| 🟡 Medium | Duplicate ID generation references wrong thread's counter | Step back by multiples of 2 |
| 🟡 Medium | Hardcoded DB password in source | `System.getenv("DB_PASSWORD")` with fallback |

---

## Frontend

The [trading-engine-ui](https://github.com/benson-code/trading-engine-ui) repo provides a Binance-styled real-time dashboard:

- **TradingView candlestick chart** — 5-second OHLCV candles built from live WebSocket orders
- **Live Order Book** — newest-first table with duplicate highlighting (⚠)
- **Thread Monitor** — real-time BUY vs SELL progress bars with `|BUY−SELL| ≤ 1` assertion
- **Stats Panel** — cache hit rate, total orders, duplicate count
- **RUN / STOP** — controls the engine via REST

---

## License

MIT
