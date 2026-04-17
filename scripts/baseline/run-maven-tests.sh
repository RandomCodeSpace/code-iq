#!/usr/bin/env bash
# Run full Maven verify with JaCoCo coverage; capture timings and outputs.
set -euo pipefail

OUT="docs/superpowers/baselines/2026-04-17/raw"
mkdir -p "$OUT"

start=$(date +%s)
# -B batch mode, -fae fail-at-end so all test classes run
mvn -B -fae \
  -DfailIfNoTests=false \
  clean verify jacoco:report \
  2>&1 | tee "$OUT/maven-test.log"
rc="${PIPESTATUS[0]}"
end=$(date +%s)
echo "[baseline] maven exit=$rc duration_s=$((end-start))" | tee -a "$OUT/maven-test.log"

# Coverage CSV (stable, parseable)
if [[ -f target/site/jacoco/jacoco.csv ]]; then
  cp target/site/jacoco/jacoco.csv "$OUT/jacoco.csv"
fi
# Surefire/Failsafe XML (for flaky scan + test counts)
if [[ -d target/surefire-reports ]]; then
  tar -cf "$OUT/surefire-reports.tar" -C target surefire-reports
fi
if [[ -d target/failsafe-reports ]]; then
  tar -cf "$OUT/failsafe-reports.tar" -C target failsafe-reports
fi

exit "$rc"
