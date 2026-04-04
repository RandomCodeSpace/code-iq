package io.github.randomcodespace.iq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CodeIqConfig} properties, defaults, and validation guards.
 */
class CodeIqConfigTest {

    // ------------------------------------------------------------------ defaults

    @Test
    void defaultRootPathIsDot() {
        assertEquals(".", new CodeIqConfig().getRootPath());
    }

    @Test
    void defaultCacheDirIsCodeIntelligence() {
        assertEquals(".code-intelligence", new CodeIqConfig().getCacheDir());
    }

    @Test
    void defaultMaxDepthIs10() {
        assertEquals(10, new CodeIqConfig().getMaxDepth());
    }

    @Test
    void defaultMaxRadiusIs10() {
        assertEquals(10, new CodeIqConfig().getMaxRadius());
    }

    @Test
    void defaultMaxFilesIs10000() {
        assertEquals(10_000, new CodeIqConfig().getMaxFiles());
    }

    @Test
    void defaultBatchSizeIs500() {
        assertEquals(500, new CodeIqConfig().getBatchSize());
    }

    @Test
    void defaultUiEnabledIsTrue() {
        assertTrue(new CodeIqConfig().isUiEnabled());
    }

    @Test
    void defaultMaxSnippetLinesIs50() {
        assertEquals(50, new CodeIqConfig().getMaxSnippetLines());
    }

    @Test
    void defaultGraphPathIsDotOssCodeIq() {
        assertEquals(".osscodeiq/graph.db", new CodeIqConfig().getGraph().getPath());
    }

    @Test
    void defaultServiceNameIsNull() {
        assertNull(new CodeIqConfig().getServiceName());
    }

    // ------------------------------------------------------------------ setters

    @Test
    void settersUpdateValues() {
        CodeIqConfig cfg = new CodeIqConfig();
        cfg.setRootPath("/my/repo");
        cfg.setCacheDir(".cache");
        cfg.setMaxDepth(5);
        cfg.setMaxRadius(3);
        cfg.setMaxFiles(500);
        cfg.setBatchSize(100);
        cfg.setUiEnabled(false);
        cfg.setMaxSnippetLines(20);
        cfg.setServiceName("my-service");

        assertEquals("/my/repo", cfg.getRootPath());
        assertEquals(".cache", cfg.getCacheDir());
        assertEquals(5, cfg.getMaxDepth());
        assertEquals(3, cfg.getMaxRadius());
        assertEquals(500, cfg.getMaxFiles());
        assertEquals(100, cfg.getBatchSize());
        assertFalse(cfg.isUiEnabled());
        assertEquals(20, cfg.getMaxSnippetLines());
        assertEquals("my-service", cfg.getServiceName());
    }

    // ------------------------------------------------------------------ clamping guards

    @Nested
    class MaxFilesGuard {

        @Test
        void setMaxFilesRejectsZeroAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxFiles(0);
            assertEquals(1, cfg.getMaxFiles());
        }

        @Test
        void setMaxFilesRejectsNegativeAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxFiles(-100);
            assertEquals(1, cfg.getMaxFiles());
        }

        @Test
        void setMaxFilesAllowsPositive() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxFiles(200);
            assertEquals(200, cfg.getMaxFiles());
        }
    }

    @Nested
    class BatchSizeGuard {

        @Test
        void setBatchSizeRejectsZeroAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setBatchSize(0);
            assertEquals(1, cfg.getBatchSize());
        }

        @Test
        void setBatchSizeRejectsNegativeAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setBatchSize(-50);
            assertEquals(1, cfg.getBatchSize());
        }

        @Test
        void setBatchSizeAllowsPositive() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setBatchSize(250);
            assertEquals(250, cfg.getBatchSize());
        }
    }

    @Nested
    class MaxSnippetLinesGuard {

        @Test
        void setMaxSnippetLinesRejectsZeroAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxSnippetLines(0);
            assertEquals(1, cfg.getMaxSnippetLines());
        }

        @Test
        void setMaxSnippetLinesRejectsNegativeAndClampsToOne() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxSnippetLines(-10);
            assertEquals(1, cfg.getMaxSnippetLines());
        }

        @Test
        void setMaxSnippetLinesAllowsPositive() {
            CodeIqConfig cfg = new CodeIqConfig();
            cfg.setMaxSnippetLines(100);
            assertEquals(100, cfg.getMaxSnippetLines());
        }
    }

    // ------------------------------------------------------------------ Graph sub-class

    @Nested
    class GraphSubProperties {

        @Test
        void graphGetterReturnsNonNull() {
            assertNotNull(new CodeIqConfig().getGraph());
        }

        @Test
        void graphPathIsSettable() {
            CodeIqConfig cfg = new CodeIqConfig();
            CodeIqConfig.Graph graph = new CodeIqConfig.Graph();
            graph.setPath("/custom/graph.db");
            cfg.setGraph(graph);
            assertEquals("/custom/graph.db", cfg.getGraph().getPath());
        }
    }
}
