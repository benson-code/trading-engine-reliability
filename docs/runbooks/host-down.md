# Runbook — 主機失聯

> **對應告警**：`HostDown` (`up{job="node"} == 0`, 2m) · critical

## 特性
此告警在 Alertmanager 設有**抑制規則**：主機掛掉時會壓制該主機上
所有其他告警，只留這一則。目的是避免一次故障噴出 20 則通知。

## 立即確認
```bash
ping -c 3 <host>
ssh <host> uptime                       # SSH 還通嗎
curl -s -m 3 http://<host>:9100/metrics | head -1   # 只有 exporter 掛？
```

## 分流
| 觀察 | 判斷 |
|---|---|
| ping 不通、SSH 不通 | 主機或網路層問題 → 查雲端 console |
| ping 通、SSH 通、9100 不通 | 只是 node_exporter 掛了，不是主機掛了 |
| SSH 極慢才連上 | 主機資源耗盡 → [host-saturation](host-saturation.md) |

## 止血
```bash
docker compose -f deploy/observability/docker-compose.yml up -d node-exporter
```

## 事後
- [ ] 若只是 exporter 掛掉，考慮把告警拆成 `HostDown` 與 `ExporterDown` 兩條
