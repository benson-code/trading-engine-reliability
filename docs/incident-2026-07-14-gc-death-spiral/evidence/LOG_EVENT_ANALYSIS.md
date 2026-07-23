# Log / Event Analysis — Trading Engine GC Death Spiral

| Field | Value |
|-------|-------|
| **Log source** | `journalctl -u binance-trading-engine.service` |
| **Full export (live)** | `snapshot-2026-07-21T0938Z/service_journal_full.txt` (17,784 lines) |
| **Baseline export** | `service_journal_full.txt` (Jul 14 pack) |
| **OOME extract** | `snapshot-2026-07-21T0938Z/oome_lines.txt` |
| **Key hits** | `snapshot-2026-07-21T0938Z/journal_key_hits.txt` (439 lines) |
| **Correlated metrics** | `jstat_*`, `/proc`, MySQL `orders` |
| **Analysis time** | 2026-07-21T09:38Z UTC |
| **PID** | 26810 (preserved) |

---

## 1. Log pipeline & gaps (important)

### What we have

| Channel | Present? | Notes |
|---------|----------|-------|
| systemd journal (stdout/stderr) | **Yes** | `StandardOutput=journal` |
| Application println banners | Yes | startup, WS connect, DB reconnect |
| JVM OOME messages | Yes | UncaughtExceptionHandler + later explicit OOME |
| JVM automatic heap/thread dump fragments | Yes | After attach/OOME storms (Jul 9, Jul 13–15) |
| GC product log (`-Xlog:gc*`) | **No** | Not configured in unit file |
| Structured app metrics | **No** | No orderbook_size / heap gauge |

### Detection implication

Without GC logs or heap metrics, the only early signals in journal are:

1. Occasional `[DB] Reconnected to MySQL` (not conclusive)  
2. Then sudden **OOME** lines  

**GC death spiral was largely silent until OOME.** Continuous thrash after OOME appears as repeated heap snapshots / thread dumps when tools attach, not as clean “ERROR” lines every minute.

---

## 2. Event severity taxonomy

| Level | Pattern in journal | Meaning |
|-------|--------------------|---------|
| **INFO** | `Started ...`, banners, `[WS] Client connected` | Normal lifecycle |
| **WARN-ish** | `[DB] Reconnected to MySQL` | Connection recovered; may precede stress |
| **CRITICAL** | `OutOfMemoryError` / `Java heap space` | Heap exhaustion |
| **CRITICAL** | OOME on `SELL-THREAD` / `HTTP-Dispatcher` | Business threads dead |
| **FORENSIC** | `garbage-first heap total ... used ...` with used≈total | Live set full |
| **FORENSIC** | `GC Thread#0/1` cpu hundreds of millions of ms | GC owns CPU |
| **FORENSIC** | `AttachListener::init` + OOME | Diagnostics also starved |

---

## 3. Master timeline (UTC)

All timestamps from journal unless noted.

### Phase A — Healthy start

| Time | Event | Log excerpt / evidence | Analysis |
|------|-------|------------------------|----------|
| **2026-07-01 15:46:07** | Service start | `Started binance-trading-engine.service` | systemd brings up PID 26810 |
| **15:46:08** | DB OK | `[DB] Connected to MySQL: binance_test_db` | JDBC up |
| **15:46:08** | WS OK | `[WS] WebSocket server started on port 8093` | :8093 listening |
| **15:46:08** | Engine idle | `Engine : STOPPED — press RUN in the UI to start` | Generator not yet running |

**Note:** DB table later shows earliest order at **2026-07-01 08:27** (before this service start). That implies **prior process/run** wrote historical rows; this PID’s generation continues into the same table. For capacity RCA, use **max timestamp + count**, not only this boot.

### Phase B — Normal operation / light activity

| Time | Event | Analysis |
|------|-------|----------|
| 07-02 10:58 | WS client connect/disconnect | UI probe; engine may have been started via API earlier or around here |
| 07-03 08:39–08:40 | Multiple WS clients | Demo traffic |
| **07-08 19:43:29** | `[DB] Reconnected to MySQL` | Possible idle timeout / connection churn under load |
| **07-08 22:53:11** | `[DB] Reconnected to MySQL` again | **~4.5 min before first OOME** — early stress signal |

