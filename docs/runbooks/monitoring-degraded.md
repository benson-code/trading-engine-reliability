# Runbook — The monitoring system itself is degraded

**English** | [繁體中文](monitoring-degraded.zh-TW.md)

> **Alerts**: `DeadMansSwitch` · `ScrapeTargetDown` · `ScrapeDurationHigh` · `TextfileCollectorStale` · `TextfileCollectorError` · `PrometheusRuleEvaluationFailing` · `PrometheusTsdbCompactionFailing` · `AlertmanagerNotificationFailing`
> **Severity**: critical / warning (and `none` for the heartbeat)
> **Origin**: the lesson of incidents #1 and #2, applied to the monitoring stack itself

---

## Why this group exists

Both incidents in this repository share one shape: **a service went quiet, and
everyone read the silence as health.**

The monitoring stack fails the same way. Rule evaluation stops, a scrape target
disappears, notifications stop being delivered — and nothing on a dashboard
turns red, because no data is arriving to turn it red.

> **"No alerts" has two possible meanings: everything is fine, or the alerting
> system is dead.** This group exists to tell those two apart.

---

## Triggers

| Alert | Expression | For | Severity |
|---|---|---|---|
| `DeadMansSwitch` | `vector(1)` — always firing | — | none |
| `ScrapeTargetDown` | `up{job!="node"} == 0` | 5m | warning |
| `ScrapeDurationHigh` | `scrape_duration_seconds > 5` | 10m | warning |
| `PrometheusRuleEvaluationFailing` | `increase(prometheus_rule_evaluation_failures_total[10m]) > 0` | 5m | critical |
| `PrometheusTsdbCompactionFailing` | `increase(prometheus_tsdb_compactions_failed_total[1h]) > 0` | 15m | warning |
| `TextfileCollectorStale` | `time() - node_textfile_mtime_seconds > 180` | 2m | critical |
| `TextfileCollectorError` | `node_textfile_scrape_error > 0` | 5m | warning |
| `AlertmanagerNotificationFailing` | `increase(alertmanager_notifications_failed_total[10m]) > 0` | 5m | critical |

`job="node"` is excluded from `ScrapeTargetDown` because
[host-down](host-down.md) already covers the host itself.

---

## ⚠️ `DeadMansSwitch` reads backwards

This alert is **permanently firing on purpose**. It signals nothing about the
services being monitored.

| | Meaning |
|---|---|
| You are receiving it | The chain Prometheus → rule evaluation → Alertmanager → webhook is alive |
| **You stopped receiving it** | **Something in that chain is dead. This is the incident.** |

Alertmanager routes it to the `heartbeat` receiver every 5 minutes. In
production the receiver is an external heartbeat service (Dead Man's Snitch,
Healthchecks.io, or equivalent) which notifies you when it *stops* arriving.
Set that service's timeout well above 5 minutes — 15 minutes is a reasonable
starting point, so one missed delivery does not page anyone.

**The classic way to break this pattern** is an inhibition rule that
accidentally swallows the heartbeat. Every `target_matchers` in
`alertmanager.yml` that could match it explicitly excludes
`alertname != "DeadMansSwitch"`. If you add an inhibition rule, check it against
the heartbeat first.

---

## ⚠️ A frozen metric is worse than a missing one

`jvm.prom` and `engine.prom` are written by cron every 30s and read by
node_exporter. If cron dies or the script starts failing, the files stop being
updated — **but node_exporter keeps serving the last values it read, forever.**

The metrics do not disappear. They freeze. And a frozen metric reads as healthy:

| Frozen metric | Consequence |
|---|---|
| `jvm_jstat_attach_success` stuck at `1` | **`JstatAttachFailed` can never fire** — the alert that exists precisely because attach failure was the key diagnostic in incident #1 |
| `jvm_oldgen_utilization_ratio` stuck at a healthy value | The JVM can walk into a death spiral with a green dashboard |
| `engine_orders_generated_total` stuck | `EngineNotProgressing` fires and blames the engine, when the collector is what died |

`TextfileCollectorStale` is the only thing that separates "the service is fine"
from "the eye watching the service closed". There is an executable test for this
exact blind spot in
[`alerts_test.yml`](../../deploy/observability/prometheus/alerts_test.yml) —
it asserts that `JstatAttachFailed` stays silent while the collector is dead,
and that `TextfileCollectorStale` catches it.

```bash
# Is cron still running the collector?
crontab -l | grep jstat

# How old is each file, in seconds?
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=time() - node_textfile_mtime_seconds' \
  | jq -r '.data.result[] | "\(.metric.file)\t\(.value[1])s"'

# Run it by hand and see what it says
deploy/observability/jstat-exporter.sh; echo "exit=$?"
```

---

## The config on disk is not necessarily the config that is running

