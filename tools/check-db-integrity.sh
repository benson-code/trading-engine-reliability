#!/usr/bin/env bash
#
# check-db-integrity.sh — 資料表持續成長中的完整性檢查
#
# 背景：binance_test_db.orders 由 trading-engine 以約 20 筆/秒 持續寫入，
#       表已累積 3,700 萬筆。「一邊長、一邊驗」的困難不在於寫 SQL，
#       而在於 **被驗證的集合本身在移動**：
#         - COUNT(*) 兩次結果不同，不代表資料壞了
#         - 跨查詢比對會被邊界切斷的資料列誤判成不一致
#
# 解法：**水位線凍結（high-watermark freeze）**。開場取 W := MAX(id)，
#       之後所有檢查一律加上 `id <= W`。id 是 AUTO_INCREMENT，新資料只會
#       出現在 W 之上，因此 [MIN(id), W] 這個集合在檢查期間是不可變的。
#       這樣就把「移動中的表」轉成「靜止的快照」，而且不必鎖表、
#       不必停寫入、不必開長交易。
#
# 檢查項目：
#   C1 主鍵連續性     id 無斷號 → 沒有失敗/回滾的插入
#   C2 域約束         金額為正、列舉值合法、時間戳合理、必填欄位非空
#   C3 重複標記不變式 每個 order_id：被標記的筆數 == 出現次數 - 1
#   C4 歷史不可變性   凍結區間的 checksum 與 baseline 一致 → 舊資料沒被改寫
#   C5 端到端對帳     engine 計數器 vs DB 筆數，差值必須恆定 → 沒有靜默丟失
#   C6 寫入連續性     每分鐘筆數不得塌陷 → 偵測寫入中斷
#
#   C4 與 C5 需要跨次執行的基準值，首次執行會建立 baseline（記為 INIT）。
#
# 用法：
#   tools/check-db-integrity.sh              # 全部檢查
#   WINDOW=1000000 tools/check-db-integrity.sh   # 加大 C2/C3 的抽樣窗口
#   DB_PASSWORD=xxx tools/check-db-integrity.sh   # 或用 .my.cnf 憑證檔
#
# 離開碼：0 = 全數通過；1 = 有檢查失敗
#
# 注意：C2/C3 只掃描水位線下方 WINDOW 筆（預設 30 萬，走 PK range scan）。
#       這是共用主機上的刻意取捨——全表掃描 3,700 萬筆會與線上寫入搶 IO。
#       要做全表驗證請明確設定 WINDOW=0，並挑離峰時段。

set -uo pipefail

# ── 資料庫憑證 ───────────────────────────────────────────────────
# 密碼絕不寫死在腳本裡（這支腳本會進公開版控）。
# 取得順序：
#   1. DB_PASSWORD 環境變數
#   2. deploy/observability/mysqld/.my.cnf（已列入 .gitignore，權限 600）
# 兩者皆無 → 直接中止並說明如何設定。
#
# 實際連線一律走 --defaults-extra-file，不用 -p 傳密碼 ——
# 命令列參數會出現在 `ps` 的輸出裡，同機器上的其他使用者看得到。
DB_USER="${DB_USER:-binance_user}"
DB_NAME="${DB_NAME:-binance_test_db}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRED_FILE="${CRED_FILE:-$REPO_ROOT/deploy/observability/mysqld/.my.cnf}"

MYSQL_DEFAULTS=""
if [ -n "${DB_PASSWORD:-}" ]; then
    MYSQL_DEFAULTS="$(mktemp)"
    chmod 600 "$MYSQL_DEFAULTS"
    printf '[client]\nuser=%s\npassword=%s\nhost=127.0.0.1\n' "$DB_USER" "$DB_PASSWORD" > "$MYSQL_DEFAULTS"
    trap 'rm -f "$MYSQL_DEFAULTS"' EXIT
elif [ -r "$CRED_FILE" ]; then
    MYSQL_DEFAULTS="$CRED_FILE"
else
    echo "錯誤：找不到資料庫憑證。" >&2
    echo "  設定環境變數：  DB_PASSWORD=xxx $0" >&2
    echo "  或建立憑證檔：  cp $CRED_FILE.example $CRED_FILE && chmod 600 $CRED_FILE" >&2
    exit 2
