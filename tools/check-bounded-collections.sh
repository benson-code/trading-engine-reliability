#!/usr/bin/env bash
#
# check-bounded-collections.sh — 長生命週期集合的無界性檢查
#
# 背景：2026-07 的 GC 死亡螺旋事故（docs/incident-2026-07-14-gc-death-spiral/）
#       起因是 OrderBook 的三個成員集合只進不出，7.6 天累積 1,136 萬個物件耗盡 heap。
#       84 個功能測試全綠，因為功能測試沒有時間軸。
#
# 規則：src/main 底下每一個「集合型別的成員欄位」都必須二擇一——
#         (a) 具備淘汰機制（removeEldestEntry / 容量上限 / 明確的移除路徑），或
#         (b) 在欄位上方以 `// BOUNDED-BY: <理由>` 註解說明為何不會無限成長
#       兩者皆無 → 檢查失敗。
#
# 這是「宣告式」而非「推論式」的檢查：腳本不猜測意圖，而是要求作者明確表態。
# 它擋不了「亂寫理由」，但能擋掉「完全沒想過這件事」——後者正是本次事故的成因。
#
# 用法：
#   tools/check-bounded-collections.sh            # 檢查全部模組
#   tools/check-bounded-collections.sh <path>...  # 檢查指定路徑
#
# 離開碼：0 = 通過；1 = 發現未經聲明的集合欄位

set -uo pipefail

# 視為「集合」的型別（含常見具體實作）
COLLECTION_TYPES='List|ArrayList|LinkedList|CopyOnWriteArrayList|Map|HashMap|LinkedHashMap|TreeMap|ConcurrentHashMap|ConcurrentSkipListMap|Set|HashSet|LinkedHashSet|TreeSet|ConcurrentSkipListSet|Collection|Queue|Deque|ArrayDeque|ConcurrentLinkedQueue|LinkedBlockingQueue|PriorityQueue'

# 已具備淘汰機制的訊號（出現在欄位所屬的類別中即視為有界）
EVICTION_SIGNALS='removeEldestEntry|CacheBuilder|Caffeine\.newBuilder|maximumSize|expireAfter|EvictingQueue|CircularFifoQueue'

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
    mapfile -t targets < <(find . -type d -path '*/src/main/java' -not -path './node_modules/*' 2>/dev/null)
fi

if [ ${#targets[@]} -eq 0 ]; then
    echo "找不到任何 src/main/java 目錄，略過檢查。"
    exit 0
fi

violations=0
declared=0
bounded=0

while IFS= read -r file; do
    [ -f "$file" ] || continue

    # 類別層級是否已有淘汰機制
    class_has_eviction=0
    if grep -qE "$EVICTION_SIGNALS" "$file"; then
        class_has_eviction=1
    fi

    # 逐行找集合型別的成員欄位宣告
    while IFS=: read -r lineno line; do
        [ -n "$lineno" ] || continue

        if [ "$class_has_eviction" -eq 1 ]; then
            bounded=$((bounded + 1))
            continue
        fi

        # 檢查前 5 行內是否有 BOUNDED-BY 聲明
        start=$((lineno > 5 ? lineno - 5 : 1))
        if sed -n "${start},${lineno}p" "$file" | grep -q "BOUNDED-BY:"; then
            declared=$((declared + 1))
            continue
        fi

        printf '  %s:%s\n' "$file" "$lineno"
        printf '      %s\n' "$(echo "$line" | sed 's/^[[:space:]]*//')"
        violations=$((violations + 1))
    # 欄位宣告的特徵：型別 + 變數名 + `=` 或 `;`；方法宣告會有 `(`，故排除。
    done < <(grep -nE "^[[:space:]]*(private|protected|public)[[:space:]]+(static[[:space:]]+)?(final[[:space:]]+)?($COLLECTION_TYPES)(<[^;(]*>)?[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*[=;]" "$file")

done < <(find "${targets[@]}" -name '*.java' -type f 2>/dev/null)

echo
echo "─────────────────────────────────────────────"
echo " 已有淘汰機制    : $bounded"
echo " 已 BOUNDED-BY 聲明: $declared"
echo " 未聲明（違規）  : $violations"
echo "─────────────────────────────────────────────"

if [ "$violations" -gt 0 ]; then
    cat <<'EOF'

❌ 發現未經聲明的長生命週期集合欄位。

每個欄位請二擇一處理：

  (a) 加上淘汰機制
      new LinkedHashMap<K,V>(cap, 0.75f, true) {
          protected boolean removeEldestEntry(Map.Entry<K,V> e) { return size() > cap; }
      }

  (b) 若確定不會無限成長，在欄位上方明確聲明理由
      // BOUNDED-BY: 僅存放 8 種訂單狀態，數量由 enum 固定
      private final Map<OrderStatus, Integer> counters = new EnumMap<>(OrderStatus.class);

參考：docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md
EOF
    exit 1
fi

echo
echo "✅ 所有長生命週期集合皆已有界或已明確聲明。"
exit 0
