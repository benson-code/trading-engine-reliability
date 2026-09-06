# Runbook — Service reachable but work is not progressing

**English** | [繁體中文](engine-not-progressing.zh-TW.md)

> **Alerts**: `EngineNotProgressing` · `EngineWorkerStopped` · `DatabaseWriteStalled`
> **Severity**: critical
> **Origin**: incident #2, 2026-07 (silent degradation, six days unnoticed)

---

## Triggers

| Alert | Expression | For |
|---|---|---|
| `EngineNotProgressing` | `engine_up == 1 and rate(engine_orders_generated_total[10m]) == 0` | 10m |
| `EngineWorkerStopped` | `engine_up == 1 and engine_running == 0` | 5m |
| `DatabaseWriteStalled` | `engine_running == 1 and rate(engine_orders_generated_total[15m]) == 0` | 15m |

**Where the threshold comes from**: the code declares a generation rate of about
20 orders/sec, and the database measured 71,913 rows/hour (≈19.98/sec). Ten
consecutive minutes of zero growth cannot be normal variation.

---

## Impact

**This is the most dangerous failure class in this system, because every
conventional health check passes:**

| Check | Result during the incident |
|---|---|
| `systemctl status` | `active (running)` ✅ |
| TCP port probe | bound ✅ |
| `GET /api/v1/status` | `200 OK` ✅ |
| K8s liveness probe | would pass ✅ |
| K8s readiness probe | would pass ✅ |
| **Actual business output** | **zero, for six days** ❌ |

The infrastructure threads (REST, WebSocket, scheduler) all came back. Only the
business threads did not.

---

## First three minutes

```bash
# 1. What the service says about itself
curl -s http://localhost:8092/api/v1/status | jq .

# 2. Is the counter moving — this is the only trustworthy signal
for i in 1 2 3; do
  curl -s http://localhost:8092/api/v1/status | jq -r '.ordersGenerated'
  sleep 10
done
# Same number three times = stall confirmed

# 3. Verify independently from the database (do not trust self-reporting)
mysql --defaults-extra-file=<cred> binance_test_db -e "
  SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:00') AS hr, COUNT(*) AS rows_written
  FROM orders WHERE created_at > NOW() - INTERVAL 8 HOUR
  GROUP BY hr ORDER BY hr;"
# Normal is roughly 71,900 rows/hour; a collapse to 0 confirms it

# 4. Was the process restarted? (find the trigger)
systemctl show binance-trading-engine -p ExecMainStartTimestamp
journalctl -u binance-trading-engine --since "24 hours ago" | grep -iE "signal|SIGTERM|143|Stopped|Started"

# 5. Was it caused by automatic updates
journalctl -u unattended-upgrades --since "24 hours ago" | tail -30
```

---

## Stop the bleeding

```bash
# Restart the generator through the API (leaves the process running, preserves the scene)
curl -s -X POST http://localhost:8092/api/v1/control/start

# Confirm recovery
sleep 30 && curl -s http://localhost:8092/api/v1/status | jq '.ordersGenerated'
```

**Only restart the service if the API does not work** — and preserve the scene first:

```bash
tools/preserve-scene.sh   # or collect manually per PRESERVE_SCENE.md
sudo systemctl restart binance-trading-engine
```

---

## Root-cause investigation

Known root cause (incident #2, July 2026):

1. `unattended-upgrades` restarted MySQL
2. That propagated `SIGTERM` to the application (the journal shows `exit code 143` = 128+15)
3. systemd's `Restart=on-failure` **restarted the process successfully**
4. But the generator's on/off state is held in an **in-memory `AtomicBoolean`**, which resets to `false` on boot
5. → process alive, port open, API returning 200 — and the business threads never started

**To confirm it is the same root cause**, check:
- whether the journal contains `exit code 143`
- whether the restart time lines up with the `unattended-upgrades` run
- whether the generator flag was `false` after the restart

---

## Follow-up

- [ ] Fixed: state now recovers automatically at boot instead of depending on an in-memory flag
- [ ] Added: this alert group — the reliability signal moved from "process alive" to "work progressing"
- [ ] Todo: add `ExecStartPost` to the systemd unit to verify the business threads actually started
- [ ] Todo: exclude MySQL from `unattended-upgrades`, or define a maintenance window

**The core lesson:**
> Automatic recovery (systemd restart) and observability work against each other.
> Auto-recovery made the failure *look* resolved, and by doing so delayed its
> discovery.
