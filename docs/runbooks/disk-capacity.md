# Runbook — Disk capacity

**English** | [繁體中文](disk-capacity.zh-TW.md)

> **Alerts**: `HostDiskLow` (<15%) · `DiskWillFillIn24h` · `DiskWillFillIn7d`

## What a predictive alert is for
`DiskWillFillIn24h` uses `predict_linear` to extrapolate from the slope of the
last six hours. It does not ask "is the disk full now" — it asks **"how long
until it is"**, and notifies while there is still time to act. That is what
capacity planning means in practice.

## First three minutes
```bash
df -h /
du -xh --max-depth=2 / 2>/dev/null | sort -rh | head -20

# The three things that grow in this project
du -sh /var/lib/mysql        # (1) MySQL data — the orders table grows continuously
docker system df             # (2) Docker images and volumes
du -sh /var/log/journal      # (3) systemd journal
```

## Stop the bleeding (safest first)
```bash
docker system prune -f                    # remove stopped containers and dangling images
sudo journalctl --vacuum-time=7d          # shrink the journal
docker volume ls -qf dangling=true | xargs -r docker volume rm
```

⚠️ **Do not** delete rows from `binance_test_db.orders` directly — that table is
part of the incident evidence chain and the baseline for the C4
"history is immutable" integrity check. If it must be shrunk, run
`tools/check-db-integrity.sh` first to establish a baseline.

## Follow-up
- [ ] Prometheus retention is 30d — evaluate whether that is longer than needed
- [ ] The orders table holds **59,557,108 rows** (measured 2026-09-06); evaluate partitioning or archiving
