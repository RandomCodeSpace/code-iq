package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePack;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadataProvider;
import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Intelligence REST API — evidence packs, artifact metadata, and capability matrix.
 * Read-only. Active only in the {@code serving} profile.
 */
@RestController
@RequestMapping("/api/intelligence")
@Profile("serving")
public class IntelligenceController {

    private final EvidencePackAssembler assembler;
    private final ArtifactMetadataProvider artifactMetadataProvider;
    private final CodeIqConfig config;

    public IntelligenceController(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            EvidencePackAssembler assembler,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ArtifactMetadataProvider artifactMetadataProvider,
            CodeIqConfig config) {
        this.assembler = assembler;
        this.artifactMetadataProvider = artifactMetadataProvider;
        this.config = config;
    }

    /**
     * Assemble an evidence pack for a symbol or file path.
     *
     * <p>At least one of {@code symbol} or {@code file} must be provided.
     * The {@code file} parameter is path-traversal guarded with the same two-stage
     * (lexical {@code normalize} then {@link Path#toRealPath} re-check) guard used
     * by {@code GraphController.readFile} and the MCP {@code read_file} tool, so a
     * symlink inside the indexed repo cannot be used to leak off-tree files.
     *
     * @param symbol          symbol name to look up
     * @param file            file path relative to repo root (path traversal guarded)
     * @param maxSnippetLines max lines per snippet (optional, capped at config limit)
     * @param includeRefs     whether to include cross-reference nodes (default false)
     * @return assembled evidence pack
     */
    @GetMapping("/evidence")
    public EvidencePack getEvidence(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String file,
            @RequestParam(required = false) Integer maxSnippetLines,
            @RequestParam(defaultValue = "false") boolean includeRefs) {

        requireAssembler();

        // 400 when both are absent
        if ((symbol == null || symbol.isBlank()) && (file == null || file.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one of 'symbol' or 'file' must be provided.");
        }

        // Path-traversal guard on file param: two-stage lexical + symlink check.
        if (file != null && !file.isBlank()) {
            Path root;
            try {
                root = Path.of(config.getRootPath()).toRealPath();
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to resolve codebase root: " + e.getMessage());
            }
            Path candidate = root.resolve(file).normalize();
            if (!candidate.startsWith(root)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid file path: path traversal detected.");
            }
            // Resolve symlinks if the file exists on disk and re-check containment.
            // If the file is logical-only (graph reference, no on-disk file), the
            // lexical guard above is sufficient — there is no symlink to traverse.
            try {
                Path real = candidate.toRealPath();
                if (!real.startsWith(root)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid file path: path traversal detected.");
                }
            } catch (NoSuchFileException ignored) {
                // file may exist only as a graph reference — lexical guard already passed
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to resolve file path: " + e.getMessage());
            }
        }

        EvidencePackRequest request = new EvidencePackRequest(symbol, file, maxSnippetLines, includeRefs);
        return assembler.assemble(request, currentArtifactMetadata());
    }

    /**
     * Returns the artifact metadata loaded at serve startup.
     */
    @GetMapping("/manifest")
    public ArtifactMetadata getManifest() {
        if (artifactMetadataProvider == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Artifact metadata unavailable. Run 'enrich' first.");
        }
        return artifactMetadataProvider.current();
    }

    /**
     * Returns the full capability matrix as a JSON object.
     * Optionally filter by language.
     *
     * @param language optional language filter (e.g. "java", "python")
     */
    @GetMapping("/capabilities")
    public Map<String, Object> getCapabilities(
            @RequestParam(required = false) String language) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (language != null && !language.isBlank()) {
            result.put("language", language.strip().toLowerCase());
            result.put("capabilities", CapabilityMatrix.forLanguage(language).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().name().toLowerCase(),
                            e -> e.getValue().name(),
                            (a, b) -> a,
                            java.util.TreeMap::new)));
        } else {
            result.put("matrix", CapabilityMatrix.asSerializableMap());
        }
        return result;
    }

    private void requireAssembler() {
        if (assembler == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Intelligence service unavailable. Run 'enrich' first.");
        }
    }

    private ArtifactMetadata currentArtifactMetadata() {
        return artifactMetadataProvider != null ? artifactMetadataProvider.current() : null;
    }
}
