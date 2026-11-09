"""Real Docker/Synapse/PostgreSQL/Caddy checks on a disposable CI runner."""
import json
import os
import secrets
import subprocess
import tempfile
import time
from pathlib import Path
from ctdesktop.hosting import Hosting, CADDY_IMAGE
from ctdesktop.matrix import MatrixClient, request_json, open_update
from test_services import circle, event

with tempfile.TemporaryDirectory(prefix="ctdesktop-integration-") as directory:
    host = Hosting(directory)
    try:
        host.setup("localhost")
        host.start_matrix()
        password = secrets.token_urlsafe(20)
        host.account("owner", password)
        host.account("viewer", password)
        host.account("stranger", password)
        owner, viewer, stranger = [MatrixClient("http://127.0.0.1:8008") for _ in range(3)]
        for client, user in ((owner, "owner"), (viewer, "viewer"), (stranger, "stranger")):
            client.login(user, password)
        try:
            request_json(owner.base, "/_matrix/client/v3/register", "POST", {"username": "anonymous", "password": password, "auth": {"type": "m.login.dummy"}})
            raise AssertionError("Public registration unexpectedly worked")
        except ValueError:
            pass
        room = owner.call(["createRoom"], "POST", {"preset": "private_chat", "visibility": "private",
            "initial_state": [{"type": "m.room.history_visibility", "state_key": "", "content": {"history_visibility": "joined"}}]})["room_id"]
        owner.call(["rooms", room, "invite"], "POST", {"user_id": viewer.user})
        viewer.join_private(room)
        now = int(time.time() * 1000)
        link = circle(now) | {"room": room, "ownerId": owner.user}
        payload = event(link, now)["content"]
        owner.call(["rooms", room, "send", "m.room.message", "integration-1"], "PUT", {"msgtype": "m.text", "body": "Private CT Trains test", **payload})
        updates = [open_update(link, message) for message in viewer.messages(room) if message.get("content", {}).get("za.co.cttrains.trip_update")]
        assert updates and updates[0]["phase"] == "Leaving"
        try:
            stranger.join_private(room)
            raise AssertionError("Uninvited account joined private room")
        except ValueError:
            pass
        public_room = owner.call(["createRoom"], "POST", {"preset": "public_chat", "visibility": "public"})["room_id"]
        try:
            viewer.join_private(public_room)
            raise AssertionError("Public room allowed for friend tracking")
        except ValueError:
            pass
        saved_user = viewer.user
        host.stop_matrix()
        host.start_matrix()
        check = MatrixClient(owner.base)
        assert check.login("viewer", password)["user"] == saved_user
        print("PASS: real Matrix/PostgreSQL setup, account creation, closed registration, private invite, sealed trip update, unauthorized denial, stop/start persistence")
        host.start_relay()
        invite = host.relay_invitation("Relay test", 60)
        request_json("http://127.0.0.1:8787", "/v1/location", "POST",
            {"consent": True, "latitude": -33.9, "longitude": 18.4, "accuracy": 15, "sent_at": time.time()}, invite["publisher_token"])
        assert request_json("http://127.0.0.1:8787", "/v1/location", token=invite["reader_token"])["status"] == "recent"
        host.stop_relay()
        host.start_relay()
        assert request_json("http://127.0.0.1:8787", "/v1/location", token=invite["reader_token"])["status"] == "unknown"
        host.write_proxy("localhost")
        proxy = host.stack / "Caddyfile-test"
        proxy.write_text((host.stack / "Caddyfile").read_text().replace("localhost {", "http://127.0.0.1:18080 {", 1))
        name = "ctdesktop-caddy-test"
        subprocess.run(["docker", "run", "-d", "--name", name, "--network", "host", "-v", str(proxy) + ":/etc/caddy/Caddyfile:ro", CADDY_IMAGE], check=True, capture_output=True)
        try:
            for _ in range(30):
                try:
                    request_json("http://127.0.0.1:18080", "/location/v1/health")
                    break
                except Exception:
                    time.sleep(0.2)
            assert request_json("http://127.0.0.1:18080", "/location/v1/health")["ok"]
            for path in ("/location/admin/invitations", "/location/v1/location"):
                try:
                    request_json("http://127.0.0.1:18080", path, token=(Path(directory) / "relay-admin.token").read_text())
                    raise AssertionError("Proxy allowed private/unauthorized endpoint")
                except ValueError:
                    pass
            assert request_json("http://127.0.0.1:18080", "/_matrix/client/versions")["versions"]
            print("PASS: relay subprocess, restart clears GPS, Caddy real routing, public administration blocked, Matrix proxy")
        finally:
            subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
        owner.logout()
        viewer.logout()
        stranger.logout()
    finally:
        host.stop_relay()
        try:
            host.compose("--profile", "https", "down", "--volumes")  # disposable CI-only data
        except Exception:
            pass
