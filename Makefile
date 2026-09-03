# ══════════════════════════════════════════════════════════════════
#  binance-qa-suite — 操作介面
#
#  這支 Makefile 是這個系統的「前門」：任何人（包含三個月後的自己）
#  只要跑 `make` 就知道這裡能做什麼，不必去讀 README。
# ══════════════════════════════════════════════════════════════════

# ── 變數 ──────────────────────────────────────────────────────────
# ?= 的意思是「如果外面沒給，才用這個預設值」。
# 所以 `make run PORT=9000` 可以覆寫，CI 也能用環境變數注入。
PORT       ?= 8091
MVN        ?= mvn --no-transfer-progress
API_JAR    := payment-api/target/payment-api-qa-framework-1.0.0.jar
OBS_DIR    := deploy/observability

# 讓 make 沒帶參數時顯示 help，而不是執行第一個目標
.DEFAULT_GOAL := help

# ── 建置與測試 ────────────────────────────────────────────────────
.PHONY: build
build: ## 編譯兩個 Java 模組並打包（跳過測試）
	$(MVN) package -DskipTests

.PHONY: test
test: ## 執行全部 Java 測試
	$(MVN) test

.PHONY: check
check: ## 執行 CI 的全部品質閘門
	tools/check-no-secrets.sh
	tools/check-bounded-collections.sh
	tools/check-bounded-collections-ts.sh
	tools/check-alert-runbooks.sh
	tools/check-k8s-manifests.sh

# ── 執行服務 ──────────────────────────────────────────────────────
.PHONY: run
run: $(API_JAR) ## 在前景啟動 payment-api（Ctrl+C 停止）
	PAYMENT_PORT=$(PORT) java -jar $(API_JAR)

# 這一條不是 .PHONY —— 它真的產生一個檔案。
# 只有當 jar 不存在、或原始碼比 jar 新的時候，make 才會重新 build。
# 這就是 make 原本的用途：相依性追蹤。
$(API_JAR): $(shell find payment-api/src/main -name '*.java' 2>/dev/null)
	$(MVN) package -pl payment-api -am -DskipTests

.PHONY: health
health: ## 檢查 payment-api 是否健康
	@curl -sf -m 3 http://localhost:$(PORT)/api/v1/health \
		&& echo "  ✓ payment-api :$(PORT) 健康" \
		|| { echo "  ✗ payment-api :$(PORT) 無回應"; exit 1; }

# ── 可觀測性堆疊 ──────────────────────────────────────────────────
.PHONY: obs-up
obs-up: ## 啟動監控平台（7 個服務）
	@nohup python3 $(OBS_DIR)/alertmanager/alert-sink.py 9199 \
		> $(OBS_DIR)/alertmanager/sink.out 2>&1 & echo "  告警接收端已啟動 (:9199)"
	docker compose -f $(OBS_DIR)/docker-compose.yml up -d
	@echo ""
	@echo "  Prometheus   → http://localhost:9090"
	@echo "  Alertmanager → http://localhost:9093"
	@echo "  Grafana      → http://localhost:3001 (admin/admin)"

.PHONY: obs-down
obs-down: ## 停止監控平台
	docker compose -f $(OBS_DIR)/docker-compose.yml down
	-@pkill -f alert-sink.py && echo "  告警接收端已停止"

.PHONY: obs-status
obs-status: ## 監控平台健康總覽（容器 + 採集目標 + 告警）
	@echo ""
	@echo "  ── 容器 ─────────────────────────────────────────"
	@docker compose -f $(OBS_DIR)/docker-compose.yml ps --format '    {{.Name}}\t{{.Status}}' 2>/dev/null || true
	@echo ""
	@echo "  ── 採集目標 ─────────────────────────────────────"
	@curl -s -m 5 http://127.0.0.1:9090/api/v1/targets 2>/dev/null | jq -r '.data.activeTargets | sort_by(.labels.job) | .[] | "    \(if .health == "up" then "✓" else "✗" end)  \(.labels.job)  \(.labels.instance)"' || echo "    Prometheus 無回應"
	@curl -s -m 5 http://127.0.0.1:9090/api/v1/targets 2>/dev/null | jq -r '.data.activeTargets | "\n    總數 \(length)   up \([.[] | select(.health == "up")] | length)   down \([.[] | select(.health != "up")] | length)"' || true
	@echo ""
	@echo "  ── 進行中的告警 ─────────────────────────────────"
	@curl -s -m 5 http://127.0.0.1:9093/api/v2/alerts 2>/dev/null | jq -r 'if length == 0 then "    （無）" else (.[] | "    \(.labels.severity | ascii_upcase)  \(.labels.alertname)  \(.labels.service // "-")") end' || echo "    Alertmanager 無回應"
	@echo ""

.PHONY: obs-reload
obs-reload: ## 熱載入 Prometheus 設定與告警規則（不重啟容器）
	@docker compose -f $(OBS_DIR)/docker-compose.yml exec -T prometheus \
		promtool check config /etc/prometheus/prometheus.yml >/dev/null \
		&& echo "  ✓ 設定語法正確" || { echo "  ✗ 設定有誤，中止"; exit 1; }
	@curl -sf -X POST http://127.0.0.1:9090/-/reload && echo "  ✓ Prometheus 已熱載入"

