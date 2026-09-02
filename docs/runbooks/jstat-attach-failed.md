# Runbook — jstat 無法 attach 到 JVM

> **對應告警**：`JstatAttachFailed` (`jvm_jstat_attach_success == 0`, 2m) · critical

## 這條為什麼存在

**診斷工具失效本身是一項證據，不是「沒有資料」。**

2026-07-14 事故當下 `jcmd` 與 `jstack` 同時逾時 —— 因為 JVM 的 attach
機制需要目標 JVM 執行一個 handshake 執行緒，而該執行緒也被 GC 餓死了。
當時如果只把它當成「採集失敗」忽略，就會錯過最強的一項確診訊號。

## 立即確認
```bash
PID=$(pgrep -f trading-engine-simulator | head -1)
echo "PID=$PID"

# attach 類工具（可能全部逾時 —— 逾時即為證據）
timeout 10 jstat -gcutil $PID ; echo "jstat exit=$?"
timeout 10 jcmd  $PID VM.uptime ; echo "jcmd exit=$?"

# 不需要 attach 的來源（這些一定拿得到）
cat /proc/$PID/status | grep -E "VmRSS|Threads"
cat /proc/$PID/smaps_rollup | grep -E "^Rss|^Pss"
ps -o pid,pcpu,pmem,etimes -p $PID
top -H -p $PID -b -n 1 | head -20
```

## 關鍵原則
attach 失效時，改用**不需要 attach 的觀測面**：

| 想知道 | 改用 |
|---|---|
| JVM 記憶體 | `/proc/$PID/smaps_rollup`、`/proc/$PID/status` |
| CPU 是誰吃掉的 | `top -H`、`/proc/$PID/task/*/stat` |
| 業務影響範圍 | **直接查 DB**（事故當時就是這樣界定的）|
| 事件時間軸 | `journalctl -u <service>` |

## 事後
- [ ] JVM 啟動參數加上 `-Xlog:gc*:file=...`，讓 GC 日誌不依賴 attach
- [ ] 評估開啟 JMX（本次因不願重啟以保留對照組而未啟用）
