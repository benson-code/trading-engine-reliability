"""Input validation and boundary values on POST /payments.

Money bugs live at the boundaries. Each expectation below was confirmed against
a running instance before being written down, not inferred from the source.

The distinct error codes matter as much as the statuses: a client retrying on
INVALID_PRECISION would loop forever, while retrying on INSUFFICIENT_BALANCE
after a top-up is correct. Collapsing them into a generic 400 would erase that.
"""

import pytest

# 8 decimal places is the DECIMAL(18,8) column limit, so 8 must pass and 9 must
# fail. Trailing zeros are stripped before the check, so 1.10000000000 is 1.1.
MAX_SCALE_OK = "1.12345678"
MAX_SCALE_OVER = "1.123456789"


@pytest.mark.validation
@pytest.mark.parametrize("amount", [-5, 0, -0.00000001])
def test_non_positive_amount_is_rejected(api, fresh_user, amount):
    r = api.create_payment(user_id=fresh_user, amount=amount)

    assert r.status_code == 400, r.text
    assert r.json()["error"] == "INVALID_AMOUNT"


@pytest.mark.validation
def test_amount_at_precision_limit_is_accepted(api, fresh_user):
    """The boundary itself must pass — an off-by-one here rejects valid money."""
    r = api.create_payment(user_id=fresh_user, amount=float(MAX_SCALE_OK))
    assert r.status_code == 202, r.text


@pytest.mark.validation
def test_amount_past_precision_limit_is_rejected(api, fresh_user):
    """Rejecting beats silent DB truncation: truncation loses the customer's money."""
    r = api.create_payment(user_id=fresh_user, amount=float(MAX_SCALE_OVER))

    assert r.status_code == 400, r.text
    assert r.json()["error"] == "INVALID_PRECISION"


@pytest.mark.validation
def test_trailing_zeros_do_not_count_towards_precision(api, fresh_user):
    """100.500000000 is really 100.5 — 9 written digits, 1 significant one."""
    r = api.create_payment(user_id=fresh_user, amount=100.500000000)
    assert r.status_code == 202, r.text


@pytest.mark.validation
@pytest.mark.parametrize("field", ["user_id", "currency", "idempotency_key"])
def test_missing_required_field_is_rejected(api, fresh_user, field):
    # Built as one dict so `field` can override user_id without colliding with
    # it as a duplicate keyword argument.
    r = api.create_payment(**{"user_id": fresh_user, field: None})

    assert r.status_code == 400, r.text
    assert r.json()["error"] == "VALIDATION_ERROR"


@pytest.mark.validation
@pytest.mark.parametrize("field", ["user_id", "currency", "idempotency_key"])
def test_blank_required_field_is_rejected(api, fresh_user, field):
    """Blank is not the same input as missing, and is far likelier from a UI."""
    r = api.create_payment(**{"user_id": fresh_user, field: "   "})

    assert r.status_code == 400, r.text
    assert r.json()["error"] == "VALIDATION_ERROR"


@pytest.mark.validation
@pytest.mark.parametrize(
    "field,limit",
    [("idempotency_key", 100), ("user_id", 50), ("order_id", 50), ("currency", 10)],
)
def test_field_length_boundaries(api, fresh_user, field, limit):
    """At the limit -> accepted; one over -> 400, never a 5xx from SQL truncation.

    currency is the exception: any value at the length limit is still not the
    account's currency, so it is checked for 'not a 5xx' rather than for 202.
    """
    at_limit = api.create_payment(**{"user_id": fresh_user, field: "A" * limit})
    assert at_limit.status_code < 500, f"{field} at limit {limit}: {at_limit.text}"
    if field != "currency":
        assert at_limit.status_code == 202, at_limit.text

    over = api.create_payment(**{"user_id": fresh_user, field: "A" * (limit + 1)})
    assert over.status_code == 400, f"{field} over limit {limit}: {over.text}"
    assert over.json()["error"] == "VALIDATION_ERROR"


@pytest.mark.validation
def test_malformed_json_is_400_not_500(api):
    r = api.post("/api/v1/payments", data="{not json")

    assert r.status_code == 400, r.text
    assert r.json()["error"] == "BAD_REQUEST"


@pytest.mark.validation
def test_empty_body_is_400_not_500(api):
    r = api.post("/api/v1/payments", data="")
    assert r.status_code == 400, r.text


@pytest.mark.validation
def test_currency_mismatch_is_422(api, fresh_user):
    """Paying EUR out of a USDT account is a semantic conflict, not bad syntax.

    422 rather than 400 is what tells the client 'the request was understood and
    is still wrong' — retrying the identical body will never succeed.
    """
    r = api.create_payment(user_id=fresh_user, currency="EUR")

    assert r.status_code == 422, r.text
    assert r.json()["error"] == "CURRENCY_MISMATCH"


@pytest.mark.validation
def test_insufficient_balance_is_402(api, fresh_user):
    """Over the 1,000,000 default balance. 402 is retryable after a top-up,
    which is why it must not be flattened into a 400."""
    r = api.create_payment(user_id=fresh_user, amount=2_000_000)

    assert r.status_code == 402, r.text
    assert r.json()["error"] == "INSUFFICIENT_BALANCE"


@pytest.mark.validation
def test_rejected_payment_does_not_partially_debit(api, fresh_user):
    """A failed debit must leave the balance whole.

    Proven behaviourally: after a rejected over-balance payment, a payment for
    the full default balance still succeeds. If the rejection had debited
    anything, this second call would be short and return 402.
    """
    over = api.create_payment(user_id=fresh_user, amount=2_000_000)
    assert over.status_code == 402, over.text

    full = api.create_payment(user_id=fresh_user, amount=1_000_000)
    assert full.status_code == 202, (
        f"balance was touched by the rejected payment: {full.status_code} {full.text}")


@pytest.mark.validation
def test_oversized_body_does_not_crash_the_server(target, fresh_user):
    """The handler caps reads at 64 KB (an OOM guard). Past the cap the JSON is
    truncated, so this must surface as a 400 — and the service must stay up.

    Uses its own client, kept out of the session-wide one: the server rejects
    without draining the remaining body, so it drops the connection instead of
    returning it to the keep-alive pool. Poisoning the shared session here would
    surface as an unrelated failure in whichever test ran next.
    """
    from conftest import ApiClient

    client = ApiClient(target.base_url, target.api_key)
    try:
        r = client.create_payment(user_id=fresh_user, order_id="A" * 200_000)
        assert r.status_code == 400, r.text
    finally:
        client.close()

    # A brand-new connection proves the *server* is alive, not merely that the
    # old socket happened to survive.
    probe = ApiClient(target.base_url, target.api_key)
    try:
        assert probe.health().status_code == 200, "server unhealthy after oversized body"
    finally:
        probe.close()
