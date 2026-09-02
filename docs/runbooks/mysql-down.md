# Runbook — MySQL 無法連線

> **對應告警**：`MysqlDown` (`mysql_up == 0`, 1m) · critical

## 為什麼這條特別重要
**2026-07 事故 #2 的觸發點就是 MySQL 被重啟。**
`unattended-upgrades` 重啟 MySQL → 連帶 SIGTERM 應用程式 →
systemd 重啟了進程但業務執行緒沒回來 → 靜默六天。

所以這條告警亮起時，**同時要檢查下游服務有沒有真的恢復工作**。

## 立即確認
```bash
systemctl status mysql
mysqladmin -u binance_user -p status
journalctl -u mysql -n 50 --no-pager

# 是不是自動更新造成的
journalctl -u unattended-upgrades --since "6 hours ago" | tail -20

# ⚠️ 關鍵：下游有沒有跟著出問題
curl -s http://localhost:8092/api/v1/status | jq '.running, .ordersGenerated'
```

## 止血
```bash
sudo systemctl start mysql
mysqladmin -u binance_user -p status

# MySQL 回來之後，務必確認產生器也回來了
curl -s -X POST http://localhost:8092/api/v1/control/start
```

## 事後
- [ ] `unattended-upgrades` 排除 MySQL，或設定維護窗口
- [ ] 見 [engine-not-progressing](engine-not-progressing.md) 的完整事故脈絡
