# Runbook — 服務回應變慢

> **對應告警**：`ServiceSlowResponse` (`probe_duration_seconds > 1`, 5m) · warning

## 為什麼重要
**這條通常比 `ServiceDown` 先亮。** GC 壓力上升、執行緒池排隊、
DB 慢查詢累積時，服務會先變慢才變不可用。這是介入的窗口。

## 立即確認
```bash
# 1. 慢了多少、從什麼時候開始
curl -s 'http://127.0.0.1:9090/api/v1/query?query=probe_duration_seconds' | jq -r '.data.result[]|"\(.metric.instance) \(.value[1])s"'

# 2. 三個常見來源，逐一排除
uptime                                   # ① 主機 CPU 飽和？
jstat -gcutil $(pgrep -f trading-engine|head -1) 1000 3   # ② GC 壓力？
mysql -u binance_user -p -e "SHOW FULL PROCESSLIST" | head -20   # ③ DB 慢查詢？
```

## 分流
- GC 佔比上升 → [gc-death-spiral](gc-death-spiral.md)
- 主機 CPU / load 高 → [host-saturation](host-saturation.md)
- MySQL 連線或慢查詢多 → [mysql-saturation](mysql-saturation.md)
- 以上皆非 → 看應用執行緒池是否耗盡（`jstack` 找 BLOCKED / WAITING）

## 事後
- [ ] 記錄基準回應時間，讓閾值有依據而不是拍腦袋
