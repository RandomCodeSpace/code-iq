package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.intelligence.RepositoryIdentity;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.ArtifactManifest;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.Provenance;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for OSSCodeIQ, bound to the "codeiq" prefix.
 */
@Configuration
@ConfigurationProperties(prefix = "codeiq")
public class CodeIqConfig {

    /** Root path of the codebase to analyze. */
    private String rootPath = ".";

    /** Cache directory name (legacy name kept for backward compatibility). */
    private String cacheDir = ".code-intelligence";

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
        private String path = ".osscodeiq/graph.db";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

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

    /**
     * Provides {@link ArtifactMetadata} as a Spring bean in the {@code serving} profile.
     *
     * <p>Metadata is derived at serve-startup from the analysed repository and the
     * populated Neo4j graph. {@code graphStore} is optional so serve can start even
     * when the graph has not been populated yet (the manifest endpoint returns 503 in
     * that case, handled by {@code IntelligenceController}).
     */
    @Bean
    @Profile("serving")
    public ArtifactMetadata artifactMetadata(
            @Autowired(required = false) GraphStore graphStore) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        RepositoryIdentity identity = RepositoryIdentity.resolve(root);

        long nodeCount = 0L;
        long edgeCount = 0L;
        if (graphStore != null) {
            try {
                nodeCount = graphStore.count();
                edgeCount = graphStore.countEdges();
            } catch (Exception ignored) {
                // Graph not yet populated — counts stay zero
            }
        }

        String integrityHash = ArtifactMetadata.computeIntegrityHash(
                nodeCount, edgeCount, identity.commitSha());

        Map<String, Map<String, CapabilityLevel>> langCaps = new LinkedHashMap<>();
        CapabilityMatrix.asSerializableMap().forEach((lang, dims) -> {
            Map<String, CapabilityLevel> dimMap = new LinkedHashMap<>();
            dims.forEach((dim, level) -> dimMap.put(dim, CapabilityLevel.valueOf(level)));
            langCaps.put(lang, Collections.unmodifiableMap(dimMap));
        });

        return new ArtifactMetadata(
                identity.repoUrl() != null ? identity.repoUrl() : root.toString(),
                identity.commitSha(),
                identity.buildTimestamp(),
                String.valueOf(Provenance.CURRENT_SCHEMA_VERSION),
                String.valueOf(ArtifactManifest.BUNDLE_FORMAT_VERSION),
                Map.of("code-iq", "phase-4"),
                Collections.unmodifiableMap(langCaps),
                integrityHash
        );
    }
}
