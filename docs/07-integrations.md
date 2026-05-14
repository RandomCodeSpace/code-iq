# 07 — Integrations

> codeiq is intentionally minimal on external dependencies. The CLI core (index / enrich / mcp / stats / find / query / cypher / flow / graph / topology / cache / plugins / version) has **zero runtime external dependencies**. Only `codeiq review` reaches the network, and only to a user-configured LLM endpoint.

## Runtime integrations

### 1. Ollama (LLM, opt-in)

Used **only** by `codeiq review` and the `review_changes` MCP tool. Everything else stays offline.

| Mode | Endpoint | Activation |
|---|---|---|
| Local | `http://localhost:11434` (default) | Default; no env vars. |
| Cloud | Ollama Cloud base URL | Set `OLLAMA_API_KEY=<key>`. |

API: OpenAI-compatible `/v1/chat/completions`. The client lives in [`internal/review/client.go`](../internal/review/client.go) (Inference — verify by reading; CLAUDE.md historically described this).

**Local-dev alternative:** any OpenAI-compatible endpoint should work as long as it accepts `model` + `messages` per the chat API. Untested with non-Ollama backends.

**Failure modes:**
- No service on `:11434` → connection refused, clean error.
- Model not pulled → 404 from the upstream, clean error.
- HTTP/2 SETTINGS infinite loop (Go std-lib CVE GO-2026-4918) → mitigated by toolchain pin `go 1.25.10` per [`.github/workflows/go-ci.yml`](../.github/workflows/go-ci.yml) header comment.

### 2. Git (CLI shell-out)

Used by:

- **File discovery** during `index` — `git ls-files` to enumerate tracked files. Falls back to `filepath.Walk` when not a git repo.
- **`codeiq review`** — `git diff <base>..<head>` (and reading `git rev-parse` for context).

Both are direct `exec.Command` shell-outs. No remote git operations.

**Failure modes:**
- `git` not in PATH → `index` falls through to dir-walk; `review` errors cleanly.
- Detached HEAD / shallow clone → diff may fail; depends on the operator's `--base` / `--head` refs.

### 3. Kuzu (embedded; not a network integration)

Kuzu 0.11.3 via `github.com/kuzudb/go-kuzu`. CGO links the embedded C++ engine into the binary; no separate process, no network. Storage at `<repo>/.codeiq/graph/codeiq.kuzu/`.

The FTS extension is **bundled** in 0.11.3+ (was network-installed pre-0.11.3 — the `INSTALL fts` call is a no-op when bundled).

### 4. SQLite (embedded)

`github.com/mattn/go-sqlite3` 1.14.44. Same story — CGO-linked, on-disk WAL file at `<repo>/.codeiq/cache/codeiq.sqlite`.

### 5. Tree-sitter (embedded)

`github.com/smacker/go-tree-sitter` plus per-language grammar packages. CGO. Used for AST parsing of Java / Python / TypeScript / Go inside the index pipeline.

## Build-time / CI integrations

These run only in the GitHub Actions environment, not in the user's binary.

### GitHub OIDC + Sigstore (Fulcio + Rekor) — release signing

| Step | Source |
|---|---|
| Cosign keyless signing of `checksums.sha256` | Goreleaser `signs:` stanza in [`.goreleaser.yml`](../.goreleaser.yml) |
| Cosign keyless signing of darwin tarball | [`.github/workflows/release-darwin.yml`](../.github/workflows/release-darwin.yml) `cosign sign-blob --bundle` |
| Identity | `id-token: write` workflow permission → Fulcio ephemeral cert from GitHub OIDC claim |
| Transparency log | Sigstore Rekor (automatic via cosign) |
| Verification regex | `https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*` |

No long-lived signing key exists anywhere. Each release mints a fresh ephemeral cert.

### Syft (SBOM)

| Step | Source |
|---|---|
| Linux archive SBOMs | Goreleaser `sboms:` stanza → `syft` CLI per archive → `spdx-json` |
| Darwin archive SBOM | [`.github/workflows/release-darwin.yml`](../.github/workflows/release-darwin.yml) → `syft "$ARCHIVE" --output spdx-json=...` |
| Tool source | `anchore/sbom-action/download-syft@v0.24.0` |

### Goreleaser

Config: [`.goreleaser.yml`](../.goreleaser.yml) (Goreleaser v2). Triggered by [`.github/workflows/release-go.yml`](../.github/workflows/release-go.yml). Cross-compiles linux/amd64 (native gcc) + linux/arm64 (`gcc-aarch64-linux-gnu`). Hooks: `go mod download` + `go test ./...` as pre-build gates.

### Security scanners (CI)

[`.github/workflows/security.yml`](../.github/workflows/security.yml) runs six independent jobs on every PR:

| Job | Action | Purpose |
|---|---|---|
| `osv-scanner` | `google/osv-scanner-action` | Vulnerable dependency scan against `go.mod` |
| `trivy` | `aquasecurity/trivy-action` | Filesystem scan, HIGH/CRITICAL, `ignore-unfixed: true` |
| `semgrep` | Semgrep CLI | `p/security-audit + p/owasp-top-ten + p/golang`, severity ERROR |
| `gitleaks` | `gitleaks/gitleaks-action` | Full-history secret scan, `--exit-code 1` |
| `jscpd` | `kucherenko/jscpd-action` | 3% duplication threshold on `cmd internal` |
| `sbom` | `anchore/sbom-action` | SPDX + CycloneDX upload as run artifacts |

Plus `go-ci.yml` runs `go vet`, `staticcheck@2025.1.1`, `gosec@v2.22.0`, `govulncheck@latest`.

### OpenSSF Scorecard

[`.github/workflows/scorecard.yml`](../.github/workflows/scorecard.yml) — weekly Monday cron + every push to main. Uploads SARIF to GitHub code-scanning. Best-effort; does not gate merge.

### Dependabot

[`.github/dependabot.yml`](../.github/dependabot.yml) — weekly Monday 08:00 UTC.

| Ecosystem | Path | Groups |
|---|---|---|
| `gomod` | `/` | `kuzu`, `tree-sitter`, `mcp`, `cobra-viper`, `sqlite`, `test-libs` |
| `github-actions` | `/` | `actions` (catch-all) |

## What's **not** integrated

| Tech | Decision |
|---|---|
| Kafka / RabbitMQ / Pub-Sub | codeiq detects these in scanned codebases. It does not connect to one itself. |
| Postgres / MySQL / MongoDB | Same — detected, never connected to. |
| Redis / Memcached | Same. |
| Cloud SDKs (AWS / Azure / GCP) | Detectors recognize their SDK patterns; no SDK pulled in as a dependency. |
| Telemetry (OpenTelemetry, Datadog, Prometheus, etc.) | None. The binary emits stderr logs only. |
| Auto-update | None. Operator tracks releases manually. |
| Issue trackers (Linear, Jira, GitHub) | Not used by the binary. The `gh` CLI is used in workflows only. |

## Local / dev alternatives

| Production use | Local-dev alternative |
|---|---|
| Goreleaser-built binary | `CGO_ENABLED=1 go build ./cmd/codeiq` |
| Cosign-verified release | `go install github.com/randomcodespace/codeiq/cmd/codeiq@v0.4.0` (or any tagged version ≥ v0.4.0) |
| Ollama Cloud (for `review`) | Local Ollama; just `ollama serve` and pull a model |
| Per-language tree-sitter grammars (CGO) | Cannot mock — they're embedded |
