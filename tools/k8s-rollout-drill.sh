#!/usr/bin/env bash
#
# k8s-rollout-drill.sh — 滾動更新的零停機驗證
#
# `maxUnavailable: 0` 是宣告，不是保證。真正決定使用者會不會看到錯誤的是
# readinessProbe 有沒有在新 pod「真的能服務」之後才放行流量 ——
# 而那要在持續流量下量測，不是看設定檔。
#
# 用法：tools/k8s-rollout-drill.sh [release] [namespace]
set -uo pipefail
export KUBECONFIG="${KUBECONFIG:-/home/ubuntu/.kube/config}"
KC="k3s kubectl"

REL="${1:-payment-api}"; NS="${2:-payment}"
URL="${URL:-http://10.0.0.167:30091/api/v1/health}"
RPS="${RPS:-10}"
OUT="$(mktemp)"; trap 'rm -f "$OUT"' EXIT

echo
echo "═══════════════════════════════════════════════"
echo " 滾動更新零停機驗證 — $REL/$NS"
echo "═══════════════════════════════════════════════"
$KC get deploy -n "$NS" "$REL" -o custom-columns='就緒:.status.readyReplicas,期望:.spec.replicas' --no-headers | sed 's/^/  更新前 /'

# 背景持續打流量
( end=$(( $(date +%s) + 75 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    for _ in $(seq 1 "$RPS"); do
      printf '%s %s\n' "$(date +%s)" "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$URL")" >> "$OUT"
    done
    sleep 1
  done ) &
LOAD=$!

sleep 8
echo "  → 觸發滾動更新（改 annotation 強制重建，等同換 image tag）"
$KC -n "$NS" patch deployment "$REL" -p \
  "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"drill/restartedAt\":\"$(date +%s)\"}}}}}" >/dev/null

$KC -n "$NS" rollout status deployment/"$REL" --timeout=120s 2>&1 | sed 's/^/     /'

wait "$LOAD" 2>/dev/null

TOT=$(wc -l < "$OUT"); OK=$(awk '$2=="200"' "$OUT" | wc -l); BAD=$((TOT-OK))
echo
echo "  ── 客戶端觀測（每 10 秒一格）──────────────────"
START=$(head -1 "$OUT" | cut -d' ' -f1)
awk -v s="$START" '{b=int(($1-s)/10)*10; t[b]++; if($2=="200") o[b]++}
  END{for(b=0;b<=80;b+=10) if(t[b]>0)
        printf "    T+%-3ds  %3d 筆   200:%-3d  非200:%-3d %s\n",
          b,t[b],o[b]+0,t[b]-o[b], (t[b]-o[b]>0?"◄ 使用者受影響":"")}' "$OUT"
echo
echo "  ── 結果 ──────────────────────────────────────"
printf "    總請求      : %d\n" "$TOT"
printf "    非 200      : %d  (%.2f%%)\n" "$BAD" "$(awk "BEGIN{print $BAD*100/$TOT}")"
printf "    pod 重啟次數: %s\n" "$($KC get pods -n "$NS" --no-headers | awk '{s+=$4} END{print s+0}')"
echo
[ "$BAD" -eq 0 ] \
  && echo "  ✅ 全部副本汰換完成，使用者零錯誤 —— maxUnavailable:0 + readinessProbe 有效。" \
  || echo "  ⚠️  有 $BAD 筆失敗。檢查 readinessProbe 是否過早放行流量。"
echo