fi

STATUS_URL="${STATUS_URL:-http://localhost:8092/api/v1/status}"
STATE="${STATE:-$HOME/.db-integrity-state}"
WINDOW="${WINDOW:-300000}"        # 0 = 全表
FROZEN_LO="${FROZEN_LO:-1000000}" # C4 的凍結區間（必須遠低於水位線）
FROZEN_HI="${FROZEN_HI:-1100000}"

FAILED=0

q() { mysql --defaults-extra-file="$MYSQL_DEFAULTS" "$DB_NAME" -N -B -e "$1" 2>/dev/null; }

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=1; }
init() { printf '  \033[33mINIT\033[0m  %s\n' "$1"; }

# state 檔以 key=value 儲存，用 grep 取值而非 source，避免執行檔案內容
state_get() { [[ -f "$STATE" ]] && grep -m1 "^$1=" "$STATE" 2>/dev/null | cut -d= -f2- || true; }
state_set() {
    [[ -f "$STATE" ]] || : > "$STATE"
    grep -v "^$1=" "$STATE" > "$STATE.tmp" 2>/dev/null || : > "$STATE.tmp"
    printf '%s=%s\n' "$1" "$2" >> "$STATE.tmp"
    mv "$STATE.tmp" "$STATE"
}

# ── 水位線凍結 ────────────────────────────────────────────────────────────
# C5 夾擠讀取（sandwich read）：
#   engine 計數器與 DB 筆數不可能同時讀到，中間每過一秒就差約 20 筆。
#   若只讀一次 DB 再讀一次 engine，delta 會隨讀取間隔漂移，檢查本身就會誤報。
#   改成 DB → engine → DB：engine 讀到的瞬間，DB 真實筆數必落在 [D1, D2] 之間，
#   因此真實 delta 必落在 [D1-GEN, D2-GEN]。用區間比對取代單點比對，
#   不需要拍腦袋的容忍值，也不會把量測誤差誤判成資料遺失。
#
#   夾擠用 MAX(id) 而非 COUNT(*)：COUNT(*) 要掃 3,700 萬筆、耗時數秒，
#   期間又寫入上百筆，區間會被自己的查詢時間撐寬到 ~120 筆，
#   等於失去偵測小量遺失的能力。MAX(id) 走 PK 是 O(1)，區間收斂到毫秒級。
#   代價是這條捷徑依賴「id 無斷號」——也就是 C1 的結論，故 C1 失敗時 C5 降級。
read -r M1 MINID <<<"$(q 'SELECT MAX(id), MIN(id) FROM orders;')"
GEN=$(curl -s --max-time 5 "$STATUS_URL" 2>/dev/null \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["total_generated"])' 2>/dev/null)
read -r M2 <<<"$(q 'SELECT MAX(id) FROM orders;')"

W="$M2"
TOTAL=$(q "SELECT COUNT(*) FROM orders WHERE id <= $W;")
D1=$(( M1 - MINID + 1 ))
D2=$(( M2 - MINID + 1 ))

if [[ -z "${W:-}" ]]; then
    echo "無法連線資料庫或表為空" >&2
    exit 1
fi

LO=$(( WINDOW > 0 && W - WINDOW > MINID ? W - WINDOW : MINID ))

echo
echo "水位線 W = $W   總筆數 = $TOTAL   檢查窗口 = [$LO, $W]"
echo

# ── C1 主鍵連續性 ─────────────────────────────────────────────────────────
# AUTO_INCREMENT 斷號的正當來源是交易回滾與 INSERT 失敗。斷號本身不等於
# 資料壞掉，但斷號數量是「有多少次寫入沒成功」的下界，值得盯著。
EXPECTED=$(( W - MINID + 1 ))
GAPS=$(( EXPECTED - TOTAL ))
if [[ "$GAPS" -eq 0 ]]; then
    C1_OK=1
    pass "C1 主鍵連續性：id $MINID..$W 無斷號（$TOTAL 筆）"
