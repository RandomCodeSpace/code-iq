# Rollback Runbook — codeiq

> **Purpose:** restore a known-good state when a `main` push, a release, or a CI/security change has broken the project. Owner: the engineer who shipped the change; TechLead is escalation. Pair this with [`release.md`](release.md).

The rule of thumb: **revert first, root-cause second.** Users on Maven Central cannot un-install a bad version, so the cheap path is always to publish a clean follow-up rather than try to "fix forward" under pressure.

---

## 1. Decide the scope

| Symptom | Section |
|---|---|
| `main` is broken (CI red, build won't compile) | §2 — revert merge |
| A release is bad on Maven Central / GH Releases | §3 — release rollback |
| Branch protection / CI / security workflow change broke things | §4 — config rollback |
| Embedded Neo4j cache or `.codeiq/graph` corrupted on a user box | §5 — data rollback |
| A secret was exposed | §6 — secret rotation |

If you are not sure, default to §2 and revert.

---

## 2. Revert a bad merge to `main`

```bash
git fetch origin main
git switch -c revert/<short-name> origin/main

# Squash-merged PRs land as a single commit with one parent. Plain `git revert`
# applies — do NOT pass `-m`, which only makes sense for true (multi-parent)
# merge commits. Use `-m 1` only if `main` ever carries an actual merge commit
# (which the squash-merge-only branch protection should prevent).
git revert <squash-merge-sha>

git push -u origin revert/<short-name>
gh pr create --base main --fill --title "revert: <short-name>" --label "type:revert"
gh pr merge --squash --auto
```

Then watch `ci-java.yml` go green and confirm SonarCloud + downstream consumers recover.

**Never** force-push `main`; the bootstrap branch protection forbids it and history rewrites would break every consumer's `git pull`.

---

## 3. Roll back a release

You cannot delete or overwrite a Maven Central artifact (Sonatype policy). The only valid rollback is a **new patch release** that returns the behaviour to the prior version.

1. **Block downloads of the bad version** *(best-effort)*:
   - GitHub Release: `gh release edit vX.Y.Z --prerelease --notes "Marked pre-release: see vX.Y.(Z+1) for fix"`.
   - SECURITY.md / README badges: add a one-line advisory linking the follow-up version.
2. **Cut a hotfix patch release** per [`release.md`](release.md) §5. The hotfix MUST contain only the minimum changes needed to restore the prior behaviour, plus a regression test.
3. **GHSA advisory** if the bad version contains a security regression: `gh api -X POST repos/RandomCodeSpace/codeiq/security-advisories -f severity=high -f summary="..." -f description="..."`. Coordinate with the OpenSSF Scorecard advisory feed.
4. **Update CHANGELOG.md** under `[X.Y.(Z+1)]` with `### Fixed (rollback)` and link the bad-version commit + the GHSA.

Do NOT delete the bad git tag. Yanking tags after they have been seen by consumers breaks `git fetch --tags` and reproducible builds.

---

## 4. Roll back CI / branch protection / security config

These are driven by `gh api` calls (see RAN-46 inventory). They are not in version control by themselves, so rollback is by re-running the prior call.

- **Branch protection**: snapshot before any change with `gh api /repos/RandomCodeSpace/codeiq/branches/main/protection > /tmp/bp-before.json`. The GET payload is a denormalized view that GitHub's PUT endpoint does **not** accept verbatim (PUT flattens the nested objects: `enforce_admins.enabled` → bare boolean, `required_status_checks.checks[].context` strings → flat `contexts[]`, `*.url` fields are rejected). Reshape with the jq filter below before piping into PUT (Reviewer finding fd559a54, R5-5):

  ```bash
  jq '{
    required_status_checks: (
      if .required_status_checks == null then null
      else {
        strict: .required_status_checks.strict,
        contexts: ([.required_status_checks.checks[]?.context] // [])
      }
      end
    ),
    enforce_admins: (.enforce_admins.enabled // false),
    required_pull_request_reviews: (
      if .required_pull_request_reviews == null then null
      else {
        dismiss_stale_reviews:           (.required_pull_request_reviews.dismiss_stale_reviews // false),
        require_code_owner_reviews:      (.required_pull_request_reviews.require_code_owner_reviews // false),
        required_approving_review_count: (.required_pull_request_reviews.required_approving_review_count // 1)
      }
      end
    ),
    restrictions: null,
    required_linear_history:           (.required_linear_history.enabled // false),
    allow_force_pushes:                (.allow_force_pushes.enabled // false),
    allow_deletions:                   (.allow_deletions.enabled // false),
    block_creations:                   (.block_creations.enabled // false),
    required_conversation_resolution:  (.required_conversation_resolution.enabled // false),
    lock_branch:                       (.lock_branch.enabled // false),
    allow_fork_syncing:                (.allow_fork_syncing.enabled // false)
  }' /tmp/bp-before.json \
    | gh api -X PUT /repos/RandomCodeSpace/codeiq/branches/main/protection --input -
  ```

  The transform unwraps the `{enabled: bool}` envelopes, projects `checks[].context` strings out into the flat `contexts[]` PUT expects, drops `*.url` fields, and forces `restrictions: null` (apps/teams/users restrictions are out of scope for this repo). If you need to *change* a field instead of rolling back, edit the transformed payload before piping.
- **CodeQL default setup**: re-toggle via Repository Settings → Code security → Code scanning. The disabled state is the safe default.
- **Dependabot security updates**: `gh api -X PUT /repos/RandomCodeSpace/codeiq/automated-security-fixes` to enable, `-X DELETE` to disable.
- **Workflow files** (`.github/workflows/*.yml`): revert via §2 — they are version-controlled.

If a change to branch protection prevents you from merging the rollback PR (e.g., you made the wrong status check required and it never passes), `aksOps` is the only account with bypass; coordinate over the board channel before bypassing — every bypass is logged.

---

## 5. User-side data rollback (analysis cache / Neo4j store)

For users who hit a bad cache shape after upgrading:

```bash
codeiq cache clear            # drops .codeiq/cache/*.h2.db
rm -rf .codeiq/graph/graph.db # drops the enriched Neo4j store
codeiq index <repo>           # rebuild
codeiq enrich <repo>
```

The `AnalysisCache.CACHE_VERSION` constant must be bumped in any release that changes the cache schema. If a release fails to bump it, rollback per §3 and ship a follow-up that bumps it.

---

## 6. Secret rotation

If a CI secret leaked (push protection alert, secret scanning hit, or external report):

1. Rotate at the source: GitHub Settings → Secrets, regenerate at the upstream provider (Sonatype, Sonar, etc.).
2. Re-run the latest failing workflows after rotation: `gh run rerun <run-id>`.
3. Force a new release if the leaked secret signed an artifact (re-cut the release with a fresh GPG key).
4. Open a tracking issue (`type:security`, `priority:high`) with the rotation timestamp, scope of exposure, and affected runs.

---

## 7. After every rollback

- Add a one-line entry to `CHANGELOG.md` describing what was rolled back and why.
- Open a follow-up Paperclip issue tagged `type:postmortem` referencing the rolled-back PR/release. The postmortem MUST land before the next release.
- Update [`engineering-standards.md`](engineering-standards.md) if the rollback exposed a missing CI gate or test layer.
