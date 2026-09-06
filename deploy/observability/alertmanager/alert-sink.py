#!/usr/bin/env python3
"""alert-sink.py — Alertmanager webhook 的最後一哩：記錄、推 LINE、打心跳。

存在的理由：告警系統最容易出的問題不是「規則沒寫」，而是「規則寫了、也 fire 了，
但通知沒有真的送到人手上」。這支東西讓那條鏈路的最後一哩變成可驗證、可觀測的。

三個出口：
  delivered.log   每一次投遞都落地，不論成敗 —— 這是鏈路的稽核軌跡
  LINE            /alert /critical /capacity → 廣播給官方帳號的好友（你自己）
  heartbeat       /heartbeat → GET $HEARTBEAT_URL（Healthchecks.io 之類）
                  DeadMansSwitch 每 5 分鐘來一次；外部服務逾時未收到才通知你。
                  心跳**不**推 LINE —— 每 5 分鐘一則會把免費額度吃光，也會把人訓練成無視。

它自己也暴露 /metrics，讓 Prometheus 看得到「LINE 到底送出去了沒」。
Alertmanager 的 notifications_failed_total 只知道 webhook 有沒有回 200，
不知道 webhook 後面那一段有沒有真的到人手上。

設定全部走環境變數（systemd EnvironmentFile），token 不進 repo：
  LINE_CHANNEL_ACCESS_TOKEN   必要，否則 LINE 出口只記錄不發送
  HEARTBEAT_URL               選用，未設時心跳只記錄
  LINE_HOURLY_CAP             每小時最多發幾則 LINE（預設 30）。超過就壓下並計數。
"""
import collections
import datetime
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = os.environ.get("NOTIFIER_LOG",
                     "/home/ubuntu/qa/deploy/observability/alertmanager/delivered.log")
LINE_TOKEN = os.environ.get("LINE_CHANNEL_ACCESS_TOKEN", "").strip()
HEARTBEAT_URL = os.environ.get("HEARTBEAT_URL", "").strip()
LINE_HOURLY_CAP = int(os.environ.get("LINE_HOURLY_CAP", "30"))
BROADCAST_URL = "https://api.line.me/v2/bot/message/broadcast"
LINE_MAX_CHARS = 4900        # 單則上限 5000，留餘裕
HTTP_TIMEOUT = 8             # 短於 Alertmanager 的 webhook 逾時，讓失敗回到 AM 自己的重試

# 標籤集合全部是固定的，所以計數器的基數有界：
#   route   ∈ KNOWN_ROUTES（別的路徑一律歸到 "other"）
#   channel ∈ {log, line, heartbeat}
#   outcome ∈ {ok, error, suppressed, skipped}
KNOWN_ROUTES = ("alert", "critical", "capacity", "heartbeat")
_lock = threading.Lock()
_deliveries = collections.Counter()
_last_success = {"line": 0.0, "heartbeat": 0.0}
# BOUNDED-BY: maxlen = LINE_HOURLY_CAP。只保留最近 N 次送出時間，用來做滑動視窗限流。
_line_sent_at = collections.deque(maxlen=max(LINE_HOURLY_CAP, 1))

SEVERITY_MARK = {"critical": "🔴", "warning": "🟠", "none": "💓"}


