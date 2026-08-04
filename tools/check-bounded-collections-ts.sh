#!/usr/bin/env bash
#
# check-bounded-collections-ts.sh — 長生命週期集合的無界性檢查（TypeScript）
#
# 這是 check-bounded-collections.sh（Java 版）的 TypeScript 對應版本，
# 沿用同一個宣告式契約：`// BOUNDED-BY: <理由>`。
#
# 背景：2026-07 的 GC 死亡螺旋事故（docs/incident-2026-07-14-gc-death-spiral/）
#       起因是 OrderBook 的三個成員集合只進不出。修復後建立了 Java 版的 CI 閘，
#       但那個閘只掃 src/main/java——同一個缺陷類別在前端完全沒有守備。
#
# 為什麼這一版「更嚴格」（這是刻意的設計決策，不是疏漏）：
#
#   Java 版採「類別層級的淘汰訊號」偵測——只要檔案裡出現 removeEldestEntry
#   之類的字樣，該檔所有集合欄位即視為有界。這個啟發式搬到 TypeScript 會失效：
#   useTradingEngine.ts 對 orders / klines 用 .slice() 設了上限，若沿用檔案層級
#   偵測，同一個 .slice() 會讓該檔所有集合都被判為有界——包括真正無界的那些。
#   換句話說，這個閘會漏掉它當初就是為了攔截的缺陷。
#
#   因此 TS 版不做任何淘汰推論：每一個長生命週期集合都必須逐一明確聲明。
#   腳本不猜測意圖，只要求作者表態——它擋不了「亂寫理由」，但能擋掉
#   「完全沒想過這件事」，而後者正是事故的成因。
#
# 掃描範圍——只看「活得比一次函式呼叫更久」的宣告位置：
#     · useState<...> / useState(...)
#     · useRef<...>   / useRef(...)
#     · 模組層級的 const / let / var（第 0 欄，含 export）
#     · class 欄位（private / public / protected / readonly）
#
#   函式內的區域變數不在範圍內——它們隨呼叫結束而回收，不構成洩漏風險。
#
# 例外：WeakMap / WeakSet / WeakRef 天生有界（鍵不可達時項目自動消失），自動通過。
#       這是型別保證，不是推論，所以可以安全地免除聲明。
#
# 用法：
#   tools/check-bounded-collections-ts.sh            # 檢查全部 .ts / .tsx
#   tools/check-bounded-collections-ts.sh <path>...  # 檢查指定路徑
#
# 離開碼：0 = 通過；1 = 發現未經聲明的集合宣告

set -uo pipefail

# 「這是一個集合」的訊號：泛型集合型別、陣列簡寫、或集合建構子
COLLECTION_SIGNAL='(Set|Map|Array|ReadonlyArray|ReadonlySet|ReadonlyMap|Record)<|\[\]|new[[:space:]]+(Set|Map|Array)\('

# 弱引用集合：型別本身保證有界，免除聲明
WEAK_SIGNAL='(WeakMap|WeakSet|WeakRef)<|new[[:space:]]+(WeakMap|WeakSet|WeakRef)\('

# 「這個宣告活得比一次函式呼叫久」的訊號
DECL_SITE='useState[<(]|useRef[<(]|^(export[[:space:]]+)?(const|let|var)[[:space:]]|^[[:space:]]*(private|public|protected|readonly)[[:space:]]'

# 印出附著於指定行的註解區塊（該行本身 + 其上方連續的註解／空行）。
# 遇到第一行非註解、非空白的程式碼即停止，因此不會誤讀到別的宣告的註解。
attached_comment_block() {
    awk -v target="$2" '
        NR > target { exit }
        { lines[NR] = $0 }
        END {
            print lines[target]
            for (i = target - 1; i >= 1; i--) {
                s = lines[i]
                gsub(/^[ \t]+|[ \t]+$/, "", s)
                if (s == "" || s ~ /^(\/\/|\/\*|\*)/) { print lines[i] }
                else { break }
            }
        }
    ' "$1"
}

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
    targets=(.)
fi

mapfile -t files < <(
    find "${targets[@]}" \
        \( -name '*.ts' -o -name '*.tsx' \) \
        -not -path '*/node_modules/*' \
        -not -path '*/.next/*' \
        -not -path '*/dist/*' \
        -not -path '*/build/*' \
        -not -name '*.d.ts' \
        -type f 2>/dev/null | sort
)

if [ ${#files[@]} -eq 0 ]; then
    echo "找不到任何 .ts / .tsx 檔案，略過檢查。"
    exit 0
fi

violations=0
declared=0
weak=0

for file in "${files[@]}"; do
    [ -f "$file" ] || continue

    while IFS=: read -r lineno line; do
        [ -n "$lineno" ] || continue

        # 弱引用集合：型別保證有界，免除聲明
        if printf '%s' "$line" | grep -qE "$WEAK_SIGNAL"; then
            weak=$((weak + 1))
            continue
        fi

        # 往回掃描「附著於此宣告的註解區塊」——從上一行開始收集空行與註解行，
        # 遇到第一行程式碼即停止。長註解能被完整讀到，別的宣告的註解不會被誤算。
        if grep -q "BOUNDED-BY:" <(attached_comment_block "$file" "$lineno"); then
            declared=$((declared + 1))
            continue
        fi

        printf '  %s:%s\n' "$file" "$lineno"
        printf '      %s\n' "$(printf '%s' "$line" | sed 's/^[[:space:]]*//')"
        violations=$((violations + 1))
    done < <(grep -nE "$DECL_SITE" "$file" | grep -E "$COLLECTION_SIGNAL")

done

echo
echo "─────────────────────────────────────────────"
echo " 弱引用（天生有界）  : $weak"
echo " 已 BOUNDED-BY 聲明  : $declared"
echo " 未聲明（違規）      : $violations"
echo "─────────────────────────────────────────────"

if [ "$violations" -gt 0 ]; then
    cat <<'EOF'

❌ 發現未經聲明的長生命週期集合。

每個宣告請二擇一處理：

  (a) 加上淘汰機制，並在上方說明它
      // BOUNDED-BY: setOrders 每次都 .slice(0, MAX_ORDERS)，上限 100 筆
      const [orders, setOrders] = useState<Order[]>([]);

  (b) 若確定不會無限成長，明確聲明理由
      // BOUNDED-BY: 鍵是 8 種訂單狀態，數量由 union type 固定
      const counters = useRef<Map<OrderStatus, number>>(new Map());

注意：本檢查不推論淘汰機制——檔案裡別處有 .slice() 不會讓這個宣告自動通過。
      理由請寫在宣告正上方的註解裡。

參考：docs/incident-2026-07-14-gc-death-spiral/RCA-zh-TW.md
EOF
    exit 1
fi

echo
echo "✅ 所有長生命週期集合皆已明確聲明或天生有界。"
exit 0
