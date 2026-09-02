#!/usr/bin/env bash
#
# check-alert-runbooks.sh — 每一條告警規則都必須有對應的 runbook
#
# 為什麼要當成 CI 閘門：
#   沒有 SOP 的告警，等於把問題丟給半夜被叫起來的人自己想。
#   那是告警設計的失職，不是值班的問題。
#   人工約定會腐化，機器檢查不會 —— 所以把它變成 PR 擋門。
#
# 檢查三件事：
#   R1 每條 alert 都有 runbook_url
#   R2 每個 runbook_url 指向的檔案真的存在
#   R3 每份 runbook 都至少被一條 alert 引用（沒有孤兒文件）
#
# 離開碼：0 = 通過；1 = 有違規

set -uo pipefail

RULES="${RULES:-deploy/observability/prometheus/alerts.yml}"
RUNBOOK_DIR="${RUNBOOK_DIR:-docs/runbooks}"
FAIL=0

command -v python3 >/dev/null || { echo "需要 python3"; exit 1; }

echo
echo "─────────────────────────────────────────────"
echo " 告警 ↔ Runbook 覆蓋檢查"
echo "─────────────────────────────────────────────"

# 用 python 解析，避免 yaml 縮排在 shell 裡難處理
mapfile -t ROWS < <(python3 - "$RULES" <<'PY'
import sys, re
txt = open(sys.argv[1], encoding='utf-8').read()
# 以 "- alert:" 切段，每段內找 runbook_url
blocks = re.split(r'\n\s*- alert:\s*', txt)[1:]
for b in blocks:
    name = b.split('\n', 1)[0].strip()
    m = re.search(r'runbook_url:\s*"([^"]+)"', b)
    print(f"{name}\t{m.group(1) if m else ''}")
PY
)

declare -A REFERENCED
MISSING_URL=0; MISSING_FILE=0; TOTAL=0

for row in "${ROWS[@]}"; do
  name="${row%%$'\t'*}"; url="${row#*$'\t'}"
  TOTAL=$((TOTAL+1))

  if [ -z "$url" ]; then
    echo "  ✗ $name —— 沒有 runbook_url"
    MISSING_URL=$((MISSING_URL+1)); FAIL=1
    continue
  fi

  file="$RUNBOOK_DIR/$(basename "$url")"
  REFERENCED["$(basename "$url")"]=1

  if [ ! -f "$file" ]; then
    echo "  ✗ $name —— runbook 不存在：$file"
    MISSING_FILE=$((MISSING_FILE+1)); FAIL=1
  fi
done

# R3：孤兒 runbook（存在但沒有任何告警引用）
ORPHANS=0
for f in "$RUNBOOK_DIR"/*.md; do
  b="$(basename "$f")"
  [ "$b" = "README.md" ] && continue
  if [ -z "${REFERENCED[$b]:-}" ]; then
    echo "  ⚠ 孤兒 runbook（無告警引用）：$b"
    ORPHANS=$((ORPHANS+1))
  fi
done

echo "─────────────────────────────────────────────"
printf " 告警總數        : %d\n" "$TOTAL"
printf " 缺 runbook_url  : %d\n" "$MISSING_URL"
printf " runbook 檔案缺失: %d\n" "$MISSING_FILE"
printf " 孤兒 runbook    : %d\n" "$ORPHANS"
echo "─────────────────────────────────────────────"
echo

if [ "$FAIL" -eq 0 ]; then
  echo "✅ 所有告警皆有對應的處理 SOP。"
else
  echo "❌ 有告警缺少 SOP —— 請補上 runbook 後再合併。"
fi
exit "$FAIL"
