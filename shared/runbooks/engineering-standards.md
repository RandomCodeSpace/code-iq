# Engineering Standards — codeiq

> **SSoT** for the cross-cutting engineering rules that every contributor — human or agent — must follow on this repo. Per-issue specifics live in the issue thread; per-component conventions live in [`/CLAUDE.md`](../../CLAUDE.md). This document is what the runbooks ([`release.md`](release.md), [`rollback.md`](rollback.md), [`first-time-setup.md`](first-time-setup.md)) reference for "what counts as done."

The rule of last resort: **`/home/dev/.claude/rules/*.md` wins.** This file does not contradict it; it specialises it for codeiq.

---

## 1. Quality gates (hard / non-negotiable)

| Gate | Threshold | Where it runs | Failure action |
|---|---|---|---|
| Unit + integration tests | All pass | `mvn verify` (CI + local) | Block merge |
| JaCoCo coverage | ≥ 85% line (project-wide, post-exclusions) | `jacoco-maven-plugin` rule in `pom.xml` | Block merge |
| SpotBugs (Java lint) | Zero High/Critical findings; `spotbugs-exclude.xml` justified per-entry | `mvn spotbugs:check` (bound to `verify`) | Block merge |
| OSV-Scanner (SCA, npm lockfile) | Zero High/Critical CVEs in npm dependency tree | `.github/workflows/security.yml` | Block merge |
| Trivy (filesystem + container scan, covers Maven + OS) | Zero High/Critical findings (`severity: HIGH,CRITICAL`, `exit-code: 1`) | `.github/workflows/security.yml` | Block merge |
| Dependabot (cross-ecosystem) | Surfaces advisories on `pom.xml` + `package-lock.json` | `.github/dependabot.yml` + GitHub Security tab | Surface; auto-PRs gated by separate review |
| Semgrep (SAST) | Zero ERROR-level findings on `p/security-audit` + `p/owasp-top-ten` + `p/java` | `.github/workflows/security.yml` | Block merge |
| Gitleaks (secret scan) | Zero findings | `.github/workflows/security.yml` | Block merge |
| jscpd (duplication) | < 3% on touched code, languages: Java + JS + TS | `.github/workflows/security.yml` | Block merge |
| SBOM (SPDX + CycloneDX) | Generated and uploaded as build artifact (`anchore/sbom-action`) | `.github/workflows/security.yml` | Surface as artifact; do **not** gate merge |
| OpenSSF Scorecard | Best-effort; no hard score floor; `Pinned-Dependencies` is a soft target | `scorecard.yml` (push to `main` + weekly) | Surface in security tab; do **not** gate merge |
| Signed commits | Every commit on `main` must verify | Branch protection + `gh api ... /commits/{sha}/check-runs` | Block merge |

Coverage exclusions are enumerated in `pom.xml` `<jacoco>` config — only generated ANTLR sources, the `application/` Spring Boot main, and pure data records are excluded. Adding to that list requires TechLead sign-off.

