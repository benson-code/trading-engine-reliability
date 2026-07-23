# 資源安全檢查清單（Resource Safety Checklist）

> 本清單源自 2026-07 的 GC 死亡螺旋事故。
> 完整根因分析：[`docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md`](../incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)

---

## 先說清楚：檢查清單「不能保證」不再犯錯

這件事必須誠實面對，否則這份文件會製造虛假的安心感。

**上一次事故的成因，不是「不知道集合要有上限」，而是「沒有人回頭檢視假設」。**

證據：`OrderCache.java:22-28` 用 `removeEldestEntry` 正確設了上限，而它和出事的 `OrderBook`
在同一個套件、同一行建構子裡被建立（`TradingEngine.java:60`）：

```java
this(new OrderBook(), new OrderCache(1000), 200, 0.05);
//   ^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^
//   沒有容量參數       明確指定容量上限
```

**知識當時就在現場，它只是沒有被套用到需要它的那個物件上。**

所以：**一份只靠人記得去讀的檢查清單，防不住這次事故。** 它會被跳過，正如上次那樣。

因此本清單分為三層，**真正的防線是第一層（自動強制），後兩層是輔助**：

| 層級 | 機制 | 能擋住什麼 | 誰執行 |
|------|------|-----------|--------|
| **L1** | CI 自動檢查 | 未經聲明的無界集合 | 機器（無法跳過） |
| **L2** | Code review 清單 | 需要判斷力的設計問題 | 人 |
| **L3** | 部署與監控清單 | 逃過前兩層的問題，縮短偵測時間 | 人（一次性設定） |

---

## L1 — 自動強制（真正的防線）

### 1.1 無界集合檢查

**工具：** [`tools/check-bounded-collections.sh`](../../tools/check-bounded-collections.sh)

**規則：** `src/main` 底下每一個集合型別的**成員欄位**，必須二擇一：

- **(a)** 具備淘汰機制（`removeEldestEntry`、容量上限、Caffeine/Guava 的 `maximumSize` 等），或
- **(b)** 在欄位上方以 `// BOUNDED-BY: <理由>` 明確聲明為何不會無限成長

兩者皆無 → **CI 失敗**。

```bash
# 本機執行
tools/check-bounded-collections.sh
```

**為什麼是「宣告式」而非「推論式」：**

腳本不猜測作者意圖——它做不到，也不該做。它強制作者**明確表態**。

這擋不了「亂寫理由」，但能擋掉「**完全沒想過這件事**」——而後者正是上次事故的成因。
一個必須填寫的 `BOUNDED-BY:` 註解，會強迫作者在寫下那一行時問自己一次：「這東西會長到多大？」

**BOUNDED-BY 的正確與錯誤範例：**

```java
// ✅ 好：說明界線來自何處，可被審查者驗證
// BOUNDED-BY: 僅存放 8 種訂單狀態，數量由 OrderStatus enum 固定
private final Map<OrderStatus, Integer> counters = new EnumMap<>(OrderStatus.class);

// ✅ 好：界線來自生命週期，且指出對應的移除點
// BOUNDED-BY: 連線數上限；onClose() 會 remove（見 L52）
private final ConcurrentLinkedQueue<WebSocket> clients = new ConcurrentLinkedQueue<>();

// ❌ 壞：沒有說明界線來源，無法被審查
// BOUNDED-BY: 應該不會太大
private final Map<String, Order> orders = new ConcurrentHashMap<>();

// ❌ 壞：這正是上次事故的那個理由
// BOUNDED-BY: 分析用途，需要保留完整歷史
private final List<Order> allOrders = new ArrayList<>();
//                                    ↑ 「需要保留完整歷史」不是界線，是無界的定義
```

> ⚠️ **最後那個範例是重點。** 「規格要求保留全部」不能作為 BOUNDED-BY 的理由——
> 那正是上次的根因（RCA 第 6.4 節：`unbounded by specification rather than implementation`）。
> 遇到這種情況，正確做法是**升級為設計決策**：改為滾動視窗、外移到資料庫，或改用概率資料結構。

### 1.2 目前的掃描結果（2026-07-21 實測）

```
已有淘汰機制    : 1
已 BOUNDED-BY 聲明: 11
未聲明（違規）  : 0
```

