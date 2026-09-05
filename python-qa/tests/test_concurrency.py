"""Behaviour under simultaneous requests.

A retry storm is the realistic shape of this failure: a mobile client times out,
the user taps Pay again, and an SDK retry lands at the same moment — three
requests carrying one idempotency key, in flight together. Sequential replay
tests cannot catch a check-then-act race; only overlapping requests can.

Each thread gets its own ApiClient because ``requests.Session`` is not
documented as thread-safe, and a client-side race would be indistinguishable
from the server-side one under test.
"""

import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from conftest import ApiClient, payment_payload

THREADS = 30


def _fire_together(target, payload_factory, threads=THREADS):
    """Release ``threads`` requests at one instant to maximise contention."""
    gate = threading.Barrier(threads)
    results = []
    lock = threading.Lock()

    def worker(i):
        client = ApiClient(target.base_url, target.api_key)
        try:
            body = payload_factory(i)
            gate.wait(timeout=30)
            r = client.post("/api/v1/payments", json=body)
            with lock:
                results.append((r.status_code, r.json()))
        finally:
            client.close()

    with ThreadPoolExecutor(max_workers=threads) as pool:
        for f in [pool.submit(worker, i) for i in range(threads)]:
            f.result()   # re-raises anything a worker threw

    return results


@pytest.mark.concurrency
@pytest.mark.idempotency
def test_concurrent_retries_of_one_key_create_one_payment(target, fresh_user):
    key = f"idem-race-{fresh_user}"
    body = payment_payload(user_id=fresh_user, amount=100, idempotency_key=key)

    results = _fire_together(target, lambda _: dict(body))

    statuses = [s for s, _ in results]
    payment_ids = {b["payment_id"] for _, b in results}

    assert set(statuses) <= {200, 202}, f"unexpected statuses: {sorted(set(statuses))}"
    assert 202 in statuses, "no request was treated as the original create"
    assert len(payment_ids) == 1, (
        f"{THREADS} concurrent retries of one key produced {len(payment_ids)} "
        f"payments — a double charge: {payment_ids}")

    # Deliberately NOT asserted: that exactly one response is 202. The 200-vs-202
    # split comes from a separate `isAlreadyProcessed` read taken before the
    # create, so under contention several threads can legitimately observe
    # "not yet processed" and all answer 202. That is a cosmetic race in the
    # status code only — the payment itself is still created once, which is the
    # invariant above. Asserting one-202 here would produce a flaky test that
    # blames the service for something it never promised.


@pytest.mark.concurrency
@pytest.mark.idempotency
def test_concurrent_retries_debit_exactly_once(target, api, fresh_user):
    """The money-level proof of the test above.

    Race 30 retries of a payment for the full 1,000,000 balance. If any two of
    them debited, the account is overdrawn — which the follow-up 1-unit payment
    detects as a 402. Balance is asserted behaviourally because the API exposes
    no balance endpoint; a debit is only observable through a later payment.
    """
    key = f"idem-race-balance-{fresh_user}"
    body = payment_payload(user_id=fresh_user, amount=1_000_000, idempotency_key=key)

    results = _fire_together(target, lambda _: dict(body))
    assert {s for s, _ in results} <= {200, 202}, results

    overdraw = api.create_payment(user_id=fresh_user, amount=1)
    assert overdraw.status_code == 402, (
        "account had balance left after a full-balance payment, or was debited "
        f"more than once: {overdraw.status_code} {overdraw.text}")


@pytest.mark.concurrency
def test_distinct_keys_under_load_all_succeed(target, fresh_user):
    """The service must not drop or merge unrelated concurrent payments.

    30 different keys, one account, fired together: every one is a real payment
    and every payment_id must be unique.
    """
    results = _fire_together(
        target,
        lambda i: payment_payload(user_id=fresh_user, amount=10,
                                  idempotency_key=f"idem-parallel-{fresh_user}-{i}"),
    )

    assert all(s == 202 for s, _ in results), sorted({s for s, _ in results})
    payment_ids = {b["payment_id"] for _, b in results}
    assert len(payment_ids) == THREADS, (
        f"expected {THREADS} distinct payments, got {len(payment_ids)}")


@pytest.mark.concurrency
@pytest.mark.slow
def test_job_status_is_pollable_while_settling(api, fresh_user):
    """A client polls immediately after accept — the job must be visible at once.

    Registration and settlement are separate steps; a job that is not queryable
    until it settles would 404 on the client's first poll and look like a lost
    payment.
    """
    created = api.create_payment(user_id=fresh_user, amount=5).json()

    immediate = api.job_status(created["job_id"])
    assert immediate.status_code == 200, (
        f"job not visible right after accept: {immediate.status_code} {immediate.text}")
    assert immediate.json()["status"] in {"PENDING", "SUCCESS"}

    assert api.await_settled(created["job_id"])["status"] == "SUCCESS"
