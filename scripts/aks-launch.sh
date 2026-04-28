#!/usr/bin/env bash
# AKS read-only deploy launcher for `codeiq serve`.
#
# Encodes the JVM flag preset that lets `serve` boot under
# securityContext.readOnlyRootFilesystem=true with /tmp mounted writable.
# Spec: docs/specs/2026-04-28-aks-read-only-deploy-design.md.
# Runbook: shared/runbooks/aks-read-only-deploy.md.
#
# Usage: aks-launch.sh /tmp/codeiq-data
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $(basename "$0") <data-dir>" >&2
  exit 64
fi
DATA_DIR="$1"

if [[ ! -d "$DATA_DIR" ]]; then
  echo "fatal: data dir does not exist: $DATA_DIR" >&2
  exit 66
fi

# Resolve the codeiq JAR. Container image installs it at /app/code-iq.jar
# by default; override via $CODEIQ_JAR for local testing.
JAR="${CODEIQ_JAR:-/app/code-iq.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "fatal: codeiq JAR not found at $JAR (override with \$CODEIQ_JAR)" >&2
  exit 66
fi

# Pre-flight: ensure /tmp has enough headroom. 1 GB is the absolute floor —
# Neo4j tx logs + Spring Boot loader extraction + JVM heap dump on OOM
# headroom. Real deploys want 2–4 GB depending on graph size.
TMP_FREE_KB="$(df -Pk /tmp | awk 'NR==2 {print $4}')"
if [[ "${TMP_FREE_KB:-0}" -lt 1048576 ]]; then
  echo "fatal: /tmp has < 1 GB free (${TMP_FREE_KB:-?} KB)" >&2
  exit 70
fi

mkdir -p /tmp/spring-boot-loader

# JVM flag preset. Every entry has a non-default behavior that without it
# would write outside /tmp. Order: -D system properties first, then -XX.
# Don't reorder — keep it greppable for the sentinel test.
JAVA_OPTS=(
  -Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader
  -Djava.io.tmpdir=/tmp
  -XX:ErrorFile=/tmp/hs_err_pid%p.log
  -XX:HeapDumpPath=/tmp
  -XX:+HeapDumpOnOutOfMemoryError
)

# Exec to PID 1 so signals (SIGTERM on pod stop) reach the JVM directly.
exec java "${JAVA_OPTS[@]}" -jar "$JAR" serve "$DATA_DIR"
