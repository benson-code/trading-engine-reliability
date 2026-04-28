# Payment API QA Framework

Full-cycle automated testing framework simulating **Binance payment backend QA** — covering API testing, database verification, idempotency validation, and ACID compliance.

![CI](https://github.com/benson-code/payment-api-qa-framework/actions/workflows/ci.yml/badge.svg)

---

## Test Coverage

| Layer | Scenarios | Tools |
|---|---|---|
| Unit Tests | Validation logic, idempotency service logic | JUnit 5, Mockito |
| API Tests | Happy path, negative cases, async 202 flow | RestAssured, WireMock |
| DB Tests | Balance deduction, ACID rollback, idempotency constraint | JDBC, H2 |
| Integration | Full flow: API → async processing → DB verification | WireMock + H2 |

**Total: 11 test cases across 4 layers**

---

## Architecture

```
┌──────────────────────────────────────────────────┐
│                   Test Suite                      │
│                                                   │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────┐  │
│  │ Unit Tests  │  │  API Tests   │  │DB Tests │  │
│  │(Mockito)    │  │(RestAssured) │  │(JDBC)   │  │
│  └──────┬──────┘  └──────┬───────┘  └────┬────┘  │
│         │                │               │        │
│    PaymentService    WireMock          H2 DB      │
│    (mocked repo)    (mock API)      (in-memory)   │
│                                                   │
│  ┌─────────────────────────────────────────────┐  │
│  │          Integration Tests                  │  │
│  │         WireMock  +  H2 DB                  │  │
│  └─────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

---

## Key Test Scenarios

### 1. Idempotency — Duplicate Payment Prevention
Simulates client retry: same `Idempotency-Key` header → API returns same `payment_id`, DB `UNIQUE` constraint blocks second insert → balance deducted exactly once.

### 2. ACID — Atomicity & Rollback
Payment exceeds balance → `deductBalance()` throws → explicit `conn.rollback()` → balance unchanged. Verifies DB-level atomicity.

### 3. Async Payment Flow (HTTP 202)
`POST /payments` → `202 Accepted` + `job_id` → `GET /payments/{jobId}/status` → `SUCCESS`. Correct pattern for async payment APIs (vs incorrect 201).

### 4. Unit Validation
`PaymentService` validates amount > 0, non-blank idempotency key, non-blank userId — before touching any repository or network.

---

## Tech Stack

| Tool | Purpose |
|---|---|
| Java 17 | Main language |
| Maven | Build & dependency management |
| JUnit 5 | Test framework |
| Mockito | Mocking for unit tests |
| RestAssured | HTTP API assertions |
| WireMock | Mock HTTP server |
| H2 | In-memory database (MySQL mode) |
| Allure | Test report generation |
| GitHub Actions | CI/CD |

---

## How to Run

```bash
# Clone
git clone https://github.com/benson-code/payment-api-qa-framework.git
cd payment-api-qa-framework

# Run all tests
mvn test

# Generate and open Allure report
mvn allure:report
open target/site/allure-maven-plugin/index.html
```

---

## Project Structure

```
src/
├── main/java/com/binance/payment/
│   ├── model/
│   │   ├── PaymentRequest.java       # Request POJO (orderId, amount, idempotencyKey)
│   │   └── PaymentResponse.java      # Response POJO (paymentId, status, jobId)
│   └── service/
│       ├── PaymentRepository.java    # Repository interface
│       └── PaymentService.java       # Core logic: validation + idempotency
│
└── test/java/com/binance/payment/
    ├── unit/
    │   └── PaymentServiceTest.java   # JUnit 5 + Mockito unit tests
    ├── api/
    │   ├── PaymentAPITest.java       # RestAssured + WireMock API tests
    │   └── IdempotencyTest.java      # WireMock Scenario-based idempotency tests
    ├── db/
    │   └── BalanceVerificationTest.java  # JDBC + H2 database tests
    ├── integration/
    │   └── PaymentFlowTest.java      # End-to-end flow: WireMock + H2
    └── util/
        └── DatabaseUtil.java         # H2 connection & schema helper
```
