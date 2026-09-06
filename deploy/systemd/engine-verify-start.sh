#!/usr/bin/env bash
#
# engine-verify-start.sh — systemd ExecStartPost：確認「業務執行緒」真的起來了
#
# 事故 #2 的核心：systemd 看到的是「進程有沒有活著」，但活著的進程可以什麼都不做。
# 2026-07 那次，進程重啟成功、port 都開了、/status 回 200 —— 產生器停了六天。
#
# 這支腳本讓 unit 的成功條件從「進程存在」提高到「該做的工作真的在做」：
#   1. 等 REST API 回應（最多 60 秒）
#   2. 讀取持久化的操作者意圖（ENGINE_STATE_FILE）
#   3. 意圖是 RUNNING → 要求 /status 回報 RUNNING，否則以非零離開，systemd 把 unit 標為 failed
#      意圖未知（首次部署）或 STOPPED → 不強求，只印出狀態
#
# 為什麼要失敗而不是自己去 POST /engine/start：
#   自動修復會掩蓋問題（事故 #2 就是 systemd 的自動重啟讓故障「看起來」解決了）。
#   這裡要的是「起不來就大聲說」，讓 EngineWorkerStopped 與 unit failed 兩邊同時亮。
set -uo pipefail

PORT="${ENGINE_REST_PORT:-8092}"
URL="http://127.0.0.1:${PORT}/api/v1/status"
STATE_FILE="${ENGINE_STATE_FILE:-}"

for _ in $(seq 1 60); do
  STATUS="$(curl -sf -m 2 "$URL" 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null || true)"
  [ -n "$STATUS" ] && break
  sleep 1
done
if [ -z "${STATUS:-}" ]; then
  echo "engine-verify-start: REST API 在 60 秒內沒有回應（$URL）" >&2
  exit 1
fi

WANT="unknown"
if [ -n "$STATE_FILE" ] && [ -r "$STATE_FILE" ]; then
  WANT="$(tr -d '[:space:]' < "$STATE_FILE")"
fi

echo "engine-verify-start: desired=${WANT} actual=${STATUS}"
if [ "$WANT" = "RUNNING" ] && [ "$STATUS" != "RUNNING" ]; then
  echo "engine-verify-start: 操作者意圖為 RUNNING，但產生器沒有恢復 —— 這正是事故 #2 的形狀。unit 標為 failed。" >&2
  exit 1
fi
exit 0
