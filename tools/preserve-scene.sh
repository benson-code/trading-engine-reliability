#!/usr/bin/env bash
#
# preserve-scene.sh — 事故現場保全（非破壞性採集）
#
# 為什麼需要這支：
#   重啟會解決症狀，同時銷毀根因。2026-07-14 的事故之所以能查出
#   真正原因，是因為進程被保留了 7 天沒有重啟。
#   但「保留現場」在半夜三點是很難執行的紀律 —— 除非它只要一個指令。
#
# 採集原則（依 PRESERVE_SCENE.md）：
#   允許：jstat（走 hsperfdata，不需 attach socket）、/proc、ps、top、
#         journalctl 唯讀、短逾時 curl 探測、MySQL SELECT
#   禁止：kill、systemctl stop/restart、強制 jmap / heap dump
#         （heap dump 在 OOME 狀態下可能讓情況更糟）
#
# 用法：
#   tools/preserve-scene.sh                       # 自動找 trading-engine
#   PATTERN=payment-api tools/preserve-scene.sh   # 指定其他服務
#   OUT=/path/to/dir tools/preserve-scene.sh      # 指定輸出位置
#
# 產出：<OUT>/snapshot-<UTC timestamp>/ 內含證據檔與 SHA256 manifest

set -uo pipefail

PATTERN="${PATTERN:-trading-engine-simulator}"
SERVICE="${SERVICE:-binance-trading-engine}"
STAMP="$(date -u +%Y-%m-%dT%H%MZ)"
OUT="${OUT:-$(pwd)/incident-snapshots}/snapshot-${STAMP}"
# ── 資料庫憑證（選用）─────────────────────────────────────────────
# 密碼不寫死在腳本裡。有憑證就採集 DB 證據，沒有就跳過該項。
DB_USER="${DB_USER:-binance_user}"
DB_NAME="${DB_NAME:-binance_test_db}"
CRED_FILE="${CRED_FILE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/deploy/observability/mysqld/.my.cnf}"
MYSQL_DEFAULTS=""
if [ -n "${DB_PASSWORD:-}" ]; then
    MYSQL_DEFAULTS="$(mktemp)"; chmod 600 "$MYSQL_DEFAULTS"
    printf '[client]\nuser=%s\npassword=%s\nhost=127.0.0.1\n' "$DB_USER" "$DB_PASSWORD" > "$MYSQL_DEFAULTS"
    trap 'rm -f "$MYSQL_DEFAULTS"' EXIT
elif [ -r "$CRED_FILE" ]; then
    MYSQL_DEFAULTS="$CRED_FILE"
fi

# cd 之後 $0 的相對路徑會失效，先把 repo root 解析成絕對路徑
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$OUT"
cd "$OUT" || exit 1

say() { printf "  %-34s" "$1"; }
ok()  { echo "✓"; }
skip(){ echo "— $1"; }

echo
echo "─────────────────────────────────────────────"
echo " 現場保全 → $OUT"
echo "─────────────────────────────────────────────"

# ── 0. 目標進程 ───────────────────────────────────────────────────
PID="$(pgrep -f "$PATTERN" | head -1)"
if [ -z "${PID:-}" ]; then
  echo "  ⚠ 找不到符合 '$PATTERN' 的進程 —— 仍會採集主機層證據"
else
  echo "  目標 PID: $PID  (pattern: $PATTERN)"
fi
echo

# ── 1. 主機快照 ───────────────────────────────────────────────────
say "主機環境"
{ uname -a; echo; nproc; echo; free -h; echo; uptime; echo; df -h; } > SNAPSHOT.txt 2>&1
ok

say "記憶體與 swap"
{ cat /proc/meminfo | head -20; echo "--- swap ---"; swapon --show || echo "無 swap"; } > meminfo.txt 2>&1
ok

# ── 2. 進程層（不需 attach）───────────────────────────────────────
if [ -n "${PID:-}" ]; then
  say "/proc/\$PID/status"
  cat "/proc/$PID/status" > proc_status.txt 2>&1 && ok || skip "讀取失敗"

  say "/proc/\$PID/smaps_rollup"
  cat "/proc/$PID/smaps_rollup" > smaps_rollup.txt 2>&1 && ok || skip "讀取失敗"

  say "/proc/\$PID/limits"
  cat "/proc/$PID/limits" > limits.txt 2>&1 && ok || skip "讀取失敗"

  say "cmdline"
  tr '\0' ' ' < "/proc/$PID/cmdline" > cmdline.txt 2>&1 && ok || skip "讀取失敗"

  say "檔案描述元數量"
  { echo "fd_count=$(ls /proc/$PID/fd 2>/dev/null | wc -l)"; } > fd_count.txt 2>&1; ok

  say "執行緒數量"
  { echo "threads=$(ls /proc/$PID/task 2>/dev/null | wc -l)"; } > thread_count.txt 2>&1; ok

  say "各執行緒 CPU（top -H）"
  top -H -p "$PID" -b -n 1 > top_threads.txt 2>&1 && ok || skip "失敗"

  # ── 3. JVM（jstat 走 hsperfdata，不需 attach socket）─────────────
  say "jstat -gc"
  timeout 15 jstat -gc "$PID" > jstat_gc.txt 2>&1
  [ -s jstat_gc.txt ] && ok || skip "逾時 ← 這本身就是證據"

  say "jstat -gcutil（5 次取樣）"
  timeout 20 jstat -gcutil "$PID" 1000 5 > jstat_gcutil.txt 2>&1
  [ -s jstat_gcutil.txt ] && ok || skip "逾時 ← 這本身就是證據"

  say "jstat -gccause"
  timeout 15 jstat -gccause "$PID" > jstat_gccause.txt 2>&1
  [ -s jstat_gccause.txt ] && ok || skip "逾時 ← 這本身就是證據"

  # attach 類工具：失敗要留下紀錄，不要靜默略過
  say "jcmd VM.uptime（attach 測試）"
  timeout 15 jcmd "$PID" VM.uptime > jcmd_attach_test.txt 2>&1
  rc=$?
  echo "exit_code=$rc" >> jcmd_attach_test.txt
  if [ $rc -eq 0 ]; then ok; else skip "attach 失敗 (exit=$rc) ← 確診訊號"; fi
