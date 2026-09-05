# Python + pytest + k6 regression suite

API-level regression tests and load scripts for two services in this repo: the
**Payment API** (`payment-api/`, Fiat payments over REST) and the **trading
engine's WebSocket stream** (`trading-engine-simulator/`).

Two layers, deliberately separate:

| Layer | Tool | Answers |
|---|---|---|
| Functional / contract | `pytest` | Is the behaviour correct? |
| Performance | `k6` | Does it stay correct under sustained request rate? |

Correctness is asserted where it can be asserted precisely (pytest), and latency
is measured where the measurement is meaningful (k6). Mixing the two produces a
suite that is bad at both.

---

## Prerequisites

Already installed on this machine:

- Python 3.12.3 (Ubuntu 24.04, aarch64)
- k6 v2.1.0 → `~/.local/bin/k6`
- JDK 21 + Maven (to build the service under test)

Ubuntu 24.04 is PEP 668 (`EXTERNALLY-MANAGED`), so dependencies live in a venv —
`pip install` outside one will be refused by the system Python.

## Setup

```bash
cd python-qa
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

Build the service under test (once, or after changing it):

```bash
cd .. && mvn -q package -pl payment-api -am -DskipTests
```

## Running

```bash
cd python-qa

.venv/bin/python -m pytest                    # everything (~30s)
.venv/bin/python -m pytest -m smoke           # release gate only
.venv/bin/python -m pytest -m "not websocket" # REST only (~4s)
.venv/bin/python -m pytest -m websocket       # the trading engine stream (~25s)
.venv/bin/python -m pytest -m "idempotency or concurrency"
.venv/bin/python -m pytest -n auto            # parallel
.venv/bin/python -m pytest --html=report.html --self-contained-html
```

The WebSocket tests are slower than the rest because the stream broadcasts on a
1-second schedule — waiting for four ticks is four seconds, and no amount of test
engineering makes that faster.

By default pytest **starts its own servers** on OS-assigned ports and stops them
afterwards. Nothing else on the machine is touched. To target a running
Payment API instead:

```bash
.venv/bin/python -m pytest --base-url http://127.0.0.1:8091
.venv/bin/python -m pytest --repo-mode jdbc   # H2-backed repository
```

k6:

```bash
./k6/run.sh smoke.js                          # functional gate, ~3s
./k6/run.sh load_payments.js                  # 20 rps for 30s (default)
RATE=50 DURATION=2m ./k6/run.sh load_payments.js
./k6/run.sh ws_stream.js                      # 10 WebSocket clients for 10s
CLIENTS=50 HOLD_MS=30000 ./k6/run.sh ws_stream.js
BASE_URL=http://127.0.0.1:8091 API_KEY=... ./k6/run.sh smoke.js
WS_URL=ws://127.0.0.1:8093 ./k6/run.sh ws_stream.js
```

`run.sh` picks the service from the script name (`ws_*.js` → trading engine,
otherwise Payment API), starts a throwaway instance on random ports unless
`BASE_URL`/`WS_URL` is set, and runs everything under `nice -n 15` so a load run
loses any CPU contest against whatever else is on the box.

## Layout

```
python-qa/
├── conftest.py               fixtures: server lifecycle, HTTP client, test data
├── pytest.ini                markers, strict-marker enforcement
├── requirements.txt          pinned
├── tests/
│   ├── test_health.py            readiness, auth exemption, hang detection
│   ├── test_payment_contract.py  status codes + JSON Schema, async settlement, CORS
│   ├── test_validation.py        boundaries: amount, precision, field lengths, body size
│   ├── test_idempotency.py       replay safety, no double debit, key reuse
│   ├── test_concurrency.py       30-thread retry storms against one key
│   ├── test_auth.py              X-API-Key: missing, wrong, prefix, rejection-before-work
│   └── test_websocket.py         trading engine stream: envelope, cadence, fan-out
└── k6/
    ├── smoke.js              create → replay → poll, zero tolerance thresholds
    ├── load_payments.js      constant-arrival-rate, business-outcome metrics
    ├── ws_stream.js          N concurrent subscribers, broadcast-gap tracking
    └── run.sh                picks the service, starts/stops it, nice'd
