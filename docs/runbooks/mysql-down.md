# Runbook — MySQL unreachable

**English** | [繁體中文](mysql-down.zh-TW.md)

> **Alert**: `MysqlDown` (`mysql_up == 0`, 1m) · critical

## Why this one is special
**A MySQL restart is what triggered incident #2 in July 2026.**
`unattended-upgrades` restarted MySQL → that propagated `SIGTERM` to the
application → systemd restarted the process, but its business threads never
came back → six days of silence.

So when this alert fires, **you must also check that downstream services
actually resumed doing work** — not merely that they came back up.

## First three minutes
```bash
systemctl status mysql
mysqladmin -u binance_user -p status
journalctl -u mysql -n 50 --no-pager

# Was this caused by automatic updates?
journalctl -u unattended-upgrades --since "6 hours ago" | tail -20

# ⚠️ The critical one: did anything downstream break with it?
curl -s http://localhost:8092/api/v1/status | jq '.running, .ordersGenerated'
```

## Stop the bleeding
```bash
sudo systemctl start mysql
mysqladmin -u binance_user -p status

# Once MySQL is back, confirm the generator came back too
curl -s -X POST http://localhost:8092/api/v1/engine/start
```

## Follow-up
- [ ] Exclude MySQL from `unattended-upgrades`, or define a maintenance window
- [ ] See [engine-not-progressing](engine-not-progressing.md) for the full incident context
