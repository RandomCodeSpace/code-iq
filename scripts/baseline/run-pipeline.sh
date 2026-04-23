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
rm -rf "$SEED/.codeiq"
# Truncate timings file so re-runs don't append stale entries.
: > "$OUT/timings.txt"

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
# Poll /api/stats up to 60s (30 x 2s) as the readiness probe. Spring Boot
# cold-start + embedded Neo4j page-cache warm-up is documented 8-16s (see
# CLAUDE.md §Gotchas). We deliberately do NOT poll /actuator/health: the
# GraphHealthIndicator currently reports OUT_OF_SERVICE (503) even after the
# graph has loaded (tracked as a known gap), so it is not a reliable readiness
# signal. /api/stats is the public REST surface and returns graph data iff
# the server has finished starting and loaded the graph.
ready_t0=$(date +%s)
ready_ok="no"
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$PORT/api/stats" > "$OUT/stats.json"; then
    ready_ok="yes"; break
  fi
  sleep 2
done
ready_elapsed=$(( $(date +%s) - ready_t0 ))
if [[ "$ready_ok" == "yes" ]]; then
  echo "stats=ok ready_after_s=${ready_elapsed}" | tee -a "$OUT/timings.txt"
else
  echo "stats=fail ready_after_s=${ready_elapsed}" | tee -a "$OUT/timings.txt"
  echo '{"error":"/api/stats never returned 2xx within 60s"}' > "$OUT/stats.json"
fi

# Capture /actuator/health as a diagnostic snapshot (may be 503 today;
# still useful for tracking the health-indicator fix over time).
health_http=$(curl -s -o "$OUT/health.json" -w '%{http_code}' \
  "http://127.0.0.1:$PORT/actuator/health" 2>/dev/null || echo "000")
echo "health_http=${health_http}" | tee -a "$OUT/timings.txt"
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true

# Summarize.
python3 - <<PY > "$OUT/summary.json"
import json, os
def load(p):
  try: return json.load(open(p))
  except Exception: return None
t=open("$OUT/timings.txt").read().strip().splitlines()
stats = load("$OUT/stats.json")
print(json.dumps({
  "seed": "$NAME",
  "timings": t,
  "stats": stats,
  "stats_ok": isinstance(stats, dict) and "graph" in stats,
  "health_raw": load("$OUT/health.json"),
}, indent=2))
PY
cat "$OUT/summary.json"
