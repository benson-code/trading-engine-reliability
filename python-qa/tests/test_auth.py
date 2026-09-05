"""X-API-Key authentication on the payment endpoints.

Skipped when the target has no key configured — an unauthenticated demo server
would make every one of these pass for the wrong reason (401 never happens
because auth is off), which is worse than not running them.
"""

import pytest


@pytest.fixture(autouse=True)
def _requires_auth(target):
    if not target.auth_enabled:
        pytest.skip("target has no API key configured (PAYMENT_API_KEY unset)")


@pytest.mark.smoke
@pytest.mark.auth
def test_create_without_key_is_401(unauthenticated, fresh_user):
    r = unauthenticated.create_payment(user_id=fresh_user)

    assert r.status_code == 401, r.text
    assert r.json()["error"] == "UNAUTHORIZED"


@pytest.mark.auth
def test_status_without_key_is_401(unauthenticated):
    """Auth must cover reads too — a job id leaks payment state otherwise."""
    r = unauthenticated.job_status("JOB_1")
    assert r.status_code == 401, r.text


@pytest.mark.auth
@pytest.mark.parametrize(
    "bad_key",
    ["", "wrong-key", "TEST-KEY", "wrong key", "null"],
    ids=["empty", "wrong", "case-flipped", "internal-space", "literal-null"],
)
# A whitespace-only value ("   ") is deliberately absent: requests refuses to
# put it on the wire (InvalidHeader), so the server never sees it and the case
# would only be testing the HTTP client. "wrong key" keeps the space while
# staying a transmittable header value.
def test_invalid_key_is_401(target, fresh_user, bad_key):
    from conftest import ApiClient

    client = ApiClient(target.base_url, api_key=bad_key)
    try:
        r = client.create_payment(user_id=fresh_user)
    finally:
        client.close()

    assert r.status_code == 401, f"key {bad_key!r} was accepted: {r.text}"


@pytest.mark.auth
def test_key_prefix_is_not_accepted(target, fresh_user):
    """Guards against a comparison that matches on prefix instead of the whole key."""
    from conftest import ApiClient

    client = ApiClient(target.base_url, api_key=target.api_key[:-1])
    try:
        r = client.create_payment(user_id=fresh_user)
    finally:
        client.close()

    assert r.status_code == 401, r.text


@pytest.mark.auth
def test_rejected_request_is_not_processed(api, unauthenticated, fresh_user):
    """A 401 must be a rejection, not a rejection *after* doing the work.

    Send a payment unauthenticated, then send the same idempotency key with a
    valid key. If the first had been processed, the second would replay it and
    answer 200; a genuine 202 proves nothing happened the first time.
    """
    key = f"idem-auth-{fresh_user}"

    rejected = unauthenticated.create_payment(user_id=fresh_user, idempotency_key=key)
    assert rejected.status_code == 401

    authorized = api.create_payment(user_id=fresh_user, idempotency_key=key)
    assert authorized.status_code == 202, (
        f"the unauthenticated request was processed anyway: {authorized.text}")
