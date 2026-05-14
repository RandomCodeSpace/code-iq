# 09 — Build, deploy, release

## Build

### Local

```bash
CGO_ENABLED=1 go build -o codeiq ./cmd/codeiq
```

Result: ~25 MB static binary. CGO links Kuzu (libstdc++), SQLite, tree-sitter grammars.

### With version info

```bash
CGO_ENABLED=1 go build \
  -ldflags "-X 'github.com/randomcodespace/codeiq/internal/buildinfo.Version=v0.4.0' \
            -X 'github.com/randomcodespace/codeiq/internal/buildinfo.Commit=$(git rev-parse --short HEAD)' \
            -X 'github.com/randomcodespace/codeiq/internal/buildinfo.Date=$(date -u +%Y-%m-%dT%H:%M:%SZ)'" \
  -o codeiq ./cmd/codeiq
```

Or just let the BuildInfo fallback handle it ([`internal/buildinfo/buildinfo.go`](../internal/buildinfo/buildinfo.go)) — `go install …@v0.4.0` produces a binary that reads `runtime/debug.BuildInfo` and reports `v0.4.0` without any `-ldflags`.

### Cross-compile

CGO complicates cross-compile. The release pipeline uses a separate macOS runner for darwin/arm64 rather than cross-compile, and linux/arm64 uses `gcc-aarch64-linux-gnu` on the linux runner.

Local cross-compile to linux/arm64:
```bash
sudo apt-get install gcc-aarch64-linux-gnu
CGO_ENABLED=1 GOOS=linux GOARCH=arm64 CC=aarch64-linux-gnu-gcc go build ./cmd/codeiq
```

Cross-compile to darwin from linux: **not supported** without OSXCross or a macOS host (Kuzu's CGO won't link cleanly).

## Packaging

Single static-binary distribution. Release artifacts are tarballs of `codeiq` + `LICENSE` (+ `README.md` / `CHANGELOG.md` when those files are present in the repo — globbed optionally so the release pipeline survives doc-wipe states).

| Artifact | Contents |
|---|---|
| `codeiq_<version>_linux_amd64.tar.gz` | binary + LICENSE [+ README + CHANGELOG] |
| `codeiq_<version>_linux_arm64.tar.gz` | same |
| `codeiq_<version>_darwin_arm64.tar.gz` | same |
| `*.sbom.spdx.json` | Syft-generated SPDX SBOM per archive |
| `*.cosign.bundle` | Cosign keyless signature bundle per archive (darwin only) + `checksums.sha256.cosign.bundle` (linux global) |
| `checksums.sha256` | SHA-256 manifest for all tarballs |

## Container usage

