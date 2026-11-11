# CT Trains developer control service

This is the authoritative server for issue #17. GitHub hosts APKs and manifests; it is not a single-use-code database. A public APK can always be modified to bypass its own local developer screens, so client UI is not an authorization boundary. Only the server can authorize shared actions. No developer setting grants Android system privileges or permits silent installation.

## Status and deployment
The worker source and database schema are prepared. **Deployment is pending owner approval for storing the generated credentials in the connected Cloudflare account.** No codes work until that deployment and initial invitation seeding complete. Do not treat an unreachable endpoint as authorized. Do not regenerate credentials automatically while approval is pending.

Use the exact wrangler.jsonc bindings; provision schema.sql with the D1 migration mechanism or prepared queries. Supply PEPPER and OWNER_DIGEST as secret bindings, never configuration values or repository secrets printed into logs. Initial owner code and 16 invitation values are generated outside the repository; store only HMAC-SHA256 verifiers. Code entropy is 192 bits; this is a random-token design, not human-password hashing. The 256-bit secret pepper lives only on the server. The APK contains only a public endpoint. Public readers cannot recover random codes from public source; no claim of absolute secrecy against compromised owner devices or the service is made.

Seed each invitation with digest HMAC(PEPPER, 'invite:'+code), a random ID and expiry in Unix seconds. Every initial code is unique. Do not place the cleartext values in migrations, issues, .llm notes or Actions output. Return the list privately to the owner. Keep the owner recovery credential in a password manager. An account administrator can replace OWNER_DIGEST through the Cloudflare secret controls if the owner credential is lost; never implement owner recovery using an app-side hardcoded bypass.

## Protocol
- POST /redeem {code}: invitation is atomically consumed and a hashed, random bearer session is created in the same D1 batch. A successful invitation never becomes unused. Network failure after redemption may consume it; issue another code.
- GET /session with Authorization: Bearer ...: validates server role, expiry and revocation. Tester sessions last seven days; owner sessions one hour. Owner code is reusable only for owner login and is never a tester invitation.
- POST /owner/codes {replace:boolean}: returns exactly 16 new codes once, expiring in 30 days. Replace revokes unused invitations; already used codes stay burned. Existing sessions are revoked separately.
- POST /owner/master {}: rotates the reusable owner credential, returns its new value once and revokes other owner sessions. The current session remains available to save it. After rotation, account recovery must replace the owner_config verifier (or remove it and replace OWNER_DIGEST); replacing only the initial secret does not override a rotated database verifier.
- POST /owner/access {}: returns IDs, status and a bounded audit; never hashes, codes or bearer tokens.
- POST /owner/revoke {id} or {all:true}: revokes sessions/invitations. The all action preserves owner sessions.
- GET /policy: public notice, no user identifiers or authorization. Default empty. Apps do not upload trip or location data.
- POST /owner/policy {kind,minCode,maxCode,message,expires,releaseTag,maintenance}: publish a notice for an inclusive installed versionCode range; notice must expire within 24 hours. UI defaults to one hour and explicit review. Optional target tag must exist in this repository and have update.json. The APK updater independently validates package, signer, SDK, version and checksum; notices cannot override these checks or install a downgrade.
- POST /owner/policy {clear:true}: immediate recovery from notice/maintenance.

Stable-version APKs and the Lab edition compile ALLOW_REMOTE_MAINTENANCE=false and hide this owner control.

Maintenance is an off-by-default, temporary test-release gate, never a process-kill/delete/remote-code feature. Settings, backups, places and updates remain available. It works only on clients that include this feature and can reach the service; it cannot shut down existing 1.13 installations or force an offline app to obey. Expired policy is discarded and outages leave ordinary offline transit usable. The client polls only while foregrounded, once a minute, and checks GitHub at most once per 15 minutes on foregrounding. Ordinary update banners appear once per version; security prompts recur per foreground visit while targeted. APK installation still belongs to Android.

Before broad public launch set ALLOW_MAINTENANCE=false at the service and remove maintenance handling from public clients. This also rejects future attempts to publish maintenance. Leave it false permanently after launch; retire this temporary capability explicitly in the launch issue.

## Operations and checks
node --test control-service/worker.test.mjs uses SQLite-backed D1 contract tests for redemption, rotation, privilege boundaries, revocation, rate limits and policy validation. Add concurrent live redemption checks before enabling the service. Request bodies and Authorization must never be logged. Observability records request metadata only. Rate keys are keyed hashes of IP and minute, expire after two minutes; raw IPs are not persisted. Audit is capped at 500 events. D1 stores code/session verifiers, random access IDs, expiries and administrative actions; do not store app user/location/profile data.

D1/account and endpoint identifiers are public configuration. Account access and quotas remain controlled by the owner. If credentials are suspected leaked, rotate PEPPER (invalidating all invitations/sessions) and OWNER_DIGEST together, revoke existing access and issue a fresh list. Preserve consumed invitation records so ordinary code rotation never reuses a value.
