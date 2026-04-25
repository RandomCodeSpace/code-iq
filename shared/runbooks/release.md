# Release Runbook — codeiq

> **SSoT for shipping codeiq.** Owner: TechLead (until bootstrap completes); thereafter the engineer who owns the change. This runbook is the gate referenced by the bootstrap precondition (`RAN-46`): it MUST exist on `main` before any product `RAN-*` issue can leave `backlog`.

---

## 1. Release surfaces

> **AC #10 ruling (RAN-46, @CEO).** Maven Central + GitHub Releases **is** the codeiq deploy surface. There is no separate static-CDN frontend (the React UI is bundled inside the JAR) and no always-on hosted backend (codeiq runs on the developer's machine). See [`engineering-standards.md`](engineering-standards.md) §7.1 for the full rationale.

codeiq ships in three forms. A "release" updates **all three** in lockstep:

| Surface | Artifact | Distribution |
|---|---|---|
| Library JAR | `io.github.randomcodespace.iq:code-iq` (POM packaging) | Maven Central via Sonatype Central Portal |
| Executable CLI JAR | `target/code-iq-<version>-cli.jar` | GitHub Release asset |
| Source tag | `v<version>` (annotated, GPG/OpenPGP-signed by `release-java.yml`) | Git tag pushed to `RandomCodeSpace/codeiq` |

