# Spec Conformance & Functional Verification

This document maps every claim in [`README.md`](README.md) to concrete, reproducible evidence — automated tests and live HTTP runs. It is a point-in-time verification record.

| | |
|---|---|
| **Verified against** | `main` @ `67afe59` |
| **Date** | 2026-05-21 |
| **Build** | `mvn clean test` → **BUILD SUCCESS**, 0 failures / 0 errors |
| **Test totals** | payment-api **43** · trading-engine **63** = **106 local / 98 CI** |
| **Live runs** | payment-api fat jar (JDBC + auth) · trading-engine `:8092`/`:8093` |
| **Result** | ✅ **All README claims verified** |

Reproduce everything below with:

```bash
mvn clean test                                                            # §1, §5
mvn -q -pl payment-api package -DskipTests                               # build jar
PAYMENT_REPO=jdbc PAYMENT_API_KEY=demo-key \
  java -jar payment-api/target/payment-api-qa-framework-1.0.0.jar 8091   # §2–§4 live
```

---

## §1 — Test inventory (README: "43 tests" / "98 in CI")

`mvn clean test` (forced full recompile), per-class counts:

| payment-api (43) | n | trading-engine (63) | n |
|---|--:|---|--:|
| unit/PaymentServiceTest | 10 | unit/AmountValidatorTest | 26 |
| api/PaymentServiceE2ETest | 9 | unit/LRUCacheTest | 9 |
| db/JdbcPaymentRepositoryTest | 6 | db/DBValidationTest | 8 |
| api/PaymentAuthTest | 5 | api/TradingEngineApiTest | 7 |
| db/BalanceVerificationTest | 4 | unit/DuplicateOrderDetectorTest | 6 |
| api/PaymentAPITest | 3 | integration/TradingFlowIntegrationTest | 4 |
| api/IdempotencyTest | 2 | unit/AlternatePrintTest | 3 |
| integration/PaymentFlowTest | 2 | | |
| concurrency/ConcurrentIdempotencyTest | 2 | | |

- **Local (MySQL up):** 43 + 63 = **106**, 0 skipped.
- **CI (no MySQL):** the 8 `DBValidationTest` cases are environment-gated → 43 + 55 = **98** (matches the `Java Tests` required check). ✅

---

## §2 — payment-api REST contract (README "REST API" table)

Live `curl` against the fat jar in **JDBC + `X-API-Key` mode**. Every documented status code reproduced exactly:

| # | Request | Documented | Observed |
|---|---|---|:--:|
| 1 | `POST /payments` valid, correct key | `202 Accepted` | **202** ✅ |
| 2 | `POST /payments` same idempotency_key | `200 OK` (replay) | **200** ✅ |
| 3 | `POST /payments` amount ≤ 0 | `400 INVALID_AMOUNT` | **400** ✅ |
| 4 | `POST /payments` amount 0.123456789 | `400 INVALID_PRECISION` | **400** ✅ |
| 5 | `POST /payments` blank user_id | `400 VALIDATION_ERROR` | **400** ✅ |
| 6 | `POST /payments` malformed JSON | `400 BAD_REQUEST` | **400** ✅ |
| 7 | `POST /payments` no/`wrong X-API-Key` | `401 UNAUTHORIZED` | **401** ✅ |
| 8 | `POST /payments` amount > balance | `402 INSUFFICIENT_BALANCE` | **402** ✅ |
| 9 | `POST /payments` unknown account | `404 ACCOUNT_NOT_FOUND` | **404** ✅ |
| 10 | `POST /payments` currency ≠ account | `422 CURRENCY_MISMATCH` | **422** ✅ |
| 11 | `GET /payments/{jobId}/status` + key | `200` `status: SUCCESS` | **200 SUCCESS** ✅ |
| 12 | `GET /payments/{jobId}/status` no key | `401 UNAUTHORIZED` | **401** ✅ |
| 13 | `GET /payments/{unknown}/status` | `404 JOB_NOT_FOUND` | **404** ✅ |
| 14 | `GET /health` (no key) | `200 {"status":"UP"}` | **200** ✅ |
| 15 | `OPTIONS /payments` (CORS preflight) | `204` | **204** ✅ |
| 16 | `DELETE /payments` | `405 Method Not Allowed` | **405** ✅ |

All 10 error codes the README documents are produced by the running service.

---

## §3 — payment-api Key Scenarios (README "Key Test Scenarios")

