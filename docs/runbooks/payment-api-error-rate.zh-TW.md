# Runbook — payment-api 伺服器錯誤率

**[English](payment-api-error-rate.md)** | 繁體中文

> **對應告警**：`PaymentApiErrorRateHigh`（5xx 佔比 > 1%，5m）· critical
> **資料來源**：`payment_requests_total`，由服務自己在 `/metrics` 暴露

---

## 觸發條件

```promql
sum by (deployment) (rate(payment_requests_total{status=~"5.."}[5m]))
/
sum by (deployment) (rate(payment_requests_total[5m]))
> 0.01
```

**閾值怎麼來的**：這一條不是從實測基準反推的，因為正常運行時 5xx 比例
就是零。1% 代表每一百個請求失敗一個 —— 低於任何延遲症狀會浮現的程度，
但對一個沒有已知錯誤模式的服務來說遠高於雜訊。
如果之後引入了合理的錯誤類別（例如會被成功重試的下游逾時），要重新推導。

---

## 這條告警以前為什麼不存在

在此之前，關於這支服務的每一個訊號都來自**外部**：對 `/health` 的黑箱探測。
那個探測回答的是「行程有沒有在聽」，而它會一邊回報 100% 成功，
一邊讓 `/api/v1/payments` 對每一個真實呼叫者噴 500 —— 因為它從來沒打過那個端點。

| 訊號 | 看得到 /payments 的 5xx 嗎？ |
|---|---|
| `probe_success`（黑箱） | ❌ 只探測 `/health` |
| `ServiceSlowResponse` | ❌ 量的是時間，不是狀態碼 |
| `EngineNotProgressing` | ❌ 不同服務 |
| `payment_requests_total` | ✅ |

這跟事故 #1、#2 是同一類盲點：**一個外部觀測者回報健康，
而它沒在看的那個東西壞了。**

---

## `deployment` 標籤很重要

同一份 image 跑在三個地方，其中兩個有被採集：

| `deployment` | 位置 | 埠 |
|---|---|---|
| `host` | systemd，`binance-payment-api.service` | 8091 |
| `container` | Docker | 8094 |

| 觀察 | 意義 |
|---|---|
| 只有一邊出錯 | 環境差異 —— 設定、資源限制、某個相依只有一邊連得到 |
| 兩邊都出錯 | 程式本身，或共用的相依（MySQL、Redis） |

這個對照是免費的診斷:程式碼相同、環境不同、其中一邊壞了。

---

## 立即確認（前 3 分鐘）

```bash
# 1. 哪個 deployment、哪條路由、哪個狀態碼
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (deployment,route,status) (rate(payment_requests_total{status=~"5.."}[5m]))' \
  | jq -r '.data.result[] | "\(.metric.deployment)\t\(.metric.route)\t\(.metric.status)\t\(.value[1])"'

# 2. 總請求速率 —— 才知道那 1% 是一個請求還是一萬個
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (deployment) (rate(payment_requests_total[5m]))' \
  | jq -r '.data.result[] | "\(.metric.deployment)\t\(.value[1]) req/s"'

# 3. 服務自己記了什麼
journalctl -u binance-payment-api -n 100 --no-pager | grep -iE 'exception|error|500'
docker logs payment-api 2>&1 | tail -50

# 4. 它的相依健康嗎
make obs-alerts | grep -iE 'mysql|redis'
```

---

## 分流

| 觀察 | 可能原因 | 往哪走 |
|---|---|---|
| 只有 `/api/v1/payments` 噴 5xx，且 MySQL 告警亮著 | Repository 層失敗 | [mysql-down](mysql-down.zh-TW.md) / [mysql-saturation](mysql-saturation.zh-TW.md) |
| 所有路由都噴 5xx | 行程本身不健康 —— 查 GC 與執行緒 | [gc-death-spiral](gc-death-spiral.zh-TW.md) |
| 只有 `deployment="container"` | 容器設定:環境變數、記憶體限制、連不到相依 | `docker logs payment-api` |
| 只有 `deployment="host"` | systemd unit 的環境，或 jar 是舊的 | `systemctl cat binance-payment-api` |
| 5xx 比例高但請求量趨近零 | 極低流量下的少數失敗 —— 叫人之前先確認 | 看第 2 步的絕對速率 |

⚠️ **第 2 步的存在就是為了擋掉誤報。** 流量極低時，
單一一個失敗請求就是 5 分鐘視窗的 100%。**比例一定要跟絕對速率一起看。**

---

## 止血

```bash
# 容器：重啟很便宜，也不會掉資料（記憶體 repository）
make docker-stop && make docker-run

# 主機：同理，走 systemd
sudo systemctl restart binance-payment-api

# Kubernetes：滾動更新，順便量這次滾動本身的代價
make k8s-drill
```

⚠️ 如果錯誤來自某個相依掛掉，重啟 API 完全沒用，只會銷毀現場。
先確認第 4 步。

---

## 根因調查

1. **比對兩個 deployment。** 相同的程式在一個環境壞、另一個沒壞，
   搜尋範圍會大幅縮小 —— 從差異處開始查
   （`docker inspect payment-api` 對照 `systemctl cat binance-payment-api`）。

2. **確認錯誤是不是跟某次部署對齊。** `payment_requests_total` 是會在重啟時
   歸零的計數器，所以 `jvm_process_uptime_seconds` 的陡降就標出了每一次部署的邊界：

   ```bash
   curl -sG http://localhost:9090/api/v1/query_range --data-urlencode \
     'query=jvm_process_uptime_seconds' --data-urlencode "start=$(date -u -d '-6 hours' +%s)" \
     --data-urlencode "end=$(date -u +%s)" --data-urlencode 'step=300' \
     | jq -r '.data.result[] | .metric.deployment as $d | .values[] | "\($d) \(.[0]) \(.[1])"'
   ```

3. **把延遲直方圖跟錯誤放在一起看。** 快速失敗通常是驗證或權限；
   緩慢失敗通常是對下游的逾時。

---

## 事後

- [ ] 這類錯誤該不該有自己的狀態碼與自己的 SLI？
      一個預期內、且會被成功重試的 5xx，不該消耗錯誤預算。
- [ ] 如果原因是 `host` 與 `container` 之間的環境漂移，
      那個差異應該放進會被 review 的設定裡，而不是留在兩個各自為政的地方。
- [ ] 考慮加一個會實際打 `/api/v1/payments` 的黑箱探測，而不是只打 `/health` ——
      這條告警依賴真實流量存在，而目前打到這支服務的流量都是合成的。
