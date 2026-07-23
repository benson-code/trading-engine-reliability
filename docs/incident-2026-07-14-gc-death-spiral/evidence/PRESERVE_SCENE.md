# Crime Scene Preservation Notice

**Policy:** DO NOT kill / restart / systemctl stop / heap-dump-force on PID 26810  
**Status:** LIVE CRIME SCENE  
**Main PID:** 26810 (`binance-trading-engine.service`)  
**Last re-verified:** 2026-07-21T09:38:45Z  

## Allowed (non-destructive)
- `jstat` (hsperfdata, no attach socket required)
- `ps` / `/proc` / `top` / `systemctl status`
- `journalctl` (read-only)
- short-timeout `curl` probes
- MySQL SELECT (read-only)

## Forbidden until owner approval
- `kill` / `kill -9` / `systemctl stop|restart`
- force `jmap` / `jcmd GC.heap_dump` (may worsen OOME)
- code redeploy to same service
- `rm` of this evidence directory

## Snapshot locations
- Baseline: `/home/ubuntu/qa-incident-2026-07-14/` (2026-07-14)
- Live re-capture: `snapshot-2026-07-21T0938Z/`
