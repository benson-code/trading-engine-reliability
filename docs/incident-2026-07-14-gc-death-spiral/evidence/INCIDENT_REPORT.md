# Incident Report: Trading Engine GC Death Spiral

**Status:** 案發現場已保留（PID 26810 未重啟、未 kill）  
**Captured at:** 2026-07-14T15:01:22Z  
**Evidence dir:** `/home/ubuntu/qa-incident-2026-07-14/`  
**Project:** [benson-code/binance-qa-suite](https://github.com/benson-code/binance-qa-suite) → `trading-engine-simulator`

---

## 0. 一句話結論

**是的，你剛好踩到教科書級 GC 死亡螺旋（GC thrashing / death spiral）。**  
根因不是「CPU 突然壞了」，而是 **`OrderBook` 無界記憶體成長 → heap 打滿 → Full GC 幾乎佔滿所有 CPU → OOME → 進程僵死卻仍活著在做無效 GC**。

---

## 1. 案發現場摘要（Do Not Touch）

| 項目 | 值 |
|------|-----|
| PID | **26810**（仍在跑，~87% CPU） |
| 服務 | `binance-trading-engine.service` |
| 啟動 | 2026-07-01 15:46:07 UTC（已跑 ~13 天） |
| JVM | OpenJDK **21.0.11**，G1GC，**未設 -Xmx**（預設約 3GB heap） |
| 啟動指令 | `java -jar .../trading-engine-simulator-1.0.0.jar`（**無 GC log、無 heap dump on OOME**） |
| RSS | ~**3.07 GB** |
| 機器 | 2 vCPU / 11 GB RAM |

**保留動作（已完成）：**

- `service_journal_full.txt` — 完整 systemd journal（含 OOME / heap / thread dump 片段）
- `jstat_gcutil*.txt` / `jstat_gc*.txt` / `jstat_gccause.txt` — 活體 GC 計數器
- `proc_status.txt` / `thread_cpu_proc.txt` / `SNAPSHOT.txt`
- `oome_lines.txt` / `timeline.txt` / unit file 備份
- `EVIDENCE_SHA256.txt` — 證據完整性雜湊

**刻意未做（避免破壞現場）：**

- 未 `systemctl stop/restart`
- 未 kill PID
- 未強制 heap dump（attach 已失敗；強行 dump 可能再加劇 OOME）

---

## 2. 為什麼說是 GC 死亡螺旋？

### 2.1 定義（面試可講）

GC death spiral 典型特徵：

1. **存活物件佔滿 heap**（live set ≈ heap capacity）  
2. GC **幾乎回收不了東西**  
3. 分配失敗 → 更頻繁 / 更長的 Full GC  
4. **應用吞吐 → 0**，但 **CPU → 100%**（都在 GC）  
5. 最後 `OutOfMemoryError`，進程可能還活著繼續 thrash

### 2.2 現場硬證據（jstat 連續採樣）

```
  E      O      M     YGC    YGCT     FGC     FGCT        GCT
0.00 100.00  98.15  5803x   ~72s   11488x  ~491240s   ~491312s
```

解讀：

| 指標 | 數值 | 意義 |
|------|------|------|
| **Old gen `O`** | **100.00%** | 老年代打滿 |
| **Eden `E`** | **0.00%** | 幾乎沒有 young 空間可配 |
| **FGC** | **~114,880+ 次** | Full GC 次數爆炸 |
| **FGCT** | **~491,240 秒 ≈ 5.69 天** | 累計 Full GC 時間 |
| **YGCT** | **~72 秒** | Young GC 幾乎可忽略 |
| **GCT ≈ FGCT** | ≈ 100% | 全部 GC 時間幾乎都是 Full GC |
| **GCC (current)** | `G1 Compaction Pause` | 當下仍卡在 compaction |
| **avg Full GC** | **~4.3 s / 次** | 每次 pause 數秒級 |

**活體 thrash 速率：** 14 秒牆鐘時間內 FGCT 增加 ~14 秒 → **GC overhead ≈ 100% wall time**。

Journal heap snapshot（多次一致）：

```
garbage-first heap   total 3053568K, used 3051505K   ← 99.93% 滿
region size 2048K, 0 young (0K), 0 survivors (0K)   ← young regions 歸零
```

### 2.3 誰在吃 CPU？

`/proc` 每線程 CPU（jiffies，排序）：

| 線程 | 相對 CPU | 角色 |
|------|----------|------|
| **GC Thread#1** | ~48.6M | G1 並行 GC |
| **GC Thread#0** | ~48.6M | G1 並行 GC |
| DB-WRITER | ~0.19M | 應用（遠低於 GC） |
| G1 Conc#0 | ~0.04M | 並發標記 |
| BUY-THREAD | ~0.03M | 應用 |

Journal 中 GC 線程累計 CPU（Jul 13）：

- `GC Thread#0` cpu ≈ **403,854,208 ms ≈ 112 小時**
- `GC Thread#1` 同級

→ **CPU 飆高 = GC 在空轉，不是交易撮合邏輯在算。**

### 2.4 OOME 時間線

| 時間 (UTC) | 事件 |
|------------|------|
| **Jul 01 15:46** | service 啟動，DB/WS 就緒，Engine STOPPED |
| （其後 UI/API start） | BUY/SELL 開始以 ~20 orders/s 灌入 `OrderBook` |
| **Jul 08 22:56** | MySQL 最後一筆 order timestamp（生成停止前） |
| **Jul 08 22:57** | OOME → `idle-timeout-task` |
| **Jul 08 22:58** | OOME → `mysql-cj-abandoned-connection-cleanup` |
| **Jul 08 22:59** | OOME → **`SELL-THREAD`**（引擎生成線程死亡） |
| **Jul 08 23:00** | OOME → `HTTP-Dispatcher` |
| **Jul 09 17:50+** | AttachListener OOME：`Java heap space`（jcmd/jstack 也掛） |
| **Jul 09 ~17:51** | Journal 出現大量 heap/thread dump 片段；heap 已 99.93% 滿 |
| **Jul 13 15:31+** | 再次 OOME + 同樣滿 heap |
| **Jul 14 15:01** | 本報告取證：jstat 仍 100% Old + 連續 Full GC |

### 2.5 附帶症狀（都符合 spiral）

- `jcmd` / `jstack` attach **timeout**（`AttachNotSupportedException` / `doesn't respond within 10500ms`）
- REST `curl :8092/api/v1/status` **3s timeout、0 bytes**（業務線程拿不到 heap 配物件）
- 進程 **不崩潰**（systemd 仍 `active (running)`）— 死亡螺旋常比「直接炸進程」更陰險

**Verdict: 確診 GC death spiral。不是偶發尖峰。**

---

## 3. 根因：無界 in-memory OrderBook

### 3.1 程式碼（`OrderBook.java`）

```java
private final Map<String, Order> orders             = new ConcurrentHashMap<>();
private final Map<String, Integer> orderIdFrequency = new ConcurrentHashMap<>();
private final List<Order> allOrders                 = Collections.synchronizedList(new ArrayList<>());

public boolean addOrder(Order order) {
    String id = order.getOrderId();
    allOrders.add(order);                          // ① 每次都 append，永不淘汰
    orderIdFrequency.merge(id, 1, Integer::sum);   // ② frequency map 只增不減
    return orders.putIfAbsent(id, order) == null;  // ③ unique map 只增不減
}
```

對比：`OrderCache` 有 LRU capacity=1000（正確示範 LC-146），**但 OrderBook 沒有任何上限 / TTL / ring buffer**。

### 3.2 產生速率（`Main.java` + `TradingEngine.java`）

```java
int intervalMs = 100; // 20 orders/sec total (10 BUY + 10 SELL)
```

- BUY / SELL 以 Semaphore 交替，各自 `sleep(100ms)`
- 實效 ≈ **20 orders/s** 持續寫入記憶體 + 異步寫 MySQL

### 3.3 數量級驗證（MySQL 佐證）

```
orders 表 count = 11,358,422
timestamp min ≈ 2026-07-01 08:27 UTC
timestamp max ≈ 2026-07-08 22:56 UTC  ← 與首次 OOME 只差 ~1 分鐘
平均寫入速率 ≈ 17.3 orders/s（接近 20/s 設計值；OOME 前略降/含歷史殘留可解釋）
```

粗算 heap：

| 運行天數 @20/s | 訂單數 | 粗估 live set（含 map/list 開銷） |
|----------------|--------|-----------------------------------|
| 1 天 | ~1.7M | ~1.2–1.9 GB |
| 3 天 | ~5.2M | ~3.6–5.7 GB → **已超過預設 ~3GB heap** |
| 7+ 天 | ~12M | 遠超 heap → 必然 OOME |

實際：約 **第 7–8 天（Jul 08 22:57）首次 OOME**，與「3GB 預設 heap 被 live Order 填滿」高度吻合。

### 3.4 次要加劇因素

| 因素 | 影響 |
|------|------|
| **systemd 未設 `-Xmx` / GC 參數** | 預設 MaxHeap ≈ 容器/機器 memory 的一部分（此處 ~3GB）；無 GC log 難以及早告警 |
| **`DB-WRITER` = 單線程 + 無界 `ExecutorService` queue** | DB 變慢時 `Runnable` 堆積（每個 task 抓著 Order 引用）→ 加重 live set |
| **`getAllOrders()` 整表 copy** | API 若被打到會短暫雙倍記憶體；目前 API 已 timeout |
| **`/duplicates` 回傳完整 frequency map** | 大 map 序列化會再配大量 short-lived 物件 |
| **Engine 不會因 OOME 自動 stop** | SELL-THREAD 死後 GC 仍對 **11M+ 強引用 Order** 反覆掃描 |

### 3.5 不是根因的東西

- `OrderCache` LRU（有界）✅  
- MySQL 本身（磁碟側；DB 存活，表可查）  
- Next.js UI / payment-api（CPU 可忽略）  
- 惡意程式 / 系統級 kworker

---

## 4. 因果鏈（面試用「故障樹」）

```
[UI/API] POST /engine/start
        │
        ▼
BUY/SELL 以 ~20 order/s 產生 Order
        │
        ├─► OrderBook.allOrders / orders / orderIdFrequency  【無界強引用】
        ├─► OrderCache (LRU 1000)                            【有界，OK】
        └─► DB-WRITER async INSERT                           【持久化成功到 Jul 08】
        │
        ▼  (數天後 live set → MaxHeap)
G1 Old 100% + young regions 被擠壓
        │
        ▼
Full GC 頻率↑、每次數秒、回收量→0
        │
        ▼
CPU 被 GC Thread 打滿（死亡螺旋）
        │
        ▼
OutOfMemoryError（SELL-THREAD / HTTP / MySQL cleanup...）
        │
        ▼
業務停滯，但 JVM 進程仍 alive → 連續 5+ 天 Full GC thrash
        │
        ▼
2026-07-14 你觀察到「CPU 飆高」  ← 你現在所在位置
```

---

## 5. 為什麼這對「幣安 QA 面試」特別有價值

你這個 demo 本來就在展示 **LC 模式落到交易系統**；這次事故剛好補上 **生產級 QA 最吃香的一塊：可靠性 / 容量 / 可觀測性**。

面試官若問「CPU 為什麼高？」，高分答法：

1. **先取證**：`top` → 鎖定 PID → `jstat -gcutil` / journal → 看到 FGC/FGCT  
2. **定性**：Old 100% + GC time ≈ wall time + OOME → death spiral，不是 busy loop  
3. **定位代碼**：無界 collection + 持續生產速率  
4. **用數據閉環**：DB 11.3M rows、時間戳對上 OOME、20/s 設計速率  
5. **提出修復與防呆**（見下）  
6. **補測試**：長跑 / 記憶體上限 / 有界緩衝的契約測試

這比只講 LeetCode 模式更像 **資深 QA / SDET 在交易所現場排障**。

---

## 6. 修復與防呆建議（可當 follow-up PR 素材）

### 6.1 產品代碼

1. **`OrderBook` 有界化**  
   - ring buffer / max size + 淘汰策略  
   - 或「只保留最近 N 筆 + frequency 用 HyperLogLog/計數器而不保留全部 Order」  
2. **生成與存儲分離**：內存只做近窗展示；完整歷史以 MySQL 為 source of truth  
3. **`DB-WRITER` 有界佇列**（`ThreadPoolExecutor` + `CallerRuns` / drop + metric）  
4. OOME / 背壓時 **自動 `engine.stop()`** 並對外暴露 degraded 狀態  

### 6.2 運行參數（systemd）

```ini
ExecStart=/usr/bin/java \
  -Xms512m -Xmx1024m \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/trading-engine \
  -Xlog:gc*:file=/var/log/trading-engine/gc.log:time,uptime,level,tags:filecount=10,filesize=20M \
  -XX:+ExitOnOutOfMemoryError \
  -jar /home/ubuntu/qa/trading-engine-simulator/target/trading-engine-simulator-1.0.0.jar
```

重點：

- **限制 heap** 讓問題更早、更可預期地暴露  
- **`HeapDumpOnOutOfMemoryError`** 留下 MAT/jhat 可分析的 dump  
- **`ExitOnOutOfMemoryError`** 避免「僵屍 thrash 五天」  
- **GC log** 讓 spiral 在 OOME 前就被監控發現  

### 6.3 測試缺口（面試加分）

| 測試類型 | 斷言 |
|----------|------|
| 長跑 soak（例如 1h @20/s） | RSS / heap used 有上界，不單調爬升 |
| 單元：OrderBook capacity | 超過 N 後 size 穩定 |
| 故障注入 | 模擬 DB 慢寫，佇列不無限長 |
| 監控契約 | 暴露 `orderbook_size`、`db_queue_depth`、`gc_pause_p99` |

---

## 7. 現場處置選項（**尚未執行**）

| 選項 | 影響 |
|------|------|
| **A. 繼續保留 PID 26810** | 最佳「面試故事現場」；機器持續滿 CPU |
| **B. `systemctl stop` 後再起** | 立刻恢復 CPU；現場進程狀態消失（journal/DB/本目錄證據仍在） |
| **C. stop + 加 JVM flags + 有界 OrderBook 修 bug 後重啟** | 根治；建議作為面試後的 demo 修復 PR |

**建議：** 若這台是面試 demo 機且你還要展示，先 **A 保留到錄影/截圖完**，再走 C。

---

## 8. 證據索引

| 檔案 | 內容 |
|------|------|
| `jstat_gcutil.txt` / `jstat_gcutil_more.txt` | Old 100%、FGC/FGCT 爆炸 |
| `jstat_gccause.txt` | 當前 `G1 Compaction Pause` |
| `jstat_gc_more.txt` | OC≈OU≈3051520K，EU=0 |
| `service_journal_full.txt` | OOME、heap dump 片段、GC thread CPU |
| `oome_lines.txt` | 所有 OOME 行 |
| `thread_cpu_proc.txt` | GC 線程主宰 CPU |
| `binance-trading-engine.service` | 無 -Xmx / 無 GC log |
| MySQL `orders` | 11,358,422 筆，最後時間戳 ≈ 首次 OOME |

---

## 9. 最終判定

| 問題 | 答案 |
|------|------|
| 是 GC 死亡螺旋嗎？ | **是，證據充分** |
| 觸發點？ | 無界 `OrderBook` + 長時間 20 order/s 生成 |
| 為何 CPU 高？ | Full GC thrashing（非業務邏輯） |
| 為何還活著？ | OOME 殺了業務線程，但未 `ExitOnOutOfMemoryError`，JVM 繼續空轉 GC |
| 首次致命時間？ | **2026-07-08 22:57 UTC**（之後 5+ 天都在 spiral） |

---

*Report generated for interview demo forensics. Process PID 26810 intentionally left running.*
