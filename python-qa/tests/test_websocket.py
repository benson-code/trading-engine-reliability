"""The trading engine's WebSocket stream (:8093 on the deployed service).

This path had no coverage of any kind, and no client had connected to the live
server since 2026-08-01 — so nothing, human or automated, had exercised it.

Every test here runs against a dedicated instance with the engine STOPPED (see
the ``trading_engine`` fixture). That is not a limitation to work around: the
engine's JDBC URL is hardcoded to the shared ``binance_test_db``, so an instance
that generates orders writes into the same table the long-running service is
filling. Keeping the engine stopped is what makes this suite safe to run at any
time, and ``test_stopped_engine_generates_nothing`` proves it held.

What that leaves untested is stated in test_engine_status_is_never_broadcast and
in the README's "Known gaps" — not silently omitted.
"""

import json
import time

import pytest
from jsonschema import validate
from websockets.sync.client import connect

pytestmark = pytest.mark.websocket

CONNECT_TIMEOUT = 10.0
# Stats are broadcast on a 1s schedule; 5s is four missed ticks, i.e. broken.
RECV_TIMEOUT = 5.0

ENVELOPE_SCHEMA = {
    "type": "object",
    "required": ["type", "data"],
    "additionalProperties": False,
    "properties": {
        "type": {"enum": ["STATS_UPDATE", "ORDER_CREATED", "ENGINE_STATUS"]},
        "data": {"type": "object"},
    },
}

STATS_SCHEMA = {
    "type": "object",
    "required": [
        "status", "total_generated", "unique_orders", "total_orders",
        "duplicate_count", "cache_size", "cache_hit_count", "cache_miss_count",
        "cache_hit_rate", "has_duplicates", "buy_count", "sell_count", "last_price",
    ],
    "additionalProperties": False,
    "properties": {
        "status":          {"enum": ["RUNNING", "STOPPED"]},
        "total_generated": {"type": "integer", "minimum": 0},
        "unique_orders":   {"type": "integer", "minimum": 0},
        "total_orders":    {"type": "integer", "minimum": 0},
        "duplicate_count": {"type": "integer", "minimum": 0},
        "cache_size":      {"type": "integer", "minimum": 0},
        "cache_hit_count": {"type": "integer", "minimum": 0},
        "cache_miss_count": {"type": "integer", "minimum": 0},
        "cache_hit_rate":  {"type": "number", "minimum": 0, "maximum": 1},
        "has_duplicates":  {"type": "boolean"},
        "buy_count":       {"type": "integer", "minimum": 0},
        "sell_count":      {"type": "integer", "minimum": 0},
        "last_price":      {"type": "number"},
    },
}


def collect(ws, count, timeout=RECV_TIMEOUT):
    """Read ``count`` decoded messages, failing loudly rather than hanging."""
    out = []
    for _ in range(count):
        out.append(json.loads(ws.recv(timeout=timeout)))
    return out


@pytest.mark.smoke
def test_client_can_connect_and_receives_a_message(trading_engine):
    """The bare minimum nobody had verified: the server accepts a client at all."""
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        message = json.loads(ws.recv(timeout=RECV_TIMEOUT))

    validate(message, ENVELOPE_SCHEMA)


def test_message_envelope_is_type_plus_data(trading_engine):
    """Every broadcast is wrapped as {type, data} — `additionalProperties: false`
    means a field added to the envelope breaks here, not in the browser."""
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        for message in collect(ws, 2):
            validate(message, ENVELOPE_SCHEMA)


def test_stats_payload_matches_its_contract(trading_engine):
    """The frontend renders these 13 fields; a rename silently blanks a widget."""
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        stats = next(m for m in collect(ws, 3) if m["type"] == "STATS_UPDATE")

    validate(stats["data"], STATS_SCHEMA)


@pytest.mark.slow
def test_stats_arrive_about_once_per_second(trading_engine):
    """Cadence is the contract — the UI's 'live' feel depends on it.

    Bounds are deliberately wide (0.5s–2.5s). This catches a scheduler that has
    died or is firing in a tight loop, without turning ordinary GC jitter on a
    2-core box into a red build.
    """
    stamps = []
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        for _ in range(4):
            ws.recv(timeout=RECV_TIMEOUT)
            stamps.append(time.monotonic())

    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    assert all(0.5 <= g <= 2.5 for g in gaps), f"irregular broadcast cadence: {gaps}"


