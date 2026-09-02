# Runbook — 服務可達但工作沒有前進

> **對應告警**：`EngineNotProgressing` · `EngineWorkerStopped` · `DatabaseWriteStalled`
> **嚴重度**：critical
> **來源**：2026-07 事故 #2（靜默降級，六天無人察覺）

---

## 觸發條件

| 告警 | 判斷式 | 持續 |
|---|---|---|
| `EngineNotProgressing` | `engine_up == 1 and rate(engine_orders_generated_total[10m]) == 0` | 10m |
| `EngineWorkerStopped` | `engine_up == 1 and engine_running == 0` | 5m |
| `DatabaseWriteStalled` | `engine_running == 1 and rate(engine_orders_generated_total[15m]) == 0` | 15m |

**閾值來源**：程式碼宣告產速約 20 筆/秒，實測 DB 每小時 71,913 筆
（≈ 19.98 筆/秒）。任何連續 10 分鐘的零成長都不可能是正常波動。

---

## 影響

**這是本系統最危險的一類故障，因為所有傳統健康檢查都會通過：**

| 檢查方式 | 事故期間的結果 |
|---|---|
| `systemctl status` | `active (running)` ✅ |
| TCP port 探測 | 已 bind ✅ |
| `GET /api/v1/status` | `200 OK` ✅ |
| K8s liveness probe | 會通過 ✅ |
| K8s readiness probe | 會通過 ✅ |
| **實際業務產出** | **零，持續六天** ❌ |

基礎設施執行緒（REST、WebSocket、scheduler）都回來了，
只有業務執行緒沒有。

---

## 立即確認（前 3 分鐘）

```bash
# 1. 服務自報怎麼說
curl -s http://localhost:8092/api/v1/status | jq .

# 2. 計數器有沒有在動 —— 這是唯一可信的訊號
for i in 1 2 3; do
  curl -s http://localhost:8092/api/v1/status | jq -r '.ordersGenerated'
  sleep 10
done
# 三次數字相同 = 確認停滯

# 3. 從 DB 側獨立驗證（不相信服務自報）
mysql -u binance_user -p binance_test_db -e "
  SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:00') AS hr, COUNT(*) AS rows_written
  FROM orders WHERE created_at > NOW() - INTERVAL 8 HOUR
  GROUP BY hr ORDER BY hr;"
# 正常應為每小時約 71,900 筆；塌到 0 即確認

# 4. 進程是否被重啟過（找觸發點）
systemctl show binance-trading-engine -p ExecMainStartTimestamp
journalctl -u binance-trading-engine --since "24 hours ago" | grep -iE "signal|SIGTERM|143|Stopped|Started"

# 5. 是不是自動更新造成的
journalctl -u unattended-upgrades --since "24 hours ago" | tail -30
```

---

## 止血

```bash
# 透過 API 重新啟動產生器（不重啟進程，保留現場）
curl -s -X POST http://localhost:8092/api/v1/control/start

# 確認恢復
sleep 30 && curl -s http://localhost:8092/api/v1/status | jq '.ordersGenerated'
```

**如果 API 無效才重啟服務** —— 但重啟前務必先保留現場：

```bash
tools/preserve-scene.sh   # 或參照 PRESERVE_SCENE.md 手動採集
sudo systemctl restart binance-trading-engine
```

---

## 根因調查

已知根因（2026-07 事故 #2）：

1. `unattended-upgrades` 重啟了 MySQL
2. 連帶對應用程式送出 `SIGTERM`（journal 顯示 `exit code 143` = 128+15）
3. systemd `Restart=on-failure` **成功重啟了進程**
4. 但產生器的啟停由一個 **in-memory `AtomicBoolean`** 控制，
   重啟後預設回到 `false`
5. → 進程活著、port 開著、API 回 200，業務執行緒卻沒有啟動

**要確認是否為同一根因**，檢查：
- journal 裡有沒有 `exit code 143`
- 重啟時間點是否對齊 `unattended-upgrades` 的執行時間
- 產生器旗標在重啟後是否為 `false`

---

## 事後

- [ ] 已修：狀態改為開機時自動恢復（不再依賴 in-memory 旗標）
- [ ] 已補：本告警（把可靠度訊號從「進程存活」改為「工作進度」）
- [ ] 待辦：在 systemd unit 加上 `ExecStartPost` 驗證業務執行緒已啟動
- [ ] 待辦：`unattended-upgrades` 排除 MySQL，或設定維護窗口

**這次事故的核心教訓**：
> 自動修復（systemd restart）與可觀測性是會互相打架的。
> 自動修復讓故障「看起來」被解決了，反而延後了發現時間。
