import base64
import hashlib
import hmac
import json
import math
import re
import time
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit, urlencode
from urllib.request import Request, build_opener, HTTPRedirectHandler
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None

def safe_base(raw):
    url = urlsplit(raw.strip())
    if url.scheme not in ("https", "http") or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ("", "/"):
        raise ValueError("Use an HTTPS homeserver URL without a path or credentials.")
    if url.scheme == "http" and url.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("HTTP is only allowed for a local server on this computer.")
    return raw.strip().rstrip("/")

def request_json(base, path, method="GET", body=None, token=None):
    headers = {"Accept": "application/json", "User-Agent": "CTTrainsDesktop/0.1"}
    if token:
        if not isinstance(token, str) or not 1 <= len(token) <= 8192 or any(c.isspace() or ord(c) < 32 for c in token):
            raise ValueError("Invalid access token.")
        headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body, allow_nan=False).encode()
    request = Request(safe_base(base) + path, data=data, headers=headers, method=method)
    try:
        with build_opener(NoRedirect()).open(request, timeout=12) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ValueError("Server response is too large.")
            return json.loads(raw)
    except HTTPError as error:
        if error.code in (301, 302, 303, 307, 308):
            raise ValueError("Homeserver redirected. Enter its actual HTTPS URL; credentials were not forwarded.") from None
        raise ValueError("Server returned HTTP %s. Check account and room permissions." % error.code) from None

class MatrixClient:
    def __init__(self, base, token="", user=""):
        self.base, self.token, self.user = safe_base(base), token, user

    def call(self, parts, method="GET", body=None, query=None):
        path = "/_matrix/client/v3/" + "/".join(quote(part, safe="") for part in parts)
        if query:
            path += "?" + urlencode(query)
        return request_json(self.base, path, method, body, self.token or None)

    def login(self, user, password):
        response = self.call(["login"], "POST", {"type": "m.login.password", "identifier": {"type": "m.id.user", "user": user},
                          "password": password, "initial_device_display_name": "CT Trains Desktop"})
        self.token, self.user = response["access_token"], response["user_id"]
        self.call(["account", "whoami"])
        return {"base": self.base, "token": self.token, "user": self.user}

    def logout(self):
        self.call(["logout"], "POST", {})

    def join_private(self, room):
        self.call(["join", room], "POST", {})
        states = self.call(["rooms", room, "state"])
        if any(state.get("type") == "m.room.encryption" for state in states):
            raise ValueError("Use Element for an encrypted Matrix room; this app reads CT Trains sealed trip messages.")
        if not any(state.get("type") == "m.room.join_rules" and state.get("content", {}).get("join_rule") == "invite" for state in states):
            raise ValueError("Friend tracking requires an invite-only room.")

    def messages(self, room):
        return self.call(["rooms", room, "messages"], query={"dir": "b", "limit": "100"}).get("chunk", [])

def create_local_account(base, secret, username, password, admin=False):
    if not re.fullmatch(r"[a-z0-9._=-]{1,64}", username):
        raise ValueError("Use 1–64 lowercase letters, numbers, dots, underscores or hyphens.")
    if not 12 <= len(password) <= 256:
        raise ValueError("Use a password of 12–256 characters.")
    if urlsplit(safe_base(base)).hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Account administration must use the local server.")
    path = "/_synapse/admin/v1/register"
    nonce = request_json(base, path)["nonce"]
    text = "\0".join((nonce, username, password, "admin" if admin else "notadmin")).encode()
    mac = hmac.new(secret.encode(), text, hashlib.sha1).hexdigest()
    request_json(base, path, "POST", {"nonce": nonce, "username": username, "password": password, "admin": admin, "mac": mac})
    return username

