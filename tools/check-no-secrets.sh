#!/usr/bin/env bash
#
# check-no-secrets.sh — 阻止憑證進入版控
#
# 這是個公開 repo。任何寫進原始碼的密碼等同於公布給所有人，
# 而且推出去之後就收不回來了（git 歷史會永久保留）。
#
# 檢查四件事：
#   S1 已知的憑證檔沒有被追蹤
#   S2 已追蹤的檔案裡沒有高風險的機密樣式
#   S3 每個憑證檔都有對應的 .example 範本
#   S4 範本檔裡放的是佔位符，不是真實值
#
# 離開碼：0 = 通過；1 = 有違規

set -uo pipefail
FAIL=0

echo
echo "─────────────────────────────────────────────"
echo " 機密外洩檢查"
echo "─────────────────────────────────────────────"

# ── S1：憑證檔不得被追蹤 ─────────────────────────────────────────
CRED_FILES=(
  "deploy/observability/mysqld/.my.cnf"
  "deploy/systemd/binance-trading-engine.env"
)
for f in "${CRED_FILES[@]}"; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    echo "  ✗ S1 憑證檔已被追蹤：$f"
    FAIL=1
  fi
done

# ── S2：已追蹤檔案中的機密樣式 ───────────────────────────────────
# 只掃 git 追蹤的檔案 —— 未追蹤的不會被推出去
PATTERNS=(
  '-----BEGIN [A-Z ]*PRIVATE KEY-----'                        # 私鑰
  'AKIA[0-9A-Z]{16}'                                          # AWS access key
  'gh[pousr]_[A-Za-z0-9]{36}'                                 # GitHub token
  'xox[baprs]-[0-9A-Za-z-]{10,}'                              # Slack token
  'sk-[A-Za-z0-9]{32,}'                                       # 各家 API key
  # 賦值成「帶引號的字面值」才算 —— this.password = password 這種欄位賦值不算
  '(PASS|PASSWD|PASSWORD|SECRET|TOKEN|APIKEY|API_KEY)[A-Za-z_]*[[:space:]]*[:=][[:space:]]*["'"'"'][A-Za-z0-9!@#%^&*_-][A-Za-z0-9!@#$%^&*_-]{7,}["'"'"']'   # 帶引號的字面值（首字元非 $，排除變數引用）
  # shell 的預設值展開 —— ${DB_PASSWORD:-<real value>} 正是密碼最常藏的地方
  '\$\{[A-Za-z_]*(PASS|SECRET|TOKEN|KEY)[A-Za-z_]*:-[^}]{6,}\}'
)
# 讀取例外清單（每條都必須附理由）
ALLOW="$(mktemp)"
if [ -f .secretsignore ]; then
  grep -vE '^\s*(#|$)' .secretsignore | awk '{print $1}' > "$ALLOW"
fi
ALLOW_N=$(wc -l < "$ALLOW")

# 掃描範圍 = 已追蹤檔案 + 未被 gitignore 排除的未追蹤檔案。
# 只用 git grep 的話，新檔案在 `git add` 之前是盲區 —— 而那正是
# 憑證最容易被寫進去、又最容易被漏掉的時機。
SCAN_LIST="$(mktemp)"; trap 'rm -f "$ALLOW" "$SCAN_LIST"' EXIT
git ls-files --cached --others --exclude-standard -z 2>/dev/null > "$SCAN_LIST"
SCAN_N=$(tr -cd '\0' < "$SCAN_LIST" | wc -c)

for pat in "${PATTERNS[@]}"; do
  # 排除範本檔與說明文件中的示範字串
  hits=$(xargs -0 -r grep -nIE "$pat" < "$SCAN_LIST" 2>/dev/null \
         | grep -viE '\.example:|CHANGE_ME|dummy-|placeholder|your[_-]|xxx|test-key-|<[^>]+>' \
         | grep -vFf "$ALLOW" || true)
  if [ -n "$hits" ]; then
    echo "  ✗ S2 疑似機密："
    echo "$hits" | sed 's/^/        /'
    FAIL=1
  fi
done

# ── S3：憑證檔要有範本 ───────────────────────────────────────────
for f in "${CRED_FILES[@]}"; do
  if [ ! -f "$f.example" ]; then
    echo "  ✗ S3 缺少範本檔：$f.example"
    FAIL=1
  fi
done

# ── S4：範本裡必須是佔位符 ───────────────────────────────────────
while IFS= read -r ex; do
  if grep -qiE '^(password|DB_PASSWORD)\s*[:=]' "$ex" 2>/dev/null; then
    if ! grep -qiE 'CHANGE_ME|<[^>]+>|your[_-]|xxx|placeholder' "$ex" 2>/dev/null; then
      echo "  ✗ S4 範本檔可能含真實憑證：$ex"
      FAIL=1
    fi
  fi
done < <(git ls-files '*.example' 2>/dev/null)

TRACKED=$(git ls-files | wc -l)
echo "─────────────────────────────────────────────"
printf " 已追蹤檔案數 : %d\n" "$TRACKED"
printf " 實際掃描檔案 : %d（含尚未 git add 的新檔案）\n" "$SCAN_N"
printf " 機密樣式規則 : %d\n" "${#PATTERNS[@]}"
printf " 憑證檔       : %d（皆應為 untracked）\n" "${#CRED_FILES[@]}"
printf " 已核准例外   : %d（見 .secretsignore）\n" "$ALLOW_N"
echo "─────────────────────────────────────────────"
echo

if [ "$FAIL" -eq 0 ]; then
  echo "✅ 未發現進入版控的憑證。"
else
  echo "❌ 有機密可能被提交 —— 修正後再推送。"
  echo "   提醒：一旦推到公開 repo，git 歷史會永久保留，改密碼才是真正的修復。"
fi
exit "$FAIL"
