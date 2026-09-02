# Runbook — Redis 無法連線

> **對應告警**：`RedisDown` (`redis_up == 0`, 1m) · critical

## 立即確認
```bash
redis-cli -h 127.0.0.1 ping              # 預期 PONG
systemctl status redis-server
ss -tlnp | grep 6379
journalctl -u redis-server -n 50 --no-pager
```

## 常見原因
| 症狀 | 原因 |
|---|---|
| journal 有 OOM killer 訊息 | 主機記憶體不足，Redis 被殺 → [redis-unbounded](redis-unbounded.md) |
| `MISCONF` 錯誤 | RDB 快照寫入失敗（磁碟滿）→ [disk-capacity](disk-capacity.md) |
| 連線被拒 | 服務未啟動，或 `bind` / `protected-mode` 設定變更 |
| exporter 掛但 Redis 正常 | 只是 `obs-redis-exporter` 容器問題 |

## 止血
```bash
sudo systemctl restart redis-server
redis-cli ping
```

## 事後
- [ ] 確認持久化設定（RDB / AOF）與可接受的資料遺失窗口
