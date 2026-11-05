#!/usr/bin/env python3
"""
Lightweight Mock Webhook Receiver for testing RW3-DirectSync (F5 Webhook Export)
Listens on http://0.0.0.0:8080/api/rw3/metrics and prints incoming JSON payloads.
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import sys

PORT = 8080

class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_length)

        print("\n" + "="*60)
        print(f"📥 [RECEIVE] HTTP POST {self.path} ({len(post_data)} bytes)")
        print(f"Client: {self.client_address[0]}")
        print("="*60)

        try:
            payload = json.loads(post_data.decode('utf-8'))
            print(json.dumps(payload, indent=2))
            hr_count = payload.get('heart_rate_count', 0)
            sleep_count = payload.get('sleep_stage_count', 0)
            print(f"\n✅ Summary: {hr_count} Heart Rate record(s), {sleep_count} Sleep stage(s) received.")
        except Exception as e:
            print("Raw body:", post_data.decode('utf-8', errors='ignore'))
            print("JSON Parse error:", e)

        # Respond with HTTP 200 OK to signal successful sync
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{"status":"ok","message":"Metrics recorded successfully"}')

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain')
        self.end_headers()
        self.wfile.write(b"RW3-DirectSync Mock Webhook Server is running.\nSend HTTP POST to /api/rw3/metrics")

    def log_message(self, format, *args):
        # Suppress default server log lines to keep output clean
        pass

if __name__ == '__main__':
    server_address = ('', PORT)
    httpd = HTTPServer(server_address, WebhookHandler)
    print(f"🚀 Mock Webhook Server listening on http://0.0.0.0:{PORT}/api/rw3/metrics")
    print("Press Ctrl+C to stop.\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server.")
        httpd.server_close()

