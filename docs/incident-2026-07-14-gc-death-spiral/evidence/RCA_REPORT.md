# RCA Report: Trading Engine GC Death Spiral

| Field | Value |
|-------|-------|
| **Incident ID** | QA-TE-2026-07-08-OOME / GC-DEATH-SPIRAL |
| **Service** | `binance-trading-engine.service` |
| **PID** | **26810** (intentionally left alive) |
| **Host** | `orion-dev` (2 vCPU / 11 GiB RAM / **swap=0**) |
| **App** | `trading-engine-simulator-1.0.0.jar` |
| **JVM** | OpenJDK 21.0.11, G1GC, **no `-Xmx`** (observed heap ≈ 3.05 GB) |
| **First impact** | 2026-07-08 22:57 UTC (first OOME) |
| **Scene re-verified** | 2026-07-21 09:38 UTC |
| **Status** | **LIVE CRIME SCENE — do not kill/restart** |
| **Evidence root** | `/home/ubuntu/qa-incident-2026-07-14/` |
| **Live snapshot** | `snapshot-2026-07-21T0938Z/` |

---

## 0. One-line conclusion

**Root cause is not “CPU hardware failure” and not business busy-loop.**  
It is **unbounded in-memory `OrderBook` growth → heap full of live objects → Full GC thrashing (death spiral) → OOME on worker threads while the JVM process remains alive and burns ~100% CPU on GC.**

---

## 1. Crime-scene preservation (what we did / did not do)

### Preserved (2026-07-21 re-capture)

| Action | Result |
|--------|--------|
| `ps` / `systemctl status` | PID 26810 still `active (running)`, elapsed **19d+**, ~**125% CPU**, RSS ~**3.07 GB** |
| `jstat -gcutil/-gc/-gccause` | Old **100%**, FGC **~251,638**, FGCT **~1,076,477 s ≈ 12.46 days** |
| `/proc/.../task` CPU sort | **GC Thread#0 / #1 dominate**; app threads negligible |
| `journalctl` full export | 17,784 lines → `snapshot-.../service_journal_full.txt` |
| OOME extraction | 13 OOME-related journal lines → `oome_lines.txt` |
| `curl :8092` 3s | **timeout, 0 bytes** (business dead, process alive) |
| MySQL `SELECT COUNT` | **11,358,422** rows; last insert **2026-07-08 22:56** (≈1 min before first OOME) |

### Explicitly NOT done (preserve integrity)

- No `kill` / `systemctl stop|restart`
- No forced heap dump (`jmap` / `jcmd GC.heap_dump`) — attach already fails; force dump risks further OOME
- No code redeploy onto the live service
- Jul-14 baseline evidence files left untouched; new data under `snapshot-2026-07-21T0938Z/`

See also: `PRESERVE_SCENE.md`.

---

## 2. Symptom → classification

| Symptom | Observation | Classification |
|---------|-------------|----------------|
| Host CPU ~100% / load ~2.1 | Single Java PID | Process-level, not kernel kworker |
| API `:8092` timeout | curl 3s → 0 bytes | App throughput ≈ 0 |
| systemd still active | Main PID 26810 | **Not crashed** — zombie thrash |
| Who burns CPU? | GC Thread#0/#1 jiffies ≫ DB-WRITER / BUY | **GC thrashing**, not trading logic |
| Heap | total≈used≈3051505K, 0 young regions | live set ≈ capacity |
| GC cause (live) | `G1 Compaction Pause` | Full compaction loop |

**Diagnosis label:** *GC Death Spiral* (aka GC thrashing / GC overhead collapse).

### Death-spiral criteria (all met)

1. Live set ≈ heap capacity  
2. GC reclaims ≈ 0 useful space  
3. Full GC becomes continuous  
4. Application throughput → 0 while CPU → 100%  
5. OOME appears, yet process may keep running without `ExitOnOutOfMemoryError`

---

## 3. Hard evidence matrix

### 3.1 jstat progression (objective)

| Capture | Wall time (approx) | O% | FGC | FGCT (s) | FGCT (days) | Notes |
|---------|--------------------|----|-----|----------|-------------|-------|
| Jul 14 15:04 | ~13d uptime | 100 | 114,909 | 491,352 | **5.69** | Baseline incident pack |
| **Jul 21 09:38** | **~19.7d uptime** | **100** | **251,638** | **1,076,477** | **12.46** | Live re-verify |

**Delta Jul 14 → Jul 21 (~6.8 wall days):**

- ΔFGC ≈ **+136,729**
- ΔFGCT ≈ **+585,125 s ≈ 6.77 days**

→ Over that interval, **GC time ≈ wall time** (overhead ~100%). Spiral is **ongoing**, not a past spike.

**Live 4-second sample (2026-07-21):**

```
E=0.00  O=100.00  FGC 251637→251638  FGCT +5.37s in ~4s wall
GCC: G1 Compaction Pause
OC≈OU=3051505K  EU=0
```

### 3.2 Thread CPU (`/proc`, Jul 21)

