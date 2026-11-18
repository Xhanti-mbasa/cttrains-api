# Uber ride handoff (beta, #26)

Off by default. Turn it on in **Mine → Beta features → Uber ride handoff**.

## What it does now

On a trip page, the **Uber · beta** card offers up to two buttons:

- **Uber to <station or stop>**: from your chosen start (or your current location in Uber) to the boarding point. Shown only for an upcoming departure, and not when the trip already starts at that station. It suggests a drop-off time 5 minutes before departure.
- **Uber from <station or stop> to <destination>**: the last mile from where you get off.

Each button shows a confirmation that lists exactly what Uber will receive. Only then does CT Trains open Uber's official universal link (`https://m.uber.com/ul/?action=setPickup…`). The rider picks the ride type and sees Uber's price and pickup time in Uber, and confirms or cancels there. CT Trains does not book, cancel or pay for rides, holds no Uber account or token, and makes no Uber requests. The link carries locations and is never logged.

Without the Uber app installed, Android opens Uber's website or Play Store page. Turning the beta off removes the card. There is no background work to stop.

## Configuration

Optional: set the repository **Actions variable** `CTTRAINS_UBER_CLIENT_ID` to the app's public **Application ID** from the Uber developer dashboard. For local builds, use the same property in untracked `android/local.properties`. Never put the Uber **client secret** in this repository, the APK or a variable: it is not needed for the handoff.

Uber App Signatures entry for the production signing key (base64 SHA-1): `mz6KZZH2KswOnAeuluTMJOUXbLE=`. The Lab package needs its own entry if its key differs.

## Not yet available: estimates and booking in CT Trains

Uber Riders API ride scopes require Uber's approval. The CTTRAINS app was created under the **Others** API suite and has no ride scopes. When Uber grants access:

1. Keep the client secret on a backend (for example a route in `src/`), never in the app.
2. Request only the scopes needed for estimates. Label estimates with their check time, and keep ride confirmation in Uber.
3. Use Uber's sandbox for tests; automated tests must never create real rides or charges.
