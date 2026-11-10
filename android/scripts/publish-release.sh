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

[[ "$SIGNING_MODE" == production ]] || { echo 'The update feed requires a persistent signing key.' >&2; exit 1; }
manifest=release-assets/update.json
jq -e --arg version "$VERSION" '
    .schema == 1 and .packageName == "za.co.cttrains.app" and .versionName == $version and
    (.versionCode | type == "number") and (.certificateSha256 | test("^[a-f0-9]{64}$"))
' "$manifest" >/dev/null
version_code=$(jq -r .versionCode "$manifest")
certificate=$(jq -r .certificateSha256 "$manifest")

prerelease=true
if [[ "$VERSION" != *-* ]]; then
    prerelease=false
fi
notes=$(mktemp)
trap 'rm -f -- "$notes"' EXIT
{
    printf 'Native Kotlin / Google Material 3 Android app.\n\n'
    printf 'Built from commit `%s`. Tests, debug/release lint and both APK builds passed.\n\n' "$SOURCE_SHA"
    printf 'APK signed with the configured persistent release key.\n'
    printf '\nDownload the APK below. Its SHA-256 checksum and public signing certificate fingerprints are provided alongside it.\n'
    printf '\nIn-app updates use `update.json` and require the same signing certificate as the installed app.\n'
    printf '\nActive directions support Android 16 Live Updates, with a standard ongoing notification on older versions. Now Bar placement remains controlled by Samsung firmware and user settings. Scheduled transit times are not live vehicle arrivals.\n'
    printf '\nBus operators, community reports and live trip sharing are optional beta features in Settings. Timetables are published schedules; confirm current operation and boarding points. Live sharing requires separate hosted Matrix accounts and device validation.\n'
} > "$notes"

# Compare only releases in the same channel that can update the same installations.
# Ignore older development signing keys, desktop tags and unrecognized manifests.
tmp=$(mktemp -d)
trap 'rm -f -- "$notes"; rm -rf -- "$tmp"' EXIT
gh api --paginate "repos/$GH_REPO/releases?per_page=100" --jq '
  .[] | select(.draft == false) | [.tag_name, .prerelease] | @tsv
' > "$tmp/releases"
older=()
while IFS=$'\t' read -r tag beta; do
    [[ "$tag" != "$TAG" && "$beta" == "$prerelease" &&
       "$tag" =~ ^android-v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9]+([.-][A-Za-z0-9]+)*)?$ ]] || continue
    previous="$tmp/$tag.json"
    if ! gh release download "$tag" --pattern update.json --output "$previous" >/dev/null 2>&1; then
        continue
    fi
    code=$(jq -er --arg cert "$certificate" --arg version "${tag#android-v}" '
        select(.schema == 1 and .packageName == "za.co.cttrains.app" and
            .certificateSha256 == $cert and .versionName == $version) |
        .versionCode | select(type == "number" and . > 0 and . <= 2100000000 and . == floor)
    ' "$previous") || continue
    if (( code >= version_code )); then
        echo "Skipping $TAG because $tag already provides this channel a newer or equal version."
        exit 0
    fi
    older+=("$tag")
done < "$tmp/releases"

if gh release view "$TAG" >/dev/null 2>&1; then
    # A retry can finish an interrupted draft without replacing a completed release.
    draft=$(gh release view "$TAG" --json isDraft --jq .isDraft)
    if [[ "$draft" == false ]]; then
        gh release download "$TAG" --pattern update.json --output "$tmp/current.json"
        cmp -s "$manifest" "$tmp/current.json" || { echo 'Existing release has different APK metadata.' >&2; exit 1; }
    fi
else
    gh release create "$TAG" --target "$SOURCE_SHA" --title "CT Trains $VERSION" --notes-file "$notes" --draft
    draft=true
fi
if [[ "$draft" == true ]]; then
    gh release upload "$TAG" "${assets[@]}" --clobber
    gh release edit "$TAG" --title "CT Trains $VERSION" --notes-file "$notes" --prerelease="$prerelease" --draft=false
fi

# Keep all previous releases if the replacement APK or its manifest is unavailable.
remote=$(gh release view "$TAG" --json isDraft,assets,url)
apk=$(jq -r .apk "$manifest")
apk_size=$(jq -r .sizeBytes "$manifest")
jq -e --arg apk "$apk" --argjson size "$apk_size" '
    .isDraft == false and
    any(.assets[]; .name == "update.json" and .size > 0) and
    any(.assets[]; .name == $apk and .size == $size)
' <<< "$remote" >/dev/null
for tag in "${older[@]}"; do
    gh release delete "$tag" --yes
done
jq -r .url <<< "$remote"
