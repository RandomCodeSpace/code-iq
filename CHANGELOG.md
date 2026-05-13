# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-tag release notes — including the full beta sequence (`v0.0.1-beta.0` …
`v0.0.1-beta.46`) — are published on
[GitHub Releases](https://github.com/RandomCodeSpace/codeiq/releases). This file
captures the cross-cutting changes that span multiple commits or releases (new
quality gates, security policy, deploy surface, etc.) — see the GitHub Release
for that specific tag for the per-commit details.

## [Unreleased]

### Added

- **Phase 5 release infrastructure for the Go binary** —
  `.goreleaser.yml` + `.github/workflows/release-go.yml` cut a
  multi-platform (linux/amd64, linux/arm64, darwin/arm64) release on
  every `v*.*.*` tag push. Each archive ships with an SPDX SBOM
  (Syft), and the `checksums.sha256` manifest is keyless-signed via
  Cosign + GitHub OIDC (Sigstore Rekor transparency log). Optional
  Homebrew tap publish to `RandomCodeSpace/homebrew-codeiq` —
  skipped silently when the secret isn't configured, so forks can
  reuse the same workflow. Build provenance attestations via
  `actions/attest-build-provenance`. Runbook at
  [`shared/runbooks/release-go.md`](shared/runbooks/release-go.md).
- **`.github/workflows/perf-gate.yml`** — per-PR perf-regression gate.
  Runs `codeiq index` against `fixture-multi-lang` and fails the build
  if wall-clock exceeds 8 s, node count drops below 40, or the
  phantom-edge drop ratio crosses 50%. Catches regex-pathology
  regressions like the CertificateAuthDetector pre-screen miss that
  blew up PSA indexing from 0.1 s to 42 s mid-port.
- **Go port (Phases 1-4 of the rewrite)** — codeiq is being ported from
  Java/Spring Boot to a single static Go binary on the `port/go-port`
  branch. PR #130. 100 detectors at 1:1 parity with the Java side; 34 MCP
  tools (deprecated) + 6 consolidated mode-driven tools (new); `codeiq
  review` CLI + `review_changes` MCP tool for LLM-driven PR review via
  Ollama (Cloud or local). Java tree untouched until Phase 6 cutover.
- **Graph dedup + determinism** (Go side) — `GraphBuilder` deduplicates
  nodes by ID with confidence-aware merging, edges by canonical
  `(source, target, kind)` tuple. Linker output sorted at the boundary.
  `codeiq index` surfaces "Deduped: N nodes, M edges  Dropped: K phantom
  edges" so graph hygiene is visible.
- **`codeiq review`** — LLM-driven review of `git diff base..head` against
  the indexed graph. Defaults to local Ollama (`gpt-oss:20b`); set
  `OLLAMA_API_KEY` to flip to Ollama Cloud. `--format=markdown|json`,
  `--out`, `--focus`. Graph evidence (nodes-in-file + 1-hop blast radius)
  attached per changed file when the Kuzu store is enriched.
- **`review_changes` MCP tool** — same review flow exposed over MCP for
  agent-driven invocation. Strictly read-only against the graph.
- OpenSSF supply-chain wiring — Best Practices project
  [12650](https://www.bestpractices.dev/projects/12650), live Scorecard at
  [securityscorecards.dev](https://api.securityscorecards.dev/projects/github.com/RandomCodeSpace/codeiq),
  manifest at `.bestpractices.json`, README badges. (RAN-46, RAN-52, RAN-57)
- `.github/workflows/scorecard.yml` — OpenSSF Scorecard analysis on push +
  weekly cron (Mondays 06:00 UTC), SARIF → Security tab. All actions
  SHA-pinned per Scorecard `Pinned-Dependencies`.
- `.github/workflows/security.yml` — consolidated OSS-CLI security stack
  per RAN-46 path-B board ruling: OSV-Scanner (npm SCA), Trivy (filesystem +
  Maven + container CVEs + IaC misconfig), Semgrep (SAST: `p/security-audit`
  + `p/owasp-top-ten` + `p/java`), Gitleaks (secret scan, full git history),
  jscpd (duplication < 3% on production code), `anchore/sbom-action` (SPDX +
  CycloneDX SBOM). Six gate-blocking jobs (SBOM is artifact-only).
- `SECURITY.md` — private vulnerability-disclosure policy, supported-versions
  table, triage SLAs (acknowledgement < 72 h, initial triage < 7 d), and
  coordinated-disclosure timeline.
- `shared/runbooks/` — `engineering-standards.md` (quality gates, code style,
  branch/commit/PR rules, testing tiers, security stack, build & distribution,
  documentation), `release.md`, `rollback.md`, `first-time-setup.md`,
  `test-strategy.md`. SSoT for cross-cutting engineering rules.
- `scripts/setup-git-signed.sh` — one-shot ssh-signed-commit setup helper.
- `CLAUDE.md` "Supply-chain observability (OpenSSF)" section — operator-level
  summary of the Best Practices state, Scorecard baseline + target (≥ 8.0/10
  stretch with eight checks at max), known floor reductions, and the OSS-CLI
  stack reference. (RAN-52 AC #7)
- `PROJECT_SUMMARY.md` (repo-root agent entry doc) and
  [`docs/project/`](docs/project/) deep-dives (architecture, data-model,
  build-and-run, conventions, ui, flows) — written for AI agents and humans
  who need to understand and modify the codebase, every claim grounded in a
  file path. Sits alongside `CLAUDE.md` (which remains the canonical
  hand-maintained internals doc).
- `docs/specs/` — directory for active architectural design specs. First
  entry: `2026-04-27-resolver-spi-and-java-pilot-design.md`, the design for
  sub-project 1 of the "robust graph" decomposition (symbol-resolver SPI
  between parse and detect, Java pilot via JavaParser's `JavaSymbolSolver`,
  `Confidence` enum + `source` field on every `CodeNode` / `CodeEdge`,
  4–6 Java detectors migrated, 9 layers of aggressive testing). Implementation
  in flight on `feat/sub-project-1-resolver-spi-and-java-pilot`.
- **Symbol-resolver SPI** (sub-project 1, Phases 1–4 of the resolver-and-Java-pilot
  plan): the foundation for moving the graph from regex-class-of-correctness
  to AST-and-symbol-resolution-class-of-correctness. New `Confidence` enum
  (`LEXICAL`/`SYNTACTIC`/`RESOLVED` with stable `score()` mapping) plus a
  `source` field land on every `CodeNode` and `CodeEdge`, round-trip through
  Neo4j (bare `confidence`/`source` properties on nodes and `RELATES_TO`
  relationships) and through the H2 analysis cache (`CACHE_VERSION` bumped
  4 → 5 so existing v4 caches drop and rebuild on next open). Read paths are
  non-throwing — legacy data without these fields reads back as
  `LEXICAL`/null, never NPEs. New SPI under
  `intelligence/resolver/`: `Resolved` interface + `EmptyResolved` singleton
  sentinel, `SymbolResolver` per-language backend, `ResolutionException`,
  `ResolverRegistry` (Spring `@Service` with deterministic alphabetical
  bootstrap, case-insensitive lookup, per-resolver failure isolation). First
  backend `JavaSymbolResolver` wraps `javaparser-symbol-solver-core` 3.28.0
  (Apache-2.0, same release train as `javaparser-core`) with a
  `JavaSourceRootDiscovery` that walks Maven/Gradle/plain layouts under a
  project root (skipping `target/`, `build/`, `node_modules/`, `.git/`, etc.;
  symlink-loop-safe via `NOFOLLOW_LINKS`). `DetectorContext` now carries an
  `Optional<Resolved>` (`withResolved()` opt-in, `Optional.empty()` for every
  detector that doesn't care — fully backward compatible). `Detector.defaultConfidence()`
  declares the per-detector floor (`LEXICAL` for regex bases, `SYNTACTIC` for
  AST/structured/JavaParser/JavaMessaging bases) and `DetectorEmissionDefaults.applyDefaults`
  is wired into every `detector.detect()` call site in `Analyzer.java` —
  emissions whose `source` is null get stamped at the orchestration boundary
  (detectors that explicitly stamp survive untouched). 11 atomic commits
  ship with ~290 new tests covering happy paths, legacy-data fallbacks,
  malformed inputs, determinism, concurrency-safe construction, and singleton
  invariants.

- **Resolver pipeline wiring + Java pilot detectors** (sub-project 1, plan
  Phases 4 + 6 — follow-up to the SPI scaffolding above): the resolver
  is now actually invoked end-to-end and four Java detectors consume
  `ctx.resolved()` to emit RESOLVED-tier edges with stable
  fully-qualified-name targets.
  - `Analyzer` now bootstraps `ResolverRegistry` exactly once per pipeline
    entry point (`run` / `runBatchedIndex` / `runSmartIndex`) and threads a
    `Resolved` onto every `DetectorContext` at all three detect call sites
    (`analyzeFile`, the batched-index variant, the regex-only fallback).
    Per-file `ResolutionException` + `RuntimeException` are swallowed and
    fall back to `EmptyResolved.INSTANCE`, so one resolver blow-up cannot
    take down the whole pass.
  - `JavaSymbolResolver.resolve()` now lazy-parses raw source `String`
    content with a fresh symbol-solver-configured `JavaParser` per call —
    a small per-call allocation that lets `Analyzer` pass the file content
    directly (the orchestrator-level structured parser doesn't cover Java).
    Permissive parsing returns `JavaResolved` with a possibly-error-laden
    `CompilationUnit` rather than refusing — production analysis must keep
    going across files with syntax errors.
  - Four detectors migrated to consume `ctx.resolved()` (purely additive —
    every existing detector test passes unchanged):
    - **JpaEntityDetector** — `MAPS_TO` edges between entities now carry
      `target_fqn` and `Confidence.RESOLVED` when the symbol solver can
      pin the relationship target's FQN (handles `@OneToMany List<Owner>`,
      `@ManyToOne Owner`, both direct-field and generic-arg cases).
    - **RepositoryDetector** — Spring Data repo `QUERIES` edges plus the
      repo node carry the resolved entity FQN (`entity_fqn` /
      `target_fqn`) when `JpaRepository<User, Long>` resolves.
    - **SpringRestDetector** — endpoints emit a `MAPS_TO` edge to the
      `@RequestBody` DTO class when the parameter type resolves, with
      `parameter_kind=request_body` + `parameter_name` properties for
      downstream consumers (SPA, MCP).
    - **ClassHierarchyDetector** — `EXTENDS` / `IMPLEMENTS` edges across
      classes, interfaces, and enums now stamp `Confidence.RESOLVED` +
      `target_fqn` when the parent type resolves, collapsing four
      duplicated in-line edge-emission blocks into a single
      `addHierarchyEdge` helper as a side-benefit.
  - Backward compatibility is total: when no resolver is registered or
    `JavaSymbolResolver.bootstrap` fails, every detector returns the
    same simple-name-targeted edge shape it shipped before this slice.
  - 18 new wiring + resolved-mode tests on top of the SPI's ~290 — every
    migration ships with the plan-required three-mode coverage (resolved,
    fallback, mixed).
- **AKS read-only deploy hardening** (sub-project 2): runbook at
  [`shared/runbooks/aks-read-only-deploy.md`](shared/runbooks/aks-read-only-deploy.md),
  JVM-flag-preset launcher at [`scripts/aks-launch.sh`](scripts/aks-launch.sh),
  and a sentinel test asserting the script contains every required flag.
  Enables `codeiq serve` inside an AKS pod with
  `securityContext.readOnlyRootFilesystem=true` and a writable `/tmp`
  emptyDir: an init-container copies the graph bundle from Nexus into
  `/tmp/codeiq-data`; the main container runs `aks-launch.sh /tmp/codeiq-data`.
  Zero source-code changes to the serve profile or Neo4j wiring — solved at
  the deployment layer plus Spring-Boot-loader / `java.io.tmpdir` /
  `-XX:ErrorFile` / `-XX:HeapDumpPath` overrides. Spec at
  [`docs/specs/2026-04-28-aks-read-only-deploy-design.md`](docs/specs/2026-04-28-aks-read-only-deploy-design.md).

- **Resolver aggressive-testing layers** (sub-project 1, plan Phase 7 —
  Layers 1, 3, 4, 5, 6, 7, 8, 9): the spec §12 testing matrix lands as
  six new test classes plus a non-default Maven profile.
  - **Layer 1** — `JavaSymbolResolverLayer1ExtendedTest` (16 tests):
    deeply-nested generics, static / non-static inner classes, records,
    sealed hierarchies, enum-with-abstract-methods, default-method
    interfaces, abstract classes, annotation types, same simple name in
    different packages by import, JDK `Optional` / `Stream` / `List` via
    `ReflectionTypeSolver`, multi-source-root cross-references
    (`src/main` ↔ `src/test`), wildcard imports, cyclic imports.
  - **Layer 3** — `JavaSymbolResolverConcurrencyTest` (already shipped
    in the prior commit): virtual-thread fan-out under `N=200` files /
    `256` concurrent calls, garbage-input variant.
  - **Layer 4** — `JavaSymbolResolverPathologicalTest` (3 tests):
    10K-line class, 1000 imports (most unresolvable), 10-deep generic
    nesting; per-test `@Timeout` is the regression sentinel against
    quadratic memoization.
  - **Layer 5** — `JavaSymbolResolverAdversarialTest` (5 tests):
    unbalanced braces (strict-success → `EmptyResolved`), mis-tagged
    Kotlin / random-bytes (no exception, no null), mixed source root
    with `.java` + `.txt` siblings, empty source root (no Java files
    anywhere) bootstraps via `ReflectionTypeSolver` alone.
  - **Layer 6** — `JavaSymbolResolverDeterminismTest` (already shipped):
    same input → same FQN 25× in a row, two independent resolvers
    agree, rebootstrap is observably idempotent, deeper FQNs are stable.
  - **Layer 7** — `E2EResolverPetclinicTest` (env-gated): runs the
    resolver against every `.java` under `$E2E_PETCLINIC_DIR`, asserts
    bootstrap < 10 s, no exception, > 50% files produce `JavaResolved`
    (i.e. strict-success isn't false-rejecting valid Java). Lighter than
    spec §12 Layer 7's full precision/recall comparison — that requires
    a pre-resolver baseline JSON checked into test resources, captured
    at implementation time. This stand-in is the strongest signal until
    that baseline lands.
  - **Layer 8** — `JavaSymbolResolverRandomizedTest` (1 test, 100
    samples): hand-rolled randomized generator with fixed seed; per the
    plan's license guidance, jqwik (EPL-2.0) is not on the preferred-
    license list, and this is the documented JUnit + `java.util.Random`
    fallback. Properties: never throws, never returns null, completes
    per file in < 1 s.
  - **Layer 9** — `mutation` Maven profile (non-default): adds
    `pitest-maven` 1.18.0 (Apache-2.0) targeting
    `intelligence.resolver.*` and `model.Confidence`. Run with
    `mvn -P mutation org.pitest:pitest-maven:mutationCoverage
    -Dfrontend.skip=true -Ddependency-check.skip=true`. Reports under
    `target/pit-reports/`.
  - Four robustness fixes from a dual-agent (superpowers + codex)
    brainstorm landed on the same branch: `volatile` on
    `JavaSymbolResolver`'s `solver` / `combined` fields, strict
    parse-success check in the String-source branch (was silently
    emitting partial-CU edges on broken parses), `StackOverflowError`
    catch in `Analyzer.resolveFor` (pathological generics no longer kill
    virtual threads), `try-with-resources` on the `Files.walk` in
    `JavaSourceRootDiscovery.containsJavaFile` (fd leak fix). 26 new
    tests on top of the resolver wiring slice's 18 — full suite at 3618
    / 0 / 32 skipped, +1 skip is the env-gated E2E petclinic test.

### Changed

- Documentation count drift fixed: detector total updated from **97 → 99**
  (live count, excluding `Abstract*` and `*Helper*`); `NodeKind` total
  updated from **32 → 34** (javadoc at `model/NodeKind.java` was stale by
  two entries); `EdgeKind` total updated from **27 → 28** (javadoc at
  `model/EdgeKind.java` was stale by one entry). `README.md`, `CLAUDE.md`,
  `PROJECT_SUMMARY.md`, `docs/project/*.md`, and the source javadocs are
  now in sync.

- Branch protection on `main` requires every commit to be ssh-signed
  (RAN-46 AC #2). Force-pushes to `main` are rejected; squash-merge from
  PRs is the only path.
- Top-level `permissions: read-all` on every GitHub Actions workflow per
  Scorecard `Token-Permissions`. Per-job permissions opt into narrower
  writes only where required (`security-events: write` for SARIF upload;
  `id-token: write` for the Scorecard publish step).
- Quality gate stack converged to OSS-CLI only: SpotBugs (`mvn spotbugs:check`),
  JaCoCo coverage (≥ 85% line, project-wide), Semgrep + Trivy + OSV-Scanner +
  Gitleaks + jscpd from `security.yml`, plus OpenSSF Scorecard as
  observability. (RAN-46 path-B board ruling.)

### Removed

- SonarCloud, CodeQL (default-setup and workflow-driven), and OWASP
  Dependency-Check are no longer part of the merge gate. Per the RAN-46
  path-B board ruling, they are not to be re-introduced without an explicit
  board reversal — see `shared/runbooks/engineering-standards.md` §5.1.

### Security

- **Production-readiness PR 1 of 5 — security baseline.** First half of the
  audit findings catalogued under `docs/audits/2026-04-28-serve-path-prod-readiness.md`
  (+ `-counter.md`). Closes audit findings #1, #7, #13 (HIGH/MEDIUM) and C2 (MEDIUM).
  - **Bearer-token auth on `/api/**` and `/mcp/**`** (audit #1). Added
    `spring-boot-starter-security`. New `config/security/SecurityConfig`,
    `BearerAuthFilter`, `TokenResolver`. Token source priority:
    `CODEIQ_MCP_TOKEN` env > `codeiq.mcp.auth.token` config > startup failure.
    Constant-time compare via SHA-256 pre-hash + `MessageDigest.isEqual` —
    32-byte digests on both sides defeat the length oracle. RFC 7235 §2.1
    case-insensitive scheme matching (`Bearer`, `bearer`, etc.). Authorization
    header value never reaches a logger from this code. Permit list:
    `/`, `/index.html`, `/favicon.ico`, `/assets/**`, `/static/**`, `/error`,
    `/actuator/health/{liveness,readiness}` — everything else under
    `/api/**`, `/mcp/**`, `/actuator/**` requires the bearer token.
  - **Fail-fast on misconfiguration** (audit #14 partial). `mode=bearer` with
    no token resolved → throws at startup. `mode=none` with active `serving`
    profile and `allow_unauthenticated` not explicitly set → throws at
    startup. `mode=mtls` is reserved and explicitly throws "not yet
    implemented" rather than silently passing through.
  - **Defensive response headers** (audit #13). New
    `config/security/SecurityHeadersFilter` sets `X-Content-Type-Options:
    nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src
    'self'; ... frame-ancestors 'none'`, `Referrer-Policy: no-referrer`,
    `Permissions-Policy` disabling geolocation/camera/microphone.
    `Strict-Transport-Security: max-age=31536000; includeSubDomains` is set
    only when `X-Forwarded-Proto: https` is present (AKS terminates TLS at
    ingress) — setting HSTS over plain HTTP would lock out misconfigured envs.
  - **Uniform error envelope** (audit #7). New
    `api/GlobalExceptionHandler` (`@RestControllerAdvice`,
    `@Profile("serving")`) maps every uncaught exception to
    `{"code","message","request_id"}` with the right HTTP status.
    `IllegalArgumentException` → 400 with surfaced message.
    `ResponseStatusException` → status code passes through. Anything else →
    500 with generic message; the actual exception is logged at WARN with
    the `request_id` so on-call can correlate without leaking stack frames
    to the client. `application.yml` now sets
    `server.error.include-stacktrace: never` + `include-message: never` +
    `include-binding-errors: never` as belt-and-suspenders.
  - **Default CORS deny-all in serving** (audit #13). `config/CorsConfig`
    default changed from loopback patterns to empty. Empty means register
    no mappings → Spring MVC rejects all preflighted cross-origin requests.
    Operators who genuinely need cross-origin (e.g. dev with a separate
    Vite server on a different port) explicitly set
    `codeiq.cors.allowed-origin-patterns`. Logs the resolved state at
    startup. The React UI at `/` is unaffected — it's served same-origin.
  - **Swagger UI / api-docs disabled in serving** (counter-audit C2).
    `springdoc.api-docs.enabled: false` + `springdoc.swagger-ui.enabled: false`
    in the serving profile of `application.yml`. The OpenAPI schema is
    reconnaissance data; reachable only when running locally or with the
    indexing profile.
  - **`management.endpoints.web.exposure.include` narrowed** to `health,info`
    in serving (was `health,info,metrics`); `health.show-details: never`.
    Defense-in-depth alongside the `SecurityFilterChain` `authenticated()`
    rule on `/actuator/**`.
  - **Spring Security autoconfig excluded outside serving.** Without the
    `serving` profile (CLI, tests, IDE runs), Spring Security's default
    HTTP Basic chain would lock all endpoints — adding the starter would
    break ~3000 existing tests that pass through MockMvc with no token.
    `application.yml` excludes `SecurityAutoConfiguration`,
    `SecurityFilterAutoConfiguration`, `UserDetailsServiceAutoConfiguration`
    at the default level; the `serving` profile re-enables them by listing
    only `UserDetailsServiceAutoConfiguration` (so the auto user/password
    is suppressed but the filter chain is built from `SecurityConfig`).
  - **Tests:** 31 new unit tests across `BearerAuthFilterTest` (14 cases:
    missing/wrong/empty/correct/lowercase scheme, length-oracle defense,
    log-leak audit, `shouldNotFilter` paths, `SecurityContextHolder` cleanup),
    `TokenResolverTest` (9 cases for mode/profile/env-priority/fail-fast),
    `SecurityHeadersFilterTest` (5 cases for header presence/HSTS gating),
    `GlobalExceptionHandlerTest` (3 cases verifying the envelope shape and
    no stack-trace leak). Full suite: 3453 tests / 0 failures / 0 errors.

  **Known follow-up (not in this PR):** the React UI cannot read env vars,
  so the SPA shell is unauthenticated to access static assets. API/MCP calls
  from the UI must inject `Authorization: Bearer <token>` from
  operator-supplied localStorage. A first-class UI auth bootstrap (login
  flow + token-issuance endpoint, OR server-side template injection) is its
  own design — tracked as a follow-up issue.

- **Production-readiness PR 2 of 5 — resource limits & abuse protection.**
  Closes audit findings #2, #3, C1 (HIGH) and #10, #11 (MEDIUM).
  - **Cypher transaction timeout** (audit #2). Neo4j embedded
    `GraphDatabaseSettings.transaction_timeout = 30s` configured in
    `Neo4jConfig` — every transaction in the JVM, including `run_cypher`
    and graph traversals, gets a hard wall-clock cap. Catches runaway
    variable-length matches before they starve the page cache.
  - **Result-set cap on `run_cypher`** (audit #2). Hard row cap at
    `mcp.limits.max_results` (default 500); excess rows dropped, response
    carries `truncated: true` + `max_results: N`. Defends the JVM heap
    against `MATCH (a),(b),(c) RETURN a,b,c LIMIT 999999999` blowups.
  - **MCP `traceImpact` depth cap** (audit #10 corrected, C3). New
    `mcp.limits.max_depth` field (default 10) wired into
    `McpTools.traceImpact` via `Math.min`. Defends against
    `RELATES_TO*1..1000` Cartesian explosions on hub nodes.
  - **TTL snapshot cache on topology tools** (audit C1). `McpTools.
    getCachedData()` now backed by a 60-second TTL snapshot. Without it,
    every concurrent `service_dependencies` / `blast_radius` /
    `find_path` / `find_bottlenecks` / `find_circular_deps` /
    `find_dead_services` / `find_node` call paid the full
    `graphStore.findAll()` cost and double-allocated multi-GB heaps.
    A bridge fix; the proper refactor (TopologyService → per-tool Cypher)
    is a tracked follow-up.
  - **Per-client rate limiter** (audit #3). New `RateLimitFilter` using
    Bucket4j 8.18.0 (Apache-2.0). Token bucket sized at
    `mcp.limits.rate_per_minute` (default 300). Keyed by SHA-256 hash of
    the `Authorization` header (so the token never lives in our key map),
    falls back to `X-Forwarded-For` (first hop) or `RemoteAddr`. 429
    response with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`
    headers. Registered before `BearerAuthFilter` so unauthenticated
    brute-force is also throttled.
  - **`/api/file` content-type sniff** (audit #11 corrected). Added
    `Files.probeContentType` guard — non-text MIMEs (`.jks`, `.so`,
    `.png`, native libs) return HTTP 415 with the probed type, instead
    of being served as garbled `text/plain`. Allowlist: `text/*`,
    `application/json`, `application/xml`, `application/x-yaml`,
    `application/javascript`. The byte cap (already enforced by
    `SafeFileReader`) is unchanged.
  - **Tomcat slow-client tarpit** (audit #11). `server.tomcat.connection-
    timeout: 10s`, `max-swallow-size: 1MB` in the serving profile —
    drops connections that hold a virtual thread + Tomcat connection at
    1 KB/s.
  - **CodeQL hardening on the security baseline.** Sanitised request
    method + URI before logging in `BearerAuthFilter` (CWE-117 / CodeQL
    `java/log-injection`); removed env-var name from the bearer-token
    bootstrap log line in `TokenResolver` (CodeQL `java/sensitive-log`);
    documented the deliberate stateless-bearer rationale on
    `SecurityConfig.csrf(disable)` (CodeQL `java/spring-disabled-csrf-protection`
    — no exploit path on a no-cookie surface).
  - **Tests:** new `RateLimitFilterTest` (10 cases: under/over limit,
    separate buckets per client, header-hashing, X-Forwarded-For
    precedence, permit-list, default-rate fallback). Existing 6 test
    classes updated for the new `McpTools` ctor signature. Full suite:
    3672 tests / 0 failures / 0 errors.

  **Known follow-up:** TopologyService still walks the full snapshot
  in-memory after the cache hit — long-term plan is to rewrite each
  topology tool as a targeted Cypher query so the snapshot isn't needed.
  The cache is the bridge; the rewrite reduces peak memory.

- **Production-readiness PR 3 of 5 — supply chain & bundle integrity.**
  Closes the air-gap drift, missing bundle integrity, and unpinned
  scanner versions audit findings.
  - **`codeiq bundle` SHA-256 manifest.** Every entry in `bundle.zip`
    (manifest, scripts, graph DB files, H2 cache, source tree, flow.html,
    optional CLI JAR) is now hashed as it streams through the
    `ZipOutputStream`, and a `checksums.sha256` entry is written last in
    standard GNU coreutils format. Receivers verify with
    `sha256sum -c checksums.sha256`. The hash is computed by feeding each
    chunk to both the SHA-256 digest and the ZIP stream — no double-read
    even for multi-hundred-MB graph databases. Order is deterministic
    (sorted dir walks + sorted git ls-files), so the resulting
    `checksums.sha256` is byte-stable.
  - **No public-internet calls in launcher scripts.** `serve.sh` and
    `serve.bat` previously fell back to `curl -fL https://repo1.maven.org/...`
    when the CLI JAR wasn't bundled — incompatible with the air-gapped
    deploy model documented in `~/.claude/rules/build.md`. The Maven
    Central download is removed; if the JAR is missing, the launcher
    fails fast and tells the operator to either `--include-jar` when
    bundling or stage from an internal artifact mirror. `serve.sh` also
    runs `sha256sum -c --quiet checksums.sha256` automatically before
    launching (skip with `CODEIQ_SKIP_VERIFY=1`).
  - **Pinned Semgrep version.** `.github/workflows/security.yml` was
    `pip install semgrep` (floating) — Scorecard's
    `Pinned-Dependencies` flagged it. Now pinned to `semgrep==1.161.0`
    (latest stable as of 2026-04-28). Bumps go through Dependabot's pip
    ecosystem on a documented cadence.
  - **Tightened secret-pattern exclusions.** `.gitignore` previously
    only matched `.env` / `.env.local` — gaps for `.env.prod`,
    `.env.test`, JKS / P12 keystores, SSH private keys, and
    cloud-credential JSON. Broadened to `.env.*` plus explicit globs
    for `*.jks`, `*.p12`, `*.pfx`, `*.keystore`, `id_{rsa,ecdsa,ed25519,dsa}`,
    `credentials.{json,yaml}`, `secrets.{json,yaml}`,
    `*.serviceaccount.json`. `.dockerignore` mirrors the same rules
    (Docker resolves COPY against the build context, which includes
    untracked working-tree files; .dockerignore does not inherit
    .gitignore).
  - **Bundle verification runbook.** `shared/runbooks/release.md` §4a
    documents consumer-side `sha256sum -c` workflow, including the
    deliberate exclusion of `checksums.sha256` from itself (would be
    circular) and the Sigstore/GPG out-of-band signing that backs
    `checksums.sha256` against tampering.
  - **Tests:** `BundleCommandTest#bundleCreatesZipWithCorrectStructure`
    extended with 4 new asserts: serve.sh contains no `curl`/`maven.org`
    references (defense against re-introduction), `checksums.sha256`
    exists, format-conforms to `<64-hex>  <path>`, and excludes itself.
    Full suite: 3672 tests / 0 failures / 0 errors.

- **Production-readiness PR 4 of 5 — observability.** Closes the missing-MDC,
  hot-path-health-probe, MCP-error-leak, and structured-logging gaps.
  - **`RequestIdFilter` (new).** Populates SLF4J `MDC.request_id` FIRST in
    the security chain so every downstream filter, controller, MCP tool,
    and exception handler sees the same correlation ID. Strict allow-list
    on inbound `X-Request-Id` (8–64 hex/dash/underscore chars) prevents
    log-forging; bad inputs are replaced with a generated UUID. Echoes
    the ID back to the client in the `X-Request-Id` response header. MDC
    is cleared in `finally` to prevent leak across pooled threads (both
    Tomcat platform and virtual-thread carriers). Pre-PR-4 every
    `MDC.get("request_id")` call returned null; the four downstream
    consumers (BearerAuthFilter, RateLimitFilter, GraphController,
    GlobalExceptionHandler) all generated synthetic UUIDs that never
    correlated.
  - **JSON-structured logging** (`logback-spring.xml`). Serving profile
    switches the encoder from `%msg%n` plaintext to LogstashEncoder
    (`logstash-logback-encoder` 9.0 — MIT). One JSON event per log line
    with `ts`, `level`, `logger`, `thread`, `msg`, `stack`, all MDC
    entries (`request_id`), and a static `application: codeiq` field for
    multi-pod ingestion. Indexing/CLI profiles keep plaintext to avoid
    JSON noise leaking into `codeiq index` output.
  - **`GraphHealthIndicator` 30s TTL cache.** Pre-PR-4 every readiness
    probe (k8s default ~1Hz) ran a `MATCH (n) RETURN count(n)` Cypher
    query — wakes the page cache, competes with API traffic.
    `AtomicReference<CachedHealth>` lock-free cache absorbs the flood;
    one underlying probe per 30s regardless of caller concurrency.
    Error response sanitized too: pre-PR-4 the `error` detail
    surfaced `e.getMessage()` (CodeQL `java/error-message-exposure`,
    permitAll endpoint = anonymous probers). Now only an `error_class`
    indicator; full stack is logged at WARN.
  - **Liveness/readiness groups** (`application.yml`). Pre-PR-4
    `GraphHealthIndicator` contributed to BOTH probes — a graph-down
    event would flap the pod (k8s killing it) instead of just routing
    away. Pinned to readiness only:
    `liveness: livenessState`, `readiness: readinessState,
    graphHealthIndicator`.
  - **Prometheus metrics** (`/actuator/prometheus`). Added
    `micrometer-registry-prometheus` dep. Exposed under the bearer-
    authenticated `/actuator/**` rule (NOT permitAll — full metrics
    tree is reconnaissance data). Application tag `codeiq` for
    multi-pod scraping. Step interval 10s.
  - **Structured MCP error envelope.** Pre-PR-4 every MCP tool catch
    block returned `toJson(Map.of("error", e.getMessage()))` — flat
    string, no correlation. Refactored to a centralized
    `errorEnvelope(code, e)` helper that returns
    `{code, message, request_id, error}` (legacy `error` field
    preserved for backwards-compat). Codes assigned per failure
    category: `INTERNAL_ERROR`, `INVALID_INPUT`, `FILE_READ_FAILED`,
    `SERIALIZATION_FAILED`. Full exception logged server-side with
    request_id; only sanitized envelope reaches the client. `readFile`
    no longer concatenates `e.getMessage()` into a string (CWE-209).
  - **Tests:** new `RequestIdFilterTest` (7 cases — UUID generation,
    header pass-through, control-char rejection, length bounds, MDC
    clear-in-finally including throw path). `GraphHealthIndicatorTest`
    extended with cache-hit assertion (3 calls → 1 underlying
    `count()`) and updated for sanitized error fields.
    `McpToolsTest#readFileShouldHandleMissingFile` updated for new
    envelope contract. Full suite: 3680 tests / 0 failures / 0 errors.

- **Production-readiness PR 5 of 5 — config validation, integration coverage,
  docs refresh.** Final PR of the production-readiness series. Closes the
  remaining audit findings around silent oversized-input clamping, missing
  end-to-end coverage of the serving filter chain, and stale tech-stack
  pins in `CLAUDE.md`.
  - **MCP request-bound clamping in `McpTools`.** `queryNodes` /
    `queryEdges` `limit` parameters are now `Math.min(requested,
    mcp.limits.max_results)` so a caller asking for `LIMIT 1_000_000` no
    longer trips the JVM into a multi-GB allocation before the
    `run_cypher` row cap kicks in. `getEgoGraph` radius is clamped to
    `mcp.limits.max_depth` for the same reason — a `radius=999` ego
    walk on a hub node is a Cartesian explosion. `searchGraph` limit
    follows the same rule. Per-call defense-in-depth on top of the
    transaction-timeout cap from PR 2.
  - **`ConfigValidator` hard ceilings + blank-string checks.** Added
    explicit validations for fields previously only typed as
    `Integer`/`Long` with no range:
    - `mcp.limits.max_payload_bytes` — must be `> 0` (was silently
      `null` → no payload cap → infinite-row run_cypher could OOM).
    - `mcp.limits.rate_per_minute` — must be `> 0`.
    - `mcp.limits.max_depth` — must be `1..100`. The 100 ceiling is a
      DoS sentinel: variable-length Cypher with depth >100 is
      pathological in practice (a graph with 100M nodes and fan-out 5
      reaches every node by depth 12), so anything higher is either a
      misconfig or a reconnaissance probe. Catch at config-load, not
      at query time.
    - `mcp.auth.token_env` / `mcp.auth.token` — when mode=bearer,
      blank-string values fail validation rather than being silently
      coerced to null and then fail-fasting at startup with a
      mysterious "no token resolved" message.
  - **`ServingChainIntegrationTest` (new — 9 cases).** Fills the gap
    where each filter (`RequestIdFilter`, `SecurityHeadersFilter`,
    `RateLimitFilter`, `BearerAuthFilter`) had unit-test coverage in
    isolation but no test exercised the full chain together. Asserts
    the cross-filter contract: 401 envelope shape with `request_id`
    echoed in the `X-Request-Id` response header; 429 envelope with
    `Retry-After` and `X-RateLimit-Remaining: 0`; security headers
    present on every response (success, 401, 429); inbound
    `X-Request-Id` propagated end-to-end when valid; control-char
    inbound rejected and replaced with a generated UUID; rate-limit
    bucket isolation per token (one client exhausting their bucket
    does not affect another); health endpoint bypasses auth (kubelet
    probes carry no token). Manually chains the four filters via
    lambda `FilterChain` instances rather than spinning up a full
    `@SpringBootTest` so the run is sub-second and doesn't need
    Neo4j. Lives in `io.github.randomcodespace.iq.config.security`
    package to access package-private `TokenResolver.resolve()`.
  - **`CLAUDE.md` tech-stack pin refresh.** Stale version pins
    updated to current: Spring Boot 4.0.5 → 4.0.6, Spring AI 2.0.0-M3
    → 2.0.0-M4, Neo4j Embedded 2026.02.3 → 2026.04.0; added
    Bucket4j 8.18.0, logstash-logback-encoder 9.0,
    micrometer-registry-prometheus to the dependency list.
  - **Tests:** new `ServingChainIntegrationTest` (9 cases). Full
    suite: 3689 tests / 0 failures / 0 errors / 32 skipped.

## [0.1.0] - 2026-03-28

First general-availability cut. See the
[v0.1.0 GitHub Release](https://github.com/RandomCodeSpace/codeiq/releases/tag/v0.1.0)
for the full notes.

- 97 detectors across 35+ languages.
- Three-command pipeline: `index` → `enrich` → `serve`.
- Read-only REST API (37 endpoints), MCP server (34 tools, Spring AI 2.0
  streamable HTTP), and React UI shipped inside a single signed JAR.
- Maven Central coordinates: `io.github.randomcodespace.iq:code-iq`.

## [0.0.1-beta.0] – [0.0.1-beta.46] - 2026-Q1

Pre-GA beta line. Full per-tag notes on
[GitHub Releases](https://github.com/RandomCodeSpace/codeiq/releases?q=prerelease%3Atrue).
The beta cadence shipped from `beta-java.yml` on `workflow_dispatch`; each
beta is an immutable Sonatype Central beta artifact + GPG-signed annotated
git tag + GitHub pre-release.

[Unreleased]: https://github.com/RandomCodeSpace/codeiq/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/RandomCodeSpace/codeiq/releases/tag/v0.1.0
