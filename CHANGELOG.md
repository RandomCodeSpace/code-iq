# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Per-tag release notes are published on
[GitHub Releases](https://github.com/RandomCodeSpace/codeiq/releases). This file
captures the cross-cutting changes that span multiple commits or releases (new
quality gates, security policy, deploy surface, etc.) — see the GitHub Release
for that specific tag for the per-commit details.

The release history was reset at v0.4.0: all earlier GitHub Releases and tags
(`v0.0.x`, `v0.1.x`, `v0.2.x`, `v0.3.0`, `v1.0.0`) were deleted because the
Go module proxy permanently caches every published version's content. A
deleted `v0.1.0` tag from the original Python-prototype era would have
poisoned `go install` for any reused number. v0.4.0 is the first never-used
version after the cleanup; the commit history under it is unchanged and
includes everything that previously shipped as v0.3.0 plus the post-cutover
work listed below. Historical sections below v0.4.0 are kept for the record
even though their GitHub Releases are gone.

## [Unreleased]

## [v0.4.1] - 2026-05-14

Patch release. Pure CI / dependency hygiene — no codeiq pipeline or
detector behavior changes.

### Fixed

- `release-darwin.yml` race against `release-go.yml`: bumped poll budget
  from 90 s to 15 minutes and added an early-bail when the upstream
  release-go run for the tag concluded as failure / cancelled /
  timed_out. Pinned `--repo` on every `gh` invocation. PR #165.

### Changed

- Routine Dependabot bumps: `github.com/spf13/pflag` 1.0.9 → 1.0.10
  (PR #163), `step-security/harden-runner` 2.19.1 → 2.19.2 (PR #164).

[v0.4.1]: https://github.com/RandomCodeSpace/codeiq/releases/tag/v0.4.1

## [v0.4.0] - 2026-05-14

First release of the Go-native codeiq after the `/go/` subdirectory
hoist. Same commit content as the (now deleted) v0.3.0 plus the
post-cutover work below.

### Fixed

- `codeiq enrich` survives polyglot codebases at `~/projects/` scale (49k
  files, 15 GiB host). Pre-fix runs OOM-killed at exit 137; now exits 0
  with peak RSS 1.8–2.2 GiB. PRs #145, #146, #147, #148.
- Five enrich pipeline correctness fixes that surfaced at scale (each one
  blocked the next — landed in order):
  - PR #149: MCP dispatch arg names in `tools_consolidated` (7 modes were
    permanently returning `INVALID_INPUT`).
  - PR #150: pipe-delimited Kuzu COPY staging — JSON property values
    containing commas (e.g. Python `imports`) no longer break the parser.
  - PR #151: path-qualified SERVICE node IDs — two modules sharing a name
    in different folders no longer collide on primary key.
  - PR #152: TOML detector unquotes quoted keys (e.g. airflow's
    `.cherry_picker.toml` `"check_sha" = ...`).
  - PR #153: explicit `QUOTE='"', ESCAPE='"'` on Kuzu COPY so RFC-4180
    quoting round-trips correctly (Istio EDS cluster names with `|`).

### Changed

- **Module hoisted from `/go/` to repo root** (PR #162). Module path drops
  the `/go` suffix; `go install github.com/randomcodespace/codeiq/cmd/codeiq@vX.Y.Z`
  resolves directly. 320 Go files rewritten, 5 CI workflows + goreleaser
  + Dependabot config aligned.
- **Kuzu 0.7.1 → 0.11.3** (PR #155). Migrates the embedded graph DB to a
  release with bundled FTS extension and bound `LIMIT`/`SKIP` parameters.
- **Real FTS replaces CONTAINS predicates** (PR #159). `SearchByLabel`
  and `SearchLexical` now route through `CALL QUERY_FTS_INDEX` with BM25
  ranking; CONTAINS fallback retained for pre-enrich graphs. Auto-suffix
  `*` on single-token queries preserves prefix-match UX. Two indexes
  created at enrich time:
  - `code_node_label_fts`   over `(label, fqn_lower)`
  - `code_node_lexical_fts` over `(prop_lex_comment, prop_lex_config_keys)`
- **Parameterized `LIMIT`/`SKIP`** across the query layer (PR #159).
  `intLiteral` helper removed; `fmt.Sprintf("LIMIT %d", n)` replaced with
  `LIMIT $lim` bindings.
- **Dropped `stringsToAny` widener** (PR #159). Kuzu 0.11's Go binding
  accepts `[]string` directly for `IN $param` clauses.
- **Mutation gate** allow-lists read-only `CALL QUERY_FTS_INDEX` (PR #159);
  `CREATE_FTS_INDEX` / `DROP_FTS_INDEX` stay blocked under
  `OpenReadOnly`.
- **Dependabot config** rewritten (PR #154) — drops the dead Java `maven`
  (`/`) and `npm` (`/src/main/frontend`) ecosystems, adds `gomod` with
  groups for `kuzu`, `tree-sitter`, `mcp`, `cobra-viper`, `sqlite`,
  `test-libs`. Routine bumps that followed: `go-kuzu` 0.7.1 → 0.11.3
  (PR #155), `spf13/cobra` + `pflag` group (PR #156), `go-sqlite3`
  1.14.22 → 1.14.44 (PR #157), 4 GitHub Actions (PR #158).

### Added

- `codeiq enrich` knobs (PR #147): `--memprofile=<path>` writes a Go
  heap profile; `--max-buffer-pool=N` overrides the 2 GiB Kuzu cap;
  `--copy-threads=N` overrides `MaxNumThreads` default.
- Perf-gate CI step (PR #148): `/usr/bin/time -v codeiq enrich` runs on
  fixture-multi-lang; fails the build if peak RSS exceeds 300 MB.
- `runtime/debug.BuildInfo` fallback in the `buildinfo` package
  (PR #161). `go install …@vX.Y.Z` binaries now self-identify their
  version, commit, and date without needing the goreleaser `-ldflags -X`
  path — the Go toolchain stamps `vcs.revision`/`vcs.time`/`vcs.modified`
  and the buildinfo `init()` reads them. Goreleaser's ldflags still win
  on release artifacts.

[v0.4.0]: https://github.com/RandomCodeSpace/codeiq/releases/tag/v0.4.0

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
