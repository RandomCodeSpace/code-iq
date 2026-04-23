package io.github.randomcodespace.iq.intelligence.provenance;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.intelligence.ArtifactManifest;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.Provenance;
import io.github.randomcodespace.iq.intelligence.RepositoryIdentity;
import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes runtime artifact metadata on demand so graph-derived fields reflect the
 * currently loaded graph instead of the graph state at bean construction time.
 */
public class ArtifactMetadataProvider {
    private final String repositoryIdentity;
    private final String commitSha;
    private final Instant buildTimestamp;
    private final Map<String, String> extractorVersions;
    private final Map<String, Map<String, CapabilityLevel>> languageCapabilities;
    private final GraphStore graphStore;

    public ArtifactMetadataProvider(Path root, GraphStore graphStore) {
        RepositoryIdentity identity = RepositoryIdentity.resolve(root.toAbsolutePath().normalize());
        this.repositoryIdentity = identity.repoUrl() != null ? identity.repoUrl() : root.toAbsolutePath().normalize().toString();
        this.commitSha = identity.commitSha();
        this.buildTimestamp = identity.buildTimestamp();
        this.extractorVersions = Map.of("codeiq", "phase-4");
        this.languageCapabilities = buildLanguageCapabilities();
        this.graphStore = graphStore;
    }

    public ArtifactMetadata current() {
        long nodeCount = 0L;
        long edgeCount = 0L;
        if (graphStore != null) {
            try {
                nodeCount = graphStore.count();
                edgeCount = graphStore.countEdges();
            } catch (Exception ignored) {
                // Graph may still be empty or unavailable during startup.
            }
        }

        return new ArtifactMetadata(
                repositoryIdentity,
                commitSha,
                buildTimestamp,
                String.valueOf(Provenance.CURRENT_SCHEMA_VERSION),
                String.valueOf(ArtifactManifest.BUNDLE_FORMAT_VERSION),
                extractorVersions,
                languageCapabilities,
                ArtifactMetadata.computeIntegrityHash(nodeCount, edgeCount, commitSha)
        );
    }

    private static Map<String, Map<String, CapabilityLevel>> buildLanguageCapabilities() {
        Map<String, Map<String, CapabilityLevel>> langCaps = new LinkedHashMap<>();
        CapabilityMatrix.asSerializableMap().forEach((lang, dims) -> {
            Map<String, CapabilityLevel> dimMap = new LinkedHashMap<>();
            dims.forEach((dim, level) -> dimMap.put(dim, CapabilityLevel.valueOf(level)));
            langCaps.put(lang, Collections.unmodifiableMap(dimMap));
        });
        return Collections.unmodifiableMap(langCaps);
    }
}
