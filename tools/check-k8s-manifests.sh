#!/usr/bin/env bash
#
# check-k8s-manifests.sh — Helm chart 與 Kubernetes manifest 的 CI 閘門
#
# 為什麼要擋在 PR：
#   chart 的錯誤通常要到 `helm install` 當下才會爆 —— 那是最糟的
#   失敗時機（正在部署、可能已經有副本被汰換掉）。往前擋成本低得多。
#
# 檢查四件事：
#   K1 helm lint 通過
#   K2 chart 能實際算繪（lint 只看語法，算繪才會抓到缺少的 values）
#   K3 算繪結果每個文件都是合法的 Kubernetes 物件
#   K4 每個容器都宣告了 liveness 與 readiness probe
#
# K4 是本專案特有的規則：沒有 probe 的 Deployment 等於宣告
# 「進程活著就算健康」—— 那正是 2026-07 兩次事故的根本誤判。
#
# 離開碼：0 = 通過；1 = 有違規

set -uo pipefail

CHART="${CHART:-deploy/helm/payment-api}"
RELEASE="${RELEASE:-payment-api}"
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.16.4}"
FAIL=0

# 本機有 helm 就直接用，CI 上沒有就走容器
if command -v helm >/dev/null 2>&1; then
  helm_run() { helm "$@"; }
else
  helm_run() {
    docker run --rm -v "$PWD/$(dirname "$CHART"):/charts" "$HELM_IMAGE" \
      "$(echo "$@" | sed "s#$CHART#/charts/$(basename "$CHART")#g")"
  }
fi

echo
echo "─────────────────────────────────────────────"
echo " Kubernetes / Helm 檢查"
echo "─────────────────────────────────────────────"

RENDERED="$(mktemp)"; trap 'rm -f "$RENDERED"' EXIT

# ── K1 ───────────────────────────────────────────────────────────
if helm_run lint "$CHART" >/dev/null 2>&1; then
  echo "  ✓ K1 helm lint"
else
  echo "  ✗ K1 helm lint 失敗："
  helm_run lint "$CHART" 2>&1 | sed 's/^/        /'
  FAIL=1
fi

# ── K2 ───────────────────────────────────────────────────────────
if helm_run template "$RELEASE" "$CHART" > "$RENDERED" 2>/dev/null && [ -s "$RENDERED" ]; then
  echo "  ✓ K2 chart 可算繪（$(grep -c '^kind:' "$RENDERED") 個資源）"
else
  echo "  ✗ K2 chart 算繪失敗："
  helm_run template "$RELEASE" "$CHART" 2>&1 | tail -5 | sed 's/^/        /'
  FAIL=1
fi

# ── K3 + K4 ──────────────────────────────────────────────────────
if [ -s "$RENDERED" ]; then
  python3 - "$RENDERED" <<'PY'
import sys, yaml
path = sys.argv[1]
docs = [d for d in yaml.safe_load_all(open(path)) if d]
rc = 0

if not docs:
    print("  ✗ K3 沒有算繪出任何文件"); sys.exit(1)

for d in docs:
    name = d.get("metadata", {}).get("name", "<unnamed>")
    if not d.get("apiVersion") or not d.get("kind"):
        print(f"  ✗ K3 {name} 缺少 apiVersion 或 kind"); rc = 1
print(f"  ✓ K3 {len(docs)} 個文件皆為合法的 Kubernetes 物件："
      + ", ".join(sorted({d['kind'] for d in docs})))

missing = []
for d in docs:
    if d.get("kind") != "Deployment":
        continue
    for c in d["spec"]["template"]["spec"]["containers"]:
        for probe in ("livenessProbe", "readinessProbe"):
            if probe not in c:
                missing.append(f"{d['metadata']['name']}/{c['name']} 缺少 {probe}")
if missing:
    for m in missing:
        print(f"  ✗ K4 {m}")
    rc = 1
else:
    print("  ✓ K4 每個容器都宣告了 liveness 與 readiness probe")
sys.exit(rc)
PY
  [ $? -ne 0 ] && FAIL=1
fi

echo "─────────────────────────────────────────────"
if [ "$FAIL" -eq 0 ]; then
  echo "✅ Helm chart 與 manifest 檢查通過。"
else
  echo "❌ 有問題 —— 修正後再合併。"
fi
exit "$FAIL"
