import base64
import json
import os
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from ctdesktop.relay import RelayState, RelayServer
from ctdesktop.storage import Vault
from ctdesktop.hosting import Hosting, hostname
from ctdesktop.matrix import safe_base, request_json, circle_document, open_update

def circle(now):
    return {"format": "cttrains.circle", "version": 1, "id": "trip-1", "room": "!room:matrix.test", "ownerId": "@owner:matrix.test",
        "secret": base64.b64encode(b"k" * 32).decode(), "expiresAt": now + 3600000,
        "trip": {"format": "cttrains.trip", "version": 1, "id": "trip-1", "owner": "Traveller", "createdAt": now,
        "steps": [{"kind": "leave", "label": "Home", "at": now}, {"kind": "arrive", "label": "Campus", "at": now + 1800000}]}}

def event(link, now, sequence=1, **extra):
    value = {"circleId": link["id"], "sequence": sequence, "sentAt": now, "expiresAt": link["expiresAt"], "phase": "Leaving",
        "point": {"latitude": -33.9, "longitude": 18.4}, "accuracy": 15} | extra
    nonce = os.urandom(12)
    aad = (link["id"] + "|" + link["room"] + "|" + link["ownerId"] + "|v1").encode()
    ciphertext = AESGCM(base64.b64decode(link["secret"])).encrypt(nonce, json.dumps(value).encode(), aad)
    return {"sender": link["ownerId"], "content": {"za.co.cttrains.trip_update": {
        "circleId": link["id"], "nonce": base64.b64encode(nonce).decode(), "ciphertext": base64.b64encode(ciphertext).decode()}}}

class RelayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = 1700000000
        self.state = RelayState(Path(self.tmp.name) / "grants.json", lambda: self.now)
        self.invite = self.state.issue("Friend", 60)
        self.publisher, self.reader = self.invite["publisher_token"], self.invite["reader_token"]
        self.position = {"consent": True, "latitude": -33.9, "longitude": 18.4, "accuracy": 20, "sent_at": self.now}

    def tearDown(self):
        self.tmp.cleanup()

    def test_role_separation(self):
        with self.assertRaises(PermissionError): self.state.publish(self.reader, self.position)
        with self.assertRaises(PermissionError): self.state.latest(self.publisher)

    def test_only_latest(self):
        self.state.publish(self.publisher, self.position)
        self.now += 1
        self.state.publish(self.publisher, self.position | {"sent_at": self.now, "latitude": -34})
        self.assertEqual(len(self.state.positions), 1)
        self.assertEqual(self.state.latest(self.reader)["position"]["latitude"], -34)

    def test_old_fix_cannot_overwrite(self):
        self.state.publish(self.publisher, self.position)
        with self.assertRaises(ValueError): self.state.publish(self.publisher, self.position)

    def test_consent_required(self):
        with self.assertRaises(ValueError): self.state.publish(self.publisher, self.position | {"consent": False})

    def test_invalid_coordinates(self):
        for value in (float("nan"), float("inf"), True, "-33", 100):
            with self.assertRaises(ValueError): self.state.publish(self.publisher, self.position | {"latitude": value})

    def test_stale_or_future_fix_rejected(self):
        for stamp in (self.now - 61, self.now + 31, float("nan")):
            with self.assertRaises(ValueError): self.state.publish(self.publisher, self.position | {"sent_at": stamp})

    def test_expiry_blocks_both_roles(self):
        self.now += 61
        for token, role in ((self.reader, "reader"), (self.publisher, "publisher")):
            with self.assertRaises(PermissionError): self.state.auth(token, role)

    def test_revocation_clears_location(self):
        self.state.publish(self.publisher, self.position)
        self.state.revoke(self.publisher)
        self.assertEqual(self.state.positions, {})
        with self.assertRaises(PermissionError): self.state.latest(self.reader)

    def test_disk_has_no_positions_or_plain_tokens(self):
        self.state.publish(self.publisher, self.position)
        stored = self.state.path.read_text()
        for value in ("latitude", self.reader, self.publisher): self.assertNotIn(value, stored)

    def test_restart_preserves_grants_not_positions(self):
        self.state.publish(self.publisher, self.position)
        restored = RelayState(self.state.path, lambda: self.now)
        self.assertEqual(restored.latest(self.reader)["status"], "unknown")

    def test_position_becomes_unknown(self):
        invite = self.state.issue("Longer", 3600)
        self.state.publish(invite["publisher_token"], self.position)
        self.now += 301
        self.assertEqual(self.state.latest(invite["reader_token"])["status"], "unknown")

    def test_rate_limit(self):
        for _ in range(30): self.state.limit(self.reader)
        with self.assertRaises(RuntimeError): self.state.limit(self.reader)

    def test_duration_limit(self):
        for duration in (0, 7201, True, "3600"):
            with self.assertRaises(ValueError): self.state.issue("Person", duration)

class HttpRelayTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.server = RelayServer(("127.0.0.1", 0), RelayState(Path(self.tmp.name) / "grants.json"), "admin-test-token")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def test_create_publish_read_revoke(self):
        invite = request_json(self.base, "/admin/invitations", "POST", {"name": "Friend", "duration": 60}, "admin-test-token")
        request_json(self.base, "/v1/location", "POST", {"consent": True, "latitude": -33.9, "longitude": 18.4, "accuracy": 20, "sent_at": time.time()}, invite["publisher_token"])
        self.assertEqual(request_json(self.base, "/v1/location", token=invite["reader_token"])["status"], "recent")
        request_json(self.base, "/v1/location", "DELETE", token=invite["publisher_token"])
        with self.assertRaises(ValueError): request_json(self.base, "/v1/location", token=invite["reader_token"])

    def test_anonymous_and_wrong_admin_denied(self):
        for path in ("/v1/location", "/admin/invitations"):
            with self.assertRaises(ValueError): request_json(self.base, path)
        with self.assertRaises(ValueError): request_json(self.base, "/admin/invitations", "POST", {"name": "x"}, "wrong")

    def test_health_discloses_no_users(self):
        self.assertEqual(set(request_json(self.base, "/v1/health")), {"ok", "version"})

    def test_oversized_body_rejected(self):
        with self.assertRaises(ValueError): request_json(self.base, "/admin/invitations", "POST", {"name": "x" * 9000}, "admin-test-token")

class CircleTests(unittest.TestCase):
    def setUp(self):
        self.now = int(time.time() * 1000)
        self.link = circle(self.now)

    def test_android_wire_format(self):
        link = circle_document(json.dumps(self.link), self.now)
        self.assertEqual(open_update(link, event(link, self.now), now=self.now)["point"]["longitude"], 18.4)

    def test_wrong_sender_and_replay(self):
        update = event(self.link, self.now)
        with self.assertRaises(ValueError): open_update(self.link, update | {"sender": "@stranger:matrix.test"}, now=self.now)
        with self.assertRaises(ValueError): open_update(self.link, update, 1, self.now)

    def test_corrupted_ciphertext(self):
        update = event(self.link, self.now)
        update["content"]["za.co.cttrains.trip_update"]["ciphertext"] = base64.b64encode(b"bad" * 20).decode()
        with self.assertRaises(Exception): open_update(self.link, update, now=self.now)

    def test_stale_or_future_update(self):
        for stamp in (self.now - 300001, self.now + 30001):
            with self.assertRaises(ValueError): open_update(self.link, event(self.link, stamp), now=self.now)

    def test_expired_invitation(self):
        with self.assertRaises(ValueError): circle_document(json.dumps(self.link | {"expiresAt": self.now}), self.now)

    def test_nan_gps(self):
        with self.assertRaises(ValueError): open_update(self.link, event(self.link, self.now, point={"latitude": float("nan"), "longitude": 18}), now=self.now)

    def test_bad_trip(self):
        invalid = self.link | {"trip": self.link["trip"] | {"steps": [{"kind": "leave", "label": "x", "at": 0}]}}
        with self.assertRaises(ValueError): circle_document(json.dumps(invalid), self.now)

class HostingStorageTests(unittest.TestCase):
    def test_vault_encryption_and_permissions(self):
        with tempfile.TemporaryDirectory() as root:
            vault = Vault(root)
            vault.write("session", {"token": "private-token"})
            self.assertNotIn(b"private-token", (Path(root) / "session.sealed").read_bytes())
            self.assertEqual(vault.read("session")["token"], "private-token")
            if os.name != "nt": self.assertEqual((Path(root) / "device.key").stat().st_mode & 0o777, 0o600)
            vault.delete("session")
            self.assertIsNone(vault.read("session"))

    def test_host_and_url_validation(self):
        self.assertEqual(hostname("matrix.example.com"), "matrix.example.com")
        for host in ("a; touch /tmp/x", "a\nxyz", "https://x.com", "a..b", "-x.com"):
            with self.assertRaises(ValueError): hostname(host)
        for url in ("http://public.example", "https://user:pass@example.com", "https://example.com/x", "https://example.com?token=x"):
            with self.assertRaises(ValueError): safe_base(url)

    def test_private_compose_and_stable_secrets(self):
        with tempfile.TemporaryDirectory() as root:
            host = Hosting(root)
            host.configure("matrix.example.com")
            host.write_proxy("matrix.example.com")
            config = yaml.safe_load((host.stack / "compose.yaml").read_text())
            self.assertEqual(config["services"]["synapse"]["ports"], ["127.0.0.1:8008:8008"])
            self.assertNotIn("ports", config["services"]["postgres"])
            self.assertIn("/admin", (host.stack / "Caddyfile").read_text())
            env = (host.stack / ".env").read_text()
            host.configure("matrix.example.com")
            self.assertEqual((host.stack / ".env").read_text(), env)
            with self.assertRaises(ValueError): host.configure("another.example.com")

    def test_stop_keeps_volumes(self):
        commands = []
        class Result:
            returncode, stdout = 0, ""
        def runner(args, **kwargs):
            commands.append(args)
            return Result()
        with tempfile.TemporaryDirectory() as root:
            host = Hosting(root, runner)
            host.configure("matrix.example.com")
            host.stop_matrix()
            self.assertEqual(commands[-1][-1], "stop")
            self.assertNotIn("--volumes", commands[-1])

    def test_password_never_in_process_arguments(self):
        with tempfile.TemporaryDirectory() as root:
            host = Hosting(root)
            path = host.stack / "synapse"
            path.mkdir(parents=True)
            (path / "homeserver.yaml").write_text("registration_shared_secret: local-secret\n")
            host.configure("matrix.example.com")
            with patch("ctdesktop.hosting.create_local_account") as account:
                host.account("friend", "secret-password-123")
                self.assertEqual(account.call_args.args, ("http://127.0.0.1:8008", "local-secret", "friend", "secret-password-123", False))

if __name__ == "__main__":
    unittest.main()
