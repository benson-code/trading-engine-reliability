# Runbook — 磁碟容量

> **對應告警**：`HostDiskLow` (<15%) · `DiskWillFillIn24h` · `DiskWillFillIn7d`

## 預測性告警的意義
`DiskWillFillIn24h` 用 `predict_linear` 以過去 6 小時的斜率外推。
它不是問「現在滿了嗎」，而是問「**多久之後會滿**」——
在還有時間處理的時候通知，這就是 JD 說的「容量規劃」。

## 立即確認
```bash
df -h /
du -xh --max-depth=2 / 2>/dev/null | sort -rh | head -20

# 本專案的三個常見成長點
du -sh /var/lib/mysql                                  # ① MySQL 資料（orders 表持續成長）
docker system df                                       # ② Docker image / volume
du -sh /var/log/journal                                # ③ systemd journal
```

## 止血（由安全到激進）
```bash
docker system prune -f                    # 移除停止的容器與 dangling image
sudo journalctl --vacuum-time=7d          # 收斂 journal
docker volume ls -qf dangling=true | xargs -r docker volume rm
```

⚠️ **不要**直接刪 `binance_test_db.orders` 的資料 ——
它是事故證據鏈與 C4「歷史不可變性」檢查的基準。
要縮減請先跑 `tools/check-db-integrity.sh` 建立 baseline。

## 事後
- [ ] Prometheus retention 目前 30d，評估是否過長
- [ ] orders 表已達 3,700 萬筆，評估分區或歸檔策略
