# 10 — Known risks and TODOs

## TODO / FIXME / HACK markers in code

**Zero.** A repo-wide grep for `TODO`, `FIXME`, `HACK`, `XXX` in `internal/`, `cmd/`, and `parity/` returns **0 occurrences**.

This is the result of a deliberate "no TODOs in main" discipline — incomplete work either ships behind a clear interface or is captured in plan files (now wiped). The flip side: there's no in-code roadmap for "things known to need work". The list below substitutes for that, drawn from comments, deleted plan files, and observed bugs.

## Risks by surface

### Index / enrich pipeline

| Risk | Severity | Why | Where |
|---|---|---|---|
| **Detector registration choke point** | High | One forgotten blank import in [`detectors_register.go`](../internal/cli/detectors_register.go) ships an empty registry for that language family. Phase 4 caught the bug: 15 language families silently produced 0 nodes. | [`internal/cli/detectors_register.go`](../internal/cli/detectors_register.go) — 18 leaf packages today. |
| **Phantom edge drop** | Medium | Edges whose endpoint isn't in the node set get dropped at `Snapshot()`. Detectors must emit anchor nodes via [`base.EnsureFileAnchor`](../internal/detector/base/) / `EnsureExternalAnchor` for imports to survive. New detectors authored without this pattern silently lose edges. | [`internal/analyzer/graph_builder.go`](../internal/analyzer/graph_builder.go), [`internal/detector/base/imports_helpers.go`](../internal/detector/base/) |
| **CSV field-delimiter collisions** | Medium (mitigated) | Three production bugs in series: commas in JSON properties (#150), Istio cluster names with `\|` (#153), TOML quoted keys (#152). Current state: `DELIM='\|', QUOTE='"', ESCAPE='"'` with RFC-4180 round-trip + per-field tests. Any new structured-output channel must re-verify quoting end-to-end. | [`internal/graph/bulk.go`](../internal/graph/bulk.go) |
| **Service-ID PK collisions** | Mitigated | Two modules sharing a folder name used to emit `service:<name>` twice → Kuzu COPY aborts. Now path-qualified `service:<dir>:<name>` (#151). Any future "container" node type (e.g. workspace, layer) needs the same discipline. | [`internal/analyzer/service_detector.go:168`](../internal/analyzer/service_detector.go) |
| **OOM at scale** | Mitigated | Pre-v0.4.0: enrich OOM-killed on 49k-file inputs. Fixed via three landed PRs (#145 parse-once-per-file + bounded extractor pool + Kuzu BufferPool cap; #146 TreeCursor refactor; #147 CLI knobs). Current ceiling: ~2 GiB peak RSS at ~/projects/-scale. Larger inputs (e.g. 200k+ files) untested. | [`internal/intelligence/extractor/enricher.go`](../internal/intelligence/extractor/enricher.go), [`internal/graph/store.go`](../internal/graph/store.go) |
| **Non-deterministic detector** | High if introduced | Same input must produce byte-identical output. Map iteration without `sort.Strings` of keys, or goroutine ordering without explicit sort, is the typical bug. Every detector ships a determinism test for exactly this reason. | All 100 detectors |
| **Tree-sitter grammar drift** | Medium | Detectors pattern-match on AST node names. A grammar bump that renames nodes silently regresses fidelity. No automated regression for this. | [`internal/parser/`](../internal/parser/) |

### MCP server

| Risk | Severity | Why | Where |
|---|---|---|---|
| **Mutation gate bypass via formatting tricks** | Medium | The gate is regex over the literal query string, comment-stripped. Adversarial inputs embedding `CREATE` inside a string literal, or weird whitespace, could in principle slip through to Kuzu. Belt-and-braces is `OpenReadOnly` at the Kuzu level. | [`internal/graph/mutation.go`](../internal/graph/mutation.go) — `MutationKeyword` |
| **`run_cypher` opens the full surface** | Medium | Even read-only Cypher can exhaust resources (huge result sets, recursive patterns). `--max-results 500` + `--query-timeout 30s` cap the worst case, but a CALL-procedure on the allow-list could theoretically be slow. | [`internal/mcp/tools_graph.go`](../internal/mcp/tools_graph.go) |
| **`read_file` path traversal** | Medium | Sandboxed to indexed root via `filepath.Rel` checks (Inference — verify in [`mcp/read_file.go`](../internal/mcp/)). If the rel-check is missing or wrong, file content outside the root could leak. Worth a focused fuzz test. | [`internal/mcp/tools_graph.go`](../internal/mcp/tools_graph.go) — `toolReadFile` |
| **Consolidated-tool arg-name dispatch** | Mitigated | 7 modes used to permanently return `INVALID_INPUT` because the 6 consolidated tools passed wrong arg names to the underlying narrow handlers (#149). Now covered by parity tests. New mode additions need a parity-test entry. | [`internal/mcp/tools_consolidated_parity_test.go`](../internal/mcp/tools_consolidated_parity_test.go) |

### Build / release

| Risk | Severity | Why | Where |
|---|---|---|---|
| **Poisoned proxy cache for deleted tags** | High (latent) | `proxy.golang.org` caches every version's zip immutably. The pre-v0.4.0 tags were deleted from GitHub but their zips live on at the proxy. Anyone running `go install …@v0.3.0` gets the old, broken layout. | History; mitigated by always picking a new version on releases. |
| **goreleaser `files:` hard-fail on missing literal** | Mitigated | `files: README.md` was a literal-file match — release blew up after the doc-wipe. Fixed by switching to globs `README.md*`. | [`.goreleaser.yml`](../.goreleaser.yml) (PR #169) |
| **release-darwin race** | Mitigated | Polls for the Release created by release-go. Old 90s window timed out. Now 15 min + early-bail on upstream failure (PR #165). | [`.github/workflows/release-darwin.yml`](../.github/workflows/release-darwin.yml) |
| **Goreleaser `draft: true`** | Operational | Every release lands as a draft. Maintainer must `gh release edit --draft=false` to publish. Forgetting it means users can't download. | [`.goreleaser.yml`](../.goreleaser.yml) |
| **No automatic CHANGELOG cut** | Operational | Each release tag should be preceded by a CHANGELOG PR that cuts `[Unreleased]` → `[vX.Y.Z]`. Today this is manual. Easy to forget. | [`docs/09-build-deploy-release.md`](09-build-deploy-release.md) |

### Supply chain / security

| Risk | Severity | Why | Where |
|---|---|---|---|
| **CGO + glibc lock-in** | Medium | The binary statically links Kuzu/SQLite/tree-sitter C++ code against glibc. Won't run on Alpine / musl without rebuilding upstream Kuzu. | All CGO deps. |
| **No SLSA build attestation visibility on the consumer side** | Low | `actions/attest-build-provenance` posts to GitHub's attestations store, but the README doesn't tell users how to verify it (`gh attestation verify codeiq.tar.gz --owner RandomCodeSpace`). | [`.github/workflows/release-go.yml`](../.github/workflows/release-go.yml) |
| **Long-running CGO third-party code** | Medium | Kuzu is C++. A use-after-free or heap-overflow in Kuzu's query engine becomes our problem. Mitigated by pinning to a stable Kuzu version + cosign-verified release archives. No automated fuzzing today. | [`go.mod`](../go.mod) |
| **Detector regex DoS** | Low | Per-file regex execution under a worker pool. A pathological input (e.g. very long backtracking-prone regex match) could stall a worker. Go's RE2 doesn't backtrack, so the risk is bounded — but a 1 GB single-line file is still 1 GB of byte scans. The default 2 MB tree-sitter buffer + `DefaultExcludeDirs` filter limit exposure. | All 100 regex detectors |

### Code quality / debt

| Item | Where | Note |
|---|---|---|
| **`label_lower` / `fqn_lower` columns** | [`internal/graph/schema.go`](../internal/graph/schema.go) | Redundant with FTS indexes since Kuzu 0.11. Kept for CONTAINS fallback. Removing would require a schema migration + dropping the fallback path. |
| **Hand-rolled structured parsers for YAML/JSON/TOML/INI/properties** | [`internal/parser/structured.go`](../internal/parser/structured.go) | Sufficient for detector use today. Misses TOML edge cases (array-of-tables, inline tables, multi-line strings — documented in code). Replace with a real TOML library if a bug surfaces. |
| **No central error type** | Across packages | Errors are stringly-typed. Operator scripting around stderr is fragile. |
| **No structured logging** | Across packages | Plain `fmt.Fprintln(os.Stderr, ...)` at `-v` levels. Fine for a CLI; would be limiting in a long-running service (and there isn't one, so it's fine). |
| **`config <action>` subcommand not implemented** | [`internal/cli/`](../internal/cli/) — no `config.go` | Older docs referenced this. Today only the root `--config` flag exists. |
| **Java detector `.java` test fixtures** | [`testdata/fixture-minimal/`](../testdata/fixture-minimal/) | The fixture has `User.java` + `UserController.java` to exercise the Java detector. They are content, not project Java code — easy to confuse with stale Java-era artifacts. |
| **`parity/` harness mostly idle** | [`parity/`](../parity/) | Build-tag `parity`. Used during the Java → Go port; now sits behind the tag waiting for someone to wake it up or delete it. |

### Incomplete features

| Feature | State |
|---|---|
| `codeiq config <action>` | Mentioned in older docs; never implemented. Root `--config` flag works. |
| Incremental enrich | Not implemented. Today `enrich` does a full re-bulk-load of every cached row. |
| Multi-host distributed enrich | Not implemented; not currently a need. |
| Real-time / watch mode | Not implemented. Each run is operator-initiated. |
| Kuzu version-upgrade path | Manual `rm -rf .codeiq/graph/` + re-enrich. No tooling. |

### Security-sensitive areas

| Surface | Why sensitive |
|---|---|
| [`internal/graph/mutation.go`](../internal/graph/mutation.go) — `MutationKeyword` | Last-line gate for the `run_cypher` MCP tool. A bypass = arbitrary writes against the read-only store. |
| [`internal/mcp/tools_graph.go`](../internal/mcp/tools_graph.go) — `toolReadFile` | Path sandboxing. A bypass = arbitrary local file disclosure. |
| [`internal/review/`](../internal/review/) | Outbound HTTP to Ollama. Only network-touching surface in the binary. SSRF / injection in `model` / `messages` would land in the LLM, not in our process — but a malicious Ollama endpoint could feed back crafted responses. |
| Release pipeline (workflows + goreleaser) | Cosign keyless signing depends on GitHub OIDC. A workflow-modify PR that changes the signing identity would produce an unverifiable signature — branch protection on `main` is the mitigation. |
| GitHub Actions third-party actions | All third-party actions are pinned by SHA (per Scorecard guidance). Updating one requires re-pinning. |

## Migration risks

| Bump | Risk |
|---|---|
| Kuzu major / minor | On-disk format may break. The graph is rebuildable from cache; the cache is SQLite (stable). Plan: enrich-rebuild on every Kuzu bump. Test by running enrich against `testdata/fixture-multi-lang` post-bump. |
| Go toolchain | Module pins `go 1.25.0` (clamped by `modelcontextprotocol/go-sdk`). Bumping past 1.26 requires waiting for the SDK to update. |
| tree-sitter grammars | A grammar bump that renames AST nodes silently regresses detectors that pattern-match on those node names. No automated regression. |
| `mattn/go-sqlite3` | Stable; minor bumps have shipped without issue (1.14.22 → 1.14.44 in PR #157). |
| MCP SDK | Pins the Go module minimum. A major bump may rework the `Server.AddTool` / `Tool` signature; the wrapper in [`internal/mcp/tool.go`](../internal/mcp/tool.go) is the absorption layer. |

## Recommended next sweeps

1. **Fuzz the mutation gate** with adversarial Cypher (comment smuggling, string literals containing blocked keywords, unicode whitespace tricks).
2. **Property fuzz the bulk-load CSV writer** with random byte sequences in node/edge properties.
3. **Snapshot tests for tree-sitter grammar output** on canonical files per language; pin grammar versions in `go.mod` rather than the wildcard tag patterns currently in use.
4. **`gh attestation verify` documentation** for release consumers.
5. **Consider extracting an `extra_files` block in `.goreleaser.yml`** with `match: optional` rather than the `*`-glob hack for README.md.
6. **Delete `parity/` or revive it** — Phase 5 of the Java port is over; the harness is in limbo.
