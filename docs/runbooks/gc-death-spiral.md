# Runbook — JVM GC death spiral

**English** | [繁體中文](gc-death-spiral.zh-TW.md)

> **Alerts**: `JvmGcTimeRatioHigh` · `JvmFullGcRateHigh` · `JvmOldGenHigh` · `JvmHeapGrowthUnbounded`
> **Severity**: critical
> **Origin**: incident #1, 2026-07-14 (full RCA in [`docs/incident-2026-07-14-gc-death-spiral/`](../incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md))

---

## Triggers, and where the thresholds came from

| Alert | Expression | Measured during the incident |
|---|---|---|
| `JvmOldGenHigh` | `jvm_oldgen_utilization_ratio > 0.85` | **0.9999** |
| `JvmGcTimeRatioHigh` | `jvm_gc_full_seconds_total / jvm_uptime_seconds > 0.10` | **0.438** (491,218s / 1,120,514s) |
| `JvmFullGcRateHigh` | `rate(jvm_gc_full_count[5m]) > 0.1` | **114,879 Full GCs** accumulated |
| `JvmHeapGrowthUnbounded` | `predict_linear(jvm_oldgen_used_bytes[6h], 24h) > capacity` | Would have warned 6 days early |

**Every threshold is reverse-engineered from values measured during the
incident**, not copied from a vendor default. The 10% threshold means: one
second in every ten is a stop-the-world pause. At that point latency has
degraded visibly, but the system is still recoverable.

> The GC time ratio was cross-checked two independent ways: JVM counters give
> 43.8%, and `/proc` CPU accounting for the GC threads gives 43.4%.

---

## Impact

The defining characteristic of a death spiral is that **the process does not die**:

- Objects are still strongly reachable from a collection → the GC correctly decides they are not garbage
- Each Full GC reclaims almost nothing → the heap is still full when it finishes → it immediately runs again
- GC threads consume the CPU; business threads are starved
- The HTTP API stops responding entirely
- **But systemd still reports `active (running)`, and every liveness check passes**

`OutOfMemoryError` does appear in the log, but **it does not terminate the process.**

---

## First three minutes

```bash
PID=$(pgrep -f trading-engine-simulator | head -1)

# 1. GC time ratio — the single most important number
jstat -gc $PID | awk 'NR==2 {printf "Full GCs=%s  cumulative STW=%.0fs\n", $13, $16}'

# 2. Old generation utilisation
jstat -gcutil $PID 1000 5     # O column near 100 and not falling = confirmed

# 3. Process uptime (needed to compute the ratio)
awk '{print $22/100 " seconds"}' /proc/$PID/stat

# 4. Are the GC threads eating the CPU
top -H -p $PID -b -n 1 | head -20   # look for GC task threads

# ⚠️ If jstat / jcmd / jstack time out or hang:
#    that is not "no data", that is a confirming diagnosis — see jstat-attach-failed.md
```

---

## Stop the bleeding

**Preserve the scene before restarting.** A restart destroys the root cause
permanently.

```bash
# 1. Preserve the scene (~30 seconds, see PRESERVE_SCENE.md)
tools/preserve-scene.sh

# 2. Bound the blast radius from the database, since the JVM can no longer be asked
mysql --defaults-extra-file=<cred> binance_test_db -e "
  SELECT COUNT(*), MIN(created_at), MAX(created_at) FROM orders;"

# 3. Only restart once the blast radius is established
sudo systemctl restart binance-trading-engine
```

**A restart is bleeding control, not a fix** — the leak starts accumulating
again, and at the rate measured during the incident it will recur in roughly
7.6 days.

---

## Root-cause investigation

A death spiral is almost always an **unbounded collection**: a collection field
on a long-lived object that only ever grows, with no capacity ceiling and no
eviction.

```bash
# 1. Find the classes holding the most
jcmd $PID GC.class_histogram | head -20
# (if attach hangs, read the preserved java_class_histogram.txt instead)

# 2. Scan the source with the CI gates
tools/check-bounded-collections.sh      # Java
tools/check-bounded-collections-ts.sh   # TypeScript
```

**Known root cause (incident #1)**: `OrderBook` held three unbounded
collections, so every `Order` object was retained forever. At roughly 17 orders
per second over 7.6 days it accumulated **11,358,422 orders** and exhausted the
2.91 GiB heap.

---

## Follow-up

- [x] Fixed: all three collections bounded (retained set reduced from O(t) to O(1))
- [x] Added: an endurance test (retained 1,000 → 100,000, post-GC heap 29MB → 70MB) — **validated by reverting the fix to confirm the test really fails**
- [x] Added: the `check-bounded-collections.sh` CI gate, which blocks PRs
- [x] Added: this alert group, with thresholds derived from the measured incident
- [ ] Todo: add `-XX:+HeapDumpOnOutOfMemoryError` to the JVM flags

**The core lesson:**
> "The process is still alive" is the weakest reliability signal there is.
> In this incident it was not merely unhelpful — it actively concealed the
> failure for six days.
