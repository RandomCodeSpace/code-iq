# Runbook: AKS OOM quick fix for `codeiq serve`

> **Audience:** ops engineers seeing `codeiq serve` pods crash, restart-loop, or feel sluggish on AKS (or any Kubernetes cluster) at the typical ~200 K-node graph scale.
>
> **Symptom:** pod is `OOMKilled`, or `kubectl top pod` shows steady-state RSS climbing toward the cgroup limit, or readiness probe flaps under load.
>
> **Companion:** [`aks-read-only-deploy.md`](aks-read-only-deploy.md) covers the read-only-rootfs deploy shape; this runbook covers the memory tuning that should pair with it.

## TL;DR

```yaml
resources:
  requests: { memory: "3Gi", cpu: "500m" }
  limits:   { memory: "4Gi", cpu: "2"     }
env:
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -XX:MaxRAMPercentage=50
      -XX:InitialRAMPercentage=25
      -XX:+UseG1GC
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/codeiq-oom.hprof
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 60
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 90
  periodSeconds: 30
  failureThreshold: 6
```

If you only do one thing, **set `MaxRAMPercentage=50` and `limits.memory: 4Gi`** — that alone resolves most OOMKilled crashes on the current architecture.

## 1. Why a graph this small OOMs

On a typical workload (~200 K nodes, ~320 K edges) the raw graph is ~150–200 MiB. The pod still OOMs because three independent memory-consumers fight for the same cgroup limit:

| Consumer | Default behaviour (untuned) | After the v0.2.1 quick-win PR |
|---|---|---|
| JVM heap | `-XX:MaxRAMPercentage=75` (JDK 25 default in containers) → ~3 GiB on a 4 GiB pod | Capped at 50% via `aks-launch.sh` |
| Neo4j page cache | Auto-grabs ~50% of *free* RAM at startup (off-heap, additive) | Capped at 256 MiB in `Neo4jConfig.java` |
| Spring `@Cacheable` regions | `ConcurrentMapCacheManager` — unbounded, no TTL, no eviction | Caffeine `maximumSize=1000, expireAfterWrite=5m` |
| Topology snapshot | Two independent `AtomicReference<List<CodeNode>>` (one in `McpTools`, one in `TopologyController`) | One shared `TopologySnapshotProvider`, 60 s TTL |

The first two cumulatively exceed `limits.memory` because nothing tells either side it has to share. The next two leak slowly under normal traffic until the heap fills.

## 2. Diagnostic — what's actually broken

Run these inside the cluster before applying the patch. They tell you whether the failure mode is **kernel OOM** (cgroup limit) or **JVM heap thrash** (probes timing out under GC pauses) — different fixes apply.

```bash
NS=<your-namespace>
POD=$(kubectl -n $NS get pod -l app=codeiq -o jsonpath='{.items[0].metadata.name}')

# 1. Are pods being kernel-OOM-killed?
kubectl -n $NS get events --sort-by='.lastTimestamp' | grep -iE "oom|kill|evict"
kubectl -n $NS describe pod $POD | grep -A2 "Last State"

# 2. Pod resource limits + actual usage
kubectl -n $NS get pod $POD -o jsonpath='{.spec.containers[0].resources}'; echo
kubectl -n $NS top pod $POD

# 3. JVM-effective heap settings + current heap
kubectl -n $NS exec $POD -- jcmd 1 VM.flags  | tr ' ' '\n' | grep -E "MaxHeapSize|MaxRAMPercentage"
kubectl -n $NS exec $POD -- jcmd 1 GC.heap_info
```

### Decision tree

- **`Last State: Terminated  Reason: OOMKilled`** → pod hit the cgroup limit. Apply the full TL;DR patch above.
- **No `OOMKilled`, but readiness flaps (`Reason: Unhealthy` events for `/actuator/health/readiness`)** → JVM is in GC thrash. The Caffeine + topology-snapshot fixes in v0.2.1 + bumping `failureThreshold: 6` resolve this without changing pod size.
- **Steady-state RSS keeps climbing for hours** → unbounded Spring cache. Confirm the pod image includes the Caffeine fix (v0.2.1+) by checking `kubectl exec $POD -- jcmd 1 VM.classloader_stats | grep -i caffeine`.

## 3. Apply the Deployment patch