else
    C1_OK=0
    fail "C1 主鍵連續性：缺 $GAPS 個 id（期望 $EXPECTED、實得 $TOTAL）→ 有失敗或回滾的插入"
fi

# ── C2 域約束 ─────────────────────────────────────────────────────────────
read -r N BAD_PRICE BAD_AMT BAD_TYPE BAD_STATUS NULL_THREAD BAD_TS <<<"$(q "
SELECT COUNT(*),
       SUM(price <= 0),
       SUM(amount <= 0),
       SUM(type NOT IN ('BUY','SELL')),
       SUM(status NOT IN ('PENDING','NEW','FILLED','PARTIALLY_FILLED','CANCELLED','REJECTED')),
       SUM(thread_name IS NULL),
       SUM(timestamp < 1780000000000 OR timestamp > (UNIX_TIMESTAMP()+60)*1000)
FROM orders WHERE id BETWEEN $LO AND $W;")"

BAD_TOTAL=$(( BAD_PRICE + BAD_AMT + BAD_TYPE + BAD_STATUS + NULL_THREAD + BAD_TS ))
if [[ "$BAD_TOTAL" -eq 0 ]]; then
    pass "C2 域約束：$N 筆全數合法"
else
    fail "C2 域約束：price=$BAD_PRICE amount=$BAD_AMT type=$BAD_TYPE status=$BAD_STATUS thread=$NULL_THREAD ts=$BAD_TS"
fi

