# Directions, Live Updates and app updates · 1.9.0

In **Plan**, choose a timed journey and tap **Directions**. The map also has a
Directions button for its selected train or bus journey. Preview the steps, then
start. Existing Work/Home station preferences, complete From/To swap, calendar,
time selectors and reminders stay available.

The active session covers mapped walking legs, boarding, each train change or
bus ride, and the final walk. New mapped walking requests ask FOSSGIS/OSRM for
street maneuvers and cache them encrypted. Older walking caches still work but
may only say to follow the mapped path. A route must be selected and cached
before it can work offline; starting directions itself makes no network request.
Map imagery has its own online/downloaded controls. No Google Maps API key is
required.

**View journey map** opens a full-screen map built from the saved directions,
even after Android closes the planner screen. It keeps only the journey's
numbered stops and walking paths. Transit links in this recovery view are
approximate station/stop connections; the regular planner map retains its
separately mapped railway trace. Without imagery, the offline schematic still
shows the saved geometry and current position.

Allow location while using the app and notifications when starting. Android's
location foreground service continues an explicitly started session when the
app is in the background, for at most four hours. Pause stops GPS sampling; Stop
ends it. After process death/reboot, resume explicitly from Tools → Directions.
Only the current fix is kept in memory. The encrypted itinerary and step index
are temporary and excluded from portable backups; no GPS history is recorded
or shared by directions.

Accurate, recent fixes can complete walking legs after dwelling near the target.
**I'm on board** and **I've arrived** confirm transit steps manually. Passing a
station, a coarse/stale fix or a scheduled time cannot confirm that you boarded,
arrived or that a vehicle is delayed. A near-stop alert helps you prepare to get
off. Off-path guidance asks you to check the map/recalculate in Plan, rather than
inventing a turn. Golden Arrow's personally associated stops remain unverified.

## Samsung Galaxy S22 / One UI 8

Android 16 uses the platform ProgressStyle, ongoing navigation category,
promotion request and `POST_PROMOTED_NOTIFICATIONS`. Older Android uses an
ongoing standard notification. Notifications have Pause, Next step and Stop
actions and open the active journey. Dismissing/demoting a Live Update stops
promotion for that session; the location foreground service still requires an
ordinary notification. Lock-screen content is private unless you allow it.

Tools → Directions → **Live notifications & Now Bar setup** opens notification
and available Live Update settings. On One UI, also check Settings → Lock screen
and AOD → Now Bar. OEM eligibility is controlled by Samsung; generic Android
emulator verification cannot guarantee Now Bar placement on a physical S22.
We do not masquerade as media playback or use hidden Samsung permissions.

Primary platform reference:
https://developer.android.com/develop/ui/views/notifications/live-update

## GitHub app updates

Tools or Settings → **App updates · GitHub** shows Current and Next versions.
Review a release, Download, then Install verified update. WorkManager stages
the download outside the installed APK; cancellation/failure leaves the current
app installed. SHA-256, size, package, increasing version code, Android minimum,
installed certificate and full APK cryptographic signature are checked. Android
then requests install-source permission and installation confirmation. APKs
are reverified immediately before handoff. Local data survives a compatible
upgrade. A foreground download has its own ordinary notification.

This is app-level staging, not Android system A/B partitions. An ordinary app
cannot silently replace itself, downgrade or automatically roll back after a
successful installation. Keep a portable profile backup. A recovery release
must use a compatible signing key and higher version code.

Public release repositories need no token. The default repository is private:
create a **fine-grained GitHub token**, select only `Xhanti-mbasa/cttrains-api`,
grant **Contents: read-only**, set an expiry, and enter it under Update source &
channel. Metadata read access is automatic. Do not use a password or write/admin
token. Device credentials are encrypted with Android Keystore and excluded from
profile backups. HTTPS redirects never receive the GitHub Authorization header.
Release asset URLs are constructed from validated repository/asset IDs; arbitrary
download hosts are rejected.

Beta releases can be included independently. Optional daily checks require a
connection, notify once per new version and never automatically install an APK.
Only the latest 30 release records are inspected. A release without update.json
is not an update candidate. If the repository has no accessible compatible
release, the app explains that; it never claims a download is available.

## Publisher setup

CI creates `update.json` from the exact verified release APK and publishes it
with the APK, checksum and certificate fingerprints. Main/tag builds publish
only after build/lint/core/native checks; PR builds remain artifacts.

Configure all four repository Actions secrets with the same persistent key used
by installed APKs: `CTTRAINS_KEYSTORE_BASE64`, `CTTRAINS_KEYSTORE_PASSWORD`,
`CTTRAINS_KEY_ALIAS`, `CTTRAINS_KEY_PASSWORD`. Do not commit keys. The separately
saved development keystore used for 1.8.1 and this provided 1.9.0 APK keeps their
signatures compatible. Workflow-generated per-run debug keys remain test
artifacts/prereleases and are not compatible upgrades for this installed key.
A public releases mirror is an option if users should not need private repo
access; point Update source at it, preserving the signing key and asset format.

Update manifests use schema 1: packageName, versionName, versionCode, minSdk,
apk, sizeBytes, sha256, certificateSha256. The manifest version must match the
`android-vVERSION` release tag. Corrupt, changed, wrong-key or replayed APKs are
rejected; no automatic data reset or uninstall is performed.
