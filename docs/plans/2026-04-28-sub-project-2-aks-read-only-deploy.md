# Sub-project 2 implementation plan — AKS read-only deploy hardening

> **Spec:** [`docs/specs/2026-04-28-aks-read-only-deploy-design.md`](../specs/2026-04-28-aks-read-only-deploy-design.md)
>
> **Goal:** ship a runbook + JVM-flag-preset launch script + a sentinel test, so `codeiq serve` runs cleanly inside an AKS pod with read-only root filesystem and writable `/tmp`. No source-code changes to the serve profile or Neo4j wiring.
>
> **Scope:** small. Five files changed, single PR off `main`. Independent of sub-project 1.

## File map

| Action | Path | Purpose |
|---|---|---|
| **CREATE** | `docs/specs/2026-04-28-aks-read-only-deploy-design.md` | Architecture spec (✅ done with this plan). |
| **CREATE** | `docs/plans/2026-04-28-sub-project-2-aks-read-only-deploy.md` | This file. |
| **CREATE** | `shared/runbooks/aks-read-only-deploy.md` | Canonical deploy runbook. |
| **CREATE** | `scripts/aks-launch.sh` | JVM-flag-preset launch wrapper. |
| **CREATE** | `src/test/java/io/github/randomcodespace/iq/deploy/AksLaunchScriptSentinelTest.java` | Asserts the launch script contains the required flags. Catches drift. |
| **MODIFY** | `CHANGELOG.md` | New `[Unreleased] / Added` bullet. |
| **MODIFY** | `shared/runbooks/engineering-standards.md` | §7.1 cross-link to the new runbook. |

## Tasks

### Task 1 — Runbook

**File:** `shared/runbooks/aks-read-only-deploy.md`.

**Sections:** Overview · Deploy shape · Init-container pattern (Kubernetes manifest snippet) · JVM flag preset · Local docker smoke · Rollback · Cross-references.

**Hard requirement:** every command in the runbook must be runnable as-is. No placeholder URLs. Where a Nexus URL is needed, parameterize via `$NEXUS_URL` env, document it once.

### Task 2 — Launch script

**File:** `scripts/aks-launch.sh`.

**Skeleton:**

```bash
#!/usr/bin/env bash
# AKS read-only deploy launcher for codeiq serve.
# Usage: aks-launch.sh /tmp/codeiq-data
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $(basename "$0") <data-dir>" >&2
  exit 64
fi
DATA_DIR="$1"

# Resolve the codeiq JAR location. Container image installs it at /app.
JAR="${CODEIQ_JAR:-/app/code-iq.jar}"

# Pre-flight: ensure /tmp has enough headroom (1 GB minimum).
TMP_FREE_KB="$(df -Pk /tmp | awk 'NR==2 {print $4}')"
if [[ "$TMP_FREE_KB" -lt 1048576 ]]; then
  echo "fatal: /tmp has < 1 GB free ($TMP_FREE_KB KB)" >&2
  exit 70
fi

# JVM flag preset: every entry has a non-default behavior that without it
# would write outside /tmp. Order is intentional — system properties first,
# then -XX flags, so any -XX value referencing a system property resolves.
JAVA_OPTS=(
  -Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader
  -Djava.io.tmpdir=/tmp
  -XX:ErrorFile=/tmp/hs_err_pid%p.log
  -XX:HeapDumpPath=/tmp
  -XX:+HeapDumpOnOutOfMemoryError
)

mkdir -p /tmp/spring-boot-loader

exec java "${JAVA_OPTS[@]}" -jar "$JAR" serve "$DATA_DIR"
```

**Permissions:** `chmod +x scripts/aks-launch.sh` after create. Must be executable (the sentinel test asserts this).

### Task 3 — Sentinel test

**File:** `src/test/java/io/github/randomcodespace/iq/deploy/AksLaunchScriptSentinelTest.java`.

**Assertions** (one per required flag, plus structural checks):

```java
@Test void scriptIsExecutable() { ... }
@Test void scriptUsesStrictBashMode() { ... }     // set -euo pipefail
@Test void scriptValidatesArgCount() { ... }
@Test void scriptSetsSpringBootLoaderTmpDir() { ... }
@Test void scriptSetsJavaIoTmpdir() { ... }
@Test void scriptSetsJvmErrorFile() { ... }
@Test void scriptSetsHeapDumpPath() { ... }
@Test void scriptEnablesHeapDumpOnOom() { ... }
@Test void scriptExecsJava() { ... }              // exec java to PID 1
```

The test reads the script as a `String` and grep-matches each required substring. Cheap, deterministic, drift-proof.

### Task 4 — CHANGELOG entry

**File:** `CHANGELOG.md`.

**Add to `[Unreleased] / ### Added`:**

```markdown
- AKS read-only deploy hardening (sub-project 2): runbook at
  `shared/runbooks/aks-read-only-deploy.md`, JVM-flag-preset launcher at
  `scripts/aks-launch.sh`, and a sentinel test asserting the script
  contains every required flag. Enables `codeiq serve` inside an AKS pod
  with read-only root filesystem + writable `/tmp` (init-container
  copies bundle from Nexus → `/tmp/codeiq-data`; main container runs
  `aks-launch.sh /tmp/codeiq-data`). Zero source-code changes to the
  serve profile or Neo4j wiring — solved at the deployment layer plus
  Spring-Boot-loader / JVM crash-file path overrides. Spec at
  `docs/specs/2026-04-28-aks-read-only-deploy-design.md`.
```

### Task 5 — engineering-standards cross-link

**File:** `shared/runbooks/engineering-standards.md` §7.1.

Add a one-line bullet right under the existing "deploy surface" sentence:

```markdown
- AKS read-only deploy is supported via `shared/runbooks/aks-read-only-deploy.md`
  and `scripts/aks-launch.sh` (sub-project 2). The Maven Central artifact + the
  launch script + an init-container that copies the graph bundle from Nexus
  into `/tmp/codeiq-data` is the full surface — no separate hosted backend.
```

### Task 6 — Test loop + commit

```bash
mvn test -Dtest=AksLaunchScriptSentinelTest
mvn test  # full suite — confirm nothing else regressed
git add docs/specs/ docs/plans/ shared/runbooks/ scripts/aks-launch.sh \
        src/test/java/io/github/randomcodespace/iq/deploy/ CHANGELOG.md
git commit -m "feat(deploy): AKS read-only deploy hardening (sub-project 2)"
git push -u origin feat/sub-project-2-aks-read-only-deploy
gh pr create --base main \
  --title "feat: AKS read-only deploy hardening (sub-project 2)" \
  --body "..."
```

## Acceptance gates

- [ ] All seven files in the file map exist and are non-empty.
- [ ] Sentinel test green.
- [ ] Full `mvn test` green.
- [ ] Runbook commands are copy-pasteable; no placeholder URLs that the operator can't substitute.
- [ ] PR open against `main`.

## Out of scope (deliberate)

- A heavyweight JVM-level filesystem-write detector (Java has no clean `chroot` / `unshare` API; environment-fragile in CI). The runbook docker smoke is the SSoT for "did this actually work in a RO root."
- A `/api/diagnostics` endpoint surfacing JVM flag preset values. Tracked separately if ops need it.
- Switching the storage layer to a static snapshot (Approach D in the spec). Reserved as the fallback if init-container copy proves operationally insufficient.
- Helm chart / OCI artifact packaging. The runbook ships a vanilla Kubernetes manifest snippet; productionizing into Helm is the deployer's call.