# ── C3 重複標記不變式 ─────────────────────────────────────────────────────
# orders.order_id 只有一般索引、沒有 UNIQUE 約束，重複偵測完全在應用層。
# 不變式：同一 order_id 出現 n 次，就該有 n-1 筆帶 is_duplicate=1
#（第一筆是正本）。窗口邊界會切斷跨界的重複組，故排除最外側 1000 筆。
VIOL=$(q "
SELECT COUNT(*) FROM (
  SELECT order_id, COUNT(*) n, SUM(is_duplicate) f
  FROM orders WHERE id BETWEEN $((LO+1000)) AND $((W-1000))
  GROUP BY order_id HAVING f <> n - 1
) t;")
if [[ "${VIOL:-1}" -eq 0 ]]; then
    pass "C3 重複標記不變式：無違反（每個 order_id 的標記數 == 出現次數-1）"
else
    fail "C3 重複標記不變式：$VIOL 個 order_id 違反 → 應用層去重與落庫不一致"
fi

# ── C4 歷史不可變性 ───────────────────────────────────────────────────────
# 已寫入的舊資料不該再被修改。對一段遠低於水位線的凍結區間取 checksum，
# 與上次執行的結果比對。XOR 對「順序」不敏感但對「內容」敏感；
# 併用 SUM 可抓到成對抵銷的極端情況。
read -r FN FXOR FSUM <<<"$(q "
SELECT COUNT(*),
       BIT_XOR(CRC32(CONCAT_WS('|',id,order_id,type,price,amount,status,timestamp,is_duplicate))),
       SUM(CRC32(CONCAT_WS('|',id,order_id,type,price,amount,status,timestamp,is_duplicate)))
FROM orders WHERE id BETWEEN $FROZEN_LO AND $FROZEN_HI;")"

SIG="$FN:$FXOR:$FSUM"
PREV_SIG=$(state_get frozen_sig)
if [[ -z "$PREV_SIG" ]]; then
    state_set frozen_sig "$SIG"
    state_set frozen_range "$FROZEN_LO-$FROZEN_HI"
    init "C4 歷史不可變性：已建立 baseline（id $FROZEN_LO..$FROZEN_HI，$FN 筆，crc=$FXOR）"
elif [[ "$SIG" == "$PREV_SIG" ]]; then
    pass "C4 歷史不可變性：凍結區間 checksum 未變（$FN 筆）"
else
    fail "C4 歷史不可變性：checksum 改變！baseline=$PREV_SIG 現在=$SIG → 舊資料被改寫或刪除"
fi

# ── C5 端到端對帳 ─────────────────────────────────────────────────────────
# engine 的 total_generated 是「產生了幾筆」，DB 的 COUNT(*) 是「落庫了幾筆」。
# 兩者的差值 = 本次啟動前就存在的歷史資料，是一個常數。
# 差值變小 = DB 追不上 engine，有寫入被靜默丟棄（2026-07-29 至 08-01 正是這個
# 失效模式：JDBC 斷線，非阻塞寫入佇列把訂單丟掉，服務卻仍顯示 active）。
# 差值變大 = engine 重啟、計數器歸零，需要重建 baseline，不是資料問題。
if [[ -z "${GEN:-}" ]]; then
    fail "C5 端到端對帳：無法取得 engine 計數器（$STATUS_URL 無回應）"
elif [[ "$C1_OK" -eq 0 ]]; then
    fail "C5 端到端對帳：略過——夾擠讀取以 MAX(id) 換算筆數，前提是 id 無斷號，而 C1 已失敗"
else
    DELTA_LO=$(( D1 - GEN ))   # engine 讀取瞬間，delta 的下界
    DELTA_HI=$(( D2 - GEN ))   # 同上，上界；區間寬度 = 讀取間隔內的寫入量
    PREV_DELTA=$(state_get delta)
    if [[ -z "$PREV_DELTA" ]]; then
        state_set delta "$DELTA_LO"
        init "C5 端到端對帳：已建立 baseline（delta ∈ [$DELTA_LO, $DELTA_HI]；generated=$GEN db=$D1..$D2）"
    elif [[ "$PREV_DELTA" -ge "$DELTA_LO" && "$PREV_DELTA" -le "$DELTA_HI" ]]; then
        pass "C5 端到端對帳：delta ∈ [$DELTA_LO, $DELTA_HI] 含 baseline $PREV_DELTA，$GEN 筆產生全數落庫、零遺失"
    elif [[ "$DELTA_HI" -lt "$PREV_DELTA" ]]; then
        # delta = DB筆數 - generated。寫入被丟棄時 DB 追不上 generated，delta 變小。
        fail "C5 端到端對帳：delta 上界 $DELTA_HI < baseline $PREV_DELTA → 至少 $(( PREV_DELTA - DELTA_HI )) 筆產生但未落庫"
    else
        # engine 重啟後 generated 歸零，DB 保留舊資料，delta 跳增。
        fail "C5 端到端對帳：delta 下界 $DELTA_LO > baseline $PREV_DELTA → engine 曾重啟（計數器歸零），請重建 baseline"
    fi
fi

# ── C6 寫入連續性 ─────────────────────────────────────────────────────────
# 每分鐘筆數塌陷代表寫入曾中斷。
# 上下界都必須對齊分鐘邊界：否則首尾會各切出一個不完整的分鐘桶，
# 被誤判成「寫入塌陷」。（第一版沒對齊下界，首桶只有 738 筆而非 1200。）
read -r MINUTES MIN_C MAX_C AVG_C <<<"$(q "
SELECT COUNT(*), MIN(c), MAX(c), ROUND(AVG(c)) FROM (
  SELECT FLOOR(timestamp/60000) m, COUNT(*) c
  FROM orders
  WHERE timestamp >= FLOOR((UNIX_TIMESTAMP()-3600)/60)*60000
    AND timestamp <  FLOOR(UNIX_TIMESTAMP()/60)*60000
  GROUP BY m
) t;")"

if [[ -z "${MINUTES:-}" || "$MINUTES" -eq 0 ]]; then
    fail "C6 寫入連續性：近 60 分鐘沒有任何寫入"
elif [[ "$MINUTES" -lt 55 ]]; then
    fail "C6 寫入連續性：近 60 分鐘只有 $MINUTES 分鐘有資料 → 中斷了 $(( 60 - MINUTES )) 分鐘"
elif [[ $(( MIN_C * 2 )) -lt "$AVG_C" ]]; then
    fail "C6 寫入連續性：最低分鐘 $MIN_C 筆 < 平均 $AVG_C 筆的一半 → 寫入曾塌陷"
else
    pass "C6 寫入連續性：$MINUTES 分鐘，每分鐘 $MIN_C..$MAX_C 筆（平均 $AVG_C）"
fi

echo
[[ "$FAILED" -eq 0 ]] && echo "全數通過" || echo "有檢查未通過"
exit "$FAILED"