**DB correlation:** last insert `2026-07-08 22:56:24` — generation still writing until seconds before OOME.

### Phase C — First OOME cascade (business kill)

| # | Time | Thread | Raw pattern | Interpretation |
|---|------|--------|-------------|----------------|
| C1 | **22:57:49.981** | `idle-timeout-task` | `Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler` | First victim: HTTP idle-timeout scheduler cannot allocate |
| C2 | **22:58:49.603** | `mysql-cj-abandoned-connection-cleanup` | same UncaughtExceptionHandler form | MySQL connector cleanup thread OOME |
| C3 | **22:59:23.744** | **`SELL-THREAD`** | same | **Order generator half dies** — engine production path broken |
| C4 | **23:00:14.971** | `HTTP-Dispatcher` | same | REST request path dies |

**Characteristics of this wave:**

- Format is UncaughtExceptionHandler summary (not full stack in journal for C1–C4).  
- Spacing ~1 minute — multiple subsystems independently fail to allocate.  
- **No process exit** after these OOMes.  
- After C3, new order production effectively ends → matches DB `max_ts = 22:56:24` (last successful insert slightly before cascade; in-flight may have failed).

### Phase D — Diagnostic attempts amplify journal noise (Jul 9)

| Time | Event | Analysis |
|------|-------|----------|
| **07-09 17:50:02** | `Exception in VM (AttachListener::init)` + `java.lang.OutOfMemoryError: Java heap space` | Someone/`jcmd`/`jstack` tried attach; VM cannot allocate for AttachListener |
| **17:51:05** | Same OOME again | Second attach attempt fails |
| **17:51:14 → ~17:58** | Repeated **thread dump + heap summary** blocks | JVM dumps state while thrashing; journal floods with RUNNABLE threads |

**Recurring heap fingerprint (identical across dumps):**

```text
garbage-first heap   total 3053568K, used 3051505K
region size 2048K, 0 young (0K), 0 survivors (0K)
```

| Field | Value | Meaning |
|-------|-------|---------|
| used/total | 3051505 / 3053568 ≈ **99.93%** | Heap full |
| young / survivors | **0** | No room for young gen allocation |
| Metaspace | ~13 MB used | **Not** Metaspace OOME |

**Recurring thread fingerprint:**

```text
"GC Thread#0" ... cpu=68_xxx_xxx.ms ... runnable
"GC Thread#1" ... cpu=68_xxx_xxx.ms ... runnable
"BUY-THREAD"  ... waiting on condition
  at TradingEngine.lambda$start$0(TradingEngine.java:73)  // buySemaphore.acquire()
```

- GC threads dominate CPU counters.  
- BUY-THREAD parked on semaphore (SELL half already dead → alternation stuck).  
- `acquireOnOOME` frames appear — lock machinery under OOME pressure.

### Phase E — Multi-day thrash (still logging OOME / dumps)

| Time | Event | Notes |
|------|-------|-------|
| **07-13 15:31:08** | `Java heap space` | Still thrashing 5 days after first OOME |
| **07-13 15:32:19 / 15:32:28** | more OOME | Clustered |
| **07-14 15:02:02 / 15:02:20 / 15:02:29** | more OOME | During Jul-14 forensic capture |
| **07-15 09:31:41** | more OOME | Latest explicit OOME in journal |
| **07-15 09:32–09:35** | Last large heap/thread dump block | GC Thread#0 cpu ≈ **552,571,071 ms ≈ 153.5 hours** |
| **07-15 09:35:01** | Last journal heap line | used still **3051505K / 3053568K** |

**After 07-15 09:35:** journal is quiet (no new app logs), but **process still runs** — verified 2026-07-21 via `jstat`/`ps`. Silence ≠ recovery; thrash continues without new journal spam unless attach/OOME triggers dumps.

### Phase F — Live re-verify (metrics, not journal)

| Time | Source | Observation |
|------|--------|-------------|
| **2026-07-21 09:38Z** | `jstat -gcutil` | O=100%, FGC≈251638, FGCT≈1.076e6 s |
| same | `/proc` thread CPU | GC#0/#1 still top |
| same | `curl :8092` 3s | timeout 0 bytes |
| same | `systemctl` | active (running) since Jul 1 15:46 |

