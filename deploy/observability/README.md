# 可觀測性平台 — orion-dev

對標 SRE 職責建置的監控平台：**採集 → 儲存 → 告警路由 → 處理 SOP → 視覺化**，
每一層都有對應的 CI 閘門。

---

## 架構

```
Layer 4  Grafana ──── SRE 總覽 · 容量規劃 · JVM 事故重現
                            ↑
Layer 3  Alertmanager ─── 分級路由 · 4 條抑制規則 · → Runbook SOP
                            ↑
Layer 2  Prometheus ───── 24 條規則 / 6 組 · 30 天保留
                            ↑
Layer 1  採集 ─── node_exporter(+textfile) · blackbox · mysqld · redis
                            ↑
Layer 0  被監控 ── payment-api · trading-engine · MySQL · Redis · 主機
```

### 網路架構決策

**全部服務使用 `network_mode: host`。**

本機（Oracle Cloud）的 iptables 會擋掉 container → host 的流量，
而 MySQL 與 Redis 都只綁 `127.0.0.1`。host 模式讓容器與主機共用網路
命名空間，直接走 `127.0.0.1` 通訊。

這同時解除了原本被迫只能用 textfile collector 的限制。

**安全**：exporter 一律綁 `127.0.0.1`（它們會吐出 DB / cache 的內部狀態），
只有 UI 層（Prometheus / Alertmanager / Grafana）對外開放。

---

## 服務與連接埠

| 服務 | 埠 | 綁定 | 用途 |
|---|---|---|---|
| Prometheus | 9090 | 0.0.0.0 | 採集與規則評估 |
| Alertmanager | 9093 | 0.0.0.0 | 告警路由與收斂 |
| Grafana | 3001 | 0.0.0.0 | 視覺化（3000 被 next dev 佔用）|
| node_exporter | 9100 | 0.0.0.0 | 主機指標 + textfile collector |
| blackbox_exporter | 9115 | 127.0.0.1 | 黑箱探測 |
| mysqld_exporter | 9104 | 127.0.0.1 | MySQL 指標 |
| redis_exporter | 9121 | 127.0.0.1 | Redis 指標 |
| alert-sink | 9199 | 127.0.0.1 | 告警送達驗證 |

---

## 快速開始

```bash
# 設定 MySQL 認證（範本已在版控，實際檔案不進版控）
cp mysqld/.my.cnf.example mysqld/.my.cnf
$EDITOR mysqld/.my.cnf && chmod 600 mysqld/.my.cnf

make obs-up          # 啟動全部 7 個服務 + 告警接收端
make obs-status      # 容器 / 採集目標 / 進行中告警 總覽
make obs-validate    # 驗證所有設定語法
make obs-reload      # 熱載入規則變更（不重啟）
make obs-alerts      # 看實際送達的通知
make runbooks        # 檢查告警 ↔ SOP 覆蓋率
```

---

## 告警設計

### 六層，各回答一個不同的問題

| 組 | 問題 | 條數 |
|---|---|---|
| `availability` | 使用者現在打得到嗎？（黑箱）| 3 |
| `work-progress` | 工作有在前進嗎？（事故 #2）| 3 |
| `jvm-gc` | JVM 還健康嗎？（事故 #1）| 4 |
| `saturation` | 資源快用完了嗎？（USE）| 4 |
| `capacity` | 多久之後會用完？（predict_linear）| 4 |
| `dependencies` | 相依元件還在嗎？| 6 |

### 閾值來源

**所有 JVM 閾值都由 2026-07 事故的實測值反推，不是抄來的預設值：**

| 指標 | 閾值 | 事故實測值 |
|---|---|---|
| Full GC 佔存活時間比 | > 10% | **70%**（491,218s / 698,732s）|
| 老年代使用率 | > 85% | **99.99%** |
| Full GC 累計 | 速率 > 0.1/s | **114,879 次** |
| 訂單產生速率 | 連續 10 分鐘 = 0 | 正常 **1,198 筆/分**（≈20/秒）|

### 告警收斂

Alertmanager 設有 4 條抑制規則，避免一次故障噴出數十則通知：

1. `HostDown` → 抑制該主機上所有其他告警
2. 同服務的 `critical` → 抑制 `warning`
3. `JvmGcTimeRatioHigh` → 抑制 `JvmOldGenHigh`（症狀鏈收斂）
4. `ServiceDown` → 抑制 `EngineNotProgressing`

> 告警疲勞比沒有告警更危險。值班的人如果每晚收 200 則，
> 第 201 則真的事故就會被忽略。

---

## 處理 SOP

**每一條告警都必須有 `runbook_url`，由 CI 強制檢查。**
見 [`docs/runbooks/`](../../docs/runbooks/README.md)（13 份，覆蓋 24 條告警）。

`tools/check-alert-runbooks.sh` 檢查三件事：
- R1 每條 alert 都有 `runbook_url`
- R2 該 URL 指向的檔案真的存在
- R3 沒有孤兒 runbook（存在但沒有告警引用）

---

## 儀表板

| 儀表板 | 內容 |
|---|---|
| **SRE 總覽** | 可用性 · 工作進度 · JVM · 相依元件 · 主機資源（5 分區 / 31 面板）|
| **容量規劃** | 磁碟 / heap / Redis / 主機的 `predict_linear` 外推（4 分區 / 19 面板）|
| **Trading Engine JVM** | 事故 #1 的專用重現視圖 |

---

## CI 閘門

`.github/workflows/ci.yml` 的 `observability` job：

| 檢查 | 擋什麼 |
|---|---|
| `promtool check config/rules` | 語法錯的規則會讓**整份 rule file 載入失敗** —— 一個 typo 可以讓所有告警靜默 |
| `amtool check-config` | 路由設定錯誤導致告警送不出去 |
| `check-alert-runbooks.sh` | 沒有處理 SOP 的告警 |
| Grafana JSON 驗證 | 缺 uid / 無面板的儀表板 |
| 憑證檢查 | `.my.cnf` 意外進版控 |

---

## 平台上線第一天抓到的問題

| 告警 | 實際狀況 |
|---|---|
| `EngineWorkerStopped` | 訂單產生器自 **2026-08-26 06:02** 起停擺，**七天無人察覺** |
| `EngineNotProgressing` | 同上，DB 寫入從 1,198 筆/分 塌到 0 |
| `RedisIsUnbounded` | Redis `maxmemory=0` / `noeviction` —— 與事故 #1 同一缺陷類別 |

前兩條是 **2026-07 事故 #2 的完整重演**，journal 證據：

```
06:02:02  [DB] Save failed ... Communications link failure
06:02:13  systemd: Stopping binance-trading-engine.service
06:02:13  Main process exited, code=exited, status=143/n/a    ← 143 = 128+15 = SIGTERM
06:02:13  systemd: Started binance-trading-engine.service     ← systemd 成功重啟
06:02:14  Engine : STOPPED — press RUN in the UI to start     ← 但產生器沒有恢復
```

**這七天之中，所有既有的健康檢查全部通過**：
`systemctl` 回報 `active (running)`、8092/8093 都有 bind、
`/api/v1/status` 回 200。唯一能發現它的訊號是**業務產出的速率**。

> 這就是本平台存在的理由：
> 把可靠度訊號從「進程還活著嗎」改成「工作還在前進嗎」。
