#!/usr/bin/env python3
"""最小告警接收端 —— 把 Alertmanager 送來的 webhook 寫成日誌。

存在的理由：告警系統最容易出的問題不是「規則沒寫」，
而是「規則寫了、也 fire 了，但通知沒有真的送到人手上」。
這支東西讓那條鏈路的最後一哩變成可驗證的。
"""
import json, sys, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer

LOG = "/home/ubuntu/qa/deploy/observability/alertmanager/delivered.log"

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        try:
            payload = json.loads(body)
        except Exception:
            payload = {"parse_error": body.decode('utf-8', 'replace')}

        ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        route = self.path.lstrip('/')
        with open(LOG, "a") as f:
            for a in payload.get("alerts", [{}]):
                labels = a.get("labels", {})
                ann    = a.get("annotations", {})
                f.write(
                    f"[{ts}] route={route} status={a.get('status','?')} "
                    f"severity={labels.get('severity','?')} "
                    f"alert={labels.get('alertname','?')} "
                    f"service={labels.get('service','-')}\n"
                    f"          summary : {ann.get('summary','-')}\n"
                    f"          runbook : {ann.get('runbook_url','-')}\n"
                )
        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')

    def log_message(self, *args):
        pass   # 不要把 access log 噴到 stdout

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9199
    print(f"alert-sink listening on 127.0.0.1:{port} → {LOG}", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