---

## 4. Complete OOME inventory (journal)

```
2026-07-08T22:57:49  idle-timeout-task                         UncaughtExceptionHandler
2026-07-08T22:58:49  mysql-cj-abandoned-connection-cleanup     UncaughtExceptionHandler
2026-07-08T22:59:23  SELL-THREAD                               UncaughtExceptionHandler
2026-07-08T23:00:14  HTTP-Dispatcher                           UncaughtExceptionHandler
2026-07-09T17:50:02  (AttachListener)                          Java heap space
2026-07-09T17:51:05  (AttachListener)                          Java heap space
2026-07-13T15:31:08  (unspecified context)                     Java heap space
2026-07-13T15:32:19  (unspecified context)                     Java heap space
2026-07-13T15:32:28  (unspecified context)                     Java heap space
2026-07-14T15:02:02  (forensic tools window)                   Java heap space
2026-07-14T15:02:20  (forensic tools window)                   Java heap space
2026-07-14T15:02:29  (forensic tools window)                   Java heap space
2026-07-15T09:31:41  (unspecified context)                     Java heap space
```

**Count:** 13 journal lines containing OOME / Java heap space (some pairs are “Exception in VM” + message).

---

## 5. Correlated non-journal events

### 5.1 MySQL `orders` table

| Metric | Value | Log linkage |
|--------|-------|-------------|
| COUNT | 11,358,422 | Scale of retained live objects |
| MAX(timestamp) | 2026-07-08 22:56:24 | **~85s before** first OOME |
| AVG rate | 17.29/s | Matches generator design order-of-magnitude |
| BUY≈SELL | 5.68M each | Semaphore alternation |

**Event interpretation:** persistence channel healthy until memory cliff; then inserts stop while JVM continues thrashing.

### 5.2 jstat series as “virtual GC log”

Because `-Xlog:gc*` was never enabled, **jstat is the GC log substitute**.

| Epoch | FGC | FGCT (s) | Wall FGCT/day equiv | Story |
|-------|-----|----------|---------------------|-------|
| Jul 14 15:04 | 114,909 | 491,352 | ~5.69 d cumulative | Already deep thrash |
| Jul 21 09:38 | 251,638 | 1,076,477 | ~12.46 d cumulative | Continuous thrash |
| Δ ~6.8 d wall | +136,729 | +585,125 s (~6.77 d) | **~100% GC overhead** | Death spiral active |

**Live GCC:** `G1 Compaction Pause` (current), LGCC `G1 Evacuation Pause`.

### 5.3 API probe

```text
curl -m 3 http://127.0.0.1:8092/api/v1/status
→ Operation timed out after 3002 ms with 0 bytes received
```

Event class: **availability failure** with process still “healthy” to systemd.

---

## 6. Log pattern cookbook (how to read this class of incident)

### Pattern 1 — Silent fill then OOME cascade

```
[DB] Reconnected...          ← weak early signal
(no ERROR for hours/days)
OutOfMemoryError on timer
OutOfMemoryError on DB cleanup
OutOfMemoryError on WORKER THREAD   ← business impact
OutOfMemoryError on HTTP            ← API impact
```

### Pattern 2 — Heap dump fingerprint of death spiral

```
garbage-first heap total X, used ≈ X
0 young, 0 survivors
GC Thread#0/#1 cpu >> application threads
```

### Pattern 3 — Attach failure as evidence

```
Exception in VM (AttachListener::init)
java.lang.OutOfMemoryError: Java heap space
```

or client-side:

```
AttachNotSupportedException: ... doesn't respond within 10500ms
```

→ Prefer **`jstat`** (hsperfdata) over `jstack`/`jmap` when this appears.

### Pattern 4 — Zombie thrash (systemd green, world red)

```
systemctl: active (running)
journal: no new INFO, occasional OOME/dump
jstat: O=100%, FGCT rising ≈ wall clock
curl: timeout
```

---

## 7. Event → root-cause mapping

