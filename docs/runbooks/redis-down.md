# Runbook — Redis unreachable

**English** | [繁體中文](redis-down.zh-TW.md)

> **Alert**: `RedisDown` (`redis_up == 0`, 1m) · critical

## First three minutes
```bash
redis-cli -h 127.0.0.1 ping              # expect PONG
systemctl status redis-server
ss -tlnp | grep 6379
journalctl -u redis-server -n 50 --no-pager
```

## Common causes
| Symptom | Cause |
|---|---|
| OOM killer messages in the journal | Host out of memory, Redis was killed → [redis-unbounded](redis-unbounded.md) |
| `MISCONF` errors | RDB snapshot write failed (disk full) → [disk-capacity](disk-capacity.md) |
| Connection refused | Service not started, or `bind` / `protected-mode` changed |
| Exporter down but Redis fine | Only the `obs-redis-exporter` container is broken |

## Stop the bleeding
```bash
sudo systemctl restart redis-server
redis-cli ping
```

## Follow-up
- [ ] Confirm the persistence settings (RDB / AOF) and the acceptable data-loss window