.PHONY: obs-validate
obs-validate: ## 驗證 Prometheus / Alertmanager 設定與告警規則語法
	@docker run --rm -v $(PWD)/$(OBS_DIR)/prometheus:/etc/prometheus:ro \
		--entrypoint promtool prom/prometheus:v3.1.0 check config /etc/prometheus/prometheus.yml
	@docker run --rm -v $(PWD)/$(OBS_DIR)/prometheus:/etc/prometheus:ro \
		--entrypoint promtool prom/prometheus:v3.1.0 check rules /etc/prometheus/alerts.yml
	@docker run --rm -v $(PWD)/$(OBS_DIR)/alertmanager:/cfg:ro \
		--entrypoint amtool prom/alertmanager:v0.28.0 check-config /cfg/alertmanager.yml

.PHONY: obs-alerts
obs-alerts: ## 顯示已送達的告警通知
	@tail -40 $(OBS_DIR)/alertmanager/delivered.log 2>/dev/null || echo "  尚無送達紀錄"

.PHONY: runbooks
runbooks: ## 檢查每條告警都有對應的處理 SOP
	tools/check-alert-runbooks.sh

.PHONY: preserve
preserve: ## 事故現場保全（非破壞性採集）
	tools/preserve-scene.sh

# ── 容器 ──────────────────────────────────────────────────────────
IMAGE ?= payment-api:local

.PHONY: docker-build
docker-build: ## 建置 payment-api 容器映像檔
	docker build -f payment-api/Dockerfile -t $(IMAGE) .

.PHONY: docker-run
docker-run: ## 以容器方式啟動 payment-api
	docker run -d --rm --name payment-api -p $(PORT):8091 $(IMAGE)
	@echo "  等待容器變健康..."
	@for i in $$(seq 1 20); do \
		st=$$(docker inspect -f '{{.State.Health.Status}}' payment-api 2>/dev/null); \
		[ "$$st" = "healthy" ] && echo "  ✓ healthy" && exit 0; \
		sleep 2; \
	done; echo "  ✗ 逾時未變健康"; docker logs payment-api; exit 1

.PHONY: docker-stop
docker-stop: ## 停止 payment-api 容器
	-docker stop payment-api

.PHONY: docker-size
docker-size: ## 比較各階段映像檔大小
	@docker images --format '  {{.Repository}}:{{.Tag}}\t{{.Size}}' | grep -E 'payment-api|temurin|maven' || true

# ── Kubernetes / Helm ─────────────────────────────────────────────
KUBECONFIG ?= $(HOME)/.kube/config
KUBECTL    := KUBECONFIG=$(KUBECONFIG) k3s kubectl
NS         ?= payment
RELEASE    ?= payment-api
CHART      := deploy/helm/payment-api

.PHONY: k8s-image
k8s-image: docker-build ## 把本機 image 匯入 k3s 的 containerd（k3s 不用 Docker 的 image store）
	docker save $(IMAGE) | sudo k3s ctr images import -

.PHONY: k8s-deploy
k8s-deploy: ## 用 Helm 部署到 k3s
	helm upgrade --install $(RELEASE) $(CHART) -n $(NS) --create-namespace --wait --timeout 3m
	@echo "  NodePort → http://$$(hostname -I | awk '{print $$1}'):30091/api/v1/health"

.PHONY: k8s-status
k8s-status: ## 叢集 / pod / service / helm release 狀態
	@echo ""
	@echo "  ── 節點 ─────────────────────────────────────────"
	@$(KUBECTL) get nodes --no-headers 2>/dev/null | awk '{printf "    %-12s %-8s %s\n",$$1,$$2,$$5}'
	@echo ""
	@echo "  ── Pod ──────────────────────────────────────────"
	@$(KUBECTL) get pods -n $(NS) --no-headers 2>/dev/null | awk '{printf "    %-32s %-8s restarts=%s\n",$$1,$$3,$$4}'
	@echo ""
	@echo "  ── Service ──────────────────────────────────────"
	@$(KUBECTL) get svc -n $(NS) --no-headers 2>/dev/null | sed 's/^/    /'
	@echo ""
	@echo "  ── Helm ─────────────────────────────────────────"
	@helm list -n $(NS) --no-headers 2>/dev/null | awk '{printf "    %-14s revision=%-3s %s\n",$$1,$$3,$$8}'
	@echo ""

.PHONY: k8s-lint
k8s-lint: ## 驗證 Helm chart：lint、算繪、probe 政策（CI 用的同一條）
	tools/check-k8s-manifests.sh

.PHONY: k8s-diff
k8s-diff: ## 顯示 chart 算繪後的完整 manifest
	@helm template $(RELEASE) $(CHART) -n $(NS)

.PHONY: k8s-drill
k8s-drill: ## 滾動更新演練：持續流量下量測使用者可見的錯誤
	tools/k8s-rollout-drill.sh $(RELEASE) $(NS)

.PHONY: k8s-logs
k8s-logs: ## 追蹤所有副本的日誌
	@$(KUBECTL) logs -n $(NS) -l app.kubernetes.io/name=payment-api --tail=50 -f

.PHONY: k8s-down
k8s-down: ## 移除 Helm release
	-helm uninstall $(RELEASE) -n $(NS)

# ── 資料完整性 ────────────────────────────────────────────────────
.PHONY: integrity
integrity: ## 對持續寫入中的 orders 表執行完整性檢查（C1–C6）
	tools/check-db-integrity.sh

# ── 說明 ──────────────────────────────────────────────────────────
# 這段是 Makefile 世界的通用慣例：掃描自己，把 `## ` 後面的註解印出來。
# 意思是「說明文件」和「程式碼」永遠不會不同步——因為它們是同一行。
.PHONY: help
help: ## 顯示這份說明
	@echo ""
	@echo "  binance-qa-suite — 可用指令"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "    \033[36m%-14s\033[0m %s\n", $$1, $$2}'
	@echo ""
