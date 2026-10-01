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
assets=(release-assets/*.apk release-assets/*.sha256)
[[ ${#assets[@]} -eq 2 ]] || { echo 'Expected one APK and its SHA-256 checksum.' >&2; exit 1; }
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
    printf '\nDownload the APK below. Its SHA-256 checksum is provided alongside it.\n'
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
