import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path
import yaml
from .storage import atomic
from .matrix import create_local_account, request_json

SYNAPSE_IMAGE = "ghcr.io/element-hq/synapse:v1.162.0"
POSTGRES_IMAGE = "postgres:16-alpine"
CADDY_IMAGE = "caddy:2-alpine"

def hostname(name):
    if not isinstance(name, str) or len(name) > 253 or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", name) or ".." in name:
        raise ValueError("Enter a hostname such as matrix.example.com, without https:// or a port.")
    if any(not part or len(part) > 63 or part.startswith("-") or part.endswith("-") for part in name.split(".")):
        raise ValueError("Invalid server hostname.")
    return name

class Hosting:
    def __init__(self, root, runner=None):
        self.root = Path(root)
        self.stack = self.root / "matrix"
        self.runner = runner or subprocess.run
        self.project = "cttrains-" + hashlib.sha256(str(self.root).encode()).hexdigest()[:10]
        self.relay = None

    def command(self, args, timeout=300, cwd=None):
        result = self.runner(args, cwd=cwd, capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise ValueError("Hosting command failed (exit %s). Check Docker permissions, available ports and Logs." % result.returncode)
        return result.stdout

    def docker(self):
        if self.runner == subprocess.run and not shutil.which("docker"):
            raise ValueError("Matrix requires Docker Engine and Compose. See Help for installation. The desktop and location relay work without Docker.")
        self.command(["docker", "info"], timeout=12)
        self.command(["docker", "compose", "version"], timeout=12)
        return "Docker Engine and Compose are ready."

    def compose(self, *args, timeout=300):
        if not (self.stack / "compose.yaml").exists():
            raise ValueError("Set up the Matrix server first.")
        return self.command(["docker", "compose", "--project-name", self.project, "--file", str(self.stack / "compose.yaml"), *args], timeout, self.stack)

    def configure(self, server):
        server = hostname(server.strip().lower())
        self.stack.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.stack.chmod(0o700)
        settings = self.stack / "settings.json"
        if settings.exists():
            if json.loads(settings.read_text())["server_name"] != server:
                raise ValueError("Matrix's server name is permanent. Keep the existing name or use a separate data directory for a new server.")
            if not (self.stack / ".env").exists():
                raise ValueError("The database secret is missing. Restore your existing .env backup; do not replace it for an existing database.")
            if not (self.stack / "compose.yaml").exists():
                self.write_compose()
            return server
        atomic(self.stack / ".env", "POSTGRES_PASSWORD=" + secrets.token_hex(32) + "\n")
        self.write_compose()
        atomic(settings, json.dumps({"server_name": server}))
        return server

    def write_compose(self):
        uid = str(os.getuid()) if hasattr(os, "getuid") else "991"
        gid = str(os.getgid()) if hasattr(os, "getgid") else "991"
        services = {
            "postgres": {"image": POSTGRES_IMAGE, "restart": "unless-stopped", "environment": {
                "POSTGRES_USER": "cttrains", "POSTGRES_DB": "cttrains", "POSTGRES_PASSWORD": "${POSTGRES_PASSWORD}",
                "POSTGRES_INITDB_ARGS": "--encoding=UTF8 --locale=C"}, "volumes": ["postgres:/var/lib/postgresql/data"],
                "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U cttrains -d cttrains"], "interval": "5s", "timeout": "5s", "retries": 20}},
            "synapse": {"image": SYNAPSE_IMAGE, "restart": "unless-stopped", "environment": {"UID": uid, "GID": gid},
                "ports": ["127.0.0.1:8008:8008"], "volumes": ["./synapse:/data"], "depends_on": {"postgres": {"condition": "service_healthy"}},
                "healthcheck": {"test": ["CMD", "curl", "-fSs", "http://localhost:8008/health"], "interval": "10s", "timeout": "5s", "retries": 20, "start_period": "20s"}},
            "caddy": {"image": CADDY_IMAGE, "restart": "unless-stopped", "profiles": ["https"], "network_mode": "host",
                "volumes": ["./Caddyfile:/etc/caddy/Caddyfile:ro", "caddy_data:/data", "caddy_config:/config"],
                "depends_on": {"synapse": {"condition": "service_healthy"}}}}
        atomic(self.stack / "compose.yaml", yaml.safe_dump({"services": services, "volumes": {"postgres": {}, "caddy_data": {}, "caddy_config": {}}}, sort_keys=False))

    def setup(self, server):
        self.docker()
        server = self.configure(server)
        target = self.stack / "synapse"
        target.mkdir(exist_ok=True, mode=0o700)
        config = target / "homeserver.yaml"
        if config.exists():
            existing = yaml.safe_load(config.read_text())
            if existing.get("database", {}).get("name") == "psycopg2" and existing.get("enable_registration") is False:
                return "Server configuration is ready. Start Matrix to bring it online."
        uid = str(os.getuid()) if hasattr(os, "getuid") else "991"
        gid = str(os.getgid()) if hasattr(os, "getgid") else "991"
        self.command(["docker", "run", "--rm", "-v", str(target) + ":/data", "-e", "SYNAPSE_SERVER_NAME=" + server,
                      "-e", "SYNAPSE_REPORT_STATS=no", "-e", "UID=" + uid, "-e", "GID=" + gid, SYNAPSE_IMAGE, "generate"])
        config_data = yaml.safe_load(config.read_text())
        password = (self.stack / ".env").read_text().strip().split("=", 1)[1]
        config_data.update({"server_name": server, "public_baseurl": "https://" + server + "/", "report_stats": False,
            "enable_registration": False, "registration_shared_secret": secrets.token_hex(32), "federation_domain_whitelist": [],
            "enable_metrics": False, "allow_public_rooms_over_federation": False,
            "listeners": [{"port": 8008, "tls": False, "type": "http", "x_forwarded": True, "bind_addresses": ["0.0.0.0"],
                           "resources": [{"names": ["client"], "compress": False}]}],
            "database": {"name": "psycopg2", "args": {"user": "cttrains", "password": password, "database": "cttrains", "host": "postgres", "cp_min": 5, "cp_max": 10}}})
        atomic(config, yaml.safe_dump(config_data, sort_keys=False))
        self.write_proxy(server)
        return "Matrix configured with PostgreSQL and closed registration. Start Matrix next."

    def write_proxy(self, server):
        hostname(server)
        well_known = json.dumps(json.dumps({"m.homeserver": {"base_url": "https://" + server}}))
        text = """%s {
    encode zstd gzip
    @matrix path /_matrix/client/* /_matrix/media/*
    handle @matrix {
        reverse_proxy 127.0.0.1:8008
    }
    handle /.well-known/matrix/client {
        header Content-Type application/json
        header Access-Control-Allow-Origin *
        respond %s
    }
    handle_path /location/* {
        @private path /admin /admin/*
        respond @private 404
        reverse_proxy 127.0.0.1:8787
    }
    handle {
        respond "CT Trains private homeserver" 200
    }
}
""" % (server, well_known)
        atomic(self.stack / "Caddyfile", text)

    def start_matrix(self):
        self.docker()
        if not (self.stack / "synapse/homeserver.yaml").exists():
            raise ValueError("Complete server setup first.")
        self.compose("up", "-d", "--wait", "--wait-timeout", "180", "postgres", "synapse")
        return "Matrix is healthy at http://127.0.0.1:8008. Enable HTTPS before connecting phones."

    def start_https(self):
        if not sys.platform.startswith("linux"):
            raise ValueError("This hosting preset supports HTTPS on Linux. On Windows use a Linux host/WSL and a trusted proxy; see Help.")
        server = self.server_name()
        if server == "localhost" or "." not in server:
            raise ValueError("Phone HTTPS needs a real domain pointing to this computer. Localhost is only for desktop tests.")
        self.compose("--profile", "https", "up", "-d", "--wait", "--wait-timeout", "180", "caddy")
        return "HTTPS proxy started. Check DNS/ports 80 and 443, then test https://" + server + " from the phone."

    def stop_matrix(self):
        self.compose("--profile", "https", "stop")
        return "Matrix and HTTPS stopped. Accounts, messages and volumes are preserved."

    def server_name(self):
        path = self.stack / "settings.json"
        if not path.exists():
            raise ValueError("Set up Matrix first.")
        return json.loads(path.read_text())["server_name"]

    def account(self, username, password, admin=False):
        config = yaml.safe_load((self.stack / "synapse/homeserver.yaml").read_text())
        create_local_account("http://127.0.0.1:8008", config["registration_shared_secret"], username, password, admin)
        return "Created @%s:%s. The password was not saved." % (username, self.server_name())

    def matrix_status(self):
        try:
            request_json("http://127.0.0.1:8008", "/_matrix/client/versions")
            return "Online"
        except Exception:
            return "Offline"

    def relay_status(self):
        return "Online · localhost:8787" if self.relay and self.relay.poll() is None else "Offline"

    def start_relay(self):
        if self.relay and self.relay.poll() is None:
            return "Location relay is already running."
        token = self.root / "relay-admin.token"
        if not token.exists():
            atomic(token, secrets.token_urlsafe(32))
        cmd = [sys.executable, "--relay", str(self.root)] if getattr(sys, "frozen", False) else [
            sys.executable, str(Path(__file__).parent.parent / "main.py"), "--relay", str(self.root)]
        with open(self.root / "relay.log", "a") as log:
            os.chmod(self.root / "relay.log", 0o600)
            self.relay = subprocess.Popen(cmd, stdout=log, stderr=log, start_new_session=(os.name != "nt"))
        for _ in range(30):
            if self.relay.poll() is not None:
                raise ValueError("The relay could not start. Port 8787 may already be in use. Check relay.log.")
            try:
                request_json("http://127.0.0.1:8787", "/v1/health")
                return "Location relay started. Positions stay in memory; invitations expire within two hours."
            except Exception:
                time.sleep(0.1)
        self.stop_relay()
        raise ValueError("Relay did not become healthy; check relay.log.")

    def stop_relay(self):
        if self.relay and self.relay.poll() is None:
            self.relay.terminate()
            try:
                self.relay.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.relay.kill()
                self.relay.wait(timeout=2)
        self.relay = None
        return "Location relay stopped. In-memory positions cleared."

    def relay_invitation(self, name, duration=3600):
        if self.relay_status() == "Offline":
            raise ValueError("Start the location relay first.")
        token = (self.root / "relay-admin.token").read_text().strip()
        return request_json("http://127.0.0.1:8787", "/admin/invitations", "POST", {"name": name, "duration": duration}, token)

    def logs(self):
        raw = self.compose("--profile", "https", "logs", "--tail", "60", "--no-color", timeout=12)
        raw = re.sub(r"\bsyt_[A-Za-z0-9_-]+", "[redacted]", raw)
        raw = re.sub(r"(?i)(access_token|password|authorization|registration_shared_secret)([=:\s]+)[^\s,]+", r"\1\2[redacted]", raw)
        return raw[-18000:]
