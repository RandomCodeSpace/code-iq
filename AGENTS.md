# AGENTS.md — codeiq

> **Repo-root entry point for any agent collaborator.** This file is intentionally short and lists pointers; the canonical contents live elsewhere and are linked from here.

## What this repo is

codeiq is a CLI + read-only server that builds a deterministic code-knowledge graph over a codebase. No AI, no external APIs — pure static analysis. See [`/CLAUDE.md`](CLAUDE.md) for the architecture, package map, pipeline, conventions, and gotchas.

## Pointers, in priority order

1. **Read [`/CLAUDE.md`](CLAUDE.md) first.** It is the SSoT for architecture, build/test commands, package layout, and the long-tail of "things that bite you on this codebase."
2. **Then [`/shared/runbooks/engineering-standards.md`](shared/runbooks/engineering-standards.md).** Coverage, CVE, signed-commits, and quality-gate policy.
3. **Then the runbooks you'll actually need:**
   - [`shared/runbooks/first-time-setup.md`](shared/runbooks/first-time-setup.md) — get from clean clone to green local build.
   - [`shared/runbooks/release.md`](shared/runbooks/release.md) — how to ship; gates downstream RAN-* product work.
   - [`shared/runbooks/rollback.md`](shared/runbooks/rollback.md) — when a ship goes bad.
4. **Security**: [`/SECURITY.md`](SECURITY.md) for disclosure; private reports only.

## Hard rules for any agent doing work in this repo

- **Branch off `main`.** Never commit to `main` directly.
- **Sign every commit.** The repo-local config (`scripts/setup-git-signed.sh`) makes this automatic; do not rewrite it.
- **One logical change per commit.** Conventional-commit subjects (`feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`, `perf:`).
- **Squash-merge only.** Branch protection rejects merge commits and force-pushes to `main`.
- **Tests + jacoco gate must pass.** `mvn -B -ntp clean verify` is the contract.
- **Determinism is non-negotiable.** Same input → same output, byte-for-byte. Any new detector ships with a determinism test.
- **Read-only serving layer.** MCP and REST API on the `serve` path do not mutate. If you find yourself adding `POST /api/<verb>` that writes, stop and reconsider.
- **No secrets in code.** Repo-level GitHub Actions secrets only.

## Paperclip / RAN-* coordination

This codebase tracks work in Paperclip under the `RAN-*` prefix. When you pick up a task:

1. Checkout the issue (`POST /api/issues/{id}/checkout`) before you start.
2. Comment progress on every heartbeat — terse markdown, link the PR.
3. Branch protection requires TechLead approval; route review there.
4. Reference the issue in your commit/PR body (`Closes RAN-N`).

If the task asks for product/feature work and `shared/runbooks/release.md` is missing on `main`, **stop**: the RAN-46 bootstrap precondition has not landed yet and product work is gated on it.

## Auth escalation

If you hit something requiring GitHub App / PAT / OAuth that the runtime cannot satisfy (org admin escalation, Sonatype Central re-namespace, OpenSSF Best Practices form, etc.), do **not** improvise auth: PATCH the issue to `blocked` with the exact ask and `@`-mention the board.
