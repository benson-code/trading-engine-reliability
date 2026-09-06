# Runbook — 錯誤預算燒錄率過高

> **對應告警**：`SLOFastBurnAvailability` · `SLOSlowBurnAvailability` · `SLOFastBurnLatency` · `SLOSlowBurnLatency` · `SLOFastBurnWorkProgress` · `SLOSlowBurnWorkProgress`
> **嚴重度**：fast burn = critical／slow burn = warning
> **來源**：SLO 層（見 [`docs/slo.md`](../slo.md)），目標值由 2026-09-06 實測推導

---

## 觸發條件

燒錄率 = 目前失誤率 ÷ 預算允許的失誤率。`1x` 代表剛好在 30 天用完預算。

| 告警 | 長視窗 | 短視窗 | 燒錄率 | 持續 | 意義 |
|---|---|---|---|---|---|
| `SLOFastBurn*` | 1h | 5m | 14.4x | 2m | 約 2 天燒光 30 天預算 |
| `SLOSlowBurn*` | 6h | 30m | 6x | 15m | 約 5 天燒光 |

**為什麼要長短視窗同時成立**：長視窗負責靈敏度（確認真的在燒），
短視窗負責重置（燒完就自己好）。只看長視窗的話，一次短暫爆發會讓
告警在事情早就恢復之後還亮好幾個小時，然後大家開始無視它。

| SLO | 目標 | 錯誤預算 | fast burn 門檻 |
|---|---|---|---|
| `payment_api_availability` | 99.9% | 0.1% | 失誤率 > 1.44% |
| `payment_api_latency` | 99.5% | 0.5% | 失誤率 > 7.2% |
| `engine_work_progress` | 99% | 1% | 失誤率 > 14.4% |

---

## 影響

燒錄率告警**不是故障告警**，它回答的是另一個問題：

| | 問的問題 | 例子 |
|---|---|---|
| `alerts.yml` | 現在有東西壞了嗎？ | `ServiceDown`、`JvmOldGenHigh` |
| `slo.yml` | 我們還剩多少犯錯額度？ | 本 runbook |

所以它可能在**沒有任何其他告警亮著**的情況下觸發 —— 那代表有一種
慢性失效正在持續消耗預算，只是每一次都還不到症狀告警的門檻。

> ⚠️ **`SLOFastBurnWorkProgress` 特別容易被誤判成誤報。**
> 2026-09-03 05:46–07:41 UTC，這台機器上工作進度停了 115 分鐘，
> 而同一時間 `probe_success = 1`、`probe_duration_seconds ≈ 1ms`、
> `jvm_uptime_seconds` 持續遞增（行程根本沒重啟）。
> **可用性與延遲兩個 SLI 全程 100%。**
> 健康檢查是綠的，不代表這條是誤報 —— 那正是它存在的理由。

---

## 立即確認（前 3 分鐘）

```bash
# 1. 是哪一個 SLO 在燒、燒多快
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=slo:error_budget_remaining' | jq -r \
  '.data.result[] | "\(.metric.slo)\t\(.value[1])"'

# 2. 三個 SLI 現在各是多少（分辨是全面性還是單一面向）
for s in payment_api_availability payment_api_latency engine_work_progress; do
  printf '%-28s ' "$s"
  curl -sG http://localhost:9090/api/v1/query \
    --data-urlencode "query=slo:${s}:ratio_rate1h" | jq -r '.data.result[0].value[1] // "no data"'
done

# 3. 有沒有症狀告警同時亮著
make obs-alerts
```

**分流：**

| 觀察 | 判斷 | 往哪走 |
|---|---|---|
| 可用性在燒 + `ServiceDown` 亮 | 真的掛了 | [`service-down.md`](service-down.md) |
| 延遲在燒 + `JvmGcTimeRatioHigh` 亮 | 事故 #1 的形狀 | [`gc-death-spiral.md`](gc-death-spiral.md) |
| 延遲在燒但 JVM 正常 | 下游或主機飽和 | [`host-saturation.md`](host-saturation.md) |
| 工作進度在燒但探測全綠 | **事故 #2 的形狀** | [`engine-not-progressing.md`](engine-not-progressing.md) |
| 三個都在燒 | 主機層問題 | [`host-down.md`](host-down.md) |

---

## 止血

燒錄率告警本身沒有「止血」動作 —— 要止的是底下那個真正的故障，
照上表轉到對應的 runbook。

但有一個**決策**是這一層獨有的：

```
預算還有剩  → 可以繼續發版、繼續做風險變更
預算見底    → 凍結一切非必要變更，把可靠度工作排到最前面
預算超支    → 停止發布新功能，直到 30 天視窗滾動回正
```

查目前狀態：

```bash
make slo
```

這是 SLO 的實際用途：**它把「要不要繼續冒險」從吵架變成查表。**

---

## 根因調查

1. **先確認不是量測問題。** 記錄規則不會回溯 —— 如果 `slo.yml` 是最近
   才加進去或改過，`ratio_rate3d` / `ratio_rate30d` 會從那一刻才開始累積，
   在填滿之前數字偏樂觀。用 `make slo` 直接對原始指標回算，可以繞開這點。

2. **找出燒錄集中在哪一段時間。** 平均值會把一次 115 分鐘的完整中斷
   稀釋成「97%」。要看的是分布，不是平均：

   ```bash
   curl -sG http://localhost:9090/api/v1/query_range \
     --data-urlencode 'query=slo:engine_work_progress:good' \
     --data-urlencode "start=$(date -u -d '-3 days' +%s)" \
     --data-urlencode "end=$(date -u +%s)" \
     --data-urlencode 'step=300' | jq -r \
     '.data.result[0].values[] | select(.[1]=="0") | .[0]' | head -20
   ```

3. **對照那段時間的其他訊號**：`engine_running`、`jvm_uptime_seconds`
   （遞增 = 行程沒重啟）、`journalctl -u binance-trading-engine`。

---

## 事後

- 如果這次燒錄是**已知且可接受**的（例如刻意的維護窗口），不要調鬆
  SLO 目標來讓告警閉嘴。正確做法是記錄這次消耗，並在 `docs/slo.md`
  補上該不該把它排除在 SLI 之外的理由。
- 如果同一個形狀重複出現，那不是預算問題，是設計問題 —— 該修的是
  底下的服務，不是門檻。
- 目標值本身要隨實測重新校準；`docs/slo.md` 記錄了每個數字的來源，
  改目標時一併更新那份文件，不要只改 `slo.yml`。
