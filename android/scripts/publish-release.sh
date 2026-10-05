#!/usr/bin/env bash
set -euo pipefail

[[ "$TAG" =~ ^android-v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$ ]] || { echo 'Invalid release tag.' >&2; exit 1; }
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid source commit.' >&2; exit 1; }

# Never attach an APK built from one commit to a tag that points at another.
if git show-ref --verify --quiet "refs/tags/$TAG"; then
    actual=$(git rev-list -n 1 "$TAG")
    [[ "$actual" == "$SOURCE_SHA" ]] || { echo 'Release tag points at a different commit.' >&2; exit 1; }
fi

shopt -s nullglob
assets=(release-assets/*.apk release-assets/*.sha256 release-assets/signing-certificate.txt release-assets/update.json)
[[ ${#assets[@]} -eq 4 && -f release-assets/signing-certificate.txt && -f release-assets/update.json ]] || { echo 'Expected one APK, checksum, signing certificate fingerprints and update manifest.' >&2; exit 1; }
(cd release-assets && sha256sum -c ./*.sha256)

prerelease=true
if [[ "$SIGNING_MODE" == production && "$VERSION" != *-* ]]; then
    prerelease=false
fi
notes=$(mktemp)
trap 'rm -f -- "$notes"' EXIT
{
    printf 'Native Kotlin / Google Material 3 Android app.\n\n'
    printf 'Built from commit `%s`. Tests, debug/release lint and both APK builds passed.\n\n' "$SOURCE_SHA"
    if [[ "$SIGNING_MODE" == production ]]; then
        printf 'APK signed with the configured private release key.\n'
    else
        printf '**Development signing:** installable test APK, signed with an Android debug key. The key can differ between workflow runs; uninstall an older debug-signed APK before installing if Android reports a signature mismatch.\n'
    fi
    printf '\nDownload the APK below. Its SHA-256 checksum and public signing certificate fingerprints are provided alongside it.\n'
    printf '\nIn-app GitHub updates use `update.json` and require the same signing key as the installed app. Configure the four signing secrets with a persistent key for compatible upgrades; per-run development keys cannot update an existing installation. Private repositories also need a device-configured Contents: read-only token.\n'
    printf '\nActive directions support Android 16 Live Updates, with a standard ongoing notification on older versions. Now Bar placement remains controlled by Samsung firmware and user settings. Scheduled transit times are not live vehicle arrivals.\n'
    printf '\nBus operators, community reports and live trip sharing are optional beta features in Settings. Timetables are published schedules; confirm current operation and boarding points. Live sharing requires separate hosted Matrix accounts and device validation.\n'
} > "$notes"

if gh release view "$TAG" >/dev/null 2>&1; then
    # A retry can finish an interrupted draft without replacing a completed release.
    draft=$(gh release view "$TAG" --json isDraft --jq .isDraft)
    if [[ "$draft" == false ]]; then
        echo "Release $TAG is already published; leaving its assets unchanged."
        exit 0
    fi
else
    gh release create "$TAG" --target "$SOURCE_SHA" --title "CT Trains $VERSION" --notes-file "$notes" --draft
fi
gh release upload "$TAG" "${assets[@]}" --clobber
gh release edit "$TAG" --title "CT Trains $VERSION" --notes-file "$notes" --prerelease="$prerelease" --draft=false
gh release view "$TAG" --json url --jq .url