Found on 2026-09-06: `obs-blackbox` had been running a configuration that **no
longer existed on disk** since 09-03. A git operation (`checkout`, `reset --hard`)
recreated the `blackbox/` directory, and the bind mount kept pointing at the old
inode — so inside the container that directory was empty.

The exporter was completely fine. It had read its config into memory at startup
and kept serving probes from it. Editing `blackbox.yml` had no effect and
produced no error. Three days, no symptom.

This is config drift with a green dashboard — the same shape as everything else
in this repo, except what silently diverged was the configuration rather than the
code.

```bash
# Does every monitoring container still see the config on disk?
make obs-mounts

# It compares sha256, not just existence - a stale mount and a
# half-written file are different failures.
```

If a mount is broken, recreate that container. A restart is not enough on some
runtimes; force the recreate so the mount is re-established:

```bash
docker compose -f deploy/observability/docker-compose.yml \
  up -d --force-recreate <service>
```

> Reloading is not a substitute for checking. `curl -X POST .../-/reload`
> returning 500 is what exposed this — but a reload that is never attempted
> will never tell you anything, which is why `make obs-mounts` exists.

---

## First three minutes

```bash
# 1. Is Prometheus itself healthy, and is it evaluating rules?
curl -s http://localhost:9090/-/healthy
curl -s http://localhost:9090/api/v1/rules | jq -r \
  '.data.groups[] | "\(.name)\tlast_eval=\(.lastEvaluation)\tdur=\(.evaluationTime)"'

# 2. Which scrape targets are down
curl -s http://localhost:9090/api/v1/targets | jq -r \
  '.data.activeTargets[] | select(.health!="up") | "\(.labels.job)\t\(.scrapeUrl)\t\(.lastError)"'

# 3. Is Alertmanager receiving and delivering
curl -s http://localhost:9093/-/healthy
curl -s http://localhost:9093/api/v2/alerts | jq -r '.[] | .labels.alertname' | sort | uniq -c

# 4. Is the heartbeat actually arriving at the receiver
#    (the webhook sink logs every delivery)
docker logs obs-alertmanager 2>&1 | tail -20
```

---

## Triage

| What you see | What it means | Where to go |
|---|---|---|
| Heartbeat stopped, Prometheus unhealthy | Prometheus is down or wedged | Restart it; check disk and memory |
| Heartbeat stopped, Prometheus healthy | Alertmanager or the webhook path is broken | Check `AlertmanagerNotificationFailing` |
| `PrometheusRuleEvaluationFailing` | **A rule group is not being computed at all** | See below — this is the most dangerous one |
| `ScrapeTargetDown` on one job | That job's metrics are frozen; its alerts have gone quiet | Fix the exporter or the service |
| `ScrapeDurationHigh` | The target is saturated | [host-saturation](host-saturation.md) |
| `PrometheusTsdbCompactionFailing` | Disk or permissions | [disk-capacity](disk-capacity.md) |

---

## Rule evaluation failure is the dangerous one

When a rule group fails to evaluate, **its alerts are not evaluated at all.**
They do not fire, they do not resolve — they simply are not computed. There is
no symptom anywhere except this alert.

```bash
# Which group is failing, and why
curl -s http://localhost:9090/api/v1/rules | jq -r \
  '.data.groups[] | select(.rules[]?.health? == "err") |
   "\(.name): \(.rules[] | select(.health=="err") | .lastError)"'

# Common causes:
#   - the rule references a metric that no longer exists
#   - a subquery is too expensive and times out (the SLO group is the usual suspect)
#   - a recording rule depends on another recording rule that failed first
```

Validate the rule files before reloading:

```bash
make obs-validate     # promtool check rules, for alerts.yml AND slo.yml
make obs-reload       # hot reload without restarting the container
```

---

## Stop the bleeding

```bash
# Reload configuration without dropping the TSDB
make obs-reload

# If Prometheus is wedged rather than misconfigured
docker restart obs-prometheus

# Confirm the chain end to end afterwards: the heartbeat should reappear
# within one group_interval (5 minutes)
docker logs obs-alertmanager 2>&1 | tail -5
```

⚠️ Restarting Prometheus loses nothing durable — the TSDB is on a volume — but
it **does** reset the `slo:*` recording series' in-memory evaluation state.
Long-window SLO ratios read optimistically until they refill. See
[`docs/slo.md`](../slo.md), *Known limitations*.

---

## Follow-up

- [ ] If a scrape target was down, was there an alert for the underlying service
      too? If the only signal was `ScrapeTargetDown`, that service has no
      independent liveness check — add one.
- [ ] Point `DeadMansSwitch` at an external heartbeat service. Delivering it to
      a webhook on the same host proves less than it appears: if the host dies,
      both the sender and the receiver die together and nobody is told.
- [ ] Any new inhibition rule must be checked against the heartbeat.
