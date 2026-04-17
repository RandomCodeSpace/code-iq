#!/usr/bin/env bash
# Re-run the test suite N times and record the union of failing tests.
# Any test that appears in the failure set for some runs but not others is flaky.
set -euo pipefail

N="${N:-3}"
OUT="docs/superpowers/baselines/2026-04-17/raw"
mkdir -p "$OUT/flaky"

for i in $(seq 1 "$N"); do
  echo "[flaky] run $i/$N"
  mvn -B -fae -DfailIfNoTests=false test 2>&1 | tail -n 200 >"$OUT/flaky/run-$i.log"
  mkdir -p "$OUT/flaky/run-$i-sfxml"
  cp -r target/surefire-reports "$OUT/flaky/run-$i-sfxml/" || true
done

python3 - <<'PY' > "$OUT/flaky.json"
import glob, json, xml.etree.ElementTree as ET, os
N=int(os.environ.get("N","3"))
per_run_fail=[]
for i in range(1, N+1):
    fails=set()
    for f in glob.glob(f"docs/superpowers/baselines/2026-04-17/raw/flaky/run-{i}-sfxml/surefire-reports/TEST-*.xml"):
        r=ET.parse(f).getroot()
        for tc in r.iter("testcase"):
            if list(tc.findall("failure")) or list(tc.findall("error")):
                fails.add(f"{tc.attrib.get('classname','')}#{tc.attrib.get('name','')}")
    per_run_fail.append(sorted(fails))
intersect=set(per_run_fail[0])
union=set()
for s in per_run_fail: intersect &= set(s); union |= set(s)
flaky=sorted(union - intersect)
print(json.dumps({
  "runs": N,
  "failures_per_run": [len(s) for s in per_run_fail],
  "always_failing": sorted(intersect),
  "flaky": flaky,
}, indent=2))
PY
cat "$OUT/flaky.json"
