# Runbook: AKS read-only deploy

> **Audience:** ops engineers deploying `codeiq serve` to an AKS cluster (or any Kubernetes cluster with `securityContext.readOnlyRootFilesystem: true`).
>
> **Spec:** [`docs/specs/2026-04-28-aks-read-only-deploy-design.md`](../../docs/specs/2026-04-28-aks-read-only-deploy-design.md). Full architecture rationale lives there; this runbook is the operational checklist.

## 1. Overview

`codeiq serve` runs inside an AKS pod with the root filesystem mounted read-only and `/tmp` mounted writable. The graph bundle is built in CI (`index → enrich → bundle`), uploaded to Nexus, then pulled at deploy time by an init-container into `/tmp/codeiq-data`. The main container runs the launch wrapper at `scripts/aks-launch.sh` which composes the JVM flag preset and execs `java -jar code-iq.jar serve /tmp/codeiq-data`.

Three deployment-layer pieces enable this with **zero source-code changes** to the serve profile:

1. The graph bundle physically lives under `/tmp/codeiq-data` so embedded Neo4j has a writable directory for its `store_lock`, transaction logs, and counts cache.
2. JVM flags redirect Spring-Boot-loader extraction, crash dumps, and heap dumps to `/tmp`.
3. The launch wrapper enforces the flag preset in one place.

## 2. Deploy shape

```
Build CI (any writable agent — GitHub Actions, GitLab, etc.)
  └─ codeiq index $REPO
  └─ codeiq enrich $REPO            ──▶ $REPO/.codeiq/graph/graph.db/
  └─ codeiq bundle $REPO            ──▶ bundle.zip (graph + manifest)
  └─ curl -u $NEXUS_USER:$NEXUS_PASS \
        --upload-file bundle.zip \
        "$NEXUS_URL/repository/codeiq-bundles/$BUNDLE_VERSION/bundle.zip"

AKS deploy (one Pod per service)
  init-container "fetch-bundle"     download from Nexus → /tmp/codeiq-data/
  main container  "codeiq-serve"    /usr/local/bin/aks-launch.sh /tmp/codeiq-data
                                    listens on :8080 (configurable)
```

## 3. Init-container Kubernetes manifest

Drop the snippet below into your Pod spec. The init-container shares an `emptyDir` mount with the main container so the unzipped bundle is visible at the same path in both.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: codeiq
  namespace: codeiq
spec:
  replicas: 1
  selector: { matchLabels: { app: codeiq } }
  template:
    metadata: { labels: { app: codeiq } }
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        fsGroup: 65532
      volumes:
        - name: tmp
          emptyDir:
            medium: Memory       # tmpfs — fastest; switch to "" for disk-backed
            sizeLimit: 4Gi       # tune to bundle size + Neo4j tx-log headroom
      initContainers:
        - name: fetch-bundle
          image: alpine:3.20
          command: [sh, -c]
          args:
            - |
              set -euo pipefail
              apk add --no-cache curl unzip > /dev/null
              curl --fail --silent --show-error \
                -u "$NEXUS_USER:$NEXUS_PASS" \
                "$NEXUS_URL/repository/codeiq-bundles/$BUNDLE_VERSION/bundle.zip" \
                -o /tmp/bundle.zip
              mkdir -p /tmp/codeiq-data
              unzip -q /tmp/bundle.zip -d /tmp/codeiq-data
              rm -f /tmp/bundle.zip
          env:
            - name: NEXUS_URL
              valueFrom: { secretKeyRef: { name: codeiq-nexus, key: url } }
            - name: NEXUS_USER
              valueFrom: { secretKeyRef: { name: codeiq-nexus, key: user } }
            - name: NEXUS_PASS
              valueFrom: { secretKeyRef: { name: codeiq-nexus, key: pass } }
            - name: BUNDLE_VERSION
              value: "0.1.0"     # bumped per release
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          securityContext:
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            capabilities: { drop: [ALL] }
      containers:
        - name: codeiq-serve
          image: ghcr.io/randomcodespace/codeiq:0.1.0
          command: [/usr/local/bin/aks-launch.sh, /tmp/codeiq-data]
          ports:
            - { name: http, containerPort: 8080 }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: http }
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: http }
            initialDelaySeconds: 60
            periodSeconds: 10
          resources:
            requests: { cpu: 500m, memory: 1Gi }
            limits:   { cpu: 2,    memory: 4Gi }
          volumeMounts:
            - name: tmp
              mountPath: /tmp
          securityContext:
            readOnlyRootFilesystem: true        # enforces the model
            allowPrivilegeEscalation: false
            runAsNonRoot: true
            capabilities: { drop: [ALL] }
            seccompProfile: { type: RuntimeDefault }
