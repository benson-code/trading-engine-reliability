# SLOs and Error Budgets

**English** | [繁體中文](slo.zh-TW.md)

> Targets were **derived from measurement on this host on 2026-09-06**, not copied
> from vendor defaults.
> Rules: [`deploy/observability/prometheus/slo.yml`](../deploy/observability/prometheus/slo.yml)
> Response procedure: [`docs/runbooks/slo-burn-rate.md`](runbooks/slo-burn-rate.md)
> Check any time: `make slo`

---

## Why this layer exists alongside the symptom alerts

`alerts.yml` and `slo.yml` answer two different questions:

| | The question it answers | Time scale | What you do about it |
|---|---|---|---|
| `alerts.yml` | **Is something broken right now?** | seconds to minutes | Go fix it |
| `slo.yml` | **How much room to fail do we have left?** | days to 30 days | Decide whether to keep taking risks |

A symptom alert cannot tell you whether this month has been bad enough to stop
shipping features. An error budget can — and it turns that decision from an
argument into a lookup.

---

## Three indicators, and why these three

Each one corresponds to a failure mode that **actually happened on this machine**.

### 1. `payment_api_availability` — can users reach it?

```promql
probe_success{instance="http://127.0.0.1:8091/api/v1/health"}
```

Black-box probing, measured from outside the service. **Target: 99.9%.**

### 2. `payment_api_latency` — can they reach it *and* is it fast?

```promql
probe_duration_seconds{instance="..."} < bool 0.25
```

**Target: 99.5%**, threshold 250 ms.

The threshold is **deliberately not tuned to current performance.** Measured
distribution over 3 days:

| Statistic | Host instance (:8091) | Container instance (:8094) |
|---|---|---|
| mean | 1.0 ms | 1.4 ms |
| p95 | 1.8 ms | 2.4 ms |
| p99 | 2.6 ms | 3.4 ms |
| p99.9 | 4.0 ms | 5.3 ms |
| max | 6.4 ms | 121.6 ms |

250 ms is nearly two orders of magnitude slower than p99.9. Tuning it to
"current performance plus headroom" would put it near 10 ms — which is
sensitive to noise and blind to the failure it exists to catch.

**A threshold belongs where the user starts to hurt, not where the service
currently happens to pass.** 250 ms is also where a GC pause becomes visible:
during incident #1, individual Full GC pauses were measured in *seconds*.

### 3. `engine_work_progress` — it answers, but is it doing anything?

```promql
rate(engine_orders_generated_total[5m]) > bool 0
```

**Target: 99%.** This indicator is the reason this repository exists.

It deliberately **omits** `and engine_up == 1`. When the process is dead, work
is not progressing either — that is still a work-progress failure. Treating
"the process is alive" as an excuse is exactly the reasoning error that let
incident #2 run silently for six days.

---

## How the targets were derived

Measurement window: 2026-09-03 to 09-06. Prometheus currently holds roughly
3.5 days of history — see *Known limitations* below.

| SLO | Measured (3d) | Target set | Reasoning |
|---|---|---|---|
| `payment_api_availability` | **100.0000%** | 99.9% | One order of magnitude of headroom. A single-node self-hosted box has no business claiming 99.99%. |
| `payment_api_latency` | **100.0000%** | 99.5% | Zero violations at the 250 ms threshold. The loose target is an admission that this is a dev environment. |
| `engine_work_progress` | **97.3611%** | 99% | ⚠ Already over budget — see below |

**The work-progress target is set deliberately above the measured value.**
Setting it to 97% would make the report go green, but that is just rewriting a
measured problem as an accepted status quo.

```
$ make slo

 SLO                          Measured    Target   Budget left  Status
 payment_api_availability    100.0000%   99.900%       +100.0%   OK
 payment_api_latency         100.0000%   99.500%       +100.0%   OK
 engine_work_progress         97.3611%   99.000%       -163.9%   OVER
```

---

## Case study: 2026-09-03 — why the third indicator is not optional

Within the 3-day window there is **one** work-progress outage, lasting 115 minutes:

```
09-03 05:46 → 07:41 UTC
```

Here is what every other signal reported during that window:

