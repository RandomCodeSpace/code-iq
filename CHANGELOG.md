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

## [v0.3.0] - 2026-05-13

### Changed

- **Phase 6 cutover — Java reference deleted, Go is the only
  implementation.** Single static binary released from `go/cmd/codeiq`.
  Deletes `src/`, `pom.xml`, `spotbugs-exclude.xml`,
  `.github/workflows/{ci-java,beta-java,release-java,go-parity}.yml`.
  ~8.9 MB / ~1500 files removed.

### v0.3.0 surface

What ships in v0.3.0 (carrying forward from the c363727 squash + c630245 release infra):

- 100 detectors across 35+ languages.
- Deterministic graph with confidence-aware NodeMerger and canonical
  `(src, tgt, kind)` edge dedup; phantom-drop visibility.
- 6 consolidated mode-driven MCP tools + `run_cypher` escape hatch +
  `review_changes`. The deprecated 34 narrow tools remain wired for
  back-compat in this release; targeted for removal in a future minor.
- `codeiq review` CLI + `review_changes` MCP tool with Ollama (local
  or Cloud) for LLM-driven PR review against graph evidence.
- Goreleaser cross-platform binaries (linux/amd64, linux/arm64,
  darwin/arm64), SPDX SBOMs, Cosign keyless signatures via GitHub
  OIDC + Sigstore Rekor.
- Per-PR perf-regression gate (`perf-gate.yml`).

### Removed

- `src/main/java/`, `src/test/java/`, `src/main/frontend/`,
  `src/main/resources/`, `pom.xml`, `spotbugs-exclude.xml`.
- `.github/workflows/ci-java.yml`, `release-java.yml`, `beta-java.yml`,
  `go-parity.yml` (the last needed the Java jar build that's gone).

### Migration notes

Pre-cutover Java-side history is preserved in the squash-merge commit
`c363727` and on `origin/main`. Anyone needing to recover Java files
can `git show c363727:<path>` or `git checkout c363727 -- <path>`.

[v0.3.0]: https://github.com/RandomCodeSpace/codeiq/releases/tag/v0.3.0

### Added

- **Phase 5 release infrastructure for the Go binary** —
  `.goreleaser.yml` + `.github/workflows/release-go.yml` cut a
  multi-platform (linux/amd64, linux/arm64, darwin/arm64) release on
  every `v*.*.*` tag push. Each archive ships with an SPDX SBOM
  (Syft), and the `checksums.sha256` manifest is keyless-signed via
  Cosign + GitHub OIDC (Sigstore Rekor transparency log). Optional