| # | 位置 | 實際狀態 | 判定 |
|---|------|---------|------|
| 1 | `OrderBook` `orders` | ✅ 已有界化 + BOUNDED-BY 聲明 | 🟢 **已修復 2026-07-23** |
| 2 | `OrderBook` `orderIdFrequency` | ✅ 與 `orders` 同步淘汰 | 🟢 **已修復 2026-07-23** |
| 3 | `OrderBook` `allOrders` → `recentOrders` | ✅ 滾動視窗 + `AtomicLong` 全時計數器 | 🟢 **已修復 2026-07-23** |
| 4 | `PaymentApiServer` `jobs` | ✅ 已有界化（結算狀態為暫時性） | 🟢 **已修復 2026-07-23** |
| 5 | `InMemoryPaymentRepository` `byIdempotencyKey` | ✅ 已有界化，**並在 javadoc 明載取捨** | 🟢 **已修復 2026-07-23** |
| 6 | `InMemoryPaymentRepository` `balances` | ✅ 已聲明。**刻意不淘汰**——淘汰等於憑空重設餘額 | 🟢 **已聲明 2026-07-23** |
| 7 | `InMemoryPaymentRepository` `currencies` | ✅ 已聲明，與 `balances` 同理 | 🟢 **已聲明 2026-07-23** |
| 8 | `TradingWebSocketServer` `clients` | ✅ 已聲明（連線生命週期界定） | 🟢 **已聲明 2026-07-23** |

> 📌 **這張表本身就是這份清單的價值證明。**
> 這個檢查在啟用的第一次執行，就找出了**兩個先前未知、與本次事故同類的缺陷**：
> - `PaymentApiServer.jobs`：每筆付款產生一個 job 條目，**永不移除** → 隨交易量無限成長
> - `InMemoryPaymentRepository.byIdempotencyKey`：每個冪等鍵永久保存 → 隨請求數無限成長
>
> 兩者都在正式路徑上（`Main.java:46` 在無資料庫時使用 `InMemoryPaymentRepository`）。
> 它們的失效模式與 `OrderBook` 完全相同：功能永遠正確，只在時間軸上錯。

### 1.3 接進 CI

在 `.github/workflows/ci.yml` 的 Java Tests job 中加入：

```yaml
      - name: Bounded-collections check
        run: tools/check-bounded-collections.sh
```

> ✅ **已於 2026-07-23 接進 CI**（`.github/workflows/ci.yml`，位於 `mvn test` 之前）。
>
> 啟用順序很重要：先把 8 個違規全部處理完、掃描歸零，才接進 CI。
> 若在還有違規時就接上，所有 PR 都會變紅，團隊會學會忽略紅燈——那比沒有檢查更糟。

### 1.4 耐久測試（endurance test）

**這是唯一能直接偵測「保留集合隨時間成長」的測試型態。**

判斷準則：**當投入量 × 10 時，Full GC 後的堆占用是否也 × 10？**

```java
@Test
@Tag("endurance")
@DisplayName("保留集合不應隨投入量線性成長")
void retainedSetMustNotGrowLinearly() throws Exception {
    OrderBook book = new OrderBook();

    feedOrders(book, 100_000);
    long baseline = usedHeapAfterFullGc();

    feedOrders(book, 900_000);          // 總量 10 倍
    long after = usedHeapAfterFullGc();

    assertTrue(after - baseline < baseline * 0.5,
        String.format("保留堆記憶體隨投入量線性成長（%d KB → %d KB），顯示集合為無界",
                      baseline / 1024, after / 1024));
}
```

**設計要點：**

| 要點 | 理由 |
|------|------|
| 量測 **Full GC 之後**的占用 | GC 前的數字包含大量待回收的短命物件，噪音極大；GC 後剩下的才是真正的保留集合 |
| 用**比例**而非絕對值斷言 | 絕對值會因 JVM 版本與機器規格而異，造成測試不穩定 |
| **兩階段**比較 | 單點量測無法區分「基礎開銷」與「線性成長」；兩點才看得出斜率 |
| ⚠️ `System.gc()` 只是建議 | JVM 可忽略。嚴謹作法：以 `-XX:+UseSerialGC` 執行此測試，或透過 `ManagementFactory` 的 GC notification 確認 Full GC 確實發生 |
| 控制執行時間 | 目標 < 90 秒；超過則用 `@Tag("endurance")` 移至 nightly build |

**狀態：✅ 已實作**（`trading-engine-simulator/src/test/java/com/binance/trading/endurance/OrderBookRetentionTest.java`）

3 個測試、約 3 秒。**並已用對照實驗證明它抓得到缺陷**——把 retention 設為
`Integer.MAX_VALUE` 重現無界行為時：

