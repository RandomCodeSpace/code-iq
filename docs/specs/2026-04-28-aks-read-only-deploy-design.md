# Sub-project 2 — AKS read-only deploy hardening

> **Status:** Design ready for implementation. Owner: AI agent + Amit Kumar. Created 2026-04-28.

## 1. Problem

`codeiq serve` is meant to run inside an AKS pod with a **read-only root filesystem** (a hardening default for production Kubernetes). Only `/tmp` is writable. The graph bundle is built in CI via `index → enrich → bundle`, uploaded to a private Nexus registry, then pulled and mounted into the pod read-only at deploy time.

Today's serve mode opens an embedded Neo4j data directory and a fat JAR. Both want to write under the directory they were pointed at:

- **Neo4j Embedded** acquires a `store_lock` file in the DB directory at open, and writes transaction logs / counts / schema cache files even in nominally read-only modes.
- **Spring Boot fat JAR loader** extracts nested JARs to `~/.m2/spring-boot-loader-tmp/` (or wherever `org.springframework.boot.loader.tmpDir` points) at startup.
- **JVM** writes `hs_err_pid*.log` and heap dumps to the working directory by default on crash.

Without a deploy-shape change, `serve` fails to boot under `--read-only` because every one of the above tries to write outside `/tmp`.

## 2. Goal

`codeiq serve` runs cleanly inside an AKS pod that has:

- root filesystem mounted **read-only**,
- `/tmp` mounted as a writable `emptyDir` or `tmpfs`,
- the graph bundle pulled from Nexus and made available at a known mount path,

with **zero source-code changes to the serve profile or the Neo4j wiring**. Everything is solved at the deployment layer plus a JVM-flag preset.

## 3. Non-goals

- **Not** rewriting the storage layer to a static read-only snapshot (e.g. JSON / Parquet at serve time replacing Neo4j). That's a separate, much larger sub-project. We address it only if the init-container copy approach proves operationally insufficient.
- **Not** adding mutation endpoints or any write surface to serve mode. The serving layer remains strictly read-only per `CLAUDE.md` §"Read-Only Serving Layer".
- **Not** changing the build-CI side of the bundle pipeline (`index`, `enrich`, `bundle`) — that runs on a writable build agent.
- **Not** introducing a hosted backend or static-CDN frontend. The Maven Central + GitHub Releases distribution model from `engineering-standards.md` §7.1 is unchanged. AKS deploy is one of several runtime targets a downstream consumer might pick; the artifacts are the same JAR.

## 4. Approach: init-container copy + JVM flag preset

```
                         ┌──────────────────────────────┐
                         │  Build CI                    │
                         │  index → enrich → bundle.zip │
                         │  upload to Nexus             │
                         └───────────────┬──────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  AKS pod (root FS = read-only, /tmp writable)                   │
│                                                                 │
│  ┌─────────────── init-container ───────────────┐               │
│  │ curl --fail "$NEXUS_URL/$BUNDLE" -o /tmp/bundle.zip  │       │
│  │ unzip /tmp/bundle.zip -d /tmp/codeiq-data/           │       │
│  └────────────────────┬─────────────────────────┘               │
│                       │ (volume share: /tmp via emptyDir)       │
│                       ▼                                         │
│  ┌─────────────── main container ───────────────┐               │
│  │ scripts/aks-launch.sh /tmp/codeiq-data       │               │
│  │   → java [JVM flag preset] -jar code-iq.jar  │               │
│  │     serve /tmp/codeiq-data                   │               │
│  └──────────────────────────────────────────────┘               │
└─────────────────────────────────────────────────────────────────┘
```

The init-container is doing one thing: making the immutable bundle physically present under `/tmp/codeiq-data` so Neo4j can open it in normal (read+write-to-its-own-dir) mode. The main container then runs `serve` with the JVM flags below.

### Why this over the alternatives