fi

# ── 4. systemd 與日誌 ─────────────────────────────────────────────
say "systemd unit 狀態"
{ systemctl status "$SERVICE" --no-pager -l; echo; systemctl cat "$SERVICE"; } > systemd_status.txt 2>&1
ok

say "journal（近 500 行）"
journalctl -u "$SERVICE" -n 500 --no-pager > service_journal_tail500.txt 2>&1; ok

say "journal 關鍵字命中"
journalctl -u "$SERVICE" --no-pager 2>/dev/null \
  | grep -iE "OutOfMemory|GC overhead|SIGTERM|signal|exit code|Stopped|Started|Failed" \
  > journal_key_hits.txt 2>&1; ok

# ── 5. 服務可達性（短逾時，非破壞性）──────────────────────────────
say "API 探測"
{
  for p in 8091 8092 8094; do
    echo "--- :$p ---"
    timeout 5 curl -s -o /dev/null -w "http_code=%{http_code} time=%{time_total}s\n" \
      "http://localhost:$p/api/v1/health" 2>&1 || echo "無回應（逾時）"
  done
} > api_status.txt 2>&1; ok

# ── 6. DB 側界定影響範圍（JVM 問不動時的第二條路）─────────────────
say "DB 訂單統計"
if command -v mysql >/dev/null 2>&1 && [ -n "$MYSQL_DEFAULTS" ]; then
  # ⚠️ 這裡刻意不做全表掃描。orders 表已達數千萬筆，且有線上寫入正在進行 ——
  #    MIN/MAX(created_at) 沒有索引，掃全表會與寫入搶 IO（見 check-db-integrity.sh 的同一取捨）。
  #    改用水位線：先取 MAX(id)（PK，瞬間），再以 id 範圍界定最近的資料。
  timeout 60 mysql --defaults-extra-file="$MYSQL_DEFAULTS" "$DB_NAME" -e "
    SELECT MAX(id) AS watermark, MIN(id) AS first_id FROM orders;
    SELECT TABLE_ROWS AS approx_rows FROM information_schema.TABLES
      WHERE TABLE_SCHEMA='$DB_NAME' AND TABLE_NAME='orders';
    SET @w := (SELECT MAX(id) FROM orders);
    SELECT DATE_FORMAT(created_at,'%Y-%m-%d %H:%i') AS minute, COUNT(*) AS rows_written
      FROM orders WHERE id > @w - 200000
      GROUP BY minute ORDER BY minute DESC LIMIT 20;" > db_order_stats.txt 2>&1 && ok || skip "查詢失敗"
else
  skip "無 mysql client 或未設定憑證"
fi

# ── 7. 監控側的當下狀態 ───────────────────────────────────────────
say "Prometheus 進行中告警"
timeout 10 curl -s http://127.0.0.1:9090/api/v1/alerts \
  > prometheus_alerts.json 2>&1 && ok || skip "Prometheus 無回應"

say "textfile collector 快照"
cp -a "$REPO_ROOT/deploy/observability/textfile" ./textfile 2>/dev/null && ok || skip "不存在"

# ── 8. 完整性 manifest ────────────────────────────────────────────
say "SHA256 manifest"
find . -type f ! -name 'MANIFEST_SHA256.txt' -print0 \
  | sort -z | xargs -0 sha256sum > MANIFEST_SHA256.txt 2>/dev/null; ok

cat > README.md <<MD
# 現場快照 — ${STAMP}

**採集時間（UTC）**：$(date -u +%Y-%m-%dT%H:%M:%SZ)
**目標進程**：PID ${PID:-未找到} (pattern: \`$PATTERN\`)
**採集主機**：$(hostname) $(uname -m)

## 保全政策
在事故負責人核可之前，**不得**對目標進程執行：
\`kill\` / \`systemctl stop|restart\` / 強制 \`jmap\` 或 heap dump。

## 判讀提示
- \`jstat_*.txt\` 若為空或逾時，**那不是採集失敗，是 JVM 已飽和的確診證據**
  （attach 機制需要目標 JVM 的 handshake 執行緒，該執行緒也會被 GC 餓死）。
- JVM 問不動時，改由 \`db_order_stats.txt\` 從 DB 側界定影響範圍。
- 完整性驗證：\`sha256sum -c MANIFEST_SHA256.txt\`
MD

echo "─────────────────────────────────────────────"
echo " 檔案數：$(find . -type f | wc -l)　大小：$(du -sh . | cut -f1)"
echo " 驗證：cd $OUT && sha256sum -c MANIFEST_SHA256.txt"
echo "─────────────────────────────────────────────"
echo
