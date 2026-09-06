# Runbook — MySQL saturation

**English** | [繁體中文](mysql-saturation.zh-TW.md)

> **Alerts**: `MysqlConnectionsHigh` (>80% of max_connections, 5m) · `MysqlSlowQueriesRising` (>0.1/s, 10m)

## Baseline
`max_connections = 151` (the default). In normal operation `threads_connected`
sits at roughly 1–5.

## First three minutes
```bash
mysql -u binance_user -p -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'"
mysql -u binance_user -p -e "SHOW FULL PROCESSLIST" | head -30
mysql -u binance_user -p -e "SHOW GLOBAL STATUS LIKE 'Slow_queries'"

# Which queries are running long
mysql -u binance_user -p -e "
  SELECT id, user, time, state, LEFT(info,80) AS query
  FROM information_schema.processlist
  WHERE command != 'Sleep' AND time > 5 ORDER BY time DESC;"
```

## Common sources in this project
| Source | Explanation |
|---|---|
| Integrity check doing a full scan | `tools/check-db-integrity.sh` with `WINDOW=0` scans the whole table (59,557,108 rows as of 2026-09-06) |
| Connections never released | Application connection-pool leak; `Sleep` connections accumulate |
| Write/read contention on orders | The generator writes continuously at roughly 20 rows/sec |

## Stop the bleeding
```bash
# Find and kill long-running queries (confirm first that they are not critical work)
mysql -u binance_user -p -e "KILL <id>"

# Run the integrity check with the default sampling window, not a full scan
WINDOW=300000 tools/check-db-integrity.sh
```

## Follow-up
- [ ] Schedule the integrity check off-peak (the trade-off is documented in the script's comments)
- [ ] The orders table holds **59,557,108 rows** (measured 2026-09-06); evaluate indexing and partitioning
