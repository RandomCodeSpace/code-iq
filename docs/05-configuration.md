# 05 — Configuration

## Resolution order

Last write wins:

1. Built-in defaults (in code)
2. `~/.codeiq/config.yml` (Inference — referenced in the root command help text; verify by reading the loader)
3. `<repo>/codeiq.yml` (Inference — same as above; CLAUDE.md historically described this resolution chain)
4. `CODEIQ_<SECTION>_<KEY>` environment variables (Inference)
5. CLI flags

> **Inference:** the YAML loader exists per the root-flag help text (`Path to codeiq.yml (default: ./codeiq.yml then ~/.codeiq/config.yml)`), but a top-level `codeiq config` subcommand for validation / explanation was never implemented (no `internal/cli/config.go`). Treat YAML config as a low-friction override knob, not a full schema-validated surface.

## Persistent root flags

Apply to every subcommand. Source: [`internal/cli/root.go`](../internal/cli/root.go).

| Flag | Default | Effect |
|---|---|---|
| `--config <path>` | `""` (auto: `./codeiq.yml` then `~/.codeiq/config.yml`) | Override the config file path. |
| `--no-color` | `false` | Disable ANSI color in output. |
| `--json` | `false` | Emit JSON where applicable. |
| `-v`, `--verbose` | `0` (count) | Verbose logging. Repeatable: `-v` / `-vv` / `-vvv`. |
| `--version` | `false` | Print version + exit (alias of `codeiq version`). |

## Per-subcommand flags (high signal)

### `codeiq index`
| Flag | Default | Effect |
|---|---|---|
| `--batch-size` | `500` | Cache write batch size. |
| `-w`, `--workers` | `2 × GOMAXPROCS` | Detector pool concurrency. |

### `codeiq enrich`
| Flag | Default | Effect |
|---|---|---|
| `--graph-dir <path>` | `<path>/.codeiq/graph/codeiq.kuzu` | Override Kuzu store location. |
| `--memprofile=<path>` | unset | Write Go heap profile (`pprof.WriteHeapProfile`). |
| `--max-buffer-pool=N` | 2 GiB | Override Kuzu `BufferPoolSize`. Bytes. |
| `--copy-threads=N` | `min(4, GOMAXPROCS)` | Override Kuzu `MaxNumThreads`. |

### `codeiq mcp`
| Flag | Default | Effect |
|---|---|---|
| `--graph-dir <path>` | `<path>/.codeiq/graph/codeiq.kuzu` | |
| `--max-results` | 500 | Cap returned rows per tool call. |
| `--max-depth` | 10 | Cap recursive-pattern depth (ego graph / blast radius / shortest path). |
| `--query-timeout` | 30s | Per-Cypher timeout. |

### `codeiq stats`
| Flag | Default | Effect |
|---|---|---|
| `--graph-dir <path>` | default location | |
| `--category` | (all) | `graph` / `languages` / `frameworks` / `infra` / `connections` / `auth` / `architecture`. |
| `--json` | inherited from root | |

### `codeiq query <sub> <node-id> [path]`
Sub: `consumers` / `producers` / `callers` / `dependencies` / `dependents`. Flag: `--graph-dir`.

### `codeiq find <sub> [path]`
Sub: `endpoints` / `guards` / `entities` / `topics` / `queues` / `services` / `databases` / `components`. Flags: `--limit` (100), `--offset` (0), `--graph-dir`.

### `codeiq cypher <query> [path]`
Flags: `--graph-dir`, `--table` (table-format output), `--max-results` (500), `--query-timeout` (30s).

### `codeiq flow <view> [path]`
Views: `overview` / `ci` / `deploy` / `runtime` / `auth`. Flags: `--format` (json / mermaid / dot / yaml), `--out <path>`, `--graph-dir`, `--query-timeout`.

### `codeiq graph [path]`
Flags: `-f, --format` (json / yaml / mermaid / dot), `--out <path>`, `--graph-dir`, `--query-timeout`.

### `codeiq topology <sub> [path]`
Sub: bare = full map; `service-detail <name>`, `blast-radius <id> --depth 5`, `bottlenecks`, `circular`, `dead`, `path <src> <tgt>`.

### `codeiq review [path]`
| Flag | Default | Effect |
|---|---|---|
| `--base` | `HEAD~1` | Diff base ref. |
| `--head` | `HEAD` | Diff head ref. |
| `--model` | provider default | Override LLM model. |
| `-o, --out <path>` | stdout | Write review to file. |
| `--format` | `markdown` | `markdown` or `json`. |
| `--focus <path>` | unset | Restrict evidence to specific paths. |

