#!/usr/bin/env bash
# Tests for scripts/ci/resolve-fork-version.sh against throwaway git repositories.
# Run: bash scripts/ci/test-resolve-fork-version.sh   (CI runs it in ci.yml)
set -euo pipefail

resolver="$(cd "$(dirname "$0")" && pwd)/resolve-fork-version.sh"
# Never let a caller's git context (e.g. a hook's GIT_DIR) leak into the scratch repos.
while IFS= read -r v; do unset "$v"; done < <(env | sed -n 's/^\(GIT_[A-Za-z0-9_]*\)=.*/\1/p')
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fails=0

new_repo() {  # $1 = .fork-version content; leaves cwd in a fresh repo with one commit
    local d="$work/repo$RANDOM$RANDOM"
    mkdir "$d" && cd "$d"
    [[ "$(pwd -P)" == "$(cd "$work" && pwd -P)"/* ]] || { echo "scratch repo escaped $work" >&2; exit 2; }
    git init -q . && git config user.email t@t && git config user.name t && git config commit.gpgsign false
    printf '%s\n' "$1" > .fork-version
    git add .fork-version && git commit -q -m init
}
commit() { git commit -q --allow-empty -m "$1"; }
run() { RELEASE_SHA="$(git rev-parse HEAD)" bash "$resolver" 2>&1; }
expect() {  # $1 = case name, $2 = expected substring(s) separated by '|'
    local out rc=0; out="$(run)" || rc=$?
    local IFS='|'; for want in $2; do
        if [[ "$want" == "FAIL" ]]; then
            (( rc != 0 )) || { echo "FAIL [$1]: expected a non-zero exit"; echo "$out"; fails=$((fails+1)); return; }
        elif [[ "$out" != *"$want"* ]]; then
            echo "FAIL [$1]: missing '$want'"; echo "$out"; fails=$((fails+1)); return
        fi
    done
    echo "ok   [$1]"
}

new_repo 1.2
git tag v1.2.0; commit upstream; git tag v1.17.0; commit legacy; git tag v1.18.0-fork.10; commit next
expect "first release ignores upstream and legacy tags" \
    "VERSION_NAME=1.2.0|VERSION_CODE=1002000|TAG=fork-v1.2.0|REUSED=false|UPSTREAM=v1.17.0"

git tag fork-v1.2.0; commit feature
expect "next release bumps the patch" "VERSION_NAME=1.2.1|VERSION_CODE=1002001|TAG=fork-v1.2.1"

git tag fork-v1.2.1
expect "an already-tagged commit is reused" "VERSION_NAME=1.2.1|REUSED=true"

commit minor; printf '1.3\n' > .fork-version
expect "a .fork-version minor bump restarts the patch" "VERSION_NAME=1.3.0|VERSION_CODE=1003000"

printf '1.1\n' > .fork-version
expect "a .fork-version that goes backwards fails" "FAIL|went backwards"

printf '1.2.0\n' > .fork-version
expect "a malformed .fork-version fails" "FAIL|must hold MAJOR.MINOR"

new_repo 1.2
git tag fork-v1.2.999; commit more
expect "patch overflow fails" "FAIL|reached 1000"

new_repo 1.2
expect "no upstream tag leaves UPSTREAM empty" "VERSION_NAME=1.2.0|UPSTREAM="

if (( fails )); then echo "$fails case(s) failed" >&2; exit 1; fi
echo "all resolver cases passed"
