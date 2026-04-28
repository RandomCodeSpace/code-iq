# Build & Run

## Prerequisites

- **Java 25** (Temurin recommended — pinned in CI: `.github/workflows/ci-java.yml` sets `distribution: 'temurin'` and `java-version: '25'` on `actions/setup-java`).
- **Maven 3.9+** (Maven Wrapper not committed; `mvn` from system path is expected).
- **Node.js + npm** for the frontend build. The `frontend-maven-plugin` (configured in `pom.xml`) downloads its own Node automatically — you don't need a system Node unless you run `npm` directly inside `src/main/frontend/`.
- **No Docker, no Postgres, no Redis** — codeiq is offline-first. Neo4j and H2 are embedded.

## First-time setup

```bash
git clone https://github.com/RandomCodeSpace/codeiq.git
cd codeiq

# Quickest validation — skip tests, skip the security gate
mvn clean package -DskipTests -Ddependency-check.skip=true

# Resulting JAR
ls target/code-iq-*-cli.jar
```

The first `mvn verify` (the full CI gate) downloads ~1 GB of NVD data for OWASP dependency-check. Use `-Ddependency-check.skip=true` while iterating locally; CI runs the full check on every push.

Source for these steps: `pom.xml` (the `<properties>` block + plugin executions further down) and [`shared/runbooks/first-time-setup.md`](../../shared/runbooks/first-time-setup.md).

## Local development loop

There's no hot-reload story for the Java side — codeiq is a CLI/server, not a long-running dev server. The typical loop:

```bash
# Edit Java source, then
mvn test -Dtest=YourDetectorTest -Dfrontend.skip=true   # fastest single-test cycle
mvn package -DskipTests -Ddependency-check.skip=true    # repackage the JAR
java -jar target/code-iq-*-cli.jar index   /path/to/scan-target
java -jar target/code-iq-*-cli.jar enrich  /path/to/scan-target
java -jar target/code-iq-*-cli.jar serve   /path/to/scan-target
```

For the **frontend** (live HMR against a running backend):

```bash
# Terminal 1 — run the Java backend
java -jar target/code-iq-*-cli.jar serve /path/to/scan-target

# Terminal 2 — run Vite dev server (proxies /api and /mcp to localhost:8080)
cd src/main/frontend
npm install
npm run dev
```

Vite proxy config: `src/main/frontend/vite.config.ts` (`server.proxy` at the bottom of the file) — `/api` and `/mcp` go to `http://localhost:8080`.

## Test layers

- **Unit + integration (JUnit, ~236 test files):**
  ```bash
  mvn test                              # all tests
  mvn test -Dtest=SpringRestDetectorTest # one class
  mvn test -Dsurefire.useFile=false      # verbose stderr to console
  ```
  Tests live in `src/test/java/**` mirroring the source-tree package layout. **Detector tests must include positive, negative, and determinism cases** — see existing `*DetectorTest.java`.

- **E2E quality tests (Context7-grounded ground truth):**
  ```bash
  E2E_PETCLINIC_DIR=/path/to/spring-petclinic mvn test -Dtest=E2EQualityTest
  ```
  Ground-truth JSON lives under `src/test/resources/e2e/ground-truth-*.json`. Skipped automatically when the env var isn't set.

- **Frontend E2E (Playwright):**
  ```bash
  cd src/main/frontend
  npm run test:e2e            # headless
  npm run test:e2e:headed     # with browser visible
  npm run test:e2e:report     # open last report
  ```

- **CI gate:**
  ```bash
  mvn verify
  ```
  Includes everything above (`mvn test` plus `spotbugs:check` and `dependency-check:check` bound to the `verify` phase). Failing any of those breaks the build. See `pom.xml` plugin executions and `.github/workflows/ci-java.yml`.

## Build artifacts

- **What:** a single fat JAR — `target/code-iq-*-cli.jar` (Spring Boot repackaged executable JAR).
- **Bundles:** all Java deps + the React SPA built into `src/main/resources/static/` by the `frontend-maven-plugin` during `mvn package`.
- **Maven coordinates:** `io.github.randomcodespace.iq:code-iq` (see `<groupId>` / `<artifactId>` in `pom.xml`). The artifactId stays `code-iq` historically; the binary command is `codeiq`.
- **Releases:**
  - Beta: `.github/workflows/beta-java.yml` — `workflow_dispatch` only → Sonatype Central beta + GitHub pre-release.
  - GA: `.github/workflows/release-java.yml` — `workflow_dispatch` with a `version` input → builds a GPG-signed release commit on a detached HEAD, deploys to Sonatype Central, then pushes a GPG-signed annotated `vX.Y.Z` tag + GitHub Release. **No tag-push trigger; no auto-release on merge.** See [`shared/runbooks/release.md`](../../shared/runbooks/release.md).

## Deploy

There is no SaaS surface, no container image, no VPS. codeiq runs on the developer's machine. The deploy flow:

1. User adds the dep / downloads the JAR from Maven Central or GitHub Releases.
2. User runs `codeiq index → enrich → serve` against their own repo.
3. The `serve` mode binds `0.0.0.0:8080` by default — exposed only to the local machine unless the user reconfigures.

For codeiq's own release (publishing to Maven Central): see [`shared/runbooks/release.md`](../../shared/runbooks/release.md). Rollback: [`shared/runbooks/rollback.md`](../../shared/runbooks/rollback.md).

## CLI reference

