#!/usr/bin/env bash
set -euo pipefail
apk=app/build/outputs/apk/release/app-release.apk
"${ANDROID_HOME}/build-tools/35.0.0/apksigner" verify --verbose "$apk"
"${ANDROID_HOME}/build-tools/35.0.0/aapt" dump badging "$apk" > "${RUNNER_TEMP}/cttrains-badging.txt"
if grep -q '^application-debuggable' "${RUNNER_TEMP}/cttrains-badging.txt"; then
  echo 'Release variant must not be debuggable.' >&2
  exit 1
fi
mkdir -p "$RELEASE_ASSETS_DIR"
filename="CT-Trains-${VERSION}-${SIGNING_MODE}.apk"
cp "$apk" "$RELEASE_ASSETS_DIR/$filename"
python3 scripts/write-update-manifest.py --apk "$RELEASE_ASSETS_DIR/$filename" --output "$RELEASE_ASSETS_DIR/update.json"
(cd "$RELEASE_ASSETS_DIR" && sha256sum "$filename" > "$filename.sha256")
"${ANDROID_HOME}/build-tools/35.0.0/apksigner" verify --print-certs "$apk" > "${RUNNER_TEMP}/cttrains-certificates.txt"
python3 - <<'PY'
import os, pathlib, re
raw = (pathlib.Path(os.environ['RUNNER_TEMP']) / 'cttrains-certificates.txt').read_text()
lines = ['Package: za.co.cttrains.app', 'Certificate fingerprints for this exact APK:']
for label in ('SHA-1', 'SHA-256'):
    found = re.search(r'Signer #1 certificate ' + label + r' digest: ([a-fA-F0-9]+)', raw)
    if found is None:
        raise SystemExit('Missing signing certificate fingerprint')
    value = found.group(1).upper()
    lines.append(label + ': ' + ':'.join(value[i:i+2] for i in range(0,len(value),2)))
(pathlib.Path(os.environ['RELEASE_ASSETS_DIR']) / 'signing-certificate.txt').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
PY
