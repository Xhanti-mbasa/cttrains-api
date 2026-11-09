# Optional Google profile setup

Maps and normal app use need no Google API key or account. The Google option uses Android Credential Manager and is a local profile convenience; it does not create a Matrix account, cloud database or cross-device profile sync.

The supplied client ID, `772311244758-df42943imv3o60523nbfresvetcieehf.apps.googleusercontent.com`, was confirmed to be an **Android** OAuth client. Do not pass it to `GetSignInWithGoogleOption` as a Web client ID.

In the same Google Cloud / Google Auth Platform project:

1. Configure the consent/branding/audience settings and test users where required.
2. Keep an **Android** client registered for package **`za.co.cttrains.app`** and the signing certificate’s **SHA-1**.
3. Create a **Web application** OAuth client. Its public client ID is needed by Credential Manager. No client secret goes in the APK or this repository.
4. Set repository **Actions variable** `CTTRAINS_GOOGLE_WEB_CLIENT_ID` to that Web application client ID. For local builds, use that environment variable or the same property in untracked `android/local.properties`.
5. Rebuild. If absent, Settings shows sign-in as awaiting configuration; local profiles and Matrix remain usable.

The APK artifact and GitHub release include **`signing-certificate.txt`** for the exact build. It gives the public SHA-1/SHA-256 fingerprints, also printed in the build log. Development signing keys can change between clean GitHub runners. Use a stable private production signing key in the existing four `CTTRAINS_KEYSTORE_*` repository secrets before expecting OAuth configuration or app upgrades to remain stable across releases. Never send a private keystore/password/client secret in chat or commit it. The Web client ID and certificate fingerprints are public metadata.

The sign-in button opens the credential provider’s own account UI, requests a random nonce, verifies Google’s RSA signature using the HTTPS Google JWKS endpoint, and checks issuer, audience, nonce and expiry. Raw ID tokens are not stored/exported/logged. Optional verified profile fields are Keystore-encrypted and excluded from portable backups. Disconnect clears the local profile binding and credential-provider session state. This client-side check is for local personalization; a future backend must independently verify an ID token before granting server privileges.

microG may implement some Play Services interfaces on supported devices. It does **not** remove OAuth registration, guarantee Credential Manager compatibility, or provide shared storage/friend tracking. Do not add a fake Google login or reuse another app’s OAuth client.

Official references: [Credential Manager Google prerequisites](https://developer.android.com/identity/sign-in/credential-manager-siwg), [implementation](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation), [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [token verification](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token).
