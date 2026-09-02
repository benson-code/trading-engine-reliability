# Runbook — 告警處理 SOP

每一條 Prometheus 告警規則都帶有 `runbook_url`，指向本目錄下的對應文件。

**原則：沒有 SOP 的告警不應該存在。** 半夜被叫起來的人需要的是
「現在做什麼」，不是「這個指標是什麼意思」。

## 文件結構

每份 runbook 固定六段：

| 段落 | 回答的問題 |
|---|---|
| 觸發條件 | 什麼情況會叫？閾值從哪來？ |
| 影響 | 使用者現在受到什麼影響？ |
| 立即確認 | 前三分鐘要打哪些指令？ |
| 止血 | 怎麼讓影響停止？（可能不等於修好） |
| 根因調查 | 止血之後往哪裡挖？ |
| 事後 | 要不要寫 RCA？要補什麼防線？ |

## 索引

| Runbook | 對應告警 | 嚴重度 |
|---|---|---|
| [service-down](service-down.md) | ServiceDown | critical |
| [service-slow](service-slow.md) | ServiceSlowResponse | warning |
| [host-down](host-down.md) | HostDown | critical |
| [engine-not-progressing](engine-not-progressing.md) | EngineNotProgressing / EngineWorkerStopped / DatabaseWriteStalled | critical |
| [gc-death-spiral](gc-death-spiral.md) | JvmGcTimeRatioHigh / JvmFullGcRateHigh / JvmOldGenHigh / JvmHeapGrowthUnbounded | critical |
| [jstat-attach-failed](jstat-attach-failed.md) | JstatAttachFailed | critical |
| [host-saturation](host-saturation.md) | HostCpuSaturated / HostLoadHigh / HostMemoryLow | warning |
| [disk-capacity](disk-capacity.md) | HostDiskLow / DiskWillFillIn24h / DiskWillFillIn7d | warning |
| [redis-unbounded](redis-unbounded.md) | RedisIsUnbounded / RedisMemoryHigh / RedisEvictionSpike | warning |
| [redis-down](redis-down.md) | RedisDown | critical |
| [mysql-down](mysql-down.md) | MysqlDown | critical |
| [mysql-saturation](mysql-saturation.md) | MysqlConnectionsHigh / MysqlSlowQueriesRising | warning |

## 通用原則

1. **保留現場優先於快速復原**（除非有使用者正在受影響）。
   重啟會銷毀證據。2026-07-14 的事故之所以能查出根因，
   是因為進程被保留了 7 天沒有重啟。
   → 見 [PRESERVE_SCENE.md](../incident-2026-07-14-gc-death-spiral/evidence/PRESERVE_SCENE.md)

2. **診斷工具失效本身是一項證據**，不是「沒有資料」。

3. **服務自報的健康狀態在事故當下通常不可信**，
   改用外部可觀測的事實：DB 寫入量、黑箱探測、計數器斜率。
