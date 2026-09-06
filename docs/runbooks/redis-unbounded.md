# Runbook — Redis memory and eviction

**English** | [繁體中文](redis-unbounded.zh-TW.md)

> **Alerts**: `RedisIsUnbounded` · `RedisMemoryHigh` · `RedisEvictionSpike`

## Why `RedisIsUnbounded` is a warning and not an info

`maxmemory = 0` means Redis will keep growing until it has consumed all host memory.

**This is the same defect class as incident #1 in July 2026**: things go in,
nothing comes out, there is no capacity ceiling and no eviction mechanism. The
only difference is that one happened in a JVM heap and the other in Redis's
process memory. **TTL and eviction policy are Redis's version of "bounded".**

## First three minutes
```bash
redis-cli info memory | grep -E "used_memory_human|maxmemory_human|maxmemory_policy"
redis-cli info stats  | grep -E "evicted_keys|keyspace_hits|keyspace_misses"
redis-cli dbsize
redis-cli --bigkeys                      # find abnormally large keys
redis-cli info keyspace                  # how many keys per db carry a TTL
```

**Keys without a TTL are the prime suspect**: `expires` far below `keys` means
a large population that never expires.

## The fix
```bash
# Set a ceiling and an eviction policy (takes effect immediately, no restart)
redis-cli config set maxmemory 512mb
redis-cli config set maxmemory-policy allkeys-lru

# Persist it, otherwise it is lost on restart
redis-cli config rewrite
```

### Choosing an eviction policy
| Policy | Behaviour | Fits |
|---|---|---|
| `noeviction` | Refuses writes when full (returns an OOM error) | Queues that must not lose data |
| `allkeys-lru` | Evicts the least recently used key | **Pure cache** (most cases) |
| `volatile-lru` | Only evicts keys that have a TTL | Cache mixed with durable data |
| `volatile-ttl` | Evicts the soonest-to-expire first | Explicit time-to-live semantics |

**Measured on this host** (`maxmemory 3mb`):
- `noeviction`: writes fail with `OOM command not allowed` after 7,393 keys — **denial of service**
- `allkeys-lru`: a further 8,000 writes all succeeded, `evicted_keys` reached 8,272, memory held flat at 3.00M

## What `RedisEvictionSpike` means
Eviction itself is healthy — it means the ceiling is working. But **a high
eviction rate means the capacity is too small**: hit rate falls and the pressure
is pushed onto MySQL behind it. The response is to add capacity or revisit the
TTL strategy, **not** to switch the policy back to `noeviction`.

## Follow-up
- [x] 2026-09-06: bounded on this host — `maxmemory 256mb`, `allkeys-lru`, persisted with `config rewrite`. Nothing in this repository writes to Redis (it exists as a monitored dependency and for the eviction measurements above), so a pure-cache policy is the right default; 256 MB is ~200× current use (1.3 MB) on an 11 GiB host with no swap.
- [ ] Every class of key should have a deliberate TTL decision — including a stated reason where the answer is "none"
