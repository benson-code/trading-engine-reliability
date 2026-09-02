# Runbook — Redis 記憶體與淘汰

> **對應告警**：`RedisIsUnbounded` · `RedisMemoryHigh` · `RedisEvictionSpike`

## `RedisIsUnbounded` 為什麼是 warning 而不是 info

`maxmemory = 0` 表示 Redis 會一直長到把主機記憶體吃光為止。

**這與 2026-07 事故 #1 是同一個缺陷類別**：
只進不出、沒有容量上限、沒有淘汰機制。
差別只在一個發生在 JVM heap，一個發生在 Redis 的行程記憶體。
TTL 與 eviction policy 就是 Redis 版的「有界」。

## 立即確認
```bash
redis-cli info memory | grep -E "used_memory_human|maxmemory_human|maxmemory_policy"
redis-cli info stats  | grep -E "evicted_keys|keyspace_hits|keyspace_misses"
redis-cli dbsize
redis-cli --bigkeys                      # 找出異常大的 key
redis-cli info keyspace                  # 各 db 有多少 key 帶 TTL
```

**沒有 TTL 的 key 是主要嫌疑**：`expires` 遠小於 `keys` 表示大量 key 永不過期。

## 修法
```bash
# 設定上限與淘汰策略（立即生效，不需重啟）
redis-cli config set maxmemory 512mb
redis-cli config set maxmemory-policy allkeys-lru

# 持久化，否則重啟後失效
redis-cli config rewrite
```

### 選擇 eviction policy
| policy | 行為 | 適用 |
|---|---|---|
| `noeviction` | 滿了拒絕寫入（回 OOM 錯誤）| 不能掉資料的佇列 |
| `allkeys-lru` | 淘汰最久未使用的 key | **純快取**（多數情境）|
| `volatile-lru` | 只淘汰有 TTL 的 key | 快取與持久資料混用 |
| `volatile-ttl` | 優先淘汰快到期的 | 有明確時效語意 |

**實測對照**（`maxmemory 3mb`，本機驗證）：
- `noeviction`：寫到 7,393 筆回 `OOM command not allowed`，**拒絕服務**
- `allkeys-lru`：再寫 8,000 筆全部成功，`evicted_keys` 累積 8,272，記憶體穩定在 3.00M

## `RedisEvictionSpike` 的意義
淘汰本身是健康的（代表上限有在生效），但**速率過高代表容量不足**：
命中率會下降，壓力轉嫁到後端 MySQL。此時應擴容或檢討 TTL 策略，
而不是把 policy 改回 `noeviction`。

## 事後
- [ ] 每一類 key 都應該有明確的 TTL 決策（含「刻意不設」的理由）
