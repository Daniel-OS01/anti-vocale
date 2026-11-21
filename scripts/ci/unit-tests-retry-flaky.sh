#!/usr/bin/env bash
#
# Run the JVM unit tests; if a FEW test classes fail, re-run only those classes once.
#
# Why: several upstream tests wait on real coroutines with wall-clock timeouts
# (e.g. VoiceNoteBusinessCoordinatorTest's withTimeout(5_000) polling, upstream's
# LogsViewModelBatchIntakeTest) and fail intermittently on busy CI runners while the
# same tree passes on the next run. Tests are not modified or skipped: a class that
# fails twice, a build/compile failure (no failing test reports), or more than
# MAX_RETRY_CLASSES failing classes (a real breakage) still fails the job.
# Classes that pass on the retry are listed as flaky in the job summary.
#
# Env: GRADLEW (default ./gradlew), TEST_TASK (default :app:testFdroidDebugUnitTest),
#      RESULTS_DIR (default app/build/test-results/testFdroidDebugUnitTest),
#      MAX_RETRY_CLASSES (default 3), GITHUB_STEP_SUMMARY (optional).
set -uo pipefail

GRADLEW="${GRADLEW:-./gradlew}"
TEST_TASK="${TEST_TASK:-:app:testFdroidDebugUnitTest}"
RESULTS_DIR="${RESULTS_DIR:-app/build/test-results/testFdroidDebugUnitTest}"
MAX_RETRY_CLASSES="${MAX_RETRY_CLASSES:-3}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

failed_classes() {
    # Classnames of <testcase> elements carrying <failure> or <error>, from the JUnit XML reports.
    python3 - "$RESULTS_DIR" <<'PY'
import glob, os, sys
import xml.etree.ElementTree as ET
names = set()
for path in glob.glob(os.path.join(sys.argv[1], "*.xml")):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            names.add(case.get("classname"))
print("\n".join(sorted(n for n in names if n)))
PY
}

"$GRADLEW" "$TEST_TASK" --console=plain
status=$?
[ "$status" -eq 0 ] && exit 0

mapfile -t classes < <(failed_classes | tr -d '\r' | sed '/^[[:space:]]*$/d')
if [ "${#classes[@]}" -eq 0 ]; then
    echo "Test task failed without failing test reports (build or compile error): not retrying."
    exit "$status"
fi
if [ "${#classes[@]}" -gt "$MAX_RETRY_CLASSES" ]; then
    echo "${#classes[@]} test classes failed (> $MAX_RETRY_CLASSES): treating as a real failure, not retrying."
    printf '  %s\n' "${classes[@]}"
    exit "$status"
fi

echo "::warning::Re-running ${#classes[@]} failed test class(es) once: ${classes[*]}"
args=()
for c in "${classes[@]}"; do args+=(--tests "$c"); done
"$GRADLEW" "$TEST_TASK" "${args[@]}" --console=plain
retry=$?
if [ "$retry" -ne 0 ]; then
    echo "Failed again on retry: ${classes[*]}"
    exit "$retry"
fi

{
    echo "### Flaky unit tests (failed once, passed on retry)"
    printf -- '- `%s`\n' "${classes[@]}"
} >> "$SUMMARY"
echo "::warning::Flaky test class(es) passed on retry: ${classes[*]}"
exit 0
