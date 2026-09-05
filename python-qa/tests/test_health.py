"""Release gate: is the service up and answering at all?

Everything downstream assumes this passes, so it runs first and is the one
suite worth wiring into a deploy's readiness check.
"""

import pytest


@pytest.mark.smoke
def test_health_reports_up(api):
    r = api.health()
    assert r.status_code == 200, r.text
    assert r.json() == {"status": "UP"}


@pytest.mark.smoke
def test_health_is_not_behind_auth(unauthenticated):
    """A readiness probe that needs a credential is useless to a load balancer.

    The server exempts /health from the X-API-Key check on purpose; this pins
    that decision so a future 'secure everything' change cannot silently break
    orchestration.
    """
    r = unauthenticated.health()
    assert r.status_code == 200, r.text


@pytest.mark.smoke
def test_health_responds_quickly(api):
    """Guards against a health check that technically answers but has stalled.

    2s is deliberately loose — this is a hang detector, not a latency SLO.
    Latency belongs to the k6 suite, which measures it under load.
    """
    import time

    start = time.monotonic()
    r = api.health()
    elapsed = time.monotonic() - start

    assert r.status_code == 200
    assert elapsed < 2.0, f"/health took {elapsed:.2f}s"
