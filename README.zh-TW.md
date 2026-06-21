# Binance QA Suite

[English](README.md) | **繁體中文**

一套完整流程（full-cycle）的幣安 QA 作品集，包含三塊：一支可以實際跑起來的支付 API，具備真實的交易型 ACID 跟高併發下的 idempotency（冪等性）；一個搭配 MySQL 做持久化的即時 BTC 交易引擎模擬器；還有一個即時的 Next.js 儀表板。

![CI](https://github.com/benson-code/binance-qa-suite/actions/workflows/ci.yml/badge.svg)

### 為什麼有這個專案

做 QA 這 10 年，我跑過支付閘道、電商平台，還有一間 Tier-1 銀行的卡片支付整合。真正會出大事的，從來都不是 happy path（正常流程），而是那種**悶不吭聲的後端失敗（silent backend failures）**：扣款明明已經 commit 了，對應的 payment 資料卻沒寫進去；高負載下重試一下就重複扣款；兩個服務之間的結算狀態對不起來。要抓到這些，常常得事後撈 Oracle SQL、JDBC，再配上 Linux log 一行一行追。

這個 repo 就是把那份用代價換來的直覺，變成**資料庫層跑得出來的證明**：那些我在正式環境追過的 ACID rollback、exactly-once（剛好一次）idempotency、race condition（競態條件）情境，這裡通通重現成自動化測試 —— 只要不變量（invariant）一被破壞，測試就會大聲喊出來，讓 bug 在 CI 階段就被擋下，而不是拖到對帳報表才爆出來。

### 重點亮點

- **真服務、真資料庫、真 ACID** —— `JdbcPaymentRepository.createPayment` 把餘額扣款跟 payment 寫入放在**同一個交易（transaction）**裡；`UNIQUE(idempotency_key)` 則是併發時的最後一道防線。萬一某個重試在競爭中輸了，它會 rollback —— **連自己剛剛的扣款也一起撤掉** —— 所以不管收到幾次重試，帳戶就是剛好扣一次（[`JdbcPaymentRepositoryTest`](payment-api/src/test/java/com/binance/payment/db/JdbcPaymentRepositoryTest.java)）。
- **併發是「測出來」的，不是嘴上講講** —— 16 條執行緒拿同一個 idempotency key 去打 `createPayment`；測試（[`ConcurrentIdempotencyTest`](payment-api/src/test/java/com/binance/payment/concurrency/ConcurrentIdempotencyTest.java)）在**兩種** repository 實作上都驗證了：就是扣一次、就是只有一個 `payment_id`。
- **不搞 WireMock 那套假把戲** —— 每一個 API 跟整合測試都是透過內嵌 HTTP server 去打**真正的** `PaymentService`，而不是 mock 出來的替身，所以測試全綠就代表服務本身真的跑得動（[commit `668bfc4`](https://github.com/benson-code/binance-qa-suite/commit/668bfc4) 就是從 mock 遷移到真實服務的過程）。
- **支付等級的輸入跟權限把關** —— 幣別一定要跟帳戶一致（`422`）；金額精度卡在 `DECIMAL(18,8)`（`400 INVALID_PRECISION`，不會偷偷截斷）；支付端點只要有設定，就一定要帶 `X-API-Key`（用常數時間比較，constant-time）（[`PaymentAuthTest`](payment-api/src/test/java/com/binance/payment/api/PaymentAuthTest.java)）。
- **品質靠 CI 強制把關** —— CI 一次跑 98 個測試 · `main` 上了連 admin 都擋不掉的分支保護 · 只能走 PR · 兩個必過的檢查一定要全綠 · 用 rebase-merge 保留 P1/P2/P3 的 commit 故事線。

---

## 儲存庫結構（Repository Structure）

```
binance-qa-suite/                  ← Monorepo 根目錄（Maven parent POM）
├── payment-api/                   ← 模組 1：可執行的支付 API + QA 測試（Java 17, 43 tests）
├── trading-engine-simulator/      ← 模組 2：BTC 交易引擎（Java 17, CI 55 tests / 含 MySQL 63 tests）
└── trading-engine-ui/             ← 模組 3：即時儀表板（Next.js 15）
```

**一行指令跑完全部 98 個 Java 測試：**
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

**總計：43 個測試案例**（16 個單元/API/idempotency 基線 + 5 個真實服務 E2E + 6 個 JDBC ACID 與負向路徑 + 3 個欄位長度與 HTTP 狀態碼準確性 + 4 個幣別相符 + 4 個金額精度 + 5 個 API-key 認證）

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
    │       ├── PaymentRepository.java          （介面 — 抽換接縫）
    │       ├── InMemoryPaymentRepository.java  ← 可執行實作（P1）
    │       ├── JdbcPaymentRepository.java      ← 真實 ACID 實作（P3）
    │       └── PaymentService.java
    └── test/java/com/binance/payment/
        ├── unit/PaymentServiceTest.java
        ├── api/
        │   ├── PaymentAPITest.java
        │   ├── IdempotencyTest.java
        │   └── PaymentServiceE2ETest.java      ← 對真實 server 的 E2E
        ├── db/
        │   ├── BalanceVerificationTest.java
        │   └── JdbcPaymentRepositoryTest.java  ← 嚴格帳戶 + ACID（P3）
        ├── concurrency/ConcurrentIdempotencyTest.java  ← N 執行緒競賽（P3）
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
# 從 repo 根目錄 — 跑完全部 98 個測試（兩個模組）
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

一個 BTC/USDT 交易引擎，用 55 個自動化測試、MySQL 持久化跟即時 WebSocket 串流，把 4 種 LeetCode 演算法模式實際跑給你看。

### 實作的 LeetCode 模式

| 模式 | 元件 | 演算法 |
|---|---|---|
| LC-217 / LC-347 | OrderBook | HashMap 重複偵測 + 頻率分析 |
| LC-146 | OrderCache | LRU Cache（LinkedHashMap） |
| LC-65 / LC-8 | AmountValidator | 十進位字串驗證 |
| LC-1115 | TradingEngine | 以 Semaphore 做執行緒交替 |

### 測試結果

```
# CI（無 MySQL）：
Tests run: 55, Failures: 0, Errors: 0, Skipped: 8 — BUILD SUCCESS

# 本機含 MySQL：
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

| 測試套件 | 測試數 | CI | 本機（MySQL） | 說明 |
|---|---|---|---|---|
| 單元 | 44 | ✅ | ✅ | OrderBook、OrderCache、AmountValidator、TradingEngine |
| API | 7 | ✅ | ✅ | RestAssured 對真實內嵌 server |
| 整合 | 4 | ✅ | ✅ | 端到端：4 種模式一起驗證 |
| DB 驗證 | 8 | ⏭ 略過 | ✅ | 幣安 QA 風格的 MySQL 檢查（`-Dgroups=db-validation`） |

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

---

## 模組 3 — 交易引擎 UI

幣安風格的即時交易儀表板，透過 WebSocket 連接模組 2。

### 功能

- 即時 K 線圖（TradingView Lightweight Charts，5 秒一根）
- 訂單簿，含重複高亮
- 引擎統計面板（BUY/SELL 計數、cache 命中率、重複數）
- 執行緒監看（BUY/SELL 執行緒活動）
- WebSocket 以指數退避自動重連

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

---

## Repo 慣例（Conventions）

| 設定 | 值 |
|---|---|
| `main` 分支保護 | 僅 PR · 2 個必過 CI 檢查 · `enforce_admins: true` · 禁止 force-push 與刪除 · 需解決所有對話 |
| Repo 合併策略 | Squash **停用** · 允許 Merge + Rebase · 合併後自動刪除分支 |
| 建議合併模式 | **Rebase merge** —— 讓 P1 → P2 → P3 commits 在 `main` 上保持線性敘事 |
| CI 觸發 | `push` 到 `main`/`develop` · `pull_request` 到 `main` |
| 必過檢查 | `Java Tests` · `UI Build Check (Next.js 15)` |

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
