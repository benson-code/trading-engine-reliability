# Runbook — 監控系統自己壞了

**[English](monitoring-degraded.md)** | 繁體中文

> **對應告警**：`DeadMansSwitch` · `ScrapeTargetDown` · `ScrapeDurationHigh` · `TextfileCollectorStale` · `TextfileCollectorError` · `PrometheusRuleEvaluationFailing` · `PrometheusTsdbCompactionFailing` · `AlertmanagerNotificationFailing`
> **嚴重度**：critical / warning（心跳為 none）
> **來源**：把事故 #1 與 #2 的教訓，套用在監控堆疊自己身上

---

## 這一組為什麼存在

本專案兩次事故是同一個形狀：**服務安靜下來，而所有人把沉默當成健康。**

監控堆疊會用完全一樣的方式壞掉。規則評估停了、採集目標消失了、通知送不出去了 ——
而儀表板上什麼都不會變紅，因為根本沒有資料進來把它變紅。

> **「沒有告警」有兩種可能：一切正常，或告警系統死了。**
> 這一組的存在，就是為了把這兩種區分開來。

---

## 觸發條件

| 告警 | 判斷式 | 持續 | 嚴重度 |
|---|---|---|---|
| `DeadMansSwitch` | `vector(1)` —— 永遠在響 | — | none |
| `ScrapeTargetDown` | `up{job!="node"} == 0` | 5m | warning |
| `ScrapeDurationHigh` | `scrape_duration_seconds > 5` | 10m | warning |
| `PrometheusRuleEvaluationFailing` | `increase(prometheus_rule_evaluation_failures_total[10m]) > 0` | 5m | critical |
| `PrometheusTsdbCompactionFailing` | `increase(prometheus_tsdb_compactions_failed_total[1h]) > 0` | 15m | warning |
| `TextfileCollectorStale` | `time() - node_textfile_mtime_seconds > 180` | 2m | critical |
| `TextfileCollectorError` | `node_textfile_scrape_error > 0` | 5m | warning |
| `AlertmanagerNotificationFailing` | `increase(alertmanager_notifications_failed_total[10m]) > 0` | 5m | critical |

`ScrapeTargetDown` 排除 `job="node"`，因為主機本身由
[host-down](host-down.zh-TW.md) 負責。

---

## ⚠️ `DeadMansSwitch` 的判讀方式是反過來的

這條**刻意永遠處於 firing 狀態**。它不代表被監控的服務有任何問題。

| | 意義 |
|---|---|
| 你收得到它 | Prometheus → 規則評估 → Alertmanager → webhook 這整條鏈路是活的 |
| **你收不到它了** | **鏈路上有東西死了。這才是事件本身。** |

Alertmanager 每 5 分鐘把它送到 `heartbeat` 接收端。正式環境會改送到外部
heartbeat 服務（Dead Man's Snitch、Healthchecks.io 之類），由對方在
**停止收到**時反過來通知你。那個服務的逾時要設得遠大於 5 分鐘 ——
建議 15 分鐘，這樣偶爾漏送一次不會誤叫人。

**這個模式最典型的踩雷方式**，是某條抑制規則不小心把心跳吃掉了。
`alertmanager.yml` 裡每一條可能命中它的 `target_matchers` 都明確加了
`alertname != "DeadMansSwitch"`。你之後新增抑制規則時，請先拿心跳對一次。

---

## ⚠️ 凍結的指標比消失的指標更危險

`jvm.prom` 與 `engine.prom` 由 cron 每 30 秒寫入，node_exporter 再讀出來。
cron 一旦掛掉或腳本開始失敗，檔案就停止更新 ——
**但 node_exporter 會永遠繼續提供它最後讀到的那組值。**

指標不會消失，它會**凍結**。而凍結的指標看起來是健康的：

| 凍結的指標 | 後果 |
|---|---|
| `jvm_jstat_attach_success` 卡在 `1` | **`JstatAttachFailed` 永遠不可能觸發** —— 而那條告警存在的唯一理由，就是 attach 失敗是事故 #1 的關鍵診斷訊號 |
| `jvm_oldgen_utilization_ratio` 卡在健康值 | JVM 可以一路走進死亡螺旋，儀表板全綠 |
| `engine_orders_generated_total` 卡住 | `EngineNotProgressing` 會叫，然後怪罪引擎 —— 但死的其實是採集器 |

`TextfileCollectorStale` 是唯一能區分「服務沒事」與「看著服務的那隻眼睛閉上了」的東西。
這個盲點有對應的可執行測試，見
[`alerts_test.yml`](../../deploy/observability/prometheus/alerts_test.yml) ——
它斷言採集器死亡期間 `JstatAttachFailed` 全程沉默，而 `TextfileCollectorStale` 抓得到。

