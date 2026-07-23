# 事故案例：JVM 記憶體洩漏導致 GC 死亡螺旋

> `trading-engine-simulator` 模組 · PID 26810 · 2026-07-08 起 · **現場保留中**

## 這是什麼

一支自架的 Java 交易模擬服務，因為 `OrderBook` 中三個無界集合累積了 1,136 萬筆訂單物件，
耗盡 JVM 堆記憶體後陷入無效的 Full GC 無限循環，把 2 核心主機的 CPU 完全占滿超過 5 天。

**而 systemd 全程回報服務 `active (running)`，所有功能測試全數通過。**

## 從哪裡開始讀

| 我想要… | 讀這個 |
|---------|--------|
| **完整的中文根因分析**（主要文件） | 📄 **[`RCA-zh-TW.md`](./RCA-zh-TW.md)** |
| **防止再犯的檢查清單** | ✅ **[`../checklists/resource-safety-zh-TW.md`](../checklists/resource-safety-zh-TW.md)** |
| 30 秒口頭版本 + 面試追問準備 | [`RCA-zh-TW.md` 第 12 節](./RCA-zh-TW.md#12-面試問答準備) |
| 修復方案與程式碼 | [`RCA-zh-TW.md` 第 10 節](./RCA-zh-TW.md#10-修復方案) |
| 原始英文分析 | [`evidence/RCA_REPORT.md`](./evidence/RCA_REPORT.md) |
| 原始 log 與現場快照 | [`evidence/`](./evidence/) |

## 一分鐘摘要

```
OrderBook 三個集合無界（只 add，無 remove）
    ↓  17.29 筆/秒 × 7.6 天
累積 11,358,422 筆訂單物件，全部被強參照持有
    ↓
JVM 堆（2.91 GiB，未設 -Xmx）耗盡 → 老年代 100%
    ↓
Full GC 啟動，但物件全部 strongly reachable → 回收不到任何空間
    ↓
回收完堆依然滿 → 立即再次 Full GC → 無限循環（死亡螺旋）
    ↓
2 個 GC 執行緒各燒 486,000 秒 CPU；BUY-THREAD 在 629,535 秒中僅得 283 秒
    ↓
API 完全無回應 —— 但進程從未結束，systemd 顯示 active (running)
```

## 關鍵數據

| 指標 | 數值 |
|------|------|
| 老年代占用率 | **100%** |
| Full GC 次數 | **114,876** |
| Full GC 累計停頓 | **491,205 秒**（≈ 5.69 天） |
| GC 占進程生命週期 | **43.8%**（兩種獨立算法互相印證） |
| 資料庫訂單筆數 | **11,358,422** |
| 最後 DB 寫入 → 首次 OOME | **85 秒** |
| GC 執行緒 vs 業務執行緒 CPU 比 | **441 : 1** |
| CI 測試通過率 | **98 / 98 全綠**（洩漏完全未被偵測） |
| 事故遺留的殘留資料 | `BUY 5,679,212` + `SELL 5,679,210` = **11,358,422**（差值 2） |

## 缺陷位置

`trading-engine-simulator/src/main/java/com/binance/trading/engine/OrderBook.java` **L19-21**

```java
private final Map<String, Order>   orders            = new ConcurrentHashMap<>();
private final Map<String, Integer> orderIdFrequency  = new ConcurrentHashMap<>();
private final List<Order>          allOrders         = Collections.synchronizedList(new ArrayList<>());
// 三個集合、三個寫入操作（L28-30）、零個移除操作
```

對照組：同一個套件下的 `OrderCache.java` L22-28 是**正確有界**的（`removeEldestEntry`）。

## 核心結論

> **這個缺陷在功能上完全正確，它只在時間軸上是錯的——而功能測試沒有時間軸。**

## ⚠️ 狀態聲明

- ✅ **`OrderBook` 已修復（2026-07-23）** — 三個集合全部有界化，保留集合從 O(t) 降為 O(1)。
- ✅ **endurance test 已補上** — `OrderBookRetentionTest`，並經對照實驗證明有效
  （無界時保留筆數 1,000 → 100,000、GC 後堆占用 29 MB → 70 MB，斷言正確失敗）。
- ✅ **同類欄位全數處理完畢** — 檢查工具掃描歸零（11 個聲明、0 違規），且已接進 CI。
- ✅ **維運層 unit 檔已提供** — [`deploy/binance-trading-engine.service`](../../deploy/binance-trading-engine.service)，
  含 `-Xmx1g`、`-XX:+ExitOnOutOfMemoryError`、`-XX:+HeapDumpOnOutOfMemoryError`、GC log，
  已通過 `systemd-analyze verify`。
- ⬜ **仍未實作** — 上述 unit 檔**尚未套用到主機**（現場保留政策禁止重啟 PID 26810），
  以及監控層的業務進度告警。
- 其餘已知限制與未驗證項目見 [第 14 節](./RCA-zh-TW.md#14-已知限制與未驗證項目)。

## 證據完整性

- 基準包取證：2026-07-14 15:01:22 UTC
- 第二次快照：2026-07-21 09:38 UTC
- 共 58 個檔案，SHA256 清單見 [`evidence/MANIFEST_SHA256_2026-07-21_copied-into-repo.txt`](./evidence/MANIFEST_SHA256_2026-07-21_copied-into-repo.txt)
- 複製進本 repo 時已用原始 `EVIDENCE_SHA256.txt` 驗證：**8/8 通過，0 失敗**
- 現場保留政策見 [`evidence/PRESERVE_SCENE.md`](./evidence/PRESERVE_SCENE.md)：**PID 26810 未經同意不得終止或重啟**
