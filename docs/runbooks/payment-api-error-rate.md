# Runbook — payment-api server error rate

**English** | [繁體中文](payment-api-error-rate.zh-TW.md)

> **Alert**: `PaymentApiErrorRateHigh` (5xx share > 1%, 5m) · critical
> **Source**: `payment_requests_total`, exposed by the service itself at `/metrics`

---

## Triggers

```promql
sum by (deployment) (rate(payment_requests_total{status=~"5.."}[5m]))
/
sum by (deployment) (rate(payment_requests_total[5m]))
> 0.01
```

**Where the threshold comes from**: this is not a measured baseline, because in
normal operation the 5xx rate here is exactly zero. 1% is the point at which one
request in a hundred fails — below any latency symptom, but well above noise for
a service with no known error modes. If a legitimate error class is introduced
later (say, a downstream timeout that is retried successfully), re-derive it.

---

## Why this alert did not exist before

Every prior signal about this service came from **outside** it: a black-box
probe of `/health`. That probe answers "is the process listening", and it will
happily report 100% success while `/api/v1/payments` returns 500 to every real
caller — because it never calls that endpoint.

| Signal | Can it see a 5xx on /payments? |
|---|---|
| `probe_success` (black-box) | ❌ only probes `/health` |
| `ServiceSlowResponse` | ❌ measures duration, not status |
| `EngineNotProgressing` | ❌ different service |
| `payment_requests_total` | ✅ |

This is the same category of blind spot as incidents #1 and #2: **an outside
observer reporting health while the thing it is not looking at is broken.**

---

## The `deployment` label matters

The same image runs in three places, and two of them are scraped:

| `deployment` | Where | Port |
|---|---|---|
| `host` | systemd, `binance-payment-api.service` | 8091 |
| `container` | Docker | 8094 |

| What you see | What it means |
|---|---|
| One deployment only | Environment difference — config, resource limits, a dependency reachable from one and not the other |
| Both deployments | The code itself, or a shared dependency (MySQL, Redis) |

That comparison is free diagnosis: identical code, different environments, one
of them failing.

---

## First three minutes

```bash
# 1. Which deployment, which route, which status
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (deployment,route,status) (rate(payment_requests_total{status=~"5.."}[5m]))' \
  | jq -r '.data.result[] | "\(.metric.deployment)\t\(.metric.route)\t\(.metric.status)\t\(.value[1])"'

# 2. Total request rate, to know whether 1% is one request or ten thousand
curl -sG http://localhost:9090/api/v1/query --data-urlencode \
  'query=sum by (deployment) (rate(payment_requests_total[5m]))' \
  | jq -r '.data.result[] | "\(.metric.deployment)\t\(.value[1]) req/s"'

# 3. What the service is actually logging
journalctl -u binance-payment-api -n 100 --no-pager | grep -iE 'exception|error|500'
docker logs payment-api 2>&1 | tail -50

# 4. Are its dependencies healthy
make obs-alerts | grep -iE 'mysql|redis'
```

---

## Triage

| What you see | Likely cause | Where to go |
|---|---|---|
| 5xx on `/api/v1/payments` only, MySQL alerts lit | Repository layer failing | [mysql-down](mysql-down.md) / [mysql-saturation](mysql-saturation.md) |
| 5xx across every route | The process is unwell — check GC and threads | [gc-death-spiral](gc-death-spiral.md) |
| Only `deployment="container"` | Container config: env vars, memory limit, no route to a dependency | `docker logs payment-api` |
| Only `deployment="host"` | systemd unit environment, or a stale jar | `systemctl cat binance-payment-api` |
| 5xx rate high but request rate near zero | A handful of failures over tiny volume — confirm before waking anyone | check absolute counts in step 2 |

⚠️ **Step 2 exists to stop a false page.** At very low traffic, a single failed
request is 100% of a 5-minute window. Always read the ratio next to the absolute
rate.

---

## Stop the bleeding

```bash
# Container: restart is cheap and loses nothing (in-memory repository)
make docker-stop && make docker-run

# Host: the same, via systemd
sudo systemctl restart binance-payment-api

# Kubernetes: roll the deployment, and measure what the roll itself costs
make k8s-drill
```

⚠️ If the errors come from a dependency being down, restarting the API changes
nothing and destroys the evidence. Confirm step 4 first.

---

## Root-cause investigation

1. **Compare the two deployments.** Identical code failing in one environment
   and not the other narrows the search enormously — start from what differs
   (`docker inspect payment-api` vs `systemctl cat binance-payment-api`).

2. **Check whether errors correlate with a deploy.** `payment_requests_total` is
   a counter that resets on restart, so a sharp reset in
   `jvm_process_uptime_seconds` marks each deploy boundary:

   ```bash
   curl -sG http://localhost:9090/api/v1/query_range --data-urlencode \
     'query=jvm_process_uptime_seconds' --data-urlencode "start=$(date -u -d '-6 hours' +%s)" \
     --data-urlencode "end=$(date -u +%s)" --data-urlencode 'step=300' \
     | jq -r '.data.result[] | .metric.deployment as $d | .values[] | "\($d) \(.[0]) \(.[1])"'
   ```

3. **Look at the latency histogram alongside the errors.** Errors that are fast
   are usually validation or auth; errors that are slow are usually a timeout
   against something downstream.

---

## Follow-up

- [ ] Does this error class deserve its own status code and its own SLI? A 5xx
      that is expected and retried successfully should not consume error budget.
- [ ] If the cause was environment drift between `host` and `container`, that
      difference belongs in configuration that is reviewed, not in two places
      that quietly disagree.
- [ ] Consider a black-box probe that exercises `/api/v1/payments` rather than
      only `/health` — this alert depends on real traffic existing, and traffic
      to this service is currently synthetic.