def _now():
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _log(lines):
    with _lock, open(LOG, "a", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def _count(route, channel, outcome):
    with _lock:
        _deliveries[(route, channel, outcome)] += 1
        if outcome == "ok" and channel in _last_success:
            _last_success[channel] = time.time()


def format_alerts(payload):
    """把一組 alerts 變成一則人看得懂的訊息。"""
    out = []
    for a in payload.get("alerts", []):
        labels = a.get("labels", {})
        ann = a.get("annotations", {})
        sev = labels.get("severity", "?")
        if a.get("status") == "resolved":
            head = f"✅ 已解除  {labels.get('alertname', '?')}"
        else:
            head = f"{SEVERITY_MARK.get(sev, '⚪')} {sev.upper()}  {labels.get('alertname', '?')}"
        body = [head]
        if labels.get("service"):
            body.append(f"服務：{labels['service']}")
        if ann.get("summary"):
            body.append(ann["summary"])
        if ann.get("runbook_url"):
            body.append(f"Runbook：{ann['runbook_url']}")
        out.append("\n".join(body))
    return "\n\n".join(out)


def line_broadcast(text):
    """回傳 (outcome, detail)。outcome ∈ ok / error / skipped / suppressed。"""
    if not LINE_TOKEN:
        return "skipped", "LINE_CHANNEL_ACCESS_TOKEN 未設定"
    now = time.time()
    with _lock:
        if len(_line_sent_at) == _line_sent_at.maxlen and now - _line_sent_at[0] < 3600:
            return "suppressed", f"已達每小時 {LINE_HOURLY_CAP} 則上限"
    chunks = [text[i:i + LINE_MAX_CHARS] for i in range(0, len(text), LINE_MAX_CHARS)][:5]
    req = urllib.request.Request(
        BROADCAST_URL,
        data=json.dumps({"messages": [{"type": "text", "text": c} for c in chunks]}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {LINE_TOKEN}"},
        method="POST")
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            with _lock:
                _line_sent_at.append(now)
            return "ok", f"HTTP {r.status}"
    except urllib.error.HTTPError as e:
        return "error", f"HTTP {e.code} {e.read().decode('utf-8', 'replace')[:200]}"
    except Exception as e:  # URLError, timeout
        return "error", repr(e)


def heartbeat_ping():
    if not HEARTBEAT_URL:
        return "skipped", "HEARTBEAT_URL 未設定"
    try:
        with urllib.request.urlopen(HEARTBEAT_URL, timeout=HTTP_TIMEOUT) as r:
            return "ok", f"HTTP {r.status}"
    except Exception as e:
        return "error", repr(e)


def render_metrics():
    out = []
    out.append("# HELP notifier_deliveries_total 最後一哩投遞次數，依路徑、通道、結果。")
    out.append("# TYPE notifier_deliveries_total counter")
    with _lock:
        for (route, channel, outcome), n in sorted(_deliveries.items()):
            out.append(f'notifier_deliveries_total{{route="{route}",channel="{channel}",'
                       f'outcome="{outcome}"}} {n}')
        out.append("# HELP notifier_last_success_timestamp_seconds 該通道最後一次成功送出的時間。")
        out.append("# TYPE notifier_last_success_timestamp_seconds gauge")
        for ch, ts in _last_success.items():
            out.append(f'notifier_last_success_timestamp_seconds{{channel="{ch}"}} {int(ts)}')
    out.append("# HELP notifier_channel_configured 通道有沒有設定憑證／URL（0 = 只記錄不發送）。")
    out.append("# TYPE notifier_channel_configured gauge")
    out.append(f'notifier_channel_configured{{channel="line"}} {1 if LINE_TOKEN else 0}')
    out.append(f'notifier_channel_configured{{channel="heartbeat"}} {1 if HEARTBEAT_URL else 0}')
    out.append("# HELP notifier_line_hourly_cap LINE 每小時上限。")
    out.append("# TYPE notifier_line_hourly_cap gauge")
    out.append(f"notifier_line_hourly_cap {LINE_HOURLY_CAP}")
    return "\n".join(out) + "\n"


class Handler(BaseHTTPRequestHandler):
    def _reply(self, code, body, ctype="text/plain; charset=utf-8"):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/metrics":
            self._reply(200, render_metrics(), "text/plain; version=0.0.4; charset=utf-8")
        elif self.path == "/healthz":
            self._reply(200, "ok")
        else:
            self._reply(404, "not found")

    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {"alerts": [], "parse_error": raw.decode("utf-8", "replace")[:200]}

        route = self.path.lstrip("/").split("?")[0]
        if route not in KNOWN_ROUTES:
            route = "other"
        ts = _now()

        # 1) 稽核軌跡：不論後面成不成功，先落地
        lines = []
        for a in payload.get("alerts", [{}]):
            labels, ann = a.get("labels", {}), a.get("annotations", {})
            lines.append(f"[{ts}] route={route} status={a.get('status', '?')} "
                         f"severity={labels.get('severity', '?')} "
                         f"alert={labels.get('alertname', '?')} "
                         f"service={labels.get('service', '-')}")
            lines.append(f"          summary : {ann.get('summary', '-')}")
            lines.append(f"          runbook : {ann.get('runbook_url', '-')}")
        _count(route, "log", "ok")

        # 2) 出口
        if route == "heartbeat":
            outcome, detail = heartbeat_ping()
            lines.append(f"          heartbeat: {outcome} ({detail})")
            _count(route, "heartbeat", outcome)
        else:
            outcome, detail = line_broadcast(format_alerts(payload))
            lines.append(f"          line    : {outcome} ({detail})")
            _count(route, "line", outcome)

        _log(lines)
        # 回 200 讓 Alertmanager 不重送同一組（LINE 失敗有自己的指標與告警）；
        # 但通道回 error 時回 502，讓 AM 用它自己的重試把訊息送到。
        self._reply(502 if outcome == "error" else 200, outcome)

    def log_message(self, *args):
        pass   # 不把 access log 噴到 journal


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9199
    print(f"alert-sink listening on 127.0.0.1:{port} → {LOG} | "
          f"line={'on' if LINE_TOKEN else 'OFF'} heartbeat={'on' if HEARTBEAT_URL else 'OFF'} "
          f"cap={LINE_HOURLY_CAP}/h", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
