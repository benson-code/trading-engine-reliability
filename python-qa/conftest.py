"""Shared fixtures for the Python regression suite against the Payment API.

Two modes, chosen by whether ``--base-url`` (or ``$BASE_URL``) is supplied:

* **hermetic (default)** — the session fixture starts ``payment-api``'s shaded
  jar on an OS-assigned ephemeral port with a per-run API key, then stops it.
  Nothing is shared with a developer's manually started server, so a suite run
  can never be polluted by, or pollute, someone else's state.
* **targeted** — point the suite at an already running instance (a staging
  deploy, or the local jar on :8091) and it will not start or stop anything.

Port 0 is passed to the jar deliberately: the OS binds a free port atomically,
so parallel runs (``-n auto``, or two branches in CI) cannot collide the way a
hardcoded port would.
"""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path

import pytest
import requests

REPO_ROOT = Path(__file__).resolve().parent.parent
JAR = REPO_ROOT / "payment-api" / "target" / "payment-api-qa-framework-1.0.0.jar"

# Main.java prints this line once the server is bound; it carries the real port.
PORT_LINE = re.compile(r"REST API\s*:\s*http://0\.0\.0\.0:(\d+)/api/v1/payments")

STARTUP_TIMEOUT_S = 60.0
DEFAULT_HTTP_TIMEOUT_S = 10.0


# ── options ──────────────────────────────────────────────────────────────────

def pytest_addoption(parser):
    parser.addoption(
        "--base-url", default=os.getenv("BASE_URL"),
        help="Target an already running Payment API instead of starting a jar "
             "(e.g. http://127.0.0.1:8091). Defaults to $BASE_URL.",
    )
    parser.addoption(
        "--api-key", default=os.getenv("PAYMENT_API_KEY"),
        help="X-API-Key for the target. Ignored in hermetic mode, which mints "
             "its own key. Defaults to $PAYMENT_API_KEY.",
    )
    parser.addoption(
        "--repo-mode", default=os.getenv("PAYMENT_REPO", "memory"),
        choices=["memory", "jdbc"],
        help="Repository the hermetic server runs with. 'memory' auto-provisions "
             "any user; 'jdbc' only knows seeded accounts (unknown user -> 404).",
    )


# ── the system under test ────────────────────────────────────────────────────

@dataclass(frozen=True)
class Target:
    base_url: str
    api_key: str | None
    repo_mode: str
    hermetic: bool

    @property
    def auth_enabled(self) -> bool:
        return bool(self.api_key)


@pytest.fixture(scope="session")
def target(request) -> Target:
    external = request.config.getoption("--base-url")
    repo_mode = request.config.getoption("--repo-mode")

    if external:
        yield Target(
            base_url=external.rstrip("/"),
            api_key=request.config.getoption("--api-key"),
            repo_mode=repo_mode,
            hermetic=False,
        )
        return

    if not JAR.exists():
        pytest.fail(
            f"Payment API jar not found at {JAR}.\n"
            f"Build it first:\n"
            f"  cd {REPO_ROOT} && mvn -q package -pl payment-api -am -DskipTests\n"
            f"Or target a running instance:  pytest --base-url http://127.0.0.1:8091",
            pytrace=False,
        )

    # A fresh key per run: a test asserting 401 then proves the header is really
    # being checked, not that some stale ambient key happened to be wrong.
    api_key = f"test-key-{uuid.uuid4()}"
    env = {**os.environ, "PAYMENT_API_KEY": api_key, "PAYMENT_REPO": repo_mode}

    log = tempfile.NamedTemporaryFile(  # noqa: SIM115 - lifetime is the fixture's
        prefix="payment-api-", suffix=".log", mode="w+", delete=False)
    proc = subprocess.Popen(
        ["java", "-jar", str(JAR), "0"],   # 0 -> OS picks a free port
        stdout=log, stderr=subprocess.STDOUT, env=env, cwd=REPO_ROOT,
    )

    try:
        port = _await_bound_port(proc, Path(log.name))
        base_url = f"http://127.0.0.1:{port}"
        _await_health(base_url)
        yield Target(base_url=base_url, api_key=api_key,
                     repo_mode=repo_mode, hermetic=True)
    finally:
        proc.terminate()          # triggers Main's shutdown hook
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        log.close()
        Path(log.name).unlink(missing_ok=True)


