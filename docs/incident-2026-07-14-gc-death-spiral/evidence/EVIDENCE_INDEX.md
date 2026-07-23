# Evidence Index — QA Trading Engine GC Incident

**Root:** `/home/ubuntu/qa-incident-2026-07-14/`  
**Policy:** See `PRESERVE_SCENE.md` — PID **26810** must not be killed/restarted without owner approval.

---

## Documents (analysis)

| File | Description |
|------|-------------|
| `PRESERVE_SCENE.md` | Do-not-touch policy |
| `RCA_REPORT.md` | Full root-cause analysis (2026-07-21) |
| `LOG_EVENT_ANALYSIS.md` | Journal/log event timeline & patterns |
| `INCIDENT_REPORT.md` | Jul-14 interview-oriented incident report |

---

## Baseline pack (2026-07-14)

| File | Description |
|------|-------------|
| `SNAPSHOT.txt` | Process snapshot |
| `INCIDENT_START.txt` | Capture start marker |
| `service_journal_full.txt` | Journal export |
| `service_journal_tail200.txt` | Journal tail |
| `oome_lines.txt` | OOME lines |
| `timeline.txt` | Key timeline extract |
| `gc_journal_hits.txt` | GC-related journal hits |
| `jstat_gcutil.txt` / `_more` / `_final` | GC utilization |
| `jstat_gc.txt` / `_more` | GC capacity counters |
| `jstat_gccause.txt` | GC cause |
| `thread_cpu_proc.txt` | Per-thread CPU |
| `proc_status.txt` / `smaps_rollup.txt` | Memory |
| `binance-trading-engine.service` | Unit file (no -Xmx) |
| `db_order_stats.txt` | MySQL stats |
| `api_status.txt` / `api_orders_sample.txt` | API probes |
| `java_*.txt` / `thread_dump.txt` | JVM / dump attempts |
| `EVIDENCE_SHA256.txt` | Hashes of baseline files |

---

## Live re-capture (2026-07-21T09:38Z)

Directory: `snapshot-2026-07-21T0938Z/`

| File | Description |
|------|-------------|
| `SNAPSHOT.txt` | Capture metadata + ps + systemctl |
| `process_still_alive.txt` | Alive marker |
| `jstat_gcutil_5s.txt` | 5 samples @1s |
| `jstat_gc_3s.txt` | GC sizes |
| `jstat_gccause_3s.txt` | Cause = G1 Compaction Pause |
| `thread_cpu_proc.txt` | GC threads dominate |
| `service_journal_full.txt` | Full journal re-export (17,784 lines) |
| `oome_lines.txt` | 13 OOME-related lines |
| `journal_key_hits.txt` | 439 filtered key lines |
| `db_order_stats.txt` | 11,358,422 orders; last ts Jul 8 22:56 |
| `api_status.txt` | curl timeout |
| `proc_status.txt` / `smaps_rollup.txt` | RSS ~3.07 GB |
| `binance-trading-engine.service` | Unit copy |
| `cmdline.txt` / `limits.txt` / `fd_count.txt` | Process env |

---

## Source code anchors (read-only)

| Path | Relevance |
|------|-----------|
| `qa/trading-engine-simulator/.../OrderBook.java` | Unbounded collections — **primary root cause** |
| `.../TradingEngine.java` | BUY/SELL generator + semaphore |
| `.../Main.java` | intervalMs=100; wiring |
| `.../OrderCache.java` | LRU 1000 — **not** root cause |
| `.../DBOrderRepository.java` | Unbounded single-thread writer queue |

---

## Integrity

Generate / refresh hashes for the Jul-21 snapshot:

```bash
cd /home/ubuntu/qa-incident-2026-07-14/snapshot-2026-07-21T0938Z
sha256sum * > ../EVIDENCE_SHA256_2026-07-21.txt
```
