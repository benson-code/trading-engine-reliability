#!/usr/bin/env bash
#
# check-obs-mounts.sh — 監控容器讀得到磁碟上的設定嗎？
#
# 為什麼需要這支：
#   2026-09-06 發現 obs-blackbox 從 09-03 起就在跑一份「磁碟上已經不存在」
#   的設定。git 操作（checkout / reset --hard）重建了 blackbox/ 目錄，
#   bind mount 卻還指著舊的 inode，於是容器裡那個目錄變成空的。
#
#   而 exporter 完全沒事 —— 它啟動時已經把設定讀進記憶體，之後照常服務。
#   你改設定不會有效果，也不會報錯。三天之內沒有任何徵兆。
#
#   這是本專案反覆遇到的同一個形狀：執行中的狀態與磁碟上的真相悄悄分家，
#   而所有健康檢查都是綠的。差別只在這次分家的是「設定」而不是「程式碼」。
#
# 檢查方式：對每個 bind 掛載的設定檔，確認容器內真的看得到同一份內容
#           （比對 sha256，不只是檔案存在）。
#
# 用法：tools/check-obs-mounts.sh
# 離開碼：0 = 全部一致；1 = 有容器讀不到或讀到不同的內容
set -uo pipefail

FAIL=0
CHECKED=0

echo
echo "─────────────────────────────────────────────"
echo " 監控容器設定掛載一致性"
echo "─────────────────────────────────────────────"

# 只檢查「設定檔」類的掛載。/proc、/sys、/rootfs 這類是主機視圖，
# 內容本來就會不一樣，比對它們只會製造雜訊。
declare -A TARGETS=(
  [obs-prometheus]="/etc/prometheus/prometheus.yml /etc/prometheus/alerts.yml /etc/prometheus/slo.yml"
  [obs-alertmanager]="/etc/alertmanager/alertmanager.yml"
  [obs-blackbox]="/etc/blackbox/blackbox.yml"
  [obs-grafana]="/etc/grafana/provisioning/datasources/prometheus.yml"
)

for container in "${!TARGETS[@]}"; do
  if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
    echo "  ⚠ $container 沒有在執行，略過"
    continue
  fi

  for path in ${TARGETS[$container]}; do
    CHECKED=$((CHECKED + 1))

    # 從容器的掛載表反推對應的主機路徑，不寫死目錄結構
    host_path="$(docker inspect "$container" --format \
      '{{range .Mounts}}{{if eq .Type "bind"}}{{.Source}}|{{.Destination}}{{println}}{{end}}{{end}}' \
      | awk -F'|' -v p="$path" '
          $2 != "" && index(p, $2) == 1 {
            rest = substr(p, length($2) + 1)
            print $1 rest
            exit
          }')"

    if [ -z "$host_path" ] || [ ! -f "$host_path" ]; then
      echo "  ✗ $container : $path —— 主機端找不到對應檔案"
      FAIL=1; continue
    fi

    host_sum="$(sha256sum "$host_path" | cut -d' ' -f1)"
    ctr_sum="$(docker exec "$container" sha256sum "$path" 2>/dev/null | cut -d' ' -f1)"

    if [ -z "$ctr_sum" ]; then
      echo "  ✗ $container : $path —— 容器內讀不到（bind mount 已斷裂）"
      echo "      主機有這個檔案，但容器看不到它。服務正在跑記憶體裡的舊設定。"
      echo "      修法：docker compose -f deploy/observability/docker-compose.yml \\"
      echo "            up -d --force-recreate <service>"
      FAIL=1
    elif [ "$host_sum" != "$ctr_sum" ]; then
      echo "  ✗ $container : $path —— 內容不一致"
      echo "      主機 ${host_sum:0:12}  容器 ${ctr_sum:0:12}"
      FAIL=1
    fi
  done
done

echo "─────────────────────────────────────────────"
printf " 檢查檔案數 : %d\n" "$CHECKED"
echo "─────────────────────────────────────────────"
echo

if [ "$FAIL" -eq 0 ]; then
  echo "✅ 所有監控容器讀到的設定與磁碟一致。"
else
  echo "❌ 有容器讀不到磁碟上的設定 —— 它正在跑舊的那一份。"
fi

exit "$FAIL"
