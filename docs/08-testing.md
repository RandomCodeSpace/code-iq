# 08 — Testing

## Framework

Standard library `testing`. **No testify, no gomock, no third-party assertion lib.** This is intentional — keeps the supply-chain surface narrow and matches Go community defaults.

Test file count per package (sampled from `find internal -name '*_test.go' | wc -l`):

| Package | _test.go files |
|---|---|
| `internal/detector/` | **104** (every detector has positive + negative + determinism tests) |
| `internal/intelligence/` | 15 |
| `internal/cli/` | 13 |
| `internal/mcp/` | 12 |
| `internal/analyzer/` | 11 |
| `internal/graph/` | 7 |
| `internal/model/` | 6 |
| `internal/parser/` | 5 |
| `internal/flow/` | 4 |
| `internal/cache/` | 3 |
| `internal/query/` | 3 |
| `internal/review/` | 3 |
| `parity/` | 1 |

**Total:** 884+ tests pass cleanly on `main`. Race detector passes too (CI runs `go test -race -count=1`).

## How to run

```bash
# Full suite (~30s on a 4-core dev machine)
CGO_ENABLED=1 go test ./... -count=1

# With race detector (CI default)
CGO_ENABLED=1 go test ./... -race -count=1

# Single package
CGO_ENABLED=1 go test ./internal/detector/jvm/java/... -count=1

# Single test (verbose)
CGO_ENABLED=1 go test ./internal/mcp/... -count=1 -run TestRunCypher -v

# Race + verbose on one package
CGO_ENABLED=1 go test ./internal/analyzer/... -race -count=1 -v
```

Always use `-count=1` to bypass the Go test cache when you've changed CGO-linked deps (Kuzu / SQLite version bumps invalidate cached results).

## Test structure

### Detector tests — three required cases per detector

Each detector ships three test cases:

1. **Positive** — sample source that the detector matches; assert emitted nodes/edges by ID + properties.
2. **Negative** — sample source the detector must *not* match (avoids false-positives on adjacent frameworks).
3. **Determinism** — run the detector twice, assert byte-identical emissions. Catches map-iteration order bugs.

Sample: [`internal/detector/jvm/java/spring_rest_test.go`](../internal/detector/jvm/java/spring_rest_test.go). Pattern is uniform across the 100 detectors.

### Analyzer tests

[`internal/analyzer/analyzer_test.go`](../internal/analyzer/analyzer_test.go), [`enrich_test.go`](../internal/analyzer/enrich_test.go), [`graph_builder_test.go`](../internal/analyzer/graph_builder_test.go). Use `t.TempDir()` for cache + graph paths, write a few synthetic source files, run the pipeline, assert on `Stats` + graph contents.

### Graph layer tests

[`internal/graph/store_test.go`](../internal/graph/store_test.go), [`schema_test.go`](../internal/graph/schema_test.go), [`bulk_test.go`](../internal/graph/bulk_test.go), [`cypher_test.go`](../internal/graph/cypher_test.go), [`indexes_test.go`](../internal/graph/indexes_test.go), [`readonly_test.go`](../internal/graph/readonly_test.go), [`reads_test.go`](../internal/graph/reads_test.go). Each opens Kuzu under `t.TempDir()`. Key regression tests:

