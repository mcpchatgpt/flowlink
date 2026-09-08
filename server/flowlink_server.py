#!/usr/bin/env python3
"""HTTPS enrollment and signed configuration API for FlowLink."""
from __future__ import annotations
import argparse, hashlib, hmac, json, ssl, threading, time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
from flowlink_core import (APK_PATH, CONFIG_PATH, HEALTH_PATH, STATE_PATH,
    VERSION, FlowLinkError, app_update, authenticate_device, effective_ports,
    enroll_device, load_json)

MAX_BODY = 16 * 1024

class RateLimiter:
    def __init__(self, attempts: int = 30, window: int = 60):
        self.attempts, self.window = attempts, window
        self.entries, self.lock = defaultdict(deque), threading.Lock()
    def allow(self, key: str) -> bool:
        now = time.monotonic()
        with self.lock:
            queue = self.entries[key]
            while queue and queue[0] < now - self.window:
                queue.popleft()
            if len(queue) >= self.attempts:
                return False
            queue.append(now)
            return True

LIMITER = RateLimiter()

class FlowLinkHandler(BaseHTTPRequestHandler):
    server_version = "FlowLink/" + VERSION
    @property
    def config(self) -> dict:
        return self.server.config
    def log_message(self, fmt: str, *args: object) -> None:
        print("%s - %s" % (self.address_string(), fmt % args), flush=True)
    def send_json(self, status: int, payload: dict, signing_key: str | None = None) -> None:
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        if signing_key:
            signature = hmac.new(signing_key.encode(), body, hashlib.sha256).hexdigest()
            self.send_header("X-FlowLink-Signature", signature)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def send_apk(self) -> None:
        if not APK_PATH.is_file():
            self.send_json(404, {"ok": False, "error": "update_not_published"})
            return
        size = APK_PATH.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", "attachment; filename=FlowLink-latest.apk")
        self.send_header("Cache-Control", "public, max-age=300")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with APK_PATH.open("rb") as handle:
            while chunk := handle.read(64 * 1024):
                self.wfile.write(chunk)
    def bearer(self) -> str:
        value = self.headers.get("Authorization", "")
        return value[7:] if value.startswith("Bearer ") else ""
    def read_body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise FlowLinkError("invalid_content_length") from exc
        if length < 1 or length > MAX_BODY:
            raise FlowLinkError("invalid_body_size")
        try:
            value = json.loads(self.rfile.read(length))
        except Exception as exc:
            raise FlowLinkError("invalid_json") from exc
        if not isinstance(value, dict):
            raise FlowLinkError("invalid_json")
        return value
    def rate_limit(self) -> bool:
        if LIMITER.allow(self.client_address[0]):
            return True
        self.send_json(429, {"ok": False, "error": "rate_limited"})
        return False
    def do_GET(self) -> None:
        if not self.rate_limit():
            return
        path = urlparse(self.path).path
        if path == "/healthz":
            health = load_json(HEALTH_PATH) if HEALTH_PATH.exists() else {}
            self.send_json(200, {"ok": bool(health.get("ok", True)),
                "service": "flowlink-server", "version": VERSION,
                "time": int(time.time())})
            return
        if path == "/v1/app/update":
            try:
                self.send_json(200, app_update())
            except FlowLinkError as exc:
                self.send_json(503, {"ok": False, "error": str(exc)})
            return
        if path == "/downloads/FlowLink-latest.apk":
            self.send_apk()
            return
        if path != "/v1/config":
            self.send_json(404, {"ok": False, "error": "not_found"})
            return
        raw_token = self.bearer()
        try:
            device_id, device, state = authenticate_device(raw_token)
        except FlowLinkError:
            self.send_json(401, {"ok": False, "error": "unauthorized"})
            return
        payload = {"ok": True, "version": VERSION,
            "config_version": state["config_version"], "device_id": device_id,
            "node_id": self.config["node_id"],
            "endpoint": self.config["endpoint"],
            "ports": effective_ports(state),
            "active_ports": state["active_ports"],
            "wireguard": {"server_public_key": self.config["server_public_key"],
                "address": device["address"], "dns": self.config["dns"],
                "mtu": self.config["mtu"],
                "persistent_keepalive": self.config["persistent_keepalive"]},
            "issued_at": int(time.time()),
            "next_update_after": min(int(state["next_rotation_at"]),
                                     int(time.time()) + 21600)}
        try:
            payload["app_update"] = app_update()
        except FlowLinkError:
            payload["app_update"] = {"ok": False, "available": False}
        self.send_json(200, payload, raw_token)
    def do_POST(self) -> None:
        if not self.rate_limit():
            return
        if urlparse(self.path).path != "/v1/enroll":
            self.send_json(404, {"ok": False, "error": "not_found"})
            return
        try:
            body = self.read_body()
            enrollment_token = str(body.get("enrollment_token", ""))
            result = enroll_device(enrollment_token,
                str(body.get("device_name", "Android")),
                str(body.get("public_key", "")))
            config, state = load_json(CONFIG_PATH), load_json(STATE_PATH)
            payload = {"ok": True, **result, "node_id": config["node_id"],
                "endpoint": config["endpoint"], "ports": effective_ports(state),
                "wireguard": {"server_public_key": config["server_public_key"],
                    "address": result["address"], "dns": config["dns"],
                    "mtu": config["mtu"],
                    "persistent_keepalive": config["persistent_keepalive"]}}
            self.send_json(201, payload, enrollment_token)
        except FlowLinkError as exc:
            self.send_json(400, {"ok": False, "error": str(exc)})

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--cert", type=Path,
                        default=Path("/etc/flowlink/tls/server.crt"))
    parser.add_argument("--key", type=Path,
                        default=Path("/etc/flowlink/tls/server.key"))
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, args.port), FlowLinkHandler)
    server.config = load_json(CONFIG_PATH)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    print(f"FlowLink {VERSION} HTTPS listening on {args.bind}:{args.port}", flush=True)
    server.serve_forever()

if __name__ == "__main__":
    main()