```

## Status

- **57 pytest tests — all passing** (48 REST in 3.6s, 9 WebSocket in 24.8s)
- **`k6/smoke.js` — verified passing** (20 iterations, 61 requests, 0 failures,
  p95 45.7ms)
- **`k6/ws_stream.js` — verified passing** at 5 clients × 8s: 40 messages, 0
  connect errors, broadcast gap p95 1.01s / max 1.01s. Not yet run at higher
  client counts, which is where the fan-out cost would actually show.
- **`k6/load_payments.js` — written but never executed.** It was not run because
  this machine is shared with a long-running order generator whose throughput
  must not be disturbed. Treat its thresholds as unvalidated guesses until
  someone runs it on a box they own.

## Design decisions

**The server is started by the fixture, on port 0.** Port 0 makes the OS assign a
free port atomically, so parallel runs cannot collide the way a hardcoded port
would, and a suite run can neither pollute nor be polluted by a developer's
manually started server. The fixture also mints a fresh API key per run — a 401
test then proves the header is actually checked, rather than proving that some
stale ambient key happened to be wrong.

**Every test gets a fresh user id.** In `memory` mode an unseen user is
auto-provisioned with 1,000,000 USDT. Sharing an account across tests would let
one test's debit change another's expected outcome, which is the classic way an
API suite becomes order-dependent.

**Balances are asserted behaviourally.** The API exposes no balance endpoint, so
"was this account debited?" is answered by whether a follow-up payment for the
remaining balance succeeds. It is indirect, but it tests the contract that
actually exists rather than one invented for the test's convenience.

**k6 uses `constant-arrival-rate`, not ramping VUs.** Arrival rate models what
clients do — send N requests per second — and cannot silently escalate when the
server slows down. A VU ramp under a slow server keeps piling on concurrency and
measures the load generator as much as the target. `maxVUs` is a hard ceiling.

**Response bodies are checked against a JSON Schema with
`additionalProperties: false`.** A field quietly renamed by the backend fails
here, rather than in a mobile client three sprints later.

**The WebSocket tests run against an engine that is deliberately left STOPPED.**
`DBOrderRepository`'s JDBC URL is hardcoded to the shared `binance_test_db`, so
*any* instance whose engine runs writes into the same `orders` table as the
deployed service — a test suite that generated orders would silently corrupt
production-shaped data. A stopped engine still broadcasts `STATS_UPDATE` every
second, which is enough to test the envelope, the cadence, and fan-out. The
constraint is enforced, not assumed: `test_stopped_engine_generates_nothing`
asserts `status == "STOPPED"` and `total_generated == 0`, and `ws_stream.js`
re-checks both on every message it receives.

## Findings

Behaviours found while building this suite. None are fixed here — the tests pin
current behaviour so any change to it is a deliberate one.

**1. Early rejections do not drain the request body, so the connection resets.**
`unauthorized()` answers 401 before `handleCreate` reads the body, and the 64 KB
body cap leaves the remainder unread on oversized requests. The server closes the
exchange with bytes still in flight and the client sees a TCP reset instead of
the response it was sent. Reproduced on both paths.
*Impact:* a client that gets an RST cannot read the 401 and cannot distinguish
"bad credentials" from "network fault" — so it retries, when it should stop.
*Worked around* by one bounded retry in `ApiClient.request` (safe because every
payment carries an idempotency key). The workaround belongs in the client; the
fix belongs in the server.

**2. An idempotency key replayed with a different body returns the original,
silently.** Sending key `K` with amount 25, then key `K` with amount 999, answers
200 with amount 25. The caller gets a success response for a payment it did not
make. Pinned by `test_replay_with_a_different_amount_returns_the_original`.
*Standard fix:* store a request fingerprint with the key and answer 409 on
mismatch (Stripe's behaviour). Out of scope here.

**3. The 200-vs-202 split is racy; the payment itself is not.** `isAlreadyProcessed`
is a separate read taken before the create, so concurrent retries of one key can
all observe "not yet processed" and all answer 202. Under 30 simultaneous
retries the suite asserts **one** `payment_id` — which holds, because
`createPayment` runs inside `computeIfAbsent` — but deliberately does *not*
assert exactly one 202. Asserting it would produce a flaky test blaming the
service for something it never promised.

**4. Whitespace-only header values never reach the server.** `requests` rejects
them client-side (`InvalidHeader`), so `X-API-Key: "   "` is untestable through
this client. The auth suite uses `"wrong key"` instead — a transmittable value
that keeps the space.

**5. `ENGINE_STATUS` is specified but never sent.** `TradingWebSocketServer`'s
javadoc documents three message types; only `ORDER_CREATED` and `STATS_UPDATE`
are ever passed to `broadcast()`. `TradingApiServer` is not even constructed with
a reference to the WebSocket server, so `POST /api/v1/engine/start|stop`
*cannot* notify subscribers.
*Impact:* a client that opened the stream to learn when the engine started or
stopped waits forever. It has to poll `GET /api/v1/status`, or read the `status`
field inside the once-a-second `STATS_UPDATE` — meaning state changes are seen up
to a second late, and only while the stats scheduler is alive. Pinned by
`test_engine_status_is_never_broadcast`.

## Known gaps

- `load_payments.js` has never been executed (see Status).
- **`ORDER_CREATED` broadcasts are untested.** They only fire while the engine is
  generating orders, and a running engine writes into the shared
  `binance_test_db.orders` table (see Design decisions). Closing this gap needs
  one of: a configurable JDBC URL so a test instance can point at a scratch
  database, a no-persistence flag, or an agreed window in which polluting that
  table is acceptable. Currently `DBOrderRepository`'s URL is a `private static
  final` constant, so none of those is possible without a code change.
- `ws_stream.js` has only been run at 5 clients. The fan-out cost it was written
  to measure — `broadcast()` iterating every socket inline on the 1s scheduler
  thread — does not show up at that size.
- `--repo-mode jdbc` is wired but the suite has only been run against `memory`.
  The JDBC path has different semantics — an unknown user is a 404 rather than an
  auto-provisioned account — so some validation tests will need markers before
  that mode passes clean.
