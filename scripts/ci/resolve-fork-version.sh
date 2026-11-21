#!/usr/bin/env bash
#
# Resolve the next auto-incremented fork release version (no commit back to main needed).
#
#   base      = upstream versionName in app/build.gradle.kts without its pre-release suffix
#               ("1.18.0-SNAPSHOT" -> "1.18.0")
#   N         = 1 + the highest N among existing tags v<base>-fork.N
#   name      = <base>-fork.<N>          tag = v<name>
#   code      = upstream versionCode * 100 + N   (the per-ABI split then makes it *10+abi)
#
# If the commit being released already carries a v<base>-fork.N tag, that version is
# reused (REUSED=true) so re-runs are idempotent. Codes only ever grow: N grows within a
# base, and an upstream bump raises versionCode (N must stay below 100 per base).
#
# Env: RELEASE_SHA (required), GRADLE_FILE (default app/build.gradle.kts),
#      GITHUB_OUTPUT (optional; outputs are appended there too).
set -euo pipefail

sha="${RELEASE_SHA:?RELEASE_SHA must be set}"
gradle_file="${GRADLE_FILE:-app/build.gradle.kts}"

literal() {  # the fallback literal after `?: ` on the given property line
    sed -nE "s/^[[:space:]]*$1 = .*\\?: \"?([^\"]+)\"?[[:space:]]*\$/\\1/p" "$gradle_file" | head -n 1
}
upstream_name="$(literal versionName)"
upstream_code="$(literal versionCode)"
if [[ -z "$upstream_name" || ! "$upstream_code" =~ ^[0-9]+$ ]]; then
    echo "Cannot read versionName/versionCode fallbacks from $gradle_file" >&2
    exit 1
fi
base="${upstream_name%%-*}"
if [[ ! "$base" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Upstream versionName base is not MAJOR.MINOR.PATCH: $upstream_name" >&2
    exit 1
fi

prefix="v${base}-fork."
n_of() { local t="$1"; local n="${t#"$prefix"}"; [[ "$n" =~ ^[1-9][0-9]*$ ]] && echo "$n"; }

reused=false
n=""
while IFS= read -r t; do
    m="$(n_of "$t" || true)"
    if [[ -n "$m" ]] && { [[ -z "$n" ]] || (( m > n )); }; then n="$m"; fi
done < <(git tag --points-at "$sha" -l "${prefix}*")
if [[ -n "$n" ]]; then
    reused=true
else
    max=0
    while IFS= read -r t; do
        m="$(n_of "$t" || true)"
        [[ -n "$m" ]] && (( m > max )) && max="$m"
    done < <(git tag -l "${prefix}*")
    n=$((max + 1))
fi
if (( n >= 100 )); then
    echo "Fork counter for $base reached $n; the versionCode scheme allows at most 99 per base." >&2
    exit 1
fi

version_name="${base}-fork.${n}"
version_code=$((upstream_code * 100 + n))
tag="v${version_name}"
short_sha="$(git rev-parse --short=7 "$sha")"

printf 'VERSION_NAME=%s\nVERSION_CODE=%s\nTAG=%s\nSHORT_SHA=%s\nREUSED=%s\n' \
    "$version_name" "$version_code" "$tag" "$short_sha" "$reused"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
        echo "version_name=$version_name"
        echo "version_code=$version_code"
        echo "tag=$tag"
        echo "short_sha=$short_sha"
        echo "reused=$reused"
    } >> "$GITHUB_OUTPUT"
fi