def trip_document(value):
    if not isinstance(value, dict) or value.get("format") != "cttrains.trip" or value.get("version") != 1:
        raise ValueError("Not a CT Trains safety-trip file.")
    for field in ("id", "owner"):
        if not isinstance(value.get(field), str) or not 1 <= len(value[field]) <= 80:
            raise ValueError("Invalid trip " + field)
    steps = value.get("steps")
    if not isinstance(steps, list) or not 2 <= len(steps) <= 100:
        raise ValueError("Invalid trip steps.")
    previous = 0
    for step in steps:
        if not isinstance(step, dict) or step.get("kind") not in ("leave", "board", "alight", "transfer", "arrive", "walk"):
            raise ValueError("Invalid trip step.")
        if not isinstance(step.get("label"), str) or not 1 <= len(step["label"]) <= 180 or type(step.get("at")) is not int or step["at"] <= 0 or step["at"] < previous:
            raise ValueError("Invalid step time or label.")
        previous = step["at"]
    if steps[-1]["at"] - steps[0]["at"] > 86400000:
        raise ValueError("Trip is longer than one day.")
    return value

def circle_document(raw, now=None):
    now = int(time.time() * 1000) if now is None else now
    if len(raw.encode()) > 256 * 1024:
        raise ValueError("Invitation is too large.")
    value = json.loads(raw)
    if not isinstance(value, dict) or value.get("format") != "cttrains.circle" or value.get("version") != 1:
        raise ValueError("Select a CT Trains private invitation (.ctcircle).")
    if not isinstance(value.get("id"), str) or not 1 <= len(value["id"]) <= 80:
        raise ValueError("Invalid invitation ID.")
    for field, regex in (("room", r"![^\s]{1,250}:[^\s]{1,250}"), ("ownerId", r"@[^\s]{1,250}:[^\s]{1,250}")):
        if not isinstance(value.get(field), str) or not re.fullmatch(regex, value[field]):
            raise ValueError("Invalid invitation " + field)
    if type(value.get("expiresAt")) is not int or not now < value["expiresAt"] <= now + 21600000:
        raise ValueError("Invitation has expired or exceeds six hours.")
    if len(base64.b64decode(value["secret"], validate=True)) != 32:
        raise ValueError("Invalid invitation key.")
    trip_document(value.get("trip"))
    return value

def open_update(link, event, last_sequence=-1, now=None):
    now = int(time.time() * 1000) if now is None else now
    sealed = event.get("content", {}).get("za.co.cttrains.trip_update", {})
    if event.get("sender") != link["ownerId"] or sealed.get("circleId") != link["id"]:
        raise ValueError("Update sender or circle does not match.")
    if len(sealed.get("ciphertext", "")) > 12000:
        raise ValueError("Update too large.")
    nonce = base64.b64decode(sealed["nonce"], validate=True)
    if len(nonce) != 12:
        raise ValueError("Invalid nonce.")
    aad = (link["id"] + "|" + link["room"] + "|" + link["ownerId"] + "|v1").encode()
    plain = AESGCM(base64.b64decode(link["secret"], validate=True)).decrypt(nonce, base64.b64decode(sealed["ciphertext"], validate=True), aad)
    value = json.loads(plain)
    if value.get("circleId") != link["id"] or type(value.get("sequence")) is not int or value["sequence"] < 0 or value["sequence"] <= last_sequence:
        raise ValueError("Repeated or incorrect update.")
    if type(value.get("sentAt")) is not int or not now - 300000 <= value["sentAt"] <= now + 30000 or value.get("expiresAt") != link["expiresAt"] or value["expiresAt"] <= now:
        raise ValueError("Expired or stale location.")
    if value.get("phase") not in ("Ready", "Leaving", "At boarding stop", "On board", "At transfer", "Arrived", "Stopped"):
        raise ValueError("Invalid phase.")
    if value.get("near") is not None and (not isinstance(value["near"], str) or len(value["near"]) > 180):
        raise ValueError("Invalid stop name.")
    if value.get("point") is not None:
        for field, low, high in (("latitude", -90, 90), ("longitude", -180, 180)):
            number = value["point"].get(field)
            if type(number) not in (int, float) or not math.isfinite(number) or not low <= number <= high:
                raise ValueError("Invalid position.")
        number = value.get("accuracy")
        if type(number) not in (int, float) or not math.isfinite(number) or not 0 <= number <= 5000:
            raise ValueError("Invalid GPS accuracy.")
    if value["phase"] == "Stopped":
        value["point"] = None
        value["accuracy"] = None
    return value
