# WhatsApp updates setup

Open **Tools → WhatsApp updates · alpha → How to set this up**. The bundled guide works offline.

You need a separately running [rmyndharis/OpenWA](https://github.com/rmyndharis/OpenWA) gateway. It processes the messages from the linked WhatsApp account; this feature is not entirely phone-local. Sharing a trip or delay report through Android's share sheet needs no gateway.

1. Install OpenWA using its current [installation instructions](https://github.com/rmyndharis/OpenWA/blob/main/docs/README.md). The Docker quick start is `git clone https://github.com/rmyndharis/OpenWA.git`, then `cd OpenWA`, then `docker compose up -d` on a computer/server with Docker installed.
2. Configure a reachable HTTPS address with a valid certificate. The default local API is `http://localhost:2785/api`; the phone requires the HTTPS address of the server. Hosting and TLS must be configured outside CT Trains.
3. Select `ENGINE_TYPE=whatsapp-web.js` for channel history; start a session and link WhatsApp in the gateway dashboard. Follow the intended channel or join the group in this account.
4. Create a **viewer** API key (the app calls it Reader), with `allowedSessions` restricted to this session UUID. Restrict `allowedChats` to intended feeds where supported. Keep the admin key outside CT Trains.
5. Enter the HTTPS website and Reader key in the app. **Find linked accounts** lists up to 100 accounts visible to the key. Select the ready account and **Test & connect reader**. An origin-only website gets `/api` appended; explicit custom paths are preserved. The manual account ID is the UUID, not the account name or phone number.
6. **Browse subscribed channels**, verify the intended publisher and add it. Group IDs end in `@g.us`, channel IDs in `@newsletter`; channel invitation URLs do not contain the same identifier.

Credentials are encrypted locally and excluded from profile backups. Reader connection/discovery uses authenticated HTTPS GET requests with `X-API-Key`; it does not create sessions, link accounts, send messages or alter groups. A connection is saved only after its session ID matches and status is ready.

401/403 means permission/key trouble, 404 often means an incorrect API base or UUID, 409 means account not ready, and 501 means unsupported engine capability. Pausing stops app checks. Disconnect erases the phone inbox and connection; revoke the key or unlink WhatsApp to remove gateway access too.

API behavior was checked against the upstream session controller, API collection and engine capability matrix on 2 October 2026. OpenWA and WhatsApp change independently, so this remains an isolated alpha feature.
