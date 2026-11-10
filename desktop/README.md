# CT Trains Desktop 0.1.0 (alpha)

Linux desktop companion for the existing CT Trains Android app: host a private Matrix homeserver, view invited friend-trip updates, and optionally run a separate consent-based location relay. A lightweight native window with a dark mint theme; network and Docker actions run off the UI thread. This is a hosting/viewer companion, not the complete Android journey planner.

Download the Linux x86_64 tar.gz from GitHub Releases. Extract and run **CT-Trains-Desktop**. No Python installation is required. Run **install-linux.sh** to add it to your application menu. Built on Ubuntu 22.04; requires glibc 2.35 or newer and X11/XWayland. Docker Engine and Compose v2 are needed only for Matrix hosting. The Windows ZIP is a viewer/relay companion; Linux is the tested HTTPS hosting preset.

## Included

- Start/stop Matrix Synapse + PostgreSQL, create normal user accounts, redacted logs and health refresh.
- Closed registration, loopback administration, federation disabled for the private-host preset.
- Explicit Caddy HTTPS setup after configuring DNS/router access. No Google API.
- Sign into Matrix, import Android .ctcircle invitations, verify/decrypt sealed updates locally, planned steps/check-ins and OpenStreetMap links for recent positions.
- Explicit watching for at most two hours. Expired/stale/repeated updates, wrong senders and tampered ciphertext rejected.
- Independent REST relay with separate publisher/reader roles, consent, expiry, revocation, bounded requests and latest-position-only memory.
- Encrypted local credentials/invitation storage with OS-user permissions. No password storage or credential forwarding to redirect hosts.
- Phone setup/privacy instructions inside Help and in HELP.txt.

**Android 1.9 uses Matrix for friends. It does not publish to the independent REST relay yet.** Hosting Matrix enables the existing mobile sharing model. All users in this preset use the same homeserver. Precise trip updates are separately AES-GCM encrypted; general Matrix room encryption needs Element or another full Matrix client. The REST relay is not end-to-end encrypted.

## Setup and operation

Read [assets/HELP.txt](assets/HELP.txt). The host must stay awake/online. A trusted HTTPS address is necessary for Android. DNS, NAT/CGNAT and router access are your deployment inputs; the app does not silently change them.

Closing the desktop stops its relay/watching; Docker-hosted Matrix continues. **Stop** preserves accounts/messages/volumes. Back up Matrix keys/config and PostgreSQL together. Do not delete Docker volumes unless you intend to erase accounts/messages.

## Development

Python 3.12 with Tk:

- python -m venv .venv
- .venv/bin/pip install -r requirements-build.txt
- .venv/bin/python main.py
- .venv/bin/python -m unittest discover -s tests -p test_services.py -v
- xvfb-run -a .venv/bin/python -m unittest discover -s tests -p test_desktop.py -v
- PYTHONPATH=.:tests .venv/bin/python tests/integration.py
- .venv/bin/python -m PyInstaller --noconfirm --clean --onefile --name CT-Trains-Desktop --add-data assets:assets --collect-data tzdata main.py

The Docker integration test creates its own temporary stack on a disposable CI runner and deliberately deletes only those test volumes. Do not run its cleanup against a real server.

GitHub Actions builds Linux/Windows bundles, checks actual PostgreSQL/Synapse/Caddy, validates permissions/expiry/encryption, and launches both source and packaged Linux UI. A prerelease is published only after required jobs pass. No live account credentials, production signing keys or deployment secrets are committed.

[Synapse](https://element-hq.github.io/synapse/latest/setup/installation.html) · [Matrix API](https://spec.matrix.org/latest/client-server-api/) · [Docker Compose](https://docs.docker.com/compose/) · [Caddy HTTPS](https://caddyserver.com/docs/automatic-https).

Credit: [Xhanti-mbasa](https://github.com/Xhanti-mbasa).
