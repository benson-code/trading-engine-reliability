"""Retry-safety of POST /payments.

The invariant that matters for Fiat is *no double charge*: one idempotency key
must ever produce one payment, however many times the client retries — and
clients on mobile networks retry a lot, because a timeout is indistinguishable
from a failure from where they stand.
"""

import pytest


@pytest.mark.smoke
@pytest.mark.idempotency
def test_replay_returns_the_same_payment(api, fresh_user):
    key = "idem-replay-fixed"
    payload = dict(user_id=fresh_user, idempotency_key=f"{key}-{fresh_user}", amount=25)

    first = api.create_payment(**payload)
    second = api.create_payment(**payload)

    assert first.status_code == 202, first.text
    assert second.status_code == 200, (
        f"a replay must answer 200, not a second 202: {second.status_code} {second.text}")
    assert second.json()["payment_id"] == first.json()["payment_id"]
    assert second.json()["job_id"] == first.json()["job_id"]


@pytest.mark.idempotency
def test_replay_does_not_debit_twice(api, fresh_user):
    """The balance proof, not just the id proof.

    Charge half the balance, replay it 4 more times, then spend the other half.
    That last call only fits if the replays debited nothing — if any one of them
    had charged again, it returns 402.
    """
    half = 500_000
    payload = dict(user_id=fresh_user, amount=half,
                   idempotency_key=f"idem-nodouble-{fresh_user}")

    assert api.create_payment(**payload).status_code == 202
    for _ in range(4):
        assert api.create_payment(**payload).status_code == 200

    remainder = api.create_payment(user_id=fresh_user, amount=half)
    assert remainder.status_code == 202, (
        f"replays debited the account more than once: {remainder.text}")


@pytest.mark.idempotency
def test_different_keys_create_different_payments(api, fresh_user):
    """The other half of the contract — dedup must not over-reach.

    Same order, same amount, different key: two genuine attempts by the user,
    and collapsing them would silently drop a payment.
    """
    common = dict(user_id=fresh_user, order_id="ORD-SAME", amount=7)

    a = api.create_payment(**common, idempotency_key=f"k-a-{fresh_user}")
    b = api.create_payment(**common, idempotency_key=f"k-b-{fresh_user}")

    assert a.status_code == 202 and b.status_code == 202
    assert a.json()["payment_id"] != b.json()["payment_id"]


@pytest.mark.idempotency
def test_replay_with_a_different_amount_returns_the_original(api, fresh_user):
    """A key collision on a *different* body: the stored result wins.

    Documented here because it is a real risk to flag, not a bug to celebrate.
    The service replays the original 25 and ignores the 999 — so a client that
    reuses a key by mistake gets a success response for a payment it did not
    make. Storing a request fingerprint alongside the key and answering 409 on a
    mismatch is the standard fix (Stripe does this); it is out of scope here.
    The test pins today's behaviour so any change is a deliberate one.
    """
    key = f"idem-conflict-{fresh_user}"

    original = api.create_payment(user_id=fresh_user, amount=25, idempotency_key=key)
    conflicting = api.create_payment(user_id=fresh_user, amount=999, idempotency_key=key)

    assert original.status_code == 202
    assert conflicting.status_code == 200
    assert float(conflicting.json()["amount"]) == 25.0, (
        "the replay reflected the new amount — the stored result was overwritten")


@pytest.mark.idempotency
def test_replay_survives_settlement(api, fresh_user):
    """Retries do not stop once the payment has settled — a client that lost the
    response may retry seconds later, after the job is already SUCCESS."""
    payload = dict(user_id=fresh_user, amount=12,
                   idempotency_key=f"idem-settled-{fresh_user}")

    first = api.create_payment(**payload).json()
    api.await_settled(first["job_id"])

    replay = api.create_payment(**payload)
    assert replay.status_code == 200, replay.text
    assert replay.json()["payment_id"] == first["payment_id"]