**No Docker image is built or published.** The binary is the unit of distribution. There is a `.dockerignore` in the repo (Inference: leftover from the Java era; doesn't matter for the Go-only build).

If you want a container, build one locally:

```dockerfile
# Inference — not a project-supported pattern
FROM gcr.io/distroless/cc-debian12
COPY codeiq /codeiq
ENTRYPOINT ["/codeiq"]
```

Note CGO ties the binary to glibc (not musl/Alpine).

## CI/CD

### Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| [`go-ci.yml`](../.github/workflows/go-ci.yml) | push (main), PR | vet + test -race + staticcheck + gosec + govulncheck |
| [`perf-gate.yml`](../.github/workflows/perf-gate.yml) | push, PR, workflow_dispatch | index + enrich on `fixture-multi-lang`; assert wall ≤ 8s / nodes ≥ 40 / phantom-drop ≤ 50% / peak RSS ≤ 300 MB |
| [`release-go.yml`](../.github/workflows/release-go.yml) | tag `v*.*.*` push, workflow_dispatch | Goreleaser build for linux/amd64 + linux/arm64 + SBOMs + cosign keyless |
| [`release-darwin.yml`](../.github/workflows/release-darwin.yml) | tag `v*.*.*` push, workflow_dispatch | macOS runner builds darwin/arm64 + uploads to the Release created by release-go |
| [`security.yml`](../.github/workflows/security.yml) | push, PR, weekly cron | OSV-Scanner, Trivy, Semgrep, Gitleaks, jscpd, SBOM upload |
| [`scorecard.yml`](../.github/workflows/scorecard.yml) | push (main), weekly cron | OpenSSF Scorecard SARIF upload (best-effort) |

`go-ci.yml` and `security.yml` are required status checks; merging is blocked until they're green. Scorecard is informational.

### Release flow end-to-end

```
git tag v0.4.X && git push origin v0.4.X
            │
            ├──► release-go.yml (ubuntu-latest)
            │     1. checkout, setup-go 1.25.10, install gcc-aarch64-linux-gnu
            │     2. install syft + cosign
            │     3. goreleaser release --clean
            │        ├─ before.hooks: go mod download + go test ./...
            │        ├─ build linux/amd64 (CC=gcc) + linux/arm64 (CC=aarch64-linux-gnu-gcc)
            │        ├─ ldflags: -X buildinfo.{Version,Commit,Date,Dirty}
            │        ├─ archive tar.gz (files glob: LICENSE*, README.md*, CHANGELOG.md*)
            │        ├─ syft per archive → spdx-json
            │        ├─ cosign sign-blob (keyless via id-token: write) on checksums.sha256
            │        └─ create draft GitHub Release with all artifacts
            │     4. actions/attest-build-provenance@v4 on dist/*.tar.gz
            │
            └──► release-darwin.yml (macos-14)
                  1. checkout, setup-go 1.25.10
                  2. go build CGO native (darwin/arm64) with ldflags
                  3. tar.gz the archive
                  4. syft → spdx-json
                  5. cosign sign-blob --bundle archive.cosign.bundle
                  6. POLL `gh release view $TAG` for up to 15 min, early-bail
                     if release-go.yml concluded failure/cancelled/timed_out
                  7. gh release upload --clobber the darwin artifacts to the
                     same Release release-go created
                  8. actions/attest-build-provenance on the darwin tarball

After both succeed:
  - Release exists as DRAFT (per .goreleaser.yml `draft: true`)
  - Maintainer reviews + runs `gh release edit $TAG --draft=false` to publish
```

The 15-minute poll budget + early-bail check (PR #165) lets release-darwin tolerate the typical 4–8 minute Goreleaser pipeline without manual intervention.

### Cosign verification (release consumer)

```bash
cosign verify-blob \
  --bundle checksums.sha256.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  checksums.sha256
```

For the darwin tarball directly:

```bash
cosign verify-blob \
  --bundle codeiq_0.4.0_darwin_arm64.tar.gz.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-darwin.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  codeiq_0.4.0_darwin_arm64.tar.gz
```

## Deployment assumptions

codeiq is a **developer-host tool**. It runs:

- Locally as a CLI.
- Locally as a stdio MCP server (Claude Code / Cursor configs spawn it).
- Inside CI as a one-shot scanner (`codeiq index <repo>` on a checked-out branch).

It is **not** designed for:

- Long-running services on shared infrastructure.
- Multi-tenant deployment.
- Publicly-exposed HTTP endpoints. (There are none — the MCP server is stdio-only.)

Per the security policy at the time of the original SECURITY.md (deleted in #168, will be rewritten): the public-internet attack surface is zero by design.

## Rollback notes

| Scenario | Action |
|---|---|
| Bad release tag | Don't delete the tag — proxy.golang.org caches version content immutably. Cut a fresh `v0.4.X+1` with the rollback applied. |
| Bad merge on main | Standard `git revert <merge-commit>` → re-merge to main → ship next tag. |
| Cache / graph corruption on a user host | `rm -rf <repo>/.codeiq/` and re-run `index` + `enrich`. Both stores are regenerable. |
| Kuzu on-disk format change | If Kuzu bumps a minor version, the binary's embedded Kuzu can't read old `.codeiq/graph/`. Same fix: nuke + re-enrich. The cache is SQLite (stable across versions). |

## Version history (post-reset)

| Tag | Date | Notes |
|---|---|---|
| `v0.4.0` | 2026-05-14 | First release after the v0.0.x–v0.3.0 history reset. Includes OOM-fix saga + Kuzu 0.7.1 → 0.11.3 + native FTS + module hoist + 5 enrich correctness fixes. |
| `v0.4.1` | 2026-05-14 | CI/dependency hygiene. release-darwin race fix + Dependabot bumps. |

Earlier tags (`v0.0.x`, `v0.1.x`, `v0.2.x`, `v0.3.0`, `v1.0.0`) were deleted because `proxy.golang.org` permanently caches each version's zip — reusing a deleted tag would serve stale (often Python-prototype-era) content. The commits remain on `main`; only the tags and GitHub Releases are gone.

## Known release issues

- **Goreleaser `draft: true`** — every release needs a manual `gh release edit --draft=false`. Defensible default; keeps you from broadcasting a half-baked release. To change, edit [`.goreleaser.yml`](../.goreleaser.yml) release block.
- **`files: README.md` was a literal-file match** — bare filenames in goreleaser's archive `files:` block hard-fail when missing. After the doc-wipe (#168), v0.4.2 release failed for this reason. Fixed by switching to `README.md*` glob (PR #169 — open at time of writing). Confirm landed before next tag push.
- **release-darwin race against release-go** — release-darwin polls for the Release created by release-go. Old 90s poll budget timed out; new 15-min budget + early-bail (PR #165) handles the worst-case Goreleaser pipeline.