20 files under `src/main/java/io/github/randomcodespace/iq/cli/` define 14 user-facing commands. Authoritative table is in [`CLAUDE.md`](../../CLAUDE.md) §"CLI Commands"; condensed here:

| Command | Purpose | Profile |
|---|---|---|
| `index <path>` | Memory-efficient batched scan → H2 cache | `indexing` |
| `enrich <path>` | Load H2 → Neo4j; run linkers, classifier, services | `indexing` |
| `serve <path>` | Read-only REST + MCP + UI on `http://localhost:8080` | **`serving`** |
| `analyze <path>` | Legacy in-memory all-in-one (small repos only) | `indexing` |
| `stats <path>` | 7-category statistics from Neo4j | `indexing` |
| `graph <path>` | Export graph (JSON / YAML / Mermaid / DOT) | `indexing` |
| `query <path>` | Preset relationship queries (consumers, producers, ...) | `indexing` |
| `find <what> <path>` | Preset finds (endpoints, guards, entities, topics) | `indexing` |
| `cypher <query>` | Raw Cypher against Neo4j | `indexing` |
| `topology <path>` | Service topology (blast radius, cycles, bottlenecks) | `indexing` |
| `flow <path>` | Architecture flow diagrams | `indexing` |
| `bundle <path>` | Pack graph + source snapshot into ZIP | `indexing` |
| `cache <action>` | Inspect / clear / stats H2 cache | `indexing` |
| `plugins <action>` | List / inspect detectors | `indexing` |
| `config validate` / `config explain` | Unified-config tooling | `indexing` |
| `version` | Show version info | `indexing` |

Profile selection happens in `CodeIqApplication.java`'s `main` (the `boolean isServe = "serve".equalsIgnoreCase(command)` block) — `serve` activates `serving` (web server on); everything else activates `indexing` (`WebApplicationType.NONE`).

## Build phases — what runs when

| Phase | What runs | Source |
|---|---|---|
| `generate-sources` | ANTLR codegen from `*.g4` files | `pom.xml` `antlr4-maven-plugin` |
| `process-resources` | `frontend-maven-plugin`: install Node, `npm ci`, `npm run build` → `src/main/resources/static/` | `pom.xml`, `src/main/frontend/vite.config.ts` (`build.outDir: '../resources/static'`) |
| `compile` / `test-compile` | javac for Java 25 | standard |
| `test` | Surefire — JUnit | standard |
| `verify` | `spotbugs:check`, `dependency-check:check` | `pom.xml` plugin executions; **this is the CI gate** |
| `package` | Spring Boot repackage → executable JAR with embedded SPA | `spring-boot-maven-plugin` |

## Gotchas

- **`mvn test` does NOT run the security gate.** SpotBugs and OWASP dependency-check are bound to `verify`. CI runs `mvn verify`. Locally, `mvn verify` is what actually mirrors CI.
- **OWASP NVD download is ~1 GB** and very slow on first run. `-Ddependency-check.skip=true` for fast local cycles; let CI run the full check.
- **`-Dfrontend.skip=true`** skips the frontend-maven-plugin entirely. The default `<frontend.skip>false</frontend.skip>` (in the `pom.xml` `<properties>` block) means `mvn package` always tries to build the SPA. Backend-only contributors should pass `-Dfrontend.skip=true` to avoid pulling Node.
- **Vite output path is relative-up:** `src/main/frontend/vite.config.ts` writes to `'../resources/static'` (= `src/main/resources/static/`) and uses `emptyOutDir: false` so a stale dir won't be wiped — if you see leftover assets, delete `src/main/resources/static/` manually.
- **ANTLR generated sources go under `target/generated-sources/antlr4/`** (per `antlr4-maven-plugin` defaults). Don't edit them; regenerate via `mvn generate-sources`. Modifying the `.g4` files in `src/main/antlr4/` is the supported edit point.
- **Spring Boot startup overhead is 8–16 s** for the embedded Neo4j + Spring context. Expected; not a perf bug.
- **Default index batch size is 500** (`Indexing batch tuning, see CLAUDE.md`). Larger isn't better; 500 outperformed 1000 in the tuning runs that set the default.
- **Tomcat 11.0.21 + Jackson 3.1.1 are pinned overrides** of Spring Boot 4.0.5's BOM (see `<tomcat.version>` / `<jackson.version>` in `pom.xml`'s `<properties>`). Both are security bumps. Revert when Spring Boot 4.0.6+ catches up — keep the rationale comments.
- **`@ActiveProfiles("test")` is required on every `@SpringBootTest`** to avoid Neo4j auto-startup conflicts in integration tests.
- **First-run cache version mismatch wipes `.codeiq/cache/`.** Bump `CACHE_VERSION` (constant near the top of `cache/AnalysisCache.java`) whenever you change the hash algorithm or H2 schema. Existing users will lose cache on next run; that's intentional (incorrect cache > slow cache).
- **`SECURITY.md`, `CHANGELOG.md`, `.bestpractices.json`, `LICENSE`** are part of the OpenSSF Best Practices gate (project_id 12650). Do not delete or rename without coordinating — they are referenced by `.bestpractices.json` and the Scorecard workflow.
- **CI workflow pins all third-party actions by 40-char SHA** (see `.github/workflows/scorecard.yml`, `.github/workflows/codeql.yml` if present). When adding a new action, pin by SHA — Scorecard's `Pinned-Dependencies` check will downgrade us otherwise.
