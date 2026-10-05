"""Bounded, opt-in, latest-position-only relay. Never logs bodies or invitation tokens."""
import hashlib
import hmac
import json
import math
import secrets
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from .storage import atomic

MAX_BODY = 8192
MAX_TTL = 7200

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

class RelayState:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = Path(path), clock
        self.lock = threading.RLock()
        self.positions, self.rates = {}, {}
        self.grants = json.loads(self.path.read_text()) if self.path.exists() else []

    def save(self):
        atomic(self.path, json.dumps(self.grants))

    def issue(self, name, duration=3600):
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 80:
            raise ValueError("Enter a name between 1 and 80 characters.")
        if type(duration) is not int or not 60 <= duration <= MAX_TTL:
            raise ValueError("Sharing duration must be 1–120 minutes.")
        with self.lock:
            self.grants = [g for g in self.grants if g["expires_at"] > self.clock() and not g["revoked"]]
            if len(self.grants) >= 100:
                raise ValueError("At most 100 active invitations are supported.")
            publisher, reader = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
            grant = {"id": secrets.token_hex(12), "name": name.strip(), "expires_at": int(self.clock()) + duration,
                     "publisher_hash": digest(publisher), "reader_hash": digest(reader), "revoked": False}
            self.grants.append(grant)
            self.save()
            return {k: grant[k] for k in ("id", "name", "expires_at")} | {"publisher_token": publisher, "reader_token": reader}

    def auth(self, token, role):
        if not isinstance(token, str) or not 1 <= len(token) <= 128:
            raise PermissionError("Invalid or expired invitation.")
        hashed = digest(token)
        with self.lock:
            for grant in self.grants:
                if hmac.compare_digest(grant[role + "_hash"], hashed) and not grant["revoked"] and grant["expires_at"] > self.clock():
                    return grant.copy()
        raise PermissionError("Invalid or expired invitation.")

    def limit(self, token):
        with self.lock:
            now, key = self.clock(), digest(token)
            self.rates = {k: v for k, v in self.rates.items() if v and v[-1] > now - 60}
            if key not in self.rates and len(self.rates) >= 500:
                raise RuntimeError("Too many requests; try later.")
            q = self.rates.setdefault(key, deque())
            while q and q[0] <= now - 60:
                q.popleft()
            if len(q) >= 30:
                raise RuntimeError("Too many requests; try later.")
            q.append(now)

    def publish(self, token, value):
        grant = self.auth(token, "publisher")
        if not isinstance(value, dict) or value.get("consent") is not True:
            raise ValueError("Explicit consent=true is required for each update.")
        for field, low, high in (("latitude", -90, 90), ("longitude", -180, 180), ("accuracy", 0, 5000)):
            number = value.get(field)
            if type(number) not in (int, float) or not math.isfinite(number) or not low <= number <= high:
                raise ValueError("Invalid " + field)
        stamp = value.get("sent_at")
        if type(stamp) not in (int, float) or not math.isfinite(stamp) or not self.clock() - 60 <= stamp <= self.clock() + 30:
            raise ValueError("Position must be a recent GPS fix (Unix seconds).")
        phase = value.get("phase", "Sharing")
        if phase not in ("Sharing", "Leaving", "At boarding stop", "On board", "At transfer", "Arrived"):
            raise ValueError("Invalid check-in phase.")
        with self.lock:
            previous = self.positions.get(grant["id"])
            if previous and stamp <= previous["sent_at"]:
                raise ValueError("An older or repeated GPS fix cannot replace a newer fix.")
            self.positions[grant["id"]] = {k: value[k] for k in ("latitude", "longitude", "accuracy", "sent_at")} | {"phase": phase}

    def latest(self, token):
        grant = self.auth(token, "reader")
        with self.lock:
            self.positions = {k: p for k, p in self.positions.items() if p["sent_at"] > self.clock() - 300}
            position = self.positions.get(grant["id"])
            return {"name": grant["name"], "expires_at": grant["expires_at"], "position": position, "status": "recent" if position else "unknown"}

    def revoke(self, token):
        grant = self.auth(token, "publisher")
        with self.lock:
            for row in self.grants:
                if row["id"] == grant["id"]:
                    row["revoked"] = True
            self.positions.pop(grant["id"], None)
            self.save()

class RelayServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 24

    def __init__(self, address, state, admin_token):
        self.state, self.admin_token = state, admin_token
        self.slots = threading.BoundedSemaphore(24)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

class Handler(BaseHTTPRequestHandler):
    server_version = "CTTrainsRelay/0.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, *_):
        pass

    def send_json(self, code, value):
        raw = json.dumps(value, allow_nan=False).encode()
        self.send_response(code)
        for key, value in (("Content-Type", "application/json"), ("Content-Length", str(len(raw))),
                           ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff"), ("Connection", "close")):
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(raw)

    def body(self):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("Chunked requests are not supported.")
        if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            raise ValueError("Send application/json.")
        length = int(self.headers.get("Content-Length", "0"))
        if not 1 <= length <= MAX_BODY:
            raise ValueError("Request too large or empty.")
        result = json.loads(self.rfile.read(length))
        if not isinstance(result, dict):
            raise ValueError("Expected a JSON object.")
        return result

    def run_route(self):
        path = urlsplit(self.path)
        if path.query or path.fragment:
            raise ValueError("Do not put tokens or data in URL parameters.")
        if self.command == "GET" and path.path == "/v1/health":
            return self.send_json(200, {"ok": True, "version": "0.1.0"})
        authorization = self.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            raise PermissionError("Bearer invitation required.")
        token = authorization[7:]
        if not 1 <= len(token) <= 128:
            raise PermissionError("Invalid invitation.")
        self.server.state.limit(token)
        if path.path == "/admin/invitations":
            if self.client_address[0] not in ("127.0.0.1", "::1") or not hmac.compare_digest(token, self.server.admin_token):
                raise PermissionError("Local administration only.")
            if self.command == "POST":
                value = self.body()
                return self.send_json(201, self.server.state.issue(value.get("name", ""), value.get("duration", 3600)))
        if path.path == "/v1/location":
            if self.command == "POST":
                self.server.state.publish(token, self.body())
                return self.send_json(202, {"ok": True})
            if self.command == "GET":
                return self.send_json(200, self.server.state.latest(token))
            if self.command == "DELETE":
                self.server.state.revoke(token)
                return self.send_json(200, {"revoked": True})
        self.send_json(404, {"error": "Unknown endpoint"})

    def handle_route(self):
        try:
            self.run_route()
        except PermissionError as error:
            self.send_json(401, {"error": str(error)})
        except RuntimeError as error:
            self.send_json(429, {"error": str(error)})
        except (ValueError, TypeError, KeyError, OverflowError):
            self.send_json(400, {"error": "Invalid request. Check the API guide."})
        except (TimeoutError, BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.send_json(500, {"error": "Server error"})

    do_GET = do_POST = do_DELETE = handle_route

def serve(root, port=8787):
    root = Path(root)
    token = (root / "relay-admin.token").read_text().strip()
    RelayServer(("127.0.0.1", port), RelayState(root / "relay-grants.json"), token).serve_forever(poll_interval=0.2)