| Metric | Value during the outage | What it means |
|---|---|---|
| `probe_success` | `1` throughout | Every probe succeeded |
| `probe_duration_seconds` | ~1 ms | Response times were normal |
| `engine_up` | `1` throughout | The REST API was reachable |
| `jvm_uptime_seconds` | 170269 → 179269, **monotonically increasing** | **The process never restarted** |
| `engine_running` | `0` throughout, back to `1` at 08:00 | The order generator was stopped |
| `engine_orders_generated_total` | no increase at all | **Zero output** |

In other words:

> **The availability SLI read 100%. The latency SLI read 100%.**
> **Only the work-progress SLI could see those 115 minutes.**

This is not a hypothetical. It is data measured on this machine three days
before this document was written, and it is the same shape as incident #2 from
July 2026 — process alive, health checks green, business output zero — which
went unnoticed for six days.

**An SLO suite built only from availability and latency would have produced a
perfect report.**

---

## Error budget policy

```
Budget remaining  → keep shipping, keep taking calculated risks
Budget exhausted  → freeze non-essential change; reliability work goes first
Budget overspent  → stop shipping features until the 30-day window rolls clear
```

As things stand, `engine_work_progress` is overspent. Under this policy, the
correct next action is **to fix that work stoppage, not to add features.**

---

## Burn-rate alerting (multi-window, multi-burn-rate)

Burn rate = current error rate ÷ the error rate the budget allows.
`1x` means you finish the 30-day budget exactly on day 30.

| Alert | Long window | Short window | Burn rate | Severity | Budget gone in |
|---|---|---|---|---|---|
| `SLOFastBurn*` | 1h | 5m | 14.4x | critical | ~2 days |
| `SLOSlowBurn*` | 6h | 30m | 6x | warning | ~5 days |

Two per SLO, six in total, all pointing at the same runbook — the response
procedure is identical, only the symptom runbook you escalate into differs.

**Why both a long and a short window must be true at once:** the long window
provides sensitivity (something really is burning), the short window provides
reset (it clears itself once the burn stops). Without the short window, a brief
spike leaves the alert lit for hours after recovery — and then people start
ignoring it. Alert fatigue is a design failure, not an on-call failure.

---

## Known limitations

Being honest about these matters more than looking good. All three affect how
the numbers above should be read:

1. **Recording rules are not retroactive.** `slo.yml` was added on 2026-09-06,
   so `slo:*:ratio_rate3d` and `ratio_rate30d` only begin accumulating from
   that moment and read optimistically (100%) until they fill. Every number in
   this document, and everything `make slo` prints, is recomputed from **raw
   metrics** via subquery and is not affected. The two will not agree until
   after 2026-10-06.

2. **Only ~3.5 days of history exists.** Prometheus retention is configured for
   30 days (`--storage.tsdb.retention.time=30d`), but the monitoring stack was
   restarted recently, so data only goes back to around 09-03. The 30-day
   windows are therefore meaningless today, and this document states everything
   over a 3-day window instead.

3. **This is a single-node, self-hosted dev environment, not production.** No
   multi-AZ, no real user traffic, and the load comes from a local order
   generator. What the SLOs here demonstrate is the *method* — how to define an
   SLI, how to derive a threshold, how to design burn-rate alerting — not that
   this service has production-grade reliability.

4. **The work-progress history restarts on 2026-09-06.** The 115-minute gap on
   09-03 was measured from the textfile source (`job="node"`). The engine now
   serves the same counter itself (`job="trading-engine"`), and the SLI is pinned
   to that source so a second source can never double the budget. `make slo`
   therefore reads from 09-06 onward; the `job="node"` series stays queryable
   in Prometheus for the 30-day retention if the 09-03 figure needs reproducing.

---

## Related

- [`slo.yml`](../deploy/observability/prometheus/slo.yml) — the rules themselves
- [`runbooks/slo-burn-rate.md`](runbooks/slo-burn-rate.md) — what to do when budget is burning
- [`runbooks/engine-not-progressing.md`](runbooks/engine-not-progressing.md) — the symptom runbook for zero work progress
- [`incident-2026-07-14-gc-death-spiral/`](incident-2026-07-14-gc-death-spiral/) — full root-cause analysis of both incidents
