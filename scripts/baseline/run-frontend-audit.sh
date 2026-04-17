#!/usr/bin/env bash
set -euo pipefail
OUT="docs/superpowers/baselines/2026-04-17/raw/frontend"
mkdir -p "$OUT"

cd src/main/frontend
npm ci 2>&1 | tee "$OLDPWD/$OUT/npm-ci.log"
npm audit --json > "$OLDPWD/$OUT/npm-audit.json" || true
npm run build 2>&1 | tee "$OLDPWD/$OUT/build.log"
# Playwright — allow failure so we capture the baseline pass rate.
npx playwright install --with-deps chromium 2>&1 | tee "$OLDPWD/$OUT/playwright-install.log" || true
npm run test:e2e -- --reporter=list 2>&1 | tee "$OLDPWD/$OUT/playwright.log" || true
cd -

python3 - <<'PY' > "$OUT/playwright-summary.json"
import re, json
log=open("docs/superpowers/baselines/2026-04-17/raw/frontend/playwright.log").read()
# "X passed, Y failed, Z skipped" at end
m=re.search(r"(\d+)\s+passed", log); passed=int(m.group(1)) if m else 0
m=re.search(r"(\d+)\s+failed", log); failed=int(m.group(1)) if m else 0
m=re.search(r"(\d+)\s+skipped", log); skipped=int(m.group(1)) if m else 0
print(json.dumps({"passed":passed,"failed":failed,"skipped":skipped}, indent=2))
PY
cat "$OUT/playwright-summary.json"
