#!/usr/bin/env bash
# Run SpotBugs and capture XML + summary JSON.
set -euo pipefail
OUT="docs/superpowers/baselines/2026-04-17/raw"
mkdir -p "$OUT"
mvn -B spotbugs:spotbugs 2>&1 | tee "$OUT/spotbugs.log"
# spotbugsXml.xml default location
if [[ -f target/spotbugsXml.xml ]]; then
  cp target/spotbugsXml.xml "$OUT/spotbugs.xml"
fi

python3 - <<'PY' > "$OUT/spotbugs-summary.json"
import xml.etree.ElementTree as ET, json, collections, os
path="docs/superpowers/baselines/2026-04-17/raw/spotbugs.xml"
if not os.path.exists(path):
    print(json.dumps({"error":"no spotbugs.xml produced"}, indent=2)); raise SystemExit
root=ET.parse(path).getroot()
by_priority=collections.Counter()
by_category=collections.Counter()
by_pattern =collections.Counter()
for b in root.iter("BugInstance"):
    by_priority[b.attrib.get("priority","?")] += 1
    by_category[b.attrib.get("category","?")] += 1
    by_pattern [b.attrib.get("type","?")]     += 1
print(json.dumps({
  "total_bugs": sum(by_priority.values()),
  "by_priority": dict(by_priority),
  "by_category": dict(by_category),
  "top_patterns": by_pattern.most_common(20),
}, indent=2))
PY
cat "$OUT/spotbugs-summary.json"