### `codeiq cache <sub>`
Sub: `info`, `list --limit --offset --json`, `inspect <hash|path>`, `clear --yes`. All support `--cache-path <path>`.

### `codeiq plugins <sub>`
Sub: `list --language <lang> --json`, `inspect <name> --json`. Source: [`internal/cli/plugins.go`](../internal/cli/plugins.go).

## Environment variables

| Variable | Effect | Inference? |
|---|---|---|
| `CGO_ENABLED` | Must be `1` to build. | Hard requirement. |
| `GOMAXPROCS` | Bounds the per-file extractor pool to `2 × GOMAXPROCS` (Phase A OOM fix). | Source: [`internal/intelligence/extractor/enricher.go`](../internal/intelligence/extractor/enricher.go). |
| `OLLAMA_API_KEY` | When set, `codeiq review` switches from local Ollama (`http://localhost:11434`) to Ollama Cloud. | Source: [`internal/review/`](../internal/review/) client. |
| `OLLAMA_HOST` | Inference: standard Ollama env (e.g. `http://my-ollama:11434`). Treated as the cloud base URL when also `OLLAMA_API_KEY`. Verify in [`internal/review/client.go`](../internal/review/client.go). | Inference |
| `CODEIQ_BULK_BATCH_SIZE` | Override the 50,000-row Kuzu COPY batch size. | Source: [`internal/graph/bulk.go`](../internal/graph/bulk.go). |
| `GH_TOKEN` / `GITHUB_TOKEN` | Used by CI (release-go.yml, release-darwin.yml) when calling `gh` CLI. Not used at runtime by the binary. | Source: workflows. |
| `OLLAMA_*` (other) | Unknown — verify in `internal/review/client.go`. | Unknown |

## Config files

| File | Tracked? | Used by | Purpose |
|---|---|---|---|
| `<repo>/codeiq.yml` | optional (user's repo) | `codeiq` runtime | Per-project override (e.g. exclude dirs, detector tuning). Inference: schema not formally documented in code. |
| `~/.codeiq/config.yml` | n/a | `codeiq` runtime | Per-user defaults. |
| `<repo>/.codeiq/cache/codeiq.sqlite` | **no** (gitignored) | runtime cache | Analysis cache. |
| `<repo>/.codeiq/graph/codeiq.kuzu/` | **no** (gitignored) | runtime graph | Kuzu store. |
| `<repo>/.codeiq/cache/embeddings.sqlite` | **no** (gitignored if present) | runtime | Inference: embedding cache (CLAUDE.md historical mention; verify in [`internal/intelligence/lexical/`](../internal/intelligence/lexical/) if relevant). |

## Feature flags

The project does not use a feature-flag system. Build-time switches are limited to:

| Switch | Mechanism |
|---|---|
| Detector registration | Blank-import gate in [`internal/cli/detectors_register.go`](../internal/cli/detectors_register.go). |
| Verbose logging | `-v` count flag on the root command. |

## Secrets

- **None required for the CLI / MCP core.** The binary makes no outbound HTTP calls during `index` / `enrich` / `mcp` / `stats` / `find` / `query` / `cypher` / `flow` / `graph` / `topology`.
- **`OLLAMA_API_KEY`** is the only runtime secret, used by `codeiq review` against Ollama Cloud.
- **GitHub OIDC** (`id-token: write` in workflows) is the only secret-equivalent in CI; mints an ephemeral Fulcio cert for cosign keyless signing. No long-lived signing key is stored anywhere.

## Safe defaults

| Concern | Default |
|---|---|
| Kuzu BufferPool | 2 GiB (was 80% of system RAM via `kuzu.DefaultSystemConfig`; capped after Phase A OOM fix) |
| Extractor pool | `2 × GOMAXPROCS` (was unbounded; capped after Phase A OOM fix) |
| MCP query timeout | 30 s |
| MCP `--max-results` | 500 |
| MCP `--max-depth` | 10 |
| Detector worker pool | `2 × GOMAXPROCS` |
| Cache batch | 500 |
| Bulk-load batch | 50,000 rows |
| Mutation gate | **on** for `OpenReadOnly` stores (CALL allow-list: `db.*`, `show_*`, `table_*`, `current_setting`, `table_info`, `query_fts_index`) |

Do not expose real secrets in any sample configs. The only sample that ever existed was [`docs/codeiq.yml.example`](../) and it was deleted in #168 — when you re-create it, ship placeholder values only.