| Rank | TID | Name | utime+stime (jiffies) |
|------|-----|------|------------------------|
| 1 | 26842 | **GC Thread#1** | 106,546,772 |
| 2 | 26820 | **GC Thread#0** | 106,541,345 |
| 3 | 36615 | DB-WRITER | 192,279 |
| 4 | 26822 | G1 Conc#0 | 41,908 |
| 5 | 36613 | BUY-THREAD | 28,472 |

**Inference:** CPU high = GC empty work, not order matching.

### 3.3 MySQL (source of truth for production rate)

| Metric | Value | Source |
|--------|-------|--------|
| `orders` count | **11,358,422** | `snapshot-.../db_order_stats.txt` |
| min timestamp | 2026-07-01 08:27:34 UTC | same |
| max timestamp | **2026-07-08 22:56:24 UTC** | same |
| span | ~7.60 days | same |
| avg rate | **~17.29 orders/s** | same |
| BUY / SELL | 5,679,212 / 5,679,210 | nearly 1:1 (semaphore alternation) |
| duplicates flag | 540,465 (~4.8%) | matches ~5% design probability |

Last DB row is **~85 seconds before** first OOME (`22:57:49`). Order generation / persistence effectively **stopped at OOME edge**.

### 3.4 Journal OOME timeline (see LOG_EVENT_ANALYSIS.md)

| UTC | Thread / context | Meaning |
|-----|------------------|---------|
| 07-08 22:57:49 | `idle-timeout-task` | First OOME (infra/timer) |
| 07-08 22:58:49 | `mysql-cj-abandoned-connection-cleanup` | Driver cleanup cannot allocate |
| 07-08 22:59:23 | **`SELL-THREAD`** | Engine generator dies |
| 07-08 23:00:14 | `HTTP-Dispatcher` | REST path dies |
| 07-09 17:50+ | AttachListener / Java heap space | Diagnostics also OOME |
| 07-13 / 07-14 / 07-15 | repeated `Java heap space` | Still thrashing days later |

### 3.5 Heap / class histogram (historical, from prior session SA scan)

When SA histogram was successfully taken (prior investigation):

| Class | Instances | Interpretation |
|-------|-----------|----------------|
| `com.binance.trading.model.Order` | **~11.19M** | live orders retained |
| `BigDecimal` | ~11.19M | 1 price per Order |
| `String` / `byte[]` | ~22.4M | fields on Order |
| `ConcurrentHashMap$Node` | ~21.3M | two unbounded maps |

Counts align with DB ~11.36M rows (small delta = duplicates / in-flight / map entry shapes).

---

## 4. Root cause analysis

### 4.1 Primary root cause (code)

**File:** `src/main/java/com/binance/trading/engine/OrderBook.java`

```java
private final Map<String, Order> orders             = new ConcurrentHashMap<>();
private final Map<String, Integer> orderIdFrequency = new ConcurrentHashMap<>();
private final List<Order> allOrders                 = Collections.synchronizedList(new ArrayList<>());

public boolean addOrder(Order order) {
    String id = order.getOrderId();
    allOrders.add(order);                          // unbounded append
    orderIdFrequency.merge(id, 1, Integer::sum);   // unbounded growth
    return orders.putIfAbsent(id, order) == null;  // unbounded growth
}
```

- `clear()` exists but is **never called** on the production path (only tests / external callers).
- All three structures hold **strong references** → GC **cannot reclaim** historical orders.
- Design intent (“for analysis”) + long-running generator = **capacity bomb**.

### 4.2 Production path (rate × time)

**Files:** `Main.java` (`intervalMs = 100`), `TradingEngine.java` (BUY/SELL + semaphore)

- Comment claims “20 orders/sec”; semaphore **strict alternation** + sleep means effective rate ≈ **10–17/s**.
- Measured DB rate **17.29/s** over 7.6 days → ~11.36M rows — consistent.
- Each order also: WS broadcast + `db.saveAsync` (single-thread `DB-WRITER` on unbounded `ExecutorService` queue — secondary risk).

### 4.3 Capacity math (heap ceiling)

| Input | Value |
|-------|-------|
| Default MaxHeap (observed) | ~3.05 GB G1 heap |
| Live orders at OOME | ~11.2M in heap / 11.36M in DB |
| Rough live set | multi-GB (Order + String + BigDecimal + map nodes) |

Without eviction, **linear growth must hit MaxHeap**. First OOME at **day ~7–8** matches the math.

### 4.4 Why CPU stays high after generation stops

After `SELL-THREAD` OOME:

1. Live set remains pinned by `OrderBook` maps/list  
2. G1 repeatedly runs **compaction Full GC**, reclaims almost nothing  
3. Heap stays `used ≈ total`, young regions stay 0  
4. No `-XX:+ExitOnOutOfMemoryError` → process **does not exit**  
5. systemd sees “running” → no restart  

→ **Days to weeks of pure GC thrash** (confirmed Jul 8 → Jul 21).

### 4.5 Contributing factors (not primary)

