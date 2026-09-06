# Trading Engine Reliability

[English](README.md) | **繁體中文**

本專案在一台自架的 ARM64 主機上，完整實作可靠度工程（reliability engineering）的各個環節：一支 Java 支付 API 與一個 BTC 交易引擎模擬器，經容器化後以**手寫的 Helm chart 部署至 k3s**，並由 **Prometheus / Alertmanager / Grafana** 監控——所有告警閾值均由**同一套基礎設施上實際發生過的兩次事故**反推而得，再輔以一組 CI 閘門，防止相同的根因再次被引入。

![CI](https://github.com/benson-code/trading-engine-reliability/actions/workflows/ci.yml/badge.svg)

### 專案緣起

作者從事支付與金融領域 QA 十年，經歷支付閘道、電商平台，以及 Tier-1 銀行的卡片支付整合。經驗顯示，真正造成重大損失的從來不是正常流程（happy path），而是**靜默的後端失敗（silent backend failures）**：扣款已提交（commit），對應的付款紀錄卻未寫入；高負載下的重試造成重複扣款；兩個服務之間的結算狀態不一致。要找出這類問題，往往必須在事後透過 Oracle SQL、JDBC 與 Linux 日誌逐行追查。

本專案將這些實務經驗轉化為**可於資料庫層執行的證明**：在正式環境中追查過的 ACID 回滾、exactly-once idempotency、競態條件（race condition）等情境，均重現為自動化測試——一旦不變量（invariant）被破壞，測試即明確失敗，使缺陷在 CI 階段被攔截，而非等到對帳報表才浮現。

### 主要特色

- **Kubernetes 部署經過實測演練，而非僅止於宣告** —— 支付 API 以兩個副本執行於自架的單節點 **k3s v1.36** 叢集，由手寫的 **Helm chart** 部署：probe 與資源配額均由 values 驅動，並以 ConfigMap checksum annotation 確保設定變更確實觸發 rollout，而非靜默地未生效。`maxUnavailable: 0` 是宣告而非保證——在持續流量下進行滾動更新演練，實測 **580 筆請求中有 2 筆非 200（0.34%）**，根因為 `SIGTERM` 與 endpoint 移除傳播至各節點 iptables 之間的競態。加入 `preStop` 延遲並搭配對應的 `terminationGracePeriodSeconds` 後重測：**680 筆請求，0 筆失敗**（[`tools/k8s-rollout-drill.sh`](tools/k8s-rollout-drill.sh)）。
- **真實服務、真實資料庫、真實 ACID** —— `JdbcPaymentRepository.createPayment` 將餘額扣款與付款寫入置於**同一個交易（transaction）**中；`UNIQUE(idempotency_key)` 為併發情境下的最後防線。競爭失敗的重試會被回滾——**連同其扣款一併撤銷**——因此無論收到多少次重試，帳戶僅扣款一次（[`JdbcPaymentRepositoryTest`](payment-api/src/test/java/com/binance/payment/db/JdbcPaymentRepositoryTest.java)）。
- **併發安全性經實測驗證，而非僅為斷言** —— 16 個執行緒以相同的 idempotency key 同時呼叫 `createPayment`；測試（[`ConcurrentIdempotencyTest`](payment-api/src/test/java/com/binance/payment/concurrency/ConcurrentIdempotencyTest.java)）於**兩種** repository 實作上均驗證：僅扣款一次、僅產生一個 `payment_id`。
- **不以 WireMock 模擬取代真實服務** —— 所有 API 與整合測試均透過內嵌 HTTP server 呼叫**真實的** `PaymentService`，而非 mock 替身；測試通過即代表服務本身確實可運作（[commit `668bfc4`](https://github.com/benson-code/trading-engine-reliability/commit/668bfc4) 記錄了由 mock 遷移至真實服務的過程）。
- **支付等級的輸入驗證與存取控制** —— 幣別須與帳戶一致（`422`）；金額精度限制於 `DECIMAL(18,8)`（`400 INVALID_PRECISION`，不進行靜默截斷）；付款端點在設定後強制要求 `X-API-Key`（以常數時間比較）（[`PaymentAuthTest`](payment-api/src/test/java/com/binance/payment/api/PaymentAuthTest.java)）。
- **將導致後端故障的缺陷類型同樣納入前端測試** —— `useTradingEngine` 曾持有兩個只增不減的集合，其中一個在每次收到訊息時完整複製自身。Pixel 7 耐久測試注入 4 萬筆訂單（約 33 分鐘的 session），驗證 retained heap 未隨之成長：**約 2,070 KB → 401 KB**，各批次耗時的首末比亦由 2.27x 收斂至 0.98x（[`session-retention.spec.ts`](trading-engine-ui/tests/endurance/session-retention.spec.ts)）。
- **服務自我回報，監控系統亦自我監控** —— `payment-api` 與 `trading-engine` 均於 `/metrics` 提供服務自報指標（手寫實作，無 client library）：請求速率、錯誤率，以及 `le="0.25"` 邊界恰與 SLO 門檻對齊的延遲直方圖。在此之前，所有訊號均來自對 `/health` 的黑箱探測——該探測可回報 100% 成功，而 `/payments` 卻對每位呼叫者回應 500。另設**死人開關（dead man's switch）**告警，永久處於 firing 並路由至心跳接收端：**未收到才代表事件發生**——這是唯一能區分「一切正常」與「告警管線已失效」的方法，亦即將兩次事故的形態套用於監控系統本身。告警經 Alertmanager 送達 **LINE**，最後一哩的投遞結果同樣以指標暴露並受告警監控。
- **SLO 與錯誤預算，且其中一項已超支** —— 三個 SLI 分別對應本主機上實際發生過的失效模式：可用性、延遲（250ms）與**工作進度**。三日實測：可用性 **100.0000%**、延遲 **100.0000%**、工作進度 **97.3611%**（目標 99%）——**超支 163.9%**。2026-09-03 引擎有 115 分鐘完全無產出，而同期 `probe_success` 全程為 1、延遲維持約 1ms、行程未曾重啟：**前兩項 SLI 全程回報完美**。其上另有 6 條多視窗多燒錄率告警（14.4x 即時通知、6x 開立工單）（[`docs/slo.zh-TW.md`](docs/slo.zh-TW.md)）。
- **可觀測性建構於真實事故之上** —— 42 條 Prometheus 告警規則，閾值均由兩次實測事故反推 · 15 份 runbook，覆蓋率由 CI 強制 · Alertmanager 分級路由與 4 條抑制規則 · 單一指令完成事故現場保全。
- **兩層彼此獨立的測試，均針對實際執行中的服務** —— 除 CI 中的 Java 測試外，另有一套 Python（`pytest`）契約／驗證／idempotency／併發測試，以及 `k6` 負載腳本。兩者皆自行啟動用後即棄的實例、綁定作業系統指派的埠號，並以 `nice -n 15` 執行，因此不會干擾、亦不會寫入本主機上長期運行的服務（[`python-qa/`](python-qa/README.md)）。
- **品質由 CI 強制執行** —— CI 執行 113 個 Java 測試與一套 mobile-web 耐久測試 · 宣告式的 `BOUNDED-BY` 閘門，對任何缺乏淘汰機制且未說明為何不會無限成長的長生命週期集合直接阻擋 · `main` 分支保護對管理員同樣生效 · 僅接受 PR · 五項必要檢查須全數通過（機密掃描優先）· 以 rebase-merge 保留 P1/P2/P3 的 commit 脈絡。

---

## 文件導覽

所有文件都有英文與繁體中文兩個版本。**英文使用預設檔名，中文加上 `.zh-TW` 後綴**，
每一頁的最上方都有切換連結。

| 文件 | English | 繁體中文 |
|---|---|---|
| 專案總覽 | [`README.md`](README.md) | [`README.zh-TW.md`](README.zh-TW.md) |
| SLO 與錯誤預算 | [`docs/slo.md`](docs/slo.md) | [`docs/slo.zh-TW.md`](docs/slo.zh-TW.md) |
| Runbook 索引 | [`docs/runbooks/README.md`](docs/runbooks/README.md) | [`docs/runbooks/README.zh-TW.md`](docs/runbooks/README.zh-TW.md) |
| 15 份告警 runbook | `docs/runbooks/*.md` | `docs/runbooks/*.zh-TW.md` |

**以下部分尚未翻譯**：完整事故 RCA
（[`RCA-zh-TW.md`](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)，約 1,000 行）
與資源安全檢查表目前只有中文版。但英文讀者不會卡住 ——
事故當下寫的鑑識報告本來就是英文：
[`RCA_REPORT.md`](docs/incident-2026-07-14-gc-death-spiral/evidence/RCA_REPORT.md)、
[`INCIDENT_REPORT.md`](docs/incident-2026-07-14-gc-death-spiral/evidence/INCIDENT_REPORT.md)、
[`LOG_EVENT_ANALYSIS.md`](docs/incident-2026-07-14-gc-death-spiral/evidence/LOG_EVENT_ANALYSIS.md)。

---

## 儲存庫結構（Repository Structure）

```
trading-engine-reliability/        ← Monorepo 根目錄（Maven parent POM）
├── payment-api/                   ← 模組 1：可執行的支付 API + QA 測試（Java 17, 46 tests）
├── trading-engine-simulator/      ← 模組 2：BTC 交易引擎（Java 17, CI 58 tests / 含 MySQL 66 tests）
├── trading-engine-ui/             ← 模組 3：即時儀表板（Next.js 15）+ mobile-web 耐久測試
├── python-qa/                     ← 模組 5：pytest 契約測試 + k6 壓測腳本（hermetic）
├── deploy/
│   ├── helm/                      ← k3s 部署用的 Helm chart（probe、preStop、滾動更新策略）
│   ├── observability/             ← 模組 4：Prometheus · Alertmanager · Grafana · exporters
│   └── systemd/                   ← 服務 unit 與憑證管理
├── docs/
│   ├── incident-2026-07-14-*/     ← 事故 RCA，含保留證據與 SHA256 manifest
│   └── runbooks/                  ← 15 份告警處理 SOP（覆蓋率由 CI 強制）
└── tools/                         ← CI 品質閘門 + 事故現場保全
```

**單一指令執行全部 113 個 Java 測試：**
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
| 整合 / E2E | 對真實服務做完整流程 + 非同步結算 | RestAssured，內嵌 JDK HTTP server |
| 併發 | N 執行緒 idempotency 競賽 → 剛好扣一次 | ExecutorService，兩種 repo |
| 耐久 | 長時間執行下，job 與 idempotency 儲存都維持有上限 | JUnit 5，直接檢查儲存內容 |
| 指標 | 敵意 method 名稱下標籤基數維持有界；直方圖攜帶 250ms 的 SLO 邊界 | JUnit 5，1 萬種 method 名稱模糊測試 |

**總計：52 個測試案例**（6 個指標基數與輸出格式 + 16 個單元/API/idempotency 基線 + 5 個真實服務 E2E + 6 個 JDBC ACID 與負向路徑 + 3 個欄位長度與 HTTP 狀態碼準確性 + 4 個幣別相符 + 4 個金額精度 + 5 個 API-key 認證 + 3 個耐久/滯留）

> 所有 API、整合跟併發測試，均透過內嵌 HTTP server 呼叫真正的
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
# 從 repo 根目錄 — 執行全部 113 個測試（兩個模組）
mvn test

# 僅執行支付模組
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

一個 BTC/USDT 交易引擎，以 61 個自動化測試、MySQL 持久化跟即時 WebSocket 串流，把 4 種 LeetCode 演算法模式實際跑給你看。

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
Tests run: 61, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

# 本機含 MySQL：
Tests run: 69, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
```

`DBValidationTest` 是在 `@BeforeAll` 裡用 `Assumptions.assumeTrue` 自我把關。容器層級的 assumption
失敗會中止整個 class，所以 surefire 記的是 `Tests run: 0`，而不是 8 個 skipped —— CI 為 61、本機為
69，而非 61 + 8 skipped。

> 本機那個數字的前提是 `binance_test_db` **剛建好**。如果 DB 裡已經累積了先前長時間跑引擎留下的訂單，
> `buySellRatioIsBalanced` 會失敗 —— 那個失敗正是 2026-07 事故在資料上留下的痕跡，分析見
> [RCA §8.1](docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)。

| 測試套件 | 測試數 | CI | 本機（MySQL） | 說明 |
|---|---|---|---|---|
| 單元 | 44 | ✅ | ✅ | OrderBook、OrderCache、AmountValidator、TradingEngine |
| API | 7 | ✅ | ✅ | RestAssured 對真實內嵌 server |
| 整合 | 4 | ✅ | ✅ | 端到端：4 種模式一起驗證 |
| 耐久 | 3 | ✅ | ✅ | `OrderBookRetentionTest` —— 持續負載下集合維持有上限 |
| 指標 | 3 | ✅ | ✅ | `EngineMetricsTest` —— /metrics 輸出格式、有界的保留量哨兵、無 `jvm_*` 洩漏 |
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

# 執行測試（不需外部 DB）
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
Layer 4   Grafana ───── SRE 總覽 · SLO 與錯誤預算 · payment-api RED · 容量規劃 · JVM
                             ▲
Layer 3   Alertmanager ── 分級路由 · 4 條抑制規則 · → LINE / 心跳 · → Runbook
                             ▲
Layer 2   Prometheus ──── 42 條規則 / 9 組 · 30 天保留
                             ▲
Layer 1   採集 ────────── node_exporter（+jstat textfile）· blackbox（+合成交易）· mysqld · redis
                          · payment-api 與 trading-engine /metrics · alert-notifier /metrics
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

42 條規則分 9 組，每一組回答一個不同的問題：

| 組 | 問題 | 條數 |
|---|---|---|
| `availability` | 使用者目前是否能連線？（黑箱）| 3 |
| `work-progress` | 工作有在前進嗎？（事故 #2）| 3 |
| `jvm-gc` | JVM 還健康嗎？（事故 #1）| 4 |
| `saturation` | 資源快用完了嗎？（USE 方法）| 4 |
| `capacity` | **多久之後**會用完？（`predict_linear`）| 4 |
| `dependencies` | MySQL 與 Redis 是否存活？| 6 |
| `meta` | 監控系統本身是否存活？（死人開關、採集器凍結、最後一哩、設定重載）| 11 |
| `application` | 請求是否確實成功？（服務自報的 RED）| 1 |
| `slo-burn-rate` | 錯誤預算消耗速度如何？（`slo.yml`）| 6 |

**閾值全部由事故實測值反推：**

| 訊號 | 閾值 | 事故當時的實測值 |
|---|---|---|
| Full GC 佔行程存活時間比 | > 10% | **43.8%**（491,218s STW / 1,120,514s uptime）|
| 老年代使用率 | > 85% | **99.99%** |
| Full GC 累計次數 | 速率 > 0.1/s | **114,879 次** |
| 訂單產生速率 | 連續 10 分鐘為 0 | 正常為 **1,198 筆/分**（≈20/秒）|

### 告警路由

`critical` 立即通知；`capacity` 類（預測 24 小時後才會發生的問題）刻意
路由至非即時管道。4 條抑制規則避免單一故障產生數十則通知：

1. `HostDown` 抑制該主機上所有其他告警
2. 同服務的 `critical` 抑制 `warning`
3. `JvmGcTimeRatioHigh` 抑制 `JvmOldGenHigh`（症狀鏈收斂）
4. `ServiceDown` 抑制 `EngineNotProgressing`

> 告警疲勞比沒有告警更危險。值班的人如果每晚收 200 則通知，
> 第 201 則真正的事故即會被忽略。

### Runbook —— 強制，不是口號

**每一條告警都必須有 `runbook_url`，缺少就無法通過 CI。**
[`tools/check-alert-runbooks.sh`](tools/check-alert-runbooks.sh) 檢查三項不變式：

- **R1** 每條告警都有 `runbook_url`
- **R2** 該 URL 指向的檔案確實存在
- **R3** 沒有孤兒 runbook（存在卻沒有任何告警引用）

15 份 runbook 覆蓋全部 42 條告警，每份都是同樣六段：
觸發條件 · 影響 · 立即確認（前三分鐘）· 止血 · 根因調查 · 事後。
見 [`docs/runbooks/`](docs/runbooks/README.zh-TW.md)。

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

## 模組 5 — Kubernetes 部署

這個服務也跑在 Kubernetes 上，用 Helm 部署。有意思的不是「部署成功」，
而是**量測部署過程之後發現了什麼**。

### 內容

| 路徑 | 說明 |
|---|---|
| [`deploy/helm/payment-api/`](deploy/helm/payment-api/) | Helm chart —— 副本數、image、資源、probe 時間全部由 values driven |
| [`deploy/k8s/payment-api.yaml`](deploy/k8s/payment-api.yaml) | 同樣的部署，純 manifest 版本，方便不裝 Helm 直接閱讀 |
| [`tools/k8s-rollout-drill.sh`](tools/k8s-rollout-drill.sh) | 滾動更新演練：持續流量下計算使用者可見的失敗 |
| [`tools/check-k8s-manifests.sh`](tools/check-k8s-manifests.sh) | CI 閘門：lint、算繪、probe 政策 |

目標是同一台 ARM64 主機上的**自架單節點 k3s** —— 規模小到可以誠實描述，
但足夠操作到真正重要的機制。

```bash
make k8s-image     # docker save | k3s ctr images import（k3s 用 containerd）
make k8s-deploy    # helm upgrade --install --wait
make k8s-status    # 節點 · pod · service · helm release
make k8s-drill     # 持續流量下的滾動更新演練
make k8s-lint      # CI 跑的同一道閘門
```

### 三支 probe，三種不同的職責

這裡是模組 4 的兩次事故回頭影響部署設計的地方。

| Probe | 失敗時 | 為什麼要分開設定 |
|---|---|---|
| `startupProbe` | 延後另外兩支 | 冷啟動的 JVM 很慢。沒有它，`livenessProbe` 會在暖機期間把 pod 殺掉，於是永遠起不來。 |
| `livenessProbe` | **殺掉並重啟** pod | 門檻刻意放寬。誤判的代價是銷毀查根因所需的現場證據 —— 事故 #1 就是這樣。 |
| `readinessProbe` | 移出 Service，**不重啟** | 這支才值得投資：把有問題的副本摘出流量，同時留著讓人去查。 |

**Kubernetes 這兩次事故都救不了。** GC 死亡螺旋那次，JVM 自己的 heap 上限
（`MaxRAMPercentage=75`）低於容器的記憶體 limit，所以容器永遠碰不到 cgroup
限制，不會被 `OOMKilled`。靜默降級那次，健康端點全程正確回 `200` ——
每一支 probe 都會通過。這正是模組 4 的告警要量測**工作進度**而不是
存活狀態的原因。

### 滾動更新演練

`maxUnavailable: 0` 是宣告，不是保證。演練在持續流量下執行滾動更新，
計算**客戶端實際看到**的結果：

```
第一次     580 筆請求    2 筆非 200   (0.34%)
修正後     680 筆請求    0 筆非 200   (0.00%)
```

**那 0.34% 的成因：** pod 進入 `Terminating` 時，`SIGTERM` 和「從 endpoint
移除」是同時發生的。移除要傳播到每個節點的 iptables 規則，而容器在傳播
完成前就關閉了 —— 流量仍被送往一個已經關掉的 socket。

**修法：** 加上足以涵蓋傳播時間的 `preStop` sleep，並把
`terminationGracePeriodSeconds` 設在它之上。

> 這個缺陷在 manifest 上完全看不出來，只有在持續流量下才會現形。
> 這就是「部署要演練，不能只用 review」的理由。

### CI 閘門

`tools/check-k8s-manifests.sh` 在 CI 執行，檢查四件事：

- **K1** `helm lint` 通過
- **K2** chart 能實際算繪（lint 只看語法，缺少的 values 要算繪才會現形）
- **K3** 算繪出的每份文件都是合法的 Kubernetes 物件
- **K4** **每個容器都宣告了 liveness 與 readiness probe**

K4 是本專案特有的政策。沒有 probe 的 Deployment 等於主張
「進程活著就是健康」—— 那正是兩次事故背後的根本誤判。

---

## 安全與憑證管理

這是一個**公開** repo。任何提交的內容均為永久公開 ——
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
| `main` 分支保護 | 僅 PR · 5 個必過 CI 檢查 · `enforce_admins: true` · 禁止 force-push 與刪除 · 需解決所有對話 |
| Repo 合併策略 | Squash **停用** · 允許 Merge + Rebase · 合併後自動刪除分支 |
| 建議合併模式 | **Rebase merge** —— 讓 P1 → P2 → P3 commits 在 `main` 上保持線性敘事 |
| CI 觸發 | `push` 到 `main`/`develop` · `pull_request` 到 `main` |
| 必過檢查 | `Secret Scan` · `Kubernetes Manifests` · `Observability Config` · `Java Tests` · `UI Build Check (Next.js 15)` |

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
| Kubernetes (k3s) | 容器編排 —— Deployment、Service、ConfigMap、probe |
| Helm | 上述部署的樣板化與參數化 |
| Bash / Make | 操作介面、CI 閘門、事故現場保全 |
