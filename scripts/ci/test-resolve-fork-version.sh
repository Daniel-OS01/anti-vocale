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
set_series() { printf '%s\n' "$1" > .fork-version; }

# ok "case" LINE...   : exit 0, and every LINE appears as a whole output line
ok() {
    local name="$1"; shift
    local out rc=0; out="$(RELEASE_SHA="$(git rev-parse HEAD)" bash "$resolver" 2>&1)" || rc=$?
    if (( rc != 0 )); then echo "FAIL [$name]: exit $rc"; echo "$out"; fails=$((fails+1)); return; fi
    local line; for line in "$@"; do
        grep -qxF -- "$line" <<<"$out" || { echo "FAIL [$name]: no line '$line'"; echo "$out"; fails=$((fails+1)); return; }
    done
    echo "ok   [$name]"
}
# bad "case" TEXT     : non-zero exit, and stderr/stdout mentions TEXT
bad() {
    local out rc=0; out="$(RELEASE_SHA="$(git rev-parse HEAD)" bash "$resolver" 2>&1)" || rc=$?
    if (( rc == 0 )) || [[ "$out" != *"$2"* ]]; then
        echo "FAIL [$1]: expected failure mentioning '$2' (exit $rc)"; echo "$out"; fails=$((fails+1)); return
    fi
    echo "ok   [$1]"
}

new_repo 1.2
git tag v1.2.0; commit upstream; git tag v1.17.0; commit legacy; git tag v1.18.0-fork.10; commit next
ok "first release ignores upstream and legacy tags" \
    VERSION_NAME=1.2.0 VERSION_CODE=1002000 TAG=fork-v1.2.0 REUSED=false UPSTREAM=v1.17.0

git tag fork-v1.2.0; commit feature
ok "next release bumps the patch" VERSION_NAME=1.2.1 VERSION_CODE=1002001 TAG=fork-v1.2.1 REUSED=false

git tag fork-v1.2.1
ok "an already-tagged commit is reused" VERSION_NAME=1.2.1 VERSION_CODE=1002001 TAG=fork-v1.2.1 REUSED=true

git checkout -q HEAD~1
bad "a superseded tagged commit is not re-published" "superseded"
git checkout -q -

commit minor; set_series 1.3
ok "a .fork-version minor bump restarts the patch" VERSION_NAME=1.3.0 VERSION_CODE=1003000 TAG=fork-v1.3.0

git tag fork-v1.2.07
ok "a non-canonical fork tag is ignored" VERSION_NAME=1.3.0 REUSED=false
git tag -d fork-v1.2.07 >/dev/null

set_series 1.1
bad "a .fork-version that goes backwards fails" "went backwards"

set_series 1.2.0
bad "a malformed .fork-version fails" "must hold MAJOR.MINOR"
set_series 01.3
bad "a leading-zero .fork-version fails" "must hold MAJOR.MINOR"

set_series 1.3; git tag fork-v1.3.0; git tag fork-v1.3.1
bad "two fork tags on one commit fail" "several fork release tags"

new_repo 1.2
git tag fork-v1.2.999; commit more
bad "patch overflow fails" "reached 1000"

new_repo 210.0
bad "a code beyond Android's limit fails" "exceeds Android's limit"

new_repo 209.999
ok "the largest code within Android's limit passes" VERSION_CODE=209999000

new_repo 1.2
ok "no upstream tag leaves UPSTREAM empty" VERSION_NAME=1.2.0 UPSTREAM=

if (( fails )); then echo "$fails case(s) failed" >&2; exit 1; fi
echo "all resolver cases passed"
