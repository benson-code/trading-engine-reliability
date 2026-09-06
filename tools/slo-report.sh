#!/usr/bin/env bash
#
# slo-report.sh — 從原始指標回算 SLI 與錯誤預算
#
# 為什麼不直接讀 slo.yml 記錄的 ratio_rate30d：
#   記錄規則不會回溯。規則加進去之前的歷史，記錄序列裡沒有。
#   要看真正的歷史達成率，只能對原始指標重算 —— 那正是這支做的事。
#
# 代價是 subquery 比較貴，所以它是「需要時手動跑」，
# 不是每 30 秒評估一次的規則。
#
# 用法：tools/slo-report.sh [視窗]      例：tools/slo-report.sh 7d
set -uo pipefail

PROM="${PROM:-http://127.0.0.1:9090}"
W="${1:-3d}"
API="http://127.0.0.1:8091/api/v1/health"

q() {
  curl -sG --max-time 60 "$PROM/api/v1/query" --data-urlencode "query=$1" \
  | python3 -c "import json,sys; r=json.load(sys.stdin).get('data',{}).get('result',[]); print(r[0]['value'][1] if r else 'nan')"
}

# SLI 定義必須跟 slo.yml 完全一致，否則這份報告是在量另一個東西
declare -A SLI TARGET
SLI[payment_api_availability]="avg_over_time(probe_success{instance=\"$API\"}[$W])"
SLI[payment_api_latency]="avg_over_time((probe_duration_seconds{instance=\"$API\"} < bool 0.25)[$W:15s])"
SLI[engine_work_progress]="avg_over_time((rate(engine_orders_generated_total[5m]) > bool 0)[$W:1m])"
TARGET[payment_api_availability]=0.999
TARGET[payment_api_latency]=0.995
TARGET[engine_work_progress]=0.99

curl -sf --max-time 5 "$PROM/-/healthy" >/dev/null || { echo "Prometheus 不可達：$PROM"; exit 1; }

echo
echo "───────────────────────────────────────────────────────────────"
echo " SLO 報告 —— 視窗 $W（由原始指標回算，非記錄規則）"
echo "───────────────────────────────────────────────────────────────"
printf " %-26s %10s %9s %11s  %s\n" "SLO" "實測" "目標" "預算剩餘" "狀態"

FAIL=0
for name in payment_api_availability payment_api_latency engine_work_progress; do
  sli="$(q "${SLI[$name]}")"; tgt="${TARGET[$name]}"
  read -r pct budget state <<<"$(python3 - "$sli" "$tgt" <<'PY'
import sys
sli, tgt = sys.argv[1], float(sys.argv[2])
if sli == 'nan':
    print("n/a n/a NODATA"); raise SystemExit
sli = float(sli)
budget = 1 - ((1 - sli) / (1 - tgt))
state = "OK" if sli >= tgt else ("BURNT" if budget >= 0 else "OVER")
print(f"{sli*100:.4f}% {budget*100:+.1f}% {state}")
PY
)"
  case "$state" in
    OK)     mark="✅" ;;
    BURNT)  mark="⚠️ " ;;
    OVER)   mark="❌"; FAIL=1 ;;
    *)      mark="—"  ;;
  esac
  printf " %-26s %10s %9s %11s  %s %s\n" "$name" "$pct" "$(python3 -c "print(f'{float('$tgt')*100:.3f}%')")" "$budget" "$mark" "$state"
done

echo "───────────────────────────────────────────────────────────────"
[ "$FAIL" -eq 0 ] \
  && echo " 全部 SLO 在預算內。" \
  || echo " ❌ 有 SLO 超支 —— 凍結非必要變更，優先處理可靠度。見 docs/slo.md"
echo
exit 0
