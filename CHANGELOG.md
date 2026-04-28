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
  invariants. Detector migrations to consume `ctx.resolved()` and the
  resolver-bootstrap-into-Analyzer hook follow in sub-project 1 Phase 5.

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
