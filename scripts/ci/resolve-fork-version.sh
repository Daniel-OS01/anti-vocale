#!/usr/bin/env bash
#
# Resolve the next fork release version (no commit back to main needed).
#
#   MAJOR.MINOR  = the one line in .fork-version (edit it by hand for a minor bump)
#   PATCH        = 1 + the highest P among tags fork-vMAJOR.MINOR.P, or 0 if none
#   name         = MAJOR.MINOR.PATCH          tag = fork-v<name>
#   code         = MAJOR*1000000 + MINOR*1000 + PATCH   (the per-ABI split then makes it *10+abi)
#   upstream     = the newest upstream release tag (vX.Y.Z) reachable from the commit,
#                  shown in the release notes as the upstream base
#
# The tag prefix is fork-v because the plain vX.Y.Z tags are upstream's releases.
# Version numbers are canonical (no leading zeros) and at most 3 digits each.
#
# If the commit being released already carries a fork-v tag, that version is reused
# (REUSED=true) so re-runs are idempotent, unless a newer fork release exists: re-publishing
# it could mark an APK older than the installed one as latest. Otherwise the new code must
# be greater than every earlier fork release (the old v1.18.0-fork.N scheme peaked below
# LEGACY_MAX_CODE), or Android refuses the update: a .fork-version that goes backwards
# fails here. The per-ABI code (code*10+4) must stay within Android's limit.
#
# Env: RELEASE_SHA (required), FORK_VERSION_FILE (default .fork-version),
#      GITHUB_OUTPUT (optional; outputs are appended there too).
set -euo pipefail

sha="${RELEASE_SHA:?RELEASE_SHA must be set}"
version_file="${FORK_VERSION_FILE:-.fork-version}"
LEGACY_MAX_CODE=5299          # v1.18.0-fork.N used 52*100+N, N <= 99
ANDROID_MAX_CODE=2100000000
NUM='(0|[1-9][0-9]{0,2})'

[[ -f "$version_file" ]] || { echo "Missing $version_file" >&2; exit 1; }
series="$(tr -d '[:space:]' < "$version_file")"
if [[ ! "$series" =~ ^$NUM\.$NUM$ ]]; then
    echo "$version_file must hold MAJOR.MINOR (e.g. 1.2, no leading zeros), got: '$series'" >&2
    exit 1
fi
major=${BASH_REMATCH[1]}; minor=${BASH_REMATCH[2]}

code_of() { echo $(( $1 * 1000000 + $2 * 1000 + $3 )); }
# Prints "MAJOR MINOR PATCH" for a canonical fork-vX.Y.Z tag; fails otherwise.
parse_tag() { [[ "$1" =~ ^fork-v$NUM\.$NUM\.$NUM$ ]] && echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]} ${BASH_REMATCH[3]}"; }

own=""
while IFS= read -r t; do
    parse_tag "$t" >/dev/null || continue
    if [[ -n "$own" ]]; then
        echo "Commit $sha carries several fork release tags ($own, $t); delete the extra ones." >&2
        exit 1
    fi
    own="$t"
done < <(git tag --points-at "$sha" -l 'fork-v*')

max_code=$LEGACY_MAX_CODE   # the highest code among the OTHER fork release tags
patch=0
while IFS= read -r t; do
    [[ "$t" == "$own" ]] && continue
    parts="$(parse_tag "$t")" || continue
    read -r a b c <<<"$parts"
    code="$(code_of "$a" "$b" "$c")"
    (( code > max_code )) && max_code=$code
    if (( a == major && b == minor && c + 1 > patch )); then patch=$((c + 1)); fi
done < <(git tag -l 'fork-v*')

if [[ -n "$own" ]]; then
    reused=true
    read -r a b c <<<"$(parse_tag "$own")"
    version_name="$a.$b.$c"
    version_code="$(code_of "$a" "$b" "$c")"
    if (( version_code <= max_code )); then
        echo "$own is superseded by a newer fork release (code $max_code); not re-publishing it." >&2
        exit 1
    fi
else
    reused=false
    (( patch <= 999 )) || { echo "Patch for $series reached $patch; bump $version_file" >&2; exit 1; }
    version_name="$major.$minor.$patch"
    version_code="$(code_of "$major" "$minor" "$patch")"
    if (( version_code <= max_code )); then
        echo "versionCode $version_code ($version_name) is not above the newest fork release ($max_code):" \
             "$version_file went backwards. Android would refuse the update." >&2
        exit 1
    fi
fi
if (( version_code * 10 + 4 > ANDROID_MAX_CODE )); then
    echo "versionCode $version_code exceeds Android's limit once split per ABI; lower $version_file" >&2
    exit 1
fi

tag="fork-v${version_name}"
short_sha="$(git rev-parse --short=7 "$sha")"
upstream="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' --exclude '*-*' "$sha" 2>/dev/null || true)"

printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nTAG=%s\nSHORT_SHA=%s\nREUSED=%s\nUPSTREAM=%s\n' \
    "$version_name" "$version_code" "$tag" "$short_sha" "$reused" "$upstream"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        echo "version_name=$version_name"
        echo "version_code=$version_code"
        echo "tag=$tag"
        echo "short_sha=$short_sha"
        echo "reused=$reused"
        echo "upstream=$upstream"
    } >> "$GITHUB_OUTPUT"
fi