| 指標 | 有界 | 無界（事故行為） |
|------|------|-----------------|
| 保留筆數 | 1,000 → 1,000 | **1,000 → 100,000** |
| GC 後堆占用 | 幾乎持平 | **29 MB → 70 MB（+139%）** |

兩個斷言都正確地失敗。

> 💡 **這一步不能省。** 一個在有缺陷的程式碼上也會通過的測試，是零價值的。
> 撰寫本測試時第一次的對照實驗用了 `while (false)` 停用淘汰，結果那是 Java 編譯錯誤
> （unreachable statement），build 失敗、讀到的是上一輪的舊報告——差點回報假的「通過」。
> **驗證測試有效性時，務必確認建置真的成功了。**

---

## L2 — Code Review 檢查清單（需要判斷力的部分）

### 2.1 集合與快取

- [ ] 每一個**長生命週期物件**（單例、服務類別、靜態欄位）持有的集合，是否有容量上限或淘汰策略？
- [ ] 集合的 `add` / `put` / `merge` / `computeIfAbsent`，是否有**對應的移除路徑**？
- [ ] 若存在 `clear()` 之類的清除方法，**是否真的有任何地方呼叫它？**
      （上次事故中 `OrderBook.clear()` 存在於 L70-73，但全 repo 零呼叫——**接縫建好了卻沒接上策略**）
- [ ] 名字不叫 `Cache` 的東西也檢查過了嗎？
      （上次事故中，`OrderCache` 是唯一正確的那個；出事的是名字聽起來人畜無害的 `OrderBook`、`allOrders`）

### 2.2 假設的有效期（本次事故的核心教訓）

- [ ] **這個類別原本是為什麼情境設計的？**
- [ ] **現在的使用情境，是否改變了它的前提假設？**
- [ ] 特別是：這段程式碼原本假設「輸入有限、跑完就結束」，現在是否被放進了「輸入無限、永遠執行」的環境？

> 上次事故中，`OrderBook` 的檔案註解明寫著它對應 LeetCode 的 LC-217 / LC-347。
> 在那個框架下（有限陣列、毫秒生命週期），保留全部資料是**正確且必要**的。
> 錯誤發生在它被接進一個 systemd 常駐服務——**輸入從有限變成無限，但假設沒有被重新檢視。**

### 2.3 生命週期與資源

- [ ] 執行緒池、連線池、排程任務是否有明確的關閉路徑？
- [ ] 監聽器 / callback 註冊後是否有反註冊？
- [ ] 任何 `while (true)` 或 `while (running)` 迴圈內，是否有物件被累積到迴圈外的結構中？

---

## L3 — 部署與監控檢查清單

### 3.1 JVM 啟動參數（每一支常駐 JVM 服務都必須具備）

- [ ] `-Xmx<size>` — **明確指定最大堆**，不依賴預設人體工學
      （上次事故：systemd unit 完全沒設，JVM 取實體記憶體 1/4 = 2.91 GiB，洩漏耗時 7.6 天才觸頂）
- [ ] `-XX:+ExitOnOutOfMemoryError` — ⭐ **本次事故最關鍵的缺項**
      OOM 時直接終止 JVM。若當初有此參數，進程會在 07-08 立即結束、`Restart=on-failure` 會被觸發、
      監控會告警——**不會有後續 13 天的靜默空轉**
- [ ] `-XX:+HeapDumpOnOutOfMemoryError` + `-XX:HeapDumpPath=<dir>` — 自動取證
      （上次事故最大的取證損失：事後無法 attach，堆直方圖再也拿不到）
- [ ] `-Xlog:gc*:file=<path>:time,uptime:filecount=5,filesize=20M` — GC 日誌與輪替

**參考完整寫法：**

```ini
ExecStart=/usr/bin/java \
  -Xmx1g \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/<service>/ \
  -Xlog:gc*:file=/var/log/<service>/gc.log:time,uptime:filecount=5,filesize=20M \
  -jar <path>.jar
```

### 3.2 健康檢查（本次事故暴露的最大盲區）

- [ ] 健康檢查是否**實際呼叫服務端點**，而非只確認進程存在？
      （上次事故：systemd 全程回報 `active (running)`，而 API 早已 3 秒逾時、回傳 0 位元組）
- [ ] 是否有**業務進度指標**告警？

