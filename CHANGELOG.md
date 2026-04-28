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
