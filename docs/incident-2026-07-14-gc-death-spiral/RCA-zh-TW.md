# 事故根因分析報告（RCA）— JVM 記憶體洩漏導致 GC 死亡螺旋

**服務：** `trading-engine-simulator`（`binance-qa-suite` 的模組之一）
**主機：** `orion-dev`（Oracle Cloud，aarch64，2 vCPU / 11 GiB RAM）
**進程：** PID `26810`
**事故開始：** 2026-07-08 22:57:49 UTC（首次 `OutOfMemoryError`）
**發現時間：** 2026-07-14 15:01:22 UTC
**報告狀態：** 事故**仍在進行中**（截至 2026-07-21，進程未被終止，現場保留中）

---

## 目錄

1. [一分鐘摘要](#1-一分鐘摘要)
2. [系統環境](#2-系統環境)
3. [完整時間軸](#3-完整時間軸)
4. [症狀：我看到了什麼](#4-症狀我看到了什麼)
5. [診斷過程：我怎麼查的](#5-診斷過程我怎麼查的)
6. [根本原因（程式碼層級）](#6-根本原因程式碼層級)
7. [為什麼會寫成這樣：設計脈絡](#7-為什麼會寫成這樣設計脈絡)
8. [為什麼測試沒有抓到](#8-為什麼測試沒有抓到)
9. [證據交叉驗證](#9-證據交叉驗證)
10. [修復方案](#10-修復方案)
11. [預防措施](#11-預防措施)
12. [面試問答準備](#12-面試問答準備)
13. [證據檔案索引](#13-證據檔案索引)
14. [已知限制與未驗證項目](#14-已知限制與未驗證項目)

---

## 1. 一分鐘摘要

`OrderBook` 這個類別裡有三個**無界（unbounded，只進不出、沒有任何容量上限或淘汰機制）**的記憶體集合。這支服務被當成 systemd 常駐服務執行，以每秒約 17 筆的速率持續產生訂單，而每一筆訂單物件都被這三個集合永久持有、從不釋放。

執行約 7.6 天、累積約 1,136 萬筆訂單後，JVM 堆記憶體（heap，上限約 2.91 GiB）被耗盡，觸發首次 `OutOfMemoryError`。

**關鍵在於進程並沒有因此結束。** 由於那些訂單物件全部仍被集合強參照著（strongly reachable），垃圾回收器（GC）判定它們**不是垃圾**，因此每一次 Full GC 都幾乎回收不到任何空間；回收完成後堆依然是滿的，於是立刻再次觸發下一次 Full GC——形成無限循環。這就是**GC 死亡螺旋（GC Death Spiral / GC Thrashing）**。

最終結果：兩顆 CPU 核心被 GC 執行緒完全占滿、業務執行緒被餓死、HTTP API 完全無回應——**但 systemd 仍然回報服務 `active (running)`，所有存活性監控（liveness check）都看不出異常。**

> **一句話版本（面試用）**
> `OrderBook` 一直 `add` 不釋放 → 這些物件全都還被集合參照著，GC 判定它們不是垃圾、清不掉 → heap 滿了也清不出空間 → Full GC 背靠背空轉 → CPU 100%，但進程沒死。

---

## 2. 系統環境

### 2.1 主機

來源：`evidence/SNAPSHOT.txt`

```
Linux orion-dev 6.17.0-1018-oracle #18~24.04.1-Ubuntu SMP aarch64 GNU/Linux
CPU 核心數: 2
              total        used        free      shared  buff/cache   available
Mem:           11Gi       4.4Gi       1.6Gi       5.3Mi       5.9Gi       7.2Gi
Swap:            0B          0B          0B
15:01:22 up 14 days, 5:23, 2 users, load average: 2.03, 2.04, 2.00
```

**重點解讀：**

| 項目 | 數值 | 意義 |
|------|------|------|
| CPU 核心 | 2 | load average 2.03 ≈ **兩顆核心 100% 飽和**，沒有任何餘裕 |
| 記憶體 | 11 GiB | 決定了 JVM 的預設堆上限（見 2.2） |
| Swap | **0 B** | 沒有置換空間，記憶體壓力無處緩衝，堆一滿就是硬碰硬 |

### 2.2 JVM

來源：`evidence/java_version.txt`、`evidence/binance-trading-engine.service`

```
openjdk version "21.0.11" 2026-04-21
OpenJDK 64-Bit Server VM (build 21.0.11+10-1-24.04.2-Ubuntu, mixed mode, sharing)
```

systemd unit 檔的啟動指令：

```ini
ExecStart=/usr/bin/java -jar /home/ubuntu/qa/trading-engine-simulator/target/trading-engine-simulator-1.0.0.jar
Restart=on-failure
```

**這一行有兩個關鍵問題：**

**問題 A：完全沒有指定任何 JVM 記憶體參數。**

沒有 `-Xmx`（最大堆大小）、沒有 `-XX:+HeapDumpOnOutOfMemoryError`（OOM 時自動產生堆傾印）、沒有 `-Xlog:gc*`（GC 日誌）。

因此 JVM 使用**預設人體工學（default ergonomics）**：最大堆 = 實體記憶體的 1/4 = 11 GiB ÷ 4 ≈ **2.91 GiB**。

這個數字與 log 中觀測到的堆大小完全吻合（來源：`evidence/timeline.txt:286`）：

```
garbage-first heap   total 3053568K, used 3051505K
```

`3,053,568 KB` = 2.91 GiB ✓

**問題 B：`Restart=on-failure` 在這個情境下完全沒有作用。**

systemd 的 `on-failure` 是看**進程結束碼（exit code）**。但這支進程從頭到尾**沒有結束**——它一直活著、一直在燒 CPU。對 systemd 而言，這是一個健康的服務。這正是「靜默劣化（silent degradation）」能持續 6 天沒被發現的機制性原因。

### 2.3 垃圾回收器

來源：`evidence/jstat_gccause.txt`、`evidence/timeline.txt`

JDK 21 在此規格機器上預設使用 **G1GC（Garbage-First Garbage Collector）**，已由兩處證據確認：

1. log 中出現 `garbage-first heap`
2. `jstat -gccause` 顯示 `LGCC = G1 Evacuation Pause`、`GCC = G1 Compaction Pause`
3. 執行緒列表中有 `G1 Conc#0`、`G1 Refine#0`、`G1 Service`

---

## 3. 完整時間軸

所有時間戳來源：`evidence/timeline.txt`、`evidence/oome_lines.txt`、`evidence/db_order_stats.txt`、`evidence/SNAPSHOT.txt`

> **時區注意：** journal 日誌顯示的是主機本地時間（UTC+8），MySQL 統計顯示的是 UTC。下表已標註各自時區。

| 時間 | 事件 | 證據來源 |
|------|------|----------|
| **07-01 15:46:07** (本地) | systemd 啟動服務，PID 26810 | `timeline.txt:1` |
| 07-01 15:46:08 (本地) | MySQL 連線建立、WebSocket 啟動於 port 8093 | `timeline.txt:2,6` |
| 07-01 15:46:08 (本地) | log 明確顯示 `Engine : STOPPED — press RUN in the UI to start` | `timeline.txt:12` |
| **07-01 08:27:34** (UTC) | **第一筆訂單寫入資料庫**（= 使用者從 UI 按下 RUN，比 systemd 啟動晚約 41 分鐘） | `db_order_stats.txt` |
| 07-01 → 07-08 | 持續產生訂單，速率 **17.29 筆/秒**，記憶體單調累積 | `db_order_stats.txt` |
| **07-08 22:56:24** (UTC) | **最後一筆成功寫入資料庫的訂單**（累計 11,358,422 筆） | `db_order_stats.txt` |
| **07-08 22:57:49** (本地) | **首次 `OutOfMemoryError`**，發生在 `idle-timeout-task` 執行緒 | `oome_lines.txt:25` |
| 07-08 22:58:49 | 第 2 次 OOME — `mysql-cj-abandoned-connection-cleanup`（MySQL 驅動的清理執行緒也配置不到記憶體） | `oome_lines.txt:26` |
| 07-08 22:59:23 | 第 3 次 OOME — **`SELL-THREAD`**（業務執行緒陣亡） | `oome_lines.txt:27` |
| 07-08 23:00:14 | 第 4 次 OOME — **`HTTP-Dispatcher`**（REST API 路徑陣亡） | `oome_lines.txt:28` |
| 07-09 17:50:02 / 17:51:05 | 再次 `OutOfMemoryError: Java heap space` | `oome_lines.txt:30,32` |
| 07-09 17:51:40 起 | 反覆傾印堆狀態：`total 3053568K, used 3051505K`（**99.93% 使用率**） | `timeline.txt:286,544,801,1059` |
| 07-13 15:31–15:32 | 又出現三次 `OutOfMemoryError: Java heap space` — **五天後仍在 thrash** | `oome_lines.txt:4149,4665,4667` |
| **07-14 15:01:22** (UTC) | **開始調查與現場取證**（`INCIDENT_START.txt`） | `INCIDENT_START.txt` |
| 07-21 09:38 (UTC) | 第二次快照取證，進程仍在螺旋中 | `evidence/snapshot-2026-07-21T0938Z/` |
| **2026-07-21（今日）** | **進程 26810 仍然存活**，`ELAPSED 19-23:32:19`、CPU 126% | 即時 `ps` 查詢 |

### 3.1 時間軸最關鍵的一點

> **資料庫最後一筆寫入（07-08 22:56:24 UTC）與首次 `OutOfMemoryError`（07-08 22:57:49）只相差 85 秒。**

這 85 秒是整份報告最有力的單一證據。它把「記憶體耗盡」和「服務停止工作」這兩個原本獨立的觀測，鎖死成同一起事件——**不是兩個巧合同時發生的問題，是一個原因產生的兩個症狀**。

---

## 4. 症狀：我看到了什麼

### 4.1 主機層面

來源：`evidence/SNAPSHOT.txt`、`evidence/process_still_alive.txt`

```
    PID    PPID USER     %CPU %MEM    VSZ     RSS      ELAPSED   STAT CMD
  26810       1 ubuntu   87.1 26.3 5756544 3221444  12-23:15:14  Ssl  /usr/bin/java -jar ...
```

| 指標 | 數值 | 解讀 |
|------|------|------|
| `%CPU` | 87.1（後續量測達 126%） | 單一進程吃掉超過一顆核心 |
| `RSS` | 3,221,444 KB ≈ **3.07 GiB** | 常駐記憶體逼近堆上限 + JVM 本身開銷 |
| `%MEM` | 26.3% | 占用主機超過四分之一記憶體 |
| `ELAPSED` | 12 天 23 小時 | 已連續執行近 13 天 |
| `PPID` | 1 | 由 systemd 託管 |
| load average | 2.03 / 2 核心 | **完全飽和** |

### 4.2 服務層面

來源：`evidence/api_status.txt`、`evidence/api_orders_sample.txt`

```
curl: (28) Operation timed out after 3002 milliseconds with 0 bytes received
```

HTTP API **完全無回應**，3 秒逾時、收到 0 位元組。不是回傳 500、不是回傳慢——是**根本不回應**。

**同時，systemd 仍然回報服務為 `active (running)`。**

這個落差是本次事故最重要的維運教訓：**進程存活（process liveness）不等於服務可用（service availability）**。任何只檢查「PID 是否存在」或「systemd 狀態」的監控，對這類故障是完全盲目的。

---

## 5. 診斷過程：我怎麼查的

這一段是整份報告在面試中最有價值的部分——**因為它展示的是方法，不是結論**。

### 步驟 1：定位到單一進程

`top` / `ps` 顯示 CPU 被單一 Java 進程（PID 26810）占滿，而非多個進程分散負載。這排除了「系統整體負載過高」的可能，把範圍縮小到單一 JVM 內部。

### 步驟 2：釐清是「哪一種」CPU 燒法

一個 Java 進程吃滿 CPU，主要有三種可能：

| 假設 | 特徵 | 如何區分 |
|------|------|----------|
| A. 業務執行緒忙迴圈（busy-loop / spin） | 燒 CPU 的是業務執行緒 | 看 per-thread CPU 歸屬 |
| B. GC 死亡螺旋 | 燒 CPU 的是 GC 執行緒 | 看 per-thread CPU + GC 統計 |
| C. 死鎖 / 資源洩漏（fd、執行緒） | CPU 通常不會滿，或執行緒數/fd 數異常 | 看 `thread_count`、`fd_count` |

**先排除 C：**

- `evidence/fd_count.txt` → **15**（檔案描述符正常，排除 fd 洩漏）
- `evidence/thread_count.txt` → **33**（執行緒數正常，排除執行緒洩漏）

> 💡 這一步在面試中值得主動講：**排除法本身就是證據**。我不只證明了是什麼，也證明了不是什麼。

### 步驟 3：per-thread CPU 歸屬 — 決定性的一步

來源：`evidence/thread_cpu_proc.txt`（直接讀 `/proc/<pid>/task/*/stat`，不需要 attach 進程）

```
   jiffies    TID  執行緒名稱
  48627897  26842  (GC Thread#1)
  48626021  26820  (GC Thread#0)
    192072  36615  (DB-WRITER)
     41894  26822  (G1 Conc#0)
     28265  36613  (BUY-THREAD)
     10570  26828  (VM Thread)
```

換算成 CPU 秒數（Linux `USER_HZ` = 100，即 1 jiffy = 10 毫秒）：

| 執行緒 | CPU 時間 | 換算 |
|--------|----------|------|
| **GC Thread#1** | **486,279 秒** | **≈ 5.63 天** |
| **GC Thread#0** | **486,260 秒** | **≈ 5.63 天** |
| DB-WRITER | 1,921 秒 | 32 分鐘 |
| BUY-THREAD | **283 秒** | 4.7 分鐘 |

**兩個 GC 執行緒合計消耗 972,539 秒 CPU；所有業務執行緒合計約 2,204 秒。比例約 441 : 1。**

`BUY-THREAD` 在 629,535 秒的生命週期中只獲得了 283 秒 CPU（占 0.045%）——這不是「服務變慢」，這是**業務執行緒被 GC 徹底餓死（starvation）**。

至此，假設 A 被排除，假設 B 成立。**燒 CPU 的不是工作，是垃圾回收。**

### 步驟 4：標準診斷工具全數失效 — 而失效本身就是證據

嘗試取得執行緒傾印與堆直方圖：

```bash
jstack 26810
jcmd 26810 GC.class_histogram
jcmd 26810 VM.flags
jcmd 26810 GC.heap_info
```

**全部失敗**，錯誤訊息完全相同（來源：`evidence/java_class_histogram.txt`、`java_vm.txt`、`java_heap_info.txt`）：

```
com.sun.tools.attach.AttachNotSupportedException: Unable to open socket file
/proc/26810/root/tmp/.java_pid26810: target process 26810 doesn't respond
within 10500ms or HotSpot VM not loaded
```

**這個失敗不是障礙，它本身就是一級證據。**

原理：`jstack` / `jcmd` 這類工具靠 **attach 機制**運作——它們要求目標 JVM 停在一個**安全點（safepoint）**，才能被檢查。而這個 JVM 因為連續執行 Full GC（本身就是全域停頓操作）並持續處於極端記憶體壓力下，**在 10.5 秒內連一次回應診斷請求的機會都擠不出來**。

> 一個活著的 JVM 忙到無法回應自己的診斷通道，這件事本身就把「輕微效能問題」的可能性完全排除了。

### 步驟 5：改用不需要 attach 的工具（關鍵轉折）

`jstat` 的運作原理完全不同：它**讀取 JVM 寫在磁碟上的效能計數器檔案**（`/tmp/hsperfdata_<user>/<pid>`），**不需要目標進程配合、不需要 attach、不需要 safepoint**。

```bash
jstat -gcutil 26810 1000 10
```

結果（來源：`evidence/jstat_gcutil.txt`）：

```
  S0     S1     E      O      M     CCS    YGC     YGCT     FGC       FGCT         CGC   CGCT      GCT
   -      -   0.00 100.00  98.15  91.76  58027   71.847  114876  491204.633    242  0.342  491276.821
   -      -   0.00 100.00  98.15  91.76  58027   71.847  114877  491208.589    242  0.342  491280.777
   -      -   0.00 100.00  98.15  91.76  58028   71.850  114878  491213.684    242  0.342  491285.875
```

**逐欄解讀：**

| 欄位 | 數值 | 意義 |
|------|------|------|
| `O` | **100.00** | **老年代（Old Gen）100% 滿**，一絲空間都擠不出來 |
| `E` | 0.00 | 伊甸園區空的——沒有新物件在配置，因為業務執行緒已死 |
| `FGC` | **114,876** | Full GC 累計次數 |
| `FGCT` | **491,204.6 秒** | Full GC 累計停頓時間 ≈ **5.69 天** |
| `GCT` | 491,276.8 秒 | 總 GC 時間 |
| `YGC` / `YGCT` | 58,027 / 71.8 秒 | 年輕代 GC 只花了 71 秒——**對比 Full GC 的 49 萬秒，差距 6,800 倍** |
| `M` / `CCS` | 98.15 / 91.76 | Metaspace 正常，排除類別載入洩漏 |

### 步驟 6：證明螺旋「正在進行」而非「歷史事件」

單次採樣只能證明「曾經發生過」。要證明**現在仍在發生**，需要看增量。

比對兩次採樣（來源：`evidence/jstat_gcutil.txt` 與 `jstat_gcutil_final.txt`）：

| 採樣 | FGC | FGCT |
|------|-----|------|
| 第一次 | 114,876 | 491,204.633 s |
| 最後一次 | 114,909 | 491,352.528 s |
| **增量** | **+33 次** | **+147.9 秒** |

`evidence/INCIDENT_REPORT.md:77` 記錄了更精確的量測：

> **活體 thrash 速率：14 秒牆鐘時間內 FGCT 增加約 14 秒 → GC overhead ≈ 100% wall time。**

**牆鐘時間每過 1 秒，Full GC 停頓時間就增加約 1 秒。** 這意味著這個 JVM **百分之百的時間都在做垃圾回收**，沒有任何時間留給應用程式。

### 步驟 7：計算 GC 占比（量化衝擊）

進程存活時間（取證當下）：12 天 23 小時 15 分 14 秒 = **1,120,514 秒**

```
GC 總時間 / 進程生命週期 = 491,276.8 / 1,120,514 = 43.8%
```

從 CPU 核心秒數的角度交叉驗算：

```
可用 CPU 總量 = 2 核心 × 1,120,514 秒 = 2,241,028 核心秒
GC 執行緒消耗  = 486,279 + 486,260 = 972,539 核心秒
占比           = 972,539 / 2,241,028 = 43.4%
```

**兩種完全獨立的算法（JVM 內部計數器 vs 作業系統 `/proc` 統計）得到 43.8% 與 43.4%，誤差在 1% 以內。** 這是內部一致性的強力佐證。

### 步驟 8：與資料庫交叉驗證（閉環）

來源：`evidence/db_order_stats.txt`

```
order_count  min_ts_utc               max_ts_utc               span_days  orders_per_sec
11358422     2026-07-01 08:27:34.280  2026-07-08 22:56:24.104  7.603      17.290
```

| 驗證項 | 結果 |
|--------|------|
| 資料庫訂單總數 | **11,358,422** |
| 產生速率 | 17.29 筆/秒 |
| 持續時間 | 7.603 天 |
| 最後寫入時間 | 2026-07-08 22:56:24 UTC |
| 首次 OOME 時間 | 2026-07-08 22:57:49 |
| **時間差** | **85 秒** |

**這一步把記憶體中的推論釘死在磁碟上的事實。** 資料庫是完全獨立於 JVM 的資料來源，它獨立證實了：訂單產生在 07-08 22:56 戛然而止，而 85 秒後 JVM 拋出第一個 OOME。

---

## 6. 根本原因（程式碼層級）

**檔案：** `trading-engine-simulator/src/main/java/com/binance/trading/engine/OrderBook.java`

### 6.1 有問題的宣告（L19–21）

```java
private final Map<String, Order>   orders            = new ConcurrentHashMap<>();
private final Map<String, Integer> orderIdFrequency  = new ConcurrentHashMap<>();
private final List<Order>          allOrders         = Collections.synchronizedList(new ArrayList<>());
```

### 6.2 唯一的寫入路徑（L26–31）

```java
public boolean addOrder(Order order) {
    String id = order.getOrderId();
    allOrders.add(order);                              // ← 只有 add
    orderIdFrequency.merge(id, 1, Integer::sum);       // ← 只有 merge
    return orders.putIfAbsent(id, order) == null;      // ← 只有 putIfAbsent
}
```

**三個集合、三個寫入操作、零個移除操作。**

### 6.3 三條強參照路徑

| 集合 | 型別 | 持有內容 | 是否持有 `Order` 物件 |
|------|------|----------|----------------------|
| `allOrders` | `List<Order>` | 每一筆提交過的訂單（含重複） | ✅ **是** — 主要洩漏源 |
| `orders` | `Map<String, Order>` | orderId → 首次出現的訂單 | ✅ **是** |
| `orderIdFrequency` | `Map<String, Integer>` | orderId → 出現次數 | ❌ **否** — 只持有字串與計數，但**條目數同樣無界成長** |

> ⚠️ **精確性提醒（面試會被抓的細節）**
> `orderIdFrequency` 的型別是 `Map<String, Integer>`，它**不持有任何 `Order` 物件的參照**。真正抓住 1,119 萬個 `Order` 物件不放的只有 `allOrders` 和 `orders` 兩條路徑。第三個 map 是獨立的無界成長來源（累積 String key 與 Integer boxing），但它不是 `Order` 的保留路徑。
> 這與 `evidence/RCA_REPORT.md` 的直方圖記錄相符：`ConcurrentHashMap$Node ≈ 21.3M —— two unbounded maps`。

### 6.4 存在但從未被呼叫的清除方法（L70–73）

```java
public void clear() {
    orders.clear();
    orderIdFrequency.clear();
    allOrders.clear();
}
```

**驗證結果：整個 repo 中沒有任何地方呼叫 `OrderBook.clear()`——連測試都沒有。**

```bash
$ grep -rn "\.clear()" --include=*.java trading-engine-simulator/src/main | grep -v OrderBook.java
trading-engine-simulator/src/main/java/com/binance/trading/engine/OrderCache.java:57:  cache.clear();
# ← 只有 OrderCache 自己內部的 clear，與 OrderBook 無關
```

**這比「完全沒想到要清除」更值得注意：清除的接縫（seam）已經建好了，卻從未接上任何執行時期的策略。**

### 6.5 呼叫端：無限資料流

**檔案：** `TradingEngine.java` L70–88

```java
buyThread = new Thread(() -> {
    while (running.get()) {              // ← 無限迴圈，永不結束
        buySemaphore.acquire();
        Order order = generateOrder("BUY");
        orderBook.addOrder(order);       // ← 每一輪都往無界集合裡塞
        orderCache.put(order.getOrderId(), order);
        ...
        Thread.sleep(intervalMs);        // intervalMs = 200ms
    }
}, "BUY-THREAD");
```

`BUY-THREAD` 與 `SELL-THREAD` 各以約 200 毫秒的間隔交替產生訂單，配合訊號量（semaphore）交握，實測速率 17.29 筆/秒。

**`while (running.get())` 中的 `running` 只有在服務關閉時才會變 false。也就是說，設計上這個迴圈預期永遠執行下去。**

### 6.6 極具諷刺的對照（`TradingEngine.java:60`）

```java
this(new OrderBook(), new OrderCache(1000), 200, 0.05);
//   ^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^^
//   沒有容量參數       明確指定容量上限 1000
```

而 `OrderCache` 是**正確有界**的（`OrderCache.java:22–28`）：

```java
this.cache = new LinkedHashMap<String, Order>(capacity, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Order> eldest) {
        return size() > capacity;      // ← 超過容量自動淘汰最舊條目
    }
};
```

**同一行建構子中，一個有界、一個無界。**

這證明「要為集合設容量上限」這個意識在撰寫當下是存在的——只是只被套用在名字叫 `Cache` 的那個物件上。

**我的推論（非事實）：這是命名語意造成的注意力偏誤。** 「Cache（快取）」這個詞在直覺上就暗示了資料會被淘汰；而「OrderBook（訂單簿）」在直覺上暗示的是應該保存完整歷史（現實中的交易所訂單簿確實有留存義務）。這個直覺讓有界性的檢查漏掉了真正需要它的那個物件。

> 📌 **紅鯡魚警告：** 調查初期，名字叫 `OrderCache` 的類別是最直覺的懷疑對象——但它是本案中**唯一正確實作的集合**。真正的洩漏源是名字聽起來人畜無害的「分析用清單 `allOrders`」。

### 6.7 完整因果鏈

```
[1] OrderBook 三個集合無界（只 add，無 remove、無容量上限、無淘汰策略）
        ↓
[2] TradingEngine 在 while 無限迴圈中以 17.29 筆/秒 持續呼叫 addOrder()
        ↓
[3] 7.6 天累積 11,358,422 筆訂單物件，全部被強參照持有
        ↓
[4] JVM 堆（預設上限 2.91 GiB，未設 -Xmx）被耗盡 → 老年代 100% 滿
        ↓
[5] JVM 觸發 Full GC 試圖回收空間
        ↓
[6] ★ 但這些物件全部仍為 strongly reachable（集合還抓著）
    → GC 的可達性分析（reachability analysis）判定它們「還活著」
    → Full GC 掃描全堆後，幾乎回收不到任何空間
        ↓
[7] 回收完成，堆依然是滿的 → 立即再次觸發 Full GC → 回到 [5]
    ↓↓↓ 無限循環（GC 死亡螺旋）↓↓↓
        ↓
[8] 兩個 GC 執行緒各消耗 486,279 / 486,260 秒 CPU，占滿兩顆核心
        ↓
[9] 業務執行緒被餓死（BUY-THREAD 在 629,535 秒中僅獲得 283 秒 CPU）
    → 訂單停止產生、DB 停止寫入、HTTP API 完全無回應
        ↓
[10] 但 JVM 進程從未結束 → systemd 持續回報 active (running)
     → 所有存活性監控失效 → 靜默劣化持續 6 天無人察覺
```

**第 [6] 步是整條鏈的核心，也是最容易被跳過的一步。** 沒有它，「記憶體滿了」到「Full GC 無限循環」之間就是斷裂的——因為在正常情況下，Full GC 跑完應該就會空出空間、服務就會恢復。

---

## 7. 為什麼會寫成這樣：設計脈絡

這一段回答的是「這個 bug 為什麼會存在」，而不是「這個 bug 是什麼」。**在面試中，前者展現的層次遠高於後者。**

### 7.1 直接證據：這個類別原本不是為常駐服務而寫的

`OrderBook.java` 的檔案標頭註解（L9–16）：

```java
/**
 * Order book with HashMap-based duplicate detection.
 * Maps to LC-217 (Contains Duplicate) and LC-347 (Top K Frequent Elements).
 *
 * - orders:           orderId → first Order (unique primary store)
 * - orderIdFrequency: orderId → submission count (the "HashMap counting" pattern)
 * - allOrders:        every submission including duplicates (for analysis)
 */
```

檔案中還有兩個分節標題：

```java
// ── LC-217: Contains Duplicate ────────────────────────────────
// ── LC-347: Top K Frequent ────────────────────────────────────
```

**`LC-217` 與 `LC-347` 是 LeetCode 題號。** 這個類別最初的撰寫框架是「用交易領域包裝兩道經典雜湊表演算法題」。

### 7.2 在原本的框架下，這段程式碼是正確的

LeetCode 題目的世界長這樣：

- 輸入是一個**有限的陣列** `nums[]`
- 你建立一個頻率 HashMap，計算答案，`return`
- **進程隨即結束**，整個生命週期是毫秒等級

在那個框架下：

- 「保留全部資料」不只是可以接受——**LC-347（Top K Frequent Elements）在定義上就要求你保有全部資料的統計**，不保留就算不出 top-K
- **「無界」這個概念根本不存在**，因為輸入本來就是有限的
- **沒有時間軸**。不存在「執行到第七天會怎樣」這個問題

**所以這不是「寫錯了」。這是在正確的框架下寫出正確的程式碼。**

### 7.3 框架被替換，但假設沒有被重新檢視

這個類別後續被接入 `Main.java` 與 `TradingEngine`，成為一個由 systemd 託管、預期永久執行的常駐服務。

**輸入的性質從「有限陣列」變成了「無限資料流」。**

而沒有任何環節回頭提問：**在新的框架下，這些資料結構的假設還成立嗎？**

這是缺陷來源中極為典型的一類——**不是程式碼寫錯，而是程式碼被搬移到了它的前提不再成立的環境中。**

### 7.4 對「AI 輔助開發」的觀察

本專案為 AI 輔助實作。誠實的觀察是：

程式碼產出強烈偏向「**在給定任務框架下局部正確、且能讓測試通過**」。當任務被表述為「用 LC-217/LC-347 的模式實作一個訂單簿」，產出的成果**完整滿足了那個任務**。

**未被表達出來的需求，不會被檢查。** 「這將在 2 核心、固定堆大小的機器上連續執行數週」這項約束，存在於後續的**部署決策**中，不存在於撰寫該類別的任務裡，也不存在於任何一個測試裡。資源在時間軸上的行為，恰好落在這個盲區。

**這對 QA 職能有直接意涵：** AI 輔助開發的專案中，人類最需要介入的環節不是「檢查程式碼邏輯對不對」（那是它最擅長的），而是「**檢查它的前提假設在我的實際部署環境下是否仍然成立**」。這恰好是測試設計與風險分析的本職技能，而非撰寫程式碼的技能。

### 7.5 反方視角：這個解釋不能作為免責

必須誠實指出：「框架搬移」是**成因分析**，不是**辯護**。

- 「長生命週期物件持有無界集合」是 Java 記憶體洩漏最經典的原型，記載於幾乎每一本 JVM 效能書籍。任何工程師看到 `while` 迴圈中持續對成員集合 `add()`，都應當無條件警覺——這不需要 prompt 特別交代。
- 更難看的事實是：`clear()` 方法**已經寫好了卻從未被接上**（見 6.4）。清除的必要性在 API 層面被意識到了，但從未轉化為執行時期的政策。這比「完全沒想到」更糟。
- **我無法排除的替代解釋：** 我沒有撰寫該檔案當時的完整對話紀錄。以上關於撰寫意圖的分析，全部是從程式碼註解與結構反推的**推論**，不是來自撰寫過程的直接證據。

---

## 8. 為什麼測試沒有抓到

**這是本次事故最重要的 QA 方法論結論。**

### 8.1 事實

實測結果（2026-07-21 於本機執行 `mvn test`，數據來自 `target/surefire-reports/*.xml`）：

| 模組 | 實際執行測試數 |
|------|---------------|
| `payment-api` | **43** |
| `trading-engine-simulator` | **63**（含 8 個需要實際 MySQL 的 `DBValidationTest`） |
| **合計（本機，有 MySQL）** | **106** |
| **合計（CI，無 MySQL）** | **98** — `DBValidationTest` 因 `Assumptions.assumeTrue` 跳過 |

> 註：原始碼中的 `@Test` 標註為 84 個，另有 5 個 `@ParameterizedTest`。標註數與執行數不同，是因為參數化測試在執行期會展開為多個案例。**引用時應使用執行數（98 / 106），而非標註數。**

- 這些測試中包含針對 `OrderBook` 的重複偵測、top-K 查詢等功能測試——**全部通過**
- 專案中**沒有任何 endurance / soak / longevity 測試**（`grep -rliE "endurance|soak|longevity"` 回傳空）

#### ⚠️ 但本機執行時有 1 個測試失敗——而失敗原因正是這起事故

```
DBValidationTest.buySellRatioIsBalanced:112
  Strict alternation: |BUY - SELL| must be ≤ 1, got BUY=5679212 SELL=5679210
Tests run: 63, Failures: 1
```

**這不是程式碼缺陷，是事故遺留的資料。** 該測試直接查詢本機 MySQL 的 `orders` 資料表，而該資料表中存放的正是這次事故累積的訂單。

驗算：

```
BUY  5,679,212
SELL 5,679,210
─────────────────
合計 11,358,422  ← 與 evidence/db_order_stats.txt 記載的訂單總數完全相同
```

`TradingEngine` 以雙訊號量（semaphore）強制 BUY / SELL 嚴格交替，因此任一時刻兩者差值不應超過 1。**現在差值為 2，代表交替序列在中途被截斷了一次**——即崩潰當下有一筆 SELL 訂單未能完成寫入。

證據佐證：`evidence/oome_lines.txt:27` 顯示 `SELL-THREAD` 在 07-08 22:59:23 因 OOME 陣亡，而 OOME 序列中**沒有 `BUY-THREAD`**；`evidence/timeline.txt:195` 顯示 `BUY-THREAD` 在隔日的執行緒傾印中仍然存活。**SELL 側先陣亡、BUY 側後陣亡，正好產生 BUY 領先 2 筆的殘差。**

> 💡 **這是本次調查的第五條獨立驗證鏈**——而且是唯一一條「至今仍可即時重現」的證據：任何人只要在這台機器上執行 `mvn test`，就會看到這個失敗。這比任何靜態的 log 檔都更有說服力。
>
> ⚠️ **但引用時必須精確**：這代表「**功能測試無法偵測記憶體洩漏本身**」（第 8.2 節的核心論點成立），而不是「測試偵測到了洩漏」。這個測試失敗的是**資料完整性斷言**，它偵測到的是**崩潰造成的資料殘留**，不是造成崩潰的洩漏。**兩者不可混為一談。**

### 8.2 為什麼功能測試在原理上不可能抓到這個缺陷

功能測試的執行特性：每個測試執行數毫秒、輸入數十筆假資料。

**在毫秒尺度、數十筆資料的條件下，「有界集合」與「無界集合」的可觀測行為完全相同。** 兩者都會正確回答「有沒有重複」「top-K 是誰」。要區分它們，必須觀察**保留集合（retained set）隨投入量的成長曲線**——而這需要時間或量體維度。

### 8.3 核心結論

> **這個缺陷在功能上是完全正確的，它只在時間軸上是錯的。而功能測試沒有時間軸。**

> 補充：唯一「碰到」這起事故的測試（`DBValidationTest.buySellRatioIsBalanced`）偵測到的是**崩潰後的資料殘留**，而非造成崩潰的洩漏——而且它是在事故發生**兩週後**、由殘留資料觸發的，完全無法作為預警。這反而更強化了上述論點。

這句話同時是本次 RCA 的結論，也是一項可推廣的測試策略主張：

**功能正確性（functional correctness）與資源正確性（resource correctness）是兩個正交的品質維度。** 一個測試套件可以在前者達到 100% 覆蓋，同時在後者達到 0% 覆蓋。傳統的功能測試矩陣（輸入組合 × 邊界值 × 例外路徑）**在結構上就不包含時間軸**，因此對此類缺陷是系統性盲目的，而非偶然遺漏。

### 8.4 這類缺陷的通用特徵

| 特徵 | 本案表現 |
|------|----------|
| 功能測試全綠 | 98 個 CI 測試全數通過（洩漏本身完全未被偵測） |
| 需要時間或量體才會顯現 | 7.6 天 / 1,136 萬筆 |
| 症狀與根因在觀測上距離遙遠 | 症狀是「CPU 100%」，根因是「集合沒有上限」 |
| 存活性監控失效 | systemd 回報 `active (running)` |
| 標準診斷工具失效 | jstack / jmap attach 逾時 |

---

## 9. 證據交叉驗證

一項發現是否可信，取決於它能否由**多個獨立來源**互相印證。本次調查建立了四條獨立的驗證鏈：

| # | 驗證項 | 來源 A | 來源 B | 結果 |
|---|--------|--------|--------|------|
| 1 | GC 占用比例 | JVM 內部計數器：`GCT / 進程壽命 = 43.8%` | 作業系統 `/proc` 統計：`GC 執行緒核心秒 / 可用核心秒 = 43.4%` | ✅ 誤差 < 1% |
| 2 | 服務停止工作的時點 | JVM 日誌：首次 OOME `07-08 22:57:49` | MySQL：最後寫入 `07-08 22:56:24 UTC` | ✅ 相差 85 秒 |
| 3 | 物件累積量 | 堆直方圖（歷史記錄）：`Order ≈ 11.19M` | MySQL：`11,358,422` 筆 | ✅ 量級吻合（差異來自重複訂單與飛行中資料） |
| 4 | 螺旋是否仍在進行 | 兩次 jstat 採樣差值：`FGC +33`、`FGCT +147.9s` | 活體量測：`14 秒牆鐘 → FGCT +14 秒` | ✅ GC overhead ≈ 100% |
| 5 | 訂單總數與崩潰殘留 | `DBValidationTest` 實測：`BUY 5,679,212` + `SELL 5,679,210` = **11,358,422** | `db_order_stats.txt`：`order_count = 11,358,422` | ✅ 完全相同；且差值 2 佐證 SELL-THREAD 先陣亡 |

**另外，以下假設均已被證據明確排除：**

| 被排除的假設 | 排除依據 |
|--------------|----------|
| 檔案描述符洩漏 | `fd_count.txt` = 15（正常） |
| 執行緒洩漏 | `thread_count.txt` = 33（正常） |
| Metaspace / 類別載入洩漏 | `jstat` 顯示 `M = 98.15%`、`CCS = 91.76%`（正常範圍） |
| 業務執行緒忙迴圈 | per-thread CPU 顯示業務執行緒僅占 0.045% |
| 外部負載造成 | `E`（伊甸園區）= 0.00，無新物件配置；API 已無回應無法接受外部請求 |
| 記憶體交換（swap thrashing） | `Swap: 0B`，`smaps_rollup` 顯示 `Swap: 0 kB` |

---

## 10. 修復方案

> ⚠️ **重要狀態聲明**
> **截至本報告撰寫時（2026-07-21），以下修復尚未實作進 repo。**
> `OrderBook.java:19-21` 目前仍為無界狀態；`git log` 對該檔案僅有兩筆 commit（`d36c26f` 修正 `ConcurrentModificationException`、`0062a81` monorepo 合併），均非本項修復。
> 本節為**修復規格（remediation specification）**，非已完成工作。

### 10.1 修復一：消除主要洩漏源 `allOrders`

**現況分析：** 需先確認 `allOrders` 實際被用於什麼用途。

```java
// 現況
private final List<Order> allOrders = Collections.synchronizedList(new ArrayList<>());

public int totalOrderCount() {
    return allOrders.size();     // ← 只用到 .size()
}
```

**若 `allOrders` 僅被用於取得計數**，則整個 list 是不必要的：

```java
// 修復後
private final AtomicLong totalSubmissions = new AtomicLong();

public boolean addOrder(Order order) {
    String id = order.getOrderId();
    totalSubmissions.incrementAndGet();          // O(1) 記憶體，而非 O(t)
    orderIdFrequency.merge(id, 1, Integer::sum);
    return orders.putIfAbsent(id, order) == null;
}

public long totalOrderCount() {
    return totalSubmissions.get();
}
```

**效果：** 保留集合從 **O(t)**（隨時間線性成長）降為 **O(1)**（常數）。

**副作用與風險：**
- ⚠️ 若程式中有其他地方需要遍歷 `allOrders`（例如 `getAllOrders()` 提供給 API 或 UI），此修改會破壞該功能。**實作前必須先完整搜尋所有使用點。**
- ⚠️ 重複訂單的偵測職責已由 `orderIdFrequency` 承擔，不受影響。
- ⚠️ 若確實需要保留最近的訂單供查詢，應改為**有界的滾動視窗**（見 10.2），而非完全移除。

### 10.2 修復二：為 `orders` 與 `orderIdFrequency` 設定界線

這兩者無法直接移除（`orders` 是唯一主儲存、`orderIdFrequency` 支撐重複偵測與 top-K 功能），因此需要**淘汰策略**。

**方案 A：LRU 有界（可直接沿用專案內既有的 `OrderCache` 模式）**

```java
private final Map<String, Order> orders = Collections.synchronizedMap(
    new LinkedHashMap<String, Order>(MAX_ORDERS, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Order> eldest) {
            return size() > MAX_ORDERS;
        }
    });
```

**方案 B：時間滾動視窗**（保留最近 N 小時的訂單，由排程任務定期清除過期條目）

**⚠️ 語意衝突警告（這是修復中最需要判斷的部分）：**

`orderIdFrequency` 支撐的是 **LC-347「Top K Frequent Elements」** 功能——該功能在定義上需要**全時段（all-time）**的頻率統計。

**因此，為它設定界線不是單純的 bug 修復，而是會改變功能語意。** 必須先做產品決策：

| 選項 | 影響 |
|------|------|
| 改為「最近 N 筆/N 小時內的 top-K」 | 功能語意改變，但記憶體有界 |
| 保持全時段 top-K，但將統計外移至資料庫/Redis | 功能不變，記憶體有界，但增加外部依賴與延遲 |
| 保持全時段 top-K，但改用 Count-Min Sketch 等概率資料結構 | 記憶體有界（固定），但統計為近似值 |

> 這正是履歷中「**unbounded by specification rather than implementation**（無界源自規格而非實作疏失）」的真正含意——它不是手滑，而是「全時段統計」與「永久執行」這兩項需求之間的**設計層級衝突**。

### 10.3 修復三：加入 endurance test（耐久測試）

**這是唯一能在 CI 中攔截此類缺陷的測試型態。**

```java
@Test
@DisplayName("保留集合不應隨投入量線性成長（記憶體洩漏防護）")
void retainedSetMustNotGrowLinearly() throws Exception {
    OrderBook book = new OrderBook();
    Runtime rt = Runtime.getRuntime();

    // 第一階段：投入 100,000 筆，量測 Full GC 後的堆占用
    feedOrders(book, 100_000);
    long baseline = usedHeapAfterFullGc(rt);

    // 第二階段：再投入 900,000 筆（總量 10 倍）
    feedOrders(book, 900_000);
    long after = usedHeapAfterFullGc(rt);

    // 若集合有界，堆占用不應隨投入量成比例成長
    long growth = after - baseline;
    assertTrue(growth < baseline * 0.5,
        String.format("保留堆記憶體隨投入量線性成長（基準 %d KB → %d KB，成長 %d KB），" +
                      "顯示集合為無界。", baseline/1024, after/1024, growth/1024));
}

private long usedHeapAfterFullGc(Runtime rt) throws InterruptedException {
    for (int i = 0; i < 3; i++) { System.gc(); Thread.sleep(200); }
    return rt.totalMemory() - rt.freeMemory();
}
```

**設計要點：**

| 要點 | 說明 |
|------|------|
| 為什麼量測「Full GC 之後」的占用 | GC 前的占用包含大量待回收的短命物件，噪音極大；GC 後剩下的才是**真正的保留集合**——這正是洩漏的定義 |
| 為什麼用比例而非絕對值斷言 | 絕對值會因 JVM 版本、機器規格而異，造成測試不穩定（flaky） |
| 為什麼分兩階段比較 | 單點量測無法區分「基礎開銷」與「線性成長」；兩點才能看出斜率 |
| ⚠️ `System.gc()` 的可靠性風險 | `System.gc()` 只是**建議**，JVM 可忽略。生產級實作應改用 `-XX:+UseSerialGC` 執行測試，或透過 `ManagementFactory.getMemoryMXBean()` 與 GC notification listener 確認 Full GC 確實發生 |
| ⚠️ 測試執行時間 | 100 萬筆需控制在 CI 可接受範圍（目標 < 90 秒），必要時改用 `@Tag("endurance")` 於 nightly build 執行 |

**價值主張：** 將一個需要 **7 天**才會顯現的靜默劣化，轉化為 **90 秒內失敗的 CI 檢查**。

### 10.4 修復四：維運層加固（成本最低、優先執行）

修改 `binance-trading-engine.service`：

```ini
ExecStart=/usr/bin/java \
  -Xmx1g \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/binance-trading-engine/ \
  -XX:+ExitOnOutOfMemoryError \
  -Xlog:gc*:file=/var/log/binance-trading-engine/gc.log:time,uptime:filecount=5,filesize=20M \
  -jar /home/ubuntu/qa/trading-engine-simulator/target/trading-engine-simulator-1.0.0.jar
```

| 參數 | 作用 | 為什麼重要 |
|------|------|-----------|
| `-Xmx1g` | 明確限制最大堆為 1 GB | 讓洩漏**快速觸頂**（約 2.5 天而非 7.6 天），縮短回饋週期 |
| `-XX:+HeapDumpOnOutOfMemoryError` | OOM 時自動產生堆傾印 | **本次事故最大的取證損失**：因為沒有這個參數，事後無法 attach 取得直方圖 |
| `-XX:HeapDumpPath` | 指定傾印路徑 | 避免寫入工作目錄造成磁碟意外爆滿 |
| `-XX:+ExitOnOutOfMemoryError` | **OOM 時直接終止 JVM** | ⭐ **本案最關鍵的一項**。若當初有此參數，進程會在 07-08 立即結束，`Restart=on-failure` 會被觸發、監控會告警——不會有後續 6 天（乃至今日 13 天）的靜默空轉 |
| `-Xlog:gc*` | 輸出 GC 日誌並輪替 | 讓劣化趨勢在事前可觀測，而非事後才發現 |

### 10.5 修復五：監控層修正

**本次事故暴露的監控盲區：所有存活性檢查都只驗證「進程存在」。**

建議加入的檢查：

| 檢查項 | 實作 | 告警閾值 |
|--------|------|----------|
| HTTP 端點實際回應 | `curl -m 3 http://localhost:8092/api/status` | 逾時或非 200 即告警 |
| GC 開銷比例 | 定期採樣 `jstat -gcutil`，計算 `ΔFGCT / Δwall` | > 10% 警告，> 50% 嚴重 |
| 老年代占用率 | `jstat` 的 `O` 欄位 | 連續 3 次 > 90% 即告警 |
| 業務進度指標 | 資料庫最新訂單時間戳與現在時間的差距 | > 5 分鐘無新訂單即告警 |

> 💡 最後一項（**業務進度指標**）是本案中唯一能在第一天就發現異常的檢查方式。它不依賴任何 JVM 知識，只問一個問題：「**這個服務有沒有在做它該做的事？**」

### 10.6 修復優先順序建議

| 優先級 | 項目 | 理由 | 預估工作量 |
|--------|------|------|-----------|
| **P0** | 10.4 維運層加固 | 不需改動任何程式碼，立即降低下次事故的損害 | 10 分鐘 |
| **P0** | 10.5 業務進度監控 | 不需改動程式碼，補上最大的監控盲區 | 30 分鐘 |
| **P1** | 10.1 消除 `allOrders` | 主要洩漏源，改動範圍小 | 1–2 小時（含使用點搜尋） |
| **P1** | 10.3 endurance test | 建立回歸防護，防止問題重現 | 2–3 小時 |
| **P2** | 10.2 有界化其餘兩個集合 | 涉及功能語意決策，需先確認產品需求 | 需先討論 |

---

## 11. 預防措施

### 11.1 程式碼審查檢查清單（可直接納入團隊規範）

- [ ] 任何**長生命週期物件**（單例、服務類別、靜態欄位）持有的集合，是否有明確的容量上限或淘汰策略？
- [ ] 集合的 `add` / `put` 操作，是否有對應的 `remove` / 淘汰路徑？
- [ ] 若存在 `clear()` 之類的清除方法，**是否真的有任何地方呼叫它**？
- [ ] 這個類別原本是為什麼情境設計的？現在的使用情境是否改變了它的前提假設？
- [ ] 快取類別是否設定了容量？**非快取類別（如 Repository、Book、Registry、Log）是否也檢查過？**（本案的教訓：命名會誤導注意力）

### 11.2 測試策略層面

| 缺陷類型 | 現有覆蓋 | 建議補強 |
|----------|----------|----------|
| 功能正確性 | ✅ 98 個 CI 測試（本機含 MySQL 為 106） | — |
| 併發正確性 | ✅ 已有 idempotency / race condition 測試 | — |
| **資源正確性（時間軸）** | ❌ **零覆蓋** | **加入 endurance / soak 測試** |

**通用原則：** 任何預期**長期執行**的服務，測試矩陣中必須包含一個維度——「**當投入量 × 10 時，保留資源是否也 × 10？**」

### 11.3 部署層面

- 所有常駐 JVM 服務必須明確指定 `-Xmx`，不依賴預設人體工學
- 所有常駐 JVM 服務必須設定 `-XX:+ExitOnOutOfMemoryError`（**寧可快速失敗，不要靜默劣化**）
- 所有常駐 JVM 服務必須開啟 GC 日誌並保留輪替
- 健康檢查必須驗證**服務功能**，而非僅驗證進程存活

---

## 12. 面試問答準備

### 12.1 30 秒口頭版本

> 「我自己跑的一支 Java 模擬交易服務，執行 12 天後 CPU 被打滿、API 完全沒回應，但 systemd 顯示它還是正常運行中——監控完全看不出來。
>
> 查下去發現是訂單簿裡的集合只進不出，訂單全部留著不釋放。跑七天累積一千一百多萬筆之後記憶體被塞滿，而且**因為那些物件都還被程式參照著，GC 判定它們不是垃圾、清不掉**，所以每次 Full GC 都在做白工，清完馬上又滿，無限循環把 CPU 燒光。
>
> 過程中標準工具 jstack、jmap 都連不進去，因為進程忙到沒空回應，所以我改用 jstat——它是直接讀計數器，不需要接進進程。最後拿 MySQL 對帳確認：資料庫最後一筆訂單的時間，跟第一次 OutOfMemoryError 只差 85 秒，整條時間軸對得起來。
>
> 最有意思的是，所有功能測試從頭到尾全綠。這個 bug 在功能上完全正確，它只在時間軸上是錯的——而功能測試沒有時間軸。」

### 12.2 常見追問與答法

**Q1：「Full GC 跑完，heap 不就空出來了嗎？為什麼會卡死？」**

> 「GC 判斷一個物件能不能回收，看的是還有沒有活的參照指向它。這些訂單物件全部還在那三個集合裡被抓著，對 GC 來說都是『還在使用中』，所以掃描完也一個都丟不掉。不是 GC 效率不好——是那些物件照定義就不算垃圾。回收完堆還是滿的，就立刻再觸發下一次，形成循環。」

**Q2：「你怎麼修的？」** ⚠️ **必須照實回答**

> 「我做的是定位、根因分析和開修復規格，程式碼修復我還沒實作進去。方向是：那個只拿來計數的 list 換成 AtomicLong 計數器，另外兩個 map 加容量上限或滾動視窗，再加一個 endurance test——跑大量訂單之後檢查 Full GC 後的堆占用有沒有隨投入量線性成長。
>
> 不過有一個地方需要產品決策才能定案：那個頻率 map 支撐的是『全時段 top-K』功能，在定義上就需要保有全部歷史統計。所以幫它設上限不是單純的 bug 修復，會改變功能語意——要嘛改成滾動視窗的 top-K，要嘛把統計外移到資料庫。」

**Q3：「為什麼測試沒抓到？」**

> 「因為功能測試每個跑幾毫秒、丟幾十筆資料。在那個尺度下，有上限跟沒上限的行為完全一樣，兩種都會給出正確答案。要抓到這種缺陷，測試必須有時間或量體的維度，而功能測試在結構上就沒有這個維度——這不是漏測，是測試型態本身的系統性盲區。」

**Q4：「為什麼 jstack 連不進去？」**

> 「jstack 是靠 attach 機制運作的，它需要目標 JVM 停在一個安全點才能被檢查。但這個 JVM 一直在做 Full GC、而且記憶體壓力極大，10.5 秒內連一次回應診斷請求的機會都擠不出來。
>
> 所以這個失敗本身就是證據——一個活著的 JVM 忙到無法回應自己的診斷通道，這件事就已經排除了『只是效能稍差』的可能性。後來我改用 jstat，它是讀 JVM 寫在磁碟上的效能計數器檔案，完全不需要目標進程配合。」

**Q5：「你怎麼確定不是別的原因？（例如執行緒洩漏、fd 洩漏）」**

> 「我有做排除法。fd 數是 15、執行緒數是 33，都在正常範圍，所以排除資源洩漏。Metaspace 使用率 98%、壓縮類別空間 91%，都在正常範圍，排除類別載入洩漏。per-thread CPU 顯示燒 CPU 的是兩個 GC 執行緒各 486,000 秒，業務執行緒只有 283 秒，所以排除業務忙迴圈。而且伊甸園區是 0%，代表根本沒有新物件在配置——服務早就停止工作了。」

**Q6：「這個經驗你學到什麼？」**（最重要的一題）

> 「兩件事。
>
> 第一，**功能正確性和資源正確性是兩個正交的維度**。一套測試可以在功能上 100% 覆蓋，在資源上 0% 覆蓋。我以前設計測試矩陣都是輸入組合 × 邊界值 × 例外路徑，這次之後我會多問一個問題：當投入量放大十倍，保留的資源是不是也放大十倍。
>
> 第二，**進程活著不等於服務可用**。這次 systemd 從頭到尾都顯示 active running，任何只看 PID 的監控都會漏掉。事後我認為最有價值的一個檢查其實不需要任何 JVM 知識——就是去問資料庫『最新一筆訂單是什麼時候寫進來的』。如果超過五分鐘沒有新資料，這個服務就是壞的，不管它的進程狀態長什麼樣。」

### 12.3 可引用的關鍵數字（全部有檔案佐證）

| 數字 | 意義 | 佐證檔案 |
|------|------|----------|
| 12 天 23 小時 | 取證時的進程執行時間 | `SNAPSHOT.txt` |
| 5 天 | CPU 被打滿的持續時間（07-08 → 07-14） | `oome_lines.txt` + `INCIDENT_START.txt` |
| 7.6 天 | 靜默劣化期（服務啟動 → 崩潰） | `db_order_stats.txt` |
| **114,876** | Full GC 次數 | `jstat_gcutil.txt` |
| **491,205 秒** | Full GC 累計停頓時間（≈ 5.69 天） | `jstat_gcutil.txt` |
| **43.8%** | GC 時間占進程生命週期比例 | 計算自 `jstat` + `SNAPSHOT.txt` |
| **100%** | 老年代占用率 | `jstat_gcutil.txt` |
| **11,358,422** | 資料庫訂單筆數 | `db_order_stats.txt` |
| **85 秒** | 最後 DB 寫入 → 首次 OOME 的時間差 | `db_order_stats.txt` + `oome_lines.txt` |
| **486,279 秒** | 單一 GC 執行緒消耗的 CPU 時間 | `thread_cpu_proc.txt` |
| **283 秒** | BUY-THREAD 在 629,535 秒中獲得的 CPU | `thread_cpu_proc.txt` |
| **441 : 1** | GC 執行緒 vs 業務執行緒的 CPU 消耗比 | 計算自 `thread_cpu_proc.txt` |
| 15 / 33 | fd 數 / 執行緒數（用於排除其他假設） | `fd_count.txt` / `thread_count.txt` |
| 5,679,212 / 5,679,210 | BUY / SELL 筆數，合計 11,358,422，差值 2 | 執行 `mvn test` 即時可重現 |
| 98 / 106 | CI 測試數 / 本機含 MySQL 測試數 | `target/surefire-reports/*.xml` |

---

## 13. 證據檔案索引

所有原始證據位於 `./evidence/`，完整 SHA256 校驗清單見 `evidence/MANIFEST_SHA256_2026-07-21_copied-into-repo.txt`（共 58 個檔案）。

### 13.1 分析文件

| 檔案 | 內容 |
|------|------|
| `evidence/RCA_REPORT.md` | 原始英文版根因分析（2026-07-21） |
| `evidence/INCIDENT_REPORT.md` | 事故報告（2026-07-14） |
| `evidence/LOG_EVENT_ANALYSIS.md` | 日誌事件時間軸與模式分析 |
| `evidence/EVIDENCE_INDEX.md` | 原始證據索引 |
| `evidence/PRESERVE_SCENE.md` | 現場保留政策（PID 26810 不得終止） |

### 13.2 關鍵證據（依重要性排序）

| 檔案 | 證明什麼 |
|------|----------|
| `evidence/jstat_gcutil.txt` `_more` `_final` | ⭐ 老年代 100%、Full GC 次數與累計停頓時間 |
| `evidence/thread_cpu_proc.txt` | ⭐ CPU 被 GC 執行緒消耗，業務執行緒被餓死 |
| `evidence/db_order_stats.txt` | ⭐ 資料庫獨立交叉驗證（1,136 萬筆、85 秒時間差） |
| `evidence/java_class_histogram.txt` | ⭐ attach 逾時的原始錯誤訊息（診斷工具失效證明） |
| `evidence/oome_lines.txt` | OOME 完整序列與級聯順序 |
| `evidence/SNAPSHOT.txt` | 主機規格、進程狀態、load average |
| `evidence/api_status.txt` | HTTP API 無回應（curl 逾時） |
| `evidence/timeline.txt` | 關鍵事件時間軸與堆使用率傾印 |
| `evidence/jstat_gccause.txt` | GC 原因（G1 Evacuation / Compaction Pause） |
| `evidence/binance-trading-engine.service` | systemd unit 檔（證明未設 `-Xmx`） |
| `evidence/fd_count.txt` / `thread_count.txt` | 排除 fd 洩漏與執行緒洩漏 |
| `evidence/smaps_rollup.txt` | 記憶體細分（RSS 3.07 GiB、Swap 0） |
| `evidence/java_vm.txt` / `java_heap_info.txt` | 更多 attach 失敗證明 |
| `evidence/java_version.txt` | OpenJDK 21.0.11 |
| `evidence/service_journal_full.txt` | 完整 journal 匯出（1.3 MB） |
| `evidence/snapshot-2026-07-21T0938Z/` | 第二次取證快照（2.8 MB，證明螺旋持續中） |

### 13.3 相關原始碼

| 檔案 | 相關性 |
|------|--------|
| `trading-engine-simulator/src/main/java/com/binance/trading/engine/OrderBook.java` | **缺陷所在**（L19-21 三個無界集合、L26-31 唯一寫入路徑、L70-73 未被呼叫的 `clear()`） |
| `trading-engine-simulator/src/main/java/com/binance/trading/engine/TradingEngine.java` | 呼叫端（L60 建構子對照、L70-88 無限迴圈） |
| `trading-engine-simulator/src/main/java/com/binance/trading/engine/OrderCache.java` | **正確的對照組**（L22-28 `removeEldestEntry` 有界實作） |

---

## 14. 已知限制與未驗證項目

**基於誠信原則，以下項目必須明確標示為未驗證或已知缺口：**

| # | 項目 | 狀態 |
|---|------|------|
| 1 | **堆直方圖原始輸出未保存** | `evidence/RCA_REPORT.md:141` 記載 `Order ≈ 11.19M`，並註明來源為「prior session SA scan」。但**該次 `jhsdb jmap --histo` 的原始輸出檔並未保存於證據集中**；現存的 `java_class_histogram.txt` 內容是 attach 失敗的錯誤訊息。因此 11.19M 這個數字目前只有二手記錄，**不建議作為主要引用數字**。<br>➡️ 建議改引用有硬證據的 **MySQL 11,358,422 筆**。 |
| 2 | **修復尚未實作** | 第 10 節全部為規格，非已完成工作。`OrderBook.java` 目前仍為無界；`git log` 無相關 commit。 |
| 3 | **endurance test 尚未實作** | `grep -rliE "endurance\|soak\|longevity"` 於整個 repo 回傳空。第 10.3 節的程式碼為建議實作，未經執行驗證。 |
| 4 | **撰寫意圖為推論** | 第 7 節關於「LeetCode 框架導致無界設計」的分析，來自程式碼註解與結構的**反推**，並非來自撰寫過程的直接紀錄。 |
| 5 | **測試數量歷史值不明** | 目前實際執行數為 **98（CI）/ 106（本機含 MySQL）**，原始碼標註為 84 個 `@Test` + 5 個 `@ParameterizedTest`。事故發生當時的實際數量無法從證據驗證。 |
| 6 | **早期 GC 數據來源不明** | 部分先前文件引用 `108,997 Full GCs / 465,407s`，但此數值**未出現於本證據集任何檔案中**。保存下來的最早採樣為 `114,876 / 491,204.6s`。推測前者為更早一次採樣，但無法佐證。 |
| 7 | **時區混用** | journal 日誌為主機本地時間（UTC+8），MySQL 統計為 UTC。本報告已逐項標註，但比對時仍需留意。 |

---

## 附錄 A：關鍵概念白話說明

### 什麼是「無界（unbounded）」？

指一個集合**沒有設定任何容量上限或淘汰機制**——只要程式持續放入資料，它就會持續成長，沒有任何機制清除舊資料或阻擋新資料。

`ArrayList` / `HashMap` 在 Java 語言層面本來就沒有固定大小限制，會隨著 `add()` 自動擴充。這是它們的正常設計，不是 bug。**「無界」指的是應用層面的容量控管缺失，不是 Java 語言本身的限制。**

### 為什麼 GC 清不掉？

GC 判斷一個物件能否回收，依據的是**可達性分析（reachability analysis）**：從 GC Root（執行緒堆疊、靜態欄位等）出發，能否走到這個物件。

只要 `allOrders` 這個 list 還活著、還被程式持有，list 裡的每一個 `Order` 物件對 GC 而言就都是「可達的」，就都不是垃圾。

**這不是 GC 效率差，是這些物件在定義上就不該被回收——但程式也永遠不會放手。**

### 什麼是「heap 滿了」？

有兩個層次容易混淆：

| 層次 | 說明 | 是否為問題 |
|------|------|-----------|
| ArrayList 內部陣列滿了 | `ArrayList` 內部用固定大小陣列存資料，不夠時自動配置更大的新陣列並複製過去 | ❌ 不是問題，JVM 自動處理 |
| **JVM heap 滿了** | heap 是 JVM 存放**所有物件**的記憶體區域，大小有上限。1,136 萬個 `Order` 物件加總起來的記憶體逼近 2.91 GiB 上限 | ✅ **這才是死亡螺旋的原因** |

**精確說法：** 不是 array 這個容器「爆掉」（它從頭到尾運作正常），而是 array **抓著不放的那些物件所占用的空間**把整個 JVM heap 塞滿了。

### 什麼是「安全點（safepoint）」？

JVM 中的一個狀態點，在此所有應用執行緒都停止執行 Java 位元組碼，使 JVM 可以安全地執行全域操作（GC、執行緒傾印、去最佳化等）。

`jstack` / `jcmd` 需要目標 JVM 到達安全點才能檢查它。本案中 JVM 因持續 Full GC 與極端記憶體壓力，10.5 秒內無法提供一次可用的安全點，導致所有 attach 型工具失效。

---

**報告撰寫日期：** 2026-07-21
**證據取證日期：** 2026-07-14（基準包）、2026-07-21（第二次快照）
**現場狀態：** PID 26810 仍存活，依 `evidence/PRESERVE_SCENE.md` 政策保留中
