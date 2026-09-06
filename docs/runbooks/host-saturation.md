# Runbook — Host resource saturation

**English** | [繁體中文](host-saturation.zh-TW.md)

> **Alerts**: `HostCpuSaturated` (>85%, 10m) · `HostLoadHigh` (>1.5/core, 10m) · `HostMemoryLow` (<15%, 10m) · warning

## Baseline for this host
`orion-dev`: Oracle Cloud aarch64, **2 vCPU / 11 GiB / swap = 0**.

**The zero swap matters**: there is no cushion at all under memory pressure.
When memory runs out, it runs out hard.

## First three minutes
```bash
uptime                                  # load average ÷ 2 = load per core
top -b -n 1 | head -15                  # who is eating CPU
ps aux --sort=-%mem | head -8           # who is eating memory
free -h
```

## Triage
| What you see | What it means |
|---|---|
| Load ≈ core count, one java process pinned | Very likely GC → [gc-death-spiral](gc-death-spiral.md) |
| Load high but CPU is not | I/O wait → `iostat -x 2 3`, look at the disk |
| Memory held by buff/cache | Usually normal — read `MemAvailable`, not `free` |
| Several containers together | `docker stats --no-stream` to find the source |

## Stop the bleeding
```bash
docker stats --no-stream                 # first check it is not the monitoring stack itself
# Non-essential containers can be paused: docker compose stop <service>
```

## Follow-up
- [ ] This host simultaneously runs the order generator, two JVMs, the monitoring
      stack and a frontend dev server. Capacity planning should evaluate splitting
      onto a second instance.