**Stack: OSS-CLI only.** Per RAN-46 board ruling (path B): no Sonar, no CodeQL, no NVD-direct tools (OWASP Dependency-Check). The OSS-CLI stack covers SCA (OSV-Scanner against the npm lockfile via OSV.dev = GHSA + ecosystem feeds; Trivy + Dependabot cover Maven and the rest of the filesystem — osv-scanner v2's Maven plugin depends on a `deps.dev` gRPC service that is intermittently unavailable in CI, so SCA on Java is delegated to Trivy), filesystem + container scan (Trivy), SAST (Semgrep), secret detection (Gitleaks), duplication (jscpd, `--min-tokens 200` to filter Java header boilerplate that 97 detector files share by template-method conformance), and SBOM emission (`anchore/sbom-action` SPDX + CycloneDX). Cost: $0 — entire stack is OSS-CLI in GitHub Actions, free for public OSS.

---

## 2. Code style

- **Java 25** — virtual threads, records, sealed types, pattern matching are first-class. Do not write defensive `instanceof` chains where pattern matching applies.
- **No mutable state in detectors.** `@Component` beans are singletons; per-call state lives on method locals.
- **UTF-8 everywhere** (`StandardCharsets.UTF_8` explicit, never the default).
- **Determinism is enforced.** No `Set` iteration without `TreeSet`/`stream().sorted()`. No reliance on thread completion order. Every detector must have a determinism test (run twice → identical bytes).
- **Property-key constants** — extract any string literal that appears in the same file 3+ times.
- **Exception hygiene** — never catch `Exception` to hide it; always either rethrow with context or log at the right level.

Formatting is handled by editor defaults (no auto-formatter committed). Reviewers will not nit on whitespace; they will block on convention drift.

---

## 3. Branch, commit, PR rules

- Branch off `main`. Conventional-commit subjects (`feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`, `perf:`).
- One logical change per commit. Squash-merge is the only path into `main`.
- Every commit ssh-signed (RAN-46 AC #2). Branch protection rejects unsigned commits.
- PR title is a conventional-commit subject. Body contains:
  - Linked Paperclip issue (e.g., `Closes RAN-42`).
  - "Why" in 1–2 sentences (the diff covers "what").
  - Tests / coverage notes if non-obvious.
  - Rollback note if the change is risky.
- No force-push to `main` ever. Force-push to feature branches is fine until the PR is open; once it is open, prefer additive commits so review threads stay anchored.

---

## 4. Testing tiers

| Tier | What it tests | Where it lives | Speed budget |
|---|---|---|---|
| Unit | Pure logic, no I/O | `src/test/java/.../<package>` next to the SUT | < 10 ms each |
| Integration | Real H2 / real Neo4j (Embedded) / real filesystem | `src/test/java/.../analyzer/`, `.../graph/`, `.../e2e/` | < 5 s each |
| E2E quality | Full pipeline against a real repo (Spring PetClinic, etc.) | `E2EQualityTest`, ground-truth files under `src/test/resources/e2e/` | Run on demand + nightly |

Ground rules:
- Test behaviour at the boundary; do not test private internals.
- Every detector ships with: a positive case, a discriminator-guard negative case, a determinism case.
- Flaky test = broken test. Fix in the same PR, quarantine with a tracked Paperclip issue, or delete.

---

## 5. Security

### 5.1 Tooling stack — OSS-CLI ONLY (board ruling, RAN-46 path B)

| Concern | Tool | Where |
|---|---|---|
| SCA (npm) | **OSV-Scanner** against `src/main/frontend/package-lock.json` (OSV.dev / GHSA / ecosystem feeds; **not NVD**) | `.github/workflows/security.yml` |
| SCA (Maven + OS) + filesystem + container scan | **Trivy** filesystem scan (covers `pom.xml` transitive resolution via Trivy's own DB, plus OS packages and any future container layers); Dependabot also surfaces Maven advisories via the GitHub Security tab | `.github/workflows/security.yml` + `.github/dependabot.yml` |
| SAST | **Semgrep** (`p/security-audit`, `p/owasp-top-ten`, `p/java`) | `.github/workflows/security.yml` |
| Secret scan | **Gitleaks** (full git history) | `.github/workflows/security.yml` |
| Duplication | **jscpd** (Java + JS + TS, threshold < 3%) | `.github/workflows/security.yml` |
| SBOM | **`anchore/sbom-action`** (SPDX + CycloneDX) | `.github/workflows/security.yml` |
| Java lint | **SpotBugs** (bound to `mvn verify`) | `pom.xml` |

**Not used (do not re-introduce without an explicit board reversal of the RAN-46 path B ruling):** SonarCloud / SonarQube, CodeQL (default-setup or workflow-driven), OWASP Dependency-Check (or any NVD-direct tool). Rationale: NVD has analysis-backlog and rate-limit reliability problems; OSV / GHSA cover the same ground without those issues. CodeQL is GHAS-paid for non-public repos; we standardise on Semgrep across all repos for consistency.

### 5.2 Code hygiene

- **Inputs** — every public-facing endpoint validates input at the boundary; parameterised queries only; output encoded by default.
- **Path traversal** — anything that takes a user path goes through the canonical-path check pattern used by `/api/file` (see RAN-8 fix).
- **Secrets** — never in code, config, or commit history. CI secrets are repo-level; rotation cadence is annual or on suspected exposure.
- **CVE policy** — High/Critical → block; Medium → fix if a patched version exists, else document non-exploitability with TechLead sign-off; Low → tracked in the next dependency bump cycle.
- **Vulnerability reporting** — see [`/SECURITY.md`](../../SECURITY.md). Private disclosure only.

---

## 6. Performance

- Default to streaming and bounded concurrency. No unbounded queues, buffers, or virtual-thread fan-outs.
- Every external call has a timeout and a cancellation path.
- Performance-sensitive paths (`Analyzer`, `GraphStore.bulkSave`, `LayerClassifier`) have a microbenchmark or a regression-detection test before they ship.
- "Make it correct, then make it fast" — but data-structure and query-shape decisions must be performance-aware up front (see `/home/dev/.claude/rules/performance.md`).

---

## 7. Build & distribution

- Vendor what you can; deterministic builds (`mvn -B -ntp clean verify`) are the contract.
- No public-CDN runtime fetches, no auto-update phone-home, no telemetry default-on.
- GitHub Actions are pinned by commit SHA in every workflow. Rationale: OpenSSF Scorecard `Pinned-Dependencies` and supply-chain integrity.
- Container artifacts (when added) build from a minimal/distroless base, are pushed to GHCR with provenance attestations, and are pinned by digest at consumer sites.

### 7.1 Deploy targets (RAN-46 AC #10 ruling — option a)

codeiq's deploy surface is the **Maven Central + GitHub Releases** pipeline. There is intentionally no static-CDN frontend or always-on hosted backend.

Rationale (per @CEO's RAN-46 ruling):

- codeiq ships as a single Java JAR (`code-iq-*-cli.jar`). The React UI is bundled **inside** that JAR and served via Spring Boot's static resource handler — there is no separately deployable frontend.
- The intended runtime is the developer's own machine (`codeiq index → enrich → serve`). There is no shared SaaS surface that needs a VPS — codeiq's value is scanning *your* code on *your* machine.
- The literal AC #10 wording ("frontend → static host / CDN; backend → container registry + VPS rollout") was written for a generic product template that does not match codeiq's shape; the deploy surface is the existing release pipeline instead.

The pipeline:

| Cadence | Workflow | Trigger | Artifact destination |
|---|---|---|---|
| Beta | `.github/workflows/beta-java.yml` | `workflow_dispatch` (manual) | Sonatype Central beta + GitHub pre-release |
| GA | `.github/workflows/release-java.yml` | `workflow_dispatch` (manual, with `version` input) | Sonatype Central GA + GitHub Release |

Both workflows are `workflow_dispatch`-only — there is no tag-push trigger and no automatic release on merge. A GA cut: the workflow builds a GPG-signed release commit on a detached HEAD, deploys from that exact tree, then creates and pushes a GPG-signed annotated `vX.Y.Z` tag pointing at the release commit plus a GitHub Release. Tags are an *output* of the GA workflow, not a trigger. See [`release.md`](release.md) §3 for the full sequence.

Hello-world / pipeline proof: `git tag -l 'v0.0.1-beta.*' | wc -l` is non-zero (47+ beta tags as of the AC #10 ruling) and `gh release list` shows the corresponding GitHub pre-releases. AC #10 is satisfied by the existing pipeline; no new deploy scaffold is required.

If the product later needs a hosted demo or container surface, that is a **new RAN-* issue**, not a re-open of RAN-46.

---

## 8. Documentation

- `/CLAUDE.md` is the architecture + conventions SSoT for code-touching changes.
- `/AGENTS.md` (repo root) is the entry-point for agent collaborators.
- Every runbook lives under `shared/runbooks/`. Adding a new runbook requires updating this file's References section.
- ADRs (`docs/adr/NNN-title.md`) for any decision that changes a contract: persistence, public API surface, deployment shape.

---

## 9. References

- `/CLAUDE.md` — architecture and conventions.
- `/SECURITY.md` — disclosure policy.
- `shared/runbooks/release.md`, `rollback.md`, `first-time-setup.md`.
- `/home/dev/.claude/rules/*.md` — global engineering rules (parent SSoT).
- `pom.xml` — quality-gate plugin wiring (`jacoco`, `spotbugs`, `central-publishing`).
- `.github/workflows/` — CI / release / security automations:
  - `ci-java.yml` — `mvn verify` (tests, JaCoCo 85%, SpotBugs).
  - `security.yml` — OSS-CLI security stack (OSV-Scanner, Trivy, Semgrep, Gitleaks, jscpd, SBOM).
  - `scorecard.yml` — OpenSSF Scorecard (push + weekly cron, non-gating).
  - `beta-java.yml`, `release-java.yml` — Maven Central publishing (manual `workflow_dispatch`).
