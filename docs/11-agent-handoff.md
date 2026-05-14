# 11 — Agent handoff

> **One-stop brief for the next AI coding agent (Claude / Cursor / Cline / etc.) that lands in this repo.** Read this once, plus `CLAUDE.md`, before doing anything else.

## Project in 20 lines

1. **codeiq** is a Go CLI + stdio MCP server that builds a deterministic code-knowledge graph from source trees.
2. Single static binary (`cmd/codeiq/main.go`), CGO-mandatory (Kuzu + SQLite + tree-sitter all link C/C++).
3. Module path is `github.com/randomcodespace/codeiq` (hoisted from `/go/` in PR #162 — paths at repo root, not `go/...`).
4. Pipeline: `index` (files → SQLite cache) → `enrich` (cache → Kuzu graph + FTS indexes) → `mcp`/`stats`/`query`/etc.
5. **Zero LLM in the index/enrich pipeline.** Same input ⇒ same output, byte-for-byte. The only LLM touch is the opt-in `codeiq review` subcommand against Ollama.
6. 100 detectors across 35+ languages live in `internal/detector/<family>/<name>.go`, each implementing the `detector.Detector` interface.
7. **Critical:** every detector category must be blank-imported in `internal/cli/detectors_register.go` — forget it and the binary ships dead for that family.
8. Storage: SQLite cache at `<repo>/.codeiq/cache/codeiq.sqlite`, Kuzu graph at `<repo>/.codeiq/graph/codeiq.kuzu/`. Both gitignored.
9. Kuzu 0.11.3 — bundled FTS extension (`CALL CREATE_FTS_INDEX` / `QUERY_FTS_INDEX`, BM25 ranked). Native parameterized `LIMIT $lim`. Mutation gate in `internal/graph/mutation.go` for `OpenReadOnly`.
10. MCP server exposes 10 tools (6 mode-driven + `run_cypher` + `read_file` + `generate_flow` + `review_changes`). Read-only — no write tools.
11. Determinism is non-negotiable. Don't iterate Go maps without sorting keys. Every detector has a determinism test.
12. Tests: `CGO_ENABLED=1 go test ./... -count=1` — ~880+ pass; CI runs `-race` too. All tests use `t.TempDir()`; no test writes outside its tempdir.
13. CI: `go-ci.yml` (vet/test/staticcheck/gosec/govulncheck), `perf-gate.yml` (RSS ≤ 300 MB on fixture-multi-lang), `security.yml` (OSV+Trivy+Semgrep+Gitleaks+jscpd+SBOM), Scorecard, release-go/darwin.
14. Releases: tag `vX.Y.Z` push → Goreleaser + Cosign keyless via GitHub OIDC + Syft SBOMs + draft Release → `gh release edit --draft=false` to publish.
15. Current version: **v0.4.1** (2026-05-14). All earlier tags were deleted from GitHub because `proxy.golang.org` permanently caches version content; reusing a deleted tag name serves stale Python-prototype-era zips.
16. **Never re-use a deleted version number.** Always tag forward (v0.4.X+1).
17. There's no REST API, no web UI, no telemetry, no auto-update, no Docker image. Operator-driven CLI + stdio MCP only.
18. Java reference implementation was deleted at v0.3.0 cutover (PR #132). Will not return.
19. `parity/` directory is a build-tag-gated harness (`-tags parity`) from the Java→Go port; mostly idle, can be revived or deleted.
20. Documentation lives entirely under `docs/` + `README.md` + `CLAUDE.md`. Wiped + rebuilt in this handoff (PR #168 + the doc-rewrite this file is part of).

## Top 20 files to read first

In order — each one is the entry point for one concept. Reading all 20 takes ~30 minutes and covers ~90% of the codebase's surface.

| Order | File | What you learn |
|---|---|---|
| 1 | [`README.md`](../README.md) | User-facing overview + install paths + MCP wiring example |
| 2 | [`CLAUDE.md`](../CLAUDE.md) | Repo-specific instructions for AI agents (this file's sibling) |
| 3 | [`docs/02-architecture.md`](02-architecture.md) | The component map |
| 4 | [`docs/04-main-flows.md`](04-main-flows.md) | Per-flow entry points + failure modes |
| 5 | [`docs/06-data-model.md`](06-data-model.md) | Kuzu + SQLite schemas + canonical taxonomy |
| 6 | [`cmd/codeiq/main.go`](../cmd/codeiq/main.go) | Entry point (5 lines) |
| 7 | [`internal/cli/root.go`](../internal/cli/root.go) | Cobra root command + global flags |
| 8 | [`internal/cli/detectors_register.go`](../internal/cli/detectors_register.go) | **The choke point** — every detector family blank-imported here |
| 9 | [`internal/analyzer/analyzer.go`](../internal/analyzer/analyzer.go) | Index pipeline orchestration |
| 10 | [`internal/analyzer/enrich.go`](../internal/analyzer/enrich.go) | Enrich pipeline orchestration |
| 11 | [`internal/analyzer/graph_builder.go`](../internal/analyzer/graph_builder.go) | Dedup + phantom-edge drop |
| 12 | [`internal/detector/detector.go`](../internal/detector/detector.go) | `Detector` interface + `Default` registry |
| 13 | [`internal/detector/jvm/java/spring_rest.go`](../internal/detector/jvm/java/spring_rest.go) | A canonical detector implementation |
| 14 | [`internal/graph/schema.go`](../internal/graph/schema.go) | Kuzu schema definition |
| 15 | [`internal/graph/bulk.go`](../internal/graph/bulk.go) | CSV staging + Kuzu COPY FROM (pipe-delim + RFC-4180) |
| 16 | [`internal/graph/mutation.go`](../internal/graph/mutation.go) | Read-only mutation gate |
| 17 | [`internal/graph/indexes.go`](../internal/graph/indexes.go) | FTS index management + search helpers |
| 18 | [`internal/mcp/server.go`](../internal/mcp/server.go) | MCP server bootstrap |
| 19 | [`internal/mcp/tools_consolidated.go`](../internal/mcp/tools_consolidated.go) | The 6 mode-driven tools + delegation pattern |
| 20 | [`.goreleaser.yml`](../.goreleaser.yml) + [`.github/workflows/release-go.yml`](../.github/workflows/release-go.yml) | Release pipeline |

## Commands future agents should use

```bash
# Build + smoke
CGO_ENABLED=1 go build -o /tmp/codeiq ./cmd/codeiq
/tmp/codeiq --version
/tmp/codeiq index testdata/fixture-minimal && /tmp/codeiq enrich testdata/fixture-minimal && /tmp/codeiq stats testdata/fixture-minimal

# Test
CGO_ENABLED=1 go test ./... -count=1                  # all 880+ tests
CGO_ENABLED=1 go test ./... -race -count=1            # CI-equivalent
CGO_ENABLED=1 go test ./internal/<pkg>/... -count=1   # single package
CGO_ENABLED=1 go test ./... -count=1 -run TestFooBar  # single test

# Static analysis (mirrors CI)
go vet ./...
go install honnef.co/go/tools/cmd/staticcheck@2025.1.1 && "$(go env GOPATH)/bin/staticcheck" ./...
go install github.com/securego/gosec/v2/cmd/gosec@v2.22.0 && "$(go env GOPATH)/bin/gosec" -exclude=G104,G115,G202,G204,G301,G304,G306,G401,G404,G501 ./...
go install golang.org/x/vuln/cmd/govulncheck@latest && "$(go env GOPATH)/bin/govulncheck" ./...

# Manual perf check (mirrors perf-gate.yml)
/usr/bin/time -v /tmp/codeiq enrich testdata/fixture-multi-lang   # expect <8s wall, <300MB RSS

# Inspect releases / tags
gh release list
git tag --list
gh pr list --state open --json number,title

# Run / verify go install
CGO_ENABLED=1 go install github.com/randomcodespace/codeiq/cmd/codeiq@v0.4.1
codeiq --version
```

## Commands future agents should AVOID

| Command | Why |
|---|---|
| `git push --force` to `main` | Branch protection blocks it; bypassing rewrites shared history. |
| `git tag --force vX.Y.Z` to reuse a deleted version | proxy.golang.org caches version content immutably. The reused tag won't refresh; users get the stale zip. |
| `git tag v0.1.0`, `v0.2.0`, `v0.3.0`, `v1.0.0` | All deleted from GitHub but poisoned at proxy.golang.org. Use a never-previously-used version (next is **v0.4.2** unless deleted; **v0.5.0** is safer). |
| `gh release delete --cleanup-tag` on a published version | Same poison risk — once a tag has had `go install …@<tag>` run against it, the proxy has cached the zip. |
| `rm -rf .codeiq/` mid-pipeline | OK between `index` and `enrich`, but never between an `enrich` and a running `mcp` — the server will lose its read store. |
| `goreleaser release` locally without `--snapshot` | Will try to create a real GitHub Release. Use `goreleaser release --snapshot --clean` for local dry-runs. |
| `CGO_ENABLED=0 go build` | Will fail at link time. Kuzu + SQLite + tree-sitter all require CGO. |
| `cd go && go build` | The `go/` subdir was hoisted to root in PR #162. Stale instructions from older docs. |
| `gh pr merge` with `--admin` to bypass CI | go-ci.yml + security.yml are required for a reason. Don't bypass. |

## Architectural rules

1. **MCP server is strictly read-only.** No tool may mutate the Kuzu store. `run_cypher` enforces this via [`MutationKeyword`](../internal/graph/mutation.go); `read_file` enforces path sandboxing.
2. **Index/enrich are CLI-only.** The MCP layer never triggers them. Operator runs them.
3. **Detectors are stateless.** No mutable struct fields. The single shared instance per detector type registers once at `init()` time and is called concurrently from the worker pool.
4. **Determinism over micro-optimization.** Never iterate a map without sorting keys. Linker outputs go through `.Sorted()` at the call site. `Snapshot()` sorts.
5. **Confidence ladder is monotonic.** `LEXICAL (0.6) < SYNTACTIC (0.8) < RESOLVED (0.95)`. Merges keep the higher-confidence node; donor only fills missing properties.
6. **Phantom edges drop at Snapshot.** Detectors that emit edges to "external" or "file-anchor" nodes must explicitly create those nodes via `base.EnsureFileAnchor` / `EnsureExternalAnchor`.
7. **ID prefixes are stable.** `<lang>:file:<path>`, `<lang>:external:<name>`, `service:<dir>:<name>`, `topic:<name>`. The GraphBuilder dedup map keys off these.
8. **No telemetry. No auto-update. No outbound network during index/enrich/mcp.** Only `codeiq review` reaches the Ollama endpoint.
9. **CGO is required everywhere.** This is not negotiable for as long as we use Kuzu + SQLite + tree-sitter.
10. **One CodeNode table for all NodeKinds.** Schema simplicity. Don't add per-label tables.

## Coding conventions

- **`gofmt`-clean.** CI verifies via `go vet`.
- **No `interface{}` unless needed.** Prefer concrete types or generics where possible (Go 1.25 has them).
- **Errors via `fmt.Errorf("layer: ...: %w", err)`.** The "layer:" prefix tells you which package failed.
- **No third-party assertion libraries in tests.** Use stdlib `testing` + plain `t.Fatalf`.
- **Receiver names: 1–3 letters, lowercase.** Match the struct's first letter (`s *Store`, `n *CodeNode`).
- **Detector files: `snake_case.go`.** Test files: `<name>_test.go`.
- **Detector type names: `PascalCaseDetector`** (e.g. `SpringRestDetector`).
- **Detector `Name()` returns `snake_case`** (e.g. `"spring_rest"`).
- **Confidence is set by the base helper** (`base.RegexDetectorDefaultConfidence` or `base.StructuredDetectorDefaultConfidence`), not stamped in each detector.
- **Conventional commits** for PRs: `feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`, `perf:`.

## Common traps

| Trap | Symptom | Fix |
|---|---|---|
| Forgot to blank-import a new detector category | `codeiq plugins list` doesn't show the detector; emissions silently 0 | Add the leaf package to [`internal/cli/detectors_register.go`](../internal/cli/detectors_register.go) |
| Detector emits an edge but not the target node | Edge silently dropped at `Snapshot()`; `analyzer.Stats.DroppedEdges` ticks up | Use [`base.EnsureFileAnchor`](../internal/detector/base/imports_helpers.go) / `EnsureExternalAnchor` |
| Detector emits the same node from two callbacks with different framework values | Higher-confidence framework wins; lower-confidence value is dropped | Intended behavior — confidence-aware merge. If you want the lower-confidence value to survive, raise its confidence at the source. |
| Re-running `go test` after a Kuzu bump and seeing cached results | Stale test results | `-count=1` flag |
| `go install` returns the wrong version | proxy.golang.org cache poisoning from a deleted tag | Use an explicit `@v0.4.1` (or higher); avoid `@latest` |
| Tag pushed but no release artifacts | release-darwin race timed out | (Fixed in PR #165) — but if it recurs, `gh run rerun --failed <run-id>` on the darwin job |
| `gh release view` returns "release not found" but `git tag` shows it | The Release is in DRAFT state and `gh release view` doesn't display drafts to non-maintainers | `gh release view <tag> --json isDraft` to confirm; `gh release edit <tag> --draft=false` to publish |
| `--max-buffer-pool=8589934592` to use 8 GiB | Bytes, not GiB. 2 GiB = `2147483648`, 8 GiB = `8589934592`. | Use the byte value. |
| Adding `TODO`/`FIXME` to code | Project policy is no TODOs in `main`. Capture in a doc or issue instead. | Edit a doc under `docs/` or open a tracked issue. |

## Current unfinished work (at the time of this handoff)

| Item | Status |
|---|---|
| PR #169 — goreleaser `files:` glob fix | **OPEN, CI green**. Merge before next tag push so v0.4.2+ works. |
| `v0.4.2` tag | Created then deleted because the v0.4.2 release failed on the goreleaser literal-file pattern. Once #169 lands, re-tag as v0.4.2. |
| CHANGELOG `[Unreleased]` section | Will need to be cut to `[v0.4.2]` when the next release ships. See [`docs/09-build-deploy-release.md`](09-build-deploy-release.md). |
| New reference docs | This is the deliverable. After this PR lands, the repo will have a clean `docs/` tree (the user wiped the prior set in PR #168). |
| `parity/` harness | Build-tag idle since the Java port. Revive or delete. |
| `config <action>` subcommand | Mentioned in older docs; never implemented. Root `--config` flag works. Implement or remove the mention. |

## Recommended next tasks (priority order)

1. **Merge PR #169** (goreleaser glob fix) → tag v0.4.2 → publish the release.
2. **Wire the new reference docs into `go-ci.yml` or `security.yml` link-check** — broken markdown links would be the most likely doc-bitrot vector.
3. **Add a `gh attestation verify` example to the README** — the binaries ship with build provenance but it's invisible to consumers.
4. **Decide on `parity/`** — keep + document, or delete.
5. **Fuzz [`MutationKeyword`](../internal/graph/mutation.go)** — adversarial Cypher with comment / string smuggling.
6. **Property-fuzz the CSV bulk-load writer** — random byte sequences in node/edge properties (catches the next #150/#152/#153-style bug).
7. **Snapshot tests for tree-sitter grammar outputs** — pin grammar versions; alert on AST node-name drift.
8. **Implement `codeiq config <action>` or remove every mention** of it from the codebase (it's already deleted from docs).
9. **Add a `go-ci.yml` step that runs `find testdata -name '*.md'` to confirm fixtures stay intact** — easy to accidentally `git rm` `testdata/fixture-minimal/README.md` thinking it's a stale doc.
10. **Consider a structured-logging layer** if a long-running mode (e.g. a watch / re-index daemon) is ever added.

## When in doubt

- Check `git log -p --since="1 month"` for what changed.
- Look for a determinism test on the package you're modifying — if there isn't one, add one.
- Read [`docs/10-known-risks-and-todos.md`](10-known-risks-and-todos.md) before making big changes.
- The user values terse direct communication. Skip preamble; show the change + the verification command.
- Never commit without explicit user request. Never push without explicit user request.
