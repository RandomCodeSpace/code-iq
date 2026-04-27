package io.github.randomcodespace.iq.detector.jvm.java;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaResolved;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaSourceRootDiscovery;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaSymbolResolver;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 — RepositoryDetector migration to consume {@code ctx.resolved()} and
 * promote QUERIES edges from SYNTACTIC → RESOLVED with a stable FQN target.
 *
 * <p>Three contract tests per the plan (plus a generic-arg variant for clarity):
 * <ol>
 *   <li><b>resolvedModeProducesResolvedEdge</b> — JpaRepository&lt;User, Long&gt;
 *       with two {@code User} classes in different packages; the imported
 *       FQN wins on edge.target_fqn + node.entity_fqn.</li>
 *   <li><b>fallbackModeMatchesPreSpecBaseline</b> — EmptyResolved → no
 *       FQN properties; default tier (orchestrator stamps LEXICAL because
 *       the base class is AbstractRegexDetector).</li>
 *   <li><b>mixedModeUsesResolverWhereAvailable</b> — repo for an entity that
 *       has no source on the project: simple-name target, no FQN, no
 *       RESOLVED stamp.</li>
 * </ol>
 */
class RepositoryDetectorResolvedTest {

    @TempDir Path repoRoot;

    private final RepositoryDetector detector = new RepositoryDetector();
    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/a"));
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/b"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");

        Files.writeString(repoRoot.resolve("src/main/java/com/example/a/User.java"),
                """
                package com.example.a;
                public class User {}
                """);
        Files.writeString(repoRoot.resolve("src/main/java/com/example/b/User.java"),
                """
                package com.example.b;
                public class User {}
                """);

        // Spring Data interfaces are referenced lexically via the parent type
        // — we don't need their actual class on the classpath for the resolver
        // to extract the type argument. A stub interface in our source root
        // makes the resolver's reachable-type set explicit, however.
        Files.createDirectories(repoRoot.resolve("src/main/java/org/springframework/data/jpa/repository"));
        Files.writeString(repoRoot.resolve("src/main/java/org/springframework/data/jpa/repository/JpaRepository.java"),
                """
                package org.springframework.data.jpa.repository;
                public interface JpaRepository<T, ID> {}
                """);

        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    }

    // ── (1) Resolved mode ────────────────────────────────────────────────────

    @Test
    void resolvedModeProducesResolvedEdgeWithTargetFqn() throws Exception {
        String repoPath = "src/main/java/com/example/UserRepo.java";
        Path absRepo = repoRoot.resolve(repoPath);
        Files.createDirectories(absRepo.getParent());
        String content = """
                package com.example;
                import com.example.a.User;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface UserRepo extends JpaRepository<User, Long> {}
                """;
        Files.writeString(absRepo, content);

        Resolved resolved = bootstrapAndResolve(repoPath, content);
        assertInstanceOf(JavaResolved.class, resolved);

        DetectorContext ctx = ctxFor(repoPath, content).withResolved(resolved);
        DetectorResult result = detector.detect(ctx);

        // Repo node has entity_fqn.
        CodeNode repo = onlyRepoNode(result);
        assertEquals("com.example.a.User", repo.getProperties().get("entity_fqn"),
                "node carries the resolved FQN, not the b/ User");

        // QUERIES edge has target_fqn + RESOLVED.
        CodeEdge queries = onlyQueriesEdge(result);
        assertEquals("com.example.a.User", queries.getProperties().get("target_fqn"));
        assertEquals(Confidence.RESOLVED, queries.getConfidence());
        assertEquals(detector.getName(), queries.getSource());
    }

    // ── (2) Fallback mode ────────────────────────────────────────────────────

    @Test
    void fallbackModeMatchesPreSpecBaseline() throws Exception {
        String repoPath = "src/main/java/com/example/UserRepo.java";
        Path absRepo = repoRoot.resolve(repoPath);
        Files.createDirectories(absRepo.getParent());
        String content = """
                package com.example;
                import com.example.a.User;
                public interface UserRepo extends JpaRepository<User, Long> {}
                """;
        Files.writeString(absRepo, content);

        DetectorContext ctx = ctxFor(repoPath, content).withResolved(EmptyResolved.INSTANCE);
        DetectorResult result = detector.detect(ctx);

        CodeNode repo = onlyRepoNode(result);
        assertNull(repo.getProperties().get("entity_fqn"),
                "fallback must not synthesise an FQN — resolver was unavailable");
        assertEquals("User", repo.getProperties().get("entity_type"),
                "regex still extracts the simple name (existing behaviour)");

        CodeEdge queries = onlyQueriesEdge(result);
        assertNull(queries.getProperties().get("target_fqn"));
        assertNull(queries.getSource(),
                "detector leaves source null in fallback (orchestrator stamps default)");
        assertNotEquals(Confidence.RESOLVED, queries.getConfidence(),
                "without FQN, edge is not RESOLVED tier");
    }

    @Test
    void fallbackModeWhenContextHasNoResolvedAtAll() throws Exception {
        String repoPath = "src/main/java/com/example/UserRepo.java";
        Path absRepo = repoRoot.resolve(repoPath);
        Files.createDirectories(absRepo.getParent());
        String content = """
                package com.example;
                public interface UserRepo extends JpaRepository<User, Long> {}
                """;
        Files.writeString(absRepo, content);

        // No withResolved() — Optional.empty() default.
        DetectorContext ctx = ctxFor(repoPath, content);
        DetectorResult result = detector.detect(ctx);

        CodeNode repo = onlyRepoNode(result);
        assertNull(repo.getProperties().get("entity_fqn"));
        assertNotEquals(Confidence.RESOLVED, onlyQueriesEdge(result).getConfidence());
    }

    // ── (3) Mixed mode ───────────────────────────────────────────────────────

    @Test
    void mixedModeFallsBackForUnreachableEntityType() throws Exception {
        // VetRepo references Vet — no source for Vet on the project. With
        // resolution, the symbol solver fails on Vet → no FQN → fallback.
        String repoPath = "src/main/java/com/example/VetRepo.java";
        Path absRepo = repoRoot.resolve(repoPath);
        Files.createDirectories(absRepo.getParent());
        String content = """
                package com.example;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface VetRepo extends JpaRepository<Vet, Long> {}
                """;
        Files.writeString(absRepo, content);

        Resolved resolved = bootstrapAndResolve(repoPath, content);
        DetectorContext ctx = ctxFor(repoPath, content).withResolved(resolved);
        DetectorResult result = detector.detect(ctx);

        CodeNode repo = onlyRepoNode(result);
        assertNull(repo.getProperties().get("entity_fqn"),
                "Vet has no source — solver fails — no entity_fqn");
        assertEquals("Vet", repo.getProperties().get("entity_type"),
                "regex still pins the simple name");

        CodeEdge queries = onlyQueriesEdge(result);
        assertNull(queries.getProperties().get("target_fqn"));
        assertNotEquals(Confidence.RESOLVED, queries.getConfidence());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Resolved bootstrapAndResolve(String relPath, String content) throws ResolutionException {
        resolver.bootstrap(repoRoot);
        DiscoveredFile file = new DiscoveredFile(Path.of(relPath), "java", content.length());
        return resolver.resolve(file, content);
    }

    private DetectorContext ctxFor(String relPath, String content) {
        return new DetectorContext(relPath, "java", content, null, null);
    }

    private static CodeNode onlyRepoNode(DetectorResult result) {
        List<CodeNode> repos = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.REPOSITORY)
                .toList();
        assertEquals(1, repos.size(), "expected exactly one REPOSITORY node, got " + repos.size());
        return repos.get(0);
    }

    private static CodeEdge onlyQueriesEdge(DetectorResult result) {
        List<CodeEdge> queries = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.QUERIES)
                .toList();
        assertEquals(1, queries.size(), "expected exactly one QUERIES edge, got " + queries.size());
        return queries.get(0);
    }
}