| Approach | Verdict | Reasoning |
|---|---|---|
| **A. Init-container copy + JVM flags** *(chosen)* | ✅ | Minimal blast radius — zero source-code changes to serve / Neo4j wiring. Neo4j gets a writable directory under `/tmp`, the rest is JVM flags. Easy to test (`docker run --read-only --tmpfs /tmp ...`). |
| B. Neo4j RO mode + writable temp dir redirects | ❌ | Embedded Neo4j 2026.04.0 still acquires `store_lock` at open. `dbms.directories.transaction.logs.root` redirect needs careful per-version validation. Neo4j's RO mode is more brittle than copying the dir. |
| C. Bake bundle into container image | ❌ | Couples release cadence to image build; large image; container's writable upper layer is ALSO read-only when mounted `--read-only`, so Neo4j still fails. |
| D. Replace Neo4j with static snapshot | ❌ | Throws away the entire read API surface (Cypher, indexes, full-text search). Massive scope. Reserved as the "if A doesn't hold" fallback. |

## 5. JVM flag preset

These flags compose at launch via `scripts/aks-launch.sh`. Every entry has a non-default behavior that without it would write outside `/tmp`.

```bash
JAVA_OPTS=(
  # Spring Boot fat JAR extracts nested JARs at startup. Default is
  # ~/.m2/spring-boot-loader-tmp/ which sits under HOME, outside /tmp.
  "-Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader"

  # Java standard temp dir. Spring Boot's multipart upload temp area,
  # any Files.createTempFile call, JNA / Netty native lib extraction.
  "-Djava.io.tmpdir=/tmp"

  # JVM crash dump file (default: cwd/hs_err_pid<pid>.log).
  "-XX:ErrorFile=/tmp/hs_err_pid%p.log"

  # JVM heap dump on OOM (default: cwd).
  "-XX:HeapDumpPath=/tmp"
  "-XX:+HeapDumpOnOutOfMemoryError"

  # Diagnostic VM logs that some JDKs default into cwd.
  "-XX:NativeMemoryTracking=summary"
)
```

The preset is **wrapper-script-encoded, not pom.xml**: pom.xml controls the build, not the runtime JVM. The script is the contract surface for AKS deploy.

## 6. Audit findings

| Surface | Default location | Conflict with RO root | Fix |
|---|---|---|---|
| Neo4j `store_lock` + tx logs + counts cache | `<dataDir>/.codeiq/graph/graph.db/` | 🚩 yes | Init-container copies bundle to `/tmp/codeiq-data`. No code change. |
| Spring Boot fat JAR extraction | `~/.m2/spring-boot-loader-tmp/` | 🚩 yes | `-Dorg.springframework.boot.loader.tmpDir=/tmp/spring-boot-loader` |
| Java standard temp | `java.io.tmpdir` (default `/tmp` on Linux but worth being explicit) | 🟡 environment-dependent | `-Djava.io.tmpdir=/tmp` |
| JVM crash files (`hs_err_pid*.log`) | cwd | 🚩 yes | `-XX:ErrorFile=/tmp/hs_err_pid%p.log` |
| JVM heap dumps on OOM | cwd | 🚩 yes | `-XX:HeapDumpPath=/tmp` |
| Logback file appenders | none — `logback-spring.xml` is console-only | ✅ no | No change. Verified at `src/main/resources/logback-spring.xml`. |
| H2 analysis cache | `<repo>/.codeiq/cache/` | ✅ no — index-time only | No change. |
| React SPA static assets | classpath: `static/` | ✅ no | No change. |
| Picocli + Spring AI MCP | in-memory + classpath | ✅ no | No change. |
| Symbol resolver SPI (sub-project 1) | in-memory; index-time only | ✅ no | No change. |

## 7. Test approach

**Layer 1 — JVM-flag preset sentinel** (unit, fast, CI-gated)

A unit test reads `scripts/aks-launch.sh` and asserts each required `-D` / `-XX:` flag is present and points at a `/tmp` path. Catches drift if someone trims the script. Cheap to keep green.

**Layer 2 — Local docker smoke** (manual, runbook-described, not CI-gated)

The runbook documents:

```bash
docker run --rm --read-only --tmpfs /tmp:rw,size=2g \
  -v /path/to/bundle:/mnt/bundle:ro \
  codeiq:latest \
  /usr/local/bin/aks-launch.sh /tmp/codeiq-data
```

