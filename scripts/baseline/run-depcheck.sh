#!/usr/bin/env bash
# Run OWASP dependency-check in aggregate mode, emit HTML + JSON reports.
set -euo pipefail
OUT="docs/superpowers/baselines/2026-04-17/raw"
mkdir -p "$OUT"
mvn -B -DnvdApiDelay=6000 dependency-check:aggregate \
  -Dformats=HTML,JSON 2>&1 | tee "$OUT/depcheck.log"
cp target/dependency-check-report.html "$OUT/depcheck.html" 2>/dev/null || true
cp target/dependency-check-report.json "$OUT/depcheck.json" 2>/dev/null || true

python3 - <<'PY' > "$OUT/depcheck-summary.json"
import json, os, collections
p="docs/superpowers/baselines/2026-04-17/raw/depcheck.json"
if not os.path.exists(p):
    print(json.dumps({"error":"no depcheck.json"}, indent=2)); raise SystemExit
d=json.load(open(p))
sev=collections.Counter()
top=[]
for dep in d.get("dependencies",[]):
    for v in dep.get("vulnerabilities",[]) or []:
        s=(v.get("severity") or "UNKNOWN").upper()
        sev[s]+=1
        top.append({"fileName":dep.get("fileName"),"cve":v.get("name"),"severity":s,"cvss":v.get("cvssv3",{}).get("baseScore")})
top.sort(key=lambda x: -(x.get("cvss") or 0))
print(json.dumps({"by_severity":dict(sev),"top_25":top[:25]}, indent=2))
PY
cat "$OUT/depcheck-summary.json"
