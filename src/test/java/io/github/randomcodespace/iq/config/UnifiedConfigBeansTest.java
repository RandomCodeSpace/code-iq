package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigLoadException;
import io.github.randomcodespace.iq.config.unified.ConfigResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Task 11 wiring: the Spring context exposes a {@link CodeIqUnifiedConfig}
 * bean that is the single source of truth, and the legacy {@link CodeIqConfig} bean
 * is produced by adapting the unified bean.
 *
 * <p>Uses {@link ApplicationContextRunner} to spin up a minimal context containing
 * only {@link UnifiedConfigBeans} + {@link ProjectConfigLoader}, rather than booting
 * the full application via {@code @SpringBootTest}. Sub-second per test vs.
 * multi-second full-context startup, same correctness guarantee for this surface.
 *
 * <p>The "defaults path" assertions here run with no {@code codeiq.yml} in cwd,
 * so values must match {@link io.github.randomcodespace.iq.config.unified.ConfigDefaults}
 * — which in turn matches the values that were historically in {@code application.yml}.
 */
class UnifiedConfigBeansTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ProjectConfigLoader.class)
            .withUserConfiguration(UnifiedConfigBeans.class);

    @Test
    void contextExposesUnifiedAndLegacyBeansBothBackedBySameSource() {
        contextRunner.run(ctx -> {
            CodeIqUnifiedConfig unified = ctx.getBean(CodeIqUnifiedConfig.class);
            CodeIqConfig legacy = ctx.getBean(CodeIqConfig.class);
            assertNotNull(unified, "unified config bean must be present");
            assertNotNull(legacy, "legacy config bean must be present");
            // Same cacheDir — proves the legacy bean is adapted from unified.
            assertEquals(unified.indexing().cacheDir(), legacy.getCacheDir());
        });
    }

    @Test
    void defaultsMatchHistoricalApplicationYmlValues() {
        contextRunner.run(ctx -> {
            CodeIqConfig legacy = ctx.getBean(CodeIqConfig.class);
            // These values came from application.yml pre-Task-11; they must still
            // be what CodeIqConfig exposes now that wiring goes through ConfigDefaults.
            assertEquals(".code-iq/cache", legacy.getCacheDir());
            assertEquals(".code-iq/graph/graph.db", legacy.getGraph().getPath());
            assertEquals(10, legacy.getMaxDepth());
            assertEquals(10, legacy.getMaxRadius());
            assertEquals(500, legacy.getBatchSize());
        });
    }

    /**
     * Locks in the "startup dies with a useful stack trace" contract: a malformed
     * {@code codeiq.yml} must surface a {@link ConfigLoadException} whose message
     * names the offending file path, so a user can find and fix the broken yml.
     *
     * <p>Tested at the {@link ConfigResolver} level (not via Spring context restart)
     * because relocating CWD inside a single context run is fragile. The Spring
     * wiring in {@link UnifiedConfigBeans#codeIqUnifiedConfig} calls exactly this
     * resolver, so the guarantee propagates: Spring wraps the
     * {@code ConfigLoadException} in a {@code BeanCreationException} at startup.
     */
    @Test
    void malformedCodeiqYmlAtStartupSurfacesFileAnchoredError(@TempDir Path tempDir) throws Exception {
        Path badYml = tempDir.resolve("codeiq.yml");
        // Unclosed flow mapping -> SnakeYAML parse error.
        Files.writeString(badYml, "serving:\n  port: [not-a-scalar\n");

        ConfigLoadException ex = assertThrows(
                ConfigLoadException.class,
                () -> new ConfigResolver()
                        .projectPath(badYml)
                        .env(Map.of())
                        .resolve());

        String msg = ex.getMessage();
        assertNotNull(msg, "exception must carry a message");
        assertTrue(msg.contains(badYml.toString()),
                "error message must name the offending file path; was: " + msg);
    }

    /**
     * Closes the spec-review gap: proves a {@code codeiq.yml} overlay flows through
     * {@link ConfigResolver} + {@link UnifiedConfigAdapter} into the legacy
     * {@link CodeIqConfig} getters end-to-end.
     */
    @Test
    void codeiqYmlOverlayFlowsIntoLegacyBean(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("codeiq.yml");
        // Canonical snake_case keys -- camelCase is still accepted as a deprecated
        // alias (see UnifiedConfigLoaderTest) but this test pins the primary form.
        Files.writeString(yml, "indexing:\n  batch_size: 1234\n  max_depth: 42\n");

        // Point user-global at the same temp dir so the test doesn't pick up the
        // running user's real ~/.codeiq/config.yml.
        Path userGlobal = tempDir.resolve("user-global-absent.yml");

        CodeIqUnifiedConfig unifiedFromYml = new ConfigResolver()
                .userGlobalPath(userGlobal)
                .projectPath(yml)
                .env(Map.of())
                .resolve()
                .effective();

        CodeIqConfig legacyFromYml = UnifiedConfigAdapter.toCodeIqConfig(unifiedFromYml);
        assertEquals(1234, legacyFromYml.getBatchSize());
        assertEquals(42, legacyFromYml.getMaxDepth());
    }
}