The smoke is the *only* honest test of the RO-root assumption — JVM-level filesystem-write detection inside JUnit is environment-fragile (CI runners have different access patterns, no clean `chroot` API in Java). The runbook smoke is the SSoT for "did this actually work?".

**Layer 3 — Integration smoke** (existing `IntegrationSmokeTest`)

The existing `IntegrationSmokeTest` boots `serve` with a real Neo4j data dir from `INTEGRATION_TEST_DIR`. Once the runbook lands, follow-up: extend that test to assert no files appear in `Path.of(".").toAbsolutePath()` after startup. Tracked as a follow-up; not blocking for this sub-project.

## 8. Backward compatibility

- Existing `codeiq serve <path>` continues to work on a writable filesystem. The launch script is **optional** — a developer-machine launch keeps using `java -jar code-iq-*-cli.jar serve <path>` with no flags.
- No new dependencies. No code changes outside the test surface and the script.
- Not breaking the Maven Central + GitHub Releases distribution channel; consumers who pull the JAR and run it from a local CLI are unaffected.

## 9. Risks

| Risk | Mitigation |
|---|---|
| `/tmp` size cap on AKS too small for the graph bundle | Document `emptyDir.sizeLimit: 4Gi` (or larger per repo size) in the runbook init-container manifest. Pre-flight check in the script — fail fast if `/tmp` has < N MB free. |
| Bundle download from Nexus fails — pod stuck in init | Init-container uses `curl --fail` so a 4xx/5xx aborts. Add a max-retry with backoff in the runbook init-container example. |
| Init-container copy slow on first deploy (large DB) | Document the trade-off; for very large repos, consider Approach D (static snapshot) as a follow-up — out of scope here. |
| Future Spring Boot release changes the loader temp-dir flag name | Sentinel test catches the flag presence; runbook lists the flag as Spring Boot 4.x — re-validate on Spring Boot 5.x upgrade. |
| Neo4j version change introduces a new write target outside the data dir | Caught by the runbook docker smoke before merge of any Neo4j upgrade PR. Make the smoke part of the upgrade checklist in `release.md`. |

## 10. Determinism + observability

- Determinism is unaffected — this is a deploy-layer change. The graph itself is byte-identical for the same input regardless of where it's served from.
- Add a `/api/diagnostics` (out of scope; tracked) that surfaces the JVM flag preset values for ops verification. Until then, ops can read the launch script directly inside the running container.

## 11. Acceptance criteria

1. **Spec** lands at `docs/specs/2026-04-28-aks-read-only-deploy-design.md`.
2. **Plan** lands at `docs/plans/2026-04-28-sub-project-2-aks-read-only-deploy.md`.
3. **Runbook** at `shared/runbooks/aks-read-only-deploy.md` covers: deploy shape, init-container manifest snippet, JVM flag preset, docker smoke, rollback.
4. **Launch script** at `scripts/aks-launch.sh` composes the JVM flag preset and execs `java -jar`. Has `set -euo pipefail` and validates its single argument.
5. **Sentinel test** at `src/test/java/.../deploy/AksLaunchScriptSentinelTest.java` asserts the script contains every required flag.
6. **CHANGELOG.md** `[Unreleased] / Added` entry.
7. **engineering-standards.md §7.1** cross-link to the new runbook.
8. **`mvn test`** green.
9. **PR** opened against `main`. Independent of sub-project 1 — separate base, separate review.

## 12. References

- `~/.claude/CLAUDE.md` — "Deployment assumption: solutions may run behind a corporate firewall / air-gapped"
- `~/.claude/rules/build.md` — "Self-contained build", "No runtime network calls to the public internet"
- `CLAUDE.md` (project) — "Read-Only Serving Layer", "Pipeline is index → enrich → serve"
- `shared/runbooks/engineering-standards.md` §7.1 — "Deploy targets"
- Spring Boot reference, "Loader" — `org.springframework.boot.loader.tmpDir` system property
- Neo4j 2026.04.0 — embedded API; `store_lock` behavior
