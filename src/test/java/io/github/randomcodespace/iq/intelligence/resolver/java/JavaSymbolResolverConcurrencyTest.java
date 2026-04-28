package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 3 — virtual-thread concurrency stress for the resolver.
 *
 * <p>Production analysis fans every {@code Analyzer.run()} file across virtual
 * threads — every {@link JavaSymbolResolver#resolve} call therefore happens
 * on a different carrier with no synchronization. This test fires a lot of
 * concurrent {@code resolve()} calls against a bootstrapped resolver and
 * asserts:
 * <ul>
 *   <li>no exceptions escape (the virtual-thread fan-out is exception-clean),</li>
 *   <li>every concurrent call produces the same resolved FQN for the same
 *       source — concurrency does not corrupt resolution,</li>
 *   <li>per-call {@code JavaParser} allocation (not a shared instance) is
 *       safe — JavaParser instances aren't thread-safe and the resolver's
 *       contract is "fresh JavaParser per call".</li>
 * </ul>
 *
 * <p>Total time bound: kept loose ({@code timeout 60s}) — the goal is to
 * catch races / deadlocks, not benchmark throughput.
 */
class JavaSymbolResolverConcurrencyTest {

    private static final int N_FILES = 200;          // distinct files
    private static final int CONCURRENT_CALLS = 256; // virtual threads

    @TempDir Path repoRoot;

    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() throws IOException, ResolutionException {
        // Single-source-root layout with a target type the per-file content
        // imports + uses, plus N_FILES different "consumer" files that each
        // resolve the same target.
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/api"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(repoRoot.resolve("src/main/java/com/example/api/Target.java"),
                """
                package com.example.api;
                public class Target {}
                """);

        Path pkg = repoRoot.resolve("src/main/java/com/example/consumers");
        Files.createDirectories(pkg);
        for (int i = 0; i < N_FILES; i++) {
            Files.writeString(pkg.resolve("Consumer" + i + ".java"),
                    "package com.example.consumers;\n"
                            + "import com.example.api.Target;\n"
                            + "public class Consumer" + i + " {\n"
                            + "    private Target t;\n"
                            + "}\n");
        }

        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        resolver.bootstrap(repoRoot);
    }

    @Test
    void parallelResolveNeverThrowsAndAlwaysAgrees() throws Exception {
        // Same content resolved CONCURRENT_CALLS times across virtual threads.
        // Race signal: any divergence in resolved FQN means the resolver isn't
        // safe under concurrent fan-out.
        String relPath = "src/main/java/com/example/consumers/Consumer0.java";
        String content = Files.readString(repoRoot.resolve(relPath));
        DiscoveredFile file = new DiscoveredFile(Path.of(relPath), "java", content.length());

        Set<String> fqns = ConcurrentHashMap.newKeySet();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = IntStream.range(0, CONCURRENT_CALLS)
                    .mapToObj(i -> exec.submit(() -> {
                        Resolved r = resolver.resolve(file, content);
                        String fqn = targetFieldFqn((JavaResolved) r);
                        fqns.add(fqn);
                        return fqn;
                    }))
                    .toList();

            // Drain — assertAll will surface any task exception explicitly.
            for (Future<String> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        assertEquals(1, fqns.size(),
                "all concurrent resolutions must agree on the FQN — got " + fqns);
        assertEquals("com.example.api.Target", fqns.iterator().next());
    }

    @Test
    void parallelResolveAcrossDistinctFilesProducesPerFileResults() throws Exception {
        // Each virtual thread resolves a distinct file. Aggregated set of FQNs
        // must still be {Target}: every consumer's field resolves to the same
        // target type. Catches "thread X's resolver state leaked into thread Y"
        // class of bugs where one thread's CU bleeds into another's resolution.
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = IntStream.range(0, N_FILES)
                    .mapToObj(i -> {
                        String relPath = "src/main/java/com/example/consumers/Consumer" + i + ".java";
                        String content;
                        try {
                            content = Files.readString(repoRoot.resolve(relPath));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        DiscoveredFile file = new DiscoveredFile(Path.of(relPath), "java", content.length());
                        return exec.submit(() ->
                                targetFieldFqn((JavaResolved) resolver.resolve(file, content)));
                    })
                    .toList();

            Set<String> distinct = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(60, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toSet());

            assertEquals(Set.of("com.example.api.Target"), distinct,
                    "every Consumer's field resolves to the single Target FQN — concurrent runs agree");
        }
    }

    @Test
    void parallelResolveOnGarbageInputDoesNotThrow() throws Exception {
        // The contract is "no exceptions, no nulls" even for unparseable
        // input. JavaParser is permissive and may produce a CU; our resolver
        // returns either JavaResolved (with errors attached) or
        // EmptyResolved.INSTANCE. Both are valid; the test asserts no
        // RuntimeException leaks from the executor.
        DiscoveredFile file = new DiscoveredFile(Path.of("Bad.java"), "java", 50);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Resolved>> futures = IntStream.range(0, CONCURRENT_CALLS)
                    .mapToObj(i -> exec.submit(() -> resolver.resolve(file, "@@@@@ garbage input " + i)))
                    .toList();

            for (Future<Resolved> f : futures) {
                Resolved r = f.get(60, TimeUnit.SECONDS);
                assertNotNull(r, "resolver must never return null even under garbage input");
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolve the Consumer's "t" field's declared-type FQN via the carried solver. */
    private static String targetFieldFqn(JavaResolved r) {
        CompilationUnit cu = r.cu();
        ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
        FieldDeclaration field = cls.getFields().stream().findFirst().orElseThrow();
        Type fieldType = field.getVariable(0).getType();
        ResolvedType rt = fieldType.asClassOrInterfaceType().resolve();
        return rt.isReferenceType()
                ? rt.asReferenceType().getQualifiedName()
                : rt.describe();
    }

    @SuppressWarnings("unused") // Reserved for future test additions that need raw type access.
    private static ClassOrInterfaceType firstClassOrInterfaceType(CompilationUnit cu) {
        return cu.findFirst(ClassOrInterfaceType.class).orElseThrow();
    }
}