def _await_bound_port(proc: subprocess.Popen, log_path: Path) -> int:
    """Poll the server's own startup log for the port the OS actually gave it."""
    deadline = time.monotonic() + STARTUP_TIMEOUT_S
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(
                f"Payment API exited during startup (rc={proc.returncode}).\n"
                f"--- server log ---\n{log_path.read_text()}")
        match = PORT_LINE.search(log_path.read_text())
        if match:
            return int(match.group(1))
        time.sleep(0.1)
    proc.kill()
    raise TimeoutError(
        f"Payment API did not report a bound port within {STARTUP_TIMEOUT_S}s.\n"
        f"--- server log ---\n{log_path.read_text()}")


def _await_health(base_url: str) -> None:
    """The port is bound before the handlers are useful — wait for readiness."""
    deadline = time.monotonic() + 30.0
    last = None
    while time.monotonic() < deadline:
        try:
            r = requests.get(f"{base_url}/api/v1/health", timeout=2)
            if r.status_code == 200 and r.json().get("status") == "UP":
                return
            last = f"HTTP {r.status_code} {r.text[:200]}"
        except requests.RequestException as e:
            last = repr(e)
        time.sleep(0.2)
    raise TimeoutError(f"{base_url} never became healthy. Last: {last}")


# ── HTTP client ──────────────────────────────────────────────────────────────

class ApiClient:
    """Thin wrapper over requests.

    Exists for two reasons a bare ``requests.Session`` does not cover:
    every call carries a timeout (a session default does not exist, and an
    un-timed-out request in CI hangs the job rather than failing it), and the
    API key is attached in one place so tests never repeat the header.
    """

    def __init__(self, base_url: str, api_key: str | None,
                 timeout: float = DEFAULT_HTTP_TIMEOUT_S):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.api_key = api_key
        self.session = self._new_session()

    def _new_session(self) -> requests.Session:
        session = requests.Session()
        if self.api_key:
            session.headers["X-API-Key"] = self.api_key
        session.headers["Content-Type"] = "application/json"
        return session

    def request(self, method: str, path: str, **kw) -> requests.Response:
        kw.setdefault("timeout", self.timeout)
        url = f"{self.base_url}{path}"
        try:
            return self.session.request(method, url, **kw)
        except requests.exceptions.ConnectionError:
            # Retried exactly once, and only for a connection-level failure —
            # never for a response the server actually sent.
            #
            # Why this is needed: the server answers 401 (bad API key) and 400
            # (oversized body) *before* draining the request body, so it closes
            # the exchange with bytes still in flight and the peer sees an RST.
            # A pooled keep-alive connection can also go stale the same way.
            # Both are client-visible races with no response involved.
            #
            # Why one retry is safe here: every payment carries an idempotency
            # key, so a re-send either creates the payment once or replays it.
            # The worst case is a 202 arriving as a 200 — never a double charge.
            # This is a workaround for a real server-side behaviour, not a fix;
            # see README "Findings" for the underlying issue.
            self.session.close()
            self.session = self._new_session()
            return self.session.request(method, url, **kw)

    def get(self, path, **kw):  return self.request("GET", path, **kw)
    def post(self, path, **kw): return self.request("POST", path, **kw)

    # ── domain helpers ───────────────────────────────────────────────────────

    def health(self):
        return self.get("/api/v1/health")

    def create_payment(self, **overrides) -> requests.Response:
        return self.post("/api/v1/payments", json=payment_payload(**overrides))

    def job_status(self, job_id: str) -> requests.Response:
        return self.get(f"/api/v1/payments/{job_id}/status")

    def await_settled(self, job_id: str, timeout: float = 10.0) -> dict:
        """Poll a job until it leaves PENDING.

        Settlement is asynchronous (~50ms), so asserting on the status straight
        after a 202 is a race. Polling to a deadline is the only correct read.
        """
        deadline = time.monotonic() + timeout
        body = {}
        while time.monotonic() < deadline:
            r = self.job_status(job_id)
            assert r.status_code == 200, f"status poll failed: {r.status_code} {r.text}"
            body = r.json()
            if body.get("status") != "PENDING":
                return body
            time.sleep(0.05)
        raise AssertionError(f"job {job_id} still PENDING after {timeout}s: {body}")

    def close(self):
        self.session.close()


