# Runbook — External probe failing

**English** | [繁體中文](service-down.zh-TW.md)

> **Alert**: `ServiceDown` (`probe_success == 0`, 1m) · critical

## Two probes can trigger this

| Probe | What it does | What its failure means |
|---|---|---|
| `blackbox-http` | `GET /health` | The process is not answering at all |
| `blackbox-transaction` | `POST /api/v1/payments` with a real body | **The transaction path is broken, even if `/health` is fine** |

Check which one failed first — a synthetic-transaction failure with a healthy
`/health` means the process is up and the business path is not.

```bash
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=probe_success == 0' \
  | jq -r '.data.result[] | "\(.metric.job)\t\(.metric.instance)"'
```

## Impact
A black-box probe measures the service **from the user's point of view**. When
this fires, users cannot reach the service right now — and that takes priority
over any health status the service reports about itself.

## First three minutes
```bash
# 1. Which target is failing
curl -s 'http://127.0.0.1:9090/api/v1/query?query=probe_success==0' | jq -r '.data.result[].metric.instance'

# 2. Reproduce it by hand
curl -v -m 5 <target URL>

# 3. Is the process still there, is the port still open
pgrep -af java ; ss -tlnp | grep <port>

# 4. Is this "process alive but not responding"? (the classic GC-saturation shape)
uptime ; top -b -n 1 | head -12
```

## Triage
| What you see | Where to go |
|---|---|
| Process is gone | Restart it; read the journal for the exit reason |
| Process running, CPU pinned | [gc-death-spiral](gc-death-spiral.md) |
| Process running, CPU idle, port closed | Application failed to start — read the journal |
| Process running, port open, no response | [gc-death-spiral](gc-death-spiral.md), or thread-pool exhaustion |
| Whole host unreachable | [host-down](host-down.md) |

## Stop the bleeding
```bash
journalctl -u <service> -n 100 --no-pager   # look first, then act
sudo systemctl restart <service>            # if CPU is saturated, preserve the scene first
```

## Follow-up
- [ ] Was there an earlier warning signal for this failure? If not, add one
- [ ] Are the probe interval (15s) and `for: 1m` still the right choice?
