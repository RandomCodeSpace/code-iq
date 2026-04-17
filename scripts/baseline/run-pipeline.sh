#!/usr/bin/env bash
# Usage: run-pipeline.sh <seed-name>
# Runs index, enrich, (brief) serve-smoke against a seed repo, captures timings + stats.
set -euo pipefail
NAME="${1:?seed name required (e.g. spring-petclinic)}"
SEED=".seeds/$NAME"
OUT="docs/superpowers/baselines/2026-04-17/raw/pipeline/$NAME"
mkdir -p "$OUT"

JAR="$(ls target/code-iq-*-cli.jar 2>/dev/null | head -n1 || true)"
if [[ -z "$JAR" ]]; then
  echo "[pipeline] CLI jar not found; running: mvn -B -DskipTests package"
  mvn -B -DskipTests package
  JAR="$(ls target/code-iq-*-cli.jar | head -n1)"
fi

[[ -d "$SEED" ]] || { echo "Seed $SEED missing. Run scripts/seed-repos.sh first."; exit 1; }

# Clean any prior state in the seed repo.
rm -rf "$SEED/.code-intelligence" "$SEED/.osscodeiq"

timer() {
  local label="$1"; shift
  local t0=$(date +%s)
  "$@" > "$OUT/$label.log" 2>&1
  local rc=$?
  local t1=$(date +%s)
  echo "$label duration=$((t1-t0))s rc=$rc" | tee -a "$OUT/timings.txt"
  return $rc
}

timer index  java -jar "$JAR" index  "$SEED"
timer enrich java -jar "$JAR" enrich "$SEED"

# Serve-smoke: start server, hit /actuator/health and /api/stats, stop.
PORT=18080
java -jar "$JAR" serve "$SEED" --port "$PORT" > "$OUT/serve.log" 2>&1 &
PID=$!
trap "kill $PID 2>/dev/null || true" EXIT
sleep 8
if curl -sf "http://127.0.0.1:$PORT/actuator/health" > "$OUT/health.json"; then
  echo "health=ok" >> "$OUT/timings.txt"
else
  echo "health=fail" >> "$OUT/timings.txt"
fi
curl -sf "http://127.0.0.1:$PORT/api/stats" > "$OUT/stats.json" || true
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

# Summarize.
python3 - <<PY > "$OUT/summary.json"
import json, os
def load(p):
  try: return json.load(open(p))
  except Exception: return None
t=open("$OUT/timings.txt").read().strip().splitlines()
print(json.dumps({
  "seed": "$NAME",
  "timings": t,
  "stats": load("$OUT/stats.json"),
  "health_ok": load("$OUT/health.json") is not None,
}, indent=2))
PY
cat "$OUT/summary.json"
