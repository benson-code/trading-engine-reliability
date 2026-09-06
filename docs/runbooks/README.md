# Runbooks — alert response procedures

**English** | [繁體中文](README.zh-TW.md)

Every Prometheus alert rule carries a `runbook_url` pointing at a document in
this directory.

**The principle: an alert without a procedure should not exist.** Somebody woken
at 3am needs to know *what to do now*, not *what this metric means*.

## Document structure

Every runbook has the same six sections:

| Section | The question it answers |
|---|---|
| Triggers | What fires this? Where did the threshold come from? |
| Impact | What are users experiencing right now? |
| First three minutes | Which commands do I run immediately? |
| Stop the bleeding | How do I make the impact stop? (not necessarily a fix) |
| Root-cause investigation | Once it has stopped, where do I dig? |
| Follow-up | Does this need an RCA? What defence is missing? |

## Index

| Runbook | Alerts | Severity |
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
| [slo-burn-rate](slo-burn-rate.md) | SLOFastBurn* / SLOSlowBurn* (6 rules) | critical / warning |

## Standing principles

1. **Preserving the scene beats fast recovery** — unless users are actively
   affected. A restart destroys the evidence. The 2026-07-14 incident was only
   solvable because the process was left running, untouched, for seven days.
   → see [PRESERVE_SCENE.md](../incident-2026-07-14-gc-death-spiral/evidence/PRESERVE_SCENE.md)

2. **A diagnostic tool failing is itself evidence**, not an absence of data.

3. **A service's self-reported health is usually untrustworthy during an
   incident.** Use externally observable facts instead: database write volume,
   black-box probes, counter slopes.