| Factor | Role |
|--------|------|
| No `-Xmx` / GC log / HeapDumpOnOOME | Late detection; default ~3GB heap |
| Swap = 0 | No OS soft landing |
| `DB-WRITER` unbounded queue | Can amplify live refs if DB slows |
| `getAllOrders()` full copy | API can spike heap if hit |
| `OrderCache` LRU 1000 | **Not root cause** (bounded correctly) |

### 4.6 Ruled out

| Hypothesis | Why rejected |
|------------|--------------|
| Infinite business loop | Thread CPU is GC, not BUY/SELL math |
| MySQL crash | DB readable; 11M rows intact |
| Malware / kworker | PID 26810 owns CPU |
| OrderCache leak | capacity=1000 + removeEldestEntry |
| “Just need more CPU” | Would only accelerate useless GC |

---

## 5. Causal chain (fault tree)

```
UI/API POST /engine/start
        │
        ▼
BUY/SELL generate Order @ ~10–17/s  (TradingEngine)
        │
        ├─► OrderBook.allOrders / orders / orderIdFrequency   [UNBOUNDED]
        ├─► OrderCache (LRU 1000)                             [BOUNDED — OK]
        └─► DB-WRITER async INSERT                            [persisted until Jul 08 22:56]
        │
        ▼  (~7–8 days, live set → MaxHeap ~3GB)
G1 Old 100%, young regions squeezed to ~0
        │
        ▼
Full GC frequency↑, reclaim→0, pause multi-second
        │
        ▼
CPU owned by GC threads  =  GC DEATH SPIRAL
        │
        ▼
OOME cascade (idle-timeout → mysql cleanup → SELL-THREAD → HTTP)
        │
        ▼
Business dead; JVM still alive without ExitOnOOME
        │
        ▼
Continuous thrash Jul 08 → Jul 21+  (FGCT ≈ wall time)
```

---

## 6. Impact

| Dimension | Impact |
|-----------|--------|
| Availability | REST/WS effectively down (timeout) |
| Integrity | In-memory book unusable; DB history frozen at last insert |
| Cost / capacity | ~100% of 2 cores wasted on GC for 12+ days cumulative FGCT |
| Operability | `jcmd`/`jstack` attach often fail; diagnosis needs `jstat`/`journal` |
| Detection gap | No GC log, no heap metrics, systemd still “green” |

---

## 7. Corrective actions (recommended; **not executed** — scene preserved)

### P0 — stop the bleeding (when owner approves)

1. After final screenshots: `systemctl stop binance-trading-engine`  
2. Prefer stop over `kill -9` so shutdown hook can run (may hang under OOME — then kill)

### P1 — code fix

1. Bound `OrderBook` (ring buffer / max N / window)  
2. Keep **MySQL as source of truth** for full history  
3. Bound `DB-WRITER` queue (`ThreadPoolExecutor` + reject/CallerRuns + metrics)  
4. On OOME / backpressure: `engine.stop()` + degraded health  

### P2 — runtime safety net

```text
-Xms512m -Xmx1024m
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/log/trading-engine
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/var/log/trading-engine/gc.log:time,uptime,level,tags:filecount=10,filesize=20M
```

### P3 — tests / observability

| Test | Assert |
|------|--------|
| Soak 1h @ fixed rate | heap/RSS bounded |
| Unit OrderBook capacity | size stable after N |
| Chaos: slow DB | queue depth capped |
| Metrics | `orderbook_size`, `db_queue_depth`, `gc_pause_p99` |

---

## 8. Verification of this RCA

| Check | Status |
|-------|--------|
| Live PID still 26810, thrashing | ✅ Jul 21 jstat/proc |
| OOME timeline from journal | ✅ 13 lines extracted |
| Code path shows unbounded structures | ✅ OrderBook.java |
| DB count ≈ heap Order count class | ✅ 11.36M vs ~11.19M hist |
| OrderCache not unbounded | ✅ LRU 1000 |
| GC time ≈ wall after OOME | ✅ ΔFGCT ≈ Δwall Jul14→21 |

---

## 9. Final verdict

| Question | Answer |
|----------|--------|
| Is it GC death spiral? | **Yes — confirmed live as of 2026-07-21** |
| Trigger? | Unbounded `OrderBook` + multi-day continuous generation |
| Why high CPU? | Full GC thrashing (G1 Compaction Pause loop) |
| Why still alive? | OOME killed workers; no `ExitOnOutOfMemoryError` |
| First fatal time? | **2026-07-08 22:57:49 UTC** |
| Scene status? | **Preserved** — PID 26810 not touched |

---

## 10. Related documents

| Doc | Purpose |
|-----|---------|
| `PRESERVE_SCENE.md` | Do-not-touch policy |
| `LOG_EVENT_ANALYSIS.md` | Journal/log event-by-event analysis |
| `INCIDENT_REPORT.md` | Jul-14 interview-oriented incident report |
| `snapshot-2026-07-21T0938Z/` | Live re-capture artifacts |
| `EVIDENCE_INDEX.md` | File index + hashes |

*RCA authored 2026-07-21. Process intentionally left running.*
