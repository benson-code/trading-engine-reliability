# Runbook — Host unreachable

**English** | [繁體中文](host-down.zh-TW.md)

> **Alert**: `HostDown` (`up{job="node"} == 0`, 2m) · critical

## A note on behaviour
This alert has an **inhibition rule** in Alertmanager: when the host is down, it
suppresses every other alert originating from that host and leaves only this
one. The point is that a single failure should not produce twenty notifications.

## First three minutes
```bash
ping -c 3 <host>
ssh <host> uptime                                    # is SSH still up?
curl -s -m 3 http://<host>:9100/metrics | head -1    # or is it only the exporter?
```

## Triage
| What you see | What it means |
|---|---|
| No ping, no SSH | Host or network-layer problem → check the cloud console |
| Ping OK, SSH OK, :9100 dead | Only node_exporter died. The host is fine |
| SSH connects but very slowly | Host resources exhausted → [host-saturation](host-saturation.md) |

## Stop the bleeding
```bash
docker compose -f deploy/observability/docker-compose.yml up -d node-exporter
```

## Follow-up
- [ ] If it was only the exporter, consider splitting this into `HostDown` and `ExporterDown`
