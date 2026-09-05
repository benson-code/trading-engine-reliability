"""The response contract of POST /payments and GET /payments/{jobId}/status.

Schema-level assertions, not field spot-checks: ``additionalProperties: false``
means a field quietly added or renamed by the backend fails here rather than in
a mobile client three sprints later.
"""

import pytest
from jsonschema import validate

ACCEPTED_SCHEMA = {
    "type": "object",
    "required": ["payment_id", "order_id", "status", "amount", "message", "job_id"],
    "additionalProperties": False,
    "properties": {
        "payment_id": {"type": "string", "minLength": 1},
        "order_id":   {"type": "string", "minLength": 1},
        "status":     {"enum": ["PENDING", "SUCCESS", "FAILED"]},
        "amount":     {"type": "number", "exclusiveMinimum": 0},
        "message":    {"type": "string"},
        "job_id":     {"type": "string", "minLength": 1},
    },
}

STATUS_SCHEMA = {
    "type": "object",
    "required": ["payment_id", "job_id", "status", "message"],
    "additionalProperties": False,
    "properties": {
        "payment_id": {"type": "string", "minLength": 1},
        "job_id":     {"type": "string", "minLength": 1},
        "status":     {"enum": ["PENDING", "SUCCESS", "FAILED"]},
        "message":    {"type": "string"},
    },
}

ERROR_SCHEMA = {
    "type": "object",
    "required": ["error"],
    "properties": {"error": {"type": "string", "minLength": 1}},
}


@pytest.mark.smoke
@pytest.mark.contract
def test_create_payment_returns_202_with_full_contract(api, fresh_user):
    r = api.create_payment(user_id=fresh_user, order_id="ORD-CONTRACT-1")

    assert r.status_code == 202, r.text
    assert r.headers["Content-Type"].startswith("application/json")

    body = r.json()
    validate(body, ACCEPTED_SCHEMA)
    assert body["status"] == "PENDING"
    assert body["order_id"] == "ORD-CONTRACT-1"
    assert float(body["amount"]) == 10.50


@pytest.mark.contract
def test_payment_settles_asynchronously(api, fresh_user):
    """202 means accepted, not completed — the job must reach SUCCESS on its own."""
    created = api.create_payment(user_id=fresh_user).json()

    settled = api.await_settled(created["job_id"])

    validate(settled, STATUS_SCHEMA)
    assert settled["status"] == "SUCCESS"
    assert settled["payment_id"] == created["payment_id"]
    assert settled["job_id"] == created["job_id"]


@pytest.mark.contract
def test_unknown_job_id_is_404_not_500(api):
    r = api.job_status("JOB_DOES_NOT_EXIST")

    assert r.status_code == 404, r.text
    body = r.json()
    validate(body, ERROR_SCHEMA)
    assert body["error"] == "JOB_NOT_FOUND"


@pytest.mark.contract
def test_wrong_method_on_collection_is_405(api):
    """GET /payments has no listing endpoint; it must refuse, not leak data."""
    r = api.get("/api/v1/payments")
    assert r.status_code == 405, r.text


@pytest.mark.contract
def test_cors_preflight_is_allowed(api):
    """The web client is cross-origin — a broken preflight breaks the whole UI."""
    r = api.request("OPTIONS", "/api/v1/payments")

    assert r.status_code == 204, r.text
    allow_headers = r.headers.get("Access-Control-Allow-Headers", "")
    assert "X-API-Key" in allow_headers, allow_headers
    assert r.headers.get("Access-Control-Allow-Origin") == "*"
