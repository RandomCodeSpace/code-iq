package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.ConfigDefaults;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CliStartupConfigOverrides} mutates only the intended fields
 * on a freshly-adapted {@link CodeIqConfig} and leaves every other field at
 * the built-in default. Protects the CLI startup contract: one helper per
 * override group, no collateral writes, null/blank inputs are no-ops.
 */
class CliStartupConfigOverridesTest {

    private CodeIqConfig freshConfig() {
        return UnifiedConfigAdapter.toCodeIqConfig(ConfigDefaults.builtIn());
    }

    @Test
    void applyServeOverrides_sets_rootPath_and_readOnly_only() {
        CodeIqConfig cfg = freshConfig();
        String originalCacheDir = cfg.getCacheDir();
        int originalMaxDepth = cfg.getMaxDepth();
        int originalBatchSize = cfg.getBatchSize();
        String originalGraphPath = cfg.getGraph().getPath();

        Path root = Paths.get("/tmp/some-repo").toAbsolutePath().normalize();
        CliStartupConfigOverrides.applyServeOverrides(cfg, root, true);

        assertEquals(root.toString(), cfg.getRootPath());
        assertTrue(cfg.isReadOnly());
        // no collateral mutation
        assertEquals(originalCacheDir, cfg.getCacheDir());
        assertEquals(originalMaxDepth, cfg.getMaxDepth());
        assertEquals(originalBatchSize, cfg.getBatchSize());
        assertEquals(originalGraphPath, cfg.getGraph().getPath());
    }

    @Test
    void applyServeOverrides_readOnly_false_leaves_flag_at_default() {
        CodeIqConfig cfg = freshConfig();
        Path root = Paths.get("/tmp/other-repo").toAbsolutePath().normalize();
        CliStartupConfigOverrides.applyServeOverrides(cfg, root, false);
        assertEquals(root.toString(), cfg.getRootPath());
        assertFalse(cfg.isReadOnly());
    }

    @Test
    void applyCacheDir_sets_cacheDir_only() {
        CodeIqConfig cfg = freshConfig();
        String originalRoot = cfg.getRootPath();
        String originalServiceName = cfg.getServiceName();
        boolean originalReadOnly = cfg.isReadOnly();

        CliStartupConfigOverrides.applyCacheDir(cfg, "/shared/graph");

        assertEquals("/shared/graph", cfg.getCacheDir());
        assertEquals(originalRoot, cfg.getRootPath());
        assertEquals(originalServiceName, cfg.getServiceName());
        assertEquals(originalReadOnly, cfg.isReadOnly());
    }

    @Test
    void applyCacheDir_null_or_blank_is_noop() {
        CodeIqConfig cfg = freshConfig();
        String before = cfg.getCacheDir();
        CliStartupConfigOverrides.applyCacheDir(cfg, null);
        assertEquals(before, cfg.getCacheDir());
        CliStartupConfigOverrides.applyCacheDir(cfg, "   ");
        assertEquals(before, cfg.getCacheDir());
    }

    @Test
    void applyServiceName_sets_serviceName_only() {
        CodeIqConfig cfg = freshConfig();
        String originalRoot = cfg.getRootPath();
        String originalCacheDir = cfg.getCacheDir();
        int originalMaxDepth = cfg.getMaxDepth();

        CliStartupConfigOverrides.applyServiceName(cfg, "payments-api");

        assertEquals("payments-api", cfg.getServiceName());
        assertEquals(originalRoot, cfg.getRootPath());
        assertEquals(originalCacheDir, cfg.getCacheDir());
        assertEquals(originalMaxDepth, cfg.getMaxDepth());
    }

    @Test
    void applyServiceName_null_or_blank_is_noop() {
        CodeIqConfig cfg = freshConfig();
        CliStartupConfigOverrides.applyServiceName(cfg, null);
        assertEquals(null, cfg.getServiceName()); // default is null
        CliStartupConfigOverrides.applyServiceName(cfg, "");
        assertEquals(null, cfg.getServiceName());
        CliStartupConfigOverrides.applyServiceName(cfg, "   ");
        assertEquals(null, cfg.getServiceName());
    }
}
