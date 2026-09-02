# 服務憑證管理

## 為什麼不寫在程式裡

這是個**公開** repo。任何寫在原始碼裡的密碼，等同於公布給所有人。

`DBOrderRepository` 現在的行為是：`DB_PASSWORD` 未設定就**丟出例外、拒絕啟動**。
沒有預設值、沒有 fallback。寧可大聲失敗，也不要靜默地連上一個用寫死密碼的資料庫。

## 為什麼不寫在 systemd unit 裡

unit 檔的權限是 `0644` —— **主機上任何使用者都讀得到**。
把 `Environment=DB_PASSWORD=xxx` 寫在 unit 裡，只是把密碼從 git 搬到 `/etc` 而已。

正確做法是 `EnvironmentFile` 指向一個 `0640 root:<service-group>` 的檔案。

## 部署步驟

```bash
# 1. 建立憑證檔（不進版控）
sudo install -m 0640 -o root -g ubuntu \
     deploy/systemd/binance-trading-engine.env.example \
     /etc/binance-trading-engine.env
sudo $EDITOR /etc/binance-trading-engine.env      # 填入真實密碼

# 2. 部署 unit
sudo cp deploy/systemd/binance-trading-engine.service.example \
        /etc/systemd/system/binance-trading-engine.service
sudo systemctl daemon-reload
sudo systemctl restart binance-trading-engine

# 3. 驗證：unit 檔裡不該出現任何密碼
sudo grep -i password /etc/systemd/system/binance-trading-engine.service || echo "OK — 乾淨"
```

## 其他元件的憑證

| 元件 | 憑證位置 | 權限 |
|---|---|---|
| trading-engine (systemd) | `/etc/binance-trading-engine.env` | `0640 root:ubuntu` |
| `tools/check-db-integrity.sh` | `DB_PASSWORD` 環境變數，或 `deploy/observability/mysqld/.my.cnf` | `0600` |
| `tools/preserve-scene.sh` | 同上（沒有憑證時跳過 DB 採集，不中止）| `0600` |
| mysqld_exporter | `deploy/observability/mysqld/.my.cnf` | `0600` |

全部都在 `.gitignore` 裡。範本檔（`*.example`）進版控，實際憑證不進。

## 腳本為什麼用 `--defaults-extra-file` 而不是 `-p`

```bash
mysql -u user -p"$PASSWORD"      # ✗ 密碼會出現在 `ps` 的輸出裡
mysql --defaults-extra-file=...  # ✓ 只有檔案權限管得到
```

同一台機器上的任何使用者都能 `ps aux` 看到命令列參數。