- `TestBulkLoadEdgesCommaInProperties` — JSON properties with commas (PR #150).
- `TestBulkLoadNodesCommaInProperties` — same on nodes.
- `TestBulkLoadEdgesPipeInTargetID` — Istio cluster names with pipes (PR #153).
- `TestMutationKeyword` — table of blocked keywords + allow-listed CALL prefixes.

### MCP tests

[`internal/mcp/`](../internal/mcp/) — registry tests, per-tool argument-validation tests, parity tests for the 6 consolidated mode-driven tools, integration test ([`integration_test.go`](../internal/mcp/integration_test.go)) that boots the server in-memory and exercises every tool surface.

Notable: [`tools_consolidated_parity_test.go`](../internal/mcp/tools_consolidated_parity_test.go) — locks down the arg names dispatched from each consolidated mode to the underlying narrow handler. Caught the PR #149 dispatch-mismatch bug.

### CLI tests

[`internal/cli/`](../internal/cli/) — every subcommand has a smoke test that builds the cobra command, sets `SetArgs(...)`, and asserts on stdout/exit code. Uses `t.TempDir()` for state.

### Fixtures

Two checked-in fixtures:

| Fixture | Purpose |
|---|---|
| [`testdata/fixture-minimal/`](../testdata/fixture-minimal/) | 5 files (`User.java`, `UserController.java`, `models.py`, `README.md`, `expected-divergence.json`). Used by `index_test.go` + as the manual smoke target. |
| [`testdata/fixture-multi-lang/`](../testdata/fixture-multi-lang/) | Multi-service polyglot fixture (Java/Spring + TS/React + Python + Go + IaC). Used by enrich tests + perf-gate CI. Contains its own `pom.xml`, sub-`package.json`, etc. — exercises the ServiceDetector. |

**Both fixtures use `t.TempDir()` or in-test setup; no test writes outside its tempdir.** Verified across all 41 test-bearing packages.

## CI gates

| Workflow | What blocks merge |
|---|---|
| [`.github/workflows/go-ci.yml`](../.github/workflows/go-ci.yml) | `go vet`, `go test -race`, staticcheck@2025.1.1, gosec@v2.22.0, govulncheck@latest |
| [`.github/workflows/perf-gate.yml`](../.github/workflows/perf-gate.yml) | Wall ≤ 8s, nodes ≥ 40, phantom-drop ratio ≤ 50%, peak enrich RSS ≤ 300 MB on `testdata/fixture-multi-lang` |
| [`.github/workflows/security.yml`](../.github/workflows/security.yml) | OSV-Scanner, Trivy HIGH/CRITICAL, Semgrep (security-audit + owasp-top-ten + golang), Gitleaks full history, jscpd ≤ 3%, SBOM upload (non-blocking) |

`go-ci.yml` and `security.yml` are required status checks per branch protection. Scorecard is best-effort and does not block.

## Test count baselines (regression detection)

The `perf-gate.yml` baseline numbers are versioned at the workflow level. The Go test suite has no formal baseline — operator runs `go test ./... -count=1 | tail -3` to confirm "880+ passed" before declaring a release.

## Missing / risky areas (places to add tests)

| Area | Risk | Suggested tests |
|---|---|---|
| **Mutation gate** | Regex-based; adversarial inputs may bypass via comments or string literals containing keywords. | Fuzz [`MutationKeyword`](../internal/graph/mutation.go) against synthesized Cypher with comment / string smuggling. |
| **CSV bulk-load** | Field-delimiter collisions caused 3 production bugs (#150 commas, #153 pipes, #152 quoted keys). | Property fuzzing: generate random property maps with `"`, `\|`, `\t`, `\n`, `\r`, `\0`, `` and round-trip through `BulkLoadNodes` + `Cypher SELECT`. |
| **Recursive-pattern depth** | `[*1..N]` upper bound must be a literal — operator-supplied depth is `fmt.Sprintf`'d in. Currently capped at 10 in MCP `--max-depth`, but no test asserts the cap. | Test that passing `radius=99999` to `inspect_node/ego` clamps to `MaxDepth` and the resulting Cypher is well-formed. |
| **OOM regression** | Phase A/B/C OOM fix has a CI perf-gate at 300 MB on fixture-multi-lang. Real ~/projects/-scale runs at 2 GiB — no automated test. | Inference: hard to test cheaply in CI. Manual benchmark on the user's tree before each release is the current bar. |
| **Ollama integration** | Tested with a mock HTTP server (Inference — verify in [`internal/review/`](../internal/review/) tests) but a real Ollama API contract change would slip through. | Pin the Ollama OpenAPI to a known-good shape; add a contract test that runs against `ollama serve` when an env flag is set. |
| **Tree-sitter grammar version drift** | If a grammar bumps and emits different node types, detectors that pattern-match on AST node names silently break. | Snapshot tests on a few canonical source files per language, comparing emitted detector results. |
| **Cross-version Kuzu upgrade** | On-disk format may change across minor versions. Today's tests use `t.TempDir()` so they always start fresh. | Inference: hard to test until a version-bump scenario. Document the "graph is rebuildable from cache" assumption (already done in [06-data-model.md](06-data-model.md)). |

## Conventions

- Determinism is a **non-negotiable** requirement. Every detector emits its results in sorted order; the GraphBuilder snapshots deterministically. Adding a non-deterministic test (e.g. timing-dependent) is grounds for rejection.
- Tests use only stdlib. If you find yourself reaching for testify, ask whether the project actually needs it (so far the answer has been no).
- Race detector must pass on every PR. If you add goroutines, `-race` will catch races; if it doesn't, your test isn't exercising the concurrency it claims to.
- Always `-count=1` after CGO-relevant changes (Kuzu / SQLite / tree-sitter version bumps).
- Test fixtures are **content** — `testdata/fixture-minimal/README.md` and `testdata/fixture-multi-lang/services/*/README.md` are part of the fixture, not project documentation.