def payment_payload(**overrides) -> dict:
    """A valid Fiat payment body. Override any field; pass None to drop it.

    Idempotency keys are unique per call by default — a shared key across
    unrelated tests would make them silently replay each other's payment.
    """
    body = {
        "order_id": f"ORD-{uuid.uuid4().hex[:12]}",
        "user_id": "USER_DEMO",
        "amount": 10.50,
        "currency": "USDT",
        "idempotency_key": f"idem-{uuid.uuid4()}",
    }
    body.update(overrides)
    return {k: v for k, v in body.items() if v is not None}


@pytest.fixture(scope="session")
def api(target: Target) -> ApiClient:
    client = ApiClient(target.base_url, target.api_key)
    yield client
    client.close()


@pytest.fixture(scope="session")
def unauthenticated(target: Target) -> ApiClient:
    """Same target, no API key — for the negative auth cases."""
    client = ApiClient(target.base_url, api_key=None)
    yield client
    client.close()


@pytest.fixture
def fresh_user() -> str:
    """A user id no other test has touched.

    In ``memory`` mode this auto-provisions a 1,000,000 USDT balance on first
    use, which is what the balance and currency cases rely on. Reusing a shared
    user would let one test's debit change another's expected outcome.
    """
    return f"USER_{uuid.uuid4().hex[:12]}"


# ── Trading engine (WebSocket stream) ────────────────────────────────────────

TRADING_JAR = (REPO_ROOT / "trading-engine-simulator" / "target"
               / "trading-engine-simulator-1.0.0.jar")

# java_websocket's getPort() resolves an ephemeral bind, so this line carries the
# real port even when the jar is started with 0.
WS_PORT_LINE = re.compile(r"\[WS\] WebSocket server started on port (\d+)")


@dataclass(frozen=True)
class TradingEngineTarget:
    ws_url: str
    pid: int


@pytest.fixture(scope="session")
def trading_engine() -> TradingEngineTarget:
    """A dedicated trading-engine instance for WebSocket tests.

    **This fixture never generates an order, by design.** The jar starts with the
    engine STOPPED ("the engine does NOT auto-start" — Main.java) and these tests
    never call POST /api/v1/engine/start. That matters because
    ``DBOrderRepository``'s JDBC URL is hardcoded to the shared
    ``binance_test_db``: any instance that runs its engine writes into the same
    ``orders`` table as the long-running service on :8092. A stopped engine opens
    the connection and writes nothing, so this suite leaves that table untouched.
    ``test_stopped_engine_generates_nothing`` asserts that property rather than
    assuming it.

    Both ports are passed as 0 so the OS assigns them — no collision with the
    running instance on :8092/:8093, and no probe-close-rebind race. The REST port
    is deliberately not discovered: every assertion here is made from the stream
    itself, so the tests need no second channel.
    """
    if not TRADING_JAR.exists():
        pytest.skip(
            f"trading-engine jar not built: {TRADING_JAR}\n"
            f"  cd {REPO_ROOT} && mvn -q package -pl trading-engine-simulator -DskipTests")

    log = tempfile.NamedTemporaryFile(  # noqa: SIM115 - lifetime is the fixture's
        prefix="trading-engine-", suffix=".log", mode="w+", delete=False)
    proc = subprocess.Popen(
        ["java", "-jar", str(TRADING_JAR), "0", "0"],   # restPort, wsPort
        stdout=log, stderr=subprocess.STDOUT, cwd=REPO_ROOT,
    )

    try:
        deadline = time.monotonic() + STARTUP_TIMEOUT_S
        port = None
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError(
                    f"trading engine exited during startup (rc={proc.returncode}).\n"
                    f"--- server log ---\n{Path(log.name).read_text()}")
            match = WS_PORT_LINE.search(Path(log.name).read_text())
            if match:
                port = int(match.group(1))
                break
            time.sleep(0.1)
        if port is None:
            raise TimeoutError(
                f"no WebSocket port reported within {STARTUP_TIMEOUT_S}s.\n"
                f"--- server log ---\n{Path(log.name).read_text()}")

        yield TradingEngineTarget(ws_url=f"ws://127.0.0.1:{port}", pid=proc.pid)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)
        log.close()
        Path(log.name).unlink(missing_ok=True)


def pytest_report_header(config):
    external = config.getoption("--base-url")
    mode = f"targeted ({external})" if external else "hermetic (jar on ephemeral port)"
    return f"payment-api: {mode}, repo={config.getoption('--repo-mode')}"
