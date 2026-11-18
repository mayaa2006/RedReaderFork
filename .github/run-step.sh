#!/usr/bin/env bash
# Runs a Gradle task and, if it fails, turns the most useful log lines into
# GitHub annotations (max 10 per step) so they can be read without the raw log.
set -o pipefail
task="$1"
./gradlew "$task" --continue 2>&1 | tee "/tmp/$task.log"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
	{
		# Lines pointing at a file and line number first, then general errors
		grep -E "^e: |\.(kt|java|xml):[0-9]+" "/tmp/$task.log"
		grep -E "What went wrong|FAILED|Lint found|PMD Failure|tests completed" "/tmp/$task.log"
	} | grep -v "^\s*at " | awk '!seen[$0]++' | head -10 \
		| while IFS= read -r line; do echo "::error title=$task::$line"; done
fi
exit "$status"
