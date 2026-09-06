# Runbook — Error budget burning too fast

**English** | [繁體中文](slo-burn-rate.zh-TW.md)

> **Alerts**: `SLOFastBurnAvailability` · `SLOSlowBurnAvailability` · `SLOFastBurnLatency` · `SLOSlowBurnLatency` · `SLOFastBurnWorkProgress` · `SLOSlowBurnWorkProgress`
> **Severity**: fast burn = critical / slow burn = warning
> **Origin**: the SLO layer (see [`docs/slo.md`](../slo.md)); targets derived from measurement on 2026-09-06

---

## Triggers

Burn rate = current error rate ÷ the error rate the budget allows. `1x` means
you finish the 30-day budget exactly on day 30.

| Alert | Long window | Short window | Burn rate | For | Meaning |
|---|---|---|---|---|---|
| `SLOFastBurn*` | 1h | 5m | 14.4x | 2m | 30-day budget gone in ~2 days |
| `SLOSlowBurn*` | 6h | 30m | 6x | 15m | gone in ~5 days |

**Why both windows must be true at once**: the long window provides sensitivity
(something really is burning), the short window provides reset (it clears itself
once the burn stops). Without the short window, one brief spike leaves the alert
lit for hours after recovery — and then people start ignoring it.

| SLO | Target | Error budget | Fast-burn threshold |
|---|---|---|---|
| `payment_api_availability` | 99.9% | 0.1% | error rate > 1.44% |
| `payment_api_latency` | 99.5% | 0.5% | error rate > 7.2% |
| `engine_work_progress` | 99% | 1% | error rate > 14.4% |

---

## Impact

A burn-rate alert **is not a failure alert**. It answers a different question:

| | The question | Example |
|---|---|---|
| `alerts.yml` | Is something broken right now? | `ServiceDown`, `JvmOldGenHigh` |
| `slo.yml` | How much room to fail is left? | this runbook |

So it can fire **with no other alert lit at all** — which means some chronic
failure is steadily consuming budget while never quite crossing a symptom
threshold.

> ⚠️ **`SLOFastBurnWorkProgress` is the one most likely to be dismissed as a
> false positive.**
> On 2026-09-03 05:46–07:41 UTC, work progress on this host stopped for 115
> minutes while `probe_success = 1`, `probe_duration_seconds ≈ 1ms`, and
> `jvm_uptime_seconds` kept increasing — the process never restarted.
> **The availability and latency SLIs read 100% throughout.**
> Green health checks do not make this alert a false positive. That is precisely
> why it exists.

---

## First three minutes

```bash
# 1. Which SLO is burning, and how fast
curl -sG http://localhost:9090/api/v1/query \
  --data-urlencode 'query=slo:error_budget_remaining' | jq -r \
  '.data.result[] | "\(.metric.slo)\t\(.value[1])"'

# 2. Where all three SLIs stand (is this broad or one-sided?)
for s in payment_api_availability payment_api_latency engine_work_progress; do
  printf '%-28s ' "$s"
  curl -sG http://localhost:9090/api/v1/query \
    --data-urlencode "query=slo:${s}:ratio_rate1h" | jq -r '.data.result[0].value[1] // "no data"'
done

# 3. Is any symptom alert lit at the same time
make obs-alerts
```

**Triage:**

| What you see | Interpretation | Go to |
|---|---|---|
| Availability burning + `ServiceDown` lit | Genuinely down | [service-down](service-down.md) |
| Latency burning + `JvmGcTimeRatioHigh` lit | The shape of incident #1 | [gc-death-spiral](gc-death-spiral.md) |
| Latency burning, JVM healthy | Downstream or host saturation | [host-saturation](host-saturation.md) |
| Work progress burning, probes all green | **The shape of incident #2** | [engine-not-progressing](engine-not-progressing.md) |
| All three burning | Host-level problem | [host-down](host-down.md) |

---

## Stop the bleeding

A burn-rate alert has no bleeding-control action of its own — what needs
stopping is the underlying failure. Use the table above.

But there is one **decision** unique to this layer:

```
Budget remaining  → keep shipping, keep taking calculated risks
Budget exhausted  → freeze non-essential change; reliability work goes first
Budget overspent  → stop shipping features until the 30-day window rolls clear
```

Check where you stand:

```bash
make slo
```

This is what an SLO is actually for: **it turns "should we keep taking risks"
from an argument into a lookup.**

---

## Root-cause investigation

1. **First rule out a measurement artefact.** Recording rules are not
   retroactive — if `slo.yml` was recently added or changed, `ratio_rate3d` and
   `ratio_rate30d` only accumulate from that moment and read optimistically
   until they fill. `make slo` recomputes from raw metrics and sidesteps this.

2. **Find out where the burn is concentrated.** An average dilutes one complete
   115-minute outage into "97%". Look at the distribution, not the mean:

   ```bash
   curl -sG http://localhost:9090/api/v1/query_range \
     --data-urlencode 'query=slo:engine_work_progress:good' \
     --data-urlencode "start=$(date -u -d '-3 days' +%s)" \
     --data-urlencode "end=$(date -u +%s)" \
     --data-urlencode 'step=300' | jq -r \
     '.data.result[0].values[] | select(.[1]=="0") | .[0]' | head -20
   ```

3. **Cross-check other signals over that window**: `engine_running`,
   `jvm_uptime_seconds` (increasing = the process never restarted), and
   `journalctl -u binance-trading-engine`.

---

## Follow-up

- If the burn was **known and acceptable** (a deliberate maintenance window, for
  example), do not loosen the SLO target to silence the alert. Record the
  consumption, and add to [`docs/slo.md`](../slo.md) the reasoning for whether it
  should be excluded from the SLI at all.
- If the same shape keeps recurring, this is not a budget problem, it is a design
  problem — fix the service underneath, not the threshold.
- Targets should be recalibrated against fresh measurements over time.
  [`docs/slo.md`](../slo.md) records the provenance of every number; when you
  change a target, update that document too, not just `slo.yml`.
