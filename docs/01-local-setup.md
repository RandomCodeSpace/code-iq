# 01 — Local setup

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| Go | **≥ 1.25.0** (toolchain pinned to 1.25.10 in `go.mod`) | Module minimum is clamped by `modelcontextprotocol/go-sdk` v1.6. CI runs 1.25.10. |
| C toolchain (`gcc` or `clang`) + `build-essential` | recent | Required for CGO. Both Kuzu (`github.com/kuzudb/go-kuzu`) and SQLite (`github.com/mattn/go-sqlite3`) link against system C/C++ libraries. |
| `git` | any modern | File discovery uses `git ls-files` first, falls back to filesystem walk. |
| `CGO_ENABLED=1` | env | Mandatory. The binary cannot be built with `CGO_ENABLED=0`. |

Optional:

| Tool | When |
|---|---|
| Ollama (local or `OLLAMA_API_KEY` for cloud) | For `codeiq review` only. |
| `cosign` v2+ | To verify release-artifact signatures. |
| `syft` | If you want to regenerate SBOMs locally. The release pipeline uses Goreleaser's bundled Syft. |

## Install

### Build from source

```bash
git clone https://github.com/RandomCodeSpace/codeiq.git
cd codeiq
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq
codeiq --version
```

### `go install` (post-v0.4.0 module layout)

```bash
CGO_ENABLED=1 go install github.com/randomcodespace/codeiq/cmd/codeiq@latest
```

Version reporting works without explicit ldflags — see [`internal/buildinfo/buildinfo.go`](../internal/buildinfo/buildinfo.go), which falls back to `runtime/debug.BuildInfo` (`Main.Version`, `Settings[vcs.revision/vcs.time/vcs.modified]`) when the goreleaser `-ldflags -X buildinfo.Version=...` path didn't run.

### Pre-built binary

See [README.md](../README.md#install).

## Run

```bash
codeiq index   /path/to/repo
codeiq enrich  /path/to/repo
codeiq stats   /path/to/repo
codeiq mcp     /path/to/repo    # stdio MCP — wire to Claude Code / Cursor
```

State lands at `<repo>/.codeiq/cache/codeiq.sqlite` and `<repo>/.codeiq/graph/codeiq.kuzu/`. Both paths are gitignored by default (see this repo's [`.gitignore`](../.gitignore)).

## Test

```bash
# Full suite (884+ tests, ~30s)
CGO_ENABLED=1 go test ./... -count=1

# With race detector
CGO_ENABLED=1 go test ./... -race -count=1

# Single package
CGO_ENABLED=1 go test ./internal/detector/jvm/java/... -count=1

# Verbose
CGO_ENABLED=1 go test ./internal/mcp/... -v
```

CI runs the race-detector path on every PR. See [`08-testing.md`](08-testing.md).

## Required services

For the **core CLI + MCP** path: **none.** The binary is self-contained; CGO embeds Kuzu + SQLite + tree-sitter.

For `codeiq review`:

| Service | How |
|---|---|
| Ollama (local) | `ollama serve` + `ollama pull llama3.1:latest` (or your model of choice). Default endpoint `http://localhost:11434`. |
| Ollama Cloud | Set `OLLAMA_API_KEY=<your-key>`; the review client switches to cloud. |

The review client only needs OpenAI-compatible chat completions — see [`internal/review/`](../internal/review/) for the exact wire format.

## Common setup issues

### "package github.com/randomcodespace/codeiq/cmd/codeiq found, but does not contain package …/cmd/codeiq" on `go install`

You're hitting a poisoned proxy cache from a previously-deleted tag (`v0.1.0`, `v0.3.0`, `v1.0.0` all existed at one point with a different module layout). Use an explicit never-poisoned version: `@v0.4.0` or later, **not** `@latest` if it resolves to one of the dead tags.

### `failed to find shared library: kuzu.so` (or libstdc++)

CGO linked but the runtime can't find the C++ stdlib. On Debian/Ubuntu: `sudo apt-get install build-essential libstdc++6`. On Alpine: this project assumes glibc — Alpine is not supported without rebuilding Kuzu.

### `go: module … needs Go ≥ 1.25.0` on `go build`

Upgrade your toolchain. The MCP SDK v1.6 requires it; `go mod tidy` will rewrite anything lower back up to 1.25.0.

### Tests pass locally but `gosec` fails in CI

`go-ci.yml` excludes `G104,G115,G202,G204,G301,G304,G306,G401,G404,G501` (security false-positives that are project-acceptable). If you add a finding outside that allow-list, you need to fix it or extend the exclusion list in [`.github/workflows/go-ci.yml`](../.github/workflows/go-ci.yml) with a justification comment.

### Enrich is slow / spends time on tree-sitter

The single biggest knob: `--copy-threads=N` and `--max-buffer-pool=BYTES` on `codeiq enrich`. Defaults are `min(4, GOMAXPROCS)` and 2 GiB respectively, picked for 16 GiB hosts. Bump on bigger hardware; lower on tighter envelopes. See [`internal/cli/enrich.go`](../internal/cli/enrich.go).

### `.codeiq/` accumulates after every run

This is on purpose — it's the cache + graph. To force a re-index, `rm -rf <repo>/.codeiq/` and re-run `index` + `enrich`. The [`.gitignore`](../.gitignore) keeps `.codeiq/` out of git.