```

**Volume sizing:** the `emptyDir.sizeLimit: 4Gi` covers a typical mid-size repo's graph + Neo4j transaction-log headroom + Spring Boot loader extraction (~50 MB) + JVM heap-dump headroom. Bump for very large bundles. The pre-flight check in `aks-launch.sh` aborts startup if `/tmp` has < 1 GB free, which is the absolute floor.

**Image:** the container image must install the launch script at `/usr/local/bin/aks-launch.sh` and the JAR at `/app/code-iq.jar` (or set `CODEIQ_JAR=...`). Reference Dockerfile:

```dockerfile
FROM eclipse-temurin:25-jre-alpine
RUN apk add --no-cache bash
WORKDIR /app
COPY code-iq-*-cli.jar /app/code-iq.jar
COPY scripts/aks-launch.sh /usr/local/bin/aks-launch.sh
RUN chmod +x /usr/local/bin/aks-launch.sh
USER 65532:65532
ENTRYPOINT ["/usr/local/bin/aks-launch.sh"]
```

## 4. JVM flag preset (canonical reference)

Encoded in `scripts/aks-launch.sh`. Updating the preset means updating the script (and the sentinel test catches the drift). Every flag has a non-default behavior that without it would write outside `/tmp`.

| Flag | Default | Why required |
|---|---|---|
| `-Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader` | `~/.m2/spring-boot-loader-tmp` | Spring Boot fat JAR extracts nested JARs to `$HOME` by default — outside `/tmp`. |
| `-Djava.io.tmpdir=/tmp` | OS-default (`/tmp` on Linux) | Explicit so multipart uploads, JNA / Netty native lib extraction land where we expect across base images. |
| `-XX:ErrorFile=/tmp/hs_err_pid%p.log` | cwd | JVM crash dump default is the working directory. |
| `-XX:HeapDumpPath=/tmp` | cwd | Heap dump on OOM default is cwd. |
| `-XX:+HeapDumpOnOutOfMemoryError` | off | Without this the path flag never fires. |

## 5. Verification

### 5.1 Local docker smoke (the gate)

This is the **single source of truth** for "did the deploy assumption actually hold." JVM-level write detection inside JUnit is environment-fragile; running the actual binary inside the actual constraint shape is the only honest test.

```bash
# Build the image once.
docker build -t codeiq:smoke .

# Run with --read-only and a tmpfs /tmp, mount a known-good bundle as RO.
docker run --rm \
  --read-only \
  --tmpfs /tmp:rw,size=2g,mode=1777 \
  -v "$PWD/test-bundle:/mnt/bundle:ro" \
  -p 8080:8080 \
  --entrypoint sh \
  codeiq:smoke \
  -c '
    cp -r /mnt/bundle/. /tmp/codeiq-data &&
    /usr/local/bin/aks-launch.sh /tmp/codeiq-data
  '

# In another terminal:
curl -fsS http://localhost:8080/api/stats > /tmp/stats.json
jq '.graph.nodes' /tmp/stats.json     # > 0 confirms the graph loaded
```

If the container exits non-zero with `Read-only file system` or `Permission denied`, **do not paper over with `--read-only=false`**. Investigate which path the new code is trying to write to, and either fix the code or extend the JVM flag preset.

### 5.2 Sentinel test (drift catcher)

```bash
mvn test -Dtest=AksLaunchScriptSentinelTest
```

Asserts every required flag is in `scripts/aks-launch.sh`. CI-gated. Catches accidental flag removal.

### 5.3 In-cluster smoke (post-deploy)

```bash
kubectl -n codeiq port-forward deploy/codeiq 8080:8080 &
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/api/stats | jq '.graph.nodes'
```

## 6. Rollback

The deploy artifact is the immutable bundle in Nexus + the immutable container image. Rollback is "redeploy the previous bundle version."

```bash
# Bundle rollback — re-tag the previous bundle version, redeploy.
kubectl -n codeiq set env deploy/codeiq \
    --containers='codeiq-serve' BUNDLE_VERSION=0.0.49

# Image rollback (CVE patch / launcher fix).
kubectl -n codeiq set image deploy/codeiq \
    codeiq-serve=ghcr.io/randomcodespace/codeiq:0.0.49
```

For full release / rollback policy see [`shared/runbooks/release.md`](release.md) and [`shared/runbooks/rollback.md`](rollback.md). This runbook covers the AKS-specific bits only.

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Read-only file system` at startup | A new code path is writing outside `/tmp`. | Run the docker smoke (§5.1) — the stack trace points at the path. Either redirect via the JVM flag preset (extend §4) or fix the code. |
| `lock acquired by another process` from Neo4j | Two pods sharing the same `/tmp` volume — only legal in single-replica mode. | Set `replicas: 1`, or split each replica's `emptyDir` (default — they're per-pod). |
| `out of disk space` during init-container | `emptyDir.sizeLimit` too small for the bundle. | Bump `sizeLimit` in the manifest. |
| `BUNDLE_VERSION` not found at Nexus | Stale tag, or release never landed. | Verify the upload step in build CI; check Nexus repository UI. |
| Pod restart loop after a clean start | Likely a heap dump filling `/tmp` — `--tmpfs` size cap reached. | Bump `sizeLimit`; investigate the OOM root cause via the heap dump pulled out of the previous pod. |

## 8. Cross-references

- Spec: [`docs/specs/2026-04-28-aks-read-only-deploy-design.md`](../../docs/specs/2026-04-28-aks-read-only-deploy-design.md)
- Plan: [`docs/plans/2026-04-28-sub-project-2-aks-read-only-deploy.md`](../../docs/plans/2026-04-28-sub-project-2-aks-read-only-deploy.md)
- Engineering standards: [`engineering-standards.md`](engineering-standards.md) §7.1 Deploy targets
- Release process: [`release.md`](release.md)
- Rollback: [`rollback.md`](rollback.md)
- Launch script: [`scripts/aks-launch.sh`](../../scripts/aks-launch.sh)
- Sentinel test: [`src/test/java/io/github/randomcodespace/iq/deploy/AksLaunchScriptSentinelTest.java`](../../src/test/java/io/github/randomcodespace/iq/deploy/AksLaunchScriptSentinelTest.java)