```yaml
# Deployment.spec.template.spec.containers[0]
resources:
  # request = guaranteed-not-evicted floor; limit = hard cgroup ceiling.
  # 200 K-node graphs comfortably fit in 4 GiB total once the v0.2.1
  # quick-win lands. Bump the limit (not the request) if your store grows
  # past ~500 MB on disk.
  requests:
    memory: "3Gi"
    cpu: "500m"
  limits:
    memory: "4Gi"
    cpu: "2"
env:
  - name: JAVA_TOOL_OPTIONS
    # JAVA_TOOL_OPTIONS is picked up by every JVM invocation and prepended
    # to argv. Useful here because aks-launch.sh already sets the same
    # flags at exec time — the env var is a belt-and-braces fallback if
    # ops bypass the launch wrapper (e.g. kubectl exec'ing into the pod).
    value: >-
      -XX:MaxRAMPercentage=50
      -XX:InitialRAMPercentage=25
      -XX:+UseG1GC
      -XX:+ExitOnOutOfMemoryError
      -XX:+HeapDumpOnOutOfMemoryError
      -XX:HeapDumpPath=/tmp/codeiq-oom.hprof
readinessProbe:
  # Spring + Neo4j cold start is 10–16s. initialDelaySeconds of 60 gives
  # Spring's lazy beans + the first Neo4j page-cache page-in headroom
  # before the first probe failure can mark the pod NotReady.
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 60
  periodSeconds: 30
  timeoutSeconds: 10
  failureThreshold: 3
livenessProbe:
  # failureThreshold: 6 over periodSeconds: 30 = 3 minutes of tolerated
  # unresponsiveness before SIGKILL. Critical because GraphHealthIndicator
  # runs against Neo4j and a flushing page cache can stall it briefly
  # under burst traffic. Liveness must never flap on transient slowness;
  # only on actual JVM-dead.
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 90
  periodSeconds: 30
  failureThreshold: 6
```

After `kubectl apply`, watch the rollout:

```bash
kubectl -n $NS rollout status deployment/codeiq --timeout=5m
kubectl -n $NS top pod -l app=codeiq         # RSS should land near the requested 3Gi, not the 4Gi limit
kubectl -n $NS logs -l app=codeiq --tail=200 | grep -iE "oom|outofmemory|gc"
```

## 4. What this does NOT fix

- **5 M+ node graphs.** At that scale the topology snapshot is multi-GB regardless of TTL. The bounded-Cypher refactor is needed (tracked as the topology-deep-refactor follow-up).
- **runCypher misuse.** Operators or LLM agents can still run unbounded ad-hoc Cypher and OOM the pod. Limit the `runCypher` MCP tool's `maxResults` via `codeiq.yml` if you expose it externally.
- **Heap dump capture under cgroup pressure.** `HeapDumpPath=/tmp` is fine on tmpfs-backed `/tmp` (the read-only deploy uses `emptyDir: { medium: Memory }`), but if `/tmp` is also at the limit when the OOM fires, the dump won't write. For long-term diagnosis attach an `emptyDir` volume sized at `1.5 × heap` and point `HeapDumpPath` at it.

## 5. Horizontal scaling

The image-bundled read-only graph means each pod is fully stateless — `replicas: N` is safe. The only per-pod state worth knowing about is the in-process rate-limit `ConcurrentHashMap` in `RateLimitFilter`; token buckets reset per-replica, which is correct for per-key throttling but means a global rate limit across replicas isn't enforced. Most workloads don't need that.

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
```

A 3-replica deploy at `4Gi × 3 = 12Gi` total cluster cost gives ~3× the request capacity of a single 8Gi pod with zero crash risk.

## 6. Cross-references

- Code changes that landed alongside this runbook: `config/Neo4jConfig.java` (page-cache cap), `query/TopologySnapshotProvider.java` (shared snapshot), `application.yml` (Caffeine cache type), `scripts/aks-launch.sh` (JVM flag preset).
- Related runbook: [`aks-read-only-deploy.md`](aks-read-only-deploy.md) — the deploy shape this OOM patch sits inside.
- Architecture rationale: [`shared/runbooks/engineering-standards.md`](engineering-standards.md) §4 (resource sizing) and the OOM review thread in the project history.
