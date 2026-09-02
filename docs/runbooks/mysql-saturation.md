# Runbook — MySQL 飽和

> **對應告警**：`MysqlConnectionsHigh` (>80% max_connections, 5m) · `MysqlSlowQueriesRising` (>0.1/s, 10m)

## 基準值
`max_connections = 151`（預設值）。正常運行時 `threads_connected` 約 1–5。

## 立即確認
```bash
mysql -u binance_user -p -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'"
mysql -u binance_user -p -e "SHOW FULL PROCESSLIST" | head -30
mysql -u binance_user -p -e "SHOW GLOBAL STATUS LIKE 'Slow_queries'"

# 哪些查詢在跑久
mysql -u binance_user -p -e "
  SELECT id, user, time, state, LEFT(info,80) AS query
  FROM information_schema.processlist
  WHERE command != 'Sleep' AND time > 5 ORDER BY time DESC;"
```

## 常見來源（本專案）
| 來源 | 說明 |
|---|---|
| 完整性檢查全表掃描 | `tools/check-db-integrity.sh` 設 `WINDOW=0` 會掃 3,700 萬筆 |
| 連線未釋放 | 應用連線池洩漏，`Sleep` 狀態連線持續累積 |
| 訂單寫入 + 讀取競爭 | 產生器持續以約 20 筆/秒寫入 |

## 止血
```bash
# 找出並終止長時間執行的查詢（先確認不是關鍵作業）
mysql -u binance_user -p -e "KILL <id>"

# 完整性檢查請改用預設抽樣窗口，不要全表掃描
WINDOW=300000 tools/check-db-integrity.sh
```

## 事後
- [ ] 完整性檢查排到離峰時段（`tools/check-db-integrity.sh` 的註解已載明此取捨）
- [ ] orders 表已達 3,700 萬筆，評估索引與分區