> 💡 **`業務進度指標` 是本次事故中唯一能在第一天就發現異常的檢查。**
> 它不需要任何 JVM 知識，只問一個問題：**「這個服務有沒有在做它該做的事？」**
>
> 以本案為例：`SELECT MAX(created_at) FROM orders` 與現在時間的差距若超過 5 分鐘 → 告警。
> 這一條在 07-08 當晚就會響。實際上沒有人設，所以拖到 07-14 才因為 CPU 異常被發現。

### 3.3 建議的告警閾值

| 檢查項 | 實作 | 閾值 |
|--------|------|------|
| 端點實際回應 | `curl -m 3 http://localhost:<port>/api/status` | 逾時或非 200 → 告警 |
| 業務進度 | 資料庫最新記錄時間戳與現在的差距 | > 5 分鐘 → 告警 |
| GC 開銷比例 | 定期採樣 `jstat -gcutil`，計算 `ΔFGCT / Δ牆鐘時間` | > 10% 警告，> 50% 嚴重 |
| 老年代占用率 | `jstat -gcutil` 的 `O` 欄位 | 連續 3 次 > 90% → 告警 |

---

## 快速診斷備忘（下次 CPU 異常時直接照做）

事故當下最容易浪費時間的地方，是標準工具全部失效卻不知道為什麼。

```bash
# 1. 哪個進程？
top -b -n1 | head -15

# 2. 進程內哪個執行緒燒 CPU？（不需 attach）
#    若名稱是 GC Thread# → 直接跳到步驟 4
awk '{print $14+$15, $1, $2}' /proc/<pid>/task/*/stat 2>/dev/null | sort -rn | head

# 3. 排除其他假設（不需 attach）
ls /proc/<pid>/fd | wc -l        # fd 洩漏？
ls /proc/<pid>/task | wc -l      # 執行緒洩漏？

# 4. GC 統計（★ 不需 attach，jstack/jmap 失效時用這個）
jstat -gcutil <pid> 1000 10
#    O=100.00      → 老年代滿
#    FGC 持續增加   → 死亡螺旋進行中
#    ΔFGCT ≈ Δ牆鐘 → GC overhead 100%

# 5. 業務面交叉驗證
mysql -e "SELECT COUNT(*), MAX(created_at) FROM orders"
```

> ⚠️ **若 `jstack` / `jmap` / `jcmd` 回報 `doesn't respond within 10500ms`——
> 這不是障礙，這是證據。** 它代表 JVM 忙到無法提供一個安全點（safepoint），
> 已足以排除「只是效能稍差」的可能性。改用 `jstat`（讀磁碟上的效能計數器，不需目標進程配合）。

---

## 這份清單能與不能做到什麼

**能做到：**

- L1 的自動檢查**無法被跳過**，能擋下「完全沒想過容量」這一類疏漏——這正是上次的成因
- L1 在首次執行就找出 2 個先前未知的同類缺陷（見 1.2 表格），證明它不是形式主義
- L3 的 `-XX:+ExitOnOutOfMemoryError` 一行設定，就能把「13 天靜默空轉」變成「立即崩潰 + 重啟 + 告警」
- L3 的業務進度告警，能把偵測時間從 6 天縮短到 5 分鐘

**不能做到：**

- 擋不住「BOUNDED-BY 寫了敷衍的理由」——這需要 code review，而 review 會被匆忙略過
- 擋不住集合以外的資源洩漏（執行緒、連線、fd、直接記憶體）
- 擋不住「規格本身就要求無界」這類設計層級衝突——那需要的是**產品決策**，不是檢查清單
- 最重要的：**擋不住「這次不一樣，先上線再說」**

**所以真正的保證不在清單，在於：讓正確的事情變成預設值，讓錯誤的事情需要額外動作去繞過。**
L1 就是照這個原則設計的——不宣告就過不了 CI。

---

## 待辦

- [x] ~~處理 `OrderBook` 的 3 個違規項~~ ✅ 2026-07-23
- [x] ~~處理其餘 5 個違規項~~ ✅ 2026-07-23（掃描已歸零）
- [x] ~~接進 CI~~ ✅ 2026-07-23
- [x] ~~實作 endurance test~~ ✅ 2026-07-23
- [x] ~~提供加固後的 unit 檔~~ ✅ 2026-07-23：`deploy/binance-trading-engine.service`（已通過 `systemd-analyze verify`）
- [ ] 實際套用到主機 — **尚未執行**，因事故現場保留政策禁止重啟 PID 26810
- [ ] 設定 L3.2 的業務進度告警

---

**建立日期：** 2026-07-21
**來源事故：** [`docs/incident-2026-07-14-gc-death-spiral/`](../incident-2026-07-14-gc-death-spiral/)
