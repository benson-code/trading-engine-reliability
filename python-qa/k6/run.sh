#!/usr/bin/env bash
# Run a k6 script against a throwaway instance of the service it targets.
#
# The service is chosen by script name: ws_*.js -> trading engine, everything
# else -> payment API. Either way the server is started here on OS-assigned
# ports and stopped on exit, so a run never touches a server someone else is
# using and two runs can never collide on a port.
#
#   ./k6/run.sh smoke.js
#   ./k6/run.sh load_payments.js
#   RATE=50 DURATION=2m ./k6/run.sh load_payments.js
#   ./k6/run.sh ws_stream.js
#   CLIENTS=50 HOLD_MS=30000 ./k6/run.sh ws_stream.js
#
# To hit an already running server instead (staging, or the local :8091):
#   BASE_URL=http://127.0.0.1:8091 API_KEY=... ./k6/run.sh smoke.js
#   WS_URL=ws://127.0.0.1:8093 ./k6/run.sh ws_stream.js
#
# SAFETY: the trading engine is started STOPPED and this script never calls
# /api/v1/engine/start. Its JDBC URL is hardcoded to the shared binance_test_db,
# so a running engine would write into the same orders table as the deployed
# service. Stopped, it writes nothing. ws_stream.js asserts that it stayed so.
#
# Everything runs under `nice -n 15`: on a shared box the load generator must
# lose any CPU contest against whatever else is running, not win it.

set -euo pipefail

SCRIPT="${1:?usage: run.sh <script.js>   (e.g. smoke.js, ws_stream.js)}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
K6="${K6:-$HOME/.local/bin/k6}"

PAYMENT_JAR="$REPO_ROOT/payment-api/target/payment-api-qa-framework-1.0.0.jar"
TRADING_JAR="$REPO_ROOT/trading-engine-simulator/target/trading-engine-simulator-1.0.0.jar"

[[ -f "$HERE/$SCRIPT" ]] || { echo "no such script: $HERE/$SCRIPT" >&2; exit 1; }
command -v "$K6" >/dev/null 2>&1 || { echo "k6 not found at $K6" >&2; exit 1; }

case "$SCRIPT" in
  ws_*) SERVICE=trading ;;
  *)    SERVICE=payment ;;
esac

RUN_ID="$(date +%s)"
PID=""
LOG=""
K6_ENV=(-e "RUN_ID=$RUN_ID")

cleanup() {
  if [[ -n "$PID" ]] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  [[ -n "$LOG" ]] && rm -f "$LOG"
}
trap cleanup EXIT INT TERM

# Waits for a regex to appear in the server log, capturing group 1 as the port.
await_port() {
  local pattern="$1" port=""
  for _ in $(seq 1 150); do   # 150 * 0.2s = 30s
    if ! kill -0 "$PID" 2>/dev/null; then
      echo "server died during startup:" >&2; cat "$LOG" >&2; exit 1
    fi
    port="$(grep -oP "$pattern" "$LOG" || true)"
    [[ -n "$port" ]] && { echo "$port"; return 0; }
    sleep 0.2
  done
  echo "server never reported a port:" >&2; cat "$LOG" >&2; exit 1
}

if [[ "$SERVICE" == "trading" ]]; then
  if [[ -n "${WS_URL:-}" ]]; then
    echo "==> targeting existing WebSocket server: $WS_URL (not starting anything)"
    K6_ENV+=(-e "WS_URL=$WS_URL")
  else
    [[ -f "$TRADING_JAR" ]] || {
      echo "jar not found: $TRADING_JAR" >&2
      echo "build it:  cd $REPO_ROOT && mvn -q package -pl trading-engine-simulator -DskipTests" >&2
      exit 1
    }
    LOG="$(mktemp -t trading-engine-k6-XXXXXX.log)"
    # Both ports 0 -> assigned by the OS. Engine stays STOPPED (no auto-start).
    nice -n 15 java -jar "$TRADING_JAR" 0 0 >"$LOG" 2>&1 &
    PID=$!
    echo "==> starting a throwaway trading engine, engine STOPPED (pid $PID)"
    PORT="$(await_port '\[WS\] WebSocket server started on port \K[0-9]+')"
    echo "==> ready on ws://127.0.0.1:$PORT"
    K6_ENV+=(-e "WS_URL=ws://127.0.0.1:$PORT")
  fi
  [[ -n "${CLIENTS:-}"  ]] && K6_ENV+=(-e "CLIENTS=$CLIENTS")
  [[ -n "${HOLD_MS:-}"  ]] && K6_ENV+=(-e "HOLD_MS=$HOLD_MS")
else
  if [[ -n "${BASE_URL:-}" ]]; then
    echo "==> targeting existing server: $BASE_URL (not starting anything)"
    K6_ENV+=(-e "BASE_URL=$BASE_URL" -e "API_KEY=${API_KEY:-}")
  else
    [[ -f "$PAYMENT_JAR" ]] || {
      echo "jar not found: $PAYMENT_JAR" >&2
      echo "build it:  cd $REPO_ROOT && mvn -q package -pl payment-api -am -DskipTests" >&2
      exit 1
    }
    KEY="k6-key-$RUN_ID"
    LOG="$(mktemp -t payment-api-k6-XXXXXX.log)"
    PAYMENT_API_KEY="$KEY" PAYMENT_REPO="${PAYMENT_REPO:-memory}" \
      nice -n 15 java -jar "$PAYMENT_JAR" 0 >"$LOG" 2>&1 &
    PID=$!
    echo "==> starting a throwaway Payment API (pid $PID)"
    PORT="$(await_port 'REST API\s*:\s*http://0\.0\.0\.0:\K[0-9]+')"
    TARGET="http://127.0.0.1:$PORT"
    for _ in $(seq 1 100); do
      curl -sf --max-time 2 "$TARGET/api/v1/health" >/dev/null 2>&1 && break
      sleep 0.2
    done
    echo "==> ready on $TARGET"
    K6_ENV+=(-e "BASE_URL=$TARGET" -e "API_KEY=$KEY")
  fi
fi

[[ -n "${RATE:-}"     ]] && K6_ENV+=(-e "RATE=$RATE")
[[ -n "${DURATION:-}" ]] && K6_ENV+=(-e "DURATION=$DURATION")

echo "==> k6 run $SCRIPT"
nice -n 15 "$K6" run "${K6_ENV[@]}" "$HERE/$SCRIPT"
