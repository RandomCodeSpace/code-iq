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
import io.github.randomcodespace.iq.model.Confidence;
import io.github.randomcodespace.iq.model.EdgeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 — SpringRestDetector migration to consume {@code ctx.resolved()}
 * and emit RESOLVED-tier MAPS_TO edges from endpoints to their {@code
 * @RequestBody} DTO classes.
 *
 * <p>Three contract tests per the plan:
 * <ol>
 *   <li><b>resolvedModeProducesResolvedMapsToEdge</b> — {@code @RequestBody
 *       UserDto} with two {@code UserDto} classes in different packages;
 *       resolution picks the imported FQN and stamps the edge RESOLVED.</li>
 *   <li><b>fallbackModeMatchesPreSpecBaseline</b> — EmptyResolved → no
 *       MAPS_TO edge from endpoint → DTO (existing pre-migration shape).</li>
 *   <li><b>mixedModeUsesResolverWhereAvailable</b> — endpoint with one
 *       resolvable DTO and one unresolvable type: only the resolvable case
 *       gets a MAPS_TO edge.</li>
 * </ol>
 */
class SpringRestDetectorResolvedTest {

    @TempDir Path repoRoot;

    private final SpringRestDetector detector = new SpringRestDetector();
    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/a"));
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/b"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");

        Files.writeString(repoRoot.resolve("src/main/java/com/example/a/UserDto.java"),
                """
                package com.example.a;
                public class UserDto {}
                """);
        Files.writeString(repoRoot.resolve("src/main/java/com/example/b/UserDto.java"),
                """
                package com.example.b;
                public class UserDto {}
                """);

        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    }

    // ── (1) Resolved mode ────────────────────────────────────────────────────

    @Test
    void resolvedModeProducesResolvedMapsToEdge() throws Exception {
        // Two UserDto classes in different packages; controller imports one.
        // With resolution, MAPS_TO target_fqn pins the imported one.
        String controllerPath = "src/main/java/com/example/UserController.java";
        Path absController = repoRoot.resolve(controllerPath);
        Files.createDirectories(absController.getParent());
        String content = """
                package com.example;
                import com.example.a.UserDto;
                public class UserController {
                    public String createUser(@RequestBody UserDto dto) {
                        return "ok";
                    }
                    @PostMapping("/users")
                    public String postUser(@RequestBody UserDto body) {
                        return "ok";
                    }
                }
                """;
        Files.writeString(absController, content);

        Resolved resolved = bootstrapAndResolve(controllerPath, content);
        assertInstanceOf(JavaResolved.class, resolved);

        DetectorContext ctx = ctxFor(controllerPath, content).withResolved(resolved);
        DetectorResult result = detector.detect(ctx);

        // Only the @PostMapping-annotated method actually creates an endpoint —
        // the un-mapped createUser is filtered out. So one MAPS_TO is expected.
        List<CodeEdge> mapsTo = mapsToEdges(result);
        assertEquals(1, mapsTo.size(),
                "exactly one @RequestBody parameter on a real endpoint → one MAPS_TO");

        CodeEdge edge = mapsTo.get(0);
        assertEquals("com.example.a.UserDto", edge.getProperties().get("target_fqn"),
                "imported package wins — not the b/ DTO");
        assertEquals("request_body", edge.getProperties().get("parameter_kind"));
        assertEquals("body", edge.getProperties().get("parameter_name"),
                "parameter name rides as metadata for downstream consumers");
        assertEquals(Confidence.RESOLVED, edge.getConfidence());
        assertEquals(detector.getName(), edge.getSource());
    }

    // ── (2) Fallback mode ────────────────────────────────────────────────────

    @Test
    void fallbackModeProducesNoMapsToEdge() throws Exception {
        // Without ctx.resolved(), the detector emits its existing endpoint
        // node + EXPOSES edge, but no MAPS_TO — that's the migration's
        // additive contract. Existing 27 SpringRestDetectorExtendedTest cases
        // already cover endpoint extraction itself.
        String controllerPath = "src/main/java/com/example/UserController.java";
        Path absController = repoRoot.resolve(controllerPath);
        Files.createDirectories(absController.getParent());
        String content = """
                package com.example;
                import com.example.a.UserDto;
                public class UserController {
                    @PostMapping("/users")
                    public String postUser(@RequestBody UserDto body) {
                        return "ok";
                    }
                }
                """;
        Files.writeString(absController, content);

        DetectorContext ctx = ctxFor(controllerPath, content).withResolved(EmptyResolved.INSTANCE);
        DetectorResult result = detector.detect(ctx);

        assertTrue(mapsToEdges(result).isEmpty(),
                "no JavaResolved → no MAPS_TO edges (additive contract)");
        // The endpoint itself still gets emitted — sanity check.
        assertFalse(result.nodes().isEmpty(),
                "endpoint detection still runs in fallback mode");
    }

    @Test
    void fallbackModeWhenContextHasNoResolvedAtAll() throws Exception {
        String controllerPath = "src/main/java/com/example/UserController.java";
        Path absController = repoRoot.resolve(controllerPath);
        Files.createDirectories(absController.getParent());
        String content = """
                package com.example;
                import com.example.a.UserDto;
                public class UserController {
                    @PostMapping("/users")
                    public String postUser(@RequestBody UserDto body) {
                        return "ok";
                    }
                }
                """;
        Files.writeString(absController, content);

        // No withResolved at all — ctx.resolved() is Optional.empty().
        DetectorContext ctx = ctxFor(controllerPath, content);
        DetectorResult result = detector.detect(ctx);
        assertTrue(mapsToEdges(result).isEmpty());
    }

    // ── (3) Mixed mode ───────────────────────────────────────────────────────

    @Test
    void mixedModeFallsBackForUnreachableType() throws Exception {
        // Two endpoints — one body type is reachable (UserDto from
        // com.example.a), the other (MysteryDto) has no source on the
        // project. Resolved one gets MAPS_TO, unreachable one doesn't.
        String controllerPath = "src/main/java/com/example/UserController.java";
        Path absController = repoRoot.resolve(controllerPath);
        Files.createDirectories(absController.getParent());
        String content = """
                package com.example;
                import com.example.a.UserDto;
                public class UserController {
                    @PostMapping("/users")
                    public String createUser(@RequestBody UserDto dto) {
                        return "ok";
                    }
                    @PostMapping("/mystery")
                    public String mystery(@RequestBody MysteryDto dto) {
                        return "ok";
                    }
                }
                """;
        Files.writeString(absController, content);

        Resolved resolved = bootstrapAndResolve(controllerPath, content);
        DetectorContext ctx = ctxFor(controllerPath, content).withResolved(resolved);
        DetectorResult result = detector.detect(ctx);

        List<CodeEdge> mapsTo = mapsToEdges(result);
        assertEquals(1, mapsTo.size(),
                "only the resolvable DTO produces a MAPS_TO edge");
        assertEquals("com.example.a.UserDto", mapsTo.get(0).getProperties().get("target_fqn"));
        assertEquals(Confidence.RESOLVED, mapsTo.get(0).getConfidence());
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

    private static List<CodeEdge> mapsToEdges(DetectorResult result) {
        return result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.MAPS_TO)
                .toList();
    }
}
