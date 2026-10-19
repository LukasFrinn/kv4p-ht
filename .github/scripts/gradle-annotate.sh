#!/usr/bin/env bash
# Runs a Gradle command; on failure, turns compiler errors, test failures and
# Gradle's "What went wrong" block into GitHub annotations, so they can be read
# from the run summary / API without downloading the raw log.
set -o pipefail
"$@" 2>&1 | tee gradle-output.log
status=$?
if [ $status -ne 0 ]; then
  { grep -E '^e: |FAILED$|^\s+(java\.|org\.junit\.|kotlin\.)[A-Za-z.]*(Error|Exception)|^\s+at com\.(vagell|kv4p)' gradle-output.log
    grep -A12 '^\* What went wrong' gradle-output.log | grep -v '^\* Try:' ; } \
    | grep -v '^> Task ' \
    | head -45 \
    | while IFS= read -r line; do echo "::error title=gradle::${line}"; done
fi
exit $status
