# Runbook — 主機資源飽和

> **對應告警**：`HostCpuSaturated` (>85%, 10m) · `HostLoadHigh` (>1.5/core, 10m) · `HostMemoryLow` (<15%, 10m) · warning

## 主機規格基準
`orion-dev`：Oracle Cloud aarch64、**2 vCPU / 11 GiB / swap = 0**。

**swap 為 0 很重要**：記憶體壓力沒有任何緩衝，用完就是硬碰硬。

## 立即確認
```bash
uptime                                  # load average 除以 2 = 每核心負載
top -b -n 1 | head -15                  # 誰在吃 CPU
ps aux --sort=-%mem | head -8           # 誰在吃記憶體
free -h
```

## 分流
| 觀察 | 判斷 |
|---|---|
| load ≈ 核心數、單一 java 進程吃滿 | 高機率是 GC → [gc-death-spiral](gc-death-spiral.md) |
| load 高但 CPU 不高 | I/O 等待 → `iostat -x 2 3`、看磁碟 |
| 記憶體被 buff/cache 佔用 | 通常正常，看 `MemAvailable` 而非 `free` |
| 多個容器一起吃 | `docker stats --no-stream` 找出來源 |

## 止血
```bash
docker stats --no-stream                 # 先確認是不是自己的監控堆疊在吃
# 非必要的容器可暫停：docker compose stop <service>
```

## 事後
- [ ] 這台主機同時跑著訂單產生器、兩支 JVM、監控堆疊與前端 dev server。
      容量規劃上應評估拆分到第二台實例。
