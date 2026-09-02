# Runbook — JVM GC 死亡螺旋

> **對應告警**：`JvmGcTimeRatioHigh` · `JvmFullGcRateHigh` · `JvmOldGenHigh` · `JvmHeapGrowthUnbounded`
> **嚴重度**：critical
> **來源**：2026-07-14 事故 #1（完整 RCA 見 [`docs/incident-2026-07-14-gc-death-spiral/`](../incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md)）

---

## 觸發條件與閾值來源

| 告警 | 判斷式 | 事故實測值 |
|---|---|---|
| `JvmOldGenHigh` | `jvm_oldgen_utilization_ratio > 0.85` | 事故時 **0.9999** |
| `JvmGcTimeRatioHigh` | `jvm_gc_full_seconds_total / jvm_uptime_seconds > 0.10` | 事故時 **0.70**（491,218s / 698,732s）|
| `JvmFullGcRateHigh` | `rate(jvm_gc_full_count[5m]) > 0.1` | 事故累計 **114,879 次 Full GC** |
| `JvmHeapGrowthUnbounded` | `predict_linear(jvm_oldgen_used_bytes[6h], 24h) > capacity` | 事故前 6 天即可預警 |

**閾值全部由事故實測值反推**，不是抄來的預設值。
10% 這個門檻代表：每 10 秒就有 1 秒是 stop-the-world，
此時延遲已經明顯劣化但系統尚可救。

---

## 影響

死亡螺旋的關鍵特徵是 **進程不會死**：

- 物件仍被集合強參照（strongly reachable）→ GC 判定它們不是垃圾
- 每次 Full GC 回收不到空間 → 回收完堆依然是滿的 → 立刻再次觸發
- CPU 被 GC 執行緒占滿，業務執行緒被餓死
- HTTP API 完全無回應
- **但 systemd 仍回報 `active (running)`，所有 liveness check 通過**

`OutOfMemoryError` 會出現在日誌裡，但**不會終止進程**。

---

## 立即確認（前 3 分鐘）

```bash
PID=$(pgrep -f trading-engine-simulator | head -1)

# 1. GC 佔比 —— 最關鍵的單一數字
jstat -gc $PID | awk 'NR==2 {printf "Full GC 次數=%s  累計 STW=%.0fs\n", $13, $16}'

# 2. 老年代使用率
jstat -gcutil $PID 1000 5     # O 欄接近 100 且不下降 = 確認

# 3. 進程存活時間（算佔比用）
awk '{print $22/100 " 秒"}' /proc/$PID/stat

# 4. GC 執行緒是不是吃滿 CPU
top -H -p $PID -b -n 1 | head -20   # 找 GC task thread

# ⚠️ 如果 jstat / jcmd / jstack 逾時或無回應：
#    那不是「沒有資料」，那是確診證據 —— 見 jstat-attach-failed.md
```

---

## 止血

**在重啟之前先保留現場。** 重啟會讓根因永遠查不出來。

```bash
# 1. 保留現場（約 30 秒，見 PRESERVE_SCENE.md）
tools/preserve-scene.sh

# 2. 從 DB 側界定影響範圍（JVM 已經問不動了）
mysql -u binance_user -p binance_test_db -e "
  SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM orders;"

# 3. 確認影響範圍後才重啟
sudo systemctl restart binance-trading-engine
```

**重啟只是止血，不是修復** —— 洩漏會重新開始累積，
以事故的實測速率大約 7.6 天後會再次發生。

---

## 根因調查

死亡螺旋幾乎一定是**無界集合**（unbounded collection）：
長生命週期物件上的集合欄位，只進不出、沒有容量上限、沒有淘汰機制。

```bash
# 1. 找出佔用最多的類別
jcmd $PID GC.class_histogram | head -20
# （attach 不動時，改看已保留的 java_class_histogram.txt）

# 2. 用 CI 閘門掃描原始碼
tools/check-bounded-collections.sh      # Java
tools/check-bounded-collections-ts.sh   # TypeScript
```

**已知根因（事故 #1）**：`OrderBook` 有三個無界集合，
每筆訂單物件被永久持有。以每秒約 17 筆的速率執行 7.6 天、
累積 **11,358,422 筆**後耗盡 2.91 GiB heap。

---

## 事後

- [x] 已修：三個集合全部有界化（retained set 從 O(t) 降為 O(1)）
- [x] 已補：耐久測試（retained 1,000 → 100,000、post-GC heap 29MB → 70MB，
      刻意 revert 修復以驗證測試真的會失敗）
- [x] 已補：CI 閘門 `check-bounded-collections.sh`，擋 PR
- [x] 已補：本組告警（閾值由事故實測值反推）
- [ ] 待辦：JVM 啟動參數加上 `-XX:+HeapDumpOnOutOfMemoryError`

**核心教訓**：
> 「進程還活著」是最弱的可靠度訊號。
> 這次事故中它不只沒有幫助，還主動掩蓋了故障六天。
