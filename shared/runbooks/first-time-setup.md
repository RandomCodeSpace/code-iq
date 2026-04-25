# First-time Setup — codeiq

> Get a fresh contributor (human or agent) from a clean machine to a green local build, signed-commit-ready, in one pass. Pairs with [`release.md`](release.md), [`rollback.md`](rollback.md), and [`engineering-standards.md`](engineering-standards.md).

---

## 0. What you'll have at the end

- Repo cloned at `~/projects/codeiq` with the offline build path verified.
- Java 25 + Maven 3.9 on PATH.
- A signed-commit configuration (ssh) bound to your `id_ed25519` key.
- `mvn verify` exits 0 on `main`.
- `codeiq` CLI runs end-to-end against this repo as a smoke target.

If any step fails, stop and follow the troubleshooting note inline — do not "fix forward" against a partially-set-up machine.

---

## 1. System prerequisites

| Tool | Min version | Notes |
|---|---|---|
| Java | 25 | Required by `pom.xml` `maven-enforcer-plugin` (`[25,)`). Use Adoptium / Temurin. |
| Maven | 3.9.x | Newer minor versions are fine; do not use 4.x snapshots. |
| Git | 2.34+ | Required for ssh-format commit signing (`gpg.format ssh`). |
| OpenSSH | 8.0+ | Bundles `ssh-keygen -Y verify` used by `commit.gpgsign=true`. |
| Node.js | 20.x LTS | Only needed for the bundled React UI — `mvn package` shells out to it via the frontend Maven plugin. |
| `gh` CLI | 2.40+ | For PR/release plumbing. |

Verify in one shot:

```bash
java --version | head -1            # openjdk 25 ...
mvn -v | head -1                    # Apache Maven 3.9.x
git --version                       # git version 2.x
ssh -V                              # OpenSSH_8.x or newer
node --version                      # v20.x
gh --version | head -1              # gh version 2.x
```

---

## 2. Clone and configure

```bash
git clone git@github.com:RandomCodeSpace/codeiq.git ~/projects/codeiq
cd ~/projects/codeiq
```

Apply the repo-local signed-commit config (this is what RAN-46 AC #2 codifies):

```bash
./scripts/setup-git-signed.sh
```

That script is idempotent and is the single SSoT for the per-repo `git config --local` block. It writes `user.name`, `user.email`, `user.signingkey`, `gpg.format=ssh`, `commit.gpgsign=true`, `tag.gpgsign=true` and verifies your public key resolves on disk. If you do not have an `id_ed25519` keypair, generate one (`ssh-keygen -t ed25519 -C "you@example.com"`) and upload the **public** key to your GitHub account under `Settings → SSH and GPG keys → New SSH key → Key type: Signing Key` before re-running.

Sanity-check the config:

```bash
git config --local --get user.signingkey   # should print a path ending in .pub
git config --local --get commit.gpgsign    # should print "true"

# Produce a throwaway signed commit object (no refs touched) and verify it.
sig_commit=$(echo "verify-signing" | git commit-tree HEAD^{tree} -S)
git verify-commit "$sig_commit"             # expect "Good ... signature"
git log -1 --pretty=%G? "$sig_commit"       # expect: G
```

`git verify-commit` operates on a commit object id, not stdin — capturing the
output of `git commit-tree -S` first and then verifying that id is the right
shape. If the verification line errors with "no principal matched", point git
at an `allowed_signers` file: see `scripts/setup-git-signed.sh` output for the
canonical template.

---

## 3. Build, test, run

The standard offline path is:

```bash
mvn -B -ntp -DskipTests=false clean verify
```

This runs the full pipeline: unit tests, integration tests, jacoco coverage gate (≥85%), SpotBugs, OWASP Dependency-Check, and the executable CLI JAR build. Expect ~2-3 min on a warm cache.

For a faster inner loop while iterating:

```bash
mvn -B -ntp test \
  -Dspotbugs.skip=true -Ddependency-check.skip=true     # unit tests only (Surefire), no static analysis / CVE plugins
mvn -B -ntp -Dtest=SomeDetectorTest test                # single unit test class
mvn -B -ntp -DskipTests=true package                    # JAR only, no tests
mvn -B -ntp verify \
  -Dspotbugs.skip=true -Ddependency-check.skip=true     # unit + integration tests (Surefire + Failsafe), no static analysis / CVE plugins
```

The first command **does run tests** — earlier drafts incorrectly passed `-DskipTests` here, which would have skipped them. Reviewer finding cf64b44d (RAN-47, R5-3): Maven's `test` phase only runs Surefire (unit tests). This repo's integration tests are wired through Failsafe at the `integration-test` / `verify` phases — use the fourth form above when you need both unit + integration in the inner loop. Use `-Dspotbugs.skip` / `-Ddependency-check.skip` to keep things fast without dropping test coverage.

Smoke-test the CLI end-to-end against this repo:

```bash
java -jar target/code-iq-*-cli.jar version
java -jar target/code-iq-*-cli.jar index .
java -jar target/code-iq-*-cli.jar enrich .
java -jar target/code-iq-*-cli.jar serve . &  # opens http://localhost:8080
curl -fsS http://localhost:8080/api/stats > /dev/null && echo OK
kill %1
```

---

## 4. Optional: full quality gate locally

If you want to mirror the CI gate before pushing:

```bash
mvn -B -ntp clean verify                     # tests + jacoco
mvn -B -ntp spotbugs:check                   # static analysis gate
mvn -B -ntp dependency-check:check -DfailBuildOnCVSS=7  # CVE gate
```

Sonar runs only in CI (the token is not on local machines by design).

---

## 5. Branch / PR workflow

Create branches off `main`:

```bash
git fetch origin main
git switch -c <type>/<short-slug> origin/main
```

Conventional-commit subjects (`feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`). One logical change per commit. Sign every commit (the local config makes this automatic). Push and open a PR:

```bash
git push -u origin HEAD
gh pr create --fill --base main
```

Branch protection on `main` requires:
- A Codex review approval from TechLead (or delegate).
- CI green on the PR: `ci-java.yml` (build + jacoco 85% + dependency-check + Sonar), the repo-level CodeQL default-setup checks (`Analyze (java-kotlin)`, `Analyze (javascript-typescript)`, `Analyze (actions)`), Socket Security, SonarCloud Code Analysis.
- All commits in the PR signed (branch protection rejects unsigned commits — there is no separate "signed-commits" status check).
- OpenSSF Scorecard runs on push-to-`main` and a weekly cron, **not** on PRs, and is intentionally non-gating per [`engineering-standards.md`](engineering-standards.md) §1.

Force-push to `main` is disabled. Direct pushes are disabled. Squash-merge is the default and only path.

---

## 6. Where to look next

- Architecture, layout, and convention SSoT: [`/CLAUDE.md`](../../CLAUDE.md).
- Coverage / Sonar / CVE policy: [`engineering-standards.md`](engineering-standards.md).
- Releasing: [`release.md`](release.md).
- Rolling back a bad release: [`rollback.md`](rollback.md).
- Security reporting: [`/SECURITY.md`](../../SECURITY.md).

If you hit anything that looks like a runtime gap (missing tool, broken hook, weird auth), open a Paperclip issue against `type:devx` rather than working around it locally — the bootstrap is meant to be reproducible.