| Log event | Points to | Not explained by |
|-----------|-----------|------------------|
| OOME cascade Jul 8 22:57–23:00 | Heap exhaustion after multi-day growth | Transient GC pause only |
| Heap used≈total, 0 young | Live set full | Temporary allocation spike |
| GC thread CPU dominance | Death spiral thrash | Trading compute load |
| SELL-THREAD OOME + BUY on semaphore | Generator stopped, book still full | Clean engine.stop() |
| DB max_ts ≈ first OOME | In-memory + DB filled together | Pure DB outage |
| No ExitOnOOME / no process death | Long thrash window | systemd crash-loop |
| OrderBook code (static analysis) | Unbounded strong refs | OrderCache LRU |

---

## 8. Chronological storyboard (narrative)

1. **Jul 1 15:46** — Service starts clean; engine STOPPED.  
2. **Engine started via UI/API** (not explicitly logged beyond later generation side-effects) — BUY/SELL fill `OrderBook` + MySQL at ~17/s.  
3. **Jul 1–8** — Live set climbs toward ~3 GB default heap. Journal mostly quiet (WS/DB noise only).  
4. **Jul 8 22:53** — DB reconnect under stress.  
5. **Jul 8 22:56** — Last successful order insert.  
6. **Jul 8 22:57–23:00** — OOME cascade kills timers, DB cleanup, **SELL-THREAD**, HTTP.  
7. **Jul 9+** — Heap remains full; GC thrash; attach attempts produce more OOME + dump spam.  
8. **Jul 13–15** — Additional OOME/dump waves; GC cumulative CPU > 150 hours per GC thread.  
9. **Jul 14 / Jul 21 forensics** — jstat proves FGC/FGCT still exploding; API still dead; PID still alive.

---

## 9. Gaps & residual uncertainty

| Gap | Impact | Mitigation used |
|-----|--------|-----------------|
| Exact `POST /engine/start` time not in journal | Start of generation window imprecise | Use DB min/max timestamps |
| No GC product log | Cannot chart pause histogram pre-OOME | jstat counters + post-OOME dumps |
| Histogram from prior SA session not re-run Jul 21 | Avoid force dump on scene | Prior hist + DB + code still consistent |
| Quiet journal after Jul 15 | Might look “recovered” | Live jstat disproves recovery |

---

## 10. Recommended log improvements (post-fix)

1. **Enable GC logging** (`-Xlog:gc*`) with rotation.  
2. **Structured app logs:** `orderbook_size`, `unique_orders`, `db_queue_depth` every 30–60s.  
3. **Alert:** Old gen > 85% or FGC rate rising; process RSS plateau + CPU high.  
4. **`ExitOnOutOfMemoryError`** so OOME becomes a clean crash event, not silent thrash.  
5. **HeapDumpOnOutOfMemoryError** for one-shot MAT analysis.  
6. Log **engine start/stop** with rate and orderbook size at transition.

---

## 11. Evidence file map for this analysis

| File | Role |
|------|------|
| `snapshot-2026-07-21T0938Z/service_journal_full.txt` | Full journal |
| `snapshot-2026-07-21T0938Z/oome_lines.txt` | OOME only |
| `snapshot-2026-07-21T0938Z/journal_key_hits.txt` | Filtered key lines |
| `snapshot-2026-07-21T0938Z/jstat_gcutil_5s.txt` | Live GC util |
| `snapshot-2026-07-21T0938Z/jstat_gccause_3s.txt` | GC cause |
| `snapshot-2026-07-21T0938Z/thread_cpu_proc.txt` | Who burns CPU |
| `snapshot-2026-07-21T0938Z/db_order_stats.txt` | MySQL correlation |
| `snapshot-2026-07-21T0938Z/api_status.txt` | Availability probe |
| `timeline.txt` / `gc_journal_hits.txt` | Jul-14 extracts |
| `RCA_REPORT.md` | Full RCA |

---

## 12. Analyst conclusion

**Logs show a textbook progression:** quiet growth → DB reconnect stress → OOME cascade killing business threads → multi-day GC thrash with heap snapshots frozen at 99.93% full → systemd still green.  

**Combined with code (unbounded OrderBook) and metrics (jstat + MySQL), the journal is sufficient to close the RCA without restarting the process.**

*Log analysis complete. Crime scene remains preserved.*
