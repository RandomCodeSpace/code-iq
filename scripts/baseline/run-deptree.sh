#!/usr/bin/env bash
set -euo pipefail
OUT="docs/superpowers/baselines/2026-04-17/raw"
mkdir -p "$OUT"
mvn -B dependency:tree -DoutputType=text -DoutputFile="$PWD/$OUT/dep-tree.txt"
mvn -B license:aggregate-third-party-report \
  -Dlicense.outputDirectory="$PWD/$OUT" 2>&1 | tee "$OUT/license.log" || true
# Fallback if license plugin not configured: list GAV+Maven Central license hints.
if [[ ! -f "$OUT/aggregate-third-party-report.html" ]]; then
  mvn -B dependency:list -DincludeScope=runtime -DoutputFile="$PWD/$OUT/dep-licenses.txt" || true
fi
ls "$OUT" | grep -E '^(dep-tree|dep-licenses|aggregate-third-party)' || true