```bash
# cron 還在跑嗎？
crontab -l | grep jstat

# 每個檔案幾秒沒更新了？
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=time() - node_textfile_mtime_seconds' \
  | jq -r '.data.result[] | "\(.metric.file)\t\(.value[1])s"'

# 手動跑一次看它說什麼
deploy/observability/jstat-exporter.sh; echo "exit=$?"
```

---

## 立即確認（前 3 分鐘）

```bash
# 1. Prometheus 自己健康嗎？規則還在評估嗎？
curl -s http://localhost:9090/-/healthy
curl -s http://localhost:9090/api/v1/rules | jq -r \
  '.data.groups[] | "\(.name)\tlast_eval=\(.lastEvaluation)\tdur=\(.evaluationTime)"'

# 2. 哪些採集目標掛了
curl -s http://localhost:9090/api/v1/targets | jq -r \
  '.data.activeTargets[] | select(.health!="up") | "\(.labels.job)\t\(.scrapeUrl)\t\(.lastError)"'

# 3. Alertmanager 收得到、送得出去嗎
curl -s http://localhost:9093/-/healthy
curl -s http://localhost:9093/api/v2/alerts | jq -r '.[] | .labels.alertname' | sort | uniq -c

# 4. 心跳真的有送到接收端嗎
docker logs obs-alertmanager 2>&1 | tail -20
```

---

## 分流

| 觀察 | 判斷 | 往哪走 |
|---|---|---|
| 心跳停了、Prometheus 不健康 | Prometheus 掛了或卡死 | 重啟；檢查磁碟與記憶體 |
| 心跳停了、Prometheus 健康 | Alertmanager 或 webhook 路徑壞了 | 查 `AlertmanagerNotificationFailing` |
| `PrometheusRuleEvaluationFailing` | **有一整組規則根本沒被計算** | 見下節，這是最危險的一條 |
| 單一 job 的 `ScrapeTargetDown` | 該 job 的指標凍結了，它的告警已經安靜 | 修 exporter 或該服務 |
| `ScrapeDurationHigh` | 目標本身在飽和 | [host-saturation](host-saturation.zh-TW.md) |
| `PrometheusTsdbCompactionFailing` | 磁碟或權限 | [disk-capacity](disk-capacity.zh-TW.md) |

---

## 規則評估失敗是最危險的一條

當一個規則群組評估失敗，**它底下的告警完全不會被計算**。
不會響、也不會解除 —— 它們只是不存在了。除了這條告警之外，
任何地方都不會有症狀。

```bash
# 是哪一組在失敗、為什麼
curl -s http://localhost:9090/api/v1/rules | jq -r \
  '.data.groups[] | select(.rules[]?.health? == "err") |
   "\(.name): \(.rules[] | select(.health=="err") | .lastError)"'

# 常見原因：
#   - 規則引用了已經不存在的指標
#   - subquery 太貴而逾時（SLO 那組最容易中）
#   - 記錄規則依賴另一條先失敗的記錄規則
```

重新載入前先驗證規則檔：

```bash
make obs-validate     # promtool 同時檢查 alerts.yml 與 slo.yml
make obs-reload       # 熱載入，不重啟容器
```

---

## 止血

```bash
# 只重載設定，不動 TSDB
make obs-reload

# 如果是卡死而不是設定錯誤
docker restart obs-prometheus

# 事後確認整條鏈路：心跳應在一個 group_interval（5 分鐘）內重新出現
docker logs obs-alertmanager 2>&1 | tail -5
```

⚠️ 重啟 Prometheus 不會遺失持久資料（TSDB 在 volume 上），
但**會重置 `slo:*` 記錄序列的累積狀態**。長視窗的 SLO 比率在重新填滿之前
會顯示得比實際樂觀。見 [`docs/slo.zh-TW.md`](../slo.zh-TW.md) 的「已知限制」。

---

## 事後

- [ ] 如果是某個採集目標掛掉，該服務本身有沒有獨立的告警？
      如果唯一的訊號是 `ScrapeTargetDown`，代表那個服務沒有獨立的存活檢查，該補一條。
- [ ] 把 `DeadMansSwitch` 接到**外部** heartbeat 服務。
      送到同一台主機上的 webhook，證明力比看起來低很多 ——
      主機掛掉時發送端和接收端會一起死，沒有人會被通知。
- [ ] 之後新增任何抑制規則，都要先拿心跳對一次。
