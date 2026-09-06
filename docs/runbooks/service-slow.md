# Runbook — Service responding slowly

**English** | [繁體中文](service-slow.zh-TW.md)

> **Alert**: `ServiceSlowResponse` (`probe_duration_seconds > 1`, 5m) · warning

## Why this matters
**This usually fires before `ServiceDown` does.** When GC pressure rises, when a
thread pool starts queueing, or when slow queries pile up, a service gets slow
before it becomes unavailable. That gap is your window to intervene.

## First three minutes
```bash
# 1. How slow, and since when
curl -s 'http://127.0.0.1:9090/api/v1/query?query=probe_duration_seconds' | jq -r '.data.result[]|"\(.metric.instance) \(.value[1])s"'

# 2. Rule out the three usual sources, in order
uptime                                   # (1) host CPU saturated?
jstat -gcutil $(pgrep -f trading-engine|head -1) 1000 3   # (2) GC pressure?
mysql -u binance_user -p -e "SHOW FULL PROCESSLIST" | head -20   # (3) slow queries?
```

## Triage
- GC time ratio climbing → [gc-death-spiral](gc-death-spiral.md)
- Host CPU / load high → [host-saturation](host-saturation.md)
- MySQL connections or slow queries high → [mysql-saturation](mysql-saturation.md)
- None of the above → check for thread-pool exhaustion (`jstack`, look for BLOCKED / WAITING)

## Follow-up
- [ ] Record the baseline response time, so the threshold rests on evidence rather than on a guess
