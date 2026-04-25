# Test Strategy — codeiq

> **SSoT for testing policy: layers, coverage targets, flake handling, regression scope.** Owner: QA (until codeiq grows a dedicated QA hire; until then, the engineer who owns the change owns its tests). Pairs with [`engineering-standards.md`](engineering-standards.md) §1 (quality gates) and §4 (testing tiers) — this file is the operational expansion of those two sections.

If a rule here conflicts with `engineering-standards.md`, the standards file wins. This runbook is **how**; the standards file is **what**.

---

## 1. Test layers (what runs where)

codeiq runs three test tiers. Every change picks the lightest tier that gives useful signal.

| Layer | Definition | Where | Runs in CI | Wall-clock target |
|---|---|---|---|---|
| **Unit** | Pure logic; no I/O; no Spring context; no Neo4j; no filesystem beyond `@TempDir`. The bulk of tests. | `src/test/java/.../<package>/*Test.java` (Surefire) | Every PR + push (`mvn test`) | < 10 ms / test, < 60 s suite |
| **Integration** | Real H2 cache, real Neo4j Embedded, real `@TempDir` filesystem, real ANTLR/JavaParser. Spring context allowed when needed. | `src/test/java/.../analyzer/`, `.../graph/`, `.../intelligence/`, `.../e2e/` (Failsafe — `*IT.java` or `@IntegrationTest`) | Every PR + push (`mvn verify`) | < 5 s / test, < 5 min suite |
| **E2E quality** | Full pipeline (`index → enrich → serve`) against a real cloned external repo (Spring PetClinic, etc.); endpoint responses validated against Context7-sourced ground-truth JSON. | `E2EQualityTest`, ground-truth at `src/test/resources/e2e/ground-truth-*.json` | On demand + nightly cron | < 10 min / repo |

**Discriminator:** if a test starts an `ApplicationContext`, touches Neo4j, or reads the filesystem outside `@TempDir`, it is integration, not unit. Move it to `src/test/java/.../analyzer/` or `.../e2e/`. Keep `src/test/java/.../detector/` unit-only — detectors are stateless beans, their tests should never need Spring.

**Spring profile rule:** any `@SpringBootTest` MUST have `@ActiveProfiles("test")` so Neo4j embedded does not start during unit-context runs. This is a real-bug-causing gotcha — see `CLAUDE.md` § "Gotchas".

---

## 2. Coverage targets

| Scope | Target | Floor (build fails below) | Tool |
|---|---|---|---|
| Project-wide line | ≥ 90% | **85%** (JaCoCo BUNDLE LINE COVEREDRATIO; `pom.xml` rule) | `jacoco-maven-plugin` |
| New code (per PR) | ≥ 90% | **80%** | SonarCloud "new code" gate (active per `engineering-standards.md` §1) |
| Critical paths (auth, path-traversal, max-bytes, deserialization, anything in `api/` security checks) | 100% line + branch | 100% — no merge with gaps | JaCoCo + manual review |
| Detectors | Positive match + negative match + determinism (run twice, assert identical output) | All three present | Test convention (per `CLAUDE.md`) |

Coverage exclusions live in `pom.xml` `<jacoco>` config. Adding to that list requires TechLead sign-off and a one-line justification per entry. Generated ANTLR sources, the Spring `application/` main, and pure data records are pre-excluded.

**Coverage is a signal, not a target.** 100% coverage with assertion-free tests is worse than 60% with meaningful ones. Don't chase the number; chase the failure modes the code can actually have.

---

## 3. What every new detector test must include

Per `CLAUDE.md` § "Adding a New Detector":

1. **Positive match** — at least one synthetic input that should produce the expected node/edge.
2. **Negative match** — an input that *looks* close but should NOT match (regression guard against the framework-false-positive class — e.g., generic `router.get` patterns wrongly attributed to Quarkus).
3. **Determinism** — run `detect()` twice on the same input, assert byte-identical `DetectorResult`. This catches `Set` iteration leaks, mutable static state, and race conditions in shared helpers.

Discriminator-guard detectors (Quarkus, Fastify, Micronaut, NestJS, etc.) need at minimum **two** negative cases: (a) framework-not-imported, (b) different-framework-imported.

---

## 4. Flake policy — flaky test = broken test

A flaky test is broken. Same PR resolution; do not merge code that makes flake worse.

| State | Resolution |
|---|---|
| Flake reproduced locally | Fix the timing / order assumption, re-run 50× before declaring solved |
| Flake in CI only, can't reproduce | Add deterministic seeding, isolate from shared state, retry **once** to gather a second log; if still flaky, quarantine |
| Quarantine | `@Disabled("flaky — RAN-XXX")` with a tracked Paperclip issue; never silently deleted, never `@RepeatedTest`-looped past in CI |
| Three quarantines on the same suite | The suite is unsound; rewrite or delete. Don't accumulate `@Disabled` debt |

**Never** retry-loop in CI to mask a flake. That hides real concurrency / timing bugs (and codeiq runs heavily on virtual threads — exactly the place those bugs hide).

---

## 5. Regression suite

The regression suite is **everything** in Surefire + Failsafe. There is no separate "regression" phase — `mvn verify` is it. Total wall-clock target: < 7 min on CI's `ubuntu-latest`.

E2E quality tests are **not** part of the per-PR gate (too slow + require external repo clone). They run nightly and on-demand via `E2E_PETCLINIC_DIR=... mvn -Dtest=E2EQualityTest test`. A red E2E nightly opens a `RAN-*` issue with the diff against ground truth attached.

Ground-truth files (`src/test/resources/e2e/ground-truth-*.json`) are versioned. Updating one requires either: (a) the underlying upstream repo legitimately changed (link the upstream PR), or (b) a bug fix landed and the prior ground truth was wrong (link the codeiq PR).

---

## 6. What we do NOT test (out of scope here)

- **No live network.** Tests must work behind a corporate firewall / air-gapped (per `~/.claude/rules/build.md`). Anything that needs `https://` goes in `E2EQualityTest` with explicit external-repo opt-in via env var.
- **No browser / E2E UI.** The React UI is bundled in the JAR; smoke-testing the SPA boot is done in `serve`-command CLI smoke (per `first-time-setup.md` §3), not in the test suite.
- **No load / stress testing.** Performance work uses `pom.xml` JMH harnesses where they exist. Microbenchmarks are not regression tests; they are decision-support for `~/.claude/rules/performance.md`.

---

## 7. References

- [`engineering-standards.md`](engineering-standards.md) §1 (quality gates), §4 (test tiers), §6 (style)
- [`first-time-setup.md`](first-time-setup.md) §3 (build, test, run loops)
- [`/CLAUDE.md`](../../CLAUDE.md) "Testing" + "Adding a New Detector"
- `pom.xml` — `jacoco-maven-plugin` rules, `surefire`/`failsafe` config