Versioning is [SemVer](https://semver.org/). Pre-`1.0.0` releases use `0.MINOR.PATCH`; breaking changes bump MINOR.

Snapshot artifacts (`-SNAPSHOT`) are published from `main` by `beta-java.yml` to OSSRH snapshots; consumers must opt into the snapshot repo. Snapshots are **not** releases.

---

## 2. Pre-release checklist

Run BEFORE creating the tag:

1. `main` is green: `gh run list --branch main --workflow ci-java.yml --limit 1` → `success`.
2. SonarCloud Quality Gate: `gh api /repos/RandomCodeSpace/codeiq/actions/runs?branch=main --jq '.workflow_runs[0].conclusion'` and SonarCloud project page both green.
3. Coverage ≥ 85% (jacoco rule + Sonar new-code ≥ 80% — see [`engineering-standards.md`](engineering-standards.md)).
4. Dependency audit clean: `mvn -B -ntp clean verify` exits 0 (the OWASP `dependency-check:check` goal is bound to `verify` and fails the build on CVSS ≥ 7 — see `pom.xml`). Cross-check with the Dependabot security tab for any open advisories.
5. SpotBugs clean: `mvn spotbugs:check` exits 0.
6. CHANGELOG entry drafted under `[Unreleased]` and ready to promote.
7. Working copy of `main` is clean (`git status --porcelain` empty).
8. GPG release-signing secrets present in repo settings: `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (verify via `gh secret list`). The workflow signs both the release commit and the annotated tag with the imported OpenPGP key — no local SSH or GPG key is required on the maintainer's machine for the GA path (Reviewer finding fd559a54, R5-7).

---

## 3. Cut a release (canonical path)

Driven by `release-java.yml` via **manual `workflow_dispatch`** with a `version` input. The workflow does **everything**: it creates a release commit (signed) on a detached HEAD with the bumped version, deploys to Maven Central from that exact source tree, and then creates a GPG-signed annotated tag pointing at that release commit. The tag is the only persistent reference to the release commit — `main` is never directly pushed by the workflow, so branch protection stays clean.

```bash
# 1. Promote CHANGELOG on main (PR + merge per branch protection)
$EDITOR CHANGELOG.md  # move [Unreleased] → [X.Y.Z] - YYYY-MM-DD
gh pr create --fill --base main
gh pr merge --squash --auto

# 2. Trigger the release workflow with the target version
gh workflow run release-java.yml --ref main -f version=X.Y.Z

# 3. Watch it run
gh run watch $(gh run list --workflow release-java.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

`release-java.yml` then, in order:
1. Configures git identity (`github-actions[bot]`) and binds it to the imported `MAVEN_GPG_PRIVATE_KEY` for both commit and tag signing — same trust path as the published artifact.
2. Runs `mvn versions:set -DnewVersion=X.Y.Z` and creates a **GPG-signed release commit** on a detached HEAD capturing that tree.
3. Runs `mvn -P release clean deploy` from that release commit's tree (full quality gate runs along the way: jacoco 85%, SpotBugs, OWASP Dependency-Check).
4. Creates a **GPG-signed annotated tag `vX.Y.Z`** pointing at the release commit.
5. Pushes only the tag (`git push origin refs/tags/vX.Y.Z`). The release commit lives only as a tag-reachable object — no `main` update.
6. Cuts a GitHub Release from the tag and uploads `code-iq-X.Y.Z-cli.jar` with auto-generated release notes.

The tag therefore points at the **exact source** that produced the artifact (no divergence between source tag and released artifact), and is annotated and GPG-signed — verifiable with `git tag --verify vX.Y.Z` provided the maintainer's public GPG key is trusted locally.

Manual cuts on a fork or downstream consumer follow the same flow: trigger the workflow with the target version. Direct `git tag && git push origin vX.Y.Z` from a developer machine does **not** publish.

---

## 4. Post-release verification

Within 30 minutes of the release workflow finishing:

1. **Maven Central index**: `curl -fsS "https://repo.maven.apache.org/maven2/io/github/randomcodespace/iq/code-iq/X.Y.Z/code-iq-X.Y.Z.pom" | head -20`.
2. **Smoke install**: in a clean directory, `mvn dependency:get -Dartifact=io.github.randomcodespace.iq:code-iq:X.Y.Z` succeeds.
3. **CLI smoke**: download the GH Release JAR, run `java -jar code-iq-X.Y.Z-cli.jar version` and `java -jar code-iq-X.Y.Z-cli.jar analyze --help` — both exit 0.
4. **GitHub Release** is marked `Latest` and links the changelog section.
5. **codebase.repoUrl** in paperclip Project still resolves (`git ls-remote git@github.com:RandomCodeSpace/codeiq.git HEAD`).

If any of (1)–(4) fails, [`rollback.md`](rollback.md) applies.

---

## 5. Hot-fix patch release (`X.Y.Z+1`)

1. Branch from the release tag: `git switch -c hotfix/X.Y.Z+1 vX.Y.Z`.
2. Apply the minimal fix; add a regression test.
3. Open PR against `main`; merge with squash.
4. Rebase the hotfix branch onto the post-merge `main` if needed; cut the tag from `main` per §3.

Hotfixes do **not** skip the pre-release checklist.

---

## 6. Required GitHub secrets (org/repo level)

| Secret | Used by | Owner |
|---|---|---|
| `SONAR_TOKEN` | `ci-java.yml` (Sonar gate) | TechLead |
| `OSS_NEXUS_USER` / `OSS_NEXUS_PASS` | `release-java.yml` (Sonatype Central) | TechLead |
| `MAVEN_GPG_KEY_ID` / `MAVEN_GPG_PASSPHRASE` / `MAVEN_GPG_PRIVATE_KEY` | `release-java.yml` (artifact signing) | TechLead |
| `CODEQL_*` | n/a — uses `GITHUB_TOKEN` | n/a |

Rotation policy: any compromise → rotate immediately + audit recent `release-java.yml` runs. Routine rotation: annually, recorded in [`engineering-standards.md`](engineering-standards.md).

---

## 7. Auth-blocked steps (escalation path)

If a release step requires auth the runtime cannot satisfy, do **not** improvise:

- Sonatype Central Portal namespace re-claim → block on board (human OAuth).
- GitHub org admin escalation (e.g., re-enable a disabled secret) → block on `aksOps` GitHub owner.
- OpenSSF Best Practices badge updates (if the badge is required for a Release announcement) → block on board to log into bestpractices.dev.

In all cases: PATCH the relevant Paperclip issue to `blocked` with the exact ask, and `@`-mention the board.

---

## 8. References

- [`rollback.md`](rollback.md) — what to do when a release goes bad.
- [`first-time-setup.md`](first-time-setup.md) — how a new contributor builds and tests locally.
- [`engineering-standards.md`](engineering-standards.md) — coverage/Sonar/CVE policy SSoT.
- `pom.xml` — version, plugins, signing wiring.
- `.github/workflows/release-java.yml` — the actual release pipeline.
