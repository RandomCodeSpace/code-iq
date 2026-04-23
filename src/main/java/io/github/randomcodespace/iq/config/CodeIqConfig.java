package io.github.randomcodespace.iq.config;

/**
 * Legacy flat configuration bean for Code IQ.
 *
 * <p>Historically bound to Spring Boot {@code @ConfigurationProperties("codeiq")}.
 * Task 11 moved bean production to {@link UnifiedConfigBeans#codeIqConfig}, which
 * adapts a {@link io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig}
 * (single source of truth) via {@link UnifiedConfigAdapter#toCodeIqConfig}. The
 * getter/setter surface is preserved unchanged so the ~100 call sites that still
 * depend on this bean continue to work.
 *
 * <p>This class is intentionally a plain POJO (no {@code @Configuration},
 * no {@code @ConfigurationProperties}); Spring Boot no longer instantiates it
 * from {@code application.yml}. Instantiable directly in tests via the public
 * no-arg constructor and setters.
 */
public class CodeIqConfig {

    /** Root path of the codebase to analyze. */
    private String rootPath = ".";

    /** Cache directory relative to repo root. */
    private String cacheDir = ".code-iq/cache";

    /** Maximum traversal depth for graph queries. */
    private int maxDepth = 10;

    /** Maximum radius for ego graph queries. */
    private int maxRadius = 10;

    /** Maximum number of file paths returned by the file-tree query before truncation. */
    private int maxFiles = 10000;

    /** Batch size for file processing during indexing (files per H2 flush). */
    private int batchSize = 500;

    /** Graph configuration sub-properties. */
    private Graph graph = new Graph();

    /** Whether to serve the React web UI. Set to false via --no-ui flag. */
    private boolean uiEnabled = true;

    /** Maximum lines per snippet returned in evidence packs (default 50). */
    private int maxSnippetLines = 50;

    public static class Graph {
        private String path = ".code-iq/graph/graph.db";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    /** Read-only mode for serving — no lock files, no writes. For read-only filesystems (AKS). */
    private boolean readOnly = false;

    /** Service name tag for multi-repo graph mode. */
    private String serviceName;

    // --- Getters and Setters ---

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getCacheDir() {
        return cacheDir;
    }

    public void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = Math.max(1, maxFiles);
    }

    public int getMaxRadius() {
        return maxRadius;
    }

    public void setMaxRadius(int maxRadius) {
        this.maxRadius = maxRadius;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Graph getGraph() {
        return graph;
    }

    public void setGraph(Graph graph) {
        this.graph = graph;
    }

    public boolean isUiEnabled() {
        return uiEnabled;
    }

    public void setUiEnabled(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    public int getMaxSnippetLines() {
        return maxSnippetLines;
    }

    public void setMaxSnippetLines(int maxSnippetLines) {
        this.maxSnippetLines = Math.max(1, maxSnippetLines);
    }
}