@pytest.mark.slow
def test_stopped_engine_generates_nothing(trading_engine):
    """The safety property this whole suite depends on.

    A running engine persists every order into the shared ``binance_test_db``.
    This asserts the fixture's instance stays STOPPED and its order counters
    never move — so the suite provably adds no rows to that table. If this ever
    fails, the suite is polluting real data and must not be run.
    """
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        samples = [m["data"] for m in collect(ws, 3) if m["type"] == "STATS_UPDATE"]

    assert samples, "no STATS_UPDATE received"
    for s in samples:
        assert s["status"] == "STOPPED", f"engine is running — it is writing to MySQL: {s}"
        assert s["total_generated"] == 0, f"orders were generated: {s}"
        assert s["buy_count"] == 0 and s["sell_count"] == 0, s


def test_every_connected_client_gets_the_same_broadcast(trading_engine):
    """Fan-out: `broadcast` iterates all sockets, so three clients must agree.

    A client that connects late enough to miss a tick would see a different
    counter, so the comparison is on the message the three receive together.
    """
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as a, \
         connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as b, \
         connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as c:
        # Discard one tick each: whichever clients joined mid-interval are then
        # aligned on the same subsequent broadcast.
        for ws in (a, b, c):
            ws.recv(timeout=RECV_TIMEOUT)
        received = [json.loads(ws.recv(timeout=RECV_TIMEOUT)) for ws in (a, b, c)]

    assert received[0] == received[1] == received[2], (
        f"clients received divergent broadcasts: {received}")


@pytest.mark.slow
def test_one_client_leaving_does_not_disturb_the_others(trading_engine):
    """`onClose` removes the socket from the queue while `broadcast` iterates it.

    If removal and iteration raced, the surviving client would stop receiving.
    This is the realistic case — browser tabs close constantly.
    """
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as survivor:
        leaver = connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT)
        survivor.recv(timeout=RECV_TIMEOUT)
        leaver.recv(timeout=RECV_TIMEOUT)
        leaver.close()

        # Two further ticks after the departure, still flowing.
        for _ in range(2):
            message = json.loads(survivor.recv(timeout=RECV_TIMEOUT))
            validate(message, ENVELOPE_SCHEMA)


def test_server_ignores_inbound_messages(trading_engine):
    """The stream is push-only (`onMessage` is a no-op).

    A client that sends anything — a stray ping, a subscribe frame copied from
    another exchange's API — must not be disconnected or crash the handler.
    """
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        ws.recv(timeout=RECV_TIMEOUT)
        ws.send(json.dumps({"action": "subscribe", "channel": "orders"}))
        ws.send("not even json")

        message = json.loads(ws.recv(timeout=RECV_TIMEOUT))
        validate(message, ENVELOPE_SCHEMA)


@pytest.mark.slow
def test_engine_status_is_never_broadcast(trading_engine):
    """Documents a real gap: ENGINE_STATUS is specified but never sent.

    ``TradingWebSocketServer``'s javadoc lists three message types —
    ORDER_CREATED, STATS_UPDATE and ENGINE_STATUS. Only the first two are ever
    passed to ``broadcast``; ``TradingApiServer`` is not even constructed with a
    reference to the WebSocket server, so start/stop *cannot* notify clients.

    Consequence: a UI that opened this stream to learn when the engine started
    or stopped would wait forever. It only finds out by polling
    ``GET /api/v1/status``, or by noticing the ``status`` field inside the
    once-a-second STATS_UPDATE — which is what the existing frontend must rely on.

    This test pins the current behaviour so implementing ENGINE_STATUS is a
    deliberate change that has to update this assertion.
    """
    with connect(trading_engine.ws_url, open_timeout=CONNECT_TIMEOUT) as ws:
        types = {m["type"] for m in collect(ws, 4)}

    assert types == {"STATS_UPDATE"}, f"unexpected message types on a stopped engine: {types}"
    assert "ENGINE_STATUS" not in types
