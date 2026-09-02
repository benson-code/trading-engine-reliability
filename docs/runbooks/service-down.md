# Runbook — 服務外部探測失敗

> **對應告警**：`ServiceDown` (`probe_success == 0`, 1m) · critical

## 影響
黑箱探測是**從使用者角度**的量測。這條亮代表使用者現在打不到服務——
優先於任何服務自報的健康指標。

## 立即確認
```bash
# 1. 探測目標是哪一個
curl -s 'http://127.0.0.1:9090/api/v1/query?query=probe_success==0' | jq -r '.data.result[].metric.instance'

# 2. 手動重現
curl -v -m 5 <目標 URL>

# 3. 進程還在嗎 / port 還開著嗎
pgrep -af java ; ss -tlnp | grep <port>

# 4. 是不是「進程活著但沒回應」（GC 飽和的典型症狀）
uptime ; top -b -n 1 | head -12
```

## 分流
| 觀察 | 走哪份 runbook |
|---|---|
| 進程不存在 | 直接重啟；檢查 journal 找退出原因 |
| 進程在、CPU 滿載 | [gc-death-spiral](gc-death-spiral.md) |
| 進程在、CPU 閒置、port 沒開 | 應用啟動失敗，看 journal |
| 進程在、port 開、但不回應 | [gc-death-spiral](gc-death-spiral.md) 或執行緒池耗盡 |
| 主機整台失聯 | [host-down](host-down.md) |

## 止血
```bash
journalctl -u <service> -n 100 --no-pager   # 先看，再動
sudo systemctl restart <service>            # CPU 飽和時請先保留現場
```

## 事後
- [ ] 這次故障有沒有更早的前兆告警？沒有的話補一條
- [ ] 探測間隔（15s）與 `for: 1m` 是否合適
