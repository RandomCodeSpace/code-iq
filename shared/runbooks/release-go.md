# Releasing the Go binary

The Java side has its own `release.md` runbook; this one covers the Go
single-binary release that ships from Phase 5 of the port onward.

The pipeline is **tag-triggered, fully automated, and keyless-signed**:

1. Push a semver tag matching `v*.*.*`.
2. `.github/workflows/release-go.yml` cross-builds for linux/amd64,
   linux/arm64, darwin/arm64 (CGO + native kuzudb/sqlite forces
   per-target runners).
3. Goreleaser packages binaries with `LICENSE`, `README.md`,
   `CHANGELOG.md` into `codeiq_<version>_<os>_<arch>.tar.gz`.
4. Syft generates an SPDX SBOM per archive.
5. Cosign keyless-signs `checksums.sha256` via GitHub OIDC (no
   long-lived key on the runner; signature transparency entry lands in
   the public Rekor log).
6. GitHub release is created as a **draft** with the verification
   recipe embedded in the release notes header.
7. Optional Homebrew tap publish — see "Homebrew tap" below.

## Cutting a release

```bash
# From the repo root, on main, with a clean working tree:
git checkout main
git pull --ff-only

# Update CHANGELOG.md [Unreleased] → [vX.Y.Z] - YYYY-MM-DD. Commit.
$EDITOR CHANGELOG.md
git add CHANGELOG.md
git commit -m "chore(release): vX.Y.Z"

# Tag (signed) and push the tag.
git tag -s vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

Within ~5 minutes:

- `release-go` workflow finishes and creates a **draft** Release.
- Sigstore transparency log records the signature.
- (If `HOMEBREW_TAP_GITHUB_TOKEN` is configured) the `homebrew-codeiq`
  tap gets a Formula bump.

Review the draft release on GitHub — verify artifact list, checksums,
SBOM presence, release notes — then click **Publish release**.

## Verifying a downloaded artifact

End-users should verify both checksum AND signature:

```bash
# Checksum
sha256sum -c checksums.sha256

# Signature (Sigstore keyless, bundle format — no key material needed locally)
cosign verify-blob \
  --bundle checksums.sha256.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  checksums.sha256
```

A successful `cosign verify-blob` proves:

- The binary was built by the release workflow in this repo (not a
  fork, not a manually-uploaded artifact).
- The build ran on a GitHub-hosted runner under GitHub's OIDC token.
- The signature was logged to the Rekor public transparency log.

## Homebrew tap

The tap repo lives at `RandomCodeSpace/homebrew-codeiq` (separate from
the main repo; Homebrew's convention).

Setup checklist (one-time, by a repo admin):

1. Create the repo `homebrew-codeiq` under the `RandomCodeSpace` org.
2. Generate a fine-grained PAT with `Contents: write` on
   `homebrew-codeiq` only.
3. Add it to `codeiq` repo secrets as `HOMEBREW_TAP_GITHUB_TOKEN`.

After setup, every tag release updates the Formula automatically.

If the secret is **not** set, the Homebrew step in `.goreleaser.yml`
skips silently — useful for forks and for local `goreleaser release
--snapshot` dry runs.

## Local dry run

To validate `.goreleaser.yml` without cutting a release:

```bash
# Dry-run (builds + packages but doesn't publish).
goreleaser release --snapshot --clean
ls dist/
```

The `--snapshot` flag forces a fake version `<incpatch>-next` and
disables publish steps (no GitHub upload, no signing, no Homebrew).
CGO is needed locally — `CGO_ENABLED=1` is set in
`.goreleaser.yml/env`.

## Failure recovery

- **Tag points at a broken commit** — delete the tag locally and
  remotely (`git tag -d vX.Y.Z && git push --delete origin vX.Y.Z`),
  fix, retag. The draft release will be replaced on retag because
  `mode: replace` is set.
- **Signing failure (OIDC token)** — usually transient. Re-run the
  workflow. The OIDC permissions in `release-go.yml` are correct;
  GitHub occasionally has Sigstore connectivity issues.
- **Homebrew tap PR fails** — check the PAT scope and that the tap
  repo exists. The main release still publishes; only the Formula
  bump skips.

## What this does NOT do

- Does not push to package registries (npm, PyPI, Cargo) — codeiq is
  a single binary, not a library.
- Does not run a smoke test of the published artifact post-release.
  Add this once we have a canary user.
- Does not auto-bump the version. Versioning is human decision.