| Claim | Evidence | ✅ |
|---|---|:--:|
| **Idempotency:** duplicate key → same `payment_id`, `200` not second `202` | live #1→#2 (same `payment_id`); `IdempotencyTest`, `PaymentServiceE2ETest.duplicate_key_returns_same_payment_id` | ✅ |
| **ACID:** debit + insert in one tx; insufficient/duplicate → rollback, balance unchanged, no orphan row; unknown account → 404, nothing persisted | `JdbcPaymentRepositoryTest` (insufficient_balance_rolls_back_atomically, unknown_account_is_rejected, duplicate_key_debits_exactly_once); `BalanceVerificationTest` | ✅ |
| **Concurrency:** 16 threads, same key → exactly-one debit | `ConcurrentIdempotencyTest` (parameterized over **both** repos) | ✅ |
| **Async 202 flow:** POST→202+job_id→GET status→SUCCESS | live #1, #11; `PaymentServiceE2ETest.async_job_settles_to_success` | ✅ |
| **Unit validation:** amount>0, non-blank key/userId, currency, lengths, precision | `PaymentServiceTest` (10 cases) | ✅ |

---

## §4 — payment-api Auth & error-code accuracy (README notes)

| Claim | Evidence | ✅ |
|---|---|:--:|
| `X-API-Key` required when `PAYMENT_API_KEY` set; constant-time compared | live #7, #12; `PaymentAuthTest` (5 cases); `MessageDigest.isEqual` in `PaymentApiServer` | ✅ |
| `/health` always exempt from auth | live #14 (200 with no key while auth on) | ✅ |
| No key configured → API open (demo default) | memory-mode boot: banner `Auth: disabled`, happy path `202` without key | ✅ |
| `402` reserved for real insufficient balance only | live #8 = 402; oversize input #5 = 400 (not 402) | ✅ |
| Oversized fields → `400`, never SQL-truncation `402`/`500` | live #5; `PaymentServiceTest.should_reject_overlong_*` | ✅ |
| Precision > 8 dp → `400 INVALID_PRECISION`, trailing zeros not over-rejected | live #4 = 400; `100.500000000` → 202 (`PaymentServiceE2ETest`) | ✅ |
| Currency mismatch → `422`, never silently accepted | live #10 = 422; `JdbcPaymentRepositoryTest.currency_mismatch_is_rejected` | ✅ |
| `PaymentRepository` swap seam via `PAYMENT_REPO=jdbc` | both modes boot & serve (banner reports repo); same contract in each | ✅ |

---

## §5 — trading-engine-simulator (README Module 2)

Tests: **63 local / 55 CI** (Unit 44 = 26+9+6+3, API 7, Integration 4, DB-Validation 8 gated) — matches the README suite table. ✅

Live REST (`:8092`) + WebSocket (`:8093`), engine start/stop lifecycle:

| Endpoint / action | Observed | ✅ |
|---|---|:--:|
| `GET /api/v1/status` | `200` — full metrics (generated/unique/duplicates/cache/last_price) | ✅ |
| `GET /api/v1/orders?limit=N` | `200` — pagination honoured (BUG-06) | ✅ |
| `GET /api/v1/orders/duplicates` | `200` — duplicate analysis | ✅ |
| `GET /api/v1/orders/history` | `200` — **MySQL-backed** (`total_in_db` grows as the engine runs) | ✅ |
| `POST /api/v1/engine/start` → `stop` | `200 RUNNING` → generates ~20 orders/s → `200 STOPPED` | ✅ |
| WebSocket `:8093` | reachable | ✅ |

---

## §6 — trading-engine-ui (README Module 3)

Same as the CI `UI Build Check (Next.js 15)` job:

| Step | Result | ✅ |
|---|---|:--:|
| `npx tsc --noEmit` | exit 0 (no type errors) | ✅ |
| `npm run build` (Next.js 15) | BUILD SUCCESS, 4 static pages generated | ✅ |

---

## §7 — Repo conventions (README "Repo Conventions")

| Claim | Observed (GitHub API) | ✅ |
|---|---|:--:|
| `main` PR-only, admin-enforced | `enforce_admins: true`, PR required | ✅ |
| 2 required CI checks | `Java Tests` · `UI Build Check (Next.js 15)` | ✅ |
| force-push & deletion disabled | both disabled | ✅ |
| local / origin / GitHub aligned | all `67afe59` (0 / 0 divergence) | ✅ |
| latest `main` CI | green | ✅ |

---

## Notes

- `JdbcPaymentRepository` runs on **H2 in MySQL-compatibility mode** by default, so the ACID/transaction behaviour is real SQL with zero external setup; it is driver-agnostic (`java.sql` only).
- The `422` response shows a blank HTTP reason-phrase — the JDK `HttpServer` has no built-in phrase for 422; the numeric code is correct and is what clients consume.
- Known non-blocking items (documented, accepted): response amount serialization differs cosmetically on idempotent replay; in-memory async-settlement job state; no account-creation API. None affect any contract above.
