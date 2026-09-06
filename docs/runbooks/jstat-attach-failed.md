# Runbook — jstat cannot attach to the JVM

**English** | [繁體中文](jstat-attach-failed.zh-TW.md)

> **Alert**: `JstatAttachFailed` (`jvm_jstat_attach_success == 0`, 2m) · critical

## Why this alert exists

**A diagnostic tool failing is itself evidence. It is not "no data".**

During the 2026-07-14 incident, `jcmd` and `jstack` both timed out. The JVM's
attach mechanism requires the target JVM to run a handshake thread — and that
thread had been starved by GC along with everything else. Had this been
dismissed as "collection failed", the strongest confirming signal of the
diagnosis would have been thrown away.

## First three minutes
```bash
PID=$(pgrep -f trading-engine-simulator | head -1)
echo "PID=$PID"

# Attach-based tools (these may all time out — the timeout IS the evidence)
timeout 10 jstat -gcutil $PID ; echo "jstat exit=$?"
timeout 10 jcmd  $PID VM.uptime ; echo "jcmd exit=$?"

# Sources that need no attach (these will always answer)
cat /proc/$PID/status | grep -E "VmRSS|Threads"
cat /proc/$PID/smaps_rollup | grep -E "^Rss|^Pss"
ps -o pid,pcpu,pmem,etimes -p $PID
top -H -p $PID -b -n 1 | head -20
```

## The governing principle
When attach fails, switch to observation surfaces that do not require it:

| What you want to know | Use instead |
|---|---|
| JVM memory | `/proc/$PID/smaps_rollup`, `/proc/$PID/status` |
| Who is consuming CPU | `top -H`, `/proc/$PID/task/*/stat` |
| Blast radius on the business | **Query the database directly** — this is exactly how it was bounded during the incident |
| Event timeline | `journalctl -u <service>` |

## Follow-up
- [ ] Add `-Xlog:gc*:file=...` to the JVM flags so GC logging does not depend on attach
- [ ] Evaluate enabling JMX (it was left off during the incident to avoid a restart, which would have destroyed the reference run)
