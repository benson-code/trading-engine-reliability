#!/usr/bin/env bash
# 把 jstat 的 GC 指標寫成 Prometheus textfile 格式，交給 node_exporter 的 textfile collector。
#
# 為什麼不用標準的 JMX exporter：
#   現有的 trading-engine JVM 啟動時沒有帶 -javaagent，也沒開 JMX remote。
#   重啟它會丟掉目前已連續運行的乾淨紀錄（修復後的對照組），所以改用 jstat 外部採樣。
#
# 這個腳本本身也是事故的一課：attach 失敗時要留下訊號，而不是靜默沒有資料。
set -uo pipefail

OUT_DIR="${OUT_DIR:-$(dirname "$0")/textfile}"
PATTERN="${PATTERN:-trading-engine-simulator}"
TMP="$OUT_DIR/jvm.prom.$$"

mkdir -p "$OUT_DIR"

PID=$(pgrep -f "$PATTERN" | head -1)
if [ -z "${PID:-}" ]; then
  { echo "# HELP jvm_process_up 目標 JVM 行程是否存在"
    echo "# TYPE jvm_process_up gauge"
    echo "jvm_process_up 0"
  } > "$TMP" && mv "$TMP" "$OUT_DIR/jvm.prom"
  exit 0
fi

# jstat 走 attach 機制 —— JVM 飽和時這一步會失敗，這正是 2026-07-14 事故當下的情況
GC=$(jstat -gc "$PID" 2>/dev/null | tail -1)
if [ -z "$GC" ]; then
  { echo "# HELP jvm_process_up 目標 JVM 行程是否存在"
    echo "# TYPE jvm_process_up gauge"
    echo "jvm_process_up 1"
    echo "# HELP jvm_jstat_attach_success jstat 是否成功 attach（0 = JVM 可能已飽和）"
    echo "# TYPE jvm_jstat_attach_success gauge"
    echo "jvm_jstat_attach_success 0"
  } > "$TMP" && mv "$TMP" "$OUT_DIR/jvm.prom"
  exit 0
fi

read -r S0C S1C S0U S1U EC EU OC OU MC MU CCSC CCSU YGC YGCT FGC FGCT CGC CGCT GCT <<< "$GC"

# 行程存活秒數，用來算「GC 佔了多少比例的存活時間」—— 事故當時是 70%
UPTIME=$(awk -v t="$(awk '{print $22}' /proc/"$PID"/stat)" \
             -v hz="$(getconf CLK_TCK)" \
             -v up="$(awk '{print $1}' /proc/uptime)" \
             'BEGIN{printf "%.0f", up - t/hz}')

k() { awk -v v="$1" 'BEGIN{printf "%.0f", v*1024}'; }   # jstat 單位是 KB
ratio() { awk -v a="$1" -v b="$2" 'BEGIN{print (b>0)? a/b : 0}'; }

cat > "$TMP" <<EOF
# HELP jvm_process_up 目標 JVM 行程是否存在
# TYPE jvm_process_up gauge
jvm_process_up 1
# HELP jvm_jstat_attach_success jstat 是否成功 attach（0 = JVM 可能已飽和）
# TYPE jvm_jstat_attach_success gauge
jvm_jstat_attach_success 1
# HELP jvm_uptime_seconds 行程存活秒數
# TYPE jvm_uptime_seconds gauge
jvm_uptime_seconds $UPTIME
# HELP jvm_oldgen_capacity_bytes 老年代容量
# TYPE jvm_oldgen_capacity_bytes gauge
jvm_oldgen_capacity_bytes $(k "$OC")
# HELP jvm_oldgen_used_bytes 老年代使用量
# TYPE jvm_oldgen_used_bytes gauge
jvm_oldgen_used_bytes $(k "$OU")
# HELP jvm_oldgen_utilization_ratio 老年代使用率（事故當時 0.9999）
# TYPE jvm_oldgen_utilization_ratio gauge
jvm_oldgen_utilization_ratio $(ratio "$OU" "$OC")
# HELP jvm_eden_capacity_bytes Eden 區容量
# TYPE jvm_eden_capacity_bytes gauge
jvm_eden_capacity_bytes $(k "$EC")
# HELP jvm_eden_used_bytes Eden 區使用量
# TYPE jvm_eden_used_bytes gauge
jvm_eden_used_bytes $(k "$EU")
# HELP jvm_metaspace_used_bytes Metaspace 使用量
# TYPE jvm_metaspace_used_bytes gauge
jvm_metaspace_used_bytes $(k "$MU")
# HELP jvm_gc_young_count Young GC 累計次數
# TYPE jvm_gc_young_count counter
jvm_gc_young_count $YGC
# HELP jvm_gc_young_seconds_total Young GC 累計耗時
# TYPE jvm_gc_young_seconds_total counter
jvm_gc_young_seconds_total $YGCT
# HELP jvm_gc_full_count Full GC 累計次數（事故當時 114,879）
# TYPE jvm_gc_full_count counter
jvm_gc_full_count $FGC
# HELP jvm_gc_full_seconds_total Full GC 累計 STW 秒數（事故當時 491,218）
# TYPE jvm_gc_full_seconds_total counter
jvm_gc_full_seconds_total $FGCT
# HELP jvm_gc_total_seconds_total 所有 GC 累計耗時
# TYPE jvm_gc_total_seconds_total counter
jvm_gc_total_seconds_total $GCT
EOF

mv "$TMP" "$OUT_DIR/jvm.prom"

# ── 業務層指標：直接打引擎的 REST API ──────────────────────────
# 從主機端採樣而非讓 Prometheus 容器連進來（Oracle Cloud 的 iptables 會擋 bridge → host）
STATUS=$(curl -s -m 5 http://localhost:8092/api/v1/status 2>/dev/null)
TMP2="$OUT_DIR/engine.prom.$$"
if [ -n "$STATUS" ]; then
  python3 - "$STATUS" > "$TMP2" <<'PYEOF'
import json,sys
try: d=json.loads(sys.argv[1])
except Exception: sys.exit(1)
running = 1 if str(d.get("status","")).upper()=="RUNNING" else 0
m=[("engine_up","gauge","REST API 是否可達",1),
   ("engine_running","gauge","訂單產生器是否運行中 (1=RUNNING)",running),
   ("engine_orders_generated_total","counter","累計產生訂單數",d.get("total_generated",0)),
   ("engine_orders_total","gauge","目前保留的訂單數 — 無界成長時這條會一路往上",d.get("total_orders",0)),
   ("engine_orders_unique","gauge","唯一訂單數",d.get("unique_orders",0)),
   ("engine_duplicate_count","gauge","重複訂單數",d.get("duplicate_count",0)),
   ("engine_cache_size","gauge","快取大小 — 有界化後應該收斂",d.get("cache_size",0)),
   ("engine_cache_hit_rate","gauge","快取命中率",d.get("cache_hit_rate",0.0)),
   ("engine_last_price","gauge","最新成交價",d.get("last_price",0))]
for n,t,h,v in m:
    print(f"# HELP {n} {h}\n# TYPE {n} {t}\n{n} {v}")
PYEOF
  [ -s "$TMP2" ] && mv "$TMP2" "$OUT_DIR/engine.prom" || rm -f "$TMP2"
else
  printf '# HELP engine_up REST API 是否可達\n# TYPE engine_up gauge\nengine_up 0\n' > "$TMP2"
  mv "$TMP2" "$OUT_DIR/engine.prom"
fi
