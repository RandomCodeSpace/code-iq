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

### Changed

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
