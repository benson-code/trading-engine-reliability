# Binance QA Suite

[English](README.md) | **繁體中文**

一套完整流程（full-cycle）的幣安 QA 作品集，包含三塊：一支可以實際跑起來的支付 API，具備真實的交易型 ACID 跟高併發下的 idempotency（冪等性）；一個搭配 MySQL 做持久化的即時 BTC 交易引擎模擬器；還有一個即時的 Next.js 儀表板。

![CI](https://github.com/benson-code/trading-engine-reliability/actions/workflows/ci.yml/badge.svg)

### 為什麼有這個專案

做 QA 這 10 年，我跑過支付閘道、電商平台，還有一間 Tier-1 銀行的卡片支付整合。真正會出大事的，從來都不是 happy path（正常流程），而是那種**悶不吭聲的後端失敗（silent backend failures）**：扣款明明已經 commit 了，對應的 payment 資料卻沒寫進去；高負載下重試一下就重複扣款；兩個服務之間的結算狀態對不起來。要抓到這些，常常得事後撈 Oracle SQL、JDBC，再配上 Linux log 一行一行追。

這個 repo 就是把那份用代價換來的直覺，變成**資料庫層跑得出來的證明**：那些我在正式環境追過的 ACID rollback、exactly-once（剛好一次）idempotency、race condition（競態條件）情境，這裡通通重現成自動化測試 —— 只要不變量（invariant）一被破壞，測試就會大聲喊出來，讓 bug 在 CI 階段就被擋下，而不是拖到對帳報表才爆出來。

### 重點亮點

- **真服務、真資料庫、真 ACID** —— `JdbcPaymentRepository.createPayment` 把餘額扣款跟 payment 寫入放在**同一個交易（transaction）**裡；`UNIQUE(idempotency_key)` 則是併發時的最後一道防線。萬一某個重試在競爭中輸了，它會 rollback —— **連自己剛剛的扣款也一起撤掉** —— 所以不管收到幾次重試，帳戶就是剛好扣一次（[`JdbcPaymentRepositoryTest`](payment-api/src/test/java/com/binance/payment/db/JdbcPaymentRepositoryTest.java)）。
- **併發是「測出來」的，不是嘴上講講** —— 16 條執行緒拿同一個 idempotency key 去打 `createPayment`；測試（[`ConcurrentIdempotencyTest`](payment-api/src/test/java/com/binance/payment/concurrency/ConcurrentIdempotencyTest.java)）在**兩種** repository 實作上都驗證了：就是扣一次、就是只有一個 `payment_id`。
- **不搞 WireMock 那套假把戲** —— 每一個 API 跟整合測試都是透過內嵌 HTTP server 去打**真正的** `PaymentService`，而不是 mock 出來的替身，所以測試全綠就代表服務本身真的跑得動（[commit `668bfc4`](https://github.com/benson-code/trading-engine-reliability/commit/668bfc4) 就是從 mock 遷移到真實服務的過程）。
- **支付等級的輸入跟權限把關** —— 幣別一定要跟帳戶一致（`422`）；金額精度卡在 `DECIMAL(18,8)`（`400 INVALID_PRECISION`，不會偷偷截斷）；支付端點只要有設定，就一定要帶 `X-API-Key`（用常數時間比較，constant-time）（[`PaymentAuthTest`](payment-api/src/test/java/com/binance/payment/api/PaymentAuthTest.java)）。
- **把弄垮後端的那類缺陷，也拿去測前端** —— `useTradingEngine` 有兩個只進不出的集合，其中一個每收到一則訊息就把自己整份複製一次。Pixel 7 的耐久測試灌 4 萬筆訂單（大約 33 分鐘的 session），然後驗證 retained heap 沒有跟著長大：**約 2,070 KB → 401 KB**，每批耗時的首末比也從 2.27x 拉平到 0.98x（[`session-retention.spec.ts`](trading-engine-ui/tests/endurance/session-retention.spec.ts)）。
- **可觀測性長在真實事故上** —— 24 條 Prometheus 告警規則，閾值全部由兩次實測事故反推 · 13 份 runbook，覆蓋率由 CI 強制 · Alertmanager 分級路由加 4 條抑制規則 · 一個指令完成事故現場保全。
- **品質靠 CI 強制把關** —— CI 一次跑 104 個 Java 測試，外加一套 mobile-web 耐久測試 · 一道宣告式的 `BOUNDED-BY` 閘，任何長生命週期集合只要沒有淘汰機制、又沒寫明為什麼不會無限成長，就直接擋下來 · `main` 上了連 admin 都擋不掉的分支保護 · 只能走 PR · 四個必過的檢查一定要全綠（機密掃描排第一）· 用 rebase-merge 保留 P1/P2/P3 的 commit 故事線。

---

## 儲存庫結構（Repository Structure）

```
binance-qa-suite/                  ← Monorepo 根目錄（Maven parent POM）
├── payment-api/                   ← 模組 1：可執行的支付 API + QA 測試（Java 17, 46 tests）
├── trading-engine-simulator/      ← 模組 2：BTC 交易引擎（Java 17, CI 58 tests / 含 MySQL 66 tests）
├── trading-engine-ui/             ← 模組 3：即時儀表板（Next.js 15）+ mobile-web 耐久測試
├── deploy/
│   ├── observability/             ← 模組 4：Prometheus · Alertmanager · Grafana · exporters
│   └── systemd/                   ← 服務 unit 與憑證管理
├── docs/
│   ├── incident-2026-07-14-*/     ← 事故 RCA，含保留證據與 SHA256 manifest
│   └── runbooks/                  ← 13 份告警處理 SOP（覆蓋率由 CI 強制）
└── tools/                         ← CI 品質閘門 + 事故現場保全
```

**一行指令跑完全部 104 個 Java 測試：**
```bash
mvn test   # 依序執行 payment-api + trading-engine-simulator
```

**資料庫驗證（需要連線的 MySQL）：**
```bash
mvn test -pl trading-engine-simulator -Dgroups=db-validation
```

---

## 模組 1 — 支付 API QA 框架

完整流程的自動化測試，從 API 測試、資料庫驗證、idempotency 驗證，一路涵蓋到 ACID 合規性。

### 測試覆蓋（Test Coverage）

| 層級 | 情境 | 工具 |
|---|---|---|
| 單元測試 | 驗證邏輯、idempotency 服務邏輯 | JUnit 5, Mockito |
| API 測試 | happy path、負向案例、非同步 202 流程 | RestAssured vs 真實 `PaymentApiServer` |
| DB 測試 | 真實 JDBC repo：ACID rollback、嚴格帳戶、idempotency 約束 | JDBC, H2（MySQL 模式） |
| 整合 / E2E | 對真實服務做完整流程 + 非同步結算 | RestAssured, 內嵌 JDK HTTP server |
| 併發 | N 執行緒 idempotency 競賽 → 剛好扣一次 | ExecutorService, 兩種 repo |
| 耐久 | 長時間執行下，job 與 idempotency 儲存都維持有上限 | JUnit 5，直接檢查儲存內容 |

**總計：46 個測試案例**（16 個單元/API/idempotency 基線 + 5 個真實服務 E2E + 6 個 JDBC ACID 與負向路徑 + 3 個欄位長度與 HTTP 狀態碼準確性 + 4 個幣別相符 + 4 個金額精度 + 5 個 API-key 認證 + 3 個耐久/滯留）

> 所有 API、整合跟併發測試，都是透過內嵌 HTTP server 去打真正的
> `PaymentService` —— 完全沒用 WireMock。
> `JdbcPaymentRepository` 提供真實的交易型 ACID 跟嚴格帳戶語意；
> `PaymentRepository` 是抽換用的接縫（swap seam），執行期用
> `PAYMENT_REPO=jdbc` 一切就換過去。

### 關鍵測試情境

**1. Idempotency — 防止重複付款**
模擬客戶端重試：同一個 `idempotency_key` 進來 → `PaymentService` 在 `findByIdempotencyKey` 這關就先擋下，所以 `createPayment`（還有它的扣款）就只跑一次 → API 直接回放同一個 `payment_id`（回 `200`，不會再給第二個 `202`）。

**2. ACID — 原子性與回滾**
`JdbcPaymentRepository.createPayment` 把扣款 + payment 寫入放在同一個交易裡。餘額不夠、或 `idempotency_key` 撞號 → `rollback()` 把扣款撤掉 → 餘額不變，也不會留下沒主人的 payment 資料。帳戶不存在 → 直接擋掉（404），什麼都不寫。這些都在 DB 層驗證過（`JdbcPaymentRepositoryTest`、`BalanceVerificationTest`）。

**3. 非同步付款流程（HTTP 202）**
`POST /payments` → `202 Accepted` + `job_id` → `GET /payments/{jobId}/status` → `SUCCESS`。這才是非同步支付 API 該有的做法（而不是用錯的 201）。

**4. 單元驗證**
`PaymentService` 在碰到任何 repository 或網路之前，就先把 amount > 0、idempotency key 不能空白、userId 不能空白通通驗過一遍。

### 專案結構

```
payment-api/
├── pom.xml
└── src/
    ├── main/java/com/binance/payment/
    │   ├── Main.java                           ← 獨立進入點（:8091）
    │   ├── api/PaymentApiServer.java           ← 真實的內嵌 JDK HTTP server
    │   ├── model/
    │   │   ├── PaymentRequest.java
    │   │   └── PaymentResponse.java
    │   └── service/
    │       ├── PaymentRepository.java             （介面 — 抽換接縫）
    │       ├── InMemoryPaymentRepository.java     ← 可執行實作（P1）
    │       ├── JdbcPaymentRepository.java         ← 真實 ACID 實作（P3）
    │       ├── CurrencyMismatchException.java     ← 觸發 422
    │       ├── InsufficientBalanceException.java  ← 觸發 402
    │       └── PaymentService.java
    └── test/java/com/binance/payment/
        ├── unit/PaymentServiceTest.java
        ├── api/
        │   ├── PaymentAPITest.java
        │   ├── IdempotencyTest.java
        │   ├── PaymentServiceE2ETest.java      ← 對真實 server 的 E2E
        │   └── JobRetentionEnduranceTest.java  ← 結算不得復活已淘汰的 job
        ├── db/
        │   ├── BalanceVerificationTest.java
        │   └── JdbcPaymentRepositoryTest.java  ← 嚴格帳戶 + ACID（P3）
        ├── concurrency/ConcurrentIdempotencyTest.java  ← N 執行緒競賽（P3）
        ├── endurance/PaymentRetentionTest.java ← idempotency 儲存維持有上限
        ├── integration/PaymentFlowTest.java
        └── util/DatabaseUtil.java
```

### 架構（Architecture）

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

| 方法 | 端點 | 成功 | 錯誤碼 |
|---|---|---|---|
| POST | `/api/v1/payments` | `202 Accepted`（新建）/ `200 OK`（idempotent 回放） | `400 INVALID_AMOUNT`, `400 INVALID_PRECISION`, `400 VALIDATION_ERROR`, `400 BAD_REQUEST`, `401 UNAUTHORIZED`, `402 INSUFFICIENT_BALANCE`, `404 ACCOUNT_NOT_FOUND`, `422 CURRENCY_MISMATCH`, `500 INTERNAL_ERROR` |
| GET | `/api/v1/payments/{jobId}/status` | `200 OK`，附 `status: PENDING` / `SUCCESS` | `401 UNAUTHORIZED`, `404 JOB_NOT_FOUND` |
| GET | `/api/v1/health` | `200 {"status":"UP"}` | —（無需認證 — readiness probe） |

> **認證（Authentication）：** 當 `PAYMENT_API_KEY` 有設定時，支付端點需要相符的
> `X-API-Key` header（以常數時間比較），否則回 `401 UNAUTHORIZED`；
> `/api/v1/health` 永遠豁免。未設定 key 時 API 為開放（demo 預設）。

> **錯誤碼準確性：** `402 INSUFFICIENT_BALANCE` 只保留給真正的餘額不足
> （由 `InsufficientBalanceException` 觸發）。欄位長度在服務層被限制到
> schema 上限（`idempotency_key` ≤ 100、`user_id`/`order_id` ≤ 50、
> `currency` ≤ 10），所以過長輸入回 `400 VALIDATION_ERROR` —— 絕不會因
> SQL 截斷而回出語意不明的 `402`/`500`。`amount` 若超過 8 位有效小數
> （`DECIMAL(18,8)` 上限）會回 `400 INVALID_PRECISION` 而非靜默截斷；
> 尾端的零（`100.500000000`）不會被過度拒絕。幣別與帳戶不符的付款
> 回 `422 CURRENCY_MISMATCH`（格式正確但無法處理）—— 絕不靜默接受。
> 任何真正非預期的伺服器錯誤回 `500 INTERNAL_ERROR`。

### DB Schema（H2 之 MySQL 模式 — `JdbcPaymentRepository`）

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

`UNIQUE(idempotency_key)` 這個約束就是併發時的最後一道防線：發生競爭時，輸的那一方 INSERT 會失敗、交易跟著 rollback、它剛剛做的扣款被撤掉，API 最後回傳的是贏家那筆的 `payment_id`。

### 如何執行

```bash
# 從 repo 根目錄 — 跑完全部 104 個測試（兩個模組）
mvn test

# 只跑支付模組
cd payment-api && mvn test

# 以獨立服務執行支付 API（不需外部 DB）
mvn package -pl payment-api -am -DskipTests
java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# → POST http://localhost:8091/api/v1/payments   GET /api/v1/health

# 同一服務改用真實 JDBC repo（H2 記憶體、MySQL 模式、嚴格帳戶）：
PAYMENT_REPO=jdbc java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# 預先建好的 demo 帳戶：USER_DEMO（未知使用者 → 404 ACCOUNT_NOT_FOUND）

# 啟用 X-API-Key 認證：
PAYMENT_API_KEY=secret java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091
# 付款現在需要：  curl -H "X-API-Key: secret" ...  （否則 401）；/health 維持開放

# 產生 Allure 報告
cd payment-api && mvn allure:report
open payment-api/target/site/allure-maven-plugin/index.html
```

---

## 模組 2 — 交易引擎模擬器

一個 BTC/USDT 交易引擎，用 58 個自動化測試、MySQL 持久化跟即時 WebSocket 串流，把 4 種 LeetCode 演算法模式實際跑給你看。

### 實作的 LeetCode 模式

| 模式 | 元件 | 演算法 |
|---|---|---|
| LC-217 / LC-347 | OrderBook | HashMap 重複偵測 + 頻率分析 |
| LC-146 | OrderCache | LRU Cache（LinkedHashMap） |
| LC-65 / LC-8 | AmountValidator | 十進位字串驗證 |
| LC-1115 | TradingEngine | 以 Semaphore 做執行緒交替 |

### 測試結果

```
# CI（無 MySQL）—— 直接取自 Java Tests job 的輸出：
Tests run: 0, ... -- in com.binance.trading.db.DBValidationTest
Tests run: 58, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

# 本機含 MySQL：
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

`DBValidationTest` 是在 `@BeforeAll` 裡用 `Assumptions.assumeTrue` 自我把關。容器層級的 assumption
失敗會中止整個 class，所以 surefire 記的是 `Tests run: 0`，而不是 8 個 skipped —— CI 是 58、本機是
66，不是 58 + 8 skipped。

> 本機那個數字的前提是 `binance_test_db` **剛建好**。如果 DB 裡已經累積了先前長時間跑引擎留下的訂單，
> `buySellRatioIsBalanced` 會失敗 —— 那個失敗正是 2026-07 事故在資料上留下的痕跡，分析見
> [RCA §8.1](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)。

| 測試套件 | 測試數 | CI | 本機（MySQL） | 說明 |
|---|---|---|---|---|
| 單元 | 44 | ✅ | ✅ | OrderBook、OrderCache、AmountValidator、TradingEngine |
| API | 7 | ✅ | ✅ | RestAssured 對真實內嵌 server |
| 整合 | 4 | ✅ | ✅ | 端到端：4 種模式一起驗證 |
| 耐久 | 3 | ✅ | ✅ | `OrderBookRetentionTest` —— 持續負載下集合維持有上限 |
| DB 驗證 | 8 | ⏭ 不執行 | ✅ | 幣安 QA 風格的 MySQL 檢查（`-Dgroups=db-validation`） |

### 架構（Architecture）

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

| 方法 | 端點 | 說明 |
|---|---|---|
| GET | `/api/v1/status` | 引擎指標、BUY/SELL 計數、cache 命中率 |
| GET | `/api/v1/orders` | 所有訂單，含分頁（`?limit=500`） |
| POST | `/api/v1/orders` | 手動注入訂單 |
| GET | `/api/v1/orders/{id}` | 依 ID 查詢（先查 LRU cache） |
| GET | `/api/v1/orders/duplicates` | 重複分析 + 頻率對照表 |
| GET | `/api/v1/orders/history` | 來自 MySQL 的持久化訂單 |
| POST | `/api/v1/engine/start` | 啟動訂單產生 |
| POST | `/api/v1/engine/stop` | 停止訂單產生 |

### 如何執行

```bash
cd trading-engine-simulator

# 建置 fat JAR
mvn package -q

# 啟動（需要 localhost:3306 上的 MySQL）
DB_PASSWORD=your_password java -jar target/trading-engine-simulator-1.0.0.jar

# 跑測試（不需外部 DB）
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

### 修復的 Bug（QA Review）

| 編號 | 元件 | 問題 | 修復 |
|---|---|---|---|
| BUG-01 | TradingEngine | 競態條件：`volatile boolean` 非原子操作 | `AtomicBoolean.compareAndSet()` |
| BUG-02 | Tests | 與正式 server 的埠號衝突 | 以 `ServerSocket(0)` 做 `findFreePort()` |
| BUG-03 | DBOrderRepository | 關閉前未排空非同步寫入 | `shutdown()` + `awaitTermination(10s)` |
| BUG-04 | DBOrderRepository | 8 小時後 JDBC 連線失效 | `conn.isValid(2)` + 自動重連 |
| BUG-05 | useTradingEngine.ts | WS onmessage 遇壞 JSON 靜默死掉 | 在 `JSON.parse` 外包 `try/catch` |
| BUG-06 | TradingApiServer | GET /orders 回傳無上限清單 | `?limit=` 分頁（預設 500，上限 5000） |
| BUG-07 | TradingApiServer | 大量 POST body 造成 OOM | `readNBytes(65_536)` 上限 |
| BUG-08 | TradingEngine | 跨執行緒重複 ID 污染 | 只以偶數倍回退 |
| BUG-09 | useTradingEngine.ts | WS 斷線後不重連 | 指數退避（1s→30s） |
| BUG-10 | useTradingEngine.ts | O(n²) 重複偵測 | `useMemo` 預先計算 `Set`，O(1) 查找 |
| BUG-11 | OrderBook | `getAllOrders()` 未加鎖迭代 `synchronizedList` → 間歇性 `ConcurrentModificationException` | `synchronized (allOrders) { return new ArrayList<>(allOrders); }` |
| BUG-12 | useTradingEngine.ts | `seenIds` 在分頁的整個生命週期只增不減，而且每則訊息都重建一次（`new Set(prev).add(id)`）—— 一個 O(n) 的複製，結果卻沒有任何人讀 | 直接刪掉；重複標示本來就是從有上限的 `orders` 陣列推導出來的 |
| BUG-13 | useTradingEngine.ts | `klineMap` 從來不刪任何一個桶，但它餵養的 `klines` 陣列卻有 `MAX_KLINES` 上限 | 超過 `MAX_KLINES` 就依插入序淘汰，不會長得比自己的消費者還大 |

---

## 模組 3 — 交易引擎 UI

幣安風格的即時交易儀表板，透過 WebSocket 連接模組 2。

### 功能

- 即時 K 線圖（TradingView Lightweight Charts，5 秒一根）
- 訂單簿，含重複高亮
- 引擎統計面板（BUY/SELL 計數、cache 命中率、重複數）
- 執行緒監看（BUY/SELL 執行緒活動）
- WebSocket 以指數退避自動重連

### 測試

功能測試沒有時間軸 —— 這正是 2026-07 GC 死亡螺旋期間，整套功能測試全程都是綠的原因。（事故當下那套測試到底有幾個，從保存下來的證據無法確認 —— 見 RCA 的已知缺口表。）這個模組把時間軸補到前端來。

| 層 | 檢查什麼 | 什麼時候跑 |
|---|---|---|
| [`check-bounded-collections-ts.sh`](tools/check-bounded-collections-ts.sh) | 每一個 `useState` / `useRef` / 模組層級的集合，不是有淘汰機制，就是要掛上 `// BOUNDED-BY: <理由>` | CI，在 `npm ci` 之前 —— 它不需要任何相依套件 |
| [`session-retention.spec.ts`](trading-engine-ui/tests/endurance/session-retention.spec.ts) | Pixel 7 模擬下灌 4 萬筆訂單（約 33 分鐘 session），驗證**強制 GC 之後**的 retained heap 沒有跟著長 | CI，build 之後 |

```bash
cd trading-engine-ui
npx playwright install chromium
npm run test:e2e          # 建置到 .next-e2e，跑在 :3100
npm run test:e2e:report   # HTML 報告 + Trace Viewer，開在 :9323
```

4 萬筆訂單下量到的數字：

| | retained 成長 | 每批耗時 | 首末比 |
|---|---|---|---|
| 修復前 | 約 2,070 KB | 2896 → 6573 ms | 2.27x |
| 修復後 | **401 KB** | 2139 → 2097 ms | **0.98x** |

> **邊界講清楚，不要讓人自己猜。** Pixel 7 模擬是「帶著手機 viewport、DPR 跟 user agent 的 Chromium」，不是手機。JS heap 跟主執行緒成本可以外推到 Android Chrome，因為那是同一個引擎；但 GPU 合成、熱節流、OS 層級的記憶體壓力都不行。
>
> 耗時有量，但**刻意不拿來斷言**：同樣的情境在閒置機器上跑兩次，首末比一次 1.64x、一次 2.12x，這個離散度比要偵測的效應本身還大。retained heap 三次量下來只差約 7%，所以閘設在那裡 —— **門檻落在雜訊帶裡面，測到的是 CI 排程器，不是你的程式碼。**

### 如何執行

```bash
cd trading-engine-ui
npm install
npm run dev
# 開啟 http://localhost:3000
```

在 `.env.local` 設定後端 URL：
```env
NEXT_PUBLIC_API_URL=http://localhost:8092
NEXT_PUBLIC_WS_URL=ws://localhost:8093
```

### 技術堆疊

| 工具 | 用途 |
|---|---|
| Next.js 15 | React 框架 |
| TypeScript | 型別安全 |
| Tailwind CSS | 樣式 |
| TradingView Lightweight Charts | K 線圖 |
| WebSocket | 即時訂單串流 |
| Playwright | mobile-web 耐久測試（Pixel 7 模擬、CDP 量 heap） |

---

## 模組 4 — 可觀測性與可靠度平台

以本專案 2026 年 7 月兩次真實事故為核心建置的 Prometheus / Grafana 平台。
**每一條告警的閾值都由那兩次事故的實測值反推**，不是套用預設值。

### 為什麼需要它

兩次事故有一個共同點：**服務壞掉的時候，所有傳統健康檢查都是綠的。**

| 檢查方式 | 事故 #1（GC 死亡螺旋）| 事故 #2（靜默降級）|
|---|---|---|
| `systemctl status` | `active (running)` ✅ | `active (running)` ✅ |
| TCP port 已 bind | 是 ✅ | 是 ✅ |
| `GET /api/v1/status` | 逾時 ❌ | `200 OK` ✅ |
| K8s liveness probe | 會失敗 ❌ | **會通過** ✅ |
| **實際業務產出** | **零** | **零，持續六天** |

事故 #2 更難處理：那六天之中服務**正確地回著 `200 OK`**，卻什麼都沒產出。
任何問「服務有沒有回應」的檢查都偵測不到。
這個平台的答案是量測**工作有沒有在前進**，而不是進程有沒有活著。

完整根因分析：[`docs/incident-2026-07-14-gc-death-spiral/`](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)
（含保留的現場證據與 SHA256 manifest）。

### 架構

```
Layer 4   Grafana ───── SRE 總覽 · 容量規劃 · JVM 事故重現
                             ▲
Layer 3   Alertmanager ── 分級路由 · 4 條抑制規則 · → Runbook
                             ▲
Layer 2   Prometheus ──── 24 條規則 / 6 組 · 30 天保留
                             ▲
Layer 1   採集 ────────── node_exporter（+textfile）· blackbox · mysqld · redis
                             ▲
Layer 0   被監控 ───────── payment-api · trading-engine · MySQL · Redis · 主機
```

全部服務使用 `network_mode: host`。主機的 iptables 會擋掉 container → host
的流量，而 MySQL / Redis 都只綁 `127.0.0.1`；host 網路模式讓所有元件走
loopback 互通。exporter 一律綁 `127.0.0.1`（它們會吐出資料庫內部狀態），
只有 UI 層對外開放。

### 元件

| 服務 | 埠 | 綁定 | 角色 |
|---|---|---|---|
| Prometheus | 9090 | `0.0.0.0` | 採集與規則評估 |
| Alertmanager | 9093 | `0.0.0.0` | 路由、分組、抑制 |
| Grafana | 3001 | `0.0.0.0` | 儀表板（3000 被 `next dev` 佔用）|
| node_exporter | 9100 | `0.0.0.0` | 主機指標 + textfile collector |
| blackbox_exporter | 9115 | `127.0.0.1` | 黑箱探測 |
| mysqld_exporter | 9104 | `127.0.0.1` | MySQL 指標 |
| redis_exporter | 9121 | `127.0.0.1` | Redis 指標 |

JVM 指標由 [`jstat-exporter.sh`](deploy/observability/jstat-exporter.sh)
採樣寫入 textfile collector，而不是走 JMX agent：被觀測的那支 JVM 啟動時
沒有帶 `-javaagent`，而重啟它會毀掉修復後那段乾淨的對照運行紀錄。

### 告警設計

24 條規則分 6 組，每一組回答一個不同的問題：

| 組 | 問題 | 條數 |
|---|---|---|
| `availability` | 使用者現在打得到嗎？（黑箱）| 3 |
| `work-progress` | 工作有在前進嗎？（事故 #2）| 3 |
| `jvm-gc` | JVM 還健康嗎？（事故 #1）| 4 |
| `saturation` | 資源快用完了嗎？（USE 方法）| 4 |
| `capacity` | **多久之後**會用完？（`predict_linear`）| 4 |
| `dependencies` | MySQL 與 Redis 還在嗎？| 6 |

**閾值全部由事故實測值反推：**

| 訊號 | 閾值 | 事故當時的實測值 |
|---|---|---|
| Full GC 佔行程存活時間比 | > 10% | **70%**（491,218s STW / 698,732s uptime）|
| 老年代使用率 | > 85% | **99.99%** |
| Full GC 累計次數 | 速率 > 0.1/s | **114,879 次** |
| 訂單產生速率 | 連續 10 分鐘為 0 | 正常為 **1,198 筆/分**（≈20/秒）|

### 告警路由

`critical` 立即通知；`capacity` 類（預測 24 小時後才會發生的問題）刻意
路由到不吵人的管道。4 條抑制規則避免一次故障噴出數十則通知：

1. `HostDown` 抑制該主機上所有其他告警
2. 同服務的 `critical` 抑制 `warning`
3. `JvmGcTimeRatioHigh` 抑制 `JvmOldGenHigh`（症狀鏈收斂）
4. `ServiceDown` 抑制 `EngineNotProgressing`

> 告警疲勞比沒有告警更危險。值班的人如果每晚收 200 則通知，
> 第 201 則真的事故就會被忽略。

### Runbook —— 強制，不是口號

**每一條告警都必須有 `runbook_url`，缺少就無法通過 CI。**
[`tools/check-alert-runbooks.sh`](tools/check-alert-runbooks.sh) 檢查三項不變式：

- **R1** 每條告警都有 `runbook_url`
- **R2** 該 URL 指向的檔案確實存在
- **R3** 沒有孤兒 runbook（存在卻沒有任何告警引用）

13 份 runbook 覆蓋全部 24 條告警，每份都是同樣六段：
觸發條件 · 影響 · 立即確認（前三分鐘）· 止血 · 根因調查 · 事後。
見 [`docs/runbooks/`](docs/runbooks/README.md)。

> 沒有處理 SOP 的告警，等於把問題丟給半夜三點被叫起來的人自己想。
> 那是告警設計的失職，不是值班的問題。

### 儀表板

| 儀表板 | 內容 |
|---|---|
| **SRE 總覽** | 可用性 · 工作進度 · JVM · 相依元件 · 主機（5 分區 / 31 面板）|
| **容量規劃** | 磁碟、heap、Redis、主機的 `predict_linear` 外推 |
| **Trading Engine JVM** | 事故 #1 的專用重現視圖 |

### 事故現場保全

[`tools/preserve-scene.sh`](tools/preserve-scene.sh) 用一個指令完成
非破壞性的證據採集 —— `/proc`、`jstat`、systemd journal、黑箱探測、
資料庫界限 —— 並產生 SHA256 manifest。

兩個刻意的設計決策：

- **`jstat` 逾時會被記錄成證據，而不是採集失敗。**
  事故 #1 當下 `jcmd` 與 `jstack` 都無法 attach，因為 JVM 的 attach
  handshake 執行緒本身也被 GC 餓死了。那個失敗本身就是確診訊號。
- **資料庫查詢一律用水位線界定範圍，絕不全表掃描。**
  `orders` 表已有數千萬筆且持續寫入中；全表掃描會在共用主機上與
  線上寫入搶 IO。

### 快速開始

```bash
cp deploy/observability/mysqld/.my.cnf.example deploy/observability/mysqld/.my.cnf
$EDITOR deploy/observability/mysqld/.my.cnf && chmod 600 deploy/observability/mysqld/.my.cnf

make obs-up          # 啟動平台
make obs-status      # 容器 · 採集目標 · 進行中的告警
make obs-validate    # promtool + amtool 設定驗證
make obs-reload      # 熱載入規則，不重啟容器
make runbooks        # 告警 ↔ runbook 覆蓋檢查
make preserve        # 採集事故現場快照
```

Grafana：`http://localhost:3001`。在遠端主機上請用 SSH 通道，不要開防火牆：

```bash
ssh -L 3001:127.0.0.1:3001 -L 9090:127.0.0.1:9090 -L 9093:127.0.0.1:9093 user@host
```

### 平台上線第一天抓到的問題

| 告警 | 實際狀況 |
|---|---|
| `EngineWorkerStopped` | 訂單產生器自 **2026-08-26 06:02** 起停擺 —— **七天**無人察覺 |
| `EngineNotProgressing` | 資料庫寫入從 1,198 筆/分 塌到 0 |
| `RedisIsUnbounded` | Redis 以 `maxmemory=0` / `noeviction` 運行 —— 與事故 #1 的無界集合是同一個缺陷類別 |

journal 顯示事故 #2 完整重演：

```
06:02:02  [DB] Save failed ... Communications link failure
06:02:13  systemd: Stopping binance-trading-engine.service
06:02:13  Main process exited, code=exited, status=143/n/a    ← 143 = 128+15 = SIGTERM
06:02:13  systemd: Started binance-trading-engine.service     ← systemd 成功重啟
06:02:14  Engine : STOPPED — press RUN in the UI to start     ← 但 worker 沒有回來
```

那七天之中，`systemctl` 全程回報 `active (running)`、8092/8093 都有 bind、
`/api/v1/status` 回 `200`。唯一抓得到它的訊號是業務產出的速率。

---

## 安全與憑證管理

這是一個**公開** repo。任何提交進來的東西都是永久公開的 ——
就算之後刪掉，git 歷史仍然保留。

### 憑證絕不寫死在程式裡

`DBOrderRepository` 在 `DB_PASSWORD` 未設定時會**拒絕啟動**，沒有 fallback
預設值。寧可大聲失敗，也不要靜默地用一個所有人都讀得到的密碼連上資料庫。

| 元件 | 憑證來源 | 權限 |
|---|---|---|
| trading-engine（systemd）| `/etc/binance-trading-engine.env`，經 `EnvironmentFile=` 注入 | `0640 root:ubuntu` |
| `tools/check-db-integrity.sh` | `DB_PASSWORD` 環境變數，或 `deploy/observability/mysqld/.my.cnf` | `0600` |
| `tools/preserve-scene.sh` | 同上（沒有憑證時跳過 DB 採集，不中止整支腳本）| `0600` |
| mysqld_exporter | `deploy/observability/mysqld/.my.cnf` | `0600` |

範本檔（`*.example`）進版控，真實憑證不進。
設定步驟見 [`deploy/systemd/README.md`](deploy/systemd/README.md)。

**憑證也不寫在 systemd unit 裡。** unit 檔的權限是 `0644` ——
主機上任何使用者都讀得到。把 `Environment=DB_PASSWORD=…` 寫在那裡，
只是把密碼從 git 搬到 `/etc` 而已。

**腳本一律用 `--defaults-extra-file`，不用 `-p`：**

```bash
mysql -u user -p"$PASSWORD"      # ✗ 密碼會出現在 `ps` 的輸出裡
mysql --defaults-extra-file=…    # ✓ 只由檔案權限保護
```

### 由 CI 強制

[`tools/check-no-secrets.sh`](tools/check-no-secrets.sh) 是 CI 的**第一個**
job，其他所有 job 都相依於它。檢查四項不變式：

- **S1** 已知的憑證檔沒有被 git 追蹤
- **S2** 已追蹤的檔案裡沒有高風險機密樣式 —— 私鑰、AWS / GitHub / Slack token、
  帶引號的密碼字面值，以及 `${DB_PASSWORD:-<真實值>}` 這類 shell 預設值展開
- **S3** 每個憑證檔都有對應的 `.example` 範本
- **S4** 範本裡放的是佔位符，不是真實值

例外記錄在 [`.secretsignore`](.secretsignore)，而且**每一條都必須附上理由**
—— 沒有理由的例外就是漏洞。

### 已知的曝險

本 repo 早期版本曾把**本機測試資料庫**（`binance_test_db`）的密碼寫死在
程式裡。它已從工作目錄移除，但仍留在 git 歷史中，必須視為已公開。

實際曝險為零：MySQL 只綁 `127.0.0.1`，主機防火牆除了 22 埠之外全部拒絕，
該憑證無法從機器外部使用。即便如此仍會進行輪替 ——
**外洩的密碼是靠更換來修復，不是靠刪掉顯示它的那一行。**

---

## Repo 慣例（Conventions）

| 設定 | 值 |
|---|---|
| `main` 分支保護 | 僅 PR · 4 個必過 CI 檢查 · `enforce_admins: true` · 禁止 force-push 與刪除 · 需解決所有對話 |
| Repo 合併策略 | Squash **停用** · 允許 Merge + Rebase · 合併後自動刪除分支 |
| 建議合併模式 | **Rebase merge** —— 讓 P1 → P2 → P3 commits 在 `main` 上保持線性敘事 |
| CI 觸發 | `push` 到 `main`/`develop` · `pull_request` 到 `main` |
| 必過檢查 | `Secret Scan` · `Observability Config` · `Java Tests` · `UI Build Check (Next.js 15)` |

> 這個作品集是一步一步分階段重構（phased refactor）做出來的。`git log --oneline main` 會照時間順序，把從空殼 payment-api 到真實 ACID 服務的四個步驟攤開給你看 —— 這份 commit log 本身就是一份設計文件。

---

## 技術堆疊（全部模組）

| 工具 | 用途 |
|---|---|
| Java 17 | 後端語言 |
| Maven | 建置與相依管理 |
| JUnit 5 | 測試框架 |
| Mockito | 單元測試 mocking |
| RestAssured | HTTP API 斷言 |
| JDBC | 與驅動無關的 DB 存取（`java.sql`）— 由 `JdbcPaymentRepository` 使用 |
| H2 | 記憶體資料庫（MySQL 模式） |
| MySQL 8 | 持久化訂單儲存 |
| Allure | 測試報告產生 |
| GitHub Actions | CI/CD |
| Next.js 15 | 前端框架 |
| TypeScript | 前端型別安全 |
| Tailwind CSS | UI 樣式 |
| Playwright | mobile-web e2e 與耐久測試 |
| Prometheus | 指標採集、告警規則評估 |
| Alertmanager | 告警路由、分組與抑制 |
| Grafana | 儀表板（SRE 總覽、容量規劃）|
| Docker Compose | 可觀測性堆疊編排 |
| Bash / Make | 操作介面、CI 閘門、事故現場保全 |
